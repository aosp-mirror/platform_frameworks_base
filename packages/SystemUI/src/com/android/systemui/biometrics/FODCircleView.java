/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreen;
import vendor.lineage.biometrics.fingerprint.inscreen.V1_0.IFingerprintInscreenCallback;

import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

public class FODCircleView extends ImageView implements OnTouchListener, ConfigurationListener {
    private final int mPositionX;
    private final int mPositionY;
    private final int mWidth;
    private final int mHeight;
    private final int mDreamingMaxOffset;
    private final boolean mShouldBoostBrightness;
    private final Paint mPaintFingerprint = new Paint();
    private final Paint mPaintShow = new Paint();
    private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();
    private final WindowManager mWindowManager;

    private IFingerprintInscreen mFingerprintInscreenDaemon;

    private int mDreamingOffsetX;
    private int mDreamingOffsetY;
    private int mNavigationBarSize;

    private boolean mIsBouncer;
    private boolean mIsDreaming;
    private boolean mIsInsideCircle;
    private boolean mIsPressed;
    private boolean mIsPulsing;
    private boolean mIsScreenOn;
    private boolean mIsViewAdded;

    private Handler mHandler;

    private Timer mBurnInProtectionTimer;

    private IFingerprintInscreenCallback mFingerprintInscreenCallback =
            new IFingerprintInscreenCallback.Stub() {
        @Override
        public void onFingerDown() {
            mIsInsideCircle = true;

            mHandler.post(() -> {
                setDim(true);
                setImageDrawable(null);

                invalidate();
            });
        }

        @Override
        public void onFingerUp() {
            mIsInsideCircle = false;

            mHandler.post(() -> {
                setDim(false);
                setImageResource(R.drawable.fod_icon_default);

                invalidate();
            });
        }
    };

    private KeyguardUpdateMonitor mUpdateMonitor;

    private KeyguardUpdateMonitorCallback mMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onDreamingStateChanged(boolean dreaming) {
            super.onDreamingStateChanged(dreaming);
            mIsDreaming = dreaming;
            mIsInsideCircle = false;
            if (dreaming) {
                mBurnInProtectionTimer = new Timer();
                mBurnInProtectionTimer.schedule(new BurnInProtectionTask(), 0, 60 * 1000);
            } else if (mBurnInProtectionTimer != null) {
                mBurnInProtectionTimer.cancel();
            }
            if (mIsViewAdded) {
                resetPosition();
                invalidate();
            }
        }

        @Override
        public void onPulsing(boolean pulsing) {
            super.onPulsing(pulsing);
            mIsPulsing = pulsing;
	    if (mIsPulsing) {
                mIsDreaming = false;
	    }
            mIsInsideCircle = false;
        }

        @Override
        public void onScreenTurnedOff() {
            super.onScreenTurnedOff();
            mIsInsideCircle = false;
        }

        @Override
        public void onStartedGoingToSleep(int why) {
            super.onStartedGoingToSleep(why);
            mIsInsideCircle = false;
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            super.onFinishedGoingToSleep(why);
        }

        @Override
        public void onStartedWakingUp() {
            super.onStartedWakingUp();
        }

        @Override
        public void onScreenTurnedOn() {
            super.onScreenTurnedOn();
            mIsScreenOn = true;
            mIsInsideCircle = false;
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            super.onKeyguardVisibilityChanged(showing);
            mIsInsideCircle = false;
        }

        @Override
        public void onKeyguardBouncerChanged(boolean isBouncer) {
            mIsBouncer = isBouncer;
            if (isBouncer) {
                hide();
            } else if (mUpdateMonitor.isFingerprintDetectionRunning()) {
                show();
            }
        }

