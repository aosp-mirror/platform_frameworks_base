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

package com.android.internal.widget;

import java.lang.ref.WeakReference;

import com.android.internal.widget.LockScreenWidgetCallback;
import com.android.internal.widget.LockScreenWidgetInterface;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.media.IRemoteControlDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
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

public class TransportControlView extends FrameLayout implements OnClickListener,
        LockScreenWidgetInterface {

    private static final int MSG_UPDATE_STATE = 100;
    private static final int MSG_SET_METADATA = 101;
    private static final int MSG_SET_TRANSPORT_CONTROLS = 102;
    private static final int MSG_SET_ARTWORK = 103;
    private static final int MSG_SET_GENERATION_ID = 104;
    private static final int MAXDIM = 512;
    protected static final boolean DEBUG = true;
    protected static final String TAG = "TransportControlView";

    private ImageView mAlbumArt;
    private TextView mTrackTitle;
    private ImageView mBtnPrev;
    private ImageView mBtnPlay;
    private ImageView mBtnNext;
    private int mClientGeneration;
    private Metadata mMetadata = new Metadata();
    private boolean mAttached;
    private ComponentName mClientName;
    private int mTransportControlFlags;
    private int mPlayState;
    private AudioManager mAudioManager;
    private LockScreenWidgetCallback mWidgetCallbacks;
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
                    mMetadata.bitmap = (Bitmap) msg.obj;
                    mAlbumArt.setImageBitmap(mMetadata.bitmap);
                }
                break;

            case MSG_SET_GENERATION_ID:
                if (mWidgetCallbacks != null) {
                    boolean clearing = msg.arg2 != 0;
                    if (DEBUG) Log.v(TAG, "New genId = " + msg.arg1 + ", clearing = " + clearing);
                    if (!clearing) {
                        mWidgetCallbacks.requestShow(TransportControlView.this);
                    } else {
                        mWidgetCallbacks.requestHide(TransportControlView.this);
                    }
                }
                mClientGeneration = msg.arg1;
                mClientName = (ComponentName) msg.obj;
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

        public void setPlaybackState(int generationId, int state) {
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

        public void setTransportControlFlags(int generationId, int flags) {
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

        public void setCurrentClientId(int clientGeneration, ComponentName clientEventReceiver,
                boolean clearing) throws RemoteException {
            Handler handler = mLocalHandler.get();
            if (handler != null) {
                handler.obtainMessage(MSG_SET_GENERATION_ID,
                    clientGeneration, (clearing ? 1 : 0), clientEventReceiver).sendToTarget();
            }
        }
    };

    public TransportControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Log.v(TAG, "Create TCV " + this);
        mAudioManager = new AudioManager(mContext);
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
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            if (DEBUG) Log.v(TAG, "Unregistering TCV " + this);
            mAudioManager.unregisterRemoteControlDisplay(mIRCD);
        }
        mAttached = false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int dim = Math.min(MAXDIM, Math.max(getWidth(), getHeight()));
//        Log.v(TAG, "setting max bitmap size: " + dim + "x" + dim);
//        mAudioManager.remoteControlDisplayUsesBitmapSize(mIRCD, dim, dim);
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
        setVisibilityBasedOnFlag(mBtnPrev, flags,
                RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_STOP);

        updatePlayPauseState(mPlayState);
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
                "updatePlayPauseState(), old=" + mPlayState + ", state=" + state);
        if (state == mPlayState) {
            return;
        }
        switch (state) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
                mBtnPlay.setImageResource(com.android.internal.R.drawable.ic_media_pause);
                break;

            case RemoteControlClient.PLAYSTATE_BUFFERING:
                mBtnPlay.setImageResource(com.android.internal.R.drawable.ic_media_stop);
                break;

            case RemoteControlClient.PLAYSTATE_PAUSED:
            default:
                mBtnPlay.setImageResource(com.android.internal.R.drawable.ic_media_play);
                break;
        }
        mPlayState = state;
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
            if (mWidgetCallbacks != null) {
                mWidgetCallbacks.userActivity(this);
            }
        }
    }

    private void sendMediaButtonClick(int keyCode) {
        // TODO: target to specific player based on mClientName
        KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        getContext().sendOrderedBroadcast(intent, null);

        keyEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
        intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        getContext().sendOrderedBroadcast(intent, null);
    }

    public void setCallback(LockScreenWidgetCallback callback) {
        mWidgetCallbacks = callback;
    }

}
