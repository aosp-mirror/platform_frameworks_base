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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.AirplaneBooleanState;
import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.HotspotController;

/** Quick settings tile: Hotspot **/
public class HotspotTile extends QSTileImpl<AirplaneBooleanState> {
    static final Intent TETHER_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.TetherSettings"));

    private final Icon mEnabledStatic = ResourceIcon.get(R.drawable.ic_hotspot);
    private final Icon mUnavailable = ResourceIcon.get(R.drawable.ic_hotspot_unavailable);

    private final HotspotController mController;
    private final Callback mCallback = new Callback();
    private final GlobalSetting mAirplaneMode;
    private boolean mListening;

    public HotspotTile(QSHost host) {
        super(host);
        mController = Dependency.get(HotspotController.class);
        mAirplaneMode = new GlobalSetting(mContext, mHandler, Global.AIRPLANE_MODE_ON) {
            @Override
            protected void handleValueChanged(int value) {
                refreshState();
            }
        };
    }

    @Override
    public boolean isAvailable() {
        return mController.isHotspotSupported();
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
    }

    @Override
    public AirplaneBooleanState newTileState() {
        return new AirplaneBooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mController.addCallback(mCallback);
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            refreshState();
        } else {
            mController.removeCallback(mCallback);
        }
        mAirplaneMode.setListening(listening);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(TETHER_SETTINGS);
    }

    @Override
    protected void handleClick() {
        final boolean isEnabled = mState.value;
        if (!isEnabled && mAirplaneMode.getValue() != 0) {
            return;
        }
        // Immediately enter transient enabling state when turning hotspot on.
        refreshState(isEnabled ? null : ARG_SHOW_TRANSIENT_ENABLING);
        mController.setHotspotEnabled(!isEnabled);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_hotspot_label);
    }

    @Override
    protected void handleUpdateState(AirplaneBooleanState state, Object arg) {
        final boolean transientEnabling = arg == ARG_SHOW_TRANSIENT_ENABLING;
        if (state.slash == null) {
            state.slash = new SlashState();
        }

        final int numConnectedDevices;
        final boolean isTransient = transientEnabling || mController.isHotspotTransient();

        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_CONFIG_TETHERING);
        if (arg instanceof CallbackInfo) {
            CallbackInfo info = (CallbackInfo) arg;
            state.value = info.enabled;
            numConnectedDevices = info.numConnectedDevices;
        } else {
            state.value = transientEnabling || mController.isHotspotEnabled();
            numConnectedDevices = mController.getNumConnectedDevices();
        }

        state.icon = mEnabledStatic;
        state.label = mContext.getString(R.string.quick_settings_hotspot_label);
        state.secondaryLabel = getSecondaryLabel(state.value, isTransient, numConnectedDevices);
        state.isAirplaneMode = mAirplaneMode.getValue() != 0;
        state.isTransient = isTransient;
        state.slash.isSlashed = !state.value && !state.isTransient;
        if (state.isTransient) {
            state.icon = ResourceIcon.get(R.drawable.ic_hotspot_transient_animation);
        }
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription = state.label;
        state.state = state.isAirplaneMode ? Tile.STATE_UNAVAILABLE
                : state.value || state.isTransient ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Nullable
    private String getSecondaryLabel(
            boolean enabled, boolean isTransient, int numConnectedDevices) {
        if (isTransient) {
            return mContext.getString(R.string.quick_settings_hotspot_secondary_label_transient);
        } else if (numConnectedDevices > 0 && enabled) {
            return mContext.getResources().getQuantityString(
                    R.plurals.quick_settings_hotspot_secondary_label_num_devices,
                    numConnectedDevices,
                    numConnectedDevices);
        }

        return null;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_HOTSPOT;
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
        final CallbackInfo mCallbackInfo = new CallbackInfo();

        @Override
        public void onHotspotChanged(boolean enabled, int numConnectedDevices) {
            mCallbackInfo.enabled = enabled;
            mCallbackInfo.numConnectedDevices = numConnectedDevices;
            refreshState(mCallbackInfo);
        }
    }

    /**
     * Holder for any hotspot state info that needs to passed from the callback to
     * {@link #handleUpdateState(State, Object)}.
     */
    protected static final class CallbackInfo {
        boolean enabled;
        int numConnectedDevices;

        @Override
        public String toString() {
            return new StringBuilder("CallbackInfo[")
                    .append("enabled=").append(enabled)
                    .append(",numConnectedDevices=").append(numConnectedDevices)
                    .append(']').toString();
        }
    }
}
