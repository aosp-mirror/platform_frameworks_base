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

package android.gesture;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.io.IOException;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A gesture is a hand-drawn shape on a touch screen. It can have one or multiple strokes.
 * Each stroke is a sequence of timed points. A user-defined gesture can be recognized by 
 * a GestureLibrary. 
 */

public class Gesture implements Parcelable {
    private static final long GESTURE_ID_BASE = System.currentTimeMillis();

    private static final int BITMAP_RENDERING_WIDTH = 2;

    private static final boolean BITMAP_RENDERING_ANTIALIAS = true;
    private static final boolean BITMAP_RENDERING_DITHER = true;

    private static final AtomicInteger sGestureCount = new AtomicInteger(0);

    private final RectF mBoundingBox = new RectF();

    // the same as its instance ID
    private long mGestureID;

    private final ArrayList<GestureStroke> mStrokes = new ArrayList<GestureStroke>();

    public Gesture() {
        mGestureID = GESTURE_ID_BASE + sGestureCount.incrementAndGet();
    }

    @Override
    public Object clone() {
        Gesture gesture = new Gesture();
        gesture.mBoundingBox.set(mBoundingBox.left, mBoundingBox.top, 
                                        mBoundingBox.right, mBoundingBox.bottom);
        final int count = mStrokes.size();
        for (int i = 0; i < count; i++) {
            GestureStroke stroke = mStrokes.get(i);
            gesture.mStrokes.add((GestureStroke)stroke.clone());
        }
        return gesture;
    }
    
    /**
     * @return all the strokes of the gesture
     */
    public ArrayList<GestureStroke> getStrokes() {
        return mStrokes;
    }

    /**
     * @return the number of strokes included by this gesture
     */
    public int getStrokesCount() {
        return mStrokes.size();
    }

    /**
     * Adds a stroke to the gesture.
     * 
     * @param stroke
     */
    public void addStroke(GestureStroke stroke) {
        mStrokes.add(stroke);
        mBoundingBox.union(stroke.boundingBox);
    }

    /**
     * Calculates the total length of the gesture. When there are multiple strokes in
     * the gesture, this returns the sum of the lengths of all the strokes.
     * 
     * @return the length of the gesture
     */
    public float getLength() {
        int len = 0;
        final ArrayList<GestureStroke> strokes = mStrokes;
        final int count = strokes.size();

        for (int i = 0; i < count; i++) {
            len += strokes.get(i).length;
        }

        return len;
    }

    /**
     * @return the bounding box of the gesture
     */
    public RectF getBoundingBox() {
        return mBoundingBox;
    }

    public Path toPath() {
        return toPath(null);
    }

    public Path toPath(Path path) {
        if (path == null) path = new Path();

        final ArrayList<GestureStroke> strokes = mStrokes;
        final int count = strokes.size();

        for (int i = 0; i < count; i++) {
            path.addPath(strokes.get(i).getPath());
        }

        return path;
    }

    public Path toPath(int width, int height, int edge, int numSample) {
        return toPath(null, width, height, edge, numSample);
    }

    public Path toPath(Path path, int width, int height, int edge, int numSample) {
        if (path == null) path = new Path();

        final ArrayList<GestureStroke> strokes = mStrokes;
        final int count = strokes.size();

        for (int i = 0; i < count; i++) {
            path.addPath(strokes.get(i).toPath(width - 2 * edge, height - 2 * edge, numSample));
        }

        return path;
    }

    /**
     * Sets the id of the gesture.
     * 
     * @param id
     */
    void setID(long id) {
        mGestureID = id;
    }

    /**
     * @return the id of the gesture
     */
    public long getID() {
        return mGestureID;
    }

