/*
 * Copyright 2017 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.LinkProperties;
import android.os.RemoteException;
import android.telephony.data.DataService.DataServiceProvider;

import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Data service callback, which is for bound data service to invoke for solicited and unsolicited
 * response. The caller is responsible to create a callback object for each single asynchronous
 * request.
 *
 * @hide
 */
@SystemApi
public class DataServiceCallback {

    private static final String TAG = DataServiceCallback.class.getSimpleName();

    private static final boolean DBG = true;

    /**
     * Result of data requests
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RESULT_SUCCESS, RESULT_ERROR_UNSUPPORTED, RESULT_ERROR_INVALID_ARG, RESULT_ERROR_BUSY,
            RESULT_ERROR_ILLEGAL_STATE})
    public @interface ResultCode {}

    /** Request is completed successfully */
    public static final int RESULT_SUCCESS              = 0;
    /** Request is not support */
    public static final int RESULT_ERROR_UNSUPPORTED    = 1;
    /** Request contains invalid arguments */
    public static final int RESULT_ERROR_INVALID_ARG    = 2;
    /** Service is busy */
    public static final int RESULT_ERROR_BUSY           = 3;
    /** Request sent in illegal state */
    public static final int RESULT_ERROR_ILLEGAL_STATE  = 4;
    /**
     * Service is temporarily unavailable. Frameworks should retry the request again.
     * @hide
     */
    public static final int RESULT_ERROR_TEMPORARILY_UNAVAILABLE = 5;

    private final IDataServiceCallback mCallback;

    /** @hide */
    public DataServiceCallback(IDataServiceCallback callback) {
        mCallback = callback;
    }

    /**
     * Called to indicate result for the request {@link DataServiceProvider#setupDataCall(int,
     * DataProfile, boolean, boolean, int, LinkProperties, DataServiceCallback)} .
     *
     * @param result The result code. Must be one of the {@link ResultCode}.
     * @param response Setup data call response.
     */
    public void onSetupDataCallComplete(@ResultCode int result,
            @Nullable DataCallResponse response) {
        if (mCallback != null) {
            try {
                if (DBG) Rlog.d(TAG, "onSetupDataCallComplete");
                mCallback.onSetupDataCallComplete(result, response);
            } catch (RemoteException e) {
                Rlog.e(TAG, "Failed to onSetupDataCallComplete on the remote");
            }
        } else {
            Rlog.e(TAG, "onSetupDataCallComplete: callback is null!");
        }
    }

    /**
     * Called to indicate result for the request {@link DataServiceProvider#deactivateDataCall(int,
     * int, DataServiceCallback)}
     *
     * @param result The result code. Must be one of the {@link ResultCode}.
     */
    public void onDeactivateDataCallComplete(@ResultCode int result) {
        if (mCallback != null) {
            try {
                if (DBG) Rlog.d(TAG, "onDeactivateDataCallComplete");
                mCallback.onDeactivateDataCallComplete(result);
            } catch (RemoteException e) {
                Rlog.e(TAG, "Failed to onDeactivateDataCallComplete on the remote");
            }
        } else {
            Rlog.e(TAG, "onDeactivateDataCallComplete: callback is null!");
        }
    }

    /**
     * Called to indicate result for the request {@link DataServiceProvider#setInitialAttachApn(
     * DataProfile, boolean, DataServiceCallback)}.
     *
     * @param result The result code. Must be one of the {@link ResultCode}.
     */
    public void onSetInitialAttachApnComplete(@ResultCode int result) {
        if (mCallback != null) {
            try {
                mCallback.onSetInitialAttachApnComplete(result);
            } catch (RemoteException e) {
                Rlog.e(TAG, "Failed to onSetInitialAttachApnComplete on the remote");
            }
        } else {
            Rlog.e(TAG, "onSetInitialAttachApnComplete: callback is null!");
        }
    }

    /**
     * Called to indicate result for the request {@link DataServiceProvider#setDataProfile(List,
     * boolean, DataServiceCallback)}.
     *
     * @param result The result code. Must be one of the {@link ResultCode}.
     */
    public void onSetDataProfileComplete(@ResultCode int result) {
        if (mCallback != null) {
            try {
                mCallback.onSetDataProfileComplete(result);
            } catch (RemoteException e) {
                Rlog.e(TAG, "Failed to onSetDataProfileComplete on the remote");
            }
        } else {
            Rlog.e(TAG, "onSetDataProfileComplete: callback is null!");
        }
    }

