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

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;

/** Quick settings tile: Rotation **/
public class RotationLockTile extends QSTile<QSTile.BooleanState> {
    private final AnimationIcon mPortraitToAuto
            = new AnimationIcon(R.drawable.ic_portrait_to_auto_rotate_animation);
    private final AnimationIcon mAutoToPortrait
            = new AnimationIcon(R.drawable.ic_portrait_from_auto_rotate_animation);

    private final AnimationIcon mLandscapeToAuto
            = new AnimationIcon(R.drawable.ic_landscape_to_auto_rotate_animation);
    private final AnimationIcon mAutoToLandscape
            = new AnimationIcon(R.drawable.ic_landscape_from_auto_rotate_animation);

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
        final boolean newState = !mState.value;
        mController.setRotationLocked(newState);
        refreshState(newState ? UserBoolean.USER_TRUE : UserBoolean.USER_FALSE);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mController == null) return;
        final boolean rotationLocked = arg != null ? ((UserBoolean) arg).value
                : mController.isRotationLocked();
        final boolean userInitiated = arg != null ? ((UserBoolean) arg).userInitiated : false;
        state.visible = mController.isRotationLockAffordanceVisible();
        state.value = rotationLocked;
        final boolean portrait = mContext.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE;
        final AnimationIcon icon;
        if (rotationLocked) {
            final int label = portrait ? R.string.quick_settings_rotation_locked_portrait_label
                    : R.string.quick_settings_rotation_locked_landscape_label;
            state.label = mContext.getString(label);
            icon = portrait ? mAutoToPortrait : mAutoToLandscape;
        } else {
            state.label = mContext.getString(R.string.quick_settings_rotation_unlocked_label);
            icon = portrait ? mPortraitToAuto : mLandscapeToAuto;
        }
        icon.setAllowAnimation(userInitiated);
        state.icon = icon;
        state.contentDescription = getAccessibilityString(rotationLocked,
                R.string.accessibility_rotation_lock_on_portrait,
                R.string.accessibility_rotation_lock_on_landscape,
                R.string.accessibility_rotation_lock_off);
    }

    /**
     * Get the correct accessibility string based on the state
     *
     * @param locked Whether or not rotation is locked.
     * @param idWhenPortrait The id which should be used when locked in portrait.
     * @param idWhenLandscape The id which should be used when locked in landscape.
     * @param idWhenOff The id which should be used when the rotation lock is off.
     * @return
     */
    private String getAccessibilityString(boolean locked, int idWhenPortrait, int idWhenLandscape,
            int idWhenOff) {
        int stringID;
        if (locked) {
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
        return getAccessibilityString(mState.value,
                R.string.accessibility_rotation_lock_on_portrait_changed,
                R.string.accessibility_rotation_lock_on_landscape_changed,
                R.string.accessibility_rotation_lock_off_changed);
    }

    private final RotationLockControllerCallback mCallback = new RotationLockControllerCallback() {
        @Override
        public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
            refreshState(rotationLocked ? UserBoolean.BACKGROUND_TRUE
                    : UserBoolean.BACKGROUND_FALSE);
        }
    };
}
