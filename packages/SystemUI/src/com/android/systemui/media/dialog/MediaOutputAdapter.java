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

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

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
    public int getItemCount() {
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
            mTitleText.setTextColor(Utils.getColorStateListDefaultColor(mContext,
                    R.color.media_dialog_inactive_item_main_content));
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
                    setTwoLineLayout(device, false /* bFocused */,
                            false /* showSeekBar */, false /* showProgressBar */,
                            true /* showSubtitle */, true /* showStatus */);
                    mSubTitleText.setText(R.string.media_output_dialog_connect_failed);
                    mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                } else if (mController.getSelectedMediaDevice().size() > 1
                        && isDeviceIncluded(mController.getSelectedMediaDevice(), device)) {
                    mTitleText.setTextColor(Utils.getColorStateListDefaultColor(mContext,
                            R.color.media_dialog_active_item_main_content));
                    setSingleLineLayout(getItemTitle(device), true /* bFocused */,
                            true /* showSeekBar */,
                            false /* showProgressBar */, false /* showStatus */);
                    mCheckBox.setVisibility(View.VISIBLE);
                    mCheckBox.setChecked(true);
                    mCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        onCheckBoxClicked(false, device);
                    });
                    initSessionSeekbar();
                } else if (!mController.hasAdjustVolumeUserRestriction() && currentlyConnected) {
                    mStatusIcon.setImageDrawable(
                            mContext.getDrawable(R.drawable.media_output_status_check));
                    mTitleText.setTextColor(Utils.getColorStateListDefaultColor(mContext,
                            R.color.media_dialog_active_item_main_content));
                    setSingleLineLayout(getItemTitle(device), true /* bFocused */,
                            true /* showSeekBar */,
                            false /* showProgressBar */, true /* showStatus */);
                    initSeekbar(device);
                    mCurrentActivePosition = position;
                } else if (isDeviceIncluded(mController.getSelectableMediaDevice(), device)) {
                    mCheckBox.setVisibility(View.VISIBLE);
                    mCheckBox.setChecked(false);
                    mCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        onCheckBoxClicked(true, device);
                    });
                    setSingleLineLayout(getItemTitle(device), false /* bFocused */,
                            false /* showSeekBar */,
                            false /* showProgressBar */, false /* showStatus */);
                    mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                } else {
                    setSingleLineLayout(getItemTitle(device), false /* bFocused */);
                    mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                }
            }
        }

        @Override
        void onBind(int customizedItem, boolean topMargin, boolean bottomMargin) {
            if (customizedItem == CUSTOMIZED_ITEM_PAIR_NEW) {
                mTitleText.setTextColor(Utils.getColorStateListDefaultColor(mContext,
                        R.color.media_dialog_inactive_item_main_content));
                mCheckBox.setVisibility(View.GONE);
                setSingleLineLayout(mContext.getText(R.string.media_output_dialog_pairing_new),
                        false /* bFocused */);
                final Drawable d = mContext.getDrawable(R.drawable.ic_add);
                d.setColorFilter(new PorterDuffColorFilter(
                        Utils.getColorAccentDefaultColor(mContext), PorterDuff.Mode.SRC_IN));
                mTitleIcon.setImageDrawable(d);
                mContainerLayout.setOnClickListener(v -> onItemClick(CUSTOMIZED_ITEM_PAIR_NEW));
            }
        }

        private void onCheckBoxClicked(boolean isChecked, MediaDevice device) {
            if (isChecked && isDeviceIncluded(mController.getSelectableMediaDevice(), device)) {
                mController.addDeviceToPlayMedia(device);
            } else if (!isChecked && isDeviceIncluded(mController.getDeselectableMediaDevice(),
                    device)) {
                mController.removeDeviceFromPlayMedia(device);
            }
        }

        private boolean isDeviceIncluded(List<MediaDevice> deviceList, MediaDevice targetDevice) {
            for (MediaDevice device : deviceList) {
                if (TextUtils.equals(device.getId(), targetDevice.getId())) {
                    return true;
                }
            }
            return false;
        }

        private void onItemClick(View view, MediaDevice device) {
            if (mController.isTransferring()) {
                return;
            }

            mCurrentActivePosition = -1;
            mController.connectDevice(device);
            device.setState(MediaDeviceState.STATE_CONNECTING);
            if (!isAnimating()) {
                notifyDataSetChanged();
            }
        }

        private void onItemClick(int customizedItem) {
            if (customizedItem == CUSTOMIZED_ITEM_PAIR_NEW) {
                mController.launchBluetoothPairing();
            }
        }
    }
}
