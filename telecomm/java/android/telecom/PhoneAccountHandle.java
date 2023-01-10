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
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
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
 *      component name. Apps registering {@link PhoneAccountHandle}s should ensure that the
 *      {@link #getId()} provided does not expose personally identifying information.  A
 *      {@link ConnectionService} should use an opaque token as the {@link PhoneAccountHandle}
 *      identifier.</li>
 * </ul>
 *
 * Note: This Class requires a non-null {@link ComponentName} and {@link UserHandle} to operate
 * properly. Passing in invalid parameters will generate a log warning.
 *
 * See {@link PhoneAccount}, {@link TelecomManager}.
 */
public final class PhoneAccountHandle implements Parcelable {
    /**
     * Expected component name of Telephony phone accounts; ONLY used to determine if we should log
     * the phone account handle ID.
     */
    private static final ComponentName TELEPHONY_COMPONENT_NAME =
            new ComponentName("com.android.phone",
                    "com.android.services.telephony.TelephonyConnectionService");

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 127403196)
    private final ComponentName mComponentName;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final String mId;
    private final UserHandle mUserHandle;

    /**
     * Creates a new {@link PhoneAccountHandle}.
     *
     * @param componentName The {@link ComponentName} of the {@link ConnectionService} which
     *                      services this {@link PhoneAccountHandle}.
     * @param id A string identifier that is unique across {@code PhoneAccountHandle}s with the same
     *           component name. Apps registering {@link PhoneAccountHandle}s should ensure that the
     *           ID provided does not expose personally identifying information.  A
     *           {@link ConnectionService} should use an opaque token as the
     *           {@link PhoneAccountHandle} identifier.
     * <p>
     * Note: Each String field is limited to 256 characters. This check is enforced when
     *           registering the PhoneAccount via
     *           {@link TelecomManager#registerPhoneAccount(PhoneAccount)} and will cause an
     *           {@link IllegalArgumentException} to be thrown if the character field limit is
     *           over 256.
     */
    public PhoneAccountHandle(
            @NonNull ComponentName componentName,
            @NonNull String id) {
        this(componentName, id, Process.myUserHandle());
    }

    /**
     * Creates a new {@link PhoneAccountHandle}.
     *
     * @param componentName The {@link ComponentName} of the {@link ConnectionService} which
     *                      services this {@link PhoneAccountHandle}.
     * @param id A string identifier that is unique across {@code PhoneAccountHandle}s with the same
     *           component name. Apps registering {@link PhoneAccountHandle}s should ensure that the
     *           ID provided does not expose personally identifying information.  A
     *           {@link ConnectionService} should use an opaque token as the
     *           {@link PhoneAccountHandle} identifier.
     * @param userHandle The {@link UserHandle} associated with this {@link PhoneAccountHandle}.
     *
     * <p>
     * Note: Each String field is limited to 256 characters. This check is enforced when
     *           registering the PhoneAccount via
     *           {@link TelecomManager#registerPhoneAccount(PhoneAccount)} and will cause an
     *           {@link IllegalArgumentException} to be thrown if the character field limit is
     *           over 256.
     */
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
     * their users' relationship with their service, across many Android devices.  The identifier
     * should be a stable opaque token which uniquely identifies the user within the service.
     * Depending on how a service chooses to operate, a bad set of identifiers might be an
     * increasing series of integers ({@code 0}, {@code 1}, {@code 2}, ...) that are generated
     * locally on each phone and could collide with values generated on other phones or after a data
     * wipe of a given phone.
     * <p>
     * Important: A non-unique identifier could cause non-deterministic call-log backup/restore
     * behavior.
     *
     * @return A service-specific unique opaque identifier for this {@code PhoneAccountHandle}.
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
        StringBuilder sb = new StringBuilder()
                .append(mComponentName)
                .append(", ");

        if (TELEPHONY_COMPONENT_NAME.equals(mComponentName)) {
            // Telephony phone account handles are now keyed by subscription id which is not
            // sensitive.
            sb.append(mId);
        } else {
            // Note: Log.pii called for mId as it can contain personally identifying phone account
            // information such as SIP account IDs.
            sb.append(Log.pii(mId));
        }
        sb.append(", ");
        sb.append(mUserHandle);

        return sb.toString();
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

    public static final @android.annotation.NonNull Creator<PhoneAccountHandle> CREATOR =
            new Creator<PhoneAccountHandle>() {
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

    /**
     * Determines if two {@link PhoneAccountHandle}s are from the same package.
     *
     * @param a Phone account handle to check for same {@link ConnectionService} package.
     * @param b Other phone account handle to check for same {@link ConnectionService} package.
     * @return {@code true} if the two {@link PhoneAccountHandle}s passed in belong to the same
     * {@link ConnectionService} / package, {@code false} otherwise.  Note: {@code null} phone
     * account handles are considered equivalent to other {@code null} phone account handles.
     * @hide
     */
    public static boolean areFromSamePackage(@Nullable PhoneAccountHandle a,
            @Nullable PhoneAccountHandle b) {
        String aPackageName = a != null ? a.getComponentName().getPackageName() : null;
        String bPackageName = b != null ? b.getComponentName().getPackageName() : null;
        return Objects.equals(aPackageName, bPackageName);
    }
}
