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
package com.android.systemui.statusbar.policy;

import static android.os.UserManager.SWITCHABILITY_STATUS_OK;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyCallback;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.WindowManagerGlobal;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.collection.SimpleArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.LatencyTracker;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.settingslib.users.UserCreatingDialog;
import com.android.systemui.GuestResetOrExitSessionReceiver;
import com.android.systemui.GuestResumeSessionReceiver;
import com.android.systemui.R;
import com.android.systemui.SystemUISecondaryUserService;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.LongRunning;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.QSUserSwitcherEvent;
import com.android.systemui.qs.user.UserSwitchDialogController.DialogShower;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.user.CreateUserActivity;
import com.android.systemui.user.data.source.UserRecord;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.inject.Inject;

import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 * Old implementation. Keeps a list of all users on the device for user switching.
 *
 * @deprecated This is the old implementation. Please depend on {@link UserSwitcherController}
 * instead.
 */
@Deprecated
@SysUISingleton
public class UserSwitcherControllerOldImpl implements UserSwitcherController {

    private static final String TAG = "UserSwitcherController";
    private static final boolean DEBUG = false;
    private static final String SIMPLE_USER_SWITCHER_GLOBAL_SETTING =
            "lockscreenSimpleUserSwitcher";
    private static final int PAUSE_REFRESH_USERS_TIMEOUT_MS = 3000;

    private static final String PERMISSION_SELF = "com.android.systemui.permission.SELF";
    private static final long MULTI_USER_JOURNEY_TIMEOUT = 20000L;

    private static final String INTERACTION_JANK_ADD_NEW_USER_TAG = "add_new_user";
    private static final String INTERACTION_JANK_EXIT_GUEST_MODE_TAG = "exit_guest_mode";

    protected final Context mContext;
    protected final UserTracker mUserTracker;
    protected final UserManager mUserManager;
    private final ContentObserver mSettingsObserver;
    private final ArrayList<WeakReference<BaseUserSwitcherAdapter>> mAdapters = new ArrayList<>();
    @VisibleForTesting
    final GuestResumeSessionReceiver mGuestResumeSessionReceiver;
    @VisibleForTesting
    final GuestResetOrExitSessionReceiver mGuestResetOrExitSessionReceiver;
    private final KeyguardStateController mKeyguardStateController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final DevicePolicyManager mDevicePolicyManager;
    protected final Handler mHandler;
    private final ActivityStarter mActivityStarter;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final BroadcastSender mBroadcastSender;
    private final TelephonyListenerManager mTelephonyListenerManager;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private final LatencyTracker mLatencyTracker;
    private final DialogLaunchAnimator mDialogLaunchAnimator;
    private final SimpleArrayMap<UserRecord, EnforcedAdmin> mEnforcedAdminByUserRecord =
            new SimpleArrayMap<>();
    private final ArraySet<UserRecord> mDisabledByAdmin = new ArraySet<>();

    private ArrayList<UserRecord> mUsers = new ArrayList<>();
    @VisibleForTesting
    AlertDialog mExitGuestDialog;
    @VisibleForTesting
    Dialog mAddUserDialog;
    private int mLastNonGuestUser = UserHandle.USER_SYSTEM;
    private boolean mSimpleUserSwitcher;
    // When false, there won't be any visual affordance to add a new user from the keyguard even if
    // the user is unlocked
    private final MutableStateFlow<Boolean> mAddUsersFromLockScreen =
            StateFlowKt.MutableStateFlow(false);
    private boolean mUserSwitcherEnabled;
    @VisibleForTesting
    boolean mPauseRefreshUsers;
    private int mSecondaryUser = UserHandle.USER_NULL;
    private Intent mSecondaryUserServiceIntent;
    private SparseBooleanArray mForcePictureLoadForUserId = new SparseBooleanArray(2);
    private final UiEventLogger mUiEventLogger;
    private final IActivityManager mActivityManager;
    private final Executor mBgExecutor;
    private final Executor mUiExecutor;
    private final Executor mLongRunningExecutor;
    private final boolean mGuestUserAutoCreated;
    private final AtomicBoolean mGuestIsResetting;
    private final AtomicBoolean mGuestCreationScheduled;
    private FalsingManager mFalsingManager;
    @Nullable
    private View mView;
    private String mCreateSupervisedUserPackage;
    private GlobalSettings mGlobalSettings;
    private List<UserSwitchCallback> mUserSwitchCallbacks =
            Collections.synchronizedList(new ArrayList<>());

