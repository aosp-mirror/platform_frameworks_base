/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.telephony.satellite.stub;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.util.TelephonyUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Base implementation of satellite service.
 * Any service wishing to provide satellite services should extend this class and implement all
 * methods that the service supports.
 * @hide
 */
public class SatelliteImplBase extends SatelliteService {
    private static final String TAG = "SatelliteImplBase";

    /** @hide */
    @IntDef(prefix = "NT_RADIO_TECHNOLOGY_", value = {
            NT_RADIO_TECHNOLOGY_NB_IOT_NTN,
            NT_RADIO_TECHNOLOGY_NR_NTN,
            NT_RADIO_TECHNOLOGY_EMTC_NTN,
            NT_RADIO_TECHNOLOGY_PROPRIETARY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NTRadioTechnology {}

    /** 3GPP NB-IoT (Narrowband Internet of Things) over Non-Terrestrial-Networks technology. */
    public static final int NT_RADIO_TECHNOLOGY_NB_IOT_NTN =
            android.telephony.satellite.stub.NTRadioTechnology.NB_IOT_NTN;
    /** 3GPP 5G NR over Non-Terrestrial-Networks technology. */
    public static final int NT_RADIO_TECHNOLOGY_NR_NTN =
            android.telephony.satellite.stub.NTRadioTechnology.NR_NTN;
    /** 3GPP eMTC (enhanced Machine-Type Communication) over Non-Terrestrial-Networks technology. */
    public static final int NT_RADIO_TECHNOLOGY_EMTC_NTN =
            android.telephony.satellite.stub.NTRadioTechnology.EMTC_NTN;
    /** Proprietary technology. */
    public static final int NT_RADIO_TECHNOLOGY_PROPRIETARY =
            android.telephony.satellite.stub.NTRadioTechnology.PROPRIETARY;

    /** @hide */
    @IntDef(prefix = "SATELLITE_MODEM_STATE_", value = {
            SATELLITE_MODEM_STATE_IDLE,
            SATELLITE_MODEM_STATE_LISTENING,
            SATELLITE_MODEM_STATE_MESSAGE_TRANSFERRING,
            SATELLITE_MODEM_STATE_OFF
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteModemState {}

    /** Satellite modem is in idle state. */
    public static final int SATELLITE_MODEM_STATE_IDLE =
            android.telephony.satellite.stub.SatelliteModemState.SATELLITE_MODEM_STATE_IDLE;
    /** Satellite modem is listening for incoming messages. */
    public static final int SATELLITE_MODEM_STATE_LISTENING =
            android.telephony.satellite.stub.SatelliteModemState.SATELLITE_MODEM_STATE_LISTENING;
    /** Satellite modem is sending and/or receiving messages. */
    public static final int SATELLITE_MODEM_STATE_MESSAGE_TRANSFERRING =
            android.telephony.satellite.stub.SatelliteModemState
                    .SATELLITE_MODEM_STATE_MESSAGE_TRANSFERRING;
    /** Satellite modem is powered off. */
    public static final int SATELLITE_MODEM_STATE_OFF =
            android.telephony.satellite.stub.SatelliteModemState.SATELLITE_MODEM_STATE_OFF;

    protected final Executor mExecutor;

    /**
     * Create SatelliteImplBase using the Executor specified for methods being called from the
     * framework.
     * @param executor The executor for the framework to use when executing the methods overridden
     * by the implementation of Satellite.
     * @hide
     */
    public SatelliteImplBase(@NonNull Executor executor) {
        super();
        mExecutor = executor;
    }

    /**
     * @return The binder for the Satellite implementation.
     * @hide
     */
    public final IBinder getBinder() {
        return mBinder;
    }

    private final IBinder mBinder = new ISatellite.Stub() {
        @Override
        public void setSatelliteListener(ISatelliteListener listener,
                IIntegerConsumer errorCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.setSatelliteListener(listener, errorCallback),
                    "setSatelliteListener");
        }

        @Override
        public void setSatelliteListeningEnabled(boolean enable, IIntegerConsumer errorCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .setSatelliteListeningEnabled(enable, errorCallback),
                    "setSatelliteListeningEnabled");
        }

        @Override
        public void requestSatelliteEnabled(boolean enable, IIntegerConsumer errorCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.requestSatelliteEnabled(enable, errorCallback),
                    "requestSatelliteEnabled");
        }

        @Override
        public void requestIsSatelliteEnabled(IIntegerConsumer errorCallback,
                IBooleanConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestIsSatelliteEnabled(errorCallback, callback),
                    "requestIsSatelliteEnabled");
        }

        @Override
        public void requestIsSatelliteSupported(IIntegerConsumer errorCallback,
                IBooleanConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestIsSatelliteSupported(errorCallback, callback),
                    "requestIsSatelliteSupported");
        }

