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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.CompoundButtonCompat;
import androidx.recyclerview.widget.RecyclerView;

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

    public MediaOutputAdapter(MediaOutputController controller) {
        super(controller);
        setHasStableIds(true);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        super.onCreateViewHolder(viewGroup, viewType);
        if (mController.isAdvancedLayoutSupported()) {
            switch (viewType) {
                case MediaItem.MediaItemType.TYPE_GROUP_DIVIDER:
                    return new MediaGroupDividerViewHolder(mHolderView);
                case MediaItem.MediaItemType.TYPE_PAIR_NEW_DEVICE:
                case MediaItem.MediaItemType.TYPE_DEVICE:
                default:
                    return new MediaDeviceViewHolder(mHolderView);
            }
        } else {
            return new MediaDeviceViewHolder(mHolderView);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        if (mController.isAdvancedLayoutSupported()) {
            if (position >= mController.getMediaItemList().size()) {
                if (DEBUG) {
                    Log.d(TAG, "Incorrect position: " + position + " list size: "
                            + mController.getMediaItemList().size());
                }
                return;
            }
            MediaItem currentMediaItem = mController.getMediaItemList().get(position);
            switch (currentMediaItem.getMediaItemType()) {
                case MediaItem.MediaItemType.TYPE_GROUP_DIVIDER:
                    ((MediaGroupDividerViewHolder) viewHolder).onBind(currentMediaItem.getTitle());
                    break;
                case MediaItem.MediaItemType.TYPE_PAIR_NEW_DEVICE:
                    ((MediaDeviceViewHolder) viewHolder).onBind(CUSTOMIZED_ITEM_PAIR_NEW);
                    break;
                case MediaItem.MediaItemType.TYPE_DEVICE:
                    ((MediaDeviceViewHolder) viewHolder).onBind(
                            currentMediaItem.getMediaDevice().get(),
                            position);
                    break;
                default:
                    Log.d(TAG, "Incorrect position: " + position);
            }
        } else {
            final int size = mController.getMediaDevices().size();
            if (position == size) {
                ((MediaDeviceViewHolder) viewHolder).onBind(CUSTOMIZED_ITEM_PAIR_NEW);
            } else if (position < size) {
                ((MediaDeviceViewHolder) viewHolder).onBind(
                        ((List<MediaDevice>) (mController.getMediaDevices())).get(position),
                        position);
            } else if (DEBUG) {
                Log.d(TAG, "Incorrect position: " + position);
            }
        }
    }

    @Override
    public long getItemId(int position) {
        if (mController.isAdvancedLayoutSupported()) {
            if (position >= mController.getMediaItemList().size()) {
                Log.d(TAG, "Incorrect position for item id: " + position);
                return position;
            }
            MediaItem currentMediaItem = mController.getMediaItemList().get(position);
            return currentMediaItem.getMediaDevice().isPresent()
                    ? currentMediaItem.getMediaDevice().get().getId().hashCode()
                    : position;
        }
        final int size = mController.getMediaDevices().size();
        if (position == size) {
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
    public int getItemViewType(int position) {
        return mController.isAdvancedLayoutSupported()
                ? mController.getMediaItemList().get(position).getMediaItemType()
                : super.getItemViewType(position);
    }

    @Override
    public int getItemCount() {
        // Add extra one for "pair new"
        return mController.isAdvancedLayoutSupported()
                ? mController.getMediaItemList().size()
                : mController.getMediaDevices().size() + 1;
    }

    class MediaDeviceViewHolder extends MediaDeviceBaseViewHolder {

        MediaDeviceViewHolder(View view) {
            super(view);
        }

        @Override
        void onBind(MediaDevice device, int position) {
            super.onBind(device, position);
            boolean isMutingExpectedDeviceExist = mController.hasMutingExpectedDevice();
            final boolean currentlyConnected = isCurrentlyConnected(device);
            boolean isCurrentSeekbarInvisible = mSeekBar.getVisibility() == View.GONE;
            if (mCurrentActivePosition == position) {
                mCurrentActivePosition = -1;
            }

            if (mController.isAnyDeviceTransferring()) {
                if (device.getState() == MediaDeviceState.STATE_CONNECTING
                        && !mController.hasAdjustVolumeUserRestriction()) {
                    setUpDeviceIcon(device);
                    updateProgressBarColor();
                    setSingleLineLayout(getItemTitle(device), false /* showSeekBar*/,
                            true /* showProgressBar */, false /* showCheckBox */,
                            false /* showEndTouchArea */);
                } else {
                    setUpDeviceIcon(device);
                    setSingleLineLayout(getItemTitle(device));
                }
            } else {
                // Set different layout for each device
                if (device.isMutingExpectedDevice()
                        && !mController.isCurrentConnectedDeviceRemote()) {
                    if (!mController.isAdvancedLayoutSupported()) {
                        updateTitleIcon(R.drawable.media_output_icon_volume,
                                mController.getColorItemContent());
                    }
                    initMutingExpectedDevice();
                    mCurrentActivePosition = position;
                    updateFullItemClickListener(v -> onItemClick(v, device));
                    setSingleLineLayout(getItemTitle(device));
                } else if (device.getState() == MediaDeviceState.STATE_CONNECTING_FAILED) {
                    setUpDeviceIcon(device);
                    updateConnectionFailedStatusIcon();
                    mSubTitleText.setText(R.string.media_output_dialog_connect_failed);
                    updateFullItemClickListener(v -> onItemClick(v, device));
                    setTwoLineLayout(device, false /* bFocused */, false /* showSeekBar */,
                            false /* showProgressBar */, true /* showSubtitle */,
                            true /* showStatus */);
                } else if (device.getState() == MediaDeviceState.STATE_GROUPING) {
                    setUpDeviceIcon(device);
                    updateProgressBarColor();
                    setSingleLineLayout(getItemTitle(device), false /* showSeekBar*/,
                            true /* showProgressBar */, false /* showCheckBox */,
                            false /* showEndTouchArea */);
                } else if (mController.getSelectedMediaDevice().size() > 1
                        && isDeviceIncluded(mController.getSelectedMediaDevice(), device)) {
                    boolean isDeviceDeselectable = isDeviceIncluded(
                            mController.getDeselectableMediaDevice(), device);
                    if (!mController.isAdvancedLayoutSupported()) {
                        updateTitleIcon(R.drawable.media_output_icon_volume,
                                mController.getColorItemContent());
                    }
                    updateGroupableCheckBox(true, isDeviceDeselectable, device);
                    updateEndClickArea(device, isDeviceDeselectable);
                    setUpContentDescriptionForView(mContainerLayout, false, device);
                    setSingleLineLayout(getItemTitle(device), true /* showSeekBar */,
                            false /* showProgressBar */, true /* showCheckBox */,
                            true /* showEndTouchArea */);
                    initSeekbar(device, isCurrentSeekbarInvisible);
                } else if (!mController.hasAdjustVolumeUserRestriction()
                        && currentlyConnected) {
                    if (isMutingExpectedDeviceExist
                            && !mController.isCurrentConnectedDeviceRemote()) {
                        // mark as disconnected and set special click listener
                        setUpDeviceIcon(device);
                        updateFullItemClickListener(v -> cancelMuteAwaitConnection());
                        setSingleLineLayout(getItemTitle(device));
                    } else if (mController.isCurrentConnectedDeviceRemote()
                            && !mController.getSelectableMediaDevice().isEmpty()
                            && mController.isAdvancedLayoutSupported()) {
                        //If device is connected and there's other selectable devices, layout as
                        // one of selected devices.
                        boolean isDeviceDeselectable = isDeviceIncluded(
                                mController.getDeselectableMediaDevice(), device);
                        updateGroupableCheckBox(true, isDeviceDeselectable, device);
                        updateEndClickArea(device, isDeviceDeselectable);
                        setUpContentDescriptionForView(mContainerLayout, false, device);
                        setSingleLineLayout(getItemTitle(device), true /* showSeekBar */,
                                false /* showProgressBar */, true /* showCheckBox */,
                                true /* showEndTouchArea */);
                        initSeekbar(device, isCurrentSeekbarInvisible);
                    } else {
                        updateTitleIcon(R.drawable.media_output_icon_volume,
                                mController.getColorItemContent());
                        setUpContentDescriptionForView(mContainerLayout, false, device);
                        mCurrentActivePosition = position;
                        setSingleLineLayout(getItemTitle(device), true /* showSeekBar */,
                                false /* showProgressBar */, false /* showCheckBox */,
                                false /* showEndTouchArea */);
                        initSeekbar(device, isCurrentSeekbarInvisible);
                    }
                } else if (isDeviceIncluded(mController.getSelectableMediaDevice(), device)) {
                    setUpDeviceIcon(device);
                    updateGroupableCheckBox(false, true, device);
                    if (mController.isAdvancedLayoutSupported()) {
                        updateEndClickArea(device, true);
                    }
                    updateFullItemClickListener(mController.isAdvancedLayoutSupported()
                            ? v -> onItemClick(v, device)
                            : v -> onGroupActionTriggered(true, device));
                    setSingleLineLayout(getItemTitle(device), false /* showSeekBar */,
                            false /* showProgressBar */, true /* showCheckBox */,
                            true /* showEndTouchArea */);
                } else {
                    setUpDeviceIcon(device);
                    setSingleLineLayout(getItemTitle(device));
                    updateFullItemClickListener(v -> onItemClick(v, device));
                }
            }
        }

        public void setCheckBoxColor(CheckBox checkBox, int color) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            int[] colors = {color, color};
            CompoundButtonCompat.setButtonTintList(checkBox, new
                    ColorStateList(states, colors));
        }

        private void updateConnectionFailedStatusIcon() {
            mStatusIcon.setImageDrawable(
                    mContext.getDrawable(R.drawable.media_output_status_failed));
            mStatusIcon.setColorFilter(mController.getColorItemContent());
        }

        private void updateProgressBarColor() {
            mProgressBar.getIndeterminateDrawable().setColorFilter(
                    new PorterDuffColorFilter(
                            mController.getColorItemContent(),
                            PorterDuff.Mode.SRC_IN));
        }

        public void updateEndClickArea(MediaDevice device, boolean isDeviceDeselectable) {
            mEndTouchArea.setOnClickListener(null);
            mEndTouchArea.setOnClickListener(
                    isDeviceDeselectable ? (v) -> mCheckBox.performClick() : null);
            mEndTouchArea.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            if (mController.isAdvancedLayoutSupported()) {
                mEndTouchArea.getBackground().setColorFilter(
                        new PorterDuffColorFilter(mController.getColorItemBackground(),
                                PorterDuff.Mode.SRC_IN));
            }
            setUpContentDescriptionForView(mEndTouchArea, true, device);
        }

        private void updateGroupableCheckBox(boolean isSelected, boolean isGroupable,
                MediaDevice device) {
            mCheckBox.setOnCheckedChangeListener(null);
            mCheckBox.setChecked(isSelected);
            mCheckBox.setOnCheckedChangeListener(
                    isGroupable ? (buttonView, isChecked) -> onGroupActionTriggered(!isSelected,
                            device) : null);
            mCheckBox.setEnabled(isGroupable);
            setCheckBoxColor(mCheckBox, mController.getColorItemContent());
        }

        private void updateFullItemClickListener(View.OnClickListener listener) {
            mContainerLayout.setOnClickListener(listener);
            updateIconAreaClickListener(listener);
        }

        @Override
        void onBind(int customizedItem) {
            if (customizedItem == CUSTOMIZED_ITEM_PAIR_NEW) {
                mTitleText.setTextColor(mController.getColorItemContent());
                mCheckBox.setVisibility(View.GONE);
                setSingleLineLayout(mContext.getText(R.string.media_output_dialog_pairing_new));
                final Drawable addDrawable = mContext.getDrawable(R.drawable.ic_add);
                mTitleIcon.setImageDrawable(addDrawable);
                mTitleIcon.setColorFilter(mController.getColorItemContent());
                if (mController.isAdvancedLayoutSupported()) {
                    mIconAreaLayout.getBackground().setColorFilter(
                            new PorterDuffColorFilter(mController.getColorItemBackground(),
                                    PorterDuff.Mode.SRC_IN));
                }
                mContainerLayout.setOnClickListener(mController::launchBluetoothPairing);
            }
        }

        private void onGroupActionTriggered(boolean isChecked, MediaDevice device) {
            disableSeekBar();
            if (isChecked && isDeviceIncluded(mController.getSelectableMediaDevice(), device)) {
                mController.addDeviceToPlayMedia(device);
            } else if (!isChecked && isDeviceIncluded(mController.getDeselectableMediaDevice(),
                    device)) {
                mController.removeDeviceFromPlayMedia(device);
            }
        }

        private void onItemClick(View view, MediaDevice device) {
            if (mController.isAnyDeviceTransferring()) {
                return;
            }
            if (isCurrentlyConnected(device)) {
                Log.d(TAG, "This device is already connected! : " + device.getName());
                return;
            }
            mController.setTemporaryAllowListExceptionIfNeeded(device);
            mCurrentActivePosition = -1;
            mController.connectDevice(device);
            device.setState(MediaDeviceState.STATE_CONNECTING);
            notifyDataSetChanged();
        }

        private void cancelMuteAwaitConnection() {
            mController.cancelMuteAwaitConnection();
            notifyDataSetChanged();
        }

        private void setUpContentDescriptionForView(View view, boolean clickable,
                MediaDevice device) {
            view.setClickable(clickable);
            view.setContentDescription(
                    mContext.getString(device.getDeviceType()
                            == MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE
                            ? R.string.accessibility_bluetooth_name
                            : R.string.accessibility_cast_name, device.getName()));
        }
    }

    class MediaGroupDividerViewHolder extends RecyclerView.ViewHolder {
        final TextView mTitleText;

        MediaGroupDividerViewHolder(@NonNull View itemView) {
            super(itemView);
            mTitleText = itemView.requireViewById(R.id.title);
        }

        void onBind(String groupDividerTitle) {
            mTitleText.setTextColor(mController.getColorItemContent());
            mTitleText.setText(groupDividerTitle);
        }
    }
}
