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

import android.content.Context;
import android.database.DataSetObserver;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.UserAvatarView;

/**
 * Manages the user switcher on the Keyguard.
 */
public class KeyguardUserSwitcher {

    private static final String TAG = "KeyguardUserSwitcher";
    private static final boolean ALWAYS_ON = false;

    private final ViewGroup mUserSwitcher;
    private final KeyguardStatusBarView mStatusBarView;
    private final Adapter mAdapter;
    private final boolean mSimpleUserSwitcher;

    public KeyguardUserSwitcher(Context context, ViewStub userSwitcher,
            KeyguardStatusBarView statusBarView, UserSwitcherController userSwitcherController) {
        if (context.getResources().getBoolean(R.bool.config_keyguardUserSwitcher) || ALWAYS_ON) {
            mUserSwitcher = (ViewGroup) userSwitcher.inflate();
            mStatusBarView = statusBarView;
            mStatusBarView.setKeyguardUserSwitcher(this);
            mAdapter = new Adapter(context, userSwitcherController);
            mAdapter.registerDataSetObserver(mDataSetObserver);
            mSimpleUserSwitcher = userSwitcherController.isSimpleUserSwitcher();
        } else {
            mUserSwitcher = null;
            mStatusBarView = null;
            mAdapter = null;
            mSimpleUserSwitcher = false;
        }
    }

    public void setKeyguard(boolean keyguard) {
        if (mUserSwitcher != null) {
            if (keyguard && shouldExpandByDefault()) {
                show();
            } else {
                hide();
            }
        }
    }

    /**
     * @return true if the user switcher should be expanded by default on the lock screen.
     * @see android.os.UserManager#isUserSwitcherEnabled()
     */
    private boolean shouldExpandByDefault() {
        return mSimpleUserSwitcher || mAdapter.getSwitchableUsers() > 1;
    }

    public void show() {
        if (mUserSwitcher != null) {
            // TODO: animate
            mUserSwitcher.setVisibility(View.VISIBLE);
            mStatusBarView.setKeyguardUserSwitcherShowing(true);
        }
    }

    public void hide() {
        if (mUserSwitcher != null) {
            // TODO: animate
            mUserSwitcher.setVisibility(View.GONE);
            mStatusBarView.setKeyguardUserSwitcherShowing(false);
        }
    }

    private void refresh() {
        final int childCount = mUserSwitcher.getChildCount();
        final int adapterCount = mAdapter.getCount();
        final int N = Math.max(childCount, adapterCount);
        for (int i = 0; i < N; i++) {
            if (i < adapterCount) {
                View oldView = null;
                if (i < childCount) {
                    oldView = mUserSwitcher.getChildAt(i);
                }
                View newView = mAdapter.getView(i, oldView, mUserSwitcher);
                if (oldView == null) {
                    // We ran out of existing views. Add it at the end.
                    mUserSwitcher.addView(newView);
                } else if (oldView != newView) {
                    // We couldn't rebind the view. Replace it.
                    mUserSwitcher.removeViewAt(i);
                    mUserSwitcher.addView(newView, i);
                }
            } else {
                int lastIndex = mUserSwitcher.getChildCount() - 1;
                mUserSwitcher.removeViewAt(lastIndex);
            }
        }
    }

    public final DataSetObserver mDataSetObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            refresh();
        }
    };

    public static class Adapter extends UserSwitcherController.BaseUserAdapter implements
            View.OnClickListener {

        private Context mContext;

        public Adapter(Context context, UserSwitcherController controller) {
            super(controller);
            mContext = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            UserSwitcherController.UserRecord item = getItem(position);

            if (convertView == null
                    || !(convertView.getTag() instanceof UserSwitcherController.UserRecord)) {
                convertView = LayoutInflater.from(mContext).inflate(
                        R.layout.keyguard_user_switcher_item, parent, false);
                convertView.setOnClickListener(this);
            }

            TextView nameView = (TextView) convertView.findViewById(R.id.name);
            UserAvatarView pictureView = (UserAvatarView) convertView.findViewById(R.id.picture);

            nameView.setText(getName(mContext, item));
            if (item.picture == null) {
                pictureView.setDrawable(mContext.getDrawable(R.drawable.ic_account_circle_qs));
            } else {
                pictureView.setBitmap(item.picture);
            }
            convertView.setActivated(item.isCurrent);
            convertView.setTag(item);
            return convertView;
        }

        @Override
        public void onClick(View v) {
            switchTo(((UserSwitcherController.UserRecord)v.getTag()));
        }
    }
}
