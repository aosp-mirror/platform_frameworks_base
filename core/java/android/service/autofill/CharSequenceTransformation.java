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
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.util.Preconditions;

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
 * new CharSequenceTransformation.Builder()
 *   .addField(ccNumberId, "^.*(\\d\\d\\d\\d)$", "...$1")
 *   .build();
 * </pre>
 *
 * <p>But a tranformation that generates a {@code Exp: MM / YYYY} credit expiration date from two
 * fields (month and year) would be:
 *
 * <pre class="prettyprint">
 * new CharSequenceTransformation.Builder()
 *   .addField(ccExpMonthId, "^(\\d\\d)$", "Exp: $1")
 *   .addField(ccExpYearId, "^(\\d\\d\\d\\d)$", " / $1");
 * </pre>
 */
//TODO(b/62534917): add unit tests
public final class CharSequenceTransformation extends InternalTransformation implements Parcelable {
    private static final String TAG = "CharSequenceTransformation";
    private final ArrayMap<AutofillId, Pair<String, String>> mFields;

    private CharSequenceTransformation(Builder builder) {
        mFields = builder.mFields;
    }

    /** @hide */
    @Override
    public void apply(@NonNull ValueFinder finder, @NonNull RemoteViews parentTemplate,
            int childViewId) {
        final StringBuilder converted = new StringBuilder();
        final int size = mFields.size();
        if (sDebug) Log.d(TAG, size + " multiple fields on id " + childViewId);
        for (int i = 0; i < size; i++) {
            final AutofillId id = mFields.keyAt(i);
            final Pair<String, String> regex = mFields.valueAt(i);
            final String value = finder.findByAutofillId(id);
            if (value == null) {
                Log.w(TAG, "No value for id " + id);
                return;
            }
            final String convertedValue = value.replaceAll(regex.first, regex.second);
            converted.append(convertedValue);
        }
        parentTemplate.setCharSequence(childViewId, "setText", converted);
    }

    /**
     * Builder for {@link CharSequenceTransformation} objects.
     */
    public static class Builder {
        private ArrayMap<AutofillId, Pair<String, String>> mFields;
        private boolean mDestroyed;

        //TODO(b/62534917): add constructor that takes a field so we force it to have at least one
        // (and then remove the check for empty from build())

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
        public Builder addField(@NonNull AutofillId id, @NonNull String regex,
                @NonNull String subst) {
            //TODO(b/62534917): throw exception if regex /subts are invalid
            throwIfDestroyed();
            Preconditions.checkNotNull(id);
            Preconditions.checkNotNull(regex);
            Preconditions.checkNotNull(subst);
            if (mFields == null) {
                mFields = new ArrayMap<>();
            }
            mFields.put(id, new Pair<>(regex, subst));
            return this;
        }

        /**
         * Creates a new {@link CharSequenceTransformation} instance.
         *
         * @throws IllegalStateException if no call to {@link #addField(AutofillId, String, String)}
         * was made.
         */
        public CharSequenceTransformation build() {
            throwIfDestroyed();
            Preconditions.checkState(mFields != null && !mFields.isEmpty(),
                    "Must add at least one field");
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
        final String[] regexs = new String[size];
        final String[] substs = new String[size];
        Pair<String, String> pair;
        for (int i = 0; i < size; i++) {
            ids[i] = mFields.keyAt(i);
            pair = mFields.valueAt(i);
            regexs[i] = pair.first;
            substs[i] = pair.second;
        }
        parcel.writeParcelableArray(ids, flags);
        parcel.writeStringArray(regexs);
        parcel.writeStringArray(substs);
    }

    public static final Parcelable.Creator<CharSequenceTransformation> CREATOR =
            new Parcelable.Creator<CharSequenceTransformation>() {
        @Override
        public CharSequenceTransformation createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final CharSequenceTransformation.Builder builder =
                    new CharSequenceTransformation.Builder();
            final AutofillId[] ids = parcel.readParcelableArray(null, AutofillId.class);
            final String[] regexs = parcel.createStringArray();
            final String[] substs = parcel.createStringArray();
            final int size = ids.length;
            for (int i = 0; i < size; i++) {
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
