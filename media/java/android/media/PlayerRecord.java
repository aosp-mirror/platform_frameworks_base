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
import android.os.Binder;
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
class PlayerRecord implements DeathRecipient {

    // on purpose not using this classe's name, as it will only be used from MediaFocusControl
    private static final String TAG = "MediaFocusControl";
    private static final boolean DEBUG = false;

    /**
     * A global counter for RemoteControlClient identifiers
     */
    private static int sLastRccId = 0;

    public static MediaFocusControl sController;

    /**
     * The target for the ACTION_MEDIA_BUTTON events.
     * Always non null. //FIXME verify
     */
    final private PendingIntent mMediaIntent;
    /**
     * The registered media button event receiver.
     */
    final private ComponentName mReceiverComponent;

    private int mRccId = -1;

    /**
     * A non-null token implies this record tracks a "live" player whose death is being monitored.
     */
    private IBinder mToken;
    private String mCallingPackageName;
    private int mCallingUid;
    /**
     * Provides access to the information to display on the remote control.
     * May be null (when a media button event receiver is registered,
     *     but no remote control client has been registered) */
    private IRemoteControlClient mRcClient;
    private RcClientDeathHandler mRcClientDeathHandler;
    /**
     * Information only used for non-local playback
     */
    //FIXME private?
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


    /**
     * Inner class to monitor remote control client deaths, and remove the client for the
     * remote control stack if necessary.
     */
    private class RcClientDeathHandler implements IBinder.DeathRecipient {
        final private IBinder mCb; // To be notified of client's death
        //FIXME needed?
        final private PendingIntent mMediaIntent;

        RcClientDeathHandler(IBinder cb, PendingIntent pi) {
            mCb = cb;
            mMediaIntent = pi;
        }

        public void binderDied() {
            Log.w(TAG, "  RemoteControlClient died");
            // remote control client died, make sure the displays don't use it anymore
            //  by setting its remote control client to null
            sController.registerRemoteControlClient(mMediaIntent, null/*rcClient*/, null/*ignored*/);
            // the dead client was maybe handling remote playback, the controller should reevaluate
            sController.postReevaluateRemote();
        }

        public IBinder getBinder() {
            return mCb;
        }
    }


    protected static class RemotePlaybackState {
        int mRccId;
        int mVolume;
        int mVolumeMax;
        int mVolumeHandling;

        protected RemotePlaybackState(int id, int vol, int volMax) {
            mRccId = id;
            mVolume = vol;
            mVolumeMax = volMax;
            mVolumeHandling = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME_HANDLING;
        }
    }


    void dump(PrintWriter pw, boolean registrationInfo) {
        if (registrationInfo) {
            pw.println("  pi: " + mMediaIntent +
                    " -- pack: " + mCallingPackageName +
                    "  -- ercvr: " + mReceiverComponent +
                    "  -- client: " + mRcClient +
                    "  -- uid: " + mCallingUid +
                    "  -- type: " + mPlaybackType +
                    "  state: " + mPlaybackState);
        } else {
            // emphasis on state
            pw.println("  uid: " + mCallingUid +
                    "  -- id: " + mRccId +
                    "  -- type: " + mPlaybackType +
                    "  -- state: " + mPlaybackState +
                    "  -- vol handling: " + mPlaybackVolumeHandling +
                    "  -- vol: " + mPlaybackVolume +
                    "  -- volMax: " + mPlaybackVolumeMax +
                    "  -- volObs: " + mRemoteVolumeObs);
        }
    }


    static protected void setMediaFocusControl(MediaFocusControl mfc) {
        sController = mfc;
    }

    /** precondition: mediaIntent != null */
    protected PlayerRecord(PendingIntent mediaIntent, ComponentName eventReceiver, IBinder token)
    {
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
                sController.unregisterMediaButtonIntentAsync(mMediaIntent);
            }
        }
    }

    //---------------------------------------------
    // Accessors
    protected int getRccId() {
        return mRccId;
    }

    protected IRemoteControlClient getRcc() {
        return mRcClient;
    }

    protected ComponentName getMediaButtonReceiver() {
        return mReceiverComponent;
    }

    protected PendingIntent getMediaButtonIntent() {
        return mMediaIntent;
    }

    protected boolean hasMatchingMediaButtonIntent(PendingIntent pi) {
        if (mToken != null) {
            return mMediaIntent.equals(pi);
        } else {
            if (mReceiverComponent != null) {
                return mReceiverComponent.equals(pi.getIntent().getComponent());
            } else {
                return false;
            }
        }
    }

    protected boolean isPlaybackActive() {
        return MediaFocusControl.isPlaystateActive(mPlaybackState.mState);
    }

    //---------------------------------------------
    // Modify the records stored in the instance
    protected void resetControllerInfoForRcc(IRemoteControlClient rcClient,
            String callingPackageName, int uid) {
        // already had a remote control client?
        if (mRcClientDeathHandler != null) {
            // stop monitoring the old client's death
            unlinkToRcClientDeath();
        }
        // save the new remote control client
        mRcClient = rcClient;
        mCallingPackageName = callingPackageName;
        mCallingUid = uid;
        if (rcClient == null) {
            // here mcse.mRcClientDeathHandler is null;
            resetPlaybackInfo();
        } else {
            IBinder b = mRcClient.asBinder();
            RcClientDeathHandler rcdh =
                    new RcClientDeathHandler(b, mMediaIntent);
            try {
                b.linkToDeath(rcdh, 0);
            } catch (RemoteException e) {
                // remote control client is DOA, disqualify it
                Log.w(TAG, "registerRemoteControlClient() has a dead client " + b);
                mRcClient = null;
            }
            mRcClientDeathHandler = rcdh;
        }
    }

    protected void resetControllerInfoForNoRcc() {
        // stop monitoring the RCC death
        unlinkToRcClientDeath();
        // reset the RCC-related fields
        mRcClient = null;
        mCallingPackageName = null;
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

    //---------------------------------------------
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
        sController.unregisterMediaButtonIntentAsync(mMediaIntent);
    }

    @Override
    protected void finalize() throws Throwable {
        destroy(); // unlink exception handled inside method
        super.finalize();
    }
}
