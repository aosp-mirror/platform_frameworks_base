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
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;

public class OnePlayerActivity extends Activity {
    private static final String TAG = "OnePlayerActivity";

    private static final int READ_REQUEST_CODE = 42;

    protected PlayerController mPlayer;

    private Button mStartButton;
    private Button mPlayButton;
    private Button mRouteButton;
    private TextView mStatusView;

    private EditText mContentText;
    private EditText mNextContentText;
    private CheckBox mHasVideo;
    private ImageView mArtView;

    private PlaybackState mPlaybackState;
    private Bitmap mAlbumArtBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_one_player);
        mPlayer = new PlayerController(this, OnePlayerService.getServiceIntent(this));


        mStartButton = (Button) findViewById(R.id.start_button);
        mPlayButton = (Button) findViewById(R.id.play_button);
        mRouteButton = (Button) findViewById(R.id.route_button);
        mStatusView = (TextView) findViewById(R.id.status);
        mContentText = (EditText) findViewById(R.id.content);
        mNextContentText = (EditText) findViewById(R.id.next_content);
        mHasVideo = (CheckBox) findViewById(R.id.has_video);
        mArtView = (ImageView) findViewById(R.id.art);

        final Button artPicker = (Button) findViewById(R.id.art_picker);
        artPicker.setOnClickListener(mButtonListener);

        mStartButton.setOnClickListener(mButtonListener);
        mPlayButton.setOnClickListener(mButtonListener);
        mRouteButton.setOnClickListener(mButtonListener);

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

    @Override
    public void onActivityResult(int requestCode, int resultCode,
            Intent resultData) {
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                Log.i(TAG, "Uri: " + uri.toString());
                mAlbumArtBitmap = null;
                try {
                    mAlbumArtBitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
                } catch (IOException e) {
                    Log.v(TAG, "Couldn't load album art", e);
                }
                mArtView.setImageBitmap(mAlbumArtBitmap);
                if (mAlbumArtBitmap != null) {
                    mArtView.setVisibility(View.VISIBLE);
                } else {
                    mArtView.setVisibility(View.GONE);
                }
                mPlayer.setArt(mAlbumArtBitmap);
            }
        }
    }

    private void setControlsEnabled(boolean enabled) {
        mStartButton.setEnabled(enabled);
        mPlayButton.setEnabled(enabled);
    }

    private View.OnClickListener mButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final int state = mPlaybackState.getState();
            switch (v.getId()) {
                case R.id.play_button:
                    Log.d(TAG, "Play button pressed, in state " + state);
                    if (state == PlaybackState.STATE_PAUSED
                            || state == PlaybackState.STATE_STOPPED) {
                        mPlayer.play();
                    } else if (state == PlaybackState.STATE_PLAYING) {
                        mPlayer.pause();
                    }
                    break;
                case R.id.start_button:
                    Log.d(TAG, "Start button pressed, in state " + state);
                    mPlayer.setContent(mContentText.getText().toString());
                    break;
                case R.id.route_button:
                    mPlayer.showRoutePicker();
                    break;
                case R.id.art_picker:
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");

                    startActivityForResult(intent, READ_REQUEST_CODE);
                    break;
            }

        }
    };

    private PlayerController.Listener mListener = new PlayerController.Listener() {
        public MediaMetadata mMetadata;

        @Override
        public void onPlaybackStateChange(PlaybackState state) {
            mPlaybackState = state;
            boolean enablePlay = false;
            boolean enableControls = true;
            StringBuilder statusBuilder = new StringBuilder();
            switch (mPlaybackState.getState()) {
                case PlaybackState.STATE_PLAYING:
                    statusBuilder.append("playing");
                    mPlayButton.setText("Pause");
                    enablePlay = true;
                    break;
                case PlaybackState.STATE_PAUSED:
                    statusBuilder.append("paused");
                    mPlayButton.setText("Play");
                    enablePlay = true;
                    break;
                case PlaybackState.STATE_STOPPED:
                    statusBuilder.append("ended");
                    mPlayButton.setText("Play");
                    enablePlay = true;
                    break;
                case PlaybackState.STATE_ERROR:
                    statusBuilder.append("error: ").append(state.getErrorMessage());
                    break;
                case PlaybackState.STATE_BUFFERING:
                    statusBuilder.append("buffering");
                    break;
                case PlaybackState.STATE_NONE:
                    statusBuilder.append("none");
                    break;
                case PlaybackState.STATE_CONNECTING:
                    statusBuilder.append("connecting");
                    enableControls = false;
                    break;
                default:
                    statusBuilder.append(mPlaybackState);
            }
            statusBuilder.append(" -- At position: ").append(state.getPosition());
            mStatusView.setText(statusBuilder.toString());
            mPlayButton.setEnabled(enablePlay);
            setControlsEnabled(enableControls);
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
            mMetadata = metadata;
        }
    };
}
