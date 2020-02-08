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

package com.android.server.autofill.ui;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

/**
 * This class is the root view for an inline suggestion. It is responsible for
 * detecting the click on the item and to also transfer input focus to the IME
 * window if we detect the user is scrolling.
 */
 // TODO(b/146453086) Move to ExtServices and add @SystemApi to transfer touch focus
@SuppressLint("ViewConstructor")
class InlineSuggestionRoot extends FrameLayout {
    private static final String LOG_TAG = InlineSuggestionRoot.class.getSimpleName();

    private final @NonNull Runnable mOnErrorCallback;
    private final int mTouchSlop;

    private float mDownX;
    private float mDownY;

    InlineSuggestionRoot(@NonNull Context context, @NonNull Runnable onErrorCallback) {
        super(context);
        mOnErrorCallback = onErrorCallback;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(false);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDownX = event.getX();
                mDownY = event.getY();
            } break;

            case MotionEvent.ACTION_MOVE: {
                final float distance = MathUtils.dist(mDownX, mDownY,
                        event.getX(), event.getY());
                if (distance > mTouchSlop) {
                    transferTouchFocusToImeWindow();
                }
            } break;
        }
        return super.onTouchEvent(event);
    }

    private void transferTouchFocusToImeWindow() {
        final WindowManagerInternal windowManagerInternal = LocalServices.getService(
                WindowManagerInternal.class);
        if (!windowManagerInternal.transferTouchFocusToImeWindow(getViewRootImpl().getInputToken(),
                getContext().getDisplayId())) {
            Log.e(LOG_TAG, "Cannot transfer touch focus from suggestion to IME");
            mOnErrorCallback.run();
        }
    }
}
