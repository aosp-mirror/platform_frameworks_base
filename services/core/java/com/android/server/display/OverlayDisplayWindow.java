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

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.TextView;

import java.io.PrintWriter;

/**
 * Manages an overlay window on behalf of {@link OverlayDisplayAdapter}.
 * <p>
 * This object must only be accessed on the UI thread.
 * No locks are held by this object and locks must not be held while making called into it.
 * </p>
 */
final class OverlayDisplayWindow implements DumpUtils.Dump {
    private static final String TAG = "OverlayDisplayWindow";
    private static final boolean DEBUG = false;

    private final float INITIAL_SCALE = 0.5f;
    private final float MIN_SCALE = 0.3f;
    private final float MAX_SCALE = 1.0f;
    private final float WINDOW_ALPHA = 0.8f;

    // When true, disables support for moving and resizing the overlay.
    // The window is made non-touchable, which makes it possible to
    // directly interact with the content underneath.
    private final boolean DISABLE_MOVE_AND_RESIZE = false;

    private final Context mContext;
    private final String mName;
    private int mWidth;
    private int mHeight;
    private int mDensityDpi;
    private final int mGravity;
    private final boolean mSecure;
    private final Listener mListener;
    private String mTitle;

    private final DisplayManager mDisplayManager;
    private final WindowManager mWindowManager;


    private final Display mDefaultDisplay;
    private final DisplayInfo mDefaultDisplayInfo = new DisplayInfo();

    private View mWindowContent;
    private WindowManager.LayoutParams mWindowParams;
    private TextureView mTextureView;
    private TextView mTitleTextView;

    private GestureDetector mGestureDetector;
    private ScaleGestureDetector mScaleGestureDetector;

    private boolean mWindowVisible;
    private int mWindowX;
    private int mWindowY;
    private float mWindowScale;

    private float mLiveTranslationX;
    private float mLiveTranslationY;
    private float mLiveScale = 1.0f;

