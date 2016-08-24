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

import android.app.AppOpsManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.IAudioFocusDispatcher;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Stack;
import java.text.DateFormat;

/**
 * @hide
 *
 */
public class MediaFocusControl {

    private static final String TAG = "MediaFocusControl";

    private final Context mContext;
    private final AppOpsManager mAppOps;

    protected MediaFocusControl(Context cntxt) {
        mContext = cntxt;
        mAppOps = (AppOpsManager)mContext.getSystemService(Context.APP_OPS_SERVICE);
    }

    protected void dump(PrintWriter pw) {
        pw.println("\nMediaFocusControl dump time: "
                + DateFormat.getTimeInstance().format(new Date()));
        dumpFocusStack(pw);
    }


    //==========================================================================================
    // AudioFocus
    //==========================================================================================

    private final static Object mAudioFocusLock = new Object();

    /**
     * Discard the current audio focus owner.
     * Notify top of audio focus stack that it lost focus (regardless of possibility to reassign
     * focus), remove it from the stack, and clear the remote control display.
     */
    protected void discardAudioFocusOwner() {
        synchronized(mAudioFocusLock) {
            if (!mFocusStack.empty()) {
                // notify the current focus owner it lost focus after removing it from stack
                final FocusRequester exFocusOwner = mFocusStack.pop();
                exFocusOwner.handleFocusLoss(AudioManager.AUDIOFOCUS_LOSS);
                exFocusOwner.release();
            }
        }
    }

    /**
     * Called synchronized on mAudioFocusLock
     */
    private void notifyTopOfAudioFocusStack() {
        // notify the top of the stack it gained focus
        if (!mFocusStack.empty()) {
            if (canReassignAudioFocus()) {
                mFocusStack.peek().handleFocusGain(AudioManager.AUDIOFOCUS_GAIN);
            }
        }
    }

    /**
     * Focus is requested, propagate the associated loss throughout the stack.
     * @param focusGain the new focus gain that will later be added at the top of the stack
     */
    private void propagateFocusLossFromGain_syncAf(int focusGain) {
        // going through the audio focus stack to signal new focus, traversing order doesn't
        // matter as all entries respond to the same external focus gain
        Iterator<FocusRequester> stackIterator = mFocusStack.iterator();
        while(stackIterator.hasNext()) {
            stackIterator.next().handleExternalFocusGain(focusGain);
        }
    }

    private final Stack<FocusRequester> mFocusStack = new Stack<FocusRequester>();

    /**
     * Helper function:
     * Display in the log the current entries in the audio focus stack
     */
    private void dumpFocusStack(PrintWriter pw) {
        pw.println("\nAudio Focus stack entries (last is top of stack):");
        synchronized(mAudioFocusLock) {
            Iterator<FocusRequester> stackIterator = mFocusStack.iterator();
            while(stackIterator.hasNext()) {
                stackIterator.next().dump(pw);
            }
        }
        pw.println("\n Notify on duck: " + mNotifyFocusOwnerOnDuck +"\n");
    }

    /**
     * Helper function:
     * Called synchronized on mAudioFocusLock
     * Remove a focus listener from the focus stack.
     * @param clientToRemove the focus listener
     * @param signal if true and the listener was at the top of the focus stack, i.e. it was holding
     *   focus, notify the next item in the stack it gained focus.
     */
    private void removeFocusStackEntry(String clientToRemove, boolean signal,
            boolean notifyFocusFollowers) {
        // is the current top of the focus stack abandoning focus? (because of request, not death)
        if (!mFocusStack.empty() && mFocusStack.peek().hasSameClient(clientToRemove))
        {
            //Log.i(TAG, "   removeFocusStackEntry() removing top of stack");
            FocusRequester fr = mFocusStack.pop();
            fr.release();
            if (notifyFocusFollowers) {
                final AudioFocusInfo afi = fr.toAudioFocusInfo();
                afi.clearLossReceived();
                notifyExtPolicyFocusLoss_syncAf(afi, false);
            }
            if (signal) {
                // notify the new top of the stack it gained focus
                notifyTopOfAudioFocusStack();
            }
        } else {
            // focus is abandoned by a client that's not at the top of the stack,
            // no need to update focus.
            // (using an iterator on the stack so we can safely remove an entry after having
            //  evaluated it, traversal order doesn't matter here)
            Iterator<FocusRequester> stackIterator = mFocusStack.iterator();
            while(stackIterator.hasNext()) {
                FocusRequester fr = stackIterator.next();
                if(fr.hasSameClient(clientToRemove)) {
                    Log.i(TAG, "AudioFocus  removeFocusStackEntry(): removing entry for "
                            + clientToRemove);
                    stackIterator.remove();
                    fr.release();
                }
            }
        }
    }

