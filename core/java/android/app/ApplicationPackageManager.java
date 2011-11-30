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

package android.app;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.pm.ManifestDigest;
import android.content.pm.VerifierDeviceIdentity;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/*package*/
final class ApplicationPackageManager extends PackageManager {
    private static final String TAG = "ApplicationPackageManager";
    private final static boolean DEBUG = false;
    private final static boolean DEBUG_ICONS = false;

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags)
            throws NameNotFoundException {
        try {
            PackageInfo pi = mPM.getPackageInfo(packageName, flags);
            if (pi != null) {
                return pi;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(packageName);
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        try {
            return mPM.currentToCanonicalPackageNames(names);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        try {
            return mPM.canonicalToCurrentPackageNames(names);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public Intent getLaunchIntentForPackage(String packageName) {
        // First see if the package has an INFO activity; the existence of
        // such an activity is implied to be the desired front-door for the
        // overall package (such as if it has multiple launcher entries).
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_INFO);
        intentToResolve.setPackage(packageName);
        List<ResolveInfo> ris = queryIntentActivities(intentToResolve, 0);

        // Otherwise, try to find a main launcher activity.
        if (ris == null || ris.size() <= 0) {
            // reuse the intent instance
            intentToResolve.removeCategory(Intent.CATEGORY_INFO);
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
            intentToResolve.setPackage(packageName);
            ris = queryIntentActivities(intentToResolve, 0);
        }
        if (ris == null || ris.size() <= 0) {
            return null;
        }
        Intent intent = new Intent(intentToResolve);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(ris.get(0).activityInfo.packageName,
                ris.get(0).activityInfo.name);
        return intent;
    }

    @Override
    public int[] getPackageGids(String packageName)
            throws NameNotFoundException {
        try {
            int[] gids = mPM.getPackageGids(packageName);
            if (gids == null || gids.length > 0) {
                return gids;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(packageName);
    }

    @Override
    public PermissionInfo getPermissionInfo(String name, int flags)
            throws NameNotFoundException {
        try {
            PermissionInfo pi = mPM.getPermissionInfo(name, flags);
            if (pi != null) {
                return pi;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(name);
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags)
            throws NameNotFoundException {
        try {
            List<PermissionInfo> pi = mPM.queryPermissionsByGroup(group, flags);
            if (pi != null) {
                return pi;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(group);
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String name,
                                                      int flags) throws NameNotFoundException {
        try {
            PermissionGroupInfo pgi = mPM.getPermissionGroupInfo(name, flags);
            if (pgi != null) {
                return pgi;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(name);
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        try {
            return mPM.getAllPermissionGroups(flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags)
            throws NameNotFoundException {
        try {
            ApplicationInfo ai = mPM.getApplicationInfo(packageName, flags);
            if (ai != null) {
                return ai;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(packageName);
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName className, int flags)
            throws NameNotFoundException {
        try {
            ActivityInfo ai = mPM.getActivityInfo(className, flags);
            if (ai != null) {
                return ai;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName className, int flags)
            throws NameNotFoundException {
        try {
            ActivityInfo ai = mPM.getReceiverInfo(className, flags);
            if (ai != null) {
                return ai;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName className, int flags)
            throws NameNotFoundException {
        try {
            ServiceInfo si = mPM.getServiceInfo(className, flags);
            if (si != null) {
                return si;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName className, int flags)
            throws NameNotFoundException {
        try {
            ProviderInfo pi = mPM.getProviderInfo(className, flags);
            if (pi != null) {
                return pi;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    public String[] getSystemSharedLibraryNames() {
        try {
            return mPM.getSystemSharedLibraryNames();
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        try {
            return mPM.getSystemAvailableFeatures();
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public boolean hasSystemFeature(String name) {
        try {
            return mPM.hasSystemFeature(name);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public int checkPermission(String permName, String pkgName) {
        try {
            return mPM.checkPermission(permName, pkgName);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public boolean addPermission(PermissionInfo info) {
        try {
            return mPM.addPermission(info);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public boolean addPermissionAsync(PermissionInfo info) {
        try {
            return mPM.addPermissionAsync(info);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public void removePermission(String name) {
        try {
            mPM.removePermission(name);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public int checkSignatures(String pkg1, String pkg2) {
        try {
            return mPM.checkSignatures(pkg1, pkg2);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public int checkSignatures(int uid1, int uid2) {
        try {
            return mPM.checkUidSignatures(uid1, uid2);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        try {
            return mPM.getPackagesForUid(uid);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public String getNameForUid(int uid) {
        try {
            return mPM.getNameForUid(uid);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public int getUidForSharedUser(String sharedUserName)
            throws NameNotFoundException {
        try {
            int uid = mPM.getUidForSharedUser(sharedUserName);
            if(uid != -1) {
                return uid;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
        throw new NameNotFoundException("No shared userid for user:"+sharedUserName);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<PackageInfo> getInstalledPackages(int flags) {
        try {
            final List<PackageInfo> packageInfos = new ArrayList<PackageInfo>();
            PackageInfo lastItem = null;
            ParceledListSlice<PackageInfo> slice;

            do {
                final String lastKey = lastItem != null ? lastItem.packageName : null;
                slice = mPM.getInstalledPackages(flags, lastKey);
                lastItem = slice.populateList(packageInfos, PackageInfo.CREATOR);
            } while (!slice.isLastSlice());

            return packageInfos;
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        try {
            final List<ApplicationInfo> applicationInfos = new ArrayList<ApplicationInfo>();
            ApplicationInfo lastItem = null;
            ParceledListSlice<ApplicationInfo> slice;

            do {
                final String lastKey = lastItem != null ? lastItem.packageName : null;
                slice = mPM.getInstalledApplications(flags, lastKey);
                lastItem = slice.populateList(applicationInfos, ApplicationInfo.CREATOR);
            } while (!slice.isLastSlice());

            return applicationInfos;
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags) {
        try {
            return mPM.resolveIntent(
                intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent,
                                                   int flags) {
        try {
            return mPM.queryIntentActivities(
                intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public List<ResolveInfo> queryIntentActivityOptions(
        ComponentName caller, Intent[] specifics, Intent intent,
        int flags) {
        final ContentResolver resolver = mContext.getContentResolver();

        String[] specificTypes = null;
        if (specifics != null) {
            final int N = specifics.length;
            for (int i=0; i<N; i++) {
                Intent sp = specifics[i];
                if (sp != null) {
                    String t = sp.resolveTypeIfNeeded(resolver);
                    if (t != null) {
                        if (specificTypes == null) {
                            specificTypes = new String[N];
                        }
                        specificTypes[i] = t;
                    }
                }
            }
        }

        try {
            return mPM.queryIntentActivityOptions(caller, specifics,
                                                  specificTypes, intent, intent.resolveTypeIfNeeded(resolver),
                                                  flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        try {
            return mPM.queryIntentReceivers(
                intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags) {
        try {
            return mPM.resolveService(
                intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        try {
            return mPM.queryIntentServices(
                intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public ProviderInfo resolveContentProvider(String name,
                                               int flags) {
        try {
            return mPM.resolveContentProvider(name, flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName,
                                                    int uid, int flags) {
        try {
            return mPM.queryContentProviders(processName, uid, flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(
        ComponentName className, int flags)
            throws NameNotFoundException {
        try {
            InstrumentationInfo ii = mPM.getInstrumentationInfo(
                className, flags);
            if (ii != null) {
                return ii;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }

        throw new NameNotFoundException(className.toString());
    }

    @Override
    public List<InstrumentationInfo> queryInstrumentation(
        String targetPackage, int flags) {
        try {
            return mPM.queryInstrumentation(targetPackage, flags);
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    @Override public Drawable getDrawable(String packageName, int resid,
                                          ApplicationInfo appInfo) {
        ResourceName name = new ResourceName(packageName, resid);
        Drawable dr = getCachedIcon(name);
        if (dr != null) {
            return dr;
        }
        if (appInfo == null) {
            try {
                appInfo = getApplicationInfo(packageName, 0);
            } catch (NameNotFoundException e) {
                return null;
            }
        }
        try {
            Resources r = getResourcesForApplication(appInfo);
            dr = r.getDrawable(resid);
            if (false) {
                RuntimeException e = new RuntimeException("here");
                e.fillInStackTrace();
                Log.w(TAG, "Getting drawable 0x" + Integer.toHexString(resid)
                      + " from package " + packageName
                      + ": app scale=" + r.getCompatibilityInfo().applicationScale
                      + ", caller scale=" + mContext.getResources().getCompatibilityInfo().applicationScale,
                      e);
            }
            if (DEBUG_ICONS) Log.v(TAG, "Getting drawable 0x"
                                   + Integer.toHexString(resid) + " from " + r
                                   + ": " + dr);
            putCachedIcon(name, dr);
            return dr;
        } catch (NameNotFoundException e) {
            Log.w("PackageManager", "Failure retrieving resources for"
                  + appInfo.packageName);
        } catch (Resources.NotFoundException e) {
            Log.w("PackageManager", "Failure retrieving resources for"
                  + appInfo.packageName + ": " + e.getMessage());
        } catch (RuntimeException e) {
            // If an exception was thrown, fall through to return
            // default icon.
            Log.w("PackageManager", "Failure retrieving icon 0x"
                  + Integer.toHexString(resid) + " in package "
                  + packageName, e);
        }
        return null;
    }

    @Override public Drawable getActivityIcon(ComponentName activityName)
            throws NameNotFoundException {
        return getActivityInfo(activityName, 0).loadIcon(this);
    }

    @Override public Drawable getActivityIcon(Intent intent)
            throws NameNotFoundException {
        if (intent.getComponent() != null) {
            return getActivityIcon(intent.getComponent());
        }

        ResolveInfo info = resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null) {
            return info.activityInfo.loadIcon(this);
        }

        throw new NameNotFoundException(intent.toUri(0));
    }

    @Override public Drawable getDefaultActivityIcon() {
        return Resources.getSystem().getDrawable(
            com.android.internal.R.drawable.sym_def_app_icon);
    }

    @Override public Drawable getApplicationIcon(ApplicationInfo info) {
        return info.loadIcon(this);
    }

    @Override public Drawable getApplicationIcon(String packageName)
            throws NameNotFoundException {
        return getApplicationIcon(getApplicationInfo(packageName, 0));
    }

    @Override
    public Drawable getActivityLogo(ComponentName activityName)
            throws NameNotFoundException {
        return getActivityInfo(activityName, 0).loadLogo(this);
    }

    @Override
    public Drawable getActivityLogo(Intent intent)
            throws NameNotFoundException {
        if (intent.getComponent() != null) {
            return getActivityLogo(intent.getComponent());
        }

        ResolveInfo info = resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null) {
            return info.activityInfo.loadLogo(this);
        }

        throw new NameNotFoundException(intent.toUri(0));
    }

    @Override
    public Drawable getApplicationLogo(ApplicationInfo info) {
        return info.loadLogo(this);
    }

    @Override
    public Drawable getApplicationLogo(String packageName)
            throws NameNotFoundException {
        return getApplicationLogo(getApplicationInfo(packageName, 0));
    }

    @Override public Resources getResourcesForActivity(
        ComponentName activityName) throws NameNotFoundException {
        return getResourcesForApplication(
            getActivityInfo(activityName, 0).applicationInfo);
    }

    @Override public Resources getResourcesForApplication(
        ApplicationInfo app) throws NameNotFoundException {
        if (app.packageName.equals("system")) {
            return mContext.mMainThread.getSystemContext().getResources();
        }
        Resources r = mContext.mMainThread.getTopLevelResources(
            app.uid == Process.myUid() ? app.sourceDir
            : app.publicSourceDir, mContext.mPackageInfo);
        if (r != null) {
            return r;
        }
        throw new NameNotFoundException("Unable to open " + app.publicSourceDir);
    }

    @Override public Resources getResourcesForApplication(
        String appPackageName) throws NameNotFoundException {
        return getResourcesForApplication(
            getApplicationInfo(appPackageName, 0));
    }

    int mCachedSafeMode = -1;
    @Override public boolean isSafeMode() {
        try {
            if (mCachedSafeMode < 0) {
                mCachedSafeMode = mPM.isSafeMode() ? 1 : 0;
            }
            return mCachedSafeMode != 0;
        } catch (RemoteException e) {
            throw new RuntimeException("Package manager has died", e);
        }
    }

    static void configurationChanged() {
        synchronized (sSync) {
            sIconCache.clear();
            sStringCache.clear();
        }
    }

    ApplicationPackageManager(ContextImpl context,
                              IPackageManager pm) {
        mContext = context;
        mPM = pm;
    }

    private Drawable getCachedIcon(ResourceName name) {
        synchronized (sSync) {
            WeakReference<Drawable.ConstantState> wr = sIconCache.get(name);
            if (DEBUG_ICONS) Log.v(TAG, "Get cached weak drawable ref for "
                                   + name + ": " + wr);
            if (wr != null) {   // we have the activity
                Drawable.ConstantState state = wr.get();
                if (state != null) {
                    if (DEBUG_ICONS) {
                        Log.v(TAG, "Get cached drawable state for " + name + ": " + state);
                    }
                    // Note: It's okay here to not use the newDrawable(Resources) variant
                    //       of the API. The ConstantState comes from a drawable that was
                    //       originally created by passing the proper app Resources instance
                    //       which means the state should already contain the proper
                    //       resources specific information (like density.) See
                    //       BitmapDrawable.BitmapState for instance.
                    return state.newDrawable();
                }
                // our entry has been purged
                sIconCache.remove(name);
            }
        }
        return null;
    }

    private void putCachedIcon(ResourceName name, Drawable dr) {
        synchronized (sSync) {
            sIconCache.put(name, new WeakReference<Drawable.ConstantState>(dr.getConstantState()));
            if (DEBUG_ICONS) Log.v(TAG, "Added cached drawable state for " + name + ": " + dr);
        }
    }

    static void handlePackageBroadcast(int cmd, String[] pkgList, boolean hasPkgInfo) {
        boolean immediateGc = false;
        if (cmd == IApplicationThread.EXTERNAL_STORAGE_UNAVAILABLE) {
            immediateGc = true;
        }
        if (pkgList != null && (pkgList.length > 0)) {
            boolean needCleanup = false;
            for (String ssp : pkgList) {
                synchronized (sSync) {
                    if (sIconCache.size() > 0) {
                        Iterator<ResourceName> it = sIconCache.keySet().iterator();
                        while (it.hasNext()) {
                            ResourceName nm = it.next();
                            if (nm.packageName.equals(ssp)) {
                                //Log.i(TAG, "Removing cached drawable for " + nm);
                                it.remove();
                                needCleanup = true;
                            }
                        }
                    }
                    if (sStringCache.size() > 0) {
                        Iterator<ResourceName> it = sStringCache.keySet().iterator();
                        while (it.hasNext()) {
                            ResourceName nm = it.next();
                            if (nm.packageName.equals(ssp)) {
                                //Log.i(TAG, "Removing cached string for " + nm);
                                it.remove();
                                needCleanup = true;
                            }
                        }
                    }
                }
            }
            if (needCleanup || hasPkgInfo) {
                if (immediateGc) {
                    // Schedule an immediate gc.
                    Runtime.getRuntime().gc();
                } else {
                    ActivityThread.currentActivityThread().scheduleGcIdler();
                }
            }
        }
    }

    private static final class ResourceName {
        final String packageName;
        final int iconId;

        ResourceName(String _packageName, int _iconId) {
            packageName = _packageName;
            iconId = _iconId;
        }

        ResourceName(ApplicationInfo aInfo, int _iconId) {
            this(aInfo.packageName, _iconId);
        }

        ResourceName(ComponentInfo cInfo, int _iconId) {
            this(cInfo.applicationInfo.packageName, _iconId);
        }

        ResourceName(ResolveInfo rInfo, int _iconId) {
            this(rInfo.activityInfo.applicationInfo.packageName, _iconId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ResourceName that = (ResourceName) o;

            if (iconId != that.iconId) return false;
            return !(packageName != null ?
                     !packageName.equals(that.packageName) : that.packageName != null);

        }

        @Override
        public int hashCode() {
            int result;
            result = packageName.hashCode();
            result = 31 * result + iconId;
            return result;
        }

        @Override
        public String toString() {
            return "{ResourceName " + packageName + " / " + iconId + "}";
        }
    }

    private CharSequence getCachedString(ResourceName name) {
        synchronized (sSync) {
            WeakReference<CharSequence> wr = sStringCache.get(name);
            if (wr != null) {   // we have the activity
                CharSequence cs = wr.get();
                if (cs != null) {
                    return cs;
                }
                // our entry has been purged
                sStringCache.remove(name);
            }
        }
        return null;
    }

    private void putCachedString(ResourceName name, CharSequence cs) {
        synchronized (sSync) {
            sStringCache.put(name, new WeakReference<CharSequence>(cs));
        }
    }

    @Override
    public CharSequence getText(String packageName, int resid,
                                ApplicationInfo appInfo) {
        ResourceName name = new ResourceName(packageName, resid);
        CharSequence text = getCachedString(name);
        if (text != null) {
            return text;
        }
        if (appInfo == null) {
            try {
                appInfo = getApplicationInfo(packageName, 0);
            } catch (NameNotFoundException e) {
                return null;
            }
        }
        try {
            Resources r = getResourcesForApplication(appInfo);
            text = r.getText(resid);
            putCachedString(name, text);
            return text;
        } catch (NameNotFoundException e) {
            Log.w("PackageManager", "Failure retrieving resources for"
                  + appInfo.packageName);
        } catch (RuntimeException e) {
            // If an exception was thrown, fall through to return
            // default icon.
            Log.w("PackageManager", "Failure retrieving text 0x"
                  + Integer.toHexString(resid) + " in package "
                  + packageName, e);
        }
        return null;
    }

    @Override
    public XmlResourceParser getXml(String packageName, int resid,
                                    ApplicationInfo appInfo) {
        if (appInfo == null) {
            try {
                appInfo = getApplicationInfo(packageName, 0);
            } catch (NameNotFoundException e) {
                return null;
            }
        }
        try {
            Resources r = getResourcesForApplication(appInfo);
            return r.getXml(resid);
        } catch (RuntimeException e) {
            // If an exception was thrown, fall through to return
            // default icon.
            Log.w("PackageManager", "Failure retrieving xml 0x"
                  + Integer.toHexString(resid) + " in package "
                  + packageName, e);
        } catch (NameNotFoundException e) {
            Log.w("PackageManager", "Failure retrieving resources for "
                  + appInfo.packageName);
        }
        return null;
    }

    @Override
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        return info.loadLabel(this);
    }

    @Override
    public void installPackage(Uri packageURI, IPackageInstallObserver observer, int flags,
                               String installerPackageName) {
        try {
            mPM.installPackage(packageURI, observer, flags, installerPackageName);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public void installPackageWithVerification(Uri packageURI, IPackageInstallObserver observer,
            int flags, String installerPackageName, Uri verificationURI,
            ManifestDigest manifestDigest) {
        try {
            mPM.installPackageWithVerification(packageURI, observer, flags, installerPackageName,
                    verificationURI, manifestDigest);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public void verifyPendingInstall(int id, int response) {
        try {
            mPM.verifyPendingInstall(id, response);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public void setInstallerPackageName(String targetPackage,
            String installerPackageName) {
        try {
            mPM.setInstallerPackageName(targetPackage, installerPackageName);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public void movePackage(String packageName, IPackageMoveObserver observer, int flags) {
        try {
            mPM.movePackage(packageName, observer, flags);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        try {
            return mPM.getInstallerPackageName(packageName);
        } catch (RemoteException e) {
            // Should never happen!
        }
        return null;
    }

    @Override
    public void deletePackage(String packageName, IPackageDeleteObserver observer, int flags) {
        try {
            mPM.deletePackage(packageName, observer, flags);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }
    @Override
    public void clearApplicationUserData(String packageName,
                                         IPackageDataObserver observer) {
        try {
            mPM.clearApplicationUserData(packageName, observer);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }
    @Override
    public void deleteApplicationCacheFiles(String packageName,
                                            IPackageDataObserver observer) {
        try {
            mPM.deleteApplicationCacheFiles(packageName, observer);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }
    @Override
    public void freeStorageAndNotify(long idealStorageSize, IPackageDataObserver observer) {
        try {
            mPM.freeStorageAndNotify(idealStorageSize, observer);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public void freeStorage(long freeStorageSize, IntentSender pi) {
        try {
            mPM.freeStorage(freeStorageSize, pi);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public void getPackageSizeInfo(String packageName,
                                   IPackageStatsObserver observer) {
        try {
            mPM.getPackageSizeInfo(packageName, observer);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }
    @Override
    public void addPackageToPreferred(String packageName) {
        try {
            mPM.addPackageToPreferred(packageName);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public void removePackageFromPreferred(String packageName) {
        try {
            mPM.removePackageFromPreferred(packageName);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        try {
            return mPM.getPreferredPackages(flags);
        } catch (RemoteException e) {
            // Should never happen!
        }
        return new ArrayList<PackageInfo>();
    }

    @Override
    public void addPreferredActivity(IntentFilter filter,
                                     int match, ComponentName[] set, ComponentName activity) {
        try {
            mPM.addPreferredActivity(filter, match, set, activity);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public void replacePreferredActivity(IntentFilter filter,
                                         int match, ComponentName[] set, ComponentName activity) {
        try {
            mPM.replacePreferredActivity(filter, match, set, activity);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public void clearPackagePreferredActivities(String packageName) {
        try {
            mPM.clearPackagePreferredActivities(packageName);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public int getPreferredActivities(List<IntentFilter> outFilters,
                                      List<ComponentName> outActivities, String packageName) {
        try {
            return mPM.getPreferredActivities(outFilters, outActivities, packageName);
        } catch (RemoteException e) {
            // Should never happen!
        }
        return 0;
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName,
                                           int newState, int flags) {
        try {
            mPM.setComponentEnabledSetting(componentName, newState, flags);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public int getComponentEnabledSetting(ComponentName componentName) {
        try {
            return mPM.getComponentEnabledSetting(componentName);
        } catch (RemoteException e) {
            // Should never happen!
        }
        return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }

    @Override
    public void setApplicationEnabledSetting(String packageName,
                                             int newState, int flags) {
        try {
            mPM.setApplicationEnabledSetting(packageName, newState, flags);
        } catch (RemoteException e) {
            // Should never happen!
        }
    }

    @Override
    public int getApplicationEnabledSetting(String packageName) {
        try {
            return mPM.getApplicationEnabledSetting(packageName);
        } catch (RemoteException e) {
            // Should never happen!
        }
        return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }

    // Multi-user support

    /**
     * @hide
     */
    @Override
    public UserInfo createUser(String name, int flags) {
        try {
            return mPM.createUser(name, flags);
        } catch (RemoteException e) {
            // Should never happen!
        }
        return null;
    }

    /**
     * @hide
     */
    @Override
    public List<UserInfo> getUsers() {
        // TODO:
        // Dummy code, always returns just the primary user
        ArrayList<UserInfo> users = new ArrayList<UserInfo>();
        UserInfo primary = new UserInfo(0, "Root!",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY);
        users.add(primary);
        return users;
    }

    /**
     * @hide
     */
    @Override
    public boolean removeUser(int id) {
        try {
            return mPM.removeUser(id);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * @hide
     */
    @Override
    public void updateUserName(int id, String name) {
        // TODO:
    }

    /**
     * @hide
     */
    @Override
    public void updateUserFlags(int id, int flags) {
        // TODO:
    }

    /**
     * @hide
     */
    @Override
    public VerifierDeviceIdentity getVerifierDeviceIdentity() {
        try {
            return mPM.getVerifierDeviceIdentity();
        } catch (RemoteException e) {
            // Should never happen!
        }
        return null;
    }

    private final ContextImpl mContext;
    private final IPackageManager mPM;

    private static final Object sSync = new Object();
    private static HashMap<ResourceName, WeakReference<Drawable.ConstantState>> sIconCache
            = new HashMap<ResourceName, WeakReference<Drawable.ConstantState>>();
    private static HashMap<ResourceName, WeakReference<CharSequence>> sStringCache
            = new HashMap<ResourceName, WeakReference<CharSequence>>();
}
