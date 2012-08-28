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

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A display adapter that uses overlay windows to simulate secondary displays
 * for development purposes.  Use Development Settings to enable one or more
 * overlay displays.
 * <p>
 * Display adapters are not thread-safe and must only be accessed
 * on the display manager service's handler thread.
 * </p>
 */
public final class OverlayDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "OverlayDisplayAdapter";

    private static final int MIN_WIDTH = 100;
    private static final int MIN_HEIGHT = 100;
    private static final int MAX_WIDTH = 4096;
    private static final int MAX_HEIGHT = 4096;

    private static final Pattern SETTING_PATTERN =
            Pattern.compile("(\\d+)x(\\d+)/(\\d+)");

    private final ArrayList<Overlay> mOverlays = new ArrayList<Overlay>();
    private String mCurrentOverlaySetting = "";

    public OverlayDisplayAdapter(Context context) {
        super(context, TAG);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("mCurrentOverlaySetting=" + mCurrentOverlaySetting);
        pw.println("mOverlays: size=" + mOverlays.size());
        for (Overlay overlay : mOverlays) {
            overlay.dump(pw);
        }
    }

    @Override
    protected void onRegister() {
        getContext().getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.Secure.OVERLAY_DISPLAY_DEVICES), true,
                new ContentObserver(getHandler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateOverlayDisplayDevices();
                    }
                });
        updateOverlayDisplayDevices();
    }

    private void updateOverlayDisplayDevices() {
        String value = Settings.System.getString(getContext().getContentResolver(),
                Settings.Secure.OVERLAY_DISPLAY_DEVICES);
        if (value == null) {
            value = "";
        }

        if (value.equals(mCurrentOverlaySetting)) {
            return;
        }
        mCurrentOverlaySetting = value;

        if (!mOverlays.isEmpty()) {
            Slog.i(TAG, "Dismissing all overlay display devices.");
            for (Overlay overlay : mOverlays) {
                overlay.dismiss();
            }
            mOverlays.clear();
        }

        int number = 1;
        for (String part : value.split(";")) {
            if (number > 4) {
                Slog.w(TAG, "Too many overlay display devices.");
            }
            Matcher matcher = SETTING_PATTERN.matcher(part);
            if (matcher.matches()) {
                try {
                    int width = Integer.parseInt(matcher.group(1), 10);
                    int height = Integer.parseInt(matcher.group(2), 10);
                    int densityDpi = Integer.parseInt(matcher.group(3), 10);
                    if (width >= MIN_WIDTH && width <= MAX_WIDTH
                            && height >= MIN_HEIGHT && height <= MAX_HEIGHT
                            && densityDpi >= DisplayMetrics.DENSITY_LOW
                            && densityDpi <= DisplayMetrics.DENSITY_XXHIGH) {
                        Slog.i(TAG, "Showing overlay display device #" + number
                                + ": width=" + width + ", height=" + height
                                + ", densityDpi=" + densityDpi);
                        mOverlays.add(new Overlay(number++, width, height, densityDpi));
                        continue;
                    }
                } catch (NumberFormatException ex) {
                }
            } else if (part.isEmpty()) {
                continue;
            }
            Slog.w(TAG, "Malformed overlay display devices setting: \"" + value + "\"");
        }

        for (Overlay overlay : mOverlays) {
            overlay.show();
        }
    }

    // Manages an overlay window.
    private final class Overlay {
        private final float INITIAL_SCALE = 0.5f;
        private final float MIN_SCALE = 0.3f;
        private final float MAX_SCALE = 1.0f;
        private final float WINDOW_ALPHA = 0.8f;

        // When true, disables support for moving and resizing the overlay.
        // The window is made non-touchable, which makes it possible to
        // directly interact with the content underneath.
        private final boolean DISABLE_MOVE_AND_RESIZE = false;

        private final DisplayManager mDisplayManager;
        private final WindowManager mWindowManager;

        private final int mNumber;
        private final int mWidth;
        private final int mHeight;
        private final int mDensityDpi;

        private final String mName;
        private final String mTitle;

        private final Display mDefaultDisplay;
        private final DisplayInfo mDefaultDisplayInfo = new DisplayInfo();
        private final IBinder mDisplayToken;
        private final OverlayDisplayDevice mDisplayDevice;

        private View mWindowContent;
        private WindowManager.LayoutParams mWindowParams;
        private TextureView mTextureView;
        private TextView mTitleTextView;
        private ScaleGestureDetector mScaleGestureDetector;

        private boolean mWindowVisible;
        private int mWindowX;
        private int mWindowY;
        private float mWindowScale;

        private int mLiveTranslationX;
        private int mLiveTranslationY;
        private float mLiveScale = 1.0f;

        private int mDragPointerId;
        private float mDragTouchX;
        private float mDragTouchY;

        public Overlay(int number, int width, int height, int densityDpi) {
            Context context = getContext();
            mDisplayManager = (DisplayManager)context.getSystemService(
                    Context.DISPLAY_SERVICE);
            mWindowManager = (WindowManager)context.getSystemService(
                    Context.WINDOW_SERVICE);

            mNumber = number;
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;

            mName = context.getResources().getString(
                    com.android.internal.R.string.display_manager_overlay_display_name, number);
            mTitle = context.getResources().getString(
                    com.android.internal.R.string.display_manager_overlay_display_title,
                    mNumber, mWidth, mHeight, mDensityDpi);

            mDefaultDisplay = mWindowManager.getDefaultDisplay();
            updateDefaultDisplayInfo();

            mDisplayToken = Surface.createDisplay(mName);
            mDisplayDevice = new OverlayDisplayDevice(mDisplayToken, mName,
                    mDefaultDisplayInfo.refreshRate, mDensityDpi);

            createWindow();
        }

        public void show() {
            if (!mWindowVisible) {
                mDisplayManager.registerDisplayListener(mDisplayListener, null);
                if (!updateDefaultDisplayInfo()) {
                    mDisplayManager.unregisterDisplayListener(mDisplayListener);
                    return;
                }

                clearLiveState();
                updateWindowParams();
                mWindowManager.addView(mWindowContent, mWindowParams);
                mWindowVisible = true;
            }
        }

        public void dismiss() {
            if (mWindowVisible) {
                mDisplayManager.unregisterDisplayListener(mDisplayListener);
                mWindowManager.removeView(mWindowContent);
                mWindowVisible = false;
            }
        }

        public void relayout() {
            if (mWindowVisible) {
                updateWindowParams();
                mWindowManager.updateViewLayout(mWindowContent, mWindowParams);
            }
        }

        public void dump(PrintWriter pw) {
            pw.println("  #" + mNumber + ": "
                    + mWidth + "x" + mHeight + ", " + mDensityDpi + " dpi");
            pw.println("    mName=" + mName);
            pw.println("    mWindowVisible=" + mWindowVisible);
            pw.println("    mWindowX=" + mWindowX);
            pw.println("    mWindowY=" + mWindowY);
            pw.println("    mWindowScale=" + mWindowScale);
            pw.println("    mWindowParams=" + mWindowParams);
            pw.println("    mLiveTranslationX=" + mLiveTranslationX);
            pw.println("    mLiveTranslationY=" + mLiveTranslationY);
            pw.println("    mLiveScale=" + mLiveScale);
        }

        private boolean updateDefaultDisplayInfo() {
            if (!mDefaultDisplay.getDisplayInfo(mDefaultDisplayInfo)) {
                Slog.w(TAG, "Cannot show overlay display because there is no "
                        + "default display upon which to show it.");
                return false;
            }
            return true;
        }

        private void createWindow() {
            Context context = getContext();
            LayoutInflater inflater = LayoutInflater.from(context);

            mWindowContent = inflater.inflate(
                    com.android.internal.R.layout.overlay_display_window, null);
            mWindowContent.setOnTouchListener(mOnTouchListener);

            mTextureView = (TextureView)mWindowContent.findViewById(
                    com.android.internal.R.id.overlay_display_window_texture);
            mTextureView.setPivotX(0);
            mTextureView.setPivotY(0);
            mTextureView.getLayoutParams().width = mWidth;
            mTextureView.getLayoutParams().height = mHeight;
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);

            mTitleTextView = (TextView)mWindowContent.findViewById(
                    com.android.internal.R.id.overlay_display_window_title);
            mTitleTextView.setText(mTitle);

            mWindowParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY);
            mWindowParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            if (DISABLE_MOVE_AND_RESIZE) {
                mWindowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            mWindowParams.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
            mWindowParams.alpha = WINDOW_ALPHA;
            mWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
            mWindowParams.setTitle(mTitle);

            mScaleGestureDetector = new ScaleGestureDetector(context, mOnScaleGestureListener);

            // By default, arrange the displays in the four corners.
            mWindowVisible = false;
            mWindowScale = INITIAL_SCALE;
            if (mNumber == 2 || mNumber == 3) {
                mWindowX = mDefaultDisplayInfo.logicalWidth;
            } else {
                mWindowX = 0;
            }
            if (mNumber == 2 || mNumber == 4) {
                mWindowY = mDefaultDisplayInfo.logicalHeight;
            } else {
                mWindowY = 0;
            }
        }

        private void updateWindowParams() {
            float scale = mWindowScale * mLiveScale;
            if (mWidth * scale > mDefaultDisplayInfo.logicalWidth) {
                scale = mDefaultDisplayInfo.logicalWidth / mWidth;
            }
            if (mHeight * scale > mDefaultDisplayInfo.logicalHeight) {
                scale = mDefaultDisplayInfo.logicalHeight / mHeight;
            }
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));

            float offsetScale = (scale / mWindowScale - 1.0f) * 0.5f;
            int width = (int)(mWidth * scale);
            int height = (int)(mHeight * scale);
            int x = mWindowX + mLiveTranslationX - (int)(width * offsetScale);
            int y = mWindowY + mLiveTranslationY - (int)(height * offsetScale);
            x = Math.max(0, Math.min(x, mDefaultDisplayInfo.logicalWidth - width));
            y = Math.max(0, Math.min(y, mDefaultDisplayInfo.logicalHeight - height));

            mTextureView.setScaleX(scale);
            mTextureView.setScaleY(scale);

            mWindowParams.x = x;
            mWindowParams.y = y;
            mWindowParams.width = width;
            mWindowParams.height = height;
        }

        private void saveWindowParams() {
            mWindowX = mWindowParams.x;
            mWindowY = mWindowParams.y;
            mWindowScale = mTextureView.getScaleX();
            clearLiveState();
        }

        private void clearLiveState() {
            mLiveTranslationX = 0;
            mLiveTranslationY = 0;
            mLiveScale = 1.0f;
        }

        private final DisplayManager.DisplayListener mDisplayListener =
                new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId == mDefaultDisplay.getDisplayId()) {
                    if (updateDefaultDisplayInfo()) {
                        relayout();
                    } else {
                        dismiss();
                    }
                }
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (displayId == mDefaultDisplay.getDisplayId()) {
                    dismiss();
                }
            }
        };

        private final SurfaceTextureListener mSurfaceTextureListener =
                new SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Surface.openTransaction();
                try {
                    Surface.setDisplaySurface(mDisplayToken, surface);
                } finally {
                    Surface.closeTransaction();
                }

                mDisplayDevice.setSize(width, height);
                sendDisplayDeviceEvent(mDisplayDevice, DISPLAY_DEVICE_EVENT_ADDED);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                sendDisplayDeviceEvent(mDisplayDevice, DISPLAY_DEVICE_EVENT_REMOVED);

                Surface.openTransaction();
                try {
                    Surface.setDisplaySurface(mDisplayToken, null);
                } finally {
                    Surface.closeTransaction();
                }
                return true;
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                mDisplayDevice.setSize(width, height);
                sendDisplayDeviceEvent(mDisplayDevice, DISPLAY_DEVICE_EVENT_CHANGED);
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        };

        private final View.OnTouchListener mOnTouchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                // Work in screen coordinates.
                final float oldX = event.getX();
                final float oldY = event.getY();
                event.setLocation(event.getRawX(), event.getRawY());

                mScaleGestureDetector.onTouchEvent(event);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        resetDrag(event);
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (event.getPointerCount() == 1) {
                            int index = event.findPointerIndex(mDragPointerId);
                            if (index < 0) {
                                resetDrag(event);
                            } else {
                                mLiveTranslationX = (int)(event.getX(index) - mDragTouchX);
                                mLiveTranslationY = (int)(event.getY(index) - mDragTouchY);
                                relayout();
                            }
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        saveWindowParams();
                        break;
                }

                // Revert to window coordinates.
                event.setLocation(oldX, oldY);
                return true;
            }

            private void resetDrag(MotionEvent event) {
                saveWindowParams();
                mDragPointerId = event.getPointerId(0);
                mDragTouchX = event.getX();
                mDragTouchY = event.getY();
            }
        };

        private final ScaleGestureDetector.OnScaleGestureListener mOnScaleGestureListener =
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                saveWindowParams();
                mDragPointerId = -1; // cause drag to be reset
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                mLiveScale = detector.getScaleFactor();
                relayout();
                return false;
            }
        };
    }

    private final class OverlayDisplayDevice extends DisplayDevice {
        private final String mName;
        private final float mRefreshRate;
        private final int mDensityDpi;
        private int mWidth;
        private int mHeight;

        public OverlayDisplayDevice(IBinder displayToken, String name,
                float refreshRate, int densityDpi) {
            super(OverlayDisplayAdapter.this, displayToken);
            mName = name;
            mRefreshRate = refreshRate;
            mDensityDpi = densityDpi;
        }

        public void setSize(int width, int height) {
            mWidth = width;
            mHeight = height;
        }

        @Override
        public void getInfo(DisplayDeviceInfo outInfo) {
            outInfo.name = mName;
            outInfo.width = mWidth;
            outInfo.height = mHeight;
            outInfo.refreshRate = mRefreshRate;
            outInfo.densityDpi = mDensityDpi;
            outInfo.xDpi = mDensityDpi;
            outInfo.yDpi = mDensityDpi;
            outInfo.flags = DisplayDeviceInfo.FLAG_SECURE;
        }
    }
}
