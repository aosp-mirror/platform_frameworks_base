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

    View mFullButtonView;
    View mFullDescriptionView;
    View mPlayPauseView;
    ImageView mPlayPauseButtonImageView;
    TextView mPlayPauseDescriptionTextView;
    View mCloseButtonView;
    View mCloseDescriptionView;

    private boolean mHasFocus;
    private OnFocusChangeListener mOnChildFocusChangeListener;

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updatePlayPauseView();
        }
    };

    private PipManager.MediaListener mPipMediaListener = new PipManager.MediaListener() {
        @Override
        public void onMediaControllerChanged() {
            updateMediaController();
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

        mFullButtonView = findViewById(R.id.full_button);
        mFullDescriptionView = findViewById(R.id.full_desc);
        mFullButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.movePipToFullscreen();
            }
        });
        mFullButtonView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mFullDescriptionView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
                onChildViewFocusChanged();
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
                if (mPipManager.getPlaybackState() == PLAYBACK_STATE_PAUSED) {
                    mMediaController.getTransportControls().play();
                } else if (mPipManager.getPlaybackState() == PLAYBACK_STATE_PLAYING) {
                    mMediaController.getTransportControls().pause();
                }
                // View will be updated later in {@link mMediaControllerCallback}
            }
        });
        mPlayPauseButtonImageView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mPlayPauseDescriptionTextView.setVisibility(
                        hasFocus ? View.VISIBLE : View.INVISIBLE);
                onChildViewFocusChanged();
            }
        });

        mCloseButtonView = findViewById(R.id.close_button);
        mCloseDescriptionView = findViewById(R.id.close_desc);
        mCloseButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.closePip();
                if (mListener != null) {
                    mListener.onClosed();
                }
            }
        });
        mCloseButtonView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                mCloseDescriptionView.setVisibility(hasFocus ? View.VISIBLE : View.INVISIBLE);
                onChildViewFocusChanged();
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
            mPlayPauseView.setVisibility(View.GONE);
        } else {
            mPlayPauseView.setVisibility(View.VISIBLE);
            if (state == PLAYBACK_STATE_PLAYING) {
                mPlayPauseButtonImageView.setImageResource(R.drawable.tv_pip_pause_button);
                mPlayPauseDescriptionTextView.setText(R.string.pip_pause);
            } else {
                mPlayPauseButtonImageView.setImageResource(R.drawable.tv_pip_play_button);
                mPlayPauseDescriptionTextView.setText(R.string.pip_play);
            }
        }
    }

    /**
     * Sets a listener to be invoked when {@link android.view.View.hasFocus()} is changed.
     */
    public void setOnChildFocusChangeListener(OnFocusChangeListener listener) {
        mOnChildFocusChangeListener = listener;
    }

    private void onChildViewFocusChanged() {
        // At this moment, hasFocus() returns true although there's no focused child.
        boolean hasFocus = (mFullButtonView != null && mFullButtonView.isFocused())
                || (mPlayPauseButtonImageView != null && mPlayPauseButtonImageView.isFocused())
                || (mCloseButtonView != null && mCloseButtonView.isFocused());
        if (mHasFocus != hasFocus) {
            mHasFocus = hasFocus;
            if (mOnChildFocusChangeListener != null) {
                mOnChildFocusChangeListener.onFocusChange(getFocusedChild(), mHasFocus);
            }
        }
    }

    /**
     * Sets the {@link Listener} to listen user actions.
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }
}
