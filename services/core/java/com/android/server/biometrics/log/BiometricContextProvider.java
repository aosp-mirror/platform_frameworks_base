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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.OperationContext;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Singleton;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A default provider for {@link BiometricContext}.
 */
class BiometricContextProvider implements BiometricContext {

    private static final String TAG = "BiometricContextProvider";

    static final Singleton<BiometricContextProvider> sInstance =
            new Singleton<BiometricContextProvider>() {
                @Override
                protected BiometricContextProvider create() {
                    return new BiometricContextProvider(IStatusBarService.Stub.asInterface(
                            ServiceManager.getService(
                                    Context.STATUS_BAR_SERVICE)), null /* handler */);
                }
            };

    @NonNull
    private final Map<OperationContext, Consumer<OperationContext>> mSubscribers =
            new ConcurrentHashMap<>();

    @VisibleForTesting
    BiometricContextProvider(@NonNull IStatusBarService service, @Nullable Handler handler) {
        try {
            service.setBiometicContextListener(new IBiometricContextListener.Stub() {
                @Override
                public void onDozeChanged(boolean isDozing) {
                    mIsDozing = isDozing;
                    notifyChanged();
                }

                private void notifyChanged() {
                    if (handler != null) {
                        handler.post(() -> notifySubscribers());
                    } else {
                        notifySubscribers();
                    }
                }
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to register biometric context listener", e);
        }
    }

    private boolean mIsDozing = false;

    @Override
    public boolean isAoD() {
        return mIsDozing;
    }

    @Override
    public void subscribe(@NonNull OperationContext context,
            @NonNull Consumer<OperationContext> consumer) {
        mSubscribers.put(context, consumer);
    }

    @Override
    public void unsubscribe(@NonNull OperationContext context) {
        mSubscribers.remove(context);
    }

    private void notifySubscribers() {
        mSubscribers.forEach((context, consumer) -> {
            context.isAoD = mIsDozing;
            consumer.accept(context);
        });
    }
}
