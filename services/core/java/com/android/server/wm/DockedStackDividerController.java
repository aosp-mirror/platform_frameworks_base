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

import android.content.Context;
import android.graphics.Rect;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;

/**
 * Keeps information about the docked stack divider.
 */
public class DockedStackDividerController {

    private static final String TAG = "DockedStackDividerController";

    private final DisplayContent mDisplayContent;
    private final int mDividerWindowWidth;
    private final int mDividerInsets;
    private boolean mResizing;
    private WindowState mWindow;
    private final Rect mTmpRect = new Rect();

    DockedStackDividerController(Context context, DisplayContent displayContent) {
        mDisplayContent = displayContent;
        mDividerWindowWidth = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mDividerInsets = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);
    }

    boolean isResizing() {
        return mResizing;
    }

    int getContentWidth() {
        return mDividerWindowWidth - 2 * mDividerInsets;
    }

    void setResizing(boolean resizing) {
        mResizing = resizing;
    }

    void setWindow(WindowState window) {
        mWindow = window;
        reevaluateVisibility();
    }

    void reevaluateVisibility() {
        if (mWindow == null) return;
        TaskStack stack = mDisplayContent.mService.mStackIdToStack.get(DOCKED_STACK_ID);
        if (stack != null && stack.isVisibleLocked()) {
            mWindow.showLw(true /* doAnimation */);
        } else {
            mWindow.hideLw(true /* doAnimation */);
        }
    }

    void positionDockedStackedDivider(Rect frame) {
        TaskStack stack = mDisplayContent.getDockedStackLocked();
        if (stack == null) {
            // Unfortunately we might end up with still having a divider, even though the underlying
            // stack was already removed. This is because we are on AM thread and the removal of the
            // divider was deferred to WM thread and hasn't happened yet.
            return;
        }
        int side = stack.getDockSide();
        stack.getBounds(mTmpRect);
        switch (side) {
            case DOCKED_LEFT:
                frame.set(mTmpRect.right - mDividerInsets, frame.top,
                        mTmpRect.right + frame.width() - mDividerInsets, frame.bottom);
                break;
            case DOCKED_TOP:
                frame.set(frame.left, mTmpRect.bottom - mDividerInsets,
                        mTmpRect.right, mTmpRect.bottom + frame.height() - mDividerInsets);
                break;
            case DOCKED_RIGHT:
                frame.set(mTmpRect.left - frame.width() + mDividerInsets, frame.top,
                        mTmpRect.left + mDividerInsets, frame.bottom);
                break;
            case DOCKED_BOTTOM:
                frame.set(frame.left, mTmpRect.top - frame.height() + mDividerInsets,
                        frame.right, mTmpRect.top + mDividerInsets);
                break;
        }
    }
}
