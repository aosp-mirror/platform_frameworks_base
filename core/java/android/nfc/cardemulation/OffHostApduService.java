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
import android.content.pm.PackageManager;
import android.os.IBinder;

/**
 * <p>OffHostApduService is a convenience {@link Service} class that can be
 * extended to describe one or more NFC applications that are residing
 * off-host, for example on an embedded secure element or a UICC.
 *
 * <div class="special reference">
 * <h3>Developer Guide</h3>
 * For a general introduction into the topic of card emulation,
 * please read the <a href="{@docRoot}guide/topics/connectivity/nfc/hce.html">
 * NFC card emulation developer guide.</a></p>
 * </div>
 *
 * <h3>NFC Protocols</h3>
 * <p>Off-host applications represented by this class are based on the NFC-Forum ISO-DEP
 * protocol (based on ISO/IEC 14443-4) and support processing
 * command Application Protocol Data Units (APDUs) as
 * defined in the ISO/IEC 7816-4 specification.
 *
 * <h3>Service selection</h3>
 * <p>When a remote NFC device wants to talk to your
 * off-host NFC application, it sends a so-called
 * "SELECT AID" APDU as defined in the ISO/IEC 7816-4 specification.
 * The AID is an application identifier defined in ISO/IEC 7816-4.
 *
 * <p>The registration procedure for AIDs is defined in the
 * ISO/IEC 7816-5 specification. If you don't want to register an
 * AID, you are free to use AIDs in the proprietary range:
 * bits 8-5 of the first byte must each be set to '1'. For example,
 * "0xF00102030405" is a proprietary AID. If you do use proprietary
 * AIDs, it is recommended to choose an AID of at least 6 bytes,
 * to reduce the risk of collisions with other applications that
 * might be using proprietary AIDs as well.
 *
 * <h3>AID groups</h3>
 * <p>In some cases, an off-host environment may need to register multiple AIDs
 * to implement a certain application, and it needs to be sure
 * that it is the default handler for all of these AIDs (as opposed
 * to some AIDs in the group going to another service).
 *
 * <p>An AID group is a list of AIDs that should be considered as
 * belonging together by the OS. For all AIDs in an AID group, the
 * OS will guarantee one of the following:
 * <ul>
 * <li>All AIDs in the group are routed to the off-host execution environment
 * <li>No AIDs in the group are routed to the off-host execution environment
 * </ul>
 * In other words, there is no in-between state, where some AIDs
 * in the group can be routed to this off-host execution environment,
 * and some to another or a host-based {@link HostApduService}.
 * <h3>AID groups and categories</h3>
 * <p>Each AID group can be associated with a category. This allows
 * the Android OS to classify services, and it allows the user to
 * set defaults at the category level instead of the AID level.
 *
 * <p>You can use
 * {@link CardEmulation#isDefaultServiceForCategory(android.content.ComponentName, String)}
 * to determine if your off-host service is the default handler for a category.
 *
 * <p>In this version of the platform, the only known categories
 * are {@link CardEmulation#CATEGORY_PAYMENT} and {@link CardEmulation#CATEGORY_OTHER}.
 * AID groups without a category, or with a category that is not recognized
 * by the current platform version, will automatically be
 * grouped into the {@link CardEmulation#CATEGORY_OTHER} category.
 *
 * <h3>Service AID registration</h3>
 * <p>To tell the platform which AIDs
 * reside off-host and are managed by this service, a {@link #SERVICE_META_DATA}
 * entry must be included in the declaration of the service. An
 * example of a OffHostApduService manifest declaration is shown below:
 * <pre> &lt;service android:name=".MyOffHostApduService" android:exported="true" android:permission="android.permission.BIND_NFC_SERVICE"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.nfc.cardemulation.action.OFF_HOST_APDU_SERVICE"/&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="android.nfc.cardemulation.off_host_apdu_ervice" android:resource="@xml/apduservice"/&gt;
 * &lt;/service&gt;</pre>
 *
 * This meta-data tag points to an apduservice.xml file.
 * An example of this file with a single AID group declaration is shown below:
 * <pre>
 * &lt;offhost-apdu-service xmlns:android="http://schemas.android.com/apk/res/android"
 *           android:description="@string/servicedesc"&gt;
 *       &lt;aid-group android:description="@string/subscription" android:category="other">
 *           &lt;aid-filter android:name="F0010203040506"/&gt;
 *           &lt;aid-filter android:name="F0394148148100"/&gt;
 *       &lt;/aid-group&gt;
 * &lt;/offhost-apdu-service&gt;
 * </pre>
 *
 * <p>The {@link android.R.styleable#OffHostApduService &lt;offhost-apdu-service&gt;} is required
 * to contain a
 * {@link android.R.styleable#OffHostApduService_description &lt;android:description&gt;}
 * attribute that contains a user-friendly description of the service that may be shown in UI.
 *
 * <p>The {@link android.R.styleable#OffHostApduService &lt;offhost-apdu-service&gt;} must
 * contain one or more {@link android.R.styleable#AidGroup &lt;aid-group&gt;} tags.
 * Each {@link android.R.styleable#AidGroup &lt;aid-group&gt;} must contain one or
 * more {@link android.R.styleable#AidFilter &lt;aid-filter&gt;} tags, each of which
 * contains a single AID. The AID must be specified in hexadecimal format, and contain
 * an even number of characters.
 *
 * <p>This registration will allow the service to be included
 * as an option for being the default handler for categories.
 * The Android OS will take care of correctly
 * routing the AIDs to the off-host execution environment,
 * based on which service the user has selected to be the handler for a certain category.
 *
 * <p>The service may define additional actions outside of the
 * Android namespace that provide further interaction with
 * the off-host execution environment.
 *
 * <p class="note">Use of this class requires the
 * {@link PackageManager#FEATURE_NFC_HOST_CARD_EMULATION} to be present
 * on the device.
 */
public abstract class OffHostApduService extends Service {
    /**
     * The {@link Intent} action that must be declared as handled by the service.
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
