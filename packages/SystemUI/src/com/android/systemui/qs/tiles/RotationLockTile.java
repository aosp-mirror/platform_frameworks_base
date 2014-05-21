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

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.AnimationDrawable;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;

/** Quick settings tile: Rotation **/
public class RotationLockTile extends QSTile<QSTile.BooleanState> {

    private final RotationLockController mController;

    public RotationLockTile(Host host) {
        super(host);
        mController = host.getRotationLockController();
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    public void setListening(boolean listening) {
        if (mController == null) return;
        if (listening) {
            mController.addRotationLockControllerCallback(mCallback);
        } else {
            mController.removeRotationLockControllerCallback(mCallback);
        }
    }

    @Override
    protected void handleClick() {
        if (mController == null) return;
        mController.setRotationLocked(!mState.value);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mController == null) return;
        final boolean rotationLocked = mController.isRotationLocked();
        state.visible = mController.isRotationLockAffordanceVisible();
        final Resources res = mContext.getResources();
        if (state.value != rotationLocked) {
            state.value = rotationLocked;
            final AnimationDrawable d = (AnimationDrawable) res.getDrawable(rotationLocked
                    ? R.drawable.ic_qs_rotation_locked
                    : R.drawable.ic_qs_rotation_unlocked);
            state.icon = d;
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    d.start();
                }
            });
        }
        if (rotationLocked) {
            final int lockOrientation = mController.getRotationLockOrientation();
            final int label = lockOrientation == Configuration.ORIENTATION_PORTRAIT
                    ? R.string.quick_settings_rotation_locked_portrait_label
                    : lockOrientation == Configuration.ORIENTATION_LANDSCAPE
                    ? R.string.quick_settings_rotation_locked_landscape_label
                    : R.string.quick_settings_rotation_locked_label;
            state.label = mContext.getString(label);
            if (state.icon == null) {
                state.icon = res.getDrawable(R.drawable.ic_qs_rotation_15);
            }
        } else {
            state.label = mContext.getString(R.string.quick_settings_rotation_unlocked_label);
            if (state.icon == null) {
                state.icon = res.getDrawable(R.drawable.ic_qs_rotation_01);
            }
        }
    }

    private final RotationLockControllerCallback mCallback = new RotationLockControllerCallback() {
        @Override
        public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
            refreshState();
        }
    };
}
