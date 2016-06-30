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

package com.android.server.wallpaper;

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IUserSwitchObserver;
import android.app.IWallpaperManager;
import android.app.IWallpaperManagerCallback;
import android.app.PendingIntent;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.backup.WallpaperBackupHelper;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.IWallpaperEngine;
import android.service.wallpaper.IWallpaperService;
import android.service.wallpaper.WallpaperService;
import android.system.ErrnoException;
import android.system.Os;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.JournaledFile;
import com.android.server.EventLogTags;
import com.android.server.SystemService;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class WallpaperManagerService extends IWallpaperManager.Stub {
    static final String TAG = "WallpaperManagerService";
    static final boolean DEBUG = false;

    public static class Lifecycle extends SystemService {
        private WallpaperManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new WallpaperManagerService(getContext());
            publishBinderService(Context.WALLPAPER_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mService.systemReady();
            } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
                mService.switchUser(UserHandle.USER_SYSTEM, null);
            }
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mService.onUnlockUser(userHandle);
        }
    }

    final Object mLock = new Object();

    /**
     * Minimum time between crashes of a wallpaper service for us to consider
     * restarting it vs. just reverting to the static wallpaper.
     */
    static final long MIN_WALLPAPER_CRASH_TIME = 10000;
    static final int MAX_WALLPAPER_COMPONENT_LOG_LENGTH = 128;
    static final String WALLPAPER = "wallpaper_orig";
    static final String WALLPAPER_CROP = "wallpaper";
    static final String WALLPAPER_LOCK_ORIG = "wallpaper_lock_orig";
    static final String WALLPAPER_LOCK_CROP = "wallpaper_lock";
    static final String WALLPAPER_INFO = "wallpaper_info.xml";

    // All the various per-user state files we need to be aware of
    static final String[] sPerUserFiles = new String[] {
        WALLPAPER, WALLPAPER_CROP,
        WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP,
        WALLPAPER_INFO
    };

    /**
     * Observes the wallpaper for changes and notifies all IWallpaperServiceCallbacks
     * that the wallpaper has changed. The CREATE is triggered when there is no
     * wallpaper set and is created for the first time. The CLOSE_WRITE is triggered
     * everytime the wallpaper is changed.
     */
    private class WallpaperObserver extends FileObserver {

        final int mUserId;
        final WallpaperData mWallpaper;
        final File mWallpaperDir;
        final File mWallpaperFile;
        final File mWallpaperLockFile;
        final File mWallpaperInfoFile;

        public WallpaperObserver(WallpaperData wallpaper) {
            super(getWallpaperDir(wallpaper.userId).getAbsolutePath(),
                    CLOSE_WRITE | MOVED_TO | DELETE | DELETE_SELF);
            mUserId = wallpaper.userId;
            mWallpaperDir = getWallpaperDir(wallpaper.userId);
            mWallpaper = wallpaper;
            mWallpaperFile = new File(mWallpaperDir, WALLPAPER);
            mWallpaperLockFile = new File(mWallpaperDir, WALLPAPER_LOCK_ORIG);
            mWallpaperInfoFile = new File(mWallpaperDir, WALLPAPER_INFO);
        }

        private WallpaperData dataForEvent(boolean sysChanged, boolean lockChanged) {
            WallpaperData wallpaper = null;
            synchronized (mLock) {
                if (lockChanged) {
                    wallpaper = mLockWallpaperMap.get(mUserId);
                }
                if (wallpaper == null) {
                    // no lock-specific wallpaper exists, or sys case, handled together
                    wallpaper = mWallpaperMap.get(mUserId);
                }
            }
            return (wallpaper != null) ? wallpaper : mWallpaper;
        }

        @Override
        public void onEvent(int event, String path) {
            if (path == null) {
                return;
            }
            final boolean moved = (event == MOVED_TO);
            final boolean written = (event == CLOSE_WRITE || moved);
            final File changedFile = new File(mWallpaperDir, path);

            // System and system+lock changes happen on the system wallpaper input file;
            // lock-only changes happen on the dedicated lock wallpaper input file
            final boolean sysWallpaperChanged = (mWallpaperFile.equals(changedFile));
            final boolean lockWallpaperChanged = (mWallpaperLockFile.equals(changedFile));
            WallpaperData wallpaper = dataForEvent(sysWallpaperChanged, lockWallpaperChanged);

            if (DEBUG) {
                Slog.v(TAG, "Wallpaper file change: evt=" + event
                        + " path=" + path
                        + " sys=" + sysWallpaperChanged
                        + " lock=" + lockWallpaperChanged
                        + " imagePending=" + wallpaper.imageWallpaperPending
                        + " whichPending=0x" + Integer.toHexString(wallpaper.whichPending)
                        + " written=" + written);
            }

            if (moved && lockWallpaperChanged) {
                // We just migrated sys -> lock to preserve imagery for an impending
                // new system-only wallpaper.  Tell keyguard about it and make sure it
                // has the right SELinux label.
                if (DEBUG) {
                    Slog.i(TAG, "Sys -> lock MOVED_TO");
                }
                SELinux.restorecon(changedFile);
                notifyLockWallpaperChanged();
                return;
            }

            synchronized (mLock) {
                if (sysWallpaperChanged || lockWallpaperChanged) {
                    notifyCallbacksLocked(wallpaper);
                    if (wallpaper.wallpaperComponent == null
                            || event != CLOSE_WRITE // includes the MOVED_TO case
                            || wallpaper.imageWallpaperPending) {
                        if (written) {
                            // The image source has finished writing the source image,
                            // so we now produce the crop rect (in the background), and
                            // only publish the new displayable (sub)image as a result
                            // of that work.
                            if (DEBUG) {
                                Slog.v(TAG, "Wallpaper written; generating crop");
                            }
                            if (moved) {
                                // This is a restore, so generate the crop using any just-restored new
                                // crop guidelines, making sure to preserve our local dimension hints.
                                // We also make sure to reapply the correct SELinux label.
                                if (DEBUG) {
                                    Slog.v(TAG, "moved-to, therefore restore; reloading metadata");
                                }
                                SELinux.restorecon(changedFile);
                                loadSettingsLocked(wallpaper.userId, true);
                            }
                            generateCrop(wallpaper);
                            if (DEBUG) {
                                Slog.v(TAG, "Crop done; invoking completion callback");
                            }
                            wallpaper.imageWallpaperPending = false;
                            if (wallpaper.setComplete != null) {
                                try {
                                    wallpaper.setComplete.onWallpaperChanged();
                                } catch (RemoteException e) {
                                    // if this fails we don't really care; the setting app may just
                                    // have crashed and that sort of thing is a fact of life.
                                }
                            }
                            if (sysWallpaperChanged) {
                                // If this was the system wallpaper, rebind...
                                bindWallpaperComponentLocked(mImageWallpaper, true,
                                        false, wallpaper, null);
                            }
                            if (lockWallpaperChanged
                                    || (wallpaper.whichPending & FLAG_LOCK) != 0) {
                                if (DEBUG) {
                                    Slog.i(TAG, "Lock-relevant wallpaper changed");
                                }
                                // either a lock-only wallpaper commit or a system+lock event.
                                // if it's system-plus-lock we need to wipe the lock bookkeeping;
                                // we're falling back to displaying the system wallpaper there.
                                if (!lockWallpaperChanged) {
                                    mLockWallpaperMap.remove(wallpaper.userId);
                                }
                                // and in any case, tell keyguard about it
                                notifyLockWallpaperChanged();
                            }
                            saveSettingsLocked(wallpaper.userId);
                        }
                    }
                }
            }
        }
    }

    void notifyLockWallpaperChanged() {
        final IWallpaperManagerCallback cb = mKeyguardListener;
        if (cb != null) {
            try {
                cb.onWallpaperChanged();
            } catch (RemoteException e) {
                // Oh well it went away; no big deal
            }
        }
    }

    /**
     * Once a new wallpaper has been written via setWallpaper(...), it needs to be cropped
     * for display.
     */
    private void generateCrop(WallpaperData wallpaper) {
        boolean success = false;

        Rect cropHint = new Rect(wallpaper.cropHint);

        if (DEBUG) {
            Slog.v(TAG, "Generating crop for new wallpaper(s): 0x"
                    + Integer.toHexString(wallpaper.whichPending)
                    + " to " + wallpaper.cropFile.getName()
                    + " crop=(" + cropHint.width() + 'x' + cropHint.height()
                    + ") dim=(" + wallpaper.width + 'x' + wallpaper.height + ')');
        }

        // Analyse the source; needed in multiple cases
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(wallpaper.wallpaperFile.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            Slog.e(TAG, "Invalid wallpaper data");
            success = false;
        } else {
            boolean needCrop = false;
            boolean needScale = false;

            // Empty crop means use the full image
            if (cropHint.isEmpty()) {
                cropHint.left = cropHint.top = 0;
                cropHint.right = options.outWidth;
                cropHint.bottom = options.outHeight;
            } else {
                // force the crop rect to lie within the measured bounds
                cropHint.offset(
                        (cropHint.right > options.outWidth ? options.outWidth - cropHint.right : 0),
                        (cropHint.bottom > options.outHeight ? options.outHeight - cropHint.bottom : 0));

                // Don't bother cropping if what we're left with is identity
                needCrop = (options.outHeight >= cropHint.height()
                        && options.outWidth >= cropHint.width());
            }

            // scale if the crop height winds up not matching the recommended metrics
            needScale = (wallpaper.height != cropHint.height());

            if (DEBUG) {
                Slog.v(TAG, "crop: w=" + cropHint.width() + " h=" + cropHint.height());
                Slog.v(TAG, "dims: w=" + wallpaper.width + " h=" + wallpaper.height);
                Slog.v(TAG, "meas: w=" + options.outWidth + " h=" + options.outHeight);
                Slog.v(TAG, "crop?=" + needCrop + " scale?=" + needScale);
            }

            if (!needCrop && !needScale) {
                // Simple case:  the nominal crop fits what we want, so we take
                // the whole thing and just copy the image file directly.
                if (DEBUG) {
                    Slog.v(TAG, "Null crop of new wallpaper; copying");
                }
                success = FileUtils.copyFile(wallpaper.wallpaperFile, wallpaper.cropFile);
                if (!success) {
                    wallpaper.cropFile.delete();
                    // TODO: fall back to default wallpaper in this case
                }
            } else {
                // Fancy case: crop and scale.  First, we decode and scale down if appropriate.
                FileOutputStream f = null;
                BufferedOutputStream bos = null;
                try {
                    BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(
                            wallpaper.wallpaperFile.getAbsolutePath(), false);

                    // This actually downsamples only by powers of two, but that's okay; we do
                    // a proper scaling blit later.  This is to minimize transient RAM use.
                    // We calculate the largest power-of-two under the actual ratio rather than
                    // just let the decode take care of it because we also want to remap where the
                    // cropHint rectangle lies in the decoded [super]rect.
                    final BitmapFactory.Options scaler;
                    final int actualScale = cropHint.height() / wallpaper.height;
                    int scale = 1;
                    while (2*scale < actualScale) {
                        scale *= 2;
                    }
                    if (scale > 1) {
                        scaler = new BitmapFactory.Options();
                        scaler.inSampleSize = scale;
                        if (DEBUG) {
                            Slog.v(TAG, "Downsampling cropped rect with scale " + scale);
                        }
                    } else {
                        scaler = null;
                    }
                    Bitmap cropped = decoder.decodeRegion(cropHint, scaler);
                    decoder.recycle();

                    if (cropped == null) {
                        Slog.e(TAG, "Could not decode new wallpaper");
                    } else {
                        // We've got the extracted crop; now we want to scale it properly to
                        // the desired rectangle.  That's a height-biased operation: make it
                        // fit the hinted height, and accept whatever width we end up with.
                        cropHint.offsetTo(0, 0);
                        cropHint.right /= scale;    // adjust by downsampling factor
                        cropHint.bottom /= scale;
                        final float heightR = ((float)wallpaper.height) / ((float)cropHint.height());
                        if (DEBUG) {
                            Slog.v(TAG, "scale " + heightR + ", extracting " + cropHint);
                        }
                        final int destWidth = (int)(cropHint.width() * heightR);
                        final Bitmap finalCrop = Bitmap.createScaledBitmap(cropped,
                                destWidth, wallpaper.height, true);
                        if (DEBUG) {
                            Slog.v(TAG, "Final extract:");
                            Slog.v(TAG, "  dims: w=" + wallpaper.width
                                    + " h=" + wallpaper.height);
                            Slog.v(TAG, "   out: w=" + finalCrop.getWidth()
                                    + " h=" + finalCrop.getHeight());
                        }

                        f = new FileOutputStream(wallpaper.cropFile);
                        bos = new BufferedOutputStream(f, 32*1024);
                        finalCrop.compress(Bitmap.CompressFormat.JPEG, 100, bos);
                        bos.flush();  // don't rely on the implicit flush-at-close when noting success
                        success = true;
                    }
                } catch (Exception e) {
                    if (DEBUG) {
                        Slog.e(TAG, "Error decoding crop", e);
                    }
                } finally {
                    IoUtils.closeQuietly(bos);
                    IoUtils.closeQuietly(f);
                }
            }
        }

        if (!success) {
            Slog.e(TAG, "Unable to apply new wallpaper");
            wallpaper.cropFile.delete();
        }

        if (wallpaper.cropFile.exists()) {
            boolean didRestorecon = SELinux.restorecon(wallpaper.cropFile.getAbsoluteFile());
            if (DEBUG) {
                Slog.v(TAG, "restorecon() of crop file returned " + didRestorecon);
            }
        }
    }

    final Context mContext;
    final IWindowManager mIWindowManager;
    final IPackageManager mIPackageManager;
    final MyPackageMonitor mMonitor;
    final AppOpsManager mAppOpsManager;
    WallpaperData mLastWallpaper;
    IWallpaperManagerCallback mKeyguardListener;
    boolean mWaitingForUnlock;

    /**
     * ID of the current wallpaper, changed every time anything sets a wallpaper.
     * This is used for external detection of wallpaper update activity.
     */
    int mWallpaperId;

    /**
     * Name of the component used to display bitmap wallpapers from either the gallery or
     * built-in wallpapers.
     */
    final ComponentName mImageWallpaper;

    final SparseArray<WallpaperData> mWallpaperMap = new SparseArray<WallpaperData>();
    final SparseArray<WallpaperData> mLockWallpaperMap = new SparseArray<WallpaperData>();

    int mCurrentUserId;

    static class WallpaperData {

        int userId;

        final File wallpaperFile;   // source image
        final File cropFile;        // eventual destination

        /**
         * True while the client is writing a new wallpaper
         */
        boolean imageWallpaperPending;

        /**
         * Which new wallpapers are being written; mirrors the 'which'
         * selector bit field to setWallpaper().
         */
        int whichPending;

        /**
         * Callback once the set + crop is finished
         */
        IWallpaperManagerCallback setComplete;

        /**
         * Is the OS allowed to back up this wallpaper imagery?
         */
        boolean allowBackup;

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
         * The ID of this wallpaper
         */
        int wallpaperId;

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

        /**
         * The crop hint supplied for displaying a subset of the source image
         */
        final Rect cropHint = new Rect(0, 0, 0, 0);

        final Rect padding = new Rect(0, 0, 0, 0);

        WallpaperData(int userId, String inputFileName, String cropFileName) {
            this.userId = userId;
            final File wallpaperDir = getWallpaperDir(userId);
            wallpaperFile = new File(wallpaperDir, inputFileName);
            cropFile = new File(wallpaperDir, cropFileName);
        }

        // Called during initialization of a given user's wallpaper bookkeeping
        boolean cropExists() {
            return cropFile.exists();
        }
    }

    int makeWallpaperIdLocked() {
        do {
            ++mWallpaperId;
        } while (mWallpaperId == 0);
        return mWallpaperId;
    }

    class WallpaperConnection extends IWallpaperConnection.Stub
            implements ServiceConnection {
        final WallpaperInfo mInfo;
        final Binder mToken = new Binder();
        IWallpaperService mService;
        IWallpaperEngine mEngine;
        WallpaperData mWallpaper;
        IRemoteCallback mReply;

        boolean mDimensionsChanged = false;
        boolean mPaddingChanged = false;

        public WallpaperConnection(WallpaperInfo info, WallpaperData wallpaper) {
            mInfo = info;
            mWallpaper = wallpaper;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                if (mWallpaper.connection == this) {
                    mService = IWallpaperService.Stub.asInterface(service);
                    attachServiceLocked(this, mWallpaper);
                    // XXX should probably do saveSettingsLocked() later
                    // when we have an engine, but I'm not sure about
                    // locking there and anyway we always need to be able to
                    // recover if there is something wrong.
                    saveSettingsLocked(mWallpaper.userId);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mService = null;
                mEngine = null;
                if (mWallpaper.connection == this) {
                    Slog.w(TAG, "Wallpaper service gone: " + mWallpaper.wallpaperComponent);
                    if (!mWallpaper.wallpaperUpdating
                            && mWallpaper.userId == mCurrentUserId) {
                        // There is a race condition which causes
                        // {@link #mWallpaper.wallpaperUpdating} to be false even if it is
                        // currently updating since the broadcast notifying us is async.
                        // This race is overcome by the general rule that we only reset the
                        // wallpaper if its service was shut down twice
                        // during {@link #MIN_WALLPAPER_CRASH_TIME} millis.
                        if (mWallpaper.lastDiedTime != 0
                                && mWallpaper.lastDiedTime + MIN_WALLPAPER_CRASH_TIME
                                    > SystemClock.uptimeMillis()) {
                            Slog.w(TAG, "Reverting to built-in wallpaper!");
                            clearWallpaperLocked(true, FLAG_SYSTEM, mWallpaper.userId, null);
                        } else {
                            mWallpaper.lastDiedTime = SystemClock.uptimeMillis();
                        }
                        final String flattened = name.flattenToString();
                        EventLog.writeEvent(EventLogTags.WP_WALLPAPER_CRASHED,
                                flattened.substring(0, Math.min(flattened.length(),
                                        MAX_WALLPAPER_COMPONENT_LOG_LENGTH)));
                    }
                }
            }
        }

        @Override
        public void attachEngine(IWallpaperEngine engine) {
            synchronized (mLock) {
                mEngine = engine;
                if (mDimensionsChanged) {
                    try {
                        mEngine.setDesiredSize(mWallpaper.width, mWallpaper.height);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to set wallpaper dimensions", e);
                    }
                    mDimensionsChanged = false;
                }
                if (mPaddingChanged) {
                    try {
                        mEngine.setDisplayPadding(mWallpaper.padding);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to set wallpaper padding", e);
                    }
                    mPaddingChanged = false;
                }
            }
        }

        @Override
        public void engineShown(IWallpaperEngine engine) {
            synchronized (mLock) {
                if (mReply != null) {
                    long ident = Binder.clearCallingIdentity();
                    try {
                        mReply.sendResult(null);
                    } catch (RemoteException e) {
                        Binder.restoreCallingIdentity(ident);
                    }
                    mReply = null;
                }
            }
        }

        @Override
        public ParcelFileDescriptor setWallpaper(String name) {
            synchronized (mLock) {
                if (mWallpaper.connection == this) {
                    return updateWallpaperBitmapLocked(name, mWallpaper, null);
                }
                return null;
            }
        }
    }

    class MyPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            synchronized (mLock) {
                if (mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
                    if (wallpaper.wallpaperComponent != null
                            && wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        wallpaper.wallpaperUpdating = false;
                        ComponentName comp = wallpaper.wallpaperComponent;
                        clearWallpaperComponentLocked(wallpaper);
                        if (!bindWallpaperComponentLocked(comp, false, false,
                                wallpaper, null)) {
                            Slog.w(TAG, "Wallpaper no longer available; reverting to default");
                            clearWallpaperLocked(false, FLAG_SYSTEM, wallpaper.userId, null);
                        }
                    }
                }
            }
        }

        @Override
        public void onPackageModified(String packageName) {
            synchronized (mLock) {
                if (mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
                    if (wallpaper.wallpaperComponent == null
                            || !wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        return;
                    }
                    doPackagesChangedLocked(true, wallpaper);
                }
            }
        }

        @Override
        public void onPackageUpdateStarted(String packageName, int uid) {
            synchronized (mLock) {
                if (mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
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
                if (mCurrentUserId != getChangingUserId()) {
                    return false;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
                    boolean res = doPackagesChangedLocked(doit, wallpaper);
                    changed |= res;
                }
                return changed;
            }
        }

        @Override
        public void onSomePackagesChanged() {
            synchronized (mLock) {
                if (mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
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
                        clearWallpaperLocked(false, FLAG_SYSTEM, wallpaper.userId, null);
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
                    mContext.getPackageManager().getServiceInfo(wallpaper.wallpaperComponent,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
                } catch (NameNotFoundException e) {
                    Slog.w(TAG, "Wallpaper component gone, removing: "
                            + wallpaper.wallpaperComponent);
                    clearWallpaperLocked(false, FLAG_SYSTEM, wallpaper.userId, null);
                }
            }
            if (wallpaper.nextWallpaperComponent != null
                    && isPackageModified(wallpaper.nextWallpaperComponent.getPackageName())) {
                try {
                    mContext.getPackageManager().getServiceInfo(wallpaper.nextWallpaperComponent,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
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
        mImageWallpaper = ComponentName.unflattenFromString(
                context.getResources().getString(R.string.image_wallpaper_component));
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mIPackageManager = AppGlobals.getPackageManager();
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mMonitor = new MyPackageMonitor();
        mMonitor.register(context, null, UserHandle.ALL, true);
        getWallpaperDir(UserHandle.USER_SYSTEM).mkdirs();
        loadSettingsLocked(UserHandle.USER_SYSTEM, false);
    }

    private static File getWallpaperDir(int userId) {
        return Environment.getUserSystemDirectory(userId);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        for (int i = 0; i < mWallpaperMap.size(); i++) {
            WallpaperData wallpaper = mWallpaperMap.valueAt(i);
            wallpaper.wallpaperObserver.stopWatching();
        }
    }

    void systemReady() {
        if (DEBUG) Slog.v(TAG, "systemReady");
        WallpaperData wallpaper = mWallpaperMap.get(UserHandle.USER_SYSTEM);
        // If we think we're going to be using the system image wallpaper imagery, make
        // sure we have something to render
        if (mImageWallpaper.equals(wallpaper.nextWallpaperComponent)) {
            // No crop file? Make sure we've finished the processing sequence if necessary
            if (!wallpaper.cropExists()) {
                if (DEBUG) {
                    Slog.i(TAG, "No crop; regenerating from source");
                }
                generateCrop(wallpaper);
            }
            // Still nothing?  Fall back to default.
            if (!wallpaper.cropExists()) {
                if (DEBUG) {
                    Slog.i(TAG, "Unable to regenerate crop; resetting");
                }
                clearWallpaperLocked(false, FLAG_SYSTEM, UserHandle.USER_SYSTEM, null);
            }
        } else {
            if (DEBUG) {
                Slog.i(TAG, "Nondefault wallpaper component; gracefully ignoring");
            }
        }

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    onRemoveUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            UserHandle.USER_NULL));
                }
            }
        }, userFilter);

        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(
                    new IUserSwitchObserver.Stub() {
                        @Override
                        public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                            switchUser(newUserId, reply);
                        }

                        @Override
                        public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        }

                        @Override
                        public void onForegroundProfileSwitch(int newProfileId) {
                            // Ignore.
                        }
                    });
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /** Called by SystemBackupAgent */
    public String getName() {
        // Verify caller is the system
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new RuntimeException("getName() can only be called from the system process");
        }
        synchronized (mLock) {
            return mWallpaperMap.get(0).name;
        }
    }

    void stopObserver(WallpaperData wallpaper) {
        if (wallpaper != null) {
            if (wallpaper.wallpaperObserver != null) {
                wallpaper.wallpaperObserver.stopWatching();
                wallpaper.wallpaperObserver = null;
            }
        }
    }

    void stopObserversLocked(int userId) {
        stopObserver(mWallpaperMap.get(userId));
        stopObserver(mLockWallpaperMap.get(userId));
        mWallpaperMap.remove(userId);
        mLockWallpaperMap.remove(userId);
    }

    void onUnlockUser(int userId) {
        synchronized (mLock) {
            if (mCurrentUserId == userId && mWaitingForUnlock) {
                switchUser(userId, null);
            }
        }
    }

    void onRemoveUser(int userId) {
        if (userId < 1) return;

        final File wallpaperDir = getWallpaperDir(userId);
        synchronized (mLock) {
            stopObserversLocked(userId);
            for (String filename : sPerUserFiles) {
                new File(wallpaperDir, filename).delete();
            }
        }
    }

    void switchUser(int userId, IRemoteCallback reply) {
        synchronized (mLock) {
            mCurrentUserId = userId;
            WallpaperData wallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
            // Not started watching yet, in case wallpaper data was loaded for other reasons.
            if (wallpaper.wallpaperObserver == null) {
                wallpaper.wallpaperObserver = new WallpaperObserver(wallpaper);
                wallpaper.wallpaperObserver.startWatching();
            }
            switchWallpaper(wallpaper, reply);
        }
    }

    void switchWallpaper(WallpaperData wallpaper, IRemoteCallback reply) {
        synchronized (mLock) {
            mWaitingForUnlock = false;
            final ComponentName cname = wallpaper.wallpaperComponent != null ?
                    wallpaper.wallpaperComponent : wallpaper.nextWallpaperComponent;
            if (!bindWallpaperComponentLocked(cname, true, false, wallpaper, reply)) {
                // We failed to bind the desired wallpaper, but that might
                // happen if the wallpaper isn't direct-boot aware
                ServiceInfo si = null;
                try {
                    si = mIPackageManager.getServiceInfo(cname,
                            PackageManager.MATCH_DIRECT_BOOT_UNAWARE, wallpaper.userId);
                } catch (RemoteException ignored) {
                }

                if (si == null) {
                    Slog.w(TAG, "Failure starting previous wallpaper; clearing");
                    clearWallpaperLocked(false, FLAG_SYSTEM, wallpaper.userId, reply);
                } else {
                    Slog.w(TAG, "Wallpaper isn't direct boot aware; using fallback until unlocked");
                    // We might end up persisting the current wallpaper data
                    // while locked, so pretend like the component was actually
                    // bound into place
                    wallpaper.wallpaperComponent = wallpaper.nextWallpaperComponent;
                    final WallpaperData fallback = new WallpaperData(wallpaper.userId,
                            WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
                    ensureSaneWallpaperData(fallback);
                    bindWallpaperComponentLocked(mImageWallpaper, true, false, fallback, reply);
                    mWaitingForUnlock = true;
                }
            }
        }
    }

    @Override
    public void clearWallpaper(String callingPackage, int which, int userId) {
        if (DEBUG) Slog.v(TAG, "clearWallpaper");
        checkPermission(android.Manifest.permission.SET_WALLPAPER);
        if (!isWallpaperSupported(callingPackage) || !isSetWallpaperAllowed(callingPackage)) {
            return;
        }
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "clearWallpaper", null);

        synchronized (mLock) {
            clearWallpaperLocked(false, which, userId, null);
        }
    }

    void clearWallpaperLocked(boolean defaultFailed, int which, int userId, IRemoteCallback reply) {
        if (which != FLAG_SYSTEM && which != FLAG_LOCK) {
            throw new IllegalArgumentException("Must specify exactly one kind of wallpaper to read");
        }

        WallpaperData wallpaper = null;
        if (which == FLAG_LOCK) {
            wallpaper = mLockWallpaperMap.get(userId);
            if (wallpaper == null) {
                // It's already gone; we're done.
                if (DEBUG) {
                    Slog.i(TAG, "Lock wallpaper already cleared");
                }
                return;
            }
        } else {
            wallpaper = mWallpaperMap.get(userId);
            if (wallpaper == null) {
                // Might need to bring it in the first time to establish our rewrite
                loadSettingsLocked(userId, false);
                wallpaper = mWallpaperMap.get(userId);
            }
        }
        if (wallpaper == null) {
            return;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            if (wallpaper.wallpaperFile.exists()) {
                wallpaper.wallpaperFile.delete();
                wallpaper.cropFile.delete();
                if (which == FLAG_LOCK) {
                    mLockWallpaperMap.remove(userId);
                    final IWallpaperManagerCallback cb = mKeyguardListener;
                    if (cb != null) {
                        if (DEBUG) {
                            Slog.i(TAG, "Notifying keyguard of lock wallpaper clear");
                        }
                        try {
                            cb.onWallpaperChanged();
                        } catch (RemoteException e) {
                            // Oh well it went away; no big deal
                        }
                    }
                    saveSettingsLocked(userId);
                    return;
                }
            }

            RuntimeException e = null;
            try {
                wallpaper.imageWallpaperPending = false;
                if (userId != mCurrentUserId) return;
                if (bindWallpaperComponentLocked(defaultFailed
                        ? mImageWallpaper
                                : null, true, false, wallpaper, reply)) {
                    return;
                }
            } catch (IllegalArgumentException e1) {
                e = e1;
            }

            // This can happen if the default wallpaper component doesn't
            // exist.  This should be a system configuration problem, but
            // let's not let it crash the system and just live with no
            // wallpaper.
            Slog.e(TAG, "Default wallpaper component not found!", e);
            clearWallpaperComponentLocked(wallpaper);
            if (reply != null) {
                try {
                    reply.sendResult(null);
                } catch (RemoteException e1) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean hasNamedWallpaper(String name) {
        synchronized (mLock) {
            List<UserInfo> users;
            long ident = Binder.clearCallingIdentity();
            try {
                users = ((UserManager) mContext.getSystemService(Context.USER_SERVICE)).getUsers();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            for (UserInfo user: users) {
                // ignore managed profiles
                if (user.isManagedProfile()) {
                    continue;
                }
                WallpaperData wd = mWallpaperMap.get(user.id);
                if (wd == null) {
                    // User hasn't started yet, so load her settings to peek at the wallpaper
                    loadSettingsLocked(user.id, false);
                    wd = mWallpaperMap.get(user.id);
                }
                if (wd != null && name.equals(wd.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Point getDefaultDisplaySize() {
        Point p = new Point();
        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        d.getRealSize(p);
        return p;
    }

    public void setDimensionHints(int width, int height, String callingPackage)
            throws RemoteException {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);
        if (!isWallpaperSupported(callingPackage)) {
            return;
        }
        synchronized (mLock) {
            int userId = UserHandle.getCallingUserId();
            WallpaperData wallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be > 0");
            }
            // Make sure it is at least as large as the display.
            Point displaySize = getDefaultDisplaySize();
            width = Math.max(width, displaySize.x);
            height = Math.max(height, displaySize.y);

            if (width != wallpaper.width || height != wallpaper.height) {
                wallpaper.width = width;
                wallpaper.height = height;
                saveSettingsLocked(userId);
                if (mCurrentUserId != userId) return; // Don't change the properties now
                if (wallpaper.connection != null) {
                    if (wallpaper.connection.mEngine != null) {
                        try {
                            wallpaper.connection.mEngine.setDesiredSize(
                                    width, height);
                        } catch (RemoteException e) {
                        }
                        notifyCallbacksLocked(wallpaper);
                    } else if (wallpaper.connection.mService != null) {
                        // We've attached to the service but the engine hasn't attached back to us
                        // yet. This means it will be created with the previous dimensions, so we
                        // need to update it to the new dimensions once it attaches.
                        wallpaper.connection.mDimensionsChanged = true;
                    }
                }
            }
        }
    }

    public int getWidthHint() throws RemoteException {
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(UserHandle.getCallingUserId());
            if (wallpaper != null) {
                return wallpaper.width;
            } else {
                return 0;
            }
        }
    }

    public int getHeightHint() throws RemoteException {
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(UserHandle.getCallingUserId());
            if (wallpaper != null) {
                return wallpaper.height;
            } else {
                return 0;
            }
        }
    }

    public void setDisplayPadding(Rect padding, String callingPackage) {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);
        if (!isWallpaperSupported(callingPackage)) {
            return;
        }
        synchronized (mLock) {
            int userId = UserHandle.getCallingUserId();
            WallpaperData wallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
            if (padding.left < 0 || padding.top < 0 || padding.right < 0 || padding.bottom < 0) {
                throw new IllegalArgumentException("padding must be positive: " + padding);
            }

            if (!padding.equals(wallpaper.padding)) {
                wallpaper.padding.set(padding);
                saveSettingsLocked(userId);
                if (mCurrentUserId != userId) return; // Don't change the properties now
                if (wallpaper.connection != null) {
                    if (wallpaper.connection.mEngine != null) {
                        try {
                            wallpaper.connection.mEngine.setDisplayPadding(padding);
                        } catch (RemoteException e) {
                        }
                        notifyCallbacksLocked(wallpaper);
                    } else if (wallpaper.connection.mService != null) {
                        // We've attached to the service but the engine hasn't attached back to us
                        // yet. This means it will be created with the previous dimensions, so we
                        // need to update it to the new dimensions once it attaches.
                        wallpaper.connection.mPaddingChanged = true;
                    }
                }
            }
        }
    }

    @Override
    public ParcelFileDescriptor getWallpaper(IWallpaperManagerCallback cb, final int which,
            Bundle outParams, int wallpaperUserId) {
        wallpaperUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), wallpaperUserId, false, true, "getWallpaper", null);

        if (which != FLAG_SYSTEM && which != FLAG_LOCK) {
            throw new IllegalArgumentException("Must specify exactly one kind of wallpaper to read");
        }

        synchronized (mLock) {
            final SparseArray<WallpaperData> whichSet =
                    (which == FLAG_LOCK) ? mLockWallpaperMap : mWallpaperMap;
            WallpaperData wallpaper = whichSet.get(wallpaperUserId);
            if (wallpaper == null) {
                // common case, this is the first lookup post-boot of the system or
                // unified lock, so we bring up the saved state lazily now and recheck.
                loadSettingsLocked(wallpaperUserId, false);
                wallpaper = whichSet.get(wallpaperUserId);
                if (wallpaper == null) {
                    return null;
                }
            }
            try {
                if (outParams != null) {
                    outParams.putInt("width", wallpaper.width);
                    outParams.putInt("height", wallpaper.height);
                }
                if (cb != null) {
                    wallpaper.callbacks.register(cb);
                }
                if (!wallpaper.cropFile.exists()) {
                    return null;
                }
                return ParcelFileDescriptor.open(wallpaper.cropFile, MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                /* Shouldn't happen as we check to see if the file exists */
                Slog.w(TAG, "Error getting wallpaper", e);
            }
            return null;
        }
    }

    @Override
    public WallpaperInfo getWallpaperInfo() {
        int userId = UserHandle.getCallingUserId();
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(userId);
            if (wallpaper != null && wallpaper.connection != null) {
                return wallpaper.connection.mInfo;
            }
            return null;
        }
    }

    @Override
    public int getWallpaperIdForUser(int which, int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getWallpaperIdForUser", null);

        if (which != FLAG_SYSTEM && which != FLAG_LOCK) {
            throw new IllegalArgumentException("Must specify exactly one kind of wallpaper");
        }

        final SparseArray<WallpaperData> map =
                (which == FLAG_LOCK) ? mLockWallpaperMap : mWallpaperMap;
        synchronized (mLock) {
            WallpaperData wallpaper = map.get(userId);
            if (wallpaper != null) {
                return wallpaper.wallpaperId;
            }
        }
        return -1;
    }

    @Override
    public boolean setLockWallpaperCallback(IWallpaperManagerCallback cb) {
        checkPermission(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW);
        synchronized (mLock) {
            mKeyguardListener = cb;
        }
        return true;
    }

    @Override
    public ParcelFileDescriptor setWallpaper(String name, String callingPackage,
            Rect cropHint, boolean allowBackup, Bundle extras, int which,
            IWallpaperManagerCallback completion) {
        checkPermission(android.Manifest.permission.SET_WALLPAPER);

        if ((which & (FLAG_LOCK|FLAG_SYSTEM)) == 0) {
            final String msg = "Must specify a valid wallpaper category to set";
            Slog.e(TAG, msg);
            throw new IllegalArgumentException(msg);
        }

        if (!isWallpaperSupported(callingPackage) || !isSetWallpaperAllowed(callingPackage)) {
            return null;
        }

        // "null" means the no-op crop, preserving the full input image
        if (cropHint == null) {
            cropHint = new Rect(0, 0, 0, 0);
        } else {
            if (cropHint.isEmpty()
                    || cropHint.left < 0
                    || cropHint.top < 0) {
                throw new IllegalArgumentException("Invalid crop rect supplied: " + cropHint);
            }
        }

        final int userId = UserHandle.getCallingUserId();

        synchronized (mLock) {
            if (DEBUG) Slog.v(TAG, "setWallpaper which=0x" + Integer.toHexString(which));
            WallpaperData wallpaper;

            /* If we're setting system but not lock, and lock is currently sharing the system
             * wallpaper, we need to migrate that image over to being lock-only before
             * the caller here writes new bitmap data.
             */
            if (which == FLAG_SYSTEM && mLockWallpaperMap.get(userId) == null) {
                if (DEBUG) {
                    Slog.i(TAG, "Migrating system->lock to preserve");
                }
                migrateSystemToLockWallpaperLocked(userId);
            }

            wallpaper = getWallpaperSafeLocked(userId, which);
            final long ident = Binder.clearCallingIdentity();
            try {
                ParcelFileDescriptor pfd = updateWallpaperBitmapLocked(name, wallpaper, extras);
                if (pfd != null) {
                    wallpaper.imageWallpaperPending = true;
                    wallpaper.whichPending = which;
                    wallpaper.setComplete = completion;
                    wallpaper.cropHint.set(cropHint);
                    if ((which & FLAG_SYSTEM) != 0) {
                        wallpaper.allowBackup = allowBackup;
                    }
                }
                return pfd;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void migrateSystemToLockWallpaperLocked(int userId) {
        WallpaperData sysWP = mWallpaperMap.get(userId);
        if (sysWP == null) {
            if (DEBUG) {
                Slog.i(TAG, "No system wallpaper?  Not tracking for lock-only");
            }
            return;
        }

        // We know a-priori that there is no lock-only wallpaper currently
        WallpaperData lockWP = new WallpaperData(userId,
                WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
        lockWP.wallpaperId = sysWP.wallpaperId;
        lockWP.cropHint.set(sysWP.cropHint);
        lockWP.width = sysWP.width;
        lockWP.height = sysWP.height;
        lockWP.allowBackup = false;

        // Migrate the bitmap files outright; no need to copy
        try {
            Os.rename(sysWP.wallpaperFile.getAbsolutePath(), lockWP.wallpaperFile.getAbsolutePath());
            Os.rename(sysWP.cropFile.getAbsolutePath(), lockWP.cropFile.getAbsolutePath());
        } catch (ErrnoException e) {
            Slog.e(TAG, "Can't migrate system wallpaper: " + e.getMessage());
            lockWP.wallpaperFile.delete();
            lockWP.cropFile.delete();
            return;
        }

        mLockWallpaperMap.put(userId, lockWP);
    }

    ParcelFileDescriptor updateWallpaperBitmapLocked(String name, WallpaperData wallpaper,
            Bundle extras) {
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
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(wallpaper.wallpaperFile,
                    MODE_CREATE|MODE_READ_WRITE|MODE_TRUNCATE);
            if (!SELinux.restorecon(wallpaper.wallpaperFile)) {
                return null;
            }
            wallpaper.name = name;
            wallpaper.wallpaperId = makeWallpaperIdLocked();
            if (extras != null) {
                extras.putInt(WallpaperManager.EXTRA_NEW_WALLPAPER_ID, wallpaper.wallpaperId);
            }
            if (DEBUG) {
                Slog.v(TAG, "updateWallpaperBitmapLocked() : id=" + wallpaper.wallpaperId
                        + " name=" + name + " file=" + wallpaper.wallpaperFile.getName());
            }
            return fd;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Error setting wallpaper", e);
        }
        return null;
    }

    @Override
    public void setWallpaperComponentChecked(ComponentName name, String callingPackage) {
        if (isWallpaperSupported(callingPackage) && isSetWallpaperAllowed(callingPackage)) {
            setWallpaperComponent(name);
        }
    }

    // ToDo: Remove this version of the function
    @Override
    public void setWallpaperComponent(ComponentName name) {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT);
        synchronized (mLock) {
            if (DEBUG) Slog.v(TAG, "setWallpaperComponent name=" + name);
            int userId = UserHandle.getCallingUserId();
            WallpaperData wallpaper = mWallpaperMap.get(userId);
            if (wallpaper == null) {
                throw new IllegalStateException("Wallpaper not yet initialized for user " + userId);
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                wallpaper.imageWallpaperPending = false;
                if (bindWallpaperComponentLocked(name, false, true, wallpaper, null)) {
                    wallpaper.wallpaperId = makeWallpaperIdLocked();
                    notifyCallbacksLocked(wallpaper);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    boolean bindWallpaperComponentLocked(ComponentName componentName, boolean force,
            boolean fromUser, WallpaperData wallpaper, IRemoteCallback reply) {
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
                componentName = WallpaperManager.getDefaultWallpaperComponent(mContext);
                if (componentName == null) {
                    // Fall back to static image wallpaper
                    componentName = mImageWallpaper;
                    //clearWallpaperComponentLocked();
                    //return;
                    if (DEBUG) Slog.v(TAG, "Using image wallpaper");
                }
            }
            int serviceUserId = wallpaper.userId;
            ServiceInfo si = mIPackageManager.getServiceInfo(componentName,
                    PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, serviceUserId);
            if (si == null) {
                // The wallpaper component we're trying to use doesn't exist
                Slog.w(TAG, "Attempted wallpaper " + componentName + " is unavailable");
                return false;
            }
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
            if (componentName != null && !componentName.equals(mImageWallpaper)) {
                // Make sure the selected service is actually a wallpaper service.
                List<ResolveInfo> ris =
                        mIPackageManager.queryIntentServices(intent,
                                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                PackageManager.GET_META_DATA, serviceUserId).getList();
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
            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.wallpaper_binding_label);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivityAsUser(
                    mContext, 0,
                    Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
                            mContext.getText(com.android.internal.R.string.chooser_wallpaper)),
                    0, null, new UserHandle(serviceUserId)));
            if (!mContext.bindServiceAsUser(intent, newConn,
                    Context.BIND_AUTO_CREATE | Context.BIND_SHOWING_UI
                            | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    new UserHandle(serviceUserId))) {
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
            newConn.mReply = reply;
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
        } catch (RemoteException e) {
            String msg = "Remote exception for " + componentName + "\n" + e;
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
            if (wallpaper.connection.mReply != null) {
                try {
                    wallpaper.connection.mReply.sendResult(null);
                } catch (RemoteException e) {
                }
                wallpaper.connection.mReply = null;
            }
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
                    wallpaper.width, wallpaper.height, wallpaper.padding);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed attaching wallpaper; clearing", e);
            if (!wallpaper.wallpaperUpdating) {
                bindWallpaperComponentLocked(null, false, false, wallpaper, null);
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
        mContext.sendBroadcastAsUser(intent, new UserHandle(mCurrentUserId));
    }

    private void checkPermission(String permission) {
        if (PackageManager.PERMISSION_GRANTED!= mContext.checkCallingOrSelfPermission(permission)) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    /**
     * Certain user types do not support wallpapers (e.g. managed profiles). The check is
     * implemented through through the OP_WRITE_WALLPAPER AppOp.
     */
    public boolean isWallpaperSupported(String callingPackage) {
        return mAppOpsManager.checkOpNoThrow(AppOpsManager.OP_WRITE_WALLPAPER, Binder.getCallingUid(),
                callingPackage) == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public boolean isSetWallpaperAllowed(String callingPackage) {
        final PackageManager pm = mContext.getPackageManager();
        String[] uidPackages = pm.getPackagesForUid(Binder.getCallingUid());
        boolean uidMatchPackage = Arrays.asList(uidPackages).contains(callingPackage);
        if (!uidMatchPackage) {
            return false;   // callingPackage was faked.
        }

        final DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        if (dpm.isDeviceOwnerApp(callingPackage) || dpm.isProfileOwnerApp(callingPackage)) {
            return true;
        }
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        return !um.hasUserRestriction(UserManager.DISALLOW_SET_WALLPAPER);
    }

    @Override
    public boolean isWallpaperBackupEligible(int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call isWallpaperBackupEligible");
        }

        WallpaperData wallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
        return (wallpaper != null) ? wallpaper.allowBackup : false;
    }

    private static JournaledFile makeJournaledFile(int userId) {
        final String base = new File(getWallpaperDir(userId), WALLPAPER_INFO).getAbsolutePath();
        return new JournaledFile(new File(base), new File(base + ".tmp"));
    }

    private void saveSettingsLocked(int userId) {
        JournaledFile journal = makeJournaledFile(userId);
        FileOutputStream fstream = null;
        BufferedOutputStream stream = null;
        try {
            XmlSerializer out = new FastXmlSerializer();
            fstream = new FileOutputStream(journal.chooseForWrite(), false);
            stream = new BufferedOutputStream(fstream);
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);

            WallpaperData wallpaper;

            wallpaper = mWallpaperMap.get(userId);
            if (wallpaper != null) {
                writeWallpaperAttributes(out, "wp", wallpaper);
            }
            wallpaper = mLockWallpaperMap.get(userId);
            if (wallpaper != null) {
                writeWallpaperAttributes(out, "kwp", wallpaper);
            }

            out.endDocument();

            stream.flush(); // also flushes fstream
            FileUtils.sync(fstream);
            stream.close(); // also closes fstream
            journal.commit();
        } catch (IOException e) {
            IoUtils.closeQuietly(stream);
            journal.rollback();
        }
    }

    private void writeWallpaperAttributes(XmlSerializer out, String tag, WallpaperData wallpaper)
            throws IllegalArgumentException, IllegalStateException, IOException {
        out.startTag(null, tag);
        out.attribute(null, "id", Integer.toString(wallpaper.wallpaperId));
        out.attribute(null, "width", Integer.toString(wallpaper.width));
        out.attribute(null, "height", Integer.toString(wallpaper.height));

        out.attribute(null, "cropLeft", Integer.toString(wallpaper.cropHint.left));
        out.attribute(null, "cropTop", Integer.toString(wallpaper.cropHint.top));
        out.attribute(null, "cropRight", Integer.toString(wallpaper.cropHint.right));
        out.attribute(null, "cropBottom", Integer.toString(wallpaper.cropHint.bottom));

        if (wallpaper.padding.left != 0) {
            out.attribute(null, "paddingLeft", Integer.toString(wallpaper.padding.left));
        }
        if (wallpaper.padding.top != 0) {
            out.attribute(null, "paddingTop", Integer.toString(wallpaper.padding.top));
        }
        if (wallpaper.padding.right != 0) {
            out.attribute(null, "paddingRight", Integer.toString(wallpaper.padding.right));
        }
        if (wallpaper.padding.bottom != 0) {
            out.attribute(null, "paddingBottom", Integer.toString(wallpaper.padding.bottom));
        }

        out.attribute(null, "name", wallpaper.name);
        if (wallpaper.wallpaperComponent != null
                && !wallpaper.wallpaperComponent.equals(mImageWallpaper)) {
            out.attribute(null, "component",
                    wallpaper.wallpaperComponent.flattenToShortString());
        }

        if (wallpaper.allowBackup) {
            out.attribute(null, "backup", "true");
        }

        out.endTag(null, tag);
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

    private int getAttributeInt(XmlPullParser parser, String name, int defValue) {
        String value = parser.getAttributeValue(null, name);
        if (value == null) {
            return defValue;
        }
        return Integer.parseInt(value);
    }

    /**
     * Sometimes it is expected the wallpaper map may not have a user's data.  E.g. This could
     * happen during user switch.  The async user switch observer may not have received
     * the event yet.  We use this safe method when we don't care about this ordering and just
     * want to update the data.  The data is going to be applied when the user switch observer
     * is eventually executed.
     */
    private WallpaperData getWallpaperSafeLocked(int userId, int which) {
        // We're setting either just system (work with the system wallpaper),
        // both (also work with the system wallpaper), or just the lock
        // wallpaper (update against the existing lock wallpaper if any).
        // Combined or just-system operations use the 'system' WallpaperData
        // for this use; lock-only operations use the dedicated one.
        final SparseArray<WallpaperData> whichSet =
                (which == FLAG_LOCK) ? mLockWallpaperMap : mWallpaperMap;
        WallpaperData wallpaper = whichSet.get(userId);
        if (wallpaper == null) {
            // common case, this is the first lookup post-boot of the system or
            // unified lock, so we bring up the saved state lazily now and recheck.
            loadSettingsLocked(userId, false);
            wallpaper = whichSet.get(userId);
            // if it's still null here, this is a lock-only operation and there is not
            // yet a lock-only wallpaper set for this user, so we need to establish
            // it now.
            if (wallpaper == null) {
                if (which == FLAG_LOCK) {
                    wallpaper = new WallpaperData(userId,
                            WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
                    mLockWallpaperMap.put(userId, wallpaper);
                    ensureSaneWallpaperData(wallpaper);
                } else {
                    // sanity fallback: we're in bad shape, but establishing a known
                    // valid system+lock WallpaperData will keep us from dying.
                    Slog.wtf(TAG, "Didn't find wallpaper in non-lock case!");
                    wallpaper = new WallpaperData(userId, WALLPAPER, WALLPAPER_CROP);
                    mWallpaperMap.put(userId, wallpaper);
                    ensureSaneWallpaperData(wallpaper);
                }
            }
        }
        return wallpaper;
    }

    private void loadSettingsLocked(int userId, boolean keepDimensionHints) {
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
            wallpaper = new WallpaperData(userId, WALLPAPER, WALLPAPER_CROP);
            wallpaper.allowBackup = true;
            mWallpaperMap.put(userId, wallpaper);
            if (!wallpaper.cropExists()) {
                generateCrop(wallpaper);
            }
        }
        boolean success = false;
        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());

            int type;
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("wp".equals(tag)) {
                        // Common to system + lock wallpapers
                        parseWallpaperAttributes(parser, wallpaper, keepDimensionHints);

                        // A system wallpaper might also be a live wallpaper
                        String comp = parser.getAttributeValue(null, "component");
                        wallpaper.nextWallpaperComponent = comp != null
                                ? ComponentName.unflattenFromString(comp)
                                : null;
                        if (wallpaper.nextWallpaperComponent == null
                                || "android".equals(wallpaper.nextWallpaperComponent
                                        .getPackageName())) {
                            wallpaper.nextWallpaperComponent = mImageWallpaper;
                        }

                        if (DEBUG) {
                            Slog.v(TAG, "mWidth:" + wallpaper.width);
                            Slog.v(TAG, "mHeight:" + wallpaper.height);
                            Slog.v(TAG, "cropRect:" + wallpaper.cropHint);
                            Slog.v(TAG, "mName:" + wallpaper.name);
                            Slog.v(TAG, "mNextWallpaperComponent:"
                                    + wallpaper.nextWallpaperComponent);
                        }
                    } else if ("kwp".equals(tag)) {
                        // keyguard-specific wallpaper for this user
                        WallpaperData lockWallpaper = mLockWallpaperMap.get(userId);
                        if (lockWallpaper == null) {
                            lockWallpaper = new WallpaperData(userId,
                                    WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
                            mLockWallpaperMap.put(userId, lockWallpaper);
                        }
                        parseWallpaperAttributes(parser, lockWallpaper, false);
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
            success = true;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "no current wallpaper -- first boot?");
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
        IoUtils.closeQuietly(stream);

        if (!success) {
            wallpaper.width = -1;
            wallpaper.height = -1;
            wallpaper.cropHint.set(0, 0, 0, 0);
            wallpaper.padding.set(0, 0, 0, 0);
            wallpaper.name = "";

            mLockWallpaperMap.remove(userId);
        } else {
            if (wallpaper.wallpaperId <= 0) {
                wallpaper.wallpaperId = makeWallpaperIdLocked();
                if (DEBUG) {
                    Slog.w(TAG, "Didn't set wallpaper id in loadSettingsLocked(" + userId
                            + "); now " + wallpaper.wallpaperId);
                }
            }
        }

        ensureSaneWallpaperData(wallpaper);
        WallpaperData lockWallpaper = mLockWallpaperMap.get(userId);
        if (lockWallpaper != null) {
            ensureSaneWallpaperData(lockWallpaper);
        }
    }

    private void ensureSaneWallpaperData(WallpaperData wallpaper) {
        // We always want to have some reasonable width hint.
        int baseSize = getMaximumSizeDimension();
        if (wallpaper.width < baseSize) {
            wallpaper.width = baseSize;
        }
        if (wallpaper.height < baseSize) {
            wallpaper.height = baseSize;
        }
        // and crop, if not previously specified
        if (wallpaper.cropHint.width() <= 0
                || wallpaper.cropHint.height() <= 0) {
            wallpaper.cropHint.set(0, 0, wallpaper.width, wallpaper.height);
        }
    }

    private void parseWallpaperAttributes(XmlPullParser parser, WallpaperData wallpaper,
            boolean keepDimensionHints) {
        final String idString = parser.getAttributeValue(null, "id");
        if (idString != null) {
            final int id = wallpaper.wallpaperId = Integer.parseInt(idString);
            if (id > mWallpaperId) {
                mWallpaperId = id;
            }
        } else {
            wallpaper.wallpaperId = makeWallpaperIdLocked();
        }

        if (!keepDimensionHints) {
            wallpaper.width = Integer.parseInt(parser.getAttributeValue(null, "width"));
            wallpaper.height = Integer.parseInt(parser
                    .getAttributeValue(null, "height"));
        }
        wallpaper.cropHint.left = getAttributeInt(parser, "cropLeft", 0);
        wallpaper.cropHint.top = getAttributeInt(parser, "cropTop", 0);
        wallpaper.cropHint.right = getAttributeInt(parser, "cropRight", 0);
        wallpaper.cropHint.bottom = getAttributeInt(parser, "cropBottom", 0);
        wallpaper.padding.left = getAttributeInt(parser, "paddingLeft", 0);
        wallpaper.padding.top = getAttributeInt(parser, "paddingTop", 0);
        wallpaper.padding.right = getAttributeInt(parser, "paddingRight", 0);
        wallpaper.padding.bottom = getAttributeInt(parser, "paddingBottom", 0);
        wallpaper.name = parser.getAttributeValue(null, "name");
        wallpaper.allowBackup = "true".equals(parser.getAttributeValue(null, "backup"));
    }

    private int getMaximumSizeDimension() {
        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        return d.getMaximumSizeDimension();
    }

    // Called by SystemBackupAgent after files are restored to disk.
    public void settingsRestored() {
        // Verify caller is the system
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new RuntimeException("settingsRestored() can only be called from the system process");
        }
        // TODO: If necessary, make it work for secondary users as well. This currently assumes
        // restores only to the primary user
        if (DEBUG) Slog.v(TAG, "settingsRestored");
        WallpaperData wallpaper = null;
        boolean success = false;
        synchronized (mLock) {
            loadSettingsLocked(UserHandle.USER_SYSTEM, false);
            wallpaper = mWallpaperMap.get(UserHandle.USER_SYSTEM);
            wallpaper.wallpaperId = makeWallpaperIdLocked();    // always bump id at restore
            wallpaper.allowBackup = true;   // by definition if it was restored
            if (wallpaper.nextWallpaperComponent != null
                    && !wallpaper.nextWallpaperComponent.equals(mImageWallpaper)) {
                if (!bindWallpaperComponentLocked(wallpaper.nextWallpaperComponent, false, false,
                        wallpaper, null)) {
                    // No such live wallpaper or other failure; fall back to the default
                    // live wallpaper (since the profile being restored indicated that the
                    // user had selected a live rather than static one).
                    bindWallpaperComponentLocked(null, false, false, wallpaper, null);
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
                if (DEBUG) Slog.v(TAG, "settingsRestored: success=" + success
                        + " id=" + wallpaper.wallpaperId);
                if (success) {
                    generateCrop(wallpaper);    // based on the new image + metadata
                    bindWallpaperComponentLocked(wallpaper.nextWallpaperComponent, true, false,
                            wallpaper, null);
                }
            }
        }

        if (!success) {
            Slog.e(TAG, "Failed to restore wallpaper: '" + wallpaper.name + "'");
            wallpaper.name = "";
            getWallpaperDir(UserHandle.USER_SYSTEM).delete();
        }

        synchronized (mLock) {
            saveSettingsLocked(UserHandle.USER_SYSTEM);
        }
    }

    // Restore the named resource bitmap to both source + crop files
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
                FileOutputStream cos = null;
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
                        wallpaper.cropFile.delete();
                    }
                    fos = new FileOutputStream(wallpaper.wallpaperFile);
                    cos = new FileOutputStream(wallpaper.cropFile);

                    byte[] buffer = new byte[32768];
                    int amt;
                    while ((amt=res.read(buffer)) > 0) {
                        fos.write(buffer, 0, amt);
                        cos.write(buffer, 0, amt);
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
                    IoUtils.closeQuietly(res);
                    if (fos != null) {
                        FileUtils.sync(fos);
                    }
                    if (cos != null) {
                        FileUtils.sync(cos);
                    }
                    IoUtils.closeQuietly(fos);
                    IoUtils.closeQuietly(cos);
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
            pw.println("System wallpaper state:");
            for (int i = 0; i < mWallpaperMap.size(); i++) {
                WallpaperData wallpaper = mWallpaperMap.valueAt(i);
                pw.print(" User "); pw.print(wallpaper.userId);
                    pw.print(": id="); pw.println(wallpaper.wallpaperId);
                pw.print("  mWidth=");
                    pw.print(wallpaper.width);
                    pw.print(" mHeight=");
                    pw.println(wallpaper.height);
                pw.print("  mCropHint="); pw.println(wallpaper.cropHint);
                pw.print("  mPadding="); pw.println(wallpaper.padding);
                pw.print("  mName=");  pw.println(wallpaper.name);
                pw.print("  mWallpaperComponent="); pw.println(wallpaper.wallpaperComponent);
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
            pw.println("Lock wallpaper state:");
            for (int i = 0; i < mLockWallpaperMap.size(); i++) {
                WallpaperData wallpaper = mLockWallpaperMap.valueAt(i);
                pw.print(" User "); pw.print(wallpaper.userId);
                    pw.print(": id="); pw.println(wallpaper.wallpaperId);
                pw.print("  mWidth="); pw.print(wallpaper.width);
                    pw.print(" mHeight="); pw.println(wallpaper.height);
                pw.print("  mCropHint="); pw.println(wallpaper.cropHint);
                pw.print("  mPadding="); pw.println(wallpaper.padding);
                pw.print("  mName=");  pw.println(wallpaper.name);
            }

        }
    }
}
