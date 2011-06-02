/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.inputmethodservice;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Button;

/**
 * Specialization of {@link Button} that ignores the window not being focused.
 */
class ExtractButton extends Button {
    public ExtractButton(Context context) {
        super(context, null);
    }

    public ExtractButton(Context context, AttributeSet attrs) {
        super(context, attrs, com.android.internal.R.attr.buttonStyle);
    }

    public ExtractButton(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }
    
    /**
     * Pretend like the window this view is in always has focus, so it will
     * highlight when selected.
     */
    @Override public boolean hasWindowFocus() {
        return isEnabled() && getVisibility() == VISIBLE ? true : false;
    }
}
