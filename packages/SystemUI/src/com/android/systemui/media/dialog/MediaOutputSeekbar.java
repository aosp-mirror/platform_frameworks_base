/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.SeekBar;

import com.android.systemui.res.R;

/**
 * Customized SeekBar for MediaOutputDialog, apply scale between device volume and progress, to make
 * adjustment smoother.
 */
public class MediaOutputSeekbar extends SeekBar {
    // The scale is added to make slider value change smooth.
    private static final int SCALE_SIZE = 1000;

    @Nullable
    private SeekBar.OnSeekBarChangeListener mOnSeekBarChangeListener = null;

    public MediaOutputSeekbar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMin(0);
        super.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final String percentageString = context.getResources().getString(
                        R.string.media_output_dialog_volume_percentage,
                        getPercentage());
                // Override the default TTS for the seekbar. The percentage should correspond to
                // the volume value, not the progress value. I.e. for the volume range 0 - 25, the
                // percentage should be 0%, 4%, 8%, etc. It should never be 6% since 6% doesn't map
                // to an integer volume value.
                setStateDescription(percentageString);
                if (mOnSeekBarChangeListener != null) {
                    mOnSeekBarChangeListener.onProgressChanged(seekBar, progress, fromUser);
                }
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
        });
    }

    @Override
    public void setOnSeekBarChangeListener(@Nullable SeekBar.OnSeekBarChangeListener listener) {
        mOnSeekBarChangeListener = listener;
    }

    static int scaleProgressToVolume(int progress) {
        return progress / SCALE_SIZE;
    }

    static int scaleVolumeToProgress(int volume) {
        return volume * SCALE_SIZE;
    }

    int getVolume() {
        return scaleProgressToVolume(getProgress());
    }

    void setVolume(int volume) {
        setProgress(scaleVolumeToProgress(volume), true);
    }

    void setMaxVolume(int maxVolume) {
        setMax(scaleVolumeToProgress(maxVolume));
    }

    void resetVolume() {
        setProgress(getMin());
    }

    int getPercentage() {
        // The progress -> volume -> progress conversion is necessary to ensure that progress
        // strictly corresponds to an integer volume value.
        // Example: 10424 (progress) -> 10 (volume) -> 10000 (progress).
        int normalizedProgress = scaleVolumeToProgress(scaleProgressToVolume(getProgress()));
        return (int) ((double) normalizedProgress * 100 / getMax());
    }
}
