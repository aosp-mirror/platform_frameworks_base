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

import android.annotation.SystemApi;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Condition information from condition providers.
 *
 * @hide
 */
@SystemApi
public class Condition implements Parcelable {

    public static final String SCHEME = "condition";

    public static final int STATE_FALSE = 0;
    public static final int STATE_TRUE = 1;
    public static final int STATE_UNKNOWN = 2;
    public static final int STATE_ERROR = 3;

    public static final int FLAG_RELEVANT_NOW = 1 << 0;
    public static final int FLAG_RELEVANT_ALWAYS = 1 << 1;

    public final Uri id;
    public final String summary;
    public final String line1;
    public final String line2;
    public final int icon;
    public final int state;
    public final int flags;

    public Condition(Uri id, String summary, String line1, String line2, int icon,
            int state, int flags) {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (summary == null) throw new IllegalArgumentException("summary is required");
        if (line1 == null) throw new IllegalArgumentException("line1 is required");
        if (line2 == null) throw new IllegalArgumentException("line2 is required");
        if (!isValidState(state)) throw new IllegalArgumentException("state is invalid: " + state);
        this.id = id;
        this.summary = summary;
        this.line1 = line1;
        this.line2 = line2;
        this.icon = icon;
        this.state = state;
        this.flags = flags;
    }

    private Condition(Parcel source) {
        this((Uri)source.readParcelable(Condition.class.getClassLoader()),
                source.readString(),
                source.readString(),
                source.readString(),
                source.readInt(),
                source.readInt(),
                source.readInt());
    }

    private static boolean isValidState(int state) {
        return state >= STATE_FALSE && state <= STATE_ERROR;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(id, 0);
        dest.writeString(summary);
        dest.writeString(line1);
        dest.writeString(line2);
        dest.writeInt(icon);
        dest.writeInt(state);
        dest.writeInt(this.flags);
    }

    @Override
    public String toString() {
        return new StringBuilder(Condition.class.getSimpleName()).append('[')
            .append("id=").append(id)
            .append(",summary=").append(summary)
            .append(",line1=").append(line1)
            .append(",line2=").append(line2)
            .append(",icon=").append(icon)
            .append(",state=").append(stateToString(state))
            .append(",flags=").append(flags)
            .append(']').toString();
    }

    public static String stateToString(int state) {
        if (state == STATE_FALSE) return "STATE_FALSE";
        if (state == STATE_TRUE) return "STATE_TRUE";
        if (state == STATE_UNKNOWN) return "STATE_UNKNOWN";
        if (state == STATE_ERROR) return "STATE_ERROR";
        throw new IllegalArgumentException("state is invalid: " + state);
    }

    public static String relevanceToString(int flags) {
        final boolean now = (flags & FLAG_RELEVANT_NOW) != 0;
        final boolean always = (flags & FLAG_RELEVANT_ALWAYS) != 0;
        if (!now && !always) return "NONE";
        if (now && always) return "NOW, ALWAYS";
        return now ? "NOW" : "ALWAYS";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Condition)) return false;
        if (o == this) return true;
        final Condition other = (Condition) o;
        return Objects.equals(other.id, id)
                && Objects.equals(other.summary, summary)
                && Objects.equals(other.line1, line1)
                && Objects.equals(other.line2, line2)
                && other.icon == icon
                && other.state == state
                && other.flags == flags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, summary, line1, line2, icon, state, flags);
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

    public static Uri.Builder newId(Context context) {
        return new Uri.Builder().scheme(SCHEME).authority(context.getPackageName());
    }

    public static boolean isValidId(Uri id, String pkg) {
        return id != null && SCHEME.equals(id.getScheme()) && pkg.equals(id.getAuthority());
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
