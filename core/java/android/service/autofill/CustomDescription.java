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
import android.util.Log;
import android.util.SparseArray;
import android.widget.RemoteViews;

import com.android.internal.util.Preconditions;

/**
 * Defines a custom description for the Save UI affordance.
 *
 * <p>This is useful when the autofill service needs to show a detailed view of what would be saved;
 * for example, when the screen contains a credit card, it could display a logo of the credit card
 * bank, the last for digits of the credit card number, and its expiration number.
 *
 * <p>A custom description is made of 2 parts:
 * <ul>
 *   <li>A {@link RemoteViews presentation template} containing children views.
 *   <li>{@link Transformation Transformations} to populate the children views.
 * </ul>
 *
 * <p>For the credit card example mentioned above, the (simplified) template would be:
 *
 * <pre class="prettyprint">
 * &lt;LinearLayout&gt;
 *   &lt;ImageView android:id="@+id/templateccLogo"/&gt;
 *   &lt;TextView android:id="@+id/templateCcNumber"/&gt;
 *   &lt;TextView android:id="@+id/templateExpDate"/&gt;
 * &lt;/LinearLayout&gt;
 * </pre>
 *
 * <p>Which in code translates to:
 *
 * <pre class="prettyprint">
 *   CustomDescription.Builder buider = new Builder(new RemoteViews(pgkName, R.layout.cc_template);
 * </pre>
 *
 * <p>Then the value of each of the 3 children would be changed at runtime based on the the value of
 * the screen fields and the {@link Transformation Transformations}:
 *
 * <pre class="prettyprint">
 * // Image child - different logo for each bank, based on credit card prefix
 * builder.addChild(R.id.templateccLogo,
 *   new ImageTransformation.Builder(ccNumberId)
 *     .addOption("^4815.*$", R.drawable.ic_credit_card_logo1)
 *     .addOption("^1623.*$", R.drawable.ic_credit_card_logo2)
 *     .addOption("^42.*$", R.drawable.ic_credit_card_logo3);
 * // Masked credit card number (as .....LAST_4_DIGITS)
 * builder.addChild(R.id.templateCcNumber, new CharSequenceTransformation.Builder()
 *     .addField(ccNumberId, "^.*(\\d\\d\\d\\d)$", "...$1")
 * // Expiration date as MM / YYYY:
 * builder.addChild(R.id.templateExpDate, new CharSequenceTransformation.Builder()
 *     .addField(ccExpMonthId, "^(\\d\\d)$", "Exp: $1")
 *     .addField(ccExpYearId, "^(\\d\\d)$", "/$1");
 * </pre>
 *
 * <p>See {@link ImageTransformation}, {@link CharSequenceTransformation} for more info about these
 * transformations.
 */
public final class CustomDescription implements Parcelable {

    private static final String TAG = "CustomDescription";

    private final RemoteViews mPresentation;
    private final SparseArray<InternalTransformation> mTransformations;

    private CustomDescription(Builder builder) {
        mPresentation = builder.mPresentation;
        mTransformations = builder.mTransformations;
    }

    /** @hide */
    public RemoteViews getPresentation(ValueFinder finder) {
        if (mTransformations != null) {
            final int size = mTransformations.size();
            if (sDebug) Log.d(TAG, "getPresentation(): applying " + size + " transformations");
            for (int i = 0; i < size; i++) {
                final int id = mTransformations.keyAt(i);
                final InternalTransformation transformation = mTransformations.valueAt(i);
                if (sDebug) Log.d(TAG, "#" + i + ": " + transformation);

                try {
                    transformation.apply(finder, mPresentation, id);
                } catch (Exception e) {
                    // Do not log full exception to avoid PII leaking
                    Log.e(TAG, "Could not apply transformation " + transformation + ": "
                            + e.getClass());
                    return null;
                }
            }
        }
        return mPresentation;
    }

    /**
     * Builder for {@link CustomDescription} objects.
     */
    public static class Builder {
        private final RemoteViews mPresentation;

        private SparseArray<InternalTransformation> mTransformations;

        /**
         * Default constructor.
         *
         * @param parentPresentation template presentation with (optional) children views.
         */
        public Builder(RemoteViews parentPresentation) {
            mPresentation = parentPresentation;
        }

        /**
         * Adds a transformation to replace the value of a child view with the fields in the
         * screen.
         *
         * @param id view id of the children view.
         * @param transformation an implementation provided by the Android System.
         * @return this builder.
         * @throws IllegalArgumentException if {@code transformation} is not a class provided
         * by the Android System.
         */
        public Builder addChild(int id, @NonNull Transformation transformation) {
            Preconditions.checkArgument((transformation instanceof InternalTransformation),
                    "not provided by Android System: " + transformation);
            if (mTransformations == null) {
                mTransformations = new SparseArray<>();
            }
            mTransformations.put(id, (InternalTransformation) transformation);
            return this;
        }

        /**
         * Creates a new {@link CustomDescription} instance.
         */
        public CustomDescription build() {
            return new CustomDescription(this);
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return new StringBuilder("CustomDescription: [presentation=")
                .append(mPresentation)
                .append(", transformations=").append(mTransformations)
                .append("]").toString();
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mPresentation, flags);
        if (mTransformations == null) {
            dest.writeIntArray(null);
        } else {
            final int size = mTransformations.size();
            final int[] ids = new int[size];
            final InternalTransformation[] values = new InternalTransformation[size];
            for (int i = 0; i < size; i++) {
                ids[i] = mTransformations.keyAt(i);
                values[i] = mTransformations.valueAt(i);
            }
            dest.writeIntArray(ids);
            dest.writeParcelableArray(values, flags);
        }
    }
    public static final Parcelable.Creator<CustomDescription> CREATOR =
            new Parcelable.Creator<CustomDescription>() {
        @Override
        public CustomDescription createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder(parcel.readParcelable(null));
            final int[] ids = parcel.createIntArray();
            if (ids != null) {
                final InternalTransformation[] values =
                    parcel.readParcelableArray(null, InternalTransformation.class);
                final int size = ids.length;
                for (int i = 0; i < size; i++) {
                    builder.addChild(ids[i], values[i]);
                }
            }
            return builder.build();
        }

        @Override
        public CustomDescription[] newArray(int size) {
            return new CustomDescription[size];
        }
    };
}
