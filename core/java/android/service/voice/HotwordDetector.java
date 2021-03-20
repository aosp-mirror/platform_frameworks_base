/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.service.voice;

import android.annotation.IntDef;
import android.annotation.SystemApi;

/**
 * Basic functionality for hotword detectors.
 *
 * @hide
 */
@SystemApi
public interface HotwordDetector {

    /** No confidence in hotword detector result. */
    int CONFIDENCE_LEVEL_NONE = 0;

    /** Small confidence in hotword detector result. */
    int CONFIDENCE_LEVEL_LOW = 1;

    /** Medium confidence in hotword detector result. */
    int CONFIDENCE_LEVEL_MEDIUM = 2;

    /** High confidence in hotword detector result. */
    int CONFIDENCE_LEVEL_HIGH = 3;

    /** @hide */
    @IntDef(prefix = { "CONFIDENCE_LEVEL_" }, value = {
            CONFIDENCE_LEVEL_NONE,
            CONFIDENCE_LEVEL_LOW,
            CONFIDENCE_LEVEL_MEDIUM,
            CONFIDENCE_LEVEL_HIGH
    })
    @interface HotwordConfidenceLevelValue {}
}
