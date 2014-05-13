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

import android.app.Dialog;
import android.content.Intent;
import android.media.MediaRouter;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.app.MediaRouteDialogPresenter;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.CastController;

/** Quick settings tile: Cast **/
public class CastTile extends QSTile<QSTile.BooleanState> {
    private static final Intent WIFI_DISPLAY_SETTINGS =
            new Intent(Settings.ACTION_WIFI_DISPLAY_SETTINGS);

    private final CastController mController;

    private boolean mShown;

    public CastTile(Host host) {
        super(host);
        mController = host.getCastController();
        if (mController != null) {
            mController.addCallback(mCallback);
        }
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void dispose() {
        if (mController == null) return;
        mController.removeCallback(mCallback);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
        if (mController == null) return;
        mController.setCurrentUserId(newUserId);
    }

    @Override
    protected void handleShown(boolean shown) {
        if (mShown == shown) return;
        if (mController == null) return;
        mShown = shown;
        mController.setDiscovering(mShown);
    }

    @Override
    protected void handleClick() {
        mHost.collapsePanels();

        final Dialog[] dialog = new Dialog[1];
        dialog[0] = MediaRouteDialogPresenter.createDialog(mContext,
                MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog[0].dismiss();
                mHost.startSettingsActivity(WIFI_DISPLAY_SETTINGS);
            }
        });
        dialog[0].getWindow().setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        dialog[0].show();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.visible = true;
        state.label = mContext
                .getString(R.string.quick_settings_remote_display_no_connection_label);
        state.icon = mHost.getVectorDrawable(R.drawable.ic_qs_cast);
        if (arg instanceof CallbackInfo) {
            final CallbackInfo cb = (CallbackInfo) arg;
            if (cb.connectedRouteName != null) {
                state.value = !cb.connecting;
            }
        }
    }

    private static class CallbackInfo {
        boolean enabled;
        boolean connecting;
        String connectedRouteName;
    }

    private final CastController.Callback mCallback = new CastController.Callback() {
        @Override
        public void onStateChanged(boolean enabled, boolean connecting,
                String connectedRouteName) {
            final CallbackInfo info = new CallbackInfo();  // TODO pool
            info.enabled = enabled;
            info.connecting = connecting;
            info.connectedRouteName = connectedRouteName;
            refreshState(info);
        }
    };
}
