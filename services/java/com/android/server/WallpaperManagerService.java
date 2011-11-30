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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.IWallpaperEngine;
import android.service.wallpaper.IWallpaperService;
import android.service.wallpaper.WallpaperService;
import android.util.Slog;
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

class WallpaperManagerService extends IWallpaperManager.Stub {
    static final String TAG = "WallpaperService";
    static final boolean DEBUG = false;

    final Object mLock = new Object[0];

    /**
     * Minimum time between crashes of a wallpaper service for us to consider
     * restarting it vs. just reverting to the static wallpaper.
     */
    static final long MIN_WALLPAPER_CRASH_TIME = 10000;
    
    static final File WALLPAPER_DIR = new File(
            "/data/data/com.android.settings/files");
    static final String WALLPAPER = "wallpaper";
    static final File WALLPAPER_FILE = new File(WALLPAPER_DIR, WALLPAPER);

    /**
     * List of callbacks registered they should each be notified
     * when the wallpaper is changed.
     */
    private final RemoteCallbackList<IWallpaperManagerCallback> mCallbacks
            = new RemoteCallbackList<IWallpaperManagerCallback>();

    /**
     * Observes the wallpaper for changes and notifies all IWallpaperServiceCallbacks
     * that the wallpaper has changed. The CREATE is triggered when there is no
     * wallpaper set and is created for the first time. The CLOSE_WRITE is triggered
     * everytime the wallpaper is changed.
     */
    private final FileObserver mWallpaperObserver = new FileObserver(
            WALLPAPER_DIR.getAbsolutePath(), CLOSE_WRITE | DELETE | DELETE_SELF) {
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

                        File changedFile = new File(WALLPAPER_DIR, path);
                        if (WALLPAPER_FILE.equals(changedFile)) {
                            notifyCallbacksLocked();
                            if (mWallpaperComponent == null || event != CLOSE_WRITE
                                    || mImageWallpaperPending) {
                                if (event == CLOSE_WRITE) {
                                    mImageWallpaperPending = false;
                                }
                                bindWallpaperComponentLocked(mImageWallpaperComponent,
                                        true, false);
                                saveSettingsLocked();
                            }
                        }
                    }
                }
            };
    
    final Context mContext;
    final IWindowManager mIWindowManager;
    final MyPackageMonitor mMonitor;

    int mWidth = -1;
    int mHeight = -1;

    /**
     * Client is currently writing a new image wallpaper.
     */
    boolean mImageWallpaperPending;

    /**
     * Resource name if using a picture from the wallpaper gallery
     */
    String mName = "";
    
    /**
     * The component name of the currently set live wallpaper.
     */
    ComponentName mWallpaperComponent;
    
    /**
     * The component name of the wallpaper that should be set next.
     */
    ComponentName mNextWallpaperComponent;
    
    /**
     * Name of the component used to display bitmap wallpapers from either the gallery or
     * built-in wallpapers.
     */
    ComponentName mImageWallpaperComponent = new ComponentName("com.android.systemui",
            "com.android.systemui.ImageWallpaper");
    
    WallpaperConnection mWallpaperConnection;
    long mLastDiedTime;
    boolean mWallpaperUpdating;
    
    class WallpaperConnection extends IWallpaperConnection.Stub
            implements ServiceConnection {
        final WallpaperInfo mInfo;
        final Binder mToken = new Binder();
        IWallpaperService mService;
        IWallpaperEngine mEngine;

        public WallpaperConnection(WallpaperInfo info) {
            mInfo = info;
        }
        
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                if (mWallpaperConnection == this) {
                    mLastDiedTime = SystemClock.uptimeMillis();
                    mService = IWallpaperService.Stub.asInterface(service);
                    attachServiceLocked(this);
                    // XXX should probably do saveSettingsLocked() later
                    // when we have an engine, but I'm not sure about
                    // locking there and anyway we always need to be able to
                    // recover if there is something wrong.
                    saveSettingsLocked();
                }
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mService = null;
                mEngine = null;
                if (mWallpaperConnection == this) {
                    Slog.w(TAG, "Wallpaper service gone: " + mWallpaperComponent);
                    if (!mWallpaperUpdating && (mLastDiedTime+MIN_WALLPAPER_CRASH_TIME)
                                > SystemClock.uptimeMillis()) {
                        Slog.w(TAG, "Reverting to built-in wallpaper!");
                        clearWallpaperLocked(true);
                    }
                }
            }
        }
        
        public void attachEngine(IWallpaperEngine engine) {
            mEngine = engine;
        }
        
        public ParcelFileDescriptor setWallpaper(String name) {
            synchronized (mLock) {
                if (mWallpaperConnection == this) {
                    return updateWallpaperBitmapLocked(name);
                }
                return null;
            }
        }
    }
    
    class MyPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            synchronized (mLock) {
                if (mWallpaperComponent != null &&
                        mWallpaperComponent.getPackageName().equals(packageName)) {
                    mWallpaperUpdating = false;
                    ComponentName comp = mWallpaperComponent;
                    clearWallpaperComponentLocked();
                    if (!bindWallpaperComponentLocked(comp, false, false)) {
                        Slog.w(TAG, "Wallpaper no longer available; reverting to default");
                        clearWallpaperLocked(false);
                    }
                }
            }
        }

        @Override
        public void onPackageModified(String packageName) {
            synchronized (mLock) {
                if (mWallpaperComponent == null ||
                        !mWallpaperComponent.getPackageName().equals(packageName)) {
                    return;
                }
            }
            doPackagesChanged(true);
        }

        @Override
        public void onPackageUpdateStarted(String packageName, int uid) {
            synchronized (mLock) {
                if (mWallpaperComponent != null &&
                        mWallpaperComponent.getPackageName().equals(packageName)) {
                    mWallpaperUpdating = true;
                }
            }
        }

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            return doPackagesChanged(doit);
        }

        @Override
        public void onSomePackagesChanged() {
            doPackagesChanged(true);
        }
        
        boolean doPackagesChanged(boolean doit) {
            boolean changed = false;
            synchronized (mLock) {
                if (mWallpaperComponent != null) {
                    int change = isPackageDisappearing(mWallpaperComponent.getPackageName());
                    if (change == PACKAGE_PERMANENT_CHANGE
                            || change == PACKAGE_TEMPORARY_CHANGE) {
                        changed = true;
                        if (doit) {
                            Slog.w(TAG, "Wallpaper uninstalled, removing: " + mWallpaperComponent);
                            clearWallpaperLocked(false);
                        }
                    }
                }
                if (mNextWallpaperComponent != null) {
                    int change = isPackageDisappearing(mNextWallpaperComponent.getPackageName());
                    if (change == PACKAGE_PERMANENT_CHANGE
                            || change == PACKAGE_TEMPORARY_CHANGE) {
                        mNextWallpaperComponent = null;
                    }
                }
                if (mWallpaperComponent != null
                        && isPackageModified(mWallpaperComponent.getPackageName())) {
                    try {
                        mContext.getPackageManager().getServiceInfo(
                                mWallpaperComponent, 0);
                    } catch (NameNotFoundException e) {
                        Slog.w(TAG, "Wallpaper component gone, removing: " + mWallpaperComponent);
                        clearWallpaperLocked(false);
                    }
                }
                if (mNextWallpaperComponent != null
                        && isPackageModified(mNextWallpaperComponent.getPackageName())) {
                    try {
                        mContext.getPackageManager().getServiceInfo(
                                mNextWallpaperComponent, 0);
                    } catch (NameNotFoundException e) {
                        mNextWallpaperComponent = null;
                    }
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
        mMonitor.register(context, true);
        WALLPAPER_DIR.mkdirs();
        loadSettingsLocked();
        mWallpaperObserver.startWatching();
    }
    
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        mWallpaperObserver.stopWatching();
    }
    
    public void systemReady() {
        if (DEBUG) Slog.v(TAG, "systemReady");
        synchronized (mLock) {
            RuntimeException e = null;
            try {
                if (bindWallpaperComponentLocked(mNextWallpaperComponent, false, false)) {
                    return;
                }
            } catch (RuntimeException e1) {
                e = e1;
            }
            Slog.w(TAG, "Failure starting previous wallpaper", e);
            clearWallpaperLocked(false);
        }
    }
    
    public void clearWallpaper() {
        if (DEBUG) Slog.v(TAG, "clearWallpaper");
        synchronized (mLock) {
            clearWallpaperLocked(false);
        }
    }

    public void clearWallpaperLocked(boolean defaultFailed) {
        File f = WALLPAPER_FILE;
        if (f.exists()) {
            f.delete();
        }
        final long ident = Binder.clearCallingIdentity();
        RuntimeException e = null;
        try {
            mImageWallpaperPending = false;
            if (bindWallpaperComponentLocked(defaultFailed
                    ? mImageWallpaperComponent : null, true, false)) {
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
        clearWallpaperComponentLocked();
    }

    public void setDimensionHints(int width, int height) throws RemoteException {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }

        synchronized (mLock) {
            if (width != mWidth || height != mHeight) {
                mWidth = width;
                mHeight = height;
                saveSettingsLocked();
                if (mWallpaperConnection != null) {
                    if (mWallpaperConnection.mEngine != null) {
                        try {
                            mWallpaperConnection.mEngine.setDesiredSize(
                                    width, height);
                        } catch (RemoteException e) {
                        }
                        notifyCallbacksLocked();
                    }
                }
            }
        }
    }

    public int getWidthHint() throws RemoteException {
        synchronized (mLock) {
            return mWidth;
        }
    }

    public int getHeightHint() throws RemoteException {
        synchronized (mLock) {
            return mHeight;
        }
    }

    public ParcelFileDescriptor getWallpaper(IWallpaperManagerCallback cb,
            Bundle outParams) {
        synchronized (mLock) {
            try {
                if (outParams != null) {
                    outParams.putInt("width", mWidth);
                    outParams.putInt("height", mHeight);
                }
                mCallbacks.register(cb);
                File f = WALLPAPER_FILE;
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
        synchronized (mLock) {
            if (mWallpaperConnection != null) {
                return mWallpaperConnection.mInfo;
            }
            return null;
        }
    }
    
    public ParcelFileDescriptor setWallpaper(String name) {
        if (DEBUG) Slog.v(TAG, "setWallpaper");
        
        checkPermission(android.Manifest.permission.SET_WALLPAPER);
        synchronized (mLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                ParcelFileDescriptor pfd = updateWallpaperBitmapLocked(name);
                if (pfd != null) {
                    mImageWallpaperPending = true;
                }
                return pfd;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    ParcelFileDescriptor updateWallpaperBitmapLocked(String name) {
        if (name == null) name = "";
        try {
            if (!WALLPAPER_DIR.exists()) {
                WALLPAPER_DIR.mkdir();
                FileUtils.setPermissions(
                        WALLPAPER_DIR.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(WALLPAPER_FILE,
                    MODE_CREATE|MODE_READ_WRITE);
            mName = name;
            return fd;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Error setting wallpaper", e);
        }
        return null;
    }

    public void setWallpaperComponent(ComponentName name) {
        if (DEBUG) Slog.v(TAG, "setWallpaperComponent name=" + name);
        checkPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT);
        synchronized (mLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mImageWallpaperPending = false;
                bindWallpaperComponentLocked(name, false, true);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    boolean bindWallpaperComponentLocked(ComponentName componentName, boolean force, boolean fromUser) {
        if (DEBUG) Slog.v(TAG, "bindWallpaperComponentLocked: componentName=" + componentName);
        
        // Has the component changed?
        if (!force) {
            if (mWallpaperConnection != null) {
                if (mWallpaperComponent == null) {
                    if (componentName == null) {
                        if (DEBUG) Slog.v(TAG, "bindWallpaperComponentLocked: still using default");
                        // Still using default wallpaper.
                        return true;
                    }
                } else if (mWallpaperComponent.equals(componentName)) {
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
                    componentName = mImageWallpaperComponent;
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
            if (componentName != null && !componentName.equals(mImageWallpaperComponent)) {
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
            WallpaperConnection newConn = new WallpaperConnection(wi);
            intent.setComponent(componentName);
            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.wallpaper_binding_label);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                    mContext, 0,
                    Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
                            mContext.getText(com.android.internal.R.string.chooser_wallpaper)),
                            0));
            if (!mContext.bindService(intent, newConn,
                    Context.BIND_AUTO_CREATE)) {
                String msg = "Unable to bind service: "
                        + componentName;
                if (fromUser) {
                    throw new IllegalArgumentException(msg);
                }
                Slog.w(TAG, msg);
                return false;
            }
            
            clearWallpaperComponentLocked();
            mWallpaperComponent = componentName;
            mWallpaperConnection = newConn;
            mLastDiedTime = SystemClock.uptimeMillis();
            try {
                if (DEBUG) Slog.v(TAG, "Adding window token: " + newConn.mToken);
                mIWindowManager.addWindowToken(newConn.mToken,
                        WindowManager.LayoutParams.TYPE_WALLPAPER);
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
    
    void clearWallpaperComponentLocked() {
        mWallpaperComponent = null;
        if (mWallpaperConnection != null) {
            if (mWallpaperConnection.mEngine != null) {
                try {
                    mWallpaperConnection.mEngine.destroy();
                } catch (RemoteException e) {
                }
            }
            mContext.unbindService(mWallpaperConnection);
            try {
                if (DEBUG) Slog.v(TAG, "Removing window token: "
                        + mWallpaperConnection.mToken);
                mIWindowManager.removeWindowToken(mWallpaperConnection.mToken);
            } catch (RemoteException e) {
            }
            mWallpaperConnection.mService = null;
            mWallpaperConnection.mEngine = null;
            mWallpaperConnection = null;
        }
    }
    
    void attachServiceLocked(WallpaperConnection conn) {
        try {
            conn.mService.attach(conn, conn.mToken,
                    WindowManager.LayoutParams.TYPE_WALLPAPER, false,
                    mWidth, mHeight);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed attaching wallpaper; clearing", e);
            if (!mWallpaperUpdating) {
                bindWallpaperComponentLocked(null, false, false);
            }
        }
    }
    
    private void notifyCallbacksLocked() {
        final int n = mCallbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mCallbacks.getBroadcastItem(i).onWallpaperChanged();
            } catch (RemoteException e) {

                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        mCallbacks.finishBroadcast();
        final Intent intent = new Intent(Intent.ACTION_WALLPAPER_CHANGED);
        mContext.sendBroadcast(intent);
    }

    private void checkPermission(String permission) {
        if (PackageManager.PERMISSION_GRANTED!= mContext.checkCallingOrSelfPermission(permission)) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private static JournaledFile makeJournaledFile() {
        final String base = "/data/system/wallpaper_info.xml";
        return new JournaledFile(new File(base), new File(base + ".tmp"));
    }

    private void saveSettingsLocked() {
        JournaledFile journal = makeJournaledFile();
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(journal.chooseForWrite(), false);
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, "utf-8");
            out.startDocument(null, true);

            out.startTag(null, "wp");
            out.attribute(null, "width", Integer.toString(mWidth));
            out.attribute(null, "height", Integer.toString(mHeight));
            out.attribute(null, "name", mName);
            if (mWallpaperComponent != null &&
                    !mWallpaperComponent.equals(mImageWallpaperComponent)) {
                out.attribute(null, "component",
                        mWallpaperComponent.flattenToShortString());
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

    private void loadSettingsLocked() {
        if (DEBUG) Slog.v(TAG, "loadSettingsLocked");
        
        JournaledFile journal = makeJournaledFile();
        FileInputStream stream = null;
        File file = journal.chooseForRead();
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
                        mWidth = Integer.parseInt(parser.getAttributeValue(null, "width"));
                        mHeight = Integer.parseInt(parser.getAttributeValue(null, "height"));
                        mName = parser.getAttributeValue(null, "name");
                        String comp = parser.getAttributeValue(null, "component");
                        mNextWallpaperComponent = comp != null
                                ? ComponentName.unflattenFromString(comp)
                                : null;
                        if (mNextWallpaperComponent == null ||
                                "android".equals(mNextWallpaperComponent.getPackageName())) {
                            mNextWallpaperComponent = mImageWallpaperComponent;
                        }
                          
                        if (DEBUG) {
                            Slog.v(TAG, "mWidth:" + mWidth);
                            Slog.v(TAG, "mHeight:" + mHeight);
                            Slog.v(TAG, "mName:" + mName);
                            Slog.v(TAG, "mNextWallpaperComponent:" + mNextWallpaperComponent);
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
            mWidth = -1;
            mHeight = -1;
            mName = "";
        }

        // We always want to have some reasonable width hint.
        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        int baseSize = d.getMaximumSizeDimension();
        if (mWidth < baseSize) {
            mWidth = baseSize;
        }
        if (mHeight < baseSize) {
            mHeight = baseSize;
        }
    }

    // Called by SystemBackupAgent after files are restored to disk.
    void settingsRestored() {
        if (DEBUG) Slog.v(TAG, "settingsRestored");

        boolean success = false;
        synchronized (mLock) {
            loadSettingsLocked();
            if (mNextWallpaperComponent != null && 
                    !mNextWallpaperComponent.equals(mImageWallpaperComponent)) {
                if (!bindWallpaperComponentLocked(mNextWallpaperComponent, false, false)) {
                    // No such live wallpaper or other failure; fall back to the default
                    // live wallpaper (since the profile being restored indicated that the
                    // user had selected a live rather than static one).
                    bindWallpaperComponentLocked(null, false, false);
                }
                success = true;
            } else {
                // If there's a wallpaper name, we use that.  If that can't be loaded, then we
                // use the default.
                if ("".equals(mName)) {
                    if (DEBUG) Slog.v(TAG, "settingsRestored: name is empty");
                    success = true;
                } else {
                    if (DEBUG) Slog.v(TAG, "settingsRestored: attempting to restore named resource");
                    success = restoreNamedResourceLocked();
                }
                if (DEBUG) Slog.v(TAG, "settingsRestored: success=" + success);
                if (success) {
                    bindWallpaperComponentLocked(mNextWallpaperComponent, false, false);
                }
            }
        }

        if (!success) {
            Slog.e(TAG, "Failed to restore wallpaper: '" + mName + "'");
            mName = "";
            WALLPAPER_FILE.delete();
        }

        synchronized (mLock) {
            saveSettingsLocked();
        }
    }

    boolean restoreNamedResourceLocked() {
        if (mName.length() > 4 && "res:".equals(mName.substring(0, 4))) {
            String resName = mName.substring(4);

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
                    if (WALLPAPER_FILE.exists()) {
                        WALLPAPER_FILE.delete();
                    }
                    fos = new FileOutputStream(WALLPAPER_FILE);

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
            pw.print("  mWidth="); pw.print(mWidth);
                    pw.print(" mHeight="); pw.println(mHeight);
            pw.print("  mName="); pw.println(mName);
            pw.print("  mWallpaperComponent="); pw.println(mWallpaperComponent);
            if (mWallpaperConnection != null) {
                WallpaperConnection conn = mWallpaperConnection;
                pw.print("  Wallpaper connection ");
                        pw.print(conn); pw.println(":");
                pw.print("    mInfo.component="); pw.println(conn.mInfo.getComponent());
                pw.print("    mToken="); pw.println(conn.mToken);
                pw.print("    mService="); pw.println(conn.mService);
                pw.print("    mEngine="); pw.println(conn.mEngine);
                pw.print("    mLastDiedTime=");
                        pw.println(mLastDiedTime - SystemClock.uptimeMillis());
            }
        }
    }
}
