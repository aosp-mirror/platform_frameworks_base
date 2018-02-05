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

import android.content.Intent;
import android.os.UserManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.R.drawable;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationChangeCallback;

/** Quick settings tile: Location **/
public class LocationTile extends QSTileImpl<BooleanState> {

    private final Icon mIcon = ResourceIcon.get(drawable.ic_signal_location);

    private final LocationController mController;
    private final KeyguardMonitor mKeyguard;
    private final Callback mCallback = new Callback();

    public LocationTile(QSHost host) {
        super(host);
        mController = Dependency.get(LocationController.class);
        mKeyguard = Dependency.get(KeyguardMonitor.class);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) {
            mController.addCallback(mCallback);
            mKeyguard.addCallback(mCallback);
        } else {
            mController.removeCallback(mCallback);
            mKeyguard.removeCallback(mCallback);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    }

    @Override
    protected void handleClick() {
        if (mKeyguard.isSecure() && mKeyguard.isShowing()) {
            Dependency.get(ActivityStarter.class).postQSRunnableDismissingKeyguard(() -> {
                final boolean wasEnabled = mState.value;
                mHost.openPanels();
                mController.setLocationEnabled(!wasEnabled);
            });
            return;
        }
        final boolean wasEnabled = mState.value;
        mController.setLocationEnabled(!wasEnabled);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_location_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (state.slash == null) {
            state.slash = new SlashState();
        }
        final boolean locationEnabled =  mController.isLocationEnabled();

        // Work around for bug 15916487: don't show location tile on top of lock screen. After the
        // bug is fixed, this should be reverted to only hiding it on secure lock screens:
        // state.visible = !(mKeyguard.isSecure() && mKeyguard.isShowing());
        state.value = locationEnabled;
        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_SHARE_LOCATION);
        if (state.disabledByPolicy == false) {
            checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_CONFIG_LOCATION);
        }
        state.icon = mIcon;
        state.slash.isSlashed = !state.value;
        if (locationEnabled) {
            state.label = mContext.getString(R.string.quick_settings_location_label);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_location_on);
        } else {
            state.label = mContext.getString(R.string.quick_settings_location_label);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_location_off);
        }
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_LOCATION;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_location_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_location_changed_off);
        }
    }

    private final class Callback implements LocationChangeCallback,
            KeyguardMonitor.Callback {
        @Override
        public void onLocationSettingsChanged(boolean enabled) {
            refreshState();
        }

        @Override
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    };
}