    @Inject
    public UserSwitcherControllerOldImpl(
            Context context,
            IActivityManager activityManager,
            UserManager userManager,
            UserTracker userTracker,
            KeyguardStateController keyguardStateController,
            DeviceProvisionedController deviceProvisionedController,
            DevicePolicyManager devicePolicyManager,
            @Main Handler handler,
            ActivityStarter activityStarter,
            BroadcastDispatcher broadcastDispatcher,
            BroadcastSender broadcastSender,
            UiEventLogger uiEventLogger,
            FalsingManager falsingManager,
            TelephonyListenerManager telephonyListenerManager,
            SecureSettings secureSettings,
            GlobalSettings globalSettings,
            @Background Executor bgExecutor,
            @LongRunning Executor longRunningExecutor,
            @Main Executor uiExecutor,
            InteractionJankMonitor interactionJankMonitor,
            LatencyTracker latencyTracker,
            DumpManager dumpManager,
            DialogLaunchAnimator dialogLaunchAnimator,
            GuestResumeSessionReceiver guestResumeSessionReceiver,
            GuestResetOrExitSessionReceiver guestResetOrExitSessionReceiver) {
        mContext = context;
        mActivityManager = activityManager;
        mUserTracker = userTracker;
        mBroadcastDispatcher = broadcastDispatcher;
        mBroadcastSender = broadcastSender;
        mTelephonyListenerManager = telephonyListenerManager;
        mUiEventLogger = uiEventLogger;
        mFalsingManager = falsingManager;
        mInteractionJankMonitor = interactionJankMonitor;
        mLatencyTracker = latencyTracker;
        mGlobalSettings = globalSettings;
        mGuestResumeSessionReceiver = guestResumeSessionReceiver;
        mGuestResetOrExitSessionReceiver = guestResetOrExitSessionReceiver;
        mBgExecutor = bgExecutor;
        mLongRunningExecutor = longRunningExecutor;
        mUiExecutor = uiExecutor;
        mGuestResumeSessionReceiver.register();
        mGuestResetOrExitSessionReceiver.register();
        mGuestUserAutoCreated = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_guestUserAutoCreated);
        mGuestIsResetting = new AtomicBoolean();
        mGuestCreationScheduled = new AtomicBoolean();
        mKeyguardStateController = keyguardStateController;
        mDeviceProvisionedController = deviceProvisionedController;
        mDevicePolicyManager = devicePolicyManager;
        mHandler = handler;
        mActivityStarter = activityStarter;
        mUserManager = userManager;
        mDialogLaunchAnimator = dialogLaunchAnimator;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        mBroadcastDispatcher.registerReceiver(
                mReceiver, filter, null /* executor */,
                UserHandle.SYSTEM, Context.RECEIVER_EXPORTED, null /* permission */);

        mSimpleUserSwitcher = shouldUseSimpleUserSwitcher();

        mSecondaryUserServiceIntent = new Intent(context, SystemUISecondaryUserService.class);

