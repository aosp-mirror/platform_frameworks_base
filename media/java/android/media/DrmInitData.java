/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.media;

import android.annotation.NonNull;
import android.media.MediaDrm;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Encapsulates initialization data required by a {@link MediaDrm} instance.
 */
public abstract class DrmInitData {

    /**
     * Prevent public constuctor access
     */
    /* package private */ DrmInitData() {
    }

    /**
     * Retrieves initialization data for a given DRM scheme, specified by its UUID.
     *
     * @param schemeUuid The DRM scheme's UUID.
     * @return The initialization data for the scheme, or null if the scheme is not supported.
     * @deprecated Use {@link #getSchemeInitDataCount} and {@link #getSchemeInitDataAt} instead.
     */
    @Deprecated
    public abstract SchemeInitData get(UUID schemeUuid);

    /**
     * Returns the number of {@link SchemeInitData} elements available through {@link
     * #getSchemeInitDataAt}.
     */
    public int getSchemeInitDataCount() {
        return 0;
    }

    /**
     * Returns the {@link SchemeInitData} with the given {@code index}.
     *
     * @param index The index of the {@link SchemeInitData} to return.
     * @return The {@link SchemeInitData} associated with the given {@code index}.
     * @throws IndexOutOfBoundsException If the given {@code index} is negative or greater than
     *         {@link #getSchemeInitDataCount}{@code - 1}.
     */
    @NonNull public SchemeInitData getSchemeInitDataAt(int index) {
        throw new IndexOutOfBoundsException();
    }

    /**
     * Scheme initialization data.
     */
    public static final class SchemeInitData {

        /**
         * The Nil UUID, as defined in RFC 4122, section 4.1.7.
         */
        @NonNull public static final UUID UUID_NIL = new UUID(0, 0);

        /**
         * The UUID associated with this scheme initialization data. May be {@link #UUID_NIL} if
         * unknown or not applicable.
         */
        @NonNull public final UUID uuid;
        /**
         * The mimeType of {@link #data}.
         */
        public final String mimeType;
        /**
         * The initialization data.
         */
        public final byte[] data;

        /**
         * Creates a new instance with the given values.
         *
         * @param uuid The UUID associated with this scheme initialization data.
         * @param mimeType The mimeType of the initialization data.
         * @param data The initialization data.
         */
        public SchemeInitData(@NonNull UUID uuid, @NonNull String mimeType, @NonNull byte[] data) {
            this.uuid = Objects.requireNonNull(uuid);
            this.mimeType = Objects.requireNonNull(mimeType);
            this.data = Objects.requireNonNull(data);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof SchemeInitData)) {
                return false;
            }
            if (obj == this) {
                return true;
            }

            SchemeInitData other = (SchemeInitData) obj;
            return uuid.equals(other.uuid)
                    && mimeType.equals(other.mimeType)
                    && Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return uuid.hashCode() + 31 * (mimeType.hashCode() + 31 * Arrays.hashCode(data));
        }

    }

}
