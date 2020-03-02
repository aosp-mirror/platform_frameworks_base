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

package com.android.server.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.IAudioFocusDispatcher;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.audio.MediaFocusControl.AudioFocusDeathHandler;

import java.io.PrintWriter;

/**
 * @hide
 * Class to handle all the information about a user of audio focus. The lifecycle of each
 * instance is managed by android.media.MediaFocusControl, from its addition to the audio focus
 * stack, or the map of focus owners for an external focus policy, to its release.
 */
public class FocusRequester {

    // on purpose not using this classe's name, as it will only be used from MediaFocusControl
    private static final String TAG = "MediaFocusControl";
    private static final boolean DEBUG = false;

    private AudioFocusDeathHandler mDeathHandler; // may be null
    private IAudioFocusDispatcher mFocusDispatcher; // may be null
    private final IBinder mSourceRef; // may be null
    private final @NonNull String mClientId;
    private final @NonNull String mPackageName;
    private final int mCallingUid;
    private final MediaFocusControl mFocusController; // never null
    private final int mSdkTarget;

    /**
     * the audio focus gain request that caused the addition of this object in the focus stack.
     */
    private final int mFocusGainRequest;
    /**
     * the flags associated with the gain request that qualify the type of grant (e.g. accepting
     * delay vs grant must be immediate)
     */
    private final int mGrantFlags;
    /**
     * the audio focus loss received my mFocusDispatcher, is AudioManager.AUDIOFOCUS_NONE if
     *  it never lost focus.
     */
    private int mFocusLossReceived;
    /**
     * whether this focus owner listener was notified when it lost focus
     */
    private boolean mFocusLossWasNotified;
    /**
     * the audio attributes associated with the focus request
     */
    private final @NonNull AudioAttributes mAttributes;

    /**
     * Class constructor
     * @param aa
     * @param focusRequest
     * @param grantFlags
     * @param afl
     * @param source
     * @param id
     * @param hdlr
     * @param pn
     * @param uid
     * @param ctlr cannot be null
     */
    FocusRequester(@NonNull AudioAttributes aa, int focusRequest, int grantFlags,
            IAudioFocusDispatcher afl, IBinder source, @NonNull String id,
            AudioFocusDeathHandler hdlr, @NonNull String pn, int uid,
            @NonNull MediaFocusControl ctlr, int sdk) {
        mAttributes = aa;
        mFocusDispatcher = afl;
        mSourceRef = source;
        mClientId = id;
        mDeathHandler = hdlr;
        mPackageName = pn;
        mCallingUid = uid;
        mFocusGainRequest = focusRequest;
        mGrantFlags = grantFlags;
        mFocusLossReceived = AudioManager.AUDIOFOCUS_NONE;
        mFocusLossWasNotified = true;
        mFocusController = ctlr;
        mSdkTarget = sdk;
    }

    FocusRequester(AudioFocusInfo afi, IAudioFocusDispatcher afl,
             IBinder source, AudioFocusDeathHandler hdlr, @NonNull MediaFocusControl ctlr) {
        mAttributes = afi.getAttributes();
        mClientId = afi.getClientId();
        mPackageName = afi.getPackageName();
        mCallingUid = afi.getClientUid();
        mFocusGainRequest = afi.getGainRequest();
        mFocusLossReceived = AudioManager.AUDIOFOCUS_NONE;
        mFocusLossWasNotified = true;
        mGrantFlags = afi.getFlags();
        mSdkTarget = afi.getSdkTarget();

        mFocusDispatcher = afl;
        mSourceRef = source;
        mDeathHandler = hdlr;
        mFocusController = ctlr;
    }

    boolean hasSameClient(String otherClient) {
        return mClientId.compareTo(otherClient) == 0;
    }

    boolean isLockedFocusOwner() {
        return ((mGrantFlags & AudioManager.AUDIOFOCUS_FLAG_LOCK) != 0);
    }

    boolean hasSameBinder(IBinder ib) {
        return (mSourceRef != null) && mSourceRef.equals(ib);
    }

    boolean hasSameDispatcher(IAudioFocusDispatcher fd) {
        return (mFocusDispatcher != null) && mFocusDispatcher.equals(fd);
    }

    boolean hasSamePackage(@NonNull String pack) {
        return mPackageName.compareTo(pack) == 0;
    }

    boolean hasSameUid(int uid) {
        return mCallingUid == uid;
    }

    int getClientUid() {
        return mCallingUid;
    }

    String getClientId() {
        return mClientId;
    }

