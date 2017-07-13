/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.prefabulated.touchlatency;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.CountDownTimer;
import android.os.Bundle;
import android.text.method.Touch;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;

class TouchLatencyView extends View implements View.OnTouchListener {
    private static final String LOG_TAG = "TouchLatency";
    private static final int BACKGROUND_COLOR = 0xFF400080;
    private static final int INNER_RADIUS = 70;
    private static final int BALL_RADIUS = 100;

    public TouchLatencyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnTouchListener(this);
        setWillNotDraw(false);
        mBluePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBluePaint.setColor(0xFF0000FF);
        mBluePaint.setStyle(Paint.Style.FILL);
        mGreenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mGreenPaint.setColor(0xFF00FF00);
        mGreenPaint.setStyle(Paint.Style.FILL);
        mYellowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mYellowPaint.setColor(0xFFFFFF00);
        mYellowPaint.setStyle(Paint.Style.FILL);
        mRedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRedPaint.setColor(0xFFFF0000);
        mRedPaint.setStyle(Paint.Style.FILL);

        mTouching = false;

        mBallX = 100.0f;
        mBallY = 100.0f;
        mVelocityX = 7.0f;
        mVelocityY = 7.0f;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            mTouching = true;
            invalidate();
        } else if (action == MotionEvent.ACTION_UP) {
            mTouching = false;
            invalidate();
            return true;
        } else {
            return true;
        }
        mTouchX = event.getX();
        mTouchY = event.getY();
        return true;
    }

    private void drawTouch(Canvas canvas) {
        if (!mTouching) {
            Log.d(LOG_TAG, "Filling background");
            canvas.drawColor(BACKGROUND_COLOR);
            return;
        }

        float deltaX = (mTouchX - mLastDrawnX);
        float deltaY = (mTouchY - mLastDrawnY);
        float scaleFactor = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY) * 1.5f;

        mLastDrawnX = mTouchX;
        mLastDrawnY = mTouchY;

        canvas.drawColor(BACKGROUND_COLOR);
        canvas.drawCircle(mTouchX, mTouchY, INNER_RADIUS + 3 * scaleFactor, mRedPaint);
        canvas.drawCircle(mTouchX, mTouchY, INNER_RADIUS + 2 * scaleFactor, mYellowPaint);
        canvas.drawCircle(mTouchX, mTouchY, INNER_RADIUS + scaleFactor, mGreenPaint);
        canvas.drawCircle(mTouchX, mTouchY, INNER_RADIUS, mBluePaint);
    }

    private void drawBall(Canvas canvas) {
        int width = canvas.getWidth();
        int height = canvas.getHeight();

        // Update position
        mBallX += mVelocityX;
        mBallY += mVelocityY;

        // Clamp and change velocity if necessary
        float left = mBallX - BALL_RADIUS;
        if (left < 0) {
            left = 0;
            mVelocityX *= -1;
        }

        float top = mBallY - BALL_RADIUS;
        if (top < 0) {
            top = 0;
            mVelocityY *= -1;
        }

        float right = mBallX + BALL_RADIUS;
        if (right > width) {
            right = width;
            mVelocityX *= -1;
        }

        float bottom = mBallY + BALL_RADIUS;
        if (bottom > height) {
            bottom = height;
            mVelocityY *= -1;
        }

        // Draw the ball
        canvas.drawColor(BACKGROUND_COLOR);
        canvas.drawOval(left, top, right, bottom, mYellowPaint);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mMode == 0) {
            drawTouch(canvas);
        } else {
            drawBall(canvas);
        }
    }

    public void changeMode(MenuItem item) {
        final int NUM_MODES = 2;
        final String modes[] = {"Touch", "Ball"};
        mMode = (mMode + 1) % NUM_MODES;
        invalidate();
        item.setTitle(modes[mMode]);
    }

    private Paint mBluePaint, mGreenPaint, mYellowPaint, mRedPaint;
    private int mMode;

    private boolean mTouching;
    private float mTouchX, mTouchY;
    private float mLastDrawnX, mLastDrawnY;

    private float mBallX, mBallY;
    private float mVelocityX, mVelocityY;
}

public class TouchLatencyActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_touch_latency);

        mTouchView = findViewById(R.id.canvasView);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_touch_latency, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            mTouchView.changeMode(item);
        }

        return super.onOptionsItemSelected(item);
    }

    private TouchLatencyView mTouchView;
}
