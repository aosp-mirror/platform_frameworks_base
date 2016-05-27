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

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.view.View;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View.OnFocusChangeListener;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.util.AttributeSet;

import com.android.systemui.R;

import static android.media.session.PlaybackState.ACTION_PAUSE;
import static android.media.session.PlaybackState.ACTION_PLAY;

import static com.android.systemui.tv.pip.PipManager.PLAYBACK_STATE_PLAYING;
import static com.android.systemui.tv.pip.PipManager.PLAYBACK_STATE_PAUSED;
import static com.android.systemui.tv.pip.PipManager.PLAYBACK_STATE_UNAVAILABLE;


/**
 * A view containing PIP controls including fullscreen, close, and media controls.
 */
public class PipControlsView extends LinearLayout {
    /**
     * An interface to listen user action.
     */
    public abstract static interface Listener {
        /**
         * Called when an user clicks close PIP button.
         */
        public abstract void onClosed();
    };

    private MediaController mMediaController;

    final PipManager mPipManager = PipManager.getInstance();
    Listener mListener;

    private PipControlButtonView mFullButtonView;
    private PipControlButtonView mCloseButtonView;
    private PipControlButtonView mPlayPauseButtonView;

    private PipControlButtonView mFocusedChild;

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updatePlayPauseView();
        }
    };

    private final PipManager.MediaListener mPipMediaListener = new PipManager.MediaListener() {
        @Override
        public void onMediaControllerChanged() {
            updateMediaController();
        }
    };

    private final OnFocusChangeListener mFocusChangeListener = new OnFocusChangeListener() {
        @Override
        public void onFocusChange(View view, boolean hasFocus) {
            if (hasFocus) {
                mFocusedChild = (PipControlButtonView) view;
            } else if (mFocusedChild == view) {
                mFocusedChild = null;
            }
        }
    };

    public PipControlsView(Context context) {
        this(context, null, 0, 0);
    }

    public PipControlsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public PipControlsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public PipControlsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.tv_pip_controls, this);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();

        mFullButtonView = (PipControlButtonView) findViewById(R.id.full_button);
        mFullButtonView.setOnFocusChangeListener(mFocusChangeListener);
        mFullButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.movePipToFullscreen();
            }
        });

        mCloseButtonView = (PipControlButtonView) findViewById(R.id.close_button);
        mCloseButtonView.setOnFocusChangeListener(mFocusChangeListener);
        mCloseButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.closePip();
                if (mListener != null) {
                    mListener.onClosed();
                }
            }
        });

        mPlayPauseButtonView = (PipControlButtonView) findViewById(R.id.play_pause_button);
        mPlayPauseButtonView.setOnFocusChangeListener(mFocusChangeListener);
        mPlayPauseButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMediaController == null || mMediaController.getPlaybackState() == null) {
                    return;
                }
                long actions = mMediaController.getPlaybackState().getActions();
                int state = mMediaController.getPlaybackState().getState();
                if (mPipManager.getPlaybackState() == PLAYBACK_STATE_PAUSED) {
                    mMediaController.getTransportControls().play();
                } else if (mPipManager.getPlaybackState() == PLAYBACK_STATE_PLAYING) {
                    mMediaController.getTransportControls().pause();
                }
                // View will be updated later in {@link mMediaControllerCallback}
            }
        });
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateMediaController();
        mPipManager.addMediaListener(mPipMediaListener);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mPipManager.removeMediaListener(mPipMediaListener);
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mMediaControllerCallback);
        }
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
        }
        updatePlayPauseView();
    }

    private void updatePlayPauseView() {
        int state = mPipManager.getPlaybackState();
        if (state == PLAYBACK_STATE_UNAVAILABLE) {
            mPlayPauseButtonView.setVisibility(View.GONE);
        } else {
            mPlayPauseButtonView.setVisibility(View.VISIBLE);
            if (state == PLAYBACK_STATE_PLAYING) {
                mPlayPauseButtonView.setImageResource(R.drawable.ic_pause_white_24dp);
                mPlayPauseButtonView.setText(R.string.pip_pause);
            } else {
                mPlayPauseButtonView.setImageResource(R.drawable.ic_play_arrow_white_24dp);
                mPlayPauseButtonView.setText(R.string.pip_play);
            }
        }
    }

    /**
     * Resets to initial state.
     */
    public void reset() {
        mFullButtonView.reset();
        mCloseButtonView.reset();
        mPlayPauseButtonView.reset();
        mFullButtonView.requestFocus();
    }

    /**
     * Sets the {@link Listener} to listen user actions.
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Returns the focused control button view to animate focused button.
     */
    PipControlButtonView getFocusedButton() {
        return mFocusedChild;
    }
}
