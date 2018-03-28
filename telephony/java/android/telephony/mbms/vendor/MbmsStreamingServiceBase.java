/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.mbms.vendor;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.telephony.mbms.IMbmsStreamingSessionCallback;
import android.telephony.mbms.IStreamingServiceCallback;
import android.telephony.mbms.MbmsErrors;
import android.telephony.mbms.MbmsStreamingSessionCallback;
import android.telephony.mbms.StreamingService;
import android.telephony.mbms.StreamingServiceCallback;
import android.telephony.mbms.StreamingServiceInfo;

import java.util.List;

/**
 * Base class for MBMS streaming services. The middleware should return an instance of this
 * object from its {@link android.app.Service#onBind(Intent)} method.
 * @hide
 */
@SystemApi
@TestApi
public class MbmsStreamingServiceBase extends IMbmsStreamingService.Stub {
    /**
     * Initialize streaming service for this app and subId, registering the listener.
     *
     * May throw an {@link IllegalArgumentException} or a {@link SecurityException}, which
     * will be intercepted and passed to the app as
     * {@link MbmsErrors.InitializationErrors#ERROR_UNABLE_TO_INITIALIZE}
     *
     * May return any value from {@link MbmsErrors.InitializationErrors}
     * or {@link MbmsErrors#SUCCESS}. Non-successful error codes will be passed to the app via
     * {@link IMbmsStreamingSessionCallback#onError(int, String)}.
     *
     * @param callback The callback to use to communicate with the app.
     * @param subscriptionId The subscription ID to use.
     */
    public int initialize(MbmsStreamingSessionCallback callback, int subscriptionId)
            throws RemoteException {
        return 0;
    }

