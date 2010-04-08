/*
**
** Copyright 2007, The Android Open Source Project
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

package android.content.pm;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.content.IntentSender;

/**
 *  See {@link PackageManager} for documentation on most of the APIs
 *  here.
 * 
 *  {@hide}
 */
interface IPackageManager {
    PackageInfo getPackageInfo(String packageName, int flags);
    int getPackageUid(String packageName);
    int[] getPackageGids(String packageName);
    
    String[] currentToCanonicalPackageNames(in String[] names);
    String[] canonicalToCurrentPackageNames(in String[] names);

    PermissionInfo getPermissionInfo(String name, int flags);
    
    List<PermissionInfo> queryPermissionsByGroup(String group, int flags);
    
    PermissionGroupInfo getPermissionGroupInfo(String name, int flags);
    
    List<PermissionGroupInfo> getAllPermissionGroups(int flags);
    
    ApplicationInfo getApplicationInfo(String packageName, int flags);

    ActivityInfo getActivityInfo(in ComponentName className, int flags);

    ActivityInfo getReceiverInfo(in ComponentName className, int flags);

    ServiceInfo getServiceInfo(in ComponentName className, int flags);

    int checkPermission(String permName, String pkgName);
    
    int checkUidPermission(String permName, int uid);
    
    boolean addPermission(in PermissionInfo info);
    
    void removePermission(String name);
    
    boolean isProtectedBroadcast(String actionName);
    
    int checkSignatures(String pkg1, String pkg2);
    
    int checkUidSignatures(int uid1, int uid2);
    
    String[] getPackagesForUid(int uid);
    
    String getNameForUid(int uid);
    
    int getUidForSharedUser(String sharedUserName);
    
    ResolveInfo resolveIntent(in Intent intent, String resolvedType, int flags);

    List<ResolveInfo> queryIntentActivities(in Intent intent, 
            String resolvedType, int flags);

    List<ResolveInfo> queryIntentActivityOptions(
            in ComponentName caller, in Intent[] specifics,
            in String[] specificTypes, in Intent intent,
            String resolvedType, int flags);

    List<ResolveInfo> queryIntentReceivers(in Intent intent,
            String resolvedType, int flags);

    ResolveInfo resolveService(in Intent intent,
            String resolvedType, int flags);

    List<ResolveInfo> queryIntentServices(in Intent intent,
            String resolvedType, int flags);

    List<PackageInfo> getInstalledPackages(int flags);

    List<ApplicationInfo> getInstalledApplications(int flags);

    /**
     * Retrieve all applications that are marked as persistent.
     * 
     * @return A List&lt;applicationInfo> containing one entry for each persistent
     *         application.
     */
    List<ApplicationInfo> getPersistentApplications(int flags);

    ProviderInfo resolveContentProvider(String name, int flags);

    /**
     * Retrieve sync information for all content providers.
     * 
     * @param outNames Filled in with a list of the root names of the content
     *                 providers that can sync.
     * @param outInfo Filled in with a list of the ProviderInfo for each
     *                name in 'outNames'.
     */
    void querySyncProviders(inout List<String> outNames,
            inout List<ProviderInfo> outInfo);

    List<ProviderInfo> queryContentProviders(
            String processName, int uid, int flags);

    InstrumentationInfo getInstrumentationInfo(
            in ComponentName className, int flags);

    List<InstrumentationInfo> queryInstrumentation(
            String targetPackage, int flags);

    /**
     * Install a package.
     *
     * @param packageURI The location of the package file to install.
     * @param observer a callback to use to notify when the package installation in finished.
     * @param flags - possible values: {@link #FORWARD_LOCK_PACKAGE},
     * {@link #REPLACE_EXISITING_PACKAGE}
     * @param installerPackageName Optional package name of the application that is performing the
     * installation. This identifies which market the package came from.
     */
    void installPackage(in Uri packageURI, IPackageInstallObserver observer, int flags,
            in String installerPackageName);

    void finishPackageInstall(int token);

    /**
     * Delete a package.
     *
     * @param packageName The fully qualified name of the package to delete.
     * @param observer a callback to use to notify when the package deletion in finished.
     * @param flags - possible values: {@link #DONT_DELETE_DATA}
     */
    void deletePackage(in String packageName, IPackageDeleteObserver observer, int flags);

    String getInstallerPackageName(in String packageName);

    void addPackageToPreferred(String packageName);
    
    void removePackageFromPreferred(String packageName);
    
    List<PackageInfo> getPreferredPackages(int flags);

    void addPreferredActivity(in IntentFilter filter, int match,
            in ComponentName[] set, in ComponentName activity);

    void replacePreferredActivity(in IntentFilter filter, int match,
            in ComponentName[] set, in ComponentName activity);