    int getGainRequest() {
        return mFocusGainRequest;
    }

    int getGrantFlags() {
        return mGrantFlags;
    }

    AudioAttributes getAudioAttributes() {
        return mAttributes;
    }

    int getSdkTarget() {
        return mSdkTarget;
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

    private static String flagsToString(int flags) {
        String msg = new String();
        if ((flags & AudioManager.AUDIOFOCUS_FLAG_DELAY_OK) != 0) {
            msg += "DELAY_OK";
        }
        if ((flags & AudioManager.AUDIOFOCUS_FLAG_LOCK) != 0)     {
            if (!msg.isEmpty()) { msg += "|"; }
            msg += "LOCK";
        }
        if ((flags & AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS) != 0) {
            if (!msg.isEmpty()) { msg += "|"; }
            msg += "PAUSES_ON_DUCKABLE_LOSS";
        }
        return msg;
    }

    void dump(PrintWriter pw) {
        pw.println("  source:" + mSourceRef
                + " -- pack: " + mPackageName
                + " -- client: " + mClientId
                + " -- gain: " + focusGainToString()
                + " -- flags: " + flagsToString(mGrantFlags)
                + " -- loss: " + focusLossToString()
                + " -- notified: " + mFocusLossWasNotified
                + " -- uid: " + mCallingUid
                + " -- attr: " + mAttributes
                + " -- sdk:" + mSdkTarget);
    }


    void release() {
        final IBinder srcRef = mSourceRef;
        final AudioFocusDeathHandler deathHdlr = mDeathHandler;
        try {
            if (srcRef != null && deathHdlr != null) {
                srcRef.unlinkToDeath(deathHdlr, 0);
            }
        } catch (java.util.NoSuchElementException e) { }
        mDeathHandler = null;
        mFocusDispatcher = null;
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

    /**
     * Handle the loss of focus resulting from a given focus gain.
     * @param focusGain the focus gain from which the loss of focus is resulting
     * @param frWinner the new focus owner
     * @return true if the focus loss is definitive, false otherwise.
     */
    @GuardedBy("MediaFocusControl.mAudioFocusLock")
    boolean handleFocusLossFromGain(int focusGain, final FocusRequester frWinner, boolean forceDuck)
    {
        final int focusLoss = focusLossForGainRequest(focusGain);
        handleFocusLoss(focusLoss, frWinner, forceDuck);
        return (focusLoss == AudioManager.AUDIOFOCUS_LOSS);
    }

    @GuardedBy("MediaFocusControl.mAudioFocusLock")
    void handleFocusGain(int focusGain) {
        try {
            mFocusLossReceived = AudioManager.AUDIOFOCUS_NONE;
            mFocusController.notifyExtPolicyFocusGrant_syncAf(toAudioFocusInfo(),
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            final IAudioFocusDispatcher fd = mFocusDispatcher;
            if (fd != null) {
                if (DEBUG) {
                    Log.v(TAG, "dispatching " + focusChangeToString(focusGain) + " to "
                        + mClientId);
                }
                if (mFocusLossWasNotified) {
                    fd.dispatchAudioFocusChange(focusGain, mClientId);
                }
            }
            mFocusController.unduckPlayers(this);
        } catch (android.os.RemoteException e) {
            Log.e(TAG, "Failure to signal gain of audio focus due to: ", e);
        }
    }

    @GuardedBy("MediaFocusControl.mAudioFocusLock")
    void handleFocusGainFromRequest(int focusRequestResult) {
        if (focusRequestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mFocusController.unduckPlayers(this);
        }
    }

    @GuardedBy("MediaFocusControl.mAudioFocusLock")
    void handleFocusLoss(int focusLoss, @Nullable final FocusRequester frWinner, boolean forceDuck)
    {
        try {
            if (focusLoss != mFocusLossReceived) {
                mFocusLossReceived = focusLoss;
                mFocusLossWasNotified = false;
                // before dispatching a focus loss, check if the following conditions are met:
                // 1/ the framework is not supposed to notify the focus loser on a DUCK loss
                //    (i.e. it has a focus controller that implements a ducking policy)
                // 2/ it is a DUCK loss
                // 3/ the focus loser isn't flagged as pausing in a DUCK loss
                // if they are, do not notify the focus loser
                if (!mFocusController.mustNotifyFocusOwnerOnDuck()
                        && mFocusLossReceived == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                        && (mGrantFlags
                                & AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS) == 0) {
                    if (DEBUG) {
                        Log.v(TAG, "NOT dispatching " + focusChangeToString(mFocusLossReceived)
                                + " to " + mClientId + ", to be handled externally");
                    }
                    mFocusController.notifyExtPolicyFocusLoss_syncAf(
                            toAudioFocusInfo(), false /* wasDispatched */);
                    return;
                }

                // check enforcement by the framework
                boolean handled = false;
                if (focusLoss == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                        && MediaFocusControl.ENFORCE_DUCKING
                        && frWinner != null) {
                    // candidate for enforcement by the framework
                    if (frWinner.mCallingUid != this.mCallingUid) {
                        if (!forceDuck && ((mGrantFlags
                                & AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS) != 0)) {
                            // the focus loser declared it would pause instead of duck, let it
                            // handle it (the framework doesn't pause for apps)
                            handled = false;
                            Log.v(TAG, "not ducking uid " + this.mCallingUid + " - flags");
                        } else if (!forceDuck && (MediaFocusControl.ENFORCE_DUCKING_FOR_NEW &&
                                this.getSdkTarget() <= MediaFocusControl.DUCKING_IN_APP_SDK_LEVEL))
                        {
                            // legacy behavior, apps used to be notified when they should be ducking
                            handled = false;
                            Log.v(TAG, "not ducking uid " + this.mCallingUid + " - old SDK");
                        } else {
                            handled = mFocusController.duckPlayers(frWinner, this, forceDuck);
                        }
                    } // else: the focus change is within the same app, so let the dispatching
                      //       happen as if the framework was not involved.
                }

                if (handled) {
                    if (DEBUG) {
                        Log.v(TAG, "NOT dispatching " + focusChangeToString(mFocusLossReceived)
                            + " to " + mClientId + ", ducking implemented by framework");
                    }
                    mFocusController.notifyExtPolicyFocusLoss_syncAf(
                            toAudioFocusInfo(), false /* wasDispatched */);
                    return; // with mFocusLossWasNotified = false
                }

                final IAudioFocusDispatcher fd = mFocusDispatcher;
                if (fd != null) {
                    if (DEBUG) {
                        Log.v(TAG, "dispatching " + focusChangeToString(mFocusLossReceived) + " to "
                            + mClientId);
                    }
                    mFocusController.notifyExtPolicyFocusLoss_syncAf(
                            toAudioFocusInfo(), true /* wasDispatched */);
                    mFocusLossWasNotified = true;
                    fd.dispatchAudioFocusChange(mFocusLossReceived, mClientId);
                }
            }
        } catch (android.os.RemoteException e) {
            Log.e(TAG, "Failure to signal loss of audio focus due to:", e);
        }
    }

    int dispatchFocusChange(int focusChange) {
        final IAudioFocusDispatcher fd = mFocusDispatcher;
        if (fd == null) {
            if (MediaFocusControl.DEBUG) { Log.e(TAG, "dispatchFocusChange: no focus dispatcher"); }
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_NONE) {
            if (MediaFocusControl.DEBUG) { Log.v(TAG, "dispatchFocusChange: AUDIOFOCUS_NONE"); }
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        } else if ((focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                || focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                || focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_GAIN)
                && (mFocusGainRequest != focusChange)){
            Log.w(TAG, "focus gain was requested with " + mFocusGainRequest
                    + ", dispatching " + focusChange);
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            mFocusLossReceived = focusChange;
        }
        try {
            fd.dispatchAudioFocusChange(focusChange, mClientId);
        } catch (android.os.RemoteException e) {
            Log.e(TAG, "dispatchFocusChange: error talking to focus listener " + mClientId, e);
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    void dispatchFocusResultFromExtPolicy(int requestResult) {
        final IAudioFocusDispatcher fd = mFocusDispatcher;
        if (fd == null) {
            if (MediaFocusControl.DEBUG) {
                Log.e(TAG, "dispatchFocusResultFromExtPolicy: no focus dispatcher");
            }
            return;
        }
        if (DEBUG) {
            Log.v(TAG, "dispatching result" + requestResult + " to " + mClientId);
        }
        try {
            fd.dispatchFocusResultFromExtPolicy(requestResult, mClientId);
        } catch (android.os.RemoteException e) {
            Log.e(TAG, "dispatchFocusResultFromExtPolicy: error talking to focus listener"
                    + mClientId, e);
        }
    }

    AudioFocusInfo toAudioFocusInfo() {
        return new AudioFocusInfo(mAttributes, mCallingUid, mClientId, mPackageName,
                mFocusGainRequest, mFocusLossReceived, mGrantFlags, mSdkTarget);
    }
}
