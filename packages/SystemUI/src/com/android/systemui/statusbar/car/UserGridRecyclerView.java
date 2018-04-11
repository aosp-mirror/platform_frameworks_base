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

package com.android.systemui.statusbar.car;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.settingslib.users.UserManagerHelper;
import com.android.systemui.R;
import com.android.systemui.qs.car.CarQSFragment;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays a GridLayout with icons for the users in the system to allow switching between users.
 * One of the uses of this is for the lock screen in auto.
 */
public class UserGridRecyclerView extends RecyclerView implements
        UserManagerHelper.OnUsersUpdateListener {

    private StatusBar mStatusBar;
    private UserSelectionListener mUserSelectionListener;
    private UserAdapter mAdapter;
    private UserManagerHelper mUserManagerHelper;
    private Context mContext;

    public UserGridRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        super.setHasFixedSize(true);
        mContext = context;
        mUserManagerHelper = new UserManagerHelper(mContext);
    }

    /**
     * Register listener for any update to the users
     */
    @Override
    public void onFinishInflate() {
        mUserManagerHelper.registerOnUsersUpdateListener(this);
    }

    /**
     * Unregisters listener checking for any change to the users
     */
    @Override
    public void onDetachedFromWindow() {
        mUserManagerHelper.unregisterOnUsersUpdateListener();
    }

    /**
     * Initializes the adapter that populates the grid layout
     *
     * @return the adapter
     */
    public void buildAdapter() {
        List<UserRecord> userRecords = createUserRecords(mUserManagerHelper
                .getAllUsers());
        mAdapter = new UserAdapter(mContext, userRecords);
        super.setAdapter(mAdapter);
    }

    public void setStatusBar(@Nullable StatusBar statusBar) {
        mStatusBar = statusBar;
    }

    private List<UserRecord> createUserRecords(List<UserInfo> userInfoList) {
        List<UserRecord> userRecords = new ArrayList<>();
        for (UserInfo userInfo : userInfoList) {
            boolean isCurrent = false;
            if (ActivityManager.getCurrentUser() == userInfo.id) {
                isCurrent = true;
            }
            UserRecord record = new UserRecord(userInfo, false /* isGuest */,
                    false /* isAddUser */, isCurrent);
            userRecords.add(record);
        }

        // Add guest user record if the current user is not a guest
        if (!mUserManagerHelper.foregroundUserIsGuestUser()) {
            userRecords.add(addGuestUserRecord());
        }

        // Add add user record if the current user can add users
        if (mUserManagerHelper.foregroundUserCanAddUsers()) {
            userRecords.add(addUserRecord());
        }

        return userRecords;
    }

    /**
     * Create guest user record
     */
    private UserRecord addGuestUserRecord() {
        UserInfo userInfo = new UserInfo();
        userInfo.name = mContext.getString(R.string.car_guest);
        return new UserRecord(userInfo, true /* isGuest */,
                false /* isAddUser */, false /* isCurrent */);
    }

    /**
     * Create add user record
     */
    private UserRecord addUserRecord() {
        UserInfo userInfo = new UserInfo();
        userInfo.name = mContext.getString(R.string.car_add_user);
        return new UserRecord(userInfo, false /* isGuest */,
                true /* isAddUser */, false /* isCurrent */);
    }

    public void onUserSwitched(int newUserId) {
        // Bring up security view after user switch is completed.
        post(this::showOfflineAuthUi);
    }

    public void setUserSelectionListener(UserSelectionListener userSelectionListener) {
        mUserSelectionListener = userSelectionListener;
    }

    void showOfflineAuthUi() {
        // TODO: Show keyguard UI in-place.
        if (mStatusBar != null) {
            mStatusBar.executeRunnableDismissingKeyguard(null/* runnable */, null /* cancelAction */,
                    true /* dismissShade */, true /* afterKeyguardGone */, true /* deferred */);
        }
    }

    @Override
    public void onUsersUpdate() {
        mAdapter.clearUsers();
        mAdapter.updateUsers(createUserRecords(mUserManagerHelper.getAllUsers()));
        mAdapter.notifyDataSetChanged();
    }

    /**
     * Adapter to populate the grid layout with the available user profiles
     */
    public final class UserAdapter extends RecyclerView.Adapter<UserAdapter.UserAdapterViewHolder> {

        private final Context mContext;
        private List<UserRecord> mUsers;
        private final int mPodImageAvatarWidth;
        private final int mPodImageAvatarHeight;
        private final Resources mRes;
        private final String mGuestName;
        private final String mNewUserName;

        public UserAdapter(Context context, List<UserRecord> users) {
            mRes = context.getResources();
            mContext = context;
            updateUsers(users);
            mPodImageAvatarWidth = mRes.getDimensionPixelSize(
                    R.dimen.car_fullscreen_user_pod_image_avatar_width);
            mPodImageAvatarHeight = mRes.getDimensionPixelSize(
                    R.dimen.car_fullscreen_user_pod_image_avatar_height);
            mGuestName = mRes.getString(R.string.car_guest);
            mNewUserName = mRes.getString(R.string.car_new_user);
        }

        public void clearUsers() {
            mUsers.clear();
        }

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
            holder.mUserAvatarImageView.setImageBitmap(getDefaultUserIcon(userRecord));
            holder.mUserNameTextView.setText(userRecord.mInfo.name);
            holder.mView.setOnClickListener(v -> {
                if (userRecord == null) {
                    return;
                }

                // Notify the listener which user was selected
                if (mUserSelectionListener != null) {
                    mUserSelectionListener.onUserSelected(userRecord);
                }

                // If the user selects Guest, switch to Guest profile
                if (userRecord.mIsGuest) {
                    mUserManagerHelper.switchToGuest(mGuestName);
                    return;
                }

                // If the user wants to add a user, start task to add new user
                if (userRecord.mIsAddUser) {
                    new AddNewUserTask().execute(mNewUserName);
                    return;
                }

                // If the user doesn't want to be a guest or add a user, switch to the user selected
                mUserManagerHelper.switchToUser(userRecord.mInfo);
            });

        }

        private class AddNewUserTask extends AsyncTask<String, Void, UserInfo> {

            @Override
            protected UserInfo doInBackground(String... userNames) {
                return mUserManagerHelper.createNewUser(userNames[0]);
            }

            @Override
            protected void onPreExecute() {
            }

            @Override
            protected void onPostExecute(UserInfo user) {
                if (user != null) {
                    mUserManagerHelper.switchToUser(user);
                }
            }
        }

        @Override
        public int getItemCount() {
            return mUsers.size();
        }

        /**
         * Returns the default user icon.  This icon is a circle with a letter in it.  The letter is
         * the first character in the username.
         *
         * @param record the profile of the user for which the icon should be created
         */
        private Bitmap getDefaultUserIcon(UserRecord record) {
            CharSequence displayText;
            boolean isAddUserText = false;
            if (record.mIsAddUser) {
                displayText = "+";
                isAddUserText = true;
            } else {
                displayText = record.mInfo.name.subSequence(0, 1);
            }
            Bitmap out = Bitmap.createBitmap(mPodImageAvatarWidth, mPodImageAvatarHeight,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);

            // Draw the circle background.
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RADIAL_GRADIENT);
            shape.setGradientRadius(1.0f);
            shape.setColor(mContext.getColor(R.color.car_user_switcher_no_user_image_bgcolor));
            shape.setBounds(0, 0, mPodImageAvatarWidth, mPodImageAvatarHeight);
            shape.draw(canvas);

            // Draw the letter in the center.
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(mContext.getColor(R.color.car_user_switcher_no_user_image_fgcolor));
            paint.setTextAlign(Align.CENTER);
            if (isAddUserText) {
                paint.setTextSize(mRes.getDimensionPixelSize(
                        R.dimen.car_touch_target_size));
            } else {
                paint.setTextSize(mRes.getDimensionPixelSize(
                        R.dimen.car_fullscreen_user_pod_icon_text_size));
            }

            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            // The Y coordinate is measured by taking half the height of the pod, but that would
            // draw the character putting the bottom of the font in the middle of the pod.  To
            // correct this, half the difference between the top and bottom distance metrics of the
            // font gives the offset of the font.  Bottom is a positive value, top is negative, so
            // the different is actually a sum.  The "half" operation is then factored out.
            canvas.drawText(displayText.toString(), mPodImageAvatarWidth / 2,
                    (mPodImageAvatarHeight - (metrics.bottom + metrics.top)) / 2, paint);

            return out;
        }

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
     * guest profile, add user profile, or a current user.
     */
    public static final class UserRecord {

        public final UserInfo mInfo;
        public final boolean mIsGuest;
        public final boolean mIsAddUser;
        public final boolean mIsCurrent;

        public UserRecord(UserInfo userInfo, boolean isGuest, boolean isAddUser,
                boolean isCurrent) {
            mInfo = userInfo;
            mIsGuest = isGuest;
            mIsAddUser = isAddUser;
            mIsCurrent = isCurrent;
        }
    }

    /**
     * Listener used to notify when a user has been selected
     */
    interface UserSelectionListener {

        void onUserSelected(UserRecord record);
    }
}
