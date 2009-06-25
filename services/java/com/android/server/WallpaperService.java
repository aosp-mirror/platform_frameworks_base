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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.FileObserver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.util.Config;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

class WallpaperService extends IWallpaperService.Stub {
    private static final String TAG = WallpaperService.class.getSimpleName();     

    private static final File WALLPAPER_DIR = new File(
            "/data/data/com.android.settings/files");
    private static final String WALLPAPER = "wallpaper";
    private static final File WALLPAPER_FILE = new File(WALLPAPER_DIR, WALLPAPER);

    private static final String PREFERENCES = "wallpaper-hints";

    private static final String HINT_WIDTH = "hintWidth";
    private static final String HINT_HEIGHT = "hintHeight";

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
            WALLPAPER_DIR.getAbsolutePath(), CREATE | CLOSE_WRITE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null) {
                        return;
                    }

                    File changedFile = new File(WALLPAPER_DIR, path);
                    if (WALLPAPER_FILE.equals(changedFile)) {
                        notifyCallbacks();
                    }
                }
            };
    
    private final Context mContext;

    private int mWidth = -1;
    private int mHeight = -1;

    public WallpaperService(Context context) {
        if (Config.LOGD) Log.d(TAG, "WallpaperService startup");
        mContext = context;
        createFilesDir();
        mWallpaperObserver.startWatching();

        SharedPreferences preferences = mContext.getSharedPreferences(PREFERENCES,
                    Context.MODE_PRIVATE);
        mWidth = preferences.getInt(HINT_WIDTH, -1);
        mHeight = preferences.getInt(HINT_HEIGHT, -1);
    }
    
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        mWallpaperObserver.stopWatching();
    }
    
    public void clearWallpaper() {
        File f = WALLPAPER_FILE;
        if (f.exists()) {
            f.delete();
        }
    }

    public void setDimensionHints(int width, int height) throws RemoteException {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be > 0");
        }

        if (width != mWidth || height != mHeight) {
            mWidth = width;
            mHeight = height;

            SharedPreferences preferences = mContext.getSharedPreferences(PREFERENCES,
                    Context.MODE_PRIVATE);

            final SharedPreferences.Editor editor = preferences.edit();
            editor.putInt(HINT_WIDTH, width);
            editor.putInt(HINT_HEIGHT, height);
            editor.commit();
        }
    }

    public int getWidthHint() throws RemoteException {
        return mWidth;
    }

    public int getHeightHint() throws RemoteException {
        return mHeight;
    }

    public ParcelFileDescriptor getWallpaper(IWallpaperServiceCallback cb) {
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

    public ParcelFileDescriptor setWallpaper() {
        checkPermission(android.Manifest.permission.SET_WALLPAPER);
        try {
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(WALLPAPER_FILE,
                    MODE_CREATE|MODE_READ_WRITE);

            // changing the wallpaper means we'll need to back up the new one
            long origId = Binder.clearCallingIdentity();
            BackupManager bm = new BackupManager(mContext);
            bm.dataChanged();
            Binder.restoreCallingIdentity(origId);

            return fd;
        } catch (FileNotFoundException e) {
            if (Config.LOGD) Log.d(TAG, "Error setting wallpaper", e);
        }
        return null;
    }

    private void createFilesDir() {
        if (!WALLPAPER_DIR.exists()) {
            WALLPAPER_DIR.mkdirs();
        }
    }

    private void notifyCallbacks() {
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
        if (PackageManager.PERMISSION_GRANTED != mContext.checkCallingOrSelfPermission(permission)) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }
}
