/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.Intent.CATEGORY_DEFAULT;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

import static com.android.server.pm.PackageManagerService.DEBUG_BACKUP;
import static com.android.server.pm.PackageManagerService.DEBUG_PREFERRED;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.LogPrinter;
import android.util.PrintStreamPrinter;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.util.ArrayUtils;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

final class PreferredActivityHelper {
    // XML tags for backup/restore of various bits of state
    private static final String TAG_PREFERRED_BACKUP = "pa";
    private static final String TAG_DEFAULT_APPS = "da";

    private final PackageManagerService mPm;

    // TODO(b/198166813): remove PMS dependency
    PreferredActivityHelper(PackageManagerService pm) {
        mPm = pm;
    }

    private ResolveInfo findPreferredActivityNotLocked(@NonNull Computer snapshot, Intent intent,
            String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            List<ResolveInfo> query, boolean always, boolean removeMatches, boolean debug,
            @UserIdInt int userId) {
        return findPreferredActivityNotLocked(snapshot, intent, resolvedType, flags, query, always,
                removeMatches, debug, userId,
                UserHandle.getAppId(Binder.getCallingUid()) >= Process.FIRST_APPLICATION_UID);
    }

    // TODO: handle preferred activities missing while user has amnesia
    /** <b>must not hold {@link PackageManagerService.mLock}</b> */
    public ResolveInfo findPreferredActivityNotLocked(@NonNull Computer snapshot,
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            List<ResolveInfo> query, boolean always, boolean removeMatches, boolean debug,
            int userId, boolean queryMayBeFiltered) {
        if (Thread.holdsLock(mPm.mLock)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName()
                    + " is holding mLock", new Throwable());
        }
        if (!mPm.mUserManager.exists(userId)) return null;

        PackageManagerService.FindPreferredActivityBodyResult body =
                snapshot.findPreferredActivityInternal(
                intent, resolvedType, flags, query, always,
                removeMatches, debug, userId, queryMayBeFiltered);
        if (body.mChanged) {
            if (DEBUG_PREFERRED) {
                Slog.v(TAG, "Preferred activity bookkeeping changed; writing restrictions");
            }
            mPm.scheduleWritePackageRestrictions(userId);
        }
        if ((DEBUG_PREFERRED || debug) && body.mPreferredResolveInfo == null) {
            Slog.v(TAG, "No preferred activity to return");
        }
        return body.mPreferredResolveInfo;
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    public void clearPackagePreferredActivities(String packageName, int userId) {
        final SparseBooleanArray changedUsers = new SparseBooleanArray();
        synchronized (mPm.mLock) {
            mPm.clearPackagePreferredActivitiesLPw(packageName, changedUsers, userId);
        }
        if (changedUsers.size() > 0) {
            updateDefaultHomeNotLocked(mPm.snapshotComputer(), changedUsers);
            mPm.postPreferredActivityChangedBroadcast(userId);
            mPm.scheduleWritePackageRestrictions(userId);
        }
    }

    /**
     * <b>must not hold {@link PackageManagerService.mLock}</b>
     *
     * @return Whether the ACTION_PREFERRED_ACTIVITY_CHANGED broadcast has been scheduled.
     */
    public boolean updateDefaultHomeNotLocked(@NonNull Computer snapshot, @UserIdInt int userId) {
        if (Thread.holdsLock(mPm.mLock)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName()
                    + " is holding mLock", new Throwable());
        }
        if (!mPm.isSystemReady()) {
            // We might get called before system is ready because of package changes etc, but
            // finding preferred activity depends on settings provider, so we ignore the update
            // before that.
            return false;
        }
        final Intent intent = snapshot.getHomeIntent();
        final List<ResolveInfo> resolveInfos = snapshot.queryIntentActivitiesInternal(
                intent, null, MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, userId);
        final ResolveInfo preferredResolveInfo = findPreferredActivityNotLocked(snapshot,
                intent, null, 0, resolveInfos, true, false, false, userId);
        final String packageName = preferredResolveInfo != null
                && preferredResolveInfo.activityInfo != null
                ? preferredResolveInfo.activityInfo.packageName : null;
        final String currentPackageName = mPm.getActiveLauncherPackageName(userId);
        if (TextUtils.equals(currentPackageName, packageName)) {
            return false;
        }
        final String[] callingPackages = snapshot.getPackagesForUid(Binder.getCallingUid());
        if (callingPackages != null && ArrayUtils.contains(callingPackages,
                mPm.mRequiredPermissionControllerPackage)) {
            // PermissionController manages default home directly.
            return false;
        }

