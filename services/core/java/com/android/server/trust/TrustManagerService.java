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

package com.android.server.trust;

import static android.service.trust.GrantTrustResult.STATUS_UNLOCKED_BY_GRANT;
import static android.service.trust.TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.admin.DevicePolicyManager;
import android.app.trust.ITrustListener;
import android.app.trust.ITrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Authorization;
import android.service.trust.GrantTrustResult;
import android.service.trust.TrustAgentService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.Xml;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.DumpUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


/**
 * Manages trust agents and trust listeners.
 *
 * It is responsible for binding to the enabled {@link android.service.trust.TrustAgentService}s
 * of each user and notifies them about events that are relevant to them.
 * It start and stops them based on the value of
 * {@link com.android.internal.widget.LockPatternUtils#getEnabledTrustAgents(int)}.
 *
 * It also keeps a set of {@link android.app.trust.ITrustListener}s that are notified whenever the
 * trust state changes for any user.
 *
 * Trust state and the setting of enabled agents is kept per user and each user has its own
 * instance of a {@link android.service.trust.TrustAgentService}.
 */
public class TrustManagerService extends SystemService {
    private static final String TAG = "TrustManagerService";
    static final boolean DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.VERBOSE);

    private static final Intent TRUST_AGENT_INTENT =
            new Intent(TrustAgentService.SERVICE_INTERFACE);
    private static final String PERMISSION_PROVIDE_AGENT = Manifest.permission.PROVIDE_TRUST_AGENT;

    private static final int MSG_REGISTER_LISTENER = 1;
    private static final int MSG_UNREGISTER_LISTENER = 2;
    private static final int MSG_DISPATCH_UNLOCK_ATTEMPT = 3;
    private static final int MSG_ENABLED_AGENTS_CHANGED = 4;
    private static final int MSG_KEYGUARD_SHOWING_CHANGED = 6;
    private static final int MSG_START_USER = 7;
    private static final int MSG_CLEANUP_USER = 8;
    private static final int MSG_SWITCH_USER = 9;
    private static final int MSG_FLUSH_TRUST_USUALLY_MANAGED = 10;
    private static final int MSG_UNLOCK_USER = 11;
    private static final int MSG_STOP_USER = 12;
    private static final int MSG_DISPATCH_UNLOCK_LOCKOUT = 13;
    private static final int MSG_REFRESH_DEVICE_LOCKED_FOR_USER = 14;
    private static final int MSG_SCHEDULE_TRUST_TIMEOUT = 15;
    private static final int MSG_USER_REQUESTED_UNLOCK = 16;
    private static final int MSG_REFRESH_TRUSTABLE_TIMERS_AFTER_AUTH = 17;
    private static final int MSG_USER_MAY_REQUEST_UNLOCK = 18;

    private static final String REFRESH_DEVICE_LOCKED_EXCEPT_USER = "except";

    private static final int TRUST_USUALLY_MANAGED_FLUSH_DELAY = 2 * 60 * 1000;
    private static final String TRUST_TIMEOUT_ALARM_TAG = "TrustManagerService.trustTimeoutForUser";
    private static final long TRUST_TIMEOUT_IN_MILLIS = 4 * 60 * 60 * 1000;
    private static final long TRUSTABLE_IDLE_TIMEOUT_IN_MILLIS = 8 * 60 * 60 * 1000;
    private static final long TRUSTABLE_TIMEOUT_IN_MILLIS = 24 * 60 * 60 * 1000;

    private static final String PRIV_NAMESPACE = "http://schemas.android.com/apk/prv/res/android";

    private final ArraySet<AgentInfo> mActiveAgents = new ArraySet<>();
    private final ArrayList<ITrustListener> mTrustListeners = new ArrayList<>();
    private final Receiver mReceiver = new Receiver();

    private final Handler mHandler;

    /* package */ final TrustArchive mArchive = new TrustArchive();
    private final Context mContext;
    private final LockPatternUtils mLockPatternUtils;
    private final UserManager mUserManager;
    private final ActivityManager mActivityManager;
    private VirtualDeviceManagerInternal mVirtualDeviceManager;

    @GuardedBy("mUserIsTrusted")
    private final SparseBooleanArray mUserIsTrusted = new SparseBooleanArray();

    //TODO(b/215724686): remove flag
    public static final boolean ENABLE_ACTIVE_UNLOCK_FLAG = SystemProperties.getBoolean(
            "fw.enable_active_unlock_flag", true);

    private enum TrustState {
        UNTRUSTED, // the phone is not unlocked by any trustagents
        TRUSTABLE, // the phone is in a semi-locked state that can be unlocked if
        // FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE is passed and a trustagent is trusted
        TRUSTED // the phone is unlocked
    };

    @GuardedBy("mUserTrustState")
    private final SparseArray<TrustManagerService.TrustState> mUserTrustState =
            new SparseArray<>();

    /**
     * Stores the locked state for users on the device. There are three different type of users
     * which are handled slightly differently:
     * <ul>
     *  <li> Users with real keyguard
     *  These are users who can be switched to ({@link UserInfo#supportsSwitchToByUser()}). Their
     *  locked state is derived by a combination of user secure state, keyguard state, trust agent
     *  decision and biometric authentication result. These are updated via
     *  {@link #refreshDeviceLockedForUser(int)} and result stored in {@link #mDeviceLockedForUser}.
     *  <li> Managed profiles with unified challenge
     *  Managed profile with unified challenge always shares the same locked state as their parent,
     *  so their locked state is not recorded in  {@link #mDeviceLockedForUser}. Instead,
     *  {@link ITrustManager#isDeviceLocked(int)} always resolves their parent user handle and
     *  queries its locked state instead.
     *  <li> Managed profiles with separate challenge
     *  Locked state for profile with separate challenge is determined by other parts of the
     *  framework (mostly PowerManager) and pushed to TrustManagerService via
     *  {@link ITrustManager#setDeviceLockedForUser(int, boolean)}. Although in a corner case when
     *  the profile has a separate but empty challenge, setting its {@link #mDeviceLockedForUser} to
     *  {@code false} is actually done by {@link #refreshDeviceLockedForUser(int)}.
     * </ul>
     * TODO: Rename {@link ITrustManager#setDeviceLockedForUser(int, boolean)} to
     * {@code setDeviceLockedForProfile} to better reflect its purpose. Unifying
     * {@code setDeviceLockedForProfile} and {@link #setDeviceLockedForUser} would also be nice.
     * At the moment they both update {@link #mDeviceLockedForUser} but have slightly different
     * side-effects: one notifies trust agents while the other sends out a broadcast.
     */
    @GuardedBy("mDeviceLockedForUser")
    private final SparseBooleanArray mDeviceLockedForUser = new SparseBooleanArray();

    @GuardedBy("mTrustUsuallyManagedForUser")
    private final SparseBooleanArray mTrustUsuallyManagedForUser = new SparseBooleanArray();

    // set to true only if user can skip bouncer
    @GuardedBy("mUsersUnlockedByBiometric")
    private final SparseBooleanArray mUsersUnlockedByBiometric = new SparseBooleanArray();

    private enum TimeoutType {
        TRUSTED,
        TRUSTABLE
    }
    private final ArrayMap<Integer, TrustedTimeoutAlarmListener> mTrustTimeoutAlarmListenerForUser =
            new ArrayMap<>();
    private final SparseArray<TrustableTimeoutAlarmListener> mTrustableTimeoutAlarmListenerForUser =
            new SparseArray<>();
    private final SparseArray<TrustableTimeoutAlarmListener>
            mIdleTrustableTimeoutAlarmListenerForUser = new SparseArray<>();
    private AlarmManager mAlarmManager;
    private final Object mAlarmLock = new Object();
    private final SettingsObserver mSettingsObserver;

    private final StrongAuthTracker mStrongAuthTracker;

    private boolean mTrustAgentsCanRun = false;
    private int mCurrentUser = UserHandle.USER_SYSTEM;

    /**
     * A class for providing dependencies to {@link TrustManagerService} in both production and test
     * cases.
     */
    protected static class Injector {
        private final LockPatternUtils mLockPatternUtils;
        private final Looper mLooper;

        public Injector(LockPatternUtils lockPatternUtils, Looper looper) {
            mLockPatternUtils = lockPatternUtils;
            mLooper = looper;
        }

        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }

        Looper getLooper() {
            return mLooper;
        }
    }

    public TrustManagerService(Context context) {
        this(context, new Injector(new LockPatternUtils(context), Looper.myLooper()));
    }

    protected TrustManagerService(Context context, Injector injector) {
        super(context);
        mContext = context;
        mHandler = createHandler(injector.getLooper());
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mLockPatternUtils = injector.getLockPatternUtils();
        mStrongAuthTracker = new StrongAuthTracker(context, injector.getLooper());
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.TRUST_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (isSafeMode()) {
            // No trust agents in safe mode.
            return;
        }
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            checkNewAgents();
            mPackageMonitor.register(mContext, mHandler.getLooper(), UserHandle.ALL, true);
            mReceiver.register(mContext);
            mLockPatternUtils.registerStrongAuthTracker(mStrongAuthTracker);
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            mTrustAgentsCanRun = true;
            refreshAgentList(UserHandle.USER_ALL);
            refreshDeviceLockedForUser(UserHandle.USER_ALL);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            maybeEnableFactoryTrustAgents(UserHandle.USER_SYSTEM);
        }
    }

    // Extend unlock config and logic
    private final class SettingsObserver extends ContentObserver {
        private final Uri TRUST_AGENTS_EXTEND_UNLOCK =
                Settings.Secure.getUriFor(Settings.Secure.TRUST_AGENTS_EXTEND_UNLOCK);

        private final Uri LOCK_SCREEN_WHEN_TRUST_LOST =
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_WHEN_TRUST_LOST);

        private final boolean mIsAutomotive;
        private final ContentResolver mContentResolver;
        private boolean mTrustAgentsNonrenewableTrust;
        private boolean mLockWhenTrustLost;

        /**
         * Creates a settings observer
         *
         * @param handler The handler to run {@link #onChange} on, or null if none.
         */
        SettingsObserver(Handler handler) {
            super(handler);

            PackageManager packageManager = getContext().getPackageManager();
            mIsAutomotive = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);

            mContentResolver = getContext().getContentResolver();
            updateContentObserver();
        }

        void updateContentObserver() {
            mContentResolver.unregisterContentObserver(this);
            mContentResolver.registerContentObserver(TRUST_AGENTS_EXTEND_UNLOCK,
                    false /* notifyForDescendents */,
                    this /* observer */,
                    mCurrentUser);
            mContentResolver.registerContentObserver(LOCK_SCREEN_WHEN_TRUST_LOST,
                    false /* notifyForDescendents */,
                    this /* observer */,
                    mCurrentUser);

            // Update the value immediately
            onChange(true /* selfChange */, TRUST_AGENTS_EXTEND_UNLOCK);
            onChange(true /* selfChange */, LOCK_SCREEN_WHEN_TRUST_LOST);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (TRUST_AGENTS_EXTEND_UNLOCK.equals(uri)) {
                // Smart lock should only grant non-renewable trust. The only exception is for
                // automotive, where it can actively unlock the head unit.
                int defaultValue = mIsAutomotive ? 0 : 1;

                mTrustAgentsNonrenewableTrust =
                        Settings.Secure.getIntForUser(
                                mContentResolver,
                                Settings.Secure.TRUST_AGENTS_EXTEND_UNLOCK,
                                defaultValue,
                                mCurrentUser) != 0;
            } else if (LOCK_SCREEN_WHEN_TRUST_LOST.equals(uri)) {
                mLockWhenTrustLost =
                        Settings.Secure.getIntForUser(
                                mContentResolver,
                                Settings.Secure.LOCK_SCREEN_WHEN_TRUST_LOST,
                                0 /* default */,
                                mCurrentUser) != 0;
            }
        }

        boolean getTrustAgentsNonrenewableTrust() {
            return mTrustAgentsNonrenewableTrust;
        }

        boolean getLockWhenTrustLost() {
            return mLockWhenTrustLost;
        }
    }

    private void maybeLockScreen(int userId) {
        if (userId != mCurrentUser) {
            return;
        }

        if (mSettingsObserver.getLockWhenTrustLost()) {
            if (DEBUG) Slog.d(TAG, "Locking device because trust was lost");
            try {
                WindowManagerGlobal.getWindowManagerService().lockNow(null);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error locking screen when trust was lost");
            }

            // If active unlocking is not allowed, cancel any pending trust timeouts because the
            // screen is already locked.
            TrustedTimeoutAlarmListener alarm = mTrustTimeoutAlarmListenerForUser.get(userId);
            if (alarm != null && mSettingsObserver.getTrustAgentsNonrenewableTrust()) {
                mAlarmManager.cancel(alarm);
                alarm.setQueued(false /* isQueued */);
            }
        }
    }

    private void scheduleTrustTimeout(boolean override, boolean isTrustableTimeout) {
        int shouldOverride = override ? 1 : 0;
        int trustableTimeout = isTrustableTimeout ? 1 : 0;
        mHandler.obtainMessage(MSG_SCHEDULE_TRUST_TIMEOUT, shouldOverride,
                trustableTimeout).sendToTarget();
    }

    private void handleScheduleTrustTimeout(boolean shouldOverride, TimeoutType timeoutType) {
        int userId = mCurrentUser;
        if (timeoutType == TimeoutType.TRUSTABLE) {
            // don't override the hard timeout unless biometric or knowledge factor authentication
            // occurs which isn't where this is called from. Override the idle timeout what the
            // calling function has determined.
            handleScheduleTrustableTimeouts(userId, shouldOverride,
                    false /* overrideHardTimeout */);
        } else {
            handleScheduleTrustedTimeout(userId, shouldOverride);
        }
    }

    /* Override both the idle and hard trustable timeouts */
    private void refreshTrustableTimers(int userId) {
        handleScheduleTrustableTimeouts(userId, true /* overrideIdleTimeout */,
                true /* overrideHardTimeout */);
    }

    private void cancelBothTrustableAlarms(int userId) {
        TrustableTimeoutAlarmListener idleTimeout =
                mIdleTrustableTimeoutAlarmListenerForUser.get(
                        userId);
        TrustableTimeoutAlarmListener trustableTimeout =
                mTrustableTimeoutAlarmListenerForUser.get(
                        userId);
        if (idleTimeout != null && idleTimeout.isQueued()) {
            idleTimeout.setQueued(false);
            mAlarmManager.cancel(idleTimeout);
        }
        if (trustableTimeout != null && trustableTimeout.isQueued()) {
            trustableTimeout.setQueued(false);
            mAlarmManager.cancel(trustableTimeout);
        }
    }

    private void handleScheduleTrustedTimeout(int userId, boolean shouldOverride) {
        long when = SystemClock.elapsedRealtime() + TRUST_TIMEOUT_IN_MILLIS;
        TrustedTimeoutAlarmListener alarm = mTrustTimeoutAlarmListenerForUser.get(userId);

        // Cancel existing trust timeouts for this user if needed.
        if (alarm != null) {
            if (!shouldOverride && alarm.isQueued()) {
                if (DEBUG) Slog.d(TAG, "Found existing trust timeout alarm. Skipping.");
                return;
            }
            mAlarmManager.cancel(alarm);
        } else {
            alarm = new TrustedTimeoutAlarmListener(userId);
            mTrustTimeoutAlarmListenerForUser.put(userId, alarm);
        }

        if (DEBUG) Slog.d(TAG, "\tSetting up trust timeout alarm");
        alarm.setQueued(true /* isQueued */);
        mAlarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, when, TRUST_TIMEOUT_ALARM_TAG, alarm,
                mHandler);
    }

    private void handleScheduleTrustableTimeouts(int userId, boolean overrideIdleTimeout,
            boolean overrideHardTimeout) {
        setUpIdleTimeout(userId, overrideIdleTimeout);
        setUpHardTimeout(userId, overrideHardTimeout);
    }

    private void setUpIdleTimeout(int userId, boolean overrideIdleTimeout) {
        long when = SystemClock.elapsedRealtime() + TRUSTABLE_IDLE_TIMEOUT_IN_MILLIS;
        TrustableTimeoutAlarmListener alarm = mIdleTrustableTimeoutAlarmListenerForUser.get(userId);
        mContext.enforceCallingOrSelfPermission(Manifest.permission.SCHEDULE_EXACT_ALARM, null);

        // Cancel existing trustable timeouts for this user if needed.
        if (alarm != null) {
            if (!overrideIdleTimeout && alarm.isQueued()) {
                if (DEBUG) Slog.d(TAG, "Found existing trustable timeout alarm. Skipping.");
                return;
            }
            mAlarmManager.cancel(alarm);
        } else {
            alarm = new TrustableTimeoutAlarmListener(userId);
            mIdleTrustableTimeoutAlarmListenerForUser.put(userId, alarm);
        }

        if (DEBUG) Slog.d(TAG, "\tSetting up trustable idle timeout alarm");
        alarm.setQueued(true /* isQueued */);
        mAlarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, when, TRUST_TIMEOUT_ALARM_TAG, alarm,
                mHandler);
    }

    private void setUpHardTimeout(int userId, boolean overrideHardTimeout) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.SCHEDULE_EXACT_ALARM, null);
        TrustableTimeoutAlarmListener alarm = mTrustableTimeoutAlarmListenerForUser.get(userId);

        // if the alarm doesn't exist, or hasn't been queued, or needs to be overridden we need to
        // set it
        if (alarm == null || !alarm.isQueued() || overrideHardTimeout) {
            // schedule hard limit on renewable trust use
            long when = SystemClock.elapsedRealtime() + TRUSTABLE_TIMEOUT_IN_MILLIS;
            if (alarm == null) {
                alarm = new TrustableTimeoutAlarmListener(userId);
                mTrustableTimeoutAlarmListenerForUser.put(userId, alarm);
            } else if (overrideHardTimeout) {
                mAlarmManager.cancel(alarm);
            }
            if (DEBUG) Slog.d(TAG, "\tSetting up trustable hard timeout alarm");
            alarm.setQueued(true /* isQueued */);
            mAlarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, when, TRUST_TIMEOUT_ALARM_TAG, alarm,
                    mHandler);
        }
    }

   // Agent management

    private static final class AgentInfo {
        CharSequence label;
        Drawable icon;
        ComponentName component; // service that implements ITrustAgent
        SettingsAttrs settings; // setting to launch to modify agent.
        TrustAgentWrapper agent;
        int userId;

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof AgentInfo)) {
                return false;
            }
            AgentInfo o = (AgentInfo) other;
            return component.equals(o.component) && userId == o.userId;
        }

        @Override
        public int hashCode() {
            return component.hashCode() * 31 + userId;
        }
    }

    private void updateTrustAll() {
        List<UserInfo> userInfos = mUserManager.getAliveUsers();
        for (UserInfo userInfo : userInfos) {
            updateTrust(userInfo.id, 0);
        }
    }

    /** Triggers a trust update. */
    public void updateTrust(
            int userId,
            int flags) {
        updateTrust(userId, flags, null);
    }

    /** Triggers a trust update. */
    public void updateTrust(
            int userId,
            int flags,
            @Nullable AndroidFuture<GrantTrustResult> resultCallback) {
        updateTrust(userId, flags, false /* isFromUnlock */, resultCallback);
    }

    private void updateTrust(
            int userId,
            int flags,
            boolean isFromUnlock,
            @Nullable AndroidFuture<GrantTrustResult> resultCallback) {
        if (ENABLE_ACTIVE_UNLOCK_FLAG) {
            updateTrustWithRenewableUnlock(userId, flags, isFromUnlock, resultCallback);
        } else {
            updateTrustWithNonrenewableTrust(userId, flags, isFromUnlock);
        }
    }

    private void updateTrustWithNonrenewableTrust(int userId, int flags, boolean isFromUnlock) {
        boolean managed = aggregateIsTrustManaged(userId);
        dispatchOnTrustManagedChanged(managed, userId);
        if (mStrongAuthTracker.isTrustAllowedForUser(userId)
                && isTrustUsuallyManagedInternal(userId) != managed) {
            updateTrustUsuallyManaged(userId, managed);
        }

        boolean trusted = aggregateIsTrusted(userId);
        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        boolean showingKeyguard = true;
        try {
            showingKeyguard = wm.isKeyguardLocked();
        } catch (RemoteException e) {
        }

        boolean changed;
        synchronized (mUserIsTrusted) {
            if (mSettingsObserver.getTrustAgentsNonrenewableTrust()) {
                // For non-renewable trust agents can only set the device to trusted if it already
                // trusted or the device is unlocked. Attempting to set the device as trusted
                // when the device is locked will be ignored.
                changed = mUserIsTrusted.get(userId) != trusted;
                trusted = trusted
                        && (!showingKeyguard || isFromUnlock || !changed)
                        && userId == mCurrentUser;
                if (DEBUG) {
                    Slog.d(TAG, "Extend unlock setting trusted as " + Boolean.toString(trusted)
                            + " && " + Boolean.toString(!showingKeyguard)
                            + " && " + Boolean.toString(userId == mCurrentUser));
                }
            }
            changed = mUserIsTrusted.get(userId) != trusted;
            mUserIsTrusted.put(userId, trusted);
        }
        dispatchOnTrustChanged(
                trusted,
                false /* newlyUnlocked */,
                userId,
                flags,
                getTrustGrantedMessages(userId));
        if (changed) {
            refreshDeviceLockedForUser(userId);
            if (!trusted) {
                maybeLockScreen(userId);
            } else {
                scheduleTrustTimeout(false /* override */, false /* isTrustableTimeout*/);
            }
        }
    }

    private void updateTrustWithRenewableUnlock(
            int userId,
            int flags,
            boolean isFromUnlock,
            @Nullable AndroidFuture<GrantTrustResult> resultCallback) {
        boolean managed = aggregateIsTrustManaged(userId);
        dispatchOnTrustManagedChanged(managed, userId);
        if (mStrongAuthTracker.isTrustAllowedForUser(userId)
                && isTrustUsuallyManagedInternal(userId) != managed) {
            updateTrustUsuallyManaged(userId, managed);
        }

        boolean trustedByAtLeastOneAgent = aggregateIsTrusted(userId);
        boolean trustableByAtLeastOneAgent = aggregateIsTrustable(userId);
        boolean wasTrusted;
        boolean wasTrustable;
        TrustState pendingTrustState;

        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        boolean alreadyUnlocked = false;
        try {
            alreadyUnlocked = !wm.isKeyguardLocked();
        } catch (RemoteException e) {
        }

        synchronized (mUserTrustState) {
            wasTrusted = (mUserTrustState.get(userId) == TrustState.TRUSTED);
            wasTrustable = (mUserTrustState.get(userId) == TrustState.TRUSTABLE);
            boolean isAutomotive = getContext().getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_AUTOMOTIVE);
            boolean renewingTrust = wasTrustable && (
                    (flags & TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) != 0);
            boolean canMoveToTrusted =
                    alreadyUnlocked || isFromUnlock || renewingTrust || isAutomotive;
            boolean upgradingTrustForCurrentUser = (userId == mCurrentUser);

            if (trustedByAtLeastOneAgent && wasTrusted) {
                // no change
                return;
            } else if (trustedByAtLeastOneAgent && canMoveToTrusted
                    && upgradingTrustForCurrentUser) {
                pendingTrustState = TrustState.TRUSTED;
            } else if (trustableByAtLeastOneAgent && (wasTrusted || wasTrustable)
                    && upgradingTrustForCurrentUser) {
                pendingTrustState = TrustState.TRUSTABLE;
            } else {
                pendingTrustState = TrustState.UNTRUSTED;
            }

            mUserTrustState.put(userId, pendingTrustState);
        }
        if (DEBUG) Slog.d(TAG, "pendingTrustState: " + pendingTrustState);

        boolean isNowTrusted = pendingTrustState == TrustState.TRUSTED;
        boolean newlyUnlocked = !alreadyUnlocked && isNowTrusted;
        dispatchOnTrustChanged(
                isNowTrusted, newlyUnlocked, userId, flags, getTrustGrantedMessages(userId));
        if (isNowTrusted != wasTrusted) {
            refreshDeviceLockedForUser(userId);
            if (!isNowTrusted) {
                maybeLockScreen(userId);
            } else {
                boolean isTrustableTimeout =
                        (flags & FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) != 0;
                // Every time we grant renewable trust we should override the idle trustable
                // timeout. If this is for non-renewable trust, then we shouldn't override.
                scheduleTrustTimeout(isTrustableTimeout /* override */,
                        isTrustableTimeout /* isTrustableTimeout */);
            }
        }

        boolean shouldSendCallback = newlyUnlocked;
        if (shouldSendCallback) {
            if (resultCallback != null) {
                if (DEBUG) Slog.d(TAG, "calling back with UNLOCKED_BY_GRANT");
                resultCallback.complete(new GrantTrustResult(STATUS_UNLOCKED_BY_GRANT));
            }
        }

        if ((wasTrusted || wasTrustable) && pendingTrustState == TrustState.UNTRUSTED) {
            if (DEBUG) Slog.d(TAG, "Trust was revoked, destroy trustable alarms");
            cancelBothTrustableAlarms(userId);
        }
    }

    private void updateTrustUsuallyManaged(int userId, boolean managed) {
        synchronized (mTrustUsuallyManagedForUser) {
            mTrustUsuallyManagedForUser.put(userId, managed);
        }
        // Wait a few minutes before committing to flash, in case the trust agent is transiently not
        // managing trust (crashed, needs to acknowledge DPM restrictions, etc).
        mHandler.removeMessages(MSG_FLUSH_TRUST_USUALLY_MANAGED);
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(MSG_FLUSH_TRUST_USUALLY_MANAGED),
                TRUST_USUALLY_MANAGED_FLUSH_DELAY);
    }

    public long addEscrowToken(byte[] token, int userId) {
        return mLockPatternUtils.addEscrowToken(token, userId,
                (long handle, int userid) -> {
                    dispatchEscrowTokenActivatedLocked(handle, userid);
                });
    }

    public boolean removeEscrowToken(long handle, int userId) {
        return mLockPatternUtils.removeEscrowToken(handle, userId);
    }

    public boolean isEscrowTokenActive(long handle, int userId) {
        return mLockPatternUtils.isEscrowTokenActive(handle, userId);
    }

    public void unlockUserWithToken(long handle, byte[] token, int userId) {
        mLockPatternUtils.unlockUserWithToken(handle, token, userId);
    }

    /**
     * Locks the phone and requires some auth (not trust) like a biometric or passcode before
     * unlocking.
     */
    public void lockUser(int userId) {
        mLockPatternUtils.requireStrongAuth(
                StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED, userId);
        try {
            WindowManagerGlobal.getWindowManagerService().lockNow(null);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error locking screen when called from trust agent");
        }
    }

    void showKeyguardErrorMessage(CharSequence message) {
        dispatchOnTrustError(message);
    }

    void refreshAgentList(int userIdOrAll) {
        if (DEBUG) Slog.d(TAG, "refreshAgentList(" + userIdOrAll + ")");
        if (!mTrustAgentsCanRun) {
            return;
        }
        if (userIdOrAll != UserHandle.USER_ALL && userIdOrAll < UserHandle.USER_SYSTEM) {
            Log.e(TAG, "refreshAgentList(userId=" + userIdOrAll + "): Invalid user handle,"
                    + " must be USER_ALL or a specific user.", new Throwable("here"));
            userIdOrAll = UserHandle.USER_ALL;
        }
        PackageManager pm = mContext.getPackageManager();

        List<UserInfo> userInfos;
        if (userIdOrAll == UserHandle.USER_ALL) {
            userInfos = mUserManager.getAliveUsers();
        } else {
            userInfos = new ArrayList<>();
            userInfos.add(mUserManager.getUserInfo(userIdOrAll));
        }
        LockPatternUtils lockPatternUtils = mLockPatternUtils;

        ArraySet<AgentInfo> obsoleteAgents = new ArraySet<>();
        obsoleteAgents.addAll(mActiveAgents);

        for (UserInfo userInfo : userInfos) {
            if (userInfo == null || userInfo.partial || !userInfo.isEnabled()
                    || userInfo.guestToRemove) continue;
            if (!userInfo.supportsSwitchToByUser()) {
                if (DEBUG) Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id
                        + ": switchToByUser=false");
                continue;
            }
            if (!mActivityManager.isUserRunning(userInfo.id)) {
                if (DEBUG) Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id
                        + ": user not started");
                continue;
            }
            if (!lockPatternUtils.isSecure(userInfo.id)) {
                if (DEBUG) Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id
                        + ": no secure credential");
                continue;
            }

            DevicePolicyManager dpm = lockPatternUtils.getDevicePolicyManager();
            int disabledFeatures = dpm.getKeyguardDisabledFeatures(null, userInfo.id);
            final boolean disableTrustAgents =
                    (disabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS) != 0;

            List<ComponentName> enabledAgents = lockPatternUtils.getEnabledTrustAgents(userInfo.id);
            if (enabledAgents.isEmpty()) {
                if (DEBUG) Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id
                        + ": no agents enabled by user");
                continue;
            }
            List<ResolveInfo> resolveInfos = resolveAllowedTrustAgents(pm, userInfo.id);
            for (ResolveInfo resolveInfo : resolveInfos) {
                ComponentName name = getComponentName(resolveInfo);

                if (!enabledAgents.contains(name)) {
                    if (DEBUG) Slog.d(TAG, "refreshAgentList: skipping "
                            + name.flattenToShortString() + " u"+ userInfo.id
                            + ": not enabled by user");
                    continue;
                }
                if (disableTrustAgents) {
                    List<PersistableBundle> config =
                            dpm.getTrustAgentConfiguration(null /* admin */, name, userInfo.id);
                    // Disable agent if no features are enabled.
                    if (config == null || config.isEmpty()) {
                        if (DEBUG) Slog.d(TAG, "refreshAgentList: skipping "
                                + name.flattenToShortString() + " u"+ userInfo.id
                                + ": not allowed by DPM");
                        continue;
                    }
                }
                AgentInfo agentInfo = new AgentInfo();
                agentInfo.component = name;
                agentInfo.userId = userInfo.id;
                if (!mActiveAgents.contains(agentInfo)) {
                    agentInfo.label = resolveInfo.loadLabel(pm);
                    agentInfo.icon = resolveInfo.loadIcon(pm);
                    agentInfo.settings = getSettingsAttrs(pm, resolveInfo);
                } else {
                    int index = mActiveAgents.indexOf(agentInfo);
                    agentInfo = mActiveAgents.valueAt(index);
                }

                boolean directUnlock = false;
                if (agentInfo.settings != null) {
                    directUnlock = resolveInfo.serviceInfo.directBootAware
                        && agentInfo.settings.canUnlockProfile;
                }

                if (directUnlock) {
                    if (DEBUG) Slog.d(TAG, "refreshAgentList: trustagent " + name
                            + "of user " + userInfo.id + "can unlock user profile.");
                }

                if (!mUserManager.isUserUnlockingOrUnlocked(userInfo.id)
                        && !directUnlock) {
                    if (DEBUG) Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id
                            + "'s trust agent " + name + ": FBE still locked and "
                            + " the agent cannot unlock user profile.");
                    continue;
                }

                if (!mStrongAuthTracker.canAgentsRunForUser(userInfo.id)) {
                    int flag = mStrongAuthTracker.getStrongAuthForUser(userInfo.id);
                    if (flag != StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT) {
                        if (flag != StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT
                            || !directUnlock) {
                            if (DEBUG)
                                Slog.d(TAG, "refreshAgentList: skipping user " + userInfo.id
                                    + ": prevented by StrongAuthTracker = 0x"
                                    + Integer.toHexString(mStrongAuthTracker.getStrongAuthForUser(
                                    userInfo.id)));
                            continue;
                        }
                    }
                }

                if (agentInfo.agent == null) {
                    agentInfo.agent = new TrustAgentWrapper(mContext, this,
                            new Intent().setComponent(name), userInfo.getUserHandle());
                }

                if (!mActiveAgents.contains(agentInfo)) {
                    mActiveAgents.add(agentInfo);
                } else {
                    obsoleteAgents.remove(agentInfo);
                }
            }
        }

        boolean trustMayHaveChanged = false;
        for (int i = 0; i < obsoleteAgents.size(); i++) {
            AgentInfo info = obsoleteAgents.valueAt(i);
            if (userIdOrAll == UserHandle.USER_ALL || userIdOrAll == info.userId) {
                if (info.agent.isManagingTrust()) {
                    trustMayHaveChanged = true;
                }
                info.agent.destroy();
                mActiveAgents.remove(info);
            }
        }

        if (trustMayHaveChanged) {
            if (userIdOrAll == UserHandle.USER_ALL) {
                updateTrustAll();
            } else {
                updateTrust(userIdOrAll, 0);
            }
        }
    }

    boolean isDeviceLockedInner(int userId) {
        synchronized (mDeviceLockedForUser) {
            return mDeviceLockedForUser.get(userId, true);
        }
    }

    private void refreshDeviceLockedForUser(int userId) {
        refreshDeviceLockedForUser(userId, UserHandle.USER_NULL);
    }

    /**
     * Update the user's locked state. Only applicable to users with a real keyguard
     * ({@link UserInfo#supportsSwitchToByUser}) and unsecured managed profiles.
     *
     * If this is called due to an unlock operation set unlockedUser to prevent the lock from
     * being prematurely reset for that user while keyguard is still in the process of going away.
     */
    private void refreshDeviceLockedForUser(int userId, int unlockedUser) {
        if (userId != UserHandle.USER_ALL && userId < UserHandle.USER_SYSTEM) {
            Log.e(TAG, "refreshDeviceLockedForUser(userId=" + userId + "): Invalid user handle,"
                    + " must be USER_ALL or a specific user.", new Throwable("here"));
            userId = UserHandle.USER_ALL;
        }
        List<UserInfo> userInfos;
        if (userId == UserHandle.USER_ALL) {
            userInfos = mUserManager.getAliveUsers();
        } else {
            userInfos = new ArrayList<>();
            userInfos.add(mUserManager.getUserInfo(userId));
        }

        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();

        for (int i = 0; i < userInfos.size(); i++) {
            UserInfo info = userInfos.get(i);

            if (info == null || info.partial || !info.isEnabled() || info.guestToRemove) {
                continue;
            }

            int id = info.id;
            boolean secure = mLockPatternUtils.isSecure(id);

            if (!info.supportsSwitchToByUser()) {
                if (info.isManagedProfile() && !secure) {
                    setDeviceLockedForUser(id, false);
                }
                continue;
            }

            boolean trusted = aggregateIsTrusted(id);
            boolean showingKeyguard = true;
            boolean biometricAuthenticated = false;
            boolean currentUserIsUnlocked = false;

            if (mCurrentUser == id) {
                synchronized(mUsersUnlockedByBiometric) {
                    biometricAuthenticated = mUsersUnlockedByBiometric.get(id, false);
                }
                try {
                    showingKeyguard = wm.isKeyguardLocked();
                } catch (RemoteException e) {
                    Log.w(TAG, "Unable to check keyguard lock state", e);
                }
                currentUserIsUnlocked = unlockedUser == id;
            }
            final boolean deviceLocked = secure && showingKeyguard && !trusted
                    && !biometricAuthenticated;
            if (deviceLocked && currentUserIsUnlocked) {
                // keyguard is finishing but may not have completed going away yet
                continue;
            }

            setDeviceLockedForUser(id, deviceLocked);
        }
    }

    private void setDeviceLockedForUser(@UserIdInt int userId, boolean locked) {
        final boolean changed;
        synchronized (mDeviceLockedForUser) {
            changed = isDeviceLockedInner(userId) != locked;
            mDeviceLockedForUser.put(userId, locked);
        }
        if (changed) {
            dispatchDeviceLocked(userId, locked);
            Authorization.onLockScreenEvent(locked, userId, null,
                    getBiometricSids(userId));
            // Also update the user's profiles who have unified challenge, since they
            // share the same unlocked state (see {@link #isDeviceLocked(int)})
            for (int profileHandle : mUserManager.getEnabledProfileIds(userId)) {
                if (mLockPatternUtils.isManagedProfileWithUnifiedChallenge(profileHandle)) {
                    Authorization.onLockScreenEvent(locked, profileHandle, null,
                            getBiometricSids(profileHandle));
                }
            }
        }
    }

    private void dispatchDeviceLocked(int userId, boolean isLocked) {
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo agent = mActiveAgents.valueAt(i);
            if (agent.userId == userId) {
                if (isLocked) {
                    agent.agent.onDeviceLocked();
                } else{
                    agent.agent.onDeviceUnlocked();
                }
            }
        }
    }

    private void dispatchEscrowTokenActivatedLocked(long handle, int userId) {
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo agent = mActiveAgents.valueAt(i);
            if (agent.userId == userId) {
                agent.agent.onEscrowTokenActivated(handle, userId);
            }
        }
    }

    void updateDevicePolicyFeatures() {
        boolean changed = false;
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.agent.isConnected()) {
                info.agent.updateDevicePolicyFeatures();
                changed = true;
            }
        }
        if (changed) {
            mArchive.logDevicePolicyChanged();
        }
    }

    private void removeAgentsOfPackage(String packageName) {
        boolean trustMayHaveChanged = false;
        for (int i = mActiveAgents.size() - 1; i >= 0; i--) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (packageName.equals(info.component.getPackageName())) {
                Log.i(TAG, "Resetting agent " + info.component.flattenToShortString());
                if (info.agent.isManagingTrust()) {
                    trustMayHaveChanged = true;
                }
                info.agent.destroy();
                mActiveAgents.removeAt(i);
            }
        }
        if (trustMayHaveChanged) {
            updateTrustAll();
        }
    }

    public void resetAgent(ComponentName name, int userId) {
        boolean trustMayHaveChanged = false;
        for (int i = mActiveAgents.size() - 1; i >= 0; i--) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (name.equals(info.component) && userId == info.userId) {
                Log.i(TAG, "Resetting agent " + info.component.flattenToShortString());
                if (info.agent.isManagingTrust()) {
                    trustMayHaveChanged = true;
                }
                info.agent.destroy();
                mActiveAgents.removeAt(i);
            }
        }
        if (trustMayHaveChanged) {
            updateTrust(userId, 0);
        }
        refreshAgentList(userId);
    }

    private SettingsAttrs getSettingsAttrs(PackageManager pm, ResolveInfo resolveInfo) {
        if (resolveInfo == null || resolveInfo.serviceInfo == null
                || resolveInfo.serviceInfo.metaData == null) return null;
        String cn = null;
        boolean canUnlockProfile = false;

        XmlResourceParser parser = null;
        Exception caughtException = null;
        try {
            parser = resolveInfo.serviceInfo.loadXmlMetaData(pm,
                    TrustAgentService.TRUST_AGENT_META_DATA);
            if (parser == null) {
                Slog.w(TAG, "Can't find " + TrustAgentService.TRUST_AGENT_META_DATA + " meta-data");
                return null;
            }
            Resources res = pm.getResourcesForApplication(resolveInfo.serviceInfo.applicationInfo);
            AttributeSet attrs = Xml.asAttributeSet(parser);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // Drain preamble.
            }
            String nodeName = parser.getName();
            if (!"trust-agent".equals(nodeName)) {
                Slog.w(TAG, "Meta-data does not start with trust-agent tag");
                return null;
            }
            TypedArray sa = res
                    .obtainAttributes(attrs, com.android.internal.R.styleable.TrustAgent);
            cn = sa.getString(com.android.internal.R.styleable.TrustAgent_settingsActivity);
            canUnlockProfile = attrs.getAttributeBooleanValue(
                    PRIV_NAMESPACE, "unlockProfile", false);
            sa.recycle();
        } catch (PackageManager.NameNotFoundException e) {
            caughtException = e;
        } catch (IOException e) {
            caughtException = e;
        } catch (XmlPullParserException e) {
            caughtException = e;
        } finally {
            if (parser != null) parser.close();
        }
        if (caughtException != null) {
            Slog.w(TAG, "Error parsing : " + resolveInfo.serviceInfo.packageName, caughtException);
            return null;
        }
        if (cn == null) {
            return null;
        }
        if (cn.indexOf('/') < 0) {
            cn = resolveInfo.serviceInfo.packageName + "/" + cn;
        }
        return new SettingsAttrs(ComponentName.unflattenFromString(cn), canUnlockProfile);
    }

    private ComponentName getComponentName(ResolveInfo resolveInfo) {
        if (resolveInfo == null || resolveInfo.serviceInfo == null) return null;
        return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
    }

    private void maybeEnableFactoryTrustAgents(int userId) {
        if (0 != Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.TRUST_AGENTS_INITIALIZED, 0, userId)) {
            return;
        }
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = resolveAllowedTrustAgents(pm, userId);
        ComponentName defaultAgent = getDefaultFactoryTrustAgent(mContext);
        boolean shouldUseDefaultAgent = defaultAgent != null;
        ArraySet<ComponentName> discoveredAgents = new ArraySet<>();

        if (shouldUseDefaultAgent) {
            discoveredAgents.add(defaultAgent);
            Log.i(TAG, "Enabling " + defaultAgent + " because it is a default agent.");
        } else { // A default agent is not set; perform regular trust agent discovery
            for (ResolveInfo resolveInfo : resolveInfos) {
                ComponentName componentName = getComponentName(resolveInfo);
                if (!isSystemTrustAgent(resolveInfo)) {
                    Log.i(TAG, "Leaving agent " + componentName + " disabled because package "
                            + "is not a system package.");
                    continue;
                }
                discoveredAgents.add(componentName);
            }
        }

        enableNewAgents(discoveredAgents, userId);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.TRUST_AGENTS_INITIALIZED, 1, userId);
    }

    private void checkNewAgents() {
        for (UserInfo userInfo : mUserManager.getAliveUsers()) {
            checkNewAgentsForUser(userInfo.id);
        }
    }

    /**
     * Checks for any new trust agents that become available after the first boot, add them to the
     * list of known agents, and enable them if they should be enabled by default.
     */
    private void checkNewAgentsForUser(int userId) {
        // When KNOWN_TRUST_AGENTS_INITIALIZED is not set, only update the known agent list but do
        // not enable any agent.
        // These agents will be enabled by #maybeEnableFactoryTrustAgents if this is the first time
        // that this device boots and TRUST_AGENTS_INITIALIZED is not already set.
        // Otherwise, these agents may have been manually disabled by the user, and we should not
        // re-enable them.
        if (0 == Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.KNOWN_TRUST_AGENTS_INITIALIZED, 0, userId)) {
            initializeKnownAgents(userId);
            return;
        }

        List<ComponentName> knownAgents = mLockPatternUtils.getKnownTrustAgents(userId);
        List<ResolveInfo> agentInfoList = resolveAllowedTrustAgents(mContext.getPackageManager(),
                userId);
        ArraySet<ComponentName> newAgents = new ArraySet<>(agentInfoList.size());
        ArraySet<ComponentName> newSystemAgents = new ArraySet<>(agentInfoList.size());

        for (ResolveInfo agentInfo : agentInfoList) {
            ComponentName agentComponentName = getComponentName(agentInfo);
            if (knownAgents.contains(agentComponentName)) {
                continue;
            }
            newAgents.add(agentComponentName);
            if (isSystemTrustAgent(agentInfo)) {
                newSystemAgents.add(agentComponentName);
            }
        }

        if (newAgents.isEmpty()) {
            return;
        }

        ArraySet<ComponentName> updatedKnowAgents = new ArraySet<>(knownAgents);
        updatedKnowAgents.addAll(newAgents);
        mLockPatternUtils.setKnownTrustAgents(updatedKnowAgents, userId);

        // Do not auto enable new trust agents when the default agent is set
        boolean hasDefaultAgent = getDefaultFactoryTrustAgent(mContext) != null;
        if (!hasDefaultAgent) {
            enableNewAgents(newSystemAgents, userId);
        }
    }

    private void enableNewAgents(Collection<ComponentName> agents, int userId) {
        if (agents.isEmpty()) {
            return;
        }

        ArraySet<ComponentName> agentsToEnable = new ArraySet<>(agents);
        agentsToEnable.addAll(mLockPatternUtils.getEnabledTrustAgents(userId));
        mLockPatternUtils.setEnabledTrustAgents(agentsToEnable, userId);
    }

    private void initializeKnownAgents(int userId) {
        List<ResolveInfo> agentInfoList = resolveAllowedTrustAgents(mContext.getPackageManager(),
                userId);
        ArraySet<ComponentName> agentComponentNames = new ArraySet<>(agentInfoList.size());
        for (ResolveInfo agentInfo : agentInfoList) {
            agentComponentNames.add(getComponentName(agentInfo));
        }
        mLockPatternUtils.setKnownTrustAgents(agentComponentNames, userId);
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.KNOWN_TRUST_AGENTS_INITIALIZED, 1, userId);
    }

    /**
     * Returns the {@link ComponentName} for the default trust agent, or {@code null} if there
     * is no trust agent set.
     */
    private static ComponentName getDefaultFactoryTrustAgent(Context context) {
        String defaultTrustAgent = context.getResources()
            .getString(com.android.internal.R.string.config_defaultTrustAgent);
        if (TextUtils.isEmpty(defaultTrustAgent)) {
            return null;
        }
        return ComponentName.unflattenFromString(defaultTrustAgent);
    }

    private List<ResolveInfo> resolveAllowedTrustAgents(PackageManager pm, int userId) {
        List<ResolveInfo> resolveInfos = pm.queryIntentServicesAsUser(TRUST_AGENT_INTENT,
                PackageManager.GET_META_DATA |
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                userId);
        ArrayList<ResolveInfo> allowedAgents = new ArrayList<>(resolveInfos.size());
        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo.serviceInfo == null) continue;
            if (resolveInfo.serviceInfo.applicationInfo == null) continue;
            String packageName = resolveInfo.serviceInfo.packageName;
            if (pm.checkPermission(PERMISSION_PROVIDE_AGENT, packageName)
                    != PackageManager.PERMISSION_GRANTED) {
                ComponentName name = getComponentName(resolveInfo);
                Log.w(TAG, "Skipping agent " + name + " because package does not have"
                        + " permission " + PERMISSION_PROVIDE_AGENT + ".");
                continue;
            }
            allowedAgents.add(resolveInfo);
        }
        return allowedAgents;
    }

    private static boolean isSystemTrustAgent(ResolveInfo agentInfo) {
        return (agentInfo.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    // Agent dispatch and aggregation

    private boolean aggregateIsTrusted(int userId) {
        if (!mStrongAuthTracker.isTrustAllowedForUser(userId)) {
            return false;
        }
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                if (info.agent.isTrusted()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean aggregateIsTrustable(int userId) {
        if (!mStrongAuthTracker.isTrustAllowedForUser(userId)) {
            return false;
        }
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                if (info.agent.isTrustable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * We downgrade to trustable whenever keyguard changes its showing value.
     *  - becomes showing: something has caused the device to show keyguard which happens due to
     *  user intent to lock the device either through direct action or a timeout
     *  - becomes not showing: keyguard was dismissed and we no longer need to keep the device
     *  unlocked
     *  */
    private void dispatchTrustableDowngrade() {
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == mCurrentUser) {
                info.agent.downgradeToTrustable();
            }
        }
    }

    private List<String> getTrustGrantedMessages(int userId) {
        if (!mStrongAuthTracker.isTrustAllowedForUser(userId)) {
            return new ArrayList<>();
        }

        List<String> trustGrantedMessages = new ArrayList<>();
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId
                    && info.agent.isTrusted()
                    && info.agent.shouldDisplayTrustGrantedMessage()
                    && info.agent.getMessage() != null) {
                trustGrantedMessages.add(info.agent.getMessage().toString());
            }
        }
        return trustGrantedMessages;
    }

    private boolean aggregateIsTrustManaged(int userId) {
        if (!mStrongAuthTracker.isTrustAllowedForUser(userId)) {
            return false;
        }
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                if (info.agent.isManagingTrust()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void dispatchUnlockAttempt(boolean successful, int userId) {
        if (successful) {
            mStrongAuthTracker.allowTrustFromUnlock(userId);
            // Allow the presence of trust on a successful unlock attempt to extend unlock
            updateTrust(userId, 0 /* flags */, true, null);
            mHandler.obtainMessage(MSG_REFRESH_TRUSTABLE_TIMERS_AFTER_AUTH, userId).sendToTarget();
        }

        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                info.agent.onUnlockAttempt(successful);
            }
        }
    }

    private void dispatchUserRequestedUnlock(int userId, boolean dismissKeyguard) {
        if (DEBUG) {
            Slog.d(TAG, "dispatchUserRequestedUnlock(user=" + userId + ", dismissKeyguard="
                    + dismissKeyguard + ")");
        }
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                info.agent.onUserRequestedUnlock(dismissKeyguard);
            }
        }
    }

    private void dispatchUserMayRequestUnlock(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "dispatchUserMayRequestUnlock(user=" + userId + ")");
        }
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                info.agent.onUserMayRequestUnlock();
            }
        }
    }

    private void dispatchUnlockLockout(int timeoutMs, int userId) {
        for (int i = 0; i < mActiveAgents.size(); i++) {
            AgentInfo info = mActiveAgents.valueAt(i);
            if (info.userId == userId) {
                info.agent.onUnlockLockout(timeoutMs);
            }
        }
    }

    // Listeners

    private void addListener(ITrustListener listener) {
        for (int i = 0; i < mTrustListeners.size(); i++) {
            if (mTrustListeners.get(i).asBinder() == listener.asBinder()) {
                return;
            }
        }
        mTrustListeners.add(listener);
        updateTrustAll();
    }

    private void removeListener(ITrustListener listener) {
        for (int i = 0; i < mTrustListeners.size(); i++) {
            if (mTrustListeners.get(i).asBinder() == listener.asBinder()) {
                mTrustListeners.remove(i);
                return;
            }
        }
    }

    private void dispatchOnTrustChanged(boolean enabled, boolean newlyUnlocked, int userId,
            int flags, @NonNull List<String> trustGrantedMessages) {
        if (DEBUG) {
            Log.i(TAG, "onTrustChanged(" + enabled + ", " + newlyUnlocked + ", " + userId + ", 0x"
                    + Integer.toHexString(flags) + ")");
        }
        if (!enabled) flags = 0;
        for (int i = 0; i < mTrustListeners.size(); i++) {
            try {
                mTrustListeners.get(i).onTrustChanged(
                        enabled, newlyUnlocked, userId, flags, trustGrantedMessages);
            } catch (DeadObjectException e) {
                Slog.d(TAG, "Removing dead TrustListener.");
                mTrustListeners.remove(i);
                i--;
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while notifying TrustListener.", e);
            }
        }
    }

    private void dispatchOnEnabledTrustAgentsChanged(int userId) {
        if (DEBUG) {
            Log.i(TAG, "onEnabledTrustAgentsChanged(" + userId + ")");
        }
        for (int i = 0; i < mTrustListeners.size(); i++) {
            try {
                mTrustListeners.get(i).onEnabledTrustAgentsChanged(userId);
            } catch (DeadObjectException e) {
                Slog.d(TAG, "Removing dead TrustListener.");
                mTrustListeners.remove(i);
                i--;
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while notifying TrustListener.", e);
            }
        }
    }

    private void dispatchOnTrustManagedChanged(boolean managed, int userId) {
        if (DEBUG) {
            Log.i(TAG, "onTrustManagedChanged(" + managed + ", " + userId + ")");
        }
        for (int i = 0; i < mTrustListeners.size(); i++) {
            try {
                mTrustListeners.get(i).onTrustManagedChanged(managed, userId);
            } catch (DeadObjectException e) {
                Slog.d(TAG, "Removing dead TrustListener.");
                mTrustListeners.remove(i);
                i--;
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while notifying TrustListener.", e);
            }
        }
    }

    private void dispatchOnTrustError(CharSequence message) {
        if (DEBUG) {
            Log.i(TAG, "onTrustError(" + message + ")");
        }
        for (int i = 0; i < mTrustListeners.size(); i++) {
            try {
                mTrustListeners.get(i).onTrustError(message);
            } catch (DeadObjectException e) {
                Slog.d(TAG, "Removing dead TrustListener.");
                mTrustListeners.remove(i);
                i--;
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while notifying TrustListener.", e);
            }
        }
    }

    private long[] getBiometricSids(int userId) {
        BiometricManager biometricManager = mContext.getSystemService(BiometricManager.class);
        if (biometricManager == null) {
            return null;
        }
        return biometricManager.getAuthenticatorIds(userId);
    }

    // User lifecycle

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        mHandler.obtainMessage(MSG_START_USER, user.getUserIdentifier(), 0, null).sendToTarget();
    }

    @Override
    public void onUserStopped(@NonNull TargetUser user) {
        mHandler.obtainMessage(MSG_CLEANUP_USER, user.getUserIdentifier(), 0, null).sendToTarget();
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        mHandler.obtainMessage(MSG_SWITCH_USER, to.getUserIdentifier(), 0, null).sendToTarget();
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        mHandler.obtainMessage(MSG_UNLOCK_USER, user.getUserIdentifier(), 0, null).sendToTarget();
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        mHandler.obtainMessage(MSG_STOP_USER, user.getUserIdentifier(), 0, null).sendToTarget();
    }

    // Plumbing

    private final IBinder mService = new ITrustManager.Stub() {
        @Override
        public void reportUnlockAttempt(boolean authenticated, int userId) throws RemoteException {
            enforceReportPermission();
            mHandler.obtainMessage(MSG_DISPATCH_UNLOCK_ATTEMPT, authenticated ? 1 : 0, userId)
                    .sendToTarget();
        }

        @Override
        public void reportUserRequestedUnlock(int userId, boolean dismissKeyguard)
                throws RemoteException {
            enforceReportPermission();
            mHandler.obtainMessage(MSG_USER_REQUESTED_UNLOCK, userId, dismissKeyguard ? 1 : 0)
                    .sendToTarget();
        }

        @Override
        public void reportUserMayRequestUnlock(int userId) throws RemoteException {
            enforceReportPermission();
            mHandler.obtainMessage(MSG_USER_MAY_REQUEST_UNLOCK, userId).sendToTarget();
        }

        @Override
        public void reportUnlockLockout(int timeoutMs, int userId) throws RemoteException {
            enforceReportPermission();
            mHandler.obtainMessage(MSG_DISPATCH_UNLOCK_LOCKOUT, timeoutMs, userId)
                    .sendToTarget();
        }

        @Override
        public void reportEnabledTrustAgentsChanged(int userId) throws RemoteException {
            enforceReportPermission();
            mHandler.obtainMessage(MSG_ENABLED_AGENTS_CHANGED, userId, 0).sendToTarget();
        }

        @Override
        public void reportKeyguardShowingChanged() throws RemoteException {
            enforceReportPermission();
            // coalesce refresh messages.
            mHandler.removeMessages(MSG_KEYGUARD_SHOWING_CHANGED);
            mHandler.sendEmptyMessage(MSG_KEYGUARD_SHOWING_CHANGED);

            // Make sure handler processes the message before returning, such that isDeviceLocked
            // after this call will retrieve the correct value.
            mHandler.runWithScissors(() -> {}, 0);
        }

        @Override
        public void registerTrustListener(ITrustListener trustListener) throws RemoteException {
            enforceListenerPermission();
            mHandler.obtainMessage(MSG_REGISTER_LISTENER, trustListener).sendToTarget();
        }

        @Override
        public void unregisterTrustListener(ITrustListener trustListener) throws RemoteException {
            enforceListenerPermission();
            mHandler.obtainMessage(MSG_UNREGISTER_LISTENER, trustListener).sendToTarget();
        }

        /**
         * @param uid: uid of the calling app (obtained via getCallingUid())
         * @param displayId: the id of a Display
         * @return Returns true if both of the following conditions hold -
         * 1) the uid belongs to an app instead of a system core component; and
         * 2) either the uid is running on a virtual device or the displayId
         *    is owned by a virtual device
         */
        private boolean isAppOrDisplayOnAnyVirtualDevice(int uid, int displayId) {
            if (UserHandle.isCore(uid)) {
                return false;
            }

            if (mVirtualDeviceManager == null) {
                mVirtualDeviceManager = LocalServices.getService(
                        VirtualDeviceManagerInternal.class);
                if (mVirtualDeviceManager == null) {
                    // VirtualDeviceManager service may not have been published
                    return false;
                }
            }

            switch (displayId) {
                case Display.INVALID_DISPLAY:
                    // There is no Display object associated with the Context of the calling app.
                    if (mVirtualDeviceManager.isAppRunningOnAnyVirtualDevice(uid)) {
                        return true;
                    }
                    break;
                case Display.DEFAULT_DISPLAY:
                    // The DEFAULT_DISPLAY is by definition not virtual.
                    break;
                default:
                    // Other display IDs can belong to logical displays created for other purposes.
                    if (mVirtualDeviceManager.isDisplayOwnedByAnyVirtualDevice(displayId)) {
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override
        public boolean isDeviceLocked(int userId, int displayId) throws RemoteException {
            int uid = getCallingUid();
            if (isAppOrDisplayOnAnyVirtualDevice(uid, displayId)) {
                // Virtual displays are considered insecure because they may be used for streaming
                // to other devices.
                return false;
            }
            userId = ActivityManager.handleIncomingUser(getCallingPid(), uid, userId,
                    false /* allowAll */, true /* requireFull */, "isDeviceLocked", null);

            final long token = Binder.clearCallingIdentity();
            try {
                if (!mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)) {
                    userId = resolveProfileParent(userId);
                }
                return isDeviceLockedInner(userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isDeviceSecure(int userId, int displayId) throws RemoteException {
            int uid = getCallingUid();
            if (isAppOrDisplayOnAnyVirtualDevice(uid, displayId)) {
                // Virtual displays are considered insecure because they may be used for streaming
                // to other devices.
                return false;
            }

            userId = ActivityManager.handleIncomingUser(getCallingPid(), uid, userId,
                    false /* allowAll */, true /* requireFull */, "isDeviceSecure", null);

            final long token = Binder.clearCallingIdentity();
            try {
                if (!mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)) {
                    userId = resolveProfileParent(userId);
                }
                return mLockPatternUtils.isSecure(userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void enforceReportPermission() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE, "reporting trust events");
        }

        private void enforceListenerPermission() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.TRUST_LISTENER,
                    "register trust listener");
        }

        @Override
        protected void dump(FileDescriptor fd, final PrintWriter fout, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, fout)) return;
            if (isSafeMode()) {
                fout.println("disabled because the system is in safe mode.");
                return;
            }
            if (!mTrustAgentsCanRun) {
                fout.println("disabled because the third-party apps can't run yet.");
                return;
            }
            final List<UserInfo> userInfos = mUserManager.getAliveUsers();
            mHandler.runWithScissors(new Runnable() {
                @Override
                public void run() {
                    fout.println("Trust manager state:");
                    for (UserInfo user : userInfos) {
                        dumpUser(fout, user, user.id == mCurrentUser);
                    }
                }
            }, 1500);
        }

        private void dumpUser(PrintWriter fout, UserInfo user, boolean isCurrent) {
            fout.printf(" User \"%s\" (id=%d, flags=%#x)",
                    user.name, user.id, user.flags);
            if (!user.supportsSwitchToByUser()) {
                fout.println("(managed profile)");
                fout.println("   disabled because switching to this user is not possible.");
                return;
            }
            if (isCurrent) {
                fout.print(" (current)");
            }
            fout.print(": trusted=" + dumpBool(aggregateIsTrusted(user.id)));
            fout.print(", trustManaged=" + dumpBool(aggregateIsTrustManaged(user.id)));
            fout.print(", deviceLocked=" + dumpBool(isDeviceLockedInner(user.id)));
            fout.print(", strongAuthRequired=" + dumpHex(
                    mStrongAuthTracker.getStrongAuthForUser(user.id)));
            fout.println();
            fout.println("   Enabled agents:");
            boolean duplicateSimpleNames = false;
            ArraySet<String> simpleNames = new ArraySet<String>();
            for (AgentInfo info : mActiveAgents) {
                if (info.userId != user.id) { continue; }
                boolean trusted = info.agent.isTrusted();
                fout.print("    "); fout.println(info.component.flattenToShortString());
                fout.print("     bound=" + dumpBool(info.agent.isBound()));
                fout.print(", connected=" + dumpBool(info.agent.isConnected()));
                fout.print(", managingTrust=" + dumpBool(info.agent.isManagingTrust()));
                fout.print(", trusted=" + dumpBool(trusted));
                fout.println();
                if (trusted) {
                    fout.println("      message=\"" + info.agent.getMessage() + "\"");
                }
                if (!info.agent.isConnected()) {
                    String restartTime = TrustArchive.formatDuration(
                            info.agent.getScheduledRestartUptimeMillis()
                                    - SystemClock.uptimeMillis());
                    fout.println("      restartScheduledAt=" + restartTime);
                }
                if (!simpleNames.add(TrustArchive.getSimpleName(info.component))) {
                    duplicateSimpleNames = true;
                }
            }
            fout.println("   Events:");
            mArchive.dump(fout, 50, user.id, "    " /* linePrefix */, duplicateSimpleNames);
            fout.println();
        }

        private String dumpBool(boolean b) {
            return b ? "1" : "0";
        }

        private String dumpHex(int i) {
            return "0x" + Integer.toHexString(i);
        }

        /**
         * Changes the lock status for the given user. This is only applicable to managed profiles,
         * other users should be handled by Keyguard.
         */
        @Override
        public void setDeviceLockedForUser(int userId, boolean locked) {
            enforceReportPermission();
            final long identity = Binder.clearCallingIdentity();
            try {
                if (mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)
                        && mLockPatternUtils.isSecure(userId)) {
                    synchronized (mDeviceLockedForUser) {
                        mDeviceLockedForUser.put(userId, locked);
                    }

                    Authorization.onLockScreenEvent(locked, userId, null,
                            getBiometricSids(userId));

                    if (locked) {
                        try {
                            ActivityManager.getService().notifyLockedProfile(userId);
                        } catch (RemoteException e) {
                        }
                    }
                    final Intent lockIntent = new Intent(Intent.ACTION_DEVICE_LOCKED_CHANGED);
                    lockIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    lockIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                    mContext.sendBroadcastAsUser(lockIntent, UserHandle.SYSTEM,
                            Manifest.permission.TRUST_LISTENER, /* options */ null);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.TRUST_LISTENER)
        @Override
        public boolean isTrustUsuallyManaged(int userId) {
            super.isTrustUsuallyManaged_enforcePermission();
            final long identity = Binder.clearCallingIdentity();
            try {
                return isTrustUsuallyManagedInternal(userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void unlockedByBiometricForUser(int userId, BiometricSourceType biometricSource) {
            enforceReportPermission();
            synchronized(mUsersUnlockedByBiometric) {
                mUsersUnlockedByBiometric.put(userId, true);
            }
            // In non-renewable trust mode we need to refresh trust state here, which will call
            // refreshDeviceLockedForUser()
            int updateTrustOnUnlock = mSettingsObserver.getTrustAgentsNonrenewableTrust() ? 1 : 0;
            mHandler.obtainMessage(MSG_REFRESH_DEVICE_LOCKED_FOR_USER, userId,
                    updateTrustOnUnlock).sendToTarget();
            mHandler.obtainMessage(MSG_REFRESH_TRUSTABLE_TIMERS_AFTER_AUTH, userId).sendToTarget();
        }

        @Override
        public void clearAllBiometricRecognized(
                BiometricSourceType biometricSource, int unlockedUser) {
            enforceReportPermission();
            synchronized(mUsersUnlockedByBiometric) {
                mUsersUnlockedByBiometric.clear();
            }
            Message message = mHandler.obtainMessage(MSG_REFRESH_DEVICE_LOCKED_FOR_USER,
                    UserHandle.USER_ALL, 0 /* arg2 */);
            if (unlockedUser >= 0) {
                Bundle bundle = new Bundle();
                bundle.putInt(REFRESH_DEVICE_LOCKED_EXCEPT_USER, unlockedUser);
                message.setData(bundle);
            }
            message.sendToTarget();
        }
    };

    private boolean isTrustUsuallyManagedInternal(int userId) {
        synchronized (mTrustUsuallyManagedForUser) {
            int i = mTrustUsuallyManagedForUser.indexOfKey(userId);
            if (i >= 0) {
                return mTrustUsuallyManagedForUser.valueAt(i);
            }
        }
        // It's not in memory yet, get the value from persisted storage instead
        boolean persistedValue = mLockPatternUtils.isTrustUsuallyManaged(userId);
        synchronized (mTrustUsuallyManagedForUser) {
            int i = mTrustUsuallyManagedForUser.indexOfKey(userId);
            if (i >= 0) {
                // Someone set the trust usually managed in the mean time. Better use that.
                return mTrustUsuallyManagedForUser.valueAt(i);
            } else {
                // .. otherwise it's safe to cache the fetched value now.
                mTrustUsuallyManagedForUser.put(userId, persistedValue);
                return persistedValue;
            }
        }
    }

    private int resolveProfileParent(int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            UserInfo parent = mUserManager.getProfileParent(userId);
            if (parent != null) {
                return parent.getUserHandle().getIdentifier();
            }
            return userId;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Handler createHandler(Looper looper) {
        return new Handler(looper) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_REGISTER_LISTENER:
                        addListener((ITrustListener) msg.obj);
                        break;
                    case MSG_UNREGISTER_LISTENER:
                        removeListener((ITrustListener) msg.obj);
                        break;
                    case MSG_DISPATCH_UNLOCK_ATTEMPT:
                        dispatchUnlockAttempt(msg.arg1 != 0, msg.arg2);
                        break;
                    case MSG_USER_REQUESTED_UNLOCK:
                        dispatchUserRequestedUnlock(msg.arg1, msg.arg2 != 0);
                        break;
                    case MSG_USER_MAY_REQUEST_UNLOCK:
                        dispatchUserMayRequestUnlock(msg.arg1);
                        break;
                    case MSG_DISPATCH_UNLOCK_LOCKOUT:
                        dispatchUnlockLockout(msg.arg1, msg.arg2);
                        break;
                    case MSG_ENABLED_AGENTS_CHANGED:
                        refreshAgentList(UserHandle.USER_ALL);
                        // This is also called when the security mode of a user changes.
                        refreshDeviceLockedForUser(UserHandle.USER_ALL);
                        dispatchOnEnabledTrustAgentsChanged(msg.arg1);
                        break;
                    case MSG_KEYGUARD_SHOWING_CHANGED:
                        dispatchTrustableDowngrade();
                        refreshDeviceLockedForUser(mCurrentUser);
                        break;
                    case MSG_START_USER:
                    case MSG_CLEANUP_USER:
                    case MSG_UNLOCK_USER:
                        refreshAgentList(msg.arg1);
                        break;
                    case MSG_SWITCH_USER:
                        mCurrentUser = msg.arg1;
                        mSettingsObserver.updateContentObserver();
                        refreshDeviceLockedForUser(UserHandle.USER_ALL);
                        break;
                    case MSG_STOP_USER:
                        setDeviceLockedForUser(msg.arg1, true);
                        break;
                    case MSG_FLUSH_TRUST_USUALLY_MANAGED:
                        SparseBooleanArray usuallyManaged;
                        synchronized (mTrustUsuallyManagedForUser) {
                            usuallyManaged = mTrustUsuallyManagedForUser.clone();
                        }

                        for (int i = 0; i < usuallyManaged.size(); i++) {
                            int userId = usuallyManaged.keyAt(i);
                            boolean value = usuallyManaged.valueAt(i);
                            if (value != mLockPatternUtils.isTrustUsuallyManaged(userId)) {
                                mLockPatternUtils.setTrustUsuallyManaged(value, userId);
                            }
                        }
                        break;
                    case MSG_REFRESH_DEVICE_LOCKED_FOR_USER:
                        if (msg.arg2 == 1) {
                            updateTrust(msg.arg1, 0 /* flags */, true /* isFromUnlock */, null);
                        }
                        final int unlockedUser = msg.getData().getInt(
                                REFRESH_DEVICE_LOCKED_EXCEPT_USER, UserHandle.USER_NULL);
                        refreshDeviceLockedForUser(msg.arg1, unlockedUser);
                        break;
                    case MSG_SCHEDULE_TRUST_TIMEOUT:
                        boolean shouldOverride = msg.arg1 == 1 ? true : false;
                        TimeoutType timeoutType =
                                msg.arg2 == 1 ? TimeoutType.TRUSTABLE : TimeoutType.TRUSTED;
                        handleScheduleTrustTimeout(shouldOverride, timeoutType);
                        break;
                    case MSG_REFRESH_TRUSTABLE_TIMERS_AFTER_AUTH:
                        TrustableTimeoutAlarmListener trustableAlarm =
                                mTrustableTimeoutAlarmListenerForUser.get(msg.arg1);
                        if (trustableAlarm != null && trustableAlarm.isQueued()) {
                            refreshTrustableTimers(msg.arg1);
                        }
                        break;
                }
            }
        };
    }

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onSomePackagesChanged() {
            refreshAgentList(UserHandle.USER_ALL);
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            checkNewAgentsForUser(UserHandle.getUserId(uid));
        }

        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            checkNewAgentsForUser(UserHandle.getUserId(uid));
            // We're interested in all changes, even if just some components get enabled / disabled.
            return true;
        }

        @Override
        public void onPackageDisappeared(String packageName, int reason) {
            removeAgentsOfPackage(packageName);
        }
    };

    private static class SettingsAttrs {
        public ComponentName componentName;
        public boolean canUnlockProfile;

        public SettingsAttrs(
                ComponentName componentName,
                boolean canUnlockProfile) {
            this.componentName = componentName;
            this.canUnlockProfile = canUnlockProfile;
        }
    };

    private class Receiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED.equals(action)) {
                refreshAgentList(getSendingUserId());
                updateDevicePolicyFeatures();
            } else if (Intent.ACTION_USER_ADDED.equals(action) || Intent.ACTION_USER_STARTED.equals(
                    action)) {
                int userId = getUserId(intent);
                if (userId > 0) {
                    maybeEnableFactoryTrustAgents(userId);
                }
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                int userId = getUserId(intent);
                if (userId > 0) {
                    synchronized (mUserIsTrusted) {
                        mUserIsTrusted.delete(userId);
                    }
                    synchronized (mDeviceLockedForUser) {
                        mDeviceLockedForUser.delete(userId);
                    }
                    synchronized (mTrustUsuallyManagedForUser) {
                        mTrustUsuallyManagedForUser.delete(userId);
                    }
                    synchronized (mUsersUnlockedByBiometric) {
                        mUsersUnlockedByBiometric.delete(userId);
                    }
                    refreshAgentList(userId);
                    refreshDeviceLockedForUser(userId);
                }
            }
        }

        private int getUserId(Intent intent) {
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -100);
            if (userId > 0) {
                return userId;
            } else {
                Log.w(TAG, "EXTRA_USER_HANDLE missing or invalid, value=" + userId);
                return -100;
            }
        }

        public void register(Context context) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
            filter.addAction(Intent.ACTION_USER_ADDED);
            filter.addAction(Intent.ACTION_USER_REMOVED);
            filter.addAction(Intent.ACTION_USER_STARTED);
            context.registerReceiverAsUser(this,
                    UserHandle.ALL,
                    filter,
                    null /* permission */,
                    null /* scheduler */);
        }
    }

    private class StrongAuthTracker extends LockPatternUtils.StrongAuthTracker {

        SparseBooleanArray mStartFromSuccessfulUnlock = new SparseBooleanArray();

        StrongAuthTracker(Context context, Looper looper) {
            super(context, looper);
        }

        @Override
        public void onStrongAuthRequiredChanged(int userId) {
            mStartFromSuccessfulUnlock.delete(userId);

            if (DEBUG) {
                Log.i(TAG, "onStrongAuthRequiredChanged(" + userId + ") ->"
                        + " trustAllowed=" + isTrustAllowedForUser(userId)
                        + " agentsCanRun=" + canAgentsRunForUser(userId));
            }

            // Cancel pending alarms if we require some auth anyway.
            if (!isTrustAllowedForUser(userId)) {
                TrustTimeoutAlarmListener alarm = mTrustTimeoutAlarmListenerForUser.get(userId);
                cancelPendingAlarm(alarm);
                alarm = mTrustableTimeoutAlarmListenerForUser.get(userId);
                cancelPendingAlarm(alarm);
                alarm = mIdleTrustableTimeoutAlarmListenerForUser.get(userId);
                cancelPendingAlarm(alarm);
            }

            refreshAgentList(userId);

            // The list of active trust agents may not have changed, if there was a previous call
            // to allowTrustFromUnlock, so we update the trust here too.
            updateTrust(userId, 0 /* flags */);
        }

        private void cancelPendingAlarm(@Nullable TrustTimeoutAlarmListener alarm) {
            if (alarm != null && alarm.isQueued()) {
                alarm.setQueued(false /* isQueued */);
                mAlarmManager.cancel(alarm);
            }
        }

        boolean canAgentsRunForUser(int userId) {
            return mStartFromSuccessfulUnlock.get(userId)
                    || super.isTrustAllowedForUser(userId);
        }

        /**
         * Temporarily suppress strong auth requirements for {@param userId} until strong auth
         * changes again. Must only be called when we know about a successful unlock already
         * before the underlying StrongAuthTracker.
         *
         * Note that this only changes whether trust agents can be started, not the actual trusted
         * value.
         */
        void allowTrustFromUnlock(int userId) {
            if (userId < UserHandle.USER_SYSTEM) {
                throw new IllegalArgumentException("userId must be a valid user: " + userId);
            }
            boolean previous = canAgentsRunForUser(userId);
            mStartFromSuccessfulUnlock.put(userId, true);

            if (DEBUG) {
                Log.i(TAG, "allowTrustFromUnlock(" + userId + ") ->"
                        + " trustAllowed=" + isTrustAllowedForUser(userId)
                        + " agentsCanRun=" + canAgentsRunForUser(userId));
            }

            if (canAgentsRunForUser(userId) != previous) {
                refreshAgentList(userId);
            }
        }
    }

    private abstract class TrustTimeoutAlarmListener implements OnAlarmListener {
        protected final int mUserId;
        protected boolean mIsQueued = false;

        TrustTimeoutAlarmListener(int userId) {
            mUserId = userId;
        }

        @Override
        public void onAlarm() {
            mIsQueued = false;
            handleAlarm();
            // Only fire if trust can unlock.
            if (mStrongAuthTracker.isTrustAllowedForUser(mUserId)) {
                if (DEBUG) Slog.d(TAG, "Revoking all trust because of trust timeout");
                mLockPatternUtils.requireStrongAuth(
                        mStrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED, mUserId);
            }
            maybeLockScreen(mUserId);
        }

        protected abstract void handleAlarm();

        public boolean isQueued() {
            return mIsQueued;
        }

        public void setQueued(boolean isQueued) {
            mIsQueued = isQueued;
        }
    }

    private class TrustedTimeoutAlarmListener extends TrustTimeoutAlarmListener {

        TrustedTimeoutAlarmListener(int userId) {
            super(userId);
        }

        @Override
        public void handleAlarm() {
            TrustableTimeoutAlarmListener otherAlarm;
            boolean otherAlarmPresent;
            if (ENABLE_ACTIVE_UNLOCK_FLAG) {
                otherAlarm = mTrustableTimeoutAlarmListenerForUser.get(mUserId);
                otherAlarmPresent = (otherAlarm != null) && otherAlarm.isQueued();
                if (otherAlarmPresent) {
                    synchronized (mAlarmLock) {
                        disableNonrenewableTrustWhileRenewableTrustIsPresent();
                    }
                    return;
                }
            }
        }

        private void disableNonrenewableTrustWhileRenewableTrustIsPresent() {
            synchronized (mUserTrustState) {
                if (mUserTrustState.get(mUserId) == TrustState.TRUSTED) {
                    // if we're trusted and we have a trustable alarm, we need to
                    // downgrade to trustable
                    mUserTrustState.put(mUserId, TrustState.TRUSTABLE);
                    updateTrust(mUserId, 0 /* flags */);
                }
            }
        }
    }

    private class TrustableTimeoutAlarmListener extends TrustTimeoutAlarmListener {

        TrustableTimeoutAlarmListener(int userId) {
            super(userId);
        }

        @Override
        public void handleAlarm() {
            TrustedTimeoutAlarmListener otherAlarm;
            boolean otherAlarmPresent;
            if (ENABLE_ACTIVE_UNLOCK_FLAG) {
                cancelBothTrustableAlarms(mUserId);
                otherAlarm = mTrustTimeoutAlarmListenerForUser.get(mUserId);
                otherAlarmPresent = (otherAlarm != null) && otherAlarm.isQueued();
                if (otherAlarmPresent) {
                    synchronized (mAlarmLock) {
                        disableRenewableTrustWhileNonrenewableTrustIsPresent();
                    }
                    return;
                }
            }
        }

        private void disableRenewableTrustWhileNonrenewableTrustIsPresent() {
            // if non-renewable trust is running, we need to temporarily prevent
            // renewable trust from being used
            for (AgentInfo agentInfo : mActiveAgents) {
                agentInfo.agent.setUntrustable();
            }
            updateTrust(mUserId, 0 /* flags */);
        }
    }
}
