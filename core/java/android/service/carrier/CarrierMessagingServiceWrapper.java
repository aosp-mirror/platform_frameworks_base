/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.carrier;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * Provides basic structure for platform to connect to the carrier messaging service.
 * <p>
 * <code>
 * CarrierMessagingServiceWrapper carrierMessagingServiceWrapper =
 *     new CarrierMessagingServiceWrapperImpl();
 * if (carrierMessagingServiceWrapper.bindToCarrierMessagingService(context, carrierPackageName)) {
 *   // wait for onServiceReady callback
 * } else {
 *   // Unable to bind: handle error.
 * }
 * </code>
 * <p> Upon completion {@link #disposeConnection} should be called to unbind the
 * CarrierMessagingService.
 * @hide
 */
public abstract class CarrierMessagingServiceWrapper {
    // Populated by bindToCarrierMessagingService. bindToCarrierMessagingService must complete
    // prior to calling disposeConnection so that mCarrierMessagingServiceConnection is initialized.
    private volatile CarrierMessagingServiceConnection mCarrierMessagingServiceConnection;

    private volatile ICarrierMessagingService mICarrierMessagingService;

    /**
     * Binds to the carrier messaging service under package {@code carrierPackageName}. This method
     * should be called exactly once.
     *
     * @param context the context
     * @param carrierPackageName the carrier package name
     * @return true upon successfully binding to a carrier messaging service, false otherwise
     * @hide
     */
    public boolean bindToCarrierMessagingService(@NonNull Context context,
            @NonNull String carrierPackageName) {
        Preconditions.checkState(mCarrierMessagingServiceConnection == null);

        Intent intent = new Intent(CarrierMessagingService.SERVICE_INTERFACE);
        intent.setPackage(carrierPackageName);
        mCarrierMessagingServiceConnection = new CarrierMessagingServiceConnection();
        return context.bindService(intent, mCarrierMessagingServiceConnection,
                Context.BIND_AUTO_CREATE);
    }

    /**
     * Unbinds the carrier messaging service. This method should be called exactly once.
     *
     * @param context the context
     * @hide
     */
    public void disposeConnection(@NonNull Context context) {
        Preconditions.checkNotNull(mCarrierMessagingServiceConnection);
        context.unbindService(mCarrierMessagingServiceConnection);
        mCarrierMessagingServiceConnection = null;
    }

    /**
     * Implemented by subclasses to use the carrier messaging service once it is ready.
     * @hide
     */
    public abstract void onServiceReady();

    /**
     * Called when connection with service is established.
     *
     * @param carrierMessagingService the carrier messaing service interface
     */
    private void onServiceReady(ICarrierMessagingService carrierMessagingService) {
        mICarrierMessagingService = carrierMessagingService;
        onServiceReady();
    }

    /**
     * Request filtering an incoming SMS message.
     * The service will call callback.onFilterComplete with the filtering result.
     *
     * @param pdu the PDUs of the message
     * @param format the format of the PDUs, typically "3gpp" or "3gpp2"
     * @param destPort the destination port of a data SMS. It will be -1 for text SMS
     * @param subId SMS subscription ID of the SIM
     * @param callback the callback to notify upon completion
     * @hide
     */
    public void filterSms(@NonNull MessagePdu pdu, @NonNull String format, int destPort,
            int subId, @NonNull final CarrierMessagingCallbackWrapper callback) {
        if (mICarrierMessagingService != null) {
            try {
                mICarrierMessagingService.filterSms(pdu, format, destPort, subId,
                        new CarrierMessagingCallbackWrapperInternal(callback));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Request sending a new text SMS from the device.
     * The service will call {@link ICarrierMessagingCallback#onSendSmsComplete} with the send
     * status.
     *
     * @param text the text to send
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param sendSmsFlag flag for sending SMS
     * @param callback the callback to notify upon completion
     * @hide
     */
    public void sendTextSms(@NonNull String text, int subId, @NonNull String destAddress,
            int sendSmsFlag, @NonNull final CarrierMessagingCallbackWrapper callback) {
        if (mICarrierMessagingService != null) {
            try {
                mICarrierMessagingService.sendTextSms(text, subId, destAddress, sendSmsFlag,
                        new CarrierMessagingCallbackWrapperInternal(callback));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Request sending a new data SMS from the device.
     * The service will call {@link ICarrierMessagingCallback#onSendSmsComplete} with the send
     * status.
     *
     * @param data the data to send
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param destPort port number of the recipient of the message
     * @param sendSmsFlag flag for sending SMS
     * @param callback the callback to notify upon completion
     * @hide
     */
    public void sendDataSms(@NonNull byte[] data, int subId, @NonNull String destAddress,
            int destPort, int sendSmsFlag,
            @NonNull final CarrierMessagingCallbackWrapper callback) {
        if (mICarrierMessagingService != null) {
            try {
                mICarrierMessagingService.sendDataSms(data, subId, destAddress, destPort,
                        sendSmsFlag, new CarrierMessagingCallbackWrapperInternal(callback));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Request sending a new multi-part text SMS from the device.
     * The service will call {@link ICarrierMessagingCallback#onSendMultipartSmsComplete}
     * with the send status.
     *
     * @param parts the parts of the multi-part text SMS to send
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param sendSmsFlag flag for sending SMS
     * @param callback the callback to notify upon completion
     * @hide
     */
    public void sendMultipartTextSms(@NonNull List<String> parts, int subId,
            @NonNull String destAddress, int sendSmsFlag,
            @NonNull final CarrierMessagingCallbackWrapper callback) {
        if (mICarrierMessagingService != null) {
            try {
                mICarrierMessagingService.sendMultipartTextSms(parts, subId, destAddress,
                        sendSmsFlag, new CarrierMessagingCallbackWrapperInternal(callback));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Request sending a new MMS PDU from the device.
     * The service will call {@link ICarrierMessagingCallback#onSendMmsComplete} with the send
     * status.
     *
     * @param pduUri the content provider URI of the PDU to send
     * @param subId SMS subscription ID of the SIM
     * @param location the optional URI to send this MMS PDU. If this is {code null},
     *        the PDU should be sent to the default MMSC URL.
     * @param callback the callback to notify upon completion
     * @hide
     */
    public void sendMms(@NonNull Uri pduUri, int subId, @NonNull Uri location,
            @NonNull final CarrierMessagingCallbackWrapper callback) {
        if (mICarrierMessagingService != null) {
            try {
                mICarrierMessagingService.sendMms(pduUri, subId, location,
                        new CarrierMessagingCallbackWrapperInternal(callback));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Request downloading a new MMS.
     * The service will call {@link ICarrierMessagingCallback#onDownloadMmsComplete} with the
     * download status.
     *
     * @param pduUri the content provider URI of the PDU to be downloaded.
     * @param subId SMS subscription ID of the SIM
     * @param location the URI of the message to be downloaded.
     * @param callback the callback to notify upon completion
     * @hide
     */
    public void downloadMms(@NonNull Uri pduUri, int subId, @NonNull Uri location,
            @NonNull final CarrierMessagingCallbackWrapper callback) {
        if (mICarrierMessagingService != null) {
            try {
                mICarrierMessagingService.downloadMms(pduUri, subId, location,
                        new CarrierMessagingCallbackWrapperInternal(callback));
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * A basic {@link ServiceConnection}.
     */
    private final class CarrierMessagingServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            onServiceReady(ICarrierMessagingService.Stub.asInterface(service));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }

    /**
     * Callback wrapper used for response to requests exposed by
     * {@link CarrierMessagingServiceWrapper}.
     * @hide
     */
    public abstract static class CarrierMessagingCallbackWrapper {

        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#filterSms}.
         * @param result a bitmask integer to indicate how the incoming text SMS should be handled
         *               by the platform. Bits set can be
         *               {@link CarrierMessagingService#RECEIVE_OPTIONS_DROP} and
         *               {@link CarrierMessagingService#
         *               RECEIVE_OPTIONS_SKIP_NOTIFY_WHEN_CREDENTIAL_PROTECTED_STORAGE_UNAVAILABLE}.
         *               {@see CarrierMessagingService#onReceiveTextSms}.
         * @hide
         */
        public void onFilterComplete(int result) {

        }

        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#sendTextSms} and
         * {@link CarrierMessagingServiceWrapper#sendDataSms}.
         * @param result send status, one of {@link CarrierMessagingService#SEND_STATUS_OK},
         *               {@link CarrierMessagingService#SEND_STATUS_RETRY_ON_CARRIER_NETWORK},
         *               and {@link CarrierMessagingService#SEND_STATUS_ERROR}.
         * @param messageRef message reference of the just-sent message. This field is applicable
         *                   only if result is {@link CarrierMessagingService#SEND_STATUS_OK}.
         * @hide
         */
        public void onSendSmsComplete(int result, int messageRef) {

        }

        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#sendMultipartTextSms}.
         * @param result send status, one of {@link CarrierMessagingService#SEND_STATUS_OK},
         *               {@link CarrierMessagingService#SEND_STATUS_RETRY_ON_CARRIER_NETWORK},
         *               and {@link CarrierMessagingService#SEND_STATUS_ERROR}.
         * @param messageRefs an array of message references, one for each part of the
         *                    multipart SMS. This field is applicable only if result is
         *                    {@link CarrierMessagingService#SEND_STATUS_OK}.
         * @hide
         */
        public void onSendMultipartSmsComplete(int result, @Nullable int[] messageRefs) {

        }

        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#sendMms}.
         * @param result send status, one of {@link CarrierMessagingService#SEND_STATUS_OK},
         *               {@link CarrierMessagingService#SEND_STATUS_RETRY_ON_CARRIER_NETWORK},
         *               and {@link CarrierMessagingService#SEND_STATUS_ERROR}.
         * @param sendConfPdu a possibly {code null} SendConf PDU, which confirms that the message
         *                    was sent. sendConfPdu is ignored if the {@code result} is not
         *                    {@link CarrierMessagingService#SEND_STATUS_OK}.
         * @hide
         */
        public void onSendMmsComplete(int result, @Nullable byte[] sendConfPdu) {

        }

        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#downloadMms}.
         * @param result download status, one of {@link CarrierMessagingService#SEND_STATUS_OK},
         *               {@link CarrierMessagingService#SEND_STATUS_RETRY_ON_CARRIER_NETWORK},
         *               and {@link CarrierMessagingService#SEND_STATUS_ERROR}.
         * @hide
         */
        public void onDownloadMmsComplete(int result) {

        }
    }

    private final class CarrierMessagingCallbackWrapperInternal
            extends ICarrierMessagingCallback.Stub {
        CarrierMessagingCallbackWrapper mCarrierMessagingCallbackWrapper;

        CarrierMessagingCallbackWrapperInternal(CarrierMessagingCallbackWrapper callback) {
            mCarrierMessagingCallbackWrapper = callback;
        }

        @Override
        public void onFilterComplete(int result) throws RemoteException {
            mCarrierMessagingCallbackWrapper.onFilterComplete(result);
        }

        @Override
        public void onSendSmsComplete(int result, int messageRef) throws RemoteException {
            mCarrierMessagingCallbackWrapper.onSendSmsComplete(result, messageRef);
        }

        @Override
        public void onSendMultipartSmsComplete(int result, int[] messageRefs)
                throws RemoteException {
            mCarrierMessagingCallbackWrapper.onSendMultipartSmsComplete(result, messageRefs);
        }

        @Override
        public void onSendMmsComplete(int result, byte[] sendConfPdu) throws RemoteException {
            mCarrierMessagingCallbackWrapper.onSendMmsComplete(result, sendConfPdu);
        }

        @Override
        public void onDownloadMmsComplete(int result) throws RemoteException {
            mCarrierMessagingCallbackWrapper.onDownloadMmsComplete(result);
        }
    }
}
