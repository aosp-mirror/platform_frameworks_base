/*
 * Copyright 2023 The Android Open Source Project
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

package android.os.vibrator;

import android.annotation.NonNull;
import android.os.VibratorInfo;

/**
 * Factory for creating {@link VibratorInfo}s.
 *
 * @hide
 */
public final class VibratorInfoFactory {
    /**
     * Creates a single {@link VibratorInfo} that is an intersection of a given collection of
     * {@link VibratorInfo}s. That is, the capabilities of the returned info will be an
     * intersection of that of the provided infos.
     *
     * @param id the ID for the new {@link VibratorInfo}.
     * @param vibratorInfos the {@link VibratorInfo}s from which to create a single
     *      {@link VibratorInfo}.
     * @return a {@link VibratorInfo} that represents the intersection of {@code vibratorInfos}.
     */
    @NonNull
    public static VibratorInfo create(int id, @NonNull VibratorInfo[] vibratorInfos) {
        if (vibratorInfos.length == 0) {
            return new VibratorInfo.Builder(id).build();
        }
        if (vibratorInfos.length == 1) {
            // Create an equivalent info with the requested ID.
            return new VibratorInfo(id, vibratorInfos[0]);
        }
        // Create a MultiVibratorInfo that intersects all the given infos and has the requested ID.
        return new MultiVibratorInfo(id, vibratorInfos);
    }

    private VibratorInfoFactory() {}
}
