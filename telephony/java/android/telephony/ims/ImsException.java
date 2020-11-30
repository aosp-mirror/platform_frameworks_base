/*
 * Copyright (c) 2019 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.PackageManager;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class defines an IMS-related exception that has been thrown while interacting with a
 * device or carrier provided ImsService implementation.
 */
public final class ImsException extends Exception {

    /**
     * The operation has failed due to an unknown or unspecified error.
     */
    public static final int CODE_ERROR_UNSPECIFIED = 0;
    /**
     * The operation has failed because there is no remote process available to service it. This
     * may be due to a process crash or other illegal state.
     * <p>
     * This is a temporary error and the operation may be retried until the connection to the
     * remote process is restored.
     */
    public static final int CODE_ERROR_SERVICE_UNAVAILABLE = 1;

    /**
     * This device or carrier configuration does not support this feature for this subscription.
     * <p>
     * This is a permanent configuration error and there should be no retry until the subscription
     * changes if this operation is denied due to a carrier configuration. If this is due to a
     * device configuration, the feature {@link PackageManager#FEATURE_TELEPHONY_IMS} is not
     * available or the device has no ImsService implementation to service this request.
     */
    public static final int CODE_ERROR_UNSUPPORTED_OPERATION = 2;

    /**
     * The subscription ID associated with this operation is invalid or not active.
     * <p>
     * This is a configuration error and there should be no retry. The subscription used for this
     * operation is either invalid or has become inactive. The active subscriptions can be queried
     * with {@link SubscriptionManager#getActiveSubscriptionInfoList()}.
     */
    public static final int CODE_ERROR_INVALID_SUBSCRIPTION = 3;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CODE_ERROR_", value = {
            CODE_ERROR_UNSPECIFIED,
            CODE_ERROR_SERVICE_UNAVAILABLE,
            CODE_ERROR_UNSUPPORTED_OPERATION,
            CODE_ERROR_INVALID_SUBSCRIPTION
    })
    public @interface ImsErrorCode {}

    private int mCode = CODE_ERROR_UNSPECIFIED;

    /**
     * A new {@link ImsException} with an unspecified {@link ImsErrorCode} code.
     * @param message an optional message to detail the error condition more specifically.
     * @hide
     */
    @SystemApi
    public ImsException(@Nullable String message) {
        super(getMessage(message, CODE_ERROR_UNSPECIFIED));
    }

    /**
     * A new {@link ImsException} that includes an {@link ImsErrorCode} error code.
     * @param message an optional message to detail the error condition more specifically.
     * @hide
     */
    @SystemApi
    public ImsException(@Nullable String message, @ImsErrorCode int code) {
        super(getMessage(message, code));
        mCode = code;
    }

    /**
     * A new {@link ImsException} that includes an {@link ImsErrorCode} error code and a
     * {@link Throwable} that contains the original error that was thrown to lead to this Exception.
     * @param message an optional message to detail the error condition more specifically.
     * @param cause the {@link Throwable} that caused this {@link ImsException} to be created.
     * @hide
     */
    @SystemApi
    public ImsException(@Nullable String message, @ImsErrorCode  int code,
            @Nullable Throwable cause) {
        super(getMessage(message, code), cause);
        mCode = code;
    }

    /**
     * @return the IMS Error code that is associated with this {@link ImsException}.
     */
    public @ImsErrorCode int getCode() {
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
