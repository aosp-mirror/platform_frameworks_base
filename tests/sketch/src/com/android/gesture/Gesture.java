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
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.gesture.recognizer.RecognitionUtil;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.StringTokenizer;

/**
 * A single stroke gesture.
 */

public class Gesture implements Parcelable {

    private RectF mBBX;
    private float mLength = 0;
    private int mColor;
    private float mWidth;
    private ArrayList<PointF> mPtsBuffer = new ArrayList<PointF>();
    private long mTimestamp = 0;
    private long mID;
    
    private static final long systemStartupTime = System.currentTimeMillis();
    private static int instanceCount = 0; 

    public Gesture() {
        mID = systemStartupTime + instanceCount++;
    }

    public void setColor(int c) {
        mColor = c;
    }
    
    public void setStrokeWidth(float w) {
        mWidth = w;
    }
    
    public int getColor() {
        return mColor;
    }
    
    public float getStrokeWidth() {
        return mWidth;
    }
  
    public ArrayList<PointF> getPoints() {
        return this.mPtsBuffer;
    }
  
    public int numOfPoints() {
        return this.mPtsBuffer.size();
    }

    public void addPoint(float x, float y) {
        mPtsBuffer.add(new PointF(x, y));
        if (mBBX == null) {
            mBBX = new RectF();
            mBBX.top = y;
            mBBX.left = x;
            mBBX.right = x;
            mBBX.bottom = y;
            mLength = 0;
        }
        else {
            PointF lst = mPtsBuffer.get(mPtsBuffer.size()-2);
            mLength += Math.sqrt(Math.pow(x-lst.x, 2)+Math.pow(y-lst.y, 2));
            mBBX.union(x, y);
        }
        mTimestamp = System.currentTimeMillis();
    }

    /**
     * @return the length of the gesture
     */
    public float getLength() {
        return this.mLength;
    }
  
    public RectF getBBX() {
        return mBBX;
    }
  
    public void setID(long id) {
        mID = id;
    }
    
    public long getID() {
        return mID;
    }
    
    public long getTimeStamp() {
        return mTimestamp;
    }
    
    public void setTimestamp(long t) {
  	    this.mTimestamp = t;
    }
    
