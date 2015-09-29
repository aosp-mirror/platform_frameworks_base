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
import android.graphics.drawable.Drawable;
import android.util.Pair;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserSwitcherController;

public class UserTile extends QSTile<QSTile.State> implements UserInfoController.OnUserInfoChangedListener {

    private final UserSwitcherController mUserSwitcherController;
    private final UserInfoController mUserInfoController;
    private Pair<String, Drawable> mLastUpdate;

    public UserTile(Host host) {
        super(host);
        mUserSwitcherController = host.getUserSwitcherController();
        mUserInfoController = host.getUserInfoController();
    }

    @Override
    protected State newTileState() {
        return new QSTile.State();
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
        return MetricsLogger.QS_USER_TILE;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mUserInfoController.addListener(this);
        } else {
            mUserInfoController.remListener(this);
        }
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        final Pair<String, Drawable> p = arg != null ? (Pair<String, Drawable>) arg : mLastUpdate;
        state.visible = p != null;
        if (!state.visible) return;
        state.label = p.first;
        // TODO: Better content description.
        state.contentDescription = p.first;
        state.icon = new Icon() {
            @Override
            public Drawable getDrawable(Context context) {
                return p.second;
            }
        };
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture) {
        mLastUpdate = new Pair<>(name, picture);
        refreshState(mLastUpdate);
    }
}
