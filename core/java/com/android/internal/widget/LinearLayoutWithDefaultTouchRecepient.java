/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.MotionEvent;
import android.widget.LinearLayout;


/**
 * Like a normal linear layout, but supports dispatching all otherwise unhandled
 * touch events to a particular descendant.  This is for the unlock screen, so
 * that a wider range of touch events than just the lock pattern widget can kick
 * off a lock pattern if the finger is eventually dragged into the bounds of the
 * lock pattern view.
 */
public class LinearLayoutWithDefaultTouchRecepient extends LinearLayout {

    private final Rect mTempRect = new Rect();
    private View mDefaultTouchRecepient;

    public LinearLayoutWithDefaultTouchRecepient(Context context) {
        super(context);
    }

    public LinearLayoutWithDefaultTouchRecepient(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setDefaultTouchRecepient(View defaultTouchRecepient) {
        mDefaultTouchRecepient = defaultTouchRecepient;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mDefaultTouchRecepient == null) {
            return super.dispatchTouchEvent(ev);
        }

        if (super.dispatchTouchEvent(ev)) {
            return true;
        }
        mTempRect.set(0, 0, 0, 0);
        offsetRectIntoDescendantCoords(mDefaultTouchRecepient, mTempRect);
        ev.setLocation(ev.getX() + mTempRect.left, ev.getY() + mTempRect.top);
        return mDefaultTouchRecepient.dispatchTouchEvent(ev);
    }

}
