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

import com.android.systemui.R;

/**
 * The layout contains a seekbar whose progress could be modified
 * through the icons on two ends of the seekbar.
 */
public class SeekBarWithIconButtonsView extends LinearLayout {

    private static final int DEFAULT_SEEKBAR_MAX = 6;
    private static final int DEFAULT_SEEKBAR_PROGRESS = 0;

    private ViewGroup mIconStartFrame;
    private ViewGroup mIconEndFrame;
    private ImageView mIconStart;
    private ImageView mIconEnd;
    private SeekBar mSeekbar;

    private SeekBarChangeListener mSeekBarListener = new SeekBarChangeListener();

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

            typedArray.recycle();
        } else {
            mSeekbar.setMax(DEFAULT_SEEKBAR_MAX);
            setProgress(DEFAULT_SEEKBAR_PROGRESS);
        }

        mSeekbar.setOnSeekBarChangeListener(mSeekBarListener);

        mIconStartFrame.setOnClickListener((view) -> {
            final int progress = mSeekbar.getProgress();
            if (progress > 0) {
                mSeekbar.setProgress(progress - 1);
                setIconViewAndFrameEnabled(mIconStart, mSeekbar.getProgress() > 0);
            }
        });

        mIconEndFrame.setOnClickListener((view) -> {
            final int progress = mSeekbar.getProgress();
            if (progress < mSeekbar.getMax()) {
                mSeekbar.setProgress(progress + 1);
                setIconViewAndFrameEnabled(mIconEnd, mSeekbar.getProgress() < mSeekbar.getMax());
            }
        });
    }

    private static void setIconViewAndFrameEnabled(View iconView, boolean enabled) {
        iconView.setEnabled(enabled);
        final ViewGroup iconFrame = (ViewGroup) iconView.getParent();
        iconFrame.setEnabled(enabled);
    }

    /**
     * Sets a onSeekbarChangeListener to the seekbar in the layout.
     * We update the Start Icon and End Icon if needed when the seekbar progress is changed.
     */
    public void setOnSeekBarChangeListener(
            @Nullable SeekBar.OnSeekBarChangeListener onSeekBarChangeListener) {
        mSeekBarListener.setOnSeekBarChangeListener(onSeekBarChangeListener);
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
     * Sets progress to the seekbar in the layout.
     * If the progress is smaller than or equals to 0, the IconStart will be disabled. If the
     * progress is larger than or equals to Max, the IconEnd will be disabled. The seekbar progress
     * will be constrained in {@link SeekBar}.
     */
    public void setProgress(int progress) {
        mSeekbar.setProgress(progress);
        updateIconViewIfNeeded(progress);
    }

    private class SeekBarChangeListener implements SeekBar.OnSeekBarChangeListener {
        private SeekBar.OnSeekBarChangeListener mOnSeekBarChangeListener = null;

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mOnSeekBarChangeListener != null) {
                mOnSeekBarChangeListener.onProgressChanged(seekBar, progress, fromUser);
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
            }
        }

        void setOnSeekBarChangeListener(SeekBar.OnSeekBarChangeListener listener) {
            mOnSeekBarChangeListener = listener;
        }
    }
}
