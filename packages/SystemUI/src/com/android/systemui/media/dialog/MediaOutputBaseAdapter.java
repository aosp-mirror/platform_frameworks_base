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

import static com.android.systemui.media.dialog.MediaOutputSeekbar.VOLUME_PERCENTAGE_SCALE_SIZE;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.DrawableRes;
import android.app.WallpaperColors;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.res.R;

import java.util.List;

/**
 * Base adapter for media output dialog.
 */
public abstract class MediaOutputBaseAdapter extends
        RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final int CUSTOMIZED_ITEM_PAIR_NEW = 1;
    static final int CUSTOMIZED_ITEM_GROUP = 2;
    static final int CUSTOMIZED_ITEM_DYNAMIC_GROUP = 3;

    protected final MediaOutputController mController;

    private static final int UNMUTE_DEFAULT_VOLUME = 2;

    Context mContext;
    View mHolderView;
    boolean mIsDragging;
    int mCurrentActivePosition;
    private boolean mIsInitVolumeFirstTime;

    public MediaOutputBaseAdapter(MediaOutputController controller) {
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

    CharSequence getItemTitle(MediaDevice device) {
        return device.getName();
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

    public MediaOutputController getController() {
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
        final TextView mTwoLineTitleText;
        final TextView mSubTitleText;
        final TextView mVolumeValueText;
        final ImageView mTitleIcon;
        final ProgressBar mProgressBar;
        final LinearLayout mTwoLineLayout;
        final ImageView mStatusIcon;
        final CheckBox mCheckBox;
        final ViewGroup mEndTouchArea;
        final ImageView mEndClickIcon;
        @VisibleForTesting
        MediaOutputSeekbar mSeekBar;
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
            mTwoLineLayout = view.requireViewById(R.id.two_line_layout);
            mTwoLineTitleText = view.requireViewById(R.id.two_line_title);
            mTitleIcon = view.requireViewById(R.id.title_icon);
            mProgressBar = view.requireViewById(R.id.volume_indeterminate_progress);
            mSeekBar = view.requireViewById(R.id.volume_seekbar);
            mStatusIcon = view.requireViewById(R.id.media_output_item_status);
            mCheckBox = view.requireViewById(R.id.check_box);
            mEndTouchArea = view.requireViewById(R.id.end_action_area);
            mEndClickIcon = view.requireViewById(R.id.media_output_item_end_click_icon);
            mVolumeValueText = view.requireViewById(R.id.volume_value);
            mIconAreaLayout = view.requireViewById(R.id.icon_area);
            initAnimator();
        }

        void onBind(MediaDevice device, int position) {
            mDeviceId = device.getId();
            mCheckBox.setVisibility(View.GONE);
            mStatusIcon.setVisibility(View.GONE);
            mEndTouchArea.setVisibility(View.GONE);
            mEndTouchArea.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            mContainerLayout.setOnClickListener(null);
            mContainerLayout.setContentDescription(null);
            mTitleText.setTextColor(mController.getColorItemContent());
            mSubTitleText.setTextColor(mController.getColorItemContent());
            mSubTitleText.setSelected(true);
            mTwoLineTitleText.setTextColor(mController.getColorItemContent());
            mVolumeValueText.setTextColor(mController.getColorItemContent());
            mSeekBar.setProgressTintList(
                    ColorStateList.valueOf(mController.getColorSeekbarProgress()));
        }

        abstract void onBind(int customizedItem);

        void setSingleLineLayout(CharSequence title) {
            setSingleLineLayout(title, false, false, false, false);
        }

        void setSingleLineLayout(CharSequence title, boolean showSeekBar,
                boolean showProgressBar, boolean showCheckBox, boolean showEndTouchArea) {
            mTwoLineLayout.setVisibility(View.GONE);
            boolean isActive = showSeekBar || showProgressBar;
            if (!mCornerAnimator.isRunning()) {
                final Drawable backgroundDrawable =
                        showSeekBar
                                ? mContext.getDrawable(
                                        R.drawable.media_output_item_background_active)
                                .mutate() : mContext.getDrawable(
                                        R.drawable.media_output_item_background)
                                .mutate();
                mItemLayout.setBackground(backgroundDrawable);
                if (showSeekBar) {
                    updateSeekbarProgressBackground();
                }
            }
            mItemLayout.setBackgroundTintList(
                    ColorStateList.valueOf(isActive ? mController.getColorConnectedItemBackground()
                            : mController.getColorItemBackground()));
            mIconAreaLayout.setBackgroundTintList(
                    ColorStateList.valueOf(showSeekBar ? mController.getColorSeekbarProgress()
                            : showProgressBar ? mController.getColorConnectedItemBackground()
                                    : mController.getColorItemBackground()));
            mProgressBar.setVisibility(showProgressBar ? View.VISIBLE : View.GONE);
            mSeekBar.setAlpha(1);
            mSeekBar.setVisibility(showSeekBar ? View.VISIBLE : View.GONE);
            if (!showSeekBar) {
                mSeekBar.resetVolume();
            }
            mTitleText.setText(title);
            mTitleText.setVisibility(View.VISIBLE);
            mCheckBox.setVisibility(showCheckBox ? View.VISIBLE : View.GONE);
            mEndTouchArea.setVisibility(showEndTouchArea ? View.VISIBLE : View.GONE);
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) mItemLayout.getLayoutParams();
            params.rightMargin = showEndTouchArea ? mController.getItemMarginEndSelectable()
                    : mController.getItemMarginEndDefault();
            mTitleIcon.setBackgroundTintList(
                    ColorStateList.valueOf(mController.getColorItemContent()));
        }

        void setTwoLineLayout(MediaDevice device, boolean bFocused, boolean showSeekBar,
                boolean showProgressBar, boolean showSubtitle, boolean showStatus,
                boolean isFakeActive) {
            setTwoLineLayout(device, null, bFocused, showSeekBar, showProgressBar, showSubtitle,
                    showStatus, false, isFakeActive);
        }

        void setTwoLineLayout(MediaDevice device, CharSequence title, boolean bFocused,
                boolean showSeekBar, boolean showProgressBar, boolean showSubtitle,
                boolean showStatus , boolean showEndTouchArea, boolean isFakeActive) {
            mTitleText.setVisibility(View.GONE);
            mTwoLineLayout.setVisibility(View.VISIBLE);
            mStatusIcon.setVisibility(showStatus ? View.VISIBLE : View.GONE);
            mSeekBar.setAlpha(1);
            mSeekBar.setVisibility(showSeekBar ? View.VISIBLE : View.GONE);
            final Drawable backgroundDrawable;
            backgroundDrawable = mContext.getDrawable(
                    showSeekBar || isFakeActive ? R.drawable.media_output_item_background_active
                            : R.drawable.media_output_item_background).mutate();
            mItemLayout.setBackgroundTintList(ColorStateList.valueOf(
                    showSeekBar || isFakeActive ? mController.getColorConnectedItemBackground()
                            : mController.getColorItemBackground()
            ));
            mIconAreaLayout.setBackgroundTintList(
                    ColorStateList.valueOf(showProgressBar || isFakeActive
                            ? mController.getColorConnectedItemBackground()
                            : showSeekBar ? mController.getColorSeekbarProgress()
                                    : mController.getColorItemBackground()));
            if (showSeekBar) {
                updateSeekbarProgressBackground();
            }
            //update end click area by isActive
            mEndTouchArea.setVisibility(showEndTouchArea ? View.VISIBLE : View.GONE);
            mEndClickIcon.setVisibility(showEndTouchArea ? View.VISIBLE : View.GONE);
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) mItemLayout.getLayoutParams();
            params.rightMargin = showEndTouchArea ? mController.getItemMarginEndSelectable()
                    : mController.getItemMarginEndDefault();
            mItemLayout.setBackground(backgroundDrawable);
            mProgressBar.setVisibility(showProgressBar ? View.VISIBLE : View.GONE);
            mSubTitleText.setVisibility(showSubtitle ? View.VISIBLE : View.GONE);
            mTwoLineTitleText.setTranslationY(0);
            mTwoLineTitleText.setText(device == null ? title : getItemTitle(device));
            mTwoLineTitleText.setTypeface(Typeface.create(mContext.getString(
                            bFocused ? com.android.internal.R.string.config_headlineFontFamilyMedium
                                    : com.android.internal.R.string.config_headlineFontFamily),
                    Typeface.NORMAL));
        }

        void updateSeekbarProgressBackground() {
            final ClipDrawable clipDrawable =
                    (ClipDrawable) ((LayerDrawable) mSeekBar.getProgressDrawable())
                            .findDrawableByLayerId(android.R.id.progress);
            final GradientDrawable progressDrawable =
                    (GradientDrawable) clipDrawable.getDrawable();
            progressDrawable.setCornerRadii(
                    new float[]{0, 0, mController.getActiveRadius(),
                            mController.getActiveRadius(),
                            mController.getActiveRadius(),
                            mController.getActiveRadius(), 0, 0});
        }

        void initSeekbar(MediaDevice device, boolean isCurrentSeekbarInvisible) {
            if (!mController.isVolumeControlEnabled(device)) {
                disableSeekBar();
            } else {
                enableSeekBar(device);
            }
            mSeekBar.setMaxVolume(device.getMaxVolume());
            final int currentVolume = device.getCurrentVolume();
            if (!mIsDragging) {
                if (mSeekBar.getVolume() != currentVolume && (mLatestUpdateVolume == -1
                        || currentVolume == mLatestUpdateVolume)) {
                    // Update only if volume of device and value of volume bar doesn't match.
                    // Check if response volume match with the latest request, to ignore obsolete
                    // response
                    if (isCurrentSeekbarInvisible && !mIsInitVolumeFirstTime) {
                        updateTitleIcon(currentVolume == 0 ? R.drawable.media_output_icon_volume_off
                                        : R.drawable.media_output_icon_volume,
                                mController.getColorItemContent());
                    } else {
                        if (!mVolumeAnimator.isStarted()) {
                            int percentage =
                                    (int) ((double) currentVolume * VOLUME_PERCENTAGE_SCALE_SIZE
                                            / (double) mSeekBar.getMax());
                            if (percentage == 0) {
                                updateMutedVolumeIcon();
                            } else {
                                updateUnmutedVolumeIcon();
                            }
                            mSeekBar.setVolume(currentVolume);
                            mLatestUpdateVolume = -1;
                        }
                    }
                } else if (currentVolume == 0) {
                    mSeekBar.resetVolume();
                    updateMutedVolumeIcon();
                }
                if (currentVolume == mLatestUpdateVolume) {
                    mLatestUpdateVolume = -1;
                }
            }
            if (mIsInitVolumeFirstTime) {
                mIsInitVolumeFirstTime = false;
            }
            mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                boolean mStartFromMute = false;
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (device == null || !fromUser) {
                        return;
                    }
                    int progressToVolume = MediaOutputSeekbar.scaleProgressToVolume(progress);
                    int deviceVolume = device.getCurrentVolume();
                    int percentage =
                            (int) ((double) progressToVolume * VOLUME_PERCENTAGE_SCALE_SIZE
                                    / (double) seekBar.getMax());
                    mVolumeValueText.setText(mContext.getResources().getString(
                            R.string.media_output_dialog_volume_percentage, percentage));
                    mVolumeValueText.setVisibility(View.VISIBLE);
                    if (mStartFromMute) {
                        updateUnmutedVolumeIcon();
                        mStartFromMute = false;
                    }
                    if (progressToVolume != deviceVolume) {
                        mLatestUpdateVolume = progressToVolume;
                        mController.adjustVolume(device, progressToVolume);
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
                        updateMutedVolumeIcon();
                    } else {
                        updateUnmutedVolumeIcon();
                    }
                    mTitleIcon.setVisibility(View.VISIBLE);
                    mVolumeValueText.setVisibility(View.GONE);
                    mController.logInteractionAdjustVolume(device);
                    mIsDragging = false;
                }
            });
        }

        void updateMutedVolumeIcon() {
            mIconAreaLayout.setBackground(
                    mContext.getDrawable(R.drawable.media_output_item_background_active));
            updateTitleIcon(R.drawable.media_output_icon_volume_off,
                    mController.getColorItemContent());
        }

        void updateUnmutedVolumeIcon() {
            mIconAreaLayout.setBackground(
                    mContext.getDrawable(R.drawable.media_output_title_icon_area)
            );
            updateTitleIcon(R.drawable.media_output_icon_volume,
                    mController.getColorItemContent());
        }

        void updateTitleIcon(@DrawableRes int id, int color) {
            mTitleIcon.setImageDrawable(mContext.getDrawable(id));
            mTitleIcon.setImageTintList(ColorStateList.valueOf(color));
            mIconAreaLayout.setBackgroundTintList(
                    ColorStateList.valueOf(mController.getColorSeekbarProgress()));
        }

        void updateIconAreaClickListener(View.OnClickListener listener) {
            mIconAreaLayout.setOnClickListener(listener);
        }

        void initFakeActiveDevice() {
            disableSeekBar();
            updateTitleIcon(R.drawable.media_output_icon_volume,
                    mController.getColorItemContent());
            final Drawable backgroundDrawable = mContext.getDrawable(
                                    R.drawable.media_output_item_background_active)
                            .mutate();
            mItemLayout.setBackground(backgroundDrawable);
            mItemLayout.setBackgroundTintList(
                    ColorStateList.valueOf(mController.getColorConnectedItemBackground()));
            mIconAreaLayout.setBackgroundTintList(
                    ColorStateList.valueOf(mController.getColorConnectedItemBackground()));
        }

        private void animateCornerAndVolume(int fromProgress, int toProgress) {
            final GradientDrawable layoutBackgroundDrawable =
                    (GradientDrawable) mItemLayout.getBackground();
            final ClipDrawable clipDrawable =
                    (ClipDrawable) ((LayerDrawable) mSeekBar.getProgressDrawable())
                            .findDrawableByLayerId(android.R.id.progress);
            final GradientDrawable targetBackgroundDrawable =
                    (GradientDrawable) (mIconAreaLayout.getBackground());
            mCornerAnimator.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                layoutBackgroundDrawable.setCornerRadius(value);
                if (toProgress == 0) {
                    targetBackgroundDrawable.setCornerRadius(value);
                } else {
                    targetBackgroundDrawable.setCornerRadii(new float[]{
                            value,
                            value,
                            0, 0, 0, 0, value, value
                    });
                }
            });
            mVolumeAnimator.setIntValues(fromProgress, toProgress);
            mVolumeAnimator.start();
            mCornerAnimator.start();
        }

        private void initAnimator() {
            mCornerAnimator = ValueAnimator.ofFloat(mController.getInactiveRadius(),
                    mController.getActiveRadius());
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

        private void enableSeekBar(MediaDevice device) {
            mSeekBar.setEnabled(true);
            mSeekBar.setOnTouchListener((v, event) -> false);
            updateIconAreaClickListener((v) -> {
                if (device.getCurrentVolume() == 0) {
                    mController.logInteractionUnmuteDevice(device);
                    mSeekBar.setVolume(UNMUTE_DEFAULT_VOLUME);
                    mController.adjustVolume(device, UNMUTE_DEFAULT_VOLUME);
                    updateUnmutedVolumeIcon();
                    mIconAreaLayout.setOnTouchListener(((iconV, event) -> false));
                } else {
                    mController.logInteractionMuteDevice(device);
                    mSeekBar.resetVolume();
                    mController.adjustVolume(device, 0);
                    updateMutedVolumeIcon();
                    mIconAreaLayout.setOnTouchListener(((iconV, event) -> {
                        mSeekBar.dispatchTouchEvent(event);
                        return false;
                    }));
                }
            });
        }

        protected void setUpDeviceIcon(MediaDevice device) {
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
    }
}
