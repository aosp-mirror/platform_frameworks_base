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

package android.service.carriermessaging;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.List;

/**
 * A service that receives calls from the system when new SMS and MMS are
 * sent or received.
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_CARRIER_MESSAGING_SERVICE} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 * &lt;service android:name=".MyMessagingService"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_CARRIER_MESSAGING_SERVICE">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.carriermessaging.CarrierMessagingService" />
 *     &lt;/intent-filter>
 * &lt;/service></pre>
 */
public abstract class CarrierMessagingService extends Service {
    /**
     * The {@link android.content.Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.carriermessaging.CarrierMessagingService";

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

    private final ICarrierMessagingWrapper mWrapper = new ICarrierMessagingWrapper();

    /**
     * Implement this method to filter SMS messages.
     *
     * @param pdu the PDUs of the message
     * @param format the format of the PDUs, typically "3gpp" or "3gpp2"
     * @param destPort the destination port of a binary SMS, this will be -1 for text SMS
     *
     * @return True to keep an inbound SMS message and delivered to SMS apps. False to
     * drop the message.
     */
    public boolean onFilterSms(@NonNull MessagePdu pdu, @NonNull String format, int destPort) {
        // optional
        return true;
    }

    /**
     * Implement this method to intercept text SMSs sent from the devcie.
     *
     * @param text the text to send
     * @param format the format of the response PDU, typically "3gpp" or "3gpp2"
     * @param destAddress phone number of the recipient of the message
     *
     * @return a possibly {code null} {@link SendSmsResponse}. Upon returning {@code null}, the SMS
     *         is sent using the carrier network.
     */
    public @Nullable SendSmsResponse onSendTextSms(
            @NonNull String text, @NonNull String format, @NonNull String destAddress) {
        // optional
        return null;
    }

    /**
     * Implement this method to intercept binary SMSs sent from the device.
     *
     * @param data the binary content
     * @param format format the format of the response PDU, typically "3gpp" or "3gpp2"
     * @param destAddress phone number of the recipient of the message
     * @param destPort the destination port
     *
     * @return a possibly {code null} {@link SendSmsResponse}. Upon returning {@code null}, the SMS
     *         is sent using the carrier network.
     */
    public @Nullable SendSmsResponse onSendDataSms(@NonNull byte[] data, @NonNull String format,
            @NonNull String destAddress, int destPort) {
        // optional
        return null;
    }

    /**
     * Implement this method to intercept long SMSs sent from the device.
     *
     * @param parts a {@link List} of the message parts
     * @param format format the format of the response PDU, typically "3gpp" or "3gpp2"
     * @param destAddress phone number of the recipient of the message
     *
     * @return a possibly {code null} {@link List} of {@link SendSmsResponse}, one for each message
     *         part. Upon returning {@code null}, the SMS is sent using the carrier network.
     */
    public @Nullable List<SendSmsResponse> onSendMultipartTextSms(@NonNull List<String> parts,
            @NonNull String format, @NonNull String destAddress) {
        // optional
        return null;
    }

    /**
     * Implement this method to intercept MMSs sent from the device.
     *
     * @param pduUri the content provider URI of the PDU to send
     * @param locationUrl the optional URL to send this MMS PDU. If this is not specified,
     *                    the PDU should be sent to the default MMSC URL.
     *
     * @return a possibly {@code null} {@link SendMmsResult}. Upon returning {@code null}, the
     *         MMS is sent using the carrier network.
     */
    public @Nullable SendMmsResult onSendMms(@NonNull Uri pduUri, @Nullable String locationUrl) {
        // optional
        return null;
    }

