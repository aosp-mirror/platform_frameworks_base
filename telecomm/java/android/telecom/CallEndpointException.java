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
 * This class represents a set of exceptions that can occur when requesting a
 * {@link CallEndpoint} change.
 */
public final class CallEndpointException extends RuntimeException implements Parcelable {
    /** @hide */
    public static final String CHANGE_ERROR = "ChangeErrorKey";

    /**
     * The operation has failed because requested CallEndpoint does not exist.
     */
    public static final int ERROR_ENDPOINT_DOES_NOT_EXIST = 1;

    /**
     * The operation was not completed on time.
     */
    public static final int ERROR_REQUEST_TIME_OUT = 2;

    /**
     * The operation was canceled by another request.
     */
    public static final int ERROR_ANOTHER_REQUEST = 3;

    /**
     * The operation has failed due to an unknown or unspecified error.
     */
    public static final int ERROR_UNSPECIFIED = 4;

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
     * Responsible for creating CallEndpointException objects for deserialized Parcels.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<CallEndpointException>
            CREATOR = new Parcelable.Creator<>() {
                @Override
                public CallEndpointException createFromParcel(Parcel source) {
                    return new CallEndpointException(source.readString8(), source.readInt());
                }

                @Override
                public CallEndpointException[] newArray(int size) {
                    return new CallEndpointException[size];
                }
            };

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ERROR_ENDPOINT_DOES_NOT_EXIST, ERROR_REQUEST_TIME_OUT, ERROR_ANOTHER_REQUEST,
            ERROR_UNSPECIFIED})
    public @interface CallEndpointErrorCode {
    }

    public CallEndpointException(@Nullable String message, @CallEndpointErrorCode int code) {
        super(getMessage(message, code));
        mCode = code;
        mMessage = message;
    }

    public @CallEndpointErrorCode int getCode() {
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
