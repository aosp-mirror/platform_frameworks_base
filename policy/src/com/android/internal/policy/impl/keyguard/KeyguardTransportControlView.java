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

package com.android.internal.policy.impl.keyguard;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.IRemoteControlDisplay;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;

import java.lang.ref.WeakReference;
/**
 * This is the widget responsible for showing music controls in keyguard.
 */
public class KeyguardTransportControlView extends FrameLayout implements OnClickListener {

    private static final int MSG_UPDATE_STATE = 100;
    private static final int MSG_SET_METADATA = 101;
    private static final int MSG_SET_TRANSPORT_CONTROLS = 102;
    private static final int MSG_SET_ARTWORK = 103;
    private static final int MSG_SET_GENERATION_ID = 104;
    private static final int DISPLAY_TIMEOUT_MS = 5000; // 5s
    protected static final boolean DEBUG = false;
    protected static final String TAG = "TransportControlView";

    private ImageView mAlbumArt;
    private TextView mTrackTitle;
    private ImageView mBtnPrev;
    private ImageView mBtnPlay;
    private ImageView mBtnNext;
    private int mClientGeneration;
    private Metadata mMetadata = new Metadata();
    private boolean mAttached;
    private PendingIntent mClientIntent;
    private int mTransportControlFlags;
    private int mCurrentPlayState;
    private AudioManager mAudioManager;
    private IRemoteControlDisplayWeak mIRCD;

    /**
     * The metadata which should be populated into the view once we've been attached
     */
    private Bundle mPopulateMetadataWhenAttached = null;

    // This handler is required to ensure messages from IRCD are handled in sequence and on
    // the UI thread.
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_UPDATE_STATE:
                if (mClientGeneration == msg.arg1) updatePlayPauseState(msg.arg2);
                break;

            case MSG_SET_METADATA:
                if (mClientGeneration == msg.arg1) updateMetadata((Bundle) msg.obj);
                break;

            case MSG_SET_TRANSPORT_CONTROLS:
                if (mClientGeneration == msg.arg1) updateTransportControls(msg.arg2);
                break;

            case MSG_SET_ARTWORK:
                if (mClientGeneration == msg.arg1) {
                    if (mMetadata.bitmap != null) {
                        mMetadata.bitmap.recycle();
                    }
                    mMetadata.bitmap = (Bitmap) msg.obj;
                    mAlbumArt.setImageBitmap(mMetadata.bitmap);
                }
                break;

