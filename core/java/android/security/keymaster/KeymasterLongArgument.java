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

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;

/**
 * @hide
 */
class KeymasterLongArgument extends KeymasterArgument {
    @UnsupportedAppUsage
    public final long value;

    @UnsupportedAppUsage
    public KeymasterLongArgument(int tag, long value) {
        super(tag);
        switch (KeymasterDefs.getTagType(tag)) {
            case KeymasterDefs.KM_ULONG:
            case KeymasterDefs.KM_ULONG_REP:
                break; // OK.
            default:
                throw new IllegalArgumentException("Bad long tag " + tag);
        }
        this.value = value;
    }

    @UnsupportedAppUsage
    public KeymasterLongArgument(int tag, Parcel in) {
        super(tag);
        value = in.readLong();
    }

    @Override
    public void writeValue(Parcel out) {
        out.writeLong(value);
    }
}
