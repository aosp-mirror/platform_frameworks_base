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

import static android.media.audiopolicy.Flags.enableFadeManagerConfiguration;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.IAudioFocusDispatcher;
import android.media.MediaMetrics;
import android.media.audiopolicy.AudioPolicy;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;

/**
 * @hide
 *
 */
public class MediaFocusControl implements PlayerFocusEnforcer {

    private static final String TAG = "MediaFocusControl";
    static final boolean DEBUG = false;

    /**
     * set to true so the framework enforces ducking itself, without communicating to apps
     * that they lost focus for most use cases.
     */
    static final boolean ENFORCE_DUCKING = true;
    /**
     * set to true to the framework enforces ducking itself only with apps above a given SDK
     * target level. Is ignored if ENFORCE_DUCKING is false.
     */
    static final boolean ENFORCE_DUCKING_FOR_NEW = true;
    /**
     * the SDK level (included) up to which the framework doesn't enforce ducking itself. Is ignored
     * if ENFORCE_DUCKING_FOR_NEW is false;
     */
    // automatic ducking was introduced for Android O
    static final int DUCKING_IN_APP_SDK_LEVEL = Build.VERSION_CODES.N_MR1;
    /**
     * set to true so the framework enforces muting media/game itself when the device is ringing
     * or in a call.
     */
    static final boolean ENFORCE_MUTING_FOR_RING_OR_CALL = true;

    /**
     * set to true so the framework enforces fading out apps that lose audio focus in a
     * non-transient way.
     */
    static final boolean ENFORCE_FADEOUT_FOR_FOCUS_LOSS = true;

    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final @NonNull PlayerFocusEnforcer mFocusEnforcer;
    private boolean mMultiAudioFocusEnabled = false;

    private boolean mRingOrCallActive = false;

    private final Object mExtFocusChangeLock = new Object();
    @GuardedBy("mExtFocusChangeLock")
    private long mExtFocusChangeCounter;

    protected MediaFocusControl(Context cntxt, PlayerFocusEnforcer pfe) {
        mContext = cntxt;
        mAppOps = (AppOpsManager)mContext.getSystemService(Context.APP_OPS_SERVICE);
        mFocusEnforcer = pfe;
        final ContentResolver cr = mContext.getContentResolver();
        mMultiAudioFocusEnabled = Settings.System.getIntForUser(cr,
                Settings.System.MULTI_AUDIO_FOCUS_ENABLED, 0, cr.getUserId()) != 0;
        initFocusThreading();
    }

    protected void dump(PrintWriter pw) {
        pw.println("\nMediaFocusControl dump time: "
                + DateFormat.getTimeInstance().format(new Date()));
        dumpFocusStack(pw);
        pw.println("\n");
        // log
        mEventLogger.dump(pw);
        dumpMultiAudioFocus(pw);
    }

