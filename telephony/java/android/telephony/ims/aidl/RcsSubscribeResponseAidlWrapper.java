/*
 * Copyright (c) 2020 The Android Open Source Project
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

package android.telephony.ims.aidl;

import android.net.Uri;
import android.os.RemoteException;
import android.telephony.ims.ImsException;
import android.telephony.ims.RcsContactTerminatedReason;
import android.telephony.ims.stub.RcsCapabilityExchangeImplBase.SubscribeResponseCallback;
import android.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of the callback OptionsResponseCallback by wrapping the internal AIDL from
 * telephony.
 * @hide
 */
public class RcsSubscribeResponseAidlWrapper implements SubscribeResponseCallback {

    private final ISubscribeResponseCallback mResponseBinder;

    public RcsSubscribeResponseAidlWrapper(ISubscribeResponseCallback responseBinder) {
        mResponseBinder = responseBinder;
    }

    @Override
    public void onCommandError(int code) throws ImsException {
        try {
            mResponseBinder.onCommandError(code);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void onNetworkResponse(int code, String reason) throws ImsException {
        try {
            mResponseBinder.onNetworkResponse(code, reason);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void onNetworkResponse(int code, String reasonPhrase, int reasonHeaderCause,
            String reasonHeaderText) throws ImsException {
        try {
            mResponseBinder.onNetworkRespHeader(code, reasonPhrase, reasonHeaderCause,
                    reasonHeaderText);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void onNotifyCapabilitiesUpdate(List<String> pidfXmls) throws ImsException {
        try {
            mResponseBinder.onNotifyCapabilitiesUpdate(pidfXmls);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void onResourceTerminated(List<Pair<Uri, String>> uriTerminatedReason)
            throws ImsException {
        try {
            mResponseBinder.onResourceTerminated(getTerminatedReasonList(uriTerminatedReason));
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    private List<RcsContactTerminatedReason> getTerminatedReasonList(
            List<Pair<Uri, String>> uriTerminatedReason) {
        List<RcsContactTerminatedReason> uriTerminatedReasonList = new ArrayList<>();
        if (uriTerminatedReason != null) {
            for (Pair<Uri, String> pair : uriTerminatedReason) {
                RcsContactTerminatedReason reason =
                        new RcsContactTerminatedReason(pair.first, pair.second);
                uriTerminatedReasonList.add(reason);
            }
        }
        return uriTerminatedReasonList;
    }

    @Override
    public void onTerminated(String reason, long retryAfterMilliseconds) throws ImsException {
        try {
            mResponseBinder.onTerminated(reason, retryAfterMilliseconds);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }
}
