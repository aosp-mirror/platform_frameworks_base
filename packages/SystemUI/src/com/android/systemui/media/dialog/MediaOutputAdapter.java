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

import android.annotation.DrawableRes;
import android.annotation.StringRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.DoNotInline;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.widget.CompoundButtonCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.media.flags.Flags;
import com.android.settingslib.media.LocalMediaManager.MediaDeviceState;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.res.R;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Adapter for media output dialog.
 */
public class MediaOutputAdapter extends MediaOutputBaseAdapter {

    private static final String TAG = "MediaOutputAdapter";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final float DEVICE_DISABLED_ALPHA = 0.5f;
    private static final float DEVICE_ACTIVE_ALPHA = 1f;
    protected List<MediaItem> mMediaItemList = new CopyOnWriteArrayList<>();
    private boolean mShouldGroupSelectedMediaItems = Flags.enableOutputSwitcherSessionGrouping();

    public MediaOutputAdapter(MediaSwitchingController controller) {
        super(controller);
        setHasStableIds(true);
    }

    @Override
    public void updateItems() {
        mMediaItemList.clear();
        mMediaItemList.addAll(mController.getMediaItemList());
        if (mShouldGroupSelectedMediaItems) {
            if (mController.getSelectedMediaDevice().size() == 1) {
                // Don't group devices if initially there isn't more than one selected.
                mShouldGroupSelectedMediaItems = false;
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        super.onCreateViewHolder(viewGroup, viewType);
        switch (viewType) {
            case MediaItem.MediaItemType.TYPE_GROUP_DIVIDER:
                return new MediaGroupDividerViewHolder(mHolderView);
            case MediaItem.MediaItemType.TYPE_PAIR_NEW_DEVICE:
            case MediaItem.MediaItemType.TYPE_DEVICE:
            default:
                return new MediaDeviceViewHolder(mHolderView);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
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
                ((MediaDeviceViewHolder) viewHolder).onBindPairNewDevice();
                break;
            case MediaItem.MediaItemType.TYPE_DEVICE:
                ((MediaDeviceViewHolder) viewHolder).onBind(
                        currentMediaItem,
                        position);
                break;
            default:
                Log.d(TAG, "Incorrect position: " + position);
        }
    }

    @Override
    public long getItemId(int position) {
        if (position >= mMediaItemList.size()) {
            Log.d(TAG, "Incorrect position for item id: " + position);
            return position;
        }
        MediaItem currentMediaItem = mMediaItemList.get(position);
        return currentMediaItem.getMediaDevice().isPresent()
                ? currentMediaItem.getMediaDevice().get().getId().hashCode()
                : position;
    }

    @Override
    public int getItemViewType(int position) {
        if (position >= mMediaItemList.size()) {
            Log.d(TAG, "Incorrect position for item type: " + position);
            return MediaItem.MediaItemType.TYPE_GROUP_DIVIDER;
        }
        return mMediaItemList.get(position).getMediaItemType();
    }

    @Override
    public int getItemCount() {
        return mMediaItemList.size();
    }

    class MediaDeviceViewHolder extends MediaDeviceBaseViewHolder {

        MediaDeviceViewHolder(View view) {
            super(view);
        }

        void onBind(MediaItem mediaItem, int position) {
            MediaDevice device = mediaItem.getMediaDevice().get();
            super.onBind(device, position);
            boolean isMutingExpectedDeviceExist = mController.hasMutingExpectedDevice();
            final boolean currentlyConnected = isCurrentlyConnected(device);
            boolean isSelected = isDeviceIncluded(mController.getSelectedMediaDevice(), device);
            boolean isDeselectable =
                    isDeviceIncluded(mController.getDeselectableMediaDevice(), device);
            boolean isSelectable = isDeviceIncluded(mController.getSelectableMediaDevice(), device);
            boolean isTransferable =
                    isDeviceIncluded(mController.getTransferableMediaDevices(), device);
            boolean hasRouteListingPreferenceItem = device.hasRouteListingPreferenceItem();

            if (DEBUG) {
                Log.d(
                        TAG,
                        "["
                                + position
                                + "] "
                                + device.getName()
                                + " ["
                                + (isDeselectable ? "deselectable" : "")
                                + "] ["
                                + (isSelected ? "selected" : "")
                                + "] ["
                                + (isSelectable ? "selectable" : "")
                                + "] ["
                                + (isTransferable ? "transferable" : "")
                                + "] ["
                                + (hasRouteListingPreferenceItem ? "hasListingPreference" : "")
                                + "]");
            }

            boolean isDeviceGroup = false;
            GroupStatus groupStatus = null;
            OngoingSessionStatus ongoingSessionStatus = null;
            ConnectionState connectionState = ConnectionState.DISCONNECTED;
            boolean restrictVolumeAdjustment = mController.hasAdjustVolumeUserRestriction();
            String subtitle = null;
            Drawable deviceStatusIcon = null;
            boolean deviceDisabled = false;
            View.OnClickListener clickListener = null;

            if (mCurrentActivePosition == position) {
                mCurrentActivePosition = -1;
            }
            mItemLayout.setVisibility(View.VISIBLE);

            if (mController.isAnyDeviceTransferring()) {
                if (device.getState() == MediaDeviceState.STATE_CONNECTING) {
                    connectionState = ConnectionState.CONNECTING;
                }
            } else {
                // Set different layout for each device
                if (device.isMutingExpectedDevice()
                        && !mController.isCurrentConnectedDeviceRemote()) {
                    connectionState = ConnectionState.CONNECTED;
                    restrictVolumeAdjustment = true;
                    mCurrentActivePosition = position;
                    clickListener = v -> onItemClick(v, device);
                } else if (mShouldGroupSelectedMediaItems
                        && mController.getSelectedMediaDevice().size() > 1
                        && isDeviceIncluded(mController.getSelectedMediaDevice(), device)) {
                    if (!mediaItem.isFirstDeviceInGroup()) {
                        mItemLayout.setVisibility(View.GONE);
                        return;
                    } else {
                        isDeviceGroup = true;
                    }
                } else if (device.hasSubtext()) {
                    subtitle = device.getSubtextString();
                    boolean isActiveWithOngoingSession =
                            device.hasOngoingSession() && (currentlyConnected || isSelected);
                    if (isActiveWithOngoingSession) {
                        mCurrentActivePosition = position;
                        ongoingSessionStatus = new OngoingSessionStatus(
                                device.isHostForOngoingSession());
                        connectionState = ConnectionState.CONNECTED;
                    } else {
                        if (currentlyConnected) {
                            mCurrentActivePosition = position;
                            connectionState = ConnectionState.CONNECTED;
                        }
                        clickListener = getClickListenerBasedOnSelectionBehavior(device);
                        deviceDisabled = clickListener == null;
                        deviceStatusIcon = getDeviceStatusIcon(device, device.hasOngoingSession());
                    }
                } else if (device.getState() == MediaDeviceState.STATE_CONNECTING_FAILED) {
                    deviceStatusIcon = mContext.getDrawable(R.drawable.media_output_status_failed);
                    subtitle = mContext.getString(R.string.media_output_dialog_connect_failed);
                    clickListener = v -> onItemClick(v, device);
                } else if (device.getState() == MediaDeviceState.STATE_GROUPING) {
                    connectionState = ConnectionState.CONNECTING;
                } else if (mController.getSelectedMediaDevice().size() > 1 && isSelected) {
                    // selected device in group
                    groupStatus = new GroupStatus(
                            true /* selected */,
                            isDeselectable /* deselectable */);
                    connectionState = ConnectionState.CONNECTED;
                } else if (currentlyConnected) {
                    // single selected device
                    if (isMutingExpectedDeviceExist
                            && !mController.isCurrentConnectedDeviceRemote()) {
                        // mark as disconnected and set special click listener
                        clickListener = v -> cancelMuteAwaitConnection();
                    } else if (device.hasOngoingSession()) {
                        mCurrentActivePosition = position;
                        ongoingSessionStatus = new OngoingSessionStatus(
                                device.isHostForOngoingSession());
                        connectionState = ConnectionState.CONNECTED;
                    } else if (mController.isCurrentConnectedDeviceRemote()
                            && !mController.getSelectableMediaDevice().isEmpty()) {
                        //If device is connected and there's other selectable devices, layout as
                        // one of selected devices.
                        groupStatus = new GroupStatus(
                                true /* selected */,
                                isDeselectable /* isDeselectable */);
                        connectionState = ConnectionState.CONNECTED;
                    } else {
                        mCurrentActivePosition = position;
                        connectionState = ConnectionState.CONNECTED;
                    }
                } else if (isSelectable) {
                    //groupable device
                    groupStatus = new GroupStatus(false /* selected */, true /* deselectable */);
                    if (!Flags.disableTransferWhenAppsDoNotSupport()
                            || isTransferable
                            || hasRouteListingPreferenceItem) {
                        clickListener = v -> onItemClick(v, device);
                    }
                    deviceDisabled = clickListener == null;
                } else {
                    deviceStatusIcon = getDeviceStatusIcon(device, device.hasOngoingSession());
                    clickListener = getClickListenerBasedOnSelectionBehavior(device);
                    deviceDisabled = clickListener == null;
                }
            }

            if (isDeviceGroup) {
                String sessionName = mController.getSessionName() == null ? ""
                        : mController.getSessionName().toString();
                updateTitle(sessionName);
                updateUnmutedVolumeIcon(null /* device */);
                updateGroupSeekBar(getGroupItemContentDescription(sessionName));
                updateEndAreaForDeviceGroup();
                updateItemBackground(ConnectionState.CONNECTED);
            } else {
                updateTitle(device.getName());
                updateTitleIcon(device, connectionState, restrictVolumeAdjustment);
                updateSeekBar(device, connectionState, restrictVolumeAdjustment,
                        getDeviceItemContentDescription(device));
                updateEndArea(device, connectionState, groupStatus, ongoingSessionStatus);
                updateLoadingIndicator(connectionState);
                updateFullItemClickListener(clickListener);
                updateContentAlpha(deviceDisabled);
                updateSubtitle(subtitle);
                updateDeviceStatusIcon(deviceStatusIcon);
                updateItemBackground(connectionState);
            }
        }

        /** Renders the right side round pill button / checkbox. */
        private void updateEndArea(@NonNull MediaDevice device, ConnectionState connectionState,
                @Nullable GroupStatus groupStatus,
                @Nullable OngoingSessionStatus ongoingSessionStatus) {
            boolean showEndArea = false;
            boolean isCheckbox = false;
            // If both group status and the ongoing session status are present, only the ongoing
            // session controls are displayed. The current layout design doesn't allow both group
            // and ongoing session controls to be rendered simultaneously.
            if (ongoingSessionStatus != null && connectionState == ConnectionState.CONNECTED) {
                showEndArea = true;
                updateEndAreaForOngoingSession(device, ongoingSessionStatus.host());
            } else if (groupStatus != null && shouldShowGroupCheckbox(groupStatus)) {
                showEndArea = true;
                isCheckbox = true;
                updateEndAreaForGroupCheckBox(device, groupStatus);
            }
            updateEndAreaVisibility(showEndArea, isCheckbox);
        }

        private boolean shouldShowGroupCheckbox(@NonNull GroupStatus groupStatus) {
            if (Flags.enableOutputSwitcherSessionGrouping()) {
                return isGroupCheckboxEnabled(groupStatus);
            }
            return true;
        }

        private boolean isGroupCheckboxEnabled(@NonNull GroupStatus groupStatus) {
            boolean disabled = groupStatus.selected() && !groupStatus.deselectable();
            return !disabled;
        }

        public void setCheckBoxColor(CheckBox checkBox, int color) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            int[] colors = {color, color};
            CompoundButtonCompat.setButtonTintList(checkBox, new
                    ColorStateList(states, colors));
        }

        private void updateContentAlpha(boolean deviceDisabled) {
            float alphaValue = deviceDisabled ? DEVICE_DISABLED_ALPHA : DEVICE_ACTIVE_ALPHA;
            mTitleIcon.setAlpha(alphaValue);
            mTitleText.setAlpha(alphaValue);
            mSubTitleText.setAlpha(alphaValue);
            mStatusIcon.setAlpha(alphaValue);
        }

        private void updateEndAreaForDeviceGroup() {
            updateEndAreaWithIcon(
                    v -> {
                        mShouldGroupSelectedMediaItems = false;
                        notifyDataSetChanged();
                    },
                    R.drawable.media_output_item_expand_group,
                    R.string.accessibility_expand_group);
            updateEndAreaVisibility(true /* showEndArea */, false /* isCheckbox */);
        }

        private void updateEndAreaForOngoingSession(@NonNull MediaDevice device, boolean isHost) {
            updateEndAreaWithIcon(
                    v -> mController.tryToLaunchInAppRoutingIntent(device.getId(), v),
                    isHost ? R.drawable.media_output_status_edit_session
                            : R.drawable.ic_sound_bars_anim,
                    R.string.accessibility_open_application);
        }

        private void updateEndAreaWithIcon(View.OnClickListener clickListener,
                @DrawableRes int iconDrawableId,
                @StringRes int accessibilityStringId) {
            updateEndAreaColor(mController.getColorSeekbarProgress());
            mEndClickIcon.setImageTintList(
                    ColorStateList.valueOf(mController.getColorItemContent()));
            mEndClickIcon.setOnClickListener(clickListener);
            mEndTouchArea.setOnClickListener(v -> mEndClickIcon.performClick());
            Drawable drawable = mContext.getDrawable(iconDrawableId);
            mEndClickIcon.setImageDrawable(drawable);
            if (drawable instanceof AnimatedVectorDrawable) {
                ((AnimatedVectorDrawable) drawable).start();
            }
            if (Flags.enableOutputSwitcherSessionGrouping()) {
                mEndClickIcon.setContentDescription(mContext.getString(accessibilityStringId));
            }
        }

        public void updateEndAreaColor(int color) {
            mEndTouchArea.setBackgroundTintList(
                    ColorStateList.valueOf(color));
        }

        @Nullable
        private View.OnClickListener getClickListenerBasedOnSelectionBehavior(
                @NonNull MediaDevice device) {
            return Api34Impl.getClickListenerBasedOnSelectionBehavior(
                    device, mController, v -> onItemClick(v, device));
        }

        @Nullable
        private Drawable getDeviceStatusIcon(MediaDevice device, boolean hasOngoingSession) {
            if (hasOngoingSession) {
                return mContext.getDrawable(R.drawable.ic_sound_bars_anim);
            } else {
                return Api34Impl.getDeviceStatusIconBasedOnSelectionBehavior(device, mContext);
            }
        }

        void updateDeviceStatusIcon(@Nullable Drawable deviceStatusIcon) {
            if (deviceStatusIcon == null) {
                mStatusIcon.setVisibility(View.GONE);
            } else {
                mStatusIcon.setImageDrawable(deviceStatusIcon);
                mStatusIcon.setImageTintList(
                        ColorStateList.valueOf(mController.getColorItemContent()));
                if (deviceStatusIcon instanceof AnimatedVectorDrawable) {
                    ((AnimatedVectorDrawable) deviceStatusIcon).start();
                }
                mStatusIcon.setVisibility(View.VISIBLE);
            }
        }

        public void updateEndAreaForGroupCheckBox(@NonNull MediaDevice device,
                @NonNull GroupStatus groupStatus) {
            boolean isEnabled = isGroupCheckboxEnabled(groupStatus);
            mEndTouchArea.setOnClickListener(
                    isEnabled ? (v) -> mCheckBox.performClick() : null);
            mEndTouchArea.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            updateEndAreaColor(groupStatus.selected() ? mController.getColorSeekbarProgress()
                    : mController.getColorItemBackground());
            mEndTouchArea.setContentDescription(getDeviceItemContentDescription(device));
            mCheckBox.setChecked(groupStatus.selected());
            mCheckBox.setOnCheckedChangeListener(
                    isEnabled ? (buttonView, isChecked) -> onGroupActionTriggered(
                            !groupStatus.selected(), device) : null);
            mCheckBox.setEnabled(isEnabled);
            setCheckBoxColor(mCheckBox, mController.getColorItemContent());
        }

        private void updateFullItemClickListener(@Nullable View.OnClickListener listener) {
            mContainerLayout.setOnClickListener(listener);
            updateIconAreaClickListener(listener);
        }

        /** Binds a ViewHolder for a "Connect a device" item. */
        void onBindPairNewDevice() {
            mTitleText.setTextColor(mController.getColorItemContent());
            mCheckBox.setVisibility(View.GONE);
            updateTitle(mContext.getText(R.string.media_output_dialog_pairing_new));
            updateItemBackground(ConnectionState.DISCONNECTED);
            final Drawable addDrawable = mContext.getDrawable(R.drawable.ic_add);
            mTitleIcon.setImageDrawable(addDrawable);
            mTitleIcon.setImageTintList(
                    ColorStateList.valueOf(mController.getColorItemContent()));
            mContainerLayout.setOnClickListener(mController::launchBluetoothPairing);
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

        private String getDeviceItemContentDescription(@NonNull MediaDevice device) {
            return mContext.getString(
                    device.getDeviceType() == MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE
                            ? R.string.accessibility_bluetooth_name
                            : R.string.accessibility_cast_name, device.getName());
        }

        private String getGroupItemContentDescription(String sessionName) {
            return mContext.getString(R.string.accessibility_cast_name, sessionName);
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
        static View.OnClickListener getClickListenerBasedOnSelectionBehavior(
                MediaDevice device,
                MediaSwitchingController controller,
                View.OnClickListener defaultTransferListener) {
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
        @Nullable
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
