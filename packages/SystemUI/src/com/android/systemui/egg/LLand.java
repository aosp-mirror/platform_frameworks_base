/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.egg;

import android.animation.TimeAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;

import com.android.systemui.R;

public class LLand extends FrameLayout {
    public static final String TAG = "LLand";

    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean DEBUG_DRAW = false && DEBUG;

    public static final void L(String s, Object ... objects) {
        if (DEBUG) {
            Log.d(TAG, String.format(s, objects));
        }
    }

    public static final boolean AUTOSTART = true;
    public static final boolean HAVE_STARS = true;

    public static final float DEBUG_SPEED_MULTIPLIER = 1f; // 0.1f;
    public static final boolean DEBUG_IDDQD = false;

    private static class Params {
        public float TRANSLATION_PER_SEC;
        public int OBSTACLE_SPACING, OBSTACLE_PERIOD;
        public int BOOST_DV;
        public int PLAYER_HIT_SIZE;
        public int PLAYER_SIZE;
        public int OBSTACLE_WIDTH;
        public int OBSTACLE_GAP;
        public int OBSTACLE_MIN;
        public int BUILDING_WIDTH_MIN, BUILDING_WIDTH_MAX;
        public int BUILDING_HEIGHT_MIN;
        public int CLOUD_SIZE_MIN, CLOUD_SIZE_MAX;
        public int STAR_SIZE_MIN, STAR_SIZE_MAX;
        public int G;
        public int MAX_V;
            public float SCENERY_Z, OBSTACLE_Z, PLAYER_Z, PLAYER_Z_BOOST, HUD_Z;
        public Params(Resources res) {
            TRANSLATION_PER_SEC = res.getDimension(R.dimen.translation_per_sec);
            OBSTACLE_SPACING = res.getDimensionPixelSize(R.dimen.obstacle_spacing);
            OBSTACLE_PERIOD = (int) (OBSTACLE_SPACING / TRANSLATION_PER_SEC);
            BOOST_DV = res.getDimensionPixelSize(R.dimen.boost_dv);
            PLAYER_HIT_SIZE = res.getDimensionPixelSize(R.dimen.player_hit_size);
            PLAYER_SIZE = res.getDimensionPixelSize(R.dimen.player_size);
            OBSTACLE_WIDTH = res.getDimensionPixelSize(R.dimen.obstacle_width);
            OBSTACLE_GAP = res.getDimensionPixelSize(R.dimen.obstacle_gap);
            OBSTACLE_MIN = res.getDimensionPixelSize(R.dimen.obstacle_height_min);
            BUILDING_HEIGHT_MIN = res.getDimensionPixelSize(R.dimen.building_height_min);
            BUILDING_WIDTH_MIN = res.getDimensionPixelSize(R.dimen.building_width_min);
            BUILDING_WIDTH_MAX = res.getDimensionPixelSize(R.dimen.building_width_max);
            CLOUD_SIZE_MIN = res.getDimensionPixelSize(R.dimen.cloud_size_min);
            CLOUD_SIZE_MAX = res.getDimensionPixelSize(R.dimen.cloud_size_max);
            STAR_SIZE_MIN = res.getDimensionPixelSize(R.dimen.star_size_min);
            STAR_SIZE_MAX = res.getDimensionPixelSize(R.dimen.star_size_max);

            G = res.getDimensionPixelSize(R.dimen.G);
            MAX_V = res.getDimensionPixelSize(R.dimen.max_v);

            SCENERY_Z = res.getDimensionPixelSize(R.dimen.scenery_z);
            OBSTACLE_Z = res.getDimensionPixelSize(R.dimen.obstacle_z);
            PLAYER_Z = res.getDimensionPixelSize(R.dimen.player_z);
            PLAYER_Z_BOOST = res.getDimensionPixelSize(R.dimen.player_z_boost);
            HUD_Z = res.getDimensionPixelSize(R.dimen.hud_z);
        }
    }

