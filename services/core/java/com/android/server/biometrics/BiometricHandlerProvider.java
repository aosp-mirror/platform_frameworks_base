/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.biometrics;

import static android.os.Process.THREAD_PRIORITY_DEFAULT;
import static android.os.Process.THREAD_PRIORITY_DISPLAY;

import android.os.Handler;
import android.os.HandlerThread;

/**
 * This class provides the handler to process biometric operations.
 */
public class BiometricHandlerProvider {
    private static final BiometricHandlerProvider sBiometricHandlerProvider =
            new BiometricHandlerProvider();

    private Handler mBiometricsCallbackHandler;
    private Handler mFingerprintHandler;
    private Handler mFaceHandler;

    /**
     * @return an instance of {@link BiometricHandlerProvider} which contains the three
     *         threads needed for running biometric operations
     */
    public static BiometricHandlerProvider getInstance() {
        return sBiometricHandlerProvider;
    }

    private BiometricHandlerProvider() {}

    /**
    * @return the handler to process all biometric callback operations
    */
    public synchronized Handler getBiometricCallbackHandler() {
        if (mBiometricsCallbackHandler == null) {
            mBiometricsCallbackHandler = getNewHandler("BiometricsCallbackHandler",
                    THREAD_PRIORITY_DISPLAY);
        }
        return mBiometricsCallbackHandler;
    }

    /**
     * @return the handler to process all face related biometric operations
     */
    public synchronized Handler getFaceHandler() {
        if (mFaceHandler == null) {
            mFaceHandler = getNewHandler("FaceHandler", THREAD_PRIORITY_DEFAULT);
        }
        return mFaceHandler;
    }

    /**
     * @return the handler to process all fingerprint related biometric operations
     */
    public synchronized Handler getFingerprintHandler() {
        if (mFingerprintHandler == null) {
            mFingerprintHandler = getNewHandler("FingerprintHandler", THREAD_PRIORITY_DEFAULT);
        }
        return mFingerprintHandler;
    }

    private Handler getNewHandler(String tag, int priority) {
        HandlerThread handlerThread = new HandlerThread(tag, priority);
        handlerThread.start();
        return new Handler(handlerThread.getLooper());
    }
}
