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
        state.value = rotationLocked;
        if (rotationLocked) {
            final boolean portrait = res.getConfiguration().orientation
                    != Configuration.ORIENTATION_LANDSCAPE;
            final int label = portrait ? R.string.quick_settings_rotation_locked_portrait_label
                    : R.string.quick_settings_rotation_locked_landscape_label;
            final int icon = portrait ? R.drawable.ic_qs_rotation_portrait
                    : R.drawable.ic_qs_rotation_landscape;
            state.label = mContext.getString(label);
            state.icon = mContext.getDrawable(icon);
        } else {
            state.label = mContext.getString(R.string.quick_settings_rotation_unlocked_label);
            state.icon = res.getDrawable(R.drawable.ic_qs_rotation_unlocked);
        }
        state.contentDescription = getAccessibilityString(
                R.string.accessibility_rotation_lock_on_portrait,
                R.string.accessibility_rotation_lock_on_landscape,
                R.string.accessibility_rotation_lock_off);
    }

    /**
     * Get the correct accessibility string based on the state
     *
     * @param idWhenPortrait The id which should be used when locked in portrait.
     * @param idWhenLandscape The id which should be used when locked in landscape.
     * @param idWhenOff The id which should be used when the rotation lock is off.
     * @return
     */
    private String getAccessibilityString(int idWhenPortrait, int idWhenLandscape, int idWhenOff) {
        int stringID;
        if (mState.value) {
            final boolean portrait = mContext.getResources().getConfiguration().orientation
                    != Configuration.ORIENTATION_LANDSCAPE;
            stringID = portrait ? idWhenPortrait: idWhenLandscape;
        } else {
            stringID = idWhenOff;
        }
        return mContext.getString(stringID);
    }

    @Override
    protected String composeChangeAnnouncement() {
        return getAccessibilityString(
                R.string.accessibility_rotation_lock_on_portrait_changed,
                R.string.accessibility_rotation_lock_on_landscape_changed,
                R.string.accessibility_rotation_lock_off_changed);
    }

    private final RotationLockControllerCallback mCallback = new RotationLockControllerCallback() {
        @Override
        public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
            refreshState();
        }
    };
}
