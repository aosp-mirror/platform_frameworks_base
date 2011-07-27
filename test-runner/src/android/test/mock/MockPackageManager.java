/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.test.mock;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.pm.ManifestDigest;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.util.List;

/**
 * A mock {@link android.content.pm.PackageManager} class.  All methods are non-functional and throw
 * {@link java.lang.UnsupportedOperationException}. Override it to provide the operations that you
 * need.
 */
public class MockPackageManager extends PackageManager {

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Intent getLaunchIntentForPackage(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int[] getPackageGids(String packageName) throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public PermissionInfo getPermissionInfo(String name, int flags)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags)
            throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String name,
            int flags) throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName className, int flags)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName className, int flags)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName className, int flags)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName className, int flags)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PackageInfo> getInstalledPackages(int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkPermission(String permName, String pkgName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addPermission(PermissionInfo info) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addPermissionAsync(PermissionInfo info) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removePermission(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkSignatures(String pkg1, String pkg2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int checkSignatures(int uid1, int uid2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getNameForUid(int uid) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public int getUidForSharedUser(String sharedUserName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller,
            Intent[] specifics, Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProviderInfo resolveContentProvider(String name, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(ComponentName className, int flags)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<InstrumentationInfo> queryInstrumentation(
            String targetPackage, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getDrawable(String packageName, int resid, ApplicationInfo appInfo) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getActivityIcon(ComponentName activityName)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getActivityIcon(Intent intent) throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getDefaultActivityIcon() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getApplicationIcon(ApplicationInfo info) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getApplicationIcon(String packageName) throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getActivityLogo(ComponentName activityName) throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getActivityLogo(Intent intent) throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getApplicationLogo(ApplicationInfo info) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Drawable getApplicationLogo(String packageName) throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public CharSequence getText(String packageName, int resid, ApplicationInfo appInfo) {
        throw new UnsupportedOperationException();
    }

    @Override
    public XmlResourceParser getXml(String packageName, int resid,
            ApplicationInfo appInfo) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Resources getResourcesForActivity(ComponentName activityName)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Resources getResourcesForApplication(ApplicationInfo app) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Resources getResourcesForApplication(String appPackageName)
    throws NameNotFoundException {
        throw new UnsupportedOperationException();
    }

    @Override
    public PackageInfo getPackageArchiveInfo(String archiveFilePath, int flags) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public void installPackage(Uri packageURI, IPackageInstallObserver observer,
            int flags, String installerPackageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setInstallerPackageName(String targetPackage,
            String installerPackageName) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public void movePackage(String packageName, IPackageMoveObserver observer, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public void clearApplicationUserData(
            String packageName, IPackageDataObserver observer) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public void deleteApplicationCacheFiles(
            String packageName, IPackageDataObserver observer) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public void freeStorageAndNotify(
            long idealStorageSize, IPackageDataObserver observer) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public void freeStorage(
            long idealStorageSize, IntentSender pi) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public void deletePackage(
            String packageName, IPackageDeleteObserver observer, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addPackageToPreferred(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removePackageFromPreferred(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName,
            int newState, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getComponentEnabledSetting(ComponentName componentName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setApplicationEnabledSetting(String packageName, int newState, int flags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getApplicationEnabledSetting(String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addPreferredActivity(IntentFilter filter,
            int match, ComponentName[] set, ComponentName activity) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public void replacePreferredActivity(IntentFilter filter,
            int match, ComponentName[] set, ComponentName activity) {
        throw new UnsupportedOperationException();
    }


    @Override
    public void clearPackagePreferredActivities(String packageName) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide - to match hiding in superclass
     */
    @Override
    public void getPackageSizeInfo(String packageName, IPackageStatsObserver observer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getPreferredActivities(List<IntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getSystemSharedLibraryNames() {
        throw new UnsupportedOperationException();
    }

    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasSystemFeature(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSafeMode() {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide
     */
    @Override
    public UserInfo createUser(String name, int flags) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide
     */
    @Override
    public List<UserInfo> getUsers() {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide
     */
    @Override
    public boolean removeUser(int id) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide
     */
    @Override
    public void updateUserName(int id, String name) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide
     */
    @Override
    public void updateUserFlags(int id, int flags) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide
     */
    @Override
    public void installPackageWithVerification(Uri packageURI, IPackageInstallObserver observer,
            int flags, String installerPackageName, Uri verificationURI,
            ManifestDigest manifestDigest) {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide
     */
    @Override
    public void verifyPendingInstall(int id, boolean verified, String failureMessage) {
        throw new UnsupportedOperationException();
    }
}
