/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.credentials;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.credentials.CredentialEntry;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents the type and contained data fields of a {@link Credential}.
 */
public final class CredentialDescription implements Parcelable {

    /**
     * The credential type.
     */
    @NonNull
    private final String mType;

    /**
     * The flattened JSON string that will be matched with requests.
     */
    @NonNull
    private final String mFlattenedRequestString;

    /**
     * The entry to be used in the UI.
     */
    @NonNull
    private final List<CredentialEntry> mCredentialEntries;

    /**
     * Constructs a {@link CredentialDescription}.
     *
     * @param type the type of the credential returned.
     * @param flattenedRequestString flattened JSON string that will be matched with requests.
     * @param credentialEntries a list of {@link CredentialEntry}s that are to be shown on the
     *                          account selector if a credential matches with this description.
     *                          Each entry contains information to be displayed within an
     *                          entry on the UI, as well as a {@link android.app.PendingIntent}
     *                          that will be invoked if the user selects this entry.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public CredentialDescription(@NonNull String type,
            @NonNull String flattenedRequestString,
            @NonNull List<CredentialEntry> credentialEntries) {
        mType = Preconditions.checkStringNotEmpty(type, "type must not be empty");
        mFlattenedRequestString = Preconditions.checkStringNotEmpty(flattenedRequestString);
        mCredentialEntries = Objects.requireNonNull(credentialEntries);
    }

    private CredentialDescription(@NonNull Parcel in) {
        String type = in.readString8();
        String flattenedRequestString = in.readString();
        List<CredentialEntry> entries = new ArrayList<>();
        in.readTypedList(entries, CredentialEntry.CREATOR);

        mType = type;
        AnnotationValidations.validate(android.annotation.NonNull.class, null, mType);
        mFlattenedRequestString = flattenedRequestString;
        AnnotationValidations.validate(android.annotation.NonNull.class, null,
                mFlattenedRequestString);
        mCredentialEntries = entries;
        AnnotationValidations.validate(android.annotation.NonNull.class, null,
                mCredentialEntries);
    }

    public static final @NonNull Parcelable.Creator<CredentialDescription> CREATOR =
            new Parcelable.Creator<CredentialDescription>() {
                @Override
                public CredentialDescription createFromParcel(Parcel in) {
                    return new CredentialDescription(in);
                }

                @Override
                public CredentialDescription[] newArray(int size) {
                    return new CredentialDescription[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mType);
        dest.writeString(mFlattenedRequestString);
        dest.writeTypedList(mCredentialEntries, flags);
    }

    @NonNull
    public String getType() {
        return mType;
    }

    @NonNull
    public String getFlattenedRequestString() {
        return mFlattenedRequestString;
    }

    @NonNull
    public List<CredentialEntry> getCredentialEntries() {
        return mCredentialEntries;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mFlattenedRequestString);
    }

    @Override
    public boolean equals(Object obj) {
        return Objects.equals(mType, ((CredentialDescription) obj).getType())
                && Objects.equals(mFlattenedRequestString, ((CredentialDescription) obj).getType());
    }
}
