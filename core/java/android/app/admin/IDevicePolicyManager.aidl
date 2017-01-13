/*
**
** Copyright 2010, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.app.admin;

import android.app.admin.NetworkEvent;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.UserHandle;
import java.util.List;

/**
 * Internal IPC interface to the device policy service.
 * {@hide}
 */
interface IDevicePolicyManager {
    void setPasswordQuality(in ComponentName who, int quality, boolean parent);
    int getPasswordQuality(in ComponentName who, int userHandle, boolean parent);

    void setPasswordMinimumLength(in ComponentName who, int length, boolean parent);
    int getPasswordMinimumLength(in ComponentName who, int userHandle, boolean parent);

    void setPasswordMinimumUpperCase(in ComponentName who, int length, boolean parent);
    int getPasswordMinimumUpperCase(in ComponentName who, int userHandle, boolean parent);

    void setPasswordMinimumLowerCase(in ComponentName who, int length, boolean parent);
    int getPasswordMinimumLowerCase(in ComponentName who, int userHandle, boolean parent);

    void setPasswordMinimumLetters(in ComponentName who, int length, boolean parent);
    int getPasswordMinimumLetters(in ComponentName who, int userHandle, boolean parent);

    void setPasswordMinimumNumeric(in ComponentName who, int length, boolean parent);
    int getPasswordMinimumNumeric(in ComponentName who, int userHandle, boolean parent);

    void setPasswordMinimumSymbols(in ComponentName who, int length, boolean parent);
    int getPasswordMinimumSymbols(in ComponentName who, int userHandle, boolean parent);

    void setPasswordMinimumNonLetter(in ComponentName who, int length, boolean parent);
    int getPasswordMinimumNonLetter(in ComponentName who, int userHandle, boolean parent);

    void setPasswordHistoryLength(in ComponentName who, int length, boolean parent);
    int getPasswordHistoryLength(in ComponentName who, int userHandle, boolean parent);

    void setPasswordExpirationTimeout(in ComponentName who, long expiration, boolean parent);
    long getPasswordExpirationTimeout(in ComponentName who, int userHandle, boolean parent);

    long getPasswordExpiration(in ComponentName who, int userHandle, boolean parent);

    boolean isActivePasswordSufficient(int userHandle, boolean parent);
    boolean isProfileActivePasswordSufficientForParent(int userHandle);
    int getCurrentFailedPasswordAttempts(int userHandle, boolean parent);
    int getProfileWithMinimumFailedPasswordsForWipe(int userHandle, boolean parent);

    void setMaximumFailedPasswordsForWipe(in ComponentName admin, int num, boolean parent);
    int getMaximumFailedPasswordsForWipe(in ComponentName admin, int userHandle, boolean parent);

    boolean resetPassword(String password, int flags);

    void setMaximumTimeToLock(in ComponentName who, long timeMs, boolean parent);
    long getMaximumTimeToLock(in ComponentName who, int userHandle, boolean parent);
    long getMaximumTimeToLockForUserAndProfiles(int userHandle);

    void setRequiredStrongAuthTimeout(in ComponentName who, long timeMs, boolean parent);
    long getRequiredStrongAuthTimeout(in ComponentName who, int userId, boolean parent);

    void lockNow(boolean parent);

    void wipeData(int flags);

    ComponentName setGlobalProxy(in ComponentName admin, String proxySpec, String exclusionList);
    ComponentName getGlobalProxyAdmin(int userHandle);
    void setRecommendedGlobalProxy(in ComponentName admin, in ProxyInfo proxyInfo);

    int setStorageEncryption(in ComponentName who, boolean encrypt);
    boolean getStorageEncryption(in ComponentName who, int userHandle);
    int getStorageEncryptionStatus(in String callerPackage, int userHandle);

    boolean requestBugreport(in ComponentName who);

    void setCameraDisabled(in ComponentName who, boolean disabled);
    boolean getCameraDisabled(in ComponentName who, int userHandle);

