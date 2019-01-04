/*
 * Copyright (c) 2016 The Android Open Source Project
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

package com.android.ims.internal.uce.common;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;


/** Class for UCE status codes.
 *  @hide */
public class StatusCode implements Parcelable {

    /**
     *  UCE status code definitions.
     *  @hide
     */

    /**  Request was processed successfully. */
    public static final int UCE_SUCCESS = 0;
    /**  Request was processed unsuccessfully. */
    public static final int UCE_FAILURE = 1;
    /**  Asynchronous request was handled successfully; the final
     *  result will be updated through
     *  callback.
     */
    public static final int UCE_SUCCESS_ASYC_UPDATE = 2;
    /**  Provided service handle is not valid. */
    public static final int UCE_INVALID_SERVICE_HANDLE = 3;
    /**  Provided listener handler is not valid. */
    public static final int UCE_INVALID_LISTENER_HANDLE = 4;
    /**  Invalid parameter(s). */
    public static final int UCE_INVALID_PARAM = 5;
    /**  Fetch error. */
    public static final int UCE_FETCH_ERROR = 6;
    /**  Request timed out. */
    public static final int UCE_REQUEST_TIMEOUT = 7;
    /**  Failure due to insufficient memory available. */
    public static final int UCE_INSUFFICIENT_MEMORY = 8;
    /**  Network connection is lost. */
    public static final int UCE_LOST_NET = 9;
    /**  Requested feature/resource is not supported. */
    public static final int UCE_NOT_SUPPORTED = 10;
    /**  Contact or resource is not found. */
    public static final int UCE_NOT_FOUND = 11;
    /**  Service is not available. */
    public static final int UCE_SERVICE_UNAVAILABLE = 12;
    /**  No Change in Capabilities */
    public static final int UCE_NO_CHANGE_IN_CAP = 13;
    /**  Service is unknown. */
    public static final int UCE_SERVICE_UNKNOWN = 14;
     /** Service cannot support Invalid Feature Tag   */
    public static final int UCE_INVALID_FEATURE_TAG = 15;
    /** Service is Available   */
    public static final int UCE_SERVICE_AVAILABLE = 16;


    private int mStatusCode = UCE_SUCCESS;

    /**
     * Constructor for the StatusCode class.
     * @hide
     */
    @UnsupportedAppUsage
    public StatusCode() {}

    /**
     *  Gets the status code.
     *  @hide
     */
    @UnsupportedAppUsage
    public int getStatusCode() {
        return mStatusCode;
    }

    /**
     *  Sets the status code.
     *  @hide
     */
    @UnsupportedAppUsage
    public void setStatusCode(int nStatusCode) {
        this.mStatusCode = nStatusCode;
    }

    /** @hide */
    public int describeContents() {
        // TODO Auto-generated method stub
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mStatusCode);
    }

    /** @hide */
    public static final Parcelable.Creator<StatusCode> CREATOR =
                                      new Parcelable.Creator<StatusCode>() {

        public StatusCode createFromParcel(Parcel source) {
            // TODO Auto-generated method stub
            return new StatusCode(source);
        }

        public StatusCode[] newArray(int size) {
            // TODO Auto-generated method stub
            return new StatusCode[size];
        }
    };

    /** @hide */
    private StatusCode(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mStatusCode = source.readInt();
    }
}
