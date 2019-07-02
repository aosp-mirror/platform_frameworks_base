/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Specialization of {@link SecurityException} that is thrown when authentication is needed from the
 * end user before viewing the content.
 * <p>
 * This exception is only appropriate where there is a concrete action the user can take to
 * authorize and make forward progress, such as confirming or entering authentication credentials,
 * or granting access via other means.
 * <p class="note">
 * Note: legacy code that receives this exception may treat it as a general
 * {@link SecurityException}, and thus there is no guarantee that the action contained will be
 * invoked by the user.
 * </p>
 */
public final class AuthenticationRequiredException extends SecurityException implements Parcelable {
    private static final String TAG = "AuthenticationRequiredException";

    private final PendingIntent mUserAction;

    /** {@hide} */
    public AuthenticationRequiredException(Parcel in) {
        this(new SecurityException(in.readString()), PendingIntent.CREATOR.createFromParcel(in));
    }

    /**
     * Create an instance ready to be thrown.
     *
     * @param cause original cause with details designed for engineering
     *            audiences.
     * @param userAction primary action that will initiate the recovery. This
     *            must launch an activity that is expected to set
     *            {@link Activity#setResult(int)} before finishing to
     *            communicate the final status of the recovery. For example,
     *            apps that observe {@link Activity#RESULT_OK} may choose to
     *            immediately retry their operation.
     */
    public AuthenticationRequiredException(Throwable cause, PendingIntent userAction) {
        super(cause.getMessage());
        mUserAction = Preconditions.checkNotNull(userAction);
    }

    /**
     * Return primary action that will initiate the authorization.
     */
    public PendingIntent getUserAction() {
        return mUserAction;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(getMessage());
        mUserAction.writeToParcel(dest, flags);
    }

    public static final @android.annotation.NonNull Creator<AuthenticationRequiredException> CREATOR =
            new Creator<AuthenticationRequiredException>() {
        @Override
        public AuthenticationRequiredException createFromParcel(Parcel source) {
            return new AuthenticationRequiredException(source);
        }

        @Override
        public AuthenticationRequiredException[] newArray(int size) {
            return new AuthenticationRequiredException[size];
        }
    };
}
