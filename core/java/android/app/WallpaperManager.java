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

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewRootImpl;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Provides access to the system wallpaper. With WallpaperManager, you can
 * get the current wallpaper, get the desired dimensions for the wallpaper, set
 * the wallpaper, and more. Get an instance of WallpaperManager with
 * {@link #getInstance(android.content.Context) getInstance()}. 
 */
public class WallpaperManager {
    private static String TAG = "WallpaperManager";
    private static boolean DEBUG = false;
    private float mWallpaperXStep = -1;
    private float mWallpaperYStep = -1;

    /**
     * Launch an activity for the user to pick the current global live
     * wallpaper.
     */
    public static final String ACTION_LIVE_WALLPAPER_CHOOSER
            = "android.service.wallpaper.LIVE_WALLPAPER_CHOOSER";

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
    
    private final Context mContext;
    
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

        private FastBitmapDrawable(Bitmap bitmap) {
            mBitmap = bitmap;
            mWidth = bitmap.getWidth();
            mHeight = bitmap.getHeight();
            setBounds(0, 0, mWidth, mHeight);
        }

        @Override
        public void draw(Canvas canvas) {
            Paint paint = new Paint();
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            canvas.drawBitmap(mBitmap, mDrawLeft, mDrawTop, paint);
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
        public void setBounds(Rect bounds) {
            // TODO Auto-generated method stub
            super.setBounds(bounds);
        }

        @Override
        public void setAlpha(int alpha) {
            throw new UnsupportedOperationException(
                    "Not supported with this drawable");
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            throw new UnsupportedOperationException(
                    "Not supported with this drawable");
        }

        @Override
        public void setDither(boolean dither) {
            throw new UnsupportedOperationException(
                    "Not supported with this drawable");
        }

        @Override
        public void setFilterBitmap(boolean filter) {
            throw new UnsupportedOperationException(
                    "Not supported with this drawable");
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
    
    static class Globals extends IWallpaperManagerCallback.Stub {
        private IWallpaperManager mService;
        private Bitmap mWallpaper;
        private Bitmap mDefaultWallpaper;
        
        private static final int MSG_CLEAR_WALLPAPER = 1;
        
        private final Handler mHandler;
        
        Globals(Looper looper) {
            IBinder b = ServiceManager.getService(Context.WALLPAPER_SERVICE);
            mService = IWallpaperManager.Stub.asInterface(b);
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MSG_CLEAR_WALLPAPER:
                            synchronized (this) {
                                mWallpaper = null;
                                mDefaultWallpaper = null;
                            }
                            break;
                    }
                }
            };
        }
        
        public void onWallpaperChanged() {
            /* The wallpaper has changed but we shouldn't eagerly load the
             * wallpaper as that would be inefficient. Reset the cached wallpaper
             * to null so if the user requests the wallpaper again then we'll
             * fetch it.
             */
            mHandler.sendEmptyMessage(MSG_CLEAR_WALLPAPER);
        }
        
        public Bitmap peekWallpaperBitmap(Context context, boolean returnDefault) {
            synchronized (this) {
                if (mWallpaper != null) {
                    return mWallpaper;
                }
                if (mDefaultWallpaper != null) {
                    return mDefaultWallpaper;
                }
                mWallpaper = null;
                try {
                    mWallpaper = getCurrentWallpaperLocked(context);
                } catch (OutOfMemoryError e) {
                    Log.w(TAG, "No memory load current wallpaper", e);
                }
                if (returnDefault) {
                    if (mWallpaper == null) {
                        mDefaultWallpaper = getDefaultWallpaperLocked(context);
                        return mDefaultWallpaper;
                    } else {
                        mDefaultWallpaper = null;
                    }
                }
                return mWallpaper;
            }
        }

        public void forgetLoadedWallpaper() {
            synchronized (this) {
                mWallpaper = null;
                mDefaultWallpaper = null;
            }
        }

        private Bitmap getCurrentWallpaperLocked(Context context) {
            try {
                Bundle params = new Bundle();
                ParcelFileDescriptor fd = mService.getWallpaper(this, params);
                if (fd != null) {
                    int width = params.getInt("width", 0);
                    int height = params.getInt("height", 0);

                    try {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        Bitmap bm = BitmapFactory.decodeFileDescriptor(
                                fd.getFileDescriptor(), null, options);
                        return generateBitmap(context, bm, width, height);
                    } catch (OutOfMemoryError e) {
                        Log.w(TAG, "Can't decode file", e);
                    } finally {
                        try {
                            fd.close();
                        } catch (IOException e) {
                        }
                    }
                }
            } catch (RemoteException e) {
            }
            return null;
        }
        
        private Bitmap getDefaultWallpaperLocked(Context context) {
            try {
                InputStream is = context.getResources().openRawResource(
                        com.android.internal.R.drawable.default_wallpaper);
                if (is != null) {
                    int width = mService.getWidthHint();
                    int height = mService.getHeightHint();

                    try {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        Bitmap bm = BitmapFactory.decodeStream(is, null, options);
                        return generateBitmap(context, bm, width, height);
                    } catch (OutOfMemoryError e) {
                        Log.w(TAG, "Can't decode stream", e);
                    } finally {
                        try {
                            is.close();
                        } catch (IOException e) {
                        }
                    }
                }
            } catch (RemoteException e) {
            }
            return null;
        }
    }
    
    private static Object mSync = new Object();
    private static Globals sGlobals;

    static void initGlobals(Looper looper) {
        synchronized (mSync) {
            if (sGlobals == null) {
                sGlobals = new Globals(looper);
            }
        }
    }
    
    /*package*/ WallpaperManager(Context context, Handler handler) {
        mContext = context;
        initGlobals(context.getMainLooper());
    }

    /**
     * Retrieve a WallpaperManager associated with the given Context.
     */
    public static WallpaperManager getInstance(Context context) {
        return (WallpaperManager)context.getSystemService(
                Context.WALLPAPER_SERVICE);
    }
    
    /** @hide */
    public IWallpaperManager getIWallpaperManager() {
        return sGlobals.mService;
    }
    
    /**
     * Retrieve the current system wallpaper; if
     * no wallpaper is set, the system default wallpaper is returned.
     * This is returned as an
     * abstract Drawable that you can install in a View to display whatever
     * wallpaper the user has currently set. 
     *
     * @return Returns a Drawable object that will draw the wallpaper.
     */
    public Drawable getDrawable() {
        Bitmap bm = sGlobals.peekWallpaperBitmap(mContext, true);
        if (bm != null) {
            Drawable dr = new BitmapDrawable(mContext.getResources(), bm);
            dr.setDither(false);
            return dr;
        }
        return null;
    }

    /**
     * Retrieve the current system wallpaper; if there is no wallpaper set,
     * a null pointer is returned. This is returned as an
     * abstract Drawable that you can install in a View to display whatever
     * wallpaper the user has currently set.  
     *
     * @return Returns a Drawable object that will draw the wallpaper or a
     * null pointer if these is none.
     */
    public Drawable peekDrawable() {
        Bitmap bm = sGlobals.peekWallpaperBitmap(mContext, false);
        if (bm != null) {
            Drawable dr = new BitmapDrawable(mContext.getResources(), bm);
            dr.setDither(false);
            return dr;
        }
        return null;
    }

    /**
     * Like {@link #getDrawable()}, but the returned Drawable has a number
     * of limitations to reduce its overhead as much as possible. It will
     * never scale the wallpaper (only centering it if the requested bounds
     * do match the bitmap bounds, which should not be typical), doesn't
     * allow setting an alpha, color filter, or other attributes, etc.  The
     * bounds of the returned drawable will be initialized to the same bounds
     * as the wallpaper, so normally you will not need to touch it.  The
     * drawable also assumes that it will be used in a context running in
     * the same density as the screen (not in density compatibility mode).
     *
     * @return Returns a Drawable object that will draw the wallpaper.
     */
    public Drawable getFastDrawable() {
        Bitmap bm = sGlobals.peekWallpaperBitmap(mContext, true);
        if (bm != null) {
            Drawable dr = new FastBitmapDrawable(bm);
            return dr;
        }
        return null;
    }

    /**
     * Like {@link #getFastDrawable()}, but if there is no wallpaper set,
     * a null pointer is returned.
     *
     * @return Returns an optimized Drawable object that will draw the
     * wallpaper or a null pointer if these is none.
     */
    public Drawable peekFastDrawable() {
        Bitmap bm = sGlobals.peekWallpaperBitmap(mContext, false);
        if (bm != null) {
            Drawable dr = new FastBitmapDrawable(bm);
            return dr;
        }
        return null;
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
     * If the current wallpaper is a live wallpaper component, return the
     * information about that wallpaper.  Otherwise, if it is a static image,
     * simply return null.
     */
    public WallpaperInfo getWallpaperInfo() {
        try {
            return sGlobals.mService.getWallpaperInfo();
        } catch (RemoteException e) {
            return null;
        }
    }
    
    /**
     * Change the current system wallpaper to the bitmap in the given resource.
     * The resource is opened as a raw data stream and copied into the
     * wallpaper; it must be a valid PNG or JPEG image.  On success, the intent
     * {@link Intent#ACTION_WALLPAPER_CHANGED} is broadcast.
     *
     * @param resid The bitmap to save.
     *
     * @throws IOException If an error occurs reverting to the default
     * wallpaper.
     */
    public void setResource(int resid) throws IOException {
        try {
            Resources resources = mContext.getResources();
            /* Set the wallpaper to the default values */
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(
                    "res:" + resources.getResourceName(resid));
            if (fd != null) {
                FileOutputStream fos = null;
                try {
                    fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                    setWallpaper(resources.openRawResource(resid), fos);
                } finally {
                    if (fos != null) {
                        fos.close();
                    }
                }
            }
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Change the current system wallpaper to a bitmap.  The given bitmap is
     * converted to a PNG and stored as the wallpaper.  On success, the intent
     * {@link Intent#ACTION_WALLPAPER_CHANGED} is broadcast.
     *
     * @param bitmap The bitmap to save.
     *
     * @throws IOException If an error occurs reverting to the default
     * wallpaper.
     */
    public void setBitmap(Bitmap bitmap) throws IOException {
        try {
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(null);
            if (fd == null) {
                return;
            }
            FileOutputStream fos = null;
            try {
                fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Change the current system wallpaper to a specific byte stream.  The
     * give InputStream is copied into persistent storage and will now be
     * used as the wallpaper.  Currently it must be either a JPEG or PNG
     * image.  On success, the intent {@link Intent#ACTION_WALLPAPER_CHANGED}
     * is broadcast.
     *
     * @param data A stream containing the raw data to install as a wallpaper.
     *
     * @throws IOException If an error occurs reverting to the default
     * wallpaper.
     */
    public void setStream(InputStream data) throws IOException {
        try {
            ParcelFileDescriptor fd = sGlobals.mService.setWallpaper(null);
            if (fd == null) {
                return;
            }
            FileOutputStream fos = null;
            try {
                fos = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
                setWallpaper(data, fos);
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
        } catch (RemoteException e) {
        }
    }

    private void setWallpaper(InputStream data, FileOutputStream fos)
            throws IOException {
        byte[] buffer = new byte[32768];
        int amt;
        while ((amt=data.read(buffer)) > 0) {
            fos.write(buffer, 0, amt);
        }
    }

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
     */
    public int getDesiredMinimumWidth() {
        try {
            return sGlobals.mService.getWidthHint();
        } catch (RemoteException e) {
            // Shouldn't happen!
            return 0;
        }
    }

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
     */
    public int getDesiredMinimumHeight() {
        try {
            return sGlobals.mService.getHeightHint();
        } catch (RemoteException e) {
            // Shouldn't happen!
            return 0;
        }
    }

    /**
     * For use only by the current home application, to specify the size of
     * wallpaper it would like to use.  This allows such applications to have
     * a virtual wallpaper that is larger than the physical screen, matching
     * the size of their workspace.
     * @param minimumWidth Desired minimum width
     * @param minimumHeight Desired minimum height
     */
    public void suggestDesiredDimensions(int minimumWidth, int minimumHeight) {
        try {
            sGlobals.mService.setDimensionHints(minimumWidth, minimumHeight);
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Set the position of the current wallpaper within any larger space, when
     * that wallpaper is visible behind the given window.  The X and Y offsets
     * are floating point numbers ranging from 0 to 1, representing where the
     * wallpaper should be positioned within the screen space.  These only
     * make sense when the wallpaper is larger than the screen.
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
            ViewRootImpl.getWindowSession(mContext.getMainLooper()).setWallpaperPosition(
                    windowToken, xOffset, yOffset, mWallpaperXStep, mWallpaperYStep);
            //Log.v(TAG, "...app returning after sending offsets!");
        } catch (RemoteException e) {
            // Ignore.
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
    public void sendWallpaperCommand(IBinder windowToken, String action,
            int x, int y, int z, Bundle extras) {
        try {
            //Log.v(TAG, "Sending new wallpaper offsets from app...");
            ViewRootImpl.getWindowSession(mContext.getMainLooper()).sendWallpaperCommand(
                    windowToken, action, x, y, z, extras, false);
            //Log.v(TAG, "...app returning after sending offsets!");
        } catch (RemoteException e) {
            // Ignore.
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
            ViewRootImpl.getWindowSession(mContext.getMainLooper()).setWallpaperPosition(
                    windowToken, -1, -1, -1, -1);
        } catch (RemoteException e) {
            // Ignore.
        }
    }
    
    /**
     * Remove any currently set wallpaper, reverting to the system's default
     * wallpaper. On success, the intent {@link Intent#ACTION_WALLPAPER_CHANGED}
     * is broadcast.
     *
     * @throws IOException If an error occurs reverting to the default
     * wallpaper.
     */
    public void clear() throws IOException {
        setResource(com.android.internal.R.drawable.default_wallpaper);
    }
    
    static Bitmap generateBitmap(Context context, Bitmap bm, int width, int height) {
        if (bm == null) {
            return null;
        }

        bm.setDensity(DisplayMetrics.DENSITY_DEVICE);

        if (width <= 0 || height <= 0
                || (bm.getWidth() == width && bm.getHeight() == height)) {
            return bm;
        }

        // This is the final bitmap we want to return.
        try {
            Bitmap newbm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            newbm.setDensity(DisplayMetrics.DENSITY_DEVICE);

            Canvas c = new Canvas(newbm);
            Rect targetRect = new Rect();
            targetRect.right = bm.getWidth();
            targetRect.bottom = bm.getHeight();

            int deltaw = width - targetRect.right;
            int deltah = height - targetRect.bottom;

            if (deltaw > 0 || deltah > 0) {
                // We need to scale up so it covers the entire area.
                float scale = 1.0f;
                if (deltaw > deltah) {
                    scale = width / (float)targetRect.right;
                } else {
                    scale = height / (float)targetRect.bottom;
                }
                targetRect.right = (int)(targetRect.right*scale);
                targetRect.bottom = (int)(targetRect.bottom*scale);
                deltaw = width - targetRect.right;
                deltah = height - targetRect.bottom;
            }

            targetRect.offset(deltaw/2, deltah/2);

            Paint paint = new Paint();
            paint.setFilterBitmap(true);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            c.drawBitmap(bm, null, targetRect, paint);

            bm.recycle();
            c.setBitmap(null);
            return newbm;
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "Can't generate default bitmap", e);
            return bm;
        }
    }
}
