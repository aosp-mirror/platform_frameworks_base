/*
 * Copyright (C) 2009 The Android Open Source Project
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

import static android.Manifest.permission.MANAGE_EXTERNAL_STORAGE;
import static android.Manifest.permission.READ_WALLPAPER_INTERNAL;
import static android.Manifest.permission.SET_WALLPAPER_DIM_AMOUNT;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

import static com.android.window.flags.Flags.FLAG_MULTI_CROP;
import static com.android.window.flags.Flags.multiCrop;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RawRes;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UiContext;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadSystemException;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Display;
import android.view.WindowManagerGlobal;

import com.android.internal.R;
import com.android.internal.annotations.Keep;

import libcore.io.IoUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Provides access to the system wallpaper. With WallpaperManager, you can
 * get the current wallpaper, get the desired dimensions for the wallpaper, set
 * the wallpaper, and more.
 *
 * <p> An app can check whether wallpapers are supported for the current user, by calling
 * {@link #isWallpaperSupported()}, and whether setting of wallpapers is allowed, by calling
 * {@link #isSetWallpaperAllowed()}.
 * Any public APIs added to WallpaperManager should have a corresponding stub in
 * {@link DisabledWallpaperManager}.
 */
@SystemService(Context.WALLPAPER_SERVICE)
public class WallpaperManager {

    private static String TAG = "WallpaperManager";
    private static final boolean DEBUG = false;

    /**
     * Trying to read the wallpaper file or bitmap in T will return
     * the default wallpaper bitmap/file instead of throwing a SecurityException.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    static final long RETURN_DEFAULT_ON_SECURITY_EXCEPTION = 239784307L;

    /**
     * In U and later, attempting to read the wallpaper file or bitmap will throw an exception,
     * (except with the READ_WALLPAPER_INTERNAL permission).
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    static final long THROW_ON_SECURITY_EXCEPTION = 237508058L;

    private float mWallpaperXStep = -1;
    private float mWallpaperYStep = -1;
    private static final @NonNull RectF LOCAL_COLOR_BOUNDS =
            new RectF(0, 0, 1, 1);

    /** {@hide} */
    private static final String PROP_WALLPAPER = "ro.config.wallpaper";
    /** {@hide} */
    private static final String PROP_LOCK_WALLPAPER = "ro.config.lock_wallpaper";
    /** {@hide} */
    private static final String PROP_WALLPAPER_COMPONENT = "ro.config.wallpaper_component";
    /** {@hide} */
    private static final String VALUE_CMF_COLOR =
            android.os.SystemProperties.get("ro.boot.hardware.color");
    /** {@hide} */
    private static final String WALLPAPER_CMF_PATH = "/wallpaper/image/";

    /**
     * Activity Action: Show settings for choosing wallpaper. Do not use directly to construct
     * an intent; instead, use {@link #getCropAndSetWallpaperIntent}.
     * <p>Input:  {@link Intent#getData} is the URI of the image to crop and set as wallpaper.
     * <p>Output: RESULT_OK if user decided to crop/set the wallpaper, RESULT_CANCEL otherwise
     * Activities that support this intent should specify a MIME filter of "image/*"
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CROP_AND_SET_WALLPAPER =
            "android.service.wallpaper.CROP_AND_SET_WALLPAPER";

    /**
     * Launch an activity for the user to pick the current global live
     * wallpaper.
     */
    public static final String ACTION_LIVE_WALLPAPER_CHOOSER
            = "android.service.wallpaper.LIVE_WALLPAPER_CHOOSER";

    /**
     * Directly launch live wallpaper preview, allowing the user to immediately
     * confirm to switch to a specific live wallpaper.  You must specify
     * {@link #EXTRA_LIVE_WALLPAPER_COMPONENT} with the ComponentName of
     * a live wallpaper component that is to be shown.
     */
    public static final String ACTION_CHANGE_LIVE_WALLPAPER
            = "android.service.wallpaper.CHANGE_LIVE_WALLPAPER";

    /**
     * Extra in {@link #ACTION_CHANGE_LIVE_WALLPAPER} that specifies the
     * ComponentName of a live wallpaper that should be shown as a preview,
     * for the user to confirm.
     */
    public static final String EXTRA_LIVE_WALLPAPER_COMPONENT
            = "android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT";

    /**
     * Manifest entry for activities that respond to {@link Intent#ACTION_SET_WALLPAPER}
     * which allows them to provide a custom large icon associated with this action.
     */
    public static final String WALLPAPER_PREVIEW_META_DATA = "android.wallpaper.preview";

    /**
     * Command for {@link #sendWallpaperCommand}: reported by the wallpaper
     * host when the user taps on an empty area (not performing an action
     * in the host).  The x and y arguments are the location of the tap in
     * screen coordinates.
     */
    public static final String COMMAND_TAP = "android.wallpaper.tap";

    /**
     * Command for {@link #sendWallpaperCommand}: reported by the wallpaper
     * host when the user releases a secondary pointer on an empty area
     * (not performing an action in the host).  The x and y arguments are
     * the location of the secondary tap in screen coordinates.
     */
    public static final String COMMAND_SECONDARY_TAP = "android.wallpaper.secondaryTap";

    /**
     * Command for {@link #sendWallpaperCommand}: reported by the wallpaper
     * host when the user drops an object into an area of the host.  The x
     * and y arguments are the location of the drop.
     */
    public static final String COMMAND_DROP = "android.home.drop";

    /**
     * Command for {@link #sendWallpaperCommand}: reported by System UI when the device is waking
     * up. The x and y arguments are a location (possibly very roughly) corresponding to the action
     * that caused the device to wake up. For example, if the power button was pressed, this will be
     * the location on the screen nearest the power button.
     *
     * If the location is unknown or not applicable, x and y will be -1.
     *
     * @hide
     */
    public static final String COMMAND_WAKING_UP = "android.wallpaper.wakingup";

    /**
     * Command for {@link #sendWallpaperCommand}: reported by System UI when the device keyguard
     * starts going away.
     * This command is triggered by {@link android.app.IActivityTaskManager#keyguardGoingAway(int)}.
     *
     * @hide
     */
    public static final String COMMAND_KEYGUARD_GOING_AWAY =
            "android.wallpaper.keyguardgoingaway";

    /**
     * Command for {@link #sendWallpaperCommand}: reported by System UI when the device is going to
     * sleep. The x and y arguments are a location (possibly very roughly) corresponding to the
     * action that caused the device to go to sleep. For example, if the power button was pressed,
     * this will be the location on the screen nearest the power button.
     *
     * If the location is unknown or not applicable, x and y will be -1.
     *
     * @hide
     */
    public static final String COMMAND_GOING_TO_SLEEP = "android.wallpaper.goingtosleep";

    /**
     * Command for {@link #sendWallpaperCommand}: reported when the wallpaper that was already
     * set is re-applied by the user.
     * @hide
     */
    public static final String COMMAND_REAPPLY = "android.wallpaper.reapply";

    /**
     * Command for {@link #sendWallpaperCommand}: reported when the live wallpaper needs to be
     * frozen.
     * @hide
     */
    public static final String COMMAND_FREEZE = "android.wallpaper.freeze";

    /**
     * Command for {@link #sendWallpaperCommand}: reported when the live wallapper doesn't need
     * to be frozen anymore.
     * @hide
     */
    public static final String COMMAND_UNFREEZE = "android.wallpaper.unfreeze";

    /**
     * Command for {@link #sendWallpaperCommand}: in sendWallpaperCommand put extra to this command
     * to give the bounds of space between the bottom of notifications and the top of shortcuts
     * @hide
     */
    public static final String COMMAND_LOCKSCREEN_LAYOUT_CHANGED =
            "android.wallpaper.lockscreen_layout_changed";

    /**
     * Extra passed back from setWallpaper() giving the new wallpaper's assigned ID.
     * @hide
     */
    public static final String EXTRA_NEW_WALLPAPER_ID = "android.service.wallpaper.extra.ID";

    /**
     * Extra passed on {@link Intent.ACTION_WALLPAPER_CHANGED} indicating if wallpaper was set from
     * a foreground app.
     * @hide
     */
    public static final String EXTRA_FROM_FOREGROUND_APP =
            "android.service.wallpaper.extra.FROM_FOREGROUND_APP";

    /**
     * Extra passed on {@link Intent.ACTION_WALLPAPER_CHANGED} indicating if wallpaper was set from
     * a foreground app.
     * @hide
     */
    public static final String EXTRA_WHICH_WALLPAPER_CHANGED =
            "android.service.wallpaper.extra.WHICH_WALLPAPER_CHANGED";

