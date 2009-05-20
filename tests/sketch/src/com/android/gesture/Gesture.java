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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;

/**
 * A gesture can have a single or multiple strokes
 */

public class Gesture implements Parcelable {
    private static final long GESTURE_ID_BASE = System.currentTimeMillis();

    private static final int BITMAP_RENDERING_WIDTH = 2;

    private static final boolean BITMAP_RENDERING_ANTIALIAS = true;
    private static final boolean BITMAP_RENDERING_DITHER = true;

    private static int sGestureCount = 0;

    private RectF mBoundingBox;

    // the same as its instance ID
    private long mGestureID;

    private final ArrayList<GestureStroke> mStrokes = new ArrayList<GestureStroke>();

    public Gesture() {
        mGestureID = GESTURE_ID_BASE + sGestureCount++;
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
     * Add a stroke to the gesture
     * 
     * @param stroke
     */
    public void addStroke(GestureStroke stroke) {
        mStrokes.add(stroke);

        if (mBoundingBox == null) {
            mBoundingBox = new RectF(stroke.boundingBox);
        } else {
            mBoundingBox.union(stroke.boundingBox);
        }
    }

    /**
     * Get the total length of the gesture. When there are multiple strokes in
     * the gesture, this returns the sum of the lengths of all the strokes
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

    /**
     * Set the id of the gesture
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
     * draw the gesture
     * 
     * @param canvas
     */
    void draw(Canvas canvas, Paint paint) {
        final ArrayList<GestureStroke> strokes = mStrokes;
        final int count = strokes.size();

        for (int i = 0; i < count; i++) {
            strokes.get(i).draw(canvas, paint);
        }
    }

    /**
     * Create a bitmap of the gesture with a transparent background
     * 
     * @param width width of the target bitmap
     * @param height height of the target bitmap
     * @param edge the edge
     * @param numSample
     * @param color
     * @return the bitmap
     */
    public Bitmap toBitmap(int width, int height, int edge, int numSample, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(edge, edge);
        Paint paint = new Paint();
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
     * Create a bitmap of the gesture with a transparent background
     * 
     * @param width
     * @param height
     * @param edge
     * @param color
     * @return the bitmap
     */
    public Bitmap toBitmap(int width, int height, int edge, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(edge, edge);
        Paint paint = new Paint();
        paint.setAntiAlias(BITMAP_RENDERING_ANTIALIAS);
        paint.setDither(BITMAP_RENDERING_DITHER);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(BITMAP_RENDERING_WIDTH);
        ArrayList<GestureStroke> strokes = mStrokes;
        int count = strokes.size();
        for (int i = 0; i < count; i++) {
            GestureStroke stroke = strokes.get(i);
            stroke.draw(canvas, paint);
        }

        return bitmap;
    }

    /**
     * Save the gesture as XML
     * 
     * @param namespace
     * @param serializer
     * @throws IOException
     */
    void toXML(String namespace, XmlSerializer serializer) throws IOException {
        serializer.startTag(namespace, GestureConstants.XML_TAG_GESTURE);
        serializer.attribute(namespace, GestureConstants.XML_TAG_ID, Long.toString(mGestureID));
        ArrayList<GestureStroke> strokes = mStrokes;
        int count = strokes.size();
        for (int i = 0; i < count; i++) {
            GestureStroke stroke = strokes.get(i);
            stroke.toXML(namespace, serializer);
        }
        serializer.endTag(namespace, GestureConstants.XML_TAG_GESTURE);
    }

    /**
     * Create the gesture from a string
     * 
     * @param str
     */
    public void createFromString(String str) {
        int startIndex = 0;
        int endIndex;
        while ((endIndex =
                str.indexOf(GestureConstants.STRING_GESTURE_DELIIMITER, startIndex + 1)) != -1) {
            String token = str.substring(startIndex, endIndex);
            if (startIndex > 0) { // stroke tokens
                addStroke(GestureStroke.createFromString(token));
            } else { // id token
                mGestureID = Long.parseLong(token);
            }
            startIndex = endIndex + 1;
        }
    }

    /**
     * Convert the gesture to string
     */
    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append(mGestureID);
        ArrayList<GestureStroke> strokes = mStrokes;
        int count = strokes.size();
        for (int i = 0; i < count; i++) {
            GestureStroke stroke = strokes.get(i);
            str.append(GestureConstants.STRING_GESTURE_DELIIMITER);
            str.append(stroke.toString());
        }

        return str.toString();
    }

    public static final Parcelable.Creator<Gesture> CREATOR = new Parcelable.Creator<Gesture>() {
        public Gesture createFromParcel(Parcel in) {
            String str = in.readString();
            Gesture gesture = new Gesture();
            gesture.createFromString(str);
            return gesture;
        }

        public Gesture[] newArray(int size) {
            return new Gesture[size];
        }
    };

    /**
     * Build a gesture from a byte array
     * 
     * @param bytes
     * @return the gesture
     */
    static Gesture buildFromArray(byte[] bytes) {
        String str = new String(bytes);
        Gesture gesture = new Gesture();
        gesture.createFromString(str);
        return gesture;
    }

    /**
     * Save a gesture to a byte array
     * 
     * @param stroke
     * @return the byte array
     */
    static byte[] saveToArray(Gesture stroke) {
        String str = stroke.toString();
        return str.getBytes();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(toString());
    }

    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }
}
