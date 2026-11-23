package dev.notifingerprint;

import java.lang.reflect.Method;
import android.content.Context;
import android.os.PowerManager;
import android.view.View;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Notification-tap-only request for SystemUI's real UDFPS alternate bouncer.
 *
 * AOD notification clicks can be handled after the Doze-to-keyguard wakeup,
 * hence the longer time window for an AOD click.  We never synthesize a
 * biometric success, bypass StrongAuth, or launch the notification ourselves.
 * SystemUI still owns the original pending OnDismissAction.
 *
 * Fail-closed: if any required ROM method/instance is missing, do nothing.
 */
public final class NotificationFingerprintHook implements IXposedHookLoadPackage {
    private static final long NORMAL_WINDOW_MS = 2500L;
    private static final long AOD_WINDOW_MS = 8000L;
    private static final AtomicLong LAST_NOTIFICATION_TAP = new AtomicLong(0L);
    private static final AtomicLong LAST_AOD_CLICK_HINT = new AtomicLong(0L);
    private static volatile boolean tapWasAod;
    private static volatile Object keyguardViewManager;
    private static final ThreadLocal<Boolean> REDIRECTING = new ThreadLocal<Boolean>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"com.android.systemui".equals(load.packageName)) return;
        final ClassLoader cl = load.classLoader;

        Class<?> starter = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter", cl);
        if (starter != null) {
            try {
                XposedBridge.hookAllMethods(starter, "onNotificationClicked",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Object state = field(p.thisObject, "mKeyguardStateController");
                            if (!bool(state, "isShowing")) return;

                            Object mgr = field(p.thisObject, "mStatusBarKeyguardViewManager");
                            if (mgr != null) keyguardViewManager = mgr;

                            Object controller = field(p.thisObject, "mStatusBarStateController");
                            boolean fromAod = isDozing(mgr) || bool(controller, "isDozing");
                            recordTap(fromAod);
                        }
                    });
                log("installed notification click handler");
            } catch (Throwable t) { log("click handler unavailable: " + t); }
        }

        // New and old notification clickers both wake the display before
        // dispatching the action. We deliberately do not intercept their
        // wakeUpIfDozing calls, nor trigger a second notification click.
        Class<?> clicker = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.notification.NotificationClicker", cl);
        if (clicker != null) {
            try {
                XposedBridge.hookAllMethods(clicker, "onClick", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        // Capture AOD before NotificationClicker wakes the display.
                        // The manager may not yet be cached on the first tap.
                        if (p.args != null && p.args.length > 0 &&
                                p.args[0] instanceof View) {
                            View view = (View) p.args[0];
                            Object service = view.getContext().getSystemService(
                                Context.POWER_SERVICE);
                            if (service instanceof PowerManager &&
                                    !((PowerManager) service).isInteractive()) {
                                LAST_AOD_CLICK_HINT.set(now());
                            }
                        }
                    }
                });
                log("installed AOD click observer");
            } catch (Throwable t) { log("AOD click observer unavailable: " + t); }
        }

        Class<?> manager = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager", cl);
        if (manager != null) {
            try {
                XposedBridge.hookAllMethods(manager, "dismissWithAction",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            keyguardViewManager = p.thisObject;
                            if (activeTap() && isDozing(p.thisObject)) {
                                tapWasAod = true;
                                log("AOD dismiss action pending wakeup; leaving action intact");
                            }
                        }
                    });
            } catch (Throwable t) { log("dismiss action observer unavailable: " + t); }

            try {
                XposedBridge.hookAllMethods(manager, "launchPendingWakeupAction",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            keyguardViewManager = p.thisObject;
                            if (activeTap() && tapWasAod) {
                                log("AOD wakeup: allowing original pending action to resume");
                            }
                        }
                    });
            } catch (Throwable t) { log("wakeup observer unavailable: " + t); }

            try {
                XposedBridge.hookAllMethods(manager, "showPrimaryBouncer",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            keyguardViewManager = p.thisObject;
                            if (!returnsVoid(p) || !activeTap() || isRedirecting()) return;
                            if (showSystemAlternateBouncer(p.thisObject)) {
                                LAST_NOTIFICATION_TAP.set(0);
                                tapWasAod = false;
                                p.setResult(null);
                                log("used UDFPS alternate bouncer instead of pattern (manager)");
                            }
                        }
                    });
                log("installed primary-bouncer redirect");
            } catch (Throwable t) { log("primary-bouncer redirect unavailable: " + t); }
        }

        // Some SystemUI versions call the primary interactor directly instead
        // of going through StatusBarKeyguardViewManager.showPrimaryBouncer().
        // Verify that it belongs to the very same tracked manager before
        // applying the redirect.
        for (String name : new String[] {
            "com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor",
            "com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor"
        }) {
            Class<?> primary = XposedHelpers.findClassIfExists(name, cl);
            if (primary == null) continue;
            try {
                XposedBridge.hookAllMethods(primary, "show", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Object mgr = keyguardViewManager;
                        if (mgr == null || !activeTap() || isRedirecting() ||
                            p.args == null || p.args.length == 0 ||
                            !(p.args[0] instanceof Boolean)) return;
                        if (field(mgr, "mPrimaryBouncerInteractor") != p.thisObject) return;
                        Class<?> ret = returnType(p);
                        if (ret != Void.TYPE && ret != Boolean.TYPE) return;
                        if (showSystemAlternateBouncer(mgr)) {
                            LAST_NOTIFICATION_TAP.set(0);
                            tapWasAod = false;
                            p.setResult(ret == Boolean.TYPE ? Boolean.TRUE : null);
                            log("used UDFPS alternate bouncer instead of pattern (interactor)");
                        }
                    }
                });
                log("installed primary-interactor redirect");
            } catch (Throwable t) { log("primary-interactor redirect unavailable: " + t); }
        }
        log("v0.2.1 AOD notification hook ready");
    }

    private static void recordTap(boolean aod) {
        long current = now();
        long hint = LAST_AOD_CLICK_HINT.getAndSet(0L);
        tapWasAod = aod || (hint > 0 && current >= hint && current - hint <= 2000L);
        LAST_NOTIFICATION_TAP.set(current);
        log("notification tapped on " + (tapWasAod ? "AOD/pulse" : "lockscreen"));
    }

    private static boolean activeTap() {
        long t = LAST_NOTIFICATION_TAP.get();
        if (t == 0L) return false;
        long age = now() - t;
        if (age >= 0L && age <= (tapWasAod ? AOD_WINDOW_MS : NORMAL_WINDOW_MS)) return true;
        LAST_NOTIFICATION_TAP.compareAndSet(t, 0L);
        tapWasAod = false;
        return false;
    }

    private static boolean showSystemAlternateBouncer(Object mgr) {
        if (mgr == null || !showing(mgr) || isDozing(mgr)) return false;
        Object state = field(mgr, "mKeyguardStateController");
        if (Boolean.TRUE.equals(optBool(state, "isOccluded"))) return false;

        Object alternate = field(mgr, "mAlternateBouncerInteractor");
        if (alternate == null ||
            !Boolean.TRUE.equals(optBool(alternate, "canShowAlternateBouncerForFingerprint"))) {
            return false;
        }

        // The ROM's own canShowAlternateBouncerForFingerprint() checks
        // biometric eligibility, lockout, dozing and bouncer state.
        // Only the ROM performs biometric authentication.
        REDIRECTING.set(Boolean.TRUE);
        try {
            XposedHelpers.callMethod(alternate, "forceShow");
            if (!Boolean.TRUE.equals(optBool(alternate, "isVisibleState"))) return false;

            // Keep SystemUI scrim, accessibility, and fingerprint UI in sync.
            // This method is normally called from the original showBouncer().
            XposedHelpers.callMethod(mgr, "updateAlternateBouncerShowing", true);
            return true;
        } catch (Throwable t) {
            log("alternate bouncer redirect failed; keeping original credential path: " + t);
            return false;
        } finally {
            REDIRECTING.remove();
        }
    }

    private static boolean showing(Object mgr) {
        return bool(field(mgr, "mKeyguardStateController"), "isShowing");
    }
    private static boolean isDozing(Object mgr) {
        if (mgr == null) return false;
        Object value = field(mgr, "mDozing");
        if (value instanceof Boolean) return (Boolean) value;
        return bool(field(mgr, "mStatusBarStateController"), "isDozing");
    }
    private static boolean returnsVoid(XC_MethodHook.MethodHookParam p) {
        return returnType(p) == Void.TYPE;
    }
    private static Class<?> returnType(XC_MethodHook.MethodHookParam p) {
        return (p.method instanceof Method) ? ((Method) p.method).getReturnType() : null;
    }
    private static boolean isRedirecting() {
        return Boolean.TRUE.equals(REDIRECTING.get());
    }
    private static Object field(Object target, String name) {
        if (target == null) return null;
        try { return XposedHelpers.getObjectField(target, name); }
        catch (Throwable ignored) { return null; }
    }
    private static boolean bool(Object target, String method) {
        return Boolean.TRUE.equals(optBool(target, method));
    }
    private static Boolean optBool(Object target, String method) {
        if (target == null) return null;
        try {
            Object result = XposedHelpers.callMethod(target, method);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Throwable ignored) { return null; }
    }
    private static long now() {
        return android.os.SystemClock.elapsedRealtime();
    }
    private static void log(String message) {
        XposedBridge.log("NotifFingerprint: " + message);
    }
}