    /**
     * Helper function:
     * Called synchronized on mAudioFocusLock
     * Remove focus listeners from the focus stack for a particular client when it has died.
     */
    private void removeFocusStackEntryForClient(IBinder cb) {
        // is the owner of the audio focus part of the client to remove?
        boolean isTopOfStackForClientToRemove = !mFocusStack.isEmpty() &&
                mFocusStack.peek().hasSameBinder(cb);
        // (using an iterator on the stack so we can safely remove an entry after having
        //  evaluated it, traversal order doesn't matter here)
        Iterator<FocusRequester> stackIterator = mFocusStack.iterator();
        while(stackIterator.hasNext()) {
            FocusRequester fr = stackIterator.next();
            if(fr.hasSameBinder(cb)) {
                Log.i(TAG, "AudioFocus  removeFocusStackEntry(): removing entry for " + cb);
                stackIterator.remove();
                // the client just died, no need to unlink to its death
            }
        }
        if (isTopOfStackForClientToRemove) {
            // we removed an entry at the top of the stack:
            //  notify the new top of the stack it gained focus.
            notifyTopOfAudioFocusStack();
        }
    }

    /**
     * Helper function:
     * Returns true if the system is in a state where the focus can be reevaluated, false otherwise.
     * The implementation guarantees that a state where focus cannot be immediately reassigned
     * implies that an "locked" focus owner is at the top of the focus stack.
     * Modifications to the implementation that break this assumption will cause focus requests to
     * misbehave when honoring the AudioManager.AUDIOFOCUS_FLAG_DELAY_OK flag.
     */
    private boolean canReassignAudioFocus() {
        // focus requests are rejected during a phone call or when the phone is ringing
        // this is equivalent to IN_VOICE_COMM_FOCUS_ID having the focus
        if (!mFocusStack.isEmpty() && isLockedFocusOwner(mFocusStack.peek())) {
            return false;
        }
        return true;
    }

    private boolean isLockedFocusOwner(FocusRequester fr) {
        return (fr.hasSameClient(AudioSystem.IN_VOICE_COMM_FOCUS_ID) || fr.isLockedFocusOwner());
    }

    /**
     * Helper function
     * Pre-conditions: focus stack is not empty, there is one or more locked focus owner
     *                 at the top of the focus stack
     * Push the focus requester onto the audio focus stack at the first position immediately
     * following the locked focus owners.
     * @return {@link AudioManager#AUDIOFOCUS_REQUEST_GRANTED} or
     *     {@link AudioManager#AUDIOFOCUS_REQUEST_DELAYED}
     */
    private int pushBelowLockedFocusOwners(FocusRequester nfr) {
        int lastLockedFocusOwnerIndex = mFocusStack.size();
        for (int index = mFocusStack.size()-1; index >= 0; index--) {
            if (isLockedFocusOwner(mFocusStack.elementAt(index))) {
                lastLockedFocusOwnerIndex = index;
            }
        }
        if (lastLockedFocusOwnerIndex == mFocusStack.size()) {
            // this should not happen, but handle it and log an error
            Log.e(TAG, "No exclusive focus owner found in propagateFocusLossFromGain_syncAf()",
                    new Exception());
            // no exclusive owner, push at top of stack, focus is granted, propagate change
            propagateFocusLossFromGain_syncAf(nfr.getGainRequest());
            mFocusStack.push(nfr);
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            mFocusStack.insertElementAt(nfr, lastLockedFocusOwnerIndex);
            return AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
        }
    }

    /**
     * Inner class to monitor audio focus client deaths, and remove them from the audio focus
     * stack if necessary.
     */
    protected class AudioFocusDeathHandler implements IBinder.DeathRecipient {
        private IBinder mCb; // To be notified of client's death

        AudioFocusDeathHandler(IBinder cb) {
            mCb = cb;
        }

        public void binderDied() {
            synchronized(mAudioFocusLock) {
                Log.w(TAG, "  AudioFocus   audio focus client died");
                removeFocusStackEntryForClient(mCb);
            }
        }

        public IBinder getBinder() {
            return mCb;
        }
    }

