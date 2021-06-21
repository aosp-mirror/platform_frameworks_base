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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Provides an immutable reference to an entity that appears repeatedly on different surfaces of the
 * platform. For example, this could represent the sender of a message.
 */
public final class Person implements Parcelable {

    @Nullable private CharSequence mName;
    @Nullable private Icon mIcon;
    @Nullable private String mUri;
    @Nullable private String mKey;
    private boolean mIsBot;
    private boolean mIsImportant;

    private Person(Parcel in) {
        mName = in.readCharSequence();
        if (in.readInt() != 0) {
            mIcon = Icon.CREATOR.createFromParcel(in);
        }
        mUri = in.readString();
        mKey = in.readString();
        mIsImportant = in.readBoolean();
        mIsBot = in.readBoolean();
    }

    private Person(Builder builder) {
        mName = builder.mName;
        mIcon = builder.mIcon;
        mUri = builder.mUri;
        mKey = builder.mKey;
        mIsBot = builder.mIsBot;
        mIsImportant = builder.mIsImportant;
    }

    /** Creates and returns a new {@link Builder} initialized with this Person's data. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * @return the uri provided for this person or {@code null} if no Uri was provided.
     */
    @Nullable
    public String getUri() {
        return mUri;
    }

    /**
     * @return the name provided for this person or {@code null} if no name was provided.
     */
    @Nullable
    public CharSequence getName() {
        return mName;
    }

    /**
     * @return the icon provided for this person or {@code null} if no icon was provided.
     */
    @Nullable
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * @return the key provided for this person or {@code null} if no key was provided.
     */
    @Nullable
    public String getKey() {
        return mKey;
    }

    /**
     * @return whether this Person is a machine.
     */
    public boolean isBot() {
        return mIsBot;
    }

    /**
     * @return whether this Person is important.
     */
    public boolean isImportant() {
        return mIsImportant;
    }

    /**
     * @return the URI associated with this person, or "name:mName" otherwise
     *  @hide
     */
    public String resolveToLegacyUri() {
        if (mUri != null) {
            return mUri;
        }
        if (mName != null) {
            return "name:" + mName;
        }
        return "";
    }

    /**
     * @return the URI associated with the {@link #getIcon()} for this person, iff the icon exists
     * and is URI based.
     * @hide
     */
    @Nullable
    public Uri getIconUri() {
        if (mIcon != null && (mIcon.getType() == Icon.TYPE_URI
                || mIcon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP)) {
            return mIcon.getUri();
        }
        return null;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof Person) {
            final Person other = (Person) obj;
            return Objects.equals(mName, other.mName)
                    && (mIcon == null ? other.mIcon == null :
                    (other.mIcon != null && mIcon.sameAs(other.mIcon)))
                    && Objects.equals(mUri, other.mUri)
                    && Objects.equals(mKey, other.mKey)
                    && mIsBot == other.mIsBot
                    && mIsImportant == other.mIsImportant;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mIcon, mUri, mKey, mIsBot, mIsImportant);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, @WriteFlags int flags) {
        dest.writeCharSequence(mName);
        if (mIcon != null) {
            dest.writeInt(1);
            mIcon.writeToParcel(dest, 0);
        } else {
            dest.writeInt(0);
        }
        dest.writeString(mUri);
        dest.writeString(mKey);
        dest.writeBoolean(mIsImportant);
        dest.writeBoolean(mIsBot);
    }

    /** Builder for the immutable {@link Person} class. */
    public static class Builder {
        @Nullable private CharSequence mName;
        @Nullable private Icon mIcon;
        @Nullable private String mUri;
        @Nullable private String mKey;
        private boolean mIsBot;
        private boolean mIsImportant;

        /** Creates a new, empty {@link Builder}. */
        public Builder() {
        }

        private Builder(Person person) {
            mName = person.mName;
            mIcon = person.mIcon;
            mUri = person.mUri;
            mKey = person.mKey;
            mIsBot = person.mIsBot;
            mIsImportant = person.mIsImportant;
        }

        /**
         * Give this person a name.
         *
         * @param name the name of this person.
         */
        @NonNull
        public Person.Builder setName(@Nullable CharSequence name) {
            this.mName = name;
            return this;
        }

        /**
         * Add an icon for this person.
         * <br />
         * The system will prefer this icon over any images that are resolved from the URI.
         *
         * @param icon the icon of the person.
         */
        @NonNull
        public Person.Builder setIcon(@Nullable Icon icon) {
            this.mIcon = icon;
            return this;
        }

        /**
         * Set a URI associated with this person.
         *
         * <P>
         * The person should be specified by the {@code String} representation of a
         * {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI}.
         * </P>
         *
         * <P>The system will also attempt to resolve {@code mailto:} and {@code tel:} schema
         * URIs. The path part of these URIs must exist in the contacts database, in the
         * appropriate column, or the reference will be discarded as invalid. Telephone schema
         * URIs will be resolved by {@link android.provider.ContactsContract.PhoneLookup}.
         * </P>
         *
         * @param uri a URI for the person.
         */
        @NonNull
        public Person.Builder setUri(@Nullable String uri) {
            mUri = uri;
            return this;
        }

        /**
         * Add a key to this person in order to uniquely identify it.
         * This is especially useful if the name doesn't uniquely identify this person or if the
         * display name is a short handle of the actual name.
         *
         * <P>If no key is provided, the name serves as the key for the purpose of
         * identification.</P>
         *
         * @param key the key that uniquely identifies this person.
         */
        @NonNull
        public Person.Builder setKey(@Nullable String key) {
            mKey = key;
            return this;
        }

        /**
         * Sets whether this is an important person. Use this method to denote users who frequently
         * interact with the user of this device when {@link #setUri(String)} isn't provided with
         * {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI}, and instead with
         * the {@code mailto:} or {@code tel:} schemas.
         *
         * @param isImportant {@code true} if this is an important person, {@code false} otherwise.
         */
        @NonNull
        public Person.Builder setImportant(boolean isImportant) {
            mIsImportant = isImportant;
            return this;
        }

        /**
         * Sets whether this person is a machine rather than a human.
         *
         * @param isBot {@code true} if this person is a machine, {@code false} otherwise.
         */
        @NonNull
        public Person.Builder setBot(boolean isBot) {
            mIsBot = isBot;
            return this;
        }

        /** Creates and returns the {@link Person} this builder represents. */
        @NonNull
        public Person build() {
            return new Person(this);
        }
    }

    public static final @android.annotation.NonNull Creator<Person> CREATOR = new Creator<Person>() {
        @Override
        public Person createFromParcel(Parcel in) {
            return new Person(in);
        }

        @Override
        public Person[] newArray(int size) {
            return new Person[size];
        }
    };
}
