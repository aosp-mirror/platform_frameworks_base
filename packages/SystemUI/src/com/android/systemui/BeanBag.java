/*);
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

package com.android.systemui;

import android.animation.AnimatorSet;
import android.animation.PropertyValuesHolder;
import android.animation.ObjectAnimator;
import android.animation.TimeAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.HashMap;
import java.util.Random;

public class BeanBag extends Activity {
    final static boolean DEBUG = false;

    public static class Board extends FrameLayout
    {
        static Random sRNG = new Random();

        static float lerp(float a, float b, float f) {
            return (b-a)*f + a;
        }

        static float randfrange(float a, float b) {
            return lerp(a, b, sRNG.nextFloat());
        }

        static int randsign() {
            return sRNG.nextBoolean() ? 1 : -1;
        }

        static boolean flip() {
            return sRNG.nextBoolean();
        }

        static float mag(float x, float y) {
            return (float) Math.sqrt(x*x+y*y);
        }

        static float clamp(float x, float a, float b) {
            return ((x<a)?a:((x>b)?b:x));
        }

        static float dot(float x1, float y1, float x2, float y2) {
            return x1*x2+y1+y2;
        }

        static <E> E pick(E[] array) {
            if (array.length == 0) return null;
            return array[sRNG.nextInt(array.length)];
        }

        static int pickInt(int[] array) {
            if (array.length == 0) return 0;
            return array[sRNG.nextInt(array.length)];
        }

        static int NUM_BEANS = 40;
        static float MIN_SCALE = 0.2f;
        static float MAX_SCALE = 1f;

        static float LUCKY = 0.001f;

        static int MAX_RADIUS = (int)(576 * MAX_SCALE);

        static int BEANS[] = {
          R.drawable.redbean0,
          R.drawable.redbean0,
          R.drawable.redbean0,
          R.drawable.redbean0,
          R.drawable.redbean1,
          R.drawable.redbean1,
          R.drawable.redbean2,
          R.drawable.redbean2,
          R.drawable.redbeandroid,
        };

        static int COLORS[] = {
            0xFF00CC00,
            0xFFCC0000,
            0xFF0000CC,
            0xFFFFFF00,
            0xFFFF8000,
            0xFF00CCFF,
            0xFFFF0080,
            0xFF8000FF,
            0xFFFF8080,
            0xFF8080FF,
            0xFFB0C0D0,
            0xFFDDDDDD,
            0xFF333333,
        };

        public class Bean extends ImageView {
            public static final float VMAX = 1000.0f;
            public static final float VMIN = 100.0f;

            public float x, y, a;

            public float va;
            public float vx, vy;

            public float r;

            public float z;

            public int h,w;

            public boolean grabbed;
            public float grabx, graby;
            public long grabtime;
            private float grabx_offset, graby_offset;

            public Bean(Context context, AttributeSet as) {
                super(context, as);
            }

            public String toString() {
                return String.format("<bean (%.1f, %.1f) (%d x %d)>",
                    getX(), getY(), getWidth(), getHeight());
            }

            private void pickBean() {
                int beanId = pickInt(BEANS);
                if (randfrange(0,1) <= LUCKY) {
                    beanId = R.drawable.jandycane;
                }
                BitmapDrawable bean = (BitmapDrawable) getContext().getResources().getDrawable(beanId);
                Bitmap beanBits = bean.getBitmap();
                h=beanBits.getHeight();
                w=beanBits.getWidth();

                if (DEBUG) {
                    bean.setAlpha(0x80);
                }
                this.setImageDrawable(bean);

                Paint pt = new Paint();
                final int color = pickInt(COLORS);
                ColorMatrix CM = new ColorMatrix();
                float[] M = CM.getArray();
                // we assume the color information is in the red channel
                /* R */ M[0]  = (float)((color & 0x00FF0000) >> 16) / 0xFF;
                /* G */ M[5]  = (float)((color & 0x0000FF00) >> 8)  / 0xFF;
                /* B */ M[10] = (float)((color & 0x000000FF))       / 0xFF;
                pt.setColorFilter(new ColorMatrixColorFilter(M));
                setLayerType(View.LAYER_TYPE_HARDWARE, (beanId == R.drawable.jandycane) ? null : pt);
            }

            public void reset() {
                pickBean();

                final float scale = lerp(MIN_SCALE,MAX_SCALE,z);
                setScaleX(scale); setScaleY(scale);

                r = 0.3f*Math.max(h,w)*scale;

                a=(randfrange(0,360));
                va = randfrange(-30,30);

                vx = randfrange(-40,40) * z;
                vy = randfrange(-40,40) * z;
                final float boardh = boardHeight;
                final float boardw = boardWidth;
                //android.util.Log.d("BeanBag", "reset: w="+w+" h="+h);
                if (flip()) {
                    x=(vx < 0 ? boardw+2*r : -r*4f);
                    y=(randfrange(0, boardh-3*r)*0.5f + ((vy < 0)?boardh*0.5f:0));
                } else {
                    y=(vy < 0 ? boardh+2*r : -r*4f);
                    x=(randfrange(0, boardw-3*r)*0.5f + ((vx < 0)?boardw*0.5f:0));
                }
            }

            public void update(float dt) {
                if (grabbed) {
//                    final float interval = (SystemClock.uptimeMillis() - grabtime) / 1000f;
                    vx = (vx * 0.75f) + ((grabx - x) / dt) * 0.25f;
                    x = grabx;
                    vy = (vy * 0.75f) + ((graby - y) / dt) * 0.25f;;
                    y = graby;
                } else {
                    x = (x + vx * dt);
                    y = (y + vy * dt);
                    a = (a + va * dt);
                }
            }

            public float overlap(Bean other) {
                final float dx = (x - other.x);
                final float dy = (y - other.y);
                return mag(dx, dy) - r - other.r;
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        grabbed = true;
                        grabx_offset = e.getRawX() - x;
                        graby_offset = e.getRawY() - y;
                        va = 0;
                        // fall
                    case MotionEvent.ACTION_MOVE:
                        grabx = e.getRawX() - grabx_offset;
                        graby = e.getRawY() - graby_offset;
                        grabtime = e.getEventTime();
                        break;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP:
                        grabbed = false;
                        float a = randsign() * clamp(mag(vx, vy) * 0.33f, 0, 1080f);
                        va = randfrange(a*0.5f, a);
                        break;
                }
                return true;
            }
        }

        TimeAnimator mAnim;
        private int boardWidth;
        private int boardHeight;

        public Board(Context context, AttributeSet as) {
            super(context, as);

            setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);

            setWillNotDraw(!DEBUG);
        }

        private void reset() {
//            android.util.Log.d("Nyandroid", "board reset");
            removeAllViews();

            final ViewGroup.LayoutParams wrap = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);

            for(int i=0; i<NUM_BEANS; i++) {
                Bean nv = new Bean(getContext(), null);
                addView(nv, wrap);
                nv.z = ((float)i/NUM_BEANS);
                nv.z *= nv.z;
                nv.reset();
                nv.x = (randfrange(0, boardWidth));
                nv.y = (randfrange(0, boardHeight));
            }

            if (mAnim != null) {
                mAnim.cancel();
            }
            mAnim = new TimeAnimator();
            mAnim.setTimeListener(new TimeAnimator.TimeListener() {
                private long lastPrint = 0;
                public void onTimeUpdate(TimeAnimator animation, long totalTime, long deltaTime) {
                    if (DEBUG && totalTime - lastPrint > 5000) {
                        lastPrint = totalTime;
                        for (int i=0; i<getChildCount(); i++) {
                            android.util.Log.d("BeanBag", "bean " + i + ": " + getChildAt(i));
                        }
                    }

                    for (int i=0; i<getChildCount(); i++) {
                        View v = getChildAt(i);
                        if (!(v instanceof Bean)) continue;
                        Bean nv = (Bean) v;
                        nv.update(deltaTime / 1000f);

                        for (int j=i+1; j<getChildCount(); j++) {
                            View v2 = getChildAt(j);
                            if (!(v2 instanceof Bean)) continue;
                            Bean nv2 = (Bean) v2;
                            final float overlap = nv.overlap(nv2);
                        }

                        nv.setRotation(nv.a);
                        nv.setX(nv.x-nv.getPivotX());
                        nv.setY(nv.y-nv.getPivotY());

                        if (   nv.x < - MAX_RADIUS
                            || nv.x > boardWidth + MAX_RADIUS
                            || nv.y < -MAX_RADIUS
                            || nv.y > boardHeight + MAX_RADIUS)
                        {
                            nv.reset();
                        }
                    }

                    if (DEBUG) invalidate();
                }
            });
        }

        @Override
        protected void onSizeChanged (int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w,h,oldw,oldh);
            boardWidth = w;
            boardHeight = h;
