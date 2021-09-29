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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.ACTION_USER_STOPPED;
import static android.content.Intent.ACTION_USER_UNLOCKED;
import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.UserSwitchObserver;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintManager.AuthenticationCallback;
import android.hardware.fingerprint.FingerprintManager.AuthenticationResult;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.telephony.CarrierConfigManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.widget.LockPatternUtils;
import com.android.settingslib.WirelessUtils;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.UdfpsController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.util.Assert;
import com.android.systemui.util.RingerModeTracker;

import com.google.android.collect.Lists;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Watches for updates that may be interesting to the keyguard, and provides
 * the up to date information as well as a registration for callbacks that care
 * to be updated.
 */
@SysUISingleton
public class KeyguardUpdateMonitor implements TrustManager.TrustListener, Dumpable {

    private static final String TAG = "KeyguardUpdateMonitor";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final boolean DEBUG_SIM_STATES = KeyguardConstants.DEBUG_SIM_STATES;
    private static final boolean DEBUG_FACE = Build.IS_DEBUGGABLE;
    private static final boolean DEBUG_FINGERPRINT = Build.IS_DEBUGGABLE;
    private static final boolean DEBUG_SPEW = false;
    private static final int FINGERPRINT_LOCKOUT_RESET_DELAY_MS = 600;

    private static final String ACTION_FACE_UNLOCK_STARTED
            = "com.android.facelock.FACE_UNLOCK_STARTED";
    private static final String ACTION_FACE_UNLOCK_STOPPED
            = "com.android.facelock.FACE_UNLOCK_STOPPED";

    // Callback messages
    private static final int MSG_TIME_UPDATE = 301;
    private static final int MSG_BATTERY_UPDATE = 302;
    private static final int MSG_SIM_STATE_CHANGE = 304;
    private static final int MSG_RINGER_MODE_CHANGED = 305;
    private static final int MSG_PHONE_STATE_CHANGED = 306;
    private static final int MSG_DEVICE_PROVISIONED = 308;
    private static final int MSG_DPM_STATE_CHANGED = 309;
    private static final int MSG_USER_SWITCHING = 310;
    private static final int MSG_KEYGUARD_RESET = 312;
    private static final int MSG_USER_SWITCH_COMPLETE = 314;
    private static final int MSG_USER_INFO_CHANGED = 317;
    private static final int MSG_REPORT_EMERGENCY_CALL_ACTION = 318;
    private static final int MSG_STARTED_WAKING_UP = 319;
    private static final int MSG_FINISHED_GOING_TO_SLEEP = 320;
    private static final int MSG_STARTED_GOING_TO_SLEEP = 321;
    private static final int MSG_KEYGUARD_BOUNCER_CHANGED = 322;
    private static final int MSG_FACE_UNLOCK_STATE_CHANGED = 327;
    private static final int MSG_SIM_SUBSCRIPTION_INFO_CHANGED = 328;
    private static final int MSG_AIRPLANE_MODE_CHANGED = 329;
    private static final int MSG_SERVICE_STATE_CHANGE = 330;
    private static final int MSG_SCREEN_TURNED_ON = 331;
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
    private static final int MSG_LOCK_SCREEN_MODE = 343;
    private static final int MSG_TIME_FORMAT_UPDATE = 344;
    private static final int MSG_REQUIRE_NFC_UNLOCK = 345;

    public static final int LOCK_SCREEN_MODE_NORMAL = 0;
    public static final int LOCK_SCREEN_MODE_LAYOUT_1 = 1;

    /** Biometric authentication state: Not listening. */
    private static final int BIOMETRIC_STATE_STOPPED = 0;

    /** Biometric authentication state: Listening. */
    private static final int BIOMETRIC_STATE_RUNNING = 1;

    /**
     * Biometric authentication: Cancelling and waiting for the relevant biometric service to
     * send us the confirmation that cancellation has happened.
     */
    private static final int BIOMETRIC_STATE_CANCELLING = 2;

    /**
     * Biometric state: During cancelling we got another request to start listening, so when we
     * receive the cancellation done signal, we should start listening again.
     */
    private static final int BIOMETRIC_STATE_CANCELLING_RESTARTING = 3;

    private static final int BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED = -1;
    public static final int BIOMETRIC_HELP_FACE_NOT_RECOGNIZED = -2;

    private static final int DEFAULT_CHARGING_VOLTAGE_MICRO_VOLT = 5000000;
    /**
     * If no cancel signal has been received after this amount of time, set the biometric running
     * state to stopped to allow Keyguard to retry authentication.
     */
    private static final int DEFAULT_CANCEL_SIGNAL_TIMEOUT = 3000;

    private static final ComponentName FALLBACK_HOME_COMPONENT = new ComponentName(
            "com.android.settings", "com.android.settings.FallbackHome");


    /**
     * If true, the system is in the half-boot-to-decryption-screen state.
     * Prudently disable lockscreen.
     */
    public static final boolean CORE_APPS_ONLY;

