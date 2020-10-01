/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.graphics.Color;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Controller for {@link PipControlsView}.
 */
public class PipControlsViewController {
    private static final String TAG = PipControlsViewController.class.getSimpleName();

    private static final float DISABLED_ACTION_ALPHA = 0.54f;

    private final PipControlsView mView;
    private final PipManager mPipManager;
    private final LayoutInflater mLayoutInflater;
    private final Handler mHandler;
    private final PipControlButtonView mPlayPauseButtonView;
    private MediaController mMediaController;
    private PipControlButtonView mFocusedChild;
    private Listener mListener;
    private ArrayList<PipControlButtonView> mCustomButtonViews = new ArrayList<>();
    private List<RemoteAction> mCustomActions = new ArrayList<>();

    public PipControlsView getView() {
        return mView;
    }

    /**
     * An interface to listen user action.
     */
    public interface Listener {
        /**
         * Called when a user clicks close PIP button.
         */
        void onClosed();
    }

    private View.OnAttachStateChangeListener
            mOnAttachStateChangeListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    updateMediaController();
                    mPipManager.addMediaListener(mPipMediaListener);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    mPipManager.removeMediaListener(mPipMediaListener);
                }
            };

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateUserActions();
        }
    };

    private final PipManager.MediaListener mPipMediaListener = this::updateMediaController;

    private final View.OnFocusChangeListener
            mFocusChangeListener =
            new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    if (hasFocus) {
                        mFocusedChild = (PipControlButtonView) view;
                    } else if (mFocusedChild == view) {
                        mFocusedChild = null;
                    }
                }
            };


    @Inject
    public PipControlsViewController(PipControlsView view, PipManager pipManager,
            LayoutInflater layoutInflater, @Main Handler handler) {
        super();
        mView = view;
        mPipManager = pipManager;
        mLayoutInflater = layoutInflater;
        mHandler = handler;

        mView.addOnAttachStateChangeListener(mOnAttachStateChangeListener);
        if (mView.isAttachedToWindow()) {
            mOnAttachStateChangeListener.onViewAttachedToWindow(mView);
        }

        View fullButtonView = mView.getFullButtonView();
        fullButtonView.setOnFocusChangeListener(mFocusChangeListener);
        fullButtonView.setOnClickListener(v -> mPipManager.movePipToFullscreen());

        View closeButtonView = mView.getCloseButtonView();
        closeButtonView.setOnFocusChangeListener(mFocusChangeListener);
        closeButtonView.setOnClickListener(v -> {
            mPipManager.closePip();
            if (mListener != null) {
                mListener.onClosed();
            }
        });


        mPlayPauseButtonView = mView.getPlayPauseButtonView();
        mPlayPauseButtonView.setOnFocusChangeListener(mFocusChangeListener);
        mPlayPauseButtonView.setOnClickListener(v -> {
            if (mMediaController == null || mMediaController.getPlaybackState() == null) {
                return;
            }
            if (mPipManager.getPlaybackState() == PipManager.PLAYBACK_STATE_PAUSED) {
                mMediaController.getTransportControls().play();
            } else if (mPipManager.getPlaybackState() == PipManager.PLAYBACK_STATE_PLAYING) {
                mMediaController.getTransportControls().pause();
            }
            // View will be updated later in {@link mMediaControllerCallback}
        });
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
                        R.layout.tv_pip_custom_control, mView, false);
                mView.addView(buttonView);
                mCustomButtonViews.add(buttonView);
            }

            // Update the visibility of all views
            for (int i = 0; i < mCustomButtonViews.size(); i++) {
                mCustomButtonViews.get(i).setVisibility(
                        i < mCustomActions.size() ? View.VISIBLE : View.GONE);
            }

            // Update the state and visibility of the action buttons, and hide the rest
            for (int i = 0; i < mCustomActions.size(); i++) {
                final RemoteAction action = mCustomActions.get(i);
                PipControlButtonView actionView = mCustomButtonViews.get(i);

                // TODO: Check if the action drawable has changed before we reload it
                action.getIcon().loadDrawableAsync(mView.getContext(), d -> {
                    d.setTint(Color.WHITE);
                    actionView.setImageDrawable(d);
                }, mHandler);
                actionView.setText(action.getContentDescription());
                if (action.isEnabled()) {
                    actionView.setOnClickListener(v -> {
                        try {
                            action.getActionIntent().send();
                        } catch (PendingIntent.CanceledException e) {
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
}