    /**
     * Indicates whether to notify an audio focus owner when it loses focus
     * with {@link AudioManager#AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK} if it will only duck.
     * This variable being false indicates an AudioPolicy has been registered and has signaled
     * it will handle audio ducking.
     */
    private boolean mNotifyFocusOwnerOnDuck = true;

    protected void setDuckingInExtPolicyAvailable(boolean available) {
        mNotifyFocusOwnerOnDuck = !available;
    }

    boolean mustNotifyFocusOwnerOnDuck() { return mNotifyFocusOwnerOnDuck; }

    private ArrayList<IAudioPolicyCallback> mFocusFollowers = new ArrayList<IAudioPolicyCallback>();

    void addFocusFollower(IAudioPolicyCallback ff) {
        if (ff == null) {
            return;
        }
        synchronized(mAudioFocusLock) {
            boolean found = false;
            for (IAudioPolicyCallback pcb : mFocusFollowers) {
                if (pcb.asBinder().equals(ff.asBinder())) {
                    found = true;
                    break;
                }
            }
            if (found) {
                return;
            } else {
                mFocusFollowers.add(ff);
                notifyExtPolicyCurrentFocusAsync(ff);
            }
        }
    }

    void removeFocusFollower(IAudioPolicyCallback ff) {
        if (ff == null) {
            return;
        }
        synchronized(mAudioFocusLock) {
            for (IAudioPolicyCallback pcb : mFocusFollowers) {
                if (pcb.asBinder().equals(ff.asBinder())) {
                    mFocusFollowers.remove(pcb);
                    break;
                }
            }
        }
    }

    /**
     * @param pcb non null
     */
    void notifyExtPolicyCurrentFocusAsync(IAudioPolicyCallback pcb) {
        final IAudioPolicyCallback pcb2 = pcb;
        final Thread thread = new Thread() {
            @Override
            public void run() {
                synchronized(mAudioFocusLock) {
                    if (mFocusStack.isEmpty()) {
                        return;
                    }
                    try {
                        pcb2.notifyAudioFocusGrant(mFocusStack.peek().toAudioFocusInfo(),
                                // top of focus stack always has focus
                                AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Can't call notifyAudioFocusGrant() on IAudioPolicyCallback "
                                + pcb2.asBinder(), e);
                    }
                }
            }
        };
        thread.start();
    }

    /**
     * Called synchronized on mAudioFocusLock
     */
    void notifyExtPolicyFocusGrant_syncAf(AudioFocusInfo afi, int requestResult) {
        for (IAudioPolicyCallback pcb : mFocusFollowers) {
            try {
                // oneway
                pcb.notifyAudioFocusGrant(afi, requestResult);
            } catch (RemoteException e) {
                Log.e(TAG, "Can't call notifyAudioFocusGrant() on IAudioPolicyCallback "
                        + pcb.asBinder(), e);
            }
        }
    }

    /**
     * Called synchronized on mAudioFocusLock
     */
    void notifyExtPolicyFocusLoss_syncAf(AudioFocusInfo afi, boolean wasDispatched) {
        for (IAudioPolicyCallback pcb : mFocusFollowers) {
            try {
                // oneway
                pcb.notifyAudioFocusLoss(afi, wasDispatched);
            } catch (RemoteException e) {
                Log.e(TAG, "Can't call notifyAudioFocusLoss() on IAudioPolicyCallback "
                        + pcb.asBinder(), e);
            }
        }
    }

    protected int getCurrentAudioFocus() {
        synchronized(mAudioFocusLock) {
            if (mFocusStack.empty()) {
                return AudioManager.AUDIOFOCUS_NONE;
            } else {
                return mFocusStack.peek().getGainRequest();
            }
        }
    }

