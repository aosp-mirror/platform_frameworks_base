/*
 * Copyright (C) 2013 The Android Open Source Project
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

package androidx.media.filterfw.samples.simplecamera;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.media.filterfw.FilterGraph;
import androidx.media.filterfw.GraphReader;
import androidx.media.filterfw.GraphRunner;
import androidx.media.filterfw.MffContext;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;


public class SmartCamera extends Activity {

    private SurfaceView mCameraView;
    private TextView mGoodBadTextView;
    private TextView mFPSTextView;
    private TextView mEyesTextView;
    private TextView mSmilesTextView;
    private TextView mScoreTextView;
    private static ImageView mImageView1;
    private static ImageView mImageView2;
    private static ImageView mImageView3;
    private static ImageView mImageView4;
    private static ImageView mImageView5;
    private Button mStartStopButton;
    private TextView mImagesSavedTextView;
    private Spinner mSpinner;
    private LinearLayout mLinearLayout;

    private MffContext mContext;
    private FilterGraph mGraph;
    private GraphRunner mRunner;
    private Handler mHandler = new Handler();

    private static final String TAG = "SmartCamera";
    private static final boolean sUseFacialExpression = false;
    private boolean isPendingRunGraph = false;

    private static ArrayList<ImageView> mImages;
    private static int count = -1;
    private static boolean countHasReachedMax = false;
    private static int numImages = 0;

    // Function to return the correct image view to display the current bitmap
    public static ImageView getImageView() {
        if (count == numImages-1) countHasReachedMax = true;
        count = (count+1) % numImages;
        return mImages.get(count);
    }

    // Function used to run images through the graph, mainly for CSV data generation
    public void runGraphOnImage(String filePath, String fileName) {
        if(fileName.endsWith(".jpg") == false) {
            return;
        }
        mGraph.getVariable("gallerySource").setValue(filePath + "/" + fileName);
        Log.v(TAG, "runGraphOnImage : : " + filePath + " name: " + fileName);
        mGraph.getVariable("imageName").setValue(fileName);
        mGraph.getVariable("filePath").setValue(filePath); // wrong
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    // Function to clear the "Images Saved" text off the screen
    private void clearImagesSavedTextView() {
        mImagesSavedTextView.setText("");
    }

    // Function to capture the images in the current imageviews and save them to the gallery
    private void captureImages() {
        ((WaveTriggerFilter) mGraph.getFilter("snapEffect")).trigger();
        mGraph.getVariable("startCapture").setValue(false);
        Bitmap bitmap = null;
        Drawable res = getResources().getDrawable(R.drawable.black_screen);
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss");

        Log.v(TAG, "numImages: " + numImages + " count: " + count +
                " hasReachedMax: " + countHasReachedMax);
        int maxI = countHasReachedMax ? numImages : count+1;
        if(maxI != 0) {
            if (maxI == 1) mImagesSavedTextView.setText("Image Saved");
            else {
                mImagesSavedTextView.setText("" + maxI + " Images Saved");
            }
        }
        for (int i = 0; i < maxI; i++) {
            bitmap = ((BitmapDrawable)mImages.get(i).getDrawable()).getBitmap();
            mImages.get(i).setImageDrawable(res);
            MediaStore.Images.Media.insertImage(getContentResolver(), bitmap,
                    sdf.format(cal.getTime()) + "_image" + i + ".jpg", "image " + i);
        }
        mStartStopButton.setText("Start");
        count = -1;
        countHasReachedMax = false;
        mSpinner.setEnabled(true);
        mHandler.postDelayed(new Runnable() {
            public void run() {
                clearImagesSavedTextView();
            }
        }, 5000);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.simplecamera);
        setTitle("Smart Camera");

        mContext = new MffContext(this);

        mCameraView = (SurfaceView) findViewById(R.id.cameraView);
        mGoodBadTextView = (TextView) findViewById(R.id.goodOrBadTextView);
        mFPSTextView = (TextView) findViewById(R.id.fpsTextView);
        mScoreTextView = (TextView) findViewById(R.id.scoreTextView);
        mStartStopButton = (Button) findViewById(R.id.startButton);
        mImagesSavedTextView = (TextView) findViewById(R.id.imagesSavedTextView);
        mImagesSavedTextView.setText("");
        mSpinner = (Spinner) findViewById(R.id.spinner);
        mLinearLayout = (LinearLayout) findViewById(R.id.scrollViewLinearLayout);
        mImages = new ArrayList<ImageView>();

        // Spinner is used to determine how many image views are displayed at the bottom
        // of the screen. Based on the item position that is selected, we inflate that
        // many imageviews into the bottom linear layout.
        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView,
                    int position, long id) {
                mLinearLayout.removeViews(0,numImages);
                numImages = position+1;
                mImages.clear();
                LayoutInflater inflater = getLayoutInflater();
                for (int i = 0; i < numImages; i++) {
                    ImageView tmp = (ImageView) inflater.inflate(R.layout.imageview, null);
                    mImages.add(tmp);
                    mLinearLayout.addView(tmp);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        numImages = mSpinner.getSelectedItemPosition()+1;
        mImages.clear();
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < numImages; i++) {
            ImageView tmp = (ImageView) inflater.inflate(R.layout.imageview, null);
            mImages.add(tmp);
            mLinearLayout.addView(tmp);

        }

        // Button used to start and stop the capture of images when they are deemed great
        mStartStopButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mStartStopButton.getText().equals("Start")) {
                    mGraph.getVariable("startCapture").setValue(true);
                    mStartStopButton.setText("Stop");
                    mSpinner.setEnabled(false);
                } else {
                    boolean tmp = (Boolean) mGraph.getVariable("startCapture").getValue();
                    if (tmp == false) {
                        return;
                    }
                    if (count == numImages-1) countHasReachedMax = true;
                    captureImages();
                }
            }
        });

        // Button to open the gallery to show the images in there
        Button galleryOpen = (Button) findViewById(R.id.galleryOpenButton);
        galleryOpen.setOnClickListener(new OnClickListener() {
           @Override
           public void onClick(View v) {
               Intent openGalleryIntent = new Intent(Intent.ACTION_MAIN);
               openGalleryIntent.addCategory(Intent.CATEGORY_APP_GALLERY);
               startActivity(openGalleryIntent);
           }
        });

        loadGraph();
        mGraph.getVariable("startCapture").setValue(false);
        runGraph();
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause");
        if (mContext != null) {
            mContext.onPause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
        if (mContext != null) {
            mContext.onResume();
        }
        if (isPendingRunGraph) {
            isPendingRunGraph = false;
            runGraph();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.i(TAG, "onStop");
    }

    // Build the Filtergraph for Camera
    private void loadGraph() {
        try {
            mGraph = GraphReader.readXmlGraphResource(mContext, R.raw.camera_graph);
            mRunner = mGraph.getRunner();

            // Connect views
            mGraph.bindFilterToView("camViewTarget", mCameraView);
            mGraph.bindFilterToView("goodOrBadTextView", mGoodBadTextView);
            mGraph.bindFilterToView("fpsTextView", mFPSTextView);
            mGraph.bindFilterToView("scoreTextView", mScoreTextView);

            // Used for Facial Expressions
            if (sUseFacialExpression) {
                mGraph.bindFilterToView("eyesTextView", mEyesTextView);
                mGraph.bindFilterToView("smilesTextView", mSmilesTextView);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Asynchronously run the filtergraph
    private void runGraph() {
        mRunner.setIsVerbose(true);
        mRunner.start(mGraph);
    }
}
