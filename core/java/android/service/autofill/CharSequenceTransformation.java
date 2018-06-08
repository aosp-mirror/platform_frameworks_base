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
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.Pair;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.util.Preconditions;

import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces a {@link TextView} child of a {@link CustomDescription} with the contents of one or
 * more regular expressions (regexs).
 *
 * <p>When it contains more than one field, the fields that match their regex are added to the
 * overall transformation result.
 *
 * <p>For example, a transformation to mask a credit card number contained in just one field would
 * be:
 *
 * <pre class="prettyprint">
 * new CharSequenceTransformation
 *     .Builder(ccNumberId, Pattern.compile("^.*(\\d\\d\\d\\d)$"), "...$1")
 *     .build();
 * </pre>
 *
 * <p>But a transformation that generates a {@code Exp: MM / YYYY} credit expiration date from two
 * fields (month and year) would be:
 *
 * <pre class="prettyprint">
 * new CharSequenceTransformation
 *   .Builder(ccExpMonthId, Pattern.compile("^(\\d\\d)$"), "Exp: $1")
 *   .addField(ccExpYearId, Pattern.compile("^(\\d\\d\\d\\d)$"), " / $1");
 * </pre>
 */
public final class CharSequenceTransformation extends InternalTransformation implements
        Transformation, Parcelable {
    private static final String TAG = "CharSequenceTransformation";

    // Must use LinkedHashMap to preserve insertion order.
    @NonNull private final LinkedHashMap<AutofillId, Pair<Pattern, String>> mFields;

    private CharSequenceTransformation(Builder builder) {
        mFields = builder.mFields;
    }

    /** @hide */
    @Override
    @TestApi
    public void apply(@NonNull ValueFinder finder, @NonNull RemoteViews parentTemplate,
            int childViewId) throws Exception {
        final StringBuilder converted = new StringBuilder();
        final int size = mFields.size();
        if (sDebug) Log.d(TAG, size + " multiple fields on id " + childViewId);
        for (Entry<AutofillId, Pair<Pattern, String>> entry : mFields.entrySet()) {
            final AutofillId id = entry.getKey();
            final Pair<Pattern, String> field = entry.getValue();
            final String value = finder.findByAutofillId(id);
            if (value == null) {
                Log.w(TAG, "No value for id " + id);
                return;
            }
            try {
                final Matcher matcher = field.first.matcher(value);
                if (!matcher.find()) {
                    if (sDebug) Log.d(TAG, "match for " + field.first + " failed on id " + id);
                    return;
                }
                // replaceAll throws an exception if the subst is invalid
                final String convertedValue = matcher.replaceAll(field.second);
                converted.append(convertedValue);
            } catch (Exception e) {
                // Do not log full exception to avoid PII leaking
                Log.w(TAG, "Cannot apply " + field.first.pattern() + "->" + field.second + " to "
                        + "field with autofill id" + id + ": " + e.getClass());
                throw e;
            }
        }
        parentTemplate.setCharSequence(childViewId, "setText", converted);
    }

    /**
     * Builder for {@link CharSequenceTransformation} objects.
     */
    public static class Builder {

        // Must use LinkedHashMap to preserve insertion order.
        @NonNull private final LinkedHashMap<AutofillId, Pair<Pattern, String>> mFields =
                new LinkedHashMap<>();
        private boolean mDestroyed;

        /**
         * Creates a new builder and adds the first transformed contents of a field to the overall
         * result of this transformation.
         *
         * @param id id of the screen field.
         * @param regex regular expression with groups (delimited by {@code (} and {@code (}) that
         * are used to substitute parts of the value.
         * @param subst the string that substitutes the matched regex, using {@code $} for
         * group substitution ({@code $1} for 1st group match, {@code $2} for 2nd, etc).
         */
        public Builder(@NonNull AutofillId id, @NonNull Pattern regex, @NonNull String subst) {
            addField(id, regex, subst);
        }

        /**
         * Adds the transformed contents of a field to the overall result of this transformation.
         *
         * @param id id of the screen field.
         * @param regex regular expression with groups (delimited by {@code (} and {@code (}) that
         * are used to substitute parts of the value.
         * @param subst the string that substitutes the matched regex, using {@code $} for
         * group substitution ({@code $1} for 1st group match, {@code $2} for 2nd, etc).
         *
         * @return this builder.
         */
        public Builder addField(@NonNull AutofillId id, @NonNull Pattern regex,
                @NonNull String subst) {
            throwIfDestroyed();
            Preconditions.checkNotNull(id);
            Preconditions.checkNotNull(regex);
            Preconditions.checkNotNull(subst);

            mFields.put(id, new Pair<>(regex, subst));
            return this;
        }

        /**
         * Creates a new {@link CharSequenceTransformation} instance.
         */
        public CharSequenceTransformation build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new CharSequenceTransformation(this);
        }

        private void throwIfDestroyed() {
            Preconditions.checkState(!mDestroyed, "Already called build()");
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "MultipleViewsCharSequenceTransformation: [fields=" + mFields + "]";
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
        final int size = mFields.size();
        final AutofillId[] ids = new AutofillId[size];
        final Pattern[] regexs = new Pattern[size];
        final String[] substs = new String[size];
        Pair<Pattern, String> pair;
        int i = 0;
        for (Entry<AutofillId, Pair<Pattern, String>> entry : mFields.entrySet()) {
            ids[i] = entry.getKey();
            pair = entry.getValue();
            regexs[i] = pair.first;
            substs[i] = pair.second;
            i++;
        }

        parcel.writeParcelableArray(ids, flags);
        parcel.writeSerializable(regexs);
        parcel.writeStringArray(substs);
    }

    public static final Parcelable.Creator<CharSequenceTransformation> CREATOR =
            new Parcelable.Creator<CharSequenceTransformation>() {
        @Override
        public CharSequenceTransformation createFromParcel(Parcel parcel) {
            final AutofillId[] ids = parcel.readParcelableArray(null, AutofillId.class);
            final Pattern[] regexs = (Pattern[]) parcel.readSerializable();
            final String[] substs = parcel.createStringArray();

            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final CharSequenceTransformation.Builder builder =
                    new CharSequenceTransformation.Builder(ids[0], regexs[0], substs[0]);

            final int size = ids.length;
            for (int i = 1; i < size; i++) {
                builder.addField(ids[i], regexs[i], substs[i]);
            }
            return builder.build();
        }

        @Override
        public CharSequenceTransformation[] newArray(int size) {
            return new CharSequenceTransformation[size];
        }
    };
}
