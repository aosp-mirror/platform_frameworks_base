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

/**
 * An instance of this class represents a connection to the surface
 * flinger, from which you can create one or more Surface instances that will
 * be composited to the screen.
 * {@hide}
 */
public final class SurfaceSession {
    // Note: This field is accessed by native code.
    private int mNativeClient; // SurfaceComposerClient*

    private static native int nativeCreate();
    private static native void nativeDestroy(int ptr);
    private static native void nativeKill(int ptr);

    /** Create a new connection with the surface flinger. */
    public SurfaceSession() {
        mNativeClient = nativeCreate();
    }

    /* no user serviceable parts here ... */
    @Override
    protected void finalize() throws Throwable {
        try {
            if (mNativeClient != 0) {
                nativeDestroy(mNativeClient);
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Forcibly detach native resources associated with this object.
     * Unlike destroy(), after this call any surfaces that were created
     * from the session will no longer work.
     */
    public void kill() {
        nativeKill(mNativeClient);
    }
}