    /**
     * draw the gesture
     * @param canvas
     */
    public void draw(Canvas canvas) {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setDither(true);
        paint.setColor(mColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(mWidth);
        
        Path path = null;
        float mX = 0, mY = 0;
        Iterator<PointF> it = mPtsBuffer.iterator();
        while (it.hasNext()) {
          PointF p = it.next();
          float x = p.x;
          float y = p.y;
          if (path == null) {
            path = new Path();
            path.moveTo(x, y);
            mX = x;
            mY = y;
          } else {
            float dx = Math.abs(x - mX);
            float dy = Math.abs(y - mY);
            if (dx >= 3 || dy >= 3) {
                path.quadTo(mX, mY, (x + mX)/2, (y + mY)/2);
                mX = x;
                mY = y;
            }
          }
        }
        
        canvas.drawPath(path, paint);
    }
    
    /**
     * convert the gesture to a Path
     * @param width the width of the bounding box of the target path
     * @param height the height of the bounding box of the target path
     * @param numSample the num of points needed
     * @return the path
     */
    public Path toPath(float width, float height, int numSample) {
        float[] pts = RecognitionUtil.resample(this, numSample);
        RectF rect = this.getBBX();
        float scale = height / rect.height();
        Matrix matrix = new Matrix();
        matrix.setTranslate(-rect.left, -rect.top);
        Matrix scalem = new Matrix();
        scalem.setScale(scale, scale);
        matrix.postConcat(scalem);
        Matrix translate = new Matrix();
        matrix.postConcat(translate);
        matrix.mapPoints(pts);
        
        Path path = null;
        float mX = 0, mY = 0;
        for (int i=0; i<pts.length-1; i+=2) {
          float x = pts[i];
          float y = pts[i+1];
          if (path == null) {
            path = new Path();
            path.moveTo(x, y);
            mX = x;
            mY = y;
          } else {
            float dx = Math.abs(x - mX);
            float dy = Math.abs(y - mY);
            if (dx >= 3 || dy >= 3) {
                path.quadTo(mX, mY, (x + mX)/2, (y + mY)/2);
                mX = x;
                mY = y;
            }
          }
        }
        return path;
    }
  
    /**
     * get a bitmap thumbnail of the gesture with a transparent background
     * @param w
     * @param h
     * @param edge
     * @param numSample
     * @param foreground
     * @return
     */
    public Bitmap toBitmap(int w, int h, 
        int edge, int numSample) {
        RectF bbx = this.getBBX();
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Path path = this.toPath(w - 2 * edge, h - 2 * edge, numSample);
        Canvas c = new Canvas(bitmap);
        //c.drawColor(background);
        c.translate(edge, edge);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setDither(true);
        paint.setColor(mColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2);
        c.drawPath(path, paint);
        return bitmap;
    }
    
    /**
     * save the gesture as XML
     * @param namespace
     * @param serializer
     * @throws IOException
     */
    public void toXML(String namespace, XmlSerializer serializer) throws IOException {
        serializer.startTag(namespace, "stroke");
        serializer.attribute(namespace, "timestamp", Long.toString(mTimestamp));
        serializer.attribute(namespace, "id", Long.toString(mID));
        serializer.attribute(namespace, "color", Integer.toString(mColor));
        serializer.attribute(namespace, "width", Float.toString(mWidth));
        Iterator it = this.mPtsBuffer.iterator();
        String pts = "";
        while (it.hasNext()) {
        	PointF fp = (PointF)it.next();
        	if (pts.length() > 0)
        		pts += ",";
        	pts += fp.x + "," + fp.y;
        }
        serializer.text(pts);
        serializer.endTag(namespace, "stroke");
    }
    
    
    public void createFromString(String str) {
        StringTokenizer st = new StringTokenizer(str, "#");
        
        String para = st.nextToken();
        StringTokenizer innerst = new StringTokenizer(para, ",");
        this.mBBX = new RectF();
        this.mBBX.left = Float.parseFloat(innerst.nextToken());
        this.mBBX.top = Float.parseFloat(innerst.nextToken());
        this.mBBX.right = Float.parseFloat(innerst.nextToken());
        this.mBBX.bottom = Float.parseFloat(innerst.nextToken());
        
        para = st.nextToken();
        innerst = new StringTokenizer(para, ",");
        while (innerst.hasMoreTokens()) {
          String s = innerst.nextToken().trim();
          if (s.length()==0)
            break;
          float x = Float.parseFloat(s);
          float y = Float.parseFloat(innerst.nextToken());
          this.mPtsBuffer.add(new PointF(x, y));
        }
  
        para = st.nextToken();
        this.mColor = Integer.parseInt(para);
        
        para = st.nextToken();
        this.mWidth = Float.parseFloat(para);
        
        para = st.nextToken();
        this.mLength = Float.parseFloat(para);
        
        para = st.nextToken();
        this.mTimestamp = Long.parseLong(para);
    }
    
    @Override
    public String toString() {
        String str = "";
        
        str += "#" + this.mBBX.left + "," + this.mBBX.top + "," +
               this.mBBX.right + "," + this.mBBX.bottom;
        
        str += "#";
        Iterator<PointF> it = this.mPtsBuffer.iterator();
        while (it.hasNext()) {
          PointF fp = it.next();
          str += fp.x + "," + fp.y + ","; 
        }

        str += "#";
        str += this.mColor;
        
        str += "#";
        str += this.mWidth;
        
        str += "#";
        str += this.mLength;
        
        str += "#";
        str += this.mTimestamp;
  
        return str;
    }
    
    public static final Parcelable.Creator CREATOR = new Parcelable.Creator() {
        public Gesture createFromParcel(Parcel in) {
            String str = in.readString();
            Gesture stk = new Gesture();
            stk.createFromString(str);
            return stk;
        }
    
        public Gesture[] newArray(int size) {
            return new Gesture[size];
        }
    };
    
    public static Gesture buildFromArray(byte[] bytes) {
        String str = new String(bytes);
        Gesture stk = new Gesture();
        stk.createFromString(str);
        return stk;
    }
    
    public static byte[] saveToArray(Gesture stk) {
        String str = stk.toString();   
        return str.getBytes();
    }
    
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(this.toString());
    }
      
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }
}
