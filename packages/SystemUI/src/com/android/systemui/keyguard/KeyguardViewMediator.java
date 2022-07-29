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
 * limitations under the License.
 */

package com.android.systemui.keyguard;

import static android.provider.Settings.System.SCREEN_OFF_TIMEOUT;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_TO_LAUNCHER_CLEAR_SNAPSHOT;
import static android.view.WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.NAV_BAR_HANDLE_SHOW_OVER_LOCKSCREEN;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_TRANSITION_FROM_AOD;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_UNLOCK_ANIMATION;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE;
import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.graphics.Matrix;
import android.hardware.biometrics.BiometricSourceType;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardStateCallback;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardDisplayManager;
import com.android.keyguard.KeyguardSecurityView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.ViewMediatorCallback;
import com.android.keyguard.mediator.ScreenOnCoordinator;
import com.android.systemui.CoreStartable;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.animation.LaunchAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.dagger.KeyguardModule;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.DeviceConfigProxy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.Executor;

import dagger.Lazy;

/**
 * Mediates requests related to the keyguard.  This includes queries about the
 * state of the keyguard, power management events that effect whether the keyguard
 * should be shown or reset, callbacks to the phone window manager to notify
 * it of when the keyguard is showing, and events from the keyguard view itself
 * stating that the keyguard was successfully unlocked.
 *
 * Note that the keyguard view is shown when the screen is off (as appropriate)
 * so that once the screen comes on, it will be ready immediately.
 *
 * Example queries about the keyguard:
 * - is {movement, key} one that should wake the keyguard?
 * - is the keyguard showing?
 * - are input events restricted due to the state of the keyguard?
 *
 * Callbacks to the phone window manager:
 * - the keyguard is showing
 *
 * Example external events that translate to keyguard view changes:
 * - screen turned off -> reset the keyguard, and show it, so it will be ready
 *   next time the screen turns on
 * - keyboard is slid open -> if the keyguard is not secure, hide it
 *
 * Events from the keyguard view:
 * - user successfully unlocked keyguard -> hide keyguard view, and no longer
 *   restrict input events.
 *
 * Note: in addition to normal power management events that effect the state of
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
public class KeyguardViewMediator extends CoreStartable implements Dumpable,
        StatusBarStateController.StateListener {
    private static final int KEYGUARD_DISPLAY_TIMEOUT_DELAY_DEFAULT = 30000;
    private static final long KEYGUARD_DONE_PENDING_TIMEOUT_MS = 3000;

    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final boolean DEBUG_SIM_STATES = KeyguardConstants.DEBUG_SIM_STATES;

    private final static String TAG = "KeyguardViewMediator";

    private static final String DELAYED_KEYGUARD_ACTION =
        "com.android.internal.policy.impl.PhoneWindowManager.DELAYED_KEYGUARD";
    private static final String DELAYED_LOCK_PROFILE_ACTION =
            "com.android.internal.policy.impl.PhoneWindowManager.DELAYED_LOCK";

    private static final String SYSTEMUI_PERMISSION = "com.android.systemui.permission.SELF";

    // used for handler messages
    private static final int SHOW = 1;
    private static final int HIDE = 2;
    private static final int RESET = 3;
    private static final int VERIFY_UNLOCK = 4;
    private static final int NOTIFY_FINISHED_GOING_TO_SLEEP = 5;
    private static final int KEYGUARD_DONE = 7;
    private static final int KEYGUARD_DONE_DRAWING = 8;
    private static final int SET_OCCLUDED = 9;
    private static final int KEYGUARD_TIMEOUT = 10;
    private static final int DISMISS = 11;
    private static final int START_KEYGUARD_EXIT_ANIM = 12;
    private static final int KEYGUARD_DONE_PENDING_TIMEOUT = 13;
    private static final int NOTIFY_STARTED_WAKING_UP = 14;
    private static final int NOTIFY_STARTED_GOING_TO_SLEEP = 17;
    private static final int SYSTEM_READY = 18;
    private static final int CANCEL_KEYGUARD_EXIT_ANIM = 19;

    /**
     * The default amount of time we stay awake (used for all key input)
     */
    public static final int AWAKE_INTERVAL_BOUNCER_MS = 10000;

    /**
     * How long to wait after the screen turns off due to timeout before
     * turning on the keyguard (i.e, the user has this much time to turn
     * the screen back on without having to face the keyguard).
     */
    private static final int KEYGUARD_LOCK_AFTER_DELAY_DEFAULT = 5000;

    /**
     * How long we'll wait for the {@link ViewMediatorCallback#keyguardDoneDrawing()}
     * callback before unblocking a call to {@link #setKeyguardEnabled(boolean)}
     * that is re-enabling the keyguard.
     */
    private static final int KEYGUARD_DONE_DRAWING_TIMEOUT_MS = 2000;

    private static final int UNOCCLUDE_ANIMATION_DURATION = 250;

    /**
     * How far down to animate the unoccluding activity, in terms of percent of the activity's
     * height.
     */
    private static final float UNOCCLUDE_TRANSLATE_DISTANCE_PERCENT = 0.1f;

    /**
     * Boolean option for doKeyguardLocked/doKeyguardTimeout which, when set to true, forces the
     * keyguard to show even if it is disabled for the current user.
     */
    public static final String OPTION_FORCE_SHOW = "force_show";
    private final DreamOverlayStateController mDreamOverlayStateController;

    /** The stream type that the lock sounds are tied to. */
    private int mUiSoundsStreamType;

    private AlarmManager mAlarmManager;
    private AudioManager mAudioManager;
    private StatusBarManager mStatusBarManager;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final Executor mUiBgExecutor;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private final Lazy<NotificationShadeDepthController> mNotificationShadeDepthController;

    private boolean mSystemReady;
    private boolean mBootCompleted;
    private boolean mBootSendUserPresent;
    private boolean mShuttingDown;
    private boolean mDozing;
    private boolean mAnimatingScreenOff;
    private final FalsingCollector mFalsingCollector;

    /** High level access to the power manager for WakeLocks */
    private final PowerManager mPM;

    /** TrustManager for letting it know when we change visibility */
    private final TrustManager mTrustManager;

    /** UserSwitcherController for creating guest user on boot complete */
    private final UserSwitcherController mUserSwitcherController;

    /**
     * Used to keep the device awake while to ensure the keyguard finishes opening before
     * we sleep.
     */
    private PowerManager.WakeLock mShowKeyguardWakeLock;

    private final Lazy<KeyguardViewController> mKeyguardViewControllerLazy;

    // these are protected by synchronized (this)

    /**
     * External apps (like the phone app) can tell us to disable the keyguard.
     */
    private boolean mExternallyEnabled = true;

    /**
     * Remember if an external call to {@link #setKeyguardEnabled} with value
     * false caused us to hide the keyguard, so that we need to reshow it once
     * the keyguard is re-enabled with another call with value true.
     */
    private boolean mNeedToReshowWhenReenabled = false;

    // cached value of whether we are showing (need to know this to quickly
    // answer whether the input should be restricted)
    private boolean mShowing;

    // AOD is enabled and status bar is in AOD state.
    private boolean mAodShowing;

    // Dream overlay is visible.
    private boolean mDreamOverlayShowing;

    /** Cached value of #isInputRestricted */
    private boolean mInputRestricted;

    // true if the keyguard is hidden by another window
    private boolean mOccluded = false;

    private boolean mWakeAndUnlocking = false;

    /**
     * Helps remember whether the screen has turned on since the last time
     * it turned off due to timeout. see {@link #onScreenTurnedOff(int)}
     */
    private int mDelayedShowingSequence;

    /**
     * Similar to {@link #mDelayedProfileShowingSequence}, but it is for profile case.
     */
    private int mDelayedProfileShowingSequence;

    /**
     * If the user has disabled the keyguard, then requests to exit, this is
     * how we'll ultimately let them know whether it was successful.  We use this
     * var being non-null as an indicator that there is an in progress request.
     */
    private IKeyguardExitCallback mExitSecureCallback;
    private final DismissCallbackRegistry mDismissCallbackRegistry;

    // the properties of the keyguard

    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final Lazy<NotificationShadeWindowController> mNotificationShadeWindowControllerLazy;

    /**
     * Last SIM state reported by the telephony system.
     * Index is the slotId - in case of multiple SIM cards.
     */
    private final SparseIntArray mLastSimStates = new SparseIntArray();

    /**
     * Indicates if a SIM card had the SIM PIN enabled during the initialization, before
     * reaching the SIM_STATE_READY state. The flag is reset to false at SIM_STATE_READY.
     * Index is the slotId - in case of multiple SIM cards.
     */
    private final SparseBooleanArray mSimWasLocked = new SparseBooleanArray();

    private boolean mDeviceInteractive;
    private boolean mGoingToSleep;

    // last known state of the cellular connection
    private String mPhoneState = TelephonyManager.EXTRA_STATE_IDLE;

    /**
     * Whether a hide is pending and we are just waiting for #startKeyguardExitAnimation to be
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
     * {@link #setKeyguardEnabled} waits on this condition when it re-enables
     * the keyguard.
     */
    private boolean mWaitingUntilKeyguardVisible = false;
    private final LockPatternUtils mLockPatternUtils;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private boolean mKeyguardDonePending = false;
    private boolean mHideAnimationRun = false;
    private boolean mHideAnimationRunning = false;

    private SoundPool mLockSounds;
    private int mLockSoundId;
    private int mUnlockSoundId;
    private int mTrustedSoundId;
    private int mLockSoundStreamId;
    private final float mPowerButtonY;
    private final float mWindowCornerRadius;

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
    private final KeyguardDisplayManager mKeyguardDisplayManager;

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
     * When starting to go away, flag a need to show the PIN lock so the keyguard can be brought
     * back.
     */
    private boolean mPendingPinLock = false;

    /**
     * Whether a power button gesture (such as double tap for camera) has been detected. This is
     * delivered directly from {@link KeyguardService}, immediately upon the gesture being detected.
     * This is used in {@link #onStartedWakingUp} to decide whether to execute the pending lock, or
     * ignore and reset it because we are actually launching an activity.
     *
     * This needs to be delivered directly to us, rather than waiting for
     * {@link CommandQueue#onCameraLaunchGestureDetected}, because that call is asynchronous and is
     * often delivered after the call to {@link #onStartedWakingUp}, which results in us locking the
     * keyguard and then launching the activity behind it.
     */
    private boolean mPowerGestureIntercepted = false;

    /**
     * Controller for showing individual "work challenge" lock screen windows inside managed profile
     * tasks when the current user has been unlocked but the profile is still locked.
     */
    private WorkLockActivityController mWorkLockController;

    private boolean mLockLater;
    private boolean mShowHomeOverLockscreen;
    private boolean mInGestureNavigationMode;
    private CharSequence mCustomMessage;

    /**
     * Whether the RemoteAnimation on the app/launcher surface behind the keyguard is 'running'.
     * Note that this does not necessarily mean the surface is currently in motion - we may be
     * 'animating' it along with the user's finger during a swipe to unlock gesture, a gesture that
     * can be paused or reversed.
     */
    private boolean mSurfaceBehindRemoteAnimationRunning;

    /**
     * Whether we've asked to make the app/launcher surface behind the keyguard visible, via a call
     * to {@link android.app.IActivityTaskManager#keyguardGoingAway(int)}.
     *
     * Since that's an IPC, this doesn't necessarily mean the remote animation has started yet.
     * {@link #mSurfaceBehindRemoteAnimationRunning} will be true if the call completed and the
     * animation is now running.
     */
    private boolean mSurfaceBehindRemoteAnimationRequested = false;

    /**
     * Callback to run to end the RemoteAnimation on the app/launcher surface behind the keyguard.
     */
    private IRemoteAnimationFinishedCallback mSurfaceBehindRemoteAnimationFinishedCallback;

    /**
     * The animation runner to use for the next exit animation.
     */
    private IRemoteAnimationRunner mKeyguardExitAnimationRunner;

    private CentralSurfaces mCentralSurfaces;

    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
            @Override
            public void onPropertiesChanged(DeviceConfig.Properties properties) {
                if (properties.getKeyset().contains(NAV_BAR_HANDLE_SHOW_OVER_LOCKSCREEN)) {
                    mShowHomeOverLockscreen = properties.getBoolean(
                            NAV_BAR_HANDLE_SHOW_OVER_LOCKSCREEN, true /* defaultValue */);
                }
            }
    };

    private final DreamOverlayStateController.Callback mDreamOverlayStateCallback =
            new DreamOverlayStateController.Callback() {
                @Override
                public void onStateChanged() {
                    mDreamOverlayShowing = mDreamOverlayStateController.isOverlayActive();
                }
            };

    KeyguardUpdateMonitorCallback mUpdateCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            synchronized (KeyguardViewMediator.this) {
                if (!showing && mPendingPinLock) {
                    Log.i(TAG, "PIN lock requested, starting keyguard");

                    // Bring the keyguard back in order to show the PIN lock
                    mPendingPinLock = false;
                    doKeyguardLocked(null);
                }
            }
        }

        @Override
        public void onUserSwitching(int userId) {
            if (DEBUG) Log.d(TAG, String.format("onUserSwitching %d", userId));
            // Note that the mLockPatternUtils user has already been updated from setCurrentUser.
            // We need to force a reset of the views, since lockNow (called by
            // ActivityManagerService) will not reconstruct the keyguard if it is already showing.
            synchronized (KeyguardViewMediator.this) {
                resetKeyguardDonePendingLocked();
                if (mLockPatternUtils.isLockScreenDisabled(userId)) {
                    // If we are switching to a user that has keyguard disabled, dismiss keyguard.
                    dismiss(null /* callback */, null /* message */);
                } else {
                    resetStateLocked();
                }
                adjustStatusBarLocked();
            }
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            if (DEBUG) Log.d(TAG, String.format("onUserSwitchComplete %d", userId));
            if (userId != UserHandle.USER_SYSTEM) {
                UserInfo info = UserManager.get(mContext).getUserInfo(userId);
                // Don't try to dismiss if the user has Pin/Pattern/Password set
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
        public void onSimStateChanged(int subId, int slotId, int simState) {

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

            boolean lastSimStateWasLocked;
            synchronized (KeyguardViewMediator.this) {
                int lastState = mLastSimStates.get(slotId);
                lastSimStateWasLocked = (lastState == TelephonyManager.SIM_STATE_PIN_REQUIRED
                        || lastState == TelephonyManager.SIM_STATE_PUK_REQUIRED);
                mLastSimStates.append(slotId, simState);
            }

            switch (simState) {
                case TelephonyManager.SIM_STATE_NOT_READY:
                case TelephonyManager.SIM_STATE_ABSENT:
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
                        if (simState == TelephonyManager.SIM_STATE_ABSENT) {
                            // MVNO SIMs can become transiently NOT_READY when switching networks,
                            // so we should only lock when they are ABSENT.
                            if (lastSimStateWasLocked) {
                                if (DEBUG_SIM_STATES) Log.d(TAG, "SIM moved to ABSENT when the "
                                        + "previous state was locked. Reset the state.");
                                resetStateLocked();
                            }
                            mSimWasLocked.append(slotId, false);
                        }
                    }
                    break;
                case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                    synchronized (KeyguardViewMediator.this) {
                        mSimWasLocked.append(slotId, true);
                        mPendingPinLock = true;
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
                case TelephonyManager.SIM_STATE_PERM_DISABLED:
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
                    }
                    break;
                case TelephonyManager.SIM_STATE_READY:
                    synchronized (KeyguardViewMediator.this) {
                        if (DEBUG_SIM_STATES) Log.d(TAG, "READY, reset state? " + mShowing);
                        if (mShowing && mSimWasLocked.get(slotId, false)) {
                            if (DEBUG_SIM_STATES) Log.d(TAG, "SIM moved to READY when the "
                                    + "previously was locked. Reset the state.");
                            mSimWasLocked.append(slotId, false);
                            resetStateLocked();
                        }
                    }
                    break;
                default:
                    if (DEBUG_SIM_STATES) Log.v(TAG, "Unspecific state: " + simState);
                    break;
            }
        }

        @Override
        public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
            final int currentUser = KeyguardUpdateMonitor.getCurrentUser();
            if (mLockPatternUtils.isSecure(currentUser)) {
                mLockPatternUtils.getDevicePolicyManager().reportFailedBiometricAttempt(
                        currentUser);
            }
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
                boolean isStrongBiometric) {
            if (mLockPatternUtils.isSecure(userId)) {
                mLockPatternUtils.getDevicePolicyManager().reportSuccessfulBiometricAttempt(
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
            if (DEBUG) Log.d(TAG, "keyguardDone");
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
            mKeyguardViewControllerLazy.get().setNeedsInput(needsInput);
        }

        @Override
        public void keyguardDonePending(boolean strongAuth, int targetUserId) {
            Trace.beginSection("KeyguardViewMediator.mViewMediatorCallback#keyguardDonePending");
            if (DEBUG) Log.d(TAG, "keyguardDonePending");
            if (targetUserId != ActivityManager.getCurrentUser()) {
                Trace.endSection();
                return;
            }

            mKeyguardDonePending = true;
            mHideAnimationRun = true;
            mHideAnimationRunning = true;
            mKeyguardViewControllerLazy.get()
                    .startPreHideAnimation(mHideAnimationFinishedRunnable);
            mHandler.sendEmptyMessageDelayed(KEYGUARD_DONE_PENDING_TIMEOUT,
                    KEYGUARD_DONE_PENDING_TIMEOUT_MS);
            Trace.endSection();
        }

        @Override
        public void keyguardGone() {
            Trace.beginSection("KeyguardViewMediator.mViewMediatorCallback#keyguardGone");
            if (DEBUG) Log.d(TAG, "keyguardGone");
            mKeyguardViewControllerLazy.get().setKeyguardGoingAwayState(false);
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
        public void onCancelClicked() {
            mKeyguardViewControllerLazy.get().onCancelClicked();
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
            int currentUser = KeyguardUpdateMonitor.getCurrentUser();
            boolean trustAgentsEnabled = mUpdateMonitor.isTrustUsuallyManaged(currentUser);
            boolean biometricsEnrolled =
                    mUpdateMonitor.isUnlockingWithBiometricsPossible(currentUser);
            boolean any = trustAgentsEnabled || biometricsEnrolled;
            KeyguardUpdateMonitor.StrongAuthTracker strongAuthTracker =
                    mUpdateMonitor.getStrongAuthTracker();
            int strongAuth = strongAuthTracker.getStrongAuthForUser(currentUser);

            if (any && !strongAuthTracker.hasUserAuthenticatedSinceBoot()) {
                return KeyguardSecurityView.PROMPT_REASON_RESTART;
            } else if (any && (strongAuth & STRONG_AUTH_REQUIRED_AFTER_TIMEOUT) != 0) {
                return KeyguardSecurityView.PROMPT_REASON_TIMEOUT;
            } else if ((strongAuth & STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW) != 0) {
                return KeyguardSecurityView.PROMPT_REASON_DEVICE_ADMIN;
            } else if (trustAgentsEnabled
                    && (strongAuth & SOME_AUTH_REQUIRED_AFTER_USER_REQUEST) != 0) {
                return KeyguardSecurityView.PROMPT_REASON_USER_REQUEST;
            } else if (any && ((strongAuth & STRONG_AUTH_REQUIRED_AFTER_LOCKOUT) != 0
                    || mUpdateMonitor.isFingerprintLockedOut())) {
                return KeyguardSecurityView.PROMPT_REASON_AFTER_LOCKOUT;
            } else if (any && (strongAuth & STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE) != 0) {
                return KeyguardSecurityView.PROMPT_REASON_PREPARE_FOR_UPDATE;
            } else if (any && (strongAuth
                    & STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT) != 0) {
                return KeyguardSecurityView.PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT;
            }
            return KeyguardSecurityView.PROMPT_REASON_NONE;
        }

        @Override
        public CharSequence consumeCustomMessage() {
            final CharSequence message = mCustomMessage;
            mCustomMessage = null;
            return message;
        }
    };

    /**
     * Animation launch controller for activities that occlude the keyguard.
     */
    private final ActivityLaunchAnimator.Controller mOccludeAnimationController =
            new ActivityLaunchAnimator.Controller() {
                @Override
                public void onLaunchAnimationStart(boolean isExpandingFullyAbove) {}

                @Override
                public void onLaunchAnimationCancelled() {
                    Log.d(TAG, "Occlude launch animation cancelled. Occluded state is now: "
                            + mOccluded);
                }

                @Override
                public void onLaunchAnimationEnd(boolean launchIsFullScreen) {
                    if (launchIsFullScreen) {
                        mCentralSurfaces.instantCollapseNotificationPanel();
                    }
                }

                @NonNull
                @Override
                public ViewGroup getLaunchContainer() {
                    return ((ViewGroup) mKeyguardViewControllerLazy.get()
                            .getViewRootImpl().getView());
                }

                @Override
                public void setLaunchContainer(@NonNull ViewGroup launchContainer) {
                    // No-op, launch container is always the shade.
                    Log.wtf(TAG, "Someone tried to change the launch container for the "
                            + "ActivityLaunchAnimator, which should never happen.");
                }

                @NonNull
                @Override
                public LaunchAnimator.State createAnimatorState() {
                    final int width = getLaunchContainer().getWidth();
                    final int height = getLaunchContainer().getHeight();

                    final float initialHeight = height / 3f;
                    final float initialWidth = width / 3f;

                    if (mUpdateMonitor.isSecureCameraLaunchedOverKeyguard()) {
                        // Start the animation near the power button, at one-third size, since the
                        // camera was launched from the power button.
                        return new LaunchAnimator.State(
                                (int) (mPowerButtonY - initialHeight / 2f) /* top */,
                                (int) (mPowerButtonY + initialHeight / 2f) /* bottom */,
                                (int) (width - initialWidth) /* left */,
                                width /* right */,
                                mWindowCornerRadius, mWindowCornerRadius);
                    } else {
                        // Start the animation in the center of the screen, scaled down.
                        return new LaunchAnimator.State(
                                height / 2, height / 2, width / 2, width / 2,
                                mWindowCornerRadius, mWindowCornerRadius);
                    }
                }
            };

    private IRemoteAnimationRunner mOccludeAnimationRunner =
            new OccludeActivityLaunchRemoteAnimationRunner(mOccludeAnimationController);

    /**
     * Animation controller for activities that unocclude the keyguard. This does not use the
     * ActivityLaunchAnimator since we're just translating down, rather than emerging from a view
     * or the power button.
     */
    private final IRemoteAnimationRunner mUnoccludeAnimationRunner =
            new IRemoteAnimationRunner.Stub() {

                @Nullable private ValueAnimator mUnoccludeAnimator;
                private final Matrix mUnoccludeMatrix = new Matrix();

                @Override
                public void onAnimationCancelled(boolean isKeyguardOccluded) {
                    if (mUnoccludeAnimator != null) {
                        mUnoccludeAnimator.cancel();
                    }

                    setOccluded(isKeyguardOccluded /* isOccluded */, false /* animate */);
                    Log.d(TAG, "Unocclude animation cancelled. Occluded state is now: "
                            + mOccluded);
                }

                @Override
                public void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                        RemoteAnimationTarget[] wallpapers,
                        RemoteAnimationTarget[] nonApps,
                        IRemoteAnimationFinishedCallback finishedCallback) throws RemoteException {
                    Log.d(TAG, "UnoccludeAnimator#onAnimationStart. Set occluded = false.");
                    setOccluded(false /* isOccluded */, true /* animate */);

                    if (apps == null || apps.length == 0 || apps[0] == null) {
                        Log.d(TAG, "No apps provided to unocclude runner; "
                                + "skipping animation and unoccluding.");
                        finishedCallback.onAnimationFinished();
                        return;
                    }

                    final RemoteAnimationTarget primary = apps[0];

                    final SyncRtSurfaceTransactionApplier applier =
                            new SyncRtSurfaceTransactionApplier(
                                    mKeyguardViewControllerLazy.get().getViewRootImpl().getView());


                    mContext.getMainExecutor().execute(() -> {
                        if (mUnoccludeAnimator != null) {
                            mUnoccludeAnimator.cancel();
                        }

                        mUnoccludeAnimator = ValueAnimator.ofFloat(1f, 0f);
                        mUnoccludeAnimator.setDuration(UNOCCLUDE_ANIMATION_DURATION);
                        mUnoccludeAnimator.setInterpolator(Interpolators.TOUCH_RESPONSE);
                        mUnoccludeAnimator.addUpdateListener(
                                animation -> {
                                    final float animatedValue =
                                            (float) animation.getAnimatedValue();

                                    final float surfaceHeight = primary.screenSpaceBounds.height();

                                    mUnoccludeMatrix.setTranslate(
                                            0f,
                                            (1f - animatedValue)
                                                    * surfaceHeight
                                                    * UNOCCLUDE_TRANSLATE_DISTANCE_PERCENT);

                                    SyncRtSurfaceTransactionApplier.SurfaceParams params =
                                            new SyncRtSurfaceTransactionApplier.SurfaceParams
                                                    .Builder(primary.leash)
                                                    .withMatrix(mUnoccludeMatrix)
                                                    .withCornerRadius(mWindowCornerRadius)
                                                    .withAlpha(animatedValue)
                                                    .build();
                                    applier.scheduleApply(params);
                                });
                        mUnoccludeAnimator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                try {
                                    finishedCallback.onAnimationFinished();
                                    mUnoccludeAnimator = null;
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                }
                            }
                        });

                        mUnoccludeAnimator.start();
                    });
                }
            };

    private DeviceConfigProxy mDeviceConfig;
    private DozeParameters mDozeParameters;

    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardStateController.Callback mKeyguardStateControllerCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onBouncerShowingChanged() {
            synchronized (KeyguardViewMediator.this) {
                if (mKeyguardStateController.isBouncerShowing()) {
                    mPendingPinLock = false;
                }
                adjustStatusBarLocked(mKeyguardStateController.isBouncerShowing(), false);
            }
        }
    };

    private final Lazy<KeyguardUnlockAnimationController> mKeyguardUnlockAnimationControllerLazy;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private boolean mWallpaperSupportsAmbientMode;
    private ScreenOnCoordinator mScreenOnCoordinator;

    private Lazy<ActivityLaunchAnimator> mActivityLaunchAnimator;

    /**
     * Injected constructor. See {@link KeyguardModule}.
     */
    public KeyguardViewMediator(
            Context context,
            FalsingCollector falsingCollector,
            LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            Lazy<KeyguardViewController> statusBarKeyguardViewManagerLazy,
            DismissCallbackRegistry dismissCallbackRegistry,
            KeyguardUpdateMonitor keyguardUpdateMonitor, DumpManager dumpManager,
            @UiBackground Executor uiBgExecutor, PowerManager powerManager,
            TrustManager trustManager,
            UserSwitcherController userSwitcherController,
            DeviceConfigProxy deviceConfig,
            NavigationModeController navigationModeController,
            KeyguardDisplayManager keyguardDisplayManager,
            DozeParameters dozeParameters,
            SysuiStatusBarStateController statusBarStateController,
            KeyguardStateController keyguardStateController,
            Lazy<KeyguardUnlockAnimationController> keyguardUnlockAnimationControllerLazy,
            ScreenOffAnimationController screenOffAnimationController,
            Lazy<NotificationShadeDepthController> notificationShadeDepthController,
            ScreenOnCoordinator screenOnCoordinator,
            InteractionJankMonitor interactionJankMonitor,
            DreamOverlayStateController dreamOverlayStateController,
            Lazy<NotificationShadeWindowController> notificationShadeWindowControllerLazy,
            Lazy<ActivityLaunchAnimator> activityLaunchAnimator) {
        super(context);
        mFalsingCollector = falsingCollector;
        mLockPatternUtils = lockPatternUtils;
        mBroadcastDispatcher = broadcastDispatcher;
        mKeyguardViewControllerLazy = statusBarKeyguardViewManagerLazy;
        mDismissCallbackRegistry = dismissCallbackRegistry;
        mNotificationShadeDepthController = notificationShadeDepthController;
        mUiBgExecutor = uiBgExecutor;
        mUpdateMonitor = keyguardUpdateMonitor;
        mPM = powerManager;
        mTrustManager = trustManager;
        mUserSwitcherController = userSwitcherController;
        mKeyguardDisplayManager = keyguardDisplayManager;
        dumpManager.registerDumpable(getClass().getName(), this);
        mDeviceConfig = deviceConfig;
        mScreenOnCoordinator = screenOnCoordinator;
        mNotificationShadeWindowControllerLazy = notificationShadeWindowControllerLazy;
        mShowHomeOverLockscreen = mDeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                NAV_BAR_HANDLE_SHOW_OVER_LOCKSCREEN,
                /* defaultValue = */ true);
        mDeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                mHandler::post,
                mOnPropertiesChangedListener);
        mInGestureNavigationMode =
                QuickStepContract.isGesturalMode(navigationModeController.addListener(mode -> {
                    mInGestureNavigationMode = QuickStepContract.isGesturalMode(mode);
                }));
        mDozeParameters = dozeParameters;

        mStatusBarStateController = statusBarStateController;
        statusBarStateController.addCallback(this);

        mKeyguardStateController = keyguardStateController;
        keyguardStateController.addCallback(mKeyguardStateControllerCallback);
        mKeyguardUnlockAnimationControllerLazy = keyguardUnlockAnimationControllerLazy;
        mScreenOffAnimationController = screenOffAnimationController;
        mInteractionJankMonitor = interactionJankMonitor;
        mDreamOverlayStateController = dreamOverlayStateController;

        mActivityLaunchAnimator = activityLaunchAnimator;

        mPowerButtonY = context.getResources().getDimensionPixelSize(
                R.dimen.physical_power_button_center_screen_location_y);
        mWindowCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context);
    }

    public void userActivity() {
        mPM.userActivity(SystemClock.uptimeMillis(), false);
    }

    boolean mustNotUnlockCurrentUser() {
        return UserManager.isSplitSystemUser()
                && KeyguardUpdateMonitor.getCurrentUser() == UserHandle.USER_SYSTEM;
    }

    private void setupLocked() {
        mShowKeyguardWakeLock = mPM.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "show keyguard");
        mShowKeyguardWakeLock.setReferenceCounted(false);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter);

        final IntentFilter delayedActionFilter = new IntentFilter();
        delayedActionFilter.addAction(DELAYED_KEYGUARD_ACTION);
        delayedActionFilter.addAction(DELAYED_LOCK_PROFILE_ACTION);
        delayedActionFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mDelayedLockBroadcastReceiver, delayedActionFilter,
                SYSTEMUI_PERMISSION, null /* scheduler */,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        KeyguardUpdateMonitor.setCurrentUser(ActivityManager.getCurrentUser());

        // Assume keyguard is showing (unless it's disabled) until we know for sure, unless Keyguard
        // is disabled.
        if (isKeyguardServiceEnabled()) {
            setShowingLocked(!shouldWaitForProvisioning()
                    && !mLockPatternUtils.isLockScreenDisabled(
                            KeyguardUpdateMonitor.getCurrentUser()), true /* forceCallbacks */);
        } else {
            // The system's keyguard is disabled or missing.
            setShowingLocked(false /* showing */, true /* forceCallbacks */);
        }

        final ContentResolver cr = mContext.getContentResolver();

        mDeviceInteractive = mPM.isInteractive();

        mLockSounds = new SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                .build();
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
    }

    /**
     * Let us know that the system is ready after startup.
     */
    public void onSystemReady() {
        mHandler.obtainMessage(SYSTEM_READY).sendToTarget();
    }

    private void handleSystemReady() {
        synchronized (this) {
            if (DEBUG) Log.d(TAG, "onSystemReady");
            mSystemReady = true;
            doKeyguardLocked(null);
            mUpdateMonitor.registerCallback(mUpdateCallback);
            mDreamOverlayStateController.addCallback(mDreamOverlayStateCallback);
        }
        // Most services aren't available until the system reaches the ready state, so we
        // send it here when the device first boots.
        maybeSendUserPresentBroadcast();
    }

    /**
     * Called to let us know the screen was turned off.
     * @param offReason either {@link WindowManagerPolicyConstants#OFF_BECAUSE_OF_USER} or
     * {@link WindowManagerPolicyConstants#OFF_BECAUSE_OF_TIMEOUT}.
     */
    public void onStartedGoingToSleep(@WindowManagerPolicyConstants.OffReason int offReason) {
        if (DEBUG) Log.d(TAG, "onStartedGoingToSleep(" + offReason + ")");
        synchronized (this) {
            mDeviceInteractive = false;
            mPowerGestureIntercepted = false;
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
            } else if (mShowing && !mKeyguardStateController.isKeyguardGoingAway()) {
                // If we are going to sleep but the keyguard is showing (and will continue to be
                // showing, not in the process of going away) then reset its state. Otherwise, let
                // this fall through and explicitly re-lock the keyguard.
                mPendingReset = true;
            } else if (
                    (offReason == WindowManagerPolicyConstants.OFF_BECAUSE_OF_TIMEOUT
                            && timeout > 0)
                            || (offReason == WindowManagerPolicyConstants.OFF_BECAUSE_OF_USER
                            && !lockImmediately)) {
                doKeyguardLaterLocked(timeout);
                mLockLater = true;
            } else if (!mLockPatternUtils.isLockScreenDisabled(currentUser)) {
                setPendingLock(true);
            }

            if (mPendingLock) {
                playSounds(true);
            }
        }

        mUpdateMonitor.dispatchStartedGoingToSleep(offReason);

        // Reset keyguard going away state, so we can start listening for fingerprint. We
        // explicitly DO NOT want to call
        // mKeyguardViewControllerLazy.get().setKeyguardGoingAwayState(false)
        // here, since that will mess with the device lock state.
        mUpdateMonitor.dispatchKeyguardGoingAway(false);

        notifyStartedGoingToSleep();
    }

    /**
     * Called to let us know the screen finished turning off.
     * @param offReason either {@link WindowManagerPolicyConstants#OFF_BECAUSE_OF_USER} or
     * {@link WindowManagerPolicyConstants#OFF_BECAUSE_OF_TIMEOUT}.
     */
    public void onFinishedGoingToSleep(
            @WindowManagerPolicyConstants.OffReason int offReason, boolean cameraGestureTriggered) {
        if (DEBUG) Log.d(TAG, "onFinishedGoingToSleep(" + offReason + ")");
        synchronized (this) {
            mDeviceInteractive = false;
            mGoingToSleep = false;
            mWakeAndUnlocking = false;
            mAnimatingScreenOff = mDozeParameters.shouldAnimateDozingChange();

            resetKeyguardDonePendingLocked();
            mHideAnimationRun = false;

            notifyFinishedGoingToSleep();

            if (cameraGestureTriggered) {

                // Just to make sure, make sure the device is awake.
                mContext.getSystemService(PowerManager.class).wakeUp(SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_CAMERA_LAUNCH,
                        "com.android.systemui:CAMERA_GESTURE_PREVENT_LOCK");
                setPendingLock(false);
                mPendingReset = false;
            }

            if (mPendingReset) {
                resetStateLocked();
                mPendingReset = false;
            }

            maybeHandlePendingLock();

            // We do not have timeout and power button instant lock setting for profile lock.
            // So we use the personal setting if there is any. But if there is no device
            // we need to make sure we lock it immediately when the screen is off.
            if (!mLockLater && !cameraGestureTriggered) {
                doKeyguardForChildProfilesLocked();
            }

        }
        mUpdateMonitor.dispatchFinishedGoingToSleep(offReason);
    }

    /**
     * Locks the keyguard if {@link #mPendingLock} is true, and there are no reasons to further
     * delay the pending lock.
     *
     * If you do delay handling the pending lock, you must ensure that this method is ALWAYS called
     * again when the condition causing the delay changes. Otherwise, the device may remain unlocked
     * indefinitely.
     */
    public void maybeHandlePendingLock() {
        if (mPendingLock) {

            // The screen off animation is playing, so if we lock now, the foreground app will
            // vanish and the keyguard will jump-cut in. Delay it, until either:
            //   - The screen off animation ends. We will call maybeHandlePendingLock from
            //     the end action in UnlockedScreenOffAnimationController#animateInKeyguard.
            //   - The screen off animation is cancelled by the device waking back up. We will call
            //     maybeHandlePendingLock from KeyguardViewMediator#onStartedWakingUp.
            if (mScreenOffAnimationController.isKeyguardShowDelayed()) {
                if (DEBUG) {
                    Log.d(TAG, "#maybeHandlePendingLock: not handling because the screen off "
                            + "animation's isKeyguardShowDelayed() returned true. This should be "
                            + "handled soon by #onStartedWakingUp, or by the end actions of the "
                            + "screen off animation.");
                }

                return;
            }

            // The device was re-locked while in the process of unlocking. If we lock now, callbacks
            // in the unlock sequence might end up re-unlocking the device. Delay the lock until the
            // keyguard is done going away. We'll call maybeHandlePendingLock again in
            // StatusBar#finishKeyguardFadingAway, which is always responsible for setting
            // isKeyguardGoingAway to false.
            if (mKeyguardStateController.isKeyguardGoingAway()) {
                if (DEBUG) {
                    Log.d(TAG, "#maybeHandlePendingLock: not handling because the keyguard is "
                            + "going away. This should be handled shortly by "
                            + "StatusBar#finishKeyguardFadingAway.");
                }

                return;
            }

            if (DEBUG) {
                Log.d(TAG, "#maybeHandlePendingLock: handling pending lock; locking keyguard.");
            }

            doKeyguardLocked(null);
            setPendingLock(false);
        }
    }

    private boolean isKeyguardServiceEnabled() {
        try {
            return mContext.getPackageManager().getServiceInfo(
                    new ComponentName(mContext, KeyguardService.class), 0).isEnabled();
        } catch (NameNotFoundException e) {
            return true;
        }
    }

    private long getLockTimeout(int userId) {
        // if the screen turned off because of timeout or the user hit the power button,
        // and we don't need to lock immediately, set an alarm
        // to enable it a bit later (i.e, give the user a chance
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
        intent.setPackage(mContext.getPackageName());
        intent.putExtra("seq", mDelayedShowingSequence);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        PendingIntent sender = PendingIntent.getBroadcast(mContext,
                0, intent, PendingIntent.FLAG_CANCEL_CURRENT |  PendingIntent.FLAG_IMMUTABLE);
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
                    lockIntent.setPackage(mContext.getPackageName());
                    lockIntent.putExtra("seq", mDelayedProfileShowingSequence);
                    lockIntent.putExtra(Intent.EXTRA_USER_ID, profileId);
                    lockIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    PendingIntent lockSender = PendingIntent.getBroadcast(
                            mContext, 0, lockIntent,
                            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
     * It will let us know when the device is waking up.
     */
    public void onStartedWakingUp(boolean cameraGestureTriggered) {
        Trace.beginSection("KeyguardViewMediator#onStartedWakingUp");

        // TODO: Rename all screen off/on references to interactive/sleeping
        synchronized (this) {
            mDeviceInteractive = true;
            if (mPendingLock && !cameraGestureTriggered) {
                doKeyguardLocked(null);
            }
            mAnimatingScreenOff = false;
            cancelDoKeyguardLaterLocked();
            cancelDoKeyguardForChildProfilesLocked();
            if (DEBUG) Log.d(TAG, "onStartedWakingUp, seq = " + mDelayedShowingSequence);
            notifyStartedWakingUp();
        }
        mUpdateMonitor.dispatchStartedWakingUp();
        maybeSendUserPresentBroadcast();
        Trace.endSection();
    }

    public void onScreenTurnedOff() {
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
            mLockPatternUtils.userPresent(KeyguardUpdateMonitor.getCurrentUser());
        }
    }

    /**
     * A dream started.  We should lock after the usual screen-off lock timeout but only
     * if there is a secure lock pattern.
     */
    public void onDreamingStarted() {
        mUpdateMonitor.dispatchDreamingStarted();
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
        mUpdateMonitor.dispatchDreamingStopped();
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
                    // can get past the keyguard. ignore extraneous requests to disable / re-enable
                    return;
                }

                // hiding keyguard that is showing, remember to reshow later
                if (DEBUG) Log.d(TAG, "remembering to reshow, hiding keyguard, "
                        + "disabling status bar expansion");
                mNeedToReshowWhenReenabled = true;
                updateInputRestrictedLocked();
                hideLocked();
            } else if (enabled && mNeedToReshowWhenReenabled) {
                // re-enabled after previously hidden, reshow
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

                    // block until we know the keyguard is done drawing (and post a message
                    // to unblock us after a timeout, so we don't risk blocking too long
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
        Log.d(TAG, "setOccluded(" + isOccluded + ")");

        Trace.beginSection("KeyguardViewMediator#setOccluded");
        if (DEBUG) Log.d(TAG, "setOccluded " + isOccluded);
        mInteractionJankMonitor.cancel(CUJ_LOCKSCREEN_TRANSITION_FROM_AOD);
        mHandler.removeMessages(SET_OCCLUDED);
        Message msg = mHandler.obtainMessage(SET_OCCLUDED, isOccluded ? 1 : 0, animate ? 1 : 0);
        mHandler.sendMessage(msg);
        Trace.endSection();
    }

    public IRemoteAnimationRunner getOccludeAnimationRunner() {
        return mOccludeAnimationRunner;
    }

    public IRemoteAnimationRunner getUnoccludeAnimationRunner() {
        return mUnoccludeAnimationRunner;
    }

    public boolean isHiding() {
        return mHiding;
    }

    public boolean isAnimatingScreenOff() {
        return mAnimatingScreenOff;
    }

    /**
     * Handles SET_OCCLUDED message sent by setOccluded()
     */
    private void handleSetOccluded(boolean isOccluded, boolean animate) {
        Trace.beginSection("KeyguardViewMediator#handleSetOccluded");
        Log.d(TAG, "handleSetOccluded(" + isOccluded + ")");
        synchronized (KeyguardViewMediator.this) {
            if (mHiding && isOccluded) {
                // We're in the process of going away but WindowManager wants to show a
                // SHOW_WHEN_LOCKED activity instead.
                // TODO(bc-unlock): Migrate to remote animation.
                startKeyguardExitAnimation(0, 0);
            }

            if (mOccluded != isOccluded) {
                mOccluded = isOccluded;
                mUpdateMonitor.setKeyguardOccluded(isOccluded);
                mKeyguardViewControllerLazy.get().setOccluded(isOccluded, animate
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
        // Treat these messages with priority - A call to timeout means the device should lock
        // as soon as possible and not wait for other messages on the thread to process first.
        mHandler.sendMessageAtFrontOfQueue(msg);
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

            mNeedToReshowWhenReenabled = true;
            return;
        }

        // if the keyguard is already showing, don't bother. check flags in both files
        // to account for the hiding animation which results in a delay and discrepancy
        // between flags
        if (mShowing && mKeyguardViewControllerLazy.get().isShowing()) {
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
                    mUpdateMonitor.getNextSubIdForState(TelephonyManager.SIM_STATE_ABSENT));
            final boolean disabled = SubscriptionManager.isValidSubscriptionId(
                    mUpdateMonitor.getNextSubIdForState(TelephonyManager.SIM_STATE_PERM_DISABLED));
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
            mKeyguardViewControllerLazy.get().dismissAndCollapse();
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

    /**
     * Send message to keyguard telling it to show itself
     * @see #handleShow
     */
    private void showLocked(Bundle options) {
        Trace.beginSection("KeyguardViewMediator#showLocked acquiring mShowKeyguardWakeLock");
        if (DEBUG) Log.d(TAG, "showLocked");
        // ensure we stay awake until we are finished displaying the keyguard
        mShowKeyguardWakeLock.acquire();
        Message msg = mHandler.obtainMessage(SHOW, options);
        // Treat these messages with priority - This call can originate from #doKeyguardTimeout,
        // meaning the device should lock as soon as possible and not wait for other messages on
        // the thread to process first.
        mHandler.sendMessageAtFrontOfQueue(msg);
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

    /**
     * Hide the keyguard and let {@code runner} handle the animation.
     *
     * This method should typically be called after {@link ViewMediatorCallback#keyguardDonePending}
     * was called, when we are ready to hide the keyguard. It will do nothing if we were not
     * expecting the keyguard to go away when called.
     */
    public void hideWithAnimation(IRemoteAnimationRunner runner) {
        if (!mKeyguardDonePending) {
            return;
        }

        mKeyguardExitAnimationRunner = runner;
        mViewMediatorCallback.readyForKeyguardDone();
    }

    /**
     * Disable notification shade background blurs until the keyguard is dismissed.
     * (Used during app launch animations)
     */
    public void setBlursDisabledForAppLaunch(boolean disabled) {
        mNotificationShadeDepthController.get().setBlursDisabledForAppLaunch(disabled);
    }

    public boolean isSecure() {
        return isSecure(KeyguardUpdateMonitor.getCurrentUser());
    }

    public boolean isSecure(int userId) {
        return mLockPatternUtils.isSecure(userId)
                || mUpdateMonitor.isSimPinSecure();
    }

    /**
     * Whether any of the SIMs on the device are secured with a PIN. If so, the keyguard should not
     * be dismissable until the PIN is entered, even if the device itself has no lock set.
     */
    public boolean isAnySimPinSecure() {
        for (int i = 0; i < mLastSimStates.size(); i++) {
            final int key = mLastSimStates.keyAt(i);
            if (KeyguardUpdateMonitor.isSimPinSecure(mLastSimStates.get(key))) {
                return true;
            }
        }

        return false;
    }

    public void setSwitchingUser(boolean switching) {
        mUpdateMonitor.setSwitchingUser(switching);
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

    /**
     * This broadcast receiver should be registered with the SystemUI permission.
     */
    private final BroadcastReceiver mDelayedLockBroadcastReceiver = new BroadcastReceiver() {
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
            }
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                synchronized (KeyguardViewMediator.this){
                    mShuttingDown = true;
                }
            }
        }
    };

    private void keyguardDone() {
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
                case NOTIFY_STARTED_WAKING_UP:
                    Trace.beginSection(
                            "KeyguardViewMediator#handleMessage NOTIFY_STARTED_WAKING_UP");
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
                    Trace.beginSection(
                            "KeyguardViewMediator#handleMessage START_KEYGUARD_EXIT_ANIM");
                    StartKeyguardExitAnimParams params = (StartKeyguardExitAnimParams) msg.obj;
                    mNotificationShadeWindowControllerLazy.get().batchApplyWindowLayoutParams(
                            () -> {
                                handleStartKeyguardExitAnimation(params.startTime,
                                        params.fadeoutDuration,
                                        params.mApps, params.mWallpapers, params.mNonApps,
                                        params.mFinishedCallback);
                                mFalsingCollector.onSuccessfulUnlock();
                            });
                    Trace.endSection();
                    break;
                case CANCEL_KEYGUARD_EXIT_ANIM:
                    Trace.beginSection(
                            "KeyguardViewMediator#handleMessage CANCEL_KEYGUARD_EXIT_ANIM");
                    handleCancelKeyguardExitAnimation();
                    Trace.endSection();
                    break;
                case KEYGUARD_DONE_PENDING_TIMEOUT:
                    Trace.beginSection("KeyguardViewMediator#handleMessage"
                            + " KEYGUARD_DONE_PENDING_TIMEOUT");
                    Log.w(TAG, "Timeout while waiting for activity drawn!");
                    Trace.endSection();
                    break;
                case SYSTEM_READY:
                    handleSystemReady();
                    break;
            }
        }
    };

    private void tryKeyguardDone() {
        if (DEBUG) {
            Log.d(TAG, "tryKeyguardDone: pending - " + mKeyguardDonePending + ", animRan - "
                    + mHideAnimationRun + " animRunning - " + mHideAnimationRunning);
        }
        if (!mKeyguardDonePending && mHideAnimationRun && !mHideAnimationRunning) {
            handleKeyguardDone();
        } else if (!mHideAnimationRun) {
            if (DEBUG) Log.d(TAG, "tryKeyguardDone: starting pre-hide animation");
            mHideAnimationRun = true;
            mHideAnimationRunning = true;
            mKeyguardViewControllerLazy.get()
                    .startPreHideAnimation(mHideAnimationFinishedRunnable);
        }
    }

    /**
     * @see #keyguardDone
     * @see #KEYGUARD_DONE
     */
    private void handleKeyguardDone() {
        Trace.beginSection("KeyguardViewMediator#handleKeyguardDone");
        final int currentUser = KeyguardUpdateMonitor.getCurrentUser();
        mUiBgExecutor.execute(() -> {
            if (mLockPatternUtils.isSecure(currentUser)) {
                mLockPatternUtils.getDevicePolicyManager().reportKeyguardDismissed(currentUser);
            }
        });
        if (DEBUG) Log.d(TAG, "handleKeyguardDone");
        synchronized (this) {
            resetKeyguardDonePendingLocked();
        }

        if (mGoingToSleep) {
            mUpdateMonitor.clearBiometricRecognizedWhenKeyguardDone(currentUser);
            Log.i(TAG, "Device is going to sleep, aborting keyguardDone");
            return;
        }
        setPendingLock(false); // user may have authenticated during the screen off animation
        if (mExitSecureCallback != null) {
            try {
                mExitSecureCallback.onKeyguardExitResult(true /* authenciated */);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call onKeyguardExitResult()", e);
            }

            mExitSecureCallback = null;

            // after successfully exiting securely, no need to reshow
            // the keyguard when they've released the lock
            mExternallyEnabled = true;
            mNeedToReshowWhenReenabled = false;
            updateInputRestricted();
        }

        handleHide();
        mUpdateMonitor.clearBiometricRecognizedWhenKeyguardDone(currentUser);
        Trace.endSection();
    }

    private void sendUserPresentBroadcast() {
        synchronized (this) {
            if (mBootCompleted) {
                int currentUserId = KeyguardUpdateMonitor.getCurrentUser();
                final UserHandle currentUser = new UserHandle(currentUserId);
                final UserManager um = (UserManager) mContext.getSystemService(
                        Context.USER_SERVICE);
                mUiBgExecutor.execute(() -> {
                    for (int profileId : um.getProfileIdsWithDisabled(currentUser.getIdentifier())) {
                        mContext.sendBroadcastAsUser(USER_PRESENT_INTENT, UserHandle.of(profileId));
                    }
                    mLockPatternUtils.userPresent(currentUserId);
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

            mUiBgExecutor.execute(() -> {
                // If the stream is muted, don't play the sound
                if (mAudioManager.isStreamMute(mUiSoundsStreamType)) return;

                int id = mLockSounds.play(soundId,
                        mLockSoundVolume, mLockSoundVolume, 1/*priority*/, 0/*loop*/, 1.0f/*rate*/);
                synchronized (this) {
                    mLockSoundStreamId = id;
                }
            });

        }
    }

    private void playTrustedSound() {
        playSound(mTrustedSoundId);
    }

    private void updateActivityLockScreenState(boolean showing, boolean aodShowing) {
        mUiBgExecutor.execute(() -> {
            if (DEBUG) {
                Log.d(TAG, "updateActivityLockScreenState(" + showing + ", " + aodShowing + ")");
            }
            try {
                ActivityTaskManager.getService().setLockScreenShown(showing, aodShowing);
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

            mHiding = false;
            mKeyguardExitAnimationRunner = null;
            mWakeAndUnlocking = false;
            setPendingLock(false);
            setShowingLocked(true);
            mKeyguardViewControllerLazy.get().show(options);
            resetKeyguardDonePendingLocked();
            mHideAnimationRun = false;
            adjustStatusBarLocked();
            userActivity();
            mUpdateMonitor.setKeyguardGoingAway(false);
            mKeyguardViewControllerLazy.get().setKeyguardGoingAwayState(false);
            mShowKeyguardWakeLock.release();
        }
        mKeyguardDisplayManager.show();

        // schedule 4hr idle timeout after which non-strong biometrics (i.e. weak or convenience
        // biometric) can't be used to unlock device until unlocking with strong biometric or
        // primary auth (i.e. PIN/pattern/password)
        mLockPatternUtils.scheduleNonStrongBiometricIdleTimeout(
                KeyguardUpdateMonitor.getCurrentUser());

        Trace.endSection();
    }

    private final Runnable mKeyguardGoingAwayRunnable = new Runnable() {
        @Override
        public void run() {
            Trace.beginSection("KeyguardViewMediator.mKeyGuardGoingAwayRunnable");
            if (DEBUG) Log.d(TAG, "keyguardGoingAway");
            mKeyguardViewControllerLazy.get().keyguardGoingAway();

            int flags = 0;
            if (mKeyguardViewControllerLazy.get().shouldDisableWindowAnimationsForUnlock()
                    || mWakeAndUnlocking && !mWallpaperSupportsAmbientMode) {
                flags |= KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS;
            }
            if (mKeyguardViewControllerLazy.get().isGoingToNotificationShade()
                    || mWakeAndUnlocking && mWallpaperSupportsAmbientMode) {
                // When the wallpaper supports ambient mode, the scrim isn't fully opaque during
                // wake and unlock, and we should fade in the app on top of the wallpaper
                flags |= WindowManagerPolicyConstants.KEYGUARD_GOING_AWAY_FLAG_TO_SHADE;
            }
            if (mKeyguardViewControllerLazy.get().isUnlockWithWallpaper()) {
                flags |= KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;
            }
            if (mKeyguardViewControllerLazy.get().shouldSubtleWindowAnimationsForUnlock()) {
                flags |= WindowManagerPolicyConstants
                        .KEYGUARD_GOING_AWAY_FLAG_SUBTLE_WINDOW_ANIMATIONS;
            }

            // If we are unlocking to the launcher, clear the snapshot so that any changes as part
            // of the in-window animations are reflected. This is needed even if we're not actually
            // playing in-window animations for this particular unlock since a previous unlock might
            // have changed the Launcher state.
            if (mWakeAndUnlocking
                    && KeyguardUnlockAnimationController.Companion.isNexusLauncherUnderneath()) {
                flags |= KEYGUARD_GOING_AWAY_FLAG_TO_LAUNCHER_CLEAR_SNAPSHOT;
            }

            mUpdateMonitor.setKeyguardGoingAway(true);
            mKeyguardViewControllerLazy.get().setKeyguardGoingAwayState(true);

            // Don't actually hide the Keyguard at the moment, wait for window
            // manager until it tells us it's safe to do so with
            // startKeyguardExitAnimation.
            // Posting to mUiOffloadThread to ensure that calls to ActivityTaskManager will be in
            // order.
            final int keyguardFlag = flags;
            mUiBgExecutor.execute(() -> {
                try {
                    ActivityTaskManager.getService().keyguardGoingAway(keyguardFlag);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error while calling WindowManager", e);
                }
            });
            Trace.endSection();
        }
    };

    private final Runnable mHideAnimationFinishedRunnable = () -> {
        Log.e(TAG, "mHideAnimationFinishedRunnable#run");
        mHideAnimationRunning = false;
        tryKeyguardDone();
    };

    /**
     * Handle message sent by {@link #hideLocked()}
     * @see #HIDE
     */
    private void handleHide() {
        Trace.beginSection("KeyguardViewMediator#handleHide");

        // It's possible that the device was unlocked in a dream state. It's time to wake up.
        if (mAodShowing || mDreamOverlayShowing) {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            pm.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                    "com.android.systemui:BOUNCER_DOZING");
        }

        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleHide");

            if (mustNotUnlockCurrentUser()) {
                // In split system user mode, we never unlock system user. The end user has to
                // switch to another user.
                // TODO: We should stop it early by disabling the swipe up flow. Right now swipe up
                // still completes and makes the screen blank.
                if (DEBUG) Log.d(TAG, "Split system user, quit unlocking.");
                mKeyguardExitAnimationRunner = null;
                return;
            }
            mHiding = true;

            if (mShowing && !mOccluded) {
                mKeyguardGoingAwayRunnable.run();
            } else {
                // TODO(bc-unlock): Fill parameters
                mNotificationShadeWindowControllerLazy.get().batchApplyWindowLayoutParams(() -> {
                    handleStartKeyguardExitAnimation(
                            SystemClock.uptimeMillis() + mHideAnimation.getStartOffset(),
                            mHideAnimation.getDuration(), null /* apps */, null /* wallpapers */,
                            null /* nonApps */, null /* finishedCallback */);
                });
            }
        }
        Trace.endSection();
    }

    private void handleStartKeyguardExitAnimation(long startTime, long fadeoutDuration,
            RemoteAnimationTarget[] apps, RemoteAnimationTarget[] wallpapers,
            RemoteAnimationTarget[] nonApps, IRemoteAnimationFinishedCallback finishedCallback) {
        Trace.beginSection("KeyguardViewMediator#handleStartKeyguardExitAnimation");
        Log.d(TAG, "handleStartKeyguardExitAnimation startTime=" + startTime
                + " fadeoutDuration=" + fadeoutDuration);
        synchronized (KeyguardViewMediator.this) {

            // Tell ActivityManager that we canceled the keyguard animation if
            // handleStartKeyguardExitAnimation was called, but we're not hiding the keyguard,
            // unless we're animating the surface behind the keyguard and will be hiding the
            // keyguard shortly.
            if (!mHiding
                    && !mSurfaceBehindRemoteAnimationRequested
                    && !mKeyguardStateController.isFlingingToDismissKeyguardDuringSwipeGesture()) {
                if (finishedCallback != null) {
                    // There will not execute animation, send a finish callback to ensure the remote
                    // animation won't hang there.
                    try {
                        finishedCallback.onAnimationFinished();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to call onAnimationFinished", e);
                    }
                }
                setShowingLocked(mShowing, true /* force */);
                return;
            }
            mHiding = false;
            IRemoteAnimationRunner runner = mKeyguardExitAnimationRunner;
            mKeyguardExitAnimationRunner = null;

            LatencyTracker.getInstance(mContext)
                    .onActionEnd(LatencyTracker.ACTION_LOCKSCREEN_UNLOCK);

            if (KeyguardService.sEnableRemoteKeyguardGoingAwayAnimation && runner != null
                    && finishedCallback != null) {
                // Wrap finishedCallback to clean up the keyguard state once the animation is done.
                IRemoteAnimationFinishedCallback callback =
                        new IRemoteAnimationFinishedCallback() {
                            @Override
                            public void onAnimationFinished() throws RemoteException {
                                try {
                                    finishedCallback.onAnimationFinished();
                                } catch (RemoteException e) {
                                    Slog.w(TAG, "Failed to call onAnimationFinished", e);
                                }
                                onKeyguardExitFinished();
                                mKeyguardViewControllerLazy.get().hide(0 /* startTime */,
                                        0 /* fadeoutDuration */);
                                mInteractionJankMonitor.end(CUJ_LOCKSCREEN_UNLOCK_ANIMATION);
                            }

                            @Override
                            public IBinder asBinder() {
                                return finishedCallback.asBinder();
                            }
                        };
                try {
                    mInteractionJankMonitor.begin(
                            createInteractionJankMonitorConf("RunRemoteAnimation"));
                    runner.onAnimationStart(WindowManager.TRANSIT_KEYGUARD_GOING_AWAY, apps,
                            wallpapers, nonApps, callback);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onAnimationStart", e);
                }

            // When remaining on the shade, there's no need to do a fancy remote animation,
            // it will dismiss the panel in that case.
            } else if (KeyguardService.sEnableRemoteKeyguardGoingAwayAnimation
                    && !mStatusBarStateController.leaveOpenOnKeyguardHide()
                    && apps != null && apps.length > 0) {
                mSurfaceBehindRemoteAnimationFinishedCallback = finishedCallback;
                mSurfaceBehindRemoteAnimationRunning = true;

                mInteractionJankMonitor.begin(
                        createInteractionJankMonitorConf("DismissPanel"));

                // Pass the surface and metadata to the unlock animation controller.
                mKeyguardUnlockAnimationControllerLazy.get()
                        .notifyStartSurfaceBehindRemoteAnimation(
                                apps[0], startTime, mSurfaceBehindRemoteAnimationRequested);
            } else {
                mInteractionJankMonitor.begin(
                        createInteractionJankMonitorConf("RemoteAnimationDisabled"));

                mKeyguardViewControllerLazy.get().hide(startTime, fadeoutDuration);

                // TODO(bc-animation): When remote animation is enabled for keyguard exit animation,
                // apps, wallpapers and finishedCallback are set to non-null. nonApps is not yet
                // supported, so it's always null.
                mContext.getMainExecutor().execute(() -> {
                    if (finishedCallback == null) {
                        mKeyguardUnlockAnimationControllerLazy.get()
                                .notifyFinishedKeyguardExitAnimation(false /* cancelled */);
                        mInteractionJankMonitor.end(CUJ_LOCKSCREEN_UNLOCK_ANIMATION);
                        return;
                    }

                    // TODO(bc-unlock): Sample animation, just to apply alpha animation on the app.
                    final SyncRtSurfaceTransactionApplier applier =
                            new SyncRtSurfaceTransactionApplier(
                                    mKeyguardViewControllerLazy.get().getViewRootImpl().getView());
                    final RemoteAnimationTarget primary = apps[0];
                    ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
                    anim.setDuration(400 /* duration */);
                    anim.setInterpolator(Interpolators.LINEAR);
                    anim.addUpdateListener((ValueAnimator animation) -> {
                        SyncRtSurfaceTransactionApplier.SurfaceParams params =
                                new SyncRtSurfaceTransactionApplier.SurfaceParams.Builder(
                                        primary.leash)
                                        .withAlpha(animation.getAnimatedFraction())
                                        .build();
                        applier.scheduleApply(params);
                    });
                    anim.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            try {
                                finishedCallback.onAnimationFinished();
                            } catch (RemoteException e) {
                                Slog.e(TAG, "RemoteException");
                            } finally {
                                mInteractionJankMonitor.end(CUJ_LOCKSCREEN_UNLOCK_ANIMATION);
                            }
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            try {
                                finishedCallback.onAnimationFinished();
                            } catch (RemoteException e) {
                                Slog.e(TAG, "RemoteException");
                            } finally {
                                mInteractionJankMonitor.cancel(CUJ_LOCKSCREEN_UNLOCK_ANIMATION);
                            }
                        }
                    });
                    anim.start();
                });

                onKeyguardExitFinished();
            }
        }

        Trace.endSection();
    }

    private void onKeyguardExitFinished() {
        // only play "unlock" noises if not on a call (since the incall UI
        // disables the keyguard)
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(mPhoneState)) {
            playSounds(false);
        }

        setShowingLocked(false);
        mWakeAndUnlocking = false;
        mDismissCallbackRegistry.notifyDismissSucceeded();
        resetKeyguardDonePendingLocked();
        mHideAnimationRun = false;
        adjustStatusBarLocked();
        sendUserPresentBroadcast();
    }

    private Configuration.Builder createInteractionJankMonitorConf(String tag) {
        return Configuration.Builder.withView(CUJ_LOCKSCREEN_UNLOCK_ANIMATION,
                mKeyguardViewControllerLazy.get().getViewRootImpl().getView())
                .setTag(tag);
    }

    /**
     * Whether we're currently animating between the keyguard and the app/launcher surface behind
     * it, or will be shortly (which happens if we started a fling to dismiss the keyguard).
     */
    public boolean isAnimatingBetweenKeyguardAndSurfaceBehindOrWillBe() {
        return mSurfaceBehindRemoteAnimationRunning
                || mKeyguardStateController.isFlingingToDismissKeyguard();
    }

    /**
     * Called if the keyguard exit animation has been cancelled, and we should dismiss to the
     * keyguard.
     *
     * This can happen due to the system cancelling the RemoteAnimation (due to a timeout, a new
     * app transition before finishing the current RemoteAnimation).
     */
    private void handleCancelKeyguardExitAnimation() {
        showSurfaceBehindKeyguard();
        onKeyguardExitRemoteAnimationFinished(true /* cancelled */);
    }

    /**
     * Called when we're done running the keyguard exit animation.
     *
     * This will call {@link #mSurfaceBehindRemoteAnimationFinishedCallback} to let WM know that
     * we're done with the RemoteAnimation, actually hide the keyguard, and clean up state related
     * to the keyguard exit animation.
     *
     * @param cancelled {@code true} if the animation was cancelled before it finishes.
     */
    public void onKeyguardExitRemoteAnimationFinished(boolean cancelled) {
        Log.d(TAG, "onKeyguardExitRemoteAnimationFinished");
        if (!mSurfaceBehindRemoteAnimationRunning && !mSurfaceBehindRemoteAnimationRequested) {
            Log.d(TAG, "skip onKeyguardExitRemoteAnimationFinished cancelled=" + cancelled
                    + " surfaceAnimationRunning=" + mSurfaceBehindRemoteAnimationRunning
                    + " surfaceAnimationRequested=" + mSurfaceBehindRemoteAnimationRequested);
            return;
        }

        // Block the panel from expanding, in case we were doing a swipe to dismiss gesture.
        mKeyguardViewControllerLazy.get().blockPanelExpansionFromCurrentTouch();
        final boolean wasShowing = mShowing;
        InteractionJankMonitor.getInstance().end(CUJ_LOCKSCREEN_UNLOCK_ANIMATION);

        // Post layout changes to the next frame, so we don't hang at the end of the animation.
        DejankUtils.postAfterTraversal(() -> {
            onKeyguardExitFinished();

            if (mKeyguardStateController.isDismissingFromSwipe() || wasShowing) {
                Log.d(TAG, "onKeyguardExitRemoteAnimationFinished"
                        + "#hideKeyguardViewAfterRemoteAnimation");
                mKeyguardUnlockAnimationControllerLazy.get().hideKeyguardViewAfterRemoteAnimation();
            } else {
                Log.d(TAG, "skip hideKeyguardViewAfterRemoteAnimation"
                        + " dismissFromSwipe=" + mKeyguardStateController.isDismissingFromSwipe()
                        + " wasShowing=" + wasShowing);
            }

            finishSurfaceBehindRemoteAnimation(cancelled);
            mSurfaceBehindRemoteAnimationRequested = false;

            // The remote animation is over, so we're not going away anymore.
            mKeyguardStateController.notifyKeyguardGoingAway(false);

            // Dispatch the callback on animation finishes.
            mUpdateMonitor.dispatchKeyguardDismissAnimationFinished();
        });

        mKeyguardUnlockAnimationControllerLazy.get().notifyFinishedKeyguardExitAnimation(
                cancelled);
    }

    /**
     * Tells the ActivityTaskManager that the keyguard is planning to go away, so that it makes the
     * surface behind the keyguard visible and calls {@link #handleStartKeyguardExitAnimation} with
     * the parameters needed to animate the surface.
     */
    public void showSurfaceBehindKeyguard() {
        mSurfaceBehindRemoteAnimationRequested = true;

        try {
            int flags = KEYGUARD_GOING_AWAY_FLAG_NO_WINDOW_ANIMATIONS
                    | KEYGUARD_GOING_AWAY_FLAG_WITH_WALLPAPER;

            // If we are unlocking to the launcher, clear the snapshot so that any changes as part
            // of the in-window animations are reflected. This is needed even if we're not actually
            // playing in-window animations for this particular unlock since a previous unlock might
            // have changed the Launcher state.
            if (KeyguardUnlockAnimationController.Companion.isNexusLauncherUnderneath()) {
                flags |= KEYGUARD_GOING_AWAY_FLAG_TO_LAUNCHER_CLEAR_SNAPSHOT;
            }

            ActivityTaskManager.getService().keyguardGoingAway(flags);
            mKeyguardStateController.notifyKeyguardGoingAway(true);
        } catch (RemoteException e) {
            mSurfaceBehindRemoteAnimationRequested = false;
            e.printStackTrace();
        }
    }

    /** Hides the surface behind the keyguard by re-showing the keyguard/activity lock screen. */
    public void hideSurfaceBehindKeyguard() {
        mSurfaceBehindRemoteAnimationRequested = false;
        mKeyguardStateController.notifyKeyguardGoingAway(false);
        if (mShowing) {
            setShowingLocked(true, true);
        }
    }

    /**
     * Whether we have requested to show the surface behind the keyguard, even if it's not yet
     * visible due to IPC delay.
     */
    public boolean requestedShowSurfaceBehindKeyguard() {
        return mSurfaceBehindRemoteAnimationRequested;
    }

    public boolean isAnimatingBetweenKeyguardAndSurfaceBehind() {
        return mSurfaceBehindRemoteAnimationRunning;
    }

    /** If it's running, finishes the RemoteAnimation on the surface behind the keyguard. */
    void finishSurfaceBehindRemoteAnimation(boolean cancelled) {
        if (!mSurfaceBehindRemoteAnimationRunning) {
            return;
        }

        mSurfaceBehindRemoteAnimationRunning = false;

        if (mSurfaceBehindRemoteAnimationFinishedCallback != null) {
            try {
                mSurfaceBehindRemoteAnimationFinishedCallback.onAnimationFinished();
                mSurfaceBehindRemoteAnimationFinishedCallback = null;
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void adjustStatusBarLocked() {
        adjustStatusBarLocked(false /* forceHideHomeRecentsButtons */,
                false /* forceClearFlags */);
    }

    private void adjustStatusBarLocked(boolean forceHideHomeRecentsButtons,
            boolean forceClearFlags) {
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

            // TODO (b/155663717) After restart, status bar will not properly hide home button
            //  unless disable is called to show un-hide it once first
            if (forceClearFlags) {
                mStatusBarManager.disable(flags);
            }

            if (forceHideHomeRecentsButtons || isShowingAndNotOccluded()) {
                if (!mShowHomeOverLockscreen || !mInGestureNavigationMode) {
                    flags |= StatusBarManager.DISABLE_HOME;
                }
                flags |= StatusBarManager.DISABLE_RECENT;
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
            mKeyguardViewControllerLazy.get().reset(true /* hideBouncerWhenShowing */);
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
            setShowingLocked(true);
            mKeyguardViewControllerLazy.get().dismissAndCollapse();
        }
        Trace.endSection();
    }

    private void handleNotifyStartedGoingToSleep() {
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleNotifyStartedGoingToSleep");
            mKeyguardViewControllerLazy.get().onStartedGoingToSleep();
        }
    }

    /**
     * Handle message sent by {@link #notifyFinishedGoingToSleep()}
     * @see #NOTIFY_FINISHED_GOING_TO_SLEEP
     */
    private void handleNotifyFinishedGoingToSleep() {
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleNotifyFinishedGoingToSleep");
            mKeyguardViewControllerLazy.get().onFinishedGoingToSleep();
        }
    }

    private void handleNotifyStartedWakingUp() {
        Trace.beginSection("KeyguardViewMediator#handleMotifyStartedWakingUp");
        synchronized (KeyguardViewMediator.this) {
            if (DEBUG) Log.d(TAG, "handleNotifyWakingUp");
            mKeyguardViewControllerLazy.get().onStartedWakingUp();
        }
        Trace.endSection();
    }

    private void resetKeyguardDonePendingLocked() {
        mKeyguardDonePending = false;
        mHandler.removeMessages(KEYGUARD_DONE_PENDING_TIMEOUT);
    }

    @Override
    public void onBootCompleted() {
        synchronized (this) {
            if (mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_guestUserAutoCreated)) {
                // TODO(b/191067027): Move post-boot guest creation to system_server
                mUserSwitcherController.schedulePostBootGuestCreation();
            }
            mBootCompleted = true;
            adjustStatusBarLocked(false, true);
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

    /**
     * Registers the CentralSurfaces to which the Keyguard View is mounted.
     *
     * @param centralSurfaces
     * @param panelView
     * @param biometricUnlockController
     * @param notificationContainer
     * @param bypassController
     * @return the View Controller for the Keyguard View this class is mediating.
     */
    public KeyguardViewController registerCentralSurfaces(CentralSurfaces centralSurfaces,
            NotificationPanelViewController panelView,
            @Nullable PanelExpansionStateManager panelExpansionStateManager,
            BiometricUnlockController biometricUnlockController,
            View notificationContainer, KeyguardBypassController bypassController) {
        mCentralSurfaces = centralSurfaces;
        mKeyguardViewControllerLazy.get().registerCentralSurfaces(
                centralSurfaces,
                panelView,
                panelExpansionStateManager,
                biometricUnlockController,
                notificationContainer,
                bypassController);
        return mKeyguardViewControllerLazy.get();
    }

    /**
     * Notifies to System UI that the activity behind has now been drawn, and it's safe to remove
     * the wallpaper and keyguard flag, and WindowManager has started running keyguard exit
     * animation.
     *
     * @param startTime the start time of the animation in uptime milliseconds. Deprecated.
     * @param fadeoutDuration the duration of the exit animation, in milliseconds Deprecated.
     * @deprecated Will be migrate to remote animation soon.
     */
    @Deprecated
    public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
        startKeyguardExitAnimation(0, startTime, fadeoutDuration, null, null, null, null);
    }

    /**
     * Notifies to System UI that the activity behind has now been drawn, and it's safe to remove
     * the wallpaper and keyguard flag, and System UI should start running keyguard exit animation.
     *
     * @param apps The list of apps to animate.
     * @param wallpapers The list of wallpapers to animate.
     * @param nonApps The list of non-app windows such as Bubbles to animate.
     * @param finishedCallback The callback to invoke when the animation is finished.
     */
    public void startKeyguardExitAnimation(@WindowManager.TransitionOldType int transit,
            RemoteAnimationTarget[] apps,
            RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
            IRemoteAnimationFinishedCallback finishedCallback) {
        startKeyguardExitAnimation(transit, 0, 0, apps, wallpapers, nonApps, finishedCallback);
    }

    /**
     * Notifies to System UI that the activity behind has now been drawn, and it's safe to remove
     * the wallpaper and keyguard flag, and start running keyguard exit animation.
     *
     * @param startTime the start time of the animation in uptime milliseconds. Deprecated.
     * @param fadeoutDuration the duration of the exit animation, in milliseconds Deprecated.
     * @param apps The list of apps to animate.
     * @param wallpapers The list of wallpapers to animate.
     * @param nonApps The list of non-app windows such as Bubbles to animate.
     * @param finishedCallback The callback to invoke when the animation is finished.
     */
    private void startKeyguardExitAnimation(@WindowManager.TransitionOldType int transit,
            long startTime, long fadeoutDuration,
            RemoteAnimationTarget[] apps, RemoteAnimationTarget[] wallpapers,
            RemoteAnimationTarget[] nonApps, IRemoteAnimationFinishedCallback finishedCallback) {
        Trace.beginSection("KeyguardViewMediator#startKeyguardExitAnimation");
        mInteractionJankMonitor.cancel(CUJ_LOCKSCREEN_TRANSITION_FROM_AOD);
        Message msg = mHandler.obtainMessage(START_KEYGUARD_EXIT_ANIM,
                new StartKeyguardExitAnimParams(transit, startTime, fadeoutDuration, apps,
                        wallpapers, nonApps, finishedCallback));
        mHandler.sendMessage(msg);
        Trace.endSection();
    }

    /**
     * Cancel the keyguard exit animation, usually because we were swiping to unlock but WM starts
     * a new remote animation before finishing the keyguard exit animation.
     *
     * This will dismiss the keyguard.
     */
    public void cancelKeyguardExitAnimation() {
        Trace.beginSection("KeyguardViewMediator#cancelKeyguardExitAnimation");
        Message msg = mHandler.obtainMessage(CANCEL_KEYGUARD_EXIT_ANIM);
        mHandler.sendMessage(msg);
        Trace.endSection();
    }

    public void onShortPowerPressedGoHome() {
        // do nothing
    }

    public void dismissKeyguardToLaunch(Intent intentToLaunch) {
        // do nothing
    }

    public void onSystemKeyPressed(int keycode) {
        // do nothing
    }

    public ViewMediatorCallback getViewMediatorCallback() {
        return mViewMediatorCallback;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
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
        pw.print("  mDozing: "); pw.println(mDozing);
        pw.print("  mAodShowing: "); pw.println(mAodShowing);
        pw.print("  mWaitingUntilKeyguardVisible: "); pw.println(mWaitingUntilKeyguardVisible);
        pw.print("  mKeyguardDonePending: "); pw.println(mKeyguardDonePending);
        pw.print("  mHideAnimationRun: "); pw.println(mHideAnimationRun);
        pw.print("  mPendingReset: "); pw.println(mPendingReset);
        pw.print("  mPendingLock: "); pw.println(mPendingLock);
        pw.print("  wakeAndUnlocking: "); pw.println(mWakeAndUnlocking);
        pw.print("  mPendingPinLock: "); pw.println(mPendingPinLock);
    }

    /**
     * @param dozing true when AOD - or ambient mode - is showing.
     */
    public void setDozing(boolean dozing) {
        if (dozing == mDozing) {
            return;
        }
        mDozing = dozing;
        if (!dozing) {
            mAnimatingScreenOff = false;
        }

        // Don't hide the keyguard due to a doze change if there's a lock pending, because we're
        // just going to show it again.
        // If the device is not capable of controlling the screen off animation, SysUI needs to
        // update lock screen state in ATMS here, otherwise ATMS tries to resume activities when
        // enabling doze state.
        if (mShowing || !mPendingLock || !mDozeParameters.canControlUnlockedScreenOff()) {
            setShowingLocked(mShowing);
        }
    }

    @Override
    public void onDozeAmountChanged(float linear, float interpolated) {
        // If we were animating the screen off, and we've completed the doze animation (doze amount
        // is 1f), then show the activity lock screen.
        if (mAnimatingScreenOff && mDozing && linear == 1f) {
            mAnimatingScreenOff = false;
            setShowingLocked(mShowing, true);
        }
    }

    /**
     * Set if the wallpaper supports ambient mode. This is used to trigger the right animation.
     * In case it does support it, we have to fade in the incoming app, otherwise we'll reveal it
     * with the light reveal scrim.
     */
    public void setWallpaperSupportsAmbientMode(boolean supportsAmbientMode) {
        mWallpaperSupportsAmbientMode = supportsAmbientMode;
    }

    private static class StartKeyguardExitAnimParams {

        @WindowManager.TransitionOldType int mTransit;
        long startTime;
        long fadeoutDuration;
        RemoteAnimationTarget[] mApps;
        RemoteAnimationTarget[] mWallpapers;
        RemoteAnimationTarget[] mNonApps;
        IRemoteAnimationFinishedCallback mFinishedCallback;

        private StartKeyguardExitAnimParams(@WindowManager.TransitionOldType int transit,
                long startTime, long fadeoutDuration,
                RemoteAnimationTarget[] apps, RemoteAnimationTarget[] wallpapers,
                RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback) {
            this.mTransit = transit;
            this.startTime = startTime;
            this.fadeoutDuration = fadeoutDuration;
            this.mApps = apps;
            this.mWallpapers = wallpapers;
            this.mNonApps = nonApps;
            this.mFinishedCallback = finishedCallback;
        }
    }

    void setShowingLocked(boolean showing) {
        setShowingLocked(showing, false /* forceCallbacks */);
    }

    private void setShowingLocked(boolean showing, boolean forceCallbacks) {
        final boolean aodShowing = mDozing && !mWakeAndUnlocking;
        final boolean notifyDefaultDisplayCallbacks = showing != mShowing || forceCallbacks;
        final boolean updateActivityLockScreenState = showing != mShowing
                || aodShowing != mAodShowing || forceCallbacks;
        mShowing = showing;
        mAodShowing = aodShowing;
        if (notifyDefaultDisplayCallbacks) {
            notifyDefaultDisplayCallbacks(showing);
        }
        if (updateActivityLockScreenState) {
            updateActivityLockScreenState(showing, aodShowing);
        }

    }

    private void notifyDefaultDisplayCallbacks(boolean showing) {
        // TODO(b/140053364)
        whitelistIpcs(() -> {
            int size = mKeyguardStateCallbacks.size();
            for (int i = size - 1; i >= 0; i--) {
                IKeyguardStateCallback callback = mKeyguardStateCallbacks.get(i);
                try {
                    callback.onShowingStateChanged(showing, KeyguardUpdateMonitor.getCurrentUser());
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onShowingStateChanged", e);
                    if (e instanceof DeadObjectException) {
                        mKeyguardStateCallbacks.remove(callback);
                    }
                }
            }
        });
        updateInputRestrictedLocked();
        mUiBgExecutor.execute(() -> {
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

    private void setPendingLock(boolean hasPendingLock) {
        mPendingLock = hasPendingLock;
        Trace.traceCounter(Trace.TRACE_TAG_APP, "pendingLock", mPendingLock ? 1 : 0);
    }

    public void addStateMonitorCallback(IKeyguardStateCallback callback) {
        synchronized (this) {
            mKeyguardStateCallbacks.add(callback);
            try {
                callback.onSimSecureStateChanged(mUpdateMonitor.isSimPinSecure());
                callback.onShowingStateChanged(mShowing, KeyguardUpdateMonitor.getCurrentUser());
                callback.onInputRestrictedStateChanged(mInputRestricted);
                callback.onTrustedChanged(mUpdateMonitor.getUserHasTrust(
                        KeyguardUpdateMonitor.getCurrentUser()));
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

    /**
     * Implementation of RemoteAnimationRunner that creates a new
     * {@link ActivityLaunchAnimator.Runner} whenever onAnimationStart is called, delegating the
     * remote animation methods to that runner.
     */
    private class ActivityLaunchRemoteAnimationRunner extends IRemoteAnimationRunner.Stub {

        private final ActivityLaunchAnimator.Controller mActivityLaunchController;
        @Nullable private ActivityLaunchAnimator.Runner mRunner;

        ActivityLaunchRemoteAnimationRunner(ActivityLaunchAnimator.Controller controller) {
            mActivityLaunchController = controller;
        }

        @Override
        public void onAnimationCancelled(boolean isKeyguardOccluded) throws RemoteException {
            if (mRunner != null) {
                mRunner.onAnimationCancelled(isKeyguardOccluded);
            }
        }

        @Override
        public void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers,
                RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback)
                throws RemoteException {
            mRunner = mActivityLaunchAnimator.get().createRunner(mActivityLaunchController);
            mRunner.onAnimationStart(transit, apps, wallpapers, nonApps, finishedCallback);
        }
    }

    /**
     * Subclass of {@link ActivityLaunchRemoteAnimationRunner} that calls {@link #setOccluded} when
     * onAnimationStart is called.
     */
    private class OccludeActivityLaunchRemoteAnimationRunner
            extends ActivityLaunchRemoteAnimationRunner {

        OccludeActivityLaunchRemoteAnimationRunner(
                ActivityLaunchAnimator.Controller controller) {
            super(controller);
        }

        @Override
        public void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback) throws RemoteException {
            super.onAnimationStart(transit, apps, wallpapers, nonApps, finishedCallback);

            // This is the first signal we have from WM that we're going to be occluded. Set our
            // internal state to reflect that immediately, vs. waiting for the launch animator to
            // begin. Otherwise, calls to setShowingLocked, etc. will not know that we're about to
            // be occluded and might re-show the keyguard.
            Log.d(TAG, "OccludeAnimator#onAnimationStart. Set occluded = true.");
            setOccluded(true /* isOccluded */, false /* animate */);
        }

        @Override
        public void onAnimationCancelled(boolean isKeyguardOccluded) throws RemoteException {
            super.onAnimationCancelled(isKeyguardOccluded);

            Log.d(TAG, "Occlude animation cancelled by WM. "
                    + "Setting occluded state to: " + isKeyguardOccluded);
            setOccluded(isKeyguardOccluded /* occluded */, false /* animate */);

        }
    }
}
