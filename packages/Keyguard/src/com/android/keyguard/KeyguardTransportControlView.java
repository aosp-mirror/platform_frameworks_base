/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadataEditor;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.media.RemoteController;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.transition.ChangeBounds;
import android.transition.ChangeText;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * This is the widget responsible for showing music controls in keyguard.
 */
public class KeyguardTransportControlView extends FrameLayout {

    private static final int DISPLAY_TIMEOUT_MS = 5000; // 5s
    private static final int RESET_TO_METADATA_DELAY = 5000;
    protected static final boolean DEBUG = false;
    protected static final String TAG = "TransportControlView";

    private static final boolean ANIMATE_TRANSITIONS = false;

    private ViewGroup mMetadataContainer;
    private ViewGroup mInfoContainer;
    private TextView mTrackTitle;
    private TextView mTrackArtistAlbum;

    private View mTransientSeek;
    private SeekBar mTransientSeekBar;
    private TextView mTransientSeekTimeElapsed;
    private TextView mTransientSeekTimeRemaining;

    private ImageView mBtnPrev;
    private ImageView mBtnPlay;
    private ImageView mBtnNext;
    private Metadata mMetadata = new Metadata();
    private int mTransportControlFlags;
    private int mCurrentPlayState;
    private AudioManager mAudioManager;
    private RemoteController mRemoteController;

    private ImageView mBadge;

    private boolean mSeekEnabled;
    private boolean mUserSeeking;
    private java.text.DateFormat mFormat;

    /**
     * The metadata which should be populated into the view once we've been attached
     */
    private RemoteController.MetadataEditor mPopulateMetadataWhenAttached = null;

    private RemoteController.OnClientUpdateListener mRCClientUpdateListener =
            new RemoteController.OnClientUpdateListener() {
        @Override
        public void onClientChange(boolean clearing) {
            if (clearing) {
                clearMetadata();
            }
        }

        @Override
        public void onClientPlaybackStateUpdate(int state) {
            setSeekBarsEnabled(false);
            updatePlayPauseState(state);
        }

        @Override
        public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            setSeekBarsEnabled(mMetadata != null && mMetadata.duration > 0);
            updatePlayPauseState(state);
            if (DEBUG) Log.d(TAG, "onClientPlaybackStateUpdate(state=" + state +
                    ", stateChangeTimeMs=" + stateChangeTimeMs + ", currentPosMs=" + currentPosMs +
                    ", speed=" + speed + ")");
        }

        @Override
        public void onClientTransportControlUpdate(int transportControlFlags) {
            updateTransportControls(transportControlFlags);
        }

