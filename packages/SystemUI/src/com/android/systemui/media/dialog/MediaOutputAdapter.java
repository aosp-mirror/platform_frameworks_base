/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.core.widget.CompoundButtonCompat;

import com.android.settingslib.Utils;
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;

import java.util.List;

/**
 * Adapter for media output dialog.
 */
public class MediaOutputAdapter extends MediaOutputBaseAdapter {

    private static final String TAG = "MediaOutputAdapter";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final float DEVICE_DISCONNECTED_ALPHA = 0.5f;
    private static final float DEVICE_CONNECTED_ALPHA = 1f;

    private final MediaOutputDialog mMediaOutputDialog;
    private ViewGroup mConnectedItem;
    private boolean mIncludeDynamicGroup;

    public MediaOutputAdapter(MediaOutputController controller,
            MediaOutputDialog mediaOutputDialog) {
        super(controller);
        mMediaOutputDialog = mediaOutputDialog;
        setHasStableIds(true);
    }

    @Override
    public MediaDeviceBaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        super.onCreateViewHolder(viewGroup, viewType);
        return new MediaDeviceViewHolder(mHolderView);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaDeviceBaseViewHolder viewHolder, int position) {
        final int size = mController.getMediaDevices().size();
        if (position == size && mController.isZeroMode()) {
            viewHolder.onBind(CUSTOMIZED_ITEM_PAIR_NEW, false /* topMargin */,
                    true /* bottomMargin */);
        } else if (position < size) {
            viewHolder.onBind(((List<MediaDevice>) (mController.getMediaDevices())).get(position),
                    position == 0 /* topMargin */, position == (size - 1) /* bottomMargin */,
                    position);
        } else if (DEBUG) {
            Log.d(TAG, "Incorrect position: " + position);
        }
    }

    @Override
    public long getItemId(int position) {
        final int size = mController.getMediaDevices().size();
        if (position == size && mController.isZeroMode()) {
            return -1;
        } else if (position < size) {
            return ((List<MediaDevice>) (mController.getMediaDevices()))
                    .get(position).getId().hashCode();
        } else if (DEBUG) {
            Log.d(TAG, "Incorrect position for item id: " + position);
        }
        return position;
    }

    @Override
    public int getItemCount() {
        if (mController.isZeroMode()) {
            // Add extra one for "pair new" or dynamic group
            return mController.getMediaDevices().size() + 1;
        }
        return mController.getMediaDevices().size();
    }

    class MediaDeviceViewHolder extends MediaDeviceBaseViewHolder {

        MediaDeviceViewHolder(View view) {
            super(view);
        }

        @Override
        void onBind(MediaDevice device, boolean topMargin, boolean bottomMargin, int position) {
            super.onBind(device, topMargin, bottomMargin, position);
            final boolean currentlyConnected = !mIncludeDynamicGroup
                    && isCurrentlyConnected(device);
            if (currentlyConnected) {
                mConnectedItem = mContainerLayout;
            }
            mCheckBox.setVisibility(View.GONE);
            mStatusIcon.setVisibility(View.GONE);
            mEndTouchArea.setVisibility(View.GONE);
            mContainerLayout.setOnClickListener(null);
            mTitleText.setTextColor(mController.getColorItemContent());
            mSubTitleText.setTextColor(mController.getColorItemContent());
            mTwoLineTitleText.setTextColor(mController.getColorItemContent());
            mSeekBar.getProgressDrawable().setColorFilter(
                    new PorterDuffColorFilter(mController.getColorSeekbarProgress(),
                            PorterDuff.Mode.SRC_IN));
            if (mCurrentActivePosition == position) {
                mCurrentActivePosition = -1;
            }
            if (device.getDeviceType() == MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE
                    && !device.isConnected()) {
                mTitleText.setAlpha(DEVICE_DISCONNECTED_ALPHA);
                mTitleIcon.setAlpha(DEVICE_DISCONNECTED_ALPHA);
            } else {
                mTitleText.setAlpha(DEVICE_CONNECTED_ALPHA);
                mTitleIcon.setAlpha(DEVICE_CONNECTED_ALPHA);
            }

            if (mController.isTransferring()) {
                if (device.getState() == MediaDeviceState.STATE_CONNECTING
                        && !mController.hasAdjustVolumeUserRestriction()) {
                    mProgressBar.getIndeterminateDrawable().setColorFilter(
                            new PorterDuffColorFilter(
                                    mController.getColorItemContent(),
                                    PorterDuff.Mode.SRC_IN));
                    setSingleLineLayout(getItemTitle(device), true /* bFocused */,
                            false /* showSeekBar*/,
                            true /* showProgressBar */, false /* showStatus */);
                } else {
                    setSingleLineLayout(getItemTitle(device), false /* bFocused */);
                }
            } else {
                // Set different layout for each device
                if (device.getState() == MediaDeviceState.STATE_CONNECTING_FAILED) {
                    mTitleText.setAlpha(DEVICE_CONNECTED_ALPHA);
                    mTitleIcon.setAlpha(DEVICE_CONNECTED_ALPHA);
                    mStatusIcon.setImageDrawable(
                            mContext.getDrawable(R.drawable.media_output_status_failed));
                    mStatusIcon.setColorFilter(mController.getColorItemContent());
                    setTwoLineLayout(device, false /* bFocused */,
                            false /* showSeekBar */, false /* showProgressBar */,
                            true /* showSubtitle */, true /* showStatus */);
                    mSubTitleText.setText(R.string.media_output_dialog_connect_failed);
                    mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                } else if (mController.getSelectedMediaDevice().size() > 1
                        && isDeviceIncluded(mController.getSelectedMediaDevice(), device)) {
                    mTitleText.setTextColor(mController.getColorItemContent());
                    setSingleLineLayout(getItemTitle(device), true /* bFocused */,
                            true /* showSeekBar */,
                            false /* showProgressBar */, false /* showStatus */);
                    mCheckBox.setOnCheckedChangeListener(null);
                    mCheckBox.setVisibility(View.VISIBLE);
                    mCheckBox.setChecked(true);
                    mCheckBox.setOnCheckedChangeListener(
                            (buttonView, isChecked) -> onGroupActionTriggered(false, device));
                    setCheckBoxColor(mCheckBox, mController.getColorItemContent());
                    initSeekbar(device);
                    mEndTouchArea.setVisibility(View.VISIBLE);
                    mEndTouchArea.setOnClickListener(null);
                    mEndTouchArea.setOnClickListener((v) -> mCheckBox.performClick());
                } else if (!mController.hasAdjustVolumeUserRestriction() && currentlyConnected) {
                    mStatusIcon.setImageDrawable(
                            mContext.getDrawable(R.drawable.media_output_status_check));
                    mStatusIcon.setColorFilter(mController.getColorItemContent());
                    mTitleText.setTextColor(mController.getColorItemContent());
                    setSingleLineLayout(getItemTitle(device), true /* bFocused */,
                            true /* showSeekBar */,
                            false /* showProgressBar */, true /* showStatus */);
                    initSeekbar(device);
                    mCurrentActivePosition = position;
                } else if (isDeviceIncluded(mController.getSelectableMediaDevice(), device)) {
                    mCheckBox.setOnCheckedChangeListener(null);
                    mCheckBox.setVisibility(View.VISIBLE);
                    mCheckBox.setChecked(false);
                    mCheckBox.setOnCheckedChangeListener(
                            (buttonView, isChecked) -> onGroupActionTriggered(true, device));
                    mContainerLayout.setOnClickListener(v -> onGroupActionTriggered(true, device));
                    setCheckBoxColor(mCheckBox, mController.getColorItemContent());
                    setSingleLineLayout(getItemTitle(device), false /* bFocused */,
                            false /* showSeekBar */,
                            false /* showProgressBar */, false /* showStatus */);
                } else {
                    setSingleLineLayout(getItemTitle(device), false /* bFocused */);
                    mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                }
            }
        }

        public void setCheckBoxColor(CheckBox checkBox, int color) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            int[] colors = {color, color};
            CompoundButtonCompat.setButtonTintList(checkBox, new
                    ColorStateList(states, colors));
        }

        @Override
        void onBind(int customizedItem, boolean topMargin, boolean bottomMargin) {
            if (customizedItem == CUSTOMIZED_ITEM_PAIR_NEW) {
                mTitleText.setTextColor(mController.getColorItemContent());
                mCheckBox.setVisibility(View.GONE);
                setSingleLineLayout(mContext.getText(R.string.media_output_dialog_pairing_new),
                        false /* bFocused */);
                final Drawable d = mContext.getDrawable(R.drawable.ic_add);
                d.setColorFilter(new PorterDuffColorFilter(
                        Utils.getColorAccentDefaultColor(mContext), PorterDuff.Mode.SRC_IN));
                mTitleIcon.setImageDrawable(d);
                mContainerLayout.setOnClickListener(mController::launchBluetoothPairing);
            }
        }

        private void onGroupActionTriggered(boolean isChecked, MediaDevice device) {
            if (isChecked && isDeviceIncluded(mController.getSelectableMediaDevice(), device)) {
                mController.addDeviceToPlayMedia(device);
            } else if (!isChecked && isDeviceIncluded(mController.getDeselectableMediaDevice(),
                    device)) {
                mController.removeDeviceFromPlayMedia(device);
            }
        }

        private void onItemClick(View view, MediaDevice device) {
            if (mController.isTransferring()) {
                return;
            }
            if (isCurrentlyConnected(device)) {
                Log.d(TAG, "This device is already connected! : " + device.getName());
                return;
            }
            mCurrentActivePosition = -1;
            mController.connectDevice(device);
            device.setState(MediaDeviceState.STATE_CONNECTING);
            if (!isAnimating()) {
                notifyDataSetChanged();
            }
        }
    }
}