    /** @see AudioManager#requestAudioFocus(AudioManager.OnAudioFocusChangeListener, int, int, int) */
    protected int requestAudioFocus(AudioAttributes aa, int focusChangeHint, IBinder cb,
            IAudioFocusDispatcher fd, String clientId, String callingPackageName, int flags) {
        Log.i(TAG, " AudioFocus  requestAudioFocus() from uid/pid " + Binder.getCallingUid()
                + "/" + Binder.getCallingPid()
                + " clientId=" + clientId
                + " req=" + focusChangeHint
                + " flags=0x" + Integer.toHexString(flags));
        // we need a valid binder callback for clients
        if (!cb.pingBinder()) {
            Log.e(TAG, " AudioFocus DOA client for requestAudioFocus(), aborting.");
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }

        if (mAppOps.noteOp(AppOpsManager.OP_TAKE_AUDIO_FOCUS, Binder.getCallingUid(),
                callingPackageName) != AppOpsManager.MODE_ALLOWED) {
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }

        synchronized(mAudioFocusLock) {
            boolean focusGrantDelayed = false;
            if (!canReassignAudioFocus()) {
                if ((flags & AudioManager.AUDIOFOCUS_FLAG_DELAY_OK) == 0) {
                    return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                } else {
                    // request has AUDIOFOCUS_FLAG_DELAY_OK: focus can't be
                    // granted right now, so the requester will be inserted in the focus stack
                    // to receive focus later
                    focusGrantDelayed = true;
                }
            }

            // handle the potential premature death of the new holder of the focus
            // (premature death == death before abandoning focus)
            // Register for client death notification
            AudioFocusDeathHandler afdh = new AudioFocusDeathHandler(cb);
            try {
                cb.linkToDeath(afdh, 0);
            } catch (RemoteException e) {
                // client has already died!
                Log.w(TAG, "AudioFocus  requestAudioFocus() could not link to "+cb+" binder death");
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            }

            if (!mFocusStack.empty() && mFocusStack.peek().hasSameClient(clientId)) {
                // if focus is already owned by this client and the reason for acquiring the focus
                // hasn't changed, don't do anything
                final FocusRequester fr = mFocusStack.peek();
                if (fr.getGainRequest() == focusChangeHint && fr.getGrantFlags() == flags) {
                    // unlink death handler so it can be gc'ed.
                    // linkToDeath() creates a JNI global reference preventing collection.
                    cb.unlinkToDeath(afdh, 0);
                    notifyExtPolicyFocusGrant_syncAf(fr.toAudioFocusInfo(),
                            AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
                // the reason for the audio focus request has changed: remove the current top of
                // stack and respond as if we had a new focus owner
                if (!focusGrantDelayed) {
                    mFocusStack.pop();
                    // the entry that was "popped" is the same that was "peeked" above
                    fr.release();
                }
            }

            // focus requester might already be somewhere below in the stack, remove it
            removeFocusStackEntry(clientId, false /* signal */, false /*notifyFocusFollowers*/);

            final FocusRequester nfr = new FocusRequester(aa, focusChangeHint, flags, fd, cb,
                    clientId, afdh, callingPackageName, Binder.getCallingUid(), this);
            if (focusGrantDelayed) {
                // focusGrantDelayed being true implies we can't reassign focus right now
                // which implies the focus stack is not empty.
                final int requestResult = pushBelowLockedFocusOwners(nfr);
                if (requestResult != AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                    notifyExtPolicyFocusGrant_syncAf(nfr.toAudioFocusInfo(), requestResult);
                }
                return requestResult;
            } else {
                // propagate the focus change through the stack
                if (!mFocusStack.empty()) {
                    propagateFocusLossFromGain_syncAf(focusChangeHint);
                }

                // push focus requester at the top of the audio focus stack
                mFocusStack.push(nfr);
            }
            notifyExtPolicyFocusGrant_syncAf(nfr.toAudioFocusInfo(),
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

        }//synchronized(mAudioFocusLock)

        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /**
     * @see AudioManager#abandonAudioFocus(AudioManager.OnAudioFocusChangeListener, AudioAttributes)
     * */
    protected int abandonAudioFocus(IAudioFocusDispatcher fl, String clientId, AudioAttributes aa) {
        // AudioAttributes are currently ignored, to be used for zones
        Log.i(TAG, " AudioFocus  abandonAudioFocus() from uid/pid " + Binder.getCallingUid()
                + "/" + Binder.getCallingPid()
                + " clientId=" + clientId);
        try {
            // this will take care of notifying the new focus owner if needed
            synchronized(mAudioFocusLock) {
                removeFocusStackEntry(clientId, true /*signal*/, true /*notifyFocusFollowers*/);
            }
        } catch (java.util.ConcurrentModificationException cme) {
            // Catching this exception here is temporary. It is here just to prevent
            // a crash seen when the "Silent" notification is played. This is believed to be fixed
            // but this try catch block is left just to be safe.
            Log.e(TAG, "FATAL EXCEPTION AudioFocus  abandonAudioFocus() caused " + cme);
            cme.printStackTrace();
        }

        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }


    protected void unregisterAudioFocusClient(String clientId) {
        synchronized(mAudioFocusLock) {
            removeFocusStackEntry(clientId, false, true /*notifyFocusFollowers*/);
        }
    }

}
