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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.READ_WALLPAPER_INTERNAL;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.WallpaperManager.COMMAND_REAPPLY;
import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;
import static android.os.Process.THREAD_PRIORITY_FOREGROUND;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.server.wallpaper.WallpaperDisplayHelper.DisplayData;
import static com.android.server.wallpaper.WallpaperUtils.RECORD_FILE;
import static com.android.server.wallpaper.WallpaperUtils.RECORD_LOCK_FILE;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER_INFO;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER_LOCK_ORIG;
import static com.android.server.wallpaper.WallpaperUtils.getWallpaperDir;
import static com.android.server.wallpaper.WallpaperUtils.makeWallpaperIdLocked;
import static com.android.window.flags.Flags.multiCrop;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.ApplicationExitInfo;
import android.app.ILocalWallpaperColorConsumer;
import android.app.IWallpaperManager;
import android.app.IWallpaperManagerCallback;
import android.app.PendingIntent;
import android.app.UidObserver;
import android.app.UserSwitchObserver;
import android.app.WallpaperColors;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.WallpaperManager.SetWallpaperFlags;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.multiuser.Flags;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.IWallpaperEngine;
import android.service.wallpaper.IWallpaperService;
import android.service.wallpaper.WallpaperService;
import android.system.ErrnoException;
import android.system.Os;
import android.util.EventLog;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wallpaper.WallpaperData.BindSource;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class WallpaperManagerService extends IWallpaperManager.Stub
        implements IWallpaperManagerService {
    private static final String TAG = "WallpaperManagerService";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_LIVE = true;

    private static final @NonNull RectF LOCAL_COLOR_BOUNDS =
            new RectF(0, 0, 1, 1);

    public static class Lifecycle extends SystemService {
        private IWallpaperManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            try {
                final Class<? extends IWallpaperManagerService> klass =
                        (Class<? extends IWallpaperManagerService>)Class.forName(
                                getContext().getResources().getString(
                                        R.string.config_wallpaperManagerServiceName));
                mService = klass.getConstructor(Context.class).newInstance(getContext());
                publishBinderService(Context.WALLPAPER_SERVICE, mService);
            } catch (Exception exp) {
                Slog.wtf(TAG, "Failed to instantiate WallpaperManagerService", exp);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            if (mService != null) {
                mService.onBootPhase(phase);
            }
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            if (mService != null) {
                mService.onUnlockUser(user.getUserIdentifier());
            }
        }
    }

    private final Object mLock = new Object();
    /** True to support different crops for different display dimensions */
    private final boolean mIsMultiCropEnabled;
    /** Tracks wallpaper being migrated from system+lock to lock when setting static wp. */
    WallpaperDestinationChangeHandler mPendingMigrationViaStatic;

    private static final double LMK_LOW_THRESHOLD_MEMORY_PERCENTAGE = 10;
    private static final int LMK_RECONNECT_REBIND_RETRIES = 3;
    private static final long LMK_RECONNECT_DELAY_MS = 5000;

    /**
     * Minimum time between crashes of a wallpaper service for us to consider
     * restarting it vs. just reverting to the static wallpaper.
     */
    private static final long MIN_WALLPAPER_CRASH_TIME = 10000;
    private static final int MAX_WALLPAPER_COMPONENT_LOG_LENGTH = 128;

    /**
     * Observes the wallpaper for changes and notifies all IWallpaperServiceCallbacks
     * that the wallpaper has changed. The CREATE is triggered when there is no
     * wallpaper set and is created for the first time. The CLOSE_WRITE is triggered
     * every time the wallpaper is changed.
     */
    class WallpaperObserver extends FileObserver {

        final int mUserId;
        final WallpaperData mWallpaper;
        final File mWallpaperDir;
        final File mWallpaperFile;
        final File mWallpaperLockFile;

        public WallpaperObserver(WallpaperData wallpaper) {
            super(getWallpaperDir(wallpaper.userId).getAbsolutePath(),
                    CLOSE_WRITE | MOVED_TO | DELETE | DELETE_SELF);
            mUserId = wallpaper.userId;
            mWallpaperDir = getWallpaperDir(wallpaper.userId);
            mWallpaper = wallpaper;
            mWallpaperFile = new File(mWallpaperDir, WALLPAPER);
            mWallpaperLockFile = new File(mWallpaperDir, WALLPAPER_LOCK_ORIG);
        }

        WallpaperData dataForEvent(boolean lockChanged) {
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

        // Handles static wallpaper changes generated by WallpaperObserver events when
        // enableSeparateLockScreenEngine() is true.
        private void updateWallpapers(int event, String path) {
            // System and system+lock changes happen on the system wallpaper input file;
            // lock-only changes happen on the dedicated lock wallpaper input file
            final File changedFile = new File(mWallpaperDir, path);
            final boolean sysWallpaperChanged = (mWallpaperFile.equals(changedFile));
            final boolean lockWallpaperChanged = (mWallpaperLockFile.equals(changedFile));
            final WallpaperData wallpaper = dataForEvent(lockWallpaperChanged);

            final boolean moved = (event == MOVED_TO);
            final boolean written = (event == CLOSE_WRITE || moved);
            final boolean isMigration = moved && lockWallpaperChanged;
            final boolean isRestore = moved && !isMigration;
            final boolean isAppliedToLock = (wallpaper.mWhich & FLAG_LOCK) != 0;
            final boolean needsUpdate = wallpaper.wallpaperComponent == null
                    || event != CLOSE_WRITE // includes the MOVED_TO case
                    || wallpaper.imageWallpaperPending;

            if (isMigration) {
                // When separate lock screen engine is supported, migration will be handled by
                // WallpaperDestinationChangeHandler.
                return;
            }
            if (!(sysWallpaperChanged || lockWallpaperChanged)) {
                return;
            }

            if (DEBUG) {
                Slog.v(TAG, "Wallpaper file change: evt=" + event
                        + " path=" + path
                        + " sys=" + sysWallpaperChanged
                        + " lock=" + lockWallpaperChanged
                        + " imagePending=" + wallpaper.imageWallpaperPending
                        + " mWhich=0x" + Integer.toHexString(wallpaper.mWhich)
                        + " written=" + written
                        + " isMigration=" + isMigration
                        + " isRestore=" + isRestore
                        + " isAppliedToLock=" + isAppliedToLock
                        + " needsUpdate=" + needsUpdate);
            }

            synchronized (mLock) {
                notifyCallbacksLocked(wallpaper);

                if (!written || !needsUpdate) {
                    return;
                }

                if (DEBUG) {
                    Slog.v(TAG, "Setting new static wallpaper: which=" + wallpaper.mWhich);
                }

                WallpaperDestinationChangeHandler localSync = mPendingMigrationViaStatic;
                mPendingMigrationViaStatic = null;
                // The image source has finished writing the source image,
                // so we now produce the crop rect (in the background), and
                // only publish the new displayable (sub)image as a result
                // of that work.
                SELinux.restorecon(changedFile);
                if (isRestore) {
                    // This is a restore, so generate the crop using any just-restored new
                    // crop guidelines, making sure to preserve our local dimension hints.
                    // We also make sure to reapply the correct SELinux label.
                    if (DEBUG) {
                        Slog.v(TAG, "Wallpaper restore; reloading metadata");
                    }
                    loadSettingsLocked(wallpaper.userId, true, FLAG_SYSTEM | FLAG_LOCK);
                }
                if (DEBUG) {
                    Slog.v(TAG, "Wallpaper written; generating crop");
                }
                mWallpaperCropper.generateCrop(wallpaper);
                if (DEBUG) {
                    Slog.v(TAG, "Crop done; invoking completion callback");
                }
                wallpaper.imageWallpaperPending = false;

                if (sysWallpaperChanged) {
                    if (DEBUG) {
                        Slog.v(TAG, "Home screen wallpaper changed");
                    }
                    IRemoteCallback.Stub callback = new IRemoteCallback.Stub() {
                        @Override
                        public void sendResult(Bundle data) throws RemoteException {
                            if (DEBUG) {
                                Slog.d(TAG, "publish system wallpaper changed!");
                            }
                            notifyWallpaperChanged(wallpaper);
                        }
                    };

                    // If this was the system wallpaper, rebind...
                    wallpaper.mBindSource = BindSource.SET_STATIC;
                    bindWallpaperComponentLocked(mImageWallpaper, true, false, wallpaper,
                            callback);
                }

                if (lockWallpaperChanged) {
                    // This is lock-only, so (re)bind to the static engine.
                    if (DEBUG) {
                        Slog.v(TAG, "Lock screen wallpaper changed");
                    }
                    IRemoteCallback.Stub callback = new IRemoteCallback.Stub() {
                        @Override
                        public void sendResult(Bundle data) throws RemoteException {
                            if (DEBUG) {
                                Slog.d(TAG, "publish lock wallpaper changed!");
                            }
                            notifyWallpaperChanged(wallpaper);
                        }
                    };

                    wallpaper.mBindSource = BindSource.SET_STATIC;
                    bindWallpaperComponentLocked(mImageWallpaper, true /* force */,
                            false /* fromUser */, wallpaper, callback);
                } else if (isAppliedToLock) {
                    // This is system-plus-lock: we need to wipe the lock bookkeeping since
                    // we're falling back to displaying the system wallpaper there.
                    if (DEBUG) {
                        Slog.v(TAG, "Lock screen wallpaper changed to same as home");
                    }
                    final WallpaperData lockedWallpaper = mLockWallpaperMap.get(
                            mWallpaper.userId);
                    if (lockedWallpaper != null) {
                        detachWallpaperLocked(lockedWallpaper);
                    }
                    clearWallpaperBitmaps(mWallpaper.userId, FLAG_LOCK);
                    mLockWallpaperMap.remove(wallpaper.userId);
                }

                saveSettingsLocked(wallpaper.userId);
                if (localSync != null) {
                    localSync.complete();
                }
            }

            // Outside of the lock since it will synchronize itself
            notifyWallpaperColorsChanged(wallpaper);
        }

        @Override
        public void onEvent(int event, String path) {
            if (path != null) updateWallpapers(event, path);
        }
    }

    private void notifyWallpaperChanged(WallpaperData wallpaper) {
        // Publish completion *after* we've persisted the changes
        if (wallpaper.setComplete != null) {
            try {
                wallpaper.setComplete.onWallpaperChanged();
            } catch (RemoteException e) {
                // if this fails we don't really care; the setting app may just
                // have crashed and that sort of thing is a fact of life.
                Slog.w(TAG, "onWallpaperChanged threw an exception", e);
            }
        }
    }

    void notifyWallpaperColorsChanged(@NonNull WallpaperData wallpaper) {
        if (DEBUG) {
            Slog.i(TAG, "Notifying wallpaper colors changed");
        }
        if (wallpaper.connection != null) {
            wallpaper.connection.forEachDisplayConnector(connector ->
                    notifyWallpaperColorsChangedOnDisplay(wallpaper, connector.mDisplayId));
        }
    }

    private RemoteCallbackList<IWallpaperManagerCallback> getWallpaperCallbacks(int userId,
            int displayId) {
        RemoteCallbackList<IWallpaperManagerCallback> listeners = null;
        final SparseArray<RemoteCallbackList<IWallpaperManagerCallback>> displayListeners =
                mColorsChangedListeners.get(userId);
        if (displayListeners != null) {
            listeners = displayListeners.get(displayId);
        }
        return listeners;
    }

    private void notifyWallpaperColorsChangedOnDisplay(@NonNull WallpaperData wallpaper,
            int displayId) {
        boolean needsExtraction;
        synchronized (mLock) {
            final RemoteCallbackList<IWallpaperManagerCallback> currentUserColorListeners =
                    getWallpaperCallbacks(wallpaper.userId, displayId);
            final RemoteCallbackList<IWallpaperManagerCallback> userAllColorListeners =
                    getWallpaperCallbacks(UserHandle.USER_ALL, displayId);
            // No-op until someone is listening to it.
            if (emptyCallbackList(currentUserColorListeners)  &&
                    emptyCallbackList(userAllColorListeners)) {
                return;
            }

            if (DEBUG) {
                Slog.v(TAG, "notifyWallpaperColorsChangedOnDisplay " + wallpaper.mWhich);
            }

            needsExtraction = wallpaper.primaryColors == null || wallpaper.mIsColorExtractedFromDim;
        }

        boolean notify = true;
        if (needsExtraction) {
            notify = extractColors(wallpaper);
        }
        if (notify) {
            notifyColorListeners(getAdjustedWallpaperColorsOnDimming(wallpaper),
                    wallpaper.mWhich, wallpaper.userId, displayId);
        }
    }

    private static <T extends IInterface> boolean emptyCallbackList(RemoteCallbackList<T> list) {
        return (list == null || list.getRegisteredCallbackCount() == 0);
    }

    private void notifyColorListeners(@NonNull WallpaperColors wallpaperColors, int which,
            int userId, int displayId) {
        final ArrayList<IWallpaperManagerCallback> colorListeners = new ArrayList<>();
        synchronized (mLock) {
            final RemoteCallbackList<IWallpaperManagerCallback> currentUserColorListeners =
                    getWallpaperCallbacks(userId, displayId);
            final RemoteCallbackList<IWallpaperManagerCallback> userAllColorListeners =
                    getWallpaperCallbacks(UserHandle.USER_ALL, displayId);

            if (currentUserColorListeners != null) {
                final int count = currentUserColorListeners.beginBroadcast();
                for (int i = 0; i < count; i++) {
                    colorListeners.add(currentUserColorListeners.getBroadcastItem(i));
                }
                currentUserColorListeners.finishBroadcast();
            }

            if (userAllColorListeners != null) {
                final int count = userAllColorListeners.beginBroadcast();
                for (int i = 0; i < count; i++) {
                    colorListeners.add(userAllColorListeners.getBroadcastItem(i));
                }
                userAllColorListeners.finishBroadcast();
            }
        }

        final int count = colorListeners.size();
        for (int i = 0; i < count; i++) {
            try {
                colorListeners.get(i).onWallpaperColorsChanged(wallpaperColors, which, userId);
            } catch (RemoteException e) {
                // Callback is gone, it's not necessary to unregister it since
                // RemoteCallbackList#getBroadcastItem will take care of it.
                Slog.w(TAG, "onWallpaperColorsChanged() threw an exception", e);
            }
        }
    }

    /**
     * We can easily extract colors from an ImageWallpaper since it's only a bitmap.
     * In this case, using the crop is more than enough. Live wallpapers are just ignored.
     *
     * @param wallpaper a wallpaper representation
     * @return true unless the wallpaper changed during the color computation
     */
    private boolean extractColors(WallpaperData wallpaper) {
        String cropFile = null;
        boolean defaultImageWallpaper = false;
        int wallpaperId;
        float dimAmount;

        synchronized (mLock) {
            wallpaper.mIsColorExtractedFromDim = false;
        }

        if (wallpaper.equals(mFallbackWallpaper)) {
            synchronized (mLock) {
                if (mFallbackWallpaper.primaryColors != null) return true;
            }
            final WallpaperColors colors = extractDefaultImageWallpaperColors(wallpaper);
            synchronized (mLock) {
                mFallbackWallpaper.primaryColors = colors;
            }
            return true;
        }

        synchronized (mLock) {
            // Not having a wallpaperComponent means it's a lock screen wallpaper.
            final boolean imageWallpaper = mImageWallpaper.equals(wallpaper.wallpaperComponent)
                    || wallpaper.wallpaperComponent == null;
            if (imageWallpaper && wallpaper.getCropFile().exists()) {
                cropFile = wallpaper.getCropFile().getAbsolutePath();
            } else if (imageWallpaper && !wallpaper.cropExists() && !wallpaper.sourceExists()) {
                defaultImageWallpaper = true;
            }
            wallpaperId = wallpaper.wallpaperId;
            dimAmount = wallpaper.mWallpaperDimAmount;
        }

        WallpaperColors colors = null;
        if (cropFile != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(cropFile);
            if (bitmap != null) {
                colors = WallpaperColors.fromBitmap(bitmap, dimAmount);
                bitmap.recycle();
            }
        } else if (defaultImageWallpaper) {
            // There is no crop and source file because this is default image wallpaper.
            colors = extractDefaultImageWallpaperColors(wallpaper);
        }

        if (colors == null) {
            Slog.w(TAG, "Cannot extract colors because wallpaper could not be read.");
            return true;
        }

        synchronized (mLock) {
            if (wallpaper.wallpaperId == wallpaperId) {
                wallpaper.primaryColors = colors;
                // Now that we have the colors, let's save them into the xml
                // to avoid having to run this again.
                saveSettingsLocked(wallpaper.userId);
                return true;
            } else {
                Slog.w(TAG, "Not setting primary colors since wallpaper changed");
                return false;
            }
        }
    }

    private WallpaperColors extractDefaultImageWallpaperColors(WallpaperData wallpaper) {
        if (DEBUG) Slog.d(TAG, "Extract default image wallpaper colors");
        float dimAmount;

        synchronized (mLock) {
            if (mCacheDefaultImageWallpaperColors != null) return mCacheDefaultImageWallpaperColors;
            dimAmount = wallpaper.mWallpaperDimAmount;
        }

        WallpaperColors colors = null;
        try (InputStream is = WallpaperManager.openDefaultWallpaper(mContext, FLAG_SYSTEM)) {
            if (is == null) {
                Slog.w(TAG, "Can't open default wallpaper stream");
                return null;
            }

            final BitmapFactory.Options options = new BitmapFactory.Options();
            final Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
            if (bitmap != null) {
                colors = WallpaperColors.fromBitmap(bitmap, dimAmount);
                bitmap.recycle();
            }
        } catch (OutOfMemoryError e) {
            Slog.w(TAG, "Can't decode default wallpaper stream", e);
        } catch (IOException e) {
            Slog.w(TAG, "Can't close default wallpaper stream", e);
        }

        if (colors == null) {
            Slog.e(TAG, "Extract default image wallpaper colors failed");
        } else {
            synchronized (mLock) {
                mCacheDefaultImageWallpaperColors = colors;
            }
        }

        return colors;
    }

    private final Context mContext;
    private final AtomicBoolean mIsInitialBinding = new AtomicBoolean(true);
    private final ServiceThread mHandlerThread;
    private final WindowManagerInternal mWindowManagerInternal;
    private final PackageManagerInternal mPackageManagerInternal;
    private final IPackageManager mIPackageManager;
    private final ActivityManager mActivityManager;
    private final MyPackageMonitor mMonitor;
    private final AppOpsManager mAppOpsManager;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {

        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLock) {
                if (mLastWallpaper != null) {
                    WallpaperData targetWallpaper = null;
                    if (mLastWallpaper.connection.containsDisplay(displayId)) {
                        targetWallpaper = mLastWallpaper;
                    } else if (mFallbackWallpaper.connection.containsDisplay(displayId)) {
                        targetWallpaper = mFallbackWallpaper;
                    }
                    if (targetWallpaper == null) return;
                    DisplayConnector connector =
                            targetWallpaper.connection.getDisplayConnectorOrCreate(displayId);
                    if (connector == null) return;
                    connector.disconnectLocked(targetWallpaper.connection);
                    targetWallpaper.connection.removeDisplayConnector(displayId);
                    mWallpaperDisplayHelper.removeDisplayData(displayId);
                }
                for (int i = mColorsChangedListeners.size() - 1; i >= 0; i--) {
                    final SparseArray<RemoteCallbackList<IWallpaperManagerCallback>> callbacks =
                            mColorsChangedListeners.valueAt(i);
                    callbacks.delete(displayId);
                }
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
        }
    };

    /**
     * Map of color listeners per user id.
     * The first key will be the id of a user or UserHandle.USER_ALL - for wildcard listeners.
     * The secondary key will be the display id, which means which display the listener is
     * interested in.
     */
    private final SparseArray<SparseArray<RemoteCallbackList<IWallpaperManagerCallback>>>
            mColorsChangedListeners;
    // The currently bound home or home+lock wallpaper
    protected WallpaperData mLastWallpaper;
    // The currently bound lock screen only wallpaper, or null if none
    protected WallpaperData mLastLockWallpaper;

    /**
     * Flag set to true after reboot if the home wallpaper is waiting for the device to be unlocked.
     * This happens for wallpapers that are not direct-boot aware; they can only be rendered after
     * the user unlocks the device for the first time after a reboot. In the meantime, the default
     * wallpaper is shown instead.
     */
    private boolean mHomeWallpaperWaitingForUnlock;

    /**
     * Flag set to true after reboot if the lock wallpaper is waiting for the device to be unlocked.
     */
    private boolean mLockWallpaperWaitingForUnlock;

    private boolean mShuttingDown;

    /**
     * Name of the component used to display bitmap wallpapers from either the gallery or
     * built-in wallpapers.
     */
    private final ComponentName mImageWallpaper;

    /**
     * Default image wallpaper shall never changed after system service started, caching it when we
     * first read the image file.
     */
    private WallpaperColors mCacheDefaultImageWallpaperColors;

    /**
     * Name of the default wallpaper component; might be different from mImageWallpaper
     */
    private final ComponentName mDefaultWallpaperComponent;

    private final SparseArray<WallpaperData> mWallpaperMap = new SparseArray<WallpaperData>();
    private final SparseArray<WallpaperData> mLockWallpaperMap = new SparseArray<WallpaperData>();

    protected WallpaperData mFallbackWallpaper;

    private final SparseBooleanArray mUserRestorecon = new SparseBooleanArray();
    private int mCurrentUserId = UserHandle.USER_NULL;
    private boolean mInAmbientMode;
    private LocalColorRepository mLocalColorRepo = new LocalColorRepository();

    @VisibleForTesting
    final WallpaperDataParser mWallpaperDataParser;

    @VisibleForTesting
    final WallpaperDisplayHelper mWallpaperDisplayHelper;
    final WallpaperCropper mWallpaperCropper;

    private boolean supportsMultiDisplay(WallpaperConnection connection) {
        if (connection != null) {
            return connection.mInfo == null // This is image wallpaper
                    || connection.mInfo.supportsMultipleDisplays();
        }
        return false;
    }

    private void updateFallbackConnection() {
        if (mLastWallpaper == null || mFallbackWallpaper == null) return;
        final WallpaperConnection systemConnection = mLastWallpaper.connection;
        final WallpaperConnection fallbackConnection = mFallbackWallpaper.connection;
        if (fallbackConnection == null) {
            Slog.w(TAG, "Fallback wallpaper connection has not been created yet!!");
            return;
        }
        if (supportsMultiDisplay(systemConnection)) {
            if (fallbackConnection.mDisplayConnector.size() != 0) {
                fallbackConnection.forEachDisplayConnector(connector -> {
                    if (connector.mEngine != null) {
                        connector.disconnectLocked(fallbackConnection);
                    }
                });
                fallbackConnection.mDisplayConnector.clear();
            }
        } else {
            fallbackConnection.appendConnectorWithCondition(display ->
                    mWallpaperDisplayHelper.isUsableDisplay(display, fallbackConnection.mClientUid)
                            && display.getDisplayId() != DEFAULT_DISPLAY
                            && !fallbackConnection.containsDisplay(display.getDisplayId()));
            fallbackConnection.forEachDisplayConnector(connector -> {
                if (connector.mEngine == null) {
                    connector.connectLocked(fallbackConnection, mFallbackWallpaper);
                }
            });
        }
    }

    /**
     * Collect needed info for a display.
     */
    @VisibleForTesting
    final class DisplayConnector {
        final int mDisplayId;
        final Binder mToken = new Binder();
        IWallpaperEngine mEngine;
        boolean mDimensionsChanged;
        boolean mPaddingChanged;

        DisplayConnector(int displayId) {
            mDisplayId = displayId;
        }

        void ensureStatusHandled() {
            final DisplayData wpdData =
                    mWallpaperDisplayHelper.getDisplayDataOrCreate(mDisplayId);
            if (mDimensionsChanged) {
                try {
                    mEngine.setDesiredSize(wpdData.mWidth, wpdData.mHeight);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to set wallpaper dimensions", e);
                }
                mDimensionsChanged = false;
            }
            if (mPaddingChanged) {
                try {
                    mEngine.setDisplayPadding(wpdData.mPadding);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to set wallpaper padding", e);
                }
                mPaddingChanged = false;
            }
        }

        void connectLocked(WallpaperConnection connection, WallpaperData wallpaper) {
            if (connection.mService == null) {
                Slog.w(TAG, "WallpaperService is not connected yet");
                return;
            }
            TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
            t.traceBegin("WPMS.connectLocked-" + wallpaper.wallpaperComponent);
            if (DEBUG) Slog.v(TAG, "Adding window token: " + mToken);
            mWindowManagerInternal.addWindowToken(mToken, TYPE_WALLPAPER, mDisplayId,
                    null /* options */);
            mWindowManagerInternal.setWallpaperShowWhenLocked(
                    mToken, (wallpaper.mWhich & FLAG_LOCK) != 0);
            final DisplayData wpdData =
                    mWallpaperDisplayHelper.getDisplayDataOrCreate(mDisplayId);
            try {
                connection.mService.attach(connection, mToken, TYPE_WALLPAPER, false,
                        wpdData.mWidth, wpdData.mHeight,
                        wpdData.mPadding, mDisplayId, wallpaper.mWhich, connection.mInfo);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed attaching wallpaper on display", e);
                if (wallpaper != null && !wallpaper.wallpaperUpdating
                        && connection.getConnectedEngineSize() == 0) {
                    wallpaper.mBindSource = BindSource.CONNECT_LOCKED;
                    bindWallpaperComponentLocked(null /* componentName */, false /* force */,
                            false /* fromUser */, wallpaper, null /* reply */);
                }
            }
            t.traceEnd();
        }

        void disconnectLocked(WallpaperConnection connection) {
            if (DEBUG) Slog.v(TAG, "Removing window token: " + mToken);
            mWindowManagerInternal.removeWindowToken(mToken, false/* removeWindows */,
                    mDisplayId);
            try {
                if (connection.mService != null) {
                    connection.mService.detach(mToken);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "connection.mService.destroy() threw a RemoteException", e);
            }
            mEngine = null;
        }
    }

    class WallpaperConnection extends IWallpaperConnection.Stub
            implements ServiceConnection {

        /**
         * A map for each display.
         * Use {@link #getDisplayConnectorOrCreate(int displayId)} to ensure the display is usable.
         */
        private final SparseArray<DisplayConnector> mDisplayConnector = new SparseArray<>();

        /** Time in milliseconds until we expect the wallpaper to reconnect (unless we're in the
         *  middle of an update). If exceeded, the wallpaper gets reset to the system default. */
        private static final long WALLPAPER_RECONNECT_TIMEOUT_MS = 10000;
        private int mLmkLimitRebindRetries = LMK_RECONNECT_REBIND_RETRIES;

        final WallpaperInfo mInfo;
        IWallpaperService mService;
        WallpaperData mWallpaper;
        final int mClientUid;
        IRemoteCallback mReply;

        private Runnable mResetRunnable = () -> {
            synchronized (mLock) {
                if (mShuttingDown) {
                    // Don't expect wallpaper services to relaunch during shutdown
                    if (DEBUG_LIVE) {
                        Slog.i(TAG, "Ignoring relaunch timeout during shutdown");
                    }
                    return;
                }

                if (!mWallpaper.wallpaperUpdating && mWallpaper.userId == mCurrentUserId) {
                    Slog.w(TAG, "Wallpaper reconnect timed out for " + mWallpaper.wallpaperComponent
                            + ", reverting to built-in wallpaper!");
                    clearWallpaperLocked(mWallpaper.mWhich, mWallpaper.userId, false, null);
                }
            }
        };

        private Runnable mTryToRebindRunnable = this::tryToRebind;

        WallpaperConnection(WallpaperInfo info, WallpaperData wallpaper, int clientUid) {
            mInfo = info;
            mWallpaper = wallpaper;
            mClientUid = clientUid;
            initDisplayState();
        }

        private void initDisplayState() {
            // Do not initialize fallback wallpaper
            if (!mWallpaper.equals(mFallbackWallpaper)) {
                if (supportsMultiDisplay(this)) {
                    // The system wallpaper is image wallpaper or it can supports multiple displays.
                    appendConnectorWithCondition(display ->
                            mWallpaperDisplayHelper.isUsableDisplay(display, mClientUid));
                } else {
                    // The system wallpaper does not support multiple displays, so just attach it on
                    // default display.
                    mDisplayConnector.append(DEFAULT_DISPLAY,
                            new DisplayConnector(DEFAULT_DISPLAY));
                }
            }
        }

        private void appendConnectorWithCondition(Predicate<Display> tester) {
            final Display[] displays = mWallpaperDisplayHelper.getDisplays();
            for (Display display : displays) {
                if (tester.test(display)) {
                    final int displayId = display.getDisplayId();
                    final DisplayConnector connector = mDisplayConnector.get(displayId);
                    if (connector == null) {
                        mDisplayConnector.append(displayId, new DisplayConnector(displayId));
                    }
                }
            }
        }

        void forEachDisplayConnector(Consumer<DisplayConnector> action) {
            for (int i = mDisplayConnector.size() - 1; i >= 0; i--) {
                final DisplayConnector connector = mDisplayConnector.valueAt(i);
                action.accept(connector);
            }
        }

        int getConnectedEngineSize() {
            int engineSize = 0;
            for (int i = mDisplayConnector.size() - 1; i >= 0; i--) {
                final DisplayConnector connector = mDisplayConnector.valueAt(i);
                if (connector.mEngine != null) engineSize++;
            }
            return engineSize;
        }

        DisplayConnector getDisplayConnectorOrCreate(int displayId) {
            DisplayConnector connector = mDisplayConnector.get(displayId);
            if (connector == null) {
                if (mWallpaperDisplayHelper.isUsableDisplay(displayId, mClientUid)) {
                    connector = new DisplayConnector(displayId);
                    mDisplayConnector.append(displayId, connector);
                }
            }
            return connector;
        }

        boolean containsDisplay(int displayId) {
            return mDisplayConnector.get(displayId) != null;
        }

        void removeDisplayConnector(int displayId) {
            final DisplayConnector connector = mDisplayConnector.get(displayId);
            if (connector != null) {
                mDisplayConnector.remove(displayId);
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
            t.traceBegin("WPMS.onServiceConnected-" + name);
            synchronized (mLock) {
                if (mWallpaper.connection == this) {
                    mService = IWallpaperService.Stub.asInterface(service);
                    attachServiceLocked(this, mWallpaper);
                    // XXX should probably do saveSettingsLocked() later
                    // when we have an engine, but I'm not sure about
                    // locking there and anyway we always need to be able to
                    // recover if there is something wrong.
                    if (!mWallpaper.equals(mFallbackWallpaper)) {
                        saveSettingsLocked(mWallpaper.userId);
                    }
                    FgThread.getHandler().removeCallbacks(mResetRunnable);
                    mContext.getMainThreadHandler().removeCallbacks(mTryToRebindRunnable);
                    mContext.getMainThreadHandler().removeCallbacks(mDisconnectRunnable);
                }
            }
            t.traceEnd();
        }

        @Override
        public void onLocalWallpaperColorsChanged(RectF area, WallpaperColors colors,
                int displayId) {
            forEachDisplayConnector(displayConnector -> {
                Consumer<ILocalWallpaperColorConsumer> callback = cb -> {
                    try {
                        cb.onColorsChanged(area, colors);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to notify local color callbacks", e);
                    }
                };
                synchronized (mLock) {
                    // it is safe to make an IPC call since it is one way (returns immediately)
                    mLocalColorRepo.forEachCallback(callback, area, displayId);
                }
            });
        }


        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Slog.w(TAG, "Wallpaper service gone: " + name);
                if (!Objects.equals(name, mWallpaper.wallpaperComponent)) {
                    Slog.e(TAG, "Does not match expected wallpaper component "
                            + mWallpaper.wallpaperComponent);
                }
                mService = null;
                forEachDisplayConnector(connector -> connector.mEngine = null);
                if (mWallpaper.connection == this) {
                    // There is an inherent ordering race between this callback and the
                    // package monitor that receives notice that a package is being updated,
                    // so we cannot quite trust at this moment that we know for sure that
                    // this is not an update.  If we think this is a genuine non-update
                    // wallpaper outage, we do our "wait for reset" work as a continuation,
                    // a short time in the future, specifically to allow any pending package
                    // update message on this same looper thread to be processed.
                    if (!mWallpaper.wallpaperUpdating) {
                        mContext.getMainThreadHandler().postDelayed(mDisconnectRunnable,
                                1000);
                    }
                }
            }
        }

        private void scheduleTimeoutLocked() {
            // If we didn't reset it right away, do so after we couldn't connect to
            // it for an extended amount of time to avoid having a black wallpaper.
            final Handler fgHandler = FgThread.getHandler();
            fgHandler.removeCallbacks(mResetRunnable);
            fgHandler.postDelayed(mResetRunnable, WALLPAPER_RECONNECT_TIMEOUT_MS);
            if (DEBUG_LIVE) {
                Slog.i(TAG,
                        "Started wallpaper reconnect timeout for " + mWallpaper.wallpaperComponent);
            }
        }

        private void tryToRebind() {
            synchronized (mLock) {
                if (mWallpaper.wallpaperUpdating) {
                    return;
                }
                final ComponentName wpService = mWallpaper.wallpaperComponent;
                // The broadcast of package update could be delayed after service disconnected. Try
                // to re-bind the service for 10 seconds.
                mWallpaper.mBindSource = BindSource.CONNECTION_TRY_TO_REBIND;
                if (bindWallpaperComponentLocked(
                        wpService, true, false, mWallpaper, null)) {
                    mWallpaper.connection.scheduleTimeoutLocked();
                } else if (SystemClock.uptimeMillis() - mWallpaper.lastDiedTime
                        < WALLPAPER_RECONNECT_TIMEOUT_MS) {
                    // Bind fail without timeout, schedule rebind
                    Slog.w(TAG, "Rebind fail! Try again later");
                    mContext.getMainThreadHandler().postDelayed(mTryToRebindRunnable, 1000);
                } else {
                    // Timeout
                    Slog.w(TAG, "Reverting to built-in wallpaper!");
                    clearWallpaperLocked(mWallpaper.mWhich, mWallpaper.userId, false, null);
                    final String flattened = wpService.flattenToString();
                    EventLog.writeEvent(EventLogTags.WP_WALLPAPER_CRASHED,
                            flattened.substring(0, Math.min(flattened.length(),
                                    MAX_WALLPAPER_COMPONENT_LOG_LENGTH)));
                }
            }
        }

        private Runnable mDisconnectRunnable = () -> {
            synchronized (mLock) {
                // The wallpaper disappeared.  If this isn't a system-default one, track
                // crashes and fall back to default if it continues to misbehave.
                if (this == mWallpaper.connection) {
                    final ComponentName wpService = mWallpaper.wallpaperComponent;
                    if (!mWallpaper.wallpaperUpdating
                            && mWallpaper.userId == mCurrentUserId
                            && !Objects.equals(mDefaultWallpaperComponent, wpService)
                            && !Objects.equals(mImageWallpaper, wpService)) {
                        List<ApplicationExitInfo> reasonList =
                                mActivityManager.getHistoricalProcessExitReasons(
                                wpService.getPackageName(), 0, 1);
                        int exitReason = ApplicationExitInfo.REASON_UNKNOWN;
                        if (reasonList != null && !reasonList.isEmpty()) {
                            ApplicationExitInfo info = reasonList.get(0);
                            exitReason = info.getReason();
                        }
                        Slog.d(TAG, "exitReason: " + exitReason);
                        // If exit reason is LOW_MEMORY_KILLER
                        // delay the mTryToRebindRunnable for 10s
                        if (exitReason == ApplicationExitInfo.REASON_LOW_MEMORY) {
                            if (isRunningOnLowMemory()) {
                                Slog.i(TAG, "Rebind is delayed due to lmk");
                                mContext.getMainThreadHandler().postDelayed(mTryToRebindRunnable,
                                        LMK_RECONNECT_DELAY_MS);
                                mLmkLimitRebindRetries = LMK_RECONNECT_REBIND_RETRIES;
                            } else {
                                if (mLmkLimitRebindRetries <= 0) {
                                    Slog.w(TAG, "Reverting to built-in wallpaper due to lmk!");
                                    clearWallpaperLocked(
                                            mWallpaper.mWhich, mWallpaper.userId, false, null);
                                    mLmkLimitRebindRetries = LMK_RECONNECT_REBIND_RETRIES;
                                    return;
                                }
                                mLmkLimitRebindRetries--;
                                mContext.getMainThreadHandler().postDelayed(mTryToRebindRunnable,
                                        LMK_RECONNECT_DELAY_MS);
                            }
                        } else {
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
                                clearWallpaperLocked(FLAG_SYSTEM, mWallpaper.userId, false, null);
                            } else {
                                mWallpaper.lastDiedTime = SystemClock.uptimeMillis();
                                tryToRebind();
                            }
                        }
                    }
                } else {
                    if (DEBUG_LIVE) {
                        Slog.i(TAG, "Wallpaper changed during disconnect tracking; ignoring");
                    }
                }
            }
        };

        private boolean isRunningOnLowMemory() {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            mActivityManager.getMemoryInfo(memoryInfo);
            double availableMBsInPercentage = memoryInfo.availMem / (double)memoryInfo.totalMem *
                    100.0;
            return availableMBsInPercentage < LMK_LOW_THRESHOLD_MEMORY_PERCENTAGE;
        }

        /**
         * Called by a live wallpaper if its colors have changed.
         * @param primaryColors representation of wallpaper primary colors
         * @param displayId for which display
         */
        @Override
        public void onWallpaperColorsChanged(WallpaperColors primaryColors, int displayId) {
            synchronized (mLock) {
                // Do not broadcast changes on ImageWallpaper since it's handled
                // internally by this class.
                if (mImageWallpaper.equals(mWallpaper.wallpaperComponent)) {
                    return;
                }
                mWallpaper.primaryColors = primaryColors;
            }
            notifyWallpaperColorsChangedOnDisplay(mWallpaper, displayId);
        }

        @Override
        public void attachEngine(IWallpaperEngine engine, int displayId) {
            synchronized (mLock) {
                final DisplayConnector connector = getDisplayConnectorOrCreate(displayId);
                if (connector == null) {
                    throw new IllegalStateException("Connector has already been destroyed");
                }
                connector.mEngine = engine;
                connector.ensureStatusHandled();

                // TODO(multi-display) TBD.
                if (mInfo != null && mInfo.supportsAmbientMode() && displayId == DEFAULT_DISPLAY) {
                    try {
                        connector.mEngine.setInAmbientMode(mInAmbientMode, 0L /* duration */);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to set ambient mode state", e);
                    }
                }
                try {
                    // This will trigger onComputeColors in the wallpaper engine.
                    // It's fine to be locked in here since the binder is oneway.
                    connector.mEngine.requestWallpaperColors();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to request wallpaper colors", e);
                }

                List<RectF> areas = mLocalColorRepo.getAreasByDisplayId(displayId);
                if (areas != null && areas.size() != 0) {
                    try {
                        connector.mEngine.addLocalColorsAreas(areas);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to register local colors areas", e);
                    }
                }

                if (mWallpaper.mWallpaperDimAmount != 0f) {
                    try {
                        connector.mEngine.applyDimming(mWallpaper.mWallpaperDimAmount);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to dim wallpaper", e);
                    }
                }
            }
        }

        @Override
        public void engineShown(IWallpaperEngine engine) {
            synchronized (mLock) {
                if (mReply != null) {
                    TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
                    t.traceBegin("WPMS.mReply.sendResult");
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        mReply.sendResult(null);
                    } catch (RemoteException e) {
                        Slog.d(TAG, "Failed to send callback!", e);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                    t.traceEnd();
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

    /**
     * Tracks wallpaper information during a wallpaper change and does bookkeeping afterwards to
     * update Engine destination, wallpaper maps, and last wallpaper.
     */
    class WallpaperDestinationChangeHandler {
        final WallpaperData mNewWallpaper;
        final WallpaperData mOriginalSystem;

        WallpaperDestinationChangeHandler(WallpaperData newWallpaper) {
            this.mNewWallpaper = newWallpaper;
            WallpaperData sysWp = mWallpaperMap.get(newWallpaper.userId);
            mOriginalSystem = new WallpaperData(sysWp);
        }

        void complete() {
            // Only changes from home+lock to just home or lock need attention
            if (mNewWallpaper.mSystemWasBoth) {
                if (DEBUG) {
                    Slog.v(TAG, "Handling change from system+lock wallpaper");
                }
                if (mNewWallpaper.mWhich == FLAG_SYSTEM) {
                    // New wp is system only, so old system+lock is now lock only
                    final boolean originalIsStatic = mImageWallpaper.equals(
                            mOriginalSystem.wallpaperComponent);
                    if (originalIsStatic) {
                        // Static wp: image file rename has already been tried via
                        // migrateStaticSystemToLockWallpaperLocked() and added to the lock wp map
                        // if successful.
                        WallpaperData lockWp = mLockWallpaperMap.get(mNewWallpaper.userId);
                        if (lockWp != null && mOriginalSystem.connection != null) {
                            // Successful rename, set old system+lock to the pending lock wp
                            if (DEBUG) {
                                Slog.v(TAG, "static system+lock to system success");
                            }
                            lockWp.wallpaperComponent =
                                    mOriginalSystem.wallpaperComponent;
                            lockWp.connection = mOriginalSystem.connection;
                            lockWp.connection.mWallpaper = lockWp;
                            mOriginalSystem.mWhich = FLAG_LOCK;
                            updateEngineFlags(mOriginalSystem);
                        } else {
                            // Failed rename, use current system wp for both
                            if (DEBUG) {
                                Slog.v(TAG, "static system+lock to system failure");
                            }
                            WallpaperData currentSystem = mWallpaperMap.get(mNewWallpaper.userId);
                            currentSystem.mWhich = FLAG_SYSTEM | FLAG_LOCK;
                            updateEngineFlags(currentSystem);
                            mLockWallpaperMap.remove(mNewWallpaper.userId);
                        }
                    } else {
                        // Live wp: just update old system+lock to lock only
                        if (DEBUG) {
                            Slog.v(TAG, "live system+lock to system success");
                        }
                        mOriginalSystem.mWhich = FLAG_LOCK;
                        updateEngineFlags(mOriginalSystem);
                        mLockWallpaperMap.put(mNewWallpaper.userId, mOriginalSystem);
                        mLastLockWallpaper = mOriginalSystem;
                    }
                } else if (mNewWallpaper.mWhich == FLAG_LOCK) {
                    // New wp is lock only, so old system+lock is now system only
                    if (DEBUG) {
                        Slog.v(TAG, "system+lock to lock");
                    }
                    WallpaperData currentSystem = mWallpaperMap.get(mNewWallpaper.userId);
                    if (currentSystem.wallpaperId == mOriginalSystem.wallpaperId) {
                        currentSystem.mWhich = FLAG_SYSTEM;
                        updateEngineFlags(currentSystem);
                    }
                }
            }
            saveSettingsLocked(mNewWallpaper.userId);

            if (DEBUG) {
                Slog.v(TAG, "--- wallpaper changed --");
                Slog.v(TAG, "new sysWp: " + mWallpaperMap.get(mCurrentUserId));
                Slog.v(TAG, "new lockWp: " + mLockWallpaperMap.get(mCurrentUserId));
                Slog.v(TAG, "new lastWp: " + mLastWallpaper);
                Slog.v(TAG, "new lastLockWp: " + mLastLockWallpaper);
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
                for (WallpaperData wallpaper: getWallpapers()) {
                    final ComponentName wpService = wallpaper.wallpaperComponent;
                    if (wpService != null && wpService.getPackageName().equals(packageName)) {
                        if (DEBUG_LIVE) {
                            Slog.i(TAG, "Wallpaper " + wpService + " update has finished");
                        }
                        wallpaper.wallpaperUpdating = false;
                        clearWallpaperComponentLocked(wallpaper);
                        wallpaper.mBindSource = BindSource.PACKAGE_UPDATE_FINISHED;
                        if (!bindWallpaperComponentLocked(wpService, false, false,
                                wallpaper, null)) {
                            Slog.w(TAG, "Wallpaper " + wpService
                                    + " no longer available; reverting to default");
                            clearWallpaperLocked(wallpaper.mWhich, wallpaper.userId, false, null);
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
                for (WallpaperData wallpaper: getWallpapers()) {
                    if (wallpaper.wallpaperComponent != null
                            && wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        doPackagesChangedLocked(true, wallpaper);
                    }
                }
            }
        }

        @Override
        public void onPackageUpdateStarted(String packageName, int uid) {
            synchronized (mLock) {
                if (mCurrentUserId != getChangingUserId()) {
                    return;
                }
                for (WallpaperData wallpaper: getWallpapers()) {
                    if (wallpaper.wallpaperComponent != null
                            && wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        if (DEBUG_LIVE) {
                            Slog.i(TAG, "Wallpaper service " + wallpaper.wallpaperComponent
                                    + " is updating");
                        }
                        wallpaper.wallpaperUpdating = true;
                        if (wallpaper.connection != null) {
                            FgThread.getHandler().removeCallbacks(
                                    wallpaper.connection.mResetRunnable);
                        }
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
                for (WallpaperData wallpaper: getWallpapers()) {
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
                for (WallpaperData wallpaper: getWallpapers()) {
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
                        clearWallpaperLocked(wallpaper.mWhich, wallpaper.userId, false, null);
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
                    clearWallpaperLocked(wallpaper.mWhich, wallpaper.userId, false, null);
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

    @VisibleForTesting
    WallpaperData getCurrentWallpaperData(@SetWallpaperFlags int which, int userId) {
        synchronized (mLock) {
            final SparseArray<WallpaperData> wallpaperDataMap =
                    which == FLAG_SYSTEM ? mWallpaperMap : mLockWallpaperMap;
            return wallpaperDataMap.get(userId);
        }
    }

    public WallpaperManagerService(Context context) {
        if (DEBUG) Slog.v(TAG, "WallpaperService startup");
        mContext = context;
        if (Flags.bindWallpaperServiceOnItsOwnThreadDuringAUserSwitch()) {
            mHandlerThread = new ServiceThread(TAG, THREAD_PRIORITY_FOREGROUND, true /*allowIo*/);
            mHandlerThread.start();
        } else {
            mHandlerThread = null;
        }
        mShuttingDown = false;
        mImageWallpaper = ComponentName.unflattenFromString(
                context.getResources().getString(R.string.image_wallpaper_component));
        ComponentName defaultComponent = WallpaperManager.getCmfDefaultWallpaperComponent(context);
        mDefaultWallpaperComponent = defaultComponent == null ? mImageWallpaper : defaultComponent;
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mIPackageManager = AppGlobals.getPackageManager();
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        dm.registerDisplayListener(mDisplayListener, null /* handler */);
        mWallpaperDisplayHelper = new WallpaperDisplayHelper(dm, mWindowManagerInternal);
        mWallpaperCropper = new WallpaperCropper(mWallpaperDisplayHelper);
        mActivityManager = mContext.getSystemService(ActivityManager.class);

        if (mContext.getResources().getBoolean(
                R.bool.config_pauseWallpaperRenderWhenStateChangeEnabled)) {
            // Pause wallpaper rendering engine as soon as a performance impacted app is launched.
            final String[] pauseRenderList = mContext.getResources().getStringArray(
                    R.array.pause_wallpaper_render_when_state_change);
            final IntArray pauseRenderUids = new IntArray();
            for (String pauseRenderApp : pauseRenderList) {
                try {
                    int uid = mContext.getPackageManager().getApplicationInfo(
                            pauseRenderApp, 0).uid;
                    pauseRenderUids.add(uid);
                } catch (Exception e) {
                    Slog.e(TAG, e.toString());
                }
            }
            if (pauseRenderUids.size() > 0) {
                try {
                    ActivityManager.getService().registerUidObserverForUids(new UidObserver() {
                        @Override
                        public void onUidStateChanged(int uid, int procState, long procStateSeq,
                                int capability) {
                            pauseOrResumeRenderingImmediately(
                                    procState == ActivityManager.PROCESS_STATE_TOP);
                        }
                    }, ActivityManager.UID_OBSERVER_PROCSTATE,
                            ActivityManager.PROCESS_STATE_TOP, "android",
                            pauseRenderUids.toArray());
                } catch (RemoteException e) {
                    Slog.e(TAG, e.toString());
                }
            }
        }

        mMonitor = new MyPackageMonitor();
        mColorsChangedListeners = new SparseArray<>();
        mWallpaperDataParser = new WallpaperDataParser(mContext, mWallpaperDisplayHelper,
                mWallpaperCropper);
        mIsMultiCropEnabled = multiCrop();
        LocalServices.addService(WallpaperManagerInternal.class, new LocalService());
    }

    private final class LocalService extends WallpaperManagerInternal {
        @Override
        public void onDisplayReady(int displayId) {
            onDisplayReadyInternal(displayId);
        }

        @Override
        public void onScreenTurnedOn(int displayId) {
            notifyScreenTurnedOn(displayId);
        }
        @Override
        public void onScreenTurningOn(int displayId) {
            notifyScreenTurningOn(displayId);
        }

        @Override
        public void onKeyguardGoingAway() {
            notifyKeyguardGoingAway();
        }
    }

    void initialize() {
        mMonitor.register(mContext, null, UserHandle.ALL, true);
        getWallpaperDir(UserHandle.USER_SYSTEM).mkdirs();

        // Initialize state from the persistent store, then guarantee that the
        // WallpaperData for the system imagery is instantiated & active, creating
        // it from defaults if necessary.
        loadSettingsLocked(UserHandle.USER_SYSTEM, false, FLAG_SYSTEM | FLAG_LOCK);
        getWallpaperSafeLocked(UserHandle.USER_SYSTEM, FLAG_SYSTEM);
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
        initialize();

        WallpaperData wallpaper = mWallpaperMap.get(UserHandle.USER_SYSTEM);
        // If we think we're going to be using the system image wallpaper imagery, make
        // sure we have something to render
        if (mImageWallpaper.equals(wallpaper.nextWallpaperComponent)) {
            // No crop file? Make sure we've finished the processing sequence if necessary
            if (!wallpaper.cropExists()) {
                if (DEBUG) {
                    Slog.i(TAG, "No crop; regenerating from source");
                }
                mWallpaperCropper.generateCrop(wallpaper);
            }
            // Still nothing?  Fall back to default.
            if (!wallpaper.cropExists()) {
                if (DEBUG) {
                    Slog.i(TAG, "Unable to regenerate crop; resetting");
                }
                clearWallpaperLocked(wallpaper.mWhich, UserHandle.USER_SYSTEM, false, null);
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

        final IntentFilter shutdownFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                    if (DEBUG) {
                        Slog.i(TAG, "Shutting down");
                    }
                    synchronized (mLock) {
                        mShuttingDown = true;
                    }
                }
            }
        }, shutdownFilter);

        try {
            ActivityManager.getService().registerUserSwitchObserver(
                    new UserSwitchObserver() {
                        @Override
                        public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                            errorCheck(newUserId);
                            switchUser(newUserId, reply);
                        }
                    }, TAG);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
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

    @Override
    public void onBootPhase(int phase) {
        // If someone set too large jpg file as wallpaper, system_server may be killed by lmk in
        // generateCrop(), so we create a file in generateCrop() before ImageDecoder starts working
        // and delete this file after ImageDecoder finishing. If the specific file exists, that
        // means ImageDecoder can't handle the original wallpaper file, in order to avoid
        // system_server restart again and again and rescue party will trigger factory reset,
        // so we reset default wallpaper in case system_server is trapped into a restart loop.
        errorCheck(UserHandle.USER_SYSTEM);

        if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
            systemReady();
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            switchUser(UserHandle.USER_SYSTEM, null);
        }
    }

    private static final Map<Integer, String> sWallpaperType = Map.of(
            FLAG_SYSTEM, RECORD_FILE,
            FLAG_LOCK, RECORD_LOCK_FILE);

    private void errorCheck(int userID) {
        sWallpaperType.forEach((type, filename) -> {
            final File record = new File(getWallpaperDir(userID), filename);
            if (record.exists()) {
                Slog.w(TAG, "User:" + userID + ", wallpaper tyep = " + type
                        + ", wallpaper fail detect!! reset to default wallpaper");
                clearWallpaperBitmaps(userID, type);
                record.delete();
            }
        });
    }

    private void clearWallpaperBitmaps(int userID, int wallpaperType) {
        final WallpaperData wallpaper = new WallpaperData(userID, wallpaperType);
        clearWallpaperBitmaps(wallpaper);
    }

    private boolean clearWallpaperBitmaps(WallpaperData wallpaper) {
        boolean sourceExists = wallpaper.sourceExists();
        boolean cropExists = wallpaper.cropExists();
        if (sourceExists) wallpaper.getWallpaperFile().delete();
        if (cropExists) wallpaper.getCropFile().delete();
        return sourceExists || cropExists;
    }

    @Override
    public void onUnlockUser(final int userId) {
        synchronized (mLock) {
            if (mCurrentUserId == userId) {
                if (mHomeWallpaperWaitingForUnlock) {
                    final WallpaperData systemWallpaper =
                            getWallpaperSafeLocked(userId, FLAG_SYSTEM);
                    systemWallpaper.mBindSource = BindSource.SWITCH_WALLPAPER_UNLOCK_USER;
                    switchWallpaper(systemWallpaper, null);
                    // TODO(b/278261563): call notifyCallbacksLocked inside switchWallpaper
                    notifyCallbacksLocked(systemWallpaper);
                }
                if (mLockWallpaperWaitingForUnlock) {
                    final WallpaperData lockWallpaper =
                            getWallpaperSafeLocked(userId, FLAG_LOCK);
                    lockWallpaper.mBindSource = BindSource.SWITCH_WALLPAPER_UNLOCK_USER;
                    switchWallpaper(lockWallpaper, null);
                    notifyCallbacksLocked(lockWallpaper);
                }

                // Make sure that the SELinux labeling of all the relevant files is correct.
                // This corrects for mislabeling bugs that might have arisen from move-to
                // operations involving the wallpaper files.  This isn't timing-critical,
                // so we do it in the background to avoid holding up the user unlock operation.
                if (!mUserRestorecon.get(userId)) {
                    mUserRestorecon.put(userId, true);
                    Runnable relabeler = () -> {
                        final TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
                        t.traceBegin("Wallpaper_selinux_restorecon-" + userId);
                        try {
                            for (File file: WallpaperUtils.getWallpaperFiles(userId)) {
                                if (file.exists()) {
                                    SELinux.restorecon(file);
                                }
                            }
                        } finally {
                            t.traceEnd();
                        }
                    };
                    BackgroundThread.getHandler().post(relabeler);
                }
            }
        }
    }

    void onRemoveUser(int userId) {
        if (userId < 1) return;

        synchronized (mLock) {
            stopObserversLocked(userId);
            WallpaperUtils.getWallpaperFiles(userId).forEach(File::delete);
            mUserRestorecon.delete(userId);
        }
    }

    void switchUser(int userId, IRemoteCallback reply) {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
        t.traceBegin("Wallpaper_switch-user-" + userId);
        try {
            final WallpaperData systemWallpaper;
            final WallpaperData lockWallpaper;
            synchronized (mLock) {
                if (mCurrentUserId == userId) {
                    return;
                }
                mCurrentUserId = userId;
                systemWallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
                lockWallpaper = systemWallpaper.mWhich == (FLAG_LOCK | FLAG_SYSTEM)
                        ? systemWallpaper : getWallpaperSafeLocked(userId, FLAG_LOCK);

                // Not started watching yet, in case wallpaper data was loaded for other reasons.
                if (systemWallpaper.wallpaperObserver == null) {
                    systemWallpaper.wallpaperObserver = new WallpaperObserver(systemWallpaper);
                    systemWallpaper.wallpaperObserver.startWatching();
                }
                if (lockWallpaper != systemWallpaper)  {
                    switchWallpaper(lockWallpaper, null);
                }
                switchWallpaper(systemWallpaper, reply);
            }

            // Offload color extraction to another thread since switchUser will be called
            // from the main thread.
            FgThread.getHandler().post(() -> {
                notifyWallpaperColorsChanged(systemWallpaper);
                if (lockWallpaper != systemWallpaper) notifyWallpaperColorsChanged(lockWallpaper);
                notifyWallpaperColorsChanged(mFallbackWallpaper);
            });
        } finally {
            t.traceEnd();
        }
    }

    void switchWallpaper(WallpaperData wallpaper, IRemoteCallback reply) {
        synchronized (mLock) {
            if ((wallpaper.mWhich & FLAG_SYSTEM) != 0) mHomeWallpaperWaitingForUnlock = false;
            if ((wallpaper.mWhich & FLAG_LOCK) != 0) mLockWallpaperWaitingForUnlock = false;

            final ComponentName cname = wallpaper.wallpaperComponent != null ?
                    wallpaper.wallpaperComponent : wallpaper.nextWallpaperComponent;
            if (!bindWallpaperComponentLocked(cname, true, false, wallpaper, reply)) {
                // We failed to bind the desired wallpaper, but that might
                // happen if the wallpaper isn't direct-boot aware
                ServiceInfo si = null;
                try {
                    si = mIPackageManager.getServiceInfo(cname,
                            PackageManager.MATCH_DIRECT_BOOT_UNAWARE, wallpaper.userId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failure starting previous wallpaper; clearing", e);
                }
                onSwitchWallpaperFailLocked(wallpaper, reply, si);
            }
        }
    }

    /**
     * Fallback method if a wallpaper fails to load on boot or after a user switch.
     */
    private void onSwitchWallpaperFailLocked(
            WallpaperData wallpaper, IRemoteCallback reply, ServiceInfo serviceInfo) {

        if (serviceInfo == null) {
            clearWallpaperLocked(wallpaper.mWhich, wallpaper.userId, false, reply);
            return;
        }
        Slog.w(TAG, "Wallpaper isn't direct boot aware; using fallback until unlocked");
        // We might end up persisting the current wallpaper data
        // while locked, so pretend like the component was actually
        // bound into place
        wallpaper.wallpaperComponent = wallpaper.nextWallpaperComponent;
        final WallpaperData fallback = new WallpaperData(wallpaper.userId, wallpaper.mWhich);

        // files from the previous static wallpaper may still be stored in memory.
        // delete them in order to show the default wallpaper.
        clearWallpaperBitmaps(wallpaper);

        fallback.mBindSource = BindSource.SWITCH_WALLPAPER_FAILURE;
        bindWallpaperComponentLocked(mImageWallpaper, true, false, fallback, reply);
        if ((wallpaper.mWhich & FLAG_SYSTEM) != 0) mHomeWallpaperWaitingForUnlock = true;
        if ((wallpaper.mWhich & FLAG_LOCK) != 0) mLockWallpaperWaitingForUnlock = true;
    }

    @Override
    public void clearWallpaper(String callingPackage, int which, int userId) {
        if (DEBUG) Slog.v(TAG, "clearWallpaper: " + which);
        checkPermission(android.Manifest.permission.SET_WALLPAPER);
        if (!isWallpaperSupported(callingPackage) || !isSetWallpaperAllowed(callingPackage)) {
            return;
        }
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "clearWallpaper", null);

        WallpaperData data = null;
        synchronized (mLock) {
            boolean fromForeground = isFromForegroundApp(callingPackage);
            clearWallpaperLocked(which, userId, fromForeground, null);

            if (which == FLAG_LOCK) {
                data = mLockWallpaperMap.get(userId);
            }
            if (which == FLAG_SYSTEM || data == null) {
                data = mWallpaperMap.get(userId);
            }
        }
    }

    private void clearWallpaperLocked(int which, int userId, boolean fromForeground,
            IRemoteCallback reply) {

        // Might need to bring it in the first time to establish our rewrite
        if (!mWallpaperMap.contains(userId)) {
            loadSettingsLocked(userId, false, FLAG_LOCK | FLAG_SYSTEM);
        }
        final WallpaperData wallpaper = mWallpaperMap.get(userId);
        final WallpaperData lockWallpaper = mLockWallpaperMap.get(userId);
        if (which == FLAG_LOCK && lockWallpaper == null) {
            // It's already gone; we're done.
            if (DEBUG) {
                Slog.i(TAG, "Lock wallpaper already cleared");
            }
            return;
        }

        RuntimeException e = null;
        try {
            if (userId != mCurrentUserId && !hasCrossUserPermission()) return;

            final ComponentName component;
            final int finalWhich;

            if ((which & FLAG_LOCK) > 0 && lockWallpaper != null) {
                clearWallpaperBitmaps(lockWallpaper);
            }
            if ((which & FLAG_SYSTEM) > 0) {
                clearWallpaperBitmaps(wallpaper);
            }

            // lock only case: set the system wallpaper component to both screens
            if (which == FLAG_LOCK) {
                component = wallpaper.wallpaperComponent;
                finalWhich = FLAG_LOCK | FLAG_SYSTEM;
            } else {
                component = null;
                finalWhich = which;
            }

            // except for the lock case (for which we keep the system wallpaper as-is), force rebind
            boolean force = which != FLAG_LOCK;
            boolean success = withCleanCallingIdentity(() -> setWallpaperComponentInternal(
                    component, finalWhich, userId, force, fromForeground, reply));
            if (success) return;
        } catch (IllegalArgumentException e1) {
            e = e1;
        }

        // This can happen if the default wallpaper component doesn't
        // exist. This should be a system configuration problem, but
        // let's not let it crash the system and just live with no
        // wallpaper.
        Slog.e(TAG, "Default wallpaper component not found!", e);
        withCleanCallingIdentity(() -> clearWallpaperComponentLocked(wallpaper));
        if (reply != null) {
            try {
                reply.sendResult(null);
            } catch (RemoteException e1) {
                Slog.w(TAG, "Failed to notify callback after wallpaper clear", e1);
            }
        }
    }

    private boolean hasCrossUserPermission() {
        final int interactPermission =
                mContext.checkCallingPermission(INTERACT_ACROSS_USERS_FULL);
        return interactPermission == PERMISSION_GRANTED;
    }

    @Override
    public boolean hasNamedWallpaper(String name) {
        final int callingUser = UserHandle.getCallingUserId();
        final boolean allowCrossUser = hasCrossUserPermission();
        if (DEBUG) {
            Slog.d(TAG, "hasNamedWallpaper() caller " + Binder.getCallingUid()
                    + " cross-user?: " + allowCrossUser);
        }

        synchronized (mLock) {
            List<UserInfo> users;
            final long ident = Binder.clearCallingIdentity();
            try {
                users = ((UserManager) mContext.getSystemService(Context.USER_SERVICE)).getUsers();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            for (UserInfo user: users) {
                if (!allowCrossUser && callingUser != user.id) {
                    // No cross-user information for callers without permission
                    continue;
                }

                // ignore profiles
                if (user.isProfile()) {
                    continue;
                }
                WallpaperData wd = mWallpaperMap.get(user.id);
                if (wd == null) {
                    // User hasn't started yet, so load their settings to peek at the wallpaper
                    loadSettingsLocked(user.id, false, FLAG_SYSTEM | FLAG_LOCK);
                    wd = mWallpaperMap.get(user.id);
                }
                if (wd != null && name.equals(wd.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sets the dimension hint for the wallpaper. These hints indicate the desired
     * minimum width and height for the wallpaper in a particular display.
     */
    public void setDimensionHints(int width, int height, String callingPackage, int displayId)
            throws RemoteException {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);
        if (!isWallpaperSupported(callingPackage)) {
            return;
        }

        // Make sure both width and height are not larger than max texture size.
        width = Math.min(width, GLHelper.getMaxTextureSize());
        height = Math.min(height, GLHelper.getMaxTextureSize());

        synchronized (mLock) {
            int userId = UserHandle.getCallingUserId();
            WallpaperData wallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be > 0");
            }

            if (!mWallpaperDisplayHelper.isValidDisplay(displayId)) {
                throw new IllegalArgumentException("Cannot find display with id=" + displayId);
            }

            final DisplayData wpdData = mWallpaperDisplayHelper.getDisplayDataOrCreate(displayId);
            if (width != wpdData.mWidth || height != wpdData.mHeight) {
                wpdData.mWidth = width;
                wpdData.mHeight = height;
                if (displayId == DEFAULT_DISPLAY) saveSettingsLocked(userId);
                if (mCurrentUserId != userId) return; // Don't change the properties now
                if (wallpaper.connection != null) {
                    final DisplayConnector connector = wallpaper.connection
                            .getDisplayConnectorOrCreate(displayId);
                    final IWallpaperEngine engine = connector != null ? connector.mEngine : null;
                    if (engine != null) {
                        try {
                            engine.setDesiredSize(width, height);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to set desired size", e);
                        }
                        notifyCallbacksLocked(wallpaper);
                    } else if (wallpaper.connection.mService != null && connector != null) {
                        // We've attached to the service but the engine hasn't attached back to us
                        // yet. This means it will be created with the previous dimensions, so we
                        // need to update it to the new dimensions once it attaches.
                        connector.mDimensionsChanged = true;
                    }
                }
            }
        }
    }

    /**
     * Returns the desired minimum width for the wallpaper in a particular display.
     */
    public int getWidthHint(int displayId) throws RemoteException {
        synchronized (mLock) {
            if (!mWallpaperDisplayHelper.isValidDisplay(displayId)) {
                throw new IllegalArgumentException("Cannot find display with id=" + displayId);
            }
            WallpaperData wallpaper = mWallpaperMap.get(UserHandle.getCallingUserId());
            if (wallpaper != null) {
                final DisplayData wpdData =
                        mWallpaperDisplayHelper.getDisplayDataOrCreate(displayId);
                return wpdData.mWidth;
            } else {
                return 0;
            }
        }
    }

    /**
     * Returns the desired minimum height for the wallpaper in a particular display.
     */
    public int getHeightHint(int displayId) throws RemoteException {
        synchronized (mLock) {
            if (!mWallpaperDisplayHelper.isValidDisplay(displayId)) {
                throw new IllegalArgumentException("Cannot find display with id=" + displayId);
            }
            WallpaperData wallpaper = mWallpaperMap.get(UserHandle.getCallingUserId());
            if (wallpaper != null) {
                final DisplayData wpdData =
                        mWallpaperDisplayHelper.getDisplayDataOrCreate(displayId);
                return wpdData.mHeight;
            } else {
                return 0;
            }
        }
    }

    /**
     * Sets extra padding that we would like the wallpaper to have outside of the display.
     */
    public void setDisplayPadding(Rect padding, String callingPackage, int displayId) {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);
        if (!isWallpaperSupported(callingPackage)) {
            return;
        }
        synchronized (mLock) {
            if (!mWallpaperDisplayHelper.isValidDisplay(displayId)) {
                throw new IllegalArgumentException("Cannot find display with id=" + displayId);
            }
            int userId = UserHandle.getCallingUserId();
            WallpaperData wallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
            if (padding.left < 0 || padding.top < 0 || padding.right < 0 || padding.bottom < 0) {
                throw new IllegalArgumentException("padding must be positive: " + padding);
            }

            int maxSize = mWallpaperDisplayHelper.getMaximumSizeDimension(displayId);

            final int paddingWidth = padding.left + padding.right;
            final int paddingHeight = padding.top + padding.bottom;
            if (paddingWidth > maxSize) {
                throw new IllegalArgumentException("padding width " + paddingWidth
                        + " exceeds max width " + maxSize);
            }
            if (paddingHeight > maxSize) {
                throw new IllegalArgumentException("padding height " + paddingHeight
                        + " exceeds max height " + maxSize);
            }

            final DisplayData wpdData = mWallpaperDisplayHelper.getDisplayDataOrCreate(displayId);
            if (!padding.equals(wpdData.mPadding)) {
                wpdData.mPadding.set(padding);
                if (displayId == DEFAULT_DISPLAY) saveSettingsLocked(userId);
                if (mCurrentUserId != userId) return; // Don't change the properties now
                if (wallpaper.connection != null) {
                    final DisplayConnector connector = wallpaper.connection
                            .getDisplayConnectorOrCreate(displayId);
                    final IWallpaperEngine engine = connector != null ? connector.mEngine : null;
                    if (engine != null) {
                        try {
                            engine.setDisplayPadding(padding);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to set display padding", e);
                        }
                        notifyCallbacksLocked(wallpaper);
                    } else if (wallpaper.connection.mService != null && connector != null) {
                        // We've attached to the service but the engine hasn't attached back to us
                        // yet. This means it will be created with the previous dimensions, so we
                        // need to update it to the new dimensions once it attaches.
                        connector.mPaddingChanged = true;
                    }
                }
            }
        }
    }

    @Deprecated
    @Override
    public ParcelFileDescriptor getWallpaper(String callingPkg, IWallpaperManagerCallback cb,
            final int which, Bundle outParams, int wallpaperUserId) {
        return getWallpaperWithFeature(callingPkg, null, cb, which, outParams,
                wallpaperUserId, /* getCropped= */ true);
    }

    @Override
    public ParcelFileDescriptor getWallpaperWithFeature(String callingPkg, String callingFeatureId,
            IWallpaperManagerCallback cb, final int which, Bundle outParams, int wallpaperUserId,
            boolean getCropped) {
        final boolean hasPrivilege = hasPermission(READ_WALLPAPER_INTERNAL);
        if (!hasPrivilege) {
            mContext.getSystemService(StorageManager.class).checkPermissionReadImages(true,
                    Binder.getCallingPid(), Binder.getCallingUid(), callingPkg, callingFeatureId);
        }

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
                // There is no established wallpaper imagery of this type (expected
                // only for lock wallpapers; a system WallpaperData is established at
                // user switch)
                return null;
            }
            // Only for default display.
            final DisplayData wpdData =
                    mWallpaperDisplayHelper.getDisplayDataOrCreate(DEFAULT_DISPLAY);
            try {
                if (outParams != null) {
                    outParams.putInt("width", wpdData.mWidth);
                    outParams.putInt("height", wpdData.mHeight);
                }
                if (cb != null) {
                    wallpaper.callbacks.register(cb);
                }

                File result = getCropped ? wallpaper.getCropFile() : wallpaper.getWallpaperFile();

                if (!result.exists()) {
                    return null;
                }

                return ParcelFileDescriptor.open(result, MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                /* Shouldn't happen as we check to see if the file exists */
                Slog.w(TAG, "Error getting wallpaper", e);
            }
            return null;
        }
    }

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED;
    }

    @Override
    public WallpaperInfo getWallpaperInfo(int userId) {
        return getWallpaperInfoWithFlags(FLAG_SYSTEM, userId);
    }

    @Override
    public WallpaperInfo getWallpaperInfoWithFlags(@SetWallpaperFlags int which, int userId) {

        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getWallpaperInfo", null);
        synchronized (mLock) {
            WallpaperData wallpaper = (which == FLAG_LOCK) ? mLockWallpaperMap.get(userId)
                    : mWallpaperMap.get(userId);
            if (wallpaper == null
                    || wallpaper.connection == null
                    || wallpaper.connection.mInfo == null) return null;

            WallpaperInfo info = wallpaper.connection.mInfo;
            if (hasPermission(READ_WALLPAPER_INTERNAL)
                    || mPackageManagerInternal.canQueryPackage(
                            Binder.getCallingUid(), info.getComponent().getPackageName())) {
                return info;
            }
        }
        return null;
    }

    @Override
    public ParcelFileDescriptor getWallpaperInfoFile(int userId) {
        synchronized (mLock) {
            try {
                File file = new File(getWallpaperDir(userId), WALLPAPER_INFO);

                if (!file.exists()) {
                    return null;
                }

                return ParcelFileDescriptor.open(file, MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                /* Shouldn't happen as we check to see if the file exists */
                Slog.w(TAG, "Error getting wallpaper info file", e);
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
    public void registerWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId,
            int displayId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, true, "registerWallpaperColorsCallback", null);
        synchronized (mLock) {
            SparseArray<RemoteCallbackList<IWallpaperManagerCallback>>
                    userDisplayColorsChangedListeners = mColorsChangedListeners.get(userId);
            if (userDisplayColorsChangedListeners == null) {
                userDisplayColorsChangedListeners = new SparseArray<>();
                mColorsChangedListeners.put(userId, userDisplayColorsChangedListeners);
            }
            RemoteCallbackList<IWallpaperManagerCallback> displayChangedListeners =
                    userDisplayColorsChangedListeners.get(displayId);
            if (displayChangedListeners == null) {
                displayChangedListeners = new RemoteCallbackList<>();
                userDisplayColorsChangedListeners.put(displayId, displayChangedListeners);
            }
            displayChangedListeners.register(cb);
        }
    }

    @Override
    public void unregisterWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId,
            int displayId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, true, "unregisterWallpaperColorsCallback", null);
        synchronized (mLock) {
            SparseArray<RemoteCallbackList<IWallpaperManagerCallback>>
                    userDisplayColorsChangedListeners = mColorsChangedListeners.get(userId);
            if (userDisplayColorsChangedListeners != null) {
                RemoteCallbackList<IWallpaperManagerCallback> displayChangedListeners =
                        userDisplayColorsChangedListeners.get(displayId);
                if (displayChangedListeners != null) {
                    displayChangedListeners.unregister(cb);
                }
            }
        }
    }

    /**
     * TODO(multi-display) Extends this method with specific display.
     * Propagate ambient state to wallpaper engine(s).
     *
     * @param inAmbientMode {@code true} when in ambient mode, {@code false} otherwise.
     * @param animationDuration Duration of the animation, or 0 when immediate.
     */
    public void setInAmbientMode(boolean inAmbientMode, long animationDuration) {
        List<IWallpaperEngine> engines = new ArrayList<>();
        synchronized (mLock) {
            mInAmbientMode = inAmbientMode;
            for (WallpaperData data : getActiveWallpapers()) {
                if (data.connection.mInfo == null
                        || data.connection.mInfo.supportsAmbientMode()) {
                    // TODO(multi-display) Extends this method with specific display.
                    IWallpaperEngine engine = data.connection
                            .getDisplayConnectorOrCreate(DEFAULT_DISPLAY).mEngine;
                    if (engine != null) engines.add(engine);
                }
            }
        }
        for (IWallpaperEngine engine : engines) {
            try {
                engine.setInAmbientMode(inAmbientMode, animationDuration);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to set ambient mode", e);
            }
        }
    }

    private void pauseOrResumeRenderingImmediately(boolean pause) {
        synchronized (mLock) {
            for (WallpaperData data : getActiveWallpapers()) {
                if (data.connection.mInfo == null) {
                    continue;
                }
                if (pause || LocalServices.getService(ActivityTaskManagerInternal.class)
                        .isUidForeground(data.connection.mInfo.getServiceInfo()
                                .applicationInfo.uid)) {
                    if (data.connection.containsDisplay(
                            mWindowManagerInternal.getTopFocusedDisplayId())) {
                        data.connection.forEachDisplayConnector(displayConnector -> {
                            if (displayConnector.mEngine != null) {
                                try {
                                    displayConnector.mEngine.setVisibility(!pause);
                                } catch (RemoteException e) {
                                    Slog.w(TAG, "Failed to set visibility", e);
                                }
                            }
                        });
                    }
                }
            }
        }
    }

    /**
     * Propagate a wake event to the wallpaper engine(s).
     */
    public void notifyWakingUp(int x, int y, @NonNull Bundle extras) {
        checkCallerIsSystemOrSystemUi();
        synchronized (mLock) {
            for (WallpaperData data : getActiveWallpapers()) {
                data.connection.forEachDisplayConnector(displayConnector -> {
                    if (displayConnector.mEngine != null) {
                        try {
                            displayConnector.mEngine.dispatchWallpaperCommand(
                                    WallpaperManager.COMMAND_WAKING_UP, x, y, -1, extras);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to dispatch COMMAND_WAKING_UP", e);
                        }
                    }
                });
            }
        }
    }

    /**
     * Propagate a sleep event to the wallpaper engine(s).
     */
    public void notifyGoingToSleep(int x, int y, @NonNull Bundle extras) {
        checkCallerIsSystemOrSystemUi();
        synchronized (mLock) {
            for (WallpaperData data : getActiveWallpapers()) {
                data.connection.forEachDisplayConnector(displayConnector -> {
                    if (displayConnector.mEngine != null) {
                        try {
                            displayConnector.mEngine.dispatchWallpaperCommand(
                                    WallpaperManager.COMMAND_GOING_TO_SLEEP, x, y, -1,
                                    extras);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to dispatch COMMAND_GOING_TO_SLEEP", e);
                        }
                    }
                });
            }
        }
    }

    /**
     * Propagates screen turned on event to wallpaper engine(s).
     */
    private void notifyScreenTurnedOn(int displayId) {
        synchronized (mLock) {
            for (WallpaperData data : getActiveWallpapers()) {
                if (data.connection.containsDisplay(displayId)) {
                    final IWallpaperEngine engine = data.connection
                            .getDisplayConnectorOrCreate(displayId).mEngine;
                    if (engine != null) {
                        try {
                            engine.onScreenTurnedOn();
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to notify that the screen turned on", e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Propagate screen turning on event to wallpaper engine(s).
     */
    private void notifyScreenTurningOn(int displayId) {
        synchronized (mLock) {
            for (WallpaperData data : getActiveWallpapers()) {
                if (data.connection.containsDisplay(displayId)) {
                    final IWallpaperEngine engine = data.connection
                            .getDisplayConnectorOrCreate(displayId).mEngine;
                    if (engine != null) {
                        try {
                            engine.onScreenTurningOn();
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to notify that the screen is turning on", e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Propagate a keyguard going away event to the wallpaper engine.
     */
    private void notifyKeyguardGoingAway() {
        synchronized (mLock) {
            for (WallpaperData data : getActiveWallpapers()) {
                data.connection.forEachDisplayConnector(displayConnector -> {
                    if (displayConnector.mEngine != null) {
                        try {
                            displayConnector.mEngine.dispatchWallpaperCommand(
                                    WallpaperManager.COMMAND_KEYGUARD_GOING_AWAY,
                                    -1, -1, -1, new Bundle());
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to notify that the keyguard is going away", e);
                        }
                    }
                });
            }
        }
    }

    private WallpaperData[] getActiveWallpapers() {
        WallpaperData systemWallpaper = mWallpaperMap.get(mCurrentUserId);
        WallpaperData lockWallpaper = mLockWallpaperMap.get(mCurrentUserId);
        boolean systemValid = systemWallpaper != null && systemWallpaper.connection != null;
        boolean lockValid = lockWallpaper != null && lockWallpaper.connection != null;
        return systemValid && lockValid ? new WallpaperData[]{systemWallpaper, lockWallpaper}
                : systemValid ? new WallpaperData[]{systemWallpaper}
                : lockValid ? new WallpaperData[]{lockWallpaper}
                : new WallpaperData[0];
    }

    private WallpaperData[] getWallpapers() {
        WallpaperData systemWallpaper = mWallpaperMap.get(mCurrentUserId);
        WallpaperData lockWallpaper = mLockWallpaperMap.get(mCurrentUserId);
        boolean systemValid = systemWallpaper != null;
        boolean lockValid = lockWallpaper != null;
        return systemValid && lockValid ? new WallpaperData[]{systemWallpaper, lockWallpaper}
                : systemValid ? new WallpaperData[]{systemWallpaper}
                : lockValid ? new WallpaperData[]{lockWallpaper}
                : new WallpaperData[0];
    }

    private IWallpaperEngine getEngine(int which, int userId, int displayId) {
        WallpaperData wallpaperData = findWallpaperAtDisplay(userId, displayId);
        if (wallpaperData == null) return null;
        WallpaperConnection connection = wallpaperData.connection;
        if (connection == null) return null;
        IWallpaperEngine engine = null;
        synchronized (mLock) {
            for (int i = 0; i < connection.mDisplayConnector.size(); i++) {
                int id = connection.mDisplayConnector.get(i).mDisplayId;
                int currentWhich = connection.mDisplayConnector.get(i).mDisplayId;
                if (id != displayId && currentWhich != which) continue;
                engine = connection.mDisplayConnector.get(i).mEngine;
                break;
            }
        }
        return engine;
    }

    @Override
    public void addOnLocalColorsChangedListener(@NonNull ILocalWallpaperColorConsumer callback,
            @NonNull List<RectF> regions, int which, int userId, int displayId)
            throws RemoteException {
        if (which != FLAG_LOCK && which != FLAG_SYSTEM) {
            throw new IllegalArgumentException("which should be either FLAG_LOCK or FLAG_SYSTEM");
        }
        IWallpaperEngine engine = getEngine(which, userId, displayId);
        if (engine == null) return;
        synchronized (mLock) {
            mLocalColorRepo.addAreas(callback, regions, displayId);
        }
        engine.addLocalColorsAreas(regions);
    }

    @Override
    public void removeOnLocalColorsChangedListener(
            @NonNull ILocalWallpaperColorConsumer callback, List<RectF> removeAreas, int which,
            int userId, int displayId) throws RemoteException {
        if (which != FLAG_LOCK && which != FLAG_SYSTEM) {
            throw new IllegalArgumentException("which should be either FLAG_LOCK or FLAG_SYSTEM");
        }
        final UserHandle callingUser = Binder.getCallingUserHandle();
        if (callingUser.getIdentifier() != userId) {
            throw new SecurityException("calling user id does not match");
        }
        final long identity = Binder.clearCallingIdentity();
        List<RectF> purgeAreas = null;
        try {
            synchronized (mLock) {
                purgeAreas = mLocalColorRepo.removeAreas(callback, removeAreas, displayId);
            }
        } catch (Exception e) {
            // ignore any exception
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        IWallpaperEngine engine = getEngine(which, userId, displayId);
        if (engine == null || purgeAreas == null) return;
        if (purgeAreas.size() > 0) engine.removeLocalColorsAreas(purgeAreas);
    }

    /**
     * Returns true if the lock screen wallpaper exists (different wallpaper from the system)
     */
    @Override
    public boolean lockScreenWallpaperExists() {
        synchronized (mLock) {
            return mLockWallpaperMap.get(mCurrentUserId) != null;
        }
    }

    /**
     * Returns true if there is a static wallpaper on the specified screen. With which=FLAG_LOCK,
     * always return false if the lockscreen doesn't run its own wallpaper engine.
     */
    @Override
    public boolean isStaticWallpaper(int which) {
        synchronized (mLock) {
            WallpaperData wallpaperData = (which == FLAG_LOCK ? mLockWallpaperMap : mWallpaperMap)
                    .get(mCurrentUserId);
            if (wallpaperData == null) return false;
            return mImageWallpaper.equals(wallpaperData.wallpaperComponent);
        }
    }

    /**
     * Sets wallpaper dim amount for the calling UID. This applies to all destinations (home, lock)
     * with an active wallpaper engine.
     *
     * @param dimAmount Dim amount which would be blended with the system default dimming.
     */
    @Override
    public void setWallpaperDimAmount(float dimAmount) throws RemoteException {
        setWallpaperDimAmountForUid(Binder.getCallingUid(), dimAmount);
    }

    /**
     * Sets wallpaper dim amount for the calling UID. This applies to all destinations (home, lock)
     * with an active wallpaper engine.
     *
     * @param uid Caller UID that wants to set the wallpaper dim amount
     * @param dimAmount Dim amount where 0f reverts any dimming applied by the caller (fully bright)
     *                  and 1f is fully black
     * @throws RemoteException
     */
    public void setWallpaperDimAmountForUid(int uid, float dimAmount) {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_DIM_AMOUNT);
        final long ident = Binder.clearCallingIdentity();
        try {
            List<WallpaperData> pendingColorExtraction = new ArrayList<>();
            synchronized (mLock) {
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                WallpaperData lockWallpaper = mLockWallpaperMap.get(mCurrentUserId);

                if (dimAmount == 0.0f) {
                    wallpaper.mUidToDimAmount.remove(uid);
                } else {
                    wallpaper.mUidToDimAmount.put(uid, dimAmount);
                }

                float maxDimAmount = getHighestDimAmountFromMap(wallpaper.mUidToDimAmount);
                wallpaper.mWallpaperDimAmount = maxDimAmount;
                // Also set the dim amount to the lock screen wallpaper if the lock and home screen
                // do not share the same wallpaper
                if (lockWallpaper != null) {
                    lockWallpaper.mWallpaperDimAmount = maxDimAmount;
                }

                boolean changed = false;
                for (WallpaperData wp : getActiveWallpapers()) {
                    if (wp != null && wp.connection != null) {
                        wp.connection.forEachDisplayConnector(connector -> {
                            if (connector.mEngine != null) {
                                try {
                                    connector.mEngine.applyDimming(maxDimAmount);
                                } catch (RemoteException e) {
                                    Slog.w(TAG, "Can't apply dimming on wallpaper display "
                                                    + "connector", e);
                                }
                            }
                        });
                        // Need to extract colors again to re-calculate dark hints after
                        // applying dimming.
                        wp.mIsColorExtractedFromDim = true;
                        pendingColorExtraction.add(wp);
                        changed = true;
                    }
                }
                if (changed) {
                    saveSettingsLocked(wallpaper.userId);
                }
            }
            for (WallpaperData wp: pendingColorExtraction) {
                notifyWallpaperColorsChanged(wp);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public float getWallpaperDimAmount() {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_DIM_AMOUNT);
        synchronized (mLock) {
            WallpaperData data = mWallpaperMap.get(mCurrentUserId);
            if (data == null) {
                data = mWallpaperMap.get(UserHandle.USER_SYSTEM);
                if (data == null) {
                    Slog.e(TAG, "getWallpaperDimAmount: wallpaperData is null");
                    return 0.0f;
                }
            }
            return data.mWallpaperDimAmount;
        }
    }

    /**
     * Gets the highest dim amount among all the calling UIDs that set the wallpaper dim amount.
     * Return 0f as default value to indicate no application has dimmed the wallpaper.
     *
     * @param uidToDimAmountMap Map of UIDs to dim amounts
     */
    private float getHighestDimAmountFromMap(SparseArray<Float> uidToDimAmountMap) {
        float maxDimAmount = 0.0f;
        for (int i = 0; i < uidToDimAmountMap.size(); i++) {
            maxDimAmount = Math.max(maxDimAmount, uidToDimAmountMap.valueAt(i));
        }
        return maxDimAmount;
    }

    @Override
    public WallpaperColors getWallpaperColors(int which, int userId, int displayId)
            throws RemoteException {
        if (which != FLAG_LOCK && which != FLAG_SYSTEM) {
            throw new IllegalArgumentException("which should be either FLAG_LOCK or FLAG_SYSTEM");
        }
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, true, "getWallpaperColors", null);

        WallpaperData wallpaperData = null;
        boolean shouldExtract;

        synchronized (mLock) {
            if (which == FLAG_LOCK) {
                wallpaperData = mLockWallpaperMap.get(userId);
            }

            // Try to get the system wallpaper anyway since it might
            // also be the lock screen wallpaper
            if (wallpaperData == null) {
                wallpaperData = findWallpaperAtDisplay(userId, displayId);
            }

            if (wallpaperData == null) {
                return null;
            }
            shouldExtract = wallpaperData.primaryColors == null
                    || wallpaperData.mIsColorExtractedFromDim;
        }

        if (shouldExtract) {
            extractColors(wallpaperData);
        }

        return getAdjustedWallpaperColorsOnDimming(wallpaperData);
    }

    /**
     * Gets the adjusted {@link WallpaperColors} if the wallpaper colors were not extracted from
     * bitmap (i.e. it's a live wallpaper) and the dim amount is not 0. If these conditions apply,
     * default to using color hints that do not support dark theme and dark text.
     *
     * @param wallpaperData WallpaperData containing the WallpaperColors and mWallpaperDimAmount
     */
    WallpaperColors getAdjustedWallpaperColorsOnDimming(WallpaperData wallpaperData) {
        synchronized (mLock) {
            WallpaperColors wallpaperColors = wallpaperData.primaryColors;

            if (wallpaperColors != null
                    && (wallpaperColors.getColorHints() & WallpaperColors.HINT_FROM_BITMAP) == 0
                    && wallpaperData.mWallpaperDimAmount != 0f) {
                int adjustedColorHints = wallpaperColors.getColorHints()
                        & ~WallpaperColors.HINT_SUPPORTS_DARK_TEXT
                        & ~WallpaperColors.HINT_SUPPORTS_DARK_THEME;
                return new WallpaperColors(
                        wallpaperColors.getPrimaryColor(), wallpaperColors.getSecondaryColor(),
                        wallpaperColors.getTertiaryColor(), adjustedColorHints);
            }
            return wallpaperColors;
        }
    }

    private WallpaperData findWallpaperAtDisplay(int userId, int displayId) {
        if (mFallbackWallpaper != null && mFallbackWallpaper.connection != null
                && mFallbackWallpaper.connection.containsDisplay(displayId)) {
            return mFallbackWallpaper;
        } else {
            return mWallpaperMap.get(userId);
        }
    }

    @Override
    public ParcelFileDescriptor setWallpaper(String name, String callingPackage,
            Rect cropHint, boolean allowBackup, Bundle extras, int which,
            IWallpaperManagerCallback completion, int userId) {
        userId = ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId,
                false /* all */, true /* full */, "changing wallpaper", null /* pkg */);
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
            if (cropHint.width() < 0 || cropHint.height() < 0
                    || cropHint.left < 0
                    || cropHint.top < 0) {
                throw new IllegalArgumentException("Invalid crop rect supplied: " + cropHint);
            }
        }

        synchronized (mLock) {
            if (DEBUG) Slog.v(TAG, "setWallpaper which=0x" + Integer.toHexString(which));
            WallpaperData wallpaper;
            final WallpaperData originalSystemWallpaper = mWallpaperMap.get(userId);
            final boolean systemIsStatic =
                    originalSystemWallpaper != null && mImageWallpaper.equals(
                            originalSystemWallpaper.wallpaperComponent);
            final boolean systemIsBoth = mLockWallpaperMap.get(userId) == null;

            /* If we're setting system but not lock, and lock is currently sharing the system
             * wallpaper, we need to migrate that image over to being lock-only before
             * the caller here writes new bitmap data.
             */
            if (which == FLAG_SYSTEM && systemIsStatic && systemIsBoth) {
                Slog.i(TAG, "Migrating current wallpaper to be lock-only before"
                        + " updating system wallpaper");
                migrateStaticSystemToLockWallpaperLocked(userId);
            }

            wallpaper = getWallpaperSafeLocked(userId, which);
            if (mPendingMigrationViaStatic != null) {
                Slog.w(TAG, "Starting new static wp migration before previous migration finished");
            }
            mPendingMigrationViaStatic = new WallpaperDestinationChangeHandler(wallpaper);
            final long ident = Binder.clearCallingIdentity();
            try {
                ParcelFileDescriptor pfd = updateWallpaperBitmapLocked(name, wallpaper, extras);
                if (pfd != null) {
                    wallpaper.imageWallpaperPending = true;
                    wallpaper.mSystemWasBoth = systemIsBoth;
                    wallpaper.mWhich = which;
                    wallpaper.setComplete = completion;
                    wallpaper.fromForegroundApp = isFromForegroundApp(callingPackage);
                    wallpaper.cropHint.set(cropHint);
                    wallpaper.allowBackup = allowBackup;
                    wallpaper.mWallpaperDimAmount = getWallpaperDimAmount();
                }
                return pfd;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void migrateStaticSystemToLockWallpaperLocked(int userId) {
        WallpaperData sysWP = mWallpaperMap.get(userId);
        if (sysWP == null) {
            if (DEBUG) {
                Slog.i(TAG, "No system wallpaper?  Not tracking for lock-only");
            }
            return;
        }

        // We know a-priori that there is no lock-only wallpaper currently
        WallpaperData lockWP = new WallpaperData(userId, FLAG_LOCK);
        lockWP.wallpaperId = sysWP.wallpaperId;
        lockWP.cropHint.set(sysWP.cropHint);
        lockWP.allowBackup = sysWP.allowBackup;
        lockWP.primaryColors = sysWP.primaryColors;
        lockWP.mWallpaperDimAmount = sysWP.mWallpaperDimAmount;
        lockWP.mWhich = FLAG_LOCK;

        // Migrate the bitmap files outright; no need to copy
        try {
            if (sysWP.getWallpaperFile().exists()) {
                Os.rename(sysWP.getWallpaperFile().getAbsolutePath(),
                        lockWP.getWallpaperFile().getAbsolutePath());
            }
            if (sysWP.getCropFile().exists()) {
                Os.rename(sysWP.getCropFile().getAbsolutePath(),
                        lockWP.getCropFile().getAbsolutePath());
            }
            mLockWallpaperMap.put(userId, lockWP);
            SELinux.restorecon(lockWP.getWallpaperFile());
            mLastLockWallpaper = lockWP;
        } catch (ErrnoException e) {
            // can happen when migrating default wallpaper (which is not stored in wallpaperFile)
            Slog.w(TAG, "Couldn't migrate system wallpaper: " + e.getMessage());
            clearWallpaperBitmaps(lockWP);
        }
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
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(wallpaper.getWallpaperFile(),
                    MODE_CREATE|MODE_READ_WRITE|MODE_TRUNCATE);
            if (!SELinux.restorecon(wallpaper.getWallpaperFile())) {
                Slog.w(TAG, "restorecon failed for wallpaper file: " +
                        wallpaper.getWallpaperFile().getPath());
                return null;
            }
            wallpaper.name = name;
            wallpaper.wallpaperId = makeWallpaperIdLocked();
            if (extras != null) {
                extras.putInt(WallpaperManager.EXTRA_NEW_WALLPAPER_ID, wallpaper.wallpaperId);
            }
            // Nullify field to require new computation
            wallpaper.primaryColors = null;
            Slog.v(TAG, "updateWallpaperBitmapLocked() : id=" + wallpaper.wallpaperId
                    + " name=" + name + " file=" + wallpaper.getWallpaperFile().getName());
            return fd;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Error setting wallpaper", e);
        }
        return null;
    }

    @Override
    public void setWallpaperComponentChecked(ComponentName name, String callingPackage,
            @SetWallpaperFlags int which, int userId) {

        if (isWallpaperSupported(callingPackage) && isSetWallpaperAllowed(callingPackage)) {
            setWallpaperComponent(name, callingPackage, which, userId);
        }
    }

    // ToDo: Remove this version of the function
    @Override
    public void setWallpaperComponent(ComponentName name) {
        setWallpaperComponent(name, "", UserHandle.getCallingUserId(), FLAG_SYSTEM);
    }

    @VisibleForTesting
    boolean setWallpaperComponent(ComponentName name, String callingPackage,
            @SetWallpaperFlags int which, int userId) {
        boolean fromForeground = isFromForegroundApp(callingPackage);
        return setWallpaperComponentInternal(name, which, userId, false, fromForeground, null);
    }

    private boolean setWallpaperComponentInternal(ComponentName name,  @SetWallpaperFlags int which,
            int userIdIn, boolean force, boolean fromForeground, IRemoteCallback reply) {
        if (DEBUG) {
            Slog.v(TAG, "Setting new live wallpaper: which=" + which + ", component: " + name);
        }
        final int userId = ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(),
                userIdIn, false /* all */, true /* full */, "changing live wallpaper",
                null /* pkg */);
        checkPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT);

        boolean shouldNotifyColors = false;
        boolean bindSuccess;
        final WallpaperData newWallpaper;

        synchronized (mLock) {
            Slog.v(TAG, "setWallpaperComponent name=" + name + ", which = " + which);
            final WallpaperData originalSystemWallpaper = mWallpaperMap.get(userId);
            if (originalSystemWallpaper == null) {
                throw new IllegalStateException("Wallpaper not yet initialized for user " + userId);
            }
            final boolean systemIsStatic = mImageWallpaper.equals(
                    originalSystemWallpaper.wallpaperComponent);
            final boolean systemIsBoth = mLockWallpaperMap.get(userId) == null;

            if (which == FLAG_SYSTEM && systemIsBoth && systemIsStatic) {
                // Migrate current static system+lock wp to lock only before proceeding.
                Slog.i(TAG, "Migrating current wallpaper to be lock-only before"
                        + "updating system wallpaper");
                migrateStaticSystemToLockWallpaperLocked(userId);
            }

            newWallpaper = getWallpaperSafeLocked(userId, which);
            final long ident = Binder.clearCallingIdentity();

            try {
                newWallpaper.imageWallpaperPending = false;
                newWallpaper.mWhich = which;
                newWallpaper.mSystemWasBoth = systemIsBoth;
                newWallpaper.fromForegroundApp = fromForeground;
                final WallpaperDestinationChangeHandler
                        liveSync = new WallpaperDestinationChangeHandler(
                        newWallpaper);
                boolean same = changingToSame(name, newWallpaper);

                /*
                 * If we have a shared system+lock wallpaper, and we reapply the same wallpaper
                 * to system only, force rebind: the current wallpaper will be migrated to lock
                 * and a new engine with the same wallpaper will be applied to system.
                 */
                boolean forceRebind = force || (same && systemIsBoth && which == FLAG_SYSTEM);

                newWallpaper.mBindSource =
                        (name == null) ? BindSource.SET_LIVE_TO_CLEAR : BindSource.SET_LIVE;
                bindSuccess = bindWallpaperComponentLocked(name, /* force */
                        forceRebind, /* fromUser */ true, newWallpaper, reply);
                if (bindSuccess) {
                    if (!same) {
                        newWallpaper.primaryColors = null;
                    } else {
                        if (newWallpaper.connection != null) {
                            newWallpaper.connection.forEachDisplayConnector(displayConnector -> {
                                try {
                                    if (displayConnector.mEngine != null) {
                                        displayConnector.mEngine.dispatchWallpaperCommand(
                                                COMMAND_REAPPLY, 0, 0, 0, null);
                                    }
                                } catch (RemoteException e) {
                                    Slog.w(TAG, "Error sending apply message to wallpaper", e);
                                }
                            });
                        }
                    }
                    boolean lockBitmapCleared = false;
                    if (!mImageWallpaper.equals(newWallpaper.wallpaperComponent)) {
                        clearWallpaperBitmaps(newWallpaper);
                        lockBitmapCleared = newWallpaper.mWhich == FLAG_LOCK;
                    }
                    newWallpaper.wallpaperId = makeWallpaperIdLocked();
                    notifyCallbacksLocked(newWallpaper);
                    shouldNotifyColors = true;

                    if (which == (FLAG_SYSTEM | FLAG_LOCK)) {
                        if (DEBUG) {
                            Slog.v(TAG, "Lock screen wallpaper changed to same as home");
                        }
                        final WallpaperData lockedWallpaper = mLockWallpaperMap.get(
                                newWallpaper.userId);
                        if (lockedWallpaper != null) {
                            detachWallpaperLocked(lockedWallpaper);
                            if (same) {
                                updateEngineFlags(newWallpaper);
                            }
                        }
                        if (!lockBitmapCleared) {
                            clearWallpaperBitmaps(newWallpaper.userId, FLAG_LOCK);
                        }
                        mLockWallpaperMap.remove(newWallpaper.userId);
                    }
                    if (liveSync != null) liveSync.complete();
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        if (shouldNotifyColors) {
            notifyWallpaperColorsChanged(newWallpaper);
        }
        return bindSuccess;
    }

    /**
     * Determines if the given component name is the default component. Note: a null name can be
     * used to represent the default component.
     * @param name The component name to check.
     * @return True if the component name matches the default wallpaper component.
     */
    private boolean isDefaultComponent(ComponentName name) {
        return name == null || name.equals(mDefaultWallpaperComponent);
    }

    private boolean changingToSame(ComponentName componentName, WallpaperData wallpaper) {
        if (wallpaper.connection != null) {
            final ComponentName wallpaperName = wallpaper.wallpaperComponent;
            if (isDefaultComponent(componentName) && isDefaultComponent(wallpaperName)) {
                if (DEBUG) Slog.v(TAG, "changingToSame: still using default");
                // Still using default wallpaper.
                return true;
            } else if (wallpaperName != null && wallpaperName.equals(componentName)) {
                // Changing to same wallpaper.
                if (DEBUG) Slog.v(TAG, "same wallpaper");
                return true;
            }
        }
        return false;
    }

    boolean bindWallpaperComponentLocked(ComponentName componentName, boolean force,
            boolean fromUser, WallpaperData wallpaper, IRemoteCallback reply) {
        if (DEBUG_LIVE) {
            Slog.v(TAG, "bindWallpaperComponentLocked: componentName=" + componentName);
        }
        // Has the component changed?
        if (!force && changingToSame(componentName, wallpaper)) {
            try {
                if (DEBUG_LIVE) {
                    Slog.v(TAG, "Changing to the same component, ignoring");
                }
                if (reply != null) reply.sendResult(null);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to send callback", e);
            }
            return true;
        }

        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
        t.traceBegin("WPMS.bindWallpaperComponentLocked-" + componentName);
        try {
            if (componentName == null) {
                componentName = mDefaultWallpaperComponent;
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
                String msg = "Selected service does not have "
                        + android.Manifest.permission.BIND_WALLPAPER
                        + ": " + componentName;
                if (fromUser) {
                    throw new SecurityException(msg);
                }
                Slog.w(TAG, msg);
                return false;
            }

            // This will only get set for non-static wallpapers.
            WallpaperInfo wi = null;

            Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
            if (componentName != null && !componentName.equals(mImageWallpaper)) {
                // The requested component is not the static wallpaper service, so make sure it's
                // actually a wallpaper service.
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

            if (wi != null && wi.supportsAmbientMode()) {
                final int hasPrivilege = mIPackageManager.checkPermission(
                        android.Manifest.permission.AMBIENT_WALLPAPER, wi.getPackageName(),
                        serviceUserId);
                // All watch wallpapers support ambient mode by default.
                if (hasPrivilege != PERMISSION_GRANTED
                        && !mIPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH, 0)) {
                    String msg = "Selected service does not have "
                            + android.Manifest.permission.AMBIENT_WALLPAPER
                            + ": " + componentName;
                    if (fromUser) {
                        throw new SecurityException(msg);
                    }
                    Slog.w(TAG, msg);
                    return false;
                }
            }

            final ActivityOptions clientOptions = ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED);
            PendingIntent clientIntent = PendingIntent.getActivityAsUser(
                    mContext, 0, Intent.createChooser(
                            new Intent(Intent.ACTION_SET_WALLPAPER),
                            mContext.getText(com.android.internal.R.string.chooser_wallpaper)),
                    PendingIntent.FLAG_IMMUTABLE, clientOptions.toBundle(),
                    UserHandle.of(serviceUserId));

            // Bind the service!
            if (DEBUG) Slog.v(TAG, "Binding to:" + componentName);
            final int componentUid = mIPackageManager.getPackageUid(componentName.getPackageName(),
                    MATCH_DIRECT_BOOT_AUTO, wallpaper.userId);
            WallpaperConnection newConn = new WallpaperConnection(wi, wallpaper, componentUid);
            intent.setComponent(componentName);
            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.wallpaper_binding_label);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, clientIntent);
            int bindFlags = Context.BIND_AUTO_CREATE | Context.BIND_SHOWING_UI
                            | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
                            | Context.BIND_INCLUDE_CAPABILITIES;

            if (mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_wallpaperTopApp)) {
                bindFlags |= Context.BIND_SCHEDULE_LIKE_TOP_APP;
            }
            Handler handler = Flags.bindWallpaperServiceOnItsOwnThreadDuringAUserSwitch()
                    && !mIsInitialBinding.compareAndSet(true, false)
                    ? mHandlerThread.getThreadHandler() : mContext.getMainThreadHandler();
            boolean bindSuccess = mContext.bindServiceAsUser(intent, newConn, bindFlags, handler,
                    new UserHandle(serviceUserId));
            if (!bindSuccess) {
                String msg = "Unable to bind service: " + componentName;
                if (fromUser) {
                    throw new IllegalArgumentException(msg);
                }
                Slog.w(TAG, msg);
                return false;
            }
            maybeDetachLastWallpapers(wallpaper);
            wallpaper.wallpaperComponent = componentName;
            wallpaper.connection = newConn;
            newConn.mReply = reply;
            updateCurrentWallpapers(wallpaper);
            updateFallbackConnection();
        } catch (RemoteException e) {
            String msg = "Remote exception for " + componentName + "\n" + e;
            if (fromUser) {
                throw new IllegalArgumentException(msg);
            }
            Slog.w(TAG, msg);
            return false;
        } finally {
            t.traceEnd();
        }
        return true;
    }

    // Updates tracking of the currently bound wallpapers.
    private void updateCurrentWallpapers(WallpaperData newWallpaper) {
        if (newWallpaper.userId != mCurrentUserId || newWallpaper.equals(mFallbackWallpaper)) {
            return;
        }
        if (newWallpaper.mWhich == (FLAG_SYSTEM | FLAG_LOCK)) {
            mLastWallpaper = newWallpaper;
        } else if (newWallpaper.mWhich == FLAG_SYSTEM) {
            mLastWallpaper = newWallpaper;
        } else if (newWallpaper.mWhich == FLAG_LOCK) {
            mLastLockWallpaper = newWallpaper;
        }
    }

    // Detaches previously bound wallpapers if no longer in use.
    private void maybeDetachLastWallpapers(WallpaperData newWallpaper) {
        if (newWallpaper.userId != mCurrentUserId || newWallpaper.equals(mFallbackWallpaper)) {
            return;
        }
        boolean homeUpdated = (newWallpaper.mWhich & FLAG_SYSTEM) != 0;
        boolean lockUpdated = (newWallpaper.mWhich & FLAG_LOCK) != 0;
        boolean systemWillBecomeLock = newWallpaper.mSystemWasBoth && !lockUpdated;
        if (mLastWallpaper != null && homeUpdated && !systemWillBecomeLock) {
            detachWallpaperLocked(mLastWallpaper);
        }
        if (mLastLockWallpaper != null && lockUpdated) {
            detachWallpaperLocked(mLastLockWallpaper);
        }
    }

    // Frees up all rendering resources used by the given wallpaper so that the WallpaperData object
    // can be reused: detaches Engine, unbinds WallpaperService, etc.
    private void detachWallpaperLocked(WallpaperData wallpaper) {
        if (wallpaper.connection != null) {
            if (DEBUG) {
                Slog.v(TAG, "Detaching wallpaper: " + wallpaper);
            }
            if (wallpaper.connection.mReply != null) {
                try {
                    wallpaper.connection.mReply.sendResult(null);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error sending reply to wallpaper before disconnect", e);
                }
                wallpaper.connection.mReply = null;
            }
            wallpaper.connection.forEachDisplayConnector(
                    connector -> connector.disconnectLocked(wallpaper.connection));
            wallpaper.connection.mService = null;
            wallpaper.connection.mDisplayConnector.clear();

            FgThread.getHandler().removeCallbacks(wallpaper.connection.mResetRunnable);
            mContext.getMainThreadHandler().removeCallbacks(
                    wallpaper.connection.mDisconnectRunnable);
            mContext.getMainThreadHandler().removeCallbacks(
                    wallpaper.connection.mTryToRebindRunnable);

            try {
                mContext.unbindService(wallpaper.connection);
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Error unbinding wallpaper when detaching", e);
            }
            wallpaper.connection = null;
            if (wallpaper == mLastWallpaper) {
                mLastWallpaper = null;
            }
            if (wallpaper == mLastLockWallpaper) {
                mLastLockWallpaper = null;
            }
        }
    }

    // Updates the given wallpaper's Engine so that its destination flags are the same as those of
    // the wallpaper, e.g., after a wallpaper has been changed from displaying on home+lock to home
    // or lock only.
    private void updateEngineFlags(WallpaperData wallpaper) {
        if (wallpaper.connection == null) {
            return;
        }
        wallpaper.connection.forEachDisplayConnector(
                connector -> {
                    try {
                        if (connector.mEngine != null) {
                            connector.mEngine.setWallpaperFlags(wallpaper.mWhich);
                            mWindowManagerInternal.setWallpaperShowWhenLocked(
                                    connector.mToken, (wallpaper.mWhich & FLAG_LOCK) != 0);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to update wallpaper engine flags", e);
                    }
                });
    }

    private void clearWallpaperComponentLocked(WallpaperData wallpaper) {
        wallpaper.wallpaperComponent = null;
        detachWallpaperLocked(wallpaper);
    }

    private void attachServiceLocked(WallpaperConnection conn, WallpaperData wallpaper) {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
        t.traceBegin("WPMS.attachServiceLocked");
        conn.forEachDisplayConnector(connector-> connector.connectLocked(conn, wallpaper));
        t.traceEnd();
    }

    private void notifyCallbacksLocked(WallpaperData wallpaper) {
        final int n = wallpaper.callbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                wallpaper.callbacks.getBroadcastItem(i).onWallpaperChanged();
            } catch (RemoteException e) {

                // The RemoteCallbackList will take care of removing
                // the dead object for us.
                Slog.w(TAG, "Failed to notify callbacks about wallpaper changes", e);
            }
        }
        wallpaper.callbacks.finishBroadcast();

        final Intent intent = new Intent(Intent.ACTION_WALLPAPER_CHANGED);
        intent.putExtra(WallpaperManager.EXTRA_FROM_FOREGROUND_APP, wallpaper.fromForegroundApp);
        mContext.sendBroadcastAsUser(intent, new UserHandle(mCurrentUserId));
    }

    private void checkPermission(String permission) {
        if (!hasPermission(permission)) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private boolean packageBelongsToUid(String packageName, int uid) {
        int userId = UserHandle.getUserId(uid);
        int packageUid;
        try {
            packageUid = mContext.getPackageManager().getPackageUidAsUser(
                    packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return packageUid == uid;
    }

    private void enforcePackageBelongsToUid(String packageName, int uid) {
        if (!packageBelongsToUid(packageName, uid)) {
            throw new IllegalArgumentException(
                    "Invalid package or package does not belong to uid:"
                            + uid);
        }
    }

    private boolean isFromForegroundApp(String callingPackage) {
        return Binder.withCleanCallingIdentity(() ->
                mActivityManager.getPackageImportance(callingPackage) == IMPORTANCE_FOREGROUND);
    }

    /** Check that the caller is either system_server or systemui */
    private void checkCallerIsSystemOrSystemUi() {
        if (Binder.getCallingUid() != Process.myUid() && mContext.checkCallingPermission(
                android.Manifest.permission.STATUS_BAR_SERVICE) != PERMISSION_GRANTED) {
            throw new SecurityException("Access denied: only system processes can call this");
        }
    }

    /**
     * Certain user types do not support wallpapers (e.g. managed profiles). The check is
     * implemented through through the OP_WRITE_WALLPAPER AppOp.
     */
    public boolean isWallpaperSupported(String callingPackage) {
        final int callingUid = Binder.getCallingUid();
        enforcePackageBelongsToUid(callingPackage, callingUid);

        return mAppOpsManager.checkOpNoThrow(AppOpsManager.OP_WRITE_WALLPAPER, callingUid,
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
        final DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        if (dpmi != null && dpmi.isDeviceOrProfileOwnerInCallingUser(callingPackage)) {
            return true;
        }
        final int callingUserId = UserHandle.getCallingUserId();
        final long ident = Binder.clearCallingIdentity();
        try {
            UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
            return !umi.hasUserRestriction(UserManager.DISALLOW_SET_WALLPAPER, callingUserId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean isWallpaperBackupEligible(int which, int userId) {
        WallpaperData wallpaper = (which == FLAG_LOCK)
                ? mLockWallpaperMap.get(userId)
                : mWallpaperMap.get(userId);
        return (wallpaper != null) ? wallpaper.allowBackup : false;
    }

    @Override
    public boolean isMultiCropEnabled() {
        return mIsMultiCropEnabled;
    }

    private void onDisplayReadyInternal(int displayId) {
        synchronized (mLock) {
            if (mLastWallpaper == null) {
                return;
            }
            if (supportsMultiDisplay(mLastWallpaper.connection)) {
                final DisplayConnector connector =
                        mLastWallpaper.connection.getDisplayConnectorOrCreate(displayId);
                if (connector == null) return;
                connector.connectLocked(mLastWallpaper.connection, mLastWallpaper);
                return;
            }
            // System wallpaper does not support multiple displays, attach this display to
            // the fallback wallpaper.
            if (mFallbackWallpaper != null) {
                final DisplayConnector connector = mFallbackWallpaper
                        .connection.getDisplayConnectorOrCreate(displayId);
                if (connector == null) return;
                connector.connectLocked(mFallbackWallpaper.connection, mFallbackWallpaper);
            } else {
                Slog.w(TAG, "No wallpaper can be added to the new display");
            }
        }
    }

    void saveSettingsLocked(int userId) {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
        t.traceBegin("WPMS.saveSettingsLocked-" + userId);
        mWallpaperDataParser.saveSettingsLocked(
                userId, mWallpaperMap.get(userId), mLockWallpaperMap.get(userId));
        t.traceEnd();
    }

    /**
     * Determines and returns the current wallpaper for the given user and destination, creating
     * a valid entry if it does not already exist and adding it to the appropriate wallpaper map.
     *
     * Sometimes it is expected the wallpaper map may not have a user's data.  E.g. This could
     * happen during user switch.  The async user switch observer may not have received
     * the event yet.  We use this safe method when we don't care about this ordering and just
     * want to update the data.  The data is going to be applied when the user switch observer
     * is eventually executed.
     *
     * Important: this method loads settings to initialize the given user's wallpaper data if
     * there is no current in-memory state.
     */
    WallpaperData getWallpaperSafeLocked(int userId, int which) {
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
            // if we're loading the system wallpaper for the first time, also load the lock
            // wallpaper to determine if the system wallpaper is system+lock or system only.
            int whichLoad = (which == FLAG_LOCK) ? FLAG_LOCK : FLAG_SYSTEM | FLAG_LOCK;
            loadSettingsLocked(userId, false, whichLoad);
            wallpaper = whichSet.get(userId);
            if (wallpaper == null) {
                // if it's still null here, this is likely a lock-only operation and there is not
                // currently a lock-only wallpaper set for this user, so we need to establish
                // it now.
                if (which == FLAG_LOCK) {
                    wallpaper = new WallpaperData(userId, FLAG_LOCK);
                    mLockWallpaperMap.put(userId, wallpaper);
                } else {
                    // rationality fallback: we're in bad shape, but establishing a known
                    // valid system+lock WallpaperData will keep us from dying.
                    Slog.wtf(TAG, "Didn't find wallpaper in non-lock case!");
                    wallpaper = new WallpaperData(userId, FLAG_SYSTEM);
                    mWallpaperMap.put(userId, wallpaper);
                }
            }
        }
        return wallpaper;
    }

    private void loadSettingsLocked(int userId, boolean keepDimensionHints, int which) {
        initializeFallbackWallpaper();
        boolean restoreFromOld = !mWallpaperMap.contains(userId);
        WallpaperDataParser.WallpaperLoadingResult result = mWallpaperDataParser.loadSettingsLocked(
                userId, keepDimensionHints, restoreFromOld, which);

        boolean updateSystem = (which & FLAG_SYSTEM) != 0;
        boolean updateLock = (which & FLAG_LOCK) != 0;

        if (updateSystem) mWallpaperMap.put(userId, result.getSystemWallpaperData());
        if (updateLock) {
            if (result.success()) {
                mLockWallpaperMap.put(userId, result.getLockWallpaperData());
            } else {
                mLockWallpaperMap.remove(userId);
            }
        }
    }

    private void initializeFallbackWallpaper() {
        if (mFallbackWallpaper == null) {
            if (DEBUG) Slog.d(TAG, "Initialize fallback wallpaper");
            final int systemUserId = UserHandle.USER_SYSTEM;
            mFallbackWallpaper = new WallpaperData(systemUserId, FLAG_SYSTEM);
            mFallbackWallpaper.allowBackup = false;
            mFallbackWallpaper.wallpaperId = makeWallpaperIdLocked();
            mFallbackWallpaper.mBindSource = BindSource.INITIALIZE_FALLBACK;
            bindWallpaperComponentLocked(mDefaultWallpaperComponent, true, false,
                    mFallbackWallpaper, null);
        }
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
            loadSettingsLocked(UserHandle.USER_SYSTEM, false, FLAG_SYSTEM | FLAG_LOCK);
            wallpaper = mWallpaperMap.get(UserHandle.USER_SYSTEM);
            wallpaper.wallpaperId = makeWallpaperIdLocked();    // always bump id at restore
            wallpaper.allowBackup = true;   // by definition if it was restored
            if (wallpaper.nextWallpaperComponent != null
                    && !wallpaper.nextWallpaperComponent.equals(mImageWallpaper)) {
                wallpaper.mBindSource = BindSource.RESTORE_SETTINGS_LIVE_SUCCESS;
                if (!bindWallpaperComponentLocked(wallpaper.nextWallpaperComponent, false, false,
                        wallpaper, null)) {
                    // No such live wallpaper or other failure; fall back to the default
                    // live wallpaper (since the profile being restored indicated that the
                    // user had selected a live rather than static one).
                    wallpaper.mBindSource = BindSource.RESTORE_SETTINGS_LIVE_FAILURE;
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
                    success = mWallpaperDataParser.restoreNamedResourceLocked(wallpaper);
                }
                if (DEBUG) Slog.v(TAG, "settingsRestored: success=" + success
                        + " id=" + wallpaper.wallpaperId);
                if (success) {
                    mWallpaperCropper.generateCrop(wallpaper); // based on the new image + metadata
                    wallpaper.mBindSource = BindSource.RESTORE_SETTINGS_STATIC;
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

    @Override // Binder call
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        new WallpaperManagerShellCommand(WallpaperManagerService.this).exec(this, in, out, err,
                args, callback, resultReceiver);
    }

    private void dumpWallpaper(WallpaperData wallpaper, PrintWriter pw) {
        if (wallpaper == null) {
            pw.println(" (null entry)");
            return;
        }
        pw.print(" User "); pw.print(wallpaper.userId);
        pw.print(": id="); pw.print(wallpaper.wallpaperId);
        pw.print(": mWhich="); pw.print(wallpaper.mWhich);
        pw.print(": mSystemWasBoth="); pw.print(wallpaper.mSystemWasBoth);
        pw.print(": mBindSource="); pw.println(wallpaper.mBindSource.name());
        pw.println(" Display state:");
        mWallpaperDisplayHelper.forEachDisplayData(wpSize -> {
            pw.print("  displayId=");
            pw.println(wpSize.mDisplayId);
            pw.print("  mWidth=");
            pw.print(wpSize.mWidth);
            pw.print("  mHeight=");
            pw.println(wpSize.mHeight);
            pw.print("  mPadding="); pw.println(wpSize.mPadding);
        });
        pw.print("  mCropHint="); pw.println(wallpaper.cropHint);
        pw.print("  mName=");  pw.println(wallpaper.name);
        pw.print("  mAllowBackup="); pw.println(wallpaper.allowBackup);
        pw.print("  mWallpaperComponent="); pw.println(wallpaper.wallpaperComponent);
        pw.print("  mWallpaperDimAmount="); pw.println(wallpaper.mWallpaperDimAmount);
        pw.print("  isColorExtracted="); pw.println(wallpaper.mIsColorExtractedFromDim);
        pw.println("  mUidToDimAmount:");
        for (int j = 0; j < wallpaper.mUidToDimAmount.size(); j++) {
            pw.print("    UID="); pw.print(wallpaper.mUidToDimAmount.keyAt(j));
            pw.print(" dimAmount="); pw.println(wallpaper.mUidToDimAmount.valueAt(j));
        }
        if (wallpaper.connection != null) {
            WallpaperConnection conn = wallpaper.connection;
            pw.print("  Wallpaper connection ");
            pw.print(conn);
            pw.println(":");
            if (conn.mInfo != null) {
                pw.print("    mInfo.component=");
                pw.println(conn.mInfo.getComponent());
            }
            conn.forEachDisplayConnector(connector -> {
                pw.print("     mDisplayId=");
                pw.println(connector.mDisplayId);
                pw.print("     mToken=");
                pw.println(connector.mToken);
                pw.print("     mEngine=");
                pw.println(connector.mEngine);
            });
            pw.print("    mService=");
            pw.println(conn.mService);
            pw.print("    mLastDiedTime=");
            pw.println(wallpaper.lastDiedTime - SystemClock.uptimeMillis());
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.print("mDefaultWallpaperComponent="); pw.println(mDefaultWallpaperComponent);
        pw.print("mImageWallpaper="); pw.println(mImageWallpaper);

        synchronized (mLock) {
            pw.println("System wallpaper state:");
            for (int i = 0; i < mWallpaperMap.size(); i++) {
                dumpWallpaper(mWallpaperMap.valueAt(i), pw);
            }
            pw.println("Lock wallpaper state:");
            for (int i = 0; i < mLockWallpaperMap.size(); i++) {
                dumpWallpaper(mLockWallpaperMap.valueAt(i), pw);
            }
            pw.println("Fallback wallpaper state:");
            if (mFallbackWallpaper != null) {
                dumpWallpaper(mFallbackWallpaper, pw);
            }
        }
    }
}
