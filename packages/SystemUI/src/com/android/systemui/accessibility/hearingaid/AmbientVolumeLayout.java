/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static com.android.settingslib.bluetooth.HearingAidInfo.DeviceSide.SIDE_LEFT;
import static com.android.settingslib.bluetooth.HearingAidInfo.DeviceSide.SIDE_RIGHT;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.bluetooth.AmbientVolumeUi;
import com.android.systemui.res.R;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.primitives.Ints;

import java.util.Map;

/**
 * A view of ambient volume controls.
 *
 * <p> It consists of a header with an expand icon and volume sliders for unified control and
 * separated control for devices in the same set. Toggle the expand icon will make the UI switch
 * between unified and separated control.
 */
public class AmbientVolumeLayout extends LinearLayout implements AmbientVolumeUi {

    @Nullable
    private AmbientVolumeUiListener mListener;
    private ImageView mExpandIcon;
    private ImageView mVolumeIcon;
    private boolean mExpandable = true;
    private boolean mExpanded = false;
    private boolean mMutable = false;
    private boolean mMuted = false;
    private final BiMap<Integer, AmbientVolumeSlider> mSideToSliderMap = HashBiMap.create();
    private int mVolumeLevel = AMBIENT_VOLUME_LEVEL_DEFAULT;

    private final AmbientVolumeSlider.OnChangeListener mSliderOnChangeListener =
            (slider, value) -> {
                if (mListener != null) {
                    final int side = mSideToSliderMap.inverse().get(slider);
                    mListener.onSliderValueChange(side, value);
                }
            };

    public AmbientVolumeLayout(@Nullable Context context) {
        this(context, /* attrs= */ null);
    }

