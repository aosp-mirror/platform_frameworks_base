/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricOverlayConstants;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.security.KeyStore;
import android.util.EventLog;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * A class to keep track of the authentication state for a given client.
 */
public abstract class AuthenticationClient<T> extends AcquisitionClient<T>
        implements AuthenticationConsumer  {

    private static final String TAG = "Biometrics/AuthenticationClient";

    // New, has not started yet
    public static final int STATE_NEW = 0;
    // Framework/HAL have started this operation
    public static final int STATE_STARTED = 1;
    // Operation is started, but requires some user action to start (such as finger lift & re-touch)
    public static final int STATE_STARTED_PAUSED = 2;
    // Same as above, except auth was attempted (rejected, timed out, etc).
    public static final int STATE_STARTED_PAUSED_ATTEMPTED = 3;
    // Done, errored, canceled, etc. HAL/framework are not running this sensor anymore.
    public static final int STATE_STOPPED = 4;

    @IntDef({STATE_NEW,
            STATE_STARTED,
            STATE_STARTED_PAUSED,
            STATE_STARTED_PAUSED_ATTEMPTED,
            STATE_STOPPED})
    @interface State {}

    private final boolean mIsStrongBiometric;
    private final boolean mRequireConfirmation;
    private final ActivityTaskManager mActivityTaskManager;
    private final BiometricManager mBiometricManager;
    @Nullable private final TaskStackListener mTaskStackListener;
    private final LockoutTracker mLockoutTracker;
    private final boolean mIsRestricted;
    private final boolean mAllowBackgroundAuthentication;
    private final boolean mIsKeyguardBypassEnabled;

    protected final long mOperationId;

    private long mStartTimeMs;

    private boolean mAuthAttempted;

    // TODO: This is currently hard to maintain, as each AuthenticationClient subclass must update
    //  the state. We should think of a way to improve this in the future.
    protected @State int mState = STATE_NEW;

    /**
     * Handles lifecycle, e.g. {@link BiometricScheduler},
     * {@link com.android.server.biometrics.sensors.BaseClientMonitor.Callback} after authentication
     * results are known. Note that this happens asynchronously from (but shortly after)
     * {@link #onAuthenticated(BiometricAuthenticator.Identifier, boolean, ArrayList)} and allows
     * {@link CoexCoordinator} a chance to invoke/delay this event.
     * @param authenticated
     */
    protected abstract void handleLifecycleAfterAuth(boolean authenticated);

    /**
     * @return true if a user was detected (i.e. face was found, fingerprint sensor was touched.
     *         etc)
     */
    public abstract boolean wasUserDetected();

    public AuthenticationClient(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener,
            int targetUserId, long operationId, boolean restricted, @NonNull String owner,
            int cookie, boolean requireConfirmation, int sensorId, boolean isStrongBiometric,
            int statsModality, int statsClient, @Nullable TaskStackListener taskStackListener,
            @NonNull LockoutTracker lockoutTracker, boolean allowBackgroundAuthentication,
            boolean shouldVibrate, boolean isKeyguardBypassEnabled) {
        super(context, lazyDaemon, token, listener, targetUserId, owner, cookie, sensorId,
                shouldVibrate, statsModality, BiometricsProtoEnums.ACTION_AUTHENTICATE,
                statsClient);
        mIsStrongBiometric = isStrongBiometric;
        mOperationId = operationId;
        mRequireConfirmation = requireConfirmation;
        mActivityTaskManager = ActivityTaskManager.getInstance();
        mBiometricManager = context.getSystemService(BiometricManager.class);
        mTaskStackListener = taskStackListener;
        mLockoutTracker = lockoutTracker;
        mIsRestricted = restricted;
        mAllowBackgroundAuthentication = allowBackgroundAuthentication;
        mIsKeyguardBypassEnabled = isKeyguardBypassEnabled;
    }

    public @LockoutTracker.LockoutMode int handleFailedAttempt(int userId) {
        final @LockoutTracker.LockoutMode int lockoutMode =
                mLockoutTracker.getLockoutModeForUser(userId);
        final PerformanceTracker performanceTracker =
                PerformanceTracker.getInstanceForSensorId(getSensorId());

        if (lockoutMode == LockoutTracker.LOCKOUT_PERMANENT) {
            performanceTracker.incrementPermanentLockoutForUser(userId);
        } else if (lockoutMode == LockoutTracker.LOCKOUT_TIMED) {
            performanceTracker.incrementTimedLockoutForUser(userId);
        }

        return lockoutMode;
    }

    protected long getStartTimeMs() {
        return mStartTimeMs;
    }

    @Override
    public void binderDied() {
        final boolean clearListener = !isBiometricPrompt();
        binderDiedInternal(clearListener);
    }

    public boolean isBiometricPrompt() {
        return getCookie() != 0;
    }

    public long getOperationId() {
        return mOperationId;
    }

    public boolean isRestricted() {
        return mIsRestricted;
    }

    public boolean isKeyguard() {
        return Utils.isKeyguard(getContext(), getOwnerString());
    }

    @Override
    protected boolean isCryptoOperation() {
        return mOperationId != 0;
    }

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> hardwareAuthToken) {
        super.logOnAuthenticated(getContext(), authenticated, mRequireConfirmation,
                getTargetUserId(), isBiometricPrompt());

        final ClientMonitorCallbackConverter listener = getListener();

        if (DEBUG) Slog.v(TAG, "onAuthenticated(" + authenticated + ")"
                + ", ID:" + identifier.getBiometricId()
                + ", Owner: " + getOwnerString()
                + ", isBP: " + isBiometricPrompt()
                + ", listener: " + listener
                + ", requireConfirmation: " + mRequireConfirmation
                + ", user: " + getTargetUserId()
                + ", clientMonitor: " + toString());

        final PerformanceTracker pm = PerformanceTracker.getInstanceForSensorId(getSensorId());
        if (isCryptoOperation()) {
            pm.incrementCryptoAuthForUser(getTargetUserId(), authenticated);
        } else {
            pm.incrementAuthForUser(getTargetUserId(), authenticated);
        }

        if (mAllowBackgroundAuthentication) {
            Slog.w(TAG, "Allowing background authentication,"
                    + " this is allowed only for platform or test invocations");
        }

        // Ensure authentication only succeeds if the client activity is on top.
        boolean isBackgroundAuth = false;
        if (!mAllowBackgroundAuthentication && authenticated
                && !Utils.isKeyguard(getContext(), getOwnerString())
                && !Utils.isSystem(getContext(), getOwnerString())) {
            final List<ActivityManager.RunningTaskInfo> tasks =
                    mActivityTaskManager.getTasks(1);
            if (tasks == null || tasks.isEmpty()) {
                Slog.e(TAG, "No running tasks reported");
                isBackgroundAuth = true;
            } else {
                final ComponentName topActivity = tasks.get(0).topActivity;
                if (topActivity == null) {
                    Slog.e(TAG, "Unable to get top activity");
                    isBackgroundAuth = true;
                } else {
                    final String topPackage = topActivity.getPackageName();
                    if (!topPackage.contentEquals(getOwnerString())) {
                        Slog.e(TAG, "Background authentication detected, top: " + topPackage
                                + ", client: " + getOwnerString());
                        isBackgroundAuth = true;
                    }
                }
            }
        }

        // Fail authentication if we can't confirm the client activity is on top.
        if (isBackgroundAuth) {
            Slog.e(TAG, "Failing possible background authentication");
            authenticated = false;

            // SafetyNet logging for exploitation attempts of b/159249069.
            final ApplicationInfo appInfo = getContext().getApplicationInfo();
            EventLog.writeEvent(0x534e4554, "159249069", appInfo != null ? appInfo.uid : -1,
                    "Attempted background authentication");
        }

        if (authenticated) {
            // SafetyNet logging for b/159249069 if constraint is violated.
            if (isBackgroundAuth) {
                final ApplicationInfo appInfo = getContext().getApplicationInfo();
                EventLog.writeEvent(0x534e4554, "159249069", appInfo != null ? appInfo.uid : -1,
                        "Successful background authentication!");
            }

            mAlreadyDone = true;

            if (mTaskStackListener != null) {
                mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
            }

            final byte[] byteToken = new byte[hardwareAuthToken.size()];
            for (int i = 0; i < hardwareAuthToken.size(); i++) {
                byteToken[i] = hardwareAuthToken.get(i);
            }

            if (mIsStrongBiometric) {
                mBiometricManager.resetLockoutTimeBound(getToken(),
                        getContext().getOpPackageName(),
                        getSensorId(), getTargetUserId(), byteToken);
            }

            final CoexCoordinator coordinator = CoexCoordinator.getInstance();
            coordinator.onAuthenticationSucceeded(SystemClock.uptimeMillis(), this,
                    new CoexCoordinator.Callback() {
                @Override
                public void sendAuthenticationResult(boolean addAuthTokenIfStrong) {
                    if (addAuthTokenIfStrong && mIsStrongBiometric) {
                        final int result = KeyStore.getInstance().addAuthToken(byteToken);
                        Slog.d(TAG, "addAuthToken: " + result);
                    } else {
                        Slog.d(TAG, "Skipping addAuthToken");
                    }

                    if (listener != null) {
                        try {
                            // Explicitly have if/else here to make it super obvious in case the
                            // code is touched in the future.
                            if (!mIsRestricted) {
                                listener.onAuthenticationSucceeded(getSensorId(),
                                        identifier,
                                        byteToken,
                                        getTargetUserId(),
                                        mIsStrongBiometric);
                            } else {
                                listener.onAuthenticationSucceeded(getSensorId(),
                                        null /* identifier */,
                                        byteToken,
                                        getTargetUserId(),
                                        mIsStrongBiometric);
                            }
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Unable to notify listener", e);
                        }
                    } else {
                        Slog.w(TAG, "Client not listening");
                    }
                }

                @Override
                public void sendHapticFeedback() {
                    if (listener != null && mShouldVibrate) {
                        vibrateSuccess();
                    }
                }

                @Override
                public void handleLifecycleAfterAuth() {
                    AuthenticationClient.this.handleLifecycleAfterAuth(true /* authenticated */);
                }

                @Override
                public void sendAuthenticationCanceled() {
                    sendCancelOnly(listener);
                }
            });
        } else {
            // Allow system-defined limit of number of attempts before giving up
            final @LockoutTracker.LockoutMode int lockoutMode =
                    handleFailedAttempt(getTargetUserId());
            if (lockoutMode != LockoutTracker.LOCKOUT_NONE) {
                mAlreadyDone = true;
            }

            final CoexCoordinator coordinator = CoexCoordinator.getInstance();
            coordinator.onAuthenticationRejected(SystemClock.uptimeMillis(), this, lockoutMode,
                    new CoexCoordinator.Callback() {
                @Override
                public void sendAuthenticationResult(boolean addAuthTokenIfStrong) {
                    if (listener != null) {
                        try {
                            listener.onAuthenticationFailed(getSensorId());
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Unable to notify listener", e);
                        }
                    }
                }

                @Override
                public void sendHapticFeedback() {
                    if (listener != null && mShouldVibrate) {
                        vibrateError();
                    }
                }

                @Override
                public void handleLifecycleAfterAuth() {
                    AuthenticationClient.this.handleLifecycleAfterAuth(false /* authenticated */);
                }

                @Override
                public void sendAuthenticationCanceled() {
                    sendCancelOnly(listener);
                }
            });
        }
    }

    private void sendCancelOnly(@Nullable ClientMonitorCallbackConverter listener) {
        if (listener == null) {
            Slog.e(TAG, "Unable to sendAuthenticationCanceled, listener null");
            return;
        }
        try {
            listener.onError(getSensorId(),
                    getCookie(),
                    BiometricConstants.BIOMETRIC_ERROR_CANCELED,
                    0 /* vendorCode */);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
        }
    }

    @Override
    public void onAcquired(int acquiredInfo, int vendorCode) {
        super.onAcquired(acquiredInfo, vendorCode);

        final @LockoutTracker.LockoutMode int lockoutMode =
                mLockoutTracker.getLockoutModeForUser(getTargetUserId());
        if (lockoutMode == LockoutTracker.LOCKOUT_NONE) {
            PerformanceTracker pt = PerformanceTracker.getInstanceForSensorId(getSensorId());
            pt.incrementAcquireForUser(getTargetUserId(), isCryptoOperation());
        }
    }

    @Override
    public void onError(@BiometricConstants.Errors int errorCode, int vendorCode) {
        super.onError(errorCode, vendorCode);
        mState = STATE_STOPPED;

        CoexCoordinator.getInstance().onAuthenticationError(this, errorCode, this::vibrateError);
    }

    /**
     * Start authentication
     */
    @Override
    public void start(@NonNull Callback callback) {
        super.start(callback);

        final @LockoutTracker.LockoutMode int lockoutMode =
                mLockoutTracker.getLockoutModeForUser(getTargetUserId());
        if (lockoutMode != LockoutTracker.LOCKOUT_NONE) {
            Slog.v(TAG, "In lockout mode(" + lockoutMode + ") ; disallowing authentication");
            int errorCode = lockoutMode == LockoutTracker.LOCKOUT_TIMED
                    ? BiometricConstants.BIOMETRIC_ERROR_LOCKOUT
                    : BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT;
            onError(errorCode, 0 /* vendorCode */);
            return;
        }

        if (mTaskStackListener != null) {
            mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
        }

        Slog.d(TAG, "Requesting auth for " + getOwnerString());

        mStartTimeMs = System.currentTimeMillis();
        mAuthAttempted = true;
        startHalOperation();
    }

    @Override
    public void cancel() {
        super.cancel();

        if (mTaskStackListener != null) {
            mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
        }
    }

    public @State int getState() {
        return mState;
    }

    /**
     * @return true if the client supports bypass (e.g. passive auth such as face), and if it's
     * enabled by the user.
     */
    public boolean isKeyguardBypassEnabled() {
        return mIsKeyguardBypassEnabled;
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_AUTHENTICATE;
    }

    @Override
    public boolean interruptsPrecedingClients() {
        return true;
    }

    public boolean wasAuthAttempted() {
        return mAuthAttempted;
    }

    protected int getShowOverlayReason() {
        if (isKeyguard()) {
            return BiometricOverlayConstants.REASON_AUTH_KEYGUARD;
        } else if (isBiometricPrompt()) {
            return BiometricOverlayConstants.REASON_AUTH_BP;
        } else {
            return BiometricOverlayConstants.REASON_AUTH_OTHER;
        }
    }
}
