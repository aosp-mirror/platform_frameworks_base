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

import android.app.IWallpaperService;
import android.app.IWallpaperServiceCallback;
import android.backup.BackupManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Binder;
import android.os.RemoteException;
import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.util.Config;
import android.util.Log;
import android.util.Xml;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import com.android.internal.util.FastXmlSerializer;

class WallpaperService extends IWallpaperService.Stub {
    private static final String TAG = "WallpaperService";

    private Object mLock = new Object();

    private static final File WALLPAPER_DIR = new File(
            "/data/data/com.android.settings/files");
    private static final String WALLPAPER = "wallpaper";
    private static final File WALLPAPER_FILE = new File(WALLPAPER_DIR, WALLPAPER);

    /**
     * List of callbacks registered they should each be notified
     * when the wallpaper is changed.
     */
    private final RemoteCallbackList<IWallpaperServiceCallback> mCallbacks
            = new RemoteCallbackList<IWallpaperServiceCallback>();

    /**
     * Observes the wallpaper for changes and notifies all IWallpaperServiceCallbacks
     * that the wallpaper has changed. The CREATE is triggered when there is no
     * wallpaper set and is created for the first time. The CLOSE_WRITE is triggered
     * everytime the wallpaper is changed.
     */
    private final FileObserver mWallpaperObserver = new FileObserver(
            WALLPAPER_DIR.getAbsolutePath(), CREATE | CLOSE_WRITE | DELETE | DELETE_SELF) {
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
                        }
                    }
                }
            };
    
    private final Context mContext;

    private int mWidth = -1;
    private int mHeight = -1;
    private String mName = "";

    public WallpaperService(Context context) {
        if (Config.LOGD) Log.d(TAG, "WallpaperService startup");
        mContext = context;
        WALLPAPER_DIR.mkdirs();
        loadSettingsLocked();
        mWallpaperObserver.startWatching();
    }
    
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        mWallpaperObserver.stopWatching();
    }
    
    public void clearWallpaper() {
        synchronized (mLock) {
            File f = WALLPAPER_FILE;
            if (f.exists()) {
                f.delete();
            }
        }
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

    public ParcelFileDescriptor getWallpaper(IWallpaperServiceCallback cb) {
        synchronized (mLock) {
            try {
                mCallbacks.register(cb);
                File f = WALLPAPER_FILE;
                if (!f.exists()) {
                    return null;
                }
                return ParcelFileDescriptor.open(f, MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                /* Shouldn't happen as we check to see if the file exists */
                if (Config.LOGD) Log.d(TAG, "Error getting wallpaper", e);
            }
            return null;
        }
    }

    public ParcelFileDescriptor setWallpaper(String name) {
        checkPermission(android.Manifest.permission.SET_WALLPAPER);
        synchronized (mLock) {
            if (name == null) name = "";
            mName = name;
            saveSettingsLocked();
            try {
                ParcelFileDescriptor fd = ParcelFileDescriptor.open(WALLPAPER_FILE,
                        MODE_CREATE|MODE_READ_WRITE);
                return fd;
            } catch (FileNotFoundException e) {
                if (Config.LOGD) Log.d(TAG, "Error setting wallpaper", e);
            }
            return null;
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
        JournaledFile journal = makeJournaledFile();
        FileInputStream stream = null;
        File file = journal.chooseForRead();
        boolean success = false;
        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type;
            int providerIndex = 0;
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("wp".equals(tag)) {
                        mWidth = Integer.parseInt(parser.getAttributeValue(null, "width"));
                        mHeight = Integer.parseInt(parser.getAttributeValue(null, "height"));
                        mName = parser.getAttributeValue(null, "name");
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
            success = true;
        } catch (NullPointerException e) {
            Log.w(TAG, "failed parsing " + file, e);
        } catch (NumberFormatException e) {
            Log.w(TAG, "failed parsing " + file, e);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "failed parsing " + file, e);
        } catch (IOException e) {
            Log.w(TAG, "failed parsing " + file, e);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "failed parsing " + file, e);
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
    }

    void settingsRestored() {
        boolean success = false;
        synchronized (mLock) {
            loadSettingsLocked();
            // If there's a wallpaper name, we use that.  If that can't be loaded, then we
            // use the default.
            if ("".equals(mName)) {
                success = true;
            } else {
                success = restoreNamedResourceLocked();
            }
        }

        if (!success) {
            Log.e(TAG, "Failed to restore wallpaper: '" + mName + "'");
            mName = "";
            WALLPAPER_FILE.delete();
        }
        saveSettingsLocked();
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
                        Log.e(TAG, "couldn't resolve identifier pkg=" + pkg + " type=" + type
                                + " ident=" + ident);
                        return false;
                    }

                    res = r.openRawResource(resId);
                    fos = new FileOutputStream(WALLPAPER_FILE);

                    byte[] buffer = new byte[32768];
                    int amt;
                    while ((amt=res.read(buffer)) > 0) {
                        fos.write(buffer, 0, amt);
                    }
                    // mWallpaperObserver will notice the close and send the change broadcast

                    Log.d(TAG, "Restored wallpaper: " + resName);
                    return true;
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Package name " + pkg + " not found");
                } catch (Resources.NotFoundException e) {
                    Log.e(TAG, "Resource not found: " + resId);
                } catch (IOException e) {
                    Log.e(TAG, "IOException while restoring wallpaper ", e);
                } finally {
                    if (res != null) {
                        try {
                            res.close();
                        } catch (IOException ex) {}
                    }
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (IOException ex) {}
                    }
                }
            }
        }
        return false;
    }
}
