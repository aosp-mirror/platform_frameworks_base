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
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

@SuppressWarnings({"UnusedDeclaration"})
public class PathsCacheActivity extends Activity {
    private Path mPath;

    private final Random mRandom = new Random();
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private final ArrayList<Path> mPathList = new ArrayList<Path>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPath = makePath();

        final PathsView view = new PathsView(this);
        setContentView(view);
    }

    private static Path makePath() {
        Path path = new Path();
        buildPath(path);
        return path;
    }

    private static void buildPath(Path path) {
        path.moveTo(0.0f, 0.0f);
        path.cubicTo(0.0f, 0.0f, 100.0f, 150.0f, 100.0f, 200.0f);
        path.cubicTo(100.0f, 200.0f, 50.0f, 300.0f, -80.0f, 200.0f);
        path.cubicTo(-80.0f, 200.0f, 100.0f, 200.0f, 200.0f, 0.0f);
    }

    private static Path makeLargePath() {
        Path path = new Path();
        buildLargePath(path);
        return path;
    }

    private static void buildLargePath(Path path) {
        path.moveTo(0.0f, 0.0f);
        path.cubicTo(0.0f, 0.0f, 10000.0f, 15000.0f, 10000.0f, 20000.0f);
        path.cubicTo(10000.0f, 20000.0f, 5000.0f, 30000.0f, -8000.0f, 20000.0f);
        path.cubicTo(-8000.0f, 20000.0f, 10000.0f, 20000.0f, 20000.0f, 0.0f);
    }

    public class PathsView extends View {
        private final Paint mMediumPaint;

        public PathsView(Context c) {
            super(c);

            mMediumPaint = new Paint();
            mMediumPaint.setAntiAlias(true);
            mMediumPaint.setColor(0xe00000ff);
            mMediumPaint.setStrokeWidth(10.0f);
            mMediumPaint.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            Log.d("OpenGLRenderer", "Start frame");

            canvas.drawARGB(255, 255, 255, 255);

            canvas.save();
            canvas.translate(550.0f, 60.0f);
            canvas.drawPath(mPath, mMediumPaint);

            mPath.reset();
            buildPath(mPath);

            canvas.translate(30.0f, 30.0f);
            canvas.drawPath(mPath, mMediumPaint);
            canvas.drawPath(mPath, mMediumPaint);

            canvas.restore();

            for (int i = 0; i < mRandom.nextInt(20); i++) {
                Path path = makePath();
                int r = mRandom.nextInt(10);
                if (r == 5 || r == 3) {
                    mPathList.add(path);
                } else if (r == 7) {
                    path = makeLargePath();
                    mPathList.add(path);
                }
    
                canvas.save();
                canvas.translate(450.0f + mRandom.nextInt(200), mRandom.nextInt(200));
                canvas.drawPath(path, mMediumPaint);
                canvas.restore();
            }

            int r = mRandom.nextInt(100);
            if (r == 50) {
                mPathList.clear();
            }

            invalidate();
        }
    }
}
