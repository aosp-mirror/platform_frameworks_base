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
import android.telephony.IBooleanConsumer;
import android.telephony.IIntegerConsumer;
import android.util.Log;

import com.android.internal.telephony.util.TelephonyUtils;

import java.util.List;
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
                IIntegerConsumer resultCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestSatelliteListeningEnabled(enable, timeout, resultCallback),
                    "requestSatelliteListeningEnabled");
        }

        @Override
        public void enableCellularModemWhileSatelliteModeIsOn(boolean enabled,
                IIntegerConsumer resultCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .enableCellularModemWhileSatelliteModeIsOn(enabled, resultCallback),
                    "enableCellularModemWhileSatelliteModeIsOn");
        }

        @Override
        public void requestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
                IIntegerConsumer resultCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestSatelliteEnabled(
                                    enableSatellite, enableDemoMode, resultCallback),
                    "requestSatelliteEnabled");
        }

        @Override
        public void requestIsSatelliteEnabled(IIntegerConsumer resultCallback,
                IBooleanConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestIsSatelliteEnabled(resultCallback, callback),
                    "requestIsSatelliteEnabled");
        }

        @Override
        public void requestIsSatelliteSupported(IIntegerConsumer resultCallback,
                IBooleanConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestIsSatelliteSupported(resultCallback, callback),
                    "requestIsSatelliteSupported");
        }

        @Override
        public void requestSatelliteCapabilities(IIntegerConsumer resultCallback,
                ISatelliteCapabilitiesConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestSatelliteCapabilities(resultCallback, callback),
                    "requestSatelliteCapabilities");
        }

        @Override
        public void startSendingSatellitePointingInfo(IIntegerConsumer resultCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.startSendingSatellitePointingInfo(resultCallback),
                    "startSendingSatellitePointingInfo");
        }

        @Override
        public void stopSendingSatellitePointingInfo(IIntegerConsumer resultCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.stopSendingSatellitePointingInfo(resultCallback),
                    "stopSendingSatellitePointingInfo");
        }

        @Override
        public void provisionSatelliteService(String token, byte[] provisionData,
                IIntegerConsumer resultCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .provisionSatelliteService(token, provisionData, resultCallback),
                    "provisionSatelliteService");
        }

        @Override
        public void deprovisionSatelliteService(String token, IIntegerConsumer resultCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.deprovisionSatelliteService(token, resultCallback),
                    "deprovisionSatelliteService");
        }

        @Override
        public void requestIsSatelliteProvisioned(IIntegerConsumer resultCallback,
                IBooleanConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestIsSatelliteProvisioned(resultCallback, callback),
                    "requestIsSatelliteProvisioned");
        }

        @Override
        public void pollPendingSatelliteDatagrams(IIntegerConsumer resultCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.pollPendingSatelliteDatagrams(resultCallback),
                    "pollPendingSatelliteDatagrams");
        }

        @Override
        public void sendSatelliteDatagram(SatelliteDatagram datagram, boolean isEmergency,
                IIntegerConsumer resultCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .sendSatelliteDatagram(datagram, isEmergency, resultCallback),
                    "sendDatagram");
        }

        @Override
        public void requestSatelliteModemState(IIntegerConsumer resultCallback,
                IIntegerConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestSatelliteModemState(resultCallback, callback),
                    "requestSatelliteModemState");
        }

        @Override
        public void requestIsSatelliteCommunicationAllowedForCurrentLocation(
                IIntegerConsumer resultCallback, IBooleanConsumer callback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestIsSatelliteCommunicationAllowedForCurrentLocation(
                                    resultCallback, callback),
                    "requestIsCommunicationAllowedForCurrentLocation");
        }

        @Override
        public void requestTimeForNextSatelliteVisibility(IIntegerConsumer resultCallback,
                IIntegerConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestTimeForNextSatelliteVisibility(resultCallback, callback),
                    "requestTimeForNextSatelliteVisibility");
        }

        @Override
        public void setSatellitePlmn(int simSlot, List<String> carrierPlmnList,
                List<String> devicePlmnList, IIntegerConsumer resultCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.setSatellitePlmn(
                            simSlot, carrierPlmnList, devicePlmnList, resultCallback),
                    "setSatellitePlmn");
        }

        @Override
        public void setSatelliteEnabledForCarrier(int simSlot, boolean enableSatellite,
                IIntegerConsumer resultCallback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.setSatelliteEnabledForCarrier(
                            simSlot, enableSatellite, resultCallback),
                    "setSatelliteEnabledForCarrier");
        }

        @Override
        public void requestIsSatelliteEnabledForCarrier(int simSlot,
                IIntegerConsumer resultCallback, IBooleanConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this
                            .requestIsSatelliteEnabledForCarrier(simSlot, resultCallback, callback),
                    "requestIsSatelliteEnabledForCarrier");
        }

        @Override
        public void requestSignalStrength(IIntegerConsumer resultCallback,
                INtnSignalStrengthConsumer callback) throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.requestSignalStrength(resultCallback, callback),
                    "requestSignalStrength");
        }

        @Override
        public void startSendingNtnSignalStrength(IIntegerConsumer resultCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.startSendingNtnSignalStrength(resultCallback),
                    "startSendingNtnSignalStrength");
        }

        @Override
        public void stopSendingNtnSignalStrength(IIntegerConsumer resultCallback)
                throws RemoteException {
            executeMethodAsync(
                    () -> SatelliteImplBase.this.stopSendingNtnSignalStrength(resultCallback),
                    "stopSendingNtnSignalStrength");
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
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
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
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void requestSatelliteListeningEnabled(boolean enable, int timeout,
            @NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * Allow cellular modem scanning while satellite mode is on.
     * @param enabled  {@code true} to enable cellular modem while satellite mode is on
     * and {@code false} to disable
     * @param resultCallback The callback to receive the error code result of the operation.
     */
    public void enableCellularModemWhileSatelliteModeIsOn(boolean enabled,
            @NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * Request to enable or disable the satellite modem and demo mode. If the satellite modem is
     * enabled, this may also disable the cellular modem, and if the satellite modem is disabled,
     * this may also re-enable the cellular modem.
     *
     * @param enableSatellite True to enable the satellite modem and false to disable.
     * @param enableDemoMode True to enable demo mode and false to disable.
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void requestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
            @NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive whether the satellite modem is enabled.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void requestIsSatelliteEnabled(@NonNull IIntegerConsumer resultCallback,
            @NonNull IBooleanConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive whether the satellite service is supported on the device.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void requestIsSatelliteSupported(@NonNull IIntegerConsumer resultCallback,
            @NonNull IBooleanConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get the SatelliteCapabilities of the satellite service.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive the SatelliteCapabilities of the satellite service.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void requestSatelliteCapabilities(@NonNull IIntegerConsumer resultCallback,
            @NonNull ISatelliteCapabilitiesConsumer callback) {
        // stub implementation
    }

    /**
     * User started pointing to the satellite.
     * The satellite service should report the satellite pointing info via
     * ISatelliteListener#onSatellitePositionChanged as the user device/satellite moves.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void startSendingSatellitePointingInfo(@NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * User stopped pointing to the satellite.
     * The satellite service should stop reporting satellite pointing info to the framework.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void stopSendingSatellitePointingInfo(@NonNull IIntegerConsumer resultCallback) {
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
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_ABORTED
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_TIMEOUT
     */
    public void provisionSatelliteService(@NonNull String token, @NonNull byte[] provisionData,
            @NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * Deprovision the device with the satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once deprovisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report false.
     *
     * @param token The token of the device/subscription to be deprovisioned.
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_ABORTED
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_TIMEOUT
     */
    public void deprovisionSatelliteService(@NonNull String token,
            @NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * Request to get whether this device is provisioned with a satellite provider.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive whether this device is provisioned with a satellite provider.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void requestIsSatelliteProvisioned(@NonNull IIntegerConsumer resultCallback,
            @NonNull IBooleanConsumer callback) {
        // stub implementation
    }

    /**
     * Poll the pending datagrams to be received over satellite.
     * The satellite service should check if there are any pending datagrams to be received over
     * satellite and report them via ISatelliteListener#onSatelliteDatagramsReceived.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_ACCESS_BARRED
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_TIMEOUT
     *   SatelliteResult:SATELLITE_RESULT_NOT_REACHABLE
     *   SatelliteResult:SATELLITE_RESULT_NOT_AUTHORIZED
     */
    public void pollPendingSatelliteDatagrams(@NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * Send datagram over satellite.
     *
     * @param datagram Datagram to send in byte format.
     * @param isEmergency Whether this is an emergency datagram.
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_ABORTED
     *   SatelliteResult:SATELLITE_RESULT_ACCESS_BARRED
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_TIMEOUT
     *   SatelliteResult:SATELLITE_RESULT_NOT_REACHABLE
     *   SatelliteResult:SATELLITE_RESULT_NOT_AUTHORIZED
     */
    public void sendSatelliteDatagram(@NonNull SatelliteDatagram datagram, boolean isEmergency,
            @NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * Request the current satellite modem state.
     * The satellite service should report the current satellite modem state via
     * ISatelliteListener#onSatelliteModemStateChanged.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive the current satellite modem state.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void requestSatelliteModemState(@NonNull IIntegerConsumer resultCallback,
            @NonNull IIntegerConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive whether satellite communication is allowed for the current location.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void requestIsSatelliteCommunicationAllowedForCurrentLocation(
            @NonNull IIntegerConsumer resultCallback, @NonNull IBooleanConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get the time after which the satellite will be visible. This is an int
     * representing the duration in seconds after which the satellite will be visible.
     * This will return 0 if the satellite is currently visible.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive the time after which the satellite will be visible.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    public void requestTimeForNextSatelliteVisibility(@NonNull IIntegerConsumer resultCallback,
            @NonNull IIntegerConsumer callback) {
        // stub implementation
    }


    /**
     * Set the non-terrestrial PLMN with lower priority than terrestrial networks.
     * MCC/MNC broadcast by the non-terrestrial networks may not be included in OPLMNwACT file on
     * SIM profile. Acquisition of satellite based system is lower priority to terrestrial
     * networks. UE shall make all attempts to acquire terrestrial service prior to camping on
     * satellite LTE service.
     *
     * @param simLogicalSlotIndex Indicates the SIM logical slot index to which this API will be
     * applied. The modem will use this information to determine the relevant carrier.
     * @param resultCallback The callback to receive the error code result of the operation.
     * @param carrierPlmnList The list of roaming PLMN used for connecting to satellite networks
     *                        supported by user subscription.
     * @param allSatellitePlmnList Modem should use the allSatellitePlmnList to identify satellite
     *                             PLMNs that are not supported by the carrier and make sure not to
     *                             attach to them.
     *
     * Valid result codes returned:
     *   SatelliteResult:NONE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:MODEM_ERR
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     */
    public void setSatellitePlmn(@NonNull int simLogicalSlotIndex,
            @NonNull List<String> carrierPlmnList, @NonNull List<String> allSatellitePlmnList,
            @NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * Request to enable or disable carrier supported satellite plmn scan and attach by modem.
     * Refer {@link #setSatellitePlmn} for the details of satellite PLMN scanning process.
     *
     * @param simLogicalSlotIndex Indicates the SIM logical slot index to which this API will be
     * applied. The modem will use this information to determine the relevant carrier.
     * @param satelliteEnabled {@code true} to enable satellite, {@code false} to disable satellite.
     * @param callback {@code true} to enable satellite, {@code false} to disable satellite.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     */
    public void setSatelliteEnabledForCarrier(@NonNull int simLogicalSlotIndex,
            @NonNull boolean satelliteEnabled, @NonNull IIntegerConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get whether the satellite is enabled in the cellular modem associated with a
     * carrier.
     *
     * @param simLogicalSlotIndex Indicates the SIM logical slot index to which this API will be
     * applied. The modem will use this information to determine the relevant carrier.
     * @param resultCallback The callback to receive the error code result of the operation.
     * @param callback {@code true} to satellite enabled, {@code false} to satellite disabled.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     */
    public void requestIsSatelliteEnabledForCarrier(@NonNull int simLogicalSlotIndex,
            @NonNull IIntegerConsumer resultCallback, @NonNull IBooleanConsumer callback) {
        // stub implementation
    }

    /**
     * Request to get the signal strength of the satellite connection.
     *
     * @param resultCallback The {@link SatelliteError} result of the operation.
     * @param callback The callback to handle the NTN signal strength changed event.
     */
    public void requestSignalStrength(@NonNull IIntegerConsumer resultCallback,
            INtnSignalStrengthConsumer callback) {
        // stub implementation
    }

    /**
     * Requests to deliver signal strength changed events through the
     * {@link ISatelliteListener#onNtnSignalStrengthChanged(NtnSignalStrength ntnSignalStrength)}
     * callback.
     *
     * @param resultCallback The {@link SatelliteError} result of the operation.
     */
    public void startSendingNtnSignalStrength(@NonNull IIntegerConsumer resultCallback) {
        // stub implementation
    }

    /**
     * Requests to stop signal strength changed events
     *
     * @param resultCallback The {@link SatelliteError} result of the operation.
     */
    public void stopSendingNtnSignalStrength(@NonNull IIntegerConsumer resultCallback){
        // stub implementation
    }
}
