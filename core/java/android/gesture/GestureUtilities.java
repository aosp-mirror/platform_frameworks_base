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

import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.Closeable;
import java.io.IOException;

import static android.gesture.GestureConstants.*;

final class GestureUtilities {
    private static final int TEMPORAL_SAMPLING_RATE = 16;

    private GestureUtilities() {
    }

    /**
     * Closes the specified stream.
     *
     * @param stream The stream to close.
     */
    static void closeStream(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                Log.e(LOG_TAG, "Could not close stream", e);
            }
        }
    }

    static float[] spatialSampling(Gesture gesture, int sampleMatrixDimension) {
        final float targetPatchSize = sampleMatrixDimension - 1; // edge inclusive
        float[] sample = new float[sampleMatrixDimension * sampleMatrixDimension];
        Arrays.fill(sample, 0);

        RectF rect = gesture.getBoundingBox();
        float sx = targetPatchSize / rect.width();
        float sy = targetPatchSize / rect.height();
        float scale = sx < sy ? sx : sy;

        float preDx = -rect.centerX();
        float preDy = -rect.centerY();
        float postDx = targetPatchSize / 2;
        float postDy = targetPatchSize / 2;

        final ArrayList<GestureStroke> strokes = gesture.getStrokes();
        final int count = strokes.size();

        int size;
        float xpos;
        float ypos;

        for (int index = 0; index < count; index++) {
            final GestureStroke stroke = strokes.get(index);
            float[] strokepoints = stroke.points;
            size = strokepoints.length;

            final float[] pts = new float[size];
             
            for (int i = 0; i < size; i += 2) {
                pts[i] = (strokepoints[i] + preDx) * scale + postDx;
                pts[i + 1] = (strokepoints[i + 1] + preDy) * scale + postDy;
            }
        
            float segmentEndX = -1;
            float segmentEndY = -1;
            
            for (int i = 0; i < size; i += 2) {
                
                float segmentStartX = pts[i] < 0 ? 0 : pts[i];
                float segmentStartY = pts[i + 1] < 0 ? 0 : pts[i + 1];
                
                if (segmentStartX > targetPatchSize) {
                    segmentStartX = targetPatchSize;
                } 
                
                if (segmentStartY > targetPatchSize) {
                    segmentStartY = targetPatchSize;
                }
                 
                plot(segmentStartX, segmentStartY, sample, sampleMatrixDimension);
                
                if (segmentEndX != -1) {
                    // evaluate horizontally
                    if (segmentEndX > segmentStartX) {
                        xpos = (float) Math.ceil(segmentStartX);
                        float slope = (segmentEndY - segmentStartY) / (segmentEndX - segmentStartX);
                        while (xpos < segmentEndX) {
                            ypos = slope * (xpos - segmentStartX) + segmentStartY;
                            plot(xpos, ypos, sample, sampleMatrixDimension); 
                            xpos++;
                        }
                    } else if (segmentEndX < segmentStartX){
                        xpos = (float) Math.ceil(segmentEndX);
                        float slope = (segmentEndY - segmentStartY) / (segmentEndX - segmentStartX);
                        while (xpos < segmentStartX) {
                            ypos = slope * (xpos - segmentStartX) + segmentStartY;
                            plot(xpos, ypos, sample, sampleMatrixDimension); 
                            xpos++;
                        }
                    }

                    // evaluating vertically
                    if (segmentEndY > segmentStartY) {
                        ypos = (float) Math.ceil(segmentStartY);
                        float invertSlope = (segmentEndX - segmentStartX) / (segmentEndY - segmentStartY);
                        while (ypos < segmentEndY) {
                            xpos = invertSlope * (ypos - segmentStartY) + segmentStartX;
                            plot(xpos, ypos, sample, sampleMatrixDimension); 
                            ypos++;
                        }
                    } else if (segmentEndY < segmentStartY) {
                        ypos = (float) Math.ceil(segmentEndY);
                        float invertSlope = (segmentEndX - segmentStartX) / (segmentEndY - segmentStartY);
                        while (ypos < segmentStartY) {
                            xpos = invertSlope * (ypos - segmentStartY) + segmentStartX; 
                            plot(xpos, ypos, sample, sampleMatrixDimension); 
                            ypos++;
                        }
                    }
                } 
                
                segmentEndX = segmentStartX;
                segmentEndY = segmentStartY;
            }
        }


        return sample;
    }

    private static void plot(float x, float y, float[] sample, int sampleSize) {
        x = x < 0 ? 0 : x;
        y = y < 0 ? 0 : y;
        int xFloor = (int) Math.floor(x);
        int xCeiling = (int) Math.ceil(x);
        int yFloor = (int) Math.floor(y);
        int yCeiling = (int) Math.ceil(y);
        
        // if it's an integer
        if (x == xFloor && y == yFloor) {
            int index = yCeiling * sampleSize + xCeiling;
            if (sample[index] < 1){
                sample[index] = 1;
            }
        } else {
            double topLeft = Math.sqrt(Math.pow(xFloor - x, 2) + Math.pow(yFloor - y, 2));
            double topRight = Math.sqrt(Math.pow(xCeiling - x, 2) + Math.pow(yFloor - y, 2));
            double btmLeft = Math.sqrt(Math.pow(xFloor - x, 2) + Math.pow(yCeiling - y, 2));
            double btmRight = Math.sqrt(Math.pow(xCeiling - x, 2) + Math.pow(yCeiling - y, 2));
            double sum = topLeft + topRight + btmLeft + btmRight;
            
            double value = topLeft / sum;
            int index = yFloor * sampleSize + xFloor;
            if (value > sample[index]){
                sample[index] = (float) value;
            }
            
            value = topRight / sum;
            index = yFloor * sampleSize + xCeiling;
            if (value > sample[index]){
                sample[index] = (float) value;
            }
            
            value = btmLeft / sum;
            index = yCeiling * sampleSize + xFloor;
            if (value > sample[index]){
                sample[index] = (float) value;
            }
            
            value = btmRight / sum;
            index = yCeiling * sampleSize + xCeiling;
            if (value > sample[index]){
                sample[index] = (float) value;
            }
        }
    }
    
    /**
     * Featurize a stroke into a vector of a given number of elements
     * 
     * @param stroke
     * @param sampleSize
     * @return a float array
     */
    static float[] temporalSampling(GestureStroke stroke, int sampleSize) {
        final float increment = stroke.length / (sampleSize - 1);
        int vectorLength = sampleSize * 2;
        float[] vector = new float[vectorLength];
        float distanceSoFar = 0;
        float[] pts = stroke.points;
        float lstPointX = pts[0];
        float lstPointY = pts[1];
        int index = 0;
        float currentPointX = Float.MIN_VALUE;
        float currentPointY = Float.MIN_VALUE;
        vector[index] = lstPointX;
        index++;
        vector[index] = lstPointY;
        index++;
        int i = 0;
        int count = pts.length / 2;
        while (i < count) {
            if (currentPointX == Float.MIN_VALUE) {
                i++;
                if (i >= count) {
                    break;
                }
                currentPointX = pts[i * 2];
                currentPointY = pts[i * 2 + 1];
            }
            float deltaX = currentPointX - lstPointX;
            float deltaY = currentPointY - lstPointY;
            float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
            if (distanceSoFar + distance >= increment) {
                float ratio = (increment - distanceSoFar) / distance;
                float nx = lstPointX + ratio * deltaX;
                float ny = lstPointY + ratio * deltaY;
                vector[index] = nx;
                index++;
                vector[index] = ny;
                index++;
                lstPointX = nx;
                lstPointY = ny;
                distanceSoFar = 0;
            } else {
                lstPointX = currentPointX;
                lstPointY = currentPointY;
                currentPointX = Float.MIN_VALUE;
                currentPointY = Float.MIN_VALUE;
                distanceSoFar += distance;
            }
        }

        for (i = index; i < vectorLength; i += 2) {
            vector[i] = lstPointX;
            vector[i + 1] = lstPointY;
        }
        return vector;
    }

    /**
     * Calculate the centroid 
     * 
     * @param points
     * @return the centroid
     */
    static float[] computeCentroid(float[] points) {
        float centerX = 0;
        float centerY = 0;
        int count = points.length;
        for (int i = 0; i < count; i++) {
            centerX += points[i];
            i++;
            centerY += points[i];
        }
        float[] center = new float[2];
        center[0] = 2 * centerX / count;
        center[1] = 2 * centerY / count;

        return center;
    }

    /**
     * calculate the variance-covariance matrix, treat each point as a sample
     * 
     * @param points
     * @return the covariance matrix
     */
    private static double[][] computeCoVariance(float[] points) {
        double[][] array = new double[2][2];
        array[0][0] = 0;
        array[0][1] = 0;
        array[1][0] = 0;
        array[1][1] = 0;
        int count = points.length;
        for (int i = 0; i < count; i++) {
            float x = points[i];
            i++;
            float y = points[i];
            array[0][0] += x * x;
            array[0][1] += x * y;
            array[1][0] = array[0][1];
            array[1][1] += y * y;
        }
        array[0][0] /= (count / 2);
        array[0][1] /= (count / 2);
        array[1][0] /= (count / 2);
        array[1][1] /= (count / 2);

        return array;
    }

    static float computeTotalLength(float[] points) {
        float sum = 0;
        int count = points.length - 4;
        for (int i = 0; i < count; i += 2) {
            float dx = points[i + 2] - points[i];
            float dy = points[i + 3] - points[i + 1];
            sum += Math.sqrt(dx * dx + dy * dy);
        }
        return sum;
    }

    static double computeStraightness(float[] points) {
        float totalLen = computeTotalLength(points);
        float dx = points[2] - points[0];
        float dy = points[3] - points[1];
        return Math.sqrt(dx * dx + dy * dy) / totalLen;
    }

    static double computeStraightness(float[] points, float totalLen) {
        float dx = points[2] - points[0];
        float dy = points[3] - points[1];
        return Math.sqrt(dx * dx + dy * dy) / totalLen;
    }

    /**
     * Calculate the squared Euclidean distance between two vectors
     * 
     * @param vector1
     * @param vector2
     * @return the distance
     */
    static double squaredEuclideanDistance(float[] vector1, float[] vector2) {
        double squaredDistance = 0;
        int size = vector1.length;
        for (int i = 0; i < size; i++) {
            float difference = vector1[i] - vector2[i];
            squaredDistance += difference * difference;
        }
        return squaredDistance / size;
    }

    /**
     * Calculate the cosine distance between two instances
     * 
     * @param vector1
     * @param vector2
     * @return the distance between 0 and Math.PI
     */
    static double cosineDistance(float[] vector1, float[] vector2) {
        float sum = 0;
        int len = vector1.length;
        for (int i = 0; i < len; i++) {
            sum += vector1[i] * vector2[i];
        }
        return Math.acos(sum);
    }

    static OrientedBoundingBox computeOrientedBoundingBox(ArrayList<GesturePoint> pts) {
        GestureStroke stroke = new GestureStroke(pts);
        float[] points = temporalSampling(stroke, TEMPORAL_SAMPLING_RATE);
        return computeOrientedBoundingBox(points);
    }

    static OrientedBoundingBox computeOrientedBoundingBox(float[] points) {
        float[] meanVector = computeCentroid(points);
        return computeOrientedBoundingBox(points, meanVector);
    }

    static OrientedBoundingBox computeOrientedBoundingBox(float[] points, float[] centroid) {
        translate(points, -centroid[0], -centroid[1]);

        double[][] array = computeCoVariance(points);
        double[] targetVector = computeOrientation(array);

        float angle;
        if (targetVector[0] == 0 && targetVector[1] == 0) {
            angle = (float) -Math.PI/2;
        } else { // -PI<alpha<PI
            angle = (float) Math.atan2(targetVector[1], targetVector[0]);
            rotate(points, -angle);
        }

        float minx = Float.MAX_VALUE;
        float miny = Float.MAX_VALUE;
        float maxx = Float.MIN_VALUE;
        float maxy = Float.MIN_VALUE;
        int count = points.length;
        for (int i = 0; i < count; i++) {
            if (points[i] < minx) {
                minx = points[i];
            }
            if (points[i] > maxx) {
                maxx = points[i];
            }
            i++;
            if (points[i] < miny) {
                miny = points[i];
            }
            if (points[i] > maxy) {
                maxy = points[i];
            }
        }

        return new OrientedBoundingBox((float) (angle * 180 / Math.PI), centroid[0], centroid[1], maxx - minx, maxy - miny);
    }

    private static double[] computeOrientation(double[][] covarianceMatrix) {
        double[] targetVector = new double[2];
        if (covarianceMatrix[0][1] == 0 || covarianceMatrix[1][0] == 0) {
            targetVector[0] = 1;
            targetVector[1] = 0;
        }

        double a = -covarianceMatrix[0][0] - covarianceMatrix[1][1];
        double b = covarianceMatrix[0][0] * covarianceMatrix[1][1] - covarianceMatrix[0][1]
                * covarianceMatrix[1][0];
        double value = a / 2;
        double rightside = Math.sqrt(Math.pow(value, 2) - b);
        double lambda1 = -value + rightside;
        double lambda2 = -value - rightside;
        if (lambda1 == lambda2) {
            targetVector[0] = 0;
            targetVector[1] = 0;
        } else {
            double lambda = lambda1 > lambda2 ? lambda1 : lambda2;
            targetVector[0] = 1;
            targetVector[1] = (lambda - covarianceMatrix[0][0]) / covarianceMatrix[0][1];
        }
        return targetVector;
    }
    
    
    static float[] rotate(float[] points, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        int size = points.length;
        for (int i = 0; i < size; i += 2) {
            float x = (float) (points[i] * cos - points[i + 1] * sin);
            float y = (float) (points[i] * sin + points[i + 1] * cos);
            points[i] = x;
            points[i + 1] = y;
        }
        return points;
    }
    
    static float[] translate(float[] points, float dx, float dy) {
        int size = points.length;
        for (int i = 0; i < size; i += 2) {
            points[i] += dx;
            points[i + 1] += dy;
        }
        return points;
    }
    
    static float[] scale(float[] points, float sx, float sy) {
        int size = points.length;
        for (int i = 0; i < size; i += 2) {
            points[i] *= sx;
            points[i + 1] *= sy;
        }
        return points;
    }
}
