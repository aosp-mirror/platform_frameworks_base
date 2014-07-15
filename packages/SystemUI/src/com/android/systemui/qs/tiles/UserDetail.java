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
import com.android.systemui.qs.QSTile;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Quick settings detail view for user switching.
 */
public class UserDetail extends FrameLayout {

    static final Intent USER_SETTINGS_INTENT = new Intent("android.settings.USER_SETTINGS");

    public UserDetail(Context context) {
        this(context, null);
    }

    public UserDetail(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UserDetail(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public UserDetail(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public static QSTile.DetailAdapter USER_DETAIL_ADAPTER = new QSTile.DetailAdapter() {
        @Override
        public int getTitle() {
            return R.string.quick_settings_user_title;
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            return LayoutInflater.from(context).inflate(R.layout.qs_user_detail, parent, false);
        }

        @Override
        public Intent getSettingsIntent() {
            return USER_SETTINGS_INTENT;
        }

        @Override
        public void setToggleState(boolean state) {
        }
    };
}
