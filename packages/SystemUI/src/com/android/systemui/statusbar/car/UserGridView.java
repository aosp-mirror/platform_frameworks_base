/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.Context;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.UserUtil;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.policy.UserSwitcherController;

public class UserGridView extends GridView {

    private PhoneStatusBar mStatusBar;
    private UserSwitcherController mUserSwitcherController;
    private Adapter mAdapter;
    private int mPendingUserId = UserHandle.USER_NULL;

    public UserGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void init(PhoneStatusBar statusBar, UserSwitcherController userSwitcherController) {
        mStatusBar = statusBar;
        mUserSwitcherController = userSwitcherController;
        mAdapter = new Adapter(mUserSwitcherController);
        setAdapter(mAdapter);

        setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                mPendingUserId = UserHandle.USER_NULL;
                UserSwitcherController.UserRecord record = mAdapter.getItem(position);
                if (record == null) {
                    return;
                }

                if (record.isGuest || record.isAddUser) {
                    mUserSwitcherController.switchTo(record);
                    return;
                }

                if (record.isCurrent) {
                    showOfflineAuthUi();
                } else {
                    mPendingUserId = record.info.id;
                    mUserSwitcherController.switchTo(record);
                }
            }
        });

        setOnItemLongClickListener(new OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent,
                    View view, int position, long id) {
                UserSwitcherController.UserRecord record = mAdapter.getItem(position);
                if (record == null || record.isAddUser) {
                    return false;
                }
                if (record.isGuest) {
                    if (record.isCurrent) {
                        mUserSwitcherController.switchTo(record);
                    }
                    return true;
                }

                UserUtil.deleteUserWithPrompt(getContext(), record.info.id,
                        mUserSwitcherController);
                return true;
            }
        });
    }

    public void onUserSwitched(int newUserId) {
        if (mPendingUserId == newUserId) {
            // Bring up security view after user switch is completed.
            post(new Runnable() {
                @Override
                public void run() {
                    showOfflineAuthUi();
                }
            });
        }
        mPendingUserId = UserHandle.USER_NULL;
    }

    private void showOfflineAuthUi() {
        // TODO: Show keyguard UI in-place.
        mStatusBar.executeRunnableDismissingKeyguard(null, null, true, true, true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            setNumColumns(AUTO_FIT);
        } else {
            int columnWidth = Math.max(1, getRequestedColumnWidth());
            int itemCount = getAdapter() == null ? 0 : getAdapter().getCount();
            int numColumns = Math.max(1, Math.min(itemCount, widthSize / columnWidth));
            setNumColumns(numColumns);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private final class Adapter extends UserSwitcherController.BaseUserAdapter {
        public Adapter(UserSwitcherController controller) {
            super(controller);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                LayoutInflater inflater = (LayoutInflater)getContext().getSystemService
                        (Context.LAYOUT_INFLATER_SERVICE);
                convertView = inflater.inflate(R.layout.car_fullscreen_user_pod, null);
            }
            UserSwitcherController.UserRecord record = getItem(position);

            TextView nameView = (TextView) convertView.findViewById(R.id.user_name);
            if (record != null) {
                nameView.setText(getName(getContext(), record));
                convertView.setActivated(record.isCurrent);
            } else {
                nameView.setText("Unknown");
            }

            ImageView iconView = (ImageView) convertView.findViewById(R.id.user_avatar);
            if (record == null || record.picture == null) {
                iconView.setImageDrawable(getDrawable(getContext(), record));
            } else {
                iconView.setImageBitmap(record.picture);
            }

            return convertView;
        }
    }
}
