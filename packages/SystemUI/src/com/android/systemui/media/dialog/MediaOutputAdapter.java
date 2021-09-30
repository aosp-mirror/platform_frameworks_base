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

import static android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
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
        } else if (mIncludeDynamicGroup) {
            if (position == 0) {
                viewHolder.onBind(CUSTOMIZED_ITEM_DYNAMIC_GROUP, true /* topMargin */,
                        false /* bottomMargin */);
            } else {
                // When group item is added at the first(position == 0), devices will be added from
                // the second item(position == 1). It means that the index of device list starts
                // from "position - 1".
                viewHolder.onBind(((List<MediaDevice>) (mController.getMediaDevices()))
                                .get(position - 1),
                        false /* topMargin */, position == size /* bottomMargin */, position);
            }
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
        mIncludeDynamicGroup = mController.getSelectedMediaDevice().size() > 1;
        if (mController.isZeroMode() || mIncludeDynamicGroup) {
            // Add extra one for "pair new" or dynamic group
            return mController.getMediaDevices().size() + 1;
        }
        return mController.getMediaDevices().size();
    }

    @Override
    CharSequence getItemTitle(MediaDevice device) {
        if (device.getDeviceType() == MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE
                && !device.isConnected()) {
            final CharSequence deviceName = device.getName();
            // Append status to title only for the disconnected Bluetooth device.
            final SpannableString spannableTitle = new SpannableString(
                    mContext.getString(R.string.media_output_dialog_disconnected, deviceName));
            spannableTitle.setSpan(new ForegroundColorSpan(
                    Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorSecondary)),
                    deviceName.length(),
                    spannableTitle.length(), SPAN_EXCLUSIVE_EXCLUSIVE);
            return spannableTitle;
        }
        return super.getItemTitle(device);
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
            mBottomDivider.setVisibility(View.GONE);
            mCheckBox.setVisibility(View.GONE);
            if (currentlyConnected && mController.isActiveRemoteDevice(device)
                    && mController.getSelectableMediaDevice().size() > 0) {
                // Init active device layout
                mDivider.setVisibility(View.VISIBLE);
                mDivider.setTransitionAlpha(1);
                mAddIcon.setVisibility(View.VISIBLE);
                mAddIcon.setTransitionAlpha(1);
                mAddIcon.setOnClickListener(this::onEndItemClick);
            } else {
                // Init non-active device layout
                mDivider.setVisibility(View.GONE);
                mAddIcon.setVisibility(View.GONE);
            }
            if (mCurrentActivePosition == position) {
                mCurrentActivePosition = -1;
            }
            if (mController.isTransferring()) {
                if (device.getState() == MediaDeviceState.STATE_CONNECTING
                        && !mController.hasAdjustVolumeUserRestriction()) {
                    setTwoLineLayout(device, true /* bFocused */, false /* showSeekBar*/,
                            true /* showProgressBar */, false /* showSubtitle */);
                } else {
                    setSingleLineLayout(getItemTitle(device), false /* bFocused */);
                }
            } else {
                // Set different layout for each device
                if (device.getState() == MediaDeviceState.STATE_CONNECTING_FAILED) {
                    setTwoLineLayout(device, false /* bFocused */,
                            false /* showSeekBar */, false /* showProgressBar */,
                            true /* showSubtitle */);
                    mSubTitleText.setText(R.string.media_output_dialog_connect_failed);
                    mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                } else if (!mController.hasAdjustVolumeUserRestriction() && currentlyConnected) {
                    setTwoLineLayout(device, true /* bFocused */, true /* showSeekBar */,
                            false /* showProgressBar */, false /* showSubtitle */);
                    initSeekbar(device);
                    mCurrentActivePosition = position;
                } else {
                    setSingleLineLayout(getItemTitle(device), false /* bFocused */);
                    mContainerLayout.setOnClickListener(v -> onItemClick(v, device));
                }
            }
        }

        @Override
        void onBind(int customizedItem, boolean topMargin, boolean bottomMargin) {
            super.onBind(customizedItem, topMargin, bottomMargin);
            if (customizedItem == CUSTOMIZED_ITEM_PAIR_NEW) {
                mCheckBox.setVisibility(View.GONE);
                mDivider.setVisibility(View.GONE);
                mAddIcon.setVisibility(View.GONE);
                mBottomDivider.setVisibility(View.GONE);
                setSingleLineLayout(mContext.getText(R.string.media_output_dialog_pairing_new),
                        false /* bFocused */);
                final Drawable d = mContext.getDrawable(R.drawable.ic_add);
                d.setColorFilter(new PorterDuffColorFilter(
                        Utils.getColorAccentDefaultColor(mContext), PorterDuff.Mode.SRC_IN));
                mTitleIcon.setImageDrawable(d);
                mContainerLayout.setOnClickListener(v -> onItemClick(CUSTOMIZED_ITEM_PAIR_NEW));
            } else if (customizedItem == CUSTOMIZED_ITEM_DYNAMIC_GROUP) {
                mConnectedItem = mContainerLayout;
                mBottomDivider.setVisibility(View.GONE);
                mCheckBox.setVisibility(View.GONE);
                if (mController.getSelectableMediaDevice().size() > 0) {
                    mDivider.setVisibility(View.VISIBLE);
                    mDivider.setTransitionAlpha(1);
                    mAddIcon.setVisibility(View.VISIBLE);
                    mAddIcon.setTransitionAlpha(1);
                    mAddIcon.setOnClickListener(this::onEndItemClick);
                } else {
                    mDivider.setVisibility(View.GONE);
                    mAddIcon.setVisibility(View.GONE);
                }
                mTitleIcon.setImageDrawable(getSpeakerDrawable());
                final CharSequence sessionName = mController.getSessionName();
                final CharSequence title = TextUtils.isEmpty(sessionName)
                        ? mContext.getString(R.string.media_output_dialog_group) : sessionName;
                setTwoLineLayout(title, true /* bFocused */, true /* showSeekBar */,
                        false /* showProgressBar */, false /* showSubtitle */);
                initSessionSeekbar();
            }
        }

        private void onItemClick(View view, MediaDevice device) {
            if (mController.isTransferring()) {
                return;
            }

            mCurrentActivePosition = -1;
            playSwitchingAnim(mConnectedItem, view);
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

        private void onEndItemClick(View view) {
            mController.launchMediaOutputGroupDialog(mMediaOutputDialog.getDialogView());
        }
    }
}
