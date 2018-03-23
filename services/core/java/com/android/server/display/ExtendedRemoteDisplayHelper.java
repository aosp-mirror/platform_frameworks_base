/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *      with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *      contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server.display;

import android.content.Context;
import android.media.RemoteDisplay;
import android.os.Handler;
import android.util.Slog;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

class ExtendedRemoteDisplayHelper {
    private static final String TAG = "ExtendedRemoteDisplayHelper";
    private static final boolean DEBUG = false;

    // ExtendedRemoteDisplay class
    // ExtendedRemoteDisplay is an enhanced RemoteDisplay.
    // It has similar interface as RemoteDisplay class.
    private static Class sExtRemoteDisplayClass;

    // Method object for the API ExtendedRemoteDisplay.Listen
    // ExtendedRemoteDisplay.Listen has the same API signature as RemoteDisplay.Listen
    // except for an additional argument to pass the Context.
    private static Method sExtRemoteDisplayListen;

    // Method Object for the API ExtendedRemoteDisplay.Dispose
    // ExtendedRemoteDisplay.Dispose follows the same API signature as RemoteDisplay.Dispose.
    private static Method sExtRemoteDisplayDispose;

    static {
        // Check availability of ExtendedRemoteDisplay runtime
        try {
            sExtRemoteDisplayClass = Class.forName("com.qualcomm.wfd.ExtendedRemoteDisplay");
        } catch (Throwable t) {
            Slog.i(TAG, "ExtendedRemoteDisplay: not available");
        }

        if (sExtRemoteDisplayClass != null) {
            // If ExtendedRemoteDisplay is available find the methods
            Slog.i(TAG, "ExtendedRemoteDisplay: is available, finding methods");
            try {
                Class args[] = { String.class, RemoteDisplay.Listener.class,
                        Handler.class, Context.class };
                sExtRemoteDisplayListen =
                        sExtRemoteDisplayClass.getDeclaredMethod("listen", args);
            } catch (Throwable t) {
                Slog.i(TAG, "ExtendedRemoteDisplay.listen: not available");
            }

            try {
                Class args[] = {};
                sExtRemoteDisplayDispose =
                        sExtRemoteDisplayClass.getDeclaredMethod("dispose", args);
            } catch (Throwable t) {
                Slog.i(TAG, "ExtendedRemoteDisplay.dispose: not available");
            }
        }
    }

    /**
     * Starts listening for displays to be connected on the specified interface.
     *
     * @param iface The interface address and port in the form "x.x.x.x:y".
     * @param listener The listener to invoke when displays are connected or disconnected.
     * @param handler The handler on which to invoke the listener.
     * @param context The current service context.
     *  */
    public static Object listen(String iface, RemoteDisplay.Listener listener,
            Handler handler, Context context) {
        Object extRemoteDisplay = null;
        if (DEBUG) Slog.i(TAG, "ExtendedRemoteDisplay.listen");

        if (sExtRemoteDisplayListen != null && sExtRemoteDisplayDispose != null) {
            try {
                extRemoteDisplay = sExtRemoteDisplayListen.invoke(null,
                        iface, listener, handler, context);
            } catch (InvocationTargetException e) {
                Slog.e(TAG, "ExtendedRemoteDisplay.listen: InvocationTargetException");
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                } else if (cause instanceof Error) {
                    throw (Error) cause;
                } else {
                    throw new RuntimeException(e);
                }
            } catch (IllegalAccessException e) {
                Slog.e(TAG, "ExtendedRemoteDisplay.listen: IllegalAccessException", e);
            }
        }
        return extRemoteDisplay;
    }

    /**
     * Disconnects the remote display and stops listening for new connections.
     */
    public static void dispose(Object extRemoteDisplay) {
        if (DEBUG) Slog.i(TAG, "ExtendedRemoteDisplay.dispose");
        try {
            sExtRemoteDisplayDispose.invoke(extRemoteDisplay);
        } catch (InvocationTargetException e) {
            Slog.e(TAG, "ExtendedRemoteDisplay.dispose: InvocationTargetException");
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else if (cause instanceof Error) {
                throw (Error) cause;
            } else {
                throw new RuntimeException(e);
            }
        } catch (IllegalAccessException e) {
            Slog.e(TAG, "ExtendedRemoteDisplay.dispose: IllegalAccessException", e);
        }
    }

    /**
     * Checks if ExtendedRemoteDisplay is available
     */
    public static boolean isAvailable() {
        return (sExtRemoteDisplayClass != null && sExtRemoteDisplayDispose != null &&
                sExtRemoteDisplayListen != null);
    }
}
