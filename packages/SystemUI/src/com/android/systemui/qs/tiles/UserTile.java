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
 * limitations under the License.
 */
package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.Pair;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserSwitcherController;

import javax.inject.Inject;

public class UserTile extends QSTileImpl<State> implements UserInfoController.OnUserInfoChangedListener {

    private final UserSwitcherController mUserSwitcherController;
    private final UserInfoController mUserInfoController;
    private Pair<String, Drawable> mLastUpdate;

    @Inject
    public UserTile(QSHost host, UserSwitcherController userSwitcherController,
            UserInfoController userInfoController) {
        super(host);
        mUserSwitcherController = userSwitcherController;
        mUserInfoController = userInfoController;
        mUserInfoController.observe(getLifecycle(), this);
    }

    @Override
    public State newTileState() {
        return new QSTile.State();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_USER_SETTINGS);
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mUserSwitcherController.userDetailAdapter;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_USER_TILE;
    }

    @Override
    public CharSequence getTileLabel() {
        return getState().label;
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        final Pair<String, Drawable> p = arg != null ? (Pair<String, Drawable>) arg : mLastUpdate;
        if (p != null) {
            state.label = p.first;
            // TODO: Better content description.
            state.contentDescription = p.first;
            state.icon = new Icon() {
                @Override
                public Drawable getDrawable(Context context) {
                    return p.second;
                }
            };
        } else {
            // TODO: Default state.
        }
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        mLastUpdate = new Pair<>(name, picture);
        refreshState(mLastUpdate);
    }
}
