/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.FileUtils;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.IndentingPrintWriter;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.util.JournaledFile;
import com.android.internal.util.XmlUtils;
import com.android.server.utils.Slogf;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

class DevicePolicyData {
    private static final String TAG_ACCEPTED_CA_CERTIFICATES = "accepted-ca-certificate";
    private static final String TAG_LOCK_TASK_COMPONENTS = "lock-task-component";
    private static final String TAG_LOCK_TASK_FEATURES = "lock-task-features";
    private static final String TAG_STATUS_BAR = "statusbar";
    private static final String TAG_APPS_SUSPENDED = "apps-suspended";
    private static final String TAG_SECONDARY_LOCK_SCREEN = "secondary-lock-screen";
    private static final String TAG_DO_NOT_ASK_CREDENTIALS_ON_BOOT =
            "do-not-ask-credentials-on-boot";
    private static final String TAG_AFFILIATION_ID = "affiliation-id";
    private static final String TAG_LAST_SECURITY_LOG_RETRIEVAL = "last-security-log-retrieval";
    private static final String TAG_LAST_BUG_REPORT_REQUEST = "last-bug-report-request";
    private static final String TAG_LAST_NETWORK_LOG_RETRIEVAL = "last-network-log-retrieval";
    private static final String TAG_ADMIN_BROADCAST_PENDING = "admin-broadcast-pending";
    private static final String TAG_CURRENT_INPUT_METHOD_SET = "current-ime-set";
    private static final String TAG_OWNER_INSTALLED_CA_CERT = "owner-installed-ca-cert";
    private static final String TAG_INITIALIZATION_BUNDLE = "initialization-bundle";
    private static final String TAG_PASSWORD_VALIDITY = "password-validity";
    private static final String TAG_PASSWORD_TOKEN_HANDLE = "password-token";
    private static final String TAG_PROTECTED_PACKAGES = "protected-packages";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_ALIAS = "alias";
    private static final String ATTR_ID = "id";
    private static final String ATTR_PERMISSION_PROVIDER = "permission-provider";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_DISABLED = "disabled";
    private static final String ATTR_SETUP_COMPLETE = "setup-complete";
    private static final String ATTR_PROVISIONING_STATE = "provisioning-state";
    private static final String ATTR_PERMISSION_POLICY = "permission-policy";
    private static final String ATTR_DEVICE_PROVISIONING_CONFIG_APPLIED =
            "device-provisioning-config-applied";
    private static final String ATTR_DEVICE_PAIRED = "device-paired";
    private static final String ATTR_NEW_USER_DISCLAIMER = "new-user-disclaimer";

    // Values of ATTR_NEW_USER_DISCLAIMER
    static final String NEW_USER_DISCLAIMER_ACKNOWLEDGED = "acked";
    static final String NEW_USER_DISCLAIMER_NOT_NEEDED = "not_needed";
    static final String NEW_USER_DISCLAIMER_NEEDED = "needed";

    private static final String ATTR_FACTORY_RESET_FLAGS = "factory-reset-flags";
    private static final String ATTR_FACTORY_RESET_REASON = "factory-reset-reason";

    // NOTE: must be public because of DebugUtils.flagsToString()
    public static final int FACTORY_RESET_FLAG_ON_BOOT = 1;
    public static final int FACTORY_RESET_FLAG_WIPE_EXTERNAL_STORAGE = 2;
    public static final int FACTORY_RESET_FLAG_WIPE_EUICC = 4;
    public static final int FACTORY_RESET_FLAG_WIPE_FACTORY_RESET_PROTECTION = 8;

    private static final String TAG = DevicePolicyManagerService.LOG_TAG;
    private static final boolean VERBOSE_LOG = false; // DO NOT SUBMIT WITH TRUE

    int mFailedPasswordAttempts = 0;
    boolean mPasswordValidAtLastCheckpoint = true;

    final @UserIdInt int mUserId;
    int mPasswordOwner = -1;
    long mLastMaximumTimeToLock = -1;
    boolean mUserSetupComplete = false;
    boolean mPaired = false;
    int mUserProvisioningState;
    int mPermissionPolicy;

    int mFactoryResetFlags;
    String mFactoryResetReason;

    boolean mDeviceProvisioningConfigApplied = false;

