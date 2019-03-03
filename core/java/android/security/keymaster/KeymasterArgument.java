/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keymaster;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelFormatException;

/**
 * Base class for the Java side of a Keymaster tagged argument.
 * <p>
 * Serialization code for this and subclasses must be kept in sync with system/security/keystore
 * and with hardware/libhardware/include/hardware/keymaster_defs.h
 * @hide
 */
abstract class KeymasterArgument implements Parcelable {
    public final int tag;

    public static final @android.annotation.NonNull Parcelable.Creator<KeymasterArgument> CREATOR = new
            Parcelable.Creator<KeymasterArgument>() {
                @Override
                public KeymasterArgument createFromParcel(Parcel in) {
                    final int pos = in.dataPosition();
                    final int tag = in.readInt();
                    switch (KeymasterDefs.getTagType(tag)) {
                        case KeymasterDefs.KM_ENUM:
                        case KeymasterDefs.KM_ENUM_REP:
                        case KeymasterDefs.KM_UINT:
                        case KeymasterDefs.KM_UINT_REP:
                            return new KeymasterIntArgument(tag, in);
                        case KeymasterDefs.KM_ULONG:
                        case KeymasterDefs.KM_ULONG_REP:
                            return new KeymasterLongArgument(tag, in);
                        case KeymasterDefs.KM_DATE:
                            return new KeymasterDateArgument(tag, in);
                        case KeymasterDefs.KM_BYTES:
                        case KeymasterDefs.KM_BIGNUM:
                            return new KeymasterBlobArgument(tag, in);
                        case KeymasterDefs.KM_BOOL:
                            return new KeymasterBooleanArgument(tag, in);
                        default:
                            throw new ParcelFormatException("Bad tag: " + tag + " at " + pos);
                    }
                }

                @Override
                public KeymasterArgument[] newArray(int size) {
                    return new KeymasterArgument[size];
                }
            };

    protected KeymasterArgument(int tag) {
        this.tag = tag;
    }

    /**
     * Writes the value of this argument, if any, to the provided parcel.
     */
    public abstract void writeValue(Parcel out);

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(tag);
        writeValue(out);
    }
}