        @Override
        public void onClientMetadataUpdate(RemoteController.MetadataEditor metadataEditor) {
            updateMetadata(metadataEditor);
        }
    };

    private final Runnable mUpdateSeekBars = new Runnable() {
        public void run() {
            if (updateSeekBars()) {
                postDelayed(this, 1000);
            }
        }
    };

    private final Runnable mResetToMetadata = new Runnable() {
        public void run() {
            resetToMetadata();
        }
    };

    private final OnClickListener mTransportCommandListener = new OnClickListener() {
        public void onClick(View v) {
            int keyCode = -1;
            if (v == mBtnPrev) {
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            } else if (v == mBtnNext) {
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
            } else if (v == mBtnPlay) {
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            }
            if (keyCode != -1) {
                sendMediaButtonClick(keyCode);
            }
        }
    };

    private final OnLongClickListener mTransportShowSeekBarListener = new OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            if (mSeekEnabled) {
                return tryToggleSeekBar();
            }
            return false;
        }
    };

    private final SeekBar.OnSeekBarChangeListener mOnSeekBarChangeListener =
            new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) {
                scrubTo(progress);
                delayResetToMetadata();
            }
            updateSeekDisplay();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            mUserSeeking = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mUserSeeking = false;
        }
    };

    private static final int TRANSITION_DURATION = 200;
    private final TransitionSet mMetadataChangeTransition;

    KeyguardHostView.TransportControlCallback mTransportControlCallback;

    private final KeyguardUpdateMonitorCallback mUpdateMonitor
            = new KeyguardUpdateMonitorCallback() {
        public void onScreenTurnedOff(int why) {
            setEnableMarquee(false);
        };
        public void onScreenTurnedOn() {
            setEnableMarquee(true);
        };
    };

    public KeyguardTransportControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (DEBUG) Log.v(TAG, "Create TCV " + this);
        mAudioManager = new AudioManager(mContext);
        mCurrentPlayState = RemoteControlClient.PLAYSTATE_NONE; // until we get a callback
        mRemoteController = new RemoteController(context, mRCClientUpdateListener);

        final DisplayMetrics dm = context.getResources().getDisplayMetrics();
        final int dim = Math.max(dm.widthPixels, dm.heightPixels);
        mRemoteController.setArtworkConfiguration(true, dim, dim);

        final ChangeText tc = new ChangeText();
        tc.setChangeBehavior(ChangeText.CHANGE_BEHAVIOR_OUT_IN);
        final TransitionSet inner = new TransitionSet();
        inner.addTransition(tc).addTransition(new ChangeBounds());
        final TransitionSet tg = new TransitionSet();
        tg.addTransition(new Fade(Fade.OUT)).addTransition(inner).
                addTransition(new Fade(Fade.IN));
        tg.setOrdering(TransitionSet.ORDERING_SEQUENTIAL);
        tg.setDuration(TRANSITION_DURATION);
        mMetadataChangeTransition = tg;
    }

    private void updateTransportControls(int transportControlFlags) {
        mTransportControlFlags = transportControlFlags;
        setSeekBarsEnabled(
                (transportControlFlags & RemoteControlClient.FLAG_KEY_MEDIA_POSITION_UPDATE) != 0);
    }

    void setSeekBarsEnabled(boolean enabled) {
        if (enabled == mSeekEnabled) return;

        mSeekEnabled = enabled;
        if (mTransientSeek.getVisibility() == VISIBLE) {
            mTransientSeek.setVisibility(INVISIBLE);
            mMetadataContainer.setVisibility(VISIBLE);
            mUserSeeking = false;
            cancelResetToMetadata();
        }
        if (enabled) {
            mUpdateSeekBars.run();
            postDelayed(mUpdateSeekBars, 1000);
        } else {
            removeCallbacks(mUpdateSeekBars);
        }
    }

    public void setTransportControlCallback(KeyguardHostView.TransportControlCallback
            transportControlCallback) {
        mTransportControlCallback = transportControlCallback;
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mTrackTitle != null) mTrackTitle.setSelected(enabled);
        if (mTrackArtistAlbum != null) mTrackTitle.setSelected(enabled);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mInfoContainer = (ViewGroup) findViewById(R.id.info_container);
        mMetadataContainer = (ViewGroup) findViewById(R.id.metadata_container);
        mBadge = (ImageView) findViewById(R.id.badge);
        mTrackTitle = (TextView) findViewById(R.id.title);
        mTrackArtistAlbum = (TextView) findViewById(R.id.artist_album);
        mTransientSeek = findViewById(R.id.transient_seek);
        mTransientSeekBar = (SeekBar) findViewById(R.id.transient_seek_bar);
        mTransientSeekBar.setOnSeekBarChangeListener(mOnSeekBarChangeListener);
        mTransientSeekTimeElapsed = (TextView) findViewById(R.id.transient_seek_time_elapsed);
        mTransientSeekTimeRemaining = (TextView) findViewById(R.id.transient_seek_time_remaining);
        mBtnPrev = (ImageView) findViewById(R.id.btn_prev);
        mBtnPlay = (ImageView) findViewById(R.id.btn_play);
        mBtnNext = (ImageView) findViewById(R.id.btn_next);
        final View buttons[] = { mBtnPrev, mBtnPlay, mBtnNext };
        for (View view : buttons) {
            view.setOnClickListener(mTransportCommandListener);
            view.setOnLongClickListener(mTransportShowSeekBarListener);
        }
        final boolean screenOn = KeyguardUpdateMonitor.getInstance(mContext).isScreenOn();
        setEnableMarquee(screenOn);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (DEBUG) Log.v(TAG, "onAttachToWindow()");
        if (mPopulateMetadataWhenAttached != null) {
            updateMetadata(mPopulateMetadataWhenAttached);
            mPopulateMetadataWhenAttached = null;
        }
        if (DEBUG) Log.v(TAG, "Registering TCV " + this);
        mAudioManager.registerRemoteController(mRemoteController);
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateMonitor);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        final int dim = Math.max(dm.widthPixels, dm.heightPixels);
        mRemoteController.setArtworkConfiguration(true, dim, dim);
    }

    @Override
    public void onDetachedFromWindow() {
        if (DEBUG) Log.v(TAG, "onDetachFromWindow()");
        super.onDetachedFromWindow();
        if (DEBUG) Log.v(TAG, "Unregistering TCV " + this);
        mAudioManager.unregisterRemoteController(mRemoteController);
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateMonitor);
        mUserSeeking = false;
        removeCallbacks(mUpdateSeekBars);
    }

    void setBadgeIcon(Drawable bmp) {
        mBadge.setImageDrawable(bmp);

        final ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        mBadge.setColorFilter(new ColorMatrixColorFilter(cm));
        mBadge.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        mBadge.setImageAlpha(0xef);
    }

    class Metadata {
        private String artist;
        private String trackTitle;
        private String albumTitle;
        private Bitmap bitmap;
        private long duration;

        public void clear() {
            artist = null;
            trackTitle = null;
            albumTitle = null;
            bitmap = null;
            duration = -1;
        }

        public String toString() {
            return "Metadata[artist=" + artist + " trackTitle=" + trackTitle +
                    " albumTitle=" + albumTitle + " duration=" + duration + "]";
        }
    }

    void clearMetadata() {
        mPopulateMetadataWhenAttached = null;
        mMetadata.clear();
        populateMetadata();
    }

    void updateMetadata(RemoteController.MetadataEditor data) {
        if (isAttachedToWindow()) {
            mMetadata.artist = data.getString(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                    mMetadata.artist);
            mMetadata.trackTitle = data.getString(MediaMetadataRetriever.METADATA_KEY_TITLE,
                    mMetadata.trackTitle);
            mMetadata.albumTitle = data.getString(MediaMetadataRetriever.METADATA_KEY_ALBUM,
                    mMetadata.albumTitle);
            mMetadata.duration = data.getLong(MediaMetadataRetriever.METADATA_KEY_DURATION, -1);
            mMetadata.bitmap = data.getBitmap(MediaMetadataEditor.BITMAP_KEY_ARTWORK,
                    mMetadata.bitmap);
            populateMetadata();
        } else {
            mPopulateMetadataWhenAttached = data;
        }
    }

    /**
     * Populates the given metadata into the view
     */
    private void populateMetadata() {
        if (ANIMATE_TRANSITIONS && isLaidOut() && mMetadataContainer.getVisibility() == VISIBLE) {
            TransitionManager.beginDelayedTransition(mMetadataContainer, mMetadataChangeTransition);
        }

        final String remoteClientPackage = mRemoteController.getRemoteControlClientPackageName();
        Drawable badgeIcon = null;
        try {
            badgeIcon = getContext().getPackageManager().getApplicationIcon(remoteClientPackage);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Couldn't get remote control client package icon", e);
        }
        setBadgeIcon(badgeIcon);
        if (!TextUtils.isEmpty(mMetadata.trackTitle)) {
            mTrackTitle.setText(mMetadata.trackTitle);
        }
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(mMetadata.artist)) {
            if (sb.length() != 0) {
                sb.append(" - ");
            }
            sb.append(mMetadata.artist);
        }
        if (!TextUtils.isEmpty(mMetadata.albumTitle)) {
            if (sb.length() != 0) {
                sb.append(" - ");
            }
            sb.append(mMetadata.albumTitle);
        }
        mTrackArtistAlbum.setText(sb.toString());

        if (mMetadata.duration >= 0) {
            setSeekBarsEnabled(true);
            setSeekBarDuration(mMetadata.duration);

            final String skeleton;

            if (mMetadata.duration >= 86400000) {
                skeleton = "DDD kk mm ss";
            } else if (mMetadata.duration >= 3600000) {
                skeleton = "kk mm ss";
            } else {
                skeleton = "mm ss";
            }
            mFormat = new SimpleDateFormat(DateFormat.getBestDateTimePattern(
                    getContext().getResources().getConfiguration().locale,
                    skeleton));
            mFormat.setTimeZone(TimeZone.getTimeZone("GMT+0"));
        } else {
            setSeekBarsEnabled(false);
        }

        KeyguardUpdateMonitor.getInstance(getContext()).dispatchSetBackground(
                mMetadata.bitmap);
        final int flags = mTransportControlFlags;
        setVisibilityBasedOnFlag(mBtnPrev, flags, RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);
        setVisibilityBasedOnFlag(mBtnNext, flags, RemoteControlClient.FLAG_KEY_MEDIA_NEXT);
        setVisibilityBasedOnFlag(mBtnPlay, flags,
                RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_STOP);

        updatePlayPauseState(mCurrentPlayState);
    }

    void updateSeekDisplay() {
        if (mMetadata != null && mRemoteController != null && mFormat != null) {
            final long timeElapsed = mRemoteController.getEstimatedMediaPosition();
            final long duration = mMetadata.duration;
            final long remaining = duration - timeElapsed;

            mTransientSeekTimeElapsed.setText(mFormat.format(new Date(timeElapsed)));
            mTransientSeekTimeRemaining.setText(mFormat.format(new Date(remaining)));

            if (DEBUG) Log.d(TAG, "updateSeekDisplay timeElapsed=" + timeElapsed +
                    " duration=" + duration + " remaining=" + remaining);
        }
    }

    boolean tryToggleSeekBar() {
        if (ANIMATE_TRANSITIONS) {
            TransitionManager.beginDelayedTransition(mInfoContainer);
        }
        if (mTransientSeek.getVisibility() == VISIBLE) {
            mTransientSeek.setVisibility(INVISIBLE);
            mMetadataContainer.setVisibility(VISIBLE);
            cancelResetToMetadata();
        } else {
            mTransientSeek.setVisibility(VISIBLE);
            mMetadataContainer.setVisibility(INVISIBLE);
            delayResetToMetadata();
        }
        mTransportControlCallback.userActivity();
        return true;
    }

    void resetToMetadata() {
        if (ANIMATE_TRANSITIONS) {
            TransitionManager.beginDelayedTransition(mInfoContainer);
        }
        if (mTransientSeek.getVisibility() == VISIBLE) {
            mTransientSeek.setVisibility(INVISIBLE);
            mMetadataContainer.setVisibility(VISIBLE);
        }
        // TODO Also hide ratings, if applicable
    }

    void delayResetToMetadata() {
        removeCallbacks(mResetToMetadata);
        postDelayed(mResetToMetadata, RESET_TO_METADATA_DELAY);
    }

    void cancelResetToMetadata() {
        removeCallbacks(mResetToMetadata);
    }

    void setSeekBarDuration(long duration) {
        mTransientSeekBar.setMax((int) duration);
    }

    void scrubTo(int progress) {
        mRemoteController.seekTo(progress);
        mTransportControlCallback.userActivity();
    }

    private static void setVisibilityBasedOnFlag(View view, int flags, int flag) {
        if ((flags & flag) != 0) {
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.INVISIBLE);
        }
    }

    private void updatePlayPauseState(int state) {
        if (DEBUG) Log.v(TAG,
                "updatePlayPauseState(), old=" + mCurrentPlayState + ", state=" + state);
        if (state == mCurrentPlayState) {
            return;
        }
        final int imageResId;
        final int imageDescId;
        switch (state) {
            case RemoteControlClient.PLAYSTATE_ERROR:
                imageResId = R.drawable.stat_sys_warning;
                // TODO use more specific image description string for warning, but here the "play"
                //      message is still valid because this button triggers a play command.
                imageDescId = R.string.keyguard_transport_play_description;
                break;

            case RemoteControlClient.PLAYSTATE_PLAYING:
                imageResId = R.drawable.ic_media_pause;
                imageDescId = R.string.keyguard_transport_pause_description;
                if (mSeekEnabled) {
                    postDelayed(mUpdateSeekBars, 1000);
                }
                break;

            case RemoteControlClient.PLAYSTATE_BUFFERING:
                imageResId = R.drawable.ic_media_stop;
                imageDescId = R.string.keyguard_transport_stop_description;
                break;

            case RemoteControlClient.PLAYSTATE_PAUSED:
            default:
                imageResId = R.drawable.ic_media_play;
                imageDescId = R.string.keyguard_transport_play_description;
                break;
        }

        if (state != RemoteControlClient.PLAYSTATE_PLAYING) {
            removeCallbacks(mUpdateSeekBars);
            updateSeekBars();
        }
        mBtnPlay.setImageResource(imageResId);
        mBtnPlay.setContentDescription(getResources().getString(imageDescId));
        mCurrentPlayState = state;
    }

    boolean updateSeekBars() {
        final int position = (int) mRemoteController.getEstimatedMediaPosition();
        if (position >= 0) {
            if (!mUserSeeking) {
                mTransientSeekBar.setProgress(position);
            }
            return true;
        }
        Log.w(TAG, "Updating seek bars; received invalid estimated media position (" +
                position + "). Disabling seek.");
        setSeekBarsEnabled(false);
        return false;
    }

    static class SavedState extends BaseSavedState {
        boolean clientPresent;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            this.clientPresent = in.readInt() != 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(this.clientPresent ? 1 : 0);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    private void sendMediaButtonClick(int keyCode) {
        // TODO We should think about sending these up/down events accurately with touch up/down
        // on the buttons, but in the near term this will interfere with the long press behavior.
        mRemoteController.sendMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        mRemoteController.sendMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));

        mTransportControlCallback.userActivity();
    }

    public boolean providesClock() {
        return false;
    }

    private boolean wasPlayingRecently(int state, long stateChangeTimeMs) {
        switch (state) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
            case RemoteControlClient.PLAYSTATE_FAST_FORWARDING:
            case RemoteControlClient.PLAYSTATE_REWINDING:
            case RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS:
            case RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS:
            case RemoteControlClient.PLAYSTATE_BUFFERING:
                // actively playing or about to play
                return true;
            case RemoteControlClient.PLAYSTATE_NONE:
                return false;
            case RemoteControlClient.PLAYSTATE_STOPPED:
            case RemoteControlClient.PLAYSTATE_PAUSED:
            case RemoteControlClient.PLAYSTATE_ERROR:
                // we have stopped playing, check how long ago
                if (DEBUG) {
                    if ((SystemClock.elapsedRealtime() - stateChangeTimeMs) < DISPLAY_TIMEOUT_MS) {
                        Log.v(TAG, "wasPlayingRecently: time < TIMEOUT was playing recently");
                    } else {
                        Log.v(TAG, "wasPlayingRecently: time > TIMEOUT");
                    }
                }
                return ((SystemClock.elapsedRealtime() - stateChangeTimeMs) < DISPLAY_TIMEOUT_MS);
            default:
                Log.e(TAG, "Unknown playback state " + state + " in wasPlayingRecently()");
                return false;
        }
    }
}
