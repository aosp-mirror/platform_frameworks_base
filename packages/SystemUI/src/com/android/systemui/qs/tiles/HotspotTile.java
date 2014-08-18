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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.systemui.R;
import com.android.systemui.qs.UsageTracker;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.HotspotController;

/** Quick settings tile: Hotspot **/
public class HotspotTile extends QSTile<QSTile.BooleanState> {
    private final HotspotController mController;
    private final Callback mCallback = new Callback();
    private final UsageTracker mUsageTracker;

    public HotspotTile(Host host) {
        super(host);
        mController = host.getHotspotController();
        mUsageTracker = new UsageTracker(host.getContext(), HotspotTile.class);
        mUsageTracker.setListening(true);
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mUsageTracker.setListening(false);
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
        final boolean isEnabled = (Boolean) mState.value;
        mController.setHotspotEnabled(!isEnabled);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.visible = mController.isHotspotSupported() && mUsageTracker.isRecentlyUsed();
        state.label = mContext.getString(R.string.quick_settings_hotspot_label);

        state.value = mController.isHotspotEnabled();
        state.iconId = state.visible && state.value ? R.drawable.ic_qs_hotspot_on
                : R.drawable.ic_qs_hotspot_off;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_hotspot_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_hotspot_changed_off);
        }
    }

    private final class Callback implements HotspotController.Callback {
        @Override
        public void onHotspotChanged(boolean enabled) {
            refreshState();
        }
    };

    /**
     * This will catch broadcasts for changes in hotspot state so we can show
     * the hotspot tile for a number of days after use.
     */
    public static class APChangedReceiver extends BroadcastReceiver {
        private UsageTracker mUsageTracker;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (mUsageTracker == null) {
                mUsageTracker = new UsageTracker(context, HotspotTile.class);
            }
            mUsageTracker.trackUsage();
        }
    }
}
