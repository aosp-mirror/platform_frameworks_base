/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.onemedia;


import android.app.Activity;
import android.media.session.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.android.onemedia.playback.Renderer;

public class OnePlayerActivity extends Activity {
    private static final String TAG = "OnePlayerActivity";

    protected PlayerController mPlayer;

    private Button mStartButton;
    private Button mPlayButton;
    private TextView mStatusView;

    private EditText mContentText;
    private EditText mNextContentText;
    private CheckBox mHasVideo;

    private int mPlaybackState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_one_player);
        mPlayer = new PlayerController(this, OnePlayerService.getServiceIntent(this));


        mStartButton = (Button) findViewById(R.id.start_button);
        mPlayButton = (Button) findViewById(R.id.play_button);
        mStatusView = (TextView) findViewById(R.id.status);
        mContentText = (EditText) findViewById(R.id.content);
        mNextContentText = (EditText) findViewById(R.id.next_content);
        mHasVideo = (CheckBox) findViewById(R.id.has_video);

        mStartButton.setOnClickListener(mButtonListener);
        mPlayButton.setOnClickListener(mButtonListener);

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        mPlayer.onResume();
        mPlayer.setListener(mListener);
    }

    @Override
    public void onPause() {
        mPlayer.setListener(null);
        mPlayer.onPause();
        super.onPause();
    }

    private void setControlsEnabled(boolean enabled) {
        mStartButton.setEnabled(enabled);
        mPlayButton.setEnabled(enabled);
    }

    private View.OnClickListener mButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.play_button:
                    Log.d(TAG, "Play button pressed, in state " + mPlaybackState);
                    if (mPlaybackState == PlaybackState.PLAYSTATE_PAUSED
                            || mPlaybackState == PlaybackState.PLAYSTATE_STOPPED) {
                        mPlayer.play();
                    } else if (mPlaybackState == PlaybackState.PLAYSTATE_PLAYING) {
                        mPlayer.pause();
                    }
                    break;
                case R.id.start_button:
                    Log.d(TAG, "Start button pressed, in state " + mPlaybackState);
                    mPlayer.setContent(mContentText.getText().toString());
                    break;
            }

        }
    };

    private PlayerController.Listener mListener = new PlayerController.Listener() {
        @Override
        public void onPlaybackStateChange(PlaybackState state) {
            mPlaybackState = state.getState();
            boolean enablePlay = false;
            StringBuilder statusBuilder = new StringBuilder();
            switch (mPlaybackState) {
                case PlaybackState.PLAYSTATE_PLAYING:
                    statusBuilder.append("playing");
                    mPlayButton.setText("Pause");
                    enablePlay = true;
                    break;
                case PlaybackState.PLAYSTATE_PAUSED:
                    statusBuilder.append("paused");
                    mPlayButton.setText("Play");
                    enablePlay = true;
                    break;
                case PlaybackState.PLAYSTATE_STOPPED:
                    statusBuilder.append("ended");
                    mPlayButton.setText("Play");
                    enablePlay = true;
                    break;
                case PlaybackState.PLAYSTATE_ERROR:
                    statusBuilder.append("error: ").append(state.getErrorMessage());
                    break;
                case PlaybackState.PLAYSTATE_BUFFERING:
                    statusBuilder.append("buffering");
                    break;
                case PlaybackState.PLAYSTATE_NONE:
                    statusBuilder.append("none");
                    break;
                default:
                    statusBuilder.append(mPlaybackState);
            }
            statusBuilder.append(" -- At position: ").append(state.getPosition());
            mStatusView.setText(statusBuilder.toString());
            mPlayButton.setEnabled(enablePlay);
        }

        @Override
        public void onConnectionStateChange(int state) {
            if (state == PlayerController.STATE_DISCONNECTED) {
                setControlsEnabled(false);
            } else if (state == PlayerController.STATE_CONNECTED) {
                setControlsEnabled(true);
            }
        }

        @Override
        public void onMetadataChange(MediaMetadata metadata) {
            Log.d(TAG, "Metadata update! Title: " + metadata);
        }
    };
}
