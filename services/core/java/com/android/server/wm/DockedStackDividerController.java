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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

/**
 * Controls showing and hiding of a docked stack divider on the display.
 */
public class DockedStackDividerController {
    private static final String TAG = "DockedStackDivider";
    private final Context mContext;
    private final int mDividerWidth;
    private final DisplayContent mDisplayContent;
    private View mView;
    private Rect mTmpRect = new Rect();

    DockedStackDividerController(Context context, DisplayContent displayContent) {
        mContext = context;
        mDisplayContent = displayContent;
        mDividerWidth = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
    }

    private void addDivider() {
        View view = LayoutInflater.from(mContext).inflate(
                com.android.internal.R.layout.docked_stack_divider, null);
        WindowManagerGlobal manager = WindowManagerGlobal.getInstance();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                mDividerWidth, MATCH_PARENT, TYPE_DOCK_DIVIDER,
                FLAG_TOUCHABLE_WHEN_WAKING | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
                        | FLAG_WATCH_OUTSIDE_TOUCH | FLAG_SPLIT_TOUCH,
                PixelFormat.OPAQUE);
        params.setTitle(TAG);
        manager.addView(view, params, mDisplayContent.getDisplay(), null);
        mView = view;
    }

    private void removeDivider() {
        WindowManagerGlobal manager = WindowManagerGlobal.getInstance();
        manager.removeView(mView, true /* immediate */);
        mView = null;
    }

    boolean hasDivider() {
        return mView != null;
    }

    void update() {
        TaskStack stack = mDisplayContent.getDockedStack();
        if (stack != null && mView == null) {
            addDivider();
        } else if (stack == null && mView != null) {
            removeDivider();
        }
    }

    int getWidth() {
        return mDividerWidth;
    }


    void positionDockedStackedDivider(Rect frame) {
        TaskStack stack = mDisplayContent.getDockedStack();
        if (stack == null) {
            // Unfortunately we might end up with still having a divider, even though the underlying
            // stack was already removed. This is because we are on AM thread and the removal of the
            // divider was deferred to WM thread and hasn't happened yet.
            return;
        }
        final @TaskStack.DockSide int side = stack.getDockSide();
        stack.getBounds(mTmpRect);
        switch (side) {
            case TaskStack.DOCKED_LEFT:
                frame.set(mTmpRect.right, frame.top, mTmpRect.right + frame.width(), frame.bottom);
                break;
            case TaskStack.DOCKED_TOP:
                frame.set(frame.left, mTmpRect.bottom, mTmpRect.right,
                        mTmpRect.bottom + frame.height());
                break;
            case TaskStack.DOCKED_RIGHT:
                frame.set(mTmpRect.left - frame.width(), frame.top, mTmpRect.left, frame.bottom);
                break;
            case TaskStack.DOCKED_BOTTOM:
                frame.set(frame.left, mTmpRect.top - frame.height(), frame.right, mTmpRect.top);
                break;
        }
    }
}