    /**
     * Implement this method to download MMSs received.
     *
     * @param contentUri the content provider URI of the PDU to be downloaded.
     * @param locationUrl the URL of the message to be downloaded.
     *
     * @return a {@link SendMmsResult}.
     */
    public int onDownloadMms(@NonNull Uri contentUri, @NonNull String locationUrl) {
        // optional
        return DOWNLOAD_STATUS_RETRY_ON_CARRIER_NETWORK;
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
        private int mResult;
        private byte[] mSendConfPdu;

        /**
         * Constructs a SendMmsResult with the MMS send result, and the SenConf PDU.
         *
         * @param result the result which is one of {@link #SEND_STATUS_OK},
         *               {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and
         *               {@link #SEND_STATUS_ERROR}
         * @param sendConfPdu a possibly {code null} SendConf PDU, which confirms that the message
         *        was sent. sendConfPdu is ignored if the {@code result} is not
         *        {@link #SEND_STATUS_OK}
         */
        public SendMmsResult(int result, @Nullable byte[] sendConfPdu) {
            mResult = result;
            mSendConfPdu = sendConfPdu;
        }

        /**
         * Returns the result of sending the MMS.
         *
         * @return the result which is one of {@link #SEND_STATUS_OK},
         *         {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and {@link #SEND_STATUS_ERROR}
         */
        public int getResult() {
            return mResult;
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
     * Object passed in callbacks upon successful completion of
     * {@link ICarrierMessagingService#sendTextSms},
     * {@link ICarrierMessagingService#sendDataSms}, and
     * {@link ICarrierMessagingService#sendMultipartTextSms}.
     * Contains message reference and ackPdu.
     */
    public static final class SendSmsResponse implements Parcelable {
        private int mMessageRef;
        private byte[] mAckPdu;
        private int mErrorCode;

        /**
         * Constructs a SendSmsResponse for the message reference, the ack PDU, and error code for
         * the just-sent SMS.
         *
         * @param messageRef message reference of the just-sent SMS
         * @param ackPdu ackPdu for the just-sent SMS
         * @param errorCode error code. See 3GPP 27.005, 3.2.5 for GSM/UMTS,
         *     3GPP2 N.S0005 (IS-41C) Table 171 for CDMA, -1 if unknown or not applicable.
         */
        public SendSmsResponse(int messageRef, @NonNull byte[] ackPdu, int errorCode) {
            mMessageRef = messageRef;
            mAckPdu = ackPdu;
            mErrorCode = errorCode;
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
         * Returns the ackPdu for the just-sent SMS.
         *
         * @return the ackPdu
         */
        public @NonNull byte[] getAckPdu() {
            return mAckPdu;
        }

        /**
         * Returns the error code upon encountering an error while sending the SMS, -1 if unknown or
         * not applicable.
         *
         * @return errorCode the errorCode as defined in 3GPP 27.005, 3.2.5 for GSM/UMTS, and 3GPP2
         * N.S0005 (IS-41C) Table 171 for CDMA, -1 if unknown or not applicable.
         */
        public int getErrorCode() {
            return mErrorCode;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mMessageRef);
            dest.writeByteArray(mAckPdu);
            dest.writeInt(mErrorCode);
        }

        public static final Parcelable.Creator<SendSmsResponse> CREATOR
                = new Parcelable.Creator<SendSmsResponse>() {
                    @Override
                    public SendSmsResponse createFromParcel(Parcel source) {
                        return new SendSmsResponse(source.readInt(),
                                                   source.createByteArray(),
                                                   source.readInt());
                    }

                    @Override
                    public SendSmsResponse[] newArray(int size) {
                        return new SendSmsResponse[size];
                    }
        };
    }

    /**
     * A wrapper around ICarrierMessagingService to enable the carrier messaging APP to implement
     * methods it cares about in the {@link ICarrierMessagingService} interface.
     */
    private class ICarrierMessagingWrapper extends ICarrierMessagingService.Stub {
        @Override
        public void filterSms(MessagePdu pdu, String format, int destPort,
                              ICarrierMessagingCallback callback) {
            try {
                callback.onFilterComplete(onFilterSms(pdu, format, destPort));
            } catch (RemoteException ex) {
            }
        }

        @Override
        public void sendTextSms(String text, String format, String destAddress,
                                ICarrierMessagingCallback callback) {
            try {
                SendSmsResponse sendSmsResponse = onSendTextSms(text, format, destAddress);
                if (sendSmsResponse == null) {
                    callback.onSendSmsComplete(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, null);
                } else {
                    callback.onSendSmsComplete(SEND_STATUS_OK, sendSmsResponse);
                }
            } catch (RemoteException ex) {
            }
        }

        @Override
        public void sendDataSms(byte[] data, String format, String destAddress, int destPort,
                                ICarrierMessagingCallback callback) {
            try {
                SendSmsResponse sendSmsResponse = onSendDataSms(data, format, destAddress,
                        destPort);
                if (sendSmsResponse == null) {
                    callback.onSendSmsComplete(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, null);
                } else {
                    callback.onSendSmsComplete(SEND_STATUS_OK, sendSmsResponse);
                }
            } catch (RemoteException ex) {
            }
        }

        @Override
        public void sendMultipartTextSms(List<String> parts, String format, String destAddress,
                                         ICarrierMessagingCallback callback) {
            try {
                List<SendSmsResponse> sendSmsResponses =
                        onSendMultipartTextSms(parts, format, destAddress);
                if (sendSmsResponses == null) {
                    callback.onSendMultipartSmsComplete(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, null);
                } else {
                    callback.onSendMultipartSmsComplete(SEND_STATUS_OK, sendSmsResponses);
                }
            } catch (RemoteException ex) {
            }
        }

        @Override
        public void sendMms(Uri pduUri, String locationUrl, ICarrierMessagingCallback callback) {
            try {
                SendMmsResult result = onSendMms(pduUri, locationUrl);
                if (result == null) {
                    callback.onSendMmsComplete(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, null);
                } else {
                    callback.onSendMmsComplete(SEND_STATUS_OK, result.getSendConfPdu());
                }
            } catch (RemoteException ex) {
            }
        }

        @Override
        public void downloadMms(Uri contentUri, String locationUrl,
                ICarrierMessagingCallback callback) {
            try {
                callback.onDownloadMmsComplete(onDownloadMms(contentUri, locationUrl));
            } catch (RemoteException ex) {
            }
        }
    }
}
