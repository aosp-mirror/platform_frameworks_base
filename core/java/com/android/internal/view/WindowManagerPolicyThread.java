package com.android.internal.view;

import android.os.Looper;

/**
 * Static storage of the thread running the window manager policy, to
 * share with others.
 */
public class WindowManagerPolicyThread {
    static Thread mThread;
    static Looper mLooper;
    
    public static void set(Thread thread, Looper looper) {
        mThread = thread;
        mLooper = looper;
    }
    
    public static Thread getThread() {
        return mThread;
    }
    
    public static Looper getLooper() {
        return mLooper;
    }
}
