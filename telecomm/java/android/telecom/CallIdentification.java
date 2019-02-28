/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telecom;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Encapsulates information about an incoming or outgoing {@link Call} provided by a
 * {@link CallScreeningService}.
 * <p>
 * Call identified information is consumed by the {@link InCallService dialer} app to provide the
 * user with more information about a call.  This can include information such as the name of the
 * caller, address, etc.  Call identification information is persisted to the
 * {@link android.provider.CallLog}.
 */
public final class CallIdentification implements Parcelable {
    /**
     * Builder for {@link CallIdentification} instances.
     * <p>
     * A {@link CallScreeningService} uses this class to create new instances of
     * {@link CallIdentification} for a screened call.
     */
    public final static class Builder {
        private CharSequence mName;
        private CharSequence mDescription;
        private CharSequence mDetails;
        private Icon mPhoto;
        private int mNuisanceConfidence = CallIdentification.CONFIDENCE_UNKNOWN;
        private String mPackageName;
        private CharSequence mAppName;

        /**
         * Default builder constructor.
         */
        public Builder() {
            // Default constructor
        }

        /**
         * Create instance of call identification with specified package/app name.
         *
         * @param callIdPackageName The package name.
         * @param callIdAppName The app name.
         * @hide
         */
        public Builder(@NonNull String callIdPackageName, @NonNull CharSequence callIdAppName) {
            mPackageName = callIdPackageName;
            mAppName = callIdAppName;
        }

        /**
         * Sets the name associated with the {@link CallIdentification} being built.
         * <p>
         * Could be a business name, for example.
         *
         * @param name The name associated with the call, or {@code null} if none is provided.
         * @return Builder instance.
         */
        public @NonNull Builder setName(@Nullable CharSequence name) {
            mName = name;
            return this;
        }

        /**
         * Sets the description associated with the {@link CallIdentification} being built.
         * <p>
         * A description of the call as identified by a {@link CallScreeningService}.  The
         * description is typically presented by Dialer apps after the
         * {@link CallIdentification#getName() name} to provide a short piece of relevant
         * information about the call.  This could include a location, address, or a message
         * regarding the potential nature of the call (e.g. potential telemarketer).
         *
         * @param description The call description, or {@code null} if none is provided.
         * @return Builder instance.
         */
        public @NonNull Builder setDescription(@Nullable CharSequence description) {
            mDescription = description;
            return this;
        }

        /**
         * Sets the details associated with the {@link CallIdentification} being built.
         * <p>
         * The details is typically presented by Dialer apps after the
         * {@link CallIdentification#getName() name} and
         * {@link CallIdentification#getDescription() description} to provide further clarifying
         * information about the call. This could include, for example, the opening hours of a
         * business, or a stats about the number of times a call has been reported as spam.
         *
         * @param details The call details, or {@code null} if none is provided.
         * @return Builder instance.
         */

        public @NonNull Builder setDetails(@Nullable CharSequence details) {
            mDetails = details;
            return this;
        }

        /**
         * Sets the photo associated with the {@link CallIdentification} being built.
         * <p>
         * This could be, for example, a business logo, or a photo of the caller.
         *
         * @param photo The photo associated with the call, or {@code null} if none was provided.
         * @return Builder instance.
         */
        public @NonNull Builder setPhoto(@Nullable Icon photo) {
            mPhoto = photo;
            return this;
        }

        /**
         * Sets the nuisance confidence with the {@link CallIdentification} being built.
         * <p>
         * This can be used to specify how confident the {@link CallScreeningService} is that a call
         * is or is not a nuisance call.
         *
         * @param nuisanceConfidence The nuisance confidence.
         * @return The builder.
         */
        public @NonNull Builder setNuisanceConfidence(@NuisanceConfidence int nuisanceConfidence) {
            mNuisanceConfidence = nuisanceConfidence;
            return this;
        }

        /**
         * Creates a new instance of {@link CallIdentification} based on the parameters set in this
         * builder.
         *
         * @return {@link CallIdentification} instance.
         */
        public @NonNull CallIdentification build() {
            return new CallIdentification(mName, mDescription, mDetails, mPhoto,
                    mNuisanceConfidence, mPackageName, mAppName);
        }
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = { "CONFIDENCE_" },
            value = {CONFIDENCE_NUISANCE, CONFIDENCE_LIKELY_NUISANCE, CONFIDENCE_UNKNOWN,
                    CONFIDENCE_LIKELY_NOT_NUISANCE, CONFIDENCE_NOT_NUISANCE})
    public @interface NuisanceConfidence {}

