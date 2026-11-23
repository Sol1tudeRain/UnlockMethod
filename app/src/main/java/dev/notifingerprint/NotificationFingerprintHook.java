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
 * AOD notification fingerprint priority module.
 *
 * v0.5 preserves SystemUI's dismissWithAction/showBouncer entrypoints (which
 * register the post-auth notification launch). It only redirects the final
 * credential bouncer/scene when the ROM confirms that the real UDFPS alternate
 * bouncer is currently permitted and becomes visible.
 *
 * Authentication, biometric lockouts and strong-auth policy remain intact.
 */
public final class NotificationFingerprintHook implements IXposedHookLoadPackage {
    private static final long NORMAL_WINDOW_MS = 3500L;
    private static final long AOD_WINDOW_MS = 10000L;
    private static final AtomicLong LAST_NOTIFICATION_TAP = new AtomicLong(0L);
    private static final AtomicLong LAST_AOD_CLICK_HINT = new AtomicLong(0L);
    private static volatile boolean tapWasAod;
    private static volatile Object keyguardViewManager;

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
                            if (!bool(state, "isShowing")) {
                                log("CLICK not tracked (keyguard not showing or state unavailable)");
                                return;
                            }

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
        final Class<?> rowType = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", cl);
        Class<?> clicker = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.notification.NotificationClicker", cl);
        if (clicker != null) {
            try {
                XposedBridge.hookAllMethods(clicker, "onClick", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args == null || p.args.length == 0 ||
                            !(p.args[0] instanceof View)) return;
                        View view = (View)p.args[0];
                        if (rowType == null || !rowType.isInstance(view)) return;
                        Object service = view.getContext().getSystemService(Context.POWER_SERVICE);
                        boolean wasInteractive = !(service instanceof PowerManager)
                            || ((PowerManager) service).isInteractive();
                        Object starter = field(p.thisObject, "mNotificationActivityStarter");
                        if (!wasInteractive) {
                            LAST_AOD_CLICK_HINT.set(now());
                            log("AOD_CLICK_HINT before wake");
                        }
                        // IMPORTANT: onNotificationClicked is not invoked on the
                        // user's ROM. Record the actual notification row tap here
                        // BEFORE NotificationClicker wakes the AOD display.
                        recordTap(!wasInteractive);
                        log("CLICK_ENTRY NotificationClicker.onClick interactive=" +
                            wasInteractive + " starter=" +
                            (starter == null ? "missing" : starter.getClass().getName()));
                    }
                });
                log("HOOK_OK NotificationClicker.onClick (direct tap tracking)");
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
                            if (activeTap()) {
                                if (isDozing(p.thisObject)) tapWasAod = true;
                                log("PATH dismissWithAction dozing=" + isDozing(p.thisObject) +
                                    " " + alternateStatus(p.thisObject));
                            }
                        }
                    });
            } catch (Throwable t) { log("dismiss action observer unavailable: " + t); }

            try {
                XposedBridge.hookAllMethods(manager, "launchPendingWakeupAction",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            keyguardViewManager = p.thisObject;
                            if (activeTap()) {
                                log("PATH launchPendingWakeupAction dozing=" + isDozing(p.thisObject) +
                                    " AOD=" + tapWasAod + " " + alternateStatus(p.thisObject));
                            }
                        }
                    });
            } catch (Throwable t) { log("wakeup observer unavailable: " + t); }

            try {
                XposedBridge.hookAllMethods(manager, "onDozingChanged", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        keyguardViewManager = p.thisObject;
                        if (activeTap()) log("PATH onDozingChanged to=" +
                            (p.args != null && p.args.length > 0 ? p.args[0] : "?"));
                    }
                });
            } catch (Throwable t) { log("doze observer unavailable: " + t); }
            // SceneContainer and legacy ROMs both call showBouncer when an
            // authenticated notification is pending. The original dismiss
            // action is already registered; keep it intact.
            try {
                XposedBridge.hookAllMethods(manager, "showBouncer", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        keyguardViewManager = p.thisObject;
                        if (!activeTap()) return;
                        log("PRIMARY showBouncer; return=" + returnType(p) +
                            " " + alternateStatus(p.thisObject));
                        // Navigation safety hotfix: NEVER suppress this method.
                        // SystemUI owns its keyguard dismiss and notification action.
                        log("SAFE_PASS showBouncer (original action preserved)");
                    }
                });
                log("HOOK_OK StatusBarKeyguardViewManager.showBouncer");
            } catch (Throwable t) { log("showBouncer hook unavailable: " + brief(t)); }
            try {
                XposedBridge.hookAllMethods(manager, "showPrimaryBouncer",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            keyguardViewManager = p.thisObject;
                            if (!activeTap()) return;
                            log("PRIMARY showPrimaryBouncer requested; " + alternateStatus(p.thisObject));
                            if (showSystemAlternateBouncer(p.thisObject)) {
                                LAST_NOTIFICATION_TAP.set(0L);
                                tapWasAod = false;
                                p.setResult(null);
                                log("FP_PRIORITY manager.showPrimaryBouncer -> UDFPS");
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
                        if (mgr == null || !activeTap() ||
                            p.args == null || p.args.length == 0 ||
                            !(p.args[0] instanceof Boolean)) return;
                        Object declared = field(mgr, "mPrimaryBouncerInteractor");
                        if (declared != null && declared != p.thisObject) return;
                        Class<?> ret = returnType(p);
                        log("PRIMARY interactor.show requested; return=" + 
                            (ret == null ? "unknown" : ret.getName()) +
                            " " + alternateStatus(mgr));
                        // This is the last-resort primary bouncer entrypoint.
                        // Dismissal actions are already registered by SystemUI.
                        if ((ret == Void.TYPE || ret == Boolean.TYPE) &&
                                showSystemAlternateBouncer(mgr)) {
                            LAST_NOTIFICATION_TAP.set(0L);
                            tapWasAod = false;
                            p.setResult(ret == Boolean.TYPE ? Boolean.TRUE : null);
                            log("FP_PRIORITY PrimaryBouncerInteractor.show -> UDFPS");
                        }
                    }
                });
                log("installed primary-interactor redirect");
            } catch (Throwable t) { log("primary-interactor redirect unavailable: " + t); }
        }
        Class<?> scene = XposedHelpers.findClassIfExists(
            "com.android.systemui.scene.domain.interactor.SceneInteractor", cl);
        if (scene != null) {
            for (String methodName : new String[] {"changeScene", "snapToScene"}) {
                final String route = methodName;
                try {
                    XposedBridge.hookAllMethods(scene, methodName, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!activeTap() ||
                                p.args == null || p.args.length < 2) return;
                            String target = String.valueOf(p.args[0]);
                            // Trace scene changes before filtering. The user's
                            // SceneContainer may choose a different target string.
                            log("SCENE_REQUEST " + route + " target=" + target +
                                " reason=" + String.valueOf(p.args[1]) +
                                " return=" + returnType(p));
                            // Only the known Bouncer target may be redirected.
                            if (!(target.equals("Bouncer") || target.endsWith(".Bouncer") ||
                                  target.endsWith("$Bouncer") || target.endsWith("(Bouncer)")))
                                return;
                            Object mgr = keyguardViewManager;
                            log("SCENE " + route + " -> Bouncer; reason=" +
                                String.valueOf(p.args[1]) + " manager=" + (mgr != null) +
                                " " + alternateStatus(mgr));
                            // When SceneContainer bypasses the legacy bouncer, redirect
                            // only a notification-linked Bouncer scene transition.
                            // We do NOT intercept the outer dismissWithAction() or
                            // showBouncer() methods that own the notification action.
                            if (returnsVoid(p) && mgr != null && showSystemAlternateBouncer(mgr)) {
                                LAST_NOTIFICATION_TAP.set(0L);
                                tapWasAod = false;
                                p.setResult(null);
                                log("FP_PRIORITY SceneInteractor." + route + " -> UDFPS");
                            }
                        }
                    });
                    log("HOOK_OK SceneInteractor." + route);
                } catch (Throwable t) { log("SceneInteractor hook unavailable: " + t); }
            }
        } else {
            log("HOOK_MISSING SceneInteractor (legacy ROM)");
        }
        log("v0.5 FINGERPRINT PRIORITY: late bouncer redirect; dismissWithAction untouched");
    }

    private static void recordTap(boolean aod) {
        long current = now();
        long hint = LAST_AOD_CLICK_HINT.getAndSet(0L);
        tapWasAod = aod || (hint > 0 && current >= hint && current - hint <= 2000L);
        LAST_NOTIFICATION_TAP.set(current);
        log("CLICK notification on " + (tapWasAod ? "AOD/pulse" : "lockscreen") +
            " manager=" + (keyguardViewManager != null));
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

    /**
     * Request the platform UDFPS bouncer. Never fake eligibility or a successful
     * biometric. Do not alter KeyguardDismissActionInteractor, post-auth actions,
     * PendingIntent or launch options: SystemUI retains all of them.
     */
    private static boolean showSystemAlternateBouncer(Object mgr) {
        if (mgr == null || !showing(mgr) || isDozing(mgr)) {
            log("FP_SKIP keyguard missing or still dozing");
            return false;
        }
        Object state = field(mgr, "mKeyguardStateController");
        if (Boolean.TRUE.equals(optBool(state, "isOccluded"))) {
            log("FP_SKIP occluded");
            return false;
        }
        Object alternate = field(mgr, "mAlternateBouncerInteractor");
        if (alternate == null) {
            log("FP_SKIP missing alternate interactor");
            return false;
        }
        Boolean eligible = optBool(alternate, "canShowAlternateBouncerForFingerprint");
        if (!Boolean.TRUE.equals(eligible)) {
            log("FP_SKIP platform eligibility=" + eligible);
            return false;
        }
        try {
            try {
                XposedHelpers.callMethod(alternate, "forceShow");
            } catch (Throwable t) {
                if (!missingMethod(t)) throw t;
                Object answer = XposedHelpers.callMethod(alternate, "show");
                log("FP legacy show result=" + answer);
            }
            if (!Boolean.TRUE.equals(optBool(alternate, "isVisibleState"))) {
                log("FP_SKIP alternate UI not visible after show");
                return false;
            }
            try {
                XposedHelpers.callMethod(mgr, "updateAlternateBouncerShowing", true);
            } catch (Throwable t) {
                // Non-fatal: the repository's visible-state flag is authoritative.
                log("FP_NOTE UI sync unavailable: " + brief(t));
            }
            log("FP_SHOWN fingerprint alternate bouncer visible");
            return true;
        } catch (Throwable t) {
            log("FP_FAIL alternate bouncer: " + brief(t));
            return false;
        }
    }

    private static boolean missingMethod(Throwable t) {
        while (t != null) {
            if (t instanceof NoSuchMethodError || t instanceof NoSuchMethodException) return true;
            t = t.getCause();
        }
        return false;
    }

    private static String alternateStatus(Object mgr) {
        if (mgr == null) return "alt=(no manager)";
        Object alternate = field(mgr, "mAlternateBouncerInteractor");
        if (alternate == null) return "alt=(missing interactor)";
        return "dozing=" + isDozing(mgr) +
            " eligible=" + optBool(alternate, "canShowAlternateBouncerForFingerprint") +
            " visible=" + optBool(alternate, "isVisibleState");
    }
    private static String brief(Throwable t) {
        return t.getClass().getSimpleName() + 
            (t.getMessage() == null ? "" : " " + t.getMessage());
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
        android.util.Log.i("NotifFingerprint", message);
    }
}
