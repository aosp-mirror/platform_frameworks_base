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

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.LOCK_DEVICE;
import static android.Manifest.permission.MANAGE_DEVICE_ADMINS;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_ACCOUNT_MANAGEMENT;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_APPS_CONTROL;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_CAMERA;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_CERTIFICATES;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_COMMON_CRITERIA_MODE;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_CONTENT_PROTECTION;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_DEFAULT_SMS;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_FACTORY_RESET;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_INPUT_METHODS;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_KEYGUARD;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_LOCK;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_LOCK_TASK;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_MTE;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_ORGANIZATION_IDENTITY;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_PACKAGE_STATE;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_PROFILE_INTERACTION;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_QUERY_SYSTEM_UPDATES;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_RESET_PASSWORD;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_RUNTIME_PERMISSIONS;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_SCREEN_CAPTURE;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_SECURITY_LOGGING;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_STATUS_BAR;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_SUPPORT_MESSAGE;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_SYSTEM_UPDATES;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_USB_DATA_SIGNALLING;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_WIFI;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_WIPE_DATA;
import static android.Manifest.permission.QUERY_ADMIN_POLICY;
import static android.Manifest.permission.QUERY_DEVICE_STOLEN_STATE;
import static android.Manifest.permission.REQUEST_PASSWORD_COMPLEXITY;
import static android.Manifest.permission.SET_TIME;
import static android.Manifest.permission.SET_TIME_ZONE;
import static android.app.admin.DeviceAdminInfo.HEADLESS_DEVICE_OWNER_MODE_UNSUPPORTED;
import static android.app.admin.flags.Flags.FLAG_DEVICE_THEFT_API_ENABLED;
import static android.app.admin.flags.Flags.onboardingBugreportV2Enabled;
import static android.app.admin.flags.Flags.onboardingConsentlessBugreports;
import static android.content.Intent.LOCAL_FLAG_FROM_SYSTEM;
import static android.net.NetworkCapabilities.NET_ENTERPRISE_ID_1;
import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest.permission;
import android.accounts.Account;
import android.annotation.BroadcastBehavior;
import android.annotation.CallbackExecutor;
import android.annotation.ColorInt;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.StringDef;
import android.annotation.SupportsCoexistence;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.Activity;
import android.app.IServiceConnection;
import android.app.KeyguardManager;
import android.app.admin.SecurityLog.SecurityEvent;
import android.app.admin.flags.Flags;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.PrivateDnsConnectivityChecker;
import android.net.ProxyInfo;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IpcDataCache;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManager.UserOperationException;
import android.os.UserManager.UserOperationResult;
import android.provider.CalendarContract;
import android.provider.ContactsContract.Directory;
import android.provider.Settings;
import android.security.AttestedKeyPair;
import android.security.Credentials;
import android.security.KeyChain;
import android.security.KeyChainException;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keystore.AttestationUtils;
import android.security.keystore.KeyAttestationException;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.ParcelableKeyGenParameterSpec;
import android.security.keystore.StrongBoxUnavailableException;
import android.service.restrictions.RestrictionsReceiver;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.net.NetworkUtilsInternal;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.org.conscrypt.TrustedCertificateStore;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Manages device policy and restrictions applied to the user of the device or
 * apps running on the device.
 *
 * <p>This class contains three types of methods:
 * <ol><li>Those aimed at <a href="#managingapps">managing apps</a>
 * <li>Those aimed at the <a href="#roleholder">Device Policy Management Role Holder</a>
 * <li>Those aimed at <a href="#querying">apps which wish to respect device policy</a>
 * </ol>
 *
 * <p>The intended caller for each API is indicated in its Javadoc.
 *
 * <p id="managingapps"><b>Managing Apps</b>
 * <p>Apps can be made capable of setting device policy ("Managing Apps") either by
 * being set as a <a href="#deviceadmin">Device Administrator</a>, being set as a
 * <a href="#devicepolicycontroller">Device Policy Controller</a>, or by holding the
 * appropriate <a href="#permissions">Permissions</a>.
 *
 * <p id="deviceadmin">A <b>Device Administrator</b> is an app which is able to enforce device
 * policies that it has declared in its device admin XML file. An app can prompt the user to give it
 * device administator privileges using the {@link #ACTION_ADD_DEVICE_ADMIN} action.
 *
 * <p>For more information about Device Administration, read the
 * <a href="{@docRoot}guide/topics/admin/device-admin.html">Device Administration</a>
 * developer guide.
 *
 * <p id="devicepolicycontroller">Device Administrator apps can also be recognised as <b>
 * Device Policy Controllers</b>. Device Policy Controllers can be one of
 * two types:
 * <ul>
 * <li>A <i id="deviceowner">Device Owner</i>, which only ever exists on the
 * {@link UserManager#isSystemUser System User} or Main User, is
 * the most powerful type of Device Policy Controller and can affect policy across the device.
 * <li>A <i id="profileowner">Profile Owner<i>, which can exist on any user, can
 * affect policy on the user it is on, and when it is running on
 * {@link UserManager#isProfile a profile} has
 * <a href="#profile-on-parent">limited</a> ability to affect policy on its parent.
 * </ul>
 *
 * <p>Additional capabilities can be provided to Device Policy Controllers in
 * the following circumstances:
 * <ul>
 * <li>A Profile Owner on an <a href="#organization-owned">organization owned</a> device has access
 * to additional abilities, both <a href="#profile-on-parent-organization-owned">affecting policy on the profile's</a>
 * parent and also the profile itself.
 * <li>A Profile Owner running on the {@link UserManager#isSystemUser System User} has access to
 * additional capabilities which affect the {@link UserManager#isSystemUser System User} and
 * also the whole device.
 * <li>A Profile Owner running on an <a href="#affiliated">affiliated</a> user has
 * capabilities similar to that of a <a href="#deviceowner">Device Owner</a>
 * </ul>
 *
 * <p>For more information, see <a href="{@docRoot}work/dpc/build-dpc">Building a Device Policy
 * Controller</a>.
 *
 * <p><a href="#permissions">Permissions</a> are generally only given to apps
 * fulfilling particular key roles on the device (such as managing
 * {@link android.devicelock.DeviceLockManager device locks}).
 *
 * <p id="roleholder"><b>Device Policy Management Role Holder</b>
 * <p>One app on the device fulfills the Device Policy Management Role and is trusted with managing
 * the overall state of Device Policy. This has access to much more powerful methods than
 * <a href="#managingapps">managing apps</a>.
 *
 * <p id="querying"><b>Querying Device Policy</b>
 * <p>In most cases, regular apps do not need to concern themselves with device
 * policy, and restrictions will be enforced automatically. There are some cases
 * where an app may wish to query device policy to provide a better user
 * experience. Only a small number of policies allow apps to query them directly.
 * These APIs will typically have no special required permissions.
 *
 * <p id="managedprovisioning"><b>Managed Provisioning</b>
 * <p>Managed Provisioning is the process of recognising an app as a
 * <a href="#deviceowner">Device Owner</a> or <a href="#profileowner">Profile Owner</a>. It
 * involves presenting education and consent screens to the user to ensure they
 * are aware of the capabilities this grants the <a href="#devicepolicycontroller">Device Policy
 * Controller</a>
 *
 * <p>For more information on provisioning, see <a href="{@docRoot}work/dpc/build-dpc">Building a
 * Device Policy Controller</a>.
 *
 * <p id="managed_profile">A <b>Managed Profile</b> enables data separation. For example to use
 * a device both for personal and corporate usage. The managed profile and its
 * parent share a launcher.
 *
 * <p id="affiliated"><b>Affiliation</b>
 * <p>Using the {@link #setAffiliationIds} method, a
 * <a href="#deviceowner">Device Owner</a> can set a list of affiliation ids for the
 * {@link UserManager#isSystemUser System User}. Any <a href="#profileowner">Profile Owner</a> on
 * the same device can also call {@link #setAffiliationIds} to set affiliation ids
 * for the {@link UserManager user} it is on. When there is the same ID
 * present in both lists, the user is said to be "affiliated" and we can refer to
 * the <a href="#profileowner">Profile Owner</a> as a "profile owner on an affiliated
 * user" or an "affiliated profile owner".
 *
 * Becoming affiliated grants the <a href="#profileowner">Profile Owner</a> capabilities similar to
 * that of the <a href="#deviceowner">Device Owner</a>. It also allows use of the
 * {@link #bindDeviceAdminServiceAsUser} APIs for direct communication between the
 * <a href="#deviceowner">Device Owner</a> and
 * affiliated <a href="#profileowner">Profile Owners</a>.
 *
 * <p id="organization-owned"><b>Organization Owned</b></p>
 * An organization owned device is one which is not owned by the person making use of the device and
 * is instead owned by an organization such as their employer or education provider. These devices
 * are recognised as being organization owned either by the presence of a
 * <a href="#deviceowner">device owner</a> or of a
 * {@link #isOrganizationOwnedDeviceWithManagedProfile profile which has a profile owner is marked
 * as organization owned}.
 *
 * <p id="profile-on-parent-organization-owned">Profile owners running on an
 * <a href="organization-owned">organization owned</a> device can exercise additional capabilities
 * using the {@link #getParentProfileInstance(ComponentName)} API which apply to the parent user.
 * Each API will indicate if it is usable in this way.
 *
 * <p id="automotive"><b>Android Automotive</b>
 * <p>On {@link android.content.pm.PackageManager#FEATURE_AUTOMOTIVE
 * "Android Automotive builds"}, some methods can throw
 * {@link UnsafeStateException "an exception"} if an action is unsafe (for example, if the vehicle
 * is moving). Callers running on
 * {@link android.content.pm.PackageManager#FEATURE_AUTOMOTIVE
 * "Android Automotive builds"} should always check for this exception.
 */

@SystemService(Context.DEVICE_POLICY_SERVICE)
@RequiresFeature(PackageManager.FEATURE_DEVICE_ADMIN)
public class DevicePolicyManager {

    /** @hide */
    public static final String DEPRECATE_USERMANAGERINTERNAL_DEVICEPOLICY_FLAG =
            "deprecate_usermanagerinternal_devicepolicy";
    /** @hide */
    public static final boolean DEPRECATE_USERMANAGERINTERNAL_DEVICEPOLICY_DEFAULT = true;
    /** @hide */
    public static final String ADD_ISFINANCED_DEVICE_FLAG =
            "add-isfinanced-device";
    /** @hide */
    public static final boolean ADD_ISFINANCED_FEVICE_DEFAULT = true;

    private static String TAG = "DevicePolicyManager";

    private final Context mContext;
    private final IDevicePolicyManager mService;
    private final boolean mParentInstance;
    private final DevicePolicyResourcesManager mResourcesManager;

    /** @hide */
    public DevicePolicyManager(Context context, IDevicePolicyManager service) {
        this(context, service, false);
    }

    /** @hide */
    @VisibleForTesting
    protected DevicePolicyManager(Context context, IDevicePolicyManager service,
            boolean parentInstance) {
        mContext = context;
        mService = service;
        mParentInstance = parentInstance;
        mResourcesManager = new DevicePolicyResourcesManager(context, service);
    }

    /**
     * Fetch the current value of mService.  This is used in the binder cache lambda
     * expressions.
     */
    private IDevicePolicyManager getService() {
        return mService;
    }

    /**
     * Fetch the current value of mParentInstance.  This is used in the binder cache
     * lambda expressions.
     */
    private boolean isParentInstance() {
        return mParentInstance;
    }

    /**
     * Fetch the current value of mContext.  This is used in the binder cache lambda
     * expressions.
     */
    private Context getContext() {
        return mContext;
    }

    /** @hide test will override it. */
    @VisibleForTesting
    protected int myUserId() {
        return mContext.getUserId();
    }

    /**
     * Activity action: Starts the provisioning flow which sets up a
     * <a href="#managed-profile">managed profile</a>.
     *
     * <p>It is possible to check if provisioning is allowed or not by querying the method
     * {@link #isProvisioningAllowed(String)}.
     *
     * <p>The intent may contain the following extras:
     *
     * <table>
     *  <thead>
     *      <tr>
     *          <th>Extra</th>
     *          <th></th>
     *          <th>Supported Versions</th>
     *      </tr>
     *  </thead>
     *  <tbody>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE}</td>
     *          <td colspan="2"></td>
     *      </tr>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}</td>
     *          <td></td>
     *          <td>{@link android.os.Build.VERSION_CODES#N}+</td>
     *      </tr>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}</td>
     *          <td colspan="2"></td>
     *      </tr>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_LOGO_URI}</td>
     *          <td colspan="2"></td>
     *      </tr>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_SKIP_USER_CONSENT}</td>
     *          <td colspan="2"><b>Can only be used by an existing device owner trying to create a
     *          managed profile</b></td>
     *      </tr>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION}</td>
     *          <td colspan="2"></td>
     *      </tr>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_DISCLAIMERS}</td>
     *          <td colspan="2"></td>
     *      </tr>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME}</td>
     *          <td>
     *              <b>Required if {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME} is not
     *              specified. Must match the package name of the calling application.</b>
     *          </td>
     *          <td>{@link android.os.Build.VERSION_CODES#LOLLIPOP}+</td>
     *      </tr>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</td>
     *          <td>
     *              <b>Required if {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME} is not
     *              specified. Package name must match the package name of the calling
     *              application.</b>
     *          </td>
     *          <td>{@link android.os.Build.VERSION_CODES#M}+</td>
     *      </tr>
     *      <tr>
     *          <td>{@link #EXTRA_PROVISIONING_ALLOW_OFFLINE}</td>
     *          <td colspan="2">On {@link android.os.Build.VERSION_CODES#TIRAMISU}+, when set to
     *          true this will <b>force</b> offline provisioning instead of allowing it</td>
     *      </tr>
     *  </tbody>
     * </table>
     *
     * <p>When <a href="#managedprovisioning">managed provisioning</a> has completed, broadcasts
     * are sent to the application specified in the provisioning intent. The
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} broadcast is sent in the
     * managed profile and the {@link #ACTION_MANAGED_PROFILE_PROVISIONED} broadcast is sent in
     * the primary profile.
     *
     * <p>From version {@link android.os.Build.VERSION_CODES#O}, when managed provisioning has
     * completed, along with the above broadcast, activity intent
     * {@link #ACTION_PROVISIONING_SUCCESSFUL} will also be sent to the profile owner.
     *
     * <p>If provisioning fails, the managed profile is removed so the device returns to its
     * previous state.
     *
     * <p>If launched with {@link android.app.Activity#startActivityForResult(Intent, int)} a
     * result code of {@link android.app.Activity#RESULT_OK} indicates that the synchronous part of
     * the provisioning flow was successful, although this doesn't guarantee the full flow will
     * succeed. Conversely a result code of {@link android.app.Activity#RESULT_CANCELED} indicates
     * that the user backed-out of provisioning or some precondition for provisioning wasn't met.
     *
     * <p>If a <a href="#roleholder">device policy management role holder</a> updater is present on
     * the device, an internet connection attempt must be made prior to launching this intent.
     */
    // See b/365955253 for additional behaviours of this API.
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_PROFILE
        = "android.app.action.PROVISION_MANAGED_PROFILE";

    /**
     * Activity action: Starts the provisioning flow which sets up a managed user.
     *
     * <p>This intent will typically be sent by a mobile device management application (MDM).
     * Provisioning configures the user as managed user and sets the MDM as the profile
     * owner who has full control over the user. Provisioning can only happen before user setup has
     * been completed. Use {@link #isProvisioningAllowed(String)} to check if provisioning is
     * allowed.
     *
     * <p>The intent contains the following extras:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LOGO_URI}, optional</li>
     * </ul>
     *
     * <p>If provisioning fails, the device returns to its previous state.
     *
     * <p>If launched with {@link android.app.Activity#startActivityForResult(Intent, int)} a
     * result code of {@link android.app.Activity#RESULT_OK} implies that the synchronous part of
     * the provisioning flow was successful, although this doesn't guarantee the full flow will
     * succeed. Conversely a result code of {@link android.app.Activity#RESULT_CANCELED} implies
     * that the user backed-out of provisioning, or some precondition for provisioning wasn't met.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_USER
        = "android.app.action.PROVISION_MANAGED_USER";

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
     * It is possible to check if provisioning is allowed or not by querying the method
     * {@link #isProvisioningAllowed(String)}.
     *
     * <p>The intent contains the following extras:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_LOGO_URI}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DISCLAIMERS}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS}, optional</li>
     * </ul>
     *
     * <p>When device owner provisioning has completed, an intent of the type
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} is broadcast to the
     * device owner.
     *
     * <p>From version {@link android.os.Build.VERSION_CODES#O}, when device owner provisioning has
     * completed, along with the above broadcast, activity intent
     * {@link #ACTION_PROVISIONING_SUCCESSFUL} will also be sent to the device owner.
     *
     * <p>If provisioning fails, the device is factory reset.
     *
     * <p>A result code of {@link android.app.Activity#RESULT_OK} implies that the synchronous part
     * of the provisioning flow was successful, although this doesn't guarantee the full flow will
     * succeed. Conversely a result code of {@link android.app.Activity#RESULT_CANCELED} implies
     * that the user backed-out of provisioning, or some precondition for provisioning wasn't met.
     *
     * @deprecated to support {@link android.os.Build.VERSION_CODES#S} and later, admin apps must
     * implement activities with intent filters for the {@link #ACTION_GET_PROVISIONING_MODE} and
     * {@link #ACTION_ADMIN_POLICY_COMPLIANCE} intent actions; using {@link
     * #ACTION_PROVISION_MANAGED_DEVICE} to start provisioning will cause the provisioning to fail;
     * to additionally support pre-{@link android.os.Build.VERSION_CODES#S}, admin apps must also
     * continue to use this constant.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_MANAGED_DEVICE
        = "android.app.action.PROVISION_MANAGED_DEVICE";

    /**
     * Activity action: launch when user provisioning completed, i.e.
     * {@link #getUserProvisioningState()} returns one of the complete state.
     *
     * <p> Please note that the API behavior is not necessarily consistent across various releases,
     * and devices, as it's contract between SetupWizard and ManagedProvisioning. The default
     * implementation is that ManagedProvisioning launches SetupWizard in NFC provisioning only.
     *
     * <p> The activity must be protected by permission
     * {@link android.Manifest.permission#BIND_DEVICE_ADMIN}, and the process must hold
     * {@link android.Manifest.permission#DISPATCH_PROVISIONING_MESSAGE} to be launched.
     * Only one {@link ComponentName} in the entire system should be enabled, and the rest of the
     * components are not started by this intent.
     *
     * @deprecated Starting from Android 13, the system no longer launches an intent with this
     * action when user provisioning completes.
     * @hide
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_STATE_USER_SETUP_COMPLETE =
            "android.app.action.STATE_USER_SETUP_COMPLETE";

    /**
     * Activity action: Starts the provisioning flow which sets up a managed device.
     *
     * <p>During device owner provisioning, a device admin app is downloaded and set as the owner of
     * the device. A device owner has full control over the device. The device owner can not be
     * modified by the user and the only way of resetting the device is via factory reset.
     *
     * <p>From version {@link android.os.Build.VERSION_CODES#Q}, the admin app can choose
     * whether to set up a fully managed device or a managed profile. For the admin app to support
     * this, it must have an activity with intent filter {@link #ACTION_GET_PROVISIONING_MODE} and
     * another one with intent filter {@link #ACTION_ADMIN_POLICY_COMPLIANCE}. For example:
     * <pre>
     * &lt;activity
     *     android:name=".GetProvisioningModeActivity"
     *     android:label="@string/app_name"
     *     android:permission="android.permission.BIND_DEVICE_ADMIN"&gt;
     *     &lt;intent-filter&gt;
     *         &lt;action
     *             android:name="android.app.action.GET_PROVISIONING_MODE" /&gt;
     *         &lt;category android:name="android.intent.category.DEFAULT" /&gt;
     *     &lt;/intent-filter&gt;
     * &lt;/activity&gt;
     *
     * &lt;activity
     *     android:name=".PolicyComplianceActivity"
     *     android:label="@string/app_name"
     *     android:permission="android.permission.BIND_DEVICE_ADMIN"&gt;
     *     &lt;intent-filter&gt;
     *         &lt;action
     *             android:name="android.app.action.ADMIN_POLICY_COMPLIANCE" /&gt;
     *         &lt;category android:name="android.intent.category.DEFAULT" /&gt;
     *     &lt;/intent-filter&gt;
     * &lt;/activity&gt;</pre>
     *
     * <p>A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p>The provisioning message should be sent to an unprovisioned device.
     *
     * <p>Unlike {@link #ACTION_PROVISION_MANAGED_DEVICE}, the provisioning message can only be sent
     * by a privileged app with the permission
     * {@link android.Manifest.permission#DISPATCH_PROVISIONING_MESSAGE}.
     *
     * <p>If a device policy management role holder (DPMRH) updater is present on the device, an
     * internet connection attempt must be made prior to launching this intent. If internet
     * connection could not be established, provisioning will fail unless {@link
     * #EXTRA_PROVISIONING_ALLOW_OFFLINE} is explicitly set to {@code true}, in which case
     * provisioning will continue without using the DPMRH. If an internet connection has been
     * established, the DPMRH updater will be launched via {@link
     * #ACTION_UPDATE_DEVICE_MANAGEMENT_ROLE_HOLDER}, which will update the DPMRH if it's not
     * present on the device, or if it's present and not valid.
     *
     * <p>A DPMRH is considered valid if it has intent filters for {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}, {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_PROFILE} and {@link
     * #ACTION_ROLE_HOLDER_PROVISION_FINALIZATION}.
     *
     * <p>If a DPMRH is present on the device and valid, the provisioning flow will be deferred to
     * it via the {@link #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent.
     *
     * <p>The provisioning intent contains the following properties:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</li>
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
     * <li>{@link #EXTRA_PROVISIONING_SUPPORT_URL}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ORGANIZATION_NAME}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_USE_MOBILE_DATA}, optional </li>
     * <li>{@link #EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS}, optional - when not used for
     * cloud enrollment, NFC or QR provisioning</li>
     * </ul>
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE =
            "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE";

    /**
     * Activity action: Starts the provisioning flow which sets up a financed device.
     *
     * <p>During financed device provisioning, a device admin app is downloaded and set as the owner
     * of the device. A device owner has full control over the device. The device owner can not be
     * modified by the user.
     *
     * <p>A typical use case would be a device that is bought from the reseller through financing
     * program.
     *
     * <p>An intent with this action can be sent only on an unprovisioned device.
     *
     * <p>Unlike {@link #ACTION_PROVISION_MANAGED_DEVICE}, the provisioning message can only be sent
     * by a privileged app with the permission
     * {@link android.Manifest.permission#DISPATCH_PROVISIONING_MESSAGE}.
     *
     * <p>The provisioning intent contains the following properties:
     * <ul>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_SUPPORT_URL}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ORGANIZATION_NAME}, optional</li>
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional</li>
     * </ul>
     *
     * <p>Once the device admin app is set as the device owner, the following APIs are available for
     * managing polices on the device:
     * <ul>
     * <li>{@link #isDeviceManaged()}</li>
     * <li>{@link #isUninstallBlocked(ComponentName, String)}</li>
     * <li>{@link #setUninstallBlocked(ComponentName, String, boolean)}</li>
     * <li>{@link #setUserControlDisabledPackages(ComponentName, List)}</li>
     * <li>{@link #getUserControlDisabledPackages(ComponentName)}</li>
     * <li>{@link #setOrganizationName(ComponentName, CharSequence)}</li>
     * <li>{@link #getOrganizationName(ComponentName)} </li>
     * <li>{@link #setShortSupportMessage(ComponentName, CharSequence)}</li>
     * <li>{@link #getShortSupportMessage(ComponentName)}</li>
     * <li>{@link #isBackupServiceEnabled(ComponentName)}</li>
     * <li>{@link #setBackupServiceEnabled(ComponentName, boolean)}</li>
     * <li>{@link #isLockTaskPermitted(String)}</li>
     * <li>{@link #setLockTaskFeatures(ComponentName, int)}, where the following lock task features
     * can be set (otherwise a {@link SecurityException} will be thrown):</li>
     * <ul>
     *     <li>{@link #LOCK_TASK_FEATURE_SYSTEM_INFO}</li>
     *     <li>{@link #LOCK_TASK_FEATURE_KEYGUARD}</li>
     *     <li>{@link #LOCK_TASK_FEATURE_HOME}</li>
     *     <li>{@link #LOCK_TASK_FEATURE_GLOBAL_ACTIONS}</li>
     *     <li>{@link #LOCK_TASK_FEATURE_NOTIFICATIONS}</li>
     *     <li>{@link #LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK}</li>
     * </ul>
     * <li>{@link #getLockTaskFeatures(ComponentName)}</li>
     * <li>{@link #setLockTaskPackages(ComponentName, String[])}</li>
     * <li>{@link #getLockTaskPackages(ComponentName)}</li>
     * <li>{@link #addPersistentPreferredActivity(ComponentName, IntentFilter, ComponentName)}</li>
     * <li>{@link #clearPackagePersistentPreferredActivities(ComponentName, String)} </li>
     * <li>{@link #wipeData(int)}</li>
     * <li>{@link #isDeviceOwnerApp(String)}</li>
     * <li>{@link #clearDeviceOwnerApp(String)}</li>
     * <li>{@link #setPermissionGrantState(ComponentName, String, String, int)}, where
     * {@link permission#READ_PHONE_STATE} is the <b>only</b> permission that can be
     * {@link #PERMISSION_GRANT_STATE_GRANTED}, {@link #PERMISSION_GRANT_STATE_DENIED}, or
     * {@link #PERMISSION_GRANT_STATE_DEFAULT} and can <b>only</b> be applied to the device admin
     * app (otherwise a {@link SecurityException} will be thrown)</li>
     * <li>{@link #getPermissionGrantState(ComponentName, String, String)}, where
     * {@link permission#READ_PHONE_STATE} is the <b>only</b> permission that can be
     * used and device admin app is the only package that can be used to retrieve the permission
     * permission grant state for (otherwise a {@link SecurityException} will be thrown)</li>
     * <li>{@link #addUserRestriction(ComponentName, String)}, where the following user restrictions
     * are permitted (otherwise a {@link SecurityException} will be thrown):</li>
     * <ul>
     *     <li>{@link UserManager#DISALLOW_ADD_USER}</li>
     *     <li>{@link UserManager#DISALLOW_DEBUGGING_FEATURES}</li>
     *     <li>{@link UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES}</li>
     *     <li>{@link UserManager#DISALLOW_SAFE_BOOT}</li>
     *     <li>{@link UserManager#DISALLOW_CONFIG_DATE_TIME}</li>
     *     <li>{@link UserManager#DISALLOW_OUTGOING_CALLS}</li>
     * </ul>
     * <li>{@link #getUserRestrictions(ComponentName)}</li>
     * <li>{@link #clearUserRestriction(ComponentName, String)}, where the following user
     * restrictions are permitted (otherwise a {@link SecurityException} will be thrown):</li>
     * <ul>
     *     <li>{@link UserManager#DISALLOW_ADD_USER}</li>
     *     <li>{@link UserManager#DISALLOW_DEBUGGING_FEATURES}</li>
     *     <li>{@link UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES}</li>
     *     <li>{@link UserManager#DISALLOW_SAFE_BOOT}</li>
     *     <li>{@link UserManager#DISALLOW_CONFIG_DATE_TIME}</li>
     *     <li>{@link UserManager#DISALLOW_OUTGOING_CALLS}</li>
     * </ul>
     * </ul>
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_PROVISION_FINANCED_DEVICE =
            "android.app.action.PROVISION_FINANCED_DEVICE";

    /**
     * Activity action: Finalizes management provisioning, should be used after user-setup
     * has been completed and {@link #getUserProvisioningState()} returns one of:
     * <ul>
     * <li>{@link #STATE_USER_SETUP_INCOMPLETE}</li>
     * <li>{@link #STATE_USER_SETUP_COMPLETE}</li>
     * <li>{@link #STATE_USER_PROFILE_COMPLETE}</li>
     * </ul>
     *
     * <p>If a device policy management role holder (DPMRH) is present on the device and
     * valid, the provisioning flow will be deferred to it via the {@link
     * #ACTION_ROLE_HOLDER_PROVISION_FINALIZATION} intent.
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISION_FINALIZATION
            = "android.app.action.PROVISION_FINALIZATION";

    /**
     * Activity action: starts the managed profile provisioning flow inside the device policy
     * management role holder.
     *
     * <p>During the managed profile provisioning flow, the platform-provided provisioning handler
     * will delegate provisioning to the device policy management role holder, by firing this
     * intent. Third-party mobile device management applications attempting to fire this intent will
     * receive a {@link SecurityException}.
     *
     * <p>Device policy management role holders are required to have a handler for this intent
     * action.
     *
     * <p>If {@link #EXTRA_ROLE_HOLDER_STATE} is supplied to this intent, it is the responsibility
     * of the role holder to restore its state from this extra. This is the same {@link Bundle}
     * which the role holder returns alongside {@link #RESULT_UPDATE_ROLE_HOLDER}.
     *
     * <p>A result code of {@link Activity#RESULT_OK} implies that managed profile provisioning
     * finished successfully. If it did not, a result code of {@link Activity#RESULT_CANCELED}
     * is used instead.
     *
     * @see #ACTION_PROVISION_MANAGED_PROFILE
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LAUNCH_DEVICE_MANAGER_SETUP)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_ROLE_HOLDER_PROVISION_MANAGED_PROFILE =
            "android.app.action.ROLE_HOLDER_PROVISION_MANAGED_PROFILE";

    /**
     * Result code that can be returned by the {@link
     * #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} or {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent handlers if a work
     * profile has been created.
     *
     * @hide
     */
    @SystemApi
    public static final int RESULT_WORK_PROFILE_CREATED = 122;

    /**
     * Result code that can be returned by the {@link
     * #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} or {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent handlers if the
     * device owner was set.
     *
     * @hide
     */
    @SystemApi
    public static final int RESULT_DEVICE_OWNER_SET = 123;

    /**
     * Activity action: starts the trusted source provisioning flow inside the device policy
     * management role holder.
     *
     * <p>During the trusted source provisioning flow, the platform-provided provisioning handler
     * will delegate provisioning to the device policy management role holder, by firing this
     * intent. Third-party mobile device management applications attempting to fire this intent will
     * receive a {@link SecurityException}.
     *
     * <p>Device policy management role holders are required to have a handler for this intent
     * action.
     *
     * <p>If {@link #EXTRA_ROLE_HOLDER_STATE} is supplied to this intent, it is the responsibility
     * of the role holder to restore its state from this extra. This is the same {@link Bundle}
     * which the role holder returns alongside {@link #RESULT_UPDATE_ROLE_HOLDER}.
     *
     * <p>The result codes can be either {@link #RESULT_WORK_PROFILE_CREATED}, {@link
     * #RESULT_DEVICE_OWNER_SET} or {@link Activity#RESULT_CANCELED} if provisioning failed.
     *
     * @see #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LAUNCH_DEVICE_MANAGER_SETUP)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE =
            "android.app.action.ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE";

    /**
     * Activity action: starts the provisioning finalization flow inside the device policy
     * management role holder.
     *
     * <p>During the provisioning finalization flow, the platform-provided provisioning handler
     * will delegate provisioning to the device policy management role holder, by firing this
     * intent. Third-party mobile device management applications attempting to fire this intent will
     * receive a {@link SecurityException}.
     *
     * <p>Device policy management role holders are required to have a handler for this intent
     * action.
     *
     * <p>This handler forwards the result from the admin app's {@link
     * #ACTION_ADMIN_POLICY_COMPLIANCE} handler. Result code {@link Activity#RESULT_CANCELED}
     * implies the provisioning finalization flow has failed.
     *
     * @see #ACTION_PROVISION_FINALIZATION
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LAUNCH_DEVICE_MANAGER_SETUP)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_ROLE_HOLDER_PROVISION_FINALIZATION =
            "android.app.action.ROLE_HOLDER_PROVISION_FINALIZATION";

    /**
     * {@link Activity} result code which can be returned by {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_PROFILE} and {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} to signal that an update
     * to the role holder is required.
     *
     * <p>This result code can be accompanied by {@link #EXTRA_ROLE_HOLDER_STATE}.
     *
     * @hide
     */
    @SystemApi
    public static final int RESULT_UPDATE_ROLE_HOLDER = 2;

    /**
     * A {@link PersistableBundle} extra which the role holder can use to describe its own state
     * when it returns {@link #RESULT_UPDATE_ROLE_HOLDER}.
     *
     * <p>If {@link #RESULT_UPDATE_ROLE_HOLDER} was accompanied by this extra, after the update
     * completes, the role holder's {@link #ACTION_ROLE_HOLDER_PROVISION_MANAGED_PROFILE} or {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent will be relaunched,
     * which will contain this extra. It is the role holder's responsibility to restore its
     * state from this extra.
     *
     * <p>The content of this {@link PersistableBundle} is entirely up to the role holder. It
     * should contain anything the role holder needs to restore its original state when it gets
     * restarted.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ROLE_HOLDER_STATE = "android.app.extra.ROLE_HOLDER_STATE";

    /**
     * A {@code boolean} extra which determines whether to force a role holder update, regardless
     * of any internal conditions {@link #ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER} might
     * have.
     *
     * <p>This extra can be provided to intents with action {@link
     * #ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER}.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_FORCE_UPDATE_ROLE_HOLDER =
            "android.app.extra.FORCE_UPDATE_ROLE_HOLDER";

    /**
     * A boolean extra indicating whether offline provisioning should be used.
     *
     * <p>The default value is {@code false}.
     */
    // See b/365955253 for detailed behaviours of this API.
    public static final String EXTRA_PROVISIONING_ALLOW_OFFLINE =
            "android.app.extra.PROVISIONING_ALLOW_OFFLINE";

    /**
     * A String extra holding a url that specifies the download location of the device policy
     * management role holder package.
     *
     * <p>This is only meant to be used in cases when a specific variant of the role holder package
     * is needed (such as a debug variant). If not provided, the default variant of the device
     * manager role holder package is downloaded.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_ROLE_HOLDER_PACKAGE_DOWNLOAD_LOCATION =
            "android.app.extra.PROVISIONING_ROLE_HOLDER_PACKAGE_DOWNLOAD_LOCATION";

    /**
     * A String extra holding the URL-safe base64 encoded SHA-256 checksum of any signature of the
     * android package archive at the download location specified in {@link
     * #EXTRA_PROVISIONING_ROLE_HOLDER_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>The signatures of an android package archive can be obtained using
     * {@link android.content.pm.PackageManager#getPackageArchiveInfo} with flag
     * {@link android.content.pm.PackageManager#GET_SIGNING_CERTIFICATES}.
     *
     * <p>If {@link #EXTRA_PROVISIONING_ROLE_HOLDER_PACKAGE_DOWNLOAD_LOCATION} is provided, it must
     * be accompanied by this extra. The provided checksum must match the checksum of any signature
     * of the file at the download location. If the checksum does not match an error will be shown
     * to the user and the user will be asked to factory reset the device.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_ROLE_HOLDER_SIGNATURE_CHECKSUM =
            "android.app.extra.PROVISIONING_ROLE_HOLDER_SIGNATURE_CHECKSUM";

    /**
     * A String extra holding a http cookie header which should be used in the http request to the
     * url specified in {@link #EXTRA_PROVISIONING_ROLE_HOLDER_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_ROLE_HOLDER_PACKAGE_DOWNLOAD_COOKIE_HEADER =
            "android.app.extra.PROVISIONING_ROLE_HOLDER_PACKAGE_DOWNLOAD_COOKIE_HEADER";

    /**
     * An extra of type {@link android.os.PersistableBundle} that allows the provisioning initiator
     * to pass data to the device policy management role holder.
     *
     * <p>The device policy management role holder will receive this extra via the {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent.
     *
     * <p>The contents of this extra are up to the contract between the provisioning initiator
     * and the device policy management role holder.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_ROLE_HOLDER_EXTRAS_BUNDLE =
            "android.app.extra.PROVISIONING_ROLE_HOLDER_EXTRAS_BUNDLE";

    /**
     * A String extra containing the package name of the provisioning initiator.
     *
     * <p>Use in an intent with action {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ROLE_HOLDER_PROVISIONING_INITIATOR_PACKAGE =
            "android.app.extra.ROLE_HOLDER_PROVISIONING_INITIATOR_PACKAGE";

    /**
     * An {@link Intent} result extra specifying the {@link Intent} to be launched after
     * provisioning is finalized.
     *
     * <p>If {@link #EXTRA_PROVISIONING_SHOULD_LAUNCH_RESULT_INTENT} is set to {@code false},
     * this result will be supplied as part of the result {@link Intent} for provisioning actions
     * such as {@link #ACTION_PROVISION_MANAGED_PROFILE}. This result will also be supplied as
     * part of the result {@link Intent} for the device policy management role holder provisioning
     * actions.
     */
    public static final String EXTRA_RESULT_LAUNCH_INTENT =
            "android.app.extra.RESULT_LAUNCH_INTENT";

    /**
     * A boolean extra that determines whether the provisioning flow should launch the resulting
     * launch intent, if one is supplied by the device policy management role holder via {@link
     * #EXTRA_RESULT_LAUNCH_INTENT}. Default value is {@code false}.
     *
     * <p>If {@code true}, the resulting intent will be launched by the provisioning flow, if one
     * is supplied by the device policy management role holder.
     *
     * <p>If {@code false}, the resulting intent will be returned as {@link
     * #EXTRA_RESULT_LAUNCH_INTENT} to the provisioning initiator, if one is supplied by the device
     * manager role holder. It will be the responsibility of the provisioning initiator to launch
     * this {@link Intent} after provisioning completes.
     *
     * <p>This extra is respected when provided via the provisioning intent actions such as {@link
     * #ACTION_PROVISION_MANAGED_PROFILE}.
     */
    public static final String EXTRA_PROVISIONING_SHOULD_LAUNCH_RESULT_INTENT =
            "android.app.extra.PROVISIONING_SHOULD_LAUNCH_RESULT_INTENT";

    /**
     * Action: Bugreport sharing with device owner has been accepted by the user.
     *
     * @hide
     */
    public static final String ACTION_BUGREPORT_SHARING_ACCEPTED =
            "com.android.server.action.REMOTE_BUGREPORT_SHARING_ACCEPTED";

    /**
     * Action: Bugreport sharing with device owner has been declined by the user.
     *
     * @hide
     */
    public static final String ACTION_BUGREPORT_SHARING_DECLINED =
            "com.android.server.action.REMOTE_BUGREPORT_SHARING_DECLINED";

    /**
     * Action: Bugreport has been collected and is dispatched to {@code DevicePolicyManagerService}.
     *
     * @hide
     */
    public static final String ACTION_REMOTE_BUGREPORT_DISPATCH =
            "android.intent.action.REMOTE_BUGREPORT_DISPATCH";

    /**
     * Extra for shared bugreport's SHA-256 hash.
     *
     * @hide
     */
    public static final String EXTRA_REMOTE_BUGREPORT_HASH =
            "android.intent.extra.REMOTE_BUGREPORT_HASH";

    /**
     * Extra for shared bugreport's nonce in long integer type.
     *
     * @hide
     */
    public static final String EXTRA_REMOTE_BUGREPORT_NONCE =
            "android.intent.extra.REMOTE_BUGREPORT_NONCE";

    /**
     * Extra for remote bugreport notification shown type.
     *
     * @hide
     */
    public static final String EXTRA_BUGREPORT_NOTIFICATION_TYPE =
            "android.app.extra.bugreport_notification_type";

    /**
     * Default value for preferential network service enabling.
     *
     * @hide
     */
    public static final boolean PREFERENTIAL_NETWORK_SERVICE_ENABLED_DEFAULT = false;

    /**
     * Notification type for a started remote bugreport flow.
     *
     * @hide
     */
    public static final int NOTIFICATION_BUGREPORT_STARTED = 1;

    /**
     * Notification type for a bugreport that has already been accepted to be shared, but is still
     * being taken.
     *
     * @hide
     */
    public static final int NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED = 2;

    /**
     * Notification type for a bugreport that has been taken and can be shared or declined.
     *
     * @hide
     */
    public static final int NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED = 3;

    /**
     * Default and maximum timeout in milliseconds after which unlocking with weak auth times out,
     * i.e. the user has to use a strong authentication method like password, PIN or pattern.
     *
     * @hide
     */
    public static final long DEFAULT_STRONG_AUTH_TIMEOUT_MS = 72 * 60 * 60 * 1000; // 72h

    /**
     * A {@link android.os.Parcelable} extra of type {@link android.os.PersistableBundle} that is
     * passed directly to the <a href="#devicepolicycontroller">Device Policy Controller</a>
     * after <a href="#managed-provisioning">provisioning</a>.
     *
     * <p>
     * Starting from {@link android.os.Build.VERSION_CODES#M}, if used with
     * {@link #MIME_TYPE_PROVISIONING_NFC} as part of NFC managed device provisioning, the NFC
     * message should contain a stringified {@link java.util.Properties} instance, whose string
     * properties will be converted into a {@link android.os.PersistableBundle} and passed to the
     * management application after provisioning.
     */
    public static final String EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE =
            "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE";

    /**
     * A String extra holding the package name of the application that
     * will be set as <a href="#devicepolicycontroller">Device Policy Controller</a>.
     *
     * <p>When this extra is set, the application must have exactly one
     * {@link DeviceAdminReceiver device admin receiver}. This receiver will be set as the
     * <a href="#devicepolicycontroller">Device Policy Controller</a>.
     *
     * @deprecated Use {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME}.
     */
    @Deprecated
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME";

    /**
     * A ComponentName extra indicating the {@link DeviceAdminReceiver device admin receiver} of
     * the application that will be set as the <a href="#devicepolicycontroller">
     *     Device Policy Controller</a>.
     *
     * <p>If an application starts provisioning directly via an intent with action
     * {@link #ACTION_PROVISION_MANAGED_DEVICE} the package name of this
     * component has to match the package name of the application that started provisioning.
     *
     * <p>This component is set as device owner and active admin when device owner provisioning is
     * started by an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE} or by an NFC
     * message containing an NFC record with MIME type
     * {@link #MIME_TYPE_PROVISIONING_NFC}. For the NFC record, the component name must be
     * flattened to a string, via {@link ComponentName#flattenToShortString()}.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME";

    /**
     * An {@link android.accounts.Account} extra holding the account to migrate during managed
     * profile provisioning.
     *
     * <p>If the account supplied is present in the user, it will be copied, along with its
     * credentials to the managed profile and removed from the user.
     */
    public static final String EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE
        = "android.app.extra.PROVISIONING_ACCOUNT_TO_MIGRATE";

    /**
     * Boolean extra to indicate that the
     * {@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE migrated account} should be kept.
     *
     * <p>If it's set to {@code true}, the account will not be removed from the user after it is
     * migrated to the newly created user or profile.
     *
     * <p>Defaults to {@code false}
     *
     * @see #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE
     */
    public static final String EXTRA_PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION
            = "android.app.extra.PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION";

    /**
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}, never used while provisioning the
     * device.
     */
    @Deprecated
    public static final String EXTRA_PROVISIONING_EMAIL_ADDRESS
        = "android.app.extra.PROVISIONING_EMAIL_ADDRESS";

    /**
     * A integer extra indicating the predominant color to show during the provisioning.
     * Refer to {@link android.graphics.Color} for how the color is represented.
     *
     * <p>Use with {@link #ACTION_PROVISION_MANAGED_PROFILE} or
     * {@link #ACTION_PROVISION_MANAGED_DEVICE}.
     *
     * @deprecated Color customization is no longer supported in the provisioning flow.
     */
    @Deprecated
    public static final String EXTRA_PROVISIONING_MAIN_COLOR =
             "android.app.extra.PROVISIONING_MAIN_COLOR";

    /**
     * A Boolean extra that can be used by the mobile device management application to skip the
     * disabling of system apps during provisioning when set to {@code true}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC}, an intent with action
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} that starts profile owner provisioning or
     * set as an extra to the intent result of the {@link #ACTION_GET_PROVISIONING_MODE} activity.
     */
    public static final String EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED =
            "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED";

    /**
     * A String extra holding the time zone {@link android.app.AlarmManager} that the device
     * will be set to.
     *
     * <p>Use only for device owner provisioning. This extra can be returned by the admin app when
     * performing the admin-integrated provisioning flow as a result of the {@link
     * #ACTION_GET_PROVISIONING_MODE} activity.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_TIME_ZONE
        = "android.app.extra.PROVISIONING_TIME_ZONE";

    /**
     * A Long extra holding the wall clock time (in milliseconds) to be set on the device's
     * {@link android.app.AlarmManager}.
     *
     * <p>Use only for device owner provisioning. This extra can be returned by the admin app when
     * performing the admin-integrated provisioning flow as a result of the {@link
     * #ACTION_GET_PROVISIONING_MODE} activity.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_LOCAL_TIME
        = "android.app.extra.PROVISIONING_LOCAL_TIME";

    /**
     * A String extra holding the {@link java.util.Locale} that the device will be set to.
     * Format: xx_yy, where xx is the language code, and yy the country code.
     *
     * <p>Use only for device owner provisioning. This extra can be returned by the admin app when
     * performing the admin-integrated provisioning flow as a result of the {@link
     * #ACTION_GET_PROVISIONING_MODE} activity.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_LOCALE
        = "android.app.extra.PROVISIONING_LOCALE";

    /**
     * A String extra holding the ssid of the wifi network that should be used during nfc device
     * owner provisioning for downloading the mobile device management application.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_SSID
        = "android.app.extra.PROVISIONING_WIFI_SSID";

    /**
     * A boolean extra indicating whether the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}
     * is hidden or not.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_HIDDEN
        = "android.app.extra.PROVISIONING_WIFI_HIDDEN";

    /**
     * A String extra indicating the security type of the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID} and could be one of {@code NONE}, {@code WPA},
     * {@code WEP} or {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_SECURITY_TYPE
        = "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE";

    /**
     * A String extra holding the password of the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PASSWORD =
            "android.app.extra.PROVISIONING_WIFI_PASSWORD";

    /**
     * The EAP method of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}
     * and could be one of {@code PEAP}, {@code TLS}, {@code TTLS}, {@code PWD}, {@code SIM},
     * {@code AKA} or {@code AKA_PRIME}. This is only used if the
     * {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_EAP_METHOD =
            "android.app.extra.PROVISIONING_WIFI_EAP_METHOD";

    /**
     * The phase 2 authentication of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}
     * and could be one of {@code NONE}, {@code PAP}, {@code MSCHAP}, {@code MSCHAPV2}, {@code GTC},
     * {@code SIM}, {@code AKA} or {@code AKA_PRIME}. This is only used if the
     * {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PHASE2_AUTH =
            "android.app.extra.PROVISIONING_WIFI_PHASE2_AUTH";

    /**
     * The CA certificate of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This should
     * be an X.509 certificate Base64 encoded DER format, ie. PEM representation of a certificate
     * without header, footer and line breaks. <a href=
     * "https://tools.ietf.org/html/rfc7468"> More information</a> This is only
     * used if the {@link
     * #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_CA_CERTIFICATE =
            "android.app.extra.PROVISIONING_WIFI_CA_CERTIFICATE";

    /**
     * The user certificate of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This
     * should be an X.509 certificate and private key Base64 encoded DER format, ie. PEM
     * representation of a certificate and key without header, footer and line breaks. <a href=
     * "https://tools.ietf.org/html/rfc7468"> More information</a> This is only
     * used if the {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_USER_CERTIFICATE =
            "android.app.extra.PROVISIONING_WIFI_USER_CERTIFICATE";

    /**
     * The identity of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This is only used
     * if the {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_IDENTITY =
            "android.app.extra.PROVISIONING_WIFI_IDENTITY";

    /**
     * The anonymous identity of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This is
     * only used if the {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */

    public static final String EXTRA_PROVISIONING_WIFI_ANONYMOUS_IDENTITY =
            "android.app.extra.PROVISIONING_WIFI_ANONYMOUS_IDENTITY";
    /**
     * The domain of the wifi network in {@link #EXTRA_PROVISIONING_WIFI_SSID}. This is only used if
     * the {@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE} is {@code EAP}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_DOMAIN =
            "android.app.extra.PROVISIONING_WIFI_DOMAIN";

    /**
     * A String extra holding the proxy host for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_HOST
        = "android.app.extra.PROVISIONING_WIFI_PROXY_HOST";

    /**
     * An int extra holding the proxy port for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_PORT
        = "android.app.extra.PROVISIONING_WIFI_PROXY_PORT";

    /**
     * A String extra holding the proxy bypass for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PROXY_BYPASS
        = "android.app.extra.PROVISIONING_WIFI_PROXY_BYPASS";

    /**
     * A String extra holding the proxy auto-config (PAC) URL for the wifi network in
     * {@link #EXTRA_PROVISIONING_WIFI_SSID}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_WIFI_PAC_URL
        = "android.app.extra.PROVISIONING_WIFI_PAC_URL";

    /**
     * A String extra holding a url that specifies the download location of the device admin
     * package. When not provided it is assumed that the device admin package is already installed.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION";

    /**
     * A String extra holding the localized name of the organization under management.
     *
     * The name is displayed only during provisioning.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_FINANCED_DEVICE}
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_ORGANIZATION_NAME =
            "android.app.extra.PROVISIONING_ORGANIZATION_NAME";

    /**
     * A String extra holding a url to the website of the device provider so the user can open it
     * during provisioning. If the url is not HTTPS, an error will be shown.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or {@link #ACTION_PROVISION_FINANCED_DEVICE}
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_SUPPORT_URL =
            "android.app.extra.PROVISIONING_SUPPORT_URL";

    /**
     * A String extra holding the localized name of the device admin package. It should be the same
     * as the app label of the package.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or {@link #ACTION_PROVISION_FINANCED_DEVICE}
     *
     * @deprecated This extra is no longer respected in the provisioning flow.
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_LABEL =
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_LABEL";

    /**
     * A {@link Uri} extra pointing to the app icon of device admin package. This image will be
     * shown during the provisioning.
     * <h5>The following URI schemes are accepted:</h5>
     * <ul>
     * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
     * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})</li>
     * </ul>
     *
     * <p> It is the responsibility of the caller to provide an image with a reasonable
     * pixel density for the device.
     *
     * <p> If a content: URI is passed, the intent should have the flag
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} and the uri should be added to the
     * {@link android.content.ClipData} of the intent too.
     *
     * <p>Use in an intent with action {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}
     * or {@link #ACTION_PROVISION_FINANCED_DEVICE}
     *
     * @deprecated This extra is no longer respected in the provisioning flow.
     * @hide
     */
    @SystemApi
    @Deprecated
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_ICON_URI =
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_ICON_URI";

    /**
     * An int extra holding a minimum required version code for the device admin package. If the
     * device admin is already installed on the device, it will only be re-downloaded from
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION} if the version of the
     * installed package is less than this version code.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_MINIMUM_VERSION_CODE";

    /**
     * A String extra holding a http cookie header which should be used in the http request to the
     * url specified in {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_COOKIE_HEADER";

    /**
     * A String extra holding the URL-safe base64 encoded SHA-256 hash of the file at download
     * location specified in {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>Either this extra or {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM} must be
     * present. The provided checksum must match the checksum of the file at the download
     * location. If the checksum doesn't match an error will be shown to the user and the user will
     * be asked to factory reset the device.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
     *
     * <p><strong>Note:</strong> for devices running {@link android.os.Build.VERSION_CODES#LOLLIPOP}
     * and {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1} only SHA-1 hash is supported.
     * Starting from {@link android.os.Build.VERSION_CODES#M}, this parameter accepts SHA-256 in
     * addition to SHA-1. From {@link android.os.Build.VERSION_CODES#Q}, only SHA-256 hash is
     * supported.
     */
    public static final String EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM
        = "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM";

    /**
     * A boolean extra indicating the admin of a fully-managed device opts out of controlling
     * permission grants for sensor-related permissions,
     * see {@link #setPermissionGrantState(ComponentName, String, String, int)}.
     *
     * The default for this extra is {@code false} - by default, the admin of a fully-managed
     * device has the ability to grant sensors-related permissions.
     *
     * <p>Use only for device owner provisioning. This extra can be returned by the
     * admin app when performing the admin-integrated provisioning flow as a result of the
     * {@link #ACTION_GET_PROVISIONING_MODE} activity.
     *
     * <p>This extra may also be provided to the admin app via an intent extra for {@link
     * #ACTION_GET_PROVISIONING_MODE}.
     *
     * @see #ACTION_GET_PROVISIONING_MODE
     */
    public static final String EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT =
            "android.app.extra.PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT";

    /**
     * A String extra holding the URL-safe base64 encoded SHA-256 checksum of any signature of the
     * android package archive at the download location specified in {@link
     * #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION}.
     *
     * <p>The signatures of an android package archive can be obtained using
     * {@link android.content.pm.PackageManager#getPackageArchiveInfo} with flag
     * {@link android.content.pm.PackageManager#GET_SIGNATURES}.
     *
     * <p>Either this extra or {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM} must be
     * present. The provided checksum must match the checksum of any signature of the file at
     * the download location. If the checksum does not match an error will be shown to the user and
     * the user will be asked to factory reset the device.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} that starts device owner
     * provisioning via an NFC bump. It can also be used for QR code provisioning.
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
     * <p>This intent will contain the following extras
     * <ul>
     * <li>{@link Intent#EXTRA_USER}, corresponds to the {@link UserHandle} of the managed
     * profile.</li>
     * <li>{@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE}, corresponds to the account requested to
     * be migrated at provisioning time, if any.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGED_PROFILE_PROVISIONED
        = "android.app.action.MANAGED_PROFILE_PROVISIONED";

    /**
     * Activity action: This activity action is sent to indicate that provisioning of a managed
     * profile or managed device has completed successfully. It'll be sent at the same time as
     * {@link DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE} broadcast but this will be
     * delivered faster as it's an activity intent.
     *
     * <p>The intent is only sent to the new device or profile owner.
     *
     * @see #ACTION_PROVISION_MANAGED_PROFILE
     * @see #ACTION_PROVISION_MANAGED_DEVICE
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_PROVISIONING_SUCCESSFUL =
            "android.app.action.PROVISIONING_SUCCESSFUL";

    /**
     * A boolean extra indicating whether device encryption can be skipped as part of
     * <a href="#managed-provisioning">provisioning</a>.
     *
     * <p>Use in an NFC record with {@link #MIME_TYPE_PROVISIONING_NFC} or an intent with action
     * {@link #ACTION_PROVISION_MANAGED_DEVICE} that starts device owner provisioning.
     *
     * <p>From {@link android.os.Build.VERSION_CODES#N} onwards, this is also supported for an
     * intent with action {@link #ACTION_PROVISION_MANAGED_PROFILE}.
     */
    public static final String EXTRA_PROVISIONING_SKIP_ENCRYPTION =
             "android.app.extra.PROVISIONING_SKIP_ENCRYPTION";

    /**
     * A {@link Uri} extra pointing to a logo image. This image will be shown during the
     * provisioning. If this extra is not passed, a default image will be shown.
     *
     * <p><b>The following URI schemes are accepted:</b>
     * <ul>
     * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
     * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})</li>
     * </ul>
     *
     * <p>It is the responsibility of the caller to provide an image with a reasonable
     * pixel density for the device.
     *
     * <p>If a content: URI is passed, the intent should also have the flag
     * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} and the uri should be added to the
     * {@link android.content.ClipData} of the intent.
     *
     * @deprecated Logo customization is no longer supported in the
     *             <a href="#managedprovisioning">provisioning flow</a>.
     */
    @Deprecated
    public static final String EXTRA_PROVISIONING_LOGO_URI =
            "android.app.extra.PROVISIONING_LOGO_URI";

    /**
     * A {@link Bundle}[] extra consisting of list of disclaimer headers and disclaimer contents.
     *
     * <p>Each {@link Bundle} must have both {@link #EXTRA_PROVISIONING_DISCLAIMER_HEADER}
     * as disclaimer header, and {@link #EXTRA_PROVISIONING_DISCLAIMER_CONTENT} as disclaimer
     * content.
     *
     * <p> The extra typically contains one disclaimer from the company of mobile device
     * management application (MDM), and one disclaimer from the organization.
     *
     * <p> Call {@link Bundle#putParcelableArray(String, Parcelable[])} to put the {@link Bundle}[]
     *
     * <p> Maximum 3 key-value pairs can be specified. The rest will be ignored.
     *
     * <p> Can be used in an intent with action {@link #ACTION_PROVISION_MANAGED_PROFILE}. This
     * extra can also be returned by the admin app when performing the admin-integrated
     * provisioning flow as a result of the {@link #ACTION_GET_PROVISIONING_MODE} activity.
     */
    public static final String EXTRA_PROVISIONING_DISCLAIMERS =
            "android.app.extra.PROVISIONING_DISCLAIMERS";

    /**
     * A String extra of localized disclaimer header.
     *
     * <p>The extra is typically the company name of mobile device management application (MDM)
     * or the organization name.
     *
     * <p>{@link ApplicationInfo#FLAG_SYSTEM System apps} can also insert a disclaimer by declaring
     * an application-level meta-data in {@code AndroidManifest.xml}.
     *
     * <p>For example:
     * <pre>
     *  &lt;meta-data
     *      android:name="android.app.extra.PROVISIONING_DISCLAIMER_HEADER"
     *      android:resource="@string/disclaimer_header"
     * /&gt;</pre>
     *
     * <p>This must be accompanied with another extra using the key
     * {@link #EXTRA_PROVISIONING_DISCLAIMER_CONTENT}.
     */
    public static final String EXTRA_PROVISIONING_DISCLAIMER_HEADER =
            "android.app.extra.PROVISIONING_DISCLAIMER_HEADER";

    /**
     * A {@link Uri} extra pointing to disclaimer content.
     *
     * <h5>The following URI schemes are accepted:</h5>
     * <ul>
     * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
     * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})</li>
     * </ul>
     *
     * <p>Styled text is supported. This is parsed by {@link android.text.Html#fromHtml(String)}
     * and displayed in a {@link android.widget.TextView}.
     *
     * <p>If a <code>content:</code> URI is passed, the intent should also have the
     * flag {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} and the uri should be added to the
     * {@link android.content.ClipData} of the intent.
     *
     * <p>{@link ApplicationInfo#FLAG_SYSTEM System apps} can also insert a
     * disclaimer by declaring an application-level meta-data in {@code AndroidManifest.xml}.
     *
     * <p>For example:
     *
     * <pre>
     *  &lt;meta-data
     *      android:name="android.app.extra.PROVISIONING_DISCLAIMER_CONTENT"
     *      android:resource="@string/disclaimer_content"
     * /&gt;</pre>
     *
     * <p>This must be accompanied with another extra using the key
     * {@link #EXTRA_PROVISIONING_DISCLAIMER_HEADER}.
     */
    public static final String EXTRA_PROVISIONING_DISCLAIMER_CONTENT =
            "android.app.extra.PROVISIONING_DISCLAIMER_CONTENT";

    /**
     * A boolean extra indicating if the user consent steps from the
     * <a href="#managed-provisioning">provisioning flow</a> should be skipped.
     *
     * <p>If unspecified, defaults to {@code false}.
     *
     * @deprecated this extra is no longer relevant as device owners cannot create managed profiles
     */
    @Deprecated
    public static final String EXTRA_PROVISIONING_SKIP_USER_CONSENT =
            "android.app.extra.PROVISIONING_SKIP_USER_CONSENT";

    /**
     * A boolean extra indicating if the education screens from the provisioning flow should be
     * skipped. If unspecified, defaults to {@code false}.
     *
     * <p>This extra can be set in the following ways:
     * <ul>
     * <li>By the admin app when performing the admin-integrated
     * provisioning flow as a result of the {@link #ACTION_GET_PROVISIONING_MODE} activity</li>
     * <li>For managed account enrollment</li>
     * </ul>
     *
     * <p>If the education screens are skipped, it is the admin application's responsibility
     * to display its own user education screens.
     */
    public static final String EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS =
            "android.app.extra.PROVISIONING_SKIP_EDUCATION_SCREENS";

    /**
     * A boolean extra indicating if mobile data should be used during the provisioning flow
     * for downloading the admin app. If {@link #EXTRA_PROVISIONING_WIFI_SSID} is also specified,
     * wifi network will be used instead.
     *
     * <p>Default value is {@code false}.
     *
     * <p>If this extra is set to {@code true} and {@link #EXTRA_PROVISIONING_WIFI_SSID} is not
     * specified, this extra has different behaviour depending on the way provisioning is triggered:
     * <ul>
     * <li>
     *     For provisioning started via a QR code or an NFC tag, mobile data is always used for
     *     downloading the admin app.
     * </li>
     * <li>
     *     For all other provisioning methods, a mobile data connection check is made at the start
     *     of provisioning. If mobile data is connected at that point, the admin app download will
     *     happen using mobile data. If mobile data is not connected at that point, the end-user
     *     will be asked to pick a wifi network and the admin app download will proceed over wifi.
     * </li>
     * </ul>
     */
    public static final String EXTRA_PROVISIONING_USE_MOBILE_DATA =
            "android.app.extra.PROVISIONING_USE_MOBILE_DATA";

    /**
     * Possible values for {@link #EXTRA_PROVISIONING_TRIGGER}.
     *
     * @hide
     */
    @IntDef(prefix = { "PROVISIONING_TRIGGER_" }, value = {
            PROVISIONING_TRIGGER_UNSPECIFIED,
            PROVISIONING_TRIGGER_CLOUD_ENROLLMENT,
            PROVISIONING_TRIGGER_QR_CODE,
            PROVISIONING_TRIGGER_PERSISTENT_DEVICE_OWNER,
            PROVISIONING_TRIGGER_MANAGED_ACCOUNT,
            PROVISIONING_TRIGGER_NFC
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProvisioningTrigger {}

    /**
     * Flags for {@link #EXTRA_PROVISIONING_SUPPORTED_MODES}.
     *
     * @hide
     */
    @IntDef(flag = true, prefix = { "FLAG_SUPPORTED_MODES_" }, value = {
            FLAG_SUPPORTED_MODES_ORGANIZATION_OWNED,
            FLAG_SUPPORTED_MODES_PERSONALLY_OWNED,
            FLAG_SUPPORTED_MODES_DEVICE_OWNER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProvisioningConfiguration {}

    /**
     * An int extra holding the provisioning trigger. It could be one of
     * {@link #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT}, {@link #PROVISIONING_TRIGGER_QR_CODE},
     * {@link #PROVISIONING_TRIGGER_MANAGED_ACCOUNT} or {@link
     * #PROVISIONING_TRIGGER_UNSPECIFIED}.
     *
     * <p>Use in an intent with action {@link
     * #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_TRIGGER =
            "android.app.extra.PROVISIONING_TRIGGER";

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning
     * trigger has not been specified.
     * @see #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT
     * @see #PROVISIONING_TRIGGER_QR_CODE
     * @see #PROVISIONING_TRIGGER_MANAGED_ACCOUNT
     * @see #PROVISIONING_TRIGGER_NFC
     * @hide
     */
    @SystemApi
    public static final int PROVISIONING_TRIGGER_UNSPECIFIED = 0;

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning
     * trigger is cloud enrollment.
     * @see #PROVISIONING_TRIGGER_QR_CODE
     * @see #PROVISIONING_TRIGGER_MANAGED_ACCOUNT
     * @see #PROVISIONING_TRIGGER_UNSPECIFIED
     * @see #PROVISIONING_TRIGGER_NFC
     * @hide
     */
    @SystemApi
    public static final int PROVISIONING_TRIGGER_CLOUD_ENROLLMENT = 1;

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning
     * trigger is the QR code scanner.
     * @see #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT
     * @see #PROVISIONING_TRIGGER_MANAGED_ACCOUNT
     * @see #PROVISIONING_TRIGGER_UNSPECIFIED
     * @see #PROVISIONING_TRIGGER_NFC
     * @hide
     */
    @SystemApi
    public static final int PROVISIONING_TRIGGER_QR_CODE = 2;

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning
     * trigger is persistent device owner enrollment.
     * <p>This constant is meant to represent a specific type of managed account provisioning which
     * provisions a device to a device owner by invoking the standard provisioning flow (where
     * the ManagedProvisioning component downloads and installs the admin app), as opposed to
     * relying on the provisioning trigger to handle download and install of the admin app.
     * <p>As of {@link android.os.Build.VERSION_CODES#S}, this constant is no longer used in favor
     * of the more general {@link #PROVISIONING_TRIGGER_MANAGED_ACCOUNT} which handles all managed
     * account provisioning types.
     * @deprecated Use the broader {@link #PROVISIONING_TRIGGER_MANAGED_ACCOUNT} instead
     * @see #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT
     * @see #PROVISIONING_TRIGGER_QR_CODE
     * @see #PROVISIONING_TRIGGER_UNSPECIFIED
     * @see #PROVISIONING_TRIGGER_NFC
     * @hide
     */
    @SystemApi
    @Deprecated
    public static final int PROVISIONING_TRIGGER_PERSISTENT_DEVICE_OWNER = 3;

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning
     * trigger is managed account enrollment.
     * <p>
     * @see #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT
     * @see #PROVISIONING_TRIGGER_QR_CODE
     * @see #PROVISIONING_TRIGGER_UNSPECIFIED
     * @see #PROVISIONING_TRIGGER_NFC
     * @hide
     */
    @SystemApi
    public static final int PROVISIONING_TRIGGER_MANAGED_ACCOUNT = 4;

    /**
     * A value for {@link #EXTRA_PROVISIONING_TRIGGER} indicating that the provisioning is
     * triggered by tapping an NFC tag.
     * @see #PROVISIONING_TRIGGER_CLOUD_ENROLLMENT
     * @see #PROVISIONING_TRIGGER_QR_CODE
     * @see #PROVISIONING_TRIGGER_UNSPECIFIED
     * @see #PROVISIONING_TRIGGER_MANAGED_ACCOUNT
     * @hide
     */
    @SystemApi
    public static final int PROVISIONING_TRIGGER_NFC = 5;

    /**
     * Flag for {@link #EXTRA_PROVISIONING_SUPPORTED_MODES} indicating that provisioning is
     * organization-owned.
     *
     * <p>Using this value indicates the admin app can only be provisioned in either a
     * fully-managed device or a corporate-owned work profile. This will cause the admin app's
     * {@link #ACTION_GET_PROVISIONING_MODE} activity to have the {@link
     * #EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES} array extra contain {@link
     * #PROVISIONING_MODE_MANAGED_PROFILE} and {@link #PROVISIONING_MODE_FULLY_MANAGED_DEVICE}.
     *
     * <p>This flag can be combined with {@link #FLAG_SUPPORTED_MODES_PERSONALLY_OWNED}. In
     * that case, the admin app's {@link #ACTION_GET_PROVISIONING_MODE} activity will have
     * the {@link #EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES} array extra contain {@link
     * #PROVISIONING_MODE_MANAGED_PROFILE}, {@link #PROVISIONING_MODE_FULLY_MANAGED_DEVICE} and
     * {@link #PROVISIONING_MODE_MANAGED_PROFILE_ON_PERSONAL_DEVICE}.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_SUPPORTED_MODES_ORGANIZATION_OWNED = 1;

    /**
     * Flag for {@link #EXTRA_PROVISIONING_SUPPORTED_MODES} indicating that provisioning
     * is personally-owned.
     *
     * <p>Using this flag will cause the admin app's {@link #ACTION_GET_PROVISIONING_MODE}
     * activity to have the {@link #EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES} array extra
     * contain only {@link #PROVISIONING_MODE_MANAGED_PROFILE}.
     *
     * <p>Also, if this flag is set, the admin app's {@link #ACTION_GET_PROVISIONING_MODE} activity
     * will not receive the {@link #EXTRA_PROVISIONING_IMEI} and {@link
     * #EXTRA_PROVISIONING_SERIAL_NUMBER} extras.
     *
     * <p>This flag can be combined with {@link #FLAG_SUPPORTED_MODES_ORGANIZATION_OWNED}. In
     * that case, the admin app's {@link #ACTION_GET_PROVISIONING_MODE} activity will have the
     * {@link #EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES} array extra contain {@link
     * #PROVISIONING_MODE_MANAGED_PROFILE}, {@link #PROVISIONING_MODE_FULLY_MANAGED_DEVICE} and
     * {@link #PROVISIONING_MODE_MANAGED_PROFILE_ON_PERSONAL_DEVICE}.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_SUPPORTED_MODES_PERSONALLY_OWNED = 1 << 1;

    /**
     * Flag for {@link #EXTRA_PROVISIONING_SUPPORTED_MODES} indicating that the only
     * supported provisioning mode is device owner.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_SUPPORTED_MODES_DEVICE_OWNER = 1 << 2;

    /**
     * Constant for {@link #getMinimumRequiredWifiSecurityLevel()} and
     * {@link #setMinimumRequiredWifiSecurityLevel(int)}: no minimum security level.
     *
     * <p> When returned from {@link #getMinimumRequiredWifiSecurityLevel()}, the constant
     * represents the current minimum security level required.
     * When passed to {@link #setMinimumRequiredWifiSecurityLevel(int)}, it sets the
     * minimum security level a Wi-Fi network must meet.
     *
     * @see #WIFI_SECURITY_PERSONAL
     * @see #WIFI_SECURITY_ENTERPRISE_EAP
     * @see #WIFI_SECURITY_ENTERPRISE_192
     */
    public static final int WIFI_SECURITY_OPEN = 0;

    /**
     * Constant for {@link #getMinimumRequiredWifiSecurityLevel()} and
     * {@link #setMinimumRequiredWifiSecurityLevel(int)}: personal network such as WEP, WPA2-PSK.
     *
     * <p> When returned from {@link #getMinimumRequiredWifiSecurityLevel()}, the constant
     * represents the current minimum security level required.
     * When passed to {@link #setMinimumRequiredWifiSecurityLevel(int)}, it sets the
     * minimum security level a Wi-Fi network must meet.
     *
     * @see #WIFI_SECURITY_OPEN
     * @see #WIFI_SECURITY_ENTERPRISE_EAP
     * @see #WIFI_SECURITY_ENTERPRISE_192
     */
    public static final int WIFI_SECURITY_PERSONAL = 1;

    /**
     * Constant for {@link #getMinimumRequiredWifiSecurityLevel()} and
     * {@link #setMinimumRequiredWifiSecurityLevel(int)}: enterprise EAP network.
     *
     * <p> When returned from {@link #getMinimumRequiredWifiSecurityLevel()}, the constant
     * represents the current minimum security level required.
     * When passed to {@link #setMinimumRequiredWifiSecurityLevel(int)}, it sets the
     * minimum security level a Wi-Fi network must meet.
     *
     * @see #WIFI_SECURITY_OPEN
     * @see #WIFI_SECURITY_PERSONAL
     * @see #WIFI_SECURITY_ENTERPRISE_192
     */
    public static final int WIFI_SECURITY_ENTERPRISE_EAP = 2;

    /**
     * Constant for {@link #getMinimumRequiredWifiSecurityLevel()} and
     * {@link #setMinimumRequiredWifiSecurityLevel(int)}: enterprise 192 bit network.
     *
     * <p> When returned from {@link #getMinimumRequiredWifiSecurityLevel()}, the constant
     * represents the current minimum security level required.
     * When passed to {@link #setMinimumRequiredWifiSecurityLevel(int)}, it sets the
     * minimum security level a Wi-Fi network must meet.
     *
     * @see #WIFI_SECURITY_OPEN
     * @see #WIFI_SECURITY_PERSONAL
     * @see #WIFI_SECURITY_ENTERPRISE_EAP
     */
    public static final int WIFI_SECURITY_ENTERPRISE_192 = 3;

    /**
     * Possible Wi-Fi minimum security levels
     *
     * @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"WIFI_SECURITY_"}, value = {
            WIFI_SECURITY_OPEN,
            WIFI_SECURITY_PERSONAL,
            WIFI_SECURITY_ENTERPRISE_EAP,
            WIFI_SECURITY_ENTERPRISE_192})
    public @interface WifiSecurity {}

    /**
     * This MIME type is used for starting the device owner provisioning.
     *
     * <p>During device owner provisioning a device admin app is set as the owner of the device.
     * A device owner has full control over the device. The device owner can not be modified by the
     * user and the only way of resetting the device is if the device owner app calls a factory
     * reset.
     *
     * <p> A typical use case would be a device that is owned by a company, but used by either an
     * employee or client.
     *
     * <p> The NFC message must be sent to an unprovisioned device.
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
     * <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#M} </li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_EAP_METHOD}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_PHASE2_AUTH}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_CA_CERTIFICATE}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_USER_CERTIFICATE}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_IDENTITY}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_ANONYMOUS_IDENTITY}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li>
     * <li>{@link #EXTRA_PROVISIONING_WIFI_DOMAIN}, optional, supported from {@link
     * android.os.Build.VERSION_CODES#Q}</li></ul>
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
     * <p>The intent must be invoked via {@link Activity#startActivityForResult} to receive the
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
     * If the profile owner of an organization-owned managed profile changes some user
     * restriction explicitly on the parent user, this broadcast will <em>not</em> be
     * sent to the parent user.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
            = "android.app.action.DEVICE_POLICY_MANAGER_STATE_CHANGED";

    /**
     * Broadcast action: sent when the device owner is set, changed or cleared.
     *
     * This broadcast is sent only to the primary user.
     * @see #ACTION_PROVISION_MANAGED_DEVICE
     * @see DevicePolicyManager#transferOwnership(ComponentName, ComponentName, PersistableBundle)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_OWNER_CHANGED
            = "android.app.action.DEVICE_OWNER_CHANGED";

    /**
     * Broadcast action: sent when the factory reset protection (FRP) policy is changed.
     *
     * @see #setFactoryResetProtectionPolicy
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_FACTORY_RESET_PROTECTION)
    @SystemApi
    public static final String ACTION_RESET_PROTECTION_POLICY_CHANGED =
            "android.app.action.RESET_PROTECTION_POLICY_CHANGED";

    /**
     * Broadcast action: sent when there is a location update on a device in lost mode. This
     * broadcast is explicitly sent to the device policy controller app only.
     *
     * @see DevicePolicyManager#sendLostModeLocationUpdate
     * @hide
     */
    @SystemApi
    public static final String ACTION_LOST_MODE_LOCATION_UPDATE =
            "android.app.action.LOST_MODE_LOCATION_UPDATE";

    /**
     * Extra used with {@link #ACTION_LOST_MODE_LOCATION_UPDATE} to send the location of a device
     * in lost mode. Value is {@code Location}.
     *
     * @see DevicePolicyManager#sendLostModeLocationUpdate
     * @hide
     */
    @SystemApi
    public static final String EXTRA_LOST_MODE_LOCATION =
            "android.app.extra.LOST_MODE_LOCATION";

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
     * Constant to indicate the feature of disabling the camera. Used as argument to
     * {@link #createAdminSupportIntent(String)}.
     * @see #setCameraDisabled(ComponentName, boolean)
     */
    public static final String POLICY_DISABLE_CAMERA = "policy_disable_camera";

    /**
     * Constant to indicate the feature of disabling screen captures. Used as argument to
     * {@link #createAdminSupportIntent(String)}.
     * @see #setScreenCaptureDisabled(ComponentName, boolean)
     */
    public static final String POLICY_DISABLE_SCREEN_CAPTURE = "policy_disable_screen_capture";

    /**
     * Constant to indicate the feature of suspending app. Use it as the value of
     * {@link #EXTRA_RESTRICTION}.
     * @hide
     */
    public static final String POLICY_SUSPEND_PACKAGES = "policy_suspend_packages";

    /**
     * A String indicating a specific restricted feature. Can be a user restriction from the
     * {@link UserManager}, e.g. {@link UserManager#DISALLOW_ADJUST_VOLUME}, or one of the values
     * {@link #POLICY_DISABLE_CAMERA} or {@link #POLICY_DISABLE_SCREEN_CAPTURE}.
     * @see #createAdminSupportIntent(String)
     * @hide
     */
    @SystemApi
    public static final String EXTRA_RESTRICTION = "android.app.extra.RESTRICTION";

    /**
     * Activity action: have the user enter a new password.
     *
     * <p>For admin apps, this activity should be launched after using {@link
     * #setPasswordQuality(ComponentName, int)}, or {@link
     * #setPasswordMinimumLength(ComponentName, int)} to have the user enter a new password that
     * meets the current requirements. You can use {@link #isActivePasswordSufficient()} to
     * determine whether you need to have the user select a new password in order to meet the
     * current constraints. Upon being resumed from this activity, you can check the new
     * password characteristics to see if they are sufficient.
     *
     * <p>Non-admin apps can use {@link #getPasswordComplexity()} to check the current screen lock
     * complexity, and use this activity with extra {@link #EXTRA_PASSWORD_COMPLEXITY} to suggest
     * to users how complex the app wants the new screen lock to be. Note that both {@link
     * #getPasswordComplexity()} and the extra {@link #EXTRA_PASSWORD_COMPLEXITY} require the
     * calling app to have the permission {@link permission#REQUEST_PASSWORD_COMPLEXITY}.
     *
     * <p>If the intent is launched from within a managed profile with a profile
     * owner built against {@link android.os.Build.VERSION_CODES#M} or before,
     * this will trigger entering a new password for the parent of the profile.
     * For all other cases it will trigger entering a new password for the user
     * or profile it is launched from.
     *
     * @see #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_NEW_PASSWORD
            = "android.app.action.SET_NEW_PASSWORD";

    /**
     * An integer indicating the complexity level of the new password an app would like the user to
     * set when launching the action {@link #ACTION_SET_NEW_PASSWORD}.
     *
     * <p>Must be one of
     * <ul>
     *     <li>{@link #PASSWORD_COMPLEXITY_HIGH}
     *     <li>{@link #PASSWORD_COMPLEXITY_MEDIUM}
     *     <li>{@link #PASSWORD_COMPLEXITY_LOW}
     *     <li>{@link #PASSWORD_COMPLEXITY_NONE}
     * </ul>
     *
     * <p>If an invalid value is used, it will be treated as {@link #PASSWORD_COMPLEXITY_NONE}.
     */
    @RequiresPermission(REQUEST_PASSWORD_COMPLEXITY)
    public static final String EXTRA_PASSWORD_COMPLEXITY =
            "android.app.extra.PASSWORD_COMPLEXITY";

    /**
     * Constant for {@link #getPasswordComplexity()} and
     * {@link #setRequiredPasswordComplexity(int)}: no password.
     *
     * <p> When returned from {@link #getPasswordComplexity()}, the constant represents
     * the exact complexity band the password is in.
     * When passed to {@link #setRequiredPasswordComplexity(int), it sets the minimum complexity
     * band which the password must meet.
     */
    public static final int PASSWORD_COMPLEXITY_NONE = 0;

    /**
     * Constant for {@link #getPasswordComplexity()} and
     * {@link #setRequiredPasswordComplexity(int)}.
     * Define the low password complexity band as:
     * <ul>
     * <li>pattern
     * <li>PIN with repeating (4444) or ordered (1234, 4321, 2468) sequences
     * </ul>
     *
     * <p> When returned from {@link #getPasswordComplexity()}, the constant represents
     * the exact complexity band the password is in.
     * When passed to {@link #setRequiredPasswordComplexity(int), it sets the minimum complexity
     * band which the password must meet.
     *
     * @see #PASSWORD_QUALITY_SOMETHING
     * @see #PASSWORD_QUALITY_NUMERIC
     */
    public static final int PASSWORD_COMPLEXITY_LOW = 0x10000;

    /**
     * Constant for {@link #getPasswordComplexity()} and
     * {@link #setRequiredPasswordComplexity(int)}.
     * Define the medium password complexity band as:
     * <ul>
     * <li>PIN with <b>no</b> repeating (4444) or ordered (1234, 4321, 2468) sequences, length at
     * least 4
     * <li>alphabetic, length at least 4
     * <li>alphanumeric, length at least 4
     * </ul>
     *
     * <p> When returned from {@link #getPasswordComplexity()}, the constant represents
     * the exact complexity band the password is in.
     * When passed to {@link #setRequiredPasswordComplexity(int), it sets the minimum complexity
     * band which the password must meet.
     *
     * @see #PASSWORD_QUALITY_NUMERIC_COMPLEX
     * @see #PASSWORD_QUALITY_ALPHABETIC
     * @see #PASSWORD_QUALITY_ALPHANUMERIC
     */
    public static final int PASSWORD_COMPLEXITY_MEDIUM = 0x30000;

    /**
     * Constant for {@link #getPasswordComplexity()} and
     * {@link #setRequiredPasswordComplexity(int)}.
     * Define the high password complexity band as:
     * <ul>
     * <li>PIN with <b>no</b> repeating (4444) or ordered (1234, 4321, 2468) sequences, length at
     * least 8
     * <li>alphabetic, length at least 6
     * <li>alphanumeric, length at least 6
     * </ul>
     *
     * <p> When returned from {@link #getPasswordComplexity()}, the constant represents
     * the exact complexity band the password is in.
     * When passed to {@link #setRequiredPasswordComplexity(int), it sets the minimum complexity
     * band which the password must meet.
     *
     * @see #PASSWORD_QUALITY_NUMERIC_COMPLEX
     * @see #PASSWORD_QUALITY_ALPHABETIC
     * @see #PASSWORD_QUALITY_ALPHANUMERIC
     */
    public static final int PASSWORD_COMPLEXITY_HIGH = 0x50000;

    /**
     * A boolean extra for {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} requesting that only
     * device password requirement is enforced during the parent profile password enrolment flow.
     * <p> Normally when enrolling password for the parent profile, both the device-wide password
     * requirement (requirement set via {@link #getParentProfileInstance(ComponentName)} instance)
     * and the profile password requirement are enforced, if the profile currently does not have a
     * separate work challenge. By setting this to {@code true}, profile password requirement is
     * explicitly disregarded.
     *
     * @see #isActivePasswordSufficientForDeviceRequirement()
     */
    public static final String EXTRA_DEVICE_PASSWORD_REQUIREMENT_ONLY =
            "android.app.extra.DEVICE_PASSWORD_REQUIREMENT_ONLY";

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"PASSWORD_COMPLEXITY_"}, value = {
            PASSWORD_COMPLEXITY_NONE,
            PASSWORD_COMPLEXITY_LOW,
            PASSWORD_COMPLEXITY_MEDIUM,
            PASSWORD_COMPLEXITY_HIGH,
    })
    public @interface PasswordComplexity {}

    /**
     * Indicates that nearby streaming is not controlled by policy, which means nearby streaming is
     * allowed.
     */
    public static final int NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY = 0;

    /** Indicates that nearby streaming is disabled. */
    public static final int NEARBY_STREAMING_DISABLED = 1;

    /** Indicates that nearby streaming is enabled. */
    public static final int NEARBY_STREAMING_ENABLED = 2;

    /**
     * Indicates that nearby streaming is enabled only to devices offering a comparable level of
     * security, with the same authenticated managed account.
     */
    public static final int NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY = 3;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"NEARBY_STREAMING_"}, value = {
        NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY,
        NEARBY_STREAMING_DISABLED,
        NEARBY_STREAMING_ENABLED,
        NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY,
    })
    public @interface NearbyStreamingPolicy {}

    /**
     * Activity action: have the user enter a new password for the parent profile.
     * If the intent is launched from within a managed profile, this will trigger
     * entering a new password for the parent of the profile. The caller can optionally
     * set {@link #EXTRA_DEVICE_PASSWORD_REQUIREMENT_ONLY} to only enforce device-wide
     * password requirement. In all other cases the behaviour is identical to
     * {@link #ACTION_SET_NEW_PASSWORD}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_NEW_PARENT_PROFILE_PASSWORD
            = "android.app.action.SET_NEW_PARENT_PROFILE_PASSWORD";

    /**
     * Broadcast action: Tell the status bar to open the device monitoring dialog, e.g. when
     * Network logging was enabled and the user tapped the notification.
     * <p class="note">This is a protected intent that can only be sent by the system.</p>
     * @hide
     */
    public static final String ACTION_SHOW_DEVICE_MONITORING_DIALOG
            = "android.app.action.SHOW_DEVICE_MONITORING_DIALOG";

    /**
     * Broadcast Action: Sent after application delegation scopes are changed. The new delegation
     * scopes will be sent in an {@code ArrayList<String>} extra identified by the
     * {@link #EXTRA_DELEGATION_SCOPES} key.
     *
     * <p class="note"><b>Note:</b> This is a protected intent that can only be sent by the
     * system.</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED =
            "android.app.action.APPLICATION_DELEGATION_SCOPES_CHANGED";

    /**
     * An {@code ArrayList<String>} corresponding to the delegation scopes given to an app in the
     * {@link #ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED} broadcast.
     */
    public static final String EXTRA_DELEGATION_SCOPES = "android.app.extra.DELEGATION_SCOPES";

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
     * Broadcast action from ManagedProvisioning to notify that the latest change to
     * {@link UserManager#DISALLOW_SHARE_INTO_MANAGED_PROFILE} restriction has been successfully
     * applied (cross profile intent filters updated). Only usesd for CTS tests.
     * @hide
     */
    @SuppressLint("ActionValue")
    @TestApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DATA_SHARING_RESTRICTION_APPLIED =
            "android.app.action.DATA_SHARING_RESTRICTION_APPLIED";

    /**
     * Broadcast action: notify that a value of {@link Settings.Global#DEVICE_POLICY_CONSTANTS}
     * has been changed.
     * @hide
     */
    @SuppressLint("ActionValue")
    @TestApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_POLICY_CONSTANTS_CHANGED =
            "android.app.action.DEVICE_POLICY_CONSTANTS_CHANGED";

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
     * Possible policy values for permissions.
     *
     * @hide
     */
    @IntDef(prefix = { "PERMISSION_GRANT_STATE_" }, value = {
            PERMISSION_GRANT_STATE_DEFAULT,
            PERMISSION_GRANT_STATE_GRANTED,
            PERMISSION_GRANT_STATE_DENIED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionGrantState {}

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
     * Delegation of certificate installation and management. This scope grants access to the
     * {@link #getInstalledCaCerts}, {@link #hasCaCertInstalled}, {@link #installCaCert},
     * {@link #uninstallCaCert}, {@link #uninstallAllUserCaCerts} and {@link #installKeyPair} APIs.
     * This scope also grants the ability to read identifiers that the delegating device owner or
     * profile owner can obtain. See {@link #getEnrollmentSpecificId()}.
     */
    public static final String DELEGATION_CERT_INSTALL = "delegation-cert-install";

    /**
     * Delegation of application restrictions management. This scope grants access to the
     * {@link #setApplicationRestrictions} and {@link #getApplicationRestrictions} APIs.
     */
    public static final String DELEGATION_APP_RESTRICTIONS = "delegation-app-restrictions";

    /**
     * Delegation of application uninstall block. This scope grants access to the
     * {@link #setUninstallBlocked} API.
     */
    public static final String DELEGATION_BLOCK_UNINSTALL = "delegation-block-uninstall";

    /**
     * Delegation of permission policy and permission grant state. This scope grants access to the
     * {@link #setPermissionPolicy}, {@link #getPermissionGrantState},
     * and {@link #setPermissionGrantState} APIs.
     */
    public static final String DELEGATION_PERMISSION_GRANT = "delegation-permission-grant";

    /**
     * Delegation of package access state. This scope grants access to the
     * {@link #isApplicationHidden}, {@link #setApplicationHidden}, {@link #isPackageSuspended}, and
     * {@link #setPackagesSuspended} APIs.
     */
    public static final String DELEGATION_PACKAGE_ACCESS = "delegation-package-access";

    /**
     * Delegation for enabling system apps. This scope grants access to the {@link #enableSystemApp}
     * API.
     */
    public static final String DELEGATION_ENABLE_SYSTEM_APP = "delegation-enable-system-app";

    /**
     * Delegation for installing existing packages. This scope grants access to the
     * {@link #installExistingPackage} API.
     */
    public static final String DELEGATION_INSTALL_EXISTING_PACKAGE =
            "delegation-install-existing-package";

    /**
     * Delegation of management of uninstalled packages. This scope grants access to the
     * {@link #setKeepUninstalledPackages} and {@link #getKeepUninstalledPackages} APIs.
     */
    public static final String DELEGATION_KEEP_UNINSTALLED_PACKAGES =
            "delegation-keep-uninstalled-packages";

    /**
     * Grants access to {@link #setNetworkLoggingEnabled}, {@link #isNetworkLoggingEnabled} and
     * {@link #retrieveNetworkLogs}. Once granted the delegated app will start receiving
     * DelegatedAdminReceiver.onNetworkLogsAvailable() callback, and Device owner or Profile Owner
     * will no longer receive the DeviceAdminReceiver.onNetworkLogsAvailable() callback.
     * There can be at most one app that has this delegation.
     * If another app already had delegated network logging access,
     * it will lose the delegation when a new app is delegated.
     *
     * <p> Device Owner can grant this access since Android 10. Profile Owner of a managed profile
     * can grant this access since Android 12.
     */
    public static final String DELEGATION_NETWORK_LOGGING = "delegation-network-logging";

    /**
     * Grants access to selection of KeyChain certificates on behalf of requesting apps.
     * Once granted the app will start receiving
     * {@link DelegatedAdminReceiver#onChoosePrivateKeyAlias}. The caller (PO/DO) will
     * no longer receive {@link DeviceAdminReceiver#onChoosePrivateKeyAlias}.
     * There can be at most one app that has this delegation.
     * If another app already had delegated certificate selection access,
     * it will lose the delegation when a new app is delegated.
     * <p> The delegated app can also call {@link #grantKeyPairToApp} and
     * {@link #revokeKeyPairFromApp} to directly grant KeyChain keys to other apps.
     * <p> Can be granted by Device Owner or Profile Owner.
     */
    public static final String DELEGATION_CERT_SELECTION = "delegation-cert-selection";

    /**
     * Grants access to {@link #setSecurityLoggingEnabled}, {@link #isSecurityLoggingEnabled},
     * {@link #retrieveSecurityLogs}, and {@link #retrievePreRebootSecurityLogs}. Once granted the
     * delegated app will start receiving {@link DelegatedAdminReceiver#onSecurityLogsAvailable}
     * callback, and Device owner or Profile Owner will no longer receive the
     * {@link DeviceAdminReceiver#onSecurityLogsAvailable} callback. There can be at most one app
     * that has this delegation. If another app already had delegated security logging access, it
     * will lose the delegation when a new app is delegated.
     *
     * <p> Can only be granted by Device Owner or Profile Owner of an organization-owned
     * managed profile.
     */
    public static final String DELEGATION_SECURITY_LOGGING = "delegation-security-logging";

    /**
     * No management for current user in-effect. This is the default.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_UNMANAGED = 0;

    /**
     * Management partially setup, user setup needs to be completed.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_SETUP_INCOMPLETE = 1;

    /**
     * Management partially setup, user setup completed.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_SETUP_COMPLETE = 2;

    /**
     * Management setup and active on current user.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_SETUP_FINALIZED = 3;

    /**
     * Management partially setup on a managed profile.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_PROFILE_COMPLETE = 4;

    /**
     * Management setup on a managed profile.
     * <p>This is used as an intermediate state after {@link #STATE_USER_PROFILE_COMPLETE} once the
     * work profile has been created.
     * @hide
     */
    @SystemApi
    public static final int STATE_USER_PROFILE_FINALIZED = 5;

    /**
     * @hide
     */
    @IntDef(prefix = { "STATE_USER_" }, value = {
            STATE_USER_UNMANAGED,
            STATE_USER_SETUP_INCOMPLETE,
            STATE_USER_SETUP_COMPLETE,
            STATE_USER_SETUP_FINALIZED,
            STATE_USER_PROFILE_COMPLETE,
            STATE_USER_PROFILE_FINALIZED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserProvisioningState {}

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Unknown error code returned  for {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} and {@link #ACTION_PROVISION_MANAGED_USER}.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_UNKNOWN_ERROR = -1;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} and {@link #ACTION_PROVISION_MANAGED_USER}
     * when provisioning is allowed.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_OK = 0;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} when the device already has a
     * device owner.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_HAS_DEVICE_OWNER = 1;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} when the user has a profile owner
     *  and for {@link #ACTION_PROVISION_MANAGED_PROFILE} when the profile owner is already set.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_USER_HAS_PROFILE_OWNER = 2;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} when the user isn't running.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_USER_NOT_RUNNING = 3;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} if the device has already been
     * setup and for {@link #ACTION_PROVISION_MANAGED_USER} if the user has already been setup.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_USER_SETUP_COMPLETED = 4;

    /**
     * Code used to indicate that the device also has a user other than the system user.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_NONSYSTEM_USER_EXISTS = 5;

    /**
     * Code used to indicate that device has an account that prevents provisioning.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_ACCOUNTS_NOT_EMPTY = 6;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} if the user is not a system user.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_NOT_SYSTEM_USER = 7;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} and
     * {@link #ACTION_PROVISION_MANAGED_USER} when the device is a watch and is already paired.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_HAS_PAIRED = 8;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_PROFILE} and
     * {@link #ACTION_PROVISION_MANAGED_USER} on devices which do not support managed users.
     *
     * @see {@link PackageManager#FEATURE_MANAGED_USERS}
     * @hide
     */
    @SystemApi
    public static final int STATUS_MANAGED_USERS_NOT_SUPPORTED = 9;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_USER} if the user is a system user and
     * for {@link #ACTION_PROVISION_MANAGED_DEVICE} on devices running headless system user mode
     * and the user is a system user.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_SYSTEM_USER = 10;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_PROFILE} when the user cannot have more
     * managed profiles.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_CANNOT_ADD_MANAGED_PROFILE = 11;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} on devices which do not support device
     * admins.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_DEVICE_ADMIN_NOT_SUPPORTED = 13;

    /**
     * TODO (b/137101239): clean up split system user codes
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * @hide
     * @deprecated not used anymore but can't be removed since it's a @TestApi.
     */
    @Deprecated
    @TestApi
    public static final int STATUS_SPLIT_SYSTEM_USER_DEVICE_SYSTEM_USER = 14;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} and
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} on devices which do not support provisioning.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_PROVISIONING_NOT_ALLOWED_FOR_NON_DEVELOPER_USERS = 15;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} when provisioning a DPC which does
     * not support headless system user mode on a headless system user mode device.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_HEADLESS_SYSTEM_USER_MODE_NOT_SUPPORTED = 16;

    /**
     * Result code for {@link #checkProvisioningPrecondition}.
     *
     * <p>Returned for {@link #ACTION_PROVISION_MANAGED_DEVICE} when provisioning a DPC into the
     * {@link DeviceAdminInfo#HEADLESS_DEVICE_OWNER_MODE_SINGLE_USER} mode but only the system
     * user exists on the device.
     *
     * @hide
     */
    @SystemApi
    public static final int STATUS_HEADLESS_ONLY_SYSTEM_USER = 17;

    /**
     * Result codes for {@link #checkProvisioningPrecondition} indicating all the provisioning pre
     * conditions.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_UNKNOWN_ERROR, STATUS_OK, STATUS_HAS_DEVICE_OWNER, STATUS_USER_HAS_PROFILE_OWNER,
            STATUS_USER_NOT_RUNNING, STATUS_USER_SETUP_COMPLETED, STATUS_NOT_SYSTEM_USER,
            STATUS_HAS_PAIRED, STATUS_MANAGED_USERS_NOT_SUPPORTED, STATUS_SYSTEM_USER,
            STATUS_CANNOT_ADD_MANAGED_PROFILE, STATUS_DEVICE_ADMIN_NOT_SUPPORTED,
            STATUS_SPLIT_SYSTEM_USER_DEVICE_SYSTEM_USER,
            STATUS_PROVISIONING_NOT_ALLOWED_FOR_NON_DEVELOPER_USERS,
            STATUS_HEADLESS_SYSTEM_USER_MODE_NOT_SUPPORTED, STATUS_HEADLESS_ONLY_SYSTEM_USER
    })
    public @interface ProvisioningPrecondition {}

    /**
     * Disable all configurable SystemUI features during LockTask mode. This includes,
     * <ul>
     *     <li>system info area in the status bar (connectivity icons, clock, etc.)
     *     <li>notifications (including alerts, icons, and the notification shade)
     *     <li>Home button
     *     <li>Recents button and UI
     *     <li>global actions menu (i.e. power button menu)
     *     <li>keyguard
     * </ul>
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_NONE = 0;

    /**
     * Enable the system info area in the status bar during LockTask mode. The system info area
     * usually occupies the right side of the status bar (although this can differ across OEMs). It
     * includes all system information indicators, such as date and time, connectivity, battery,
     * vibration mode, etc.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_SYSTEM_INFO = 1;

    /**
     * Enable notifications during LockTask mode. This includes notification icons on the status
     * bar, heads-up notifications, and the expandable notification shade. Note that the Quick
     * Settings panel remains disabled. This feature flag can only be used in combination with
     * {@link #LOCK_TASK_FEATURE_HOME}. {@link #setLockTaskFeatures(ComponentName, int)}
     * throws an {@link IllegalArgumentException} if this feature flag is defined without
     * {@link #LOCK_TASK_FEATURE_HOME}.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_NOTIFICATIONS = 1 << 1;

    /**
     * Enable the Home button during LockTask mode. Note that if a custom launcher is used, it has
     * to be registered as the default launcher with
     * {@link #addPersistentPreferredActivity(ComponentName, IntentFilter, ComponentName)}, and its
     * package needs to be allowlisted for LockTask with
     * {@link #setLockTaskPackages(ComponentName, String[])}.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_HOME = 1 << 2;

    /**
     * Enable the Overview button and the Overview screen during LockTask mode. This feature flag
     * can only be used in combination with {@link #LOCK_TASK_FEATURE_HOME}, and
     * {@link #setLockTaskFeatures(ComponentName, int)} will throw an
     * {@link IllegalArgumentException} if this feature flag is defined without
     * {@link #LOCK_TASK_FEATURE_HOME}.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_OVERVIEW = 1 << 3;

    /**
     * Enable the global actions dialog during LockTask mode. This is the dialog that shows up when
     * the user long-presses the power button, for example. Note that the user may not be able to
     * power off the device if this flag is not set.
     *
     * <p>This flag is enabled by default until {@link #setLockTaskFeatures(ComponentName, int)} is
     * called for the first time.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_GLOBAL_ACTIONS = 1 << 4;

    /**
     * Enable the keyguard during LockTask mode. Note that if the keyguard is already disabled with
     * {@link #setKeyguardDisabled(ComponentName, boolean)}, setting this flag will have no effect.
     * If this flag is not set, the keyguard will not be shown even if the user has a lock screen
     * credential.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_KEYGUARD = 1 << 5;

    /**
     * Enable blocking of non-allowlisted activities from being started into a locked task.
     *
     * @see #setLockTaskFeatures(ComponentName, int)
     */
    public static final int LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 1 << 6;

    /**
     * Flags supplied to {@link #setLockTaskFeatures(ComponentName, int)}.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "LOCK_TASK_FEATURE_" }, value = {
            LOCK_TASK_FEATURE_NONE,
            LOCK_TASK_FEATURE_SYSTEM_INFO,
            LOCK_TASK_FEATURE_NOTIFICATIONS,
            LOCK_TASK_FEATURE_HOME,
            LOCK_TASK_FEATURE_OVERVIEW,
            LOCK_TASK_FEATURE_GLOBAL_ACTIONS,
            LOCK_TASK_FEATURE_KEYGUARD,
            LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
    })
    public @interface LockTaskFeature {}

    /**
     * Service action: Action for a service that device owner and profile owner can optionally
     * own.  If a device owner or a profile owner has such a service, the system tries to keep
     * a bound connection to it, in order to keep their process always running.
     * The service must be protected with the {@link android.Manifest.permission#BIND_DEVICE_ADMIN}
     * permission.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_DEVICE_ADMIN_SERVICE
            = "android.app.action.DEVICE_ADMIN_SERVICE";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"ID_TYPE_"}, value = {
        ID_TYPE_BASE_INFO,
        ID_TYPE_SERIAL,
        ID_TYPE_IMEI,
        ID_TYPE_MEID,
        ID_TYPE_INDIVIDUAL_ATTESTATION
    })
    public @interface AttestationIdType {}

    /**
     * Specifies that the device should attest its manufacturer details. For use with
     * {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_BASE_INFO = 1;

    /**
     * Specifies that the device should attest its serial number. For use with
     * {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_SERIAL = 2;

    /**
     * Specifies that the device should attest its IMEI. For use with {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_IMEI = 4;

    /**
     * Specifies that the device should attest its MEID. For use with {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_MEID = 8;

    /**
     * Specifies that the device should attest using an individual attestation certificate.
     * For use with {@link #generateKeyPair}.
     *
     * @see #generateKeyPair
     */
    public static final int ID_TYPE_INDIVIDUAL_ATTESTATION = 16;

    /**
     * Service-specific error code for {@link #generateKeyPair}:
     * Indicates the call has failed due to StrongBox unavailability.
     * @hide
     */
    public static final int KEY_GEN_STRONGBOX_UNAVAILABLE = 1;

    /**
     * Specifies that the calling app should be granted access to the installed credentials
     * immediately. Otherwise, access to the credentials will be gated by user approval.
     * For use with {@link #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)}
     *
     * @see #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)
     */
    public static final int INSTALLKEY_REQUEST_CREDENTIALS_ACCESS = 1;

    /**
     * Specifies that a user can select the key via the Certificate Selection prompt.
     * If this flag is not set when calling {@link #installKeyPair}, the key can only be granted
     * access by implementing {@link android.app.admin.DeviceAdminReceiver#onChoosePrivateKeyAlias}.
     * For use with {@link #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)}
     *
     * @see #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)
     */
    public static final int INSTALLKEY_SET_USER_SELECTABLE = 2;

    /**
     * Broadcast action: sent when the profile owner is set, changed or cleared.
     *
     * This broadcast is sent only to the user managed by the new profile owner.
     * @see DevicePolicyManager#transferOwnership(ComponentName, ComponentName, PersistableBundle)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PROFILE_OWNER_CHANGED =
            "android.app.action.PROFILE_OWNER_CHANGED";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"PRIVATE_DNS_MODE_"}, value = {
            PRIVATE_DNS_MODE_UNKNOWN,
            PRIVATE_DNS_MODE_OFF,
            PRIVATE_DNS_MODE_OPPORTUNISTIC,
            PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
    })
    public @interface PrivateDnsMode {}

    /**
     * Specifies that the Private DNS setting is in an unknown state.
     */
    public static final int PRIVATE_DNS_MODE_UNKNOWN = 0;

    /**
     * Specifies that Private DNS was turned off completely.
     */
    public static final int PRIVATE_DNS_MODE_OFF = 1;

    /**
     * Specifies that the device owner requested opportunistic DNS over TLS
     */
    public static final int PRIVATE_DNS_MODE_OPPORTUNISTIC = 2;

    /**
     * Specifies that the device owner configured a specific host to use for Private DNS.
     */
    public static final int PRIVATE_DNS_MODE_PROVIDER_HOSTNAME = 3;

    /**
     * Callback used in {@link #installSystemUpdate} to indicate that there was an error while
     * trying to install an update.
     */
    public abstract static class InstallSystemUpdateCallback {
        /** Represents an unknown error while trying to install an update. */
        public static final int UPDATE_ERROR_UNKNOWN = 1;

        /** Represents the update file being intended for different OS version. */
        public static final int UPDATE_ERROR_INCORRECT_OS_VERSION = 2;

        /**
         * Represents the update file being wrong; e.g. payloads are mismatched, or the wrong
         * compression method is used.
         */
        public static final int UPDATE_ERROR_UPDATE_FILE_INVALID = 3;

        /** Represents that the file could not be found. */
        public static final int UPDATE_ERROR_FILE_NOT_FOUND = 4;

        /** Represents the battery being too low to apply an update. */
        public static final int UPDATE_ERROR_BATTERY_LOW = 5;

        /**
         * Method invoked when there was an error while installing an update.
         *
         * <p>The given error message is not intended to be user-facing. It is intended to be
         * reported back to the IT admin to be read.
         */
        public void onInstallUpdateError(
                @InstallUpdateCallbackErrorConstants int errorCode, @NonNull String errorMessage) {
        }
    }

    /**
     * @hide
     */
    @IntDef(prefix = { "UPDATE_ERROR_" }, value = {
            InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN,
            InstallSystemUpdateCallback.UPDATE_ERROR_INCORRECT_OS_VERSION,
            InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID,
            InstallSystemUpdateCallback.UPDATE_ERROR_FILE_NOT_FOUND,
            InstallSystemUpdateCallback.UPDATE_ERROR_BATTERY_LOW
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstallUpdateCallbackErrorConstants {}

    /**
     * The selected mode has been set successfully. If the mode is
     * {@code PRIVATE_DNS_MODE_PROVIDER_HOSTNAME} then it implies the supplied host is valid
     * and reachable.
     */
    public static final int PRIVATE_DNS_SET_NO_ERROR = 0;

    /**
     * If the {@code privateDnsHost} provided was of a valid hostname but that host was found
     * to not support DNS-over-TLS.
     */
    public static final int PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING = 1;

    /**
     * General failure to set the Private DNS mode, not due to one of the reasons listed above.
     */
    public static final int PRIVATE_DNS_SET_ERROR_FAILURE_SETTING = 2;

    /**
     * @hide
     */
    @IntDef(prefix = {"PRIVATE_DNS_SET_"}, value = {
            PRIVATE_DNS_SET_NO_ERROR,
            PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING,
            PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PrivateDnsModeErrorCodes {}

    /**
     * Activity action: Starts the administrator to get the mode for the provisioning.
     * This intent may contain the following extras:
     * <ul>
     *     <li>{@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}</li>
     *     <li>{@link #EXTRA_PROVISIONING_IMEI}</li>
     *     <li>{@link #EXTRA_PROVISIONING_SERIAL_NUMBER}</li>
     *     <li>{@link #EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES}</li>
     *     <li>{@link #EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT}</li>
     * </ul>
     *
     * <p>The target activity should return one of the following values in
     * {@link #EXTRA_PROVISIONING_MODE} as result:
     * <ul>
     *     <li>{@link #PROVISIONING_MODE_FULLY_MANAGED_DEVICE}</li>
     *     <li>{@link #PROVISIONING_MODE_MANAGED_PROFILE}</li>
     * </ul>
     *
     * <p>If performing fully-managed device provisioning and the admin app desires to show its
     * own education screens, the target activity can additionally return
     * {@link #EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS} set to <code>true</code>.
     *
     * <p>The target activity may also return the account that needs to be migrated from primary
     * user to managed profile in case of a profile owner provisioning in
     * {@link #EXTRA_PROVISIONING_ACCOUNT_TO_MIGRATE} as result.
     *
     * <p>The target activity may also include the {@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE}
     * extra in the intent result. The values of this {@link android.os.PersistableBundle} will be
     * sent as an intent extra of the same name to the {@link #ACTION_ADMIN_POLICY_COMPLIANCE}
     * activity, along with the values of the {@link #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE} extra
     * that are already supplied to this activity.
     *
     * <p>Other extras the target activity may include in the intent result:
     * <ul>
     *     <li>{@link #EXTRA_PROVISIONING_DISCLAIMERS}</li>
     *     <li>{@link #EXTRA_PROVISIONING_SKIP_ENCRYPTION}</li>
     *     <li>{@link #EXTRA_PROVISIONING_KEEP_SCREEN_ON}</li>
     *     <li>{@link #EXTRA_PROVISIONING_KEEP_ACCOUNT_ON_MIGRATION} for work profile
     *     provisioning</li>
     *     <li>{@link #EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED} for work profile
     *     provisioning</li>
     *     <li>{@link #EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT} for fully-managed
     *     device provisioning</li>
     *     <li>{@link #EXTRA_PROVISIONING_LOCALE} for fully-managed device provisioning</li>
     *     <li>{@link #EXTRA_PROVISIONING_LOCAL_TIME} for fully-managed device provisioning</li>
     *     <li>{@link #EXTRA_PROVISIONING_TIME_ZONE} for fully-managed device provisioning</li>
     * </ul>
     *
     * @see #ACTION_ADMIN_POLICY_COMPLIANCE
     */
    public static final String ACTION_GET_PROVISIONING_MODE =
            "android.app.action.GET_PROVISIONING_MODE";

    /**
     * A string extra holding the IMEI (International Mobile Equipment Identity) of the device.
     */
    public static final String EXTRA_PROVISIONING_IMEI = "android.app.extra.PROVISIONING_IMEI";

    /**
     * A string extra holding the serial number of the device.
     */
    public static final String EXTRA_PROVISIONING_SERIAL_NUMBER =
            "android.app.extra.PROVISIONING_SERIAL_NUMBER";

    /**
     * An intent extra holding the provisioning mode returned by the administrator.
     * The value of this extra must be one of the values provided in {@link
     * #EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES}, which is provided as an intent extra to
     * the admin app's {@link #ACTION_GET_PROVISIONING_MODE} activity.
     *
     * @see #PROVISIONING_MODE_FULLY_MANAGED_DEVICE
     * @see #PROVISIONING_MODE_MANAGED_PROFILE
     */
    public static final String EXTRA_PROVISIONING_MODE =
            "android.app.extra.PROVISIONING_MODE";

    /**
     * An integer extra indication what provisioning modes should be available for the admin app
     * to pick.
     *
     * <p>The default value is {@link #FLAG_SUPPORTED_MODES_ORGANIZATION_OWNED}.
     *
     * <p>The value of this extra will determine the contents of the {@link
     * #EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES} array that is passed to the admin app as an
     * extra to its {@link #ACTION_GET_PROVISIONING_MODE} activity.
     *
     * <p>If one of the possible admin app choices is a personally-owned work profile, then the
     * IMEI and serial number will not be passed to the admin app's {@link
     * #ACTION_GET_PROVISIONING_MODE} activity via the {@link #EXTRA_PROVISIONING_IMEI} and {@link
     * #EXTRA_PROVISIONING_SERIAL_NUMBER} respectively.
     *
     * <p>The allowed flag combinations are:
     * <ul>
     *     <li>{@link #FLAG_SUPPORTED_MODES_ORGANIZATION_OWNED}</li>
     *     <li>{@link #FLAG_SUPPORTED_MODES_PERSONALLY_OWNED}</li>
     *     <li>{@link #FLAG_SUPPORTED_MODES_DEVICE_OWNER}</li>
     *     <li>{@link #FLAG_SUPPORTED_MODES_ORGANIZATION_OWNED}
     *             | {@link #FLAG_SUPPORTED_MODES_PERSONALLY_OWNED}</li>
     * </ul>
     *
     * <p>This extra is only respected when provided alongside the {@link
     * #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent action.
     *
     * @see #FLAG_SUPPORTED_MODES_ORGANIZATION_OWNED
     * @see #FLAG_SUPPORTED_MODES_PERSONALLY_OWNED
     * @see #FLAG_SUPPORTED_MODES_DEVICE_OWNER
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_SUPPORTED_MODES =
            "android.app.extra.PROVISIONING_SUPPORTED_MODES";

    /**
     * A boolean extra which determines whether to skip the ownership disclaimer screen during the
     * provisioning flow. The default value is {@code false}.
     *
     * If the value is {@code true}, the provisioning initiator must display a device ownership
     * disclaimer screen similar to that provided in AOSP.
     *
     * <p>This extra is only respected when provided alongside the {@link
     * #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent action.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_SKIP_OWNERSHIP_DISCLAIMER =
            "android.app.extra.PROVISIONING_SKIP_OWNERSHIP_DISCLAIMER";

    /**
     * An {@link ArrayList} of {@link Integer} extra specifying the allowed provisioning modes.
     * <p>This extra will be passed to the admin app's {@link #ACTION_GET_PROVISIONING_MODE}
     * activity, whose result intent must contain {@link #EXTRA_PROVISIONING_MODE} set to one of
     * the values in this array.
     * <p>If the value set to {@link #EXTRA_PROVISIONING_MODE} is not in the array,
     * provisioning will fail.
     * @see #PROVISIONING_MODE_MANAGED_PROFILE
     * @see #PROVISIONING_MODE_FULLY_MANAGED_DEVICE
     */
    public static final String EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES =
            "android.app.extra.PROVISIONING_ALLOWED_PROVISIONING_MODES";

    /**
     * The provisioning mode for fully managed device.
     */
    public static final int PROVISIONING_MODE_FULLY_MANAGED_DEVICE = 1;

    /**
     * The provisioning mode for managed profile.
     */
    public static final int PROVISIONING_MODE_MANAGED_PROFILE = 2;

    /**
     * The provisioning mode for a managed profile on a personal device.
     * <p>This mode is only available when the provisioning initiator has explicitly instructed the
     * provisioning flow to support managed profile on a personal device provisioning. In that case,
     * {@link #PROVISIONING_MODE_MANAGED_PROFILE} corresponds to an organization-owned managed
     * profile, whereas this constant corresponds to a personally-owned managed profile.
     *
     * @see #EXTRA_PROVISIONING_MODE
     */
    public static final int PROVISIONING_MODE_MANAGED_PROFILE_ON_PERSONAL_DEVICE = 3;

    /**
     * A {@code boolean} flag that indicates whether the provisioning flow should return before
     * starting the admin app's {@link #ACTION_ADMIN_POLICY_COMPLIANCE} handler. The default value
     * is {@code true}.
     *
     * <p>If this extra is set to {@code true}, then when the provisioning flow returns back to the
     * provisioning initiator, provisioning will not be complete. The provisioning initiator can
     * use this opportunity to do its own preparatory steps prior to the launch of the admin app's
     * {@link #ACTION_ADMIN_POLICY_COMPLIANCE} handler. It is the responsibility of the
     * provisioning initiator to ensure that the provisioning flow is then resumed and completed.
     *
     * <p>If this extra is set to {@code false}, then when the provisioning flow returns back to
     * the provisioning initiator, provisioning will be complete. Note that device owner
     * provisioning is not currently supported for the this scenario.
     *
     * <p>This extra is only respected when provided alongside the {@link
     * #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent action.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PROVISIONING_RETURN_BEFORE_POLICY_COMPLIANCE =
            "android.app.extra.PROVISIONING_RETURN_BEFORE_POLICY_COMPLIANCE";

    /**
     * A {@code boolean} flag that indicates whether the screen should be on throughout the
     * provisioning flow.
     *
     * <p>This extra can either be passed as an extra to the {@link
     * #ACTION_PROVISION_MANAGED_PROFILE} intent, or it can be returned by the
     * admin app when performing the admin-integrated provisioning flow as a result of the
     * {@link #ACTION_GET_PROVISIONING_MODE} activity.
     *
     * @deprecated from {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, the flag wouldn't
     * be functional. The screen is kept on throughout the provisioning flow.
     */

    @Deprecated
    public static final String EXTRA_PROVISIONING_KEEP_SCREEN_ON =
            "android.app.extra.PROVISIONING_KEEP_SCREEN_ON";

    /**
     * Activity action: Starts the administrator to show policy compliance for the provisioning.
     * This action is used any time that the administrator has an opportunity to show policy
     * compliance before the end of setup wizard. This could happen as part of the admin-integrated
     * provisioning flow (in which case this gets sent after {@link #ACTION_GET_PROVISIONING_MODE}),
     * or it could happen during provisioning finalization if the administrator supports
     * finalization during setup wizard.
     *
     * <p>Intents with this action may also be supplied with the {@link
     * #EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE} extra.
     *
     * @see #ACTION_GET_PROVISIONING_MODE
     */
    public static final String ACTION_ADMIN_POLICY_COMPLIANCE =
            "android.app.action.ADMIN_POLICY_COMPLIANCE";

    /**
     * Activity action: Starts the device policy management role holder updater.
     *
     * <p>The activity must handle the device policy management role holder update and set the
     * intent result. This can include {@link Activity#RESULT_OK} if the update was successful,
     * {@link #RESULT_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER_RECOVERABLE_ERROR} if
     * it encounters a problem that may be solved by relaunching it again, {@link
     * #RESULT_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER_PROVISIONING_DISABLED} if role holder
     * provisioning is disabled, or {@link
     * #RESULT_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER_UNRECOVERABLE_ERROR} if it encounters
     * any other problem that will not be solved by relaunching it again.
     *
     * <p>If this activity has additional internal conditions which are not met, it should return
     * {@link #RESULT_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER_UNRECOVERABLE_ERROR}.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.LAUNCH_DEVICE_MANAGER_SETUP)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER =
            "android.app.action.UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER";

    /**
     * Result code that can be returned by the {@link
     * #ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER} handler if it encounters a problem
     * that may be solved by relaunching it again.
     *
     * @hide
     */
    @SystemApi
    public static final int RESULT_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER_RECOVERABLE_ERROR =
            1;

    /**
     * Result code that can be returned by the {@link
     * #ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER} handler if it encounters a problem that
     * will not be solved by relaunching it again.
     *
     * @hide
     */
    @SystemApi
    public static final int RESULT_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER_UNRECOVERABLE_ERROR =
            2;

    /**
     * Result code that can be returned by the {@link
     * #ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER} handler if role holder provisioning
     * is disabled.
     *
     * @hide
     */
    @SystemApi
    public static final int
            RESULT_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER_PROVISIONING_DISABLED = 3;

    /**
     * An {@code int} extra that specifies one of {@link
     * #ROLE_HOLDER_UPDATE_FAILURE_STRATEGY_FAIL_PROVISIONING} or {@link
     * #ROLE_HOLDER_UPDATE_FAILURE_STRATEGY_FALLBACK_TO_PLATFORM_PROVISIONING}.
     *
     * <p>The failure strategy specifies how the platform should handle a failed device policy
     * management role holder update via {@link
     * #ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER} when {@link
     * #EXTRA_PROVISIONING_ALLOW_OFFLINE} is not set or set to {@code false}.
     *
     * <p>This extra may be supplied as part of the {@link
     * #ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER} result intent.
     *
     * <p>Default value is {@link #ROLE_HOLDER_UPDATE_FAILURE_STRATEGY_FAIL_PROVISIONING}.
     *
     * @hide
     */
    public static final String EXTRA_ROLE_HOLDER_UPDATE_FAILURE_STRATEGY =
            "android.app.extra.ROLE_HOLDER_UPDATE_FAILURE_STRATEGY";

    /**
     * Possible values for {@link #EXTRA_ROLE_HOLDER_UPDATE_FAILURE_STRATEGY}.
     *
     * @hide
     */
    @IntDef(prefix = { "ROLE_HOLDER_UPDATE_FAILURE_STRATEGY_" }, value = {
            ROLE_HOLDER_UPDATE_FAILURE_STRATEGY_FAIL_PROVISIONING,
            ROLE_HOLDER_UPDATE_FAILURE_STRATEGY_FALLBACK_TO_PLATFORM_PROVISIONING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RoleHolderUpdateFailureStrategy {}

    /**
     * A value for {@link #EXTRA_ROLE_HOLDER_UPDATE_FAILURE_STRATEGY} indicating that upon
     * failure to update the role holder, provisioning should fail.
     *
     * @hide
     */
    public static final int ROLE_HOLDER_UPDATE_FAILURE_STRATEGY_FAIL_PROVISIONING = 1;

    /**
     * A value for {@link #EXTRA_ROLE_HOLDER_UPDATE_FAILURE_STRATEGY} indicating that upon
     * failure to update the role holder, provisioning should fallback to be platform-driven.
     *
     * @hide
     */
    public static final int ROLE_HOLDER_UPDATE_FAILURE_STRATEGY_FALLBACK_TO_PLATFORM_PROVISIONING =
            2;

    /**
     * An {@code int} extra which contains the result code of the last attempt to update
     * the device policy management role holder via {@link
     * #ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER}.
     *
     * <p>This extra is provided to the device policy management role holder via either {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} or {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_PROFILE} when started after the role holder
     * had previously returned {@link #RESULT_UPDATE_ROLE_HOLDER}.
     *
     * <p>If the role holder update had failed, the role holder can use the value of this extra to
     * make a decision whether to fail the provisioning flow or to carry on with the older version
     * of the role holder.
     *
     * <p>Possible values can be:
     * <ul>
     *    <li>{@link Activity#RESULT_OK} if the update was successful
     *    <li>{@link #RESULT_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER_RECOVERABLE_ERROR} if it
     *    encounters a problem that may be solved by relaunching it again.
     *    <li>{@link #RESULT_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER_UNRECOVERABLE_ERROR} if
     *    it encounters a problem that will not be solved by relaunching it again.
     *    <li>Any other value returned by {@link
     *    #ACTION_UPDATE_DEVICE_POLICY_MANAGEMENT_ROLE_HOLDER}
     * </ul>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ROLE_HOLDER_UPDATE_RESULT_CODE =
            "android.app.extra.ROLE_HOLDER_UPDATE_RESULT_CODE";

    /**
     * An {@link Intent} extra which resolves to a custom user consent screen.
     *
     * <p>If this extra is provided to the device policy management role holder via either {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} or {@link
     * #ACTION_ROLE_HOLDER_PROVISION_MANAGED_PROFILE}, the device policy management role holder must
     * launch this intent which shows the custom user consent screen, replacing its own standard
     * consent screen.
     *
     * <p>If this extra is provided, it is the responsibility of the intent handler to show the
     * list of disclaimers which are normally shown by the standard consent screen:
     * <ul>
     *     <li>Disclaimers set by the IT admin via the {@link #EXTRA_PROVISIONING_DISCLAIMERS}
     *     provisioning extra</li>
     *     <li>For fully-managed device provisioning, disclaimers defined in system apps via the
     *     {@link #EXTRA_PROVISIONING_DISCLAIMER_HEADER} and {@link
     *     #EXTRA_PROVISIONING_DISCLAIMER_CONTENT} metadata in their manifests</li>
     *     <li>General disclaimer relevant to the provisioning mode</li>
     * </ul>
     *
     * <p>When this {@link Intent} is started, the following intent extras will be supplied:
     * <ul>
     *     <li>{@link #EXTRA_PROVISIONING_DISCLAIMERS}</li>
     *     <li>{@link #EXTRA_PROVISIONING_MODE}</li>
     *     <li>{@link #EXTRA_PROVISIONING_LOCALE}</li>
     *     <li>{@link #EXTRA_PROVISIONING_LOCAL_TIME}</li>
     *     <li>{@link #EXTRA_PROVISIONING_TIME_ZONE}</li>
     *     <li>{@link #EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS}</li>
     * </ul>
     *
     * <p>If the custom consent screens are granted by the user {@link Activity#RESULT_OK} is
     * returned, otherwise {@link Activity#RESULT_CANCELED} is returned. The device policy
     * management role holder should ensure that the provisioning flow terminates immediately if
     * consent is not granted by the user.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String EXTRA_PROVISIONING_ROLE_HOLDER_CUSTOM_USER_CONSENT_INTENT =
            "android.app.extra.PROVISIONING_ROLE_HOLDER_CUSTOM_USER_CONSENT_INTENT";

    /**
     * Activity action: attempts to establish network connection
     *
     * <p>This intent can be accompanied by any of the relevant provisioning extras related to
     * network connectivity, such as:
     * <ul>
     *     <li>{@link #EXTRA_PROVISIONING_WIFI_SSID}</li>
     *     <li>{@link #EXTRA_PROVISIONING_WIFI_HIDDEN}</li>
     *     <li>{@link #EXTRA_PROVISIONING_WIFI_SECURITY_TYPE}</li>
     *     <li>{@link #EXTRA_PROVISIONING_WIFI_PASSWORD}</li>
     *     <li>{@link #EXTRA_PROVISIONING_WIFI_PROXY_HOST}</li>
     *     <li>{@link #EXTRA_PROVISIONING_WIFI_PROXY_PORT}</li>
     *     <li>{@link #EXTRA_PROVISIONING_WIFI_PROXY_BYPASS}</li>
     *     <li>{@link #EXTRA_PROVISIONING_WIFI_PAC_URL}</li>
     *     <li>{@code #EXTRA_PROVISIONING_WIFI_EAP_METHOD}</li>
     *     <li>{@code #EXTRA_PROVISIONING_WIFI_PHASE2_AUTH}</li>
     *     <li>{@code #EXTRA_PROVISIONING_WIFI_CA_CERTIFICATE}</li>
     *     <li>{@code #EXTRA_PROVISIONING_WIFI_USER_CERTIFICATE}</li>
     *     <li>{@code #EXTRA_PROVISIONING_WIFI_IDENTITY}</li>
     *     <li>{@code #EXTRA_PROVISIONING_WIFI_ANONYMOUS_IDENTITY}</li>
     *     <li>{@code #EXTRA_PROVISIONING_WIFI_DOMAIN}</li>
     * </ul>
     *
     * <p>If there are provisioning extras related to network connectivity, this activity
     * attempts to connect to the specified network. Otherwise it prompts the end-user to connect.
     *
     * <p>This activity is meant to be started by the provisioning initiator prior to starting
     * {@link #ACTION_PROVISION_MANAGED_PROFILE} or {@link
     * #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}.
     *
     * <p>Note that network connectivity is still also handled when provisioning via {@link
     * #ACTION_PROVISION_MANAGED_PROFILE} or {@link
     * #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE}. {@link
     * #ACTION_ESTABLISH_NETWORK_CONNECTION} should only be used in cases when the provisioning
     * initiator would like to do some additional logic after the network connectivity step and
     * before the start of provisioning.
     *
     * If network connection is established, {@link Activity#RESULT_OK} will be returned. Otherwise
     * the result will be {@link Activity#RESULT_CANCELED}.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DISPATCH_PROVISIONING_MESSAGE)
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    @SystemApi
    public static final String ACTION_ESTABLISH_NETWORK_CONNECTION =
            "android.app.action.ESTABLISH_NETWORK_CONNECTION";

    /**
     * Maximum supported password length. Kind-of arbitrary.
     * @hide
     */
    public static final int MAX_PASSWORD_LENGTH = 16;

    /**
     * Service Action: Service implemented by a device owner or profile owner supervision app to
     * provide a secondary lockscreen.
     * @hide
     */
    @SystemApi
    public static final String ACTION_BIND_SECONDARY_LOCKSCREEN_SERVICE =
            "android.app.action.BIND_SECONDARY_LOCKSCREEN_SERVICE";

    /**
     * Return value for {@link #getPersonalAppsSuspendedReasons} when personal apps are not
     * suspended.
     */
    public static final int PERSONAL_APPS_NOT_SUSPENDED = 0;

    /**
     * Flag for {@link #getPersonalAppsSuspendedReasons} return value. Set when personal
     * apps are suspended by an admin explicitly via {@link #setPersonalAppsSuspended}.
     */
    public static final int PERSONAL_APPS_SUSPENDED_EXPLICITLY = 1 << 0;

    /**
     * Flag for {@link #getPersonalAppsSuspendedReasons} return value. Set when personal apps are
     * suspended by framework because managed profile was off for longer than allowed by policy.
     * @see #setManagedProfileMaximumTimeOff
     */
    public static final int PERSONAL_APPS_SUSPENDED_PROFILE_TIMEOUT = 1 << 1;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "PERSONAL_APPS_" }, value = {
            PERSONAL_APPS_NOT_SUSPENDED,
            PERSONAL_APPS_SUSPENDED_EXPLICITLY,
            PERSONAL_APPS_SUSPENDED_PROFILE_TIMEOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PersonalAppsSuspensionReason {}

    /**
     * The default device owner type for a managed device.
     *
     * @hide
     */
    @TestApi
    public static final int DEVICE_OWNER_TYPE_DEFAULT = 0;

    /**
     * The device owner type for a financed device.
     *
     * @hide
     */
    @TestApi
    public static final int DEVICE_OWNER_TYPE_FINANCED = 1;

    /**
     * Different device owner types for a managed device.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "DEVICE_OWNER_TYPE_" }, value = {
            DEVICE_OWNER_TYPE_DEFAULT,
            DEVICE_OWNER_TYPE_FINANCED
    })
    public @interface DeviceOwnerType {}

    /** @hide */
    @TestApi
    public static final int OPERATION_LOCK_NOW = 1;
    /** @hide */
    @TestApi
    public static final int OPERATION_SWITCH_USER = 2;
    /** @hide */
    @TestApi
    public static final int OPERATION_START_USER_IN_BACKGROUND = 3;
    /** @hide */
    @TestApi
    public static final int OPERATION_STOP_USER = 4;
    /** @hide */
    @TestApi
    public static final int OPERATION_CREATE_AND_MANAGE_USER = 5;
    /** @hide */
    @TestApi
    public static final int OPERATION_REMOVE_USER = 6;
    /** @hide */
    @TestApi
    public static final int OPERATION_REBOOT = 7;
    /** @hide */
    @TestApi
    public static final int OPERATION_WIPE_DATA = 8;
    /** @hide */
    @TestApi
    public static final int OPERATION_LOGOUT_USER = 9;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_USER_RESTRICTION = 10;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_SYSTEM_SETTING = 11;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_KEYGUARD_DISABLED = 12;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_STATUS_BAR_DISABLED = 13;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_SYSTEM_UPDATE_POLICY = 14;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_APPLICATION_HIDDEN = 15;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_APPLICATION_RESTRICTIONS = 16;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_KEEP_UNINSTALLED_PACKAGES = 17;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_LOCK_TASK_FEATURES = 18;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_LOCK_TASK_PACKAGES = 19;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_PACKAGES_SUSPENDED = 20;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_TRUST_AGENT_CONFIGURATION = 21;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_USER_CONTROL_DISABLED_PACKAGES = 22;
    /** @hide */
    @TestApi
    public static final int OPERATION_CLEAR_APPLICATION_USER_DATA = 23;
    /** @hide */
    @TestApi
    public static final int OPERATION_INSTALL_CA_CERT = 24;
    /** @hide */
    @TestApi
    public static final int OPERATION_INSTALL_KEY_PAIR = 25;
    /** @hide */
    @TestApi
    public static final int OPERATION_INSTALL_SYSTEM_UPDATE = 26;
    /** @hide */
    @TestApi
    public static final int OPERATION_REMOVE_ACTIVE_ADMIN = 27;
    /** @hide */
    @TestApi
    public static final int OPERATION_REMOVE_KEY_PAIR = 28;
    /** @hide */
    @TestApi
    public static final int OPERATION_REQUEST_BUGREPORT = 29;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_ALWAYS_ON_VPN_PACKAGE = 30;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_CAMERA_DISABLED = 31;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_FACTORY_RESET_PROTECTION_POLICY = 32;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_GLOBAL_PRIVATE_DNS = 33;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_LOGOUT_ENABLED = 34;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_MASTER_VOLUME_MUTED = 35;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_OVERRIDE_APNS_ENABLED = 36;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_PERMISSION_GRANT_STATE = 37;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_PERMISSION_POLICY = 38;
    /** @hide */
    @TestApi
    public static final int OPERATION_SET_RESTRICTIONS_PROVIDER = 39;
    /** @hide */
    @TestApi
    public static final int OPERATION_UNINSTALL_CA_CERT = 40;
    /** @hide */
    @TestApi
    @FlaggedApi(android.view.contentprotection.flags.Flags.FLAG_MANAGE_DEVICE_POLICY_ENABLED)
    public static final int OPERATION_SET_CONTENT_PROTECTION_POLICY = 41;

    private static final String PREFIX_OPERATION = "OPERATION_";

    /** @hide */
    @IntDef(prefix = PREFIX_OPERATION, value = {
            OPERATION_LOCK_NOW,
            OPERATION_SWITCH_USER,
            OPERATION_START_USER_IN_BACKGROUND,
            OPERATION_STOP_USER,
            OPERATION_CREATE_AND_MANAGE_USER,
            OPERATION_REMOVE_USER,
            OPERATION_REBOOT,
            OPERATION_WIPE_DATA,
            OPERATION_LOGOUT_USER,
            OPERATION_SET_USER_RESTRICTION,
            OPERATION_SET_SYSTEM_SETTING,
            OPERATION_SET_KEYGUARD_DISABLED,
            OPERATION_SET_STATUS_BAR_DISABLED,
            OPERATION_SET_SYSTEM_UPDATE_POLICY,
            OPERATION_SET_APPLICATION_HIDDEN,
            OPERATION_SET_APPLICATION_RESTRICTIONS,
            OPERATION_SET_KEEP_UNINSTALLED_PACKAGES,
            OPERATION_SET_LOCK_TASK_FEATURES,
            OPERATION_SET_LOCK_TASK_PACKAGES,
            OPERATION_SET_PACKAGES_SUSPENDED,
            OPERATION_SET_TRUST_AGENT_CONFIGURATION,
            OPERATION_SET_USER_CONTROL_DISABLED_PACKAGES,
            OPERATION_CLEAR_APPLICATION_USER_DATA,
            OPERATION_INSTALL_CA_CERT,
            OPERATION_INSTALL_KEY_PAIR,
            OPERATION_INSTALL_SYSTEM_UPDATE,
            OPERATION_REMOVE_ACTIVE_ADMIN,
            OPERATION_REMOVE_KEY_PAIR,
            OPERATION_REQUEST_BUGREPORT,
            OPERATION_SET_ALWAYS_ON_VPN_PACKAGE,
            OPERATION_SET_CAMERA_DISABLED,
            OPERATION_SET_FACTORY_RESET_PROTECTION_POLICY,
            OPERATION_SET_GLOBAL_PRIVATE_DNS,
            OPERATION_SET_LOGOUT_ENABLED,
            OPERATION_SET_MASTER_VOLUME_MUTED,
            OPERATION_SET_OVERRIDE_APNS_ENABLED,
            OPERATION_SET_PERMISSION_GRANT_STATE,
            OPERATION_SET_PERMISSION_POLICY,
            OPERATION_SET_RESTRICTIONS_PROVIDER,
            OPERATION_UNINSTALL_CA_CERT,
            OPERATION_SET_CONTENT_PROTECTION_POLICY
    })
    @Retention(RetentionPolicy.SOURCE)
    public static @interface DevicePolicyOperation {
    }

    /** @hide */
    @TestApi
    @NonNull
    public static String operationToString(@DevicePolicyOperation int operation) {
        return DebugUtils.constantToString(DevicePolicyManager.class, PREFIX_OPERATION, operation);
    }

    private static final String PREFIX_OPERATION_SAFETY_REASON = "OPERATION_SAFETY_REASON_";

    /** @hide */
    @IntDef(prefix = PREFIX_OPERATION_SAFETY_REASON, value = {
            OPERATION_SAFETY_REASON_NONE,
            OPERATION_SAFETY_REASON_DRIVING_DISTRACTION
    })
    @Retention(RetentionPolicy.SOURCE)
    public static @interface OperationSafetyReason {
    }

    /** @hide */
    @TestApi
    public static final int OPERATION_SAFETY_REASON_NONE = -1;

    /**
     * Indicates that a {@link UnsafeStateException} was thrown because the operation would distract
     * the driver of the vehicle.
     */
    public static final int OPERATION_SAFETY_REASON_DRIVING_DISTRACTION = 1;

    /**
     * Prevent an app from being suspended.
     *
     * @hide
     */
    @SystemApi
    public static final int EXEMPT_FROM_SUSPENSION =  0;

    /**
     * Prevent an app from dismissible notifications. Starting from Android U, notifications with
     * the ongoing parameter can be dismissed by a user on an unlocked device. An app with
     * this exemption can create non-dismissible notifications.
     *
     * @hide
     */
    @SystemApi
    public static final int EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS =  1;

    /**
     * Allows an application to start an activity while running in the background.
     *
     * @hide
     */
    @SystemApi
    public static final int EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION = 2;

    /**
     * Prevent an app from entering hibernation.
     *
     * @hide
     */
    @SystemApi
    public static final int EXEMPT_FROM_HIBERNATION =  3;

    /**
     * Exempt an app from all power-related restrictions, including app standby and doze.
     * In addition, the app will be able to start foreground services from the background,
     * and the user will not be able to stop foreground services run by the app.
     *
     * @hide
     */
    @SystemApi
    public static final int EXEMPT_FROM_POWER_RESTRICTIONS =  4;

    /**
     * Exemptions to platform restrictions, given to an application through
     * {@link #setApplicationExemptions(String, Set)}.
     *
     * @hide
     */
    @IntDef(prefix = { "EXEMPT_FROM_"}, value = {
            EXEMPT_FROM_SUSPENSION,
            EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS,
            EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION,
            EXEMPT_FROM_HIBERNATION,
            EXEMPT_FROM_POWER_RESTRICTIONS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplicationExemptionConstants {}

    /**
     * Broadcast action: notify system apps (e.g. settings, SysUI, etc) that the device management
     * resources with IDs {@link #EXTRA_RESOURCE_IDS} has been updated, the updated resources can be
     * retrieved using {@link DevicePolicyResourcesManager#getDrawable} and
     * {@link DevicePolicyResourcesManager#getString}.
     *
     * <p>This broadcast is sent to registered receivers only.
     *
     * <p> {@link #EXTRA_RESOURCE_TYPE} will be included to identify the type of resource being
     * updated.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEVICE_POLICY_RESOURCE_UPDATED =
            "android.app.action.DEVICE_POLICY_RESOURCE_UPDATED";

    /**
     * An {@code int} extra for {@link #ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to indicate the type
     * of the resource being updated, the type can be {@link #EXTRA_RESOURCE_TYPE_DRAWABLE} or
     * {@link #EXTRA_RESOURCE_TYPE_STRING}
     */
    public static final String EXTRA_RESOURCE_TYPE =
            "android.app.extra.RESOURCE_TYPE";

    /**
     * A {@code int} value for {@link #EXTRA_RESOURCE_TYPE} to indicate that a resource of type
     * {@link Drawable} is being updated.
     */
    public static final int EXTRA_RESOURCE_TYPE_DRAWABLE = 1;

    /**
     * A {@code int} value for {@link #EXTRA_RESOURCE_TYPE} to indicate that a resource of type
     * {@link String} is being updated.
     */
    public static final int EXTRA_RESOURCE_TYPE_STRING = 2;

    /**
     * An integer array extra for {@link #ACTION_DEVICE_POLICY_RESOURCE_UPDATED} to indicate which
     * resource IDs (i.e. strings and drawables) have been updated.
     */
    public static final String EXTRA_RESOURCE_IDS =
            "android.app.extra.RESOURCE_IDS";

    /**
     * Broadcast Action: Broadcast sent to indicate that the device financing state has changed.
     *
     * <p>This occurs when, for example, a financing kiosk app has been added or removed.
     *
     * <p>To query the current device financing state see {@link #isDeviceFinanced}.
     *
     * <p>This will be delivered to the following apps if they include a receiver for this action
     * in their manifest:
     * <ul>
     *     <li>Device owner admins.
     *     <li>Organization-owned profile owner admins
     *     <li>The supervision app
     *     <li>The device management role holder
     * </ul>
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true, includeBackground = true)
    public static final String ACTION_DEVICE_FINANCING_STATE_CHANGED =
            "android.app.admin.action.DEVICE_FINANCING_STATE_CHANGED";

    /** Allow the user to choose whether to enable MTE on the device. */
    public static final int MTE_NOT_CONTROLLED_BY_POLICY = 0;

    /**
     * Require that MTE be enabled on the device, if supported. Can be set by a device owner or a
     * profile owner of an organization-owned managed profile.
     */
    public static final int MTE_ENABLED = 1;

    /** Require that MTE be disabled on the device. Can be set by a device owner. */
    public static final int MTE_DISABLED = 2;

    /** @hide */
    @IntDef(
            prefix = {"MTE_"},
            value = {MTE_ENABLED, MTE_DISABLED, MTE_NOT_CONTROLLED_BY_POLICY})
    @Retention(RetentionPolicy.SOURCE)
    public static @interface MtePolicy {}

    /**
     * Called by a device owner, profile owner of an organization-owned device, to set the Memory
     * Tagging Extension (MTE) policy. MTE is a CPU extension that allows to protect against certain
     * classes of security problems at a small runtime performance cost overhead.
     *
     * <p>The MTE policy can only be set to {@link #MTE_DISABLED} if called by a device owner.
     * Otherwise a {@link SecurityException} will be thrown.
     *
     * <p>The device needs to be rebooted to apply changes to the MTE policy.
     *
     * @throws SecurityException if caller is not permitted to set Mte policy
     * @throws UnsupportedOperationException if the device does not support MTE
     * @param policy the MTE policy to be set
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_MTE, conditional = true)
    public void setMtePolicy(@MtePolicy int policy) {
        throwIfParentInstance("setMtePolicy");
        if (mService != null) {
            try {
                mService.setMtePolicy(policy, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device owner, profile owner of an organization-owned device to
     * get the Memory Tagging Extension (MTE) policy
     *
     * <a href="https://source.android.com/docs/security/test/memory-safety/arm-mte">
     * Learn more about MTE</a>
     *
     * @throws SecurityException if caller is not permitted to set Mte policy
     * @return the currently set MTE policy
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_MTE, conditional = true)
    public @MtePolicy int getMtePolicy() {
        throwIfParentInstance("setMtePolicy");
        if (mService != null) {
            try {
                return mService.getMtePolicy(mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return MTE_NOT_CONTROLLED_BY_POLICY;
    }

    /**
     * Get the current MTE state of the device.
     *
     * <a href="https://source.android.com/docs/security/test/memory-safety/arm-mte">
     * Learn more about MTE</a>
     *
     * @return whether MTE is currently enabled on the device.
     */
    public static boolean isMtePolicyEnforced() {
        return Zygote.nativeSupportsMemoryTagging();
    }

    /** Indicates that content protection is not controlled by policy, allowing user to choose. */
    @FlaggedApi(android.view.contentprotection.flags.Flags.FLAG_MANAGE_DEVICE_POLICY_ENABLED)
    public static final int CONTENT_PROTECTION_NOT_CONTROLLED_BY_POLICY = 0;

    /** Indicates that content protection is controlled and disabled by a policy (default). */
    @FlaggedApi(android.view.contentprotection.flags.Flags.FLAG_MANAGE_DEVICE_POLICY_ENABLED)
    public static final int CONTENT_PROTECTION_DISABLED = 1;

    /** Indicates that content protection is controlled and enabled by a policy. */
    @FlaggedApi(android.view.contentprotection.flags.Flags.FLAG_MANAGE_DEVICE_POLICY_ENABLED)
    public static final int CONTENT_PROTECTION_ENABLED = 2;

    /** @hide */
    @IntDef(
            prefix = {"CONTENT_PROTECTION_"},
            value = {
                CONTENT_PROTECTION_NOT_CONTROLLED_BY_POLICY,
                CONTENT_PROTECTION_DISABLED,
                CONTENT_PROTECTION_ENABLED,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ContentProtectionPolicy {}

    /**
     * Sets the content protection policy which controls scanning for deceptive apps.
     * <p>
     * This function can only be called by the device owner, a profile owner of an affiliated user
     * or profile, or the profile owner when no device owner is set or holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CONTENT_PROTECTION}. See
     * {@link #isAffiliatedUser}.
     * Any policy set via this method will be cleared if the user becomes unaffiliated.
     * <p>
     * After the content protection policy has been set,
     * {@link PolicyUpdateReceiver#onPolicySetResult(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin on whether the policy was successfully set or not.
     * This callback will contain:
     * <ul>
     * <li> The policy identifier {@link DevicePolicyIdentifiers#CONTENT_PROTECTION_POLICY}
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param policy The content protection policy to set. One of {@link
     *               #CONTENT_PROTECTION_NOT_CONTROLLED_BY_POLICY},
     *               {@link #CONTENT_PROTECTION_DISABLED} or {@link #CONTENT_PROTECTION_ENABLED}.
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CONTENT_PROTECTION}.
     * @see #isAffiliatedUser
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CONTENT_PROTECTION, conditional = true)
    @SupportsCoexistence
    @FlaggedApi(android.view.contentprotection.flags.Flags.FLAG_MANAGE_DEVICE_POLICY_ENABLED)
    public void setContentProtectionPolicy(
            @Nullable ComponentName admin, @ContentProtectionPolicy int policy) {
        throwIfParentInstance("setContentProtectionPolicy");
        if (mService != null) {
            try {
                mService.setContentProtectionPolicy(admin, mContext.getPackageName(), policy);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the current content protection policy.
     * <p>
     * The returned policy will be the current resolved policy rather than the policy set by the
     * calling admin.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CONTENT_PROTECTION}.
     * @see #isAffiliatedUser
     * @see #setContentProtectionPolicy
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CONTENT_PROTECTION, conditional = true)
    @FlaggedApi(android.view.contentprotection.flags.Flags.FLAG_MANAGE_DEVICE_POLICY_ENABLED)
    @UserHandleAware
    public @ContentProtectionPolicy int getContentProtectionPolicy(@Nullable ComponentName admin) {
        throwIfParentInstance("getContentProtectionPolicy");
        if (mService != null) {
            try {
                return mService.getContentProtectionPolicy(admin, mContext.getPackageName(),
                        myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return CONTENT_PROTECTION_DISABLED;
    }

    /**
     * This object is a single place to tack on invalidation and disable calls.  All
     * binder caches in this class derive from this Config, so all can be invalidated or
     * disabled through this Config.
     */
    private static final IpcDataCache.Config sDpmCaches =
            new IpcDataCache.Config(8, IpcDataCache.MODULE_SYSTEM, "DevicePolicyManagerCaches");

    /** @hide */
    public static void invalidateBinderCaches() {
        sDpmCaches.invalidateCache();
    }

    /** @hide */
    public static void disableLocalCaches() {
        sDpmCaches.disableAllForCurrentProcess();
    }

    /** @hide */
    @NonNull
    @TestApi
    public static String operationSafetyReasonToString(@OperationSafetyReason int reason) {
        return DebugUtils.constantToString(DevicePolicyManager.class,
                PREFIX_OPERATION_SAFETY_REASON, reason);
    }

    /** @hide */
    public static boolean isValidOperationSafetyReason(@OperationSafetyReason int reason) {
        return reason == OPERATION_SAFETY_REASON_DRIVING_DISTRACTION;
    }

    /**
     * Checks if it's safe to run operations that can be affected by the given {@code reason}.
     *
     * <p><b>Note:</b> notice that the operation safety state might change between the time this
     * method returns and the operation's method is called, so calls to the latter could still throw
     * a {@link UnsafeStateException} even when this method returns {@code true}.
     *
     * @param reason currently, only supported reason is
     * {@link #OPERATION_SAFETY_REASON_DRIVING_DISTRACTION}.
     *
     * @return whether it's safe to run operations that can be affected by the given {@code reason}.
     */
    // TODO(b/173541467): should it throw SecurityException if caller is not admin?
    public boolean isSafeOperation(@OperationSafetyReason int reason) {
        throwIfParentInstance("isSafeOperation");
        if (mService == null) return false;

        try {
            return mService.isSafeOperation(reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Acknoledges that the new managed user disclaimer was viewed by the (human) user
     * so that {@link #ACTION_SHOW_NEW_USER_DISCLAIMER broadcast} is not sent again the next time
     * this user is switched to.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    @UserHandleAware
    @TestApi
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void acknowledgeNewUserDisclaimer() {
        if (mService != null) {
            try {
                mService.acknowledgeNewUserDisclaimer(mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Checks whether the new managed user disclaimer was viewed by the user.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    @TestApi
    @UserHandleAware
    public boolean isNewUserDisclaimerAcknowledged() {
        if (mService != null) {
            try {
                return mService.isNewUserDisclaimerAcknowledged(mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Return true if the given administrator component is currently active (enabled) in the system.
     *
     * @param admin The administrator component to check for.
     * @return {@code true} if {@code admin} is currently enabled in the system, {@code false}
     *         otherwise
     */
    public boolean isAdminActive(@NonNull ComponentName admin) {
        throwIfParentInstance("isAdminActive");
        return isAdminActiveAsUser(admin, myUserId());
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
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Return true if the given administrator component is currently being removed
     * for the user.
     * @hide
     */
    @TestApi
    public boolean isRemovingAdmin(@NonNull ComponentName admin, int userId) {
        if (mService != null) {
            try {
                return mService.isRemovingAdmin(admin, userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Return a list of all currently active device administrators' component
     * names.  If there are no administrators {@code null} may be
     * returned.
     */
    public @Nullable List<ComponentName> getActiveAdmins() {
        throwIfParentInstance("getActiveAdmins");
        return getActiveAdminsAsUser(myUserId());
    }

    /**
     * @see #getActiveAdmins()
     * @hide
     */
    @UnsupportedAppUsage
    public @Nullable List<ComponentName> getActiveAdminsAsUser(int userId) {
        if (mService != null) {
            try {
                return mService.getActiveAdmins(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Used by package administration code to determine if a package can be stopped
     * or uninstalled.
     * @hide
     */
    @SystemApi
    @RequiresPermission(INTERACT_ACROSS_USERS_FULL)
    public boolean packageHasActiveAdmins(String packageName) {
        return packageHasActiveAdmins(packageName, myUserId());
    }

    /**
     * Used by package administration code to determine if a package can be stopped
     * or uninstalled.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean packageHasActiveAdmins(String packageName, int userId) {
        if (mService != null) {
            try {
                return mService.packageHasActiveAdmins(packageName, userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Remove a current administration component.  This can only be called
     * by the application that owns the administration component; if you
     * try to remove someone else's component, a security exception will be
     * thrown.
     *
     * <p>Note that the operation is not synchronous and the admin might still be active (as
     * indicated by {@link #getActiveAdmins()}) by the time this method returns.
     *
     * @param admin The administration compononent to remove.
     * @throws SecurityException if the caller is not in the owner application of {@code admin}.
     */
    public void removeActiveAdmin(@NonNull ComponentName admin) {
        throwIfParentInstance("removeActiveAdmin");
        if (mService != null) {
            try {
                mService.removeActiveAdmin(admin, myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns true if an administrator has been granted a particular device policy. This can be
     * used to check whether the administrator was activated under an earlier set of policies, but
     * requires additional policies after an upgrade.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Must be an
     *            active administrator, or an exception will be thrown.
     * @param usesPolicy Which uses-policy to check, as defined in {@link DeviceAdminInfo}.
     * @throws SecurityException if {@code admin} is not an active administrator.
     */
    public boolean hasGrantedPolicy(@NonNull ComponentName admin, int usesPolicy) {
        throwIfParentInstance("hasGrantedPolicy");
        if (mService != null) {
            try {
                return mService.hasGrantedPolicy(admin, usesPolicy, myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
     * Constant for {@link #setPasswordQuality}: allows the admin to set precisely how many
     * characters of various types the password should contain to satisfy the policy. The admin
     * should set these requirements via {@link #setPasswordMinimumLetters},
     * {@link #setPasswordMinimumNumeric}, {@link #setPasswordMinimumSymbols},
     * {@link #setPasswordMinimumUpperCase}, {@link #setPasswordMinimumLowerCase},
     * {@link #setPasswordMinimumNonLetter}, and {@link #setPasswordMinimumLength}.
     * Note that quality constants are ordered so that higher values are more restrictive.
     */
    public static final int PASSWORD_QUALITY_COMPLEX = 0x60000;

    /**
     * Constant for {@link #setPasswordQuality}: the user is not allowed to
     * modify password. In case this password quality is set, the password is
     * managed by a profile owner. The profile owner can set any password,
     * as if {@link #PASSWORD_QUALITY_UNSPECIFIED} is used. Note
     * that quality constants are ordered so that higher values are more
     * restrictive. The value of {@link #PASSWORD_QUALITY_MANAGED} is
     * the highest.
     * @hide
     */
    public static final int PASSWORD_QUALITY_MANAGED = 0x80000;

    /**
     * @hide
     *
     * adb shell dpm set-{device,profile}-owner will normally not allow installing an owner to
     * a user with accounts.  {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED}
     * and {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_DISALLOWED} are the account features
     * used by authenticator to exempt their accounts from this:
     *
     * <ul>
     *     <li>Non-test-only DO/PO still can't be installed when there are accounts.
     *     <p>In order to make an apk test-only, add android:testOnly="true" to the
     *     &lt;application&gt; tag in the manifest.
     *
     *     <li>Test-only DO/PO can be installed even when there are accounts, as long as all the
     *     accounts have the {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED} feature.
     *     Some authenticators claim to have any features, so to detect it, we also check
     *     {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_DISALLOWED} and disallow installing
     *     if any of the accounts have it.
     * </ul>
     */
    @SystemApi
    public static final String ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED =
            "android.account.DEVICE_OR_PROFILE_OWNER_ALLOWED";

    /** @hide See {@link #ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED} */
    @SystemApi
    public static final String ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_DISALLOWED =
            "android.account.DEVICE_OR_PROFILE_OWNER_DISALLOWED";

    /**
     * A {@code boolean} metadata to be included in a mainline module's {@code <application>}
     * manifest element, which declares that the module should be considered a required app for
     * managed users.
     * <p>Being declared as a required app prevents removal of this package during the
     * provisioning process.
     * @hide
     */
    @SystemApi
    public static final String REQUIRED_APP_MANAGED_USER = "android.app.REQUIRED_APP_MANAGED_USER";

    /**
     * A {@code boolean} metadata to be included in a mainline module's {@code <application>}
     * manifest element, which declares that the module should be considered a required app for
     * managed devices.
     * <p>Being declared as a required app prevents removal of this package during the
     * provisioning process.
     * @hide
     */
    @SystemApi
    public static final String REQUIRED_APP_MANAGED_DEVICE =
            "android.app.REQUIRED_APP_MANAGED_DEVICE";

    /**
     * A {@code boolean} metadata to be included in a mainline module's {@code <application>}
     * manifest element, which declares that the module should be considered a required app for
     * managed profiles.
     * <p>Being declared as a required app prevents removal of this package during the
     * provisioning process.
     * @hide
     */
    @SystemApi
    public static final String REQUIRED_APP_MANAGED_PROFILE =
            "android.app.REQUIRED_APP_MANAGED_PROFILE";

    /**
     * Called by an application that is administering the device to set the password restrictions it
     * is imposing. After setting this, the user will not be able to enter a new password that is
     * not at least as restrictive as what has been set. Note that the current password will remain
     * until the user has set a new one, so the change does not take place immediately. To prompt
     * the user for a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after calling this method.
     * <p>
     * Quality constants are ordered so that higher values are more restrictive; thus the highest
     * requested quality constant (between the policy set here, the user's preference, and any other
     * considerations) is the one that is in effect.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Apps targeting {@link android.os.Build.VERSION_CODES#R} and below can call this method on the
     * {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile. Apps targeting {@link android.os.Build.VERSION_CODES#S} and above, with the
     * exception of a profile owner on an organization-owned device (as can be identified by
     * {@link #isOrganizationOwnedDeviceWithManagedProfile}), will get a
     * {@code IllegalArgumentException} when calling this method on the parent
     * {@link DevicePolicyManager} instance.
     *
     * <p><strong>Note:</strong> Specifying password requirements using this method clears the
     * password complexity requirements set using {@link #setRequiredPasswordComplexity(int)}.
     * If this method is called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)}, then password complexity requirements
     * set on the primary {@link DevicePolicyManager} must be cleared first by calling
     * {@link #setRequiredPasswordComplexity} with {@link #PASSWORD_COMPLEXITY_NONE) first.
     *
     * <p><string>Note:</strong> this method is ignored on
     * {PackageManager#FEATURE_AUTOMOTIVE automotive builds}.
     *
     * @deprecated Prefer using {@link #setRequiredPasswordComplexity(int)}, to require a password
     * that satisfies a complexity level defined by the platform, rather than specifying custom
     * password requirement.
     * Setting custom, overly-complicated password requirements leads to passwords that are hard
     * for users to remember and may not provide any security benefits given as Android uses
     * hardware-backed throttling to thwart online and offline brute-forcing of the device's
     * screen lock. Company-owned devices (fully-managed and organization-owned managed profile
     * devices) are able to continue using this method, though it is recommended that
     * {@link #setRequiredPasswordComplexity(int)} should be used instead.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param quality The new desired quality. One of {@link #PASSWORD_QUALITY_UNSPECIFIED},
     *            {@link #PASSWORD_QUALITY_BIOMETRIC_WEAK},
     *            {@link #PASSWORD_QUALITY_SOMETHING}, {@link #PASSWORD_QUALITY_NUMERIC},
     *            {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX}, {@link #PASSWORD_QUALITY_ALPHABETIC},
     *            {@link #PASSWORD_QUALITY_ALPHANUMERIC} or {@link #PASSWORD_QUALITY_COMPLEX}.
     * @throws SecurityException if {@code admin} is not an active administrator, if {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} or if the
     *             calling app is targeting {@link android.os.Build.VERSION_CODES#S} and above,
     *             and is calling the method the {@link DevicePolicyManager} instance returned by
     *             {@link #getParentProfileInstance(ComponentName)}.
     * @throws IllegalStateException if the caller is trying to set password quality on the parent
     *             {@link DevicePolicyManager} instance while password complexity was set on the
     *             primary {@link DevicePolicyManager} instance.
     */
    @Deprecated
    public void setPasswordQuality(@NonNull ComponentName admin, int quality) {
        if (mService != null) {
            try {
                mService.setPasswordQuality(admin, quality, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current minimum password quality for a particular admin or all admins that set
     * restrictions on this user and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * <p>Note: on devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * the password is always treated as empty.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    @Deprecated
    public int getPasswordQuality(@Nullable ComponentName admin) {
        return getPasswordQuality(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPasswordQuality(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordQuality(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return PASSWORD_QUALITY_UNSPECIFIED;
    }

    /**
     * Called by an application that is administering the device to set the minimum allowed password
     * length. After setting this, the user will not be able to enter a new password that is not at
     * least as restrictive as what has been set. Note that the current password will remain until
     * the user has set a new one, so the change does not take place immediately. To prompt the user
     * for a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after setting this value. This constraint is
     * only imposed if the administrator has also requested either {@link #PASSWORD_QUALITY_NUMERIC}
     * , {@link #PASSWORD_QUALITY_NUMERIC_COMPLEX}, {@link #PASSWORD_QUALITY_ALPHABETIC},
     * {@link #PASSWORD_QUALITY_ALPHANUMERIC}, or {@link #PASSWORD_QUALITY_COMPLEX} with
     * {@link #setPasswordQuality}. If an app targeting SDK level
     * {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without settings
     * password quality to one of these values first, this method will throw
     * {@link IllegalStateException}.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Apps targeting {@link android.os.Build.VERSION_CODES#R} and below can call this method on the
     * {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * <p><string>Note:</strong> this method is ignored on
     * {PackageManager#FEATURE_AUTOMOTIVE automotive builds}.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum password length. A value of 0 means there is no
     *     restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *     does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
     */
    @Deprecated
    public void setPasswordMinimumLength(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLength(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current minimum password length for a particular admin or all admins that set
     * restrictions on this user and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    @Deprecated
    public int getPasswordMinimumLength(@Nullable ComponentName admin) {
        return getPasswordMinimumLength(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPasswordMinimumLength(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLength(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of upper
     * case letters required in the password. After setting this, the user will not be able to enter
     * a new password that is not at least as restrictive as what has been set. Note that the
     * current password will remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} or {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. If an app targeting
     * SDK level {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without
     * settings password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 0.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Apps targeting {@link android.os.Build.VERSION_CODES#R} and below can call this method on the
     * {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * <p><string>Note:</strong> this method is ignored on
     * {PackageManager#FEATURE_AUTOMOTIVE automotive builds}.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of upper case letters required in the password.
     *            A value of 0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
     */
    @Deprecated
    public void setPasswordMinimumUpperCase(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumUpperCase(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of upper case letters required in the password
     * for a particular admin or all admins that set restrictions on this user and
     * its participating profiles. Restrictions on profiles that have a separate challenge
     * are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumUpperCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of upper case letters required in the
     *         password.
     */
    @Deprecated
    public int getPasswordMinimumUpperCase(@Nullable ComponentName admin) {
        return getPasswordMinimumUpperCase(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPasswordMinimumUpperCase(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumUpperCase(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of lower
     * case letters required in the password. After setting this, the user will not be able to enter
     * a new password that is not at least as restrictive as what has been set. Note that the
     * current password will remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} or {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. If an app targeting
     * SDK level {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without
     * settings password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 0.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Apps targeting {@link android.os.Build.VERSION_CODES#R} and below can call this method on the
     * {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * <p><string>Note:</strong> this method is ignored on
     * {PackageManager#FEATURE_AUTOMOTIVE automotive builds}.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of lower case letters required in the password.
     *            A value of 0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
     */
    @Deprecated
    public void setPasswordMinimumLowerCase(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLowerCase(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of lower case letters required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumLowerCase(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of lower case letters required in the
     *         password.
     */
    @Deprecated
    public int getPasswordMinimumLowerCase(@Nullable ComponentName admin) {
        return getPasswordMinimumLowerCase(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPasswordMinimumLowerCase(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLowerCase(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of
     * letters required in the password. After setting this, the user will not be able to enter a
     * new password that is not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does not take place
     * immediately. To prompt the user for a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after setting this value. This constraint is
     * only imposed if the administrator has also requested {@link #PASSWORD_QUALITY_COMPLEX} with
     * {@link #setPasswordQuality}. If an app targeting SDK level
     * {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without settings
     * password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 1.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Apps targeting {@link android.os.Build.VERSION_CODES#R} and below can call this method on the
     * {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * <p><string>Note:</strong> this method is ignored on
     * {PackageManager#FEATURE_AUTOMOTIVE automotive builds}.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of letters required in the password. A value of
     *            0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
     */
    @Deprecated
    public void setPasswordMinimumLetters(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumLetters(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of letters required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumLetters(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    @Deprecated
    public int getPasswordMinimumLetters(@Nullable ComponentName admin) {
        return getPasswordMinimumLetters(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPasswordMinimumLetters(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumLetters(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of
     * numerical digits required in the password. After setting this, the user will not be able to
     * enter a new password that is not at least as restrictive as what has been set. Note that the
     * current password will remain until the user has set a new one, so the change does not take
     * place immediately. To prompt the user for a new password, use
     * {@link #ACTION_SET_NEW_PASSWORD} or {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. If an app targeting
     * SDK level {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without
     * settings password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 1.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Apps targeting {@link android.os.Build.VERSION_CODES#R} and below can call this method on the
     * {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * <p><string>Note:</strong> this method is ignored on
     * {PackageManager#FEATURE_AUTOMOTIVE automotive builds}.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of numerical digits required in the password. A
     *            value of 0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
     */
    @Deprecated
    public void setPasswordMinimumNumeric(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNumeric(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of numerical digits required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumNumeric(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of numerical digits required in the password.
     */
    @Deprecated
    public int getPasswordMinimumNumeric(@Nullable ComponentName admin) {
        return getPasswordMinimumNumeric(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPasswordMinimumNumeric(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNumeric(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of
     * symbols required in the password. After setting this, the user will not be able to enter a
     * new password that is not at least as restrictive as what has been set. Note that the current
     * password will remain until the user has set a new one, so the change does not take place
     * immediately. To prompt the user for a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after setting this value. This constraint is
     * only imposed if the administrator has also requested {@link #PASSWORD_QUALITY_COMPLEX} with
     * {@link #setPasswordQuality}. If an app targeting SDK level
     * {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without settings
     * password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 1.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Apps targeting {@link android.os.Build.VERSION_CODES#R} and below can call this method on the
     * {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * <p><string>Note:</strong> this method is ignored on
     * {PackageManager#FEATURE_AUTOMOTIVE automotive builds}.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of symbols required in the password. A value of
     *            0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
     */
    @Deprecated
    public void setPasswordMinimumSymbols(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumSymbols(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of symbols required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account. This is the same value as
     * set by {@link #setPasswordMinimumSymbols(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of symbols required in the password.
     */
    @Deprecated
    public int getPasswordMinimumSymbols(@Nullable ComponentName admin) {
        return getPasswordMinimumSymbols(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPasswordMinimumSymbols(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumSymbols(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by an application that is administering the device to set the minimum number of
     * non-letter characters (numerical digits or symbols) required in the password. After setting
     * this, the user will not be able to enter a new password that is not at least as restrictive
     * as what has been set. Note that the current password will remain until the user has set a new
     * one, so the change does not take place immediately. To prompt the user for a new password,
     * use {@link #ACTION_SET_NEW_PASSWORD} or {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after
     * setting this value. This constraint is only imposed if the administrator has also requested
     * {@link #PASSWORD_QUALITY_COMPLEX} with {@link #setPasswordQuality}. If an app targeting
     * SDK level {@link android.os.Build.VERSION_CODES#R} and above enforces this constraint without
     * settings password quality to {@link #PASSWORD_QUALITY_COMPLEX} first, this method will throw
     * {@link IllegalStateException}. The default value is 0.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Apps targeting {@link android.os.Build.VERSION_CODES#R} and below can call this method on the
     * {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * <p><string>Note:</strong> this method is ignored on
     * {PackageManager#FEATURE_AUTOMOTIVE automotive builds}.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired minimum number of letters required in the password. A value of
     *            0 means there is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the calling app is targeting SDK level
     *     {@link android.os.Build.VERSION_CODES#R} and above and didn't set a sufficient password
     *     quality requirement prior to calling this method.
     */
    @Deprecated
    public void setPasswordMinimumNonLetter(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordMinimumNonLetter(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current number of non-letter characters required in the password
     * for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     * This is the same value as set by
     * {@link #setPasswordMinimumNonLetter(ComponentName, int)}
     * and only applies when the password quality is
     * {@link #PASSWORD_QUALITY_COMPLEX}.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @deprecated see {@link #setPasswordQuality(ComponentName, int)} for details.
     *
     * @param admin The name of the admin component to check, or {@code null} to
     *            aggregate all admins.
     * @return The minimum number of letters required in the password.
     */
    @Deprecated
    public int getPasswordMinimumNonLetter(@Nullable ComponentName admin) {
        return getPasswordMinimumNonLetter(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getPasswordMinimumNonLetter(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumNonLetter(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Returns minimum PasswordMetrics that satisfies all admin policies.
     *
     * @hide
     */
    public PasswordMetrics getPasswordMinimumMetrics(@UserIdInt int userHandle) {
        return getPasswordMinimumMetrics(userHandle, false);
    }

    /**
     * Returns minimum PasswordMetrics that satisfies all admin policies.
     * If requested, only consider device-wide admin policies and ignore policies set on the
     * managed profile instance (as if the managed profile had separate work challenge).
     *
     * @hide
     */
    public PasswordMetrics getPasswordMinimumMetrics(@UserIdInt int userHandle,
            boolean deviceWideOnly) {
        if (mService != null) {
            try {
                return mService.getPasswordMinimumMetrics(userHandle, deviceWideOnly);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by an application that is administering the device to set the length of the password
     * history. After setting this, the user will not be able to enter a new password that is the
     * same as any password in the history. Note that the current password will remain until the
     * user has set a new one, so the change does not take place immediately. To prompt the user for
     * a new password, use {@link #ACTION_SET_NEW_PASSWORD} or
     * {@link #ACTION_SET_NEW_PARENT_PROFILE_PASSWORD} after setting this value.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password history length is always 0.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param length The new desired length of password history. A value of 0 means there is no
     *            restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void setPasswordHistoryLength(@NonNull ComponentName admin, int length) {
        if (mService != null) {
            try {
                mService.setPasswordHistoryLength(admin, length, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device admin to set the password expiration timeout. Calling this method will
     * restart the countdown for password expiration for the given admin, as will changing the
     * device password (for all admins).
     * <p>
     * The provided timeout is the time delta in ms and will be added to the current time. For
     * example, to have the password expire 5 days from now, timeout would be 5 * 86400 * 1000 =
     * 432000000 ms for timeout.
     * <p>
     * To disable password expiration, a value of 0 may be used for timeout.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password expiration is always disabled.
     * <p>
     * A calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_EXPIRE_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * Note that setting the password will automatically reset the expiration time for all active
     * admins. Active admins do not need to explicitly call this method in that case.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin
     * @param timeout The limit (in ms) that a password can remain in effect. A value of 0 means
     *            there is no restriction (unlimited).
     * @throws SecurityException if {@code admin} is not an active administrator or {@code admin}
     *             does not use {@link DeviceAdminInfo#USES_POLICY_EXPIRE_PASSWORD}
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS, conditional = true)
    public void setPasswordExpirationTimeout(@Nullable ComponentName admin, long timeout) {
        if (mService != null) {
            try {
                mService.setPasswordExpirationTimeout(
                        admin, mContext.getPackageName(), timeout, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Get the password expiration timeout for the given admin. The expiration timeout is the
     * recurring expiration timeout provided in the call to
     * {@link #setPasswordExpirationTimeout(ComponentName, long)} for the given admin or the
     * aggregate of all participating policy administrators if {@code admin} is null. Admins that
     * have set restrictions on profiles that have a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password expiration is always disabled and this method always returns 0.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate all admins.
     * @return The timeout for the given admin or the minimum of all timeouts
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public long getPasswordExpirationTimeout(@Nullable ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpirationTimeout(admin, myUserId(), mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Get the current password expiration time for a particular admin or all admins that set
     * restrictions on this user and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account. If admin is {@code null}, then a composite
     * of all expiration times is returned - which will be the minimum of all of them.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * the password expiration for the parent profile.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password expiration is always disabled and this method always returns 0.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate all admins.
     * @return The password expiration time, in milliseconds since epoch.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public long getPasswordExpiration(@Nullable ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPasswordExpiration(admin, myUserId(), mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Retrieve the current password history length for a particular admin or all admins that
     * set restrictions on this user and its participating profiles. Restrictions on profiles that
     * have a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password history length is always 0.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     * @return The length of the password history
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public int getPasswordHistoryLength(@Nullable ComponentName admin) {
        return getPasswordHistoryLength(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public int getPasswordHistoryLength(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getPasswordHistoryLength(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Return the maximum password length that the device supports for a
     * particular password quality.
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always empty and this method always returns 0.
     * @param quality The quality being interrogated.
     * @return Returns the maximum length that the user can enter.
     */
    public int getPasswordMaximumLength(int quality) {
        PackageManager pm = mContext.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)) {
            return 0;
        }
        return MAX_PASSWORD_LENGTH;
    }

    /**
     * Determines whether the calling user's current password meets policy requirements
     * (e.g. quality, minimum length). The user must be unlocked to perform this check.
     *
     * <p>Policy requirements which affect this check can be set by admins of the user, but also
     * by the admin of a managed profile associated with the calling user (when the managed profile
     * doesn't have a separate work challenge). When a managed profile has a separate work
     * challenge, its policy requirements only affect the managed profile.
     *
     * <p>Depending on the user, this method checks the policy requirement against one of the
     * following passwords:
     * <ul>
     * <li>For the primary user or secondary users: the personal keyguard password.
     * <li>For managed profiles: a work challenge if set, otherwise the parent user's personal
     *     keyguard password.
     * <ul/>
     * In other words, it's always checking the requirement against the password that is protecting
     * the calling user.
     *
     * <p>Note that this method considers all policy requirements targeting the password in
     * question. For example a profile owner might set a requirement on the parent profile i.e.
     * personal keyguard but not on the profile itself. When the device has a weak personal keyguard
     * password and no separate work challenge, calling this method will return {@code false}
     * despite the profile owner not setting a policy on the profile itself. This is because the
     * profile's current password is the personal keyguard password, and it does not meet all policy
     * requirements.
     *
     * <p>Device admins must request {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD} before
     * calling this method. Note, this policy type is deprecated for device admins in Android 9.0
     * (API level 28) or higher.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to determine if the password set on
     * the parent profile is sufficient.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always treated as empty - i.e. this method will always return false on such
     * devices, provided any password requirements were set.
     *
     * @return {@code true} if the password meets the policy requirements, {@code false} otherwise
     * @throws SecurityException if the calling application isn't an active admin that uses
     *     {@link DeviceAdminInfo#USES_POLICY_LIMIT_PASSWORD}
     * @throws IllegalStateException if the user isn't unlocked
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS, conditional = true)
    public boolean isActivePasswordSufficient() {
        if (mService != null) {
            try {
                return mService.isActivePasswordSufficient(
                        mContext.getPackageName(), myUserId(), mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by profile owner of a managed profile to determine whether the current device password
     * meets policy requirements set explicitly device-wide.
     * <p> This API is similar to {@link #isActivePasswordSufficient()}, with two notable
     * differences:
     * <ul>
     * <li>this API always targets the device password. As a result it should always be called on
     *   the {@link #getParentProfileInstance(ComponentName)} instance.</li>
     * <li>password policy requirement set on the managed profile is not taken into consideration
     *   by this API, even if the device currently does not have a separate work challenge set.</li>
     * </ul>
     *
     * <p>This API is designed to facilite progressive password enrollment flows when the DPC
     * imposes both device and profile password policies. DPC applies profile password policy by
     * calling {@link #setPasswordQuality(ComponentName, int)} or
     * {@link #setRequiredPasswordComplexity} on the regular {@link DevicePolicyManager} instance,
     * while it applies device-wide policy by calling {@link #setRequiredPasswordComplexity} on the
     * {@link #getParentProfileInstance(ComponentName)} instance. The DPC can utilize this check to
     * guide the user to set a device password first taking into consideration the device-wide
     * policy only, and then prompt the user to either upgrade it to be fully compliant, or enroll a
     * separate work challenge to satisfy the profile password policy only.
     *
     * <p>The device user must be unlocked (@link {@link UserManager#isUserUnlocked(UserHandle)})
     * to perform this check.
     *
     * @return {@code true} if the device password meets explicit requirement set on it,
     *   {@code false} otherwise.
     * @throws SecurityException if the calling application is not a profile owner of a managed
     *   profile, or if this API is not called on the parent DevicePolicyManager instance.
     * @throws IllegalStateException if the user isn't unlocked
     * @see #EXTRA_DEVICE_PASSWORD_REQUIREMENT_ONLY
     */
    public boolean isActivePasswordSufficientForDeviceRequirement() {
        if (!mParentInstance) {
            throw new SecurityException("only callable on the parent instance");
        }
        if (mService != null) {
            try {
                return mService.isActivePasswordSufficientForDeviceRequirement();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns how complex the current user's screen lock is.
     *
     * <p>Note that when called from a profile which uses an unified challenge with its parent, the
     * screen lock complexity of the parent will be returned.
     *
     * <p>Apps need the {@link permission#REQUEST_PASSWORD_COMPLEXITY} permission to call this
     * method. On Android {@link android.os.Build.VERSION_CODES#S} and above, the calling
     * application does not need this permission if it is a device owner or a profile owner.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @throws IllegalStateException if the user is not unlocked.
     * @throws SecurityException     if the calling application does not have the permission
     *                               {@link permission#REQUEST_PASSWORD_COMPLEXITY}, and is not a
     *                               device owner or a profile owner.
     */
    @PasswordComplexity
    @RequiresPermission(anyOf={MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS, REQUEST_PASSWORD_COMPLEXITY}, conditional = true)
    public int getPasswordComplexity() {
        if (mService == null) {
            return PASSWORD_COMPLEXITY_NONE;
        }

        try {
            return mService.getPasswordComplexity(mParentInstance);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets a minimum password complexity requirement for the user's screen lock.
     * The complexity level is one of the pre-defined levels, and the user is unable to set a
     * password with a lower complexity level.
     *
     * <p>Note that when called on a profile which uses an unified challenge with its parent, the
     * complexity would apply to the unified challenge.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to set
     * restrictions on the parent profile.
     *
     * <p><strong>Note:</strong> Specifying password requirements using this method clears any
     * password requirements set using the obsolete {@link #setPasswordQuality(ComponentName, int)}
     * and any of its associated methods.
     * Additionally, if there are password requirements set using the obsolete
     * {@link #setPasswordQuality(ComponentName, int)} on the parent {@code DevicePolicyManager}
     * instance, they must be cleared by calling {@link #setPasswordQuality(ComponentName, int)}
     * with {@link #PASSWORD_QUALITY_UNSPECIFIED} on that instance prior to setting complexity
     * requirement for the managed profile.
     *
     * Starting from {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, after the password
     * requirement has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier {@link DevicePolicyIdentifiers#PASSWORD_COMPLEXITY_POLICY}
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @throws SecurityException if the calling application is not a device owner or a profile
     * owner.
     * @throws IllegalArgumentException if the complexity level is not one of the four above.
     * @throws IllegalStateException if the caller is trying to set password complexity while there
     * are password requirements specified using {@link #setPasswordQuality(ComponentName, int)}
     * on the parent {@code DevicePolicyManager} instance.
     */
    @SupportsCoexistence
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS, conditional = true)
    public void setRequiredPasswordComplexity(@PasswordComplexity int passwordComplexity) {
        if (mService == null) {
            return;
        }

        try {
            mService.setRequiredPasswordComplexity(
                    mContext.getPackageName(), passwordComplexity, mParentInstance);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Gets the password complexity requirement set by {@link #setRequiredPasswordComplexity(int)},
     * for the current user.
     *
     * <p>The difference between this method and {@link #getPasswordComplexity()} is that this
     * method simply returns the value set by {@link #setRequiredPasswordComplexity(int)} while
     * {@link #getPasswordComplexity()} returns the complexity of the actual password.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to get
     * restrictions on the parent profile.
     *
     * @throws SecurityException if the calling application is not a device owner or a profile
     * owner.
     */
    @PasswordComplexity
    @SupportsCoexistence
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS, conditional = true)
    public int getRequiredPasswordComplexity() {
        if (mService == null) {
            return PASSWORD_COMPLEXITY_NONE;
        }

        try {
            return mService.getRequiredPasswordComplexity(
                    mContext.getPackageName(), mParentInstance);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the password complexity that applies to this user, aggregated from other users if
     * necessary (for example, if the DPC has set password complexity requirements on the parent
     * profile DPM instance of a managed profile user, they would apply to the primary user on the
     * device).
     * @hide
     */
    @PasswordComplexity
    public int getAggregatedPasswordComplexityForUser(int userId) {
        return getAggregatedPasswordComplexityForUser(userId, false);
    }

    /**
     * Returns the password complexity that applies to this user, aggregated from other users if
     * necessary (for example, if the DPC has set password complexity requirements on the parent
     * profile DPM instance of a managed profile user, they would apply to the primary user on the
     * device). If {@code deviceWideOnly} is {@code true}, ignore policies set on the
     * managed profile DPM instance (as if the managed profile had separate work challenge).
     * @hide
     */
    @PasswordComplexity
    public int getAggregatedPasswordComplexityForUser(int userId, boolean deviceWideOnly) {
        if (mService == null) {
            return PASSWORD_COMPLEXITY_NONE;
        }

        try {
            return mService.getAggregatedPasswordComplexityForUser(userId, deviceWideOnly);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * When called by a profile owner of a managed profile returns true if the profile uses unified
     * challenge with its parent user.
     *
     * <strong>Note</strong>: This method is not concerned with password quality and will return
     * false if the profile has empty password as a separate challenge.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a profile owner of a managed profile.
     * @see UserManager#DISALLOW_UNIFIED_PASSWORD
     */
    public boolean isUsingUnifiedPassword(@NonNull ComponentName admin) {
        throwIfParentInstance("isUsingUnifiedPassword");
        if (mService != null) {
            try {
                return mService.isUsingUnifiedPassword(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return true;
    }

    /**
     * Returns whether the given user's credential will be sufficient for all password policy
     * requirement, once the user's profile has switched to unified challenge.
     *
     * <p>This is different from {@link #isActivePasswordSufficient()} since once the profile
     * switches to unified challenge, policies set explicitly on the profile will start to affect
     * the parent user.
     * @param userHandle the user whose password requirement will be checked
     * @param profileUser the profile user whose lockscreen challenge will be unified.
     * @hide
     */
    public boolean isPasswordSufficientAfterProfileUnification(int userHandle, int profileUser) {
        if (mService != null) {
            try {
                return mService.isPasswordSufficientAfterProfileUnification(userHandle,
                        profileUser);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }
    /**
     * Retrieve the number of times the user has failed at entering a password since that last
     * successful password entry.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to retrieve the number of failed
     * password attemts for the parent user.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN}
     * to be able to call this method; if it has not, a security exception will be thrown.
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always empty and this method always returns 0.
     *
     * @return The number of times user has entered an incorrect password since the last correct
     *         password entry.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN}
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS, conditional = true)
    public int getCurrentFailedPasswordAttempts() {
        return getCurrentFailedPasswordAttempts(myUserId());
    }

    /**
     * Retrieve the number of times the given user has failed at entering a
     * password since that last successful password entry.
     *
     * <p>The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} to be able to call this method; if it has
     * not and it is not the system uid, a security exception will be thrown.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getCurrentFailedPasswordAttempts(int userHandle) {
        if (mService != null) {
            try {
                return mService.getCurrentFailedPasswordAttempts(
                        mContext.getPackageName(), userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Setting this to a value greater than zero enables a policy that will perform a
     * device or profile wipe after too many incorrect device-unlock passwords have been entered.
     * This policy combines watching for failed passwords and wiping the device, and
     * requires that calling Device Admins request both
     * {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} and
     * {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}}.
     * <p>
     * When this policy is set on the system or the main user, the device will be factory reset
     * after too many incorrect password attempts. When set on any other user, only the
     * corresponding user or profile will be wiped.
     * <p>
     * To implement any other policy (e.g. wiping data for a particular application only, erasing or
     * revoking credentials, or reporting the failure to a server), you should implement
     * {@link DeviceAdminReceiver#onPasswordFailed(Context, android.content.Intent)} instead. Do not
     * use this API, because if the maximum count is reached, the device or profile will be wiped
     * immediately, and your callback will not be invoked.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set a value on the parent
     * profile. This allows a profile wipe after too many incorrect device-unlock password have
     * been entered on the parent profile even if each profile has a separate challenge.
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always empty and this method has no effect - i.e. the policy is not set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param num The number of failed password attempts at which point the device or profile will
     *            be wiped.
     * @throws SecurityException if {@code admin} is not null, and {@code admin} is not an active
     *            administrator or does not use both
     *            {@link DeviceAdminInfo#USES_POLICY_WATCH_LOGIN} and
     *            {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA}, or if {@code admin} is null and the
     *            caller does not have permission to wipe the device.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIPE_DATA, conditional = true)
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void setMaximumFailedPasswordsForWipe(@Nullable ComponentName admin, int num) {
        if (mService != null) {
            try {
                mService.setMaximumFailedPasswordsForWipe(
                        admin, mContext.getPackageName(), num, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current maximum number of login attempts that are allowed before the device
     * or profile is wiped, for a particular admin or all admins that set restrictions on this user
     * and its participating profiles. Restrictions on profiles that have a separate challenge are
     * not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * the value for the parent profile.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * password is always empty and this method returns a default value (0) indicating that the
     * policy is not set.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public int getMaximumFailedPasswordsForWipe(@Nullable ComponentName admin) {
        return getMaximumFailedPasswordsForWipe(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public int getMaximumFailedPasswordsForWipe(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumFailedPasswordsForWipe(
                        admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Returns the user that will be wiped first when too many failed attempts are made to unlock
     * user {@code userHandle}. That user is either the same as {@code userHandle} or belongs to the
     * same profile group. When there is no such policy, returns {@code UserHandle.USER_NULL}.
     * E.g. managed profile user may be wiped as a result of failed primary profile password
     * attempts when using unified challenge. Primary user may be wiped as a result of failed
     * password attempts on the managed profile on an organization-owned device.
     * @hide Used only by Keyguard
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public int getProfileWithMinimumFailedPasswordsForWipe(int userHandle) {
        if (mService != null) {
            try {
                return mService.getProfileWithMinimumFailedPasswordsForWipe(
                        userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * Flag for {@link #resetPasswordWithToken} and {@link #resetPassword}: don't allow other admins
     * to change the password again until the user has entered it.
     */
    public static final int RESET_PASSWORD_REQUIRE_ENTRY = 0x0001;

    /**
     * Flag for {@link #resetPasswordWithToken} and {@link #resetPassword}: don't ask for user
     * credentials on device boot.
     * If the flag is set, the device can be booted without asking for user password.
     * The absence of this flag does not change the current boot requirements. This flag
     * can be set by the device owner only. If the app is not the device owner, the flag
     * is ignored. Once the flag is set, it cannot be reverted back without resetting the
     * device to factory defaults.
     */
    public static final int RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT = 0x0002;

    /**
     * Force a new password for device unlock (the password needed to access the entire device) or
     * the work profile challenge on the current user. This takes effect immediately.
     *
     * <p> Before {@link android.os.Build.VERSION_CODES#N}, this API is available to device admin,
     * profile owner and device owner. Starting from {@link android.os.Build.VERSION_CODES#N},
     * legacy device admin (who is not also profile owner or device owner) can only call this
     * API to set a new password if there is currently no password set. Profile owner and device
     * owner can continue to force change an existing password as long as the target user is
     * unlocked, although device owner will not be able to call this API at all if there is also a
     * managed profile on the device.
     *
     * <p> Between {@link android.os.Build.VERSION_CODES#O},
     * {@link android.os.Build.VERSION_CODES#P} and {@link android.os.Build.VERSION_CODES#Q},
     * profile owner and devices owner targeting SDK level {@link android.os.Build.VERSION_CODES#O}
     * or above who attempt to call this API will receive {@link SecurityException}; they are
     * encouraged to migrate to the new {@link #resetPasswordWithToken} API instead.
     * Profile owner and device owner targeting older SDK levels are not affected: they continue
     * to experience the existing behaviour described in the previous paragraph.
     *
     * <p><em>Starting from {@link android.os.Build.VERSION_CODES#R}, this API is no longer
     * supported in most cases.</em> Device owner and profile owner calling
     * this API will receive {@link SecurityException} if they target SDK level
     * {@link android.os.Build.VERSION_CODES#O} or above, or they will receive a silent failure
     * (API returning {@code false}) if they target lower SDK level.
     * For legacy device admins, this API throws {@link SecurityException} if they target SDK level
     * {@link android.os.Build.VERSION_CODES#N} or above, and returns {@code false} otherwise. Only
     * privileged apps holding RESET_PASSWORD permission which are part of
     * the system factory image can still call this API to set a new password if there is currently
     * no password set. In this case, if the device already has a password, this API will throw
     * {@link SecurityException}.
     *
     * <p>
     * The given password must be sufficient for the current password quality and length constraints
     * as returned by {@link #getPasswordQuality(ComponentName)} and
     * {@link #getPasswordMinimumLength(ComponentName)}; if it does not meet these constraints, then
     * it will be rejected and false returned. Note that the password may be a stronger quality
     * (containing alphanumeric characters when the requested quality is only numeric), in which
     * case the currently active quality will be increased to match.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, this
     * methods does nothing.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_RESET_PASSWORD} to be able to call this method; if it has
     * not, a security exception will be thrown.
     *
     * @param password The new password for the user. Null or empty clears the password.
     * @param flags May be 0 or combination of {@link #RESET_PASSWORD_REQUIRE_ENTRY} and
     *            {@link #RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT}.
     * @return Returns true if the password was applied, or false if it is not acceptable for the
     *         current constraints.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_RESET_PASSWORD}
     * @throws IllegalStateException if the calling user is locked or has a managed profile.
     * @deprecated Please use {@link #resetPasswordWithToken} instead.
     */
    @Deprecated
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public boolean resetPassword(String password, int flags) {
        throwIfParentInstance("resetPassword");
        if (mService != null) {
            try {
                return mService.resetPassword(password, flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a profile or device owner to provision a token which can later be used to reset the
     * device lockscreen password (if called by device owner), or managed profile challenge (if
     * called by profile owner), via {@link #resetPasswordWithToken}.
     * <p>
     * If the user currently has a lockscreen password, the provisioned token will not be
     * immediately usable; it only becomes active after the user performs a confirm credential
     * operation, which can be triggered by {@link KeyguardManager#createConfirmDeviceCredentialIntent}.
     * If the user has no lockscreen password, the token is activated immediately. In all cases,
     * the active state of the current token can be checked by {@link #isResetPasswordTokenActive}.
     * For security reasons, un-activated tokens are only stored in memory and will be lost once
     * the device reboots. In this case a new token needs to be provisioned again.
     * <p>
     * Once provisioned and activated, the token will remain effective even if the user changes
     * or clears the lockscreen password.
     * <p>
     * <em>This token is highly sensitive and should be treated at the same level as user
     * credentials. In particular, NEVER store this token on device in plaintext. Do not store
     * the plaintext token in device-encrypted storage if it will be needed to reset password on
     * file-based encryption devices before user unlocks. Consider carefully how any password token
     * will be stored on your server and who will need access to them. Tokens may be the subject of
     * legal access requests.
     * </em>
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, the
     * reset token is not set and this method returns false.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param token a secure token a least 32-byte long, which must be generated by a
     *        cryptographically strong random number generator.
     * @return true if the operation is successful, false otherwise.
     * @throws SecurityException if admin is not a device or profile owner.
     * @throws IllegalArgumentException if the supplied token is invalid.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_RESET_PASSWORD, conditional = true)
    public boolean setResetPasswordToken(@Nullable ComponentName admin, byte[] token) {
        throwIfParentInstance("setResetPasswordToken");
        if (mService != null) {
            try {
                return mService.setResetPasswordToken(admin, mContext.getPackageName(), token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a profile, device owner or holder of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_RESET_PASSWORD}
     * to revoke the current password reset token.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, this
     * method has no effect - the reset token should not have been set in the first place - and
     * false is returned.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @return true if the operation is successful, false otherwise.
     * @throws SecurityException if admin is not a device or profile owner and if the caller does
     * not the permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_RESET_PASSWORD}.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_RESET_PASSWORD, conditional = true)
    public boolean clearResetPasswordToken(@Nullable ComponentName admin) {
        throwIfParentInstance("clearResetPasswordToken");
        if (mService != null) {
            try {
                return mService.clearResetPasswordToken(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a profile, device owner or a holder of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_RESET_PASSWORD}
     * to check if the current reset password token is active.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * false is always returned.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @return true if the token is active, false otherwise.
     * @throws SecurityException if admin is not a device or profile owner and not a holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_RESET_PASSWORD}
     * @throws IllegalStateException if no token has been set.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_RESET_PASSWORD, conditional = true)
    public boolean isResetPasswordTokenActive(@Nullable ComponentName admin) {
        throwIfParentInstance("isResetPasswordTokenActive");
        if (mService != null) {
            try {
                return mService.isResetPasswordTokenActive(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device or profile owner to force set a new device unlock password or a managed
     * profile challenge on current user. This takes effect immediately.
     * <p>
     * Unlike {@link #resetPassword}, this API can change the password even before the user or
     * device is unlocked or decrypted. The supplied token must have been previously provisioned via
     * {@link #setResetPasswordToken}, and in active state {@link #isResetPasswordTokenActive}.
     * <p>
     * The given password must be sufficient for the current password quality and length constraints
     * as returned by {@link #getPasswordQuality(ComponentName)} and
     * {@link #getPasswordMinimumLength(ComponentName)}; if it does not meet these constraints, then
     * it will be rejected and false returned. Note that the password may be a stronger quality, for
     * example, a password containing alphanumeric characters when the requested quality is only
     * numeric.
     * <p>
     * Calling with a {@code null} or empty password will clear any existing PIN, pattern or
     * password if the current password constraints allow it.
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * calling this methods has no effect - the password is always empty - and false is returned.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param password The new password for the user. {@code null} or empty clears the password.
     * @param token the password reset token previously provisioned by
     *        {@link #setResetPasswordToken}.
     * @param flags May be 0 or combination of {@link #RESET_PASSWORD_REQUIRE_ENTRY} and
     *        {@link #RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT}.
     * @return Returns true if the password was applied, or false if it is not acceptable for the
     *         current constraints.
     * @throws SecurityException if admin is not a device or profile owner.
     * @throws IllegalStateException if the provided token is not valid.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_RESET_PASSWORD, conditional = true)
    public boolean resetPasswordWithToken(@Nullable ComponentName admin, String password,
            byte[] token, int flags) {
        throwIfParentInstance("resetPassword");
        if (mService != null) {
            try {
                return mService.resetPasswordWithToken(admin, mContext.getPackageName(), password,
                        token, flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to set the maximum time for user
     * activity until the device will lock. This limits the length that the user can set. It takes
     * effect immediately.
     * <p>
     * A calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     * to be able to call this method; if it has not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin
     * @param timeMs The new desired maximum time to lock in milliseconds. A value of 0 means there
     *            is no restriction.
     * @throws SecurityException if {@code admin} is not an active administrator or it does not use
     *             {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK, conditional = true)
    public void setMaximumTimeToLock(@Nullable ComponentName admin, long timeMs) {
        if (mService != null) {
            try {
                mService.setMaximumTimeToLock(admin, mContext.getPackageName(), timeMs, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve the current maximum time to unlock for a particular admin or all admins that set
     * restrictions on this user and its participating profiles. Restrictions on profiles that have
     * a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     * all admins.
     * @return time in milliseconds for the given admin or the minimum value (strictest) of
     * all admins if admin is null. Returns 0 if there are no restrictions.
     */
    public long getMaximumTimeToLock(@Nullable ComponentName admin) {
        return getMaximumTimeToLock(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getMaximumTimeToLock(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getMaximumTimeToLock(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by a device/profile owner to set the timeout after which unlocking with secondary, non
     * strong auth (e.g. fingerprint, face, trust agents) times out, i.e. the user has to use a
     * strong authentication method like password, pin or pattern.
     *
     * <p>This timeout is used internally to reset the timer to require strong auth again after
     * specified timeout each time it has been successfully used.
     *
     * <p>Fingerprint can also be disabled altogether using {@link #KEYGUARD_DISABLE_FINGERPRINT}.
     *
     * <p>Trust agents can also be disabled altogether using {@link #KEYGUARD_DISABLE_TRUST_AGENTS}.
     *
     * <p>A calling device admin can verify the value it has set by calling
     * {@link #getRequiredStrongAuthTimeout(ComponentName)} and passing in its instance.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * calling this methods has no effect - i.e. the timeout is not set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin
     * @param timeoutMs The new timeout in milliseconds, after which the user will have to unlock
     *         with strong authentication method. A value of 0 means the admin is not participating
     *         in controlling the timeout.
     *         The minimum and maximum timeouts are platform-defined and are typically 1 hour and
     *         72 hours, respectively. Though discouraged, the admin may choose to require strong
     *         auth at all times using {@link #KEYGUARD_DISABLE_FINGERPRINT} and/or
     *         {@link #KEYGUARD_DISABLE_TRUST_AGENTS}.
     *
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_CREDENTIALS, conditional = true)
    public void setRequiredStrongAuthTimeout(@Nullable ComponentName admin,
            long timeoutMs) {
        if (mService != null) {
            try {
                mService.setRequiredStrongAuthTimeout(
                        admin, mContext.getPackageName(), timeoutMs, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine for how long the user will be able to use secondary, non strong auth for
     * authentication, since last strong method authentication (password, pin or pattern) was used.
     * After the returned timeout the user is required to use strong authentication method.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * <p>On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature,
     * 0 is returned to indicate that no timeout is configured.
     *
     * @param admin The name of the admin component to check, or {@code null} to aggregate
     *         across all participating admins.
     * @return The timeout in milliseconds or 0 if not configured for the provided admin.
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public long getRequiredStrongAuthTimeout(@Nullable ComponentName admin) {
        return getRequiredStrongAuthTimeout(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public long getRequiredStrongAuthTimeout(@Nullable ComponentName admin, @UserIdInt int userId) {
        if (mService != null) {
            try {
                return mService.getRequiredStrongAuthTimeout(admin, userId, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return DEFAULT_STRONG_AUTH_TIMEOUT_MS;
    }

    /**
     * Flag for {@link #lockNow(int)}: also evict the user's credential encryption key from the
     * keyring. The user's credential will need to be entered again in order to derive the
     * credential encryption key that will be stored back in the keyring for future use.
     * <p>
     * This flag can only be used by a profile owner when locking a managed profile when
     * {@link #getStorageEncryptionStatus} returns {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER}.
     * <p>
     * In order to secure user data, the user will be stopped and restarted so apps should wait
     * until they are next run to perform further actions.
     */
    public static final int FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "FLAG_EVICT_" }, value = {
            FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY
    })
    public @interface LockNowFlag {}

    /**
     * Make the device lock immediately, as if the lock screen timeout has expired at the point of
     * this call.
     * <p>
     * This method secures the device in response to an urgent situation, such as a lost or stolen
     * device. After this method is called, the device must be unlocked using strong authentication
     * (PIN, pattern, or password). This API is intended for use only by device admins.
     * <p>
     * From version {@link android.os.Build.VERSION_CODES#R} onwards, the caller must either have
     * the LOCK_DEVICE permission or the device must have the
     * device admin feature; if neither is true, then the method will return without completing
     * any action. Before version {@link android.os.Build.VERSION_CODES#R},
     * the device needed the device admin feature, regardless of the caller's permissions.
     * <p>
     * The calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     * to be able to call this method; if it has not, a security exception will be thrown.
     * <p>
     * If there's no lock type set, this method forces the device to go to sleep but doesn't lock
     * the device. Device admins who find the device in this state can lock an otherwise-insecure
     * device by first calling {@link #resetPassword} to set the password and then lock the device.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to lock the parent profile.
     * <p>
     * NOTE: on {@link android.content.pm.PackageManager#FEATURE_AUTOMOTIVE automotive builds}, this
     * method doesn't turn off the screen as it would be a driving safety distraction.
     * <p>
     * Equivalent to calling {@link #lockNow(int)} with no flags.
     *
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     */
    @SuppressLint("RequiresPermission")
    @RequiresPermission(value = LOCK_DEVICE, conditional = true)
    public void lockNow() {
        lockNow(0);
    }

    /**
     * Make the device lock immediately, as if the lock screen timeout has expired at the point of
     * this call.
     * <p>
     * This method secures the device in response to an urgent situation, such as a lost or stolen
     * device. After this method is called, the device must be unlocked using strong authentication
     * (PIN, pattern, or password). This API is intended for use only by device admins.
     * <p>
     * From version {@link android.os.Build.VERSION_CODES#R} onwards, the caller must either have
     * the LOCK_DEVICE permission or the device must have the
     * device admin feature; if neither is true, then the method will return without completing any
     * action. Before version {@link android.os.Build.VERSION_CODES#R}, the device needed the device
     * admin feature, regardless of the caller's permissions.
     * <p>
     * A calling device admin must have requested {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK}
     * to be able to call this method; if it has not, a security exception will be thrown.
     * <p>
     * If there's no lock type set, this method forces the device to go to sleep but doesn't lock
     * the device. Device admins who find the device in this state can lock an otherwise-insecure
     * device by first calling {@link #resetPassword} to set the password and then lock the device.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to lock the parent profile as
     * well as the managed profile.
     * <p>
     * NOTE: In order to lock the parent profile and evict the encryption key of the managed
     * profile, {@link #lockNow()} must be called twice: First, {@link #lockNow()} should be called
     * on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)}, then {@link #lockNow(int)} should be
     * called on the {@link DevicePolicyManager} instance associated with the managed profile,
     * with the {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY} flag.
     * Calling the method twice in this order ensures that all users are locked and does not
     * stop the device admin on the managed profile from issuing a second call to lock its own
     * profile.
     * <p>
     * NOTE: on {@link android.content.pm.PackageManager#FEATURE_AUTOMOTIVE automotive builds}, this
     * method doesn't turn off the screen as it would be a driving safety distraction.
     *
     * @param flags May be 0 or {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY}.
     * @throws SecurityException if the calling application does not own an active administrator
     *             that uses {@link DeviceAdminInfo#USES_POLICY_FORCE_LOCK} and the does not hold
     *             the LOCK_DEVICE permission, or
     *             the {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY} flag is passed by an
     *             application that is not a profile owner of a managed profile.
     * @throws IllegalArgumentException if the {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY} flag is
     *             passed when locking the parent profile.
     * @throws UnsupportedOperationException if the {@link #FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY}
     *             flag is passed when {@link #getStorageEncryptionStatus} does not return
     *             {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER}.
     */
    @RequiresPermission(value = LOCK_DEVICE, conditional = true)
    public void lockNow(@LockNowFlag int flags) {
        if (mService != null) {
            try {
                mService.lockNow(flags, mContext.getPackageName(), mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Flag for {@link #wipeData(int)}: also erase the device's adopted external storage (such as
     * adopted SD cards).
     * @see <a href="{@docRoot}about/versions/marshmallow/android-6.0.html#adoptable-storage">
     *     Adoptable Storage Devices</a>
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
     * Flag for {@link #wipeData(int)}: also erase the device's eUICC data.
     */
    public static final int WIPE_EUICC = 0x0004;

    /**
     * Flag for {@link #wipeData(int)}: won't show reason for wiping to the user.
     */
    public static final int WIPE_SILENTLY = 0x0008;

    /**
     * See {@link #wipeData(int, CharSequence)}
     *
     * @param flags Bit mask of additional options: currently supported flags are
     *              {@link #WIPE_EXTERNAL_STORAGE}, {@link #WIPE_RESET_PROTECTION_DATA},
     *              {@link #WIPE_EUICC} and {@link #WIPE_SILENTLY}.
     * @throws SecurityException if the calling application does not own an active
     *                           administrator
     *                           that uses {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} and is
     *                           not granted the
     *                           {@link android.Manifest.permission#MASTER_CLEAR} or
     *                           {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WIPE_DATA}
     *                            permissions.
     * @throws IllegalStateException if called on last full-user or system-user
     * @see #wipeDevice(int)
     * @see #wipeData(int, CharSequence)
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIPE_DATA, conditional = true)
    public void wipeData(int flags) {
        wipeDataInternal(flags,
                /* wipeReasonForUser= */ "",
                /* factoryReset= */ false);
    }

    /**
     * Ask that all user data be wiped.
     *
     * <p>
     * If called as a secondary user or managed profile, the user itself and its associated user
     * data will be wiped. In particular, If the caller is a profile owner of an
     * organization-owned managed profile, calling this method will relinquish the device for
     * personal use, removing the managed profile and all policies set by the profile owner.
     * </p>
     *
     * <p> Calling this method from the primary user will only work if the calling app is
     * targeting SDK level {@link Build.VERSION_CODES#TIRAMISU} or below, in which case it will
     * cause the device to reboot, erasing all device data - including all the secondary users
     * and their data - while booting up. If an app targeting SDK level
     * {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE} and above is calling this method from the
     * primary user or last full user, {@link IllegalStateException} will be thrown. </p>
     *
     * If an app wants to wipe the entire device irrespective of which user they are from, they
     * should use {@link #wipeDevice} instead.
     *
     * @param flags Bit mask of additional options: currently supported flags are
     *            {@link #WIPE_EXTERNAL_STORAGE}, {@link #WIPE_RESET_PROTECTION_DATA} and
     *            {@link #WIPE_EUICC}.
     * @param reason a string that contains the reason for wiping data, which can be
     *            presented to the user.
     * @throws SecurityException if the calling application does not own an active administrator
     *            that uses {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} and is not granted the
     *            {@link android.Manifest.permission#MASTER_CLEAR} or
     *            {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WIPE_DATA} permissions.
     * @throws IllegalArgumentException if the input reason string is null or empty, or if
     *            {@link #WIPE_SILENTLY} is set.
     * @throws IllegalStateException if called on last full-user or system-user
     * @see #wipeDevice(int)
     * @see #wipeData(int)
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIPE_DATA, conditional = true)
    public void wipeData(int flags, @NonNull CharSequence reason) {
        Objects.requireNonNull(reason, "reason string is null");
        Preconditions.checkStringNotEmpty(reason, "reason string is empty");
        Preconditions.checkArgument((flags & WIPE_SILENTLY) == 0, "WIPE_SILENTLY cannot be set");
        wipeDataInternal(flags, reason.toString(), /* factoryReset= */ false);
    }

    /**
     * Ask that the device be wiped and factory reset.
     *
     * <p>
     * The calling Device Owner or Organization Owned Profile Owner must have requested
     * {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} to be able to call this method; if it has
     * not, a security exception will be thrown.
     *
     * @param flags Bit mask of additional options: currently supported flags are
     *              {@link #WIPE_EXTERNAL_STORAGE}, {@link #WIPE_RESET_PROTECTION_DATA},
     *              {@link #WIPE_EUICC} and {@link #WIPE_SILENTLY}.
     * @throws SecurityException if the calling application does not own an active administrator
     *                       that uses {@link DeviceAdminInfo#USES_POLICY_WIPE_DATA} and is not
     *                       granted the {@link android.Manifest.permission#MASTER_CLEAR}
     *                       or both the
     *                       {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_WIPE_DATA} and
     *                       {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_ACROSS_USERS}
     *                       permissions.
     * @see #wipeData(int)
     * @see #wipeData(int, CharSequence)
     */
    // TODO(b/255323293) Add host-side tests
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIPE_DATA, conditional = true)
    public void wipeDevice(int flags) {
        wipeDataInternal(flags,
                /* wipeReasonForUser= */ "",
                /* factoryReset= */ true);
    }

    /**
     * Internal function for {@link #wipeData(int)}, {@link #wipeData(int, CharSequence)}
     * and {@link #wipeDevice(int)} to call.
     *
     * @hide
     * @see #wipeData(int)
     * @see #wipeData(int, CharSequence)
     * @see #wipeDevice(int)
     */
    private void wipeDataInternal(int flags, @NonNull String wipeReasonForUser,
            boolean factoryReset) {
        if (mService != null) {
            try {
                mService.wipeDataWithReason(mContext.getPackageName(), flags, wipeReasonForUser,
                        mParentInstance, factoryReset);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Callable by device owner or profile owner of an organization-owned device, to set a
     * factory reset protection (FRP) policy. When a new policy is set, the system
     * notifies the FRP management agent of a policy change by broadcasting
     * {@code ACTION_RESET_PROTECTION_POLICY_CHANGED}.
     *
     * @param admin  Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin
     * @param policy the new FRP policy, or {@code null} to clear the current policy.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner of
     *                           an organization-owned device.
     * @throws UnsupportedOperationException if factory reset protection is not
     *                           supported on the device.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_FACTORY_RESET, conditional = true)
    public void setFactoryResetProtectionPolicy(@Nullable ComponentName admin,
            @Nullable FactoryResetProtectionPolicy policy) {
        throwIfParentInstance("setFactoryResetProtectionPolicy");
        if (mService != null) {
            try {
                mService.setFactoryResetProtectionPolicy(admin, mContext.getPackageName(), policy);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Callable by device owner or profile owner of an organization-owned device, to retrieve
     * the current factory reset protection (FRP) policy set previously by
     * {@link #setFactoryResetProtectionPolicy}.
     * <p>
     * This method can also be called by the FRP management agent on device or with the permission
     * {@link android.Manifest.permission#MASTER_CLEAR}, in which case, it can pass {@code null}
     * as the ComponentName.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with or
     *              {@code null} if the caller is not a device admin
     * @return The current FRP policy object or {@code null} if no policy is set.
     * @throws SecurityException if {@code admin} is not a device owner, a profile owner of
     *                           an organization-owned device or the FRP management agent.
     * @throws UnsupportedOperationException if factory reset protection is not
     *                           supported on the device.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_FACTORY_RESET, conditional = true)
    public @Nullable FactoryResetProtectionPolicy getFactoryResetProtectionPolicy(
            @Nullable ComponentName admin) {
        throwIfParentInstance("getFactoryResetProtectionPolicy");
        if (mService != null) {
            try {
                return mService.getFactoryResetProtectionPolicy(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Send a lost mode location update to the admin. This API is limited to organization-owned
     * devices, which includes devices with a device owner or devices with a profile owner on an
     * organization-owned managed profile.
     *
     * <p>The caller must hold the
     * {@link android.Manifest.permission#TRIGGER_LOST_MODE} permission.
     *
     * <p>Register a broadcast receiver to receive lost mode location updates. This receiver should
     * subscribe to the {@link #ACTION_LOST_MODE_LOCATION_UPDATE} action and receive the location
     * from an intent extra {@link #EXTRA_LOST_MODE_LOCATION}.
     *
     * <p> Not for use by third-party applications.
     *
     * @param executor The executor through which the callback should be invoked.
     * @param callback A callback object that will inform the caller whether a lost mode location
     *                 update was successfully sent
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.TRIGGER_LOST_MODE)
    public void sendLostModeLocationUpdate(@NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> callback) {
        throwIfParentInstance("sendLostModeLocationUpdate");
        if (mService == null) {
            executeCallback(AndroidFuture.completedFuture(false), executor, callback);
            return;
        }
        try {
            final AndroidFuture<Boolean> future = new AndroidFuture<>();
            mService.sendLostModeLocationUpdate(future);
            executeCallback(future, executor, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void executeCallback(AndroidFuture<Boolean> future,
            @CallbackExecutor @NonNull Executor executor,
            Consumer<Boolean> callback) {
        future.whenComplete((result, error) -> executor.execute(() -> {
            final long token = Binder.clearCallingIdentity();
            try {
                if (error != null) {
                    callback.accept(false);
                } else {
                    callback.accept(result);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }));
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @Nullable ComponentName setGlobalProxy(@NonNull ComponentName admin, Proxy proxySpec,
            List<String> exclusionList ) {
        throwIfParentInstance("setGlobalProxy");
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
                    final Pair<String, String> proxyParams =
                            getProxyParameters(proxySpec, exclusionList);
                    hostSpec = proxyParams.first;
                    exclSpec = proxyParams.second;
                }
                return mService.setGlobalProxy(admin, hostSpec, exclSpec);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Build HTTP proxy parameters for {@link IDevicePolicyManager#setGlobalProxy}.
     * @throws IllegalArgumentException Invalid proxySpec
     * @hide
     */
    @VisibleForTesting
    public Pair<String, String> getProxyParameters(Proxy proxySpec, List<String> exclusionList) {
        InetSocketAddress sa = (InetSocketAddress) proxySpec.address();
        String hostName = sa.getHostName();
        int port = sa.getPort();
        final List<String> trimmedExclList;
        if (exclusionList == null) {
            trimmedExclList = Collections.emptyList();
        } else {
            trimmedExclList = new ArrayList<>(exclusionList.size());
            for (String exclDomain : exclusionList) {
                trimmedExclList.add(exclDomain.trim());
            }
        }
        final ProxyInfo info = ProxyInfo.buildDirectProxy(hostName, port, trimmedExclList);
        // The hostSpec is built assuming that there is a specified port and hostname,
        // but ProxyInfo.isValid() accepts 0 / empty as unspecified: also reject them.
        if (port == 0 || TextUtils.isEmpty(hostName) || !info.isValid()) {
            throw new IllegalArgumentException();
        }

        return new Pair<>(hostName + ":" + port, TextUtils.join(",", trimmedExclList));
    }

    /**
     * Set a network-independent global HTTP proxy. This is not normally what you want for typical
     * HTTP proxies - they are generally network dependent. However if you're doing something
     * unusual like general internal filtering this may be useful. On a private network where the
     * proxy is not accessible, you may break HTTP using this.
     * <p>
     * This method requires the caller to be the device owner.
     * <p>
     * This proxy is only a recommendation and it is possible that some apps will ignore it.
     * <p>
     * Note: The device owner won't be able to set a global HTTP proxy if there are unaffiliated
     * secondary users or profiles on the device. It's recommended that affiliation ids are set for
     * new users as soon as possible after provisioning via {@link #setAffiliationIds}.
     *
     * @see ProxyInfo
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param proxyInfo The a {@link ProxyInfo} object defining the new global HTTP proxy. A
     *            {@code null} value will clear the global HTTP proxy.
     * @throws SecurityException if {@code admin} is not the device owner.
     */
    public void setRecommendedGlobalProxy(@NonNull ComponentName admin, @Nullable ProxyInfo
            proxyInfo) {
        throwIfParentInstance("setRecommendedGlobalProxy");
        if (mService != null) {
            try {
                mService.setRecommendedGlobalProxy(admin, proxyInfo);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the component name setting the global proxy.
     * @return ComponentName object of the device admin that set the global proxy, or {@code null}
     *         if no admin has set the proxy.
     * @hide
     */
    public @Nullable ComponentName getGlobalProxyAdmin() {
        if (mService != null) {
            try {
                return mService.getGlobalProxyAdmin(myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
     * <p>
     * {@link #getStorageEncryptionStatus} can only return this value on devices that use Full Disk
     * Encryption.  Support for Full Disk Encryption was entirely removed in API level 33, having
     * been replaced by File Based Encryption.  Devices that use File Based Encryption always
     * automatically activate their encryption on first boot.
     * <p>
     * {@link #setStorageEncryption} can still return this value for an unrelated reason, but {@link
     * #setStorageEncryption} is deprecated since it doesn't do anything useful.
     */
    public static final int ENCRYPTION_STATUS_INACTIVE = 1;

    /**
     * Result code for {@link #getStorageEncryptionStatus}: indicating that encryption is not
     * currently active, but is currently being activated.
     * <p>
     * @deprecated This result code has never actually been used, so there is no reason for apps to
     * check for it.
     */
    @Deprecated
    public static final int ENCRYPTION_STATUS_ACTIVATING = 2;

    /**
     * Result code for {@link #setStorageEncryption} and {@link #getStorageEncryptionStatus}:
     * indicating that encryption is active.
     * <p>
     * {@link #getStorageEncryptionStatus} can only return this value for apps targeting API level
     * 23 or lower, or on devices that use Full Disk Encryption.  Support for Full Disk Encryption
     * was entirely removed in API level 33, having been replaced by File Based Encryption.  The
     * result code {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER} is used on devices that use File Based
     * Encryption, except when the app targets API level 23 or lower.
     * <p>
     * {@link #setStorageEncryption} can still return this value for an unrelated reason, but {@link
     * #setStorageEncryption} is deprecated since it doesn't do anything useful.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE = 3;

    /**
     * Result code for {@link #getStorageEncryptionStatus}: indicating that encryption is active,
     * but the encryption key is not cryptographically protected by the user's credentials.
     * <p>
     * This value can only be returned on devices that use Full Disk Encryption.  Support for Full
     * Disk Encryption was entirely removed in API level 33, having been replaced by File Based
     * Encryption.  With File Based Encryption, each user's credential-encrypted storage is always
     * cryptographically protected by the user's credentials.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY = 4;

    /**
     * Result code for {@link #getStorageEncryptionStatus}:
     * indicating that encryption is active and the encryption key is tied to the user or profile.
     * <p>
     * This value is only returned to apps targeting API level 24 and above. For apps targeting
     * earlier API levels, {@link #ENCRYPTION_STATUS_ACTIVE} is returned, even if the
     * encryption key is specific to the user or profile.
     */
    public static final int ENCRYPTION_STATUS_ACTIVE_PER_USER = 5;

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
     * Activity action: launch the DPC to check policy compliance. This intent is launched when
     * the user taps on the notification about personal apps suspension. When handling this intent
     * the DPC must check if personal apps should still be suspended and either unsuspend them or
     * instruct the user on how to resolve the noncompliance causing the suspension.
     *
     * @see #setPersonalAppsSuspended
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CHECK_POLICY_COMPLIANCE =
            "android.app.action.CHECK_POLICY_COMPLIANCE";

    /**
     * Broadcast action: notify managed provisioning that PO/DO provisioning has completed.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PROVISIONING_COMPLETED =
            "android.app.action.PROVISIONING_COMPLETED";

    /**
     * Extra for {@link #ACTION_PROVISIONING_COMPLETED} to indicate the provisioning action that has
     * been completed, this can either be {@link #ACTION_PROVISION_MANAGED_PROFILE},
     * {@link #ACTION_PROVISION_MANAGED_DEVICE}, or {@link #ACTION_PROVISION_MANAGED_USER}.
     *
     * @hide
     */
    public static final String EXTRA_PROVISIONING_ACTION =
            "android.app.extra.PROVISIONING_ACTION";

    /**
     * Broadcast action: notify system that a new (Android) user was added when the device is
     * managed by a device owner, so receivers can show the proper disclaimer to the (human) user.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SHOW_NEW_USER_DISCLAIMER =
            "android.app.action.SHOW_NEW_USER_DISCLAIMER";

    /**
     * Widgets are enabled in keyguard
     */
    public static final int KEYGUARD_DISABLE_FEATURES_NONE = 0;

    /**
     * Disable all keyguard widgets. Has no effect between {@link
     * android.os.Build.VERSION_CODES#LOLLIPOP} and {@link
     * android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} (both inclusive), since keyguard widget is
     * only supported on Android versions lower than 5.0 and versions higher than 14.
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
     * Disable trust agents on secure keyguard screens (e.g. PIN/Pattern/Password).
     * By setting this flag alone, all trust agents are disabled. If the admin then wants to
     * allowlist specific features of some trust agent, {@link #setTrustAgentConfiguration} can be
     * used in conjuction to set trust-agent-specific configurations.
     */
    public static final int KEYGUARD_DISABLE_TRUST_AGENTS = 1 << 4;

    /**
     * Disable fingerprint authentication on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_FINGERPRINT = 1 << 5;

    /**
     * Disable text entry into notifications on secure keyguard screens (e.g. PIN/Pattern/Password).
     * @deprecated This flag was added in version {@link android.os.Build.VERSION_CODES#N}, but it
     * never had any effect.
     */
    @Deprecated
    public static final int KEYGUARD_DISABLE_REMOTE_INPUT = 1 << 6;

    /**
     * Disable face authentication on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_FACE = 1 << 7;

    /**
     * Disable iris authentication on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_IRIS = 1 << 8;

    /**
     * Disable all keyguard shortcuts.
     */
    public static final int KEYGUARD_DISABLE_SHORTCUTS_ALL = 1 << 9;

    /**
     * NOTE: Please remember to update the DevicePolicyManagerTest's testKeyguardDisabledFeatures
     * CTS test when adding to the list above.
     */

    /**
     * Disable all biometric authentication on keyguard secure screens (e.g. PIN/Pattern/Password).
     */
    public static final int KEYGUARD_DISABLE_BIOMETRICS =
            DevicePolicyManager.KEYGUARD_DISABLE_FACE
            | DevicePolicyManager.KEYGUARD_DISABLE_IRIS
            | DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT;

    /**
     * Disable all current and future keyguard customizations.
     */
    public static final int KEYGUARD_DISABLE_FEATURES_ALL = 0x7fffffff;

    /**
     * Keyguard features that when set on a non-organization-owned managed profile that doesn't
     * have its own challenge will affect the profile's parent user. These can also be set on the
     * managed profile's parent {@link DevicePolicyManager} instance to explicitly control the
     * parent user.
     *
     * <p>
     * Organization-owned managed profile supports disabling additional keyguard features on the
     * parent user as defined in {@link #ORG_OWNED_PROFILE_KEYGUARD_FEATURES_PARENT_ONLY}.
     *
     * @hide
     */
    public static final int NON_ORG_OWNED_PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER =
            DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS
            | DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS;

    /**
     * Keyguard features that when set by the profile owner of an organization-owned managed
     * profile will affect the profile's parent user if set on the managed profile's parent
     * {@link DevicePolicyManager} instance.
     *
     * @hide
     */
    public static final int ORG_OWNED_PROFILE_KEYGUARD_FEATURES_PARENT_ONLY =
            DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA
                    | DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS
                    | DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL
                    | DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL;

    /**
     * Keyguard features that when set on a normal or organization-owned managed profile, have
     * the potential to affect the profile's parent user.
     *
     * @hide
     */
    public static final int PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER =
            DevicePolicyManager.NON_ORG_OWNED_PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER
                    | DevicePolicyManager.ORG_OWNED_PROFILE_KEYGUARD_FEATURES_PARENT_ONLY;

    /**
     * @deprecated This method does not actually modify the storage encryption of the device.
     * It has never affected the encryption status of a device.
     *
     * Called by an application that is administering the device to request that the storage system
     * be encrypted. Does nothing if the caller is on a secondary user or a managed profile.
     * <p>
     * When multiple device administrators attempt to control device encryption, the most secure,
     * supported setting will always be used. If any device administrator requests device
     * encryption, it will be enabled; Conversely, if a device administrator attempts to disable
     * device encryption while another device administrator has enabled it, the call to disable will
     * fail (most commonly returning {@link #ENCRYPTION_STATUS_ACTIVE}).
     * <p>
     * This policy controls encryption of the secure (application data) storage area. Data written
     * to other storage areas may or may not be encrypted, and this policy does not require or
     * control the encryption of any other storage areas. There is one exception: If
     * {@link android.os.Environment#isExternalStorageEmulated()} is {@code true}, then the
     * directory returned by {@link android.os.Environment#getExternalStorageDirectory()} must be
     * written to disk within the encrypted storage area.
     * <p>
     * Important Note: On some devices, it is possible to encrypt storage without requiring the user
     * to create a device PIN or Password. In this case, the storage is encrypted, but the
     * encryption key may not be fully secured. For maximum security, the administrator should also
     * require (and check for) a pattern, PIN, or password.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param encrypt true to request encryption, false to release any previous request
     * @return the new total request status (for all active admins), or {@link
     *         DevicePolicyManager#ENCRYPTION_STATUS_UNSUPPORTED} if called for a non-system user.
     *         Will be one of {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link
     *         #ENCRYPTION_STATUS_INACTIVE}, or {@link #ENCRYPTION_STATUS_ACTIVE}. This is the value
     *         of the requests; use {@link #getStorageEncryptionStatus()} to query the actual device
     *         state.
     *
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             {@link DeviceAdminInfo#USES_ENCRYPTED_STORAGE}
     */
    @Deprecated
    public int setStorageEncryption(@NonNull ComponentName admin, boolean encrypt) {
        throwIfParentInstance("setStorageEncryption");
        if (mService != null) {
            try {
                return mService.setStorageEncryption(admin, encrypt);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * @deprecated This method only returns the value set by {@link #setStorageEncryption}.
     * It does not actually reflect the storage encryption status.
     * Use {@link #getStorageEncryptionStatus} for that.
     *
     * Called by an application that is administering the device to
     * determine the requested setting for secure storage.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.  If null,
     * this will return the requested encryption setting as an aggregate of all active
     * administrators.
     * @return true if the admin(s) are requesting encryption, false if not.
     */
    @Deprecated
    public boolean getStorageEncryption(@Nullable ComponentName admin) {
        throwIfParentInstance("getStorageEncryption");
        if (mService != null) {
            try {
                return mService.getStorageEncryption(admin, myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to
     * determine the current encryption status of the device.
     * <p>
     * Depending on the returned status code, the caller may proceed in different
     * ways.  If the result is {@link #ENCRYPTION_STATUS_UNSUPPORTED}, the
     * storage system does not support encryption.  If the
     * result is {@link #ENCRYPTION_STATUS_INACTIVE}, use {@link
     * #ACTION_START_ENCRYPTION} to begin the process of encrypting or decrypting the
     * storage.  If the result is {@link #ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY}, the
     * storage system has enabled encryption but no password is set so further action
     * may be required.  If the result is {@link #ENCRYPTION_STATUS_ACTIVATING},
     * {@link #ENCRYPTION_STATUS_ACTIVE} or {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER},
     * no further action is required.
     *
     * @return current status of encryption. The value will be one of
     * {@link #ENCRYPTION_STATUS_UNSUPPORTED}, {@link #ENCRYPTION_STATUS_INACTIVE},
     * {@link #ENCRYPTION_STATUS_ACTIVATING}, {@link #ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY},
     * {@link #ENCRYPTION_STATUS_ACTIVE}, or {@link #ENCRYPTION_STATUS_ACTIVE_PER_USER}.
     *
     * @throws SecurityException if called on a parent instance.
     */
    public int getStorageEncryptionStatus() {
        throwIfParentInstance("getStorageEncryptionStatus");
        return getStorageEncryptionStatus(myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage
    public int getStorageEncryptionStatus(int userHandle) {
        if (mService != null) {
            try {
                return mService.getStorageEncryptionStatus(mContext.getPackageName(), userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * Mark a CA certificate as approved by the device user. This means that they have been notified
     * of the installation, were made aware of the risks, viewed the certificate and still wanted to
     * keep the certificate on the device.
     *
     * Calling with {@param approval} as {@code true} will cancel any ongoing warnings related to
     * this certificate.
     *
     * @hide
     */
    public boolean approveCaCert(String alias, int userHandle, boolean approval) {
        if (mService != null) {
            try {
                return mService.approveCaCert(alias, userHandle, approval);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Check whether a CA certificate has been approved by the device user.
     *
     * @hide
     */
    public boolean isCaCertApproved(String alias, int userHandle) {
        if (mService != null) {
            try {
                return mService.isCaCertApproved(alias, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Installs the given certificate as a user CA.
     * <p>
     * Inserted user CAs aren't automatically trusted by apps in Android 7.0 (API level 24) and
     * higher. App developers can change the default behavior for an app by adding a
     * <a href="{@docRoot}training/articles/security-config.html">Security Configuration
     * File</a> to the app manifest file.
     *
     * The caller must be a profile or device owner on that user, or a delegate package given the
     * {@link #DELEGATION_CERT_INSTALL} scope via {@link #setDelegatedScopes}; otherwise a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to install.
     *
     * @return false if the certBuffer cannot be parsed or installation is
     *         interrupted, true otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    public boolean installCaCert(@Nullable ComponentName admin, byte[] certBuffer) {
        throwIfParentInstance("installCaCert");
        if (mService != null) {
            try {
                return mService.installCaCert(admin, mContext.getPackageName(), certBuffer);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Uninstalls the given certificate from trusted user CAs, if present.
     *
     * The caller must be a profile or device owner on that user, or a delegate package given the
     * {@link #DELEGATION_CERT_INSTALL} scope via {@link #setDelegatedScopes}; otherwise a
     * security exception will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to remove.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    public void uninstallCaCert(@Nullable ComponentName admin, byte[] certBuffer) {
        throwIfParentInstance("uninstallCaCert");
        if (mService != null) {
            try {
                final String alias = getCaCertAlias(certBuffer);
                mService.uninstallCaCerts(admin, mContext.getPackageName(), new String[] {alias});
            } catch (CertificateException e) {
                Log.w(TAG, "Unable to parse certificate", e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public @NonNull List<byte[]> getInstalledCaCerts(@Nullable ComponentName admin) {
        final List<byte[]> certs = new ArrayList<byte[]>();
        throwIfParentInstance("getInstalledCaCerts");
        if (mService != null) {
            try {
                mService.enforceCanManageCaCerts(admin, mContext.getPackageName());
                final TrustedCertificateStore certStore = new TrustedCertificateStore();
                for (String alias : certStore.userAliases()) {
                    try {
                        certs.add(certStore.getCertificate(alias).getEncoded());
                    } catch (CertificateException ce) {
                        Log.w(TAG, "Could not encode certificate: " + alias, ce);
                    }
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
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
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public void uninstallAllUserCaCerts(@Nullable ComponentName admin) {
        throwIfParentInstance("uninstallAllUserCaCerts");
        if (mService != null) {
            try {
                mService.uninstallCaCerts(admin, mContext.getPackageName(),
                        new TrustedCertificateStore().userAliases() .toArray(new String[0]));
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns whether this certificate is installed as a trusted CA.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *              {@code null} if calling from a delegated certificate installer.
     * @param certBuffer encoded form of the certificate to look up.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner.
     */
    public boolean hasCaCertInstalled(@Nullable ComponentName admin, byte[] certBuffer) {
        throwIfParentInstance("hasCaCertInstalled");
        if (mService != null) {
            try {
                mService.enforceCanManageCaCerts(admin, mContext.getPackageName());
                return getCaCertAlias(certBuffer) != null;
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            } catch (CertificateException ce) {
                Log.w(TAG, "Could not parse certificate", ce);
            }
        }
        return false;
    }

    /**
     * This API can be called by the following to install a certificate and corresponding
     * private key:
     * <ul>
     *    <li>Device owner</li>
     *    <li>Profile owner</li>
     *    <li>Delegated certificate installer</li>
     *    <li>Credential management app</li>
     *    <li>An app that holds the
     *    {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission</li>
     * </ul>
     * All apps within the profile will be able to access the certificate and use the private key,
     * given direct user approval.
     *
     * <p>From Android {@link android.os.Build.VERSION_CODES#S}, the credential management app
     * can call this API. However, this API sets the key pair as user selectable by default,
     * which is not permitted when called by the credential management app. Instead,
     * {@link #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)} should be
     * called with {@link #INSTALLKEY_SET_USER_SELECTABLE} not set as a flag.
     *
     * <p>Access to the installed credentials will not be granted to the caller of this API without
     * direct user approval. This is for security - should a certificate installer become
     * compromised, certificates it had already installed will be protected.
     *
     * <p>If the installer must have access to the credentials, call
     * {@link #installKeyPair(ComponentName, PrivateKey, Certificate[], String, boolean)} instead.
     *
     * <p>Note: If the provided {@code alias} is of an existing alias, all former grants that apps
     * have been given to access the key and certificates associated with this alias will be
     * revoked.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is not a device admin.
     * @param privKey The private key to install.
     * @param cert The certificate to install.
     * @param alias The private key alias under which to install the certificate. If a certificate
     * with that alias already exists, it will be overwritten.
     * @return {@code true} if the keys were installed, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner, or {@code admin} is null and the calling application does not have the
     *         {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission.
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CERTIFICATES, conditional = true)
    public boolean installKeyPair(@Nullable ComponentName admin, @NonNull PrivateKey privKey,
            @NonNull Certificate cert, @NonNull String alias) {
        return installKeyPair(admin, privKey, new Certificate[] {cert}, alias, false);
    }

    /**
     * This API can be called by the following to install a certificate chain and corresponding
     * private key for the leaf certificate:
     * <ul>
     *    <li>Device owner</li>
     *    <li>Profile owner</li>
     *    <li>Delegated certificate installer</li>
     *    <li>Credential management app</li>
     *    <li>An app that holds the
     *    {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission</li>
     * </ul>
     * All apps within the profile will be able to access the certificate chain and use the private
     * key, given direct user approval.
     *
     * <p>From Android {@link android.os.Build.VERSION_CODES#S}, the credential management app
     * can call this API. However, this API sets the key pair as user selectable by default,
     * which is not permitted when called by the credential management app. Instead,
     * {@link #installKeyPair(ComponentName, PrivateKey, Certificate[], String, int)} should be
     * called with {@link #INSTALLKEY_SET_USER_SELECTABLE} not set as a flag.
     * Note, there can only be a credential management app on an unmanaged device.
     *
     * <p>The caller of this API may grant itself access to the certificate and private key
     * immediately, without user approval. It is a best practice not to request this unless strictly
     * necessary since it opens up additional security vulnerabilities.
     *
     * <p>Note: If the provided {@code alias} is of an existing alias, all former grants that apps
     * have been given to access the key and certificates associated with this alias will be
     * revoked.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if the caller is not a device admin.
     * @param privKey The private key to install.
     * @param certs The certificate chain to install. The chain should start with the leaf
     *        certificate and include the chain of trust in order. This will be returned by
     *        {@link android.security.KeyChain#getCertificateChain}.
     * @param alias The private key alias under which to install the certificate. If a certificate
     *        with that alias already exists, it will be overwritten.
     * @param requestAccess {@code true} to request that the calling app be granted access to the
     *        credentials immediately. Otherwise, access to the credentials will be gated by user
     *        approval.
     * @return {@code true} if the keys were installed, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner, or {@code admin} is null and the calling application does not have the
     *         {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission.
     * @see android.security.KeyChain#getCertificateChain
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CERTIFICATES, conditional = true)
    public boolean installKeyPair(@Nullable ComponentName admin, @NonNull PrivateKey privKey,
            @NonNull Certificate[] certs, @NonNull String alias, boolean requestAccess) {
        int flags = INSTALLKEY_SET_USER_SELECTABLE;
        if (requestAccess) {
            flags |= INSTALLKEY_REQUEST_CREDENTIALS_ACCESS;
        }
        return installKeyPair(admin, privKey, certs, alias, flags);
    }

    /**
     * This API can be called by the following to install a certificate chain and corresponding
     * private key for the leaf certificate:
     * <ul>
     *    <li>Device owner</li>
     *    <li>Profile owner</li>
     *    <li>Delegated certificate installer</li>
     *    <li>Credential management app</li>
     *    <li>An app that holds the
     *    {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission</li>
     * </ul>
     * All apps within the profile will be able to access the certificate chain and use the
     * private key, given direct user approval (if the user is allowed to select the private key).
     *
     * <p>From Android {@link android.os.Build.VERSION_CODES#S}, the credential management app
     * can call this API. If called by the credential management app:
     * <ul>
     *    <li>The componentName must be {@code null}r</li>
     *    <li>The alias must exist in the credential management app's
     *    {@link android.security.AppUriAuthenticationPolicy}</li>
     *    <li>The key pair must not be user selectable</li>
     * </ul>
     * Note, there can only be a credential management app on an unmanaged device.
     *
     * <p>The caller of this API may grant itself access to the certificate and private key
     * immediately, without user approval. It is a best practice not to request this unless strictly
     * necessary since it opens up additional security vulnerabilities.
     *
     * <p>Include {@link #INSTALLKEY_SET_USER_SELECTABLE} in the {@code flags} argument to allow
     * the user to select the key from a dialog.
     *
     * <p>Note: If the provided {@code alias} is of an existing alias, all former grants that apps
     * have been given to access the key and certificates associated with this alias will be
     * revoked.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if the caller is not a device admin.
     * @param privKey The private key to install.
     * @param certs The certificate chain to install. The chain should start with the leaf
     *        certificate and include the chain of trust in order. This will be returned by
     *        {@link android.security.KeyChain#getCertificateChain}.
     * @param alias The private key alias under which to install the certificate. If a certificate
     *        with that alias already exists, it will be overwritten.
     * @param flags Flags to request that the calling app be granted access to the credentials
     *        and set the key to be user-selectable. See {@link #INSTALLKEY_SET_USER_SELECTABLE} and
     *        {@link #INSTALLKEY_REQUEST_CREDENTIALS_ACCESS}.
     * @return {@code true} if the keys were installed, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or
     *        profile owner, or {@code admin} is null but the calling application is not a
     *        delegated certificate installer, credential management app and does not have the
     *        {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission.
     * @see android.security.KeyChain#getCertificateChain
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CERTIFICATES, conditional = true)
    public boolean installKeyPair(@Nullable ComponentName admin, @NonNull PrivateKey privKey,
            @NonNull Certificate[] certs, @NonNull String alias, int flags) {
        throwIfParentInstance("installKeyPair");
        boolean requestAccess = (flags & INSTALLKEY_REQUEST_CREDENTIALS_ACCESS)
                == INSTALLKEY_REQUEST_CREDENTIALS_ACCESS;
        boolean isUserSelectable = (flags & INSTALLKEY_SET_USER_SELECTABLE)
                == INSTALLKEY_SET_USER_SELECTABLE;
        try {
            final byte[] pemCert = Credentials.convertToPem(certs[0]);
            byte[] pemChain = null;
            if (certs.length > 1) {
                pemChain = Credentials.convertToPem(Arrays.copyOfRange(certs, 1, certs.length));
            }
            final byte[] pkcs8Key = KeyFactory.getInstance(privKey.getAlgorithm())
                    .getKeySpec(privKey, PKCS8EncodedKeySpec.class).getEncoded();
            return mService.installKeyPair(admin, mContext.getPackageName(), pkcs8Key, pemCert,
                    pemChain, alias, requestAccess, isUserSelectable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            Log.w(TAG, "Failed to obtain private key material", e);
        } catch (CertificateException | IOException e) {
            Log.w(TAG, "Could not pem-encode certificate", e);
        }
        return false;
    }

    /**
     * This API can be called by the following to remove a certificate and private key pair
     * installed under a given alias:
     * <ul>
     *    <li>Device owner</li>
     *    <li>Profile owner</li>
     *    <li>Delegated certificate installer</li>
     *    <li>Credential management app</li>
     * </ul>
     *
     * <p>From Android {@link android.os.Build.VERSION_CODES#S}, the credential management app
     * can call this API. If called by the credential management app, the componentName must be
     * {@code null}. Note, there can only be a credential management app on an unmanaged device.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if the caller is not a device admin.
     * @param alias The private key alias under which the certificate is installed.
     * @return {@code true} if the private key alias no longer exists, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner, or {@code admin} is null but the calling application is not a delegated
     *         certificate installer or credential management app.
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CERTIFICATES, conditional = true)
    public boolean removeKeyPair(@Nullable ComponentName admin, @NonNull String alias) {
        throwIfParentInstance("removeKeyPair");
        try {
            return mService.removeKeyPair(admin, mContext.getPackageName(), alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // STOPSHIP(b/174298501): clarify the expected return value following generateKeyPair call.
    /**
     * This API can be called by the following to query whether a certificate and private key are
     * installed under a given alias:
     * <ul>
     *    <li>Device owner</li>
     *    <li>Profile owner</li>
     *    <li>Delegated certificate installer</li>
     *    <li>Credential management app</li>
     *    <li>An app that holds the
     *    {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission</li>
     * </ul>
     *
     * If called by the credential management app, the alias must exist in the credential
     * management app's {@link android.security.AppUriAuthenticationPolicy}.
     *
     * @param alias The alias under which the key pair is installed.
     * @return {@code true} if a key pair with this alias exists, {@code false} otherwise.
     * @throws SecurityException if the caller is not a device or profile owner, a delegated
     *         certificate installer, the credential management app and does not have the
     *         {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission.
     * @see #setDelegatedScopes
     * @see #DELEGATION_CERT_INSTALL
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CERTIFICATES, conditional = true)
    public boolean hasKeyPair(@NonNull String alias) {
        throwIfParentInstance("hasKeyPair");
        try {
            return mService.hasKeyPair(mContext.getPackageName(), alias);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This API can be called by the following to generate a new private/public key pair:
     * <ul>
     *    <li>Device owner</li>
     *    <li>Profile owner</li>
     *    <li>Delegated certificate installer</li>
     *    <li>Credential management app</li>
     *    <li>An app that holds the
     *    {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission</li>
     * </ul>
     * If the device supports key generation via secure hardware, this method is useful for
     * creating a key in KeyChain that never left the secure hardware. Access to the key is
     * controlled the same way as in {@link #installKeyPair}.
     *
     * <p>From Android {@link android.os.Build.VERSION_CODES#S}, the credential management app
     * can call this API. If called by the credential management app, the componentName must be
     * {@code null}. Note, there can only be a credential management app on an unmanaged device.
     *
     * <p>Because this method might take several seconds to complete, it should only be called from
     * a worker thread. This method returns {@code null} when called from the main thread.
     *
     * <p>This method is not thread-safe, calling it from multiple threads at the same time will
     * result in undefined behavior. If the calling thread is interrupted while the invocation is
     * in-flight, it will eventually terminate and return {@code null}.
     *
     * <p>Note: If the provided {@code alias} is of an existing alias, all former grants that apps
     * have been given to access the key and certificates associated with this alias will be
     * revoked.
     *
     * <p>Attestation: to enable attestation, set an attestation challenge in {@code keySpec} via
     * {@link KeyGenParameterSpec.Builder#setAttestationChallenge}. By specifying flags to the
     * {@code idAttestationFlags} parameter, it is possible to request the device's unique
     * identity to be included in the attestation record.
     *
     * <p>Specific identifiers can be included in the attestation record, and an individual
     * attestation certificate can be used to sign the attestation record. To find out if the device
     * supports these features, refer to {@link #isDeviceIdAttestationSupported()} and
     * {@link #isUniqueDeviceAttestationSupported()}.
     *
     * <p>Device owner, profile owner, their delegated certificate installer and the credential
     * management app can use {@link #ID_TYPE_BASE_INFO} to request inclusion of the general device
     * information including manufacturer, model, brand, device and product in the attestation
     * record.
     * Only device owner, profile owner on an organization-owned device or affiliated user, and
     * their delegated certificate installers can use {@link #ID_TYPE_SERIAL}, {@link #ID_TYPE_IMEI}
     * and {@link #ID_TYPE_MEID} to request unique device identifiers to be attested (the serial
     * number, IMEI and MEID correspondingly), if supported by the device
     * (see {@link #isDeviceIdAttestationSupported()}).
     * Additionally, device owner, profile owner on an organization-owned device and their delegated
     * certificate installers can also request the attestation record to be signed using an
     * individual attestation certificate by specifying the {@link #ID_TYPE_INDIVIDUAL_ATTESTATION}
     * flag (if supported by the device, see {@link #isUniqueDeviceAttestationSupported()}).
     * <p>
     * If any of {@link #ID_TYPE_SERIAL}, {@link #ID_TYPE_IMEI} and {@link #ID_TYPE_MEID}
     * is set, it is implicitly assumed that {@link #ID_TYPE_BASE_INFO} is also set.
     * <p>
     * Attestation using {@link #ID_TYPE_INDIVIDUAL_ATTESTATION} can only be requested if
     * key generation is done in StrongBox.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is not a device admin.
     * @param algorithm The key generation algorithm, see {@link java.security.KeyPairGenerator}.
     * @param keySpec Specification of the key to generate, see
     * {@link java.security.KeyPairGenerator}.
     * @param idAttestationFlags A bitmask of the identifiers that should be included in the
     *        attestation record ({@code ID_TYPE_BASE_INFO}, {@code ID_TYPE_SERIAL},
     *        {@code ID_TYPE_IMEI} and {@code ID_TYPE_MEID}), and
     *        {@code ID_TYPE_INDIVIDUAL_ATTESTATION} if the attestation record should be signed
     *        using an individual attestation certificate.
     *        <p>
     *        {@code 0} should be passed in if no device identification is required in the
     *        attestation record and the batch attestation certificate should be used.
     *        <p>
     *        If any flag is specified, then an attestation challenge must be included in the
     *        {@code keySpec}.
     * @return A non-null {@code AttestedKeyPair} if the key generation succeeded, null otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner, or {@code admin} is null but the calling application is not a delegated
     *         certificate installer or credential management app. If Device ID attestation is
     *         requested (using {@link #ID_TYPE_SERIAL}, {@link #ID_TYPE_IMEI} or
     *         {@link #ID_TYPE_MEID}), the caller must be the Device Owner or the Certificate
     *         Installer delegate.
     * @throws IllegalArgumentException in the following cases:
     *         <p>
     *         <ul>
     *         <li>The alias in {@code keySpec} is empty.</li>
     *         <li>The algorithm specification in {@code keySpec} is not
     *         {@code RSAKeyGenParameterSpec} or {@code ECGenParameterSpec}.</li>
     *         <li>Device ID attestation was requested but the {@code keySpec} does not contain an
     *         attestation challenge.</li>
     *         </ul>
     * @throws UnsupportedOperationException if Device ID attestation or individual attestation
     *         was requested but the underlying hardware does not support it.
     * @throws StrongBoxUnavailableException if the use of StrongBox for key generation was
     *         specified in {@code keySpec} but the device does not have one.
     * @see KeyGenParameterSpec.Builder#setAttestationChallenge(byte[])
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CERTIFICATES, conditional = true)
    public AttestedKeyPair generateKeyPair(@Nullable ComponentName admin,
            @NonNull String algorithm, @NonNull KeyGenParameterSpec keySpec,
            @AttestationIdType int idAttestationFlags) {
        throwIfParentInstance("generateKeyPair");
        try {
            final ParcelableKeyGenParameterSpec parcelableSpec =
                    new ParcelableKeyGenParameterSpec(keySpec);
            KeymasterCertificateChain attestationChain = new KeymasterCertificateChain();

            // Translate ID attestation flags to values used by AttestationUtils
            final boolean success = mService.generateKeyPair(
                    admin, mContext.getPackageName(), algorithm, parcelableSpec,
                    idAttestationFlags, attestationChain);
            if (!success) {
                Log.e(TAG, "Error generating key via DevicePolicyManagerService.");
                return null;
            }

            final String alias = keySpec.getKeystoreAlias();
            final KeyPair keyPair = KeyChain.getKeyPair(mContext, alias);
            Certificate[] outputChain = null;
            try {
                if (AttestationUtils.isChainValid(attestationChain)) {
                    outputChain = AttestationUtils.parseCertificateChain(attestationChain);
                }
            } catch (KeyAttestationException e) {
                Log.e(TAG, "Error parsing attestation chain for alias " + alias, e);
                mService.removeKeyPair(admin, mContext.getPackageName(), alias);
                return null;
            }
            return new AttestedKeyPair(keyPair, outputChain);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (KeyChainException e) {
            Log.w(TAG, "Failed to generate key", e);
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted while generating key", e);
            Thread.currentThread().interrupt();
        } catch (ServiceSpecificException e) {
            Log.w(TAG, String.format("Key Generation failure: %d", e.errorCode));
            switch (e.errorCode) {
                case KEY_GEN_STRONGBOX_UNAVAILABLE:
                    throw new StrongBoxUnavailableException("No StrongBox for key generation.");
                default:
                    throw new RuntimeException(
                            String.format("Unknown error while generating key: %d", e.errorCode));
            }
        }
        return null;
    }

    /**
     * Called by a device or profile owner, or delegated certificate chooser (an app that has been
     * delegated the {@link #DELEGATION_CERT_SELECTION} privilege), to grant an application access
     * to an already-installed (or generated) KeyChain key.
     * This is useful (in combination with {@link #installKeyPair} or {@link #generateKeyPair}) to
     * let an application call {@link android.security.KeyChain#getPrivateKey} without having to
     * call {@link android.security.KeyChain#choosePrivateKeyAlias} first.
     *
     * The grantee app will receive the {@link android.security.KeyChain#ACTION_KEY_ACCESS_CHANGED}
     * broadcast when access to a key is granted.
     *
     * Starting from {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} throws an
     * {@link IllegalArgumentException} if {@code alias} doesn't correspond to an existing key.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if calling from a delegated certificate chooser.
     * @param alias The alias of the key to grant access to.
     * @param packageName The name of the (already installed) package to grant access to.
     * @return {@code true} if the grant was set successfully, {@code false} otherwise.
     *
     * @throws SecurityException if the caller is not a device owner, a profile owner or
     *         delegated certificate chooser.
     * @throws IllegalArgumentException if {@code packageName} or {@code alias} are empty, or if
     *         {@code packageName} is not a name of an installed package.
     * @see #revokeKeyPairFromApp
     */
    public boolean grantKeyPairToApp(@Nullable ComponentName admin, @NonNull String alias,
            @NonNull String packageName) {
        throwIfParentInstance("grantKeyPairToApp");
        try {
            return mService.setKeyGrantForApp(
                    admin, mContext.getPackageName(), alias, packageName, true);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Called by a device or profile owner, or delegated certificate chooser (an app that has been
     * delegated the {@link #DELEGATION_CERT_SELECTION} privilege), to query which apps have access
     * to a given KeyChain key.
     *
     * Key are granted on a per-UID basis, so if several apps share the same UID, granting access to
     * one of them automatically grants it to others. This method returns a map containing one entry
     * per grantee UID. Entries have UIDs as keys and sets of corresponding package names as values.
     * In particular, grantee packages that don't share UID with other packages are represented by
     * entries having singleton sets as values.
     *
     * @param alias The alias of the key to grant access to.
     * @return apps that have access to a given key, arranged in a map from UID to sets of
     *       package names.
     *
     * @throws SecurityException if the caller is not a device owner, a profile owner or
     *         delegated certificate chooser.
     * @throws IllegalArgumentException if {@code alias} doesn't correspond to an existing key.
     *
     * @see #grantKeyPairToApp(ComponentName, String, String)
     */
    public @NonNull Map<Integer, Set<String>> getKeyPairGrants(@NonNull String alias) {
        throwIfParentInstance("getKeyPairGrants");
        try {
            // The result is wrapped into intermediate parcelable representation.
            return mService.getKeyPairGrants(mContext.getPackageName(), alias).getPackagesByUid();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return null;
    }

    /**
     * Called by a device or profile owner, or delegated certificate chooser (an app that has been
     * delegated the {@link #DELEGATION_CERT_SELECTION} privilege), to revoke an application's
     * grant to a KeyChain key pair.
     * Calls by the application to {@link android.security.KeyChain#getPrivateKey}
     * will fail after the grant is revoked.
     *
     * The grantee app will receive the {@link android.security.KeyChain#ACTION_KEY_ACCESS_CHANGED}
     * broadcast when access to a key is revoked.
     *
     * Starting from {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} throws an
     * {@link IllegalArgumentException} if {@code alias} doesn't correspond to an existing key.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if calling from a delegated certificate chooser.
     * @param alias The alias of the key to revoke access from.
     * @param packageName The name of the (already installed) package to revoke access from.
     * @return {@code true} if the grant was revoked successfully, {@code false} otherwise.
     *
     * @throws SecurityException if the caller is not a device owner, a profile owner or
     *         delegated certificate chooser.
     * @throws IllegalArgumentException if {@code packageName} or {@code alias} are empty, or if
     *         {@code packageName} is not a name of an installed package.
     * @see #grantKeyPairToApp
     */
    public boolean revokeKeyPairFromApp(@Nullable ComponentName admin, @NonNull String alias,
            @NonNull String packageName) {
        throwIfParentInstance("revokeKeyPairFromApp");
        try {
            return mService.setKeyGrantForApp(
                    admin, mContext.getPackageName(), alias, packageName, false);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Called by a device or profile owner, or delegated certificate chooser (an app that has been
     * delegated the {@link #DELEGATION_CERT_SELECTION} privilege), to allow using a KeyChain key
     * pair for authentication to Wifi networks. The key can then be used in configurations passed
     * to {@link android.net.wifi.WifiManager#addNetwork}.
     *
     * Starting from {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} throws an
     * {@link IllegalArgumentException} if {@code alias} doesn't correspond to an existing key.
     *
     * @param alias The alias of the key pair.
     * @return {@code true} if the operation was set successfully, {@code false} otherwise.
     *
     * @throws SecurityException if the caller is not a device owner, a profile owner or
     *         delegated certificate chooser.
     * @see #revokeKeyPairFromWifiAuth
     */
    public boolean grantKeyPairToWifiAuth(@NonNull String alias) {
        throwIfParentInstance("grantKeyPairToWifiAuth");
        try {
            return mService.setKeyGrantToWifiAuth(mContext.getPackageName(), alias, true);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Called by a device or profile owner, or delegated certificate chooser (an app that has been
     * delegated the {@link #DELEGATION_CERT_SELECTION} privilege), to deny using a KeyChain key
     * pair for authentication to Wifi networks. Configured networks using this key won't be able to
     * authenticate.
     *
     * Starting from {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} throws an
     * {@link IllegalArgumentException} if {@code alias} doesn't correspond to an existing key.
     *
     * @param alias The alias of the key pair.
     * @return {@code true} if the operation was set successfully, {@code false} otherwise.
     *
     * @throws SecurityException if the caller is not a device owner, a profile owner or
     *         delegated certificate chooser.
     * @see #grantKeyPairToWifiAuth
     */
    public boolean revokeKeyPairFromWifiAuth(@NonNull String alias) {
        throwIfParentInstance("revokeKeyPairFromWifiAuth");
        try {
            return mService.setKeyGrantToWifiAuth(mContext.getPackageName(), alias, false);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Called by a device or profile owner, or delegated certificate chooser (an app that has been
     * delegated the {@link #DELEGATION_CERT_SELECTION} privilege), to query whether a KeyChain key
     * pair can be used for authentication to Wifi networks.
     *
     * @param alias The alias of the key pair.
     * @return {@code true} if the key pair can be used, {@code false} otherwise.
     *
     * @throws SecurityException if the caller is not a device owner, a profile owner or
     *         delegated certificate chooser.
     * @see #grantKeyPairToWifiAuth
     */
    public boolean isKeyPairGrantedToWifiAuth(@NonNull String alias) {
        throwIfParentInstance("isKeyPairGrantedToWifiAuth");
        try {
            return mService.isKeyPairGrantedToWifiAuth(mContext.getPackageName(), alias);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Returns {@code true} if the device supports attestation of device identifiers in addition
     * to key attestation. See
     * {@link #generateKeyPair(ComponentName, String, KeyGenParameterSpec, int)}
     * @return {@code true} if Device ID attestation is supported.
     */
    public boolean isDeviceIdAttestationSupported() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_ID_ATTESTATION);
    }

    /**
     * Returns {@code true} if the StrongBox Keymaster implementation on the device was provisioned
     * with an individual attestation certificate and can sign attestation records using it (as
     * attestation using an individual attestation certificate is a feature only Keymaster
     * implementations with StrongBox security level can implement).
     * For use prior to calling
     * {@link #generateKeyPair(ComponentName, String, KeyGenParameterSpec, int)}.
     * @return {@code true} if individual attestation is supported.
     */
    public boolean isUniqueDeviceAttestationSupported() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_DEVICE_UNIQUE_ATTESTATION);
    }

    /**
     * This API can be called by the following to associate certificates with a key pair that was
     * generated using {@link #generateKeyPair}, and set whether the key is available for the user
     * to choose in the certificate selection prompt:
     * <ul>
     *    <li>Device owner</li>
     *    <li>Profile owner</li>
     *    <li>Delegated certificate installer</li>
     *    <li>Credential management app</li>
     * </ul>
     *
     * <p>From Android {@link android.os.Build.VERSION_CODES#S}, the credential management app
     * can call this API. If called by the credential management app, the componentName must be
     * {@code null}. Note, there can only be a credential management app on an unmanaged device.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is not a device admin.
     * @param alias The private key alias under which to install the certificate. The {@code alias}
     *        should denote an existing private key. If a certificate with that alias already
     *        exists, it will be overwritten.
     * @param certs The certificate chain to install. The chain should start with the leaf
     *        certificate and include the chain of trust in order. This will be returned by
     *        {@link android.security.KeyChain#getCertificateChain}.
     * @param isUserSelectable {@code true} to indicate that a user can select this key via the
     *        certificate selection prompt, {@code false} to indicate that this key can only be
     *        granted access by implementing
     *        {@link android.app.admin.DeviceAdminReceiver#onChoosePrivateKeyAlias}.
     * @return {@code true} if the provided {@code alias} exists and the certificates has been
     *        successfully associated with it, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not {@code null} and not a device or profile
     *         owner, or {@code admin} is null but the calling application is not a delegated
     *         certificate installer or credential management app.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CERTIFICATES, conditional = true)
    public boolean setKeyPairCertificate(@Nullable ComponentName admin,
            @NonNull String alias, @NonNull List<Certificate> certs, boolean isUserSelectable) {
        throwIfParentInstance("setKeyPairCertificate");
        try {
            final byte[] pemCert = Credentials.convertToPem(certs.get(0));
            byte[] pemChain = null;
            if (certs.size() > 1) {
                pemChain = Credentials.convertToPem(
                        certs.subList(1, certs.size()).toArray(new Certificate[0]));
            }
            return mService.setKeyPairCertificate(admin, mContext.getPackageName(), alias, pemCert,
                    pemChain, isUserSelectable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
     * <p>
     * <b>Note:</b>Starting from {@link android.os.Build.VERSION_CODES#N}, if the caller
     * application's target SDK version is {@link android.os.Build.VERSION_CODES#N} or newer, the
     * supplied certificate installer package must be installed when calling this API, otherwise an
     * {@link IllegalArgumentException} will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param installerPackage The package name of the certificate installer which will be given
     *            access. If {@code null} is given the current package will be cleared.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #setDelegatedScopes}
     * with the {@link #DELEGATION_CERT_INSTALL} scope instead.
     */
    @Deprecated
    public void setCertInstallerPackage(@NonNull ComponentName admin, @Nullable String
            installerPackage) throws SecurityException {
        throwIfParentInstance("setCertInstallerPackage");
        if (mService != null) {
            try {
                mService.setCertInstallerPackage(admin, installerPackage);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner to retrieve the certificate installer for the user,
     * or {@code null} if none is set. If there are multiple delegates this function will return one
     * of them.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The package name of the current delegated certificate installer, or {@code null} if
     *         none is set.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #getDelegatePackages}
     * with the {@link #DELEGATION_CERT_INSTALL} scope instead.
     */
    @Deprecated
    public @Nullable String getCertInstallerPackage(@NonNull ComponentName admin)
            throws SecurityException {
        throwIfParentInstance("getCertInstallerPackage");
        if (mService != null) {
            try {
                return mService.getCertInstallerPackage(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a profile owner or device owner to grant access to privileged APIs to another app.
     * Granted APIs are determined by {@code scopes}, which is a list of the {@code DELEGATION_*}
     * constants.
     * <p>
     * A broadcast with the {@link #ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED} action will be
     * sent to the {@code delegatePackage} with its new scopes in an {@code ArrayList<String>} extra
     * under the {@link #EXTRA_DELEGATION_SCOPES} key. The broadcast is sent with the
     * {@link Intent#FLAG_RECEIVER_REGISTERED_ONLY} flag.
     * <p>
     * Delegated scopes are a per-user state. The delegated access is persistent until it is later
     * cleared by calling this method with an empty {@code scopes} list or uninstalling the
     * {@code delegatePackage}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param delegatePackage The package name of the app which will be given access.
     * @param scopes The groups of privileged APIs whose access should be granted to
     *            {@code delegatedPackage}.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
     public void setDelegatedScopes(@NonNull ComponentName admin, @NonNull String delegatePackage,
            @NonNull List<String> scopes) {
        throwIfParentInstance("setDelegatedScopes");
        if (mService != null) {
            try {
                mService.setDelegatedScopes(admin, delegatePackage, scopes);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner to retrieve a list of the scopes given to a
     * delegate package. Other apps can use this method to retrieve their own delegated scopes by
     * passing {@code null} for {@code admin} and their own package name as
     * {@code delegatedPackage}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is {@code delegatedPackage}.
     * @param delegatedPackage The package name of the app whose scopes should be retrieved.
     * @return A list containing the scopes given to {@code delegatedPackage}.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
     @NonNull
     public List<String> getDelegatedScopes(@Nullable ComponentName admin,
             @NonNull String delegatedPackage) {
         throwIfParentInstance("getDelegatedScopes");
         if (mService != null) {
             try {
                 return mService.getDelegatedScopes(admin, delegatedPackage);
             } catch (RemoteException e) {
                 throw e.rethrowFromSystemServer();
             }
         }
         return null;
    }

    /**
     * Called by a profile owner or device owner to retrieve a list of delegate packages that were
     * granted a delegation scope.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param delegationScope The scope whose delegates should be retrieved.
     * @return A list of package names of the current delegated packages for
               {@code delegationScope}.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
     @Nullable
     public List<String> getDelegatePackages(@NonNull ComponentName admin,
             @NonNull String delegationScope) {
        throwIfParentInstance("getDelegatePackages");
        if (mService != null) {
            try {
                return mService.getDelegatePackages(admin, delegationScope);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Service-specific error code used in implementation of {@code setAlwaysOnVpnPackage} methods.
     * @hide
     */
    public static final int ERROR_VPN_PACKAGE_NOT_FOUND = 1;

    /**
     * Called by a device or profile owner to configure an always-on VPN connection through a
     * specific application for the current user. This connection is automatically granted and
     * persisted after a reboot.
     * <p> To support the always-on feature, an app must
     * <ul>
     *     <li>declare a {@link android.net.VpnService} in its manifest, guarded by
     *         {@link android.Manifest.permission#BIND_VPN_SERVICE};</li>
     *     <li>target {@link android.os.Build.VERSION_CODES#N API 24} or above; and</li>
     *     <li><i>not</i> explicitly opt out of the feature through
     *         {@link android.net.VpnService#SERVICE_META_DATA_SUPPORTS_ALWAYS_ON}.</li>
     * </ul>
     * The call will fail if called with the package name of an unsupported VPN app.
     * <p> Enabling lockdown via {@code lockdownEnabled} argument carries the risk that any failure
     * of the VPN provider could break networking for all apps. This method clears any lockdown
     * allowlist set by {@link #setAlwaysOnVpnPackage(ComponentName, String, boolean, Set)}.
     * <p> Starting from {@link android.os.Build.VERSION_CODES#S API 31} calling this method with
     * {@code vpnPackage} set to {@code null} only removes the existing configuration if it was
     * previously created by this admin. To remove VPN configuration created by the user use
     * {@link UserManager#DISALLOW_CONFIG_VPN}.
     *
     * @param vpnPackage The package name for an installed VPN app on the device, or {@code null} to
     *        remove an existing always-on VPN configuration.
     * @param lockdownEnabled {@code true} to disallow networking when the VPN is not connected or
     *        {@code false} otherwise. This has no effect when clearing.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     * @throws NameNotFoundException if {@code vpnPackage} is not installed.
     * @throws UnsupportedOperationException if {@code vpnPackage} exists but does not support being
     *         set as always-on, or if always-on VPN is not available.
     * @see #setAlwaysOnVpnPackage(ComponentName, String, boolean, Set)
     */
    public void setAlwaysOnVpnPackage(@NonNull ComponentName admin, @Nullable String vpnPackage,
            boolean lockdownEnabled) throws NameNotFoundException {
        setAlwaysOnVpnPackage(admin, vpnPackage, lockdownEnabled, Collections.emptySet());
    }

    /**
     * A version of {@link #setAlwaysOnVpnPackage(ComponentName, String, boolean)} that allows the
     * admin to specify a set of apps that should be able to access the network directly when VPN
     * is not connected. When VPN connects these apps switch over to VPN if allowed to use that VPN.
     * System apps can always bypass VPN.
     * <p> Note that the system doesn't update the allowlist when packages are installed or
     * uninstalled, the admin app must call this method to keep the list up to date.
     * <p> When {@code lockdownEnabled} is false {@code lockdownAllowlist} is ignored . When
     * {@code lockdownEnabled} is {@code true} and {@code lockdownAllowlist} is {@code null} or
     * empty, only system apps can bypass VPN.
     * <p> Setting always-on VPN package to {@code null} or using
     * {@link #setAlwaysOnVpnPackage(ComponentName, String, boolean)} clears lockdown allowlist.
     *
     * @param vpnPackage package name for an installed VPN app on the device, or {@code null}
     *         to remove an existing always-on VPN configuration
     * @param lockdownEnabled {@code true} to disallow networking when the VPN is not connected or
     *         {@code false} otherwise. This has no effect when clearing.
     * @param lockdownAllowlist Packages that will be able to access the network directly when VPN
     *         is in lockdown mode but not connected. Has no effect when clearing.
     * @throws SecurityException if {@code admin} is not a device or a profile
     *         owner.
     * @throws NameNotFoundException if {@code vpnPackage} or one of
     *         {@code lockdownAllowlist} is not installed.
     * @throws UnsupportedOperationException if {@code vpnPackage} exists but does
     *         not support being set as always-on, or if always-on VPN is not
     *         available.
     */
    public void setAlwaysOnVpnPackage(@NonNull ComponentName admin, @Nullable String vpnPackage,
            boolean lockdownEnabled, @Nullable Set<String> lockdownAllowlist)
            throws NameNotFoundException {
        throwIfParentInstance("setAlwaysOnVpnPackage");
        if (mService != null) {
            try {
                mService.setAlwaysOnVpnPackage(admin, vpnPackage, lockdownEnabled,
                        lockdownAllowlist == null ? null : new ArrayList<>(lockdownAllowlist));
            } catch (ServiceSpecificException e) {
                switch (e.errorCode) {
                    case ERROR_VPN_PACKAGE_NOT_FOUND:
                        throw new NameNotFoundException(e.getMessage());
                    default:
                        throw new RuntimeException(
                                "Unknown error setting always-on VPN: " + e.errorCode, e);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by device or profile owner to query whether current always-on VPN is configured in
     * lockdown mode. Returns {@code false} when no always-on configuration is set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     *
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     *
     * @see #setAlwaysOnVpnPackage(ComponentName, String, boolean)
     */
    public boolean isAlwaysOnVpnLockdownEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("isAlwaysOnVpnLockdownEnabled");
        if (mService != null) {
            try {
                // Starting from Android R, the caller can pass the permission check in
                // DevicePolicyManagerService if it holds android.permission.MAINLINE_NETWORK_STACK.
                // Note that the android.permission.MAINLINE_NETWORK_STACK is a signature permission
                // which is used by the NetworkStack mainline module.
                return mService.isAlwaysOnVpnLockdownEnabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns whether the admin has enabled always-on VPN lockdown for the current user.
     *
     * Only callable by the system.
    * @hide
    */
    @UserHandleAware
    public boolean isAlwaysOnVpnLockdownEnabled() {
        throwIfParentInstance("isAlwaysOnVpnLockdownEnabled");
        if (mService != null) {
            try {
                return mService.isAlwaysOnVpnLockdownEnabledForUser(myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device or profile owner to query the set of packages that are allowed to access
     * the network directly when always-on VPN is in lockdown mode but not connected. Returns
     * {@code null} when always-on VPN is not active or not in lockdown mode.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     *
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     *
     * @see #setAlwaysOnVpnPackage(ComponentName, String, boolean, Set)
     */
    public @Nullable Set<String> getAlwaysOnVpnLockdownWhitelist(@NonNull ComponentName admin) {
        throwIfParentInstance("getAlwaysOnVpnLockdownWhitelist");
        if (mService != null) {
            try {
                final List<String> allowlist =
                        mService.getAlwaysOnVpnLockdownAllowlist(admin);
                return allowlist == null ? null : new HashSet<>(allowlist);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a device or profile owner to read the name of the package administering an
     * always-on VPN connection for the current user. If there is no such package, or the always-on
     * VPN is provided by the system instead of by an application, {@code null} will be returned.
     *
     * @return Package name of VPN controller responsible for always-on VPN, or {@code null} if none
     *         is set.
     * @throws SecurityException if {@code admin} is not a device or a profile owner.
     */
    public @Nullable String getAlwaysOnVpnPackage(@NonNull ComponentName admin) {
        throwIfParentInstance("getAlwaysOnVpnPackage");
        if (mService != null) {
            try {
                return mService.getAlwaysOnVpnPackage(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Returns the VPN package name if the admin has enabled always-on VPN on the current user,
     * or {@code null} if none is set.
     *
     * Only callable by the system.
     * @hide
     */
    @UserHandleAware
    public @Nullable String getAlwaysOnVpnPackage() {
        throwIfParentInstance("getAlwaysOnVpnPackage");
        if (mService != null) {
            try {
                return mService.getAlwaysOnVpnPackageForUser(myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by an application that is administering the device to disable all cameras on the
     * device, for this user. After setting this, no applications running as this user will be able
     * to access any cameras on the device.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance,
     * returned by {@link #getParentProfileInstance(ComponentName)}, where the caller must be
     * the profile owner of an organization-owned managed profile.
     * <p>
     * If the caller is device owner, then the restriction will be applied to all users. If
     * called on the parent instance, then the restriction will be applied on the personal profile.
     * <p>
     * The calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_CAMERA} to be able to call this method; if it has
     * not, a security exception will be thrown.
     * <p>
     * <b>Note</b>, this policy type is deprecated for legacy device admins since
     * {@link android.os.Build.VERSION_CODES#Q}. On Android
     * {@link android.os.Build.VERSION_CODES#Q} devices, legacy device admins targeting SDK
     * version {@link android.os.Build.VERSION_CODES#P} or below can still call this API to
     * disable camera, while legacy device admins targeting SDK version
     * {@link android.os.Build.VERSION_CODES#Q} will receive a SecurityException. Starting
     * from Android {@link android.os.Build.VERSION_CODES#R}, requests to disable camera from
     * legacy device admins targeting SDK version {@link android.os.Build.VERSION_CODES#P} or
     * below will be silently ignored.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the camera disabled
     * policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier: userRestriction_no_camera
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with or null if
                     the caller is not a device admin
     * @param disabled Whether or not the camera should be disabled.
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             {@link DeviceAdminInfo#USES_POLICY_DISABLE_CAMERA}.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CAMERA, conditional = true)
    @SupportsCoexistence
    public void setCameraDisabled(@Nullable ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setCameraDisabled(admin, mContext.getPackageName(), disabled,
                        mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine whether or not the device's cameras have been disabled for this user,
     * either by the calling admin, if specified, or all admins.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance,
     * returned by {@link #getParentProfileInstance(ComponentName)}, where the caller must be
     * the profile owner of an organization-owned managed profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to check whether any
     *              admins have disabled the camera
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CAMERA, conditional = true)
    public boolean getCameraDisabled(@Nullable ComponentName admin) {
        return getCameraDisabled(admin, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage
    public boolean getCameraDisabled(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getCameraDisabled(admin, mContext.getPackageName(), userHandle,
                        mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner to request a bugreport.
     * <p>
     * If the device contains secondary users or profiles, they must be affiliated with the device.
     * Otherwise a {@link SecurityException} will be thrown. See {@link #isAffiliatedUser}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return {@code true} if the bugreport collection started successfully, or {@code false} if it
     *         wasn't triggered because a previous bugreport operation is still active (either the
     *         bugreport is still running or waiting for the user to share or decline)
     * @throws SecurityException if {@code admin} is not a device owner, or there is at least one
     *         profile or secondary user that is not affiliated with the device.
     * @see #isAffiliatedUser
     */
    public boolean requestBugreport(@NonNull ComponentName admin) {
        throwIfParentInstance("requestBugreport");
        if (mService != null) {
            try {
                return mService.requestBugreport(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device/profile owner to set whether the screen capture is disabled. Disabling
     * screen capture also prevents the content from being shown on display devices that do not have
     * a secure video output. See {@link android.view.Display#FLAG_SECURE} for more details about
     * secure surfaces and secure displays.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance, returned by
     * {@link #getParentProfileInstance(ComponentName)}, where the calling device admin must be
     * the profile owner of an organization-owned managed profile. If it is not, a security
     * exception will be thrown.
     * <p>
     * If the caller is device owner or called on the parent instance by a profile owner of an
     * organization-owned managed profile, then the restriction will be applied to all users.
     * <p>
     * From version {@link android.os.Build.VERSION_CODES#M} disabling screen capture also blocks
     * assist requests for all activities of the relevant user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param disabled Whether screen capture is disabled or not.
     * @throws SecurityException if the caller is not permitted to control screen capture policy.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_SCREEN_CAPTURE, conditional = true)
    public void setScreenCaptureDisabled(@Nullable ComponentName admin, boolean disabled) {
        if (mService != null) {
            try {
                mService.setScreenCaptureDisabled(
                        admin, mContext.getPackageName(), disabled, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine whether or not screen capture has been disabled by the calling
     * admin, if specified, or all admins.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance,
     * returned by {@link #getParentProfileInstance(ComponentName)}, where the caller must be
     * the profile owner of an organization-owned managed profile (the calling admin must be
     * specified).
     *
     * @param admin The name of the admin component to check, or {@code null} to check whether any
     *              admins have disabled screen capture.
     */
    public boolean getScreenCaptureDisabled(@Nullable ComponentName admin) {
        return getScreenCaptureDisabled(admin, myUserId());
    }

    /** @hide per-user version */
    public boolean getScreenCaptureDisabled(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getScreenCaptureDisabled(admin, userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device/profile owner to set nearby notification streaming policy. Notification
     * streaming is sending notification data from pre-installed apps to nearby devices.
     *
     * @param policy One of the {@code NearbyStreamingPolicy} constants.
     * @throws SecurityException if caller is not a device or profile owner
     */
    public void setNearbyNotificationStreamingPolicy(@NearbyStreamingPolicy int policy) {
        throwIfParentInstance("setNearbyNotificationStreamingPolicy");
        if (mService == null) {
            return;
        }
        try {
            mService.setNearbyNotificationStreamingPolicy(policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current runtime nearby notification streaming policy set by the device or profile
     * owner.
     * <p>
     * The caller must be the target user's device owner/profile owner or hold the
     * {@link android.Manifest.permission#READ_NEARBY_STREAMING_POLICY READ_NEARBY_STREAMING_POLICY}
     * permission.
     */
    @RequiresPermission(
            value = android.Manifest.permission.READ_NEARBY_STREAMING_POLICY,
            conditional = true)
    public @NearbyStreamingPolicy int getNearbyNotificationStreamingPolicy() {
        return getNearbyNotificationStreamingPolicy(myUserId());
    }

    /** @hide per-user version */
    public @NearbyStreamingPolicy int getNearbyNotificationStreamingPolicy(int userId) {
        throwIfParentInstance("getNearbyNotificationStreamingPolicy");
        if (mService == null) {
            return NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY;
        }
        try {
            return mService.getNearbyNotificationStreamingPolicy(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device/profile owner to set nearby app streaming policy. App streaming is when
     * the device starts an app on a virtual display and sends a video stream of the app to nearby
     * devices.
     *
     * @param policy One of the {@code NearbyStreamingPolicy} constants.
     * @throws SecurityException if caller is not a device or profile owner.
     */
    public void setNearbyAppStreamingPolicy(@NearbyStreamingPolicy int policy) {
        throwIfParentInstance("setNearbyAppStreamingPolicy");
        if (mService == null) {
            return;
        }
        try {
            mService.setNearbyAppStreamingPolicy(policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current runtime nearby app streaming policy set by the device or profile owner.
     * <p>
     * The caller must be the target user's device owner/profile owner or hold the
     * {@link android.Manifest.permission#READ_NEARBY_STREAMING_POLICY READ_NEARBY_STREAMING_POLICY}
     * permission.
     */
    @RequiresPermission(
            value = android.Manifest.permission.READ_NEARBY_STREAMING_POLICY,
            conditional = true)
    public @NearbyStreamingPolicy int getNearbyAppStreamingPolicy() {
        return getNearbyAppStreamingPolicy(myUserId());
    }

    /** @hide per-user version */
    public @NearbyStreamingPolicy int getNearbyAppStreamingPolicy(int userId) {
        throwIfParentInstance("getNearbyAppStreamingPolicy");
        if (mService == null) {
            return NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY;
        }
        try {
            return mService.getNearbyAppStreamingPolicy(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner, or alternatively a profile owner from Android 8.0 (API level 26) or
     * higher, to set whether auto time is required. If auto time is required, no user will be able
     * set the date and time and network date and time will be used.
     * <p>
     * Note: If auto time is required the user can still manually set the time zone. Staring from
     * Android 11, if auto time is required, the user cannot manually set the time zone.
     * <p>
     * The calling device admin must be a device owner, or alternatively a profile owner from
     * Android 8.0 (API level 26) or higher. If it is not, a security exception will be thrown.
     * <p>
     * Staring from Android 11, this API switches to use
     * {@link UserManager#DISALLOW_CONFIG_DATE_TIME} to enforce the auto time settings. Calling
     * this API to enforce auto time will result in
     * {@link UserManager#DISALLOW_CONFIG_DATE_TIME} being set, while calling this API to lift
     * the requirement will result in {@link UserManager#DISALLOW_CONFIG_DATE_TIME} being cleared.
     * From Android 11, this API can also no longer be called on a managed profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param required Whether auto time is set required or not.
     * @throws SecurityException if {@code admin} is not a device owner, not a profile owner or
     * if this API is called on a managed profile.
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}. Use {@link #setAutoTimeEnabled}
     * to turn auto time on or off and use {@link UserManager#DISALLOW_CONFIG_DATE_TIME}
     * to prevent the user from changing this setting.
     */
    @Deprecated
    public void setAutoTimeRequired(@NonNull ComponentName admin, boolean required) {
        throwIfParentInstance("setAutoTimeRequired");
        if (mService != null) {
            try {
                mService.setAutoTimeRequired(admin, required);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @return true if auto time is required.
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}. Use {@link #getAutoTimeEnabled}
     */
    @Deprecated
    public boolean getAutoTimeRequired() {
        throwIfParentInstance("getAutoTimeRequired");
        if (mService != null) {
            try {
                return mService.getAutoTimeRequired();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner, a profile owner for the primary user or a profile
     * owner of an organization-owned managed profile to turn auto time on and off.
     * Callers are recommended to use {@link UserManager#DISALLOW_CONFIG_DATE_TIME}
     * to prevent the user from changing this setting.
     * <p>
     * If user restriction {@link UserManager#DISALLOW_CONFIG_DATE_TIME} is used,
     * no user will be able set the date and time. Instead, the network date
     * and time will be used.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param enabled Whether time should be obtained automatically from the network or not.
     * @throws SecurityException if caller is not a device owner, a profile owner for the
     * primary user, or a profile owner of an organization-owned managed profile.
     */
    @RequiresPermission(value = SET_TIME, conditional = true)
    public void setAutoTimeEnabled(@Nullable ComponentName admin, boolean enabled) {
        throwIfParentInstance("setAutoTimeEnabled");
        if (mService != null) {
            try {
                mService.setAutoTimeEnabled(admin, mContext.getPackageName(), enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns true if auto time is enabled on the device.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @return true if auto time is enabled on the device.
     * @throws SecurityException if caller is not a device owner, a profile owner for the
     * primary user, or a profile owner of an organization-owned managed profile.
     */
    @RequiresPermission(anyOf = {SET_TIME, QUERY_ADMIN_POLICY}, conditional = true)
    public boolean getAutoTimeEnabled(@Nullable ComponentName admin) {
        throwIfParentInstance("getAutoTimeEnabled");
        if (mService != null) {
            try {
                return mService.getAutoTimeEnabled(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner, a profile owner for the primary user or a profile
     * owner of an organization-owned managed profile to turn auto time zone on and off.
     * Callers are recommended to use {@link UserManager#DISALLOW_CONFIG_DATE_TIME}
     * to prevent the user from changing this setting.
     * <p>
     * If user restriction {@link UserManager#DISALLOW_CONFIG_DATE_TIME} is used,
     * no user will be able set the date and time zone. Instead, the network date
     * and time zone will be used.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with or Null if the
     *              caller is not a device admin.
     * @param enabled Whether time zone should be obtained automatically from the network or not.
     * @throws SecurityException if caller is not a device owner, a profile owner for the
     * primary user, or a profile owner of an organization-owned managed profile.
     */
    @SupportsCoexistence
    @RequiresPermission(value = SET_TIME_ZONE, conditional = true)
    public void setAutoTimeZoneEnabled(@Nullable ComponentName admin, boolean enabled) {
        throwIfParentInstance("setAutoTimeZone");
        if (mService != null) {
            try {
                mService.setAutoTimeZoneEnabled(admin, mContext.getPackageName(), enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns true if auto time zone is enabled on the device.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @return true if auto time zone is enabled on the device.
     * @throws SecurityException if caller is not a device owner, a profile owner for the
     * primary user, or a profile owner of an organization-owned managed profile.
     */
    @RequiresPermission(anyOf = {SET_TIME_ZONE, QUERY_ADMIN_POLICY}, conditional = true)
    public boolean getAutoTimeZoneEnabled(@Nullable ComponentName admin) {
        throwIfParentInstance("getAutoTimeZone");
        if (mService != null) {
            try {
                return mService.getAutoTimeZoneEnabled(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * TODO (b/137101239): remove this method in follow-up CL
     * since it's only used for split system user.
     * Called by a device owner to set whether all users created on the device should be ephemeral.
     * @hide
     */
    public void setForceEphemeralUsers(
            @NonNull ComponentName admin, boolean forceEphemeralUsers) {
        throwIfParentInstance("setForceEphemeralUsers");
        if (mService != null) {
            try {
                mService.setForceEphemeralUsers(admin, forceEphemeralUsers);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * TODO (b/137101239): remove this method in follow-up CL
     * since it's only used for split system user.
     * @return true if all users are created ephemeral.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @hide
     */
    public boolean getForceEphemeralUsers(@NonNull ComponentName admin) {
        throwIfParentInstance("getForceEphemeralUsers");
        if (mService != null) {
            try {
                return mService.getForceEphemeralUsers(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by an application that is administering the device to disable keyguard customizations,
     * such as widgets. After setting this, keyguard features will be disabled according to the
     * provided feature list.
     * <p>
     * A calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call this method;
     * if it has not, a security exception will be thrown.
     * <p>
     * Calling this from a managed profile before version {@link android.os.Build.VERSION_CODES#M}
     * will throw a security exception. From version {@link android.os.Build.VERSION_CODES#M} the
     * profile owner of a managed profile can set:
     * <ul>
     * <li>{@link #KEYGUARD_DISABLE_TRUST_AGENTS}, which affects the parent user, but only if there
     * is no separate challenge set on the managed profile.
     * <li>{@link #KEYGUARD_DISABLE_FINGERPRINT}, {@link #KEYGUARD_DISABLE_FACE} or
     * {@link #KEYGUARD_DISABLE_IRIS} which affects the managed profile challenge if
     * there is one, or the parent user otherwise.
     * <li>{@link #KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS} which affects notifications generated
     * by applications in the managed profile.
     * </ul>
     * <p>
     * From version {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}, the profile owner of a
     * managed profile can also set {@link #KEYGUARD_DISABLE_WIDGETS_ALL} which disables keyguard
     * widgets for the managed profile.
     * <p>
     * From version {@link android.os.Build.VERSION_CODES#R} the profile owner of an
     * organization-owned managed profile can set:
     * <ul>
     * <li>{@link #KEYGUARD_DISABLE_SECURE_CAMERA} which affects the parent user when called on the
     * parent profile.
     * <li>{@link #KEYGUARD_DISABLE_SECURE_NOTIFICATIONS} which affects the parent user when called
     * on the parent profile.
     * </ul>
     * Starting from version {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} the profile
     * owner of an organization-owned managed profile can set:
     * <ul>
     * <li>{@link #KEYGUARD_DISABLE_WIDGETS_ALL} which affects the parent user when called on the
     * parent profile.
     * </ul>
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS}, {@link #KEYGUARD_DISABLE_FINGERPRINT},
     * {@link #KEYGUARD_DISABLE_FACE}, {@link #KEYGUARD_DISABLE_IRIS},
     * {@link #KEYGUARD_DISABLE_SECURE_CAMERA} and {@link #KEYGUARD_DISABLE_SECURE_NOTIFICATIONS}
     * can also be set on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set restrictions on the parent
     * profile. {@link #KEYGUARD_DISABLE_SECURE_CAMERA} can only be set on the parent profile
     * instance if the calling device admin is the profile owner of an organization-owned
     * managed profile.
     * <p>
     * Requests to disable other features on a managed profile will be ignored.
     * <p>
     * The admin can check which features have been disabled by calling
     * {@link #getKeyguardDisabledFeatures(ComponentName)}
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin
     * @param which The disabled features flag which can be either
     *            {@link #KEYGUARD_DISABLE_FEATURES_NONE} (default),
     *            {@link #KEYGUARD_DISABLE_FEATURES_ALL}, or a combination of
     *            {@link #KEYGUARD_DISABLE_WIDGETS_ALL}, {@link #KEYGUARD_DISABLE_SECURE_CAMERA},
     *            {@link #KEYGUARD_DISABLE_SECURE_NOTIFICATIONS},
     *            {@link #KEYGUARD_DISABLE_TRUST_AGENTS},
     *            {@link #KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS},
     *            {@link #KEYGUARD_DISABLE_FINGERPRINT},
     *            {@link #KEYGUARD_DISABLE_FACE},
     *            {@link #KEYGUARD_DISABLE_IRIS},
     *            {@link #KEYGUARD_DISABLE_SHORTCUTS_ALL}.
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES}
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_KEYGUARD, conditional = true)
    public void setKeyguardDisabledFeatures(@Nullable ComponentName admin, int which) {
        if (mService != null) {
            try {
                mService.setKeyguardDisabledFeatures(
                        admin, mContext.getPackageName(), which, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Determine whether or not features have been disabled in keyguard either by the calling
     * admin, if specified, or all admins that set restrictions on this user and its participating
     * profiles. Restrictions on profiles that have a separate challenge are not taken into account.
     *
     * <p>This method can be called on the {@link DevicePolicyManager} instance
     * returned by {@link #getParentProfileInstance(ComponentName)} in order to retrieve
     * restrictions on the parent profile.
     *
     * @param admin The name of the admin component to check, or {@code null} to check whether any
     * admins have disabled features in keyguard.
     * @return bitfield of flags. See {@link #setKeyguardDisabledFeatures(ComponentName, int)}
     * for a list.
     */
    public int getKeyguardDisabledFeatures(@Nullable ComponentName admin) {
        return getKeyguardDisabledFeatures(admin, myUserId());
    }

    private IpcDataCache<Pair<ComponentName, Integer>, Integer> mGetKeyGuardDisabledFeaturesCache =
            new IpcDataCache<>(sDpmCaches.child("getKeyguardDisabledFeatures"),
                    (query) -> getService().getKeyguardDisabledFeatures(
                            query.first, query.second, isParentInstance()));

    /** @hide per-user version */
    @UnsupportedAppUsage
    public int getKeyguardDisabledFeatures(@Nullable ComponentName admin, int userHandle) {
        if (mService != null) {
            return mGetKeyGuardDisabledFeaturesCache.query(new Pair<>(admin, userHandle));
        } else {
            return KEYGUARD_DISABLE_FEATURES_NONE;
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setActiveAdmin(@NonNull ComponentName policyReceiver, boolean refreshing) {
        setActiveAdmin(policyReceiver, refreshing, myUserId());
    }

    /**
     * @hide
     */
    @TestApi
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresPermission(allOf = {
            MANAGE_DEVICE_ADMINS,
            INTERACT_ACROSS_USERS_FULL
    })
    public void setActiveAdmin(@NonNull ComponentName policyReceiver, boolean refreshing,
            int userHandle) {
        setActiveAdminInternal(policyReceiver, refreshing, userHandle, null);
    }

    /**
     * @hide
     */
    @TestApi
    @RequiresPermission(allOf = {
            MANAGE_DEVICE_ADMINS,
            INTERACT_ACROSS_USERS_FULL
    })
    @FlaggedApi(Flags.FLAG_PROVISIONING_CONTEXT_PARAMETER)
    public void setActiveAdmin(
            @NonNull ComponentName policyReceiver,
            boolean refreshing,
            int userHandle,
            @Nullable String provisioningContext
    ) {
        setActiveAdminInternal(policyReceiver, refreshing, userHandle, provisioningContext);
    }

    private void setActiveAdminInternal(
            @NonNull ComponentName policyReceiver,
            boolean refreshing,
            int userHandle,
            @Nullable String provisioningContext
    ) {
        if (mService != null) {
            try {
                mService.setActiveAdmin(
                        policyReceiver,
                        refreshing,
                        userHandle,
                        provisioningContext
                );
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a privileged caller holding {@code BIND_DEVICE_ADMIN} permission to retrieve
     * the remove warning for the given device admin.
     * @hide
     */
    public void getRemoveWarning(@Nullable ComponentName admin, RemoteCallback result) {
        if (mService != null) {
            try {
                mService.getRemoveWarning(admin, result, myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void reportPasswordChanged(PasswordMetrics metrics, @UserIdInt int userId) {
        if (mService != null) {
            try {
                mService.reportPasswordChanged(metrics, userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void reportFailedPasswordAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportFailedPasswordAttempt(userHandle, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void reportSuccessfulPasswordAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportSuccessfulPasswordAttempt(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void reportFailedBiometricAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportFailedBiometricAttempt(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public void reportSuccessfulBiometricAttempt(int userHandle) {
        if (mService != null) {
            try {
                mService.reportSuccessfulBiometricAttempt(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Should be called when keyguard has been dismissed.
     * @hide
     */
    public void reportKeyguardDismissed(int userHandle) {
        if (mService != null) {
            try {
                mService.reportKeyguardDismissed(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Should be called when keyguard view has been shown to the user.
     * @hide
     */
    public void reportKeyguardSecured(int userHandle) {
        if (mService != null) {
            try {
                mService.reportKeyguardSecured(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sets the given package as the device owner.
     *
     * <p>Preconditions:
     * <ul>
     *   <li>The package must already be installed.
     *   <li>There must not already be a device owner.
     *   <li>Only apps with the {@code MANAGE_PROFILE_AND_DEVICE_OWNERS} permission or the
     *       {@link Process#SHELL_UID Shell UID} can call this method.
     * </ul>
     *
     * <p>Calling this after the setup phase of the device owner user has completed is allowed only
     * if the caller is the {@link Process#SHELL_UID Shell UID}, and there are no additional users
     * (except when the device runs on headless system user mode, in which case it could have exact
     * one extra user, which is the current user.
     *
     * <p>On a headless devices, if it is in affiliated mode the device owner will be set in the
     * {@link UserHandle#SYSTEM system} user. If the device is in single user mode, the device owner
     * will be set in the first secondary user.
     *
     * @param who the component name to be registered as device owner.
     * @param userId ID of the user on which the device owner runs.
     *
     * @return whether the package was successfully registered as the device owner.
     *
     * @throws IllegalArgumentException if the package name is {@code null} or invalid.
     * @throws IllegalStateException If the preconditions mentioned are not met.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public boolean setDeviceOwner(@NonNull ComponentName who, @UserIdInt int userId) {
        if (mService != null) {
            try {
                return mService.setDeviceOwner(who, userId,
                        /* setProfileOwnerOnCurrentUserIfNecessary= */ true);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Same as {@link #setDeviceOwner(ComponentName, int)}, but without setting the profile
     * owner on current user when running on headless system user mode - should be used only by
     * testing infra.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public boolean setDeviceOwnerOnly(
            @NonNull ComponentName who, @UserIdInt int userId) {
        if (mService != null) {
            try {
                return mService.setDeviceOwner(who, userId,
                        /* setProfileOwnerOnCurrentUserIfNecessary= */ false);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * For apps targeting {@link Build.VERSION_CODES#VANILLA_ICE_CREAM} and above, the
     * {@link #isDeviceOwnerApp} method will use the user contained within the
     * context.
     * For apps targeting an SDK version <em>below</em> this, the user of the calling process will
     * be used (Process.myUserHandle()).
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static final long IS_DEVICE_OWNER_USER_AWARE = 307233716L;

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
    @UserHandleAware(enabledSinceTargetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public boolean isDeviceOwnerApp(String packageName) {
        throwIfParentInstance("isDeviceOwnerApp");
        if (android.permission.flags.Flags.systemServerRoleControllerEnabled()
                && CompatChanges.isChangeEnabled(IS_DEVICE_OWNER_USER_AWARE)) {
            return isDeviceOwnerAppOnContextUser(packageName);
        }
        return isDeviceOwnerAppOnCallingUser(packageName);
    }

    /**
     * @return true if a package is registered as device owner, only when it's running on the
     * calling user.
     *
     * <p>Same as {@link #isDeviceOwnerApp}, but bundled code should use it for clarity.
     * @hide
     */
    public boolean isDeviceOwnerAppOnCallingUser(String packageName) {
        return isDeviceOwnerAppOnAnyUserInner(packageName, /* callingUserOnly =*/ true);
    }

    /**
     * @return true if a package is registered as device owner, even if it's running on a different
     * user.
     *
     * <p>Requires the MANAGE_USERS permission.
     *
     * @hide
     */
    public boolean isDeviceOwnerAppOnAnyUser(String packageName) {
        return isDeviceOwnerAppOnAnyUserInner(packageName, /* callingUserOnly =*/ false);
    }

    /**
     * @return device owner component name, only when it's running on the calling user.
     *
     * @hide
     */
    public ComponentName getDeviceOwnerComponentOnCallingUser() {
        return getDeviceOwnerComponentInner(/* callingUserOnly =*/ true);
    }

    /**
     * @return device owner component name, even if it's running on a different user.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS
    })
    public ComponentName getDeviceOwnerComponentOnAnyUser() {
        return getDeviceOwnerComponentInner(/* callingUserOnly =*/ false);
    }

    private boolean isDeviceOwnerAppOnAnyUserInner(String packageName, boolean callingUserOnly) {
        if (packageName == null) {
            return false;
        }
        final ComponentName deviceOwner = getDeviceOwnerComponentInner(callingUserOnly);
        if (deviceOwner == null) {
            return false;
        }
        return packageName.equals(deviceOwner.getPackageName());
    }

    private boolean isDeviceOwnerAppOnContextUser(String packageName) {
        if (packageName == null) {
            return false;
        }
        ComponentName deviceOwner = null;
        if (mService != null) {
            try {
                deviceOwner = mService.getDeviceOwnerComponentOnUser(myUserId());
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        if (deviceOwner == null) {
            return false;
        }
        return packageName.equals(deviceOwner.getPackageName());
    }

    private ComponentName getDeviceOwnerComponentInner(boolean callingUserOnly) {
        if (mService != null) {
            try {
                return mService.getDeviceOwnerComponent(callingUserOnly);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @return Handle of the user who runs device owner, or {@code null} if there's no device owner.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @SystemApi
    public @Nullable UserHandle getDeviceOwnerUser() {
        if (mService != null) {
            try {
                int userId = mService.getDeviceOwnerUserId();

                if (userId != UserHandle.USER_NULL) {
                    return UserHandle.of(userId);
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @hide
     */
    public int getDeviceOwnerUserId() {
        if (mService != null) {
            try {
                return mService.getDeviceOwnerUserId();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * Clears the current device owner. The caller must be the device owner. This function should be
     * used cautiously as once it is called it cannot be undone. The device owner can only be set as
     * a part of device setup, before it completes.
     * <p>
     * While some policies previously set by the device owner will be cleared by this method, it is
     * a best-effort process and some other policies will still remain in place after the device
     * owner is cleared.
     *
     * @param packageName The package name of the device owner.
     * @throws SecurityException if the caller is not in {@code packageName} or {@code packageName}
     *             does not own the current device owner component.
     *
     * @deprecated This method is expected to be used for testing purposes only. The device owner
     * will lose control of the device and its data after calling it. In order to protect any
     * sensitive data that remains on the device, it is advised that the device owner factory resets
     * the device instead of calling this method. See {@link #wipeData(int)}.
     */
    @Deprecated
    public void clearDeviceOwnerApp(String packageName) {
        throwIfParentInstance("clearDeviceOwnerApp");
        if (mService != null) {
            try {
                mService.clearDeviceOwner(packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the device owner package name, only if it's running on the calling user.
     *
     * <p>Bundled components should use {@code getDeviceOwnerComponentOnCallingUser()} for clarity.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public @Nullable String getDeviceOwner() {
        throwIfParentInstance("getDeviceOwner");
        final ComponentName name = getDeviceOwnerComponentOnCallingUser();
        return name != null ? name.getPackageName() : null;
    }

    private IpcDataCache<Void, Boolean> mHasDeviceOwnerCache =
            new IpcDataCache<>(sDpmCaches.child("hasDeviceOwner"),
                    (query) -> getService().hasDeviceOwner());

    /**
     * Called by the system to find out whether the device is managed by a Device Owner.
     *
     * @return whether the device is managed by a Device Owner.
     * @throws SecurityException if the caller is not the device owner, does not hold
     *         MANAGE_USERS or MANAGE_PROFILE_AND_DEVICE_OWNERS permissions and is not the system.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("RequiresPermission")
    public boolean isDeviceManaged() {
        return mHasDeviceOwnerCache.query(null);
    }

    /**
     * Returns the device owner name.  Note this method *will* return the device owner
     * name when it's running on a different user.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS
    })
    public String getDeviceOwnerNameOnAnyUser() {
        throwIfParentInstance("getDeviceOwnerNameOnAnyUser");
        if (mService != null) {
            try {
                return mService.getDeviceOwnerName();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
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
    @Deprecated
    @SystemApi
    @RequiresPermission(MANAGE_DEVICE_ADMINS)
    public boolean setActiveProfileOwner(@NonNull ComponentName admin, String ownerName)
            throws IllegalArgumentException {
        throwIfParentInstance("setActiveProfileOwner");
        if (mService != null) {
            try {
                final int myUserId = myUserId();
                mService.setActiveAdmin(admin, false, myUserId, null);
                return mService.setProfileOwner(admin, myUserId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Clears the active profile owner. The caller must be the profile owner of this user, otherwise
     * a SecurityException will be thrown. This method is not available to managed profile owners.
     * <p>
     * While some policies previously set by the profile owner will be cleared by this method, it is
     * a best-effort process and some other policies will still remain in place after the profile
     * owner is cleared.
     *
     * @param admin The component to remove as the profile owner.
     * @throws SecurityException if {@code admin} is not an active profile owner, or the method is
     * being called from a managed profile.
     *
     * @deprecated This method is expected to be used for testing purposes only. The profile owner
     * will lose control of the user and its data after calling it. In order to protect any
     * sensitive data that remains on this user, it is advised that the profile owner deletes it
     * instead of calling this method. See {@link #wipeData(int)}.
     */
    @Deprecated
    public void clearProfileOwner(@NonNull ComponentName admin) {
        throwIfParentInstance("clearProfileOwner");
        if (mService != null) {
            try {
                mService.clearProfileOwner(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
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
                throw re.rethrowFromSystemServer();
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
     * @param userHandle the userId to set the profile owner for.
     * @return whether the component was successfully registered as the profile owner.
     * @throws IllegalArgumentException if admin is null, the package isn't installed, or the
     * preconditions mentioned are not met.
     */
    public boolean setProfileOwner(@NonNull ComponentName admin, int userHandle)
            throws IllegalArgumentException {
        if (mService != null) {
            try {
                return mService.setProfileOwner(admin, userHandle);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets the device owner information to be shown on the lock screen.
     * <p>
     * Device owner information set using this method overrides any owner information manually set
     * by the user and prevents the user from further changing it.
     * <p>
     * If the device owner information is {@code null} or empty then the device owner info is
     * cleared and the user owner info is shown on the lock screen if it is set.
     * <p>
     * If the device owner information contains only whitespaces then the message on the lock screen
     * will be blank and the user will not be allowed to change it.
     * <p>
     * If the device owner information needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this string accordingly.
     * <p>
     * May be called by the device owner or the profile owner of an organization-owned device.
     *
     * @param admin The name of the admin component to check.
     * @param info Device owner information which will be displayed instead of the user owner info.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setDeviceOwnerLockScreenInfo(@NonNull ComponentName admin, CharSequence info) {
        throwIfParentInstance("setDeviceOwnerLockScreenInfo");
        if (mService != null) {
            try {
                mService.setDeviceOwnerLockScreenInfo(admin, info);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @return The device owner information. If it is not set returns {@code null}.
     */
    public CharSequence getDeviceOwnerLockScreenInfo() {
        throwIfParentInstance("getDeviceOwnerLockScreenInfo");
        if (mService != null) {
            try {
                return mService.getDeviceOwnerLockScreenInfo();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by device or profile owners to suspend packages for this user. This function can be
     * called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_PACKAGE_ACCESS} scope via {@link #setDelegatedScopes}.
     * <p>
     * A suspended package will not be able to start activities. Its notifications will be hidden,
     * it will not show up in recents, will not be able to show toasts or dialogs or ring the
     * device.
     * <p>
     * The package must already be installed. If the package is uninstalled while suspended the
     * package will no longer be suspended. The admin can block this by using
     * {@link #setUninstallBlocked}.
     *
     * <p>Some apps cannot be suspended, such as device admins, the active launcher, the required
     * package installer, the required package uninstaller, the required package verifier, the
     * default dialer, and the permission controller.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param packageNames The package names to suspend or unsuspend.
     * @param suspended If set to {@code true} than the packages will be suspended, if set to
     *            {@code false} the packages will be unsuspended.
     * @return an array of package names for which the suspended status is not set as requested in
     *         this method.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_PACKAGE_STATE, conditional = true)
    @NonNull
    public String[] setPackagesSuspended(@Nullable ComponentName admin,
            @NonNull String[] packageNames, boolean suspended) {
        throwIfParentInstance("setPackagesSuspended");
        if (mService != null) {
            try {
                return mService.setPackagesSuspended(admin, mContext.getPackageName(), packageNames,
                        suspended);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return packageNames;
    }

    /**
     * Determine if a package is suspended. This function can be called by a device owner, profile
     * owner, or by a delegate given the {@link #DELEGATION_PACKAGE_ACCESS} scope via
     * {@link #setDelegatedScopes} or by holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PACKAGE_STATE}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param packageName The name of the package to retrieve the suspended status of.
     * @return {@code true} if the package is suspended or {@code false} if the package is not
     *         suspended, could not be found or an error occurred.
     * @throws SecurityException if {@code admin} is not a device or profile owner or has not been
     * granted the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PACKAGE_STATE}.
     * @throws NameNotFoundException if the package could not be found.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_PACKAGE_STATE, conditional = true)
    public boolean isPackageSuspended(@Nullable ComponentName admin, String packageName)
            throws NameNotFoundException {
        throwIfParentInstance("isPackageSuspended");
        if (mService != null) {
            try {
                return mService.isPackageSuspended(admin, mContext.getPackageName(), packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (IllegalArgumentException ex) {
                Log.e(TAG, "IllegalArgumentException checking isPackageSuspended", ex);
                throw new NameNotFoundException(packageName);
            }
        }
        return false;
    }

    /**
     * Sets the enabled state of the profile. A profile should be enabled only once it is ready to
     * be used. Only the profile owner can call this.
     *
     * @see #isProfileOwnerApp
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public void setProfileEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("setProfileEnabled");
        if (mService != null) {
            try {
                mService.setProfileEnabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sets the name of the profile. In the device owner case it sets the name of the user which it
     * is called from. Only a profile owner or device owner can call this. If this is never called
     * by the profile or device owner, the name will be set to default values.
     *
     * @see #isProfileOwnerApp
     * @see #isDeviceOwnerApp
     * @param admin Which {@link DeviceAdminReceiver} this request is associate with.
     * @param profileName The name of the profile. If the name is longer than 200 characters
     *                    it will be truncated.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setProfileName(@NonNull ComponentName admin, String profileName) {
        throwIfParentInstance("setProfileName");
        if (mService != null) {
            try {
                mService.setProfileName(admin, profileName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Used to determine if a particular package is registered as the profile owner for the
     * user. A profile owner is a special device admin that has additional privileges
     * within the profile.
     *
     * @param packageName The package name of the app to compare with the registered profile owner.
     * @return Whether or not the package is registered as the profile owner.
     */
    @UserHandleAware
    public boolean isProfileOwnerApp(String packageName) {
        throwIfParentInstance("isProfileOwnerApp");
        if (mService != null) {
            try {
                ComponentName profileOwner = mService.getProfileOwnerAsUser(myUserId());
                return profileOwner != null
                        && profileOwner.getPackageName().equals(packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
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
    public @Nullable ComponentName getProfileOwner() throws IllegalArgumentException {
        throwIfParentInstance("getProfileOwner");
        return getProfileOwnerAsUser(mContext.getUserId());
    }

    /**
     * @see #getProfileOwner()
     * @hide
     */
    @RequiresPermission(value = android.Manifest.permission.INTERACT_ACROSS_USERS,
            conditional = true)
    public @Nullable ComponentName getProfileOwnerAsUser(@NonNull UserHandle user) {
        if (mService != null) {
            try {
                return mService.getProfileOwnerAsUser(user.getIdentifier());
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @Nullable ComponentName getProfileOwnerAsUser(final int userId) {
        if (mService != null) {
            try {
                return mService.getProfileOwnerAsUser(userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    private final IpcDataCache<UserHandle, ComponentName>
            mGetProfileOwnerOrDeviceOwnerSupervisionComponentCache =
            new IpcDataCache<>(sDpmCaches.child("getProfileOwnerOrDeviceOwnerSupervisionComponent"),
                    (arg) -> getService().getProfileOwnerOrDeviceOwnerSupervisionComponent(arg));

    /**
     * Returns the configured supervision app if it exists and is the device owner or policy owner.
     * @hide
     */
    public @Nullable ComponentName getProfileOwnerOrDeviceOwnerSupervisionComponent(
            @NonNull UserHandle user) {
        if (mService != null) {
            return mGetProfileOwnerOrDeviceOwnerSupervisionComponentCache.query(user);
        }
        return null;
    }

    /**
     * Checks if the specified component is the supervision component.
     * @hide
     */
    public boolean isSupervisionComponent(@NonNull ComponentName who) {
        if (mService != null) {
            try {
                return getService().isSupervisionComponent(who);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * @hide
     * @return the human readable name of the organisation associated with this DPM or {@code null}
     *         if one is not set.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    public @Nullable String getProfileOwnerName() throws IllegalArgumentException {
        if (mService != null) {
            try {
                return mService.getProfileOwnerName(mContext.getUserId());
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * @hide
     * @param userId The user for whom to fetch the profile owner name, if any.
     * @return the human readable name of the organisation associated with this profile owner or
     *         null if one is not set.
     * @throws IllegalArgumentException if the userId is invalid.
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS
    })
    public @Nullable String getProfileOwnerNameAsUser(int userId) throws IllegalArgumentException {
        throwIfParentInstance("getProfileOwnerNameAsUser");
        if (mService != null) {
            try {
                return mService.getProfileOwnerName(userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    private final IpcDataCache<Void, Boolean> mIsOrganizationOwnedDeviceWithManagedProfileCache =
            new IpcDataCache(sDpmCaches.child("isOrganizationOwnedDeviceWithManagedProfile"),
                    (query) -> getService().isOrganizationOwnedDeviceWithManagedProfile());

    /**
     * Apps can use this method to find out if the device was provisioned as
     * organization-owend device with a managed profile.
     *
     * This, together with checking whether the device has a device owner (by calling
     * {@link #isDeviceOwnerApp}), could be used to learn whether the device is owned by an
     * organization or an individual:
     * If this method returns true OR {@link #isDeviceOwnerApp} returns true (for any package),
     * then the device is owned by an organization. Otherwise, it's owned by an individual.
     *
     * @return {@code true} if the device was provisioned as organization-owned device,
     * {@code false} otherwise.
     */
    public boolean isOrganizationOwnedDeviceWithManagedProfile() {
        throwIfParentInstance("isOrganizationOwnedDeviceWithManagedProfile");
        if (mService != null) {
            return mIsOrganizationOwnedDeviceWithManagedProfileCache.query(null);
        }
        return false;
    }

    /**
     * Returns whether the specified package can read the device identifiers.
     *
     * @param packageName The package name of the app to check for device identifier access.
     * @param pid The process id of the package to be checked.
     * @param uid The uid of the package to be checked.
     * @return whether the package can read the device identifiers.
     *
     * @hide
     */
    public boolean hasDeviceIdentifierAccess(@NonNull String packageName, int pid, int uid) {
        throwIfParentInstance("hasDeviceIdentifierAccess");
        if (packageName == null) {
            return false;
        }
        if (mService != null) {
            try {
                return mService.checkDeviceIdentifierAccess(packageName, pid, uid);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a profile owner or device owner or holder of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK}. to set a default activity
     * that the system selects to handle intents that match the given {@link IntentFilter} instead
     * of showing the default disambiguation mechanism.
     * This activity will remain the default intent handler even if the set of potential event
     * handlers for the intent filter changes and if the intent preferences are reset.
     * <p>
     * Note that the target application should still declare the activity in the manifest, the API
     * just sets the activity to be the default one to handle the given intent filter.
     * <p>
     * The default disambiguation mechanism takes over if the activity is not installed (anymore).
     * When the activity is (re)installed, it is automatically reset as default intent handler for
     * the filter.
     * <p>
     * Note that calling this API to set a default intent handler, only allow to avoid the default
     * disambiguation mechanism. Implicit intents that do not trigger this mechanism (like invoking
     * the browser) cannot be configured as they are controlled by other configurations.
     * <p>
     * The calling device admin must be a profile owner or device owner. If it is not, a security
     * exception will be thrown.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the persistent preferred
     * activity policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier
     * {@link DevicePolicyIdentifiers#PERSISTENT_PREFERRED_ACTIVITY_POLICY}
     * <li> The additional policy params bundle, which contains
     * {@link PolicyUpdateReceiver#EXTRA_INTENT_FILTER} the intent filter the policy applies to
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * <p>NOTE: Performs disk I/O and shouldn't be called on the main thread.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *        caller is not a device admin.
     * @param filter The IntentFilter for which a default handler is added.
     * @param activity The Activity that is added as default intent handler.
     * @throws SecurityException if {@code admin} is not a device or profile owner or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK}.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_TASK, conditional = true)
    @SupportsCoexistence
    public void addPersistentPreferredActivity(@Nullable ComponentName admin, IntentFilter filter,
            @NonNull ComponentName activity) {
        throwIfParentInstance("addPersistentPreferredActivity");
        if (mService != null) {
            try {
                mService.addPersistentPreferredActivity(admin, mContext.getPackageName(), filter,
                        activity);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK} to remove all
     * persistent intent handler preferences associated with the given package that were set by
     * {@link #addPersistentPreferredActivity}.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the persistent preferred
     * activity policy has been cleared, {@link PolicyUpdateReceiver#onPolicySetResult(Context,
     * String, Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy
     * was successfully cleared or not. This callback will contain:
     * <ul>
     * <li> The policy identifier
     * {@link DevicePolicyIdentifiers#PERSISTENT_PREFERRED_ACTIVITY_POLICY}
     * <li> The additional policy params bundle, which contains
     * {@link PolicyUpdateReceiver#EXTRA_INTENT_FILTER} the intent filter the policy applies to
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully cleared or the
     * reason the policy failed to be cleared
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param packageName The name of the package for which preferences are removed.
     * @throws SecurityException if {@code admin} is not a device or profile owner or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK}.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_TASK, conditional = true)
    @SupportsCoexistence
    public void clearPackagePersistentPreferredActivities(@Nullable ComponentName admin,
            String packageName) {
        throwIfParentInstance("clearPackagePersistentPreferredActivities");
        if (mService != null) {
            try {
                mService.clearPackagePersistentPreferredActivities(
                        admin,
                        mContext.getPackageName(),
                        packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Must be called by a device owner or a profile owner of an organization-owned managed profile
     * to set the default SMS application.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance, returned by
     * {@link #getParentProfileInstance(ComponentName)}, where the caller must be the profile owner
     * of an organization-owned managed profile and the package must be a pre-installed system
     * package. If called on the parent instance, then the default SMS application is set on the
     * personal profile.
     * <p>
     * Starting from Android {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, the profile
     * owner of an organization-owned managed profile can also call this method directly (not on the
     * parent profile instance) to set the default SMS application in the work profile. This is only
     * meaningful when work profile telephony is enabled by {@link #setManagedSubscriptionsPolicy}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param packageName The name of the package to set as the default SMS application.
     * @throws SecurityException        if {@code admin} is not a device or profile owner or if
     *                                  called on the parent profile and the {@code admin} is not a
     *                                  profile owner of an organization-owned managed profile.
     * @throws IllegalArgumentException if called on the parent profile and the package
     *                                  provided is not a pre-installed system package.
     * @throws IllegalStateException while trying to set default sms app on the profile and
     *                             {@link ManagedSubscriptionsPolicy#TYPE_ALL_MANAGED_SUBSCRIPTIONS}
     *                             policy is not set.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_DEFAULT_SMS, conditional = true)
    public void setDefaultSmsApplication(@Nullable ComponentName admin,
            @NonNull String packageName) {
        if (mService != null) {
            try {
                mService.setDefaultSmsApplication(admin, mContext.getPackageName(), packageName,
                        mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Must be called by a device owner or a profile owner of an organization-owned managed profile
     * to set the default dialer application for the calling user.
     * <p>
     * When the profile owner of an organization-owned managed profile calls this method, it sets
     * the default dialer application in the work profile. This is only meaningful when work profile
     * telephony is enabled by {@link #setManagedSubscriptionsPolicy}.
     * <p>
     * If the device does not support telephony ({@link PackageManager#FEATURE_TELEPHONY}), calling
     * this method will do nothing.
     *
     * @param packageName The name of the package to set as the default dialer application.
     * @throws SecurityException        if {@code admin} is not a device or profile owner or a
     *                                  profile owner of an organization-owned managed profile.
     * @throws IllegalArgumentException if the package cannot be set as the default dialer, for
     *                                  example if the package is not installed or does not expose
     *                                  the expected activities or services that a dialer app is
     *                                  required to have.
     */
    public void setDefaultDialerApplication(@NonNull String packageName) {
        throwIfParentInstance("setDefaultDialerApplication");
        if (mService != null) {
            try {
                mService.setDefaultDialerApplication(packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner to grant permission to a package to manage
     * application restrictions for the calling user via {@link #setApplicationRestrictions} and
     * {@link #getApplicationRestrictions}.
     * <p>
     * This permission is persistent until it is later cleared by calling this method with a
     * {@code null} value or uninstalling the managing package.
     * <p>
     * The supplied application restriction managing package must be installed when calling this
     * API, otherwise an {@link NameNotFoundException} will be thrown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package name which will be given access to application restrictions
     *            APIs. If {@code null} is given the current package will be cleared.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @throws NameNotFoundException if {@code packageName} is not found
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #setDelegatedScopes}
     * with the {@link #DELEGATION_APP_RESTRICTIONS} scope instead.
     */
    @Deprecated
    public void setApplicationRestrictionsManagingPackage(@NonNull ComponentName admin,
            @Nullable String packageName) throws NameNotFoundException {
        throwIfParentInstance("setApplicationRestrictionsManagingPackage");
        if (mService != null) {
            try {
                if (!mService.setApplicationRestrictionsManagingPackage(admin, packageName)) {
                    throw new NameNotFoundException(packageName);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner or device owner to retrieve the application restrictions managing
     * package for the current user, or {@code null} if none is set. If there are multiple
     * delegates this function will return one of them.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The package name allowed to manage application restrictions on the current user, or
     *         {@code null} if none is set.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #getDelegatePackages}
     * with the {@link #DELEGATION_APP_RESTRICTIONS} scope instead.
     */
    @Deprecated
    @Nullable
    public String getApplicationRestrictionsManagingPackage(
            @NonNull ComponentName admin) {
        throwIfParentInstance("getApplicationRestrictionsManagingPackage");
        if (mService != null) {
            try {
                return mService.getApplicationRestrictionsManagingPackage(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by any application to find out whether it has been granted permission via
     * {@link #setApplicationRestrictionsManagingPackage} to manage application restrictions
     * for the calling user.
     *
     * <p>This is done by comparing the calling Linux uid with the uid of the package specified by
     * that method.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#O}. Use {@link #getDelegatedScopes}
     * instead.
     */
    @Deprecated
    public boolean isCallerApplicationRestrictionsManagingPackage() {
        throwIfParentInstance("isCallerApplicationRestrictionsManagingPackage");
        if (mService != null) {
            try {
                return mService.isCallerApplicationRestrictionsManagingPackage(
                        mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets the application restrictions for a given target application running in the calling user.
     * <p>
     * The caller must be a profile or device owner on that user, or the package allowed to manage
     * application restrictions via {@link #setDelegatedScopes} with the
     * {@link #DELEGATION_APP_RESTRICTIONS} scope; otherwise a security exception will be thrown.
     * <p>
     * The provided {@link Bundle} consists of key-value pairs, where the types of values may be:
     * <ul>
     * <li>{@code boolean}
     * <li>{@code int}
     * <li>{@code String} or {@code String[]}
     * <li>From {@link android.os.Build.VERSION_CODES#M}, {@code Bundle} or {@code Bundle[]}
     * </ul>
     * <p>
     * If the restrictions are not available yet, but may be applied in the near future, the caller
     * can notify the target application of that by adding
     * {@link UserManager#KEY_RESTRICTIONS_PENDING} to the settings parameter.
     * <p>
     * The application restrictions are only made visible to the target application via
     * {@link UserManager#getApplicationRestrictions(String)}, in addition to the profile or device
     * owner, and the application restrictions managing package via
     * {@link #getApplicationRestrictions}.
     *
     * <p>Starting from Android Version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * multiple admins can set app restrictions for the same application, the target application can
     * get the list of app restrictions set by each admin via
     * {@link android.content.RestrictionsManager#getApplicationRestrictionsPerAdmin}.
     *
     * <p>Starting from Android Version {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM},
     * the device policy management role holder can also set app restrictions on any applications
     * in the calling user, as well as the parent user of an organization-owned managed profile via
     * the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)}. App restrictions set by the device policy
     * management role holder are not returned by
     * {@link UserManager#getApplicationRestrictions(String)}. The target application should use
     * {@link android.content.RestrictionsManager#getApplicationRestrictionsPerAdmin} to retrieve
     * them, alongside any app restrictions the profile or device owner might have set.
     *
     * <p>NOTE: The method performs disk I/O and shouldn't be called on the main thread
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if called by the application restrictions managing package.
     * @param packageName The name of the package to update restricted settings for.
     * @param settings A {@link Bundle} to be parsed by the receiving application, conveying a new
     *            set of active restrictions.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_APP_RESTRICTIONS
     * @see UserManager#KEY_RESTRICTIONS_PENDING
     */
    @WorkerThread
    public void setApplicationRestrictions(@Nullable ComponentName admin, String packageName,
            Bundle settings) {
        if (mService != null) {
            try {
                mService.setApplicationRestrictions(admin, mContext.getPackageName(), packageName,
                        settings, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Sets a list of configuration features to enable for a trust agent component. This is meant to
     * be used in conjunction with {@link #KEYGUARD_DISABLE_TRUST_AGENTS}, which disables all trust
     * agents but those enabled by this function call. If flag
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} is not set, then this call has no effect.
     * <p>
     * For any specific trust agent, whether it is disabled or not depends on the aggregated state
     * of each admin's {@link #KEYGUARD_DISABLE_TRUST_AGENTS} setting and its trust agent
     * configuration as set by this function call. In particular: if any admin sets
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} and does not additionally set any
     * trust agent configuration, the trust agent is disabled completely. Otherwise, the trust agent
     * will receive the list of configurations from all admins who set
     * {@link #KEYGUARD_DISABLE_TRUST_AGENTS} and aggregate the configurations to determine its
     * behavior. The exact meaning of aggregation is trust-agent-specific.
     * <p>
     * A calling device admin must have requested
     * {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES} to be able to call this method;
     * if not, a security exception will be thrown.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to set the configuration for
     * the parent profile.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, calling
     * this method has no effect - no trust agent configuration will be set.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin
     * @param target Component name of the agent to be configured.
     * @param configuration Trust-agent-specific feature configuration bundle. Please consult
     *        documentation of the specific trust agent to determine the interpretation of this
     *        bundle.
     * @throws SecurityException if {@code admin} is not an active administrator or does not use
     *             {@link DeviceAdminInfo#USES_POLICY_DISABLE_KEYGUARD_FEATURES}
     */
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_KEYGUARD, conditional = true)
    public void setTrustAgentConfiguration(@Nullable ComponentName admin,
            @NonNull ComponentName target, PersistableBundle configuration) {
        if (mService != null) {
            try {
                mService.setTrustAgentConfiguration(
                        admin, mContext.getPackageName(), target, configuration, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets configuration for the given trust agent based on aggregating all calls to
     * {@link #setTrustAgentConfiguration(ComponentName, ComponentName, PersistableBundle)} for
     * all device admins.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} in order to retrieve the configuration set
     * on the parent profile.
     * <p>
     * On devices not supporting {@link PackageManager#FEATURE_SECURE_LOCK_SCREEN} feature, null is
     * always returned.
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
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public @Nullable List<PersistableBundle> getTrustAgentConfiguration(
            @Nullable ComponentName admin, @NonNull ComponentName agent) {
        return getTrustAgentConfiguration(admin, agent, myUserId());
    }

    /** @hide per-user version */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @RequiresFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN)
    public @Nullable List<PersistableBundle> getTrustAgentConfiguration(
            @Nullable ComponentName admin, @NonNull ComponentName agent, int userHandle) {
        if (mService != null) {
            try {
                return mService.getTrustAgentConfiguration(admin, agent, userHandle,
                        mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return new ArrayList<PersistableBundle>(); // empty list
    }

    /**
     * Called by a profile owner of a managed profile to set whether caller-Id information from the
     * managed profile will be shown in the parent profile, for incoming calls.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     * <p>
     * Starting with {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, calling this function
     * is similar to calling {@link #setManagedProfileCallerIdAccessPolicy(PackagePolicy)}
     * with a {@link PackagePolicy#PACKAGE_POLICY_BLOCKLIST} policy type when {@code disabled} is
     * false or a {@link PackagePolicy#PACKAGE_POLICY_ALLOWLIST} policy type when
     * {@code disabled} is true.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled If true caller-Id information in the managed profile is not displayed.
     * @throws SecurityException if {@code admin} is not a profile owner.
     * @deprecated starting with {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, use
     * {@link #setManagedProfileCallerIdAccessPolicy(PackagePolicy)} instead
     */
    @Deprecated
    public void setCrossProfileCallerIdDisabled(@NonNull ComponentName admin, boolean disabled) {
        throwIfParentInstance("setCrossProfileCallerIdDisabled");
        if (mService != null) {
            try {
                mService.setCrossProfileCallerIdDisabled(admin, disabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to determine whether or not caller-Id
     * information has been disabled.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     * <p>
     * Starting with {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * this will return true when
     * {@link #setManagedProfileCallerIdAccessPolicy(PackagePolicy)}
     * has been set with a non-null policy whose policy type is NOT
     * {@link PackagePolicy#PACKAGE_POLICY_BLOCKLIST}
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a profile owner.
     * @deprecated starting with {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, use
     * {@link #getManagedProfileCallerIdAccessPolicy()} instead
     */
    @Deprecated
    public boolean getCrossProfileCallerIdDisabled(@NonNull ComponentName admin) {
        throwIfParentInstance("getCrossProfileCallerIdDisabled");
        if (mService != null) {
            try {
                return mService.getCrossProfileCallerIdDisabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by the system to determine whether or not caller-Id information has been disabled.
     * <p>
     * Starting with {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * this will return true when
     * {@link #setManagedProfileCallerIdAccessPolicy(PackagePolicy)}
     * has been set with a non-null policy whose policy type is NOT
     * {@link PackagePolicy#PACKAGE_POLICY_BLOCKLIST}
     *
     * @param userHandle The user for whom to check the caller-id permission
     * @deprecated use {@link #hasManagedProfileCallerIdAccess(UserHandle, String)} and provide the
     * package name requesting access
     * @hide
     */
    @Deprecated
    public boolean getCrossProfileCallerIdDisabled(UserHandle userHandle) {
        if (mService != null) {
            try {
                return mService.getCrossProfileCallerIdDisabledForUser(userHandle.getIdentifier());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner or profile owner of a managed profile to set the credential manager
     * policy.
     *
     * <p>Affects APIs exposed by {@link android.credentials.CredentialManager}.
     *
     * <p>A {@link PackagePolicy#PACKAGE_POLICY_ALLOWLIST} policy type will limit the credential
     * providers that the user can use to the list of packages in the policy.
     *
     * <p>A {@link PackagePolicy#PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM} policy type
     * allows access from the OEM default credential providers and the allowlist of credential
     * providers.
     *
     * <p>A {@link PackagePolicy#PACKAGE_POLICY_BLOCKLIST} policy type will block the credential
     * providers listed in the policy from being used by the user.
     *
     * @param policy the policy to set, setting this value to {@code null} will allow all packages
     * @throws SecurityException if caller is not a device owner or profile owner of a
     * managed profile
     */
    public void setCredentialManagerPolicy(@Nullable PackagePolicy policy) {
        throwIfParentInstance("setCredentialManagerPolicy");
        if (mService != null) {
            try {
                mService.setCredentialManagerPolicy(policy);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device owner or profile owner of a managed profile to retrieve the credential
     * manager policy.
     *
     * @throws SecurityException if caller is not a device owner or profile owner of a
     * managed profile.
     * @return the current credential manager policy if null then this policy has not been
     * configured.
     */
    @UserHandleAware(
            enabledSinceTargetSdkVersion = UPSIDE_DOWN_CAKE,
            requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public @Nullable PackagePolicy getCredentialManagerPolicy() {
        throwIfParentInstance("getCredentialManagerPolicy");
        if (mService != null) {
            try {
                return mService.getCredentialManagerPolicy(myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a profile owner of a managed profile to set the packages that are allowed to
     * lookup contacts in the managed profile based on caller id information.
     * <p>
     * For example, the policy determines if a dialer app in the parent profile resolving
     * an incoming call can search the caller id data, such as phone number,
     * of managed contacts and return managed contacts that match.
     * <p>
     * The calling device admin must be a profile owner of a managed profile.
     * If it is not, a {@link SecurityException} will be thrown.
     * <p>
     * A {@link PackagePolicy#PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM} policy type
     * allows access from the OEM default packages for the Sms, Dialer and Contact roles,
     * in addition to the packages specified in {@link PackagePolicy#getPackageNames()}
     *
     * @param policy the policy to set, setting this value to {@code null} will allow
     *               all packages
     * @throws SecurityException if caller is not a profile owner of a managed profile
     */
    public void setManagedProfileCallerIdAccessPolicy(@Nullable PackagePolicy policy) {
        throwIfParentInstance("setManagedProfileCallerIdAccessPolicy");
        if (mService != null) {
            try {
                mService.setManagedProfileCallerIdAccessPolicy(policy);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to retrieve the caller id policy.
     * <p>
     * The calling device admin must be a profile owner of a managed profile.
     * If it is not, a {@link SecurityException} will be thrown.
     *
     * @throws SecurityException if caller is not a profile owner of a managed profile.
     * @return the current caller id policy
     */
    public @Nullable PackagePolicy getManagedProfileCallerIdAccessPolicy() {
        throwIfParentInstance("getManagedProfileCallerIdAccessPolicy");
        if (mService != null) {
            try {
                return mService.getManagedProfileCallerIdAccessPolicy();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Determine whether the given package is allowed to query the requested user to
     * populate caller id information
     *
     * @param userHandle The user for whom to check the contacts search permission
     * @param packageName the name of the package requesting access
     * @return true if package should be granted access, false otherwise
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public boolean hasManagedProfileCallerIdAccess(@NonNull UserHandle userHandle,
            @NonNull String packageName) {
        if (mService == null) {
            return true;
        }
        try {
            return mService.hasManagedProfileCallerIdAccess(userHandle.getIdentifier(),
                    packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of a managed profile to set the packages that are allowed
     * access to the managed profile contacts from the parent user.
     * <p>
     * For example, the system will enforce the provided policy and determine
     * if contacts in the managed profile are shown when queried by an application
     * in the parent user.
     * <p>
     * The calling device admin must be a profile owner of a managed profile.
     * If it is not, a {@link SecurityException} will be thrown.
     * <p>
     * A {@link PackagePolicy#PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM} policy type
     * allows access from the OEM default packages for the Sms, Dialer and Contact roles,
     * in addition to the packages specified in {@link PackagePolicy#getPackageNames()}
     *
     * @param policy the policy to set, setting this value to {@code null} will allow
     *               all packages
     * @throws SecurityException if caller is not a profile owner of a managed profile
     */
    public void setManagedProfileContactsAccessPolicy(@Nullable PackagePolicy policy) {
        throwIfParentInstance("setManagedProfileContactsAccessPolicy");
        if (mService != null) {
            try {
                mService.setManagedProfileContactsAccessPolicy(policy);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to determine the current policy applied
     * to managed profile contacts.
     * <p>
     * The calling device admin must be a profile owner of a managed profile.
     * If it is not, a {@link SecurityException} will be thrown.
     *
     * @throws SecurityException if caller is not a profile owner of a managed profile.
     * @return the current contacts search policy
     */
    public @Nullable PackagePolicy getManagedProfileContactsAccessPolicy() {
        throwIfParentInstance("getManagedProfileContactsAccessPolicy");
        if (mService == null) {
            return null;
        }
        try {
            return mService.getManagedProfileContactsAccessPolicy();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Determine whether requesting package has ability to access contacts of the requested user
     *
     * @param userHandle The user for whom to check the contacts search permission
     * @param packageName packageName requesting access to contact search
     * @return true when package is allowed access, false otherwise
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public boolean hasManagedProfileContactsAccess(@NonNull UserHandle userHandle,
            @NonNull String packageName) {
        if (mService != null) {
            try {
                return mService.hasManagedProfileContactsAccess(userHandle.getIdentifier(),
                        packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a profile owner of a managed profile to set whether contacts search from the
     * managed profile will be shown in the parent profile, for incoming calls.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     *
     * Starting with {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, calling this function
     * is similar to calling {@link #setManagedProfileContactsAccessPolicy(PackagePolicy)} with a
     * {@link PackagePolicy#PACKAGE_POLICY_BLOCKLIST} policy type when {@code disabled} is false
     * or a {@link PackagePolicy#PACKAGE_POLICY_ALLOWLIST} policy type when {@code disabled}
     * is true.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled If true contacts search in the managed profile is not displayed.
     * @throws SecurityException if {@code admin} is not a profile owner.
     *
     * @deprecated From {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} use
     * {@link #setManagedProfileContactsAccessPolicy(PackagePolicy)}
     */
    @Deprecated
    public void setCrossProfileContactsSearchDisabled(@NonNull ComponentName admin,
            boolean disabled) {
        throwIfParentInstance("setCrossProfileContactsSearchDisabled");
        if (mService != null) {
            try {
                mService.setCrossProfileContactsSearchDisabled(admin, disabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to determine whether or not contacts search
     * has been disabled.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     * <p>
     * Starting with {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * this will return true when
     * {@link #setManagedProfileContactsAccessPolicy(PackagePolicy)}
     * has been set with a non-null policy whose policy type is NOT
     * {@link PackagePolicy#PACKAGE_POLICY_BLOCKLIST}
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a profile owner.
     * @deprecated From {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} use
     * {@link #getManagedProfileContactsAccessPolicy()}
     */
    @Deprecated
    public boolean getCrossProfileContactsSearchDisabled(@NonNull ComponentName admin) {
        throwIfParentInstance("getCrossProfileContactsSearchDisabled");
        if (mService != null) {
            try {
                return mService.getCrossProfileContactsSearchDisabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Determine whether or not contacts search has been disabled.
     * <p>
     * Starting with {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * this will return true when
     * {@link #setManagedProfileContactsAccessPolicy(PackagePolicy)}
     * has been set with a non-null policy whose policy type is NOT
     * {@link PackagePolicy#PACKAGE_POLICY_BLOCKLIST}
     * @param userHandle The user for whom to check the contacts search permission
     * @deprecated use {@link #hasManagedProfileContactsAccess(UserHandle, String)} and provide the
     * package name requesting access
     * @hide
     */
    @Deprecated
    public boolean getCrossProfileContactsSearchDisabled(@NonNull UserHandle userHandle) {
        if (mService != null) {
            try {
                return mService
                        .getCrossProfileContactsSearchDisabledForUser(userHandle.getIdentifier());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Start Quick Contact on the managed profile for the user, if the policy allows.
     *
     * @hide
     */
    public void startManagedQuickContact(String actualLookupKey, long actualContactId,
            boolean isContactIdIgnored, long directoryId, Intent originalIntent) {
        if (mService != null) {
            try {
                mService.startManagedQuickContact(actualLookupKey, actualContactId,
                        isContactIdIgnored, directoryId, originalIntent);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Start Quick Contact on the managed profile for the user, if the policy allows.
     * @hide
     */
    public void startManagedQuickContact(String actualLookupKey, long actualContactId,
            Intent originalIntent) {
        startManagedQuickContact(actualLookupKey, actualContactId, false, Directory.DEFAULT,
                originalIntent);
    }

    /**
     * Called by a profile owner of a managed profile to set whether bluetooth devices can access
     * enterprise contacts.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     * <p>
     * This API works on managed profile only.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled If true, bluetooth devices cannot access enterprise contacts.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public void setBluetoothContactSharingDisabled(@NonNull ComponentName admin, boolean disabled) {
        throwIfParentInstance("setBluetoothContactSharingDisabled");
        if (mService != null) {
            try {
                mService.setBluetoothContactSharingDisabled(admin, disabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to determine whether or not Bluetooth devices
     * cannot access enterprise contacts.
     * <p>
     * The calling device admin must be a profile owner. If it is not, a security exception will be
     * thrown.
     * <p>
     * This API works on managed profile only.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public boolean getBluetoothContactSharingDisabled(@NonNull ComponentName admin) {
        throwIfParentInstance("getBluetoothContactSharingDisabled");
        if (mService != null) {
            try {
                return mService.getBluetoothContactSharingDisabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
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
    @SystemApi
    @RequiresPermission(permission.INTERACT_ACROSS_USERS)
    public boolean getBluetoothContactSharingDisabled(@NonNull UserHandle userHandle) {
        if (mService != null) {
            try {
                return mService.getBluetoothContactSharingDisabledForUser(userHandle
                        .getIdentifier());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return true;
    }

    /**
     * Called by the profile owner of a managed profile so that some intents sent in the managed
     * profile can also be resolved in the parent, or vice versa. Only activity intents are
     * supported.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param filter The {@link IntentFilter} the intent has to match to be also resolved in the
     *            other profile
     * @param flags {@link DevicePolicyManager#FLAG_MANAGED_CAN_ACCESS_PARENT} and
     *            {@link DevicePolicyManager#FLAG_PARENT_CAN_ACCESS_MANAGED} are supported.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_PROFILE_INTERACTION, conditional = true)
    public void addCrossProfileIntentFilter(@Nullable ComponentName admin, IntentFilter filter,
            int flags) {
        throwIfParentInstance("addCrossProfileIntentFilter");
        if (mService != null) {
            try {
                mService.addCrossProfileIntentFilter(admin, mContext.getPackageName(), filter,
                        flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of a managed profile to remove the cross-profile intent filters
     * that go from the managed profile to the parent, or from the parent to the managed profile.
     * Only removes those that have been set by the profile owner.
     * <p>
     * <em>Note</em>: A list of default cross profile intent filters are set up by the system when
     * the profile is created, some of them ensure the proper functioning of the profile, while
     * others enable sharing of data from the parent to the managed profile for user convenience.
     * These default intent filters are not cleared when this API is called. If the default cross
     * profile data sharing is not desired, they can be disabled with
     * {@link UserManager#DISALLOW_SHARE_INTO_MANAGED_PROFILE}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_PROFILE_INTERACTION, conditional = true)
    public void clearCrossProfileIntentFilters(@Nullable ComponentName admin) {
        throwIfParentInstance("clearCrossProfileIntentFilters");
        if (mService != null) {
            try {
                mService.clearCrossProfileIntentFilters(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile or device owner to set the permitted
     * {@link android.accessibilityservice.AccessibilityService}. When set by
     * a device owner or profile owner the restriction applies to all profiles of the user the
     * device owner or profile owner is an admin for. By default, the user can use any accessibility
     * service. When zero or more packages have been added, accessibility services that are not in
     * the list and not part of the system can not be enabled by the user.
     * <p>
     * Calling with a {@code null} value for the list disables the restriction so that all services
     * can be used, calling with an empty list only allows the built-in system services. Any
     * non-system accessibility service that's currently enabled must be included in the list.
     * <p>
     * System accessibility services are always available to the user and this method can't
     * disable them.
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames List of accessibility service package names.
     * @return {@code true} if the operation succeeded, or {@code false} if the list didn't
     *         contain every enabled non-system accessibility service.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean setPermittedAccessibilityServices(@NonNull ComponentName admin,
            List<String> packageNames) {
        throwIfParentInstance("setPermittedAccessibilityServices");
        if (mService != null) {
            try {
                return mService.setPermittedAccessibilityServices(admin, packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the list of permitted accessibility services set by this device or profile owner.
     * <p>
     * An empty list means no accessibility services except system services are allowed.
     * {@code null} means all accessibility services are allowed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return List of accessiblity service package names.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public @Nullable List<String> getPermittedAccessibilityServices(@NonNull ComponentName admin) {
        throwIfParentInstance("getPermittedAccessibilityServices");
        if (mService != null) {
            try {
                return mService.getPermittedAccessibilityServices(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by the system to check if a specific accessibility service is disabled by admin.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName Accessibility service package name that needs to be checked.
     * @param userHandle user id the admin is running as.
     * @return true if the accessibility service is permitted, otherwise false.
     *
     * @hide
     */
    public boolean isAccessibilityServicePermittedByAdmin(@NonNull ComponentName admin,
            @NonNull String packageName, int userHandle) {
        if (mService != null) {
            try {
                return mService.isAccessibilityServicePermittedByAdmin(admin, packageName,
                        userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the list of accessibility services permitted by the device or profiles
     * owners of this user.
     *
     * <p>{@code null} means all accessibility services are allowed, if a non-null list is returned
     * it will contain the intersection of the permitted lists for any device or profile
     * owners that apply to this user. It will also include any system accessibility services.
     *
     * @param userId which user to check for.
     * @return List of accessiblity service package names.
     * @hide
     */
     @SystemApi
     @RequiresPermission(anyOf = {
             android.Manifest.permission.MANAGE_USERS,
             android.Manifest.permission.QUERY_ADMIN_POLICY})
     public @Nullable List<String> getPermittedAccessibilityServices(int userId) {
        throwIfParentInstance("getPermittedAccessibilityServices");
        if (mService != null) {
            try {
                return mService.getPermittedAccessibilityServicesForUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
     }

    /**
     * Called by a profile or device owner or holder of the
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_INPUT_METHODS} permission to set
     * the permitted input methods services for this user. By default, the user can use any input
     * method.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance,
     * returned by {@link #getParentProfileInstance(ComponentName)}, where the caller must be
     * a profile owner of an organization-owned device.
     * <p>
     * If called on the parent instance:
     * <ul>
     *    <li>The permitted input methods will be applied on the personal profile</li>
     *    <li>Can only permit all input methods (calling this method with a {@code null} package
     *    list) or only permit system input methods (calling this method with an empty package
     *    list). This is to prevent the caller from learning which packages are installed on
     *    the personal side</li>
     * </ul>
     * <p>
     * When zero or more packages have been added, input method that are not in the list and not
     * part of the system can not be enabled by the user. This method will fail if it is called for
     * a admin that is not for the foreground user or a profile of the foreground user. Any
     * non-system input method service that's currently enabled must be included in the list.
     * <p>
     * Calling with a null value for the list disables the restriction so that all input methods can
     * be used, calling with an empty list disables all but the system's own input methods.
     * <p>
     * System input methods are always available to the user - this method can't modify this.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin
     * @param packageNames List of input method package names.
     * @return {@code true} if the operation succeeded, or {@code false} if the list didn't
     *        contain every enabled non-system input method service.
     * @throws SecurityException if {@code admin} is not a device or profile owner and does not
     *                              hold the {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_INPUT_METHODS}
     *                              permission, or if called on the parent profile and the
     *                              {@code admin} is not a profile owner of an organization-owned
     *                              managed profile.
     * @throws IllegalArgumentException if called on the parent profile, the {@code admin} is a
     *                           profile owner of an organization-owned managed profile and the
     *                           list of permitted input method package names is not null or empty.
     */
    @SupportsCoexistence
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_INPUT_METHODS, conditional = true)
    public boolean setPermittedInputMethods(
            @Nullable ComponentName admin, List<String> packageNames) {
        if (mService != null) {
            try {
                return mService.setPermittedInputMethods(
                        admin, mContext.getPackageName(), packageNames, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }


    /**
     * Returns the list of permitted input methods set by this device or profile owner.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance,
     * returned by {@link #getParentProfileInstance(ComponentName)}, where the caller must be
     * a profile owner of an organization-owned managed profile. If called on the parent instance,
     * then the returned list of permitted input methods are those which are applied on the
     * personal profile.
     * <p>
     * An empty list means no input methods except system input methods are allowed. Null means all
     * input methods are allowed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin
     * @return List of input method package names.
     * @throws SecurityException if {@code admin} is not a device, profile owner or if called on
     *                           the parent profile and the {@code admin} is not a profile owner
     *                           of an organization-owned managed profile.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_INPUT_METHODS, conditional = true)
    public @Nullable List<String> getPermittedInputMethods(@Nullable ComponentName admin) {
        if (mService != null) {
            try {
                return mService.getPermittedInputMethods(admin, mContext.getPackageName(), mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by the system to check if a specific input method is disabled by admin.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance,
     * returned by {@link #getParentProfileInstance(ComponentName)}. If called on the parent
     * instance, this method will check whether the given input method is permitted on
     * the personal profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName Input method package name that needs to be checked.
     * @param userHandle user id the admin is running as.
     * @return true if the input method is permitted, otherwise false.
     *
     * @hide
     */
    public boolean isInputMethodPermittedByAdmin(@NonNull ComponentName admin,
            @NonNull String packageName, int userHandle) {
        if (mService != null) {
            try {
                return mService.isInputMethodPermittedByAdmin(admin, packageName, userHandle,
                        mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the list of input methods permitted by the device or profiles owners.
     *
     * <p>On {@link android.os.Build.VERSION_CODES#Q} and later devices, this method returns the
     * result for the calling user.</p>
     *
     * <p>On Android P and prior devices, this method returns the result for the current user.</p>
     *
     * <p>Null means all input methods are allowed, if a non-null list is returned
     * it will contain the intersection of the permitted lists for any device or profile
     * owners that apply to this user. It will also include any system input methods.
     *
     * @return List of input method package names.
     * @hide
     *
     * @see #setPermittedAccessibilityServices(ComponentName, List)
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.QUERY_ADMIN_POLICY})
    public @Nullable List<String> getPermittedInputMethodsForCurrentUser() {
        throwIfParentInstance("getPermittedInputMethodsForCurrentUser");
        if (mService != null) {
            try {
                return mService.getPermittedInputMethodsAsUser(UserHandle.myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Returns the list of input methods permitted.
     *
     * <p>{@code null} means all input methods are allowed, if a non-null list is returned
     * it will contain the intersection of the permitted lists for any device or profile
     * owners that apply to this user. It will also include any system input methods.
     *
     * @return List of input method package names.
     * @hide
     *
     * @see #setPermittedAccessibilityServices(ComponentName, List)
     */
    @UserHandleAware
    @RequiresPermission(allOf = {
            INTERACT_ACROSS_USERS_FULL,
            android.Manifest.permission.MANAGE_USERS
            }, conditional = true)
    public @Nullable List<String> getPermittedInputMethods() {
        throwIfParentInstance("getPermittedInputMethods");
        if (mService != null) {
            try {
                return mService.getPermittedInputMethodsAsUser(myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a profile owner of a managed profile to set the packages that are allowed to use
     * a {@link android.service.notification.NotificationListenerService} in the primary user to
     * see notifications from the managed profile. By default all packages are permitted by this
     * policy. When zero or more packages have been added, notification listeners installed on the
     * primary user that are not in the list and are not part of the system won't receive events
     * for managed profile notifications.
     * <p>
     * Calling with a {@code null} value for the list disables the restriction so that all
     * notification listener services be used. Calling with an empty list disables all but the
     * system's own notification listeners. System notification listener services are always
     * available to the user.
     * <p>
     * If a device or profile owner want to stop notification listeners in their user from seeing
     * that user's notifications they should prevent that service from running instead (e.g. via
     * {@link #setApplicationHidden(ComponentName, String, boolean)})
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageList List of package names to allowlist
     * @return true if setting the restriction succeeded. It will fail if called outside a managed
     * profile
     * @throws SecurityException if {@code admin} is not a profile owner.
     *
     * @see android.service.notification.NotificationListenerService
     */
    public boolean setPermittedCrossProfileNotificationListeners(
            @NonNull ComponentName admin, @Nullable List<String> packageList) {
        throwIfParentInstance("setPermittedCrossProfileNotificationListeners");
        if (mService != null) {
            try {
                return mService.setPermittedCrossProfileNotificationListeners(admin, packageList);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the list of packages installed on the primary user that allowed to use a
     * {@link android.service.notification.NotificationListenerService} to receive
     * notifications from this managed profile, as set by the profile owner.
     * <p>
     * An empty list means no notification listener services except system ones are allowed.
     * A {@code null} return value indicates that all notification listeners are allowed.
     */
    public @Nullable List<String> getPermittedCrossProfileNotificationListeners(
            @NonNull ComponentName admin) {
        throwIfParentInstance("getPermittedCrossProfileNotificationListeners");
        if (mService != null) {
            try {
                return mService.getPermittedCrossProfileNotificationListeners(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Returns true if {@code NotificationListenerServices} from the given package are allowed to
     * receive events for notifications from the given user id. Can only be called by the system uid
     *
     * @see #setPermittedCrossProfileNotificationListeners(ComponentName, List)
     *
     * @hide
     */
    public boolean isNotificationListenerServicePermitted(
            @NonNull String packageName, @UserIdInt int userId) {
        if (mService != null) {
            try {
                return mService.isNotificationListenerServicePermitted(packageName, userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return true;
    }

    /**
     * Get the list of apps to keep around as APKs even if no user has currently installed it. This
     * function can be called by a device owner or by a delegate given the
     * {@link #DELEGATION_KEEP_UNINSTALLED_PACKAGES} scope via {@link #setDelegatedScopes}.
     * <p>
     * Please note that packages returned in this method are not automatically pre-cached.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is a keep uninstalled packages delegate.
     * @return List of package names to keep cached.
     * @see #setDelegatedScopes
     * @see #DELEGATION_KEEP_UNINSTALLED_PACKAGES
     */
    public @Nullable List<String> getKeepUninstalledPackages(@Nullable ComponentName admin) {
        throwIfParentInstance("getKeepUninstalledPackages");
        if (mService != null) {
            try {
                return mService.getKeepUninstalledPackages(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Set a list of apps to keep around as APKs even if no user has currently installed it. This
     * function can be called by a device owner or by a delegate given the
     * {@link #DELEGATION_KEEP_UNINSTALLED_PACKAGES} scope via {@link #setDelegatedScopes}.
     *
     * <p>Please note that setting this policy does not imply that specified apps will be
     * automatically pre-cached.</p>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is a keep uninstalled packages delegate.
     * @param packageNames List of package names to keep cached.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_KEEP_UNINSTALLED_PACKAGES
     */
    public void setKeepUninstalledPackages(@Nullable ComponentName admin,
            @NonNull List<String> packageNames) {
        throwIfParentInstance("setKeepUninstalledPackages");
        if (mService != null) {
            try {
                mService.setKeepUninstalledPackages(admin, mContext.getPackageName(), packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
      * Flag used by {@link #createAndManageUser} to skip setup wizard after creating a new user.
      */
    public static final int SKIP_SETUP_WIZARD = 0x0001;

    /**
     * Flag used by {@link #createAndManageUser} to specify that the user should be created
     * ephemeral. Ephemeral users will be removed after switching to another user or rebooting the
     * device.
     */
    public static final int MAKE_USER_EPHEMERAL = 0x0002;

    /**
     * Flag used by {@link #createAndManageUser} to specify that the user should be created as a
     * demo user.
     * @hide
     */
    public static final int MAKE_USER_DEMO = 0x0004;

    /**
     * Flag used by {@link #createAndManageUser} to specify that the newly created user should skip
     * the disabling of system apps during provisioning.
     */
    public static final int LEAVE_ALL_SYSTEM_APPS_ENABLED = 0x0010;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "SKIP_", "MAKE_USER_", "START_", "LEAVE_" }, value = {
            SKIP_SETUP_WIZARD,
            MAKE_USER_EPHEMERAL,
            MAKE_USER_DEMO,
            LEAVE_ALL_SYSTEM_APPS_ENABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CreateAndManageUserFlags {}

    /**
     * Called by a device owner to create a user with the specified name and a given component of
     * the calling package as profile owner. The UserHandle returned by this method should not be
     * persisted as user handles are recycled as users are removed and created. If you need to
     * persist an identifier for this user, use {@link UserManager#getSerialNumberForUser}. The new
     * user will not be started in the background.
     * <p>
     * admin is the {@link DeviceAdminReceiver} which is the device owner. profileOwner is also a
     * DeviceAdminReceiver in the same package as admin, and will become the profile owner and will
     * be registered as an active admin on the new user. The profile owner package will be installed
     * on the new user.
     * <p>
     * If the adminExtras are not null, they will be stored on the device until the user is started
     * for the first time. Then the extras will be passed to the admin when onEnable is called.
     * <p>From {@link android.os.Build.VERSION_CODES#P} onwards, if targeting
     * {@link android.os.Build.VERSION_CODES#P}, throws {@link UserOperationException} instead of
     * returning {@code null} on failure.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param name The user's name.
     * @param profileOwner Which {@link DeviceAdminReceiver} will be profile owner. Has to be in the
     *            same package as admin, otherwise no user is created and an
     *            IllegalArgumentException is thrown.
     * @param adminExtras Extras that will be passed to onEnable of the admin receiver on the new
     *            user.
     * @param flags {@link #SKIP_SETUP_WIZARD}, {@link #MAKE_USER_EPHEMERAL} and
     *        {@link #LEAVE_ALL_SYSTEM_APPS_ENABLED} are supported.
     * @see UserHandle
     * @return the {@link android.os.UserHandle} object for the created user, or {@code null} if the
     *         user could not be created.
     * @throws SecurityException if headless device is in
     *        {@link DeviceAdminInfo#HEADLESS_DEVICE_OWNER_MODE_SINGLE_USER} mode.
     * @throws SecurityException if {@code admin} is not a device owner
     * @throws UserOperationException if the user could not be created and the calling app is
     * targeting {@link android.os.Build.VERSION_CODES#P} and running on
     * {@link android.os.Build.VERSION_CODES#P}.
     */
    public @Nullable UserHandle createAndManageUser(@NonNull ComponentName admin,
            @NonNull String name,
            @NonNull ComponentName profileOwner, @Nullable PersistableBundle adminExtras,
            @CreateAndManageUserFlags int flags) {
        throwIfParentInstance("createAndManageUser");
        try {
            return mService.createAndManageUser(admin, name, profileOwner, adminExtras, flags);
        } catch (ServiceSpecificException e) {
            throw new UserOperationException(e.getMessage(), e.errorCode);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to remove a user/profile and all associated data. The primary user
     * can not be removed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to remove.
     * @return {@code true} if the user was removed, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public boolean removeUser(@NonNull ComponentName admin, @NonNull UserHandle userHandle) {
        throwIfParentInstance("removeUser");
        try {
            return mService.removeUser(admin, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to switch the specified secondary user to the foreground.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to switch to; null will switch to primary.
     * @return {@code true} if the switch was successful, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see Intent#ACTION_USER_FOREGROUND
     * @see #getSecondaryUsers(ComponentName)
     */
    public boolean switchUser(@NonNull ComponentName admin, @Nullable UserHandle userHandle) {
        throwIfParentInstance("switchUser");
        try {
            return mService.switchUser(admin, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to start the specified secondary user in background.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to be started in background.
     * @return one of the following result codes:
     * {@link UserManager#USER_OPERATION_ERROR_UNKNOWN},
     * {@link UserManager#USER_OPERATION_SUCCESS},
     * {@link UserManager#USER_OPERATION_ERROR_MANAGED_PROFILE},
     * {@link UserManager#USER_OPERATION_ERROR_MAX_RUNNING_USERS},
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #getSecondaryUsers(ComponentName)
     */
    public @UserOperationResult int startUserInBackground(
            @NonNull ComponentName admin, @NonNull UserHandle userHandle) {
        throwIfParentInstance("startUserInBackground");
        try {
            return mService.startUserInBackground(admin, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to stop the specified secondary user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle the user to be stopped.
     * @return one of the following result codes:
     * {@link UserManager#USER_OPERATION_ERROR_UNKNOWN},
     * {@link UserManager#USER_OPERATION_SUCCESS},
     * {@link UserManager#USER_OPERATION_ERROR_MANAGED_PROFILE},
     * {@link UserManager#USER_OPERATION_ERROR_CURRENT_USER}
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #getSecondaryUsers(ComponentName)
     */
    public @UserOperationResult int stopUser(
            @NonNull ComponentName admin, @NonNull UserHandle userHandle) {
        throwIfParentInstance("stopUser");
        try {
            return mService.stopUser(admin, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of secondary user that is affiliated with the device to stop the
     * calling user and switch back to primary user (when the user was
     * {@link #switchUser(ComponentName, UserHandle)} switched to) or stop the user (when it was
     * {@link #startUserInBackground(ComponentName, UserHandle) started in background}.
     *
     * <p>Notice that on devices running with
     * {@link UserManager#isHeadlessSystemUserMode() headless system user mode}, there is no primary
     * user, so it switches back to the user that was in the foreground before the first call to
     * {@link #switchUser(ComponentName, UserHandle)} (or fails with
     * {@link UserManager#USER_OPERATION_ERROR_UNKNOWN} if that method was not called prior to this
     * call).
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return one of the following result codes:
     * {@link UserManager#USER_OPERATION_ERROR_UNKNOWN},
     * {@link UserManager#USER_OPERATION_SUCCESS},
     * {@link UserManager#USER_OPERATION_ERROR_MANAGED_PROFILE},
     * {@link UserManager#USER_OPERATION_ERROR_CURRENT_USER}
     * @throws SecurityException if {@code admin} is not a profile owner affiliated with the device.
     * @see #getSecondaryUsers(ComponentName)
     */
    public @UserOperationResult int logoutUser(@NonNull ComponentName admin) {
        throwIfParentInstance("logoutUser");
        try {
            return mService.logoutUser(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of an organization-owned device to specify {@link
     * ManagedSubscriptionsPolicy}
     *
     * <p>Managed subscriptions policy controls how SIMs would be associated with the
     * managed profile. For example a policy of type
     * {@link ManagedSubscriptionsPolicy#TYPE_ALL_MANAGED_SUBSCRIPTIONS} assigns all
     * SIM-based subscriptions to the managed profile. In this case OEM default
     * dialer and messages app are automatically installed in the managed profile
     * and all incoming and outgoing calls and text messages are handled by them.
     * <p>This API can only be called during device setup.
     *
     * @param policy {@link ManagedSubscriptionsPolicy} policy, passing null for this resets the
     *               policy to be the default.
     * @throws SecurityException     if the caller is not a profile owner on an organization-owned
     *                               managed profile.
     * @throws IllegalStateException if called after the device setup has been completed.
     * @throws UnsupportedOperationException if managed subscriptions policy is not explicitly
     *         enabled by the device policy management role holder during device setup.
     * @see ManagedSubscriptionsPolicy
     */
    public void setManagedSubscriptionsPolicy(@Nullable ManagedSubscriptionsPolicy policy) {
        throwIfParentInstance("setManagedSubscriptionsPolicy");
        try {
            mService.setManagedSubscriptionsPolicy(policy);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current {@link ManagedSubscriptionsPolicy}.
     * If the policy has not been set, it will return a default policy of Type {@link
     * ManagedSubscriptionsPolicy#TYPE_ALL_PERSONAL_SUBSCRIPTIONS}.
     *
     * @see #setManagedSubscriptionsPolicy(ManagedSubscriptionsPolicy)
     */
    @NonNull
    public ManagedSubscriptionsPolicy getManagedSubscriptionsPolicy() {
        throwIfParentInstance("getManagedSubscriptionsPolicy");
        try {
            return mService.getManagedSubscriptionsPolicy();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Similar to {@link #logoutUser(ComponentName)}, except:
     *
     * <ul>
     *   <li>Called by system (like Settings), not admin.
     *   <li>It logs out the current user, not the caller.
     * </ul>
     *
     * @hide
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public @UserOperationResult int logoutUser() {
        // TODO(b/214336184): add CTS test
        try {
            return mService.logoutUserInternal();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the user a {@link #logoutUser(ComponentName)} call would switch to,
     * or {@code null} if the current user is not in a session (i.e., if it was not
     * {@link #switchUser(ComponentName, UserHandle) switched} or
     * {@link #startUserInBackground(ComponentName, UserHandle) started in background} by the
     * device admin.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public @Nullable UserHandle getLogoutUser() {
        // TODO(b/214336184): add CTS test
        try {
            int userId = mService.getLogoutUserId();
            return userId == UserHandle.USER_NULL ? null : UserHandle.of(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to list all secondary users on the device. Managed profiles are not
     * considered as secondary users.
     * <p> Used for various user management APIs, including {@link #switchUser}, {@link #removeUser}
     * and {@link #stopUser}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return list of other {@link UserHandle}s on the device.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see #removeUser(ComponentName, UserHandle)
     * @see #switchUser(ComponentName, UserHandle)
     * @see #startUserInBackground(ComponentName, UserHandle)
     * @see #stopUser(ComponentName, UserHandle)
     */
    public List<UserHandle> getSecondaryUsers(@NonNull ComponentName admin) {
        throwIfParentInstance("getSecondaryUsers");
        try {
            return mService.getSecondaryUsers(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the profile owner is running in an ephemeral user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return whether the profile owner is running in an ephemeral user.
     */
    public boolean isEphemeralUser(@NonNull ComponentName admin) {
        throwIfParentInstance("isEphemeralUser");
        try {
            return mService.isEphemeralUser(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the application restrictions for a given target application running in the calling
     * user.
     * <p>
     * The caller must be a profile or device owner on that user, or the package allowed to manage
     * application restrictions via {@link #setDelegatedScopes} with the
     * {@link #DELEGATION_APP_RESTRICTIONS} scope; otherwise a security exception will be thrown.
     *
     * <p>NOTE: The method performs disk I/O and shouldn't be called on the main thread
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if called by the application restrictions managing package.
     * @param packageName The name of the package to fetch restricted settings of.
     * @return {@link Bundle} of settings corresponding to what was set last time
     *         {@link DevicePolicyManager#setApplicationRestrictions} was called, or an empty
     *         {@link Bundle} if no restrictions have been set.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_APP_RESTRICTIONS
     */
    @WorkerThread
    public @NonNull Bundle getApplicationRestrictions(
            @Nullable ComponentName admin, String packageName) {

        if (mService != null) {
            try {
                return mService.getApplicationRestrictions(admin, mContext.getPackageName(),
                        packageName, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a profile owner, device owner or a holder of any permission that is associated with
     * a user restriction to set a user restriction specified by the key.
     * <p>
     * The calling device admin must be a profile owner, device owner or holder of any permission
     * that is associated with a user restriction; if it is not, a security
     * exception will be thrown.
     * <p>
     * The profile owner of an organization-owned managed profile may invoke this method on
     * the {@link DevicePolicyManager} instance it obtained from
     * {@link #getParentProfileInstance(ComponentName)}, for enforcing device-wide restrictions.
     * <p>
     * See the constants in {@link android.os.UserManager} for the list of restrictions that can
     * be enforced device-wide. These constants will also state in their documentation which
     * permission is required to manage the restriction using this API.
     *
     * <p>For callers targeting Android {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or
     * above, calling this API will result in applying the restriction locally on the calling user,
     * or locally on the parent profile if called from the
     * {@link DevicePolicyManager} instance obtained from
     * {@link #getParentProfileInstance(ComponentName)}. To set a restriction globally, call
     * {@link #addUserRestrictionGlobally} instead.
     *
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the user restriction
     * policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier returned from
     * {@link DevicePolicyIdentifiers#getIdentifierForUserRestriction(String)}
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param key   The key of the restriction.
     * @throws SecurityException if {@code admin} is not a device or profile owner and if the caller
     * has not been granted the permission to set the given user restriction.
     */
    @SupportsCoexistence
    public void addUserRestriction(@NonNull ComponentName admin,
            @UserManager.UserRestrictionKey String key) {
        if (mService != null) {
            try {
                mService.setUserRestriction(
                        admin, mContext.getPackageName(), key, true, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Adds a user restriction on {@code targetUser}, specified by the {@code key}.
     *
     * <p>Called by a system service only, meaning that the caller's UID must be equal to
     * {@link Process#SYSTEM_UID}.
     *
     * @param systemEntity  The service entity that adds the restriction. A user restriction set by
     *                       a service entity can only be cleared by the same entity. This can be
     *                       just the calling package name, or any string of the caller's choice
     *                       can be used.
     * @param key  The key of the restriction.
     * @param targetUser  The user to add the restriction on.
     * @throws SecurityException if the caller is not a system service
     *
     * @hide
     */
    public void addUserRestriction(@NonNull String systemEntity,
            @NonNull @UserManager.UserRestrictionKey String key, @UserIdInt int targetUser) {
        if (mService != null) {
            try {
                mService.setUserRestrictionForUser(
                        systemEntity, key, /* enable= */ true, targetUser);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner, device owner or a holder of any permission that is associated with
     *  a user restriction to set a user restriction specified by the provided {@code key} globally
     *  on all users. To clear the restriction use {@link #clearUserRestriction}.
     *
     * <p>For a given user, a restriction will be set if it was applied globally or locally by any
     * admin.
     *
     * <p> The calling device admin must be a profile owner, device owner or a holder of any
     * permission that is associated with a user restriction; if it is not, a security
     * exception will be thrown.
     *
     * <p> See the constants in {@link android.os.UserManager} for the list of restrictions that can
     * be enforced device-wide. These constants will also state in their documentation which
     * permission is required to manage the restriction using this API.
     * <p>
     * After the user restriction policy has been set,
     * {@link PolicyUpdateReceiver#onPolicySetResult(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin on whether the policy was successfully set or not.
     * This callback will contain:
     * <ul>
     * <li> The policy identifier returned from
     * {@link DevicePolicyIdentifiers#getIdentifierForUserRestriction(String)}
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param key The key of the restriction.
     * @throws SecurityException if {@code admin} is not a device or profile owner and if the
     * caller has not been granted the permission to set the given user restriction.
     * @throws IllegalStateException if caller is not targeting Android
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or above.
     */
    @SupportsCoexistence
    public void addUserRestrictionGlobally(@NonNull @UserManager.UserRestrictionKey String key) {
        throwIfParentInstance("addUserRestrictionGlobally");
        if (mService != null) {
            try {
                mService.setUserRestrictionGlobally(mContext.getPackageName(), key);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner, device owner or a holder of any permission that is associated with
     * a user restriction to clear a user restriction specified by the key.
     * <p>
     * The calling device admin must be a profile or device owner; if it is not, a security
     * exception will be thrown.
     * <p>
     * The profile owner of an organization-owned managed profile may invoke this method on
     * the {@link DevicePolicyManager} instance it obtained from
     * {@link #getParentProfileInstance(ComponentName)}, for clearing device-wide restrictions.
     * <p>
     * See the constants in {@link android.os.UserManager} for the list of restrictions. These
     * constants state in their documentation which permission is required to manage the restriction
     * using this API.
     *
     * <p>For callers targeting Android {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or
     * above, calling this API will result in clearing any local and global restriction with the
     * specified key that was previously set by the caller.
     *
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the user restriction
     * policy has been cleared, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully cleared or not. This callback will contain:
     * <ul>
     * <li> The policy identifier returned from
     * {@link DevicePolicyIdentifiers#getIdentifierForUserRestriction(String)}
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully cleared or the
     * reason the policy failed to be cleared
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param key   The key of the restriction.
     * @throws SecurityException if {@code admin} is not a device or profile owner  and if the
     *  caller has not been granted the permission to set the given user restriction.
     */
    @SupportsCoexistence
    public void clearUserRestriction(@NonNull ComponentName admin,
            @UserManager.UserRestrictionKey String key) {
        if (mService != null) {
            try {
                mService.setUserRestriction(
                        admin, mContext.getPackageName(), key, false, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Clears a user restriction from {@code targetUser}, specified by the {@code key}.
     *
     * <p>Called by a system service only, meaning that the caller's UID must be equal to
     * {@link Process#SYSTEM_UID}.
     *
     * @param systemEntity  The system entity that clears the restriction. A user restriction
     *                         set by a system entity can only be cleared by the same entity. This
     *                         can be just the calling package name, or any string of the caller's
     *                         choice can be used.
     * @param key  The key of the restriction.
     * @param targetUser  The user to clear the restriction from.
     * @throws SecurityException if the caller is not a system service
     *
     * @hide
     */
    public void clearUserRestriction(@NonNull String systemEntity,
            @NonNull @UserManager.UserRestrictionKey String key, @UserIdInt int targetUser) {
        if (mService != null) {
            try {
                mService.setUserRestrictionForUser(
                        systemEntity, key, /* enable= */ false, targetUser);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by an admin to get user restrictions set by themselves with
     * {@link #addUserRestriction(ComponentName, String)}.
     * <p>
     * The target user may have more restrictions set by the system or other admin.
     * To get all the user restrictions currently set, use
     * {@link UserManager#getUserRestrictions()}.
     * <p>
     * The profile owner of an organization-owned managed profile may invoke this method on
     * the {@link DevicePolicyManager} instance it obtained from
     * {@link #getParentProfileInstance(ComponentName)}, for retrieving device-wide restrictions
     * it previously set with {@link #addUserRestriction(ComponentName, String)}.
     *
     * <p>For callers targeting Android {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or
     * above, this API will return the local restrictions set on the calling user, or on the parent
     * profile if called from the {@link DevicePolicyManager} instance obtained from
     * {@link #getParentProfileInstance(ComponentName)}. To get global restrictions set by admin,
     * call {@link #getUserRestrictionsGlobally()} instead.
     *
     * <p>Note that this is different that the returned restrictions for callers targeting pre
     * Android {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, were this API returns
     * all local/global restrictions set by the admin on the calling user using
     * {@link #addUserRestriction(ComponentName, String)} or the parent user if called on the
     * {@link DevicePolicyManager} instance it obtained from {@link #getParentProfileInstance}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return a {@link Bundle} whose keys are the user restrictions, and the values a
     * {@code boolean} indicating whether the restriction is set.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public @NonNull Bundle getUserRestrictions(@NonNull ComponentName admin) {
        Bundle ret = null;
        if (mService != null) {
            try {
                ret = mService.getUserRestrictions(
                        admin, mContext.getPackageName(), mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return ret == null ? new Bundle() : ret;
    }

    /**
     * Called by a profile or device owner to get global user restrictions set with
     * {@link #addUserRestrictionGlobally(String)}.
     * <p>
     * To get all the user restrictions currently set for a certain user, use
     * {@link UserManager#getUserRestrictions()}.
     * @return a {@link Bundle} whose keys are the user restrictions, and the values a
     * {@code boolean} indicating whether the restriction is set.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @throws IllegalStateException if caller is not targeting Android
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or above.
     */
    public @NonNull Bundle getUserRestrictionsGlobally() {
        throwIfParentInstance("createAdminSupportIntent");
        Bundle ret = null;
        if (mService != null) {
            try {
                ret = mService.getUserRestrictionsGlobally(mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return ret == null ? new Bundle() : ret;
    }

    /**
     * Called by any app to display a support dialog when a feature was disabled by an admin.
     * This returns an intent that can be used with {@link Context#startActivity(Intent)} to
     * display the dialog. It will tell the user that the feature indicated by {@code restriction}
     * was disabled by an admin, and include a link for more information. The default content of
     * the dialog can be changed by the restricting admin via
     * {@link #setShortSupportMessage(ComponentName, CharSequence)}. If the restriction is not
     * set (i.e. the feature is available), then the return value will be {@code null}.
     * @param restriction Indicates for which feature the dialog should be displayed. Can be a
     *            user restriction from {@link UserManager}, e.g.
     *            {@link UserManager#DISALLOW_ADJUST_VOLUME}, or one of the constants
     *            {@link #POLICY_DISABLE_CAMERA} or {@link #POLICY_DISABLE_SCREEN_CAPTURE}.
     * @return Intent An intent to be used to start the dialog-activity if the restriction is
     *            set by an admin, or null if the restriction does not exist or no admin set it.
     */
    public Intent createAdminSupportIntent(@NonNull String restriction) {
        throwIfParentInstance("createAdminSupportIntent");
        Intent result = null;
        if (mService != null) {
            try {
                result = mService.createAdminSupportIntent(restriction);
                if (result != null) {
                    result.prepareToEnterProcess(LOCAL_FLAG_FROM_SYSTEM,
                            mContext.getAttributionSource());
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return result;
    }

    /**
     * @param userId      The user for whom to retrieve information.
     * @param restriction The restriction enforced by admin. It could be any user restriction or
     *                    policy like {@link DevicePolicyManager#POLICY_DISABLE_CAMERA} and
     *                    {@link DevicePolicyManager#POLICY_DISABLE_SCREEN_CAPTURE}.
     * @return Details of admin and user which enforced the restriction for the userId. If
     * restriction is null, profile owner for the user or device owner info is returned.
     * @hide
     */
    public @Nullable Bundle getEnforcingAdminAndUserDetails(int userId,
            @Nullable String restriction) {
        if (mService != null) {
            try {
                return mService.getEnforcingAdminAndUserDetails(userId, restriction);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Returns the list of {@link EnforcingAdmin}s who have set this restriction.
     *
     * <p>Note that for {@link #POLICY_SUSPEND_PACKAGES} it returns the PO or DO to keep the
     * behavior the same as before the bug fix for b/192245204.
     *
     * <p>This API is only callable by the system UID
     *
     * @param userId      The user for whom to retrieve the information.
     * @param restriction The restriction enforced by admins. It could be any user restriction or
     *                    policy like {@link DevicePolicyManager#POLICY_DISABLE_CAMERA} and
     *                    {@link DevicePolicyManager#POLICY_DISABLE_SCREEN_CAPTURE}.
     *
     * @hide
     */
    public @NonNull Set<EnforcingAdmin> getEnforcingAdminsForRestriction(int userId,
            @NonNull String restriction) {
        if (mService != null) {
            try {
                return new HashSet<>(mService.getEnforcingAdminsForRestriction(
                        userId, restriction));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Hide or unhide packages. When a package is hidden it is unavailable for use, but the data and
     * actual package file remain. This function can be called by a device owner, profile owner, or
     * by a delegate given the {@link #DELEGATION_PACKAGE_ACCESS} scope via
     * {@link #setDelegatedScopes}.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance, returned by
     * {@link #getParentProfileInstance(ComponentName)}, where the caller must be the profile owner
     * of an organization-owned managed profile and the package must be a system package. If called
     * on the parent instance, then the package is hidden or unhidden in the personal profile.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the application hidden
     * policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier
     * {@link DevicePolicyIdentifiers#APPLICATION_HIDDEN_POLICY}
     * <li> The additional policy params bundle, which contains
     * {@link PolicyUpdateReceiver#EXTRA_PACKAGE_NAME} the package name the policy applies to
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is not a device admin.
     * @param packageName The name of the package to hide or unhide.
     * @param hidden {@code true} if the package should be hidden, {@code false} if it should be
     *            unhidden.
     * @return boolean Whether the hidden setting of the package was successfully updated.
     * @throws SecurityException if {@code admin} is not a device or profile owner or if called on
     *            the parent profile and the {@code admin} is not a profile owner of an
     *            organization-owned managed profile.
     * @throws IllegalArgumentException if called on the parent profile and the package provided
     *            is not a system package.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_PACKAGE_STATE, conditional = true)
    @SupportsCoexistence
    public boolean setApplicationHidden(@Nullable ComponentName admin, String packageName,
            boolean hidden) {
        if (mService != null) {
            try {
                return mService.setApplicationHidden(admin, mContext.getPackageName(), packageName,
                        hidden, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Determine if a package is hidden. This function can be called by a device owner, profile
     * owner, or by a delegate given the {@link #DELEGATION_PACKAGE_ACCESS} scope via
     * {@link #setDelegatedScopes}.
     * <p>
     * This method can be called on the {@link DevicePolicyManager} instance, returned by
     * {@link #getParentProfileInstance(ComponentName)}, where the caller must be the profile owner
     * of an organization-owned managed profile and the package must be a system package. If called
     * on the parent instance, this will determine whether the package is hidden or unhidden in the
     * personal profile.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, the returned policy will be the
     * current resolved policy rather than the policy set by the calling admin.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is not a device admin.
     * @param packageName The name of the package to retrieve the hidden status of.
     * @return boolean {@code true} if the package is hidden, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device or profile owner or if called on
     *            the parent profile and the {@code admin} is not a profile owner of an
     *            organization-owned managed profile.
     * @throws IllegalArgumentException if called on the parent profile and the package provided
     *            is not a system package.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_PACKAGE_STATE, conditional = true)
    public boolean isApplicationHidden(@Nullable ComponentName admin, String packageName) {
        if (mService != null) {
            try {
                return mService.isApplicationHidden(admin, mContext.getPackageName(), packageName,
                        mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Re-enable a system app that was disabled by default when the user was initialized. This
     * function can be called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_ENABLE_SYSTEM_APP} scope via {@link #setDelegatedScopes}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is an enable system app delegate.
     * @param packageName The package to be re-enabled in the calling profile.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public void enableSystemApp(@NonNull ComponentName admin, String packageName) {
        throwIfParentInstance("enableSystemApp");
        if (mService != null) {
            try {
                mService.enableSystemApp(admin, mContext.getPackageName(), packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Re-enable system apps by intent that were disabled by default when the user was initialized.
     * This function can be called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_ENABLE_SYSTEM_APP} scope via {@link #setDelegatedScopes}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *            {@code null} if the caller is an enable system app delegate.
     * @param intent An intent matching the app(s) to be installed. All apps that resolve for this
     *            intent will be re-enabled in the calling profile.
     * @return int The number of activities that matched the intent and were installed.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setDelegatedScopes
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public int enableSystemApp(@NonNull ComponentName admin, Intent intent) {
        throwIfParentInstance("enableSystemApp");
        if (mService != null) {
            try {
                return mService.enableSystemAppWithIntent(admin, mContext.getPackageName(), intent);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Install an existing package that has been installed in another user, or has been kept after
     * removal via {@link #setKeepUninstalledPackages}.
     * This function can be called by a device owner, profile owner or a delegate given
     * the {@link #DELEGATION_INSTALL_EXISTING_PACKAGE} scope via {@link #setDelegatedScopes}.
     * When called in a secondary user or managed profile, the user/profile must be affiliated with
     * the device. See {@link #isAffiliatedUser}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The package to be installed in the calling profile.
     * @return {@code true} if the app is installed; {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not the device owner, or the profile owner of
     * an affiliated user or profile.
     * @see #setKeepUninstalledPackages
     * @see #setDelegatedScopes
     * @see #isAffiliatedUser
     * @see #DELEGATION_PACKAGE_ACCESS
     */
    public boolean installExistingPackage(@NonNull ComponentName admin, String packageName) {
        throwIfParentInstance("installExistingPackage");
        if (mService != null) {
            try {
                return mService.installExistingPackage(admin, mContext.getPackageName(),
                        packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner or profile owner to disable account management for a specific type
     * of account.
     * <p>
     * The calling device admin must be a device owner or profile owner. If it is not, a security
     * exception will be thrown.
     * <p>
     * When account management is disabled for an account type, adding or removing an account of
     * that type will not be possible.
     * <p>
     * From {@link android.os.Build.VERSION_CODES#N} the profile or device owner can still use
     * {@link android.accounts.AccountManager} APIs to add or remove accounts when account
     * management for a specific type is disabled.
     * <p>
     * This method may be called on the {@code DevicePolicyManager} instance returned from
     * {@link #getParentProfileInstance(ComponentName)} by the profile owner on an
     * organization-owned device, to restrict accounts that may not be managed on the primary
     * profile.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the account management
     * disabled policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier
     * {@link DevicePolicyIdentifiers#ACCOUNT_MANAGEMENT_DISABLED_POLICY}
     * <li> The additional policy params bundle, which contains
     * {@link PolicyUpdateReceiver#EXTRA_ACCOUNT_TYPE} the account type the policy applies to
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param accountType For which account management is disabled or enabled.
     * @param disabled The boolean indicating that account management will be disabled (true) or
     *            enabled (false).
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_ACCOUNT_MANAGEMENT, conditional = true)
    @SupportsCoexistence
    public void setAccountManagementDisabled(@Nullable ComponentName admin, String accountType,
            boolean disabled) {
        if (mService != null) {
            try {
                mService.setAccountManagementDisabled(admin, mContext.getPackageName(), accountType,
                        disabled, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets the array of accounts for which account management is disabled by the profile owner
     * or device owner.
     *
     * <p> Account management can be disabled/enabled by calling
     * {@link #setAccountManagementDisabled}.
     * <p>
     * This method may be called on the {@code DevicePolicyManager} instance returned from
     * {@link #getParentProfileInstance(ComponentName)}. Note that only a profile owner on
     * an organization-owned device can affect account types on the parent profile instance.
     *
     * @return a list of account types for which account management has been disabled.
     *
     * @see #setAccountManagementDisabled
     */
    public @Nullable String[] getAccountTypesWithManagementDisabled() {
        return getAccountTypesWithManagementDisabledAsUser(myUserId(), mParentInstance);
    }

    /**
     * @see #getAccountTypesWithManagementDisabled()
     * Note that calling this method on the parent profile instance will return the same
     * value as calling it on the main {@code DevicePolicyManager} instance.
     * @hide
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_ACCOUNT_MANAGEMENT, conditional = true)
    public @Nullable String[] getAccountTypesWithManagementDisabledAsUser(int userId) {
        return getAccountTypesWithManagementDisabledAsUser(userId, false);
    }

    /**
     * @see #getAccountTypesWithManagementDisabled()
     * @hide
     */
    public @Nullable String[] getAccountTypesWithManagementDisabledAsUser(
            int userId, boolean parentInstance) {
        if (mService != null) {
            try {
                return mService.getAccountTypesWithManagementDisabledAsUser(userId,
                        mContext.getPackageName(), parentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        return null;
    }

    /**
     * Called by device owner or profile owner to set whether a secondary lockscreen needs to be
     * shown.
     *
     * <p>The secondary lockscreen will by displayed after the primary keyguard security screen
     * requirements are met. To provide the lockscreen content the DO/PO will need to provide a
     * service handling the {@link #ACTION_BIND_SECONDARY_LOCKSCREEN_SERVICE} intent action,
     * extending the {@link DevicePolicyKeyguardService} class.
     *
     * <p>Relevant interactions on the secondary lockscreen should be communicated back to the
     * keyguard via {@link IKeyguardCallback}, such as when the screen is ready to be dismissed.
     *
     * <p>This API, and associated APIs, can only be called by the default supervision app when it
     * is set as the device owner or profile owner.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param enabled Whether or not the lockscreen needs to be shown.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #isSecondaryLockscreenEnabled
     * @hide
     **/
    @SystemApi
    public void setSecondaryLockscreenEnabled(@NonNull ComponentName admin, boolean enabled) {
        setSecondaryLockscreenEnabled(admin, enabled, null);
    }

    /**
     * Called by the system supervision app to set whether a secondary lockscreen needs to be shown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param enabled Whether or not the lockscreen needs to be shown.
     * @param options A {@link PersistableBundle} to supply options to the lock screen.
     * @hide
     */
    public void setSecondaryLockscreenEnabled(@Nullable ComponentName admin, boolean enabled,
            @Nullable PersistableBundle options) {
        throwIfParentInstance("setSecondaryLockscreenEnabled");
        if (mService != null) {
            try {
                mService.setSecondaryLockscreenEnabled(admin, enabled, options);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns whether the secondary lock screen needs to be shown.
     * @see #setSecondaryLockscreenEnabled
     * @hide
     */
    @SystemApi
    public boolean isSecondaryLockscreenEnabled(@NonNull UserHandle userHandle) {
        throwIfParentInstance("isSecondaryLockscreenEnabled");
        if (mService != null) {
            try {
                return mService.isSecondaryLockscreenEnabled(userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets which packages may enter lock task mode.
     * <p>
     * Any packages that share uid with an allowed package will also be allowed to activate lock
     * task. From {@link android.os.Build.VERSION_CODES#M} removing packages from the lock task
     * package list results in locked tasks belonging to those packages to be finished.
     * <p>
     * This function can only be called by the device owner, a profile owner of an affiliated user
     * or profile, or the profile owner when no device owner is set or holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK}. See
     * {@link #isAffiliatedUser}.
     * Any package set via this method will be cleared if the user becomes unaffiliated.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the lock task policy has
     * been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin on whether the policy was successfully set or not.
     * This callback will contain:
     * <ul>
     * <li> The policy identifier {@link DevicePolicyIdentifiers#LOCK_TASK_POLICY}
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, lock task features and lock task
     * packages are bundled as one policy. A failure to apply one will result in a failure to apply
     * the other.
     *
     * @param packages The list of packages allowed to enter lock task mode
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK}.
     * @see #isAffiliatedUser
     * @see Activity#startLockTask()
     * @see DeviceAdminReceiver#onLockTaskModeEntering(Context, Intent, String)
     * @see DeviceAdminReceiver#onLockTaskModeExiting(Context, Intent)
     * @see UserManager#DISALLOW_CREATE_WINDOWS
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_TASK, conditional = true)
    @SupportsCoexistence
    public void setLockTaskPackages(@Nullable ComponentName admin, @NonNull String[] packages)
            throws SecurityException {
        throwIfParentInstance("setLockTaskPackages");
        if (mService != null) {
            try {
                mService.setLockTaskPackages(admin, mContext.getPackageName(),  packages);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the list of packages allowed to start the lock task mode.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, the returned policy will be the
     * current resolved policy rather than the policy set by the calling admin.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK}.
     * @see #isAffiliatedUser
     * @see #setLockTaskPackages
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_TASK, conditional = true)
    public @NonNull String[] getLockTaskPackages(@Nullable ComponentName admin) {
        throwIfParentInstance("getLockTaskPackages");
        if (mService != null) {
            try {
                return mService.getLockTaskPackages(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return new String[0];
    }

    /**
     * This function lets the caller know whether the given component is allowed to start the
     * lock task mode.
     * @param pkg The package to check
     */
    public boolean isLockTaskPermitted(String pkg) {
        throwIfParentInstance("isLockTaskPermitted");
        if (mService != null) {
            try {
                return mService.isLockTaskPermitted(pkg);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets which system features are enabled when the device runs in lock task mode. This method
     * doesn't affect the features when lock task mode is inactive. Any system features not included
     * in {@code flags} are implicitly disabled when calling this method. By default, only
     * {@link #LOCK_TASK_FEATURE_GLOBAL_ACTIONS} is enabled; all the other features are disabled. To
     * disable the global actions dialog, call this method omitting
     * {@link #LOCK_TASK_FEATURE_GLOBAL_ACTIONS}.
     *
     * <p>This method can only be called by the device owner, a profile owner of an affiliated
     * user or profile, or the profile owner when no device owner is set or holders of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK}. See
     * {@link #isAffiliatedUser}.
     * Any features set using this method are cleared if the user becomes unaffiliated.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the lock task features
     * policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String, Bundle,
     * TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier {@link DevicePolicyIdentifiers#LOCK_TASK_POLICY}
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, lock task features and lock task
     * packages are bundled as one policy. A failure to apply one will result in a failure to apply
     * the other.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param flags The system features enabled during lock task mode.
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK}.
     * @see #isAffiliatedUser
     **/
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_TASK, conditional = true)
    @SupportsCoexistence
    public void setLockTaskFeatures(@Nullable ComponentName admin, @LockTaskFeature int flags) {
        throwIfParentInstance("setLockTaskFeatures");
        if (mService != null) {
            try {
                mService.setLockTaskFeatures(admin, mContext.getPackageName(), flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets which system features are enabled for LockTask mode.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, the returned policy will be the
     * current resolved policy rather than the policy set by the calling admin.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @return bitfield of flags. See {@link #setLockTaskFeatures(ComponentName, int)} for a list.
     * @throws SecurityException if {@code admin} is not the device owner, the profile owner of an
     * affiliated user or profile, or the profile owner when no device owner is set or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_LOCK_TASK}.
     * @see #isAffiliatedUser
     * @see #setLockTaskFeatures
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_LOCK_TASK, conditional = true)
    public @LockTaskFeature int getLockTaskFeatures(@Nullable ComponentName admin) {
        throwIfParentInstance("getLockTaskFeatures");
        if (mService != null) {
            try {
                return mService.getLockTaskFeatures(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Sets whether preferential network service is enabled.
     * For example, an organization can have a deal/agreement with a carrier that all of
     * the work data from its employees’ devices will be sent via a network service dedicated
     * for enterprise use.
     *
     * An example of a supported preferential network service is the Enterprise
     * slice on 5G networks. For devices on 4G networks, the profile owner needs to additionally
     * configure enterprise APN to set up data call for the preferential network service.
     * These APNs can be added using {@link #addOverrideApn}.
     *
     * By default, preferential network service is disabled on the work profile and
     * fully managed devices, on supported carriers and devices.
     * Admins can explicitly enable it with this API.
     *
     * <p> This method enables preferential network service with a default configuration.
     * To fine-tune the configuration, use {@link #setPreferentialNetworkServiceConfigs) instead.
     * <p> Before Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * this method can be called by the profile owner of a managed profile.
     * <p> Starting from Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * This method can be called by the profile owner of a managed profile
     * or device owner.
     *
     * @param enabled whether preferential network service should be enabled.
     * @throws SecurityException if the caller is not the profile owner or device owner.
     **/
    public void setPreferentialNetworkServiceEnabled(boolean enabled) {
        throwIfParentInstance("setPreferentialNetworkServiceEnabled");
        PreferentialNetworkServiceConfig.Builder configBuilder =
                new PreferentialNetworkServiceConfig.Builder();
        configBuilder.setEnabled(enabled);
        if (enabled) {
            configBuilder.setNetworkId(NET_ENTERPRISE_ID_1);
        }
        setPreferentialNetworkServiceConfigs(List.of(configBuilder.build()));
    }

    /**
     * Indicates whether preferential network service is enabled.
     *
     * <p> Before Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * This method can be called by the profile owner of a managed profile.
     * <p> Starting from Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * This method can be called by the profile owner of a managed profile
     * or device owner.
     *
     * @return whether preferential network service is enabled.
     * @throws SecurityException if the caller is not the profile owner or device owner.
     */
    public boolean isPreferentialNetworkServiceEnabled() {
        throwIfParentInstance("isPreferentialNetworkServiceEnabled");
        return getPreferentialNetworkServiceConfigs().stream().anyMatch(c -> c.isEnabled());
    }

    /**
     * Sets preferential network configurations.
     * {@see PreferentialNetworkServiceConfig}
     *
     * An example of a supported preferential network service is the Enterprise
     * slice on 5G networks. For devices on 4G networks, the profile owner needs to additionally
     * configure enterprise APN to set up data call for the preferential network service.
     * These APNs can be added using {@link #addOverrideApn}.
     *
     * By default, preferential network service is disabled on the work profile and fully managed
     * devices, on supported carriers and devices. Admins can explicitly enable it with this API.
     * If admin wants to have multiple enterprise slices,
     * it can be configured by passing list of {@link PreferentialNetworkServiceConfig} objects.
     *
     * @param preferentialNetworkServiceConfigs list of preferential network configurations.
     * @throws SecurityException if the caller is not the profile owner or device owner.
     **/
    public void setPreferentialNetworkServiceConfigs(
            @NonNull List<PreferentialNetworkServiceConfig> preferentialNetworkServiceConfigs) {
        throwIfParentInstance("setPreferentialNetworkServiceConfigs");
        if (mService == null) {
            return;
        }
        try {
            mService.setPreferentialNetworkServiceConfigs(preferentialNetworkServiceConfigs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get preferential network configuration
     * {@see PreferentialNetworkServiceConfig}
     *
     * @return preferential network configuration.
     * @throws SecurityException if the caller is not the profile owner or device owner.
     */
    public @NonNull List<PreferentialNetworkServiceConfig> getPreferentialNetworkServiceConfigs() {
        throwIfParentInstance("getPreferentialNetworkServiceConfigs");
        if (mService == null) {
            return List.of(PreferentialNetworkServiceConfig.DEFAULT);
        }
        try {
            return mService.getPreferentialNetworkServiceConfigs();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This method is mostly deprecated.
     * Most of the settings that still have an effect have dedicated setter methods or user
     * restrictions. See individual settings for details.
     * <p>
     * Called by device owner to update {@link android.provider.Settings.Global} settings.
     * Validation that the value of the setting is in the correct form for the setting type should
     * be performed by the caller.
     * <p>
     * The settings that can be updated with this method are:
     * <ul>
     * <li>{@link android.provider.Settings.Global#ADB_ENABLED} : use
     * {@link UserManager#DISALLOW_DEBUGGING_FEATURES} instead to restrict users from enabling
     * debugging features and this setting to turn adb on.</li>
     * <li>{@link android.provider.Settings.Global#USB_MASS_STORAGE_ENABLED}</li>
     * <li>{@link android.provider.Settings.Global#STAY_ON_WHILE_PLUGGED_IN} This setting is only
     * available from {@link android.os.Build.VERSION_CODES#M} onwards and can only be set if
     * {@link #setMaximumTimeToLock} is not used to set a timeout.</li>
     * <li>{@link android.provider.Settings.Global#WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN}</li> This
     * setting is only available from {@link android.os.Build.VERSION_CODES#M} onwards.</li>
     * </ul>
     * <p>
     * The following settings used to be supported, but can be controlled in other ways:
     * <ul>
     * <li>{@link android.provider.Settings.Global#AUTO_TIME} : Use {@link #setAutoTimeEnabled} and
     * {@link UserManager#DISALLOW_CONFIG_DATE_TIME} instead.</li>
     * <li>{@link android.provider.Settings.Global#AUTO_TIME_ZONE} : Use
     * {@link #setAutoTimeZoneEnabled} and {@link UserManager#DISALLOW_CONFIG_DATE_TIME}
     * instead.</li>
     * <li>{@link android.provider.Settings.Global#DATA_ROAMING} : Use
     * {@link UserManager#DISALLOW_DATA_ROAMING} instead.</li>
     * </ul>
     * <p>
     * Changing the following settings has no effect as of {@link android.os.Build.VERSION_CODES#M}:
     * <ul>
     * <li>{@link android.provider.Settings.Global#BLUETOOTH_ON}. Use
     * {@link android.bluetooth.BluetoothAdapter#enable()} and
     * {@link android.bluetooth.BluetoothAdapter#disable()} instead.</li>
     * <li>{@link android.provider.Settings.Global#DEVELOPMENT_SETTINGS_ENABLED}</li>
     * <li>{@link android.provider.Settings.Global#MODE_RINGER}. Use
     * {@link android.media.AudioManager#setRingerMode(int)} instead.</li>
     * <li>{@link android.provider.Settings.Global#NETWORK_PREFERENCE}</li>
     * <li>{@link android.provider.Settings.Global#WIFI_ON}. Use
     * {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)} instead.</li>
     * <li>{@link android.provider.Settings.Global#WIFI_SLEEP_POLICY}. No longer has effect.</li>
     * </ul>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setGlobalSetting(@NonNull ComponentName admin, String setting, String value) {
        throwIfParentInstance("setGlobalSetting");
        if (mService != null) {
            try {
                mService.setGlobalSetting(admin, setting, value);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /** @hide */
    @StringDef({
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS,
            Settings.System.SCREEN_OFF_TIMEOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SystemSettingsWhitelist {}

    /**
     * Called by a device or profile owner to update {@link android.provider.Settings.System}
     * settings. Validation that the value of the setting is in the correct form for the setting
     * type should be performed by the caller.
     * <p>
     * The settings that can be updated by a device owner or profile owner of secondary user with
     * this method are:
     * <ul>
     * <li>{@link android.provider.Settings.System#SCREEN_BRIGHTNESS}</li>
     * <li>{@link android.provider.Settings.System#SCREEN_BRIGHTNESS_MODE}</li>
     * <li>{@link android.provider.Settings.System#SCREEN_OFF_TIMEOUT}</li>
     * </ul>
     * <p>
     * Starting from Android {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}, a
     * profile owner on an organization-owned device can call this method on the parent
     * {@link DevicePolicyManager} instance returned by
     * {@link #getParentProfileInstance(ComponentName)} to set system settings on the parent user.
     *
     * @see android.provider.Settings.System#SCREEN_OFF_TIMEOUT
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setSystemSetting(@NonNull ComponentName admin,
            @NonNull @SystemSettingsWhitelist String setting, String value) {
        if (mService != null) {
            try {
                mService.setSystemSetting(admin, setting, value, mParentInstance);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device owner or a profile owner of an organization-owned managed profile to
     * control whether the user can change networks configured by the admin. When this lockdown is
     * enabled, the user can still configure and connect to other Wi-Fi networks, or use other Wi-Fi
     * capabilities such as tethering.
     * <p>
     * WiFi network configuration lockdown is controlled by a global settings
     * {@link android.provider.Settings.Global#WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN} and calling
     * this API effectively modifies the global settings. Previously device owners can also
     * control this directly via {@link #setGlobalSetting} but they are recommended to switch
     * to this API.
     *
     * @param admin             admin Which {@link DeviceAdminReceiver} this request is associated
     *                          with. Null if the caller is not a device admin.
     * @param lockdown Whether the admin configured networks should be unmodifiable by the
     *                          user.
     * @throws SecurityException if caller is not a device owner or a profile owner of an
     *                           organization-owned managed profile.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIFI, conditional = true)
    public void setConfiguredNetworksLockdownState(
            @Nullable ComponentName admin, boolean lockdown) {
        throwIfParentInstance("setConfiguredNetworksLockdownState");
        if (mService != null) {
            try {
                mService.setConfiguredNetworksLockdownState(
                        admin, mContext.getPackageName(), lockdown);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device owner or a profile owner of an organization-owned managed profile to
     * determine whether the user is prevented from modifying networks configured by the admin.
     *
     * @param admin             admin Which {@link DeviceAdminReceiver} this request is associated
     *                          with.
     * @throws SecurityException if caller is not a device owner or a profile owner of an
     *                           organization-owned managed profile.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIFI, conditional = true)
    public boolean hasLockdownAdminConfiguredNetworks(@Nullable ComponentName admin) {
        throwIfParentInstance("hasLockdownAdminConfiguredNetworks");
        if (mService != null) {
            try {
                return mService.hasLockdownAdminConfiguredNetworks(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner or a profile owner of an organization-owned managed
     * profile to set the system wall clock time. This only takes effect if called when
     * {@link android.provider.Settings.Global#AUTO_TIME} is 0, otherwise {@code false}
     * will be returned.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param millis time in milliseconds since the Epoch
     * @return {@code true} if set time succeeded, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner
     * of an organization-owned managed profile.
     */
    @RequiresPermission(value = SET_TIME, conditional = true)
    public boolean setTime(@Nullable ComponentName admin, long millis) {
        throwIfParentInstance("setTime");
        if (mService != null) {
            try {
                return mService.setTime(admin, mContext.getPackageName(), millis);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by a device owner or a profile owner of an organization-owned managed
     * profile to set the system's persistent default time zone. This only takes
     * effect if called when {@link android.provider.Settings.Global#AUTO_TIME_ZONE}
     * is 0, otherwise {@code false} will be returned.
     *
     * @see android.app.AlarmManager#setTimeZone(String)
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param timeZone one of the Olson ids from the list returned by
     *     {@link java.util.TimeZone#getAvailableIDs}
     * @return {@code true} if set timezone succeeded, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner
     * of an organization-owned managed profile.
     */
    @RequiresPermission(value = SET_TIME_ZONE, conditional = true)
    public boolean setTimeZone(@Nullable ComponentName admin, String timeZone) {
        throwIfParentInstance("setTimeZone");
        if (mService != null) {
            try {
                return mService.setTimeZone(admin, mContext.getPackageName(), timeZone);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device owners to set the user's global location setting.
     *
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @param locationEnabled whether location should be enabled or disabled. <b>Note: </b> on
     * {@link android.content.pm.PackageManager#FEATURE_AUTOMOTIVE automotive builds}, calls to
     * disable will be ignored.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setLocationEnabled(@NonNull ComponentName admin, boolean locationEnabled) {
        throwIfParentInstance("setLocationEnabled");
        if (mService != null) {
            try {
                mService.setLocationEnabled(admin, locationEnabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * This method is mostly deprecated.
     * Most of the settings that still have an effect have dedicated setter methods
     * (e.g. {@link #setLocationEnabled}) or user restrictions.
     * <p>
     *
     * Called by profile or device owners to update {@link android.provider.Settings.Secure}
     * settings. Validation that the value of the setting is in the correct form for the setting
     * type should be performed by the caller.
     * <p>
     * The settings that can be updated by a profile or device owner with this method are:
     * <ul>
     * <li>{@link android.provider.Settings.Secure#DEFAULT_INPUT_METHOD}</li>
     * <li>{@link android.provider.Settings.Secure#SKIP_FIRST_USE_HINTS}</li>
     * </ul>
     * <p>
     * A device owner can additionally update the following settings:
     * <ul>
     * <li>{@link android.provider.Settings.Secure#LOCATION_MODE}, but see note below.</li>
     * </ul>
     *
     * <strong>Note: Starting from Android O, apps should no longer call this method with the
     * setting {@link android.provider.Settings.Secure#INSTALL_NON_MARKET_APPS}, which is
     * deprecated. Instead, device owners or profile owners should use the restriction
     * {@link UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES}.
     * If any app targeting {@link android.os.Build.VERSION_CODES#O} or higher calls this method
     * with {@link android.provider.Settings.Secure#INSTALL_NON_MARKET_APPS},
     * an {@link UnsupportedOperationException} is thrown.
     *
     * Starting from Android Q, the device and profile owner can also call
     * {@link UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY} to restrict unknown sources for
     * all users.
     * </strong>
     *
     * <strong>Note: Starting from Android R, apps should no longer call this method with the
     * setting {@link android.provider.Settings.Secure#LOCATION_MODE}, which is deprecated. Instead,
     * device owners should call {@link #setLocationEnabled(ComponentName, boolean)}. This will be
     * enforced for all apps targeting Android R or above.
     * </strong>
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param setting The name of the setting to update.
     * @param value The value to update the setting to.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setSecureSetting(@NonNull ComponentName admin, String setting, String value) {
        throwIfParentInstance("setSecureSetting");

        if (mService != null) {
            try {
                mService.setSecureSetting(admin, setting, value);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Designates a specific service component as the provider for making permission requests of a
     * local or remote administrator of the user.
     * <p/>
     * Only a device owner or profile owner can designate the restrictions provider.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param provider The component name of the service that implements
     *            {@link RestrictionsReceiver}. If this param is null, it removes the restrictions
     *            provider previously assigned.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setRestrictionsProvider(@NonNull ComponentName admin,
            @Nullable ComponentName provider) {
        throwIfParentInstance("setRestrictionsProvider");
        if (mService != null) {
            try {
                mService.setRestrictionsProvider(admin, provider);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by profile or device owners to set the global volume mute on or off.
     * This has no effect when set on a managed profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param on {@code true} to mute global volume, {@code false} to turn mute off.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setMasterVolumeMuted(@NonNull ComponentName admin, boolean on) {
        throwIfParentInstance("setMasterVolumeMuted");
        if (mService != null) {
            try {
                mService.setMasterVolumeMuted(admin, on);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by profile or device owners to check whether the global volume mute is on or off.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return {@code true} if global volume is muted, {@code false} if it's not.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean isMasterVolumeMuted(@NonNull ComponentName admin) {
        throwIfParentInstance("isMasterVolumeMuted");
        if (mService != null) {
            try {
                return mService.isMasterVolumeMuted(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Change whether a user can uninstall a package. This function can be called by a device owner,
     * profile owner, or by a delegate given the {@link #DELEGATION_BLOCK_UNINSTALL} scope via
     * {@link #setDelegatedScopes} or holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_APPS_CONTROL}.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the set uninstall blocked
     * policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier
     * {@link DevicePolicyIdentifiers#PACKAGE_UNINSTALL_BLOCKED_POLICY}
     * <li> The additional policy params bundle, which contains
     * {@link PolicyUpdateReceiver#EXTRA_PACKAGE_NAME} the package name the policy applies to
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param packageName package to change.
     * @param uninstallBlocked true if the user shouldn't be able to uninstall the package.
     * @throws SecurityException if {@code admin} is not a device or profile owner or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_APPS_CONTROL}.
     * @see #setDelegatedScopes
     * @see #DELEGATION_BLOCK_UNINSTALL
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_APPS_CONTROL, conditional = true)
    @SupportsCoexistence
    public void setUninstallBlocked(@Nullable ComponentName admin, String packageName,
            boolean uninstallBlocked) {
        throwIfParentInstance("setUninstallBlocked");
        if (mService != null) {
            try {
                mService.setUninstallBlocked(admin, mContext.getPackageName(), packageName,
                    uninstallBlocked);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Check whether the user has been blocked by device policy from uninstalling a package.
     * Requires the caller to be the profile owner if checking a specific admin's policy.
     * <p>
     * <strong>Note:</strong> Starting from {@link android.os.Build.VERSION_CODES#LOLLIPOP_MR1}, the
     * behavior of this API is changed such that passing {@code null} as the {@code admin} parameter
     * will return if any admin has blocked the uninstallation. Before L MR1, passing {@code null}
     * will cause a NullPointerException to be raised.
     * <p>
     * <strong>Note:</strong> If your app targets Android 11 (API level 30) or higher,
     * this method returns a filtered result. Learn more about how to
     * <a href="/training/basics/intents/package-visibility">manage package visibility</a>.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, the returned policy will be the
     * current resolved policy rather than the policy set by the calling admin.
     *
     * @param admin The name of the admin component whose blocking policy will be checked, or
     *            {@code null} to check whether any admin has blocked the uninstallation. Starting
     *              from {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} admin will be
     *              ignored and assumed {@code null}.
     * @param packageName package to check.
     * @return true if uninstallation is blocked and the given package is visible to you, false
     *         otherwise if uninstallation isn't blocked or the given package isn't visible to you.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public boolean isUninstallBlocked(@Nullable ComponentName admin, String packageName) {
        throwIfParentInstance("isUninstallBlocked");
        if (mService != null) {
            try {
                return mService.isUninstallBlocked(packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by the profile owner of a managed profile or a holder of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILE_INTERACTION} to enable
     * widget providers from a given package to be available in the parent profile. As a result the
     * user will be able to add widgets from the allowlisted package running under the profile to a
     * widget host which runs under the parent profile, for example the home screen. Note that a
     * package may have zero or more provider components, where each component provides a different
     * widget type.
     * <p>
     * <strong>Note:</strong> By default no widget provider package is allowlisted.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param packageName The package from which widget providers are allowlisted.
     * @return Whether the package was added.
     * @throws SecurityException if {@code admin} is not a profile owner and not a holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILE_INTERACTION}.
     * @see #removeCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #getCrossProfileWidgetProviders(android.content.ComponentName)
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_PROFILE_INTERACTION, conditional = true)
    public boolean addCrossProfileWidgetProvider(@Nullable ComponentName admin,
            String packageName) {
        throwIfParentInstance("addCrossProfileWidgetProvider");
        if (mService != null) {
            try {
                return mService.addCrossProfileWidgetProvider(admin,
                        mContext.getPackageName(), packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by the profile owner of a managed profile or a holder of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILE_INTERACTION} to disable
     * widget providers from a given package to be available in the parent profile. For this method
     * to take effect the package should have been added via
     * {@link #addCrossProfileWidgetProvider( android.content.ComponentName, String)}.
     * <p>
     * <strong>Note:</strong> By default no widget provider package is allowlisted.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param packageName The package from which widget providers are no longer allowlisted.
     * @return Whether the package was removed.
     * @throws SecurityException if {@code admin} is not a profile owner and not a holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILE_INTERACTION}.
     * @see #addCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #getCrossProfileWidgetProviders(android.content.ComponentName)
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_PROFILE_INTERACTION, conditional = true)
    public boolean removeCrossProfileWidgetProvider(@Nullable ComponentName admin,
            String packageName) {
        throwIfParentInstance("removeCrossProfileWidgetProvider");
        if (mService != null) {
            try {
                return mService.removeCrossProfileWidgetProvider(admin, mContext.getPackageName(),
                        packageName);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by the profile owner of a managed profile or a holder of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILE_INTERACTION} to query
     * providers from which packages are available in the parent profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @return The allowlisted package list.
     * @see #addCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @see #removeCrossProfileWidgetProvider(android.content.ComponentName, String)
     * @throws SecurityException if {@code admin} is not a profile owner and not a holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_PROFILE_INTERACTION}.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_PROFILE_INTERACTION, conditional = true)
    public @NonNull List<String> getCrossProfileWidgetProviders(@Nullable ComponentName admin) {
        throwIfParentInstance("getCrossProfileWidgetProviders");
        if (mService != null) {
            try {
                List<String> providers = mService.getCrossProfileWidgetProviders(admin,
                        mContext.getPackageName());
                if (providers != null) {
                    return providers;
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Called by profile or device owners to set the user's photo.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param icon the bitmap to set as the photo.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public void setUserIcon(@NonNull ComponentName admin, Bitmap icon) {
        throwIfParentInstance("setUserIcon");
        try {
            mService.setUserIcon(admin, icon);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owners or profile owners of an organization-owned managed profile to set
     * a local system update policy. When a new policy is set,
     * {@link #ACTION_SYSTEM_UPDATE_POLICY_CHANGED} is broadcast.
     * <p>
     * If the supplied system update policy has freeze periods set but the freeze periods do not
     * meet 90-day maximum length or 60-day minimum separation requirement set out in
     * {@link SystemUpdatePolicy#setFreezePeriods},
     * {@link SystemUpdatePolicy.ValidationFailedException} will the thrown. Note that the system
     * keeps a record of freeze periods the device experienced previously, and combines them with
     * the new freeze periods to be set when checking the maximum freeze length and minimum freeze
     * separation constraints. As a result, freeze periods that passed validation during
     * {@link SystemUpdatePolicy#setFreezePeriods} might fail the additional checks here due to
     * the freeze period history. If this is causing issues during development,
     * {@code adb shell dpm clear-freeze-period-record} can be used to clear the record.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. All
     *            components in the package can set system update policies and the most
     *            recent policy takes effect. This should be null if the caller is not a device
     *              admin.
     * @param policy the new policy, or {@code null} to clear the current policy.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner of an
     *      organization-owned managed profile, or the caller is not permitted to set this policy
     * @throws IllegalArgumentException if the policy type or maintenance window is not valid.
     * @throws SystemUpdatePolicy.ValidationFailedException if the policy's freeze period does not
     *             meet the requirement.
     * @see SystemUpdatePolicy
     * @see SystemUpdatePolicy#setFreezePeriods(List)
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_SYSTEM_UPDATES, conditional = true)
    public void setSystemUpdatePolicy(@NonNull ComponentName admin, SystemUpdatePolicy policy) {
        throwIfParentInstance("setSystemUpdatePolicy");
        if (mService != null) {
            try {
                mService.setSystemUpdatePolicy(admin, mContext.getPackageName(), policy);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieve a local system update policy set previously by {@link #setSystemUpdatePolicy}.
     *
     * @return The current policy object, or {@code null} if no policy is set.
     */
    public @Nullable SystemUpdatePolicy getSystemUpdatePolicy() {
        throwIfParentInstance("getSystemUpdatePolicy");
        if (mService != null) {
            try {
                return mService.getSystemUpdatePolicy();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Reset record of previous system update freeze period the device went through.
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.CLEAR_FREEZE_PERIOD)
    public void clearSystemUpdatePolicyFreezePeriodRecord() {
        throwIfParentInstance("clearSystemUpdatePolicyFreezePeriodRecord");
        if (mService == null) {
            return;
        }
        try {
            mService.clearSystemUpdatePolicyFreezePeriodRecord();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner or profile owner of secondary users that is affiliated with the
     * device to disable the keyguard altogether.
     * <p>
     * Setting the keyguard to disabled has the same effect as choosing "None" as the screen lock
     * type. However, this call has no effect if a password, pin or pattern is currently set. If a
     * password, pin or pattern is set after the keyguard was disabled, the keyguard stops being
     * disabled.
     *
     * <p>
     * As of {@link android.os.Build.VERSION_CODES#P}, this call also dismisses the
     * keyguard if it is currently shown.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param disabled {@code true} disables the keyguard, {@code false} reenables it.
     * @return {@code false} if attempting to disable the keyguard while a lock password was in
     *         place. {@code true} otherwise.
     * @throws SecurityException if {@code admin} is not the device owner, or a profile owner of
     * secondary user that is affiliated with the device.
     * @see #isAffiliatedUser
     * @see #getSecondaryUsers
     */
    public boolean setKeyguardDisabled(@NonNull ComponentName admin, boolean disabled) {
        throwIfParentInstance("setKeyguardDisabled");
        try {
            return mService.setKeyguardDisabled(admin, disabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner or profile owner of secondary users that is affiliated with the
     * device to disable the status bar. Disabling the status bar blocks notifications and quick
     * settings.
     * <p>
     * <strong>Note:</strong> This method has no effect for LockTask mode. The behavior of the
     * status bar in LockTask mode can be configured with
     * {@link #setLockTaskFeatures(ComponentName, int)}. Calls to this method when the device is in
     * LockTask mode will be registered, but will only take effect when the device leaves LockTask
     * mode.
     *
     * <p>This policy does not have any effect while on the lock screen, where the status bar will
     * not be disabled. Using LockTask instead of this method is recommended.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param disabled {@code true} disables the status bar, {@code false} reenables it.
     * @return {@code false} if attempting to disable the status bar failed. {@code true} otherwise.
     * @throws SecurityException if {@code admin} is not the device owner, or a profile owner of
     * secondary user that is affiliated with the device.
     * @see #isAffiliatedUser
     * @see #getSecondaryUsers
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_STATUS_BAR, conditional = true)
    public boolean setStatusBarDisabled(@Nullable ComponentName admin, boolean disabled) {
        throwIfParentInstance("setStatusBarDisabled");
        try {
            return mService.setStatusBarDisabled(admin, mContext.getPackageName(), disabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the status bar is disabled/enabled, see {@link #setStatusBarDisabled}.
     *
     * <p>Callable by device owner or profile owner of secondary users that is affiliated with the
     * device owner.
     *
     * <p>This policy has no effect in LockTask mode. The behavior of the
     * status bar in LockTask mode can be configured with
     * {@link #setLockTaskFeatures(ComponentName, int)}.
     *
     * <p>This policy also does not have any effect while on the lock screen, where the status bar
     * will not be disabled.
     *
     * @throws SecurityException if the caller is not the device owner, or a profile owner of
     * secondary user that is affiliated with the device.
     * @see #isAffiliatedUser
     * @see #getSecondaryUsers
     */
    public boolean isStatusBarDisabled() {
        throwIfParentInstance("isStatusBarDisabled");
        try {
            return mService.isStatusBarDisabled(mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system update service to notify device and profile owners of pending system
     * updates.
     *
     * This method should only be used when it is unknown whether the pending system
     * update is a security patch. Otherwise, use
     * {@link #notifyPendingSystemUpdate(long, boolean)}.
     *
     * @param updateReceivedTime The time as given by {@link System#currentTimeMillis()}
     *         indicating when the current pending update was first available. {@code -1} if no
     *         update is available.
     * @see #notifyPendingSystemUpdate(long, boolean)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NOTIFY_PENDING_SYSTEM_UPDATE)
    public void notifyPendingSystemUpdate(long updateReceivedTime) {
        throwIfParentInstance("notifyPendingSystemUpdate");
        if (mService != null) {
            try {
                mService.notifyPendingSystemUpdate(SystemUpdateInfo.of(updateReceivedTime));
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by the system update service to notify device and profile owners of pending system
     * updates.
     *
     * This method should be used instead of {@link #notifyPendingSystemUpdate(long)}
     * when it is known whether the pending system update is a security patch.
     *
     * @param updateReceivedTime The time as given by {@link System#currentTimeMillis()}
     *         indicating when the current pending update was first available. {@code -1} if no
     *         update is available.
     * @param isSecurityPatch {@code true} if this system update is purely a security patch;
     *         {@code false} if not.
     * @see #notifyPendingSystemUpdate(long)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NOTIFY_PENDING_SYSTEM_UPDATE)
    public void notifyPendingSystemUpdate(long updateReceivedTime, boolean isSecurityPatch) {
        throwIfParentInstance("notifyPendingSystemUpdate");
        if (mService != null) {
            try {
                mService.notifyPendingSystemUpdate(SystemUpdateInfo.of(updateReceivedTime,
                        isSecurityPatch));
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Get information about a pending system update.
     *
     * Can be called by device or profile owners, and starting from Android
     * {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}, holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_QUERY_SYSTEM_UPDATES}.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @return Information about a pending system update or {@code null} if no update pending.
     * @throws SecurityException if {@code admin} is not a device, profile owner or holders of
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_QUERY_SYSTEM_UPDATES}.
     * @see DeviceAdminReceiver#onSystemUpdatePending(Context, Intent, long)
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_QUERY_SYSTEM_UPDATES, conditional = true)
    @SuppressLint("RequiresPermission")
    public @Nullable SystemUpdateInfo getPendingSystemUpdate(@Nullable ComponentName admin) {
        throwIfParentInstance("getPendingSystemUpdate");
        try {
            return mService.getPendingSystemUpdate(admin, mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Set the default response for future runtime permission requests by applications. This
     * function can be called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_PERMISSION_GRANT} scope via {@link #setDelegatedScopes}.
     * The policy can allow for normal operation which prompts the user to grant a permission, or
     * can allow automatic granting or denying of runtime permission requests by an application.
     * This also applies to new permissions declared by app updates. When a permission is denied or
     * granted this way, the effect is equivalent to setting the permission * grant state via
     * {@link #setPermissionGrantState}.
     * <p/>
     * As this policy only acts on runtime permission requests, it only applies to applications
     * built with a {@code targetSdkVersion} of {@link android.os.Build.VERSION_CODES#M} or later.
     *
     * <p>
     * NOTE: On devices running {@link android.os.Build.VERSION_CODES#S} and above, an auto-grant
     * policy will not apply to certain sensors-related permissions on some configurations.
     * See {@link #setPermissionGrantState(ComponentName, String, String, int)} for the list of
     * permissions affected, and the behavior change for managed profiles and fully-managed
     * devices.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @param policy One of the policy constants {@link #PERMISSION_POLICY_PROMPT},
     *            {@link #PERMISSION_POLICY_AUTO_GRANT} and {@link #PERMISSION_POLICY_AUTO_DENY}.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setPermissionGrantState
     * @see #setDelegatedScopes
     * @see #DELEGATION_PERMISSION_GRANT
     */
    public void setPermissionPolicy(@NonNull ComponentName admin, int policy) {
        throwIfParentInstance("setPermissionPolicy");
        try {
            mService.setPermissionPolicy(admin, mContext.getPackageName(), policy);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current runtime permission policy set by the device or profile owner. The
     * default is {@link #PERMISSION_POLICY_PROMPT}.
     *
     * @param admin Which profile or device owner this request is associated with.
     * @return the current policy for future permission requests.
     */
    public int getPermissionPolicy(ComponentName admin) {
        throwIfParentInstance("getPermissionPolicy");
        try {
            return mService.getPermissionPolicy(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the grant state of a runtime permission for a specific application. The state can be
     * {@link #PERMISSION_GRANT_STATE_DEFAULT default} in which a user can manage it through the UI,
     * {@link #PERMISSION_GRANT_STATE_DENIED denied}, in which the permission is denied and the user
     * cannot manage it through the UI, and {@link #PERMISSION_GRANT_STATE_GRANTED granted} in which
     * the permission is granted and the user cannot manage it through the UI. This method can only
     * be called by a profile owner, device owner, or a delegate given the
     * {@link #DELEGATION_PERMISSION_GRANT} scope via {@link #setDelegatedScopes}.
     * <p/>
     * Note that user cannot manage other permissions in the affected group through the UI
     * either and their granted state will be kept as the current value. Thus, it's recommended that
     * you set the grant state of all the permissions in the affected group.
     * <p/>
     * Setting the grant state to {@link #PERMISSION_GRANT_STATE_DEFAULT default} does not revoke
     * the permission. It retains the previous grant, if any.
     * <p/>
     * Device admins with a {@code targetSdkVersion} &lt; {@link android.os.Build.VERSION_CODES#Q}
     * cannot grant and revoke permissions for applications built with a {@code targetSdkVersion}
     * &lt; {@link android.os.Build.VERSION_CODES#M}.
     * <p/>
     * Admins with a {@code targetSdkVersion} &ge; {@link android.os.Build.VERSION_CODES#Q} can
     * grant and revoke permissions of all apps. Similar to the user revoking a permission from a
     * application built with a {@code targetSdkVersion} &lt;
     * {@link android.os.Build.VERSION_CODES#M} the app-op matching the permission is set to
     * {@link android.app.AppOpsManager#MODE_IGNORED}, but the permission stays granted.
     * <p>
     * NOTE: On devices running {@link android.os.Build.VERSION_CODES#S} and above, control over
     * the following, sensors-related, permissions is restricted:
     * <ul>
     *    <li>Manifest.permission.ACCESS_FINE_LOCATION</li>
     *    <li>Manifest.permission.ACCESS_BACKGROUND_LOCATION</li>
     *    <li>Manifest.permission.ACCESS_COARSE_LOCATION</li>
     *    <li>Manifest.permission.CAMERA</li>
     *    <li>Manifest.permission.RECORD_AUDIO</li>
     *    <li>Manifest.permission.RECORD_BACKGROUND_AUDIO</li>
     *    <li>Manifest.permission.ACTIVITY_RECOGNITION</li>
     *    <li>Manifest.permission.BODY_SENSORS</li>
     * </ul>
     * <p>
     * A profile owner may not grant these permissions (i.e. call this method with any of the
     * permissions listed above and {@code grantState} of {@code #PERMISSION_GRANT_STATE_GRANTED}),
     * but may deny them.
     * <p>
     * A device owner, by default, may continue granting these permissions. However, for increased
     * user control, the admin may opt out of controlling grants for these permissions by including
     * {@link #EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT} in the provisioning parameters.
     * In that case the device owner's control will be limited to denying these permissions.
     * <p>
     * When sensor-related permissions aren't grantable due to the above cases, calling this method
     * to grant these permissions will silently fail, if device admins are built with
     * {@code targetSdkVersion} &lt; {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}. If
     * they are built with {@code targetSdkVersion} &gt;=
     * {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}, this method will throw a
     * {@link SecurityException}.
     * <p>
     * NOTE: On devices running {@link android.os.Build.VERSION_CODES#S} and above, control over
     * the following permissions are restricted for managed profile owners:
     * <ul>
     *    <li>Manifest.permission.READ_SMS</li>
     * </ul>
     * <p>
     * A managed profile owner may not grant these permissions (i.e. call this method with any of
     * the permissions listed above and {@code grantState} of
     * {@code #PERMISSION_GRANT_STATE_GRANTED}), but may deny them.
     * <p>
     * Attempts by the admin to grant these permissions, when the admin is restricted from doing
     * so, will be silently ignored (no exception will be thrown).
     *
     * Control over the following permissions are restricted for managed profile owners:
     * <ul>
     *  <li>Manifest.permission.READ_SMS</li>
     * </ul>
     * <p>
     * A managed profile owner may not grant these permissions (i.e. call this method with any of
     * the permissions listed above and {@code grantState} of
     * {@code #PERMISSION_GRANT_STATE_GRANTED}), but may deny them.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param packageName The application to grant or revoke a permission to.
     * @param permission The permission to grant or revoke.
     * @param grantState The permission grant state which is one of
     *            {@link #PERMISSION_GRANT_STATE_DENIED}, {@link #PERMISSION_GRANT_STATE_DEFAULT},
     *            {@link #PERMISSION_GRANT_STATE_GRANTED},
     * @return whether the permission was successfully granted or revoked.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #PERMISSION_GRANT_STATE_DENIED
     * @see #PERMISSION_GRANT_STATE_DEFAULT
     * @see #PERMISSION_GRANT_STATE_GRANTED
     * @see #setDelegatedScopes
     * @see #DELEGATION_PERMISSION_GRANT
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_RUNTIME_PERMISSIONS, conditional = true)
    @SupportsCoexistence
    public boolean setPermissionGrantState(@Nullable ComponentName admin,
            @NonNull String packageName, @NonNull String permission,
            @PermissionGrantState int grantState) {
        throwIfParentInstance("setPermissionGrantState");
        try {
            CompletableFuture<Boolean> result = new CompletableFuture<>();

            mService.setPermissionGrantState(admin, mContext.getPackageName(), packageName,
                    permission, grantState, new RemoteCallback((b) -> result.complete(b != null)));

            // Timeout
            BackgroundThread.getHandler().sendMessageDelayed(
                    obtainMessage(CompletableFuture::complete, result, false),
                    20_000);

            return result.get();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the current grant state of a runtime permission for a specific application. This
     * function can be called by a device owner, profile owner, or by a delegate given the
     * {@link #DELEGATION_PERMISSION_GRANT} scope via {@link #setDelegatedScopes}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param packageName The application to check the grant state for.
     * @param permission The permission to check for.
     * @return the current grant state specified by device policy. If admins have not set a grant
     *         has not set a grant state, the return value is
     *         {@link #PERMISSION_GRANT_STATE_DEFAULT}. This does not indicate whether or not the
     *         permission is currently granted for the package.
     *         <p/>
     *         If a grant state was set by the profile or device owner, then the return value will
     *         be one of {@link #PERMISSION_GRANT_STATE_DENIED} or
     *         {@link #PERMISSION_GRANT_STATE_GRANTED}, which indicates if the permission is
     *         currently denied or granted.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     * @see #setPermissionGrantState(ComponentName, String, String, int)
     * @see PackageManager#checkPermission(String, String)
     * @see #setDelegatedScopes
     * @see #DELEGATION_PERMISSION_GRANT
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_RUNTIME_PERMISSIONS, conditional = true)
    public @PermissionGrantState int getPermissionGrantState(@Nullable ComponentName admin,
            @NonNull String packageName, @NonNull String permission) {
        throwIfParentInstance("getPermissionGrantState");
        try {
            return mService.getPermissionGrantState(admin, mContext.getPackageName(), packageName,
                    permission);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether it is possible for the caller to initiate provisioning of a managed profile
     * or device, setting itself as the device or profile owner.
     *
     * @param action One of {@link #ACTION_PROVISION_MANAGED_DEVICE},
     * {@link #ACTION_PROVISION_MANAGED_PROFILE}.
     * @return whether provisioning a managed profile or device is possible.
     * @throws IllegalArgumentException if the supplied action is not valid.
     */
    public boolean isProvisioningAllowed(@NonNull String action) {
        throwIfParentInstance("isProvisioningAllowed");
        try {
            return mService.isProvisioningAllowed(action, mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether it is possible to initiate provisioning a managed device,
     * profile or user, setting the given package as owner.
     *
     * @param action One of {@link #ACTION_PROVISION_MANAGED_DEVICE},
     *        {@link #ACTION_PROVISION_MANAGED_PROFILE}
     * @param packageName The package of the component that would be set as device, user, or profile
     *        owner.
     * @return An int constant value indicating whether provisioning is allowed.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @ProvisioningPrecondition
    public int checkProvisioningPrecondition(
            @NonNull String action, @NonNull String packageName) {
        try {
            return mService.checkProvisioningPrecondition(action, packageName);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return if this user is a managed profile of another user. An admin can become the profile
     * owner of a managed profile with {@link #ACTION_PROVISION_MANAGED_PROFILE} and of a managed
     * user with {@link #createAndManageUser}
     * @param admin Which profile owner this request is associated with.
     * @return if this user is a managed profile of another user.
     */
    public boolean isManagedProfile(@NonNull ComponentName admin) {
        throwIfParentInstance("isManagedProfile");
        try {
            return mService.isManagedProfile(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner or profile owner on organization-owned device to get the MAC
     * address of the Wi-Fi device.
     *
     * NOTE: The MAC address returned here should only be used for inventory management and is
     * not likely to be the MAC address used by the device to connect to Wi-Fi networks: MAC
     * addresses used for scanning and connecting to Wi-Fi networks are randomized by default.
     * To get the randomized MAC address used, call
     * {@link android.net.wifi.WifiConfiguration#getRandomizedMacAddress}.
     *
     * @param admin Which admin this request is associated with. Null if the caller is not a device
     *              admin
     * @return the MAC address of the Wi-Fi device, or null when the information is not available.
     *         (For example, Wi-Fi hasn't been enabled, or the device doesn't support Wi-Fi.)
     *         <p>
     *         The address will be in the {@code XX:XX:XX:XX:XX:XX} format.
     * @throws SecurityException if {@code admin} is not permitted to get wifi mac addresses
     */
//    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIFI, conditional = true)
    public @Nullable String getWifiMacAddress(@Nullable ComponentName admin) {
        throwIfParentInstance("getWifiMacAddress");
        try {
            return mService.getWifiMacAddress(admin, mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner to reboot the device. If there is an ongoing call on the device,
     * throws an {@link IllegalStateException}.
     * @param admin Which device owner the request is associated with.
     * @throws IllegalStateException if device has an ongoing call.
     * @throws SecurityException if {@code admin} is not a device owner.
     * @see TelephonyManager#CALL_STATE_IDLE
     */
    public void reboot(@NonNull ComponentName admin) {
        throwIfParentInstance("reboot");
        try {
            mService.reboot(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device admin to set the short support message. This will be displayed to the user
     * in settings screens where functionality has been disabled by the admin. The message should be
     * limited to a short statement such as "This setting is disabled by your administrator. Contact
     * someone@example.com for support." If the message is longer than 200 characters it may be
     * truncated.
     * <p>
     * If the short support message needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this string accordingly.
     *
     * @see #setLongSupportMessage
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param message Short message to be displayed to the user in settings or null to clear the
     *            existing message.
     * @throws SecurityException if {@code admin} is not an active administrator.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_SUPPORT_MESSAGE, conditional = true)
    public void setShortSupportMessage(@Nullable ComponentName admin,
            @Nullable CharSequence message) {
        throwIfParentInstance("setShortSupportMessage");
        if (mService != null) {
            try {
                mService.setShortSupportMessage(admin, mContext.getPackageName(), message);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device admin or holder of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_SUPPORT_MESSAGE} to get the short
     * support message.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @return The message set by {@link #setShortSupportMessage(ComponentName, CharSequence)} or
     *         null if no message has been set.
     * @throws SecurityException if {@code admin} is not an active administrator and not a holder of
     * the permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_SUPPORT_MESSAGE}..
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_SUPPORT_MESSAGE, conditional = true)
    public CharSequence getShortSupportMessage(@Nullable ComponentName admin) {
        throwIfParentInstance("getShortSupportMessage");
        if (mService != null) {
            try {
                return mService.getShortSupportMessage(admin, mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by a device admin to set the long support message. This will be displayed to the user
     * in the device administrators settings screen. If the message is longer than 20000 characters
     * it may be truncated.
     * <p>
     * If the long support message needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this string accordingly.
     *
     * @see #setShortSupportMessage
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param message Long message to be displayed to the user in settings or null to clear the
     *            existing message.
     * @throws SecurityException if {@code admin} is not an active administrator.
     */
    public void setLongSupportMessage(@NonNull ComponentName admin,
            @Nullable CharSequence message) {
        throwIfParentInstance("setLongSupportMessage");
        if (mService != null) {
            try {
                mService.setLongSupportMessage(admin, message);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a device admin to get the long support message.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The message set by {@link #setLongSupportMessage(ComponentName, CharSequence)} or
     *         null if no message has been set.
     * @throws SecurityException if {@code admin} is not an active administrator.
     */
    public @Nullable CharSequence getLongSupportMessage(@NonNull ComponentName admin) {
        throwIfParentInstance("getLongSupportMessage");
        if (mService != null) {
            try {
                return mService.getLongSupportMessage(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by the system to get the short support message.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle user id the admin is running as.
     * @return The message set by {@link #setShortSupportMessage(ComponentName, CharSequence)}
     *
     * @hide
     */
    public @Nullable CharSequence getShortSupportMessageForUser(@NonNull ComponentName admin,
            int userHandle) {
        if (mService != null) {
            try {
                return mService.getShortSupportMessageForUser(admin, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }


    /**
     * Called by the system to get the long support message.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userHandle user id the admin is running as.
     * @return The message set by {@link #setLongSupportMessage(ComponentName, CharSequence)}
     *
     * @hide
     */
    public @Nullable CharSequence getLongSupportMessageForUser(
            @NonNull ComponentName admin, int userHandle) {
        if (mService != null) {
            try {
                return mService.getLongSupportMessageForUser(admin, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Called by the profile owner of a managed profile to obtain a {@link DevicePolicyManager}
     * whose calls act on the parent profile.
     *
     * <p>The following methods are supported for the parent instance, all other methods will
     * throw a SecurityException when called on the parent instance:
     * <ul>
     * <li>{@link #getPasswordQuality}</li>
     * <li>{@link #setPasswordQuality}</li>
     * <li>{@link #getPasswordMinimumLength}</li>
     * <li>{@link #setPasswordMinimumLength}</li>
     * <li>{@link #getPasswordMinimumUpperCase}</li>
     * <li>{@link #setPasswordMinimumUpperCase}</li>
     * <li>{@link #getPasswordMinimumLowerCase}</li>
     * <li>{@link #setPasswordMinimumLowerCase}</li>
     * <li>{@link #getPasswordMinimumLetters}</li>
     * <li>{@link #setPasswordMinimumLetters}</li>
     * <li>{@link #getPasswordMinimumNumeric}</li>
     * <li>{@link #setPasswordMinimumNumeric}</li>
     * <li>{@link #getPasswordMinimumSymbols}</li>
     * <li>{@link #setPasswordMinimumSymbols}</li>
     * <li>{@link #getPasswordMinimumNonLetter}</li>
     * <li>{@link #setPasswordMinimumNonLetter}</li>
     * <li>{@link #getPasswordHistoryLength}</li>
     * <li>{@link #setPasswordHistoryLength}</li>
     * <li>{@link #getPasswordExpirationTimeout}</li>
     * <li>{@link #setPasswordExpirationTimeout}</li>
     * <li>{@link #getPasswordExpiration}</li>
     * <li>{@link #getPasswordMaximumLength}</li>
     * <li>{@link #isActivePasswordSufficient}</li>
     * <li>{@link #getCurrentFailedPasswordAttempts}</li>
     * <li>{@link #getMaximumFailedPasswordsForWipe}</li>
     * <li>{@link #setMaximumFailedPasswordsForWipe}</li>
     * <li>{@link #getMaximumTimeToLock}</li>
     * <li>{@link #setMaximumTimeToLock}</li>
     * <li>{@link #lockNow}</li>
     * <li>{@link #getKeyguardDisabledFeatures}</li>
     * <li>{@link #setKeyguardDisabledFeatures}</li>
     * <li>{@link #getTrustAgentConfiguration}</li>
     * <li>{@link #setTrustAgentConfiguration}</li>
     * <li>{@link #getRequiredStrongAuthTimeout}</li>
     * <li>{@link #setRequiredStrongAuthTimeout}</li>
     * <li>{@link #getAccountTypesWithManagementDisabled}</li>
     * <li>{@link #setRequiredPasswordComplexity(int)} </li>
     * <li>{@link #getRequiredPasswordComplexity()}</li>
     * </ul>
     * <p>
     * The following methods are supported for the parent instance but can only be called by the
     * profile owner of a managed profile that was created during the device provisioning flow:
     * <ul>
     * <li>{@link #getPasswordComplexity}</li>
     * <li>{@link #setCameraDisabled}</li>
     * <li>{@link #getCameraDisabled}</li>
     * <li>{@link #setAccountManagementDisabled(ComponentName, String, boolean)}</li>
     * <li>{@link #setPermittedInputMethods}</li>
     * <li>{@link #getPermittedInputMethods}</li>
     * </ul>
     *
     * <p>The following methods can be called by the profile owner of a managed profile
     * on an organization-owned device:
     * <ul>
     * <li>{@link #wipeData}</li>
     * </ul>
     *
     * @return a new instance of {@link DevicePolicyManager} that acts on the parent profile.
     * @throws SecurityException if {@code admin} is not a profile owner.
     */
    public @NonNull DevicePolicyManager getParentProfileInstance(@NonNull ComponentName admin) {
        throwIfParentInstance("getParentProfileInstance");
        UserManager um = mContext.getSystemService(UserManager.class);
        if (!um.isManagedProfile()) {
            throw new SecurityException("The current user does not have a parent profile.");
        }
        return new DevicePolicyManager(mContext, mService, true);
    }

    /**
     * Called by device owner or a profile owner of an organization-owned managed profile to
     * control the security logging feature.
     *
     * <p> Security logs contain various information intended for security auditing purposes.
     * When security logging is enabled by any app other than the device owner, certain security
     * logs are not visible (for example personal app launch events) or they will be redacted
     * (for example, details of the physical volume mount events).
     * Please see {@link SecurityEvent} for details.
     *
     * <p><strong>Note:</strong> The device owner won't be able to retrieve security logs if there
     * are unaffiliated secondary users or profiles on the device, regardless of whether the
     * feature is enabled. Logs will be discarded if the internal buffer fills up while waiting for
     * all users to become affiliated. Therefore it's recommended that affiliation ids are set for
     * new users as soon as possible after provisioning via {@link #setAffiliationIds}. Non device
     * owners are not subject to this restriction since all
     * privacy-sensitive events happening outside the managed profile would have been redacted
     * already.
     *
     * Starting from {@link Build.VERSION_CODES#VANILLA_ICE_CREAM}, after the security logging
     * policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier {@link DevicePolicyIdentifiers#SECURITY_LOGGING_POLICY}
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which device admin this request is associated with, or {@code null}
     *              if called by a delegated app.
     * @param enabled whether security logging should be enabled or not.
     * @throws SecurityException if the caller is not permitted to control security logging.
     * @see #setAffiliationIds
     * @see #retrieveSecurityLogs
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_SECURITY_LOGGING, conditional = true)
    @SupportsCoexistence
    public void setSecurityLoggingEnabled(@Nullable ComponentName admin, boolean enabled) {
        throwIfParentInstance("setSecurityLoggingEnabled");
        try {
            mService.setSecurityLoggingEnabled(admin, mContext.getPackageName(), enabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether security logging is enabled or not by the admin.
     *
     * <p>Can only be called by the device owner or a profile owner of an organization-owned
     * managed profile, otherwise a {@link SecurityException} will be thrown.
     *
     * @param admin Which device admin this request is associated with. Null if the caller is not
     *              a device admin
     * @return {@code true} if security logging is enabled, {@code false} otherwise.
     * @throws SecurityException if the caller is not allowed to control security logging.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_SECURITY_LOGGING, conditional = true)
    public boolean isSecurityLoggingEnabled(@Nullable ComponentName admin) {
        throwIfParentInstance("isSecurityLoggingEnabled");
        try {
            return mService.isSecurityLoggingEnabled(admin, mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Controls whether audit logging is enabled.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    public void setAuditLogEnabled(boolean enabled) {
        throwIfParentInstance("setAuditLogEnabled");
        try {
            mService.setAuditLogEnabled(mContext.getPackageName(), enabled);
        } catch (RemoteException re) {
            re.rethrowFromSystemServer();
        }
    }

    /**
     * @return Whether audit logging is enabled.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    public boolean isAuditLogEnabled() {
        throwIfParentInstance("isAuditLogEnabled");
        try {
            return mService.isAuditLogEnabled(mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets audit log event callback. Only one callback per UID is active at any time, when a new
     * callback is set, the previous one is forgotten. Should only be called when audit log policy
     * is enforced by the caller. Disabling the policy clears the callback. Each time a new callback
     * is set, it will first be invoked with all the audit log events available at the time.
     *
     * @param callback The callback to invoke when new audit log events become available.
     * @param executor The executor through which the callback should be invoked.
     * @hide
     */
    @SystemApi
    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    public void setAuditLogEventCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<List<SecurityEvent>> callback) {
        throwIfParentInstance("setAuditLogEventCallback");
        final IAuditLogEventsCallback wrappedCallback =
                new IAuditLogEventsCallback.Stub() {
                    @Override
                    public void onNewAuditLogEvents(List<SecurityEvent> events) {
                        executor.execute(() -> callback.accept(events));
                    }
                };
        try {
            mService.setAuditLogEventsCallback(mContext.getPackageName(), wrappedCallback);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Clears audit log event callback. If a callback was set previously, it may still get invoked
     * after this call returns if it was already scheduled.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    public void clearAuditLogEventCallback() {
        throwIfParentInstance("clearAuditLogEventCallback");
        try {
            mService.setAuditLogEventsCallback(mContext.getPackageName(), null);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to retrieve
     * all new security logging entries since the last call to this API after device boots.
     *
     * <p> Access to the logs is rate limited and it will only return new logs after the admin has
     * been notified via {@link DeviceAdminReceiver#onSecurityLogsAvailable}.
     *
     * <p> When called by a device owner, if there is any other user or profile on the device,
     * it must be affiliated with the device. Otherwise a {@link SecurityException} will be thrown.
     * See {@link #isAffiliatedUser}.
     *
     * @param admin Which device admin this request is associated with, or {@code null}
     *              if called by a delegated app.
     * @return the new batch of security logs which is a list of {@link SecurityEvent},
     * or {@code null} if rate limitation is exceeded or if logging is currently disabled.
     * @throws SecurityException if the caller is not allowed to access security logging,
     * or there is at least one profile or secondary user that is not affiliated with the device.
     * @see #isAffiliatedUser
     * @see DeviceAdminReceiver#onSecurityLogsAvailable
     */
    @SuppressLint("NullableCollection")
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_SECURITY_LOGGING, conditional = true)
    public @Nullable List<SecurityEvent> retrieveSecurityLogs(@Nullable ComponentName admin) {
        throwIfParentInstance("retrieveSecurityLogs");
        try {
            ParceledListSlice<SecurityEvent> list = mService.retrieveSecurityLogs(
                    admin, mContext.getPackageName());
            if (list != null) {
                return list.getList();
            } else {
                // Rate limit exceeded.
                return null;
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Makes all accumulated network logs available to DPC in a new batch.
     * If throttled, returns time to wait in milliseconds, otherwise 0.
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.FORCE_DEVICE_POLICY_MANAGER_LOGS)
    public long forceNetworkLogs() {
        if (mService == null) {
            return -1;
        }
        try {
            return mService.forceNetworkLogs();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Forces a batch of security logs to be fetched from logd and makes it available for DPC.
     * If throttled, returns time to wait in milliseconds, otherwise 0.
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.FORCE_DEVICE_POLICY_MANAGER_LOGS)
    public long forceSecurityLogs() {
        if (mService == null) {
            return 0;
        }
        try {
            return mService.forceSecurityLogs();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to obtain a {@link DevicePolicyManager} whose calls act on the parent
     * profile.
     *
     * @hide
     */
    public @NonNull DevicePolicyManager getParentProfileInstance(UserInfo uInfo) {
        mContext.checkSelfPermission(
                android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS);
        if (!uInfo.isManagedProfile()) {
            throw new SecurityException("The user " + uInfo.id
                    + " does not have a parent profile.");
        }
        return new DevicePolicyManager(mContext, mService, true);
    }

    /**
     * Called by a device or profile owner to restrict packages from using metered data.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageNames the list of package names to be restricted.
     * @return a list of package names which could not be restricted.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public @NonNull List<String> setMeteredDataDisabledPackages(@NonNull ComponentName admin,
            @NonNull List<String> packageNames) {
        throwIfParentInstance("setMeteredDataDisabled");
        if (mService != null) {
            try {
                return mService.setMeteredDataDisabledPackages(admin, packageNames);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return packageNames;
    }

    /**
     * Called by a device or profile owner to retrieve the list of packages which are restricted
     * by the admin from using metered data.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @return the list of restricted package names.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    public @NonNull List<String> getMeteredDataDisabledPackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getMeteredDataDisabled");
        if (mService != null) {
            try {
                return mService.getMeteredDataDisabledPackages(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return new ArrayList<>();
    }

    /**
     * Called by the system to check if a package is restricted from using metered data
     * by {@param admin}.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName the package whose restricted status is needed.
     * @param userId the user to which {@param packageName} belongs.
     * @return {@code true} if the package is restricted by admin, otherwise {@code false}
     * @throws SecurityException if the caller doesn't run with {@link Process#SYSTEM_UID}
     * @hide
     */
    public boolean isMeteredDataDisabledPackageForUser(@NonNull ComponentName admin,
            String packageName, @UserIdInt int userId) {
        throwIfParentInstance("getMeteredDataDisabledForUser");
        if (mService != null) {
            try {
                return mService.isMeteredDataDisabledPackageForUser(admin, packageName, userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to retrieve
     * device logs from before the device's last reboot.
     * <p>
     * <strong> This API is not supported on all devices. Calling this API on unsupported devices
     * will result in {@code null} being returned. The device logs are retrieved from a RAM region
     * which is not guaranteed to be corruption-free during power cycles, as a result be cautious
     * about data corruption when parsing. </strong>
     *
     * <p> When called by a device owner, if there is any other user or profile on the device,
     * it must be affiliated with the device. Otherwise a {@link SecurityException} will be thrown.
     * See {@link #isAffiliatedUser}.
     *
     * @param admin Which device admin this request is associated with, or {@code null}
     *             if called by a delegated app.
     * @return Device logs from before the latest reboot of the system, or {@code null} if this API
     *         is not supported on the device.
     * @throws SecurityException if the caller is not allowed to access security logging, or
     * there is at least one profile or secondary user that is not affiliated with the device.
     * @see #isAffiliatedUser
     * @see #retrieveSecurityLogs
     */
    @SuppressLint("NullableCollection")
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_SECURITY_LOGGING, conditional = true)
    public @Nullable List<SecurityEvent> retrievePreRebootSecurityLogs(
            @Nullable ComponentName admin) {
        throwIfParentInstance("retrievePreRebootSecurityLogs");
        try {
            ParceledListSlice<SecurityEvent> list = mService.retrievePreRebootSecurityLogs(
                    admin, mContext.getPackageName());
            if (list != null) {
                return list.getList();
            } else {
                return null;
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of a managed profile to set the color used for customization. This
     * color is used as background color of the confirm credentials screen for that user. The
     * default color is teal (#00796B).
     * <p>
     * The confirm credentials screen can be created using
     * {@link android.app.KeyguardManager#createConfirmDeviceCredentialIntent}.
     * <p>
     * Starting from Android R, the organization color will no longer be used as the background
     * color of the confirm credentials screen.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param color The 24bit (0xRRGGBB) representation of the color to be used.
     * @throws SecurityException if {@code admin} is not a profile owner.
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}, the organization color is never
     * used as the background color of the confirm credentials screen.
     */
    @Deprecated
    public void setOrganizationColor(@NonNull ComponentName admin, int color) {
        throwIfParentInstance("setOrganizationColor");
        try {
            // always enforce alpha channel to have 100% opacity
            color |= 0xFF000000;
            mService.setOrganizationColor(admin, color);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     *
     * Sets the color used for customization.
     *
     * @param color The 24bit (0xRRGGBB) representation of the color to be used.
     * @param userId which user to set the color to.
     * @RequiresPermission(allOf = {
     *       Manifest.permission.MANAGE_USERS,
     *       Manifest.permission.INTERACT_ACROSS_USERS_FULL})
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}, the organization color is never
     * used as the background color of the confirm credentials screen.
     */
    @Deprecated
    public void setOrganizationColorForUser(@ColorInt int color, @UserIdInt int userId) {
        try {
            // always enforce alpha channel to have 100% opacity
            color |= 0xFF000000;
            mService.setOrganizationColorForUser(color, userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a profile owner of a managed profile to retrieve the color used for customization.
     * This color is used as background color of the confirm credentials screen for that user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @return The 24bit (0xRRGGBB) representation of the color to be used.
     * @throws SecurityException if {@code admin} is not a profile owner.
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}, the organization color is never
     * used as the background color of the confirm credentials screen.
     */
    @Deprecated
    public @ColorInt int getOrganizationColor(@NonNull ComponentName admin) {
        throwIfParentInstance("getOrganizationColor");
        try {
            return mService.getOrganizationColor(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Retrieve the customization color for a given user.
     *
     * @param userHandle The user id of the user we're interested in.
     * @return The 24bit (0xRRGGBB) representation of the color to be used.
     * @deprecated From {@link android.os.Build.VERSION_CODES#R}, the organization color is never
     * used as the background color of the confirm credentials screen.
     */
    @Deprecated
    public @ColorInt int getOrganizationColorForUser(int userHandle) {
        try {
            return mService.getOrganizationColorForUser(userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the device owner (since API 26) or profile owner (since API 24) to set the name of
     * the organization under management.
     *
     * <p>If the organization name needs to be localized, it is the responsibility of the caller
     * to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast and set a new version of this
     * string accordingly.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param title The organization name or {@code null} to clear a previously set name.
     * @throws SecurityException if {@code admin} is not a device or profile owner.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_ORGANIZATION_IDENTITY, conditional = true)
    public void setOrganizationName(@Nullable ComponentName admin, @Nullable CharSequence title) {
        throwIfParentInstance("setOrganizationName");
        try {
            mService.setOrganizationName(admin, mContext.getPackageName(), title);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the device owner (since API 26) or profile owner (since API 24) or holders of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_ORGANIZATION_IDENTITY
     * to retrieve the name of the organization under management.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @return The organization name or {@code null} if none is set.
     * @throws SecurityException if {@code admin} if {@code admin} is not a device or profile
     * owner or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_ORGANIZATION_IDENTITY}.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_ORGANIZATION_IDENTITY, conditional = true)
    public @Nullable CharSequence getOrganizationName(@Nullable ComponentName admin) {
        throwIfParentInstance("getOrganizationName");
        try {
            return mService.getOrganizationName(admin, mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    private final IpcDataCache<Void, CharSequence> mGetDeviceOwnerOrganizationNameCache =
            new IpcDataCache(sDpmCaches.child("getDeviceOwnerOrganizationName"),
                    (query) -> getService().getDeviceOwnerOrganizationName());

    /**
     * Called by the system to retrieve the name of the organization managing the device.
     *
     * @return The organization name or {@code null} if none is set.
     * @throws SecurityException if the caller is not the device owner, does not hold the
     *         MANAGE_USERS permission and is not the system.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("RequiresPermission")
    public @Nullable CharSequence getDeviceOwnerOrganizationName() {
        return mGetDeviceOwnerOrganizationNameCache.query(null);
    }

    private final IpcDataCache<Integer, CharSequence> mGetOrganizationNameForUserCache =
            new IpcDataCache<>(sDpmCaches.child("getOrganizationNameForUser"),
                    (query) -> getService().getOrganizationNameForUser(query));

    /**
     * Retrieve the default title message used in the confirm credentials screen for a given user.
     *
     * @param userHandle The user id of the user we're interested in.
     * @return The organization name or {@code null} if none is set.
     *
     * @hide
     */
    public @Nullable CharSequence getOrganizationNameForUser(int userHandle) {
        return mGetOrganizationNameForUserCache.query(userHandle);
    }

    /**
     * @return the {@link UserProvisioningState} for the current user - for unmanaged users will
     *         return {@link #STATE_USER_UNMANAGED}
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS
    })
    @UserProvisioningState
    @UserHandleAware(
            enabledSinceTargetSdkVersion = UPSIDE_DOWN_CAKE,
            requiresPermissionIfNotCaller = INTERACT_ACROSS_USERS)
    public int getUserProvisioningState() {
        throwIfParentInstance("getUserProvisioningState");
        if (mService != null) {
            try {
                return mService.getUserProvisioningState(mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return STATE_USER_UNMANAGED;
    }

    /**
     * Set the {@link UserProvisioningState} for the supplied user, if they are managed.
     *
     * @param state to store
     * @param userHandle for user
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void setUserProvisioningState(@UserProvisioningState int state, int userHandle) {
        if (mService != null) {
            try {
                mService.setUserProvisioningState(state, userHandle);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Set the {@link UserProvisioningState} for the supplied user. The supplied user has to be
     * manged, otherwise it will throw an {@link IllegalStateException}.
     *
     * <p> For managed users/profiles/devices, only the following state changes are allowed:
     * <ul>
     *     <li>{@link #STATE_USER_UNMANAGED} can change to any other state except itself
     *     <li>{@link #STATE_USER_SETUP_INCOMPLETE} and {@link #STATE_USER_SETUP_COMPLETE} can only
     *     change to {@link #STATE_USER_SETUP_FINALIZED}</li>
     *     <li>{@link #STATE_USER_PROFILE_COMPLETE} can only change to
     *     {@link #STATE_USER_PROFILE_FINALIZED}</li>
     *     <li>{@link #STATE_USER_SETUP_FINALIZED} can't be changed to any other state</li>
     *     <li>{@link #STATE_USER_PROFILE_FINALIZED} can only change to
     *     {@link #STATE_USER_UNMANAGED}</li>
     * </ul>
     * @param state to store
     * @param userHandle for user
     * @throws IllegalStateException if called with an invalid state change.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void setUserProvisioningState(
            @UserProvisioningState int state, @NonNull UserHandle userHandle) {
        setUserProvisioningState(state, userHandle.getIdentifier());
    }

    /**
     * Indicates the entity that controls the device. Two users are
     * affiliated if the set of ids set by the device owner and the admin of the secondary user.
     *
     * <p>A user that is affiliated with the device owner user is considered to be
     * affiliated with the device.
     *
     * <p><strong>Note:</strong> Features that depend on user affiliation (such as security logging
     * or {@link #bindDeviceAdminServiceAsUser}) won't be available when a secondary user
     * is created, until it becomes affiliated. Therefore it is recommended that the appropriate
     * affiliation ids are set by its owner as soon as possible after the user is
     * created.
     * <p>
     * Note: This method used to be available for affiliating device owner and profile
     * owner. However, since Android 11, this combination is not possible. This method is now
     * only useful for affiliating the primary user with managed secondary users.
     *
     * @param admin Which device owner, or owner of secondary user, this request is associated with.
     * @param ids A set of opaque non-empty affiliation ids.
     *
     * @throws IllegalArgumentException if {@code ids} is null or contains an empty string.
     * @see #isAffiliatedUser
     */
    public void setAffiliationIds(@NonNull ComponentName admin, @NonNull Set<String> ids) {
        throwIfParentInstance("setAffiliationIds");
        if (ids == null) {
            throw new IllegalArgumentException("ids must not be null");
        }
        try {
            mService.setAffiliationIds(admin, new ArrayList<>(ids));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the set of affiliation ids previously set via {@link #setAffiliationIds}, or an
     * empty set if none have been set.
     */
    public @NonNull Set<String> getAffiliationIds(@NonNull ComponentName admin) {
        throwIfParentInstance("getAffiliationIds");
        try {
            return new ArraySet<>(mService.getAffiliationIds(admin));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether this user is affiliated with the device.
     * <p>
     * By definition, the user that the device owner runs on is always affiliated with the device.
     * Any other user is considered affiliated with the device if the set specified by its
     * profile owner via {@link #setAffiliationIds} intersects with the device owner's.
     * @see #setAffiliationIds
     */
    public boolean isAffiliatedUser() {
        throwIfParentInstance("isAffiliatedUser");
        try {
            return mService.isCallingUserAffiliated();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether target user is affiliated with the device.
     */
    public boolean isAffiliatedUser(@UserIdInt int userId) {
        try {
            return mService.isAffiliatedUser(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Returns whether the uninstall for {@code packageName} for the current user is in queue
     * to be started
     * @param packageName the package to check for
     * @return whether the uninstall intent for {@code packageName} is pending
     */
    public boolean isUninstallInQueue(String packageName) {
        try {
            return mService.isUninstallInQueue(packageName);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * @param packageName the package containing active DAs to be uninstalled
     */
    public void uninstallPackageWithActiveAdmins(String packageName) {
        try {
            mService.uninstallPackageWithActiveAdmins(packageName);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Remove a test admin synchronously without sending it a broadcast about being removed.
     * If the admin is a profile owner or device owner it will still be removed.
     *
     * @param userHandle user id to remove the admin for.
     * @param admin The administration compononent to remove.
     * @throws SecurityException if the caller is not shell / root or the admin package
     *         isn't a test application see {@link ApplicationInfo#FLAG_TEST_APP}.
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void forceRemoveActiveAdmin(
            @NonNull ComponentName adminReceiver, @UserIdInt int userHandle) {
        try {
            mService.forceRemoveActiveAdmin(adminReceiver, userHandle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the device has been provisioned.
     *
     * <p>Not for use by third-party applications.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean isDeviceProvisioned() {
        try {
            return mService.isDeviceProvisioned();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
      * Writes that the provisioning configuration has been applied.
      *
      * <p>The caller must hold the {@link android.Manifest.permission#MANAGE_USERS}
      * permission.
      *
      * <p>Not for use by third-party applications.
      *
      * @hide
      */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public void setDeviceProvisioningConfigApplied() {
        try {
            mService.setDeviceProvisioningConfigApplied();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the provisioning configuration has been applied.
     *
     * <p>The caller must hold the {@link android.Manifest.permission#MANAGE_USERS} permission.
     *
     * <p>Not for use by third-party applications.
     *
     * @return whether the provisioning configuration has been applied.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean isDeviceProvisioningConfigApplied() {
        try {
            return mService.isDeviceProvisioningConfigApplied();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Force update user setup completed status for the given {@code userId}.
     * @throws {@link SecurityException} if the caller has no
     *         {@code android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS}.
     */
    @TestApi
    public void forceUpdateUserSetupComplete(@UserIdInt int userId) {
        try {
            mService.forceUpdateUserSetupComplete(userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void throwIfParentInstance(String functionName) {
        if (mParentInstance) {
            throw new SecurityException(functionName + " cannot be called on the parent instance");
        }
    }

    /**
     * Allows the device owner or profile owner to enable or disable the backup service.
     *
     * <p> Each user has its own backup service which manages the backup and restore mechanisms in
     * that user. Disabling the backup service will prevent data from being backed up or restored.
     *
     * <p> Device owner calls this API to control backup services across all users on the device.
     * Profile owner can use this API to enable or disable the profile's backup service. However,
     * for a managed profile its backup functionality is only enabled if both the device owner
     * and the profile owner have enabled the backup service.
     *
     * <p> By default, backup service is disabled on a device with device owner, and within a
     * managed profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param enabled {@code true} to enable the backup service, {@code false} to disable it.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner.
     */
    public void setBackupServiceEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setBackupServiceEnabled");
        try {
            mService.setBackupServiceEnabled(admin, enabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether the backup service is enabled by the device owner or profile owner for the
     * current user, as previously set by {@link #setBackupServiceEnabled(ComponentName, boolean)}.
     *
     * <p> Whether the backup functionality is actually enabled or not depends on settings from both
     * the current user and the device owner, please see
     * {@link #setBackupServiceEnabled(ComponentName, boolean)} for details.
     *
     * <p> Backup service manages all backup and restore mechanisms on the device.
     *
     * @return {@code true} if backup service is enabled, {@code false} otherwise.
     * @see #setBackupServiceEnabled
     */
    public boolean isBackupServiceEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("isBackupServiceEnabled");
        try {
            return mService.isBackupServiceEnabled(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner, profile owner of a managed profile or delegated app with
     * {@link #DELEGATION_NETWORK_LOGGING} to control the network logging feature.
     *
     * <p> Supported for a device owner from Android 8 and a delegated app granted by a device
     * owner from Android 10. Supported for a profile owner of a managed profile and a delegated
     * app granted by a profile owner from Android 12. When network logging is enabled by a
     * profile owner, the network logs will only include work profile network activity, not
     * activity on the personal profile.
     *
     * <p> Network logs contain DNS lookup and connect() library call events. The following library
     *     functions are recorded while network logging is active:
     *     <ul>
     *       <li>{@code getaddrinfo()}</li>
     *       <li>{@code gethostbyname()}</li>
     *       <li>{@code connect()}</li>
     *     </ul>
     *
     * <p> Network logging is a low-overhead tool for forensics but it is not guaranteed to use
     *     full system call logging; event reporting is enabled by default for all processes but not
     *     strongly enforced.
     *     Events from applications using alternative implementations of libc, making direct kernel
     *     calls, or deliberately obfuscating traffic may not be recorded.
     *
     * <p> Some common network events may not be reported. For example:
     *     <ul>
     *       <li>Applications may hardcode IP addresses to reduce the number of DNS lookups, or use
     *           an alternative system for name resolution, and so avoid calling
     *           {@code getaddrinfo()} or {@code gethostbyname}.</li>
     *       <li>Applications may use datagram sockets for performance reasons, for example
     *           for a game client. Calling {@code connect()} is unnecessary for this kind of
     *           socket, so it will not trigger a network event.</li>
     *     </ul>
     *
     * <p> It is possible to directly intercept layer 3 traffic leaving the device using an
     *     always-on VPN service.
     *     See {@link #setAlwaysOnVpnPackage(ComponentName, String, boolean)}
     *     and {@link android.net.VpnService} for details.
     *
     * <p><strong>Note:</strong> The device owner won't be able to retrieve network logs if there
     * are unaffiliated secondary users or profiles on the device, regardless of whether the
     * feature is enabled. Logs will be discarded if the internal buffer fills up while waiting for
     * all users to become affiliated. Therefore it's recommended that affiliation ids are set for
     * new users as soon as possible after provisioning via {@link #setAffiliationIds}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if called by a delegated app.
     * @param enabled whether network logging should be enabled or not.
     * @throws SecurityException if {@code admin} is not a device owner or profile owner.
     * @see #setAffiliationIds
     * @see #retrieveNetworkLogs
     */
    public void setNetworkLoggingEnabled(@Nullable ComponentName admin, boolean enabled) {
        throwIfParentInstance("setNetworkLoggingEnabled");
        try {
            mService.setNetworkLoggingEnabled(admin, mContext.getPackageName(), enabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    private IpcDataCache<ComponentName, Boolean> mIsNetworkLoggingEnabledCache =
            new IpcDataCache<>(sDpmCaches.child("isNetworkLoggingEnabled"),
                    (admin) -> getService().isNetworkLoggingEnabled(admin,
                            getContext().getPackageName()));

    /**
     * Return whether network logging is enabled by a device owner or profile owner of
     * a managed profile.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Can only
     * be {@code null} if the caller is a delegated app with {@link #DELEGATION_NETWORK_LOGGING}
     * or has MANAGE_USERS permission.
     * @return {@code true} if network logging is enabled by device owner or profile owner,
     * {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner or profile owner and
     * caller has no MANAGE_USERS permission
     */
    public boolean isNetworkLoggingEnabled(@Nullable ComponentName admin) {
        throwIfParentInstance("isNetworkLoggingEnabled");
        return mIsNetworkLoggingEnabledCache.query(admin);
    }

    /**
     * Called by device owner, profile owner of a managed profile or delegated app with
     * {@link #DELEGATION_NETWORK_LOGGING} to retrieve the most recent batch of
     * network logging events.
     *
     * <p> When network logging is enabled by a profile owner, the network logs will only include
     * work profile network activity, not activity on the personal profile.
     *
     * A device owner or profile owner has to provide a batchToken provided as part of
     * {@link DeviceAdminReceiver#onNetworkLogsAvailable} callback. If the token doesn't match the
     * token of the most recent available batch of logs, {@code null} will be returned.
     *
     * <p> {@link NetworkEvent} can be one of {@link DnsEvent} or {@link ConnectEvent}.
     *
     * <p> The list of network events is sorted chronologically, and contains at most 1200 events.
     *
     * <p> Access to the logs is rate limited and this method will only return a new batch of logs
     * after the device device owner has been notified via
     * {@link DeviceAdminReceiver#onNetworkLogsAvailable}.
     *
     * <p>If the caller is not a profile owner and a secondary user or profile is created, calling
     * this method will throw a {@link SecurityException} until all users become affiliated again.
     * It will also no longer be possible to retrieve the network logs batch with the most recent
     * batchToken provided by {@link DeviceAdminReceiver#onNetworkLogsAvailable}.
     * See {@link DevicePolicyManager#setAffiliationIds}.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with, or
     *        {@code null} if called by a delegated app.
     * @param batchToken A token of the batch to retrieve
     * @return A new batch of network logs which is a list of {@link NetworkEvent}. Returns
     *        {@code null} if the batch represented by batchToken is no longer available or if
     *        logging is disabled.
     * @throws SecurityException if {@code admin} is not a device owner, profile owner or if the
     * {@code admin} is not a profile owner and there is at least one profile or secondary user
     * that is not affiliated with the device.
     * @see #setAffiliationIds
     * @see DeviceAdminReceiver#onNetworkLogsAvailable
     */
    public @Nullable List<NetworkEvent> retrieveNetworkLogs(@Nullable ComponentName admin,
            long batchToken) {
        throwIfParentInstance("retrieveNetworkLogs");
        try {
            return mService.retrieveNetworkLogs(admin, mContext.getPackageName(), batchToken);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to bind to a service from a secondary managed user or vice versa.
     * See {@link #getBindDeviceAdminTargetUsers} for the pre-requirements of a
     * device owner to bind to services of another managed user.
     * <p>
     * The service must be protected by {@link android.Manifest.permission#BIND_DEVICE_ADMIN}.
     * Note that the {@link Context} used to obtain this
     * {@link DevicePolicyManager} instance via {@link Context#getSystemService(Class)} will be used
     * to bind to the {@link android.app.Service}.
     * <p>
     * Note: This method used to be available for communication between device owner and profile
     * owner. However, since Android 11, this combination is not possible. This method is now
     * only useful for communication between device owner and managed secondary users.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param serviceIntent Identifies the service to connect to.  The Intent must specify either an
     *        explicit component name or a package name to match an
     *        {@link IntentFilter} published by a service.
     * @param conn Receives information as the service is started and stopped in main thread. This
     *        must be a valid {@link ServiceConnection} object; it must not be {@code null}.
     * @param flags Operation options for the binding operation. See
     *        {@link Context#bindService(Intent, ServiceConnection, int)}.
     * @param targetUser Which user to bind to. Must be one of the users returned by
     *        {@link #getBindDeviceAdminTargetUsers}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @return If you have successfully bound to the service, {@code true} is returned;
     *         {@code false} is returned if the connection is not made and you will not
     *         receive the service object.
     *
     * @see Context#bindService(Intent, ServiceConnection, int)
     * @see #getBindDeviceAdminTargetUsers(ComponentName)
     */
    public boolean bindDeviceAdminServiceAsUser(@NonNull ComponentName admin,
            @NonNull Intent serviceIntent, @NonNull ServiceConnection conn,
            @Context.BindServiceFlagsBits int flags, @NonNull UserHandle targetUser) {
        throwIfParentInstance("bindDeviceAdminServiceAsUser");
        // Keep this in sync with ContextImpl.bindServiceCommon.
        try {
            final IServiceConnection sd = mContext.getServiceDispatcher(
                    conn, mContext.getMainThreadHandler(), Integer.toUnsignedLong(flags));
            serviceIntent.prepareToLeaveProcess(mContext);
            return mService.bindDeviceAdminServiceAsUser(admin,
                    mContext.getIApplicationThread(), mContext.getActivityToken(), serviceIntent,
                    sd, Integer.toUnsignedLong(flags), targetUser.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * See {@link #bindDeviceAdminServiceAsUser(ComponentName, Intent, ServiceConnection, int,
     *       UserHandle)}.
     * Call {@link Context.BindServiceFlags#of(long)} to obtain a BindServiceFlags object.
     */
    public boolean bindDeviceAdminServiceAsUser(@NonNull ComponentName admin,
            @NonNull Intent serviceIntent, @NonNull ServiceConnection conn,
            @NonNull Context.BindServiceFlags flags, @NonNull UserHandle targetUser) {
        throwIfParentInstance("bindDeviceAdminServiceAsUser");
        // Keep this in sync with ContextImpl.bindServiceCommon.
        try {
            final IServiceConnection sd = mContext.getServiceDispatcher(
                    conn, mContext.getMainThreadHandler(), flags.getValue());
            serviceIntent.prepareToLeaveProcess(mContext);
            return mService.bindDeviceAdminServiceAsUser(admin,
                    mContext.getIApplicationThread(), mContext.getActivityToken(), serviceIntent,
                    sd, flags.getValue(), targetUser.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of target users that the calling device owner or owner of secondary user
     * can use when calling {@link #bindDeviceAdminServiceAsUser}.
     * <p>
     * A device owner can bind to a service from a secondary managed user and vice versa, provided
     * that both users are affiliated. See {@link #setAffiliationIds}.
     */
    public @NonNull List<UserHandle> getBindDeviceAdminTargetUsers(@NonNull ComponentName admin) {
        throwIfParentInstance("getBindDeviceAdminTargetUsers");
        try {
            return mService.getBindDeviceAdminTargetUsers(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to get the time at which the device owner last retrieved security
     * logging entries.
     *
     * @return the time at which the device owner most recently retrieved security logging entries,
     *         in milliseconds since epoch; -1 if security logging entries were never retrieved.
     * @throws SecurityException if the caller is not the device owner, does not hold the
     *         MANAGE_USERS permission and is not the system.
     *
     * @hide
     */
    @TestApi
    public long getLastSecurityLogRetrievalTime() {
        try {
            return mService.getLastSecurityLogRetrievalTime();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to get the time at which the device owner last requested a bug report.
     *
     * @return the time at which the device owner most recently requested a bug report, in
     *         milliseconds since epoch; -1 if a bug report was never requested.
     * @throws SecurityException if the caller is not the device owner, does not hold the
     *         MANAGE_USERS permission and is not the system.
     *
     * @hide
     */
    @TestApi
    public long getLastBugReportRequestTime() {
        try {
            return mService.getLastBugReportRequestTime();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to get the time at which the device owner or profile owner of a
     * managed profile last retrieved network logging events.
     *
     * @return the time at which the device owner or profile owner most recently retrieved network
     *         logging events, in milliseconds since epoch; -1 if network logging events were
     *         never retrieved.
     * @throws SecurityException if the caller is not the device owner, does not hold the
     *         MANAGE_USERS permission and is not the system.
     *
     * @hide
     */
    @TestApi
    public long getLastNetworkLogRetrievalTime() {
        try {
            return mService.getLastNetworkLogRetrievalTime();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the current user's IME was set by an admin.
     *
     * <p>Requires the caller to be the system server, a device owner or profile owner, or a holder
     * of the QUERY_ADMIN_POLICY permission.
     *
     * @throws SecurityException if the caller is not authorized
     *
     * @hide
     */
    @TestApi
    public boolean isCurrentInputMethodSetByOwner() {
        try {
            return mService.isCurrentInputMethodSetByOwner();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the system to get a list of CA certificates that were installed by the device or
     * profile owner.
     *
     * <p> The caller must be the target user's device owner/profile Owner or hold the
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission.
     *
     * @param user The user for whom to retrieve information.
     * @return list of aliases identifying CA certificates installed by the device or profile owner
     * @throws SecurityException if the caller does not have permission to retrieve information
     *         about the given user's CA certificates.
     *
     * @hide
     */
    @TestApi
    public List<String> getOwnerInstalledCaCerts(@NonNull UserHandle user) {
        try {
            return mService.getOwnerInstalledCaCerts(user).getList();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether factory reset protection policy is supported on the device.
     *
     * @return {@code true} if the device support factory reset protection policy.
     *
     * @hide
     */
    @TestApi
    public boolean isFactoryResetProtectionPolicySupported() {
        try {
            return mService.isFactoryResetProtectionPolicySupported();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the device owner or profile owner to clear application user data of a given
     * package. The behaviour of this is equivalent to the target application calling
     * {@link android.app.ActivityManager#clearApplicationUserData()}.
     *
     * <p><strong>Note:</strong> an application can store data outside of its application data, e.g.
     * external storage or user dictionary. This data will not be wiped by calling this API.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param packageName The name of the package which will have its user data wiped.
     * @param executor The executor through which the listener should be invoked.
     * @param listener A callback object that will inform the caller when the clearing is done.
     * @throws SecurityException if the caller is not the device owner/profile owner.
     */
    public void clearApplicationUserData(@NonNull ComponentName admin,
            @NonNull String packageName, @NonNull @CallbackExecutor Executor executor,
            @NonNull OnClearApplicationUserDataListener listener) {
        throwIfParentInstance("clearAppData");
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        try {
            mService.clearApplicationUserData(admin, packageName,
                    new IPackageDataObserver.Stub() {
                        public void onRemoveCompleted(String pkg, boolean succeeded) {
                            executor.execute(() ->
                                    listener.onApplicationUserDataCleared(pkg, succeeded));
                        }
                    });
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to specify whether logout is enabled for all secondary users. The
     * system may show a logout button that stops the user and switches back to the primary user.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param enabled whether logout should be enabled or not.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setLogoutEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setLogoutEnabled");
        try {
            mService.setLogoutEnabled(admin, enabled);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether logout is enabled by a device owner.
     *
     * @return {@code true} if logout is enabled by device owner, {@code false} otherwise.
     */
    public boolean isLogoutEnabled() {
        throwIfParentInstance("isLogoutEnabled");
        try {
            return mService.isLogoutEnabled();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Callback used in {@link #clearApplicationUserData}
     * to indicate that the clearing of an application's user data is done.
     */
    public interface OnClearApplicationUserDataListener {
        /**
         * Method invoked when clearing the application user data has completed.
         *
         * @param packageName The name of the package which had its user data cleared.
         * @param succeeded Whether the clearing succeeded. Clearing fails for device administrator
         *                  apps and protected system packages.
         */
        void onApplicationUserDataCleared(String packageName, boolean succeeded);
    }

    /**
     * Returns set of system apps that should be removed during provisioning.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with.
     * @param userId ID of the user to be provisioned.
     * @param provisioningAction action indicating type of provisioning, should be one of
     * {@link #ACTION_PROVISION_MANAGED_DEVICE}, {@link #ACTION_PROVISION_MANAGED_PROFILE} or
     * {@link #ACTION_PROVISION_MANAGED_USER}.
     *
     * @hide
     */
    @TestApi
    public @NonNull Set<String> getDisallowedSystemApps(@NonNull ComponentName admin,
            @UserIdInt int userId, @NonNull String provisioningAction) {
        try {
            return new ArraySet<>(
                    mService.getDisallowedSystemApps(admin, userId, provisioningAction));
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Changes the current administrator to another one. All policies from the current
     * administrator are migrated to the new administrator. The whole operation is atomic -
     * the transfer is either complete or not done at all.
     *
     * <p>Depending on the current administrator (device owner, profile owner), you have the
     * following expected behaviour:
     * <ul>
     *     <li>A device owner can only be transferred to a new device owner</li>
     *     <li>A profile owner can only be transferred to a new profile owner</li>
     * </ul>
     *
     * <p>Use the {@code bundle} parameter to pass data to the new administrator. The data
     * will be received in the
     * {@link DeviceAdminReceiver#onTransferOwnershipComplete(Context, PersistableBundle)}
     * callback of the new administrator.
     *
     * <p>The transfer has failed if the original administrator is still the corresponding owner
     * after calling this method.
     *
     * <p>The incoming target administrator must have the
     * <code>&lt;support-transfer-ownership /&gt;</code> tag inside the
     * <code>&lt;device-admin&gt;&lt;/device-admin&gt;</code> tags in the xml file referenced by
     * {@link DeviceAdminReceiver#DEVICE_ADMIN_META_DATA}. Otherwise an
     * {@link IllegalArgumentException} will be thrown.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param target which {@link DeviceAdminReceiver} we want the new administrator to be.
     * @param bundle data to be sent to the new administrator.
     * @throws SecurityException if {@code admin} is not a device owner nor a profile owner.
     * @throws IllegalArgumentException if {@code admin} or {@code target} is {@code null}, they
     * are components in the same package or {@code target} is not an active admin.
     */
    public void transferOwnership(@NonNull ComponentName admin, @NonNull ComponentName target,
            @Nullable PersistableBundle bundle) {
        throwIfParentInstance("transferOwnership");
        try {
            mService.transferOwnership(admin, target, bundle);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to specify the user session start message. This may be displayed
     * during a user switch.
     * <p>
     * The message should be limited to a short statement or it may be truncated.
     * <p>
     * If the message needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this message accordingly.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param startUserSessionMessage message for starting user session, or {@code null} to use
     * system default message.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setStartUserSessionMessage(
            @NonNull ComponentName admin, @Nullable CharSequence startUserSessionMessage) {
        throwIfParentInstance("setStartUserSessionMessage");
        try {
            mService.setStartUserSessionMessage(admin, startUserSessionMessage);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a device owner to specify the user session end message. This may be displayed
     * during a user switch.
     * <p>
     * The message should be limited to a short statement or it may be truncated.
     * <p>
     * If the message needs to be localized, it is the responsibility of the
     * {@link DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast
     * and set a new version of this message accordingly.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param endUserSessionMessage message for ending user session, or {@code null} to use system
     * default message.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setEndUserSessionMessage(
            @NonNull ComponentName admin, @Nullable CharSequence endUserSessionMessage) {
        throwIfParentInstance("setEndUserSessionMessage");
        try {
            mService.setEndUserSessionMessage(admin, endUserSessionMessage);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user session start message.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public CharSequence getStartUserSessionMessage(@NonNull ComponentName admin) {
        throwIfParentInstance("getStartUserSessionMessage");
        try {
            return mService.getStartUserSessionMessage(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the user session end message.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public CharSequence getEndUserSessionMessage(@NonNull ComponentName admin) {
        throwIfParentInstance("getEndUserSessionMessage");
        try {
            return mService.getEndUserSessionMessage(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner or managed profile owner to add an override APN.
     *
     * <p>This method may returns {@code -1} if {@code apnSetting} conflicts with an existing
     * override APN. Update the existing conflicted APN with
     * {@link #updateOverrideApn(ComponentName, int, ApnSetting)} instead of adding a new entry.
     * <p>Two override APNs are considered to conflict when all the following APIs return
     * the same values on both override APNs:
     * <ul>
     *   <li>{@link ApnSetting#getOperatorNumeric()}</li>
     *   <li>{@link ApnSetting#getApnName()}</li>
     *   <li>{@link ApnSetting#getProxyAddressAsString()}</li>
     *   <li>{@link ApnSetting#getProxyPort()}</li>
     *   <li>{@link ApnSetting#getMmsProxyAddressAsString()}</li>
     *   <li>{@link ApnSetting#getMmsProxyPort()}</li>
     *   <li>{@link ApnSetting#getMmsc()}</li>
     *   <li>{@link ApnSetting#isEnabled()}</li>
     *   <li>{@link ApnSetting#getMvnoType()}</li>
     *   <li>{@link ApnSetting#getProtocol()}</li>
     *   <li>{@link ApnSetting#getRoamingProtocol()}</li>
     * </ul>
     *
     * <p> Before Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * Only device owners can add APNs.
     * <p> Starting from Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * Both device owners and managed profile owners can add enterprise APNs
     * ({@link ApnSetting#TYPE_ENTERPRISE}), while only device owners can add other type of APNs.
     * Enterprise APNs are specific to the managed profile and do not override any user-configured
     * VPNs. They are prerequisites for enabling preferential network service on the managed
     * profile on 4G networks ({@link #setPreferentialNetworkServiceConfigs}).
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param apnSetting the override APN to insert
     * @return The {@code id} of inserted override APN. Or {@code -1} when failed to insert into
     *         the database.
     * @throws SecurityException If request is for enterprise APN {@code admin} is either device
     * owner or profile owner and in all other types of APN if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public int addOverrideApn(@NonNull ComponentName admin, @NonNull ApnSetting apnSetting) {
        throwIfParentInstance("addOverrideApn");
        if (mService != null) {
            try {
                return mService.addOverrideApn(admin, apnSetting);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return -1;
    }

    /**
     * Called by device owner or managed profile owner to update an override APN.
     *
     * <p>This method may returns {@code false} if there is no override APN with the given
     * {@code apnId}.
     * <p>This method may also returns {@code false} if {@code apnSetting} conflicts with an
     * existing override APN. Update the existing conflicted APN instead.
     * <p>See {@link #addOverrideApn} for the definition of conflict.
     * <p> Before Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * Only device owners can update APNs.
     * <p> Starting from Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * Both device owners and managed profile owners can update enterprise APNs
     * ({@link ApnSetting#TYPE_ENTERPRISE}), while only device owners can update other type of APNs.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param apnId the {@code id} of the override APN to update
     * @param apnSetting the override APN to update
     * @return {@code true} if the required override APN is successfully updated,
     *         {@code false} otherwise.
     * @throws SecurityException If request is for enterprise APN {@code admin} is either device
     * owner or profile owner and in all other types of APN if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public boolean updateOverrideApn(@NonNull ComponentName admin, int apnId,
            @NonNull ApnSetting apnSetting) {
        throwIfParentInstance("updateOverrideApn");
        if (mService != null) {
            try {
                return mService.updateOverrideApn(admin, apnId, apnSetting);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device owner or managed profile owner to remove an override APN.
     *
     * <p>This method may returns {@code false} if there is no override APN with the given
     * {@code apnId}.
     * <p> Before Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * Only device owners can remove APNs.
     * <p> Starting from Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}:
     * Both device owners and managed profile owners can remove enterprise APNs
     * ({@link ApnSetting#TYPE_ENTERPRISE}), while only device owners can remove other type of APNs.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param apnId the {@code id} of the override APN to remove
     * @return {@code true} if the required override APN is successfully removed, {@code false}
     *         otherwise.
     * @throws SecurityException If request is for enterprise APN {@code admin} is either device
     * owner or profile owner and in all other types of APN if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public boolean removeOverrideApn(@NonNull ComponentName admin, int apnId) {
        throwIfParentInstance("removeOverrideApn");
        if (mService != null) {
            try {
                return mService.removeOverrideApn(admin, apnId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by device owner or managed profile owner to get all override APNs inserted by
     * device owner or managed profile owner previously using {@link #addOverrideApn}.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @return A list of override APNs inserted by device owner.
     * @throws SecurityException if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public List<ApnSetting> getOverrideApns(@NonNull ComponentName admin) {
        throwIfParentInstance("getOverrideApns");
        if (mService != null) {
            try {
                return mService.getOverrideApns(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Called by device owner to set if override APNs should be enabled.
     * <p> Override APNs are separated from other APNs on the device, and can only be inserted or
     * modified by the device owner. When enabled, only override APNs are in use, any other APNs
     * are ignored.
     * <p>Note: Enterprise APNs added by managed profile owners do not need to be enabled by
     * this API. They are part of the preferential network service config and is controlled by
     * {@link #setPreferentialNetworkServiceConfigs}.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param enabled {@code true} if override APNs should be enabled, {@code false} otherwise
     * @throws SecurityException if {@code admin} is not a device owner.
     */
    public void setOverrideApnsEnabled(@NonNull ComponentName admin, boolean enabled) {
        throwIfParentInstance("setOverrideApnEnabled");
        if (mService != null) {
            try {
                mService.setOverrideApnsEnabled(admin, enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by device owner to check if override APNs are currently enabled.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @return {@code true} if override APNs are currently enabled, {@code false} otherwise.
     * @throws SecurityException if {@code admin} is not a device owner.
     *
     * @see #setOverrideApnsEnabled(ComponentName, boolean)
     */
    public boolean isOverrideApnEnabled(@NonNull ComponentName admin) {
        throwIfParentInstance("isOverrideApnEnabled");
        if (mService != null) {
            try {
                return mService.isOverrideApnEnabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the data passed from the current administrator to the new administrator during an
     * ownership transfer. This is the same {@code bundle} passed in
     * {@link #transferOwnership(ComponentName, ComponentName, PersistableBundle)}. The bundle is
     * persisted until the profile owner or device owner is removed.
     *
     * <p>This is the same <code>bundle</code> received in the
     * {@link DeviceAdminReceiver#onTransferOwnershipComplete(Context, PersistableBundle)}.
     * Use this method to retrieve it after the transfer as long as the new administrator is the
     * active device or profile owner.
     *
     * <p>Returns <code>null</code> if no ownership transfer was started for the calling user.
     *
     * @see #transferOwnership
     * @see DeviceAdminReceiver#onTransferOwnershipComplete(Context, PersistableBundle)
     * @throws SecurityException if the caller is not a device or profile owner.
     */
    @Nullable
    public PersistableBundle getTransferOwnershipBundle() {
        throwIfParentInstance("getTransferOwnershipBundle");
        try {
            return mService.getTransferOwnershipBundle();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the global Private DNS mode to opportunistic.
     * May only be called by the device owner.
     *
     * <p>In this mode, the DNS subsystem will attempt a TLS handshake to the network-supplied
     * resolver prior to attempting name resolution in cleartext.
     *
     * <p>Note: The device owner won't be able to set the global private DNS mode if there are
     * unaffiliated secondary users or profiles on the device. It's recommended that affiliation
     * ids are set for new users as soon as possible after provisioning via
     * {@link #setAffiliationIds}.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     *
     * @return {@code PRIVATE_DNS_SET_NO_ERROR} if the mode was set successfully, or
     *         {@code PRIVATE_DNS_SET_ERROR_FAILURE_SETTING} if it could not be set.
     *
     * @throws SecurityException if the caller is not the device owner.
     */
    public @PrivateDnsModeErrorCodes int setGlobalPrivateDnsModeOpportunistic(
            @NonNull ComponentName admin) {
        throwIfParentInstance("setGlobalPrivateDnsModeOpportunistic");

        if (mService == null) {
            return PRIVATE_DNS_SET_ERROR_FAILURE_SETTING;
        }

        try {
            return mService.setGlobalPrivateDns(admin, PRIVATE_DNS_MODE_OPPORTUNISTIC, null);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the global Private DNS host to be used.
     * May only be called by the device owner.
     *
     * <p>Note that the method is blocking as it will perform a connectivity check to the resolver,
     * to ensure it is valid. Because of that, the method should not be called on any thread that
     * relates to user interaction, such as the UI thread.
     *
     * <p>In case a VPN is used in conjunction with Private DNS resolver, the Private DNS resolver
     * must be reachable both from within and outside the VPN. Otherwise, the device may lose
     * the ability to resolve hostnames as system traffic to the resolver may not go through the
     * VPN.
     *
     * <p>Note: The device owner won't be able to set the global private DNS mode if there are
     * unaffiliated secondary users or profiles on the device. It's recommended that affiliation
     * ids are set for new users as soon as possible after provisioning via
     * {@link #setAffiliationIds}.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @param privateDnsHost The hostname of a server that implements DNS over TLS (RFC7858).
     *
     * @return {@code PRIVATE_DNS_SET_NO_ERROR} if the mode was set successfully,
     *         {@code PRIVATE_DNS_SET_ERROR_FAILURE_SETTING} if it could not be set or
     *         {@code PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING} if the specified host does not
     *         implement RFC7858.
     *
     * @throws IllegalArgumentException if the {@code privateDnsHost} is not a valid hostname.
     *
     * @throws SecurityException if the caller is not the device owner.
     */
    @WorkerThread public @PrivateDnsModeErrorCodes int setGlobalPrivateDnsModeSpecifiedHost(
            @NonNull ComponentName admin, @NonNull String privateDnsHost) {
        throwIfParentInstance("setGlobalPrivateDnsModeSpecifiedHost");
        Objects.requireNonNull(privateDnsHost, "dns resolver is null");

        if (mService == null) {
            return PRIVATE_DNS_SET_ERROR_FAILURE_SETTING;
        }

        if (NetworkUtilsInternal.isWeaklyValidatedHostname(privateDnsHost)) {
            if (!PrivateDnsConnectivityChecker.canConnectToPrivateDnsServer(privateDnsHost)) {
                return PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING;
            }
        }

        try {
            return mService.setGlobalPrivateDns(
                    admin, PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, privateDnsHost);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to install
     * a system update from the given file. The device will be
     * rebooted in order to finish installing the update. Note that if the device is rebooted, this
     * doesn't necessarily mean that the update has been applied successfully. The caller should
     * additionally check the system version with {@link android.os.Build#FINGERPRINT} or {@link
     * android.os.Build.VERSION}. If an error occurs during processing the OTA before the reboot,
     * the caller will be notified by {@link InstallSystemUpdateCallback}. If device does not have
     * sufficient battery level, the installation will fail with error {@link
     * InstallSystemUpdateCallback#UPDATE_ERROR_BATTERY_LOW}.
     *
     * @param admin The {@link DeviceAdminReceiver} that this request is associated with. Null if
     *              the caller is not a device admin
     * @param updateFilePath A Uri of the file that contains the update. The file should be
     * readable by the calling app.
     * @param executor The executor through which the callback should be invoked.
     * @param callback A callback object that will inform the caller when installing an update
     * fails.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_SYSTEM_UPDATES, conditional = true)
    public void installSystemUpdate(
            @Nullable ComponentName admin, @NonNull Uri updateFilePath,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull InstallSystemUpdateCallback callback) {
        throwIfParentInstance("installUpdate");
        if (mService == null) {
            return;
        }
        try (ParcelFileDescriptor fileDescriptor = mContext.getContentResolver()
                    .openFileDescriptor(updateFilePath, "r")) {
            mService.installUpdateFromFile(
                    admin, mContext.getPackageName(), fileDescriptor,
                    new StartInstallingUpdateCallback.Stub() {
                        @Override
                        public void onStartInstallingUpdateError(
                                int errorCode, String errorMessage) {
                            executeCallback(errorCode, errorMessage, executor, callback);
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (FileNotFoundException e) {
            Log.w(TAG, e);
            executeCallback(
                    InstallSystemUpdateCallback.UPDATE_ERROR_FILE_NOT_FOUND,
                    Log.getStackTraceString(e),
                    executor, callback);
        } catch (IOException e) {
            Log.w(TAG, e);
            executeCallback(
                    InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN, Log.getStackTraceString(e),
                    executor, callback);
        }
    }

    private void executeCallback(int errorCode, String errorMessage,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull InstallSystemUpdateCallback callback) {
        executor.execute(() -> callback.onInstallUpdateError(errorCode, errorMessage));
    }

    /**
     * Returns the system-wide Private DNS mode.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @return one of {@code PRIVATE_DNS_MODE_OFF}, {@code PRIVATE_DNS_MODE_OPPORTUNISTIC},
     * {@code PRIVATE_DNS_MODE_PROVIDER_HOSTNAME} or {@code PRIVATE_DNS_MODE_UNKNOWN}.
     * @throws SecurityException if the caller is not the device owner.
     */
    public int getGlobalPrivateDnsMode(@NonNull ComponentName admin) {
        throwIfParentInstance("setGlobalPrivateDns");
        if (mService == null) {
            return PRIVATE_DNS_MODE_UNKNOWN;
        }

        try {
            return mService.getGlobalPrivateDnsMode(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the system-wide Private DNS host.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with.
     * @return The hostname used for Private DNS queries, null if none is set.
     * @throws SecurityException if the caller is not the device owner.
     */
    public @Nullable String getGlobalPrivateDnsHost(@NonNull ComponentName admin) {
        throwIfParentInstance("setGlobalPrivateDns");
        if (mService == null) {
            return null;
        }

        try {
            return mService.getGlobalPrivateDnsHost(admin);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Deprecated. Use {@code markProfileOwnerOnOrganizationOwnedDevice} instead.
     * When called by an app targeting SDK level {@link android.os.Build.VERSION_CODES#Q} or
     * below, will behave the same as {@link #setProfileOwnerOnOrganizationOwnedDevice}.
     *
     * When called by an app targeting SDK level {@link android.os.Build.VERSION_CODES#R}
     * or above, will throw an UnsupportedOperationException when called.
     *
     * @deprecated Use {@link #setProfileOwnerOnOrganizationOwnedDevice} instead.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.GRANT_PROFILE_OWNER_DEVICE_IDS_ACCESS,
            conditional = true)
    public void setProfileOwnerCanAccessDeviceIds(@NonNull ComponentName who) {
        ApplicationInfo ai = mContext.getApplicationInfo();
        if (ai.targetSdkVersion > Build.VERSION_CODES.Q) {
            throw new UnsupportedOperationException(
                    "This method is deprecated. use markProfileOwnerOnOrganizationOwnedDevice"
                    + " instead.");
        } else {
            setProfileOwnerOnOrganizationOwnedDevice(who, true);
        }
    }

    /**
     * Sets whether the profile owner of the given user as managing an organization-owned device.
     * Managing an organization-owned device will give it access to device identifiers (such as
     * serial number, IMEI and MEID) as well as other privileges.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MARK_DEVICE_ORGANIZATION_OWNED,
            android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS
            }, conditional = true)
    public void setProfileOwnerOnOrganizationOwnedDevice(@NonNull ComponentName who,
            boolean isProfileOwnerOnOrganizationOwnedDevice) {
        if (mService == null) {
            return;
        }
        try {
            mService.setProfileOwnerOnOrganizationOwnedDevice(who, myUserId(),
                    isProfileOwnerOnOrganizationOwnedDevice);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Allows a set of packages to access cross-profile calendar APIs.
     *
     * <p>Called by a profile owner of a managed profile.
     *
     * <p>Calling with a {@code null} value for the set disables the restriction so that all
     * packages are allowed to access cross-profile calendar APIs. Calling with an empty set
     * disallows all packages from accessing cross-profile calendar APIs. If this method isn't
     * called, no package is allowed to access cross-profile calendar APIs by default.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @param packageNames set of packages to be allowlisted
     * @throws SecurityException if {@code admin} is not a profile owner
     *
     * @see #getCrossProfileCalendarPackages(ComponentName)
     * @deprecated Use {@link #setCrossProfilePackages(ComponentName, Set)}.
     */
    @Deprecated
    public void setCrossProfileCalendarPackages(@NonNull ComponentName admin,
            @Nullable Set<String> packageNames) {
        throwIfParentInstance("setCrossProfileCalendarPackages");
        if (mService != null) {
            try {
                mService.setCrossProfileCalendarPackages(admin, packageNames == null ? null
                        : new ArrayList<>(packageNames));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Gets a set of package names that are allowed to access cross-profile calendar APIs.
     *
     * <p>Called by a profile owner of a managed profile.
     *
     * @param admin which {@link DeviceAdminReceiver} this request is associated with
     * @return the set of names of packages that were previously allowed via
     * {@link #setCrossProfileCalendarPackages(ComponentName, Set)}, or an
     * empty set if none have been allowed
     * @throws SecurityException if {@code admin} is not a profile owner
     *
     * @see #setCrossProfileCalendarPackages(ComponentName, Set)
     * @deprecated Use {@link #setCrossProfilePackages(ComponentName, Set)}.
     */
    @Deprecated
    public @Nullable Set<String> getCrossProfileCalendarPackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getCrossProfileCalendarPackages");
        if (mService != null) {
            try {
                final List<String> packageNames = mService.getCrossProfileCalendarPackages(admin);
                return packageNames == null ? null : new ArraySet<>(packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Returns if a package is allowed to access cross-profile calendar APIs.
     *
     * <p>A package is allowed to access cross-profile calendar APIs if it's allowed by
     * admins via {@link #setCrossProfileCalendarPackages(ComponentName, Set)} and
     * {@link android.provider.Settings.Secure#CROSS_PROFILE_CALENDAR_ENABLED}
     * is turned on in the managed profile.
     *
     * <p>To query for a specific user, use
     * {@link Context#createPackageContextAsUser(String, int, UserHandle)} to create a context for
     * that user, and get a {@link DevicePolicyManager} from this context.
     *
     * @param packageName the name of the package
     * @return {@code true} if the package is allowed to access cross-profile calendar APIs,
     * {@code false} otherwise
     *
     * @see #setCrossProfileCalendarPackages(ComponentName, Set)
     * @see #getCrossProfileCalendarPackages(ComponentName)
     * @hide
     */
    @RequiresPermission(anyOf = {
            INTERACT_ACROSS_USERS_FULL,
            permission.INTERACT_ACROSS_USERS
    }, conditional = true)
    public boolean isPackageAllowedToAccessCalendar(@NonNull  String packageName) {
        throwIfParentInstance("isPackageAllowedToAccessCalendar");
        if (mService != null) {
            try {
                return mService.isPackageAllowedToAccessCalendarForUser(packageName,
                        myUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Gets a set of package names that are allowed to access cross-profile calendar APIs.
     *
     * <p>To query for a specific user, use
     * {@link Context#createPackageContextAsUser(String, int, UserHandle)} to create a context for
     * that user, and get a {@link DevicePolicyManager} from this context.
     *
     * @return the set of names of packages that were previously allowed via
     * {@link #setCrossProfileCalendarPackages(ComponentName, Set)}, or an
     * empty set if none have been allowed
     *
     * @see #setCrossProfileCalendarPackages(ComponentName, Set)
     * @see #getCrossProfileCalendarPackages(ComponentName)
     * @hide
     */
    @RequiresPermission(anyOf = {
            INTERACT_ACROSS_USERS_FULL,
            permission.INTERACT_ACROSS_USERS
    })
    public @Nullable Set<String> getCrossProfileCalendarPackages() {
        throwIfParentInstance("getCrossProfileCalendarPackages");
        if (mService != null) {
            try {
                final List<String> packageNames = mService.getCrossProfileCalendarPackagesForUser(
                        myUserId());
                return packageNames == null ? null : new ArraySet<>(packageNames);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Sets the set of admin-allowlisted package names that are allowed to request user consent for
     * cross-profile communication.
     *
     * <p>Assumes that the caller is a profile owner and is the given {@code admin}.
     *
     * <p>Previous calls are overridden by each subsequent call to this method.
     *
     * <p>Note that other apps may be able to request user consent for cross-profile communication
     * if they have been explicitly allowlisted by the OEM.
     *
     * <p>When previously-set cross-profile packages are missing from {@code packageNames}, the
     * app-op for {@code INTERACT_ACROSS_PROFILES} will be reset for those packages. This will not
     * occur for packages that are allowlisted by the OEM.
     *
     * @param admin the {@link DeviceAdminReceiver} this request is associated with
     * @param packageNames the new cross-profile package names
     */
    public void setCrossProfilePackages(
            @NonNull ComponentName admin, @NonNull Set<String> packageNames) {
        throwIfParentInstance("setCrossProfilePackages");
        if (mService != null) {
            try {
                mService.setCrossProfilePackages(admin, new ArrayList<>(packageNames));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the set of package names that the admin has previously set as allowed to request user
     * consent for cross-profile communication, via {@link #setCrossProfilePackages(ComponentName,
     * Set)}.
     *
     * <p>Assumes that the caller is a profile owner and is the given {@code admin}.
     *
     * <p>Note that other apps not included in the returned set may be able to request user consent
     * for cross-profile communication if they have been explicitly allowlisted by the OEM.
     *
     * @param admin the {@link DeviceAdminReceiver} this request is associated with
     * @return the set of package names the admin has previously set as allowed to request user
     * consent for cross-profile communication, via {@link #setCrossProfilePackages(ComponentName,
     * Set)}
     */
    public @NonNull Set<String> getCrossProfilePackages(@NonNull ComponentName admin) {
        throwIfParentInstance("getCrossProfilePackages");
        if (mService != null) {
            try {
                return new ArraySet<>(mService.getCrossProfilePackages(admin));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Returns the combined set of the following:
     * <ul>
     * <li>The package names that the admin has previously set as allowed to request user consent
     * for cross-profile communication, via {@link #setCrossProfilePackages(ComponentName,
     * Set)}.</li>
     * <li>The default package names set by the OEM that are allowed to request user consent for
     * cross-profile communication without being explicitly enabled by the admin, via {@link
     * com.android.internal.R.array#cross_profile_apps} and {@link com.android.internal.R.array
     * #vendor_cross_profile_apps}.</li>
     * </ul>
     *
     * @return the combined set of allowlisted package names set via
     * {@link #setCrossProfilePackages(ComponentName, Set)}, {@link com.android.internal.R.array
     * #cross_profile_apps}, and {@link com.android.internal.R.array#vendor_cross_profile_apps}.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            INTERACT_ACROSS_USERS_FULL,
            permission.INTERACT_ACROSS_USERS,
            permission.INTERACT_ACROSS_PROFILES
    })
    public @NonNull Set<String> getAllCrossProfilePackages() {
        throwIfParentInstance("getAllCrossProfilePackages");
        if (mService != null) {
            try {
                return new ArraySet<>(mService.getAllCrossProfilePackages(mContext.getUserId()));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Returns the default package names set by the OEM that are allowed to communicate
     * cross-profile without being explicitly enabled by the admin, via {@link
     * com.android.internal.R.array#cross_profile_apps} and {@link com.android.internal.R.array
     * #vendor_cross_profile_apps}.
     *
     * @hide
     */
    @TestApi
    public @NonNull Set<String> getDefaultCrossProfilePackages() {
        throwIfParentInstance("getDefaultCrossProfilePackages");
        if (mService != null) {
            try {
                return new ArraySet<>(mService.getDefaultCrossProfilePackages());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptySet();
    }

    /**
     * Returns whether the device is being used as a managed kiosk. These requirements are as
     * follows:
     * <ul>
     *     <li>The device is in Lock Task (therefore there is also a Device Owner app on the
     *     device)</li>
     *     <li>The Lock Task feature {@link DevicePolicyManager#LOCK_TASK_FEATURE_SYSTEM_INFO} is
     *     not enabled, so the system info in the status bar is not visible</li>
     *     <li>The device does not have a secure lock screen (e.g. it has no lock screen or has
     *     swipe-to-unlock)</li>
     *     <li>The device is not in the middle of an ephemeral user session</li>
     * </ul>
     *
     * <p>Publicly-accessible dedicated devices don't have the same privacy model as
     * personally-used devices. In particular, user consent popups don't make sense as a barrier to
     * accessing persistent data on these devices since the user giving consent and the user whose
     * data is on the device are unlikely to be the same. These consent popups prevent the true
     * remote management of these devices.
     *
     * <p>This condition is not sufficient to cover APIs that would access data that only lives for
     * the duration of the user's session, since the user has an expectation of privacy in these
     * conditions that more closely resembles use of a personal device. In those cases, see {@link
     * #isUnattendedManagedKiosk()}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS
    })
    public boolean isManagedKiosk() {
        throwIfParentInstance("isManagedKiosk");
        if (mService != null) {
            try {
                return mService.isManagedKiosk();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns whether the device is being used as an unattended managed kiosk. These requirements
     * are as follows:
     * <ul>
     *     <li>The device is being used as a managed kiosk, as defined at {@link
     *     #isManagedKiosk()}</li>
     *     <li>The device has not received user input for at least 30 minutes</li>
     * </ul>
     *
     * <p>See {@link #isManagedKiosk()} for context. This is a stronger requirement that also
     * ensures that the device hasn't been interacted with recently, making it an appropriate check
     * for privacy-sensitive APIs that wouldn't be appropriate during an active user session.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS
    })
    public boolean isUnattendedManagedKiosk() {
        throwIfParentInstance("isUnattendedManagedKiosk");
        if (mService != null) {
            try {
                return mService.isUnattendedManagedKiosk();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Starts an activity to view calendar events in the managed profile.
     *
     * @param eventId the id of the event to be viewed
     * @param start the start time of the event
     * @param end the end time of the event
     * @param allDay if the event is an all-day event
     * @param flags flags to be set for the intent
     * @return {@code true} if the activity is started successfully, {@code false} otherwise
     *
     * @see CalendarContract#startViewCalendarEventInManagedProfile(Context, String, long, long,
     * long, boolean, int)
     *
     * @hide
     */
    public boolean startViewCalendarEventInManagedProfile(long eventId, long start, long end,
            boolean allDay, int flags) {
        throwIfParentInstance("startViewCalendarEventInManagedProfile");
        if (mService != null) {
            try {
                return mService.startViewCalendarEventInManagedProfile(mContext.getPackageName(),
                        eventId, start, end, allDay, flags);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Service-specific error code used in {@link #setApplicationExemptions(String, Set)} and
     * {@link #getApplicationExemptions(String)}.
     * @hide
     */
    public static final int ERROR_PACKAGE_NAME_NOT_FOUND = 1;

    /**
     * Called by an application with the
     * {@link  android.Manifest.permission#MANAGE_DEVICE_POLICY_APP_EXEMPTIONS} permission, to
     * grant platform restriction exemptions to a given application.
     *
     * @param  packageName The package name of the application to be exempt.
     * @param  exemptions The set of exemptions to be applied.
     * @throws SecurityException If the caller does not have
     *             {@link  android.Manifest.permission#MANAGE_DEVICE_POLICY_APP_EXEMPTIONS}
     * @throws NameNotFoundException If either the package is not installed or the package is not
     *              visible to the caller.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_DEVICE_POLICY_APP_EXEMPTIONS)
    public void setApplicationExemptions(@NonNull String packageName,
            @NonNull @ApplicationExemptionConstants Set<Integer> exemptions)
            throws NameNotFoundException {
        throwIfParentInstance("setApplicationExemptions");
        if (mService != null) {
            try {
                mService.setApplicationExemptions(mContext.getPackageName(), packageName,
                        ArrayUtils.convertToIntArray(new ArraySet<>(exemptions)));
            } catch (ServiceSpecificException e) {
                switch (e.errorCode) {
                    case ERROR_PACKAGE_NAME_NOT_FOUND:
                        throw new NameNotFoundException(e.getMessage());
                    default:
                        throw new RuntimeException(
                                "Unknown error setting application exemptions: " + e.errorCode, e);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns all the platform restriction exemptions currently applied to an application. Called
     * by an application with the
     * {@link  android.Manifest.permission#MANAGE_DEVICE_POLICY_APP_EXEMPTIONS} permission.
     *
     * @param  packageName The package name to check.
     * @return A set of platform restrictions an application is exempt from.
     * @throws SecurityException If the caller does not have
     *             {@link  android.Manifest.permission#MANAGE_DEVICE_POLICY_APP_EXEMPTIONS}
     * @throws NameNotFoundException If either the package is not installed or the package is not
     *              visible to the caller.
     * @hide
     */
    @NonNull
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_DEVICE_POLICY_APP_EXEMPTIONS)
    public Set<Integer> getApplicationExemptions(@NonNull String packageName)
            throws NameNotFoundException {
        throwIfParentInstance("getApplicationExemptions");
        if (mService == null) {
            return Collections.emptySet();
        }
        try {
            return intArrayToSet(mService.getApplicationExemptions(packageName));
        } catch (ServiceSpecificException e) {
            switch (e.errorCode) {
                case ERROR_PACKAGE_NAME_NOT_FOUND:
                    throw new NameNotFoundException(e.getMessage());
                default:
                    throw new RuntimeException(
                            "Unknown error getting application exemptions: " + e.errorCode, e);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private Set<Integer> intArrayToSet(int[] array) {
        Set<Integer> set = new ArraySet<>();
        for (int item : array) {
            set.add(item);
        }
        return set;
    }

    /**
     * Called by a device owner or a profile owner or holder of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_APPS_CONTROL} to disable user
     * control over apps. User will not be able to clear app data or force-stop packages. When
     * called by a device owner, applies to all users on the device. Packages with user control
     * disabled are exempted from App Standby Buckets.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, after the user control disabled
     * packages policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The policy identifier
     * {@link DevicePolicyIdentifiers#USER_CONTROL_DISABLED_PACKAGES_POLICY}
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * (e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @param packages The package names for the apps.
     * @throws SecurityException if {@code admin} is not a device owner or a profile owner or
     * holder of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_APPS_CONTROL}.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_APPS_CONTROL, conditional = true)
    @SupportsCoexistence
    public void setUserControlDisabledPackages(@Nullable ComponentName admin,
            @NonNull List<String> packages) {
        throwIfParentInstance("setUserControlDisabledPackages");
        if (mService != null) {
            try {
                mService.setUserControlDisabledPackages(admin, mContext.getPackageName(), packages);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the list of packages over which user control is disabled by a device or profile
     * owner or holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_APPS_CONTROL}.
     * <p>
     * Starting from {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, the returned policy will be the
     * current resolved policy rather than the policy set by the calling admin.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *               caller is not a device admin.
     * @throws SecurityException if {@code admin} is not a device or profile owner or holder of the
     * permission {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_APPS_CONTROL}.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_APPS_CONTROL, conditional = true)
    @NonNull
    public List<String> getUserControlDisabledPackages(@Nullable ComponentName admin) {
        throwIfParentInstance("getUserControlDisabledPackages");
        if (mService != null) {
            try {
                return mService.getUserControlDisabledPackages(admin, mContext.getPackageName());
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to toggle
     * Common Criteria mode for the device. When the device is in Common Criteria mode,
     * certain device functionalities are tuned to meet the higher
     * security level required by Common Criteria certification. For example:
     * <ul>
     * <li> Bluetooth long term key material is additionally integrity-protected with AES-GCM. </li>
     * <li> WiFi configuration store is additionally integrity-protected with AES-GCM. </li>
     * </ul>
     * Common Criteria mode is disabled by default.
     *
     * <p><em>Note:</em> if Common Critera mode is turned off after being enabled previously,
     * all existing WiFi configurations will be lost.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @param enabled whether Common Criteria mode should be enabled or not.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_COMMON_CRITERIA_MODE, conditional = true)
    public void setCommonCriteriaModeEnabled(@Nullable ComponentName admin, boolean enabled) {
        throwIfParentInstance("setCommonCriteriaModeEnabled");
        if (mService != null) {
            try {
                mService.setCommonCriteriaModeEnabled(admin, mContext.getPackageName(), enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns whether Common Criteria mode is currently enabled. Device owner and profile owner of
     * an organization-owned managed profile can query its own Common Criteria mode setting by
     * calling this method with its admin {@link ComponentName}. Any caller can obtain the
     * aggregated device-wide Common Criteria mode state by passing {@code null} as the
     * {@code admin} argument.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with. Null if the
     *              caller is not a device admin.
     * @return {@code true} if Common Criteria mode is enabled, {@code false} otherwise.
     */
    public boolean isCommonCriteriaModeEnabled(@Nullable ComponentName admin) {
        throwIfParentInstance("isCommonCriteriaModeEnabled");
        if (mService != null) {
            try {
                return mService.isCommonCriteriaModeEnabled(admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Called by profile owner of an organization-owned managed profile to check whether
     * personal apps are suspended.
     *
     * @return a bitmask of reasons for personal apps suspension or
     *     {@link #PERSONAL_APPS_NOT_SUSPENDED} if apps are not suspended.
     * @see #setPersonalAppsSuspended
     */
    public @PersonalAppsSuspensionReason int getPersonalAppsSuspendedReasons(
            @NonNull ComponentName admin) {
        throwIfParentInstance("getPersonalAppsSuspendedReasons");
        if (mService != null) {
            try {
                return mService.getPersonalAppsSuspendedReasons(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by a profile owner of an organization-owned managed profile to suspend personal
     * apps on the device. When personal apps are suspended the device can only be used for calls.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @param suspended Whether personal apps should be suspended.
     * @throws IllegalStateException if the profile owner doesn't have an activity that handles
     *        {@link #ACTION_CHECK_POLICY_COMPLIANCE}
     */
    public void setPersonalAppsSuspended(@NonNull ComponentName admin, boolean suspended) {
        throwIfParentInstance("setPersonalAppsSuspended");
        if (mService != null) {
            try {
                mService.setPersonalAppsSuspended(admin, suspended);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of an organization-owned managed profile to set maximum time
     * the profile is allowed to be turned off. If the profile is turned off for longer, personal
     * apps are suspended on the device.
     *
     * <p>When personal apps are suspended, an ongoing notification about that is shown to the user.
     * When the user taps the notification, system invokes {@link #ACTION_CHECK_POLICY_COMPLIANCE}
     * in the profile owner package. Profile owner implementation that uses personal apps suspension
     * must handle this intent.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @param timeoutMillis Maximum time the profile is allowed to be off in milliseconds or 0 if
     *        not limited. The minimum non-zero value corresponds to 72 hours. If an admin sets a
     *        smaller non-zero vaulue, 72 hours will be set instead.
     * @throws IllegalStateException if the profile owner doesn't have an activity that handles
     *        {@link #ACTION_CHECK_POLICY_COMPLIANCE}
     * @see #setPersonalAppsSuspended
     */
    public void setManagedProfileMaximumTimeOff(@NonNull ComponentName admin, long timeoutMillis) {
        throwIfParentInstance("setManagedProfileMaximumTimeOff");
        if (mService != null) {
            try {
                mService.setManagedProfileMaximumTimeOff(admin, timeoutMillis);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

     /**
     * Called by a profile owner of an organization-owned managed profile to get maximum time
     * the profile is allowed to be turned off.
     *
     * @param admin Which {@link DeviceAdminReceiver} this request is associated with
     * @return Maximum time the profile is allowed to be off in milliseconds or 0 if not limited.
     * @see #setPersonalAppsSuspended
     */
    public long getManagedProfileMaximumTimeOff(@NonNull ComponentName admin) {
        throwIfParentInstance("getManagedProfileMaximumTimeOff");
        if (mService != null) {
            try {
                return mService.getManagedProfileMaximumTimeOff(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Called by a profile owner of an organization-owned managed profile to acknowledge that the
     * device is compliant and the user can turn the profile off if needed according to the maximum
     * time off policy.
     *
     * This method should be called when the device is deemed compliant after getting
     * {@link DeviceAdminReceiver#onComplianceAcknowledgementRequired(Context, Intent)} callback in
     * case it is overridden. Before this method is called the user is still free to turn the
     * profile off, but the timer won't be reset, so personal apps will be suspended sooner.
     *
     * DPCs only need acknowledging device compliance if they override
     * {@link DeviceAdminReceiver#onComplianceAcknowledgementRequired(Context, Intent)}, otherwise
     * compliance is acknowledged automatically.
     *
     * @throws IllegalStateException if the user isn't unlocked
     * @see #isComplianceAcknowledgementRequired()
     * @see #setManagedProfileMaximumTimeOff(ComponentName, long)
     * @see DeviceAdminReceiver#onComplianceAcknowledgementRequired(Context, Intent)
     */
    public void acknowledgeDeviceCompliant() {
        throwIfParentInstance("acknowledgeDeviceCompliant");
        if (mService != null) {
            try {
                mService.acknowledgeDeviceCompliant();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Called by a profile owner of an organization-owned managed profile to query whether it needs
     * to acknowledge device compliance to allow the user to turn the profile off if needed
     * according to the maximum profile time off policy.
     *
     * Normally when acknowledgement is needed the DPC gets a
     * {@link DeviceAdminReceiver#onComplianceAcknowledgementRequired(Context, Intent)} callback.
     * But if the callback was not delivered or handled for some reason, this method can be used to
     * verify if acknowledgement is needed.
     *
     * @throws IllegalStateException if the user isn't unlocked
     * @see #acknowledgeDeviceCompliant()
     * @see #setManagedProfileMaximumTimeOff(ComponentName, long)
     * @see DeviceAdminReceiver#onComplianceAcknowledgementRequired(Context, Intent)
     */
    public boolean isComplianceAcknowledgementRequired() {
        throwIfParentInstance("isComplianceAcknowledgementRequired");
        if (mService != null) {
            try {
                return mService.isComplianceAcknowledgementRequired();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code userId} has a profile owner that is capable of resetting
     * password in RUNNING_LOCKED state. For that it should have at least one direct boot aware
     * component and have an active password reset token. Can only be called by the system.
     * @hide
     */
    public boolean canProfileOwnerResetPasswordWhenLocked(int userId) {
        if (mService != null) {
            try {
                return mService.canProfileOwnerResetPasswordWhenLocked(userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Used by CTS to set the result of the next safety operation check.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(MANAGE_DEVICE_ADMINS)
    public void setNextOperationSafety(@DevicePolicyOperation int operation,
            @OperationSafetyReason int reason) {
        if (mService != null) {
            try {
                mService.setNextOperationSafety(operation, reason);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns an enrollment-specific identifier of this device, which is guaranteed to be the same
     * value for the same device, enrolled into the same organization by the same managing app.
     * This identifier is high-entropy, useful for uniquely identifying individual devices within
     * the same organisation.
     * It is available both in a work profile and on a fully-managed device.
     * The identifier would be consistent even if the work profile is removed and enrolled again
     * (to the same organization), or the device is factory reset and re-enrolled.
     *
     * Can only be called by the Profile Owner and Device Owner, and starting from Android
     * {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}, holders of the permission
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES}.
     * If {@link #setOrganizationId(String)} was not called, then the returned value will be an
     * empty string.
     *
     * <p>Note about access to device identifiers: a device owner, a profile owner of an
     * organization-owned device or the delegated certificate installer (holding the
     * {@link #DELEGATION_CERT_INSTALL} delegation) on such a device can still obtain hardware
     * identifiers by calling e.g. {@link android.os.Build#getSerial()}, in addition to using
     * this method. However, a profile owner on a personal (non organization-owned) device, or the
     * delegated certificate installer on such a device, cannot obtain hardware identifiers anymore
     * and must switch to using this method.
     *
     * @return A stable, enrollment-specific identifier.
     * @throws SecurityException if the caller is not a profile owner, device owner or holding the
     * {@link android.Manifest.permission#MANAGE_DEVICE_POLICY_CERTIFICATES} permission
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_CERTIFICATES, conditional = true)
    @SuppressLint("RequiresPermission")
    @NonNull public String getEnrollmentSpecificId() {
        throwIfParentInstance("getEnrollmentSpecificId");
        if (mService == null) {
            return "";
        }

        try {
            return mService.getEnrollmentSpecificId(mContext.getPackageName());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the Enterprise ID for the work profile or managed device. This is a requirement for
     * generating an enrollment-specific ID for the device, see {@link #getEnrollmentSpecificId()}.
     *
     * It is recommended that the Enterprise ID is at least 6 characters long, and no more than
     * 64 characters.
     *
     * @param enterpriseId An identifier of the organization this work profile or device is
     *                     enrolled into.
     */
    public void setOrganizationId(@NonNull String enterpriseId) {
        throwIfParentInstance("setOrganizationId");
        setOrganizationIdForUser(mContext.getPackageName(), enterpriseId, myUserId());
    }

    /**
     * Sets the Enterprise ID for the work profile or managed device. This is a requirement for
     * generating an enrollment-specific ID for the device, see
     * {@link #getEnrollmentSpecificId()}.
     *
     * @hide
     */
    public void setOrganizationIdForUser(@NonNull String packageName,
            @NonNull String enterpriseId, @UserIdInt int userId) {
        if (mService == null) {
            return;
        }
        try {
            mService.setOrganizationIdForUser(packageName, enterpriseId, userId);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Clears organization ID set by the DPC and resets the precomputed enrollment specific ID.
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void clearOrganizationId() {
        if (mService == null) {
            return;
        }
        try {
            mService.clearOrganizationIdForUser(myUserId());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Creates and provisions a managed profile and sets the
     * {@link ManagedProfileProvisioningParams#getProfileAdminComponentName()} as the profile
     * owner.
     *
     * <p>The method {@link #checkProvisioningPrecondition} must be returning {@link #STATUS_OK}
     * before calling this method.
     *
     * @param provisioningParams Params required to provision a managed profile,
     * see {@link ManagedProfileProvisioningParams}.
     * @return The {@link UserHandle} of the created profile or {@code null} if the service is
     * not available.
     * @throws SecurityException if the caller does not hold
     * {@link android.Manifest.permission#MANAGE_PROFILE_AND_DEVICE_OWNERS}.
     * @throws ProvisioningException if an error occurred during provisioning.
     * @hide
     */
    @Nullable
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public UserHandle createAndProvisionManagedProfile(
            @NonNull ManagedProfileProvisioningParams provisioningParams)
            throws ProvisioningException {
        if (mService == null) {
            return null;
        }
        try {
            return mService.createAndProvisionManagedProfile(
                    provisioningParams, mContext.getPackageName());
        } catch (ServiceSpecificException e) {
            throw new ProvisioningException(e, e.errorCode, getErrorMessage(e));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called when a managed profile has been provisioned.
     *
     * @throws SecurityException if the caller does not hold
     * {@link android.Manifest.permission#MANAGE_PROFILE_AND_DEVICE_OWNERS}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void finalizeWorkProfileProvisioning(
            @NonNull UserHandle managedProfileUser, @Nullable Account migratedAccount) {
        Objects.requireNonNull(managedProfileUser, "managedProfileUser can't be null");
        if (mService == null) {
            throw new IllegalStateException("Could not find DevicePolicyManagerService");
        }
        try {
            mService.finalizeWorkProfileProvisioning(managedProfileUser, migratedAccount);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * The localized error message to show to the end-user. If {@code null}, a generic error
     * message will be shown.
     */
    private String getErrorMessage(ServiceSpecificException e) {
        return null;
    }


    /**
     * Provisions a managed device and sets the {@code deviceAdminComponentName} as the device
     * owner.
     *
     * <p>The method {@link #checkProvisioningPrecondition} must be returning {@link #STATUS_OK}
     * before calling this method.
     *
     * <p>Holders of {@link android.Manifest.permission#PROVISION_DEMO_DEVICE} can call this API
     * only if {@link FullyManagedDeviceProvisioningParams#isDemoDevice()} is {@code true}.
     *
     * <p>If headless device is in {@link DeviceAdminInfo#HEADLESS_DEVICE_OWNER_MODE_SINGLE_USER}
     * mode then it sets the device owner on the first secondary user.</p>
     *
     * @param provisioningParams Params required to provision a fully managed device,
     * see {@link FullyManagedDeviceProvisioningParams}.
     *
     * @throws ProvisioningException if an error occurred during provisioning.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS,
            android.Manifest.permission.PROVISION_DEMO_DEVICE})
    public void provisionFullyManagedDevice(
            @NonNull FullyManagedDeviceProvisioningParams provisioningParams)
            throws ProvisioningException {
        if (mService != null) {
            try {
                mService.provisionFullyManagedDevice(provisioningParams, mContext.getPackageName());
            } catch (ServiceSpecificException e) {
                throw new ProvisioningException(e, e.errorCode, getErrorMessage(e));
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Resets the default cross profile intent filters that were set during
     * {@link #createAndProvisionManagedProfile} between {@code userId} and all it's managed
     * profiles if any.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void resetDefaultCrossProfileIntentFilters(@UserIdInt int userId) {
        if (mService != null) {
            try {
                mService.resetDefaultCrossProfileIntentFilters(userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns true if the caller is running on a device where an admin can grant
     * permissions related to device sensors.
     * This is a signal that the device is a fully-managed device where personal usage is
     * discouraged.
     * The list of permissions is listed in
     * {@link #setPermissionGrantState(ComponentName, String, String, int)}.
     *
     * May be called by any app.
     * @return true if an admin can grant device sensors-related permissions, false otherwise.
     */
    public boolean canAdminGrantSensorsPermissions() {
        throwIfParentInstance("canAdminGrantSensorsPermissions");
        if (mService == null) {
            return false;
        }
        try {
            return mService.canAdminGrantSensorsPermissions();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the device owner type for a managed device (e.g. financed device).
     *
     * @param admin The {@link DeviceAdminReceiver} that is the device owner.
     * @param deviceOwnerType The device owner type is set to. Use
     * {@link #DEVICE_OWNER_TYPE_DEFAULT} for the default device owner type. Use
     * {@link #DEVICE_OWNER_TYPE_FINANCED} for the financed device owner type.
     *
     * @throws IllegalStateException When admin is not the device owner, or there is no device
     *     owner, or attempting to set the device owner type again for the same admin.
     * @throws SecurityException If the caller does not have the permission
     *     {@link permission#MANAGE_PROFILE_AND_DEVICE_OWNERS}.
     *
     * @hide
     */
    @TestApi
    public void setDeviceOwnerType(@NonNull ComponentName admin,
            @DeviceOwnerType int deviceOwnerType) {
        throwIfParentInstance("setDeviceOwnerType");
        if (mService != null) {
            try {
                mService.setDeviceOwnerType(admin, deviceOwnerType);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the device owner type for the admin used in
     * {@link #setDeviceOwnerType(ComponentName, int)}. {@link #DEVICE_OWNER_TYPE_DEFAULT}
     * would be returned when the device owner type is not set for the device owner admin.
     *
     * @param admin The {@link DeviceAdminReceiver} that is the device owner.
     *
     * @throws IllegalStateException When admin is not the device owner or there is no device owner.
     *
     * @deprecated Use type-specific APIs (e.g. {@link #isFinancedDevice}).
     * @hide
     */
    @TestApi
    @DeviceOwnerType
    @Deprecated
    // TODO(b/259908270): remove
    public int getDeviceOwnerType(@NonNull ComponentName admin) {
        throwIfParentInstance("getDeviceOwnerType");
        if (mService != null) {
            try {
                return mService.getDeviceOwnerType(admin);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return DEVICE_OWNER_TYPE_DEFAULT;
    }

    /**
     * {@code true} if this device is financed.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS
    })
    public boolean isFinancedDevice() {
        return isDeviceManaged()
                && getDeviceOwnerType(getDeviceOwnerComponentOnAnyUser())
                == DEVICE_OWNER_TYPE_FINANCED;
    }

    // TODO(b/315298076): revert ag/25574027 and update the doc
    /**
     * Called by a device owner or profile owner of an organization-owned managed profile to enable
     * or disable USB data signaling for the device. When disabled, USB data connections
     * (except from charging functions) are prohibited.
     *
     * <p> This API is not supported on all devices, the caller should call
     * {@link #canUsbDataSignalingBeDisabled()} to check whether enabling or disabling USB data
     * signaling is supported on the device.
     *
     * Starting from Android 15, after the USB data signaling
     * policy has been set, {@link PolicyUpdateReceiver#onPolicySetResult(Context, String,
     * Bundle, TargetUser, PolicyUpdateResult)} will notify the admin on whether the policy was
     * successfully set or not. This callback will contain:
     * <ul>
     * <li> The {@link TargetUser} that this policy relates to
     * <li> The {@link PolicyUpdateResult}, which will be
     * {@link PolicyUpdateResult#RESULT_POLICY_SET} if the policy was successfully set or the
     * reason the policy failed to be set
     * e.g. {@link PolicyUpdateResult#RESULT_FAILURE_CONFLICTING_ADMIN_POLICY})
     * </ul>
     * If there has been a change to the policy,
     * {@link PolicyUpdateReceiver#onPolicyChanged(Context, String, Bundle, TargetUser,
     * PolicyUpdateResult)} will notify the admin of this change. This callback will contain the
     * same parameters as PolicyUpdateReceiver#onPolicySetResult and the {@link PolicyUpdateResult}
     * will contain the reason why the policy changed.
     *
     * @param enabled whether USB data signaling should be enabled or not.
     * @throws SecurityException if the caller is not permitted to set this policy
     * @throws IllegalStateException if disabling USB data signaling is not supported or
     * if USB data signaling fails to be enabled/disabled.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_USB_DATA_SIGNALLING, conditional = true)
    @SupportsCoexistence
    public void setUsbDataSignalingEnabled(boolean enabled) {
        throwIfParentInstance("setUsbDataSignalingEnabled");
        if (mService != null) {
            try {
                mService.setUsbDataSignalingEnabled(mContext.getPackageName(), enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns whether USB data signaling is currently enabled.
     *
     * <p> When called by a device owner or profile owner of an organization-owned managed profile,
     * this API returns whether USB data signaling is currently enabled by that admin. When called
     * by any other app, returns whether USB data signaling is currently enabled on the device.
     *
     * @return {@code true} if USB data signaling is enabled, {@code false} otherwise.
     */
    public boolean isUsbDataSignalingEnabled() {
        throwIfParentInstance("isUsbDataSignalingEnabled");
        if (mService != null) {
            try {
                return mService.isUsbDataSignalingEnabled(mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return true;
    }

    /**
     * Returns whether enabling or disabling USB data signaling is supported on the device.
     *
     * @return {@code true} if the device supports enabling and disabling USB data signaling.
     */
    public boolean canUsbDataSignalingBeDisabled() {
        throwIfParentInstance("canUsbDataSignalingBeDisabled");
        if (mService != null) {
            try {
                return mService.canUsbDataSignalingBeDisabled();
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Gets the list of {@link #isAffiliatedUser() affiliated} users running on foreground.
     *
     * @return list of {@link #isAffiliatedUser() affiliated} users running on foreground.
     *
     * @throws SecurityException if the calling application is not a device owner
     */
    @NonNull
    public List<UserHandle> listForegroundAffiliatedUsers() {
        if (mService == null) return Collections.emptyList();

        try {
            return mService.listForegroundAffiliatedUsers();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Lists apps that are exempt from policies (such as
     * {@link #setPackagesSuspended(ComponentName, String[], boolean)}).
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(value = MANAGE_DEVICE_ADMINS)
    public @NonNull Set<String> getPolicyExemptApps() {
        if (mService == null) return Collections.emptySet();

        try {
            return new HashSet<>(mService.listPolicyExemptApps());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent
     * from the provided {@code nfcIntent}.
     *
     * <p>Prerequisites to create the provisioning intent:
     *
     * <ul>
     * <li>{@code nfcIntent}'s action is {@link NfcAdapter#ACTION_NDEF_DISCOVERED}</li>
     * <li>{@code nfcIntent}'s NFC properties contain either
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME} or
     * {@link #EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME} </li>
     * </ul>
     *
     * This method returns {@code null} if the prerequisites are not met or if an error occurs
     * when reading the NFC properties.
     *
     * @param nfcIntent the nfc intent generated from scanning a NFC tag
     * @return a {@link #ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE} intent with
     * intent extras as read by {@code nfcIntent}'s NFC properties or {@code null} if the
     * prerequisites are not met or if an error occurs when reading the NFC properties.
     *
     * @hide
     */
    @Nullable
    @SystemApi
    public Intent createProvisioningIntentFromNfcIntent(@NonNull Intent nfcIntent) {
        return ProvisioningIntentHelper.createProvisioningIntentFromNfcIntent(nfcIntent);
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to
     * specify the minimum security level required for Wi-Fi networks.
     * The device may not connect to networks that do not meet the minimum security level.
     * If the current network does not meet the minimum security level set, it will be disconnected.
     *
     * The following shows the Wi-Fi security levels from the lowest to the highest security level:
     * {@link #WIFI_SECURITY_OPEN}
     * {@link #WIFI_SECURITY_PERSONAL}
     * {@link #WIFI_SECURITY_ENTERPRISE_EAP}
     * {@link #WIFI_SECURITY_ENTERPRISE_192}
     *
     * @param level minimum security level
     * @throws SecurityException if the caller is not permitted to set this policy
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIFI, conditional = true)
    public void setMinimumRequiredWifiSecurityLevel(@WifiSecurity int level) {
        throwIfParentInstance("setMinimumRequiredWifiSecurityLevel");
        if (mService != null) {
            try {
                mService.setMinimumRequiredWifiSecurityLevel(mContext.getPackageName(), level);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the current Wi-Fi minimum security level.
     *
     * @see #setMinimumRequiredWifiSecurityLevel(int)
     */
    public @WifiSecurity int getMinimumRequiredWifiSecurityLevel() {
        throwIfParentInstance("getMinimumRequiredWifiSecurityLevel");
        if (mService == null) {
            return WIFI_SECURITY_OPEN;
        }
        try {
            return mService.getMinimumRequiredWifiSecurityLevel();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by device owner or profile owner of an organization-owned managed profile to
     * specify the Wi-Fi SSID policy ({@link WifiSsidPolicy}).
     * Wi-Fi SSID policy specifies the SSID restriction the network must satisfy
     * in order to be eligible for a connection. Providing a null policy results in the
     * deactivation of the SSID restriction
     *
     * @param policy Wi-Fi SSID policy
     * @throws SecurityException if the caller is not permitted to manage wifi policy
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIFI, conditional = true)
    public void setWifiSsidPolicy(@Nullable WifiSsidPolicy policy) {
        throwIfParentInstance("setWifiSsidPolicy");
        if (mService == null) {
            return;
        }
        try {
            mService.setWifiSsidPolicy(mContext.getPackageName(), policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

    }

    /**
     * Returns the current Wi-Fi SSID policy.
     * If the policy has not been set, it will return NULL.
     *
     * @see #setWifiSsidPolicy(WifiSsidPolicy)
     * @throws SecurityException if the caller is not a device owner or a profile owner on
     * an organization-owned managed profile.
     */
    @RequiresPermission(value = MANAGE_DEVICE_POLICY_WIFI, conditional = true)
    @Nullable
    public WifiSsidPolicy getWifiSsidPolicy() {
        throwIfParentInstance("getWifiSsidPolicy");
        if (mService == null) {
            return null;
        }
        try {
            return mService.getWifiSsidPolicy(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     *
     * Returns whether the device considers itself to be potentially stolen.
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = QUERY_DEVICE_STOLEN_STATE)
    @FlaggedApi(FLAG_DEVICE_THEFT_API_ENABLED)
    public boolean isDevicePotentiallyStolen() {
        throwIfParentInstance("isDevicePotentiallyStolen");
        if (mService == null) {
            return false;
        }
        try {
            return mService.isDevicePotentiallyStolen(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a {@link DevicePolicyResourcesManager} containing the required APIs to set, reset,
     * and get device policy related resources.
     */
    @NonNull
    public DevicePolicyResourcesManager getResources() {
        return mResourcesManager;
    }

    /**
     * Returns a boolean for whether the DPC
     * (Device Policy Controller, the agent responsible for enforcing policy)
     * has been downloaded during provisioning.
     *
     * <p>If true is returned, then any attempts to begin setup again should result in factory reset
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public boolean isDpcDownloaded() {
        throwIfParentInstance("isDpcDownloaded");
        if (mService != null) {
            try {
                return mService.isDpcDownloaded();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Indicates that the DPC (Device Policy Controller, the agent responsible for enforcing policy)
     * has or has not been downloaded during provisioning.
     *
     * @param downloaded {@code true} if the dpc has been downloaded during provisioning.
     *                               {@code false} otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void setDpcDownloaded(boolean downloaded) {
        throwIfParentInstance("setDpcDownloaded");
        if (mService != null) {
            try {
                mService.setDpcDownloaded(downloaded);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the package name of the device policy management role holder.
     *
     * <p>If the device policy management role holder is not configured for this device, returns
     * {@code null}.
     */
    @Nullable
    public String getDevicePolicyManagementRoleHolderPackage() {
        String devicePolicyManagementConfig = mContext.getString(
                com.android.internal.R.string.config_devicePolicyManagement);
        return extractPackageNameFromDeviceManagerConfig(devicePolicyManagementConfig);
    }

    /**
     * Returns the package name of the device policy management role holder updater.
     *
     * <p>If the device policy management role holder updater is not configured for this device,
     * returns {@code null}.
     *
     * @hide
     */
    @Nullable
    @TestApi
    public String getDevicePolicyManagementRoleHolderUpdaterPackage() {
        String devicePolicyManagementUpdaterConfig = mContext.getString(
                com.android.internal.R.string.config_devicePolicyManagementUpdater);
        if (TextUtils.isEmpty(devicePolicyManagementUpdaterConfig)) {
            return null;
        }
        return devicePolicyManagementUpdaterConfig;
    }

    /**
     * Returns a {@link List} of managed profiles managed by some profile owner within the profile
     * group of the given user, or an empty {@link List} if there is not one.
     *
     * @param user the user whose profile group to look within to return managed profiles
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @NonNull
    public List<UserHandle> getPolicyManagedProfiles(@NonNull UserHandle user) {
        Objects.requireNonNull(user);
        if (mService != null) {
            try {
                return mService.getPolicyManagedProfiles(user);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Retrieves the package name for a given {@code deviceManagerConfig}.
     *
     * <p>Valid configs look like:
     * <ul>
     *     <li>{@code com.package.name}</li>
     *     <li>{@code com.package.name:<SHA256 checksum>}</li>
     * </ul>
     *
     * <p>If the supplied {@code deviceManagerConfig} is {@code null} or empty, returns
     * {@code null}.
     */
    @Nullable
    private String extractPackageNameFromDeviceManagerConfig(
            @Nullable String deviceManagerConfig) {
        if (TextUtils.isEmpty(deviceManagerConfig)) {
            return null;
        }
        if (deviceManagerConfig.contains(":")) {
            return deviceManagerConfig.split(":")[0];
        }
        return deviceManagerConfig;
    }

    /**
     * Reset cache for {@link #shouldAllowBypassingDevicePolicyManagementRoleQualification}.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_ROLE_HOLDERS)
    public void resetShouldAllowBypassingDevicePolicyManagementRoleQualificationState() {
        if (mService != null) {
            try {
                mService.resetShouldAllowBypassingDevicePolicyManagementRoleQualificationState();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Recalculate the incompatible accounts cache.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void calculateHasIncompatibleAccounts() {
        if (mService != null) {
            try {
                mService.calculateHasIncompatibleAccounts();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @return {@code true} if bypassing the device policy management role qualification is allowed
     * with the current state of the device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_ROLE_HOLDERS)
    public boolean shouldAllowBypassingDevicePolicyManagementRoleQualification() {
        if (mService != null) {
            try {
                return mService.shouldAllowBypassingDevicePolicyManagementRoleQualification();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns a {@link DevicePolicyState} object containing information about the current state
     * of device policies (e.g. values set by different admins, info about the enforcing admins,
     * resolved policy, etc).
     *
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public DevicePolicyState getDevicePolicyState() {
        if (mService != null) {
            try {
                return mService.getDevicePolicyState();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    /**
     * Triggers the data migration of device policies for existing DPCs to the Device Policy Engine.
     * If {@code forceMigration} is set to {@code true} it skips the prerequisite checks before
     * triggering the migration.
     *
     * <p>Returns {@code true} if migration was completed successfully, {@code false} otherwise.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public boolean triggerDevicePolicyEngineMigration(boolean forceMigration) {
        if (mService != null) {
            try {
                return mService.triggerDevicePolicyEngineMigration(forceMigration);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if this device is marked as a financed device.
     *
     * <p>A financed device can be entered into lock task mode (see {@link #setLockTaskPackages})
     * by the holder of the role {@code android.app.role.RoleManager#ROLE_FINANCED_DEVICE_KIOSK}.
     * If this occurs, Device Owners and Profile Owners that have set lock task packages or
     * features, or that attempt to set lock task packages or features, will receive a callback
     * indicating that it could not be set. See {@link PolicyUpdateReceiver#onPolicyChanged} and
     * {@link PolicyUpdateReceiver#onPolicySetResult}.
     *
     * <p>To be informed of changes to this status you can subscribe to the broadcast
     * {@link #ACTION_DEVICE_FINANCING_STATE_CHANGED}.
     *
     * @throws SecurityException if the caller is not a device owner, profile owner of an
     * organization-owned managed profile, profile owner on the primary user or holder of one of the
     * following roles: {@code android.app.role.RoleManager.ROLE_DEVICE_POLICY_MANAGEMENT},
     * {@code android.app.role.RoleManager.ROLE_SYSTEM_SUPERVISION}.
     */
    public boolean isDeviceFinanced() {
        throwIfParentInstance("isDeviceFinanced");
        if (mService != null) {
            try {
                return mService.isDeviceFinanced(mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Returns the package name of the application holding the role:
     * {@link android.app.role.RoleManager#ROLE_FINANCED_DEVICE_KIOSK}.
     *
     * @return the package name of the application holding the role or {@code null} if the role is
     * not held by any applications.
     * @hide
     */
    @SystemApi
    @RequiresPermission(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @Nullable
    public String getFinancedDeviceKioskRoleHolder() {
        if (mService != null) {
            try {
                return mService.getFinancedDeviceKioskRoleHolder(mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }

    // TODO(b/308755220): Remove once the build is finalised.
    /**
     * Returns true if the flag for the onboarding bugreport V2 is enabled.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isOnboardingBugreportV2FlagEnabled() {
        return onboardingBugreportV2Enabled();
    }

    // TODO(b/308755220): Remove once the build is finalised.
    /**
     * Returns true if the flag for consentless bugreports is enabled.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isOnboardingConsentlessBugreportFlagEnabled() {
        return onboardingConsentlessBugreports();
    }

    /**
     * Returns the subscription ids of all subscriptions which were downloaded by the calling
     * admin.
     *
     * <p> This returns only the subscriptions which were downloaded by the calling admin via
     *      {@link android.telephony.euicc.EuiccManager#downloadSubscription}.
     *      If a subscription is returned by this method then in it subject to management controls
     *      and cannot be removed by users.
     *
     * <p> Callable by device owners and profile owners.
     *
     * @throws SecurityException if the caller is not authorized to call this method.
     * @return ids of all managed subscriptions currently downloaded by an admin on the device.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DEVICE_POLICY_MANAGED_SUBSCRIPTIONS)
    @NonNull
    public Set<Integer> getSubscriptionIds() {
        throwIfParentInstance("getSubscriptionIds");
        if (mService != null) {
            try {
                return intArrayToSet(mService.getSubscriptionIds(mContext.getPackageName()));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return new HashSet<>();
    }

    /**
     * Controls the maximum storage size allowed for policies associated with an admin.
     * Setting a limit of -1 effectively removes any storage restrictions.
     *
     * @param storageLimit Maximum storage allowed in bytes. Use -1 to disable limits.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public void setMaxPolicyStorageLimit(int storageLimit) {
        if (mService != null) {
            try {
                mService.setMaxPolicyStorageLimit(mContext.getPackageName(), storageLimit);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieves the current maximum storage limit for policies associated with an admin.
     *
     * @return The maximum storage limit in bytes, or -1 if no limit is enforced.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    public int getMaxPolicyStorageLimit() {
        if (mService != null) {
            try {
                return mService.getMaxPolicyStorageLimit(mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return -1;
    }

    /**
     * Force sets the maximum storage size allowed for policies associated with an admin regardless
     * of the default value set in the system, unlike {@link #setMaxPolicyStorageLimit} which can
     * only set it to a value higher than the default value set by the system.Setting a limit of -1
     * effectively removes any storage restrictions.
     *
     * @param storageLimit Maximum storage allowed in bytes. Use -1 to disable limits.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_STORAGE_LIMIT)
    public void forceSetMaxPolicyStorageLimit(int storageLimit) {
        if (mService != null) {
            try {
                mService.forceSetMaxPolicyStorageLimit(mContext.getPackageName(), storageLimit);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Retrieves the size of the current policies set by the {@code admin}.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(permission.MANAGE_DEVICE_POLICY_STORAGE_LIMIT)
    public int getPolicySizeForAdmin(@NonNull EnforcingAdmin admin) {
        if (mService != null) {
            try {
                return mService.getPolicySizeForAdmin(mContext.getPackageName(), admin);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return -1;
    }

    /**
     * @return The headless device owner mode for the current set DO, returns
     * {@link DeviceAdminInfo#HEADLESS_DEVICE_OWNER_MODE_UNSUPPORTED} if no DO is set.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(permission.MANAGE_PROFILE_AND_DEVICE_OWNERS)
    @DeviceAdminInfo.HeadlessDeviceOwnerMode
    public int getHeadlessDeviceOwnerMode() {
        if (mService != null) {
            try {
                return mService.getHeadlessDeviceOwnerMode(mContext.getPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return HEADLESS_DEVICE_OWNER_MODE_UNSUPPORTED;
    }
}