    public OverlayDisplayWindow(Context context, String name,
            int width, int height, int densityDpi, int gravity, boolean secure,
            Listener listener) {
        mContext = context;
        mName = name;
        mGravity = gravity;
        mSecure = secure;
        mListener = listener;

        mDisplayManager = (DisplayManager)context.getSystemService(
                Context.DISPLAY_SERVICE);
        mWindowManager = (WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE);

        mDefaultDisplay = mWindowManager.getDefaultDisplay();
        updateDefaultDisplayInfo();

        resize(width, height, densityDpi, false /* doLayout */);

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

    public void resize(int width, int height, int densityDpi) {
        resize(width, height, densityDpi, true /* doLayout */);
    }

    private void resize(int width, int height, int densityDpi, boolean doLayout) {
        mWidth = width;
        mHeight = height;
        mDensityDpi = densityDpi;
        mTitle = mContext.getResources().getString(
                com.android.internal.R.string.display_manager_overlay_display_title,
                mName, mWidth, mHeight, mDensityDpi);
        if (mSecure) {
            mTitle += mContext.getResources().getString(
                    com.android.internal.R.string.display_manager_overlay_display_secure_suffix);
        }
        if (doLayout) {
            relayout();
        }
    }

    public void relayout() {
        if (mWindowVisible) {
            updateWindowParams();
            mWindowManager.updateViewLayout(mWindowContent, mWindowParams);
        }
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        pw.println("mWindowVisible=" + mWindowVisible);
        pw.println("mWindowX=" + mWindowX);
        pw.println("mWindowY=" + mWindowY);
        pw.println("mWindowScale=" + mWindowScale);
        pw.println("mWindowParams=" + mWindowParams);
        if (mTextureView != null) {
            pw.println("mTextureView.getScaleX()=" + mTextureView.getScaleX());
            pw.println("mTextureView.getScaleY()=" + mTextureView.getScaleY());
        }
        pw.println("mLiveTranslationX=" + mLiveTranslationX);
        pw.println("mLiveTranslationY=" + mLiveTranslationY);
        pw.println("mLiveScale=" + mLiveScale);
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
        LayoutInflater inflater = LayoutInflater.from(mContext);

        mWindowContent = inflater.inflate(
                com.android.internal.R.layout.overlay_display_window, null);
        mWindowContent.setOnTouchListener(mOnTouchListener);

        mTextureView = (TextureView)mWindowContent.findViewById(
                com.android.internal.R.id.overlay_display_window_texture);
        mTextureView.setPivotX(0);
        mTextureView.setPivotY(0);
        mTextureView.getLayoutParams().width = mWidth;
        mTextureView.getLayoutParams().height = mHeight;
        mTextureView.setOpaque(false);
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
        if (mSecure) {
            mWindowParams.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        }
        if (DISABLE_MOVE_AND_RESIZE) {
            mWindowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        mWindowParams.privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
        mWindowParams.alpha = WINDOW_ALPHA;
        mWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        mWindowParams.setTitle(mTitle);

        mGestureDetector = new GestureDetector(mContext, mOnGestureListener);
        mScaleGestureDetector = new ScaleGestureDetector(mContext, mOnScaleGestureListener);

        // Set the initial position and scale.
        // The position and scale will be clamped when the display is first shown.
        mWindowX = (mGravity & Gravity.LEFT) == Gravity.LEFT ?
                0 : mDefaultDisplayInfo.logicalWidth;
        mWindowY = (mGravity & Gravity.TOP) == Gravity.TOP ?
                0 : mDefaultDisplayInfo.logicalHeight;
        mWindowScale = INITIAL_SCALE;
    }

    private void updateWindowParams() {
        float scale = mWindowScale * mLiveScale;
        scale = Math.min(scale, (float)mDefaultDisplayInfo.logicalWidth / mWidth);
        scale = Math.min(scale, (float)mDefaultDisplayInfo.logicalHeight / mHeight);
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));

        float offsetScale = (scale / mWindowScale - 1.0f) * 0.5f;
        int width = (int)(mWidth * scale);
        int height = (int)(mHeight * scale);
        int x = (int)(mWindowX + mLiveTranslationX - width * offsetScale);
        int y = (int)(mWindowY + mLiveTranslationY - height * offsetScale);
        x = Math.max(0, Math.min(x, mDefaultDisplayInfo.logicalWidth - width));
        y = Math.max(0, Math.min(y, mDefaultDisplayInfo.logicalHeight - height));

        if (DEBUG) {
            Slog.d(TAG, "updateWindowParams: scale=" + scale
                    + ", offsetScale=" + offsetScale
                    + ", x=" + x + ", y=" + y
                    + ", width=" + width + ", height=" + height);
        }

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
        mLiveTranslationX = 0f;
        mLiveTranslationY = 0f;
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
                    mListener.onStateChanged(mDefaultDisplayInfo.state);
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
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                int width, int height) {
            mListener.onWindowCreated(surfaceTexture,
                    mDefaultDisplayInfo.getMode().getRefreshRate(),
                    mDefaultDisplayInfo.presentationDeadlineNanos, mDefaultDisplayInfo.state);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            mListener.onWindowDestroyed();
            return true;
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                int width, int height) {
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }
    };

    private final View.OnTouchListener mOnTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            // Work in screen coordinates.
            final float oldX = event.getX();
            final float oldY = event.getY();
            event.setLocation(event.getRawX(), event.getRawY());

            mGestureDetector.onTouchEvent(event);
            mScaleGestureDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    saveWindowParams();
                    break;
            }

            // Revert to window coordinates.
            event.setLocation(oldX, oldY);
            return true;
        }
    };

    private final GestureDetector.OnGestureListener mOnGestureListener =
            new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                float distanceX, float distanceY) {
            mLiveTranslationX -= distanceX;
            mLiveTranslationY -= distanceY;
            relayout();
            return true;
        }
    };

    private final ScaleGestureDetector.OnScaleGestureListener mOnScaleGestureListener =
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            mLiveScale *= detector.getScaleFactor();
            relayout();
            return true;
        }
    };

    /**
     * Watches for significant changes in the overlay display window lifecycle.
     */
    public interface Listener {
        public void onWindowCreated(SurfaceTexture surfaceTexture,
                float refreshRate, long presentationDeadlineNanos, int state);
        public void onWindowDestroyed();
        public void onStateChanged(int state);
    }
}
