/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.annotation.UnsupportedAppUsage;

import java.util.UUID;

/**
 * This class is a Parcelable wrapper around {@link UUID} which is an
 * immutable representation of a 128-bit universally unique
 * identifier.
 */
public final class ParcelUuid implements Parcelable {

    private final UUID mUuid;

    /**
     * Constructor creates a ParcelUuid instance from the
     * given {@link UUID}.
     *
     * @param uuid UUID
     */
    public ParcelUuid(UUID uuid) {
        mUuid = uuid;
    }

    /**
     * Creates a new ParcelUuid from a string representation of {@link UUID}.
     *
     * @param uuid
     *            the UUID string to parse.
     * @return a ParcelUuid instance.
     * @throws NullPointerException
     *             if {@code uuid} is {@code null}.
     * @throws IllegalArgumentException
     *             if {@code uuid} is not formatted correctly.
     */
    public static ParcelUuid fromString(String uuid) {
        return new ParcelUuid(UUID.fromString(uuid));
    }

    /**
     * Get the {@link UUID} represented by the ParcelUuid.
     *
     * @return UUID contained in the ParcelUuid.
     */
    public UUID getUuid() {
        return mUuid;
    }

    /**
     * Returns a string representation of the ParcelUuid
     * For example: 0000110B-0000-1000-8000-00805F9B34FB will be the return value.
     *
     * @return a String instance.
     */
    @Override
    public String toString() {
        return mUuid.toString();
    }


   @Override
   public int hashCode() {
       return mUuid.hashCode();
   }

   /**
    * Compares this ParcelUuid to another object for equality. If {@code object}
    * is not {@code null}, is a ParcelUuid instance, and all bits are equal, then
    * {@code true} is returned.
    *
    * @param object
    *            the {@code Object} to compare to.
    * @return {@code true} if this ParcelUuid is equal to {@code object}
    *         or {@code false} if not.
    */
   @Override
   public boolean equals(Object object) {
       if (object == null) {
           return false;
       }

       if (this == object) {
           return true;
       }

       if (!(object instanceof ParcelUuid)) {
           return false;
       }

       ParcelUuid that = (ParcelUuid) object;

       return (this.mUuid.equals(that.mUuid));
   }

   public static final @android.annotation.NonNull Parcelable.Creator<ParcelUuid> CREATOR =
               new Parcelable.Creator<ParcelUuid>() {
        @UnsupportedAppUsage
        public ParcelUuid createFromParcel(Parcel source) {
            long mostSigBits = source.readLong();
            long leastSigBits = source.readLong();
            UUID uuid = new UUID(mostSigBits, leastSigBits);
            return new ParcelUuid(uuid);
        }

        public ParcelUuid[] newArray(int size) {
            return new ParcelUuid[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mUuid.getMostSignificantBits());
        dest.writeLong(mUuid.getLeastSignificantBits());
    }
}
