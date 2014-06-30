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
 * limitations under the License
 */

package com.android.systemui.qs.tiles;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.statusbar.policy.FlashlightController;

import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Secure;

/** Quick settings tile: Control flashlight **/
public class FlashlightTile extends QSTile<QSTile.BooleanState> implements
        FlashlightController.FlashlightListener {

    private final FlashlightController mFlashlightController;

    public FlashlightTile(Host host) {
        super(host);
        mFlashlightController = host.getFlashlightController();
        mFlashlightController.addListener(this);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
    }

    @Override
    protected void handleClick() {
        boolean newState = !mState.value;
        mFlashlightController.setFlashlight(newState);
        refreshState(newState);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (arg instanceof Boolean) {
            state.value = (Boolean) arg;
        }
        state.visible = mFlashlightController.isAvailable();
        state.label = mHost.getContext().getString(R.string.quick_settings_flashlight_label);
        state.iconId = state.value
                ? R.drawable.ic_qs_flashlight_on : R.drawable.ic_qs_flashlight_off;
    }

    @Override
    public void onFlashlightOff() {
        refreshState(false);
    }

    @Override
    public void onFlashlightError() {
        refreshState(false);
    }
}
