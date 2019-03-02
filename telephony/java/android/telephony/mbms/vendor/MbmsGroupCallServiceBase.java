/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.mbms.vendor;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.mbms.GroupCallCallback;
import android.telephony.mbms.IGroupCallCallback;
import android.telephony.mbms.IMbmsGroupCallSessionCallback;
import android.telephony.mbms.MbmsErrors;
import android.telephony.mbms.MbmsGroupCallSessionCallback;
import android.telephony.mbms.vendor.IMbmsGroupCallService.Stub;

import java.util.List;

/**
 * Base class for MBMS group-call services. The middleware should override this class to implement
 * its {@link Service} for group calls
 * Subclasses should call this class's {@link #onBind} method to obtain an {@link IBinder} if they
 * override {@link #onBind}.
 * @hide
 */
@SystemApi
@TestApi
public class MbmsGroupCallServiceBase extends Service {
    private final IBinder mInterface = new Stub() {
        @Override
        public int initialize(final IMbmsGroupCallSessionCallback callback,
                final int subscriptionId) throws RemoteException {
            if (callback == null) {
                throw new NullPointerException("Callback must not be null");
            }

            final int uid = Binder.getCallingUid();

            int result = MbmsGroupCallServiceBase.this.initialize(
                    new MbmsGroupCallSessionCallback() {
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
                        public void onAvailableSaisUpdated(final List currentSais,
                                final List availableSais) {
                            try {
                                callback.onAvailableSaisUpdated(currentSais, availableSais);
                            } catch (RemoteException e) {
                                onAppCallbackDied(uid, subscriptionId);
                            }
                        }

                        @Override
                        public void onServiceInterfaceAvailable(final String interfaceName,
                                final int index) {
                            try {
                                callback.onServiceInterfaceAvailable(interfaceName, index);
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

        @Override
        public void stopGroupCall(int subId, long tmgi) {
            MbmsGroupCallServiceBase.this.stopGroupCall(subId, tmgi);
        }

        @Override
        public void updateGroupCall(int subscriptionId, long tmgi, List saiList,
                List frequencyList) {
            MbmsGroupCallServiceBase.this.updateGroupCall(
                    subscriptionId, tmgi, saiList, frequencyList);
        }

        @Override
        public int startGroupCall(final int subscriptionId, final long tmgi,
                final List saiList,
                final List frequencyList, final IGroupCallCallback callback)
                throws RemoteException {
            if (callback == null) {
                throw new NullPointerException("Callback must not be null");
            }

            final int uid = Binder.getCallingUid();

            int result = MbmsGroupCallServiceBase.this.startGroupCall(
                    subscriptionId, tmgi, saiList, frequencyList, new GroupCallCallback() {
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

                        public void onGroupCallStateChanged(int state, int reason) {
                            try {
                                callback.onGroupCallStateChanged(state, reason);
                            } catch (RemoteException e) {
                                onAppCallbackDied(uid, subscriptionId);
                            }
                        }

                        public void onBroadcastSignalStrengthUpdated(int signalStrength) {
                            try {
                                callback.onBroadcastSignalStrengthUpdated(signalStrength);
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

        @Override
        public void dispose(int subId) throws RemoteException {
            MbmsGroupCallServiceBase.this.dispose(subId);
        }
    };

    /**
     * Initialize the group call service for this app and subscription ID, registering the callback.
     *
     * May throw an {@link IllegalArgumentException} or a {@link SecurityException}, which
     * will be intercepted and passed to the app as
     * {@link MbmsErrors.InitializationErrtrors#ERROR_UNABLE_TO_INITIALIZE}
     *
     * May return any value from {@link MbmsErrors.InitializationErrors}
     * or {@link MbmsErrors#SUCCESS}. Non-successful error codes will be passed to the app via
     * {@link IMbmsGroupCallSessionCallback#onError(int, String)}.
     *
     * @param callback The callback to use to communicate with the app.
     * @param subscriptionId The subscription ID to use.
     */
    public int initialize(@NonNull MbmsGroupCallSessionCallback callback, int subscriptionId)
            throws RemoteException {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Starts a particular group call. This method may perform asynchronous work. When
     * the call is ready for consumption, the middleware should inform the app via
     * {@link IGroupCallCallback#onGroupCallStateChanged(int, int)}.
     *
     * May throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     * @param tmgi The TMGI, an identifier for the group call.
     * @param saiList A list of SAIs for the group call.
     * @param frequencyList A list of frequencies for the group call.
     * @param callback The callback object on which the app wishes to receive updates.
     * @return Any error in {@link MbmsErrors.GeneralErrors}
     */
    public int startGroupCall(int subscriptionId, long tmgi, @NonNull List<Integer> saiList,
            @NonNull List<Integer> frequencyList, @NonNull GroupCallCallback callback) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Stop the group call identified by {@code tmgi}.
     *
     * The callback provided via {@link #startGroupCall} should no longer be
     * used after this method has called by the app.
     *
     * May throw an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     * @param tmgi The TMGI for the call to stop.
     */
    public void stopGroupCall(int subscriptionId, long tmgi) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Called when the app receives new SAI and frequency information for the group call identified
     * by {@code tmgi}.
     * @param saiList New list of SAIs that the call is available on.
     * @param frequencyList New list of frequencies that the call is available on.
     */
    public void updateGroupCall(int subscriptionId, long tmgi, @NonNull List<Integer> saiList,
            @NonNull List<Integer> frequencyList) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Signals that the app wishes to dispose of the session identified by the
     * {@code subscriptionId} argument and the caller's uid. No notification back to the
     * app is required for this operation, and the corresponding callback provided via
     * {@link #initialize} should no longer be used
     * after this method has been called by the app.
     *
     * May throw an {@link IllegalStateException}
     *
     * @param subscriptionId The subscription id to use.
     */
    public void dispose(int subscriptionId) throws RemoteException {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Indicates that the app identified by the given UID and subscription ID has died.
     * @param uid the UID of the app, as returned by {@link Binder#getCallingUid()}.
     * @param subscriptionId The subscription ID the app is using.
     */
    public void onAppCallbackDied(int uid, int subscriptionId) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mInterface;
    }
}
