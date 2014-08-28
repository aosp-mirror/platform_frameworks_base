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

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;

/** Quick settings tile: Location **/
public class LocationTile extends QSTile<QSTile.BooleanState> {

    private final LocationController mController;
    private final KeyguardMonitor mKeyguard;
    private final Callback mCallback = new Callback();

    public LocationTile(Host host) {
        super(host);
        mController = host.getLocationController();
        mKeyguard = host.getKeyguardMonitor();
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addSettingsChangedCallback(mCallback);
            mKeyguard.addCallback(mCallback);
        } else {
            mController.removeSettingsChangedCallback(mCallback);
            mKeyguard.removeCallback(mCallback);
        }
    }

    @Override
    protected void handleClick() {
        final boolean wasEnabled = (Boolean) mState.value;
        mController.setLocationEnabled(!wasEnabled);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean locationEnabled =  mController.isLocationEnabled();
        state.visible = !(mKeyguard.isSecure() && mKeyguard.isShowing());
        state.value = locationEnabled;
        if (locationEnabled) {
            state.iconId = R.drawable.ic_qs_location_on;
            state.label = mContext.getString(R.string.quick_settings_location_label);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_location_on);
        } else {
            state.iconId = R.drawable.ic_qs_location_off;
            state.label = mContext.getString(R.string.quick_settings_location_label);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_location_off);
        }
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_location_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_location_changed_off);
        }
    }

    private final class Callback implements LocationSettingsChangeCallback,
            KeyguardMonitor.Callback {
        @Override
        public void onLocationSettingsChanged(boolean enabled) {
            refreshState();
        }

        @Override
        public void onKeyguardChanged() {
            refreshState();
        }
    };
}
