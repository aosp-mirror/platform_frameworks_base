/**
 * Copyright (c) 2014, The Android Open Source Project
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

package android.service.notification;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Condition information from condition providers.
 *
 * @hide
 */
public class Condition implements Parcelable {

    public static final int FLAG_RELEVANT_NOW = 1 << 0;
    public static final int FLAG_RELEVANT_ALWAYS = 1 << 1;

    public final Uri id;
    public String caption;
    public boolean state;
    public int flags;


    public Condition(Uri id, String caption, boolean state, int flags) {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (caption == null) throw new IllegalArgumentException("caption is required");
        this.id = id;
        this.caption = caption;
        this.state = state;
        this.flags = flags;
    }

    private Condition(Parcel source) {
        id = Uri.CREATOR.createFromParcel(source);
        caption = source.readString();
        state = source.readInt() == 1;
        flags = source.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(id, 0);
        dest.writeString(caption);
        dest.writeInt(state ? 1 : 0);
        dest.writeInt(flags);
    }

    @Override
    public String toString() {
        return new StringBuilder(Condition.class.getSimpleName()).append('[')
            .append("id=").append(id)
            .append(",caption=").append(caption)
            .append(",state=").append(state)
            .append(",flags=").append(flags)
            .append(']').toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Condition)) return false;
        if (o == this) return true;
        final Condition other = (Condition) o;
        return Objects.equals(other.id, id)
                && Objects.equals(other.caption, caption)
                && other.state == state
                && other.flags == flags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, caption, state, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public Condition copy() {
        final Parcel parcel = Parcel.obtain();
        try {
            writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return new Condition(parcel);
        } finally {
            parcel.recycle();
        }
    }

    public static final Parcelable.Creator<Condition> CREATOR
            = new Parcelable.Creator<Condition>() {
        @Override
        public Condition createFromParcel(Parcel source) {
            return new Condition(source);
        }

        @Override
        public Condition[] newArray(int size) {
            return new Condition[size];
        }
    };
}
