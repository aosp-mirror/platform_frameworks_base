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
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.biometrics.AuthenticateOptions;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricRequestConstants;
import android.os.IBinder;
import android.os.RemoteException;
import android.security.KeyStore;
import android.util.EventLog;
import android.util.Slog;

import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * A class to keep track of the authentication state for a given client.
 */
public abstract class AuthenticationClient<T, O extends AuthenticateOptions>
        extends AcquisitionClient<T> implements AuthenticationConsumer {

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
    private static final String TAG = "Biometrics/AuthenticationClient";
    protected final long mOperationId;
    private final boolean mIsStrongBiometric;
    private final boolean mRequireConfirmation;
    private final ActivityTaskManager mActivityTaskManager;
    private final BiometricManager mBiometricManager;
    @Nullable
    private final TaskStackListener mTaskStackListener;
    private final LockoutTracker mLockoutTracker;
    private final O mOptions;
    private final boolean mIsRestricted;
    private final boolean mAllowBackgroundAuthentication;
    // TODO: This is currently hard to maintain, as each AuthenticationClient subclass must update
    //  the state. We should think of a way to improve this in the future.
    @State
    protected int mState = STATE_NEW;
    private long mStartTimeMs;
    private boolean mAuthAttempted;
    private boolean mAuthSuccess = false;
    private final int mSensorStrength;
    // This is used to determine if we should use the old lockout counter (HIDL) or the new lockout
    // counter implementation (AIDL)
    private final boolean mShouldUseLockoutTracker;

    public AuthenticationClient(@NonNull Context context, @NonNull Supplier<T> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener,
            long operationId, boolean restricted, @NonNull O options,
            int cookie, boolean requireConfirmation,
            @NonNull BiometricLogger biometricLogger, @NonNull BiometricContext biometricContext,
            boolean isStrongBiometric, @Nullable TaskStackListener taskStackListener,
            @NonNull LockoutTracker lockoutTracker, boolean allowBackgroundAuthentication,
            boolean shouldVibrate, int sensorStrength) {
        super(context, lazyDaemon, token, listener, options.getUserId(),
                options.getOpPackageName(), cookie, options.getSensorId(), shouldVibrate,
                biometricLogger, biometricContext);
        mIsStrongBiometric = isStrongBiometric;
        mOperationId = operationId;
        mRequireConfirmation = requireConfirmation;
        mActivityTaskManager = getActivityTaskManager();
        mBiometricManager = context.getSystemService(BiometricManager.class);
        mTaskStackListener = taskStackListener;
        mLockoutTracker = lockoutTracker;
        mIsRestricted = restricted;
        mAllowBackgroundAuthentication = allowBackgroundAuthentication;
        mShouldUseLockoutTracker = lockoutTracker != null;
        mSensorStrength = sensorStrength;
        mOptions = options;
    }

    @LockoutTracker.LockoutMode
    public int handleFailedAttempt(int userId) {
        if (Flags.deHidl()) {
            if (mLockoutTracker != null) {
                mLockoutTracker.addFailedAttemptForUser(getTargetUserId());
            }
            @LockoutTracker.LockoutMode final int lockoutMode =
                    getLockoutTracker().getLockoutModeForUser(userId);
            final PerformanceTracker performanceTracker =
                    PerformanceTracker.getInstanceForSensorId(getSensorId());
            if (lockoutMode == LockoutTracker.LOCKOUT_PERMANENT) {
                performanceTracker.incrementPermanentLockoutForUser(userId);
            } else if (lockoutMode == LockoutTracker.LOCKOUT_TIMED) {
                performanceTracker.incrementTimedLockoutForUser(userId);
            }

            return lockoutMode;
        } else {
            return LockoutTracker.LOCKOUT_NONE;
        }
    }

    protected long getStartTimeMs() {
        return mStartTimeMs;
    }

    protected ActivityTaskManager getActivityTaskManager() {
        return ActivityTaskManager.getInstance();
    }

    @Override
    public void binderDied() {
        final boolean clearListener = !isBiometricPrompt();
        binderDiedInternal(clearListener);
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

    private boolean isSettings() {
        return Utils.isSettings(getContext(), getOwnerString());
    }

    /** The options requested at the start of the operation. */
    protected O getOptions() {
        return mOptions;
    }

    @Override
    protected boolean isCryptoOperation() {
        return mOperationId != 0;
    }

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier,
            boolean authenticated, ArrayList<Byte> hardwareAuthToken) {
        getLogger().logOnAuthenticated(getContext(), getOperationContext(),
                authenticated, mRequireConfirmation, getTargetUserId(), isBiometricPrompt());

        final ClientMonitorCallbackConverter listener = getListener();

        if (DEBUG) {
            Slog.v(TAG, "onAuthenticated(" + authenticated + ")"
                    + ", ID:" + identifier.getBiometricId()
                    + ", Owner: " + getOwnerString()
                    + ", isBP: " + isBiometricPrompt()
                    + ", listener: " + listener
                    + ", requireConfirmation: " + mRequireConfirmation
                    + ", user: " + getTargetUserId()
                    + ", clientMonitor: " + this);
        }

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
            isBackgroundAuth = Utils.isBackground(getOwnerString());
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

            mAuthSuccess = true;
            markAlreadyDone();

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

            // For BP, BiometricService will add the authToken to Keystore.
            if (!isBiometricPrompt() && mIsStrongBiometric) {
                final int result = KeyStore.getInstance().addAuthToken(byteToken);
                if (result != KeyStore.NO_ERROR) {
                    Slog.d(TAG, "Error adding auth token : " + result);
                } else {
                    Slog.d(TAG, "addAuthToken: " + result);
                }
            } else {
                Slog.d(TAG, "Skipping addAuthToken");
            }
            try {
                if (listener != null) {
                    if (!mIsRestricted) {
                        listener.onAuthenticationSucceeded(getSensorId(), identifier, byteToken,
                                getTargetUserId(), mIsStrongBiometric);
                    } else {
                        listener.onAuthenticationSucceeded(getSensorId(), null /* identifier */,
                                byteToken,
                                getTargetUserId(), mIsStrongBiometric);
                    }
                } else {
                    Slog.e(TAG, "Received successful auth, but client was not listening");
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to notify listener", e);
                mCallback.onClientFinished(this, false);
                return;
            }
        } else {
            if (isBackgroundAuth) {
                Slog.e(TAG, "Sending cancel to client(Due to background auth)");
                if (mTaskStackListener != null) {
                    mActivityTaskManager.unregisterTaskStackListener(mTaskStackListener);
                }
                sendCancelOnly(getListener());
                mCallback.onClientFinished(this, false);
            } else {
                // Allow system-defined limit of number of attempts before giving up
                if (mShouldUseLockoutTracker) {
                    @LockoutTracker.LockoutMode final int lockoutMode =
                            handleFailedAttempt(getTargetUserId());
                    if (lockoutMode != LockoutTracker.LOCKOUT_NONE) {
                        markAlreadyDone();
                    }
                }

                try {
                    if (listener != null) {
                        listener.onAuthenticationFailed(getSensorId());
                    } else {
                        Slog.e(TAG, "Received failed auth, but client was not listening");
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify listener", e);
                    mCallback.onClientFinished(this, false);
                    return;
                }
            }
        }
        AuthenticationClient.this.handleLifecycleAfterAuth(authenticated);
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
    }

    @Override
    public void onError(@BiometricConstants.Errors int errorCode, int vendorCode) {
        super.onError(errorCode, vendorCode);
        mState = STATE_STOPPED;
    }

    /**
     * Start authentication
     */
    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);

        final @LockoutTracker.LockoutMode int lockoutMode;
        if (mShouldUseLockoutTracker) {
            lockoutMode = mLockoutTracker.getLockoutModeForUser(getTargetUserId());
        } else {
            lockoutMode = getBiometricContext().getAuthSessionCoordinator()
                    .getLockoutStateFor(getTargetUserId(), mSensorStrength);
        }

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

    /**
     * Handles lifecycle, e.g. {@link BiometricScheduler} after authentication. This is necessary
     * as different clients handle the lifecycle of authentication success/reject differently. I.E.
     * Fingerprint does not finish authentication when it is rejected.
     */
    protected abstract void handleLifecycleAfterAuth(boolean authenticated);

    /**
     * @return true if a user was detected (i.e. face was found, fingerprint sensor was touched.
     * etc)
     */
    public abstract boolean wasUserDetected();

    @State
    public int getState() {
        return mState;
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

    /** If an auth attempt completed successfully. */
    public boolean wasAuthSuccessful() {
        return mAuthSuccess;
    }

    protected int getSensorStrength() {
        return mSensorStrength;
    }

    protected LockoutTracker getLockoutTracker() {
        return mLockoutTracker;
    }

    protected int getRequestReason() {
        if (isKeyguard()) {
            return BiometricRequestConstants.REASON_AUTH_KEYGUARD;
        } else if (isBiometricPrompt()) {
            // BP reason always takes precedent over settings, since callers from within
            // settings can always invoke BP.
            return BiometricRequestConstants.REASON_AUTH_BP;
        } else if (isSettings()) {
            // This is pretty much only for FingerprintManager#authenticate usage from
            // FingerprintSettings.
            return BiometricRequestConstants.REASON_AUTH_SETTINGS;
        } else {
            return BiometricRequestConstants.REASON_AUTH_OTHER;
        }
    }
}
