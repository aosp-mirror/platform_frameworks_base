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
import android.view.WindowManager;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.ripple.RippleShader.RippleShape;

/**
 * A WirelessChargingAnimation is a view containing view + animation for wireless charging.
 * @hide
 */
public class WirelessChargingAnimation {
    public static final int UNKNOWN_BATTERY_LEVEL = -1;
    public static final long DURATION = 1500;
    private static final String TAG = "WirelessChargingView";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

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
    private WirelessChargingAnimation(@NonNull Context context, @Nullable Looper looper,
            int transmittingBatteryLevel, int batteryLevel, Callback callback, boolean isDozing,
            RippleShape rippleShape, UiEventLogger uiEventLogger) {
        mCurrentWirelessChargingView = new WirelessChargingView(context, looper,
                transmittingBatteryLevel, batteryLevel, callback, isDozing,
                rippleShape, uiEventLogger);
    }

    /**
     * Creates a wireless charging animation object populated with next view.
     *
     * @hide
     */
    public static WirelessChargingAnimation makeWirelessChargingAnimation(@NonNull Context context,
            @Nullable Looper looper, int transmittingBatteryLevel, int batteryLevel,
            Callback callback, boolean isDozing, RippleShape rippleShape,
            UiEventLogger uiEventLogger) {
        return new WirelessChargingAnimation(context, looper, transmittingBatteryLevel,
                batteryLevel, callback, isDozing, rippleShape, uiEventLogger);
    }

    /**
     * Creates a charging animation object using mostly default values for non-dozing and unknown
     * battery level without charging number shown.
     */
    public static WirelessChargingAnimation makeChargingAnimationWithNoBatteryLevel(
            @NonNull Context context, RippleShape rippleShape, UiEventLogger uiEventLogger) {
        return makeWirelessChargingAnimation(context, null,
                UNKNOWN_BATTERY_LEVEL, UNKNOWN_BATTERY_LEVEL, null, false,
                rippleShape, uiEventLogger);
    }

    /**
     * Show the view for the specified duration.
     */
    public void show(long delay) {
        if (mCurrentWirelessChargingView == null ||
                mCurrentWirelessChargingView.mNextView == null) {
            throw new RuntimeException("setView must have been called");
        }

        if (mPreviousWirelessChargingView != null) {
            mPreviousWirelessChargingView.hide(0);
        }

        mPreviousWirelessChargingView = mCurrentWirelessChargingView;
        mCurrentWirelessChargingView.show(delay);
        mCurrentWirelessChargingView.hide(delay + DURATION);
    }

    private static class WirelessChargingView {
        private static final int SHOW = 0;
        private static final int HIDE = 1;

        private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
        private final Handler mHandler;
        private final UiEventLogger mUiEventLogger;

        private int mGravity;
        private WirelessChargingLayout mView;
        private WirelessChargingLayout mNextView;
        private WindowManager mWM;
        private Callback mCallback;

        public WirelessChargingView(Context context, @Nullable Looper looper,
                int transmittingBatteryLevel, int batteryLevel, Callback callback,
                boolean isDozing, RippleShape rippleShape, UiEventLogger uiEventLogger) {
            mCallback = callback;
            mNextView = new WirelessChargingLayout(context, transmittingBatteryLevel, batteryLevel,
                    isDozing, rippleShape);
            mGravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER;
            mUiEventLogger = uiEventLogger;

            final WindowManager.LayoutParams params = mParams;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.format = PixelFormat.TRANSLUCENT;
            params.type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
            params.setTitle("Charging Animation");
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setFitInsetsTypes(0 /* ignore all system bar insets */);
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            params.setTrustedOverlay();

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

        public void show(long delay) {
            if (DEBUG) Slog.d(TAG, "SHOW: " + this);
            mHandler.sendMessageDelayed(Message.obtain(mHandler, SHOW), delay);
        }

        public void hide(long duration) {
            mHandler.removeMessages(HIDE);

            if (DEBUG) Slog.d(TAG, "HIDE: " + this);
            mHandler.sendMessageDelayed(Message.obtain(mHandler, HIDE), duration);
        }

        private void handleShow() {
            if (DEBUG) {
                Slog.d(TAG, "HANDLE SHOW: " + this + " mView=" + mView + " mNextView="
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
                    if (DEBUG) Slog.d(TAG, "REMOVE! " + mView + " in " + this);
                    mWM.removeView(mView);
                }
                if (DEBUG) Slog.d(TAG, "ADD! " + mView + " in " + this);

                try {
                    if (mCallback != null) {
                        mCallback.onAnimationStarting();
                    }
                    mWM.addView(mView, mParams);
                    mUiEventLogger.log(WirelessChargingRippleEvent.WIRELESS_RIPPLE_PLAYED);
                } catch (WindowManager.BadTokenException e) {
                    Slog.d(TAG, "Unable to add wireless charging view. " + e);
                }
            }
        }

        private void handleHide() {
            if (DEBUG) Slog.d(TAG, "HANDLE HIDE: " + this + " mView=" + mView);
            if (mView != null) {
                if (mView.getParent() != null) {
                    if (DEBUG) Slog.d(TAG, "REMOVE! " + mView + " in " + this);
                    if (mCallback != null) {
                        mCallback.onAnimationEnded();
                    }
                    mWM.removeViewImmediate(mView);
                }

                mView = null;
            }
        }

        enum WirelessChargingRippleEvent implements UiEventLogger.UiEventEnum {
            @UiEvent(doc = "Wireless charging ripple effect played")
            WIRELESS_RIPPLE_PLAYED(830);

            private final int mInt;
            WirelessChargingRippleEvent(int id) {
                mInt = id;
            }

            @Override public int getId() {
                return mInt;
            }
        }
    }
}
