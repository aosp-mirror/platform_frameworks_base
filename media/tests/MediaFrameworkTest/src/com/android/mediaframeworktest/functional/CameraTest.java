/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mediaframeworktest.functional;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.ConditionVariable;
import android.os.Environment;
import android.os.Looper;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.*;

/**
 * Junit / Instrumentation test case for the camera api
 
 */  
public class CameraTest extends ActivityInstrumentationTestCase<MediaFrameworkTest> {    
    private String TAG = "CameraTest";
    
    private boolean rawPreviewCallbackResult = false;
    private boolean shutterCallbackResult = false;
    private boolean rawPictureCallbackResult = false;
    private boolean jpegPictureCallbackResult = false;
    
    private static int WAIT_FOR_COMMAND_TO_COMPLETE = 10000;  // Milliseconds.
    
    private RawPreviewCallback mRawPreviewCallback = new RawPreviewCallback();
    private TestShutterCallback mShutterCallback = new TestShutterCallback();
    private RawPictureCallback mRawPictureCallback = new RawPictureCallback();
    private JpegPictureCallback mJpegPictureCallback = new JpegPictureCallback();
    
    private boolean mInitialized = false;
    private Looper mLooper = null;
    private final ConditionVariable mPreviewDone = new ConditionVariable();
    private final ConditionVariable mSnapshotDone = new ConditionVariable();
    
    Camera mCamera;
    Context mContext;
  
    public CameraTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    protected void setUp() throws Exception {
        super.setUp(); 
    }
   
    /*
     * Initializes the message looper so that the Camera object can 
     * receive the callback messages.
     */
    private void initializeMessageLooper() {
        final ConditionVariable startDone = new ConditionVariable();
        Log.v(TAG, "start looper");
        new Thread() {
            @Override
            public void run() {
                // Set up a looper to be used by camera.
                Looper.prepare();
                Log.v(TAG, "start loopRun");
                // Save the looper so that we can terminate this thread 
                // after we are done with it.
                mLooper = Looper.myLooper();
                mCamera = Camera.open();
                startDone.open();
                Looper.loop();  // Blocks forever until Looper.quit() is called.
                Log.v(TAG, "initializeMessageLooper: quit.");
            }
        }.start();

        if (!startDone.block(WAIT_FOR_COMMAND_TO_COMPLETE)) {
            fail("initializeMessageLooper: start timeout");
        }
    }
    
    /*
     * Terminates the message looper thread.
     */
    private void terminateMessageLooper() throws Exception {
        mLooper.quit();
        // Looper.quit() is asynchronous. The looper may still has some
        // preview callbacks in the queue after quit is called. The preview
        // callback still uses the camera object (setHasPreviewCallback).
        // After camera is released, RuntimeException will be thrown from
        // the method. So we need to join the looper thread here.
        mLooper.getThread().join();
        mCamera.release();
    }
    
    //Implement the previewCallback
    private final class RawPreviewCallback implements PreviewCallback { 
        public void onPreviewFrame(byte [] rawData, Camera camera) {         
            Log.v(TAG, "Preview callback start");            
            int rawDataLength = 0;
            if (rawData != null) {
                rawDataLength = rawData.length;
            }
            if (rawDataLength > 0) {
                rawPreviewCallbackResult = true;
            } else {
                rawPreviewCallbackResult = false;
            }
            mPreviewDone.open();
            Log.v(TAG, "Preview callback stop");
        }
    };
    
    //Implement the shutterCallback
    private final class TestShutterCallback implements ShutterCallback {
        public void onShutter() {
            shutterCallbackResult = true;
            Log.v(TAG, "onShutter called");
        }
    };
    
    //Implement the RawPictureCallback
    private final class RawPictureCallback implements PictureCallback { 
        public void onPictureTaken(byte [] rawData, Camera camera) {
            // no support for raw data - success if we get the callback
            rawPictureCallbackResult = true;
            Log.v(TAG, "RawPictureCallback callback");
        }
    };
    
    //Implement the JpegPictureCallback
    private final class JpegPictureCallback implements PictureCallback {   
        public void onPictureTaken(byte [] rawData, Camera camera) {           
            try {         
                if (rawData != null) {
                    int rawDataLength = rawData.length;
                    File rawoutput = new File(
                            Environment.getExternalStorageDirectory().toString(), "/test.bmp");
                    FileOutputStream outstream = new FileOutputStream(rawoutput);
                    outstream.write(rawData);                   
                    Log.v(TAG, "JpegPictureCallback rawDataLength = " + rawDataLength);
                    jpegPictureCallbackResult = true;
                } else {
                    jpegPictureCallbackResult = false;
                }
                mSnapshotDone.open();
                Log.v(TAG, "Jpeg Picture callback");
            } catch (Exception e) {
                Log.v(TAG, e.toString());
            }
        }
    };
   
    private void waitForPreviewDone() {
        if (!mPreviewDone.block(WAIT_FOR_COMMAND_TO_COMPLETE)) {
            Log.v(TAG, "waitForPreviewDone: timeout");
        }
        mPreviewDone.close();
    }

    private void waitForSnapshotDone() {
        if (!mSnapshotDone.block(MediaNames.WAIT_SNAPSHOT_TIME)) {
            // timeout could be expected or unexpected. The caller will decide.
            Log.v(TAG, "waitForSnapshotDone: timeout");
        }
        mSnapshotDone.close();
    }


    private void checkTakePicture() { 
        SurfaceHolder mSurfaceHolder;
        try {
            mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            mCamera.setPreviewDisplay(mSurfaceHolder);
            Log.v(TAG, "Start preview");
            mCamera.startPreview();
            waitForPreviewDone();
            mCamera.setPreviewCallback(null);
            mCamera.takePicture(mShutterCallback, mRawPictureCallback, mJpegPictureCallback);
            waitForSnapshotDone();
        } catch (Exception e) {
            Log.v(TAG, e.toString());
        }      
    }
    
    private void checkPreviewCallback() { 
        SurfaceHolder mSurfaceHolder;
        try {
            mSurfaceHolder = MediaFrameworkTest.mSurfaceView.getHolder();
            mCamera.setPreviewDisplay(mSurfaceHolder);
            Log.v(TAG, "start preview");
            mCamera.startPreview();
            waitForPreviewDone();
            mCamera.setPreviewCallback(null);
        } catch (Exception e) {
            Log.v(TAG, e.toString());
        }      
    }
    
    /*
     * TODO(yslau): Need to setup the golden rawData and compare the 
     * the new captured rawData with the golden one.       
     * 
     * Test case 1: Take a picture and verify all the callback
     * functions are called properly.
     */
    @LargeTest
    public void testTakePicture() throws Exception {  
        initializeMessageLooper();
        mCamera.setPreviewCallback(mRawPreviewCallback);
        checkTakePicture();
        terminateMessageLooper();
        assertTrue("shutterCallbackResult", shutterCallbackResult);
        assertTrue("rawPictureCallbackResult", rawPictureCallbackResult);
        assertTrue("jpegPictureCallbackResult", jpegPictureCallbackResult);
    }
    
    /*
     * Test case 2: Set the preview and 
     * verify the RawPreviewCallback is called
     */
    @LargeTest
    public void testCheckPreview() throws Exception {  
        initializeMessageLooper();
        mCamera.setPreviewCallback(mRawPreviewCallback);
        checkPreviewCallback();     
        terminateMessageLooper();
        assertTrue("RawPreviewCallbackResult", rawPreviewCallbackResult);
    }

}

