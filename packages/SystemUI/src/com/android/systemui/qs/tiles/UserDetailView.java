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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settingslib.RestrictedLockUtils;
import com.android.systemui.R;
import com.android.systemui.qs.PseudoGridView;
import com.android.systemui.statusbar.policy.UserSwitcherController;
/**
 * Quick settings detail view for user switching.
 */
public class UserDetailView extends PseudoGridView {

    private Adapter mAdapter;

    public UserDetailView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public static UserDetailView inflate(Context context, ViewGroup parent, boolean attach) {
        return (UserDetailView) LayoutInflater.from(context).inflate(
                R.layout.qs_user_detail, parent, attach);
    }

    public void createAndSetAdapter(UserSwitcherController controller) {
        mAdapter = new Adapter(mContext, controller);
        ViewGroupAdapterBridge.link(this, mAdapter);
    }

    public void refreshAdapter() {
        mAdapter.refresh();
    }

    public static class Adapter extends UserSwitcherController.BaseUserAdapter
            implements OnClickListener {

        private final Context mContext;
        private final UserSwitcherController mController;

        public Adapter(Context context, UserSwitcherController controller) {
            super(controller);
            mContext = context;
            mController = controller;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            UserSwitcherController.UserRecord item = getItem(position);
            UserDetailItemView v = UserDetailItemView.convertOrInflate(
                    mContext, convertView, parent);
            if (v != convertView) {
                v.setOnClickListener(this);
            }
            String name = getName(mContext, item);
            if (item.picture == null) {
                v.bind(name, getDrawable(mContext, item), item.resolveId());
            } else {
                v.bind(name, item.picture, item.info.id);
            }
            v.setActivated(item.isCurrent);
            v.setDisabledByAdmin(item.isDisabledByAdmin);
            if (!item.isSwitchToEnabled) {
                v.setEnabled(false);
            }
            v.setTag(item);
            return v;
        }

        @Override
        public void onClick(View view) {
            UserSwitcherController.UserRecord tag =
                    (UserSwitcherController.UserRecord) view.getTag();
            if (tag.isDisabledByAdmin) {
                final Intent intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(
                        mContext, tag.enforcedAdmin);
                mController.startActivity(intent);
            } else if (tag.isSwitchToEnabled) {
                MetricsLogger.action(mContext, MetricsEvent.QS_SWITCH_USER);
                switchTo(tag);
            }
        }
    }
}
