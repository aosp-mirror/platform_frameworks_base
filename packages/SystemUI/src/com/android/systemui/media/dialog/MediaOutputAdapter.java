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

import static com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_GO_TO_APP;
import static com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_NONE;
import static com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_TRANSFER;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.DoNotInline;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.widget.CompoundButtonCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Adapter for media output dialog.
 */
public class MediaOutputAdapter extends MediaOutputBaseAdapter {

    private static final String TAG = "MediaOutputAdapter";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final float DEVICE_DISCONNECTED_ALPHA = 0.5f;
    private static final float DEVICE_CONNECTED_ALPHA = 1f;
    protected List<MediaItem> mMediaItemList = new CopyOnWriteArrayList<>();

    public MediaOutputAdapter(MediaOutputController controller) {
        super(controller);
        setHasStableIds(true);
    }

    @Override
    public void updateItems() {
        mMediaItemList.clear();
        mMediaItemList.addAll(mController.getMediaItemList());
        notifyDataSetChanged();
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
            if (position >= mMediaItemList.size()) {
                if (DEBUG) {
                    Log.d(TAG, "Incorrect position: " + position + " list size: "
                            + mMediaItemList.size());
                }
                return;
            }
            MediaItem currentMediaItem = mMediaItemList.get(position);
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
            if (position >= mMediaItemList.size()) {
                Log.d(TAG, "Incorrect position for item id: " + position);
                return position;
            }
            MediaItem currentMediaItem = mMediaItemList.get(position);
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
        if (mController.isAdvancedLayoutSupported()
                && position >= mMediaItemList.size()) {
            Log.d(TAG, "Incorrect position for item type: " + position);
            return MediaItem.MediaItemType.TYPE_GROUP_DIVIDER;
        }
        return mController.isAdvancedLayoutSupported()
                ? mMediaItemList.get(position).getMediaItemType()
                : super.getItemViewType(position);
    }

