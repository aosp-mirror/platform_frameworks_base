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
    private static final float DEVICE_DISCONNECTED_ALPHA = 0.5f;
    private static final float DEVICE_CONNECTED_ALPHA = 1f;
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
            boolean isCurrentSeekbarInvisible = mSeekBar.getVisibility() == View.GONE;
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

            if (mCurrentActivePosition == position) {
                mCurrentActivePosition = -1;
            }
            mItemLayout.setVisibility(View.VISIBLE);
            mStatusIcon.setVisibility(View.GONE);
            enableFocusPropertyForView(mContainerLayout);

            if (mController.isAnyDeviceTransferring()) {
                if (device.getState() == MediaDeviceState.STATE_CONNECTING
                        && !mController.hasAdjustVolumeUserRestriction()) {
                    setUpDeviceIcon(device);
                    updateProgressBarColor();
                    setSingleLineLayout(device.getName(), false /* showSeekBar*/,
                            true /* showProgressBar */, false /* showCheckBox */,
                            false /* showEndTouchArea */);
                } else {
                    setUpDeviceIcon(device);
                    setSingleLineLayout(device.getName());
                }
            } else {
                // Set different layout for each device
                if (device.isMutingExpectedDevice()
                        && !mController.isCurrentConnectedDeviceRemote()) {
                    updateUnmutedVolumeIcon(device);
                    mCurrentActivePosition = position;
                    updateFullItemClickListener(v -> onItemClick(v, device));
                    setSingleLineLayout(device.getName());
                    initFakeActiveDevice(device);
                } else if (mShouldGroupSelectedMediaItems
                        && mController.getSelectedMediaDevice().size() > 1
                        && isDeviceIncluded(mController.getSelectedMediaDevice(), device)) {
                    if (!mediaItem.isFirstDeviceInGroup()) {
                        mItemLayout.setVisibility(View.GONE);
                        mEndTouchArea.setVisibility(View.GONE);
                    } else {
                        String sessionName = mController.getSessionName().toString();
                        updateUnmutedVolumeIcon(null);
                        updateEndClickAreaWithIcon(
                                v -> {
                                    mShouldGroupSelectedMediaItems = false;
                                    notifyDataSetChanged();
                                },
                                R.drawable.media_output_item_expand_group,
                                R.string.accessibility_expand_group);
                        disableFocusPropertyForView(mContainerLayout);
                        setUpContentDescriptionForView(mSeekBar, mContext.getString(
                                R.string.accessibility_cast_name, sessionName));
                        setSingleLineLayout(sessionName, true /* showSeekBar */,
                                false /* showProgressBar */, false /* showCheckBox */,
                                true /* showEndTouchArea */);
                        initGroupSeekbar(isCurrentSeekbarInvisible);
                    }
                } else if (device.hasSubtext()) {
                    boolean isActiveWithOngoingSession =
                            device.hasOngoingSession() && (currentlyConnected || isSelected);
                    boolean isHost = device.isHostForOngoingSession()
                            && isActiveWithOngoingSession;
                    if (isActiveWithOngoingSession) {
                        mCurrentActivePosition = position;
                        updateUnmutedVolumeIcon(device);
                        mSubTitleText.setText(device.getSubtextString());
                        updateContentAlpha(DEVICE_CONNECTED_ALPHA);
                        updateEndClickAreaAsSessionEditing(device,
                                isHost ? R.drawable.media_output_status_edit_session
                                        : R.drawable.ic_sound_bars_anim);
                        setTwoLineLayout(device.getName() /* title */,
                                true /* showSeekBar */, false /* showProgressBar */,
                                true /* showSubtitle */, false /* showStatus */,
                                true /* showEndTouchArea */);
                        initSeekbar(device, isCurrentSeekbarInvisible);
                    } else {
                        if (currentlyConnected) {
                            mCurrentActivePosition = position;
                            updateUnmutedVolumeIcon(device);
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
                        updateContentAlpha(
                                updateClickActionBasedOnSelectionBehavior(device)
                                        ? DEVICE_CONNECTED_ALPHA : DEVICE_DISCONNECTED_ALPHA);
                        setTwoLineLayout(device.getName(),
                                currentlyConnected  /* showSeekBar */,
                                false /* showProgressBar */, true /* showSubtitle */,
                                deviceStatusIcon != null /* showStatus */);
                    }
                } else if (device.getState() == MediaDeviceState.STATE_CONNECTING_FAILED) {
                    setUpDeviceIcon(device);
                    updateConnectionFailedStatusIcon();
                    mSubTitleText.setText(R.string.media_output_dialog_connect_failed);
                    updateFullItemClickListener(v -> onItemClick(v, device));
                    setTwoLineLayout(device.getName(), false /* showSeekBar */,
                            false /* showProgressBar */, true /* showSubtitle */,
                            true /* showStatus */);
                } else if (device.getState() == MediaDeviceState.STATE_GROUPING) {
                    setUpDeviceIcon(device);
                    updateProgressBarColor();
                    setSingleLineLayout(device.getName(), false /* showSeekBar*/,
                            true /* showProgressBar */, false /* showCheckBox */,
                            false /* showEndTouchArea */);
                } else if (mController.getSelectedMediaDevice().size() > 1 && isSelected) {
                    // selected device in group
                    boolean showEndArea =
                            !Flags.enableOutputSwitcherSessionGrouping() || isDeselectable;
                    updateUnmutedVolumeIcon(device);
                    updateEndAreaForGroupCheckbox(device, true /* isSelected */, isDeselectable);
                    disableFocusPropertyForView(mContainerLayout);
                    setUpContentDescriptionForView(mSeekBar, device);
                    setSingleLineLayout(device.getName(), true /* showSeekBar */,
                            false /* showProgressBar */, true /* showCheckBox */,
                            showEndArea /* showEndTouchArea */);
                    initSeekbar(device, isCurrentSeekbarInvisible);
                } else if (!mController.hasAdjustVolumeUserRestriction()
                        && currentlyConnected) {
                    // single selected device
                    if (isMutingExpectedDeviceExist
                            && !mController.isCurrentConnectedDeviceRemote()) {
                        // mark as disconnected and set special click listener
                        setUpDeviceIcon(device);
                        updateFullItemClickListener(v -> cancelMuteAwaitConnection());
                        setSingleLineLayout(device.getName());
                    } else if (device.hasOngoingSession()) {
                        mCurrentActivePosition = position;
                        updateUnmutedVolumeIcon(device);
                        updateEndClickAreaAsSessionEditing(device, device.isHostForOngoingSession()
                                ? R.drawable.media_output_status_edit_session
                                : R.drawable.ic_sound_bars_anim);
                        mEndClickIcon.setVisibility(View.VISIBLE);
                        setSingleLineLayout(device.getName(), true /* showSeekBar */,
                                false /* showProgressBar */, false /* showCheckBox */,
                                true /* showEndTouchArea */);
                        initSeekbar(device, isCurrentSeekbarInvisible);
                    } else if (mController.isCurrentConnectedDeviceRemote()
                            && !mController.getSelectableMediaDevice().isEmpty()) {
                        //If device is connected and there's other selectable devices, layout as
                        // one of selected devices.
                        updateUnmutedVolumeIcon(device);
                        updateEndAreaForGroupCheckbox(device, true /* isSelected */,
                                isDeselectable);
                        disableFocusPropertyForView(mContainerLayout);
                        setUpContentDescriptionForView(mSeekBar, device);
                        setSingleLineLayout(device.getName(), true /* showSeekBar */,
                                false /* showProgressBar */, true /* showCheckBox */,
                                true /* showEndTouchArea */);
                        initSeekbar(device, isCurrentSeekbarInvisible);
                    } else {
                        updateUnmutedVolumeIcon(device);
                        disableFocusPropertyForView(mContainerLayout);
                        setUpContentDescriptionForView(mSeekBar, device);
                        mCurrentActivePosition = position;
                        setSingleLineLayout(device.getName(), true /* showSeekBar */,
                                false /* showProgressBar */, false /* showCheckBox */,
                                false /* showEndTouchArea */);
                        initSeekbar(device, isCurrentSeekbarInvisible);
                    }
                } else if (isSelectable) {
                    //groupable device
                    setUpDeviceIcon(device);
                    updateEndAreaForGroupCheckbox(device, false /* isSelected */,
                            true /* isDeselectable */);
                    if (!Flags.disableTransferWhenAppsDoNotSupport()
                            || isTransferable
                            || hasRouteListingPreferenceItem) {
                        updateFullItemClickListener(v -> onItemClick(v, device));
                    }
                    setSingleLineLayout(device.getName(), false /* showSeekBar */,
                            false /* showProgressBar */, true /* showCheckBox */,
                            true /* showEndTouchArea */);
                } else {
                    setUpDeviceIcon(device);
                    setSingleLineLayout(device.getName());
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
                    updateContentAlpha(
                            updateClickActionBasedOnSelectionBehavior(device)
                                    ? DEVICE_CONNECTED_ALPHA : DEVICE_DISCONNECTED_ALPHA);
                }
            }
        }

        public void setCheckBoxColor(CheckBox checkBox, int color) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            int[] colors = {color, color};
            CompoundButtonCompat.setButtonTintList(checkBox, new
                    ColorStateList(states, colors));
        }

        private void updateContentAlpha(float alphaValue) {
            mTitleIcon.setAlpha(alphaValue);
            mTitleText.setAlpha(alphaValue);
            mSubTitleText.setAlpha(alphaValue);
            mStatusIcon.setAlpha(alphaValue);
        }

        private void updateEndClickAreaAsSessionEditing(MediaDevice device, @DrawableRes int id) {
            updateEndClickAreaWithIcon(
                    v -> mController.tryToLaunchInAppRoutingIntent(device.getId(), v),
                    id,
                    R.string.accessibility_open_application);
        }

        private void updateEndClickAreaWithIcon(View.OnClickListener clickListener,
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
                setUpContentDescriptionForView(
                        mEndClickIcon, mContext.getString(accessibilityStringId));
            }
        }

        public void updateEndAreaColor(int color) {
            mEndTouchArea.setBackgroundTintList(
                    ColorStateList.valueOf(color));
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

        public void updateEndAreaForGroupCheckbox(MediaDevice device, boolean isSelected,
                boolean isDeselectable) {
            mEndTouchArea.setOnClickListener(null);
            mEndTouchArea.setOnClickListener(
                    isDeselectable ? (v) -> mCheckBox.performClick() : null);
            mEndTouchArea.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            updateEndAreaColor(isSelected ? mController.getColorSeekbarProgress()
                    : mController.getColorItemBackground());
            setUpContentDescriptionForView(mEndTouchArea, device);
            mCheckBox.setOnCheckedChangeListener(null);
            mCheckBox.setChecked(isSelected);
            mCheckBox.setOnCheckedChangeListener(
                    isDeselectable ? (buttonView, isChecked) -> onGroupActionTriggered(!isSelected,
                            device) : null);
            mCheckBox.setEnabled(isDeselectable);
            setCheckBoxColor(mCheckBox, mController.getColorItemContent());
        }

        private void updateFullItemClickListener(View.OnClickListener listener) {
            mContainerLayout.setOnClickListener(listener);
            updateIconAreaClickListener(listener);
        }

        /** Binds a ViewHolder for a "Connect a device" item. */
        void onBindPairNewDevice() {
            mTitleText.setTextColor(mController.getColorItemContent());
            mCheckBox.setVisibility(View.GONE);
            setSingleLineLayout(mContext.getText(R.string.media_output_dialog_pairing_new));
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

        private void disableFocusPropertyForView(View view) {
            view.setFocusable(false);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private void enableFocusPropertyForView(View view) {
            view.setFocusable(true);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }

        private void setUpContentDescriptionForView(View view, MediaDevice device) {
            setUpContentDescriptionForView(
                    view,
                    mContext.getString(device.getDeviceType()
                            == MediaDevice.MediaDeviceType.TYPE_BLUETOOTH_DEVICE
                            ? R.string.accessibility_bluetooth_name
                            : R.string.accessibility_cast_name, device.getName()));
        }

        protected void setUpContentDescriptionForView(View view, String description) {
            view.setContentDescription(description);
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