    static {
        try {
            CORE_APPS_ONLY = IPackageManager.Stub.asInterface(
                    ServiceManager.getService("package")).isOnlyCoreApps();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final Context mContext;
    private final boolean mIsPrimaryUser;
    private final boolean mIsAutomotive;
    private final AuthController mAuthController;
    private final StatusBarStateController mStatusBarStateController;
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

    HashMap<Integer, SimData> mSimDatas = new HashMap<>();
    HashMap<Integer, ServiceState> mServiceStates = new HashMap<Integer, ServiceState>();

    private int mRingMode;
    private int mPhoneState;
    private boolean mKeyguardIsVisible;
    private boolean mCredentialAttempted;
    private boolean mKeyguardGoingAway;
    private boolean mGoingToSleep;
    private boolean mBouncer; // true if bouncerIsOrWillBeShowing
    private boolean mAuthInterruptActive;
    private boolean mNeedsSlowUnlockTransition;
    private boolean mHasLockscreenWallpaper;
    private boolean mAssistantVisible;
    private boolean mKeyguardOccluded;
    private boolean mOccludingAppRequestingFp;
    private boolean mOccludingAppRequestingFace;
    private boolean mSecureCameraLaunched;
    @VisibleForTesting
    protected boolean mTelephonyCapable;

    private final boolean mAcquiredHapticEnabled = false;
    @Nullable private final Vibrator mVibrator;

    // Device provisioning state
    private boolean mDeviceProvisioned;

    // Battery status
    @VisibleForTesting
    BatteryStatus mBatteryStatus;

    private StrongAuthTracker mStrongAuthTracker;

    private final ArrayList<WeakReference<KeyguardUpdateMonitorCallback>>
            mCallbacks = Lists.newArrayList();
    private ContentObserver mDeviceProvisionedObserver;
    private ContentObserver mTimeFormatChangeObserver;

    private boolean mSwitchingUser;

    private boolean mDeviceInteractive;
    private boolean mScreenOn;
    private SubscriptionManager mSubscriptionManager;
    private final TelephonyListenerManager mTelephonyListenerManager;
    private List<SubscriptionInfo> mSubscriptionInfo;
    private TrustManager mTrustManager;
    private UserManager mUserManager;
    private KeyguardBypassController mKeyguardBypassController;
    private RingerModeTracker mRingerModeTracker;
    private int mFingerprintRunningState = BIOMETRIC_STATE_STOPPED;
    private int mFaceRunningState = BIOMETRIC_STATE_STOPPED;
    private boolean mIsFaceAuthUserRequested;
    private LockPatternUtils mLockPatternUtils;
    private final IDreamManager mDreamManager;
    private boolean mIsDreaming;
    private final DevicePolicyManager mDevicePolicyManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private boolean mLogoutEnabled;
    // cached value to avoid IPCs
    private boolean mIsUdfpsEnrolled;
    private boolean mIsFaceEnrolled;
    // If the user long pressed the lock icon, disabling face auth for the current session.
    private boolean mLockIconPressed;
    private int mActiveMobileDataSubscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private final Executor mBackgroundExecutor;
    private int mLockScreenMode;

    /**
     * Short delay before restarting fingerprint authentication after a successful try. This should
     * be slightly longer than the time between onFingerprintAuthenticated and
     * setKeyguardGoingAway(true).
     */
    private static final int FINGERPRINT_CONTINUE_DELAY_MS = 500;

    // If the HAL dies or is unable to authenticate, keyguard should retry after a short delay
    private int mHardwareFingerprintUnavailableRetryCount = 0;
    private int mHardwareFaceUnavailableRetryCount = 0;
    private static final int HAL_ERROR_RETRY_TIMEOUT = 500; // ms
    private static final int HAL_ERROR_RETRY_MAX = 20;

    private final Runnable mFpCancelNotReceived = () -> {
        Log.e(TAG, "Fp cancellation not received, transitioning to STOPPED");
        mFingerprintRunningState = BIOMETRIC_STATE_STOPPED;
        updateFingerprintListeningState();
    };

    private final Runnable mFaceCancelNotReceived = () -> {
        Log.e(TAG, "Face cancellation not received, transitioning to STOPPED");
        mFaceRunningState = BIOMETRIC_STATE_STOPPED;
        updateFaceListeningState();
    };

    private final Handler mHandler;

    private final Observer<Integer> mRingerModeObserver = new Observer<Integer>() {
        @Override
        public void onChanged(Integer ringer) {
            mHandler.obtainMessage(MSG_RINGER_MODE_CHANGED, ringer, 0).sendToTarget();
        }
    };

    private SparseBooleanArray mBiometricEnabledForUser = new SparseBooleanArray();
    private BiometricManager mBiometricManager;
    private IBiometricEnabledOnKeyguardCallback mBiometricEnabledCallback =
            new IBiometricEnabledOnKeyguardCallback.Stub() {
                @Override
                public void onChanged(boolean enabled, int userId) throws RemoteException {
                    mHandler.post(() -> {
                        mBiometricEnabledForUser.put(userId, enabled);
                        updateBiometricListeningState();
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

    private OnSubscriptionsChangedListener mSubscriptionListener =
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

    private SparseBooleanArray mUserIsUnlocked = new SparseBooleanArray();
    private SparseBooleanArray mUserHasTrust = new SparseBooleanArray();
    private SparseBooleanArray mUserTrustIsManaged = new SparseBooleanArray();
    private SparseBooleanArray mUserTrustIsUsuallyManaged = new SparseBooleanArray();
    private SparseBooleanArray mUserFaceUnlockRunning = new SparseBooleanArray();
    private Map<Integer, Intent> mSecondaryLockscreenRequirement = new HashMap<Integer, Intent>();

    @VisibleForTesting
    SparseArray<BiometricAuthenticated> mUserFingerprintAuthenticated = new SparseArray<>();
    @VisibleForTesting
    SparseArray<BiometricAuthenticated> mUserFaceAuthenticated = new SparseArray<>();

    // Keep track of recent calls to shouldListenFor*() for debugging.
    private final KeyguardListenQueue mListenModels = new KeyguardListenQueue();

    private static int sCurrentUser;
    private Runnable mUpdateBiometricListeningState = this::updateBiometricListeningState;

    public synchronized static void setCurrentUser(int currentUser) {
        sCurrentUser = currentUser;
    }

    public synchronized static int getCurrentUser() {
        return sCurrentUser;
    }

    @Override
    public void onTrustChanged(boolean enabled, int userId, int flags) {
        Assert.isMainThread();
        mUserHasTrust.put(userId, enabled);
        updateBiometricListeningState();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTrustChanged(userId);
                if (enabled && flags != 0) {
                    cb.onTrustGrantedWithFlags(flags, userId);
                }
            }
        }
    }

    @Override
    public void onTrustError(CharSequence message) {
        dispatchErrorMessage(message);
    }

    private void handleSimSubscriptionInfoChanged() {
        Assert.isMainThread();
        if (DEBUG_SIM_STATES) {
            Log.v(TAG, "onSubscriptionInfoChanged()");
            List<SubscriptionInfo> sil = mSubscriptionManager
                    .getCompleteActiveSubscriptionInfoList();
            if (sil != null) {
                for (SubscriptionInfo subInfo : sil) {
                    Log.v(TAG, "SubInfo:" + subInfo);
                }
            } else {
                Log.v(TAG, "onSubscriptionInfoChanged: list is null");
            }
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
                Log.i(TAG, "Previously active sub id " + simData.getKey() + " is now invalid, "
                        + "will remove");
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
            mSubscriptionInfo = new ArrayList<SubscriptionInfo>();
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
    public List<SubscriptionInfo> getFilteredSubscriptionInfo(boolean forceReload) {
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
        mUserTrustIsUsuallyManaged.put(userId, mTrustManager.isTrustUsuallyManaged(userId));
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
        updateBiometricListeningState();
    }

    /**
     * Updates KeyguardUpdateMonitor's internal state to know if keyguard is going away.
     */
    public void setKeyguardGoingAway(boolean goingAway) {
        mKeyguardGoingAway = goingAway;
        updateBiometricListeningState();
    }

    /**
     * Updates KeyguardUpdateMonitor's internal state to know if keyguard is occluded
     */
    public void setKeyguardOccluded(boolean occluded) {
        mKeyguardOccluded = occluded;
        updateBiometricListeningState();
    }


    /**
     * Request to listen for face authentication when an app is occluding keyguard.
     * @param request if true and mKeyguardOccluded, request face auth listening, else default
     *                to normal behavior.
     *                See {@link KeyguardUpdateMonitor#shouldListenForFace()}
     */
    public void requestFaceAuthOnOccludingApp(boolean request) {
        mOccludingAppRequestingFace = request;
        updateFaceListeningState();
    }

    /**
     * Request to listen for fingerprint when an app is occluding keyguard.
     * @param request if true and mKeyguardOccluded, request fingerprint listening, else default
     *                to normal behavior.
     *                See {@link KeyguardUpdateMonitor#shouldListenForFingerprint(boolean)}
     */
    public void requestFingerprintAuthOnOccludingApp(boolean request) {
        mOccludingAppRequestingFp = request;
        updateFingerprintListeningState();
    }

    /**
     * Invoked when the secure camera is launched.
     */
    public void onCameraLaunched() {
        mSecureCameraLaunched = true;
        updateBiometricListeningState();
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
        if (mIsDreaming && mDreamManager != null) {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to awaken from dream");
            }
        }
    }

    @VisibleForTesting
    protected void onFingerprintAuthenticated(int userId, boolean isStrongBiometric) {
        Assert.isMainThread();
        Trace.beginSection("KeyGuardUpdateMonitor#onFingerPrintAuthenticated");
        mUserFingerprintAuthenticated.put(userId,
                new BiometricAuthenticated(true, isStrongBiometric));
        // Update/refresh trust state only if user can skip bouncer
        if (getUserCanSkipBouncer(userId)) {
            mTrustManager.unlockedByBiometricForUser(userId, BiometricSourceType.FINGERPRINT);
        }
        // Don't send cancel if authentication succeeds
        mFingerprintCancelSignal = null;
        updateBiometricListeningState();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAuthenticated(userId, BiometricSourceType.FINGERPRINT,
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
        mBackgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mLockPatternUtils.reportSuccessfulBiometricUnlock(isStrongBiometric, userId);
            }
        });
    }

    private void handleFingerprintAuthFailed() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT);
            }
        }
        handleFingerprintHelp(BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED,
                mContext.getString(R.string.kg_fingerprint_not_recognized));
    }

    private void handleFingerprintAcquired(int acquireInfo) {
        Assert.isMainThread();
        if (acquireInfo != FingerprintManager.FINGERPRINT_ACQUIRED_GOOD) {
            return;
        }
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAcquired(BiometricSourceType.FINGERPRINT);
            }
        }
    }

    private void handleFingerprintAuthenticated(int authUserId, boolean isStrongBiometric) {
        Trace.beginSection("KeyGuardUpdateMonitor#handlerFingerPrintAuthenticated");
        try {
            final int userId;
            try {
                userId = ActivityManager.getService().getCurrentUser().id;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to get current user id: ", e);
                return;
            }
            if (userId != authUserId) {
                Log.d(TAG, "Fingerprint authenticated for wrong user: " + authUserId);
                return;
            }
            if (isFingerprintDisabled(userId)) {
                Log.d(TAG, "Fingerprint disabled by DPM for userId: " + userId);
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
                cb.onBiometricHelp(msgId, helpString, BiometricSourceType.FINGERPRINT);
            }
        }
    }

    private Runnable mRetryFingerprintAuthentication = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "Retrying fingerprint after HW unavailable, attempt " +
                    mHardwareFingerprintUnavailableRetryCount);
            if (mFpm.isHardwareDetected()) {
                updateFingerprintListeningState();
            } else if (mHardwareFingerprintUnavailableRetryCount < HAL_ERROR_RETRY_MAX) {
                mHardwareFingerprintUnavailableRetryCount++;
                mHandler.postDelayed(mRetryFingerprintAuthentication, HAL_ERROR_RETRY_TIMEOUT);
            }
        }
    };

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
            updateFingerprintListeningState();
        } else {
            setFingerprintRunningState(BIOMETRIC_STATE_STOPPED);
        }

        if (msgId == FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE) {
            mHandler.postDelayed(mRetryFingerprintAuthentication, HAL_ERROR_RETRY_TIMEOUT);
        }

        if (msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT) {
            mFingerprintLockedOutPermanent = true;
            requireStrongAuthIfAllLockedOut();
        }

        if (msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT
                || msgId == FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT) {
            mFingerprintLockedOut = true;
            if (isUdfpsEnrolled()) {
                updateFingerprintListeningState();
            }
        }

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricError(msgId, errString, BiometricSourceType.FINGERPRINT);
            }
        }
    }

    private void handleFingerprintLockoutReset() {
        mFingerprintLockedOut = false;
        mFingerprintLockedOutPermanent = false;

        if (isUdfpsEnrolled()) {
            // TODO(b/194825098): update the reset signal(s)
            // A successful unlock will trigger a lockout reset, but there is no guarantee
            // that the events will arrive in a particular order. Add a delay here in case
            // an unlock is in progress. In this is a normal unlock the extra delay won't
            // be noticeable.
            mHandler.postDelayed(this::updateFingerprintListeningState,
                    FINGERPRINT_LOCKOUT_RESET_DELAY_MS);
        } else {
            updateFingerprintListeningState();
        }
    }

    private void setFingerprintRunningState(int fingerprintRunningState) {
        boolean wasRunning = mFingerprintRunningState == BIOMETRIC_STATE_RUNNING;
        boolean isRunning = fingerprintRunningState == BIOMETRIC_STATE_RUNNING;
        mFingerprintRunningState = fingerprintRunningState;
        Log.d(TAG, "fingerprintRunningState: " + mFingerprintRunningState);
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
                        BiometricSourceType.FINGERPRINT);
            }
        }
    }

    @VisibleForTesting
    protected void onFaceAuthenticated(int userId, boolean isStrongBiometric) {
        Trace.beginSection("KeyGuardUpdateMonitor#onFaceAuthenticated");
        Assert.isMainThread();
        mUserFaceAuthenticated.put(userId,
                new BiometricAuthenticated(true, isStrongBiometric));
        // Update/refresh trust state only if user can skip bouncer
        if (getUserCanSkipBouncer(userId)) {
            mTrustManager.unlockedByBiometricForUser(userId, BiometricSourceType.FACE);
        }
        // Don't send cancel if authentication succeeds
        mFaceCancelSignal = null;
        updateBiometricListeningState();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAuthenticated(userId,
                        BiometricSourceType.FACE,
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
        mFaceCancelSignal = null;
        setFaceRunningState(BIOMETRIC_STATE_STOPPED);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAuthFailed(BiometricSourceType.FACE);
            }
        }
        handleFaceHelp(BIOMETRIC_HELP_FACE_NOT_RECOGNIZED,
                mContext.getString(R.string.kg_face_not_recognized));
    }

    private void handleFaceAcquired(int acquireInfo) {
        Assert.isMainThread();
        if (acquireInfo != FaceManager.FACE_ACQUIRED_GOOD) {
            return;
        }
        if (DEBUG_FACE) Log.d(TAG, "Face acquired");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricAcquired(BiometricSourceType.FACE);
            }
        }
    }

    private void handleFaceAuthenticated(int authUserId, boolean isStrongBiometric) {
        Trace.beginSection("KeyGuardUpdateMonitor#handlerFaceAuthenticated");
        try {
            if (mGoingToSleep) {
                Log.d(TAG, "Aborted successful auth because device is going to sleep.");
                return;
            }
            final int userId;
            try {
                userId = ActivityManager.getService().getCurrentUser().id;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to get current user id: ", e);
                return;
            }
            if (userId != authUserId) {
                Log.d(TAG, "Face authenticated for wrong user: " + authUserId);
                return;
            }
            if (isFaceDisabled(userId)) {
                Log.d(TAG, "Face authentication disabled by DPM for userId: " + userId);
                return;
            }
            if (DEBUG_FACE) Log.d(TAG, "Face auth succeeded for user " + userId);
            onFaceAuthenticated(userId, isStrongBiometric);
        } finally {
            setFaceRunningState(BIOMETRIC_STATE_STOPPED);
        }
        Trace.endSection();
    }

    private void handleFaceHelp(int msgId, String helpString) {
        Assert.isMainThread();
        if (DEBUG_FACE) Log.d(TAG, "Face help received: " + helpString);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricHelp(msgId, helpString, BiometricSourceType.FACE);
            }
        }
    }

    private Runnable mRetryFaceAuthentication = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "Retrying face after HW unavailable, attempt " +
                    mHardwareFaceUnavailableRetryCount);
            updateFaceListeningState();
        }
    };

    private void handleFaceError(int msgId, String errString) {
        Assert.isMainThread();
        if (DEBUG_FACE) Log.d(TAG, "Face error received: " + errString);
        if (mHandler.hasCallbacks(mFaceCancelNotReceived)) {
            mHandler.removeCallbacks(mFaceCancelNotReceived);
        }

        // Error is always the end of authentication lifecycle
        mFaceCancelSignal = null;

        if (msgId == FaceManager.FACE_ERROR_CANCELED
                && mFaceRunningState == BIOMETRIC_STATE_CANCELLING_RESTARTING) {
            setFaceRunningState(BIOMETRIC_STATE_STOPPED);
            updateFaceListeningState();
        } else {
            setFaceRunningState(BIOMETRIC_STATE_STOPPED);
        }

        if (msgId == FaceManager.FACE_ERROR_HW_UNAVAILABLE
                || msgId == FaceManager.FACE_ERROR_UNABLE_TO_PROCESS) {
            if (mHardwareFaceUnavailableRetryCount < HAL_ERROR_RETRY_MAX) {
                mHardwareFaceUnavailableRetryCount++;
                mHandler.removeCallbacks(mRetryFaceAuthentication);
                mHandler.postDelayed(mRetryFaceAuthentication, HAL_ERROR_RETRY_TIMEOUT);
            }
        }

        if (msgId == FaceManager.FACE_ERROR_LOCKOUT_PERMANENT) {
            mFaceLockedOutPermanent = true;
            requireStrongAuthIfAllLockedOut();
        }

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricError(msgId, errString,
                        BiometricSourceType.FACE);
            }
        }
    }

    private void handleFaceLockoutReset() {
        mFaceLockedOutPermanent = false;
        updateFaceListeningState();
    }

    private void setFaceRunningState(int faceRunningState) {
        boolean wasRunning = mFaceRunningState == BIOMETRIC_STATE_RUNNING;
        boolean isRunning = faceRunningState == BIOMETRIC_STATE_RUNNING;
        mFaceRunningState = faceRunningState;
        Log.d(TAG, "faceRunningState: " + mFaceRunningState);
        // Clients of KeyguardUpdateMonitor don't care about the internal state or about the
        // asynchronousness of the cancel cycle. So only notify them if the actually running state
        // has changed.
        if (wasRunning != isRunning) {
            notifyFaceRunningStateChanged();
        }
    }

    private void notifyFaceRunningStateChanged() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricRunningStateChanged(isFaceDetectionRunning(),
                        BiometricSourceType.FACE);
            }
        }
    }

    private void handleFaceUnlockStateChanged(boolean running, int userId) {
        Assert.isMainThread();
        mUserFaceUnlockRunning.put(userId, running);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onFaceUnlockStateChanged(running, userId);
            }
        }
    }

    public boolean isFaceUnlockRunning(int userId) {
        return mUserFaceUnlockRunning.get(userId);
    }

    public boolean isFingerprintDetectionRunning() {
        return mFingerprintRunningState == BIOMETRIC_STATE_RUNNING;
    }

    public boolean isFaceDetectionRunning() {
        return mFaceRunningState == BIOMETRIC_STATE_RUNNING;
    }

    private boolean isTrustDisabled(int userId) {
        // Don't allow trust agent if device is secured with a SIM PIN. This is here
        // mainly because there's no other way to prompt the user to enter their SIM PIN
        // once they get past the keyguard screen.
        final boolean disabledBySimPin = isSimPinSecure();
        return disabledBySimPin;
    }

    private boolean isFingerprintDisabled(int userId) {
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && (dpm.getKeyguardDisabledFeatures(null, userId)
                & DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT) != 0
                || isSimPinSecure();
    }

    private boolean isFaceDisabled(int userId) {
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        // TODO(b/140035044)
        return whitelistIpcs(() -> dpm != null && (dpm.getKeyguardDisabledFeatures(null, userId)
                & DevicePolicyManager.KEYGUARD_DISABLE_FACE) != 0
                || isSimPinSecure());
    }

    private boolean getIsFaceAuthenticated() {
        boolean faceAuthenticated = false;
        BiometricAuthenticated bioFaceAuthenticated = mUserFaceAuthenticated.get(getCurrentUser());
        if (bioFaceAuthenticated != null) {
            faceAuthenticated = bioFaceAuthenticated.mAuthenticated;
        }
        return faceAuthenticated;
    }

    private void requireStrongAuthIfAllLockedOut() {
        final boolean faceLock =
                (mFaceLockedOutPermanent || !shouldListenForFace()) && !getIsFaceAuthenticated();
        final boolean fpLock =
                mFingerprintLockedOutPermanent || !shouldListenForFingerprint(isUdfpsEnrolled());

        if (faceLock && fpLock) {
            Log.d(TAG, "All biometrics locked out - requiring strong auth");
            mLockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_LOCKOUT,
                    getCurrentUser());
        }
    }

    public boolean getUserCanSkipBouncer(int userId) {
        return getUserHasTrust(userId) || getUserUnlockedWithBiometric(userId);
    }

    public boolean getUserHasTrust(int userId) {
        return !isTrustDisabled(userId) && mUserHasTrust.get(userId);
    }

    /**
     * Returns whether the user is unlocked with biometrics.
     */
    public boolean getUserUnlockedWithBiometric(int userId) {
        BiometricAuthenticated fingerprint = mUserFingerprintAuthenticated.get(userId);
        BiometricAuthenticated face = mUserFaceAuthenticated.get(userId);
        boolean fingerprintAllowed = fingerprint != null && fingerprint.mAuthenticated
                && isUnlockingWithBiometricAllowed(fingerprint.mIsStrongBiometric);
        boolean faceAllowed = face != null && face.mAuthenticated
                && isUnlockingWithBiometricAllowed(face.mIsStrongBiometric);
        return fingerprintAllowed || faceAllowed;
    }

    public boolean getUserTrustIsManaged(int userId) {
        return mUserTrustIsManaged.get(userId) && !isTrustDisabled(userId);
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
                Log.e(TAG, "No Profile Owner or Device Owner supervision app found for User "
                        + userId);
            } else {
                Intent intent =
                        new Intent(DevicePolicyManager.ACTION_BIND_SECONDARY_LOCKSCREEN_SERVICE)
                                .setPackage(supervisorComponent.getPackageName());
                ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent, 0);
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

    public boolean isUnlockingWithBiometricAllowed(boolean isStrongBiometric) {
        return mStrongAuthTracker.isUnlockingWithBiometricAllowed(isStrongBiometric);
    }

    public boolean isUserInLockdown(int userId) {
        return containsFlag(mStrongAuthTracker.getStrongAuthForUser(userId),
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
    }

    private boolean isEncryptedOrLockdown(int userId) {
        final int strongAuth = mStrongAuthTracker.getStrongAuthForUser(userId);
        final boolean isLockDown =
                containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW)
                        || containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
        final boolean isEncrypted = containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_BOOT);

        return isEncrypted || isLockDown;
    }

    public boolean userNeedsStrongAuth() {
        return mStrongAuthTracker.getStrongAuthForUser(getCurrentUser())
                != LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
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

    private void notifyStrongAuthStateChanged(int userId) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onStrongAuthStateChanged(userId);
            }
        }
    }

    public boolean isScreenOn() {
        return mScreenOn;
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
        updateBiometricListeningState();
    }

    static class DisplayClientState {
        public int clientGeneration;
        public boolean clearing;
        public PendingIntent intent;
        public int playbackState;
        public long playbackEventTime;
    }

    private DisplayClientState mDisplayClientState = new DisplayClientState();

    @VisibleForTesting
    protected final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "received broadcast " + action);

            if (Intent.ACTION_TIME_TICK.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MSG_TIME_UPDATE);
            } else if (Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                final Message msg = mHandler.obtainMessage(
                        MSG_TIMEZONE_UPDATE, intent.getStringExtra(Intent.EXTRA_TIMEZONE));
                mHandler.sendMessage(msg);
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {

                final Message msg = mHandler.obtainMessage(
                        MSG_BATTERY_UPDATE, new BatteryStatus(intent));
                mHandler.sendMessage(msg);
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
                if (DEBUG_SIM_STATES) {
                    Log.v(TAG, "action " + action
                            + " state: " + intent.getStringExtra(
                            Intent.EXTRA_SIM_STATE)
                            + " slotId: " + args.slotId + " subid: " + args.subId);
                }
                mHandler.obtainMessage(MSG_SIM_STATE_CHANGE, args.subId, args.slotId, args.simState)
                        .sendToTarget();
            } else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_PHONE_STATE_CHANGED, state));
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MSG_AIRPLANE_MODE_CHANGED);
            } else if (Intent.ACTION_SERVICE_STATE.equals(action)) {
                ServiceState serviceState = ServiceState.newFromBundle(intent.getExtras());
                int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                if (DEBUG) {
                    Log.v(TAG, "action " + action + " serviceState=" + serviceState + " subId="
                            + subId);
                }
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
            } else if (Intent.ACTION_USER_INFO_CHANGED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_INFO_CHANGED,
                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId()), 0));
            } else if (ACTION_FACE_UNLOCK_STARTED.equals(action)) {
                Trace.beginSection(
                        "KeyguardUpdateMonitor.mBroadcastAllReceiver#onReceive "
                                + "ACTION_FACE_UNLOCK_STARTED");
                mHandler.sendMessage(mHandler.obtainMessage(MSG_FACE_UNLOCK_STATE_CHANGED, 1,
                        getSendingUserId()));
                Trace.endSection();
            } else if (ACTION_FACE_UNLOCK_STOPPED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_FACE_UNLOCK_STATE_CHANGED, 0,
                        getSendingUserId()));
            } else if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                    .equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_DPM_STATE_CHANGED,
                        getSendingUserId()));
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
            handleFingerprintLockoutReset();
        }
    };

    private final FaceManager.LockoutResetCallback mFaceLockoutResetCallback
            = new FaceManager.LockoutResetCallback() {
        @Override
        public void onLockoutReset(int sensorId) {
            handleFaceLockoutReset();
        }
    };

    private final FingerprintManager.FingerprintDetectionCallback mFingerprintDetectionCallback
            = (sensorId, userId, isStrongBiometric) -> {
                // Trigger the fingerprint success path so the bouncer can be shown
                handleFingerprintAuthenticated(userId, isStrongBiometric);
            };

    @VisibleForTesting
    final FingerprintManager.AuthenticationCallback mFingerprintAuthenticationCallback
            = new AuthenticationCallback() {
                private boolean mPlayedAcquiredHaptic;

                @Override
                public void onAuthenticationFailed() {
                    handleFingerprintAuthFailed();
                }

                @Override
                public void onAuthenticationSucceeded(AuthenticationResult result) {
                    Trace.beginSection("KeyguardUpdateMonitor#onAuthenticationSucceeded");
                    handleFingerprintAuthenticated(result.getUserId(), result.isStrongBiometric());
                    Trace.endSection();

                    // on auth success, we sometimes never received an acquired haptic
                    if (!mPlayedAcquiredHaptic && isUdfpsEnrolled()) {
                        playAcquiredHaptic();
                    }
                }

                @Override
                public void onAuthenticationHelp(int helpMsgId, CharSequence helpString) {
                    handleFingerprintHelp(helpMsgId, helpString.toString());
                }

                @Override
                public void onAuthenticationError(int errMsgId, CharSequence errString) {
                    handleFingerprintError(errMsgId, errString.toString());
                }

                @Override
                public void onAuthenticationAcquired(int acquireInfo) {
                    handleFingerprintAcquired(acquireInfo);
                    if (acquireInfo == FingerprintManager.FINGERPRINT_ACQUIRED_GOOD
                            && isUdfpsEnrolled()) {
                        mPlayedAcquiredHaptic = true;
                        playAcquiredHaptic();
                    }
                }

                @Override
                public void onUdfpsPointerDown(int sensorId) {
                    Log.d(TAG, "onUdfpsPointerDown, sensorId: " + sensorId);
                    mPlayedAcquiredHaptic = false;
                }

                @Override
                public void onUdfpsPointerUp(int sensorId) {
                    Log.d(TAG, "onUdfpsPointerUp, sensorId: " + sensorId);
                }
            };

    /**
     * Play haptic to signal udfps fingeprrint acquired.
     */
    @VisibleForTesting
    public void playAcquiredHaptic() {
        if (mAcquiredHapticEnabled && mVibrator != null) {
            mVibrator.vibrate(UdfpsController.EFFECT_CLICK,
                    UdfpsController.VIBRATION_SONIFICATION_ATTRIBUTES);
        }
    }

    private final FaceManager.FaceDetectionCallback mFaceDetectionCallback
            = (sensorId, userId, isStrongBiometric) -> {
                // Trigger the face success path so the bouncer can be shown
                handleFaceAuthenticated(userId, isStrongBiometric);
            };

    @VisibleForTesting
    final FaceManager.AuthenticationCallback mFaceAuthenticationCallback
            = new FaceManager.AuthenticationCallback() {

                @Override
                public void onAuthenticationFailed() {
                    handleFaceAuthFailed();
                    if (mKeyguardBypassController != null) {
                        mKeyguardBypassController.setUserHasDeviceEntryIntent(false);
                    }
                }

                @Override
                public void onAuthenticationSucceeded(FaceManager.AuthenticationResult result) {
                    Trace.beginSection("KeyguardUpdateMonitor#onAuthenticationSucceeded");
                    handleFaceAuthenticated(result.getUserId(), result.isStrongBiometric());
                    Trace.endSection();

                    if (mKeyguardBypassController != null) {
                        mKeyguardBypassController.setUserHasDeviceEntryIntent(false);
                    }
                }

                @Override
                public void onAuthenticationHelp(int helpMsgId, CharSequence helpString) {
                    handleFaceHelp(helpMsgId, helpString.toString());
                }

                @Override
                public void onAuthenticationError(int errMsgId, CharSequence errString) {
                    handleFaceError(errMsgId, errString.toString());
                    if (mKeyguardBypassController != null) {
                        mKeyguardBypassController.setUserHasDeviceEntryIntent(false);
                    }
                }

                @Override
                public void onAuthenticationAcquired(int acquireInfo) {
                    handleFaceAcquired(acquireInfo);
                }
    };

    private CancellationSignal mFingerprintCancelSignal;
    private CancellationSignal mFaceCancelSignal;
    private FingerprintManager mFpm;
    private FaceManager mFaceManager;
    private List<FaceSensorPropertiesInternal> mFaceSensorProperties;
    private boolean mFingerprintLockedOut;
    private boolean mFingerprintLockedOutPermanent;
    private boolean mFaceLockedOutPermanent;
    private TelephonyManager mTelephonyManager;

    /**
     * When we receive a
     * {@link com.android.internal.telephony.TelephonyIntents#ACTION_SIM_STATE_CHANGED} broadcast,
     * and then pass a result via our handler to {@link KeyguardUpdateMonitor#handleSimStateChange},
     * we need a single object to pass to the handler.  This class helps decode
     * the intent and provide a {@link SimCard.State} result.
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
            int state;
            if (!Intent.ACTION_SIM_STATE_CHANGED.equals(intent.getAction())) {
                throw new IllegalArgumentException("only handles intent ACTION_SIM_STATE_CHANGED");
            }
            String stateExtra = intent.getStringExtra(Intent.EXTRA_SIM_STATE);
            int slotId = intent.getIntExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 0);
            int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            if (Intent.SIM_STATE_ABSENT.equals(stateExtra)) {
                final String absentReason = intent
                        .getStringExtra(Intent.EXTRA_SIM_LOCKED_REASON);

                if (Intent.SIM_ABSENT_ON_PERM_DISABLED.equals(
                        absentReason)) {
                    state = TelephonyManager.SIM_STATE_PERM_DISABLED;
                } else {
                    state = TelephonyManager.SIM_STATE_ABSENT;
                }
            } else if (Intent.SIM_STATE_READY.equals(stateExtra)) {
                state = TelephonyManager.SIM_STATE_READY;
            } else if (Intent.SIM_STATE_LOCKED.equals(stateExtra)) {
                final String lockedReason = intent
                        .getStringExtra(Intent.EXTRA_SIM_LOCKED_REASON);
                if (Intent.SIM_LOCKED_ON_PIN.equals(lockedReason)) {
                    state = TelephonyManager.SIM_STATE_PIN_REQUIRED;
                } else if (Intent.SIM_LOCKED_ON_PUK.equals(lockedReason)) {
                    state = TelephonyManager.SIM_STATE_PUK_REQUIRED;
                } else {
                    state = TelephonyManager.SIM_STATE_UNKNOWN;
                }
            } else if (Intent.SIM_LOCKED_NETWORK.equals(stateExtra)) {
                state = TelephonyManager.SIM_STATE_NETWORK_LOCKED;
            } else if (Intent.SIM_STATE_CARD_IO_ERROR.equals(stateExtra)) {
                state = TelephonyManager.SIM_STATE_CARD_IO_ERROR;
            } else if (Intent.SIM_STATE_LOADED.equals(stateExtra)
                    || Intent.SIM_STATE_IMSI.equals(stateExtra)) {
                // This is required because telephony doesn't return to "READY" after
                // these state transitions. See bug 7197471.
                state = TelephonyManager.SIM_STATE_READY;
            } else {
                state = TelephonyManager.SIM_STATE_UNKNOWN;
            }
            return new SimData(state, slotId, subId);
        }

        @Override
        public String toString() {
            return "SimData{state=" + simState + ",slotId=" + slotId + ",subId=" + subId + "}";
        }
    }

    public static class StrongAuthTracker extends LockPatternUtils.StrongAuthTracker {
        private final Consumer<Integer> mStrongAuthRequiredChangedCallback;

        public StrongAuthTracker(Context context,
                Consumer<Integer> strongAuthRequiredChangedCallback) {
            super(context);
            mStrongAuthRequiredChangedCallback = strongAuthRequiredChangedCallback;
        }

        public boolean isUnlockingWithBiometricAllowed(boolean isStrongBiometric) {
            int userId = getCurrentUser();
            return isBiometricAllowedForUser(isStrongBiometric, userId);
        }

        public boolean hasUserAuthenticatedSinceBoot() {
            int userId = getCurrentUser();
            return (getStrongAuthForUser(userId)
                    & STRONG_AUTH_REQUIRED_AFTER_BOOT) == 0;
        }

        @Override
        public void onStrongAuthRequiredChanged(int userId) {
            mStrongAuthRequiredChangedCallback.accept(userId);
        }
    }

    protected void handleStartedWakingUp() {
        Trace.beginSection("KeyguardUpdateMonitor#handleStartedWakingUp");
        Assert.isMainThread();
        updateBiometricListeningState();
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
        mLockIconPressed = false;
        clearBiometricRecognized();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onStartedGoingToSleep(arg1);
            }
        }
        mGoingToSleep = true;
        updateBiometricListeningState();
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
        updateBiometricListeningState();
    }

    private void handleScreenTurnedOn() {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onScreenTurnedOn();
            }
        }
    }

    private void handleScreenTurnedOff() {
        final String tag = "KeyguardUpdateMonitor#handleScreenTurnedOff";
        DejankUtils.startDetectingBlockingIpcs(tag);
        Assert.isMainThread();
        mHardwareFingerprintUnavailableRetryCount = 0;
        mHardwareFaceUnavailableRetryCount = 0;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onScreenTurnedOff();
            }
        }
        DejankUtils.stopDetectingBlockingIpcs(tag);
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
        updateBiometricListeningState();
    }

    private void handleUserInfoChanged(int userId) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserInfoChanged(userId);
            }
        }
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
        mFaceRunningState = BIOMETRIC_STATE_STOPPED;
    }

    private void registerRingerTracker() {
        mRingerModeTracker.getRingerMode().observeForever(mRingerModeObserver);
    }

    @VisibleForTesting
    @Inject
    protected KeyguardUpdateMonitor(
            Context context,
            @Main Looper mainLooper,
            BroadcastDispatcher broadcastDispatcher,
            DumpManager dumpManager,
            RingerModeTracker ringerModeTracker,
            @Background Executor backgroundExecutor,
            StatusBarStateController statusBarStateController,
            LockPatternUtils lockPatternUtils,
            AuthController authController,
            TelephonyListenerManager telephonyListenerManager,
            FeatureFlags featureFlags,
            InteractionJankMonitor interactionJankMonitor,
            @Nullable Vibrator vibrator) {
        mContext = context;
        mSubscriptionManager = SubscriptionManager.from(context);
        mTelephonyListenerManager = telephonyListenerManager;
        mDeviceProvisioned = isDeviceProvisionedInSettingsDb();
        mStrongAuthTracker = new StrongAuthTracker(context, this::notifyStrongAuthStateChanged);
        mBackgroundExecutor = backgroundExecutor;
        mBroadcastDispatcher = broadcastDispatcher;
        mInteractionJankMonitor = interactionJankMonitor;
        mRingerModeTracker = ringerModeTracker;
        mStatusBarStateController = statusBarStateController;
        mStatusBarStateController.addCallback(mStatusBarStateControllerListener);
        mStatusBarState = mStatusBarStateController.getState();
        mLockPatternUtils = lockPatternUtils;
        mAuthController = authController;
        dumpManager.registerDumpable(getClass().getName(), this);
        mVibrator = vibrator;

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
                    case MSG_RINGER_MODE_CHANGED:
                        handleRingerModeChange(msg.arg1);
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
                        handleUserSwitching(msg.arg1, (IRemoteCallback) msg.obj);
                        break;
                    case MSG_USER_SWITCH_COMPLETE:
                        handleUserSwitchComplete(msg.arg1);
                        break;
                    case MSG_KEYGUARD_RESET:
                        handleKeyguardReset();
                        break;
                    case MSG_KEYGUARD_BOUNCER_CHANGED:
                        handleKeyguardBouncerChanged(msg.arg1);
                        break;
                    case MSG_USER_INFO_CHANGED:
                        handleUserInfoChanged(msg.arg1);
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
                        handleStartedWakingUp();
                        Trace.endSection();
                        break;
                    case MSG_FACE_UNLOCK_STATE_CHANGED:
                        Trace.beginSection(
                                "KeyguardUpdateMonitor#handler MSG_FACE_UNLOCK_STATE_CHANGED");
                        handleFaceUnlockStateChanged(msg.arg1 != 0, msg.arg2);
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
                    case MSG_SCREEN_TURNED_ON:
                        handleScreenTurnedOn();
                        break;
                    case MSG_SCREEN_TURNED_OFF:
                        Trace.beginSection("KeyguardUpdateMonitor#handler MSG_SCREEN_TURNED_ON");
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
                        updateBiometricListeningState();
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
                    case MSG_LOCK_SCREEN_MODE:
                        handleLockScreenMode();
                        break;
                    case MSG_TIME_FORMAT_UPDATE:
                        handleTimeFormatUpdate((String) msg.obj);
                        break;
                    case MSG_REQUIRE_NFC_UNLOCK:
                        handleRequireUnlockForNfc();
                        break;
                    default:
                        super.handleMessage(msg);
                        break;
                }
            }
        };

        // Since device can't be un-provisioned, we only need to register a content observer
        // to update mDeviceProvisioned when we are...
        if (!mDeviceProvisioned) {
            watchForDeviceProvisioning();
        }

        // Take a guess at initial SIM state, battery status and PLMN until we get an update
        mBatteryStatus = new BatteryStatus(BATTERY_STATUS_UNKNOWN, 100, 0, 0, 0, true);

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
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mBroadcastDispatcher.registerReceiverWithHandler(mBroadcastReceiver, filter, mHandler);
        // Since ACTION_SERVICE_STATE is being moved to a non-sticky broadcast, trigger the
        // listener now with the service state from the default sub.
        mBackgroundExecutor.execute(() -> {
            int subId = SubscriptionManager.getDefaultSubscriptionId();
            ServiceState serviceState = mContext.getSystemService(TelephonyManager.class)
                    .getServiceStateForSubscriber(subId);
            mHandler.sendMessage(
                    mHandler.obtainMessage(MSG_SERVICE_STATE_CHANGE, subId, 0, serviceState));

            // Get initial state. Relying on Sticky behavior until API for getting info.
            if (mBatteryStatus == null) {
                Intent intent = mContext.registerReceiver(
                        null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                );
                if (intent != null && mBatteryStatus == null) {
                    mBroadcastReceiver.onReceive(mContext, intent);
                }
            }
        });

        mHandler.post(this::registerRingerTracker);

        final IntentFilter allUserFilter = new IntentFilter();
        allUserFilter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        allUserFilter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        allUserFilter.addAction(ACTION_FACE_UNLOCK_STARTED);
        allUserFilter.addAction(ACTION_FACE_UNLOCK_STOPPED);
        allUserFilter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        allUserFilter.addAction(ACTION_USER_UNLOCKED);
        allUserFilter.addAction(ACTION_USER_STOPPED);
        allUserFilter.addAction(ACTION_USER_REMOVED);
        allUserFilter.addAction(NfcAdapter.ACTION_REQUIRE_UNLOCK_FOR_NFC);
        mBroadcastDispatcher.registerReceiverWithHandler(mBroadcastAllReceiver, allUserFilter,
                mHandler, UserHandle.ALL);

        mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionListener);
        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserSwitchObserver, TAG);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }

        mTrustManager = context.getSystemService(TrustManager.class);
        mTrustManager.registerTrustListener(this);

        setStrongAuthTracker(mStrongAuthTracker);

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));

        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            mFpm = (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
        }
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FACE)) {
            mFaceManager = (FaceManager) context.getSystemService(Context.FACE_SERVICE);
            mFaceSensorProperties = mFaceManager.getSensorPropertiesInternal();
        }

        if (mFpm != null || mFaceManager != null) {
            mBiometricManager = context.getSystemService(BiometricManager.class);
            mBiometricManager.registerEnabledOnKeyguardCallback(mBiometricEnabledCallback);
        }

        updateBiometricListeningState();
        if (mFpm != null) {
            mFpm.addLockoutResetCallback(mFingerprintLockoutResetCallback);
        }
        if (mFaceManager != null) {
            mFaceManager.addLockoutResetCallback(mFaceLockoutResetCallback);
        }

        mIsAutomotive = isAutomotive();

        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);
        mUserManager = context.getSystemService(UserManager.class);
        mIsPrimaryUser = mUserManager.isPrimaryUser();
        int user = ActivityManager.getCurrentUser();
        mUserIsUnlocked.put(user, mUserManager.isUserUnlocked(user));
        mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);
        mLogoutEnabled = mDevicePolicyManager.isLogoutEnabled();
        updateSecondaryLockscreenRequirement(user);
        List<UserInfo> allUsers = mUserManager.getUsers();
        for (UserInfo userInfo : allUsers) {
            mUserTrustIsUsuallyManaged.put(userInfo.id,
                    mTrustManager.isTrustUsuallyManaged(userInfo.id));
        }
        updateAirplaneModeState();

        mTelephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (mTelephonyManager != null) {
            mTelephonyListenerManager.addActiveDataSubscriptionIdListener(mPhoneStateListener);
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

        updateLockScreenMode(featureFlags.isKeyguardLayoutEnabled());

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
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.TIME_12_24),
                false, mTimeFormatChangeObserver, UserHandle.USER_ALL);
    }

    private void updateLockScreenMode(boolean isEnabled) {
        final int newMode = isEnabled ? LOCK_SCREEN_MODE_LAYOUT_1 : LOCK_SCREEN_MODE_NORMAL;
        if (newMode != mLockScreenMode) {
            mLockScreenMode = newMode;
            mHandler.sendEmptyMessage(MSG_LOCK_SCREEN_MODE);
        }
    }

    private void updateUdfpsEnrolled(int userId) {
        mIsUdfpsEnrolled = mAuthController.isUdfpsEnrolled(userId);
    }

    private void updateFaceEnrolled(int userId) {
        mIsFaceEnrolled = whitelistIpcs(
                () -> mFaceManager != null && mFaceManager.isHardwareDetected()
                        && mFaceManager.hasEnrolledTemplates(userId)
                        && mBiometricEnabledForUser.get(userId));
    }

    /**
     * @return true if there's at least one udfps enrolled
     */
    public boolean isUdfpsEnrolled() {
        return mIsUdfpsEnrolled;
    }

    /**
     * @return if udfps is available on this device. will return true even if the user hasn't
     * enrolled udfps.
     */
    public boolean isUdfpsAvailable() {
        return mAuthController.getUdfpsProps() != null
                && !mAuthController.getUdfpsProps().isEmpty();
    }

    /**
     * @return true if there's at least one face enrolled
     */
    public boolean isFaceEnrolled() {
        return mIsFaceEnrolled;
    }

    private final UserSwitchObserver mUserSwitchObserver = new UserSwitchObserver() {
        @Override
        public void onUserSwitching(int newUserId, IRemoteCallback reply) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCHING,
                    newUserId, 0, reply));
        }

        @Override
        public void onUserSwitchComplete(int newUserId) throws RemoteException {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCH_COMPLETE,
                    newUserId, 0));
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

    private void updateBiometricListeningState() {
        updateFingerprintListeningState();
        updateFaceListeningState();
    }

    private void updateFingerprintListeningState() {
        // If this message exists, we should not authenticate again until this message is
        // consumed by the handler
        if (mHandler.hasMessages(MSG_BIOMETRIC_AUTHENTICATION_CONTINUE)) {
            return;
        }

        // TODO: Add support for multiple fingerprint sensors, b/173730729
        updateUdfpsEnrolled(getCurrentUser());
        final boolean shouldListenForFingerprint = shouldListenForFingerprint(isUdfpsEnrolled());
        final boolean runningOrRestarting = mFingerprintRunningState == BIOMETRIC_STATE_RUNNING
                || mFingerprintRunningState == BIOMETRIC_STATE_CANCELLING_RESTARTING;
        if (runningOrRestarting && !shouldListenForFingerprint) {
            stopListeningForFingerprint();
        } else if (!runningOrRestarting && shouldListenForFingerprint) {
            startListeningForFingerprint();
        }
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
        if (DEBUG) Log.d(TAG, "onAuthInterruptDetected(" + active + ")");
        if (mAuthInterruptActive == active) {
            return;
        }
        mAuthInterruptActive = active;
        updateFaceListeningState();
    }

    /**
     * Requests face authentication if we're on a state where it's allowed.
     * This will re-trigger auth in case it fails.
     * @param userInitiatedRequest true if the user explicitly requested face auth
     */
    public void requestFaceAuth(boolean userInitiatedRequest) {
        if (DEBUG) Log.d(TAG, "requestFaceAuth() userInitiated=" + userInitiatedRequest);
        mIsFaceAuthUserRequested |= userInitiatedRequest;
        updateFaceListeningState();
    }

    public boolean isFaceAuthUserRequested() {
        return mIsFaceAuthUserRequested;
    }

    /**
     * In case face auth is running, cancel it.
     */
    public void cancelFaceAuth() {
        stopListeningForFace();
    }

    private void updateFaceListeningState() {
        // If this message exists, we should not authenticate again until this message is
        // consumed by the handler
        if (mHandler.hasMessages(MSG_BIOMETRIC_AUTHENTICATION_CONTINUE)) {
            return;
        }
        mHandler.removeCallbacks(mRetryFaceAuthentication);
        boolean shouldListenForFace = shouldListenForFace();
        if (mFaceRunningState == BIOMETRIC_STATE_RUNNING && !shouldListenForFace) {
            mIsFaceAuthUserRequested = false;
            stopListeningForFace();
        } else if (mFaceRunningState != BIOMETRIC_STATE_RUNNING && shouldListenForFace) {
            startListeningForFace();
        }
    }

    private boolean shouldListenForFingerprintAssistant() {
        BiometricAuthenticated fingerprint = mUserFingerprintAuthenticated.get(getCurrentUser());
        return mAssistantVisible && mKeyguardOccluded
                && !(fingerprint != null && fingerprint.mAuthenticated)
                && !mUserHasTrust.get(getCurrentUser(), false);
    }

    private boolean shouldListenForFaceAssistant() {
        BiometricAuthenticated face = mUserFaceAuthenticated.get(getCurrentUser());
        return mAssistantVisible && mKeyguardOccluded
                && !(face != null && face.mAuthenticated)
                && !mUserHasTrust.get(getCurrentUser(), false);
    }

    @VisibleForTesting
    protected boolean shouldListenForFingerprint(boolean isUdfps) {
        final int user = getCurrentUser();
        final boolean userDoesNotHaveTrust = !getUserHasTrust(user);
        final boolean shouldListenForFingerprintAssistant = shouldListenForFingerprintAssistant();
        final boolean shouldListenKeyguardState =
                mKeyguardIsVisible
                        || !mDeviceInteractive
                        || (mBouncer && !mKeyguardGoingAway)
                        || mGoingToSleep
                        || shouldListenForFingerprintAssistant
                        || (mKeyguardOccluded && mIsDreaming)
                        || (mKeyguardOccluded && userDoesNotHaveTrust
                            && (mOccludingAppRequestingFp || isUdfps));

        // Only listen if this KeyguardUpdateMonitor belongs to the primary user. There is an
        // instance of KeyguardUpdateMonitor for each user but KeyguardUpdateMonitor is user-aware.
        final boolean biometricEnabledForUser = mBiometricEnabledForUser.get(user);
        final boolean userCanSkipBouncer = getUserCanSkipBouncer(user);
        final boolean fingerprintDisabledForUser = isFingerprintDisabled(user);
        final boolean shouldListenUserState =
                !mSwitchingUser
                        && !fingerprintDisabledForUser
                        && (!mKeyguardGoingAway || !mDeviceInteractive)
                        && mIsPrimaryUser
                        && biometricEnabledForUser;

        final boolean shouldListenBouncerState =
                !(mFingerprintLockedOut && mBouncer && mCredentialAttempted);

        final boolean isEncryptedOrLockdownForUser = isEncryptedOrLockdown(user);
        final boolean userNeedsStrongAuth = userNeedsStrongAuth();
        final boolean shouldListenUdfpsState = !isUdfps
                || (!userCanSkipBouncer
                    && !isEncryptedOrLockdownForUser
                    && !userNeedsStrongAuth
                    && userDoesNotHaveTrust
                    && !mFingerprintLockedOut);

        final boolean shouldListen = shouldListenKeyguardState && shouldListenUserState
                && shouldListenBouncerState && shouldListenUdfpsState;

        if (DEBUG_FINGERPRINT || DEBUG_SPEW) {
            maybeLogListenerModelData(
                    new KeyguardFingerprintListenModel(
                        System.currentTimeMillis(),
                        user,
                        shouldListen,
                        biometricEnabledForUser,
                        mBouncer,
                        userCanSkipBouncer,
                        mCredentialAttempted,
                        mDeviceInteractive,
                        mIsDreaming,
                        isEncryptedOrLockdownForUser,
                        fingerprintDisabledForUser,
                        mFingerprintLockedOut,
                        mGoingToSleep,
                        mKeyguardGoingAway,
                        mKeyguardIsVisible,
                        mKeyguardOccluded,
                        mOccludingAppRequestingFp,
                        mIsPrimaryUser,
                        shouldListenForFingerprintAssistant,
                        mSwitchingUser,
                        isUdfps,
                        userDoesNotHaveTrust,
                        userNeedsStrongAuth));
        }

        return shouldListen;
    }

    /**
     * If face auth is allows to scan on this exact moment.
     */
    public boolean shouldListenForFace() {
        if (mFaceManager == null) {
            // Device does not have face auth
            return false;
        }

        final boolean statusBarShadeLocked = mStatusBarState == StatusBarState.SHADE_LOCKED;
        final boolean awakeKeyguard = mKeyguardIsVisible && mDeviceInteractive && !mGoingToSleep
                && !statusBarShadeLocked;
        final int user = getCurrentUser();
        final int strongAuth = mStrongAuthTracker.getStrongAuthForUser(user);
        final boolean isLockDown =
                containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW)
                        || containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
        final boolean isEncryptedOrTimedOut =
                containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_BOOT)
                        || containsFlag(strongAuth, STRONG_AUTH_REQUIRED_AFTER_TIMEOUT);

        final boolean canBypass = mKeyguardBypassController != null
                && mKeyguardBypassController.canBypass();
        // There's no reason to ask the HAL for authentication when the user can dismiss the
        // bouncer, unless we're bypassing and need to auto-dismiss the lock screen even when
        // TrustAgents or biometrics are keeping the device unlocked.
        final boolean becauseCannotSkipBouncer = !getUserCanSkipBouncer(user) || canBypass;

        // Scan even when encrypted or timeout to show a preemptive bouncer when bypassing.
        // Lock-down mode shouldn't scan, since it is more explicit.
        boolean strongAuthAllowsScanning = (!isEncryptedOrTimedOut || canBypass && !mBouncer);

        // If the device supports face detection (without authentication), allow it to happen
        // if the device is in lockdown mode. Otherwise, prevent scanning.
        final boolean supportsDetectOnly = !mFaceSensorProperties.isEmpty()
                && mFaceSensorProperties.get(0).supportsFaceDetection;
        if (isLockDown && !supportsDetectOnly) {
            strongAuthAllowsScanning = false;
        }

        // If the face has recently been authenticated do not attempt to authenticate again.
        final boolean faceAuthenticated = getIsFaceAuthenticated();
        final boolean faceDisabledForUser = isFaceDisabled(user);
        final boolean biometricEnabledForUser = mBiometricEnabledForUser.get(user);
        final boolean shouldListenForFaceAssistant = shouldListenForFaceAssistant();

        // Only listen if this KeyguardUpdateMonitor belongs to the primary user. There is an
        // instance of KeyguardUpdateMonitor for each user but KeyguardUpdateMonitor is user-aware.
        final boolean shouldListen =
                (mBouncer || mAuthInterruptActive || mOccludingAppRequestingFace || awakeKeyguard
                        || shouldListenForFaceAssistant)
                && !mSwitchingUser && !faceDisabledForUser && becauseCannotSkipBouncer
                && !mKeyguardGoingAway && biometricEnabledForUser && !mLockIconPressed
                && strongAuthAllowsScanning && mIsPrimaryUser
                && (!mSecureCameraLaunched || mOccludingAppRequestingFace)
                && !faceAuthenticated;

        // Aggregate relevant fields for debug logging.
        if (DEBUG_FACE || DEBUG_SPEW) {
            maybeLogListenerModelData(
                    new KeyguardFaceListenModel(
                        System.currentTimeMillis(),
                        user,
                        shouldListen,
                        mAuthInterruptActive,
                        becauseCannotSkipBouncer,
                        biometricEnabledForUser,
                        mBouncer,
                        faceAuthenticated,
                        faceDisabledForUser,
                        awakeKeyguard,
                        mKeyguardGoingAway,
                        shouldListenForFaceAssistant,
                        mLockIconPressed,
                        mOccludingAppRequestingFace,
                        mIsPrimaryUser,
                        strongAuthAllowsScanning,
                        mSecureCameraLaunched,
                        mSwitchingUser));
        }

        return shouldListen;
    }

    private void maybeLogListenerModelData(KeyguardListenModel model) {
        // Too chatty, but very useful when debugging issues.
        if (DEBUG_SPEW) {
            Log.v(TAG, model.toString());
        }

        // Add model data to the historical buffer.
        final boolean notYetRunning =
                (DEBUG_FACE
                    && model instanceof KeyguardFaceListenModel
                    && mFaceRunningState != BIOMETRIC_STATE_RUNNING)
                || (DEBUG_FINGERPRINT
                    && model instanceof KeyguardFingerprintListenModel
                    && mFingerprintRunningState != BIOMETRIC_STATE_RUNNING);
        if (notYetRunning && model.getListening()) {
            mListenModels.add(model);
        }
    }

    /**
     * Whenever the lock icon is long pressed, disabling trust agents.
     * This means that we cannot auth passively (face) until the user presses power.
     */
    public void onLockIconPressed() {
        mLockIconPressed = true;
        final int userId = getCurrentUser();
        mUserFaceAuthenticated.put(userId, null);
        updateFaceListeningState();
        mStrongAuthTracker.onStrongAuthRequiredChanged(userId);
    }

    private void startListeningForFingerprint() {
        final int userId = getCurrentUser();
        final boolean unlockPossible = isUnlockWithFingerprintPossible(userId);
        if (mFingerprintCancelSignal != null) {
            Log.e(TAG, "Cancellation signal is not null, high chance of bug in fp auth lifecycle"
                    + " management. FP state: " + mFingerprintRunningState
                    + ", unlockPossible: " + unlockPossible);
        }

        if (mFingerprintRunningState == BIOMETRIC_STATE_CANCELLING) {
            setFingerprintRunningState(BIOMETRIC_STATE_CANCELLING_RESTARTING);
            return;
        }
        if (mFingerprintRunningState == BIOMETRIC_STATE_CANCELLING_RESTARTING) {
            // Waiting for restart via handleFingerprintError().
            return;
        }
        if (DEBUG) Log.v(TAG, "startListeningForFingerprint()");

        if (unlockPossible) {
            mFingerprintCancelSignal = new CancellationSignal();

            if (isEncryptedOrLockdown(userId)) {
                mFpm.detectFingerprint(mFingerprintCancelSignal, mFingerprintDetectionCallback,
                        userId);
            } else {
                mFpm.authenticate(null /* crypto */, mFingerprintCancelSignal,
                        mFingerprintAuthenticationCallback, null /* handler */,
                        FingerprintManager.SENSOR_ID_ANY, userId);
            }
            setFingerprintRunningState(BIOMETRIC_STATE_RUNNING);
        }
    }

    private void startListeningForFace() {
        final int userId = getCurrentUser();
        final boolean unlockPossible = isUnlockWithFacePossible(userId);
        if (mFaceCancelSignal != null) {
            Log.e(TAG, "Cancellation signal is not null, high chance of bug in face auth lifecycle"
                    + " management. Face state: " + mFaceRunningState
                    + ", unlockPossible: " + unlockPossible);
        }

        if (mFaceRunningState == BIOMETRIC_STATE_CANCELLING) {
            setFaceRunningState(BIOMETRIC_STATE_CANCELLING_RESTARTING);
            return;
        } else if (mFaceRunningState == BIOMETRIC_STATE_CANCELLING_RESTARTING) {
            // Waiting for ERROR_CANCELED before requesting auth again
            return;
        }
        if (DEBUG) Log.v(TAG, "startListeningForFace(): " + mFaceRunningState);

        if (unlockPossible) {
            mFaceCancelSignal = new CancellationSignal();

            // This would need to be updated for multi-sensor devices
            final boolean supportsFaceDetection = !mFaceSensorProperties.isEmpty()
                    && mFaceSensorProperties.get(0).supportsFaceDetection;
            if (isEncryptedOrLockdown(userId) && supportsFaceDetection) {
                mFaceManager.detectFace(mFaceCancelSignal, mFaceDetectionCallback, userId);
            } else {
                final boolean isBypassEnabled = mKeyguardBypassController != null
                        && mKeyguardBypassController.isBypassEnabled();
                mFaceManager.authenticate(null /* crypto */, mFaceCancelSignal,
                        mFaceAuthenticationCallback, null /* handler */, userId, isBypassEnabled);
            }
            setFaceRunningState(BIOMETRIC_STATE_RUNNING);
        }
    }

    /**
     * If biometrics hardware is available, not disabled, and user has enrolled templates.
     * This does NOT check if the device is encrypted or in lockdown.
     *
     * @param userId User that's trying to unlock.
     * @return {@code true} if possible.
     */
    public boolean isUnlockingWithBiometricsPossible(int userId) {
        return isUnlockWithFacePossible(userId) || isUnlockWithFingerprintPossible(userId);
    }

    private boolean isUnlockWithFingerprintPossible(int userId) {
        return mFpm != null && mFpm.isHardwareDetected() && !isFingerprintDisabled(userId)
                && mFpm.hasEnrolledTemplates(userId);
    }

    private boolean isUnlockWithFacePossible(int userId) {
        return isFaceAuthEnabledForUser(userId) && !isFaceDisabled(userId);
    }

    /**
     * If face hardware is available, user has enrolled and enabled auth via setting.
     */
    public boolean isFaceAuthEnabledForUser(int userId) {
        updateFaceEnrolled(userId);
        return mIsFaceEnrolled;
    }

    private void stopListeningForFingerprint() {
        if (DEBUG) Log.v(TAG, "stopListeningForFingerprint()");
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

    private void stopListeningForFace() {
        if (DEBUG) Log.v(TAG, "stopListeningForFace()");
        if (mFaceRunningState == BIOMETRIC_STATE_RUNNING) {
            if (mFaceCancelSignal != null) {
                mFaceCancelSignal.cancel();
                mFaceCancelSignal = null;
                mHandler.removeCallbacks(mFaceCancelNotReceived);
                mHandler.postDelayed(mFaceCancelNotReceived, DEFAULT_CANCEL_SIGNAL_TIMEOUT);
            }
            setFaceRunningState(BIOMETRIC_STATE_CANCELLING);
        }
        if (mFaceRunningState == BIOMETRIC_STATE_CANCELLING_RESTARTING) {
            setFaceRunningState(BIOMETRIC_STATE_CANCELLING);
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
                if (DEBUG) Log.d(TAG, "DEVICE_PROVISIONED state = " + mDeviceProvisioned);
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
     * Update the state whether Keyguard currently has a lockscreen wallpaper.
     *
     * @param hasLockscreenWallpaper Whether Keyguard has a lockscreen wallpaper.
     */
    public void setHasLockscreenWallpaper(boolean hasLockscreenWallpaper) {
        Assert.isMainThread();
        if (hasLockscreenWallpaper != mHasLockscreenWallpaper) {
            mHasLockscreenWallpaper = hasLockscreenWallpaper;
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onHasLockscreenWallpaperChanged(hasLockscreenWallpaper);
                }
            }
        }
    }

    /**
     * @return Whether Keyguard has a lockscreen wallpaper.
     */
    public boolean hasLockscreenWallpaper() {
        return mHasLockscreenWallpaper;
    }

    /**
     * Handle {@link #MSG_DPM_STATE_CHANGED}
     */
    private void handleDevicePolicyManagerStateChanged(int userId) {
        Assert.isMainThread();
        updateFingerprintListeningState();
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
    void handleUserSwitching(int userId, IRemoteCallback reply) {
        Assert.isMainThread();
        clearBiometricRecognized();
        mUserTrustIsUsuallyManaged.put(userId, mTrustManager.isTrustUsuallyManaged(userId));
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserSwitching(userId);
            }
        }
        try {
            reply.sendResult(null);
        } catch (RemoteException e) {
        }
    }

    /**
     * Handle {@link #MSG_USER_SWITCH_COMPLETE}
     */
    @VisibleForTesting
    void handleUserSwitchComplete(int userId) {
        Assert.isMainThread();
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserSwitchComplete(userId);
            }
        }
        mInteractionJankMonitor.end(InteractionJankMonitor.CUJ_USER_SWITCH);
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
        if (DEBUG) Log.d(TAG, "handlePhoneStateChanged(" + newState + ")");
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
     * Handle {@link #MSG_RINGER_MODE_CHANGED}
     */
    private void handleRingerModeChange(int mode) {
        Assert.isMainThread();
        if (DEBUG) Log.d(TAG, "handleRingerModeChange(" + mode + ")");
        mRingMode = mode;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onRingerModeChanged(mode);
            }
        }
    }

    /**
     * Handle {@link #MSG_TIME_UPDATE}
     */
    private void handleTimeUpdate() {
        Assert.isMainThread();
        if (DEBUG) Log.d(TAG, "handleTimeUpdate");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTimeChanged();
            }
        }
    }

    /**
     * Handle {@link #MSG_LOCK_SCREEN_MODE}
     */
    private void handleLockScreenMode() {
        Assert.isMainThread();
        if (DEBUG) Log.d(TAG, "handleLockScreenMode(" + mLockScreenMode + ")");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onLockScreenModeChanged(mLockScreenMode);
            }
        }
    }

    /**
     * Handle (@line #MSG_TIMEZONE_UPDATE}
     */
    private void handleTimeZoneUpdate(String timeZone) {
        Assert.isMainThread();
        if (DEBUG) Log.d(TAG, "handleTimeZoneUpdate");
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
        if (DEBUG) Log.d(TAG, "handleTimeFormatUpdate timeFormat=" + timeFormat);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTimeFormatChanged(timeFormat);
            }
        }
    }

    /**
     * Handle {@link #MSG_BATTERY_UPDATE}
     */
    private void handleBatteryUpdate(BatteryStatus status) {
        Assert.isMainThread();
        if (DEBUG) Log.d(TAG, "handleBatteryUpdate");
        final boolean batteryUpdateInteresting = isBatteryUpdateInteresting(mBatteryStatus, status);
        mBatteryStatus = status;
        if (batteryUpdateInteresting) {
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
        if (DEBUG_SIM_STATES) {
            Log.d(TAG, "handleSimStateChange(subId=" + subId + ", slotId="
                    + slotId + ", state=" + state + ")");
        }

        boolean becameAbsent = false;
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            Log.w(TAG, "invalid subId in handleSimStateChange()");
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
            } else {
                return;
            }
        }

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
        if ((changed || becameAbsent) && state != TelephonyManager.SIM_STATE_UNKNOWN) {
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
        if (DEBUG) {
            Log.d(TAG,
                    "handleServiceStateChange(subId=" + subId + ", serviceState=" + serviceState);
        }

        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            Log.w(TAG, "invalid subId in handleServiceStateChange()");
            return;
        } else {
            updateTelephonyCapable(true);
        }

        mServiceStates.put(subId, serviceState);

        callbacksRefreshCarrierInfo();
    }

    public boolean isKeyguardVisible() {
        return mKeyguardIsVisible;
    }

    /**
     * Notifies that the visibility state of Keyguard has changed.
     *
     * <p>Needs to be called from the main thread.
     */
    public void onKeyguardVisibilityChanged(boolean showing) {
        Assert.isMainThread();
        Log.d(TAG, "onKeyguardVisibilityChanged(" + showing + ")");
        mKeyguardIsVisible = showing;

        if (showing) {
            mSecureCameraLaunched = false;
        }

        if (mKeyguardBypassController != null) {
            // LS visibility has changed, so reset deviceEntryIntent
            mKeyguardBypassController.setUserHasDeviceEntryIntent(false);
        }

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onKeyguardVisibilityChangedRaw(showing);
            }
        }
        updateBiometricListeningState();
    }

    /** Notifies that the occluded state changed. */
    public void onKeyguardOccludedChanged(boolean occluded) {
        Assert.isMainThread();
        if (DEBUG) {
            Log.d(TAG, "onKeyguardOccludedChanged(" + occluded + ")");
        }
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onKeyguardOccludedChanged(occluded);
            }
        }
    }

    /**
     * Handle {@link #MSG_KEYGUARD_RESET}
     */
    private void handleKeyguardReset() {
        if (DEBUG) Log.d(TAG, "handleKeyguardReset");
        updateBiometricListeningState();
        mNeedsSlowUnlockTransition = resolveNeedsSlowUnlockTransition();
    }

    private boolean resolveNeedsSlowUnlockTransition() {
        if (isUserUnlocked(getCurrentUser())) {
            return false;
        }
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = mContext.getPackageManager().resolveActivityAsUser(homeIntent,
                0 /* flags */, getCurrentUser());

        if (resolveInfo == null) {
            Log.w(TAG, "resolveNeedsSlowUnlockTransition: returning false since activity "
                    + "could not be resolved.");
            return false;
        }

        return FALLBACK_HOME_COMPONENT.equals(resolveInfo.getComponentInfo().getComponentName());
    }

    /**
     * Handle {@link #MSG_KEYGUARD_BOUNCER_CHANGED}
     *
     * @see #sendKeyguardBouncerChanged(boolean)
     */
    private void handleKeyguardBouncerChanged(int bouncerVisible) {
        Assert.isMainThread();
        if (DEBUG) Log.d(TAG, "handleKeyguardBouncerChanged(" + bouncerVisible + ")");
        mBouncer = bouncerVisible == 1;
        if (mBouncer) {
            // If the bouncer is shown, always clear this flag. This can happen in the following
            // situations: 1) Default camera with SHOW_WHEN_LOCKED is not chosen yet. 2) Secure
            // camera requests dismiss keyguard (tapping on photos for example). When these happen,
            // face auth should resume.
            mSecureCameraLaunched = false;
        } else {
            mCredentialAttempted = false;
        }

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onKeyguardBouncerChanged(mBouncer);
            }
        }
        updateBiometricListeningState();
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
        final boolean nowPresent = current.present;
        final boolean wasPresent = old.present;

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

        // Battery either showed up or disappeared
        if (wasPresent != nowPresent) {
            return true;
        }

        // change in battery overheat
        if (current.health != old.health) {
            return true;
        }

        return false;
    }

    private boolean isAutomotive() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    /**
     * Remove the given observer's callback.
     *
     * @param callback The callback to remove
     */
    public void removeCallback(KeyguardUpdateMonitorCallback callback) {
        Assert.isMainThread();
        if (DEBUG) {
            Log.v(TAG, "*** unregister callback for " + callback);
        }

        mCallbacks.removeIf(el -> el.get() == callback);
    }

    /**
     * Register to receive notifications about general keyguard information
     * (see {@link InfoCallback}.
     *
     * @param callback The callback to register
     */
    public void registerCallback(KeyguardUpdateMonitorCallback callback) {
        Assert.isMainThread();
        if (DEBUG) Log.v(TAG, "*** register callback for " + callback);
        // Prevent adding duplicate callbacks

        for (int i = 0; i < mCallbacks.size(); i++) {
            if (mCallbacks.get(i).get() == callback) {
                if (DEBUG) {
                    Log.e(TAG, "Object tried to add another callback",
                            new Exception("Called by"));
                }
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
        mSwitchingUser = switching;
        // Since this comes in on a binder thread, we need to post if first
        mHandler.post(mUpdateBiometricListeningState);
    }

    private void sendUpdates(KeyguardUpdateMonitorCallback callback) {
        // Notify listener of the current state
        callback.onRefreshBatteryInfo(mBatteryStatus);
        callback.onTimeChanged();
        callback.onRingerModeChanged(mRingMode);
        callback.onPhoneStateChanged(mPhoneState);
        callback.onRefreshCarrierInfo();
        callback.onClockVisibilityChanged();
        callback.onKeyguardOccludedChanged(mKeyguardOccluded);
        callback.onKeyguardVisibilityChangedRaw(mKeyguardIsVisible);
        callback.onTelephonyCapable(mTelephonyCapable);
        callback.onLockScreenModeChanged(mLockScreenMode);

        for (Entry<Integer, SimData> data : mSimDatas.entrySet()) {
            final SimData state = data.getValue();
            callback.onSimStateChanged(state.subId, state.slotId, state.simState);
        }
    }

    public void sendKeyguardReset() {
        mHandler.obtainMessage(MSG_KEYGUARD_RESET).sendToTarget();
    }

    /**
     * @see #handleKeyguardBouncerChanged(int)
     */
    public void sendKeyguardBouncerChanged(boolean bouncerIsOrWillBeShowing) {
        if (DEBUG) Log.d(TAG, "sendKeyguardBouncerChanged(" + bouncerIsOrWillBeShowing + ")");
        Message message = mHandler.obtainMessage(MSG_KEYGUARD_BOUNCER_CHANGED);
        message.arg1 = bouncerIsOrWillBeShowing ? 1 : 0;
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
        if (DEBUG_SIM_STATES) Log.v(TAG, "reportSimUnlocked(subId=" + subId + ")");
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

    public void clearBiometricRecognized() {
        Assert.isMainThread();
        mUserFingerprintAuthenticated.clear();
        mUserFaceAuthenticated.clear();
        mTrustManager.clearAllBiometricRecognized(BiometricSourceType.FINGERPRINT);
        mTrustManager.clearAllBiometricRecognized(BiometricSourceType.FACE);

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBiometricsCleared();
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
     * @see #isSimPinSecure(State)
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

    private final TaskStackChangeListener
            mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChangedBackground() {
            try {
                RootTaskInfo info = ActivityTaskManager.getService().getRootTaskInfo(
                        WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_ASSISTANT);
                if (info == null) {
                    return;
                }
                mHandler.sendMessage(mHandler.obtainMessage(MSG_ASSISTANT_STACK_CHANGED,
                        info.visible));
            } catch (RemoteException e) {
                Log.e(TAG, "unable to check task stack", e);
            }
        }
    };

    /**
     * @return true if and only if the state has changed for the specified {@code slotId}
     */
    private boolean refreshSimState(int subId, int slotId) {
        final TelephonyManager tele =
                (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        int state = (tele != null) ?
                tele.getSimState(slotId) : TelephonyManager.SIM_STATE_UNKNOWN;
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

    public DisplayClientState getCachedDisplayClientState() {
        return mDisplayClientState;
    }

    // TODO: use these callbacks elsewhere in place of the existing notifyScreen*()
    // (KeyguardViewMediator, KeyguardHostView)
    public void dispatchStartedWakingUp() {
        synchronized (this) {
            mDeviceInteractive = true;
        }
        mHandler.sendEmptyMessage(MSG_STARTED_WAKING_UP);
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

    public void dispatchScreenTurnedOn() {
        synchronized (this) {
            mScreenOn = true;
        }
        mHandler.sendEmptyMessage(MSG_SCREEN_TURNED_ON);
    }

    public void dispatchScreenTurnedOff() {
        synchronized (this) {
            mScreenOn = false;
        }
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

    /**
     * Unregister all listeners.
     */
    public void destroy() {
        // TODO: inject these dependencies:
        TelephonyManager telephony =
                (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (telephony != null) {
            mTelephonyListenerManager.removeActiveDataSubscriptionIdListener(mPhoneStateListener);
        }

        mSubscriptionManager.removeOnSubscriptionsChangedListener(mSubscriptionListener);

        if (mDeviceProvisionedObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mDeviceProvisionedObserver);
        }

        if (mTimeFormatChangeObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mTimeFormatChangeObserver);
        }

        try {
            ActivityManager.getService().unregisterUserSwitchObserver(mUserSwitchObserver);
        } catch (RemoteException e) {
            Log.d(TAG, "RemoteException onDestroy. cannot unregister userSwitchObserver");
        }

        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);

        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        mBroadcastDispatcher.unregisterReceiver(mBroadcastAllReceiver);
        mRingerModeTracker.getRingerMode().removeObserver(mRingerModeObserver);

        mLockPatternUtils.unregisterStrongAuthTracker(mStrongAuthTracker);
        mTrustManager.unregisterTrustListener(this);

        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardUpdateMonitor state:");
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
        if (mFpm != null && mFpm.isHardwareDetected()) {
            final int userId = ActivityManager.getCurrentUser();
            final int strongAuthFlags = mStrongAuthTracker.getStrongAuthForUser(userId);
            BiometricAuthenticated fingerprint = mUserFingerprintAuthenticated.get(userId);
            pw.println("  Fingerprint state (user=" + userId + ")");
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
            pw.println("    udfpsEnrolled=" + isUdfpsEnrolled());
            pw.println("    mFingerprintLockedOut=" + mFingerprintLockedOut);
            pw.println("    mFingerprintLockedOutPermanent=" + mFingerprintLockedOutPermanent);
            pw.println("    enabledByUser=" + mBiometricEnabledForUser.get(userId));
            if (isUdfpsEnrolled()) {
                pw.println("        shouldListenForUdfps=" + shouldListenForFingerprint(true));
                pw.println("        bouncerVisible=" + mBouncer);
                pw.println("        mStatusBarState="
                        + StatusBarState.toShortString(mStatusBarState));
            }
        }
        if (mFaceManager != null && mFaceManager.isHardwareDetected()) {
            final int userId = ActivityManager.getCurrentUser();
            final int strongAuthFlags = mStrongAuthTracker.getStrongAuthForUser(userId);
            BiometricAuthenticated face = mUserFaceAuthenticated.get(userId);
            pw.println("  Face authentication state (user=" + userId + ")");
            pw.println("    allowed="
                    + (face != null && isUnlockingWithBiometricAllowed(face.mIsStrongBiometric)));
            pw.println("    auth'd="
                    + (face != null && face.mAuthenticated));
            pw.println("    authSinceBoot="
                    + getStrongAuthTracker().hasUserAuthenticatedSinceBoot());
            pw.println("    disabled(DPM)=" + isFaceDisabled(userId));
            pw.println("    possible=" + isUnlockWithFacePossible(userId));
            pw.println("    listening: actual=" + mFaceRunningState
                    + " expected=(" + (shouldListenForFace() ? 1 : 0));
            pw.println("    strongAuthFlags=" + Integer.toHexString(strongAuthFlags));
            pw.println("    trustManaged=" + getUserTrustIsManaged(userId));
            pw.println("    mFaceLockedOutPermanent=" + mFaceLockedOutPermanent);
            pw.println("    enabledByUser=" + mBiometricEnabledForUser.get(userId));
            pw.println("    mSecureCameraLaunched=" + mSecureCameraLaunched);
        }
        mListenModels.print(pw);

        if (mIsAutomotive) {
            pw.println("  Running on Automotive build");
        }
    }
}
