/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.userswitcher;

import static android.content.DialogInterface.BUTTON_NEGATIVE;
import static android.content.DialogInterface.BUTTON_POSITIVE;
import static android.os.UserManager.DISALLOW_ADD_USER;
import static android.os.UserManager.SWITCHABILITY_STATUS_OK;
import static android.view.WindowInsets.Type.statusBars;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.car.user.CarUserManager;
import android.car.user.UserCreationResult;
import android.car.user.UserSwitchResult;
import android.car.userlib.UserHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.os.UserManager;
import android.sysprop.CarProperties;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.UserIcons;
import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Displays a GridLayout with icons for the users in the system to allow switching between users.
 * One of the uses of this is for the lock screen in auto.
 */
public class UserGridRecyclerView extends RecyclerView {
    private static final String TAG = UserGridRecyclerView.class.getSimpleName();
    private static final int TIMEOUT_MS = CarProperties.user_hal_timeout().orElse(5_000) + 500;

    private UserSelectionListener mUserSelectionListener;
    private UserAdapter mAdapter;
    private CarUserManager mCarUserManager;
    private UserManager mUserManager;
    private Context mContext;
    private UserIconProvider mUserIconProvider;

    private final BroadcastReceiver mUserUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onUsersUpdate();
        }
    };

    public UserGridRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mUserManager = UserManager.get(mContext);
        mUserIconProvider = new UserIconProvider();

        addItemDecoration(new ItemSpacingDecoration(mContext.getResources().getDimensionPixelSize(
                R.dimen.car_user_switcher_vertical_spacing_between_users)));
    }

    /**
     * Register listener for any update to the users
     */
    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        registerForUserEvents();
    }

    /**
     * Unregisters listener checking for any change to the users
     */
    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        unregisterForUserEvents();
    }

    /**
     * Initializes the adapter that populates the grid layout
     */
    public void buildAdapter() {
        List<UserRecord> userRecords = createUserRecords(getUsersForUserGrid());
        mAdapter = new UserAdapter(mContext, userRecords);
        super.setAdapter(mAdapter);
    }

    private List<UserInfo> getUsersForUserGrid() {
        return mUserManager.getUsers(/* excludeDying= */ true)
                .stream()
                .filter(UserInfo::supportsSwitchToByUser)
                .collect(Collectors.toList());
    }

    private List<UserRecord> createUserRecords(List<UserInfo> userInfoList) {
        int fgUserId = ActivityManager.getCurrentUser();
        UserHandle fgUserHandle = UserHandle.of(fgUserId);
        List<UserRecord> userRecords = new ArrayList<>();

        // If the foreground user CANNOT switch to other users, only display the foreground user.
        if (mUserManager.getUserSwitchability(fgUserHandle) != SWITCHABILITY_STATUS_OK) {
            userRecords.add(createForegroundUserRecord());
            return userRecords;
        }

        for (UserInfo userInfo : userInfoList) {
            if (userInfo.isGuest()) {
                // Don't display guests in the switcher.
                continue;
            }

            boolean isForeground = fgUserId == userInfo.id;
            UserRecord record = new UserRecord(userInfo,
                    isForeground ? UserRecord.FOREGROUND_USER : UserRecord.BACKGROUND_USER);
            userRecords.add(record);
        }

        // Add button for starting guest session.
        userRecords.add(createStartGuestUserRecord());

        // Add add user record if the foreground user can add users
        if (!mUserManager.hasUserRestriction(DISALLOW_ADD_USER, fgUserHandle)) {
            userRecords.add(createAddUserRecord());
        }

        return userRecords;
    }

    private UserRecord createForegroundUserRecord() {
        return new UserRecord(mUserManager.getUserInfo(ActivityManager.getCurrentUser()),
                UserRecord.FOREGROUND_USER);
    }

    /**
     * Create guest user record
     */
    private UserRecord createStartGuestUserRecord() {
        return new UserRecord(null /* userInfo */, UserRecord.START_GUEST);
    }

    /**
     * Create add user record
     */
    private UserRecord createAddUserRecord() {
        return new UserRecord(null /* userInfo */, UserRecord.ADD_USER);
    }

    public void setUserSelectionListener(UserSelectionListener userSelectionListener) {
        mUserSelectionListener = userSelectionListener;
    }

    /** Sets a {@link CarUserManager}. */
    public void setCarUserManager(CarUserManager carUserManager) {
        mCarUserManager = carUserManager;
    }

    private void onUsersUpdate() {
        mAdapter.clearUsers();
        mAdapter.updateUsers(createUserRecords(getUsersForUserGrid()));
        mAdapter.notifyDataSetChanged();
    }

    private void registerForUserEvents() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiverAsUser(
                mUserUpdateReceiver,
                UserHandle.ALL, // Necessary because CarSystemUi lives in User 0
                filter,
                /* broadcastPermission= */ null,
                /* scheduler= */ null);
    }

    private void unregisterForUserEvents() {
        mContext.unregisterReceiver(mUserUpdateReceiver);
    }

    /**
     * Adapter to populate the grid layout with the available user profiles
     */
    public final class UserAdapter extends RecyclerView.Adapter<UserAdapter.UserAdapterViewHolder>
            implements Dialog.OnClickListener, Dialog.OnCancelListener {

        private final Context mContext;
        private List<UserRecord> mUsers;
        private final Resources mRes;
        private final String mGuestName;
        private final String mNewUserName;
        // View that holds the add user button.  Used to enable/disable the view
        private View mAddUserView;
        // User record for the add user.  Need to call notifyUserSelected only if the user
        // confirms adding a user
        private UserRecord mAddUserRecord;

        public UserAdapter(Context context, List<UserRecord> users) {
            mRes = context.getResources();
            mContext = context;
            updateUsers(users);
            mGuestName = mRes.getString(R.string.car_guest);
            mNewUserName = mRes.getString(R.string.car_new_user);
        }

        /**
         * Clears list of user records.
         */
        public void clearUsers() {
            mUsers.clear();
        }

        /**
         * Updates list of user records.
         */
        public void updateUsers(List<UserRecord> users) {
            mUsers = users;
        }

        @Override
        public UserAdapterViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(mContext)
                    .inflate(R.layout.car_fullscreen_user_pod, parent, false);
            view.setAlpha(1f);
            view.bringToFront();
            return new UserAdapterViewHolder(view);
        }

        @Override
        public void onBindViewHolder(UserAdapterViewHolder holder, int position) {
            UserRecord userRecord = mUsers.get(position);
            RoundedBitmapDrawable circleIcon = getCircularUserRecordIcon(userRecord);
            holder.mUserAvatarImageView.setImageDrawable(circleIcon);
            holder.mUserNameTextView.setText(getUserRecordName(userRecord));

            holder.mView.setOnClickListener(v -> {
                if (userRecord == null) {
                    return;
                }

                switch (userRecord.mType) {
                    case UserRecord.START_GUEST:
                        notifyUserSelected(userRecord);
                        UserInfo guest = createNewOrFindExistingGuest(mContext);
                        if (guest != null) {
                            if (!switchUser(guest.id)) {
                                Log.e(TAG, "Failed to switch to guest user: " + guest.id);
                            }
                        }
                        break;
                    case UserRecord.ADD_USER:
                        // If the user wants to add a user, show dialog to confirm adding a user
                        // Disable button so it cannot be clicked multiple times
                        mAddUserView = holder.mView;
                        mAddUserView.setEnabled(false);
                        mAddUserRecord = userRecord;

                        handleAddUserClicked();
                        break;
                    default:
                        // If the user doesn't want to be a guest or add a user, switch to the user
                        // selected
                        notifyUserSelected(userRecord);
                        if (!switchUser(userRecord.mInfo.id)) {
                            Log.e(TAG, "Failed to switch users: " + userRecord.mInfo.id);
                        }
                }
            });

        }

        private void handleAddUserClicked() {
            if (!mUserManager.canAddMoreUsers()) {
                mAddUserView.setEnabled(true);
                showMaxUserLimitReachedDialog();
            } else {
                showConfirmAddUserDialog();
            }
        }

        /**
         * Get the maximum number of real (non-guest, non-managed profile) users that can be created
         * on the device. This is a dynamic value and it decreases with the increase of the number
         * of managed profiles on the device.
         *
         * <p> It excludes system user in headless system user model.
         *
         * @return Maximum number of real users that can be created.
         */
        private int getMaxSupportedRealUsers() {
            int maxSupportedUsers = UserManager.getMaxSupportedUsers();
            if (UserManager.isHeadlessSystemUserMode()) {
                maxSupportedUsers -= 1;
            }

            List<UserInfo> users = mUserManager.getUsers(/* excludeDying= */ true);

            // Count all users that are managed profiles of another user.
            int managedProfilesCount = 0;
            for (UserInfo user : users) {
                if (user.isManagedProfile()) {
                    managedProfilesCount++;
                }
            }

            return maxSupportedUsers - managedProfilesCount;
        }

        private void showMaxUserLimitReachedDialog() {
            AlertDialog maxUsersDialog = new Builder(mContext,
                    com.android.internal.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.user_limit_reached_title)
                    .setMessage(getResources().getQuantityString(
                            R.plurals.user_limit_reached_message,
                            getMaxSupportedRealUsers(),
                            getMaxSupportedRealUsers()))
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            // Sets window flags for the SysUI dialog
            applyCarSysUIDialogFlags(maxUsersDialog);
            maxUsersDialog.show();
        }

        private void showConfirmAddUserDialog() {
            String message = mRes.getString(R.string.user_add_user_message_setup)
                    .concat(System.getProperty("line.separator"))
                    .concat(System.getProperty("line.separator"))
                    .concat(mRes.getString(R.string.user_add_user_message_update));

            AlertDialog addUserDialog = new Builder(mContext,
                    com.android.internal.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.user_add_user_title)
                    .setMessage(message)
                    .setNegativeButton(android.R.string.cancel, this)
                    .setPositiveButton(android.R.string.ok, this)
                    .setOnCancelListener(this)
                    .create();
            // Sets window flags for the SysUI dialog
            applyCarSysUIDialogFlags(addUserDialog);
            addUserDialog.show();
        }

        private void applyCarSysUIDialogFlags(AlertDialog dialog) {
            final Window window = dialog.getWindow();
            window.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            window.getAttributes().setFitInsetsTypes(
                    window.getAttributes().getFitInsetsTypes() & ~statusBars());
        }

        private void notifyUserSelected(UserRecord userRecord) {
            // Notify the listener which user was selected
            if (mUserSelectionListener != null) {
                mUserSelectionListener.onUserSelected(userRecord);
            }
        }

        private RoundedBitmapDrawable getCircularUserRecordIcon(UserRecord userRecord) {
            Resources resources = mContext.getResources();
            RoundedBitmapDrawable circleIcon;
            switch (userRecord.mType) {
                case UserRecord.START_GUEST:
                    circleIcon = mUserIconProvider.getRoundedGuestDefaultIcon(resources);
                    break;
                case UserRecord.ADD_USER:
                    circleIcon = getCircularAddUserIcon();
                    break;
                default:
                    circleIcon = mUserIconProvider.getRoundedUserIcon(userRecord.mInfo, mContext);
                    break;
            }
            return circleIcon;
        }

        private RoundedBitmapDrawable getCircularAddUserIcon() {
            RoundedBitmapDrawable circleIcon =
                    RoundedBitmapDrawableFactory.create(mRes, UserIcons.convertToBitmap(
                    mContext.getDrawable(R.drawable.car_add_circle_round)));
            circleIcon.setCircular(true);
            return circleIcon;
        }

        private String getUserRecordName(UserRecord userRecord) {
            String recordName;
            switch (userRecord.mType) {
                case UserRecord.START_GUEST:
                    recordName = mContext.getString(R.string.start_guest_session);
                    break;
                case UserRecord.ADD_USER:
                    recordName = mContext.getString(R.string.car_add_user);
                    break;
                default:
                    recordName = userRecord.mInfo.name;
                    break;
            }
            return recordName;
        }

        /**
         * Finds the existing Guest user, or creates one if it doesn't exist.
         * @param context App context
         * @return UserInfo representing the Guest user
         */
        @Nullable
        public UserInfo createNewOrFindExistingGuest(Context context) {
            AndroidFuture<UserCreationResult> future = mCarUserManager.createGuest(mGuestName);
            // CreateGuest will return null if a guest already exists.
            UserInfo newGuest = getUserInfo(future);
            if (newGuest != null) {
                new UserIconProvider().assignDefaultIcon(
                        mUserManager, context.getResources(), newGuest);
                return newGuest;
            }

            return mUserManager.findCurrentGuestUser();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == BUTTON_POSITIVE) {
                new AddNewUserTask().execute(mNewUserName);
            } else if (which == BUTTON_NEGATIVE) {
                // Enable the add button only if cancel
                if (mAddUserView != null) {
                    mAddUserView.setEnabled(true);
                }
            }
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            // Enable the add button again if user cancels dialog by clicking outside the dialog
            if (mAddUserView != null) {
                mAddUserView.setEnabled(true);
            }
        }

        @Nullable
        private UserInfo getUserInfo(AndroidFuture<UserCreationResult> future) {
            UserCreationResult userCreationResult;
            try {
                userCreationResult = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Log.w(TAG, "Could not create user.", e);
                return null;
            }

            if (userCreationResult == null) {
                Log.w(TAG, "Timed out while creating user: " + TIMEOUT_MS + "ms");
                return null;
            }
            if (!userCreationResult.isSuccess() || userCreationResult.getUser() == null) {
                Log.w(TAG, "Could not create user: " + userCreationResult);
                return null;
            }

            return userCreationResult.getUser();
        }

        private boolean switchUser(@UserIdInt int userId) {
            AndroidFuture<UserSwitchResult> userSwitchResultFuture =
                    mCarUserManager.switchUser(userId);
            UserSwitchResult userSwitchResult;
            try {
                userSwitchResult = userSwitchResultFuture.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Log.w(TAG, "Could not switch user.", e);
                return false;
            }

            if (userSwitchResult == null) {
                Log.w(TAG, "Timed out while switching user: " + TIMEOUT_MS + "ms");
                return false;
            }
            if (!userSwitchResult.isSuccess()) {
                Log.w(TAG, "Could not switch user: " + userSwitchResult);
                return false;
            }

            return true;
        }

        // TODO(b/161539497): Replace AsyncTask with standard {@link java.util.concurrent} code.
        private class AddNewUserTask extends AsyncTask<String, Void, UserInfo> {

            @Override
            protected UserInfo doInBackground(String... userNames) {
                AndroidFuture<UserCreationResult> future = mCarUserManager.createUser(userNames[0],
                        /* flags= */ 0);
                try {
                    UserInfo user = getUserInfo(future);
                    if (user != null) {
                        UserHelper.setDefaultNonAdminRestrictions(mContext, user,
                                /* enable= */ true);
                        UserHelper.assignDefaultIcon(mContext, user);
                        mAddUserRecord = new UserRecord(user, UserRecord.ADD_USER);
                        return user;
                    } else {
                        Log.e(TAG, "Failed to create user in the background");
                        return user;
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    Log.e(TAG, "Error creating new user: ", e);
                }
                return null;
            }

            @Override
            protected void onPreExecute() {
            }

            @Override
            protected void onPostExecute(UserInfo user) {
                if (user != null) {
                    notifyUserSelected(mAddUserRecord);
                    mAddUserView.setEnabled(true);
                    if (!switchUser(user.id)) {
                        Log.e(TAG, "Failed to switch to new user: " + user.id);
                    }
                }
                if (mAddUserView != null) {
                    mAddUserView.setEnabled(true);
                }
            }
        }

        @Override
        public int getItemCount() {
            return mUsers.size();
        }

        /**
         * An extension of {@link RecyclerView.ViewHolder} that also houses the user name and the
         * user avatar.
         */
        public class UserAdapterViewHolder extends RecyclerView.ViewHolder {

            public ImageView mUserAvatarImageView;
            public TextView mUserNameTextView;
            public View mView;

            public UserAdapterViewHolder(View view) {
                super(view);
                mView = view;
                mUserAvatarImageView = (ImageView) view.findViewById(R.id.user_avatar);
                mUserNameTextView = (TextView) view.findViewById(R.id.user_name);
            }
        }
    }

    /**
     * Object wrapper class for the userInfo.  Use it to distinguish if a profile is a
     * guest profile, add user profile, or the foreground user.
     */
    public static final class UserRecord {
        public final UserInfo mInfo;
        public final @UserRecordType int mType;

        public static final int START_GUEST = 0;
        public static final int ADD_USER = 1;
        public static final int FOREGROUND_USER = 2;
        public static final int BACKGROUND_USER = 3;

        @IntDef({START_GUEST, ADD_USER, FOREGROUND_USER, BACKGROUND_USER})
        @Retention(RetentionPolicy.SOURCE)
        public @interface UserRecordType{}

        public UserRecord(@Nullable UserInfo userInfo, @UserRecordType int recordType) {
            mInfo = userInfo;
            mType = recordType;
        }
    }

    /**
     * Listener used to notify when a user has been selected
     */
    interface UserSelectionListener {

        void onUserSelected(UserRecord record);
    }

    /**
     * A {@link RecyclerView.ItemDecoration} that will add spacing between each item in the
     * RecyclerView that it is added to.
     */
    private static class ItemSpacingDecoration extends RecyclerView.ItemDecoration {
        private int mItemSpacing;

        private ItemSpacingDecoration(int itemSpacing) {
            mItemSpacing = itemSpacing;
        }

        @Override
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                RecyclerView.State state) {
            super.getItemOffsets(outRect, view, parent, state);
            int position = parent.getChildAdapterPosition(view);

            // Skip offset for last item except for GridLayoutManager.
            if (position == state.getItemCount() - 1
                    && !(parent.getLayoutManager() instanceof GridLayoutManager)) {
                return;
            }

            outRect.bottom = mItemSpacing;
        }
    }
}