        if (packageName == null) {
            // Keep the default home package in RoleManager.
            return false;
        }
        return mPm.setActiveLauncherPackage(packageName, userId,
                successful -> {
                    if (successful) {
                        mPm.postPreferredActivityChangedBroadcast(userId);
                    }
                });
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void addPreferredActivity(@NonNull Computer snapshot, WatchedIntentFilter filter,
            int match, ComponentName[] set, ComponentName activity, boolean always, int userId,
            String opname, boolean removeExisting) {
        // writer
        int callingUid = Binder.getCallingUid();
        snapshot.enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "add preferred activity");
        if (mPm.mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            if (snapshot.getUidTargetSdkVersion(callingUid)
                    < Build.VERSION_CODES.FROYO) {
                Slog.w(TAG, "Ignoring addPreferredActivity() from uid "
                        + callingUid);
                return;
            }
            mPm.mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
        }
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        if (DEBUG_PREFERRED) {
            Slog.i(TAG, opname + " activity " + activity.flattenToShortString() + " for user "
                    + userId + ":");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
        }
        synchronized (mPm.mLock) {
            final PreferredIntentResolver pir = mPm.mSettings.editPreferredActivitiesLPw(userId);
            final ArrayList<PreferredActivity> existing = pir.findFilters(filter);
            if (removeExisting && existing != null) {
                Settings.removeFilters(pir, filter, existing);
            }
            pir.addFilter(mPm.snapshotComputer(),
                    new PreferredActivity(filter, match, set, activity, always));
            mPm.scheduleWritePackageRestrictions(userId);
        }
        // Re-snapshot after mLock
        if (!(isHomeFilter(filter) && updateDefaultHomeNotLocked(mPm.snapshotComputer(), userId))) {
            mPm.postPreferredActivityChangedBroadcast(userId);
        }
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void replacePreferredActivity(@NonNull Computer snapshot, WatchedIntentFilter filter,
            int match, ComponentName[] set, ComponentName activity, int userId) {
        if (filter.countActions() != 1) {
            throw new IllegalArgumentException(
                    "replacePreferredActivity expects filter to have only 1 action.");
        }
        if (filter.countDataAuthorities() != 0
                || filter.countDataPaths() != 0
                || filter.countDataSchemes() > 1
                || filter.countDataTypes() != 0) {
            throw new IllegalArgumentException(
                    "replacePreferredActivity expects filter to have no data authorities, "
                            + "paths, or types; and at most one scheme.");
        }

        final int callingUid = Binder.getCallingUid();
        snapshot.enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "replace preferred activity");
        if (mPm.mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            synchronized (mPm.mLock) {
                // TODO: Remove lock?
                if (mPm.snapshotComputer().getUidTargetSdkVersion(callingUid)
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring replacePreferredActivity() from uid "
                            + Binder.getCallingUid());
                    return;
                }
            }
            mPm.mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
        }

        synchronized (mPm.mLock) {
            final PreferredIntentResolver pir = mPm.mSettings.getPreferredActivities(userId);
            if (pir != null) {
                // Get all of the existing entries that exactly match this filter.
                final ArrayList<PreferredActivity> existing = pir.findFilters(filter);
                if (existing != null && existing.size() == 1) {
                    final PreferredActivity cur = existing.get(0);
                    if (DEBUG_PREFERRED) {
                        Slog.i(TAG, "Checking replace of preferred:");
                        filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
                        if (!cur.mPref.mAlways) {
                            Slog.i(TAG, "  -- CUR; not mAlways!");
                        } else {
                            Slog.i(TAG, "  -- CUR: mMatch=" + cur.mPref.mMatch);
                            Slog.i(TAG, "  -- CUR: mSet="
                                    + Arrays.toString(cur.mPref.mSetComponents));
                            Slog.i(TAG, "  -- CUR: mComponent=" + cur.mPref.mShortComponent);
                            Slog.i(TAG, "  -- NEW: mMatch="
                                    + (match & IntentFilter.MATCH_CATEGORY_MASK));
                            Slog.i(TAG, "  -- CUR: mSet=" + Arrays.toString(set));
                            Slog.i(TAG, "  -- CUR: mComponent=" + activity.flattenToShortString());
                        }
                    }
                    if (cur.mPref.mAlways && cur.mPref.mComponent.equals(activity)
                            && cur.mPref.mMatch == (match & IntentFilter.MATCH_CATEGORY_MASK)
                            && cur.mPref.sameSet(set)) {
                        // Setting the preferred activity to what it happens to be already
                        if (DEBUG_PREFERRED) {
                            Slog.i(TAG, "Replacing with same preferred activity "
                                    + cur.mPref.mShortComponent + " for user "
                                    + userId + ":");
                            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
                        }
                        return;
                    }
                }
                if (existing != null) {
                    Settings.removeFilters(pir, filter, existing);
                }
            }
        }

        // Retake a snapshot after editing with lock held
        addPreferredActivity(mPm.snapshotComputer(), filter, match, set, activity, true, userId,
                "Replacing preferred", false);
    }

    public void clearPackagePreferredActivities(@NonNull Computer snapshot, String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (snapshot.getInstantAppPackageName(callingUid) != null) {
            return;
        }
        final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        if (packageState == null || !snapshot.isCallerSameApp(packageName, callingUid)) {
            if (mPm.mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (snapshot.getUidTargetSdkVersion(callingUid)
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring clearPackagePreferredActivities() from uid "
                            + callingUid);
                    return;
                }
                mPm.mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
            }
        }
        if (packageState != null && snapshot.shouldFilterApplication(packageState, callingUid,
                UserHandle.getUserId(callingUid))) {
            return;
        }
        int callingUserId = UserHandle.getCallingUserId();
        clearPackagePreferredActivities(packageName, callingUserId);
    }

    /** <b>must not hold {@link #PackageManagerService.mLock}</b> */
    void updateDefaultHomeNotLocked(@NonNull Computer snapshot, SparseBooleanArray userIds) {
        if (Thread.holdsLock(mPm.mLock)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName()
                    + " is holding mLock", new Throwable());
        }
        for (int i = userIds.size() - 1; i >= 0; --i) {
            final int userId = userIds.keyAt(i);
            updateDefaultHomeNotLocked(snapshot, userId);
        }
    }

    public void setHomeActivity(@NonNull Computer snapshot, ComponentName comp, int userId) {
        if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        ArrayList<ResolveInfo> homeActivities = new ArrayList<>();
        snapshot.getHomeActivitiesAsUser(homeActivities, userId);

        boolean found = false;

        final int size = homeActivities.size();
        final ComponentName[] set = new ComponentName[size];
        for (int i = 0; i < size; i++) {
            final ResolveInfo candidate = homeActivities.get(i);
            final ActivityInfo info = candidate.activityInfo;
            final ComponentName activityName = new ComponentName(info.packageName, info.name);
            set[i] = activityName;
            if (!found && activityName.equals(comp)) {
                found = true;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Component " + comp + " cannot be home on user "
                    + userId);
        }
        replacePreferredActivity(snapshot, getHomeFilter(), IntentFilter.MATCH_CATEGORY_EMPTY,
                set, comp, userId);
    }

    private WatchedIntentFilter getHomeFilter() {
        WatchedIntentFilter filter = new WatchedIntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        return filter;
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void addPersistentPreferredActivity(WatchedIntentFilter filter, ComponentName activity,
            int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "addPersistentPreferredActivity can only be run by the system");
        }
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        if (DEBUG_PREFERRED) {
            Slog.i(TAG, "Adding persistent preferred activity " + activity
                    + " for user " + userId + ":");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
        }
        synchronized (mPm.mLock) {
            mPm.mSettings.editPersistentPreferredActivitiesLPw(userId).addFilter(
                    mPm.snapshotComputer(),
                    new PersistentPreferredActivity(filter, activity, true));
            mPm.scheduleWritePackageRestrictions(userId);
        }
        if (isHomeFilter(filter)) {
            updateDefaultHomeNotLocked(mPm.snapshotComputer(), userId);
        }
        mPm.postPreferredActivityChangedBroadcast(userId);
    }

    public void clearPackagePersistentPreferredActivities(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "clearPackagePersistentPreferredActivities can only be run by the system");
        }
        boolean changed = false;
        synchronized (mPm.mLock) {
            changed = mPm.mSettings.clearPackagePersistentPreferredActivities(packageName, userId);
        }
        if (changed) {
            updateDefaultHomeNotLocked(mPm.snapshotComputer(), userId);
            mPm.postPreferredActivityChangedBroadcast(userId);
            mPm.scheduleWritePackageRestrictions(userId);
        }
    }

    private boolean isHomeFilter(@NonNull WatchedIntentFilter filter) {
        return filter.hasAction(Intent.ACTION_MAIN) && filter.hasCategory(Intent.CATEGORY_HOME)
                && filter.hasCategory(CATEGORY_DEFAULT);
    }

    /**
     * Common machinery for picking apart a restored XML blob and passing
     * it to a caller-supplied functor to be applied to the running system.
     */
    private void restoreFromXml(TypedXmlPullParser parser, int userId,
            String expectedStartTag, BlobXmlRestorer functor)
            throws IOException, XmlPullParserException {
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
        }
        if (type != XmlPullParser.START_TAG) {
            // oops didn't find a start tag?!
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Didn't find start tag during restore");
            }
            return;
        }
        // this is supposed to be TAG_PREFERRED_BACKUP
        if (!expectedStartTag.equals(parser.getName())) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Found unexpected tag " + parser.getName());
            }
            return;
        }

        // skip interfering stuff, then we're aligned with the backing implementation
        while ((type = parser.next()) == XmlPullParser.TEXT) { }
        functor.apply(parser, userId);
    }

    private interface BlobXmlRestorer {
        void apply(TypedXmlPullParser parser, int userId)
                throws IOException, XmlPullParserException;
    }

    public byte[] getPreferredActivityBackup(int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call getPreferredActivityBackup()");
        }

        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            final TypedXmlSerializer serializer = Xml.newFastSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_PREFERRED_BACKUP);

            synchronized (mPm.mLock) {
                mPm.mSettings.writePreferredActivitiesLPr(serializer, userId, true);
            }

            serializer.endTag(null, TAG_PREFERRED_BACKUP);
            serializer.endDocument();
            serializer.flush();
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write preferred activities for backup", e);
            }
            return null;
        }

        return dataStream.toByteArray();
    }

    public void restorePreferredActivities(byte[] backup, int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call restorePreferredActivities()");
        }

        try {
            final TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_PREFERRED_BACKUP,
                    (readParser, readUserId) -> {
                        synchronized (mPm.mLock) {
                            mPm.mSettings.readPreferredActivitiesLPw(readParser, readUserId);
                        }
                        updateDefaultHomeNotLocked(mPm.snapshotComputer(), readUserId);
                    });
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring preferred activities: " + e.getMessage());
            }
        }
    }

    /**
     * Non-Binder method, support for the backup/restore mechanism: write the
     * default browser (etc) settings in its canonical XML format.  Returns the default
     * browser XML representation as a byte array, or null if there is none.
     */
    public byte[] getDefaultAppsBackup(int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call getDefaultAppsBackup()");
        }

        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            final TypedXmlSerializer serializer = Xml.newFastSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_DEFAULT_APPS);

            synchronized (mPm.mLock) {
                mPm.mSettings.writeDefaultAppsLPr(serializer, userId);
            }

            serializer.endTag(null, TAG_DEFAULT_APPS);
            serializer.endDocument();
            serializer.flush();
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write default apps for backup", e);
            }
            return null;
        }

        return dataStream.toByteArray();
    }

    public void restoreDefaultApps(byte[] backup, int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call restoreDefaultApps()");
        }

        try {
            final TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_DEFAULT_APPS,
                    (parser1, userId1) -> {
                        final String defaultBrowser;
                        synchronized (mPm.mLock) {
                            mPm.mSettings.readDefaultAppsLPw(parser1, userId1);
                            defaultBrowser = mPm.mSettings.removeDefaultBrowserPackageNameLPw(
                                    userId1);
                        }
                        if (defaultBrowser != null) {
                            mPm.setDefaultBrowser(defaultBrowser, false, userId1);
                        }
                    });
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring default apps: " + e.getMessage());
            }
        }
    }

    public void resetApplicationPreferences(int userId) {
        mPm.mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
        final long identity = Binder.clearCallingIdentity();
        // writer
        try {
            final SparseBooleanArray changedUsers = new SparseBooleanArray();
            synchronized (mPm.mLock) {
                mPm.clearPackagePreferredActivitiesLPw(null, changedUsers, userId);
            }
            if (changedUsers.size() > 0) {
                mPm.postPreferredActivityChangedBroadcast(userId);
            }
            synchronized (mPm.mLock) {
                mPm.mSettings.applyDefaultPreferredAppsLPw(userId);
                mPm.mDomainVerificationManager.clearUser(userId);
                final int numPackages = mPm.mPackages.size();
                for (int i = 0; i < numPackages; i++) {
                    final AndroidPackage pkg = mPm.mPackages.valueAt(i);
                    mPm.mPermissionManager.resetRuntimePermissions(pkg, userId);
                }
            }
            updateDefaultHomeNotLocked(mPm.snapshotComputer(), userId);
            resetNetworkPolicies(userId);
            mPm.scheduleWritePackageRestrictions(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void resetNetworkPolicies(int userId) {
        mPm.mInjector.getLocalService(NetworkPolicyManagerInternal.class).resetUserState(userId);
    }

    public int getPreferredActivities(@NonNull Computer snapshot, List<IntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName) {
        List<WatchedIntentFilter> temp =
                WatchedIntentFilter.toWatchedIntentFilterList(outFilters);
        int result = getPreferredActivitiesInternal(snapshot, temp, outActivities, packageName);
        outFilters.clear();
        for (int i = 0; i < temp.size(); i++) {
            outFilters.add(temp.get(i).getIntentFilter());
        }
        return result;
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    private int getPreferredActivitiesInternal(@NonNull Computer snapshot,
            List<WatchedIntentFilter> outFilters, List<ComponentName> outActivities,
            String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (snapshot.getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        int num = 0;
        final int userId = UserHandle.getCallingUserId();

        PreferredIntentResolver pir = snapshot.getPreferredActivities(userId);
        if (pir != null) {
            final Iterator<PreferredActivity> it = pir.filterIterator();
            while (it.hasNext()) {
                final PreferredActivity pa = it.next();
                final String prefPackageName = pa.mPref.mComponent.getPackageName();
                if (packageName == null
                        || (prefPackageName.equals(packageName) && pa.mPref.mAlways)) {
                    if (snapshot.shouldFilterApplication(
                            snapshot.getPackageStateInternal(prefPackageName), callingUid,
                            userId)) {
                        continue;
                    }
                    if (outFilters != null) {
                        outFilters.add(new WatchedIntentFilter(pa.getIntentFilter()));
                    }
                    if (outActivities != null) {
                        outActivities.add(pa.mPref.mComponent);
                    }
                }
            }
        }

        return num;
    }

    public ResolveInfo findPersistentPreferredActivity(@NonNull Computer snapshot, Intent intent,
            int userId) {
        if (!UserHandle.isSameApp(Binder.getCallingUid(), Process.SYSTEM_UID)) {
            throw new SecurityException(
                    "findPersistentPreferredActivity can only be run by the system");
        }
        if (!mPm.mUserManager.exists(userId)) {
            return null;
        }
        final int callingUid = Binder.getCallingUid();
        intent = PackageManagerServiceUtils.updateIntentForResolve(intent);
        final String resolvedType = intent.resolveTypeIfNeeded(mPm.mContext.getContentResolver());
        final long flags = snapshot.updateFlagsForResolve(
                0, userId, callingUid, false /*includeInstantApps*/,
                snapshot.isImplicitImageCaptureIntentAndNotSetByDpc(intent, userId, resolvedType,
                        0));
        final List<ResolveInfo> query = snapshot.queryIntentActivitiesInternal(intent,
                resolvedType, flags, userId);
        return snapshot.findPersistentPreferredActivity(intent, resolvedType, flags, query, false,
                userId);
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void setLastChosenActivity(@NonNull Computer snapshot, Intent intent,
            String resolvedType, int flags, WatchedIntentFilter filter, int match,
            ComponentName activity) {
        if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG_PREFERRED) {
            Log.v(TAG, "setLastChosenActivity intent=" + intent
                    + " resolvedType=" + resolvedType
                    + " flags=" + flags
                    + " filter=" + filter
                    + " match=" + match
                    + " activity=" + activity);
            filter.dump(new PrintStreamPrinter(System.out), "    ");
        }
        intent.setComponent(null);
        final List<ResolveInfo> query = snapshot.queryIntentActivitiesInternal(intent,
                resolvedType, flags, userId);
        // Find any earlier preferred or last chosen entries and nuke them
        findPreferredActivityNotLocked(snapshot, intent, resolvedType, flags, query, false, true,
                false, userId);
        // Add the new activity as the last chosen for this filter
        addPreferredActivity(snapshot, filter, match, null, activity, false, userId,
                "Setting last chosen", false);
    }

    public ResolveInfo getLastChosenActivity(@NonNull Computer snapshot, Intent intent,
            String resolvedType, int flags) {
        if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG_PREFERRED) Log.v(TAG, "Querying last chosen activity for " + intent);
        final List<ResolveInfo> query = snapshot.queryIntentActivitiesInternal(intent,
                resolvedType, flags, userId);
        return findPreferredActivityNotLocked(snapshot, intent, resolvedType, flags, query, false,
                false, false, userId);
    }
}
