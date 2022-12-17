/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.media;

/**
 * Base class for MediaDrm exceptions
 */
public class MediaDrmException extends Exception implements MediaDrmThrowable {
    public MediaDrmException(String detailMessage) {
        this(detailMessage, 0, 0, 0);
    }

    /**
     * @hide
     */
    public MediaDrmException(String message, int vendorError, int oemError, int errorContext) {
        super(message);
        mVendorError = vendorError;
        mOemError = oemError;
        mErrorContext = errorContext;
    }

    @Override
    public int getVendorError() {
        return mVendorError;
    }

    @Override
    public int getOemError() {
        return mOemError;
    }

    @Override
    public int getErrorContext() {
        return mErrorContext;
    }

    private final int mVendorError, mOemError, mErrorContext;
}
