/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.wm;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;

import java.util.ArrayList;
import android.os.ServiceManager;
import android.os.Looper;
import android.app.IWallpaperManager;
import android.app.IWallpaperManagerCallback;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import android.os.RemoteException;
import android.os.Message;
import android.app.WallpaperManager;
import android.graphics.Canvas;
import android.view.WindowManager;
import android.view.DisplayInfo;
import android.graphics.Paint;
import android.app.ActivityManager;
import android.os.UserHandle;


/**
 * A single hand adapter that simulates a small display
 * Blur background is not implementend temporary.
 */
final class SingleHandAdapter {
    static final String TAG = "SingleHandAdapter";
    static final boolean DEBUG = false;

    private final ArrayList<SingleHandHandle> mSingleHandlers =
            new ArrayList<SingleHandHandle>();
    private String mCurMode = "";
    private final Context mContext;

    private Bitmap mBlurBitmap;
    private WallpaperManager mWallpaperManager;

    private static final int MSG_CLEAR_WALLPAPER = 1;
    private final Handler mPaperHandler;

    static Bitmap scaleWallpaper = null;
    private DisplayInfo mDefaultDisplayInfo = new DisplayInfo();
    private final Handler mHandler;
    private final Handler mUiHandler;
    private final WindowManagerService mService;
    static final Object mLock = new Object();
    private final static float INITIAL_SCALE = 0.75f;

    private static boolean isSingleHandEnabled = true;
    public static final String KEY_SINGLE_HAND_SCREEN_ZOOM = "single_hand_screen_zoom";

