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
 * <p>A convenience class that can be extended to implement
 * a service that processes ISO7816-4 commands on top of
 * the ISO14443-4 / IsoDep protocol (T=CL).
 *
 * <p>To tell the platform which ISO7816 application ID (AIDs)
 * are implemented by this service, a {@link #SERVICE_META_DATA}
 * entry must be included in the declaration of the service. An
 * example of such a service declaration is shown below:
 * <pre> &lt;service android:name=".MyHostApduService"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.nfc.HostApduService"/&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="android.nfc.HostApduService" android:resource="@xml/apduservice.xml"/&gt;
 * &lt;/service&gt;</pre>
 * <p>For more details refer to {@link #SERVICE_META_DATA},
 * <code>&lt;{@link android.R.styleable#ApduService apdu-service}&gt;</code> and
 * <code>&lt;{@link android.R.styleable#AidFilter aid-filter}&gt;</code>.
 * <p class="note">The Android platform currently only supports a single
 * logical channel.
 */
public abstract class HostApduService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.nfc.HostApduService";

    /**
     * The name of the meta-data element that contains
     * more information about this service.
     */
    public static final String SERVICE_META_DATA = "android.nfc.HostApduService";

    /**
     * Reason for {@link #onDeactivated(int)}.
     * Indicates deactivation was due to the NFC link
     * being lost.
     */
    public static final int DEACTIVATION_LINK_LOSS = 0;

    /**
     * Reason for {@link #onDeactivated(int)}.
     *
     * <p>Indicates deactivation was due to a different AID
     * being selected (which implicitly deselects the AID
     * currently active on the logical channel).
     *
     * <p>Note that this next AID may still be resolved to this
     * service, in which case {@link #processCommandApdu(byte[], int)}
     * will be called again.
     */
    public static final int DEACTIVATION_DESELECTED = 1;

    static final String TAG = "ApduService";

    /**
     * MSG_COMMAND_APDU is sent by NfcService when
     * a 7816-4 command APDU has been received.
     *
     * @hide
     */
    public static final int MSG_COMMAND_APDU = 0;

    /**
     * MSG_RESPONSE_APDU is sent to NfcService to send
     * a response APDU back to the remote device.
     *
     * @hide
     */
    public static final int MSG_RESPONSE_APDU = 1;

    /**
     * MSG_DEACTIVATED is sent by NfcService when
     * the current session is finished; either because
     * another AID was selected that resolved to
     * another service, or because the NFC link
     * was deactivated.
     *
     * @hide
     */
    public static final int MSG_DEACTIVATED = 2;

    /**
     * @hide
     */
    public static final String KEY_DATA = "data";

    /**
     * Messenger interface to NfcService for sending responses.
     * Only accessed on main thread by the message handler.
     */
    Messenger mNfcService = null;

    final Messenger mMessenger = new Messenger(new MsgHandler());

    final class MsgHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_COMMAND_APDU:
                Bundle dataBundle = msg.getData();
                if (dataBundle == null) {
                    return;
                }
                if (mNfcService == null) mNfcService = msg.replyTo;

                byte[] apdu = dataBundle.getByteArray(KEY_DATA);
                if (apdu != null) {
                    byte[] responseApdu = processCommandApdu(apdu, 0);
                    if (responseApdu != null) {
                        if (mNfcService == null) {
                            Log.e(TAG, "Response not sent; service was deactivated.");
                            return;
                        }
                        Message responseMsg = Message.obtain(null, MSG_RESPONSE_APDU);
                        Bundle responseBundle = new Bundle();
                        responseBundle.putByteArray(KEY_DATA, responseApdu);
                        responseMsg.setData(responseBundle);
                        try {
                            mNfcService.send(responseMsg);
                        } catch (RemoteException e) {
                            Log.e("TAG", "Response not sent; RemoteException calling into " +
                                    "NfcService.");
                        }
                    }
                } else {
                    Log.e(TAG, "Received MSG_COMMAND_APDU without data.");
                }
                break;
            case MSG_RESPONSE_APDU:
                if (mNfcService == null) {
                    Log.e(TAG, "Response not sent; service was deactivated.");
                    return;
                }
                try {
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
     * Sends a response APDU back to the remote device.
     *
     * <p>Note: this method may be called from any thread and will not block.
     * @param responseApdu A byte-array containing the reponse APDU.
     */
    public final void sendResponseApdu(byte[] responseApdu) {
        Message responseMsg = Message.obtain(null, MSG_RESPONSE_APDU);
        Bundle dataBundle = new Bundle();
        dataBundle.putByteArray(KEY_DATA, responseApdu);
        responseMsg.setData(dataBundle);
        try {
            mMessenger.send(responseMsg);
        } catch (RemoteException e) {
            Log.e("TAG", "Local messenger has died.");
        }
    }

    /**
     * <p>This method will be called when a command APDU has been received
     * from a remote device. A response APDU can be provided directly
     * by returning a byte-array in this method. Note that in general
     * response APDUs must be sent as quickly as possible, given the fact
     * that the user is likely holding his device over an NFC reader
     * when this method is called.
     *
     * <p class="note">If there are multiple services that have registered for the same
     * AIDs in their meta-data entry, you will only get called if the user has
     * explicitly selected your service, either as a default or just for the next tap.
     *
     * <p class="note">This method is running on the main thread of your application.
     * If you cannot return a response APDU immediately, return null
     * and use the {@link #sendResponseApdu(byte[])} method later.
     *
     * @param commandApdu
     * @param flags
     * @return a byte-array containing the response APDU, or null if no
     *         response APDU can be sent at this point.
     */
    public abstract byte[] processCommandApdu(byte[] commandApdu, int flags);

    /**
     * This method will be called in two possible scenarios:
     * <li>The NFC link has been deactivated or lost
     * <li>A different AID has been selected and was resolved to a different
     *     service component
     * @param reason Either {@link #DEACTIVATION_LINK_LOSS} or {@link #DEACTIVATION_DESELECTED}
     */
    public abstract void onDeactivated(int reason);
}
