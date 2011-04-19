/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Per-user information.
 * @hide
 */
public class UserInfo implements Parcelable {
    /**
     * Primary user. Only one user can have this flag set. Meaning of this
     * flag TBD.
     */
    public static final int FLAG_PRIMARY = 0x00000001;

    /**
     * User with administrative privileges. Such a user can create and
     * delete users.
     */
    public static final int FLAG_ADMIN   = 0x00000002;

    /**
     * Indicates a guest user that may be transient.
     */
    public static final int FLAG_GUEST   = 0x00000004;

    public int id;
    public String name;
    public int flags;

    public UserInfo(int id, String name, int flags) {
        this.id = id;
        this.name = name;
        this.flags = flags;
    }

    public boolean isPrimary() {
        return (flags & FLAG_PRIMARY) == FLAG_PRIMARY;
    }

    public boolean isAdmin() {
        return (flags & FLAG_ADMIN) == FLAG_ADMIN;
    }

    public boolean isGuest() {
        return (flags & FLAG_GUEST) == FLAG_GUEST;
    }

    public UserInfo() {
    }

    public UserInfo(UserInfo orig) {
        name = orig.name;
        id = orig.id;
        flags = orig.flags;
    }

    @Override
    public String toString() {
        return "UserInfo{" + id + ":" + name + ":" + Integer.toHexString(flags) + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeInt(id);
        dest.writeString(name);
        dest.writeInt(flags);
    }

    public static final Parcelable.Creator<UserInfo> CREATOR
            = new Parcelable.Creator<UserInfo>() {
        public UserInfo createFromParcel(Parcel source) {
            return new UserInfo(source);
        }
        public UserInfo[] newArray(int size) {
            return new UserInfo[size];
        }
    };

    private UserInfo(Parcel source) {
        id = source.readInt();
        name = source.readString();
        flags = source.readInt();
    }
}
