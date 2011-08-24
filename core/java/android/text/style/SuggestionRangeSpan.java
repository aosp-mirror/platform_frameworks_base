/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.graphics.Color;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * A SuggestionRangeSpan is used to show which part of an EditText is affected by a suggestion
 * popup window.
 *
 * @hide
 */
public class SuggestionRangeSpan extends CharacterStyle implements ParcelableSpan {
    @Override
    public void updateDrawState(TextPaint tp) {
        tp.setColor(Color.GREEN);            
    }

    public SuggestionRangeSpan() { /* Nothing to do*/ }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) { /* Nothing to do*/ }

    @Override
    public int getSpanTypeId() {
        return TextUtils.SUGGESTION_RANGE_SPAN;
    }
}
