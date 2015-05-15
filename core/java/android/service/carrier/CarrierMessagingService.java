/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.List;

/**
 * A service that receives calls from the system when new SMS and MMS are
 * sent or received.
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_CARRIER_SERVICES} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 * &lt;service android:name=".MyMessagingService"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_CARRIER_SERVICES">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.carrier.CarrierMessagingService" />
 *     &lt;/intent-filter>
 * &lt;/service></pre>
 */
public abstract class CarrierMessagingService extends Service {
    /**
     * The {@link android.content.Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.carrier.CarrierMessagingService";

    /**
     * Indicates that an SMS or MMS message was successfully sent.
     */
    public static final int SEND_STATUS_OK = 0;

    /**
     * SMS/MMS sending failed. We should retry via the carrier network.
     */
    public static final int SEND_STATUS_RETRY_ON_CARRIER_NETWORK = 1;

    /**
     * SMS/MMS sending failed. We should not retry via the carrier network.
     */
    public static final int SEND_STATUS_ERROR = 2;

    /**
     * Successfully downloaded an MMS message.
     */
    public static final int DOWNLOAD_STATUS_OK = 0;

    /**
     * MMS downloading failed. We should retry via the carrier network.
     */
    public static final int DOWNLOAD_STATUS_RETRY_ON_CARRIER_NETWORK = 1;

    /**
     * MMS downloading failed. We should not retry via the carrier network.
     */
    public static final int DOWNLOAD_STATUS_ERROR = 2;

    /**
     * Flag to request SMS delivery status report.
     */
    public static final int SEND_FLAG_REQUEST_DELIVERY_STATUS = 1;

    private final ICarrierMessagingWrapper mWrapper = new ICarrierMessagingWrapper();

