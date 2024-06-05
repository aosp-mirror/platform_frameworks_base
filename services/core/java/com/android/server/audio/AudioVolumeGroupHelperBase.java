/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.server.audio;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.media.audiopolicy.AudioVolumeGroup;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/** Abstract class for {@link AudioVolumeGroup} related helper methods. */
@VisibleForTesting(visibility = PACKAGE)
public class AudioVolumeGroupHelperBase {
    public List<AudioVolumeGroup> getAudioVolumeGroups() {
        return new ArrayList<>();
    }
}
