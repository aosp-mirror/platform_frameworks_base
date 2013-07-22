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

/**
 * <p>A convenience class that can be extended to implement
 * a service that registers and deals with events for
 * ISO7814-4 AIDs that reside on an embedded secure element
 * or UICC.
 *
 * <p>To tell the platform which ISO7816 application ID (AIDs)
 * are present on the Secure Element and handled by this service,
 * a {@link #SERVICE_META_DATA} entry must be included in the declaration
 * of the service. An example of such a service declaration is shown below:
 * <pre> &lt;service android:name=".MySeApduService"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.nfc.SeApduService"/&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="android.nfc.SeApduService" android:resource="@xml/apduservice.xml"/&gt;
 * &lt;/service&gt;</pre>
 * <p>For more details refer to {@link #SERVICE_META_DATA},
 * <code>&lt;{@link android.R.styleable#ApduService apdu-service}&gt;</code> and
 * <code>&lt;{@link android.R.styleable#AidFilter aid-filter}&gt;</code>.
 */
public abstract class SeApduService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.nfc.SeApduService";

    /**
     * The name of the meta-data element that contains
     * more information about this service.
     */
    public static final String SERVICE_META_DATA = "android.nfc.SeApduService";

    /**
     * @hide
     */
    public static final int MSG_AID_SELECTED = 0;

    /**
     * @hide
     */
    public static final int MSG_HCI_TRANSACTION_EVT = 1;

    /**
     * @hide
     */
    public static final String KEY_AID = "aid";

    /**
     * @hide
     */
    public static final String KEY_PARAMETERS = "parameters";

    final Messenger mMessenger = new Messenger(new MsgHandler());

    final class MsgHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what){
                case MSG_AID_SELECTED: {
                    Bundle dataBundle = msg.getData();
                    byte[] aid = dataBundle.getByteArray(KEY_AID);
                    onAidSelected(aid);
                    break;
                }
                case MSG_HCI_TRANSACTION_EVT: {
                    Bundle dataBundle = msg.getData();
                    byte[] aid = dataBundle.getByteArray(KEY_AID);
                    byte[] parameters = dataBundle.getByteArray(KEY_PARAMETERS);
                    onHciTransactionEvent(aid, parameters);
                    break;
                }
            }
        }
    };

    @Override
    public final IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    /**
     * This method is called when an AID that has been registered
     * in the manifest of this service has been selected on a
     * eSE/UICC.
     * @param aid The AID that has been selected
     */
    public abstract void onAidSelected(byte[] aid);

    /**
     * This method is called when a HCI transaction event has
     * been received for an AID that has been registered
     * in the manifest of this service.
     * @param aid The AID of the application that generated the event
     * @param parameters Parameters according to ETSI-TS 102 622
     */
    public abstract void onHciTransactionEvent(byte[] aid, byte[] parameters);
}