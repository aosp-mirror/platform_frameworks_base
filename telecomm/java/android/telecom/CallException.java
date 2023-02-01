/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class defines exceptions that can be thrown when using Telecom APIs with
 * {@link android.os.OutcomeReceiver}s.  Most of these exceptions are thrown when changing a call
 * state with {@link CallControl}s or {@link CallEventCallback}s.
 */
public final class CallException extends RuntimeException implements Parcelable {
    /** @hide **/
    public static final String TRANSACTION_EXCEPTION_KEY = "TelecomTransactionalExceptionKey";

    /**
     * The operation has failed due to an unknown or unspecified error.
     */
    public static final int CODE_ERROR_UNKNOWN = 1;

    /**
     * The operation has failed due to Telecom failing to hold the current active call for the
     * call attempting to become the new active call.  The client should end the current active call
     * and re-try the failed operation.
     */
    public static final int CODE_CANNOT_HOLD_CURRENT_ACTIVE_CALL = 2;

    /**
     * The operation has failed because Telecom has already removed the call from the server side
     * and destroyed all the objects associated with it.  The client should re-add the call.
     */
    public static final int CODE_CALL_IS_NOT_BEING_TRACKED = 3;

    /**
     * The operation has failed because Telecom cannot set the requested call as the current active
     * call.  The client should end the current active call and re-try the operation.
     */
    public static final int CODE_CALL_CANNOT_BE_SET_TO_ACTIVE = 4;

    /**
     * The operation has failed because there is either no PhoneAccount registered with Telecom
     * for the given operation, or the limit of calls has been reached. The client should end the
     * current active call and re-try the failed operation.
     */
    public static final int CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME = 5;

    /**
     * The operation has failed because the operation failed to complete before the timeout
     */
    public static final int CODE_OPERATION_TIMED_OUT = 6;

    private int mCode = CODE_ERROR_UNKNOWN;
    private final String mMessage;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mMessage);
        dest.writeInt(mCode);
    }

    /**
     * Responsible for creating CallAttribute objects for deserialized Parcels.
     */
    public static final @android.annotation.NonNull
            Parcelable.Creator<CallException> CREATOR = new Parcelable.Creator<>() {
                    @Override
                    public CallException createFromParcel(Parcel source) {
                        return new CallException(source.readString8(), source.readInt());
                    }

                    @Override
                    public CallException[] newArray(int size) {
                        return new CallException[size];
                    }
            };

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CODE_ERROR_", value = {
            CODE_ERROR_UNKNOWN,
            CODE_CANNOT_HOLD_CURRENT_ACTIVE_CALL,
            CODE_CALL_IS_NOT_BEING_TRACKED,
            CODE_CALL_CANNOT_BE_SET_TO_ACTIVE,
            CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME,
            CODE_OPERATION_TIMED_OUT
    })
    public @interface CallErrorCode {
    }

    /**
     * Constructor for a new CallException when only message can be specified.
     * {@code CODE_ERROR_UNKNOWN} will be default code returned when calling {@code getCode}
     *
     * @param message related to why the exception was created
     */
    public CallException(@Nullable String message) {
        super(getMessage(message, CODE_ERROR_UNKNOWN));
        mMessage = message;
    }

    /**
     * Constructor for a new CallException that has a defined error code in this class
     *
     * @param message related to why the exception was created
     * @param code defined above that caused this exception to be created
     */
    public CallException(@Nullable String message, @CallErrorCode int code) {
        super(getMessage(message, code));
        mCode = code;
        mMessage = message;
    }

    /**
     * @return one of the error codes defined in this class that was passed into the constructor
     */
    public @CallErrorCode int getCode() {
        return mCode;
    }

    private static String getMessage(String message, int code) {
        StringBuilder builder;
        if (!TextUtils.isEmpty(message)) {
            builder = new StringBuilder(message);
            builder.append(" (code: ");
            builder.append(code);
            builder.append(")");
            return builder.toString();
        } else {
            return "code: " + code;
        }
    }
}
