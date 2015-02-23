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

import java.util.List;

/**
 * @hide
 */
public class KeyCharacteristics implements Parcelable {
    public KeymasterArguments swEnforced;
    public KeymasterArguments hwEnforced;

    public static final Parcelable.Creator<KeyCharacteristics> CREATOR = new
            Parcelable.Creator<KeyCharacteristics>() {
                public KeyCharacteristics createFromParcel(Parcel in) {
                    return new KeyCharacteristics(in);
                }

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

    public void writeToParcel(Parcel out, int flags) {
        swEnforced.writeToParcel(out, flags);
        hwEnforced.writeToParcel(out, flags);
    }

    public void readFromParcel(Parcel in) {
        swEnforced = KeymasterArguments.CREATOR.createFromParcel(in);
        hwEnforced = KeymasterArguments.CREATOR.createFromParcel(in);
    }
}

