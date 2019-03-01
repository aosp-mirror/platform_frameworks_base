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
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
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
 *                                   R.drawable.ic_credit_card_logo1, "Brand 1")
 *     .addOption(Pattern.compile("^1623.*$"), R.drawable.ic_credit_card_logo2, "Brand 2")
 *     .addOption(Pattern.compile("^42.*$"), R.drawable.ic_credit_card_logo3, "Brand 3")
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
    private final ArrayList<Option> mOptions;

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
            final Option option = mOptions.get(i);
            try {
                if (option.pattern.matcher(value).matches()) {
                    Log.d(TAG, "Found match at " + i + ": " + option);
                    parentTemplate.setImageViewResource(childViewId, option.resId);
                    if (option.contentDescription != null) {
                        parentTemplate.setContentDescription(childViewId,
                                option.contentDescription);
                    }
                    return;
                }
            } catch (Exception e) {
                // Do not log full exception to avoid PII leaking
                Log.w(TAG, "Error matching regex #" + i + "(" + option.pattern + ") on id "
                        + option.resId + ": " + e.getClass());
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
        private final ArrayList<Option> mOptions = new ArrayList<>();
        private boolean mDestroyed;

        /**
         * Creates a new builder for a autofill id and add a first option.
         *
         * @param id id of the screen field that will be used to evaluate whether the image should
         * be used.
         * @param regex regular expression defining what should be matched to use this image.
         * @param resId resource id of the image (in the autofill service's package). The
         * {@link RemoteViews presentation} must contain a {@link ImageView} child with that id.
         *
         * @deprecated use
         * {@link #ImageTransformation.Builder(AutofillId, Pattern, int, CharSequence)} instead.
         */
        @Deprecated
        public Builder(@NonNull AutofillId id, @NonNull Pattern regex, @DrawableRes int resId) {
            mId = Preconditions.checkNotNull(id);
            addOption(regex, resId);
        }

        /**
         * Creates a new builder for a autofill id and add a first option.
         *
         * @param id id of the screen field that will be used to evaluate whether the image should
         * be used.
         * @param regex regular expression defining what should be matched to use this image.
         * @param resId resource id of the image (in the autofill service's package). The
         * {@link RemoteViews presentation} must contain a {@link ImageView} child with that id.
         * @param contentDescription content description to be applied in the child view.
         */
        public Builder(@NonNull AutofillId id, @NonNull Pattern regex, @DrawableRes int resId,
                @NonNull CharSequence contentDescription) {
            mId = Preconditions.checkNotNull(id);
            addOption(regex, resId, contentDescription);
        }

        /**
         * Adds an option to replace the child view with a different image when the regex matches.
         *
         * @param regex regular expression defining what should be matched to use this image.
         * @param resId resource id of the image (in the autofill service's package). The
         * {@link RemoteViews presentation} must contain a {@link ImageView} child with that id.
         *
         * @return this build
         *
         * @deprecated use {@link #addOption(Pattern, int, CharSequence)} instead.
         */
        @Deprecated
        public Builder addOption(@NonNull Pattern regex, @DrawableRes int resId) {
            addOptionInternal(regex, resId, null);
            return this;
        }

        /**
         * Adds an option to replace the child view with a different image and content description
         * when the regex matches.
         *
         * @param regex regular expression defining what should be matched to use this image.
         * @param resId resource id of the image (in the autofill service's package). The
         * {@link RemoteViews presentation} must contain a {@link ImageView} child with that id.
         * @param contentDescription content description to be applied in the child view.
         *
         * @return this build
         */
        public Builder addOption(@NonNull Pattern regex, @DrawableRes int resId,
                @NonNull CharSequence contentDescription) {
            addOptionInternal(regex, resId, Preconditions.checkNotNull(contentDescription));
            return this;
        }

        private void addOptionInternal(@NonNull Pattern regex, @DrawableRes int resId,
                @Nullable CharSequence contentDescription) {
            throwIfDestroyed();

            Preconditions.checkNotNull(regex);
            Preconditions.checkArgument(resId != 0);

            mOptions.add(new Option(regex, resId, contentDescription));
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
        final Pattern[] patterns = new Pattern[size];
        final int[] resIds = new int[size];
        final CharSequence[] contentDescriptions = new String[size];
        for (int i = 0; i < size; i++) {
            final Option option = mOptions.get(i);
            patterns[i] = option.pattern;
            resIds[i] = option.resId;
            contentDescriptions[i] = option.contentDescription;
        }
        parcel.writeSerializable(patterns);
        parcel.writeIntArray(resIds);
        parcel.writeCharSequenceArray(contentDescriptions);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ImageTransformation> CREATOR =
            new Parcelable.Creator<ImageTransformation>() {
        @Override
        public ImageTransformation createFromParcel(Parcel parcel) {
            final AutofillId id = parcel.readParcelable(null);

            final Pattern[] regexs = (Pattern[]) parcel.readSerializable();
            final int[] resIds = parcel.createIntArray();
            final CharSequence[] contentDescriptions = parcel.readCharSequenceArray();

            // Always go through the builder to ensure the data ingested by the system obeys the
            // contract of the builder to avoid attacks using specially crafted parcels.
            final CharSequence contentDescription = contentDescriptions[0];
            final ImageTransformation.Builder builder = (contentDescription != null)
                    ? new ImageTransformation.Builder(id, regexs[0], resIds[0], contentDescription)
                    : new ImageTransformation.Builder(id, regexs[0], resIds[0]);

            final int size = regexs.length;
            for (int i = 1; i < size; i++) {
                if (contentDescriptions[i] != null) {
                    builder.addOption(regexs[i], resIds[i], contentDescriptions[i]);
                } else {
                    builder.addOption(regexs[i], resIds[i]);
                }
            }

            return builder.build();
        }

        @Override
        public ImageTransformation[] newArray(int size) {
            return new ImageTransformation[size];
        }
    };

    private static final class Option {
        public final Pattern pattern;
        public final int resId;
        public final CharSequence contentDescription;

        Option(Pattern pattern, int resId, CharSequence contentDescription) {
            this.pattern = pattern;
            this.resId = resId;
            this.contentDescription = TextUtils.trimNoCopySpans(contentDescription);
        }
    }
}
