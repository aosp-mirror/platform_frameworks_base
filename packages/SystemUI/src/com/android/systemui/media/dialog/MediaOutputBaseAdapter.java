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

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.WallpaperColors;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.RecyclerView;

import com.android.media.flags.Flags;
import com.android.settingslib.media.InputMediaDevice;
import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.res.R;

import java.util.List;

/**
 * Base adapter for media output dialog.
 */
public abstract class MediaOutputBaseAdapter extends
        RecyclerView.Adapter<RecyclerView.ViewHolder> {

    record OngoingSessionStatus(boolean host) {}

    record GroupStatus(Boolean selected, Boolean deselectable) {}

    enum ConnectionState {
        CONNECTED,
        CONNECTING,
        DISCONNECTED,
    }

    protected final MediaSwitchingController mController;

    private static final int UNMUTE_DEFAULT_VOLUME = 2;

    Context mContext;
    View mHolderView;
    boolean mIsDragging;
    int mCurrentActivePosition;
    private boolean mIsInitVolumeFirstTime;

    public MediaOutputBaseAdapter(MediaSwitchingController controller) {
        mController = controller;
        mIsDragging = false;
        mCurrentActivePosition = -1;
        mIsInitVolumeFirstTime = true;
    }

    /**
     * Refresh current dataset
     */
    public abstract void updateItems();

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        mContext = viewGroup.getContext();
        mHolderView = LayoutInflater.from(mContext).inflate(MediaItem.getMediaLayoutId(viewType),
                viewGroup, false);

        return null;
    }

    void updateColorScheme(WallpaperColors wallpaperColors, boolean isDarkTheme) {
        mController.setCurrentColorScheme(wallpaperColors, isDarkTheme);
    }

    boolean isCurrentlyConnected(MediaDevice device) {
        return TextUtils.equals(device.getId(),
                mController.getCurrentConnectedMediaDevice().getId())
                || (mController.getSelectedMediaDevice().size() == 1
                && isDeviceIncluded(mController.getSelectedMediaDevice(), device));
    }

    boolean isDeviceIncluded(List<MediaDevice> deviceList, MediaDevice targetDevice) {
        for (MediaDevice device : deviceList) {
            if (TextUtils.equals(device.getId(), targetDevice.getId())) {
                return true;
            }
        }
        return false;
    }

    boolean isDragging() {
        return mIsDragging;
    }

    int getCurrentActivePosition() {
        return mCurrentActivePosition;
    }

    public MediaSwitchingController getController() {
        return mController;
    }

    /**
     * ViewHolder for binding device view.
     */
    abstract class MediaDeviceBaseViewHolder extends RecyclerView.ViewHolder {

        private static final int ANIM_DURATION = 500;

        final ViewGroup mContainerLayout;
        final FrameLayout mItemLayout;
        final FrameLayout mIconAreaLayout;
        final TextView mTitleText;
        final TextView mSubTitleText;
        final TextView mVolumeValueText;
        final ImageView mTitleIcon;
        final ProgressBar mProgressBar;
        final ImageView mStatusIcon;
        final CheckBox mCheckBox;
        final ViewGroup mEndTouchArea;
        final ImageView mEndClickIcon;
        @VisibleForTesting
        MediaOutputSeekbar mSeekBar;
        private final float mInactiveRadius;
        private final float mActiveRadius;
        private String mDeviceId;
        private ValueAnimator mCornerAnimator;
        private ValueAnimator mVolumeAnimator;
        private int mLatestUpdateVolume = -1;

        MediaDeviceBaseViewHolder(View view) {
            super(view);
            mContainerLayout = view.requireViewById(R.id.device_container);
            mItemLayout = view.requireViewById(R.id.item_layout);
            mTitleText = view.requireViewById(R.id.title);
            mSubTitleText = view.requireViewById(R.id.subtitle);
            mTitleIcon = view.requireViewById(R.id.title_icon);
            mProgressBar = view.requireViewById(R.id.volume_indeterminate_progress);
            mSeekBar = view.requireViewById(R.id.volume_seekbar);
            mStatusIcon = view.requireViewById(R.id.media_output_item_status);
            mCheckBox = view.requireViewById(R.id.check_box);
            mEndTouchArea = view.requireViewById(R.id.end_action_area);
            mEndClickIcon = view.requireViewById(R.id.media_output_item_end_click_icon);
            mVolumeValueText = view.requireViewById(R.id.volume_value);
            mIconAreaLayout = view.requireViewById(R.id.icon_area);
            mInactiveRadius = mContext.getResources().getDimension(
                    R.dimen.media_output_dialog_background_radius);
            mActiveRadius = mContext.getResources().getDimension(
                    R.dimen.media_output_dialog_active_background_radius);
            initAnimator();
        }

        void onBind(MediaDevice device, int position) {
            mDeviceId = device.getId();
            mCheckBox.setVisibility(View.GONE);
            mStatusIcon.setVisibility(View.GONE);
            mEndTouchArea.setVisibility(View.GONE);
            mEndClickIcon.setVisibility(View.GONE);
            mEndTouchArea.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            mContainerLayout.setOnClickListener(null);
            mContainerLayout.setContentDescription(null);
            mTitleText.setTextColor(mController.getColorItemContent());
            mSubTitleText.setTextColor(mController.getColorItemContent());
            mVolumeValueText.setTextColor(mController.getColorItemContent());
            mIconAreaLayout.setBackground(null);
            mSeekBar.setProgressTintList(
                    ColorStateList.valueOf(mController.getColorSeekbarProgress()));
            enableFocusPropertyForView(mContainerLayout);
        }

        void updateTitle(CharSequence title) {
            mTitleText.setText(title);
        }

        void updateSeekBar(@NonNull MediaDevice device, ConnectionState connectionState,
                boolean restrictVolumeAdjustment, String contentDescription) {
            boolean showSeekBar =
                    connectionState == ConnectionState.CONNECTED && !restrictVolumeAdjustment;
            if (!mCornerAnimator.isRunning()) {
                if (showSeekBar) {
                    updateSeekbarProgressBackground();
                }
            }
            boolean isCurrentSeekbarInvisible = mSeekBar.getVisibility() == View.GONE;
            mSeekBar.setVisibility(showSeekBar ? View.VISIBLE : View.GONE);
            if (showSeekBar) {
                initSeekbar(device, isCurrentSeekbarInvisible);
                disableFocusPropertyForView(mContainerLayout);
                mSeekBar.setContentDescription(contentDescription);
            } else {
                enableFocusPropertyForView(mContainerLayout);
            }
        }

        void updateGroupSeekBar(String contentDescription) {
            updateSeekbarProgressBackground();
            boolean isCurrentSeekbarInvisible = mSeekBar.getVisibility() == View.GONE;
            mSeekBar.setVisibility(View.VISIBLE);
            initGroupSeekbar(isCurrentSeekbarInvisible);
            disableFocusPropertyForView(mContainerLayout);
            mSeekBar.setContentDescription(contentDescription);
        }

        private void disableFocusPropertyForView(View view) {
            view.setFocusable(false);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private void enableFocusPropertyForView(View view) {
            view.setFocusable(true);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }

        void updateSubtitle(@Nullable String subtitle) {
            if (subtitle == null) {
                mSubTitleText.setVisibility(View.GONE);
            } else {
                mSubTitleText.setText(subtitle);
                mSubTitleText.setVisibility(View.VISIBLE);
            }
        }

        protected void updateLoadingIndicator(ConnectionState connectionState) {
            if (connectionState == ConnectionState.CONNECTING) {
                mProgressBar.setVisibility(View.VISIBLE);
                mProgressBar.getIndeterminateDrawable().setTintList(
                        ColorStateList.valueOf(mController.getColorItemContent()));
            } else {
                mProgressBar.setVisibility(View.GONE);
            }
        }

        protected void updateItemBackground(ConnectionState connectionState) {
            boolean isConnected = connectionState == ConnectionState.CONNECTED;
            boolean isConnecting = connectionState == ConnectionState.CONNECTING;

            // Increase corner radius for a connected state.
            if (!mCornerAnimator.isRunning()) {  // FIXME(b/387576145): This is always True.
                int backgroundDrawableId =
                        isConnected ? R.drawable.media_output_item_background_active
                                : R.drawable.media_output_item_background;
                mItemLayout.setBackground(mContext.getDrawable(backgroundDrawableId).mutate());
            }

            // Connected or connecting state has a darker background.
            int backgroundColor = isConnected || isConnecting
                    ? mController.getColorConnectedItemBackground()
                    : mController.getColorItemBackground();
            mItemLayout.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        }

        protected void updateEndAreaVisibility(boolean showEndTouchArea, boolean isCheckbox) {
            mEndTouchArea.setVisibility(showEndTouchArea ? View.VISIBLE : View.GONE);
            if (showEndTouchArea) {
                mCheckBox.setVisibility(isCheckbox ? View.VISIBLE : View.GONE);
                mEndClickIcon.setVisibility(!isCheckbox ? View.VISIBLE : View.GONE);
            }
        }

        void updateSeekbarProgressBackground() {
            final ClipDrawable clipDrawable =
                    (ClipDrawable) ((LayerDrawable) mSeekBar.getProgressDrawable())
                            .findDrawableByLayerId(android.R.id.progress);
            final GradientDrawable progressDrawable =
                    (GradientDrawable) clipDrawable.getDrawable();
            progressDrawable.setCornerRadii(
                    new float[]{0, 0, mActiveRadius,
                            mActiveRadius,
                            mActiveRadius,
                            mActiveRadius, 0, 0});
        }

        private void initializeSeekbarVolume(
                @Nullable MediaDevice device, int currentVolume,
                boolean isCurrentSeekbarInvisible) {
            if (!mIsDragging) {
                if (mSeekBar.getVolume() != currentVolume && (mLatestUpdateVolume == -1
                        || currentVolume == mLatestUpdateVolume)) {
                    // Update only if volume of device and value of volume bar doesn't match.
                    // Check if response volume match with the latest request, to ignore obsolete
                    // response
                    if (isCurrentSeekbarInvisible && !mIsInitVolumeFirstTime) {
                        if (currentVolume == 0) {
                            updateMutedVolumeIcon(device);
                        } else {
                            updateUnmutedVolumeIcon(device);
                        }
                    } else {
                        if (!mVolumeAnimator.isStarted()) {
                            if (currentVolume == 0) {
                                updateMutedVolumeIcon(device);
                            } else {
                                updateUnmutedVolumeIcon(device);
                            }
                            mSeekBar.setVolume(currentVolume);
                            mLatestUpdateVolume = -1;
                        }
                    }
                } else if (currentVolume == 0) {
                    mSeekBar.resetVolume();
                    updateMutedVolumeIcon(device);
                }
                if (currentVolume == mLatestUpdateVolume) {
                    mLatestUpdateVolume = -1;
                }
            }
            if (mIsInitVolumeFirstTime) {
                mIsInitVolumeFirstTime = false;
            }
        }

        void initSeekbar(@NonNull MediaDevice device, boolean isCurrentSeekbarInvisible) {
            SeekBarVolumeControl volumeControl = new SeekBarVolumeControl() {
                @Override
                public int getVolume() {
                    return device.getCurrentVolume();
                }
                @Override
                public void setVolume(int volume) {
                    mController.adjustVolume(device, volume);
                }

                @Override
                public void onMute() {
                    mController.logInteractionUnmuteDevice(device);
                }
            };

            if (!mController.isVolumeControlEnabled(device)) {
                disableSeekBar();
            } else {
                enableSeekBar(volumeControl);
            }
            mSeekBar.setMaxVolume(device.getMaxVolume());
            final int currentVolume = device.getCurrentVolume();
            initializeSeekbarVolume(device, currentVolume, isCurrentSeekbarInvisible);

            mSeekBar.setOnSeekBarChangeListener(new MediaSeekBarChangedListener(
                    device, volumeControl) {
                @Override
                public void onStopTrackingTouch(SeekBar seekbar) {
                    super.onStopTrackingTouch(seekbar);
                    mController.logInteractionAdjustVolume(device);
                }
            });
        }

        // Initializes the seekbar for a group of devices.
        void initGroupSeekbar(boolean isCurrentSeekbarInvisible) {
            SeekBarVolumeControl volumeControl = new SeekBarVolumeControl() {
                @Override
                public int getVolume() {
                    return mController.getSessionVolume();
                }

                @Override
                public void setVolume(int volume) {
                    mController.adjustSessionVolume(volume);
                }

                @Override
                public void onMute() {}
            };

            if (!mController.isVolumeControlEnabledForSession()) {
                disableSeekBar();
            } else {
                enableSeekBar(volumeControl);
            }
            mSeekBar.setMaxVolume(mController.getSessionVolumeMax());

            final int currentVolume = mController.getSessionVolume();
            initializeSeekbarVolume(null, currentVolume, isCurrentSeekbarInvisible);
            mSeekBar.setOnSeekBarChangeListener(new MediaSeekBarChangedListener(
                    null, volumeControl) {
                @Override
                protected boolean shouldHandleProgressChanged() {
                    return true;
                }
            });
        }

        protected void updateTitleIcon(@NonNull MediaDevice device,
                ConnectionState connectionState, boolean restrictVolumeAdjustment) {
            if (connectionState == ConnectionState.CONNECTED) {
                if (restrictVolumeAdjustment) {
                    // Volume icon without a background that makes it looks like part of a seekbar.
                    updateVolumeIcon(device, false /* isMutedIcon */);
                } else {
                    updateUnmutedVolumeIcon(device);
                }
            } else {
                setUpDeviceIcon(device);
            }
        }

        void updateMutedVolumeIcon(@Nullable MediaDevice device) {
            mIconAreaLayout.setBackground(
                    mContext.getDrawable(R.drawable.media_output_item_background_active));
            updateVolumeIcon(device, true /* isMutedVolumeIcon */);
        }

        void updateUnmutedVolumeIcon(@Nullable MediaDevice device) {
            mIconAreaLayout.setBackground(
                    mContext.getDrawable(R.drawable.media_output_title_icon_area)
            );
            updateVolumeIcon(device, false /* isMutedVolumeIcon */);
        }

        void updateVolumeIcon(@Nullable MediaDevice device, boolean isMutedVolumeIcon) {
            boolean isInputMediaDevice = device instanceof InputMediaDevice;
            int id = getDrawableId(isInputMediaDevice, isMutedVolumeIcon);
            mTitleIcon.setImageDrawable(mContext.getDrawable(id));
            mTitleIcon.setImageTintList(ColorStateList.valueOf(mController.getColorItemContent()));
            mIconAreaLayout.setBackgroundTintList(
                    ColorStateList.valueOf(mController.getColorSeekbarProgress()));
        }

        @VisibleForTesting
        int getDrawableId(boolean isInputDevice, boolean isMutedVolumeIcon) {
            // Returns the microphone icon when the flag is enabled and the device is an input
            // device.
            if (Flags.enableAudioInputDeviceRoutingAndVolumeControl()
                    && isInputDevice) {
                return isMutedVolumeIcon ? R.drawable.ic_mic_off : R.drawable.ic_mic_26dp;
            }
            return isMutedVolumeIcon
                    ? R.drawable.media_output_icon_volume_off
                    : R.drawable.media_output_icon_volume;
        }

        void updateIconAreaClickListener(@Nullable View.OnClickListener listener) {
            mIconAreaLayout.setOnClickListener(listener);
        }

        private void initAnimator() {
            mCornerAnimator = ValueAnimator.ofFloat(mInactiveRadius, mActiveRadius);
            mCornerAnimator.setDuration(ANIM_DURATION);
            mCornerAnimator.setInterpolator(new LinearInterpolator());

            mVolumeAnimator = ValueAnimator.ofInt();
            mVolumeAnimator.addUpdateListener(animation -> {
                int value = (int) animation.getAnimatedValue();
                mSeekBar.setProgress(value);
            });
            mVolumeAnimator.setDuration(ANIM_DURATION);
            mVolumeAnimator.setInterpolator(new LinearInterpolator());
            mVolumeAnimator.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mSeekBar.setEnabled(false);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mSeekBar.setEnabled(true);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    mSeekBar.setEnabled(true);
                }

                @Override
                public void onAnimationRepeat(Animator animation) {

                }
            });
        }

        protected void disableSeekBar() {
            mSeekBar.setEnabled(false);
            mSeekBar.setOnTouchListener((v, event) -> true);
            updateIconAreaClickListener(null);
        }

        private void enableSeekBar(SeekBarVolumeControl volumeControl) {
            mSeekBar.setEnabled(true);

            mSeekBar.setOnTouchListener((v, event) -> false);
            updateIconAreaClickListener((v) -> {
                if (volumeControl.getVolume() == 0) {
                    mSeekBar.setVolume(UNMUTE_DEFAULT_VOLUME);
                    volumeControl.setVolume(UNMUTE_DEFAULT_VOLUME);
                    updateUnmutedVolumeIcon(null);
                    mIconAreaLayout.setOnTouchListener(((iconV, event) -> false));
                } else {
                    volumeControl.onMute();
                    mSeekBar.resetVolume();
                    volumeControl.setVolume(0);
                    updateMutedVolumeIcon(null);
                    mIconAreaLayout.setOnTouchListener(((iconV, event) -> {
                        mSeekBar.dispatchTouchEvent(event);
                        return false;
                    }));
                }
            });

        }

        protected void setUpDeviceIcon(@NonNull MediaDevice device) {
            ThreadUtils.postOnBackgroundThread(() -> {
                Icon icon = mController.getDeviceIconCompat(device).toIcon(mContext);
                ThreadUtils.postOnMainThread(() -> {
                    if (!TextUtils.equals(mDeviceId, device.getId())) {
                        return;
                    }
                    mTitleIcon.setImageIcon(icon);
                    mTitleIcon.setImageTintList(
                            ColorStateList.valueOf(mController.getColorItemContent()));
                });
            });
        }

        interface SeekBarVolumeControl {
            int getVolume();
            void setVolume(int volume);
            void onMute();
        }

        private abstract class MediaSeekBarChangedListener
                implements SeekBar.OnSeekBarChangeListener {
            boolean mStartFromMute = false;
            private MediaDevice mMediaDevice;
            private SeekBarVolumeControl mVolumeControl;

            MediaSeekBarChangedListener(MediaDevice device, SeekBarVolumeControl volumeControl) {
                mMediaDevice = device;
                mVolumeControl = volumeControl;
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!shouldHandleProgressChanged() || !fromUser) {
                    return;
                }

                final String percentageString = mContext.getResources().getString(
                        R.string.media_output_dialog_volume_percentage,
                        mSeekBar.getPercentage());
                mVolumeValueText.setText(percentageString);

                if (mStartFromMute) {
                    updateUnmutedVolumeIcon(mMediaDevice);
                    mStartFromMute = false;
                }

                int seekBarVolume = MediaOutputSeekbar.scaleProgressToVolume(progress);
                if (seekBarVolume != mVolumeControl.getVolume()) {
                    mLatestUpdateVolume = seekBarVolume;
                    mVolumeControl.setVolume(seekBarVolume);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mTitleIcon.setVisibility(View.INVISIBLE);
                mVolumeValueText.setVisibility(View.VISIBLE);
                int currentVolume = MediaOutputSeekbar.scaleProgressToVolume(
                        seekBar.getProgress());
                mStartFromMute = (currentVolume == 0);
                mIsDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int currentVolume = MediaOutputSeekbar.scaleProgressToVolume(
                        seekBar.getProgress());
                if (currentVolume == 0) {
                    seekBar.setProgress(0);
                    updateMutedVolumeIcon(mMediaDevice);
                } else {
                    updateUnmutedVolumeIcon(mMediaDevice);
                }
                mTitleIcon.setVisibility(View.VISIBLE);
                mVolumeValueText.setVisibility(View.GONE);
                mIsDragging = false;
            }
            protected boolean shouldHandleProgressChanged() {
                return mMediaDevice != null;
            }
        };
    }
}