    /**
     * Call has been identified as a nuisance call.
     * <p>
     * Returned from {@link #getNuisanceConfidence()} to indicate that a
     * {@link CallScreeningService} to indicate how confident it is that a call is or is not a
     * nuisance call.
     */
    public static final int CONFIDENCE_NUISANCE = 2;

    /**
     * Call has been identified as a likely nuisance call.
     * <p>
     * Returned from {@link #getNuisanceConfidence()} to indicate that a
     * {@link CallScreeningService} to indicate how confident it is that a call is or is not a
     * nuisance call.
     */
    public static final int CONFIDENCE_LIKELY_NUISANCE = 1;

    /**
     * Call could not be classified as nuisance or non-nuisance.
     * <p>
     * Returned from {@link #getNuisanceConfidence()} to indicate that a
     * {@link CallScreeningService} to indicate how confident it is that a call is or is not a
     * nuisance call.
     */
    public static final int CONFIDENCE_UNKNOWN = 0;

    /**
     * Call has been identified as not likely to be a nuisance call.
     * <p>
     * Returned from {@link #getNuisanceConfidence()} to indicate that a
     * {@link CallScreeningService} to indicate how confident it is that a call is or is not a
     * nuisance call.
     */
    public static final int CONFIDENCE_LIKELY_NOT_NUISANCE = -1;

    /**
     * Call has been identified as not a nuisance call.
     * <p>
     * Returned from {@link #getNuisanceConfidence()} to indicate that a
     * {@link CallScreeningService} to indicate how confident it is that a call is or is not a
     * nuisance call.
     */
    public static final int CONFIDENCE_NOT_NUISANCE = -2;

    /**
     * Default constructor for {@link CallIdentification}.
     *
     * @param name The name.
     * @param description The description.
     * @param details The details.
     * @param photo The photo.
     * @param nuisanceConfidence Confidence that this is a nuisance call.
     * @hide
     */
    private CallIdentification(@Nullable String name, @Nullable String description,
            @Nullable String details, @Nullable Icon photo,
            @NuisanceConfidence int nuisanceConfidence) {
        this(name, description, details, photo, nuisanceConfidence, null, null);
    }

    /**
     * Default constructor for {@link CallIdentification}.
     *
     * @param name The name.
     * @param description The description.
     * @param details The details.
     * @param photo The photo.
     * @param nuisanceConfidence Confidence that this is a nuisance call.
     * @param callScreeningPackageName Package name of the {@link CallScreeningService} which
     *                                 provided the call identification.
     * @param callScreeningAppName App name of the {@link CallScreeningService} which provided the
     *                             call identification.
     * @hide
     */
    private CallIdentification(@Nullable CharSequence name, @Nullable CharSequence description,
            @Nullable CharSequence details, @Nullable Icon photo,
            @NuisanceConfidence int nuisanceConfidence, @NonNull String callScreeningPackageName,
            @NonNull CharSequence callScreeningAppName) {
        mName = name;
        mDescription = description;
        mDetails = details;
        mPhoto = photo;
        mNuisanceConfidence = nuisanceConfidence;
        mCallScreeningAppName = callScreeningAppName;
        mCallScreeningPackageName = callScreeningPackageName;
    }

