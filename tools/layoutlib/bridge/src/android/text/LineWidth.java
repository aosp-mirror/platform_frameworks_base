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

// Based on the native implementation of LineWidth in
// frameworks/base/core/jni/android_text_StaticLayout.cpp revision b808260
public class LineWidth {
    private final float mFirstWidth;
    private final int mFirstWidthLineCount;
    private float mRestWidth;

    public LineWidth(float firstWidth, int firstWidthLineCount, float restWidth) {
        mFirstWidth = firstWidth;
        mFirstWidthLineCount = firstWidthLineCount;
        mRestWidth = restWidth;
    }

    public float getLineWidth(int line) {
        return (line < mFirstWidthLineCount) ? mFirstWidth : mRestWidth;
    }
}
