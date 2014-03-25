/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.media.MediaFocusControl.RcClientDeathHandler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.util.Log;

import java.io.PrintWriter;

/**
 * @hide
 * Class to handle all the information about a media player, encapsulating information
 * about its use RemoteControlClient, playback type and volume... The lifecycle of each
 * instance is managed by android.media.MediaFocusControl, from its addition to the player stack
 * stack to its release.
 */
class MediaController implements DeathRecipient {

    // on purpose not using this classe's name, as it will only be used from MediaFocusControl
    private static final String TAG = "MediaFocusControl";
    private static final boolean DEBUG = false;

    /**
     * A global counter for RemoteControlClient identifiers
     */
    private static int sLastRccId = 0;

    //FIXME should be final static
    public MediaFocusControl mController;

    /**
     * The target for the ACTION_MEDIA_BUTTON events.
     * Always non null.
     */
    final public PendingIntent mMediaIntent;
    /**
     * The registered media button event receiver.
     * Always non null.
     */
    final public ComponentName mReceiverComponent;

    public int mRccId = RemoteControlClient.RCSE_ID_UNREGISTERED;

    public IBinder mToken;
    public String mCallingPackageName;
    public int mCallingUid;
    /**
     * Provides access to the information to display on the remote control.
     * May be null (when a media button event receiver is registered,
     *     but no remote control client has been registered) */
    public IRemoteControlClient mRcClient;
    public RcClientDeathHandler mRcClientDeathHandler;
    /**
     * Information only used for non-local playback
     */
    public int mPlaybackType;
    public int mPlaybackVolume;
    public int mPlaybackVolumeMax;
    public int mPlaybackVolumeHandling;
    public int mPlaybackStream;
    public RccPlaybackState mPlaybackState;
    public IRemoteVolumeObserver mRemoteVolumeObs;


    protected static class RccPlaybackState {
        public int mState;
        public long mPositionMs;
        public float mSpeed;

        public RccPlaybackState(int state, long positionMs, float speed) {
            mState = state;
            mPositionMs = positionMs;
            mSpeed = speed;
        }

        public void reset() {
            mState = RemoteControlClient.PLAYSTATE_STOPPED;
            mPositionMs = RemoteControlClient.PLAYBACK_POSITION_INVALID;
            mSpeed = RemoteControlClient.PLAYBACK_SPEED_1X;
        }

        @Override
        public String toString() {
            return stateToString() + ", " + posToString() + ", " + mSpeed + "X";
        }

        private String posToString() {
            if (mPositionMs == RemoteControlClient.PLAYBACK_POSITION_INVALID) {
                return "PLAYBACK_POSITION_INVALID";
            } else if (mPositionMs == RemoteControlClient.PLAYBACK_POSITION_ALWAYS_UNKNOWN) {
                return "PLAYBACK_POSITION_ALWAYS_UNKNOWN";
            } else {
                return (String.valueOf(mPositionMs) + "ms");
            }
        }

        private String stateToString() {
            switch (mState) {
                case RemoteControlClient.PLAYSTATE_NONE:
                    return "PLAYSTATE_NONE";
                case RemoteControlClient.PLAYSTATE_STOPPED:
                    return "PLAYSTATE_STOPPED";
                case RemoteControlClient.PLAYSTATE_PAUSED:
                    return "PLAYSTATE_PAUSED";
                case RemoteControlClient.PLAYSTATE_PLAYING:
                    return "PLAYSTATE_PLAYING";
                case RemoteControlClient.PLAYSTATE_FAST_FORWARDING:
                    return "PLAYSTATE_FAST_FORWARDING";
                case RemoteControlClient.PLAYSTATE_REWINDING:
                    return "PLAYSTATE_REWINDING";
                case RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS:
                    return "PLAYSTATE_SKIPPING_FORWARDS";
                case RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS:
                    return "PLAYSTATE_SKIPPING_BACKWARDS";
                case RemoteControlClient.PLAYSTATE_BUFFERING:
                    return "PLAYSTATE_BUFFERING";
                case RemoteControlClient.PLAYSTATE_ERROR:
                    return "PLAYSTATE_ERROR";
                default:
                    return "[invalid playstate]";
            }
        }
    }


    void dump(PrintWriter pw) {
        // FIXME to implement, remove dump from MediaFocusControl that accesses private members
    }

    public void resetPlaybackInfo() {
        mPlaybackType = RemoteControlClient.PLAYBACK_TYPE_LOCAL;
        mPlaybackVolume = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME;
        mPlaybackVolumeMax = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME;
        mPlaybackVolumeHandling = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME_HANDLING;
        mPlaybackStream = AudioManager.STREAM_MUSIC;
        mPlaybackState.reset();
        mRemoteVolumeObs = null;
    }

    /** precondition: mediaIntent != null */
    public MediaController(MediaFocusControl controller, PendingIntent mediaIntent,
            ComponentName eventReceiver, IBinder token) {
        mController = controller;
        mMediaIntent = mediaIntent;
        mReceiverComponent = eventReceiver;
        mToken = token;
        mCallingUid = -1;
        mRcClient = null;
        mRccId = ++sLastRccId;
        mPlaybackState = new RccPlaybackState(
                RemoteControlClient.PLAYSTATE_STOPPED,
                RemoteControlClient.PLAYBACK_POSITION_INVALID,
                RemoteControlClient.PLAYBACK_SPEED_1X);

        resetPlaybackInfo();
        if (mToken != null) {
            try {
                mToken.linkToDeath(this, 0);
            } catch (RemoteException e) {
                //FIXME do not access the event handler directly
                mController.mEventHandler.post(new Runnable() {
                    @Override public void run() {
                        mController.unregisterMediaButtonIntent(mMediaIntent);
                    }
                });
            }
        }
    }

    public void unlinkToRcClientDeath() {
        if ((mRcClientDeathHandler != null) && (mRcClientDeathHandler.mCb != null)) {
            try {
                mRcClientDeathHandler.mCb.unlinkToDeath(mRcClientDeathHandler, 0);
                mRcClientDeathHandler = null;
            } catch (java.util.NoSuchElementException e) {
                // not much we can do here
                Log.e(TAG, "Error in unlinkToRcClientDeath()", e);
            }
        }
    }

    // FIXME rename to "release"? (as in FocusRequester class)
    public void destroy() {
        unlinkToRcClientDeath();
        if (mToken != null) {
            mToken.unlinkToDeath(this, 0);
            mToken = null;
        }
    }

    @Override
    public void binderDied() {
        mController.unregisterMediaButtonIntent(mMediaIntent);
    }

    @Override
    protected void finalize() throws Throwable {
        destroy(); // unlink exception handled inside method
        super.finalize();
    }
}
