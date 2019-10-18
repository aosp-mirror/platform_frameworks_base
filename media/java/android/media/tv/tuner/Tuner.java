/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner;

/**
 * Tuner is used to interact with tuner devices.
 *
 * @hide
 */
public final class Tuner implements AutoCloseable  {
    private static final String TAG = "MediaTvTuner";
    private static final boolean DEBUG = false;

    static {
        System.loadLibrary("media_tv_tuner");
        nativeInit();
    }

    public Tuner() {
        nativeSetup();
    }

    private long mNativeContext; // used by native jMediaTuner

    @Override
    public void close() {}

    /**
     * Native Initialization.
     */
    private static native void nativeInit();

    /**
     * Native setup.
     */
    private native void nativeSetup();
}
