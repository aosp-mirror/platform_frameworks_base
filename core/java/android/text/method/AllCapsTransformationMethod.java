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
package android.text.method;

import android.content.Context;
import android.graphics.Rect;
import android.icu.text.CaseMap;
import android.icu.text.Edits;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/**
 * Transforms source text into an ALL CAPS string, locale-aware.
 *
 * @hide
 */
public class AllCapsTransformationMethod implements TransformationMethod2 {
    private static final String TAG = "AllCapsTransformationMethod";

    private boolean mEnabled;
    private Locale mLocale;

    public AllCapsTransformationMethod(Context context) {
        mLocale = context.getResources().getConfiguration().getLocales().get(0);
    }

    @Override
    public CharSequence getTransformation(CharSequence source, View view) {
        if (!mEnabled) {
            Log.w(TAG, "Caller did not enable length changes; not transforming text");
            return source;
        }

        if (source == null) {
            return null;
        }

        Locale locale = null;
        if (view instanceof TextView) {
            locale = ((TextView)view).getTextLocale();
        }
        if (locale == null) {
            locale = mLocale;
        }

        if (!(source instanceof Spanned)) { // No spans
            return CaseMap.toUpper().apply(
                    locale, source, new StringBuilder(),
                    null /* we don't need the edits */);
        }

        final Edits edits = new Edits();
        final SpannableStringBuilder result = CaseMap.toUpper().apply(
                locale, source, new SpannableStringBuilder(), edits);
        if (!edits.hasChanges()) {
            // No changes happened while capitalizing. We can return the source as it was.
            return source;
        }

        final Edits.Iterator iterator = edits.getFineIterator();
        final Spanned spanned = (Spanned) source;
        final int sourceLength = source.length();
        final Object[] spans = spanned.getSpans(0, sourceLength, Object.class);
        for (Object span : spans) {
            final int sourceStart = spanned.getSpanStart(span);
            final int sourceEnd = spanned.getSpanEnd(span);
            final int flags = spanned.getSpanFlags(span);
            // Make sure the indexes are not at the end of the string, since in that case
            // iterator.findSourceIndex() would fail.
            final int destStart = sourceStart == sourceLength ? result.length() :
                    mapToDest(iterator, sourceStart);
            final int destEnd = sourceEnd == sourceLength ? result.length() :
                    mapToDest(iterator, sourceEnd);
            result.setSpan(span, destStart, destEnd, flags);
        }
        return result;
    }

    private static int mapToDest(Edits.Iterator iterator, int sourceIndex) {
        // Guaranteed to succeed if sourceIndex < source.length().
        iterator.findSourceIndex(sourceIndex);
        if (sourceIndex == iterator.sourceIndex()) {
            return iterator.destinationIndex();
        }
        // We handle the situation differently depending on if we are in the changed slice or an
        // unchanged one: In an unchanged slice, we can find the exact location the span
        // boundary was before and map there.
        //
        // But in a changed slice, we need to treat the whole destination slice as an atomic unit.
        // We adjust the span boundary to the end of that slice to reduce of the chance of adjacent
        // spans in the source overlapping in the result. (The choice for the end vs the beginning
        // is somewhat arbitrary, but was taken because we except to see slightly more spans only
        // affecting a base character compared to spans only affecting a combining character.)
        if (iterator.hasChange()) {
            return iterator.destinationIndex() + iterator.newLength();
        } else {
            // Move the index 1:1 along with this unchanged piece of text.
            return iterator.destinationIndex() + (sourceIndex - iterator.sourceIndex());
        }
    }

    @Override
    public void onFocusChanged(View view, CharSequence sourceText, boolean focused, int direction,
            Rect previouslyFocusedRect) {
    }

    @Override
    public void setLengthChangesAllowed(boolean allowLengthChanges) {
        mEnabled = allowLengthChanges;
    }

}
