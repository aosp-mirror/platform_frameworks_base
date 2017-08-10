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

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.Pair;
import android.view.autofill.AutofillId;
import android.widget.ImageView;
import android.widget.RemoteViews;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Replaces the content of a child {@link ImageView} of a
 * {@link RemoteViews presentation template} with the first image that matches a regular expression
 * (regex).
 *
 * <p>Typically used to display credit card logos. Example:
 *
 * <pre class="prettyprint">
 *   new ImageTransformation.Builder(ccNumberId, Pattern.compile("^4815.*$"),
 *                                   R.drawable.ic_credit_card_logo1)
 *     .addOption(Pattern.compile("^1623.*$"), R.drawable.ic_credit_card_logo2)
 *     .addOption(Pattern.compile("^42.*$"), R.drawable.ic_credit_card_logo3)
 *     .build();
 * </pre>
 *
 * <p>There is no imposed limit in the number of options, but keep in mind that regexs are
 * expensive to evaluate, so use the minimum number of regexs and add the most common first
 * (for example, if this is a tranformation for a credit card logo and the most common credit card
 * issuers are banks X and Y, add the regexes that resolves these 2 banks first).
 */
public final class ImageTransformation extends InternalTransformation implements Transformation,
        Parcelable {
    private static final String TAG = "ImageTransformation";

    private final AutofillId mId;
    private final ArrayList<Pair<Pattern, Integer>> mOptions;

    private ImageTransformation(Builder builder) {
        mId = builder.mId;
        mOptions = builder.mOptions;
    }

    /** @hide */
    @TestApi
    @Override
    public void apply(@NonNull ValueFinder finder, @NonNull RemoteViews parentTemplate,
            int childViewId) throws Exception {
        final String value = finder.findByAutofillId(mId);
        if (value == null) {
            Log.w(TAG, "No view for id " + mId);
            return;
        }
        final int size = mOptions.size();
        if (sDebug) {
            Log.d(TAG, size + " multiple options on id " + childViewId + " to compare against");
        }

        for (int i = 0; i < size; i++) {
            final Pair<Pattern, Integer> option = mOptions.get(i);
            try {
                if (option.first.matcher(value).matches()) {
                    Log.d(TAG, "Found match at " + i + ": " + option);
                    parentTemplate.setImageViewResource(childViewId, option.second);
                    return;
                }
            } catch (Exception e) {
                // Do not log full exception to avoid PII leaking
                Log.w(TAG, "Error matching regex #" + i + "(" + option.first.pattern() + ") on id "
                        + option.second + ": " + e.getClass());
                throw e;

            }
        }
        if (sDebug) Log.d(TAG, "No match for " + value);
    }

    /**
     * Builder for {@link ImageTransformation} objects.
     */
    public static class Builder {
        private final AutofillId mId;
        private final ArrayList<Pair<Pattern, Integer>> mOptions = new ArrayList<>();
        private boolean mDestroyed;

        /**
         * Create a new builder for a autofill id and add a first option.
         *
         * @param id id of the screen field that will be used to evaluate whether the image should
         * be used.
         * @param regex regular expression defining what should be matched to use this image.
         * @param resId resource id of the image (in the autofill service's package). The
         * {@link RemoteViews presentation} must contain a {@link ImageView} child with that id.
         */
        public Builder(@NonNull AutofillId id, @NonNull Pattern regex, @DrawableRes int resId) {
            mId = Preconditions.checkNotNull(id);

            addOption(regex, resId);
        }

        /**
         * Adds an option to replace the child view with a different image when the regex matches.
         *
         * @param regex regular expression defining what should be matched to use this image.
         * @param resId resource id of the image (in the autofill service's package). The
         * {@link RemoteViews presentation} must contain a {@link ImageView} child with that id.
         *
         * @return this build
         */
        public Builder addOption(@NonNull Pattern regex, @DrawableRes int resId) {
            throwIfDestroyed();

            Preconditions.checkNotNull(regex);
            Preconditions.checkArgument(resId != 0);

            mOptions.add(new Pair<>(regex, resId));
            return this;
        }

        /**
         * Creates a new {@link ImageTransformation} instance.
         */
        public ImageTransformation build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new ImageTransformation(this);
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

        return "ImageTransformation: [id=" + mId + ", options=" + mOptions + "]";
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
        parcel.writeParcelable(mId, flags);

        final int size = mOptions.size();
        final Pattern[] regexs = new Pattern[size];
        final int[] resIds = new int[size];
        for (int i = 0; i < size; i++) {
            Pair<Pattern, Integer> regex = mOptions.get(i);
            regexs[i] = regex.first;
            resIds[i] = regex.second;
        }
        parcel.writeSerializable(regexs);
        parcel.writeIntArray(resIds);
    }

    public static final Parcelable.Creator<ImageTransformation> CREATOR =
            new Parcelable.Creator<ImageTransformation>() {
        @Override
        public ImageTransformation createFromParcel(Parcel parcel) {
            final AutofillId id = parcel.readParcelable(null);

            final Pattern[] regexs = (Pattern[]) parcel.readSerializable();
            final int[] resIds = parcel.createIntArray();

            // Always go through the builder to ensure the data ingested by the system obeys the
            // contract of the builder to avoid attacks using specially crafted parcels.
            final ImageTransformation.Builder builder = new ImageTransformation.Builder(id,
                    regexs[0], resIds[0]);

            final int size = regexs.length;
            for (int i = 1; i < size; i++) {
                builder.addOption(regexs[i], resIds[i]);
            }

            return builder.build();
        }

        @Override
        public ImageTransformation[] newArray(int size) {
            return new ImageTransformation[size];
        }
    };
}
