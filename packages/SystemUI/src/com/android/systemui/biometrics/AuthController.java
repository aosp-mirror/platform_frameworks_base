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
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_REAR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager.Authenticators;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.IUdfpsRefreshRateRequestCallback;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.Log;
import android.util.RotationUtils;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.os.SomeArgs;
import com.android.internal.widget.LockPatternUtils;
import com.android.settingslib.udfps.UdfpsOverlayParams;
import com.android.settingslib.udfps.UdfpsUtils;
import com.android.systemui.CoreStartable;
import com.android.systemui.biometrics.domain.interactor.LogContextInteractor;
import com.android.systemui.biometrics.domain.interactor.PromptCredentialInteractor;
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor;
import com.android.systemui.biometrics.ui.viewmodel.AuthBiometricFingerprintViewModel;
import com.android.systemui.biometrics.ui.viewmodel.CredentialViewModel;
import com.android.systemui.biometrics.ui.viewmodel.PromptViewModel;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.data.repository.BiometricType;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Provider;

import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;

/**
 * Receives messages sent from {@link com.android.server.biometrics.BiometricService} and shows the
 * appropriate biometric UI (e.g. BiometricDialogView).
 *
 * Also coordinates biometric-related things, such as UDFPS, with
 * {@link com.android.keyguard.KeyguardUpdateMonitor}
 */
