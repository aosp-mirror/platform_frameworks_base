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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.SmsMessage;

import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

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
 * <p> Upon completion {@link #disconnect} should be called to unbind the
 * CarrierMessagingService.
 * @hide
 */
@SystemApi
public final class CarrierMessagingServiceWrapper implements AutoCloseable {
    // Populated by bindToCarrierMessagingService. bindToCarrierMessagingService must complete
    // prior to calling disposeConnection so that mCarrierMessagingServiceConnection is initialized.
    private volatile CarrierMessagingServiceConnection mCarrierMessagingServiceConnection;

    private volatile ICarrierMessagingService mICarrierMessagingService;
    private Runnable mOnServiceReadyCallback;
    private Executor mServiceReadyCallbackExecutor;
    private Context mContext;

    /**
     * Binds to the carrier messaging service under package {@code carrierPackageName}. This method
     * should be called exactly once.
     *
     * @param context the context
     * @param carrierPackageName the carrier package name
     * @param executor the executor to run the callback.
     * @param onServiceReadyCallback the callback when service becomes ready.
     * @return true upon successfully binding to a carrier messaging service, false otherwise
     * @hide
     */
    @SystemApi
    public boolean bindToCarrierMessagingService(@NonNull Context context,
            @NonNull String carrierPackageName,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Runnable onServiceReadyCallback) {
        Preconditions.checkState(mCarrierMessagingServiceConnection == null);
        Objects.requireNonNull(context);
        Objects.requireNonNull(carrierPackageName);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(onServiceReadyCallback);

        Intent intent = new Intent(CarrierMessagingService.SERVICE_INTERFACE);
        intent.setPackage(carrierPackageName);
        mCarrierMessagingServiceConnection = new CarrierMessagingServiceConnection();
        mOnServiceReadyCallback = onServiceReadyCallback;
        mServiceReadyCallbackExecutor = executor;
        mContext = context;
        return context.bindService(intent, mCarrierMessagingServiceConnection,
                Context.BIND_AUTO_CREATE);
    }

    /**
     * Unbinds the carrier messaging service. This method should be called exactly once.
     *
     * @hide
     */
    @SystemApi
    public void disconnect() {
        Preconditions.checkNotNull(mCarrierMessagingServiceConnection);
        mContext.unbindService(mCarrierMessagingServiceConnection);
        mCarrierMessagingServiceConnection = null;
        mOnServiceReadyCallback = null;
        mServiceReadyCallbackExecutor = null;
    }

    /**
     * Called when connection with service is established.
     *
     * @param carrierMessagingService the carrier messaing service interface
     */
    private void onServiceReady(ICarrierMessagingService carrierMessagingService) {
        mICarrierMessagingService = carrierMessagingService;
        if (mOnServiceReadyCallback != null && mServiceReadyCallbackExecutor != null) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mServiceReadyCallbackExecutor.execute(mOnServiceReadyCallback);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    /**
     * Request the CarrierMessagingService to process an incoming SMS text or data message.
     * The service will call callback.onFilterComplete with the filtering result.
     *
     * @param pdu the PDUs of the message
     * @param format the format of the PDUs, typically "3gpp" or "3gpp2".
     *               See {@link SmsMessage#FORMAT_3GPP} and {@link SmsMessage#FORMAT_3GPP2} for
     *               more details.
     * @param destPort the destination port of a data SMS. It will be -1 for text SMS
     * @param subId SMS subscription ID of the SIM
     * @param executor the executor to run the callback.
     * @param callback the callback to notify upon completion
     * @hide
     */
    @SystemApi
    public void receiveSms(@NonNull MessagePdu pdu, @NonNull @SmsMessage.Format String format,
            int destPort, int subId, @NonNull @CallbackExecutor final Executor executor,
            @NonNull final CarrierMessagingCallback callback) {
        if (mICarrierMessagingService != null) {
            try {
                mICarrierMessagingService.filterSms(pdu, format, destPort, subId,
                        new CarrierMessagingCallbackInternal(callback, executor));
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
     * @param sendSmsFlag Flag for sending SMS. Acceptable values are 0 and
     *        {@link CarrierMessagingService#SEND_FLAG_REQUEST_DELIVERY_STATUS}.
     * @param executor the executor to run the callback.
     * @param callback the callback to notify upon completion
     * @hide
     */
    @SystemApi
    public void sendTextSms(@NonNull String text, int subId, @NonNull String destAddress,
            @CarrierMessagingService.SendRequest int sendSmsFlag,
            @NonNull @CallbackExecutor final Executor executor,
            @NonNull final CarrierMessagingCallback callback) {
        Objects.requireNonNull(mICarrierMessagingService);
        try {
            mICarrierMessagingService.sendTextSms(text, subId, destAddress, sendSmsFlag,
                    new CarrierMessagingCallbackInternal(callback, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
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
     * @param sendSmsFlag Flag for sending SMS. Acceptable values are 0 and
     *        {@link CarrierMessagingService#SEND_FLAG_REQUEST_DELIVERY_STATUS}.
     * @param executor the executor to run the callback.
     * @param callback the callback to notify upon completion
     * @hide
     */
    @SystemApi
    public void sendDataSms(@NonNull byte[] data, int subId, @NonNull String destAddress,
            int destPort, @CarrierMessagingService.SendRequest int sendSmsFlag,
            @NonNull @CallbackExecutor final Executor executor,
            @NonNull final CarrierMessagingCallback callback) {
        Objects.requireNonNull(mICarrierMessagingService);
        try {
            mICarrierMessagingService.sendDataSms(data, subId, destAddress, destPort,
                    sendSmsFlag, new CarrierMessagingCallbackInternal(
                            callback, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
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
     * @param sendSmsFlag Flag for sending SMS. Acceptable values are 0 and
     *        {@link CarrierMessagingService#SEND_FLAG_REQUEST_DELIVERY_STATUS}.
     * @param executor the executor to run the callback.
     * @param callback the callback to notify upon completion
     * @hide
     */
    @SystemApi
    public void sendMultipartTextSms(@NonNull List<String> parts, int subId,
            @NonNull String destAddress,
            @CarrierMessagingService.SendRequest int sendSmsFlag,
            @NonNull @CallbackExecutor final Executor executor,
            @NonNull final CarrierMessagingCallback callback) {
        Objects.requireNonNull(mICarrierMessagingService);
        try {
            mICarrierMessagingService.sendMultipartTextSms(parts, subId, destAddress,
                    sendSmsFlag, new CarrierMessagingCallbackInternal(callback, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
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
     * @param executor the executor to run the callback.
     * @param callback the callback to notify upon completion
     * @hide
     */
    @SystemApi
    public void sendMms(@NonNull Uri pduUri, int subId, @NonNull Uri location,
            @NonNull @CallbackExecutor final Executor executor,
            @NonNull final CarrierMessagingCallback callback) {
        Objects.requireNonNull(mICarrierMessagingService);
        try {
            mICarrierMessagingService.sendMms(pduUri, subId, location,
                    new CarrierMessagingCallbackInternal(callback, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
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
     * @param executor the executor to run the callback.
     * @param callback the callback to notify upon completion
     * @hide
     */
    @SystemApi
    public void downloadMms(@NonNull Uri pduUri, int subId, @NonNull Uri location,
            @NonNull @CallbackExecutor final Executor executor,
            @NonNull final CarrierMessagingCallback callback) {
        Objects.requireNonNull(mICarrierMessagingService);
        try {
            mICarrierMessagingService.downloadMms(pduUri, subId, location,
                    new CarrierMessagingCallbackInternal(callback, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** @hide */
    @Override
    public void close() {
        disconnect();
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
    @SystemApi
    public interface CarrierMessagingCallback {
        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#receiveSms}.
         * @param result a bitmask integer to indicate how the incoming text SMS should be handled
         *               by the platform. Bits set can be
         *               {@link CarrierMessagingService#RECEIVE_OPTIONS_DROP} and
         *               {@link CarrierMessagingService#
         *               RECEIVE_OPTIONS_SKIP_NOTIFY_WHEN_CREDENTIAL_PROTECTED_STORAGE_UNAVAILABLE}.
         *               {@link CarrierMessagingService#onReceiveTextSms}.
         */
        default void onReceiveSmsComplete(
                @CarrierMessagingService.FilterCompleteResult int result) {

        }

        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#sendTextSms} and
         * {@link CarrierMessagingServiceWrapper#sendDataSms}.
         * @param result send status, one of {@link CarrierMessagingService#SEND_STATUS_OK},
         *               {@link CarrierMessagingService#SEND_STATUS_RETRY_ON_CARRIER_NETWORK},
         *               and {@link CarrierMessagingService#SEND_STATUS_ERROR}.
         * @param messageRef message reference of the just-sent message. This field is applicable
         *                   only if result is {@link CarrierMessagingService#SEND_STATUS_OK}.
         */
        default void onSendSmsComplete(@CarrierMessagingService.SendResult
                int result, int messageRef) {
        }

        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#sendMultipartTextSms}.
         * @param result send status, one of {@link CarrierMessagingService#SEND_STATUS_OK},
         *               {@link CarrierMessagingService#SEND_STATUS_RETRY_ON_CARRIER_NETWORK},
         *               and {@link CarrierMessagingService#SEND_STATUS_ERROR}.
         * @param messageRefs an array of message references, one for each part of the
         *                    multipart SMS. This field is applicable only if result is
         *                    {@link CarrierMessagingService#SEND_STATUS_OK}.
         */
        default void onSendMultipartSmsComplete(@CarrierMessagingService.SendResult
                int result, @Nullable int[] messageRefs) {

        }

        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#sendMms}.
         * @param result send status, one of {@link CarrierMessagingService#SEND_STATUS_OK},
         *               {@link CarrierMessagingService#SEND_STATUS_RETRY_ON_CARRIER_NETWORK},
         *               and {@link CarrierMessagingService#SEND_STATUS_ERROR}.
         * @param sendConfPdu a possibly {code null} SendConf PDU, which confirms that the message
         *                    was sent. sendConfPdu is ignored if the {@code result} is not
         *                    {@link CarrierMessagingService#SEND_STATUS_OK}.
         */
        default void onSendMmsComplete(@CarrierMessagingService.SendResult
                int result, @Nullable byte[] sendConfPdu) {

        }

        /**
         * Response callback for {@link CarrierMessagingServiceWrapper#downloadMms}.
         * @param result download status, one of {@link CarrierMessagingService#DOWNLOAD_STATUS_OK},
         *               {@link CarrierMessagingService#DOWNLOAD_STATUS_RETRY_ON_CARRIER_NETWORK},
         *               and {@link CarrierMessagingService#DOWNLOAD_STATUS_ERROR}.
         */
        default void onDownloadMmsComplete(@CarrierMessagingService.DownloadResult
                int result) {

        }
    }

    private final class CarrierMessagingCallbackInternal
            extends ICarrierMessagingCallback.Stub {
        final CarrierMessagingCallback mCarrierMessagingCallback;
        final Executor mExecutor;

        CarrierMessagingCallbackInternal(CarrierMessagingCallback callback,
                final Executor executor) {
            mCarrierMessagingCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onFilterComplete(int result) throws RemoteException {
            mExecutor.execute(() -> mCarrierMessagingCallback.onReceiveSmsComplete(result));
        }

        @Override
        public void onSendSmsComplete(int result, int messageRef) throws RemoteException {
            mExecutor.execute(() -> mCarrierMessagingCallback.onSendSmsComplete(
                    result, messageRef));
        }

        @Override
        public void onSendMultipartSmsComplete(int result, int[] messageRefs)
                throws RemoteException {
            mExecutor.execute(() -> mCarrierMessagingCallback.onSendMultipartSmsComplete(
                    result, messageRefs));
        }

        @Override
        public void onSendMmsComplete(int result, byte[] sendConfPdu) throws RemoteException {
            mExecutor.execute(() -> mCarrierMessagingCallback.onSendMmsComplete(
                    result, sendConfPdu));
        }

        @Override
        public void onDownloadMmsComplete(int result) throws RemoteException {
            mExecutor.execute(() -> mCarrierMessagingCallback.onDownloadMmsComplete(result));
        }
    }
}
