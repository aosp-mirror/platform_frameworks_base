/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.media;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.Log;

import java.lang.IllegalArgumentException;

/**
 * Identifies the faces of people in a 
 * {@link android.graphics.Bitmap} graphic object.
 */
public class FaceDetector {

    /**
     * A Face contains all the information identifying the location
     * of a face in a bitmap.
     */
    public class Face {
        /** The minimum confidence factor of good face recognition */
        public static final float CONFIDENCE_THRESHOLD = 0.4f;
        /** The x-axis Euler angle of a face. */
        public static final int EULER_X = 0;
        /** The y-axis Euler angle of a face. */
        public static final int EULER_Y = 1;
        /** The z-axis Euler angle of a face. */
        public static final int EULER_Z = 2;

        /** 
         * Returns a confidence factor between 0 and 1. This indicates how
         * certain what has been found is actually a face. A confidence
         * factor above 0.3 is usually good enough.
         */
        public float confidence() {
            return mConfidence;
        }
        /**
         * Sets the position of the mid-point between the eyes.
         * @param point the PointF coordinates (float values) of the 
         *              face's mid-point
         */
        public void getMidPoint(PointF point) {
            // don't return a PointF to avoid allocations
            point.set(mMidPointX, mMidPointY);
        }
        /**
         * Returns the distance between the eyes.
         */
        public float eyesDistance() {
            return mEyesDist;
        }
        /**
         * Returns the face's pose. That is, the rotations around either 
         * the X, Y or Z axis (the positions in 3-dimensional Euclidean space).
         * 
         * @param euler the Euler axis to retrieve an angle from 
         *              (<var>EULER_X</var>, <var>EULER_Y</var> or 
         *              <var>EULER_Z</var>)
         * @return the Euler angle of the of the face, for the given axis
         */
        public float pose(int euler) {
            // don't use an array to avoid allocations
            if (euler == EULER_X)
                return mPoseEulerX;
            else if (euler == EULER_Y)
                return mPoseEulerY;
            else if (euler == EULER_Z)
                return mPoseEulerZ;
           throw new IllegalArgumentException();
        }

        // private ctor, user not supposed to build this object
        private Face() {
        }
        private float   mConfidence;
        private float   mMidPointX;
        private float   mMidPointY;
        private float   mEyesDist;
        private float   mPoseEulerX;
        private float   mPoseEulerY;
        private float   mPoseEulerZ;
    }


    /**
     * Creates a FaceDetector, configured with the size of the images to
     * be analysed and the maximum number of faces that can be detected.
     * These parameters cannot be changed once the object is constructed.
     * Note that the width of the image must be even.
     * 
     * @param width  the width of the image
     * @param height the height of the image
     * @param maxFaces the maximum number of faces to identify
     *
     */
    public FaceDetector(int width, int height, int maxFaces)
    {
        if (!sInitialized) {
            return;
        }
        fft_initialize(width, height, maxFaces);
        mWidth = width;
        mHeight = height;
        mMaxFaces = maxFaces;
        mBWBuffer = new byte[width * height];
    }

    /**
     * Finds all the faces found in a given {@link android.graphics.Bitmap}. 
     * The supplied array is populated with {@link FaceDetector.Face}s for each
     * face found. The bitmap must be in 565 format (for now).
     * 
     * @param bitmap the {@link android.graphics.Bitmap} graphic to be analyzed
     * @param faces  an array in which to place all found 
     *               {@link FaceDetector.Face}s. The array must be sized equal
     *               to the <var>maxFaces</var> value set at initialization
     * @return the number of faces found
     * @throws IllegalArgumentException if the Bitmap dimensions don't match
     *               the dimensions defined at initialization or the given array 
     *               is not sized equal to the <var>maxFaces</var> value defined
     *               at initialization
     */
    public int findFaces(Bitmap bitmap, Face[] faces)
    {
        if (!sInitialized) {
            return 0;
        }
        if (bitmap.getWidth() != mWidth || bitmap.getHeight() != mHeight) {
            throw new IllegalArgumentException(
                    "bitmap size doesn't match initialization");
        }
        if (faces.length < mMaxFaces) {
            throw new IllegalArgumentException(
                    "faces[] smaller than maxFaces");
        }
        
        int numFaces = fft_detect(bitmap);
        if (numFaces >= mMaxFaces)
            numFaces = mMaxFaces;
        for (int i=0 ; i<numFaces ; i++) {
            if (faces[i] == null)
                faces[i] = new Face();
            fft_get_face(faces[i], i);
        }
        return numFaces;
    }


    /* no user serviceable parts here ... */
    @Override
    protected void finalize() throws Throwable {
        fft_destroy();
    }

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    private static boolean sInitialized;
    native private static void nativeClassInit();

    static {
        sInitialized = false;
        try {
            System.loadLibrary("FFTEm");
            nativeClassInit();
            sInitialized = true;
        } catch (UnsatisfiedLinkError e) {
            Log.d("FFTEm", "face detection library not found!");
        }
    }

    native private int  fft_initialize(int width, int height, int maxFaces);
    native private int  fft_detect(Bitmap bitmap);
    native private void fft_get_face(Face face, int i);
    native private void fft_destroy();

    private long    mFD;
    private long    mSDK;
    private long    mDCR;
    private int     mWidth;
    private int     mHeight;
    private int     mMaxFaces;    
    private byte    mBWBuffer[];
}

