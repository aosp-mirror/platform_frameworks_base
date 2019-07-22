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

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.content.res.ColorStateList;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.metrics.LogMaker;
import android.os.Handler;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import java.util.Timer;

/**
 * Wraps a notification containing a media template
 */
public class NotificationMediaTemplateViewWrapper extends NotificationTemplateViewWrapper {

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
    private MediaMetadata mMediaMetadata;
    private NotificationMediaManager mMediaManager;
    private View mSeekBarView;
    private Context mContext;
    private MetricsLogger mMetricsLogger;

    @VisibleForTesting
    protected SeekBar.OnSeekBarChangeListener mSeekListener =
            new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            if (mMediaController != null) {
                mMediaController.getTransportControls().seekTo(mSeekBar.getProgress());
                mMetricsLogger.write(newLog(MetricsEvent.TYPE_UPDATE));
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
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            if (state == null) {
                return;
            }

            if (state.getState() != PlaybackState.STATE_PLAYING) {
                // Update the UI once, in case playback info changed while we were paused
                updatePlaybackUi(state);
                clearTimer();
            } else if (mSeekBarTimer == null && mSeekBarView != null
                    && mSeekBarView.getVisibility() != View.GONE) {
                startTimer();
            }
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            if (mMediaMetadata == null || !mMediaMetadata.equals(metadata)) {
                mMediaMetadata = metadata;
                updateDuration();
            }
        }
    };

    protected NotificationMediaTemplateViewWrapper(Context ctx, View view,
            ExpandableNotificationRow row) {
        super(ctx, view, row);
        mContext = ctx;
        mMediaManager = Dependency.get(NotificationMediaManager.class);
        mMetricsLogger = Dependency.get(MetricsLogger.class);
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
        boolean controllerUpdated = false;
        if (mMediaController == null || !mMediaController.getSessionToken().equals(token)) {
            if (mMediaController != null) {
                mMediaController.unregisterCallback(mMediaCallback);
            }
            mMediaController = new MediaController(mContext, token);
            controllerUpdated = true;
        }

        mMediaMetadata = mMediaController.getMetadata();
        if (mMediaMetadata != null) {
            long duration = mMediaMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (duration <= 0) {
                // Don't include the seekbar if this is a livestream
                if (mSeekBarView != null && mSeekBarView.getVisibility() != View.GONE) {
                    mSeekBarView.setVisibility(View.GONE);
                    mMetricsLogger.write(newLog(MetricsEvent.TYPE_CLOSE));
                    clearTimer();
                } else if (mSeekBarView == null && controllerUpdated) {
                    // Only log if the controller changed, otherwise we would log multiple times for
                    // the same notification when user pauses/resumes
                    mMetricsLogger.write(newLog(MetricsEvent.TYPE_CLOSE));
                }
                return;
            } else if (mSeekBarView != null && mSeekBarView.getVisibility() == View.GONE) {
                // Otherwise, make sure the seekbar is visible
                mSeekBarView.setVisibility(View.VISIBLE);
                mMetricsLogger.write(newLog(MetricsEvent.TYPE_OPEN));
                updateDuration();
                startTimer();
            }
        }

        // Inflate the seekbar template
        ViewStub stub = mView.findViewById(R.id.notification_media_seekbar_container);
        if (stub instanceof ViewStub) {
            LayoutInflater layoutInflater = LayoutInflater.from(stub.getContext());
            stub.setLayoutInflater(layoutInflater);
            stub.setLayoutResource(R.layout.notification_material_media_seekbar);
            mSeekBarView = stub.inflate();
            mMetricsLogger.write(newLog(MetricsEvent.TYPE_OPEN));

            mSeekBar = mSeekBarView.findViewById(R.id.notification_media_progress_bar);
            mSeekBar.setOnSeekBarChangeListener(mSeekListener);

            mSeekBarElapsedTime = mSeekBarView.findViewById(R.id.notification_media_elapsed_time);
            mSeekBarTotalTime = mSeekBarView.findViewById(R.id.notification_media_total_time);

            if (mSeekBarTimer == null) {
                if (mMediaController != null && canSeekMedia(mMediaController.getPlaybackState())) {
                    // Log initial state, since it will not be updated
                    mMetricsLogger.write(newLog(MetricsEvent.TYPE_DETAIL, 1));
                } else {
                    setScrubberVisible(false);
                }
                updateDuration();
                startTimer();
                mMediaController.registerCallback(mMediaCallback);
            }
        }
        updateSeekBarTint(mSeekBarView);
    }

    private void startTimer() {
        clearTimer();
        updateSeekBarView();
    }

    private void updateSeekBarView() {
        mSeekBarView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener(){
            @Override
            public boolean onPreDraw() {
                mSeekBarView.getViewTreeObserver().removeOnPreDrawListener(this);
                mHandler.postDelayed(mOnUpdateTimerTick, PROGRESS_UPDATE_INTERVAL);
                return true;
            }
        });
    }

    private void clearTimer() {
        if (mSeekBarTimer != null) {
            // TODO: also trigger this when the notification panel is collapsed
            mSeekBarTimer.cancel();
            mSeekBarTimer.purge();
            mSeekBarTimer = null;
        }
    }

    private boolean canSeekMedia(@Nullable PlaybackState state) {
        if (state == null) {
            return false;
        }

        long actions = state.getActions();
        return ((actions & PlaybackState.ACTION_SEEK_TO) != 0);
    }

    private void setScrubberVisible(boolean isVisible) {
        if (mSeekBar == null || mSeekBar.isEnabled() == isVisible) {
            return;
        }

        mSeekBar.getThumb().setAlpha(isVisible ? 255 : 0);
        mSeekBar.setEnabled(isVisible);
        mMetricsLogger.write(newLog(MetricsEvent.TYPE_DETAIL, isVisible ? 1 : 0));
    }

    private void updateDuration() {
        if (mMediaMetadata != null && mSeekBar != null) {
            long duration = mMediaMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (mDuration != duration) {
                mDuration = duration;
                mSeekBar.setMax((int) mDuration);
                mSeekBarTotalTime.setText(millisecondsToTimeString(duration));
            }
        }
    }

    protected final Runnable mOnUpdateTimerTick = new Runnable() {
        @Override
        public void run() {
            if (mMediaController != null && mSeekBar != null) {
                PlaybackState playbackState = mMediaController.getPlaybackState();
                updateSeekBarView();
                if (playbackState != null) {
                    updatePlaybackUi(playbackState);
                } else {
                    clearTimer();
                }
            } else {
                clearTimer();
            }
        }
    };

    private void updatePlaybackUi(PlaybackState state) {
        long position = state.getPosition();
        mSeekBar.setProgress((int) position);

        mSeekBarElapsedTime.setText(millisecondsToTimeString(position));

        // Update scrubber in case available actions have changed
        setScrubberVisible(canSeekMedia(state));
    }

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
        mSeekBarTotalTime.setShadowLayer(1.5f, 1.5f, 1.5f, mBackgroundColor);

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

    /**
     * Returns an initialized LogMaker for logging changes to the seekbar
     * @return new LogMaker
     */
    private LogMaker newLog(int event) {
        String packageName = mRow.getEntry().notification.getPackageName();

        return new LogMaker(MetricsEvent.MEDIA_NOTIFICATION_SEEKBAR)
                .setType(event)
                .setPackageName(packageName);
    }

    /**
     * Returns an initialized LogMaker for logging changes with subtypes
     * @return new LogMaker
     */
    private LogMaker newLog(int event, int subtype) {
        String packageName = mRow.getEntry().notification.getPackageName();
        return new LogMaker(MetricsEvent.MEDIA_NOTIFICATION_SEEKBAR)
                .setType(event)
                .setSubtype(subtype)
                .setPackageName(packageName);
    }
}
