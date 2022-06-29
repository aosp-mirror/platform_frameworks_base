/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.service.autofill;

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;
import android.view.autofill.AutofillValue;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizes a text {@link AutofillValue} using a regular expression (regex) substitution.
 *
 * <p>For example, to remove spaces from groups of 4-digits in a credit card:
 *
 * <pre class="prettyprint">
 * new TextValueSanitizer(Pattern.compile("^(\\d{4})\\s?(\\d{4})\\s?(\\d{4})\\s?(\\d{4})$"),
 *     "$1$2$3$4")
 * </pre>
 */
public final class TextValueSanitizer extends InternalSanitizer implements
        Sanitizer, Parcelable {
    private static final String TAG = "TextValueSanitizer";

    private final Pattern mRegex;
    private final String mSubst;

    /**
     * Default constructor.
     *
     * @param regex regular expression with groups (delimited by {@code (} and {@code (}) that
     * are used to substitute parts of the {@link AutofillValue#getTextValue() text value}.
     * @param subst the string that substitutes the matched regex, using {@code $} for
     * group substitution ({@code $1} for 1st group match, {@code $2} for 2nd, etc).
     */
    public TextValueSanitizer(@NonNull Pattern regex, @NonNull String subst) {
        mRegex = Objects.requireNonNull(regex);
        mSubst = Objects.requireNonNull(subst);
    }

    /** @hide */
    @Override
    @TestApi
    @Nullable
    public AutofillValue sanitize(@NonNull AutofillValue value) {
        if (value == null) {
            Slog.w(TAG, "sanitize() called with null value");
            return null;
        }
        if (!value.isText()) {
            if (sDebug) Slog.d(TAG, "sanitize() called with non-text value: " + value);
            return null;
        }

        final CharSequence text = value.getTextValue();

        try {
            final Matcher matcher = mRegex.matcher(text);
            if (!matcher.matches()) {
                if (sDebug) Slog.d(TAG, "sanitize(): " + mRegex + " failed for " + value);
                return null;
            }

            final CharSequence sanitized = matcher.replaceAll(mSubst);
            return AutofillValue.forText(sanitized);
        } catch (Exception e) {
            Slog.w(TAG, "Exception evaluating " + mRegex + "/" + mSubst + ": " + e);
            return null;
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "TextValueSanitizer: [regex=" + mRegex + ", subst=" + mSubst + "]";
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeSerializable(mRegex);
        parcel.writeString(mSubst);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TextValueSanitizer> CREATOR =
            new Parcelable.Creator<TextValueSanitizer>() {
        @Override
        public TextValueSanitizer createFromParcel(Parcel parcel) {
            return new TextValueSanitizer((Pattern) parcel.readSerializable(java.util.regex.Pattern.class.getClassLoader(), java.util.regex.Pattern.class), parcel.readString());
        }

        @Override
        public TextValueSanitizer[] newArray(int size) {
            return new TextValueSanitizer[size];
        }
    };
}
