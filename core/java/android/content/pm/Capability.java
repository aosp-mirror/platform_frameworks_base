/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Represents a capability that can be performed by an app, also known as App Action.
 * Capabilities can be associated with a {@link ShortcutInfo}.
 *
 * @see ShortcutInfo.Builder#addCapabilityBinding(Capability, CapabilityParams)
 */
public final class Capability implements Parcelable {

    @NonNull
    private final String mName;

    /**
     * Constructor.
     * @param name Name of the capability, usually maps to a built-in intent,
     *            e.g. actions.intent.GET_MESSAGE. Note the character "/" is not permitted.
     * @throws IllegalArgumentException If specified capability name contains the character "/".
     *
     * @hide
     */
    Capability(@NonNull final String name) {
        Objects.requireNonNull(name);
        if (name.contains("/")) {
            throw new IllegalArgumentException("'/' is not permitted in the capability name");
        }
        mName = name;
    }

    /**
     * Copy constructor.
     *
     * @hide
     */
    Capability(@NonNull final Capability orig) {
        this(orig.mName);
    }

    private Capability(@NonNull final Builder builder) {
        this(builder.mName);
    }

    private Capability(@NonNull final Parcel in) {
        mName = in.readString();
    }

    /**
     * Returns the name of the capability. e.g. actions.intent.GET_MESSAGE.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Capability)) {
            return false;
        }
        return mName.equals(((Capability) obj).mName);
    }

    @Override
    public int hashCode() {
        return mName.hashCode();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
    }
    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<Capability> CREATOR =
            new Parcelable.Creator<Capability>() {
        @Override
        public Capability[] newArray(int size) {
            return new Capability[size];
        }

        @Override
        public Capability createFromParcel(@NonNull Parcel in) {
            return new Capability(in);
        }
    };

    /**
     * Builder class for {@link Capability}.
     */
    public static final class Builder {

        @NonNull
        private final String mName;

        /**
         * Constructor.
         * @param name Name of the capability, usually maps to a built-in intent,
         *            e.g. actions.intent.GET_MESSAGE. Note the character "/" is not permitted.
         * @throws IllegalArgumentException If specified capability name contains the character "/".
         */
        public Builder(@NonNull final String name) {
            Objects.requireNonNull(name);
            if (name.contains("/")) {
                throw new IllegalArgumentException("'/' is not permitted in the capability name");
            }
            mName = name;
        }

        /**
         * Creates an instance of {@link Capability}
         */
        @NonNull
        public Capability build() {
            return new Capability(this);
        }
    }
}
