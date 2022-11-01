/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.Immutable;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A list of packages and associated attribution tags that supports easy membership checks. Supports
 * "wildcard" attribution tags (ie, matching any attribution tag under a package) in additional to
 * standard checks.
 *
 * @hide
 */
@TestApi
@Immutable
public final class PackageTagsList implements Parcelable {

    // an empty set value matches any attribution tag (ie, wildcard)
    private final ArrayMap<String, ArraySet<String>> mPackageTags;

    private PackageTagsList(@NonNull ArrayMap<String, ArraySet<String>> packageTags) {
        mPackageTags = Objects.requireNonNull(packageTags);
    }

    /**
     * Returns true if this instance is empty;
     */
    public boolean isEmpty() {
        return mPackageTags.isEmpty();
    }

    /**
     * Returns true if the given package is found within this instance. If this returns true this
     * does not imply anything about whether any given attribution tag under the given package name
     * is present.
     */
    public boolean includes(@NonNull String packageName) {
        return mPackageTags.containsKey(packageName);
    }

    /**
     * Returns true if the given attribution tag is found within this instance under any package.
     * Only returns true if the attribution tag literal is found, not if any package contains the
     * set of all attribution tags.
     *
     * @hide
     */
    public boolean includesTag(@NonNull String attributionTag) {
        final int size = mPackageTags.size();
        for (int i = 0; i < size; i++) {
            ArraySet<String> tags = mPackageTags.valueAt(i);
            if (tags.contains(attributionTag)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if all attribution tags under the given package are contained within this
     * instance.
     */
    public boolean containsAll(@NonNull String packageName) {
        Set<String> tags = mPackageTags.get(packageName);
        return tags != null && tags.isEmpty();
    }

    /**
     * Returns true if the given package and attribution tag are contained within this instance.
     */
    public boolean contains(@NonNull String packageName, @Nullable String attributionTag) {
        Set<String> tags = mPackageTags.get(packageName);
        if (tags == null) {
            return false;
        } else if (tags.isEmpty()) {
            // our tags are the full set, so we contain any attribution tag
            return true;
        } else {
            return tags.contains(attributionTag);
        }
    }

    /**
     * Returns true if the given PackageTagsList is a subset of this instance.
     */
    public boolean contains(@NonNull PackageTagsList packageTagsList) {
        int otherSize = packageTagsList.mPackageTags.size();
        if (otherSize > mPackageTags.size()) {
            return false;
        }

        for (int i = 0; i < otherSize; i++) {
            String packageName = packageTagsList.mPackageTags.keyAt(i);
            ArraySet<String> tags = mPackageTags.get(packageName);
            if (tags == null) {
                return false;
            }
            if (tags.isEmpty()) {
                // our tags are the full set, so we contain whatever the other tags are
                continue;
            }
            ArraySet<String> otherTags = packageTagsList.mPackageTags.valueAt(i);
            if (otherTags.isEmpty()) {
                // other tags are the full set, so we can't contain them
                return false;
            }
            if (!tags.containsAll(otherTags)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns a list of packages.
     *
     * @deprecated Do not use.
     * @hide
     */
    @Deprecated
    public @NonNull Collection<String> getPackages() {
        return new ArrayList<>(mPackageTags.keySet());
    }

    public static final @NonNull Parcelable.Creator<PackageTagsList> CREATOR =
            new Parcelable.Creator<PackageTagsList>() {
                @SuppressWarnings("unchecked")
                @Override
                public PackageTagsList createFromParcel(Parcel in) {
                    int count = in.readInt();
                    ArrayMap<String, ArraySet<String>> packageTags = new ArrayMap<>(count);
                    for (int i = 0; i < count; i++) {
                        String key = in.readString8();
                        ArraySet<String> value = (ArraySet<String>) in.readArraySet(null);
                        packageTags.append(key, value);
                    }
                    return new PackageTagsList(packageTags);
                }

                @Override
                public PackageTagsList[] newArray(int size) {
                    return new PackageTagsList[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        int count = mPackageTags.size();
        parcel.writeInt(count);
        for (int i = 0; i < count; i++) {
            parcel.writeString8(mPackageTags.keyAt(i));
            parcel.writeArraySet(mPackageTags.valueAt(i));
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PackageTagsList)) {
            return false;
        }

        PackageTagsList that = (PackageTagsList) o;
        return mPackageTags.equals(that.mPackageTags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageTags);
    }

    @Override
    public @NonNull String toString() {
        return mPackageTags.toString();
    }

    /**
     * @hide
     */
    public void dump(PrintWriter pw) {
        int size = mPackageTags.size();
        for (int i = 0; i < size; i++) {
            String packageName = mPackageTags.keyAt(i);
            pw.print(packageName);
            pw.print("[");
            int tagsSize = mPackageTags.valueAt(i).size();
            if (tagsSize == 0) {
                pw.print("*");
            } else {
                for (int j = 0; j < tagsSize; j++) {
                    String attributionTag = mPackageTags.valueAt(i).valueAt(j);
                    if (j > 0) {
                        pw.print(", ");
                    }
                    if (attributionTag != null && attributionTag.startsWith(packageName)) {
                        pw.print(attributionTag.substring(packageName.length()));
                    } else {
                        pw.print(attributionTag);
                    }
                }
            }
            pw.println("]");
        }
    }

    /**
     * Builder class for {@link PackageTagsList}.
     */
    public static final class Builder {

        private final ArrayMap<String, ArraySet<String>> mPackageTags;

        /**
         * Creates a new builder.
         */
        public Builder() {
            mPackageTags = new ArrayMap<>();
        }

        /**
         * Creates a new builder with the given initial capacity.
         */
        public Builder(int capacity) {
            mPackageTags = new ArrayMap<>(capacity);
        }

        /**
         * Adds all attribution tags under the specified package to the builder.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder add(@NonNull String packageName) {
            mPackageTags.computeIfAbsent(packageName, p -> new ArraySet<>()).clear();
            return this;
        }

        /**
         * Adds the specified package and attribution tag to the builder.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder add(@NonNull String packageName, @Nullable String attributionTag) {
            ArraySet<String> tags = mPackageTags.get(packageName);
            if (tags == null) {
                tags = new ArraySet<>(1);
                tags.add(attributionTag);
                mPackageTags.put(packageName, tags);
            } else if (!tags.isEmpty()) {
                tags.add(attributionTag);
            }

            return this;
        }

        /**
         * Adds the specified package and set of attribution tags to the builder.
         *
         * @hide
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder add(@NonNull String packageName,
                @NonNull Collection<String> attributionTags) {
            if (attributionTags.isEmpty()) {
                // the input is not allowed to specify a full set by passing in an empty collection
                return this;
            }

            ArraySet<String> tags = mPackageTags.get(packageName);
            if (tags == null) {
                tags = new ArraySet<>(attributionTags);
                mPackageTags.put(packageName, tags);
            } else if (!tags.isEmpty()) {
                // if we contain the full set, already done, otherwise add all the tags
                tags.addAll(attributionTags);
            }

            return this;
        }

        /**
         * Adds the specified {@link PackageTagsList} to the builder.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder add(@NonNull PackageTagsList packageTagsList) {
            return add(packageTagsList.mPackageTags);
        }

        /**
         * Adds the given map of package to attribution tags to the builder. An empty set of
         * attribution tags is interpreted to imply all attribution tags under that package.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder add(@NonNull Map<String, ? extends Set<String>> packageTagsMap) {
            mPackageTags.ensureCapacity(packageTagsMap.size());
            for (Map.Entry<String, ? extends Set<String>> entry : packageTagsMap.entrySet()) {
                Set<String> newTags = entry.getValue();
                if (newTags.isEmpty()) {
                    add(entry.getKey());
                } else {
                    add(entry.getKey(), newTags);
                }
            }

            return this;
        }

        /**
         * Removes all attribution tags under the specified package from the builder.
         *
         * @hide
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder remove(@NonNull String packageName) {
            mPackageTags.remove(packageName);
            return this;
        }

        /**
         * Removes the specified package and attribution tag from the builder if and only if the
         * specified attribution tag is listed explicitly under the package. If the package contains
         * all possible attribution tags, then nothing will be removed.
         *
         * @hide
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder remove(@NonNull String packageName,
                @Nullable String attributionTag) {
            ArraySet<String> tags = mPackageTags.get(packageName);
            if (tags != null && tags.remove(attributionTag) && tags.isEmpty()) {
                mPackageTags.remove(packageName);
            }
            return this;
        }

        /**
         * Removes the specified package and set of attribution tags from the builder if and only if
         * the specified set of attribution tags are listed explicitly under the package. If the
         * package contains all possible attribution tags, then nothing will be removed.
         *
         * @hide
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder remove(@NonNull String packageName,
                @NonNull Collection<String> attributionTags) {
            if (attributionTags.isEmpty()) {
                // the input is not allowed to specify a full set by passing in an empty collection
                return this;
            }

            ArraySet<String> tags = mPackageTags.get(packageName);
            if (tags != null && tags.removeAll(attributionTags) && tags.isEmpty()) {
                mPackageTags.remove(packageName);
            }
            return this;
        }

        /**
         * Removes the specified {@link PackageTagsList} from the builder. If a package contains all
         * possible attribution tags, it will only be removed if the package in the removed
         * {@link PackageTagsList} also contains all possible attribution tags.
         *
         * @hide
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder remove(@NonNull PackageTagsList packageTagsList) {
            return remove(packageTagsList.mPackageTags);
        }

        /**
         * Removes the given map of package to attribution tags to the builder. An empty set of
         * attribution tags is interpreted to imply all attribution tags under that package. If a
         * package contains all possible attribution tags, it will only be removed if the package in
         * the removed map also contains all possible attribution tags.
         *
         * @hide
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder remove(@NonNull Map<String, ? extends Set<String>> packageTagsMap) {
            for (Map.Entry<String, ? extends Set<String>> entry : packageTagsMap.entrySet()) {
                Set<String> removedTags = entry.getValue();
                if (removedTags.isEmpty()) {
                    // if removing the full set, drop the package completely
                    remove(entry.getKey());
                } else {
                    remove(entry.getKey(), removedTags);
                }
            }

            return this;
        }

        /**
         * Clears the builder.
         */
        public @NonNull Builder clear() {
            mPackageTags.clear();
            return this;
        }

        /**
         * Constructs a new {@link PackageTagsList}.
         */
        public @NonNull PackageTagsList build() {
            return new PackageTagsList(copy(mPackageTags));
        }

        private static ArrayMap<String, ArraySet<String>> copy(
                ArrayMap<String, ArraySet<String>> value) {
            int size = value.size();
            ArrayMap<String, ArraySet<String>> copy = new ArrayMap<>(size);
            for (int i = 0; i < size; i++) {
                String packageName = value.keyAt(i);
                ArraySet<String> tags = new ArraySet<>(Objects.requireNonNull(value.valueAt(i)));
                copy.append(packageName, tags);
            }
            return copy;
        }
    }
}
