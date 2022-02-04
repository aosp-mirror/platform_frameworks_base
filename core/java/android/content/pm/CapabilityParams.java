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
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents the parameters and its matching names which can be associated with a
 * {@link Capability}.
 *
 * @see ShortcutInfo.Builder#addCapabilityBinding(Capability, CapabilityParams)
 */
public final class CapabilityParams implements Parcelable {

    @NonNull
    private final String mName;
    @NonNull
    private final String mPrimaryValue;
    @NonNull
    private final List<String> mAliases;

    /**
     * Constructor.
     * @param name Name of the capability parameter.
     *           Note the character "/" is not permitted.
     * @param primaryValue The primary value of the parameter.
     * @param aliases Alternative values of the parameter.
     */
    private CapabilityParams(@NonNull final String name,
            @NonNull final String primaryValue, @Nullable final Collection<String> aliases) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(primaryValue);
        mName = name;
        mPrimaryValue = primaryValue;
        mAliases = aliases == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(aliases));
    }

    /**
     * Copy constructor.
     * @hide
     */
    CapabilityParams(@NonNull final CapabilityParams orig) {
        this(orig.mName, orig.mPrimaryValue, orig.mAliases);
    }

    private CapabilityParams(@NonNull final Builder builder) {
        this(builder.mKey, builder.mPrimaryValue, builder.mAliases);
    }

    private CapabilityParams(@NonNull final Parcel in) {
        mName = in.readString();
        mPrimaryValue = in.readString();
        final List<String> values = new ArrayList<>();
        in.readStringList(values);
        mAliases = Collections.unmodifiableList(values);
    }

    /**
     * Name of the parameter.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the primary name of values in this parameter.
     */
    @NonNull
    public String getValue() {
        return mPrimaryValue;
    }

    /**
     * Returns the aliases of the values in ths parameter. Returns an empty list if there are no
     * aliases.
     */
    @NonNull
    public List<String> getAliases() {
        return new ArrayList<>(mAliases);
    }

    /**
     * A list of values for this parameter. The first value will be the primary name, while the
     * rest will be alternative names.
     * @hide
     */
    @NonNull
    List<String> getValues() {
        if (mAliases == null) {
            return new ArrayList<>(Collections.singletonList(mPrimaryValue));
        }
        final List<String> ret = new ArrayList<>(mAliases.size() + 1);
        ret.add(mPrimaryValue);
        ret.addAll(mAliases);
        return ret;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CapabilityParams)) {
            return false;
        }
        final CapabilityParams target = (CapabilityParams) obj;
        return mName.equals(target.mName) && mPrimaryValue.equals(target.mPrimaryValue)
                && mAliases.equals(target.mAliases);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mPrimaryValue, mAliases);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeString(mPrimaryValue);
        dest.writeStringList(mAliases);
    }

    @NonNull
    public static final Parcelable.Creator<CapabilityParams> CREATOR =
            new Parcelable.Creator<CapabilityParams>() {
        @Override
        public CapabilityParams[] newArray(int size) {
            return new CapabilityParams[size];
        }

        @Override
        public CapabilityParams createFromParcel(@NonNull Parcel in) {
            return new CapabilityParams(in);
        }
    };

    /**
     * Builder class for {@link CapabilityParams}.
     */
    public static final class Builder {

        @NonNull
        private final String mKey;
        @NonNull
        private String mPrimaryValue;
        @NonNull
        private Set<String> mAliases;

        /**
         * Constructor.
         * @param key key of the capability parameter.
         *           Note the character "/" is not permitted.
         * @param value The primary name of value in the {@link CapabilityParams}, cannot be empty.
         */
        public Builder(@NonNull final String key, @NonNull final String value) {
            Objects.requireNonNull(key);
            if (TextUtils.isEmpty(value)) {
                throw new IllegalArgumentException("Primary value cannot be empty or null");
            }
            mPrimaryValue = value;
            mKey = key;
        }

        /**
         * Add an alias in the {@link CapabilityParams}.
         */
        @NonNull
        public Builder addAlias(@NonNull final String alias) {
            if (mAliases == null) {
                mAliases = new ArraySet<>(1);
            }
            mAliases.add(alias);
            return this;
        }

        /**
         * Creates an instance of {@link CapabilityParams}
         * @throws IllegalArgumentException If the specified value is empty.
         */
        @NonNull
        public CapabilityParams build() {
            return new CapabilityParams(this);
        }
    }
}
