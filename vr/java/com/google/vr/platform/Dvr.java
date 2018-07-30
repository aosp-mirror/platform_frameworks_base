package com.google.vr.platform;

/**
 * Class to load the dvr api.
 * @hide
 */
public class Dvr {
    /**
     * Opens a shared library containing the dvr api and returns the handle to it.
     *
     * @return A Long object describing the handle returned by dlopen.
     */
    public static Long loadLibrary() {
        // Load a thin JNI library that runs dlopen on request.
        System.loadLibrary("dvr_loader");

        // Performs dlopen on the library and returns the handle.
        return nativeLoadLibrary("libdvr.so");
    }

    private static native long nativeLoadLibrary(String library);
}
