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

package com.android.server.hdmi;

import android.annotation.Nullable;

import java.util.Objects;

/**
 * Immutable representation of the information in the [Audio Status] operand:
 * volume status (0 <= N <= 100) and mute status (muted or unmuted).
 */
public class AudioStatus {
    public static final int MAX_VOLUME = 100;
    public static final int MIN_VOLUME = 0;

    int mVolume;
    boolean mMute;

    public AudioStatus(int volume, boolean mute) {
        mVolume = volume;
        mMute = mute;
    }

    public int getVolume() {
        return mVolume;
    }

    public boolean getMute() {
        return mMute;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof AudioStatus)) {
            return false;
        }

        AudioStatus other = (AudioStatus) obj;
        return mVolume == other.mVolume
                && mMute == other.mMute;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mVolume, mMute);
    }

    @Override
    public String toString() {
        return "AudioStatus mVolume:" + mVolume + " mMute:" + mMute;
    }
}