    private TimeAnimator mAnim;

    private TextView mScoreField;
    private View mSplash;

    private Player mDroid;
    private ArrayList<Obstacle> mObstaclesInPlay = new ArrayList<Obstacle>();

    private float t, dt;

    private int mScore;
    private float mLastPipeTime; // in sec
    private int mWidth, mHeight;
    private boolean mAnimating, mPlaying;
    private boolean mFrozen; // after death, a short backoff

    private int mTimeOfDay;
    private static final int DAY = 0, NIGHT = 1, TWILIGHT = 2, SUNSET = 3;
    private static final int[][] SKIES = {
            { 0xFFc0c0FF, 0xFFa0a0FF }, // DAY
            { 0xFF000010, 0xFF000000 }, // NIGHT
            { 0xFF000040, 0xFF000010 }, // TWILIGHT
            { 0xFF805010, 0xFF202080 }, // SUNSET
    };

    private static Params PARAMS;

    public LLand(Context context) {
        this(context, null);
    }

    public LLand(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LLand(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setFocusable(true);
        PARAMS = new Params(getResources());
        mTimeOfDay = irand(0, SKIES.length);
    }

    @Override
    public boolean willNotDraw() {
        return !DEBUG;
    }

    public int getGameWidth() { return mWidth; }
    public int getGameHeight() { return mHeight; }
    public float getGameTime() { return t; }
    public float getLastTimeStep() { return dt; }

    public void setScoreField(TextView tv) {
        mScoreField = tv;
        if (tv != null) {
            tv.setTranslationZ(PARAMS.HUD_Z);
            if (!(mAnimating && mPlaying)) {
                tv.setTranslationY(-500);
            }
        }
    }

    public void setSplash(View v) {
        mSplash = v;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        stop();
        reset();
        if (AUTOSTART) {
            start(false);
        }
    }

    final float hsv[] = {0, 0, 0};

    private void reset() {
        L("reset");
        final Drawable sky = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                SKIES[mTimeOfDay]
        );
        sky.setDither(true);
        setBackground(sky);

        setScaleX(frand() > 0.5f ? 1 : -1);

        setScore(0);

        int i = getChildCount();
        while (i-->0) {
            final View v = getChildAt(i);
            if (v instanceof GameView) {
                removeViewAt(i);
            }
        }

        mObstaclesInPlay.clear();

        mWidth = getWidth();
        mHeight = getHeight();

        boolean showingSun = (mTimeOfDay == DAY || mTimeOfDay == SUNSET) && frand() > 0.25;
        if (showingSun) {
            final Star sun = new Star(getContext());
            sun.setBackgroundResource(R.drawable.sun);
            final int w = getResources().getDimensionPixelSize(R.dimen.sun_size);
            sun.setTranslationX(frand(w, mWidth-w));
            if (mTimeOfDay == DAY) {
                sun.setTranslationY(frand(w, (mHeight * 0.66f)));
                sun.getBackground().setTint(0);
            } else {
                sun.setTranslationY(frand(mHeight * 0.66f, mHeight - w));
                sun.getBackground().setTintMode(PorterDuff.Mode.SRC_ATOP);
                sun.getBackground().setTint(0xC0FF8000);

            }
            addView(sun, new LayoutParams(w, w));
        }
        if (!showingSun) {
            final boolean dark = mTimeOfDay == NIGHT || mTimeOfDay == TWILIGHT;
            final float ff = frand();
            if ((dark && ff < 0.75f) || ff < 0.5f) {
                final Star moon = new Star(getContext());
                moon.setBackgroundResource(R.drawable.moon);
                moon.getBackground().setAlpha(dark ? 255 : 128);
                moon.setScaleX(frand() > 0.5 ? -1 : 1);
                moon.setRotation(moon.getScaleX() * frand(5, 30));
                final int w = getResources().getDimensionPixelSize(R.dimen.sun_size);
                moon.setTranslationX(frand(w, mWidth - w));
                moon.setTranslationY(frand(w, mHeight - w));
                addView(moon, new LayoutParams(w, w));
            }
        }

        final int mh = mHeight / 6;
        final boolean cloudless = frand() < 0.25;
        final int N = 20;
        for (i=0; i<N; i++) {
            final float r1 = frand();
            final Scenery s;
            if (HAVE_STARS && r1 < 0.3 && mTimeOfDay != DAY) {
                s = new Star(getContext());
            } else if (r1 < 0.6 && !cloudless) {
                s = new Cloud(getContext());
            } else {
                s = new Building(getContext());

                s.z = (float)i/N;
                s.setTranslationZ(PARAMS.SCENERY_Z * (1+s.z));
                s.v = 0.85f * s.z; // buildings move proportional to their distance
                hsv[0] = 175;
                hsv[1] = 0.25f;
                hsv[2] = 1 * s.z;
                s.setBackgroundColor(Color.HSVToColor(hsv));
                s.h = irand(PARAMS.BUILDING_HEIGHT_MIN, mh);
            }
            final LayoutParams lp = new LayoutParams(s.w, s.h);
            if (s instanceof Building) {
                lp.gravity = Gravity.BOTTOM;
            } else {
                lp.gravity = Gravity.TOP;
                final float r = frand();
                if (s instanceof Star) {
                    lp.topMargin = (int) (r * r * mHeight);
                } else {
                    lp.topMargin = (int) (1 - r*r * mHeight/2) + mHeight/2;
                }
            }

            addView(s, lp);
            s.setTranslationX(frand(-lp.width, mWidth + lp.width));
        }

        mDroid = new Player(getContext());
        mDroid.setX(mWidth / 2);
        mDroid.setY(mHeight / 2);
        addView(mDroid, new LayoutParams(PARAMS.PLAYER_SIZE, PARAMS.PLAYER_SIZE));
        if (mAnim != null) {
            Log.wtf(TAG, "reseting while animating??!?");
        }
        mAnim = new TimeAnimator();
        mAnim.setTimeListener(new TimeAnimator.TimeListener() {
            @Override
            public void onTimeUpdate(TimeAnimator timeAnimator, long t, long dt) {
                step(t, dt);
            }
        });
    }