        @Override
        public void requestSatelliteCapabilities(IIntegerConsumer errorCallback,
                ISatelliteCapabilitiesConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestSatelliteCapabilities(errorCallback, callback),
                    "requestSatelliteCapabilities");
        }

        @Override
        public void startSendingSatellitePointingInfo(IIntegerConsumer errorCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.startSendingSatellitePointingInfo(errorCallback),
                    "startSendingSatellitePointingInfo");
        }

        @Override
        public void stopSendingSatellitePointingInfo(IIntegerConsumer errorCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.stopSendingSatellitePointingInfo(errorCallback),
                    "stopSendingSatellitePointingInfo");
        }

        @Override
        public void requestMaxCharactersPerMOTextMessage(IIntegerConsumer errorCallback,
                IIntegerConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestMaxCharactersPerMOTextMessage(errorCallback, callback),
                    "requestMaxCharactersPerMOTextMessage");
        }

        @Override
        public void provisionSatelliteService(String token, IIntegerConsumer errorCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.provisionSatelliteService(token, errorCallback),
                    "provisionSatelliteService");
        }

        @Override
        public void deprovisionSatelliteService(String token, IIntegerConsumer errorCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.deprovisionSatelliteService(token, errorCallback),
                    "deprovisionSatelliteService");
        }

        @Override
        public void requestIsSatelliteProvisioned(IIntegerConsumer errorCallback,
                IBooleanConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestIsSatelliteProvisioned(errorCallback, callback),
                    "requestIsSatelliteProvisioned");
        }

        @Override
        public void pollPendingSatelliteDatagrams(IIntegerConsumer errorCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.pollPendingSatelliteDatagrams(errorCallback),
                    "pollPendingSatelliteDatagrams");
        }

        @Override
        public void sendSatelliteDatagram(SatelliteDatagram datagram, boolean isEmergency,
                IIntegerConsumer errorCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .sendSatelliteDatagram(datagram, isEmergency, errorCallback),
                    "sendSatelliteDatagram");
        }

        @Override
        public void requestSatelliteModemState(IIntegerConsumer errorCallback,
                IIntegerConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestSatelliteModemState(errorCallback, callback),
                    "requestSatelliteModemState");
        }

        @Override
        public void requestIsSatelliteCommunicationAllowedForCurrentLocation(
                IIntegerConsumer errorCallback, IBooleanConsumer callback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestIsSatelliteCommunicationAllowedForCurrentLocation(
                                    errorCallback, callback),
                    "requestIsSatelliteCommunicationAllowedForCurrentLocation");
        }

        @Override
        public void requestTimeForNextSatelliteVisibility(IIntegerConsumer errorCallback,
                IIntegerConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestTimeForNextSatelliteVisibility(errorCallback, callback),
                    "requestTimeForNextSatelliteVisibility");
        }

        // Call the methods with a clean calling identity on the executor and wait indefinitely for
        // the future to return.
        private void executeMethodAsync(Runnable r, String errorLogName) throws RemoteException {
            try {
                CompletableFuture.runAsync(
                        () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor).join();
            } catch (CancellationException | CompletionException e) {
                Log.w(TAG, "SatelliteImplBase Binder - " + errorLogName + " exception: "
                        + e.getMessage());
                throw new RemoteException(e.getMessage());
            }
        }
    };

    /**
     * Register the callback interface with satellite service.
     *
     * @param listener The callback interface to handle satellite service indications.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void setSatelliteListener(@NonNull ISatelliteListener listener,
            @NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Enable or disable the satellite service listening mode.
     * Listening mode allows the satellite service to listen for incoming pages.
     *
     * @param enable True to enable satellite listening mode and false to disable.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void setSatelliteListeningEnabled(boolean enable,
            @NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Request to enable or disable the satellite modem. If the satellite modem is enabled,
     * this will also disable the cellular modem, and if the satellite modem is disabled,
     * this will also re-enable the cellular modem.
     *
     * @param enable True to enable the satellite modem and false to disable.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void requestSatelliteEnabled(boolean enable, @NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 whether the satellite modem is enabled.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void requestIsSatelliteEnabled(@NonNull IIntegerConsumer errorCallback,
            @NonNull IBooleanConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 whether the satellite service is supported on the device.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void requestIsSatelliteSupported(@NonNull IIntegerConsumer errorCallback,
            @NonNull IBooleanConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get the SatelliteCapabilities of the satellite service.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 the SatelliteCapabilities of the satellite service.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void requestSatelliteCapabilities(@NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteCapabilitiesConsumer callback) {
        // stub implementation
    }

    /**
     * User started pointing to the satellite.
     * The satellite service should report the satellite pointing info via
     * ISatelliteListener#onSatellitePointingInfoChanged as the user device/satellite moves.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void startSendingSatellitePointingInfo(@NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * User stopped pointing to the satellite.
     * The satellite service should stop reporting satellite pointing info to the framework.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void stopSendingSatellitePointingInfo(@NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Request to get the maximum number of characters per MO text message on satellite.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 the maximum number of characters per MO text message on satellite.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void requestMaxCharactersPerMOTextMessage(@NonNull IIntegerConsumer errorCallback,
            @NonNull IIntegerConsumer callback) {
        // stub implementation
    }

    /**
     * Provision the device with a satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once provisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report true.
     *
     * @param token The token to be used as a unique identifier for provisioning with satellite
     *              gateway.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:NETWORK_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     *   SatelliteError:REQUEST_ABORTED
     *   SatelliteError:NETWORK_TIMEOUT
     */
    public void provisionSatelliteService(@NonNull String token,
            @NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Deprovision the device with the satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once deprovisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report false.
     *
     * @param token The token of the device/subscription to be deprovisioned.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:NETWORK_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     *   SatelliteError:REQUEST_ABORTED
     *   SatelliteError:NETWORK_TIMEOUT
     */
    public void deprovisionSatelliteService(@NonNull String token,
            @NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Request to get whether this device is provisioned with a satellite provider.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 whether this device is provisioned with a satellite provider.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void requestIsSatelliteProvisioned(@NonNull IIntegerConsumer errorCallback,
            @NonNull IBooleanConsumer callback) {
        // stub implementation
    }

    /**
     * Poll the pending datagrams.
     * The satellite service should report the new datagrams via ISatelliteListener#onNewDatagrams.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:NETWORK_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     *   SatelliteError:SATELLITE_ACCESS_BARRED
     *   SatelliteError:NETWORK_TIMEOUT
     *   SatelliteError:SATELLITE_NOT_REACHABLE
     *   SatelliteError:NOT_AUTHORIZED
     */
    public void pollPendingSatelliteDatagrams(@NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Send datagram over satellite.
     * Once sent, the satellite service should report whether the operation was successful via
     * SatelliteListener#onDatagramsDelivered.
     *
     * @param datagram Datagram to send in byte format.
     * @param isEmergency Whether this is an emergency datagram.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:NETWORK_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     *   SatelliteError:REQUEST_ABORTED
     *   SatelliteError:SATELLITE_ACCESS_BARRED
     *   SatelliteError:NETWORK_TIMEOUT
     *   SatelliteError:SATELLITE_NOT_REACHABLE
     *   SatelliteError:NOT_AUTHORIZED
     */
    public void sendSatelliteDatagram(@NonNull SatelliteDatagram datagram, boolean isEmergency,
            @NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Request the current satellite modem state.
     * The satellite service should report the current satellite modem state via
     * ISatelliteListener#onSatelliteModemStateChanged.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 the current satellite modem state.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void requestSatelliteModemState(@NonNull IIntegerConsumer errorCallback,
            @NonNull IIntegerConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 whether satellite communication is allowed for the current location.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void requestIsSatelliteCommunicationAllowedForCurrentLocation(
            @NonNull IIntegerConsumer errorCallback, @NonNull IBooleanConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get the time after which the satellite will be visible. This is an int
     * representing the duration in seconds after which the satellite will be visible.
     * This will return 0 if the satellite is currently visible.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 the time after which the satellite will be visible.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    public void requestTimeForNextSatelliteVisibility(@NonNull IIntegerConsumer errorCallback,
            @NonNull IIntegerConsumer callback) {
        // stub implementation
    }
}
