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

package com.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.HashSet;
import java.util.Set;

public class DessertCaseView extends FrameLayout {
    private static final String TAG = DessertCaseView.class.getSimpleName();

    private static final boolean DEBUG = false;

    static final int START_DELAY = 5000;
    static final int DELAY = 2000;
    static final int DURATION = 500;

    private static final int TAG_POS = 0x2000001;
    private static final int TAG_SPAN = 0x2000002;

    private static final int[] PASTRIES = {
            R.drawable.dessert_kitkat,      // used with permission
            R.drawable.dessert_android,     // thx irina
    };

    private static final int[] RARE_PASTRIES = {
            R.drawable.dessert_cupcake,     // 2009
            R.drawable.dessert_donut,       // 2009
            R.drawable.dessert_eclair,      // 2009
            R.drawable.dessert_froyo,       // 2010
            R.drawable.dessert_gingerbread, // 2010
            R.drawable.dessert_honeycomb,   // 2011
            R.drawable.dessert_ics,         // 2011
            R.drawable.dessert_jellybean,   // 2012
    };

    private static final int[] XRARE_PASTRIES = {
            R.drawable.dessert_petitfour,   // the original and still delicious

            R.drawable.dessert_donutburger, // remember kids, this was long before cronuts

            R.drawable.dessert_flan,        //     sholes final approach
                                            //     landing gear punted to flan
                                            //     runway foam glistens
                                            //         -- mcleron

            R.drawable.dessert_keylimepie,  // from an alternative timeline
    };
    private static final int[] XXRARE_PASTRIES = {
            R.drawable.dessert_zombiegingerbread, // thx hackbod
            R.drawable.dessert_dandroid,    // thx morrildl
            R.drawable.dessert_jandycane,   // thx nes
    };

    private static final int NUM_PASTRIES = PASTRIES.length + RARE_PASTRIES.length
            + XRARE_PASTRIES.length + XXRARE_PASTRIES.length;

    private SparseArray<Drawable> mDrawables = new SparseArray<Drawable>(NUM_PASTRIES);

    private static final float[] MASK = {
            0f,  0f,  0f,  0f, 255f,
            0f,  0f,  0f,  0f, 255f,
            0f,  0f,  0f,  0f, 255f,
            1f,  0f,  0f,  0f, 0f
    };

    private static final float[] ALPHA_MASK = {
            0f,  0f,  0f,  0f, 255f,
            0f,  0f,  0f,  0f, 255f,
            0f,  0f,  0f,  0f, 255f,
            0f,  0f,  0f,  1f, 0f
    };

    private static final float[] WHITE_MASK = {
            0f,  0f,  0f,  0f, 255f,
            0f,  0f,  0f,  0f, 255f,
            0f,  0f,  0f,  0f, 255f,
            -1f,  0f,  0f,  0f, 255f
    };

    public static final float SCALE = 0.25f; // natural display size will be SCALE*mCellSize

    private static final float PROB_2X = 0.33f;
    private static final float PROB_3X = 0.1f;
    private static final float PROB_4X = 0.01f;

    private boolean mStarted;

    private int mCellSize;
    private int mWidth, mHeight;
    private int mRows, mColumns;
    private View[] mCells;

    private final Set<Point> mFreeList = new HashSet<Point>();

    private final Handler mHandler = new Handler();

    private final Runnable mJuggle = new Runnable() {
        @Override
        public void run() {
            final int N = getChildCount();

            final int K = 1; //irand(1,3);
            for (int i=0; i<K; i++) {
                final View child = getChildAt((int) (Math.random() * N));
                place(child, true);
            }

            fillFreeList();

            if (mStarted) {
                mHandler.postDelayed(mJuggle, DELAY);
            }
        }
    };

    public DessertCaseView(Context context) {
        this(context, null);
    }

    public DessertCaseView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DessertCaseView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final Resources res = getResources();

        mStarted = false;