    private void setScore(int score) {
        mScore = score;
        if (mScoreField != null) mScoreField.setText(String.valueOf(score));
    }

    private void addScore(int incr) {
        setScore(mScore + incr);
    }

    private void start(boolean startPlaying) {
        L("start(startPlaying=%s)", startPlaying?"true":"false");
        if (startPlaying) {
            mPlaying = true;

            t = 0;
            mLastPipeTime = getGameTime() - PARAMS.OBSTACLE_PERIOD; // queue up a obstacle

            if (mSplash != null && mSplash.getAlpha() > 0f) {
                mSplash.setTranslationZ(PARAMS.HUD_Z);
                mSplash.animate().alpha(0).translationZ(0).setDuration(400);

                mScoreField.animate().translationY(0)
                        .setInterpolator(new DecelerateInterpolator())
                        .setDuration(1500);
            }

            mScoreField.setTextColor(0xFFAAAAAA);
            mScoreField.setBackgroundResource(R.drawable.scorecard);
            mDroid.setVisibility(View.VISIBLE);
            mDroid.setX(mWidth / 2);
            mDroid.setY(mHeight / 2);
        } else {
            mDroid.setVisibility(View.GONE);
        }
        if (!mAnimating) {
            mAnim.start();
            mAnimating = true;
        }
    }

