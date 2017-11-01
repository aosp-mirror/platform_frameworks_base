/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.settingslib.applications;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.Application;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageStats;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.format.Formatter;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;

import java.io.File;
import java.io.IOException;
import java.text.Collator;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps track of information about all installed applications, lazy-loading
 * as needed.
 */
public class ApplicationsState {
    static final String TAG = "ApplicationsState";
    static final boolean DEBUG = false;
    static final boolean DEBUG_LOCKING = false;

    public static final int SIZE_UNKNOWN = -1;
    public static final int SIZE_INVALID = -2;

    static final Pattern REMOVE_DIACRITICALS_PATTERN
            = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    static final Object sLock = new Object();
    static ApplicationsState sInstance;

    public static ApplicationsState getInstance(Application app) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new ApplicationsState(app);
            }
            return sInstance;
        }
    }

    final Context mContext;
    final PackageManager mPm;
    final IconDrawableFactory mDrawableFactory;
    final IPackageManager mIpm;
    final UserManager mUm;
    final StorageStatsManager mStats;
    final int mAdminRetrieveFlags;
    final int mRetrieveFlags;
    PackageIntentReceiver mPackageIntentReceiver;

    boolean mResumed;
    boolean mHaveDisabledApps;
    boolean mHaveInstantApps;

    // Information about all applications.  Synchronize on mEntriesMap
    // to protect access to these.
    final ArrayList<Session> mSessions = new ArrayList<Session>();
    final ArrayList<Session> mRebuildingSessions = new ArrayList<Session>();
    final InterestingConfigChanges mInterestingConfigChanges = new InterestingConfigChanges();
    // Map: userid => (Map: package name => AppEntry)
    final SparseArray<HashMap<String, AppEntry>> mEntriesMap =
            new SparseArray<HashMap<String, AppEntry>>();
    final ArrayList<AppEntry> mAppEntries = new ArrayList<AppEntry>();
    List<ApplicationInfo> mApplications = new ArrayList<ApplicationInfo>();
    long mCurId = 1;
    UUID mCurComputingSizeUuid;
    String mCurComputingSizePkg;
    int mCurComputingSizeUserId;
    boolean mSessionsChanged;

    // Temporary for dispatching session callbacks.  Only touched by main thread.
    final ArrayList<Session> mActiveSessions = new ArrayList<Session>();

    final HandlerThread mThread;
    final BackgroundHandler mBackgroundHandler;
    final MainHandler mMainHandler = new MainHandler(Looper.getMainLooper());

    private ApplicationsState(Application app) {
        mContext = app;
        mPm = mContext.getPackageManager();
        mDrawableFactory = IconDrawableFactory.newInstance(mContext);
        mIpm = AppGlobals.getPackageManager();
        mUm = mContext.getSystemService(UserManager.class);
        mStats = mContext.getSystemService(StorageStatsManager.class);
        for (int userId : mUm.getProfileIdsWithDisabled(UserHandle.myUserId())) {
            mEntriesMap.put(userId, new HashMap<String, AppEntry>());
        }
        mThread = new HandlerThread("ApplicationsState.Loader",
                Process.THREAD_PRIORITY_BACKGROUND);
        mThread.start();
        mBackgroundHandler = new BackgroundHandler(mThread.getLooper());

        // Only the owner can see all apps.
        mAdminRetrieveFlags = PackageManager.MATCH_ANY_USER |
                PackageManager.MATCH_DISABLED_COMPONENTS |
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        mRetrieveFlags = PackageManager.MATCH_DISABLED_COMPONENTS |
                PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;

        /**
         * This is a trick to prevent the foreground thread from being delayed.
         * The problem is that Dalvik monitors are initially spin locks, to keep
         * them lightweight.  This leads to unfair contention -- Even though the
         * background thread only holds the lock for a short amount of time, if
         * it keeps running and locking again it can prevent the main thread from
         * acquiring its lock for a long time...  sometimes even > 5 seconds
         * (leading to an ANR).
         *
         * Dalvik will promote a monitor to a "real" lock if it detects enough
         * contention on it.  It doesn't figure this out fast enough for us
         * here, though, so this little trick will force it to turn into a real
         * lock immediately.
         */
        synchronized (mEntriesMap) {
            try {
                mEntriesMap.wait(1);
            } catch (InterruptedException e) {
            }
        }
    }

    public Looper getBackgroundLooper() {
        return mThread.getLooper();
    }

    public Session newSession(Callbacks callbacks) {
        Session s = new Session(callbacks);
        synchronized (mEntriesMap) {
            mSessions.add(s);
        }
        return s;
    }

    void doResumeIfNeededLocked() {
        if (mResumed) {
            return;
        }
        mResumed = true;
        if (mPackageIntentReceiver == null) {
            mPackageIntentReceiver = new PackageIntentReceiver();
            mPackageIntentReceiver.registerReceiver();
        }
        mApplications = new ArrayList<ApplicationInfo>();
        for (UserInfo user : mUm.getProfiles(UserHandle.myUserId())) {
            try {
                // If this user is new, it needs a map created.
                if (mEntriesMap.indexOfKey(user.id) < 0) {
                    mEntriesMap.put(user.id, new HashMap<String, AppEntry>());
                }
                @SuppressWarnings("unchecked")
                ParceledListSlice<ApplicationInfo> list =
                        mIpm.getInstalledApplications(
                                user.isAdmin() ? mAdminRetrieveFlags : mRetrieveFlags,
                                user.id);
                mApplications.addAll(list.getList());
            } catch (RemoteException e) {
            }
        }

        if (mInterestingConfigChanges.applyNewConfig(mContext.getResources())) {
            // If an interesting part of the configuration has changed, we
            // should completely reload the app entries.
            clearEntries();
        } else {
            for (int i=0; i<mAppEntries.size(); i++) {
                mAppEntries.get(i).sizeStale = true;
            }
        }

        mHaveDisabledApps = false;
        mHaveInstantApps = false;
        for (int i=0; i<mApplications.size(); i++) {
            final ApplicationInfo info = mApplications.get(i);
            // Need to trim out any applications that are disabled by
            // something different than the user.
            if (!info.enabled) {
                if (info.enabledSetting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    mApplications.remove(i);
                    i--;
                    continue;
                }
                mHaveDisabledApps = true;
            }
            if (!mHaveInstantApps && AppUtils.isInstant(info)) {
                mHaveInstantApps = true;
            }

            int userId = UserHandle.getUserId(info.uid);
            final AppEntry entry = mEntriesMap.get(userId).get(info.packageName);
            if (entry != null) {
                entry.info = info;
            }
        }
        if (mAppEntries.size() > mApplications.size()) {
            // There are less apps now, some must have been uninstalled.
            clearEntries();
        }
        mCurComputingSizePkg = null;
        if (!mBackgroundHandler.hasMessages(BackgroundHandler.MSG_LOAD_ENTRIES)) {
            mBackgroundHandler.sendEmptyMessage(BackgroundHandler.MSG_LOAD_ENTRIES);
        }
    }

    private void clearEntries() {
        for (int i = 0; i < mEntriesMap.size(); i++) {
            mEntriesMap.valueAt(i).clear();
        }
        mAppEntries.clear();
    }

    public boolean haveDisabledApps() {
        return mHaveDisabledApps;
    }
    public boolean haveInstantApps() {
        return mHaveInstantApps;
    }

    void doPauseIfNeededLocked() {
        if (!mResumed) {
            return;
        }
        for (int i=0; i<mSessions.size(); i++) {
            if (mSessions.get(i).mResumed) {
                return;
            }
        }
        doPauseLocked();
    }

    void doPauseLocked() {
        mResumed = false;
        if (mPackageIntentReceiver != null) {
            mPackageIntentReceiver.unregisterReceiver();
            mPackageIntentReceiver = null;
        }
    }

    public AppEntry getEntry(String packageName, int userId) {
        if (DEBUG_LOCKING) Log.v(TAG, "getEntry about to acquire lock...");
        synchronized (mEntriesMap) {
            AppEntry entry = mEntriesMap.get(userId).get(packageName);
            if (entry == null) {
                ApplicationInfo info = getAppInfoLocked(packageName, userId);
                if (info == null) {
                    try {
                        info = mIpm.getApplicationInfo(packageName, 0, userId);
                    } catch (RemoteException e) {
                        Log.w(TAG, "getEntry couldn't reach PackageManager", e);
                        return null;
                    }
                }
                if (info != null) {
                    entry = getEntryLocked(info);
                }
            }
            if (DEBUG_LOCKING) Log.v(TAG, "...getEntry releasing lock");
            return entry;
        }
    }

    private ApplicationInfo getAppInfoLocked(String pkg, int userId) {
        for (int i = 0; i < mApplications.size(); i++) {
            ApplicationInfo info = mApplications.get(i);
            if (pkg.equals(info.packageName)
                    && userId == UserHandle.getUserId(info.uid)) {
                return info;
            }
        }
        return null;
    }

    public void ensureIcon(AppEntry entry) {
        if (entry.icon != null) {
            return;
        }
        synchronized (entry) {
            entry.ensureIconLocked(mContext, mDrawableFactory);
        }
    }

    public void requestSize(String packageName, int userId) {
        if (DEBUG_LOCKING) Log.v(TAG, "requestSize about to acquire lock...");
        synchronized (mEntriesMap) {
            AppEntry entry = mEntriesMap.get(userId).get(packageName);
            if (entry != null && (entry.info.flags & ApplicationInfo.FLAG_INSTALLED) != 0) {
                mBackgroundHandler.post(() -> {
                    try {
                        final StorageStats stats = mStats.queryStatsForPackage(
                                entry.info.storageUuid, packageName, UserHandle.of(userId));
                        final PackageStats legacy = new PackageStats(packageName, userId);
                        legacy.codeSize = stats.getCodeBytes();
                        legacy.dataSize = stats.getDataBytes();
                        legacy.cacheSize = stats.getCacheBytes();
                        try {
                            mBackgroundHandler.mStatsObserver.onGetStatsCompleted(legacy, true);
                        } catch (RemoteException ignored) {
                        }
                    } catch (NameNotFoundException | IOException e) {
                        Log.w(TAG, "Failed to query stats: " + e);
                        try {
                            mBackgroundHandler.mStatsObserver.onGetStatsCompleted(null, false);
                        } catch (RemoteException ignored) {
                        }
                    }
                });
            }
            if (DEBUG_LOCKING) Log.v(TAG, "...requestSize releasing lock");
        }
    }

    long sumCacheSizes() {
        long sum = 0;
        if (DEBUG_LOCKING) Log.v(TAG, "sumCacheSizes about to acquire lock...");
        synchronized (mEntriesMap) {
            if (DEBUG_LOCKING) Log.v(TAG, "-> sumCacheSizes now has lock");
            for (int i=mAppEntries.size()-1; i>=0; i--) {
                sum += mAppEntries.get(i).cacheSize;
            }
            if (DEBUG_LOCKING) Log.v(TAG, "...sumCacheSizes releasing lock");
        }
        return sum;
    }

    int indexOfApplicationInfoLocked(String pkgName, int userId) {
        for (int i=mApplications.size()-1; i>=0; i--) {
            ApplicationInfo appInfo = mApplications.get(i);
            if (appInfo.packageName.equals(pkgName)
                    && UserHandle.getUserId(appInfo.uid) == userId) {
                return i;
            }
        }
        return -1;
    }

    void addPackage(String pkgName, int userId) {
        try {
            synchronized (mEntriesMap) {
                if (DEBUG_LOCKING) Log.v(TAG, "addPackage acquired lock");
                if (DEBUG) Log.i(TAG, "Adding package " + pkgName);
                if (!mResumed) {
                    // If we are not resumed, we will do a full query the
                    // next time we resume, so there is no reason to do work
                    // here.
                    if (DEBUG_LOCKING) Log.v(TAG, "addPackage release lock: not resumed");
                    return;
                }
                if (indexOfApplicationInfoLocked(pkgName, userId) >= 0) {
                    if (DEBUG) Log.i(TAG, "Package already exists!");
                    if (DEBUG_LOCKING) Log.v(TAG, "addPackage release lock: already exists");
                    return;
                }
                ApplicationInfo info = mIpm.getApplicationInfo(pkgName,
                        mUm.isUserAdmin(userId) ? mAdminRetrieveFlags : mRetrieveFlags,
                        userId);
                if (info == null) {
                    return;
                }
                if (!info.enabled) {
                    if (info.enabledSetting
                            != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                        return;
                    }
                    mHaveDisabledApps = true;
                }
                if (AppUtils.isInstant(info)) {
                    mHaveInstantApps = true;
                }
                mApplications.add(info);
                if (!mBackgroundHandler.hasMessages(BackgroundHandler.MSG_LOAD_ENTRIES)) {
                    mBackgroundHandler.sendEmptyMessage(BackgroundHandler.MSG_LOAD_ENTRIES);
                }
                if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_LIST_CHANGED)) {
                    mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_LIST_CHANGED);
                }
                if (DEBUG_LOCKING) Log.v(TAG, "addPackage releasing lock");
            }
        } catch (RemoteException e) {
        }
    }

    public void removePackage(String pkgName, int userId) {
        synchronized (mEntriesMap) {
            if (DEBUG_LOCKING) Log.v(TAG, "removePackage acquired lock");
            int idx = indexOfApplicationInfoLocked(pkgName, userId);
            if (DEBUG) Log.i(TAG, "removePackage: " + pkgName + " @ " + idx);
            if (idx >= 0) {
                AppEntry entry = mEntriesMap.get(userId).get(pkgName);
                if (DEBUG) Log.i(TAG, "removePackage: " + entry);
                if (entry != null) {
                    mEntriesMap.get(userId).remove(pkgName);
                    mAppEntries.remove(entry);
                }
                ApplicationInfo info = mApplications.get(idx);
                mApplications.remove(idx);
                if (!info.enabled) {
                    mHaveDisabledApps = false;
                    for (ApplicationInfo otherInfo : mApplications) {
                        if (!otherInfo.enabled) {
                            mHaveDisabledApps = true;
                            break;
                        }
                    }
                }
                if (AppUtils.isInstant(info)) {
                    mHaveInstantApps = false;
                    for (ApplicationInfo otherInfo : mApplications) {
                        if (AppUtils.isInstant(otherInfo)) {
                            mHaveInstantApps = true;
                            break;
                        }
                    }
                }
                if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_LIST_CHANGED)) {
                    mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_LIST_CHANGED);
                }
            }
            if (DEBUG_LOCKING) Log.v(TAG, "removePackage releasing lock");
        }
    }

    public void invalidatePackage(String pkgName, int userId) {
        removePackage(pkgName, userId);
        addPackage(pkgName, userId);
    }

    private void addUser(int userId) {
        final int profileIds[] = mUm.getProfileIdsWithDisabled(UserHandle.myUserId());
        if (ArrayUtils.contains(profileIds, userId)) {
            synchronized (mEntriesMap) {
                mEntriesMap.put(userId, new HashMap<String, AppEntry>());
                if (mResumed) {
                    // If resumed, Manually pause, then cause a resume to repopulate the app list.
                    // This is the simplest way to reload the packages so that the new user
                    // is included.  Otherwise the list will be repopulated on next resume.
                    doPauseLocked();
                    doResumeIfNeededLocked();
                }
                if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_LIST_CHANGED)) {
                    mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_LIST_CHANGED);
                }
            }
        }
    }

    private void removeUser(int userId) {
        synchronized (mEntriesMap) {
            HashMap<String, AppEntry> userMap = mEntriesMap.get(userId);
            if (userMap != null) {
                for (AppEntry appEntry : userMap.values()) {
                    mAppEntries.remove(appEntry);
                    mApplications.remove(appEntry.info);
                }
                mEntriesMap.remove(userId);
                if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_LIST_CHANGED)) {
                    mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_LIST_CHANGED);
                }
            }
        }
    }

    private AppEntry getEntryLocked(ApplicationInfo info) {
        int userId = UserHandle.getUserId(info.uid);
        AppEntry entry = mEntriesMap.get(userId).get(info.packageName);
        if (DEBUG) Log.i(TAG, "Looking up entry of pkg " + info.packageName + ": " + entry);
        if (entry == null) {
            if (DEBUG) Log.i(TAG, "Creating AppEntry for " + info.packageName);
            entry = new AppEntry(mContext, info, mCurId++);
            mEntriesMap.get(userId).put(info.packageName, entry);
            mAppEntries.add(entry);
        } else if (entry.info != info) {
            entry.info = info;
        }
        return entry;
    }

    // --------------------------------------------------------------

    private long getTotalInternalSize(PackageStats ps) {
        if (ps != null) {
            return ps.codeSize + ps.dataSize;
        }
        return SIZE_INVALID;
    }

    private long getTotalExternalSize(PackageStats ps) {
        if (ps != null) {
            // We also include the cache size here because for non-emulated
            // we don't automtically clean cache files.
            return ps.externalCodeSize + ps.externalDataSize
                    + ps.externalCacheSize
                    + ps.externalMediaSize + ps.externalObbSize;
        }
        return SIZE_INVALID;
    }

    private String getSizeStr(long size) {
        if (size >= 0) {
            return Formatter.formatFileSize(mContext, size);
        }
        return null;
    }

    void rebuildActiveSessions() {
        synchronized (mEntriesMap) {
            if (!mSessionsChanged) {
                return;
            }
            mActiveSessions.clear();
            for (int i=0; i<mSessions.size(); i++) {
                Session s = mSessions.get(i);
                if (s.mResumed) {
                    mActiveSessions.add(s);
                }
            }
        }
    }

    public static String normalize(String str) {
        String tmp = Normalizer.normalize(str, Form.NFD);
        return REMOVE_DIACRITICALS_PATTERN.matcher(tmp)
                .replaceAll("").toLowerCase();
    }

    public class Session {
        final Callbacks mCallbacks;
        boolean mResumed;

        // Rebuilding of app list.  Synchronized on mRebuildSync.
        final Object mRebuildSync = new Object();
        boolean mRebuildRequested;
        boolean mRebuildAsync;
        AppFilter mRebuildFilter;
        Comparator<AppEntry> mRebuildComparator;
        ArrayList<AppEntry> mRebuildResult;
        ArrayList<AppEntry> mLastAppList;
        boolean mRebuildForeground;

        Session(Callbacks callbacks) {
            mCallbacks = callbacks;
        }

        public void resume() {
            if (DEBUG_LOCKING) Log.v(TAG, "resume about to acquire lock...");
            synchronized (mEntriesMap) {
                if (!mResumed) {
                    mResumed = true;
                    mSessionsChanged = true;
                    doResumeIfNeededLocked();
                }
            }
            if (DEBUG_LOCKING) Log.v(TAG, "...resume releasing lock");
        }

        public void pause() {
            if (DEBUG_LOCKING) Log.v(TAG, "pause about to acquire lock...");
            synchronized (mEntriesMap) {
                if (mResumed) {
                    mResumed = false;
                    mSessionsChanged = true;
                    mBackgroundHandler.removeMessages(BackgroundHandler.MSG_REBUILD_LIST, this);
                    doPauseIfNeededLocked();
                }
                if (DEBUG_LOCKING) Log.v(TAG, "...pause releasing lock");
            }
        }

        public ArrayList<AppEntry> getAllApps() {
            synchronized (mEntriesMap) {
                return new ArrayList<>(mAppEntries);
            }
        }

        // Creates a new list of app entries with the given filter and comparator.
        public ArrayList<AppEntry> rebuild(AppFilter filter, Comparator<AppEntry> comparator) {
            return rebuild(filter, comparator, true);
        }

        public ArrayList<AppEntry> rebuild(AppFilter filter, Comparator<AppEntry> comparator,
                boolean foreground) {
            synchronized (mRebuildSync) {
                synchronized (mRebuildingSessions) {
                    mRebuildingSessions.add(this);
                    mRebuildRequested = true;
                    mRebuildAsync = true;
                    mRebuildFilter = filter;
                    mRebuildComparator = comparator;
                    mRebuildForeground = foreground;
                    mRebuildResult = null;
                    if (!mBackgroundHandler.hasMessages(BackgroundHandler.MSG_REBUILD_LIST)) {
                        Message msg = mBackgroundHandler.obtainMessage(
                                BackgroundHandler.MSG_REBUILD_LIST);
                        mBackgroundHandler.sendMessage(msg);
                    }
                }

                return null;
            }
        }

        void handleRebuildList() {
            AppFilter filter;
            Comparator<AppEntry> comparator;
            synchronized (mRebuildSync) {
                if (!mRebuildRequested) {
                    return;
                }

                filter = mRebuildFilter;
                comparator = mRebuildComparator;
                mRebuildRequested = false;
                mRebuildFilter = null;
                mRebuildComparator = null;
                if (mRebuildForeground) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
                    mRebuildForeground = false;
                }
            }

            if (filter != null) {
                filter.init(mContext);
            }

            List<AppEntry> apps;
            synchronized (mEntriesMap) {
                apps = new ArrayList<>(mAppEntries);
            }

            ArrayList<AppEntry> filteredApps = new ArrayList<AppEntry>();
            if (DEBUG) Log.i(TAG, "Rebuilding...");
            for (int i=0; i<apps.size(); i++) {
                AppEntry entry = apps.get(i);
                if (entry != null && (filter == null || filter.filterApp(entry))) {
                    synchronized (mEntriesMap) {
                        if (DEBUG_LOCKING) Log.v(TAG, "rebuild acquired lock");
                        if (comparator != null) {
                            // Only need the label if we are going to be sorting.
                            entry.ensureLabel(mContext);
                        }
                        if (DEBUG) Log.i(TAG, "Using " + entry.info.packageName + ": " + entry);
                        filteredApps.add(entry);
                        if (DEBUG_LOCKING) Log.v(TAG, "rebuild releasing lock");
                    }
                }
            }

            if (comparator != null) {
                synchronized (mEntriesMap) {
                    // Locking to ensure that the background handler does not mutate
                    // the size of AppEntries used for ordering while sorting.
                    Collections.sort(filteredApps, comparator);
                }
            }

            synchronized (mRebuildSync) {
                if (!mRebuildRequested) {
                    mLastAppList = filteredApps;
                    if (!mRebuildAsync) {
                        mRebuildResult = filteredApps;
                        mRebuildSync.notifyAll();
                    } else {
                        if (!mMainHandler.hasMessages(MainHandler.MSG_REBUILD_COMPLETE, this)) {
                            Message msg = mMainHandler.obtainMessage(
                                    MainHandler.MSG_REBUILD_COMPLETE, this);
                            mMainHandler.sendMessage(msg);
                        }
                    }
                }
            }

            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        }

        public void release() {
            pause();
            synchronized (mEntriesMap) {
                mSessions.remove(this);
            }
        }
    }

    class MainHandler extends Handler {
        static final int MSG_REBUILD_COMPLETE = 1;
        static final int MSG_PACKAGE_LIST_CHANGED = 2;
        static final int MSG_PACKAGE_ICON_CHANGED = 3;
        static final int MSG_PACKAGE_SIZE_CHANGED = 4;
        static final int MSG_ALL_SIZES_COMPUTED = 5;
        static final int MSG_RUNNING_STATE_CHANGED = 6;
        static final int MSG_LAUNCHER_INFO_CHANGED = 7;
        static final int MSG_LOAD_ENTRIES_COMPLETE = 8;

        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            rebuildActiveSessions();
            switch (msg.what) {
                case MSG_REBUILD_COMPLETE: {
                    Session s = (Session)msg.obj;
                    if (mActiveSessions.contains(s)) {
                        s.mCallbacks.onRebuildComplete(s.mLastAppList);
                    }
                } break;
                case MSG_PACKAGE_LIST_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onPackageListChanged();
                    }
                } break;
                case MSG_PACKAGE_ICON_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onPackageIconChanged();
                    }
                } break;
                case MSG_PACKAGE_SIZE_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onPackageSizeChanged(
                                (String)msg.obj);
                    }
                } break;
                case MSG_ALL_SIZES_COMPUTED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onAllSizesComputed();
                    }
                } break;
                case MSG_RUNNING_STATE_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onRunningStateChanged(
                                msg.arg1 != 0);
                    }
                } break;
                case MSG_LAUNCHER_INFO_CHANGED: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onLauncherInfoChanged();
                    }
                } break;
                case MSG_LOAD_ENTRIES_COMPLETE: {
                    for (int i=0; i<mActiveSessions.size(); i++) {
                        mActiveSessions.get(i).mCallbacks.onLoadEntriesCompleted();
                    }
                } break;
            }
        }
    }

    private class BackgroundHandler extends Handler {
        static final int MSG_REBUILD_LIST = 1;
        static final int MSG_LOAD_ENTRIES = 2;
        static final int MSG_LOAD_ICONS = 3;
        static final int MSG_LOAD_SIZES = 4;
        static final int MSG_LOAD_LAUNCHER = 5;
        static final int MSG_LOAD_HOME_APP = 6;

        boolean mRunning;

        BackgroundHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            // Always try rebuilding list first thing, if needed.
            ArrayList<Session> rebuildingSessions = null;
            synchronized (mRebuildingSessions) {
                if (mRebuildingSessions.size() > 0) {
                    rebuildingSessions = new ArrayList<Session>(mRebuildingSessions);
                    mRebuildingSessions.clear();
                }
            }
            if (rebuildingSessions != null) {
                for (int i=0; i<rebuildingSessions.size(); i++) {
                    rebuildingSessions.get(i).handleRebuildList();
                }
            }

            switch (msg.what) {
                case MSG_REBUILD_LIST: {
                } break;
                case MSG_LOAD_ENTRIES: {
                    int numDone = 0;
                    synchronized (mEntriesMap) {
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_ENTRIES acquired lock");
                        for (int i = 0; i < mApplications.size() && numDone < 6; i++) {
                            if (!mRunning) {
                                mRunning = true;
                                Message m = mMainHandler.obtainMessage(
                                        MainHandler.MSG_RUNNING_STATE_CHANGED, 1);
                                mMainHandler.sendMessage(m);
                            }
                            ApplicationInfo info = mApplications.get(i);
                            int userId = UserHandle.getUserId(info.uid);
                            if (mEntriesMap.get(userId).get(info.packageName) == null) {
                                numDone++;
                                getEntryLocked(info);
                            }
                            if (userId != 0 && mEntriesMap.indexOfKey(0) >= 0) {
                                // If this app is for a profile and we are on the owner, remove
                                // the owner entry if it isn't installed.  This will prevent
                                // duplicates of work only apps showing up as 'not installed
                                // for this user'.
                                // Note: This depends on us traversing the users in order, which
                                // happens because of the way we generate the list in
                                // doResumeIfNeededLocked.
                                AppEntry entry = mEntriesMap.get(0).get(info.packageName);
                                if (entry != null &&
                                        (entry.info.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                                    mEntriesMap.get(0).remove(info.packageName);
                                    mAppEntries.remove(entry);
                                }
                            }
                        }
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_ENTRIES releasing lock");
                    }

                    if (numDone >= 6) {
                        sendEmptyMessage(MSG_LOAD_ENTRIES);
                    } else {
                        if (!mMainHandler.hasMessages(MainHandler.MSG_LOAD_ENTRIES_COMPLETE)) {
                            mMainHandler.sendEmptyMessage(MainHandler.MSG_LOAD_ENTRIES_COMPLETE);
                        }
                        sendEmptyMessage(MSG_LOAD_HOME_APP);
                    }
                } break;
                case MSG_LOAD_HOME_APP: {
                    final List<ResolveInfo> homeActivities = new ArrayList<>();
                    mPm.getHomeActivities(homeActivities);
                    synchronized (mEntriesMap) {
                        final int entryCount = mEntriesMap.size();
                        for (int i = 0; i < entryCount; i++) {
                            if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_HOME_APP acquired lock");
                            final HashMap<String, AppEntry> userEntries = mEntriesMap.valueAt(i);
                            for (ResolveInfo activity : homeActivities) {
                                String packageName = activity.activityInfo.packageName;
                                AppEntry entry = userEntries.get(packageName);
                                if (entry != null) {
                                    entry.isHomeApp = true;
                                }
                            }
                            if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_HOME_APP releasing lock");
                        }
                    }
                    sendEmptyMessage(MSG_LOAD_LAUNCHER);
                }
                break;
                case MSG_LOAD_LAUNCHER: {
                    Intent launchIntent = new Intent(Intent.ACTION_MAIN, null)
                            .addCategory(Intent.CATEGORY_LAUNCHER);
                    for (int i = 0; i < mEntriesMap.size(); i++) {
                        int userId = mEntriesMap.keyAt(i);
                        // If we do not specify MATCH_DIRECT_BOOT_AWARE or
                        // MATCH_DIRECT_BOOT_UNAWARE, system will derive and update the flags
                        // according to the user's lock state. When the user is locked, components
                        // with ComponentInfo#directBootAware == false will be filtered. We should
                        // explicitly include both direct boot aware and unaware components here.
                        List<ResolveInfo> intents = mPm.queryIntentActivitiesAsUser(
                                launchIntent,
                                PackageManager.MATCH_DISABLED_COMPONENTS
                                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                                userId
                        );
                        synchronized (mEntriesMap) {
                            if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_LAUNCHER acquired lock");
                            HashMap<String, AppEntry> userEntries = mEntriesMap.valueAt(i);
                            final int N = intents.size();
                            for (int j = 0; j < N; j++) {
                                String packageName = intents.get(j).activityInfo.packageName;
                                AppEntry entry = userEntries.get(packageName);
                                if (entry != null) {
                                    entry.hasLauncherEntry = true;
                                } else {
                                    Log.w(TAG, "Cannot find pkg: " + packageName
                                            + " on user " + userId);
                                }
                            }
                            if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_LAUNCHER releasing lock");
                        }
                    }

                    if (!mMainHandler.hasMessages(MainHandler.MSG_LAUNCHER_INFO_CHANGED)) {
                        mMainHandler.sendEmptyMessage(MainHandler.MSG_LAUNCHER_INFO_CHANGED);
                    }
                    sendEmptyMessage(MSG_LOAD_ICONS);
                } break;
                case MSG_LOAD_ICONS: {
                    int numDone = 0;
                    synchronized (mEntriesMap) {
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_ICONS acquired lock");
                        for (int i=0; i<mAppEntries.size() && numDone<2; i++) {
                            AppEntry entry = mAppEntries.get(i);
                            if (entry.icon == null || !entry.mounted) {
                                synchronized (entry) {
                                    if (entry.ensureIconLocked(mContext, mDrawableFactory)) {
                                        if (!mRunning) {
                                            mRunning = true;
                                            Message m = mMainHandler.obtainMessage(
                                                    MainHandler.MSG_RUNNING_STATE_CHANGED, 1);
                                            mMainHandler.sendMessage(m);
                                        }
                                        numDone++;
                                    }
                                }
                            }
                        }
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_ICONS releasing lock");
                    }
                    if (numDone > 0) {
                        if (!mMainHandler.hasMessages(MainHandler.MSG_PACKAGE_ICON_CHANGED)) {
                            mMainHandler.sendEmptyMessage(MainHandler.MSG_PACKAGE_ICON_CHANGED);
                        }
                    }
                    if (numDone >= 2) {
                        sendEmptyMessage(MSG_LOAD_ICONS);
                    } else {
                        sendEmptyMessage(MSG_LOAD_SIZES);
                    }
                } break;
                case MSG_LOAD_SIZES: {
                    synchronized (mEntriesMap) {
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_SIZES acquired lock");
                        if (mCurComputingSizePkg != null) {
                            if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_SIZES releasing: currently computing");
                            return;
                        }

                        long now = SystemClock.uptimeMillis();
                        for (int i=0; i<mAppEntries.size(); i++) {
                            AppEntry entry = mAppEntries.get(i);
                            if ((entry.info.flags & ApplicationInfo.FLAG_INSTALLED) != 0
                                    && (entry.size == SIZE_UNKNOWN || entry.sizeStale)) {
                                if (entry.sizeLoadStart == 0 ||
                                        (entry.sizeLoadStart < (now-20*1000))) {
                                    if (!mRunning) {
                                        mRunning = true;
                                        Message m = mMainHandler.obtainMessage(
                                                MainHandler.MSG_RUNNING_STATE_CHANGED, 1);
                                        mMainHandler.sendMessage(m);
                                    }
                                    entry.sizeLoadStart = now;
                                    mCurComputingSizeUuid = entry.info.storageUuid;
                                    mCurComputingSizePkg = entry.info.packageName;
                                    mCurComputingSizeUserId = UserHandle.getUserId(entry.info.uid);

                                    mBackgroundHandler.post(() -> {
                                        try {
                                            final StorageStats stats = mStats.queryStatsForPackage(
                                                    mCurComputingSizeUuid, mCurComputingSizePkg,
                                                    UserHandle.of(mCurComputingSizeUserId));
                                            final PackageStats legacy = new PackageStats(
                                                    mCurComputingSizePkg, mCurComputingSizeUserId);
                                            legacy.codeSize = stats.getCodeBytes();
                                            legacy.dataSize = stats.getDataBytes();
                                            legacy.cacheSize = stats.getCacheBytes();
                                            try {
                                                mStatsObserver.onGetStatsCompleted(legacy, true);
                                            } catch (RemoteException ignored) {
                                            }
                                        } catch (NameNotFoundException | IOException e) {
                                            Log.w(TAG, "Failed to query stats: " + e);
                                            try {
                                                mStatsObserver.onGetStatsCompleted(null, false);
                                            } catch (RemoteException ignored) {
                                            }
                                        }

                                    });
                                }
                                if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_SIZES releasing: now computing");
                                return;
                            }
                        }
                        if (!mMainHandler.hasMessages(MainHandler.MSG_ALL_SIZES_COMPUTED)) {
                            mMainHandler.sendEmptyMessage(MainHandler.MSG_ALL_SIZES_COMPUTED);
                            mRunning = false;
                            Message m = mMainHandler.obtainMessage(
                                    MainHandler.MSG_RUNNING_STATE_CHANGED, 0);
                            mMainHandler.sendMessage(m);
                        }
                        if (DEBUG_LOCKING) Log.v(TAG, "MSG_LOAD_SIZES releasing lock");
                    }
                } break;
            }
        }

        final IPackageStatsObserver.Stub mStatsObserver = new IPackageStatsObserver.Stub() {
            public void onGetStatsCompleted(PackageStats stats, boolean succeeded) {
                if (!succeeded) {
                    // There is no meaningful information in stats if the call failed.
                    return;
                }

                boolean sizeChanged = false;
                synchronized (mEntriesMap) {
                    if (DEBUG_LOCKING) Log.v(TAG, "onGetStatsCompleted acquired lock");
                    HashMap<String, AppEntry> userMap = mEntriesMap.get(stats.userHandle);
                    if (userMap == null) {
                        // The user must have been removed.
                        return;
                    }
                    AppEntry entry = userMap.get(stats.packageName);
                    if (entry != null) {
                        synchronized (entry) {
                            entry.sizeStale = false;
                            entry.sizeLoadStart = 0;
                            long externalCodeSize = stats.externalCodeSize
                                    + stats.externalObbSize;
                            long externalDataSize = stats.externalDataSize
                                    + stats.externalMediaSize;
                            long newSize = externalCodeSize + externalDataSize
                                    + getTotalInternalSize(stats);
                            if (entry.size != newSize ||
                                    entry.cacheSize != stats.cacheSize ||
                                    entry.codeSize != stats.codeSize ||
                                    entry.dataSize != stats.dataSize ||
                                    entry.externalCodeSize != externalCodeSize ||
                                    entry.externalDataSize != externalDataSize ||
                                    entry.externalCacheSize != stats.externalCacheSize) {
                                entry.size = newSize;
                                entry.cacheSize = stats.cacheSize;
                                entry.codeSize = stats.codeSize;
                                entry.dataSize = stats.dataSize;
                                entry.externalCodeSize = externalCodeSize;
                                entry.externalDataSize = externalDataSize;
                                entry.externalCacheSize = stats.externalCacheSize;
                                entry.sizeStr = getSizeStr(entry.size);
                                entry.internalSize = getTotalInternalSize(stats);
                                entry.internalSizeStr = getSizeStr(entry.internalSize);
                                entry.externalSize = getTotalExternalSize(stats);
                                entry.externalSizeStr = getSizeStr(entry.externalSize);
                                if (DEBUG) Log.i(TAG, "Set size of " + entry.label + " " + entry
                                        + ": " + entry.sizeStr);
                                sizeChanged = true;
                            }
                        }
                        if (sizeChanged) {
                            Message msg = mMainHandler.obtainMessage(
                                    MainHandler.MSG_PACKAGE_SIZE_CHANGED, stats.packageName);
                            mMainHandler.sendMessage(msg);
                        }
                    }
                    if (mCurComputingSizePkg != null
                            && (mCurComputingSizePkg.equals(stats.packageName)
                            && mCurComputingSizeUserId == stats.userHandle)) {
                        mCurComputingSizePkg = null;
                        sendEmptyMessage(MSG_LOAD_SIZES);
                    }
                    if (DEBUG_LOCKING) Log.v(TAG, "onGetStatsCompleted releasing lock");
                }
            }
        };
    }

    /**
     * Receives notifications when applications are added/removed.
     */
    private class PackageIntentReceiver extends BroadcastReceiver {
        void registerReceiver() {
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addDataScheme("package");
            mContext.registerReceiver(this, filter);
            // Register for events related to sdcard installation.
            IntentFilter sdFilter = new IntentFilter();
            sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
            sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            mContext.registerReceiver(this, sdFilter);
            // Register for events related to user creation/deletion.
            IntentFilter userFilter = new IntentFilter();
            userFilter.addAction(Intent.ACTION_USER_ADDED);
            userFilter.addAction(Intent.ACTION_USER_REMOVED);
            mContext.registerReceiver(this, userFilter);
        }
        void unregisterReceiver() {
            mContext.unregisterReceiver(this);
        }
        @Override
        public void onReceive(Context context, Intent intent) {
            String actionStr = intent.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(actionStr)) {
                Uri data = intent.getData();
                String pkgName = data.getEncodedSchemeSpecificPart();
                for (int i = 0; i < mEntriesMap.size(); i++) {
                    addPackage(pkgName, mEntriesMap.keyAt(i));
                }
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(actionStr)) {
                Uri data = intent.getData();
                String pkgName = data.getEncodedSchemeSpecificPart();
                for (int i = 0; i < mEntriesMap.size(); i++) {
                    removePackage(pkgName, mEntriesMap.keyAt(i));
                }
            } else if (Intent.ACTION_PACKAGE_CHANGED.equals(actionStr)) {
                Uri data = intent.getData();
                String pkgName = data.getEncodedSchemeSpecificPart();
                for (int i = 0; i < mEntriesMap.size(); i++) {
                    invalidatePackage(pkgName, mEntriesMap.keyAt(i));
                }
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(actionStr) ||
                    Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(actionStr)) {
                // When applications become available or unavailable (perhaps because
                // the SD card was inserted or ejected) we need to refresh the
                // AppInfo with new label, icon and size information as appropriate
                // given the newfound (un)availability of the application.
                // A simple way to do that is to treat the refresh as a package
                // removal followed by a package addition.
                String pkgList[] = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                if (pkgList == null || pkgList.length == 0) {
                    // Ignore
                    return;
                }
                boolean avail = Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(actionStr);
                if (avail) {
                    for (String pkgName : pkgList) {
                        for (int i = 0; i < mEntriesMap.size(); i++) {
                            invalidatePackage(pkgName, mEntriesMap.keyAt(i));
                        }
                    }
                }
            } else if (Intent.ACTION_USER_ADDED.equals(actionStr)) {
                addUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL));
            } else if (Intent.ACTION_USER_REMOVED.equals(actionStr)) {
                removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL));
            }
        }
    }

    public interface Callbacks {
        void onRunningStateChanged(boolean running);
        void onPackageListChanged();
        void onRebuildComplete(ArrayList<AppEntry> apps);
        void onPackageIconChanged();
        void onPackageSizeChanged(String packageName);
        void onAllSizesComputed();
        void onLauncherInfoChanged();
        void onLoadEntriesCompleted();
    }

    public static class SizeInfo {
        public long cacheSize;
        public long codeSize;
        public long dataSize;
        public long externalCodeSize;
        public long externalDataSize;

        // This is the part of externalDataSize that is in the cache
        // section of external storage.  Note that we don't just combine
        // this with cacheSize because currently the platform can't
        // automatically trim this data when needed, so it is something
        // the user may need to manage.  The externalDataSize also includes
        // this value, since what this is here is really the part of
        // externalDataSize that we can just consider to be "cache" files
        // for purposes of cleaning them up in the app details UI.
        public long externalCacheSize;
    }

    public static class AppEntry extends SizeInfo {
        public final File apkFile;
        public final long id;
        public String label;
        public long size;
        public long internalSize;
        public long externalSize;

        public boolean mounted;

        /**
         * Setting this to {@code true} prevents the entry to be filtered by
         * {@link #FILTER_DOWNLOADED_AND_LAUNCHER}.
         */
        public boolean hasLauncherEntry;

        /**
         * Whether or not it's a Home app.
         */
        public boolean isHomeApp;

        public String getNormalizedLabel() {
            if (normalizedLabel != null) {
                return normalizedLabel;
            }
            normalizedLabel = normalize(label);
            return normalizedLabel;
        }

        // Need to synchronize on 'this' for the following.
        public ApplicationInfo info;
        public Drawable icon;
        public String sizeStr;
        public String internalSizeStr;
        public String externalSizeStr;
        public boolean sizeStale;
        public long sizeLoadStart;

        public String normalizedLabel;

        // A location where extra info can be placed to be used by custom filters.
        public Object extraInfo;

        AppEntry(Context context, ApplicationInfo info, long id) {
            apkFile = new File(info.sourceDir);
            this.id = id;
            this.info = info;
            this.size = SIZE_UNKNOWN;
            this.sizeStale = true;
            ensureLabel(context);
        }

        public void ensureLabel(Context context) {
            if (this.label == null || !this.mounted) {
                if (!this.apkFile.exists()) {
                    this.mounted = false;
                    this.label = info.packageName;
                } else {
                    this.mounted = true;
                    CharSequence label = info.loadLabel(context.getPackageManager());
                    this.label = label != null ? label.toString() : info.packageName;
                }
            }
        }

        boolean ensureIconLocked(Context context, IconDrawableFactory drawableFactory) {
            if (this.icon == null) {
                if (this.apkFile.exists()) {
                    this.icon = drawableFactory.getBadgedIcon(info);
                    return true;
                } else {
                    this.mounted = false;
                    this.icon = context.getDrawable(R.drawable.sym_app_on_sd_unavailable_icon);
                }
            } else if (!this.mounted) {
                // If the app wasn't mounted but is now mounted, reload
                // its icon.
                if (this.apkFile.exists()) {
                    this.mounted = true;
                    this.icon = drawableFactory.getBadgedIcon(info);
                    return true;
                }
            }
            return false;
        }

        public String getVersion(Context context) {
            try {
                return context.getPackageManager().getPackageInfo(info.packageName, 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                return "";
            }
        }
    }

    /**
     * Compare by label, then package name, then uid.
     */
    public static final Comparator<AppEntry> ALPHA_COMPARATOR = new Comparator<AppEntry>() {
        private final Collator sCollator = Collator.getInstance();
        @Override
        public int compare(AppEntry object1, AppEntry object2) {
            int compareResult = sCollator.compare(object1.label, object2.label);
            if (compareResult != 0) {
                return compareResult;
            }
            if (object1.info != null && object2.info != null) {
                compareResult =
                    sCollator.compare(object1.info.packageName, object2.info.packageName);
                if (compareResult != 0) {
                    return compareResult;
                }
            }
            return object1.info.uid - object2.info.uid;
        }
    };

    public static final Comparator<AppEntry> SIZE_COMPARATOR
            = new Comparator<AppEntry>() {
        @Override
        public int compare(AppEntry object1, AppEntry object2) {
            if (object1.size < object2.size) return 1;
            if (object1.size > object2.size) return -1;
            return ALPHA_COMPARATOR.compare(object1, object2);
        }
    };

    public static final Comparator<AppEntry> INTERNAL_SIZE_COMPARATOR
            = new Comparator<AppEntry>() {
        @Override
        public int compare(AppEntry object1, AppEntry object2) {
            if (object1.internalSize < object2.internalSize) return 1;
            if (object1.internalSize > object2.internalSize) return -1;
            return ALPHA_COMPARATOR.compare(object1, object2);
        }
    };

    public static final Comparator<AppEntry> EXTERNAL_SIZE_COMPARATOR
            = new Comparator<AppEntry>() {
        @Override
        public int compare(AppEntry object1, AppEntry object2) {
            if (object1.externalSize < object2.externalSize) return 1;
            if (object1.externalSize > object2.externalSize) return -1;
            return ALPHA_COMPARATOR.compare(object1, object2);
        }
    };

    public interface AppFilter {
        void init();
        default void init(Context context) {
            init();
        }
        boolean filterApp(AppEntry info);
    }

    public static final AppFilter FILTER_PERSONAL = new AppFilter() {
        private int mCurrentUser;

        @Override
        public void init() {
            mCurrentUser = ActivityManager.getCurrentUser();
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return UserHandle.getUserId(entry.info.uid) == mCurrentUser;
        }
    };

    public static final AppFilter FILTER_WITHOUT_DISABLED_UNTIL_USED = new AppFilter() {
        @Override
        public void init() {
            // do nothing
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return entry.info.enabledSetting
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        }
    };

    public static final AppFilter FILTER_WORK = new AppFilter() {
        private int mCurrentUser;

        @Override
        public void init() {
            mCurrentUser = ActivityManager.getCurrentUser();
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return UserHandle.getUserId(entry.info.uid) != mCurrentUser;
        }
    };

    /**
     * Displays a combined list with "downloaded" and "visible in launcher" apps only.
     */
    public static final AppFilter FILTER_DOWNLOADED_AND_LAUNCHER = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            if (AppUtils.isInstant(entry.info)) {
                return false;
            } else if ((entry.info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                return true;
            } else if ((entry.info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return true;
            } else if (entry.hasLauncherEntry) {
                return true;
            } else if ((entry.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && entry.isHomeApp) {
                return true;
            }
            return false;
        }
    };

    /**
     * Displays a combined list with "downloaded" and "visible in launcher" apps only.
     */
    public static final AppFilter FILTER_DOWNLOADED_AND_LAUNCHER_AND_INSTANT = new AppFilter() {

        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return AppUtils.isInstant(entry.info)
                    || FILTER_DOWNLOADED_AND_LAUNCHER.filterApp(entry);
        }

    };

    public static final AppFilter FILTER_THIRD_PARTY = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            if ((entry.info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                return true;
            } else if ((entry.info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return true;
            }
            return false;
        }
    };

    public static final AppFilter FILTER_DISABLED = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return !entry.info.enabled && !AppUtils.isInstant(entry.info);
        }
    };

    public static final AppFilter FILTER_INSTANT = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return AppUtils.isInstant(entry.info);
        }
    };

    public static final AppFilter FILTER_ALL_ENABLED = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return entry.info.enabled && !AppUtils.isInstant(entry.info);
        }
    };

    public static final AppFilter FILTER_EVERYTHING = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return true;
        }
    };

    public static final AppFilter FILTER_WITH_DOMAIN_URLS = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            return !AppUtils.isInstant(entry.info)
                && (entry.info.privateFlags & ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS) != 0;
        }
    };

    public static final AppFilter FILTER_NOT_HIDE = new AppFilter() {
        private String[] mHidePackageNames;

        @Override
        public void init(Context context) {
            mHidePackageNames = context.getResources()
                .getStringArray(R.array.config_hideWhenDisabled_packageNames);
        }

        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            if (ArrayUtils.contains(mHidePackageNames, entry.info.packageName)) {
                if (!entry.info.enabled) {
                    return false;
                } else if (entry.info.enabledSetting ==
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                    return false;
                }
            }

            return true;
        }
    };

    public static final AppFilter FILTER_GAMES = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(ApplicationsState.AppEntry info) {
            // TODO: Update for the new game category.
            boolean isGame;
            synchronized (info.info) {
                isGame = ((info.info.flags & ApplicationInfo.FLAG_IS_GAME) != 0)
                        || info.info.category == ApplicationInfo.CATEGORY_GAME;
            }
            return isGame;
        }
    };

    public static class VolumeFilter implements AppFilter {
        private final String mVolumeUuid;

        public VolumeFilter(String volumeUuid) {
            mVolumeUuid = volumeUuid;
        }

        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry info) {
            return Objects.equals(info.info.volumeUuid, mVolumeUuid);
        }
    }

    public static class CompoundFilter implements AppFilter {
        private final AppFilter mFirstFilter;
        private final AppFilter mSecondFilter;

        public CompoundFilter(AppFilter first, AppFilter second) {
            mFirstFilter = first;
            mSecondFilter = second;
        }

        @Override
        public void init(Context context) {
            mFirstFilter.init(context);
            mSecondFilter.init(context);
        }

        @Override
        public void init() {
            mFirstFilter.init();
            mSecondFilter.init();
        }

        @Override
        public boolean filterApp(AppEntry info) {
            return mFirstFilter.filterApp(info) && mSecondFilter.filterApp(info);
        }
    }

    public static final AppFilter FILTER_AUDIO = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            boolean isMusicApp;
            synchronized(entry) {
                isMusicApp = entry.info.category == ApplicationInfo.CATEGORY_AUDIO;
            }
            return isMusicApp;
        }
    };

    public static final AppFilter FILTER_MOVIES = new AppFilter() {
        @Override
        public void init() {
        }

        @Override
        public boolean filterApp(AppEntry entry) {
            boolean isMovieApp;
            synchronized(entry) {
                isMovieApp = entry.info.category == ApplicationInfo.CATEGORY_VIDEO;
            }
            return isMovieApp;
        }
    };

    public static final AppFilter FILTER_OTHER_APPS =
            new AppFilter() {
                @Override
                public void init() {}

                @Override
                public boolean filterApp(AppEntry entry) {
                    boolean isCategorized;
                    synchronized (entry) {
                        isCategorized =
                                FILTER_AUDIO.filterApp(entry)
                                        || FILTER_GAMES.filterApp(entry)
                                        || FILTER_MOVIES.filterApp(entry);
                    }
                    return !isCategorized;
                }
            };
}
