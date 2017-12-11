/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.telephony.uicc.asn1;

/**
 * Exception for invalid ASN.1 data in DER encoding which cannot be parsed as a node or a specific
 * data type.
 */
public class InvalidAsn1DataException extends Exception {
    private final int mTag;

    public InvalidAsn1DataException(int tag, String message) {
        super(message);
        mTag = tag;
    }

    public InvalidAsn1DataException(int tag, String message, Throwable throwable) {
        super(message, throwable);
        mTag = tag;
    }

    /** @return The tag which has the invalid data. */
    public int getTag() {
        return mTag;
    }

    @Override
    public String getMessage() {
        return super.getMessage() + " (tag=" + mTag + ")";
    }
}
