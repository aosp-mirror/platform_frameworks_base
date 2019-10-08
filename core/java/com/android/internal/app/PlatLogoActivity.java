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

package com.android.internal.app;

import android.animation.ObjectAnimator;
import android.animation.TimeAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.internal.R;

import org.json.JSONObject;

/**
 * @hide
 */
public class PlatLogoActivity extends Activity {
    ImageView mZeroView, mOneView;
    BackslashDrawable mBackslash;
    int mClicks;

    static final Paint sPaint = new Paint();
    static {
        sPaint.setStyle(Paint.Style.STROKE);
        sPaint.setStrokeWidth(4f);
        sPaint.setStrokeCap(Paint.Cap.SQUARE);
    }

    @Override
    protected void onPause() {
        if (mBackslash != null) {
            mBackslash.stopAnimating();
        }
        mClicks = 0;
        super.onPause();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final float dp = getResources().getDisplayMetrics().density;

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().setNavigationBarColor(0);
        getWindow().setStatusBarColor(0);

        getActionBar().hide();

        setContentView(R.layout.platlogo_layout);

        mBackslash = new BackslashDrawable((int) (50 * dp));

        mOneView = findViewById(R.id.one);
        mOneView.setImageDrawable(new OneDrawable());
        mZeroView = findViewById(R.id.zero);
        mZeroView.setImageDrawable(new ZeroDrawable());

        final ViewGroup root = (ViewGroup) mOneView.getParent();
        root.setClipChildren(false);
        root.setBackground(mBackslash);
        root.getBackground().setAlpha(0x20);

        View.OnTouchListener tl = new View.OnTouchListener() {
            float mOffsetX, mOffsetY;
            long mClickTime;
            ObjectAnimator mRotAnim;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                measureTouchPressure(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(1.1f).scaleY(1.1f);
                        v.getParent().bringChildToFront(v);
                        mOffsetX = event.getRawX() - v.getX();
                        mOffsetY = event.getRawY() - v.getY();
                        long now = System.currentTimeMillis();
                        if (now - mClickTime < 350) {
                            mRotAnim = ObjectAnimator.ofFloat(v, View.ROTATION,
                                    v.getRotation(), v.getRotation() + 3600);
                            mRotAnim.setDuration(10000);
                            mRotAnim.start();
                            mClickTime = 0;
                        } else {
                            mClickTime = now;
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        v.setX(event.getRawX() - mOffsetX);
                        v.setY(event.getRawY() - mOffsetY);
                        v.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE);
                        break;
                    case MotionEvent.ACTION_UP:
                        v.performClick();
                        // fall through
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f);
                        if (mRotAnim != null) mRotAnim.cancel();
                        testOverlap();
                        break;
                }
                return true;
            }
        };

        findViewById(R.id.one).setOnTouchListener(tl);
        findViewById(R.id.zero).setOnTouchListener(tl);
        findViewById(R.id.text).setOnTouchListener(tl);
    }

    private void testOverlap() {
        final float width = mZeroView.getWidth();
        final float targetX = mZeroView.getX() + width * .2f;
        final float targetY = mZeroView.getY() + width * .3f;
        if (Math.hypot(targetX - mOneView.getX(), targetY - mOneView.getY()) < width * .2f
                && Math.abs(mOneView.getRotation() % 360 - 315) < 15) {
            mOneView.animate().x(mZeroView.getX() + width * .2f);
            mOneView.animate().y(mZeroView.getY() + width * .3f);
            mOneView.setRotation(mOneView.getRotation() % 360);
            mOneView.animate().rotation(315);
            mOneView.performHapticFeedback(HapticFeedbackConstants.CONFIRM);

            mBackslash.startAnimating();

            mClicks++;
            if (mClicks >= 7) {
                launchNextStage();
            }
        } else {
            mBackslash.stopAnimating();
        }
    }

    private void launchNextStage() {
        final ContentResolver cr = getContentResolver();

        if (Settings.System.getLong(cr, "egg_mode" /* Settings.System.EGG_MODE */, 0) == 0) {
            // For posterity: the moment this user unlocked the easter egg
            try {
                Settings.System.putLong(cr,
                        "egg_mode", // Settings.System.EGG_MODE,
                        System.currentTimeMillis());
            } catch (RuntimeException e) {
                Log.e("com.android.internal.app.PlatLogoActivity", "Can't write settings", e);
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .addCategory("com.android.internal.category.PLATLOGO"));
        } catch (ActivityNotFoundException ex) {
            Log.e("com.android.internal.app.PlatLogoActivity", "No more eggs.");
        }
        finish();
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
                Settings.System.putString(getContentResolver(), TOUCH_STATS, touchData.toString());
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

    static class ZeroDrawable extends Drawable {
        int mTintColor;

        @Override
        public void draw(Canvas canvas) {
            sPaint.setColor(mTintColor | 0xFF000000);

            canvas.save();
            canvas.scale(canvas.getWidth() / 24f, canvas.getHeight() / 24f);

            canvas.drawCircle(12f, 12f, 10f, sPaint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) { }

        @Override
        public void setColorFilter(ColorFilter colorFilter) { }

        @Override
        public void setTintList(ColorStateList tint) {
            mTintColor = tint.getDefaultColor();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static class OneDrawable extends Drawable {
        int mTintColor;

        @Override
        public void draw(Canvas canvas) {
            sPaint.setColor(mTintColor | 0xFF000000);

            canvas.save();
            canvas.scale(canvas.getWidth() / 24f, canvas.getHeight() / 24f);

            final Path p = new Path();
            p.moveTo(12f, 21.83f);
            p.rLineTo(0f, -19.67f);
            p.rLineTo(-5f, 0f);
            canvas.drawPath(p, sPaint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) { }

        @Override
        public void setColorFilter(ColorFilter colorFilter) { }

        @Override
        public void setTintList(ColorStateList tint) {
            mTintColor = tint.getDefaultColor();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static class BackslashDrawable extends Drawable implements TimeAnimator.TimeListener {
        Bitmap mTile;
        Paint mPaint = new Paint();
        BitmapShader mShader;
        TimeAnimator mAnimator = new TimeAnimator();
        Matrix mMatrix = new Matrix();

        public void draw(Canvas canvas) {
            canvas.drawPaint(mPaint);
        }

        BackslashDrawable(int width) {
            mTile = Bitmap.createBitmap(width, width, Bitmap.Config.ALPHA_8);
            mAnimator.setTimeListener(this);

            final Canvas tileCanvas = new Canvas(mTile);
            final float w = tileCanvas.getWidth();
            final float h = tileCanvas.getHeight();

            final Path path = new Path();
            path.moveTo(0, 0);
            path.lineTo(w / 2, 0);
            path.lineTo(w, h / 2);
            path.lineTo(w, h);
            path.close();

            path.moveTo(0, h / 2);
            path.lineTo(w / 2, h);
            path.lineTo(0, h);
            path.close();

            final Paint slashPaint = new Paint();
            slashPaint.setAntiAlias(true);
            slashPaint.setStyle(Paint.Style.FILL);
            slashPaint.setColor(0xFF000000);
            tileCanvas.drawPath(path, slashPaint);

            //mPaint.setColor(0xFF0000FF);
            mShader = new BitmapShader(mTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            mPaint.setShader(mShader);
        }

        public void startAnimating() {
            if (!mAnimator.isStarted()) {
                mAnimator.start();
            }
        }

        public void stopAnimating() {
            if (mAnimator.isStarted()) {
                mAnimator.cancel();
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void onTimeUpdate(TimeAnimator animation, long totalTime, long deltaTime) {
            if (mShader != null) {
                mMatrix.postTranslate(deltaTime / 4f, 0);
                mShader.setLocalMatrix(mMatrix);
                invalidateSelf();
            }
        }
    }
}

