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

package com.test.tilebenchmark;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;

/**
 * Interface for playing back WebView tile rendering status. Draws viewport and
 * states of tiles and statistics for off-line analysis.
 */
public class PlaybackActivity extends Activity {
    private static final float SCROLL_SCALER = 0.125f;

    PlaybackView mPlaybackView;
    SeekBar mSeekBar;
    Button mForward;
    Button mBackward;
    TextView mFrameDisplay;

    private int mFrame = -1;
    private int mFrameMax;

    private class TouchFrameChangeListener extends SimpleOnGestureListener {
        float mDist = 0;

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                float distanceX, float distanceY) {
            // aggregate scrolls so that small ones can add up
            mDist += distanceY * SCROLL_SCALER;
            int intComponent = (int) Math.floor(Math.abs(mDist));
            if (intComponent >= 1) {
                int scrollDist = (mDist > 0) ? intComponent : -intComponent;
                setFrame(null, mFrame + scrollDist);
                mDist -= scrollDist;
            }
            return super.onScroll(e1, e2, distanceX, distanceY);
        }
    };

    private class SeekFrameChangeListener implements OnSeekBarChangeListener {
        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress,
                boolean fromUser) {
            setFrame(seekBar, progress);
        }
    };

    private class LoadFileTask extends AsyncTask<String, Void, TileData[][]> {
        @Override
        protected TileData[][] doInBackground(String... params) {
            TileData[][] data = null;
            try {
                FileInputStream fis = openFileInput(params[0]);
                ObjectInputStream in = new ObjectInputStream(fis);
                data = (TileData[][]) in.readObject();
                in.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            } catch (ClassNotFoundException ex) {
                ex.printStackTrace();
            }
            return data;
        }

        @Override
        protected void onPostExecute(TileData data[][]) {
            if (data == null) {
                Toast.makeText(getApplicationContext(),
                        getResources().getString(R.string.error_no_data),
                        Toast.LENGTH_LONG).show();
                return;
            }
            mPlaybackView.setData(data);

            mFrameMax = data.length - 1;
            mSeekBar.setMax(mFrameMax);

            setFrame(null, 0);
        }
    }

    private void setFrame(View changer, int f) {
        if (f < 0) {
            f = 0;
        } else if (f > mFrameMax) {
            f = mFrameMax;
        }

        if (mFrame == f) {
            return;
        }

        mFrame = f;
        mForward.setEnabled(mFrame != mFrameMax);
        mBackward.setEnabled(mFrame != 0);
        if (changer != mSeekBar) {
            mSeekBar.setProgress(mFrame);
        }
        mFrameDisplay.setText(Integer.toString(mFrame));
        mPlaybackView.setFrame(mFrame);
    };

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.playback);

        mPlaybackView = (PlaybackView) findViewById(R.id.playback);
        mSeekBar = (SeekBar) findViewById(R.id.seek_bar);
        mForward = (Button) findViewById(R.id.forward);
        mBackward = (Button) findViewById(R.id.backward);
        mFrameDisplay = (TextView) findViewById(R.id.frame_display);

        mForward.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setFrame(v, mFrame + 1);
            }
        });

        mBackward.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setFrame(v, mFrame - 1);
            }
        });

        mSeekBar.setOnSeekBarChangeListener(new SeekFrameChangeListener());

        mPlaybackView.setOnGestureListener(new TouchFrameChangeListener());

        new LoadFileTask().execute(ProfileActivity.TEMP_FILENAME);
    }
}
