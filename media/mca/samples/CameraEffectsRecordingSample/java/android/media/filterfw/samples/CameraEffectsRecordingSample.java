/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.media.filterfw.samples;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.SurfaceView;
import android.view.KeyEvent;
import android.widget.Button;
import android.filterfw.GraphEnvironment;
import android.filterfw.core.GraphRunner;
import android.filterpacks.videosink.MediaEncoderFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;

public class CameraEffectsRecordingSample extends Activity {

    private Button mRunButton;
    private SurfaceView mCameraView;

    private GraphRunner mRunner;
    private int mCameraId = 0;
    private String mOutFileName =  Environment.getExternalStorageDirectory().toString() +
        "/CameraEffectsRecordingSample.mp4";

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mRunButton = findViewById(R.id.runbutton);
        mCameraView = findViewById(R.id.cameraview);
        mRunButton.setOnClickListener(mRunButtonClick);

        Intent intent = getIntent();
        if (intent.hasExtra("OUTPUT_FILENAME")) {
            mOutFileName = intent.getStringExtra("OUTPUT_FILENAME");
        }
        // Set up the references and load the filter graph
        createGraph();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CAMERA:
                mRunButton.performClick();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void createGraph() {
        Bitmap sourceBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.android);
        GraphEnvironment graphEnvironment = new GraphEnvironment();
        graphEnvironment.createGLEnvironment();
        graphEnvironment.addReferences("cameraView", mCameraView);
        graphEnvironment.addReferences("cameraId", mCameraId);
        graphEnvironment.addReferences("outputFileName", mOutFileName);
        int graphId = graphEnvironment.loadGraph(this, R.raw.cameraeffectsrecordingsample);
        mRunner = graphEnvironment.getRunner(graphId, GraphEnvironment.MODE_ASYNCHRONOUS);
    }

    protected void onPause() {
        super.onPause();
        if (mRunner.isRunning()) {
            mRunner.stop();
            mRunButton.setText("Record");
        }
    }

    private OnClickListener mRunButtonClick = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mRunner.isRunning()) {
                mRunner.stop();
                mRunButton.setText("Record");
            } else {
                mRunner.run();
                mRunButton.setText("Stop");
            }
        }
    };
}
