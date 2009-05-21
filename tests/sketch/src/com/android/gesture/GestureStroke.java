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
    public final float[] points;

    private final long[] timestamps;
    private Path mCachedPath;

    /**
     * Construct a gesture stroke from a list of gesture points
     * 
     * @param points
     */
    public GestureStroke(ArrayList<GesturePoint> points) {
        final int count = points.size();
        final float[] tmpPoints = new float[count * 2];
        final long[] times = new long[count];

        RectF bx = null;
        float len = 0;
        int index = 0;

        for (int i = 0; i < count; i++) {
            final GesturePoint p = points.get(i);
            tmpPoints[i * 2] = p.x;
            tmpPoints[i * 2 + 1] = p.y;
            times[index] = p.timestamp;

            if (bx == null) {
                bx = new RectF();
                bx.top = p.y;
                bx.left = p.x;
                bx.right = p.x;
                bx.bottom = p.y;
                len = 0;
            } else {
                len += Math.sqrt(Math.pow(p.x - tmpPoints[(i - 1) * 2], 2)
                        + Math.pow(p.y - tmpPoints[(i -1 ) * 2 + 1], 2));
                bx.union(p.x, p.y);
            }
            index++;
        }
        
        timestamps = times;
        this.points = tmpPoints;
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
            final float[] localPoints = points;
            final int count = localPoints.length;

            Path path = null;

            float mX = 0;
            float mY = 0;

            for (int i = 0; i < count; i += 2) {
                float x = localPoints[i];
                float y = localPoints[i + 1];
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
        final float[] pts = GestureUtilities.temporalSampling(this, numSample);
        final RectF rect = boundingBox;
        final float scale = height / rect.height();

        final Matrix matrix = new Matrix();
        matrix.setTranslate(-rect.left, -rect.top);
        matrix.postScale(scale, scale);
        matrix.mapPoints(pts);

        float mX = 0;
        float mY = 0;

        Path path = null;

        final int count = pts.length;

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
        final ArrayList<GesturePoint> points = new ArrayList<GesturePoint>(
                GestureConstants.STROKE_POINT_BUFFER_SIZE);

        int endIndex;
        int startIndex = 0;

        while ((endIndex =
                str.indexOf(GestureConstants.STRING_STROKE_DELIIMITER, startIndex + 1)) != -1) {

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
        final StringBuilder str = new StringBuilder(GestureConstants.STROKE_STRING_BUFFER_SIZE);
        final float[] pts = points;
        final long[] times = timestamps;
        final int count = points.length;

        for (int i = 0; i < count; i += 2) {
            str.append(pts[i]).append(GestureConstants.STRING_STROKE_DELIIMITER);
            str.append(pts[i + 1]).append(GestureConstants.STRING_STROKE_DELIIMITER);
            str.append(times[i / 2]).append(GestureConstants.STRING_STROKE_DELIIMITER);
        }

        return str.toString();
    }

    /**
     * Invalidate the cached path that is used for rendering the stroke
     */
    public void invalidate() {
        mCachedPath = null;
    }
    
    /**
     * Compute an oriented bounding box of the stroke
     * @return OrientedBoundingBox
     */
    public OrientedBoundingBox computeOrientedBoundingBox() {
        return GestureUtilities.computeOrientedBoundingBox(points);
    }
}
