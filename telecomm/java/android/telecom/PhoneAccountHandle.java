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

package android.telecom;

import android.annotation.NonNull;
import android.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.UserHandle;

import java.util.Objects;

/**
 * The unique identifier for a {@link PhoneAccount}. A {@code PhoneAccountHandle} is made of two
 * parts:
 * <ul>
 *  <li>The component name of the associated connection service.</li>
 *  <li>A string identifier that is unique across {@code PhoneAccountHandle}s with the same
 *      component name.</li>
 * </ul>
 *
 * Note: This Class requires a non-null {@link ComponentName} and {@link UserHandle} to operate
 * properly. Passing in invalid parameters will generate a log warning.
 *
 * See {@link PhoneAccount}, {@link TelecomManager}.
 */
public final class PhoneAccountHandle implements Parcelable {
    @UnsupportedAppUsage
    private final ComponentName mComponentName;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final String mId;
    private final UserHandle mUserHandle;

    public PhoneAccountHandle(
            @NonNull ComponentName componentName,
            @NonNull String id) {
        this(componentName, id, Process.myUserHandle());
    }

    public PhoneAccountHandle(
            @NonNull ComponentName componentName,
            @NonNull String id,
            @NonNull UserHandle userHandle) {
        checkParameters(componentName, userHandle);
        mComponentName = componentName;
        mId = id;
        mUserHandle = userHandle;
    }

    /**
     * The {@code ComponentName} of the connection service which is responsible for making phone
     * calls using this {@code PhoneAccountHandle}.
     *
     * @return A suitable {@code ComponentName}.
     */
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * A string that uniquely distinguishes this particular {@code PhoneAccountHandle} from all the
     * others supported by the connection service that created it.
     * <p>
     * A connection service must select identifiers that are stable for the lifetime of
     * their users' relationship with their service, across many Android devices. For example, a
     * good set of identifiers might be the email addresses with which with users registered for
     * their accounts with a particular service. Depending on how a service chooses to operate,
     * a bad set of identifiers might be an increasing series of integers
     * ({@code 0}, {@code 1}, {@code 2}, ...) that are generated locally on each phone and could
     * collide with values generated on other phones or after a data wipe of a given phone.
     *
     * Important: A non-unique identifier could cause non-deterministic call-log backup/restore
     * behavior.
     *
     * @return A service-specific unique identifier for this {@code PhoneAccountHandle}.
     */
    public String getId() {
        return mId;
    }

    /**
     * @return the {@link UserHandle} to use when connecting to this PhoneAccount.
     */
    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mComponentName, mId, mUserHandle);
    }

    @Override
    public String toString() {
        // Note: Log.pii called for mId as it can contain personally identifying phone account
        // information such as SIP account IDs.
        return new StringBuilder().append(mComponentName)
                    .append(", ")
                    .append(Log.pii(mId))
                    .append(", ")
                    .append(mUserHandle)
                    .toString();
    }

    @Override
    public boolean equals(Object other) {
        return other != null &&
                other instanceof PhoneAccountHandle &&
                Objects.equals(((PhoneAccountHandle) other).getComponentName(),
                        getComponentName()) &&
                Objects.equals(((PhoneAccountHandle) other).getId(), getId()) &&
                Objects.equals(((PhoneAccountHandle) other).getUserHandle(), getUserHandle());
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
        mComponentName.writeToParcel(out, flags);
        out.writeString(mId);
        mUserHandle.writeToParcel(out, flags);
    }

    private void checkParameters(ComponentName componentName, UserHandle userHandle) {
        if(componentName == null) {
            android.util.Log.w("PhoneAccountHandle", new Exception("PhoneAccountHandle has " +
                    "been created with null ComponentName!"));
        }
        if(userHandle == null) {
            android.util.Log.w("PhoneAccountHandle", new Exception("PhoneAccountHandle has " +
                    "been created with null UserHandle!"));
        }
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

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private PhoneAccountHandle(Parcel in) {
        this(ComponentName.CREATOR.createFromParcel(in),
                in.readString(),
                UserHandle.CREATOR.createFromParcel(in));
    }
}
