/*
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

package com.android.internal.widget.multiwaveview;

import java.util.ArrayList;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.Log;

public class PointCloud {
    private static final float MIN_POINT_SIZE = 2.0f;
    private static final float MAX_POINT_SIZE = 4.0f;
    private static final int INNER_POINTS = 8;
    private static final String TAG = "PointCloud";
    private ArrayList<Point> mPointCloud = new ArrayList<Point>();
    private Drawable mDrawable;
    private float mCenterX;
    private float mCenterY;
    private Paint mPaint;
    private float mScale = 1.0f;
    private static final float PI = (float) Math.PI;

    // These allow us to have multiple concurrent animations.
    WaveManager waveManager = new WaveManager();
    GlowManager glowManager = new GlowManager();
    private float mOuterRadius;

    public class WaveManager {
        private float radius = 50;
        private float alpha = 0.0f;

        public void setRadius(float r) {
            radius = r;
        }

        public float getRadius() {
            return radius;
        }

        public void setAlpha(float a) {
            alpha = a;
        }

        public float getAlpha() {
            return alpha;
        }
    };

    public class GlowManager {
        private float x;
        private float y;
        private float radius = 0.0f;
        private float alpha = 0.0f;

        public void setX(float x1) {
            x = x1;
        }

        public float getX() {
            return x;
        }

        public void setY(float y1) {
            y = y1;
        }

        public float getY() {
            return y;
        }

        public void setAlpha(float a) {
            alpha = a;
        }

        public float getAlpha() {
            return alpha;
        }

        public void setRadius(float r) {
            radius = r;
        }

        public float getRadius() {
            return radius;
        }
    }

    class Point {
        float x;
        float y;
        float radius;

        public Point(float x2, float y2, float r) {
            x = (float) x2;
            y = (float) y2;
            radius = r;
        }
    }

    public PointCloud(Drawable drawable) {
        mPaint = new Paint();
        mPaint.setFilterBitmap(true);
        mPaint.setColor(Color.rgb(255, 255, 255)); // TODO: make configurable
        mPaint.setAntiAlias(true);
        mPaint.setDither(true);

        mDrawable = drawable;
        if (mDrawable != null) {
            drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        }
    }

    public void setCenter(float x, float y) {
        mCenterX = x;
        mCenterY = y;
    }

    public void makePointCloud(float innerRadius, float outerRadius) {
        if (innerRadius == 0) {
            Log.w(TAG, "Must specify an inner radius");
            return;
        }
        mOuterRadius = outerRadius;
        mPointCloud.clear();
        final float pointAreaRadius =  (outerRadius - innerRadius);
        final float ds = (2.0f * PI * innerRadius / INNER_POINTS);
        final int bands = (int) Math.round(pointAreaRadius / ds);
        final float dr = pointAreaRadius / bands;
        float r = innerRadius;
        for (int b = 0; b <= bands; b++, r += dr) {
            float circumference = 2.0f * PI * r;
            final int pointsInBand = (int) (circumference / ds);
            float eta = PI/2.0f;
            float dEta = 2.0f * PI / pointsInBand;
            for (int i = 0; i < pointsInBand; i++) {
                float x = r * (float) Math.cos(eta);
                float y = r * (float) Math.sin(eta);
                eta += dEta;
                mPointCloud.add(new Point(x, y, r));
            }
        }
    }

    public void setScale(float scale) {
        mScale  = scale;
    }

    public float getScale() {
        return mScale;
    }

    public int getAlphaForPoint(Point point) {
        // Contribution from positional glow
        float glowDistance = (float) Math.hypot(glowManager.x - point.x, glowManager.y - point.y);
        float glowAlpha = 0.0f;
        if (glowDistance < glowManager.radius) {
            float cosf = (float) Math.cos(PI * 0.25f * glowDistance / glowManager.radius);
            glowAlpha = glowManager.alpha * Math.max(0.0f, (float) Math.pow(cosf, 10.0f));
        }

        // Compute contribution from Wave
        float radius = (float) Math.hypot(point.x, point.y);
        float waveAlpha = 0.0f;
        if (radius < waveManager.radius * 2) {
            float distanceToWaveRing = (radius - waveManager.radius);
            float cosf = (float) Math.cos(PI * 0.5f * distanceToWaveRing / waveManager.radius);
            waveAlpha = waveManager.alpha * Math.max(0.0f, (float) Math.pow(cosf, 6.0f));
        }
        return (int) (Math.max(glowAlpha, waveAlpha) * 255);
    }

    private float interp(float min, float max, float f) {
        return min + (max - min) * f;
    }

    public void draw(Canvas canvas) {
        ArrayList<Point> points = mPointCloud;
        canvas.save(Canvas.MATRIX_SAVE_FLAG);
        canvas.scale(mScale, mScale, mCenterX, mCenterY);
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            final float pointSize = interp(MAX_POINT_SIZE, MIN_POINT_SIZE,
                    point.radius / mOuterRadius);
            final float px = point.x + mCenterX;
            final float py = point.y + mCenterY;
            int alpha = getAlphaForPoint(point);

            if (alpha == 0) continue;

            if (mDrawable != null) {
                canvas.save(Canvas.MATRIX_SAVE_FLAG);
                final float cx = mDrawable.getIntrinsicWidth() * 0.5f;
                final float cy = mDrawable.getIntrinsicHeight() * 0.5f;
                final float s = pointSize / MAX_POINT_SIZE;
                canvas.scale(s, s, px, py);
                canvas.translate(px - cx, py - cy);
                mDrawable.setAlpha(alpha);
                mDrawable.draw(canvas);
                canvas.restore();
            } else {
                mPaint.setAlpha(alpha);
                canvas.drawCircle(px, py, pointSize, mPaint);
            }
        }
        canvas.restore();
    }

}
