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

package com.android.systemui.settings.brightness;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

public enum BrightnessSliderEvent implements UiEventLogger.UiEventEnum {

    @UiEvent(doc = "brightness slider started to track touch")
    BRIGHTNESS_SLIDER_STARTED_TRACKING_TOUCH(1472),
    @UiEvent(doc = "brightness slider stopped tracking touch")
    BRIGHTNESS_SLIDER_STOPPED_TRACKING_TOUCH(1473);

    private final int mId;

    BrightnessSliderEvent(int id) {
        mId = id;
    }

    @Override
    public int getId() {
        return mId;
    }
}
