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

import android.annotation.NonNull;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.util.TelephonyUtils;

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
        public void setSatelliteListener(ISatelliteListener listener) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.setSatelliteListener(listener),
                    "setSatelliteListener");
        }

        @Override
        public void requestSatelliteListeningEnabled(boolean enable, int timeout,
                IIntegerConsumer errorCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestSatelliteListeningEnabled(enable, timeout, errorCallback),
                    "requestSatelliteListeningEnabled");
        }

        @Override
        public void enableCellularModemWhileSatelliteModeIsOn(boolean enabled,
                IIntegerConsumer errorCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .enableCellularModemWhileSatelliteModeIsOn(enabled, errorCallback),
                    "enableCellularModemWhileSatelliteModeIsOn");
        }

        @Override
        public void requestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
                IIntegerConsumer errorCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestSatelliteEnabled(
                                    enableSatellite, enableDemoMode, errorCallback),
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
        public void provisionSatelliteService(String token, byte[] provisionData,
                IIntegerConsumer errorCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .provisionSatelliteService(token, provisionData, errorCallback),
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
    public void setSatelliteListener(@NonNull ISatelliteListener listener) {
        // stub implementation
    }

    /**
     * Request to enable or disable the satellite service listening mode.
     * Listening mode allows the satellite service to listen for incoming pages.
     *
     * @param enable True to enable satellite listening mode and false to disable.
     * @param timeout How long the satellite modem should wait for the next incoming page before
     *                disabling listening mode.
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
    public void requestSatelliteListeningEnabled(boolean enable, int timeout,
            @NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Allow cellular modem scanning while satellite mode is on.
     * @param enabled  {@code true} to enable cellular modem while satellite mode is on
     * and {@code false} to disable
     * @param errorCallback The callback to receive the error code result of the operation.
     */
    public void enableCellularModemWhileSatelliteModeIsOn(boolean enabled,
            @NonNull IIntegerConsumer errorCallback) {
        // stub implementation
    }

    /**
     * Request to enable or disable the satellite modem and demo mode. If the satellite modem is
     * enabled, this may also disable the cellular modem, and if the satellite modem is disabled,
     * this may also re-enable the cellular modem.
     *
     * @param enableSatellite True to enable the satellite modem and false to disable.
     * @param enableDemoMode True to enable demo mode and false to disable.
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
    public void requestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
            @NonNull IIntegerConsumer errorCallback) {
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
     * ISatelliteListener#onSatellitePositionChanged as the user device/satellite moves.
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
     * Provision the device with a satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once provisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report true.
     *
     * @param token The token to be used as a unique identifier for provisioning with satellite
     *              gateway.
     * @param provisionData Data from the provisioning app that can be used by provisioning 
     *                      server
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
    public void provisionSatelliteService(@NonNull String token, @NonNull byte[] provisionData,
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
     * Poll the pending datagrams to be received over satellite.
     * The satellite service should check if there are any pending datagrams to be received over
     * satellite and report them via ISatelliteListener#onSatelliteDatagramsReceived.
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
