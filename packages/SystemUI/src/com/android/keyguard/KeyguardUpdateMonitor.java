/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.keyguard;

import static android.app.StatusBarManager.SESSION_KEYGUARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.ACTION_USER_STOPPED;
import static android.content.Intent.ACTION_USER_UNLOCKED;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_NONE;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_PERMANENT;
import static android.hardware.biometrics.BiometricConstants.BIOMETRIC_LOCKOUT_TIMED;
import static android.hardware.biometrics.BiometricConstants.LockoutMode;
import static android.hardware.biometrics.BiometricSourceType.FACE;
import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;
import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;
import static android.os.BatteryManager.CHARGING_POLICY_DEFAULT;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_OPENED;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN;

import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.annotation.SuppressLint;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.AlarmManager;
import android.app.IActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.SensorProperties;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintManager.AuthenticationCallback;
import android.hardware.fingerprint.FingerprintManager.AuthenticationResult;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.telephony.CarrierConfigManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.foldables.FoldGracePeriodProvider;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.logging.KeyguardUpdateMonitorLogger;
import com.android.settingslib.Utils;
import com.android.settingslib.WirelessUtils;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.CoreStartable;
import com.android.systemui.Dumpable;
import com.android.systemui.Flags;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.deviceentry.data.repository.FaceWakeUpTriggersConfig;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.deviceentry.domain.interactor.FaceAuthenticationListener;
import com.android.systemui.deviceentry.shared.model.AcquiredFaceAuthenticationStatus;
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus;
import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus;
import com.android.systemui.deviceentry.shared.model.FaceDetectionStatus;
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus;
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus;
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.dump.DumpsysTableLogger;
import com.android.systemui.keyguard.shared.constants.TrustAgentUiEvent;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.clocks.WeatherData;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.statusbar.policy.DevicePostureController.DevicePostureInt;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.Assert;

import dalvik.annotation.optimization.NeverCompile;

import com.google.android.collect.Lists;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Watches for updates that may be interesting to the keyguard, and provides
 * the up to date information as well as a registration for callbacks that care
 * to be updated.
 */
@SysUISingleton
public class KeyguardUpdateMonitor implements TrustManager.TrustListener, Dumpable, CoreStartable {

    private static final String TAG = "KeyguardUpdateMonitor";
    private static final int BIOMETRIC_LOCKOUT_RESET_DELAY_MS = 600;

    // Callback messages
    private static final int MSG_TIME_UPDATE = 301;
    private static final int MSG_BATTERY_UPDATE = 302;
    private static final int MSG_SIM_STATE_CHANGE = 304;
    private static final int MSG_PHONE_STATE_CHANGED = 306;
    private static final int MSG_DEVICE_PROVISIONED = 308;
    private static final int MSG_DPM_STATE_CHANGED = 309;
    private static final int MSG_USER_SWITCHING = 310;
    private static final int MSG_KEYGUARD_RESET = 312;
    private static final int MSG_USER_SWITCH_COMPLETE = 314;
    private static final int MSG_REPORT_EMERGENCY_CALL_ACTION = 318;
    private static final int MSG_STARTED_WAKING_UP = 319;
    private static final int MSG_FINISHED_GOING_TO_SLEEP = 320;
    private static final int MSG_STARTED_GOING_TO_SLEEP = 321;
    private static final int MSG_KEYGUARD_BOUNCER_CHANGED = 322;
    private static final int MSG_SIM_SUBSCRIPTION_INFO_CHANGED = 328;
    private static final int MSG_AIRPLANE_MODE_CHANGED = 329;
    private static final int MSG_SERVICE_STATE_CHANGE = 330;
    private static final int MSG_SCREEN_TURNED_OFF = 332;
    private static final int MSG_DREAMING_STATE_CHANGED = 333;
    private static final int MSG_USER_UNLOCKED = 334;
    private static final int MSG_ASSISTANT_STACK_CHANGED = 335;
    private static final int MSG_BIOMETRIC_AUTHENTICATION_CONTINUE = 336;
    private static final int MSG_DEVICE_POLICY_MANAGER_STATE_CHANGED = 337;
    private static final int MSG_TELEPHONY_CAPABLE = 338;
    private static final int MSG_TIMEZONE_UPDATE = 339;
    private static final int MSG_USER_STOPPED = 340;
    private static final int MSG_USER_REMOVED = 341;
    private static final int MSG_KEYGUARD_GOING_AWAY = 342;
    private static final int MSG_TIME_FORMAT_UPDATE = 344;
    private static final int MSG_REQUIRE_NFC_UNLOCK = 345;
    private static final int MSG_KEYGUARD_DISMISS_ANIMATION_FINISHED = 346;
    private static final int MSG_SERVICE_PROVIDERS_UPDATED = 347;
    private static final int MSG_BIOMETRIC_ENROLLMENT_STATE_CHANGED = 348;

    /** Biometric authentication state: Not listening. */
    @VisibleForTesting
    protected static final int BIOMETRIC_STATE_STOPPED = 0;

    /** Biometric authentication state: Listening. */
    private static final int BIOMETRIC_STATE_RUNNING = 1;

    /**
     * Biometric authentication: Cancelling and waiting for the relevant biometric service to
     * send us the confirmation that cancellation has happened.
     */
    @VisibleForTesting
    protected static final int BIOMETRIC_STATE_CANCELLING = 2;

    /**
     * Biometric state: During cancelling we got another request to start listening, so when we
     * receive the cancellation done signal, we should start listening again.
     */
    @VisibleForTesting
    protected static final int BIOMETRIC_STATE_CANCELLING_RESTARTING = 3;

    /**
     * Action indicating keyguard *can* start biometric authentiation.
     */
    private static final int BIOMETRIC_ACTION_START = 0;
    /**
     * Action indicating keyguard *can* stop biometric authentiation.
     */
    private static final int BIOMETRIC_ACTION_STOP = 1;
    /**
     * Action indicating keyguard *can* start or stop biometric authentiation.
     */
    private static final int BIOMETRIC_ACTION_UPDATE = 2;

    @VisibleForTesting
    public static final int BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED = -1;
    public static final int BIOMETRIC_HELP_FACE_NOT_RECOGNIZED = -2;
    public static final int BIOMETRIC_HELP_FACE_NOT_AVAILABLE = -3;

    /**
     * If no cancel signal has been received after this amount of time, set the biometric running
     * state to stopped to allow Keyguard to retry authentication.
     */
    @VisibleForTesting
    protected static final int DEFAULT_CANCEL_SIGNAL_TIMEOUT = 3000;

    private static final ComponentName FALLBACK_HOME_COMPONENT = new ComponentName(
            "com.android.settings", "com.android.settings.FallbackHome");

    private static final List<Integer> ABSENT_SIM_STATE_LIST = Arrays.asList(
            TelephonyManager.SIM_STATE_ABSENT,
            TelephonyManager.SIM_STATE_UNKNOWN,
            TelephonyManager.SIM_STATE_NOT_READY);

    private final Context mContext;
    private final UserTracker mUserTracker;
    private final KeyguardUpdateMonitorLogger mLogger;
    private final boolean mIsSystemUser;
    private final AuthController mAuthController;
    private final UiEventLogger mUiEventLogger;
    private final Set<String> mAllowFingerprintOnOccludingActivitiesFromPackage;
    private final PackageManager mPackageManager;
    private int mStatusBarState;
    private final StatusBarStateController.StateListener mStatusBarStateControllerListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {
            mStatusBarState = newState;
        }

