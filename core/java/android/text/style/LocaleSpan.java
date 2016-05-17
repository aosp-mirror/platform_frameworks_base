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

import com.android.internal.util.Preconditions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Paint;
import android.os.LocaleList;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.Locale;

/**
 * Changes the {@link Locale} of the text to which the span is attached.
 */
public class LocaleSpan extends MetricAffectingSpan implements ParcelableSpan {
    @NonNull
    private final LocaleList mLocales;

    /**
     * Creates a {@link LocaleSpan} from a well-formed {@link Locale}.  Note that only
     * {@link Locale} objects that can be created by {@link Locale#forLanguageTag(String)} are
     * supported.
     *
     * <p><b>Caveat:</b> Do not specify any {@link Locale} object that cannot be created by
     * {@link Locale#forLanguageTag(String)}.  {@code new Locale(" a ", " b c", " d")} is an
     * example of such a malformed {@link Locale} object.</p>
     *
     * @param locale The {@link Locale} of the text to which the span is attached.
     *
     * @see #LocaleSpan(LocaleList)
     */
    public LocaleSpan(@Nullable Locale locale) {
        mLocales = locale == null ? LocaleList.getEmptyLocaleList() : new LocaleList(locale);
    }

    /**
     * Creates a {@link LocaleSpan} from {@link LocaleList}.
     *
     * @param locales The {@link LocaleList} of the text to which the span is attached.
     * @throws NullPointerException if {@code locales} is null
     */
    public LocaleSpan(@NonNull LocaleList locales) {
        Preconditions.checkNotNull(locales, "locales cannot be null");
        mLocales = locales;
    }

    public LocaleSpan(Parcel source) {
        mLocales = LocaleList.CREATOR.createFromParcel(source);
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
        mLocales.writeToParcel(dest, flags);
    }

    /**
     * @return The {@link Locale} for this span.  If multiple locales are associated with this
     * span, only the first locale is returned.  {@code null} if no {@link Locale} is specified.
     *
     * @see LocaleList#get()
     * @see #getLocales()
     */
    @Nullable
    public Locale getLocale() {
        return mLocales.get(0);
    }

    /**
     * @return The entire list of locales that are associated with this span.
     */
    @NonNull
    public LocaleList getLocales() {
        return mLocales;
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        apply(ds, mLocales);
    }

    @Override
    public void updateMeasureState(TextPaint paint) {
        apply(paint, mLocales);
    }

    private static void apply(@NonNull Paint paint, @NonNull LocaleList locales) {
        paint.setTextLocales(locales);
    }
}
