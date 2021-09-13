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
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.admin.ParcelableGranteeMap;
import android.app.admin.StartInstallingUpdateCallback;
import android.app.admin.SystemUpdateInfo;
import android.app.admin.SystemUpdatePolicy;
import android.app.admin.PasswordMetrics;
import android.app.admin.FactoryResetProtectionPolicy;
import android.app.admin.ManagedProfileProvisioningParams;
import android.app.admin.FullyManagedDeviceProvisioningParams;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageDataObserver;
import android.content.pm.ParceledListSlice;
import android.content.pm.StringParceledListSlice;
import android.graphics.Bitmap;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.UserHandle;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keystore.ParcelableKeyGenParameterSpec;
import android.telephony.data.ApnSetting;

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

    PasswordMetrics getPasswordMinimumMetrics(int userHandle, boolean deviceWideOnly);

    void setPasswordHistoryLength(in ComponentName who, int length, boolean parent);
    int getPasswordHistoryLength(in ComponentName who, int userHandle, boolean parent);

    void setPasswordExpirationTimeout(in ComponentName who, long expiration, boolean parent);
    long getPasswordExpirationTimeout(in ComponentName who, int userHandle, boolean parent);

    long getPasswordExpiration(in ComponentName who, int userHandle, boolean parent);

    boolean isActivePasswordSufficient(int userHandle, boolean parent);
    boolean isActivePasswordSufficientForDeviceRequirement();
    boolean isPasswordSufficientAfterProfileUnification(int userHandle, int profileUser);
    int getPasswordComplexity(boolean parent);
    void setRequiredPasswordComplexity(int passwordComplexity, boolean parent);
    int getRequiredPasswordComplexity(boolean parent);
    int getAggregatedPasswordComplexityForUser(int userId, boolean deviceWideOnly);
    boolean isUsingUnifiedPassword(in ComponentName admin);
    int getCurrentFailedPasswordAttempts(int userHandle, boolean parent);
    int getProfileWithMinimumFailedPasswordsForWipe(int userHandle, boolean parent);

    void setMaximumFailedPasswordsForWipe(in ComponentName admin, int num, boolean parent);
    int getMaximumFailedPasswordsForWipe(in ComponentName admin, int userHandle, boolean parent);

    boolean resetPassword(String password, int flags);

    void setMaximumTimeToLock(in ComponentName who, long timeMs, boolean parent);
    long getMaximumTimeToLock(in ComponentName who, int userHandle, boolean parent);

    void setRequiredStrongAuthTimeout(in ComponentName who, long timeMs, boolean parent);
    long getRequiredStrongAuthTimeout(in ComponentName who, int userId, boolean parent);

    void lockNow(int flags, boolean parent);

    void wipeDataWithReason(int flags, String wipeReasonForUser, boolean parent);

    void setFactoryResetProtectionPolicy(in ComponentName who, in FactoryResetProtectionPolicy policy);
    FactoryResetProtectionPolicy getFactoryResetProtectionPolicy(in ComponentName who);
    boolean isFactoryResetProtectionPolicySupported();

    ComponentName setGlobalProxy(in ComponentName admin, String proxySpec, String exclusionList);
    ComponentName getGlobalProxyAdmin(int userHandle);
    void setRecommendedGlobalProxy(in ComponentName admin, in ProxyInfo proxyInfo);

    int setStorageEncryption(in ComponentName who, boolean encrypt);
    boolean getStorageEncryption(in ComponentName who, int userHandle);
    int getStorageEncryptionStatus(in String callerPackage, int userHandle);

    boolean requestBugreport(in ComponentName who);

    void setCameraDisabled(in ComponentName who, boolean disabled, boolean parent);
    boolean getCameraDisabled(in ComponentName who, int userHandle, boolean parent);

    void setScreenCaptureDisabled(in ComponentName who, boolean disabled, boolean parent);
    boolean getScreenCaptureDisabled(in ComponentName who, int userHandle, boolean parent);

    void setNearbyNotificationStreamingPolicy(int policy);
    int getNearbyNotificationStreamingPolicy(int userId);

    void setNearbyAppStreamingPolicy(int policy);
    int getNearbyAppStreamingPolicy(int userId);

    void setKeyguardDisabledFeatures(in ComponentName who, int which, boolean parent);
    int getKeyguardDisabledFeatures(in ComponentName who, int userHandle, boolean parent);

    void setActiveAdmin(in ComponentName policyReceiver, boolean refreshing, int userHandle);
    boolean isAdminActive(in ComponentName policyReceiver, int userHandle);
    List<ComponentName> getActiveAdmins(int userHandle);
    @UnsupportedAppUsage
    boolean packageHasActiveAdmins(String packageName, int userHandle);
    void getRemoveWarning(in ComponentName policyReceiver, in RemoteCallback result, int userHandle);
    void removeActiveAdmin(in ComponentName policyReceiver, int userHandle);
    void forceRemoveActiveAdmin(in ComponentName policyReceiver, int userHandle);
    boolean hasGrantedPolicy(in ComponentName policyReceiver, int usesPolicy, int userHandle);

    void reportPasswordChanged(int userId);
    void reportFailedPasswordAttempt(int userHandle);
    void reportSuccessfulPasswordAttempt(int userHandle);
    void reportFailedBiometricAttempt(int userHandle);
    void reportSuccessfulBiometricAttempt(int userHandle);
    void reportKeyguardDismissed(int userHandle);
    void reportKeyguardSecured(int userHandle);

    boolean setDeviceOwner(in ComponentName who, String ownerName, int userId);
    ComponentName getDeviceOwnerComponent(boolean callingUserOnly);
    boolean hasDeviceOwner();
    String getDeviceOwnerName();
    void clearDeviceOwner(String packageName);
    int getDeviceOwnerUserId();

    boolean setProfileOwner(in ComponentName who, String ownerName, int userHandle);
    ComponentName getProfileOwnerAsUser(int userHandle);
    ComponentName getProfileOwnerOrDeviceOwnerSupervisionComponent(in UserHandle userHandle);
    String getProfileOwnerName(int userHandle);
    void setProfileEnabled(in ComponentName who);
    void setProfileName(in ComponentName who, String profileName);
    void clearProfileOwner(in ComponentName who);
    boolean hasUserSetupCompleted();
    boolean isOrganizationOwnedDeviceWithManagedProfile();

    boolean checkDeviceIdentifierAccess(in String packageName, int pid, int uid);

    void setDeviceOwnerLockScreenInfo(in ComponentName who, CharSequence deviceOwnerInfo);
    CharSequence getDeviceOwnerLockScreenInfo();

    String[] setPackagesSuspended(in ComponentName admin, in String callerPackage, in String[] packageNames, boolean suspended);
    boolean isPackageSuspended(in ComponentName admin, in String callerPackage, String packageName);
    List<String> listPolicyExemptApps();

    boolean installCaCert(in ComponentName admin, String callerPackage, in byte[] certBuffer);
    void uninstallCaCerts(in ComponentName admin, String callerPackage, in String[] aliases);
    void enforceCanManageCaCerts(in ComponentName admin, in String callerPackage);
    boolean approveCaCert(in String alias, int userHandle, boolean approval);
    boolean isCaCertApproved(in String alias, int userHandle);

    boolean installKeyPair(in ComponentName who, in String callerPackage, in byte[] privKeyBuffer,
            in byte[] certBuffer, in byte[] certChainBuffer, String alias, boolean requestAccess,
            boolean isUserSelectable);
    boolean removeKeyPair(in ComponentName who, in String callerPackage, String alias);
    boolean hasKeyPair(in String callerPackage, in String alias);
    boolean generateKeyPair(in ComponentName who, in String callerPackage, in String algorithm,
            in ParcelableKeyGenParameterSpec keySpec,
            in int idAttestationFlags, out KeymasterCertificateChain attestationChain);
    boolean setKeyPairCertificate(in ComponentName who, in String callerPackage, in String alias,
            in byte[] certBuffer, in byte[] certChainBuffer, boolean isUserSelectable);
    void choosePrivateKeyAlias(int uid, in Uri uri, in String alias, IBinder aliasCallback);

    void setDelegatedScopes(in ComponentName who, in String delegatePackage, in List<String> scopes);
    List<String> getDelegatedScopes(in ComponentName who, String delegatePackage);
    List<String> getDelegatePackages(in ComponentName who, String scope);

    void setCertInstallerPackage(in ComponentName who, String installerPackage);
    String getCertInstallerPackage(in ComponentName who);

    boolean setAlwaysOnVpnPackage(in ComponentName who, String vpnPackage, boolean lockdown, in List<String> lockdownAllowlist);
    String getAlwaysOnVpnPackage(in ComponentName who);
    String getAlwaysOnVpnPackageForUser(int userHandle);
    boolean isAlwaysOnVpnLockdownEnabled(in ComponentName who);
    boolean isAlwaysOnVpnLockdownEnabledForUser(int userHandle);
    List<String> getAlwaysOnVpnLockdownAllowlist(in ComponentName who);

    void addPersistentPreferredActivity(in ComponentName admin, in IntentFilter filter, in ComponentName activity);
    void clearPackagePersistentPreferredActivities(in ComponentName admin, String packageName);

    void setDefaultSmsApplication(in ComponentName admin, String packageName, boolean parent);

    void setApplicationRestrictions(in ComponentName who, in String callerPackage, in String packageName, in Bundle settings);
    Bundle getApplicationRestrictions(in ComponentName who, in String callerPackage, in String packageName);
    boolean setApplicationRestrictionsManagingPackage(in ComponentName admin, in String packageName);
    String getApplicationRestrictionsManagingPackage(in ComponentName admin);
    boolean isCallerApplicationRestrictionsManagingPackage(in String callerPackage);

    void setRestrictionsProvider(in ComponentName who, in ComponentName provider);
    ComponentName getRestrictionsProvider(int userHandle);

    void setUserRestriction(in ComponentName who, in String key, boolean enable, boolean parent);
    Bundle getUserRestrictions(in ComponentName who, boolean parent);
    void addCrossProfileIntentFilter(in ComponentName admin, in IntentFilter filter, int flags);
    void clearCrossProfileIntentFilters(in ComponentName admin);

    boolean setPermittedAccessibilityServices(in ComponentName admin,in List<String> packageList);
    List<String> getPermittedAccessibilityServices(in ComponentName admin);
    List<String> getPermittedAccessibilityServicesForUser(int userId);
    boolean isAccessibilityServicePermittedByAdmin(in ComponentName admin, String packageName, int userId);

    boolean setPermittedInputMethods(in ComponentName admin,in List<String> packageList, boolean parent);
    List<String> getPermittedInputMethods(in ComponentName admin, boolean parent);
    List<String> getPermittedInputMethodsForCurrentUser();
    boolean isInputMethodPermittedByAdmin(in ComponentName admin, String packageName, int userId, boolean parent);

    boolean setPermittedCrossProfileNotificationListeners(in ComponentName admin, in List<String> packageList);
    List<String> getPermittedCrossProfileNotificationListeners(in ComponentName admin);
    boolean isNotificationListenerServicePermitted(in String packageName, int userId);

    Intent createAdminSupportIntent(in String restriction);
    boolean setApplicationHidden(in ComponentName admin, in String callerPackage, in String packageName, boolean hidden, boolean parent);
    boolean isApplicationHidden(in ComponentName admin, in String callerPackage, in String packageName, boolean parent);

    UserHandle createAndManageUser(in ComponentName who, in String name, in ComponentName profileOwner, in PersistableBundle adminExtras, in int flags);
    boolean removeUser(in ComponentName who, in UserHandle userHandle);
    boolean switchUser(in ComponentName who, in UserHandle userHandle);
    int startUserInBackground(in ComponentName who, in UserHandle userHandle);
    int stopUser(in ComponentName who, in UserHandle userHandle);
    int logoutUser(in ComponentName who);
    List<UserHandle> getSecondaryUsers(in ComponentName who);
    void resetNewUserDisclaimer();

    void enableSystemApp(in ComponentName admin, in String callerPackage, in String packageName);
    int enableSystemAppWithIntent(in ComponentName admin, in String callerPackage, in Intent intent);
    boolean installExistingPackage(in ComponentName admin, in String callerPackage, in String packageName);

    void setAccountManagementDisabled(in ComponentName who, in String accountType, in boolean disabled, in boolean parent);
    String[] getAccountTypesWithManagementDisabled();
    String[] getAccountTypesWithManagementDisabledAsUser(int userId, in boolean parent);

    void setSecondaryLockscreenEnabled(in ComponentName who, boolean enabled);
    boolean isSecondaryLockscreenEnabled(in UserHandle userHandle);

    void setPreferentialNetworkServiceEnabled(in boolean enabled);
    boolean isPreferentialNetworkServiceEnabled(int userHandle);

    void setLockTaskPackages(in ComponentName who, in String[] packages);
    String[] getLockTaskPackages(in ComponentName who);
    boolean isLockTaskPermitted(in String pkg);

    void setLockTaskFeatures(in ComponentName who, int flags);
    int getLockTaskFeatures(in ComponentName who);

    void setGlobalSetting(in ComponentName who, in String setting, in String value);
    void setSystemSetting(in ComponentName who, in String setting, in String value);
    void setSecureSetting(in ComponentName who, in String setting, in String value);

    void setConfiguredNetworksLockdownState(in ComponentName who, boolean lockdown);
    boolean hasLockdownAdminConfiguredNetworks(in ComponentName who);

    void setLocationEnabled(in ComponentName who, boolean locationEnabled);

    boolean setTime(in ComponentName who, long millis);
    boolean setTimeZone(in ComponentName who, String timeZone);

    void setMasterVolumeMuted(in ComponentName admin, boolean on);
    boolean isMasterVolumeMuted(in ComponentName admin);

    void notifyLockTaskModeChanged(boolean isEnabled, String pkg, int userId);

    void setUninstallBlocked(in ComponentName admin, in String callerPackage, in String packageName, boolean uninstallBlocked);
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

    void setAutoTimeEnabled(in ComponentName who, boolean enabled);
    boolean getAutoTimeEnabled(in ComponentName who);

    void setAutoTimeZoneEnabled(in ComponentName who, boolean enabled);
    boolean getAutoTimeZoneEnabled(in ComponentName who);

    void setForceEphemeralUsers(in ComponentName who, boolean forceEpehemeralUsers);
    boolean getForceEphemeralUsers(in ComponentName who);

    boolean isRemovingAdmin(in ComponentName adminReceiver, int userHandle);

    void setUserIcon(in ComponentName admin, in Bitmap icon);

    void setSystemUpdatePolicy(in ComponentName who, in SystemUpdatePolicy policy);
    SystemUpdatePolicy getSystemUpdatePolicy();
    void clearSystemUpdatePolicyFreezePeriodRecord();

    boolean setKeyguardDisabled(in ComponentName admin, boolean disabled);
    boolean setStatusBarDisabled(in ComponentName who, boolean disabled);
    boolean getDoNotAskCredentialsOnBoot();

    void notifyPendingSystemUpdate(in SystemUpdateInfo info);
    SystemUpdateInfo getPendingSystemUpdate(in ComponentName admin);

    void setPermissionPolicy(in ComponentName admin, in String callerPackage, int policy);
    int  getPermissionPolicy(in ComponentName admin);
    void setPermissionGrantState(in ComponentName admin, in String callerPackage, String packageName,
            String permission, int grantState, in RemoteCallback resultReceiver);
    int getPermissionGrantState(in ComponentName admin, in String callerPackage, String packageName, String permission);
    boolean isProvisioningAllowed(String action, String packageName);
    int checkProvisioningPreCondition(String action, String packageName);
    void setKeepUninstalledPackages(in ComponentName admin, in String callerPackage, in List<String> packageList);
    List<String> getKeepUninstalledPackages(in ComponentName admin, in String callerPackage);
    boolean isManagedProfile(in ComponentName admin);
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
    void clearOrganizationIdForUser(int userHandle);
    int getOrganizationColor(in ComponentName admin);
    int getOrganizationColorForUser(int userHandle);

    void setOrganizationName(in ComponentName admin, in CharSequence title);
    CharSequence getOrganizationName(in ComponentName admin);
    CharSequence getDeviceOwnerOrganizationName();
    CharSequence getOrganizationNameForUser(int userHandle);

    int getUserProvisioningState();
    void setUserProvisioningState(int state, int userHandle);

    void setAffiliationIds(in ComponentName admin, in List<String> ids);
    List<String> getAffiliationIds(in ComponentName admin);
    boolean isCallingUserAffiliated();
    boolean isAffiliatedUser(int userId);

    void setSecurityLoggingEnabled(in ComponentName admin, String packageName, boolean enabled);
    boolean isSecurityLoggingEnabled(in ComponentName admin, String packageName);
    ParceledListSlice retrieveSecurityLogs(in ComponentName admin, String packageName);
    ParceledListSlice retrievePreRebootSecurityLogs(in ComponentName admin, String packageName);
    long forceNetworkLogs();
    long forceSecurityLogs();

    boolean isUninstallInQueue(String packageName);
    void uninstallPackageWithActiveAdmins(String packageName);

    boolean isDeviceProvisioned();
    boolean isDeviceProvisioningConfigApplied();
    void setDeviceProvisioningConfigApplied();

    void forceUpdateUserSetupComplete(int userId);

    void setBackupServiceEnabled(in ComponentName admin, boolean enabled);
    boolean isBackupServiceEnabled(in ComponentName admin);

    void setNetworkLoggingEnabled(in ComponentName admin, in String packageName, boolean enabled);
    boolean isNetworkLoggingEnabled(in ComponentName admin, in String packageName);
    List<NetworkEvent> retrieveNetworkLogs(in ComponentName admin, in String packageName, long batchToken);

    boolean bindDeviceAdminServiceAsUser(in ComponentName admin,
        IApplicationThread caller, IBinder token, in Intent service,
        IServiceConnection connection, int flags, int targetUserId);
    List<UserHandle> getBindDeviceAdminTargetUsers(in ComponentName admin);
    boolean isEphemeralUser(in ComponentName admin);

    long getLastSecurityLogRetrievalTime();
    long getLastBugReportRequestTime();
    long getLastNetworkLogRetrievalTime();

    boolean setResetPasswordToken(in ComponentName admin, in byte[] token);
    boolean clearResetPasswordToken(in ComponentName admin);
    boolean isResetPasswordTokenActive(in ComponentName admin);
    boolean resetPasswordWithToken(in ComponentName admin, String password, in byte[] token, int flags);

    boolean isCurrentInputMethodSetByOwner();
    StringParceledListSlice getOwnerInstalledCaCerts(in UserHandle user);

    void clearApplicationUserData(in ComponentName admin, in String packageName, in IPackageDataObserver callback);

    void setLogoutEnabled(in ComponentName admin, boolean enabled);
    boolean isLogoutEnabled();

    List<String> getDisallowedSystemApps(in ComponentName admin, int userId, String provisioningAction);

    void transferOwnership(in ComponentName admin, in ComponentName target, in PersistableBundle bundle);
    PersistableBundle getTransferOwnershipBundle();

    void setStartUserSessionMessage(in ComponentName admin, in CharSequence startUserSessionMessage);
    void setEndUserSessionMessage(in ComponentName admin, in CharSequence endUserSessionMessage);
    CharSequence getStartUserSessionMessage(in ComponentName admin);
    CharSequence getEndUserSessionMessage(in ComponentName admin);

    List<String> setMeteredDataDisabledPackages(in ComponentName admin, in List<String> packageNames);
    List<String> getMeteredDataDisabledPackages(in ComponentName admin);

    int addOverrideApn(in ComponentName admin, in ApnSetting apnSetting);
    boolean updateOverrideApn(in ComponentName admin, int apnId, in ApnSetting apnSetting);
    boolean removeOverrideApn(in ComponentName admin, int apnId);
    List<ApnSetting> getOverrideApns(in ComponentName admin);
    void setOverrideApnsEnabled(in ComponentName admin, boolean enabled);
    boolean isOverrideApnEnabled(in ComponentName admin);

    boolean isMeteredDataDisabledPackageForUser(in ComponentName admin, String packageName, int userId);

    int setGlobalPrivateDns(in ComponentName admin, int mode, in String privateDnsHost);
    int getGlobalPrivateDnsMode(in ComponentName admin);
    String getGlobalPrivateDnsHost(in ComponentName admin);

    void markProfileOwnerOnOrganizationOwnedDevice(in ComponentName who, int userId);

    void installUpdateFromFile(in ComponentName admin, in ParcelFileDescriptor updateFileDescriptor, in StartInstallingUpdateCallback listener);

    void setCrossProfileCalendarPackages(in ComponentName admin, in List<String> packageNames);
    List<String> getCrossProfileCalendarPackages(in ComponentName admin);
    boolean isPackageAllowedToAccessCalendarForUser(String packageName, int userHandle);
    List<String> getCrossProfileCalendarPackagesForUser(int userHandle);

    void setCrossProfilePackages(in ComponentName admin, in List<String> packageNames);
    List<String> getCrossProfilePackages(in ComponentName admin);

    List<String> getAllCrossProfilePackages();
    List<String> getDefaultCrossProfilePackages();

    boolean isManagedKiosk();
    boolean isUnattendedManagedKiosk();

    boolean startViewCalendarEventInManagedProfile(String packageName, long eventId, long start, long end, boolean allDay, int flags);

    boolean setKeyGrantForApp(in ComponentName admin, String callerPackage, String alias, String packageName, boolean hasGrant);
    ParcelableGranteeMap getKeyPairGrants(in String callerPackage, in String alias);
    boolean setKeyGrantToWifiAuth(String callerPackage, String alias, boolean hasGrant);
    boolean isKeyPairGrantedToWifiAuth(String callerPackage, String alias);

    void setUserControlDisabledPackages(in ComponentName admin, in List<String> packages);

    List<String> getUserControlDisabledPackages(in ComponentName admin);

    void setCommonCriteriaModeEnabled(in ComponentName admin, boolean enabled);
    boolean isCommonCriteriaModeEnabled(in ComponentName admin);

    int getPersonalAppsSuspendedReasons(in ComponentName admin);
    void setPersonalAppsSuspended(in ComponentName admin, boolean suspended);

    long getManagedProfileMaximumTimeOff(in ComponentName admin);
    void setManagedProfileMaximumTimeOff(in ComponentName admin, long timeoutMs);

    void acknowledgeDeviceCompliant();
    boolean isComplianceAcknowledgementRequired();

    boolean canProfileOwnerResetPasswordWhenLocked(int userId);

    void setNextOperationSafety(int operation, int reason);
    boolean isSafeOperation(int reason);

    String getEnrollmentSpecificId(String callerPackage);
    void setOrganizationIdForUser(in String callerPackage, in String enterpriseId, int userId);

    UserHandle createAndProvisionManagedProfile(in ManagedProfileProvisioningParams provisioningParams, in String callerPackage);
    void provisionFullyManagedDevice(in FullyManagedDeviceProvisioningParams provisioningParams, in String callerPackage);

    void setDeviceOwnerType(in ComponentName admin, in int deviceOwnerType);
    int getDeviceOwnerType(in ComponentName admin);

    void resetDefaultCrossProfileIntentFilters(int userId);
    boolean canAdminGrantSensorsPermissionsForUser(int userId);

    void setUsbDataSignalingEnabled(String callerPackage, boolean enabled);
    boolean isUsbDataSignalingEnabled(String callerPackage);
    boolean isUsbDataSignalingEnabledForUser(int userId);
    boolean canUsbDataSignalingBeDisabled();

    List<UserHandle> listForegroundAffiliatedUsers();
}