@SysUISingleton
public class AuthController implements CoreStartable, CommandQueue.Callbacks,
        AuthDialogCallback, DozeReceiver {

    private static final String TAG = "AuthController";
    private static final boolean DEBUG = true;
    private static final int SENSOR_PRIVACY_DELAY = 500;

    private final Handler mHandler;
    private final Context mContext;
    private final FeatureFlags mFeatureFlags;
    private final Execution mExecution;
    private final CommandQueue mCommandQueue;
    private final ActivityTaskManager mActivityTaskManager;
    @Nullable private final FingerprintManager mFingerprintManager;
    @Nullable private final FaceManager mFaceManager;
    private final Provider<UdfpsController> mUdfpsControllerFactory;
    private final Provider<SideFpsController> mSidefpsControllerFactory;
    private final CoroutineScope mApplicationCoroutineScope;

    // TODO: these should be migrated out once ready
    @NonNull private final Provider<AuthBiometricFingerprintViewModel>
            mAuthBiometricFingerprintViewModelProvider;
    @NonNull private final Provider<PromptCredentialInteractor> mPromptCredentialInteractor;
    @NonNull private final Provider<PromptSelectorInteractor> mPromptSelectorInteractor;
    @NonNull private final Provider<CredentialViewModel> mCredentialViewModelProvider;
    @NonNull private final Provider<PromptViewModel> mPromptViewModelProvider;
    @NonNull private final LogContextInteractor mLogContextInteractor;

    private final Display mDisplay;
    private float mScaleFactor = 1f;
    // sensor locations without any resolution scaling nor rotation adjustments:
    @Nullable private final Point mFaceSensorLocationDefault;
    // cached sensor locations:
    @Nullable private Point mFaceSensorLocation;
    @Nullable private Point mFingerprintSensorLocation;
    @Nullable private Rect mUdfpsBounds;
    private final Set<Callback> mCallbacks = new HashSet<>();

    // TODO: These should just be saved from onSaveState
    private SomeArgs mCurrentDialogArgs;
    @VisibleForTesting
    AuthDialog mCurrentDialog;

    @NonNull private final WindowManager mWindowManager;
    @NonNull private final DisplayManager mDisplayManager;
    @Nullable private UdfpsController mUdfpsController;
    @Nullable private UdfpsOverlayParams mUdfpsOverlayParams;
    @Nullable private IUdfpsRefreshRateRequestCallback mUdfpsRefreshRateRequestCallback;
    @Nullable private SideFpsController mSideFpsController;
    @Nullable private UdfpsLogger mUdfpsLogger;
    @VisibleForTesting IBiometricSysuiReceiver mReceiver;
    @VisibleForTesting @NonNull final BiometricDisplayListener mOrientationListener;
    @Nullable private final List<FaceSensorPropertiesInternal> mFaceProps;
    @Nullable private List<FingerprintSensorPropertiesInternal> mFpProps;
    @Nullable private List<FingerprintSensorPropertiesInternal> mUdfpsProps;
    @Nullable private List<FingerprintSensorPropertiesInternal> mSidefpsProps;

    @NonNull private final Map<Integer, Boolean> mFpEnrolledForUser = new HashMap<>();
    @NonNull private final SparseBooleanArray mUdfpsEnrolledForUser;
    @NonNull private final SparseBooleanArray mFaceEnrolledForUser;
    @NonNull private final SparseBooleanArray mSfpsEnrolledForUser;
    @NonNull private final SensorPrivacyManager mSensorPrivacyManager;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final AuthDialogPanelInteractionDetector mPanelInteractionDetector;
    private boolean mAllFingerprintAuthenticatorsRegistered;
    @NonNull private final UserManager mUserManager;
    @NonNull private final LockPatternUtils mLockPatternUtils;
    @NonNull private final InteractionJankMonitor mInteractionJankMonitor;
    @NonNull private final UdfpsUtils mUdfpsUtils;
    private final @Background DelayableExecutor mBackgroundExecutor;
    private final DisplayInfo mCachedDisplayInfo = new DisplayInfo();

    private final VibratorHelper mVibratorHelper;

    private void vibrateSuccess(int modality) {
        mVibratorHelper.vibrateAuthSuccess(
                getClass().getSimpleName() + ", modality = " + modality + "BP::success");
    }

    private void vibrateError(int modality) {
        mVibratorHelper.vibrateAuthError(
                getClass().getSimpleName() + ", modality = " + modality + "BP::error");
    }

    @VisibleForTesting
    final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(AuthController.this::cancelIfOwnerIsNotInForeground);
        }
    };

    @VisibleForTesting
    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                String reason = intent.getStringExtra("reason");
                reason = (reason != null) ? reason : "unknown";
                closeDioalog(reason);
            }
        }
    };

    private void closeDioalog(String reason) {
        if (isShowing()) {
            Log.i(TAG, "Close BP, reason :" + reason);
            mCurrentDialog.dismissWithoutCallback(true /* animate */);
            mCurrentDialog = null;

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

    private void cancelIfOwnerIsNotInForeground() {
        mExecution.assertIsMainThread();
        if (mCurrentDialog != null) {
            try {
                if (isOwnerInBackground()) {
                    Log.w(TAG, "Evicting client due to top activity is not : "
                            + mCurrentDialog.getOpPackageName());
                    mCurrentDialog.dismissWithoutCallback(true /* animate */);
                    mCurrentDialog = null;

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
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception", e);
            }
        }
    }

    private boolean isOwnerInBackground() {
        if (mCurrentDialog != null) {
            final String clientPackage = mCurrentDialog.getOpPackageName();

            final List<ActivityManager.RunningTaskInfo> runningTasks =
                    mActivityTaskManager.getTasks(1);
            if (runningTasks == null || runningTasks.isEmpty()) {
                Log.w(TAG, "No running tasks reported");
                return false;
            }

            final boolean isSystemApp = Utils.isSystem(mContext, clientPackage);

            final ComponentName topActivity = runningTasks.get(0).topActivity;
            final String topPackage =  topActivity.getPackageName();
            final boolean topPackageEqualsToClient =
                    topPackage == null
                            || topActivity.getPackageName().contentEquals(clientPackage);

            // b/339532378: If it's ConfirmDeviceCredentialActivity, we need to check further on
            // class name.
            final String clientClassNameForCDCA =
                    mCurrentDialog.getClassNameIfItIsConfirmDeviceCredentialActivity();
            final boolean isClientCDCA = clientClassNameForCDCA != null;
            final String topClassName = topActivity.getClassName();
            final boolean isCDCAWithWrongTopClass =
                    isClientCDCA
                            && !(topClassName == null
                                    || topClassName.contentEquals(clientClassNameForCDCA));

            final boolean isInBackground =
                    !(isSystemApp || topPackageEqualsToClient) || isCDCAWithWrongTopClass;

            Log.w(TAG, "isInBackground " + isInBackground);
            return isInBackground;
        }
        return false;
    }

    /**
     * Whether all fingerprint authentictors have been registered.
     */
    public boolean areAllFingerprintAuthenticatorsRegistered() {
        return mAllFingerprintAuthenticatorsRegistered;
    }

    private void handleAllFingerprintAuthenticatorsRegistered(
            List<FingerprintSensorPropertiesInternal> sensors) {
        mExecution.assertIsMainThread();
        if (DEBUG) {
            Log.d(TAG, "handleAllFingerprintAuthenticatorsRegistered | sensors: "
                    + Arrays.toString(sensors.toArray()));
        }
        mAllFingerprintAuthenticatorsRegistered = true;
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
                public void onFingerUp() {
                }

                @Override
                public void onFingerDown() {
                    if (mCurrentDialog != null) {
                        mCurrentDialog.onPointerDown();
                    }
                }
            });
            mUdfpsController.setAuthControllerUpdateUdfpsLocation(this::updateUdfpsLocation);
            mUdfpsController.setUdfpsDisplayMode(new UdfpsDisplayMode(mContext, mExecution,
                    this, mUdfpsLogger));
            mUdfpsBounds = mUdfpsProps.get(0).getLocation().getRect();
        }

        mSidefpsProps = !sidefpsProps.isEmpty() ? sidefpsProps : null;
        if (mSidefpsProps != null) {
            mSideFpsController = mSidefpsControllerFactory.get();
        }

        mFingerprintManager.registerBiometricStateListener(new BiometricStateListener() {
            @Override
            public void onEnrollmentsChanged(int userId, int sensorId, boolean hasEnrollments) {
                mHandler.post(() -> handleEnrollmentsChanged(
                        TYPE_FINGERPRINT, userId, sensorId, hasEnrollments));
            }
        });
        updateSensorLocations();

        for (Callback cb : mCallbacks) {
            cb.onAllAuthenticatorsRegistered(TYPE_FINGERPRINT);
        }
    }

    private void handleAllFaceAuthenticatorsRegistered(List<FaceSensorPropertiesInternal> sensors) {
        mExecution.assertIsMainThread();
        if (DEBUG) {
            Log.d(TAG, "handleAllFaceAuthenticatorsRegistered | sensors: " + Arrays.toString(
                    sensors.toArray()));
        }

        mFaceManager.registerBiometricStateListener(new BiometricStateListener() {
            @Override
            public void onEnrollmentsChanged(int userId, int sensorId, boolean hasEnrollments) {
                mHandler.post(() -> handleEnrollmentsChanged(
                        TYPE_FACE, userId, sensorId, hasEnrollments));
            }
        });

        for (Callback cb : mCallbacks) {
            cb.onAllAuthenticatorsRegistered(TYPE_FACE);
        }
    }

    private void handleEnrollmentsChanged(@Modality int modality, int userId, int sensorId,
            boolean hasEnrollments) {
        mExecution.assertIsMainThread();
        Log.d(TAG, "handleEnrollmentsChanged, userId: " + userId + ", sensorId: " + sensorId
                + ", hasEnrollments: " + hasEnrollments);
        BiometricType sensorBiometricType = BiometricType.UNKNOWN;
        if (mFpProps != null) {
            for (FingerprintSensorPropertiesInternal prop: mFpProps) {
                if (prop.sensorId == sensorId) {
                    mFpEnrolledForUser.put(userId, hasEnrollments);
                    if (prop.isAnyUdfpsType()) {
                        sensorBiometricType = BiometricType.UNDER_DISPLAY_FINGERPRINT;
                        mUdfpsEnrolledForUser.put(userId, hasEnrollments);
                    } else if (prop.isAnySidefpsType()) {
                        sensorBiometricType = BiometricType.SIDE_FINGERPRINT;
                        mSfpsEnrolledForUser.put(userId, hasEnrollments);
                    } else if (prop.sensorType == TYPE_REAR) {
                        sensorBiometricType = BiometricType.REAR_FINGERPRINT;
                    }
                    break;
                }
            }
        }
        if (mFaceProps == null) {
            Log.d(TAG, "handleEnrollmentsChanged, mFaceProps is null");
        } else {
            for (FaceSensorPropertiesInternal prop : mFaceProps) {
                if (prop.sensorId == sensorId) {
                    mFaceEnrolledForUser.put(userId, hasEnrollments);
                    sensorBiometricType = BiometricType.FACE;
                    break;
                }
            }
        }
        for (Callback cb : mCallbacks) {
            cb.onEnrollmentsChanged(modality);
            cb.onEnrollmentsChanged(sensorBiometricType, userId, hasEnrollments);
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
    public void onTryAgainPressed(long requestId) {
        final IBiometricSysuiReceiver receiver = getCurrentReceiver(requestId);
        if (receiver == null) {
            Log.w(TAG, "Skip onTryAgainPressed");
            return;
        }

        try {
            receiver.onTryAgainPressed();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when handling try again", e);
        }
    }

    @Override
    public void onDeviceCredentialPressed(long requestId) {
        final IBiometricSysuiReceiver receiver = getCurrentReceiver(requestId);
        if (receiver == null) {
            Log.w(TAG, "Skip onDeviceCredentialPressed");
            return;
        }

        try {
            receiver.onDeviceCredentialPressed();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when handling credential button", e);
        }
    }

    @Override
    public void onSystemEvent(int event, long requestId) {
        final IBiometricSysuiReceiver receiver = getCurrentReceiver(requestId);
        if (receiver == null) {
            Log.w(TAG, "Skip onSystemEvent");
            return;
        }

        try {
            receiver.onSystemEvent(event);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when sending system event", e);
        }
    }

    @Override
    public void onDialogAnimatedIn(long requestId, boolean startFingerprintNow) {
        final IBiometricSysuiReceiver receiver = getCurrentReceiver(requestId);
        if (receiver == null) {
            Log.w(TAG, "Skip onDialogAnimatedIn");
            return;
        }

        try {
            receiver.onDialogAnimatedIn(startFingerprintNow);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when sending onDialogAnimatedIn", e);
        }
    }

    @Override
    public void onStartFingerprintNow(long requestId) {
        final IBiometricSysuiReceiver receiver = getCurrentReceiver(requestId);
        if (receiver == null) {
            Log.e(TAG, "onStartUdfpsNow: Receiver is null");
            return;
        }

        try {
            receiver.onStartFingerprintNow();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException when sending onDialogAnimatedIn", e);
        }
    }

    @Nullable
    private IBiometricSysuiReceiver getCurrentReceiver(long requestId) {
        if (!isRequestIdValid(requestId)) {
            return null;
        }

        if (mReceiver == null) {
            Log.w(TAG, "getCurrentReceiver: Receiver is null");
        }

        return mReceiver;
    }

    private boolean isRequestIdValid(long requestId) {
        if (mCurrentDialog == null) {
            Log.w(TAG, "shouldNotifyReceiver: dialog already gone");
            return false;
        }

        if (requestId != mCurrentDialog.getRequestId()) {
            Log.w(TAG, "shouldNotifyReceiver: requestId doesn't match");
            return false;
        }

        return true;
    }

    @Override
    public void onDismissed(@DismissedReason int reason,
                            @Nullable byte[] credentialAttestation, long requestId) {

        if (mCurrentDialog != null && requestId != mCurrentDialog.getRequestId()) {
            Log.w(TAG, "requestId doesn't match, skip onDismissed");
            return;
        }

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

    @Override
    public void handleShowGlobalActionsMenu() {
        closeDioalog("PowerMenu shown");
    }

    /**
     * @return where the UDFPS exists on the screen in pixels in portrait mode.
     */
    @Nullable public Point getUdfpsLocation() {
        if (mUdfpsController == null || mUdfpsBounds == null) {
            return null;
        }
        return new Point(mUdfpsBounds.centerX(), mUdfpsBounds.centerY());
    }

    /**
     * @return the radius of UDFPS on the screen in pixels
     */
    public float getUdfpsRadius() {
        if (mUdfpsController == null || mUdfpsBounds == null) {
            return -1;
        }
        return mUdfpsBounds.height() / 2f;
    }

    /**
     * Gets the cached scale factor representing the user's current resolution / the stable
     * (default) resolution.
     */
    public float getScaleFactor() {
        return mScaleFactor;
    }

    /**
     * Updates the current display info and cached scale factor & sensor locations.
     * Getting the display info is a relatively expensive call, so avoid superfluous calls.
     */
    private void updateSensorLocations() {
        mDisplay.getDisplayInfo(mCachedDisplayInfo);
        mScaleFactor = mUdfpsUtils.getScaleFactor(mCachedDisplayInfo);
        updateUdfpsLocation();
        updateFingerprintLocation();
        updateFaceLocation();
    }
    /**
     * @return where the fingerprint sensor exists in pixels in its natural orientation.
     * Devices without location configs will use the default value even if they don't have a
     * fingerprint sensor.
     *
     * May return null if the fingerprint sensor isn't available yet.
     */
    @Nullable private Point getFingerprintSensorLocationInNaturalOrientation() {
        if (getUdfpsLocation() != null) {
            return getUdfpsLocation();
        } else {
            int xFpLocation = mCachedDisplayInfo.getNaturalWidth() / 2;
            try {
                xFpLocation = mContext.getResources().getDimensionPixelSize(
                        com.android.systemui.R.dimen
                                .physical_fingerprint_sensor_center_screen_location_x);
            } catch (Resources.NotFoundException e) {
            }

            return new Point(
                    (int) (xFpLocation * mScaleFactor),
                    (int) (mContext.getResources().getDimensionPixelSize(
                            com.android.systemui.R.dimen
                                    .physical_fingerprint_sensor_center_screen_location_y)
                            * mScaleFactor)
            );
        }
    }

    /**
     * @return where the fingerprint sensor exists in pixels exists the current device orientation.
     * Devices without location configs will use the default value even if they don't have a
     * fingerprint sensor.
     */
    @Nullable public Point getFingerprintSensorLocation() {
        return mFingerprintSensorLocation;
    }

    private void updateFingerprintLocation() {
        if (mFpProps == null) {
            mFingerprintSensorLocation = null;
        } else {
            mFingerprintSensorLocation = rotateToCurrentOrientation(
                    getFingerprintSensorLocationInNaturalOrientation(),
                    mCachedDisplayInfo);
        }

        for (final Callback cb : mCallbacks) {
            cb.onFingerprintLocationChanged();
        }
    }

    /** Get FP sensor properties */
    public @Nullable List<FingerprintSensorPropertiesInternal> getFingerprintProperties() {
        return mFpProps;
    }

    /**
     * @return where the face sensor exists in pixels in the current device orientation. Returns
     * null if no face sensor exists.
     */
    @Nullable public Point getFaceSensorLocation() {
        return mFaceSensorLocation;
    }

    private void updateFaceLocation() {
        if (mFaceProps == null || mFaceSensorLocationDefault == null) {
            mFaceSensorLocation = null;
        } else {
            mFaceSensorLocation = rotateToCurrentOrientation(
                    new Point(
                            (int) (mFaceSensorLocationDefault.x * mScaleFactor),
                            (int) (mFaceSensorLocationDefault.y * mScaleFactor)),
                    mCachedDisplayInfo
            );
        }

        for (final Callback cb : mCallbacks) {
            cb.onFaceSensorLocationChanged();
        }
    }

    /**
     * @param inOutPoint point on the display in pixels. Going in, represents the point
     *                   in the device's natural orientation. Going out, represents
     *                   the point in the display's current orientation.
     * @param displayInfo currently display information to use to rotate the point
     */
    @VisibleForTesting
    protected Point rotateToCurrentOrientation(Point inOutPoint, DisplayInfo displayInfo) {
        RotationUtils.rotatePoint(
                inOutPoint,
                displayInfo.rotation,
                displayInfo.getNaturalWidth(),
                displayInfo.getNaturalHeight()
        );
        return inOutPoint;
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
            @NonNull FeatureFlags featureFlags,
            @Application CoroutineScope applicationCoroutineScope,
            Execution execution,
            CommandQueue commandQueue,
            ActivityTaskManager activityTaskManager,
            @NonNull WindowManager windowManager,
            @Nullable FingerprintManager fingerprintManager,
            @Nullable FaceManager faceManager,
            Provider<UdfpsController> udfpsControllerFactory,
            Provider<SideFpsController> sidefpsControllerFactory,
            @NonNull DisplayManager displayManager,
            @NonNull WakefulnessLifecycle wakefulnessLifecycle,
            @NonNull AuthDialogPanelInteractionDetector panelInteractionDetector,
            @NonNull UserManager userManager,
            @NonNull LockPatternUtils lockPatternUtils,
            @NonNull UdfpsLogger udfpsLogger,
            @NonNull LogContextInteractor logContextInteractor,
            @NonNull Provider<AuthBiometricFingerprintViewModel>
                    authBiometricFingerprintViewModelProvider,
            @NonNull Provider<PromptCredentialInteractor> promptCredentialInteractorProvider,
            @NonNull Provider<PromptSelectorInteractor> promptSelectorInteractorProvider,
            @NonNull Provider<CredentialViewModel> credentialViewModelProvider,
            @NonNull Provider<PromptViewModel> promptViewModelProvider,
            @NonNull InteractionJankMonitor jankMonitor,
            @Main Handler handler,
            @Background DelayableExecutor bgExecutor,
            @NonNull VibratorHelper vibrator,
            @NonNull UdfpsUtils udfpsUtils) {
        mContext = context;
        mFeatureFlags = featureFlags;
        mExecution = execution;
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
        mUdfpsLogger = udfpsLogger;
        mDisplayManager = displayManager;
        mWindowManager = windowManager;
        mInteractionJankMonitor = jankMonitor;
        mUdfpsEnrolledForUser = new SparseBooleanArray();
        mSfpsEnrolledForUser = new SparseBooleanArray();
        mFaceEnrolledForUser = new SparseBooleanArray();
        mVibratorHelper = vibrator;
        mUdfpsUtils = udfpsUtils;
        mApplicationCoroutineScope = applicationCoroutineScope;

        mLogContextInteractor = logContextInteractor;
        mAuthBiometricFingerprintViewModelProvider = authBiometricFingerprintViewModelProvider;
        mPromptSelectorInteractor = promptSelectorInteractorProvider;
        mPromptCredentialInteractor = promptCredentialInteractorProvider;
        mPromptViewModelProvider = promptViewModelProvider;
        mCredentialViewModelProvider = credentialViewModelProvider;

        mOrientationListener = new BiometricDisplayListener(
                context,
                mDisplayManager,
                mHandler,
                BiometricDisplayListener.SensorType.Generic.INSTANCE,
                () -> {
                    onOrientationChanged();
                    return Unit.INSTANCE;
                });

        mWakefulnessLifecycle = wakefulnessLifecycle;
        mPanelInteractionDetector = panelInteractionDetector;


        mFaceProps = mFaceManager != null ? mFaceManager.getSensorPropertiesInternal() : null;
        int[] faceAuthLocation = context.getResources().getIntArray(
                com.android.systemui.R.array.config_face_auth_props);
        if (faceAuthLocation == null || faceAuthLocation.length < 2) {
            mFaceSensorLocationDefault = null;
        } else {
            mFaceSensorLocationDefault = new Point(
                    faceAuthLocation[0],
                    faceAuthLocation[1]);
        }

        mDisplay = mContext.getDisplay();
        updateSensorLocations();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED_UNAUDITED);
        mSensorPrivacyManager = context.getSystemService(SensorPrivacyManager.class);
    }

    // TODO(b/229290039): UDFPS controller should manage its dimensions on its own. Remove this.
    // This is not combined with updateFingerprintLocation because this is invoked directly from
    // UdfpsController, only when it cares about a rotation change. The implications of calling
    // updateFingerprintLocation in such a case are unclear.
    private void updateUdfpsLocation() {
        if (mUdfpsController != null) {
            final FingerprintSensorPropertiesInternal udfpsProp = mUdfpsProps.get(0);

            final Rect previousUdfpsBounds = mUdfpsBounds;
            final UdfpsOverlayParams previousUdfpsOverlayParams = mUdfpsOverlayParams;

            mUdfpsBounds = udfpsProp.getLocation().getRect();
            mUdfpsBounds.scale(mScaleFactor);

            final Rect overlayBounds = new Rect(
                    0, /* left */
                    mCachedDisplayInfo.getNaturalHeight() / 2, /* top */
                    mCachedDisplayInfo.getNaturalWidth(), /* right */
                    mCachedDisplayInfo.getNaturalHeight() /* bottom */);

            mUdfpsOverlayParams = new UdfpsOverlayParams(
                    mUdfpsBounds,
                    overlayBounds,
                    mCachedDisplayInfo.getNaturalWidth(),
                    mCachedDisplayInfo.getNaturalHeight(),
                    mScaleFactor,
                    mCachedDisplayInfo.rotation);

            mUdfpsController.updateOverlayParams(udfpsProp, mUdfpsOverlayParams);
            if (!Objects.equals(previousUdfpsBounds, mUdfpsBounds) || !Objects.equals(
                    previousUdfpsOverlayParams, mUdfpsOverlayParams)) {
                for (Callback cb : mCallbacks) {
                    cb.onUdfpsLocationChanged(mUdfpsOverlayParams);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void start() {
        mCommandQueue.addCallback(this);

        if (mFingerprintManager != null) {
            mFingerprintManager.addAuthenticatorsRegisteredCallback(
                    new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                        @Override
                        public void onAllAuthenticatorsRegistered(
                                List<FingerprintSensorPropertiesInternal> sensors) {
                            mHandler.post(() ->
                                    handleAllFingerprintAuthenticatorsRegistered(sensors));
                        }
                    });
        }
        if (mFaceManager != null) {
            mFaceManager.addAuthenticatorsRegisteredCallback(
                    new IFaceAuthenticatorsRegisteredCallback.Stub() {
                        @Override
                        public void onAllAuthenticatorsRegistered(
                                List<FaceSensorPropertiesInternal> sensors) {
                            mHandler.post(() ->
                                    handleAllFaceAuthenticatorsRegistered(sensors));
                        }
                    }
            );
        }

        mActivityTaskManager.registerTaskStackListener(mTaskStackListener);
        mOrientationListener.enable();
        updateSensorLocations();
    }

    @Override
    public void setBiometricContextListener(IBiometricContextListener listener) {
        mLogContextInteractor.addBiometricContextListener(listener);
    }

    /**
     * Stores the callback received from
     * {@link com.android.server.display.mode.DisplayModeDirector}.
     *
     * DisplayModeDirector implements {@link IUdfpsRefreshRateRequestCallback}
     * and registers it with this class by calling
     * {@link CommandQueue#setUdfpsRefreshRateCallback(IUdfpsRefreshRateRequestCallback)}.
     */
    @Override
    public void setUdfpsRefreshRateCallback(IUdfpsRefreshRateRequestCallback callback) {
        mUdfpsRefreshRateRequestCallback = callback;
    }

    /**
     * @return IUdfpsRefreshRateRequestCallback that can be set by DisplayModeDirector.
     */
    @Nullable public IUdfpsRefreshRateRequestCallback getUdfpsRefreshRateCallback() {
        return mUdfpsRefreshRateRequestCallback;
    }

    @Override
    public void showAuthenticationDialog(PromptInfo promptInfo, IBiometricSysuiReceiver receiver,
            int[] sensorIds, boolean credentialAllowed, boolean requireConfirmation,
            int userId, long operationId, String opPackageName, long requestId) {
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
                    + ", requestId: " + requestId);
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

        boolean skipAnimation = false;
        if (mCurrentDialog != null) {
            Log.w(TAG, "mCurrentDialog: " + mCurrentDialog);
            skipAnimation = true;
        }

        showDialog(args, skipAnimation, null /* savedState */, mPromptViewModelProvider.get());
    }

    /**
     * Only called via BiometricService for the biometric prompt. Will not be called for
     * authentication directly requested through FingerprintManager. For
     * example, KeyguardUpdateMonitor has its own {@link FingerprintManager.AuthenticationCallback}.
     */
    @Override
    public void onBiometricAuthenticated(@Modality int modality) {
        if (DEBUG) Log.d(TAG, "onBiometricAuthenticated: ");

        vibrateSuccess(modality);

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

    @Nullable
    public List<FingerprintSensorPropertiesInternal> getSfpsProps() {
        return mSidefpsProps;
    }

    /**
     * @return true if udfps HW is supported on this device. Can return true even if the user has
     * not enrolled udfps. This may be false if called before onAllAuthenticatorsRegistered.
     */
    public boolean isUdfpsSupported() {
        return getUdfpsProps() != null && !getUdfpsProps().isEmpty();
    }

    /**
     * @return true if sfps HW is supported on this device. Can return true even if the user has
     * not enrolled sfps. This may be false if called before onAllAuthenticatorsRegistered.
     */
    public boolean isSfpsSupported() {
        return getSfpsProps() != null && !getSfpsProps().isEmpty();
    }

    /**
     * @return true if rear fps HW is supported on this device. Can return true even if the user has
     * not enrolled sfps. This may be false if called before onAllAuthenticatorsRegistered.
     */
    public boolean isRearFpsSupported() {
        if (mFpProps != null) {
            for (FingerprintSensorPropertiesInternal prop: mFpProps) {
                if (prop.sensorType == TYPE_REAR) {
                    return true;
                }
            }
        }
        return false;
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

        vibrateError(modality);

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

        return mFaceEnrolledForUser.get(userId);
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

    /**
     * Whether the passed userId has enrolled SFPS.
     */
    public boolean isSfpsEnrolled(int userId) {
        if (mSideFpsController == null) {
            return false;
        }

        return mSfpsEnrolledForUser.get(userId);
    }

    /** If BiometricPrompt is currently being shown to the user. */
    public boolean isShowing() {
        return mCurrentDialog != null;
    }

    /**
     * Whether the passed userId has enrolled at least one fingerprint.
     */
    public boolean isFingerprintEnrolled(int userId) {
        return mFpEnrolledForUser.getOrDefault(userId, false);
    }

    private void showDialog(SomeArgs args, boolean skipAnimation, Bundle savedState,
            @Nullable PromptViewModel viewModel) {
        mCurrentDialogArgs = args;

        final PromptInfo promptInfo = (PromptInfo) args.arg1;
        final int[] sensorIds = (int[]) args.arg3;
        final boolean credentialAllowed = (boolean) args.arg4;
        final boolean requireConfirmation = (boolean) args.arg5;
        final int userId = args.argi1;
        final String opPackageName = (String) args.arg6;
        final long operationId = args.argl1;
        final long requestId = args.argl2;

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
                mWakefulnessLifecycle,
                mPanelInteractionDetector,
                mUserManager,
                mLockPatternUtils,
                viewModel);

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

        if (!promptInfo.isAllowBackgroundAuthentication()) {
            mHandler.post(this::cancelIfOwnerIsNotInForeground);
        }
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
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updateSensorLocations();

        // Save the state of the current dialog (buttons showing, etc)
        if (mCurrentDialog != null) {
            final PromptViewModel viewModel = mCurrentDialog.getViewModel();
            final Bundle savedState = new Bundle();
            mCurrentDialog.onSaveState(savedState);
            mCurrentDialog.dismissWithoutCallback(false /* animate */);
            mCurrentDialog = null;

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

                showDialog(mCurrentDialogArgs, true /* skipAnimation */, savedState, viewModel);
            }
        }
    }

    private void onOrientationChanged() {
        updateSensorLocations();
        if (mCurrentDialog != null) {
            mCurrentDialog.onOrientationChanged();
        }
    }

    protected AuthDialog buildDialog(@Background DelayableExecutor bgExecutor,
            PromptInfo promptInfo, boolean requireConfirmation, int userId, int[] sensorIds,
            String opPackageName, boolean skipIntro, long operationId, long requestId,
            @NonNull WakefulnessLifecycle wakefulnessLifecycle,
            @NonNull AuthDialogPanelInteractionDetector panelInteractionDetector,
            @NonNull UserManager userManager,
            @NonNull LockPatternUtils lockPatternUtils,
            @NonNull PromptViewModel viewModel) {
        final AuthContainerView.Config config = new AuthContainerView.Config();
        config.mContext = mContext;
        config.mCallback = this;
        config.mPromptInfo = promptInfo;
        config.mRequireConfirmation = requireConfirmation;
        config.mUserId = userId;
        config.mOpPackageName = opPackageName;
        config.mSkipIntro = skipIntro;
        config.mOperationId = operationId;
        config.mRequestId = requestId;
        config.mSensorIds = sensorIds;
        config.mScaleProvider = this::getScaleFactor;
        return new AuthContainerView(config, mFeatureFlags, mApplicationCoroutineScope, mFpProps, mFaceProps,
                wakefulnessLifecycle, panelInteractionDetector, userManager, lockPatternUtils,
                mInteractionJankMonitor, mAuthBiometricFingerprintViewModelProvider,
                mPromptCredentialInteractor, mPromptSelectorInteractor, viewModel,
                mCredentialViewModelProvider, bgExecutor);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        final AuthDialog dialog = mCurrentDialog;
        pw.println("  mCachedDisplayInfo=" + mCachedDisplayInfo);
        pw.println("  mScaleFactor=" + mScaleFactor);
        pw.println("  faceAuthSensorLocationDefault=" + mFaceSensorLocationDefault);
        pw.println("  faceAuthSensorLocation=" + getFaceSensorLocation());
        pw.println("  fingerprintSensorLocationInNaturalOrientation="
                + getFingerprintSensorLocationInNaturalOrientation());
        pw.println("  fingerprintSensorLocation=" + getFingerprintSensorLocation());
        pw.println("  udfpsBounds=" + mUdfpsBounds);
        pw.println("  allFingerprintAuthenticatorsRegistered="
                + mAllFingerprintAuthenticatorsRegistered);
        pw.println("  currentDialog=" + dialog);
        if (dialog != null) {
            dialog.dump(pw, args);
        }
    }

    /**
     * Provides a float that represents the resolution scale(if the controller is for UDFPS).
     */
    public interface ScaleFactorProvider {
        /**
         * Returns a float representing the scaled resolution(if the controller if for UDFPS).
         */
        float provide();
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
        default void onAllAuthenticatorsRegistered(@Modality int modality) {}

        /**
         * Called when enrollments have changed. This is called after boot and on changes to
         * enrollment.
         */
        default void onEnrollmentsChanged(@Modality int modality) {}

        /**
         * Called when enrollments have changed. This is called after boot and on changes to
         * enrollment.
         */
        default void onEnrollmentsChanged(
                @NonNull BiometricType biometricType,
                int userId,
                boolean hasEnrollments
        ) {}

        /**
         * Called when the biometric prompt starts showing.
         */
        default void onBiometricPromptShown() {}

        /**
         * Called when the biometric prompt is no longer showing.
         */
        default void onBiometricPromptDismissed() {}

        /**
         * Called when the location of the fingerprint sensor changes. The location in pixels can
         * change due to resolution changes.
         */
        default void onFingerprintLocationChanged() {}

        /**
         * Called when the location of the under display fingerprint sensor changes. The location in
         * pixels can change due to resolution changes.
         *
         * On devices with UDFPS, this is always called alongside
         * {@link #onFingerprintLocationChanged}.
         */
        default void onUdfpsLocationChanged(UdfpsOverlayParams udfpsOverlayParams) {}

        /**
         * Called when the location of the face unlock sensor (typically the front facing camera)
         * changes. The location in pixels can change due to resolution changes.
         */
        default void onFaceSensorLocationChanged() {}
    }
}
