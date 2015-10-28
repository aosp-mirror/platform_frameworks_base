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
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.QSQuickTileView;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileBaseView;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

public class QLockTile extends QSTile<QSTile.State> implements KeyguardMonitor.Callback {

    private final KeyguardMonitor mKeyguard;

    public QLockTile(Host host) {
        super(host);
        mKeyguard = host.getKeyguardMonitor();
    }

    @Override
    public QSTileBaseView createTileView(Context context) {
        return new QSQuickTileView(context);
    }

    @Override
    public int getTileType() {
        return QSTileView.QS_TYPE_QUICK;
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mKeyguard.addCallback(this);
        } else {
            mKeyguard.removeCallback(this);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.QS_LOCK_TILE;
    }

    @Override
    public void onKeyguardChanged() {
        refreshState();
    }

    @Override
    protected void handleClick() {
        if (mKeyguard.isShowing()) {
            mKeyguard.unlock();
        } else {
            mKeyguard.lock();
        }
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        // TOD: Content description.
        state.visible = true;
        if (mKeyguard.isShowing()) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_lock);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_lock_open);
        }
    }
}
