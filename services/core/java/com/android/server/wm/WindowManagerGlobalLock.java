package com.android.server.wm;

/**
 * Class that is used to generate an instance of the WM global lock. We are only doing this because
 * we need a class for the pattern used in frameworks/base/services/core/Android.bp for CPU boost
 * in the WM critical section.
 */
public class WindowManagerGlobalLock {
}
