/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.ProxyInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Credentials;
import android.service.restrictions.RestrictionsReceiver;
import android.util.Log;

import com.android.org.conscrypt.TrustedCertificateStore;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Public interface for managing policies enforced on a device. Most clients of this class must be
 * registered with the system as a
 * <a href="{@docRoot}guide/topics/admin/device-admin.html">device administrator</a>. Additionally,
 * a device administrator may be registered as either a profile or device owner. A given method is
 * accessible to all device administrators unless the documentation for that method specifies that
 * it is restricted to either device or profile owners.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about managing policies for device administration, read the
 * <a href="{@docRoot}guide/topics/admin/device-admin.html">Device Administration</a>
 * developer guide.
 * </div>
 */
public class DevicePolicyManager {
    private static String TAG = "DevicePolicyManager";

    private final Context mContext;
    private final IDevicePolicyManager mService;

    private DevicePolicyManager(Context context, Handler handler) {
        mContext = context;
        mService = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
    }

    /** @hide */
    public static DevicePolicyManager create(Context context, Handler handler) {
        DevicePolicyManager me = new DevicePolicyManager(context, handler);
        return me.mService != null ? me : null;
    }

    /**
     * Activity action: Starts the provisioning flow which sets up a managed profile.
     *
     * <p>A managed profile allows data separation for example for the usage of a
     * device as a personal and corporate device. The user which provisioning is started from and
     * the managed profile share a launcher.
     *
     * <p>This intent will typically be sent by a mobile device management application (MDM).
     * Provisioning adds a managed profile and sets the MDM as the profile owner who has full
     * control over the profile.
     *
     * In version {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this intent must contain the
     * extra {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}.
     * As of {@link android.os.Build.VERSION_CODES#M}, it should contain the extra
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME} instead, although specifying only
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME} is still supported.
     *
     * <p> When managed provisioning has completed, broadcasts are sent to the application specified
     * in the provisioning intent. The
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} broadcast is sent in the
     * managed profile and the {@link #ACTION_MANAGED_PROFILE_PROVISIONED} broadcast is sent in
     * the primary profile.
     *
     * <p> If provisioning fails, the managedProfile is removed so the device returns to its
     * previous state.
     *
     * <p>If launched with {@link android.app.Activity#startActivityForResult(Intent, int)} a
     * result code of {@link android.app.Activity#RESULT_OK} implies that the synchronous part of
     * the provisioning flow was successful, although this doesn't guarantee the full flow will
     * succeed. Conversely a result code of {@link android.app.Activity#RESULT_CANCELED} implies
     * that the user backed-out of provisioning, or some precondition for provisioning wasn't met.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_PROFILE
        = "android.app.action.PROVISION_MANAGED_PROFILE";

    /**
     * Activity action: Starts the provisioning flow which sets up a managed device.
     * Must be started with {@link android.app.Activity#startActivityForResult(Intent, int)}.
     *
     * <p> During device owner provisioning a device admin app is set as the owner of the device.
     * A device owner has full control over the device. The device owner can not be modified by the
     * user.
     *
     * <p> A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p> An intent with this action can be sent only on an unprovisioned device.
     * It is possible to check if the device is provisioned or not by looking at
     * {@link android.provider.Settings.Global#DEVICE_PROVISIONED}
     *
     * The intent contains the following extras:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * </ul>
     *
     * <p> When device owner provisioning has completed, an intent of the type
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} is broadcast to the
     * device owner.
     *
     * <p> If provisioning fails, the device is factory reset.
     *
     * <p>A result code of {@link android.app.Activity#RESULT_OK} implies that the synchronous part
     * of the provisioning flow was successful, although this doesn't guarantee the full flow will
     * succeed. Conversely a result code of {@link android.app.Activity#RESULT_CANCELED} implies
     * that the user backed-out of provisioning, or some precondition for provisioning wasn't met.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_DEVICE
        = "android.app.action.PROVISION_MANAGED_DEVICE";

    /**
     * A {@link android.os.Parcelable} extra of type {@link android.os.PersistableBundle} that
     * allows a mobile device management application or NFC programmer application which starts
     * managed provisioning to pass data to the management application instance after provisioning.
     * <p>
     * If used with {@link #ACTION_PROVISION_MANAGED_PROFILE} it can be used by the application that
     * sends the intent to pass data to itself on the newly created profile.
     * If used with {@link #ACTION_PROVISION_MANAGED_DEVICE} it allows passing data to the same
     * instance of the app on the primary user.
     * Starting from {@link android.os.Build.VERSION_CODES#M}, if used with
     * {@link #MIME_TYPE_PROVISIONING_NFC} as part of NFC managed device provisioning, the NFC
     * message should contain a stringified {@link java.util.Properties} instance, whose string
     * properties will be converted into a {@link android.os.PersistableBundle} and passed to the
     * management application after provisioning.
     *
     * <p>
     * In both cases the application receives the data in
     * {@link DeviceAdminReceiver#onProfileProvisioningComplete} via an intent with the action
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE}. The bundle is not changed
     * during the managed provisioning.
     */
    public static final String EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE =
            "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE";

    /**
     * A String extra holding the package name of the mobile device management application that
     * will be set as the profile owner or device owner.
     *
     * <p>If an application starts provisioning directly via an intent with action
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} this package has to match the package name of the
     * application that started provisioning. The package will be set as profile owner in that case.
     *
     * <p>This package is set as device owner when device owner provisioning is started by an NFC
     * message containing an NFC record with MIME type {@link #MIME_TYPE_PROVISIONING_NFC}.
     *
     * <p> When this extra is set, the application must have exactly one device admin receiver.
     * This receiver will be set as the profile or device owner and active admin.

     * @see DeviceAdminReceiver
     * @deprecated Use {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}. This extra is still
     * supported.
     */
    @Deprecated
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME";

    /**
     * A ComponentName extra indicating the device admin receiver of the mobile device management
     * application that will be set as the profile owner or device owner and active admin.
     *
     * <p>If an application starts provisioning directly via an intent with action
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} or
     * {@link #ACTION_PROVISION_MANAGED_DEVICE} the package name of this
     * component has to match the package name of the application that started provisioning.
     *
     * <p>This component is set as device owner and active admin when device owner provisioning is
     * started by an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE} or by an NFC
     * message containing an NFC record with MIME type
     * {@link #MIME_TYPE_PROVISIONING_NFC}. For the NFC record, the component name should be
     * flattened to a string, via {@link ComponentName#flattenToShortString()}.
     *
     * @see DeviceAdminReceiver
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME";

    /**
     * An {@link android.accounts.Account} extra holding the account to migrate during managed
     * profile provisioning. If the account supplied is present in the primary user, it will be
     * copied, along with its credentials to the managed profile and removed from the primary user.
     *
     * Use with {@link #ACTION_PROVISION_MANAGED_PROFILE}.
     */

    public static final String EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE
        = "android.app.extra.PROVISIONING_ACCOUNT_TO_MIGRATE";

    /**
     * A String extra that, holds the email address of the account which a managed profile is
     * created for. Used with {@link #ACTION_PROVISION_MANAGED_PROFILE} and
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE}.
     *
     * <p> This extra is part of the {@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}.
     *
     * <p> If the {@link #ACTION_PROVISION_MANAGED_PROFILE} intent that starts managed provisioning
     * contains this extra, it is forwarded in the
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} intent to the mobile
     * device management application that was set as the profile owner during provisioning.
     * It is usually used to avoid that the user has to enter their email address twice.
     */
    public static final String EXTRA_PROVISIONING_EMAIL_ADDRESS
        = "android.app.extra.PROVISIONING_EMAIL_ADDRESS";

    /**
     * A Boolean extra that can be used by the mobile device management application to skip the
     * disabling of system apps during provisioning when set to {@code true}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} or an intent with action
     * {@link #ACTION_PROVISION_MANAGED_DEVICE} that starts device owner provisioning.
     */
    public static final String EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED =
            "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED";

    /**
     * A String extra holding the time zone {@link android.app.AlarmManager} that the device
     * will be set to.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_TIME_ZONE
        = "android.app.extra.PROVISIONING_TIME_ZONE";

    /**
     * A Long extra holding the wall clock time (in milliseconds) to be set on the device's
     * {@link android.app.AlarmManager}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_LOCAL_TIME
        = "android.app.extra.PROVISIONING_LOCAL_TIME";

    /**
     * A String extra holding the {@link java.util.Locale} that the device will be set to.
     * Format: xx_yy, where xx is the language code, and yy the country code.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_LOCALE
        = "android.app.extra.PROVISIONING_LOCALE";

    /**
     * A String extra holding the ssid of the wifi network that should be used during nfc device
     * owner provisioning for downloading the mobile device management application.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_SSID
        = "android.app.extra.PROVISIONING_WIFI_SSID";

    /**
     * A boolean extra indicating whether the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}
     * is hidden or not.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_HIDDEN
        = "android.app.extra.PROVISIONING_WIFI_HIDDEN";

    /**
     * A String extra indicating the security type of the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_SECURITY_TYPE
        = "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE";

    /**
     * A String extra holding the password of the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PASSWORD
        = "android.app.extra.PROVISIONING_WIFI_PASSWORD";

    /**
     * A String extra holding the proxy host for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_HOST
        = "android.app.extra.PROVISIONING_WIFI_PROXY_HOST";

    /**
     * An int extra holding the proxy port for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_PORT
        = "android.app.extra.PROVISIONING_WIFI_PROXY_PORT";

    /**
     * A String extra holding the proxy bypass for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_BYPASS
        = "android.app.extra.PROVISIONING_WIFI_PROXY_BYPASS";

    /**
     * A String extra holding the proxy auto-config (PAC) URL for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PAC_URL
        = "android.app.extra.PROVISIONING_WIFI_PAC_URL";

    /**
     * A String extra holding a url that specifies the download location of the device admin
     * package. When not provided it is assumed that the device admin package is already installed.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION";

    /**
     * An int extra holding a minimum required version code for the device admin package. If the
     * device admin is already installed on the device, it will only be re-downloaded from
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION} if the version of the
     * installed package is less than this version code.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE";

    /**
     * A String extra holding a http cookie header which should be used in the http request to the
     * url specified in {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER";

    /**
     * A String extra holding the URL-safe base64 encoded SHA-256 or SHA-1 hash (see notes below) of
     * the file at download location specified in
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Either this extra or {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM} should be
     * present. The provided checksum should match the checksum of the file at the download
     * location. If the checksum doesn't match an error will be shown to the user and the user will
     * be asked to factory reset the device.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     *
     * <p><strong>Note:</strong> for devices running {@link android.os.Build.VERSION_CODES#LOLLIPOP}
     * and {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1} only SHA-1 hash is supported.
     * Starting from {@link android.os.Build.VERSION_CODES#M}, this parameter accepts SHA-256 in
     * addition to SHA-1. Support for SHA-1 is likely to be removed in future OS releases.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM";

    /**
     * A String extra holding the URL-safe base64 encoded SHA-256 checksum of any signature of the
     * android package archive at the download location specified in {@link
     * #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>The signatures of an android package archive can be obtained using
     * {@link android.content.pm.PackageManager#getPackageArchiveInfo} with flag
     * {@link android.content.pm.PackageManager#GET_SIGNATURES}.
     *
     * <p>Either this extra or {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM} should be
     * present. The provided checksum should match the checksum of any signature of the file at
     * the download location. If the checksum does not match an error will be shown to the user and
     * the user will be asked to factory reset the device.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM";

    /**
     * Broadcast Action: This broadcast is sent to indicate that provisioning of a managed profile
     * has completed successfully.
     *
     * <p>The broadcast is limited to the primary profile, to the app specified in the provisioning
     * intent with action {@link #ACTION_PROVISION_MANAGED_PROFILE}.
     *
     * <p>This intent will contain the extra {@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE} which
     * corresponds to the account requested to be migrated at provisioning time, if any.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGED_PROFILE_PROVISIONED
        = "android.app.action.MANAGED_PROFILE_PROVISIONED";

    /**
     * A boolean extra indicating whether device encryption can be skipped as part of Device Owner
     * provisioning.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} or an intent with action
     * {@link #ACTION_PROVISION_MANAGED_DEVICE} that starts device owner provisioning.
     */
    public static final String EXTRA_PROVISIONING_SKIP_ENCRYPTION =
             "android.app.extra.PROVISIONING_SKIP_ENCRYPTION";

    /**
     * @hide
     * On devices managed by a device owner app, a {@link ComponentName} extra indicating the
     * component of the application that is temporarily granted device owner privileges during
     * device initialization and profile owner privileges during secondary user initialization.
     *
     * <p>
     * It can also be used in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC_V2} that starts
     * device owner provisioning via an NFC bump. For the NFC record, it should be flattened to a
     * string first.
     *
     * @see ComponentName#flattenToShortString()
     */
    public static final String EXTRA_PROVISIONING_DEVICE_INITIALIZER_COMPONENT_NAME
        = "android.app.extra.PROVISIONING_DEVICE_INITIALIZER_COMPONENT_NAME";

