/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.RectF;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Receives messages sent from {@link com.android.server.biometrics.BiometricService} and shows the
 * appropriate biometric UI (e.g. BiometricDialogView).
 */
@SysUISingleton
public class AuthController extends SystemUI implements CommandQueue.Callbacks,
        AuthDialogCallback, DozeReceiver {

    private static final String TAG = "AuthController";
    private static final boolean DEBUG = true;

    private final CommandQueue mCommandQueue;
    private final StatusBarStateController mStatusBarStateController;
    private final ActivityTaskManager mActivityTaskManager;
    @Nullable private final FingerprintManager mFingerprintManager;
    @Nullable private final FaceManager mFaceManager;
    private final Provider<UdfpsController> mUdfpsControllerFactory;

    @Nullable private final List<FingerprintSensorPropertiesInternal> mFpProps;
    @Nullable private final List<FaceSensorPropertiesInternal> mFaceProps;
    @Nullable private final List<FingerprintSensorPropertiesInternal> mUdfpsProps;

    // TODO: These should just be saved from onSaveState
    private SomeArgs mCurrentDialogArgs;
    @VisibleForTesting
    AuthDialog mCurrentDialog;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private WindowManager mWindowManager;
    @Nullable
    private UdfpsController mUdfpsController;
    @VisibleForTesting
    TaskStackListener mTaskStackListener;
    @VisibleForTesting
    IBiometricSysuiReceiver mReceiver;

    private class BiometricTaskStackListener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(AuthController.this::handleTaskStackChanged);
        }
    }

    @VisibleForTesting
    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mCurrentDialog != null
                    && Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                Log.w(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS received");
                mCurrentDialog.dismissWithoutCallback(true /* animate */);
                mCurrentDialog = null;

                try {
                    if (mReceiver != null) {
                        mReceiver.onDialogDismissed(BiometricPrompt.DISMISSED_REASON_USER_CANCEL,
                                null /* credentialAttestation */);
                        mReceiver = null;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Remote exception", e);
                }
            }
        }
    };

    private void handleTaskStackChanged() {
        if (mCurrentDialog != null) {
            try {
                final String clientPackage = mCurrentDialog.getOpPackageName();
                Log.w(TAG, "Task stack changed, current client: " + clientPackage);
                final List<ActivityManager.RunningTaskInfo> runningTasks =
                        mActivityTaskManager.getTasks(1);
                if (!runningTasks.isEmpty()) {
                    final String topPackage = runningTasks.get(0).topActivity.getPackageName();
                    if (!topPackage.contentEquals(clientPackage)
                            && !Utils.isSystem(mContext, clientPackage)) {
                        Log.w(TAG, "Evicting client due to: " + topPackage);
                        mCurrentDialog.dismissWithoutCallback(true /* animate */);
                        mCurrentDialog = null;
                        if (mReceiver != null) {
                            mReceiver.onDialogDismissed(
                                    BiometricPrompt.DISMISSED_REASON_USER_CANCEL,
                                    null /* credentialAttestation */);
                            mReceiver = null;
                        }
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception", e);
            }
        }
    }

    @Override
    public void dozeTimeTick() {
        if (mUdfpsController != null) {
            mUdfpsController.dozeTimeTick();
        }
    }

    @Override
    public void onTryAgainPressed() {
        if (mReceiver == null) {
            Log.e(TAG, "onTryAgainPressed: Receiver is null");
            return;
        }
        try {
            mReceiver.onTryAgainPressed();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when handling try again", e);
        }
    }

    @Override
    public void onDeviceCredentialPressed() {
        if (mReceiver == null) {
            Log.e(TAG, "onDeviceCredentialPressed: Receiver is null");
            return;
        }
        try {
            mReceiver.onDeviceCredentialPressed();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when handling credential button", e);
        }
    }

    @Override
    public void onSystemEvent(int event) {
        if (mReceiver == null) {
            Log.e(TAG, "onSystemEvent(" + event + "): Receiver is null");
            return;
        }
        try {
            mReceiver.onSystemEvent(event);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when sending system event", e);
        }
    }

    @Override
    public void onDialogAnimatedIn() {
        if (mReceiver == null) {
            Log.e(TAG, "onDialogAnimatedIn: Receiver is null");
            return;
        }

        try {
            mReceiver.onDialogAnimatedIn();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when sending onDialogAnimatedIn", e);
        }
    }

    @Override
    public void onDismissed(@DismissedReason int reason, @Nullable byte[] credentialAttestation) {
        switch (reason) {
            case AuthDialogCallback.DISMISSED_USER_CANCELED:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_USER_CANCEL,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_NEGATIVE,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_BUTTON_POSITIVE:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRMED,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED:
                sendResultAndCleanUp(
                        BiometricPrompt.DISMISSED_REASON_BIOMETRIC_CONFIRM_NOT_REQUIRED,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_ERROR:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_ERROR,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_BY_SYSTEM_SERVER:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED,
                        credentialAttestation);
                break;

            case AuthDialogCallback.DISMISSED_CREDENTIAL_AUTHENTICATED:
                sendResultAndCleanUp(BiometricPrompt.DISMISSED_REASON_CREDENTIAL_CONFIRMED,
                        credentialAttestation);
                break;

            default:
                Log.e(TAG, "Unhandled reason: " + reason);
                break;
        }
    }

    /**
     * @return where the UDFPS exists on the screen in pixels.
     */
    public RectF getUdfpsRegion() {
        return mUdfpsController == null ? null : mUdfpsController.getSensorLocation();
    }

    /**
     * Requests fingerprint scan.
     *
     * @param screenX X position of long press
     * @param screenY Y position of long press
     * @param major length of the major axis. See {@link MotionEvent#AXIS_TOOL_MAJOR}.
     * @param minor length of the minor axis. See {@link MotionEvent#AXIS_TOOL_MINOR}.
     */
    public void onAodInterrupt(int screenX, int screenY, float major, float minor) {
        if (mUdfpsController == null) {
            return;
        }
        mUdfpsController.onAodInterrupt(screenX, screenY, major, minor);
    }

    /**
     * Cancel a fingerprint scan.
     *
     * The sensor that triggers an AOD interrupt for fingerprint doesn't give
     * ACTION_UP/ACTION_CANCEL events, so the scan needs to be cancelled manually. This should be
     * called when authentication either succeeds or fails. Failing to cancel the scan will leave
     * the screen in high brightness mode.
     */
    public void onCancelAodInterrupt() {
        if (mUdfpsController == null) {
            return;
        }
        mUdfpsController.onCancelAodInterrupt();
    }

    private void sendResultAndCleanUp(@DismissedReason int reason,
            @Nullable byte[] credentialAttestation) {
        if (mReceiver == null) {
            Log.e(TAG, "sendResultAndCleanUp: Receiver is null");
            return;
        }
        try {
            mReceiver.onDialogDismissed(reason, credentialAttestation);
        } catch (RemoteException e) {
            Log.w(TAG, "Remote exception", e);
        }
        onDialogDismissed(reason);
    }

    @Inject
    public AuthController(Context context, CommandQueue commandQueue,
            StatusBarStateController statusBarStateController,
            ActivityTaskManager activityTaskManager,
            @Nullable FingerprintManager fingerprintManager,
            @Nullable FaceManager faceManager,
            Provider<UdfpsController> udfpsControllerFactory) {
        super(context);
        mCommandQueue = commandQueue;
        mStatusBarStateController = statusBarStateController;
        mActivityTaskManager = activityTaskManager;
        mFingerprintManager = fingerprintManager;
        mFaceManager = faceManager;
        mUdfpsControllerFactory = udfpsControllerFactory;

        mFpProps = mFingerprintManager != null ? mFingerprintManager.getSensorPropertiesInternal()
                : null;
        mFaceProps = mFaceManager != null ? mFaceManager.getSensorPropertiesInternal() : null;

        List<FingerprintSensorPropertiesInternal> udfpsProps = new ArrayList<>();
        if (mFpProps != null) {
            for (FingerprintSensorPropertiesInternal props : mFpProps) {
                if (props.isAnyUdfpsType()) {
                    udfpsProps.add(props);
                }
            }
        }
        mUdfpsProps = !udfpsProps.isEmpty() ? udfpsProps : null;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);

        context.registerReceiver(mBroadcastReceiver, filter);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void start() {
        mCommandQueue.addCallback(this);
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);

        if (mFingerprintManager != null && mFingerprintManager.isHardwareDetected()
                && mUdfpsProps != null) {
            mUdfpsController = mUdfpsControllerFactory.get();
        }

        mTaskStackListener = new BiometricTaskStackListener();
        mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
    }

    @Override
    public void showAuthenticationDialog(PromptInfo promptInfo, IBiometricSysuiReceiver receiver,
            int[] sensorIds, boolean credentialAllowed, boolean requireConfirmation,
            int userId, String opPackageName, long operationId) {
        @Authenticators.Types final int authenticators = promptInfo.getAuthenticators();

        if (DEBUG) {
            StringBuilder ids = new StringBuilder();
            for (int sensorId : sensorIds) {
                ids.append(sensorId).append(" ");
            }
            Log.d(TAG, "showAuthenticationDialog, authenticators: " + authenticators
                    + ", sensorIds: " + ids.toString()
                    + ", credentialAllowed: " + credentialAllowed
                    + ", requireConfirmation: " + requireConfirmation
                    + ", operationId: " + operationId);
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = promptInfo;
        args.arg2 = receiver;
        args.arg3 = sensorIds;
        args.arg4 = credentialAllowed;
        args.arg5 = requireConfirmation;
        args.argi1 = userId;
        args.arg6 = opPackageName;
        args.arg7 = operationId;

        boolean skipAnimation = false;
        if (mCurrentDialog != null) {
            Log.w(TAG, "mCurrentDialog: " + mCurrentDialog);
            skipAnimation = true;
        }

        showDialog(args, skipAnimation, null /* savedState */);
    }

    /**
     * Only called via BiometricService for the biometric prompt. Will not be called for
     * authentication directly requested through FingerprintManager. For
     * example, KeyguardUpdateMonitor has its own {@link FingerprintManager.AuthenticationCallback}.
     */
    @Override
    public void onBiometricAuthenticated() {
        mCurrentDialog.onAuthenticationSucceeded();
    }

    @Override
    public void onBiometricHelp(String message) {
        if (DEBUG) Log.d(TAG, "onBiometricHelp: " + message);

        mCurrentDialog.onHelp(message);
    }

    @Nullable
    public List<FingerprintSensorPropertiesInternal> getUdfpsProps() {
        return mUdfpsProps;
    }

    private String getErrorString(int modality, int error, int vendorCode) {
        switch (modality) {
            case TYPE_FACE:
                return FaceManager.getErrorString(mContext, error, vendorCode);

            case TYPE_FINGERPRINT:
                return FingerprintManager.getErrorString(mContext, error, vendorCode);

            default:
                return "";
        }
    }

    /**
     * Only called via BiometricService for the biometric prompt. Will not be called for
     * authentication directly requested through FingerprintManager. For
     * example, KeyguardUpdateMonitor has its own {@link FingerprintManager.AuthenticationCallback}.
     */
    @Override
    public void onBiometricError(int modality, int error, int vendorCode) {
        if (DEBUG) {
            Log.d(TAG, String.format("onBiometricError(%d, %d, %d)", modality, error, vendorCode));
        }

        final boolean isLockout = (error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT)
                || (error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT);

        // TODO(b/141025588): Create separate methods for handling hard and soft errors.
        final boolean isSoftError = (error == BiometricConstants.BIOMETRIC_PAUSED_REJECTED
                || error == BiometricConstants.BIOMETRIC_ERROR_TIMEOUT);

        if (mCurrentDialog.isAllowDeviceCredentials() && isLockout) {
            if (DEBUG) Log.d(TAG, "onBiometricError, lockout");
            mCurrentDialog.animateToCredentialUI();
        } else if (isSoftError) {
            final String errorMessage = (error == BiometricConstants.BIOMETRIC_PAUSED_REJECTED)
                    ? mContext.getString(R.string.biometric_not_recognized)
                    : getErrorString(modality, error, vendorCode);
            if (DEBUG) Log.d(TAG, "onBiometricError, soft error: " + errorMessage);
            mCurrentDialog.onAuthenticationFailed(errorMessage);
        } else {
            final String errorMessage = getErrorString(modality, error, vendorCode);
            if (DEBUG) Log.d(TAG, "onBiometricError, hard error: " + errorMessage);
            mCurrentDialog.onError(errorMessage);
        }
    }

    @Override
    public void hideAuthenticationDialog() {
        if (DEBUG) Log.d(TAG, "hideAuthenticationDialog: " + mCurrentDialog);

        if (mCurrentDialog == null) {
            // Could be possible if the caller canceled authentication after credential success
            // but before the client was notified.
            return;
        }

        mCurrentDialog.dismissFromSystemServer();

        // BiometricService will have already sent the callback to the client in this case.
        // This avoids a round trip to SystemUI. So, just dismiss the dialog and we're done.
        mCurrentDialog = null;
    }

   /**
     * Whether the passed userId has enrolled UDFPS.
     */
    public boolean isUdfpsEnrolled(int userId) {
        if (mUdfpsController == null) {
            return false;
        }

        return mFingerprintManager.hasEnrolledTemplatesForAnySensor(userId, mUdfpsProps);
    }

    private void showDialog(SomeArgs args, boolean skipAnimation, Bundle savedState) {
        mCurrentDialogArgs = args;

        final PromptInfo promptInfo = (PromptInfo) args.arg1;
        final int[] sensorIds = (int[]) args.arg3;
        final boolean credentialAllowed = (boolean) args.arg4;
        final boolean requireConfirmation = (boolean) args.arg5;
        final int userId = args.argi1;
        final String opPackageName = (String) args.arg6;
        final long operationId = (long) args.arg7;

        // Create a new dialog but do not replace the current one yet.
        final AuthDialog newDialog = buildDialog(
                promptInfo,
                requireConfirmation,
                userId,
                sensorIds,
                credentialAllowed,
                opPackageName,
                skipAnimation,
                operationId);

        if (newDialog == null) {
            Log.e(TAG, "Unsupported type configuration");
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "userId: " + userId
                    + " savedState: " + savedState
                    + " mCurrentDialog: " + mCurrentDialog
                    + " newDialog: " + newDialog);
        }

        if (mCurrentDialog != null) {
            // If somehow we're asked to show a dialog, the old one doesn't need to be animated
            // away. This can happen if the app cancels and re-starts auth during configuration
            // change. This is ugly because we also have to do things on onConfigurationChanged
            // here.
            mCurrentDialog.dismissWithoutCallback(false /* animate */);
        }

        mReceiver = (IBiometricSysuiReceiver) args.arg2;
        mCurrentDialog = newDialog;
        mCurrentDialog.show(mWindowManager, savedState);
    }

    private void onDialogDismissed(@DismissedReason int reason) {
        if (DEBUG) Log.d(TAG, "onDialogDismissed: " + reason);
        if (mCurrentDialog == null) {
            Log.w(TAG, "Dialog already dismissed");
        }
        mReceiver = null;
        mCurrentDialog = null;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // UdfpsController is not BiometricPrompt-specific. It can be active for keyguard or
        // enrollment.
        if (mUdfpsController != null) {
            mUdfpsController.onConfigurationChanged();
        }

        // Save the state of the current dialog (buttons showing, etc)
        if (mCurrentDialog != null) {
            final Bundle savedState = new Bundle();
            mCurrentDialog.onSaveState(savedState);
            mCurrentDialog.dismissWithoutCallback(false /* animate */);
            mCurrentDialog = null;

            // Only show the dialog if necessary. If it was animating out, the dialog is supposed
            // to send its pending callback immediately.
            if (savedState.getInt(AuthDialog.KEY_CONTAINER_STATE)
                    != AuthContainerView.STATE_ANIMATING_OUT) {
                final boolean credentialShowing =
                        savedState.getBoolean(AuthDialog.KEY_CREDENTIAL_SHOWING);
                if (credentialShowing) {
                    // There may be a cleaner way to do this, rather than altering the current
                    // authentication's parameters. This gets the job done and should be clear
                    // enough for now.
                    PromptInfo promptInfo = (PromptInfo) mCurrentDialogArgs.arg1;
                    promptInfo.setAuthenticators(Authenticators.DEVICE_CREDENTIAL);
                }

                showDialog(mCurrentDialogArgs, true /* skipAnimation */, savedState);
            }
        }
    }

    protected AuthDialog buildDialog(PromptInfo promptInfo, boolean requireConfirmation,
            int userId, int[] sensorIds, boolean credentialAllowed, String opPackageName,
            boolean skipIntro, long operationId) {
        return new AuthContainerView.Builder(mContext)
                .setCallback(this)
                .setPromptInfo(promptInfo)
                .setRequireConfirmation(requireConfirmation)
                .setUserId(userId)
                .setOpPackageName(opPackageName)
                .setSkipIntro(skipIntro)
                .setOperationId(operationId)
                .build(sensorIds, credentialAllowed, mFpProps, mFaceProps);
    }
}
