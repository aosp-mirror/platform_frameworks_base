package android.nfc.cardemulation;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * <p>A convenience class that can be extended to implement
 * a service that registers ISO7814-4 AIDs that reside off-host,
 * for example on an embedded secure element or UICC.
 *
 * <p>This registration will allow the service to be included
 * as an option for handling these AIDs on non-host execution
 * environments. The Operating System will take care of correctly
 * routing the AIDs, based on which service the user has selected
 * to be the handler for an AID.
 *
 * <p>The service may define additional actions outside of the
 * Android namespace that provide further interaction with
 * the off-host execution environment.
 *
 * <p>To tell the platform which ISO7816 application ID (AIDs)
 * are present and handled by the app containing this service,
 * a {@link #SERVICE_META_DATA} entry must be included in the declaration
 * of the service. An example of such a service declaration is shown below:
 * <pre> &lt;service android:name=".MyOffHostApduService"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.nfc.OffHostApduService"/&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="android.nfc.OffHostApduService" android:resource="@xml/apduservice.xml"/&gt;
 * &lt;/service&gt;</pre>
 * <p>For more details refer to {@link #SERVICE_META_DATA},
 * <code>&lt;{@link android.R.styleable#OffHostApduService offhost-apdu-service}&gt;</code>,
 * <code>&lt;{@link android.R.styleable#AidGroup aid-group}&gt;</code> and
 * <code>&lt;{@link android.R.styleable#AidFilter aid-filter}&gt;</code>.
 */
public abstract class OffHostApduService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.nfc.cardemulation.action.OFF_HOST_APDU_SERVICE";

    /**
     * The name of the meta-data element that contains
     * more information about this service.
     */
    public static final String SERVICE_META_DATA =
            "android.nfc.cardemulation.off_host_apdu_service";

    /**
     * The Android platform itself will not bind to this service,
     * but merely uses its declaration to keep track of what AIDs
     * the service is interested in. This information is then used
     * to present the user with a list of applications that can handle
     * an AID, as well as correctly route those AIDs either to the host (in case
     * the user preferred a {@link HostApduService}), or to an off-host
     * execution environment (in case the user preferred a {@link OffHostApduService}.
     *
     * Implementers may define additional actions outside of the
     * Android namespace that allow further interactions with
     * the off-host execution environment. Such implementations
     * would need to override this method.
     */
    public abstract IBinder onBind(Intent intent);
}