        @Override
        public void onStrongAuthStateChanged(int userId) {
            super.onStrongAuthStateChanged(userId);
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType) {
            super.onBiometricAuthenticated(userId, biometricSourceType);
            mIsInsideCircle = false;
        }

        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            super.onBiometricRunningStateChanged(running, biometricSourceType);
            if (running) {
                show();
            } else {
                hide();
            }
        }
    };

    private boolean mCutoutMasked;
    private int mStatusbarHeight;

    public FODCircleView(Context context) {
        super(context);

        Resources res = context.getResources();

        mPaintFingerprint.setAntiAlias(true);
        mPaintFingerprint.setColor(res.getColor(R.color.config_fodColor));

        setImageResource(R.drawable.fod_icon_default);

        mPaintShow.setAntiAlias(true);
        mPaintShow.setColor(res.getColor(R.color.config_fodColor));

        setOnTouchListener(this);

        mWindowManager = context.getSystemService(WindowManager.class);

        mNavigationBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);

        try {
            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            if (daemon == null) {
                throw new RuntimeException("Unable to get IFingerprintInscreen");
            }
            mPositionX = daemon.getPositionX();
            mPositionY = daemon.getPositionY();
            mWidth = daemon.getSize();
            mHeight = mWidth; // We do not expect mWidth != mHeight

            mShouldBoostBrightness = daemon.shouldBoostBrightness();
        } catch (NoSuchElementException | RemoteException e) {
            throw new RuntimeException(e);
        }

        if (mPositionX < 0 || mPositionY < 0 || mWidth < 0 || mHeight < 0) {
            throw new RuntimeException("Invalid FOD circle position or size.");
        }

        mDreamingMaxOffset = (int) (mWidth * 0.1f);

        mHandler = new Handler(Looper.getMainLooper());

        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
        mUpdateMonitor.registerCallback(mMonitorCallback);


        updateCutoutFlags();

        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mIsInsideCircle) {
            canvas.drawCircle(mWidth / 2, mHeight / 2, (float) (mWidth / 2.0f), mPaintFingerprint);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // onLayout is a good time to call the HAL because dim layer
        // added by setDim() should have come into effect
        // the HAL is expected (if supported) to set the screen brightness
        // to maximum / minimum immediately when called
        if (mIsInsideCircle) {
            if (mIsDreaming || mIsPulsing) {
                setAlpha(1.0f);
            }
            if (!mIsPressed) {
                IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
                if (daemon != null) {
                    try {
                        daemon.onPress();
                    } catch (RemoteException e) {
                        // do nothing
                    }
                }
                mIsPressed = true;
            }
        } else {
            setAlpha(mIsDreaming ? (mIsPulsing ? 1.0f : 0.8f) : 1.0f);
            if (mIsPressed) {
                IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
                if (daemon != null) {
                    try {
                        daemon.onRelease();
                    } catch (RemoteException e) {
                        // do nothing
                    }
                }
                mIsPressed = false;
            }
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);

        boolean newInside = (x > 0 && x < mWidth) && (y > 0 && y < mWidth);

        if (event.getAction() == MotionEvent.ACTION_UP) {
            newInside = false;
            setDim(false);
            setImageResource(R.drawable.fod_icon_default);
        }

        if (newInside == mIsInsideCircle) {
            return mIsInsideCircle;
        }

        mIsInsideCircle = newInside;

        invalidate();

        if (!mIsInsideCircle) {
            setImageResource(R.drawable.fod_icon_default);
            return false;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            setDim(true);
            setImageDrawable(null);
        }

        return true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mIsViewAdded) {
            resetPosition();
            mWindowManager.updateViewLayout(this, mParams);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon != null) {
            try {
                daemon.onHideFODView();
            } catch (RemoteException e) {
                // do nothing
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
        if (daemon != null) {
            try {
                daemon.onShowFODView();
            } catch (RemoteException e) {
                // do nothing
            }
        }
    }

    public synchronized IFingerprintInscreen getFingerprintInScreenDaemon() {
        if (mFingerprintInscreenDaemon == null) {
            try {
                mFingerprintInscreenDaemon = IFingerprintInscreen.getService();
                if (mFingerprintInscreenDaemon != null) {
                    mFingerprintInscreenDaemon.setCallback(mFingerprintInscreenCallback);
                    mFingerprintInscreenDaemon.asBinder().linkToDeath((cookie) -> {
                        mFingerprintInscreenDaemon = null;
                    }, 0);
                }
            } catch (NoSuchElementException | RemoteException e) {
                // do nothing
            }
        }
        return mFingerprintInscreenDaemon;
    }

    public void show() {
        if (mIsViewAdded) {
            return;
        }

        if (mIsBouncer) {
            return;
        }

        resetPosition();

        mParams.height = mWidth;
        mParams.width = mHeight;
        mParams.format = PixelFormat.TRANSLUCENT;

        mParams.setTitle("Fingerprint on display");
        mParams.packageName = "android";
        mParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
        mParams.flags = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM |
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_DIM_BEHIND |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mParams.gravity = Gravity.TOP | Gravity.LEFT;

        setImageResource(R.drawable.fod_icon_default);

        mWindowManager.addView(this, mParams);
        mIsViewAdded = true;

        mIsPressed = false;
        setDim(false);
    }

    public void hide() {
        if (!mIsViewAdded) {
            return;
        }

        mIsInsideCircle = false;
        mIsViewAdded = false;
        mWindowManager.removeView(this);
        mIsPressed = false;
        setDim(false);
        invalidate();
    }

    private void resetPosition() {
        Display defaultDisplay = mWindowManager.getDefaultDisplay();

        Point size = new Point();
        defaultDisplay.getRealSize(size);

        int rotation = defaultDisplay.getRotation();
        int cutoutMaskedExtra = mCutoutMasked ? mStatusbarHeight : 0;

        switch (rotation) {
            case Surface.ROTATION_0:
                mParams.x = mPositionX;
                mParams.y = mPositionY - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_90:
                mParams.x = mPositionY;
                mParams.y = mPositionX - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_180:
                mParams.x = mPositionX;
                mParams.y = size.y - mPositionY - mHeight - cutoutMaskedExtra;
                break;
            case Surface.ROTATION_270:
                mParams.x = size.x - mPositionY - mWidth - mNavigationBarSize - cutoutMaskedExtra;
                mParams.y = mPositionX;
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }

        if (mIsDreaming) {
            mParams.x += mDreamingOffsetX;
            mParams.y += mDreamingOffsetY;
        }

        if (mIsViewAdded) {
            mWindowManager.updateViewLayout(this, mParams);
        }
    }

    private void setDim(boolean dim) {
        if (dim) {
            int curBrightness = Settings.System.getInt(getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 100);
            int dimAmount = 0;

            IFingerprintInscreen daemon = getFingerprintInScreenDaemon();
            if (daemon != null) {
                try {
                    dimAmount = daemon.getDimAmount(curBrightness);
                } catch (RemoteException e) {
                    // do nothing
                }
            }

            if (mShouldBoostBrightness) {
                mParams.screenBrightness = 1.0f;
            }

            mParams.dimAmount = ((float) dimAmount) / 255.0f;
        } else {
            mParams.screenBrightness = 0.0f;
            mParams.dimAmount = 0.0f;
        }

        try {
            mWindowManager.updateViewLayout(this, mParams);
        } catch (IllegalArgumentException e) {
            // do nothing
        }
    }

    private class BurnInProtectionTask extends TimerTask {
        @Override
        public void run() {
            // It is fine to modify the variables here because
            // no other thread will be modifying it
            long now = System.currentTimeMillis() / 1000 / 60;
            mDreamingOffsetX = (int) (now % (mDreamingMaxOffset * 4));
            if (mDreamingOffsetX > mDreamingMaxOffset * 2) {
                mDreamingOffsetX = mDreamingMaxOffset * 4 - mDreamingOffsetX;
            }
            // Let y to be not synchronized with x, so that we get maximum movement
            mDreamingOffsetY = (int) ((now + mDreamingMaxOffset / 3) % (mDreamingMaxOffset * 2));
            if (mDreamingOffsetY > mDreamingMaxOffset * 2) {
                mDreamingOffsetY = mDreamingMaxOffset * 4 - mDreamingOffsetY;
            }
            mDreamingOffsetX -= mDreamingMaxOffset;
            mDreamingOffsetY -= mDreamingMaxOffset;
            if (mIsViewAdded) {
                mHandler.post(() -> resetPosition());
            }
        }
    };

    @Override
    public void onOverlayChanged() {
        updateCutoutFlags();
    }

    private void updateCutoutFlags() {
        mStatusbarHeight = getContext().getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height_portrait);
        boolean cutoutMasked = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_maskMainBuiltInDisplayCutout);
        if (mCutoutMasked != cutoutMasked){
            mCutoutMasked = cutoutMasked;
            if (mIsViewAdded) {
                resetPosition();
            }
        }
    }
}
