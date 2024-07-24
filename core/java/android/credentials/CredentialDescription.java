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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents the type and contained data fields of a {@link Credential}.
 */
public final class CredentialDescription implements Parcelable {

    private static final int MAX_ALLOWED_ENTRIES_PER_DESCRIPTION = 16;

    /**
     * The credential type.
     */
    @NonNull
    private final String mType;

    /**
     * Keys of elements to match with Credential requests.
     */
    @NonNull
    private final Set<String> mSupportedElementKeys;

    /**
     * The credential entries to be used in the UI.
     */
    @NonNull
    private final List<CredentialEntry> mCredentialEntries;

    /**
     * Constructs a {@link CredentialDescription}.
     *
     * @param type the type of the credential returned.
     * @param supportedElementKeys Keys of elements to match with Credential requests.
     * @param credentialEntries a list of {@link CredentialEntry}s that are to be shown on the
     *                          account selector if a credential matches with this description.
     *                          Each entry contains information to be displayed within an
     *                          entry on the UI, as well as a {@link android.app.PendingIntent}
     *                          that will be invoked if the user selects this entry.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public CredentialDescription(@NonNull String type,
            @NonNull Set<String> supportedElementKeys,
            @NonNull List<CredentialEntry> credentialEntries) {
        mType = Preconditions.checkStringNotEmpty(type, "type must not be empty");
        mSupportedElementKeys = Objects.requireNonNull(supportedElementKeys);
        mCredentialEntries = Objects.requireNonNull(credentialEntries);
        Preconditions.checkArgument(credentialEntries.size()
                        <= MAX_ALLOWED_ENTRIES_PER_DESCRIPTION,
                "The number of Credential Entries exceed 16.");
        Preconditions.checkArgument(compareEntryTypes(type, credentialEntries) == 0,
                "Credential Entry type(s) do not match the request type.");
    }

    private CredentialDescription(@NonNull Parcel in) {
        String type = in.readString8();
        List<String> descriptions = in.createStringArrayList();
        List<CredentialEntry> entries = new ArrayList<>();
        in.readTypedList(entries, CredentialEntry.CREATOR);

        mType = type;
        AnnotationValidations.validate(android.annotation.NonNull.class, null, mType);
        mSupportedElementKeys = new HashSet<>(descriptions);
        AnnotationValidations.validate(android.annotation.NonNull.class, null,
                mSupportedElementKeys);
        mCredentialEntries = entries;
        AnnotationValidations.validate(android.annotation.NonNull.class, null,
                mCredentialEntries);
    }

    private static int compareEntryTypes(@NonNull String type,
            @NonNull List<CredentialEntry> credentialEntries) {
        return credentialEntries.stream()
                .filter(credentialEntry ->
                        !credentialEntry.getType().equals(type)).toList().size();

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
        dest.writeString8(mType);
        dest.writeStringList(mSupportedElementKeys.stream().toList());
        dest.writeTypedList(mCredentialEntries, flags);
    }

    /**
     * Returns the type of the Credential described.
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the flattened JSON string that will be matched with requests.
     */
    @NonNull
    public Set<String> getSupportedElementKeys() {
        return new HashSet<>(mSupportedElementKeys);
    }

    /**
     * Returns the credential entries to be used in the UI.
     */
    @NonNull
    public List<CredentialEntry> getCredentialEntries() {
        return mCredentialEntries;
    }

    /**
     * {@link #getType()} and {@link #getSupportedElementKeys()} are enough for hashing. Constructor
     * enforces {@link CredentialEntry} to have the same type and
     * {@link android.app.slice.Slice} contained by the entry can not be hashed.
     */
    @Override
    public int hashCode() {
        return Objects.hash(mType, mSupportedElementKeys);
    }

    /**
     * {@link #getType()} and {@link #getSupportedElementKeys()} are enough for equality check.
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CredentialDescription)) {
            return false;
        }
        CredentialDescription other = (CredentialDescription) obj;
        return mType.equals(other.mType)
                && mSupportedElementKeys.equals(other.mSupportedElementKeys);
    }
}