    /**
     * The different screen orientations. {@link #getOrientation} provides their exact definition.
     * This is only used internally by the framework and the WallpaperBackupAgent.
     * @hide
     */
    @IntDef(value = {
            ORIENTATION_UNKNOWN,
            PORTRAIT,
            LANDSCAPE,
            SQUARE_PORTRAIT,
            SQUARE_LANDSCAPE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScreenOrientation {}

    /**
     * @hide
     */
    public static final int ORIENTATION_UNKNOWN = -1;

    /**
     * Portrait orientation of most screens
     * @hide
     */
    public static final int PORTRAIT = 0;

    /**
     * Landscape orientation of most screens
     * @hide
     */
    public static final int LANDSCAPE = 1;

    /**
     * Portrait orientation with similar width and height (e.g. the inner screen of a foldable)
     * @hide
     */
    public static final int SQUARE_PORTRAIT = 2;

    /**
     * Landscape orientation with similar width and height (e.g. the inner screen of a foldable)
     * @hide
     */
    public static final int SQUARE_LANDSCAPE = 3;

    /**
     * Converts a (width, height) screen size to a {@link ScreenOrientation}.
     * @param screenSize the dimensions of a screen
     * @return the corresponding {@link ScreenOrientation}.
     * @hide
     */
    public static @ScreenOrientation int getOrientation(Point screenSize) {
        float ratio = ((float) screenSize.x) / screenSize.y;
        // ratios between 3/4 and 4/3 are considered square
        return ratio >= 4 / 3f ? LANDSCAPE
                : ratio > 1f ? SQUARE_LANDSCAPE
                : ratio > 3 / 4f ? SQUARE_PORTRAIT
                : PORTRAIT;
    }

    /**
     * Get the 90° rotation of a given orientation
     * @hide
     */
    public static @ScreenOrientation int getRotatedOrientation(@ScreenOrientation int orientation) {
        switch (orientation) {
            case PORTRAIT: return LANDSCAPE;
            case LANDSCAPE: return PORTRAIT;
            case SQUARE_PORTRAIT: return SQUARE_LANDSCAPE;
            case SQUARE_LANDSCAPE: return SQUARE_PORTRAIT;
            default: return ORIENTATION_UNKNOWN;
        }
    }

    // flags for which kind of wallpaper to act on

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_SYSTEM,
            FLAG_LOCK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SetWallpaperFlags {}

    /**
     * Flag: set or retrieve the general system wallpaper.
     */
    public static final int FLAG_SYSTEM = 1 << 0;

    /**
     * Flag: set or retrieve the lock-screen-specific wallpaper.
     */
    public static final int FLAG_LOCK = 1 << 1;

    private static final Object sSync = new Object[0];
    @UnsupportedAppUsage
    private static Globals sGlobals;
    private final Context mContext;
    private final boolean mWcgEnabled;
    private final ColorManagementProxy mCmProxy;
    private static Boolean sIsMultiCropEnabled = null;

    /**
     * Special drawable that draws a wallpaper as fast as possible.  Assumes
     * no scaling or placement off (0,0) of the wallpaper (this should be done
     * at the time the bitmap is loaded).
     */
    static class FastBitmapDrawable extends Drawable {
        private final Bitmap mBitmap;
        private final int mWidth;
        private final int mHeight;
        private int mDrawLeft;
        private int mDrawTop;
        private final Paint mPaint;

        private FastBitmapDrawable(Bitmap bitmap) {
            mBitmap = bitmap;
            mWidth = bitmap.getWidth();
            mHeight = bitmap.getHeight();

            setBounds(0, 0, mWidth, mHeight);

            mPaint = new Paint();
            mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawBitmap(mBitmap, mDrawLeft, mDrawTop, mPaint);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }

        @Override
        public void setBounds(int left, int top, int right, int bottom) {
            mDrawLeft = left + (right-left - mWidth) / 2;
            mDrawTop = top + (bottom-top - mHeight) / 2;
        }

        @Override
        public void setAlpha(int alpha) {
            throw new UnsupportedOperationException("Not supported with this drawable");
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            throw new UnsupportedOperationException("Not supported with this drawable");
        }

        @Override
        public void setDither(boolean dither) {
            throw new UnsupportedOperationException("Not supported with this drawable");
        }

        @Override
        public void setFilterBitmap(boolean filter) {
            throw new UnsupportedOperationException("Not supported with this drawable");
        }

        @Override
        public int getIntrinsicWidth() {
            return mWidth;
        }

        @Override
        public int getIntrinsicHeight() {
            return mHeight;
        }

        @Override
        public int getMinimumWidth() {
            return mWidth;
        }

        @Override
        public int getMinimumHeight() {
            return mHeight;
        }
    }

    /**
     * Convenience class representing a cached wallpaper bitmap and associated data.
     */
    private static class CachedWallpaper {
        final Bitmap mCachedWallpaper;
        final int mCachedWallpaperUserId;
        @SetWallpaperFlags final int mWhich;

        CachedWallpaper(Bitmap cachedWallpaper, int cachedWallpaperUserId,
                @SetWallpaperFlags int which) {
            mCachedWallpaper = cachedWallpaper;
            mCachedWallpaperUserId = cachedWallpaperUserId;
            mWhich = which;
        }

        /**
         * Returns true if this object represents a valid cached bitmap for the given parameters,
         * otherwise false.
         */
        boolean isValid(int userId, @SetWallpaperFlags int which) {
            return userId == mCachedWallpaperUserId && which == mWhich
                    && !mCachedWallpaper.isRecycled();
        }
    }

    private static class Globals extends IWallpaperManagerCallback.Stub {
        private final IWallpaperManager mService;
        private boolean mColorCallbackRegistered;
        private final ArrayList<Pair<OnColorsChangedListener, Handler>> mColorListeners =
                new ArrayList<>();
        private CachedWallpaper mCachedWallpaper;
        private Bitmap mDefaultWallpaper;
        private Handler mMainLooperHandler;
        private ArrayMap<LocalWallpaperColorConsumer, ArraySet<RectF>> mLocalColorCallbackAreas =
                        new ArrayMap<>();
        private ILocalWallpaperColorConsumer mLocalColorCallback =
                new ILocalWallpaperColorConsumer.Stub() {
                    @Override
                    public void onColorsChanged(RectF area, WallpaperColors colors) {
                        for (LocalWallpaperColorConsumer callback :
                                mLocalColorCallbackAreas.keySet()) {
                            ArraySet<RectF> areas = mLocalColorCallbackAreas.get(callback);
                            if (areas != null && areas.contains(area)) {
                                callback.onColorsChanged(area, colors);
                            }
                        }
                    }
                };

        Globals(IWallpaperManager service, Looper looper) {
            mService = service;
            mMainLooperHandler = new Handler(looper);
            forgetLoadedWallpaper();
        }

        public void onWallpaperChanged() {
            /* The wallpaper has changed but we shouldn't eagerly load the
             * wallpaper as that would be inefficient. Reset the cached wallpaper
             * to null so if the user requests the wallpaper again then we'll
             * fetch it.
             */
            forgetLoadedWallpaper();
        }

        /**
         * Start listening to wallpaper color events.
         * Will be called whenever someone changes their wallpaper or if a live wallpaper
         * changes its colors.
         * @param callback Listener
         * @param handler Thread to call it from. Main thread if null.
         * @param userId Owner of the wallpaper or UserHandle.USER_ALL
         * @param displayId Caller comes from which display
         */
        public void addOnColorsChangedListener(@NonNull OnColorsChangedListener callback,
                @Nullable Handler handler, int userId, int displayId) {
            synchronized (this) {
                if (!mColorCallbackRegistered) {
                    try {
                        mService.registerWallpaperColorsCallback(this, userId, displayId);
                        mColorCallbackRegistered = true;
                    } catch (RemoteException e) {
                        // Failed, service is gone
                        Log.w(TAG, "Can't register for color updates", e);
                    }
                }
                mColorListeners.add(new Pair<>(callback, handler));
            }
        }

        public void addOnColorsChangedListener(
                @NonNull LocalWallpaperColorConsumer callback,
                @NonNull List<RectF> regions, int which, int userId, int displayId) {
            synchronized (this) {
                for (RectF area : regions) {
                    ArraySet<RectF> areas = mLocalColorCallbackAreas.get(callback);
                    if (areas == null) {
                        areas = new ArraySet<>();
                        mLocalColorCallbackAreas.put(callback, areas);
                    }
                    areas.add(area);
                }
                try {
                    // one way returns immediately
                    mService.addOnLocalColorsChangedListener(mLocalColorCallback, regions, which,
                            userId, displayId);
                } catch (RemoteException e) {
                    // Can't get colors, connection lost.
                    Log.e(TAG, "Can't register for local color updates", e);
                }
            }
        }

        public void removeOnColorsChangedListener(
                @NonNull LocalWallpaperColorConsumer callback, int which, int userId,
                int displayId) {
            synchronized (this) {
                final ArraySet<RectF> removeAreas = mLocalColorCallbackAreas.remove(callback);
                if (removeAreas == null || removeAreas.size() == 0) {
                    return;
                }
                for (LocalWallpaperColorConsumer cb : mLocalColorCallbackAreas.keySet()) {
                    ArraySet<RectF> areas = mLocalColorCallbackAreas.get(cb);
                    if (areas != null && cb != callback) removeAreas.removeAll(areas);
                }
                try {
                    if (removeAreas.size() > 0) {
                        // one way returns immediately
                        mService.removeOnLocalColorsChangedListener(
                                mLocalColorCallback, new ArrayList(removeAreas), which, userId,
                                displayId);
                    }
                } catch (RemoteException e) {
                    // Can't get colors, connection lost.
                    Log.e(TAG, "Can't unregister for local color updates", e);
                }
            }
        }

        /**
         * Stop listening to wallpaper color events.
         *
         * @param callback listener
         * @param userId Owner of the wallpaper or UserHandle.USER_ALL
         * @param displayId Which display is interested
         */
        public void removeOnColorsChangedListener(@NonNull OnColorsChangedListener callback,
                int userId, int displayId) {
            synchronized (this) {
                mColorListeners.removeIf(pair -> pair.first == callback);

                if (mColorListeners.size() == 0 && mColorCallbackRegistered) {
                    mColorCallbackRegistered = false;
                    try {
                        mService.unregisterWallpaperColorsCallback(this, userId, displayId);
                    } catch (RemoteException e) {
                        // Failed, service is gone
                        Log.w(TAG, "Can't unregister color updates", e);
                    }
                }
            }
        }

        @Override
        public void onWallpaperColorsChanged(WallpaperColors colors, int which, int userId) {
            synchronized (this) {
                for (Pair<OnColorsChangedListener, Handler> listener : mColorListeners) {
                    Handler handler = listener.second;
                    if (listener.second == null) {
                        handler = mMainLooperHandler;
                    }
                    handler.post(() -> {
                        // Dealing with race conditions between posting a callback and
                        // removeOnColorsChangedListener being called.
                        boolean stillExists;
                        synchronized (sGlobals) {
                            stillExists = mColorListeners.contains(listener);
                        }
                        if (stillExists) {
                            listener.first.onColorsChanged(colors, which, userId);
                        }
                    });
                }
            }
        }

        WallpaperColors getWallpaperColors(int which, int userId, int displayId) {
            checkExactlyOneWallpaperFlagSet(which);

            try {
                return mService.getWallpaperColors(which, userId, displayId);
            } catch (RemoteException e) {
                // Can't get colors, connection lost.
            }
            return null;
        }

        public Bitmap peekWallpaperBitmap(Context context, boolean returnDefault,
                @SetWallpaperFlags int which, ColorManagementProxy cmProxy) {
            return peekWallpaperBitmap(context, returnDefault, which, context.getUserId(),
                    false /* hardware */, cmProxy);
        }

        /**
         * Retrieves the current wallpaper Bitmap, caching the result. If this fails and
         * `returnDefault` is set, returns the Bitmap for the default wallpaper; otherwise returns
         * null.
         *
         * More sophisticated caching might a) store and compare the wallpaper ID so that
         * consecutive calls for FLAG_SYSTEM and FLAG_LOCK could return the cached wallpaper if
         * no lock screen wallpaper is set, or b) separately cache home and lock screen wallpaper.
         */
        public Bitmap peekWallpaperBitmap(Context context, boolean returnDefault,
                @SetWallpaperFlags int which, int userId, boolean hardware,
                ColorManagementProxy cmProxy) {
            if (mService != null) {
                try {
                    Trace.beginSection("WPMS.isWallpaperSupported");
                    if (!mService.isWallpaperSupported(context.getOpPackageName())) {
                        return null;
                    }
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                } finally {
                    Trace.endSection();
                }
            }
            synchronized (this) {
                if (mCachedWallpaper != null && mCachedWallpaper.isValid(userId, which) && context
                        .checkSelfPermission(READ_WALLPAPER_INTERNAL) == PERMISSION_GRANTED) {
                    return mCachedWallpaper.mCachedWallpaper;
                }
                mCachedWallpaper = null;
                Bitmap currentWallpaper = null;
                try {
                    Trace.beginSection("WPMS.getCurrentWallpaperLocked");
                    currentWallpaper = getCurrentWallpaperLocked(
                            context, which, userId, hardware, cmProxy);
                } catch (OutOfMemoryError e) {
                    Log.w(TAG, "Out of memory loading the current wallpaper: " + e);
                } catch (SecurityException e) {
                    /*
                     * Apps with target SDK <= S can still access the wallpaper through
                     * READ_EXTERNAL_STORAGE. In T however, app that previously had access to the
                     * wallpaper via READ_EXTERNAL_STORAGE will get a SecurityException here.
                     * Thus, in T specifically, return the default wallpaper instead of crashing.
                     */
                    if (CompatChanges.isChangeEnabled(RETURN_DEFAULT_ON_SECURITY_EXCEPTION)
                            && !CompatChanges.isChangeEnabled(THROW_ON_SECURITY_EXCEPTION)) {
                        Log.w(TAG, "No permission to access wallpaper, returning default"
                                + " wallpaper to avoid crashing legacy app.");
                        return getDefaultWallpaper(context, FLAG_SYSTEM);
                    }

                    if (context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.O_MR1) {
                        Log.w(TAG, "No permission to access wallpaper, suppressing"
                                + " exception to avoid crashing legacy app.");
                    } else {
                        // Post-O apps really most sincerely need the permission.
                        throw e;
                    }
                } finally {
                    Trace.endSection();
                }
                if (currentWallpaper != null) {
                    mCachedWallpaper = new CachedWallpaper(currentWallpaper, userId, which);
                    return currentWallpaper;
                }
            }
            if (returnDefault || (which == FLAG_LOCK && isStaticWallpaper(FLAG_LOCK))) {
                return getDefaultWallpaper(context, which);
            }
            return null;
        }

        @Nullable
        public Rect peekWallpaperDimensions(Context context, boolean returnDefault,
                @SetWallpaperFlags int which, int userId) {
            if (mService != null) {
                try {
                    if (!mService.isWallpaperSupported(context.getOpPackageName())) {
                        return new Rect();
                    }
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }

            Rect dimensions = null;
            synchronized (this) {
                Bundle params = new Bundle();
                try (ParcelFileDescriptor pfd = mService.getWallpaperWithFeature(
                        context.getOpPackageName(), context.getAttributionTag(), this, which,
                        params, userId, /* getCropped = */ true)) {
                    // Let's peek user wallpaper first.
                    if (pfd != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, options);
                        dimensions = new Rect(0, 0, options.outWidth, options.outHeight);
                    }
                } catch (RemoteException ex) {
                    Log.w(TAG, "peek wallpaper dimensions failed", ex);
                } catch (IOException ignored) {
                    // This is only thrown on close and can be safely ignored.
                }
            }
            // If user wallpaper is unavailable, may be the default one instead.
            if ((dimensions == null || dimensions.width() == 0 || dimensions.height() == 0)
                    && (returnDefault || (which == FLAG_LOCK && isStaticWallpaper(FLAG_LOCK)))) {
                InputStream is = openDefaultWallpaper(context, which);
                if (is != null) {
                    try {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeStream(is, null, options);
                        dimensions = new Rect(0, 0, options.outWidth, options.outHeight);
                    } finally {
                        IoUtils.closeQuietly(is);
                    }
                }
            }
            return dimensions;
        }

        void forgetLoadedWallpaper() {
            synchronized (this) {
                mCachedWallpaper = null;
                mDefaultWallpaper = null;
            }
        }

        private Bitmap getCurrentWallpaperLocked(Context context, @SetWallpaperFlags int which,
                int userId, boolean hardware, ColorManagementProxy cmProxy) {
            if (mService == null) {
                Log.w(TAG, "WallpaperService not running");
                return null;
            }

            try {
                Bundle params = new Bundle();
                Trace.beginSection("WPMS.getWallpaperWithFeature_" + which);
                ParcelFileDescriptor pfd = mService.getWallpaperWithFeature(
                        context.getOpPackageName(), context.getAttributionTag(), this, which,
                        params, userId, /* getCropped = */ true);
                Trace.endSection();

                if (pfd == null) {
                    return null;
                }
                try (InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                    ImageDecoder.Source src = ImageDecoder.createSource(context.getResources(), is);
                    return ImageDecoder.decodeBitmap(src, ((decoder, info, source) -> {
                        // Mutable and hardware config can't be set at the same time.
                        decoder.setMutableRequired(!hardware);
                        // Let's do color management
                        if (cmProxy != null) {
                            cmProxy.doColorManagement(decoder, info);
                        }
                    }));
                } catch (OutOfMemoryError | IOException e) {
                    Log.w(TAG, "Can't decode file", e);
                }
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            return null;
        }

        private Bitmap getDefaultWallpaper(Context context, @SetWallpaperFlags int which) {
            Trace.beginSection("WPMS.getDefaultWallpaper_" + which);
            Bitmap defaultWallpaper = mDefaultWallpaper;
            if (defaultWallpaper == null || defaultWallpaper.isRecycled()) {
                defaultWallpaper = null;
                Trace.beginSection("WPMS.openDefaultWallpaper");
                try (InputStream is = openDefaultWallpaper(context, which)) {
                    Trace.endSection();
                    if (is != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        Trace.beginSection("WPMS.decodeStream");
                        defaultWallpaper = BitmapFactory.decodeStream(is, null, options);
                        Trace.endSection();
                    }
                } catch (OutOfMemoryError | IOException e) {
                    Log.w(TAG, "Can't decode stream", e);
                }
            }
            synchronized (this) {
                mDefaultWallpaper = defaultWallpaper;
            }
            Trace.endSection();
            return defaultWallpaper;
        }

        /**
         * Return true if there is a static wallpaper on the specified screen.
         * With {@code which=}{@link #FLAG_LOCK}, always return false if the lockscreen doesn't run
         * its own wallpaper engine.
         */
        private boolean isStaticWallpaper(@SetWallpaperFlags int which) {
            if (mService == null) {
                Log.w(TAG, "WallpaperService not running");
                throw new RuntimeException(new DeadSystemException());
            }
            try {
                return mService.isStaticWallpaper(which);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    static void initGlobals(IWallpaperManager service, Looper looper) {
        synchronized (sSync) {
            if (sGlobals == null) {
                sGlobals = new Globals(service, looper);
            }
        }
    }

    /*package*/ WallpaperManager(IWallpaperManager service, @UiContext Context context,
            Handler handler) {
        mContext = context;
        if (service != null) {
            initGlobals(service, context.getMainLooper());
        }
        // Check if supports mixed color spaces composition in hardware.
        mWcgEnabled = context.getResources().getConfiguration().isScreenWideColorGamut()
                && context.getResources().getBoolean(R.bool.config_enableWcgMode);
        mCmProxy = new ColorManagementProxy(context);
    }

    // no-op constructor called just by DisabledWallpaperManager
    /*package*/ WallpaperManager() {
        mContext = null;
        mCmProxy = null;
        mWcgEnabled = false;
    }

    /**
     * Retrieve a WallpaperManager associated with the given Context.
     */
    public static WallpaperManager getInstance(Context context) {
        return (WallpaperManager)context.getSystemService(
                Context.WALLPAPER_SERVICE);
    }

    /** @hide */
    @UnsupportedAppUsage
    public IWallpaperManager getIWallpaperManager() {
        return sGlobals.mService;
    }

    /**
     * TODO (b/305908217) remove
     * Temporary method for project b/197814683.
     * @return true if the lockscreen wallpaper always uses a wallpaperService, not a static image
     * @hide
     */
    @TestApi
    public boolean isLockscreenLiveWallpaperEnabled() {
        return true;
    }

    /**
     * Temporary method for project b/270726737
     * @return true if the wallpaper supports different crops for different display dimensions
     * @hide
     */
    public static boolean isMultiCropEnabled() {
        if (sIsMultiCropEnabled == null) {
            sIsMultiCropEnabled = multiCrop();
        }
        return sIsMultiCropEnabled;
    }

    /**
     * Indicate whether wcg (Wide Color Gamut) should be enabled.
     * <p>
     * Some devices lack of capability of mixed color spaces composition,
     * enable wcg on such devices might cause memory or battery concern.
     * <p>
     * Therefore, in addition to {@link Configuration#isScreenWideColorGamut()},
     * we also take mixed color spaces composition (config_enableWcgMode) into account.
     *
     * @see Configuration#isScreenWideColorGamut()
     * @return True if wcg should be enabled for this device.
     * @hide
     */
    @TestApi
    public boolean shouldEnableWideColorGamut() {
        return mWcgEnabled;
    }

    /**
     * <strong> Important note: </strong>
     * <ul>
     *     <li>Up to Android 12, this method requires the
     *     {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} permission.</li>
     *     <li>Starting in Android 13, directly accessing the wallpaper is not possible anymore,
     *     instead the default system wallpaper is returned
     *     (some versions of Android 13 may throw a {@code SecurityException}).</li>
     *     <li>From Android 14, this method should not be used
     *     and will always throw a {@code SecurityException}.</li>
     *     <li> Apps with {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE}
     *     can still access the real wallpaper on all versions. </li>
     * </ul>
     *
     * <p>
     * Equivalent to {@link #getDrawable(int)} with {@code which=}{@link #FLAG_SYSTEM}.
     * </p>
     *
     * @return A Drawable object for the requested wallpaper.
     *
     * @see #getDrawable(int)
     *
     * @throws SecurityException as described in the note
     */
    @Nullable
    @RequiresPermission(anyOf = {MANAGE_EXTERNAL_STORAGE, READ_WALLPAPER_INTERNAL})
    public Drawable getDrawable() {
        return getDrawable(FLAG_SYSTEM);
    }

    /**
     * <strong> Important note: </strong> only apps with
     * {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE} should use this method.
     * Otherwise, a {@code SecurityException} will be thrown.
     *
     * <p>
     * Retrieve the requested wallpaper for the specified wallpaper type if the wallpaper is not
     * a live wallpaper. This method should not be used to display the user wallpaper on an app:
     * {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WALLPAPER} should be used instead.
     * </p>
     * <p>
     * When called with {@code which=}{@link #FLAG_SYSTEM},
     * if there is a live wallpaper on home screen, the built-in default wallpaper is returned.
     * </p>
     * <p>
     * When called with {@code which=}{@link #FLAG_LOCK}, if there is a live wallpaper
     * on lock screen, or if the lock screen and home screen share the same wallpaper engine,
     * {@code null} is returned.
     * </p>
     * <p>
     * {@link #getWallpaperInfo(int)} can be used to determine whether there is a live wallpaper
     * on a specified screen type.
     * </p>
     *
     * @param which The {@code FLAG_*} identifier of a valid wallpaper type. Throws
     *     IllegalArgumentException if an invalid wallpaper is requested.
     * @return A Drawable object for the requested wallpaper.
     *
     * @throws SecurityException as described in the note
     */
    @Nullable
    @RequiresPermission(anyOf = {MANAGE_EXTERNAL_STORAGE, READ_WALLPAPER_INTERNAL})
    public Drawable getDrawable(@SetWallpaperFlags int which) {
        final ColorManagementProxy cmProxy = getColorManagementProxy();
        boolean returnDefault = which != FLAG_LOCK;
        Bitmap bm = sGlobals.peekWallpaperBitmap(mContext, returnDefault, which, cmProxy);
        if (bm != null) {
            Drawable dr = new BitmapDrawable(mContext.getResources(), bm);
            dr.setDither(false);
            return dr;
        }
        return null;
    }

    /**
     * Obtain a drawable for the built-in static system wallpaper.
     */
    public Drawable getBuiltInDrawable() {
        return getBuiltInDrawable(0, 0, false, 0, 0, FLAG_SYSTEM);
    }

    /**
     * Obtain a drawable for the specified built-in static system wallpaper.
     *
     * @param which The {@code FLAG_*} identifier of a valid wallpaper type.  Throws
     *     IllegalArgumentException if an invalid wallpaper is requested.
     * @return A Drawable presenting the specified wallpaper image, or {@code null}
     *     if no built-in default image for that wallpaper type exists.
     */
    public Drawable getBuiltInDrawable(@SetWallpaperFlags int which) {
        return getBuiltInDrawable(0, 0, false, 0, 0, which);
    }

    /**
     * Returns a drawable for the system built-in static wallpaper. Based on the parameters, the
     * drawable can be cropped and scaled
     *
     * @param outWidth The width of the returned drawable
     * @param outWidth The height of the returned drawable
     * @param scaleToFit If true, scale the wallpaper down rather than just cropping it
     * @param horizontalAlignment A float value between 0 and 1 specifying where to crop the image;
     *        0 for left-aligned, 0.5 for horizontal center-aligned, and 1 for right-aligned
     * @param verticalAlignment A float value between 0 and 1 specifying where to crop the image;
     *        0 for top-aligned, 0.5 for vertical center-aligned, and 1 for bottom-aligned
     * @return A Drawable presenting the built-in default system wallpaper image,
     *        or {@code null} if no such default image is defined on this device.
     */
    public Drawable getBuiltInDrawable(int outWidth, int outHeight,
            boolean scaleToFit, float horizontalAlignment, float verticalAlignment) {
        return getBuiltInDrawable(outWidth, outHeight, scaleToFit,
                horizontalAlignment, verticalAlignment, FLAG_SYSTEM);
    }

    /**
     * Returns a drawable for the built-in static wallpaper of the specified type.  Based on the
     * parameters, the drawable can be cropped and scaled.
     *
     * @param outWidth The width of the returned drawable
     * @param outWidth The height of the returned drawable
     * @param scaleToFit If true, scale the wallpaper down rather than just cropping it
     * @param horizontalAlignment A float value between 0 and 1 specifying where to crop the image;
     *        0 for left-aligned, 0.5 for horizontal center-aligned, and 1 for right-aligned
     * @param verticalAlignment A float value between 0 and 1 specifying where to crop the image;
     *        0 for top-aligned, 0.5 for vertical center-aligned, and 1 for bottom-aligned
     * @param which The {@code FLAG_*} identifier of a valid wallpaper type.  Throws
     *     IllegalArgumentException if an invalid wallpaper is requested.
     * @return A Drawable presenting the built-in default wallpaper image of the given type,
     *        or {@code null} if no default image of that type is defined on this device.
     */
    public Drawable getBuiltInDrawable(int outWidth, int outHeight, boolean scaleToFit,
            float horizontalAlignment, float verticalAlignment, @SetWallpaperFlags int which) {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }

        checkExactlyOneWallpaperFlagSet(which);

        Resources resources = mContext.getResources();
        horizontalAlignment = Math.max(0, Math.min(1, horizontalAlignment));
        verticalAlignment = Math.max(0, Math.min(1, verticalAlignment));

        InputStream wpStream = openDefaultWallpaper(mContext, which);
        if (wpStream == null) {
            if (DEBUG) {
                Log.w(TAG, "default wallpaper stream " + which + " is null");
            }
            return null;
        } else {
            InputStream is = new BufferedInputStream(wpStream);
            if (outWidth <= 0 || outHeight <= 0) {
                Bitmap fullSize = BitmapFactory.decodeStream(is, null, null);
                return new BitmapDrawable(resources, fullSize);
            } else {
                int inWidth;
                int inHeight;
                // Just measure this time through...
                {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(is, null, options);
                    if (options.outWidth != 0 && options.outHeight != 0) {
                        inWidth = options.outWidth;
                        inHeight = options.outHeight;
                    } else {
                        Log.e(TAG, "default wallpaper dimensions are 0");
                        return null;
                    }
                }

                // Reopen the stream to do the full decode.  We know at this point
                // that openDefaultWallpaper() will return non-null.
                is = new BufferedInputStream(openDefaultWallpaper(mContext, which));

                RectF cropRectF;

                outWidth = Math.min(inWidth, outWidth);
                outHeight = Math.min(inHeight, outHeight);
                if (scaleToFit) {
                    cropRectF = getMaxCropRect(inWidth, inHeight, outWidth, outHeight,
                        horizontalAlignment, verticalAlignment);
                } else {
                    float left = (inWidth - outWidth) * horizontalAlignment;
                    float right = left + outWidth;
                    float top = (inHeight - outHeight) * verticalAlignment;
                    float bottom = top + outHeight;
                    cropRectF = new RectF(left, top, right, bottom);
                }
                Rect roundedTrueCrop = new Rect();
                cropRectF.roundOut(roundedTrueCrop);

                if (roundedTrueCrop.width() <= 0 || roundedTrueCrop.height() <= 0) {
                    Log.w(TAG, "crop has bad values for full size image");
                    return null;
                }

                // See how much we're reducing the size of the image
                int scaleDownSampleSize = Math.min(roundedTrueCrop.width() / outWidth,
                        roundedTrueCrop.height() / outHeight);

                // Attempt to open a region decoder
                BitmapRegionDecoder decoder = null;
                try {
                    decoder = BitmapRegionDecoder.newInstance(is, true);
                } catch (IOException e) {
                    Log.w(TAG, "cannot open region decoder for default wallpaper");
                }

                Bitmap crop = null;
                if (decoder != null) {
                    // Do region decoding to get crop bitmap
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    if (scaleDownSampleSize > 1) {
                        options.inSampleSize = scaleDownSampleSize;
                    }
                    crop = decoder.decodeRegion(roundedTrueCrop, options);
                    decoder.recycle();
                }

                if (crop == null) {
                    // BitmapRegionDecoder has failed, try to crop in-memory. We know at
                    // this point that openDefaultWallpaper() will return non-null.
                    is = new BufferedInputStream(openDefaultWallpaper(mContext, which));
                    Bitmap fullSize = null;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    if (scaleDownSampleSize > 1) {
                        options.inSampleSize = scaleDownSampleSize;
                    }
                    fullSize = BitmapFactory.decodeStream(is, null, options);
                    if (fullSize != null) {
                        crop = Bitmap.createBitmap(fullSize, roundedTrueCrop.left,
                                roundedTrueCrop.top, roundedTrueCrop.width(),
                                roundedTrueCrop.height());
                    }
                }

                if (crop == null) {
                    Log.w(TAG, "cannot decode default wallpaper");
                    return null;
                }

                // Scale down if necessary
                if (outWidth > 0 && outHeight > 0 &&
                        (crop.getWidth() != outWidth || crop.getHeight() != outHeight)) {
                    Matrix m = new Matrix();
                    RectF cropRect = new RectF(0, 0, crop.getWidth(), crop.getHeight());
                    RectF returnRect = new RectF(0, 0, outWidth, outHeight);
                    m.setRectToRect(cropRect, returnRect, Matrix.ScaleToFit.FILL);
                    Bitmap tmp = Bitmap.createBitmap((int) returnRect.width(),
                            (int) returnRect.height(), Bitmap.Config.ARGB_8888);
                    if (tmp != null) {
                        Canvas c = new Canvas(tmp);
                        Paint p = new Paint();
                        p.setFilterBitmap(true);
                        c.drawBitmap(crop, m, p);
                        crop = tmp;
                    }
                }

                return new BitmapDrawable(resources, crop);
            }
        }
    }

    private static RectF getMaxCropRect(int inWidth, int inHeight, int outWidth, int outHeight,
                float horizontalAlignment, float verticalAlignment) {
        RectF cropRect = new RectF();
        // Get a crop rect that will fit this
        if (inWidth / (float) inHeight > outWidth / (float) outHeight) {
             cropRect.top = 0;
             cropRect.bottom = inHeight;
             float cropWidth = outWidth * (inHeight / (float) outHeight);
             cropRect.left = (inWidth - cropWidth) * horizontalAlignment;
             cropRect.right = cropRect.left + cropWidth;
        } else {
            cropRect.left = 0;
            cropRect.right = inWidth;
            float cropHeight = outHeight * (inWidth / (float) outWidth);
            cropRect.top = (inHeight - cropHeight) * verticalAlignment;
            cropRect.bottom = cropRect.top + cropHeight;
        }
        return cropRect;
    }

    /**
     * <strong> Important note: </strong>
     * <ul>
     *     <li>Up to Android 12, this method requires the
     *     {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} permission.</li>
     *     <li>Starting in Android 13, directly accessing the wallpaper is not possible anymore,
     *     instead the default system wallpaper is returned
     *     (some versions of Android 13 may throw a {@code SecurityException}).</li>
     *     <li>From Android 14, this method should not be used
     *     and will always throw a {@code SecurityException}.</li>
     *     <li> Apps with {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE}
     *     can still access the real wallpaper on all versions. </li>
     * </ul>
     *
     * <p>
     * Equivalent to {@link #getDrawable()}.
     * </p>
     *
     * @return A Drawable object for the requested wallpaper.
     *
     * @see #getDrawable()
     *
     * @throws SecurityException as described in the note
     */
    @Nullable
    @RequiresPermission(anyOf = {MANAGE_EXTERNAL_STORAGE, READ_WALLPAPER_INTERNAL})
    public Drawable peekDrawable() {
        return peekDrawable(FLAG_SYSTEM);
    }

    /**
     * <strong> Important note: </strong> only apps with
     * {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE} should use this method.
     * Otherwise, a {@code SecurityException} will be thrown.
     *
     * <p>
     * Equivalent to {@link #getDrawable(int)}.
     * </p>
     *
     * @param which The {@code FLAG_*} identifier of a valid wallpaper type. Throws
     *     IllegalArgumentException if an invalid wallpaper is requested.
     * @return A Drawable object for the requested wallpaper.
     *
     * @see #getDrawable(int)
     *
     * @throws SecurityException as described in the note
     */
    @Nullable
    @RequiresPermission(anyOf = {MANAGE_EXTERNAL_STORAGE, READ_WALLPAPER_INTERNAL})
    public Drawable peekDrawable(@SetWallpaperFlags int which) {
        return getDrawable(which);
    }

    /**
     * <strong> Important note: </strong>
     * <ul>
     *     <li>Up to Android 12, this method requires the
     *     {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} permission.</li>
     *     <li>Starting in Android 13, directly accessing the wallpaper is not possible anymore,
     *     instead the default wallpaper is returned
     *     (some versions of Android 13 may throw a {@code SecurityException}).</li>
     *     <li>From Android 14, this method should not be used
     *     and will always throw a {@code SecurityException}.</li>
     *     <li> Apps with {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE}
     *     can still access the real wallpaper on all versions. </li>
     * </ul>
     *
     * <p>
     * Equivalent to {@link #getFastDrawable(int)} with {@code which=}{@link #FLAG_SYSTEM}.
     * </p>
     *
     * @return A Drawable object for the requested wallpaper.
     *
     * @see #getFastDrawable(int)
     *
     * @throws SecurityException as described in the note
     */
    @Nullable
    @RequiresPermission(anyOf = {MANAGE_EXTERNAL_STORAGE, READ_WALLPAPER_INTERNAL})
    public Drawable getFastDrawable() {
        return getFastDrawable(FLAG_SYSTEM);
    }

    /**
     * <strong> Important note: </strong> only apps with
     * {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE} should use this method.
     * Otherwise, a {@code SecurityException} will be thrown.
     *
     * Like {@link #getDrawable(int)}, but the returned Drawable has a number
     * of limitations to reduce its overhead as much as possible. It will
     * never scale the wallpaper (only centering it if the requested bounds
     * do match the bitmap bounds, which should not be typical), doesn't
     * allow setting an alpha, color filter, or other attributes, etc.  The
     * bounds of the returned drawable will be initialized to the same bounds
     * as the wallpaper, so normally you will not need to touch it.  The
     * drawable also assumes that it will be used in a context running in
     * the same density as the screen (not in density compatibility mode).
     *
     * @param which The {@code FLAG_*} identifier of a valid wallpaper type.  Throws
     *     IllegalArgumentException if an invalid wallpaper is requested.
     * @return An optimized Drawable object for the requested wallpaper, or {@code null}
     *     in some cases as specified in {@link #getDrawable(int)}.
     *
     * @throws SecurityException as described in the note
     */
    @Nullable
    @RequiresPermission(anyOf = {MANAGE_EXTERNAL_STORAGE, READ_WALLPAPER_INTERNAL})
    public Drawable getFastDrawable(@SetWallpaperFlags int which) {
        final ColorManagementProxy cmProxy = getColorManagementProxy();
        boolean returnDefault = which != FLAG_LOCK;
        Bitmap bm = sGlobals.peekWallpaperBitmap(mContext, returnDefault, which, cmProxy);
        if (bm != null) {
            return new FastBitmapDrawable(bm);
        }
        return null;
    }

    /**
     * <strong> Important note: </strong> only apps with
     * {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE} should use this method.
     * Otherwise, a {@code SecurityException} will be thrown.
     *
     * <p>
     * Equivalent to {@link #getFastDrawable()}.
     * </p>
     *
     * @return An optimized Drawable object for the requested wallpaper.
     *
     * @see #getFastDrawable()
     *
     * @throws SecurityException as described in the note
     */
    @Nullable
    @RequiresPermission(anyOf = {MANAGE_EXTERNAL_STORAGE, READ_WALLPAPER_INTERNAL})
    public Drawable peekFastDrawable() {
        return peekFastDrawable(FLAG_SYSTEM);
    }

    /**
     * <strong> Important note: </strong> only apps with
     * {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE}
     * should use this method. Otherwise, a {@code SecurityException} will be thrown.
     *
     * <p>
     * Equivalent to {@link #getFastDrawable(int)}.
     * </p>
     *
     * @param which The {@code FLAG_*} identifier of a valid wallpaper type.  Throws
     *     IllegalArgumentException if an invalid wallpaper is requested.
     * @return An optimized Drawable object for the requested wallpaper.
     *
     * @throws SecurityException as described in the note
     */
    @Nullable
    @RequiresPermission(anyOf = {MANAGE_EXTERNAL_STORAGE, READ_WALLPAPER_INTERNAL})
    public Drawable peekFastDrawable(@SetWallpaperFlags int which) {
        return getFastDrawable(which);
    }

    /**
     * Whether the wallpaper supports Wide Color Gamut or not. This is only meant to be used by
     * ImageWallpaper, and will always return false if the wallpaper for the specified screen
     * is not an ImageWallpaper. This will also return false when called with {@link #FLAG_LOCK} if
     * the lock and home screen share the same wallpaper engine.
     *
     * @param which The wallpaper whose image file is to be retrieved. Must be a single
     *     defined kind of wallpaper, either {@link #FLAG_SYSTEM} or {@link #FLAG_LOCK}.
     * @return true when supported.
     *
     * @see #FLAG_LOCK
     * @see #FLAG_SYSTEM
     * @hide
     */
    @TestApi
    public boolean wallpaperSupportsWcg(int which) {
        if (!shouldEnableWideColorGamut()) {
            return false;
        }
        final ColorManagementProxy cmProxy = getColorManagementProxy();
        Bitmap bitmap = sGlobals.peekWallpaperBitmap(mContext, false, which, cmProxy);
        return bitmap != null && bitmap.getColorSpace() != null
                && bitmap.getColorSpace() != ColorSpace.get(ColorSpace.Named.SRGB)
                && cmProxy.isSupportedColorSpace(bitmap.getColorSpace());
    }

    /**
     * Like {@link #getDrawable()} but returns a Bitmap with default {@link Bitmap.Config}.
     *
     * @hide
     */
    @TestApi
    @Nullable
    @UnsupportedAppUsage
    public Bitmap getBitmap() {
        return getBitmap(false);
    }

    /**
     * Like {@link #getDrawable()} but returns a Bitmap.
     *
     * @param hardware Asks for a hardware backed bitmap.
     * @see Bitmap.Config#HARDWARE
     * @hide
     */
    @UnsupportedAppUsage
    public Bitmap getBitmap(boolean hardware) {
        return getBitmapAsUser(mContext.getUserId(), hardware);
    }

    /**
     * Like {@link #getDrawable(int)} but returns a Bitmap.
     *
     * @param hardware Asks for a hardware backed bitmap.
     * @param which Specifies home or lock screen
     * @see Bitmap.Config#HARDWARE
     * @hide
     */
    @Nullable
    public Bitmap getBitmap(boolean hardware, @SetWallpaperFlags int which) {
        return getBitmapAsUser(mContext.getUserId(), hardware, which);
    }

    /**
     * Like {@link #getDrawable()} but returns a Bitmap for the provided user.
     *
     * @hide
     */
    public Bitmap getBitmapAsUser(int userId, boolean hardware) {
        final ColorManagementProxy cmProxy = getColorManagementProxy();
        return sGlobals.peekWallpaperBitmap(mContext, true, FLAG_SYSTEM, userId, hardware, cmProxy);
    }

    /**
     * Like {@link #getDrawable(int)} but returns a Bitmap for the provided user.
     *
     * @param which Specifies home or lock screen
     * @hide
     */
    @TestApi
    @Nullable
    public Bitmap getBitmapAsUser(int userId, boolean hardware, @SetWallpaperFlags int which) {
        boolean returnDefault = which != FLAG_LOCK;
        return getBitmapAsUser(userId, hardware, which, returnDefault);
    }

    /**
     * Overload of {@link #getBitmapAsUser(int, boolean, int)} with a returnDefault argument.
     *
     * @param returnDefault If true, return the default static wallpaper if no custom static
     *                      wallpaper is set on the specified screen.
     *                      If false, return {@code null} in that case.
     * @hide
     */
    @Nullable
    public Bitmap getBitmapAsUser(int userId, boolean hardware,
            @SetWallpaperFlags int which, boolean returnDefault) {
        final ColorManagementProxy cmProxy = getColorManagementProxy();
        return sGlobals.peekWallpaperBitmap(mContext, returnDefault,
                which, userId, hardware, cmProxy);
    }

    /**
     * Peek the dimensions of system wallpaper of the user without decoding it.
     * Equivalent to {@link #peekBitmapDimensions(int)} with {@code which=}{@link #FLAG_SYSTEM}.
     *
     * @return the dimensions of system wallpaper
     * @hide
     */
    @TestApi
    @Nullable
    public Rect peekBitmapDimensions() {
        return peekBitmapDimensions(FLAG_SYSTEM);
    }

    /**
     * Peek the dimensions of given wallpaper of the user without decoding it.
     *
     * <p>
     * When called with {@code which=}{@link #FLAG_SYSTEM}, if there is a live wallpaper on
     * home screen, the built-in default wallpaper dimensions are returned.
     * </p>
     * <p>
     * When called with {@code which=}{@link #FLAG_LOCK}, if there is a live wallpaper
     * on lock screen, or if the lock screen and home screen share the same wallpaper engine,
     * {@code null} is returned.
     * </p>
     * <p>
     * {@link #getWallpaperInfo(int)} can be used to determine whether there is a live wallpaper
     * on a specified screen type.
     * </p>
     *
     * @param which Wallpaper type. Must be either {@link #FLAG_SYSTEM} or {@link #FLAG_LOCK}.
     * @return the dimensions of specified wallpaper
     * @hide
     */
    @TestApi
    @Nullable
    public Rect peekBitmapDimensions(@SetWallpaperFlags int which) {
        boolean returnDefault = which != FLAG_LOCK;
        return peekBitmapDimensions(which, returnDefault);
    }

    /**
     * Overload of {@link #peekBitmapDimensions(int)} with a returnDefault argument.
     *
     * @param which Wallpaper type. Must be either {@link #FLAG_SYSTEM} or {@link #FLAG_LOCK}.
     * @param returnDefault If true, always return the default static wallpaper dimensions
     *                      if no custom static wallpaper is set on the specified screen.
     *                      If false, always return {@code null} in that case.
     * @return the dimensions of specified wallpaper
     * @hide
     */
    @Nullable
    public Rect peekBitmapDimensions(@SetWallpaperFlags int which, boolean returnDefault) {
        if (multiCrop()) {
            return peekBitmapDimensionsAsUser(which, returnDefault, mContext.getUserId());
        }
        checkExactlyOneWallpaperFlagSet(which);
        return sGlobals.peekWallpaperDimensions(mContext, returnDefault, which,
                mContext.getUserId());
    }

    /**
     * Overload of {@link #peekBitmapDimensions(int, boolean)} with a userId argument.
     * TODO(b/360120606): remove the SuppressWarnings
     * @hide
     */
    @SuppressWarnings("AndroidFrameworkContextUserId")
    @FlaggedApi(FLAG_MULTI_CROP)
    @Nullable
    public Rect peekBitmapDimensionsAsUser(@SetWallpaperFlags int which, boolean returnDefault,
            int userId) {
        checkExactlyOneWallpaperFlagSet(which);
        return sGlobals.peekWallpaperDimensions(mContext, returnDefault, which, userId);
    }

    /**
     * For the current user, given a list of display sizes, return a list of rectangles representing
     * the area of the current wallpaper that would be shown for each of these sizes.
     *
     * @param displaySizes the display sizes.
     * @param which wallpaper type. Must be either {@link #FLAG_SYSTEM} or {@link #FLAG_LOCK}.
     * @param originalBitmap If true, return areas relative to the original bitmap.
     *                   If false, return areas relative to the cropped bitmap.
     * @return A List of Rect where the Rect is within the cropped/original bitmap, and corresponds
     *          to what is displayed. The Rect may have a larger width/height ratio than the screen
     *          due to parallax. Return {@code null} if the wallpaper is not an ImageWallpaper.
     *          Also return {@code null} when called with which={@link #FLAG_LOCK} if there is a
     *          shared home + lock wallpaper.
     * @hide
     */
    @FlaggedApi(FLAG_MULTI_CROP)
    @RequiresPermission(READ_WALLPAPER_INTERNAL)
    @Nullable
    public List<Rect> getBitmapCrops(@NonNull List<Point> displaySizes,
            @SetWallpaperFlags int which, boolean originalBitmap) {
        checkExactlyOneWallpaperFlagSet(which);
        try {
            List<Rect> result = sGlobals.mService.getBitmapCrops(
                    displaySizes, which, originalBitmap, mContext.getUserId());
            if (result != null) return result;
            // mService.getBitmapCrops returns null if the requested wallpaper is an ImageWallpaper,
            // but there are no crop hints and the bitmap size is unknown to the service (this
            // mostly happens for the default wallpaper). In that case, fetch the bitmap dimensions
            // and use the other getBitmapCrops API with no cropHints to figure out the crops.
            Rect bitmapDimensions = peekBitmapDimensions(which, true);
            if (bitmapDimensions == null) return List.of();
            Point bitmapSize = new Point(bitmapDimensions.width(), bitmapDimensions.height());
            return getBitmapCrops(bitmapSize, displaySizes, null);

        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * For preview purposes.
     * Return how a bitmap of a given size would be cropped for a given list of display sizes, if
     * it was set as wallpaper via {@link #setBitmapWithCrops(Bitmap, Map, boolean, int)} or
     * {@link #setStreamWithCrops(InputStream, Map, boolean, int)}.
     *
     * @return A List of Rect where the Rect is within the bitmap, and corresponds to what is
     *          displayed for each display size. The Rect may have a larger width/height ratio than
     *          the display due to parallax.
     * @hide
     */
    @FlaggedApi(FLAG_MULTI_CROP)
    @Nullable
    public List<Rect> getBitmapCrops(@NonNull Point bitmapSize, @NonNull List<Point> displaySizes,
            @Nullable Map<Point, Rect> cropHints) {
        try {
            if (cropHints == null) cropHints = Map.of();
            Set<Map.Entry<Point, Rect>> entries = cropHints.entrySet();
            int[] screenOrientations = entries.stream().mapToInt(entry ->
                    getOrientation(entry.getKey())).toArray();
            List<Rect> crops = entries.stream().map(Map.Entry::getValue).toList();
            return sGlobals.mService.getFutureBitmapCrops(bitmapSize, displaySizes,
                    screenOrientations, crops);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * For preview purposes.
     * Compute the wallpaper colors of the given bitmap, if it was set as wallpaper via
     * {@link #setBitmapWithCrops(Bitmap, Map, boolean, int)} or
     * {@link #setStreamWithCrops(InputStream, Map, boolean, int)}.
     *  Return {@code null} if an error occurred and the colors could not be computed.
     *
     * @hide
     */
    @FlaggedApi(FLAG_MULTI_CROP)
    @RequiresPermission(SET_WALLPAPER_DIM_AMOUNT)
    @Nullable
    public WallpaperColors getWallpaperColors(@NonNull Bitmap bitmap,
            @Nullable Map<Point, Rect> cropHints) {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            if (cropHints == null) cropHints = Map.of();
            Set<Map.Entry<Point, Rect>> entries = cropHints.entrySet();
            int[] screenOrientations = entries.stream().mapToInt(entry ->
                    getOrientation(entry.getKey())).toArray();
            List<Rect> crops = entries.stream().map(Map.Entry::getValue).toList();
            Point bitmapSize = new Point(bitmap.getWidth(), bitmap.getHeight());
            Rect crop = sGlobals.mService.getBitmapCrop(bitmapSize, screenOrientations, crops);
            float dimAmount = getWallpaperDimAmount();
            Bitmap croppedBitmap = Bitmap.createBitmap(
                    bitmap, crop.left, crop.top, crop.width(), crop.height());
            WallpaperColors result = WallpaperColors.fromBitmap(croppedBitmap, dimAmount);
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * <strong> Important note: </strong>
     * <ul>
     *     <li>Up to Android 12, this method requires the
     *     {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} permission.</li>
     *     <li>Starting in Android 13, directly accessing the wallpaper is not possible anymore,
     *     instead the default system wallpaper is returned
     *     (some versions of Android 13 may throw a {@code SecurityException}).</li>
     *     <li>From Android 14, this method should not be used
     *     and will always throw a {@code SecurityException}.</li>
     *     <li> Apps with {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE}
     *     can still access the real wallpaper on all versions. </li>
     * </ul>
     * <br>
     *
     * Get an open, readable file descriptor to the given wallpaper image file.
     * The caller is responsible for closing the file descriptor when done ingesting the file.
     *
     * <p>If no lock-specific wallpaper has been configured for the given user, then
     * this method will return {@code null} when requesting {@link #FLAG_LOCK} rather than
     * returning the system wallpaper's image file.
     *
     * @param which The wallpaper whose image file is to be retrieved.  Must be a single
     *     defined kind of wallpaper, either {@link #FLAG_SYSTEM} or
     *     {@link #FLAG_LOCK}.
     * @return An open, readable file descriptor to the requested wallpaper image file;
     *     or {@code null} if no such wallpaper is configured or if the calling app does
     *     not have permission to read the current wallpaper.
     *
     * @see #FLAG_LOCK
     * @see #FLAG_SYSTEM
     *
     * @throws SecurityException as described in the note
     */
    @Nullable
    @RequiresPermission(anyOf = {MANAGE_EXTERNAL_STORAGE, READ_WALLPAPER_INTERNAL})
    public ParcelFileDescriptor getWallpaperFile(@SetWallpaperFlags int which) {
        return getWallpaperFile(which, mContext.getUserId());
    }

    /**
     * Registers a listener to get notified when the wallpaper colors change.
     * @param listener A listener to register
     * @param handler Where to call it from. Will be called from the main thread
     *                if null.
     */
    public void addOnColorsChangedListener(@NonNull OnColorsChangedListener listener,
            @NonNull Handler handler) {
        addOnColorsChangedListener(listener, handler, mContext.getUserId());
    }

    /**
     * Registers a listener to get notified when the wallpaper colors change
     * @param listener A listener to register
     * @param handler Where to call it from. Will be called from the main thread
     *                if null.
     * @param userId Owner of the wallpaper or UserHandle.USER_ALL.
     * @hide
     */
    @UnsupportedAppUsage
    public void addOnColorsChangedListener(@NonNull OnColorsChangedListener listener,
            @NonNull Handler handler, int userId) {
        sGlobals.addOnColorsChangedListener(listener, handler, userId, mContext.getDisplayId());
    }

    /**
     * Stop listening to color updates.
     * @param callback A callback to unsubscribe.
     */
    public void removeOnColorsChangedListener(@NonNull OnColorsChangedListener callback) {
        removeOnColorsChangedListener(callback, mContext.getUserId());
    }

    /**
     * Stop listening to color updates.
     * @param callback A callback to unsubscribe.
     * @param userId Owner of the wallpaper or UserHandle.USER_ALL.
     * @hide
     */
    public void removeOnColorsChangedListener(@NonNull OnColorsChangedListener callback,
            int userId) {
        sGlobals.removeOnColorsChangedListener(callback, userId, mContext.getDisplayId());
    }

    /**
     * Get the primary colors of a wallpaper.
     *
     * <p>This method can return {@code null} when:
     * <ul>
     * <li>Colors are still being processed by the system.</li>
     * <li>The user has chosen to use a live wallpaper:  live wallpapers might not
     * implement
     * {@link android.service.wallpaper.WallpaperService.Engine#onComputeColors()
     *     WallpaperService.Engine#onComputeColors()}.</li>
     * </ul>
     * <p>Please note that this API will go through IPC and may take some time to
     * calculate the wallpaper color, which could block the caller thread, so it is
     * not recommended to call this in the UI thread.</p>
     *
     * @param which Wallpaper type. Must be either {@link #FLAG_SYSTEM} or
     *     {@link #FLAG_LOCK}.
     * @return Current {@link WallpaperColors} or null if colors are unknown.
     * @see #addOnColorsChangedListener(OnColorsChangedListener, Handler)
     */
    public @Nullable WallpaperColors getWallpaperColors(int which) {
        return getWallpaperColors(which, mContext.getUserId());
    }

    // TODO(b/181083333): add multiple root display area support on this API.
    /**
     * Get the primary colors of the wallpaper configured in the given user.
     * @param which wallpaper type. Must be either {@link #FLAG_SYSTEM} or
     *     {@link #FLAG_LOCK}
     * @param userId Owner of the wallpaper.
     * @return {@link WallpaperColors} or null if colors are unknown.
     * @hide
     */
    @UnsupportedAppUsage
    public @Nullable WallpaperColors getWallpaperColors(int which, int userId) {
        StrictMode.assertUiContext(mContext, "getWallpaperColors");
        return sGlobals.getWallpaperColors(which, userId, mContext.getDisplayId());
    }

    /**
     * @hide
     */
    public void addOnColorsChangedListener(@NonNull LocalWallpaperColorConsumer callback,
            List<RectF> regions, int which) throws IllegalArgumentException {
        for (RectF region : regions) {
            if (!LOCAL_COLOR_BOUNDS.contains(region)) {
                throw new IllegalArgumentException("Regions must be within bounds "
                        + LOCAL_COLOR_BOUNDS);
            }
        }
        sGlobals.addOnColorsChangedListener(callback, regions, which,
                                                 mContext.getUserId(), mContext.getDisplayId());
    }

    /**
     * @hide
     */
    public void removeOnColorsChangedListener(@NonNull LocalWallpaperColorConsumer callback) {
        sGlobals.removeOnColorsChangedListener(callback, FLAG_SYSTEM, mContext.getUserId(),
                mContext.getDisplayId());
    }

    /**
     * Version of {@link #getWallpaperFile(int)} that can access the wallpaper data
     * for a given user.  The caller must hold the INTERACT_ACROSS_USERS_FULL
     * permission to access another user's wallpaper data.
     *
     * @param which The wallpaper whose image file is to be retrieved.  Must be a single
     *     defined kind of wallpaper, either {@link #FLAG_SYSTEM} or
     *     {@link #FLAG_LOCK}.
     * @param userId The user or profile whose imagery is to be retrieved
     *
     * @see #FLAG_LOCK
     * @see #FLAG_SYSTEM
     *
     * @hide
     */
    @UnsupportedAppUsage
    public ParcelFileDescriptor getWallpaperFile(@SetWallpaperFlags int which, int userId) {
        return getWallpaperFile(which, userId, /* getCropped = */ true);
    }

    /**
     * Version of {@link #getWallpaperFile(int)} that allows specifying whether to get the
     * cropped version of the wallpaper file or the original.
     *
     * @param which The wallpaper whose image file is to be retrieved.  Must be a single
     *    defined kind of wallpaper, either {@link #FLAG_SYSTEM} or {@link #FLAG_LOCK}.
     * @param getCropped If true the cropped file will be retrieved, if false the original will
     *                   be retrieved.
     *
     * @hide
     */
    @Nullable
    public ParcelFileDescriptor getWallpaperFile(@SetWallpaperFlags int which, boolean getCropped) {
        return getWallpaperFile(which, mContext.getUserId(), getCropped);
    }

    private ParcelFileDescriptor getWallpaperFile(@SetWallpaperFlags int which, int userId,
            boolean getCropped) {
        checkExactlyOneWallpaperFlagSet(which);

        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        } else {
            try {
                Bundle outParams = new Bundle();
                return sGlobals.mService.getWallpaperWithFeature(mContext.getOpPackageName(),
                        mContext.getAttributionTag(), null, which, outParams,
                        userId, getCropped);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } catch (SecurityException e) {
                if (CompatChanges.isChangeEnabled(RETURN_DEFAULT_ON_SECURITY_EXCEPTION)
                        && !CompatChanges.isChangeEnabled(THROW_ON_SECURITY_EXCEPTION)) {
                    Log.w(TAG, "No permission to access wallpaper, returning default"
                            + " wallpaper file to avoid crashing legacy app.");
                    return getDefaultSystemWallpaperFile();
                }
                if (mContext.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.O_MR1) {
                    Log.w(TAG, "No permission to access wallpaper, suppressing"
                            + " exception to avoid crashing legacy app.");
                    return null;
                }
                throw e;
            }
        }
    }

    /**
     * Remove all internal references to the last loaded wallpaper.  Useful
     * for apps that want to reduce memory usage when they only temporarily
     * need to have the wallpaper.  After calling, the next request for the
     * wallpaper will require reloading it again from disk.
     */
    public void forgetLoadedWallpaper() {
        sGlobals.forgetLoadedWallpaper();
    }

    /**
     * Returns the information about the home screen wallpaper if its current wallpaper is a live
     * wallpaper component. Otherwise, if the wallpaper is a static image or is not set, or if the
     * caller doesn't have the appropriate permissions, this returns {@code null}.
     *
     * <p>
     * For devices running Android 13 or earlier, this method requires the
     * {@link android.Manifest.permission#QUERY_ALL_PACKAGES} permission.
     * </p>
     *
     * <p>
     * For devices running Android 14 or later, in order to use this, apps should declare a
     * {@code <queries>} tag with the action {@code "android.service.wallpaper.WallpaperService"}.
     * Otherwise, this method will return {@code null} if the caller doesn't otherwise have
     * <a href="{@docRoot}training/package-visibility">visibility</a> of the wallpaper package.
     * </p>
     */
    @RequiresPermission(value = "QUERY_ALL_PACKAGES", conditional = true)
    public WallpaperInfo getWallpaperInfo() {
        return getWallpaperInfoForUser(mContext.getUserId());
    }

    /**
     * Returns the information about the home screen wallpaper if its current wallpaper is a live
     * wallpaper component. Otherwise, if the wallpaper is a static image, this returns null.
     *
     * @param userId Owner of the wallpaper.
     * @hide
     */
    public WallpaperInfo getWallpaperInfoForUser(int userId) {
        return getWallpaperInfo(FLAG_SYSTEM, userId);
    }

    /**
     * Returns the information about the designated wallpaper if its current wallpaper is a live
     * wallpaper component. Otherwise, if the wallpaper is a static image or is not set, or if
     * the caller doesn't have the appropriate permissions, this returns {@code null}.
     *
     * <p>
     * In order to use this, apps should declare a {@code <queries>} tag with the action
     * {@code "android.service.wallpaper.WallpaperService"}. Otherwise, this method will return
     * {@code null} if the caller doesn't otherwise have
     * <a href="{@docRoot}training/package-visibility">visibility</a> of the wallpaper package.
     * </p>
     *
     * @param which Specifies wallpaper to request (home or lock).
     * @throws IllegalArgumentException if {@code which} is not exactly one of
     * {{@link #FLAG_SYSTEM},{@link #FLAG_LOCK}}.
     */
    @Nullable
    public WallpaperInfo getWallpaperInfo(@SetWallpaperFlags int which) {
        return getWallpaperInfo(which, mContext.getUserId());
    }

    /**
     * Returns the information about the designated wallpaper if its current wallpaper is a live
     * wallpaper component. Otherwise, if the wallpaper is a static image or is not set, or if the
     * caller doesn't have the appropriate permissions, this returns {@code null}.
     *
     * <p>
     * In order to use this, apps should declare a {@code <queries>} tag
     * with the action {@code "android.service.wallpaper.WallpaperService"}. Otherwise,
     * this method will return {@code null} if the caller doesn't otherwise have
     * <a href="{@docRoot}training/package-visibility">visibility</a> of the wallpaper package.
     * </p>
     *
     * @param which Specifies wallpaper to request (home or lock).
     * @param userId Owner of the wallpaper.
     * @throws IllegalArgumentException if {@code which} is not exactly one of
     * {{@link #FLAG_SYSTEM},{@link #FLAG_LOCK}}.
     * @hide
     */
    public WallpaperInfo getWallpaperInfo(@SetWallpaperFlags int which, int userId) {
        checkExactlyOneWallpaperFlagSet(which);
        try {
            if (sGlobals.mService == null) {
                Log.w(TAG, "WallpaperService not running");
                throw new RuntimeException(new DeadSystemException());
            } else {
                return sGlobals.mService.getWallpaperInfoWithFlags(which, userId);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get an open, readable file descriptor for the file that contains metadata about the
     * context user's wallpaper.
     *
     * The caller is responsible for closing the file descriptor when done ingesting the file.
     *
     * @hide
     */
    @Nullable
    public ParcelFileDescriptor getWallpaperInfoFile() {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        } else {
            try {
                return sGlobals.mService.getWallpaperInfoFile(mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Get the ID of the current wallpaper of the given kind.  If there is no
     * such wallpaper configured, returns a negative number.
     *
     * <p>Every time the wallpaper image is set, a new ID is assigned to it.
     * This method allows the caller to determine whether the wallpaper imagery
     * has changed, regardless of how that change happened.
     *
     * @param which The wallpaper whose ID is to be returned.  Must be a single
     *     defined kind of wallpaper, either {@link #FLAG_SYSTEM} or
     *     {@link #FLAG_LOCK}.
     * @return The positive numeric ID of the current wallpaper of the given kind,
     *     or a negative value if no such wallpaper is configured.
     */
    public int getWallpaperId(@SetWallpaperFlags int which) {
        return getWallpaperIdForUser(which, mContext.getUserId());
    }

    /**
     * Get the ID of the given user's current wallpaper of the given kind.  If there
     * is no such wallpaper configured, returns a negative number.
     * @hide
     */
    public int getWallpaperIdForUser(@SetWallpaperFlags int which, int userId) {
        try {
            if (sGlobals.mService == null) {
                Log.w(TAG, "WallpaperService not running");
                throw new RuntimeException(new DeadSystemException());
            } else {
                return sGlobals.mService.getWallpaperIdForUser(which, userId);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets an Intent that will launch an activity that crops the given
     * image and sets the device's wallpaper. If there is a default HOME activity
     * that supports cropping wallpapers, it will be preferred as the default.
     * Use this method instead of directly creating a {@link #ACTION_CROP_AND_SET_WALLPAPER}
     * intent.
     *
     * @param imageUri The image URI that will be set in the intent. The must be a content
     *                 URI and its provider must resolve its type to "image/*"
     *
     * @throws IllegalArgumentException if the URI is not a content URI or its MIME type is
     *         not "image/*"
     */
    public Intent getCropAndSetWallpaperIntent(Uri imageUri) {
        if (imageUri == null) {
            throw new IllegalArgumentException("Image URI must not be null");
        }

        if (!ContentResolver.SCHEME_CONTENT.equals(imageUri.getScheme())) {
            throw new IllegalArgumentException("Image URI must be of the "
                    + ContentResolver.SCHEME_CONTENT + " scheme type");
        }

        final PackageManager packageManager = mContext.getPackageManager();
        Intent cropAndSetWallpaperIntent =
                new Intent(ACTION_CROP_AND_SET_WALLPAPER, imageUri);
        cropAndSetWallpaperIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Find out if the default HOME activity supports CROP_AND_SET_WALLPAPER
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolvedHome = packageManager.resolveActivity(homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (resolvedHome != null) {
            cropAndSetWallpaperIntent.setPackage(resolvedHome.activityInfo.packageName);

            List<ResolveInfo> cropAppList = packageManager.queryIntentActivities(
                    cropAndSetWallpaperIntent, 0);
            if (cropAppList.size() > 0) {
                return cropAndSetWallpaperIntent;
            }
        }

        // fallback crop activity
        final String cropperPackage = mContext.getString(
                com.android.internal.R.string.config_wallpaperCropperPackage);
        cropAndSetWallpaperIntent.setPackage(cropperPackage);
        List<ResolveInfo> cropAppList = packageManager.queryIntentActivities(
                cropAndSetWallpaperIntent, 0);
        if (cropAppList.size() > 0) {
            return cropAndSetWallpaperIntent;
        }
        // If the URI is not of the right type, or for some reason the system wallpaper
        // cropper doesn't exist, return null
        throw new IllegalArgumentException("Cannot use passed URI to set wallpaper; " +
            "check that the type returned by ContentProvider matches image/*");
    }

    /**
     * Change the current system wallpaper to the bitmap in the given resource.
     * The resource is opened as a raw data stream and copied into the
     * wallpaper; it must be a valid PNG or JPEG image.  On success, the intent
     * {@link Intent#ACTION_WALLPAPER_CHANGED} is broadcast.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER}.
     *
     * @param resid The resource ID of the bitmap to be used as the wallpaper image
     *
     * @throws IOException If an error occurs reverting to the built-in
     * wallpaper.
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public void setResource(@RawRes int resid) throws IOException {
        setResource(resid, FLAG_SYSTEM | FLAG_LOCK);
    }

    /**
     * Version of {@link #setResource(int)} that allows the caller to specify which
     * of the supported wallpaper categories to set.
     *
     * @param resid The resource ID of the bitmap to be used as the wallpaper image
     * @param which Flags indicating which wallpaper(s) to configure with the new imagery
     *
     * @see #FLAG_LOCK
     * @see #FLAG_SYSTEM
     *
     * @return An integer ID assigned to the newly active wallpaper; or zero on failure.
     *
     * @throws IOException
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public int setResource(@RawRes int resid, @SetWallpaperFlags int which)
            throws IOException {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        final Bundle result = new Bundle();
        final WallpaperSetCompletion completion = new WallpaperSetCompletion();
        try {
            Resources resources = mContext.getResources();
            /* Set the wallpaper to the default values */
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(
                    "res:" + resources.getResourceName(resid),
                    mContext.getOpPackageName(), null, null, false, result, which, completion,
                    mContext.getUserId());
            if (fd != null) {
                FileOutputStream fos = null;
                try {
                    fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                    copyStreamToWallpaperFile(resources.openRawResource(resid), fos);
                    // The 'close()' is the trigger for any server-side image manipulation,
                    // so we must do that before waiting for completion.
                    fos.close();
                    completion.waitForCompletion();
                } finally {
                    // Might be redundant but completion shouldn't wait unless the write
                    // succeeded; this is a fallback if it threw past the close+wait.
                    IoUtils.closeQuietly(fos);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result.getInt(EXTRA_NEW_WALLPAPER_ID, 0);
    }

    /**
     * Change the current system wallpaper to a bitmap.  The given bitmap is
     * converted to a PNG and stored as the wallpaper.  On success, the intent
     * {@link Intent#ACTION_WALLPAPER_CHANGED} is broadcast.
     *
     * <p>This method is equivalent to calling
     * {@link #setBitmap(Bitmap, Rect, boolean)} and passing {@code null} for the
     * {@code visibleCrop} rectangle and {@code true} for the {@code allowBackup}
     * parameter.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER}.
     *
     * @param bitmap The bitmap to be used as the new system wallpaper.
     *
     * @throws IOException If an error occurs when attempting to set the wallpaper
     *     to the provided image.
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public void setBitmap(Bitmap bitmap) throws IOException {
        setBitmap(bitmap, null, true);
    }

    /**
     * Change the current system wallpaper to a bitmap, specifying a hint about
     * which subrectangle of the full image is to be visible.  The OS will then
     * try to best present the given portion of the full image as the static system
     * wallpaper image.  On success, the intent
     * {@link Intent#ACTION_WALLPAPER_CHANGED} is broadcast.
     *
     * <p>Passing {@code null} as the {@code visibleHint} parameter is equivalent to
     * passing (0, 0, {@code fullImage.getWidth()}, {@code fullImage.getHeight()}).
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER}.
     *
     * @param fullImage A bitmap that will supply the wallpaper imagery.
     * @param visibleCropHint The rectangular subregion of {@code fullImage} that should be
     *     displayed as wallpaper.  Passing {@code null} for this parameter means that
     *     the full image should be displayed if possible given the image's and device's
     *     aspect ratios, etc.
     * @param allowBackup {@code true} if the OS is permitted to back up this wallpaper
     *     image for restore to a future device; {@code false} otherwise.
     *
     * @return An integer ID assigned to the newly active wallpaper; or zero on failure.
     *
     * @throws IOException If an error occurs when attempting to set the wallpaper
     *     to the provided image.
     * @throws IllegalArgumentException If the {@code visibleCropHint} rectangle is
     *     empty or invalid.
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public int setBitmap(Bitmap fullImage, Rect visibleCropHint, boolean allowBackup)
            throws IOException {
        return setBitmap(fullImage, visibleCropHint, allowBackup, FLAG_SYSTEM | FLAG_LOCK);
    }

    /**
     * Version of {@link #setBitmap(Bitmap, Rect, boolean)} that allows the caller
     * to specify which of the supported wallpaper categories to set.
     *
     * @param fullImage A bitmap that will supply the wallpaper imagery.
     * @param visibleCropHint The rectangular subregion of {@code fullImage} that should be
     *     displayed as wallpaper.  Passing {@code null} for this parameter means that
     *     the full image should be displayed if possible given the image's and device's
     *     aspect ratios, etc.
     * @param allowBackup {@code true} if the OS is permitted to back up this wallpaper
     *     image for restore to a future device; {@code false} otherwise.
     * @param which Flags indicating which wallpaper(s) to configure with the new imagery.
     *
     * @see #FLAG_LOCK
     * @see #FLAG_SYSTEM
     *
     * @return An integer ID assigned to the newly active wallpaper; or zero on failure.
     *
     * @throws IOException
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public int setBitmap(Bitmap fullImage, Rect visibleCropHint,
            boolean allowBackup, @SetWallpaperFlags int which)
            throws IOException {
        return setBitmap(fullImage, visibleCropHint, allowBackup, which,
                mContext.getUserId());
    }

    /**
     * Like {@link #setBitmap(Bitmap, Rect, boolean, int)}, but allows to pass in an explicit user
     * id. If the user id doesn't match the user id the process is running under, calling this
     * requires permission {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int setBitmap(Bitmap fullImage, Rect visibleCropHint,
            boolean allowBackup, @SetWallpaperFlags int which, int userId)
            throws IOException {
        if (multiCrop()) {
            SparseArray<Rect> cropMap = new SparseArray<>();
            if (visibleCropHint != null) cropMap.put(ORIENTATION_UNKNOWN, visibleCropHint);
            return setBitmapWithCrops(fullImage, cropMap, allowBackup, which, userId);
        }
        validateRect(visibleCropHint);
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        final Bundle result = new Bundle();
        final WallpaperSetCompletion completion = new WallpaperSetCompletion();
        final List<Rect> crops = visibleCropHint == null ? null : List.of(visibleCropHint);
        try {
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(null,
                    mContext.getOpPackageName(), null, crops, allowBackup, result, which,
                    completion, userId);
            if (fd != null) {
                FileOutputStream fos = null;
                try {
                    fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                    fullImage.compress(Bitmap.CompressFormat.PNG, 90, fos);
                    fos.close();
                    completion.waitForCompletion();
                } finally {
                    IoUtils.closeQuietly(fos);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result.getInt(EXTRA_NEW_WALLPAPER_ID, 0);
    }

    /**
     * Version of setBitmap that defines how the wallpaper will be positioned for different
     * display sizes.
     * Requires permission {@link android.Manifest.permission#SET_WALLPAPER}.
     * @param cropHints map from screen dimensions to a sub-region of the image to display for those
     *                  dimensions. The {@code Rect} sub-region may have a larger width/height ratio
     *                  than the screen dimensions to apply a horizontal parallax effect. If the
     *                  map is empty or some entries are missing, the system will apply a default
     *                  strategy to position the wallpaper for any unspecified screen dimensions.
     * @hide
     */
    @FlaggedApi(FLAG_MULTI_CROP)
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public int setBitmapWithCrops(@Nullable Bitmap fullImage, @NonNull Map<Point, Rect> cropHints,
            boolean allowBackup, @SetWallpaperFlags int which) throws IOException {
        SparseArray<Rect> crops = new SparseArray<>();
        cropHints.forEach((k, v) -> crops.put(getOrientation(k), v));
        return setBitmapWithCrops(fullImage, crops, allowBackup, which, mContext.getUserId());
    }

    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    private int setBitmapWithCrops(@Nullable Bitmap fullImage, @NonNull SparseArray<Rect> cropHints,
            boolean allowBackup, @SetWallpaperFlags int which, int userId) throws IOException {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        int size = cropHints.size();
        int[] screenOrientations = new int[size];
        List<Rect> crops = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            screenOrientations[i] = cropHints.keyAt(i);
            Rect cropHint = cropHints.valueAt(i);
            validateRect(cropHint);
            crops.add(cropHint);
        }
        final Bundle result = new Bundle();
        final WallpaperSetCompletion completion = new WallpaperSetCompletion();
        try {
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(null,
                    mContext.getOpPackageName(), screenOrientations, crops, allowBackup,
                    result, which, completion, userId);
            if (fd != null) {
                FileOutputStream fos = null;
                try {
                    fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                    fullImage.compress(Bitmap.CompressFormat.PNG, 90, fos);
                    fos.close();
                    completion.waitForCompletion();
                } finally {
                    IoUtils.closeQuietly(fos);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result.getInt(EXTRA_NEW_WALLPAPER_ID, 0);
    }

    private final void validateRect(Rect rect) {
        if (rect != null && rect.isEmpty()) {
            throw new IllegalArgumentException("visibleCrop rectangle must be valid and non-empty");
        }
    }

    /**
     * Change the current system wallpaper to a specific byte stream.  The
     * give InputStream is copied into persistent storage and will now be
     * used as the wallpaper.  Currently it must be either a JPEG or PNG
     * image.  On success, the intent {@link Intent#ACTION_WALLPAPER_CHANGED}
     * is broadcast.
     *
     * <p>This method is equivalent to calling
     * {@link #setStream(InputStream, Rect, boolean)} and passing {@code null} for the
     * {@code visibleCrop} rectangle and {@code true} for the {@code allowBackup}
     * parameter.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER}.
     *
     * @param bitmapData A stream containing the raw data to install as a wallpaper.  This
     *     data can be in any format handled by {@link BitmapRegionDecoder}.
     *
     * @throws IOException If an error occurs when attempting to set the wallpaper
     *     based on the provided image data.
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public void setStream(InputStream bitmapData) throws IOException {
        setStream(bitmapData, null, true);
    }

    private void copyStreamToWallpaperFile(InputStream data, FileOutputStream fos)
            throws IOException {
        FileUtils.copy(data, fos);
    }

    /**
     * Change the current system wallpaper to a specific byte stream, specifying a
     * hint about which subrectangle of the full image is to be visible.  The OS will
     * then try to best present the given portion of the full image as the static system
     * wallpaper image.  The data from the given InputStream is copied into persistent
     * storage and will then be used as the system wallpaper.  Currently the data must
     * be either a JPEG or PNG image.  On success, the intent
     * {@link Intent#ACTION_WALLPAPER_CHANGED} is broadcast.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER}.
     *
     * @param bitmapData A stream containing the raw data to install as a wallpaper.  This
     *     data can be in any format handled by {@link BitmapRegionDecoder}.
     * @param visibleCropHint The rectangular subregion of the streamed image that should be
     *     displayed as wallpaper.  Passing {@code null} for this parameter means that
     *     the full image should be displayed if possible given the image's and device's
     *     aspect ratios, etc.
     * @param allowBackup {@code true} if the OS is permitted to back up this wallpaper
     *     image for restore to a future device; {@code false} otherwise.
     * @return An integer ID assigned to the newly active wallpaper; or zero on failure.
     *
     * @see #getWallpaperId(int)
     *
     * @throws IOException If an error occurs when attempting to set the wallpaper
     *     based on the provided image data.
     * @throws IllegalArgumentException If the {@code visibleCropHint} rectangle is
     *     empty or invalid.
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public int setStream(InputStream bitmapData, Rect visibleCropHint, boolean allowBackup)
            throws IOException {
        return setStream(bitmapData, visibleCropHint, allowBackup, FLAG_SYSTEM | FLAG_LOCK);
    }

    /**
     * Version of {@link #setStream(InputStream, Rect, boolean)} that allows the caller
     * to specify which of the supported wallpaper categories to set.
     *
     * @param bitmapData A stream containing the raw data to install as a wallpaper.  This
     *     data can be in any format handled by {@link BitmapRegionDecoder}.
     * @param visibleCropHint The rectangular subregion of the streamed image that should be
     *     displayed as wallpaper.  Passing {@code null} for this parameter means that
     *     the full image should be displayed if possible given the image's and device's
     *     aspect ratios, etc.
     * @param allowBackup {@code true} if the OS is permitted to back up this wallpaper
     *     image for restore to a future device; {@code false} otherwise.
     * @param which Flags indicating which wallpaper(s) to configure with the new imagery.
     * @return An integer ID assigned to the newly active wallpaper; or zero on failure.
     *
     * @see #getWallpaperId(int)
     * @see #FLAG_LOCK
     * @see #FLAG_SYSTEM
     *
     * @throws IOException
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public int setStream(InputStream bitmapData, Rect visibleCropHint,
            boolean allowBackup, @SetWallpaperFlags int which)
                    throws IOException {
        if (multiCrop()) {
            SparseArray<Rect> cropMap = new SparseArray<>();
            if (visibleCropHint != null) cropMap.put(ORIENTATION_UNKNOWN, visibleCropHint);
            return setStreamWithCrops(bitmapData, cropMap, allowBackup, which);
        }
        validateRect(visibleCropHint);
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        final Bundle result = new Bundle();
        final WallpaperSetCompletion completion = new WallpaperSetCompletion();
        final List<Rect> crops = visibleCropHint == null ? null : List.of(visibleCropHint);
        try {
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(null,
                    mContext.getOpPackageName(), null, crops, allowBackup, result, which,
                    completion, mContext.getUserId());
            if (fd != null) {
                FileOutputStream fos = null;
                try {
                    fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                    copyStreamToWallpaperFile(bitmapData, fos);
                    fos.close();
                    completion.waitForCompletion();
                } finally {
                    IoUtils.closeQuietly(fos);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        return result.getInt(EXTRA_NEW_WALLPAPER_ID, 0);
    }

    /**
     * Version of setStream that defines how the wallpaper will be positioned for different
     * display sizes.
     * Requires permission {@link android.Manifest.permission#SET_WALLPAPER}.
     * @param cropHints map from screen dimensions to a sub-region of the image to display for those
     *                  dimensions. The {@code Rect} sub-region may have a larger width/height ratio
     *                  than the screen dimensions to apply a horizontal parallax effect. If the
     *                  map is empty or some entries are missing, the system will apply a default
     *                  strategy to position the wallpaper for any unspecified screen dimensions.
     * @hide
     */
    @FlaggedApi(FLAG_MULTI_CROP)
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public int setStreamWithCrops(InputStream bitmapData, @NonNull Map<Point, Rect> cropHints,
            boolean allowBackup, @SetWallpaperFlags int which) throws IOException {
        SparseArray<Rect> crops = new SparseArray<>();
        cropHints.forEach((k, v) -> crops.put(getOrientation(k), v));
        return setStreamWithCrops(bitmapData, crops, allowBackup, which);
    }

    /**
     * Similar to {@link #setStreamWithCrops(InputStream, Map, boolean, int)}, but using
     * {@link ScreenOrientation} as keys of the cropHints map. Used for backup & restore, since
     * WallpaperBackupAgent stores orientations rather than the exact display size.
     * Requires permission {@link android.Manifest.permission#SET_WALLPAPER}.
     * @param cropHints map from {@link ScreenOrientation} to a sub-region of the image to display
     *                  for that screen orientation.
     * @hide
     */
    @FlaggedApi(FLAG_MULTI_CROP)
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public int setStreamWithCrops(InputStream bitmapData, @NonNull SparseArray<Rect> cropHints,
            boolean allowBackup, @SetWallpaperFlags int which) throws IOException {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        int size = cropHints.size();
        int[] screenOrientations = new int[size];
        List<Rect> crops = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            screenOrientations[i] = cropHints.keyAt(i);
            Rect cropHint = cropHints.valueAt(i);
            validateRect(cropHint);
            crops.add(cropHint);
        }
        final Bundle result = new Bundle();
        final WallpaperSetCompletion completion = new WallpaperSetCompletion();
        try {
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(null,
                    mContext.getOpPackageName(), screenOrientations, crops, allowBackup,
                    result, which, completion, mContext.getUserId());
            if (fd != null) {
                FileOutputStream fos = null;
                try {
                    fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                    copyStreamToWallpaperFile(bitmapData, fos);
                    fos.close();
                    completion.waitForCompletion();
                } finally {
                    IoUtils.closeQuietly(fos);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result.getInt(EXTRA_NEW_WALLPAPER_ID, 0);
    }

    /**
     * Return whether any users are currently set to use the wallpaper
     * with the given resource ID.  That is, their wallpaper has been
     * set through {@link #setResource(int)} with the same resource id.
     */
    public boolean hasResourceWallpaper(@RawRes int resid) {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            Resources resources = mContext.getResources();
            String name = "res:" + resources.getResourceName(resid);
            return sGlobals.mService.hasNamedWallpaper(name);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO(b/181083333): add multiple root display area support on this API.
    /**
     * Returns the desired minimum width for the wallpaper. Callers of
     * {@link #setBitmap(android.graphics.Bitmap)} or
     * {@link #setStream(java.io.InputStream)} should check this value
     * beforehand to make sure the supplied wallpaper respects the desired
     * minimum width.
     *
     * If the returned value is <= 0, the caller should use the width of
     * the default display instead.
     *
     * @return The desired minimum width for the wallpaper. This value should
     * be honored by applications that set the wallpaper but it is not
     * mandatory.
     *
     * @see #getDesiredMinimumHeight()
     */
    public int getDesiredMinimumWidth() {
        StrictMode.assertUiContext(mContext, "getDesiredMinimumWidth");
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            return sGlobals.mService.getWidthHint(mContext.getDisplayId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO(b/181083333): add multiple root display area support on this API.
    /**
     * Returns the desired minimum height for the wallpaper. Callers of
     * {@link #setBitmap(android.graphics.Bitmap)} or
     * {@link #setStream(java.io.InputStream)} should check this value
     * beforehand to make sure the supplied wallpaper respects the desired
     * minimum height.
     *
     * If the returned value is <= 0, the caller should use the height of
     * the default display instead.
     *
     * @return The desired minimum height for the wallpaper. This value should
     * be honored by applications that set the wallpaper but it is not
     * mandatory.
     *
     * @see #getDesiredMinimumWidth()
     */
    public int getDesiredMinimumHeight() {
        StrictMode.assertUiContext(mContext, "getDesiredMinimumHeight");
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            return sGlobals.mService.getHeightHint(mContext.getDisplayId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO(b/181083333): add multiple root display area support on this API.
    /**
     * For use only by the current home application, to specify the size of
     * wallpaper it would like to use.  This allows such applications to have
     * a virtual wallpaper that is larger than the physical screen, matching
     * the size of their workspace.
     *
     * <p class="note">Calling this method from apps other than the active
     * home app is not guaranteed to work properly.  Other apps that supply
     * wallpaper imagery should use {@link #getDesiredMinimumWidth()} and
     * {@link #getDesiredMinimumHeight()} and construct a wallpaper that
     * matches those dimensions.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER_HINTS}.
     *
     * @param minimumWidth Desired minimum width
     * @param minimumHeight Desired minimum height
     */
    public void suggestDesiredDimensions(int minimumWidth, int minimumHeight) {
        StrictMode.assertUiContext(mContext, "suggestDesiredDimensions");
        try {
            /**
             * The framework makes no attempt to limit the window size
             * to the maximum texture size. Any window larger than this
             * cannot be composited.
             *
             * Read maximum texture size from system property and scale down
             * minimumWidth and minimumHeight accordingly.
             */
            int maximumTextureSize;
            try {
                maximumTextureSize = SystemProperties.getInt("sys.max_texture_size", 0);
            } catch (Exception e) {
                maximumTextureSize = 0;
            }

            if (maximumTextureSize > 0) {
                if ((minimumWidth > maximumTextureSize) ||
                    (minimumHeight > maximumTextureSize)) {
                    float aspect = (float)minimumHeight / (float)minimumWidth;
                    if (minimumWidth > minimumHeight) {
                        minimumWidth = maximumTextureSize;
                        minimumHeight = (int)((minimumWidth * aspect) + 0.5);
                    } else {
                        minimumHeight = maximumTextureSize;
                        minimumWidth = (int)((minimumHeight / aspect) + 0.5);
                    }
                }
            }

            if (sGlobals.mService == null) {
                Log.w(TAG, "WallpaperService not running");
                throw new RuntimeException(new DeadSystemException());
            } else {
                sGlobals.mService.setDimensionHints(minimumWidth, minimumHeight,
                        mContext.getOpPackageName(), mContext.getDisplayId());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // TODO(b/181083333): add multiple root display area support on this API.
    /**
     * Specify extra padding that the wallpaper should have outside of the display.
     * That is, the given padding supplies additional pixels the wallpaper should extend
     * outside of the display itself.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER_HINTS}.
     *
     * @param padding The number of pixels the wallpaper should extend beyond the display,
     * on its left, top, right, and bottom sides.
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER_HINTS)
    public void setDisplayPadding(Rect padding) {
        StrictMode.assertUiContext(mContext, "setDisplayPadding");
        try {
            if (sGlobals.mService == null) {
                Log.w(TAG, "WallpaperService not running");
                throw new RuntimeException(new DeadSystemException());
            } else {
                sGlobals.mService.setDisplayPadding(padding, mContext.getOpPackageName(),
                        mContext.getDisplayId());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Apply a raw offset to the wallpaper window.  Should only be used in
     * combination with {@link #setDisplayPadding(android.graphics.Rect)} when you
     * have ensured that the wallpaper will extend outside of the display area so that
     * it can be moved without leaving part of the display uncovered.
     * @param x The offset, in pixels, to apply to the left edge.
     * @param y The offset, in pixels, to apply to the top edge.
     * @hide
     */
    @SystemApi
    public void setDisplayOffset(IBinder windowToken, int x, int y) {
        try {
            //Log.v(TAG, "Sending new wallpaper display offsets from app...");
            WindowManagerGlobal.getWindowSession().setWallpaperDisplayOffset(
                    windowToken, x, y);
            //Log.v(TAG, "...app returning after sending display offset!");
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Equivalent to {@link #clear()}.
     * @see #clear()
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public void clearWallpaper() {
        clearWallpaper(FLAG_LOCK | FLAG_SYSTEM, mContext.getUserId());
    }

    /**
     * Clear the wallpaper for a specific user.
     * <ul>
     *     <li> When called with {@code which=}{@link #FLAG_LOCK}, clear the lockscreen wallpaper.
     *     The home screen wallpaper will become visible on the lock screen. </li>
     *
     *     <li> When called with {@code which=}{@link #FLAG_SYSTEM}, revert the home screen
     *     wallpaper to default. The lockscreen wallpaper will be unchanged: if the previous
     *     wallpaper was shared between home and lock screen, it will become lock screen only. </li>
     *
     *     <li> When called with {@code which=}({@link #FLAG_LOCK} | {@link #FLAG_SYSTEM}), put the
     *     default wallpaper on both home and lock screen, removing any user defined wallpaper.</li>
     * </ul>
     * </p>
     *
     * The caller must hold the
     * INTERACT_ACROSS_USERS_FULL permission to clear another user's
     * wallpaper, and must hold the SET_WALLPAPER permission in all
     * circumstances.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public void clearWallpaper(@SetWallpaperFlags int which, int userId) {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            sGlobals.mService.clearWallpaper(mContext.getOpPackageName(), which, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the implementation of {@link android.service.wallpaper.WallpaperService} used to render
     * wallpaper, usually in order to set a live wallpaper.
     *
     * @param name Name of the component to use.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT)
    public boolean setWallpaperComponent(ComponentName name) {
        return setWallpaperComponent(name, mContext.getUserId());
    }

    /**
     * Sets the wallpaper dim amount between [0f, 1f] which would be blended with the system default
     * dimming. 0f doesn't add any additional dimming and 1f makes the wallpaper fully black.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(SET_WALLPAPER_DIM_AMOUNT)
    public void setWallpaperDimAmount(@FloatRange (from = 0f, to = 1f) float dimAmount) {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            sGlobals.mService.setWallpaperDimAmount(MathUtils.saturate(dimAmount));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current additional dim amount set on the wallpaper. 0f means no application has
     * added any dimming on top of the system default dim amount.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(SET_WALLPAPER_DIM_AMOUNT)
    public @FloatRange (from = 0f, to = 1f) float getWallpaperDimAmount() {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            return sGlobals.mService.getWallpaperDimAmount();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Whether the lock screen wallpaper is different from the system wallpaper.
     *
     * @hide
     */
    public boolean lockScreenWallpaperExists() {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            return sGlobals.mService.lockScreenWallpaperExists();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the implementation of {@link android.service.wallpaper.WallpaperService} used to render
     * wallpaper, usually in order to set a live wallpaper.
     *
     * This can only be called by packages with android.permission.SET_WALLPAPER_COMPONENT
     * permission. The caller must hold the INTERACT_ACROSS_USERS_FULL permission to change
     * another user's wallpaper.
     *
     * @param name Name of the component to use.
     * @param userId User for whom the component should be set.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT)
    @UnsupportedAppUsage
    public boolean setWallpaperComponent(ComponentName name, int userId) {
        return setWallpaperComponentWithFlags(name, FLAG_SYSTEM | FLAG_LOCK, userId);
    }

    /**
     * Set the implementation of {@link android.service.wallpaper.WallpaperService} used to render
     * wallpaper, usually in order to set a live wallpaper, for a given wallpaper destination.
     *
     * This can only be called by packages with android.permission.SET_WALLPAPER_COMPONENT
     * permission. The caller must hold the INTERACT_ACROSS_USERS_FULL permission to change
     * another user's wallpaper.
     *
     * @param name Name of the component to use.
     * @param which Specifies wallpaper destination (home and/or lock).
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT)
    public boolean setWallpaperComponentWithFlags(@NonNull ComponentName name,
            @SetWallpaperFlags int which) {
        return setWallpaperComponentWithFlags(name, which, mContext.getUserId());
    }

    /**
     * Set the implementation of {@link android.service.wallpaper.WallpaperService} used to render
     * wallpaper, usually in order to set a live wallpaper, for a given wallpaper destination.
     *
     * This can only be called by packages with android.permission.SET_WALLPAPER_COMPONENT
     * permission. The caller must hold the INTERACT_ACROSS_USERS_FULL permission to change
     * another user's wallpaper.
     *
     * @param name Name of the component to use.
     * @param which Specifies wallpaper destination (home and/or lock).
     * @param userId User for whom the component should be set.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT)
    public boolean setWallpaperComponentWithFlags(@NonNull ComponentName name,
            @SetWallpaperFlags int which, int userId) {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperManagerService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            sGlobals.mService.setWallpaperComponentChecked(name, mContext.getOpPackageName(),
                    which, userId);
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the display position of the current wallpaper within any larger space, when
     * that wallpaper is visible behind the given window.  The X and Y offsets
     * are floating point numbers ranging from 0 to 1, representing where the
     * wallpaper should be positioned within the screen space.  These only
     * make sense when the wallpaper is larger than the display.
     *
     * @param windowToken The window who these offsets should be associated
     * with, as returned by {@link android.view.View#getWindowToken()
     * View.getWindowToken()}.
     * @param xOffset The offset along the X dimension, from 0 to 1.
     * @param yOffset The offset along the Y dimension, from 0 to 1.
     */
    public void setWallpaperOffsets(IBinder windowToken, float xOffset, float yOffset) {
        try {
            //Log.v(TAG, "Sending new wallpaper offsets from app...");
            WindowManagerGlobal.getWindowSession().setWallpaperPosition(
                    windowToken, xOffset, yOffset, mWallpaperXStep, mWallpaperYStep);
            //Log.v(TAG, "...app returning after sending offsets!");
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * For applications that use multiple virtual screens showing a wallpaper,
     * specify the step size between virtual screens. For example, if the
     * launcher has 3 virtual screens, it would specify an xStep of 0.5,
     * since the X offset for those screens are 0.0, 0.5 and 1.0
     * @param xStep The X offset delta from one screen to the next one
     * @param yStep The Y offset delta from one screen to the next one
     */
    public void setWallpaperOffsetSteps(float xStep, float yStep) {
        mWallpaperXStep = xStep;
        mWallpaperYStep = yStep;
    }

    /**
     * Send an arbitrary command to the current active wallpaper.
     *
     * @param windowToken The window who these offsets should be associated
     * with, as returned by {@link android.view.View#getWindowToken()
     * View.getWindowToken()}.
     * @param action Name of the command to perform.  This must be a scoped
     * name to avoid collisions, such as "com.mycompany.wallpaper.DOIT".
     * @param x Arbitrary integer argument based on command.
     * @param y Arbitrary integer argument based on command.
     * @param z Arbitrary integer argument based on command.
     * @param extras Optional additional information for the command, or null.
     */
    @RequiresPermission(value = android.Manifest.permission.ALWAYS_UPDATE_WALLPAPER,
            conditional = true)
    public void sendWallpaperCommand(IBinder windowToken, String action,
            int x, int y, int z, Bundle extras) {
        try {
            //Log.v(TAG, "Sending new wallpaper offsets from app...");
            WindowManagerGlobal.getWindowSession().sendWallpaperCommand(
                    windowToken, action, x, y, z, extras, false);
            //Log.v(TAG, "...app returning after sending offsets!");
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the current zoom out level of the wallpaper.
     *
     * @param windowToken window requesting wallpaper zoom. Zoom level will only be applier while
     *                    such window is visible.
     * @param zoom from 0 to 1 (inclusive) where 1 means fully zoomed out, 0 means fully zoomed in
     *
     * @hide
     */
    @Keep
    @TestApi
    public void setWallpaperZoomOut(@NonNull IBinder windowToken, float zoom) {
        if (zoom < 0 || zoom > 1f) {
            throw new IllegalArgumentException("zoom must be between 0 and 1: " + zoom);
        }
        if (windowToken == null) {
            throw new IllegalArgumentException("windowToken must not be null");
        }
        try {
            WindowManagerGlobal.getWindowSession().setWallpaperZoomOut(windowToken, zoom);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether wallpapers are supported for the calling user. If this function returns
     * {@code false}, any attempts to changing the wallpaper will have no effect,
     * and any attempt to obtain of the wallpaper will return {@code null}.
     */
    public boolean isWallpaperSupported() {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        } else {
            try {
                return sGlobals.mService.isWallpaperSupported(mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns whether the calling package is allowed to set the wallpaper for the calling user.
     * If this function returns {@code false}, any attempts to change the wallpaper will have
     * no effect. Always returns {@code true} for device owner and profile owner.
     *
     * @see android.os.UserManager#DISALLOW_SET_WALLPAPER
     */
    public boolean isSetWallpaperAllowed() {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        } else {
            try {
                return sGlobals.mService.isSetWallpaperAllowed(mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Clear the offsets previously associated with this window through
     * {@link #setWallpaperOffsets(IBinder, float, float)}.  This reverts
     * the window to its default state, where it does not cause the wallpaper
     * to scroll from whatever its last offsets were.
     *
     * @param windowToken The window who these offsets should be associated
     * with, as returned by {@link android.view.View#getWindowToken()
     * View.getWindowToken()}.
     */
    public void clearWallpaperOffsets(IBinder windowToken) {
        try {
            WindowManagerGlobal.getWindowSession().setWallpaperPosition(
                    windowToken, -1, -1, -1, -1);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove any currently set system wallpaper, reverting to the system's built-in
     * wallpaper.
     * On success, the intent {@link Intent#ACTION_WALLPAPER_CHANGED} is broadcast.
     *
     * <p>This method requires the caller to hold the permission
     * {@link android.Manifest.permission#SET_WALLPAPER}.
     *
     * @throws IOException If an error occurs reverting to the built-in
     * wallpaper.
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public void clear() throws IOException {
        clear(FLAG_SYSTEM | FLAG_LOCK);
    }

    /**
     * Remove one or more currently set wallpapers, reverting to the system default
     * display for each one. On success, the intent {@link Intent#ACTION_WALLPAPER_CHANGED}
     * is broadcast.
     * <ul>
     *     <li> When called with {@code which=}{@link #FLAG_LOCK}, clear the lockscreen wallpaper.
     *     The home screen wallpaper will become visible on the lock screen. </li>
     *
     *     <li> When called with {@code which=}{@link #FLAG_SYSTEM}, revert the home screen
     *     wallpaper to default. The lockscreen wallpaper will be unchanged: if the previous
     *     wallpaper was shared between home and lock screen, it will become lock screen only. </li>
     *
     *     <li> When called with {@code which=}({@link #FLAG_LOCK} | {@link #FLAG_SYSTEM}), put the
     *     default wallpaper on both home and lock screen, removing any user defined wallpaper.</li>
     * </ul>
     *
     * @param which A bitwise combination of {@link #FLAG_SYSTEM} or
     *   {@link #FLAG_LOCK}
     * @throws IOException If an error occurs reverting to the built-in wallpaper.
     */
    @RequiresPermission(android.Manifest.permission.SET_WALLPAPER)
    public void clear(@SetWallpaperFlags int which) throws IOException {
        clearWallpaper(which, mContext.getUserId());
    }

    /**
     * Open stream representing the default static image wallpaper.
     *
     * If the device defines no default wallpaper of the requested kind,
     * {@code null} is returned.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static InputStream openDefaultWallpaper(Context context, @SetWallpaperFlags int which) {
        final String whichProp;
        final int defaultResId;
        /* Factory-default lock wallpapers are not yet supported.
        whichProp = which == FLAG_LOCK ? PROP_LOCK_WALLPAPER : PROP_WALLPAPER;
        defaultResId = which == FLAG_LOCK ? R.drawable.default_lock_wallpaper :  ....
        */
        whichProp = PROP_WALLPAPER;
        defaultResId = R.drawable.default_wallpaper;
        final String path = SystemProperties.get(whichProp);
        final InputStream wallpaperInputStream = getWallpaperInputStream(path);
        if (wallpaperInputStream != null) {
            return wallpaperInputStream;
        }
        final String cmfPath = getCmfWallpaperPath();
        final InputStream cmfWallpaperInputStream = getWallpaperInputStream(cmfPath);
        if (cmfWallpaperInputStream != null) {
            return cmfWallpaperInputStream;
        }
        try {
            return context.getResources().openRawResource(defaultResId);
        } catch (NotFoundException e) {
            // no default defined for this device; this is not a failure
        }
        return null;
    }

    /**
     * util used in T to return a default system wallpaper file
     * when third party apps attempt to read the wallpaper with {@link #getWallpaperFile}
     */
    private static ParcelFileDescriptor getDefaultSystemWallpaperFile() {
        for (String path: getDefaultSystemWallpaperPaths()) {
            File file = new File(path);
            if (file.exists()) {
                try {
                    return ParcelFileDescriptor.open(file, MODE_READ_ONLY);
                } catch (FileNotFoundException e) {
                    // continue; default wallpaper file not found on this path
                }
            }
        }
        return null;
    }

    private static InputStream getWallpaperInputStream(String path) {
        if (!TextUtils.isEmpty(path)) {
            final File file = new File(path);
            if (file.exists()) {
                try {
                    return new FileInputStream(file);
                } catch (IOException e) {
                    // Ignored, fall back to platform default
                }
            }
        }
        return null;
    }

    /**
     * @return a list of paths to the system default wallpapers, in order of priority:
     * if the file exists for the first path of this list, the first path should be used.
     */
    private static List<String> getDefaultSystemWallpaperPaths() {
        return List.of(SystemProperties.get(PROP_WALLPAPER), getCmfWallpaperPath());
    }

    private static String getCmfWallpaperPath() {
        return Environment.getProductDirectory() + WALLPAPER_CMF_PATH + "default_wallpaper_"
                + VALUE_CMF_COLOR;
    }

    /**
     * Return {@link ComponentName} of the default live wallpaper, or
     * {@code null} if none is defined.
     *
     * @hide
     */
    public static ComponentName getDefaultWallpaperComponent(Context context) {
        ComponentName cn = null;

        String flat = SystemProperties.get(PROP_WALLPAPER_COMPONENT);
        if (!TextUtils.isEmpty(flat)) {
            cn = ComponentName.unflattenFromString(flat);
        }

        if (cn == null) {
            flat = context.getString(com.android.internal.R.string.default_wallpaper_component);
            if (!TextUtils.isEmpty(flat)) {
                cn = ComponentName.unflattenFromString(flat);
            }
        }

        if (!isComponentExist(context, cn)) {
            cn = null;
        }

        return cn;
    }

    /**
     * Return {@link ComponentName} of the CMF default wallpaper, or
     * {@link #getDefaultWallpaperComponent(Context)} if none is defined.
     *
     * @hide
     */
    public static ComponentName getCmfDefaultWallpaperComponent(Context context) {
        ComponentName cn = null;
        String[] cmfWallpaperMap = context.getResources().getStringArray(
                com.android.internal.R.array.default_wallpaper_component_per_device_color);
        if (cmfWallpaperMap != null && cmfWallpaperMap.length > 0) {
            for (String entry : cmfWallpaperMap) {
                String[] cmfWallpaper;
                if (!TextUtils.isEmpty(entry)) {
                    cmfWallpaper = entry.split(",");
                    if (cmfWallpaper != null && cmfWallpaper.length == 2 && VALUE_CMF_COLOR.equals(
                            cmfWallpaper[0]) && !TextUtils.isEmpty(cmfWallpaper[1])) {
                        cn = ComponentName.unflattenFromString(cmfWallpaper[1]);
                        break;
                    }
                }
            }
        }

        if (!isComponentExist(context, cn)) {
            cn = null;
        }

        return cn == null ? getDefaultWallpaperComponent(context) : cn;
    }

    private static boolean isComponentExist(Context context, ComponentName cn) {
        if (cn == null) {
            return false;
        }
        try {
            final PackageManager packageManager = context.getPackageManager();
            packageManager.getPackageInfo(cn.getPackageName(),
                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return true;
    }

    /**
     * Is the current system wallpaper eligible for backup?
     *
     * Only the OS itself may use this method.
     * @hide
     */
    public boolean isWallpaperBackupEligible(int which) {
        if (sGlobals.mService == null) {
            Log.w(TAG, "WallpaperService not running");
            throw new RuntimeException(new DeadSystemException());
        }
        try {
            return sGlobals.mService.isWallpaperBackupEligible(which, mContext.getUserId());
        } catch (RemoteException e) {
            Log.e(TAG, "Exception querying wallpaper backup eligibility: " + e.getMessage());
        }
        return false;
    }

    /**
     * Get the instance of {@link ColorManagementProxy}.
     *
     * @return instance of {@link ColorManagementProxy}.
     * @hide
     */
    public ColorManagementProxy getColorManagementProxy() {
        return mCmProxy;
    }

    private static void checkExactlyOneWallpaperFlagSet(@SetWallpaperFlags int which) {
        if (which == FLAG_SYSTEM || which == FLAG_LOCK) {
            return;
        }
        throw new IllegalArgumentException("Must specify exactly one kind of wallpaper");
    }

    /**
     * A hidden class to help {@link Globals#getCurrentWallpaperLocked} handle color management.
     * @hide
     */
    public static class ColorManagementProxy {
        private final Set<ColorSpace> mSupportedColorSpaces = new HashSet<>();

        public ColorManagementProxy(@NonNull Context context) {
            // Get a list of supported wide gamut color spaces.
            Display display = context.getDisplayNoVerify();
            if (display != null) {
                mSupportedColorSpaces.addAll(Arrays.asList(display.getSupportedWideColorGamut()));
            }
        }

        @NonNull
        public Set<ColorSpace> getSupportedColorSpaces() {
            return mSupportedColorSpaces;
        }

        boolean isSupportedColorSpace(ColorSpace colorSpace) {
            return colorSpace != null && (colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
                    || getSupportedColorSpaces().contains(colorSpace));
        }

        void doColorManagement(ImageDecoder decoder, ImageDecoder.ImageInfo info) {
            if (!isSupportedColorSpace(info.getColorSpace())) {
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                Log.w(TAG, "Not supported color space: " + info.getColorSpace());
            }
        }
    }

    // Private completion callback for setWallpaper() synchronization
    private class WallpaperSetCompletion extends IWallpaperManagerCallback.Stub {
        final CountDownLatch mLatch;

        public WallpaperSetCompletion() {
            mLatch = new CountDownLatch(1);
        }

        public void waitForCompletion() {
            try {
                final boolean completed = mLatch.await(30, TimeUnit.SECONDS);
                if (completed) {
                    Log.d(TAG, "Wallpaper set completion.");
                } else {
                    Log.d(TAG, "Timeout waiting for wallpaper set completion!");
                }
            } catch (InterruptedException e) {
                // This might be legit: the crop may take a very long time. Don't sweat
                // it in that case; we are okay with display lagging behind in order to
                // keep the caller from locking up indeterminately.
            }
        }

        @Override
        public void onWallpaperChanged() throws RemoteException {
            mLatch.countDown();
        }

        @Override
        public void onWallpaperColorsChanged(WallpaperColors colors, int which, int userId)
            throws RemoteException {
            sGlobals.onWallpaperColorsChanged(colors, which, userId);
        }
    }

    /**
     * Interface definition for a callback to be invoked when colors change on a wallpaper.
     */
    public interface OnColorsChangedListener {
        /**
         * Called when colors change.
         * A {@link android.app.WallpaperColors} object containing a simplified
         * color histogram will be given.
         *
         * @param colors Wallpaper color info, {@code null} when not available.
         * @param which A combination of {@link #FLAG_LOCK} and {@link #FLAG_SYSTEM}
         * @see android.service.wallpaper.WallpaperService.Engine#onComputeColors()
         */
        void onColorsChanged(@Nullable WallpaperColors colors, int which);

        /**
         * Called when colors change.
         * A {@link android.app.WallpaperColors} object containing a simplified
         * color histogram will be given.
         *
         * @param colors Wallpaper color info, {@code null} when not available.
         * @param which A combination of {@link #FLAG_LOCK} and {@link #FLAG_SYSTEM}
         * @param userId Owner of the wallpaper
         * @see android.service.wallpaper.WallpaperService.Engine#onComputeColors()
         * @hide
         */
        default void onColorsChanged(@Nullable WallpaperColors colors, int which, int userId) {
            onColorsChanged(colors, which);
        }
    }

    /**
     * Callback to update a consumer with a local color change
     * @hide
     */
    public interface LocalWallpaperColorConsumer {

        /**
         * Gets called when a color of an area gets updated
         * @param area
         * @param colors
         */
        void onColorsChanged(RectF area, WallpaperColors colors);
    }
}
