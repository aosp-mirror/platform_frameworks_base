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
package com.android.systemui.screenshot.appclips;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

enum AppClipsEvent implements UiEventLogger.UiEventEnum {

    @UiEvent(doc = "Notes application triggered the screenshot for notes")
    SCREENSHOT_FOR_NOTE_TRIGGERED(1308),
    @UiEvent(doc = "User accepted the screenshot to be sent to the notes app")
    SCREENSHOT_FOR_NOTE_ACCEPTED(1309),
    @UiEvent(doc = "User cancelled the screenshot for notes app flow")
    SCREENSHOT_FOR_NOTE_CANCELLED(1310);

    private final int mId;

    AppClipsEvent(int id) {
        mId = id;
    }

    @Override
    public int getId() {
        return mId;
    }
}
