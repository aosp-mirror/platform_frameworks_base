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

import android.content.res.Resources;
import android.graphics.drawable.AnimationDrawable;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;

/** Quick settings tile: Location **/
public class LocationTile extends QSTile<QSTile.BooleanState> {

    private final LocationController mController;

    public LocationTile(Host host) {
        super(host);
        mController = host.getLocationController();
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addSettingsChangedCallback(mCallback);
        } else {
            mController.removeSettingsChangedCallback(mCallback);
        }
    }

    @Override
    protected void handleClick() {
        final boolean wasEnabled = (Boolean) mState.value;
        final boolean changed = mController.setLocationEnabled(!wasEnabled);
        if (!wasEnabled && changed) {
            // If we've successfully switched from location off to on, close the
            // notifications tray to show the network location provider consent dialog.
            mHost.collapsePanels();
        }
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean locationEnabled =  mController.isLocationEnabled();
        state.visible = true;
        if (state.value != locationEnabled) {
            state.value = locationEnabled;
            final Resources res = mContext.getResources();
            final AnimationDrawable d = (AnimationDrawable) res.getDrawable(locationEnabled
                    ? R.drawable.ic_qs_location_on
                    : R.drawable.ic_qs_location_off);
            state.icon = d;
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    d.start();
                }
            });
        }
        if (locationEnabled) {
            if (state.icon == null) state.iconId = R.drawable.ic_qs_location_01;
            state.label = mContext.getString(R.string.quick_settings_location_label);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_location,
                    mContext.getString(R.string.accessibility_desc_on));
        } else {
            if (state.icon == null) state.iconId = R.drawable.ic_qs_location_11;
            state.label = mContext.getString(R.string.quick_settings_location_off_label);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_location,
                    mContext.getString(R.string.accessibility_desc_off));
        }
    }

    private final LocationSettingsChangeCallback mCallback = new LocationSettingsChangeCallback() {
        @Override
        public void onLocationSettingsChanged(boolean enabled) {
            refreshState();
        }
    };
}
