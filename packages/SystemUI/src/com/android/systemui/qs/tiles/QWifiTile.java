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
import com.android.systemui.QSQuickTileView;
import com.android.systemui.qs.QSTileBaseView;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.statusbar.policy.WifiIcons;

/** Quick settings tile: Wifi **/
public class QWifiTile extends WifiTile {

    public QWifiTile(Host host) {
        super(host, false);
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
    protected void handleUpdateState(SignalState state, Object arg) {
        super.handleUpdateState(state, arg);

        CallbackInfo cb = (CallbackInfo) arg;
        if (cb == null) {
            cb = mSignalCallback.mInfo;
        }
        boolean wifiConnected = cb.enabled && (cb.wifiSignalIconId > 0) && (cb.enabledDesc != null);

        if (state.enabled && wifiConnected) {
            // Only show full signal here.
            state.icon = ResourceIcon.get(WifiIcons.QS_WIFI_SIGNAL_STRENGTH[1][4]);
        }
        // No activity in the quick toggle.
        state.activityIn = false;
        state.activityOut = false;
    }
}
