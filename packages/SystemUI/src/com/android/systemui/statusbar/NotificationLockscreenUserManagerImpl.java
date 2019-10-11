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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import static android.app.admin.DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles keeping track of the current user, profiles, and various things related to hiding
 * contents, redacting notifications, and the lockscreen.
 */
public class NotificationLockscreenUserManagerImpl implements
        Dumpable, NotificationLockscreenUserManager, StateListener {
    private static final String TAG = "LockscreenUserManager";
    private static final boolean ENABLE_LOCK_SCREEN_ALLOW_REMOTE_INPUT = false;

    private final DeviceProvisionedController mDeviceProvisionedController =
            Dependency.get(DeviceProvisionedController.class);
    private final KeyguardMonitor mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);

    // Lazy
    private NotificationEntryManager mEntryManager;

    private final DevicePolicyManager mDevicePolicyManager;
    private final SparseBooleanArray mLockscreenPublicMode = new SparseBooleanArray();
    private final SparseBooleanArray mUsersWithSeperateWorkChallenge = new SparseBooleanArray();
    private final SparseBooleanArray mUsersAllowingPrivateNotifications = new SparseBooleanArray();
    private final SparseBooleanArray mUsersAllowingNotifications = new SparseBooleanArray();
    private final UserManager mUserManager;
    private final IStatusBarService mBarService;
    private final List<UserChangedListener> mListeners = new ArrayList<>();

    private boolean mShowLockscreenNotifications;
    private boolean mAllowLockscreenRemoteInput;
    private LockPatternUtils mLockPatternUtils;
    protected KeyguardManager mKeyguardManager;
    private int mState = StatusBarState.SHADE;

    protected final BroadcastReceiver mAllUsersReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED.equals(action) &&
                    isCurrentProfile(getSendingUserId())) {
                mUsersAllowingPrivateNotifications.clear();
                updateLockscreenNotificationSetting();
                getEntryManager().updateNotifications();
            }
        }
    };

    protected final BroadcastReceiver mBaseBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                mCurrentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                updateCurrentProfilesCache();
                Log.v(TAG, "userId " + mCurrentUserId + " is in the house");

                updateLockscreenNotificationSetting();
                updatePublicMode();
                // The filtering needs to happen before the update call below in order to make sure
                // the presenter has the updated notifications from the new user
                getEntryManager().getNotificationData().filterAndSort();
                mPresenter.onUserSwitched(mCurrentUserId);

                for (UserChangedListener listener : mListeners) {
                    listener.onUserChanged(mCurrentUserId);
                }
            } else if (Intent.ACTION_USER_ADDED.equals(action)) {
                updateCurrentProfilesCache();
            } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                // Start the overview connection to the launcher service
                Dependency.get(OverviewProxyService.class).startConnectionToCurrentUser();
            } else if (NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION.equals(action)) {
                final IntentSender intentSender = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                final String notificationKey = intent.getStringExtra(Intent.EXTRA_INDEX);
                if (intentSender != null) {
                    try {
                        mContext.startIntentSender(intentSender, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        /* ignore */
                    }
                }
                if (notificationKey != null) {
                    final int count =
                            getEntryManager().getNotificationData().getActiveNotifications().size();
                    final int rank = getEntryManager().getNotificationData().getRank(notificationKey);
                    NotificationVisibility.NotificationLocation location =
                            NotificationLogger.getNotificationLocation(
                                    getEntryManager().getNotificationData().get(notificationKey));
                    final NotificationVisibility nv = NotificationVisibility.obtain(notificationKey,
                            rank, count, true, location);
                    try {
                        mBarService.onNotificationClick(notificationKey, nv);
                    } catch (RemoteException e) {
                        /* ignore */
                    }
                }
            }
        }
    };

    protected final Context mContext;
    protected final SparseArray<UserInfo> mCurrentProfiles = new SparseArray<>();

    protected int mCurrentUserId = 0;
    protected NotificationPresenter mPresenter;
    protected ContentObserver mLockscreenSettingsObserver;
    protected ContentObserver mSettingsObserver;

    private NotificationEntryManager getEntryManager() {
        if (mEntryManager == null) {
            mEntryManager = Dependency.get(NotificationEntryManager.class);
        }
        return mEntryManager;
    }

    public NotificationLockscreenUserManagerImpl(Context context) {
        mContext = context;
        mDevicePolicyManager = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mCurrentUserId = ActivityManager.getCurrentUser();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        Dependency.get(StatusBarStateController.class).addCallback(this);
        mLockPatternUtils = new LockPatternUtils(context);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
    }

    public void setUpWithPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;

        mLockscreenSettingsObserver = new ContentObserver(Dependency.get(Dependency.MAIN_HANDLER)) {
            @Override
            public void onChange(boolean selfChange) {
                // We don't know which user changed LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS or
                // LOCK_SCREEN_SHOW_NOTIFICATIONS, so we just dump our cache ...
                mUsersAllowingPrivateNotifications.clear();
                mUsersAllowingNotifications.clear();
                // ... and refresh all the notifications
                updateLockscreenNotificationSetting();
                getEntryManager().updateNotifications();
            }
        };

        mSettingsObserver = new ContentObserver(Dependency.get(Dependency.MAIN_HANDLER)) {
            @Override
            public void onChange(boolean selfChange) {
                updateLockscreenNotificationSetting();
                if (mDeviceProvisionedController.isDeviceProvisioned()) {
                    getEntryManager().updateNotifications();
                }
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS), false,
                mLockscreenSettingsObserver,
                UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
                true,
                mLockscreenSettingsObserver,
                UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ZEN_MODE), false,
                mSettingsObserver);

        if (ENABLE_LOCK_SCREEN_ALLOW_REMOTE_INPUT) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_REMOTE_INPUT),
                    false,
                    mSettingsObserver,
                    UserHandle.USER_ALL);
        }

        mContext.registerReceiverAsUser(mAllUsersReceiver, UserHandle.ALL,
                new IntentFilter(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                null, null);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiver(mBaseBroadcastReceiver, filter);

        IntentFilter internalFilter = new IntentFilter();
        internalFilter.addAction(NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION);
        mContext.registerReceiver(mBaseBroadcastReceiver, internalFilter, PERMISSION_SELF, null);

        updateCurrentProfilesCache();

        mSettingsObserver.onChange(false);  // set up
    }

    public boolean shouldShowLockscreenNotifications() {
        return mShowLockscreenNotifications;
    }

    public boolean shouldAllowLockscreenRemoteInput() {
        return mAllowLockscreenRemoteInput;
    }

    public boolean isCurrentProfile(int userId) {
        synchronized (mCurrentProfiles) {
            return userId == UserHandle.USER_ALL || mCurrentProfiles.get(userId) != null;
        }
    }

    /**
     * Returns true if notifications are temporarily disabled for this user for security reasons,
     * regardless of the normal settings for that user.
     */
    private boolean shouldTemporarilyHideNotifications(int userId) {
        if (userId == UserHandle.USER_ALL) {
            userId = mCurrentUserId;
        }
        return KeyguardUpdateMonitor.getInstance(mContext).isUserInLockdown(userId);
    }

    /**
     * Returns true if we're on a secure lockscreen and the user wants to hide notification data.
     * If so, notifications should be hidden.
     */
    public boolean shouldHideNotifications(int userId) {
        return isLockscreenPublicMode(userId) && !userAllowsNotificationsInPublic(userId)
                || (userId != mCurrentUserId && shouldHideNotifications(mCurrentUserId))
                || shouldTemporarilyHideNotifications(userId);
    }

    /**
     * Returns true if we're on a secure lockscreen and the user wants to hide notifications via
     * package-specific override.
     */
    public boolean shouldHideNotifications(String key) {
        if (getEntryManager() == null) {
            Log.wtf(TAG, "mEntryManager was null!", new Throwable());
            return true;
        }
        return isLockscreenPublicMode(mCurrentUserId)
                && getEntryManager().getNotificationData().getVisibilityOverride(key) ==
                        Notification.VISIBILITY_SECRET;
    }

    public boolean shouldShowOnKeyguard(NotificationEntry entry) {
        if (getEntryManager() == null) {
            Log.wtf(TAG, "mEntryManager was null!", new Throwable());
            return false;
        }
        boolean exceedsPriorityThreshold;
        if (NotificationUtils.useNewInterruptionModel(mContext)
                && hideSilentNotificationsOnLockscreen()) {
            exceedsPriorityThreshold = entry.isTopBucket();
        } else {
            exceedsPriorityThreshold =
                    !getEntryManager().getNotificationData().isAmbient(entry.key);
        }
        return mShowLockscreenNotifications && exceedsPriorityThreshold;
    }

    private boolean hideSilentNotificationsOnLockscreen() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS, 1) == 0;
    }

    private void setShowLockscreenNotifications(boolean show) {
        mShowLockscreenNotifications = show;
    }

    private void setLockscreenAllowRemoteInput(boolean allowLockscreenRemoteInput) {
        mAllowLockscreenRemoteInput = allowLockscreenRemoteInput;
    }

    protected void updateLockscreenNotificationSetting() {
        final boolean show = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                1,
                mCurrentUserId) != 0;
        final int dpmFlags = mDevicePolicyManager.getKeyguardDisabledFeatures(
                null /* admin */, mCurrentUserId);
        final boolean allowedByDpm = (dpmFlags
                & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS) == 0;

        setShowLockscreenNotifications(show && allowedByDpm);

        if (ENABLE_LOCK_SCREEN_ALLOW_REMOTE_INPUT) {
            final boolean remoteInput = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.LOCK_SCREEN_ALLOW_REMOTE_INPUT,
                    0,
                    mCurrentUserId) != 0;
            final boolean remoteInputDpm =
                    (dpmFlags & DevicePolicyManager.KEYGUARD_DISABLE_REMOTE_INPUT) == 0;

            setLockscreenAllowRemoteInput(remoteInput && remoteInputDpm);
        } else {
            setLockscreenAllowRemoteInput(false);
        }
    }

    /**
     * Has the given user chosen to allow their private (full) notifications to be shown even
     * when the lockscreen is in "public" (secure & locked) mode?
     */
    public boolean userAllowsPrivateNotificationsInPublic(int userHandle) {
        if (userHandle == UserHandle.USER_ALL) {
            return true;
        }

        if (mUsersAllowingPrivateNotifications.indexOfKey(userHandle) < 0) {
            final boolean allowedByUser = 0 != Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, userHandle);
            final boolean allowedByDpm = adminAllowsKeyguardFeature(userHandle,
                    DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);
            final boolean allowed = allowedByUser && allowedByDpm;
            mUsersAllowingPrivateNotifications.append(userHandle, allowed);
            return allowed;
        }

        return mUsersAllowingPrivateNotifications.get(userHandle);
    }

    private boolean adminAllowsKeyguardFeature(int userHandle, int feature) {
        if (userHandle == UserHandle.USER_ALL) {
            return true;
        }
        final int dpmFlags =
                mDevicePolicyManager.getKeyguardDisabledFeatures(null /* admin */, userHandle);
        return (dpmFlags & feature) == 0;
    }

    /**
     * Save the current "public" (locked and secure) state of the lockscreen.
     */
    public void setLockscreenPublicMode(boolean publicMode, int userId) {
        mLockscreenPublicMode.put(userId, publicMode);
    }

    public boolean isLockscreenPublicMode(int userId) {
        if (userId == UserHandle.USER_ALL) {
            return mLockscreenPublicMode.get(mCurrentUserId, false);
        }
        return mLockscreenPublicMode.get(userId, false);
    }

    @Override
    public boolean needsSeparateWorkChallenge(int userId) {
        return mUsersWithSeperateWorkChallenge.get(userId, false);
    }

    /**
     * Has the given user chosen to allow notifications to be shown even when the lockscreen is in
     * "public" (secure & locked) mode?
     */
    private boolean userAllowsNotificationsInPublic(int userHandle) {
        if (isCurrentProfile(userHandle) && userHandle != mCurrentUserId) {
            return true;
        }

        if (mUsersAllowingNotifications.indexOfKey(userHandle) < 0) {
            final boolean allowedByUser = 0 != Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, userHandle);
            final boolean allowedByDpm = adminAllowsKeyguardFeature(userHandle,
                    DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);
            final boolean allowedBySystem = mKeyguardManager.getPrivateNotificationsAllowed();
            final boolean allowed = allowedByUser && allowedByDpm && allowedBySystem;
            mUsersAllowingNotifications.append(userHandle, allowed);
            return allowed;
        }

        return mUsersAllowingNotifications.get(userHandle);
    }

    /** @return true if the entry needs redaction when on the lockscreen. */
    public boolean needsRedaction(NotificationEntry ent) {
        int userId = ent.notification.getUserId();

        boolean currentUserWantsRedaction = !userAllowsPrivateNotificationsInPublic(mCurrentUserId);
        boolean notiUserWantsRedaction = !userAllowsPrivateNotificationsInPublic(userId);
        boolean redactedLockscreen = currentUserWantsRedaction || notiUserWantsRedaction;

        boolean notificationRequestsRedaction =
                ent.notification.getNotification().visibility == Notification.VISIBILITY_PRIVATE;
        boolean userForcesRedaction = packageHasVisibilityOverride(ent.notification.getKey());

        return userForcesRedaction || notificationRequestsRedaction && redactedLockscreen;
    }

    private boolean packageHasVisibilityOverride(String key) {
        if (getEntryManager() == null) {
            Log.wtf(TAG, "mEntryManager was null!", new Throwable());
            return true;
        }
        return getEntryManager().getNotificationData().getVisibilityOverride(key) ==
                Notification.VISIBILITY_PRIVATE;
    }

    private void updateCurrentProfilesCache() {
        synchronized (mCurrentProfiles) {
            mCurrentProfiles.clear();
            if (mUserManager != null) {
                for (UserInfo user : mUserManager.getProfiles(mCurrentUserId)) {
                    mCurrentProfiles.put(user.id, user);
                }
            }
        }
    }

    public boolean isAnyProfilePublicMode() {
        for (int i = mCurrentProfiles.size() - 1; i >= 0; i--) {
            if (isLockscreenPublicMode(mCurrentProfiles.valueAt(i).id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the current user id. This can change if the user is switched.
     */
    public int getCurrentUserId() {
        return mCurrentUserId;
    }

    public SparseArray<UserInfo> getCurrentProfiles() {
        return mCurrentProfiles;
    }

    @Override
    public void onStateChanged(int newState) {
        mState = newState;
        updatePublicMode();
    }

    public void updatePublicMode() {
        //TODO: I think there may be a race condition where mKeyguardViewManager.isShowing() returns
        // false when it should be true. Therefore, if we are not on the SHADE, don't even bother
        // asking if the keyguard is showing. We still need to check it though because showing the
        // camera on the keyguard has a state of SHADE but the keyguard is still showing.
        final boolean showingKeyguard = mState != StatusBarState.SHADE
              || mKeyguardMonitor.isShowing();
        final boolean devicePublic = showingKeyguard && isSecure(getCurrentUserId());


        // Look for public mode users. Users are considered public in either case of:
        //   - device keyguard is shown in secure mode;
        //   - profile is locked with a work challenge.
        SparseArray<UserInfo> currentProfiles = getCurrentProfiles();
        mUsersWithSeperateWorkChallenge.clear();
        for (int i = currentProfiles.size() - 1; i >= 0; i--) {
            final int userId = currentProfiles.valueAt(i).id;
            boolean isProfilePublic = devicePublic;
            boolean needsSeparateChallenge = mLockPatternUtils.isSeparateProfileChallengeEnabled(
                    userId);
            if (!devicePublic && userId != getCurrentUserId()
                    && needsSeparateChallenge && isSecure(userId)) {
                // Keyguard.isDeviceLocked is updated asynchronously, assume that all profiles
                // with separate challenge are locked when keyguard is visible to avoid race.
                isProfilePublic = showingKeyguard || mKeyguardManager.isDeviceLocked(userId);
            }
            setLockscreenPublicMode(isProfilePublic, userId);
            mUsersWithSeperateWorkChallenge.put(userId, needsSeparateChallenge);
        }
        getEntryManager().updateNotifications();
    }

    @Override
    public void addUserChangedListener(UserChangedListener listener) {
        mListeners.add(listener);
    }

//    public void updatePublicMode() {
//        //TODO: I think there may be a race condition where mKeyguardViewManager.isShowing() returns
//        // false when it should be true. Therefore, if we are not on the SHADE, don't even bother
//        // asking if the keyguard is showing. We still need to check it though because showing the
//        // camera on the keyguard has a state of SHADE but the keyguard is still showing.
//        final boolean showingKeyguard = mState != StatusBarState.SHADE
//              || mKeyguardMonitor.isShowing();
//        final boolean devicePublic = showingKeyguard && isSecure(getCurrentUserId());
//
//
//        // Look for public mode users. Users are considered public in either case of:
//        //   - device keyguard is shown in secure mode;
//        //   - profile is locked with a work challenge.
//        SparseArray<UserInfo> currentProfiles = getCurrentProfiles();
//        for (int i = currentProfiles.size() - 1; i >= 0; i--) {
//            final int userId = currentProfiles.valueAt(i).id;
//            boolean isProfilePublic = devicePublic;
//            if (!devicePublic && userId != getCurrentUserId()) {
//                // We can't rely on KeyguardManager#isDeviceLocked() for unified profile challenge
//                // due to a race condition where this code could be called before
//                // TrustManagerService updates its internal records, resulting in an incorrect
//                // state being cached in mLockscreenPublicMode. (b/35951989)
//                if (mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)
//                        && isSecure(userId)) {
//                    isProfilePublic = mKeyguardManager.isDeviceLocked(userId);
//                }
//            }
//            setLockscreenPublicMode(isProfilePublic, userId);
//        }
//    }

    private boolean isSecure(int userId) {
        return mKeyguardMonitor.isSecure() || mLockPatternUtils.isSecure(userId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NotificationLockscreenUserManager state:");
        pw.print("  mCurrentUserId=");
        pw.println(mCurrentUserId);
        pw.print("  mShowLockscreenNotifications=");
        pw.println(mShowLockscreenNotifications);
        pw.print("  mAllowLockscreenRemoteInput=");
        pw.println(mAllowLockscreenRemoteInput);
        pw.print("  mCurrentProfiles=");
        for (int i = mCurrentProfiles.size() - 1; i >= 0; i--) {
            final int userId = mCurrentProfiles.valueAt(i).id;
            pw.print("" + userId + " ");
        }
        pw.println();
    }
}
