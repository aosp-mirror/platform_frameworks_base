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

package com.android.systemui.screenshot;

import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_ACCESSIBILITY_ACTIONS;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_GLOBAL_ACTIONS;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_OTHER;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_OVERVIEW;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_VENDOR_GESTURE;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

public enum ScreenshotEvent implements UiEventLogger.UiEventEnum {
    @UiEvent(doc = "screenshot requested from global actions")
    SCREENSHOT_REQUESTED_GLOBAL_ACTIONS(302),
    @UiEvent(doc = "screenshot requested from key chord")
    SCREENSHOT_REQUESTED_KEY_CHORD(303),
    @UiEvent(doc = "screenshot requested from other key press (e.g. ctrl-s)")
    SCREENSHOT_REQUESTED_KEY_OTHER(384),
    @UiEvent(doc = "screenshot requested from overview")
    SCREENSHOT_REQUESTED_OVERVIEW(304),
    @UiEvent(doc = "screenshot requested from accessibility actions")
    SCREENSHOT_REQUESTED_ACCESSIBILITY_ACTIONS(382),
    @UiEvent(doc = "screenshot requested from vendor gesture")
    SCREENSHOT_REQUESTED_VENDOR_GESTURE(638),
    @UiEvent(doc = "screenshot requested (other)")
    SCREENSHOT_REQUESTED_OTHER(305),
    @UiEvent(doc = "screenshot was saved")
    SCREENSHOT_SAVED(306),
    @UiEvent(doc = "screenshot failed to save")
    SCREENSHOT_NOT_SAVED(336),
    @UiEvent(doc = "failed to capture screenshot")
    SCREENSHOT_CAPTURE_FAILED(1281),
    @UiEvent(doc = "screenshot preview tapped")
    SCREENSHOT_PREVIEW_TAPPED(307),
    @UiEvent(doc = "screenshot edit button tapped")
    SCREENSHOT_EDIT_TAPPED(308),
    @UiEvent(doc = "screenshot share button tapped")
    SCREENSHOT_SHARE_TAPPED(309),
    @UiEvent(doc = "screenshot smart action chip tapped")
    SCREENSHOT_SMART_ACTION_TAPPED(374),
    @UiEvent(doc = "screenshot scroll tapped")
    SCREENSHOT_SCROLL_TAPPED(373),
    @UiEvent(doc = "screenshot interaction timed out")
    SCREENSHOT_INTERACTION_TIMEOUT(310),
    @UiEvent(doc = "screenshot explicitly dismissed")
    SCREENSHOT_EXPLICIT_DISMISSAL(311),
    @UiEvent(doc = "screenshot swiped to dismiss")
    SCREENSHOT_SWIPE_DISMISSED(656),
    @UiEvent(doc = "screenshot dismissed, miscellaneous reason")
    SCREENSHOT_DISMISSED_OTHER(1076),
    @UiEvent(doc = "screenshot reentered for new screenshot")
    SCREENSHOT_REENTERED(640),
    @UiEvent(doc = "Long screenshot button was shown to the user")
    SCREENSHOT_LONG_SCREENSHOT_IMPRESSION(687),
    @UiEvent(doc = "User has requested a long screenshot")
    SCREENSHOT_LONG_SCREENSHOT_REQUESTED(688),
    @UiEvent(doc = "User has shared a long screenshot")
    SCREENSHOT_LONG_SCREENSHOT_SHARE(689),
    @UiEvent(doc = "User has sent a long screenshot to the editor")
    SCREENSHOT_LONG_SCREENSHOT_EDIT(690),
    @UiEvent(doc = "A long screenshot capture has started")
    SCREENSHOT_LONG_SCREENSHOT_STARTED(880),
    @UiEvent(doc = "The long screenshot capture failed")
    SCREENSHOT_LONG_SCREENSHOT_FAILURE(881),
    @UiEvent(doc = "The long screenshot capture completed successfully")
    SCREENSHOT_LONG_SCREENSHOT_COMPLETED(882),
    @UiEvent(doc = "Long screenshot editor activity started")
    SCREENSHOT_LONG_SCREENSHOT_ACTIVITY_STARTED(889),
    @UiEvent(doc = "Long screenshot editor activity loaded a previously saved screenshot")
    SCREENSHOT_LONG_SCREENSHOT_ACTIVITY_CACHED_IMAGE_LOADED(890),
    @UiEvent(doc = "Long screenshot editor activity finished")
    SCREENSHOT_LONG_SCREENSHOT_ACTIVITY_FINISHED(891),
    @UiEvent(doc = "User has saved a long screenshot to a file")
    SCREENSHOT_LONG_SCREENSHOT_SAVED(910),
    @UiEvent(doc = "User has discarded the result of a long screenshot")
    SCREENSHOT_LONG_SCREENSHOT_EXIT(911),
    @UiEvent(doc = "A screenshot has been taken and saved to work profile")
    SCREENSHOT_SAVED_TO_WORK_PROFILE(1240);

    private final int mId;

    ScreenshotEvent(int id) {
        mId = id;
    }

    @Override
    public int getId() {
        return mId;
    }

    static ScreenshotEvent getScreenshotSource(int source) {
        switch (source) {
            case SCREENSHOT_GLOBAL_ACTIONS:
                return ScreenshotEvent.SCREENSHOT_REQUESTED_GLOBAL_ACTIONS;
            case SCREENSHOT_KEY_CHORD:
                return ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_CHORD;
            case SCREENSHOT_KEY_OTHER:
                return ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_OTHER;
            case SCREENSHOT_OVERVIEW:
                return ScreenshotEvent.SCREENSHOT_REQUESTED_OVERVIEW;
            case SCREENSHOT_ACCESSIBILITY_ACTIONS:
                return ScreenshotEvent.SCREENSHOT_REQUESTED_ACCESSIBILITY_ACTIONS;
            case SCREENSHOT_VENDOR_GESTURE:
                return ScreenshotEvent.SCREENSHOT_REQUESTED_VENDOR_GESTURE;
            case SCREENSHOT_OTHER:
            default:
                return ScreenshotEvent.SCREENSHOT_REQUESTED_OTHER;
        }
    }
}
