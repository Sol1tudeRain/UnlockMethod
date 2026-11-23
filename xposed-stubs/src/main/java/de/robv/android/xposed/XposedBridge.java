package de.robv.android.xposed;
import java.util.Set;
public final class XposedBridge {
    public static Set<XC_MethodHook.Unhook> hookAllMethods(
        Class<?> cls, String methodName, XC_MethodHook callback) { return null; }
    public static void log(String text) {}
    public static Object invokeOriginalMethod(
        java.lang.reflect.Member method, Object receiver, Object[] args) throws Throwable {
        return null;
    }
}
