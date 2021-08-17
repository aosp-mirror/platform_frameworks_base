/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a {@code KeySet} that has been declared in the AndroidManifest.xml
 * file for the application.  A {@code KeySet} can be used explicitly to
 * represent a trust relationship with other applications on the device.
 * @hide
 */
public class KeySet implements Parcelable {

    private IBinder token;

    /** @hide */
    public KeySet(IBinder token) {
        if (token == null) {
            throw new NullPointerException("null value for KeySet IBinder token");
        }
        this.token = token;
    }

    /** @hide */
    public IBinder getToken() {
        return token;
    }

    /** @hide */
    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof KeySet) {
            KeySet ks = (KeySet) o;
            return token == ks.token;
        }
        return false;
    }

    /** @hide */
    @Override
    public int hashCode() {
        return token.hashCode();
    }

    /**
     * Implement Parcelable
     * @hide
     */
    public static final @android.annotation.NonNull Parcelable.Creator<KeySet> CREATOR
            = new Parcelable.Creator<KeySet>() {

        /**
         * Create a KeySet from a Parcel
         *
         * @param in The parcel containing the KeySet
         */
        public KeySet createFromParcel(Parcel source) {
            return readFromParcel(source);
        }

        /**
         * Create an array of null KeySets
         */
        public KeySet[] newArray(int size) {
            return new KeySet[size];
        }
    };

    /**
     * @hide
     */
    private static KeySet readFromParcel(Parcel in) {
        IBinder token = in.readStrongBinder();
        return new KeySet(token);
    }

    /**
     * @hide
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(token);
    }

    /**
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }
}