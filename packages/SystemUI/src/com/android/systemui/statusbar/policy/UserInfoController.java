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

import android.app.ActivityManagerNative;
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
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.UserIcons;
import com.android.settingslib.drawable.UserIconDrawable;
import com.android.systemui.R;

import java.util.ArrayList;

public final class UserInfoController {

    private static final String TAG = "UserInfoController";

    private final Context mContext;
    private final ArrayList<OnUserInfoChangedListener> mCallbacks =
            new ArrayList<OnUserInfoChangedListener>();
    private AsyncTask<Void, Void, Pair<String, Drawable>> mUserInfoTask;

    private String mUserName;
    private Drawable mUserDrawable;

    public UserInfoController(Context context) {
        mContext = context;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mReceiver, filter);

        IntentFilter profileFilter = new IntentFilter();
        profileFilter.addAction(ContactsContract.Intents.ACTION_PROFILE_CHANGED);
        profileFilter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        mContext.registerReceiverAsUser(mProfileReceiver, UserHandle.ALL, profileFilter,
                null, null);
    }

    public void addListener(OnUserInfoChangedListener callback) {
        mCallbacks.add(callback);
        callback.onUserInfoChanged(mUserName, mUserDrawable);
    }

    public void remListener(OnUserInfoChangedListener callback) {
        mCallbacks.remove(callback);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                reloadUserInfo();
            }
        }
    };

    private final BroadcastReceiver mProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ContactsContract.Intents.ACTION_PROFILE_CHANGED.equals(action) ||
                    Intent.ACTION_USER_INFO_CHANGED.equals(action)) {
                try {
                    final int currentUser = ActivityManagerNative.getDefault().getCurrentUser().id;
                    final int changedUser =
                            intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId());
                    if (changedUser == currentUser) {
                        reloadUserInfo();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Couldn't get current user id for profile change", e);
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
            userInfo = ActivityManagerNative.getDefault().getCurrentUser();
            currentUserContext = mContext.createPackageContextAsUser("android", 0,
                    new UserHandle(userInfo.id));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Couldn't create user context", e);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't get user info", e);
            throw new RuntimeException(e);
        }
        final int userId = userInfo.id;
        final boolean isGuest = userInfo.isGuest();
        final String userName = userInfo.name;

        final Resources res = mContext.getResources();
        final int avatarSize = Math.max(
                res.getDimensionPixelSize(R.dimen.multi_user_avatar_expanded_size),
                res.getDimensionPixelSize(R.dimen.multi_user_avatar_keyguard_size));

        final Context context = currentUserContext;
        mUserInfoTask = new AsyncTask<Void, Void, Pair<String, Drawable>>() {
            @Override
            protected Pair<String, Drawable> doInBackground(Void... params) {
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
                    avatar = UserIcons.getDefaultUserIcon(isGuest? UserHandle.USER_NULL : userId,
                            /* light= */ true);
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
                return new Pair<String, Drawable>(name, avatar);
            }

            @Override
            protected void onPostExecute(Pair<String, Drawable> result) {
                mUserName = result.first;
                mUserDrawable = result.second;
                mUserInfoTask = null;
                notifyChanged();
            }
        };
        mUserInfoTask.execute();
    }

    private void notifyChanged() {
        for (OnUserInfoChangedListener listener : mCallbacks) {
            listener.onUserInfoChanged(mUserName, mUserDrawable);
        }
    }

    public void onDensityOrFontScaleChanged() {
        reloadUserInfo();
    }

    public interface OnUserInfoChangedListener {
        public void onUserInfoChanged(String name, Drawable picture);
    }
}
