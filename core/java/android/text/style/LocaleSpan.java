/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;
import java.util.Locale;

/**
 * Changes the {@link Locale} of the text to which the span is attached.
 */
public class LocaleSpan extends MetricAffectingSpan implements ParcelableSpan {
    private final Locale mLocale;

    /**
     * Creates a LocaleSpan.
     * @param locale The {@link Locale} of the text to which the span is
     * attached.
     */
    public LocaleSpan(Locale locale) {
        mLocale = locale;
    }

    public LocaleSpan(Parcel src) {
        mLocale = new Locale(src.readString(), src.readString(), src.readString());
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    public int getSpanTypeIdInternal() {
        return TextUtils.LOCALE_SPAN;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    /** @hide */
    public void writeToParcelInternal(Parcel dest, int flags) {
        dest.writeString(mLocale.getLanguage());
        dest.writeString(mLocale.getCountry());
        dest.writeString(mLocale.getVariant());
    }

    /**
     * Returns the {@link Locale}.
     *
     * @return The {@link Locale} for this span.
     */
    public Locale getLocale() {
        return mLocale;
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        apply(ds, mLocale);
    }

    @Override
    public void updateMeasureState(TextPaint paint) {
        apply(paint, mLocale);
    }

    private static void apply(Paint paint, Locale locale) {
        paint.setTextLocale(locale);
    }
}
