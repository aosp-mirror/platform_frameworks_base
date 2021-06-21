/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

/**
 * An instance of this class represents a connection to the surface
 * flinger, from which you can create one or more Surface instances that will
 * be composited to the screen.
 * {@hide}
 */
public final class SurfaceSession {
    // Note: This field is accessed by native code.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private long mNativeClient; // SurfaceComposerClient*

    private static native long nativeCreate();
    private static native void nativeDestroy(long ptr);

    /** Create a new connection with the surface flinger. */
    @UnsupportedAppUsage
    public SurfaceSession() {
        mNativeClient = nativeCreate();
    }

    /* no user serviceable parts here ... */
    @Override
    protected void finalize() throws Throwable {
        try {
            kill();
        } finally {
            super.finalize();
        }
    }

    /**
     * Remove the reference to the native Session object. The native object may still exist if
     * there are other references to it, but it cannot be accessed from this Java object anymore.
     */
    @UnsupportedAppUsage
    public void kill() {
        if (mNativeClient != 0) {
            nativeDestroy(mNativeClient);
            mNativeClient = 0;
        }
    }
}