    /**
     * Called to indicate result for the request {@link DataServiceProvider#requestDataCallList(
     * DataServiceCallback)}.
     *
     * @param result The result code. Must be one of the {@link ResultCode}.
     * @param dataCallList List of the current active data connection. If no data call is presented,
     * set it to an empty list.
     */
    public void onRequestDataCallListComplete(@ResultCode int result,
            @NonNull List<DataCallResponse> dataCallList) {
        if (mCallback != null) {
            try {
                mCallback.onRequestDataCallListComplete(result, dataCallList);
            } catch (RemoteException e) {
                Rlog.e(TAG, "Failed to onRequestDataCallListComplete on the remote");
            }
        } else {
            Rlog.e(TAG, "onRequestDataCallListComplete: callback is null!");
        }
    }

    /**
     * Called to indicate that data connection list changed. If no data call is presented, set it to
     * an empty list.
     *
     * @param dataCallList List of the current active data connection.
     */
    public void onDataCallListChanged(@NonNull List<DataCallResponse> dataCallList) {
        if (mCallback != null) {
            try {
                if (DBG) Rlog.d(TAG, "onDataCallListChanged");
                mCallback.onDataCallListChanged(dataCallList);
            } catch (RemoteException e) {
                Rlog.e(TAG, "Failed to onDataCallListChanged on the remote");
            }
        } else {
            Rlog.e(TAG, "onDataCallListChanged: callback is null!");
        }
    }

    /**
     * Called to indicate result for the request {@link DataService#startHandover}.
     *
     * @param result The result code. Must be one of the {@link ResultCode}
     *
     * @hide
     */
    public void onHandoverStarted(@ResultCode int result) {
        if (mCallback != null) {
            try {
                if (DBG) Rlog.d(TAG, "onHandoverStarted");
                mCallback.onHandoverStarted(result);
            } catch (RemoteException e) {
                Rlog.e(TAG, "Failed to onHandoverStarted on the remote");
            }
        } else {
            Rlog.e(TAG, "onHandoverStarted: callback is null!");
        }
    }

    /**
     * Called to indicate result for the request {@link DataService#cancelHandover}.
     *
     * @param result The result code. Must be one of the {@link ResultCode}
     *
     * @hide
     */
    public void onHandoverCancelled(@ResultCode int result) {
        if (mCallback != null) {
            try {
                if (DBG) Rlog.d(TAG, "onHandoverCancelled");
                mCallback.onHandoverCancelled(result);
            } catch (RemoteException e) {
                Rlog.e(TAG, "Failed to onHandoverCancelled on the remote");
            }
        } else {
            Rlog.e(TAG, "onHandoverCancelled: callback is null!");
        }
    }

    /**
     * Get the result code as a string
     *
     * @param resultCode The result code. Must be one of the {@link ResultCode}
     * @return the string representation
     *
     * @hide
     */
    @NonNull
    public static String resultCodeToString(@DataServiceCallback.ResultCode int resultCode) {
        switch (resultCode) {
            case RESULT_SUCCESS:
                return "RESULT_SUCCESS";
            case RESULT_ERROR_UNSUPPORTED:
                return "RESULT_ERROR_UNSUPPORTED";
            case RESULT_ERROR_INVALID_ARG:
                return "RESULT_ERROR_INVALID_ARG";
            case RESULT_ERROR_BUSY:
                return "RESULT_ERROR_BUSY";
            case RESULT_ERROR_ILLEGAL_STATE:
                return "RESULT_ERROR_ILLEGAL_STATE";
            default:
                return "Missing case for result code=" + resultCode;
        }
    }

    /**
     * Unthrottles the APN on the current transport.  There is no matching "APN throttle" method.
     * Instead, the APN is throttled for the time specified in
     * {@link DataCallResponse#getRetryDurationMillis}.
     * <p/>
     * see: {@link DataCallResponse#getRetryDurationMillis}
     *
     * @param apn Access Point Name defined by the carrier.
     */
    public void onApnUnthrottled(final @NonNull String apn) {
        if (mCallback != null) {
            try {
                if (DBG) Rlog.d(TAG, "onApnUnthrottled");
                mCallback.onApnUnthrottled(apn);
            } catch (RemoteException e) {
                Rlog.e(TAG, "onApnUnthrottled: remote exception", e);
            }
        } else {
            Rlog.e(TAG, "onApnUnthrottled: callback is null!");
        }
    }
}
