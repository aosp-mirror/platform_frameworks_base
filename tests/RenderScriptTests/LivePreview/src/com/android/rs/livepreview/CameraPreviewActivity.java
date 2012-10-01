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
package com.android.rs.livepreview;

//import com.android.cts.verifier.PassFailButtons;
//import com.android.cts.verifier.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.TextureView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;

import java.io.IOException;
import java.lang.InterruptedException;
import java.lang.Math;
import java.lang.Thread;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import android.renderscript.*;

/**
 * Tests for manual verification of the CDD-required camera output formats
 * for preview callbacks
 */
public class CameraPreviewActivity extends Activity
        implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {

    private static final String TAG = "CameraFormats";

    private TextureView mPreviewView;
    private SurfaceTexture mPreviewTexture;
    private int mPreviewTexWidth;
    private int mPreviewTexHeight;

    private ImageView mFormatView;

    private Spinner mCameraSpinner;
    private Spinner mResolutionSpinner;

    private int mCurrentCameraId = -1;
    private Camera mCamera;

    private List<Camera.Size> mPreviewSizes;
    private Camera.Size mNextPreviewSize;
    private Camera.Size mPreviewSize;

    private Bitmap mCallbackBitmap;

    private static final int STATE_OFF = 0;
    private static final int STATE_PREVIEW = 1;
    private static final int STATE_NO_CALLBACKS = 2;
    private int mState = STATE_OFF;
    private boolean mProcessInProgress = false;
    private boolean mProcessingFirstFrame = false;


    private RenderScript mRS;
    private RsYuv mFilterYuv;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.cf_main);

        mPreviewView = (TextureView) findViewById(R.id.preview_view);
        mFormatView = (ImageView) findViewById(R.id.format_view);

        mPreviewView.setSurfaceTextureListener(this);

        int numCameras = Camera.getNumberOfCameras();
        String[] cameraNames = new String[numCameras];
        for (int i = 0; i < numCameras; i++) {
            cameraNames[i] = "Camera " + i;
        }
        mCameraSpinner = (Spinner) findViewById(R.id.cameras_selection);
        mCameraSpinner.setAdapter(
            new ArrayAdapter<String>(
                this, R.layout.cf_format_list_item, cameraNames));
        mCameraSpinner.setOnItemSelectedListener(mCameraSpinnerListener);

        mResolutionSpinner = (Spinner) findViewById(R.id.resolution_selection);
        mResolutionSpinner.setOnItemSelectedListener(mResolutionSelectedListener);


        mRS = RenderScript.create(this);
    }

    @Override
    public void onResume() {
        super.onResume();

        setUpCamera(mCameraSpinner.getSelectedItemPosition());
    }

    @Override
    public void onPause() {
        super.onPause();

        shutdownCamera();
    }

    public void onSurfaceTextureAvailable(SurfaceTexture surface,
            int width, int height) {
        mPreviewTexture = surface;
        mPreviewTexWidth = width;
        mPreviewTexHeight = height;
        if (mCamera != null) {
            startPreview();
        }
    }

    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored, Camera does all the work for us
    }

    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Invoked every time there's a new Camera preview frame
    }

    private AdapterView.OnItemSelectedListener mCameraSpinnerListener =
            new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent,
                        View view, int pos, long id) {
                    if (mCurrentCameraId != pos) {
                        setUpCamera(pos);
                    }
                }

                public void onNothingSelected(AdapterView parent) {

                }

            };

    private AdapterView.OnItemSelectedListener mResolutionSelectedListener =
            new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent,
                        View view, int position, long id) {
                    if (mPreviewSizes.get(position) != mPreviewSize) {
                        mNextPreviewSize = mPreviewSizes.get(position);
                        startPreview();
                    }
                }

                public void onNothingSelected(AdapterView parent) {

                }

            };


    private void setUpCamera(int id) {
        shutdownCamera();

        mCurrentCameraId = id;
        mCamera = Camera.open(id);
        Camera.Parameters p = mCamera.getParameters();

        // Get preview resolutions

        List<Camera.Size> unsortedSizes = p.getSupportedPreviewSizes();

        class SizeCompare implements Comparator<Camera.Size> {
            public int compare(Camera.Size lhs, Camera.Size rhs) {
                if (lhs.width < rhs.width) return -1;
                if (lhs.width > rhs.width) return 1;
                if (lhs.height < rhs.height) return -1;
                if (lhs.height > rhs.height) return 1;
                return 0;
            }
        };

        SizeCompare s = new SizeCompare();
        TreeSet<Camera.Size> sortedResolutions = new TreeSet<Camera.Size>(s);
        sortedResolutions.addAll(unsortedSizes);

        mPreviewSizes = new ArrayList<Camera.Size>(sortedResolutions);

        String[] availableSizeNames = new String[mPreviewSizes.size()];
        for (int i = 0; i < mPreviewSizes.size(); i++) {
            availableSizeNames[i] =
                    Integer.toString(mPreviewSizes.get(i).width) + " x " +
                    Integer.toString(mPreviewSizes.get(i).height);
        }
        mResolutionSpinner.setAdapter(
            new ArrayAdapter<String>(
                this, R.layout.cf_format_list_item, availableSizeNames));


        // Set initial values

        mNextPreviewSize = mPreviewSizes.get(0);
        mResolutionSpinner.setSelection(0);

        if (mPreviewTexture != null) {
            startPreview();
        }
    }

    private void shutdownCamera() {
        if (mCamera != null) {
            mCamera.setPreviewCallbackWithBuffer(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            mState = STATE_OFF;
        }
    }

    private void startPreview() {
        if (mState != STATE_OFF) {
            // Stop for a while to drain callbacks
            mCamera.setPreviewCallbackWithBuffer(null);
            mCamera.stopPreview();
            mState = STATE_OFF;
            Handler h = new Handler();
            Runnable mDelayedPreview = new Runnable() {
                public void run() {
                    startPreview();
                }
            };
            h.postDelayed(mDelayedPreview, 300);
            return;
        }
        mState = STATE_PREVIEW;

        Matrix transform = new Matrix();
        float widthRatio = mNextPreviewSize.width / (float)mPreviewTexWidth;
        float heightRatio = mNextPreviewSize.height / (float)mPreviewTexHeight;

        transform.setScale(1, heightRatio/widthRatio);
        transform.postTranslate(0,
                mPreviewTexHeight * (1 - heightRatio/widthRatio)/2);

        mPreviewView.setTransform(transform);

        mPreviewSize   = mNextPreviewSize;

        Camera.Parameters p = mCamera.getParameters();
        p.setPreviewFormat(ImageFormat.NV21);
        p.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
        mCamera.setParameters(p);

        mCamera.setPreviewCallbackWithBuffer(this);
        int expectedBytes = mPreviewSize.width * mPreviewSize.height *
                ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
        for (int i=0; i < 4; i++) {
            mCamera.addCallbackBuffer(new byte[expectedBytes]);
        }
        //mFormatView.setColorFilter(mYuv2RgbFilter);

        mProcessingFirstFrame = true;
        try {
            mCamera.setPreviewTexture(mPreviewTexture);
            mCamera.startPreview();
        } catch (IOException ioe) {
            // Something bad happened
            Log.e(TAG, "Unable to start up preview");
        }

    }


    private class ProcessPreviewDataTask extends AsyncTask<byte[], Void, Boolean> {
        protected Boolean doInBackground(byte[]... datas) {
            byte[] data = datas[0];

            long t1 = java.lang.System.currentTimeMillis();

            mFilterYuv.execute(data, mCallbackBitmap);

            long t2 = java.lang.System.currentTimeMillis();
            mTiming[mTimingSlot++] = t2 - t1;
            if (mTimingSlot >= mTiming.length) {
                float total = 0;
                for (int i=0; i<mTiming.length; i++) {
                    total += (float)mTiming[i];
                }
                total /= mTiming.length;
                Log.e(TAG, "time + " + total);
                mTimingSlot = 0;
            }

            mCamera.addCallbackBuffer(data);
            mProcessInProgress = false;
            return true;
        }

        protected void onPostExecute(Boolean result) {
            mFormatView.invalidate();
        }

    }

    private long mTiming[] = new long[50];
    private int mTimingSlot = 0;

    public void onPreviewFrame(byte[] data, Camera camera) {
        if (mProcessInProgress || mState != STATE_PREVIEW) {
            mCamera.addCallbackBuffer(data);
            return;
        }
        if (data == null) {
            return;
        }

        int expectedBytes = mPreviewSize.width * mPreviewSize.height *
                ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;

        if (expectedBytes != data.length) {
            Log.e(TAG, "Mismatched size of buffer! Expected ");

            mState = STATE_NO_CALLBACKS;
            mCamera.setPreviewCallbackWithBuffer(null);
            return;
        }

        mProcessInProgress = true;

        if (mCallbackBitmap == null ||
                mPreviewSize.width != mCallbackBitmap.getWidth() ||
                mPreviewSize.height != mCallbackBitmap.getHeight() ) {
            mCallbackBitmap =
                    Bitmap.createBitmap(
                        mPreviewSize.width, mPreviewSize.height,
                        Bitmap.Config.ARGB_8888);
            mFilterYuv = new RsYuv(mRS, getResources(), mPreviewSize.width, mPreviewSize.height);
            mFormatView.setImageBitmap(mCallbackBitmap);
        }


        mFormatView.invalidate();

        mCamera.addCallbackBuffer(data);
        mProcessInProgress = true;
        new ProcessPreviewDataTask().execute(data);
    }



}