            case MSG_SET_GENERATION_ID:
                if (DEBUG) Log.v(TAG, "New genId = " + msg.arg1 + ", clearing = " + msg.arg2);
                mClientGeneration = msg.arg1;
                mClientIntent = (PendingIntent) msg.obj;
                break;

            }
        }
    };

    /**
     * This class is required to have weak linkage to the current TransportControlView
     * because the remote process can hold a strong reference to this binder object and
     * we can't predict when it will be GC'd in the remote process. Without this code, it
     * would allow a heavyweight object to be held on this side of the binder when there's
     * no requirement to run a GC on the other side.
     */
    private static class IRemoteControlDisplayWeak extends IRemoteControlDisplay.Stub {
        private WeakReference<Handler> mLocalHandler;

        IRemoteControlDisplayWeak(Handler handler) {
            mLocalHandler = new WeakReference<Handler>(handler);
        }

        public void setPlaybackState(int generationId, int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_UPDATE_STATE, generationId, state).sendToTarget();
            }
        }

        public void setMetadata(int generationId, Bundle metadata) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_METADATA, generationId, 0, metadata).sendToTarget();
            }
        }

        public void setTransportControlInfo(int generationId, int flags, int posCapabilities) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_TRANSPORT_CONTROLS, generationId, flags)
                        .sendToTarget();
            }
        }

        public void setArtwork(int generationId, Bitmap bitmap) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_ARTWORK, generationId, 0, bitmap).sendToTarget();
            }
        }

        public void setAllMetadata(int generationId, Bundle metadata, Bitmap bitmap) {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_METADATA, generationId, 0, metadata).sendToTarget();
                handler.obtainMessage(MSG_SET_ARTWORK, generationId, 0, bitmap).sendToTarget();
            }
        }

        public void setCurrentClientId(int clientGeneration, PendingIntent mediaIntent,
                boolean clearing) throws RemoteException {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_GENERATION_ID,
                    clientGeneration, (clearing ? 1 : 0), mediaIntent).sendToTarget();
            }
        }
    };

    public KeyguardTransportControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (DEBUG) Log.v(TAG, "Create TCV " + this);
        mAudioManager = new AudioManager(mContext);
        mCurrentPlayState = RemoteControlClient.PLAYSTATE_NONE; // until we get a callback
        mIRCD = new IRemoteControlDisplayWeak(mHandler);
    }

    private void updateTransportControls(int transportControlFlags) {
        mTransportControlFlags = transportControlFlags;
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mTrackTitle = (TextView) findViewById(R.id.title);
        mTrackTitle.setSelected(true); // enable marquee
        mAlbumArt = (ImageView) findViewById(R.id.albumart);
        mBtnPrev = (ImageView) findViewById(R.id.btn_prev);
        mBtnPlay = (ImageView) findViewById(R.id.btn_play);
        mBtnNext = (ImageView) findViewById(R.id.btn_next);
        final View buttons[] = { mBtnPrev, mBtnPlay, mBtnNext };
        for (View view : buttons) {
            view.setOnClickListener(this);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (DEBUG) Log.v(TAG, "onAttachToWindow()");
        if (mPopulateMetadataWhenAttached != null) {
            updateMetadata(mPopulateMetadataWhenAttached);
            mPopulateMetadataWhenAttached = null;
        }
        if (!mAttached) {
            if (DEBUG) Log.v(TAG, "Registering TCV " + this);
            mAudioManager.registerRemoteControlDisplay(mIRCD);
        }
        mAttached = true;
    }

    @Override
    protected void onSizeChanged (int w, int h, int oldw, int oldh) {
        if (mAttached) {
            int dim = Math.min(512, Math.max(w, h));
            if (DEBUG) Log.v(TAG, "TCV uses bitmap size=" + dim);
            mAudioManager.remoteControlDisplayUsesBitmapSize(mIRCD, dim, dim);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        if (DEBUG) Log.v(TAG, "onDetachFromWindow()");
        super.onDetachedFromWindow();
        if (mAttached) {
            if (DEBUG) Log.v(TAG, "Unregistering TCV " + this);
            mAudioManager.unregisterRemoteControlDisplay(mIRCD);
        }
        mAttached = false;
    }

    class Metadata {
        private String artist;
        private String trackTitle;
        private String albumTitle;
        private Bitmap bitmap;

        public String toString() {
            return "Metadata[artist=" + artist + " trackTitle=" + trackTitle + " albumTitle=" + albumTitle + "]";
        }
    }

    private String getMdString(Bundle data, int id) {
        return data.getString(Integer.toString(id));
    }

    private void updateMetadata(Bundle data) {
        if (mAttached) {
            mMetadata.artist = getMdString(data, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            mMetadata.trackTitle = getMdString(data, MediaMetadataRetriever.METADATA_KEY_TITLE);
            mMetadata.albumTitle = getMdString(data, MediaMetadataRetriever.METADATA_KEY_ALBUM);
            populateMetadata();
        } else {
            mPopulateMetadataWhenAttached = data;
        }
    }

    /**
     * Populates the given metadata into the view
     */
    private void populateMetadata() {
        StringBuilder sb = new StringBuilder();
        int trackTitleLength = 0;
        if (!TextUtils.isEmpty(mMetadata.trackTitle)) {
            sb.append(mMetadata.trackTitle);
            trackTitleLength = mMetadata.trackTitle.length();
        }
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
        mTrackTitle.setText(sb.toString(), TextView.BufferType.SPANNABLE);
        Spannable str = (Spannable) mTrackTitle.getText();
        if (trackTitleLength != 0) {
            str.setSpan(new ForegroundColorSpan(0xffffffff), 0, trackTitleLength,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            trackTitleLength++;
        }
        if (sb.length() > trackTitleLength) {
            str.setSpan(new ForegroundColorSpan(0x7fffffff), trackTitleLength, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        mAlbumArt.setImageBitmap(mMetadata.bitmap);
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

    private static void setVisibilityBasedOnFlag(View view, int flags, int flag) {
        if ((flags & flag) != 0) {
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.GONE);
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
                imageResId = com.android.internal.R.drawable.stat_sys_warning;
                // TODO use more specific image description string for warning, but here the "play"
                //      message is still valid because this button triggers a play command.
                imageDescId = com.android.internal.R.string.lockscreen_transport_play_description;
                break;

            case RemoteControlClient.PLAYSTATE_PLAYING:
                imageResId = com.android.internal.R.drawable.ic_media_pause;
                imageDescId = com.android.internal.R.string.lockscreen_transport_pause_description;
                break;

            case RemoteControlClient.PLAYSTATE_BUFFERING:
                imageResId = com.android.internal.R.drawable.ic_media_stop;
                imageDescId = com.android.internal.R.string.lockscreen_transport_stop_description;
                break;

            case RemoteControlClient.PLAYSTATE_PAUSED:
            default:
                imageResId = com.android.internal.R.drawable.ic_media_play;
                imageDescId = com.android.internal.R.string.lockscreen_transport_play_description;
                break;
        }
        mBtnPlay.setImageResource(imageResId);
        mBtnPlay.setContentDescription(getResources().getString(imageDescId));
        mCurrentPlayState = state;
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

    private void sendMediaButtonClick(int keyCode) {
        if (mClientIntent == null) {
            // Shouldn't be possible because this view should be hidden in this case.
            Log.e(TAG, "sendMediaButtonClick(): No client is currently registered");
            return;
        }
        // use the registered PendingIntent that will be processed by the registered
        //    media button event receiver, which is the component of mClientIntent
        KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        try {
            mClientIntent.send(getContext(), 0, intent);
        } catch (CanceledException e) {
            Log.e(TAG, "Error sending intent for media button down: "+e);
            e.printStackTrace();
        }

        keyEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
        intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        try {
            mClientIntent.send(getContext(), 0, intent);
        } catch (CanceledException e) {
            Log.e(TAG, "Error sending intent for media button up: "+e);
            e.printStackTrace();
        }
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
