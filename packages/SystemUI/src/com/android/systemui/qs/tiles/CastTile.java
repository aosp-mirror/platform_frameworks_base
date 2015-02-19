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
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.util.LinkedHashMap;
import java.util.Set;

/** Quick settings tile: Cast **/
public class CastTile extends QSTile<QSTile.BooleanState> {
    private static final Intent CAST_SETTINGS =
            new Intent(Settings.ACTION_CAST_SETTINGS);

    private final CastController mController;
    private final CastDetailAdapter mDetailAdapter;
    private final KeyguardMonitor mKeyguard;
    private final Callback mCallback = new Callback();

    public CastTile(Host host) {
        super(host);
        mController = host.getCastController();
        mDetailAdapter = new CastDetailAdapter();
        mKeyguard = host.getKeyguardMonitor();
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (mController == null) return;
        if (DEBUG) Log.d(TAG, "setListening " + listening);
        if (listening) {
            mController.addCallback(mCallback);
            mKeyguard.addCallback(mCallback);
        } else {
            mController.setDiscovering(false);
            mController.removeCallback(mCallback);
            mKeyguard.removeCallback(mCallback);
        }
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
        if (mController == null) return;
        mController.setCurrentUserId(newUserId);
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.visible = !(mKeyguard.isSecure() && mKeyguard.isShowing());
        state.label = mContext.getString(R.string.quick_settings_cast_title);
        state.value = false;
        state.autoMirrorDrawable = false;
        final Set<CastDevice> devices = mController.getCastDevices();
        boolean connecting = false;
        for (CastDevice device : devices) {
            if (device.state == CastDevice.STATE_CONNECTED) {
                state.value = true;
                state.label = getDeviceName(device);
            } else if (device.state == CastDevice.STATE_CONNECTING) {
                connecting = true;
            }
        }
        if (!state.value && connecting) {
            state.label = mContext.getString(R.string.quick_settings_connecting);
        }
        state.icon = ResourceIcon.get(state.value ? R.drawable.ic_qs_cast_on
                : R.drawable.ic_qs_cast_off);
        mDetailAdapter.updateItems(devices);
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (!mState.value) {
            // We only announce when it's turned off to avoid vocal overflow.
            return mContext.getString(R.string.accessibility_casting_turned_off);
        }
        return null;
    }

    private String getDeviceName(CastDevice device) {
        return device.name != null ? device.name
                : mContext.getString(R.string.quick_settings_cast_device_default_name);
    }

    private final class Callback implements CastController.Callback, KeyguardMonitor.Callback {
        @Override
        public void onCastDevicesChanged() {
            refreshState();
        }

        @Override
        public void onKeyguardChanged() {
            refreshState();
        }
    };

    private final class CastDetailAdapter implements DetailAdapter, QSDetailItems.Callback {
        private final LinkedHashMap<String, CastDevice> mVisibleOrder = new LinkedHashMap<>();

        private QSDetailItems mItems;

        @Override
        public int getTitle() {
            return R.string.quick_settings_cast_title;
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public Intent getSettingsIntent() {
            return CAST_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            // noop
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            mItems = QSDetailItems.convertOrInflate(context, convertView, parent);
            mItems.setTagSuffix("Cast");
            if (convertView == null) {
                if (DEBUG) Log.d(TAG, "addOnAttachStateChangeListener");
                mItems.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        if (DEBUG) Log.d(TAG, "onViewAttachedToWindow");
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        if (DEBUG) Log.d(TAG, "onViewDetachedFromWindow");
                        mVisibleOrder.clear();
                    }
                });
            }
            mItems.setEmptyState(R.drawable.ic_qs_cast_detail_empty,
                    R.string.quick_settings_cast_detail_empty_text);
            mItems.setCallback(this);
            updateItems(mController.getCastDevices());
            mController.setDiscovering(true);
            return mItems;
        }

        private void updateItems(Set<CastDevice> devices) {
            if (mItems == null) return;
            Item[] items = null;
            if (devices != null && !devices.isEmpty()) {
                // if we are connected, simply show that device
                for (CastDevice device : devices) {
                    if (device.state == CastDevice.STATE_CONNECTED) {
                        final Item item = new Item();
                        item.icon = R.drawable.ic_qs_cast_on;
                        item.line1 = getDeviceName(device);
                        item.line2 = mContext.getString(R.string.quick_settings_connected);
                        item.tag = device;
                        item.canDisconnect = true;
                        items = new Item[] { item };
                        break;
                    }
                }
                // otherwise list all available devices, and don't move them around
                if (items == null) {
                    for (CastDevice device : devices) {
                        mVisibleOrder.put(device.id, device);
                    }
                    items = new Item[devices.size()];
                    int i = 0;
                    for (String id : mVisibleOrder.keySet()) {
                        final CastDevice device = mVisibleOrder.get(id);
                        if (!devices.contains(device)) continue;
                        final Item item = new Item();
                        item.icon = R.drawable.ic_qs_cast_off;
                        item.line1 = getDeviceName(device);
                        if (device.state == CastDevice.STATE_CONNECTING) {
                            item.line2 = mContext.getString(R.string.quick_settings_connecting);
                        }
                        item.tag = device;
                        items[i++] = item;
                    }
                }
            }
            mItems.setItems(items);
        }

        @Override
        public void onDetailItemClick(Item item) {
            if (item == null || item.tag == null) return;
            final CastDevice device = (CastDevice) item.tag;
            mController.startCasting(device);
        }

        @Override
        public void onDetailItemDisconnect(Item item) {
            if (item == null || item.tag == null) return;
            final CastDevice device = (CastDevice) item.tag;
            mController.stopCasting(device);
        }
    }
}