    /**
     * Test method to return the duration of the fade out applied on the players of a focus loser
     * @return the fade out duration in ms
     */
    public long getFocusFadeOutDurationForTest() {
        return getFadeOutDurationMillis(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
    }

    /**
     * Test method to return the length of time after a fade out before the focus loser is unmuted
     * (and is faded back in).
     * @return the time gap after a fade out completion on focus loss, and fade in start in ms
     */
    public long getFocusUnmuteDelayAfterFadeOutForTest() {
        return getFadeInDelayForOffendersMillis(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
    }

    //=================================================================
    // PlayerFocusEnforcer implementation
    @Override
    public boolean duckPlayers(@NonNull FocusRequester winner, @NonNull FocusRequester loser,
                               boolean forceDuck) {
        return mFocusEnforcer.duckPlayers(winner, loser, forceDuck);
    }

    @Override
    public void restoreVShapedPlayers(@NonNull FocusRequester winner) {
        mFocusEnforcer.restoreVShapedPlayers(winner);
        // remove scheduled events to unfade out offending players (if any) corresponding to
        // this uid, as we're removing any effects of muting/ducking/fade out now
        mFocusHandler.removeEqualMessages(MSL_L_FORGET_UID,
                new ForgetFadeUidInfo(winner.getClientUid()));

    }

    @Override
    public void mutePlayersForCall(int[] usagesToMute) {
        mFocusEnforcer.mutePlayersForCall(usagesToMute);
    }

    @Override
    public void unmutePlayersForCall() {
        mFocusEnforcer.unmutePlayersForCall();
    }

    @Override
    public boolean fadeOutPlayers(@NonNull FocusRequester winner, @NonNull FocusRequester loser) {
        return mFocusEnforcer.fadeOutPlayers(winner, loser);
    }

    @Override
    public void forgetUid(int uid) {
        mFocusEnforcer.forgetUid(uid);
    }

    @Override
    public long getFadeOutDurationMillis(@NonNull AudioAttributes aa) {
        if (aa == null) {
            return 0;
        }
        return mFocusEnforcer.getFadeOutDurationMillis(aa);
    }

    @Override
    public long getFadeInDelayForOffendersMillis(@NonNull AudioAttributes aa) {
        if (aa == null) {
            return 0;
        }
        return mFocusEnforcer.getFadeInDelayForOffendersMillis(aa);
    }

    @Override
    public boolean shouldEnforceFade() {
        if (!enableFadeManagerConfiguration()) {
            return ENFORCE_FADEOUT_FOR_FOCUS_LOSS;
        }

        return mFocusEnforcer.shouldEnforceFade();
    }
    //==========================================================================================
    // AudioFocus
    //==========================================================================================

    private final static Object mAudioFocusLock = new Object();

    /**
     * Arbitrary maximum size of audio focus stack to prevent apps OOM'ing this process.
     */
    private static final int MAX_STACK_SIZE = 100;

    private static final EventLogger
            mEventLogger = new EventLogger(50,
            "focus commands as seen by MediaFocusControl");

    private static final String mMetricsId = MediaMetrics.Name.AUDIO_FOCUS;

    /*package*/ void noFocusForSuspendedApp(@NonNull String packageName, int uid) {
        synchronized (mAudioFocusLock) {
            final Iterator<FocusRequester> stackIterator = mFocusStack.iterator();
            List<String> clientsToRemove = new ArrayList<>();
            while (stackIterator.hasNext()) {
                final FocusRequester focusOwner = stackIterator.next();
                if (focusOwner.hasSameUid(uid) && focusOwner.hasSamePackage(packageName)) {
                    clientsToRemove.add(focusOwner.getClientId());
                    mEventLogger.enqueue((new EventLogger.StringEvent(
                            "focus owner:" + focusOwner.getClientId()
                                    + " in uid:" + uid + " pack: " + packageName
                                    + " getting AUDIOFOCUS_LOSS due to app suspension"))
                            .printLog(TAG));
                    // make the suspended app lose focus through its focus listener (if any)
                    focusOwner.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS);
                }
            }
            for (String clientToRemove : clientsToRemove) {
                // update the stack but don't signal the change.
                removeFocusStackEntry(clientToRemove, false, true);
            }
        }
    }

    /*package*/ boolean hasAudioFocusUsers() {
        synchronized (mAudioFocusLock) {
            return !mFocusStack.empty();
        }
    }

    /**
     * Discard the current audio focus owner (unless the user is considered {@link
     * FocusRequester#isAlwaysVisibleUser() always visible)}.
     * Notify top of audio focus stack that it lost focus (regardless of possibility to reassign
     * focus), remove it from the stack, and clear the remote control display.
     * @return whether the current audio focus owner was discarded (including if there was none);
     *         returns false if it was purposefully kept
     */
    protected boolean maybeDiscardAudioFocusOwner() {
        synchronized(mAudioFocusLock) {
            if (!mFocusStack.empty()) {
                final FocusRequester exFocusOwner = mFocusStack.peek();
                if (!exFocusOwner.isAlwaysVisibleUser()) {
                    mFocusStack.pop();
                    exFocusOwner.handleFocusLoss(AudioManager.AUDIOFOCUS_LOSS, null,
                            false /*forceDuck*/);
                    exFocusOwner.release();
                    return true;
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Like {@link #sendFocusLoss(AudioFocusInfo)} but if the loser was at the top of stack,
     * make the next entry gain focus with {@link AudioManager#AUDIOFOCUS_GAIN}.
     * @param focusInfo the focus owner to discard
     * @see AudioPolicy#sendFocusLossAndUpdate(AudioFocusInfo)
     */
    protected void sendFocusLossAndUpdate(@NonNull AudioFocusInfo focusInfo) {
        synchronized (mAudioFocusLock) {
            if (mFocusStack.isEmpty()) {
                return;
            }
            final FocusRequester currentFocusOwner = mFocusStack.peek();
            if (currentFocusOwner.toAudioFocusInfo().equals(focusInfo)) {
                // focus loss is for the top of the stack
                currentFocusOwner.handleFocusLoss(AudioManager.AUDIOFOCUS_LOSS, null,
                            false /*forceDuck*/);
                currentFocusOwner.release();

                mFocusStack.pop();
                // is there a new focus owner?
                if (!mFocusStack.isEmpty()) {
                    mFocusStack.peek().handleFocusGain(AudioManager.AUDIOFOCUS_GAIN);
                }
            } else {
                // focus loss if for another entry that's not at the top of the stack,
                // just remove it from the stack and make it lose focus
                sendFocusLoss(focusInfo);
            }
        }
    }

    /**
     * Return a copy of the focus stack for external consumption (composed of AudioFocusInfo
     * instead of FocusRequester instances)
     * @return a SystemApi-friendly version of the focus stack, in the same order (last entry
     *         is top of focus stack, i.e. latest focus owner)
     * @see AudioPolicy#getFocusStack()
     */
    @NonNull List<AudioFocusInfo> getFocusStack() {
        synchronized (mAudioFocusLock) {
            final ArrayList<AudioFocusInfo> stack = new ArrayList<>(mFocusStack.size());
            for (FocusRequester fr : mFocusStack) {
                stack.add(fr.toAudioFocusInfo());
            }
            return stack;
        }
    }

    /**
     * Return the UID of the focus owner that has focus with exclusive focus gain
     * @return -1 if nobody has exclusive focus, the UID of the owner otherwise
     */
    protected int getExclusiveFocusOwnerUid() {
        synchronized (mAudioFocusLock) {
            if (mFocusStack.empty()) {
                return -1;
            }
            final FocusRequester owner = mFocusStack.peek();
            if (owner.getGainRequest() != AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) {
                return -1;
            }
            return owner.getClientUid();
        }
    }

    /**
     * Send AUDIOFOCUS_LOSS to a specific stack entry.
     * Note this method is supporting an external API, and is restricted to LOSS in order to
     * prevent allowing the stack to be in an invalid state (e.g. entry inside stack has focus)
     * @param focusLoser the stack entry that is exiting the stack through a focus loss
     * @return false if the focusLoser wasn't found in the stack, true otherwise
     * @see AudioPolicy#sendFocusLoss(AudioFocusInfo)
     */
    boolean sendFocusLoss(@NonNull AudioFocusInfo focusLoser) {
        synchronized (mAudioFocusLock) {
            FocusRequester loserToRemove = null;
            for (FocusRequester fr : mFocusStack) {
                if (fr.getClientId().equals(focusLoser.getClientId())) {
                    fr.handleFocusLoss(AudioManager.AUDIOFOCUS_LOSS, null,
                            false /*forceDuck*/);
                    loserToRemove = fr;
                    break;
                }
            }
            if (loserToRemove != null) {
                mFocusStack.remove(loserToRemove);
                loserToRemove.release();
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mAudioFocusLock")
    private void notifyTopOfAudioFocusStack() {
        // notify the top of the stack it gained focus
        if (!mFocusStack.empty()) {
            if (canReassignAudioFocus()) {
                mFocusStack.peek().handleFocusGain(AudioManager.AUDIOFOCUS_GAIN);
            }
        }

        if (mMultiAudioFocusEnabled && !mMultiAudioFocusList.isEmpty()) {
            for (FocusRequester multifr : mMultiAudioFocusList) {
                if (isLockedFocusOwner(multifr)) {
                    multifr.handleFocusGain(AudioManager.AUDIOFOCUS_GAIN);
                }
            }
        }
    }

    /**
     * Focus is requested, propagate the associated loss throughout the stack.
     * Will also remove entries in the stack that have just received a definitive loss of focus.
     * @param focusGain the new focus gain that will later be added at the top of the stack
     */
    @GuardedBy("mAudioFocusLock")
    private void propagateFocusLossFromGain_syncAf(int focusGain, final FocusRequester fr,
                                                   boolean forceDuck) {
        if (DEBUG) {
            Log.i(TAG, "propagateFocusLossFromGain_syncAf gain:" + focusGain);
        }
        final List<String> clientsToRemove = new LinkedList<String>();
        // going through the audio focus stack to signal new focus, traversing order doesn't
        // matter as all entries respond to the same external focus gain
        if (!mFocusStack.empty()) {
            for (FocusRequester focusLoser : mFocusStack) {
                if (DEBUG) {
                    Log.i(TAG, "propagateFocusLossFromGain_syncAf checking client:"
                            + focusLoser.getClientId());
                }
                final boolean isDefinitiveLoss =
                        focusLoser.handleFocusLossFromGain(focusGain, fr, forceDuck);
                if (isDefinitiveLoss) {
                    clientsToRemove.add(focusLoser.getClientId());
                }
            }
        } else if (DEBUG) {
            Log.i(TAG, "propagateFocusLossFromGain_syncAf empty stack");
        }

        if (mMultiAudioFocusEnabled && !mMultiAudioFocusList.isEmpty()) {
            for (FocusRequester multifocusLoser : mMultiAudioFocusList) {
                final boolean isDefinitiveLoss =
                        multifocusLoser.handleFocusLossFromGain(focusGain, fr, forceDuck);
                if (isDefinitiveLoss) {
                    clientsToRemove.add(multifocusLoser.getClientId());
                }
            }
        }

        for (String clientToRemove : clientsToRemove) {
            removeFocusStackEntry(clientToRemove, false /*signal*/,
                    true /*notifyFocusFollowers*/);
        }
    }

    private final Stack<FocusRequester> mFocusStack = new Stack<FocusRequester>();

    ArrayList<FocusRequester> mMultiAudioFocusList = new ArrayList<FocusRequester>();

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
            pw.println("\n");
            if (mFocusPolicy == null) {
                pw.println("No external focus policy\n");
            } else {
                pw.println("External focus policy: "+ mFocusPolicy + ", focus owners:\n");
                dumpExtFocusPolicyFocusOwners(pw);
            }
        }
        pw.println("\n");
        pw.println(" Notify on duck:  " + mNotifyFocusOwnerOnDuck + "\n");
        pw.println(" In ring or call: " + mRingOrCallActive + "\n");
    }

    /**
     * Remove a focus listener from the focus stack.
     * @param clientToRemove the focus listener
     * @param signal if true and the listener was at the top of the focus stack, i.e. it was holding
     *   focus, notify the next item in the stack it gained focus.
     */
    @GuardedBy("mAudioFocusLock")
    private void removeFocusStackEntry(String clientToRemove, boolean signal,
            boolean notifyFocusFollowers) {
        if (DEBUG) {
            Log.i(TAG, "removeFocusStackEntry client:" + clientToRemove);
        }
        AudioFocusInfo abandonSource = null;
        // is the current top of the focus stack abandoning focus? (because of request, not death)
        if (!mFocusStack.empty() && mFocusStack.peek().hasSameClient(clientToRemove))
        {
            //Log.i(TAG, "   removeFocusStackEntry() removing top of stack");
            FocusRequester fr = mFocusStack.pop();
            fr.maybeRelease();
            if (notifyFocusFollowers) {
                abandonSource = fr.toAudioFocusInfo();
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
                    if (notifyFocusFollowers) {
                        abandonSource = fr.toAudioFocusInfo();
                    }
                    // stack entry not used anymore, clear references
                    fr.maybeRelease();
                }
            }
        }
        // focus followers still want to know focus was abandoned, handled as a loss
        if (abandonSource != null) {
            abandonSource.clearLossReceived();
            notifyExtPolicyFocusLoss_syncAf(abandonSource, false);
        }

        if (mMultiAudioFocusEnabled && !mMultiAudioFocusList.isEmpty()) {
            Iterator<FocusRequester> listIterator = mMultiAudioFocusList.iterator();
            while (listIterator.hasNext()) {
                FocusRequester fr = listIterator.next();
                if (fr.hasSameClient(clientToRemove)) {
                    listIterator.remove();
                    fr.release();
                }
            }

            if (signal) {
                // notify the new top of the stack it gained focus
                notifyTopOfAudioFocusStack();
            }
        }
    }

    /**
     * Remove focus listeners from the focus stack for a particular client when it has died.
     */
    @GuardedBy("mAudioFocusLock")
    private void removeFocusStackEntryOnDeath(IBinder cb) {
        // is the owner of the audio focus part of the client to remove?
        boolean isTopOfStackForClientToRemove = !mFocusStack.isEmpty() &&
                mFocusStack.peek().hasSameBinder(cb);
        // (using an iterator on the stack so we can safely remove an entry after having
        //  evaluated it, traversal order doesn't matter here)
        Iterator<FocusRequester> stackIterator = mFocusStack.iterator();
        while(stackIterator.hasNext()) {
            FocusRequester fr = stackIterator.next();
            if(fr.hasSameBinder(cb)) {
                Log.i(TAG, "AudioFocus  removeFocusStackEntryOnDeath(): removing entry for " + cb);
                mEventLogger.enqueue(new EventLogger.StringEvent(
                        "focus requester:" + fr.getClientId()
                                + " in uid:" + fr.getClientUid()
                                + " pack:" + fr.getPackageName()
                                + " died"));
                notifyExtPolicyFocusLoss_syncAf(fr.toAudioFocusInfo(), false);

                stackIterator.remove();
                // stack entry not used anymore, clear references
                fr.release();
            }
        }
        if (isTopOfStackForClientToRemove) {
            // we removed an entry at the top of the stack:
            //  notify the new top of the stack it gained focus.
            notifyTopOfAudioFocusStack();
        }
    }

    /**
     * Helper function for external focus policy:
     * Remove focus listeners from the list of potential focus owners for a particular client when
     * it has died.
     */
    @GuardedBy("mAudioFocusLock")
    private void removeFocusEntryForExtPolicyOnDeath(IBinder cb) {
        if (mFocusOwnersForFocusPolicy.isEmpty()) {
            return;
        }
        boolean released = false;
        final Set<Entry<String, FocusRequester>> owners = mFocusOwnersForFocusPolicy.entrySet();
        final Iterator<Entry<String, FocusRequester>> ownerIterator = owners.iterator();
        while (ownerIterator.hasNext()) {
            final Entry<String, FocusRequester> owner = ownerIterator.next();
            final FocusRequester fr = owner.getValue();
            if (fr.hasSameBinder(cb)) {
                ownerIterator.remove();
                mEventLogger.enqueue(new EventLogger.StringEvent(
                        "focus requester:" + fr.getClientId()
                                + " in uid:" + fr.getClientUid()
                                + " pack:" + fr.getPackageName()
                                + " died"));
                fr.release();
                notifyExtFocusPolicyFocusAbandon_syncAf(fr.toAudioFocusInfo());
                break;
            }
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
     * Propagate through the stack the changes that the new (future) focus owner causes.
     * @param nfr the future focus owner that will gain focus when the locked focus owners are
     *            removed from the stack
     * @return {@link AudioManager#AUDIOFOCUS_REQUEST_GRANTED} or
     *     {@link AudioManager#AUDIOFOCUS_REQUEST_DELAYED}
     */
    @GuardedBy("mAudioFocusLock")
    private int pushBelowLockedFocusOwnersAndPropagate(FocusRequester nfr) {
        if (DEBUG) {
            Log.v(TAG, "pushBelowLockedFocusOwnersAndPropagate client=" + nfr.getClientId());
        }
        int lastLockedFocusOwnerIndex = mFocusStack.size();
        for (int index = mFocusStack.size() - 1; index >= 0; index--) {
            if (isLockedFocusOwner(mFocusStack.elementAt(index))) {
                lastLockedFocusOwnerIndex = index;
            }
        }
        if (lastLockedFocusOwnerIndex == mFocusStack.size()) {
            // this should not happen, but handle it and log an error
            Log.e(TAG, "No exclusive focus owner found in propagateFocusLossFromGain_syncAf()",
                    new Exception());
            // no exclusive owner, push at top of stack, focus is granted, propagate change
            propagateFocusLossFromGain_syncAf(nfr.getGainRequest(), nfr, false /*forceDuck*/);
            mFocusStack.push(nfr);
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }

        if (DEBUG) {
            Log.v(TAG, "> lastLockedFocusOwnerIndex=" + lastLockedFocusOwnerIndex);
        }
        mFocusStack.insertElementAt(nfr, lastLockedFocusOwnerIndex);

        // propagate potential focus loss (and removal from stack) after the newly
        // inserted FocusRequester (at index lastLockedFocusOwnerIndex-1)
        final List<String> clientsToRemove = new LinkedList<String>();
        for (int index = lastLockedFocusOwnerIndex - 1; index >= 0; index--) {
            final boolean isDefinitiveLoss =
                    mFocusStack.elementAt(index).handleFocusLossFromGain(
                            nfr.getGainRequest(), nfr, false /*forceDuck*/);
            if (isDefinitiveLoss) {
                clientsToRemove.add(mFocusStack.elementAt(index).getClientId());
            }
        }
        for (String clientToRemove : clientsToRemove) {
            if (DEBUG) {
                Log.v(TAG, "> removing focus client " + clientToRemove);
            }
            removeFocusStackEntry(clientToRemove, false /*signal*/,
                    true /*notifyFocusFollowers*/);
        }

        return AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
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
                if (mFocusPolicy != null) {
                    removeFocusEntryForExtPolicyOnDeath(mCb);
                } else {
                    removeFocusStackEntryOnDeath(mCb);
                    if (mMultiAudioFocusEnabled && !mMultiAudioFocusList.isEmpty()) {
                        Iterator<FocusRequester> listIterator = mMultiAudioFocusList.iterator();
                        while (listIterator.hasNext()) {
                            FocusRequester fr = listIterator.next();
                            if (fr.hasSameBinder(mCb)) {
                                listIterator.remove();
                                fr.release();
                            }
                        }
                    }
                }
            }
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

    /** The current audio focus policy */
    @GuardedBy("mAudioFocusLock")
    @Nullable private IAudioPolicyCallback mFocusPolicy = null;
    /**
     * The audio focus policy that was registered before a test focus policy was registered
     * during a test
     */
    @GuardedBy("mAudioFocusLock")
    @Nullable private IAudioPolicyCallback mPreviousFocusPolicy = null;

    // Since we don't have a stack of focus owners when using an external focus policy, we keep
    // track of all the focus requesters in this map, with their clientId as the key. This is
    // used both for focus dispatch and death handling
    private HashMap<String, FocusRequester> mFocusOwnersForFocusPolicy =
            new HashMap<String, FocusRequester>();

    void setFocusPolicy(IAudioPolicyCallback policy, boolean isTestFocusPolicy) {
        if (policy == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            if (isTestFocusPolicy) {
                mPreviousFocusPolicy = mFocusPolicy;
            }
            mFocusPolicy = policy;
        }
    }

    void unsetFocusPolicy(IAudioPolicyCallback policy, boolean isTestFocusPolicy) {
        if (policy == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            if (mFocusPolicy == policy) {
                if (isTestFocusPolicy) {
                    // restore the focus policy that was there before the focus policy test started
                    mFocusPolicy = mPreviousFocusPolicy;
                } else {
                    mFocusPolicy = null;
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

    /**
     * Called synchronized on mAudioFocusLock.
     * Can only be called with an external focus policy installed (mFocusPolicy != null)
     * @param afi
     * @param fd
     * @param cb binder of the focus requester
     * @return true if the external audio focus policy (if any) can handle the focus request,
     *     and false if there was any error handling the request (e.g. error talking to policy,
     *     focus requester is already dead)
     */
    boolean notifyExtFocusPolicyFocusRequest_syncAf(AudioFocusInfo afi,
            IAudioFocusDispatcher fd, @NonNull IBinder cb) {
        if (DEBUG) {
            Log.v(TAG, "notifyExtFocusPolicyFocusRequest client="+afi.getClientId()
            + " dispatcher=" + fd);
        }
        synchronized (mExtFocusChangeLock) {
            afi.setGen(mExtFocusChangeCounter++);
        }
        final FocusRequester existingFr = mFocusOwnersForFocusPolicy.get(afi.getClientId());
        boolean keepTrack = false;
        if (existingFr != null) {
            if (!existingFr.hasSameDispatcher(fd)) {
                existingFr.release();
                keepTrack = true;
            }
        } else {
            keepTrack = true;
        }
        if (keepTrack) {
            final AudioFocusDeathHandler hdlr = new AudioFocusDeathHandler(cb);
            try {
                cb.linkToDeath(hdlr, 0);
            } catch (RemoteException e) {
                // client has already died!
                return false;
            }
            // new focus (future) focus owner to keep track of
            mFocusOwnersForFocusPolicy.put(afi.getClientId(),
                    new FocusRequester(afi, fd, cb, hdlr, this));
        }

        try {
            //oneway
            mFocusPolicy.notifyAudioFocusRequest(afi, AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call notifyAudioFocusRequest() on IAudioPolicyCallback "
                    + mFocusPolicy.asBinder(), e);
        }
        return false;
    }

    void setFocusRequestResultFromExtPolicy(AudioFocusInfo afi, int requestResult) {
        synchronized (mExtFocusChangeLock) {
            if (afi.getGen() > mExtFocusChangeCounter) {
                return;
            }
        }
        synchronized (mAudioFocusLock) {
            FocusRequester fr = getFocusRequesterLocked(afi.getClientId(),
                    /* shouldRemove= */ requestResult == AudioManager.AUDIOFOCUS_REQUEST_FAILED);
            if (fr != null) {
                fr.dispatchFocusResultFromExtPolicy(requestResult);
                // if fade is enabled for external focus policies, apply it when setting
                // focus result as well
                if (enableFadeManagerConfiguration()) {
                    fr.handleFocusGainFromRequest(requestResult);
                }
            }
        }
    }

    /**
     * Called synchronized on mAudioFocusLock
     * @param afi
     * @return true if the external audio focus policy (if any) is handling the focus request
     */
    boolean notifyExtFocusPolicyFocusAbandon_syncAf(AudioFocusInfo afi) {
        if (mFocusPolicy == null) {
            return false;
        }
        final FocusRequester fr = mFocusOwnersForFocusPolicy.remove(afi.getClientId());
        if (fr != null) {
            fr.release();
        }
        try {
            //oneway
            mFocusPolicy.notifyAudioFocusAbandon(afi);
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call notifyAudioFocusAbandon() on IAudioPolicyCallback "
                    + mFocusPolicy.asBinder(), e);
        }
        return true;
    }

    /** see AudioManager.dispatchFocusChange(AudioFocusInfo afi, int focusChange, AudioPolicy ap) */
    int dispatchFocusChange(AudioFocusInfo afi, int focusChange) {
        if (DEBUG) {
            Log.v(TAG, "dispatchFocusChange " + focusChange + " to afi client="
                    + afi.getClientId());
        }
        synchronized (mAudioFocusLock) {
            FocusRequester fr = getFocusRequesterLocked(afi.getClientId(),
                    /* shouldRemove= */ focusChange == AudioManager.AUDIOFOCUS_LOSS);
            if (fr == null) {
                if (DEBUG) {
                    Log.v(TAG, "> failed: no such focus requester known");
                }
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            }
            return fr.dispatchFocusChange(focusChange);
        }
    }

    int dispatchFocusChangeWithFade(AudioFocusInfo afi, int focusChange,
            List<AudioFocusInfo> otherActiveAfis) {
        if (DEBUG) {
            Log.v(TAG, "dispatchFocusChangeWithFade " + AudioManager.audioFocusToString(focusChange)
                    + " to afi client=" + afi.getClientId()
                    + " other active afis=" + otherActiveAfis);
        }

        synchronized (mAudioFocusLock) {
            String clientId = afi.getClientId();
            // do not remove the entry since it can be posted for fade
            FocusRequester fr = getFocusRequesterLocked(clientId, /* shouldRemove= */ false);
            if (fr == null) {
                if (DEBUG) {
                    Log.v(TAG, "> failed: no such focus requester known");
                }
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            }

            // convert other AudioFocusInfo to corresponding FocusRequester
            ArrayList<FocusRequester> otherActiveFrs = new ArrayList<>();
            for (int index = 0; index < otherActiveAfis.size(); index++) {
                FocusRequester otherFr = getFocusRequesterLocked(
                        otherActiveAfis.get(index).getClientId(), /* shouldRemove= */ false);
                if (otherFr == null) {
                    continue;
                }
                otherActiveFrs.add(otherFr);
            }

            int status = fr.dispatchFocusChangeWithFadeLocked(focusChange, otherActiveFrs);
            if (status != AudioManager.AUDIOFOCUS_REQUEST_DELAYED
                    && focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                mFocusOwnersForFocusPolicy.remove(clientId);
            }

            return status;
        }
    }

    @GuardedBy("mAudioFocusLock")
    private FocusRequester getFocusRequesterLocked(String clientId, boolean shouldRemove) {
        if (mFocusPolicy == null) {
            if (DEBUG) {
                Log.v(TAG, "> failed: no focus policy");
            }
            return null;
        }

        FocusRequester fr;
        if (shouldRemove) {
            fr = mFocusOwnersForFocusPolicy.remove(clientId);
        } else {
            fr = mFocusOwnersForFocusPolicy.get(clientId);
        }

        if (fr == null && DEBUG) {
            Log.v(TAG, "> failed: no such focus requester known");
        }
        return fr;
    }

    private void dumpExtFocusPolicyFocusOwners(PrintWriter pw) {
        final Set<Entry<String, FocusRequester>> owners = mFocusOwnersForFocusPolicy.entrySet();
        final Iterator<Entry<String, FocusRequester>> ownerIterator = owners.iterator();
        while (ownerIterator.hasNext()) {
            final Entry<String, FocusRequester> owner = ownerIterator.next();
            final FocusRequester fr = owner.getValue();
            fr.dump(pw);
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

    /**
     * Delay after entering ringing or call mode after which the framework will mute streams
     * that are still playing.
     */
    private static final int RING_CALL_MUTING_ENFORCEMENT_DELAY_MS = 100;

    /**
     * Usages to mute when the device rings or is in a call
     */
    private final static int[] USAGES_TO_MUTE_IN_RING_OR_CALL =
        { AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME };

    /**
     * Return the volume ramp time expected before playback with the given AudioAttributes would
     * start after gaining audio focus.
     * @param attr attributes of the sound about to start playing
     * @return time in ms
     */
    protected static int getFocusRampTimeMs(int focusGain, AudioAttributes attr) {
        switch (attr.getUsage()) {
            case AudioAttributes.USAGE_MEDIA:
            case AudioAttributes.USAGE_GAME:
                return 1000;
            case AudioAttributes.USAGE_ALARM:
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
            case AudioAttributes.USAGE_ASSISTANT:
            case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
            case AudioAttributes.USAGE_ANNOUNCEMENT:
                return 700;
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
            case AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING:
            case AudioAttributes.USAGE_NOTIFICATION:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
            case AudioAttributes.USAGE_NOTIFICATION_EVENT:
            case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
            case AudioAttributes.USAGE_VEHICLE_STATUS:
                return 500;
            case AudioAttributes.USAGE_EMERGENCY:
            case AudioAttributes.USAGE_SAFETY:
            case AudioAttributes.USAGE_UNKNOWN:
            default:
                return 0;
        }
    }

    /** @see AudioManager#requestAudioFocus(AudioManager.OnAudioFocusChangeListener, int, int, int)
     * @param aa
     * @param focusChangeHint
     * @param cb
     * @param fd
     * @param clientId
     * @param callingPackageName
     * @param flags
     * @param sdk
     * @param forceDuck only true if
     *     {@link android.media.AudioFocusRequest.Builder#setFocusGain(int)} was set to true for
     *                  accessibility.
     * @param testUid ignored if flags doesn't contain AudioManager.AUDIOFOCUS_FLAG_TEST
     *                otherwise the UID being injected for testing
     * @param permissionOverridesCheck true if permission checks guaranteed that the call should
     *                                 go through, false otherwise (e.g. non-privileged caller)
     * @return
     */
    protected int requestAudioFocus(@NonNull AudioAttributes aa, int focusChangeHint, IBinder cb,
            IAudioFocusDispatcher fd, @NonNull String clientId, @NonNull String callingPackageName,
            int flags, int sdk, boolean forceDuck, int testUid,
            boolean permissionOverridesCheck) {
        new MediaMetrics.Item(mMetricsId)
                .setUid(Binder.getCallingUid())
                .set(MediaMetrics.Property.CALLING_PACKAGE, callingPackageName)
                .set(MediaMetrics.Property.CLIENT_NAME, clientId)
                .set(MediaMetrics.Property.EVENT, "requestAudioFocus")
                .set(MediaMetrics.Property.FLAGS, flags)
                .set(MediaMetrics.Property.FOCUS_CHANGE_HINT,
                        AudioManager.audioFocusToString(focusChangeHint))
                //.set(MediaMetrics.Property.SDK, sdk)
                .record();

        // when using the test API, a fake UID can be injected (testUid is ignored otherwise)
        // note that the test on flags is not a mask test on purpose, AUDIOFOCUS_FLAG_TEST is
        // supposed to be alone in bitfield
        final int uid = (flags == AudioManager.AUDIOFOCUS_FLAG_TEST)
                ? testUid : Binder.getCallingUid();
        mEventLogger.enqueue((new EventLogger.StringEvent(
                "requestAudioFocus() from uid/pid " + uid
                    + "/" + Binder.getCallingPid()
                    + " AA=" + aa.usageToString() + "/" + aa.contentTypeToString()
                    + " clientId=" + clientId + " callingPack=" + callingPackageName
                    + " req=" + focusChangeHint
                    + " flags=0x" + Integer.toHexString(flags)
                    + " sdk=" + sdk))
                .printLog(TAG));
        // we need a valid binder callback for clients
        if (!cb.pingBinder()) {
            Log.e(TAG, " AudioFocus DOA client for requestAudioFocus(), aborting.");
            return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }

        synchronized(mAudioFocusLock) {
            // check whether a focus freeze is in place and filter
            if (isFocusFrozenForTest()) {
                int focusRequesterUid;
                if ((flags & AudioManager.AUDIOFOCUS_FLAG_TEST)
                        == AudioManager.AUDIOFOCUS_FLAG_TEST) {
                    focusRequesterUid = testUid;
                } else {
                    focusRequesterUid = Binder.getCallingUid();
                }
                if (isFocusFrozenForTestForUid(focusRequesterUid)) {
                    Log.i(TAG, "requestAudioFocus: focus frozen for test for uid:"
                            + focusRequesterUid);
                    return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
                }
                Log.i(TAG, "requestAudioFocus: focus frozen for test but uid:" + focusRequesterUid
                        + " is exempt");
            }

            if (mFocusStack.size() > MAX_STACK_SIZE) {
                Log.e(TAG, "Max AudioFocus stack size reached, failing requestAudioFocus()");
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            }

            boolean enteringRingOrCall = !mRingOrCallActive
                    & (AudioSystem.IN_VOICE_COMM_FOCUS_ID.compareTo(clientId) == 0);
            if (enteringRingOrCall) { mRingOrCallActive = true; }

            final AudioFocusInfo afiForExtPolicy;
            if (mFocusPolicy != null) {
                // construct AudioFocusInfo as it will be communicated to audio focus policy
                afiForExtPolicy = new AudioFocusInfo(aa, uid,
                        clientId, callingPackageName, focusChangeHint, 0 /*lossReceived*/,
                        flags, sdk);
            } else {
                afiForExtPolicy = null;
            }

            // handle delayed focus
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

            // external focus policy?
            if (mFocusPolicy != null) {
                if (notifyExtFocusPolicyFocusRequest_syncAf(afiForExtPolicy, fd, cb)) {
                    // stop handling focus request here as it is handled by external audio
                    // focus policy (return code will be handled in AudioManager)
                    return AudioManager.AUDIOFOCUS_REQUEST_WAITING_FOR_EXT_POLICY;
                } else {
                    // an error occured, client already dead, bail early
                    return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
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
                    clientId, afdh, callingPackageName, uid, this, sdk);

            if (mMultiAudioFocusEnabled
                    && (focusChangeHint == AudioManager.AUDIOFOCUS_GAIN)) {
                if (enteringRingOrCall) {
                    if (!mMultiAudioFocusList.isEmpty()) {
                        for (FocusRequester multifr : mMultiAudioFocusList) {
                            multifr.handleFocusLossFromGain(focusChangeHint, nfr, forceDuck);
                        }
                    }
                } else {
                    boolean needAdd = true;
                    if (!mMultiAudioFocusList.isEmpty()) {
                        for (FocusRequester multifr : mMultiAudioFocusList) {
                            if (multifr.getClientUid() == Binder.getCallingUid()) {
                                needAdd = false;
                                break;
                            }
                        }
                    }
                    if (needAdd) {
                        mMultiAudioFocusList.add(nfr);
                    }
                    nfr.handleFocusGainFromRequest(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                    notifyExtPolicyFocusGrant_syncAf(nfr.toAudioFocusInfo(),
                            AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
            }

            if (focusGrantDelayed) {
                // focusGrantDelayed being true implies we can't reassign focus right now
                // which implies the focus stack is not empty.
                final int requestResult = pushBelowLockedFocusOwnersAndPropagate(nfr);
                if (requestResult != AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                    notifyExtPolicyFocusGrant_syncAf(nfr.toAudioFocusInfo(), requestResult);
                }
                return requestResult;
            } else {
                // propagate the focus change through the stack
                propagateFocusLossFromGain_syncAf(focusChangeHint, nfr, forceDuck);

                // push focus requester at the top of the audio focus stack
                mFocusStack.push(nfr);
                nfr.handleFocusGainFromRequest(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            }
            notifyExtPolicyFocusGrant_syncAf(nfr.toAudioFocusInfo(),
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

            if (ENFORCE_MUTING_FOR_RING_OR_CALL & enteringRingOrCall) {
                runAudioCheckerForRingOrCallAsync(true/*enteringRingOrCall*/);
            }
        }//synchronized(mAudioFocusLock)

        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /**
     * @see AudioManager#abandonAudioFocus(AudioManager.OnAudioFocusChangeListener, AudioAttributes)
     * */
    protected int abandonAudioFocus(IAudioFocusDispatcher fl, String clientId, AudioAttributes aa,
            String callingPackageName) {
        new MediaMetrics.Item(mMetricsId)
                .setUid(Binder.getCallingUid())
                .set(MediaMetrics.Property.CALLING_PACKAGE, callingPackageName)
                .set(MediaMetrics.Property.CLIENT_NAME, clientId)
                .set(MediaMetrics.Property.EVENT, "abandonAudioFocus")
                .record();

        // AudioAttributes are currently ignored, to be used for zones / a11y
        mEventLogger.enqueue((new EventLogger.StringEvent(
                "abandonAudioFocus() from uid/pid " + Binder.getCallingUid()
                    + "/" + Binder.getCallingPid()
                    + " clientId=" + clientId + " callingPack=" + callingPackageName))
                .printLog(TAG));
        try {
            // this will take care of notifying the new focus owner if needed
            synchronized(mAudioFocusLock) {
                // external focus policy?
                if (mFocusPolicy != null) {
                    final AudioFocusInfo afi = new AudioFocusInfo(aa, Binder.getCallingUid(),
                            clientId, callingPackageName, 0 /*gainRequest*/, 0 /*lossReceived*/,
                            0 /*flags*/, 0 /* sdk n/a here*/);
                    if (notifyExtFocusPolicyFocusAbandon_syncAf(afi)) {
                        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                    }
                }

                boolean exitingRingOrCall = mRingOrCallActive
                        & (AudioSystem.IN_VOICE_COMM_FOCUS_ID.compareTo(clientId) == 0);
                if (exitingRingOrCall) { mRingOrCallActive = false; }

                removeFocusStackEntry(clientId, true /*signal*/, true /*notifyFocusFollowers*/);

                if (ENFORCE_MUTING_FOR_RING_OR_CALL & exitingRingOrCall) {
                    runAudioCheckerForRingOrCallAsync(false/*enteringRingOrCall*/);
                }
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

    /**
     * Reference to the caller of {@link #enterAudioFocusFreezeForTest(IBinder, int[])}
     * Will be null when there is no focus freeze for test
     */
    @GuardedBy("mAudioFocusLock")
    @Nullable
    private IBinder mFocusFreezerForTest = null;

    /**
     * The death handler for {@link #mFocusFreezerForTest}
     * Will be null when there is no focus freeze for test
     */
    @GuardedBy("mAudioFocusLock")
    @Nullable
    private IBinder.DeathRecipient mFocusFreezerDeathHandler = null;

    /**
     *  Array of UIDs exempt from focus freeze when focus is frozen for test, null during normal
     *  operations.
     *  Will be null when there is no focus freeze for test
     */
    @GuardedBy("mAudioFocusLock")
    @Nullable
    private int[] mFocusFreezeExemptUids = null;

    @GuardedBy("mAudioFocusLock")
    private boolean isFocusFrozenForTest() {
        return (mFocusFreezerForTest != null);
    }

    /**
     * Checks if the given UID can request focus when a focus freeze is in place for a test.
     * Focus can be requested if focus is not frozen or if it's frozen but the UID is exempt.
     * @param uidToCheck
     * @return true if that UID is barred from requesting focus, false if its focus request
     *     can proceed being processed
     */
    @GuardedBy("mAudioFocusLock")
    private boolean isFocusFrozenForTestForUid(int uidToCheck) {
        if (isFocusFrozenForTest()) {
            return false;
        }
        // check the list of exempts (array is not null because we're in a freeze for test
        for (int uid : mFocusFreezeExemptUids) {
            if (uid == uidToCheck) {
                return false;
            }
        }
        // uid was not found in the exempt list, its focus request is denied
        return true;
    }

    protected boolean enterAudioFocusFreezeForTest(
            @NonNull IBinder cb, @NonNull int[] exemptedUids) {
        Log.i(TAG, "enterAudioFocusFreezeForTest UIDs exempt:" + Arrays.toString(exemptedUids));
        synchronized (mAudioFocusLock) {
            if (mFocusFreezerForTest != null) {
                Log.e(TAG, "Error enterAudioFocusFreezeForTest: focus already frozen");
                return false;
            }
            // new focus freeze, register death handler
            try {
                mFocusFreezerDeathHandler = new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Log.i(TAG, "Audio focus freezer died, exiting focus freeze for test");
                        releaseFocusFreeze();
                    }
                };
                cb.linkToDeath(mFocusFreezerDeathHandler, 0);
                mFocusFreezerForTest = cb;
                mFocusFreezeExemptUids = exemptedUids.clone();
            } catch (RemoteException e) {
                // client has already died!
                mFocusFreezerForTest = null;
                mFocusFreezeExemptUids = null;
                return false;
            }
        }
        return true;
    }

    protected boolean exitAudioFocusFreezeForTest(@NonNull IBinder cb) {
        synchronized (mAudioFocusLock) {
            if (mFocusFreezerForTest != cb) {
                Log.e(TAG, "Error exitAudioFocusFreezeForTest: "
                        + ((mFocusFreezerForTest == null)
                        ? "call to exit while not frozen"
                        : "call to exit not coming from freeze owner"));
                return false;
            }
            mFocusFreezerForTest.unlinkToDeath(mFocusFreezerDeathHandler, 0);
            releaseFocusFreeze();
        }
        return true;
    }

    private void releaseFocusFreeze() {
        synchronized (mAudioFocusLock) {
            mFocusFreezerDeathHandler = null;
            mFocusFreezeExemptUids = null;
            mFocusFreezerForTest = null;
        }
    }

    protected void unregisterAudioFocusClient(String clientId) {
        synchronized(mAudioFocusLock) {
            removeFocusStackEntry(clientId, false, true /*notifyFocusFollowers*/);
        }
    }

    private void runAudioCheckerForRingOrCallAsync(final boolean enteringRingOrCall) {
        new Thread() {
            public void run() {
                if (enteringRingOrCall) {
                    try {
                        Thread.sleep(RING_CALL_MUTING_ENFORCEMENT_DELAY_MS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                synchronized (mAudioFocusLock) {
                    // since the new thread starting running the state could have changed, so
                    // we need to check again mRingOrCallActive, not enteringRingOrCall
                    if (mRingOrCallActive) {
                        mFocusEnforcer.mutePlayersForCall(USAGES_TO_MUTE_IN_RING_OR_CALL);
                    } else {
                        mFocusEnforcer.unmutePlayersForCall();
                    }
                }
            }
        }.start();
    }

    public void updateMultiAudioFocus(boolean enabled) {
        Log.d(TAG, "updateMultiAudioFocus( " + enabled + " )");
        mMultiAudioFocusEnabled = enabled;
        final ContentResolver cr = mContext.getContentResolver();
        Settings.System.putIntForUser(cr,
                Settings.System.MULTI_AUDIO_FOCUS_ENABLED, enabled ? 1 : 0, cr.getUserId());
        if (!mFocusStack.isEmpty()) {
            final FocusRequester fr = mFocusStack.peek();
            fr.handleFocusLoss(AudioManager.AUDIOFOCUS_LOSS, null, false);
        }
        if (!enabled) {
            if (!mMultiAudioFocusList.isEmpty()) {
                for (FocusRequester multifr : mMultiAudioFocusList) {
                    multifr.handleFocusLoss(AudioManager.AUDIOFOCUS_LOSS, null, false);
                }
                mMultiAudioFocusList.clear();
            }
        }
    }

    public boolean getMultiAudioFocusEnabled() {
        return mMultiAudioFocusEnabled;
    }

    /*package*/ long getFadeOutDurationOnFocusLossMillis(AudioAttributes aa) {
        if (!ENFORCE_FADEOUT_FOR_FOCUS_LOSS) {
            return 0;
        }
        return getFadeOutDurationMillis(aa);
    }

    private void dumpMultiAudioFocus(PrintWriter pw) {
        pw.println("Multi Audio Focus enabled :" + mMultiAudioFocusEnabled);
        if (!mMultiAudioFocusList.isEmpty()) {
            pw.println("Multi Audio Focus List:");
            pw.println("------------------------------");
            for (FocusRequester multifr : mMultiAudioFocusList) {
                multifr.dump(pw);
            }
            pw.println("------------------------------");
        }
    }

    //=================================================================
    // Async focus events
    void postDelayedLossAfterFade(FocusRequester focusLoser, long delayMs) {
        if (DEBUG) {
            Log.v(TAG, "postDelayedLossAfterFade loser=" + focusLoser.getPackageName());
        }
        mFocusHandler.sendMessageDelayed(
                mFocusHandler.obtainMessage(MSG_L_FOCUS_LOSS_AFTER_FADE, focusLoser), delayMs);
    }

    private void postForgetUidLater(FocusRequester focusRequester) {
        mFocusHandler.sendMessageDelayed(
                mFocusHandler.obtainMessage(MSL_L_FORGET_UID,
                        new ForgetFadeUidInfo(focusRequester.getClientUid())),
                getFadeInDelayForOffendersMillis(focusRequester.getAudioAttributes()));
    }

    //=================================================================
    // Message handling
    private Handler mFocusHandler;
    private HandlerThread mFocusThread;

    /**
     * dispatch a focus loss after an app has been faded out. Focus loser is to be released
     * after dispatch as it has already left the stack
     * args:
     *     msg.obj: the audio focus loser
     *         type:FocusRequester
     */
    private static final int MSG_L_FOCUS_LOSS_AFTER_FADE = 1;

    private static final int MSL_L_FORGET_UID = 2;

    private void initFocusThreading() {
        mFocusThread = new HandlerThread(TAG);
        mFocusThread.start();
        mFocusHandler = new Handler(mFocusThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_L_FOCUS_LOSS_AFTER_FADE:
                        if (DEBUG) {
                            Log.d(TAG, "MSG_L_FOCUS_LOSS_AFTER_FADE loser="
                                    + ((FocusRequester) msg.obj).getPackageName());
                        }
                        synchronized (mAudioFocusLock) {
                            final FocusRequester loser = (FocusRequester) msg.obj;
                            if (loser.isInFocusLossLimbo()) {
                                loser.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS);
                                loser.release();
                                postForgetUidLater(loser);
                            }
                        }
                        break;

                    case MSL_L_FORGET_UID:
                        final int uid = ((ForgetFadeUidInfo) msg.obj).mUid;
                        if (DEBUG) {
                            Log.d(TAG, "MSL_L_FORGET_UID uid=" + uid);
                        }
                        mFocusEnforcer.forgetUid(uid);
                        break;
                    default:
                        break;
                }
            }
        };
    }

    /**
     * Class to associate a UID with a scheduled event to "forget" a UID for the fade out behavior.
     * Having a class with an equals() override allows using Handler.removeEqualsMessage() to
     * unschedule events when needed. Here we need to unschedule the "unfading out" == "forget uid"
     * whenever a new, more recent, focus related event happens before this one is handled.
     */
    private static final class ForgetFadeUidInfo {
        private final int mUid;

        ForgetFadeUidInfo(int uid) {
            mUid = uid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final ForgetFadeUidInfo f = (ForgetFadeUidInfo) o;
            if (f.mUid != mUid) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return mUid;
        }
    }
}