    public AmbientVolumeLayout(@Nullable Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, /* defStyleAttr= */ 0);
    }

    public AmbientVolumeLayout(@Nullable Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, /* defStyleRes= */ 0);
    }

    public AmbientVolumeLayout(@Nullable Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        inflate(context, R.layout.hearing_device_ambient_volume_layout, /* root= */ this);
        init();
    }

    private void init() {
        mVolumeIcon = requireViewById(R.id.ambient_volume_icon);
        mVolumeIcon.setImageResource(com.android.settingslib.R.drawable.ic_ambient_volume);
        mVolumeIcon.setOnClickListener(v -> {
            if (!mMutable) {
                return;
            }
            setMuted(!mMuted);
            if (mListener != null) {
                mListener.onAmbientVolumeIconClick();
            }
        });
        updateVolumeIcon();

        mExpandIcon = requireViewById(R.id.ambient_expand_icon);
        mExpandIcon.setOnClickListener(v -> {
            setExpanded(!mExpanded);
            if (mListener != null) {
                mListener.onExpandIconClick();
            }
        });
        updateExpandIcon();
    }

    @Override
    public void setVisible(boolean visible) {
        setVisibility(visible ? VISIBLE : GONE);
    }

    @Override
    public void setExpandable(boolean expandable) {
        mExpandable = expandable;
        if (!mExpandable) {
            setExpanded(false);
        }
        updateExpandIcon();
    }

    @Override
    public boolean isExpandable() {
        return mExpandable;
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (!mExpandable && expanded) {
            return;
        }
        mExpanded = expanded;
        updateExpandIcon();
        updateLayout();
    }

    @Override
    public boolean isExpanded() {
        return mExpanded;
    }

    @Override
    public void setMutable(boolean mutable) {
        mMutable = mutable;
        if (!mMutable) {
            mVolumeLevel = AMBIENT_VOLUME_LEVEL_DEFAULT;
            setMuted(false);
        }
        updateVolumeIcon();
    }

    @Override
    public boolean isMutable() {
        return mMutable;
    }

    @Override
    public void setMuted(boolean muted) {
        if (!mMutable && muted) {
            return;
        }
        mMuted = muted;
        if (mMutable && mMuted) {
            for (AmbientVolumeSlider slider : mSideToSliderMap.values()) {
                slider.setValue(slider.getMin());
            }
        }
        updateVolumeIcon();
    }

    @Override
    public boolean isMuted() {
        return mMuted;
    }

    @Override
    public void setListener(@Nullable AmbientVolumeUiListener listener) {
        mListener = listener;
    }

    @Override
    public void setupSliders(@NonNull Map<Integer, BluetoothDevice> sideToDeviceMap) {
        sideToDeviceMap.forEach((side, device) -> createSlider(side));
        createSlider(SIDE_UNIFIED);

        LinearLayout controlContainer = requireViewById(R.id.ambient_control_container);
        controlContainer.removeAllViews();
        if (!mSideToSliderMap.isEmpty()) {
            for (int side : VALID_SIDES) {
                final AmbientVolumeSlider slider = mSideToSliderMap.get(side);
                if (slider != null) {
                    controlContainer.addView(slider);
                }
            }
        }
        updateLayout();
    }

    @Override
    public void setSliderEnabled(int side, boolean enabled) {
        AmbientVolumeSlider slider = mSideToSliderMap.get(side);
        if (slider != null && slider.isEnabled() != enabled) {
            slider.setEnabled(enabled);
            updateLayout();
        }
    }

    @Override
    public void setSliderValue(int side, int value) {
        AmbientVolumeSlider slider = mSideToSliderMap.get(side);
        if (slider != null && slider.getValue() != value) {
            slider.setValue(value);
            updateVolumeLevel();
        }
    }

    @Override
    public void setSliderRange(int side, int min, int max) {
        AmbientVolumeSlider slider = mSideToSliderMap.get(side);
        if (slider != null) {
            slider.setMin(min);
            slider.setMax(max);
        }
    }

    @Override
    public void updateLayout() {
        mSideToSliderMap.forEach((side, slider) -> {
            if (side == SIDE_UNIFIED) {
                slider.setVisibility(mExpanded ? GONE : VISIBLE);
            } else {
                slider.setVisibility(mExpanded ? VISIBLE : GONE);
            }
            if (!slider.isEnabled()) {
                slider.setValue(slider.getMin());
            }
        });
        updateVolumeLevel();
    }

    private void updateVolumeLevel() {
        int leftLevel, rightLevel;
        if (mExpanded) {
            leftLevel = getVolumeLevel(SIDE_LEFT);
            rightLevel = getVolumeLevel(SIDE_RIGHT);
        } else {
            final int unifiedLevel = getVolumeLevel(SIDE_UNIFIED);
            leftLevel = unifiedLevel;
            rightLevel = unifiedLevel;
        }
        mVolumeLevel = Ints.constrainToRange(leftLevel * 5 + rightLevel,
                AMBIENT_VOLUME_LEVEL_MIN, AMBIENT_VOLUME_LEVEL_MAX);
        updateVolumeIcon();
    }

    private int getVolumeLevel(int side) {
        AmbientVolumeSlider slider = mSideToSliderMap.get(side);
        if (slider == null || !slider.isEnabled()) {
            return 0;
        }
        return slider.getVolumeLevel();
    }

    private void updateExpandIcon() {
        mExpandIcon.setVisibility(mExpandable ? VISIBLE : GONE);
        mExpandIcon.setRotation(mExpanded ? ROTATION_EXPANDED : ROTATION_COLLAPSED);
        if (mExpandable) {
            final int stringRes = mExpanded ? R.string.hearing_devices_ambient_collapse_controls
                    : R.string.hearing_devices_ambient_expand_controls;
            mExpandIcon.setContentDescription(mContext.getString(stringRes));
        } else {
            mExpandIcon.setContentDescription(null);
        }
    }

    private void updateVolumeIcon() {
        mVolumeIcon.setImageLevel(mMuted ? 0 : mVolumeLevel);
        if (mMutable) {
            final int stringRes = mMuted ? R.string.hearing_devices_ambient_unmute
                    : R.string.hearing_devices_ambient_mute;
            mVolumeIcon.setContentDescription(mContext.getString(stringRes));
            mVolumeIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }  else {
            mVolumeIcon.setContentDescription(null);
            mVolumeIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    private void createSlider(int side) {
        if (mSideToSliderMap.containsKey(side)) {
            return;
        }
        AmbientVolumeSlider slider = new AmbientVolumeSlider(mContext);
        slider.addOnChangeListener(mSliderOnChangeListener);
        if (side == SIDE_LEFT) {
            slider.setTitle(mContext.getString(R.string.hearing_devices_ambient_control_left));
        } else if (side == SIDE_RIGHT) {
            slider.setTitle(mContext.getString(R.string.hearing_devices_ambient_control_right));
        }
        mSideToSliderMap.put(side, slider);
    }

    @VisibleForTesting
    ImageView getVolumeIcon() {
        return mVolumeIcon;
    }

    @VisibleForTesting
    ImageView getExpandIcon() {
        return mExpandIcon;
    }

    @VisibleForTesting
    Map<Integer, AmbientVolumeSlider> getSliders() {
        return mSideToSliderMap;
    }
}
