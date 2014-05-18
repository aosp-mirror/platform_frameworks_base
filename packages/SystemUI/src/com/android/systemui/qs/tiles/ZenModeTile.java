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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.ZenModeController;

/** Quick settings tile: Zen mode **/
public class ZenModeTile extends QSTile<QSTile.BooleanState> {
    private final ZenModeController mController;

    public ZenModeTile(Host host) {
        super(host);
        mController = host.getZenModeController();
    }

    @Override
    public View createDetailView(Context context, ViewGroup root) {
        final Context themedContext = new ContextThemeWrapper(mContext, R.style.QSAccentTheme);
        final ZenModeDetail v = (ZenModeDetail) LayoutInflater.from(themedContext)
                .inflate(R.layout.qs_zen_mode_detail, root, false);
        v.init(this);
        return v;
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addCallback(mCallback);
        } else {
            mController.removeCallback(mCallback);
        }
    }

    @Override
    protected void handleClick() {
        final boolean newZen = !mState.value;
        mController.setZen(newZen);
        if (newZen) {
            showDetail(true);
        }
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean zen = arg instanceof Boolean ? (Boolean)arg : mController.isZen();
        state.value = zen;
        state.visible = true;
        state.iconId = R.drawable.stat_sys_zen_limited;
        state.icon = mHost.getVectorDrawable(R.drawable.ic_qs_zen);
        state.label = mContext.getString(R.string.zen_mode_title);
    }

    private final ZenModeController.Callback mCallback = new ZenModeController.Callback() {
        @Override
        public void onZenChanged(boolean zen) {
            if (DEBUG) Log.d(TAG, "onZenChanged " + zen);
            refreshState(zen);
        }
    };
}
