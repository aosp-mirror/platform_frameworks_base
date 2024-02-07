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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * This class provides the handler to process biometric operations.
 */
public class BiometricHandlerProvider {
    private static final BiometricHandlerProvider sBiometricHandlerProvider =
            new BiometricHandlerProvider();

    private final Handler mBiometricsCallbackHandler;
    private final Handler mFingerprintHandler;
    private final Handler mFaceHandler;

    /**
     * @return an instance of {@link BiometricHandlerProvider} which contains the three
     *         threads needed for running biometric operations
     */
    public static BiometricHandlerProvider getInstance() {
        return sBiometricHandlerProvider;
    }

    private BiometricHandlerProvider() {
        mBiometricsCallbackHandler = getNewHandler("BiometricsCallbackHandler");
        mFingerprintHandler = getNewHandler("FingerprintHandler");
        mFaceHandler = getNewHandler("FaceHandler");
    }

    /**
    * @return the handler to process all biometric callback operations
    */
    public synchronized Handler getBiometricCallbackHandler() {
        return mBiometricsCallbackHandler;
    }

    /**
     * @return the handler to process all face related biometric operations
     */
    public synchronized Handler getFaceHandler() {
        return mFaceHandler;
    }

    /**
     * @return the handler to process all fingerprint related biometric operations
     */
    public synchronized Handler getFingerprintHandler() {
        return mFingerprintHandler;
    }

    private Handler getNewHandler(String tag) {
        if (Flags.deHidl()) {
            HandlerThread handlerThread = new HandlerThread(tag);
            handlerThread.start();
            return new Handler(handlerThread.getLooper());
        }
        return new Handler(Looper.getMainLooper());
    }
}
