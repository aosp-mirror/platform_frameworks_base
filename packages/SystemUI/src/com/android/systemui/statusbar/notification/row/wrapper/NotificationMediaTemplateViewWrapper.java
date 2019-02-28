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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row.wrapper;

import static com.android.systemui.Dependency.MAIN_HANDLER;

import android.app.Notification;
import android.content.Context;
import android.content.res.ColorStateList;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewStub;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.internal.R;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import java.util.Timer;
import java.util.TimerTask;

/**
 * Wraps a notification containing a media template
 */
public class NotificationMediaTemplateViewWrapper extends NotificationTemplateViewWrapper {

    private static final String TAG = "NotificationMediaTVW";
    private static final long PROGRESS_UPDATE_INTERVAL = 1000; // 1s
    private static final String COMPACT_MEDIA_TAG = "media";
    private final Handler mHandler = Dependency.get(MAIN_HANDLER);
    private Timer mSeekBarTimer;
    private View mActions;
    private SeekBar mSeekBar;
    private TextView mSeekBarElapsedTime;
    private TextView mSeekBarTotalTime;
    private long mDuration = 0;
    private MediaController mMediaController;
    private NotificationMediaManager mMediaManager;
    private View mSeekBarView;
    private Context mContext;

    private SeekBar.OnSeekBarChangeListener mSeekListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            if (mMediaController != null && canSeekMedia()) {
                mMediaController.getTransportControls().seekTo(mSeekBar.getProgress());
            }
        }
    };

    private MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onSessionDestroyed() {
            clearTimer();
            mMediaController.unregisterCallback(this);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state.getState() != PlaybackState.STATE_PLAYING) {
                clearTimer();
            } else if (mSeekBarTimer == null) {
                startTimer();
            }
        }
    };

    protected NotificationMediaTemplateViewWrapper(Context ctx, View view,
            ExpandableNotificationRow row) {
        super(ctx, view, row);
        mContext = ctx;
        mMediaManager = Dependency.get(NotificationMediaManager.class);
    }

    private void resolveViews() {
        mActions = mView.findViewById(com.android.internal.R.id.media_actions);

        final MediaSession.Token token = mRow.getEntry().notification.getNotification().extras
                .getParcelable(Notification.EXTRA_MEDIA_SESSION);

        boolean showCompactSeekbar = mMediaManager.getShowCompactMediaSeekbar();
        if (token == null || (COMPACT_MEDIA_TAG.equals(mView.getTag()) && !showCompactSeekbar)) {
            if (mSeekBarView != null) {
                mSeekBarView.setVisibility(View.GONE);
            }
            return;
        }

        // Check for existing media controller and clean up / create as necessary
        if (mMediaController == null || !mMediaController.getSessionToken().equals(token)) {
            if (mMediaController != null) {
                mMediaController.unregisterCallback(mMediaCallback);
            }
            mMediaController = new MediaController(mContext, token);
        }

        if (mMediaController.getMetadata() != null) {
            long duration = mMediaController.getMetadata().getLong(
                    MediaMetadata.METADATA_KEY_DURATION);
            if (duration <= 0) {
                // Don't include the seekbar if this is a livestream
                Log.d(TAG, "removing seekbar");
                if (mSeekBarView != null) {
                    mSeekBarView.setVisibility(View.GONE);
                }
                return;
            } else {
                // Otherwise, make sure the seekbar is visible
                if (mSeekBarView != null) {
                    mSeekBarView.setVisibility(View.VISIBLE);
                }
            }
        }

        // Inflate the seekbar template
        ViewStub stub = mView.findViewById(R.id.notification_media_seekbar_container);
        if (stub instanceof ViewStub) {
            LayoutInflater layoutInflater = LayoutInflater.from(stub.getContext());
            stub.setLayoutInflater(layoutInflater);
            stub.setLayoutResource(R.layout.notification_material_media_seekbar);
            mSeekBarView = stub.inflate();

            mSeekBar = mSeekBarView.findViewById(R.id.notification_media_progress_bar);
            mSeekBar.setOnSeekBarChangeListener(mSeekListener);

            mSeekBarElapsedTime = mSeekBarView.findViewById(R.id.notification_media_elapsed_time);
            mSeekBarTotalTime = mSeekBarView.findViewById(R.id.notification_media_total_time);

            if (mSeekBarTimer == null) {
                // Disable seeking if it is not supported for this media session
                if (!canSeekMedia()) {
                    mSeekBar.getThumb().setAlpha(0);
                    mSeekBar.setEnabled(false);
                } else {
                    mSeekBar.getThumb().setAlpha(255);
                    mSeekBar.setEnabled(true);
                }

                startTimer();

                mMediaController.registerCallback(mMediaCallback);
            }
        }
        updateSeekBarTint(mSeekBarView);
    }

    private void startTimer() {
        clearTimer();
        mSeekBarTimer = new Timer(true /* isDaemon */);
        mSeekBarTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                mHandler.post(mUpdatePlaybackUi);
            }
        }, 0, PROGRESS_UPDATE_INTERVAL);
    }

    private void clearTimer() {
        if (mSeekBarTimer != null) {
            // TODO: also trigger this when the notification panel is collapsed
            mSeekBarTimer.cancel();
            mSeekBarTimer.purge();
            mSeekBarTimer = null;
        }
    }

    private boolean canSeekMedia() {
        if (mMediaController == null || mMediaController.getPlaybackState() == null) {
            return false;
        }

        long actions = mMediaController.getPlaybackState().getActions();
        return (actions == 0 || (actions & PlaybackState.ACTION_SEEK_TO) != 0);
    }

    protected final Runnable mUpdatePlaybackUi = new Runnable() {
        @Override
        public void run() {
            if (mMediaController != null && mMediaController.getMetadata() != null
                    && mSeekBar != null) {
                long position = mMediaController.getPlaybackState().getPosition();
                long duration = mMediaController.getMetadata().getLong(
                        MediaMetadata.METADATA_KEY_DURATION);

                if (mDuration != duration) {
                    mDuration = duration;
                    mSeekBar.setMax((int) mDuration);
                    mSeekBarTotalTime.setText(millisecondsToTimeString(duration));
                }
                mSeekBar.setProgress((int) position);

                mSeekBarElapsedTime.setText(millisecondsToTimeString(position));
            } else {
                // We no longer have a media session / notification
                clearTimer();
            }
        }
    };

    private String millisecondsToTimeString(long milliseconds) {
        long seconds = milliseconds / 1000;
        String text = DateUtils.formatElapsedTime(seconds);
        return text;
    }

    @Override
    public void onContentUpdated(ExpandableNotificationRow row) {
        // Reinspect the notification. Before the super call, because the super call also updates
        // the transformation types and we need to have our values set by then.
        resolveViews();
        super.onContentUpdated(row);
    }

    private void updateSeekBarTint(View seekBarContainer) {
        if (seekBarContainer == null) {
            return;
        }

        if (this.getNotificationHeader() == null) {
            return;
        }

        int tintColor = getNotificationHeader().getOriginalIconColor();
        mSeekBarElapsedTime.setTextColor(tintColor);
        mSeekBarTotalTime.setTextColor(tintColor);

        ColorStateList tintList = ColorStateList.valueOf(tintColor);
        mSeekBar.setThumbTintList(tintList);
        tintList = tintList.withAlpha(192); // 75%
        mSeekBar.setProgressTintList(tintList);
        tintList = tintList.withAlpha(128); // 50%
        mSeekBar.setProgressBackgroundTintList(tintList);
    }

    @Override
    protected void updateTransformedTypes() {
        // This also clears the existing types
        super.updateTransformedTypes();
        if (mActions != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_ACTIONS,
                    mActions);
        }
    }

    @Override
    public boolean isDimmable() {
        return getCustomBackgroundColor() == 0;
    }

    @Override
    public boolean shouldClipToRounding(boolean topRounded, boolean bottomRounded) {
        return true;
    }
}