        filter = new IntentFilter();
        mContext.registerReceiverAsUser(mReceiver, UserHandle.SYSTEM, filter,
                PERMISSION_SELF, null /* scheduler */,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mSimpleUserSwitcher = shouldUseSimpleUserSwitcher();
                mAddUsersFromLockScreen.setValue(
                        mGlobalSettings.getIntForUser(
                                Settings.Global.ADD_USERS_WHEN_LOCKED,
                                0,
                                UserHandle.USER_SYSTEM) != 0);
                mUserSwitcherEnabled = mGlobalSettings.getIntForUser(
                        Settings.Global.USER_SWITCHER_ENABLED, 0, UserHandle.USER_SYSTEM) != 0;
                refreshUsers(UserHandle.USER_NULL);
            };
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SIMPLE_USER_SWITCHER_GLOBAL_SETTING), true,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.USER_SWITCHER_ENABLED), true,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADD_USERS_WHEN_LOCKED), true,
                mSettingsObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                        Settings.Global.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED),
                true, mSettingsObserver);
        // Fetch initial values.
        mSettingsObserver.onChange(false);

        keyguardStateController.addCallback(mCallback);
        listenForCallState();

        mCreateSupervisedUserPackage = mContext.getString(
                com.android.internal.R.string.config_supervisedUserCreationPackage);

        dumpManager.registerDumpable(getClass().getSimpleName(), this);

        refreshUsers(UserHandle.USER_NULL);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void refreshUsers(int forcePictureLoadForId) {
        if (DEBUG) Log.d(TAG, "refreshUsers(forcePictureLoadForId=" + forcePictureLoadForId + ")");
        if (forcePictureLoadForId != UserHandle.USER_NULL) {
            mForcePictureLoadForUserId.put(forcePictureLoadForId, true);
        }

        if (mPauseRefreshUsers) {
            return;
        }

        boolean forceAllUsers = mForcePictureLoadForUserId.get(UserHandle.USER_ALL);
        SparseArray<Bitmap> bitmaps = new SparseArray<>(mUsers.size());
        final int userCount = mUsers.size();
        for (int i = 0; i < userCount; i++) {
            UserRecord r = mUsers.get(i);
            if (r == null || r.picture == null || r.info == null || forceAllUsers
                    || mForcePictureLoadForUserId.get(r.info.id)) {
                continue;
            }
            bitmaps.put(r.info.id, r.picture);
        }
        mForcePictureLoadForUserId.clear();

        mBgExecutor.execute(() ->  {
            List<UserInfo> infos = mUserManager.getAliveUsers();
            if (infos == null) {
                return;
            }
            ArrayList<UserRecord> records = new ArrayList<>(infos.size());
            int currentId = mUserTracker.getUserId();
            // Check user switchability of the foreground user since SystemUI is running in
            // User 0
            boolean canSwitchUsers = mUserManager.getUserSwitchability(
                    UserHandle.of(mUserTracker.getUserId())) == SWITCHABILITY_STATUS_OK;
            UserRecord guestRecord = null;

            for (UserInfo info : infos) {
                boolean isCurrent = currentId == info.id;
                boolean switchToEnabled = canSwitchUsers || isCurrent;
                if (!mUserSwitcherEnabled && !info.isPrimary()) {
                    continue;
                }

                if (info.isEnabled()) {
                    if (info.isGuest()) {
                        // Tapping guest icon triggers remove and a user switch therefore
                        // the icon shouldn't be enabled even if the user is current
                        guestRecord = new UserRecord(info, null /* picture */,
                                true /* isGuest */, isCurrent, false /* isAddUser */,
                                false /* isRestricted */, canSwitchUsers,
                                false /* isAddSupervisedUser */);
                    } else if (info.supportsSwitchToByUser()) {
                        Bitmap picture = bitmaps.get(info.id);
                        if (picture == null) {
                            picture = mUserManager.getUserIcon(info.id);

                            if (picture != null) {
                                int avatarSize = mContext.getResources()
                                        .getDimensionPixelSize(R.dimen.max_avatar_size);
                                picture = Bitmap.createScaledBitmap(
                                        picture, avatarSize, avatarSize, true);
                            }
                        }
                        records.add(new UserRecord(info, picture, false /* isGuest */,
                                isCurrent, false /* isAddUser */, false /* isRestricted */,
                                switchToEnabled, false /* isAddSupervisedUser */));
                    }
                }
            }

            if (guestRecord == null) {
                if (mGuestUserAutoCreated) {
                    // If mGuestIsResetting=true, the switch should be disabled since
                    // we will just use it as an indicator for "Resetting guest...".
                    // Otherwise, default to canSwitchUsers.
                    boolean isSwitchToGuestEnabled = !mGuestIsResetting.get() && canSwitchUsers;
                    guestRecord = new UserRecord(null /* info */, null /* picture */,
                            true /* isGuest */, false /* isCurrent */,
                            false /* isAddUser */, false /* isRestricted */,
                            isSwitchToGuestEnabled, false /* isAddSupervisedUser */);
                    checkIfAddUserDisallowedByAdminOnly(guestRecord);
                    records.add(guestRecord);
                } else if (canCreateGuest(guestRecord != null)) {
                    guestRecord = new UserRecord(null /* info */, null /* picture */,
                            true /* isGuest */, false /* isCurrent */,
                            false /* isAddUser */, createIsRestricted(), canSwitchUsers,
                            false /* isAddSupervisedUser */);
                    checkIfAddUserDisallowedByAdminOnly(guestRecord);
                    records.add(guestRecord);
                }
            } else {
                records.add(guestRecord);
            }

            if (canCreateUser()) {
                UserRecord addUserRecord = new UserRecord(null /* info */, null /* picture */,
                        false /* isGuest */, false /* isCurrent */, true /* isAddUser */,
                        createIsRestricted(), canSwitchUsers,
                        false /* isAddSupervisedUser */);
                checkIfAddUserDisallowedByAdminOnly(addUserRecord);
                records.add(addUserRecord);
            }

            if (canCreateSupervisedUser()) {
                UserRecord addUserRecord = new UserRecord(null /* info */, null /* picture */,
                        false /* isGuest */, false /* isCurrent */, false /* isAddUser */,
                        createIsRestricted(), canSwitchUsers, true /* isAddSupervisedUser */);
                checkIfAddUserDisallowedByAdminOnly(addUserRecord);
                records.add(addUserRecord);
            }

            mUiExecutor.execute(() -> {
                if (records != null) {
                    mUsers = records;
                    notifyAdapters();
                }
            });
        });
    }

    private boolean systemCanCreateUsers() {
        return !mUserManager.hasBaseUserRestriction(
                UserManager.DISALLOW_ADD_USER, UserHandle.SYSTEM);
    }

    private boolean currentUserCanCreateUsers() {
        UserInfo currentUser = mUserTracker.getUserInfo();
        return currentUser != null
                && (currentUser.isAdmin() || mUserTracker.getUserId() == UserHandle.USER_SYSTEM)
                && systemCanCreateUsers();
    }

    private boolean anyoneCanCreateUsers() {
        return systemCanCreateUsers() && mAddUsersFromLockScreen.getValue();
    }

    @VisibleForTesting
    boolean canCreateGuest(boolean hasExistingGuest) {
        return mUserSwitcherEnabled
                && (currentUserCanCreateUsers() || anyoneCanCreateUsers())
                && !hasExistingGuest;
    }

    @VisibleForTesting
    boolean canCreateUser() {
        return mUserSwitcherEnabled
                && (currentUserCanCreateUsers() || anyoneCanCreateUsers())
                && mUserManager.canAddMoreUsers(UserManager.USER_TYPE_FULL_SECONDARY);
    }

    private boolean createIsRestricted() {
        return !mAddUsersFromLockScreen.getValue();
    }

    @VisibleForTesting
    boolean canCreateSupervisedUser() {
        return !TextUtils.isEmpty(mCreateSupervisedUserPackage) && canCreateUser();
    }

    private void pauseRefreshUsers() {
        if (!mPauseRefreshUsers) {
            mHandler.postDelayed(mUnpauseRefreshUsers, PAUSE_REFRESH_USERS_TIMEOUT_MS);
            mPauseRefreshUsers = true;
        }
    }

    private void notifyAdapters() {
        for (int i = mAdapters.size() - 1; i >= 0; i--) {
            BaseUserSwitcherAdapter adapter = mAdapters.get(i).get();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            } else {
                mAdapters.remove(i);
            }
        }
    }

    @Override
    public boolean isSimpleUserSwitcher() {
        return mSimpleUserSwitcher;
    }

    /**
     * Returns whether the current user is a system user.
     */
    @VisibleForTesting
    boolean isSystemUser() {
        return mUserTracker.getUserId() == UserHandle.USER_SYSTEM;
    }

    @Override
    public @Nullable UserRecord getCurrentUserRecord() {
        for (int i = 0; i < mUsers.size(); ++i) {
            UserRecord userRecord = mUsers.get(i);
            if (userRecord.isCurrent) {
                return userRecord;
            }
        }
        return null;
    }

    @Override
    public void onUserSelected(int userId, @Nullable DialogShower dialogShower) {
        UserRecord userRecord = mUsers.stream()
                .filter(x -> x.resolveId() == userId)
                .findFirst()
                .orElse(null);
        if (userRecord == null) {
            return;
        }

        onUserListItemClicked(userRecord, dialogShower);
    }

    @Override
    public Flow<Boolean> isAddUsersFromLockScreenEnabled() {
        return mAddUsersFromLockScreen;
    }

    @Override
    public boolean isGuestUserAutoCreated() {
        return mGuestUserAutoCreated;
    }

    @Override
    public boolean isGuestUserResetting() {
        return mGuestIsResetting.get();
    }

    @Override
    public void onUserListItemClicked(UserRecord record, DialogShower dialogShower) {
        if (record.isGuest && record.info == null) {
            createAndSwitchToGuestUser(dialogShower);
        } else if (record.isAddUser) {
            showAddUserDialog(dialogShower);
        } else if (record.isAddSupervisedUser) {
            startSupervisedUserActivity();
        } else {
            onUserListItemClicked(record.info.id, record, dialogShower);
        }
    }

    private void onUserListItemClicked(int id, UserRecord record, DialogShower dialogShower) {
        int currUserId = mUserTracker.getUserId();
        // If switching from guest and guest is ephemeral, then follow the flow
        // of showExitGuestDialog to remove current guest,
        // and switch to selected user
        UserInfo currUserInfo = mUserTracker.getUserInfo();
        if (currUserId == id) {
            if (record.isGuest) {
                showExitGuestDialog(id, currUserInfo.isEphemeral(), dialogShower);
            }
            return;
        }

        if (currUserInfo != null && currUserInfo.isGuest()) {
            showExitGuestDialog(currUserId, currUserInfo.isEphemeral(),
                    record.resolveId(), dialogShower);
            return;
        }

        if (dialogShower != null) {
            // If we haven't morphed into another dialog, it means we have just switched users.
            // Then, dismiss the dialog.
            dialogShower.dismiss();
        }
        switchToUserId(id);
    }

    private void switchToUserId(int id) {
        try {
            if (mView != null) {
                mInteractionJankMonitor.begin(InteractionJankMonitor.Configuration.Builder
                        .withView(InteractionJankMonitor.CUJ_USER_SWITCH, mView)
                        .setTimeout(MULTI_USER_JOURNEY_TIMEOUT));
            }
            mLatencyTracker.onActionStart(LatencyTracker.ACTION_USER_SWITCH);
            pauseRefreshUsers();
            mActivityManager.switchUser(id);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
    }

    private void showExitGuestDialog(int id, boolean isGuestEphemeral, DialogShower dialogShower) {
        int newId = UserHandle.USER_SYSTEM;
        if (mLastNonGuestUser != UserHandle.USER_SYSTEM) {
            UserInfo info = mUserManager.getUserInfo(mLastNonGuestUser);
            if (info != null && info.isEnabled() && info.supportsSwitchToByUser()) {
                newId = info.id;
            }
        }
        showExitGuestDialog(id, isGuestEphemeral, newId, dialogShower);
    }

    private void showExitGuestDialog(int id, boolean isGuestEphemeral,
                        int targetId, DialogShower dialogShower) {
        if (mExitGuestDialog != null && mExitGuestDialog.isShowing()) {
            mExitGuestDialog.cancel();
        }
        mExitGuestDialog = new ExitGuestDialog(mContext, id, isGuestEphemeral, targetId);
        if (dialogShower != null) {
            dialogShower.showDialog(mExitGuestDialog, new DialogCuj(
                    InteractionJankMonitor.CUJ_USER_DIALOG_OPEN,
                    INTERACTION_JANK_EXIT_GUEST_MODE_TAG));
        } else {
            mExitGuestDialog.show();
        }
    }

    @Override
    public void createAndSwitchToGuestUser(@Nullable DialogShower dialogShower) {
        createGuestAsync(guestId -> {
            // guestId may be USER_NULL if we haven't reloaded the user list yet.
            if (guestId != UserHandle.USER_NULL) {
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_ADD);
                onUserListItemClicked(guestId, UserRecord.createForGuest(), dialogShower);
            }
        });
    }

    @Override
    public void showAddUserDialog(@Nullable DialogShower dialogShower) {
        if (mAddUserDialog != null && mAddUserDialog.isShowing()) {
            mAddUserDialog.cancel();
        }
        mAddUserDialog = new AddUserDialog(mContext);
        if (dialogShower != null) {
            dialogShower.showDialog(mAddUserDialog,
                    new DialogCuj(
                            InteractionJankMonitor.CUJ_USER_DIALOG_OPEN,
                            INTERACTION_JANK_ADD_NEW_USER_TAG
                    ));
        } else {
            mAddUserDialog.show();
        }
    }

    @Override
    public void startSupervisedUserActivity() {
        final Intent intent = new Intent()
                .setAction(UserManager.ACTION_CREATE_SUPERVISED_USER)
                .setPackage(mCreateSupervisedUserPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        mContext.startActivity(intent);
    }

    private void listenForCallState() {
        mTelephonyListenerManager.addCallStateListener(mPhoneStateListener);
    }

    private final TelephonyCallback.CallStateListener mPhoneStateListener =
            new TelephonyCallback.CallStateListener() {
        private int mCallState;

        @Override
        public void onCallStateChanged(int state) {
            if (mCallState == state) return;
            if (DEBUG) Log.v(TAG, "Call state changed: " + state);
            mCallState = state;
            refreshUsers(UserHandle.USER_NULL);
        }
    };

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.v(TAG, "Broadcast: a=" + intent.getAction()
                        + " user=" + intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1));
            }

            boolean unpauseRefreshUsers = false;
            int forcePictureLoadForId = UserHandle.USER_NULL;

            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                if (mExitGuestDialog != null && mExitGuestDialog.isShowing()) {
                    mExitGuestDialog.cancel();
                    mExitGuestDialog = null;
                }

                final int currentId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                final UserInfo userInfo = mUserManager.getUserInfo(currentId);
                final int userCount = mUsers.size();
                for (int i = 0; i < userCount; i++) {
                    UserRecord record = mUsers.get(i);
                    if (record.info == null) continue;
                    boolean shouldBeCurrent = record.info.id == currentId;
                    if (record.isCurrent != shouldBeCurrent) {
                        mUsers.set(i, record.copyWithIsCurrent(shouldBeCurrent));
                    }
                    if (shouldBeCurrent && !record.isGuest) {
                        mLastNonGuestUser = record.info.id;
                    }
                    if ((userInfo == null || !userInfo.isAdmin()) && record.isRestricted) {
                        // Immediately remove restricted records in case the AsyncTask is too slow.
                        mUsers.remove(i);
                        i--;
                    }
                }
                notifyUserSwitchCallbacks();
                notifyAdapters();

                // Disconnect from the old secondary user's service
                if (mSecondaryUser != UserHandle.USER_NULL) {
                    context.stopServiceAsUser(mSecondaryUserServiceIntent,
                            UserHandle.of(mSecondaryUser));
                    mSecondaryUser = UserHandle.USER_NULL;
                }
                // Connect to the new secondary user's service (purely to ensure that a persistent
                // SystemUI application is created for that user)
                if (userInfo != null && userInfo.id != UserHandle.USER_SYSTEM) {
                    context.startServiceAsUser(mSecondaryUserServiceIntent,
                            UserHandle.of(userInfo.id));
                    mSecondaryUser = userInfo.id;
                }
                unpauseRefreshUsers = true;
                if (mGuestUserAutoCreated) {
                    // Guest user must be scheduled for creation AFTER switching to the target user.
                    // This avoids lock contention which will produce UX bugs on the keyguard
                    // (b/193933686).
                    // TODO(b/191067027): Move guest user recreation to system_server
                    guaranteeGuestPresent();
                }
            } else if (Intent.ACTION_USER_INFO_CHANGED.equals(intent.getAction())) {
                forcePictureLoadForId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_NULL);
            } else if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                // Unlocking the system user may require a refresh
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (userId != UserHandle.USER_SYSTEM) {
                    return;
                }
            }
            refreshUsers(forcePictureLoadForId);
            if (unpauseRefreshUsers) {
                mUnpauseRefreshUsers.run();
            }
        }
    };

    private final Runnable mUnpauseRefreshUsers = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(this);
            mPauseRefreshUsers = false;
            refreshUsers(UserHandle.USER_NULL);
        }
    };

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("UserSwitcherController state:");
        pw.println("  mLastNonGuestUser=" + mLastNonGuestUser);
        pw.print("  mUsers.size="); pw.println(mUsers.size());
        for (int i = 0; i < mUsers.size(); i++) {
            final UserRecord u = mUsers.get(i);
            pw.print("    "); pw.println(u.toString());
        }
        pw.println("mSimpleUserSwitcher=" + mSimpleUserSwitcher);
        pw.println("mGuestUserAutoCreated=" + mGuestUserAutoCreated);
    }

    @Override
    public String getCurrentUserName() {
        if (mUsers.isEmpty()) return null;
        UserRecord item = mUsers.stream().filter(x -> x.isCurrent).findFirst().orElse(null);
        if (item == null || item.info == null) return null;
        if (item.isGuest) return mContext.getString(com.android.internal.R.string.guest_name);
        return item.info.name;
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        refreshUsers(UserHandle.USER_ALL);
    }

    @Override
    public void addAdapter(WeakReference<BaseUserSwitcherAdapter> adapter) {
        mAdapters.add(adapter);
    }

    @Override
    public ArrayList<UserRecord> getUsers() {
        return mUsers;
    }

    @Override
    public void removeGuestUser(@UserIdInt int guestUserId, @UserIdInt int targetUserId) {
        UserInfo currentUser = mUserTracker.getUserInfo();
        if (currentUser.id != guestUserId) {
            Log.w(TAG, "User requesting to start a new session (" + guestUserId + ")"
                    + " is not current user (" + currentUser.id + ")");
            return;
        }
        if (!currentUser.isGuest()) {
            Log.w(TAG, "User requesting to start a new session (" + guestUserId + ")"
                    + " is not a guest");
            return;
        }

        boolean marked = mUserManager.markGuestForDeletion(currentUser.id);
        if (!marked) {
            Log.w(TAG, "Couldn't mark the guest for deletion for user " + guestUserId);
            return;
        }

        if (targetUserId == UserHandle.USER_NULL) {
            // Create a new guest in the foreground, and then immediately switch to it
            createGuestAsync(newGuestId -> {
                if (newGuestId == UserHandle.USER_NULL) {
                    Log.e(TAG, "Could not create new guest, switching back to system user");
                    switchToUserId(UserHandle.USER_SYSTEM);
                    mUserManager.removeUser(currentUser.id);
                    try {
                        WindowManagerGlobal.getWindowManagerService().lockNow(/* options= */ null);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Couldn't remove guest because ActivityManager "
                                + "or WindowManager is dead");
                    }
                    return;
                }
                switchToUserId(newGuestId);
                mUserManager.removeUser(currentUser.id);
            });
        } else {
            if (mGuestUserAutoCreated) {
                mGuestIsResetting.set(true);
            }
            switchToUserId(targetUserId);
            mUserManager.removeUser(currentUser.id);
        }
    }

    @Override
    public void exitGuestUser(@UserIdInt int guestUserId, @UserIdInt int targetUserId,
            boolean forceRemoveGuestOnExit) {
        UserInfo currentUser = mUserTracker.getUserInfo();
        if (currentUser.id != guestUserId) {
            Log.w(TAG, "User requesting to start a new session (" + guestUserId + ")"
                    + " is not current user (" + currentUser.id + ")");
            return;
        }
        if (!currentUser.isGuest()) {
            Log.w(TAG, "User requesting to start a new session (" + guestUserId + ")"
                    + " is not a guest");
            return;
        }

        int newUserId = UserHandle.USER_SYSTEM;
        if (targetUserId == UserHandle.USER_NULL) {
            // when target user is not specified switch to last non guest user
            if (mLastNonGuestUser != UserHandle.USER_SYSTEM) {
                UserInfo info = mUserManager.getUserInfo(mLastNonGuestUser);
                if (info != null && info.isEnabled() && info.supportsSwitchToByUser()) {
                    newUserId = info.id;
                }
            }
        } else {
            newUserId = targetUserId;
        }

        if (currentUser.isEphemeral() || forceRemoveGuestOnExit) {
            mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_REMOVE);
            removeGuestUser(currentUser.id, newUserId);
        } else {
            mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_SWITCH);
            switchToUserId(newUserId);
        }
    }

    private void scheduleGuestCreation() {
        if (!mGuestCreationScheduled.compareAndSet(false, true)) {
            return;
        }

        mLongRunningExecutor.execute(() -> {
            int newGuestId = createGuest();
            mGuestCreationScheduled.set(false);
            mGuestIsResetting.set(false);
            if (newGuestId == UserHandle.USER_NULL) {
                Log.w(TAG, "Could not create new guest while exiting existing guest");
                // Refresh users so that we still display "Guest" if
                // config_guestUserAutoCreated=true
                refreshUsers(UserHandle.USER_NULL);
            }
        });

    }

    @Override
    public void schedulePostBootGuestCreation() {
        if (isDeviceAllowedToAddGuest()) {
            guaranteeGuestPresent();
        } else {
            mDeviceProvisionedController.addCallback(mGuaranteeGuestPresentAfterProvisioned);
        }
    }

    private boolean isDeviceAllowedToAddGuest() {
        return mDeviceProvisionedController.isDeviceProvisioned()
                && !mDevicePolicyManager.isDeviceManaged();
    }

    /**
     * If there is no guest on the device, schedule creation of a new guest user in the background.
     */
    private void guaranteeGuestPresent() {
        if (isDeviceAllowedToAddGuest() && mUserManager.findCurrentGuestUser() == null) {
            scheduleGuestCreation();
        }
    }

    private void createGuestAsync(Consumer<Integer> callback) {
        final Dialog guestCreationProgressDialog =
                new UserCreatingDialog(mContext, /* isGuest= */true);
        guestCreationProgressDialog.show();

        // userManager.createGuest will block the thread so post is needed for the dialog to show
        mBgExecutor.execute(() -> {
            final int guestId = createGuest();
            mUiExecutor.execute(() -> {
                guestCreationProgressDialog.dismiss();
                if (guestId == UserHandle.USER_NULL) {
                    Toast.makeText(mContext,
                            com.android.settingslib.R.string.add_guest_failed,
                            Toast.LENGTH_SHORT).show();
                }
                callback.accept(guestId);
            });
        });
    }

    /**
     * Creates a guest user and return its multi-user user ID.
     *
     * This method does not check if a guest already exists before it makes a call to
     * {@link UserManager} to create a new one.
     *
     * @return The multi-user user ID of the newly created guest user, or
     * {@link UserHandle#USER_NULL} if the guest couldn't be created.
     */
    private @UserIdInt int createGuest() {
        UserInfo guest;
        try {
            guest = mUserManager.createGuest(mContext);
        } catch (UserManager.UserOperationException e) {
            Log.e(TAG, "Couldn't create guest user", e);
            return UserHandle.USER_NULL;
        }
        if (guest == null) {
            Log.e(TAG, "Couldn't create guest, most likely because there already exists one");
            return UserHandle.USER_NULL;
        }
        return guest.id;
    }

    @Override
    public void init(View view) {
        mView = view;
    }

    @Override
    public boolean isKeyguardShowing() {
        return mKeyguardStateController.isShowing();
    }

    @Override
    @Nullable
    public EnforcedAdmin getEnforcedAdmin(UserRecord record) {
        return mEnforcedAdminByUserRecord.get(record);
    }

    @Override
    public boolean isDisabledByAdmin(UserRecord record) {
        return mDisabledByAdmin.contains(record);
    }

    private void checkIfAddUserDisallowedByAdminOnly(UserRecord record) {
        EnforcedAdmin admin = RestrictedLockUtilsInternal.checkIfRestrictionEnforced(mContext,
                UserManager.DISALLOW_ADD_USER, mUserTracker.getUserId());
        if (admin != null && !RestrictedLockUtilsInternal.hasBaseUserRestriction(mContext,
                UserManager.DISALLOW_ADD_USER, mUserTracker.getUserId())) {
            mDisabledByAdmin.add(record);
            mEnforcedAdminByUserRecord.put(record, admin);
        } else {
            mDisabledByAdmin.remove(record);
            mEnforcedAdminByUserRecord.put(record, null);
        }
    }

    private boolean shouldUseSimpleUserSwitcher() {
        int defaultSimpleUserSwitcher = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_expandLockScreenUserSwitcher) ? 1 : 0;
        return mGlobalSettings.getIntForUser(SIMPLE_USER_SWITCHER_GLOBAL_SETTING,
                defaultSimpleUserSwitcher, UserHandle.USER_SYSTEM) != 0;
    }

    @Override
    public void startActivity(Intent intent) {
        mActivityStarter.startActivity(intent, true);
    }

    @Override
    public void addUserSwitchCallback(UserSwitchCallback callback) {
        mUserSwitchCallbacks.add(callback);
    }

    @Override
    public void removeUserSwitchCallback(UserSwitchCallback callback) {
        mUserSwitchCallbacks.remove(callback);
    }

    /**
     *  Notify user switch callbacks that user has switched.
     */
    private void notifyUserSwitchCallbacks() {
        List<UserSwitchCallback> temp;
        synchronized (mUserSwitchCallbacks) {
            temp = new ArrayList<>(mUserSwitchCallbacks);
        }
        for (UserSwitchCallback callback : temp) {
            callback.onUserSwitched();
        }
    }

    private final KeyguardStateController.Callback mCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {

                    // When Keyguard is going away, we don't need to update our items immediately
                    // which
                    // helps making the transition faster.
                    if (!mKeyguardStateController.isShowing()) {
                        mHandler.post(UserSwitcherControllerOldImpl.this::notifyAdapters);
                    } else {
                        notifyAdapters();
                    }
                }
            };

    private final DeviceProvisionedController.DeviceProvisionedListener
            mGuaranteeGuestPresentAfterProvisioned =
            new DeviceProvisionedController.DeviceProvisionedListener() {
                @Override
                public void onDeviceProvisionedChanged() {
                    if (isDeviceAllowedToAddGuest()) {
                        mBgExecutor.execute(
                                () -> mDeviceProvisionedController.removeCallback(
                                        mGuaranteeGuestPresentAfterProvisioned));
                        guaranteeGuestPresent();
                    }
                }
            };


    private final class ExitGuestDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        private final int mGuestId;
        private final int mTargetId;
        private final boolean mIsGuestEphemeral;

        ExitGuestDialog(Context context, int guestId, boolean isGuestEphemeral,
                    int targetId) {
            super(context);
            if (isGuestEphemeral) {
                setTitle(context.getString(
                            com.android.settingslib.R.string.guest_exit_dialog_title));
                setMessage(context.getString(
                            com.android.settingslib.R.string.guest_exit_dialog_message));
                setButton(DialogInterface.BUTTON_NEUTRAL,
                        context.getString(android.R.string.cancel), this);
                setButton(DialogInterface.BUTTON_POSITIVE,
                        context.getString(
                            com.android.settingslib.R.string.guest_exit_dialog_button), this);
            } else {
                setTitle(context.getString(
                            com.android.settingslib
                                .R.string.guest_exit_dialog_title_non_ephemeral));
                setMessage(context.getString(
                            com.android.settingslib
                                .R.string.guest_exit_dialog_message_non_ephemeral));
                setButton(DialogInterface.BUTTON_NEUTRAL,
                        context.getString(android.R.string.cancel), this);
                setButton(DialogInterface.BUTTON_NEGATIVE,
                        context.getString(
                            com.android.settingslib.R.string.guest_exit_clear_data_button),
                        this);
                setButton(DialogInterface.BUTTON_POSITIVE,
                        context.getString(
                            com.android.settingslib.R.string.guest_exit_save_data_button),
                        this);
            }
            SystemUIDialog.setWindowOnTop(this, mKeyguardStateController.isShowing());
            setCanceledOnTouchOutside(false);
            mGuestId = guestId;
            mTargetId = targetId;
            mIsGuestEphemeral = isGuestEphemeral;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            int penalty = which == BUTTON_NEGATIVE ? FalsingManager.NO_PENALTY
                    : FalsingManager.HIGH_PENALTY;
            if (mFalsingManager.isFalseTap(penalty)) {
                return;
            }
            if (mIsGuestEphemeral) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    mDialogLaunchAnimator.dismissStack(this);
                    // Ephemeral guest: exit guest, guest is removed by the system
                    // on exit, since its marked ephemeral
                    exitGuestUser(mGuestId, mTargetId, false);
                } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                    // Cancel clicked, do nothing
                    cancel();
                }
            } else {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    mDialogLaunchAnimator.dismissStack(this);
                    // Non-ephemeral guest: exit guest, guest is not removed by the system
                    // on exit, since its marked non-ephemeral
                    exitGuestUser(mGuestId, mTargetId, false);
                } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                    mDialogLaunchAnimator.dismissStack(this);
                    // Non-ephemeral guest: remove guest and then exit
                    exitGuestUser(mGuestId, mTargetId, true);
                } else if (which == DialogInterface.BUTTON_NEUTRAL) {
                    // Cancel clicked, do nothing
                    cancel();
                }
            }
        }
    }

    @VisibleForTesting
    final class AddUserDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        AddUserDialog(Context context) {
            super(context);

            setTitle(com.android.settingslib.R.string.user_add_user_title);
            String message = context.getString(
                                com.android.settingslib.R.string.user_add_user_message_short);
            UserInfo currentUser = mUserTracker.getUserInfo();
            if (currentUser != null && currentUser.isGuest() && currentUser.isEphemeral()) {
                message += context.getString(R.string.user_add_user_message_guest_remove);
            }
            setMessage(message);
            setButton(DialogInterface.BUTTON_NEUTRAL,
                    context.getString(android.R.string.cancel), this);
            setButton(DialogInterface.BUTTON_POSITIVE,
                    context.getString(android.R.string.ok), this);
            SystemUIDialog.setWindowOnTop(this, mKeyguardStateController.isShowing());
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            int penalty = which == BUTTON_NEGATIVE ? FalsingManager.NO_PENALTY
                    : FalsingManager.MODERATE_PENALTY;
            if (mFalsingManager.isFalseTap(penalty)) {
                return;
            }
            if (which == BUTTON_NEUTRAL) {
                cancel();
            } else {
                mDialogLaunchAnimator.dismissStack(this);
                if (ActivityManager.isUserAMonkey()) {
                    return;
                }
                // Use broadcast instead of ShadeController, as this dialog may have started in
                // another process and normal dagger bindings are not available
                mBroadcastSender.sendBroadcastAsUser(
                        new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), UserHandle.CURRENT);
                getContext().startActivityAsUser(
                        CreateUserActivity.createIntentForStart(getContext()),
                        mUserTracker.getUserHandle());
            }
        }
    }

}