    private void stop() {
        if (mAnimating) {
            mAnim.cancel();
            mAnim = null;
            mAnimating = false;
            mScoreField.setTextColor(0xFFFFFFFF);
            mScoreField.setBackgroundResource(R.drawable.scorecard_gameover);
            mTimeOfDay = irand(0, SKIES.length); // for next reset
            mFrozen = true;
            postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mFrozen = false;
                    }
                }, 250);
        }
    }

    public static final float lerp(float x, float a, float b) {
        return (b - a) * x + a;
    }

    public static final float rlerp(float v, float a, float b) {
        return (v - a) / (b - a);
    }

    public static final float clamp(float f) {
        return f < 0f ? 0f : f > 1f ? 1f : f;
    }

    public static final float frand() {
        return (float) Math.random();
    }

    public static final float frand(float a, float b) {
        return lerp(frand(), a, b);
    }

    public static final int irand(int a, int b) {
        return (int) lerp(frand(), (float) a, (float) b);
    }

    private void step(long t_ms, long dt_ms) {
        t = t_ms / 1000f; // seconds
        dt = dt_ms / 1000f;

        if (DEBUG) {
            t *= DEBUG_SPEED_MULTIPLIER;
            dt *= DEBUG_SPEED_MULTIPLIER;
        }

        // 1. Move all objects and update bounds
        final int N = getChildCount();
        int i = 0;
        for (; i<N; i++) {
            final View v = getChildAt(i);
            if (v instanceof GameView) {
                ((GameView) v).step(t_ms, dt_ms, t, dt);
            }
        }

        // 2. Check for altitude
        if (mPlaying && mDroid.below(mHeight)) {
            if (DEBUG_IDDQD) {
                poke();
            } else {
                L("player hit the floor");
                stop();
            }
        }

        // 3. Check for obstacles
        boolean passedBarrier = false;
        for (int j = mObstaclesInPlay.size(); j-->0;) {
            final Obstacle ob = mObstaclesInPlay.get(j);
            if (mPlaying && ob.intersects(mDroid) && !DEBUG_IDDQD) {
                L("player hit an obstacle");
                stop();
            } else if (ob.cleared(mDroid)) {
                passedBarrier = true;
                mObstaclesInPlay.remove(j);
            }
        }

        if (mPlaying && passedBarrier) {
            addScore(1);
        }

        // 4. Handle edge of screen
        // Walk backwards to make sure removal is safe
        while (i-->0) {
            final View v = getChildAt(i);
            if (v instanceof Obstacle) {
                if (v.getTranslationX() + v.getWidth() < 0) {
                    removeViewAt(i);
                }
            } else if (v instanceof Scenery) {
                final Scenery s = (Scenery) v;
                if (v.getTranslationX() + s.w < 0) {
                    v.setTranslationX(getWidth());
                }
            }
        }

        // 3. Time for more obstacles!
        if (mPlaying && (t - mLastPipeTime) > PARAMS.OBSTACLE_PERIOD) {
            mLastPipeTime = t;
            final int obstacley = (int) (Math.random()
                    * (mHeight - 2*PARAMS.OBSTACLE_MIN - PARAMS.OBSTACLE_GAP)) + PARAMS.OBSTACLE_MIN;

            final Obstacle p1 = new Obstacle(getContext(), obstacley);
            addView(p1, new LayoutParams(
                    PARAMS.OBSTACLE_WIDTH,
                    mHeight,
                    Gravity.TOP|Gravity.LEFT));
            p1.setTranslationX(mWidth);
            p1.setTranslationY(-mHeight);
            p1.setTranslationZ(0);
            p1.animate()
                    .translationY(-mHeight+p1.h)
                    .translationZ(PARAMS.OBSTACLE_Z)
                    .setStartDelay(irand(0,250))
                    .setDuration(250);
            mObstaclesInPlay.add(p1);

            final Obstacle p2 = new Obstacle(getContext(),
                    mHeight - obstacley - PARAMS.OBSTACLE_GAP);
            addView(p2, new LayoutParams(
                    PARAMS.OBSTACLE_WIDTH,
                    mHeight,
                    Gravity.TOP|Gravity.LEFT));
            p2.setTranslationX(mWidth);
            p2.setTranslationY(mHeight);
            p2.setTranslationZ(0);
            p2.animate()
                    .translationY(mHeight-p2.h)
                    .translationZ(PARAMS.OBSTACLE_Z)
                    .setStartDelay(irand(0,100))
                    .setDuration(400);
            mObstaclesInPlay.add(p2);
        }

        if (DEBUG) {
            final Rect r = new Rect();
            mDroid.getHitRect(r);
            r.inset(-4, -4);
            invalidate(r);
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (DEBUG) L("touch: %s", ev);
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            poke();
            return true;
        }
        return false;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        if (DEBUG) L("trackball: %s", ev);
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            poke();
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (DEBUG) L("keyDown: %d", keyCode);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                poke();
                return true;
        }
        return false;
    }

    @Override
    public boolean onGenericMotionEvent (MotionEvent ev) {
        if (DEBUG) L("generic: %s", ev);
        return false;
    }

    private void poke() {
        L("poke");
        if (mFrozen) return;
        if (!mAnimating) {
            reset();
            start(true);
        } else if (!mPlaying) {
            start(true);
        }
        mDroid.boost();
        if (DEBUG) {
            mDroid.dv *= DEBUG_SPEED_MULTIPLIER;
            mDroid.animate().setDuration((long) (200/DEBUG_SPEED_MULTIPLIER));
        }
    }

    @Override
    public void onDraw(Canvas c) {
        super.onDraw(c);

        if (!DEBUG_DRAW) return;

        final Paint pt = new Paint();
        pt.setColor(0xFFFFFFFF);
        final int L = mDroid.corners.length;
        final int N = L/2;
        for (int i=0; i<N; i++) {
            final int x = (int) mDroid.corners[i*2];
            final int y = (int) mDroid.corners[i*2+1];
            c.drawCircle(x, y, 4, pt);
            c.drawLine(x, y,
                    mDroid.corners[(i*2+2)%L],
                    mDroid.corners[(i*2+3)%L],
                    pt);
        }

        final int M = getChildCount();
        pt.setColor(0x6000FF00);
        for (int i=0; i<M; i++) {
            final View v = getChildAt(i);
            if (v == mDroid) continue;
            if (!(v instanceof GameView)) continue;
            final Rect r = new Rect();
            v.getHitRect(r);
            c.drawRect(r, pt);
        }

        pt.setColor(Color.BLACK);
        final StringBuilder sb = new StringBuilder("obstacles: ");
        for (Obstacle ob : mObstaclesInPlay) {
            sb.append(ob.hitRect.toShortString());
            sb.append(" ");
        }
        pt.setTextSize(20f);
        c.drawText(sb.toString(), 20, 100, pt);
    }

    static final Rect sTmpRect = new Rect();

    private interface GameView {
        public void step(long t_ms, long dt_ms, float t, float dt);
    }

    private class Player extends ImageView implements GameView {
        public float dv;

        private final float[] sHull = new float[] {
                0.3f,  0f,    // left antenna
                0.7f,  0f,    // right antenna
                0.92f, 0.33f, // off the right shoulder of Orion
                0.92f, 0.75f, // right hand (our right, not his right)
                0.6f,  1f,    // right foot
                0.4f,  1f,    // left foot BLUE!
                0.08f, 0.75f, // sinistram
                0.08f, 0.33f,  // cold shoulder
        };
        public final float[] corners = new float[sHull.length];

        public Player(Context context) {
            super(context);

            setBackgroundResource(R.drawable.android);
            getBackground().setTintMode(PorterDuff.Mode.SRC_ATOP);
            getBackground().setTint(0xFF00FF00);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    final int w = view.getWidth();
                    final int h = view.getHeight();
                    final int ix = (int) (w * 0.3f);
                    final int iy = (int) (h * 0.2f);
                    outline.setRect(ix, iy, w - ix, h - iy);
                }
            });
        }

        public void prepareCheckIntersections() {
            final int inset = (PARAMS.PLAYER_SIZE - PARAMS.PLAYER_HIT_SIZE)/2;
            final int scale = PARAMS.PLAYER_HIT_SIZE;
            final int N = sHull.length/2;
            for (int i=0; i<N; i++) {
                corners[i*2]   = scale * sHull[i*2]   + inset;
                corners[i*2+1] = scale * sHull[i*2+1] + inset;
            }
            final Matrix m = getMatrix();
            m.mapPoints(corners);
        }

        public boolean below(int h) {
            final int N = corners.length/2;
            for (int i=0; i<N; i++) {
                final int y = (int) corners[i*2+1];
                if (y >= h) return true;
            }
            return false;
        }

        public void step(long t_ms, long dt_ms, float t, float dt) {
            if (getVisibility() != View.VISIBLE) return; // not playing yet

            dv += PARAMS.G;
            if (dv < -PARAMS.MAX_V) dv = -PARAMS.MAX_V;
            else if (dv > PARAMS.MAX_V) dv = PARAMS.MAX_V;

            final float y = getTranslationY() + dv * dt;
            setTranslationY(y < 0 ? 0 : y);
            setRotation(
                    90 + lerp(clamp(rlerp(dv, PARAMS.MAX_V, -1 * PARAMS.MAX_V)), 90, -90));

            prepareCheckIntersections();
        }

        public void boost() {
            dv = -PARAMS.BOOST_DV;
            setTranslationZ(PARAMS.PLAYER_Z_BOOST);
            setScaleX(1.25f);
            setScaleY(1.25f);
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationZ(PARAMS.PLAYER_Z)
                .setDuration(200);
        }
    }

    private class Obstacle extends View implements GameView {
        public float h;

        public final Rect hitRect = new Rect();

        public Obstacle(Context context, float h) {
            super(context);
            setBackgroundResource(R.drawable.placeholder);
            this.h = h;
        }

        public boolean intersects(Player p) {
            final int N = p.corners.length/2;
            for (int i=0; i<N; i++) {
                final int x = (int) p.corners[i*2];
                final int y = (int) p.corners[i*2+1];
                if (hitRect.contains(x, y)) return true;
            }
            return false;
        }

        public boolean cleared(Player p) {
            final int N = p.corners.length/2;
            for (int i=0; i<N; i++) {
                final int x = (int) p.corners[i*2];
                if (hitRect.right >= x) return false;
            }
            return true;
        }

        @Override
        public void step(long t_ms, long dt_ms, float t, float dt) {
            setTranslationX(getTranslationX()-PARAMS.TRANSLATION_PER_SEC*dt);
            getHitRect(hitRect);
        }
    }

    private class Scenery extends FrameLayout implements GameView {
        public float z;
        public float v;
        public int h, w;
        public Scenery(Context context) {
            super(context);
        }

        @Override
        public void step(long t_ms, long dt_ms, float t, float dt) {
            setTranslationX(getTranslationX() - PARAMS.TRANSLATION_PER_SEC * dt * v);
        }
    }

    private class Building extends Scenery {
        public Building(Context context) {
            super(context);

            w = irand(PARAMS.BUILDING_WIDTH_MIN, PARAMS.BUILDING_WIDTH_MAX);
            h = 0; // will be setup later, along with z

            setTranslationZ(PARAMS.SCENERY_Z);
        }
    }

    private class Cloud extends Scenery {
        public Cloud(Context context) {
            super(context);
            setBackgroundResource(frand() < 0.01f ? R.drawable.cloud_off : R.drawable.cloud);
            getBackground().setAlpha(0x40);
            w = h = irand(PARAMS.CLOUD_SIZE_MIN, PARAMS.CLOUD_SIZE_MAX);
            z = 0;
            v = frand(0.15f,0.5f);
        }
    }

    private class Star extends Scenery {
        public Star(Context context) {
            super(context);
            setBackgroundResource(R.drawable.star);
            w = h = irand(PARAMS.STAR_SIZE_MIN, PARAMS.STAR_SIZE_MAX);
            v = z = 0;
        }
    }
}
