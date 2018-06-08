/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.charging;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Slog;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * A WirelessChargingAnimation is a view containing view + animation for wireless charging.
 * @hide
 */
public class WirelessChargingAnimation {

    public static final long DURATION = 1133;
    private static final String TAG = "WirelessChargingView";
    private static final boolean LOCAL_LOGV = false;

    private final WirelessChargingView mCurrentWirelessChargingView;
    private static WirelessChargingView mPreviousWirelessChargingView;

    public interface Callback {
        void onAnimationStarting();
        void onAnimationEnded();
    }

    /**
     * Constructs an empty WirelessChargingAnimation object.  If looper is null,
     * Looper.myLooper() is used.  Must set
     * {@link WirelessChargingAnimation#mCurrentWirelessChargingView}
     * before calling {@link #show} - can be done through {@link #makeWirelessChargingAnimation}.
     * @hide
     */
    public WirelessChargingAnimation(@NonNull Context context, @Nullable Looper looper, int
            batteryLevel, Callback callback, boolean isDozing) {
        mCurrentWirelessChargingView = new WirelessChargingView(context, looper,
                batteryLevel, callback, isDozing);
    }

    /**
     * Creates a wireless charging animation object populated with next view.
     * @hide
     */
    public static WirelessChargingAnimation makeWirelessChargingAnimation(@NonNull Context context,
            @Nullable Looper looper, int batteryLevel, Callback callback, boolean isDozing) {
        return new WirelessChargingAnimation(context, looper, batteryLevel, callback, isDozing);
    }

    /**
     * Show the view for the specified duration.
     */
    public void show() {
        if (mCurrentWirelessChargingView == null ||
                mCurrentWirelessChargingView.mNextView == null) {
            throw new RuntimeException("setView must have been called");
        }

        if (mPreviousWirelessChargingView != null) {
            mPreviousWirelessChargingView.hide(0);
        }

        mPreviousWirelessChargingView = mCurrentWirelessChargingView;
        mCurrentWirelessChargingView.show();
        mCurrentWirelessChargingView.hide(DURATION);
    }

    private static class WirelessChargingView {
        private static final int SHOW = 0;
        private static final int HIDE = 1;

        private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
        private final Handler mHandler;

        private int mGravity;

        private View mView;
        private View mNextView;
        private WindowManager mWM;
        private Callback mCallback;

        public WirelessChargingView(Context context, @Nullable Looper looper, int batteryLevel,
                Callback callback, boolean isDozing) {
            mCallback = callback;
            mNextView = new WirelessChargingLayout(context, batteryLevel, isDozing);
            mGravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER;

            final WindowManager.LayoutParams params = mParams;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.format = PixelFormat.TRANSLUCENT;

            params.type = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
            params.setTitle("Charging Animation");
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND;

            params.dimAmount = .3f;

            if (looper == null) {
                // Use Looper.myLooper() if looper is not specified.
                looper = Looper.myLooper();
                if (looper == null) {
                    throw new RuntimeException(
                            "Can't display wireless animation on a thread that has not called "
                                    + "Looper.prepare()");
                }
            }

            mHandler = new Handler(looper, null) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case SHOW: {
                            handleShow();
                            break;
                        }
                        case HIDE: {
                            handleHide();
                            // Don't do this in handleHide() because it is also invoked by
                            // handleShow()
                            mNextView = null;
                            break;
                        }
                    }
                }
            };
        }

        public void show() {
            if (LOCAL_LOGV) Log.v(TAG, "SHOW: " + this);
            mHandler.obtainMessage(SHOW).sendToTarget();
        }

        public void hide(long duration) {
            mHandler.removeMessages(HIDE);

            if (LOCAL_LOGV) Log.v(TAG, "HIDE: " + this);
            mHandler.sendMessageDelayed(Message.obtain(mHandler, HIDE), duration);
        }

        private void handleShow() {
            if (LOCAL_LOGV) {
                Log.v(TAG, "HANDLE SHOW: " + this + " mView=" + mView + " mNextView="
                        + mNextView);
            }

            if (mView != mNextView) {
                // remove the old view if necessary
                handleHide();
                mView = mNextView;
                Context context = mView.getContext().getApplicationContext();
                String packageName = mView.getContext().getOpPackageName();
                if (context == null) {
                    context = mView.getContext();
                }
                mWM = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                mParams.packageName = packageName;
                mParams.hideTimeoutMilliseconds = DURATION;

                if (mView.getParent() != null) {
                    if (LOCAL_LOGV) Log.v(TAG, "REMOVE! " + mView + " in " + this);
                    mWM.removeView(mView);
                }
                if (LOCAL_LOGV) Log.v(TAG, "ADD! " + mView + " in " + this);

                try {
                    if (mCallback != null) {
                        mCallback.onAnimationStarting();
                    }
                    mWM.addView(mView, mParams);
                } catch (WindowManager.BadTokenException e) {
                    Slog.d(TAG, "Unable to add wireless charging view. " + e);
                }
            }
        }

        private void handleHide() {
            if (LOCAL_LOGV) Log.v(TAG, "HANDLE HIDE: " + this + " mView=" + mView);
            if (mView != null) {
                if (mView.getParent() != null) {
                    if (LOCAL_LOGV) Log.v(TAG, "REMOVE! " + mView + " in " + this);
                    if (mCallback != null) {
                        mCallback.onAnimationEnded();
                    }
                    mWM.removeViewImmediate(mView);
                }

                mView = null;
            }
        }
    }
}
