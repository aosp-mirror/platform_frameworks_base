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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Button;

import com.android.systemui.animation.LaunchableView;
import com.android.systemui.animation.LaunchableViewDelegate;

import kotlin.Unit;

/**
 * A Button which doesn't have overlapping drawing commands
 */
public class AlphaOptimizedButton extends Button implements LaunchableView {
    private LaunchableViewDelegate mDelegate = new LaunchableViewDelegate(this,
            (visibility) -> {
                super.setVisibility(visibility);
                return Unit.INSTANCE;
            });

    public AlphaOptimizedButton(Context context) {
        super(context);
    }

    public AlphaOptimizedButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AlphaOptimizedButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AlphaOptimizedButton(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    public void setShouldBlockVisibilityChanges(boolean block) {
        mDelegate.setShouldBlockVisibilityChanges(block);
    }

    @Override
    public void setVisibility(int visibility) {
        mDelegate.setVisibility(visibility);
    }
}
