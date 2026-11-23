package dev.notifingerprint;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * SystemUI-only, fail-closed notification-tap compatibility hook.
 * This never synthesizes biometric authentication, changes lockout,
 * or relaxes the device's StrongAuth policy.
 *
 * ROM class/field names vary: unsupported variants simply do nothing.
 */
public final class NotificationFingerprintHook implements IXposedHookLoadPackage {
    private static final long WINDOW_MS = 1750L;
    private static final AtomicLong LAST_TAP = new AtomicLong(0L);
    private static volatile Object manager;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"com.android.systemui".equals(load.packageName)) return;
        ClassLoader cl = load.classLoader;
        int clicks = 0;
        int bouncers = 0;

        Class<?> starter = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter", cl);
        if (starter != null) {
            try {
                XposedBridge.hookAllMethods(starter, "onNotificationClicked", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Object keyguard = field(p.thisObject, "mKeyguardStateController");
                        if (!bool(keyguard, "isShowing")) return;
                        Object m = field(p.thisObject, "mStatusBarKeyguardViewManager");
                        if (m != null) manager = m;
                        LAST_TAP.set(now());
                    }
                });
                clicks++;
            } catch (Throwable e) { log("starter hook: " + e); }
        }

        Class<?> mgr = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager", cl);
        if (mgr != null) {
            try {
                XposedBridge.hookAllMethods(mgr, "dismissWithAction", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        manager = p.thisObject;
                    }
                });
            } catch (Throwable e) { log("manager tracking: " + e); }
            try {
                XposedBridge.hookAllMethods(mgr, "showPrimaryBouncer", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        manager = p.thisObject;
                        if (isVoid(p) && canKeepFingerprint(p.thisObject)) {
                            LAST_TAP.set(0);
                            p.setResult(null);
                            log("held lockscreen at showPrimaryBouncer");
                        }
                    }
                });
                bouncers++;
            } catch (Throwable e) { log("manager bouncer: " + e); }
        }

        // Older ROM notification click handling.
        Class<?> clicker = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.notification.NotificationClicker", cl);
        if (clicker != null) {
            try {
                XposedBridge.hookAllMethods(clicker, "onClick", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (manager != null && showing(manager)) LAST_TAP.set(now());
                    }
                });
                clicks++;
            } catch (Throwable e) { log("clicker hook: " + e); }
        }

        for (String name : new String[] {
            "com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor",
            "com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor"
        }) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c == null) continue;
            try {
                XposedBridge.hookAllMethods(c, "show", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!isVoid(p) || p.args == null || p.args.length == 0 ||
                            !(p.args[0] instanceof Boolean)) return;
                        if (canKeepFingerprint(manager)) {
                            LAST_TAP.set(0);
                            p.setResult(null);
                            log("held lockscreen at PrimaryBouncerInteractor.show");
                        }
                    }
                });
                bouncers++;
            } catch (Throwable e) { log("interactor hook: " + e); }
        }
        log("loaded clickHooks=" + clicks + " bouncerHooks=" + bouncers);
    }

    private static boolean canKeepFingerprint(Object mgr) {
        long tap = LAST_TAP.get();
        long age = now() - tap;
        if (tap == 0 || age < 0 || age > WINDOW_MS || mgr == null || !showing(mgr))
            return false;

        Object monitor = field(mgr, "mKeyguardUpdateManager");
        if (monitor == null) monitor = field(mgr, "mKeyguardUpdateMonitor");
        if (monitor == null) return false;

        // If any of these checks cannot be verified, do not suppress credential UI.
        if (!bool(monitor, "isUdfpsEnrolled")) return false;
        if (bool(monitor, "isFingerprintLockedOut") ||
            bool(monitor, "isFingerprintLockedOutPermanent")) return false;

        Boolean allowed = optionalBool(monitor, "isUnlockingWithFingerprintAllowed");
        if (allowed == null) {
            try {
                Object result = XposedHelpers.callMethod(monitor,
                    "isUnlockingWithBiometricAllowed", true);
                allowed = (result instanceof Boolean) ? (Boolean) result : null;
            } catch (Throwable ignored) { return false; }
        }
        if (!Boolean.TRUE.equals(allowed)) return false;

        Object state = field(mgr, "mKeyguardStateController");
        if (Boolean.TRUE.equals(optionalBool(state, "isOccluded"))) return false;
        return true;
    }

    private static boolean showing(Object mgr) {
        return bool(field(mgr, "mKeyguardStateController"), "isShowing");
    }
    private static boolean isVoid(XC_MethodHook.MethodHookParam p) {
        return p.method instanceof Method && ((Method)p.method).getReturnType() == Void.TYPE;
    }
    private static Object field(Object target, String name) {
        if (target == null) return null;
        try { return XposedHelpers.getObjectField(target, name); }
        catch (Throwable ignored) { return null; }
    }
    private static boolean bool(Object target, String method) {
        return Boolean.TRUE.equals(optionalBool(target, method));
    }
    private static Boolean optionalBool(Object target, String method) {
        if (target == null) return null;
        try {
            Object result = XposedHelpers.callMethod(target, method);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Throwable ignored) { return null; }
    }
    private static long now() {
        try {
            Class<?> systemClock = Class.forName("android.os.SystemClock");
            return (Long) systemClock.getMethod("elapsedRealtime").invoke(null);
        } catch (Throwable ignored) {
            return System.nanoTime() / 1_000_000L;
        }
    }
    private static void log(String m) { XposedBridge.log("NotifFingerprint: " + m); }
}
