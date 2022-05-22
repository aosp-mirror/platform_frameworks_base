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
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.Utils;
import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;

import java.util.List;

/**
 * Base adapter for media output dialog.
 */
public abstract class MediaOutputBaseAdapter extends
        RecyclerView.Adapter<MediaOutputBaseAdapter.MediaDeviceBaseViewHolder> {

    static final int CUSTOMIZED_ITEM_PAIR_NEW = 1;
    static final int CUSTOMIZED_ITEM_GROUP = 2;
    static final int CUSTOMIZED_ITEM_DYNAMIC_GROUP = 3;

    protected final MediaOutputController mController;

    private int mMargin;

    Context mContext;
    View mHolderView;
    boolean mIsDragging;
    int mCurrentActivePosition;

    public MediaOutputBaseAdapter(MediaOutputController controller) {
        mController = controller;
        mIsDragging = false;
        mCurrentActivePosition = -1;
    }

    @Override
    public MediaDeviceBaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        mContext = viewGroup.getContext();
        mMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_list_margin);
        mHolderView = LayoutInflater.from(mContext).inflate(R.layout.media_output_list_item,
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

        final LinearLayout mContainerLayout;
        final FrameLayout mItemLayout;
        final TextView mTitleText;
        final TextView mTwoLineTitleText;
        final TextView mSubTitleText;
        final ImageView mTitleIcon;
        final ProgressBar mProgressBar;
        final MediaOutputSeekbar mSeekBar;
        final LinearLayout mTwoLineLayout;
        final ImageView mStatusIcon;
        final CheckBox mCheckBox;
        final LinearLayout mEndTouchArea;
        private String mDeviceId;
        private ValueAnimator mCornerAnimator;
        private ValueAnimator mVolumeAnimator;

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
            initAnimator();
        }

        void onBind(MediaDevice device, boolean topMargin, boolean bottomMargin, int position) {
            mDeviceId = device.getId();
        }

        abstract void onBind(int customizedItem, boolean topMargin, boolean bottomMargin);

        void setSingleLineLayout(CharSequence title, boolean bFocused) {
            setSingleLineLayout(title, bFocused, false, false, false);
        }

        void setSingleLineLayout(CharSequence title, boolean bFocused, boolean showSeekBar,
                boolean showProgressBar, boolean showStatus) {
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
                backgroundDrawable.setColorFilter(new PorterDuffColorFilter(
                        isActive ? mController.getColorConnectedItemBackground()
                                : mController.getColorItemBackground(),
                        PorterDuff.Mode.SRC_IN));
                mItemLayout.setBackground(backgroundDrawable);
                if (showSeekBar) {
                    final ClipDrawable clipDrawable =
                            (ClipDrawable) ((LayerDrawable) mSeekBar.getProgressDrawable())
                                    .findDrawableByLayerId(android.R.id.progress);
                    final GradientDrawable progressDrawable =
                            (GradientDrawable) clipDrawable.getDrawable();
                    progressDrawable.setCornerRadius(mController.getActiveRadius());
                }
            } else {
                mItemLayout.getBackground().setColorFilter(new PorterDuffColorFilter(
                        isActive ? mController.getColorConnectedItemBackground()
                                : mController.getColorItemBackground(),
                        PorterDuff.Mode.SRC_IN));
            }
            mProgressBar.setVisibility(showProgressBar ? View.VISIBLE : View.GONE);
            mSeekBar.setAlpha(1);
            mSeekBar.setVisibility(showSeekBar ? View.VISIBLE : View.GONE);
            if (!showSeekBar) {
                mSeekBar.resetVolume();
            }
            mStatusIcon.setVisibility(showStatus ? View.VISIBLE : View.GONE);
            mTitleText.setText(title);
            mTitleText.setVisibility(View.VISIBLE);
        }

        void setTwoLineLayout(MediaDevice device, boolean bFocused, boolean showSeekBar,
                boolean showProgressBar, boolean showSubtitle) {
            setTwoLineLayout(device, null, bFocused, showSeekBar, showProgressBar, showSubtitle,
                    false);
        }

        void setTwoLineLayout(MediaDevice device, boolean bFocused, boolean showSeekBar,
                boolean showProgressBar, boolean showSubtitle, boolean showStatus) {
            setTwoLineLayout(device, null, bFocused, showSeekBar, showProgressBar, showSubtitle,
                    showStatus);
        }

        void setTwoLineLayout(CharSequence title, boolean bFocused, boolean showSeekBar,
                boolean showProgressBar, boolean showSubtitle) {
            setTwoLineLayout(null, title, bFocused, showSeekBar, showProgressBar, showSubtitle,
                    false);
        }

        private void setTwoLineLayout(MediaDevice device, CharSequence title, boolean bFocused,
                boolean showSeekBar, boolean showProgressBar, boolean showSubtitle,
                boolean showStatus) {
            mTitleText.setVisibility(View.GONE);
            mTwoLineLayout.setVisibility(View.VISIBLE);
            mStatusIcon.setVisibility(showStatus ? View.VISIBLE : View.GONE);
            mSeekBar.setAlpha(1);
            mSeekBar.setVisibility(showSeekBar ? View.VISIBLE : View.GONE);
            final Drawable backgroundDrawable = mContext.getDrawable(
                            R.drawable.media_output_item_background)
                    .mutate();
            backgroundDrawable.setColorFilter(new PorterDuffColorFilter(
                    mController.getColorItemBackground(),
                    PorterDuff.Mode.SRC_IN));
            mItemLayout.setBackground(backgroundDrawable);
            mProgressBar.setVisibility(showProgressBar ? View.VISIBLE : View.GONE);
            mSubTitleText.setVisibility(showSubtitle ? View.VISIBLE : View.GONE);
            mTwoLineTitleText.setTranslationY(0);
            if (device == null) {
                mTwoLineTitleText.setText(title);
            } else {
                mTwoLineTitleText.setText(getItemTitle(device));
            }

            if (bFocused) {
                mTwoLineTitleText.setTypeface(Typeface.create(mContext.getString(
                                com.android.internal.R.string.config_headlineFontFamilyMedium),
                        Typeface.NORMAL));
            } else {
                mTwoLineTitleText.setTypeface(Typeface.create(mContext.getString(
                        com.android.internal.R.string.config_headlineFontFamily), Typeface.NORMAL));
            }
        }

        void initSeekbar(MediaDevice device, boolean isCurrentSeekbarInvisible) {
            if (!mController.isVolumeControlEnabled(device)) {
                disableSeekBar();
            }
            mSeekBar.setMaxVolume(device.getMaxVolume());
            final int currentVolume = device.getCurrentVolume();
            if (mSeekBar.getVolume() != currentVolume) {
                if (isCurrentSeekbarInvisible) {
                    animateCornerAndVolume(mSeekBar.getProgress(),
                            MediaOutputSeekbar.scaleVolumeToProgress(currentVolume));
                } else {
                    if (!mVolumeAnimator.isStarted()) {
                        mSeekBar.setVolume(currentVolume);
                    }
                }
            }
            mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (device == null || !fromUser) {
                        return;
                    }
                    int currentVolume = MediaOutputSeekbar.scaleProgressToVolume(progress);
                    int deviceVolume = device.getCurrentVolume();
                    if (currentVolume != deviceVolume) {
                        mController.adjustVolume(device, currentVolume);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mIsDragging = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mIsDragging = false;
                }
            });
        }

        void initSessionSeekbar() {
            disableSeekBar();
            mSeekBar.setMax(mController.getSessionVolumeMax());
            mSeekBar.setMin(0);
            final int currentVolume = mController.getSessionVolume();
            if (mSeekBar.getProgress() != currentVolume) {
                mSeekBar.setProgress(currentVolume, true);
            }
            mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) {
                        return;
                    }
                    mController.adjustSessionVolume(progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    mIsDragging = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mIsDragging = false;
                }
            });
        }

        private void animateCornerAndVolume(int fromProgress, int toProgress) {
            final GradientDrawable layoutBackgroundDrawable =
                    (GradientDrawable) mItemLayout.getBackground();
            final ClipDrawable clipDrawable =
                    (ClipDrawable) ((LayerDrawable) mSeekBar.getProgressDrawable())
                            .findDrawableByLayerId(android.R.id.progress);
            final GradientDrawable progressDrawable = (GradientDrawable) clipDrawable.getDrawable();
            mCornerAnimator.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                layoutBackgroundDrawable.setCornerRadius(value);
                progressDrawable.setCornerRadius(value);
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

        Drawable getSpeakerDrawable() {
            final Drawable drawable = mContext.getDrawable(R.drawable.ic_speaker_group_black_24dp)
                    .mutate();
            drawable.setColorFilter(
                    new PorterDuffColorFilter(Utils.getColorStateListDefaultColor(mContext,
                            R.color.media_dialog_item_main_content),
                            PorterDuff.Mode.SRC_IN));
            return drawable;
        }

        private void disableSeekBar() {
            mSeekBar.setEnabled(false);
            mSeekBar.setOnTouchListener((v, event) -> true);
        }

        protected void setUpDeviceIcon(MediaDevice device) {
            ThreadUtils.postOnBackgroundThread(() -> {
                Icon icon = mController.getDeviceIconCompat(device).toIcon(mContext);
                ThreadUtils.postOnMainThread(() -> {
                    if (!TextUtils.equals(mDeviceId, device.getId())) {
                        return;
                    }
                    mTitleIcon.setImageIcon(icon);
                });
            });
        }
    }
}
