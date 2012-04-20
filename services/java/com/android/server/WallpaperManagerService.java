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

package com.android.server;

import static android.os.FileObserver.*;
import static android.os.ParcelFileDescriptor.*;

import android.app.IWallpaperManager;
import android.app.IWallpaperManagerCallback;
import android.app.PendingIntent;
import android.app.WallpaperInfo;
import android.app.backup.BackupManager;
import android.app.backup.WallpaperBackupHelper;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserId;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.IWallpaperEngine;
import android.service.wallpaper.IWallpaperService;
import android.service.wallpaper.WallpaperService;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManager;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.JournaledFile;
import com.android.server.am.ActivityManagerService;

class WallpaperManagerService extends IWallpaperManager.Stub {
    static final String TAG = "WallpaperService";
    static final boolean DEBUG = false;

    final Object mLock = new Object[0];

    /**
     * Minimum time between crashes of a wallpaper service for us to consider
     * restarting it vs. just reverting to the static wallpaper.
     */
    static final long MIN_WALLPAPER_CRASH_TIME = 10000;
    
    static final File WALLPAPER_BASE_DIR = new File("/data/system/users");
    static final String WALLPAPER = "wallpaper";
    static final String WALLPAPER_INFO = "wallpaper_info.xml";

    /**
     * Observes the wallpaper for changes and notifies all IWallpaperServiceCallbacks
     * that the wallpaper has changed. The CREATE is triggered when there is no
     * wallpaper set and is created for the first time. The CLOSE_WRITE is triggered
     * everytime the wallpaper is changed.
     */
    private class WallpaperObserver extends FileObserver {

        final WallpaperData mWallpaper;
        final File mWallpaperDir;
        final File mWallpaperFile;

        public WallpaperObserver(WallpaperData wallpaper) {
            super(getWallpaperDir(wallpaper.userId).getAbsolutePath(),
                    CLOSE_WRITE | DELETE | DELETE_SELF);
            mWallpaperDir = getWallpaperDir(wallpaper.userId);
            mWallpaper = wallpaper;
            mWallpaperFile = new File(mWallpaperDir, WALLPAPER);
        }

        @Override
        public void onEvent(int event, String path) {
            if (path == null) {
                return;
            }
            synchronized (mLock) {
                // changing the wallpaper means we'll need to back up the new one
                long origId = Binder.clearCallingIdentity();
                BackupManager bm = new BackupManager(mContext);
                bm.dataChanged();
                Binder.restoreCallingIdentity(origId);

                File changedFile = new File(mWallpaperDir, path);
                if (mWallpaperFile.equals(changedFile)) {
                    notifyCallbacksLocked(mWallpaper);
                    if (mWallpaper.wallpaperComponent == null || event != CLOSE_WRITE
                            || mWallpaper.imageWallpaperPending) {
                        if (event == CLOSE_WRITE) {
                            mWallpaper.imageWallpaperPending = false;
                        }
                        bindWallpaperComponentLocked(mWallpaper.imageWallpaperComponent, true,
                                false, mWallpaper);
                        saveSettingsLocked(mWallpaper);
                    }
                }
            }
        }
    }

    final Context mContext;
    final IWindowManager mIWindowManager;
    final MyPackageMonitor mMonitor;
    WallpaperData mLastWallpaper;

    SparseArray<WallpaperData> mWallpaperMap = new SparseArray<WallpaperData>();

    int mCurrentUserId;

    static class WallpaperData {

        int userId;

        File wallpaperFile;

        /**
         * Client is currently writing a new image wallpaper.
         */
        boolean imageWallpaperPending;

        /**
         * Resource name if using a picture from the wallpaper gallery
         */
        String name = "";

        /**
         * The component name of the currently set live wallpaper.
         */
        ComponentName wallpaperComponent;

        /**
         * The component name of the wallpaper that should be set next.
         */
        ComponentName nextWallpaperComponent;

        /**
         * Name of the component used to display bitmap wallpapers from either the gallery or
         * built-in wallpapers.
         */
        ComponentName imageWallpaperComponent = new ComponentName("com.android.systemui",
                "com.android.systemui.ImageWallpaper");

        WallpaperConnection connection;
        long lastDiedTime;
        boolean wallpaperUpdating;
        WallpaperObserver wallpaperObserver;

        /**
         * List of callbacks registered they should each be notified when the wallpaper is changed.
         */
        private RemoteCallbackList<IWallpaperManagerCallback> callbacks
                = new RemoteCallbackList<IWallpaperManagerCallback>();

