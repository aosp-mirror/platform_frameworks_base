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

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.UserSwitcherController;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;

/**
 * Quick settings detail view for user switching.
 */
public class UserDetailView extends GridView {

    public UserDetailView(Context context) {
        this(context, null);
    }

    public UserDetailView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.gridViewStyle);
    }

    public UserDetailView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public UserDetailView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                UserSwitcherController.UserRecord tag =
                        (UserSwitcherController.UserRecord) view.getTag();
                ((Adapter)getAdapter()).switchTo(tag);
            }
        });
    }

    public static UserDetailView inflate(Context context, ViewGroup parent, boolean attach) {
        return (UserDetailView) LayoutInflater.from(context).inflate(
                R.layout.qs_user_detail, parent, attach);
    }

    public void createAndSetAdapter(UserSwitcherController controller) {
        setAdapter(new Adapter(mContext, controller));
    }

    public static class Adapter extends UserSwitcherController.BaseUserAdapter {

        private Context mContext;

        public Adapter(Context context, UserSwitcherController controller) {
            super(controller);
            mContext = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            UserSwitcherController.UserRecord item = getItem(position);
            UserDetailItemView v = UserDetailItemView.convertOrInflate(
                    mContext, convertView, parent);
            String name;
            if (item.isGuest) {
                name = mContext.getString(
                        item.info == null ? R.string.guest_new_guest : R.string.guest_nickname);
            } else {
                name = item.info.name;
            }
            if (item.picture == null) {
                v.bind(name, mContext.getDrawable(R.drawable.ic_account_circle_qs));
            } else {
                v.bind(name, item.picture);
            }
            v.setActivated(item.isCurrent);
            v.setTag(item);
            return v;
        }
    }
}
