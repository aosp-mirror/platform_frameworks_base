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

package com.android.systemui.navigationbar;

import static android.view.MotionEvent.ACTION_OUTSIDE;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.android.systemui.navigationbar.buttons.DeadZone;

public class NavigationBarFrame extends FrameLayout {

    private DeadZone mDeadZone = null;

    public NavigationBarFrame(@NonNull Context context) {
        super(context);
    }

    public NavigationBarFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NavigationBarFrame(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setDeadZone(@NonNull DeadZone deadZone) {
        mDeadZone = deadZone;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == ACTION_OUTSIDE) {
            if (mDeadZone != null) {
                return mDeadZone.onTouchEvent(event);
            }
        }
        return super.dispatchTouchEvent(event);
    }
}