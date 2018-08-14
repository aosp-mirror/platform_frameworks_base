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

import static android.media.MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;

import com.android.internal.app.MediaRouteDialogPresenter;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSDetailItems;
import com.android.systemui.qs.QSDetailItems.Item;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.util.LinkedHashMap;
import java.util.Set;

/** Quick settings tile: Cast **/
public class CastTile extends QSTileImpl<BooleanState> {
    private static final Intent CAST_SETTINGS =
            new Intent(Settings.ACTION_CAST_SETTINGS);

    private final CastController mController;
    private final CastDetailAdapter mDetailAdapter;
    private final KeyguardMonitor mKeyguard;
    private final Callback mCallback = new Callback();
    private final ActivityStarter mActivityStarter;
    private Dialog mDialog;
    private boolean mRegistered;

    public CastTile(QSHost host) {
        super(host);
        mController = Dependency.get(CastController.class);
        mDetailAdapter = new CastDetailAdapter();
        mKeyguard = Dependency.get(KeyguardMonitor.class);
        mActivityStarter = Dependency.get(ActivityStarter.class);
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (DEBUG) Log.d(TAG, "handleSetListening " + listening);
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
        mController.setCurrentUserId(newUserId);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_CAST_SETTINGS);
    }

    @Override
    protected void handleSecondaryClick() {
        handleClick();
    }

    @Override
    protected void handleClick() {
        if (mKeyguard.isSecure() && !mKeyguard.canSkipBouncer()) {
            mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                showDetail(true);
            });
            return;
        }
        showDetail(true);
    }

    @Override
    public void showDetail(boolean show) {
        mUiHandler.post(() -> {
            mDialog = MediaRouteDialogPresenter.createDialog(mContext, ROUTE_TYPE_REMOTE_DISPLAY,
                    v -> {
                        mDialog.dismiss();
                        Dependency.get(ActivityStarter.class)
                                .postStartActivityDismissingKeyguard(getLongClickIntent(), 0);
                    });
            mDialog.getWindow().setType(LayoutParams.TYPE_KEYGUARD_DIALOG);
            SystemUIDialog.setShowForAllUsers(mDialog, true);
            SystemUIDialog.registerDismissListener(mDialog);
            SystemUIDialog.setWindowOnTop(mDialog);
            mUiHandler.post(() -> mDialog.show());
            mHost.collapsePanels();
        });
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_cast_title);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.quick_settings_cast_title);
        state.contentDescription = state.label;
        state.value = false;
        final Set<CastDevice> devices = mController.getCastDevices();
        boolean connecting = false;
        for (CastDevice device : devices) {
            if (device.state == CastDevice.STATE_CONNECTED) {
                state.value = true;
                state.label = getDeviceName(device);
                state.contentDescription = state.contentDescription + "," +
                        mContext.getString(R.string.accessibility_cast_name, state.label);
            } else if (device.state == CastDevice.STATE_CONNECTING) {
                connecting = true;
            }
        }
        if (!state.value && connecting) {
            state.label = mContext.getString(R.string.quick_settings_connecting);
        }
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = ResourceIcon.get(state.value ? R.drawable.ic_qs_cast_on
                : R.drawable.ic_qs_cast_off);
        mDetailAdapter.updateItems(devices);
        state.expandedAccessibilityClassName = Button.class.getName();
        state.contentDescription = state.contentDescription + ","
                + mContext.getString(R.string.accessibility_quick_settings_open_details);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_CAST;
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
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    };

    private final class CastDetailAdapter implements DetailAdapter, QSDetailItems.Callback {
        private final LinkedHashMap<String, CastDevice> mVisibleOrder = new LinkedHashMap<>();

        private QSDetailItems mItems;

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.quick_settings_cast_title);
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
        public int getMetricsCategory() {
            return MetricsEvent.QS_CAST_DETAILS;
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
                        item.iconResId = R.drawable.ic_qs_cast_on;
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
                        item.iconResId = R.drawable.ic_qs_cast_off;
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
            MetricsLogger.action(mContext, MetricsEvent.QS_CAST_SELECT);
            final CastDevice device = (CastDevice) item.tag;
            mController.startCasting(device);
        }

        @Override
        public void onDetailItemDisconnect(Item item) {
            if (item == null || item.tag == null) return;
            MetricsLogger.action(mContext, MetricsEvent.QS_CAST_DISCONNECT);
            final CastDevice device = (CastDevice) item.tag;
            mController.stopCasting(device);
        }
    }
}
