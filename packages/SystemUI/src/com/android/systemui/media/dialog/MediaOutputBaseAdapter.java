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
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;

/**
 * Base adapter for media output dialog.
 */
public abstract class MediaOutputBaseAdapter extends
        RecyclerView.Adapter<MediaOutputBaseAdapter.MediaDeviceBaseViewHolder> {

    static final int CUSTOMIZED_ITEM_PAIR_NEW = 1;
    static final int CUSTOMIZED_ITEM_GROUP = 2;
    static final int CUSTOMIZED_ITEM_DYNAMIC_GROUP = 3;

    final MediaOutputController mController;

    private int mMargin;
    private boolean mIsAnimating;

    Context mContext;
    View mHolderView;
    boolean mIsDragging;

    public MediaOutputBaseAdapter(MediaOutputController controller) {
        mController = controller;
        mIsDragging = false;
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

    CharSequence getItemTitle(MediaDevice device) {
        return device.getName();
    }

    boolean isCurrentlyConnected(MediaDevice device) {
        return TextUtils.equals(device.getId(),
                mController.getCurrentConnectedMediaDevice().getId());
    }

    boolean isDragging() {
        return mIsDragging;
    }

    boolean isAnimating() {
        return mIsAnimating;
    }

    /**
     * ViewHolder for binding device view.
     */
    abstract class MediaDeviceBaseViewHolder extends RecyclerView.ViewHolder {

        private static final int ANIM_DURATION = 200;

        final LinearLayout mContainerLayout;
        final TextView mTitleText;
        final TextView mTwoLineTitleText;
        final TextView mSubTitleText;
        final ImageView mTitleIcon;
        final ImageView mAddIcon;
        final ProgressBar mProgressBar;
        final SeekBar mSeekBar;
        final RelativeLayout mTwoLineLayout;
        final View mDivider;
        final View mBottomDivider;
        final CheckBox mCheckBox;
        private String mDeviceId;

        MediaDeviceBaseViewHolder(View view) {
            super(view);
            mContainerLayout = view.requireViewById(R.id.device_container);
            mTitleText = view.requireViewById(R.id.title);
            mSubTitleText = view.requireViewById(R.id.subtitle);
            mTwoLineLayout = view.requireViewById(R.id.two_line_layout);
            mTwoLineTitleText = view.requireViewById(R.id.two_line_title);
            mTitleIcon = view.requireViewById(R.id.title_icon);
            mProgressBar = view.requireViewById(R.id.volume_indeterminate_progress);
            mSeekBar = view.requireViewById(R.id.volume_seekbar);
            mDivider = view.requireViewById(R.id.end_divider);
            mBottomDivider = view.requireViewById(R.id.bottom_divider);
            mAddIcon = view.requireViewById(R.id.add_icon);
            mCheckBox = view.requireViewById(R.id.check_box);
        }

        void onBind(MediaDevice device, boolean topMargin, boolean bottomMargin) {
            mDeviceId = device.getId();
            ThreadUtils.postOnBackgroundThread(() -> {
                Icon icon = mController.getDeviceIconCompat(device).toIcon(mContext);
                ThreadUtils.postOnMainThread(() -> {
                    if (!TextUtils.equals(mDeviceId, device.getId())) {
                        return;
                    }
                    mTitleIcon.setImageIcon(icon);
                    setMargin(topMargin, bottomMargin);
                });
            });
        }

        void onBind(int customizedItem, boolean topMargin, boolean bottomMargin) {
            setMargin(topMargin, bottomMargin);
        }

        private void setMargin(boolean topMargin, boolean bottomMargin) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) mContainerLayout
                    .getLayoutParams();
            params.topMargin = topMargin ? mMargin : 0;
            params.bottomMargin = bottomMargin ? mMargin : 0;
            mContainerLayout.setLayoutParams(params);
        }

        void setSingleLineLayout(CharSequence title, boolean bFocused) {
            mTwoLineLayout.setVisibility(View.GONE);
            mProgressBar.setVisibility(View.GONE);
            mTitleText.setVisibility(View.VISIBLE);
            mTitleText.setTranslationY(0);
            mTitleText.setText(title);
            if (bFocused) {
                mTitleText.setTypeface(Typeface.create(mContext.getString(
                        com.android.internal.R.string.config_headlineFontFamilyMedium),
                        Typeface.NORMAL));
            } else {
                mTitleText.setTypeface(Typeface.create(mContext.getString(
                        com.android.internal.R.string.config_headlineFontFamily), Typeface.NORMAL));
            }
        }

        void setTwoLineLayout(MediaDevice device, boolean bFocused, boolean showSeekBar,
                boolean showProgressBar, boolean showSubtitle) {
            setTwoLineLayout(device, null, bFocused, showSeekBar, showProgressBar, showSubtitle);
        }

        void setTwoLineLayout(CharSequence title, boolean bFocused, boolean showSeekBar,
                boolean showProgressBar, boolean showSubtitle) {
            setTwoLineLayout(null, title, bFocused, showSeekBar, showProgressBar, showSubtitle);
        }

        private void setTwoLineLayout(MediaDevice device, CharSequence title, boolean bFocused,
                boolean showSeekBar, boolean showProgressBar, boolean showSubtitle) {
            mTitleText.setVisibility(View.GONE);
            mTwoLineLayout.setVisibility(View.VISIBLE);
            mSeekBar.setAlpha(1);
            mSeekBar.setVisibility(showSeekBar ? View.VISIBLE : View.GONE);
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

        void initSeekbar(MediaDevice device) {
            mSeekBar.setMax(device.getMaxVolume());
            mSeekBar.setMin(0);
            final int currentVolume = device.getCurrentVolume();
            if (mSeekBar.getProgress() != currentVolume) {
                mSeekBar.setProgress(currentVolume);
            }
            mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (device == null || !fromUser) {
                        return;
                    }
                    mController.adjustVolume(device, progress);
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
            mSeekBar.setMax(mController.getSessionVolumeMax());
            mSeekBar.setMin(0);
            final int currentVolume = mController.getSessionVolume();
            if (mSeekBar.getProgress() != currentVolume) {
                mSeekBar.setProgress(currentVolume);
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

        void playSwitchingAnim(@NonNull View from, @NonNull View to) {
            final float delta = (float) (mContext.getResources().getDimensionPixelSize(
                    R.dimen.media_output_dialog_title_anim_y_delta));
            final SeekBar fromSeekBar = from.requireViewById(R.id.volume_seekbar);
            final TextView toTitleText = to.requireViewById(R.id.title);
            if (fromSeekBar.getVisibility() != View.VISIBLE || toTitleText.getVisibility()
                    != View.VISIBLE) {
                return;
            }
            mIsAnimating = true;
            // Animation for title text
            toTitleText.setTypeface(Typeface.create(mContext.getString(
                    com.android.internal.R.string.config_headlineFontFamilyMedium),
                    Typeface.NORMAL));
            toTitleText.animate()
                    .setDuration(ANIM_DURATION)
                    .translationY(-delta)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            to.requireViewById(R.id.volume_indeterminate_progress).setVisibility(
                                    View.VISIBLE);
                        }
                    });
            // Animation for seek bar
            fromSeekBar.animate()
                    .alpha(0)
                    .setDuration(ANIM_DURATION)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            final TextView fromTitleText = from.requireViewById(
                                    R.id.two_line_title);
                            fromTitleText.setTypeface(Typeface.create(mContext.getString(
                                    com.android.internal.R.string.config_headlineFontFamily),
                                    Typeface.NORMAL));
                            fromTitleText.animate()
                                    .setDuration(ANIM_DURATION)
                                    .translationY(delta)
                                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                                    .setListener(new AnimatorListenerAdapter() {
                                        @Override
                                        public void onAnimationEnd(Animator animation) {
                                            mIsAnimating = false;
                                            notifyDataSetChanged();
                                        }
                                    });
                        }
                    });
        }

        Drawable getSpeakerDrawable() {
            final Drawable drawable = mContext.getDrawable(R.drawable.ic_speaker_group_black_24dp)
                    .mutate();
            final ColorStateList list = mContext.getResources().getColorStateList(
                    R.color.advanced_icon_color, mContext.getTheme());
            drawable.setColorFilter(new PorterDuffColorFilter(list.getDefaultColor(),
                    PorterDuff.Mode.SRC_IN));
            return BluetoothUtils.buildAdvancedDrawable(mContext, drawable);
        }
    }
}