    void clearPackagePreferredActivities(String packageName);

    int getPreferredActivities(out List<IntentFilter> outFilters,
            out List<ComponentName> outActivities, String packageName);
    
    /**
     * As per {@link android.content.pm.PackageManager#setComponentEnabledSetting}.
     */
    void setComponentEnabledSetting(in ComponentName componentName,
            in int newState, in int flags);

    /**
     * As per {@link android.content.pm.PackageManager#getComponentEnabledSetting}.
     */
    int getComponentEnabledSetting(in ComponentName componentName);
    
    /**
     * As per {@link android.content.pm.PackageManager#setApplicationEnabledSetting}.
     */
    void setApplicationEnabledSetting(in String packageName, in int newState, int flags);
    
    /**
     * As per {@link android.content.pm.PackageManager#getApplicationEnabledSetting}.
     */
    int getApplicationEnabledSetting(in String packageName);
    
    /**
     * Free storage by deleting LRU sorted list of cache files across
     * all applications. If the currently available free storage
     * on the device is greater than or equal to the requested
     * free storage, no cache files are cleared. If the currently
     * available storage on the device is less than the requested
     * free storage, some or all of the cache files across
     * all applications are deleted (based on last accessed time)
     * to increase the free storage space on the device to
     * the requested value. There is no guarantee that clearing all
     * the cache files from all applications will clear up
     * enough storage to achieve the desired value.
     * @param freeStorageSize The number of bytes of storage to be
     * freed by the system. Say if freeStorageSize is XX,
     * and the current free storage is YY,
     * if XX is less than YY, just return. if not free XX-YY number
     * of bytes if possible.
     * @param observer call back used to notify when
     * the operation is completed
     */
     void freeStorageAndNotify(in long freeStorageSize,
             IPackageDataObserver observer);

    /**
     * Free storage by deleting LRU sorted list of cache files across
     * all applications. If the currently available free storage
     * on the device is greater than or equal to the requested
     * free storage, no cache files are cleared. If the currently
     * available storage on the device is less than the requested
     * free storage, some or all of the cache files across
     * all applications are deleted (based on last accessed time)
     * to increase the free storage space on the device to
     * the requested value. There is no guarantee that clearing all
     * the cache files from all applications will clear up
     * enough storage to achieve the desired value.
     * @param freeStorageSize The number of bytes of storage to be
     * freed by the system. Say if freeStorageSize is XX,
     * and the current free storage is YY,
     * if XX is less than YY, just return. if not free XX-YY number
     * of bytes if possible.
     * @param pi IntentSender call back used to
     * notify when the operation is completed.May be null
     * to indicate that no call back is desired.
     */
     void freeStorage(in long freeStorageSize,
             in IntentSender pi);
     
    /**
     * Delete all the cache files in an applications cache directory
     * @param packageName The package name of the application whose cache
     * files need to be deleted
     * @param observer a callback used to notify when the deletion is finished.
     */
    void deleteApplicationCacheFiles(in String packageName, IPackageDataObserver observer);
    
    /**
     * Clear the user data directory of an application.
     * @param packageName The package name of the application whose cache
     * files need to be deleted
     * @param observer a callback used to notify when the operation is completed.
     */
    void clearApplicationUserData(in String packageName, IPackageDataObserver observer);
    
   /**
     * Get package statistics including the code, data and cache size for
     * an already installed package
     * @param packageName The package name of the application
     * @param observer a callback to use to notify when the asynchronous
     * retrieval of information is complete.
     */
    void getPackageSizeInfo(in String packageName, IPackageStatsObserver observer);
    
    /**
     * Get a list of shared libraries that are available on the
     * system.
     */
    String[] getSystemSharedLibraryNames();

    /**
     * Get a list of features that are available on the
     * system.
     */
    FeatureInfo[] getSystemAvailableFeatures();

    boolean hasSystemFeature(String name);
    
    void enterSafeMode();
    boolean isSafeMode();
    void systemReady();
    boolean hasSystemUidErrors();
    
    /**
     * Ask the package manager to perform dex-opt (if needed) on the given
     * package, if it already hasn't done mode.  Only does this if running
     * in the special development "no pre-dexopt" mode.
     */
    boolean performDexOpt(String packageName);

    /**
     * Update status of external media on the package manager to scan and
     * install packages installed on the external media. Like say the
     * MountService uses this to call into the package manager to update
     * status of sdcard.
     */
    void updateExternalMediaStatus(boolean mounted, boolean reportStatus);

    String nextPackageToClean(String lastPackage);

    void movePackage(String packageName, IPackageMoveObserver observer, int flags);
    
    boolean addPermissionAsync(in PermissionInfo info);

    boolean setInstallLocation(int loc);
    int getInstallLocation();
}