    /**
     * @hide
     * A String extra holding an http url that specifies the download location of the device
     * initializer package. When not provided it is assumed that the device initializer package is
     * already installed.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC_V2} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_INITIALIZER_PACKAGE_DOWNLOAD_LOCATION
        = "android.app.extra.PROVISIONING_DEVICE_INITIALIZER_PACKAGE_DOWNLOAD_LOCATION";

    /**
     * @hide
     * An int extra holding a minimum required version code for the device initializer package.
     * If the initializer is already installed on the device, it will only be re-downloaded from
     * {@link #EXTRA_PROVISIONING_DEVICE_INITIALIZER_PACKAGE_DOWNLOAD_LOCATION} if the version of
     * the installed package is less than this version code.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC_V2} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_INITIALIZER_MINIMUM_VERSION_CODE
        = "android.app.extra.PROVISIONING_DEVICE_INITIALIZER_MINIMUM_VERSION_CODE";

    /**
     * @hide
     * A String extra holding a http cookie header which should be used in the http request to the
     * url specified in {@link #EXTRA_PROVISIONING_DEVICE_INITIALIZER_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC_V2} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_INITIALIZER_PACKAGE_DOWNLOAD_COOKIE_HEADER
        = "android.app.extra.PROVISIONING_DEVICE_INITIALIZER_PACKAGE_DOWNLOAD_COOKIE_HEADER";

    /**
     * @hide
     * A String extra holding the URL-safe base64 encoded SHA-256 checksum of the file at download
     * location specified in
     * {@link #EXTRA_PROVISIONING_DEVICE_INITIALIZER_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Either this extra or {@link #EXTRA_PROVISIONING_DEVICE_INITIALIZER_SIGNATURE_CHECKSUM}
     * should be present. The provided checksum should match the checksum of the file at the
     * download location. If the checksum doesn't match an error will be shown to the user and the
     * user will be asked to factory reset the device.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC_V2} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_INITIALIZER_PACKAGE_CHECKSUM
        = "android.app.extra.PROVISIONING_DEVICE_INITIALIZER_PACKAGE_CHECKSUM";

    /**
     * @hide
     * A String extra holding the URL-safe base64 encoded SHA-256 checksum of any signature of the
     * android package archive at the download location specified in {@link
     * #EXTRA_PROVISIONING_DEVICE_INITIALIZER_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>The signatures of an android package archive can be obtained using
     * {@link android.content.pm.PackageManager#getPackageArchiveInfo} with flag
     * {@link android.content.pm.PackageManager#GET_SIGNATURES}.
     *
     * <p>Either this extra or {@link #EXTRA_PROVISIONING_DEVICE_INITIALIZER_PACKAGE_CHECKSUM}
     * should be present. The provided checksum should match the checksum of any signature of the
     * file at the download location. If the checksum doesn't match an error will be shown to the
     * user and the user will be asked to factory reset the device.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC_V2} that starts device owner
     * provisioning via an NFC bump.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_INITIALIZER_SIGNATURE_CHECKSUM
        = "android.app.extra.PROVISIONING_DEVICE_INITIALIZER_SIGNATURE_CHECKSUM";

    /**
     * This MIME type is used for starting the Device Owner provisioning.
     *
     * <p>During device owner provisioning a device admin app is set as the owner of the device.
     * A device owner has full control over the device. The device owner can not be modified by the
     * user and the only way of resetting the device is if the device owner app calls a factory
     * reset.
     *
     * <p> A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p> The NFC message should be send to an unprovisioned device.
     *
     * <p>The NFC record must contain a serialized {@link java.util.Properties} object which
     * contains the following properties:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LOCAL_TIME} (convert to String), optional</li>
     * <li>{@link #EXTRA_PROVISIONING_TIME_ZONE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LOCALE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_SSID}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_HIDDEN} (convert to String), optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PASSWORD}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PROXY_HOST}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PROXY_PORT} (convert to String), optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PROXY_BYPASS}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PAC_URL}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional, supported from
     * {@link android.os.Build.VERSION_CODES#M} </li></ul>
     *
     * <p>
     * As of {@link android.os.Build.VERSION_CODES#M}, the properties should contain
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME} instead of
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}, (although specifying only
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME} is still supported).
     */
    public static final String MIME_TYPE_PROVISIONING_NFC
        = "application/com.android.managedprovisioning";


    /**
     * @hide
     * This MIME type is used for starting the Device Owner provisioning that requires
     * new provisioning features introduced in API version
     * {@link android.os.Build.VERSION_CODES#M} in addition to those supported in earlier
     * versions.
     *
     * <p>During device owner provisioning a device admin app is set as the owner of the device.
     * A device owner has full control over the device. The device owner can not be modified by the
     * user.
     *
     * <p> A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p> The NFC message should be sent to an unprovisioned device.
     *
     * <p>The NFC record must contain a serialized {@link java.util.Properties} object which
     * contains the following properties in addition to properties listed at
     * {@link #MIME_TYPE_PROVISIONING_NFC}:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}.
     * Replaces {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}. The value of the property
     * should be converted to a String via
     * {@link android.content.ComponentName#flattenToString()}</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE}, optional</li></ul>
     *
     * <p> When device owner provisioning has completed, an intent of the type
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} is broadcasted to the
     * device owner.
     *
     * <p>
     * If provisioning fails, the device is factory reset.
     */
    public static final String MIME_TYPE_PROVISIONING_NFC_V2
            = "application/com.android.managedprovisioning.v2";

    /**
     * Activity action: ask the user to add a new device administrator to the system.
     * The desired policy is the ComponentName of the policy in the
     * {@link #EXTRA_DEVICE_ADMIN} extra field.  This will invoke a UI to
     * bring the user through adding the device administrator to the system (or
     * allowing them to reject it).
     *
     * <p>You can optionally include the {@link #EXTRA_ADD_EXPLANATION}
     * field to provide the user with additional explanation (in addition
     * to your component's description) about what is being added.
     *
     * <p>If your administrator is already active, this will ordinarily return immediately (without
     * user intervention).  However, if your administrator has been updated and is requesting
     * additional uses-policy flags, the user will be presented with the new list.  New policies
     * will not be available to the updated administrator until the user has accepted the new list.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_ADD_DEVICE_ADMIN
            = "android.app.action.ADD_DEVICE_ADMIN";

    /**
     * @hide
     * Activity action: ask the user to add a new device administrator as the profile owner
     * for this user. Only system apps can launch this intent.
     *
     * <p>The ComponentName of the profile owner admin is passed in the {@link #EXTRA_DEVICE_ADMIN}
     * extra field. This will invoke a UI to bring the user through adding the profile owner admin
     * to remotely control restrictions on the user.
     *
     * <p>The intent must be invoked via {@link Activity#startActivityForResult()} to receive the
     * result of whether or not the user approved the action. If approved, the result will
     * be {@link Activity#RESULT_OK} and the component will be set as an active admin as well
     * as a profile owner.
     *
     * <p>You can optionally include the {@link #EXTRA_ADD_EXPLANATION}
     * field to provide the user with additional explanation (in addition
     * to your component's description) about what is being added.
     *
     * <p>If there is already a profile owner active or the caller is not a system app, the
     * operation will return a failure result.
     */
    @SystemApi
    public static final String ACTION_SET_PROFILE_OWNER
            = "android.app.action.SET_PROFILE_OWNER";

    /**
     * @hide
     * Name of the profile owner admin that controls the user.
     */
    @SystemApi
    public static final String EXTRA_PROFILE_OWNER_NAME
            = "android.app.extra.PROFILE_OWNER_NAME";

    /**
     * Broadcast action: send when any policy admin changes a policy.
     * This is generally used to find out when a new policy is in effect.
     *
     * @hide
     */
    public static final String ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
            = "android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED";

    /**
     * Broadcast action: sent when the device owner is set or changed.
     *
     * This broadcast is sent only to the primary user.
     * @see #ACTION_PROVISION_MANAGED_DEVICE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_OWNER_CHANGED
            = "android.app.action.DEVICE_OWNER_CHANGED";

    /**
     * The ComponentName of the administrator component.
     *
     * @see #ACTION_ADD_DEVICE_ADMIN
     */
    public static final String EXTRA_DEVICE_ADMIN = "android.app.extra.DEVICE_ADMIN";

    /**
     * An optional CharSequence providing additional explanation for why the
     * admin is being added.
     *
     * @see #ACTION_ADD_DEVICE_ADMIN
     */
    public static final String EXTRA_ADD_EXPLANATION = "android.app.extra.ADD_EXPLANATION";

    /**
     * Activity action: have the user enter a new password. This activity should
     * be launched after using {@link #setPasswordQuality(ComponentName, int)},
     * or {@link #setPasswordMinimumLength(ComponentName, int)} to have the user
     * enter a new password that meets the current requirements. You can use
     * {@link #isActivePasswordSufficient()} to determine whether you need to
     * have the user select a new password in order to meet the current
     * constraints. Upon being resumed from this activity, you can check the new
     * password characteristics to see if they are sufficient.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_NEW_PASSWORD
            = "android.app.action.SET_NEW_PASSWORD";

    /**
     * Flag used by {@link #addCrossProfileIntentFilter} to allow activities in
     * the parent profile to access intents sent from the managed profile.
     * That is, when an app in the managed profile calls
     * {@link Activity#startActivity(Intent)}, the intent can be resolved by a
     * matching activity in the parent profile.
     */
    public static final int FLAG_PARENT_CAN_ACCESS_MANAGED = 0x0001;

    /**
     * Flag used by {@link #addCrossProfileIntentFilter} to allow activities in
     * the managed profile to access intents sent from the parent profile.
     * That is, when an app in the parent profile calls
     * {@link Activity#startActivity(Intent)}, the intent can be resolved by a
     * matching activity in the managed profile.
     */
    public static final int FLAG_MANAGED_CAN_ACCESS_PARENT = 0x0002;

    /**
     * Broadcast action: notify that a new local system update policy has been set by the device
     * owner. The new policy can be retrieved by {@link #getSystemUpdatePolicy()}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SYSTEM_UPDATE_POLICY_CHANGED
            = "android.app.action.SYSTEM_UPDATE_POLICY_CHANGED";

    /**
     * Permission policy to prompt user for new permission requests for runtime permissions.
     * Already granted or denied permissions are not affected by this.
     */
    public static final int PERMISSION_POLICY_PROMPT = 0;

    /**
     * Permission policy to always grant new permission requests for runtime permissions.
     * Already granted or denied permissions are not affected by this.
     */
    public static final int PERMISSION_POLICY_AUTO_GRANT = 1;

    /**
     * Permission policy to always deny new permission requests for runtime permissions.
     * Already granted or denied permissions are not affected by this.
     */
    public static final int PERMISSION_POLICY_AUTO_DENY = 2;

    /**
     * Runtime permission state: The user can manage the permission
     * through the UI.
     */
    public static final int PERMISSION_GRANT_STATE_DEFAULT = 0;

    /**
     * Runtime permission state: The permission is granted to the app
     * and the user cannot manage the permission through the UI.
     */
    public static final int PERMISSION_GRANT_STATE_GRANTED = 1;

    /**
     * Runtime permission state: The permission is denied to the app
     * and the user cannot manage the permission through the UI.
     */
    public static final int PERMISSION_GRANT_STATE_DENIED = 2;

    /**
     * Return true if the given administrator component is currently
     * active (enabled) in the system.
     */
    public boolean isAdminActive(@NonNull ComponentName admin) {
        return isAdminActiveAsUser(admin, UserHandle.myUserId());
    }

