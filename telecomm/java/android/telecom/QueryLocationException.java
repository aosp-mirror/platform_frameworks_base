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
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * This class represents a set of exceptions that can occur when requesting a
 * {@link Connection#queryLocationForEmergency(long, String, Executor, OutcomeReceiver)}
 */
public final class QueryLocationException extends RuntimeException implements Parcelable {
    /** @hide */
    public static final String QUERY_LOCATION_ERROR = "QueryLocationErrorKey";

    /**
     * The operation was not completed on time.
     */
    public static final int ERROR_REQUEST_TIME_OUT = 1;
    /**
     * The operation was rejected due to an existing request.
     */
    public static final int ERROR_PREVIOUS_REQUEST_EXISTS = 2;
    /**
     * The operation has failed because it is not permitted.
     */
    public static final int ERROR_NOT_PERMITTED = 3;
    /**
     * The operation has failed due to a location query being requested for a non-emergency
     * connection.
     */
    public static final int ERROR_NOT_ALLOWED_FOR_NON_EMERGENCY_CONNECTIONS = 4;
    /**
     * The operation has failed due to the service is not available.
     */
    public static final int ERROR_SERVICE_UNAVAILABLE = 5;
    /**
     * The operation has failed due to an unknown or unspecified error.
     */
    public static final int ERROR_UNSPECIFIED = 6;

    private int mCode = ERROR_UNSPECIFIED;
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
     * Responsible for creating QueryLocationException objects for deserialized Parcels.
     */
    public static final
            @android.annotation.NonNull Parcelable.Creator<QueryLocationException> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public QueryLocationException createFromParcel(Parcel source) {
                    return new QueryLocationException(source.readString8(), source.readInt());
                }
                @Override
                public QueryLocationException[] newArray(int size) {
                    return new QueryLocationException[size];
                }
            };
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ERROR_REQUEST_TIME_OUT,
            ERROR_PREVIOUS_REQUEST_EXISTS,
            ERROR_NOT_PERMITTED,
            ERROR_NOT_ALLOWED_FOR_NON_EMERGENCY_CONNECTIONS,
            ERROR_SERVICE_UNAVAILABLE,
            ERROR_UNSPECIFIED})
    public @interface QueryLocationErrorCode {}
    public QueryLocationException(@Nullable String message) {
        super(getMessage(message, ERROR_UNSPECIFIED));
        mMessage = message;
    }
    public QueryLocationException(@Nullable String message, @QueryLocationErrorCode int code) {
        super(getMessage(message, code));
        mCode = code;
        mMessage = message;
    }
    public QueryLocationException(
            @Nullable String message, @QueryLocationErrorCode int code, @Nullable Throwable cause) {
        super(getMessage(message, code), cause);
        mCode = code;
        mMessage = message;
    }
    public @QueryLocationErrorCode int getCode() {
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