        int width = -1;
        int height = -1;

        WallpaperData(int userId) {
            this.userId = userId;
            wallpaperFile = new File(getWallpaperDir(userId), WALLPAPER);
        }
    }

    class WallpaperConnection extends IWallpaperConnection.Stub
            implements ServiceConnection {
        final WallpaperInfo mInfo;
        final Binder mToken = new Binder();
        IWallpaperService mService;
        IWallpaperEngine mEngine;
        WallpaperData mWallpaper;

        public WallpaperConnection(WallpaperInfo info, WallpaperData wallpaper) {
            mInfo = info;
            mWallpaper = wallpaper;
        }
        
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                if (mWallpaper.connection == this) {
                    mWallpaper.lastDiedTime = SystemClock.uptimeMillis();
                    mService = IWallpaperService.Stub.asInterface(service);
                    attachServiceLocked(this, mWallpaper);
                    // XXX should probably do saveSettingsLocked() later
                    // when we have an engine, but I'm not sure about
                    // locking there and anyway we always need to be able to
                    // recover if there is something wrong.
                    saveSettingsLocked(mWallpaper);
                }
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mService = null;
                mEngine = null;
                if (mWallpaper.connection == this) {
                    Slog.w(TAG, "Wallpaper service gone: " + mWallpaper.wallpaperComponent);
                    if (!mWallpaper.wallpaperUpdating
                            && (mWallpaper.lastDiedTime + MIN_WALLPAPER_CRASH_TIME)
                                > SystemClock.uptimeMillis()
                            && mWallpaper.userId == mCurrentUserId) {
                        Slog.w(TAG, "Reverting to built-in wallpaper!");
                        clearWallpaperLocked(true, mWallpaper.userId);
                    }
                }
            }
        }

        public void attachEngine(IWallpaperEngine engine) {
            mEngine = engine;
        }

        public ParcelFileDescriptor setWallpaper(String name) {
            synchronized (mLock) {
                if (mWallpaper.connection == this) {
                    return updateWallpaperBitmapLocked(name, mWallpaper);
                }
                return null;
            }
        }
    }

    class MyPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            synchronized (mLock) {
                for (int i = 0; i < mWallpaperMap.size(); i++) {
                    WallpaperData wallpaper = mWallpaperMap.valueAt(i);
                    if (wallpaper.wallpaperComponent != null
                            && wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        wallpaper.wallpaperUpdating = false;
                        ComponentName comp = wallpaper.wallpaperComponent;
                        clearWallpaperComponentLocked(wallpaper);
                        // Do this only for the current user's wallpaper
                        if (wallpaper.userId == mCurrentUserId
                                && !bindWallpaperComponentLocked(comp, false, false, wallpaper)) {
                            Slog.w(TAG, "Wallpaper no longer available; reverting to default");
                            clearWallpaperLocked(false, wallpaper.userId);
                        }
                    }
                }
            }
        }

        @Override
        public void onPackageModified(String packageName) {
            synchronized (mLock) {
                for (int i = 0; i < mWallpaperMap.size(); i++) {
                    WallpaperData wallpaper = mWallpaperMap.valueAt(i);
                    if (wallpaper.wallpaperComponent == null
                            || !wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        continue;
                    }
                    doPackagesChangedLocked(true, wallpaper);
                }
            }
        }

        @Override
        public void onPackageUpdateStarted(String packageName, int uid) {
            synchronized (mLock) {
                for (int i = 0; i < mWallpaperMap.size(); i++) {
                    WallpaperData wallpaper = mWallpaperMap.valueAt(i);
                    if (wallpaper.wallpaperComponent != null
                            && wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        wallpaper.wallpaperUpdating = true;
                    }
                }
            }
        }

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            synchronized (mLock) {
                boolean changed = false;
                for (int i = 0; i < mWallpaperMap.size(); i++) {
                    WallpaperData wallpaper = mWallpaperMap.valueAt(i);
                    boolean res = doPackagesChangedLocked(doit, wallpaper);
                    changed |= res;
                }
                return changed;
            }
        }

        @Override
        public void onSomePackagesChanged() {
            synchronized (mLock) {
                for (int i = 0; i < mWallpaperMap.size(); i++) {
                    WallpaperData wallpaper = mWallpaperMap.valueAt(i);
                    doPackagesChangedLocked(true, wallpaper);
                }
            }
        }

        boolean doPackagesChangedLocked(boolean doit, WallpaperData wallpaper) {
            boolean changed = false;
            if (wallpaper.wallpaperComponent != null) {
                int change = isPackageDisappearing(wallpaper.wallpaperComponent
                        .getPackageName());
                if (change == PACKAGE_PERMANENT_CHANGE
                        || change == PACKAGE_TEMPORARY_CHANGE) {
                    changed = true;
                    if (doit) {
                        Slog.w(TAG, "Wallpaper uninstalled, removing: "
                                + wallpaper.wallpaperComponent);
                        clearWallpaperLocked(false, wallpaper.userId);
                    }
                }
            }
            if (wallpaper.nextWallpaperComponent != null) {
                int change = isPackageDisappearing(wallpaper.nextWallpaperComponent
                        .getPackageName());
                if (change == PACKAGE_PERMANENT_CHANGE
                        || change == PACKAGE_TEMPORARY_CHANGE) {
                    wallpaper.nextWallpaperComponent = null;
                }
            }
            if (wallpaper.wallpaperComponent != null
                    && isPackageModified(wallpaper.wallpaperComponent.getPackageName())) {
                try {
                    mContext.getPackageManager().getServiceInfo(
                            wallpaper.wallpaperComponent, 0);
                } catch (NameNotFoundException e) {
                    Slog.w(TAG, "Wallpaper component gone, removing: "
                            + wallpaper.wallpaperComponent);
                    clearWallpaperLocked(false, wallpaper.userId);
                }
            }
            if (wallpaper.nextWallpaperComponent != null
                    && isPackageModified(wallpaper.nextWallpaperComponent.getPackageName())) {
                try {
                    mContext.getPackageManager().getServiceInfo(
                            wallpaper.nextWallpaperComponent, 0);
                } catch (NameNotFoundException e) {
                    wallpaper.nextWallpaperComponent = null;
                }
            }
            return changed;
        }
    }
    
    public WallpaperManagerService(Context context) {
        if (DEBUG) Slog.v(TAG, "WallpaperService startup");
        mContext = context;
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mMonitor = new MyPackageMonitor();
        mMonitor.register(context, null, true);
        WALLPAPER_BASE_DIR.mkdirs();
        loadSettingsLocked(0);
    }
    
    private static File getWallpaperDir(int userId) {
        return new File(WALLPAPER_BASE_DIR + "/" + userId);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        for (int i = 0; i < mWallpaperMap.size(); i++) {
            WallpaperData wallpaper = mWallpaperMap.valueAt(i);
            wallpaper.wallpaperObserver.stopWatching();
        }
    }

    public void systemReady() {
        if (DEBUG) Slog.v(TAG, "systemReady");
        WallpaperData wallpaper = mWallpaperMap.get(0);
        switchWallpaper(wallpaper);
        wallpaper.wallpaperObserver = new WallpaperObserver(wallpaper);
        wallpaper.wallpaperObserver.startWatching();

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_SWITCHED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USERID, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USERID, 0));
                }
            }
        }, userFilter);
    }

    String getName() {
        return mWallpaperMap.get(0).name;
    }

    void removeUser(int userId) {
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(userId);
            if (wallpaper != null) {
                wallpaper.wallpaperObserver.stopWatching();
                mWallpaperMap.remove(userId);
            }
            File wallpaperFile = new File(getWallpaperDir(userId), WALLPAPER);
            wallpaperFile.delete();
            File wallpaperInfoFile = new File(getWallpaperDir(userId), WALLPAPER_INFO);
            wallpaperInfoFile.delete();
        }
    }

    void switchUser(int userId) {
        synchronized (mLock) {
            mCurrentUserId = userId;
            WallpaperData wallpaper = mWallpaperMap.get(userId);
            if (wallpaper == null) {
                wallpaper = new WallpaperData(userId);
                mWallpaperMap.put(userId, wallpaper);
                loadSettingsLocked(userId);
                wallpaper.wallpaperObserver = new WallpaperObserver(wallpaper);
                wallpaper.wallpaperObserver.startWatching();
            }
            switchWallpaper(wallpaper);
        }
    }

    void switchWallpaper(WallpaperData wallpaper) {
        synchronized (mLock) {
            RuntimeException e = null;
            try {
                ComponentName cname = wallpaper.wallpaperComponent != null ?
                        wallpaper.wallpaperComponent : wallpaper.nextWallpaperComponent;
                if (bindWallpaperComponentLocked(cname, true, false, wallpaper)) {
                    return;
                }
            } catch (RuntimeException e1) {
                e = e1;
            }
            Slog.w(TAG, "Failure starting previous wallpaper", e);
            clearWallpaperLocked(false, wallpaper.userId);
        }
    }

    public void clearWallpaper() {
        if (DEBUG) Slog.v(TAG, "clearWallpaper");
        synchronized (mLock) {
            clearWallpaperLocked(false, UserId.getCallingUserId());
        }
    }

    void clearWallpaperLocked(boolean defaultFailed, int userId) {
        WallpaperData wallpaper = mWallpaperMap.get(userId);
        File f = new File(getWallpaperDir(userId), WALLPAPER);
        if (f.exists()) {
            f.delete();
        }
        final long ident = Binder.clearCallingIdentity();
        RuntimeException e = null;
        try {
            wallpaper.imageWallpaperPending = false;
            if (userId != mCurrentUserId) return;
            if (bindWallpaperComponentLocked(defaultFailed
                    ? wallpaper.imageWallpaperComponent
                    : null, true, false, wallpaper)) {
                return;
            }
        } catch (IllegalArgumentException e1) {
            e = e1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        
        // This can happen if the default wallpaper component doesn't
        // exist.  This should be a system configuration problem, but
        // let's not let it crash the system and just live with no
        // wallpaper.
        Slog.e(TAG, "Default wallpaper component not found!", e);
        clearWallpaperComponentLocked(wallpaper);
    }

    public void setDimensionHints(int width, int height) throws RemoteException {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);

        int userId = UserId.getCallingUserId();
        WallpaperData wallpaper = mWallpaperMap.get(userId);
        if (wallpaper == null) {
            throw new IllegalStateException("Wallpaper not yet initialized for user " + userId);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }

        synchronized (mLock) {
            if (width != wallpaper.width || height != wallpaper.height) {
                wallpaper.width = width;
                wallpaper.height = height;
                saveSettingsLocked(wallpaper);
                if (mCurrentUserId != userId) return; // Don't change the properties now
                if (wallpaper.connection != null) {
                    if (wallpaper.connection.mEngine != null) {
                        try {
                            wallpaper.connection.mEngine.setDesiredSize(
                                    width, height);
                        } catch (RemoteException e) {
                        }
                        notifyCallbacksLocked(wallpaper);
                    }
                }
            }
        }
    }

    public int getWidthHint() throws RemoteException {
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(UserId.getCallingUserId());
            return wallpaper.width;
        }
    }

    public int getHeightHint() throws RemoteException {
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(UserId.getCallingUserId());
            return wallpaper.height;
        }
    }

    public ParcelFileDescriptor getWallpaper(IWallpaperManagerCallback cb,
            Bundle outParams) {
        synchronized (mLock) {
            // This returns the current user's wallpaper, if called by a system service. Else it
            // returns the wallpaper for the calling user.
            int callingUid = Binder.getCallingUid();
            int wallpaperUserId = 0;
            if (callingUid == android.os.Process.SYSTEM_UID) {
                wallpaperUserId = mCurrentUserId;
            } else {
                wallpaperUserId = UserId.getUserId(callingUid);
            }
            WallpaperData wallpaper = mWallpaperMap.get(wallpaperUserId);
            try {
                if (outParams != null) {
                    outParams.putInt("width", wallpaper.width);
                    outParams.putInt("height", wallpaper.height);
                }
                wallpaper.callbacks.register(cb);
                File f = new File(getWallpaperDir(wallpaperUserId), WALLPAPER);
                if (!f.exists()) {
                    return null;
                }
                return ParcelFileDescriptor.open(f, MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                /* Shouldn't happen as we check to see if the file exists */
                Slog.w(TAG, "Error getting wallpaper", e);
            }
            return null;
        }
    }

    public WallpaperInfo getWallpaperInfo() {
        int userId = UserId.getCallingUserId();
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(userId);
            if (wallpaper.connection != null) {
                return wallpaper.connection.mInfo;
            }
            return null;
        }
    }

    public ParcelFileDescriptor setWallpaper(String name) {
        if (DEBUG) Slog.v(TAG, "setWallpaper");
        int userId = UserId.getCallingUserId();
        WallpaperData wallpaper = mWallpaperMap.get(userId);
        if (wallpaper == null) {
            throw new IllegalStateException("Wallpaper not yet initialized for user " + userId);
        }
        checkPermission(android.Manifest.permission.SET_WALLPAPER);
        synchronized (mLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                ParcelFileDescriptor pfd = updateWallpaperBitmapLocked(name, wallpaper);
                if (pfd != null) {
                    wallpaper.imageWallpaperPending = true;
                }
                return pfd;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    ParcelFileDescriptor updateWallpaperBitmapLocked(String name, WallpaperData wallpaper) {
        if (name == null) name = "";
        try {
            File dir = getWallpaperDir(wallpaper.userId);
            if (!dir.exists()) {
                dir.mkdir();
                FileUtils.setPermissions(
                        dir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(dir, WALLPAPER),
                    MODE_CREATE|MODE_READ_WRITE);
            wallpaper.name = name;
            return fd;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Error setting wallpaper", e);
        }
        return null;
    }

    public void setWallpaperComponent(ComponentName name) {
        if (DEBUG) Slog.v(TAG, "setWallpaperComponent name=" + name);
        int userId = UserId.getCallingUserId();
        WallpaperData wallpaper = mWallpaperMap.get(userId);
        if (wallpaper == null) {
            throw new IllegalStateException("Wallpaper not yet initialized for user " + userId);
        }
        checkPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT);
        synchronized (mLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                wallpaper.imageWallpaperPending = false;
                bindWallpaperComponentLocked(name, false, true, wallpaper);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    boolean bindWallpaperComponentLocked(ComponentName componentName, boolean force,
            boolean fromUser, WallpaperData wallpaper) {
        if (DEBUG) Slog.v(TAG, "bindWallpaperComponentLocked: componentName=" + componentName);
        // Has the component changed?
        if (!force) {
            if (wallpaper.connection != null) {
                if (wallpaper.wallpaperComponent == null) {
                    if (componentName == null) {
                        if (DEBUG) Slog.v(TAG, "bindWallpaperComponentLocked: still using default");
                        // Still using default wallpaper.
                        return true;
                    }
                } else if (wallpaper.wallpaperComponent.equals(componentName)) {
                    // Changing to same wallpaper.
                    if (DEBUG) Slog.v(TAG, "same wallpaper");
                    return true;
                }
            }
        }
        
        try {
            if (componentName == null) {
                String defaultComponent = 
                    mContext.getString(com.android.internal.R.string.default_wallpaper_component);
                if (defaultComponent != null) {
                    // See if there is a default wallpaper component specified
                    componentName = ComponentName.unflattenFromString(defaultComponent);
                    if (DEBUG) Slog.v(TAG, "Use default component wallpaper:" + componentName);
                }
                if (componentName == null) {
                    // Fall back to static image wallpaper
                    componentName = wallpaper.imageWallpaperComponent;
                    //clearWallpaperComponentLocked();
                    //return;
                    if (DEBUG) Slog.v(TAG, "Using image wallpaper");
                }
            }
            ServiceInfo si = mContext.getPackageManager().getServiceInfo(componentName,
                    PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS);
            if (!android.Manifest.permission.BIND_WALLPAPER.equals(si.permission)) {
                String msg = "Selected service does not require "
                        + android.Manifest.permission.BIND_WALLPAPER
                        + ": " + componentName;
                if (fromUser) {
                    throw new SecurityException(msg);
                }
                Slog.w(TAG, msg);
                return false;
            }
            
            WallpaperInfo wi = null;
            
            Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
            if (componentName != null && !componentName.equals(wallpaper.imageWallpaperComponent)) {
                // Make sure the selected service is actually a wallpaper service.
                List<ResolveInfo> ris = mContext.getPackageManager()
                        .queryIntentServices(intent, PackageManager.GET_META_DATA);
                for (int i=0; i<ris.size(); i++) {
                    ServiceInfo rsi = ris.get(i).serviceInfo;
                    if (rsi.name.equals(si.name) &&
                            rsi.packageName.equals(si.packageName)) {
                        try {
                            wi = new WallpaperInfo(mContext, ris.get(i));
                        } catch (XmlPullParserException e) {
                            if (fromUser) {
                                throw new IllegalArgumentException(e);
                            }
                            Slog.w(TAG, e);
                            return false;
                        } catch (IOException e) {
                            if (fromUser) {
                                throw new IllegalArgumentException(e);
                            }
                            Slog.w(TAG, e);
                            return false;
                        }
                        break;
                    }
                }
                if (wi == null) {
                    String msg = "Selected service is not a wallpaper: "
                            + componentName;
                    if (fromUser) {
                        throw new SecurityException(msg);
                    }
                    Slog.w(TAG, msg);
                    return false;
                }
            }
            
            // Bind the service!
            if (DEBUG) Slog.v(TAG, "Binding to:" + componentName);
            WallpaperConnection newConn = new WallpaperConnection(wi, wallpaper);
            intent.setComponent(componentName);
            int serviceUserId = wallpaper.userId;
            // Because the image wallpaper is running in the system ui
            if (componentName.equals(wallpaper.imageWallpaperComponent)) {
                serviceUserId = 0;
            }
            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.wallpaper_binding_label);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                    mContext, 0,
                    Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
                            mContext.getText(com.android.internal.R.string.chooser_wallpaper)),
                            0));
            if (!mContext.bindService(intent, newConn, Context.BIND_AUTO_CREATE, serviceUserId)) {
                String msg = "Unable to bind service: "
                        + componentName;
                if (fromUser) {
                    throw new IllegalArgumentException(msg);
                }
                Slog.w(TAG, msg);
                return false;
            }
            if (wallpaper.userId == mCurrentUserId && mLastWallpaper != null) {
                detachWallpaperLocked(mLastWallpaper);
            }
            wallpaper.wallpaperComponent = componentName;
            wallpaper.connection = newConn;
            wallpaper.lastDiedTime = SystemClock.uptimeMillis();
            try {
                if (wallpaper.userId == mCurrentUserId) {
                    if (DEBUG)
                        Slog.v(TAG, "Adding window token: " + newConn.mToken);
                    mIWindowManager.addWindowToken(newConn.mToken,
                            WindowManager.LayoutParams.TYPE_WALLPAPER);
                    mLastWallpaper = wallpaper;
                }
            } catch (RemoteException e) {
            }
        } catch (PackageManager.NameNotFoundException e) {
            String msg = "Unknown component " + componentName;
            if (fromUser) {
                throw new IllegalArgumentException(msg);
            }
            Slog.w(TAG, msg);
            return false;
        }
        return true;
    }

    void detachWallpaperLocked(WallpaperData wallpaper) {
        if (wallpaper.connection != null) {
            if (wallpaper.connection.mEngine != null) {
                try {
                    wallpaper.connection.mEngine.destroy();
                } catch (RemoteException e) {
                }
            }
            mContext.unbindService(wallpaper.connection);
            try {
                if (DEBUG)
                    Slog.v(TAG, "Removing window token: " + wallpaper.connection.mToken);
                mIWindowManager.removeWindowToken(wallpaper.connection.mToken);
            } catch (RemoteException e) {
            }
            wallpaper.connection.mService = null;
            wallpaper.connection.mEngine = null;
            wallpaper.connection = null;
        }
    }

    void clearWallpaperComponentLocked(WallpaperData wallpaper) {
        wallpaper.wallpaperComponent = null;
        detachWallpaperLocked(wallpaper);
    }

    void attachServiceLocked(WallpaperConnection conn, WallpaperData wallpaper) {
        try {
            conn.mService.attach(conn, conn.mToken,
                    WindowManager.LayoutParams.TYPE_WALLPAPER, false,
                    wallpaper.width, wallpaper.height);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed attaching wallpaper; clearing", e);
            if (!wallpaper.wallpaperUpdating) {
                bindWallpaperComponentLocked(null, false, false, wallpaper);
            }
        }
    }

    private void notifyCallbacksLocked(WallpaperData wallpaper) {
        final int n = wallpaper.callbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                wallpaper.callbacks.getBroadcastItem(i).onWallpaperChanged();
            } catch (RemoteException e) {

                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        wallpaper.callbacks.finishBroadcast();
        final Intent intent = new Intent(Intent.ACTION_WALLPAPER_CHANGED);
        mContext.sendBroadcast(intent);
    }

    private void checkPermission(String permission) {
        if (PackageManager.PERMISSION_GRANTED!= mContext.checkCallingOrSelfPermission(permission)) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private static JournaledFile makeJournaledFile(int userId) {
        final String base = getWallpaperDir(userId) + "/" + WALLPAPER_INFO;
        return new JournaledFile(new File(base), new File(base + ".tmp"));
    }

    private void saveSettingsLocked(WallpaperData wallpaper) {
        JournaledFile journal = makeJournaledFile(wallpaper.userId);
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(journal.chooseForWrite(), false);
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, "utf-8");
            out.startDocument(null, true);

            out.startTag(null, "wp");
            out.attribute(null, "width", Integer.toString(wallpaper.width));
            out.attribute(null, "height", Integer.toString(wallpaper.height));
            out.attribute(null, "name", wallpaper.name);
            if (wallpaper.wallpaperComponent != null
                    && !wallpaper.wallpaperComponent.equals(wallpaper.imageWallpaperComponent)) {
                out.attribute(null, "component",
                        wallpaper.wallpaperComponent.flattenToShortString());
            }
            out.endTag(null, "wp");

            out.endDocument();
            stream.close();
            journal.commit();
        } catch (IOException e) {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
                // Ignore
            }
            journal.rollback();
        }
    }

    private void migrateFromOld() {
        File oldWallpaper = new File(WallpaperBackupHelper.WALLPAPER_IMAGE_KEY);
        File oldInfo = new File(WallpaperBackupHelper.WALLPAPER_INFO_KEY);
        if (oldWallpaper.exists()) {
            File newWallpaper = new File(getWallpaperDir(0), WALLPAPER);
            oldWallpaper.renameTo(newWallpaper);
        }
        if (oldInfo.exists()) {
            File newInfo = new File(getWallpaperDir(0), WALLPAPER_INFO);
            oldInfo.renameTo(newInfo);
        }
    }

    private void loadSettingsLocked(int userId) {
        if (DEBUG) Slog.v(TAG, "loadSettingsLocked");
        
        JournaledFile journal = makeJournaledFile(userId);
        FileInputStream stream = null;
        File file = journal.chooseForRead();
        if (!file.exists()) {
            // This should only happen one time, when upgrading from a legacy system
            migrateFromOld();
        }
        WallpaperData wallpaper = mWallpaperMap.get(userId);
        if (wallpaper == null) {
            wallpaper = new WallpaperData(userId);
            mWallpaperMap.put(userId, wallpaper);
        }
        boolean success = false;
        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type;
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("wp".equals(tag)) {
                        wallpaper.width = Integer.parseInt(parser.getAttributeValue(null, "width"));
                        wallpaper.height = Integer.parseInt(parser
                                .getAttributeValue(null, "height"));
                        wallpaper.name = parser.getAttributeValue(null, "name");
                        String comp = parser.getAttributeValue(null, "component");
                        wallpaper.nextWallpaperComponent = comp != null
                                ? ComponentName.unflattenFromString(comp)
                                : null;
                        if (wallpaper.nextWallpaperComponent == null
                                || "android".equals(wallpaper.nextWallpaperComponent
                                        .getPackageName())) {
                            wallpaper.nextWallpaperComponent = wallpaper.imageWallpaperComponent;
                        }
                          
                        if (DEBUG) {
                            Slog.v(TAG, "mWidth:" + wallpaper.width);
                            Slog.v(TAG, "mHeight:" + wallpaper.height);
                            Slog.v(TAG, "mName:" + wallpaper.name);
                            Slog.v(TAG, "mNextWallpaperComponent:"
                                    + wallpaper.nextWallpaperComponent);
                        }
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
            success = true;
        } catch (NullPointerException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (IOException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        if (!success) {
            wallpaper.width = -1;
            wallpaper.height = -1;
            wallpaper.name = "";
        }

        // We always want to have some reasonable width hint.
        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        int baseSize = d.getMaximumSizeDimension();
        if (wallpaper.width < baseSize) {
            wallpaper.width = baseSize;
        }
        if (wallpaper.height < baseSize) {
            wallpaper.height = baseSize;
        }
    }

    // Called by SystemBackupAgent after files are restored to disk.
    void settingsRestored() {
        // TODO: If necessary, make it work for secondary users as well. This currently assumes
        // restores only to the primary user
        if (DEBUG) Slog.v(TAG, "settingsRestored");
        WallpaperData wallpaper = null;
        boolean success = false;
        synchronized (mLock) {
            loadSettingsLocked(0);
            wallpaper = mWallpaperMap.get(0);
            if (wallpaper.nextWallpaperComponent != null
                    && !wallpaper.nextWallpaperComponent.equals(wallpaper.imageWallpaperComponent)) {
                if (!bindWallpaperComponentLocked(wallpaper.nextWallpaperComponent, false, false,
                        wallpaper)) {
                    // No such live wallpaper or other failure; fall back to the default
                    // live wallpaper (since the profile being restored indicated that the
                    // user had selected a live rather than static one).
                    bindWallpaperComponentLocked(null, false, false, wallpaper);
                }
                success = true;
            } else {
                // If there's a wallpaper name, we use that.  If that can't be loaded, then we
                // use the default.
                if ("".equals(wallpaper.name)) {
                    if (DEBUG) Slog.v(TAG, "settingsRestored: name is empty");
                    success = true;
                } else {
                    if (DEBUG) Slog.v(TAG, "settingsRestored: attempting to restore named resource");
                    success = restoreNamedResourceLocked(wallpaper);
                }
                if (DEBUG) Slog.v(TAG, "settingsRestored: success=" + success);
                if (success) {
                    bindWallpaperComponentLocked(wallpaper.nextWallpaperComponent, false, false,
                            wallpaper);
                }
            }
        }

        if (!success) {
            Slog.e(TAG, "Failed to restore wallpaper: '" + wallpaper.name + "'");
            wallpaper.name = "";
            getWallpaperDir(0).delete();
        }

        synchronized (mLock) {
            saveSettingsLocked(wallpaper);
        }
    }

    boolean restoreNamedResourceLocked(WallpaperData wallpaper) {
        if (wallpaper.name.length() > 4 && "res:".equals(wallpaper.name.substring(0, 4))) {
            String resName = wallpaper.name.substring(4);

            String pkg = null;
            int colon = resName.indexOf(':');
            if (colon > 0) {
                pkg = resName.substring(0, colon);
            }

            String ident = null;
            int slash = resName.lastIndexOf('/');
            if (slash > 0) {
                ident = resName.substring(slash+1);
            }

            String type = null;
            if (colon > 0 && slash > 0 && (slash-colon) > 1) {
                type = resName.substring(colon+1, slash);
            }

            if (pkg != null && ident != null && type != null) {
                int resId = -1;
                InputStream res = null;
                FileOutputStream fos = null;
                try {
                    Context c = mContext.createPackageContext(pkg, Context.CONTEXT_RESTRICTED);
                    Resources r = c.getResources();
                    resId = r.getIdentifier(resName, null, null);
                    if (resId == 0) {
                        Slog.e(TAG, "couldn't resolve identifier pkg=" + pkg + " type=" + type
                                + " ident=" + ident);
                        return false;
                    }

                    res = r.openRawResource(resId);
                    if (wallpaper.wallpaperFile.exists()) {
                        wallpaper.wallpaperFile.delete();
                    }
                    fos = new FileOutputStream(wallpaper.wallpaperFile);

                    byte[] buffer = new byte[32768];
                    int amt;
                    while ((amt=res.read(buffer)) > 0) {
                        fos.write(buffer, 0, amt);
                    }
                    // mWallpaperObserver will notice the close and send the change broadcast

                    Slog.v(TAG, "Restored wallpaper: " + resName);
                    return true;
                } catch (NameNotFoundException e) {
                    Slog.e(TAG, "Package name " + pkg + " not found");
                } catch (Resources.NotFoundException e) {
                    Slog.e(TAG, "Resource not found: " + resId);
                } catch (IOException e) {
                    Slog.e(TAG, "IOException while restoring wallpaper ", e);
                } finally {
                    if (res != null) {
                        try {
                            res.close();
                        } catch (IOException ex) {}
                    }
                    if (fos != null) {
                        FileUtils.sync(fos);
                        try {
                            fos.close();
                        } catch (IOException ex) {}
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            
            pw.println("Permission Denial: can't dump wallpaper service from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (mLock) {
            pw.println("Current Wallpaper Service state:");
            for (int i = 0; i < mWallpaperMap.size(); i++) {
                WallpaperData wallpaper = mWallpaperMap.valueAt(i);
                pw.println(" User " + wallpaper.userId + ":");
                pw.print("  mWidth=");
                pw.print(wallpaper.width);
                pw.print(" mHeight=");
                pw.println(wallpaper.height);
                pw.print("  mName=");
                pw.println(wallpaper.name);
                pw.print("  mWallpaperComponent=");
                pw.println(wallpaper.wallpaperComponent);
                if (wallpaper.connection != null) {
                    WallpaperConnection conn = wallpaper.connection;
                    pw.print("  Wallpaper connection ");
                    pw.print(conn);
                    pw.println(":");
                    if (conn.mInfo != null) {
                        pw.print("    mInfo.component=");
                        pw.println(conn.mInfo.getComponent());
                    }
                    pw.print("    mToken=");
                    pw.println(conn.mToken);
                    pw.print("    mService=");
                    pw.println(conn.mService);
                    pw.print("    mEngine=");
                    pw.println(conn.mEngine);
                    pw.print("    mLastDiedTime=");
                    pw.println(wallpaper.lastDiedTime - SystemClock.uptimeMillis());
                }
            }
        }
    }
}
