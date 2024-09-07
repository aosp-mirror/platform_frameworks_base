/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.app;

import static android.os.VibrationEffect.Composition.PRIMITIVE_SPIN;

import android.animation.ObjectAnimator;
import android.animation.TimeAnimator;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;

import org.json.JSONObject;

import java.util.Random;

/**
 * @hide
 */
public class PlatLogoActivity extends Activity {
    private static final String TAG = "PlatLogoActivity";

    private static final long LAUNCH_TIME = 5000L;

    private static final String EGG_UNLOCK_SETTING = "egg_mode_v";

    private static final float MIN_WARP = 1f;
    private static final float MAX_WARP = 10f; // after all these years
    private static final boolean FINISH_AFTER_NEXT_STAGE_LAUNCH = false;

    private ImageView mLogo;
    private Starfield mStarfield;

    private FrameLayout mLayout;

    private TimeAnimator mAnim;
    private ObjectAnimator mWarpAnim;
    private Random mRandom;
    private float mDp;

    private RumblePack mRumble;

    private boolean mAnimationsEnabled = true;

    private final View.OnTouchListener mTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    measureTouchPressure(event);
                    startWarp();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopWarp();
                    break;
            }
            return true;
        }

    };

    private final Runnable mLaunchNextStage = () -> {
        stopWarp();
        launchNextStage(false);
    };

    private final TimeAnimator.TimeListener mTimeListener = new TimeAnimator.TimeListener() {
        @Override
        public void onTimeUpdate(TimeAnimator animation, long totalTime, long deltaTime) {
            mStarfield.update(deltaTime);
            final float warpFrac = (mStarfield.getWarp() - MIN_WARP) / (MAX_WARP - MIN_WARP);
            if (mAnimationsEnabled) {
                mLogo.setTranslationX(mRandom.nextFloat() * warpFrac * 5 * mDp);
                mLogo.setTranslationY(mRandom.nextFloat() * warpFrac * 5 * mDp);
            }
            if (warpFrac > 0f) {
                mRumble.rumble(warpFrac);
            }
            mLayout.postInvalidate();
        }
    };

    private class RumblePack implements Handler.Callback {
        private static final int MSG = 6464;
        private static final int INTERVAL = 50;

        private final VibratorManager mVibeMan;
        private final HandlerThread mVibeThread;
        private final Handler mVibeHandler;
        private boolean mSpinPrimitiveSupported;

        private long mLastVibe = 0;

        @SuppressLint("MissingPermission")
        @Override
        public boolean handleMessage(Message msg) {
            final float warpFrac = msg.arg1 / 100f;
            if (mSpinPrimitiveSupported) {
                if (msg.getWhen() > mLastVibe + INTERVAL) {
                    mLastVibe = msg.getWhen();
                    mVibeMan.vibrate(CombinedVibration.createParallel(
                            VibrationEffect.startComposition()
                                    .addPrimitive(PRIMITIVE_SPIN, (float) Math.pow(warpFrac, 3.0))
                                    .compose()
                    ));
                }
            } else {
                if (mRandom.nextFloat() < warpFrac) {
                    mLogo.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            return false;
        }
        RumblePack() {
            mVibeMan = getSystemService(VibratorManager.class);
            mSpinPrimitiveSupported = mVibeMan.getDefaultVibrator()
                    .areAllPrimitivesSupported(PRIMITIVE_SPIN);

            mVibeThread = new HandlerThread("VibratorThread");
            mVibeThread.start();
            mVibeHandler = Handler.createAsync(mVibeThread.getLooper(), this);
        }

        public void destroy() {
            mVibeThread.quit();
        }

        private void rumble(float warpFrac) {
            if (!mVibeThread.isAlive()) return;

            final Message msg = Message.obtain();
            msg.what = MSG;
            msg.arg1 = (int) (warpFrac * 100);
            mVibeHandler.removeMessages(MSG);
            mVibeHandler.sendMessage(msg);
        }

    }

    @Override
    protected void onDestroy() {
        mRumble.destroy();

        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarColor(0);
        getWindow().setStatusBarColor(0);
        getWindow().getDecorView().getWindowInsetsController().hide(WindowInsets.Type.systemBars());

        final ActionBar ab = getActionBar();
        if (ab != null) ab.hide();

        try {
            mAnimationsEnabled = Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE) > 0f;
        } catch (Settings.SettingNotFoundException e) {
            mAnimationsEnabled = true;
        }

        mRumble = new RumblePack();

        mLayout = new FrameLayout(this);
        mRandom = new Random();
        mDp = getResources().getDisplayMetrics().density;
        mStarfield = new Starfield(mRandom, mDp * 2f);
        mStarfield.setVelocity(
                200f * (mRandom.nextFloat() - 0.5f),
                200f * (mRandom.nextFloat() - 0.5f));
        mLayout.setBackground(mStarfield);

        final DisplayMetrics dm = getResources().getDisplayMetrics();
        final float dp = dm.density;
        final int minSide = Math.min(dm.widthPixels, dm.heightPixels);
        final int widgetSize = (int) (minSide * 0.75);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(widgetSize, widgetSize);
        lp.gravity = Gravity.CENTER;

        mLogo = new ImageView(this);
        mLogo.setImageResource(R.drawable.platlogo);
        mLogo.setOnTouchListener(mTouchListener);
        mLogo.requestFocus();
        mLayout.addView(mLogo, lp);

        Log.v(TAG, "Hello");

        setContentView(mLayout);
    }

    private void startAnimating() {
        mAnim = new TimeAnimator();
        mAnim.setTimeListener(mTimeListener);
        mAnim.start();
    }

    private void stopAnimating() {
        mAnim.cancel();
        mAnim = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            if (event.getRepeatCount() == 0) {
                startWarp();
            }
            return true;
        }
        return super.onKeyDown(keyCode,event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            stopWarp();
            return true;
        }
        return super.onKeyUp(keyCode,event);
    }

    private void startWarp() {
        stopWarp();
        mWarpAnim = ObjectAnimator.ofFloat(mStarfield, "warp", MIN_WARP, MAX_WARP)
                .setDuration(LAUNCH_TIME);
        mWarpAnim.start();

        mLogo.postDelayed(mLaunchNextStage, LAUNCH_TIME + 1000L);
    }

    private void stopWarp() {
        if (mWarpAnim != null) {
            mWarpAnim.cancel();
            mWarpAnim.removeAllListeners();
            mWarpAnim = null;
        }
        mStarfield.setWarp(1f);
        mLogo.removeCallbacks(mLaunchNextStage);
    }

    @Override
    public void onResume() {
        super.onResume();
        startAnimating();
    }

    @Override
    public void onPause() {
        stopWarp();
        stopAnimating();
        super.onPause();
    }

    private boolean shouldWriteSettings() {
        return getPackageName().equals("android");
    }

    private void launchNextStage(boolean locked) {
        final ContentResolver cr = getContentResolver();
        try {
            if (shouldWriteSettings()) {
                Log.v(TAG, "Saving egg locked=" + locked);
                syncTouchPressure();
                Settings.System.putLong(cr,
                        EGG_UNLOCK_SETTING,
                        locked ? 0 : System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't write settings", e);
        }

        try {
            final Intent eggActivity = new Intent(Intent.ACTION_MAIN)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .addCategory("com.android.internal.category.PLATLOGO");
            Log.v(TAG, "launching: " + eggActivity);
            startActivity(eggActivity);
        } catch (ActivityNotFoundException ex) {
            Log.e("com.android.internal.app.PlatLogoActivity", "No more eggs.");
        }
        if (FINISH_AFTER_NEXT_STAGE_LAUNCH) {
            finish(); // we're done here.
        }
    }

    static final String TOUCH_STATS = "touch.stats";
    double mPressureMin = 0, mPressureMax = -1;

    private void measureTouchPressure(MotionEvent event) {
        final float pressure = event.getPressure();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mPressureMax < 0) {
                    mPressureMin = mPressureMax = pressure;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (pressure < mPressureMin) mPressureMin = pressure;
                if (pressure > mPressureMax) mPressureMax = pressure;
                break;
        }
    }

    private void syncTouchPressure() {
        try {
            final String touchDataJson = Settings.System.getString(
                    getContentResolver(), TOUCH_STATS);
            final JSONObject touchData = new JSONObject(
                    touchDataJson != null ? touchDataJson : "{}");
            if (touchData.has("min")) {
                mPressureMin = Math.min(mPressureMin, touchData.getDouble("min"));
            }
            if (touchData.has("max")) {
                mPressureMax = Math.max(mPressureMax, touchData.getDouble("max"));
            }
            if (mPressureMax >= 0) {
                touchData.put("min", mPressureMin);
                touchData.put("max", mPressureMax);
                if (shouldWriteSettings()) {
                    Settings.System.putString(getContentResolver(), TOUCH_STATS,
                            touchData.toString());
                }
            }
        } catch (Exception e) {
            Log.e("com.android.internal.app.PlatLogoActivity", "Can't write touch settings", e);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        syncTouchPressure();
    }

    @Override
    public void onStop() {
        syncTouchPressure();
        super.onStop();
    }

    private static class Starfield extends Drawable {
        private static final int NUM_STARS = 34; // Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        private static final int NUM_PLANES = 2;
        private final float[] mStars = new float[NUM_STARS * 4];
        private float mVx, mVy;
        private long mDt = 0;
        private final Paint mStarPaint;

        private final Random mRng;
        private final float mSize;

        private final Rect mSpace = new Rect();
        private float mWarp = 1f;

        private float mBuffer;

        public void setWarp(float warp) {
            mWarp = warp;
        }

        public float getWarp() {
            return mWarp;
        }

        Starfield(Random rng, float size) {
            mRng = rng;
            mSize = size;
            mStarPaint = new Paint();
            mStarPaint.setStyle(Paint.Style.STROKE);
            mStarPaint.setColor(Color.WHITE);
        }

        @Override
        public void onBoundsChange(Rect bounds) {
            mSpace.set(bounds);
            mBuffer = mSize * NUM_PLANES * 2 * MAX_WARP;
            mSpace.inset(-(int) mBuffer, -(int) mBuffer);
            final float w = mSpace.width();
            final float h = mSpace.height();
            for (int i = 0; i < NUM_STARS; i++) {
                mStars[4 * i] = mRng.nextFloat() * w;
                mStars[4 * i + 1] = mRng.nextFloat() * h;
                mStars[4 * i + 2] = mStars[4 * i];
                mStars[4 * i + 3] = mStars[4 * i + 1];
            }
        }

        public void setVelocity(float x, float y) {
            mVx = x;
            mVy = y;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final float dtSec = mDt / 1000f;
            final float dx = (mVx * dtSec * mWarp);
            final float dy = (mVy * dtSec * mWarp);

            final boolean inWarp = mWarp > 1f;

            canvas.drawColor(Color.BLACK); // 0xFF16161D);

            if (mDt > 0 && mDt < 1000) {
                canvas.translate(
                        -(mBuffer) + mRng.nextFloat() * (mWarp - 1f),
                        -(mBuffer) + mRng.nextFloat() * (mWarp - 1f)
                );
                final float w = mSpace.width();
                final float h = mSpace.height();
                for (int i = 0; i < NUM_STARS; i++) {
                    final int plane = (int) ((((float) i) / NUM_STARS) * NUM_PLANES) + 1;
                    mStars[4 * i + 2] = (mStars[4 * i + 2] + dx * plane + w) % w;
                    mStars[4 * i + 3] = (mStars[4 * i + 3] + dy * plane + h) % h;
                    mStars[4 * i + 0] = inWarp ? mStars[4 * i + 2] - dx * mWarp * 2 * plane : -100;
                    mStars[4 * i + 1] = inWarp ? mStars[4 * i + 3] - dy * mWarp * 2 * plane : -100;
                }
            }
            final int slice = (mStars.length / NUM_PLANES / 4) * 4;
            for (int p = 0; p < NUM_PLANES; p++) {
                mStarPaint.setStrokeWidth(mSize * (p + 1));
                if (inWarp) {
                    canvas.drawLines(mStars, p * slice, slice, mStarPaint);
                }
                canvas.drawPoints(mStars, p * slice, slice, mStarPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {

        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {

        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }

        public void update(long dt) {
            mDt = dt;
        }
    }
}
