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

package com.android.server.display;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A display adapter that uses overlay windows to simulate secondary displays
 * for development purposes.  Use Development Settings to enable one or more
 * overlay displays.
 * <p>
 * This object has two different handlers (which may be the same) which must not
 * get confused.  The main handler is used to posting messages to the display manager
 * service as usual.  The UI handler is only used by the {@link OverlayDisplayWindow}.
 * </p><p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
final class OverlayDisplayAdapter extends DisplayAdapter {
    static final String TAG = "OverlayDisplayAdapter";
    static final boolean DEBUG = false;

    private static final int MIN_WIDTH = 100;
    private static final int MIN_HEIGHT = 100;
    private static final int MAX_WIDTH = 4096;
    private static final int MAX_HEIGHT = 4096;

    private static final Pattern SETTING_PATTERN =
            Pattern.compile("(\\d+)x(\\d+)/(\\d+)(,[a-z]+)*");

    private final Handler mUiHandler;
    private final ArrayList<OverlayDisplayHandle> mOverlays =
            new ArrayList<OverlayDisplayHandle>();
    private String mCurrentOverlaySetting = "";

    // Called with SyncRoot lock held.
    public OverlayDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener, Handler uiHandler) {
        super(syncRoot, context, handler, listener, TAG);
        mUiHandler = uiHandler;
    }

    @Override
    public void dumpLocked(PrintWriter pw) {
        super.dumpLocked(pw);

        pw.println("mCurrentOverlaySetting=" + mCurrentOverlaySetting);
        pw.println("mOverlays: size=" + mOverlays.size());
        for (OverlayDisplayHandle overlay : mOverlays) {
            overlay.dumpLocked(pw);
        }
    }

    @Override
    public void registerLocked() {
        super.registerLocked();

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                getContext().getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.OVERLAY_DISPLAY_DEVICES),
                        true, new ContentObserver(getHandler()) {
                            @Override
                            public void onChange(boolean selfChange) {
                                updateOverlayDisplayDevices();
                            }
                        });

                updateOverlayDisplayDevices();
            }
        });
    }

    private void updateOverlayDisplayDevices() {
        synchronized (getSyncRoot()) {
            updateOverlayDisplayDevicesLocked();
        }
    }

    private void updateOverlayDisplayDevicesLocked() {
        String value = Settings.Global.getString(getContext().getContentResolver(),
                Settings.Global.OVERLAY_DISPLAY_DEVICES);
        if (value == null) {
            value = "";
        }

        if (value.equals(mCurrentOverlaySetting)) {
            return;
        }
        mCurrentOverlaySetting = value;

        if (!mOverlays.isEmpty()) {
            Slog.i(TAG, "Dismissing all overlay display devices.");
            for (OverlayDisplayHandle overlay : mOverlays) {
                overlay.dismissLocked();
            }
            mOverlays.clear();
        }

        int count = 0;
        for (String part : value.split(";")) {
            Matcher matcher = SETTING_PATTERN.matcher(part);
            if (matcher.matches()) {
                if (count >= 4) {
                    Slog.w(TAG, "Too many overlay display devices specified: " + value);
                    break;
                }
                try {
                    int width = Integer.parseInt(matcher.group(1), 10);
                    int height = Integer.parseInt(matcher.group(2), 10);
                    int densityDpi = Integer.parseInt(matcher.group(3), 10);
                    String flagString = matcher.group(4);
                    if (width >= MIN_WIDTH && width <= MAX_WIDTH
                            && height >= MIN_HEIGHT && height <= MAX_HEIGHT
                            && densityDpi >= DisplayMetrics.DENSITY_LOW
                            && densityDpi <= DisplayMetrics.DENSITY_XXHIGH) {
                        int number = ++count;
                        String name = getContext().getResources().getString(
                                com.android.internal.R.string.display_manager_overlay_display_name,
                                number);
                        int gravity = chooseOverlayGravity(number);
                        boolean secure = flagString != null && flagString.contains(",secure");

                        Slog.i(TAG, "Showing overlay display device #" + number
                                + ": name=" + name + ", width=" + width + ", height=" + height
                                + ", densityDpi=" + densityDpi + ", secure=" + secure);

                        mOverlays.add(new OverlayDisplayHandle(name,
                                width, height, densityDpi, gravity, secure));
                        continue;
                    }
                } catch (NumberFormatException ex) {
                }
            } else if (part.isEmpty()) {
                continue;
            }
            Slog.w(TAG, "Malformed overlay display devices setting: " + value);
        }
    }

    private static int chooseOverlayGravity(int overlayNumber) {
        switch (overlayNumber) {
            case 1:
                return Gravity.TOP | Gravity.LEFT;
            case 2:
                return Gravity.BOTTOM | Gravity.RIGHT;
            case 3:
                return Gravity.TOP | Gravity.RIGHT;
            case 4:
            default:
                return Gravity.BOTTOM | Gravity.LEFT;
        }
    }

    private final class OverlayDisplayDevice extends DisplayDevice {
        private final String mName;
        private final int mWidth;
        private final int mHeight;
        private final float mRefreshRate;
        private final long mDisplayPresentationDeadlineNanos;
        private final int mDensityDpi;
        private final boolean mSecure;

        private int mState;
        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;
        private DisplayDeviceInfo mInfo;

        public OverlayDisplayDevice(IBinder displayToken, String name,
                int width, int height, float refreshRate, long presentationDeadlineNanos,
                int densityDpi, boolean secure, int state,
                SurfaceTexture surfaceTexture) {
            super(OverlayDisplayAdapter.this, displayToken);
            mName = name;
            mWidth = width;
            mHeight = height;
            mRefreshRate = refreshRate;
            mDisplayPresentationDeadlineNanos = presentationDeadlineNanos;
            mDensityDpi = densityDpi;
            mSecure = secure;
            mState = state;
            mSurfaceTexture = surfaceTexture;
        }

        public void destroyLocked() {
            mSurfaceTexture = null;
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            SurfaceControl.destroyDisplay(getDisplayTokenLocked());
        }

        @Override
        public void performTraversalInTransactionLocked() {
            if (mSurfaceTexture != null) {
                if (mSurface == null) {
                    mSurface = new Surface(mSurfaceTexture);
                }
                setSurfaceInTransactionLocked(mSurface);
            }
        }

        public void setStateLocked(int state) {
            mState = state;
            mInfo = null;
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.refreshRate = mRefreshRate;
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.presentationDeadlineNanos = mDisplayPresentationDeadlineNanos +
                        1000000000L / (int) mRefreshRate;   // display's deadline + 1 frame
                mInfo.flags = DisplayDeviceInfo.FLAG_PRESENTATION;
                if (mSecure) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SECURE;
                }
                mInfo.type = Display.TYPE_OVERLAY;
                mInfo.touch = DisplayDeviceInfo.TOUCH_NONE;
                mInfo.state = mState;
            }
            return mInfo;
        }
    }

    /**
     * Functions as a handle for overlay display devices which are created and
     * destroyed asynchronously.
     *
     * Guarded by the {@link DisplayManagerService.SyncRoot} lock.
     */
    private final class OverlayDisplayHandle implements OverlayDisplayWindow.Listener {
        private final String mName;
        private final int mWidth;
        private final int mHeight;
        private final int mDensityDpi;
        private final int mGravity;
        private final boolean mSecure;

        private OverlayDisplayWindow mWindow;
        private OverlayDisplayDevice mDevice;

        public OverlayDisplayHandle(String name,
                int width, int height, int densityDpi, int gravity, boolean secure) {
            mName = name;
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
            mGravity = gravity;
            mSecure = secure;

            mUiHandler.post(mShowRunnable);
        }

        public void dismissLocked() {
            mUiHandler.removeCallbacks(mShowRunnable);
            mUiHandler.post(mDismissRunnable);
        }

        // Called on the UI thread.
        @Override
        public void onWindowCreated(SurfaceTexture surfaceTexture, float refreshRate,
                long presentationDeadlineNanos, int state) {
            synchronized (getSyncRoot()) {
                IBinder displayToken = SurfaceControl.createDisplay(mName, mSecure);
                mDevice = new OverlayDisplayDevice(displayToken, mName,
                        mWidth, mHeight, refreshRate, presentationDeadlineNanos,
                        mDensityDpi, mSecure, state, surfaceTexture);

                sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_ADDED);
            }
        }

        // Called on the UI thread.
        @Override
        public void onWindowDestroyed() {
            synchronized (getSyncRoot()) {
                if (mDevice != null) {
                    mDevice.destroyLocked();
                    sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_REMOVED);
                }
            }
        }

        // Called on the UI thread.
        @Override
        public void onStateChanged(int state) {
            synchronized (getSyncRoot()) {
                if (mDevice != null) {
                    mDevice.setStateLocked(state);
                    sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_CHANGED);
                }
            }
        }

        public void dumpLocked(PrintWriter pw) {
            pw.println("  " + mName + ":");
            pw.println("    mWidth=" + mWidth);
            pw.println("    mHeight=" + mHeight);
            pw.println("    mDensityDpi=" + mDensityDpi);
            pw.println("    mGravity=" + mGravity);
            pw.println("    mSecure=" + mSecure);

            // Try to dump the window state.
            if (mWindow != null) {
                final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
                ipw.increaseIndent();
                DumpUtils.dumpAsync(mUiHandler, mWindow, ipw, 200);
            }
        }

        // Runs on the UI thread.
        private final Runnable mShowRunnable = new Runnable() {
            @Override
            public void run() {
                OverlayDisplayWindow window = new OverlayDisplayWindow(getContext(),
                        mName, mWidth, mHeight, mDensityDpi, mGravity, mSecure,
                        OverlayDisplayHandle.this);
                window.show();

                synchronized (getSyncRoot()) {
                    mWindow = window;
                }
            }
        };

        // Runs on the UI thread.
        private final Runnable mDismissRunnable = new Runnable() {
            @Override
            public void run() {
                OverlayDisplayWindow window;
                synchronized (getSyncRoot()) {
                    window = mWindow;
                    mWindow = null;
                }

                if (window != null) {
                    window.dismiss();
                }
            }
        };
    }
}
