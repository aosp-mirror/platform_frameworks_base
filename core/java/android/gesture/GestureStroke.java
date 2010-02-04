/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.gesture;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.IOException;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.util.ArrayList;

/**
 * A gesture stroke started on a touch down and ended on a touch up. A stroke
 * consists of a sequence of timed points. One or multiple strokes form a gesture.
 */
public class GestureStroke {
    static final float TOUCH_TOLERANCE = 3;

    public final RectF boundingBox;

    public final float length;
    public final float[] points;

    private final long[] timestamps;
    private Path mCachedPath;

    /**
     * A constructor that constructs a gesture stroke from a list of gesture points.
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
     * A faster constructor specially for cloning a stroke.
     */
    private GestureStroke(RectF bbx, float len, float[] pts, long[] times) {
        boundingBox = new RectF(bbx.left, bbx.top, bbx.right, bbx.bottom);
        length = len;
        points = pts.clone();
        timestamps = times.clone();
    }
    
    @Override
    public Object clone() {
        return new GestureStroke(boundingBox, length, points, timestamps);
    }
    
    /**
     * Draws the stroke with a given canvas and paint.
     * 
     * @param canvas
     */
    void draw(Canvas canvas, Paint paint) {
        if (mCachedPath == null) {
            makePath();
        }

        canvas.drawPath(mCachedPath, paint);
    }

    public Path getPath() {
        if (mCachedPath == null) {
            makePath();
        }

        return mCachedPath;
    }

    private void makePath() {
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
                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    path.quadTo(mX, mY, (x + mX) / 2, (y + mY) / 2);
                    mX = x;
                    mY = y;
                }
            }
        }

        mCachedPath = path;
    }

    /**
     * Converts the stroke to a Path of a given number of points.
     * 
     * @param width the width of the bounding box of the target path
     * @param height the height of the bounding box of the target path
     * @param numSample the number of points needed
     * 
     * @return the path
     */
    public Path toPath(float width, float height, int numSample) {
        final float[] pts = GestureUtils.temporalSampling(this, numSample);
        final RectF rect = boundingBox;

        GestureUtils.translate(pts, -rect.left, -rect.top);
        
        float sx = width / rect.width();
        float sy = height / rect.height();
        float scale = sx > sy ? sy : sx;
        GestureUtils.scale(pts, scale, scale);

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
                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    path.quadTo(mX, mY, (x + mX) / 2, (y + mY) / 2);
                    mX = x;
                    mY = y;
                }
            }
        }

        return path;
    }

    void serialize(DataOutputStream out) throws IOException {
        final float[] pts = points;
        final long[] times = timestamps;
        final int count = points.length;

        // Write number of points
        out.writeInt(count / 2);

        for (int i = 0; i < count; i += 2) {
            // Write X
            out.writeFloat(pts[i]);
            // Write Y
            out.writeFloat(pts[i + 1]);
            // Write timestamp
            out.writeLong(times[i / 2]);
        }
    }

    static GestureStroke deserialize(DataInputStream in) throws IOException {
        // Number of points
        final int count = in.readInt();

        final ArrayList<GesturePoint> points = new ArrayList<GesturePoint>(count);
        for (int i = 0; i < count; i++) {
            points.add(GesturePoint.deserialize(in));
        }

        return new GestureStroke(points);
    }    

    /**
     * Invalidates the cached path that is used to render the stroke.
     */
    public void clearPath() {
        if (mCachedPath != null) mCachedPath.rewind();
    }
    
    /**
     * Computes an oriented bounding box of the stroke.
     * 
     * @return OrientedBoundingBox
     */
    public OrientedBoundingBox computeOrientedBoundingBox() {
        return GestureUtils.computeOrientedBoundingBox(points);
    }
}
