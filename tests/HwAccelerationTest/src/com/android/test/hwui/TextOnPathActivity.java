/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class TextOnPathActivity extends Activity {
    private Path mPath;
    private Path mStraightPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPath = makePath();
        mStraightPath = makeStraightPath();

        final TextOnPathView view = new TextOnPathView(this);
        setContentView(view);
    }

    private Path makePath() {
        Path path = new Path();
        buildPath(path);
        return path;
    }

    private void buildPath(Path path) {
        path.moveTo(0.0f, 0.0f);
        path.cubicTo(0.0f, 0.0f, 100.0f, 150.0f, 100.0f, 200.0f);
        path.cubicTo(100.0f, 200.0f, 50.0f, 300.0f, -80.0f, 200.0f);
        path.cubicTo(-80.0f, 200.0f, 100.0f, 200.0f, 200.0f, 0.0f);
    }

    private Path makeStraightPath() {
        Path path = new Path();
        buildStraightPath(path);
        return path;
    }

    private void buildStraightPath(Path path) {
        path.moveTo(0.0f, 0.0f);
        path.lineTo(400.0f, 0.0f);
    }

    public class TextOnPathView extends View {
        private static final String TEST_STRING = "Hello OpenGL renderer, text on path! ";

        private final Paint mPaint;
        private final Paint mPathPaint;
        private final String mText;
        private final PathMeasure mMeasure;
        private final float mLength;
        private final float[] mLines;
        private final float[] mPos;
        private final float[] mTan;

        public TextOnPathView(Context c) {
            super(c);

            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setColor(0xff000000);

            mPathPaint = new Paint();
            mPathPaint.setAntiAlias(true);
            mPathPaint.setStyle(Paint.Style.STROKE);
            mPathPaint.setColor(0xff000099);

            StringBuilder builder = new StringBuilder(TEST_STRING.length() * 2);
            for (int i = 0; i < 2; i++) {
                builder.append(TEST_STRING);
            }
            mText = builder.toString();

            mMeasure = new PathMeasure(mPath, false);
            mLength = mMeasure.getLength();
            
            mLines = new float[100 * 4];
            mPos = new float[2];
            mTan = new float[2];
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawARGB(255, 255, 255, 255);

            canvas.save();
            canvas.translate(400.0f, 350.0f);
            mPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawTextOnPath(mText + mText, mPath, 0.0f, 0.0f, mPaint);
            canvas.drawPath(mPath, mPathPaint);
            
            for (int i = 0; i < 100; i++) {
                mMeasure.getPosTan(i * mLength / 100.0f, mPos, mTan);
                mLines[i * 4    ] = mPos[0];
                mLines[i * 4 + 1] = mPos[1];
                mLines[i * 4 + 2] = mPos[0] + mTan[1] * 15;
                mLines[i * 4 + 3] = mPos[1] - mTan[0] * 15;
            }
            canvas.drawLines(mLines, mPathPaint);
            
            canvas.translate(200.0f, 0.0f);
            canvas.drawTextOnPath(mText + mText, mStraightPath, 0.0f, 0.0f, mPaint);
            canvas.drawPath(mStraightPath, mPathPaint);

            canvas.restore();

            canvas.save();
            canvas.translate(150.0f, 60.0f);
            canvas.drawTextOnPath(mText, mPath, 0.0f, 10.0f, mPaint);
            mMeasure.getPosTan(5.0f, mPos, mTan);
            canvas.drawLine(mPos[0], mPos[1], mPos[0] + mTan[1] * 10, mPos[1] - mTan[0] * 10,
                    mPathPaint);
            canvas.drawPath(mPath, mPathPaint);

            canvas.translate(250.0f, 0.0f);
            mPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawTextOnPath(mText, mPath, 0.0f, 0.0f, mPaint);
            canvas.drawPath(mPath, mPathPaint);

            canvas.translate(250.0f, 0.0f);
            mPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawTextOnPath(mText, mPath, 0.0f, 0.0f, mPaint);
            canvas.drawPath(mPath, mPathPaint);
            canvas.restore();
        }
    }
}
