/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims;

import android.telephony.ims.ImsReasonInfo;

/**
 * This class defines a general IMS-related exception.
 *
 * @hide
 */
public class ImsException extends Exception {

    /**
     * Refer to CODE_LOCAL_* in {@link ImsReasonInfo}
     */
    private int mCode;

    public ImsException() {
    }

    public ImsException(String message, int code) {
        super(message + "(" + code + ")");
        mCode = code;
    }

    public ImsException(String message, Throwable cause, int code) {
        super(message, cause);
        mCode = code;
    }

    /**
     * Gets the detailed exception code when ImsException is throwed
     *
     * @return the exception code in {@link ImsReasonInfo}
     */
    public int getCode() {
        return mCode;
    }
}
