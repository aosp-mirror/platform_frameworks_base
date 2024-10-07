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

import android.accounts.Account;
import android.app.admin.DevicePolicyDrawableResource;
import android.app.admin.DevicePolicyStringResource;
import android.app.admin.ParcelableResource;
import android.app.admin.NetworkEvent;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.admin.ParcelableGranteeMap;
import android.app.admin.PreferentialNetworkServiceConfig;
import android.app.admin.StartInstallingUpdateCallback;
import android.app.admin.SystemUpdateInfo;
import android.app.admin.SystemUpdatePolicy;
import android.app.admin.PackagePolicy;
import android.app.admin.PasswordMetrics;
import android.app.admin.FactoryResetProtectionPolicy;
import android.app.admin.IAuditLogEventsCallback;
import android.app.admin.ManagedProfileProvisioningParams;
import android.app.admin.FullyManagedDeviceProvisioningParams;
import android.app.admin.ManagedSubscriptionsPolicy;
import android.app.admin.WifiSsidPolicy;
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
import com.android.internal.infra.AndroidFuture;
import android.app.admin.DevicePolicyState;
import android.app.admin.EnforcingAdmin;

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

    void setPasswordExpirationTimeout(in ComponentName who, String callerPackageName, long expiration, boolean parent);
    long getPasswordExpirationTimeout(in ComponentName who, int userHandle, boolean parent);

    long getPasswordExpiration(in ComponentName who, int userHandle, boolean parent);

    boolean isActivePasswordSufficient(String callerPackageName, int userHandle, boolean parent);
    boolean isActivePasswordSufficientForDeviceRequirement();
    boolean isPasswordSufficientAfterProfileUnification(int userHandle, int profileUser);
    int getPasswordComplexity(boolean parent);
    void setRequiredPasswordComplexity(String callerPackageName, int passwordComplexity, boolean parent);
    int getRequiredPasswordComplexity(String callerPackageName, boolean parent);
    int getAggregatedPasswordComplexityForUser(int userId, boolean deviceWideOnly);
    boolean isUsingUnifiedPassword(in ComponentName admin);
    int getCurrentFailedPasswordAttempts(String callerPackageName, int userHandle, boolean parent);
    int getProfileWithMinimumFailedPasswordsForWipe(int userHandle, boolean parent);

    void setMaximumFailedPasswordsForWipe(
        in ComponentName admin, String callerPackageName, int num, boolean parent);
    int getMaximumFailedPasswordsForWipe(in ComponentName admin, int userHandle, boolean parent);

    boolean resetPassword(String password, int flags);

    void setMaximumTimeToLock(in ComponentName who, String callerPackageName, long timeMs, boolean parent);
    long getMaximumTimeToLock(in ComponentName who, int userHandle, boolean parent);

    void setRequiredStrongAuthTimeout(in ComponentName who, String callerPackageName, long timeMs, boolean parent);
    long getRequiredStrongAuthTimeout(in ComponentName who, int userId, boolean parent);

    void lockNow(int flags, String callerPackageName, boolean parent);

    /**
    * @param factoryReset only applicable when `targetSdk >= U`, either tries to factoryReset/fail or removeUser/fail otherwise
    **/
    void wipeDataWithReason(String callerPackageName, int flags, String wipeReasonForUser, boolean parent, boolean factoryReset);

    void setFactoryResetProtectionPolicy(in ComponentName who, String callerPackageName, in FactoryResetProtectionPolicy policy);
    FactoryResetProtectionPolicy getFactoryResetProtectionPolicy(in ComponentName who);
    boolean isFactoryResetProtectionPolicySupported();

    void sendLostModeLocationUpdate(in AndroidFuture<boolean> future);

    ComponentName setGlobalProxy(in ComponentName admin, String proxySpec, String exclusionList);
    ComponentName getGlobalProxyAdmin(int userHandle);
    void setRecommendedGlobalProxy(in ComponentName admin, in ProxyInfo proxyInfo);

    int setStorageEncryption(in ComponentName who, boolean encrypt);
    boolean getStorageEncryption(in ComponentName who, int userHandle);
    int getStorageEncryptionStatus(in String callerPackage, int userHandle);

    boolean requestBugreport(in ComponentName who);

    void setCameraDisabled(in ComponentName who, String callerPackageName, boolean disabled, boolean parent);
    boolean getCameraDisabled(in ComponentName who, String callerPackageName, int userHandle, boolean parent);

    void setScreenCaptureDisabled(
        in ComponentName who, String callerPackageName, boolean disabled, boolean parent);
    boolean getScreenCaptureDisabled(in ComponentName who, int userHandle, boolean parent);

    void setNearbyNotificationStreamingPolicy(int policy);
    int getNearbyNotificationStreamingPolicy(int userId);

    void setNearbyAppStreamingPolicy(int policy);
    int getNearbyAppStreamingPolicy(int userId);

    void setKeyguardDisabledFeatures(in ComponentName who, String callerPackageName, int which, boolean parent);
    int getKeyguardDisabledFeatures(in ComponentName who, int userHandle, boolean parent);

    void setActiveAdmin(in ComponentName policyReceiver, boolean refreshing,
        int userHandle, String provisioningContext);
    boolean isAdminActive(in ComponentName policyReceiver, int userHandle);
    List<ComponentName> getActiveAdmins(int userHandle);
    @UnsupportedAppUsage
    boolean packageHasActiveAdmins(String packageName, int userHandle);
    void getRemoveWarning(in ComponentName policyReceiver, in RemoteCallback result, int userHandle);
    void removeActiveAdmin(in ComponentName policyReceiver, int userHandle);
    void forceRemoveActiveAdmin(in ComponentName policyReceiver, int userHandle);
    boolean hasGrantedPolicy(in ComponentName policyReceiver, int usesPolicy, int userHandle);

    void reportPasswordChanged(in PasswordMetrics metrics, int userId);
    void reportFailedPasswordAttempt(int userHandle, boolean parent);
    void reportSuccessfulPasswordAttempt(int userHandle);
    void reportFailedBiometricAttempt(int userHandle);
    void reportSuccessfulBiometricAttempt(int userHandle);
    void reportKeyguardDismissed(int userHandle);
    void reportKeyguardSecured(int userHandle);

    boolean setDeviceOwner(in ComponentName who, int userId, boolean setProfileOwnerOnCurrentUserIfNecessary);
    ComponentName getDeviceOwnerComponent(boolean callingUserOnly);
    ComponentName getDeviceOwnerComponentOnUser(int userId);
    boolean hasDeviceOwner();
    String getDeviceOwnerName();
    void clearDeviceOwner(String packageName);
    int getDeviceOwnerUserId();

    boolean setProfileOwner(in ComponentName who, int userHandle);
    ComponentName getProfileOwnerAsUser(int userHandle);
    ComponentName getProfileOwnerOrDeviceOwnerSupervisionComponent(in UserHandle userHandle);
    boolean isSupervisionComponent(in ComponentName who);
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

    void addPersistentPreferredActivity(in ComponentName admin, String callerPackageName, in IntentFilter filter, in ComponentName activity);
    void clearPackagePersistentPreferredActivities(in ComponentName admin, String callerPackageName, String packageName);

    void setDefaultSmsApplication(in ComponentName admin, String callerPackageName, String packageName, boolean parent);
    void setDefaultDialerApplication(String packageName);

    void setApplicationRestrictions(in ComponentName who, in String callerPackage, in String packageName, in Bundle settings, in boolean parent);
    Bundle getApplicationRestrictions(in ComponentName who, in String callerPackage, in String packageName, in boolean parent);
    boolean setApplicationRestrictionsManagingPackage(in ComponentName admin, in String packageName);
    String getApplicationRestrictionsManagingPackage(in ComponentName admin);
    boolean isCallerApplicationRestrictionsManagingPackage(in String callerPackage);

    void setRestrictionsProvider(in ComponentName who, in ComponentName provider);
    ComponentName getRestrictionsProvider(int userHandle);

    void setUserRestriction(in ComponentName who, in String callerPackage, in String key, boolean enable, boolean parent);
    void setUserRestrictionForUser(in String systemEntity, in String key, boolean enable, int targetUser);
    void setUserRestrictionGlobally(in String callerPackage, in String key);
    Bundle getUserRestrictions(in ComponentName who, in String callerPackage, boolean parent);
    Bundle getUserRestrictionsGlobally(in String callerPackage);

    void addCrossProfileIntentFilter(in ComponentName admin, String callerPackageName, in IntentFilter filter, int flags);
    void clearCrossProfileIntentFilters(in ComponentName admin, String callerPackageName);

    boolean setPermittedAccessibilityServices(in ComponentName admin,in List<String> packageList);
    List<String> getPermittedAccessibilityServices(in ComponentName admin);
    List<String> getPermittedAccessibilityServicesForUser(int userId);
    boolean isAccessibilityServicePermittedByAdmin(in ComponentName admin, String packageName, int userId);

    boolean setPermittedInputMethods(in ComponentName admin, String callerPackageName, in List<String> packageList, boolean parent);
    List<String> getPermittedInputMethods(in ComponentName admin, String callerPackageName, boolean parent);
    List<String> getPermittedInputMethodsAsUser(int userId);
    boolean isInputMethodPermittedByAdmin(in ComponentName admin, String packageName, int userId, boolean parent);

    boolean setPermittedCrossProfileNotificationListeners(in ComponentName admin, in List<String> packageList);
    List<String> getPermittedCrossProfileNotificationListeners(in ComponentName admin);
    boolean isNotificationListenerServicePermitted(in String packageName, int userId);

    Intent createAdminSupportIntent(in String restriction);
    Bundle getEnforcingAdminAndUserDetails(int userId,String restriction);
    List<EnforcingAdmin> getEnforcingAdminsForRestriction(int userId,String restriction);
    boolean setApplicationHidden(in ComponentName admin, in String callerPackage, in String packageName, boolean hidden, boolean parent);
    boolean isApplicationHidden(in ComponentName admin, in String callerPackage, in String packageName, boolean parent);

    UserHandle createAndManageUser(in ComponentName who, in String name, in ComponentName profileOwner, in PersistableBundle adminExtras, in int flags);
    boolean removeUser(in ComponentName who, in UserHandle userHandle);
    boolean switchUser(in ComponentName who, in UserHandle userHandle);
    int startUserInBackground(in ComponentName who, in UserHandle userHandle);
    int stopUser(in ComponentName who, in UserHandle userHandle);
    int logoutUser(in ComponentName who);
    int logoutUserInternal(); // AIDL doesn't allow overloading name (logoutUser())
    int getLogoutUserId();
    List<UserHandle> getSecondaryUsers(in ComponentName who);
    void acknowledgeNewUserDisclaimer(int userId);
    boolean isNewUserDisclaimerAcknowledged(int userId);

    void enableSystemApp(in ComponentName admin, in String callerPackage, in String packageName);
    int enableSystemAppWithIntent(in ComponentName admin, in String callerPackage, in Intent intent);
    boolean installExistingPackage(in ComponentName admin, in String callerPackage, in String packageName);

    void setAccountManagementDisabled(in ComponentName who, String callerPackageName, in String accountType, in boolean disabled, in boolean parent);
    String[] getAccountTypesWithManagementDisabled(String callerPackageName);
    String[] getAccountTypesWithManagementDisabledAsUser(int userId, String callerPackageName, in boolean parent);

    void setSecondaryLockscreenEnabled(in ComponentName who, boolean enabled, in PersistableBundle options);
    boolean isSecondaryLockscreenEnabled(in UserHandle userHandle);

    void setPreferentialNetworkServiceConfigs(
            in List<PreferentialNetworkServiceConfig> preferentialNetworkServiceConfigs);
    List<PreferentialNetworkServiceConfig> getPreferentialNetworkServiceConfigs();

    void setLockTaskPackages(in ComponentName who, String callerPackageName, in String[] packages);
    String[] getLockTaskPackages(in ComponentName who, String callerPackageName);
    boolean isLockTaskPermitted(in String pkg);

    void setLockTaskFeatures(in ComponentName who, String callerPackageName, int flags);
    int getLockTaskFeatures(in ComponentName who, String callerPackageName);

    void setGlobalSetting(in ComponentName who, in String setting, in String value);
    void setSystemSetting(in ComponentName who, in String setting, in String value, boolean parent);
    void setSecureSetting(in ComponentName who, in String setting, in String value);

    void setConfiguredNetworksLockdownState(in ComponentName who, String callerPackageName, boolean lockdown);
    boolean hasLockdownAdminConfiguredNetworks(in ComponentName who);

    void setLocationEnabled(in ComponentName who, boolean locationEnabled);

    boolean setTime(in ComponentName who, String callerPackageName, long millis);
    boolean setTimeZone(in ComponentName who, String callerPackageName, String timeZone);

    void setMasterVolumeMuted(in ComponentName admin, boolean on);
    boolean isMasterVolumeMuted(in ComponentName admin);

    void notifyLockTaskModeChanged(boolean isEnabled, String pkg, int userId);

    void setUninstallBlocked(in ComponentName admin, in String callerPackage, in String packageName, boolean uninstallBlocked);
    boolean isUninstallBlocked(in String packageName);

    void setCrossProfileCallerIdDisabled(in ComponentName who, boolean disabled);
    boolean getCrossProfileCallerIdDisabled(in ComponentName who);
    boolean getCrossProfileCallerIdDisabledForUser(int userId);
    void setCrossProfileContactsSearchDisabled(in ComponentName who, boolean disabled);
    boolean getCrossProfileContactsSearchDisabled(in ComponentName who);
    boolean getCrossProfileContactsSearchDisabledForUser(int userId);
    void startManagedQuickContact(String lookupKey, long contactId, boolean isContactIdIgnored, long directoryId, in Intent originalIntent);

    void setManagedProfileCallerIdAccessPolicy(in PackagePolicy policy);
    PackagePolicy getManagedProfileCallerIdAccessPolicy();
    boolean hasManagedProfileCallerIdAccess(int userId, String packageName);

    void setCredentialManagerPolicy(in PackagePolicy policy);
    PackagePolicy getCredentialManagerPolicy(int userId);

    void setManagedProfileContactsAccessPolicy(in PackagePolicy policy);
    PackagePolicy getManagedProfileContactsAccessPolicy();
    boolean hasManagedProfileContactsAccess(int userId, String packageName);

    void setBluetoothContactSharingDisabled(in ComponentName who, boolean disabled);
    boolean getBluetoothContactSharingDisabled(in ComponentName who);
    boolean getBluetoothContactSharingDisabledForUser(int userId);

    void setTrustAgentConfiguration(in ComponentName admin, String callerPackageName,
            in ComponentName agent, in PersistableBundle args, boolean parent);
    List<PersistableBundle> getTrustAgentConfiguration(in ComponentName admin,
            in ComponentName agent, int userId, boolean parent);

    boolean addCrossProfileWidgetProvider(in ComponentName admin, String callerPackageName, String packageName);
    boolean removeCrossProfileWidgetProvider(in ComponentName admin, String callerPackageName, String packageName);
    List<String> getCrossProfileWidgetProviders(in ComponentName admin, String callerPackageName);

    void setAutoTimeRequired(in ComponentName who, boolean required);
    boolean getAutoTimeRequired();

    void setAutoTimeEnabled(in ComponentName who, String callerPackageName, boolean enabled);
    boolean getAutoTimeEnabled(in ComponentName who, String callerPackageName);

    void setAutoTimeZoneEnabled(in ComponentName who, String callerPackageName, boolean enabled);
    boolean getAutoTimeZoneEnabled(in ComponentName who, String callerPackageName);

    void setForceEphemeralUsers(in ComponentName who, boolean forceEpehemeralUsers);
    boolean getForceEphemeralUsers(in ComponentName who);

    boolean isRemovingAdmin(in ComponentName adminReceiver, int userHandle);

    void setUserIcon(in ComponentName admin, in Bitmap icon);

    void setSystemUpdatePolicy(in ComponentName who, String callerPackageName, in SystemUpdatePolicy policy);
    SystemUpdatePolicy getSystemUpdatePolicy();
    void clearSystemUpdatePolicyFreezePeriodRecord();

    boolean setKeyguardDisabled(in ComponentName admin, boolean disabled);
    boolean setStatusBarDisabled(in ComponentName who, String callerPackageName, boolean disabled);
    boolean isStatusBarDisabled(in String callerPackage);
    boolean getDoNotAskCredentialsOnBoot();

    void notifyPendingSystemUpdate(in SystemUpdateInfo info);
    SystemUpdateInfo getPendingSystemUpdate(in ComponentName admin, in String callerPackage);

    void setPermissionPolicy(in ComponentName admin, in String callerPackage, int policy);
    int  getPermissionPolicy(in ComponentName admin);
    void setPermissionGrantState(in ComponentName admin, in String callerPackage, String packageName,
            String permission, int grantState, in RemoteCallback resultReceiver);
    int getPermissionGrantState(in ComponentName admin, in String callerPackage, String packageName, String permission);
    boolean isProvisioningAllowed(String action, String packageName);
    int checkProvisioningPrecondition(String action, String packageName);
    void setKeepUninstalledPackages(in ComponentName admin, in String callerPackage, in List<String> packageList);
    List<String> getKeepUninstalledPackages(in ComponentName admin, in String callerPackage);
    boolean isManagedProfile(in ComponentName admin);
    String getWifiMacAddress(in ComponentName admin, String callerPackageName);
    void reboot(in ComponentName admin);

    void setShortSupportMessage(in ComponentName admin, String callerPackageName, in CharSequence message);
    CharSequence getShortSupportMessage(in ComponentName admin, String callerPackageName);
    void setLongSupportMessage(in ComponentName admin, in CharSequence message);
    CharSequence getLongSupportMessage(in ComponentName admin);

    CharSequence getShortSupportMessageForUser(in ComponentName admin, int userHandle);
    CharSequence getLongSupportMessageForUser(in ComponentName admin, int userHandle);

    void setOrganizationColor(in ComponentName admin, in int color);
    void setOrganizationColorForUser(in int color, in int userId);
    void clearOrganizationIdForUser(int userHandle);
    int getOrganizationColor(in ComponentName admin);
    int getOrganizationColorForUser(int userHandle);

    void setOrganizationName(in ComponentName admin, String callerPackageName, in CharSequence title);
    CharSequence getOrganizationName(in ComponentName admin, String callerPackageName);
    CharSequence getDeviceOwnerOrganizationName();
    CharSequence getOrganizationNameForUser(int userHandle);

    int getUserProvisioningState(int userHandle);
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

    void setAuditLogEnabled(String callerPackage, boolean enabled);
    boolean isAuditLogEnabled(String callerPackage);
    void setAuditLogEventsCallback(String callerPackage, in IAuditLogEventsCallback callback);

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
        IServiceConnection connection, long flags, int targetUserId);
    List<UserHandle> getBindDeviceAdminTargetUsers(in ComponentName admin);
    boolean isEphemeralUser(in ComponentName admin);

    long getLastSecurityLogRetrievalTime();
    long getLastBugReportRequestTime();
    long getLastNetworkLogRetrievalTime();

    boolean setResetPasswordToken(in ComponentName admin, String callerPackageName, in byte[] token);
    boolean clearResetPasswordToken(in ComponentName admin, String callerPackageName);
    boolean isResetPasswordTokenActive(in ComponentName admin, String callerPackageName);
    boolean resetPasswordWithToken(in ComponentName admin, String callerPackageName, String password, in byte[] token, int flags);

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

    void setProfileOwnerOnOrganizationOwnedDevice(in ComponentName who, int userId, boolean isProfileOwnerOnOrganizationOwnedDevice);

    void installUpdateFromFile(in ComponentName admin, String callerPackageName, in ParcelFileDescriptor updateFileDescriptor, in StartInstallingUpdateCallback listener);

    void setCrossProfileCalendarPackages(in ComponentName admin, in List<String> packageNames);
    List<String> getCrossProfileCalendarPackages(in ComponentName admin);
    boolean isPackageAllowedToAccessCalendarForUser(String packageName, int userHandle);
    List<String> getCrossProfileCalendarPackagesForUser(int userHandle);

    void setCrossProfilePackages(in ComponentName admin, in List<String> packageNames);
    List<String> getCrossProfilePackages(in ComponentName admin);

    List<String> getAllCrossProfilePackages(int userId);
    List<String> getDefaultCrossProfilePackages();

    boolean isManagedKiosk();
    boolean isUnattendedManagedKiosk();

    boolean startViewCalendarEventInManagedProfile(String packageName, long eventId, long start, long end, boolean allDay, int flags);

    boolean setKeyGrantForApp(in ComponentName admin, String callerPackage, String alias, String packageName, boolean hasGrant);
    ParcelableGranteeMap getKeyPairGrants(in String callerPackage, in String alias);
    boolean setKeyGrantToWifiAuth(String callerPackage, String alias, boolean hasGrant);
    boolean isKeyPairGrantedToWifiAuth(String callerPackage, String alias);

    void setUserControlDisabledPackages(in ComponentName admin, String callerPackageName, in List<String> packages);

    List<String> getUserControlDisabledPackages(in ComponentName admin, String callerPackageName);

    void setCommonCriteriaModeEnabled(in ComponentName admin, String callerPackageName, boolean enabled);
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

    void finalizeWorkProfileProvisioning(in UserHandle managedProfileUser, in Account migratedAccount);

    void setDeviceOwnerType(in ComponentName admin, in int deviceOwnerType);
    int getDeviceOwnerType(in ComponentName admin);

    void resetDefaultCrossProfileIntentFilters(int userId);
    boolean canAdminGrantSensorsPermissions();

    void setUsbDataSignalingEnabled(String callerPackage, boolean enabled);
    boolean isUsbDataSignalingEnabled(String callerPackage);
    boolean canUsbDataSignalingBeDisabled();

    void setMinimumRequiredWifiSecurityLevel(String callerPackageName, int level);
    int getMinimumRequiredWifiSecurityLevel();

    void setWifiSsidPolicy(String callerPackageName, in WifiSsidPolicy policy);
    WifiSsidPolicy getWifiSsidPolicy(String callerPackageName);

    boolean isDevicePotentiallyStolen(String callerPackageName);

    List<UserHandle> listForegroundAffiliatedUsers();
    void setDrawables(in List<DevicePolicyDrawableResource> drawables);
    void resetDrawables(in List<String> drawableIds);
    ParcelableResource getDrawable(String drawableId, String drawableStyle, String drawableSource);

    boolean isDpcDownloaded();
    void setDpcDownloaded(boolean downloaded);

    void setStrings(in List<DevicePolicyStringResource> strings);
    void resetStrings(in List<String> stringIds);
    ParcelableResource getString(String stringId);

    void resetShouldAllowBypassingDevicePolicyManagementRoleQualificationState();
    boolean shouldAllowBypassingDevicePolicyManagementRoleQualification();

    List<UserHandle> getPolicyManagedProfiles(in UserHandle userHandle);

    void setApplicationExemptions(String callerPackage, String packageName, in int[]exemptions);
    int[] getApplicationExemptions(String packageName);

    void setMtePolicy(int flag, String callerPackageName);
    int getMtePolicy(String callerPackageName);

    void setManagedSubscriptionsPolicy(in ManagedSubscriptionsPolicy policy);
    ManagedSubscriptionsPolicy getManagedSubscriptionsPolicy();

    DevicePolicyState getDevicePolicyState();

    boolean triggerDevicePolicyEngineMigration(boolean forceMigration);

    boolean isDeviceFinanced(String callerPackageName);
    String getFinancedDeviceKioskRoleHolder(String callerPackageName);

    void calculateHasIncompatibleAccounts();

    void setContentProtectionPolicy(in ComponentName who, String callerPackageName, int policy);
    int getContentProtectionPolicy(in ComponentName who, String callerPackageName, int userId);

    int[] getSubscriptionIds(String callerPackageName);

    void setMaxPolicyStorageLimit(String callerPackageName, int storageLimit);
    void forceSetMaxPolicyStorageLimit(String callerPackageName, int storageLimit);
    int getMaxPolicyStorageLimit(String callerPackageName);
    int getPolicySizeForAdmin(String callerPackageName, in EnforcingAdmin admin);

    int getHeadlessDeviceOwnerMode(String callerPackageName);
}
