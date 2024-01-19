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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.biometrics.AuthenticateOptions;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.OperationContext;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Slog;
import android.view.Display;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.statusbar.ISessionListener;
import com.android.internal.statusbar.IStatusBarService;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A default provider for {@link BiometricContext} that aggregates device state from SysUI
 * and packages it into an {@link android.hardware.biometrics.common.OperationContext} that can
 * be propagated to the HAL.
 */
public final class BiometricContextProvider implements BiometricContext {

    private static final String TAG = "BiometricContextProvider";

    private static final int SESSION_TYPES =
            StatusBarManager.SESSION_KEYGUARD | StatusBarManager.SESSION_BIOMETRIC_PROMPT;

    private static BiometricContextProvider sInstance;

    static BiometricContextProvider defaultProvider(@NonNull Context context) {
        synchronized (BiometricContextProvider.class) {
            if (sInstance == null) {
                try {
                    sInstance = new BiometricContextProvider(context,
                            (WindowManager) context.getSystemService(Context.WINDOW_SERVICE),
                            IStatusBarService.Stub.asInterface(ServiceManager.getServiceOrThrow(
                                    Context.STATUS_BAR_SERVICE)), null /* handler */,
                            new AuthSessionCoordinator());
                } catch (ServiceNotFoundException e) {
                    throw new IllegalStateException("Failed to find required service", e);
                }
            }
        }
        return sInstance;
    }

    @NonNull
    private final Map<OperationContextExt, Consumer<OperationContext>> mSubscribers =
            new ConcurrentHashMap<>();

    @Nullable
    private final Map<Integer, BiometricContextSessionInfo> mSession = new ConcurrentHashMap<>();
    private final AuthSessionCoordinator mAuthSessionCoordinator;
    private final WindowManager mWindowManager;
    @Nullable private final Handler mHandler;
    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private int mFoldState = IBiometricContextListener.FoldState.UNKNOWN;

    private int mDisplayState = AuthenticateOptions.DISPLAY_STATE_UNKNOWN;
    @VisibleForTesting
    final BroadcastReceiver mDockStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mDockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                    Intent.EXTRA_DOCK_STATE_UNDOCKED);
            // no need to notify, not sent to HAL
        }
    };

    @VisibleForTesting
    public BiometricContextProvider(@NonNull Context context,
            @NonNull WindowManager windowManager,
            @NonNull IStatusBarService service, @Nullable Handler handler,
            @NonNull AuthSessionCoordinator authSessionCoordinator) {
        mWindowManager = windowManager;
        mAuthSessionCoordinator = authSessionCoordinator;
        mHandler = handler;

        subscribeBiometricContextListener(service);
        subscribeDockState(context);
    }

    private void subscribeBiometricContextListener(@NonNull IStatusBarService service) {
        try {
            service.setBiometicContextListener(new IBiometricContextListener.Stub() {
                @Override
                public void onFoldChanged(int foldState) {
                    if (mFoldState != foldState) {
                        mFoldState = foldState;
                        notifyChanged();
                    }
                }

                @Override
                public void onDisplayStateChanged(int displayState) {
                    if (displayState != mDisplayState) {
                        mDisplayState = displayState;
                        notifyChanged();
                    }
                }
            });
            service.registerSessionListener(SESSION_TYPES, new ISessionListener.Stub() {
                @Override
                public void onSessionStarted(int sessionType, InstanceId instance) {
                    mSession.put(sessionType, new BiometricContextSessionInfo(instance));
                }

                @Override
                public void onSessionEnded(int sessionType, InstanceId instance) {
                    final BiometricContextSessionInfo info = mSession.remove(sessionType);
                    if (info != null && instance != null && info.getId() != instance.getId()) {
                        Slog.w(TAG, "session id mismatch");
                    }
                }
            });
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to register biometric context listener", e);
        }
    }

    private void subscribeDockState(@NonNull Context context) {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DOCK_EVENT);
        context.registerReceiver(mDockStateReceiver, filter);
    }

    @Override
    public OperationContextExt updateContext(@NonNull OperationContextExt operationContext,
            boolean isCryptoOperation) {
        return operationContext.update(this, isCryptoOperation);
    }

    @Nullable
    @Override
    public BiometricContextSessionInfo getKeyguardEntrySessionInfo() {
        return mSession.get(StatusBarManager.SESSION_KEYGUARD);
    }

    @Nullable
    @Override
    public BiometricContextSessionInfo getBiometricPromptSessionInfo() {
        return mSession.get(StatusBarManager.SESSION_BIOMETRIC_PROMPT);
    }

    @Override
    public boolean isAod() {
        return mDisplayState == AuthenticateOptions.DISPLAY_STATE_AOD;
    }

    @Override
    public boolean isAwake() {
        switch (mDisplayState) {
            case AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN:
            case AuthenticateOptions.DISPLAY_STATE_SCREENSAVER:
            case AuthenticateOptions.DISPLAY_STATE_UNKNOWN:
                return true;
        }
        return false;
    }

    @Override
    public boolean isDisplayOn() {
        return mWindowManager.getDefaultDisplay().getState() == Display.STATE_ON;
    }

    @Override
    public int getDockedState() {
        return mDockState;
    }

    @Override
    public int getFoldState() {
        return mFoldState;
    }

    @Override
    public int getCurrentRotation() {
        return mWindowManager.getDefaultDisplay().getRotation();
    }

    @Override
    public int getDisplayState() {
        return mDisplayState;
    }

    @Override
    public void subscribe(@NonNull OperationContextExt context,
            @NonNull Consumer<OperationContext> consumer) {
        mSubscribers.put(context, consumer);
        // TODO(b/294161627) Combine the getContext/subscribe APIs to avoid race
        if (context.getDisplayState() != getDisplayState()) {
            consumer.accept(context.update(this, context.isCrypto()).toAidlContext());
        }
    }

    @Override
    public void subscribe(@NonNull OperationContextExt context,
            @NonNull Consumer<OperationContext> startHalConsumer,
            @NonNull Consumer<OperationContext> updateContextConsumer,
            @Nullable AuthenticateOptions options) {
        mSubscribers.put(updateContext(context, context.isCrypto()), updateContextConsumer);
        if (options != null) {
            startHalConsumer.accept(context.toAidlContext(options));
        } else {
            startHalConsumer.accept(context.toAidlContext());
        }
    }

    @Override
    public void unsubscribe(@NonNull OperationContextExt context) {
        mSubscribers.remove(context);
    }

    @Override
    public AuthSessionCoordinator getAuthSessionCoordinator() {
        return mAuthSessionCoordinator;
    }

    private void notifyChanged() {
        if (mHandler != null) {
            mHandler.post(this::notifySubscribers);
        } else {
            notifySubscribers();
        }
    }

    private void notifySubscribers() {
        mSubscribers.forEach((context, consumer) -> {
            consumer.accept(context.update(this, context.isCrypto()).toAidlContext());
        });
    }

    @Override
    public String toString() {
        return "[keyguard session: " + getKeyguardEntrySessionInfo() + ", "
                + "bp session: " + getBiometricPromptSessionInfo() + ", "
                + "displayState: " + getDisplayState() + ", "
                + "isAwake: " + isAwake() +  ", "
                + "isDisplayOn: " + isDisplayOn() +  ", "
                + "dock: " + getDockedState() + ", "
                + "rotation: " + getCurrentRotation() + ", "
                + "foldState: " + mFoldState + "]";
    }
}
