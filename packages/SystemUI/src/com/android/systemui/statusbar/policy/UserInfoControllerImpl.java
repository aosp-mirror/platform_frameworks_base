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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.util.UserIcons;
import com.android.settingslib.drawable.UserIconDrawable;
import com.android.systemui.res.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;

import java.util.ArrayList;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 */
@SysUISingleton
public class UserInfoControllerImpl implements UserInfoController {

    private static final String TAG = "UserInfoController";

    private final Context mContext;
    private final UserTracker mUserTracker;
    private final ArrayList<OnUserInfoChangedListener> mCallbacks =
            new ArrayList<OnUserInfoChangedListener>();
    private AsyncTask<Void, Void, UserInfoQueryResult> mUserInfoTask;

    private String mUserName;
    private Drawable mUserDrawable;
    private String mUserAccount;

    /**
     */
    @Inject
    public UserInfoControllerImpl(Context context, @Main Executor mainExecutor,
            UserTracker userTracker) {
        mContext = context;
        mUserTracker = userTracker;
        mUserTracker.addCallback(mUserChangedCallback, mainExecutor);

        IntentFilter profileFilter = new IntentFilter();
        profileFilter.addAction(ContactsContract.Intents.ACTION_PROFILE_CHANGED);
        profileFilter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        mContext.registerReceiverAsUser(mProfileReceiver, UserHandle.ALL, profileFilter,
                null, null, Context.RECEIVER_EXPORTED_UNAUDITED);
    }

    @Override
    public void addCallback(@NonNull OnUserInfoChangedListener callback) {
        mCallbacks.add(callback);
        callback.onUserInfoChanged(mUserName, mUserDrawable, mUserAccount);
    }

    @Override
    public void removeCallback(@NonNull OnUserInfoChangedListener callback) {
        mCallbacks.remove(callback);
    }

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    reloadUserInfo();
                }
            };

    private final BroadcastReceiver mProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ContactsContract.Intents.ACTION_PROFILE_CHANGED.equals(action) ||
                    Intent.ACTION_USER_INFO_CHANGED.equals(action)) {
                final int currentUser = mUserTracker.getUserId();
                final int changedUser =
                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId());
                if (changedUser == currentUser) {
                    reloadUserInfo();
                }
            }
        }
    };

    public void reloadUserInfo() {
        if (mUserInfoTask != null) {
            mUserInfoTask.cancel(false);
            mUserInfoTask = null;
        }
        queryForUserInformation();
    }

    private void queryForUserInformation() {
        Context currentUserContext;
        UserInfo userInfo;
        try {
            userInfo = mUserTracker.getUserInfo();
            currentUserContext = mContext.createPackageContextAsUser("android", 0,
                    new UserHandle(userInfo.id));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Couldn't create user context", e);
            throw new RuntimeException(e);
        }
        final int userId = userInfo.id;
        final boolean isGuest = userInfo.isGuest();
        final String userName = userInfo.name;
        final boolean lightIcon = mContext.getThemeResId() != R.style.Theme_SystemUI_LightWallpaper;

        final Resources res = mContext.getResources();
        final int avatarSize = Math.max(
                res.getDimensionPixelSize(R.dimen.multi_user_avatar_expanded_size),
                res.getDimensionPixelSize(R.dimen.multi_user_avatar_keyguard_size));

        final Context context = currentUserContext;
        mUserInfoTask = new AsyncTask<Void, Void, UserInfoQueryResult>() {

            @Override
            protected UserInfoQueryResult doInBackground(Void... params) {
                final UserManager um = UserManager.get(mContext);

                // Fall back to the UserManager nickname if we can't read the name from the local
                // profile below.
                String name = userName;
                Drawable avatar = null;
                Bitmap rawAvatar = um.getUserIcon(userId);
                if (rawAvatar != null) {
                    avatar = new UserIconDrawable(avatarSize)
                            .setIcon(rawAvatar).setBadgeIfManagedUser(mContext, userId).bake();
                } else {
                    avatar = UserIcons.getDefaultUserIcon(
                            context.getResources(),
                            isGuest? UserHandle.USER_NULL : userId,
                            lightIcon);
                }

                // If it's a single-user device, get the profile name, since the nickname is not
                // usually valid
                if (um.getUsers().size() <= 1) {
                    // Try and read the display name from the local profile
                    final Cursor cursor = context.getContentResolver().query(
                            ContactsContract.Profile.CONTENT_URI, new String[] {
                                    ContactsContract.CommonDataKinds.Phone._ID,
                                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                            }, null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                name = cursor.getString(cursor.getColumnIndex(
                                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                }
                String userAccount = um.getUserAccount(userId);
                return new UserInfoQueryResult(name, avatar, userAccount);
            }

            @Override
            protected void onPostExecute(UserInfoQueryResult result) {
                mUserName = result.getName();
                mUserDrawable = result.getAvatar();
                mUserAccount = result.getUserAccount();
                mUserInfoTask = null;
                notifyChanged();
            }
        };
        mUserInfoTask.execute();
    }

    private void notifyChanged() {
        for (OnUserInfoChangedListener listener : mCallbacks) {
            listener.onUserInfoChanged(mUserName, mUserDrawable, mUserAccount);
        }
    }

    public void onDensityOrFontScaleChanged() {
        reloadUserInfo();
    }

    private static class UserInfoQueryResult {
        private String mName;
        private Drawable mAvatar;
        private String mUserAccount;

        public UserInfoQueryResult(String name, Drawable avatar, String userAccount) {
            mName = name;
            mAvatar = avatar;
            mUserAccount = userAccount;
        }

        public String getName() {
            return mName;
        }

        public Drawable getAvatar() {
            return mAvatar;
        }

        public String getUserAccount() {
            return mUserAccount;
        }
    }
}