    final ArrayMap<ComponentName, ActiveAdmin> mAdminMap = new ArrayMap<>();
    final ArrayList<ActiveAdmin> mAdminList = new ArrayList<>();
    final ArrayList<ComponentName> mRemovingAdmins = new ArrayList<>();

    // TODO(b/35385311): Keep track of metadata in TrustedCertificateStore instead.
    final ArraySet<String> mAcceptedCaCertificates = new ArraySet<>();

    // This is the list of component allowed to start lock task mode.
    List<String> mLockTaskPackages = new ArrayList<>();

    // List of packages protected by device owner
    List<String> mUserControlDisabledPackages = new ArrayList<>();

    // Bitfield of feature flags to be enabled during LockTask mode.
    // We default on the power button menu, in order to be consistent with pre-P behaviour.
    int mLockTaskFeatures = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;

    boolean mStatusBarDisabled = false;

    ComponentName mRestrictionsProvider;

    // Map of delegate package to delegation scopes
    final ArrayMap<String, List<String>> mDelegationMap = new ArrayMap<>();

    boolean mDoNotAskCredentialsOnBoot = false;

    Set<String> mAffiliationIds = new ArraySet<>();

    long mLastSecurityLogRetrievalTime = -1;

    long mLastBugReportRequestTime = -1;

    long mLastNetworkLogsRetrievalTime = -1;

    boolean mCurrentInputMethodSet = false;

    boolean mSecondaryLockscreenEnabled = false;

    // TODO(b/35385311): Keep track of metadata in TrustedCertificateStore instead.
    Set<String> mOwnerInstalledCaCerts = new ArraySet<>();

    // Used for initialization of users created by createAndManageUser.
    boolean mAdminBroadcastPending = false;
    PersistableBundle mInitBundle = null;

    long mPasswordTokenHandle = 0;

    // Whether user's apps are suspended. This flag should only be written AFTER all the needed
    // apps were suspended or unsuspended.
    boolean mAppsSuspended = false;

    // Whether it's necessary to show a disclaimer (that the device is managed) after the user
    // starts.
    String mNewUserDisclaimer = NEW_USER_DISCLAIMER_NOT_NEEDED;

    DevicePolicyData(@UserIdInt int userId) {
        mUserId = userId;
    }

