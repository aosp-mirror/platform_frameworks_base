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

package com.android.settingslib.drawer;

import android.app.ActivityManager;

import android.content.Context;
import android.content.pm.UserInfo;
import android.database.DataSetObserver;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import com.android.internal.util.UserIcons;
import com.android.settingslib.drawable.UserIconDrawable;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for a spinner that shows a list of users.
 */
public class UserAdapter implements SpinnerAdapter, ListAdapter {
    /** Holder for user details */
    public static class UserDetails {
        private final UserHandle mUserHandle;
        private final String mName;
        private final Drawable mIcon;

        public UserDetails(UserHandle userHandle, UserManager um, Context context) {
            mUserHandle = userHandle;
            UserInfo userInfo = um.getUserInfo(mUserHandle.getIdentifier());
            Drawable icon;
            if (userInfo.isManagedProfile()) {
                mName = context.getString(R.string.managed_user_title);
                icon = context.getDrawable(
                    com.android.internal.R.drawable.ic_corp_badge);
            } else {
                mName = userInfo.name;
                final int userId = userInfo.id;
                if (um.getUserIcon(userId) != null) {
                    icon = new BitmapDrawable(context.getResources(), um.getUserIcon(userId));
                } else {
                    icon = UserIcons.getDefaultUserIcon(
                            context.getResources(), userId, /* light= */ false);
                }
            }
            this.mIcon = encircle(context, icon);
        }

        private static Drawable encircle(Context context, Drawable icon) {
            return new UserIconDrawable(UserIconDrawable.getSizeForList(context))
                    .setIconDrawable(icon).bake();
        }
    }
    private ArrayList<UserDetails> data;
    private final LayoutInflater mInflater;

    public UserAdapter(Context context, ArrayList<UserDetails> users) {
        if (users == null) {
            throw new IllegalArgumentException("A list of user details must be provided");
        }
        this.data = users;
        mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    public UserHandle getUserHandle(int position) {
        if (position < 0 || position >= data.size()) {
            return null;
        }
        return data.get(position).mUserHandle;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        final View row = convertView != null ? convertView : createUser(parent);

        UserDetails user = data.get(position);
        ((ImageView) row.findViewById(android.R.id.icon)).setImageDrawable(user.mIcon);
        ((TextView) row.findViewById(android.R.id.title)).setText(getTitle(user));
        return row;
    }

    private int getTitle(UserDetails user) {
        int userHandle = user.mUserHandle.getIdentifier();
        if (userHandle == UserHandle.USER_CURRENT
                || userHandle == ActivityManager.getCurrentUser()) {
            return R.string.category_personal;
        } else {
            return R.string.category_work;
        }
    }

    private View createUser(ViewGroup parent) {
        return mInflater.inflate(R.layout.user_preference, parent, false);
    }

    @Override
    public void registerDataSetObserver(DataSetObserver observer) {
        // We don't support observers
    }

    @Override
    public void unregisterDataSetObserver(DataSetObserver observer) {
        // We don't support observers
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public UserDetails getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return data.get(position).mUserHandle.getIdentifier();
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return getDropDownView(position, convertView, parent);
    }

    @Override
    public int getItemViewType(int position) {
        return 0;
    }

    @Override
    public int getViewTypeCount() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return data.isEmpty();
    }

    @Override
    public boolean areAllItemsEnabled() {
        return true;
    }

    @Override
    public boolean isEnabled(int position) {
        return true;
    }

    /**
     * Creates a {@link UserAdapter} if there is more than one profile on the device.
     *
     * <p> The adapter can be used to populate a spinner that switches between the Settings
     * app on the different profiles.
     *
     * @return a {@link UserAdapter} or null if there is only one profile.
     */
    public static UserAdapter createUserSpinnerAdapter(UserManager userManager,
            Context context) {
        List<UserHandle> userProfiles = userManager.getUserProfiles();
        if (userProfiles.size() < 2) {
            return null;
        }

        UserHandle myUserHandle = new UserHandle(UserHandle.myUserId());
        // The first option should be the current profile
        userProfiles.remove(myUserHandle);
        userProfiles.add(0, myUserHandle);

        return createUserAdapter(userManager, context, userProfiles);
    }

    public static UserAdapter createUserAdapter(UserManager userManager,
            Context context, List<UserHandle> userProfiles) {
        ArrayList<UserDetails> userDetails = new ArrayList<UserDetails>(userProfiles.size());
        final int count = userProfiles.size();
        for (int i = 0; i < count; i++) {
            userDetails.add(new UserDetails(userProfiles.get(i), userManager, context));
        }
        return new UserAdapter(context, userDetails);
    }
}
