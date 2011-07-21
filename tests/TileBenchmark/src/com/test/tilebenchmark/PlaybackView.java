/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.test.tilebenchmark;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ShapeDrawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

public class PlaybackView extends View {
    public static final int TILE_SCALE = 300;
    private static final int INVAL_FLAG = -2;
    private static final int INVAL_CYCLE = 250;

    private Paint levelPaint = null, coordPaint = null, goldPaint = null;
    private PlaybackGraphs mGraphs;

    private ArrayList<ShapeDrawable> mTempShapes = new ArrayList<ShapeDrawable>();
    private TileData mProfData[][] = null;
    private GestureDetector mGestureDetector = null;
    private String mRenderStrings[] = new String[4];

    private class TileDrawable extends ShapeDrawable {
        TileData tile;
        String label;

        public TileDrawable(TileData t, int colorId) {
            this.tile = t;
            getPaint().setColor(getResources().getColor(colorId));
            if (colorId == R.color.ready_tile
                    || colorId == R.color.unready_tile) {

                label = (int) (t.left / TILE_SCALE) + ", "
                        + (int) (t.top / TILE_SCALE);
                // ignore scale value for tiles
                setBounds(t.left, t.top,
                        t.right, t.bottom);
            } else {
                setBounds((int) (t.left * t.scale),
                        (int) (t.top * t.scale),
                        (int) (t.right * t.scale),
                        (int) (t.bottom * t.scale));
            }
        }

        @SuppressWarnings("unused")
        public void setColor(int color) {
            getPaint().setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            if (label != null) {
                canvas.drawText(Integer.toString(tile.level), getBounds().left,
                        getBounds().bottom, levelPaint);
                canvas.drawText(label, getBounds().left,
                        ((getBounds().bottom + getBounds().top) / 2),
                        coordPaint);
            }
        }
    }

    public PlaybackView(Context context) {
        super(context);
        init();
    }

    public PlaybackView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PlaybackView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    public void setOnGestureListener(OnGestureListener gl) {
        mGestureDetector = new GestureDetector(getContext(), gl);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGestureDetector.onTouchEvent(event);
        return true;
    }

    private void init() {
        levelPaint = new Paint();
        levelPaint.setColor(Color.WHITE);
        levelPaint.setTextSize(TILE_SCALE / 2);
        coordPaint = new Paint();
        coordPaint.setColor(Color.BLACK);
        coordPaint.setTextSize(TILE_SCALE / 3);
        goldPaint = new Paint();
        goldPaint.setColor(0xffa0e010);
        mGraphs = new PlaybackGraphs();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mTempShapes == null || mTempShapes.isEmpty()) {
            return;
        }

        mGraphs.draw(canvas, mTempShapes, mRenderStrings, getResources());
        invalidate(); // may have animations, force redraw
    }

    public int setFrame(int frame) {
        if (mProfData == null || mProfData.length == 0) {
            return 0;
        }

        int readyTiles = 0, unreadyTiles = 0, unplacedTiles = 0, numInvals = 0;
        mTempShapes.clear();

        // create tile shapes (as they're drawn on bottom)
        for (TileData t : mProfData[frame]) {
            if (t.level != INVAL_FLAG && t != mProfData[frame][0]) {
                int colorId;
                if (t.isReady) {
                    readyTiles++;
                    colorId = R.color.ready_tile;
                } else {
                    unreadyTiles++;
                    colorId = R.color.unready_tile;
                }
                if (t.left < 0 || t.top < 0) {
                    unplacedTiles++;
                }
                mTempShapes.add(new TileDrawable(t, colorId));
            } else {
                numInvals++;
            }
        }

        // create invalidate shapes (drawn above tiles)
        int invalId = 0;
        for (TileData t : mProfData[frame]) {
            if (t.level == INVAL_FLAG && t != mProfData[frame][0]) {
                TileDrawable invalShape = new TileDrawable(t,
                        R.color.inval_region_start);
                ValueAnimator tileAnimator = ObjectAnimator.ofInt(invalShape,
                        "color",
                        getResources().getColor(R.color.inval_region_start),
                        getResources().getColor(R.color.inval_region_stop));
                tileAnimator.setDuration(numInvals * INVAL_CYCLE);
                tileAnimator.setEvaluator(new ArgbEvaluator());
                tileAnimator.setRepeatCount(ValueAnimator.INFINITE);
                tileAnimator.setRepeatMode(ValueAnimator.RESTART);
                float delay = (float) (invalId) * INVAL_CYCLE;
                tileAnimator.setStartDelay((int) delay);
                invalId++;
                tileAnimator.start();

                mTempShapes.add(invalShape);
            }
        }

        mRenderStrings[0] = getResources().getString(R.string.format_stat_name,
                getResources().getString(R.string.ready_tiles), readyTiles);
        mRenderStrings[1] = getResources().getString(R.string.format_stat_name,
                getResources().getString(R.string.unready_tiles), unreadyTiles);
        mRenderStrings[2] = getResources().getString(R.string.format_stat_name,
                getResources().getString(R.string.unplaced_tiles),
                unplacedTiles);
        mRenderStrings[3] = getResources().getString(R.string.format_stat_name,
                getResources().getString(R.string.number_invalidates),
                numInvals);

        // draw view rect (using first TileData object, on top)
        TileDrawable viewShape = new TileDrawable(mProfData[frame][0],
                R.color.view);
        mTempShapes.add(viewShape);
        this.invalidate();
        return frame;
    }

    public void setData(TileData[][] tileProfilingData) {
        mProfData = tileProfilingData;

        mGraphs.setData(mProfData);
    }
}
