/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.tv.pip;

import android.app.Activity;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.recents.Recents;

import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;
import static android.media.session.PlaybackState.ACTION_PAUSE;
import static android.media.session.PlaybackState.ACTION_PLAY;

/**
 * Activity to show the PIP menu to control PIP.
 */
public class PipMenuActivity extends Activity implements PipManager.Listener {
    private static final String TAG = "PipMenuActivity";

    private final PipManager mPipManager = PipManager.getInstance();
    private MediaController mMediaController;

    private View mFullButtonView;
    private View mFullDescriptionView;
    private View mPlayPauseView;
    private ImageView mPlayPauseButtonImageView;
    private TextView mPlayPauseDescriptionTextView;
    private View mCloseButtonView;
    private View mCloseDescriptionView;
    private boolean mPipMovedToFullscreen;

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updatePlayPauseView(state);
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.tv_pip_menu);
        mPipManager.addListener(this);
        mFullButtonView = findViewById(R.id.full_button);
        mFullDescriptionView = findViewById(R.id.full_desc);
        mFullButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.movePipToFullscreen();
                mPipMovedToFullscreen = true;
                finish();
            }
        });
        mFullButtonView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mFullDescriptionView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
            }
        });

        mPlayPauseView = findViewById(R.id.play_pause);
        mPlayPauseButtonImageView = (ImageView) findViewById(R.id.play_pause_button);
        mPlayPauseDescriptionTextView = (TextView) findViewById(R.id.play_pause_desc);
        mPlayPauseButtonImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMediaController == null || mMediaController.getPlaybackState() == null) {
                    return;
                }
                long actions = mMediaController.getPlaybackState().getActions();
                int state = mMediaController.getPlaybackState().getState();
                if (((actions & ACTION_PLAY) != 0) && !isPlaying(state)) {
                    mMediaController.getTransportControls().play();
                } else if ((actions & ACTION_PAUSE) != 0 && isPlaying(state)) {
                    mMediaController.getTransportControls().pause();
                }
                // View will be updated later in {@link mMediaControllerCallback}
            }
        });
        mPlayPauseButtonImageView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mPlayPauseDescriptionTextView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
            }
        });

        mCloseButtonView = findViewById(R.id.close_button);
        mCloseDescriptionView = findViewById(R.id.close_desc);
        mCloseButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.closePip();
                finish();
            }
        });
        mCloseButtonView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mCloseDescriptionView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
            }
        });
        updateMediaController();
    }

    private void updateMediaController() {
        MediaController newController = mPipManager.getMediaController();
        if (mMediaController == newController) {
            return;
        }
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mMediaControllerCallback);
        }
        mMediaController = newController;
        if (mMediaController != null) {
            mMediaController.registerCallback(mMediaControllerCallback);
            updatePlayPauseView(mMediaController.getPlaybackState());
        } else {
            updatePlayPauseView(null);
        }
    }

    private void updatePlayPauseView(PlaybackState playbackState) {
        if (playbackState != null
                && (playbackState.getActions() & (ACTION_PLAY | ACTION_PAUSE)) != 0) {
            mPlayPauseView.setVisibility(View.VISIBLE);
            if (isPlaying(playbackState.getState())) {
                mPlayPauseButtonImageView.setImageResource(R.drawable.tv_pip_pause_button);
                mPlayPauseDescriptionTextView.setText(R.string.pip_pause);
            } else {
                mPlayPauseButtonImageView.setImageResource(R.drawable.tv_pip_play_button);
                mPlayPauseDescriptionTextView.setText(R.string.pip_play);
            }
        } else {
            mPlayPauseView.setVisibility(View.GONE);
        }
    }

    private boolean isPlaying(int state) {
        return state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT;
    }

    private void restorePipAndFinish() {
        if (!mPipMovedToFullscreen) {
            mPipManager.resizePinnedStack(PipManager.STATE_PIP_OVERLAY);
        }
        finish();
    }

    @Override
    public void onPause() {
        super.onPause();
        restorePipAndFinish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mMediaControllerCallback);
        }
        mPipManager.removeListener(this);
        mPipManager.resumePipResizing(
                PipManager.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }

    @Override
    public void onBackPressed() {
        restorePipAndFinish();
    }

    @Override
    public void onPipActivityClosed() {
        finish();
    }

    @Override
    public void onShowPipMenu() { }

    @Override
    public void onMoveToFullscreen() {
        finish();
    }

    @Override
    public void onMediaControllerChanged() {
        updateMediaController();
    }

    @Override
    public void onPipResizeAboutToStart() {
        finish();
        mPipManager.suspendPipResizing(
                PipManager.SUSPEND_PIP_RESIZE_REASON_WAITING_FOR_MENU_ACTIVITY_FINISH);
    }

    @Override
    public void finish() {
        super.finish();
        if (mPipManager.isRecentsShown() && !mPipMovedToFullscreen) {
            SystemUI[] services = ((SystemUIApplication) getApplication()).getServices();
            for (int i = services.length - 1; i >= 0; i--) {
                if (services[i] instanceof Recents) {
                    ((Recents) services[i]).showRecents(false, null);
                    break;
                }
            }
        }
    }
}