    /**
     * @see #isAdminActive(ComponentName)
     * @hide
     */
    public boolean isAdminActiveAsUser(@NonNull ComponentName admin, int userId) {
        if (mService != null) {
            try {
                return mService.isAdminActive(admin, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }
    /**
     * Return true if the given administrator component is currently being removed
     * for the user.
     * @hide
     */
    public boolean isRemovingAdmin(@NonNull ComponentName admin, int userId) {
        if (mService != null) {
            try {
                return mService.isRemovingAdmin(admin, userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }


    /**
     * Return a list of all currently active device administrators' component
     * names.  If there are no administrators {@code null} may be
     * returned.
     */
    public List<ComponentName> getActiveAdmins() {
        return getActiveAdminsAsUser(UserHandle.myUserId());
    }

    /**
     * @see #getActiveAdmins()
     * @hide
     */
    public List<ComponentName> getActiveAdminsAsUser(int userId) {
        if (mService != null) {
            try {
                return mService.getActiveAdmins(userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Used by package administration code to determine if a package can be stopped
     * or uninstalled.
     * @hide
     */
    public boolean packageHasActiveAdmins(String packageName) {
        if (mService != null) {
            try {
                return mService.packageHasActiveAdmins(packageName, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Remove a current administration component.  This can only be called
     * by the application that owns the administration component; if you
     * try to remove someone else's component, a security exception will be
     * thrown.
     */
    public void removeActiveAdmin(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                mService.removeActiveAdmin(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Returns true if an administrator has been granted a particular device policy.  This can
     * be used to check whether the administrator was activated under an earlier set of policies,
     * but requires additional policies after an upgrade.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.  Must be
     * an active administrator, or an exception will be thrown.
     * @param usesPolicy Which uses-policy to check, as defined in {@link DeviceAdminInfo}.
     */
    public boolean hasGrantedPolicy(@NonNull ComponentName admin, int usesPolicy) {
        if (mService != null) {
            try {
                return mService.hasGrantedPolicy(admin, usesPolicy, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Constant for {@link #setPasswordQuality}: the policy has no requirements
     * for the password.  Note that quality constants are ordered so that higher
     * values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_UNSPECIFIED = 0;

    /**
     * Constant for {@link #setPasswordQuality}: the policy allows for low-security biometric
     * recognition technology.  This implies technologies that can recognize the identity of
     * an individual to about a 3 digit PIN (false detection is less than 1 in 1,000).
     * Note that quality constants are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_BIOMETRIC_WEAK = 0x8000;

    /**
     * Constant for {@link #setPasswordQuality}: the policy requires some kind
     * of password or pattern, but doesn't care what it is. Note that quality constants
     * are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_SOMETHING = 0x10000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least numeric characters.  Note that quality
     * constants are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_NUMERIC = 0x20000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least numeric characters with no repeating (4444)
     * or ordered (1234, 4321, 2468) sequences.  Note that quality
     * constants are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_NUMERIC_COMPLEX = 0x30000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least alphabetic (or other symbol) characters.
     * Note that quality constants are ordered so that higher values are more
     * restrictive.
     */
    public static final int PASSWORD_QUALITY_ALPHABETIC = 0x40000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least <em>both></em> numeric <em>and</em>
     * alphabetic (or other symbol) characters.  Note that quality constants are
     * ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_ALPHANUMERIC = 0x50000;

    /**
     * Constant for {@link #setPasswordQuality}: the user must have entered a
     * password containing at least a letter, a numerical digit and a special
     * symbol, by default. With this password quality, passwords can be
     * restricted to contain various sets of characters, like at least an
     * uppercase letter, etc. These are specified using various methods,
     * like {@link #setPasswordMinimumLowerCase(ComponentName, int)}. Note
     * that quality constants are ordered so that higher values are more
     * restrictive.
     */
    public static final int PASSWORD_QUALITY_COMPLEX = 0x60000;

    /**
     * Called by an application that is administering the device to set the
     * password restrictions it is imposing.  After setting this, the user
     * will not be able to enter a new password that is not at least as
     * restrictive as what has been set.  Note that the current password
     * will remain until the user has set a new one, so the change does not
     * take place immediately.  To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value.
     *
     * <p>Quality constants are ordered so that higher values are more restrictive;
     * thus the highest requested quality constant (between the policy set here,
     * the user's preference, and any other considerations) is the one that
     * is in effect.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param quality The new desired quality.  One of
     * {@link #PASSWORD_QUALITY_UNSPECIFIED}, {@link #PASSWORD_QUALITY_SOMETHING},
     * {@link #PASSWORD_QUALITY_NUMERIC}, {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX},
     * {@link #PASSWORD_QUALITY_ALPHABETIC}, {@link #PASSWORD_QUALITY_ALPHANUMERIC}
     * or {@link #PASSWORD_QUALITY_COMPLEX}.
     */
    public void setPasswordQuality(@NonNull ComponentName admin, int quality) {
        if (mService != null) {
            try {
                mService.setPasswordQuality(admin, quality);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current minimum password quality for all admins of this user
     * and its profiles or a particular one.
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    public int getPasswordQuality(@Nullable ComponentName admin) {
        return getPasswordQuality(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordQuality(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordQuality(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return PASSWORD_QUALITY_UNSPECIFIED;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum allowed password length.  After setting this, the user
     * will not be able to enter a new password that is not at least as
     * restrictive as what has been set.  Note that the current password
     * will remain until the user has set a new one, so the change does not
     * take place immediately.  To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value.  This
     * constraint is only imposed if the administrator has also requested either
     * {@link #PASSWORD_QUALITY_NUMERIC}, {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX},
     * {@link #PASSWORD_QUALITY_ALPHABETIC}, {@link #PASSWORD_QUALITY_ALPHANUMERIC},
     * or {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum password length.  A value of 0
     * means there is no restriction.
     */
    public void setPasswordMinimumLength(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLength(admin, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current minimum password length for all admins of this
     * user and its profiles or a particular one.
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    public int getPasswordMinimumLength(@Nullable ComponentName admin) {
        return getPasswordMinimumLength(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLength(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLength(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of upper case letters required in the password. After
     * setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does
     * not take place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of upper case letters
     *            required in the password. A value of 0 means there is no
     *            restriction.
     */
    public void setPasswordMinimumUpperCase(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumUpperCase(admin, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of upper case letters required in the
     * password for all admins of this user and its profiles or a particular one.
     * This is the same value as set by
     * {#link {@link #setPasswordMinimumUpperCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of upper case letters required in the
     *         password.
     */
    public int getPasswordMinimumUpperCase(@Nullable ComponentName admin) {
        return getPasswordMinimumUpperCase(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumUpperCase(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumUpperCase(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of lower case letters required in the password. After
     * setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does
     * not take place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of lower case letters
     *            required in the password. A value of 0 means there is no
     *            restriction.
     */
    public void setPasswordMinimumLowerCase(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLowerCase(admin, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of lower case letters required in the
     * password for all admins of this user and its profiles or a particular one.
     * This is the same value as set by
     * {#link {@link #setPasswordMinimumLowerCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of lower case letters required in the
     *         password.
     */
    public int getPasswordMinimumLowerCase(@Nullable ComponentName admin) {
        return getPasswordMinimumLowerCase(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLowerCase(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLowerCase(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of letters required in the password. After setting this,
     * the user will not be able to enter a new password that is not at least as
     * restrictive as what has been set. Note that the current password will
     * remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of letters required in the
     *            password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumLetters(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLetters(admin, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of letters required in the password for all
     * admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumLetters(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    public int getPasswordMinimumLetters(@Nullable ComponentName admin) {
        return getPasswordMinimumLetters(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumLetters(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLetters(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of numerical digits required in the password. After
     * setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does
     * not take place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of numerical digits required
     *            in the password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumNumeric(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNumeric(admin, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of numerical digits required in the password
     * for all admins of this user and its profiles or a particular one.
     * This is the same value as set by
     * {#link {@link #setPasswordMinimumNumeric(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of numerical digits required in the password.
     */
    public int getPasswordMinimumNumeric(@Nullable ComponentName admin) {
        return getPasswordMinimumNumeric(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumNumeric(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNumeric(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of symbols required in the password. After setting this,
     * the user will not be able to enter a new password that is not at least as
     * restrictive as what has been set. Note that the current password will
     * remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} after setting this value. This
     * constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. The
     * default value is 1.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of symbols required in the
     *            password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumSymbols(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumSymbols(admin, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of symbols required in the password for all
     * admins or a particular one. This is the same value as
     * set by {#link {@link #setPasswordMinimumSymbols(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of symbols required in the password.
     */
    public int getPasswordMinimumSymbols(@Nullable ComponentName admin) {
        return getPasswordMinimumSymbols(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumSymbols(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumSymbols(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the
     * minimum number of non-letter characters (numerical digits or symbols)
     * required in the password. After setting this, the user will not be able
     * to enter a new password that is not at least as restrictive as what has
     * been set. Note that the current password will remain until the user has
     * set a new one, so the change does not take place immediately. To prompt
     * the user for a new password, use {@link #ACTION_SET_NEW_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator
     * has also requested {@link #PASSWORD_QUALITY_COMPLEX} with
     * {@link #setPasswordQuality}. The default value is 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param length The new desired minimum number of letters required in the
     *            password. A value of 0 means there is no restriction.
     */
    public void setPasswordMinimumNonLetter(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNonLetter(admin, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current number of non-letter characters required in the
     * password for all admins of this user and its profiles or a particular one.
     * This is the same value as set by
     * {#link {@link #setPasswordMinimumNonLetter(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    public int getPasswordMinimumNonLetter(@Nullable ComponentName admin) {
        return getPasswordMinimumNonLetter(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordMinimumNonLetter(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNonLetter(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

  /**
   * Called by an application that is administering the device to set the length
   * of the password history. After setting this, the user will not be able to
   * enter a new password that is the same as any password in the history. Note
   * that the current password will remain until the user has set a new one, so
   * the change does not take place immediately. To prompt the user for a new
   * password, use {@link #ACTION_SET_NEW_PASSWORD} after setting this value.
   * This constraint is only imposed if the administrator has also requested
   * either {@link #PASSWORD_QUALITY_NUMERIC}, {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX}
   * {@link #PASSWORD_QUALITY_ALPHABETIC}, or {@link #PASSWORD_QUALITY_ALPHANUMERIC}
   * with {@link #setPasswordQuality}.
   *
   * <p>
   * The calling device admin must have requested
   * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this
   * method; if it has not, a security exception will be thrown.
   *
   * @param admin Which {@link DeviceAdminReceiver} this request is associated
   *        with.
   * @param length The new desired length of password history. A value of 0
   *        means there is no restriction.
   */
    public void setPasswordHistoryLength(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordHistoryLength(admin, length);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a device admin to set the password expiration timeout. Calling this method
     * will restart the countdown for password expiration for the given admin, as will changing
     * the device password (for all admins).
     *
     * <p>The provided timeout is the time delta in ms and will be added to the current time.
     * For example, to have the password expire 5 days from now, timeout would be
     * 5 * 86400 * 1000 = 432000000 ms for timeout.
     *
     * <p>To disable password expiration, a value of 0 may be used for timeout.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_EXPIRE_PASSWORD} to be able to call this
     * method; if it has not, a security exception will be thrown.
     *
     * <p> Note that setting the password will automatically reset the expiration time for all
     * active admins. Active admins do not need to explicitly call this method in that case.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param timeout The limit (in ms) that a password can remain in effect. A value of 0
     *        means there is no restriction (unlimited).
     */
    public void setPasswordExpirationTimeout(@NonNull ComponentName admin, long timeout) {
        if (mService != null) {
            try {
                mService.setPasswordExpirationTimeout(admin, timeout);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Get the password expiration timeout for the given admin. The expiration timeout is the
     * recurring expiration timeout provided in the call to
     * {@link #setPasswordExpirationTimeout(ComponentName, long)} for the given admin or the
     * aggregate of all policy administrators if {@code admin} is null.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate all admins.
     * @return The timeout for the given admin or the minimum of all timeouts
     */
    public long getPasswordExpirationTimeout(@Nullable ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpirationTimeout(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Get the current password expiration time for the given admin or an aggregate of
     * all admins of this user and its profiles if admin is null. If the password is
     * expired, this will return the time since the password expired as a negative number.
     * If admin is null, then a composite of all expiration timeouts is returned
     * - which will be the minimum of all timeouts.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate all admins.
     * @return The password expiration time, in ms.
     */
    public long getPasswordExpiration(@Nullable ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpiration(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Retrieve the current password history length for all admins of this
     * user and its profiles or a particular one.
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     * @return The length of the password history
     */
    public int getPasswordHistoryLength(@Nullable ComponentName admin) {
        return getPasswordHistoryLength(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getPasswordHistoryLength(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordHistoryLength(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Return the maximum password length that the device supports for a
     * particular password quality.
     * @param quality The quality being interrogated.
     * @return Returns the maximum length that the user can enter.
     */
    public int getPasswordMaximumLength(int quality) {
        // Kind-of arbitrary.
        return 16;
    }

    /**
     * Determine whether the current password the user has set is sufficient
     * to meet the policy requirements (quality, minimum length) that have been
     * requested by the admins of this user and its profiles.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @return Returns true if the password meets the current requirements, else false.
     */
    public boolean isActivePasswordSufficient() {
        if (mService != null) {
            try {
                return mService.isActivePasswordSufficient(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Retrieve the number of times the user has failed at entering a
     * password since that last successful password entry.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} to be able to call
     * this method; if it has not, a security exception will be thrown.
     */
    public int getCurrentFailedPasswordAttempts() {
        if (mService != null) {
            try {
                return mService.getCurrentFailedPasswordAttempts(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return -1;
    }

    /**
     * Queries whether {@link #RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT} flag is set.
     *
     * @return true if RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT flag is set.
     * @hide
     */
    public boolean getDoNotAskCredentialsOnBoot() {
        if (mService != null) {
            try {
                return mService.getDoNotAskCredentialsOnBoot();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to call getDoNotAskCredentialsOnBoot()", e);
            }
        }
        return false;
    }

    /**
     * Setting this to a value greater than zero enables a built-in policy
     * that will perform a device wipe after too many incorrect
     * device-unlock passwords have been entered.  This built-in policy combines
     * watching for failed passwords and wiping the device, and requires
     * that you request both {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} and
     * {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}}.
     *
     * <p>To implement any other policy (e.g. wiping data for a particular
     * application only, erasing or revoking credentials, or reporting the
     * failure to a server), you should implement
     * {@link DeviceAdminReceiver#onPasswordFailed(Context, android.content.Intent)}
     * instead.  Do not use this API, because if the maximum count is reached,
     * the device will be wiped immediately, and your callback will not be invoked.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param num The number of failed password attempts at which point the
     * device will wipe its data.
     */
    public void setMaximumFailedPasswordsForWipe(@NonNull ComponentName admin, int num) {
        if (mService != null) {
            try {
                mService.setMaximumFailedPasswordsForWipe(admin, num);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current maximum number of login attempts that are allowed
     * before the device wipes itself, for all admins of this user and its profiles
     * or a particular one.
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    public int getMaximumFailedPasswordsForWipe(@Nullable ComponentName admin) {
        return getMaximumFailedPasswordsForWipe(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getMaximumFailedPasswordsForWipe(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumFailedPasswordsForWipe(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Returns the profile with the smallest maximum failed passwords for wipe,
     * for the given user. So for primary user, it might return the primary or
     * a managed profile. For a secondary user, it would be the same as the
     * user passed in.
     * @hide Used only by Keyguard
     */
    public int getProfileWithMinimumFailedPasswordsForWipe(int userHandle) {
        if (mService != null) {
            try {
                return mService.getProfileWithMinimumFailedPasswordsForWipe(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * Flag for {@link #resetPassword}: don't allow other admins to change
     * the password again until the user has entered it.
     */
    public static final int RESET_PASSWORD_REQUIRE_ENTRY = 0x0001;

    /**
     * Flag for {@link #resetPassword}: don't ask for user credentials on device boot.
     * If the flag is set, the device can be booted without asking for user password.
     * The absence of this flag does not change the current boot requirements. This flag
     * can be set by the device owner only. If the app is not the device owner, the flag
     * is ignored. Once the flag is set, it cannot be reverted back without resetting the
     * device to factory defaults.
     */
    public static final int RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT = 0x0002;

    /**
     * Force a new device unlock password (the password needed to access the
     * entire device, not for individual accounts) on the user.  This takes
     * effect immediately.
     * The given password must be sufficient for the
     * current password quality and length constraints as returned by
     * {@link #getPasswordQuality(ComponentName)} and
     * {@link #getPasswordMinimumLength(ComponentName)}; if it does not meet
     * these constraints, then it will be rejected and false returned.  Note
     * that the password may be a stronger quality (containing alphanumeric
     * characters when the requested quality is only numeric), in which case
     * the currently active quality will be increased to match.
     *
     * <p>Calling with a null or empty password will clear any existing PIN,
     * pattern or password if the current password constraints allow it.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_RESET_PASSWORD} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * <p>Calling this from a managed profile will throw a security exception.
     *
     * @param password The new password for the user. Null or empty clears the password.
     * @param flags May be 0 or combination of {@link #RESET_PASSWORD_REQUIRE_ENTRY} and
     *              {@link #RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT}.
     * @return Returns true if the password was applied, or false if it is
     * not acceptable for the current constraints.
     */
    public boolean resetPassword(String password, int flags) {
        if (mService != null) {
            try {
                return mService.resetPassword(password, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to set the
     * maximum time for user activity until the device will lock.  This limits
     * the length that the user can set.  It takes effect immediately.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param timeMs The new desired maximum time to lock in milliseconds.
     * A value of 0 means there is no restriction.
     */
    public void setMaximumTimeToLock(@NonNull ComponentName admin, long timeMs) {
        if (mService != null) {
            try {
                mService.setMaximumTimeToLock(admin, timeMs);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Retrieve the current maximum time to unlock for all admins of this user
     * and its profiles or a particular one.
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     * @return time in milliseconds for the given admin or the minimum value (strictest) of
     * all admins if admin is null. Returns 0 if there are no restrictions.
     */
    public long getMaximumTimeToLock(@Nullable ComponentName admin) {
        return getMaximumTimeToLock(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public long getMaximumTimeToLock(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumTimeToLock(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return 0;
    }

    /**
     * Make the device lock immediately, as if the lock screen timeout has
     * expired at the point of this call.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK} to be able to call
     * this method; if it has not, a security exception will be thrown.
     */
    public void lockNow() {
        if (mService != null) {
            try {
                mService.lockNow();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Flag for {@link #wipeData(int)}: also erase the device's external
     * storage (such as SD cards).
     */
    public static final int WIPE_EXTERNAL_STORAGE = 0x0001;

    /**
     * Flag for {@link #wipeData(int)}: also erase the factory reset protection
     * data.
     *
     * <p>This flag may only be set by device owner admins; if it is set by
     * other admins a {@link SecurityException} will be thrown.
     */
    public static final int WIPE_RESET_PROTECTION_DATA = 0x0002;

    /**
     * Ask the user data be wiped.  Wiping the primary user will cause the
     * device to reboot, erasing all user data while next booting up.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param flags Bit mask of additional options: currently supported flags
     * are {@link #WIPE_EXTERNAL_STORAGE} and
     * {@link #WIPE_RESET_PROTECTION_DATA}.
     */
    public void wipeData(int flags) {
        if (mService != null) {
            try {
                mService.wipeData(flags, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by an application that is administering the device to set the
     * global proxy and exclusion list.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_SETS_GLOBAL_PROXY} to be able to call
     * this method; if it has not, a security exception will be thrown.
     * Only the first device admin can set the proxy. If a second admin attempts
     * to set the proxy, the {@link ComponentName} of the admin originally setting the
     * proxy will be returned. If successful in setting the proxy, {@code null} will
     * be returned.
     * The method can be called repeatedly by the device admin alrady setting the
     * proxy to update the proxy and exclusion list.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param proxySpec the global proxy desired. Must be an HTTP Proxy.
     *            Pass Proxy.NO_PROXY to reset the proxy.
     * @param exclusionList a list of domains to be excluded from the global proxy.
     * @return {@code null} if the proxy was successfully set, or otherwise a {@link ComponentName}
     *            of the device admin that sets the proxy.
     * @hide
     */
    public ComponentName setGlobalProxy(@NonNull ComponentName admin, Proxy proxySpec,
            List<String> exclusionList ) {
        if (proxySpec == null) {
            throw new NullPointerException();
        }
        if (mService != null) {
            try {
                String hostSpec;
                String exclSpec;
                if (proxySpec.equals(Proxy.NO_PROXY)) {
                    hostSpec = null;
                    exclSpec = null;
                } else {
                    if (!proxySpec.type().equals(Proxy.Type.HTTP)) {
                        throw new IllegalArgumentException();
                    }
                    InetSocketAddress sa = (InetSocketAddress)proxySpec.address();
                    String hostName = sa.getHostName();
                    int port = sa.getPort();
                    StringBuilder hostBuilder = new StringBuilder();
                    hostSpec = hostBuilder.append(hostName)
                        .append(":").append(Integer.toString(port)).toString();
                    if (exclusionList == null) {
                        exclSpec = "";
                    } else {
                        StringBuilder listBuilder = new StringBuilder();
                        boolean firstDomain = true;
                        for (String exclDomain : exclusionList) {
                            if (!firstDomain) {
                                listBuilder = listBuilder.append(",");
                            } else {
                                firstDomain = false;
                            }
                            listBuilder = listBuilder.append(exclDomain.trim());
                        }
                        exclSpec = listBuilder.toString();
                    }
                    if (android.net.Proxy.validate(hostName, Integer.toString(port), exclSpec)
                            != android.net.Proxy.PROXY_VALID)
                        throw new IllegalArgumentException();
                }
                return mService.setGlobalProxy(admin, hostSpec, exclSpec);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Set a network-independent global HTTP proxy.  This is not normally what you want
     * for typical HTTP proxies - they are generally network dependent.  However if you're
     * doing something unusual like general internal filtering this may be useful.  On
     * a private network where the proxy is not accessible, you may break HTTP using this.
     *
     * <p>This method requires the caller to be the device owner.
     *
     * <p>This proxy is only a recommendation and it is possible that some apps will ignore it.
     * @see ProxyInfo
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param proxyInfo The a {@link ProxyInfo} object defining the new global
     *        HTTP proxy.  A {@code null} value will clear the global HTTP proxy.
     */
    public void setRecommendedGlobalProxy(@NonNull ComponentName admin, @Nullable ProxyInfo
            proxyInfo) {
        if (mService != null) {
            try {
                mService.setRecommendedGlobalProxy(admin, proxyInfo);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Returns the component name setting the global proxy.
     * @return ComponentName object of the device admin that set the global proxy, or {@code null}
     *         if no admin has set the proxy.
     * @hide
     */
    public ComponentName getGlobalProxyAdmin() {
        if (mService != null) {
            try {
                return mService.getGlobalProxyAdmin(UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is not supported.
     */
    public static final int ENCRYPTION_STATUS_UNSUPPORTED = 0;

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is supported, but is not currently active.
     */
    public static final int ENCRYPTION_STATUS_INACTIVE = 1;

    /**
     * Result code for {@link #getStorageEncryptionStatus}:
     * indicating that encryption is not currently active, but is currently
     * being activated.  This is only reported by devices that support
     * encryption of data and only when the storage is currently
     * undergoing a process of becoming encrypted.  A device that must reboot and/or wipe data
     * to become encrypted will never return this value.
     */
    public static final int ENCRYPTION_STATUS_ACTIVATING = 2;

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is active.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE = 3;

    /**
     * Result code for {@link #getStorageEncryptionStatus}:
     * indicating that encryption is active, but an encryption key has not
     * been set by the user.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY = 4;

    /**
     * Activity action: begin the process of encrypting data on the device.  This activity should
     * be launched after using {@link #setStorageEncryption} to request encryption be activated.
     * After resuming from this activity, use {@link #getStorageEncryption}
     * to check encryption status.  However, on some devices this activity may never return, as
     * it may trigger a reboot and in some cases a complete data wipe of the device.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_START_ENCRYPTION
            = "android.app.action.START_ENCRYPTION";

    /**
     * Widgets are enabled in keyguard
     */
    public static final int KEYGUARD_DISABLE_FEATURES_NONE = 0;

    /**
     * Disable all keyguard widgets. Has no effect.
     */
    public static final int KEYGUARD_DISABLE_WIDGETS_ALL = 1 << 0;

    /**
     * Disable the camera on secure keyguard screens (e.g. PIN/Pattern/Password)
     */
    public static final int KEYGUARD_DISABLE_SECURE_CAMERA = 1 << 1;

    /**
     * Disable showing all notifications on secure keyguard screens (e.g. PIN/Pattern/Password)
     */
    public static final int KEYGUARD_DISABLE_SECURE_NOTIFICATIONS = 1 << 2;

    /**
     * Only allow redacted notifications on secure keyguard screens (e.g. PIN/Pattern/Password)
     */
    public static final int KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS = 1 << 3;

    /**
     * Ignore trust agent state on secure keyguard screens
     * (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_TRUST_AGENTS = 1 << 4;

    /**
     * Disable fingerprint sensor on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_FINGERPRINT = 1 << 5;

    /**
     * Disable all current and future keyguard customizations.
     */
    public static final int KEYGUARD_DISABLE_FEATURES_ALL = 0x7fffffff;

    /**
     * Called by an application that is administering the device to
     * request that the storage system be encrypted.
     *
     * <p>When multiple device administrators attempt to control device
     * encryption, the most secure, supported setting will always be
     * used.  If any device administrator requests device encryption,
     * it will be enabled;  Conversely, if a device administrator
     * attempts to disable device encryption while another
     * device administrator has enabled it, the call to disable will
     * fail (most commonly returning {@link #ENCRYPTION_STATUS_ACTIVE}).
     *
     * <p>This policy controls encryption of the secure (application data) storage area.  Data
     * written to other storage areas may or may not be encrypted, and this policy does not require
     * or control the encryption of any other storage areas.
     * There is one exception:  If {@link android.os.Environment#isExternalStorageEmulated()} is
     * {@code true}, then the directory returned by
     * {@link android.os.Environment#getExternalStorageDirectory()} must be written to disk
     * within the encrypted storage area.
     *
     * <p>Important Note:  On some devices, it is possible to encrypt storage without requiring
     * the user to create a device PIN or Password.  In this case, the storage is encrypted, but
     * the encryption key may not be fully secured.  For maximum security, the administrator should
     * also require (and check for) a pattern, PIN, or password.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param encrypt true to request encryption, false to release any previous request
     * @return the new request status (for all active admins) - will be one of
     * {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link #ENCRYPTION_STATUS_INACTIVE}, or
     * {@link #ENCRYPTION_STATUS_ACTIVE}.  This is the value of the requests;  Use
     * {@link #getStorageEncryptionStatus()} to query the actual device state.
     */
    public int setStorageEncryption(@NonNull ComponentName admin, boolean encrypt) {
        if (mService != null) {
            try {
                return mService.setStorageEncryption(admin, encrypt);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * Called by an application that is administering the device to
     * determine the requested setting for secure storage.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.  If null,
     * this will return the requested encryption setting as an aggregate of all active
     * administrators.
     * @return true if the admin(s) are requesting encryption, false if not.
     */
    public boolean getStorageEncryption(@Nullable ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getStorageEncryption(admin, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to
     * determine the current encryption status of the device.
     *
     * Depending on the returned status code, the caller may proceed in different
     * ways.  If the result is {@link #ENCRYPTION_STATUS_UNSUPPORTED}, the
     * storage system does not support encryption.  If the
     * result is {@link #ENCRYPTION_STATUS_INACTIVE}, use {@link
     * #ACTION_START_ENCRYPTION} to begin the process of encrypting or decrypting the
     * storage.  If the result is {@link #ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY}, the
     * storage system has enabled encryption but no password is set so further action
     * may be required.  If the result is {@link #ENCRYPTION_STATUS_ACTIVATING} or
     * {@link #ENCRYPTION_STATUS_ACTIVE}, no further action is required.
     *
     * @return current status of encryption. The value will be one of
     * {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link #ENCRYPTION_STATUS_INACTIVE},
     * {@link #ENCRYPTION_STATUS_ACTIVATING}, {@link #ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY},
     * or {@link #ENCRYPTION_STATUS_ACTIVE}.
     */
    public int getStorageEncryptionStatus() {
        return getStorageEncryptionStatus(UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getStorageEncryptionStatus(int userHandle) {
        if (mService != null) {
            try {
                return mService.getStorageEncryptionStatus(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * Installs the given certificate as a user CA.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to install.
     *
     * @return false if the certBuffer cannot be parsed or installation is
     *         interrupted, true otherwise.
     */
    public boolean installCaCert(@Nullable ComponentName admin, byte[] certBuffer) {
        if (mService != null) {
            try {
                return mService.installCaCert(admin, certBuffer);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Uninstalls the given certificate from trusted user CAs, if present.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to remove.
     */
    public void uninstallCaCert(@Nullable ComponentName admin, byte[] certBuffer) {
        if (mService != null) {
            try {
                final String alias = getCaCertAlias(certBuffer);
                mService.uninstallCaCerts(admin, new String[] {alias});
            } catch (CertificateException e) {
                Log.w(TAG, "Unable to parse certificate", e);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Returns all CA certificates that are currently trusted, excluding system CA certificates.
     * If a user has installed any certificates by other means than device policy these will be
     * included too.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @return a List of byte[] arrays, each encoding one user CA certificate.
     */
    public List<byte[]> getInstalledCaCerts(@Nullable ComponentName admin) {
        List<byte[]> certs = new ArrayList<byte[]>();
        if (mService != null) {
            try {
                mService.enforceCanManageCaCerts(admin);
                final TrustedCertificateStore certStore = new TrustedCertificateStore();
                for (String alias : certStore.userAliases()) {
                    try {
                        certs.add(certStore.getCertificate(alias).getEncoded());
                    } catch (CertificateException ce) {
                        Log.w(TAG, "Could not encode certificate: " + alias, ce);
                    }
                }
            } catch (RemoteException re) {
                Log.w(TAG, "Failed talking with device policy service", re);
            }
        }
        return certs;
    }

    /**
     * Uninstalls all custom trusted CA certificates from the profile. Certificates installed by
     * means other than device policy will also be removed, except for system CA certificates.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     */
    public void uninstallAllUserCaCerts(@Nullable ComponentName admin) {
        if (mService != null) {
            try {
                mService.uninstallCaCerts(admin, new TrustedCertificateStore().userAliases()
                        .toArray(new String[0]));
            } catch (RemoteException re) {
                Log.w(TAG, "Failed talking with device policy service", re);
            }
        }
    }

    /**
     * Returns whether this certificate is installed as a trusted CA.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to look up.
     */
    public boolean hasCaCertInstalled(@Nullable ComponentName admin, byte[] certBuffer) {
        if (mService != null) {
            try {
                mService.enforceCanManageCaCerts(admin);
                return getCaCertAlias(certBuffer) != null;
            } catch (RemoteException re) {
                Log.w(TAG, "Failed talking with device policy service", re);
            } catch (CertificateException ce) {
                Log.w(TAG, "Could not parse certificate", ce);
            }
        }
        return false;
    }

    /**
     * Called by a device or profile owner to install a certificate and private key pair. The
     * keypair will be visible to all apps within the profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if calling from a delegated certificate installer.
     * @param privKey The private key to install.
     * @param cert The certificate to install.
     * @param alias The private key alias under which to install the certificate. If a certificate
     * with that alias already exists, it will be overwritten.
     * @return {@code true} if the keys were installed, {@code false} otherwise.
     */
    public boolean installKeyPair(@Nullable ComponentName admin, PrivateKey privKey, Certificate cert,
            String alias) {
        try {
            final byte[] pemCert = Credentials.convertToPem(cert);
            final byte[] pkcs8Key = KeyFactory.getInstance(privKey.getAlgorithm())
                    .getKeySpec(privKey, PKCS8EncodedKeySpec.class).getEncoded();
            return mService.installKeyPair(admin, pkcs8Key, pemCert, alias);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed talking with device policy service", e);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            Log.w(TAG, "Failed to obtain private key material", e);
        } catch (CertificateException | IOException e) {
            Log.w(TAG, "Could not pem-encode certificate", e);
        }
        return false;
    }

    /**
     * @return the alias of a given CA certificate in the certificate store, or {@code null} if it
     * doesn't exist.
     */
    private static String getCaCertAlias(byte[] certBuffer) throws CertificateException {
        final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        final X509Certificate cert = (X509Certificate) certFactory.generateCertificate(
                              new ByteArrayInputStream(certBuffer));
        return new TrustedCertificateStore().getCertificateAlias(cert);
    }

    /**
     * Called by a profile owner or device owner to grant access to privileged certificate
     * manipulation APIs to a third-party certificate installer app. Granted APIs include
     * {@link #getInstalledCaCerts}, {@link #hasCaCertInstalled}, {@link #installCaCert},
     * {@link #uninstallCaCert}, {@link #uninstallAllUserCaCerts} and {@link #installKeyPair}.
     * <p>
     * Delegated certificate installer is a per-user state. The delegated access is persistent until
     * it is later cleared by calling this method with a null value or uninstallling the certificate
     * installer.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param installerPackage The package name of the certificate installer which will be given
     * access. If {@code null} is given the current package will be cleared.
     */
    public void setCertInstallerPackage(@NonNull ComponentName admin, @Nullable String
            installerPackage) throws SecurityException {
        if (mService != null) {
            try {
                mService.setCertInstallerPackage(admin, installerPackage);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile owner or device owner to retrieve the certificate installer for the
     * current user. null if none is set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The package name of the current delegated certificate installer, or {@code null}
     * if none is set.
     */
    public String getCertInstallerPackage(@NonNull ComponentName admin) throws SecurityException {
        if (mService != null) {
            try {
                return mService.getCertInstallerPackage(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Called by an application that is administering the device to disable all cameras
     * on the device, for this user. After setting this, no applications running as this user
     * will be able to access any cameras on the device.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_CAMERA} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled Whether or not the camera should be disabled.
     */
    public void setCameraDisabled(@NonNull ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setCameraDisabled(admin, disabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Determine whether or not the device's cameras have been disabled for this user,
     * either by the current admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or {@code null} to check whether any admins
     * have disabled the camera
     */
    public boolean getCameraDisabled(@Nullable ComponentName admin) {
        return getCameraDisabled(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public boolean getCameraDisabled(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getCameraDisabled(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by a device/profile owner to set whether the screen capture is disabled. Disabling
     * screen capture also prevents the content from being shown on display devices that do not have
     * a secure video output. See {@link android.view.Display#FLAG_SECURE} for more details about
     * secure surfaces and secure displays.
     *
     * <p>The calling device admin must be a device or profile owner. If it is not, a
     * security exception will be thrown.
     *
     * <p>From version {@link android.os.Build.VERSION_CODES#M} disabling screen capture also
     * blocks assist requests for all activities of the relevant user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled Whether screen capture is disabled or not.
     */
    public void setScreenCaptureDisabled(@NonNull ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setScreenCaptureDisabled(admin, disabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Determine whether or not screen capture has been disabled by the current
     * admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or {@code null} to check whether any admins
     * have disabled screen capture.
     */
    public boolean getScreenCaptureDisabled(@Nullable ComponentName admin) {
        return getScreenCaptureDisabled(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public boolean getScreenCaptureDisabled(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getScreenCaptureDisabled(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by a device owner to set whether auto time is required. If auto time is
     * required the user cannot set the date and time, but has to use network date and time.
     *
     * <p>Note: if auto time is required the user can still manually set the time zone.
     *
     * <p>The calling device admin must be a device owner. If it is not, a security exception will
     * be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param required Whether auto time is set required or not.
     */
    public void setAutoTimeRequired(@NonNull ComponentName admin, boolean required) {
        if (mService != null) {
            try {
                mService.setAutoTimeRequired(admin, required);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @return true if auto time is required.
     */
    public boolean getAutoTimeRequired() {
        if (mService != null) {
            try {
                return mService.getAutoTimeRequired();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to disable keyguard customizations,
     * such as widgets. After setting this, keyguard features will be disabled according to the
     * provided feature list.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call
     * this method; if it has not, a security exception will be thrown.
     *
     * <p>Calling this from a managed profile before version
     * {@link android.os.Build.VERSION_CODES#M} will throw a security exception.
     *
     * <p>From version {@link android.os.Build.VERSION_CODES#M} a profile owner can set:
     * <ul>
     * <li>{@link #KEYGUARD_DISABLE_TRUST_AGENTS}, {@link #KEYGUARD_DISABLE_FINGERPRINT}
     *      these will affect the profile's parent user.
     * <li>{@link #KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS} this will affect notifications
     * generated by applications in the managed profile.
     * </ul>
     * <p>Requests to disable other features on a managed profile will be ignored. The admin
     * can check which features have been disabled by calling
     * {@link #getKeyguardDisabledFeatures(ComponentName)}
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param which {@link #KEYGUARD_DISABLE_FEATURES_NONE} (default),
     * {@link #KEYGUARD_DISABLE_WIDGETS_ALL}, {@link #KEYGUARD_DISABLE_SECURE_CAMERA},
     * {@link #KEYGUARD_DISABLE_SECURE_NOTIFICATIONS}, {@link #KEYGUARD_DISABLE_TRUST_AGENTS},
     * {@link #KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS}, {@link #KEYGUARD_DISABLE_FINGERPRINT},
     * {@link #KEYGUARD_DISABLE_FEATURES_ALL}
     */
    public void setKeyguardDisabledFeatures(@NonNull ComponentName admin, int which) {
        if (mService != null) {
            try {
                mService.setKeyguardDisabledFeatures(admin, which);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Determine whether or not features have been disabled in keyguard either by the current
     * admin, if specified, or all admins.
     * @param admin The name of the admin component to check, or {@code null} to check whether any admins
     * have disabled features in keyguard.
     * @return bitfield of flags. See {@link #setKeyguardDisabledFeatures(ComponentName, int)}
     * for a list.
     */
    public int getKeyguardDisabledFeatures(@Nullable ComponentName admin) {
        return getKeyguardDisabledFeatures(admin, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public int getKeyguardDisabledFeatures(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getKeyguardDisabledFeatures(admin, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return KEYGUARD_DISABLE_FEATURES_NONE;
    }

    /**
     * @hide
     */
    public void setActiveAdmin(@NonNull ComponentName policyReceiver, boolean refreshing,
            int userHandle) {
        if (mService != null) {
            try {
                mService.setActiveAdmin(policyReceiver, refreshing, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void setActiveAdmin(@NonNull ComponentName policyReceiver, boolean refreshing) {
        setActiveAdmin(policyReceiver, refreshing, UserHandle.myUserId());
    }

    /**
     * Returns the DeviceAdminInfo as defined by the administrator's package info &amp; meta-data
     * @hide
     */
    public DeviceAdminInfo getAdminInfo(@NonNull ComponentName cn) {
        ActivityInfo ai;
        try {
            ai = mContext.getPackageManager().getReceiverInfo(cn,
                    PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Unable to retrieve device policy " + cn, e);
            return null;
        }

        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = ai;

        try {
            return new DeviceAdminInfo(mContext, ri);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Unable to parse device policy " + cn, e);
            return null;
        } catch (IOException e) {
            Log.w(TAG, "Unable to parse device policy " + cn, e);
            return null;
        }
    }

    /**
     * @hide
     */
    public void getRemoveWarning(@Nullable ComponentName admin, RemoteCallback result) {
        if (mService != null) {
            try {
                mService.getRemoveWarning(admin, result, UserHandle.myUserId());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void setActivePasswordState(int quality, int length, int letters, int uppercase,
            int lowercase, int numbers, int symbols, int nonletter, int userHandle) {
        if (mService != null) {
            try {
                mService.setActivePasswordState(quality, length, letters, uppercase, lowercase,
                        numbers, symbols, nonletter, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void reportFailedPasswordAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportFailedPasswordAttempt(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     */
    public void reportSuccessfulPasswordAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportSuccessfulPasswordAttempt(userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * @hide
     * Sets the given package as the device owner.
     * Same as {@link #setDeviceOwner(String, String)} but without setting a device owner name.
     * @param packageName the package name of the application to be registered as the device owner.
     * @return whether the package was successfully registered as the device owner.
     * @throws IllegalArgumentException if the package name is null or invalid
     * @throws IllegalStateException If the preconditions mentioned are not met.
     */
    public boolean setDeviceOwner(String packageName) throws IllegalArgumentException,
            IllegalStateException {
        return setDeviceOwner(packageName, null);
    }

    /**
     * @hide
     * Sets the given package as the device owner. The package must already be installed. There
     * must not already be a device owner.
     * Only apps with the MANAGE_PROFILE_AND_DEVICE_OWNERS permission and the shell uid can call
     * this method.
     * Calling this after the setup phase of the primary user has completed is allowed only if
     * the caller is the shell uid, and there are no additional users and no accounts.
     * @param packageName the package name of the application to be registered as the device owner.
     * @param ownerName the human readable name of the institution that owns this device.
     * @return whether the package was successfully registered as the device owner.
     * @throws IllegalArgumentException if the package name is null or invalid
     * @throws IllegalStateException If the preconditions mentioned are not met.
     */
    public boolean setDeviceOwner(String packageName, String ownerName)
            throws IllegalArgumentException, IllegalStateException {
        if (mService != null) {
            try {
                return mService.setDeviceOwner(packageName, ownerName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to set device owner");
            }
        }
        return false;
    }

    /**
     * Used to determine if a particular package has been registered as a Device Owner app.
     * A device owner app is a special device admin that cannot be deactivated by the user, once
     * activated as a device admin. It also cannot be uninstalled. To check whether a particular
     * package is currently registered as the device owner app, pass in the package name from
     * {@link Context#getPackageName()} to this method.<p/>This is useful for device
     * admin apps that want to check whether they are also registered as the device owner app. The
     * exact mechanism by which a device admin app is registered as a device owner app is defined by
     * the setup process.
     * @param packageName the package name of the app, to compare with the registered device owner
     * app, if any.
     * @return whether or not the package is registered as the device owner app.
     */
    public boolean isDeviceOwnerApp(String packageName) {
        if (mService != null) {
            try {
                return mService.isDeviceOwner(packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to check device owner");
            }
        }
        return false;
    }

    /**
     * @hide
     * Redirect to isDeviceOwnerApp.
     */
    public boolean isDeviceOwner(String packageName) {
        return isDeviceOwnerApp(packageName);
    }

    /**
     * Clears the current device owner.  The caller must be the device owner.
     *
     * This function should be used cautiously as once it is called it cannot
     * be undone.  The device owner can only be set as a part of device setup
     * before setup completes.
     *
     * @param packageName The package name of the device owner.
     */
    public void clearDeviceOwnerApp(String packageName) {
        if (mService != null) {
            try {
                mService.clearDeviceOwner(packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to clear device owner");
            }
        }
    }

    /** @hide */
    @SystemApi
    public String getDeviceOwner() {
        if (mService != null) {
            try {
                return mService.getDeviceOwner();
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get device owner");
            }
        }
        return null;
    }

    /** @hide */
    public String getDeviceOwnerName() {
        if (mService != null) {
            try {
                return mService.getDeviceOwnerName();
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get device owner");
            }
        }
        return null;
    }

    /**
     * @hide
     * Sets the given component as the device initializer. The package must already be installed and
     * set as an active device administrator, and there must not be an existing device initializer,
     * for this call to succeed. This method can only be called by an app holding the
     * MANAGE_DEVICE_ADMINS permission before the device is provisioned or by a device owner app. A
     * device initializer app is granted device owner privileges during device initialization and
     * profile owner privileges during secondary user initialization.
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if not called by the device owner.
     * @param initializer Which {@link DeviceAdminReceiver} to make device initializer.
     * @return whether the component was successfully registered as the device initializer.
     * @throws IllegalArgumentException if the componentname is null or invalid
     * @throws IllegalStateException if the caller is not device owner or the device has
     *         already been provisioned or a device initializer already exists.
     */
    public boolean setDeviceInitializer(@Nullable ComponentName admin,
            @NonNull ComponentName initializer)
            throws IllegalArgumentException, IllegalStateException {
        if (mService != null) {
            try {
                return mService.setDeviceInitializer(admin, initializer);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to set device initializer");
            }
        }
        return false;
    }

    /**
     * @hide
     * Used to determine if a particular package has been registered as the device initializer.
     *
     * @param packageName the package name of the app, to compare with the registered device
     *        initializer app, if any.
     * @return whether or not the caller is registered as the device initializer app.
     */
    public boolean isDeviceInitializerApp(String packageName) {
        if (mService != null) {
            try {
                return mService.isDeviceInitializer(packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to check device initializer");
            }
        }
        return false;
    }

    /**
     * @hide
     * Removes the device initializer, so that it will not be invoked on user initialization for any
     * subsequently created users. This method can be called by either the device owner or device
     * initializer itself. The caller must be an active administrator.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     */
    public void clearDeviceInitializerApp(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                mService.clearDeviceInitializer(admin);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to clear device initializer");
            }
        }
    }

    /**
     * @hide
     * Gets the device initializer of the system.
     *
     * @return the package name of the device initializer.
     */
    @SystemApi
    public String getDeviceInitializerApp() {
        if (mService != null) {
            try {
                return mService.getDeviceInitializer();
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get device initializer");
            }
        }
        return null;
    }

    /**
     * @hide
     * Gets the device initializer component of the system.
     *
     * @return the component name of the device initializer.
     */
    @SystemApi
    public ComponentName getDeviceInitializerComponent() {
        if (mService != null) {
            try {
                return mService.getDeviceInitializerComponent();
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get device initializer");
            }
        }
        return null;
    }


    /**
     * @hide
     * Sets the enabled state of the user. A user should be enabled only once it is ready to
     * be used.
     *
     * <p>Device initializer must call this method to mark the user as functional.
     * Only the device initializer agent can call this.
     *
     * <p>When the user is enabled, if the device initializer is not also the device owner, the
     * device initializer will no longer have elevated permissions to call methods in this class.
     * Additionally, it will be removed as an active administrator and its
     * {@link DeviceAdminReceiver} will be disabled.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return whether the user is now enabled.
     */
    public boolean setUserEnabled(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                return mService.setUserEnabled(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * @hide
     * @deprecated Use #ACTION_SET_PROFILE_OWNER
     * Sets the given component as an active admin and registers the package as the profile
     * owner for this user. The package must already be installed and there shouldn't be
     * an existing profile owner registered for this user. Also, this method must be called
     * before the user setup has been completed.
     * <p>
     * This method can only be called by system apps that hold MANAGE_USERS permission and
     * MANAGE_DEVICE_ADMINS permission.
     * @param admin The component to register as an active admin and profile owner.
     * @param ownerName The user-visible name of the entity that is managing this user.
     * @return whether the admin was successfully registered as the profile owner.
     * @throws IllegalArgumentException if packageName is null, the package isn't installed, or
     *         the user has already been set up.
     */
    @SystemApi
    public boolean setActiveProfileOwner(@NonNull ComponentName admin, @Deprecated String ownerName)
            throws IllegalArgumentException {
        if (mService != null) {
            try {
                final int myUserId = UserHandle.myUserId();
                mService.setActiveAdmin(admin, false, myUserId);
                return mService.setProfileOwner(admin, ownerName, myUserId);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to set profile owner " + re);
                throw new IllegalArgumentException("Couldn't set profile owner.", re);
            }
        }
        return false;
    }

    /**
     * @hide
     * Clears the active profile owner and removes all user restrictions. The caller must
     * be from the same package as the active profile owner for this user, otherwise a
     * SecurityException will be thrown.
     *
     * @param admin The component to remove as the profile owner.
     * @return
     */
    @SystemApi
    public void clearProfileOwner(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                mService.clearProfileOwner(admin);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to clear profile owner " + admin + re);
            }
        }
    }

    /**
     * @hide
     * Checks whether the user was already setup.
     */
    public boolean hasUserSetupCompleted() {
        if (mService != null) {
            try {
                return mService.hasUserSetupCompleted();
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to check whether user setup has completed");
            }
        }
        return true;
    }

    /**
     * @hide
     * Sets the given component as the profile owner of the given user profile. The package must
     * already be installed. There must not already be a profile owner for this user.
     * Only apps with the MANAGE_PROFILE_AND_DEVICE_OWNERS permission and the shell uid can call
     * this method.
     * Calling this after the setup phase of the specified user has completed is allowed only if:
     * - the caller is SYSTEM_UID.
     * - or the caller is the shell uid, and there are no accounts on the specified user.
     * @param admin the component name to be registered as profile owner.
     * @param ownerName the human readable name of the organisation associated with this DPM.
     * @param userHandle the userId to set the profile owner for.
     * @return whether the component was successfully registered as the profile owner.
     * @throws IllegalArgumentException if admin is null, the package isn't installed, or the
     * preconditions mentioned are not met.
     */
    public boolean setProfileOwner(@NonNull ComponentName admin, @Deprecated String ownerName,
            int userHandle) throws IllegalArgumentException {
        if (admin == null) {
            throw new NullPointerException("admin cannot be null");
        }
        if (mService != null) {
            try {
                if (ownerName == null) {
                    ownerName = "";
                }
                return mService.setProfileOwner(admin, ownerName, userHandle);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to set profile owner", re);
                throw new IllegalArgumentException("Couldn't set profile owner.", re);
            }
        }
        return false;
    }

    /**
     * Sets the enabled state of the profile. A profile should be enabled only once it is ready to
     * be used. Only the profile owner can call this.
     *
     * @see #isProfileOwnerApp
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     */
    public void setProfileEnabled(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                mService.setProfileEnabled(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Sets the name of the profile. In the device owner case it sets the name of the user
     * which it is called from. Only a profile owner or device owner can call this. If this is
     * never called by the profile or device owner, the name will be set to default values.
     *
     * @see #isProfileOwnerApp
     * @see #isDeviceOwnerApp
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associate with.
     * @param profileName The name of the profile.
     */
    public void setProfileName(@NonNull ComponentName admin, String profileName) {
        if (mService != null) {
            try {
                mService.setProfileName(admin, profileName);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Used to determine if a particular package is registered as the profile owner for the
     * current user. A profile owner is a special device admin that has additional privileges
     * within the profile.
     *
     * @param packageName The package name of the app to compare with the registered profile owner.
     * @return Whether or not the package is registered as the profile owner.
     */
    public boolean isProfileOwnerApp(String packageName) {
        if (mService != null) {
            try {
                ComponentName profileOwner = mService.getProfileOwner(
                        Process.myUserHandle().getIdentifier());
                return profileOwner != null
                        && profileOwner.getPackageName().equals(packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to check profile owner");
            }
        }
        return false;
    }

    /**
     * @hide
     * @return the packageName of the owner of the given user profile or {@code null} if no profile
     * owner has been set for that user.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    @SystemApi
    public ComponentName getProfileOwner() throws IllegalArgumentException {
        return getProfileOwnerAsUser(Process.myUserHandle().getIdentifier());
    }

    /**
     * @see #getProfileOwner()
     * @hide
     */
    public ComponentName getProfileOwnerAsUser(final int userId) throws IllegalArgumentException {
        if (mService != null) {
            try {
                return mService.getProfileOwner(userId);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get profile owner");
                throw new IllegalArgumentException(
                        "Requested profile owner for invalid userId", re);
            }
        }
        return null;
    }

    /**
     * @hide
     * @return the human readable name of the organisation associated with this DPM or {@code null}
     *         if one is not set.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    public String getProfileOwnerName() throws IllegalArgumentException {
        if (mService != null) {
            try {
                return mService.getProfileOwnerName(Process.myUserHandle().getIdentifier());
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get profile owner");
                throw new IllegalArgumentException(
                        "Requested profile owner for invalid userId", re);
            }
        }
        return null;
    }

    /**
     * @hide
     * @param user The user for whom to fetch the profile owner name, if any.
     * @return the human readable name of the organisation associated with this profile owner or
     *         null if one is not set.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    @SystemApi
    public String getProfileOwnerNameAsUser(int userId) throws IllegalArgumentException {
        if (mService != null) {
            try {
                return mService.getProfileOwnerName(userId);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get profile owner");
                throw new IllegalArgumentException(
                        "Requested profile owner for invalid userId", re);
            }
        }
        return null;
    }

    /**
     * Called by a profile owner or device owner to add a default intent handler activity for
     * intents that match a certain intent filter. This activity will remain the default intent
     * handler even if the set of potential event handlers for the intent filter changes and if
     * the intent preferences are reset.
     *
     * <p>The default disambiguation mechanism takes over if the activity is not installed
     * (anymore). When the activity is (re)installed, it is automatically reset as default
     * intent handler for the filter.
     *
     * <p>The calling device admin must be a profile owner or device owner. If it is not, a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param filter The IntentFilter for which a default handler is added.
     * @param activity The Activity that is added as default intent handler.
     */
    public void addPersistentPreferredActivity(@NonNull ComponentName admin, IntentFilter filter,
            @NonNull ComponentName activity) {
        if (mService != null) {
            try {
                mService.addPersistentPreferredActivity(admin, filter, activity);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile owner or device owner to remove all persistent intent handler preferences
     * associated with the given package that were set by {@link #addPersistentPreferredActivity}.
     *
     * <p>The calling device admin must be a profile owner. If it is not, a security
     * exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package for which preferences are removed.
     */
    public void clearPackagePersistentPreferredActivities(@NonNull ComponentName admin,
            String packageName) {
        if (mService != null) {
            try {
                mService.clearPackagePersistentPreferredActivities(admin, packageName);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile or device owner to set the application restrictions for a given target
     * application running in the profile.
     *
     * <p>The provided {@link Bundle} consists of key-value pairs, where the types of values may be:
     * <ul>
     * <li>{@code boolean}
     * <li>{@code int}
     * <li>{@code String} or {@code String[]}
     * <li>From {@link android.os.Build.VERSION_CODES#M}, {@code Bundle} or {@code Bundle[]}
     * </ul>
     *
     * <p>The application restrictions are only made visible to the target application and the
     * profile or device owner.
     *
     * <p>If the restrictions are not available yet, but may be applied in the near future,
     * the admin can notify the target application of that by adding
     * {@link UserManager#KEY_RESTRICTIONS_PENDING} to the settings parameter.
     *
     * <p>The calling device admin must be a profile or device owner; if it is not, a security
     * exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to update restricted settings for.
     * @param settings A {@link Bundle} to be parsed by the receiving application, conveying a new
     * set of active restrictions.
     *
     * @see UserManager#KEY_RESTRICTIONS_PENDING
     */
    public void setApplicationRestrictions(@NonNull ComponentName admin, String packageName,
            Bundle settings) {
        if (mService != null) {
            try {
                mService.setApplicationRestrictions(admin, packageName, settings);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Sets a list of configuration features to enable for a TrustAgent component. This is meant
     * to be used in conjunction with {@link #KEYGUARD_DISABLE_TRUST_AGENTS}, which disables all
     * trust agents but those enabled by this function call. If flag
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} is not set, then this call has no effect.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call
     * this method; if not, a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param target Component name of the agent to be enabled.
     * @param configuration TrustAgent-specific feature bundle. If null for any admin, agent
     * will be strictly disabled according to the state of the
     *  {@link #KEYGUARD_DISABLE_TRUST_AGENTS} flag.
     * <p>If {@link #KEYGUARD_DISABLE_TRUST_AGENTS} is set and options is not null for all admins,
     * then it's up to the TrustAgent itself to aggregate the values from all device admins.
     * <p>Consult documentation for the specific TrustAgent to determine legal options parameters.
     */
    public void setTrustAgentConfiguration(@NonNull ComponentName admin,
            @NonNull ComponentName target, PersistableBundle configuration) {
        if (mService != null) {
            try {
                mService.setTrustAgentConfiguration(admin, target, configuration);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Gets configuration for the given trust agent based on aggregating all calls to
     * {@link #setTrustAgentConfiguration(ComponentName, ComponentName, PersistableBundle)} for
     * all device admins.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. If null,
     * this function returns a list of configurations for all admins that declare
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS}. If any admin declares
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} but doesn't call
     * {@link #setTrustAgentConfiguration(ComponentName, ComponentName, PersistableBundle)}
     * for this {@param agent} or calls it with a null configuration, null is returned.
     * @param agent Which component to get enabled features for.
     * @return configuration for the given trust agent.
     */
    public List<PersistableBundle> getTrustAgentConfiguration(@Nullable ComponentName admin,
            @NonNull ComponentName agent) {
        return getTrustAgentConfiguration(admin, agent, UserHandle.myUserId());
    }

    /** @hide per-user version */
    public List<PersistableBundle> getTrustAgentConfiguration(@Nullable ComponentName admin,
            @NonNull ComponentName agent, int userHandle) {
        if (mService != null) {
            try {
                return mService.getTrustAgentConfiguration(admin, agent, userHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return new ArrayList<PersistableBundle>(); // empty list
    }

    /**
     * Called by a profile owner of a managed profile to set whether caller-Id information from
     * the managed profile will be shown in the parent profile, for incoming calls.
     *
     * <p>The calling device admin must be a profile owner. If it is not, a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled If true caller-Id information in the managed profile is not displayed.
     */
    public void setCrossProfileCallerIdDisabled(@NonNull ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setCrossProfileCallerIdDisabled(admin, disabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to determine whether or not caller-Id
     * information has been disabled.
     *
     * <p>The calling device admin must be a profile owner. If it is not, a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     */
    public boolean getCrossProfileCallerIdDisabled(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getCrossProfileCallerIdDisabled(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Determine whether or not caller-Id information has been disabled.
     *
     * @param userHandle The user for whom to check the caller-id permission
     * @hide
     */
    public boolean getCrossProfileCallerIdDisabled(UserHandle userHandle) {
        if (mService != null) {
            try {
                return mService.getCrossProfileCallerIdDisabledForUser(userHandle.getIdentifier());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Start Quick Contact on the managed profile for the current user, if the policy allows.
     * @hide
     */
    public void startManagedQuickContact(String actualLookupKey, long actualContactId,
            Intent originalIntent) {
        if (mService != null) {
            try {
                mService.startManagedQuickContact(
                        actualLookupKey, actualContactId, originalIntent);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to set whether bluetooth
     * devices can access enterprise contacts.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a
     * security exception will be thrown.
     * <p>
     * This API works on managed profile only.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param disabled If true, bluetooth devices cannot access enterprise
     *            contacts.
     */
    public void setBluetoothContactSharingDisabled(@NonNull ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setBluetoothContactSharingDisabled(admin, disabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to determine whether or
     * not Bluetooth devices cannot access enterprise contacts.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a
     * security exception will be thrown.
     * <p>
     * This API works on managed profile only.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     */
    public boolean getBluetoothContactSharingDisabled(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getBluetoothContactSharingDisabled(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return true;
    }

    /**
     * Determine whether or not Bluetooth devices cannot access contacts.
     * <p>
     * This API works on managed profile UserHandle only.
     *
     * @param userHandle The user for whom to check the caller-id permission
     * @hide
     */
    public boolean getBluetoothContactSharingDisabled(UserHandle userHandle) {
        if (mService != null) {
            try {
                return mService.getBluetoothContactSharingDisabledForUser(userHandle
                        .getIdentifier());
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return true;
    }

    /**
     * Called by the profile owner of a managed profile so that some intents sent in the managed
     * profile can also be resolved in the parent, or vice versa.
     * Only activity intents are supported.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param filter The {@link IntentFilter} the intent has to match to be also resolved in the
     * other profile
     * @param flags {@link DevicePolicyManager#FLAG_MANAGED_CAN_ACCESS_PARENT} and
     * {@link DevicePolicyManager#FLAG_PARENT_CAN_ACCESS_MANAGED} are supported.
     */
    public void addCrossProfileIntentFilter(@NonNull ComponentName admin, IntentFilter filter, int flags) {
        if (mService != null) {
            try {
                mService.addCrossProfileIntentFilter(admin, filter, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to remove the cross-profile intent filters
     * that go from the managed profile to the parent, or from the parent to the managed profile.
     * Only removes those that have been set by the profile owner.
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     */
    public void clearCrossProfileIntentFilters(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                mService.clearCrossProfileIntentFilters(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile or device owner to set the permitted accessibility services. When
     * set by a device owner or profile owner the restriction applies to all profiles of the
     * user the device owner or profile owner is an admin for.
     *
     * By default the user can use any accessiblity service. When zero or more packages have
     * been added, accessiblity services that are not in the list and not part of the system
     * can not be enabled by the user.
     *
     * <p> Calling with a null value for the list disables the restriction so that all services
     * can be used, calling with an empty list only allows the builtin system's services.
     *
     * <p> System accesibility services are always available to the user the list can't modify
     * this.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames List of accessibility service package names.
     *
     * @return true if setting the restriction succeeded. It fail if there is
     * one or more non-system accessibility services enabled, that are not in the list.
     */
    public boolean setPermittedAccessibilityServices(@NonNull ComponentName admin,
            List<String> packageNames) {
        if (mService != null) {
            try {
                return mService.setPermittedAccessibilityServices(admin, packageNames);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Returns the list of permitted accessibility services set by this device or profile owner.
     *
     * <p>An empty list means no accessibility services except system services are allowed.
     * Null means all accessibility services are allowed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return List of accessiblity service package names.
     */
    public List<String> getPermittedAccessibilityServices(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPermittedAccessibilityServices(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Returns the list of accessibility services permitted by the device or profiles
     * owners of this user.
     *
     * <p>Null means all accessibility services are allowed, if a non-null list is returned
     * it will contain the intersection of the permitted lists for any device or profile
     * owners that apply to this user. It will also include any system accessibility services.
     *
     * @param userId which user to check for.
     * @return List of accessiblity service package names.
     * @hide
     */
     @SystemApi
     public List<String> getPermittedAccessibilityServices(int userId) {
        if (mService != null) {
            try {
                return mService.getPermittedAccessibilityServicesForUser(userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
     }

    /**
     * Called by a profile or device owner to set the permitted input methods services. When
     * set by a device owner or profile owner the restriction applies to all profiles of the
     * user the device owner or profile owner is an admin for.
     *
     * By default the user can use any input method. When zero or more packages have
     * been added, input method that are not in the list and not part of the system
     * can not be enabled by the user.
     *
     * This method will fail if it is called for a admin that is not for the foreground user
     * or a profile of the foreground user.
     *
     * <p> Calling with a null value for the list disables the restriction so that all input methods
     * can be used, calling with an empty list disables all but the system's own input methods.
     *
     * <p> System input methods are always available to the user this method can't modify this.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames List of input method package names.
     * @return true if setting the restriction succeeded. It will fail if there are
     *     one or more non-system input methods currently enabled that are not in
     *     the packageNames list.
     */
    public boolean setPermittedInputMethods(@NonNull ComponentName admin, List<String> packageNames) {
        if (mService != null) {
            try {
                return mService.setPermittedInputMethods(admin, packageNames);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }


    /**
     * Returns the list of permitted input methods set by this device or profile owner.
     *
     * <p>An empty list means no input methods except system input methods are allowed.
     * Null means all input methods are allowed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return List of input method package names.
     */
    public List<String> getPermittedInputMethods(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPermittedInputMethods(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Returns the list of input methods permitted by the device or profiles
     * owners of the current user.
     *
     * <p>Null means all input methods are allowed, if a non-null list is returned
     * it will contain the intersection of the permitted lists for any device or profile
     * owners that apply to this user. It will also include any system input methods.
     *
     * @return List of input method package names.
     * @hide
     */
    @SystemApi
    public List<String> getPermittedInputMethodsForCurrentUser() {
        if (mService != null) {
            try {
                return mService.getPermittedInputMethodsForCurrentUser();
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Called by a device owner to create a user with the specified name. The UserHandle returned
     * by this method should not be persisted as user handles are recycled as users are removed and
     * created. If you need to persist an identifier for this user, use
     * {@link UserManager#getSerialNumberForUser}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param name the user's name
     * @see UserHandle
     * @return the {@link android.os.UserHandle} object for the created user, or {@code null} if the
     *         user could not be created.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#M}
     */
    @Deprecated
    public UserHandle createUser(@NonNull ComponentName admin, String name) {
        try {
            return mService.createUser(admin, name);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not create a user", re);
        }
        return null;
    }

    /**
     * Called by a device owner to create a user with the specified name. The UserHandle returned
     * by this method should not be persisted as user handles are recycled as users are removed and
     * created. If you need to persist an identifier for this user, use
     * {@link UserManager#getSerialNumberForUser}.  The new user will be started in the background
     * immediately.
     *
     * <p> profileOwnerComponent is the {@link DeviceAdminReceiver} to be the profile owner as well
     * as registered as an active admin on the new user.  The profile owner package will be
     * installed on the new user if it already is installed on the device.
     *
     * <p>If the optionalInitializeData is not null, then the extras will be passed to the
     * profileOwnerComponent when onEnable is called.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param name the user's name
     * @param ownerName the human readable name of the organisation associated with this DPM.
     * @param profileOwnerComponent The {@link DeviceAdminReceiver} that will be an active admin on
     *      the user.
     * @param adminExtras Extras that will be passed to onEnable of the admin receiver
     *      on the new user.
     * @see UserHandle
     * @return the {@link android.os.UserHandle} object for the created user, or {@code null} if the
     *         user could not be created.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#M}
     */
    @Deprecated
    public UserHandle createAndInitializeUser(@NonNull ComponentName admin, String name,
            String ownerName, @NonNull ComponentName profileOwnerComponent, Bundle adminExtras) {
        try {
            return mService.createAndInitializeUser(admin, name, ownerName, profileOwnerComponent,
                    adminExtras);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not create a user", re);
        }
        return null;
    }

    /**
     * Called by a device owner to remove a user and all associated data. The primary user can
     * not be removed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to remove.
     * @return {@code true} if the user was removed, {@code false} otherwise.
     */
    public boolean removeUser(@NonNull ComponentName admin, UserHandle userHandle) {
        try {
            return mService.removeUser(admin, userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not remove user ", re);
            return false;
        }
    }

    /**
     * Called by a device owner to switch the specified user to the foreground.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to switch to; null will switch to primary.
     * @return {@code true} if the switch was successful, {@code false} otherwise.
     *
     * @see Intent#ACTION_USER_FOREGROUND
     */
    public boolean switchUser(@NonNull ComponentName admin, @Nullable UserHandle userHandle) {
        try {
            return mService.switchUser(admin, userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not switch user ", re);
            return false;
        }
    }

    /**
     * Called by a profile or device owner to get the application restrictions for a given target
     * application running in the profile.
     *
     * <p>The calling device admin must be a profile or device owner; if it is not, a security
     * exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to fetch restricted settings of.
     * @return {@link Bundle} of settings corresponding to what was set last time
     * {@link DevicePolicyManager#setApplicationRestrictions} was called, or an empty {@link Bundle}
     * if no restrictions have been set.
     */
    public Bundle getApplicationRestrictions(@NonNull ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.getApplicationRestrictions(admin, packageName);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * Called by a profile or device owner to set a user restriction specified by the key.
     * <p>
     * The calling device admin must be a profile or device owner; if it is not,
     * a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param key The key of the restriction. See the constants in
     *            {@link android.os.UserManager} for the list of keys.
     */
    public void addUserRestriction(@NonNull ComponentName admin, String key) {
        if (mService != null) {
            try {
                mService.setUserRestriction(admin, key, true);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by a profile or device owner to clear a user restriction specified by the key.
     * <p>
     * The calling device admin must be a profile or device owner; if it is not,
     * a security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated
     *            with.
     * @param key The key of the restriction. See the constants in
     *            {@link android.os.UserManager} for the list of keys.
     */
    public void clearUserRestriction(@NonNull ComponentName admin, String key) {
        if (mService != null) {
            try {
                mService.setUserRestriction(admin, key, false);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by profile or device owners to hide or unhide packages. When a package is hidden it
     * is unavailable for use, but the data and actual package file remain.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to hide or unhide.
     * @param hidden {@code true} if the package should be hidden, {@code false} if it should be
     *                 unhidden.
     * @return boolean Whether the hidden setting of the package was successfully updated.
     */
    public boolean setApplicationHidden(@NonNull ComponentName admin, String packageName,
            boolean hidden) {
        if (mService != null) {
            try {
                return mService.setApplicationHidden(admin, packageName, hidden);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by profile or device owners to determine if a package is hidden.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package to retrieve the hidden status of.
     * @return boolean {@code true} if the package is hidden, {@code false} otherwise.
     */
    public boolean isApplicationHidden(@NonNull ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.isApplicationHidden(admin, packageName);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by profile or device owners to re-enable a system app that was disabled by default
     * when the user was initialized.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package to be re-enabled in the current profile.
     */
    public void enableSystemApp(@NonNull ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                mService.enableSystemApp(admin, packageName);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to install package: " + packageName);
            }
        }
    }

    /**
     * Called by profile or device owners to re-enable system apps by intent that were disabled
     * by default when the user was initialized.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param intent An intent matching the app(s) to be installed. All apps that resolve for this
     *               intent will be re-enabled in the current profile.
     * @return int The number of activities that matched the intent and were installed.
     */
    public int enableSystemApp(@NonNull ComponentName admin, Intent intent) {
        if (mService != null) {
            try {
                return mService.enableSystemAppWithIntent(admin, intent);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to install packages matching filter: " + intent);
            }
        }
        return 0;
    }

    /**
     * Called by a device owner or profile owner to disable account management for a specific type
     * of account.
     *
     * <p>The calling device admin must be a device owner or profile owner. If it is not, a
     * security exception will be thrown.
     *
     * <p>When account management is disabled for an account type, adding or removing an account
     * of that type will not be possible.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param accountType For which account management is disabled or enabled.
     * @param disabled The boolean indicating that account management will be disabled (true) or
     * enabled (false).
     */
    public void setAccountManagementDisabled(@NonNull ComponentName admin, String accountType,
            boolean disabled) {
        if (mService != null) {
            try {
                mService.setAccountManagementDisabled(admin, accountType, disabled);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Gets the array of accounts for which account management is disabled by the profile owner.
     *
     * <p> Account management can be disabled/enabled by calling
     * {@link #setAccountManagementDisabled}.
     *
     * @return a list of account types for which account management has been disabled.
     *
     * @see #setAccountManagementDisabled
     */
    public String[] getAccountTypesWithManagementDisabled() {
        return getAccountTypesWithManagementDisabledAsUser(UserHandle.myUserId());
    }

    /**
     * @see #getAccountTypesWithManagementDisabled()
     * @hide
     */
    public String[] getAccountTypesWithManagementDisabledAsUser(int userId) {
        if (mService != null) {
            try {
                return mService.getAccountTypesWithManagementDisabledAsUser(userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }

        return null;
    }

    /**
     * Sets which packages may enter lock task mode.
     *
     * <p>Any packages that shares uid with an allowed package will also be allowed
     * to activate lock task.
     *
     * From {@link android.os.Build.VERSION_CODES#M} removing packages from the lock task
     * package list results in locked tasks belonging to those packages to be finished.
     *
     * This function can only be called by the device owner.
     * @param packages The list of packages allowed to enter lock task mode
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     *
     * @see Activity#startLockTask()
     * @see DeviceAdminReceiver#onLockTaskModeEntering(Context, Intent, String)
     * @see DeviceAdminReceiver#onLockTaskModeExiting(Context, Intent)
     * @see UserManager#DISALLOW_CREATE_WINDOWS
     */
    public void setLockTaskPackages(@NonNull ComponentName admin, String[] packages)
            throws SecurityException {
        if (mService != null) {
            try {
                mService.setLockTaskPackages(admin, packages);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * This function returns the list of packages allowed to start the lock task mode.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @hide
     */
    public String[] getLockTaskPackages(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getLockTaskPackages(admin);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return null;
    }

    /**
     * This function lets the caller know whether the given component is allowed to start the
     * lock task mode.
     * @param pkg The package to check
     */
    public boolean isLockTaskPermitted(String pkg) {
        if (mService != null) {
            try {
                return mService.isLockTaskPermitted(pkg);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
        return false;
    }

    /**
     * Called by device owners to update {@link Settings.Global} settings. Validation that the value
     * of the setting is in the correct form for the setting type should be performed by the caller.
     * <p>The settings that can be updated with this method are:
     * <ul>
     * <li>{@link Settings.Global#ADB_ENABLED}</li>
     * <li>{@link Settings.Global#AUTO_TIME}</li>
     * <li>{@link Settings.Global#AUTO_TIME_ZONE}</li>
     * <li>{@link Settings.Global#DATA_ROAMING}</li>
     * <li>{@link Settings.Global#USB_MASS_STORAGE_ENABLED}</li>
     * <li>{@link Settings.Global#WIFI_SLEEP_POLICY}</li>
     * <li>{@link Settings.Global#STAY_ON_WHILE_PLUGGED_IN}
     *   This setting is only available from {@link android.os.Build.VERSION_CODES#M} onwards
     *   and can only be set if {@link #setMaximumTimeToLock} is not used to set a timeout.</li>
     * <li>{@link Settings.Global#WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN}</li>
     *   This setting is only available from {@link android.os.Build.VERSION_CODES#M} onwards.
     *   </li>
     * </ul>
     * <p>Changing the following settings has no effect as of
     * {@link android.os.Build.VERSION_CODES#M}:
     * <ul>
     * <li>{@link Settings.Global#BLUETOOTH_ON}.
     *   Use {@link android.bluetooth.BluetoothAdapter#enable()} and
     *   {@link android.bluetooth.BluetoothAdapter#disable()} instead.</li>
     * <li>{@link Settings.Global#DEVELOPMENT_SETTINGS_ENABLED}</li>
     * <li>{@link Settings.Global#MODE_RINGER}.
     *   Use {@link android.media.AudioManager#setRingerMode(int)} instead.</li>
     * <li>{@link Settings.Global#NETWORK_PREFERENCE}</li>
     * <li>{@link Settings.Global#WIFI_ON}.
     *   Use {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)} instead.</li>
     * </ul>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     */
    public void setGlobalSetting(@NonNull ComponentName admin, String setting, String value) {
        if (mService != null) {
            try {
                mService.setGlobalSetting(admin, setting, value);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Called by profile or device owners to update {@link Settings.Secure} settings. Validation
     * that the value of the setting is in the correct form for the setting type should be performed
     * by the caller.
     * <p>The settings that can be updated by a profile or device owner with this method are:
     * <ul>
     * <li>{@link Settings.Secure#DEFAULT_INPUT_METHOD}</li>
     * <li>{@link Settings.Secure#INSTALL_NON_MARKET_APPS}</li>
     * <li>{@link Settings.Secure#SKIP_FIRST_USE_HINTS}</li>
     * </ul>
     * <p>A device owner can additionally update the following settings:
     * <ul>
     * <li>{@link Settings.Secure#LOCATION_MODE}</li>
     * </ul>
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     */
    public void setSecureSetting(@NonNull ComponentName admin, String setting, String value) {
        if (mService != null) {
            try {
                mService.setSecureSetting(admin, setting, value);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed talking with device policy service", e);
            }
        }
    }

    /**
     * Designates a specific service component as the provider for
     * making permission requests of a local or remote administrator of the user.
     * <p/>
     * Only a profile owner can designate the restrictions provider.
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param provider The component name of the service that implements
     * {@link RestrictionsReceiver}. If this param is null,
     * it removes the restrictions provider previously assigned.
     */
    public void setRestrictionsProvider(@NonNull ComponentName admin,
            @Nullable ComponentName provider) {
        if (mService != null) {
            try {
                mService.setRestrictionsProvider(admin, provider);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to set permission provider on device policy service");
            }
        }
    }

    /**
     * Called by profile or device owners to set the master volume mute on or off.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param on {@code true} to mute master volume, {@code false} to turn mute off.
     */
    public void setMasterVolumeMuted(@NonNull ComponentName admin, boolean on) {
        if (mService != null) {
            try {
                mService.setMasterVolumeMuted(admin, on);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to setMasterMute on device policy service");
            }
        }
    }

    /**
     * Called by profile or device owners to check whether the master volume mute is on or off.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return {@code true} if master volume is muted, {@code false} if it's not.
     */
    public boolean isMasterVolumeMuted(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                return mService.isMasterVolumeMuted(admin);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to get isMasterMute on device policy service");
            }
        }
        return false;
    }

    /**
     * Called by profile or device owners to change whether a user can uninstall
     * a package.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName package to change.
     * @param uninstallBlocked true if the user shouldn't be able to uninstall the package.
     */
    public void setUninstallBlocked(@NonNull ComponentName admin, String packageName,
            boolean uninstallBlocked) {
        if (mService != null) {
            try {
                mService.setUninstallBlocked(admin, packageName, uninstallBlocked);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to call block uninstall on device policy service");
            }
        }
    }

    /**
     * Check whether the current user has been blocked by device policy from uninstalling a package.
     * Requires the caller to be the profile owner if checking a specific admin's policy.
     * <p>
     * <strong>Note:</strong> Starting from {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1}, the
     * behavior of this API is changed such that passing {@code null} as the {@code admin}
     * parameter will return if any admin has blocked the uninstallation. Before L MR1, passing
     * {@code null} will cause a NullPointerException to be raised.
     *
     * @param admin The name of the admin component whose blocking policy will be checked, or
     *              {@code null} to check whether any admin has blocked the uninstallation.
     * @param packageName package to check.
     * @return true if uninstallation is blocked.
     */
    public boolean isUninstallBlocked(@Nullable ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.isUninstallBlocked(admin, packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Failed to call block uninstall on device policy service");
            }
        }
        return false;
    }

    /**
     * Called by the profile owner of a managed profile to enable widget providers from a
     * given package to be available in the parent profile. As a result the user will be able to
     * add widgets from the white-listed package running under the profile to a widget
     * host which runs under the parent profile, for example the home screen. Note that
     * a package may have zero or more provider components, where each component
     * provides a different widget type.
     * <p>
     * <strong>Note:</strong> By default no widget provider package is white-listed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package from which widget providers are white-listed.
     * @return Whether the package was added.
     *
     * @see #removeCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #getCrossProfileWidgetProviders(android.content.ComponentName)
     */
    public boolean addCrossProfileWidgetProvider(@NonNull ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.addCrossProfileWidgetProvider(admin, packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Error calling addCrossProfileWidgetProvider", re);
            }
        }
        return false;
    }

    /**
     * Called by the profile owner of a managed profile to disable widget providers from a given
     * package to be available in the parent profile. For this method to take effect the
     * package should have been added via {@link #addCrossProfileWidgetProvider(
     * android.content.ComponentName, String)}.
     * <p>
     * <strong>Note:</strong> By default no widget provider package is white-listed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package from which widget providers are no longer
     *     white-listed.
     * @return Whether the package was removed.
     *
     * @see #addCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #getCrossProfileWidgetProviders(android.content.ComponentName)
     */
    public boolean removeCrossProfileWidgetProvider(@NonNull ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.removeCrossProfileWidgetProvider(admin, packageName);
            } catch (RemoteException re) {
                Log.w(TAG, "Error calling removeCrossProfileWidgetProvider", re);
            }
        }
        return false;
    }

    /**
     * Called by the profile owner of a managed profile to query providers from which packages are
     * available in the parent profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The white-listed package list.
     *
     * @see #addCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #removeCrossProfileWidgetProvider(android.content.ComponentName, String)
     */
    public List<String> getCrossProfileWidgetProviders(@NonNull ComponentName admin) {
        if (mService != null) {
            try {
                List<String> providers = mService.getCrossProfileWidgetProviders(admin);
                if (providers != null) {
                    return providers;
                }
            } catch (RemoteException re) {
                Log.w(TAG, "Error calling getCrossProfileWidgetProviders", re);
            }
        }
        return Collections.emptyList();
    }

    /**
     * Called by profile or device owners to set the current user's photo.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param icon the bitmap to set as the photo.
     */
    public void setUserIcon(@NonNull ComponentName admin, Bitmap icon) {
        try {
            mService.setUserIcon(admin, icon);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not set the user icon ", re);
        }
    }

    /**
     * Called by device owners to set a local system update policy. When a new policy is set,
     * {@link #ACTION_SYSTEM_UPDATE_POLICY_CHANGED} is broadcasted.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. All
     *              components in the device owner package can set system update policies and the
     *              most recent policy takes
     * effect.
     * @param policy the new policy, or {@code null} to clear the current policy.
     * @see SystemUpdatePolicy
     */
    public void setSystemUpdatePolicy(@NonNull ComponentName admin, SystemUpdatePolicy policy) {
        if (mService != null) {
            try {
                mService.setSystemUpdatePolicy(admin, policy);
            } catch (RemoteException re) {
                Log.w(TAG, "Error calling setSystemUpdatePolicy", re);
            }
        }
    }

    /**
     * Retrieve a local system update policy set previously by {@link #setSystemUpdatePolicy}.
     *
     * @return The current policy object, or {@code null} if no policy is set.
     */
    public SystemUpdatePolicy getSystemUpdatePolicy() {
        if (mService != null) {
            try {
                return mService.getSystemUpdatePolicy();
            } catch (RemoteException re) {
                Log.w(TAG, "Error calling getSystemUpdatePolicy", re);
            }
        }
        return null;
    }

    /**
     * Called by a device owner to disable the keyguard altogether.
     *
     * <p>Setting the keyguard to disabled has the same effect as choosing "None" as the screen
     * lock type. However, this call has no effect if a password, pin or pattern is currently set.
     * If a password, pin or pattern is set after the keyguard was disabled, the keyguard stops
     * being disabled.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled {@code true} disables the keyguard, {@code false} reenables it.
     *
     * @return {@code false} if attempting to disable the keyguard while a lock password was in
     * place. {@code true} otherwise.
     */
    public boolean setKeyguardDisabled(@NonNull ComponentName admin, boolean disabled) {
        try {
            return mService.setKeyguardDisabled(admin, disabled);
        } catch (RemoteException re) {
            Log.w(TAG, "Failed talking with device policy service", re);
            return false;
        }
    }

    /**
     * Called by device owner to disable the status bar. Disabling the status bar blocks
     * notifications, quick settings and other screen overlays that allow escaping from
     * a single use device.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled {@code true} disables the status bar, {@code false} reenables it.
     *
     * @return {@code false} if attempting to disable the status bar failed.
     * {@code true} otherwise.
     */
    public boolean setStatusBarDisabled(@NonNull ComponentName admin, boolean disabled) {
        try {
            return mService.setStatusBarDisabled(admin, disabled);
        } catch (RemoteException re) {
            Log.w(TAG, "Failed talking with device policy service", re);
            return false;
        }
    }

    /**
     * Callable by the system update service to notify device owners about pending updates.
     * The caller must hold {@link android.Manifest.permission#NOTIFY_PENDING_SYSTEM_UPDATE}
     * permission.
     *
     * @param updateReceivedTime The time as given by {@link System#currentTimeMillis()} indicating
     *        when the current pending update was first available. -1 if no update is available.
     * @hide
     */
    @SystemApi
    public void notifyPendingSystemUpdate(long updateReceivedTime) {
        if (mService != null) {
            try {
                mService.notifyPendingSystemUpdate(updateReceivedTime);
            } catch (RemoteException re) {
                Log.w(TAG, "Could not notify device owner about pending system update", re);
            }
        }
    }

    /**
     * Called by profile or device owners to set the default response for future runtime permission
     * requests by applications. The policy can allow for normal operation which prompts the
     * user to grant a permission, or can allow automatic granting or denying of runtime
     * permission requests by an application. This also applies to new permissions declared by app
     * updates. When a permission is denied or granted this way, the effect is equivalent to setting
     * the permission grant state via {@link #setPermissionGrantState}.
     *
     * <p/>As this policy only acts on runtime permission requests, it only applies to applications
     * built with a {@code targetSdkVersion} of {@link android.os.Build.VERSION_CODES#M} or later.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @param policy One of the policy constants {@link #PERMISSION_POLICY_PROMPT},
     * {@link #PERMISSION_POLICY_AUTO_GRANT} and {@link #PERMISSION_POLICY_AUTO_DENY}.
     *
     * @see #setPermissionGrantState
     */
    public void setPermissionPolicy(@NonNull ComponentName admin, int policy) {
        try {
            mService.setPermissionPolicy(admin, policy);
        } catch (RemoteException re) {
            Log.w(TAG, "Failed talking with device policy service", re);
        }
    }

    /**
     * Returns the current runtime permission policy set by the device or profile owner. The
     * default is {@link #PERMISSION_POLICY_PROMPT}.
     * @param admin Which profile or device owner this request is associated with.
     * @return the current policy for future permission requests.
     */
    public int getPermissionPolicy(ComponentName admin) {
        try {
            return mService.getPermissionPolicy(admin);
        } catch (RemoteException re) {
            return PERMISSION_POLICY_PROMPT;
        }
    }

    /**
     * Sets the grant state of a runtime permission for a specific application. The state
     * can be {@link #PERMISSION_GRANT_STATE_DEFAULT default} in which a user can manage it
     * through the UI, {@link #PERMISSION_GRANT_STATE_DENIED denied}, in which the permission
     * is denied and the user cannot manage it through the UI, and {@link
     * #PERMISSION_GRANT_STATE_GRANTED granted} in which the permission is granted and the
     * user cannot manage it through the UI. This might affect all permissions in a
     * group that the runtime permission belongs to. This method can only be called
     * by a profile or device owner.
     *
     * <p/>Setting the grant state to {@link #PERMISSION_GRANT_STATE_DEFAULT default} does not
     * revoke the permission. It retains the previous grant, if any.
     *
     * <p/>Permissions can be granted or revoked only for applications built with a
     * {@code targetSdkVersion} of {@link android.os.Build.VERSION_CODES#M} or later.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @param packageName The application to grant or revoke a permission to.
     * @param permission The permission to grant or revoke.
     * @param grantState The permission grant state which is one of {@link
     *         #PERMISSION_GRANT_STATE_DENIED}, {@link #PERMISSION_GRANT_STATE_DEFAULT},
     *         {@link #PERMISSION_GRANT_STATE_GRANTED},
     * @return whether the permission was successfully granted or revoked.
     *
     * @see #PERMISSION_GRANT_STATE_DENIED
     * @see #PERMISSION_GRANT_STATE_DEFAULT
     * @see #PERMISSION_GRANT_STATE_GRANTED
     */
    public boolean setPermissionGrantState(@NonNull ComponentName admin, String packageName,
            String permission, int grantState) {
        try {
            return mService.setPermissionGrantState(admin, packageName, permission, grantState);
        } catch (RemoteException re) {
            Log.w(TAG, "Failed talking with device policy service", re);
            return false;
        }
    }

    /**
     * Returns the current grant state of a runtime permission for a specific application.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @param packageName The application to check the grant state for.
     * @param permission The permission to check for.
     * @return the current grant state specified by device policy. If the profile or device owner
     * has not set a grant state, the return value is {@link #PERMISSION_GRANT_STATE_DEFAULT}.
     * This does not indicate whether or not the permission is currently granted for the package.
     *
     * <p/>If a grant state was set by the profile or device owner, then the return value will
     * be one of {@link #PERMISSION_GRANT_STATE_DENIED} or {@link #PERMISSION_GRANT_STATE_GRANTED},
     * which indicates if the permission is currently denied or granted.
     *
     * @see #setPermissionGrantState(ComponentName, String, String, int)
     * @see PackageManager#checkPermission(String, String)
     */
    public int getPermissionGrantState(@NonNull ComponentName admin, String packageName,
            String permission) {
        try {
            return mService.getPermissionGrantState(admin, packageName, permission);
        } catch (RemoteException re) {
            Log.w(TAG, "Failed talking with device policy service", re);
            return PERMISSION_GRANT_STATE_DEFAULT;
        }
    }
}
