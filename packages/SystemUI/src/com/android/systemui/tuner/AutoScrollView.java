/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.tuner;

import android.content.Context;
import android.util.AttributeSet;
import android.view.DragEvent;
import android.widget.ScrollView;

public class AutoScrollView extends ScrollView {

    private static final float SCROLL_PERCENT = .10f;

    public AutoScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public boolean onDragEvent(DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_LOCATION:
                int y = (int) event.getY();
                int height = getHeight();
                int scrollPadding = (int) (height * SCROLL_PERCENT);
                if (y < scrollPadding) {
                    scrollBy(0, y - scrollPadding);
                } else if (y > height - scrollPadding) {
                    scrollBy(0, y - height + scrollPadding);
                }
                break;
        }
        return false;
    }
}
