/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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

package com.android.gesture;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;

/**
 * A gesture stroke started on a touch down and ended on a touch up.
 */
public class GestureStroke {
    public final RectF boundingBox;

    public final float length;

    public final float[] xPoints;

    public final float[] yPoints;

    public final long[] timestamps;

    private Path mCachedPath;

    /**
     * Construct a gesture stroke from a list of gesture points
     * 
     * @param pts
     */
    public GestureStroke(ArrayList<GesturePoint> pts) {
        xPoints = new float[pts.size()];
        yPoints = new float[pts.size()];
        timestamps = new long[pts.size()];

        RectF bx = null;
        float len = 0;
        int index = 0;
        int count = pts.size();
        float[] xpts = xPoints;
        float[] ypts = yPoints;
        long[] times = timestamps;

        for (int i = 0; i < count; i++) {
            GesturePoint p = pts.get(i);
            xpts[index] = p.xpos;
            ypts[index] = p.ypos;
            times[index] = p.timestamp;

            if (bx == null) {
                bx = new RectF();
                bx.top = p.ypos;
                bx.left = p.xpos;
                bx.right = p.xpos;
                bx.bottom = p.ypos;
                len = 0;
            } else {
                len += Math.sqrt(Math.pow(p.xpos - xpts[index - 1], 2)
                        + Math.pow(p.ypos - ypts[index - 1], 2));
                bx.union(p.xpos, p.ypos);
            }
            index++;
        }

        boundingBox = bx;
        length = len;
    }

    /**
     * Draw the gesture with a given canvas and paint
     * 
     * @param canvas
     */
    void draw(Canvas canvas, Paint paint) {
        if (mCachedPath == null) {
            float[] xpts = xPoints;
            float[] ypts = yPoints;
            int count = xpts.length;
            Path path = null;
            float mX = 0, mY = 0;
            for (int i = 0; i < count; i++) {
                float x = xpts[i];
                float y = ypts[i];
                if (path == null) {
                    path = new Path();
                    path.moveTo(x, y);
                    mX = x;
                    mY = y;
                } else {
                    float dx = Math.abs(x - mX);
                    float dy = Math.abs(y - mY);
                    if (dx >= 3 || dy >= 3) {
                        path.quadTo(mX, mY, (x + mX) / 2, (y + mY) / 2);
                        mX = x;
                        mY = y;
                    }
                }
            }

            mCachedPath = path;
        }

        canvas.drawPath(mCachedPath, paint);
    }

    /**
     * Convert the stroke to a Path based on the number of points
     * 
     * @param width the width of the bounding box of the target path
     * @param height the height of the bounding box of the target path
     * @param numSample the number of points needed
     * @return the path
     */
    public Path toPath(float width, float height, int numSample) {
        float[] pts = GestureUtils.sequentialFeaturize(this, numSample);
        RectF rect = boundingBox;
        float scale = height / rect.height();
        Matrix matrix = new Matrix();
        matrix.setTranslate(-rect.left, -rect.top);
        Matrix scaleMatrix = new Matrix();
        scaleMatrix.setScale(scale, scale);
        matrix.postConcat(scaleMatrix);
        Matrix translate = new Matrix();
        matrix.postConcat(translate);
        matrix.mapPoints(pts);

        Path path = null;
        float mX = 0;
        float mY = 0;
        int count = pts.length;
        for (int i = 0; i < count; i += 2) {
            float x = pts[i];
            float y = pts[i + 1];
            if (path == null) {
                path = new Path();
                path.moveTo(x, y);
                mX = x;
                mY = y;
            } else {
                float dx = Math.abs(x - mX);
                float dy = Math.abs(y - mY);
                if (dx >= GestureOverlay.TOUCH_TOLERANCE || dy >= GestureOverlay.TOUCH_TOLERANCE) {
                    path.quadTo(mX, mY, (x + mX) / 2, (y + mY) / 2);
                    mX = x;
                    mY = y;
                }
            }
        }
        return path;
    }

    /**
     * Save the gesture stroke as XML
     * 
     * @param namespace
     * @param serializer
     * @throws IOException
     */
    void toXML(String namespace, XmlSerializer serializer) throws IOException {
        serializer.startTag(namespace, GestureConstants.XML_TAG_STROKE);
        serializer.text(toString());
        serializer.endTag(namespace, GestureConstants.XML_TAG_STROKE);
    }

    /**
     * Create a gesture stroke from a string
     * 
     * @param str
     * @return the gesture stroke
     */
    public static GestureStroke createFromString(String str) {
        ArrayList<GesturePoint> points = new ArrayList<GesturePoint>(
                GestureConstants.STROKE_POINT_BUFFER_SIZE);
        int endIndex;
        int startIndex = 0;
        while ((endIndex = str.indexOf(GestureConstants.STRING_STROKE_DELIIMITER, startIndex + 1)) != -1) {

            // parse x
            String token = str.substring(startIndex, endIndex);
            float x = Float.parseFloat(token);
            startIndex = endIndex + 1;

            // parse y
            endIndex = str.indexOf(GestureConstants.STRING_STROKE_DELIIMITER, startIndex + 1);
            token = str.substring(startIndex, endIndex);
            float y = Float.parseFloat(token);
            startIndex = endIndex + 1;

            // parse t
            endIndex = str.indexOf(GestureConstants.STRING_STROKE_DELIIMITER, startIndex + 1);
            token = str.substring(startIndex, endIndex);
            long time = Long.parseLong(token);
            startIndex = endIndex + 1;

            points.add(new GesturePoint(x, y, time));
        }
        return new GestureStroke(points);
    }

    /**
     * Convert the stroke to string
     */
    @Override
    public String toString() {
        StringBuilder str = new StringBuilder(GestureConstants.STROKE_STRING_BUFFER_SIZE);
        float[] xpts = xPoints;
        float[] ypts = yPoints;
        long[] times = timestamps;
        int count = xpts.length;
        for (int i = 0; i < count; i++) {
            str.append(xpts[i] + GestureConstants.STRING_STROKE_DELIIMITER + ypts[i]
                    + GestureConstants.STRING_STROKE_DELIIMITER + times[i]
                    + GestureConstants.STRING_STROKE_DELIIMITER);
        }
        return str.toString();
    }

    /**
     * Invalidate the cached path that is used for rendering the stroke
     */
    public void invalidate() {
        mCachedPath = null;
    }
}
