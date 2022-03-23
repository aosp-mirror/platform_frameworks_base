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

import static android.app.Notification.VISIBILITY_SECRET;
import static android.app.admin.DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED;

import static com.android.systemui.DejankUtils.whitelistIpcs;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_MEDIA_CONTROLS;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_SILENT;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Handles keeping track of the current user, profiles, and various things related to hiding
 * contents, redacting notifications, and the lockscreen.
 */
@SysUISingleton
public class NotificationLockscreenUserManagerImpl implements
        Dumpable, NotificationLockscreenUserManager, StateListener {
    private static final String TAG = "LockscreenUserManager";
    private static final boolean ENABLE_LOCK_SCREEN_ALLOW_REMOTE_INPUT = false;

    private final DeviceProvisionedController mDeviceProvisionedController;
    private final KeyguardStateController mKeyguardStateController;
    private final Object mLock = new Object();

    // Lazy
    private NotificationEntryManager mEntryManager;

    private final DevicePolicyManager mDevicePolicyManager;
    private final SparseBooleanArray mLockscreenPublicMode = new SparseBooleanArray();
    private final SparseBooleanArray mUsersWithSeperateWorkChallenge = new SparseBooleanArray();
    private final SparseBooleanArray mUsersAllowingPrivateNotifications = new SparseBooleanArray();
    private final SparseBooleanArray mUsersAllowingNotifications = new SparseBooleanArray();
    private final UserManager mUserManager;
    private final List<UserChangedListener> mListeners = new ArrayList<>();
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final NotificationClickNotifier mClickNotifier;

    private boolean mShowLockscreenNotifications;
    private boolean mAllowLockscreenRemoteInput;
    private LockPatternUtils mLockPatternUtils;
    protected KeyguardManager mKeyguardManager;
    private int mState = StatusBarState.SHADE;
    private List<KeyguardNotificationSuppressor> mKeyguardSuppressors = new ArrayList<>();

    protected final BroadcastReceiver mAllUsersReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED.equals(action) &&
                    isCurrentProfile(getSendingUserId())) {
                mUsersAllowingPrivateNotifications.clear();
                updateLockscreenNotificationSetting();
                getEntryManager().updateNotifications("ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED");
            }
        }
    };

    protected final BroadcastReceiver mBaseBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case Intent.ACTION_USER_SWITCHED:
                    mCurrentUserId = intent.getIntExtra(
                            Intent.EXTRA_USER_HANDLE, UserHandle.USER_ALL);
                    updateCurrentProfilesCache();

                    Log.v(TAG, "userId " + mCurrentUserId + " is in the house");

                    updateLockscreenNotificationSetting();
                    updatePublicMode();
                    // The filtering needs to happen before the update call below in order to
                    // make sure
                    // the presenter has the updated notifications from the new user
                    getEntryManager().reapplyFilterAndSort("user switched");
                    mPresenter.onUserSwitched(mCurrentUserId);

                    for (UserChangedListener listener : mListeners) {
                        listener.onUserChanged(mCurrentUserId);
                    }
                    break;
                case Intent.ACTION_USER_ADDED:
                case Intent.ACTION_MANAGED_PROFILE_AVAILABLE:
                case Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE:
                    updateCurrentProfilesCache();
                    break;
                case Intent.ACTION_USER_UNLOCKED:
                    // Start the overview connection to the launcher service
                    Dependency.get(OverviewProxyService.class).startConnectionToCurrentUser();
                    break;
                case NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION:
                    final IntentSender intentSender = intent.getParcelableExtra(
                            Intent.EXTRA_INTENT);
                    final String notificationKey = intent.getStringExtra(Intent.EXTRA_INDEX);
                    if (intentSender != null) {
                        try {
                            mContext.startIntentSender(intentSender, null, 0, 0, 0);
                        } catch (IntentSender.SendIntentException e) {
                            /* ignore */
                        }
                    }
                    if (notificationKey != null) {
                        NotificationEntry entry =
                                getEntryManager().getActiveNotificationUnfiltered(notificationKey);
                        final int count = getEntryManager().getActiveNotificationsCount();
                        final int rank = entry != null ? entry.getRanking().getRank() : 0;
                        NotificationVisibility.NotificationLocation location =
                                NotificationLogger.getNotificationLocation(entry);
                        final NotificationVisibility nv = NotificationVisibility.obtain(
                                notificationKey,
                                rank, count, true, location);
                        mClickNotifier.onNotificationClick(notificationKey, nv);
                    }
                    break;
            }
        }
    };

    protected final Context mContext;
    private final Handler mMainHandler;
    protected final SparseArray<UserInfo> mCurrentProfiles = new SparseArray<>();
    protected final SparseArray<UserInfo> mCurrentManagedProfiles = new SparseArray<>();

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

    @Inject
    public NotificationLockscreenUserManagerImpl(Context context,
            BroadcastDispatcher broadcastDispatcher,
            DevicePolicyManager devicePolicyManager,
            UserManager userManager,
            NotificationClickNotifier clickNotifier,
            KeyguardManager keyguardManager,
            StatusBarStateController statusBarStateController,
            @Main Handler mainHandler,
            DeviceProvisionedController deviceProvisionedController,
            KeyguardStateController keyguardStateController) {
        mContext = context;
        mMainHandler = mainHandler;
        mDevicePolicyManager = devicePolicyManager;
        mUserManager = userManager;
        mCurrentUserId = ActivityManager.getCurrentUser();
        mClickNotifier = clickNotifier;
        statusBarStateController.addCallback(this);
        mLockPatternUtils = new LockPatternUtils(context);
        mKeyguardManager = keyguardManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mDeviceProvisionedController = deviceProvisionedController;
        mKeyguardStateController = keyguardStateController;
    }

    public void setUpWithPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;

        mLockscreenSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                // We don't know which user changed LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS or
                // LOCK_SCREEN_SHOW_NOTIFICATIONS, so we just dump our cache ...
                mUsersAllowingPrivateNotifications.clear();
                mUsersAllowingNotifications.clear();
                // ... and refresh all the notifications
                updateLockscreenNotificationSetting();
                getEntryManager().updateNotifications("LOCK_SCREEN_SHOW_NOTIFICATIONS,"
                        + " or LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS change");
            }
        };

        mSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateLockscreenNotificationSetting();
                if (mDeviceProvisionedController.isDeviceProvisioned()) {
                    getEntryManager().updateNotifications("LOCK_SCREEN_ALLOW_REMOTE_INPUT"
                            + " or ZEN_MODE change");
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

        mBroadcastDispatcher.registerReceiver(mAllUsersReceiver,
                new IntentFilter(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                null /* handler */, UserHandle.ALL);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        mBroadcastDispatcher.registerReceiver(mBaseBroadcastReceiver, filter,
                null /* executor */, UserHandle.ALL);

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
        synchronized (mLock) {
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
        return Dependency.get(KeyguardUpdateMonitor.class).isUserInLockdown(userId);
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
        NotificationEntry visibleEntry = getEntryManager().getActiveNotificationUnfiltered(key);
        return isLockscreenPublicMode(mCurrentUserId) && visibleEntry != null
                && visibleEntry.getRanking().getLockscreenVisibilityOverride() == VISIBILITY_SECRET;
    }

    public boolean shouldShowOnKeyguard(NotificationEntry entry) {
        if (getEntryManager() == null) {
            Log.wtf(TAG, "mEntryManager was null!", new Throwable());
            return false;
        }
        for (int i = 0; i < mKeyguardSuppressors.size(); i++) {
            if (mKeyguardSuppressors.get(i).shouldSuppressOnKeyguard(entry)) {
                return false;
            }
        }
        boolean exceedsPriorityThreshold;
        if (hideSilentNotificationsOnLockscreen()) {
            exceedsPriorityThreshold =
                    entry.getBucket() == BUCKET_MEDIA_CONTROLS
                            || (entry.getBucket() != BUCKET_SILENT
                            && entry.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT);
        } else {
            exceedsPriorityThreshold = !entry.getRanking().isAmbient();
        }
        return mShowLockscreenNotifications && exceedsPriorityThreshold;
    }

    private boolean hideSilentNotificationsOnLockscreen() {
        return whitelistIpcs(() -> Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS, 1) == 0);
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

    /**
     * If all managed profiles (work profiles) can show private data in public (secure & locked.)
     */
    public boolean allowsManagedPrivateNotificationsInPublic() {
        synchronized (mLock) {
            for (int i = mCurrentManagedProfiles.size() - 1; i >= 0; i--) {
                if (!userAllowsPrivateNotificationsInPublic(
                        mCurrentManagedProfiles.valueAt(i).id)) {
                    return false;
                }
            }
        }
        return true;
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
    public boolean userAllowsNotificationsInPublic(int userHandle) {
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
        int userId = ent.getSbn().getUserId();

        boolean isCurrentUserRedactingNotifs =
                !userAllowsPrivateNotificationsInPublic(mCurrentUserId);
        boolean isNotifForManagedProfile = mCurrentManagedProfiles.contains(userId);
        boolean isNotifUserRedacted = !userAllowsPrivateNotificationsInPublic(userId);

        // redact notifications if the current user is redacting notifications; however if the
        // notification is associated with a managed profile, we rely on the managed profile
        // setting to determine whether to redact it
        boolean isNotifRedacted = (!isNotifForManagedProfile && isCurrentUserRedactingNotifs)
                || isNotifUserRedacted;

        boolean notificationRequestsRedaction =
                ent.getSbn().getNotification().visibility == Notification.VISIBILITY_PRIVATE;
        boolean userForcesRedaction = packageHasVisibilityOverride(ent.getSbn().getKey());

        return userForcesRedaction || notificationRequestsRedaction && isNotifRedacted;
    }

    private boolean packageHasVisibilityOverride(String key) {
        if (getEntryManager() == null) {
            Log.wtf(TAG, "mEntryManager was null!", new Throwable());
            return true;
        }
        NotificationEntry entry = getEntryManager().getActiveNotificationUnfiltered(key);
        return entry != null
                && entry.getRanking().getLockscreenVisibilityOverride() 
                == Notification.VISIBILITY_PRIVATE;
    }

    private void updateCurrentProfilesCache() {
        synchronized (mLock) {
            mCurrentProfiles.clear();
            mCurrentManagedProfiles.clear();
            if (mUserManager != null) {
                for (UserInfo user : mUserManager.getProfiles(mCurrentUserId)) {
                    mCurrentProfiles.put(user.id, user);
                    if (UserManager.USER_TYPE_PROFILE_MANAGED.equals(user.userType)) {
                        mCurrentManagedProfiles.put(user.id, user);
                    }
                }
            }
        }
        mMainHandler.post(() -> {
            for (UserChangedListener listener : mListeners) {
                listener.onCurrentProfilesChanged(mCurrentProfiles);
            }
        });
    }

    /**
     * If any of the profiles are in public mode.
     */
    public boolean isAnyProfilePublicMode() {
        synchronized (mLock) {
            for (int i = mCurrentProfiles.size() - 1; i >= 0; i--) {
                if (isLockscreenPublicMode(mCurrentProfiles.valueAt(i).id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * If any managed/work profiles are in public mode.
     */
    public boolean isAnyManagedProfilePublicMode() {
        synchronized (mLock) {
            for (int i = mCurrentManagedProfiles.size() - 1; i >= 0; i--) {
                if (isLockscreenPublicMode(mCurrentManagedProfiles.valueAt(i).id)) {
                    return true;
                }
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
                || mKeyguardStateController.isShowing();
        final boolean devicePublic = showingKeyguard && mKeyguardStateController.isMethodSecure();


        // Look for public mode users. Users are considered public in either case of:
        //   - device keyguard is shown in secure mode;
        //   - profile is locked with a work challenge.
        SparseArray<UserInfo> currentProfiles = getCurrentProfiles();
        mUsersWithSeperateWorkChallenge.clear();
        for (int i = currentProfiles.size() - 1; i >= 0; i--) {
            final int userId = currentProfiles.valueAt(i).id;
            boolean isProfilePublic = devicePublic;
            // TODO(b/140058091)
            boolean needsSeparateChallenge = whitelistIpcs(() ->
                    mLockPatternUtils.isSeparateProfileChallengeEnabled(userId));
            if (!devicePublic && userId != getCurrentUserId()
                    && needsSeparateChallenge && mLockPatternUtils.isSecure(userId)) {
                // Keyguard.isDeviceLocked is updated asynchronously, assume that all profiles
                // with separate challenge are locked when keyguard is visible to avoid race.
                isProfilePublic = showingKeyguard || mKeyguardManager.isDeviceLocked(userId);
            }
            setLockscreenPublicMode(isProfilePublic, userId);
            mUsersWithSeperateWorkChallenge.put(userId, needsSeparateChallenge);
        }
        getEntryManager().updateNotifications("NotificationLockscreenUserManager.updatePublicMode");
    }

    @Override
    public void addUserChangedListener(UserChangedListener listener) {
        mListeners.add(listener);
    }

    @Override
    public void addKeyguardNotificationSuppressor(KeyguardNotificationSuppressor suppressor) {
        mKeyguardSuppressors.add(suppressor);
    }

    @Override
    public void removeUserChangedListener(UserChangedListener listener) {
        mListeners.remove(listener);
    }

//    public void updatePublicMode() {
//        //TODO: I think there may be a race condition where mKeyguardViewManager.isShowing() returns
//        // false when it should be true. Therefore, if we are not on the SHADE, don't even bother
//        // asking if the keyguard is showing. We still need to check it though because showing the
//        // camera on the keyguard has a state of SHADE but the keyguard is still showing.
//        final boolean showingKeyguard = mState != StatusBarState.SHADE
//              || mKeyguardStateController.isShowing();
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
        synchronized (mLock) {
            for (int i = mCurrentProfiles.size() - 1; i >= 0; i--) {
                final int userId = mCurrentProfiles.valueAt(i).id;
                pw.print("" + userId + " ");
            }
        }
        pw.print("  mCurrentManagedProfiles=");
        synchronized (mLock) {
            for (int i = mCurrentManagedProfiles.size() - 1; i >= 0; i--) {
                pw.print("" + mCurrentManagedProfiles.valueAt(i).id + " ");
            }
        }
        pw.println();
    }
}