        mCellSize = res.getDimensionPixelSize(R.dimen.dessert_case_cell_size);
        final BitmapFactory.Options opts = new BitmapFactory.Options();
        if (mCellSize < 512) { // assuming 512x512 images
            opts.inSampleSize = 2;
        }
        opts.inMutable = true;
        Bitmap loaded = null;
        for (int[] list : new int[][] { PASTRIES, RARE_PASTRIES, XRARE_PASTRIES, XXRARE_PASTRIES }) {
            for (int resid : list) {
                opts.inBitmap = loaded;
                loaded = BitmapFactory.decodeResource(res, resid, opts);
                final BitmapDrawable d = new BitmapDrawable(res, convertToAlphaMask(loaded));
                d.setColorFilter(new ColorMatrixColorFilter(ALPHA_MASK));
                d.setBounds(0, 0, mCellSize, mCellSize);
                mDrawables.append(resid, d);
            }
        }
        loaded = null;
        if (DEBUG) setWillNotDraw(false);
    }

    private static Bitmap convertToAlphaMask(Bitmap b) {
        Bitmap a = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(a);
        Paint pt = new Paint();
        pt.setColorFilter(new ColorMatrixColorFilter(MASK));
        c.drawBitmap(b, 0.0f, 0.0f, pt);
        return a;
    }

    public void start() {
        if (!mStarted) {
            mStarted = true;
            fillFreeList(DURATION * 4);
        }
        mHandler.postDelayed(mJuggle, START_DELAY);
    }

    public void stop() {
        mStarted = false;
        mHandler.removeCallbacks(mJuggle);
    }

    int pick(int[] a) {
        return a[(int)(Math.random()*a.length)];
    }

    <T> T pick(T[] a) {
        return a[(int)(Math.random()*a.length)];
    }

    <T> T pick(SparseArray<T> sa) {
        return sa.valueAt((int)(Math.random()*sa.size()));
    }

    float[] hsv = new float[] { 0, 1f, .85f };
    int random_color() {
//        return 0xFF000000 | (int) (Math.random() * (float) 0xFFFFFF); // totally random
        final int COLORS = 12;
        hsv[0] = irand(0,COLORS) * (360f/COLORS);
        return Color.HSVToColor(hsv);
    }

    @Override
    protected synchronized void onSizeChanged (int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mWidth == w && mHeight == h) return;

        final boolean wasStarted = mStarted;
        if (wasStarted) {
            stop();
        }

        mWidth = w;
        mHeight = h;

        mCells = null;
        removeAllViewsInLayout();
        mFreeList.clear();

        mRows = mHeight / mCellSize;
        mColumns = mWidth / mCellSize;

        mCells = new View[mRows * mColumns];

        if (DEBUG) Log.v(TAG, String.format("New dimensions: %dx%d", mColumns, mRows));

        setScaleX(SCALE);
        setScaleY(SCALE);
        setTranslationX(0.5f * (mWidth - mCellSize * mColumns) * SCALE);
        setTranslationY(0.5f * (mHeight - mCellSize * mRows) * SCALE);

        for (int j=0; j<mRows; j++) {
            for (int i=0; i<mColumns; i++) {
                mFreeList.add(new Point(i,j));
            }
        }

        if (wasStarted) {
            start();
        }
    }

    public void fillFreeList() {
        fillFreeList(DURATION);
    }

    public synchronized void fillFreeList(int animationLen) {
        final Context ctx = getContext();
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(mCellSize, mCellSize);

        while (! mFreeList.isEmpty()) {
            Point pt = mFreeList.iterator().next();
            mFreeList.remove(pt);
            final int i=pt.x;
            final int j=pt.y;

            if (mCells[j*mColumns+i] != null) continue;
            final ImageView v = new ImageView(ctx);
            v.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    place(v, true);
                    postDelayed(new Runnable() { public void run() { fillFreeList(); } }, DURATION/2);
                }
            });

            final int c = random_color();
            v.setBackgroundColor(c);

            final float which = frand();
            final Drawable d;
            if (which < 0.0005f) {
                d = mDrawables.get(pick(XXRARE_PASTRIES));
            } else if (which < 0.005f) {
                d = mDrawables.get(pick(XRARE_PASTRIES));
            } else if (which < 0.5f) {
                d = mDrawables.get(pick(RARE_PASTRIES));
            } else if (which < 0.7f) {
                d = mDrawables.get(pick(PASTRIES));
            } else {
                d = null;
            }
            if (d != null) {
                v.getOverlay().add(d);
            }

            lp.width = lp.height = mCellSize;
            addView(v, lp);
            place(v, pt, false);
            if (animationLen > 0) {
                final float s = (Integer) v.getTag(TAG_SPAN);
                v.setScaleX(0.5f * s);
                v.setScaleY(0.5f * s);
                v.setAlpha(0f);
                v.animate().withLayer().scaleX(s).scaleY(s).alpha(1f).setDuration(animationLen);
            }
        }
    }

    public void place(View v, boolean animate) {
        place(v, new Point(irand(0, mColumns), irand(0, mRows)), animate);
    }

    // we don't have .withLayer() on general Animators
    private final Animator.AnimatorListener makeHardwareLayerListener(final View v) {
        return new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animator) {
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                v.buildLayer();
            }
            @Override
            public void onAnimationEnd(Animator animator) {
                v.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        };
    }

    private final HashSet<View> tmpSet = new HashSet<View>();
    public synchronized void place(View v, Point pt, boolean animate) {
        final int i = pt.x;
        final int j = pt.y;
        final float rnd = frand();
        if (v.getTag(TAG_POS) != null) {
            for (final Point oc : getOccupied(v)) {
                mFreeList.add(oc);
                mCells[oc.y*mColumns + oc.x] = null;
            }
        }
        int scale = 1;
        if (rnd < PROB_4X) {
            if (!(i >= mColumns-3 || j >= mRows-3)) {
                scale = 4;
            }
        } else if (rnd < PROB_3X) {
            if (!(i >= mColumns-2 || j >= mRows-2)) {
                scale = 3;
            }
        } else if (rnd < PROB_2X) {
            if (!(i == mColumns-1 || j == mRows-1)) {
                scale = 2;
            }
        }

        v.setTag(TAG_POS, pt);
        v.setTag(TAG_SPAN, scale);

        tmpSet.clear();

        final Point[] occupied = getOccupied(v);
        for (final Point oc : occupied) {
            final View squatter = mCells[oc.y*mColumns + oc.x];
            if (squatter != null) {
                tmpSet.add(squatter);
            }
        }

        for (final View squatter : tmpSet) {
            for (final Point sq : getOccupied(squatter)) {
                mFreeList.add(sq);
                mCells[sq.y*mColumns + sq.x] = null;
            }
            if (squatter != v) {
                squatter.setTag(TAG_POS, null);
                if (animate) {
                    squatter.animate().withLayer()
                            .scaleX(0.5f).scaleY(0.5f).alpha(0)
                            .setDuration(DURATION)
                            .setInterpolator(new AccelerateInterpolator())
                            .setListener(new Animator.AnimatorListener() {
                                public void onAnimationStart(Animator animator) { }
                                public void onAnimationEnd(Animator animator) {
                                    removeView(squatter);
                                }
                                public void onAnimationCancel(Animator animator) { }
                                public void onAnimationRepeat(Animator animator) { }
                            })
                            .start();
                } else {
                    removeView(squatter);
                }
            }
        }

        for (final Point oc : occupied) {
            mCells[oc.y*mColumns + oc.x] = v;
            mFreeList.remove(oc);
        }

        final float rot = (float)irand(0, 4) * 90f;

        if (animate) {
            v.bringToFront();

            AnimatorSet set1 = new AnimatorSet();
            set1.playTogether(
                    ObjectAnimator.ofFloat(v, View.SCALE_X, (float) scale),
                    ObjectAnimator.ofFloat(v, View.SCALE_Y, (float) scale)
            );
            set1.setInterpolator(new AnticipateOvershootInterpolator());
            set1.setDuration(DURATION);

            AnimatorSet set2 = new AnimatorSet();
            set2.playTogether(
                    ObjectAnimator.ofFloat(v, View.ROTATION, rot),
                    ObjectAnimator.ofFloat(v, View.X, i* mCellSize + (scale-1) * mCellSize /2),
                    ObjectAnimator.ofFloat(v, View.Y, j* mCellSize + (scale-1) * mCellSize /2)
            );
            set2.setInterpolator(new DecelerateInterpolator());
            set2.setDuration(DURATION);

            set1.addListener(makeHardwareLayerListener(v));

            set1.start();
            set2.start();
        } else {
            v.setX(i * mCellSize + (scale-1) * mCellSize /2);
            v.setY(j * mCellSize + (scale-1) * mCellSize /2);
            v.setScaleX((float) scale);
            v.setScaleY((float) scale);
            v.setRotation(rot);
        }
    }

    private Point[] getOccupied(View v) {
        final int scale = (Integer) v.getTag(TAG_SPAN);
        final Point pt = (Point)v.getTag(TAG_POS);
        if (pt == null || scale == 0) return new Point[0];

        final Point[] result = new Point[scale * scale];
        int p=0;
        for (int i=0; i<scale; i++) {
            for (int j=0; j<scale; j++) {
                result[p++] = new Point(pt.x + i, pt.y + j);
            }
        }
        return result;
    }

    static float frand() {
        return (float)(Math.random());
    }

    static float frand(float a, float b) {
        return (frand() * (b-a) + a);
    }

    static int irand(int a, int b) {
        return (int)(frand(a, b));
    }

    @Override
    public void onDraw(Canvas c) {
        super.onDraw(c);
        if (!DEBUG) return;

        Paint pt = new Paint();
        pt.setStyle(Paint.Style.STROKE);
        pt.setColor(0xFFCCCCCC);
        pt.setStrokeWidth(2.0f);

        final Rect check = new Rect();
        final int N = getChildCount();
        for (int i = 0; i < N; i++) {
            View stone = getChildAt(i);

            stone.getHitRect(check);

            c.drawRect(check, pt);
        }
    }

    public static class RescalingContainer extends FrameLayout {
        private DessertCaseView mView;
        private float mDarkness;

        public RescalingContainer(Context context) {
            super(context);

            setSystemUiVisibility(0
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }

        public void setView(DessertCaseView v) {
            addView(v);
            mView = v;
        }

        @Override
        protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
            final float w = right-left;
            final float h = bottom-top;
            final int w2 = (int) (w / mView.SCALE / 2);
            final int h2 = (int) (h / mView.SCALE / 2);
            final int cx = (int) (left + w * 0.5f);
            final int cy = (int) (top + h * 0.5f);
            mView.layout(cx - w2, cy - h2, cx + w2, cy + h2);
        }

        public void setDarkness(float p) {
            mDarkness = p;
            getDarkness();
            final int x = (int) (p * 0xff);
            setBackgroundColor(x << 24 & 0xFF000000);
        }

        public float getDarkness() {
            return mDarkness;
        }
    }
}
