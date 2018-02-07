/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.volume;

import static android.support.v7.media.MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED;
import static android.support.v7.media.MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING;
import static android.support.v7.media.MediaRouter.UNSELECT_REASON_DISCONNECTED;

import static com.android.settingslib.bluetooth.Utils.getBtClassDrawableWithDescription;

import android.app.Dialog;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.v7.media.MediaControlIntent;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.telecom.TelecomManager;
import android.util.Log;
import android.util.Pair;
import android.view.Window;
import android.view.WindowManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.Dependency;
import com.android.systemui.HardwareUiLayout;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.statusbar.policy.BluetoothController;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public class OutputChooserDialog extends Dialog
        implements DialogInterface.OnDismissListener, OutputChooserLayout.Callback {

    private static final String TAG = Util.logTag(OutputChooserDialog.class);
    private static final int MAX_DEVICES = 10;

    private static final long UPDATE_DELAY_MS = 300L;
    private static final int MSG_UPDATE_ITEMS = 1;

    private final Context mContext;
    private final BluetoothController mBluetoothController;
    private WifiManager mWifiManager;
    private OutputChooserLayout mView;
    private final MediaRouterWrapper mRouter;
    private final MediaRouterCallback mRouterCallback;
    private long mLastUpdateTime;
    static final boolean INCLUDE_MEDIA_ROUTES = false;
    private boolean mIsInCall;
    protected boolean isAttached;

    private final MediaRouteSelector mRouteSelector;
    private Drawable mDefaultIcon;
    private Drawable mTvIcon;
    private Drawable mSpeakerIcon;
    private Drawable mSpeakerGroupIcon;
    private HardwareUiLayout mHardwareLayout;
    private final VolumeDialogController mController;

    public OutputChooserDialog(Context context, MediaRouterWrapper router) {
        super(context, com.android.systemui.R.style.qs_theme);
        mContext = context;
        mBluetoothController = Dependency.get(BluetoothController.class);
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        mIsInCall = tm.isInCall();
        mRouter = router;
        mRouterCallback = new MediaRouterCallback();
        mRouteSelector = new MediaRouteSelector.Builder()
                .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .build();

        final IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mReceiver, filter);

        mController = Dependency.get(VolumeDialogController.class);

        // Window initialization
        Window window = getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
    }

    protected void setIsInCall(boolean inCall) {
        mIsInCall = inCall;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.output_chooser);
        setCanceledOnTouchOutside(true);
        setOnDismissListener(this::onDismiss);

        mView = findViewById(R.id.output_chooser);
        mHardwareLayout = HardwareUiLayout.get(mView);
        mHardwareLayout.setOutsideTouchListener(view -> dismiss());
        mHardwareLayout.setSwapOrientation(false);
        mView.setCallback(this);

        if (mIsInCall) {
            mView.setTitle(R.string.output_calls_title);
        } else {
            mView.setTitle(R.string.output_title);
        }

        mDefaultIcon = mContext.getDrawable(R.drawable.ic_cast);
        mTvIcon = mContext.getDrawable(R.drawable.ic_tv);
        mSpeakerIcon = mContext.getDrawable(R.drawable.ic_speaker);
        mSpeakerGroupIcon = mContext.getDrawable(R.drawable.ic_speaker_group);

        final boolean wifiOff = !mWifiManager.isWifiEnabled();
        final boolean btOff = !mBluetoothController.isBluetoothEnabled();
        if (wifiOff && btOff) {
            mView.setEmptyState(getDisabledServicesMessage(wifiOff, btOff));
        }
        // time out after 5 seconds
        mView.postDelayed(() -> updateItems(true), 5000);
    }

    protected void cleanUp() {}


    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mIsInCall && INCLUDE_MEDIA_ROUTES) {
            mRouter.addCallback(mRouteSelector, mRouterCallback,
                    MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
        }
        mBluetoothController.addCallback(mCallback);
        mController.addCallback(mControllerCallbackH, mHandler);
        isAttached = true;
    }

    @Override
    public void onDetachedFromWindow() {
        isAttached = false;
        mRouter.removeCallback(mRouterCallback);
        mController.removeCallback(mControllerCallbackH);
        mBluetoothController.removeCallback(mCallback);
        super.onDetachedFromWindow();
    }

    @Override
    public void onDismiss(DialogInterface unused) {
        mContext.unregisterReceiver(mReceiver);
        cleanUp();
    }

    @Override
    public void show() {
        super.show();
        Dependency.get(MetricsLogger.class).visible(MetricsProto.MetricsEvent.OUTPUT_CHOOSER);
        mHardwareLayout.setTranslationX(getAnimTranslation());
        mHardwareLayout.setAlpha(0);
        mHardwareLayout.animate()
                .alpha(1)
                .translationX(0)
                .setDuration(300)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .withEndAction(() -> getWindow().getDecorView().requestAccessibilityFocus())
                .start();
    }

    @Override
    public void dismiss() {
        Dependency.get(MetricsLogger.class).hidden(MetricsProto.MetricsEvent.OUTPUT_CHOOSER);
        mHardwareLayout.setTranslationX(0);
        mHardwareLayout.setAlpha(1);
        mHardwareLayout.animate()
                .alpha(0)
                .translationX(getAnimTranslation())
                .setDuration(300)
                .withEndAction(() -> super.dismiss())
                .setInterpolator(new SystemUIInterpolators.LogAccelerateInterpolator())
                .start();
    }

    private float getAnimTranslation() {
        return getContext().getResources().getDimension(
                com.android.systemui.R.dimen.output_chooser_panel_width) / 2;
    }

    @Override
    public void onDetailItemClick(OutputChooserLayout.Item item) {
        if (item == null || item.tag == null) return;
        if (item.deviceType == OutputChooserLayout.Item.DEVICE_TYPE_BT) {
            final CachedBluetoothDevice device = (CachedBluetoothDevice) item.tag;
            if (device.getMaxConnectionState() == BluetoothProfile.STATE_DISCONNECTED) {
                Dependency.get(MetricsLogger.class).action(
                        MetricsProto.MetricsEvent.ACTION_OUTPUT_CHOOSER_CONNECT);
                mBluetoothController.connect(device);
            }
        } else if (item.deviceType == OutputChooserLayout.Item.DEVICE_TYPE_MEDIA_ROUTER) {
            final MediaRouter.RouteInfo route = (MediaRouter.RouteInfo) item.tag;
            if (route.isEnabled()) {
                Dependency.get(MetricsLogger.class).action(
                        MetricsProto.MetricsEvent.ACTION_OUTPUT_CHOOSER_CONNECT);
                route.select();
            }
        }
    }

    @Override
    public void onDetailItemDisconnect(OutputChooserLayout.Item item) {
        if (item == null || item.tag == null) return;
        if (item.deviceType == OutputChooserLayout.Item.DEVICE_TYPE_BT) {
            final CachedBluetoothDevice device = (CachedBluetoothDevice) item.tag;
            Dependency.get(MetricsLogger.class).action(
                    MetricsProto.MetricsEvent.ACTION_OUTPUT_CHOOSER_DISCONNECT);
            mBluetoothController.disconnect(device);
        } else if (item.deviceType == OutputChooserLayout.Item.DEVICE_TYPE_MEDIA_ROUTER) {
            Dependency.get(MetricsLogger.class).action(
                    MetricsProto.MetricsEvent.ACTION_OUTPUT_CHOOSER_DISCONNECT);
            mRouter.unselect(UNSELECT_REASON_DISCONNECTED);
        }
    }

    private void updateItems(boolean timeout) {
        if (SystemClock.uptimeMillis() - mLastUpdateTime < UPDATE_DELAY_MS) {
            mHandler.removeMessages(MSG_UPDATE_ITEMS);
            mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_UPDATE_ITEMS, timeout),
                    mLastUpdateTime + UPDATE_DELAY_MS);
            return;
        }
        mLastUpdateTime = SystemClock.uptimeMillis();
        if (mView == null) return;
        ArrayList<OutputChooserLayout.Item> items = new ArrayList<>();

        // Add bluetooth devices
        addBluetoothDevices(items);

        // Add remote displays
        if (!mIsInCall && INCLUDE_MEDIA_ROUTES) {
            addRemoteDisplayRoutes(items);
        }

        items.sort(ItemComparator.sInstance);

        if (items.size() == 0 && timeout) {
            String emptyMessage = mContext.getString(R.string.output_none_found);
            final boolean wifiOff = !mWifiManager.isWifiEnabled();
            final boolean btOff = !mBluetoothController.isBluetoothEnabled();
            if (wifiOff || btOff) {
                emptyMessage = getDisabledServicesMessage(wifiOff, btOff);
            }
            mView.setEmptyState(emptyMessage);
        }

        mView.setItems(items.toArray(new OutputChooserLayout.Item[items.size()]));
    }

    private String getDisabledServicesMessage(boolean wifiOff, boolean btOff) {
        return mContext.getString(R.string.output_none_found_service_off,
                wifiOff && btOff ? mContext.getString(R.string.output_service_bt_wifi)
                        : wifiOff ? mContext.getString(R.string.output_service_wifi)
                                : mContext.getString(R.string.output_service_bt));
    }

    private void addBluetoothDevices(List<OutputChooserLayout.Item> items) {
        final Collection<CachedBluetoothDevice> devices = mBluetoothController.getDevices();
        if (devices != null) {
            int connectedDevices = 0;
            int count = 0;
            for (CachedBluetoothDevice device : devices) {
                if (mBluetoothController.getBondState(device) == BluetoothDevice.BOND_NONE) continue;
                final int majorClass = device.getBtClass().getMajorDeviceClass();
                if (majorClass != BluetoothClass.Device.Major.AUDIO_VIDEO
                        && majorClass != BluetoothClass.Device.Major.UNCATEGORIZED) {
                    continue;
                }
                final OutputChooserLayout.Item item = new OutputChooserLayout.Item();
                item.iconResId = R.drawable.ic_qs_bluetooth_on;
                item.line1 = device.getName();
                item.tag = device;
                item.deviceType = OutputChooserLayout.Item.DEVICE_TYPE_BT;
                int state = device.getMaxConnectionState();
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    item.iconResId = R.drawable.ic_qs_bluetooth_connected;
                    int batteryLevel = device.getBatteryLevel();
                    if (batteryLevel != BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
                        Pair<Drawable, String> pair =
                                getBtClassDrawableWithDescription(getContext(), device);
                        item.icon = pair.first;
                        item.line2 = mContext.getString(
                                R.string.quick_settings_connected_battery_level,
                                Utils.formatPercentage(batteryLevel));
                    } else {
                        item.line2 = mContext.getString(R.string.quick_settings_connected);
                    }
                    item.canDisconnect = true;
                    items.add(connectedDevices, item);
                    connectedDevices++;
                } else if (state == BluetoothProfile.STATE_CONNECTING) {
                    item.iconResId = R.drawable.ic_qs_bluetooth_connecting;
                    item.line2 = mContext.getString(R.string.quick_settings_connecting);
                    items.add(connectedDevices, item);
                } else {
                    items.add(item);
                }
                if (++count == MAX_DEVICES) {
                    break;
                }
            }
        }
    }

    private void addRemoteDisplayRoutes(List<OutputChooserLayout.Item> items) {
        List<MediaRouter.RouteInfo> routes = mRouter.getRoutes();
        for(MediaRouter.RouteInfo route : routes) {
            if (route.isDefaultOrBluetooth() || !route.isEnabled()
                    || !route.matchesSelector(mRouteSelector)) {
                continue;
            }
            final OutputChooserLayout.Item item = new OutputChooserLayout.Item();
            item.icon = getIconDrawable(route);
            item.line1 = route.getName();
            item.tag = route;
            item.deviceType = OutputChooserLayout.Item.DEVICE_TYPE_MEDIA_ROUTER;
            if (route.getConnectionState() == CONNECTION_STATE_CONNECTING) {
                mContext.getString(R.string.quick_settings_connecting);
            } else {
                item.line2 = route.getDescription();
            }

            if (route.getConnectionState() == CONNECTION_STATE_CONNECTED) {
                item.canDisconnect = true;
            }
            items.add(item);
        }
    }

    private Drawable getIconDrawable(MediaRouter.RouteInfo route) {
        Uri iconUri = route.getIconUri();
        if (iconUri != null) {
            try {
                InputStream is = getContext().getContentResolver().openInputStream(iconUri);
                Drawable drawable = Drawable.createFromStream(is, null);
                if (drawable != null) {
                    return drawable;
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to load " + iconUri, e);
                // Falls back.
            }
        }
        return getDefaultIconDrawable(route);
    }

    private Drawable getDefaultIconDrawable(MediaRouter.RouteInfo route) {
        // If the type of the receiver device is specified, use it.
        switch (route.getDeviceType()) {
            case  MediaRouter.RouteInfo.DEVICE_TYPE_TV:
                return mTvIcon;
            case MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER:
                return mSpeakerIcon;
        }

        // Otherwise, make the best guess based on other route information.
        if (route instanceof MediaRouter.RouteGroup) {
            // Only speakers can be grouped for now.
            return mSpeakerGroupIcon;
        }
        return mDefaultIcon;
    }

    private final class MediaRouterCallback extends MediaRouter.Callback {
        @Override
        public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo info) {
            updateItems(false);
        }

        @Override
        public void onRouteRemoved(MediaRouter router, MediaRouter.RouteInfo info) {
            updateItems(false);
        }

        @Override
        public void onRouteChanged(MediaRouter router, MediaRouter.RouteInfo info) {
            updateItems(false);
        }

        @Override
        public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo route) {
            updateItems(false);
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                if (D.BUG) Log.d(TAG, "Received ACTION_CLOSE_SYSTEM_DIALOGS");
                cancel();
                cleanUp();
            }
        }
    };

    private final BluetoothController.Callback mCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled) {
            updateItems(false);
        }

        @Override
        public void onBluetoothDevicesChanged() {
            updateItems(false);
        }
    };

    static final class ItemComparator implements Comparator<OutputChooserLayout.Item> {
        public static final ItemComparator sInstance = new ItemComparator();

        @Override
        public int compare(OutputChooserLayout.Item lhs, OutputChooserLayout.Item rhs) {
            // Connected item(s) first
            if (lhs.canDisconnect != rhs.canDisconnect) {
                return Boolean.compare(rhs.canDisconnect, lhs.canDisconnect);
            }
            // Bluetooth items before media routes
            if (lhs.deviceType != rhs.deviceType) {
                return Integer.compare(lhs.deviceType, rhs.deviceType);
            }
            // then by name
            return lhs.line1.toString().compareToIgnoreCase(rhs.line1.toString());
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_UPDATE_ITEMS:
                    updateItems((Boolean) message.obj);
                    break;
            }
        }
    };

    private final VolumeDialogController.Callbacks mControllerCallbackH
            = new VolumeDialogController.Callbacks() {
        @Override
        public void onShowRequested(int reason) {
            dismiss();
        }

        @Override
        public void onDismissRequested(int reason) {}

        @Override
        public void onScreenOff() {
            dismiss();
        }

        @Override
        public void onStateChanged(VolumeDialogController.State state) {}

        @Override
        public void onLayoutDirectionChanged(int layoutDirection) {}

        @Override
        public void onConfigurationChanged() {}

        @Override
        public void onShowVibrateHint() {}

        @Override
        public void onShowSilentHint() {}

        @Override
        public void onShowSafetyWarning(int flags) {}

        @Override
        public void onAccessibilityModeChanged(Boolean showA11yStream) {}

        @Override
        public void onConnectedDeviceChanged(String deviceName) {}
    };
}