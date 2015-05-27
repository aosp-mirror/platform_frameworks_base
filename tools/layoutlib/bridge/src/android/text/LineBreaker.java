/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.text;

import android.annotation.NonNull;
import android.text.StaticLayout.LineBreaks;

import java.util.Collections;
import java.util.List;

// Based on the native implementation of LineBreaker in
// frameworks/base/core/jni/android_text_StaticLayout.cpp revision b808260
public abstract class LineBreaker {

    protected static final int TAB_MASK   = 0x20000000;  // keep in sync with StaticLayout

    protected final @NonNull List<Primitive> mPrimitives;
    protected final @NonNull LineWidth mLineWidth;
    protected final @NonNull TabStops mTabStops;

    public LineBreaker(@NonNull List<Primitive> primitives, @NonNull LineWidth lineWidth,
            @NonNull TabStops tabStops) {
        mPrimitives = Collections.unmodifiableList(primitives);
        mLineWidth = lineWidth;
        mTabStops = tabStops;
    }

    public abstract void computeBreaks(@NonNull LineBreaks breakInfo);
}
