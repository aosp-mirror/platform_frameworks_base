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
    public static final int TILEX = 300;
    public static final int TILEY = 300;

    private Paint levelPaint = null, coordPaint = null, goldPaint = null;
    private PlaybackGraphs mGraphs;

    private ArrayList<ShapeDrawable> mTempShapes = new ArrayList<ShapeDrawable>();
    private TileData mProfData[][] = null;
    private GestureDetector mGestureDetector = null;
    private String mRenderStrings[] = new String[3];

    private class TileDrawable extends ShapeDrawable {
        TileData tile;

        public TileDrawable(TileData t) {
            int tileColorId = t.isReady ? R.color.ready_tile
                    : R.color.unready_tile;
            getPaint().setColor(getResources().getColor(tileColorId));

            setBounds(t.x * TILEX, t.y * TILEY, (t.x + 1) * TILEX, (t.y + 1)
                    * TILEY);
            this.tile = t;
        }

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            canvas.drawText(Integer.toString(tile.level), getBounds().left,
                    getBounds().bottom, levelPaint);
            canvas.drawText(tile.x + "," + tile.y, getBounds().left,
                    ((getBounds().bottom + getBounds().top) / 2), coordPaint);
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
        levelPaint.setTextSize(TILEY / 2);
        coordPaint = new Paint();
        coordPaint.setColor(Color.BLACK);
        coordPaint.setTextSize(TILEY / 3);
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
    }

    public int setFrame(int frame) {
        if (mProfData == null || mProfData.length == 0) {
            return 0;
        }

        int readyTiles = 0, unreadyTiles = 0, unplacedTiles = 0;
        mTempShapes.clear();

        // draw actual tiles
        for (int tileID = 2; tileID < mProfData[frame].length; tileID++) {
            TileData t = mProfData[frame][tileID];
            mTempShapes.add(new TileDrawable(t));
            if (t.isReady) {
                readyTiles++;
            } else {
                unreadyTiles++;
            }
            if (t.x < 0 || t.y < 0) {
                unplacedTiles++;
            }
        }
        mRenderStrings[0] = getResources().getString(R.string.format_stat_name,
                getResources().getString(R.string.ready_tiles), readyTiles);
        mRenderStrings[1] = getResources().getString(R.string.format_stat_name,
                getResources().getString(R.string.unready_tiles), unreadyTiles);
        mRenderStrings[2] = getResources().getString(R.string.format_stat_name,
                getResources().getString(R.string.unplaced_tiles), unplacedTiles);

        // draw view rect (using first two TileData objects)
        ShapeDrawable viewShape = new ShapeDrawable();
        viewShape.getPaint().setColor(0xff0000ff);
        viewShape.setAlpha(64);
        viewShape.setBounds(mProfData[frame][0].x, mProfData[frame][0].y,
                mProfData[frame][1].x, mProfData[frame][1].y);
        mTempShapes.add(viewShape);
        this.invalidate();
        return frame;
    }

    public void setData(TileData[][] tileProfilingData) {
        mProfData = tileProfilingData;

        mGraphs.setData(mProfData);
    }
}
