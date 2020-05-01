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

import android.annotation.Nullable;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * </p><p>
 * This adapter is configured via the
 * {@link android.provider.Settings.Global#OVERLAY_DISPLAY_DEVICES} setting. This setting should be
 * formatted as follows:
 * <pre>
 * [display1];[display2];...
 * </pre>
 * with each display specified as:
 * <pre>
 * [mode1]|[mode2]|...,[flag1],[flag2],...
 * </pre>
 * with each mode specified as:
 * <pre>
 * [width]x[height]/[densityDpi]
 * </pre>
 * Supported flags:
 * <ul>
 * <li><pre>secure</pre>: creates a secure display</li>
 * <li><pre>own_content_only</pre>: only shows this display's own content</li>
 * <li><pre>should_show_system_decorations</pre>: supports system decorations</li>
 * </ul>
 * </p><p>
 * Example:
 * <ul>
 * <li><code>1280x720/213</code>: make one overlay that is 1280x720 at 213dpi.</li>
 * <li><code>1920x1080/320,secure;1280x720/213</code>: make two overlays, the first at 1080p and
 * secure; the second at 720p.</li>
 * <li><code>1920x1080/320|3840x2160/640</code>: make one overlay that is 1920x1080 at
 * 213dpi by default, but can also be upscaled to 3840x2160 at 640dpi by the system if the
 * display device allows.</li>
 * <li>If the value is empty, then no overlay display devices are created.</li>
 * </ul></p>
 */
final class OverlayDisplayAdapter extends DisplayAdapter {
    static final String TAG = "OverlayDisplayAdapter";
    static final boolean DEBUG = false;

    /**
     * When this flag is set, the overlay display is considered secure.
     * @see DisplayDeviceInfo#FLAG_SECURE
     */
    private static final String OVERLAY_DISPLAY_FLAG_SECURE = "secure";

    /**
     * When this flag is set, only show this display's own content; do not mirror the content of
     * another display.
     * @see DisplayDeviceInfo#FLAG_OWN_CONTENT_ONLY
     */
    private static final String OVERLAY_DISPLAY_FLAG_OWN_CONTENT_ONLY = "own_content_only";

    /**
     * When this flag is set, the overlay display should support system decorations.
     * @see DisplayDeviceInfo#FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
     */
    private static final String OVERLAY_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS =
            "should_show_system_decorations";

    private static final int MIN_WIDTH = 100;
    private static final int MIN_HEIGHT = 100;
    private static final int MAX_WIDTH = 4096;
    private static final int MAX_HEIGHT = 4096;

    private static final String DISPLAY_SPLITTER = ";";
    private static final String MODE_SPLITTER = "\\|";
    private static final String FLAG_SPLITTER = ",";

    private static final Pattern DISPLAY_PATTERN = Pattern.compile("([^,]+)(,[,_a-z]+)*");
    private static final Pattern MODE_PATTERN = Pattern.compile("(\\d+)x(\\d+)/(\\d+)");

    // Unique id prefix for overlay displays.
    private static final String UNIQUE_ID_PREFIX = "overlay:";

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
        for (String part : value.split(DISPLAY_SPLITTER)) {
            Matcher displayMatcher = DISPLAY_PATTERN.matcher(part);
            if (displayMatcher.matches()) {
                if (count >= 4) {
                    Slog.w(TAG, "Too many overlay display devices specified: " + value);
                    break;
                }
                String modeString = displayMatcher.group(1);
                String flagString = displayMatcher.group(2);
                ArrayList<OverlayMode> modes = new ArrayList<>();
                for (String mode : modeString.split(MODE_SPLITTER)) {
                    Matcher modeMatcher = MODE_PATTERN.matcher(mode);
                    if (modeMatcher.matches()) {
                        try {
                            int width = Integer.parseInt(modeMatcher.group(1), 10);
                            int height = Integer.parseInt(modeMatcher.group(2), 10);
                            int densityDpi = Integer.parseInt(modeMatcher.group(3), 10);
                            if (width >= MIN_WIDTH && width <= MAX_WIDTH
                                    && height >= MIN_HEIGHT && height <= MAX_HEIGHT
                                    && densityDpi >= DisplayMetrics.DENSITY_LOW
                                    && densityDpi <= DisplayMetrics.DENSITY_XXXHIGH) {
                                modes.add(new OverlayMode(width, height, densityDpi));
                                continue;
                            } else {
                                Slog.w(TAG, "Ignoring out-of-range overlay display mode: " + mode);
                            }
                        } catch (NumberFormatException ex) {
                        }
                    } else if (mode.isEmpty()) {
                        continue;
                    }
                }
                if (!modes.isEmpty()) {
                    int number = ++count;
                    String name = getContext().getResources().getString(
                            com.android.internal.R.string.display_manager_overlay_display_name,
                            number);
                    int gravity = chooseOverlayGravity(number);
                    OverlayFlags flags = OverlayFlags.parseFlags(flagString);

                    Slog.i(TAG, "Showing overlay display device #" + number
                            + ": name=" + name + ", modes=" + Arrays.toString(modes.toArray())
                            + ", flags=" + flags);

                    mOverlays.add(new OverlayDisplayHandle(name, modes, gravity, flags, number));
                    continue;
                }
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

    private abstract class OverlayDisplayDevice extends DisplayDevice {
        private final String mName;
        private final float mRefreshRate;
        private final long mDisplayPresentationDeadlineNanos;
        private final OverlayFlags mFlags;
        private final List<OverlayMode> mRawModes;
        private final Display.Mode[] mModes;
        private final int mDefaultMode;

        private int mState;
        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;
        private DisplayDeviceInfo mInfo;
        private int mActiveMode;

        OverlayDisplayDevice(IBinder displayToken, String name,
                List<OverlayMode> modes, int activeMode, int defaultMode,
                float refreshRate, long presentationDeadlineNanos,
                OverlayFlags flags, int state, SurfaceTexture surfaceTexture, int number) {
            super(OverlayDisplayAdapter.this, displayToken, UNIQUE_ID_PREFIX + number);
            mName = name;
            mRefreshRate = refreshRate;
            mDisplayPresentationDeadlineNanos = presentationDeadlineNanos;
            mFlags = flags;
            mState = state;
            mSurfaceTexture = surfaceTexture;
            mRawModes = modes;
            mModes = new Display.Mode[modes.size()];
            for (int i = 0; i < modes.size(); i++) {
                OverlayMode mode = modes.get(i);
                mModes[i] = createMode(mode.mWidth, mode.mHeight, refreshRate);
            }
            mActiveMode = activeMode;
            mDefaultMode = defaultMode;
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
        public boolean hasStableUniqueId() {
            return false;
        }

        @Override
        public void performTraversalLocked(SurfaceControl.Transaction t) {
            if (mSurfaceTexture != null) {
                if (mSurface == null) {
                    mSurface = new Surface(mSurfaceTexture);
                }
                setSurfaceLocked(t, mSurface);
            }
        }

        public void setStateLocked(int state) {
            mState = state;
            mInfo = null;
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                Display.Mode mode = mModes[mActiveMode];
                OverlayMode rawMode = mRawModes.get(mActiveMode);
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.uniqueId = getUniqueId();
                mInfo.width = mode.getPhysicalWidth();
                mInfo.height = mode.getPhysicalHeight();
                mInfo.modeId = mode.getModeId();
                mInfo.defaultModeId = mModes[0].getModeId();
                mInfo.supportedModes = mModes;
                mInfo.densityDpi = rawMode.mDensityDpi;
                mInfo.xDpi = rawMode.mDensityDpi;
                mInfo.yDpi = rawMode.mDensityDpi;
                mInfo.presentationDeadlineNanos = mDisplayPresentationDeadlineNanos +
                        1000000000L / (int) mRefreshRate;   // display's deadline + 1 frame
                mInfo.flags = DisplayDeviceInfo.FLAG_PRESENTATION;
                if (mFlags.mSecure) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SECURE;
                }
                if (mFlags.mOwnContentOnly) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                }
                if (mFlags.mShouldShowSystemDecorations) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
                }
                mInfo.type = Display.TYPE_OVERLAY;
                mInfo.touch = DisplayDeviceInfo.TOUCH_VIRTUAL;
                mInfo.state = mState;
            }
            return mInfo;
        }

        @Override
        public void setDesiredDisplayModeSpecsLocked(
                DisplayModeDirector.DesiredDisplayModeSpecs displayModeSpecs) {
            final int id = displayModeSpecs.baseModeId;
            int index = -1;
            if (id == 0) {
                // Use the default.
                index = 0;
            } else {
                for (int i = 0; i < mModes.length; i++) {
                    if (mModes[i].getModeId() == id) {
                        index = i;
                        break;
                    }
                }
            }
            if (index == -1) {
                Slog.w(TAG, "Unable to locate mode " + id + ", reverting to default.");
                index = mDefaultMode;
            }
            if (mActiveMode == index) {
                return;
            }
            mActiveMode = index;
            mInfo = null;
            sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
            onModeChangedLocked(index);
        }

        /**
         * Called when the device switched to a new mode.
         *
         * @param index index of the mode in the list of modes
         */
        public abstract void onModeChangedLocked(int index);
    }

    /**
     * Functions as a handle for overlay display devices which are created and
     * destroyed asynchronously.
     *
     * Guarded by the {@link DisplayManagerService.SyncRoot} lock.
     */
    private final class OverlayDisplayHandle implements OverlayDisplayWindow.Listener {
        private static final int DEFAULT_MODE_INDEX = 0;

        private final String mName;
        private final List<OverlayMode> mModes;
        private final int mGravity;
        private final OverlayFlags mFlags;
        private final int mNumber;

        private OverlayDisplayWindow mWindow;
        private OverlayDisplayDevice mDevice;
        private int mActiveMode;

        OverlayDisplayHandle(
                String name,
                List<OverlayMode> modes,
                int gravity,
                OverlayFlags flags,
                int number) {
            mName = name;
            mModes = modes;
            mGravity = gravity;
            mFlags = flags;
            mNumber = number;

            mActiveMode = 0;

            showLocked();
        }

        private void showLocked() {
            mUiHandler.post(mShowRunnable);
        }

        public void dismissLocked() {
            mUiHandler.removeCallbacks(mShowRunnable);
            mUiHandler.post(mDismissRunnable);
        }

        private void onActiveModeChangedLocked(int index) {
            mUiHandler.removeCallbacks(mResizeRunnable);
            mActiveMode = index;
            if (mWindow != null) {
                mUiHandler.post(mResizeRunnable);
            }
        }

        // Called on the UI thread.
        @Override
        public void onWindowCreated(SurfaceTexture surfaceTexture, float refreshRate,
                long presentationDeadlineNanos, int state) {
            synchronized (getSyncRoot()) {
                IBinder displayToken = SurfaceControl.createDisplay(mName, mFlags.mSecure);
                mDevice = new OverlayDisplayDevice(displayToken, mName, mModes, mActiveMode,
                        DEFAULT_MODE_INDEX, refreshRate, presentationDeadlineNanos,
                        mFlags, state, surfaceTexture, mNumber) {
                    @Override
                    public void onModeChangedLocked(int index) {
                        onActiveModeChangedLocked(index);
                    }
                };

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
            pw.println("    mModes=" + Arrays.toString(mModes.toArray()));
            pw.println("    mActiveMode=" + mActiveMode);
            pw.println("    mGravity=" + mGravity);
            pw.println("    mFlags=" + mFlags);
            pw.println("    mNumber=" + mNumber);

            // Try to dump the window state.
            if (mWindow != null) {
                final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
                ipw.increaseIndent();
                DumpUtils.dumpAsync(mUiHandler, mWindow, ipw, "", 200);
            }
        }

        // Runs on the UI thread.
        private final Runnable mShowRunnable = new Runnable() {
            @Override
            public void run() {
                OverlayMode mode = mModes.get(mActiveMode);
                OverlayDisplayWindow window = new OverlayDisplayWindow(getContext(),
                        mName, mode.mWidth, mode.mHeight, mode.mDensityDpi, mGravity,
                        mFlags.mSecure, OverlayDisplayHandle.this);
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

        // Runs on the UI thread.
        private final Runnable mResizeRunnable = new Runnable() {
            @Override
            public void run() {
                OverlayMode mode;
                OverlayDisplayWindow window;
                synchronized (getSyncRoot()) {
                    if (mWindow == null) {
                        return;
                    }
                    mode = mModes.get(mActiveMode);
                    window = mWindow;
                }
                window.resize(mode.mWidth, mode.mHeight, mode.mDensityDpi);
            }
        };
    }

    /**
     * A display mode for an overlay display.
     */
    private static final class OverlayMode {
        final int mWidth;
        final int mHeight;
        final int mDensityDpi;

        OverlayMode(int width, int height, int densityDpi) {
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
        }

        @Override
        public String toString() {
            return new StringBuilder("{")
                    .append("width=").append(mWidth)
                    .append(", height=").append(mHeight)
                    .append(", densityDpi=").append(mDensityDpi)
                    .append("}")
                    .toString();
        }
    }

    /** Represents the flags of the overlay display. */
    private static final class OverlayFlags {
        /** See {@link #OVERLAY_DISPLAY_FLAG_SECURE}. */
        final boolean mSecure;

        /** See {@link #OVERLAY_DISPLAY_FLAG_OWN_CONTENT_ONLY}. */
        final boolean mOwnContentOnly;

        /** See {@link #OVERLAY_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS}. */
        final boolean mShouldShowSystemDecorations;

        OverlayFlags(
                boolean secure,
                boolean ownContentOnly,
                boolean shouldShowSystemDecorations) {
            mSecure = secure;
            mOwnContentOnly = ownContentOnly;
            mShouldShowSystemDecorations = shouldShowSystemDecorations;
        }

        static OverlayFlags parseFlags(@Nullable String flagString) {
            if (TextUtils.isEmpty(flagString)) {
                return new OverlayFlags(
                        false /* secure */,
                        false /* ownContentOnly */,
                        false /* shouldShowSystemDecorations */);
            }

            boolean secure = false;
            boolean ownContentOnly = false;
            boolean shouldShowSystemDecorations = false;
            for (String flag: flagString.split(FLAG_SPLITTER)) {
                if (OVERLAY_DISPLAY_FLAG_SECURE.equals(flag)) {
                    secure = true;
                }
                if (OVERLAY_DISPLAY_FLAG_OWN_CONTENT_ONLY.equals(flag)) {
                    ownContentOnly = true;
                }
                if (OVERLAY_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS.equals(flag)) {
                    shouldShowSystemDecorations = true;
                }
            }
            return new OverlayFlags(secure, ownContentOnly, shouldShowSystemDecorations);
        }

        @Override
        public String toString() {
            return new StringBuilder("{")
                    .append("secure=").append(mSecure)
                    .append(", ownContentOnly=").append(mOwnContentOnly)
                    .append(", shouldShowSystemDecorations=").append(mShouldShowSystemDecorations)
                    .append("}")
                    .toString();
        }
    }
}
