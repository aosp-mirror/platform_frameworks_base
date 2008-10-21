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

package android.text.style;

import android.graphics.Paint;
import android.text.TextPaint;

public class AbsoluteSizeSpan extends MetricAffectingSpan {

    private int mSize;

    public AbsoluteSizeSpan(int size) {
        mSize = size;
    }

    public int getSize() {
        return mSize;
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        ds.setTextSize(mSize);
    }

    @Override
    public void updateMeasureState(TextPaint ds) {
        ds.setTextSize(mSize);
    }
}
