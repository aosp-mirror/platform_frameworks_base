/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.keyguard;

import static android.view.Display.INVALID_DISPLAY;

import android.app.Presentation;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.graphics.Point;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.os.Bundle;
import android.util.Slog;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

// TODO(multi-display): Support multiple external displays
public class KeyguardDisplayManager {
    protected static final String TAG = "KeyguardDisplayManager";
    private static boolean DEBUG = KeyguardConstants.DEBUG;

    private final ViewMediatorCallback mCallback;
    private final MediaRouter mMediaRouter;
    private final Context mContext;

    Presentation mPresentation;
    private boolean mShowing;

    public KeyguardDisplayManager(Context context, ViewMediatorCallback callback) {
        mContext = context;
        mCallback = callback;
        mMediaRouter = (MediaRouter) mContext.getSystemService(Context.MEDIA_ROUTER_SERVICE);
    }

    public void show() {
        if (!mShowing) {
            if (DEBUG) Slog.v(TAG, "show");
            mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                    mMediaRouterCallback, MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
            updateDisplays(true);
        }
        mShowing = true;
    }

    public void hide() {
        if (mShowing) {
            if (DEBUG) Slog.v(TAG, "hide");
            mMediaRouter.removeCallback(mMediaRouterCallback);
            updateDisplays(false);
        }
        mShowing = false;
    }

    private final MediaRouter.SimpleCallback mMediaRouterCallback =
            new MediaRouter.SimpleCallback() {
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
            if (DEBUG) Slog.d(TAG, "onRouteSelected: type=" + type + ", info=" + info);
            updateDisplays(mShowing);
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
            if (DEBUG) Slog.d(TAG, "onRouteUnselected: type=" + type + ", info=" + info);
            updateDisplays(mShowing);
        }

        @Override
        public void onRoutePresentationDisplayChanged(MediaRouter router, RouteInfo info) {
            if (DEBUG) Slog.d(TAG, "onRoutePresentationDisplayChanged: info=" + info);
            updateDisplays(mShowing);
        }
    };

    private OnDismissListener mOnDismissListener = new OnDismissListener() {

        @Override
        public void onDismiss(DialogInterface dialog) {
            mPresentation = null;
        }
    };

    protected void updateDisplays(boolean showing) {
        Presentation originalPresentation = mPresentation;
        if (showing) {
            MediaRouter.RouteInfo route = mMediaRouter.getSelectedRoute(
                    MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
            boolean useDisplay = route != null
                    && route.getPlaybackType() == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE;
            Display presentationDisplay = useDisplay ? route.getPresentationDisplay() : null;

            if (mPresentation != null && mPresentation.getDisplay() != presentationDisplay) {
                if (DEBUG) Slog.v(TAG, "Display gone: " + mPresentation.getDisplay());
                mPresentation.dismiss();
                mPresentation = null;
            }

            if (mPresentation == null && presentationDisplay != null) {
                if (DEBUG) Slog.i(TAG, "Keyguard enabled on display: " + presentationDisplay);
                mPresentation = new KeyguardPresentation(mContext, presentationDisplay,
                        R.style.keyguard_presentation_theme);
                mPresentation.setOnDismissListener(mOnDismissListener);
                try {
                    mPresentation.show();
                } catch (WindowManager.InvalidDisplayException ex) {
                    Slog.w(TAG, "Invalid display:", ex);
                    mPresentation = null;
                }
            }
        } else {
            if (mPresentation != null) {
                mPresentation.dismiss();
                mPresentation = null;
            }
        }

        // mPresentation is only updated when the display changes
        if (mPresentation != originalPresentation) {
            final int displayId = mPresentation != null
                    ? mPresentation.getDisplay().getDisplayId() : INVALID_DISPLAY;
            mCallback.onSecondaryDisplayShowingChanged(displayId);
        }
    }

    private final static class KeyguardPresentation extends Presentation {
        private static final int VIDEO_SAFE_REGION = 80; // Percentage of display width & height
        private static final int MOVE_CLOCK_TIMEOUT = 10000; // 10s
        private View mClock;
        private int mUsableWidth;
        private int mUsableHeight;
        private int mMarginTop;
        private int mMarginLeft;
        Runnable mMoveTextRunnable = new Runnable() {
            @Override
            public void run() {
                int x = mMarginLeft + (int) (Math.random() * (mUsableWidth - mClock.getWidth()));
                int y = mMarginTop + (int) (Math.random() * (mUsableHeight - mClock.getHeight()));
                mClock.setTranslationX(x);
                mClock.setTranslationY(y);
                mClock.postDelayed(mMoveTextRunnable, MOVE_CLOCK_TIMEOUT);
            }
        };

        public KeyguardPresentation(Context context, Display display, int theme) {
            super(context, display, theme);
            getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }

        @Override
        public void onDetachedFromWindow() {
            mClock.removeCallbacks(mMoveTextRunnable);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            Point p = new Point();
            getDisplay().getSize(p);
            mUsableWidth = VIDEO_SAFE_REGION * p.x/100;
            mUsableHeight = VIDEO_SAFE_REGION * p.y/100;
            mMarginLeft = (100 - VIDEO_SAFE_REGION) * p.x / 200;
            mMarginTop = (100 - VIDEO_SAFE_REGION) * p.y / 200;

            setContentView(R.layout.keyguard_presentation);
            mClock = findViewById(R.id.clock);

            // Avoid screen burn in
            mClock.post(mMoveTextRunnable);
        }
    }
}
