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

import android.content.Context;
import android.util.AttributeSet;
import android.widget.SeekBar;

/**
 * Customized SeekBar for MediaOutputDialog, apply scale between device volume and progress, to make
 * adjustment smoother.
 */
public class MediaOutputSeekbar extends SeekBar {
    private static final int SCALE_SIZE = 1000;
    public static final int VOLUME_PERCENTAGE_SCALE_SIZE = 100000;

    public MediaOutputSeekbar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMin(0);
    }

    static int scaleProgressToVolume(int progress) {
        return progress / SCALE_SIZE;
    }

    static int scaleVolumeToProgress(int volume) {
        return volume * SCALE_SIZE;
    }

    int getVolume() {
        return getProgress() / SCALE_SIZE;
    }

    void setVolume(int volume) {
        setProgress(volume * SCALE_SIZE, true);
    }

    void setMaxVolume(int maxVolume) {
        setMax(maxVolume * SCALE_SIZE);
    }

    void resetVolume() {
        setProgress(getMin());
    }
}
