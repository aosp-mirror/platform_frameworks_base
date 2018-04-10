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

package com.android.systemui.keyguard;

import static android.provider.Settings.System.SCREEN_OFF_TIMEOUT;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.internal.telephony.IccCardConstants.State.ABSENT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.view.ViewGroup;
import android.view.WindowManagerPolicyConstants;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardDrawnCallback;
import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardStateCallback;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardDisplayManager;
import com.android.keyguard.KeyguardSecurityView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.classifier.FalsingManager;
import com.android.systemui.statusbar.phone.FingerprintUnlockController;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Mediates requests related to the keyguard.  This includes queries about the
 * state of the keyguard, power management events that effect whether the keyguard
 * should be shown or reset, callbacks to the phone window manager to notify
 * it of when the keyguard is showing, and events from the keyguard view itself
 * stating that the keyguard was succesfully unlocked.
 *
 * Note that the keyguard view is shown when the screen is off (as appropriate)
 * so that once the screen comes on, it will be ready immediately.
 *
 * Example queries about the keyguard:
 * - is {movement, key} one that should wake the keygaurd?
 * - is the keyguard showing?
 * - are input events restricted due to the state of the keyguard?
 *
 * Callbacks to the phone window manager:
 * - the keyguard is showing
 *
 * Example external events that translate to keyguard view changes:
 * - screen turned off -> reset the keyguard, and show it so it will be ready
 *   next time the screen turns on
 * - keyboard is slid open -> if the keyguard is not secure, hide it
 *
 * Events from the keyguard view:
 * - user succesfully unlocked keyguard -> hide keyguard view, and no longer
 *   restrict input events.
 *
 * Note: in addition to normal power managment events that effect the state of
 * whether the keyguard should be showing, external apps and services may request
 * that the keyguard be disabled via {@link #setKeyguardEnabled(boolean)}.  When
 * false, this will override all other conditions for turning on the keyguard.
 *
 * Threading and synchronization:
 * This class is created by the initialization routine of the {@link WindowManagerPolicyConstants},
 * and runs on its thread.  The keyguard UI is created from that thread in the
 * constructor of this class.  The apis may be called from other threads, including the
 * {@link com.android.server.input.InputManagerService}'s and {@link android.view.WindowManager}'s.
 * Therefore, methods on this class are synchronized, and any action that is pointed
 * directly to the keyguard UI is posted to a {@link android.os.Handler} to ensure it is taken on the UI
 * thread of the keyguard.
 */
public class KeyguardViewMediator extends SystemUI {
    private static final int KEYGUARD_DISPLAY_TIMEOUT_DELAY_DEFAULT = 30000;
    private static final long KEYGUARD_DONE_PENDING_TIMEOUT_MS = 3000;

    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final boolean DEBUG_SIM_STATES = KeyguardConstants.DEBUG_SIM_STATES;

    private final static String TAG = "KeyguardViewMediator";

    private static final String DELAYED_KEYGUARD_ACTION =
        "com.android.internal.policy.impl.PhoneWindowManager.DELAYED_KEYGUARD";
    private static final String DELAYED_LOCK_PROFILE_ACTION =
            "com.android.internal.policy.impl.PhoneWindowManager.DELAYED_LOCK";

    // used for handler messages
    private static final int SHOW = 1;
    private static final int HIDE = 2;
    private static final int RESET = 3;
    private static final int VERIFY_UNLOCK = 4;
    private static final int NOTIFY_FINISHED_GOING_TO_SLEEP = 5;
    private static final int NOTIFY_SCREEN_TURNING_ON = 6;
    private static final int KEYGUARD_DONE = 7;
    private static final int KEYGUARD_DONE_DRAWING = 8;
    private static final int SET_OCCLUDED = 9;
    private static final int KEYGUARD_TIMEOUT = 10;
    private static final int DISMISS = 11;
    private static final int START_KEYGUARD_EXIT_ANIM = 12;
    private static final int KEYGUARD_DONE_PENDING_TIMEOUT = 13;
    private static final int NOTIFY_STARTED_WAKING_UP = 14;
    private static final int NOTIFY_SCREEN_TURNED_ON = 15;
    private static final int NOTIFY_SCREEN_TURNED_OFF = 16;
    private static final int NOTIFY_STARTED_GOING_TO_SLEEP = 17;

    /**
     * The default amount of time we stay awake (used for all key input)
     */
    public static final int AWAKE_INTERVAL_DEFAULT_MS = 10000;

    /**
     * How long to wait after the screen turns off due to timeout before
     * turning on the keyguard (i.e, the user has this much time to turn
     * the screen back on without having to face the keyguard).
     */
    private static final int KEYGUARD_LOCK_AFTER_DELAY_DEFAULT = 5000;

    /**
     * How long we'll wait for the {@link ViewMediatorCallback#keyguardDoneDrawing()}
     * callback before unblocking a call to {@link #setKeyguardEnabled(boolean)}
     * that is reenabling the keyguard.
     */
    private static final int KEYGUARD_DONE_DRAWING_TIMEOUT_MS = 2000;

    /**
     * Boolean option for doKeyguardLocked/doKeyguardTimeout which, when set to true, forces the
     * keyguard to show even if it is disabled for the current user.
     */
    public static final String OPTION_FORCE_SHOW = "force_show";

    /** The stream type that the lock sounds are tied to. */
    private int mUiSoundsStreamType;

    private AlarmManager mAlarmManager;
    private AudioManager mAudioManager;
    private StatusBarManager mStatusBarManager;
    private final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);

    private boolean mSystemReady;
    private boolean mBootCompleted;
    private boolean mBootSendUserPresent;
    private boolean mShuttingDown;

    /** High level access to the power manager for WakeLocks */
    private PowerManager mPM;

    /** TrustManager for letting it know when we change visibility */
    private TrustManager mTrustManager;

    /**
     * Used to keep the device awake while to ensure the keyguard finishes opening before
     * we sleep.
     */
    private PowerManager.WakeLock mShowKeyguardWakeLock;

    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    // these are protected by synchronized (this)

    /**
     * External apps (like the phone app) can tell us to disable the keygaurd.
     */
    private boolean mExternallyEnabled = true;

    /**
     * Remember if an external call to {@link #setKeyguardEnabled} with value
     * false caused us to hide the keyguard, so that we need to reshow it once
     * the keygaurd is reenabled with another call with value true.
     */
    private boolean mNeedToReshowWhenReenabled = false;

    // cached value of whether we are showing (need to know this to quickly
    // answer whether the input should be restricted)
    private boolean mShowing;

    // AOD is enabled and status bar is in AOD state.
    private boolean mAodShowing;

    // display id of the secondary display on which we have put a keyguard window
    private int mSecondaryDisplayShowing = INVALID_DISPLAY;

    /** Cached value of #isInputRestricted */
    private boolean mInputRestricted;

    // true if the keyguard is hidden by another window
    private boolean mOccluded = false;

    /**
     * Helps remember whether the screen has turned on since the last time
     * it turned off due to timeout. see {@link #onScreenTurnedOff(int)}
     */
    private int mDelayedShowingSequence;

    /**
     * Simiar to {@link #mDelayedProfileShowingSequence}, but it is for profile case.
     */
    private int mDelayedProfileShowingSequence;

    /**
     * If the user has disabled the keyguard, then requests to exit, this is
     * how we'll ultimately let them know whether it was successful.  We use this
     * var being non-null as an indicator that there is an in progress request.
     */
    private IKeyguardExitCallback mExitSecureCallback;
    private final DismissCallbackRegistry mDismissCallbackRegistry = new DismissCallbackRegistry();

    // the properties of the keyguard

    private KeyguardUpdateMonitor mUpdateMonitor;

    private boolean mDeviceInteractive;
    private boolean mGoingToSleep;

    // last known state of the cellular connection
    private String mPhoneState = TelephonyManager.EXTRA_STATE_IDLE;

    /**
     * Whether a hide is pending an we are just waiting for #startKeyguardExitAnimation to be
     * called.
     * */
    private boolean mHiding;

    /**
     * we send this intent when the keyguard is dismissed.
     */
    private static final Intent USER_PRESENT_INTENT = new Intent(Intent.ACTION_USER_PRESENT)
            .addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                    | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                    | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);

    /**
     * {@link #setKeyguardEnabled} waits on this condition when it reenables
     * the keyguard.
     */
    private boolean mWaitingUntilKeyguardVisible = false;
    private LockPatternUtils mLockPatternUtils;
    private boolean mKeyguardDonePending = false;
    private boolean mHideAnimationRun = false;
    private boolean mHideAnimationRunning = false;

    private SoundPool mLockSounds;
    private int mLockSoundId;
    private int mUnlockSoundId;
    private int mTrustedSoundId;
    private int mLockSoundStreamId;

    /**
     * The animation used for hiding keyguard. This is used to fetch the animation timings if
     * WindowManager is not providing us with them.
     */
    private Animation mHideAnimation;

    /**
     * The volume applied to the lock/unlock sounds.
     */
    private float mLockSoundVolume;

    /**
     * For managing external displays
     */
    private KeyguardDisplayManager mKeyguardDisplayManager;

    private final ArrayList<IKeyguardStateCallback> mKeyguardStateCallbacks = new ArrayList<>();

    /**
     * When starting going to sleep, we figured out that we need to reset Keyguard state and this
     * should be committed when finished going to sleep.
     */
    private boolean mPendingReset;

    /**
     * When starting going to sleep, we figured out that we need to lock Keyguard and this should be
     * committed when finished going to sleep.
     */
    private boolean mPendingLock;

    /**
     * Controller for showing individual "work challenge" lock screen windows inside managed profile
     * tasks when the current user has been unlocked but the profile is still locked.
     */
    private WorkLockActivityController mWorkLockController;

    private boolean mLockLater;

    private boolean mWakeAndUnlocking;
    private IKeyguardDrawnCallback mDrawnCallback;
    private boolean mLockWhenSimRemoved;
    private CharSequence mCustomMessage;

    KeyguardUpdateMonitorCallback mUpdateCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onUserSwitching(int userId) {
            // Note that the mLockPatternUtils user has already been updated from setCurrentUser.
            // We need to force a reset of the views, since lockNow (called by
            // ActivityManagerService) will not reconstruct the keyguard if it is already showing.
            synchronized (KeyguardViewMediator.this) {
                resetKeyguardDonePendingLocked();
                resetStateLocked();
                adjustStatusBarLocked();
            }
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            if (userId != UserHandle.USER_SYSTEM) {
                UserInfo info = UserManager.get(mContext).getUserInfo(userId);
                // Don't try to dismiss if the user has Pin/Patter/Password set
                if (info == null || mLockPatternUtils.isSecure(userId)) {
                    return;
                } else if (info.isGuest() || info.isDemo()) {
                    // If we just switched to a guest, try to dismiss keyguard.
                    dismiss(null /* callback */, null /* message */);
                }
            }
        }

        @Override
        public void onUserInfoChanged(int userId) {
        }

        @Override
        public void onPhoneStateChanged(int phoneState) {
            synchronized (KeyguardViewMediator.this) {
                if (TelephonyManager.CALL_STATE_IDLE == phoneState  // call ending
                        && !mDeviceInteractive                           // screen off
                        && mExternallyEnabled) {                // not disabled by any app

                    // note: this is a way to gracefully reenable the keyguard when the call
                    // ends and the screen is off without always reenabling the keyguard
                    // each time the screen turns off while in call (and having an occasional ugly
                    // flicker while turning back on the screen and disabling the keyguard again).
                    if (DEBUG) Log.d(TAG, "screen is off and call ended, let's make sure the "
                            + "keyguard is showing");
                    doKeyguardLocked(null);
                }
            }
        }

        @Override
        public void onClockVisibilityChanged() {
            adjustStatusBarLocked();
        }

        @Override
        public void onDeviceProvisioned() {
            sendUserPresentBroadcast();
            synchronized (KeyguardViewMediator.this) {
                // If system user is provisioned, we might want to lock now to avoid showing launcher
                if (mustNotUnlockCurrentUser()) {
                    doKeyguardLocked(null);
                }
            }
        }

        @Override
        public void onSimStateChanged(int subId, int slotId, IccCardConstants.State simState) {

            if (DEBUG_SIM_STATES) {
                Log.d(TAG, "onSimStateChanged(subId=" + subId + ", slotId=" + slotId
                        + ",state=" + simState + ")");
            }

            int size = mKeyguardStateCallbacks.size();
            boolean simPinSecure = mUpdateMonitor.isSimPinSecure();
            for (int i = size - 1; i >= 0; i--) {
                try {
                    mKeyguardStateCallbacks.get(i).onSimSecureStateChanged(simPinSecure);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onSimSecureStateChanged", e);
                    if (e instanceof DeadObjectException) {
                        mKeyguardStateCallbacks.remove(i);
                    }
                }
            }

            switch (simState) {
                case NOT_READY:
                case ABSENT:
                    // only force lock screen in case of missing sim if user hasn't
                    // gone through setup wizard
                    synchronized (KeyguardViewMediator.this) {
                        if (shouldWaitForProvisioning()) {
                            if (!mShowing) {
                                if (DEBUG_SIM_STATES) Log.d(TAG, "ICC_ABSENT isn't showing,"
                                        + " we need to show the keyguard since the "
                                        + "device isn't provisioned yet.");
                                doKeyguardLocked(null);
                            } else {
                                resetStateLocked();
                            }
                        }
                        if (simState == ABSENT) {
                            // MVNO SIMs can become transiently NOT_READY when switching networks,
                            // so we should only lock when they are ABSENT.
                            onSimAbsentLocked();
                        }
                    }
                    break;
                case PIN_REQUIRED:
                case PUK_REQUIRED:
                    synchronized (KeyguardViewMediator.this) {
                        if (!mShowing) {
                            if (DEBUG_SIM_STATES) Log.d(TAG,
                                    "INTENT_VALUE_ICC_LOCKED and keygaurd isn't "
                                    + "showing; need to show keyguard so user can enter sim pin");
                            doKeyguardLocked(null);
                        } else {
                            resetStateLocked();
                        }
                    }
                    break;
                case PERM_DISABLED:
                    synchronized (KeyguardViewMediator.this) {
                        if (!mShowing) {
                            if (DEBUG_SIM_STATES) Log.d(TAG, "PERM_DISABLED and "
                                  + "keygaurd isn't showing.");
                            doKeyguardLocked(null);
                        } else {
                            if (DEBUG_SIM_STATES) Log.d(TAG, "PERM_DISABLED, resetStateLocked to"
                                  + "show permanently disabled message in lockscreen.");
                            resetStateLocked();
                        }
                        onSimAbsentLocked();
                    }
                    break;
                case READY:
                    synchronized (KeyguardViewMediator.this) {
                        if (mShowing) {
                            resetStateLocked();
                        }
                        mLockWhenSimRemoved = true;
                    }
                    break;
                default:
                    if (DEBUG_SIM_STATES) Log.v(TAG, "Unspecific state: " + simState);
                    synchronized (KeyguardViewMediator.this) {
                        onSimAbsentLocked();
                    }
                    break;
            }
        }

        private void onSimAbsentLocked() {
            if (isSecure() && mLockWhenSimRemoved && !mShuttingDown) {
                mLockWhenSimRemoved = false;
                MetricsLogger.action(mContext,
                        MetricsProto.MetricsEvent.ACTION_LOCK_BECAUSE_SIM_REMOVED, mShowing);
                if (!mShowing) {
                    Log.i(TAG, "SIM removed, showing keyguard");
                    doKeyguardLocked(null);
                }
            }
        }

        @Override
        public void onFingerprintAuthFailed() {
            final int currentUser = KeyguardUpdateMonitor.getCurrentUser();
            if (mLockPatternUtils.isSecure(currentUser)) {
                mLockPatternUtils.getDevicePolicyManager().reportFailedFingerprintAttempt(
                        currentUser);
            }
        }

        @Override
        public void onFingerprintAuthenticated(int userId) {
            if (mLockPatternUtils.isSecure(userId)) {
                mLockPatternUtils.getDevicePolicyManager().reportSuccessfulFingerprintAttempt(
                        userId);
            }
        }

        @Override
        public void onTrustChanged(int userId) {
            if (userId == KeyguardUpdateMonitor.getCurrentUser()) {
                synchronized (KeyguardViewMediator.this) {
                    notifyTrustedChangedLocked(mUpdateMonitor.getUserHasTrust(userId));
                }
            }
        }

        @Override
        public void onHasLockscreenWallpaperChanged(boolean hasLockscreenWallpaper) {
            synchronized (KeyguardViewMediator.this) {
                notifyHasLockscreenWallpaperChanged(hasLockscreenWallpaper);
            }
        }
    };

    ViewMediatorCallback mViewMediatorCallback = new ViewMediatorCallback() {

        @Override
        public void userActivity() {
            KeyguardViewMediator.this.userActivity();
        }

        @Override
        public void keyguardDone(boolean strongAuth, int targetUserId) {
            if (targetUserId != ActivityManager.getCurrentUser()) {
                return;
            }

            tryKeyguardDone();
        }

        @Override
        public void keyguardDoneDrawing() {
            Trace.beginSection("KeyguardViewMediator.mViewMediatorCallback#keyguardDoneDrawing");
            mHandler.sendEmptyMessage(KEYGUARD_DONE_DRAWING);
            Trace.endSection();
        }

        @Override
        public void setNeedsInput(boolean needsInput) {
            mStatusBarKeyguardViewManager.setNeedsInput(needsInput);
        }

        @Override
        public void keyguardDonePending(boolean strongAuth, int targetUserId) {
            Trace.beginSection("KeyguardViewMediator.mViewMediatorCallback#keyguardDonePending");
            if (targetUserId != ActivityManager.getCurrentUser()) {
                Trace.endSection();
                return;
            }

            mKeyguardDonePending = true;
            mHideAnimationRun = true;
            mHideAnimationRunning = true;
            mStatusBarKeyguardViewManager.startPreHideAnimation(mHideAnimationFinishedRunnable);
            mHandler.sendEmptyMessageDelayed(KEYGUARD_DONE_PENDING_TIMEOUT,
                    KEYGUARD_DONE_PENDING_TIMEOUT_MS);
            Trace.endSection();
        }

        @Override
        public void keyguardGone() {
            Trace.beginSection("KeyguardViewMediator.mViewMediatorCallback#keyguardGone");
            mKeyguardDisplayManager.hide();
            Trace.endSection();
        }

        @Override
        public void readyForKeyguardDone() {
            Trace.beginSection("KeyguardViewMediator.mViewMediatorCallback#readyForKeyguardDone");
            if (mKeyguardDonePending) {
                mKeyguardDonePending = false;
                tryKeyguardDone();
            }
            Trace.endSection();
        }

        @Override
        public void resetKeyguard() {
            resetStateLocked();
        }

        @Override
        public void onBouncerVisiblityChanged(boolean shown) {
            synchronized (KeyguardViewMediator.this) {
                adjustStatusBarLocked(shown);
            }
        }

        @Override
        public void playTrustedSound() {
            KeyguardViewMediator.this.playTrustedSound();
        }

        @Override
        public boolean isScreenOn() {
            return mDeviceInteractive;
        }

        @Override
        public int getBouncerPromptReason() {
            int currentUser = ActivityManager.getCurrentUser();
            boolean trust = mTrustManager.isTrustUsuallyManaged(currentUser);
            boolean fingerprint = mUpdateMonitor.isUnlockWithFingerprintPossible(currentUser);
            boolean any = trust || fingerprint;
            KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                    mUpdateMonitor.getStrongAuthTracker();
            int strongAuth = strongAuthTracker.getStrongAuthForUser(currentUser);

            if (any && !strongAuthTracker.hasUserAuthenticatedSinceBoot()) {
                return KeyguardSecurityView.PROMPT_REASON_RESTART;
            } else if (any && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_TIMEOUT) != 0) {
                return KeyguardSecurityView.PROMPT_REASON_TIMEOUT;
            } else if (any && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW) != 0) {
                return KeyguardSecurityView.PROMPT_REASON_DEVICE_ADMIN;
            } else if (trust && (strongAuth & SOME_AUTH_REQUIRED_AFTER_USER_REQUEST) != 0) {
                return KeyguardSecurityView.PROMPT_REASON_USER_REQUEST;
            } else if (any && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_LOCKOUT) != 0) {
                return KeyguardSecurityView.PROMPT_REASON_AFTER_LOCKOUT;
            }
            return KeyguardSecurityView.PROMPT_REASON_NONE;
        }

        @Override
        public CharSequence consumeCustomMessage() {
            final CharSequence message = mCustomMessage;
            mCustomMessage = null;
            return message;
        }

        @Override
        public void onSecondaryDisplayShowingChanged(int displayId) {
            synchronized (KeyguardViewMediator.this) {
                setShowingLocked(mShowing, mAodShowing, displayId, false);
            }
        }
    };

    public void userActivity() {
        mPM.userActivity(SystemClock.uptimeMillis(), false);
    }

    boolean mustNotUnlockCurrentUser() {
        return UserManager.isSplitSystemUser()
                && KeyguardUpdateMonitor.getCurrentUser() == UserHandle.USER_SYSTEM;
    }

    private void setupLocked() {
        mPM = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mTrustManager = (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);

        mShowKeyguardWakeLock = mPM.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "show keyguard");
        mShowKeyguardWakeLock.setReferenceCounted(false);

        IntentFilter filter = new IntentFilter();
        filter.addAction(DELAYED_KEYGUARD_ACTION);
        filter.addAction(DELAYED_LOCK_PROFILE_ACTION);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mKeyguardDisplayManager = new KeyguardDisplayManager(mContext, mViewMediatorCallback);

        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);

        mLockPatternUtils = new LockPatternUtils(mContext);
        KeyguardUpdateMonitor.setCurrentUser(ActivityManager.getCurrentUser());

        // Assume keyguard is showing (unless it's disabled) until we know for sure, unless Keyguard
        // is disabled.
        if (mContext.getResources().getBoolean(
                com.android.keyguard.R.bool.config_enableKeyguardService)) {
            setShowingLocked(!shouldWaitForProvisioning()
                    && !mLockPatternUtils.isLockScreenDisabled(
                            KeyguardUpdateMonitor.getCurrentUser()),
                    mAodShowing, mSecondaryDisplayShowing, true /* forceCallbacks */);
        } else {
            // The system's keyguard is disabled or missing.
            setShowingLocked(false, mAodShowing, mSecondaryDisplayShowing, true);
        }

        mStatusBarKeyguardViewManager =
                SystemUIFactory.getInstance().createStatusBarKeyguardViewManager(mContext,
                        mViewMediatorCallback, mLockPatternUtils);
        final ContentResolver cr = mContext.getContentResolver();

        mDeviceInteractive = mPM.isInteractive();

        mLockSounds = new SoundPool(1, AudioManager.STREAM_SYSTEM, 0);
        String soundPath = Settings.Global.getString(cr, Settings.Global.LOCK_SOUND);
        if (soundPath != null) {
            mLockSoundId = mLockSounds.load(soundPath, 1);
        }
        if (soundPath == null || mLockSoundId == 0) {
            Log.w(TAG, "failed to load lock sound from " + soundPath);
        }
        soundPath = Settings.Global.getString(cr, Settings.Global.UNLOCK_SOUND);
        if (soundPath != null) {
            mUnlockSoundId = mLockSounds.load(soundPath, 1);
        }
        if (soundPath == null || mUnlockSoundId == 0) {
            Log.w(TAG, "failed to load unlock sound from " + soundPath);
        }
        soundPath = Settings.Global.getString(cr, Settings.Global.TRUSTED_SOUND);
        if (soundPath != null) {
            mTrustedSoundId = mLockSounds.load(soundPath, 1);
        }
        if (soundPath == null || mTrustedSoundId == 0) {
            Log.w(TAG, "failed to load trusted sound from " + soundPath);
        }

        int lockSoundDefaultAttenuation = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lockSoundVolumeDb);
        mLockSoundVolume = (float)Math.pow(10, (float)lockSoundDefaultAttenuation/20);

        mHideAnimation = AnimationUtils.loadAnimation(mContext,
                com.android.internal.R.anim.lock_screen_behind_enter);

        mWorkLockController = new WorkLockActivityController(mContext);
    }

    @Override
    public void start() {
        synchronized (this) {
            setupLocked();
        }
        putComponent(KeyguardViewMediator.class, this);
    }

    /**
     * Let us know that the system is ready after startup.
     */
    public void onSystemReady() {
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "onSystemReady");
            mSystemReady = true;
            doKeyguardLocked(null);
            mUpdateMonitor.registerCallback(mUpdateCallback);
        }
        // Most services aren't available until the system reaches the ready state, so we
        // send it here when the device first boots.
        maybeSendUserPresentBroadcast();
    }

    /**
     * Called to let us know the screen was turned off.
     * @param why either {@link WindowManagerPolicyConstants#OFF_BECAUSE_OF_USER} or
     *   {@link WindowManagerPolicyConstants#OFF_BECAUSE_OF_TIMEOUT}.
     */
    public void onStartedGoingToSleep(int why) {
        if (DEBUG) Log.d(TAG, "onStartedGoingToSleep(" + why + ")");
        synchronized (this) {
            mDeviceInteractive = false;
            mGoingToSleep = true;

            // Lock immediately based on setting if secure (user has a pin/pattern/password).
            // This also "locks" the device when not secure to provide easy access to the
            // camera while preventing unwanted input.
            int currentUser = KeyguardUpdateMonitor.getCurrentUser();
            final boolean lockImmediately =
                    mLockPatternUtils.getPowerButtonInstantlyLocks(currentUser)
                            || !mLockPatternUtils.isSecure(currentUser);
            long timeout = getLockTimeout(KeyguardUpdateMonitor.getCurrentUser());
            mLockLater = false;
            if (mExitSecureCallback != null) {
                if (DEBUG) Log.d(TAG, "pending exit secure callback cancelled");
                try {
                    mExitSecureCallback.onKeyguardExitResult(false);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onKeyguardExitResult(false)", e);
                }
                mExitSecureCallback = null;
                if (!mExternallyEnabled) {
                    hideLocked();
                }
            } else if (mShowing) {
                mPendingReset = true;
            } else if ((why == WindowManagerPolicyConstants.OFF_BECAUSE_OF_TIMEOUT && timeout > 0)
                    || (why == WindowManagerPolicyConstants.OFF_BECAUSE_OF_USER && !lockImmediately)) {
                doKeyguardLaterLocked(timeout);
                mLockLater = true;
            } else if (!mLockPatternUtils.isLockScreenDisabled(currentUser)) {
                mPendingLock = true;
            }

            if (mPendingLock) {
                playSounds(true);
            }
        }
        KeyguardUpdateMonitor.getInstance(mContext).dispatchStartedGoingToSleep(why);
        notifyStartedGoingToSleep();
    }

    public void onFinishedGoingToSleep(int why, boolean cameraGestureTriggered) {
        if (DEBUG) Log.d(TAG, "onFinishedGoingToSleep(" + why + ")");
        synchronized (this) {
            mDeviceInteractive = false;
            mGoingToSleep = false;
            mWakeAndUnlocking = false;

            resetKeyguardDonePendingLocked();
            mHideAnimationRun = false;

            notifyFinishedGoingToSleep();

            if (cameraGestureTriggered) {
                Log.i(TAG, "Camera gesture was triggered, preventing Keyguard locking.");

                // Just to make sure, make sure the device is awake.
                mContext.getSystemService(PowerManager.class).wakeUp(SystemClock.uptimeMillis(),
                        "com.android.systemui:CAMERA_GESTURE_PREVENT_LOCK");
                mPendingLock = false;
                mPendingReset = false;
            }

            if (mPendingReset) {
                resetStateLocked();
                mPendingReset = false;
            }

            if (mPendingLock) {
                doKeyguardLocked(null);
                mPendingLock = false;
            }

            // We do not have timeout and power button instant lock setting for profile lock.
            // So we use the personal setting if there is any. But if there is no device
            // we need to make sure we lock it immediately when the screen is off.
            if (!mLockLater && !cameraGestureTriggered) {
                doKeyguardForChildProfilesLocked();
            }

        }
        KeyguardUpdateMonitor.getInstance(mContext).dispatchFinishedGoingToSleep(why);
    }

    private long getLockTimeout(int userId) {
        // if the screen turned off because of timeout or the user hit the power button
        // and we don't need to lock immediately, set an alarm
        // to enable it a little bit later (i.e, give the user a chance
        // to turn the screen back on within a certain window without
        // having to unlock the screen)
        final ContentResolver cr = mContext.getContentResolver();

        // From SecuritySettings
        final long lockAfterTimeout = Settings.Secure.getInt(cr,
                Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
                KEYGUARD_LOCK_AFTER_DELAY_DEFAULT);

        // From DevicePolicyAdmin
        final long policyTimeout = mLockPatternUtils.getDevicePolicyManager()
                .getMaximumTimeToLock(null, userId);

        long timeout;

        if (policyTimeout <= 0) {
            timeout = lockAfterTimeout;
        } else {
            // From DisplaySettings
            long displayTimeout = Settings.System.getInt(cr, SCREEN_OFF_TIMEOUT,
                    KEYGUARD_DISPLAY_TIMEOUT_DELAY_DEFAULT);

            // policy in effect. Make sure we don't go beyond policy limit.
            displayTimeout = Math.max(displayTimeout, 0); // ignore negative values
            timeout = Math.min(policyTimeout - displayTimeout, lockAfterTimeout);
            timeout = Math.max(timeout, 0);
        }
        return timeout;
    }

    private void doKeyguardLaterLocked() {
        long timeout = getLockTimeout(KeyguardUpdateMonitor.getCurrentUser());
        if (timeout == 0) {
            doKeyguardLocked(null);
        } else {
            doKeyguardLaterLocked(timeout);
        }
    }

    private void doKeyguardLaterLocked(long timeout) {
        // Lock in the future
        long when = SystemClock.elapsedRealtime() + timeout;
        Intent intent = new Intent(DELAYED_KEYGUARD_ACTION);
        intent.putExtra("seq", mDelayedShowingSequence);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        PendingIntent sender = PendingIntent.getBroadcast(mContext,
                0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, sender);
        if (DEBUG) Log.d(TAG, "setting alarm to turn off keyguard, seq = "
                         + mDelayedShowingSequence);
        doKeyguardLaterForChildProfilesLocked();
    }

    private void doKeyguardLaterForChildProfilesLocked() {
        UserManager um = UserManager.get(mContext);
        for (int profileId : um.getEnabledProfileIds(UserHandle.myUserId())) {
            if (mLockPatternUtils.isSeparateProfileChallengeEnabled(profileId)) {
                long userTimeout = getLockTimeout(profileId);
                if (userTimeout == 0) {
                    doKeyguardForChildProfilesLocked();
                } else {
                    long userWhen = SystemClock.elapsedRealtime() + userTimeout;
                    Intent lockIntent = new Intent(DELAYED_LOCK_PROFILE_ACTION);
                    lockIntent.putExtra("seq", mDelayedProfileShowingSequence);
                    lockIntent.putExtra(Intent.EXTRA_USER_ID, profileId);
                    lockIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    PendingIntent lockSender = PendingIntent.getBroadcast(
                            mContext, 0, lockIntent, PendingIntent.FLAG_CANCEL_CURRENT);
                    mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            userWhen, lockSender);
                }
            }
        }
    }

    private void doKeyguardForChildProfilesLocked() {
        UserManager um = UserManager.get(mContext);
        for (int profileId : um.getEnabledProfileIds(UserHandle.myUserId())) {
            if (mLockPatternUtils.isSeparateProfileChallengeEnabled(profileId)) {
                lockProfile(profileId);
            }
        }
    }

    private void cancelDoKeyguardLaterLocked() {
        mDelayedShowingSequence++;
    }

    private void cancelDoKeyguardForChildProfilesLocked() {
        mDelayedProfileShowingSequence++;
    }

    /**
     * Let's us know when the device is waking up.
     */
    public void onStartedWakingUp() {
        Trace.beginSection("KeyguardViewMediator#onStartedWakingUp");

        // TODO: Rename all screen off/on references to interactive/sleeping
        synchronized (this) {
            mDeviceInteractive = true;
            cancelDoKeyguardLaterLocked();
            cancelDoKeyguardForChildProfilesLocked();
            if (DEBUG) Log.d(TAG, "onStartedWakingUp, seq = " + mDelayedShowingSequence);
            notifyStartedWakingUp();
        }
        KeyguardUpdateMonitor.getInstance(mContext).dispatchStartedWakingUp();
        maybeSendUserPresentBroadcast();
        Trace.endSection();
    }

    public void onScreenTurningOn(IKeyguardDrawnCallback callback) {
        Trace.beginSection("KeyguardViewMediator#onScreenTurningOn");
        notifyScreenOn(callback);
        Trace.endSection();
    }

    public void onScreenTurnedOn() {
        Trace.beginSection("KeyguardViewMediator#onScreenTurnedOn");
        notifyScreenTurnedOn();
        mUpdateMonitor.dispatchScreenTurnedOn();
        Trace.endSection();
    }

    public void onScreenTurnedOff() {
        notifyScreenTurnedOff();
        mUpdateMonitor.dispatchScreenTurnedOff();
    }

    private void maybeSendUserPresentBroadcast() {
        if (mSystemReady && mLockPatternUtils.isLockScreenDisabled(
                KeyguardUpdateMonitor.getCurrentUser())) {
            // Lock screen is disabled because the user has set the preference to "None".
            // In this case, send out ACTION_USER_PRESENT here instead of in
            // handleKeyguardDone()
            sendUserPresentBroadcast();
        } else if (mSystemReady && shouldWaitForProvisioning()) {
            // Skipping the lockscreen because we're not yet provisioned, but we still need to
            // notify the StrongAuthTracker that it's now safe to run trust agents, in case the
            // user sets a credential later.
            getLockPatternUtils().userPresent(KeyguardUpdateMonitor.getCurrentUser());
        }
    }

    /**
     * A dream started.  We should lock after the usual screen-off lock timeout but only
     * if there is a secure lock pattern.
     */
    public void onDreamingStarted() {
        KeyguardUpdateMonitor.getInstance(mContext).dispatchDreamingStarted();
        synchronized (this) {
            if (mDeviceInteractive
                    && mLockPatternUtils.isSecure(KeyguardUpdateMonitor.getCurrentUser())) {
                doKeyguardLaterLocked();
            }
        }
    }

    /**
     * A dream stopped.
     */
    public void onDreamingStopped() {
        KeyguardUpdateMonitor.getInstance(mContext).dispatchDreamingStopped();
        synchronized (this) {
            if (mDeviceInteractive) {
                cancelDoKeyguardLaterLocked();
            }
        }
    }

    /**
     * Same semantics as {@link WindowManagerPolicyConstants#enableKeyguard}; provide
     * a way for external stuff to override normal keyguard behavior.  For instance
     * the phone app disables the keyguard when it receives incoming calls.
     */
    public void setKeyguardEnabled(boolean enabled) {
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "setKeyguardEnabled(" + enabled + ")");

            mExternallyEnabled = enabled;

            if (!enabled && mShowing) {
                if (mExitSecureCallback != null) {
                    if (DEBUG) Log.d(TAG, "in process of verifyUnlock request, ignoring");
                    // we're in the process of handling a request to verify the user
                    // can get past the keyguard. ignore extraneous requests to disable / reenable
                    return;
                }

                // hiding keyguard that is showing, remember to reshow later
                if (DEBUG) Log.d(TAG, "remembering to reshow, hiding keyguard, "
                        + "disabling status bar expansion");
                mNeedToReshowWhenReenabled = true;
                updateInputRestrictedLocked();
                hideLocked();
            } else if (enabled && mNeedToReshowWhenReenabled) {
                // reenabled after previously hidden, reshow
                if (DEBUG) Log.d(TAG, "previously hidden, reshowing, reenabling "
                        + "status bar expansion");
                mNeedToReshowWhenReenabled = false;
                updateInputRestrictedLocked();

                if (mExitSecureCallback != null) {
                    if (DEBUG) Log.d(TAG, "onKeyguardExitResult(false), resetting");
                    try {
                        mExitSecureCallback.onKeyguardExitResult(false);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to call onKeyguardExitResult(false)", e);
                    }
                    mExitSecureCallback = null;
                    resetStateLocked();
                } else {
                    showLocked(null);

                    // block until we know the keygaurd is done drawing (and post a message
                    // to unblock us after a timeout so we don't risk blocking too long
                    // and causing an ANR).
                    mWaitingUntilKeyguardVisible = true;
                    mHandler.sendEmptyMessageDelayed(KEYGUARD_DONE_DRAWING, KEYGUARD_DONE_DRAWING_TIMEOUT_MS);
                    if (DEBUG) Log.d(TAG, "waiting until mWaitingUntilKeyguardVisible is false");
                    while (mWaitingUntilKeyguardVisible) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (DEBUG) Log.d(TAG, "done waiting for mWaitingUntilKeyguardVisible");
                }
            }
        }
    }

    /**
     * @see android.app.KeyguardManager#exitKeyguardSecurely
     */
    public void verifyUnlock(IKeyguardExitCallback callback) {
        Trace.beginSection("KeyguardViewMediator#verifyUnlock");
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "verifyUnlock");
            if (shouldWaitForProvisioning()) {
                // don't allow this api when the device isn't provisioned
                if (DEBUG) Log.d(TAG, "ignoring because device isn't provisioned");
                try {
                    callback.onKeyguardExitResult(false);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onKeyguardExitResult(false)", e);
                }
            } else if (mExternallyEnabled) {
                // this only applies when the user has externally disabled the
                // keyguard.  this is unexpected and means the user is not
                // using the api properly.
                Log.w(TAG, "verifyUnlock called when not externally disabled");
                try {
                    callback.onKeyguardExitResult(false);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onKeyguardExitResult(false)", e);
                }
            } else if (mExitSecureCallback != null) {
                // already in progress with someone else
                try {
                    callback.onKeyguardExitResult(false);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onKeyguardExitResult(false)", e);
                }
            } else if (!isSecure()) {

                // Keyguard is not secure, no need to do anything, and we don't need to reshow
                // the Keyguard after the client releases the Keyguard lock.
                mExternallyEnabled = true;
                mNeedToReshowWhenReenabled = false;
                updateInputRestricted();
                try {
                    callback.onKeyguardExitResult(true);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onKeyguardExitResult(false)", e);
                }
            } else {

                // Since we prevent apps from hiding the Keyguard if we are secure, this should be
                // a no-op as well.
                try {
                    callback.onKeyguardExitResult(false);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onKeyguardExitResult(false)", e);
                }
            }
        }
        Trace.endSection();
    }

    /**
     * Is the keyguard currently showing and not being force hidden?
     */
    public boolean isShowingAndNotOccluded() {
        return mShowing && !mOccluded;
    }

    /**
     * Notify us when the keyguard is occluded by another window
     */
    public void setOccluded(boolean isOccluded, boolean animate) {
        Trace.beginSection("KeyguardViewMediator#setOccluded");
        if (DEBUG) Log.d(TAG, "setOccluded " + isOccluded);
        mHandler.removeMessages(SET_OCCLUDED);
        Message msg = mHandler.obtainMessage(SET_OCCLUDED, isOccluded ? 1 : 0, animate ? 1 : 0);
        mHandler.sendMessage(msg);
        Trace.endSection();
    }

    public boolean isHiding() {
        return mHiding;
    }

    /**
     * Handles SET_OCCLUDED message sent by setOccluded()
     */
    private void handleSetOccluded(boolean isOccluded, boolean animate) {
        Trace.beginSection("KeyguardViewMediator#handleSetOccluded");
        synchronized (KeyguardViewMediator.this) {
            if (mHiding && isOccluded) {
                // We're in the process of going away but WindowManager wants to show a
                // SHOW_WHEN_LOCKED activity instead.
                startKeyguardExitAnimation(0, 0);
            }

            if (mOccluded != isOccluded) {
                mOccluded = isOccluded;
                mUpdateMonitor.setKeyguardOccluded(isOccluded);
                mStatusBarKeyguardViewManager.setOccluded(isOccluded, animate
                        && mDeviceInteractive);
                adjustStatusBarLocked();
            }
        }
        Trace.endSection();
    }

    /**
     * Used by PhoneWindowManager to enable the keyguard due to a user activity timeout.
     * This must be safe to call from any thread and with any window manager locks held.
     */
    public void doKeyguardTimeout(Bundle options) {
        mHandler.removeMessages(KEYGUARD_TIMEOUT);
        Message msg = mHandler.obtainMessage(KEYGUARD_TIMEOUT, options);
        mHandler.sendMessage(msg);
    }

    /**
     * Given the state of the keyguard, is the input restricted?
     * Input is restricted when the keyguard is showing, or when the keyguard
     * was suppressed by an app that disabled the keyguard or we haven't been provisioned yet.
     */
    public boolean isInputRestricted() {
        return mShowing || mNeedToReshowWhenReenabled;
    }

    private void updateInputRestricted() {
        synchronized (this) {
            updateInputRestrictedLocked();
        }
    }

    private void updateInputRestrictedLocked() {
        boolean inputRestricted = isInputRestricted();
        if (mInputRestricted != inputRestricted) {
            mInputRestricted = inputRestricted;
            int size = mKeyguardStateCallbacks.size();
            for (int i = size - 1; i >= 0; i--) {
                final IKeyguardStateCallback callback = mKeyguardStateCallbacks.get(i);
                try {
                    callback.onInputRestrictedStateChanged(inputRestricted);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onDeviceProvisioned", e);
                    if (e instanceof DeadObjectException) {
                        mKeyguardStateCallbacks.remove(callback);
                    }
                }
            }
        }
    }

    /**
     * Enable the keyguard if the settings are appropriate.
     */
    private void doKeyguardLocked(Bundle options) {
        if (KeyguardUpdateMonitor.CORE_APPS_ONLY) {
            // Don't show keyguard during half-booted cryptkeeper stage.
            if (DEBUG) Log.d(TAG, "doKeyguard: not showing because booting to cryptkeeper");
            return;
        }

        // if another app is disabling us, don't show
        if (!mExternallyEnabled) {
            if (DEBUG) Log.d(TAG, "doKeyguard: not showing because externally disabled");

            // note: we *should* set mNeedToReshowWhenReenabled=true here, but that makes
            // for an occasional ugly flicker in this situation:
            // 1) receive a call with the screen on (no keyguard) or make a call
            // 2) screen times out
            // 3) user hits key to turn screen back on
            // instead, we reenable the keyguard when we know the screen is off and the call
            // ends (see the broadcast receiver below)
            // TODO: clean this up when we have better support at the window manager level
            // for apps that wish to be on top of the keyguard
            return;
        }

        // if the keyguard is already showing, don't bother
        if (mStatusBarKeyguardViewManager.isShowing()) {
            if (DEBUG) Log.d(TAG, "doKeyguard: not showing because it is already showing");
            resetStateLocked();
            return;
        }

        // In split system user mode, we never unlock system user.
        if (!mustNotUnlockCurrentUser()
                || !mUpdateMonitor.isDeviceProvisioned()) {

            // if the setup wizard hasn't run yet, don't show
            final boolean requireSim = !SystemProperties.getBoolean("keyguard.no_require_sim", false);
            final boolean absent = SubscriptionManager.isValidSubscriptionId(
                    mUpdateMonitor.getNextSubIdForState(ABSENT));
            final boolean disabled = SubscriptionManager.isValidSubscriptionId(
                    mUpdateMonitor.getNextSubIdForState(IccCardConstants.State.PERM_DISABLED));
            final boolean lockedOrMissing = mUpdateMonitor.isSimPinSecure()
                    || ((absent || disabled) && requireSim);

            if (!lockedOrMissing && shouldWaitForProvisioning()) {
                if (DEBUG) Log.d(TAG, "doKeyguard: not showing because device isn't provisioned"
                        + " and the sim is not locked or missing");
                return;
            }

            boolean forceShow = options != null && options.getBoolean(OPTION_FORCE_SHOW, false);
            if (mLockPatternUtils.isLockScreenDisabled(KeyguardUpdateMonitor.getCurrentUser())
                    && !lockedOrMissing && !forceShow) {
                if (DEBUG) Log.d(TAG, "doKeyguard: not showing because lockscreen is off");
                return;
            }

            if (mLockPatternUtils.checkVoldPassword(KeyguardUpdateMonitor.getCurrentUser())) {
                if (DEBUG) Log.d(TAG, "Not showing lock screen since just decrypted");
                // Without this, settings is not enabled until the lock screen first appears
                setShowingLocked(false, mAodShowing);
                hideLocked();
                return;
            }
        }

        if (DEBUG) Log.d(TAG, "doKeyguard: showing the lock screen");
        showLocked(options);
    }

    private void lockProfile(int userId) {
        mTrustManager.setDeviceLockedForUser(userId, true);
    }

    private boolean shouldWaitForProvisioning() {
        return !mUpdateMonitor.isDeviceProvisioned() && !isSecure();
    }

    /**
     * Dismiss the keyguard through the security layers.
     * @param callback Callback to be informed about the result
     * @param message Message that should be displayed on the bouncer.
     */
    private void handleDismiss(IKeyguardDismissCallback callback, CharSequence message) {
        if (mShowing) {
            if (callback != null) {
                mDismissCallbackRegistry.addCallback(callback);
            }
            mCustomMessage = message;
            mStatusBarKeyguardViewManager.dismissAndCollapse();
        } else if (callback != null) {
            new DismissCallbackWrapper(callback).notifyDismissError();
        }
    }

    public void dismiss(IKeyguardDismissCallback callback, CharSequence message) {
        mHandler.obtainMessage(DISMISS, new DismissMessage(callback, message)).sendToTarget();
    }

    /**
     * Send message to keyguard telling it to reset its state.
     * @see #handleReset
     */
    private void resetStateLocked() {
        if (DEBUG) Log.e(TAG, "resetStateLocked");
        Message msg = mHandler.obtainMessage(RESET);
        mHandler.sendMessage(msg);
    }

    /**
     * Send message to keyguard telling it to verify unlock
     * @see #handleVerifyUnlock()
     */
    private void verifyUnlockLocked() {
        if (DEBUG) Log.d(TAG, "verifyUnlockLocked");
        mHandler.sendEmptyMessage(VERIFY_UNLOCK);
    }

    private void notifyStartedGoingToSleep() {
        if (DEBUG) Log.d(TAG, "notifyStartedGoingToSleep");
        mHandler.sendEmptyMessage(NOTIFY_STARTED_GOING_TO_SLEEP);
    }

    private void notifyFinishedGoingToSleep() {
        if (DEBUG) Log.d(TAG, "notifyFinishedGoingToSleep");
        mHandler.sendEmptyMessage(NOTIFY_FINISHED_GOING_TO_SLEEP);
    }

    private void notifyStartedWakingUp() {
        if (DEBUG) Log.d(TAG, "notifyStartedWakingUp");
        mHandler.sendEmptyMessage(NOTIFY_STARTED_WAKING_UP);
    }

    private void notifyScreenOn(IKeyguardDrawnCallback callback) {
        if (DEBUG) Log.d(TAG, "notifyScreenOn");
        Message msg = mHandler.obtainMessage(NOTIFY_SCREEN_TURNING_ON, callback);
        mHandler.sendMessage(msg);
    }

    private void notifyScreenTurnedOn() {
        if (DEBUG) Log.d(TAG, "notifyScreenTurnedOn");
        Message msg = mHandler.obtainMessage(NOTIFY_SCREEN_TURNED_ON);
        mHandler.sendMessage(msg);
    }

    private void notifyScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "notifyScreenTurnedOff");
        Message msg = mHandler.obtainMessage(NOTIFY_SCREEN_TURNED_OFF);
        mHandler.sendMessage(msg);
    }

    /**
     * Send message to keyguard telling it to show itself
     * @see #handleShow
     */
    private void showLocked(Bundle options) {
        Trace.beginSection("KeyguardViewMediator#showLocked aqcuiring mShowKeyguardWakeLock");
        if (DEBUG) Log.d(TAG, "showLocked");
        // ensure we stay awake until we are finished displaying the keyguard
        mShowKeyguardWakeLock.acquire();
        Message msg = mHandler.obtainMessage(SHOW, options);
        mHandler.sendMessage(msg);
        Trace.endSection();
    }

    /**
     * Send message to keyguard telling it to hide itself
     * @see #handleHide()
     */
    private void hideLocked() {
        Trace.beginSection("KeyguardViewMediator#hideLocked");
        if (DEBUG) Log.d(TAG, "hideLocked");
        Message msg = mHandler.obtainMessage(HIDE);
        mHandler.sendMessage(msg);
        Trace.endSection();
    }

    public boolean isSecure() {
        return isSecure(KeyguardUpdateMonitor.getCurrentUser());
    }

    public boolean isSecure(int userId) {
        return mLockPatternUtils.isSecure(userId)
                || KeyguardUpdateMonitor.getInstance(mContext).isSimPinSecure();
    }

    public void setSwitchingUser(boolean switching) {
        KeyguardUpdateMonitor.getInstance(mContext).setSwitchingUser(switching);
    }

    /**
     * Update the newUserId. Call while holding WindowManagerService lock.
     * NOTE: Should only be called by KeyguardViewMediator in response to the user id changing.
     *
     * @param newUserId The id of the incoming user.
     */
    public void setCurrentUser(int newUserId) {
        KeyguardUpdateMonitor.setCurrentUser(newUserId);
        synchronized (this) {
            notifyTrustedChangedLocked(mUpdateMonitor.getUserHasTrust(newUserId));
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DELAYED_KEYGUARD_ACTION.equals(intent.getAction())) {
                final int sequence = intent.getIntExtra("seq", 0);
                if (DEBUG) Log.d(TAG, "received DELAYED_KEYGUARD_ACTION with seq = "
                        + sequence + ", mDelayedShowingSequence = " + mDelayedShowingSequence);
                synchronized (KeyguardViewMediator.this) {
                    if (mDelayedShowingSequence == sequence) {
                        doKeyguardLocked(null);
                    }
                }
            } else if (DELAYED_LOCK_PROFILE_ACTION.equals(intent.getAction())) {
                final int sequence = intent.getIntExtra("seq", 0);
                int userId = intent.getIntExtra(Intent.EXTRA_USER_ID, 0);
                if (userId != 0) {
                    synchronized (KeyguardViewMediator.this) {
                        if (mDelayedProfileShowingSequence == sequence) {
                            lockProfile(userId);
                        }
                    }
                }
            } else if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                synchronized (KeyguardViewMediator.this){
                    mShuttingDown = true;
                }
            }
        }
    };

    public void keyguardDone() {
        Trace.beginSection("KeyguardViewMediator#keyguardDone");
        if (DEBUG) Log.d(TAG, "keyguardDone()");
        userActivity();
        EventLog.writeEvent(70000, 2);
        Message msg = mHandler.obtainMessage(KEYGUARD_DONE);
        mHandler.sendMessage(msg);
        Trace.endSection();
    }

    /**
     * This handler will be associated with the policy thread, which will also
     * be the UI thread of the keyguard.  Since the apis of the policy, and therefore
     * this class, can be called by other threads, any action that directly
     * interacts with the keyguard ui should be posted to this handler, rather
     * than called directly.
     */
    private Handler mHandler = new Handler(Looper.myLooper(), null, true /*async*/) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SHOW:
                    handleShow((Bundle) msg.obj);
                    break;
                case HIDE:
                    handleHide();
                    break;
                case RESET:
                    handleReset();
                    break;
                case VERIFY_UNLOCK:
                    Trace.beginSection("KeyguardViewMediator#handleMessage VERIFY_UNLOCK");
                    handleVerifyUnlock();
                    Trace.endSection();
                    break;
                case NOTIFY_STARTED_GOING_TO_SLEEP:
                    handleNotifyStartedGoingToSleep();
                    break;
                case NOTIFY_FINISHED_GOING_TO_SLEEP:
                    handleNotifyFinishedGoingToSleep();
                    break;
                case NOTIFY_SCREEN_TURNING_ON:
                    Trace.beginSection("KeyguardViewMediator#handleMessage NOTIFY_SCREEN_TURNING_ON");
                    handleNotifyScreenTurningOn((IKeyguardDrawnCallback) msg.obj);
                    Trace.endSection();
                    break;
                case NOTIFY_SCREEN_TURNED_ON:
                    Trace.beginSection("KeyguardViewMediator#handleMessage NOTIFY_SCREEN_TURNED_ON");
                    handleNotifyScreenTurnedOn();
                    Trace.endSection();
                    break;
                case NOTIFY_SCREEN_TURNED_OFF:
                    handleNotifyScreenTurnedOff();
                    break;
                case NOTIFY_STARTED_WAKING_UP:
                    Trace.beginSection("KeyguardViewMediator#handleMessage NOTIFY_STARTED_WAKING_UP");
                    handleNotifyStartedWakingUp();
                    Trace.endSection();
                    break;
                case KEYGUARD_DONE:
                    Trace.beginSection("KeyguardViewMediator#handleMessage KEYGUARD_DONE");
                    handleKeyguardDone();
                    Trace.endSection();
                    break;
                case KEYGUARD_DONE_DRAWING:
                    Trace.beginSection("KeyguardViewMediator#handleMessage KEYGUARD_DONE_DRAWING");
                    handleKeyguardDoneDrawing();
                    Trace.endSection();
                    break;
                case SET_OCCLUDED:
                    Trace.beginSection("KeyguardViewMediator#handleMessage SET_OCCLUDED");
                    handleSetOccluded(msg.arg1 != 0, msg.arg2 != 0);
                    Trace.endSection();
                    break;
                case KEYGUARD_TIMEOUT:
                    synchronized (KeyguardViewMediator.this) {
                        doKeyguardLocked((Bundle) msg.obj);
                    }
                    break;
                case DISMISS:
                    final DismissMessage message = (DismissMessage) msg.obj;
                    handleDismiss(message.getCallback(), message.getMessage());
                    break;
                case START_KEYGUARD_EXIT_ANIM:
                    Trace.beginSection("KeyguardViewMediator#handleMessage START_KEYGUARD_EXIT_ANIM");
                    StartKeyguardExitAnimParams params = (StartKeyguardExitAnimParams) msg.obj;
                    handleStartKeyguardExitAnimation(params.startTime, params.fadeoutDuration);
                    FalsingManager.getInstance(mContext).onSucccessfulUnlock();
                    Trace.endSection();
                    break;
                case KEYGUARD_DONE_PENDING_TIMEOUT:
                    Trace.beginSection("KeyguardViewMediator#handleMessage KEYGUARD_DONE_PENDING_TIMEOUT");
                    Log.w(TAG, "Timeout while waiting for activity drawn!");
                    Trace.endSection();
                    break;
            }
        }
    };

    private void tryKeyguardDone() {
        if (!mKeyguardDonePending && mHideAnimationRun && !mHideAnimationRunning) {
            handleKeyguardDone();
        } else if (!mHideAnimationRun) {
            mHideAnimationRun = true;
            mHideAnimationRunning = true;
            mStatusBarKeyguardViewManager.startPreHideAnimation(mHideAnimationFinishedRunnable);
        }
    }

    /**
     * @see #keyguardDone
     * @see #KEYGUARD_DONE
     */
    private void handleKeyguardDone() {
        Trace.beginSection("KeyguardViewMediator#handleKeyguardDone");
        final int currentUser = KeyguardUpdateMonitor.getCurrentUser();
        mUiOffloadThread.submit(() -> {
            if (mLockPatternUtils.isSecure(currentUser)) {
                mLockPatternUtils.getDevicePolicyManager().reportKeyguardDismissed(currentUser);
            }
        });
        if (DEBUG) Log.d(TAG, "handleKeyguardDone");
        synchronized (this) {
            resetKeyguardDonePendingLocked();
        }

        mUpdateMonitor.clearFailedUnlockAttempts();
        mUpdateMonitor.clearFingerprintRecognized();

        if (mGoingToSleep) {
            Log.i(TAG, "Device is going to sleep, aborting keyguardDone");
            return;
        }
        if (mExitSecureCallback != null) {
            try {
                mExitSecureCallback.onKeyguardExitResult(true /* authenciated */);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call onKeyguardExitResult()", e);
            }

            mExitSecureCallback = null;

            // after succesfully exiting securely, no need to reshow
            // the keyguard when they've released the lock
            mExternallyEnabled = true;
            mNeedToReshowWhenReenabled = false;
            updateInputRestricted();
        }

        handleHide();
        Trace.endSection();
    }

    private void sendUserPresentBroadcast() {
        synchronized (this) {
            if (mBootCompleted) {
                int currentUserId = KeyguardUpdateMonitor.getCurrentUser();
                final UserHandle currentUser = new UserHandle(currentUserId);
                final UserManager um = (UserManager) mContext.getSystemService(
                        Context.USER_SERVICE);
                mUiOffloadThread.submit(() -> {
                    for (int profileId : um.getProfileIdsWithDisabled(currentUser.getIdentifier())) {
                        mContext.sendBroadcastAsUser(USER_PRESENT_INTENT, UserHandle.of(profileId));
                    }
                    getLockPatternUtils().userPresent(currentUserId);
                });
            } else {
                mBootSendUserPresent = true;
            }
        }
    }

    /**
     * @see #keyguardDone
     * @see #KEYGUARD_DONE_DRAWING
     */
    private void handleKeyguardDoneDrawing() {
        Trace.beginSection("KeyguardViewMediator#handleKeyguardDoneDrawing");
        synchronized(this) {
            if (DEBUG) Log.d(TAG, "handleKeyguardDoneDrawing");
            if (mWaitingUntilKeyguardVisible) {
                if (DEBUG) Log.d(TAG, "handleKeyguardDoneDrawing: notifying mWaitingUntilKeyguardVisible");
                mWaitingUntilKeyguardVisible = false;
                notifyAll();

                // there will usually be two of these sent, one as a timeout, and one
                // as a result of the callback, so remove any remaining messages from
                // the queue
                mHandler.removeMessages(KEYGUARD_DONE_DRAWING);
            }
        }
        Trace.endSection();
    }

    private void playSounds(boolean locked) {
        playSound(locked ? mLockSoundId : mUnlockSoundId);
    }

    private void playSound(int soundId) {
        if (soundId == 0) return;
        final ContentResolver cr = mContext.getContentResolver();
        if (Settings.System.getInt(cr, Settings.System.LOCKSCREEN_SOUNDS_ENABLED, 1) == 1) {

            mLockSounds.stop(mLockSoundStreamId);
            // Init mAudioManager
            if (mAudioManager == null) {
                mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                if (mAudioManager == null) return;
                mUiSoundsStreamType = mAudioManager.getUiSoundsStreamType();
            }

            mUiOffloadThread.submit(() -> {
                // If the stream is muted, don't play the sound
                if (mAudioManager.isStreamMute(mUiSoundsStreamType)) return;

                int id = mLockSounds.play(soundId,
                        mLockSoundVolume, mLockSoundVolume, 1/*priortiy*/, 0/*loop*/, 1.0f/*rate*/);
                synchronized (this) {
                    mLockSoundStreamId = id;
                }
            });

        }
    }

    private void playTrustedSound() {
        playSound(mTrustedSoundId);
    }

    private void updateActivityLockScreenState(boolean showing, boolean aodShowing,
            int secondaryDisplayShowing) {
        mUiOffloadThread.submit(() -> {
            try {
                ActivityManager.getService().setLockScreenShown(showing, aodShowing,
                        secondaryDisplayShowing);
            } catch (RemoteException e) {
            }
        });
    }

    /**
     * Handle message sent by {@link #showLocked}.
     * @see #SHOW
     */
    private void handleShow(Bundle options) {
        Trace.beginSection("KeyguardViewMediator#handleShow");
        final int currentUser = KeyguardUpdateMonitor.getCurrentUser();
        if (mLockPatternUtils.isSecure(currentUser)) {
            mLockPatternUtils.getDevicePolicyManager().reportKeyguardSecured(currentUser);
        }
        synchronized (KeyguardViewMediator.this) {
            if (!mSystemReady) {
                if (DEBUG) Log.d(TAG, "ignoring handleShow because system is not ready.");
                return;
            } else {
                if (DEBUG) Log.d(TAG, "handleShow");
            }

            setShowingLocked(true, mAodShowing);
            mStatusBarKeyguardViewManager.show(options);
            mHiding = false;
            mWakeAndUnlocking = false;
            resetKeyguardDonePendingLocked();
            mHideAnimationRun = false;
            adjustStatusBarLocked();
            userActivity();
            mShowKeyguardWakeLock.release();
        }
        mKeyguardDisplayManager.show();
        Trace.endSection();
    }

    private final Runnable mKeyguardGoingAwayRunnable = new Runnable() {
        @Override
        public void run() {
            Trace.beginSection("KeyguardViewMediator.mKeyGuardGoingAwayRunnable");
            if (DEBUG) Log.d(TAG, "keyguardGoingAway");
            try {
                mStatusBarKeyguardViewManager.keyguardGoingAway();

                int flags = 0;
                if (mStatusBarKeyguardViewManager.shouldDisableWindowAnimationsForUnlock()
                        || mWakeAndUnlocking) {
                    flags |= WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS;
                }
                if (mStatusBarKeyguardViewManager.isGoingToNotificationShade()) {
                    flags |= WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_TO_SHADE;
                }
                if (mStatusBarKeyguardViewManager.isUnlockWithWallpaper()) {
                    flags |= WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;
                }

                mUpdateMonitor.setKeyguardGoingAway(true /* goingAway */);
                // Don't actually hide the Keyguard at the moment, wait for window
                // manager until it tells us it's safe to do so with
                // startKeyguardExitAnimation.
                ActivityManager.getService().keyguardGoingAway(flags);
            } catch (RemoteException e) {
                Log.e(TAG, "Error while calling WindowManager", e);
            }
            Trace.endSection();
        }
    };

    private final Runnable mHideAnimationFinishedRunnable = () -> {
        mHideAnimationRunning = false;
        tryKeyguardDone();
    };

    /**
     * Handle message sent by {@link #hideLocked()}
     * @see #HIDE
     */
    private void handleHide() {
        Trace.beginSection("KeyguardViewMediator#handleHide");
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleHide");

            if (mustNotUnlockCurrentUser()) {
                // In split system user mode, we never unlock system user. The end user has to
                // switch to another user.
                // TODO: We should stop it early by disabling the swipe up flow. Right now swipe up
                // still completes and makes the screen blank.
                if (DEBUG) Log.d(TAG, "Split system user, quit unlocking.");
                return;
            }
            mHiding = true;

            if (mShowing && !mOccluded) {
                mKeyguardGoingAwayRunnable.run();
            } else {
                handleStartKeyguardExitAnimation(
                        SystemClock.uptimeMillis() + mHideAnimation.getStartOffset(),
                        mHideAnimation.getDuration());
            }
        }
        Trace.endSection();
    }

    private void handleStartKeyguardExitAnimation(long startTime, long fadeoutDuration) {
        Trace.beginSection("KeyguardViewMediator#handleStartKeyguardExitAnimation");
        if (DEBUG) Log.d(TAG, "handleStartKeyguardExitAnimation startTime=" + startTime
                + " fadeoutDuration=" + fadeoutDuration);
        synchronized (KeyguardViewMediator.this) {

            if (!mHiding) {
                return;
            }
            mHiding = false;

            if (mWakeAndUnlocking && mDrawnCallback != null) {

                // Hack level over 9000: To speed up wake-and-unlock sequence, force it to report
                // the next draw from here so we don't have to wait for window manager to signal
                // this to our ViewRootImpl.
                mStatusBarKeyguardViewManager.getViewRootImpl().setReportNextDraw();
                notifyDrawn(mDrawnCallback);
                mDrawnCallback = null;
            }

            // only play "unlock" noises if not on a call (since the incall UI
            // disables the keyguard)
            if (TelephonyManager.EXTRA_STATE_IDLE.equals(mPhoneState)) {
                playSounds(false);
            }

            mWakeAndUnlocking = false;
            setShowingLocked(false, mAodShowing);
            mDismissCallbackRegistry.notifyDismissSucceeded();
            mStatusBarKeyguardViewManager.hide(startTime, fadeoutDuration);
            resetKeyguardDonePendingLocked();
            mHideAnimationRun = false;
            adjustStatusBarLocked();
            sendUserPresentBroadcast();
            mUpdateMonitor.setKeyguardGoingAway(false /* goingAway */);
        }
        Trace.endSection();
    }

    private void adjustStatusBarLocked() {
        adjustStatusBarLocked(false /* forceHideHomeRecentsButtons */);
    }

    private void adjustStatusBarLocked(boolean forceHideHomeRecentsButtons) {
        if (mStatusBarManager == null) {
            mStatusBarManager = (StatusBarManager)
                    mContext.getSystemService(Context.STATUS_BAR_SERVICE);
        }
        if (mStatusBarManager == null) {
            Log.w(TAG, "Could not get status bar manager");
        } else {
            // Disable aspects of the system/status/navigation bars that must not be re-enabled by
            // windows that appear on top, ever
            int flags = StatusBarManager.DISABLE_NONE;
            if (forceHideHomeRecentsButtons || isShowingAndNotOccluded()) {
                flags |= StatusBarManager.DISABLE_HOME | StatusBarManager.DISABLE_RECENT;
            }

            if (DEBUG) {
                Log.d(TAG, "adjustStatusBarLocked: mShowing=" + mShowing + " mOccluded=" + mOccluded
                        + " isSecure=" + isSecure() + " force=" + forceHideHomeRecentsButtons
                        +  " --> flags=0x" + Integer.toHexString(flags));
            }

            mStatusBarManager.disable(flags);
        }
    }

    /**
     * Handle message sent by {@link #resetStateLocked}
     * @see #RESET
     */
    private void handleReset() {
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleReset");
            mStatusBarKeyguardViewManager.reset(true /* hideBouncerWhenShowing */);
        }
    }

    /**
     * Handle message sent by {@link #verifyUnlock}
     * @see #VERIFY_UNLOCK
     */
    private void handleVerifyUnlock() {
        Trace.beginSection("KeyguardViewMediator#handleVerifyUnlock");
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleVerifyUnlock");
            setShowingLocked(true, mAodShowing);
            mStatusBarKeyguardViewManager.dismissAndCollapse();
        }
        Trace.endSection();
    }

    private void handleNotifyStartedGoingToSleep() {
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleNotifyStartedGoingToSleep");
            mStatusBarKeyguardViewManager.onStartedGoingToSleep();
        }
    }

    /**
     * Handle message sent by {@link #notifyFinishedGoingToSleep()}
     * @see #NOTIFY_FINISHED_GOING_TO_SLEEP
     */
    private void handleNotifyFinishedGoingToSleep() {
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleNotifyFinishedGoingToSleep");
            mStatusBarKeyguardViewManager.onFinishedGoingToSleep();
        }
    }

    private void handleNotifyStartedWakingUp() {
        Trace.beginSection("KeyguardViewMediator#handleMotifyStartedWakingUp");
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleNotifyWakingUp");
            mStatusBarKeyguardViewManager.onStartedWakingUp();
        }
        Trace.endSection();
    }

    private void handleNotifyScreenTurningOn(IKeyguardDrawnCallback callback) {
        Trace.beginSection("KeyguardViewMediator#handleNotifyScreenTurningOn");
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleNotifyScreenTurningOn");
            mStatusBarKeyguardViewManager.onScreenTurningOn();
            if (callback != null) {
                if (mWakeAndUnlocking) {
                    mDrawnCallback = callback;
                } else {
                    notifyDrawn(callback);
                }
            }
        }
        Trace.endSection();
    }

    private void handleNotifyScreenTurnedOn() {
        Trace.beginSection("KeyguardViewMediator#handleNotifyScreenTurnedOn");
        if (LatencyTracker.isEnabled(mContext)) {
            LatencyTracker.getInstance(mContext).onActionEnd(LatencyTracker.ACTION_TURN_ON_SCREEN);
        }
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "handleNotifyScreenTurnedOn");
            mStatusBarKeyguardViewManager.onScreenTurnedOn();
        }
        Trace.endSection();
    }

    private void handleNotifyScreenTurnedOff() {
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "handleNotifyScreenTurnedOff");
            mStatusBarKeyguardViewManager.onScreenTurnedOff();
            mDrawnCallback = null;
        }
    }

    private void notifyDrawn(final IKeyguardDrawnCallback callback) {
        Trace.beginSection("KeyguardViewMediator#notifyDrawn");
        try {
            callback.onDrawn();
        } catch (RemoteException e) {
            Slog.w(TAG, "Exception calling onDrawn():", e);
        }
        Trace.endSection();
    }

    private void resetKeyguardDonePendingLocked() {
        mKeyguardDonePending = false;
        mHandler.removeMessages(KEYGUARD_DONE_PENDING_TIMEOUT);
    }

    @Override
    public void onBootCompleted() {
        mUpdateMonitor.dispatchBootCompleted();
        synchronized (this) {
            mBootCompleted = true;
            if (mBootSendUserPresent) {
                sendUserPresentBroadcast();
            }
        }
    }

    public void onWakeAndUnlocking() {
        Trace.beginSection("KeyguardViewMediator#onWakeAndUnlocking");
        mWakeAndUnlocking = true;
        keyguardDone();
        Trace.endSection();
    }

    public StatusBarKeyguardViewManager registerStatusBar(StatusBar statusBar,
            ViewGroup container, NotificationPanelView panelView,
            FingerprintUnlockController fingerprintUnlockController) {
        mStatusBarKeyguardViewManager.registerStatusBar(statusBar, container, panelView,
                fingerprintUnlockController, mDismissCallbackRegistry);
        return mStatusBarKeyguardViewManager;
    }

    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
        Trace.beginSection("KeyguardViewMediator#startKeyguardExitAnimation");
        Message msg = mHandler.obtainMessage(START_KEYGUARD_EXIT_ANIM,
                new StartKeyguardExitAnimParams(startTime, fadeoutDuration));
        mHandler.sendMessage(msg);
        Trace.endSection();
    }

    public void onShortPowerPressedGoHome() {
        // do nothing
    }

    public ViewMediatorCallback getViewMediatorCallback() {
        return mViewMediatorCallback;
    }

    public LockPatternUtils getLockPatternUtils() {
        return mLockPatternUtils;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("  mSystemReady: "); pw.println(mSystemReady);
        pw.print("  mBootCompleted: "); pw.println(mBootCompleted);
        pw.print("  mBootSendUserPresent: "); pw.println(mBootSendUserPresent);
        pw.print("  mExternallyEnabled: "); pw.println(mExternallyEnabled);
        pw.print("  mShuttingDown: "); pw.println(mShuttingDown);
        pw.print("  mNeedToReshowWhenReenabled: "); pw.println(mNeedToReshowWhenReenabled);
        pw.print("  mShowing: "); pw.println(mShowing);
        pw.print("  mInputRestricted: "); pw.println(mInputRestricted);
        pw.print("  mOccluded: "); pw.println(mOccluded);
        pw.print("  mDelayedShowingSequence: "); pw.println(mDelayedShowingSequence);
        pw.print("  mExitSecureCallback: "); pw.println(mExitSecureCallback);
        pw.print("  mDeviceInteractive: "); pw.println(mDeviceInteractive);
        pw.print("  mGoingToSleep: "); pw.println(mGoingToSleep);
        pw.print("  mHiding: "); pw.println(mHiding);
        pw.print("  mWaitingUntilKeyguardVisible: "); pw.println(mWaitingUntilKeyguardVisible);
        pw.print("  mKeyguardDonePending: "); pw.println(mKeyguardDonePending);
        pw.print("  mHideAnimationRun: "); pw.println(mHideAnimationRun);
        pw.print("  mPendingReset: "); pw.println(mPendingReset);
        pw.print("  mPendingLock: "); pw.println(mPendingLock);
        pw.print("  mWakeAndUnlocking: "); pw.println(mWakeAndUnlocking);
        pw.print("  mDrawnCallback: "); pw.println(mDrawnCallback);
    }

    public void setAodShowing(boolean aodShowing) {
        setShowingLocked(mShowing, aodShowing);
    }

    private static class StartKeyguardExitAnimParams {

        long startTime;
        long fadeoutDuration;

        private StartKeyguardExitAnimParams(long startTime, long fadeoutDuration) {
            this.startTime = startTime;
            this.fadeoutDuration = fadeoutDuration;
        }
    }

    private void setShowingLocked(boolean showing, boolean aodShowing) {
        setShowingLocked(showing, aodShowing, mSecondaryDisplayShowing,
                false /* forceCallbacks */);
    }

    private void setShowingLocked(boolean showing, boolean aodShowing, int secondaryDisplayShowing,
            boolean forceCallbacks) {
        final boolean notifyDefaultDisplayCallbacks = showing != mShowing
                || aodShowing != mAodShowing || forceCallbacks;
        if (notifyDefaultDisplayCallbacks || secondaryDisplayShowing != mSecondaryDisplayShowing) {
            mShowing = showing;
            mAodShowing = aodShowing;
            mSecondaryDisplayShowing = secondaryDisplayShowing;
            if (notifyDefaultDisplayCallbacks) {
                notifyDefaultDisplayCallbacks(showing);
            }
            updateActivityLockScreenState(showing, aodShowing, secondaryDisplayShowing);
        }
    }

    private void notifyDefaultDisplayCallbacks(boolean showing) {
        int size = mKeyguardStateCallbacks.size();
        for (int i = size - 1; i >= 0; i--) {
            IKeyguardStateCallback callback = mKeyguardStateCallbacks.get(i);
            try {
                callback.onShowingStateChanged(showing);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call onShowingStateChanged", e);
                if (e instanceof DeadObjectException) {
                    mKeyguardStateCallbacks.remove(callback);
                }
            }
        }
        updateInputRestrictedLocked();
        mUiOffloadThread.submit(() -> {
            mTrustManager.reportKeyguardShowingChanged();
        });
    }

    private void notifyTrustedChangedLocked(boolean trusted) {
        int size = mKeyguardStateCallbacks.size();
        for (int i = size - 1; i >= 0; i--) {
            try {
                mKeyguardStateCallbacks.get(i).onTrustedChanged(trusted);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call notifyTrustedChangedLocked", e);
                if (e instanceof DeadObjectException) {
                    mKeyguardStateCallbacks.remove(i);
                }
            }
        }
    }

    private void notifyHasLockscreenWallpaperChanged(boolean hasLockscreenWallpaper) {
        int size = mKeyguardStateCallbacks.size();
        for (int i = size - 1; i >= 0; i--) {
            try {
                mKeyguardStateCallbacks.get(i).onHasLockscreenWallpaperChanged(
                        hasLockscreenWallpaper);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call onHasLockscreenWallpaperChanged", e);
                if (e instanceof DeadObjectException) {
                    mKeyguardStateCallbacks.remove(i);
                }
            }
        }
    }

    public void addStateMonitorCallback(IKeyguardStateCallback callback) {
        synchronized (this) {
            mKeyguardStateCallbacks.add(callback);
            try {
                callback.onSimSecureStateChanged(mUpdateMonitor.isSimPinSecure());
                callback.onShowingStateChanged(mShowing);
                callback.onInputRestrictedStateChanged(mInputRestricted);
                callback.onTrustedChanged(mUpdateMonitor.getUserHasTrust(
                        KeyguardUpdateMonitor.getCurrentUser()));
                callback.onHasLockscreenWallpaperChanged(mUpdateMonitor.hasLockscreenWallpaper());
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call to IKeyguardStateCallback", e);
            }
        }
    }

    private static class DismissMessage {
        private final CharSequence mMessage;
        private final IKeyguardDismissCallback mCallback;

        DismissMessage(IKeyguardDismissCallback callback, CharSequence message) {
            mCallback = callback;
            mMessage = message;
        }

        public IKeyguardDismissCallback getCallback() {
            return mCallback;
        }

        public CharSequence getMessage() {
            return mMessage;
        }
    }
}