    /**
     * Override this method to filter inbound SMS messages.
     *
     * @param pdu the PDUs of the message
     * @param format the format of the PDUs, typically "3gpp" or "3gpp2"
     * @param destPort the destination port of a binary SMS, this will be -1 for text SMS
     * @param subId SMS subscription ID of the SIM
     * @param callback result callback. Call with {@code true} to keep an inbound SMS message and
     *        deliver to SMS apps, and {@code false} to drop the message.
     */
    public void onFilterSms(@NonNull MessagePdu pdu, @NonNull String format, int destPort,
            int subId, @NonNull ResultCallback<Boolean> callback) {
        // optional
        try {
            callback.onReceiveResult(true);
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to intercept text SMSs sent from the device.
     * @deprecated Override {@link #onSendTextSms} below instead.
     *
     * @param text the text to send
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param callback result callback. Call with a {@link SendSmsResult}.
     */
    @Deprecated
    public void onSendTextSms(
            @NonNull String text, int subId, @NonNull String destAddress,
            @NonNull ResultCallback<SendSmsResult> callback) {
        // optional
        try {
            callback.onReceiveResult(new SendSmsResult(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, 0));
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to intercept text SMSs sent from the device.
     *
     * @param text the text to send
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param sendSmsFlag Flag for sending SMS. Acceptable values are 0 and
     *        {@link #SEND_FLAG_REQUEST_DELIVERY_STATUS}.
     * @param callback result callback. Call with a {@link SendSmsResult}.
     */
    public void onSendTextSms(
            @NonNull String text, int subId, @NonNull String destAddress,
            int sendSmsFlag, @NonNull ResultCallback<SendSmsResult> callback) {
        // optional
        onSendTextSms(text, subId, destAddress, callback);
    }

    /**
     * Override this method to intercept binary SMSs sent from the device.
     * @deprecated Override {@link #onSendDataSms} below instead.
     *
     * @param data the binary content
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param destPort the destination port
     * @param callback result callback. Call with a {@link SendSmsResult}.
     */
    @Deprecated
    public void onSendDataSms(@NonNull byte[] data, int subId,
            @NonNull String destAddress, int destPort,
            @NonNull ResultCallback<SendSmsResult> callback) {
        // optional
        try {
            callback.onReceiveResult(new SendSmsResult(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, 0));
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to intercept binary SMSs sent from the device.
     *
     * @param data the binary content
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param destPort the destination port
     * @param sendSmsFlag Flag for sending SMS. Acceptable values are 0 and
     *        {@link #SEND_FLAG_REQUEST_DELIVERY_STATUS}.
     * @param callback result callback. Call with a {@link SendSmsResult}.
     */
    public void onSendDataSms(@NonNull byte[] data, int subId,
            @NonNull String destAddress, int destPort, int sendSmsFlag,
            @NonNull ResultCallback<SendSmsResult> callback) {
        // optional
        onSendDataSms(data, subId, destAddress, destPort, callback);
    }

    /**
     * Override this method to intercept long SMSs sent from the device.
     * @deprecated Override {@link #onSendMultipartTextSms} below instead.
     *
     * @param parts a {@link List} of the message parts
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param callback result callback. Call with a {@link SendMultipartSmsResult}.
     */
    @Deprecated
    public void onSendMultipartTextSms(@NonNull List<String> parts,
            int subId, @NonNull String destAddress,
            @NonNull ResultCallback<SendMultipartSmsResult> callback) {
        // optional
        try {
            callback.onReceiveResult(
                    new SendMultipartSmsResult(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, null));
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to intercept long SMSs sent from the device.
     *
     * @param parts a {@link List} of the message parts
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param sendSmsFlag Flag for sending SMS. Acceptable values are 0 and
     *        {@link #SEND_FLAG_REQUEST_DELIVERY_STATUS}.
     * @param callback result callback. Call with a {@link SendMultipartSmsResult}.
     */
    public void onSendMultipartTextSms(@NonNull List<String> parts,
            int subId, @NonNull String destAddress, int sendSmsFlag,
            @NonNull ResultCallback<SendMultipartSmsResult> callback) {
        // optional
        onSendMultipartTextSms(parts, subId, destAddress, callback);
    }

    /**
     * Override this method to intercept MMSs sent from the device.
     *
     * @param pduUri the content provider URI of the PDU to send
     * @param subId SMS subscription ID of the SIM
     * @param location the optional URI to send this MMS PDU. If this is {code null},
     *        the PDU should be sent to the default MMSC URL.
     * @param callback result callback. Call with a {@link SendMmsResult}.
     */
    public void onSendMms(@NonNull Uri pduUri, int subId,
            @Nullable Uri location, @NonNull ResultCallback<SendMmsResult> callback) {
        // optional
        try {
            callback.onReceiveResult(new SendMmsResult(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, null));
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to download MMSs received.
     *
     * @param contentUri the content provider URI of the PDU to be downloaded.
     * @param subId SMS subscription ID of the SIM
     * @param location the URI of the message to be downloaded.
     * @param callback result callback. Call with a status code which is one of
     *        {@link #DOWNLOAD_STATUS_OK},
     *        {@link #DOWNLOAD_STATUS_RETRY_ON_CARRIER_NETWORK}, or {@link #DOWNLOAD_STATUS_ERROR}.
     */
    public void onDownloadMms(@NonNull Uri contentUri, int subId, @NonNull Uri location,
            @NonNull ResultCallback<Integer> callback) {
        // optional
        try {
            callback.onReceiveResult(DOWNLOAD_STATUS_RETRY_ON_CARRIER_NETWORK);
        } catch (RemoteException ex) {
        }
    }

    @Override
    public @Nullable IBinder onBind(@NonNull Intent intent) {
        if (!SERVICE_INTERFACE.equals(intent.getAction())) {
            return null;
        }
        return mWrapper;
    }

    /**
     * The result of sending an MMS.
     */
    public static final class SendMmsResult {
        private int mSendStatus;
        private byte[] mSendConfPdu;

        /**
         * Constructs a SendMmsResult with the MMS send result, and the SendConf PDU.
         *
         * @param sendStatus send status, one of {@link #SEND_STATUS_OK},
         *        {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and
         *        {@link #SEND_STATUS_ERROR}
         * @param sendConfPdu a possibly {code null} SendConf PDU, which confirms that the message
         *        was sent. sendConfPdu is ignored if the {@code result} is not
         *        {@link #SEND_STATUS_OK}.
         */
        public SendMmsResult(int sendStatus, @Nullable byte[] sendConfPdu) {
            mSendStatus = sendStatus;
            mSendConfPdu = sendConfPdu;
        }

        /**
         * Returns the send status of the just-sent MMS.
         *
         * @return the send status which is one of {@link #SEND_STATUS_OK},
         *         {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and {@link #SEND_STATUS_ERROR}
         */
        public int getSendStatus() {
            return mSendStatus;
        }

        /**
         * Returns the SendConf PDU, which confirms that the message was sent.
         *
         * @return the SendConf PDU
         */
        public @Nullable byte[] getSendConfPdu() {
            return mSendConfPdu;
        }
    }

    /**
     * The result of sending an SMS.
     */
    public static final class SendSmsResult {
        private final int mSendStatus;
        private final int mMessageRef;

        /**
         * Constructs a SendSmsResult with the send status and message reference for the
         * just-sent SMS.
         *
         * @param sendStatus send status, one of {@link #SEND_STATUS_OK},
         *        {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and {@link #SEND_STATUS_ERROR}.
         * @param messageRef message reference of the just-sent SMS. This field is applicable only
         *        if send status is {@link #SEND_STATUS_OK}.
         */
        public SendSmsResult(int sendStatus, int messageRef) {
            mSendStatus = sendStatus;
            mMessageRef = messageRef;
        }

        /**
         * Returns the message reference of the just-sent SMS.
         *
         * @return the message reference
         */
        public int getMessageRef() {
            return mMessageRef;
        }

        /**
         * Returns the send status of the just-sent SMS.
         *
         * @return the send status
         */
        public int getSendStatus() {
            return mSendStatus;
        }
    }

    /**
     * The result of sending a multipart SMS.
     */
    public static final class SendMultipartSmsResult {
        private final int mSendStatus;
        private final int[] mMessageRefs;

        /**
         * Constructs a SendMultipartSmsResult with the send status and message references for the
         * just-sent multipart SMS.
         *
         * @param sendStatus send status, one of {@link #SEND_STATUS_OK},
         *        {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and {@link #SEND_STATUS_ERROR}.
         * @param messageRefs an array of message references, one for each part of the
         *        multipart SMS. This field is applicable only if send status is
         *        {@link #SEND_STATUS_OK}.
         */
        public SendMultipartSmsResult(int sendStatus, @Nullable int[] messageRefs) {
            mSendStatus = sendStatus;
            mMessageRefs = messageRefs;
        }

        /**
         * Returns the message references of the just-sent multipart SMS.
         *
         * @return the message references, one for each part of the multipart SMS
         */
        public @Nullable int[] getMessageRefs() {
            return mMessageRefs;
        }

        /**
         * Returns the send status of the just-sent SMS.
         *
         * @return the send status
         */
        public int getSendStatus() {
            return mSendStatus;
        }
    }

    /**
     * A callback interface used to provide results asynchronously.
     */
    public interface ResultCallback<T> {
        /**
         * Invoked when the result is available.
         *
         * @param result the result
         */
        public void onReceiveResult(@NonNull T result) throws RemoteException;
    };

    /**
     * A wrapper around ICarrierMessagingService to enable the carrier messaging app to implement
     * methods it cares about in the {@link ICarrierMessagingService} interface.
     */
    private class ICarrierMessagingWrapper extends ICarrierMessagingService.Stub {
        @Override
        public void filterSms(MessagePdu pdu, String format, int destPort,
                              int subId, final ICarrierMessagingCallback callback) {
            onFilterSms(pdu, format, destPort, subId, new ResultCallback<Boolean>() {
                    @Override
                    public void onReceiveResult(final Boolean result) throws RemoteException {
                        callback.onFilterComplete(result);
                    }
                });
        }

        @Override
        public void sendTextSms(String text, int subId, String destAddress,
                int sendSmsFlag, final ICarrierMessagingCallback callback) {
            onSendTextSms(text, subId, destAddress, sendSmsFlag,
                    new ResultCallback<SendSmsResult>() {
                    @Override
                    public void onReceiveResult(final SendSmsResult result) throws RemoteException {
                        callback.onSendSmsComplete(result.getSendStatus(), result.getMessageRef());
                    }
                });
        }

        @Override
        public void sendDataSms(byte[] data, int subId, String destAddress, int destPort,
                int sendSmsFlag, final ICarrierMessagingCallback callback) {
            onSendDataSms(data, subId, destAddress, destPort, sendSmsFlag,
                    new ResultCallback<SendSmsResult>() {
                    @Override
                    public void onReceiveResult(final SendSmsResult result) throws RemoteException {
                        callback.onSendSmsComplete(result.getSendStatus(), result.getMessageRef());
                    }
                });
        }

        @Override
        public void sendMultipartTextSms(List<String> parts, int subId, String destAddress,
                int sendSmsFlag, final ICarrierMessagingCallback callback) {
            onSendMultipartTextSms(parts, subId, destAddress, sendSmsFlag,
                        new ResultCallback<SendMultipartSmsResult>() {
                                @Override
                                public void onReceiveResult(final SendMultipartSmsResult result)
                                        throws RemoteException {
                                    callback.onSendMultipartSmsComplete(
                                            result.getSendStatus(), result.getMessageRefs());
                                }
                            });
        }

        @Override
        public void sendMms(Uri pduUri, int subId, Uri location,
                final ICarrierMessagingCallback callback) {
            onSendMms(pduUri, subId, location, new ResultCallback<SendMmsResult>() {
                    @Override
                    public void onReceiveResult(final SendMmsResult result) throws RemoteException {
                        callback.onSendMmsComplete(result.getSendStatus(), result.getSendConfPdu());
                    }
                });
        }

        @Override
        public void downloadMms(Uri pduUri, int subId, Uri location,
                final ICarrierMessagingCallback callback) {
            onDownloadMms(pduUri, subId, location, new ResultCallback<Integer>() {
                    @Override
                    public void onReceiveResult(Integer result) throws RemoteException {
                        callback.onDownloadMmsComplete(result);
                    }
                });
        }
    }
}