    public SingleHandAdapter(Context context, Handler handler, Handler uiHandler, WindowManagerService service) {
        mHandler = handler;
        mContext = context;
        mUiHandler = uiHandler;
        mService = service;
        mDefaultDisplayInfo = mService.getDefaultDisplayInfoLocked();
        mWallpaperManager = (WallpaperManager) mContext.getSystemService(Context.WALLPAPER_SERVICE);
        mPaperHandler = new Handler(mUiHandler.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_CLEAR_WALLPAPER:
                        Slog.i(TAG, "for BlurWallpaper :Wallpaper changed.");
                        updateScaleWallpaperForBlur();
                        break;
                    default:
                        break;
                }
            }
        };
        updateBlur();
    }

    public void registerLocked() {
        Settings.Global.putString(mContext.getContentResolver(), Settings.Global.SINGLE_HAND_MODE, "");

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mContext.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.SINGLE_HAND_MODE),
                        true, new ContentObserver(mHandler) {
                            @Override
                            public void onChange(boolean selfChange) {
                                Slog.i(TAG, "onChange..");
                                updateSingleHandMode();
                            }
                        });

                mContext.getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(KEY_SINGLE_HAND_SCREEN_ZOOM),
                        true, new ContentObserver(mHandler) {
                            @Override
                            public void onChange(boolean selfChange) {
                                isSingleHandEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                                KEY_SINGLE_HAND_SCREEN_ZOOM, 1, ActivityManager.getCurrentUser()) == 1;
                                Slog.i(TAG, " KEY_SINGLE_HAND_SCREEN_ZOOM onChange isSingleHandEnabled="+isSingleHandEnabled);
                                synchronized(mLock) {
                                    if (isSingleHandEnabled) {
                                        updateBlur();
                                        updateScaleWallpaperForBlur();
                                    } else {
                                        if(mBlurBitmap!=null) {
                                            mBlurBitmap.recycle();
                                            mBlurBitmap = null;
                                        }
                                        if(scaleWallpaper!=null) {
                                            scaleWallpaper.recycle();
                                            scaleWallpaper = null;
                                        }
                                    }
                                }
                            }
                        });
            }
        });
    }

    private void updateSingleHandMode() {
        String value = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.SINGLE_HAND_MODE);
        Slog.i(TAG, "updateSingleHandMode value: "+value+" cur: "+mCurMode);
        if (value == null) {
            value = "";
        }

        if (value.equals(mCurMode)) {
            return;
        }
        mCurMode = value;

        synchronized(mLock) {
            if (!mSingleHandlers.isEmpty()) {
                for (SingleHandHandle overlay : mSingleHandlers) {
                    overlay.dismissLocked();
                }
                mSingleHandlers.clear();
            }
            if(value != null && !"".equals(value)) {
                boolean left = value.contains("left");
                mSingleHandlers.add(new SingleHandHandle(left));
            }
        }
    }

    private final class SingleHandHandle {
        private final boolean mLeft;
        private SingleHandWindow mWindowWalltop;
        private SingleHandWindow mWindow;

        public SingleHandHandle(boolean left) {
            mLeft = left;
            synchronized(mLock) {
                mUiHandler.post(mShowRunnableWalltop);
                mUiHandler.postDelayed(mShowRunnable, 150);
            }
        }

        public void dismissLocked() {
            mUiHandler.removeCallbacks(mShowRunnable);
            mUiHandler.removeCallbacks(mShowRunnableWalltop);
            mUiHandler.post(mDismissRunnable);
        }

        // Runs on the UI thread.
        private final Runnable mShowRunnable = new Runnable() {
            @Override
            public void run() {
                SingleHandWindow window = new SingleHandWindow(mContext, mLeft,
                        "virtual", mDefaultDisplayInfo.logicalWidth, mDefaultDisplayInfo.logicalHeight, mService);
                window.show();
                mWindow = window;
            }
        };

        private final Runnable mShowRunnableWalltop = new Runnable() {
            @Override
            public void run() {
                SingleHandWindow window = new SingleHandWindow(mContext, mLeft,
                        "blurpapertop", mDefaultDisplayInfo.logicalWidth, mDefaultDisplayInfo.logicalHeight, mService);
                window.show();
                mWindowWalltop = window;
            }
        };

        // Runs on the UI thread.
        private final Runnable mDismissRunnable = new Runnable() {
            @Override
            public void run() {
                SingleHandWindow window;

                window = mWindow;
                mWindow = null;
                if (window != null) {
                    window.dismiss();
                }

                window = mWindowWalltop;
                mWindowWalltop = null;
                if (window != null) {
                    window.dismiss();
                }
            }
        };

        public void onBlurWallpaperChanged() {
            if (mWindowWalltop != null) {
                mWindowWalltop.onBlurWallpaperChanged();
            }
        }
    }

    private void updateScaleWallpaperForBlur() {
        synchronized(mLock) {
            // update scaleWallpaper
            if (scaleWallpaper != null) {
                scaleWallpaper.recycle();
                scaleWallpaper = null;
            }

            if(mBlurBitmap == null) {
                Slog.e(TAG, "getBlurBitmap return null");
                return;
            }
            int wwidth = (int)(mBlurBitmap.getWidth() * 1.0f);
            int hheight = (int)(mBlurBitmap.getHeight() * 1.0f);
            scaleWallpaper = Bitmap.createBitmap(wwidth, hheight, Bitmap.Config.ARGB_8888);
            if(scaleWallpaper == null) {
                Slog.e(TAG, "createBitmap return null");
                return;
            }
            Canvas canvas = new Canvas(scaleWallpaper);
            Paint p = new Paint();
            p.setColor(0x92000000);
            canvas.drawBitmap(mBlurBitmap, 0, 0, null);
            canvas.drawRect(0, 0, mBlurBitmap.getWidth(), mBlurBitmap.getHeight(), p);

            int[] inPixels = new int[wwidth * hheight];
            scaleWallpaper.getPixels(inPixels, 0, wwidth, 0, 0, wwidth, hheight);

            for (int y = 0; y < hheight; y++) {
                for (int x = 0; x < wwidth; x++) {
                    int index = y * wwidth + x;
                    inPixels[index] = 0xff000000 | inPixels[index];
                }
            }
            scaleWallpaper.setPixels(inPixels, 0, wwidth, 0, 0, wwidth, hheight);

            // notify SingleHandWindow to invalidate background
            if (!mSingleHandlers.isEmpty()) {
                for (SingleHandHandle overlay : mSingleHandlers) {
                    overlay.onBlurWallpaperChanged();
                }
            }
        }
    }

    private void updateBlur() {
        //TODO
    }
}
