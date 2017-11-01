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

package com.android.systemui.pip.tv;

import android.app.ActivityManager;
import android.app.PendingIntent.CanceledException;
import android.app.RemoteAction;
import android.content.Context;
import android.graphics.Color;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.util.AttributeSet;

import com.android.systemui.R;

import static android.media.session.PlaybackState.ACTION_PAUSE;
import static android.media.session.PlaybackState.ACTION_PLAY;

import java.util.ArrayList;
import java.util.List;


/**
 * A view containing PIP controls including fullscreen, close, and media controls.
 */
public class PipControlsView extends LinearLayout {

    private static final String TAG = PipControlsView.class.getSimpleName();

    private static final float DISABLED_ACTION_ALPHA = 0.54f;

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

    private final PipManager mPipManager = PipManager.getInstance();
    private final LayoutInflater mLayoutInflater;
    private final Handler mHandler;
    private Listener mListener;

    private PipControlButtonView mFullButtonView;
    private PipControlButtonView mCloseButtonView;
    private PipControlButtonView mPlayPauseButtonView;
    private ArrayList<PipControlButtonView> mCustomButtonViews = new ArrayList<>();
    private List<RemoteAction> mCustomActions = new ArrayList<>();

    private PipControlButtonView mFocusedChild;

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateUserActions();
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
        mLayoutInflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mLayoutInflater.inflate(R.layout.tv_pip_controls, this);
        mHandler = new Handler();

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();

        mFullButtonView = findViewById(R.id.full_button);
        mFullButtonView.setOnFocusChangeListener(mFocusChangeListener);
        mFullButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPipManager.movePipToFullscreen();
            }
        });

        mCloseButtonView = findViewById(R.id.close_button);
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

        mPlayPauseButtonView = findViewById(R.id.play_pause_button);
        mPlayPauseButtonView.setOnFocusChangeListener(mFocusChangeListener);
        mPlayPauseButtonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMediaController == null || mMediaController.getPlaybackState() == null) {
                    return;
                }
                long actions = mMediaController.getPlaybackState().getActions();
                int state = mMediaController.getPlaybackState().getState();
                if (mPipManager.getPlaybackState() == PipManager.PLAYBACK_STATE_PAUSED) {
                    mMediaController.getTransportControls().play();
                } else if (mPipManager.getPlaybackState() == PipManager.PLAYBACK_STATE_PLAYING) {
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
        updateUserActions();
    }

    /**
     * Updates the actions for the PIP. If there are no custom actions, then the media session
     * actions are shown.
     */
    private void updateUserActions() {
        if (!mCustomActions.isEmpty()) {
            // Ensure we have as many buttons as actions
            while (mCustomButtonViews.size() < mCustomActions.size()) {
                PipControlButtonView buttonView = (PipControlButtonView) mLayoutInflater.inflate(
                        R.layout.tv_pip_custom_control, this, false);
                addView(buttonView);
                mCustomButtonViews.add(buttonView);
            }

            // Update the visibility of all views
            for (int i = 0; i < mCustomButtonViews.size(); i++) {
                mCustomButtonViews.get(i).setVisibility(i < mCustomActions.size()
                        ? View.VISIBLE
                        : View.GONE);
            }

            // Update the state and visibility of the action buttons, and hide the rest
            for (int i = 0; i < mCustomActions.size(); i++) {
                final RemoteAction action = mCustomActions.get(i);
                PipControlButtonView actionView = mCustomButtonViews.get(i);

                // TODO: Check if the action drawable has changed before we reload it
                action.getIcon().loadDrawableAsync(getContext(), d -> {
                    d.setTint(Color.WHITE);
                    actionView.setImageDrawable(d);
                }, mHandler);
                actionView.setText(action.getContentDescription());
                if (action.isEnabled()) {
                    actionView.setOnClickListener(v -> {
                        try {
                            action.getActionIntent().send();
                        } catch (CanceledException e) {
                            Log.w(TAG, "Failed to send action", e);
                        }
                    });
                }
                actionView.setEnabled(action.isEnabled());
                actionView.setAlpha(action.isEnabled() ? 1f : DISABLED_ACTION_ALPHA);
            }

            // Hide the media session buttons
            mPlayPauseButtonView.setVisibility(View.GONE);
        } else {
            int state = mPipManager.getPlaybackState();
            if (state == PipManager.PLAYBACK_STATE_UNAVAILABLE) {
                mPlayPauseButtonView.setVisibility(View.GONE);
            } else {
                mPlayPauseButtonView.setVisibility(View.VISIBLE);
                if (state == PipManager.PLAYBACK_STATE_PLAYING) {
                    mPlayPauseButtonView.setImageResource(R.drawable.ic_pause_white);
                    mPlayPauseButtonView.setText(R.string.pip_pause);
                } else {
                    mPlayPauseButtonView.setImageResource(R.drawable.ic_play_arrow_white);
                    mPlayPauseButtonView.setText(R.string.pip_play);
                }
            }

            // Hide all the custom action buttons
            for (int i = 0; i < mCustomButtonViews.size(); i++) {
                mCustomButtonViews.get(i).setVisibility(View.GONE);
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
        for (int i = 0; i < mCustomButtonViews.size(); i++) {
            mCustomButtonViews.get(i).reset();
        }
    }

    /**
     * Sets the {@link Listener} to listen user actions.
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Updates the set of activity-defined actions.
     */
    public void setActions(List<RemoteAction> actions) {
        mCustomActions.clear();
        mCustomActions.addAll(actions);
        updateUserActions();
    }

    /**
     * Returns the focused control button view to animate focused button.
     */
    PipControlButtonView getFocusedButton() {
        return mFocusedChild;
    }
}
