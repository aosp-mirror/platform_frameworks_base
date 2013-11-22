/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.media.MediaFocusControl.AudioFocusDeathHandler;
import android.os.IBinder;
import android.util.Log;

import java.io.PrintWriter;

/**
 * @hide
 * Class to handle all the information about a user of audio focus. The lifecycle of each
 * instance is managed by android.media.MediaFocusControl, from its addition to the audio focus
 * stack to its release.
 */
class FocusRequester {

    // on purpose not using this classe's name, as it will only be used from MediaFocusControl
    private static final String TAG = "MediaFocusControl";
    private static final boolean DEBUG = false;

    private AudioFocusDeathHandler mDeathHandler;
    private final IAudioFocusDispatcher mFocusDispatcher; // may be null
    private final IBinder mSourceRef;
    private final String mClientId;
    private final String mPackageName;
    private final int mCallingUid;
    /**
     * the audio focus gain request that caused the addition of this object in the focus stack.
     */
    private final int mFocusGainRequest;
    /**
     * the audio focus loss received my mFocusDispatcher, is AudioManager.AUDIOFOCUS_NONE if
     *  it never lost focus.
     */
    private int mFocusLossReceived;
    /**
     * the stream type associated with the focus request
     */
    private final int mStreamType;

    FocusRequester(int streamType, int focusRequest,
            IAudioFocusDispatcher afl, IBinder source, String id, AudioFocusDeathHandler hdlr,
            String pn, int uid) {
        mStreamType = streamType;
        mFocusDispatcher = afl;
        mSourceRef = source;
        mClientId = id;
        mDeathHandler = hdlr;
        mPackageName = pn;
        mCallingUid = uid;
        mFocusGainRequest = focusRequest;
        mFocusLossReceived = AudioManager.AUDIOFOCUS_NONE;
    }


    boolean hasSameClient(String otherClient) {
        try {
            return mClientId.compareTo(otherClient) == 0;
        } catch (NullPointerException e) {
            return false;
        }
    }

    boolean hasSameBinder(IBinder ib) {
        return (mSourceRef != null) && mSourceRef.equals(ib);
    }

    boolean hasSamePackage(String pack) {
        try {
            return mPackageName.compareTo(pack) == 0;
        } catch (NullPointerException e) {
            return false;
        }
    }

    boolean hasSameUid(int uid) {
        return mCallingUid == uid;
    }


    int getGainRequest() {
        return mFocusGainRequest;
    }

    int getStreamType() {
        return mStreamType;
    }


    private static String focusChangeToString(int focus) {
        switch(focus) {
            case AudioManager.AUDIOFOCUS_NONE:
                return "none";
            case AudioManager.AUDIOFOCUS_GAIN:
                return "GAIN";
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
                return "GAIN_TRANSIENT";
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                return "GAIN_TRANSIENT_MAY_DUCK";
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                return "GAIN_TRANSIENT_EXCLUSIVE";
            case AudioManager.AUDIOFOCUS_LOSS:
                return "LOSS";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                return "LOSS_TRANSIENT";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                return "LOSS_TRANSIENT_CAN_DUCK";
            default:
                return "[invalid focus change" + focus + "]";
        }
    }

    private String focusGainToString() {
        return focusChangeToString(mFocusGainRequest);
    }

    private String focusLossToString() {
        return focusChangeToString(mFocusLossReceived);
    }

    void dump(PrintWriter pw) {
        pw.println("  source:" + mSourceRef
                + " -- pack: " + mPackageName
                + " -- client: " + mClientId
                + " -- gain: " + focusGainToString()
                + " -- loss: " + focusLossToString()
                + " -- uid: " + mCallingUid
                + " -- stream: " + mStreamType);
    }


    void release() {
        try {
            if (mSourceRef != null && mDeathHandler != null) {
                mSourceRef.unlinkToDeath(mDeathHandler, 0);
                mDeathHandler = null;
            }
        } catch (java.util.NoSuchElementException e) {
            Log.e(TAG, "FocusRequester.release() hit ", e);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    /**
     * For a given audio focus gain request, return the audio focus loss type that will result
     * from it, taking into account any previous focus loss.
     * @param gainRequest
     * @return the audio focus loss type that matches the gain request
     */
    private int focusLossForGainRequest(int gainRequest) {
        switch(gainRequest) {
            case AudioManager.AUDIOFOCUS_GAIN:
                switch(mFocusLossReceived) {
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    case AudioManager.AUDIOFOCUS_LOSS:
                    case AudioManager.AUDIOFOCUS_NONE:
                        return AudioManager.AUDIOFOCUS_LOSS;
                }
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
                switch(mFocusLossReceived) {
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    case AudioManager.AUDIOFOCUS_NONE:
                        return AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                    case AudioManager.AUDIOFOCUS_LOSS:
                        return AudioManager.AUDIOFOCUS_LOSS;
                }
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                switch(mFocusLossReceived) {
                    case AudioManager.AUDIOFOCUS_NONE:
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        return AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        return AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                    case AudioManager.AUDIOFOCUS_LOSS:
                        return AudioManager.AUDIOFOCUS_LOSS;
                }
            default:
                Log.e(TAG, "focusLossForGainRequest() for invalid focus request "+ gainRequest);
                        return AudioManager.AUDIOFOCUS_NONE;
        }
    }

    void handleExternalFocusGain(int focusGain) {
        int focusLoss = focusLossForGainRequest(focusGain);
        handleFocusLoss(focusLoss);
    }

    void handleFocusGain(int focusGain) {
        try {
            if (mFocusDispatcher != null) {
                if (DEBUG) {
                    Log.v(TAG, "dispatching " + focusChangeToString(focusGain) + " to "
                        + mClientId);
                }
                mFocusDispatcher.dispatchAudioFocusChange(focusGain, mClientId);
            }
            mFocusLossReceived = AudioManager.AUDIOFOCUS_NONE;
        } catch (android.os.RemoteException e) {
            Log.e(TAG, "Failure to signal gain of audio focus due to: ", e);
        }
    }

    void handleFocusLoss(int focusLoss) {
        try {
            if (focusLoss != mFocusLossReceived) {
                if (mFocusDispatcher != null) {
                    if (DEBUG) {
                        Log.v(TAG, "dispatching " + focusChangeToString(focusLoss) + " to "
                            + mClientId);
                    }
                    mFocusDispatcher.dispatchAudioFocusChange(focusLoss, mClientId);
                }
                mFocusLossReceived = focusLoss;
            }
        } catch (android.os.RemoteException e) {
            Log.e(TAG, "Failure to signal loss of audio focus due to:", e);
        }
    }

}
