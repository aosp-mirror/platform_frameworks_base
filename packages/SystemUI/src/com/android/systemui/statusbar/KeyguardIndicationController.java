/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static android.adaptiveauth.Flags.enableAdaptiveAuth;
import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_FINANCED;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.KEYGUARD_MANAGEMENT_DISCLOSURE;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_START;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_TIMEOUT;
import static android.hardware.biometrics.BiometricSourceType.FACE;
import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_AVAILABLE;
import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_RECOGNIZED;
import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED;
import static com.android.systemui.DejankUtils.whitelistIpcs;
import static com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.IMPORTANT_MSG_MIN_DURATION;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_IS_DISMISSIBLE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_ADAPTIVE_AUTH;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_ALIGNMENT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BATTERY;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_DISCLOSURE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_LOGOUT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_OWNER_INFO;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_TRUST;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_USER_LOCKED;
import static com.android.systemui.keyguard.ScreenLifecycle.SCREEN_ON;
import static com.android.systemui.log.core.LogLevel.ERROR;
import static com.android.systemui.plugins.FalsingManager.LOW_PENALTY;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.hardware.biometrics.BiometricSourceType;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.TrustGrantFlags;
import com.android.keyguard.logging.KeyguardLogger;
import com.android.settingslib.Utils;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.FaceHelpMessageDeferral;
import com.android.systemui.biometrics.FaceHelpMessageDeferralFactory;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.deviceentry.domain.interactor.BiometricMessageInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFingerprintAuthInteractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.KeyguardIndication;
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.util.IndicationHelper;
import com.android.systemui.log.core.LogLevel;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.PrintWriter;
import java.text.NumberFormat;
import java.util.Set;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Controls the indications and error messages shown on the Keyguard
 *
 * On AoD, only one message shows with the following priorities:
 *   1. Biometric
 *   2. Transient
 *   3. Charging alignment
 *   4. Battery information
 *
 * On the lock screen, message rotate through different message types.
 *   See {@link KeyguardIndicationRotateTextViewController.IndicationType} for the list of types.
 */
@SysUISingleton
public class KeyguardIndicationController {

    public static final String TAG = "KeyguardIndication";
    private static final boolean DEBUG_CHARGING_SPEED = false;

    private static final int MSG_SHOW_ACTION_TO_UNLOCK = 1;
    private static final int MSG_RESET_ERROR_MESSAGE_ON_SCREEN_ON = 2;
    private static final long TRANSIENT_BIOMETRIC_ERROR_TIMEOUT = 1300;
    public static final long DEFAULT_MESSAGE_TIME = 3500;
    public static final long DEFAULT_HIDE_DELAY_MS =
            DEFAULT_MESSAGE_TIME + KeyguardIndicationTextView.Y_IN_DURATION;

    private final Context mContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final KeyguardStateController mKeyguardStateController;
    protected final StatusBarStateController mStatusBarStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final AuthController mAuthController;
    private final KeyguardLogger mKeyguardLogger;
    private final UserTracker mUserTracker;
    private final BouncerMessageInteractor mBouncerMessageInteractor;
    private ViewGroup mIndicationArea;
    private KeyguardIndicationTextView mTopIndicationView;
    private KeyguardIndicationTextView mLockScreenIndicationView;
    private final IBatteryStats mBatteryInfo;
    private final SettableWakeLock mWakeLock;
    private final DockManager mDockManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final UserManager mUserManager;
    protected final @Main DelayableExecutor mExecutor;
    protected final @Background DelayableExecutor mBackgroundExecutor;
    private final LockPatternUtils mLockPatternUtils;
    private final FalsingManager mFalsingManager;
    private final KeyguardBypassController mKeyguardBypassController;
    private final AccessibilityManager mAccessibilityManager;
    private final Handler mHandler;
    private final AlternateBouncerInteractor mAlternateBouncerInteractor;

    @VisibleForTesting
    public KeyguardIndicationRotateTextViewController mRotateTextViewController;
    private BroadcastReceiver mBroadcastReceiver;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private KeyguardInteractor mKeyguardInteractor;
    private final BiometricMessageInteractor mBiometricMessageInteractor;
    private DeviceEntryFingerprintAuthInteractor mDeviceEntryFingerprintAuthInteractor;
    private DeviceEntryFaceAuthInteractor mDeviceEntryFaceAuthInteractor;
    private String mPersistentUnlockMessage;
    private String mAlignmentIndication;
    private boolean mForceIsDismissible;
    private CharSequence mTrustGrantedIndication;
    private CharSequence mTransientIndication;
    private CharSequence mTrustAgentErrorMessage;
    private CharSequence mBiometricMessage;
    private CharSequence mBiometricMessageFollowUp;
    private BiometricSourceType mBiometricMessageSource;
    protected ColorStateList mInitialTextColorState;
    private boolean mVisible;
    private boolean mOrganizationOwnedDevice;

    // these all assume the device is plugged in (wired/wireless/docked) AND chargingOrFull:
    protected boolean mPowerPluggedIn;
    protected boolean mPowerPluggedInWired;
    protected boolean mPowerPluggedInWireless;
    protected boolean mPowerPluggedInDock;
    protected int mChargingSpeed;

    private boolean mPowerCharged;
    /** Whether the battery defender is triggered. */
    private boolean mBatteryDefender;
    /** Whether the battery defender is triggered with the device plugged. */
    private boolean mEnableBatteryDefender;
    private boolean mIncompatibleCharger;
    private int mChargingWattage;
    private int mBatteryLevel;
    private boolean mBatteryPresent = true;
    protected long mChargingTimeRemaining;
    private Pair<String, BiometricSourceType> mBiometricErrorMessageToShowOnScreenOn;
    private Set<Integer> mCoExFaceAcquisitionMsgIdsToShow;
    private final FaceHelpMessageDeferral mFaceAcquiredMessageDeferral;
    private boolean mInited;

    private KeyguardUpdateMonitorCallback mUpdateMonitorCallback;

