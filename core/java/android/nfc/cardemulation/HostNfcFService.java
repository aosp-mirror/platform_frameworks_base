/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.nfc.cardemulation;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

/**
 * <p>HostNfcFService is a convenience {@link Service} class that can be
 * extended to emulate an NFC-F card inside an Android service component.
 */
public abstract class HostNfcFService extends Service {
    /**
     * The {@link Intent} action that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.nfc.cardemulation.action.HOST_NFCF_SERVICE";

    /**
     * The name of the meta-data element that contains
     * more information about this service.
     */
    public static final String SERVICE_META_DATA =
            "android.nfc.cardemulation.host_nfcf_service";

    /**
     * Reason for {@link #onDeactivated(int)}.
     * Indicates deactivation was due to the NFC link
     * being lost.
     */
    public static final int DEACTIVATION_LINK_LOSS = 0;

    static final String TAG = "NfcFService";

    /**
     * MSG_COMMAND_PACKET is sent by NfcService when
     * a NFC-F command packet has been received.
     *
     * @hide
     */
    public static final int MSG_COMMAND_PACKET = 0;

    /**
     * MSG_RESPONSE_PACKET is sent to NfcService to send
     * a response packet back to the remote device.
     *
     * @hide
     */
    public static final int MSG_RESPONSE_PACKET = 1;

    /**
     * MSG_DEACTIVATED is sent by NfcService when
     * the current session is finished; because
     * the NFC link was deactivated.
     *
     * @hide
     */
    public static final int MSG_DEACTIVATED = 2;

   /**
     * @hide
     */
    public static final String KEY_DATA = "data";

    /**
     * @hide
     */
    public static final String KEY_MESSENGER = "messenger";

    /**
     * Messenger interface to NfcService for sending responses.
     * Only accessed on main thread by the message handler.
     *
     * @hide
     */
    Messenger mNfcService = null;

    final Messenger mMessenger = new Messenger(new MsgHandler());

    final class MsgHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_COMMAND_PACKET:
                Bundle dataBundle = msg.getData();
                if (dataBundle == null) {
                    return;
                }
                if (mNfcService == null) mNfcService = msg.replyTo;

                byte[] packet = dataBundle.getByteArray(KEY_DATA);
                if (packet != null) {
                    byte[] responsePacket = processNfcFPacket(packet, null);
                    if (responsePacket != null) {
                        if (mNfcService == null) {
                            Log.e(TAG, "Response not sent; service was deactivated.");
                            return;
                        }
                        Message responseMsg = Message.obtain(null, MSG_RESPONSE_PACKET);
                        Bundle responseBundle = new Bundle();
                        responseBundle.putByteArray(KEY_DATA, responsePacket);
                        responseMsg.setData(responseBundle);
                        responseMsg.replyTo = mMessenger;
                        try {
                            mNfcService.send(responseMsg);
                        } catch (RemoteException e) {
                            Log.e("TAG", "Response not sent; RemoteException calling into " +
                                    "NfcService.");
                        }
                    }
                } else {
                    Log.e(TAG, "Received MSG_COMMAND_PACKET without data.");
                }
                break;
            case MSG_RESPONSE_PACKET:
                if (mNfcService == null) {
                    Log.e(TAG, "Response not sent; service was deactivated.");
                    return;
                }
                try {
                    msg.replyTo = mMessenger;
                    mNfcService.send(msg);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException calling into NfcService.");
                }
                break;
            case MSG_DEACTIVATED:
                // Make sure we won't call into NfcService again
                mNfcService = null;
                onDeactivated(msg.arg1);
                break;
            default:
                super.handleMessage(msg);
            }
        }
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    /**
     * Sends a response packet back to the remote device.
     *
     * <p>Note: this method may be called from any thread and will not block.
     * @param responsePacket A byte-array containing the response packet.
     */
    public final void sendResponsePacket(byte[] responsePacket) {
        Message responseMsg = Message.obtain(null, MSG_RESPONSE_PACKET);
        Bundle dataBundle = new Bundle();
        dataBundle.putByteArray(KEY_DATA, responsePacket);
        responseMsg.setData(dataBundle);
        try {
            mMessenger.send(responseMsg);
        } catch (RemoteException e) {
            Log.e("TAG", "Local messenger has died.");
        }
    }

    /**
     * <p>This method will be called when a NFC-F packet has been received
     * from a remote device. A response packet can be provided directly
     * by returning a byte-array in this method. Note that in general
     * response packets must be sent as quickly as possible, given the fact
     * that the user is likely holding his device over an NFC reader
     * when this method is called.
     *
     * <p class="note">This method is running on the main thread of your application.
     * If you cannot return a response packet immediately, return null
     * and use the {@link #sendResponsePacket(byte[])} method later.
     *
     * @param commandPacket The NFC-F packet that was received from the remote device
     * @param extras A bundle containing extra data. May be null.
     * @return a byte-array containing the response packet, or null if no
     *         response packet can be sent at this point.
     */
    public abstract byte[] processNfcFPacket(byte[] commandPacket, Bundle extras);

    /**
     * This method will be called in following possible scenarios:
     * <li>The NFC link has been lost
     * @param reason {@link #DEACTIVATION_LINK_LOSS}
     */
    public abstract void onDeactivated(int reason);
}
