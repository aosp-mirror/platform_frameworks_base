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

import static com.android.systemui.flags.Flags.SIGNAL_CALLBACK_DEPRECATION;

import android.annotation.NonNull;
import android.app.Dialog;
import android.content.Intent;
import android.media.MediaRouter.RouteInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.annotation.Nullable;

import com.android.internal.app.MediaRouteDialogPresenter;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.WifiIndicators;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel;
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;

/** Quick settings tile: Cast **/
public class CastTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "cast";

    private static final String INTERACTION_JANK_TAG = TILE_SPEC;

    private static final Intent CAST_SETTINGS =
            new Intent(Settings.ACTION_CAST_SETTINGS);

    private final CastController mController;
    private final KeyguardStateController mKeyguard;
    private final NetworkController mNetworkController;
    private final DialogLaunchAnimator mDialogLaunchAnimator;
    private final Callback mCallback = new Callback();
    private final TileJavaAdapter mJavaAdapter;
    private final FeatureFlags mFeatureFlags;
    private boolean mCastTransportAllowed;
    private boolean mHotspotConnected;

    @Inject
    public CastTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            CastController castController,
            KeyguardStateController keyguardStateController,
            NetworkController networkController,
            HotspotController hotspotController,
            DialogLaunchAnimator dialogLaunchAnimator,
            ConnectivityRepository connectivityRepository,
            TileJavaAdapter javaAdapter,
            FeatureFlags featureFlags
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = castController;
        mKeyguard = keyguardStateController;
        mNetworkController = networkController;
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mJavaAdapter = javaAdapter;
        mFeatureFlags = featureFlags;
        mController.observe(this, mCallback);
        mKeyguard.observe(this, mCallback);
        if (!mFeatureFlags.isEnabled(SIGNAL_CALLBACK_DEPRECATION)) {
            mNetworkController.observe(this, mSignalCallback);
        } else {
            mJavaAdapter.bind(
                    this,
                    connectivityRepository.getDefaultConnections(),
                    mNetworkModelConsumer
            );
        }
        hotspotController.observe(this, mHotspotCallback);
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (DEBUG) Log.d(TAG, "handleSetListening " + listening);
        if (!listening) {
            mController.setDiscovering(false);
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
    protected void handleLongClick(@Nullable View view) {
        handleClick(view);
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (getState().state == Tile.STATE_UNAVAILABLE) {
            return;
        }

        List<CastDevice> activeDevices = getActiveDevices();
        if (willPopDialog()) {
            if (!mKeyguard.isShowing()) {
                showDialog(view);
            } else {
                mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                    // Dismissing the keyguard will collapse the shade, so we don't animate from the
                    // view here as it would not look good.
                    showDialog(null /* view */);
                });
            }
        } else {
            mController.stopCasting(activeDevices.get(0));
        }
    }

    // We want to pop up the media route selection dialog if we either have no active devices
    // (neither routes nor projection), or if we have an active route. In other cases, we assume
    // that a projection is active. This is messy, but this tile never correctly handled the
    // case where multiple devices were active :-/.
    private boolean willPopDialog() {
        List<CastDevice> activeDevices = getActiveDevices();
        return activeDevices.isEmpty() || (activeDevices.get(0).tag instanceof RouteInfo);
    }

    private List<CastDevice> getActiveDevices() {
        ArrayList<CastDevice> activeDevices = new ArrayList<>();
        for (CastDevice device : mController.getCastDevices()) {
            if (device.state == CastDevice.STATE_CONNECTED
                    || device.state == CastDevice.STATE_CONNECTING) {
                activeDevices.add(device);
            }
        }

        return activeDevices;
    }

    private static class DialogHolder {
        private Dialog mDialog;

        private void init(Dialog dialog) {
            mDialog = dialog;
        }
    }

    private void showDialog(@Nullable View view) {
        mUiHandler.post(() -> {
            final DialogHolder holder = new DialogHolder();
            final Dialog dialog = MediaRouteDialogPresenter.createDialog(
                    mContext,
                    ROUTE_TYPE_REMOTE_DISPLAY,
                    v -> {
                        ActivityLaunchAnimator.Controller controller =
                                mDialogLaunchAnimator.createActivityLaunchController(v);

                        if (controller == null) {
                            holder.mDialog.dismiss();
                        }

                        mActivityStarter
                                .postStartActivityDismissingKeyguard(getLongClickIntent(), 0,
                                        controller);
                    }, R.style.Theme_SystemUI_Dialog_Cast, false /* showProgressBarWhenEmpty */);
            holder.init(dialog);
            SystemUIDialog.setShowForAllUsers(dialog, true);
            SystemUIDialog.registerDismissListener(dialog);
            SystemUIDialog.setWindowOnTop(dialog, mKeyguard.isShowing());
            SystemUIDialog.setDialogSize(dialog);

            mUiHandler.post(() -> {
                if (view != null) {
                    mDialogLaunchAnimator.showFromView(dialog, view,
                            new DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                    INTERACTION_JANK_TAG));
                } else {
                    dialog.show();
                }
            });
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
        state.stateDescription = "";
        state.value = false;
        final List<CastDevice> devices = mController.getCastDevices();
        boolean connecting = false;
        // We always choose the first device that's in the CONNECTED state in the case where
        // multiple devices are CONNECTED at the same time.
        for (CastDevice device : devices) {
            if (device.state == CastDevice.STATE_CONNECTED) {
                state.value = true;
                state.secondaryLabel = getDeviceName(device);
                state.stateDescription = state.stateDescription + ","
                        + mContext.getString(
                                R.string.accessibility_cast_name, state.label);
                connecting = false;
                break;
            } else if (device.state == CastDevice.STATE_CONNECTING) {
                connecting = true;
            }
        }
        if (connecting && !state.value) {
            state.secondaryLabel = mContext.getString(R.string.quick_settings_connecting);
        }
        state.icon = ResourceIcon.get(state.value ? R.drawable.ic_cast_connected
                : R.drawable.ic_cast);
        if (canCastToNetwork() || state.value) {
            state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
            if (!state.value) {
                state.secondaryLabel = "";
            }
            state.expandedAccessibilityClassName = Button.class.getName();
            state.forceExpandIcon = willPopDialog();
        } else {
            state.state = Tile.STATE_UNAVAILABLE;
            String noWifi = mContext.getString(R.string.quick_settings_cast_no_network);
            state.secondaryLabel = noWifi;
            state.forceExpandIcon = false;
        }
        state.stateDescription = state.stateDescription + ", " + state.secondaryLabel;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_CAST;
    }

    private String getDeviceName(CastDevice device) {
        return device.name != null ? device.name
                : mContext.getString(R.string.quick_settings_cast_device_default_name);
    }

    private boolean canCastToNetwork() {
        return mCastTransportAllowed || mHotspotConnected;
    }

    private void setCastTransportAllowed(boolean connected) {
        if (connected != mCastTransportAllowed) {
            mCastTransportAllowed = connected;
            // Hotspot is not connected, so changes here should update
            if (!mHotspotConnected) {
                refreshState();
            }
        }
    }

    private void setHotspotConnected(boolean connected) {
        if (connected != mHotspotConnected) {
            mHotspotConnected = connected;
            // Wifi is not connected, so changes here should update
            if (!mCastTransportAllowed) {
                refreshState();
            }
        }
    }

    private final Consumer<DefaultConnectionModel> mNetworkModelConsumer = (model) -> {
        boolean isWifiDefault = model.getWifi().isDefault();
        boolean isEthernetDefault = model.getEthernet().isDefault();
        boolean hasCellularTransport = model.getMobile().isDefault();
        setCastTransportAllowed((isWifiDefault || isEthernetDefault) && !hasCellularTransport);
    };

    private final SignalCallback mSignalCallback = new SignalCallback() {
                @Override
                public void setWifiIndicators(@NonNull WifiIndicators indicators) {
                    // statusIcon.visible has the connected status information
                    boolean enabledAndConnected = indicators.enabled
                            && (indicators.qsIcon != null && indicators.qsIcon.visible);
                    setCastTransportAllowed(enabledAndConnected);
                }
            };

    private final HotspotController.Callback mHotspotCallback =
            new HotspotController.Callback() {
                @Override
                public void onHotspotChanged(boolean enabled, int numDevices) {
                    boolean enabledAndConnected = enabled && numDevices > 0;
                    setHotspotConnected(enabledAndConnected);
                }
            };

    private final class Callback implements CastController.Callback,
            KeyguardStateController.Callback {
        @Override
        public void onCastDevicesChanged() {
            refreshState();
        }

        @Override
        public void onKeyguardShowingChanged() {
            refreshState();
        }
    }
}
