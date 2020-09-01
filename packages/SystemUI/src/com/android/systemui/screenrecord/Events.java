/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

/**
 * Events related to the SystemUI screen recorder
 */
public class Events {

    public enum ScreenRecordEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Screen recording was started")
        SCREEN_RECORD_START(299),
        @UiEvent(doc = "Screen recording was stopped from the quick settings tile")
        SCREEN_RECORD_END_QS_TILE(300),
        @UiEvent(doc = "Screen recording was stopped from the notification")
        SCREEN_RECORD_END_NOTIFICATION(301);

        private final int mId;
        ScreenRecordEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
}
