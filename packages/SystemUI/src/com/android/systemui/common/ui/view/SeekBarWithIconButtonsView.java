/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.common.ui.view;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.res.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The layout contains a seekbar whose progress could be modified
 * through the icons on two ends of the seekbar.
 */
public class SeekBarWithIconButtonsView extends LinearLayout {

    private static final int DEFAULT_SEEKBAR_MAX = 6;
    private static final int DEFAULT_SEEKBAR_PROGRESS = 0;
    private static final int DEFAULT_SEEKBAR_TICK_MARK = 0;

    private ViewGroup mIconStartFrame;
    private ViewGroup mIconEndFrame;
    private ImageView mIconStart;
    private ImageView mIconEnd;
    private SeekBar mSeekbar;
    private int mSeekBarChangeMagnitude = 1;

    private boolean mSetProgressFromButtonFlag = false;

    private SeekBarChangeListener mSeekBarListener = new SeekBarChangeListener();
    private String[] mStateLabels = null;

    public SeekBarWithIconButtonsView(Context context) {
        this(context, null);
    }

    public SeekBarWithIconButtonsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SeekBarWithIconButtonsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public SeekBarWithIconButtonsView(Context context,
            AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        LayoutInflater.from(context).inflate(
                R.layout.seekbar_with_icon_buttons, this, /* attachToRoot= */ true);

        mIconStartFrame = findViewById(R.id.icon_start_frame);
        mIconEndFrame = findViewById(R.id.icon_end_frame);
        mIconStart = findViewById(R.id.icon_start);
        mIconEnd = findViewById(R.id.icon_end);
        mSeekbar = findViewById(R.id.seekbar);

        if (attrs != null) {
            TypedArray typedArray = context.obtainStyledAttributes(
                    attrs,
                    R.styleable.SeekBarWithIconButtonsView_Layout,
                    defStyleAttr, defStyleRes
            );
            int max = typedArray.getInt(
                    R.styleable.SeekBarWithIconButtonsView_Layout_max, DEFAULT_SEEKBAR_MAX);
            int progress = typedArray.getInt(
                    R.styleable.SeekBarWithIconButtonsView_Layout_progress,
                    DEFAULT_SEEKBAR_PROGRESS);
            mSeekbar.setMax(max);
            setProgress(progress);

            int iconStartFrameContentDescriptionId = typedArray.getResourceId(
                    R.styleable.SeekBarWithIconButtonsView_Layout_iconStartContentDescription,
                    /* defValue= */ 0);
            int iconEndFrameContentDescriptionId = typedArray.getResourceId(
                    R.styleable.SeekBarWithIconButtonsView_Layout_iconEndContentDescription,
                    /* defValue= */ 0);
            if (iconStartFrameContentDescriptionId != 0) {
                final String contentDescription =
                        context.getString(iconStartFrameContentDescriptionId);
                mIconStartFrame.setContentDescription(contentDescription);
            }
            if (iconEndFrameContentDescriptionId != 0) {
                final String contentDescription =
                        context.getString(iconEndFrameContentDescriptionId);
                mIconEndFrame.setContentDescription(contentDescription);
            }
            int tickMarkId = typedArray.getResourceId(
                    R.styleable.SeekBarWithIconButtonsView_Layout_tickMark,
                    DEFAULT_SEEKBAR_TICK_MARK);
            if (tickMarkId != DEFAULT_SEEKBAR_TICK_MARK) {
                mSeekbar.setTickMark(getResources().getDrawable(tickMarkId));
            }
            mSeekBarChangeMagnitude = typedArray.getInt(
                    R.styleable.SeekBarWithIconButtonsView_Layout_seekBarChangeMagnitude,
                    /* defValue= */ 1);
        } else {
            mSeekbar.setMax(DEFAULT_SEEKBAR_MAX);
            setProgress(DEFAULT_SEEKBAR_PROGRESS);
        }

        mSeekbar.setOnSeekBarChangeListener(mSeekBarListener);

        mIconStartFrame.setOnClickListener((view) -> onIconStartClicked());
        mIconEndFrame.setOnClickListener((view) -> onIconEndClicked());
    }

    private static void setIconViewAndFrameEnabled(View iconView, boolean enabled) {
        iconView.setEnabled(enabled);
        final ViewGroup iconFrame = (ViewGroup) iconView.getParent();
        iconFrame.setEnabled(enabled);
    }

    /**
     * Stores the String array we would like to use for describing the state of seekbar progress
     * and updates the state description with current progress.
     *
     * @param labels The state descriptions to be announced for each progress.
     */
    public void setProgressStateLabels(String[] labels) {
        mStateLabels = labels;
        if (mStateLabels != null) {
            setSeekbarStateDescription();
        }
    }

    /**
     * Sets the state of seekbar based on current progress. The progress of seekbar is
     * corresponding to the index of the string array. If the progress is larger than or equals
     * to the length of the array, the state description is set to an empty string.
     */
    private void setSeekbarStateDescription() {
        mSeekbar.setStateDescription(
                (mSeekbar.getProgress() < mStateLabels.length)
                        ? mStateLabels[mSeekbar.getProgress()] : "");
    }

    /**
     * Sets a onSeekbarChangeListener to the seekbar in the layout.
     * We update the Start Icon and End Icon if needed when the seekbar progress is changed.
     */
    public void setOnSeekBarWithIconButtonsChangeListener(
            @Nullable OnSeekBarWithIconButtonsChangeListener onSeekBarChangeListener) {
        mSeekBarListener.setOnSeekBarWithIconButtonsChangeListener(onSeekBarChangeListener);
    }

