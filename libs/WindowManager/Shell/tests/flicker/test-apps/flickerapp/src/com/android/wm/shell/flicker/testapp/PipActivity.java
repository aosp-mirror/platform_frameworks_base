/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.flicker.testapp;

import static android.media.MediaMetadata.METADATA_KEY_TITLE;
import static android.media.session.PlaybackState.ACTION_PLAY_PAUSE;
import static android.media.session.PlaybackState.ACTION_STOP;
import static android.media.session.PlaybackState.STATE_PAUSED;
import static android.media.session.PlaybackState.STATE_PLAYING;
import static android.media.session.PlaybackState.STATE_STOPPED;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Rational;
import android.view.Window;
import android.view.WindowManager;

public class PipActivity extends Activity {
    /**
     * A media session title for when the session is in {@link STATE_PLAYING}.
     * TvPipNotificationTests check whether the actual notification title matches this string.
     */
    private static final String TITLE_STATE_PLAYING = "TestApp media is playing";
    /**
     * A media session title for when the session is in {@link STATE_PAUSED}.
     * TvPipNotificationTests check whether the actual notification title matches this string.
     */
    private static final String TITLE_STATE_PAUSED = "TestApp media is paused";

    private MediaSession mMediaSession;
    private final PlaybackState.Builder mPlaybackStateBuilder = new PlaybackState.Builder()
            .setActions(ACTION_PLAY_PAUSE | ACTION_STOP)
            .setState(STATE_STOPPED, 0, 1f);
    private PlaybackState mPlaybackState = mPlaybackStateBuilder.build();
    private final MediaMetadata.Builder mMediaMetadataBuilder = new MediaMetadata.Builder();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Window window = getWindow();
        final WindowManager.LayoutParams layoutParams = window.getAttributes();
        layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.setAttributes(layoutParams);

        setContentView(R.layout.activity_pip);

        final PictureInPictureParams pipParams = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1, 1))
                .setSourceRectHint(new Rect(0, 0, 100, 100))
                .build();
        findViewById(R.id.enter_pip).setOnClickListener(v -> enterPictureInPictureMode(pipParams));

        findViewById(R.id.media_session_start)
                .setOnClickListener(v -> updateMediaSessionState(STATE_PLAYING));
        findViewById(R.id.media_session_stop)
                .setOnClickListener(v -> updateMediaSessionState(STATE_STOPPED));

        mMediaSession = new MediaSession(this, "WMShell_TestApp");
        mMediaSession.setPlaybackState(mPlaybackStateBuilder.build());
        mMediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                updateMediaSessionState(STATE_PLAYING);
            }

            @Override
            public void onPause() {
                updateMediaSessionState(STATE_PAUSED);
            }

            @Override
            public void onStop() {
                updateMediaSessionState(STATE_STOPPED);
            }
        });
    }

    private void updateMediaSessionState(int newState) {
        if (mPlaybackState.getState() == newState) {
            return;
        }
        final String title;
        switch (newState) {
            case STATE_PLAYING:
                title = TITLE_STATE_PLAYING;
                break;
            case STATE_PAUSED:
                title = TITLE_STATE_PAUSED;
                break;
            case STATE_STOPPED:
                title = "";
                break;

            default:
                throw new IllegalArgumentException("Unknown state " + newState);
        }

        mPlaybackStateBuilder.setState(newState, 0, 1f);
        mPlaybackState = mPlaybackStateBuilder.build();

        mMediaMetadataBuilder.putText(METADATA_KEY_TITLE, title);

        mMediaSession.setPlaybackState(mPlaybackState);
        mMediaSession.setMetadata(mMediaMetadataBuilder.build());
        mMediaSession.setActive(newState != STATE_STOPPED);
    }
}
