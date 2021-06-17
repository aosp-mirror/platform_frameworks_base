/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.telephony.ims.ImsException;
import android.telephony.ims.RcsContactUceCapability;
import android.telephony.ims.stub.CapabilityExchangeEventListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.Set;

/**
 * The ICapabilityExchangeEventListener wrapper class to store the listener which is registered by
 * the framework. This wrapper class also delivers the request to the framework when receive the
 * request from the network.
 * @hide
 */
public class CapabilityExchangeAidlWrapper implements CapabilityExchangeEventListener {

    private static final String LOG_TAG = "CapExchangeListener";

    private final ICapabilityExchangeEventListener mListenerBinder;

    public CapabilityExchangeAidlWrapper(@Nullable ICapabilityExchangeEventListener listener) {
        mListenerBinder = listener;
    }

    /**
     * Receives the request of publishing capabilities from the network and deliver this request
     * to the framework via the registered capability exchange event listener.
     */
    public void onRequestPublishCapabilities(int publishTriggerType) throws ImsException {
        ICapabilityExchangeEventListener listener = mListenerBinder;
        if (listener == null) {
            return;
        }
        try {
            listener.onRequestPublishCapabilities(publishTriggerType);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "request publish capabilities exception: " + e);
            throw new ImsException("Remote is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Receives the unpublish notification and deliver this callback to the framework.
     */
    public void onUnpublish() throws ImsException {
        ICapabilityExchangeEventListener listener = mListenerBinder;
        if (listener == null) {
            return;
        }
        try {
            listener.onUnpublish();
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Unpublish exception: " + e);
            throw new ImsException("Remote is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Receives the callback of the remote capability request from the network and deliver this
     * request to the framework.
     */
    public void onRemoteCapabilityRequest(@NonNull Uri contactUri,
            @NonNull Set<String> remoteCapabilities, @NonNull OptionsRequestCallback callback)
            throws ImsException {
        ICapabilityExchangeEventListener listener = mListenerBinder;
        if (listener == null) {
            return;
        }

        IOptionsRequestCallback internalCallback = new IOptionsRequestCallback.Stub() {
            @Override
            public void respondToCapabilityRequest(RcsContactUceCapability ownCapabilities,
                    boolean isBlocked) {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    callback.onRespondToCapabilityRequest(ownCapabilities, isBlocked);
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }
            @Override
            public void respondToCapabilityRequestWithError(int code, String reason) {
                final long callingIdentity = Binder.clearCallingIdentity();
                try {
                    callback.onRespondToCapabilityRequestWithError(code, reason);
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }
        };

        try {
            listener.onRemoteCapabilityRequest(contactUri, new ArrayList<>(remoteCapabilities),
                    internalCallback);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote capability request exception: " + e);
            throw new ImsException("Remote is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }
}
