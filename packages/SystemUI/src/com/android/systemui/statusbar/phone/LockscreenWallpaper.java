/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.app.ActivityManager;
import android.app.IWallpaperManager;
import android.app.IWallpaperManagerCallback;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import libcore.io.IoUtils;

import java.util.Objects;

/**
 * Manages the lockscreen wallpaper.
 */
public class LockscreenWallpaper extends IWallpaperManagerCallback.Stub implements Runnable {

    private static final String TAG = "LockscreenWallpaper";

    private final Context mContext;
    private final PhoneStatusBar mBar;
    private final IWallpaperManager mService;
    private final WallpaperManager mWallpaperManager;
    private final Handler mH;

    private boolean mCached;
    private Bitmap mCache;
    private int mCurrentUserId;
    // The user selected in the UI, or null if no user is selected or UI doesn't support selecting
    // users.
    private UserHandle mSelectedUser;

    public LockscreenWallpaper(Context ctx, PhoneStatusBar bar, Handler h) {
        mContext = ctx;
        mBar = bar;
        mH = h;
        mService = IWallpaperManager.Stub.asInterface(
                ServiceManager.getService(Context.WALLPAPER_SERVICE));
        mWallpaperManager = (WallpaperManager) ctx.getSystemService(Context.WALLPAPER_SERVICE);
        mCurrentUserId = ActivityManager.getCurrentUser();

        try {
            mService.setLockWallpaperCallback(this);
        } catch (RemoteException e) {
            Log.e(TAG, "System dead?" + e);
        }
    }

    public Bitmap getBitmap() {
        try {
            if (mCached) {
                return mCache;
            }
            if (!mService.isWallpaperSupported(mContext.getOpPackageName())) {
                mCached = true;
                mCache = null;
                return null;
            }
            // Prefer the selected user (when specified) over the current user for the FLAG_SET_LOCK
            // wallpaper.
            final int lockWallpaperUserId =
                    mSelectedUser != null ? mSelectedUser.getIdentifier() : mCurrentUserId;
            ParcelFileDescriptor fd = mService.getWallpaper(null, WallpaperManager.FLAG_SET_LOCK,
                    new Bundle(), lockWallpaperUserId);
            if (fd != null) {
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    mCache = BitmapFactory.decodeFileDescriptor(
                            fd.getFileDescriptor(), null, options);
                    mCached = true;
                    return mCache;
                } catch (OutOfMemoryError e) {
                    Log.w(TAG, "Can't decode file", e);
                    return null;
                } finally {
                    IoUtils.closeQuietly(fd);
                }
            } else {
                mCached = true;
                if (mSelectedUser != null && mSelectedUser.getIdentifier() != mCurrentUserId) {
                    // When selected user is different from the current user, show the selected
                    // user's static wallpaper.
                    mCache = mWallpaperManager.getBitmapAsUser(mSelectedUser.getIdentifier());
                } else {
                    // When there is no selected user, or it's same as the current user, show the
                    // system (possibly dynamic) wallpaper for the selected user.
                    mCache = null;
                }
                return mCache;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "System dead?" + e);
            return null;
        }
    }

    public void setCurrentUser(int user) {
        if (user != mCurrentUserId) {
            mCached = false;
            mCurrentUserId = user;
        }
    }

    public void setSelectedUser(UserHandle selectedUser) {
        if (Objects.equals(selectedUser, mSelectedUser)) {
            return;
        }
        mSelectedUser = selectedUser;

        mH.removeCallbacks(this);
        mH.post(this);
    }

    @Override
    public void onWallpaperChanged() {
        // Called on Binder thread.
        mH.removeCallbacks(this);
        mH.post(this);
    }

    @Override
    public void run() {
        // Called in response to onWallpaperChanged on the main thread.
        mCached = false;
        mCache = null;
        getBitmap();
        mBar.updateMediaMetaData(true /* metaDataChanged */, true /* allowEnterAnimation */);
    }
}