    /**
     * Only for testing. Get previous set mOnSeekBarChangeListener to the seekbar.
     */
    @VisibleForTesting
    public OnSeekBarWithIconButtonsChangeListener getOnSeekBarWithIconButtonsChangeListener() {
        return mSeekBarListener.mOnSeekBarChangeListener;
    }

    /**
     * Only for testing. Get {@link #mSeekbar} in the layout.
     */
    @VisibleForTesting
    public SeekBar getSeekbar() {
        return mSeekbar;
    }

    /**
     * Start and End icons might need to be updated when there is a change in seekbar progress.
     * Icon Start will need to be enabled when the seekbar progress is larger than 0.
     * Icon End will need to be enabled when the seekbar progress is less than Max.
     */
    private void updateIconViewIfNeeded(int progress) {
        setIconViewAndFrameEnabled(mIconStart, progress > 0);
        setIconViewAndFrameEnabled(mIconEnd, progress < mSeekbar.getMax());
    }

    /**
     * Sets max to the seekbar in the layout.
     */
    public void setMax(int max) {
        mSeekbar.setMax(max);
    }

    /**
     * Gets max to the seekbar in the layout.
     */
    public int getMax() {
        return mSeekbar.getMax();
    }

    /**
     * @return the magnitude by which seekbar progress changes when start and end icons are clicked.
     */
    public int getChangeMagnitude() {
        return mSeekBarChangeMagnitude;
    }

    /**
     * Sets progress to the seekbar in the layout.
     * If the progress is smaller than or equals to 0, the IconStart will be disabled. If the
     * progress is larger than or equals to Max, the IconEnd will be disabled. The seekbar progress
     * will be constrained in {@link SeekBar}.
     */
    public void setProgress(int progress) {
        mSeekbar.setProgress(progress);
        updateIconViewIfNeeded(mSeekbar.getProgress());
    }

    private void setProgressFromButton(int progress) {
        mSetProgressFromButtonFlag = true;
        mSeekbar.setProgress(progress);
        updateIconViewIfNeeded(mSeekbar.getProgress());
    }

    private void onIconStartClicked() {
        final int progress = mSeekbar.getProgress();
        if (progress > 0) {
            setProgressFromButton(progress - mSeekBarChangeMagnitude);
        }
    }

    private void onIconEndClicked() {
        final int progress = mSeekbar.getProgress();
        if (progress < mSeekbar.getMax()) {
            setProgressFromButton(progress + mSeekBarChangeMagnitude);
        }
    }

    /**
     * Get current seekbar progress
     *
     * @return
     */
    @VisibleForTesting
    public int getProgress() {
        return mSeekbar.getProgress();
    }

    /**
     * Extended from {@link SeekBar.OnSeekBarChangeListener} to add callback to notify the listeners
     * the user interaction with the SeekBarWithIconButtonsView is finalized.
     */
    public interface OnSeekBarWithIconButtonsChangeListener
            extends SeekBar.OnSeekBarChangeListener {

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({
                ControlUnitType.SLIDER,
                ControlUnitType.BUTTON
        })
        /** Denotes the Last user interacted control unit type. */
        @interface ControlUnitType {
            int SLIDER = 0;
            int BUTTON = 1;
        }

        /**
         * Notification that the user interaction with SeekBarWithIconButtonsView is finalized. This
         * would be triggered after user ends dragging on the slider or clicks icon buttons.
         *
         * @param seekBar The SeekBar in which the user ends interaction with
         * @param control The last user interacted control unit. It would be
         *                {@link ControlUnitType#SLIDER} if the user was changing the seekbar
         *                progress through dragging the slider, or {@link ControlUnitType#BUTTON}
         *                is the user was clicking button to change the progress.
         */
        void onUserInteractionFinalized(SeekBar seekBar, @ControlUnitType int control);
    }

    private class SeekBarChangeListener implements SeekBar.OnSeekBarChangeListener {
        private OnSeekBarWithIconButtonsChangeListener mOnSeekBarChangeListener = null;

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mStateLabels != null) {
                setSeekbarStateDescription();
            }
            if (mOnSeekBarChangeListener != null) {
                if (mSetProgressFromButtonFlag) {
                    mSetProgressFromButtonFlag = false;
                    mOnSeekBarChangeListener.onProgressChanged(
                            seekBar, progress, /* fromUser= */ true);
                    // Directly trigger onUserInteractionFinalized since the interaction
                    // (click button) is ended.
                    mOnSeekBarChangeListener.onUserInteractionFinalized(
                            seekBar, OnSeekBarWithIconButtonsChangeListener.ControlUnitType.BUTTON);
                } else {
                    mOnSeekBarChangeListener.onProgressChanged(seekBar, progress, fromUser);
                }
            }
            updateIconViewIfNeeded(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            if (mOnSeekBarChangeListener != null) {
                mOnSeekBarChangeListener.onStartTrackingTouch(seekBar);
            }
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            if (mOnSeekBarChangeListener != null) {
                mOnSeekBarChangeListener.onStopTrackingTouch(seekBar);
                mOnSeekBarChangeListener.onUserInteractionFinalized(
                        seekBar, OnSeekBarWithIconButtonsChangeListener.ControlUnitType.SLIDER);
            }
        }

        void setOnSeekBarWithIconButtonsChangeListener(
                OnSeekBarWithIconButtonsChangeListener listener) {
            mOnSeekBarChangeListener = listener;
        }
    }
}