    void setScreenCaptureDisabled(in ComponentName who, boolean disabled);
    boolean getScreenCaptureDisabled(in ComponentName who, int userHandle);

    void setKeyguardDisabledFeatures(in ComponentName who, int which, boolean parent);
    int getKeyguardDisabledFeatures(in ComponentName who, int userHandle, boolean parent);

    void setActiveAdmin(in ComponentName policyReceiver, boolean refreshing, int userHandle);
    boolean isAdminActive(in ComponentName policyReceiver, int userHandle);
    List<ComponentName> getActiveAdmins(int userHandle);
    boolean packageHasActiveAdmins(String packageName, int userHandle);
    void getRemoveWarning(in ComponentName policyReceiver, in RemoteCallback result, int userHandle);
    void removeActiveAdmin(in ComponentName policyReceiver, int userHandle);
    void forceRemoveActiveAdmin(in ComponentName policyReceiver, int userHandle);
    boolean hasGrantedPolicy(in ComponentName policyReceiver, int usesPolicy, int userHandle);

    void setActivePasswordState(int quality, int length, int letters, int uppercase, int lowercase,
        int numbers, int symbols, int nonletter, int userHandle);
    void reportPasswordChanged(int userId);
    void reportFailedPasswordAttempt(int userHandle);
    void reportSuccessfulPasswordAttempt(int userHandle);
    void reportFailedFingerprintAttempt(int userHandle);
    void reportSuccessfulFingerprintAttempt(int userHandle);
    void reportKeyguardDismissed(int userHandle);
    void reportKeyguardSecured(int userHandle);

    boolean setDeviceOwner(in ComponentName who, String ownerName, int userId);
    ComponentName getDeviceOwnerComponent(boolean callingUserOnly);
    String getDeviceOwnerName();
    void clearDeviceOwner(String packageName);
    int getDeviceOwnerUserId();

    boolean setProfileOwner(in ComponentName who, String ownerName, int userHandle);
    ComponentName getProfileOwner(int userHandle);
    String getProfileOwnerName(int userHandle);
    void setProfileEnabled(in ComponentName who);
    void setProfileName(in ComponentName who, String profileName);
    void clearProfileOwner(in ComponentName who);
    boolean hasUserSetupCompleted();

    void setDeviceOwnerLockScreenInfo(in ComponentName who, CharSequence deviceOwnerInfo);
    CharSequence getDeviceOwnerLockScreenInfo();

    String[] setPackagesSuspended(in ComponentName admin, in String[] packageNames, boolean suspended);
    boolean isPackageSuspended(in ComponentName admin, String packageName);

    boolean installCaCert(in ComponentName admin, in byte[] certBuffer);
    void uninstallCaCerts(in ComponentName admin, in String[] aliases);
    void enforceCanManageCaCerts(in ComponentName admin);
    boolean approveCaCert(in String alias, int userHandle, boolean approval);
    boolean isCaCertApproved(in String alias, int userHandle);

    boolean installKeyPair(in ComponentName who, in byte[] privKeyBuffer, in byte[] certBuffer,
            in byte[] certChainBuffer, String alias, boolean requestAccess);
    boolean removeKeyPair(in ComponentName who, String alias);
    void choosePrivateKeyAlias(int uid, in Uri uri, in String alias, IBinder aliasCallback);

    void setCertInstallerPackage(in ComponentName who, String installerPackage);
    String getCertInstallerPackage(in ComponentName who);

    boolean setAlwaysOnVpnPackage(in ComponentName who, String vpnPackage, boolean lockdown);
    String getAlwaysOnVpnPackage(in ComponentName who);

    void addPersistentPreferredActivity(in ComponentName admin, in IntentFilter filter, in ComponentName activity);
    void clearPackagePersistentPreferredActivities(in ComponentName admin, String packageName);

