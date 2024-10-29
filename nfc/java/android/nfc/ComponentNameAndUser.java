/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.nfc;

import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * @hide
 */
public class ComponentNameAndUser implements Parcelable {
    @UserIdInt private final int mUserId;
    private ComponentName mComponentName;

    public ComponentNameAndUser(@UserIdInt int userId, ComponentName componentName) {
        mUserId = userId;
        mComponentName = componentName;
    }

    /**
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mUserId);
        out.writeParcelable(mComponentName, flags);
    }

    public static final Parcelable.Creator<ComponentNameAndUser> CREATOR =
            new Parcelable.Creator<ComponentNameAndUser>() {
                public ComponentNameAndUser createFromParcel(Parcel in) {
                    return new ComponentNameAndUser(in);
                }

                public ComponentNameAndUser[] newArray(int size) {
                    return new ComponentNameAndUser[size];
                }
            };

    private ComponentNameAndUser(Parcel in) {
        mUserId = in.readInt();
        mComponentName = in.readParcelable(null, ComponentName.class);
    }

    @UserIdInt
    public int getUserId() {
        return mUserId;
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    @Override
    public String toString() {
        return mComponentName + " for user id: " + mUserId;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj != null && obj instanceof ComponentNameAndUser) {
            ComponentNameAndUser other = (ComponentNameAndUser) obj;
            return other.getUserId() == mUserId
                    && Objects.equals(other.getComponentName(), mComponentName);
        }
        return false;
    }

    @Override
    public int hashCode() {
        if (mComponentName == null) {
            return mUserId;
        }
        return mComponentName.hashCode() + mUserId;
    }
}