    /**
     * Actual AIDL implementation that hides the callback AIDL from the middleware.
     * @hide
     */
    @Override
    public final int initialize(final IMbmsStreamingSessionCallback callback,
            final int subscriptionId) throws RemoteException {
        if (callback == null) {
            throw new NullPointerException("Callback must not be null");
        }

        final int uid = Binder.getCallingUid();

        int result = initialize(new MbmsStreamingSessionCallback() {
            @Override
            public void onError(final int errorCode, final String message) {
                try {
                    if (errorCode == MbmsErrors.UNKNOWN) {
                        throw new IllegalArgumentException(
                                "Middleware cannot send an unknown error.");
                    }
                    callback.onError(errorCode, message);
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }

            @Override
            public void onStreamingServicesUpdated(final List<StreamingServiceInfo> services) {
                try {
                    callback.onStreamingServicesUpdated(services);
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }

            @Override
            public void onMiddlewareReady() {
                try {
                    callback.onMiddlewareReady();
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }
        }, subscriptionId);

        if (result == MbmsErrors.SUCCESS) {
            callback.asBinder().linkToDeath(new DeathRecipient() {
                @Override
                public void binderDied() {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }, 0);
        }

        return result;
    }


    /**
     * Registers serviceClasses of interest with the appName/subId key.
     * Starts async fetching data on streaming services of matching classes to be reported
     * later via {@link IMbmsStreamingSessionCallback#onStreamingServicesUpdated(List)}
     *
     * Note that subsequent calls with the same uid and subId will replace
     * the service class list.
     *
     * May throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     * @param serviceClasses The service classes that the app wishes to get info on. The strings
     *                       may contain arbitrary data as negotiated between the app and the
     *                       carrier.
     * @return {@link MbmsErrors#SUCCESS} or any of the errors in
     * {@link MbmsErrors.GeneralErrors}
     */
    @Override
    public int requestUpdateStreamingServices(int subscriptionId,
            List<String> serviceClasses) throws RemoteException {
        return 0;
    }

    /**
     * Starts streaming on a particular service. This method may perform asynchronous work. When
     * the middleware is ready to send bits to the frontend, it should inform the app via
     * {@link IStreamingServiceCallback#onStreamStateUpdated(int, int)}.
     *
     * May throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     * @param serviceId The ID of the streaming service that the app has requested.
     * @param callback The callback object on which the app wishes to receive updates.
     * @return Any error in {@link MbmsErrors.GeneralErrors}
     */
    public int startStreaming(int subscriptionId, String serviceId,
            StreamingServiceCallback callback) throws RemoteException {
        return 0;
    }

    /**
     * Actual AIDL implementation of startStreaming that hides the callback AIDL from the
     * middleware.
     * @hide
     */
    @Override
    public int startStreaming(final int subscriptionId, String serviceId,
            final IStreamingServiceCallback callback) throws RemoteException {
        if (callback == null) {
            throw new NullPointerException("Callback must not be null");
        }

        final int uid = Binder.getCallingUid();

        int result = startStreaming(subscriptionId, serviceId, new StreamingServiceCallback() {
            @Override
            public void onError(final int errorCode, final String message) {
                try {
                    if (errorCode == MbmsErrors.UNKNOWN) {
                        throw new IllegalArgumentException(
                                "Middleware cannot send an unknown error.");
                    }
                    callback.onError(errorCode, message);
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }

            @Override
            public void onStreamStateUpdated(@StreamingService.StreamingState final int state,
                    @StreamingService.StreamingStateChangeReason final int reason) {
                try {
                    callback.onStreamStateUpdated(state, reason);
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }

            @Override
            public void onMediaDescriptionUpdated() {
                try {
                    callback.onMediaDescriptionUpdated();
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }

            @Override
            public void onBroadcastSignalStrengthUpdated(final int signalStrength) {
                try {
                    callback.onBroadcastSignalStrengthUpdated(signalStrength);
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }

            @Override
            public void onStreamMethodUpdated(final int methodType) {
                try {
                    callback.onStreamMethodUpdated(methodType);
                } catch (RemoteException e) {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }
        });

        if (result == MbmsErrors.SUCCESS) {
            callback.asBinder().linkToDeath(new DeathRecipient() {
                @Override
                public void binderDied() {
                    onAppCallbackDied(uid, subscriptionId);
                }
            }, 0);
        }

        return result;
    }

    /**
     * Retrieves the streaming URI for a particular service. If the middleware is not yet ready to
     * stream the service, this method may return null.
     *
     * May throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     * @param serviceId The ID of the streaming service that the app has requested.
     * @return An opaque {@link Uri} to be passed to a video player that understands the format.
     */
    @Override
    public @Nullable Uri getPlaybackUri(int subscriptionId, String serviceId)
            throws RemoteException {
        return null;
    }

    /**
     * Stop streaming the stream identified by {@code serviceId}. Notification of the resulting
     * stream state change should be reported to the app via
     * {@link IStreamingServiceCallback#onStreamStateUpdated(int, int)}.
     *
     * In addition, the callback provided via
     * {@link #startStreaming(int, String, IStreamingServiceCallback)} should no longer be
     * used after this method has called by the app.
     *
     * May throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     * @param serviceId The ID of the streaming service that the app wishes to stop.
     */
    @Override
    public void stopStreaming(int subscriptionId, String serviceId)
            throws RemoteException {
    }

    /**
     * Signals that the app wishes to dispose of the session identified by the
     * {@code subscriptionId} argument and the caller's uid. No notification back to the
     * app is required for this operation, and the corresponding callback provided via
     * {@link #initialize(IMbmsStreamingSessionCallback, int)} should no longer be used
     * after this method has been called by the app.
     *
     * May throw an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     */
    @Override
    public void dispose(int subscriptionId) throws RemoteException {
    }

    /**
     * Indicates that the app identified by the given UID and subscription ID has died.
     * @param uid the UID of the app, as returned by {@link Binder#getCallingUid()}.
     * @param subscriptionId The subscription ID the app is using.
     */
    public void onAppCallbackDied(int uid, int subscriptionId) {
    }
}