    private boolean mDozing;
    private boolean mIsActiveDreamLockscreenHosted;
    private final ScreenLifecycle mScreenLifecycle;
    @VisibleForTesting
    final Consumer<Boolean> mIsActiveDreamLockscreenHostedCallback =
            (Boolean isLockscreenHosted) -> {
                if (mIsActiveDreamLockscreenHosted == isLockscreenHosted) {
                    return;
                }
                mIsActiveDreamLockscreenHosted = isLockscreenHosted;
                updateDeviceEntryIndication(false);
            };
    @VisibleForTesting
    final Consumer<Set<Integer>> mCoExAcquisitionMsgIdsToShowCallback =
            (Set<Integer> coExFaceAcquisitionMsgIdsToShow) -> mCoExFaceAcquisitionMsgIdsToShow =
                    coExFaceAcquisitionMsgIdsToShow;
    @VisibleForTesting
    final Consumer<Boolean> mIsFingerprintEngagedCallback =
            (Boolean isEngaged) -> {
                if (!isEngaged) {
                    showTrustAgentErrorMessage(mTrustAgentErrorMessage);
                }
            };
    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            mHandler.removeMessages(MSG_RESET_ERROR_MESSAGE_ON_SCREEN_ON);
            if (mBiometricErrorMessageToShowOnScreenOn != null) {
                String followUpMessage = mFaceLockedOutThisAuthSession
                        ? faceLockedOutFollowupMessage() : null;
                showBiometricMessage(
                        mBiometricErrorMessageToShowOnScreenOn.first,
                        followUpMessage,
                        mBiometricErrorMessageToShowOnScreenOn.second
                );
                // We want to keep this message around in case the screen was off
                hideBiometricMessageDelayed(DEFAULT_HIDE_DELAY_MS);
                mBiometricErrorMessageToShowOnScreenOn = null;
            }
        }
    };
    private boolean mFaceLockedOutThisAuthSession;

    // Use AlarmTimeouts to guarantee that the events are handled even if scheduled and
    // triggered while the device is asleep
    private final AlarmTimeout mHideTransientMessageHandler;
    private final AlarmTimeout mHideBiometricMessageHandler;
    private final FeatureFlags mFeatureFlags;
    private final IndicationHelper mIndicationHelper;

    /**
     * Creates a new KeyguardIndicationController and registers callbacks.
     */
    @Inject
    public KeyguardIndicationController(
            Context context,
            @Main Looper mainLooper,
            WakeLock.Builder wakeLockBuilder,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DockManager dockManager,
            BroadcastDispatcher broadcastDispatcher,
            DevicePolicyManager devicePolicyManager,
            IBatteryStats iBatteryStats,
            UserManager userManager,
            @Main DelayableExecutor executor,
            @Background DelayableExecutor bgExecutor,
            FalsingManager falsingManager,
            AuthController authController,
            LockPatternUtils lockPatternUtils,
            ScreenLifecycle screenLifecycle,
            KeyguardBypassController keyguardBypassController,
            AccessibilityManager accessibilityManager,
            FaceHelpMessageDeferralFactory faceHelpMessageDeferral,
            KeyguardLogger keyguardLogger,
            AlternateBouncerInteractor alternateBouncerInteractor,
            AlarmManager alarmManager,
            UserTracker userTracker,
            BouncerMessageInteractor bouncerMessageInteractor,
            FeatureFlags flags,
            IndicationHelper indicationHelper,
            KeyguardInteractor keyguardInteractor,
            BiometricMessageInteractor biometricMessageInteractor,
            DeviceEntryFingerprintAuthInteractor deviceEntryFingerprintAuthInteractor,
            DeviceEntryFaceAuthInteractor deviceEntryFaceAuthInteractor
    ) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mDevicePolicyManager = devicePolicyManager;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDockManager = dockManager;
        mWakeLock = new SettableWakeLock(
                wakeLockBuilder.setTag("Doze:KeyguardIndication").build(), TAG);
        mBatteryInfo = iBatteryStats;
        mUserManager = userManager;
        mExecutor = executor;
        mBackgroundExecutor = bgExecutor;
        mLockPatternUtils = lockPatternUtils;
        mAuthController = authController;
        mFalsingManager = falsingManager;
        mKeyguardBypassController = keyguardBypassController;
        mAccessibilityManager = accessibilityManager;
        mScreenLifecycle = screenLifecycle;
        mKeyguardLogger = keyguardLogger;
        mScreenLifecycle.addObserver(mScreenObserver);
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        mUserTracker = userTracker;
        mBouncerMessageInteractor = bouncerMessageInteractor;
        mFeatureFlags = flags;
        mIndicationHelper = indicationHelper;
        mKeyguardInteractor = keyguardInteractor;
        mBiometricMessageInteractor = biometricMessageInteractor;
        mDeviceEntryFingerprintAuthInteractor = deviceEntryFingerprintAuthInteractor;
        mDeviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor;

        mFaceAcquiredMessageDeferral = faceHelpMessageDeferral.create();

        mHandler = new Handler(mainLooper) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_SHOW_ACTION_TO_UNLOCK) {
                    showActionToUnlock();
                } else if (msg.what == MSG_RESET_ERROR_MESSAGE_ON_SCREEN_ON) {
                    mBiometricErrorMessageToShowOnScreenOn = null;
                }
            }
        };

        mHideTransientMessageHandler = new AlarmTimeout(
                alarmManager,
                this::hideTransientIndication,
                TAG,
                mHandler
        );
        mHideBiometricMessageHandler = new AlarmTimeout(
                alarmManager,
                this::hideBiometricMessage,
                TAG,
                mHandler
        );
    }

    /** Call this after construction to finish setting up the instance. */
    public void init() {
        if (mInited) {
            return;
        }
        mInited = true;

        mDockManager.addAlignmentStateListener(
                alignState -> mHandler.post(() -> handleAlignStateChanged(alignState)));
        mKeyguardUpdateMonitor.registerCallback(getKeyguardCallback());
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);

        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());
    }

    public void setIndicationArea(ViewGroup indicationArea) {
        mIndicationArea = indicationArea;
        mTopIndicationView = indicationArea.findViewById(R.id.keyguard_indication_text);
        mLockScreenIndicationView = indicationArea.findViewById(
                R.id.keyguard_indication_text_bottom);
        mInitialTextColorState = mTopIndicationView != null
                ? mTopIndicationView.getTextColors() : ColorStateList.valueOf(Color.WHITE);
        if (mRotateTextViewController != null) {
            mRotateTextViewController.destroy();
        }
        mRotateTextViewController = new KeyguardIndicationRotateTextViewController(
                mLockScreenIndicationView,
                mExecutor,
                mStatusBarStateController,
                mKeyguardLogger,
                mFeatureFlags
        );
        updateDeviceEntryIndication(false /* animate */);
        updateOrganizedOwnedDevice();
        if (mBroadcastReceiver == null) {
            // Update the disclosure proactively to avoid IPC on the critical path.
            mBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateOrganizedOwnedDevice();
                }
            };
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_USER_REMOVED);
            mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, intentFilter);
        }
        if (mFeatureFlags.isEnabled(LOCKSCREEN_WALLPAPER_DREAM_ENABLED)) {
            collectFlow(mIndicationArea, mKeyguardInteractor.isActiveDreamLockscreenHosted(),
                    mIsActiveDreamLockscreenHostedCallback);
        }

        collectFlow(mIndicationArea,
                mBiometricMessageInteractor.getCoExFaceAcquisitionMsgIdsToShow(),
                mCoExAcquisitionMsgIdsToShowCallback);
        collectFlow(mIndicationArea, mDeviceEntryFingerprintAuthInteractor.isEngaged(),
                mIsFingerprintEngagedCallback);
    }

    /**
     * Cleanup
     */
    public void destroy() {
        mHandler.removeCallbacksAndMessages(null);
        mHideBiometricMessageHandler.cancel();
        mHideTransientMessageHandler.cancel();
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
    }

    private void handleAlignStateChanged(int alignState) {
        String alignmentIndication = "";
        if (alignState == DockManager.ALIGN_STATE_POOR) {
            alignmentIndication =
                    mContext.getResources().getString(R.string.dock_alignment_slow_charging);
        } else if (alignState == DockManager.ALIGN_STATE_TERRIBLE) {
            alignmentIndication =
                    mContext.getResources().getString(R.string.dock_alignment_not_charging);
        }
        if (!alignmentIndication.equals(mAlignmentIndication)) {
            mAlignmentIndication = alignmentIndication;
            updateDeviceEntryIndication(false);
        }
    }

    /**
     * Gets the {@link KeyguardUpdateMonitorCallback} instance associated with this
     * {@link KeyguardIndicationController}.
     *
     * <p>Subclasses may override this method to extend or change the callback behavior by extending
     * the {@link BaseKeyguardCallback}.
     *
     * @return A KeyguardUpdateMonitorCallback. Multiple calls to this method <b>must</b> return the
     * same instance.
     */
    protected KeyguardUpdateMonitorCallback getKeyguardCallback() {
        if (mUpdateMonitorCallback == null) {
            mUpdateMonitorCallback = new BaseKeyguardCallback();
        }
        return mUpdateMonitorCallback;
    }

    private void updateLockScreenIndications(boolean animate, int userId) {
        // update transient messages:
        updateBiometricMessage();
        updateTransient();

        // Update persistent messages. The following methods should only be called if we're on the
        // lock screen:
        updateForceIsDimissibileChanged();
        updateLockScreenDisclosureMsg();
        updateLockScreenOwnerInfo();
        updateLockScreenBatteryMsg(animate);
        updateLockScreenUserLockedMsg(userId);
        updateLockScreenTrustMsg(userId, getTrustGrantedIndication(), getTrustManagedIndication());
        updateLockScreenAlignmentMsg();
        updateLockScreenLogoutView();
        updateLockScreenPersistentUnlockMsg();
        if (enableAdaptiveAuth()) {
            updateLockScreenAdaptiveAuthMsg(userId);
        }
    }

    private void updateOrganizedOwnedDevice() {
        // avoid calling this method since it has an IPC
        mOrganizationOwnedDevice = whitelistIpcs(this::isOrganizationOwnedDevice);
        updateDeviceEntryIndication(false);
    }

    private void updateForceIsDimissibileChanged() {
        if (mForceIsDismissible) {
            mRotateTextViewController.updateIndication(
                    INDICATION_IS_DISMISSIBLE,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getResources().getString(
                                    com.android.systemui.res.R.string.dismissible_keyguard_swipe)
                            )
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    /* updateImmediately */ true);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_IS_DISMISSIBLE);
        }
    }

    private void updateLockScreenDisclosureMsg() {
        if (mOrganizationOwnedDevice) {
            mBackgroundExecutor.execute(() -> {
                final CharSequence organizationName = getOrganizationOwnedDeviceOrganizationName();
                final CharSequence disclosure = getDisclosureText(organizationName);

                mExecutor.execute(() -> {
                    if (mKeyguardStateController.isShowing()) {
                        mRotateTextViewController.updateIndication(
                              INDICATION_TYPE_DISCLOSURE,
                              new KeyguardIndication.Builder()
                                      .setMessage(disclosure)
                                      .setTextColor(mInitialTextColorState)
                                      .build(),
                              /* updateImmediately */ false);
                    }
                });
            });
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_DISCLOSURE);
        }
    }

    private CharSequence getDisclosureText(@Nullable CharSequence organizationName) {
        final Resources packageResources = mContext.getResources();

        // TODO(b/259908270): remove and inline
        boolean isFinanced;
        if (DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                DevicePolicyManager.ADD_ISFINANCED_DEVICE_FLAG,
                DevicePolicyManager.ADD_ISFINANCED_FEVICE_DEFAULT)) {
            isFinanced = mDevicePolicyManager.isFinancedDevice();
        } else {
            isFinanced = mDevicePolicyManager.isDeviceManaged()
                    && mDevicePolicyManager.getDeviceOwnerType(
                    mDevicePolicyManager.getDeviceOwnerComponentOnAnyUser())
                    == DEVICE_OWNER_TYPE_FINANCED;
        }

        if (organizationName == null) {
            return mDevicePolicyManager.getResources().getString(
                    KEYGUARD_MANAGEMENT_DISCLOSURE,
                    () -> packageResources.getString(R.string.do_disclosure_generic));
        } else if (isFinanced) {
            return packageResources.getString(R.string.do_financed_disclosure_with_name,
                    organizationName);
        } else {
            return mDevicePolicyManager.getResources().getString(
                    KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE,
                    () -> packageResources.getString(
                            R.string.do_disclosure_with_name, organizationName),
                    organizationName);
        }
    }

    private int getCurrentUser() {
        return mUserTracker.getUserId();
    }

    private void updateLockScreenOwnerInfo() {
        // Check device owner info on a bg thread.
        // It makes multiple IPCs that could block the thread it's run on.
        mBackgroundExecutor.execute(() -> {
            String info = mLockPatternUtils.getDeviceOwnerInfo();
            if (info == null) {
                // Use the current user owner information if enabled.
                final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                        getCurrentUser());
                if (ownerInfoEnabled) {
                    info = mLockPatternUtils.getOwnerInfo(getCurrentUser());
                }
            }

            // Update the UI on the main thread.
            final String finalInfo = info;
            mExecutor.execute(() -> {
                if (!TextUtils.isEmpty(finalInfo) && mKeyguardStateController.isShowing()) {
                    mRotateTextViewController.updateIndication(
                            INDICATION_TYPE_OWNER_INFO,
                            new KeyguardIndication.Builder()
                                    .setMessage(finalInfo)
                                    .setTextColor(mInitialTextColorState)
                                    .build(),
                            false);
                } else {
                    mRotateTextViewController.hideIndication(INDICATION_TYPE_OWNER_INFO);
                }
            });
        });
    }

    private void updateLockScreenBatteryMsg(boolean animate) {
        if (mPowerPluggedIn || mEnableBatteryDefender) {
            String powerIndication = computePowerIndication();
            if (DEBUG_CHARGING_SPEED) {
                powerIndication += ",  " + (mChargingWattage / 1000) + " mW";
            }

            mKeyguardLogger.logUpdateBatteryIndication(powerIndication, mPowerPluggedIn);
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_BATTERY,
                    new KeyguardIndication.Builder()
                            .setMessage(powerIndication)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    animate);
        } else {
            mKeyguardLogger.log(TAG, LogLevel.DEBUG, "hide battery indication");
            // don't show the charging information if device isn't plugged in
            mRotateTextViewController.hideIndication(INDICATION_TYPE_BATTERY);
        }
    }

    private void updateLockScreenUserLockedMsg(int userId) {
        boolean userUnlocked = mKeyguardUpdateMonitor.isUserUnlocked(userId);
        boolean encryptedOrLockdown = mKeyguardUpdateMonitor.isEncryptedOrLockdown(userId);
        mKeyguardLogger.logUpdateLockScreenUserLockedMsg(userId, userUnlocked, encryptedOrLockdown);
        if (!userUnlocked || encryptedOrLockdown) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_USER_LOCKED,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getResources().getText(
                                    com.android.internal.R.string.lockscreen_storage_locked))
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_USER_LOCKED);
        }
    }

    private void updateBiometricMessage() {
        if (mDozing) {
            updateDeviceEntryIndication(false);
            return;
        }

        if (!TextUtils.isEmpty(mBiometricMessage)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_BIOMETRIC_MESSAGE,
                    new KeyguardIndication.Builder()
                            .setMessage(mBiometricMessage)
                            .setForceAccessibilityLiveRegionAssertive()
                            .setMinVisibilityMillis(IMPORTANT_MSG_MIN_DURATION)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    true
            );
        } else {
            mRotateTextViewController.hideIndication(
                    INDICATION_TYPE_BIOMETRIC_MESSAGE);
        }
        if (!TextUtils.isEmpty(mBiometricMessageFollowUp)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                    new KeyguardIndication.Builder()
                            .setMessage(mBiometricMessageFollowUp)
                            .setMinVisibilityMillis(IMPORTANT_MSG_MIN_DURATION)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    true
            );
        } else {
            mRotateTextViewController.hideIndication(
                    INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP);
        }
    }

    private void updateTransient() {
        if (mDozing) {
            updateDeviceEntryIndication(false);
            return;
        }

        if (!TextUtils.isEmpty(mTransientIndication)) {
            mRotateTextViewController.showTransient(mTransientIndication);
        } else {
            mRotateTextViewController.hideTransient();
        }
    }

    private void updateLockScreenTrustMsg(int userId, CharSequence trustGrantedIndication,
            CharSequence trustManagedIndication) {
        final boolean userHasTrust = mKeyguardUpdateMonitor.getUserHasTrust(userId);
        if (!TextUtils.isEmpty(trustGrantedIndication) && userHasTrust) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_TRUST,
                    new KeyguardIndication.Builder()
                            .setMessage(trustGrantedIndication)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    true);
            hideBiometricMessage();
        } else if (!TextUtils.isEmpty(trustManagedIndication)
                && mKeyguardUpdateMonitor.getUserTrustIsManaged(userId)
                && !userHasTrust) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_TRUST,
                    new KeyguardIndication.Builder()
                            .setMessage(trustManagedIndication)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_TRUST);
        }
    }

    private void updateLockScreenAlignmentMsg() {
        if (!TextUtils.isEmpty(mAlignmentIndication)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_ALIGNMENT,
                    new KeyguardIndication.Builder()
                            .setMessage(mAlignmentIndication)
                            .setTextColor(ColorStateList.valueOf(
                                    mContext.getColor(R.color.misalignment_text_color)))
                            .build(),
                    true);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_ALIGNMENT);
        }
    }

    private void updateLockScreenPersistentUnlockMsg() {
        if (!TextUtils.isEmpty(mPersistentUnlockMessage)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE,
                    new KeyguardIndication.Builder()
                            .setMessage(mPersistentUnlockMessage)
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    true);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE);
        }
    }

    private void updateLockScreenLogoutView() {
        final boolean shouldShowLogout = mKeyguardUpdateMonitor.isLogoutEnabled()
                && getCurrentUser() != UserHandle.USER_SYSTEM;
        if (shouldShowLogout) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_LOGOUT,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getResources().getString(
                                    com.android.internal.R.string.global_action_logout))
                            .setTextColor(Utils.getColorAttr(
                                    mContext, com.android.internal.R.attr.textColorOnAccent))
                            .setBackground(mContext.getDrawable(
                                    com.android.systemui.res.R.drawable.logout_button_background))
                            .setClickListener((view) -> {
                                if (mFalsingManager.isFalseTap(LOW_PENALTY)) {
                                    return;
                                }
                                mDevicePolicyManager.logoutUser();
                            })
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_LOGOUT);
        }
    }

    private void updateLockScreenAdaptiveAuthMsg(int userId) {
        final boolean deviceLocked = mKeyguardUpdateMonitor.isDeviceLockedByAdaptiveAuth(userId);
        if (deviceLocked) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_ADAPTIVE_AUTH,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getString(
                                    R.string.keyguard_indication_after_adaptive_auth_lock))
                            .setTextColor(mInitialTextColorState)
                            .build(),
                    true);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_ADAPTIVE_AUTH);
        }
    }

    private boolean isOrganizationOwnedDevice() {
        return mDevicePolicyManager.isDeviceManaged()
                || mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile();
    }

    @Nullable
    private CharSequence getOrganizationOwnedDeviceOrganizationName() {
        if (mDevicePolicyManager.isDeviceManaged()) {
            return mDevicePolicyManager.getDeviceOwnerOrganizationName();
        } else if (mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile()) {
            return getWorkProfileOrganizationName();
        }
        return null;
    }

    private CharSequence getWorkProfileOrganizationName() {
        final int profileId = getWorkProfileUserId(UserHandle.myUserId());
        if (profileId == UserHandle.USER_NULL) {
            return null;
        }
        return mDevicePolicyManager.getOrganizationNameForUser(profileId);
    }

    private int getWorkProfileUserId(int userId) {
        for (final UserInfo userInfo : mUserManager.getProfiles(userId)) {
            if (userInfo.isManagedProfile()) {
                return userInfo.id;
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * Sets the visibility of keyguard bottom area, and if the indications are updatable.
     *
     * @param visible true to make the area visible and update the indication, false otherwise.
     */
    public void setVisible(boolean visible) {
        mVisible = visible;
        mIndicationArea.setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            // If this is called after an error message was already shown, we should not clear it.
            // Otherwise the error message won't be shown
            if (!mHideTransientMessageHandler.isScheduled()) {
                hideTransientIndication();
            }
            updateDeviceEntryIndication(false);
        } else {
            // If we unlock and return to keyguard quickly, previous error should not be shown
            hideTransientIndication();
        }
    }

    private void setPersistentUnlockMessage(String persistentUnlockMessage) {
        mPersistentUnlockMessage = persistentUnlockMessage;
        updateDeviceEntryIndication(false);
    }

    /**
     * Returns the indication text indicating that trust has been granted.
     *
     * @return an empty string if a trust indication text should not be shown.
     */
    @VisibleForTesting
    String getTrustGrantedIndication() {
        return mTrustGrantedIndication == null
                ? mContext.getString(R.string.keyguard_indication_trust_unlocked)
                : mTrustGrantedIndication.toString();
    }

    /**
     * Sets if the device is plugged in
     */
    @VisibleForTesting
    void setPowerPluggedIn(boolean plugged) {
        mPowerPluggedIn = plugged;
    }

    /**
     * Returns the indication text indicating that trust is currently being managed.
     *
     * @return {@code null} or an empty string if a trust managed text should not be shown.
     */
    private String getTrustManagedIndication() {
        return null;
    }

    /**
     * Hides transient indication in {@param delayMs}.
     */
    public void hideTransientIndicationDelayed(long delayMs) {
        mHideTransientMessageHandler.schedule(delayMs, AlarmTimeout.MODE_RESCHEDULE_IF_SCHEDULED);
    }

    /**
     * Hides biometric indication in {@param delayMs}.
     */
    public void hideBiometricMessageDelayed(long delayMs) {
        mHideBiometricMessageHandler.schedule(delayMs, AlarmTimeout.MODE_RESCHEDULE_IF_SCHEDULED);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(int transientIndication) {
        showTransientIndication(mContext.getResources().getString(transientIndication));
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    private void showTransientIndication(CharSequence transientIndication) {
        mTransientIndication = transientIndication;
        hideTransientIndicationDelayed(DEFAULT_HIDE_DELAY_MS);

        updateTransient();
    }

    private void showSuccessBiometricMessage(
            CharSequence biometricMessage,
            @Nullable CharSequence biometricMessageFollowUp,
            BiometricSourceType biometricSourceType
    ) {
        showBiometricMessage(biometricMessage, biometricMessageFollowUp, biometricSourceType, true);
    }

    private void showSuccessBiometricMessage(CharSequence biometricMessage,
            BiometricSourceType biometricSourceType) {
        showSuccessBiometricMessage(biometricMessage, null, biometricSourceType);
    }

    private void showBiometricMessage(CharSequence biometricMessage,
            BiometricSourceType biometricSourceType) {
        showBiometricMessage(biometricMessage, null, biometricSourceType, false);
    }

    private void showBiometricMessage(
            CharSequence biometricMessage,
            @Nullable CharSequence biometricMessageFollowUp,
            BiometricSourceType biometricSourceType
    ) {
        showBiometricMessage(
                biometricMessage,
                biometricMessageFollowUp,
                biometricSourceType,
                false
        );
    }

    /**
     * Shows {@param biometricMessage} and {@param biometricMessageFollowUp}
     * until they are hidden by {@link #hideBiometricMessage}. Messages are rotated through
     * by {@link KeyguardIndicationRotateTextViewController}, see class for rotating message
     * logic.
     */
    private void showBiometricMessage(
            CharSequence biometricMessage,
            @Nullable CharSequence biometricMessageFollowUp,
            BiometricSourceType biometricSourceType,
            boolean isSuccessMessage
    ) {
        if (TextUtils.equals(biometricMessage, mBiometricMessage)
                && biometricSourceType == mBiometricMessageSource
                && TextUtils.equals(biometricMessageFollowUp, mBiometricMessageFollowUp)) {
            return;
        }

        if (!isSuccessMessage
                && mBiometricMessageSource == FINGERPRINT
                && biometricSourceType == FACE) {
            // drop any face messages if there's a fingerprint message showing
            mKeyguardLogger.logDropFaceMessage(
                    biometricMessage,
                    biometricMessageFollowUp
            );
            return;
        }

        if (mBiometricMessageSource != null && biometricSourceType == null) {
            // If there's a current biometric message showing and a non-biometric message
            // arrives, update the followup message with the non-biometric message.
            // Keep the biometricMessage and biometricMessageSource the same.
            mBiometricMessageFollowUp = biometricMessage;
        } else {
            mBiometricMessage = biometricMessage;
            mBiometricMessageFollowUp = biometricMessageFollowUp;
            mBiometricMessageSource = biometricSourceType;
        }

        mHandler.removeMessages(MSG_SHOW_ACTION_TO_UNLOCK);
        hideBiometricMessageDelayed(
                !TextUtils.isEmpty(mBiometricMessage)
                        && !TextUtils.isEmpty(mBiometricMessageFollowUp)
                        ? IMPORTANT_MSG_MIN_DURATION * 2
                        : DEFAULT_HIDE_DELAY_MS
        );

        updateBiometricMessage();
    }

    private void hideBiometricMessage() {
        if (mBiometricMessage != null || mBiometricMessageFollowUp != null) {
            mBiometricMessage = null;
            mBiometricMessageFollowUp = null;
            mBiometricMessageSource = null;
            mHideBiometricMessageHandler.cancel();
            updateBiometricMessage();
        }
    }

    /**
     * Hides transient indication.
     */
    public void hideTransientIndication() {
        if (mTransientIndication != null) {
            mTransientIndication = null;
            mHideTransientMessageHandler.cancel();
            updateTransient();
        }
    }

    /**
     * Updates message shown to the user. If the device is dozing, a single message with the highest
     * precedence is shown. If the device is not dozing (on the lock screen), then several messages
     * may continuously be cycled through.
     */
    protected final void updateDeviceEntryIndication(boolean animate) {
        mKeyguardLogger.logUpdateDeviceEntryIndication(animate, mVisible, mDozing);
        if (!mVisible) {
            return;
        }

        // Device is dreaming and the dream is hosted in lockscreen
        if (mIsActiveDreamLockscreenHosted) {
            mIndicationArea.setVisibility(GONE);
            return;
        }

        // A few places might need to hide the indication, so always start by making it visible
        mIndicationArea.setVisibility(VISIBLE);

        // Walk down a precedence-ordered list of what indication
        // should be shown based on device state
        if (mDozing) {
            boolean useMisalignmentColor = false;
            mLockScreenIndicationView.setVisibility(View.GONE);
            mTopIndicationView.setVisibility(VISIBLE);
            CharSequence newIndication;
            if (!TextUtils.isEmpty(mBiometricMessage)) {
                newIndication = mBiometricMessage; // note: doesn't show mBiometricMessageFollowUp
            } else if (!TextUtils.isEmpty(mTransientIndication)) {
                newIndication = mTransientIndication;
            } else if (!mBatteryPresent) {
                // If there is no battery detected, hide the indication and bail
                mIndicationArea.setVisibility(GONE);
                return;
            } else if (!TextUtils.isEmpty(mAlignmentIndication)) {
                useMisalignmentColor = true;
                newIndication = mAlignmentIndication;
            } else if (mPowerPluggedIn || mEnableBatteryDefender) {
                newIndication = computePowerIndication();
            } else {
                newIndication = NumberFormat.getPercentInstance()
                        .format(mBatteryLevel / 100f);
            }

            if (!TextUtils.equals(mTopIndicationView.getText(), newIndication)) {
                mWakeLock.setAcquired(true);
                mTopIndicationView.switchIndication(newIndication,
                        new KeyguardIndication.Builder()
                                .setMessage(newIndication)
                                .setTextColor(ColorStateList.valueOf(
                                        useMisalignmentColor
                                                ? mContext.getColor(R.color.misalignment_text_color)
                                                : Color.WHITE))
                                .build(),
                        true, () -> mWakeLock.setAcquired(false));
            }
            return;
        }

        // LOCK SCREEN
        mTopIndicationView.setVisibility(GONE);
        mTopIndicationView.setText(null);
        mLockScreenIndicationView.setVisibility(View.VISIBLE);
        updateLockScreenIndications(animate, getCurrentUser());
    }

    /**
     * Assumption: device is charging
     */
    protected String computePowerIndication() {
        if (mBatteryDefender) {
            String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100f);
            return mContext.getResources().getString(
                    R.string.keyguard_plugged_in_charging_limited, percentage);
        } else if (mPowerPluggedIn && mIncompatibleCharger) {
            String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100f);
            return mContext.getResources().getString(
                    R.string.keyguard_plugged_in_incompatible_charger, percentage);
        } else if (mPowerCharged) {
            return mContext.getResources().getString(R.string.keyguard_charged);
        }

        return computePowerChargingStringIndication();
    }

    protected String computePowerChargingStringIndication() {
        final boolean hasChargingTime = mChargingTimeRemaining > 0;
        int chargingId;
        if (mPowerPluggedInWired) {
            switch (mChargingSpeed) {
                case BatteryStatus.CHARGING_FAST:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_fast
                            : R.string.keyguard_plugged_in_charging_fast;
                    break;
                case BatteryStatus.CHARGING_SLOWLY:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_slowly
                            : R.string.keyguard_plugged_in_charging_slowly;
                    break;
                default:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time
                            : R.string.keyguard_plugged_in;
                    break;
            }
        } else if (mPowerPluggedInWireless) {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time_wireless
                    : R.string.keyguard_plugged_in_wireless;
        } else if (mPowerPluggedInDock) {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time_dock
                    : R.string.keyguard_plugged_in_dock;
        } else {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time
                    : R.string.keyguard_plugged_in;
        }

        String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100f);
        if (hasChargingTime) {
            String chargingTimeFormatted = Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                    mContext, mChargingTimeRemaining);
            return mContext.getResources().getString(chargingId, chargingTimeFormatted,
                    percentage);
        } else {
            return mContext.getResources().getString(chargingId, percentage);
        }
    }

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    /**
     * Show message on the keyguard for how the user can unlock/enter their device.
     */
    public void showActionToUnlock() {
        if (mDozing
                && !mKeyguardUpdateMonitor.getUserCanSkipBouncer(
                        getCurrentUser())) {
            return;
        }

        if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
            if (mAlternateBouncerInteractor.isVisibleState()) {
                return; // udfps affordance is highlighted, no need to show action to unlock
            } else if (mKeyguardUpdateMonitor.isFaceEnabledAndEnrolled()
                    && !mKeyguardUpdateMonitor.getIsFaceAuthenticated()) {
                String message;
                if (mAccessibilityManager.isEnabled()
                        || mAccessibilityManager.isTouchExplorationEnabled()) {
                    message = mContext.getString(R.string.accesssibility_keyguard_retry);
                } else {
                    message = mContext.getString(R.string.keyguard_retry);
                }
                mStatusBarKeyguardViewManager.setKeyguardMessage(message, mInitialTextColorState,
                        null);
            }
        } else {
            final boolean canSkipBouncer = mKeyguardUpdateMonitor.getUserCanSkipBouncer(
                    getCurrentUser());
            if (canSkipBouncer) {
                final boolean faceAuthenticated = mKeyguardUpdateMonitor.getIsFaceAuthenticated();
                final boolean udfpsSupported = mKeyguardUpdateMonitor.isUdfpsSupported();
                final boolean a11yEnabled = mAccessibilityManager.isEnabled()
                        || mAccessibilityManager.isTouchExplorationEnabled();
                if (udfpsSupported && faceAuthenticated) { // co-ex
                    if (a11yEnabled) {
                        showSuccessBiometricMessage(
                                mContext.getString(R.string.keyguard_face_successful_unlock),
                                mContext.getString(R.string.keyguard_unlock),
                                FACE
                        );
                    } else {
                        showSuccessBiometricMessage(
                                mContext.getString(R.string.keyguard_face_successful_unlock),
                                mContext.getString(R.string.keyguard_unlock_press),
                                FACE
                        );
                    }
                } else if (faceAuthenticated) { // face-only
                    showSuccessBiometricMessage(
                            mContext.getString(R.string.keyguard_face_successful_unlock),
                            mContext.getString(R.string.keyguard_unlock),
                            FACE
                    );
                } else if (udfpsSupported) { // udfps-only
                    if (a11yEnabled) {
                        showSuccessBiometricMessage(
                                mContext.getString(R.string.keyguard_unlock),
                                null
                        );
                    } else {
                        showSuccessBiometricMessage(mContext.getString(
                                R.string.keyguard_unlock_press), null);
                    }
                } else { // no security or unlocked by a trust agent
                    showSuccessBiometricMessage(mContext.getString(R.string.keyguard_unlock), null);
                }
            } else {
                // suggest swiping up for the primary authentication bouncer
                showBiometricMessage(mContext.getString(R.string.keyguard_unlock), null);
            }
        }
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardIndicationController:");
        pw.println("  mInitialTextColorState: " + mInitialTextColorState);
        pw.println("  mPowerPluggedInWired: " + mPowerPluggedInWired);
        pw.println("  mPowerPluggedIn: " + mPowerPluggedIn);
        pw.println("  mPowerCharged: " + mPowerCharged);
        pw.println("  mChargingSpeed: " + mChargingSpeed);
        pw.println("  mChargingWattage: " + mChargingWattage);
        pw.println("  mMessageToShowOnScreenOn: " + mBiometricErrorMessageToShowOnScreenOn);
        pw.println("  mDozing: " + mDozing);
        pw.println("  mTransientIndication: " + mTransientIndication);
        pw.println("  mBiometricMessage: " + mBiometricMessage);
        pw.println("  mBiometricMessageFollowUp: " + mBiometricMessageFollowUp);
        pw.println("  mBatteryLevel: " + mBatteryLevel);
        pw.println("  mBatteryPresent: " + mBatteryPresent);
        pw.println("  mIsActiveDreamLockscreenHosted: " + mIsActiveDreamLockscreenHosted);
        pw.println("  AOD text: " + (
                mTopIndicationView == null ? null : mTopIndicationView.getText()));
        pw.println("  computePowerIndication(): " + computePowerIndication());
        pw.println("  trustGrantedIndication: " + getTrustGrantedIndication());
        pw.println("    mCoExFaceHelpMsgIdsToShow=" + mCoExFaceAcquisitionMsgIdsToShow);
        mRotateTextViewController.dump(pw, args);
    }

    protected class BaseKeyguardCallback extends KeyguardUpdateMonitorCallback {
        @Override
        public void onTimeChanged() {
            if (mVisible) {
                updateDeviceEntryIndication(false /* animate */);
            }
        }

        /**
         * KeyguardUpdateMonitor only sends "interesting" battery updates
         * {@link KeyguardUpdateMonitor#isBatteryUpdateInteresting}.
         * Therefore, make sure to always check plugged in state along with any charging status
         * change, or else we could end up with stale state.
         */
        @Override
        public void onRefreshBatteryInfo(BatteryStatus status) {
            boolean isChargingOrFull = status.status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status.isCharged();
            boolean wasPluggedIn = mPowerPluggedIn;
            mPowerPluggedInWired = status.isPluggedInWired() && isChargingOrFull;
            mPowerPluggedInWireless = status.isPluggedInWireless() && isChargingOrFull;
            mPowerPluggedInDock = status.isPluggedInDock() && isChargingOrFull;
            mPowerPluggedIn = status.isPluggedIn() && isChargingOrFull;
            mPowerCharged = status.isCharged();
            mChargingWattage = status.maxChargingWattage;
            mChargingSpeed = status.getChargingSpeed(mContext);
            mBatteryLevel = status.level;
            mBatteryPresent = status.present;
            mBatteryDefender = isBatteryDefender(status);
            // when the battery is overheated, device doesn't charge so only guard on pluggedIn:
            mEnableBatteryDefender = mBatteryDefender && status.isPluggedIn();
            mIncompatibleCharger = status.incompatibleCharger.orElse(false);
            try {
                mChargingTimeRemaining = mPowerPluggedIn
                        ? mBatteryInfo.computeChargeTimeRemaining() : -1;
            } catch (RemoteException e) {
                mKeyguardLogger.log(TAG, ERROR, "Error calling IBatteryStats", e);
                mChargingTimeRemaining = -1;
            }

            mKeyguardLogger.logRefreshBatteryInfo(isChargingOrFull, mPowerPluggedIn, mBatteryLevel,
                    mBatteryDefender);
            updateDeviceEntryIndication(!wasPluggedIn && mPowerPluggedInWired);
        }

        @Override
        public void onBiometricAcquired(BiometricSourceType biometricSourceType, int acquireInfo) {
            if (biometricSourceType == FACE) {
                if (acquireInfo == FACE_ACQUIRED_START) {
                    // Let's hide any previous messages when authentication starts, otherwise
                    // multiple auth attempts would overlap.
                    hideBiometricMessage();
                    mBiometricErrorMessageToShowOnScreenOn = null;
                }
                mFaceAcquiredMessageDeferral.processFrame(acquireInfo);
            }
        }

        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FACE) {
                mFaceAcquiredMessageDeferral.updateMessage(msgId, helpString);
                if (mFaceAcquiredMessageDeferral.shouldDefer(msgId)) {
                    return;
                }
            }

            final boolean faceAuthUnavailable = biometricSourceType == FACE
                    && msgId == BIOMETRIC_HELP_FACE_NOT_AVAILABLE;

            if (isPrimaryAuthRequired()
                    && !faceAuthUnavailable) {
                return;
            }

            final boolean faceAuthSoftError = biometricSourceType == FACE
                    && msgId != BIOMETRIC_HELP_FACE_NOT_RECOGNIZED
                    && msgId != BIOMETRIC_HELP_FACE_NOT_AVAILABLE;
            final boolean faceAuthFailed = biometricSourceType == FACE
                    && msgId == BIOMETRIC_HELP_FACE_NOT_RECOGNIZED; // ran through matcher & failed
            if (faceAuthFailed && mFaceLockedOutThisAuthSession) {
                mKeyguardLogger.logBiometricMessage(
                        "skipped showing faceAuthFailed message due to lockout",
                        msgId,
                        helpString);
                return;
            }
            final boolean fpAuthFailed = biometricSourceType == FINGERPRINT
                    && msgId == BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED; // ran matcher & failed
            final boolean isUnlockWithFingerprintPossible = canUnlockWithFingerprint();
            final boolean isCoExFaceAcquisitionMessage =
                    faceAuthSoftError && isUnlockWithFingerprintPossible;
            if (isCoExFaceAcquisitionMessage && !mCoExFaceAcquisitionMsgIdsToShow.contains(msgId)) {
                mKeyguardLogger.logBiometricMessage(
                        "skipped showing help message due to co-ex logic",
                        msgId,
                        helpString);
            } else if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                if (biometricSourceType == FINGERPRINT && !fpAuthFailed) {
                    mBouncerMessageInteractor.setFingerprintAcquisitionMessage(helpString);
                } else if (faceAuthSoftError) {
                    mBouncerMessageInteractor.setFaceAcquisitionMessage(helpString);
                }
                mStatusBarKeyguardViewManager.setKeyguardMessage(helpString,
                        mInitialTextColorState, biometricSourceType);
            } else if (mScreenLifecycle.getScreenState() == SCREEN_ON) {
                if (isCoExFaceAcquisitionMessage && msgId == FACE_ACQUIRED_TOO_DARK) {
                    showBiometricMessage(
                            helpString,
                            mContext.getString(R.string.keyguard_suggest_fingerprint),
                            biometricSourceType
                    );
                } else if (faceAuthFailed && isUnlockWithFingerprintPossible) {
                    showBiometricMessage(
                            mContext.getString(R.string.keyguard_face_failed),
                            mContext.getString(R.string.keyguard_suggest_fingerprint),
                            biometricSourceType
                    );
                } else if (fpAuthFailed
                        && mKeyguardUpdateMonitor.isCurrentUserUnlockedWithFace()) {
                    // face had already previously unlocked the device, so instead of showing a
                    // fingerprint error, tell them they have already unlocked with face auth
                    // and how to enter their device
                    showSuccessBiometricMessage(
                            mContext.getString(R.string.keyguard_face_successful_unlock),
                            mContext.getString(R.string.keyguard_unlock),
                            null
                    );
                } else if (fpAuthFailed
                        && mKeyguardUpdateMonitor.getUserHasTrust(getCurrentUser())) {
                    showSuccessBiometricMessage(
                            getTrustGrantedIndication(),
                            mContext.getString(R.string.keyguard_unlock),
                            null
                    );
                } else if (faceAuthUnavailable) {
                    showBiometricMessage(
                            helpString,
                            isUnlockWithFingerprintPossible
                                    ? mContext.getString(R.string.keyguard_suggest_fingerprint)
                                    : mContext.getString(R.string.keyguard_unlock),
                            biometricSourceType
                    );
                } else {
                    showBiometricMessage(helpString, biometricSourceType);
                }
            } else if (faceAuthFailed) {
                // show action to unlock
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SHOW_ACTION_TO_UNLOCK),
                        TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
            } else {
                mBiometricErrorMessageToShowOnScreenOn =
                        new Pair<>(helpString, biometricSourceType);
                mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_RESET_ERROR_MESSAGE_ON_SCREEN_ON),
                        1000);
            }
        }

        @Override
        public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FACE) {
                mFaceAcquiredMessageDeferral.reset();
            }
        }

        @Override
        public void onLockedOutStateChanged(BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FACE && !mKeyguardUpdateMonitor.isFaceLockedOut()) {
                mFaceLockedOutThisAuthSession = false;
            } else if (biometricSourceType == FINGERPRINT) {
                setPersistentUnlockMessage(mKeyguardUpdateMonitor.isFingerprintLockedOut()
                        ? mContext.getString(R.string.keyguard_unlock) : "");
            }
        }

        @Override
        public void onBiometricError(int msgId, String errString,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FACE) {
                onFaceAuthError(msgId, errString);
            } else if (biometricSourceType == FINGERPRINT) {
                onFingerprintAuthError(msgId, errString);
            }
        }

        private void onFaceAuthError(int msgId, String errString) {
            CharSequence deferredFaceMessage = mFaceAcquiredMessageDeferral.getDeferredMessage();
            mFaceAcquiredMessageDeferral.reset();
            if (mIndicationHelper.shouldSuppressErrorMsg(FACE, msgId)) {
                mKeyguardLogger.logBiometricMessage("KIC suppressingFaceError", msgId, errString);
                return;
            }
            if (msgId == FACE_ERROR_TIMEOUT) {
                handleFaceAuthTimeoutError(deferredFaceMessage);
            } else if (mIndicationHelper.isFaceLockoutErrorMsg(msgId)) {
                handleFaceLockoutError(errString);
            } else {
                showErrorMessageNowOrLater(errString, null, FACE);
            }
        }

        private void onFingerprintAuthError(int msgId, String errString) {
            if (mIndicationHelper.shouldSuppressErrorMsg(FINGERPRINT, msgId)) {
                mKeyguardLogger.logBiometricMessage("KIC suppressingFingerprintError",
                        msgId,
                        errString);
            } else {
                showErrorMessageNowOrLater(errString, null, FINGERPRINT);
            }
        }

        @Override
        public void onTrustChanged(int userId) {
            if (!isCurrentUser(userId)) return;
            updateDeviceEntryIndication(false);
        }

        @Override
        public void onForceIsDismissibleChanged(boolean forceIsDismissible) {
            mForceIsDismissible = forceIsDismissible;
            updateDeviceEntryIndication(false);
        }

        @Override
        public void onTrustGrantedForCurrentUser(
                boolean dismissKeyguard,
                boolean newlyUnlocked,
                @NonNull TrustGrantFlags flags,
                @Nullable String message
        ) {
            showTrustGrantedMessage(dismissKeyguard, message);
        }

        @Override
        public void onTrustAgentErrorMessage(CharSequence message) {
            showTrustAgentErrorMessage(message);
        }

        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            if (!running && biometricSourceType == FACE) {
                showTrustAgentErrorMessage(mTrustAgentErrorMessage);
            }
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
                boolean isStrongBiometric) {
            super.onBiometricAuthenticated(userId, biometricSourceType, isStrongBiometric);
            hideBiometricMessage();
            if (biometricSourceType == FACE) {
                mFaceAcquiredMessageDeferral.reset();
                if (!mKeyguardBypassController.canBypass()) {
                    showActionToUnlock();
                }
            }
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            if (mVisible) {
                updateDeviceEntryIndication(false);
            }
        }

        @Override
        public void onUserUnlocked() {
            if (mVisible) {
                updateDeviceEntryIndication(false);
            }
        }

        @Override
        public void onLogoutEnabledChanged() {
            if (mVisible) {
                updateDeviceEntryIndication(false);
            }
        }

        @Override
        public void onRequireUnlockForNfc() {
            showTransientIndication(mContext.getString(R.string.require_unlock_for_nfc));
            hideTransientIndicationDelayed(DEFAULT_HIDE_DELAY_MS);
        }
    }

    private boolean isPrimaryAuthRequired() {
        // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
        // as long as primary auth, i.e. PIN/pattern/password, is required), so it's ok to
        // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
        // check of whether non-strong biometric is allowed since strong biometrics can still be
        // used.
        return !mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                true /* isStrongBiometric */);
    }

    protected boolean isPluggedInAndCharging() {
        return mPowerPluggedIn;
    }

    /** Return true if the device is under the battery defender mode. */
    protected boolean isBatteryDefender(BatteryStatus status) {
        return status.isBatteryDefender();
    }

    private boolean isCurrentUser(int userId) {
        return getCurrentUser() == userId;
    }

    /**
     * Only show trust agent messages after biometrics are no longer active.
     */
    private void showTrustAgentErrorMessage(CharSequence message) {
        if (message == null) {
            mTrustAgentErrorMessage = null;
            return;
        }
        boolean fpEngaged = mDeviceEntryFingerprintAuthInteractor.isEngaged().getValue();
        boolean faceRunning = mDeviceEntryFaceAuthInteractor.isRunning();
        if (fpEngaged || faceRunning) {
            mKeyguardLogger.delayShowingTrustAgentError(message, fpEngaged, faceRunning);
            mTrustAgentErrorMessage = message;
        } else {
            mTrustAgentErrorMessage = null;
            showBiometricMessage(message, null);
        }
    }

    protected void showTrustGrantedMessage(boolean dismissKeyguard, @Nullable String message) {
        mTrustGrantedIndication = message;
        updateDeviceEntryIndication(false);
    }

    private void handleFaceLockoutError(String errString) {
        String followupMessage = faceLockedOutFollowupMessage();
        // Lockout error can happen multiple times in a session because we trigger face auth
        // even when it is locked out so that the user is aware that face unlock would have
        // triggered but didn't because it is locked out.

        // On first lockout we show the error message from FaceManager, which tells the user they
        // had too many unsuccessful attempts.
        if (!mFaceLockedOutThisAuthSession) {
            mFaceLockedOutThisAuthSession = true;
            showErrorMessageNowOrLater(errString, followupMessage, FACE);
        } else if (!mAuthController.isUdfpsFingerDown()) {
            // On subsequent lockouts, we show a more generic locked out message.
            showErrorMessageNowOrLater(
                    mContext.getString(R.string.keyguard_face_unlock_unavailable),
                    followupMessage,
                    FACE);
        }
    }

    private String faceLockedOutFollowupMessage() {
        int followupMsgId = canUnlockWithFingerprint() ? R.string.keyguard_suggest_fingerprint
                : R.string.keyguard_unlock;
        return mContext.getString(followupMsgId);
    }

    private void handleFaceAuthTimeoutError(@Nullable CharSequence deferredFaceMessage) {
        mKeyguardLogger.logBiometricMessage("deferred message after face auth timeout",
                null, String.valueOf(deferredFaceMessage));
        if (canUnlockWithFingerprint()) {
            // Co-ex: show deferred message OR nothing
            // if we're on the lock screen (bouncer isn't showing), show the deferred msg
            if (deferredFaceMessage != null
                    && !mStatusBarKeyguardViewManager.isBouncerShowing()) {
                showBiometricMessage(
                        deferredFaceMessage,
                        mContext.getString(R.string.keyguard_suggest_fingerprint),
                        FACE
                );
            } else {
                // otherwise, don't show any message
                mKeyguardLogger.logBiometricMessage(
                        "skip showing FACE_ERROR_TIMEOUT due to co-ex logic");
            }
        } else if (deferredFaceMessage != null) {
            // Face-only: The face timeout message is not very actionable, let's ask the
            // user to manually retry.
            showBiometricMessage(
                    deferredFaceMessage,
                    mContext.getString(R.string.keyguard_unlock),
                    FACE
            );
        } else {
            // Face-only
            // suggest swiping up to unlock (try face auth again or swipe up to bouncer)
            showActionToUnlock();
        }
    }

    private boolean canUnlockWithFingerprint() {
        return mKeyguardUpdateMonitor.isUnlockWithFingerprintPossible(
                getCurrentUser()) && mKeyguardUpdateMonitor.isUnlockingWithFingerprintAllowed();
    }

    private void showErrorMessageNowOrLater(String errString, @Nullable String followUpMsg,
            BiometricSourceType biometricSourceType) {
        if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
            mStatusBarKeyguardViewManager.setKeyguardMessage(errString, mInitialTextColorState,
                    biometricSourceType);
        } else if (mScreenLifecycle.getScreenState() == SCREEN_ON) {
            showBiometricMessage(errString, followUpMsg, biometricSourceType);
        } else {
            mBiometricErrorMessageToShowOnScreenOn = new Pair<>(errString, biometricSourceType);
        }
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {
            setVisible(newState == StatusBarState.KEYGUARD);
        }

        @Override
        public void onDozingChanged(boolean dozing) {
            if (mDozing == dozing) {
                return;
            }
            mDozing = dozing;

            if (mDozing) {
                hideBiometricMessage();
            }
            updateDeviceEntryIndication(false);
        }
    };

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            mTrustAgentErrorMessage = null;
            updateDeviceEntryIndication(false);
        }

        @Override
        public void onKeyguardShowingChanged() {
            // All transient messages are gone the next time keyguard is shown
            if (!mKeyguardStateController.isShowing()) {
                mKeyguardLogger.log(TAG, LogLevel.DEBUG, "clear messages");
                mTopIndicationView.clearMessages();
                mRotateTextViewController.clearMessages();
                mTrustAgentErrorMessage = null;
            } else {
                updateDeviceEntryIndication(false);
            }
        }
    };
}