    void setApplicationRestrictions(in ComponentName who, in String packageName, in Bundle settings);
    Bundle getApplicationRestrictions(in ComponentName who, in String packageName);
    boolean setApplicationRestrictionsManagingPackage(in ComponentName admin, in String packageName);
    String getApplicationRestrictionsManagingPackage(in ComponentName admin);
    boolean isCallerApplicationRestrictionsManagingPackage();

    void setRestrictionsProvider(in ComponentName who, in ComponentName provider);
    ComponentName getRestrictionsProvider(int userHandle);

    void setUserRestriction(in ComponentName who, in String key, boolean enable);
    Bundle getUserRestrictions(in ComponentName who);
    void addCrossProfileIntentFilter(in ComponentName admin, in IntentFilter filter, int flags);
    void clearCrossProfileIntentFilters(in ComponentName admin);

    boolean setPermittedAccessibilityServices(in ComponentName admin,in List packageList);
    List getPermittedAccessibilityServices(in ComponentName admin);
    List getPermittedAccessibilityServicesForUser(int userId);
    boolean isAccessibilityServicePermittedByAdmin(in ComponentName admin, String packageName, int userId);

    boolean setPermittedInputMethods(in ComponentName admin,in List packageList);
    List getPermittedInputMethods(in ComponentName admin);
    List getPermittedInputMethodsForCurrentUser();
    boolean isInputMethodPermittedByAdmin(in ComponentName admin, String packageName, int userId);

    boolean setApplicationHidden(in ComponentName admin, in String packageName, boolean hidden);
    boolean isApplicationHidden(in ComponentName admin, in String packageName);

    UserHandle createAndManageUser(in ComponentName who, in String name, in ComponentName profileOwner, in PersistableBundle adminExtras, in int flags);
    boolean removeUser(in ComponentName who, in UserHandle userHandle);
    boolean switchUser(in ComponentName who, in UserHandle userHandle);

    void enableSystemApp(in ComponentName admin, in String packageName);
    int enableSystemAppWithIntent(in ComponentName admin, in Intent intent);

    void setAccountManagementDisabled(in ComponentName who, in String accountType, in boolean disabled);
    String[] getAccountTypesWithManagementDisabled();
    String[] getAccountTypesWithManagementDisabledAsUser(int userId);

    void setLockTaskPackages(in ComponentName who, in String[] packages);
    String[] getLockTaskPackages(in ComponentName who);
    boolean isLockTaskPermitted(in String pkg);

    void setGlobalSetting(in ComponentName who, in String setting, in String value);
    void setSecureSetting(in ComponentName who, in String setting, in String value);

    void setMasterVolumeMuted(in ComponentName admin, boolean on);
    boolean isMasterVolumeMuted(in ComponentName admin);

    void notifyLockTaskModeChanged(boolean isEnabled, String pkg, int userId);

    void setUninstallBlocked(in ComponentName admin, in String packageName, boolean uninstallBlocked);
    boolean isUninstallBlocked(in ComponentName admin, in String packageName);

    void setCrossProfileCallerIdDisabled(in ComponentName who, boolean disabled);
    boolean getCrossProfileCallerIdDisabled(in ComponentName who);
    boolean getCrossProfileCallerIdDisabledForUser(int userId);
    void setCrossProfileContactsSearchDisabled(in ComponentName who, boolean disabled);
    boolean getCrossProfileContactsSearchDisabled(in ComponentName who);
    boolean getCrossProfileContactsSearchDisabledForUser(int userId);
    void startManagedQuickContact(String lookupKey, long contactId, boolean isContactIdIgnored, long directoryId, in Intent originalIntent);

    void setBluetoothContactSharingDisabled(in ComponentName who, boolean disabled);
    boolean getBluetoothContactSharingDisabled(in ComponentName who);
    boolean getBluetoothContactSharingDisabledForUser(int userId);

    void setTrustAgentConfiguration(in ComponentName admin, in ComponentName agent,
            in PersistableBundle args, boolean parent);
    List<PersistableBundle> getTrustAgentConfiguration(in ComponentName admin,
            in ComponentName agent, int userId, boolean parent);