    /**
     * Creates a bitmap of the gesture with a transparent background.
     * 
     * @param width width of the target bitmap
     * @param height height of the target bitmap
     * @param edge the edge
     * @param numSample
     * @param color
     * @return the bitmap
     */
    public Bitmap toBitmap(int width, int height, int edge, int numSample, int color) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);

        canvas.translate(edge, edge);

        final Paint paint = new Paint();
        paint.setAntiAlias(BITMAP_RENDERING_ANTIALIAS);
        paint.setDither(BITMAP_RENDERING_DITHER);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(BITMAP_RENDERING_WIDTH);

        final ArrayList<GestureStroke> strokes = mStrokes;
        final int count = strokes.size();

        for (int i = 0; i < count; i++) {
            Path path = strokes.get(i).toPath(width - 2 * edge, height - 2 * edge, numSample);
            canvas.drawPath(path, paint);
        }

        return bitmap;
    }

    /**
     * Creates a bitmap of the gesture with a transparent background.
     * 
     * @param width
     * @param height
     * @param inset
     * @param color
     * @return the bitmap
     */
    public Bitmap toBitmap(int width, int height, int inset, int color) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);

        final Paint paint = new Paint();
        paint.setAntiAlias(BITMAP_RENDERING_ANTIALIAS);
        paint.setDither(BITMAP_RENDERING_DITHER);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(BITMAP_RENDERING_WIDTH);

        final Path path = toPath();
        final RectF bounds = new RectF();
        path.computeBounds(bounds, true);

        final float sx = (width - 2 * inset) / bounds.width();
        final float sy = (height - 2 * inset) / bounds.height();
        final float scale = sx > sy ? sy : sx;
        paint.setStrokeWidth(2.0f / scale);

        path.offset(-bounds.left + (width - bounds.width() * scale) / 2.0f,
                -bounds.top + (height - bounds.height() * scale) / 2.0f);

        canvas.translate(inset, inset);
        canvas.scale(scale, scale);

        canvas.drawPath(path, paint);

        return bitmap;
    }

    void serialize(DataOutputStream out) throws IOException {
        final ArrayList<GestureStroke> strokes = mStrokes;
        final int count = strokes.size();

        // Write gesture ID
        out.writeLong(mGestureID);
        // Write number of strokes
        out.writeInt(count);

        for (int i = 0; i < count; i++) {
            strokes.get(i).serialize(out);
        }
    }

    static Gesture deserialize(DataInputStream in) throws IOException {
        final Gesture gesture = new Gesture();

        // Gesture ID
        gesture.mGestureID = in.readLong();
        // Number of strokes
        final int count = in.readInt();

        for (int i = 0; i < count; i++) {
            gesture.addStroke(GestureStroke.deserialize(in));
        }

        return gesture;
    }

    public static final Parcelable.Creator<Gesture> CREATOR = new Parcelable.Creator<Gesture>() {
        public Gesture createFromParcel(Parcel in) {
            Gesture gesture = null;
            final long gestureID = in.readLong();

            final DataInputStream inStream = new DataInputStream(
                    new ByteArrayInputStream(in.createByteArray()));

            try {
                gesture = deserialize(inStream);
            } catch (IOException e) {
                Log.e(GestureConstants.LOG_TAG, "Error reading Gesture from parcel:", e);
            } finally {
                GestureUtils.closeStream(inStream);
            }

            if (gesture != null) {
                gesture.mGestureID = gestureID;
            }

            return gesture;
        }

        public Gesture[] newArray(int size) {
            return new Gesture[size];
        }
    };

    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mGestureID);

        boolean result = false;
        final ByteArrayOutputStream byteStream =
                new ByteArrayOutputStream(GestureConstants.IO_BUFFER_SIZE);
        final DataOutputStream outStream = new DataOutputStream(byteStream);

        try {
            serialize(outStream);
            result = true;
        } catch (IOException e) {
            Log.e(GestureConstants.LOG_TAG, "Error writing Gesture to parcel:", e);
        } finally {
            GestureUtils.closeStream(outStream);
            GestureUtils.closeStream(byteStream);
        }

        if (result) {
            out.writeByteArray(byteStream.toByteArray());
        }
    }

    public int describeContents() {
        return 0;
    }
}

