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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PointF;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricManager.BiometricMultiSensorMode;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintStateListener;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.IUdfpsHbmListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.CoreStartable;
import com.android.systemui.assist.ui.DisplayUtils;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Provider;

import kotlin.Unit;

/**
 * Receives messages sent from {@link com.android.server.biometrics.BiometricService} and shows the
 * appropriate biometric UI (e.g. BiometricDialogView).
 *
 * Also coordinates biometric-related things, such as UDFPS, with
 * {@link com.android.keyguard.KeyguardUpdateMonitor}
 */
@SysUISingleton
public class AuthController extends CoreStartable implements CommandQueue.Callbacks,
        AuthDialogCallback, DozeReceiver {

    private static final String TAG = "AuthController";
    private static final boolean DEBUG = true;
    private static final int SENSOR_PRIVACY_DELAY = 500;

    private final Handler mHandler;
    private final Execution mExecution;
    private final CommandQueue mCommandQueue;
    private final StatusBarStateController mStatusBarStateController;
    private final ActivityTaskManager mActivityTaskManager;
    @Nullable
    private final FingerprintManager mFingerprintManager;
    @Nullable
    private final FaceManager mFaceManager;
    private final Provider<UdfpsController> mUdfpsControllerFactory;
    private final Provider<SidefpsController> mSidefpsControllerFactory;
    @Nullable
    private final PointF mFaceAuthSensorLocation;
    @Nullable
    private PointF mFingerprintLocation;
    private final Set<Callback> mCallbacks = new HashSet<>();

    // TODO: These should just be saved from onSaveState
    private SomeArgs mCurrentDialogArgs;
    @VisibleForTesting
    AuthDialog mCurrentDialog;

    @NonNull private final WindowManager mWindowManager;
    @Nullable private UdfpsController mUdfpsController;
    @Nullable private IUdfpsHbmListener mUdfpsHbmListener;
    @Nullable private SidefpsController mSidefpsController;
    @Nullable private IBiometricContextListener mBiometricContextListener;
    @VisibleForTesting
    IBiometricSysuiReceiver mReceiver;
    @VisibleForTesting
    @NonNull final BiometricDisplayListener mOrientationListener;
    @Nullable private final List<FaceSensorPropertiesInternal> mFaceProps;
    @Nullable private List<FingerprintSensorPropertiesInternal> mFpProps;
    @Nullable private List<FingerprintSensorPropertiesInternal> mUdfpsProps;
    @Nullable private List<FingerprintSensorPropertiesInternal> mSidefpsProps;

    @NonNull private final SparseBooleanArray mUdfpsEnrolledForUser;
    @NonNull private final SensorPrivacyManager mSensorPrivacyManager;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private boolean mAllAuthenticatorsRegistered;
    @NonNull private final UserManager mUserManager;
    @NonNull private final LockPatternUtils mLockPatternUtils;
    private final @Background DelayableExecutor mBackgroundExecutor;

    @VisibleForTesting
    final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(AuthController.this::handleTaskStackChanged);
        }
    };

    private final IFingerprintAuthenticatorsRegisteredCallback
            mFingerprintAuthenticatorsRegisteredCallback =
            new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                @Override
                public void onAllAuthenticatorsRegistered(
                        List<FingerprintSensorPropertiesInternal> sensors) {
                    mHandler.post(() -> handleAllAuthenticatorsRegistered(sensors));
                }
            };

    private final FingerprintStateListener mFingerprintStateListener =
            new FingerprintStateListener() {
                @Override
                public void onEnrollmentsChanged(int userId, int sensorId, boolean hasEnrollments) {
                    mHandler.post(
                            () -> handleEnrollmentsChanged(userId, sensorId, hasEnrollments));
                }
            };

    @VisibleForTesting
    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mCurrentDialog != null
                    && Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                Log.w(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS received");
                mCurrentDialog.dismissWithoutCallback(true /* animate */);
                mCurrentDialog = null;
                mOrientationListener.disable();

                for (Callback cb : mCallbacks) {
                    cb.onBiometricPromptDismissed();
                }

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
        mExecution.assertIsMainThread();
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
                        mOrientationListener.disable();

                        for (Callback cb : mCallbacks) {
                            cb.onBiometricPromptDismissed();
                        }

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

    /**
     * Whether all authentictors have been registered.
     */
    public boolean areAllAuthenticatorsRegistered() {
        return mAllAuthenticatorsRegistered;
    }

    private void handleAllAuthenticatorsRegistered(
            List<FingerprintSensorPropertiesInternal> sensors) {
        mExecution.assertIsMainThread();
        if (DEBUG) {
            Log.d(TAG, "handleAllAuthenticatorsRegistered | sensors: " + Arrays.toString(
                    sensors.toArray()));
        }
        mAllAuthenticatorsRegistered = true;
        mFpProps = sensors;
        List<FingerprintSensorPropertiesInternal> udfpsProps = new ArrayList<>();
        List<FingerprintSensorPropertiesInternal> sidefpsProps = new ArrayList<>();
        for (FingerprintSensorPropertiesInternal props : mFpProps) {
            if (props.isAnyUdfpsType()) {
                udfpsProps.add(props);
            }
            if (props.isAnySidefpsType()) {
                sidefpsProps.add(props);
            }
        }
        mUdfpsProps = !udfpsProps.isEmpty() ? udfpsProps : null;
        if (mUdfpsProps != null) {
            mUdfpsController = mUdfpsControllerFactory.get();
            mUdfpsController.addCallback(new UdfpsController.Callback() {
                @Override
                public void onFingerUp() {}

                @Override
                public void onFingerDown() {
                    if (mCurrentDialog != null) {
                        mCurrentDialog.onPointerDown();
                    }
                }
            });
        }
        mSidefpsProps = !sidefpsProps.isEmpty() ? sidefpsProps : null;
        if (mSidefpsProps != null) {
            mSidefpsController = mSidefpsControllerFactory.get();
        }
        for (Callback cb : mCallbacks) {
            cb.onAllAuthenticatorsRegistered();
        }
        mFingerprintManager.registerFingerprintStateListener(mFingerprintStateListener);
    }

    private void handleEnrollmentsChanged(int userId, int sensorId, boolean hasEnrollments) {
        mExecution.assertIsMainThread();
        Log.d(TAG, "handleEnrollmentsChanged, userId: " + userId + ", sensorId: " + sensorId
                + ", hasEnrollments: " + hasEnrollments);
        if (mUdfpsProps == null) {
            Log.d(TAG, "handleEnrollmentsChanged, mUdfpsProps is null");
        } else {
            for (FingerprintSensorPropertiesInternal prop : mUdfpsProps) {
                if (prop.sensorId == sensorId) {
                    mUdfpsEnrolledForUser.put(userId, hasEnrollments);
                }
            }
        }
        for (Callback cb : mCallbacks) {
            cb.onEnrollmentsChanged();
        }
    }

    /**
     * Adds a callback. See {@link Callback}.
     */
    public void addCallback(@NonNull Callback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Removes a callback. See {@link Callback}.
     */
    public void removeCallback(@NonNull Callback callback) {
        mCallbacks.remove(callback);
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
     * @return where the UDFPS exists on the screen in pixels in portrait mode.
     */
    @Nullable public PointF getUdfpsSensorLocation() {
        if (mUdfpsController == null) {
            return null;
        }
        return new PointF(mUdfpsController.getSensorLocation().centerX(),
                mUdfpsController.getSensorLocation().centerY());
    }

    /**
     * @return where the fingerprint sensor exists in pixels in portrait mode. devices without an
     * overridden value will use the default value even if they don't have a fingerprint sensor
     */
    @Nullable public PointF getFingerprintSensorLocation() {
        if (getUdfpsSensorLocation() != null) {
            return getUdfpsSensorLocation();
        }
        return mFingerprintLocation;
    }

    /**
     * @return where the face authentication sensor exists relative to the screen in pixels in
     * portrait mode.
     */
    @Nullable public PointF getFaceAuthSensorLocation() {
        if (mFaceProps == null || mFaceAuthSensorLocation == null) {
            return null;
        }
        return new PointF(mFaceAuthSensorLocation.x, mFaceAuthSensorLocation.y);
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
     * Cancel a fingerprint scan manually. This will get rid of the white circle on the udfps
     * sensor area even if the user hasn't explicitly lifted their finger yet.
     */
    public void onCancelUdfps() {
        if (mUdfpsController == null) {
            return;
        }
        mUdfpsController.onCancelUdfps();
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
    public AuthController(Context context,
            Execution execution,
            CommandQueue commandQueue,
            ActivityTaskManager activityTaskManager,
            @NonNull WindowManager windowManager,
            @Nullable FingerprintManager fingerprintManager,
            @Nullable FaceManager faceManager,
            Provider<UdfpsController> udfpsControllerFactory,
            Provider<SidefpsController> sidefpsControllerFactory,
            @NonNull DisplayManager displayManager,
            @NonNull WakefulnessLifecycle wakefulnessLifecycle,
            @NonNull UserManager userManager,
            @NonNull LockPatternUtils lockPatternUtils,
            @NonNull StatusBarStateController statusBarStateController,
            @Main Handler handler,
            @Background DelayableExecutor bgExecutor) {
        super(context);
        mExecution = execution;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mUserManager = userManager;
        mLockPatternUtils = lockPatternUtils;
        mHandler = handler;
        mBackgroundExecutor = bgExecutor;
        mCommandQueue = commandQueue;
        mActivityTaskManager = activityTaskManager;
        mFingerprintManager = fingerprintManager;
        mFaceManager = faceManager;
        mUdfpsControllerFactory = udfpsControllerFactory;
        mSidefpsControllerFactory = sidefpsControllerFactory;
        mWindowManager = windowManager;
        mUdfpsEnrolledForUser = new SparseBooleanArray();

        mOrientationListener = new BiometricDisplayListener(
                context,
                displayManager,
                mHandler,
                BiometricDisplayListener.SensorType.Generic.INSTANCE,
                () -> {
                    onOrientationChanged();
                    return Unit.INSTANCE;
                });

        mStatusBarStateController = statusBarStateController;
        mStatusBarStateController.addCallback(new StatusBarStateController.StateListener() {
            @Override
            public void onDozingChanged(boolean isDozing) {
                notifyDozeChanged(isDozing);
            }
        });

        mFaceProps = mFaceManager != null ? mFaceManager.getSensorPropertiesInternal() : null;

        int[] faceAuthLocation = context.getResources().getIntArray(
                com.android.systemui.R.array.config_face_auth_props);
        if (faceAuthLocation == null || faceAuthLocation.length < 2) {
            mFaceAuthSensorLocation = null;
        } else {
            mFaceAuthSensorLocation = new PointF(
                    (float) faceAuthLocation[0],
                    (float) faceAuthLocation[1]);
        }

        updateFingerprintLocation();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);

        context.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED_UNAUDITED);
        mSensorPrivacyManager = context.getSystemService(SensorPrivacyManager.class);
    }

    private void updateFingerprintLocation() {
        int xLocation = DisplayUtils.getWidth(mContext) / 2;
        try {
            xLocation = mContext.getResources().getDimensionPixelSize(
                    com.android.systemui.R.dimen
                            .physical_fingerprint_sensor_center_screen_location_x);
        } catch (Resources.NotFoundException e) {
        }
        int yLocation = mContext.getResources().getDimensionPixelSize(
                com.android.systemui.R.dimen.physical_fingerprint_sensor_center_screen_location_y);
        mFingerprintLocation = new PointF(
                xLocation,
                yLocation);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void start() {
        mCommandQueue.addCallback(this);

        if (mFingerprintManager != null) {
            mFingerprintManager.addAuthenticatorsRegisteredCallback(
                    mFingerprintAuthenticatorsRegisteredCallback);
        }

        mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
    }

    @Override
    public void setBiometicContextListener(IBiometricContextListener listener) {
        mBiometricContextListener = listener;
        notifyDozeChanged(mStatusBarStateController.isDozing());
    }

    private void notifyDozeChanged(boolean isDozing) {
        if (mBiometricContextListener != null) {
            try {
                mBiometricContextListener.onDozeChanged(isDozing);
            } catch (RemoteException e) {
                Log.w(TAG, "failed to notify initial doze state");
            }
        }
    }

    /**
     * Stores the listener received from {@link com.android.server.display.DisplayModeDirector}.
     *
     * DisplayModeDirector implements {@link IUdfpsHbmListener} and registers it with this class by
     * calling {@link CommandQueue#setUdfpsHbmListener(IUdfpsHbmListener)}.
     */
    @Override
    public void setUdfpsHbmListener(IUdfpsHbmListener listener) {
        mUdfpsHbmListener = listener;
    }

    /**
     * @return IUdfpsHbmListener that can be set by DisplayModeDirector.
     */
    @Nullable public IUdfpsHbmListener getUdfpsHbmListener() {
        return mUdfpsHbmListener;
    }

    @Override
    public void showAuthenticationDialog(PromptInfo promptInfo, IBiometricSysuiReceiver receiver,
            int[] sensorIds, boolean credentialAllowed, boolean requireConfirmation,
            int userId, long operationId, String opPackageName, long requestId,
            @BiometricMultiSensorMode int multiSensorConfig) {
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
                    + ", operationId: " + operationId
                    + ", requestId: " + requestId
                    + ", multiSensorConfig: " + multiSensorConfig);
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = promptInfo;
        args.arg2 = receiver;
        args.arg3 = sensorIds;
        args.arg4 = credentialAllowed;
        args.arg5 = requireConfirmation;
        args.argi1 = userId;
        args.arg6 = opPackageName;
        args.argl1 = operationId;
        args.argl2 = requestId;
        args.argi2 = multiSensorConfig;

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
    public void onBiometricAuthenticated(@Modality int modality) {
        if (DEBUG) Log.d(TAG, "onBiometricAuthenticated: ");

        if (mCurrentDialog != null) {
            mCurrentDialog.onAuthenticationSucceeded(modality);
        } else {
            Log.w(TAG, "onBiometricAuthenticated callback but dialog gone");
        }
    }

    @Override
    public void onBiometricHelp(@Modality int modality, String message) {
        if (DEBUG) Log.d(TAG, "onBiometricHelp: " + message);

        if (mCurrentDialog != null) {
            mCurrentDialog.onHelp(modality, message);
        } else {
            Log.w(TAG, "onBiometricHelp callback but dialog gone");
        }
    }

    @Nullable
    public List<FingerprintSensorPropertiesInternal> getUdfpsProps() {
        return mUdfpsProps;
    }

    private String getErrorString(@Modality int modality, int error, int vendorCode) {
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
    public void onBiometricError(@Modality int modality, int error, int vendorCode) {
        if (DEBUG) {
            Log.d(TAG, String.format("onBiometricError(%d, %d, %d)", modality, error, vendorCode));
        }

        final boolean isLockout = (error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT)
                || (error == BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT);

        boolean isCameraPrivacyEnabled = false;
        if (error == BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE
                && mSensorPrivacyManager.isSensorPrivacyEnabled(
                SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE, SensorPrivacyManager.Sensors.CAMERA)) {
            isCameraPrivacyEnabled = true;
        }
        // TODO(b/141025588): Create separate methods for handling hard and soft errors.
        final boolean isSoftError = (error == BiometricConstants.BIOMETRIC_PAUSED_REJECTED
                || error == BiometricConstants.BIOMETRIC_ERROR_TIMEOUT
                || isCameraPrivacyEnabled);
        if (mCurrentDialog != null) {
            if (mCurrentDialog.isAllowDeviceCredentials() && isLockout) {
                if (DEBUG) Log.d(TAG, "onBiometricError, lockout");
                mCurrentDialog.animateToCredentialUI();
            } else if (isSoftError) {
                final String errorMessage = (error == BiometricConstants.BIOMETRIC_PAUSED_REJECTED)
                        ? mContext.getString(R.string.biometric_not_recognized)
                        : getErrorString(modality, error, vendorCode);
                if (DEBUG) Log.d(TAG, "onBiometricError, soft error: " + errorMessage);
                // The camera privacy error can return before the prompt initializes its state,
                // causing the prompt to appear to endlessly authenticate. Add a small delay
                // to stop this.
                if (isCameraPrivacyEnabled) {
                    mHandler.postDelayed(() -> {
                        mCurrentDialog.onAuthenticationFailed(modality,
                                mContext.getString(R.string.face_sensor_privacy_enabled));
                    }, SENSOR_PRIVACY_DELAY);
                } else {
                    mCurrentDialog.onAuthenticationFailed(modality, errorMessage);
                }
            } else {
                final String errorMessage = getErrorString(modality, error, vendorCode);
                if (DEBUG) Log.d(TAG, "onBiometricError, hard error: " + errorMessage);
                mCurrentDialog.onError(modality, errorMessage);
            }

        } else {
            Log.w(TAG, "onBiometricError callback but dialog is gone");
        }

        onCancelUdfps();
    }

    @Override
    public void hideAuthenticationDialog(long requestId) {
        if (DEBUG) Log.d(TAG, "hideAuthenticationDialog: " + mCurrentDialog);

        if (mCurrentDialog == null) {
            // Could be possible if the caller canceled authentication after credential success
            // but before the client was notified.
            if (DEBUG) Log.d(TAG, "dialog already gone");
            return;
        }
        if (requestId != mCurrentDialog.getRequestId()) {
            Log.w(TAG, "ignore - ids do not match: " + requestId + " current: "
                    + mCurrentDialog.getRequestId());
            return;
        }

        mCurrentDialog.dismissFromSystemServer();

        // BiometricService will have already sent the callback to the client in this case.
        // This avoids a round trip to SystemUI. So, just dismiss the dialog and we're done.
        mCurrentDialog = null;
        mOrientationListener.disable();
    }

    /**
     * Whether the user's finger is currently on udfps attempting to authenticate.
     */
    public boolean isUdfpsFingerDown() {
        if (mUdfpsController == null)  {
            return false;
        }

        return mUdfpsController.isFingerDown();
    }

    /**
     * Whether the passed userId has enrolled face auth.
     */
    public boolean isFaceAuthEnrolled(int userId) {
        if (mFaceProps == null) {
            return false;
        }

        return mFaceManager.hasEnrolledTemplates(userId);
    }

    /**
     * Whether the passed userId has enrolled UDFPS.
     */
    public boolean isUdfpsEnrolled(int userId) {
        if (mUdfpsController == null) {
            return false;
        }

        return mUdfpsEnrolledForUser.get(userId);
    }

    private void showDialog(SomeArgs args, boolean skipAnimation, Bundle savedState) {
        mCurrentDialogArgs = args;

        final PromptInfo promptInfo = (PromptInfo) args.arg1;
        final int[] sensorIds = (int[]) args.arg3;
        final boolean credentialAllowed = (boolean) args.arg4;
        final boolean requireConfirmation = (boolean) args.arg5;
        final int userId = args.argi1;
        final String opPackageName = (String) args.arg6;
        final long operationId = args.argl1;
        final long requestId = args.argl2;
        @BiometricMultiSensorMode final int multiSensorConfig = args.argi2;

        // Create a new dialog but do not replace the current one yet.
        final AuthDialog newDialog = buildDialog(
                mBackgroundExecutor,
                promptInfo,
                requireConfirmation,
                userId,
                sensorIds,
                opPackageName,
                skipAnimation,
                operationId,
                requestId,
                multiSensorConfig,
                mWakefulnessLifecycle,
                mUserManager,
                mLockPatternUtils);

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
        for (Callback cb : mCallbacks) {
            cb.onBiometricPromptShown();
        }
        mCurrentDialog = newDialog;
        mCurrentDialog.show(mWindowManager, savedState);
        mOrientationListener.enable();
    }

    private void onDialogDismissed(@DismissedReason int reason) {
        if (DEBUG) Log.d(TAG, "onDialogDismissed: " + reason);
        if (mCurrentDialog == null) {
            Log.w(TAG, "Dialog already dismissed");
        }

        for (Callback cb : mCallbacks) {
            cb.onBiometricPromptDismissed();
        }

        mReceiver = null;
        mCurrentDialog = null;
        mOrientationListener.disable();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateFingerprintLocation();

        // Save the state of the current dialog (buttons showing, etc)
        if (mCurrentDialog != null) {
            final Bundle savedState = new Bundle();
            mCurrentDialog.onSaveState(savedState);
            mCurrentDialog.dismissWithoutCallback(false /* animate */);
            mCurrentDialog = null;
            mOrientationListener.disable();

            // Only show the dialog if necessary. If it was animating out, the dialog is supposed
            // to send its pending callback immediately.
            if (!savedState.getBoolean(AuthDialog.KEY_CONTAINER_GOING_AWAY, false)) {
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

    private void onOrientationChanged() {
        updateFingerprintLocation();
        if (mCurrentDialog != null) {
            mCurrentDialog.onOrientationChanged();
        }
    }

    protected AuthDialog buildDialog(@Background DelayableExecutor bgExecutor,
            PromptInfo promptInfo, boolean requireConfirmation, int userId, int[] sensorIds,
            String opPackageName, boolean skipIntro, long operationId, long requestId,
            @BiometricMultiSensorMode int multiSensorConfig,
            @NonNull WakefulnessLifecycle wakefulnessLifecycle,
            @NonNull UserManager userManager,
            @NonNull LockPatternUtils lockPatternUtils) {
        return new AuthContainerView.Builder(mContext)
                .setCallback(this)
                .setPromptInfo(promptInfo)
                .setRequireConfirmation(requireConfirmation)
                .setUserId(userId)
                .setOpPackageName(opPackageName)
                .setSkipIntro(skipIntro)
                .setOperationId(operationId)
                .setRequestId(requestId)
                .setMultiSensorConfig(multiSensorConfig)
                .build(bgExecutor, sensorIds, mFpProps, mFaceProps, wakefulnessLifecycle,
                        userManager, lockPatternUtils);
    }

    /**
     * AuthController callback used to receive signal for when biometric authenticators are
     * registered.
     */
    public interface Callback {
        /**
         * Called when authenticators are registered. If authenticators are already
         * registered before this call, this callback will never be triggered.
         */
        default void onAllAuthenticatorsRegistered() {}

        /**
         * Called when UDFPS enrollments have changed. This is called after boot and on changes to
         * enrollment.
         */
        default void onEnrollmentsChanged() {}

        /**
         * Called when the biometric prompt starts showing.
         */
        default void onBiometricPromptShown() {}

        /**
         * Called when the biometric prompt is no longer showing.
         */
        default void onBiometricPromptDismissed() {}
    }
}
