package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-time stub of the real Xposed callback class.
 * NOT packaged into the APK: at runtime the framework provides the real one.
 */
public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public class Unhook {
        public final Member hookMethod;

        public Unhook(Member hookMethod) {
            this.hookMethod = hookMethod;
        }

        public void unhook() {
        }
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        private Object result = null;
        private Throwable throwable = null;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }
}
