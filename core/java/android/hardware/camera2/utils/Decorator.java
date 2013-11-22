
package android.hardware.camera2.utils;

import java.lang.reflect.*;

/**
 * This is an implementation of the 'decorator' design pattern using Java's proxy mechanism.
 *
 * @see android.hardware.camera2.utils.Decorator#newInstance
 *
 * @hide
 */
public class Decorator<T> implements InvocationHandler {

    public interface DecoratorListener {
        /**
         * This method is called before the target method is invoked
         * @param args arguments to target method
         * @param m Method being called
         */
        void onBeforeInvocation(Method m, Object[] args);
        /**
         * This function is called after the target method is invoked
         * if there were no uncaught exceptions
         * @param args arguments to target method
         * @param m Method being called
         * @param result return value of target method
         */
        void onAfterInvocation(Method m, Object[] args, Object result);
        /**
         * This method is called only if there was an exception thrown by the target method
         * during its invocation.
         *
         * @param args arguments to target method
         * @param m Method being called
         * @param t Throwable that was thrown
         * @return false to rethrow exception, true if the exception was handled
         */
        boolean onCatchException(Method m, Object[] args, Throwable t);
        /**
         * This is called after the target method is invoked, regardless of whether or not
         * there were any exceptions.
         * @param args arguments to target method
         * @param m Method being called
         */
        void onFinally(Method m, Object[] args);
    }

    private final T mObject;
    private final DecoratorListener mListener;

    /**
     * Create a decorator wrapping the specified object's method calls.
     *
     * @param obj the object whose method calls you want to intercept
     * @param listener the decorator handler for intercepted method calls
     * @param <T> the type of the element you want to wrap. This must be an interface.
     * @return a wrapped interface-compatible T
     */
    @SuppressWarnings("unchecked")
    public static<T> T newInstance(T obj, DecoratorListener listener) {
        return (T)java.lang.reflect.Proxy.newProxyInstance(
                obj.getClass().getClassLoader(),
                obj.getClass().getInterfaces(),
                new Decorator<T>(obj, listener));
    }

    private Decorator(T obj, DecoratorListener listener) {
        this.mObject = obj;
        this.mListener = listener;
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args)
            throws Throwable
    {
        Object result = null;
        try {
            mListener.onBeforeInvocation(m, args);
            result = m.invoke(mObject, args);
            mListener.onAfterInvocation(m, args, result);
        } catch (InvocationTargetException e) {
            Throwable t = e.getTargetException();
            if (!mListener.onCatchException(m, args, t)) {
                throw t;
            }
        } finally {
            mListener.onFinally(m, args);
        }
        return result;
    }
}
