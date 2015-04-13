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

package android.security.keymaster;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @hide
 */
public class KeyCharacteristics implements Parcelable {
    public KeymasterArguments swEnforced;
    public KeymasterArguments hwEnforced;

    public static final Parcelable.Creator<KeyCharacteristics> CREATOR = new
            Parcelable.Creator<KeyCharacteristics>() {
                @Override
                public KeyCharacteristics createFromParcel(Parcel in) {
                    return new KeyCharacteristics(in);
                }

                @Override
                public KeyCharacteristics[] newArray(int length) {
                    return new KeyCharacteristics[length];
                }
            };

    public KeyCharacteristics() {}

    protected KeyCharacteristics(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        swEnforced.writeToParcel(out, flags);
        hwEnforced.writeToParcel(out, flags);
    }

    public void readFromParcel(Parcel in) {
        swEnforced = KeymasterArguments.CREATOR.createFromParcel(in);
        hwEnforced = KeymasterArguments.CREATOR.createFromParcel(in);
    }

    public Integer getInteger(int tag) {
        if (hwEnforced.containsTag(tag)) {
            return hwEnforced.getInt(tag, -1);
        } else if (swEnforced.containsTag(tag)) {
            return swEnforced.getInt(tag, -1);
        } else {
            return null;
        }
    }

    public int getInt(int tag, int defaultValue) {
        Integer result = getInteger(tag);
        return (result != null) ? result : defaultValue;
    }

    public List<Integer> getInts(int tag) {
        List<Integer> result = new ArrayList<Integer>();
        result.addAll(hwEnforced.getInts(tag));
        result.addAll(swEnforced.getInts(tag));
        return result;
    }

    public Date getDate(int tag) {
        Date result = hwEnforced.getDate(tag, null);
        if (result == null) {
            result = swEnforced.getDate(tag, null);
        }
        return result;
    }

    public Date getDate(int tag, Date defaultValue) {
        if (hwEnforced.containsTag(tag)) {
            return hwEnforced.getDate(tag, null);
        } else if (hwEnforced.containsTag(tag)) {
            return swEnforced.getDate(tag, null);
        } else {
            return defaultValue;
        }
    }

    public boolean getBoolean(KeyCharacteristics keyCharacteristics, int tag) {
        if (keyCharacteristics.hwEnforced.containsTag(tag)) {
            return keyCharacteristics.hwEnforced.getBoolean(tag, false);
        } else {
            return keyCharacteristics.swEnforced.getBoolean(tag, false);
        }
    }
}

