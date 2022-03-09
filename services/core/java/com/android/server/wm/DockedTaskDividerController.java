/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.wm;

import android.graphics.Rect;

/**
 * Keeps information about the docked task divider.
 */
public class DockedTaskDividerController {

    private final DisplayContent mDisplayContent;
    private boolean mResizing;

    private final Rect mTouchRegion = new Rect();

    DockedTaskDividerController(DisplayContent displayContent) {
        mDisplayContent = displayContent;
    }

    boolean isResizing() {
        return mResizing;
    }

    void setResizing(boolean resizing) {
        if (mResizing != resizing) {
            mResizing = resizing;
            resetDragResizingChangeReported();
        }
    }

    void setTouchRegion(Rect touchRegion) {
        mTouchRegion.set(touchRegion);
        // We need to report touchable region changes to accessibility.
        if (mDisplayContent.mWmService.mAccessibilityController.hasCallbacks()) {
            mDisplayContent.mWmService.mAccessibilityController.onSomeWindowResizedOrMoved(
                    mDisplayContent.getDisplayId());
        }
    }

    void getTouchRegion(Rect outRegion) {
        outRegion.set(mTouchRegion);
    }

    private void resetDragResizingChangeReported() {
        mDisplayContent.forAllWindows(WindowState::resetDragResizingChangeReported,
                true /* traverseTopToBottom */);
    }
}
