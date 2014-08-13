/*
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

package android.telecomm;

import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * The unique identifier for a {@link PhoneAccount}.
 */
public class PhoneAccountHandle implements Parcelable {
    private ComponentName mComponentName;
    private String mId;

    public PhoneAccountHandle(
            ComponentName componentName,
            String id) {
        mComponentName = componentName;
        mId = id;
    }

    /**
     * The {@code ComponentName} of the {@link android.telecomm.ConnectionService} which is
     * responsible for making phone calls using this {@code PhoneAccountHandle}.
     *
     * @return A suitable {@code ComponentName}.
     */
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * A string that uniquely distinguishes this particular {@code PhoneAccountHandle} from all the
     * others supported by the {@link ConnectionService} that created it.
     * <p>
     * A {@code ConnectionService} must select identifiers that are stable for the lifetime of
     * their users' relationship with their service, across many Android devices. For example, a
     * good set of identifiers might be the email addresses with which with users registered for
     * their accounts with a particular service. Depending on how a service chooses to operate,
     * a bad set of identifiers might be an increasing series of integers
     * ({@code 0}, {@code 1}, {@code 2}, ...) that are generated locally on each phone and could
     * collide with values generated on other phones or after a data wipe of a given phone.
     *
     * @return A service-specific unique identifier for this {@code PhoneAccountHandle}.
     */
    public String getId() {
        return mId;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mComponentName) + Objects.hashCode(mId);
    }

    @Override
    public String toString() {
        return new StringBuilder().append(mComponentName)
                    .append(", ")
                    .append(mId)
                    .toString();
    }

    @Override
    public boolean equals(Object other) {
        return other != null &&
                other instanceof PhoneAccountHandle &&
                Objects.equals(((PhoneAccountHandle) other).getComponentName(),
                        getComponentName()) &&
                Objects.equals(((PhoneAccountHandle) other).getId(), getId());
    }

    //
    // Parcelable implementation.
    //

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mComponentName, flags);
        out.writeString(mId);
    }

    public static final Creator<PhoneAccountHandle> CREATOR = new Creator<PhoneAccountHandle>() {
        @Override
        public PhoneAccountHandle createFromParcel(Parcel in) {
            return new PhoneAccountHandle(in);
        }

        @Override
        public PhoneAccountHandle[] newArray(int size) {
            return new PhoneAccountHandle[size];
        }
    };

    private PhoneAccountHandle(Parcel in) {
        mComponentName = in.readParcelable(getClass().getClassLoader());
        mId = in.readString();
    }
}
