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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;

import java.util.Date;

/**
 * @hide
 */
class KeymasterDateArgument extends KeymasterArgument {
    public final Date date;

    public KeymasterDateArgument(int tag, Date date) {
        super(tag);
        switch (KeymasterDefs.getTagType(tag)) {
            case KeymasterDefs.KM_DATE:
                break; // OK.
            default:
                throw new IllegalArgumentException("Bad date tag " + tag);
        }
        this.date = date;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public KeymasterDateArgument(int tag, Parcel in) {
        super(tag);
        date = new Date(in.readLong());
    }

    @Override
    public void writeValue(Parcel out) {
        out.writeLong(date.getTime());
    }
}