    /**
     * Serializes DevicePolicyData object as XML.
     */
    static boolean store(DevicePolicyData policyData, JournaledFile file, boolean isFdeDevice) {
        FileOutputStream stream = null;
        File chooseForWrite = null;
        try {
            chooseForWrite = file.chooseForWrite();
            if (VERBOSE_LOG) {
                Slogf.v(TAG, "Storing data for user %d on %s ", policyData.mUserId, chooseForWrite);
            }
            stream = new FileOutputStream(chooseForWrite, false);
            TypedXmlSerializer out = Xml.resolveSerializer(stream);
            out.startDocument(null, true);

            out.startTag(null, "policies");
            if (policyData.mRestrictionsProvider != null) {
                out.attribute(null, ATTR_PERMISSION_PROVIDER,
                        policyData.mRestrictionsProvider.flattenToString());
            }
            if (policyData.mUserSetupComplete) {
                if (VERBOSE_LOG) Slogf.v(TAG, "setting %s to true", ATTR_SETUP_COMPLETE);
                out.attributeBoolean(null, ATTR_SETUP_COMPLETE, true);
            }
            if (policyData.mPaired) {
                out.attributeBoolean(null, ATTR_DEVICE_PAIRED, true);
            }
            if (policyData.mDeviceProvisioningConfigApplied) {
                out.attributeBoolean(null, ATTR_DEVICE_PROVISIONING_CONFIG_APPLIED, true);
            }
            if (policyData.mUserProvisioningState != DevicePolicyManager.STATE_USER_UNMANAGED) {
                out.attributeInt(null, ATTR_PROVISIONING_STATE, policyData.mUserProvisioningState);
            }
            if (policyData.mPermissionPolicy != DevicePolicyManager.PERMISSION_POLICY_PROMPT) {
                out.attributeInt(null, ATTR_PERMISSION_POLICY, policyData.mPermissionPolicy);
            }
            if (NEW_USER_DISCLAIMER_NEEDED.equals(policyData.mNewUserDisclaimer)) {
                out.attribute(null, ATTR_NEW_USER_DISCLAIMER, policyData.mNewUserDisclaimer);
            }

            if (policyData.mFactoryResetFlags != 0) {
                if (VERBOSE_LOG) {
                    Slogf.v(TAG, "Storing factory reset flags for user %d: %s", policyData.mUserId,
                            factoryResetFlagsToString(policyData.mFactoryResetFlags));
                }
                out.attributeInt(null, ATTR_FACTORY_RESET_FLAGS, policyData.mFactoryResetFlags);
            }
            if (policyData.mFactoryResetReason != null) {
                out.attribute(null, ATTR_FACTORY_RESET_REASON, policyData.mFactoryResetReason);
            }

            // Serialize delegations.
            for (int i = 0; i < policyData.mDelegationMap.size(); ++i) {
                final String delegatePackage = policyData.mDelegationMap.keyAt(i);
                final List<String> scopes = policyData.mDelegationMap.valueAt(i);

                // Every "delegation" tag serializes the information of one delegate-scope pair.
                for (String scope : scopes) {
                    out.startTag(null, "delegation");
                    out.attribute(null, "delegatePackage", delegatePackage);
                    out.attribute(null, "scope", scope);
                    out.endTag(null, "delegation");
                }
            }

            final int n = policyData.mAdminList.size();
            for (int i = 0; i < n; i++) {
                ActiveAdmin ap = policyData.mAdminList.get(i);
                if (ap != null) {
                    out.startTag(null, "admin");
                    out.attribute(null, "name", ap.info.getComponent().flattenToString());
                    ap.writeToXml(out);
                    out.endTag(null, "admin");
                }
            }

            if (policyData.mPasswordOwner >= 0) {
                out.startTag(null, "password-owner");
                out.attributeInt(null, "value", policyData.mPasswordOwner);
                out.endTag(null, "password-owner");
            }

            if (policyData.mFailedPasswordAttempts != 0) {
                out.startTag(null, "failed-password-attempts");
                out.attributeInt(null, "value", policyData.mFailedPasswordAttempts);
                out.endTag(null, "failed-password-attempts");
            }

            // For FDE devices only, we save this flag so we can report on password sufficiency
            // before the user enters their password for the first time after a reboot.  For
            // security reasons, we don't want to store the full set of active password metrics.
            if (isFdeDevice) {
                out.startTag(null, TAG_PASSWORD_VALIDITY);
                out.attributeBoolean(null, ATTR_VALUE, policyData.mPasswordValidAtLastCheckpoint);
                out.endTag(null, TAG_PASSWORD_VALIDITY);
            }

            for (int i = 0; i < policyData.mAcceptedCaCertificates.size(); i++) {
                out.startTag(null, TAG_ACCEPTED_CA_CERTIFICATES);
                out.attribute(null, ATTR_NAME, policyData.mAcceptedCaCertificates.valueAt(i));
                out.endTag(null, TAG_ACCEPTED_CA_CERTIFICATES);
            }

            for (int i = 0; i < policyData.mLockTaskPackages.size(); i++) {
                String component = policyData.mLockTaskPackages.get(i);
                out.startTag(null, TAG_LOCK_TASK_COMPONENTS);
                out.attribute(null, "name", component);
                out.endTag(null, TAG_LOCK_TASK_COMPONENTS);
            }

            if (policyData.mLockTaskFeatures != DevicePolicyManager.LOCK_TASK_FEATURE_NONE) {
                out.startTag(null, TAG_LOCK_TASK_FEATURES);
                out.attributeInt(null, ATTR_VALUE, policyData.mLockTaskFeatures);
                out.endTag(null, TAG_LOCK_TASK_FEATURES);
            }

            if (policyData.mSecondaryLockscreenEnabled) {
                out.startTag(null, TAG_SECONDARY_LOCK_SCREEN);
                out.attributeBoolean(null, ATTR_VALUE, true);
                out.endTag(null, TAG_SECONDARY_LOCK_SCREEN);
            }

            if (policyData.mStatusBarDisabled) {
                out.startTag(null, TAG_STATUS_BAR);
                out.attributeBoolean(null, ATTR_DISABLED, policyData.mStatusBarDisabled);
                out.endTag(null, TAG_STATUS_BAR);
            }

            if (policyData.mDoNotAskCredentialsOnBoot) {
                out.startTag(null, TAG_DO_NOT_ASK_CREDENTIALS_ON_BOOT);
                out.endTag(null, TAG_DO_NOT_ASK_CREDENTIALS_ON_BOOT);
            }

            for (String id : policyData.mAffiliationIds) {
                out.startTag(null, TAG_AFFILIATION_ID);
                out.attribute(null, ATTR_ID, id);
                out.endTag(null, TAG_AFFILIATION_ID);
            }

            if (policyData.mLastSecurityLogRetrievalTime >= 0) {
                out.startTag(null, TAG_LAST_SECURITY_LOG_RETRIEVAL);
                out.attributeLong(null, ATTR_VALUE, policyData.mLastSecurityLogRetrievalTime);
                out.endTag(null, TAG_LAST_SECURITY_LOG_RETRIEVAL);
            }

            if (policyData.mLastBugReportRequestTime >= 0) {
                out.startTag(null, TAG_LAST_BUG_REPORT_REQUEST);
                out.attributeLong(null, ATTR_VALUE, policyData.mLastBugReportRequestTime);
                out.endTag(null, TAG_LAST_BUG_REPORT_REQUEST);
            }

            if (policyData.mLastNetworkLogsRetrievalTime >= 0) {
                out.startTag(null, TAG_LAST_NETWORK_LOG_RETRIEVAL);
                out.attributeLong(null, ATTR_VALUE, policyData.mLastNetworkLogsRetrievalTime);
                out.endTag(null, TAG_LAST_NETWORK_LOG_RETRIEVAL);
            }

            if (policyData.mAdminBroadcastPending) {
                out.startTag(null, TAG_ADMIN_BROADCAST_PENDING);
                out.attributeBoolean(null, ATTR_VALUE, policyData.mAdminBroadcastPending);
                out.endTag(null, TAG_ADMIN_BROADCAST_PENDING);
            }

            if (policyData.mInitBundle != null) {
                out.startTag(null, TAG_INITIALIZATION_BUNDLE);
                policyData.mInitBundle.saveToXml(out);
                out.endTag(null, TAG_INITIALIZATION_BUNDLE);
            }

            if (policyData.mPasswordTokenHandle != 0) {
                out.startTag(null, TAG_PASSWORD_TOKEN_HANDLE);
                out.attributeLong(null, ATTR_VALUE, policyData.mPasswordTokenHandle);
                out.endTag(null, TAG_PASSWORD_TOKEN_HANDLE);
            }

            if (policyData.mCurrentInputMethodSet) {
                out.startTag(null, TAG_CURRENT_INPUT_METHOD_SET);
                out.endTag(null, TAG_CURRENT_INPUT_METHOD_SET);
            }

            for (final String cert : policyData.mOwnerInstalledCaCerts) {
                out.startTag(null, TAG_OWNER_INSTALLED_CA_CERT);
                out.attribute(null, ATTR_ALIAS, cert);
                out.endTag(null, TAG_OWNER_INSTALLED_CA_CERT);
            }

            for (int i = 0, size = policyData.mUserControlDisabledPackages.size(); i < size; i++) {
                String packageName = policyData.mUserControlDisabledPackages.get(i);
                out.startTag(null, TAG_PROTECTED_PACKAGES);
                out.attribute(null, ATTR_NAME, packageName);
                out.endTag(null, TAG_PROTECTED_PACKAGES);
            }

            if (policyData.mAppsSuspended) {
                out.startTag(null, TAG_APPS_SUSPENDED);
                out.attributeBoolean(null, ATTR_VALUE, policyData.mAppsSuspended);
                out.endTag(null, TAG_APPS_SUSPENDED);
            }

            out.endTag(null, "policies");

            out.endDocument();
            stream.flush();
            FileUtils.sync(stream);
            stream.close();
            file.commit();
            return true;
        } catch (XmlPullParserException | IOException e) {
            Slogf.w(TAG, e, "failed writing file %s", chooseForWrite);
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
                // Ignore
            }
            file.rollback();
            return false;
        }
    }

    /**
     * @param adminInfoSupplier function that queries DeviceAdminInfo from PackageManager
     * @param ownerComponent device or profile owner component if any.
     */
    static void load(DevicePolicyData policy, boolean isFdeDevice, JournaledFile journaledFile,
            Function<ComponentName, DeviceAdminInfo> adminInfoSupplier,
            ComponentName ownerComponent) {
        FileInputStream stream = null;
        File file = journaledFile.chooseForRead();
        if (VERBOSE_LOG) Slogf.v(TAG, "Loading data for user %d from %s", policy.mUserId, file);
        boolean needsRewrite = false;
        try {
            stream = new FileInputStream(file);
            TypedXmlPullParser parser = Xml.resolvePullParser(stream);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }
            String tag = parser.getName();
            if (!"policies".equals(tag)) {
                throw new XmlPullParserException(
                        "Settings do not start with policies tag: found " + tag);
            }

            // Extract the permission provider component name if available
            String permissionProvider = parser.getAttributeValue(null, ATTR_PERMISSION_PROVIDER);
            if (permissionProvider != null) {
                policy.mRestrictionsProvider =
                        ComponentName.unflattenFromString(permissionProvider);
            }
            String userSetupComplete = parser.getAttributeValue(null, ATTR_SETUP_COMPLETE);
            if (Boolean.toString(true).equals(userSetupComplete)) {
                if (VERBOSE_LOG) Slogf.v(TAG, "setting mUserSetupComplete to true");
                policy.mUserSetupComplete = true;
            }
            String paired = parser.getAttributeValue(null, ATTR_DEVICE_PAIRED);
            if (Boolean.toString(true).equals(paired)) {
                policy.mPaired = true;
            }
            String deviceProvisioningConfigApplied = parser.getAttributeValue(null,
                    ATTR_DEVICE_PROVISIONING_CONFIG_APPLIED);
            if (Boolean.toString(true).equals(deviceProvisioningConfigApplied)) {
                policy.mDeviceProvisioningConfigApplied = true;
            }
            int provisioningState = parser.getAttributeInt(null, ATTR_PROVISIONING_STATE, -1);
            if (provisioningState != -1) {
                policy.mUserProvisioningState = provisioningState;
            }
            int permissionPolicy = parser.getAttributeInt(null, ATTR_PERMISSION_POLICY, -1);
            if (permissionPolicy != -1) {
                policy.mPermissionPolicy = permissionPolicy;
            }
            policy.mNewUserDisclaimer = parser.getAttributeValue(null, ATTR_NEW_USER_DISCLAIMER);

            policy.mFactoryResetFlags = parser.getAttributeInt(null, ATTR_FACTORY_RESET_FLAGS, 0);
            if (VERBOSE_LOG) {
                Slogf.v(TAG, "Restored factory reset flags for user %d: %s", policy.mUserId,
                        factoryResetFlagsToString(policy.mFactoryResetFlags));
            }
            policy.mFactoryResetReason = parser.getAttributeValue(null, ATTR_FACTORY_RESET_REASON);

            int outerDepth = parser.getDepth();
            policy.mLockTaskPackages.clear();
            policy.mAdminList.clear();
            policy.mAdminMap.clear();
            policy.mAffiliationIds.clear();
            policy.mOwnerInstalledCaCerts.clear();
            policy.mUserControlDisabledPackages.clear();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }
                tag = parser.getName();
                if ("admin".equals(tag)) {
                    String name = parser.getAttributeValue(null, "name");
                    try {
                        DeviceAdminInfo dai = adminInfoSupplier.apply(
                                ComponentName.unflattenFromString(name));

                        if (dai != null) {
                            // b/123415062: If DA, overwrite with the stored policies that were
                            // agreed by the user to prevent apps from sneaking additional policies
                            // into updates.
                            boolean overwritePolicies = !dai.getComponent().equals(ownerComponent);
                            ActiveAdmin ap = new ActiveAdmin(dai, /* parent */ false);
                            ap.readFromXml(parser, overwritePolicies);
                            policy.mAdminMap.put(ap.info.getComponent(), ap);
                        }
                    } catch (RuntimeException e) {
                        Slogf.w(TAG, e, "Failed loading admin %s", name);
                    }
                } else if ("delegation".equals(tag)) {
                    // Parse delegation info.
                    final String delegatePackage = parser.getAttributeValue(null,
                            "delegatePackage");
                    final String scope = parser.getAttributeValue(null, "scope");

                    // Get a reference to the scopes list for the delegatePackage.
                    List<String> scopes = policy.mDelegationMap.get(delegatePackage);
                    // Or make a new list if none was found.
                    if (scopes == null) {
                        scopes = new ArrayList<>();
                        policy.mDelegationMap.put(delegatePackage, scopes);
                    }
                    // Add the new scope to the list of delegatePackage if it's not already there.
                    if (!scopes.contains(scope)) {
                        scopes.add(scope);
                    }
                } else if ("failed-password-attempts".equals(tag)) {
                    policy.mFailedPasswordAttempts = parser.getAttributeInt(null, "value");
                } else if ("password-owner".equals(tag)) {
                    policy.mPasswordOwner = parser.getAttributeInt(null, "value");
                } else if (TAG_ACCEPTED_CA_CERTIFICATES.equals(tag)) {
                    policy.mAcceptedCaCertificates.add(parser.getAttributeValue(null, ATTR_NAME));
                } else if (TAG_LOCK_TASK_COMPONENTS.equals(tag)) {
                    policy.mLockTaskPackages.add(parser.getAttributeValue(null, "name"));
                } else if (TAG_LOCK_TASK_FEATURES.equals(tag)) {
                    policy.mLockTaskFeatures = parser.getAttributeInt(null, ATTR_VALUE);
                } else if (TAG_SECONDARY_LOCK_SCREEN.equals(tag)) {
                    policy.mSecondaryLockscreenEnabled =
                            parser.getAttributeBoolean(null, ATTR_VALUE, false);
                } else if (TAG_STATUS_BAR.equals(tag)) {
                    policy.mStatusBarDisabled =
                            parser.getAttributeBoolean(null, ATTR_DISABLED, false);
                } else if (TAG_DO_NOT_ASK_CREDENTIALS_ON_BOOT.equals(tag)) {
                    policy.mDoNotAskCredentialsOnBoot = true;
                } else if (TAG_AFFILIATION_ID.equals(tag)) {
                    policy.mAffiliationIds.add(parser.getAttributeValue(null, ATTR_ID));
                } else if (TAG_LAST_SECURITY_LOG_RETRIEVAL.equals(tag)) {
                    policy.mLastSecurityLogRetrievalTime =
                            parser.getAttributeLong(null, ATTR_VALUE);
                } else if (TAG_LAST_BUG_REPORT_REQUEST.equals(tag)) {
                    policy.mLastBugReportRequestTime =
                            parser.getAttributeLong(null, ATTR_VALUE);
                } else if (TAG_LAST_NETWORK_LOG_RETRIEVAL.equals(tag)) {
                    policy.mLastNetworkLogsRetrievalTime =
                            parser.getAttributeLong(null, ATTR_VALUE);
                } else if (TAG_ADMIN_BROADCAST_PENDING.equals(tag)) {
                    String pending = parser.getAttributeValue(null, ATTR_VALUE);
                    policy.mAdminBroadcastPending = Boolean.toString(true).equals(pending);
                } else if (TAG_INITIALIZATION_BUNDLE.equals(tag)) {
                    policy.mInitBundle = PersistableBundle.restoreFromXml(parser);
                } else if (TAG_PASSWORD_VALIDITY.equals(tag)) {
                    if (isFdeDevice) {
                        // This flag is only used for FDE devices
                        policy.mPasswordValidAtLastCheckpoint =
                                parser.getAttributeBoolean(null, ATTR_VALUE, false);
                    }
                } else if (TAG_PASSWORD_TOKEN_HANDLE.equals(tag)) {
                    policy.mPasswordTokenHandle = parser.getAttributeLong(null, ATTR_VALUE);
                } else if (TAG_CURRENT_INPUT_METHOD_SET.equals(tag)) {
                    policy.mCurrentInputMethodSet = true;
                } else if (TAG_OWNER_INSTALLED_CA_CERT.equals(tag)) {
                    policy.mOwnerInstalledCaCerts.add(parser.getAttributeValue(null, ATTR_ALIAS));
                } else if (TAG_PROTECTED_PACKAGES.equals(tag)) {
                    policy.mUserControlDisabledPackages.add(
                            parser.getAttributeValue(null, ATTR_NAME));
                } else if (TAG_APPS_SUSPENDED.equals(tag)) {
                    policy.mAppsSuspended =
                            parser.getAttributeBoolean(null, ATTR_VALUE, false);
                } else {
                    Slogf.w(TAG, "Unknown tag: %s", tag);
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        } catch (FileNotFoundException e) {
            // Don't be noisy, this is normal if we haven't defined any policies.
        } catch (NullPointerException | NumberFormatException | XmlPullParserException | IOException
                | IndexOutOfBoundsException e) {
            Slogf.w(TAG, e, "failed parsing %s", file);
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        // Generate a list of admins from the admin map
        policy.mAdminList.addAll(policy.mAdminMap.values());
    }

    void validatePasswordOwner() {
        if (mPasswordOwner >= 0) {
            boolean haveOwner = false;
            for (int i = mAdminList.size() - 1; i >= 0; i--) {
                if (mAdminList.get(i).getUid() == mPasswordOwner) {
                    haveOwner = true;
                    break;
                }
            }
            if (!haveOwner) {
                Slogf.w(TAG, "Previous password owner %s no longer active; disabling",
                        mPasswordOwner);
                mPasswordOwner = -1;
            }
        }
    }

    void setDelayedFactoryReset(@NonNull String reason, boolean wipeExtRequested, boolean wipeEuicc,
            boolean wipeResetProtectionData) {
        mFactoryResetReason = reason;

        mFactoryResetFlags = FACTORY_RESET_FLAG_ON_BOOT;
        if (wipeExtRequested) {
            mFactoryResetFlags |= FACTORY_RESET_FLAG_WIPE_EXTERNAL_STORAGE;
        }
        if (wipeEuicc) {
            mFactoryResetFlags |= FACTORY_RESET_FLAG_WIPE_EUICC;
        }
        if (wipeResetProtectionData) {
            mFactoryResetFlags |= FACTORY_RESET_FLAG_WIPE_FACTORY_RESET_PROTECTION;
        }
    }

    boolean isNewUserDisclaimerAcknowledged() {
        if (mNewUserDisclaimer == null) {
            if (mUserId == UserHandle.USER_SYSTEM) {
                return true;
            }
            Slogf.w(TAG, "isNewUserDisclaimerAcknowledged(%d): mNewUserDisclaimer is null",
                    mUserId);
            return false;
        }
        switch (mNewUserDisclaimer) {
            case NEW_USER_DISCLAIMER_ACKNOWLEDGED:
            case NEW_USER_DISCLAIMER_NOT_NEEDED:
                return true;
            case NEW_USER_DISCLAIMER_NEEDED:
                return false;
            default:
                Slogf.w(TAG, "isNewUserDisclaimerAcknowledged(%d): invalid value %d", mUserId,
                        mNewUserDisclaimer);
                return false;
        }
    }

    void dump(IndentingPrintWriter pw) {
        pw.println();
        pw.println("Enabled Device Admins (User " + mUserId + ", provisioningState: "
                + mUserProvisioningState + "):");
        final int n = mAdminList.size();
        for (int i = 0; i < n; i++) {
            ActiveAdmin ap = mAdminList.get(i);
            if (ap != null) {
                pw.increaseIndent();
                pw.print(ap.info.getComponent().flattenToShortString());
                pw.println(":");
                pw.increaseIndent();
                ap.dump(pw);
                pw.decreaseIndent();
                pw.decreaseIndent();
            }
        }
        if (!mRemovingAdmins.isEmpty()) {
            pw.increaseIndent();
            pw.println("Removing Device Admins (User " + mUserId + "): " + mRemovingAdmins);
            pw.decreaseIndent();
        }
        pw.println();
        pw.increaseIndent();
        pw.print("mPasswordOwner="); pw.println(mPasswordOwner);
        pw.print("mUserControlDisabledPackages=");
        pw.println(mUserControlDisabledPackages);
        pw.print("mAppsSuspended="); pw.println(mAppsSuspended);
        pw.print("mUserSetupComplete="); pw.println(mUserSetupComplete);
        pw.print("mAffiliationIds="); pw.println(mAffiliationIds);
        pw.print("mNewUserDisclaimer="); pw.println(mNewUserDisclaimer);
        if (mFactoryResetFlags != 0) {
            pw.print("mFactoryResetFlags="); pw.print(mFactoryResetFlags);
            pw.print(" (");
            pw.print(factoryResetFlagsToString(mFactoryResetFlags));
            pw.println(')');
        }
        if (mFactoryResetReason != null) {
            pw.print("mFactoryResetReason="); pw.println(mFactoryResetReason);
        }
        pw.decreaseIndent();
    }

    static String factoryResetFlagsToString(int flags) {
        return DebugUtils.flagsToString(DevicePolicyData.class, "FACTORY_RESET_FLAG_", flags);
    }
}