        @Override
        public void onExpandedChanged(boolean isExpanded) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onShadeExpandedChanged(isExpanded);
                }
            }
        }
    };
    private final FaceWakeUpTriggersConfig mFaceWakeUpTriggersConfig;

    HashMap<Integer, SimData> mSimDatas = new HashMap<>();
    HashMap<Integer, ServiceState> mServiceStates = new HashMap<>();

    private int mPhoneState;
    private boolean mKeyguardShowing;
    private boolean mKeyguardOccluded;
    private boolean mCredentialAttempted;
    private boolean mKeyguardGoingAway;
    /**
     * Whether the keyguard is forced into a dismissible state.
     */
    private boolean mForceIsDismissible;
    private boolean mGoingToSleep;
    private boolean mPrimaryBouncerFullyShown;
    private boolean mPrimaryBouncerIsOrWillBeShowing;
    private boolean mAlternateBouncerShowing;
    private boolean mAuthInterruptActive;
    private boolean mNeedsSlowUnlockTransition;
    private boolean mAssistantVisible;
    private boolean mOccludingAppRequestingFp;
    private boolean mSecureCameraLaunched;
    private boolean mBiometricPromptShowing;
    @VisibleForTesting
    protected boolean mTelephonyCapable;
    private boolean mAllowFingerprintOnCurrentOccludingActivity;

    // Device provisioning state
    private boolean mDeviceProvisioned;

    // Battery status
    @VisibleForTesting
    BatteryStatus mBatteryStatus;
    @VisibleForTesting
    boolean mIncompatibleCharger;

    private StrongAuthTracker mStrongAuthTracker;

    private final ArrayList<WeakReference<KeyguardUpdateMonitorCallback>>
            mCallbacks = Lists.newArrayList();
    private ContentObserver mDeviceProvisionedObserver;
    private final ContentObserver mTimeFormatChangeObserver;

    private boolean mSwitchingUser;

    private boolean mDeviceInteractive;
    private final SubscriptionManager mSubscriptionManager;
    private final TelephonyListenerManager mTelephonyListenerManager;
    private final TrustManager mTrustManager;
    private final UserManager mUserManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private final LatencyTracker mLatencyTracker;
    private final StatusBarStateController mStatusBarStateController;
    private final Executor mBackgroundExecutor;
    private final Executor mMainExecutor;
    private final SensorPrivacyManager mSensorPrivacyManager;
    private final ActiveUnlockConfig mActiveUnlockConfig;
    private final IDreamManager mDreamManager;
    private final TelephonyManager mTelephonyManager;
    @Nullable
    private final FingerprintManager mFpm;
    @Nullable
    private final BiometricManager mBiometricManager;
    @Nullable
    private DeviceEntryFaceAuthInteractor mFaceAuthInteractor;
    @VisibleForTesting
    protected FoldGracePeriodProvider mFoldGracePeriodProvider =
            new FoldGracePeriodProvider();
    private final DevicePostureController mDevicePostureController;
    private final TaskStackChangeListeners mTaskStackChangeListeners;
    private final IActivityTaskManager mActivityTaskManager;
    private final SelectedUserInteractor mSelectedUserInteractor;
    private final LockPatternUtils mLockPatternUtils;
    @VisibleForTesting
    @DevicePostureInt
    protected int mConfigFaceAuthSupportedPosture;

    private KeyguardBypassController mKeyguardBypassController;
    private List<SubscriptionInfo> mSubscriptionInfo;
    @VisibleForTesting
    protected int mFingerprintRunningState = BIOMETRIC_STATE_STOPPED;
    private boolean mFingerprintDetectRunning;
    private boolean mIsDreaming;
    private boolean mLogoutEnabled;
    private int mActiveMobileDataSubscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private final FingerprintInteractiveToAuthProvider mFingerprintInteractiveToAuthProvider;

    /**
     * Short delay before restarting fingerprint authentication after a successful try. This should
     * be slightly longer than the time between onFingerprintAuthenticated and
     * setKeyguardGoingAway(true).
     */
    private static final int FINGERPRINT_CONTINUE_DELAY_MS = 500;

    // If the HAL dies or is unable to authenticate, keyguard should retry after a short delay
    private int mHardwareFingerprintUnavailableRetryCount = 0;
    private static final int HAL_ERROR_RETRY_TIMEOUT = 500; // ms
    private static final int HAL_ERROR_RETRY_MAX = 20;

    @VisibleForTesting
    protected static final int HAL_POWER_PRESS_TIMEOUT = 50; // ms

    @VisibleForTesting
    protected final Runnable mFpCancelNotReceived = this::onFingerprintCancelNotReceived;

    private final Provider<SessionTracker> mSessionTrackerProvider;

    @VisibleForTesting
    protected Handler getHandler() {
        return mHandler;
    }

    private final Handler mHandler;

    private final IBiometricEnabledOnKeyguardCallback mBiometricEnabledCallback =
            new IBiometricEnabledOnKeyguardCallback.Stub() {
                @Override
                public void onChanged(boolean enabled, int userId) {
                    mHandler.post(() -> {
                        mBiometricEnabledForUser.put(userId, enabled);
                        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
                    });
                }
            };

    @VisibleForTesting
    public TelephonyCallback.ActiveDataSubscriptionIdListener mPhoneStateListener =
            new TelephonyCallback.ActiveDataSubscriptionIdListener() {
        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            mActiveMobileDataSubscription = subId;
            mHandler.sendEmptyMessage(MSG_SIM_SUBSCRIPTION_INFO_CHANGED);
        }
    };

    private final OnSubscriptionsChangedListener mSubscriptionListener =
            new OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    mHandler.sendEmptyMessage(MSG_SIM_SUBSCRIPTION_INFO_CHANGED);
                }
            };

    @VisibleForTesting
    static class BiometricAuthenticated {
        private final boolean mAuthenticated;
        private final boolean mIsStrongBiometric;

        BiometricAuthenticated(boolean authenticated, boolean isStrongBiometric) {
            this.mAuthenticated = authenticated;
            this.mIsStrongBiometric = isStrongBiometric;
        }
    }

    private final SparseBooleanArray mUserIsUnlocked = new SparseBooleanArray();
    private final SparseBooleanArray mUserHasTrust = new SparseBooleanArray();
    private final SparseBooleanArray mUserTrustIsManaged = new SparseBooleanArray();
    private final SparseBooleanArray mUserTrustIsUsuallyManaged = new SparseBooleanArray();
    private final SparseBooleanArray mBiometricEnabledForUser = new SparseBooleanArray();
    private final Map<Integer, Intent> mSecondaryLockscreenRequirement = new HashMap<>();

    private final KeyguardFingerprintListenModel.Buffer mFingerprintListenBuffer =
            new KeyguardFingerprintListenModel.Buffer();
    private final KeyguardActiveUnlockModel.Buffer mActiveUnlockTriggerBuffer =
            new KeyguardActiveUnlockModel.Buffer();

    @VisibleForTesting
    SparseArray<BiometricAuthenticated> mUserFingerprintAuthenticated = new SparseArray<>();

    private static int sCurrentUser;

    @Deprecated
    public synchronized static void setCurrentUser(int currentUser) {
        sCurrentUser = currentUser;
    }

    /**
     * @deprecated This can potentially return unexpected values in a multi user scenario
     * as this state is managed by another component. Consider using {@link SelectedUserInteractor}.
     */
    @Deprecated
    public synchronized static int getCurrentUser() {
        return sCurrentUser;
    }

    @Override
    public void onTrustChanged(boolean enabled, boolean newlyUnlocked, int userId, int flags,
            List<String> trustGrantedMessages) {
        Assert.isMainThread();
        boolean wasTrusted = mUserHasTrust.get(userId, false);
        mUserHasTrust.put(userId, enabled);
        // If there was no change in trusted state or trust granted, make sure we are not
        // authenticating.  TrustManager sends an onTrustChanged whenever a user unlocks keyguard,
        // for this reason we need to make sure to not authenticate.
        if (wasTrusted == enabled || enabled) {
            updateFingerprintListeningState(BIOMETRIC_ACTION_STOP);
        } else {
            updateFingerprintListeningState(BIOMETRIC_ACTION_START);
        }

        mLogger.logTrustChanged(wasTrusted, enabled, userId);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTrustChanged(userId);
            }
        }

        if (enabled) {
            String message = null;
            if (mSelectedUserInteractor.getSelectedUserId() == userId
                    && trustGrantedMessages != null) {
                // Show the first non-empty string provided by a trust agent OR intentionally pass
                // an empty string through (to prevent the default trust agent string from showing)
                for (String msg : trustGrantedMessages) {
                    message = msg;
                    if (!TextUtils.isEmpty(message)) {
                        break;
                    }
                }
            }

            mLogger.logTrustGrantedWithFlags(flags, newlyUnlocked, userId, message);
            if (userId == mSelectedUserInteractor.getSelectedUserId()) {
                if (newlyUnlocked) {
                    // if this callback is ever removed, this should then be logged in
                    // TrustRepository
                    mUiEventLogger.log(
                            TrustAgentUiEvent.TRUST_AGENT_NEWLY_UNLOCKED,
                            getKeyguardSessionId()
                    );
                }
                final TrustGrantFlags trustGrantFlags = new TrustGrantFlags(flags);
                for (int i = 0; i < mCallbacks.size(); i++) {
                    KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                    if (cb != null) {
                        cb.onTrustGrantedForCurrentUser(
                                shouldDismissKeyguardOnTrustGrantedWithCurrentUser(trustGrantFlags),
                                newlyUnlocked,
                                trustGrantFlags,
                                message
                        );
                    }
                }
            }
        }
    }

    @Override
    public void onIsActiveUnlockRunningChanged(boolean isRunning, int userId) {
    }

    /**
     * Whether the trust granted call with its passed flags should dismiss keyguard.
     * It's assumed that the trust was granted for the current user.
     */
    private boolean shouldDismissKeyguardOnTrustGrantedWithCurrentUser(TrustGrantFlags flags) {
        final boolean isBouncerShowing =
                mPrimaryBouncerIsOrWillBeShowing || mAlternateBouncerShowing;
        return (flags.isInitiatedByUser() || flags.dismissKeyguardRequested())
                && (mDeviceInteractive || flags.temporaryAndRenewable())
                && (isBouncerShowing || flags.dismissKeyguardRequested());
    }

    @Override
    public void onTrustError(CharSequence message) {
        dispatchErrorMessage(message);
    }

    @Override
    public void onEnabledTrustAgentsChanged(int userId) {
        Assert.isMainThread();

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onEnabledTrustAgentsChanged(userId);
            }
        }
    }

    private void handleSimSubscriptionInfoChanged() {
        Assert.isMainThread();
        mLogger.v("onSubscriptionInfoChanged()");
        List<SubscriptionInfo> sil = mSubscriptionManager
                .getCompleteActiveSubscriptionInfoList();
        if (sil != null) {
            for (SubscriptionInfo subInfo : sil) {
                mLogger.logSubInfo(subInfo);
            }
        } else {
            mLogger.v("onSubscriptionInfoChanged: list is null");
        }
        List<SubscriptionInfo> subscriptionInfos = getSubscriptionInfo(true /* forceReload */);

        // Hack level over 9000: Because the subscription id is not yet valid when we see the
        // first update in handleSimStateChange, we need to force refresh all SIM states
        // so the subscription id for them is consistent.
        ArrayList<SubscriptionInfo> changedSubscriptions = new ArrayList<>();
        Set<Integer> activeSubIds = new HashSet<>();
        for (int i = 0; i < subscriptionInfos.size(); i++) {
            SubscriptionInfo info = subscriptionInfos.get(i);
            activeSubIds.add(info.getSubscriptionId());
            boolean changed = refreshSimState(info.getSubscriptionId(), info.getSimSlotIndex());
            if (changed) {
                changedSubscriptions.add(info);
            }
        }

        // It is possible for active subscriptions to become invalid (-1), and these will not be
        // present in the subscriptionInfo list
        Iterator<Map.Entry<Integer, SimData>> iter = mSimDatas.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Integer, SimData> simData = iter.next();
            if (!activeSubIds.contains(simData.getKey())) {
                mLogger.logInvalidSubId(simData.getKey());
                iter.remove();

                SimData data = simData.getValue();
                for (int j = 0; j < mCallbacks.size(); j++) {
                    KeyguardUpdateMonitorCallback cb = mCallbacks.get(j).get();
                    if (cb != null) {
                        cb.onSimStateChanged(data.subId, data.slotId, data.simState);
                    }
                }
            }
        }

        for (int i = 0; i < changedSubscriptions.size(); i++) {
            SimData data = mSimDatas.get(changedSubscriptions.get(i).getSubscriptionId());
            for (int j = 0; j < mCallbacks.size(); j++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(j).get();
                if (cb != null) {
                    cb.onSimStateChanged(data.subId, data.slotId, data.simState);
                }
            }
        }
        callbacksRefreshCarrierInfo();
    }

    private void handleAirplaneModeChanged() {
        callbacksRefreshCarrierInfo();
    }

    private void callbacksRefreshCarrierInfo() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onRefreshCarrierInfo();
            }
        }
    }

    /**
     * @return List of SubscriptionInfo records, maybe empty but never null.
     */
    public List<SubscriptionInfo> getSubscriptionInfo(boolean forceReload) {
        List<SubscriptionInfo> sil = mSubscriptionInfo;
        if (sil == null || forceReload) {
            sil = mSubscriptionManager.getCompleteActiveSubscriptionInfoList();
        }
        if (sil == null) {
            // getCompleteActiveSubscriptionInfoList was null callers expect an empty list.
            mSubscriptionInfo = new ArrayList<>();
        } else {
            mSubscriptionInfo = sil;
        }
        return new ArrayList<>(mSubscriptionInfo);
    }

    /**
     * This method returns filtered list of SubscriptionInfo from {@link #getSubscriptionInfo}.
     * above. Maybe empty but never null.
     *
     * In DSDS mode if both subscriptions are grouped and one is opportunistic, we filter out one
     * of them based on carrier config. e.g. In this case we should only show one carrier name
     * on the status bar and quick settings.
     */
    public List<SubscriptionInfo> getFilteredSubscriptionInfo() {
        List<SubscriptionInfo> subscriptions = getSubscriptionInfo(false);
        if (subscriptions.size() == 2) {
            SubscriptionInfo info1 = subscriptions.get(0);
            SubscriptionInfo info2 = subscriptions.get(1);
            if (info1.getGroupUuid() != null && info1.getGroupUuid().equals(info2.getGroupUuid())) {
                // If both subscriptions are primary, show both.
                if (!info1.isOpportunistic() && !info2.isOpportunistic()) return subscriptions;

                // If carrier required, always show signal bar of primary subscription.
                // Otherwise, show whichever subscription is currently active for Internet.
                boolean alwaysShowPrimary = CarrierConfigManager.getDefaultConfig()
                        .getBoolean(CarrierConfigManager
                        .KEY_ALWAYS_SHOW_PRIMARY_SIGNAL_BAR_IN_OPPORTUNISTIC_NETWORK_BOOLEAN);
                if (alwaysShowPrimary) {
                    subscriptions.remove(info1.isOpportunistic() ? info1 : info2);
                } else {
                    subscriptions.remove(info1.getSubscriptionId() == mActiveMobileDataSubscription
                            ? info2 : info1);
                }

            }
        }

        return subscriptions;
    }

    @Override
    public void onTrustManagedChanged(boolean managed, int userId) {
        Assert.isMainThread();
        mUserTrustIsManaged.put(userId, managed);
        boolean trustUsuallyManaged = mTrustManager.isTrustUsuallyManaged(userId);
        mLogger.logTrustUsuallyManagedUpdated(userId, mUserTrustIsUsuallyManaged.get(userId),
                trustUsuallyManaged, "onTrustManagedChanged");
        mUserTrustIsUsuallyManaged.put(userId, trustUsuallyManaged);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTrustManagedChanged(userId);
            }
        }
    }

    /**
     * Updates KeyguardUpdateMonitor's internal state to know if credential was attempted on
     * bouncer. Note that this does not care if the credential was correct/incorrect. This is
     * cleared when the user leaves the bouncer (unlocked, screen off, back to lockscreen, etc)
     */
    public void setCredentialAttempted() {
        mCredentialAttempted = true;
        // Do not update face listening state in case of false authentication attempts.
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
    }

    /**
     * Updates KeyguardUpdateMonitor's internal state to know the device should remain unlocked
     * until the next signal to lock. Does nothing if the keyguard is already showing.
     */
    public void tryForceIsDismissibleKeyguard() {
        setForceIsDismissibleKeyguard(true);
    }

    /**
     * Updates KeyguardUpdateMonitor's internal state to know if keyguard is going away.
     */
    public void setKeyguardGoingAway(boolean goingAway) {
        mKeyguardGoingAway = goingAway;
        if (mKeyguardGoingAway) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onKeyguardGoingAway();
                }
            }
        }
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
    }

    /**
     * Whether keyguard is going away due to screen off or device entry.
     */
    public boolean isKeyguardGoingAway() {
        return mKeyguardGoingAway;
    }

    /**
     * Updates KeyguardUpdateMonitor's internal state to know if keyguard is showing and if
     * its occluded. The keyguard is considered visible if its showing and NOT occluded.
     */
    public void setKeyguardShowing(boolean showing, boolean occluded) {
        final boolean occlusionChanged = mKeyguardOccluded != occluded;
        final boolean showingChanged = mKeyguardShowing != showing;
        if (!occlusionChanged && !showingChanged) {
            return;
        }

        final boolean wasKeyguardVisible = isKeyguardVisible();
        mKeyguardShowing = showing;
        mKeyguardOccluded = occluded;
        final boolean isKeyguardVisible = isKeyguardVisible();
        mLogger.logKeyguardShowingChanged(showing, occluded, isKeyguardVisible);

        if (isKeyguardVisible != wasKeyguardVisible) {
            if (isKeyguardVisible) {
                mSecureCameraLaunched = false;
            }
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onKeyguardVisibilityChanged(isKeyguardVisible);
                }
            }
        }

        if (occlusionChanged || showingChanged) {
            updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        }
    }

    /**
     * Request to listen for fingerprint when an app is occluding keyguard.
     *
     * @param request if true and mKeyguardOccluded, request fingerprint listening, else default
     *                to normal behavior.
     *                See {@link KeyguardUpdateMonitor#shouldListenForFingerprint(boolean)}
     */
    public void requestFingerprintAuthOnOccludingApp(boolean request) {
        mOccludingAppRequestingFp = request;
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
    }

    /**
     * Invoked when the secure camera is launched.
     */
    public void onCameraLaunched() {
        mSecureCameraLaunched = true;
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
    }

    /**
     * Whether the secure camera is currently showing over the keyguard.
     */
    public boolean isSecureCameraLaunchedOverKeyguard() {
        return mSecureCameraLaunched;
    }

    /**
     * @return a cached version of DreamManager.isDreaming()
     */
    public boolean isDreaming() {
        return mIsDreaming;
    }

    /**
     * If the device is dreaming, awakens the device
     */
    public void awakenFromDream() {
        if (mIsDreaming) {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                mLogger.logException(e, "Unable to awaken from dream");
            }
        }
    }

    private void onBiometricDetected(int userId, BiometricSourceType biometricSourceType,
            boolean isStrongBiometric) {
        Assert.isMainThread();
        Trace.beginSection("KeyGuardUpdateMonitor#onBiometricDetected");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricDetected(userId, biometricSourceType, isStrongBiometric);
            }
        }
        Trace.endSection();
    }

    @VisibleForTesting
    public void onFingerprintAuthenticated(int userId, boolean isStrongBiometric) {
        Assert.isMainThread();
        Trace.beginSection("KeyGuardUpdateMonitor#onFingerPrintAuthenticated");
        mUserFingerprintAuthenticated.put(userId,
                new BiometricAuthenticated(true, isStrongBiometric));
        // Update/refresh trust state only if user can skip bouncer
        if (getUserCanSkipBouncer(userId)) {
            mTrustManager.unlockedByBiometricForUser(userId, FINGERPRINT);
        }
        // Don't send cancel if authentication succeeds
        mFingerprintCancelSignal = null;
        mLogger.logFingerprintSuccess(userId, isStrongBiometric);
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAuthenticated(userId, FINGERPRINT,
                        isStrongBiometric);
            }
        }

        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BIOMETRIC_AUTHENTICATION_CONTINUE),
                FINGERPRINT_CONTINUE_DELAY_MS);

        // Only authenticate fingerprint once when assistant is visible
        mAssistantVisible = false;

        // Report unlock with strong or non-strong biometric
        reportSuccessfulBiometricUnlock(isStrongBiometric, userId);

        Trace.endSection();
    }

    private void reportSuccessfulBiometricUnlock(boolean isStrongBiometric, int userId) {
        mBackgroundExecutor.execute(
                () -> {
                    mLogger.logReportSuccessfulBiometricUnlock(isStrongBiometric, userId);
                    mLockPatternUtils.reportSuccessfulBiometricUnlock(isStrongBiometric, userId);
                });
    }

    private void handleFingerprintAuthFailed() {
        Assert.isMainThread();
        if (mHandler.hasCallbacks(mFpCancelNotReceived)) {
            mLogger.d("handleFingerprintAuthFailed()"
                    + " triggered while waiting for cancellation, removing watchdog");
            mHandler.removeCallbacks(mFpCancelNotReceived);
        }
        mLogger.d("handleFingerprintAuthFailed");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAuthFailed(FINGERPRINT);
            }
        }
        if (isUdfpsSupported()) {
            handleFingerprintHelp(BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                    mContext.getString(
                            com.android.internal.R.string.fingerprint_udfps_error_not_match));
        } else {
            handleFingerprintHelp(BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                    mContext.getString(
                            com.android.internal.R.string.fingerprint_error_not_match));
        }
    }

    private void handleFingerprintAcquired(
            @BiometricFingerprintConstants.FingerprintAcquired int acquireInfo) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAcquired(FINGERPRINT, acquireInfo);
            }
        }
    }

    private void handleBiometricDetected(int authUserId, BiometricSourceType biometricSourceType,
            boolean isStrongBiometric) {
        Trace.beginSection("KeyGuardUpdateMonitor#handlerBiometricDetected");
        onBiometricDetected(authUserId, biometricSourceType, isStrongBiometric);
        if (biometricSourceType == FINGERPRINT) {
            mLogger.logFingerprintDetected(authUserId, isStrongBiometric);
        } else if (biometricSourceType == FACE) {
            mLogger.logFaceDetected(authUserId, isStrongBiometric);
        }

        Trace.endSection();
    }

    private void handleFingerprintAuthenticated(int authUserId, boolean isStrongBiometric) {
        Trace.beginSection("KeyGuardUpdateMonitor#handlerFingerPrintAuthenticated");
        if (mHandler.hasCallbacks(mFpCancelNotReceived)) {
            mLogger.d("handleFingerprintAuthenticated()"
                    + " triggered while waiting for cancellation, removing watchdog");
            mHandler.removeCallbacks(mFpCancelNotReceived);
        }
        try {
            final int userId = mSelectedUserInteractor.getSelectedUserId(true);
            if (userId != authUserId) {
                mLogger.logFingerprintAuthForWrongUser(authUserId);
                return;
            }
            if (isFingerprintDisabled(userId)) {
                mLogger.logFingerprintDisabledForUser(userId);
                return;
            }
            onFingerprintAuthenticated(userId, isStrongBiometric);
        } finally {
            setFingerprintRunningState(BIOMETRIC_STATE_STOPPED);
        }
        Trace.endSection();
    }

    private void handleFingerprintHelp(int msgId, String helpString) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricHelp(msgId, helpString, FINGERPRINT);
            }
        }
    }

    private final Runnable mRetryFingerprintAuthenticationAfterHwUnavailable = new Runnable() {
        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            mLogger.logRetryAfterFpHwUnavailable(mHardwareFingerprintUnavailableRetryCount);
            if (!mFingerprintSensorProperties.isEmpty()) {
                updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
            } else if (mHardwareFingerprintUnavailableRetryCount < HAL_ERROR_RETRY_MAX) {
                mHardwareFingerprintUnavailableRetryCount++;
                mHandler.postDelayed(mRetryFingerprintAuthenticationAfterHwUnavailable,
                        HAL_ERROR_RETRY_TIMEOUT);
            }
        }
    };

    private void onFingerprintCancelNotReceived() {
        mLogger.e("Fp cancellation not received, transitioning to STOPPED");
        final boolean wasCancellingRestarting = mFingerprintRunningState
                == BIOMETRIC_STATE_CANCELLING_RESTARTING;
        mFingerprintRunningState = BIOMETRIC_STATE_STOPPED;
        mFingerprintDetectRunning = false;
        if (wasCancellingRestarting) {
            KeyguardUpdateMonitor.this.updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        } else {
            KeyguardUpdateMonitor.this.updateFingerprintListeningState(BIOMETRIC_ACTION_STOP);
        }
    }

    private void handleFingerprintError(int msgId, String errString) {
        Assert.isMainThread();
        if (mHandler.hasCallbacks(mFpCancelNotReceived)) {
            mHandler.removeCallbacks(mFpCancelNotReceived);
        }

        // Error is always the end of authentication lifecycle.
        mFingerprintCancelSignal = null;

        if (msgId == FingerprintManager.FINGERPRINT_ERROR_CANCELED
                && mFingerprintRunningState == BIOMETRIC_STATE_CANCELLING_RESTARTING) {
            setFingerprintRunningState(BIOMETRIC_STATE_STOPPED);
            updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        } else {
            setFingerprintRunningState(BIOMETRIC_STATE_STOPPED);
        }

        if (msgId == FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE) {
            mLogger.logRetryAfterFpErrorWithDelay(msgId, errString, HAL_ERROR_RETRY_TIMEOUT);
            mHandler.postDelayed(mRetryFingerprintAuthenticationAfterHwUnavailable,
                    HAL_ERROR_RETRY_TIMEOUT);
        }

        if (msgId == FingerprintManager.BIOMETRIC_ERROR_POWER_PRESSED) {
            mLogger.logRetryAfterFpErrorWithDelay(msgId, errString, HAL_POWER_PRESS_TIMEOUT);
            mHandler.postDelayed(() -> {
                mLogger.d("Retrying fingerprint listening after power pressed error.");
                updateFingerprintListeningState(BIOMETRIC_ACTION_START);
            }, HAL_POWER_PRESS_TIMEOUT);
        }

        boolean lockedOutStateChanged = false;
        if (msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT) {
            lockedOutStateChanged = !mFingerprintLockedOutPermanent;
            mFingerprintLockedOutPermanent = true;
            mLogger.d("Fingerprint permanently locked out - requiring stronger auth");
            mLockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_LOCKOUT,
                    mSelectedUserInteractor.getSelectedUserId());
        }

        if (msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT
                || msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT) {
            lockedOutStateChanged |= !mFingerprintLockedOut;
            mFingerprintLockedOut = true;
            mLogger.d("Fingerprint temporarily locked out - requiring stronger auth");
            if (isUdfpsEnrolled()) {
                updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
            }
        }

        mLogger.logFingerprintError(msgId, errString);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricError(msgId, errString, FINGERPRINT);
            }
        }

        if (lockedOutStateChanged) {
            notifyLockedOutStateChanged(FINGERPRINT);
        }
    }

    private void handleFingerprintLockoutReset(@LockoutMode int mode) {
        mLogger.logFingerprintLockoutReset(mode);
        final boolean wasLockout = mFingerprintLockedOut;
        final boolean wasLockoutPermanent = mFingerprintLockedOutPermanent;
        mFingerprintLockedOut = (mode == BIOMETRIC_LOCKOUT_TIMED)
                || mode == BIOMETRIC_LOCKOUT_PERMANENT;
        mFingerprintLockedOutPermanent = (mode == BIOMETRIC_LOCKOUT_PERMANENT);
        final boolean changed = (mFingerprintLockedOut != wasLockout)
                || (mFingerprintLockedOutPermanent != wasLockoutPermanent);

        if (isUdfpsEnrolled()) {
            // TODO(b/194825098): update the reset signal(s)
            // A successful unlock will trigger a lockout reset, but there is no guarantee
            // that the events will arrive in a particular order. Add a delay here in case
            // an unlock is in progress. In this is a normal unlock the extra delay won't
            // be noticeable.
            mHandler.postDelayed(
                    () -> updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE),
                    getBiometricLockoutDelay());
        } else {
            boolean temporaryLockoutReset = wasLockout && !mFingerprintLockedOut;
            if (temporaryLockoutReset) {
                mLogger.d("temporaryLockoutReset - stopListeningForFingerprint() to stop"
                        + " detectFingerprint");
                stopListeningForFingerprint();
            }
            updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        }

        if (changed) {
            notifyLockedOutStateChanged(FINGERPRINT);
        }
    }

    private void setFingerprintRunningState(int fingerprintRunningState) {
        boolean wasRunning = mFingerprintRunningState == BIOMETRIC_STATE_RUNNING;
        boolean isRunning = fingerprintRunningState == BIOMETRIC_STATE_RUNNING;
        mFingerprintRunningState = fingerprintRunningState;
        if (mFingerprintRunningState == BIOMETRIC_STATE_STOPPED) {
            mFingerprintDetectRunning = false;
        }
        mLogger.logFingerprintRunningState(mFingerprintRunningState);
        // Clients of KeyguardUpdateMonitor don't care about the internal state about the
        // asynchronousness of the cancel cycle. So only notify them if the actually running state
        // has changed.
        if (wasRunning != isRunning) {
            notifyFingerprintRunningStateChanged();
        }
    }

    private void notifyFingerprintRunningStateChanged() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricRunningStateChanged(isFingerprintDetectionRunning(),
                        FINGERPRINT);
            }
        }
    }

    @VisibleForTesting
    public void onFaceAuthenticated(int userId, boolean isStrongBiometric) {
        Trace.beginSection("KeyGuardUpdateMonitor#onFaceAuthenticated");
        Assert.isMainThread();
        // Update/refresh trust state only if user can skip bouncer
        if (getUserCanSkipBouncer(userId)) {
            mTrustManager.unlockedByBiometricForUser(userId, FACE);
        }
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        mLogger.d("onFaceAuthenticated");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAuthenticated(userId,
                        FACE,
                        isStrongBiometric);
            }
        }

        // Only authenticate face once when assistant is visible
        mAssistantVisible = false;

        // Report unlock with strong or non-strong biometric
        reportSuccessfulBiometricUnlock(isStrongBiometric, userId);

        Trace.endSection();
    }

    private void handleFaceAuthFailed() {
        Assert.isMainThread();
        String reason =
                mKeyguardBypassController.canBypass() ? "bypass"
                        : mAlternateBouncerShowing ? "alternateBouncer"
                                : mPrimaryBouncerFullyShown ? "bouncer"
                                        : "udfpsFpDown";
        requestActiveUnlock(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL,
                "faceFailure-" + reason);

        mLogger.d("onFaceAuthFailed");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAuthFailed(FACE);
            }
        }
        handleFaceHelp(BIOMETRIC_HELP_FACE_NOT_RECOGNIZED,
                mContext.getString(R.string.kg_face_not_recognized));
    }

    private void handleFaceAcquired(int acquireInfo) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAcquired(FACE, acquireInfo);
            }
        }

        if (mActiveUnlockConfig.shouldRequestActiveUnlockOnFaceAcquireInfo(
                acquireInfo)) {
            requestActiveUnlock(
                    ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL,
                    "faceAcquireInfo-" + acquireInfo);
        }
    }

    private void handleFaceAuthenticated(int authUserId, boolean isStrongBiometric) {
        Trace.beginSection("KeyGuardUpdateMonitor#handlerFaceAuthenticated");
        if (mGoingToSleep) {
            mLogger.d("Aborted successful auth because device is going to sleep.");
            return;
        }
        final int userId = mSelectedUserInteractor.getSelectedUserId(true);
        if (userId != authUserId) {
            mLogger.logFaceAuthForWrongUser(authUserId);
            return;
        }
        mLogger.logFaceAuthSuccess(userId);
        onFaceAuthenticated(userId, isStrongBiometric);
        Trace.endSection();
    }

    private void handleFaceHelp(int msgId, String helpString) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricHelp(msgId, helpString, FACE);
            }
        }
    }

    private void handleFaceError(int msgId, final String originalErrMsg) {
        Assert.isMainThread();
        String errString = originalErrMsg;
        mLogger.logFaceAuthError(msgId, originalErrMsg);

        // Error is always the end of authentication lifecycle
        boolean cameraPrivacyEnabled = mSensorPrivacyManager.isSensorPrivacyEnabled(
                SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE, SensorPrivacyManager.Sensors.CAMERA);

        final boolean isHwUnavailable = msgId == FaceManager.FACE_ERROR_HW_UNAVAILABLE;

        if (msgId == FaceManager.FACE_ERROR_LOCKOUT_PERMANENT) {
            if (getFaceAuthInteractor() != null && getFaceAuthInteractor().isFaceAuthStrong()) {
                updateFingerprintListeningState(BIOMETRIC_ACTION_STOP);
            }
        }

        if (isHwUnavailable && cameraPrivacyEnabled) {
            errString = mContext.getString(R.string.kg_face_sensor_privacy_enabled);
        }

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricError(msgId, errString,
                        FACE);
            }
        }

        if (mActiveUnlockConfig.shouldRequestActiveUnlockOnFaceError(msgId)) {
            requestActiveUnlock(
                    ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL,
                    "faceError-" + msgId);
        }
    }

    private void notifyFaceRunningStateChanged() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricRunningStateChanged(isFaceDetectionRunning(),
                        FACE);
            }
        }
    }

    public boolean isFingerprintDetectionRunning() {
        return mFingerprintRunningState == BIOMETRIC_STATE_RUNNING;
    }

    /**
     * @deprecated This is being migrated to use modern architecture.
     */
    @Deprecated
    public boolean isFaceDetectionRunning() {
        return getFaceAuthInteractor() != null && getFaceAuthInteractor().isRunning();
    }

    private @Nullable DeviceEntryFaceAuthInteractor getFaceAuthInteractor() {
        return mFaceAuthInteractor;
    }

    /**
     * Set the face auth interactor that should be used for initiating face authentication.
     */
    public void setFaceAuthInteractor(DeviceEntryFaceAuthInteractor faceAuthInteractor) {
        if (mFaceAuthInteractor != null) {
            mFaceAuthInteractor.unregisterListener(mFaceAuthenticationListener);
        }
        mFaceAuthInteractor = faceAuthInteractor;
        mFaceAuthInteractor.registerListener(mFaceAuthenticationListener);
    }

    private final FaceAuthenticationListener mFaceAuthenticationListener =
            new FaceAuthenticationListener() {
                public void onAuthenticatedChanged(boolean isAuthenticated) {
                    if (!isAuthenticated) {
                        for (int i = 0; i < mCallbacks.size(); i++) {
                            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                            if (cb != null) {
                                cb.onFacesCleared();
                            }
                        }
                    }
                }

                @Override
                public void onAuthEnrollmentStateChanged(boolean enrolled) {
                    notifyAboutEnrollmentChange(TYPE_FACE);
                }

                @Override
                public void onRunningStateChanged(boolean isRunning) {
                    notifyFaceRunningStateChanged();
                }

                @Override
                public void onLockoutStateChanged(boolean isLockedOut) {
                    notifyLockedOutStateChanged(FACE);
                }

                @Override
                public void onAuthenticationStatusChanged(
                        @NonNull FaceAuthenticationStatus status
                ) {
                    if (status instanceof AcquiredFaceAuthenticationStatus) {
                        handleFaceAcquired(
                                ((AcquiredFaceAuthenticationStatus) status).getAcquiredInfo());
                    } else if (status instanceof ErrorFaceAuthenticationStatus) {
                        ErrorFaceAuthenticationStatus error =
                                (ErrorFaceAuthenticationStatus) status;
                        handleFaceError(error.getMsgId(), error.getMsg());
                    } else if (status instanceof FailedFaceAuthenticationStatus) {
                        handleFaceAuthFailed();
                    } else if (status instanceof HelpFaceAuthenticationStatus) {
                        HelpFaceAuthenticationStatus helpMsg =
                                (HelpFaceAuthenticationStatus) status;
                        handleFaceHelp(helpMsg.getMsgId(), helpMsg.getMsg());
                    } else if (status instanceof SuccessFaceAuthenticationStatus) {
                        FaceManager.AuthenticationResult result =
                                ((SuccessFaceAuthenticationStatus) status).getSuccessResult();
                        handleFaceAuthenticated(result.getUserId(), result.isStrongBiometric());
                    }
                }

                @Override
                public void onDetectionStatusChanged(@NonNull FaceDetectionStatus status) {
                    handleBiometricDetected(status.getUserId(), FACE, status.isStrongBiometric());
                }
            };

    private boolean isTrustDisabled() {
        // Don't allow trust agent if device is secured with a SIM PIN. This is here
        // mainly because there's no other way to prompt the user to enter their SIM PIN
        // once they get past the keyguard screen.
        return isSimPinSecure(); // Disabled by SIM PIN
    }

    private boolean isFingerprintDisabled(int userId) {
        return (mDevicePolicyManager.getKeyguardDisabledFeatures(null, userId)
                        & DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT) != 0
                || isSimPinSecure();
    }

    /**
     * @return whether the current user has been authenticated with face. This may be true
     * on the lockscreen if the user doesn't have bypass enabled.
     *
     * @deprecated Use {@link DeviceEntryFaceAuthInteractor#isAuthenticated()}
     */
    @Deprecated
    public boolean getIsFaceAuthenticated() {
        return getFaceAuthInteractor() != null && getFaceAuthInteractor().isAuthenticated();
    }

    public boolean getUserCanSkipBouncer(int userId) {
        return getUserHasTrust(userId) || getUserUnlockedWithBiometric(userId)
                || forceIsDismissibleIsKeepingDeviceUnlocked();
    }

    /**
     * Whether the keyguard should be kept unlocked for the folding grace period.
     */
    public boolean forceIsDismissibleIsKeepingDeviceUnlocked() {
        if (mFoldGracePeriodProvider.isEnabled()) {
            return mForceIsDismissible && isUnlockingWithForceKeyguardDismissibleAllowed();
        }
        return false;
    }

    public boolean getUserHasTrust(int userId) {
        return !isTrustDisabled() && mUserHasTrust.get(userId)
                && isUnlockingWithTrustAgentAllowed();
    }

    /**
     * Returns whether the user is unlocked with biometrics.
     */
    public boolean getUserUnlockedWithBiometric(int userId) {
        BiometricAuthenticated fingerprint = mUserFingerprintAuthenticated.get(userId);
        boolean fingerprintAllowed = fingerprint != null && fingerprint.mAuthenticated
                && isUnlockingWithBiometricAllowed(fingerprint.mIsStrongBiometric);
        boolean unlockedByFace = isCurrentUserUnlockedWithFace() && isUnlockingWithBiometricAllowed(
                FACE);
        return fingerprintAllowed || unlockedByFace;
    }


    /**
     * Returns whether the user is unlocked with face.
     * @deprecated Use {@link DeviceEntryFaceAuthInteractor#isAuthenticated()} instead
     */
    @Deprecated
    public boolean isCurrentUserUnlockedWithFace() {
        return getFaceAuthInteractor() != null && getFaceAuthInteractor().isAuthenticated();
    }

    /**
     * Returns whether the user is unlocked with a biometric that is currently bypassing
     * the lock screen.
     */
    public boolean getUserUnlockedWithBiometricAndIsBypassing(int userId) {
        BiometricAuthenticated fingerprint = mUserFingerprintAuthenticated.get(userId);
        // fingerprint always bypasses
        boolean fingerprintAllowed = fingerprint != null && fingerprint.mAuthenticated
                && isUnlockingWithBiometricAllowed(fingerprint.mIsStrongBiometric);
        return fingerprintAllowed || (isCurrentUserUnlockedWithFace()
                && mKeyguardBypassController.canBypass());
    }

    public boolean getUserTrustIsManaged(int userId) {
        return mUserTrustIsManaged.get(userId) && !isTrustDisabled();
    }

    private void updateSecondaryLockscreenRequirement(int userId) {
        Intent oldIntent = mSecondaryLockscreenRequirement.get(userId);
        boolean enabled = mDevicePolicyManager.isSecondaryLockscreenEnabled(UserHandle.of(userId));
        boolean changed = false;

        if (enabled && (oldIntent == null)) {
            ComponentName supervisorComponent =
                    mDevicePolicyManager.getProfileOwnerOrDeviceOwnerSupervisionComponent(
                            UserHandle.of(userId));
            if (supervisorComponent == null) {
                mLogger.logMissingSupervisorAppError(userId);
            } else {
                Intent intent =
                        new Intent(DevicePolicyManager.ACTION_BIND_SECONDARY_LOCKSCREEN_SERVICE)
                                .setPackage(supervisorComponent.getPackageName());
                ResolveInfo resolveInfo = mPackageManager.resolveService(intent, 0);
                if (resolveInfo != null && resolveInfo.serviceInfo != null) {
                    Intent launchIntent =
                            new Intent().setComponent(resolveInfo.serviceInfo.getComponentName());
                    mSecondaryLockscreenRequirement.put(userId, launchIntent);
                    changed = true;
                }
            }
        } else if (!enabled && (oldIntent != null)) {
            mSecondaryLockscreenRequirement.put(userId, null);
            changed = true;
        }
        if (changed) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onSecondaryLockscreenRequirementChanged(userId);
                }
            }
        }
    }

    /**
     * Returns an Intent by which to bind to a service that will provide additional security screen
     * content that must be shown prior to dismissing the keyguard for this user.
     */
    public Intent getSecondaryLockscreenRequirement(int userId) {
        return mSecondaryLockscreenRequirement.get(userId);
    }

    /**
     * Cached version of {@link TrustManager#isTrustUsuallyManaged(int)}.
     */
    public boolean isTrustUsuallyManaged(int userId) {
        Assert.isMainThread();
        return mUserTrustIsUsuallyManaged.get(userId);
    }

    private boolean isUnlockingWithTrustAgentAllowed() {
        return isUnlockingWithBiometricAllowed(true);
    }

    private boolean isUnlockingWithForceKeyguardDismissibleAllowed() {
        return isUnlockingWithBiometricAllowed(false);
    }

    public boolean isUnlockingWithBiometricAllowed(boolean isStrongBiometric) {
        // StrongAuthTracker#isUnlockingWithBiometricAllowed includes
        // STRONG_AUTH_REQUIRED_AFTER_LOCKOUT which is the same as mFingerprintLockedOutPermanent;
        // however the strong auth tracker does not include the temporary lockout
        // mFingerprintLockedOut.
        if (!mStrongAuthTracker.isUnlockingWithBiometricAllowed(isStrongBiometric)) {
            return false;
        }
        boolean isFaceLockedOut =
                getFaceAuthInteractor() != null && getFaceAuthInteractor().isLockedOut();
        boolean isFaceAuthStrong =
                getFaceAuthInteractor() != null && getFaceAuthInteractor().isFaceAuthStrong();
        boolean isFingerprintLockedOut = isFingerprintLockedOut();
        boolean isAnyStrongBiometricLockedOut =
                (isFingerprintClass3() && isFingerprintLockedOut) || (isFaceAuthStrong
                        && isFaceLockedOut);
        // Class 3 biometric lockout will lockout ALL biometrics
        if (isAnyStrongBiometricLockedOut) {
            return false;
        }
        return !isFaceLockedOut || !isFingerprintLockedOut;
    }

    /**
     * Whether fingerprint is allowed ot be used for unlocking based on the strongAuthTracker
     * and temporary lockout state (tracked by FingerprintManager via error codes).
     */
    public boolean isUnlockingWithFingerprintAllowed() {
        return isUnlockingWithBiometricAllowed(FINGERPRINT);
    }

    /**
     * Whether the given biometric is allowed based on strongAuth & lockout states.
     */
    public boolean isUnlockingWithBiometricAllowed(
            @NonNull BiometricSourceType biometricSourceType) {
        switch (biometricSourceType) {
            case FINGERPRINT:
                return isUnlockingWithBiometricAllowed(isFingerprintClass3());
            case FACE:
                return getFaceAuthInteractor() != null
                        && isUnlockingWithBiometricAllowed(
                        getFaceAuthInteractor().isFaceAuthStrong());
            default:
                return false;
        }
    }

    /**
     * Whether the user locked down the device. This doesn't include device policy manager lockdown.
     */
    public boolean isUserInLockdown(int userId) {
        return containsFlag(mStrongAuthTracker.getStrongAuthForUser(userId),
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
    }

    /**
     * Returns true if primary authentication is required for the given user due to lockdown
     * or encryption after reboot.
     */
    public boolean isEncryptedOrLockdown(int userId) {
        final int strongAuth = mStrongAuthTracker.getStrongAuthForUser(userId);
        final boolean isLockDown =
                containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW)
                        || containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
        final boolean isEncrypted = containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_BOOT);

        return isEncrypted || isLockDown;
    }

    private boolean containsFlag(int haystack, int needle) {
        return (haystack & needle) != 0;
    }

    public boolean needsSlowUnlockTransition() {
        return mNeedsSlowUnlockTransition;
    }

    public StrongAuthTracker getStrongAuthTracker() {
        return mStrongAuthTracker;
    }

    @VisibleForTesting
    void notifyStrongAuthAllowedChanged(int userId) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onStrongAuthStateChanged(userId);
            }
        }
        if (userId == mSelectedUserInteractor.getSelectedUserId()) {
            // Strong auth is only reset when primary auth is used to enter the device,
            // so we only check whether to stop biometric listening states here
            updateFingerprintListeningState(BIOMETRIC_ACTION_STOP);
        }
    }

    private void notifyLockedOutStateChanged(BiometricSourceType type) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onLockedOutStateChanged(type);
            }
        }
    }
    @VisibleForTesting
    void notifyNonStrongBiometricAllowedChanged(int userId) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onNonStrongBiometricAllowedChanged(userId);
            }
        }
        if (userId == mSelectedUserInteractor.getSelectedUserId()) {
            // This is only reset when primary auth is used to enter the device, so we only check
            // whether to stop biometric listening states here
            updateFingerprintListeningState(BIOMETRIC_ACTION_STOP);
        }
    }

    private void dispatchErrorMessage(CharSequence message) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTrustAgentErrorMessage(message);
            }
        }

    }

    @VisibleForTesting
    void setAssistantVisible(boolean assistantVisible) {
        mAssistantVisible = assistantVisible;
        mLogger.logAssistantVisible(mAssistantVisible);
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        if (mAssistantVisible) {
            if (getFaceAuthInteractor() != null) {
                getFaceAuthInteractor().onAssistantTriggeredOnLockScreen();
            }
            requestActiveUnlock(
                    ActiveUnlockConfig.ActiveUnlockRequestOrigin.ASSISTANT,
                    "assistant",
                    /* dismissKeyguard */ true);
        }
    }

    @VisibleForTesting
    protected final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            mLogger.logBroadcastReceived(action);

            if (Intent.ACTION_TIME_TICK.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MSG_TIME_UPDATE);
            } else if (Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                final Message msg = mHandler.obtainMessage(
                        MSG_TIMEZONE_UPDATE, intent.getStringExtra(Intent.EXTRA_TIMEZONE));
                mHandler.sendMessage(msg);
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                final Message msg = mHandler.obtainMessage(
                        MSG_BATTERY_UPDATE, new BatteryStatus(intent, mIncompatibleCharger));
                mHandler.sendMessage(msg);
            } else if (UsbManager.ACTION_USB_PORT_COMPLIANCE_CHANGED.equals(action)) {
                mIncompatibleCharger = Utils.containsIncompatibleChargers(context, TAG);
                BatteryStatus batteryStatus = BatteryStatus.create(context, mIncompatibleCharger);
                if (batteryStatus != null) {
                    mHandler.sendMessage(
                            mHandler.obtainMessage(MSG_BATTERY_UPDATE, batteryStatus));
                }
            } else if (Intent.ACTION_SIM_STATE_CHANGED.equals(action)) {
                SimData args = SimData.fromIntent(intent);
                // ACTION_SIM_STATE_CHANGED is rebroadcast after unlocking the device to
                // keep compatibility with apps that aren't direct boot aware.
                // SysUI should just ignore this broadcast because it was already received
                // and processed previously.
                if (intent.getBooleanExtra(Intent.EXTRA_REBROADCAST_ON_UNLOCK, false)) {
                    // Guarantee mTelephonyCapable state after SysUI crash and restart
                    if (args.simState == TelephonyManager.SIM_STATE_ABSENT) {
                        mHandler.obtainMessage(MSG_TELEPHONY_CAPABLE, true).sendToTarget();
                    }
                    return;
                }
                mLogger.logSimStateFromIntent(action,
                        intent.getStringExtra(Intent.EXTRA_SIM_STATE),
                        args.slotId,
                        args.subId);
                mHandler.obtainMessage(MSG_SIM_STATE_CHANGE, args.subId, args.slotId, args.simState)
                        .sendToTarget();
            } else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_PHONE_STATE_CHANGED, state));
            } else if (TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED.equals(action)) {
                mHandler.obtainMessage(MSG_SERVICE_PROVIDERS_UPDATED, intent).sendToTarget();
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MSG_AIRPLANE_MODE_CHANGED);
            } else if (Intent.ACTION_SERVICE_STATE.equals(action)) {
                ServiceState serviceState = ServiceState.newFromBundle(intent.getExtras());
                int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                mLogger.logServiceStateIntent(action, serviceState, subId);
                mHandler.sendMessage(
                        mHandler.obtainMessage(MSG_SERVICE_STATE_CHANGE, subId, 0, serviceState));
            } else if (TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MSG_SIM_SUBSCRIPTION_INFO_CHANGED);
            } else if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED.equals(
                    action)) {
                mHandler.sendEmptyMessage(MSG_DEVICE_POLICY_MANAGER_STATE_CHANGED);
            }
        }
    };

    @VisibleForTesting
    protected final BroadcastReceiver mBroadcastAllReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MSG_TIME_UPDATE);
            } else if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                    .equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_DPM_STATE_CHANGED,
                        getSendingUserId(), 0));
            } else if (ACTION_USER_UNLOCKED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_UNLOCKED,
                        getSendingUserId(), 0));
            } else if (ACTION_USER_STOPPED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_STOPPED,
                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1), 0));
            } else if (ACTION_USER_REMOVED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_REMOVED,
                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1), 0));
            } else if (NfcAdapter.ACTION_REQUIRE_UNLOCK_FOR_NFC.equals(action)) {
                mHandler.sendEmptyMessage(MSG_REQUIRE_NFC_UNLOCK);
            }
        }
    };

    private final FingerprintManager.LockoutResetCallback mFingerprintLockoutResetCallback
            = new FingerprintManager.LockoutResetCallback() {
        @Override
        public void onLockoutReset(int sensorId) {
            handleFingerprintLockoutReset(BIOMETRIC_LOCKOUT_NONE);
        }
    };

    /**
     * Propagates a pointer down event to keyguard.
     */
    public void onUdfpsPointerDown(int sensorId) {
        mFingerprintAuthenticationCallback.onUdfpsPointerDown(sensorId);
    }

    /**
     * Propagates a pointer up event to keyguard.
     */
    public void onUdfpsPointerUp(int sensorId) {
        mFingerprintAuthenticationCallback.onUdfpsPointerUp(sensorId);
    }

    @VisibleForTesting
    final FingerprintManager.AuthenticationCallback mFingerprintAuthenticationCallback
            = new AuthenticationCallback() {

                @Override
                public void onAuthenticationFailed() {
                    requestActiveUnlockDismissKeyguard(
                            ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL,
                            "fingerprintFailure");
                    handleFingerprintAuthFailed();
                }

                @Override
                public void onAuthenticationSucceeded(AuthenticationResult result) {
                    Trace.beginSection("KeyguardUpdateMonitor#onAuthenticationSucceeded");
                    handleFingerprintAuthenticated(result.getUserId(), result.isStrongBiometric());
                    Trace.endSection();
                }

                @Override
                public void onAuthenticationHelp(int helpMsgId, CharSequence helpString) {
                    Trace.beginSection("KeyguardUpdateMonitor#onAuthenticationHelp");
                    handleFingerprintHelp(helpMsgId, helpString.toString());
                    Trace.endSection();
                }

                @Override
                public void onAuthenticationError(int errMsgId, CharSequence errString) {
                    Trace.beginSection("KeyguardUpdateMonitor#onAuthenticationError");
                    handleFingerprintError(errMsgId, errString.toString());
                    Trace.endSection();
                }

                @Override
                public void onAuthenticationAcquired(int acquireInfo) {
                    Trace.beginSection("KeyguardUpdateMonitor#onAuthenticationAcquired");
                    mLogger.logFingerprintAcquired(acquireInfo);
                    handleFingerprintAcquired(acquireInfo);
                    Trace.endSection();
                }

                /**
                 * Note, this is currently called from UdfpsController.
                 */
                @Override
                public void onUdfpsPointerDown(int sensorId) {
                    mLogger.logUdfpsPointerDown(sensorId);
                }

                /**
                 * Note, this is currently called from UdfpsController.
                 */
                @Override
                public void onUdfpsPointerUp(int sensorId) {
                    mLogger.logUdfpsPointerUp(sensorId);
                }
            };

    private final FingerprintManager.FingerprintDetectionCallback mFingerprintDetectionCallback =
            new FingerprintManager.FingerprintDetectionCallback() {
                @Override
                public void onDetectionError(int errorMsgId) {
                    handleFingerprintError(errorMsgId, "");
                }

                @Override
                public void onFingerprintDetected(int sensorId, int userId,
                        boolean isStrongBiometric) {
                    handleBiometricDetected(userId, FINGERPRINT, isStrongBiometric);
                    setFingerprintRunningState(BIOMETRIC_STATE_STOPPED);
                }
            };

    @VisibleForTesting
    final DevicePostureController.Callback mPostureCallback =
            new DevicePostureController.Callback() {
                @Override
                public void onPostureChanged(@DevicePostureInt int posture) {
                    if (posture == DEVICE_POSTURE_OPENED) {
                        mLogger.d("Posture changed to open - attempting to request active unlock");
                        requestActiveUnlockFromWakeReason(PowerManager.WAKE_REASON_UNFOLD_DEVICE,
                                false);
                    }
                }
            };

    @VisibleForTesting
    CancellationSignal mFingerprintCancelSignal;
    private List<FingerprintSensorPropertiesInternal> mFingerprintSensorProperties =
            Collections.emptyList();
    private boolean mFingerprintLockedOut;
    private boolean mFingerprintLockedOutPermanent;

    /**
     * When we receive a {@link android.content.Intent#ACTION_SIM_STATE_CHANGED} broadcast,
     * and then pass a result via our handler to {@link KeyguardUpdateMonitor#handleSimStateChange},
     * we need a single object to pass to the handler.  This class helps decode
     * the intent and provide a {@link SimData} result.
     *
     * Below is the Sim state mapping matrixs:
     * +---+-----------------------------------------------------+----------------------------+
     * |   |Telephony FWK broadcast with action                  |SystemUI mapping SIM state  |
     * |   |android.content.Intent#ACTION_SIM_STATE_CHANGED      |refer to android.telephony. |
     * |NO.+-------------------------+---------------------------+TelephonyManager#getSimState|
     * |   |EXTRA_SIM_STATE          |EXTRA_SIM_LOCKED_REASON    |                            |
     * |   |(Intent#XXX)             |(Intent#XXX)               |TelephonyManager#SimState   |
     * +===+=====================================================+============================+
     * |1  |SIM_STATE_UNKNOWN        |always null                |SIM_STATE_UNKNOWN           |
     * +---+-------------------------+---------------------------+----------------------------+
     * |2  |SIM_STATE_ABSENT         |always null                |SIM_STATE_ABSENT            |
     * +---+-------------------------+---------------------------+----------------------------+
     * |3  |SIM_STATE_CARD_IO_ERROR  |SIM_STATE_CARD_IO_ERROR    |SIM_STATE_CARD_IO_ERROR     |
     * +---+-------------------------+---------------------------+----------------------------+
     * |4  |SIM_STATE_CARD_RESTRICTED|SIM_STATE_CARD_RESTRICTED  |SIM_STATE_CARD_RESTRICTED   |
     * +---+-------------------------+---------------------------+----------------------------+
     * |5  |SIM_STATE_LOCKED         |SIM_LOCKED_ON_PIN          |SIM_STATE_PIN_REQUIRED      |
     * +---+-------------------------+---------------------------+----------------------------+
     * |6  |SIM_STATE_LOCKED         |SIM_LOCKED_ON_PUK          |SIM_STATE_PUK_REQUIRED      |
     * +---+-------------------------+---------------------------+----------------------------+
     * |7  |SIM_STATE_LOCKED         |SIM_LOCKED_NETWORK         |SIM_STATE_NETWORK_LOCKED    |
     * +---+-------------------------+---------------------------+----------------------------+
     * |8  |SIM_STATE_LOCKED         |SIM_ABSENT_ON_PERM_DISABLED|SIM_STATE_PERM_DISABLED     |
     * +---+-------------------------+---------------------------+----------------------------+
     * |9  |SIM_STATE_NOT_READY      |always null                |SIM_STATE_NOT_READY         |
     * +---+-------------------------+---------------------------+----------------------------+
     * |10 |SIM_STATE_IMSI           |always null                |SIM_STATE_READY             |
     * +---+-------------------------+---------------------------+----------------------------+
     * |11 |SIM_STATE_READY          |always null                |SIM_STATE_READY             |
     * +---+-------------------------+---------------------------+----------------------------+
     * |12 |SIM_STATE_LOADED         |always null                |SIM_STATE_READY             |
     * +---+-------------------------+---------------------------+----------------------------+
     *
     * Note that, it seems #10 imsi ready case(i.e. SIM_STATE_IMSI) is never triggered from
     * Android Pie(telephony FWK doesn't trigger this broadcast any more), but it is still
     * OK keep this mapping logic.
     */
    private static class SimData {
        public int simState;
        public int slotId;
        public int subId;

        SimData(int state, int slot, int id) {
            simState = state;
            slotId = slot;
            subId = id;
        }

        static SimData fromIntent(Intent intent) {
            if (!Intent.ACTION_SIM_STATE_CHANGED.equals(intent.getAction())) {
                throw new IllegalArgumentException("only handles intent ACTION_SIM_STATE_CHANGED");
            }
            int state = TelephonyManager.SIM_STATE_UNKNOWN;
            String stateExtra = intent.getStringExtra(Intent.EXTRA_SIM_STATE);
            int slotId = intent.getIntExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 0);
            int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            if (Intent.SIM_STATE_ABSENT.equals(stateExtra)) {
                state = TelephonyManager.SIM_STATE_ABSENT;
            } else if (Intent.SIM_STATE_LOCKED.equals(stateExtra)) {
                final String lockedReason = intent
                        .getStringExtra(Intent.EXTRA_SIM_LOCKED_REASON);
                if (Intent.SIM_LOCKED_ON_PIN.equals(lockedReason)) {
                    state = TelephonyManager.SIM_STATE_PIN_REQUIRED;
                } else if (Intent.SIM_LOCKED_ON_PUK.equals(lockedReason)) {
                    state = TelephonyManager.SIM_STATE_PUK_REQUIRED;
                } else if (Intent.SIM_LOCKED_NETWORK.equals(lockedReason)) {
                    state = TelephonyManager.SIM_STATE_NETWORK_LOCKED;
                } else if (Intent.SIM_ABSENT_ON_PERM_DISABLED.equals(lockedReason)) {
                    state = TelephonyManager.SIM_STATE_PERM_DISABLED;
                }
            } else if (Intent.SIM_STATE_CARD_IO_ERROR.equals(stateExtra)) {
                state = TelephonyManager.SIM_STATE_CARD_IO_ERROR;
            } else if (Intent.SIM_STATE_CARD_RESTRICTED.equals(stateExtra)) {
                state = TelephonyManager.SIM_STATE_CARD_RESTRICTED;
            } else if (Intent.SIM_STATE_NOT_READY.equals(stateExtra)) {
                state = TelephonyManager.SIM_STATE_NOT_READY;
            } else if (Intent.SIM_STATE_READY.equals(stateExtra)
                    || Intent.SIM_STATE_LOADED.equals(stateExtra)
                    || Intent.SIM_STATE_IMSI.equals(stateExtra)) {
                // Mapping SIM_STATE_LOADED and SIM_STATE_IMSI to SIM_STATE_READY is required
                // because telephony doesn't return to "READY" after
                // these state transitions. See bug 7197471.
                state = TelephonyManager.SIM_STATE_READY;
            }
            return new SimData(state, slotId, subId);
        }

        @Override
        public String toString() {
            return "SimData{state=" + simState + ",slotId=" + slotId + ",subId=" + subId + "}";
        }
    }

    /**
     * Updates callbacks when strong auth requirements change.
     */
    public class StrongAuthTracker extends LockPatternUtils.StrongAuthTracker {
        public StrongAuthTracker(Context context) {
            super(context);
        }

        public boolean isUnlockingWithBiometricAllowed(boolean isStrongBiometric) {
            int userId = mSelectedUserInteractor.getSelectedUserId();
            return isBiometricAllowedForUser(isStrongBiometric, userId);
        }

        public boolean hasUserAuthenticatedSinceBoot() {
            int userId = mSelectedUserInteractor.getSelectedUserId();
            return (getStrongAuthForUser(userId)
                    & STRONG_AUTH_REQUIRED_AFTER_BOOT) == 0;
        }

        @Override
        public void onStrongAuthRequiredChanged(int userId) {
            notifyStrongAuthAllowedChanged(userId);
        }

        // TODO(b/247091681): Renaming the inappropriate onIsNonStrongBiometricAllowedChanged
        //  callback wording for Weak/Convenience idle timeout constraint that only allow
        //  Strong-Auth
        @Override
        public void onIsNonStrongBiometricAllowedChanged(int userId) {
            notifyNonStrongBiometricAllowedChanged(userId);
        }
    }

    protected void handleStartedWakingUp(@PowerManager.WakeReason int pmWakeReason) {
        Trace.beginSection("KeyguardUpdateMonitor#handleStartedWakingUp");
        Assert.isMainThread();

        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        requestActiveUnlockFromWakeReason(pmWakeReason, true);

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onStartedWakingUp();
            }
        }
        Trace.endSection();
    }

    protected void handleStartedGoingToSleep(int arg1) {
        Assert.isMainThread();
        setForceIsDismissibleKeyguard(false);
        clearFingerprintRecognized();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onStartedGoingToSleep(arg1);
            }
        }
        mGoingToSleep = true;
        // Resetting assistant visibility state as the device is going to sleep now.
        // TaskStackChangeListener gets triggered a little late when we transition to AoD,
        // which results in face auth running once on AoD.
        mAssistantVisible = false;
        mLogger.d("Started going to sleep, mGoingToSleep=true, mAssistantVisible=false");
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
    }

    protected void handleFinishedGoingToSleep(int arg1) {
        Assert.isMainThread();
        mGoingToSleep = false;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onFinishedGoingToSleep(arg1);
            }
        }
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
    }

    private void handleScreenTurnedOff() {
        Assert.isMainThread();
        mHardwareFingerprintUnavailableRetryCount = 0;
    }

    private void handleDreamingStateChanged(int dreamStart) {
        Assert.isMainThread();
        mIsDreaming = dreamStart == 1;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onDreamingStateChanged(mIsDreaming);
            }
        }
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
    }

    private void handleUserUnlocked(int userId) {
        Assert.isMainThread();
        mUserIsUnlocked.put(userId, true);
        mNeedsSlowUnlockTransition = resolveNeedsSlowUnlockTransition();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserUnlocked();
            }
        }
    }

    private void handleUserStopped(int userId) {
        Assert.isMainThread();
        mUserIsUnlocked.put(userId, mUserManager.isUserUnlocked(userId));
    }

    @VisibleForTesting
    void handleUserRemoved(int userId) {
        Assert.isMainThread();
        mUserIsUnlocked.delete(userId);
        mUserTrustIsUsuallyManaged.delete(userId);
    }

    private void handleKeyguardGoingAway(boolean goingAway) {
        Assert.isMainThread();
        setKeyguardGoingAway(goingAway);
    }

    @VisibleForTesting
    protected void setStrongAuthTracker(@NonNull StrongAuthTracker tracker) {
        if (mStrongAuthTracker != null) {
            mLockPatternUtils.unregisterStrongAuthTracker(mStrongAuthTracker);
        }

        mStrongAuthTracker = tracker;
        mLockPatternUtils.registerStrongAuthTracker(mStrongAuthTracker);
    }

    @VisibleForTesting
    void resetBiometricListeningState() {
        mFingerprintRunningState = BIOMETRIC_STATE_STOPPED;
        mFingerprintDetectRunning = false;
    }

    @VisibleForTesting
    @Inject
    protected KeyguardUpdateMonitor(
            Context context,
            UserTracker userTracker,
            @Main Looper mainLooper,
            BroadcastDispatcher broadcastDispatcher,
            DumpManager dumpManager,
            @Background Executor backgroundExecutor,
            @Main Executor mainExecutor,
            StatusBarStateController statusBarStateController,
            LockPatternUtils lockPatternUtils,
            AuthController authController,
            TelephonyListenerManager telephonyListenerManager,
            InteractionJankMonitor interactionJankMonitor,
            LatencyTracker latencyTracker,
            ActiveUnlockConfig activeUnlockConfiguration,
            KeyguardUpdateMonitorLogger logger,
            UiEventLogger uiEventLogger,
            // This has to be a provider because SessionTracker depends on KeyguardUpdateMonitor :(
            Provider<SessionTracker> sessionTrackerProvider,
            TrustManager trustManager,
            SubscriptionManager subscriptionManager,
            UserManager userManager,
            IDreamManager dreamManager,
            DevicePolicyManager devicePolicyManager,
            SensorPrivacyManager sensorPrivacyManager,
            TelephonyManager telephonyManager,
            PackageManager packageManager,
            @Nullable FingerprintManager fingerprintManager,
            @Nullable BiometricManager biometricManager,
            FaceWakeUpTriggersConfig faceWakeUpTriggersConfig,
            DevicePostureController devicePostureController,
            Optional<FingerprintInteractiveToAuthProvider> interactiveToAuthProvider,
            TaskStackChangeListeners taskStackChangeListeners,
            SelectedUserInteractor selectedUserInteractor,
            IActivityTaskManager activityTaskManagerService) {
        mContext = context;
        mSubscriptionManager = subscriptionManager;
        mUserTracker = userTracker;
        mTelephonyListenerManager = telephonyListenerManager;
        mDeviceProvisioned = isDeviceProvisionedInSettingsDb();
        mStrongAuthTracker = new StrongAuthTracker(context);
        mBackgroundExecutor = backgroundExecutor;
        mMainExecutor = mainExecutor;
        mBroadcastDispatcher = broadcastDispatcher;
        mInteractionJankMonitor = interactionJankMonitor;
        mLatencyTracker = latencyTracker;
        mStatusBarStateController = statusBarStateController;
        mStatusBarStateController.addCallback(mStatusBarStateControllerListener);
        mStatusBarState = mStatusBarStateController.getState();
        mLockPatternUtils = lockPatternUtils;
        mAuthController = authController;
        dumpManager.registerDumpable(this);
        mSensorPrivacyManager = sensorPrivacyManager;
        mActiveUnlockConfig = activeUnlockConfiguration;
        mLogger = logger;
        mUiEventLogger = uiEventLogger;
        mSessionTrackerProvider = sessionTrackerProvider;
        mTrustManager = trustManager;
        mUserManager = userManager;
        mDreamManager = dreamManager;
        mTelephonyManager = telephonyManager;
        mDevicePolicyManager = devicePolicyManager;
        mPackageManager = packageManager;
        mFpm = fingerprintManager;
        mBiometricManager = biometricManager;
        mConfigFaceAuthSupportedPosture = mContext.getResources().getInteger(
                R.integer.config_face_auth_supported_posture);
        mFaceWakeUpTriggersConfig = faceWakeUpTriggersConfig;
        mAllowFingerprintOnOccludingActivitiesFromPackage = Arrays.stream(
                mContext.getResources().getStringArray(
                        R.array.config_fingerprint_listen_on_occluding_activity_packages))
                .collect(Collectors.toSet());
        mDevicePostureController = devicePostureController;
        mTaskStackChangeListeners = taskStackChangeListeners;
        mActivityTaskManager = activityTaskManagerService;
        mSelectedUserInteractor = selectedUserInteractor;

        mFingerprintInteractiveToAuthProvider = interactiveToAuthProvider.orElse(null);
        mIsSystemUser = mUserManager.isSystemUser();

        mHandler = new Handler(mainLooper) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_TIME_UPDATE:
                        handleTimeUpdate();
                        break;
                    case MSG_TIMEZONE_UPDATE:
                        handleTimeZoneUpdate((String) msg.obj);
                        break;
                    case MSG_BATTERY_UPDATE:
                        handleBatteryUpdate((BatteryStatus) msg.obj);
                        break;
                    case MSG_SIM_STATE_CHANGE:
                        handleSimStateChange(msg.arg1, msg.arg2, (int) msg.obj);
                        break;
                    case MSG_PHONE_STATE_CHANGED:
                        handlePhoneStateChanged((String) msg.obj);
                        break;
                    case MSG_DEVICE_PROVISIONED:
                        handleDeviceProvisioned();
                        break;
                    case MSG_DPM_STATE_CHANGED:
                        handleDevicePolicyManagerStateChanged(msg.arg1);
                        break;
                    case MSG_USER_SWITCHING:
                        handleUserSwitching(msg.arg1, (Runnable) msg.obj);
                        break;
                    case MSG_USER_SWITCH_COMPLETE:
                        handleUserSwitchComplete(msg.arg1);
                        break;
                    case MSG_KEYGUARD_RESET:
                        handleKeyguardReset();
                        break;
                    case MSG_KEYGUARD_BOUNCER_CHANGED:
                        handlePrimaryBouncerChanged(msg.arg1, msg.arg2);
                        break;
                    case MSG_REPORT_EMERGENCY_CALL_ACTION:
                        handleReportEmergencyCallAction();
                        break;
                    case MSG_STARTED_GOING_TO_SLEEP:
                        handleStartedGoingToSleep(msg.arg1);
                        break;
                    case MSG_FINISHED_GOING_TO_SLEEP:
                        handleFinishedGoingToSleep(msg.arg1);
                        break;
                    case MSG_STARTED_WAKING_UP:
                        Trace.beginSection("KeyguardUpdateMonitor#handler MSG_STARTED_WAKING_UP");
                        handleStartedWakingUp(msg.arg1);
                        Trace.endSection();
                        break;
                    case MSG_SIM_SUBSCRIPTION_INFO_CHANGED:
                        handleSimSubscriptionInfoChanged();
                        break;
                    case MSG_AIRPLANE_MODE_CHANGED:
                        handleAirplaneModeChanged();
                        break;
                    case MSG_SERVICE_STATE_CHANGE:
                        handleServiceStateChange(msg.arg1, (ServiceState) msg.obj);
                        break;
                    case MSG_SERVICE_PROVIDERS_UPDATED:
                        handleServiceProvidersUpdated((Intent) msg.obj);
                        break;
                    case MSG_SCREEN_TURNED_OFF:
                        Trace.beginSection("KeyguardUpdateMonitor#handler MSG_SCREEN_TURNED_OFF");
                        handleScreenTurnedOff();
                        Trace.endSection();
                        break;
                    case MSG_DREAMING_STATE_CHANGED:
                        handleDreamingStateChanged(msg.arg1);
                        break;
                    case MSG_USER_UNLOCKED:
                        handleUserUnlocked(msg.arg1);
                        break;
                    case MSG_USER_STOPPED:
                        handleUserStopped(msg.arg1);
                        break;
                    case MSG_USER_REMOVED:
                        handleUserRemoved(msg.arg1);
                        break;
                    case MSG_ASSISTANT_STACK_CHANGED:
                        setAssistantVisible((boolean) msg.obj);
                        break;
                    case MSG_BIOMETRIC_AUTHENTICATION_CONTINUE:
                        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
                        break;
                    case MSG_DEVICE_POLICY_MANAGER_STATE_CHANGED:
                        updateLogoutEnabled();
                        break;
                    case MSG_TELEPHONY_CAPABLE:
                        updateTelephonyCapable((boolean) msg.obj);
                        break;
                    case MSG_KEYGUARD_GOING_AWAY:
                        handleKeyguardGoingAway((boolean) msg.obj);
                        break;
                    case MSG_TIME_FORMAT_UPDATE:
                        handleTimeFormatUpdate((String) msg.obj);
                        break;
                    case MSG_REQUIRE_NFC_UNLOCK:
                        handleRequireUnlockForNfc();
                        break;
                    case MSG_KEYGUARD_DISMISS_ANIMATION_FINISHED:
                        handleKeyguardDismissAnimationFinished();
                        break;
                    case MSG_BIOMETRIC_ENROLLMENT_STATE_CHANGED:
                        notifyAboutEnrollmentChange(msg.arg1);
                        break;
                    default:
                        super.handleMessage(msg);
                        break;
                }
            }
        };

        mTimeFormatChangeObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mHandler.sendMessage(mHandler.obtainMessage(
                        MSG_TIME_FORMAT_UPDATE,
                        Settings.System.getString(
                                mContext.getContentResolver(),
                                Settings.System.TIME_12_24)));
            }
        };
    }

    @Override
    public void start() {
        // Since device can't be un-provisioned, we only need to register a content observer
        // to update mDeviceProvisioned when we are...
        if (!mDeviceProvisioned) {
            watchForDeviceProvisioning();
        }

        // Take a guess at initial SIM state, battery status and PLMN until we get an update
        mBatteryStatus = new BatteryStatus(BATTERY_STATUS_UNKNOWN, /* level= */ 100, /* plugged= */
                0, CHARGING_POLICY_DEFAULT, /* maxChargingWattage= */0, /* present= */true);

        // Watch for interesting updates
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_SIM_STATE_CHANGED);
        filter.addAction(Intent.ACTION_SERVICE_STATE);
        filter.addAction(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED);
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        filter.addAction(UsbManager.ACTION_USB_PORT_COMPLIANCE_CHANGED);
        mBroadcastDispatcher.registerReceiverWithHandler(mBroadcastReceiver, filter, mHandler);
        // Since ACTION_SERVICE_STATE is being moved to a non-sticky broadcast, trigger the
        // listener now with the service state from the default sub.
        mBackgroundExecutor.execute(() -> {
            int subId = SubscriptionManager.getDefaultSubscriptionId();
            ServiceState serviceState = mTelephonyManager.getServiceStateForSubscriber(subId);
            mHandler.sendMessage(
                    mHandler.obtainMessage(MSG_SERVICE_STATE_CHANGE, subId, 0, serviceState));
        });

        final IntentFilter allUserFilter = new IntentFilter();
        allUserFilter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        allUserFilter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        allUserFilter.addAction(ACTION_USER_UNLOCKED);
        allUserFilter.addAction(ACTION_USER_STOPPED);
        allUserFilter.addAction(ACTION_USER_REMOVED);
        allUserFilter.addAction(NfcAdapter.ACTION_REQUIRE_UNLOCK_FOR_NFC);
        mBroadcastDispatcher.registerReceiverWithHandler(mBroadcastAllReceiver, allUserFilter,
                mHandler, UserHandle.ALL);

        mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionListener);
        mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);

        mTrustManager.registerTrustListener(this);

        setStrongAuthTracker(mStrongAuthTracker);

        if (mFpm != null) {
            mFpm.addAuthenticatorsRegisteredCallback(
                    new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                        @Override
                        public void onAllAuthenticatorsRegistered(
                                List<FingerprintSensorPropertiesInternal> sensors)
                                throws RemoteException {
                            mFingerprintSensorProperties = sensors;
                            updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
                            mLogger.d("FingerprintManager onAllAuthenticatorsRegistered");
                        }
                    });
            mFpm.addLockoutResetCallback(mFingerprintLockoutResetCallback);
        }

        if (mBiometricManager != null) {
            mBiometricManager.registerEnabledOnKeyguardCallback(mBiometricEnabledCallback);
        }

        // in case authenticators aren't registered yet at this point:
        mAuthController.addCallback(new AuthController.Callback() {
            @Override
            public void onAllAuthenticatorsRegistered(
                    @BiometricAuthenticator.Modality int modality) {
                mMainExecutor.execute(
                        () -> updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE));
            }

            @Override
            public void onEnrollmentsChanged(@BiometricAuthenticator.Modality int modality) {
                mHandler.obtainMessage(MSG_BIOMETRIC_ENROLLMENT_STATE_CHANGED, modality, 0)
                        .sendToTarget();
                mMainExecutor.execute(
                        () -> updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE));
            }

            @Override
            public void onBiometricPromptShown() {
                // SysUI should give priority to the biometric prompt requesting FP instead of
                // taking over the fingerprint listening state.
                mBiometricPromptShowing = true;
            }

            @Override
            public void onBiometricPromptDismissed() {
                mBiometricPromptShowing = false;
                updateFingerprintListeningState(BIOMETRIC_ACTION_START);
            }
        });
        if (mConfigFaceAuthSupportedPosture != DEVICE_POSTURE_UNKNOWN) {
            mDevicePostureController.addCallback(mPostureCallback);
        }
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);

        mTaskStackChangeListeners.registerTaskStackListener(mTaskStackListener);
        int user = mSelectedUserInteractor.getSelectedUserId(true);
        mUserIsUnlocked.put(user, mUserManager.isUserUnlocked(user));
        mLogoutEnabled = mDevicePolicyManager.isLogoutEnabled();
        updateSecondaryLockscreenRequirement(user);
        List<UserInfo> allUsers = mUserManager.getUsers();
        for (UserInfo userInfo : allUsers) {
            boolean trustUsuallyManaged = mTrustManager.isTrustUsuallyManaged(userInfo.id);
            mLogger.logTrustUsuallyManagedUpdated(userInfo.id,
                    mUserTrustIsUsuallyManaged.get(userInfo.id),
                    trustUsuallyManaged, "init from constructor");
            mUserTrustIsUsuallyManaged.put(userInfo.id,
                    trustUsuallyManaged);
        }
        updateAirplaneModeState();

        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(mPhoneStateListener);
        initializeSimState();

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.TIME_12_24),
                false, mTimeFormatChangeObserver, UserHandle.USER_ALL);
    }

    private void initializeSimState() {
        // Set initial sim states values.
        for (int slot = 0; slot < mTelephonyManager.getActiveModemCount(); slot++) {
            int state = mTelephonyManager.getSimState(slot);
            int[] subIds = mSubscriptionManager.getSubscriptionIds(slot);
            if (subIds != null) {
                for (int subId : subIds) {
                    mHandler.obtainMessage(MSG_SIM_STATE_CHANGE, subId, slot, state)
                            .sendToTarget();
                }
            }
        }
    }

    private boolean isFingerprintSupported() {
        return mFpm != null && !mFingerprintSensorProperties.isEmpty();
    }

    /**
     * @return true if there's at least one udfps enrolled for the current user.
     */
    public boolean isUdfpsEnrolled() {
        return mAuthController.isUdfpsEnrolled(mSelectedUserInteractor.getSelectedUserId());
    }

    /**
     * @return true if udfps HW is supported on this device. Can return true even if the user has
     * not enrolled udfps. This may be false if called before onAllAuthenticatorsRegistered.
     */
    public boolean isUdfpsSupported() {
        return mAuthController.isUdfpsSupported();
    }

    /**
     * @return true if there's at least one sfps enrollment for the current user.
     */
    public boolean isSfpsEnrolled() {
        return mAuthController.isSfpsEnrolled(mSelectedUserInteractor.getSelectedUserId());
    }

    /**
     * @return true if sfps HW is supported on this device. Can return true even if the user has
     * not enrolled sfps. This may be false if called before onAllAuthenticatorsRegistered.
     */
    public boolean isSfpsSupported() {
        return mAuthController.isSfpsSupported();
    }

    /**
     * @return true if there's at least one face enrolled
     * @deprecated Use {@link DeviceEntryFaceAuthInteractor#isFaceAuthEnabledAndEnrolled()}
     */
    @Deprecated
    public boolean isFaceEnabledAndEnrolled() {
        return getFaceAuthInteractor() != null
                && getFaceAuthInteractor().isFaceAuthEnabledAndEnrolled();
    }

    private final UserTracker.Callback mUserChangedCallback = new UserTracker.Callback() {

        @Override
        public void onUserChanging(int newUser, @NonNull Context userContext,
                @NonNull Runnable resultCallback) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCHING,
                    newUser, 0, resultCallback));
        }

        @Override
        public void onUserChanged(int newUser, Context userContext) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCH_COMPLETE,
                    newUser, 0));
        }
    };

    private void updateAirplaneModeState() {
        // ACTION_AIRPLANE_MODE_CHANGED do not broadcast if device set AirplaneMode ON and boot
        if (!WirelessUtils.isAirplaneModeOn(mContext)
                || mHandler.hasMessages(MSG_AIRPLANE_MODE_CHANGED)) {
            return;
        }
        mHandler.sendEmptyMessage(MSG_AIRPLANE_MODE_CHANGED);
    }

    private void updateFingerprintListeningState(int action) {
        // If this message exists, we should not authenticate again until this message is
        // consumed by the handler
        if (mHandler.hasMessages(MSG_BIOMETRIC_AUTHENTICATION_CONTINUE)) {
            mLogger.logHandlerHasAuthContinueMsgs(action);
            return;
        }

        // don't start running fingerprint until they're registered
        if (!mAuthController.areAllFingerprintAuthenticatorsRegistered()) {
            mLogger.d("All FP authenticators not registered, skipping FP listening state update");
            return;
        }
        final boolean shouldListenForFingerprint = shouldListenForFingerprint(isUdfpsSupported());
        final boolean running = mFingerprintRunningState == BIOMETRIC_STATE_RUNNING;
        final boolean runningOrRestarting = running
                || mFingerprintRunningState == BIOMETRIC_STATE_CANCELLING_RESTARTING;
        final boolean runDetect = shouldRunFingerprintDetect();
        if (runningOrRestarting && !shouldListenForFingerprint) {
            if (action == BIOMETRIC_ACTION_START) {
                mLogger.v("Ignoring stopListeningForFingerprint()");
                return;
            }
            stopListeningForFingerprint();
        } else if (!runningOrRestarting && shouldListenForFingerprint) {
            if (action == BIOMETRIC_ACTION_STOP) {
                mLogger.v("Ignoring startListeningForFingerprint()");
                return;
            }
            startListeningForFingerprint(runDetect);
        } else if (running && runDetect && !mFingerprintDetectRunning) {
            if (action == BIOMETRIC_ACTION_STOP) {
                mLogger.v("Ignoring startListeningForFingerprint(detect)");
                return;
            }
            // stop running authentication and start running fingerprint detection
            stopListeningForFingerprint();
            startListeningForFingerprint(true);
        }
    }

    private boolean shouldRunFingerprintDetect() {
        return !isUnlockingWithFingerprintAllowed()
                || (Flags.runFingerprintDetectOnDismissibleKeyguard()
                && getUserCanSkipBouncer(mSelectedUserInteractor.getSelectedUserId()));
    }

    /**
     * If a user is encrypted or not.
     * This is NOT related to the lock screen being visible or not.
     *
     * @param userId The user.
     * @return {@code true} when encrypted.
     * @see UserManager#isUserUnlocked()
     * @see Intent#ACTION_USER_UNLOCKED
     */
    public boolean isUserUnlocked(int userId) {
        return mUserIsUnlocked.get(userId);
    }

    /**
     * Called whenever passive authentication is requested or aborted by a sensor.
     *
     * @param active If the interrupt started or ended.
     */
    public void onAuthInterruptDetected(boolean active) {
        mLogger.logAuthInterruptDetected(active);
        if (mAuthInterruptActive == active) {
            return;
        }
        mAuthInterruptActive = active;
        requestActiveUnlock(ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE, "onReach");
    }

    @Nullable
    private InstanceId getKeyguardSessionId() {
        return mSessionTrackerProvider.get().getSessionId(SESSION_KEYGUARD);
    }

    /**
     * Initiates active unlock to get the unlock token ready.
     */
    private void initiateActiveUnlock(String reason) {
        // If this message exists, FP has already authenticated, so wait until that is handled
        if (mHandler.hasMessages(MSG_BIOMETRIC_AUTHENTICATION_CONTINUE)) {
            return;
        }

        if (shouldTriggerActiveUnlock()) {
            mLogger.logActiveUnlockTriggered(reason);
            mTrustManager.reportUserMayRequestUnlock(mSelectedUserInteractor.getSelectedUserId());
        }
    }

    private void requestActiveUnlockFromWakeReason(@PowerManager.WakeReason int wakeReason,
            boolean powerManagerWakeup) {
        if (!mFaceWakeUpTriggersConfig.shouldTriggerFaceAuthOnWakeUpFrom(wakeReason)) {
            mLogger.logActiveUnlockRequestSkippedForWakeReasonDueToFaceConfig(wakeReason);
            return;
        }

        final ActiveUnlockConfig.ActiveUnlockRequestOrigin requestOrigin =
                mActiveUnlockConfig.isWakeupConsideredUnlockIntent(wakeReason)
                        ? ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT
                        : ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE;
        final String reason = "wakingUp - " + PowerManager.wakeReasonToString(wakeReason)
                + " powerManagerWakeup=" + powerManagerWakeup;
        if (mActiveUnlockConfig.shouldWakeupForceDismissKeyguard(wakeReason)) {
            requestActiveUnlockDismissKeyguard(
                    requestOrigin,
                    reason
            );
        } else {
            requestActiveUnlock(
                    requestOrigin,
                    reason
            );
        }
    }

    /**
     * Attempts to trigger active unlock from trust agent.
     */
    private void requestActiveUnlock(
            @NonNull ActiveUnlockConfig.ActiveUnlockRequestOrigin requestOrigin,
            String reason,
            boolean dismissKeyguard
    ) {
        // If this message exists, FP has already authenticated, so wait until that is handled
        if (mHandler.hasMessages(MSG_BIOMETRIC_AUTHENTICATION_CONTINUE)) {
            return;
        }

        final boolean allowRequest =
                mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(requestOrigin);
        if (requestOrigin == ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE
                && !allowRequest && mActiveUnlockConfig.isActiveUnlockEnabled()) {
            // instead of requesting the active unlock, initiate the unlock
            initiateActiveUnlock(reason);
            return;
        }

        if (allowRequest && shouldTriggerActiveUnlock()) {
            mLogger.logUserRequestedUnlock(requestOrigin, reason, dismissKeyguard);
            mTrustManager.reportUserRequestedUnlock(mSelectedUserInteractor.getSelectedUserId(),
                    dismissKeyguard);
        }
    }


    /**
     * Attempts to trigger active unlock from trust agent.
     * Only dismisses the keyguard under certain conditions.
     */
    public void requestActiveUnlock(
            @NonNull ActiveUnlockConfig.ActiveUnlockRequestOrigin requestOrigin,
            String extraReason
    ) {
        final boolean canFaceBypass =
                isFaceEnabledAndEnrolled() && mKeyguardBypassController != null
                        && mKeyguardBypassController.canBypass();
        requestActiveUnlock(
                requestOrigin,
                extraReason, canFaceBypass
                        || mAlternateBouncerShowing
                        || mPrimaryBouncerFullyShown
                        || mAuthController.isUdfpsFingerDown());
    }

    /**
     * Attempts to trigger active unlock from trust agent with a request to dismiss the keyguard.
     */
    public void requestActiveUnlockDismissKeyguard(
            @NonNull ActiveUnlockConfig.ActiveUnlockRequestOrigin requestOrigin,
            String extraReason
    ) {
        requestActiveUnlock(
                requestOrigin,
                extraReason + "-dismissKeyguard", true);
    }

    /**
     * Whether the alternate bouncer is showing.
     */
    public void setAlternateBouncerShowing(boolean showing) {
        mAlternateBouncerShowing = showing;
        if (mAlternateBouncerShowing) {
            requestActiveUnlock(
                    ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT,
                    "alternateBouncer");
        }
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
    }

    /**
     * If the current state of the device allows for triggering active unlock. This does not
     * include active unlock availability.
     */
    public boolean canTriggerActiveUnlockBasedOnDeviceState() {
        return shouldTriggerActiveUnlock(/* shouldLog */ false);
    }

    private boolean shouldTriggerActiveUnlock() {
        return shouldTriggerActiveUnlock(/* shouldLog */ true);
    }

    private boolean shouldTriggerActiveUnlock(boolean shouldLog) {
        // Triggers:
        final boolean triggerActiveUnlockForAssistant = shouldTriggerActiveUnlockForAssistant();
        final boolean awakeKeyguard = mPrimaryBouncerFullyShown || mAlternateBouncerShowing
                || (isKeyguardVisible() && !mGoingToSleep
                && mStatusBarState != StatusBarState.SHADE_LOCKED);

        // Gates:
        final int user = mSelectedUserInteractor.getSelectedUserId();

        // No need to trigger active unlock if we're already unlocked or don't have
        // pin/pattern/password setup
        final boolean userCanDismissLockScreen = getUserCanSkipBouncer(user)
                || !mLockPatternUtils.isSecure(user);

        // Don't trigger active unlock if fp is locked out
        final boolean fpLockedOut = isFingerprintLockedOut();

        // Don't trigger active unlock if primary auth is required
        final boolean primaryAuthRequired = !isUnlockingWithTrustAgentAllowed();

        final boolean shouldTriggerActiveUnlock =
                (mAuthInterruptActive || triggerActiveUnlockForAssistant || awakeKeyguard)
                        && !mSwitchingUser
                        && !userCanDismissLockScreen
                        && !fpLockedOut
                        && !primaryAuthRequired
                        && !mKeyguardGoingAway
                        && !mSecureCameraLaunched;

        if (shouldLog) {
            // Aggregate relevant fields for debug logging.
            logListenerModelData(
                    new KeyguardActiveUnlockModel(
                            System.currentTimeMillis(),
                            user,
                            shouldTriggerActiveUnlock,
                            awakeKeyguard,
                            mAuthInterruptActive,
                            fpLockedOut,
                            primaryAuthRequired,
                            mSwitchingUser,
                            triggerActiveUnlockForAssistant,
                            userCanDismissLockScreen));
        }

        return shouldTriggerActiveUnlock;
    }

    private boolean shouldListenForFingerprintAssistant() {
        BiometricAuthenticated fingerprint = mUserFingerprintAuthenticated.get(
                mSelectedUserInteractor.getSelectedUserId());
        return mAssistantVisible && mKeyguardOccluded
                && !(fingerprint != null && fingerprint.mAuthenticated)
                && !mUserHasTrust.get(
                        mSelectedUserInteractor.getSelectedUserId(), false);
    }

    private boolean shouldTriggerActiveUnlockForAssistant() {
        return mAssistantVisible && mKeyguardOccluded
                && !mUserHasTrust.get(mSelectedUserInteractor.getSelectedUserId(), false);
    }

    @VisibleForTesting
    protected boolean shouldListenForFingerprint(boolean isUdfps) {
        final int user = mSelectedUserInteractor.getSelectedUserId();
        final boolean userDoesNotHaveTrust = !getUserHasTrust(user);
        final boolean shouldListenForFingerprintAssistant = shouldListenForFingerprintAssistant();
        final boolean shouldListenKeyguardState =
                isKeyguardVisible()
                        || !mDeviceInteractive
                        || (mPrimaryBouncerIsOrWillBeShowing && !mKeyguardGoingAway)
                        || mGoingToSleep
                        || shouldListenForFingerprintAssistant
                        || (mKeyguardOccluded && mIsDreaming)
                        || (mKeyguardOccluded && userDoesNotHaveTrust && mKeyguardShowing
                        && (mOccludingAppRequestingFp
                        || isUdfps
                        || mAlternateBouncerShowing
                        || mAllowFingerprintOnCurrentOccludingActivity
                )
            );

        // Only listen if this KeyguardUpdateMonitor belongs to the system user. There is an
        // instance of KeyguardUpdateMonitor for each user but KeyguardUpdateMonitor is user-aware.
        final boolean biometricEnabledForUser = mBiometricEnabledForUser.get(user);
        final boolean userCanSkipBouncer = getUserCanSkipBouncer(user);
        final boolean fingerprintDisabledForUser = isFingerprintDisabled(user);
        final boolean shouldListenUserState =
                !mSwitchingUser
                        && !fingerprintDisabledForUser
                        && (!mKeyguardGoingAway || !mDeviceInteractive)
                        && mIsSystemUser
                        && biometricEnabledForUser
                        && !isUserInLockdown(user);
        final boolean strongerAuthRequired = !isUnlockingWithFingerprintAllowed();
        final boolean shouldListenBouncerState =
                !strongerAuthRequired || !mPrimaryBouncerIsOrWillBeShowing;

        final boolean shouldListenUdfpsState = !isUdfps
                || (!userCanSkipBouncer
                && !strongerAuthRequired
                && userDoesNotHaveTrust);


        boolean shouldListen = shouldListenKeyguardState && shouldListenUserState
                && shouldListenBouncerState && shouldListenUdfpsState && !mBiometricPromptShowing;
        logListenerModelData(
                new KeyguardFingerprintListenModel(
                    System.currentTimeMillis(),
                    user,
                    shouldListen,
                    mAllowFingerprintOnCurrentOccludingActivity,
                    mAlternateBouncerShowing,
                    biometricEnabledForUser,
                    mBiometricPromptShowing,
                    mPrimaryBouncerIsOrWillBeShowing,
                    userCanSkipBouncer,
                    mCredentialAttempted,
                    mDeviceInteractive,
                    mIsDreaming,
                    fingerprintDisabledForUser,
                    mFingerprintLockedOut,
                    mGoingToSleep,
                    mKeyguardGoingAway,
                    isKeyguardVisible(),
                    mKeyguardOccluded,
                    mOccludingAppRequestingFp,
                    shouldListenForFingerprintAssistant,
                    strongerAuthRequired,
                    mSwitchingUser,
                    mIsSystemUser,
                    isUdfps,
                    userDoesNotHaveTrust));

        return shouldListen;
    }

    /**
     * If face auth is allows to scan on this exact moment.
     *
     * @deprecated Use {@link DeviceEntryFaceAuthInteractor#canFaceAuthRun()}
     */
    @Deprecated
    public boolean shouldListenForFace() {
        return getFaceAuthInteractor() != null && getFaceAuthInteractor().canFaceAuthRun();
    }


    private void logListenerModelData(@NonNull KeyguardListenModel model) {
        mLogger.logKeyguardListenerModel(model);
        if (model instanceof KeyguardFingerprintListenModel) {
            mFingerprintListenBuffer.insert((KeyguardFingerprintListenModel) model);
        } else if (model instanceof KeyguardActiveUnlockModel) {
            mActiveUnlockTriggerBuffer.insert((KeyguardActiveUnlockModel) model);
        }
    }

    private void startListeningForFingerprint(boolean runDetect) {
        final int userId = mSelectedUserInteractor.getSelectedUserId();
        final boolean unlockPossible = isUnlockWithFingerprintPossible(userId);
        if (mFingerprintCancelSignal != null) {
            mLogger.logUnexpectedFpCancellationSignalState(
                    mFingerprintRunningState,
                    unlockPossible);
        }

        if (mFingerprintRunningState == BIOMETRIC_STATE_CANCELLING) {
            setFingerprintRunningState(BIOMETRIC_STATE_CANCELLING_RESTARTING);
            return;
        }
        if (mFingerprintRunningState == BIOMETRIC_STATE_CANCELLING_RESTARTING) {
            // Waiting for restart via handleFingerprintError().
            return;
        }

        if (unlockPossible) {
            mFingerprintCancelSignal = new CancellationSignal();

            final FingerprintAuthenticateOptions fingerprintAuthenticateOptions =
                    new FingerprintAuthenticateOptions.Builder()
                        .setUserId(userId)
                        .build();
            if (mFingerprintInteractiveToAuthProvider != null) {
                fingerprintAuthenticateOptions.setVendorReason(
                        mFingerprintInteractiveToAuthProvider.getVendorExtension(userId));
            }

            if (runDetect) {
                mLogger.v("startListeningForFingerprint - detect");
                mFpm.detectFingerprint(
                        mFingerprintCancelSignal,
                        mFingerprintDetectionCallback,
                        fingerprintAuthenticateOptions);
                mFingerprintDetectRunning = true;
            } else {
                mLogger.v("startListeningForFingerprint");
                mFpm.authenticate(null /* crypto */, mFingerprintCancelSignal,
                        mFingerprintAuthenticationCallback,
                        null /* handler */,
                        fingerprintAuthenticateOptions);
                mFingerprintDetectRunning = false;
            }
            setFingerprintRunningState(BIOMETRIC_STATE_RUNNING);
        }
    }

    public boolean isFingerprintLockedOut() {
        return mFingerprintLockedOut || mFingerprintLockedOutPermanent;
    }

    /**
     * @deprecated Use {@link DeviceEntryFaceAuthInteractor#isLockedOut()}
     */
    @Deprecated
    public boolean isFaceLockedOut() {
        return getFaceAuthInteractor() != null && getFaceAuthInteractor().isLockedOut();
    }

    /**
     * If biometrics hardware is available, not disabled, and user has enrolled templates.
     * This does NOT check if the device is encrypted or in lockdown.
     *
     * @param userId User that's trying to unlock.
     * @return {@code true} if possible.
     */
    public boolean isUnlockingWithBiometricsPossible(int userId) {
        return isUnlockWithFacePossible() || isUnlockWithFingerprintPossible(userId);
    }

    /**
     * If non-strong (i.e. weak or convenience) biometrics hardware is available, not disabled, and
     * user has enrolled templates. This does NOT check if the device is encrypted or in lockdown.
     *
     * @param userId User that's trying to unlock.
     * @return {@code true} if possible.
     */
    public boolean isUnlockingWithNonStrongBiometricsPossible(int userId) {
        if (getFaceAuthInteractor() != null && !getFaceAuthInteractor().isFaceAuthStrong()) {
            if (isUnlockWithFacePossible()) {
                return true;
            }
        }
        return isFingerprintClass3() && isUnlockWithFingerprintPossible(userId);
    }

    @SuppressLint("MissingPermission")
    public boolean isUnlockWithFingerprintPossible(int userId) {
        return isFingerprintSupported()
                && !isFingerprintDisabled(userId) && mAuthController.isFingerprintEnrolled(userId);
    }

    /**
     * @deprecated Use {@link DeviceEntryFaceAuthInteractor#isFaceAuthEnabledAndEnrolled()}
     */
    @VisibleForTesting
    @Deprecated
    public boolean isUnlockWithFacePossible() {
        return getFaceAuthInteractor() != null
                    && getFaceAuthInteractor().isFaceAuthEnabledAndEnrolled();
    }

    private void notifyAboutEnrollmentChange(@BiometricAuthenticator.Modality int modality) {
        BiometricSourceType biometricSourceType;
        if (modality == TYPE_FINGERPRINT) {
            biometricSourceType = FINGERPRINT;
        } else if (modality == TYPE_FACE) {
            biometricSourceType = FACE;
        } else {
            return;
        }
        mLogger.notifyAboutEnrollmentsChanged(biometricSourceType);
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricEnrollmentStateChanged(biometricSourceType);
            }
        }
    }

    private void stopListeningForFingerprint() {
        mLogger.v("stopListeningForFingerprint()");
        if (mFingerprintRunningState == BIOMETRIC_STATE_RUNNING) {
            if (mFingerprintCancelSignal != null) {
                mFingerprintCancelSignal.cancel();
                mFingerprintCancelSignal = null;
                mHandler.removeCallbacks(mFpCancelNotReceived);
                mHandler.postDelayed(mFpCancelNotReceived, DEFAULT_CANCEL_SIGNAL_TIMEOUT);
            }
            setFingerprintRunningState(BIOMETRIC_STATE_CANCELLING);
        }
        if (mFingerprintRunningState == BIOMETRIC_STATE_CANCELLING_RESTARTING) {
            setFingerprintRunningState(BIOMETRIC_STATE_CANCELLING);
        }
    }

    private boolean isDeviceProvisionedInSettingsDb() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    private void watchForDeviceProvisioning() {
        mDeviceProvisionedObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                mDeviceProvisioned = isDeviceProvisionedInSettingsDb();
                if (mDeviceProvisioned) {
                    mHandler.sendEmptyMessage(MSG_DEVICE_PROVISIONED);
                }
                mLogger.logDeviceProvisionedState(mDeviceProvisioned);
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                false, mDeviceProvisionedObserver);

        // prevent a race condition between where we check the flag and where we register the
        // observer by grabbing the value once again...
        boolean provisioned = isDeviceProvisionedInSettingsDb();
        if (provisioned != mDeviceProvisioned) {
            mDeviceProvisioned = provisioned;
            if (mDeviceProvisioned) {
                mHandler.sendEmptyMessage(MSG_DEVICE_PROVISIONED);
            }
        }
    }

    /**
     * Handle {@link #MSG_DPM_STATE_CHANGED} which can change primary authentication methods to
     * pin/pattern/password/none.
     */
    private void handleDevicePolicyManagerStateChanged(int userId) {
        Assert.isMainThread();
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        updateSecondaryLockscreenRequirement(userId);

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onDevicePolicyManagerStateChanged();
            }
        }
    }

    /**
     * Handle {@link #MSG_USER_SWITCHING}
     */
    @VisibleForTesting
    void handleUserSwitching(int userId, Runnable resultCallback) {
        mLogger.logUserSwitching(userId, "from UserTracker");
        Assert.isMainThread();
        setForceIsDismissibleKeyguard(false);
        clearFingerprintRecognized();
        boolean trustUsuallyManaged = mTrustManager.isTrustUsuallyManaged(userId);
        mLogger.logTrustUsuallyManagedUpdated(userId, mUserTrustIsUsuallyManaged.get(userId),
                trustUsuallyManaged, "userSwitching");
        mUserTrustIsUsuallyManaged.put(userId, trustUsuallyManaged);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserSwitching(userId);
            }
        }
        resultCallback.run();
    }

    /**
     * Handle {@link #MSG_USER_SWITCH_COMPLETE}
     */
    @VisibleForTesting
    void handleUserSwitchComplete(int userId) {
        mLogger.logUserSwitchComplete(userId, "from UserTracker");
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserSwitchComplete(userId);
            }
        }

        if (isFingerprintSupported()) {
            stopListeningForFingerprint();
            handleFingerprintLockoutReset(mFpm.getLockoutModeForUser(
                    mFingerprintSensorProperties.get(0).sensorId, userId));
        }

        mInteractionJankMonitor.end(InteractionJankMonitor.CUJ_USER_SWITCH);
        mLatencyTracker.onActionEnd(LatencyTracker.ACTION_USER_SWITCH);
    }

    /**
     * Handle {@link #MSG_DEVICE_PROVISIONED}
     */
    private void handleDeviceProvisioned() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onDeviceProvisioned();
            }
        }
        if (mDeviceProvisionedObserver != null) {
            // We don't need the observer anymore...
            mContext.getContentResolver().unregisterContentObserver(mDeviceProvisionedObserver);
            mDeviceProvisionedObserver = null;
        }
    }

    /**
     * Handle {@link #MSG_PHONE_STATE_CHANGED}
     */
    private void handlePhoneStateChanged(String newState) {
        Assert.isMainThread();
        mLogger.logPhoneStateChanged(newState);
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(newState)) {
            mPhoneState = TelephonyManager.CALL_STATE_IDLE;
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(newState)) {
            mPhoneState = TelephonyManager.CALL_STATE_OFFHOOK;
        } else if (TelephonyManager.EXTRA_STATE_RINGING.equals(newState)) {
            mPhoneState = TelephonyManager.CALL_STATE_RINGING;
        }
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onPhoneStateChanged(mPhoneState);
            }
        }
    }

    /**
     * Handle {@link #MSG_TIME_UPDATE}
     */
    private void handleTimeUpdate() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTimeChanged();
            }
        }
    }

    /**
     * Handle (@line #MSG_TIMEZONE_UPDATE}
     */
    private void handleTimeZoneUpdate(String timeZone) {
        Assert.isMainThread();
        mLogger.d("handleTimeZoneUpdate");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTimeZoneChanged(TimeZone.getTimeZone(timeZone));
                // Also notify callbacks about time change to remain compatible.
                cb.onTimeChanged();
            }
        }
    }

    /**
     * Handle (@line #MSG_TIME_FORMAT_UPDATE}
     *
     * @param timeFormat "12" for 12-hour format, "24" for 24-hour format
     */
    private void handleTimeFormatUpdate(String timeFormat) {
        Assert.isMainThread();
        mLogger.logTimeFormatChanged(timeFormat);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTimeFormatChanged(timeFormat);
            }
        }
    }

    /**
     * @param data the weather data (temp, conditions, unit) for weather clock to use
     */
    public void sendWeatherData(WeatherData data) {
        mHandler.post(()-> {
            handleWeatherDataUpdate(data); });
    }

    private void handleWeatherDataUpdate(WeatherData data) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onWeatherDataChanged(data);
            }
        }
    }

    /**
     * Handle {@link #MSG_BATTERY_UPDATE}
     */
    private void handleBatteryUpdate(BatteryStatus status) {
        Assert.isMainThread();
        final boolean batteryUpdateInteresting = isBatteryUpdateInteresting(mBatteryStatus, status);
        mBatteryStatus = status;
        if (batteryUpdateInteresting) {
            mLogger.logHandleBatteryUpdate(mBatteryStatus);
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onRefreshBatteryInfo(status);
                }
            }
        }
    }

    /**
     * Handle Telephony status during Boot for CarrierText display policy
     */
    @VisibleForTesting
    void updateTelephonyCapable(boolean capable) {
        Assert.isMainThread();
        if (capable == mTelephonyCapable) {
            return;
        }
        mTelephonyCapable = capable;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTelephonyCapable(mTelephonyCapable);
            }
        }
    }

    /**
     * Handle {@link #MSG_SIM_STATE_CHANGE}
     */
    @VisibleForTesting
    void handleSimStateChange(int subId, int slotId, int state) {
        Assert.isMainThread();
        mLogger.logSimState(subId, slotId, state);

        boolean becameAbsent = false;
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            mLogger.w("invalid subId in handleSimStateChange()");
            /* Only handle No SIM(ABSENT) and Card Error(CARD_IO_ERROR) due to
             * handleServiceStateChange() handle other case */
            if (state == TelephonyManager.SIM_STATE_ABSENT) {
                updateTelephonyCapable(true);
                // Even though the subscription is not valid anymore, we need to notify that the
                // SIM card was removed so we can update the UI.
                becameAbsent = true;
                for (SimData data : mSimDatas.values()) {
                    // Set the SIM state of all SimData associated with that slot to ABSENT se we
                    // do not move back into PIN/PUK locked and not detect the change below.
                    if (data.slotId == slotId) {
                        data.simState = TelephonyManager.SIM_STATE_ABSENT;
                    }
                }
            } else if (state == TelephonyManager.SIM_STATE_CARD_IO_ERROR) {
                updateTelephonyCapable(true);
            }
        }

        becameAbsent |= ABSENT_SIM_STATE_LIST.contains(state);

        SimData data = mSimDatas.get(subId);
        final boolean changed;
        if (data == null) {
            data = new SimData(state, slotId, subId);
            mSimDatas.put(subId, data);
            changed = true; // no data yet; force update
        } else {
            changed = (data.simState != state || data.subId != subId || data.slotId != slotId);
            data.simState = state;
            data.subId = subId;
            data.slotId = slotId;
        }
        if ((changed || becameAbsent)) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onSimStateChanged(subId, slotId, state);
                }
            }
        }
    }

    /**
     * Handle {@link #MSG_SERVICE_STATE_CHANGE}
     */
    @VisibleForTesting
    void handleServiceStateChange(int subId, ServiceState serviceState) {
        mLogger.logServiceStateChange(subId, serviceState);

        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            mLogger.w("invalid subId in handleServiceStateChange()");
            return;
        } else {
            updateTelephonyCapable(true);
        }

        mServiceStates.put(subId, serviceState);

        callbacksRefreshCarrierInfo();
    }

    /**
     * Handle {@link #MSG_SERVICE_PROVIDERS_UPDATED}
     */
    private void handleServiceProvidersUpdated(Intent intent) {
        mLogger.logServiceProvidersUpdated(intent);
        callbacksRefreshCarrierInfo();
    }

    /**
     * Whether the keyguard is showing and not occluded.
     */
    public boolean isKeyguardVisible() {
        return mKeyguardShowing && !mKeyguardOccluded;
    }

    /**
     * Handle {@link #MSG_KEYGUARD_RESET}
     */
    @VisibleForTesting
    protected void handleKeyguardReset() {
        mLogger.d("handleKeyguardReset");
        updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        mNeedsSlowUnlockTransition = resolveNeedsSlowUnlockTransition();
    }

    private boolean resolveNeedsSlowUnlockTransition() {
        if (isUserUnlocked(mSelectedUserInteractor.getSelectedUserId())) {
            return false;
        }
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = mPackageManager.resolveActivityAsUser(homeIntent,
                0 /* flags */, mSelectedUserInteractor.getSelectedUserId());

        if (resolveInfo == null) {
            mLogger.w("resolveNeedsSlowUnlockTransition: returning false since activity could "
                            + "not be resolved.");
            return false;
        }

        return FALLBACK_HOME_COMPONENT.equals(resolveInfo.getComponentInfo().getComponentName());
    }

    /**
     * Handle {@link #MSG_KEYGUARD_BOUNCER_CHANGED}
     *
     * @see #sendPrimaryBouncerChanged(boolean, boolean)
     */
    private void handlePrimaryBouncerChanged(int primaryBouncerIsOrWillBeShowing,
            int primaryBouncerFullyShown) {
        Assert.isMainThread();
        final boolean wasPrimaryBouncerIsOrWillBeShowing = mPrimaryBouncerIsOrWillBeShowing;
        final boolean wasPrimaryBouncerFullyShown = mPrimaryBouncerFullyShown;
        mPrimaryBouncerIsOrWillBeShowing = primaryBouncerIsOrWillBeShowing == 1;
        mPrimaryBouncerFullyShown = primaryBouncerFullyShown == 1;
        mLogger.logPrimaryKeyguardBouncerChanged(mPrimaryBouncerIsOrWillBeShowing,
                mPrimaryBouncerFullyShown);

        if (mPrimaryBouncerFullyShown) {
            // If the bouncer is shown, always clear this flag. This can happen in the following
            // situations: 1) Default camera with SHOW_WHEN_LOCKED is not chosen yet. 2) Secure
            // camera requests dismiss keyguard (tapping on photos for example). When these happen,
            // face auth should resume.
            mSecureCameraLaunched = false;
        } else {
            mCredentialAttempted = false;
        }

        if (wasPrimaryBouncerIsOrWillBeShowing != mPrimaryBouncerIsOrWillBeShowing) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onKeyguardBouncerStateChanged(mPrimaryBouncerIsOrWillBeShowing);
                }
            }
            updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
        }

        if (wasPrimaryBouncerFullyShown != mPrimaryBouncerFullyShown) {
            if (mPrimaryBouncerFullyShown) {
                requestActiveUnlock(
                        ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT,
                        "bouncerFullyShown");
            }
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onKeyguardBouncerFullyShowingChanged(mPrimaryBouncerFullyShown);
                }
            }
        }
    }

    /**
     * Handle {@link #MSG_REQUIRE_NFC_UNLOCK}
     */
    private void handleRequireUnlockForNfc() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onRequireUnlockForNfc();
            }
        }
    }

    /**
     * Handle {@link #MSG_KEYGUARD_DISMISS_ANIMATION_FINISHED}
     */
    private void handleKeyguardDismissAnimationFinished() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onKeyguardDismissAnimationFinished();
            }
        }
    }

    /**
     * Handle {@link #MSG_REPORT_EMERGENCY_CALL_ACTION}
     */
    private void handleReportEmergencyCallAction() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onEmergencyCallAction();
            }
        }
    }

    private boolean isBatteryUpdateInteresting(BatteryStatus old, BatteryStatus current) {
        final boolean nowPluggedIn = current.isPluggedIn();
        final boolean wasPluggedIn = old.isPluggedIn();
        final boolean stateChangedWhilePluggedIn = wasPluggedIn && nowPluggedIn
                && (old.status != current.status);

        // change in plug state is always interesting
        if (wasPluggedIn != nowPluggedIn || stateChangedWhilePluggedIn) {
            return true;
        }

        // change in battery level
        if (old.level != current.level) {
            return true;
        }

        // change in charging current while plugged in
        if (nowPluggedIn && current.maxChargingWattage != old.maxChargingWattage) {
            return true;
        }

        // change in battery is present or not
        if (old.present != current.present) {
            return true;
        }

        // change in the incompatible charger
        if (!old.incompatibleCharger.equals(current.incompatibleCharger)) {
            return true;
        }

        // change in charging status
        return current.chargingStatus != old.chargingStatus;
    }

    /**
     * Remove the given observer's callback.
     *
     * @param callback The callback to remove
     */
    public void removeCallback(KeyguardUpdateMonitorCallback callback) {
        Assert.isMainThread();
        mLogger.logUnregisterCallback(callback);

        mCallbacks.removeIf(el -> el.get() == callback);
    }

    /**
     * Register to receive notifications about general keyguard information
     * (see {@link KeyguardUpdateMonitorCallback}.
     *
     * @param callback The callback to register. Stay away from passing anonymous instances
     *                 as they will likely be dereferenced. Ensure that the callback is a class
     *                 field to persist it.
     */
    public void registerCallback(KeyguardUpdateMonitorCallback callback) {
        Assert.isMainThread();
        mLogger.logRegisterCallback(callback);
        // Prevent adding duplicate callbacks

        for (int i = 0; i < mCallbacks.size(); i++) {
            if (mCallbacks.get(i).get() == callback) {
                mLogger.logException(
                        new Exception("Called by"),
                        "Object tried to add another callback");
                return;
            }
        }
        mCallbacks.add(new WeakReference<>(callback));
        removeCallback(null); // remove unused references
        sendUpdates(callback);
    }

    public void setKeyguardBypassController(KeyguardBypassController keyguardBypassController) {
        mKeyguardBypassController = keyguardBypassController;
    }

    public boolean isSwitchingUser() {
        return mSwitchingUser;
    }

    @AnyThread
    public void setSwitchingUser(boolean switching) {
        if (switching) {
            mLogger.logUserSwitching(
                    mSelectedUserInteractor.getSelectedUserId(), "from setSwitchingUser");
        } else {
            mLogger.logUserSwitchComplete(
                    mSelectedUserInteractor.getSelectedUserId(), "from setSwitchingUser");
        }
        mSwitchingUser = switching;
        // Since this comes in on a binder thread, we need to post it first
        mHandler.post(() -> updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE));
    }

    private void sendUpdates(KeyguardUpdateMonitorCallback callback) {
        // Notify listener of the current state
        callback.onRefreshBatteryInfo(mBatteryStatus);
        callback.onTimeChanged();
        callback.onPhoneStateChanged(mPhoneState);
        callback.onRefreshCarrierInfo();
        callback.onKeyguardVisibilityChanged(isKeyguardVisible());
        callback.onTelephonyCapable(mTelephonyCapable);

        for (Entry<Integer, SimData> data : mSimDatas.entrySet()) {
            final SimData state = data.getValue();
            callback.onSimStateChanged(state.subId, state.slotId, state.simState);
        }
    }

    public void sendKeyguardReset() {
        mHandler.obtainMessage(MSG_KEYGUARD_RESET).sendToTarget();
    }

    /**
     * @see #handlePrimaryBouncerChanged(int, int)
     */
    public void sendPrimaryBouncerChanged(boolean primaryBouncerIsOrWillBeShowing,
            boolean primaryBouncerFullyShown) {
        mLogger.logSendPrimaryBouncerChanged(primaryBouncerIsOrWillBeShowing,
                primaryBouncerFullyShown);
        Message message = mHandler.obtainMessage(MSG_KEYGUARD_BOUNCER_CHANGED);
        message.arg1 = primaryBouncerIsOrWillBeShowing ? 1 : 0;
        message.arg2 = primaryBouncerFullyShown ? 1 : 0;
        message.sendToTarget();
    }

    /**
     * Report that the user successfully entered the SIM PIN or PUK/SIM PIN so we
     * have the information earlier than waiting for the intent
     * broadcast from the telephony code.
     *
     * NOTE: Because handleSimStateChange() invokes callbacks immediately without going
     * through mHandler, this *must* be called from the UI thread.
     */
    @MainThread
    public void reportSimUnlocked(int subId) {
        mLogger.logSimUnlocked(subId);
        handleSimStateChange(subId, getSlotId(subId), TelephonyManager.SIM_STATE_READY);
    }

    /**
     * Report that the emergency call button has been pressed and the emergency dialer is
     * about to be displayed.
     *
     * @param bypassHandler runs immediately.
     *
     *                      NOTE: Must be called from UI thread if bypassHandler == true.
     */
    public void reportEmergencyCallAction(boolean bypassHandler) {
        if (!bypassHandler) {
            mHandler.obtainMessage(MSG_REPORT_EMERGENCY_CALL_ACTION).sendToTarget();
        } else {
            Assert.isMainThread();
            handleReportEmergencyCallAction();
        }
    }

    /**
     * @return Whether the device is provisioned (whether they have gone through
     * the setup wizard)
     */
    public boolean isDeviceProvisioned() {
        return mDeviceProvisioned;
    }

    public ServiceState getServiceState(int subId) {
        return mServiceStates.get(subId);
    }

    /**
     * Resets the fingerprint authenticated state to false.
     */
    public void clearFingerprintRecognized() {
        clearFingerprintRecognized(UserHandle.USER_NULL);
    }

    /**
     * Resets the fingerprint authenticated state to false.
     */
    public void clearFingerprintRecognizedWhenKeyguardDone(int unlockedUser) {
        clearFingerprintRecognized(unlockedUser);
    }

    private void clearFingerprintRecognized(int unlockedUser) {
        Assert.isMainThread();
        mUserFingerprintAuthenticated.clear();
        mTrustManager.clearAllBiometricRecognized(FINGERPRINT, unlockedUser);
        mLogger.d("clearFingerprintRecognized");

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onFingerprintsCleared();
            }
        }
    }

    private void setForceIsDismissibleKeyguard(boolean forceIsDismissible) {
        Assert.isMainThread();
        if (!mFoldGracePeriodProvider.isEnabled()) {
            // never send updates if the feature isn't enabled
            return;
        }
        if (mKeyguardShowing && forceIsDismissible) {
            // never keep the device unlocked if the keyguard was already showing
            mLogger.d("Skip setting forceIsDismissibleKeyguard to true. "
                    + "Keyguard already showing.");
            return;
        }
        if (mForceIsDismissible != forceIsDismissible) {
            mForceIsDismissible = forceIsDismissible;
            mLogger.logForceIsDismissibleKeyguard(mForceIsDismissible);
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onForceIsDismissibleChanged(forceIsDismissibleIsKeepingDeviceUnlocked());
                }
            }
        }

    }

    public boolean isSimPinVoiceSecure() {
        // TODO: only count SIMs that handle voice
        return isSimPinSecure();
    }

    /**
     * If any SIM cards are currently secure.
     *
     * @see #isSimPinSecure(int)
     */
    public boolean isSimPinSecure() {
        // True if any SIM is pin secure
        for (SubscriptionInfo info : getSubscriptionInfo(false /* forceReload */)) {
            if (isSimPinSecure(getSimState(info.getSubscriptionId()))) return true;
        }
        return false;
    }

    public int getSimState(int subId) {
        if (mSimDatas.containsKey(subId)) {
            return mSimDatas.get(subId).simState;
        } else {
            return TelephonyManager.SIM_STATE_UNKNOWN;
        }
    }

    private int getSlotId(int subId) {
        if (!mSimDatas.containsKey(subId)) {
            refreshSimState(subId, SubscriptionManager.getSlotIndex(subId));
        }
        return mSimDatas.get(subId).slotId;
    }

    private final TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChangedBackground() {
            try {
                RootTaskInfo standardTask = mActivityTaskManager.getRootTaskInfo(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
                final boolean previousState = mAllowFingerprintOnCurrentOccludingActivity;
                mAllowFingerprintOnCurrentOccludingActivity =
                        standardTask != null && standardTask.topActivity != null
                                && !TextUtils.isEmpty(standardTask.topActivity.getPackageName())
                                && mAllowFingerprintOnOccludingActivitiesFromPackage.contains(
                                        standardTask.topActivity.getPackageName())
                                && standardTask.visible;
                if (mAllowFingerprintOnCurrentOccludingActivity != previousState) {
                    mLogger.allowFingerprintOnCurrentOccludingActivityChanged(
                            mAllowFingerprintOnCurrentOccludingActivity);
                    updateFingerprintListeningState(BIOMETRIC_ACTION_UPDATE);
                }

                RootTaskInfo assistantTask = mActivityTaskManager.getRootTaskInfo(
                        WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_ASSISTANT);
                if (assistantTask == null) {
                    return;
                }
                mLogger.logTaskStackChangedForAssistant(assistantTask.visible);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_ASSISTANT_STACK_CHANGED,
                        assistantTask.visible));
            } catch (RemoteException e) {
                mLogger.logException(e, "unable to check task stack ");
            }
        }
    };

    /**
     * @return true if and only if the state has changed for the specified {@code slotId}
     */
    private boolean refreshSimState(int subId, int slotId) {
        int state = mTelephonyManager.getSimState(slotId);
        SimData data = mSimDatas.get(subId);
        final boolean changed;
        if (data == null) {
            data = new SimData(state, slotId, subId);
            mSimDatas.put(subId, data);
            changed = true; // no data yet; force update
        } else {
            changed = data.simState != state;
            data.simState = state;
        }
        return changed;
    }

    /**
     * If the {@code state} is currently requiring a SIM PIN, PUK, or is disabled.
     */
    public static boolean isSimPinSecure(int state) {
        return (state == TelephonyManager.SIM_STATE_PIN_REQUIRED
                || state == TelephonyManager.SIM_STATE_PUK_REQUIRED
                || state == TelephonyManager.SIM_STATE_PERM_DISABLED);
    }

    // TODO: use these callbacks elsewhere in place of the existing notifyScreen*()
    // (KeyguardViewMediator, KeyguardSecurityContainer)
    /**
     * Dispatch wakeup events to:
     *  - update biometric listening states
     *  - send to registered KeyguardUpdateMonitorCallbacks
     */
    public void dispatchStartedWakingUp(@PowerManager.WakeReason int pmWakeReason) {
        synchronized (this) {
            mDeviceInteractive = true;
        }
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STARTED_WAKING_UP, pmWakeReason, 0));
    }

    public void dispatchStartedGoingToSleep(int why) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STARTED_GOING_TO_SLEEP, why, 0));
    }

    public void dispatchFinishedGoingToSleep(int why) {
        synchronized (this) {
            mDeviceInteractive = false;
        }
        mHandler.sendMessage(mHandler.obtainMessage(MSG_FINISHED_GOING_TO_SLEEP, why, 0));
    }

    public void dispatchScreenTurnedOff() {
        mHandler.sendEmptyMessage(MSG_SCREEN_TURNED_OFF);
    }

    public void dispatchDreamingStarted() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_DREAMING_STATE_CHANGED, 1, 0));
    }

    public void dispatchDreamingStopped() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_DREAMING_STATE_CHANGED, 0, 0));
    }

    /**
     * Sends a message to update the keyguard going away state on the main thread.
     *
     * @param goingAway Whether the keyguard is going away.
     */
    public void dispatchKeyguardGoingAway(boolean goingAway) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_KEYGUARD_GOING_AWAY, goingAway));
    }

    /**
     * Sends a message to notify the keyguard dismiss animation is finished.
     */
    public void dispatchKeyguardDismissAnimationFinished() {
        mHandler.sendEmptyMessage(MSG_KEYGUARD_DISMISS_ANIMATION_FINISHED);
    }

    /**
     * @return true when the screen is on (including when a screensaver is showing),
     * false when the screen is OFF or DOZE (including showing AOD UI)
     */
    public boolean isDeviceInteractive() {
        return mDeviceInteractive;
    }

    public boolean isGoingToSleep() {
        return mGoingToSleep;
    }

    /**
     * Find the next SubscriptionId for a SIM in the given state, favoring lower slot numbers first.
     *
     * @return subid or {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} if none found
     */
    public int getNextSubIdForState(int state) {
        List<SubscriptionInfo> list = getSubscriptionInfo(false /* forceReload */);
        int resultId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        int bestSlotId = Integer.MAX_VALUE; // Favor lowest slot first
        for (int i = 0; i < list.size(); i++) {
            final SubscriptionInfo info = list.get(i);
            final int id = info.getSubscriptionId();
            int slotId = getSlotId(id);
            if (state == getSimState(id) && bestSlotId > slotId) {
                resultId = id;
                bestSlotId = slotId;
            }
        }
        return resultId;
    }

    public SubscriptionInfo getSubscriptionInfoForSubId(int subId) {
        List<SubscriptionInfo> list = getSubscriptionInfo(false /* forceReload */);
        for (int i = 0; i < list.size(); i++) {
            SubscriptionInfo info = list.get(i);
            if (subId == info.getSubscriptionId()) return info;
        }
        return null; // not found
    }

    /**
     * @return a cached version of DevicePolicyManager.isLogoutEnabled()
     */
    public boolean isLogoutEnabled() {
        return mLogoutEnabled;
    }

    private void updateLogoutEnabled() {
        Assert.isMainThread();
        boolean logoutEnabled = mDevicePolicyManager.isLogoutEnabled();
        if (mLogoutEnabled != logoutEnabled) {
            mLogoutEnabled = logoutEnabled;

            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onLogoutEnabledChanged();
                }
            }
        }
    }

    protected int getBiometricLockoutDelay() {
        return BIOMETRIC_LOCKOUT_RESET_DELAY_MS;
    }

    @VisibleForTesting
    protected boolean isFingerprintClass3() {
        // This assumes that there is at most one fingerprint sensor property
        return isFingerprintSupported() && isClass3Biometric(mFingerprintSensorProperties.get(0));
    }

    private boolean isClass3Biometric(SensorPropertiesInternal sensorProperties) {
        return sensorProperties.sensorStrength == SensorProperties.STRENGTH_STRONG;
    }

    /**
     * Unregister all listeners.
     */
    public void destroy() {
        mStatusBarStateController.removeCallback(mStatusBarStateControllerListener);
        mTelephonyListenerManager.removeActiveDataSubscriptionIdListener(mPhoneStateListener);
        mSubscriptionManager.removeOnSubscriptionsChangedListener(mSubscriptionListener);
        if (getFaceAuthInteractor() != null) {
            getFaceAuthInteractor().unregisterListener(mFaceAuthenticationListener);
        }

        if (mDeviceProvisionedObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mDeviceProvisionedObserver);
        }

        if (mTimeFormatChangeObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mTimeFormatChangeObserver);
        }

        mUserTracker.removeCallback(mUserChangedCallback);

        mTaskStackChangeListeners.unregisterTaskStackListener(mTaskStackListener);

        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        mBroadcastDispatcher.unregisterReceiver(mBroadcastAllReceiver);

        mLockPatternUtils.unregisterStrongAuthTracker(mStrongAuthTracker);
        mTrustManager.unregisterTrustListener(this);

        mHandler.removeCallbacksAndMessages(null);
    }

    @SuppressLint("MissingPermission")
    @NeverCompile
    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("KeyguardUpdateMonitor state:");
        pw.println("  forceIsDismissible=" + mForceIsDismissible);
        pw.println("  forceIsDismissibleIsKeepingDeviceUnlocked="
                + forceIsDismissibleIsKeepingDeviceUnlocked());
        pw.println("  getUserHasTrust()=" + getUserHasTrust(
                mSelectedUserInteractor.getSelectedUserId()));
        pw.println("  getUserUnlockedWithBiometric()="
                + getUserUnlockedWithBiometric(mSelectedUserInteractor.getSelectedUserId()));
        pw.println("  mFingerprintDetectRunning=" + mFingerprintDetectRunning);
        pw.println("  SIM States:");
        for (SimData data : mSimDatas.values()) {
            pw.println("    " + data.toString());
        }
        pw.println("  Subs:");
        if (mSubscriptionInfo != null) {
            for (int i = 0; i < mSubscriptionInfo.size(); i++) {
                pw.println("    " + mSubscriptionInfo.get(i));
            }
        }
        pw.println("  Current active data subId=" + mActiveMobileDataSubscription);
        pw.println("  Service states:");
        for (int subId : mServiceStates.keySet()) {
            pw.println("    " + subId + "=" + mServiceStates.get(subId));
        }
        if (isFingerprintSupported()) {
            final int userId = mSelectedUserInteractor.getSelectedUserId(true);
            final int strongAuthFlags = mStrongAuthTracker.getStrongAuthForUser(userId);
            BiometricAuthenticated fingerprint = mUserFingerprintAuthenticated.get(userId);
            pw.println("  Fingerprint state (user=" + userId + ")");
            pw.println("    isFingerprintClass3=" + isFingerprintClass3());
            pw.println("    areAllFpAuthenticatorsRegistered="
                    + mAuthController.areAllFingerprintAuthenticatorsRegistered());
            pw.println("    allowed="
                    + (fingerprint != null
                            && isUnlockingWithBiometricAllowed(fingerprint.mIsStrongBiometric)));
            pw.println("    auth'd=" + (fingerprint != null && fingerprint.mAuthenticated));
            pw.println("    authSinceBoot="
                    + getStrongAuthTracker().hasUserAuthenticatedSinceBoot());
            pw.println("    disabled(DPM)=" + isFingerprintDisabled(userId));
            pw.println("    possible=" + isUnlockWithFingerprintPossible(userId));
            pw.println("    listening: actual=" + mFingerprintRunningState
                    + " expected=" + (shouldListenForFingerprint(isUdfpsEnrolled()) ? 1 : 0));
            pw.println("    strongAuthFlags=" + Integer.toHexString(strongAuthFlags));
            pw.println("    trustManaged=" + getUserTrustIsManaged(userId));
            pw.println("    mFingerprintLockedOut=" + mFingerprintLockedOut);
            pw.println("    mFingerprintLockedOutPermanent=" + mFingerprintLockedOutPermanent);
            pw.println("    enabledByUser=" + mBiometricEnabledForUser.get(userId));
            pw.println("    mKeyguardOccluded=" + mKeyguardOccluded);
            pw.println("    mIsDreaming=" + mIsDreaming);
            pw.println("    mFingerprintListenOnOccludingActivitiesFromPackage="
                    + mAllowFingerprintOnOccludingActivitiesFromPackage);
            if (isUdfpsSupported()) {
                pw.println("        udfpsEnrolled=" + isUdfpsEnrolled());
                pw.println("        shouldListenForUdfps=" + shouldListenForFingerprint(true));
                pw.println("        mPrimaryBouncerIsOrWillBeShowing="
                        + mPrimaryBouncerIsOrWillBeShowing);
                pw.println("        mStatusBarState=" + StatusBarState.toString(mStatusBarState));
                pw.println("        mAlternateBouncerShowing=" + mAlternateBouncerShowing);
            } else if (isSfpsSupported()) {
                pw.println("        sfpsEnrolled=" + isSfpsEnrolled());
                pw.println("        shouldListenForSfps=" + shouldListenForFingerprint(false));
            }
            new DumpsysTableLogger(
                    "KeyguardFingerprintListen",
                    KeyguardFingerprintListenModel.TABLE_HEADERS,
                    mFingerprintListenBuffer.toList()
            ).printTableData(pw);
        } else if (mFpm != null && mFingerprintSensorProperties.isEmpty()) {
            final int userId = mSelectedUserInteractor.getSelectedUserId(true);
            pw.println("  Fingerprint state (user=" + userId + ")");
            pw.println("    mFingerprintSensorProperties.isEmpty="
                    + mFingerprintSensorProperties.isEmpty());
            pw.println("    mFpm.isHardwareDetected="
                    + mFpm.isHardwareDetected());

            new DumpsysTableLogger(
                    "KeyguardFingerprintListen",
                    KeyguardFingerprintListenModel.TABLE_HEADERS,
                    mFingerprintListenBuffer.toList()
            ).printTableData(pw);
        }
        final int userId = mSelectedUserInteractor.getSelectedUserId(true);
        final int strongAuthFlags = mStrongAuthTracker.getStrongAuthForUser(userId);
        pw.println("    authSinceBoot="
                + getStrongAuthTracker().hasUserAuthenticatedSinceBoot());
        pw.println("    strongAuthFlags=" + Integer.toHexString(strongAuthFlags));
        pw.println("ActiveUnlockRunning="
                + mTrustManager.isActiveUnlockRunning(mSelectedUserInteractor.getSelectedUserId()));
        new DumpsysTableLogger(
                "KeyguardActiveUnlockTriggers",
                KeyguardActiveUnlockModel.TABLE_HEADERS,
                mActiveUnlockTriggerBuffer.toList()
        ).printTableData(pw);
    }

    /**
     * Schedules a watchdog for the face and fingerprint BiometricScheduler.
     * Cancels all operations in the scheduler if it is hung for 10 seconds.
     */
    public void startBiometricWatchdog() {
        mBackgroundExecutor.execute(() -> {
            Trace.beginSection("#startBiometricWatchdog");
            if (mFpm != null) {
                mLogger.scheduleWatchdog("fingerprint");
                mFpm.scheduleWatchdog();
            }
            Trace.endSection();
        });
    }
}