    @Override
    public int getItemCount() {
        // Add extra one for "pair new"
        return mController.isAdvancedLayoutSupported()
                ? mMediaItemList.size()
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
            mStatusIcon.setVisibility(View.GONE);

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
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && mController.isSubStatusSupported()
                        && mController.isAdvancedLayoutSupported() && device.hasSubtext()) {
                    boolean isActiveWithOngoingSession =
                            (device.hasOngoingSession() && (currentlyConnected || isDeviceIncluded(
                                    mController.getSelectedMediaDevice(), device)));
                    boolean isHost = device.isHostForOngoingSession()
                            && isActiveWithOngoingSession;
                    if (isHost) {
                        mCurrentActivePosition = position;
                        updateTitleIcon(R.drawable.media_output_icon_volume,
                                mController.getColorItemContent());
                        mSubTitleText.setText(device.getSubtextString());
                        updateTwoLineLayoutContentAlpha(DEVICE_CONNECTED_ALPHA);
                        updateEndClickAreaAsSessionEditing(device);
                        setTwoLineLayout(device, null /* title */, true /* bFocused */,
                                true /* showSeekBar */, false /* showProgressBar */,
                                true /* showSubtitle */, false /* showStatus */,
                                true /* showEndTouchArea */, false /* isFakeActive */);
                        initSeekbar(device, isCurrentSeekbarInvisible);
                    } else {
                        if (isActiveWithOngoingSession) {
                            //Selected device which has ongoing session, disable seekbar since we
                            //only allow volume control on Host
                            mCurrentActivePosition = position;
                        }
                        boolean showSeekbar =
                                (!device.hasOngoingSession() && currentlyConnected);
                        if (showSeekbar) {
                            updateTitleIcon(R.drawable.media_output_icon_volume,
                                    mController.getColorItemContent());
                            initSeekbar(device, isCurrentSeekbarInvisible);
                        } else {
                            setUpDeviceIcon(device);
                        }
                        mSubTitleText.setText(device.getSubtextString());
                        Drawable deviceStatusIcon =
                                device.hasOngoingSession() ? mContext.getDrawable(
                                        R.drawable.ic_sound_bars_anim)
                                        : Api34Impl.getDeviceStatusIconBasedOnSelectionBehavior(
                                                device,
                                                mContext);
                        if (deviceStatusIcon != null) {
                            updateDeviceStatusIcon(deviceStatusIcon);
                        }
                        updateTwoLineLayoutContentAlpha(
                                updateClickActionBasedOnSelectionBehavior(device)
                                        ? DEVICE_CONNECTED_ALPHA : DEVICE_DISCONNECTED_ALPHA);
                        setTwoLineLayout(device, currentlyConnected /* bFocused */,
                                showSeekbar  /* showSeekBar */,
                                false /* showProgressBar */, true /* showSubtitle */,
                                deviceStatusIcon != null /* showStatus */,
                                isActiveWithOngoingSession /* isFakeActive */);
                    }
                } else if (device.getState() == MediaDeviceState.STATE_CONNECTING_FAILED) {
                    setUpDeviceIcon(device);
                    updateConnectionFailedStatusIcon();
                    mSubTitleText.setText(R.string.media_output_dialog_connect_failed);
                    updateFullItemClickListener(v -> onItemClick(v, device));
                    setTwoLineLayout(device, false /* bFocused */, false /* showSeekBar */,
                            false /* showProgressBar */, true /* showSubtitle */,
                            true /* showStatus */, false /*isFakeActive*/);
                } else if (device.getState() == MediaDeviceState.STATE_GROUPING) {
                    setUpDeviceIcon(device);
                    updateProgressBarColor();
                    setSingleLineLayout(getItemTitle(device), false /* showSeekBar*/,
                            true /* showProgressBar */, false /* showCheckBox */,
                            false /* showEndTouchArea */);
                } else if (mController.getSelectedMediaDevice().size() > 1
                        && isDeviceIncluded(mController.getSelectedMediaDevice(), device)) {
                    // selected device in group
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
                    // single selected device
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
                        updateTitleIcon(R.drawable.media_output_icon_volume,
                                mController.getColorItemContent());
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
                    //groupable device
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
                    if (mController.isAdvancedLayoutSupported()
                            && mController.isSubStatusSupported()) {
                        Drawable deviceStatusIcon =
                                device.hasOngoingSession() ? mContext.getDrawable(
                                        R.drawable.ic_sound_bars_anim)
                                        : Api34Impl.getDeviceStatusIconBasedOnSelectionBehavior(
                                                device,
                                                mContext);
                        if (deviceStatusIcon != null) {
                            updateDeviceStatusIcon(deviceStatusIcon);
                            mStatusIcon.setVisibility(View.VISIBLE);
                        }
                        updateSingleLineLayoutContentAlpha(
                                updateClickActionBasedOnSelectionBehavior(device)
                                        ? DEVICE_CONNECTED_ALPHA : DEVICE_DISCONNECTED_ALPHA);
                    } else {
                        updateFullItemClickListener(v -> onItemClick(v, device));
                    }
                }
            }
        }

        public void setCheckBoxColor(CheckBox checkBox, int color) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            int[] colors = {color, color};
            CompoundButtonCompat.setButtonTintList(checkBox, new
                    ColorStateList(states, colors));
        }

        private void updateTwoLineLayoutContentAlpha(float alphaValue) {
            mSubTitleText.setAlpha(alphaValue);
            mTitleIcon.setAlpha(alphaValue);
            mTwoLineTitleText.setAlpha(alphaValue);
            mStatusIcon.setAlpha(alphaValue);
        }

        private void updateSingleLineLayoutContentAlpha(float alphaValue) {
            mTitleIcon.setAlpha(alphaValue);
            mTitleText.setAlpha(alphaValue);
            mStatusIcon.setAlpha(alphaValue);
        }

        private void updateEndClickAreaAsSessionEditing(MediaDevice device) {
            mEndClickIcon.setOnClickListener(null);
            mEndTouchArea.setOnClickListener(null);
            updateEndClickAreaColor(mController.getColorSeekbarProgress());
            mEndClickIcon.setImageTintList(
                    ColorStateList.valueOf(mController.getColorItemContent()));
            mEndClickIcon.setOnClickListener(
                    v -> mController.tryToLaunchInAppRoutingIntent(device.getId(), v));
            mEndTouchArea.setOnClickListener(v -> mCheckBox.performClick());
        }

        public void updateEndClickAreaColor(int color) {
            if (mController.isAdvancedLayoutSupported()) {
                mEndTouchArea.setBackgroundTintList(
                        ColorStateList.valueOf(color));
            }
        }

        private boolean updateClickActionBasedOnSelectionBehavior(MediaDevice device) {
            View.OnClickListener clickListener = Api34Impl.getClickListenerBasedOnSelectionBehavior(
                    device, mController, v -> onItemClick(v, device));
            updateFullItemClickListener(clickListener);
            return clickListener != null;
        }

        private void updateConnectionFailedStatusIcon() {
            mStatusIcon.setImageDrawable(
                    mContext.getDrawable(R.drawable.media_output_status_failed));
            mStatusIcon.setImageTintList(
                    ColorStateList.valueOf(mController.getColorItemContent()));
        }

        private void updateDeviceStatusIcon(Drawable drawable) {
            mStatusIcon.setImageDrawable(drawable);
            mStatusIcon.setImageTintList(
                    ColorStateList.valueOf(mController.getColorItemContent()));
            if (drawable instanceof AnimatedVectorDrawable) {
                ((AnimatedVectorDrawable) drawable).start();
            }
        }

        private void updateProgressBarColor() {
            mProgressBar.getIndeterminateDrawable().setTintList(
                    ColorStateList.valueOf(mController.getColorItemContent()));
        }

        public void updateEndClickArea(MediaDevice device, boolean isDeviceDeselectable) {
            mEndTouchArea.setOnClickListener(null);
            mEndTouchArea.setOnClickListener(
                    isDeviceDeselectable ? (v) -> mCheckBox.performClick() : null);
            mEndTouchArea.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            if (mController.isAdvancedLayoutSupported()) {
                mEndTouchArea.setBackgroundTintList(
                        ColorStateList.valueOf(mController.getColorItemBackground()));
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
                mTitleIcon.setImageTintList(
                        ColorStateList.valueOf(mController.getColorItemContent()));
                if (mController.isAdvancedLayoutSupported()) {
                    mIconAreaLayout.setBackgroundTintList(
                            ColorStateList.valueOf(mController.getColorItemBackground()));
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
            if (mController.isCurrentOutputDeviceHasSessionOngoing()) {
                showCustomEndSessionDialog(device);
            } else {
                transferOutput(device);
            }
        }

        private void transferOutput(MediaDevice device) {
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

        @VisibleForTesting
        void showCustomEndSessionDialog(MediaDevice device) {
            MediaSessionReleaseDialog mediaSessionReleaseDialog = new MediaSessionReleaseDialog(
                    mContext, () -> transferOutput(device), mController.getColorButtonBackground(),
                    mController.getColorItemContent());
            mediaSessionReleaseDialog.show();
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

    @RequiresApi(34)
    private static class Api34Impl {
        @DoNotInline
        static View.OnClickListener getClickListenerBasedOnSelectionBehavior(MediaDevice device,
                MediaOutputController controller, View.OnClickListener defaultTransferListener) {
            switch (device.getSelectionBehavior()) {
                case SELECTION_BEHAVIOR_NONE:
                    return null;
                case SELECTION_BEHAVIOR_TRANSFER:
                    return defaultTransferListener;
                case SELECTION_BEHAVIOR_GO_TO_APP:
                    return v -> controller.tryToLaunchInAppRoutingIntent(device.getId(), v);
            }
            return defaultTransferListener;
        }

        @DoNotInline
        static Drawable getDeviceStatusIconBasedOnSelectionBehavior(MediaDevice device,
                Context context) {
            switch (device.getSelectionBehavior()) {
                case SELECTION_BEHAVIOR_NONE:
                    return context.getDrawable(R.drawable.media_output_status_failed);
                case SELECTION_BEHAVIOR_TRANSFER:
                    return null;
                case SELECTION_BEHAVIOR_GO_TO_APP:
                    return context.getDrawable(R.drawable.media_output_status_help);
            }
            return null;
        }
    }
}
