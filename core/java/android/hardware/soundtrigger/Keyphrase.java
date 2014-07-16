/**
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.soundtrigger;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A Voice Keyphrase.
 *
 * @hide
 */
public class Keyphrase implements Parcelable {
    /** A unique identifier for this keyphrase */
    public final int id;
    /** A hint text to display corresponding to this keyphrase, e.g. "Hello There". */
    public final String hintText;
    /** The locale of interest when using this Keyphrase. */
    public final String locale;
    /** The various recognition modes supported by this keyphrase */
    public final int recognitionModeFlags;
    /** The users associated with this keyphrase */
    public final int[] users;

    public static final Parcelable.Creator<Keyphrase> CREATOR
            = new Parcelable.Creator<Keyphrase>() {
        public Keyphrase createFromParcel(Parcel in) {
            return Keyphrase.fromParcel(in);
        }

        public Keyphrase[] newArray(int size) {
            return new Keyphrase[size];
        }
    };

    private static Keyphrase fromParcel(Parcel in) {
        int id = in.readInt();
        String hintText = in.readString();
        String locale = in.readString();
        int recognitionModeFlags = in.readInt();
        int numUsers = in.readInt();
        int[] users = null;
        if (numUsers > 0) {
            users = new int[numUsers];
            in.readIntArray(users);
        }
        return new Keyphrase(id, hintText, locale, recognitionModeFlags, users);
    }

    public Keyphrase(int id, String hintText, String locale, int recognitionModeFlags,
            int[] users) {
        this.id = id;
        this.hintText = hintText;
        this.locale = locale;
        this.recognitionModeFlags = recognitionModeFlags;
        this.users = users;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeString(hintText);
        dest.writeString(locale);
        dest.writeInt(recognitionModeFlags);
        if (users != null) {
            dest.writeInt(users.length);
            dest.writeIntArray(users);
        } else {
            dest.writeInt(0);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((hintText == null) ? 0 : hintText.hashCode());
        result = prime * result + id;
        result = prime * result + ((locale == null) ? 0 : locale.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Keyphrase other = (Keyphrase) obj;
        if (hintText == null) {
            if (other.hintText != null)
                return false;
        } else if (!hintText.equals(other.hintText))
            return false;
        if (id != other.id)
            return false;
        if (locale == null) {
            if (other.locale != null)
                return false;
        } else if (!locale.equals(other.locale))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "Keyphrase[id=" + id + ", text=" + hintText + ", locale=" + locale
                + ", recognitionModes=" + recognitionModeFlags + "]";
    }

    protected SoundTrigger.Keyphrase convertToSoundTriggerKeyphrase() {
        return new SoundTrigger.Keyphrase(id, recognitionModeFlags, locale, hintText, users);
    }
}
