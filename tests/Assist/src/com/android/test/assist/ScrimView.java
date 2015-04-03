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

package com.android.test.assist;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

public class ScrimView extends View {

    public ScrimView(Context context) {
        super(context);
    }

    public ScrimView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ScrimView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
