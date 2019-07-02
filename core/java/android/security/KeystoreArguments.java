/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class for handling the additional arguments to some keystore binder calls.
 * This must be kept in sync with the deserialization code in system/security/keystore.
 * @hide
 */
public class KeystoreArguments implements Parcelable {
    public byte[][] args;

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Parcelable.Creator<KeystoreArguments> CREATOR = new
            Parcelable.Creator<KeystoreArguments>() {
                public KeystoreArguments createFromParcel(Parcel in) {
                    return new KeystoreArguments(in);
                }
                public KeystoreArguments[] newArray(int size) {
                    return new KeystoreArguments[size];
                }
            };

    public KeystoreArguments() {
        args = null;
    }

    @UnsupportedAppUsage
    public KeystoreArguments(byte[][] args) {
        this.args = args;
    }

    private KeystoreArguments(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (args == null) {
            out.writeInt(0);
        } else {
            out.writeInt(args.length);
            for (byte[] arg : args) {
                out.writeByteArray(arg);
            }
        }
    }

    private void readFromParcel(Parcel in) {
        int length = in.readInt();
        args = new byte[length][];
        for (int i = 0; i < length; i++) {
            args[i] = in.createByteArray();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
