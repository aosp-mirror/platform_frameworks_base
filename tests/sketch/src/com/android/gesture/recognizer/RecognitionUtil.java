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

package com.android.gesture.recognizer;

import android.graphics.PointF;

import com.android.gesture.Gesture;

import java.util.Iterator;

/**
 * 
 * Utilities for recognition.
 */

public class RecognitionUtil {
  
    /**
     * Re-sample a list of points to a given number
     * @param stk
     * @param num
     * @return
     */
    public static float[] resample(Gesture gesture, int num) {
        final float increment = gesture.getLength()/(num - 1);
        float[] newstk = new float[num*2];
        float distanceSoFar = 0;
        Iterator<PointF> it = gesture.getPoints().iterator();
        PointF lstPoint = it.next();
        int index = 0;
        PointF currentPoint = null;
        try
        {
            newstk[index] = lstPoint.x;
            index++;
            newstk[index] = lstPoint.y;
            index++;
            while (it.hasNext()) {
                if (currentPoint == null)
                    currentPoint = it.next();
                float deltaX = currentPoint.x - lstPoint.x;
                float deltaY = currentPoint.y - lstPoint.y;
                float distance = (float)Math.sqrt(deltaX*deltaX+deltaY*deltaY);
                if (distanceSoFar+distance >= increment) {
                    float ratio = (increment - distanceSoFar) / distance;
                    float nx = lstPoint.x + ratio * deltaX;
                    float ny = lstPoint.y + ratio * deltaY;
                    newstk[index] = nx;
                    index++;
                    newstk[index] = ny;
                    index++;
                    lstPoint = new PointF(nx, ny);
                    distanceSoFar = 0;
                }
                else {
                    lstPoint = currentPoint;
                    currentPoint = null;
                    distanceSoFar += distance;
                }
            }
        }
        catch(Exception ex) {
            ex.printStackTrace();
        }
    
        for(int i = index; i < newstk.length -1; i+=2) {
            newstk[i] = lstPoint.x;
            newstk[i+1] = lstPoint.y;
        }
        return newstk;
    }

    /**
     * Calculate the centroid of a list of points
     * @param points
     * @return the centroid
     */
    public static PointF computeCentroid(float[] points) {
        float centerX = 0;
        float centerY = 0;
        for(int i=0; i<points.length; i++)
        {
            centerX += points[i];
            i++;
            centerY += points[i];
        }
        centerX = 2 * centerX/points.length;
        centerY = 2 * centerY/points.length;
        return new PointF(centerX, centerY);
    }

    /**
     * calculate the variance-covariance matrix, treat each point as a sample
     * @param points
     * @return
     */
    public static double[][] computeCoVariance(float[] points) {
        double[][] array = new double[2][2];
        array[0][0] = 0;
        array[0][1] = 0;
        array[1][0] = 0;
        array[1][1] = 0;
        for(int i=0; i<points.length; i++)
        {
            float x = points[i];
            i++;
            float y = points[i];
            array[0][0] += x * x;
            array[0][1] += x * y;
            array[1][0] = array[0][1];
            array[1][1] += y * y;
        }
        array[0][0] /= (points.length/2);
        array[0][1] /= (points.length/2);
        array[1][0] /= (points.length/2);
        array[1][1] /= (points.length/2);
        
        return array;
    }
    

    public static float computeTotalLength(float[] points) {
        float sum = 0;
        for (int i=0; i<points.length - 4; i+=2) {
            float dx = points[i+2] - points[i];
            float dy = points[i+3] - points[i+1];
            sum += Math.sqrt(dx*dx + dy*dy);
        }
        return sum;
    }
    
    public static double computeStraightness(float[] points) {
        float totalLen = computeTotalLength(points);
        float dx = points[2] - points[0];
        float dy = points[3] - points[1];
        return Math.sqrt(dx*dx + dy*dy) / totalLen;
    }

    public static double computeStraightness(float[] points, float totalLen) {
        float dx = points[2] - points[0];
        float dy = points[3] - points[1];
        return Math.sqrt(dx*dx + dy*dy) / totalLen;
    }

    public static double averageEuclidDistance(float[] stk1, float[] stk2) {
        double distance = 0;
        for (int i = 0; i < stk1.length; i += 2) {
            distance += PointF.length(stk1[i] - stk2[i], stk1[i+1] - stk2[i+1]);
        }
        return distance/stk1.length;
    }
    
    public static double squaredEuclidDistance(float[] stk1, float[] stk2) {
        double squaredDistance = 0;
        for (int i = 0; i < stk1.length; i++) {
            float difference = stk1[i] - stk2[i];
            squaredDistance += difference * difference;
        }
        return squaredDistance/stk1.length;
    }
    
    /**
     * Calculate the cosine distance between two instances
     * @param in1
     * @param in2
     * @return the angle between 0 and Math.PI
     */
    public static double cosineDistance(Instance in1, Instance in2) {
        float sum = 0;
        for (int i = 0; i < in1.vector.length; i++) {
            sum += in1.vector[i] * in2.vector[i];
        }
        return Math.acos(sum / (in1.length * in2.length));
    }

}
