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
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS;
import static android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS;
import static android.os.UserHandle.USER_NULL;
import static android.provider.Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS;
import static android.provider.Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS;
import static android.os.Flags.allowPrivateProfile;

import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityOptions;
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
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlagsClassic;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ListenerSet;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.Objects;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Handles keeping track of the current user, profiles, and various things related to hiding
 * contents, redacting notifications, and the lockscreen.
 */
@SysUISingleton
public class NotificationLockscreenUserManagerImpl implements
        Dumpable,
        NotificationLockscreenUserManager,
        StateListener {
    private static final String TAG = "LockscreenUserManager";
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final KeyguardStateController mKeyguardStateController;
    private final SecureSettings mSecureSettings;
    private final Object mLock = new Object();

    private static final Uri SHOW_LOCKSCREEN =
            Settings.Secure.getUriFor(LOCK_SCREEN_SHOW_NOTIFICATIONS);
    private static final Uri SHOW_PRIVATE_LOCKSCREEN =
            Settings.Secure.getUriFor(LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);

    private final Lazy<NotificationVisibilityProvider> mVisibilityProviderLazy;
    private final Lazy<CommonNotifCollection> mCommonNotifCollectionLazy;
    private final DevicePolicyManager mDevicePolicyManager;
    private final SparseBooleanArray mLockscreenPublicMode = new SparseBooleanArray();
    private final SparseBooleanArray mUsersWithSeparateWorkChallenge = new SparseBooleanArray();
    private final SparseBooleanArray mUsersAllowingPrivateNotifications = new SparseBooleanArray();
    private final SparseBooleanArray mUsersAllowingNotifications = new SparseBooleanArray();

    // The variables between mUsersDpcAllowingNotifications and
    // mUsersUsersAllowingPrivateNotifications (inclusive) are written on a background thread
    // and read on the main thread. Because the pipeline needs these values, adding locks would
    // introduce too much jank. This means that some pipeline runs could get incorrect values, that
    // would be fixed on the next pipeline run. We think this will be rare since a pipeline run
    // would have to overlap with a DPM sync or a user changing a value in Settings, and we run the
    // pipeline frequently enough that it should be corrected by the next time it matters for the
    // user.
    private final SparseBooleanArray mUsersDpcAllowingNotifications = new SparseBooleanArray();
    private final SparseBooleanArray mUsersUsersAllowingNotifications = new SparseBooleanArray();
    private boolean mKeyguardAllowingNotifications = true;
    private final SparseBooleanArray mUsersDpcAllowingPrivateNotifications
            = new SparseBooleanArray();
    private final SparseBooleanArray mUsersUsersAllowingPrivateNotifications
            = new SparseBooleanArray();

    private final SparseBooleanArray mUsersInLockdownLatestResult = new SparseBooleanArray();
    private final SparseBooleanArray mShouldHideNotifsLatestResult = new SparseBooleanArray();
    private final UserManager mUserManager;
    private final UserTracker mUserTracker;
    private final List<UserChangedListener> mListeners = new ArrayList<>();
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final NotificationClickNotifier mClickNotifier;
    private final Lazy<OverviewProxyService> mOverviewProxyServiceLazy;
    private final FeatureFlagsClassic mFeatureFlags;
    private boolean mShowLockscreenNotifications;
    private LockPatternUtils mLockPatternUtils;
    protected KeyguardManager mKeyguardManager;
    private int mState = StatusBarState.SHADE;
    private final ListenerSet<NotificationStateChangedListener> mNotifStateChangedListeners =
            new ListenerSet<>();
    private final Collection<Uri> mLockScreenUris = new ArrayList<>();


    protected final BroadcastReceiver mAllUsersReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED.equals(action)) {
                if (mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
                    boolean changed = updateDpcSettings(getSendingUserId());
                    if (mCurrentUserId == getSendingUserId()) {
                        changed |= updateLockscreenNotificationSetting();
                    }
                    if (changed) {
                        notifyNotificationStateChanged();
                    }
                } else {
                    if (isCurrentProfile(getSendingUserId())) {
                        mUsersAllowingPrivateNotifications.clear();
                        updateLockscreenNotificationSetting();
                        // TODO(b/231976036): Consolidate pipeline invalidations related to this
                        //  event
                        // notifyNotificationStateChanged();
                    }
                }
            }
        }
    };

    protected final BroadcastReceiver mBaseBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Objects.equals(action, Intent.ACTION_USER_REMOVED)) {
                int removedUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (removedUserId != -1) {
                    for (UserChangedListener listener : mListeners) {
                        listener.onUserRemoved(removedUserId);
                    }
                }
                updateCurrentProfilesCache();
            } else if (Objects.equals(action, Intent.ACTION_USER_ADDED)){
                updateCurrentProfilesCache();
                if (mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_NULL);
                    mBackgroundHandler.post(() -> {
                        initValuesForUser(userId);
                    });
                }
            } else if (profileAvailabilityActions(action)) {
                updateCurrentProfilesCache();
            } else if (Objects.equals(action, Intent.ACTION_USER_UNLOCKED)) {
                // Start the overview connection to the launcher service
                // Connect if user hasn't connected yet
                if (mOverviewProxyServiceLazy.get().getProxy() == null) {
                    mOverviewProxyServiceLazy.get().startConnectionToCurrentUser();
                }
            } else if (Objects.equals(action, NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION)) {
                final IntentSender intentSender = intent.getParcelableExtra(
                        Intent.EXTRA_INTENT);
                final String notificationKey = intent.getStringExtra(Intent.EXTRA_INDEX);
                if (intentSender != null) {
                    try {
                        ActivityOptions options = ActivityOptions.makeBasic();
                        options.setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                        mContext.startIntentSender(intentSender, null, 0, 0, 0,
                                options.toBundle());
                    } catch (IntentSender.SendIntentException e) {
                        /* ignore */
                    }
                }
                if (notificationKey != null) {
                    final NotificationVisibility nv = mVisibilityProviderLazy.get()
                            .obtain(notificationKey, true);
                    mClickNotifier.onNotificationClick(notificationKey, nv);
                }
            }
        }
    };

    protected final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanging(int newUser, @NonNull Context userContext) {
                    mCurrentUserId = newUser;
                    updateCurrentProfilesCache();

                    Log.v(TAG, "userId " + mCurrentUserId + " is in the house");

                    updateLockscreenNotificationSetting();
                    updatePublicMode();
                    mPresenter.onUserSwitched(mCurrentUserId);

                    for (UserChangedListener listener : mListeners) {
                        listener.onUserChanged(mCurrentUserId);
                    }
                }
            };

    protected final Context mContext;
    private final Handler mMainHandler;
    private final Handler mBackgroundHandler;
    private final Executor mBackgroundExecutor;
    /** The current user and its profiles (possibly including a communal profile). */
    protected final SparseArray<UserInfo> mCurrentProfiles = new SparseArray<>();
    protected final SparseArray<UserInfo> mCurrentManagedProfiles = new SparseArray<>();

    protected int mCurrentUserId = 0;
    protected NotificationPresenter mPresenter;
    protected ContentObserver mLockscreenSettingsObserver;
    protected ContentObserver mSettingsObserver;

    @Inject
    public NotificationLockscreenUserManagerImpl(Context context,
            BroadcastDispatcher broadcastDispatcher,
            DevicePolicyManager devicePolicyManager,
            UserManager userManager,
            UserTracker userTracker,
            Lazy<NotificationVisibilityProvider> visibilityProviderLazy,
            Lazy<CommonNotifCollection> commonNotifCollectionLazy,
            NotificationClickNotifier clickNotifier,
            Lazy<OverviewProxyService> overviewProxyServiceLazy,
            KeyguardManager keyguardManager,
            StatusBarStateController statusBarStateController,
            @Main Handler mainHandler,
            @Background Handler backgroundHandler,
            @Background Executor backgroundExecutor,
            DeviceProvisionedController deviceProvisionedController,
            KeyguardStateController keyguardStateController,
            SecureSettings secureSettings,
            DumpManager dumpManager,
            LockPatternUtils lockPatternUtils,
            FeatureFlagsClassic featureFlags) {
        mContext = context;
        mMainHandler = mainHandler;
        mBackgroundHandler = backgroundHandler;
        mBackgroundExecutor = backgroundExecutor;
        mDevicePolicyManager = devicePolicyManager;
        mUserManager = userManager;
        mUserTracker = userTracker;
        mCurrentUserId = mUserTracker.getUserId();
        mVisibilityProviderLazy = visibilityProviderLazy;
        mCommonNotifCollectionLazy = commonNotifCollectionLazy;
        mClickNotifier = clickNotifier;
        mOverviewProxyServiceLazy = overviewProxyServiceLazy;
        statusBarStateController.addCallback(this);
        mLockPatternUtils = lockPatternUtils;
        mKeyguardManager = keyguardManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mDeviceProvisionedController = deviceProvisionedController;
        mSecureSettings = secureSettings;
        mKeyguardStateController = keyguardStateController;
        mFeatureFlags = featureFlags;

        mLockScreenUris.add(SHOW_LOCKSCREEN);
        mLockScreenUris.add(SHOW_PRIVATE_LOCKSCREEN);

        dumpManager.registerDumpable(this);
    }

    public void setUpWithPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;

        mLockscreenSettingsObserver = new ContentObserver(
                mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)
                        ? mBackgroundHandler
                        : mMainHandler) {

            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris, int flags) {
                if (mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
                    @SuppressLint("MissingPermission")
                    List<UserInfo> users = mUserManager.getUsers();
                    for (int i = users.size() - 1; i >= 0; i--) {
                        onChange(selfChange, uris, flags,users.get(i).getUserHandle());
                    }
                } else {
                    // We don't know which user changed LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS or
                    // LOCK_SCREEN_SHOW_NOTIFICATIONS, so we just dump our cache ...
                    mUsersAllowingPrivateNotifications.clear();
                    mUsersAllowingNotifications.clear();
                    // ... and refresh all the notifications
                    updateLockscreenNotificationSetting();
                    notifyNotificationStateChanged();
                }
            }

            // Note: even though this is an override, this method is not called by the OS
            // since we're not in system_server. We are using it internally for cases when
            // we have a single user id available (e.g. from USER_ADDED).
            @Override
            public void onChange(boolean selfChange, Collection<Uri> uris,
                    int flags, UserHandle user) {
                if (mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
                    boolean changed = false;
                    for (Uri uri: uris) {
                        if (SHOW_LOCKSCREEN.equals(uri)) {
                            changed |= updateUserShowSettings(user.getIdentifier());
                        } else if (SHOW_PRIVATE_LOCKSCREEN.equals(uri)) {
                            changed |= updateUserShowPrivateSettings(user.getIdentifier());
                        }
                    }

                    if (mCurrentUserId == user.getIdentifier()) {
                        changed |= updateLockscreenNotificationSetting();
                    }
                    if (changed) {
                        notifyNotificationStateChanged();
                    }
                }
            }
        };

        mSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateLockscreenNotificationSetting();
                if (mDeviceProvisionedController.isDeviceProvisioned()) {
                    // TODO(b/231976036): Consolidate pipeline invalidations related to this event
                    // notifyNotificationStateChanged();
                }
            }
        };

        mContext.getContentResolver().registerContentObserver(
                SHOW_LOCKSCREEN, false,
                mLockscreenSettingsObserver,
                UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                SHOW_PRIVATE_LOCKSCREEN,
                true,
                mLockscreenSettingsObserver,
                UserHandle.USER_ALL);

        if (!mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ZEN_MODE), false,
                    mSettingsObserver);
        }

        mBroadcastDispatcher.registerReceiver(mAllUsersReceiver,
                new IntentFilter(ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
                mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)
                        ? mBackgroundExecutor : null, UserHandle.ALL);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        if (allowPrivateProfile()){
            filter.addAction(Intent.ACTION_PROFILE_AVAILABLE);
            filter.addAction(Intent.ACTION_PROFILE_UNAVAILABLE);
        }
        mBroadcastDispatcher.registerReceiver(mBaseBroadcastReceiver, filter,
                null /* executor */, UserHandle.ALL);

        IntentFilter internalFilter = new IntentFilter();
        internalFilter.addAction(NOTIFICATION_UNLOCKED_BY_WORK_CHALLENGE_ACTION);
        mContext.registerReceiver(mBaseBroadcastReceiver, internalFilter, PERMISSION_SELF, null,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        mUserTracker.addCallback(mUserChangedCallback, new HandlerExecutor(mMainHandler));

        mCurrentUserId = mUserTracker.getUserId(); // in case we reg'd receiver too late
        updateCurrentProfilesCache();

        if (mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
            // Set  up
            mBackgroundHandler.post(() -> {
                @SuppressLint("MissingPermission") List<UserInfo> users = mUserManager.getUsers();
                for (int i = users.size() - 1; i >= 0; i--) {
                    initValuesForUser(users.get(i).id);
                }
            });
        } else {
            mSettingsObserver.onChange(false);  // set up
        }
    }

    private void initValuesForUser(@UserIdInt int userId) {
        mLockscreenSettingsObserver.onChange(
                false, mLockScreenUris, 0, UserHandle.of(userId));
        updateDpcSettings(userId);
    }

    public boolean shouldShowLockscreenNotifications() {
        return mShowLockscreenNotifications;
    }

    public boolean isCurrentProfile(int userId) {
        synchronized (mLock) {
            return userId == UserHandle.USER_ALL || mCurrentProfiles.get(userId) != null;
        }
    }

    private void setShowLockscreenNotifications(boolean show) {
        mShowLockscreenNotifications = show;
    }

    protected boolean updateLockscreenNotificationSetting() {
        boolean show;
        boolean allowedByDpm;

        if (mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
            show = mUsersUsersAllowingNotifications.get(mCurrentUserId)
                    && mKeyguardAllowingNotifications;
            // If DPC never notified us about a user, that means they have no policy for the user,
            // and they allow the behavior
            allowedByDpm = mUsersDpcAllowingNotifications.get(mCurrentUserId, true);
        } else {
            show = mSecureSettings.getIntForUser(
                    LOCK_SCREEN_SHOW_NOTIFICATIONS,
                    1,
                    mCurrentUserId) != 0;
            final int dpmFlags = mDevicePolicyManager.getKeyguardDisabledFeatures(
                    null /* admin */, mCurrentUserId);
            allowedByDpm = (dpmFlags
                    & KEYGUARD_DISABLE_SECURE_NOTIFICATIONS) == 0;
        }

        final boolean oldValue = mShowLockscreenNotifications;
        setShowLockscreenNotifications(show && allowedByDpm);

        return oldValue != mShowLockscreenNotifications;
    }

    @WorkerThread
    protected boolean updateDpcSettings(int userId) {
        boolean originalAllowLockscreen = mUsersDpcAllowingNotifications.get(userId);
        boolean originalAllowPrivate = mUsersDpcAllowingPrivateNotifications.get(userId);
        final int dpmFlags = mDevicePolicyManager.getKeyguardDisabledFeatures(
                null /* admin */, userId);
        final boolean allowedLockscreen = (dpmFlags & KEYGUARD_DISABLE_SECURE_NOTIFICATIONS) == 0;
        final boolean allowedPrivate = (dpmFlags & KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS) == 0;
        mUsersDpcAllowingNotifications.put(userId, allowedLockscreen);
        mUsersDpcAllowingPrivateNotifications.put(userId, allowedPrivate);
        return (originalAllowLockscreen != allowedLockscreen)
                || (originalAllowPrivate != allowedPrivate);
    }

    @WorkerThread
    private boolean updateUserShowSettings(int userId) {
        boolean originalAllowLockscreen = mUsersUsersAllowingNotifications.get(userId);
        boolean newAllowLockscreen = mSecureSettings.getIntForUser(
                LOCK_SCREEN_SHOW_NOTIFICATIONS,
                1,
                userId) != 0;
        mUsersUsersAllowingNotifications.put(userId, newAllowLockscreen);
        boolean keyguardChanged = updateGlobalKeyguardSettings();
        return (newAllowLockscreen != originalAllowLockscreen) || keyguardChanged;
    }

    @WorkerThread
    private boolean updateUserShowPrivateSettings(int userId) {
        boolean originalValue = mUsersUsersAllowingPrivateNotifications.get(userId);
        boolean newValue = mSecureSettings.getIntForUser(
                LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                0,
                userId) != 0;
        mUsersUsersAllowingPrivateNotifications.put(userId, newValue);
        return (newValue != originalValue);
    }

    @WorkerThread
    private boolean updateGlobalKeyguardSettings() {
        final boolean oldValue = mKeyguardAllowingNotifications;
        mKeyguardAllowingNotifications = mKeyguardManager.getPrivateNotificationsAllowed();
        return oldValue != mKeyguardAllowingNotifications;
    }

    /**
     * Has the given user chosen to allow their private (full) notifications to be shown even
     * when the lockscreen is in "public" (secure & locked) mode?
     */
    public boolean userAllowsPrivateNotificationsInPublic(int userHandle) {
        if (mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
            if (userHandle == UserHandle.USER_ALL) {
                userHandle = mCurrentUserId;
            }
            if (mUsersUsersAllowingPrivateNotifications.indexOfKey(userHandle) < 0) {
                // TODO(b/301955929): STOP_SHIP (stop flag flip): remove this read and use a safe
                // default value before moving to 'released'
                Log.wtf(TAG, "Asking for redact notifs setting too early", new Throwable());
                updateUserShowPrivateSettings(userHandle);
            }
            if (mUsersDpcAllowingPrivateNotifications.indexOfKey(userHandle) < 0) {
                // TODO(b/301955929): STOP_SHIP (stop flag flip): remove this read and use a safe
                // default value before moving to 'released'
                Log.wtf(TAG, "Asking for redact notifs dpm override too early", new Throwable());
                updateDpcSettings(userHandle);
            }
            return mUsersUsersAllowingPrivateNotifications.get(userHandle)
                    && mUsersDpcAllowingPrivateNotifications.get(userHandle);
        } else {
            if (userHandle == UserHandle.USER_ALL) {
                return true;
            }

            if (mUsersAllowingPrivateNotifications.indexOfKey(userHandle) < 0) {
                final boolean allowedByUser = 0 != mSecureSettings.getIntForUser(
                        LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, userHandle);
                final boolean allowedByDpm = adminAllowsKeyguardFeature(userHandle,
                        KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);
                final boolean allowed = allowedByUser && allowedByDpm;
                mUsersAllowingPrivateNotifications.append(userHandle, allowed);
                return allowed;
            }

            return mUsersAllowingPrivateNotifications.get(userHandle);
        }
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
    @VisibleForTesting
    void setLockscreenPublicMode(boolean publicMode, int userId) {
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
        return mUsersWithSeparateWorkChallenge.get(userId, false);
    }

    /**
     * Has the given user chosen to allow notifications to be shown even when the lockscreen is in
     * "public" (secure & locked) mode?
     */
    public boolean userAllowsNotificationsInPublic(int userHandle) {
        if (mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
            // Unlike 'show private', settings does not show a copy of this setting for each
            // profile, so it inherits from the parent user.
            if (userHandle == UserHandle.USER_ALL || mCurrentManagedProfiles.contains(userHandle)) {
                userHandle = mCurrentUserId;
            }
            if (mUsersUsersAllowingNotifications.indexOfKey(userHandle) < 0) {
                // TODO(b/301955929): STOP_SHIP (stop flag flip): remove this read and use a safe
                // default value before moving to 'released'
                Log.wtf(TAG, "Asking for show notifs setting too early", new Throwable());
                updateUserShowSettings(userHandle);
            }
            if (mUsersDpcAllowingNotifications.indexOfKey(userHandle) < 0) {
                // TODO(b/301955929): STOP_SHIP (stop flag flip): remove this read and use a safe
                // default value before moving to 'released'
                Log.wtf(TAG, "Asking for show notifs dpm override too early", new Throwable());
                updateDpcSettings(userHandle);
            }
            return mUsersUsersAllowingNotifications.get(userHandle)
                    && mUsersDpcAllowingNotifications.get(userHandle)
                    && mKeyguardAllowingNotifications;
        } else {
            if (isCurrentProfile(userHandle) && userHandle != mCurrentUserId) {
                return true;
            }

            if (mUsersAllowingNotifications.indexOfKey(userHandle) < 0) {
                final boolean allowedByUser = 0 != mSecureSettings.getIntForUser(
                        LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, userHandle);
                final boolean allowedByDpm = adminAllowsKeyguardFeature(userHandle,
                        KEYGUARD_DISABLE_SECURE_NOTIFICATIONS);
                final boolean allowedBySystem = mKeyguardManager.getPrivateNotificationsAllowed();
                final boolean allowed = allowedByUser && allowedByDpm && allowedBySystem;
                mUsersAllowingNotifications.append(userHandle, allowed);
                return allowed;
            }
            return mUsersAllowingNotifications.get(userHandle);
        }
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
        if (mCommonNotifCollectionLazy.get() == null) {
            Log.wtf(TAG, "mEntryManager was null!", new Throwable());
            return true;
        }
        NotificationEntry entry = mCommonNotifCollectionLazy.get().getEntry(key);
        if (mFeatureFlags.isEnabled(Flags.NOTIF_LS_BACKGROUND_THREAD)) {
            return entry != null && entry.getRanking().getChannel() != null
                    && entry.getRanking().getChannel().getLockscreenVisibility()
                    == Notification.VISIBILITY_PRIVATE;
        } else {
            return entry != null
                    && entry.getRanking().getLockscreenVisibilityOverride()
                    == Notification.VISIBILITY_PRIVATE;
        }
    }

    @SuppressLint("MissingPermission")
    private void updateCurrentProfilesCache() {
        synchronized (mLock) {
            mCurrentProfiles.clear();
            mCurrentManagedProfiles.clear();
            if (mUserManager != null) {
                List<UserInfo> profiles = android.multiuser.Flags.supportCommunalProfile()
                        ? mUserManager.getProfilesIncludingCommunal(mCurrentUserId)
                        : mUserManager.getProfiles(mCurrentUserId);
                for (UserInfo user : profiles) {
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
        SparseBooleanArray oldPublicModes = mLockscreenPublicMode.clone();
        SparseBooleanArray oldWorkChallenges = mUsersWithSeparateWorkChallenge.clone();
        mUsersWithSeparateWorkChallenge.clear();
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
            mUsersWithSeparateWorkChallenge.put(userId, needsSeparateChallenge);
        }
        // TODO(b/234738798): Migrate KeyguardNotificationVisibilityProvider to use this listener
        if (!mLockscreenPublicMode.equals(oldPublicModes)
                || !mUsersWithSeparateWorkChallenge.equals(oldWorkChallenges)) {
            notifyNotificationStateChanged();
        }
    }

    @Override
    public void addUserChangedListener(UserChangedListener listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeUserChangedListener(UserChangedListener listener) {
        mListeners.remove(listener);
    }

    @Override
    public void addNotificationStateChangedListener(NotificationStateChangedListener listener) {
        mNotifStateChangedListeners.addIfAbsent(listener);
    }

    @Override
    public void removeNotificationStateChangedListener(NotificationStateChangedListener listener) {
        mNotifStateChangedListeners.remove(listener);
    }

    private void notifyNotificationStateChanged() {
        if (!Looper.getMainLooper().isCurrentThread()) {
            mMainHandler.post(() -> {
                for (NotificationStateChangedListener listener : mNotifStateChangedListeners) {
                    listener.onNotificationStateChanged();
                }
            });
        } else {
            for (NotificationStateChangedListener listener : mNotifStateChangedListeners) {
                listener.onNotificationStateChanged();
            }
        }
    }

    private boolean profileAvailabilityActions(String action){
        return allowPrivateProfile()?
                Objects.equals(action,Intent.ACTION_PROFILE_AVAILABLE)||
                        Objects.equals(action,Intent.ACTION_PROFILE_UNAVAILABLE):
                Objects.equals(action,Intent.ACTION_MANAGED_PROFILE_AVAILABLE)||
                        Objects.equals(action,Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("NotificationLockscreenUserManager state:");
        pw.print("  mCurrentUserId=");
        pw.println(mCurrentUserId);
        pw.print("  mShowLockscreenNotifications=");
        pw.println(mShowLockscreenNotifications);
        pw.print("  mCurrentProfiles=");
        synchronized (mLock) {
            for (int i = mCurrentProfiles.size() - 1; i >= 0; i--) {
                final int userId = mCurrentProfiles.valueAt(i).id;
                pw.print("" + userId + " ");
            }
        }
        pw.println();
        pw.print("  mCurrentManagedProfiles=");
        synchronized (mLock) {
            for (int i = mCurrentManagedProfiles.size() - 1; i >= 0; i--) {
                pw.print("" + mCurrentManagedProfiles.valueAt(i).id + " ");
            }
        }
        pw.println();
        pw.print("  mLockscreenPublicMode=");
        pw.println(mLockscreenPublicMode);
        pw.print("  mUsersWithSeparateWorkChallenge=");
        pw.println(mUsersWithSeparateWorkChallenge);
        pw.print("  mUsersAllowingPrivateNotifications=");
        pw.println(mUsersAllowingPrivateNotifications);
        pw.print("  mUsersAllowingNotifications=");
        pw.println(mUsersAllowingNotifications);
        pw.print("  mUsersInLockdownLatestResult=");
        pw.println(mUsersInLockdownLatestResult);
        pw.print("  mShouldHideNotifsLatestResult=");
        pw.println(mShouldHideNotifsLatestResult);
        pw.print("  mUsersDpcAllowingNotifications=");
        pw.println(mUsersDpcAllowingNotifications);
        pw.print("  mUsersUsersAllowingNotifications=");
        pw.println(mUsersUsersAllowingNotifications);
        pw.print("  mKeyguardAllowingNotifications=");
        pw.println(mKeyguardAllowingNotifications);
        pw.print("  mUsersDpcAllowingPrivateNotifications=");
        pw.println(mUsersDpcAllowingPrivateNotifications);
        pw.print("  mUsersUsersAllowingPrivateNotifications=");
        pw.println(mUsersUsersAllowingPrivateNotifications);
    }
}