//            android.util.Log.d("Nyandroid", "resized: " + w + "x" + h);
        }

        public void startAnimation() {
            stopAnimation();
            if (mAnim == null) {
                post(new Runnable() { public void run() {
                    reset();
                    startAnimation();
                } });
            } else {
                mAnim.start();
            }
        }

        public void stopAnimation() {
            if (mAnim != null) mAnim.cancel();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAnimation();
        }

        @Override
        public boolean isOpaque() {
            return false;
        }

        @Override
        public void onDraw(Canvas c) {
            if (DEBUG) {
                //android.util.Log.d("BeanBag", "onDraw");
                Paint pt = new Paint();
                pt.setAntiAlias(true);
                pt.setStyle(Paint.Style.STROKE);
                pt.setColor(0xFFFF0000);
                pt.setStrokeWidth(4.0f);
                c.drawRect(0, 0, getWidth(), getHeight(), pt);
                pt.setColor(0xFFFFCC00);
                pt.setStrokeWidth(1.0f);
                for (int i=0; i<getChildCount(); i++) {
                    Bean b = (Bean) getChildAt(i);
                    final float a = (360-b.a)/180f*3.14159f;
                    final float tx = b.getTranslationX();
                    final float ty = b.getTranslationY();
                    c.drawCircle(b.x, b.y, b.r, pt);
                    c.drawCircle(tx, ty, 4, pt);
                    c.drawLine(b.x, b.y, (float)(b.x+b.r*Math.sin(a)), (float)(b.y+b.r*Math.cos(a)), pt);
                }
            }
        }
    }

    private Board mBoard;

    @Override
    public void onStart() {
        super.onStart();

        // ACHIEVEMENT UNLOCKED
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(new ComponentName(this, BeanBagDream.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);

        getWindow().addFlags(
                  WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                );
        mBoard = new Board(this, null);
        setContentView(mBoard);
    }

    @Override
    public void onPause() {
        super.onPause();
        mBoard.stopAnimation();
    }

    @Override
    public void onResume() {
        super.onResume();
        mBoard.startAnimation();
    }
}
