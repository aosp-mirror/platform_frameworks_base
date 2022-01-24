/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.log;

import android.content.Context;
import android.hardware.biometrics.IBiometricContextListener;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Singleton;
import android.util.Slog;

import com.android.internal.statusbar.IStatusBarService;

/**
 * A default provider for {@link BiometricContext}.
 */
class BiometricContextProvider implements BiometricContext {

    private static final String TAG = "BiometricContextProvider";

    private BiometricContextProvider() {}

    static final Singleton<BiometricContextProvider> sInstance =
            new Singleton<BiometricContextProvider>() {
        @Override
        protected BiometricContextProvider create() {
            final BiometricContextProvider provider =  new BiometricContextProvider();
            try {
                IStatusBarService.Stub.asInterface(
                        ServiceManager.getService(Context.STATUS_BAR_SERVICE))
                            .setBiometicContextListener(new IBiometricContextListener.Stub() {
                                @Override
                                public void onDozeChanged(boolean isDozing) {
                                    provider.mIsDozing = isDozing;
                                }
                            });
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to register biometric context listener", e);
            }
            return provider;
        }
    };

    private boolean mIsDozing = false;

    @Override
    public boolean isDozing() {
        return mIsDozing;
    }
}