    boolean addCrossProfileWidgetProvider(in ComponentName admin, String packageName);
    boolean removeCrossProfileWidgetProvider(in ComponentName admin, String packageName);
    List<String> getCrossProfileWidgetProviders(in ComponentName admin);

    void setAutoTimeRequired(in ComponentName who, boolean required);
    boolean getAutoTimeRequired();

    void setForceEphemeralUsers(in ComponentName who, boolean forceEpehemeralUsers);
    boolean getForceEphemeralUsers(in ComponentName who);

    boolean isRemovingAdmin(in ComponentName adminReceiver, int userHandle);

    void setUserIcon(in ComponentName admin, in Bitmap icon);

    void setSystemUpdatePolicy(in ComponentName who, in SystemUpdatePolicy policy);
    SystemUpdatePolicy getSystemUpdatePolicy();

    boolean setKeyguardDisabled(in ComponentName admin, boolean disabled);
    boolean setStatusBarDisabled(in ComponentName who, boolean disabled);
    boolean getDoNotAskCredentialsOnBoot();

    void notifyPendingSystemUpdate(in long updateReceivedTime);

    void setPermissionPolicy(in ComponentName admin, int policy);
    int  getPermissionPolicy(in ComponentName admin);
    boolean setPermissionGrantState(in ComponentName admin, String packageName,
            String permission, int grantState);
    int getPermissionGrantState(in ComponentName admin, String packageName, String permission);
    boolean isProvisioningAllowed(String action);
    void setKeepUninstalledPackages(in ComponentName admin,in List<String> packageList);
    List<String> getKeepUninstalledPackages(in ComponentName admin);
    boolean isManagedProfile(in ComponentName admin);
    boolean isSystemOnlyUser(in ComponentName admin);
    String getWifiMacAddress(in ComponentName admin);
    void reboot(in ComponentName admin);

    void setShortSupportMessage(in ComponentName admin, in CharSequence message);
    CharSequence getShortSupportMessage(in ComponentName admin);
    void setLongSupportMessage(in ComponentName admin, in CharSequence message);
    CharSequence getLongSupportMessage(in ComponentName admin);

    CharSequence getShortSupportMessageForUser(in ComponentName admin, int userHandle);
    CharSequence getLongSupportMessageForUser(in ComponentName admin, int userHandle);

    boolean isSeparateProfileChallengeAllowed(int userHandle);

    void setOrganizationColor(in ComponentName admin, in int color);
    void setOrganizationColorForUser(in int color, in int userId);
    int getOrganizationColor(in ComponentName admin);
    int getOrganizationColorForUser(int userHandle);

    void setOrganizationName(in ComponentName admin, in CharSequence title);
    CharSequence getOrganizationName(in ComponentName admin);
    CharSequence getOrganizationNameForUser(int userHandle);

    int getUserProvisioningState();
    void setUserProvisioningState(int state, int userHandle);

    void setAffiliationIds(in ComponentName admin, in List<String> ids);
    boolean isAffiliatedUser();

    void setSecurityLoggingEnabled(in ComponentName admin, boolean enabled);
    boolean isSecurityLoggingEnabled(in ComponentName admin);
    ParceledListSlice retrieveSecurityLogs(in ComponentName admin);
    ParceledListSlice retrievePreRebootSecurityLogs(in ComponentName admin);

    boolean isUninstallInQueue(String packageName);
    void uninstallPackageWithActiveAdmins(String packageName);

    boolean isDeviceProvisioned();
    boolean isDeviceProvisioningConfigApplied();
    void setDeviceProvisioningConfigApplied();

    void setBackupServiceEnabled(in ComponentName admin, boolean enabled);
    boolean isBackupServiceEnabled(in ComponentName admin);

    void setNetworkLoggingEnabled(in ComponentName admin, boolean enabled);
    boolean isNetworkLoggingEnabled(in ComponentName admin);
    List<NetworkEvent> retrieveNetworkLogs(in ComponentName admin, long batchToken);
}
