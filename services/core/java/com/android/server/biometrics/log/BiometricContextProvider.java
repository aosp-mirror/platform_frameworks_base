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
import android.app.StatusBarManager;
import android.content.Context;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.OperationReason;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.statusbar.ISessionListener;
import com.android.internal.statusbar.IStatusBarService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A default provider for {@link BiometricContext}.
 */
final class BiometricContextProvider implements BiometricContext {

    private static final String TAG = "BiometricContextProvider";

    private static final int SESSION_TYPES =
            StatusBarManager.SESSION_KEYGUARD | StatusBarManager.SESSION_BIOMETRIC_PROMPT;

    private static BiometricContextProvider sInstance;

    static BiometricContextProvider defaultProvider(@NonNull Context context) {
        synchronized (BiometricContextProvider.class) {
            if (sInstance == null) {
                try {
                    sInstance = new BiometricContextProvider(
                            new AmbientDisplayConfiguration(context),
                            IStatusBarService.Stub.asInterface(ServiceManager.getServiceOrThrow(
                                    Context.STATUS_BAR_SERVICE)), null /* handler */);
                } catch (ServiceNotFoundException e) {
                    throw new IllegalStateException("Failed to find required service", e);
                }
            }
        }
        return sInstance;
    }

    @NonNull
    private final Map<OperationContext, Consumer<OperationContext>> mSubscribers =
            new ConcurrentHashMap<>();

    @Nullable
    private final Map<Integer, InstanceId> mSession = new ConcurrentHashMap<>();

    private final AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    private boolean mIsAod = false;
    private boolean mIsAwake = false;

    @VisibleForTesting
    BiometricContextProvider(@NonNull AmbientDisplayConfiguration ambientDisplayConfiguration,
            @NonNull IStatusBarService service, @Nullable Handler handler) {
        mAmbientDisplayConfiguration = ambientDisplayConfiguration;
        try {
            service.setBiometicContextListener(new IBiometricContextListener.Stub() {
                @Override
                public void onDozeChanged(boolean isDozing, boolean isAwake) {
                    isDozing = isDozing && isAodEnabled();
                    final boolean changed = (mIsAod != isDozing) || (mIsAwake != isAwake);
                    if (changed) {
                        mIsAod = isDozing;
                        mIsAwake = isAwake;
                        notifyChanged();
                    }
                }

                private void notifyChanged() {
                    if (handler != null) {
                        handler.post(() -> notifySubscribers());
                    } else {
                        notifySubscribers();
                    }
                }

                private boolean isAodEnabled() {
                    return mAmbientDisplayConfiguration.alwaysOnEnabled(UserHandle.USER_CURRENT);
                }
            });
            service.registerSessionListener(SESSION_TYPES, new ISessionListener.Stub() {
                @Override
                public void onSessionStarted(int sessionType, InstanceId instance) {
                    mSession.put(sessionType, instance);
                }

                @Override
                public void onSessionEnded(int sessionType, InstanceId instance) {
                    final InstanceId id = mSession.remove(sessionType);
                    if (id != null && instance != null && id.getId() != instance.getId()) {
                        Slog.w(TAG, "session id mismatch");
                    }
                }
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to register biometric context listener", e);
        }
    }

    @Override
    public OperationContext updateContext(@NonNull OperationContext operationContext,
            boolean isCryptoOperation) {
        operationContext.isAod = isAod();
        operationContext.isCrypto = isCryptoOperation;
        setFirstSessionId(operationContext);
        return operationContext;
    }

    private void setFirstSessionId(@NonNull OperationContext operationContext) {
        Integer sessionId = getKeyguardEntrySessionId();
        if (sessionId != null) {
            operationContext.id = sessionId;
            operationContext.reason = OperationReason.KEYGUARD;
            return;
        }

        sessionId = getBiometricPromptSessionId();
        if (sessionId != null) {
            operationContext.id = sessionId;
            operationContext.reason = OperationReason.BIOMETRIC_PROMPT;
            return;
        }

        operationContext.id = 0;
        operationContext.reason = OperationReason.UNKNOWN;
    }

    @Nullable
    @Override
    public Integer getKeyguardEntrySessionId() {
        final InstanceId id = mSession.get(StatusBarManager.SESSION_KEYGUARD);
        return id != null ? id.getId() : null;
    }

    @Nullable
    @Override
    public Integer getBiometricPromptSessionId() {
        final InstanceId id = mSession.get(StatusBarManager.SESSION_BIOMETRIC_PROMPT);
        return id != null ? id.getId() : null;
    }

    @Override
    public boolean isAod() {
        return mIsAod;
    }

    @Override
    public boolean isAwake() {
        return mIsAwake;
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
            context.isAod = isAod();
            consumer.accept(context);
        });
    }
}