    private CharSequence mName;
    private CharSequence mDescription;
    private CharSequence mDetails;
    private Icon mPhoto;
    private int mNuisanceConfidence;
    private String mCallScreeningPackageName;
    private CharSequence mCallScreeningAppName;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeCharSequence(mName);
        parcel.writeCharSequence(mDescription);
        parcel.writeCharSequence(mDetails);
        parcel.writeParcelable(mPhoto, 0);
        parcel.writeInt(mNuisanceConfidence);
        parcel.writeString(mCallScreeningPackageName);
        parcel.writeCharSequence(mCallScreeningAppName);
    }

    /**
     * Responsible for creating CallIdentification objects for deserialized Parcels.
     */
    public static final Parcelable.Creator<CallIdentification> CREATOR =
            new Parcelable.Creator<CallIdentification> () {

                @Override
                public CallIdentification createFromParcel(Parcel source) {
                    CharSequence name = source.readCharSequence();
                    CharSequence description = source.readCharSequence();
                    CharSequence details = source.readCharSequence();
                    Icon photo = source.readParcelable(ClassLoader.getSystemClassLoader());
                    int nuisanceConfidence = source.readInt();
                    String callScreeningPackageName = source.readString();
                    CharSequence callScreeningAppName = source.readCharSequence();
                    return new CallIdentification(name, description, details, photo,
                            nuisanceConfidence, callScreeningPackageName, callScreeningAppName);
                }

                @Override
                public CallIdentification[] newArray(int size) {
                    return new CallIdentification[size];
                }
            };

    /**
     * The name associated with the number.
     * <p>
     * The name of the call as identified by a {@link CallScreeningService}.  Could be a business
     * name, for example.
     *
     * @return The name associated with the number, or {@code null} if none was provided.
     */
    public final @Nullable CharSequence getName() {
        return mName;
    }

    /**
     * Description of the call.
     * <p>
     * A description of the call as identified by a {@link CallScreeningService}.  The description
     * is typically presented by Dialer apps after the {@link #getName() name} to provide a short
     * piece of relevant information about the call.  This could include a location, address, or a
     * message regarding the potential nature of the call (e.g. potential telemarketer).
     *
     * @return The call description, or {@code null} if none was provided.
     */
    public final @Nullable CharSequence getDescription() {
        return mDescription;
    }

    /**
     * Details of the call.
     * <p>
     * Details of the call as identified by a {@link CallScreeningService}.  The details
     * are typically presented by Dialer apps after the {@link #getName() name} and
     * {@link #getDescription() description} to provide further clarifying information about the
     * call. This could include, for example, the opening hours of a business, or stats about
     * the number of times a call has been reported as spam.
     *
     * @return The call details, or {@code null} if none was provided.
     */
    public final @Nullable CharSequence getDetails() {
        return mDetails;
    }

    /**
     * Photo associated with the call.
     * <p>
     * A photo associated with the call as identified by a {@link CallScreeningService}.  This
     * could be, for example, a business logo, or a photo of the caller.
     *
     * @return The photo associated with the call, or {@code null} if none was provided.
     */
    public final @Nullable Icon getPhoto() {
        return mPhoto;
    }

    /**
     * Indicates the likelihood that this call is a nuisance call.
     * <p>
     * How likely the call is a nuisance call, as identified by a {@link CallScreeningService}.
     *
     * @return The nuisance confidence.
     */
    public final @NuisanceConfidence int getNuisanceConfidence() {
        return mNuisanceConfidence;
    }

    /**
     * The package name of the {@link CallScreeningService} which provided the
     * {@link CallIdentification}.
     * <p>
     * A {@link CallScreeningService} may not set this property; it is set by the system.
     * @return the package name
     */
    public final @NonNull String getCallScreeningPackageName() {
        return mCallScreeningPackageName;
    }

    /**
     * The {@link android.content.pm.PackageManager#getApplicationLabel(ApplicationInfo) name} of
     * the {@link CallScreeningService} which provided the {@link CallIdentification}.
     * <p>
     * A {@link CallScreeningService} may not set this property; it is set by the system.
     *
     * @return The name of the app.
     */
    public final @NonNull CharSequence getCallScreeningAppName() {
        return mCallScreeningAppName;
    }

    /**
     * Set the package name of the {@link CallScreeningService} which provided this information.
     *
     * @param callScreeningPackageName The package name.
     * @hide
     */
    public void setCallScreeningPackageName(@NonNull String callScreeningPackageName) {
        mCallScreeningPackageName = callScreeningPackageName;
    }

    /**
     * Set the app name of the {@link CallScreeningService} which provided this information.
     *
     * @param callScreeningAppName The app name.
     * @hide
     */
    public void setCallScreeningAppName(@NonNull CharSequence callScreeningAppName) {
        mCallScreeningAppName = callScreeningAppName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CallIdentification that = (CallIdentification) o;
        // Note: mPhoto purposely omit as no good comparison exists.
        return mNuisanceConfidence == that.mNuisanceConfidence
                && Objects.equals(mName, that.mName)
                && Objects.equals(mDescription, that.mDescription)
                && Objects.equals(mDetails, that.mDetails)
                && Objects.equals(mCallScreeningAppName, that.mCallScreeningAppName)
                && Objects.equals(mCallScreeningPackageName, that.mCallScreeningPackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mDescription, mDetails, mPhoto, mNuisanceConfidence,
                mCallScreeningAppName, mCallScreeningPackageName);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[CallId mName=");
        sb.append(Log.pii(mName));
        sb.append(", mDesc=");
        sb.append(mDescription);
        sb.append(", mDet=");
        sb.append(mDetails);
        sb.append(", conf=");
        sb.append(mNuisanceConfidence);
        sb.append(", appName=");
        sb.append(mCallScreeningAppName);
        sb.append(", pkgName=");
        sb.append(mCallScreeningPackageName);
        return sb.toString();
    }
}
