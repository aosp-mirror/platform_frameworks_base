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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.app.PendingIntent.OnFinished;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.IBinder.DeathRecipient;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Stack;

/**
 * @hide
 *
 */
public class MediaFocusControl implements OnFinished {

    private static final String TAG = "MediaFocusControl";

    /** Debug remote control client/display feature */
    protected static final boolean DEBUG_RC = false;
    /** Debug volumes */
    protected static final boolean DEBUG_VOL = false;

    /** Used to alter media button redirection when the phone is ringing. */
    private boolean mIsRinging = false;

    private final PowerManager.WakeLock mMediaEventWakeLock;
    private final MediaEventHandler mEventHandler;
    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final VolumeController mVolumeController;
    private final BroadcastReceiver mReceiver = new PackageIntentsReceiver();
    private final AppOpsManager mAppOps;
    private final KeyguardManager mKeyguardManager;
    private final AudioService mAudioService;
    private final NotificationListenerObserver mNotifListenerObserver;

    protected MediaFocusControl(Looper looper, Context cntxt,
            VolumeController volumeCtrl, AudioService as) {
        mEventHandler = new MediaEventHandler(looper);
        mContext = cntxt;
        mContentResolver = mContext.getContentResolver();
        mVolumeController = volumeCtrl;
        mAudioService = as;

        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mMediaEventWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "handleMediaEvent");
        mMainRemote = new RemotePlaybackState(-1,
                AudioService.getMaxStreamVolume(AudioManager.STREAM_MUSIC),
                AudioService.getMaxStreamVolume(AudioManager.STREAM_MUSIC));

        // Register for phone state monitoring
        TelephonyManager tmgr = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        tmgr.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);

        // Register for package addition/removal/change intent broadcasts
        //    for media button receiver persistence
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        pkgFilter.addDataScheme("package");
        mContext.registerReceiver(mReceiver, pkgFilter);

        mAppOps = (AppOpsManager)mContext.getSystemService(Context.APP_OPS_SERVICE);
        mKeyguardManager =
                (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mNotifListenerObserver = new NotificationListenerObserver();

        mHasRemotePlayback = false;
        mMainRemoteIsActive = false;
        postReevaluateRemote();
    }

    protected void dump(PrintWriter pw) {
        dumpFocusStack(pw);
        dumpRCStack(pw);
        dumpRCCStack(pw);
        dumpRCDList(pw);
    }

    //==========================================================================================
    // Management of RemoteControlDisplay registration permissions
    //==========================================================================================
    private final static Uri ENABLED_NOTIFICATION_LISTENERS_URI =
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);

    private class NotificationListenerObserver extends ContentObserver {

        NotificationListenerObserver() {
            super(mEventHandler);
            mContentResolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.ENABLED_NOTIFICATION_LISTENERS), false, this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!ENABLED_NOTIFICATION_LISTENERS_URI.equals(uri) || selfChange) {
                return;
            }
            if (DEBUG_RC) { Log.d(TAG, "NotificationListenerObserver.onChange()"); }
            postReevaluateRemoteControlDisplays();
        }
    }

    private final static int RCD_REG_FAILURE = 0;
    private final static int RCD_REG_SUCCESS_PERMISSION = 1;
    private final static int RCD_REG_SUCCESS_ENABLED_NOTIF = 2;

    /**
     * Checks a caller's authorization to register an IRemoteControlDisplay.
     * Authorization is granted if one of the following is true:
     * <ul>
     * <li>the caller has android.Manifest.permission.MEDIA_CONTENT_CONTROL permission</li>
     * <li>the caller's listener is one of the enabled notification listeners</li>
     * </ul>
     * @return RCD_REG_FAILURE if it's not safe to proceed with the IRemoteControlDisplay
     *     registration.
     */
    private int checkRcdRegistrationAuthorization(ComponentName listenerComp) {
        // MEDIA_CONTENT_CONTROL permission check
        if (PackageManager.PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MEDIA_CONTENT_CONTROL)) {
            if (DEBUG_RC) { Log.d(TAG, "ok to register Rcd: has MEDIA_CONTENT_CONTROL permission");}
            return RCD_REG_SUCCESS_PERMISSION;
        }

        // ENABLED_NOTIFICATION_LISTENERS settings check
        if (listenerComp != null) {
            // this call is coming from an app, can't use its identity to read secure settings
            final long ident = Binder.clearCallingIdentity();
            try {
                final int currentUser = ActivityManager.getCurrentUser();
                final String enabledNotifListeners = Settings.Secure.getStringForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                        currentUser);
                if (enabledNotifListeners != null) {
                    final String[] components = enabledNotifListeners.split(":");
                    for (int i=0; i<components.length; i++) {
                        final ComponentName component =
                                ComponentName.unflattenFromString(components[i]);
                        if (component != null) {
                            if (listenerComp.equals(component)) {
                                if (DEBUG_RC) { Log.d(TAG, "ok to register RCC: " + component +
                                        " is authorized notification listener"); }
                                return RCD_REG_SUCCESS_ENABLED_NOTIF;
                            }
                        }
                    }
                }
                if (DEBUG_RC) { Log.d(TAG, "not ok to register RCD, " + listenerComp +
                        " is not in list of ENABLED_NOTIFICATION_LISTENERS"); }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        return RCD_REG_FAILURE;
    }

    protected boolean registerRemoteController(IRemoteControlDisplay rcd, int w, int h,
            ComponentName listenerComp) {
        int reg = checkRcdRegistrationAuthorization(listenerComp);
        if (reg != RCD_REG_FAILURE) {
            registerRemoteControlDisplay_int(rcd, w, h, listenerComp);
            return true;
        } else {
            Slog.w(TAG, "Access denied to process: " + Binder.getCallingPid() +
                    ", must have permission " + android.Manifest.permission.MEDIA_CONTENT_CONTROL +
                    " or be an enabled NotificationListenerService for registerRemoteController");
            return false;
        }
    }

    protected boolean registerRemoteControlDisplay(IRemoteControlDisplay rcd, int w, int h) {
        int reg = checkRcdRegistrationAuthorization(null);
        if (reg != RCD_REG_FAILURE) {
            registerRemoteControlDisplay_int(rcd, w, h, null);
            return true;
        } else {
            Slog.w(TAG, "Access denied to process: " + Binder.getCallingPid() +
                    ", must have permission " + android.Manifest.permission.MEDIA_CONTENT_CONTROL +
                    " to register IRemoteControlDisplay");
            return false;
        }
    }

    private void postReevaluateRemoteControlDisplays() {
        sendMsg(mEventHandler, MSG_REEVALUATE_RCD, SENDMSG_QUEUE, 0, 0, null, 0);
    }

    private void onReevaluateRemoteControlDisplays() {
        if (DEBUG_RC) { Log.d(TAG, "onReevaluateRemoteControlDisplays()"); }
        // read which components are enabled notification listeners
        final int currentUser = ActivityManager.getCurrentUser();
        final String enabledNotifListeners = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                currentUser);
        if (DEBUG_RC) { Log.d(TAG, " > enabled list: " + enabledNotifListeners); }
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                // check whether the "enable" status of each RCD with a notification listener
                // has changed
                final String[] enabledComponents;
                if (enabledNotifListeners == null) {
                    enabledComponents = null;
                } else {
                    enabledComponents = enabledNotifListeners.split(":");
                }
                final Iterator<DisplayInfoForServer> displayIterator = mRcDisplays.iterator();
                while (displayIterator.hasNext()) {
                    final DisplayInfoForServer di =
                            (DisplayInfoForServer) displayIterator.next();
                    if (di.mClientNotifListComp != null) {
                        boolean wasEnabled = di.mEnabled;
                        di.mEnabled = isComponentInStringArray(di.mClientNotifListComp,
                                enabledComponents);
                        if (wasEnabled != di.mEnabled){
                            try {
                                // tell the RCD whether it's enabled
                                di.mRcDisplay.setEnabled(di.mEnabled);
                                // tell the RCCs about the change for this RCD
                                enableRemoteControlDisplayForClient_syncRcStack(
                                        di.mRcDisplay, di.mEnabled);
                                // when enabling, refresh the information on the display
                                if (di.mEnabled) {
                                    sendMsg(mEventHandler, MSG_RCDISPLAY_INIT_INFO, SENDMSG_QUEUE,
                                            di.mArtworkExpectedWidth /*arg1*/,
                                            di.mArtworkExpectedHeight/*arg2*/,
                                            di.mRcDisplay /*obj*/, 0/*delay*/);
                                }
                            } catch (RemoteException e) {
                                Log.e(TAG, "Error en/disabling RCD: ", e);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * @param comp a non-null ComponentName
     * @param enabledArray may be null
     * @return
     */
    private boolean isComponentInStringArray(ComponentName comp, String[] enabledArray) {
        if (enabledArray == null || enabledArray.length == 0) {
            if (DEBUG_RC) { Log.d(TAG, " > " + comp + " is NOT enabled"); }
            return false;
        }
        final String compString = comp.flattenToString();
        for (int i=0; i<enabledArray.length; i++) {
            if (compString.equals(enabledArray[i])) {
                if (DEBUG_RC) { Log.d(TAG, " > " + compString + " is enabled"); }
                return true;
            }
        }
        if (DEBUG_RC) { Log.d(TAG, " > " + compString + " is NOT enabled"); }
        return false;
    }

    //==========================================================================================
    // Internal event handling
    //==========================================================================================

    // event handler messages
    private static final int MSG_RCDISPLAY_CLEAR = 1;
    private static final int MSG_RCDISPLAY_UPDATE = 2;
    private static final int MSG_REEVALUATE_REMOTE = 3;
    private static final int MSG_RCC_NEW_PLAYBACK_INFO = 4;
    private static final int MSG_RCC_NEW_VOLUME_OBS = 5;
    private static final int MSG_PROMOTE_RCC = 6;
    private static final int MSG_RCC_NEW_PLAYBACK_STATE = 7;
    private static final int MSG_RCC_SEEK_REQUEST = 8;
    private static final int MSG_RCC_UPDATE_METADATA = 9;
    private static final int MSG_RCDISPLAY_INIT_INFO = 10;
    private static final int MSG_REEVALUATE_RCD = 11;

    // sendMsg() flags
    /** If the msg is already queued, replace it with this one. */
    private static final int SENDMSG_REPLACE = 0;
    /** If the msg is already queued, ignore this one and leave the old. */
    private static final int SENDMSG_NOOP = 1;
    /** If the msg is already queued, queue this one and leave the old. */
    private static final int SENDMSG_QUEUE = 2;

    private static void sendMsg(Handler handler, int msg,
            int existingMsgPolicy, int arg1, int arg2, Object obj, int delay) {

        if (existingMsgPolicy == SENDMSG_REPLACE) {
            handler.removeMessages(msg);
        } else if (existingMsgPolicy == SENDMSG_NOOP && handler.hasMessages(msg)) {
            return;
        }

        handler.sendMessageDelayed(handler.obtainMessage(msg, arg1, arg2, obj), delay);
    }

    private class MediaEventHandler extends Handler {
        MediaEventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {

                case MSG_RCDISPLAY_CLEAR:
                    onRcDisplayClear();
                    break;

                case MSG_RCDISPLAY_UPDATE:
                    // msg.obj is guaranteed to be non null
                    onRcDisplayUpdate( (RemoteControlStackEntry) msg.obj, msg.arg1);
                    break;

                case MSG_REEVALUATE_REMOTE:
                    onReevaluateRemote();
                    break;

                case MSG_RCC_NEW_PLAYBACK_INFO:
                    onNewPlaybackInfoForRcc(msg.arg1 /* rccId */, msg.arg2 /* key */,
                            ((Integer)msg.obj).intValue() /* value */);
                    break;

                case MSG_RCC_NEW_VOLUME_OBS:
                    onRegisterVolumeObserverForRcc(msg.arg1 /* rccId */,
                            (IRemoteVolumeObserver)msg.obj /* rvo */);
                    break;

                case MSG_RCC_NEW_PLAYBACK_STATE:
                    onNewPlaybackStateForRcc(msg.arg1 /* rccId */,
                            msg.arg2 /* state */,
                            (RccPlaybackState)msg.obj /* newState */);
                    break;

                case MSG_RCC_SEEK_REQUEST:
                    onSetRemoteControlClientPlaybackPosition(
                            msg.arg1 /* generationId */, ((Long)msg.obj).longValue() /* timeMs */);
                    break;

                case MSG_RCC_UPDATE_METADATA:
                    onUpdateRemoteControlClientMetadata(msg.arg1 /*genId*/, msg.arg2 /*key*/,
                            (Rating) msg.obj /* value */);
                    break;

                case MSG_PROMOTE_RCC:
                    onPromoteRcc(msg.arg1);
                    break;

                case MSG_RCDISPLAY_INIT_INFO:
                    // msg.obj is guaranteed to be non null
                    onRcDisplayInitInfo((IRemoteControlDisplay)msg.obj /*newRcd*/,
                            msg.arg1/*w*/, msg.arg2/*h*/);
                    break;

                case MSG_REEVALUATE_RCD:
                    onReevaluateRemoteControlDisplays();
                    break;
            }
        }
    }


    //==========================================================================================
    // AudioFocus
    //==========================================================================================

    /* constant to identify focus stack entry that is used to hold the focus while the phone
     * is ringing or during a call. Used by com.android.internal.telephony.CallManager when
     * entering and exiting calls.
     */
    protected final static String IN_VOICE_COMM_FOCUS_ID = "AudioFocus_For_Phone_Ring_And_Calls";

    private final static Object mAudioFocusLock = new Object();

    private final static Object mRingingLock = new Object();

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                //Log.v(TAG, " CALL_STATE_RINGING");
                synchronized(mRingingLock) {
                    mIsRinging = true;
                }
            } else if ((state == TelephonyManager.CALL_STATE_OFFHOOK)
                    || (state == TelephonyManager.CALL_STATE_IDLE)) {
                synchronized(mRingingLock) {
                    mIsRinging = false;
                }
            }
        }
    };

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
                // clear RCD
                synchronized(mRCStack) {
                    clearRemoteControlDisplay_syncAfRcs();
                }
            }
        }
    }

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
    }

    /**
     * Helper function:
     * Called synchronized on mAudioFocusLock
     * Remove a focus listener from the focus stack.
     * @param clientToRemove the focus listener
     * @param signal if true and the listener was at the top of the focus stack, i.e. it was holding
     *   focus, notify the next item in the stack it gained focus.
     */
    private void removeFocusStackEntry(String clientToRemove, boolean signal) {
        // is the current top of the focus stack abandoning focus? (because of request, not death)
        if (!mFocusStack.empty() && mFocusStack.peek().hasSameClient(clientToRemove))
        {
            //Log.i(TAG, "   removeFocusStackEntry() removing top of stack");
            FocusRequester fr = mFocusStack.pop();
            fr.release();
            if (signal) {
                // notify the new top of the stack it gained focus
                notifyTopOfAudioFocusStack();
                // there's a new top of the stack, let the remote control know
                synchronized(mRCStack) {
                    checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
                }
            }
        } else {
            // focus is abandoned by a client that's not at the top of the stack,
            // no need to update focus.
            // (using an iterator on the stack so we can safely remove an entry after having
            //  evaluated it, traversal order doesn't matter here)
            Iterator<FocusRequester> stackIterator = mFocusStack.iterator();
            while(stackIterator.hasNext()) {
                FocusRequester fr = (FocusRequester)stackIterator.next();
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
            FocusRequester fr = (FocusRequester)stackIterator.next();
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
            // there's a new top of the stack, let the remote control know
            synchronized(mRCStack) {
                checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
            }
        }
    }

    /**
     * Helper function:
     * Returns true if the system is in a state where the focus can be reevaluated, false otherwise.
     */
    private boolean canReassignAudioFocus() {
        // focus requests are rejected during a phone call or when the phone is ringing
        // this is equivalent to IN_VOICE_COMM_FOCUS_ID having the focus
        if (!mFocusStack.isEmpty() && mFocusStack.peek().hasSameClient(IN_VOICE_COMM_FOCUS_ID)) {
            return false;
        }
        return true;
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

    protected int getCurrentAudioFocus() {
        synchronized(mAudioFocusLock) {
            if (mFocusStack.empty()) {
                return AudioManager.AUDIOFOCUS_NONE;
            } else {
                return mFocusStack.peek().getGainRequest();
            }
        }
    }

    /** @see AudioManager#requestAudioFocus(AudioManager.OnAudioFocusChangeListener, int, int)  */
    protected int requestAudioFocus(int mainStreamType, int focusChangeHint, IBinder cb,
            IAudioFocusDispatcher fd, String clientId, String callingPackageName) {
        Log.i(TAG, " AudioFocus  requestAudioFocus() from " + clientId);
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
            if (!canReassignAudioFocus()) {
                return AudioManager.AUDIOFOCUS_REQUEST_FAILED;
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
                if (mFocusStack.peek().getGainRequest() == focusChangeHint) {
                    // unlink death handler so it can be gc'ed.
                    // linkToDeath() creates a JNI global reference preventing collection.
                    cb.unlinkToDeath(afdh, 0);
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
                // the reason for the audio focus request has changed: remove the current top of
                // stack and respond as if we had a new focus owner
                FocusRequester fr = mFocusStack.pop();
                fr.release();
            }

            // focus requester might already be somewhere below in the stack, remove it
            removeFocusStackEntry(clientId, false /* signal */);

            // propagate the focus change through the stack
            if (!mFocusStack.empty()) {
                propagateFocusLossFromGain_syncAf(focusChangeHint);
            }

            // push focus requester at the top of the audio focus stack
            mFocusStack.push(new FocusRequester(mainStreamType, focusChangeHint, fd, cb,
                    clientId, afdh, callingPackageName, Binder.getCallingUid()));

            // there's a new top of the stack, let the remote control know
            synchronized(mRCStack) {
                checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
            }
        }//synchronized(mAudioFocusLock)

        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** @see AudioManager#abandonAudioFocus(AudioManager.OnAudioFocusChangeListener)  */
    protected int abandonAudioFocus(IAudioFocusDispatcher fl, String clientId) {
        Log.i(TAG, " AudioFocus  abandonAudioFocus() from " + clientId);
        try {
            // this will take care of notifying the new focus owner if needed
            synchronized(mAudioFocusLock) {
                removeFocusStackEntry(clientId, true /*signal*/);
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
            removeFocusStackEntry(clientId, false);
        }
    }


    //==========================================================================================
    // RemoteControl
    //==========================================================================================
    /**
     * No-op if the key code for keyEvent is not a valid media key
     * (see {@link #isValidMediaKeyEvent(KeyEvent)})
     * @param keyEvent the key event to send
     */
    protected void dispatchMediaKeyEvent(KeyEvent keyEvent) {
        filterMediaKeyEvent(keyEvent, false /*needWakeLock*/);
    }

    /**
     * No-op if the key code for keyEvent is not a valid media key
     * (see {@link #isValidMediaKeyEvent(KeyEvent)})
     * @param keyEvent the key event to send
     */
    protected void dispatchMediaKeyEventUnderWakelock(KeyEvent keyEvent) {
        filterMediaKeyEvent(keyEvent, true /*needWakeLock*/);
    }

    private void filterMediaKeyEvent(KeyEvent keyEvent, boolean needWakeLock) {
        // sanity check on the incoming key event
        if (!isValidMediaKeyEvent(keyEvent)) {
            Log.e(TAG, "not dispatching invalid media key event " + keyEvent);
            return;
        }
        // event filtering for telephony
        synchronized(mRingingLock) {
            synchronized(mRCStack) {
                if ((mMediaReceiverForCalls != null) &&
                        (mIsRinging || (mAudioService.getMode() == AudioSystem.MODE_IN_CALL))) {
                    dispatchMediaKeyEventForCalls(keyEvent, needWakeLock);
                    return;
                }
            }
        }
        // event filtering based on voice-based interactions
        if (isValidVoiceInputKeyCode(keyEvent.getKeyCode())) {
            filterVoiceInputKeyEvent(keyEvent, needWakeLock);
        } else {
            dispatchMediaKeyEvent(keyEvent, needWakeLock);
        }
    }

    /**
     * Handles the dispatching of the media button events to the telephony package.
     * Precondition: mMediaReceiverForCalls != null
     * @param keyEvent a non-null KeyEvent whose key code is one of the supported media buttons
     * @param needWakeLock true if a PARTIAL_WAKE_LOCK needs to be held while this key event
     *     is dispatched.
     */
    private void dispatchMediaKeyEventForCalls(KeyEvent keyEvent, boolean needWakeLock) {
        Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        keyIntent.setPackage(mMediaReceiverForCalls.getPackageName());
        if (needWakeLock) {
            mMediaEventWakeLock.acquire();
            keyIntent.putExtra(EXTRA_WAKELOCK_ACQUIRED, WAKELOCK_RELEASE_ON_FINISHED);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendOrderedBroadcastAsUser(keyIntent, UserHandle.ALL,
                    null, mKeyEventDone, mEventHandler, Activity.RESULT_OK, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Handles the dispatching of the media button events to one of the registered listeners,
     * or if there was none, broadcast an ACTION_MEDIA_BUTTON intent to the rest of the system.
     * @param keyEvent a non-null KeyEvent whose key code is one of the supported media buttons
     * @param needWakeLock true if a PARTIAL_WAKE_LOCK needs to be held while this key event
     *     is dispatched.
     */
    private void dispatchMediaKeyEvent(KeyEvent keyEvent, boolean needWakeLock) {
        if (needWakeLock) {
            mMediaEventWakeLock.acquire();
        }
        Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
        keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        synchronized(mRCStack) {
            if (!mRCStack.empty()) {
                // send the intent that was registered by the client
                try {
                    mRCStack.peek().mMediaIntent.send(mContext,
                            needWakeLock ? WAKELOCK_RELEASE_ON_FINISHED : 0 /*code*/,
                            keyIntent, this, mEventHandler);
                } catch (CanceledException e) {
                    Log.e(TAG, "Error sending pending intent " + mRCStack.peek());
                    e.printStackTrace();
                }
            } else {
                // legacy behavior when nobody registered their media button event receiver
                //    through AudioManager
                if (needWakeLock) {
                    keyIntent.putExtra(EXTRA_WAKELOCK_ACQUIRED, WAKELOCK_RELEASE_ON_FINISHED);
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    mContext.sendOrderedBroadcastAsUser(keyIntent, UserHandle.ALL,
                            null, mKeyEventDone,
                            mEventHandler, Activity.RESULT_OK, null, null);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    /**
     * The different actions performed in response to a voice button key event.
     */
    private final static int VOICEBUTTON_ACTION_DISCARD_CURRENT_KEY_PRESS = 1;
    private final static int VOICEBUTTON_ACTION_START_VOICE_INPUT = 2;
    private final static int VOICEBUTTON_ACTION_SIMULATE_KEY_PRESS = 3;

    private final Object mVoiceEventLock = new Object();
    private boolean mVoiceButtonDown;
    private boolean mVoiceButtonHandled;

    /**
     * Filter key events that may be used for voice-based interactions
     * @param keyEvent a non-null KeyEvent whose key code is that of one of the supported
     *    media buttons that can be used to trigger voice-based interactions.
     * @param needWakeLock true if a PARTIAL_WAKE_LOCK needs to be held while this key event
     *     is dispatched.
     */
    private void filterVoiceInputKeyEvent(KeyEvent keyEvent, boolean needWakeLock) {
        if (DEBUG_RC) {
            Log.v(TAG, "voice input key event: " + keyEvent + ", needWakeLock=" + needWakeLock);
        }

        int voiceButtonAction = VOICEBUTTON_ACTION_DISCARD_CURRENT_KEY_PRESS;
        int keyAction = keyEvent.getAction();
        synchronized (mVoiceEventLock) {
            if (keyAction == KeyEvent.ACTION_DOWN) {
                if (keyEvent.getRepeatCount() == 0) {
                    // initial down
                    mVoiceButtonDown = true;
                    mVoiceButtonHandled = false;
                } else if (mVoiceButtonDown && !mVoiceButtonHandled
                        && (keyEvent.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                    // long-press, start voice-based interactions
                    mVoiceButtonHandled = true;
                    voiceButtonAction = VOICEBUTTON_ACTION_START_VOICE_INPUT;
                }
            } else if (keyAction == KeyEvent.ACTION_UP) {
                if (mVoiceButtonDown) {
                    // voice button up
                    mVoiceButtonDown = false;
                    if (!mVoiceButtonHandled && !keyEvent.isCanceled()) {
                        voiceButtonAction = VOICEBUTTON_ACTION_SIMULATE_KEY_PRESS;
                    }
                }
            }
        }//synchronized (mVoiceEventLock)

        // take action after media button event filtering for voice-based interactions
        switch (voiceButtonAction) {
            case VOICEBUTTON_ACTION_DISCARD_CURRENT_KEY_PRESS:
                if (DEBUG_RC) Log.v(TAG, "   ignore key event");
                break;
            case VOICEBUTTON_ACTION_START_VOICE_INPUT:
                if (DEBUG_RC) Log.v(TAG, "   start voice-based interactions");
                // then start the voice-based interactions
                startVoiceBasedInteractions(needWakeLock);
                break;
            case VOICEBUTTON_ACTION_SIMULATE_KEY_PRESS:
                if (DEBUG_RC) Log.v(TAG, "   send simulated key event, wakelock=" + needWakeLock);
                sendSimulatedMediaButtonEvent(keyEvent, needWakeLock);
                break;
        }
    }

    private void sendSimulatedMediaButtonEvent(KeyEvent originalKeyEvent, boolean needWakeLock) {
        // send DOWN event
        KeyEvent keyEvent = KeyEvent.changeAction(originalKeyEvent, KeyEvent.ACTION_DOWN);
        dispatchMediaKeyEvent(keyEvent, needWakeLock);
        // send UP event
        keyEvent = KeyEvent.changeAction(originalKeyEvent, KeyEvent.ACTION_UP);
        dispatchMediaKeyEvent(keyEvent, needWakeLock);

    }

    private class PackageIntentsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_PACKAGE_REMOVED)
                    || action.equals(Intent.ACTION_PACKAGE_DATA_CLEARED)) {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // a package is being removed, not replaced
                    String packageName = intent.getData().getSchemeSpecificPart();
                    if (packageName != null) {
                        cleanupMediaButtonReceiverForPackage(packageName, true);
                    }
                }
            } else if (action.equals(Intent.ACTION_PACKAGE_ADDED)
                    || action.equals(Intent.ACTION_PACKAGE_CHANGED)) {
                String packageName = intent.getData().getSchemeSpecificPart();
                if (packageName != null) {
                    cleanupMediaButtonReceiverForPackage(packageName, false);
                }
            }
        }
    }

    protected static boolean isMediaKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_CLOSE:
            case KeyEvent.KEYCODE_MEDIA_EJECT:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidMediaKeyEvent(KeyEvent keyEvent) {
        if (keyEvent == null) {
            return false;
        }
        return MediaFocusControl.isMediaKeyCode(keyEvent.getKeyCode());
    }

    /**
     * Checks whether the given key code is one that can trigger the launch of voice-based
     *   interactions.
     * @param keyCode the key code associated with the key event
     * @return true if the key is one of the supported voice-based interaction triggers
     */
    private static boolean isValidVoiceInputKeyCode(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Tell the system to start voice-based interactions / voice commands
     */
    private void startVoiceBasedInteractions(boolean needWakeLock) {
        Intent voiceIntent = null;
        // select which type of search to launch:
        // - screen on and device unlocked: action is ACTION_WEB_SEARCH
        // - device locked or screen off: action is ACTION_VOICE_SEARCH_HANDS_FREE
        //    with EXTRA_SECURE set to true if the device is securely locked
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        boolean isLocked = mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        if (!isLocked && pm.isScreenOn()) {
            voiceIntent = new Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH);
            Log.i(TAG, "voice-based interactions: about to use ACTION_WEB_SEARCH");
        } else {
            voiceIntent = new Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE);
            voiceIntent.putExtra(RecognizerIntent.EXTRA_SECURE,
                    isLocked && mKeyguardManager.isKeyguardSecure());
            Log.i(TAG, "voice-based interactions: about to use ACTION_VOICE_SEARCH_HANDS_FREE");
        }
        // start the search activity
        if (needWakeLock) {
            mMediaEventWakeLock.acquire();
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            if (voiceIntent != null) {
                voiceIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                mContext.startActivityAsUser(voiceIntent, UserHandle.CURRENT);
            }
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No activity for search: " + e);
        } finally {
            Binder.restoreCallingIdentity(identity);
            if (needWakeLock) {
                mMediaEventWakeLock.release();
            }
        }
    }

    private static final int WAKELOCK_RELEASE_ON_FINISHED = 1980; //magic number

    // only set when wakelock was acquired, no need to check value when received
    private static final String EXTRA_WAKELOCK_ACQUIRED =
            "android.media.AudioService.WAKELOCK_ACQUIRED";

    public void onSendFinished(PendingIntent pendingIntent, Intent intent,
            int resultCode, String resultData, Bundle resultExtras) {
        if (resultCode == WAKELOCK_RELEASE_ON_FINISHED) {
            mMediaEventWakeLock.release();
        }
    }

    BroadcastReceiver mKeyEventDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            Bundle extras = intent.getExtras();
            if (extras == null) {
                return;
            }
            if (extras.containsKey(EXTRA_WAKELOCK_ACQUIRED)) {
                mMediaEventWakeLock.release();
            }
        }
    };

    /**
     * Synchronization on mCurrentRcLock always inside a block synchronized on mRCStack
     */
    private final Object mCurrentRcLock = new Object();
    /**
     * The one remote control client which will receive a request for display information.
     * This object may be null.
     * Access protected by mCurrentRcLock.
     */
    private IRemoteControlClient mCurrentRcClient = null;
    /**
     * The PendingIntent associated with mCurrentRcClient. Its value is irrelevant
     * if mCurrentRcClient is null
     */
    private PendingIntent mCurrentRcClientIntent = null;

    private final static int RC_INFO_NONE = 0;
    private final static int RC_INFO_ALL =
        RemoteControlClient.FLAG_INFORMATION_REQUEST_ALBUM_ART |
        RemoteControlClient.FLAG_INFORMATION_REQUEST_KEY_MEDIA |
        RemoteControlClient.FLAG_INFORMATION_REQUEST_METADATA |
        RemoteControlClient.FLAG_INFORMATION_REQUEST_PLAYSTATE;

    /**
     * A monotonically increasing generation counter for mCurrentRcClient.
     * Only accessed with a lock on mCurrentRcLock.
     * No value wrap-around issues as we only act on equal values.
     */
    private int mCurrentRcClientGen = 0;

    /**
     * Inner class to monitor remote control client deaths, and remove the client for the
     * remote control stack if necessary.
     */
    private class RcClientDeathHandler implements IBinder.DeathRecipient {
        final private IBinder mCb; // To be notified of client's death
        final private PendingIntent mMediaIntent;

        RcClientDeathHandler(IBinder cb, PendingIntent pi) {
            mCb = cb;
            mMediaIntent = pi;
        }

        public void binderDied() {
            Log.w(TAG, "  RemoteControlClient died");
            // remote control client died, make sure the displays don't use it anymore
            //  by setting its remote control client to null
            registerRemoteControlClient(mMediaIntent, null/*rcClient*/, null/*ignored*/);
            // the dead client was maybe handling remote playback, reevaluate
            postReevaluateRemote();
        }

        public IBinder getBinder() {
            return mCb;
        }
    }

    /**
     * A global counter for RemoteControlClient identifiers
     */
    private static int sLastRccId = 0;

    private class RemotePlaybackState {
        int mRccId;
        int mVolume;
        int mVolumeMax;
        int mVolumeHandling;

        private RemotePlaybackState(int id, int vol, int volMax) {
            mRccId = id;
            mVolume = vol;
            mVolumeMax = volMax;
            mVolumeHandling = RemoteControlClient.DEFAULT_PLAYBACK_VOLUME_HANDLING;
        }
    }

    /**
     * Internal cache for the playback information of the RemoteControlClient whose volume gets to
     * be controlled by the volume keys ("main"), so we don't have to iterate over the RC stack
     * every time we need this info.
     */
    private RemotePlaybackState mMainRemote;
    /**
     * Indicates whether the "main" RemoteControlClient is considered active.
     * Use synchronized on mMainRemote.
     */
    private boolean mMainRemoteIsActive;
    /**
     * Indicates whether there is remote playback going on. True even if there is no "active"
     * remote playback (mMainRemoteIsActive is false), but a RemoteControlClient has declared it
     * handles remote playback.
     * Use synchronized on mMainRemote.
     */
    private boolean mHasRemotePlayback;

    private static class RccPlaybackState {
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

    protected static class RemoteControlStackEntry implements DeathRecipient {
        public int mRccId = RemoteControlClient.RCSE_ID_UNREGISTERED;
        final public MediaFocusControl mController;
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
        public RemoteControlStackEntry(MediaFocusControl controller, PendingIntent mediaIntent,
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
                    Log.e(TAG, "Encountered " + e + " in unlinkToRcClientDeath()");
                    e.printStackTrace();
                }
            }
        }

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

    /**
     *  The stack of remote control event receivers.
     *  Code sections and methods that modify the remote control event receiver stack are
     *  synchronized on mRCStack, but also BEFORE on mFocusLock as any change in either
     *  stack, audio focus or RC, can lead to a change in the remote control display
     */
    private final Stack<RemoteControlStackEntry> mRCStack = new Stack<RemoteControlStackEntry>();

    /**
     * The component the telephony package can register so telephony calls have priority to
     * handle media button events
     */
    private ComponentName mMediaReceiverForCalls = null;

    /**
     * Helper function:
     * Display in the log the current entries in the remote control focus stack
     */
    private void dumpRCStack(PrintWriter pw) {
        pw.println("\nRemote Control stack entries (last is top of stack):");
        synchronized(mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                pw.println("  pi: " + rcse.mMediaIntent +
                        " -- pack: " + rcse.mCallingPackageName +
                        "  -- ercvr: " + rcse.mReceiverComponent +
                        "  -- client: " + rcse.mRcClient +
                        "  -- uid: " + rcse.mCallingUid +
                        "  -- type: " + rcse.mPlaybackType +
                        "  state: " + rcse.mPlaybackState);
            }
        }
    }

    /**
     * Helper function:
     * Display in the log the current entries in the remote control stack, focusing
     * on RemoteControlClient data
     */
    private void dumpRCCStack(PrintWriter pw) {
        pw.println("\nRemote Control Client stack entries (last is top of stack):");
        synchronized(mRCStack) {
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                pw.println("  uid: " + rcse.mCallingUid +
                        "  -- id: " + rcse.mRccId +
                        "  -- type: " + rcse.mPlaybackType +
                        "  -- state: " + rcse.mPlaybackState +
                        "  -- vol handling: " + rcse.mPlaybackVolumeHandling +
                        "  -- vol: " + rcse.mPlaybackVolume +
                        "  -- volMax: " + rcse.mPlaybackVolumeMax +
                        "  -- volObs: " + rcse.mRemoteVolumeObs);
            }
            synchronized(mCurrentRcLock) {
                pw.println("\nCurrent remote control generation ID = " + mCurrentRcClientGen);
            }
        }
        synchronized (mMainRemote) {
            pw.println("\nRemote Volume State:");
            pw.println("  has remote: " + mHasRemotePlayback);
            pw.println("  is remote active: " + mMainRemoteIsActive);
            pw.println("  rccId: " + mMainRemote.mRccId);
            pw.println("  volume handling: "
                    + ((mMainRemote.mVolumeHandling == RemoteControlClient.PLAYBACK_VOLUME_FIXED) ?
                            "PLAYBACK_VOLUME_FIXED(0)" : "PLAYBACK_VOLUME_VARIABLE(1)"));
            pw.println("  volume: " + mMainRemote.mVolume);
            pw.println("  volume steps: " + mMainRemote.mVolumeMax);
        }
    }

    /**
     * Helper function:
     * Display in the log the current entries in the list of remote control displays
     */
    private void dumpRCDList(PrintWriter pw) {
        pw.println("\nRemote Control Display list entries:");
        synchronized(mRCStack) {
            final Iterator<DisplayInfoForServer> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForServer di = (DisplayInfoForServer) displayIterator.next();
                pw.println("  IRCD: " + di.mRcDisplay +
                        "  -- w:" + di.mArtworkExpectedWidth +
                        "  -- h:" + di.mArtworkExpectedHeight +
                        "  -- wantsPosSync:" + di.mWantsPositionSync +
                        "  -- " + (di.mEnabled ? "enabled" : "disabled"));
            }
        }
    }

    /**
     * Helper function:
     * Remove any entry in the remote control stack that has the same package name as packageName
     * Pre-condition: packageName != null
     */
    private void cleanupMediaButtonReceiverForPackage(String packageName, boolean removeAll) {
        synchronized(mRCStack) {
            if (mRCStack.empty()) {
                return;
            } else {
                final PackageManager pm = mContext.getPackageManager();
                RemoteControlStackEntry oldTop = mRCStack.peek();
                Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
                // iterate over the stack entries
                // (using an iterator on the stack so we can safely remove an entry after having
                //  evaluated it, traversal order doesn't matter here)
                while(stackIterator.hasNext()) {
                    RemoteControlStackEntry rcse = (RemoteControlStackEntry)stackIterator.next();
                    if (removeAll && packageName.equals(rcse.mMediaIntent.getCreatorPackage())) {
                        // a stack entry is from the package being removed, remove it from the stack
                        stackIterator.remove();
                        rcse.destroy();
                    } else if (rcse.mReceiverComponent != null) {
                        try {
                            // Check to see if this receiver still exists.
                            pm.getReceiverInfo(rcse.mReceiverComponent, 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            // Not found -- remove it!
                            stackIterator.remove();
                            rcse.destroy();
                        }
                    }
                }
            }
        }
    }

    /**
     * Helper function:
     * Set the new remote control receiver at the top of the RC focus stack.
     * Called synchronized on mAudioFocusLock, then mRCStack
     * precondition: mediaIntent != null
     * @return true if mRCStack was changed, false otherwise
     */
    private boolean pushMediaButtonReceiver_syncAfRcs(PendingIntent mediaIntent,
            ComponentName target, IBinder token) {
        // already at top of stack?
        if (!mRCStack.empty() && mRCStack.peek().mMediaIntent.equals(mediaIntent)) {
            return false;
        }
        if (mAppOps.noteOp(AppOpsManager.OP_TAKE_MEDIA_BUTTONS, Binder.getCallingUid(),
                mediaIntent.getCreatorPackage()) != AppOpsManager.MODE_ALLOWED) {
            return false;
        }
        RemoteControlStackEntry rcse = null;
        boolean wasInsideStack = false;
        try {
            for (int index = mRCStack.size()-1; index >= 0; index--) {
                rcse = mRCStack.elementAt(index);
                if(rcse.mMediaIntent.equals(mediaIntent)) {
                    // ok to remove element while traversing the stack since we're leaving the loop
                    mRCStack.removeElementAt(index);
                    wasInsideStack = true;
                    break;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            // not expected to happen, indicates improper concurrent modification
            Log.e(TAG, "Wrong index accessing media button stack, lock error? ", e);
        }
        if (!wasInsideStack) {
            rcse = new RemoteControlStackEntry(this, mediaIntent, target, token);
        }
        mRCStack.push(rcse); // rcse is never null

        // RC stack was modified
        return true;
    }

    /**
     * Helper function:
     * Remove the remote control receiver from the RC focus stack.
     * Called synchronized on mAudioFocusLock, then mRCStack
     * precondition: pi != null
     */
    private void removeMediaButtonReceiver_syncAfRcs(PendingIntent pi) {
        try {
            for (int index = mRCStack.size()-1; index >= 0; index--) {
                final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                if (rcse.mMediaIntent.equals(pi)) {
                    rcse.destroy();
                    // ok to remove element while traversing the stack since we're leaving the loop
                    mRCStack.removeElementAt(index);
                    break;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            // not expected to happen, indicates improper concurrent modification
            Log.e(TAG, "Wrong index accessing media button stack, lock error? ", e);
        }
    }

    /**
     * Helper function:
     * Called synchronized on mRCStack
     */
    private boolean isCurrentRcController(PendingIntent pi) {
        if (!mRCStack.empty() && mRCStack.peek().mMediaIntent.equals(pi)) {
            return true;
        }
        return false;
    }


    //==========================================================================================
    // Remote control display / client
    //==========================================================================================
    /**
     * Update the remote control displays with the new "focused" client generation
     */
    private void setNewRcClientOnDisplays_syncRcsCurrc(int newClientGeneration,
            PendingIntent newMediaIntent, boolean clearing) {
        synchronized(mRCStack) {
            if (mRcDisplays.size() > 0) {
                final Iterator<DisplayInfoForServer> displayIterator = mRcDisplays.iterator();
                while (displayIterator.hasNext()) {
                    final DisplayInfoForServer di = displayIterator.next();
                    try {
                        di.mRcDisplay.setCurrentClientId(
                                newClientGeneration, newMediaIntent, clearing);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Dead display in setNewRcClientOnDisplays_syncRcsCurrc()",e);
                        di.release();
                        displayIterator.remove();
                    }
                }
            }
        }
    }

    /**
     * Update the remote control clients with the new "focused" client generation
     */
    private void setNewRcClientGenerationOnClients_syncRcsCurrc(int newClientGeneration) {
        // (using an iterator on the stack so we can safely remove an entry if needed,
        //  traversal order doesn't matter here as we update all entries)
        Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
        while(stackIterator.hasNext()) {
            RemoteControlStackEntry se = stackIterator.next();
            if ((se != null) && (se.mRcClient != null)) {
                try {
                    se.mRcClient.setCurrentClientGenerationId(newClientGeneration);
                } catch (RemoteException e) {
                    Log.w(TAG, "Dead client in setNewRcClientGenerationOnClients_syncRcsCurrc()",e);
                    stackIterator.remove();
                    se.unlinkToRcClientDeath();
                }
            }
        }
    }

    /**
     * Update the displays and clients with the new "focused" client generation and name
     * @param newClientGeneration the new generation value matching a client update
     * @param newMediaIntent the media button event receiver associated with the client.
     *    May be null, which implies there is no registered media button event receiver.
     * @param clearing true if the new client generation value maps to a remote control update
     *    where the display should be cleared.
     */
    private void setNewRcClient_syncRcsCurrc(int newClientGeneration,
            PendingIntent newMediaIntent, boolean clearing) {
        // send the new valid client generation ID to all displays
        setNewRcClientOnDisplays_syncRcsCurrc(newClientGeneration, newMediaIntent, clearing);
        // send the new valid client generation ID to all clients
        setNewRcClientGenerationOnClients_syncRcsCurrc(newClientGeneration);
    }

    /**
     * Called when processing MSG_RCDISPLAY_CLEAR event
     */
    private void onRcDisplayClear() {
        if (DEBUG_RC) Log.i(TAG, "Clear remote control display");

        synchronized(mRCStack) {
            synchronized(mCurrentRcLock) {
                mCurrentRcClientGen++;
                // synchronously update the displays and clients with the new client generation
                setNewRcClient_syncRcsCurrc(mCurrentRcClientGen,
                        null /*newMediaIntent*/, true /*clearing*/);
            }
        }
    }

    /**
     * Called when processing MSG_RCDISPLAY_UPDATE event
     */
    private void onRcDisplayUpdate(RemoteControlStackEntry rcse, int flags /* USED ?*/) {
        synchronized(mRCStack) {
            synchronized(mCurrentRcLock) {
                if ((mCurrentRcClient != null) && (mCurrentRcClient.equals(rcse.mRcClient))) {
                    if (DEBUG_RC) Log.i(TAG, "Display/update remote control ");

                    mCurrentRcClientGen++;
                    // synchronously update the displays and clients with
                    //      the new client generation
                    setNewRcClient_syncRcsCurrc(mCurrentRcClientGen,
                            rcse.mMediaIntent /*newMediaIntent*/,
                            false /*clearing*/);

                    // tell the current client that it needs to send info
                    try {
                        //TODO change name to informationRequestForAllDisplays()
                        mCurrentRcClient.onInformationRequested(mCurrentRcClientGen, flags);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Current valid remote client is dead: "+e);
                        mCurrentRcClient = null;
                    }
                } else {
                    // the remote control display owner has changed between the
                    // the message to update the display was sent, and the time it
                    // gets to be processed (now)
                }
            }
        }
    }

    /**
     * Called when processing MSG_RCDISPLAY_INIT_INFO event
     * Causes the current RemoteControlClient to send its info (metadata, playstate...) to
     *   a single RemoteControlDisplay, NOT all of them, as with MSG_RCDISPLAY_UPDATE.
     */
    private void onRcDisplayInitInfo(IRemoteControlDisplay newRcd, int w, int h) {
        synchronized(mRCStack) {
            synchronized(mCurrentRcLock) {
                if (mCurrentRcClient != null) {
                    if (DEBUG_RC) { Log.i(TAG, "Init RCD with current info"); }
                    try {
                        // synchronously update the new RCD with the current client generation
                        // and matching PendingIntent
                        newRcd.setCurrentClientId(mCurrentRcClientGen, mCurrentRcClientIntent,
                                false);

                        // tell the current RCC that it needs to send info, but only to the new RCD
                        try {
                            mCurrentRcClient.informationRequestForDisplay(newRcd, w, h);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Current valid remote client is dead: ", e);
                            mCurrentRcClient = null;
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Dead display in onRcDisplayInitInfo()", e);
                    }
                }
            }
        }
    }

    /**
     * Helper function:
     * Called synchronized on mRCStack
     */
    private void clearRemoteControlDisplay_syncAfRcs() {
        synchronized(mCurrentRcLock) {
            mCurrentRcClient = null;
        }
        // will cause onRcDisplayClear() to be called in AudioService's handler thread
        mEventHandler.sendMessage( mEventHandler.obtainMessage(MSG_RCDISPLAY_CLEAR) );
    }

    /**
     * Helper function for code readability: only to be called from
     *    checkUpdateRemoteControlDisplay_syncAfRcs() which checks the preconditions for
     *    this method.
     * Preconditions:
     *    - called synchronized mAudioFocusLock then on mRCStack
     *    - mRCStack.isEmpty() is false
     */
    private void updateRemoteControlDisplay_syncAfRcs(int infoChangedFlags) {
        RemoteControlStackEntry rcse = mRCStack.peek();
        int infoFlagsAboutToBeUsed = infoChangedFlags;
        // this is where we enforce opt-in for information display on the remote controls
        //   with the new AudioManager.registerRemoteControlClient() API
        if (rcse.mRcClient == null) {
            //Log.w(TAG, "Can't update remote control display with null remote control client");
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }
        synchronized(mCurrentRcLock) {
            if (!rcse.mRcClient.equals(mCurrentRcClient)) {
                // new RC client, assume every type of information shall be queried
                infoFlagsAboutToBeUsed = RC_INFO_ALL;
            }
            mCurrentRcClient = rcse.mRcClient;
            mCurrentRcClientIntent = rcse.mMediaIntent;
        }
        // will cause onRcDisplayUpdate() to be called in AudioService's handler thread
        mEventHandler.sendMessage( mEventHandler.obtainMessage(MSG_RCDISPLAY_UPDATE,
                infoFlagsAboutToBeUsed /* arg1 */, 0, rcse /* obj, != null */) );
    }

    /**
     * Helper function:
     * Called synchronized on mAudioFocusLock, then mRCStack
     * Check whether the remote control display should be updated, triggers the update if required
     * @param infoChangedFlags the flags corresponding to the remote control client information
     *     that has changed, if applicable (checking for the update conditions might trigger a
     *     clear, rather than an update event).
     */
    private void checkUpdateRemoteControlDisplay_syncAfRcs(int infoChangedFlags) {
        // determine whether the remote control display should be refreshed
        // if either stack is empty, there is a mismatch, so clear the RC display
        if (mRCStack.isEmpty() || mFocusStack.isEmpty()) {
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }

        // determine which entry in the AudioFocus stack to consider, and compare against the
        // top of the stack for the media button event receivers : simply using the top of the
        // stack would make the entry disappear from the RemoteControlDisplay in conditions such as
        // notifications playing during music playback.
        // Crawl the AudioFocus stack from the top until an entry is found with the following
        // characteristics:
        // - focus gain on STREAM_MUSIC stream
        // - non-transient focus gain on a stream other than music
        FocusRequester af = null;
        try {
            for (int index = mFocusStack.size()-1; index >= 0; index--) {
                FocusRequester fr = mFocusStack.elementAt(index);
                if ((fr.getStreamType() == AudioManager.STREAM_MUSIC)
                        || (fr.getGainRequest() == AudioManager.AUDIOFOCUS_GAIN)) {
                    af = fr;
                    break;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            Log.e(TAG, "Wrong index accessing audio focus stack when updating RCD: " + e);
            af = null;
        }
        if (af == null) {
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }

        // if the audio focus and RC owners belong to different packages, there is a mismatch, clear
        if (!af.hasSamePackage(mRCStack.peek().mCallingPackageName)) {
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }
        // if the audio focus didn't originate from the same Uid as the one in which the remote
        //   control information will be retrieved, clear
        if (!af.hasSameUid(mRCStack.peek().mCallingUid)) {
            clearRemoteControlDisplay_syncAfRcs();
            return;
        }

        // refresh conditions were verified: update the remote controls
        // ok to call: synchronized mAudioFocusLock then on mRCStack, mRCStack is not empty
        updateRemoteControlDisplay_syncAfRcs(infoChangedFlags);
    }

    /**
     * Helper function:
     * Post a message to asynchronously move the media button event receiver associated with the
     * given remote control client ID to the top of the remote control stack
     * @param rccId
     */
    private void postPromoteRcc(int rccId) {
        sendMsg(mEventHandler, MSG_PROMOTE_RCC, SENDMSG_REPLACE,
                rccId /*arg1*/, 0, null, 0/*delay*/);
    }

    private void onPromoteRcc(int rccId) {
        if (DEBUG_RC) { Log.d(TAG, "Promoting RCC " + rccId); }
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                // ignore if given RCC ID is already at top of remote control stack
                if (!mRCStack.isEmpty() && (mRCStack.peek().mRccId == rccId)) {
                    return;
                }
                int indexToPromote = -1;
                try {
                    for (int index = mRCStack.size()-1; index >= 0; index--) {
                        final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                        if (rcse.mRccId == rccId) {
                            indexToPromote = index;
                            break;
                        }
                    }
                    if (indexToPromote >= 0) {
                        if (DEBUG_RC) { Log.d(TAG, "  moving RCC from index " + indexToPromote
                                + " to " + (mRCStack.size()-1)); }
                        final RemoteControlStackEntry rcse = mRCStack.remove(indexToPromote);
                        mRCStack.push(rcse);
                        // the RC stack changed, reevaluate the display
                        checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    // not expected to happen, indicates improper concurrent modification
                    Log.e(TAG, "Wrong index accessing RC stack, lock error? ", e);
                }
            }//synchronized(mRCStack)
        }//synchronized(mAudioFocusLock)
    }

    /**
     * see AudioManager.registerMediaButtonIntent(PendingIntent pi, ComponentName c)
     * precondition: mediaIntent != null
     */
    protected void registerMediaButtonIntent(PendingIntent mediaIntent, ComponentName eventReceiver,
            IBinder token) {
        Log.i(TAG, "  Remote Control   registerMediaButtonIntent() for " + mediaIntent);

        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                if (pushMediaButtonReceiver_syncAfRcs(mediaIntent, eventReceiver, token)) {
                    // new RC client, assume every type of information shall be queried
                    checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
                }
            }
        }
    }

    /**
     * see AudioManager.unregisterMediaButtonIntent(PendingIntent mediaIntent)
     * precondition: mediaIntent != null, eventReceiver != null
     */
    protected void unregisterMediaButtonIntent(PendingIntent mediaIntent)
    {
        Log.i(TAG, "  Remote Control   unregisterMediaButtonIntent() for " + mediaIntent);

        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                boolean topOfStackWillChange = isCurrentRcController(mediaIntent);
                removeMediaButtonReceiver_syncAfRcs(mediaIntent);
                if (topOfStackWillChange) {
                    // current RC client will change, assume every type of info needs to be queried
                    checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
                }
            }
        }
    }

    /**
     * see AudioManager.registerMediaButtonEventReceiverForCalls(ComponentName c)
     * precondition: c != null
     */
    protected void registerMediaButtonEventReceiverForCalls(ComponentName c) {
        if (mContext.checkCallingPermission("android.permission.MODIFY_PHONE_STATE")
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Invalid permissions to register media button receiver for calls");
            return;
        }
        synchronized(mRCStack) {
            mMediaReceiverForCalls = c;
        }
    }

    /**
     * see AudioManager.unregisterMediaButtonEventReceiverForCalls()
     */
    protected void unregisterMediaButtonEventReceiverForCalls() {
        if (mContext.checkCallingPermission("android.permission.MODIFY_PHONE_STATE")
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Invalid permissions to unregister media button receiver for calls");
            return;
        }
        synchronized(mRCStack) {
            mMediaReceiverForCalls = null;
        }
    }

    /**
     * see AudioManager.registerRemoteControlClient(ComponentName eventReceiver, ...)
     * @return the unique ID of the RemoteControlStackEntry associated with the RemoteControlClient
     * Note: using this method with rcClient == null is a way to "disable" the IRemoteControlClient
     *     without modifying the RC stack, but while still causing the display to refresh (will
     *     become blank as a result of this)
     */
    protected int registerRemoteControlClient(PendingIntent mediaIntent,
            IRemoteControlClient rcClient, String callingPackageName) {
        if (DEBUG_RC) Log.i(TAG, "Register remote control client rcClient="+rcClient);
        int rccId = RemoteControlClient.RCSE_ID_UNREGISTERED;
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                // store the new display information
                try {
                    for (int index = mRCStack.size()-1; index >= 0; index--) {
                        final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                        if(rcse.mMediaIntent.equals(mediaIntent)) {
                            // already had a remote control client?
                            if (rcse.mRcClientDeathHandler != null) {
                                // stop monitoring the old client's death
                                rcse.unlinkToRcClientDeath();
                            }
                            // save the new remote control client
                            rcse.mRcClient = rcClient;
                            rcse.mCallingPackageName = callingPackageName;
                            rcse.mCallingUid = Binder.getCallingUid();
                            if (rcClient == null) {
                                // here rcse.mRcClientDeathHandler is null;
                                rcse.resetPlaybackInfo();
                                break;
                            }
                            rccId = rcse.mRccId;

                            // there is a new (non-null) client:
                            // 1/ give the new client the displays (if any)
                            if (mRcDisplays.size() > 0) {
                                plugRemoteControlDisplaysIntoClient_syncRcStack(rcse.mRcClient);
                            }
                            // 2/ monitor the new client's death
                            IBinder b = rcse.mRcClient.asBinder();
                            RcClientDeathHandler rcdh =
                                    new RcClientDeathHandler(b, rcse.mMediaIntent);
                            try {
                                b.linkToDeath(rcdh, 0);
                            } catch (RemoteException e) {
                                // remote control client is DOA, disqualify it
                                Log.w(TAG, "registerRemoteControlClient() has a dead client " + b);
                                rcse.mRcClient = null;
                            }
                            rcse.mRcClientDeathHandler = rcdh;
                            break;
                        }
                    }//for
                } catch (ArrayIndexOutOfBoundsException e) {
                    // not expected to happen, indicates improper concurrent modification
                    Log.e(TAG, "Wrong index accessing RC stack, lock error? ", e);
                }

                // if the eventReceiver is at the top of the stack
                // then check for potential refresh of the remote controls
                if (isCurrentRcController(mediaIntent)) {
                    checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
                }
            }//synchronized(mRCStack)
        }//synchronized(mAudioFocusLock)
        return rccId;
    }

    /**
     * see AudioManager.unregisterRemoteControlClient(PendingIntent pi, ...)
     * rcClient is guaranteed non-null
     */
    protected void unregisterRemoteControlClient(PendingIntent mediaIntent,
            IRemoteControlClient rcClient) {
        if (DEBUG_RC) Log.i(TAG, "Unregister remote control client rcClient="+rcClient);
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                boolean topRccChange = false;
                try {
                    for (int index = mRCStack.size()-1; index >= 0; index--) {
                        final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                        if ((rcse.mMediaIntent.equals(mediaIntent))
                                && rcClient.equals(rcse.mRcClient)) {
                            // we found the IRemoteControlClient to unregister
                            // stop monitoring its death
                            rcse.unlinkToRcClientDeath();
                            // reset the client-related fields
                            rcse.mRcClient = null;
                            rcse.mCallingPackageName = null;
                            topRccChange = (index == mRCStack.size()-1);
                            // there can only be one matching RCC in the RC stack, we're done
                            break;
                        }
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    // not expected to happen, indicates improper concurrent modification
                    Log.e(TAG, "Wrong index accessing RC stack, lock error? ", e);
                }
                if (topRccChange) {
                    // no more RCC for the RCD, check for potential refresh of the remote controls
                    checkUpdateRemoteControlDisplay_syncAfRcs(RC_INFO_ALL);
                }
            }
        }
    }


    /**
     * A class to encapsulate all the information about a remote control display.
     * After instanciation, init() must always be called before the object is added in the list
     * of displays.
     * Before being removed from the list of displays, release() must always be called (otherwise
     * it will leak death handlers).
     */
    private class DisplayInfoForServer implements IBinder.DeathRecipient {
        /** may never be null */
        private final IRemoteControlDisplay mRcDisplay;
        private final IBinder mRcDisplayBinder;
        private int mArtworkExpectedWidth = -1;
        private int mArtworkExpectedHeight = -1;
        private boolean mWantsPositionSync = false;
        private ComponentName mClientNotifListComp;
        private boolean mEnabled = true;

        public DisplayInfoForServer(IRemoteControlDisplay rcd, int w, int h) {
            if (DEBUG_RC) Log.i(TAG, "new DisplayInfoForServer for " + rcd + " w=" + w + " h=" + h);
            mRcDisplay = rcd;
            mRcDisplayBinder = rcd.asBinder();
            mArtworkExpectedWidth = w;
            mArtworkExpectedHeight = h;
        }

        public boolean init() {
            try {
                mRcDisplayBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                // remote control display is DOA, disqualify it
                Log.w(TAG, "registerRemoteControlDisplay() has a dead client " + mRcDisplayBinder);
                return false;
            }
            return true;
        }

        public void release() {
            try {
                mRcDisplayBinder.unlinkToDeath(this, 0);
            } catch (java.util.NoSuchElementException e) {
                // not much we can do here, the display should have been unregistered anyway
                Log.e(TAG, "Error in DisplaInfoForServer.relase()", e);
            }
        }

        public void binderDied() {
            synchronized(mRCStack) {
                Log.w(TAG, "RemoteControl: display " + mRcDisplay + " died");
                // remove the display from the list
                final Iterator<DisplayInfoForServer> displayIterator = mRcDisplays.iterator();
                while (displayIterator.hasNext()) {
                    final DisplayInfoForServer di = (DisplayInfoForServer) displayIterator.next();
                    if (di.mRcDisplay == mRcDisplay) {
                        if (DEBUG_RC) Log.w(TAG, " RCD removed from list");
                        displayIterator.remove();
                        return;
                    }
                }
            }
        }
    }

    /**
     * The remote control displays.
     * Access synchronized on mRCStack
     */
    private ArrayList<DisplayInfoForServer> mRcDisplays = new ArrayList<DisplayInfoForServer>(1);

    /**
     * Plug each registered display into the specified client
     * @param rcc, guaranteed non null
     */
    private void plugRemoteControlDisplaysIntoClient_syncRcStack(IRemoteControlClient rcc) {
        final Iterator<DisplayInfoForServer> displayIterator = mRcDisplays.iterator();
        while (displayIterator.hasNext()) {
            final DisplayInfoForServer di = (DisplayInfoForServer) displayIterator.next();
            try {
                rcc.plugRemoteControlDisplay(di.mRcDisplay, di.mArtworkExpectedWidth,
                        di.mArtworkExpectedHeight);
                if (di.mWantsPositionSync) {
                    rcc.setWantsSyncForDisplay(di.mRcDisplay, true);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error connecting RCD to RCC in RCC registration",e);
            }
        }
    }

    private void enableRemoteControlDisplayForClient_syncRcStack(IRemoteControlDisplay rcd,
            boolean enabled) {
        // let all the remote control clients know whether the given display is enabled
        //   (so the remote control stack traversal order doesn't matter).
        final Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
        while(stackIterator.hasNext()) {
            RemoteControlStackEntry rcse = stackIterator.next();
            if(rcse.mRcClient != null) {
                try {
                    rcse.mRcClient.enableRemoteControlDisplay(rcd, enabled);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error connecting RCD to client: ", e);
                }
            }
        }
    }

    /**
     * Is the remote control display interface already registered
     * @param rcd
     * @return true if the IRemoteControlDisplay is already in the list of displays
     */
    private boolean rcDisplayIsPluggedIn_syncRcStack(IRemoteControlDisplay rcd) {
        final Iterator<DisplayInfoForServer> displayIterator = mRcDisplays.iterator();
        while (displayIterator.hasNext()) {
            final DisplayInfoForServer di = (DisplayInfoForServer) displayIterator.next();
            if (di.mRcDisplay.asBinder().equals(rcd.asBinder())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Register an IRemoteControlDisplay.
     * Notify all IRemoteControlClient of the new display and cause the RemoteControlClient
     * at the top of the stack to update the new display with its information.
     * @see android.media.IAudioService#registerRemoteControlDisplay(android.media.IRemoteControlDisplay, int, int)
     * @param rcd the IRemoteControlDisplay to register. No effect if null.
     * @param w the maximum width of the expected bitmap. Negative or zero values indicate this
     *   display doesn't need to receive artwork.
     * @param h the maximum height of the expected bitmap. Negative or zero values indicate this
     *   display doesn't need to receive artwork.
     * @param listenerComp the component for the listener interface, may be null if it's not needed
     *   to verify it belongs to one of the enabled notification listeners
     */
    private void registerRemoteControlDisplay_int(IRemoteControlDisplay rcd, int w, int h,
            ComponentName listenerComp) {
        if (DEBUG_RC) Log.d(TAG, ">>> registerRemoteControlDisplay("+rcd+")");
        synchronized(mAudioFocusLock) {
            synchronized(mRCStack) {
                if ((rcd == null) || rcDisplayIsPluggedIn_syncRcStack(rcd)) {
                    return;
                }
                DisplayInfoForServer di = new DisplayInfoForServer(rcd, w, h);
                di.mEnabled = true;
                di.mClientNotifListComp = listenerComp;
                if (!di.init()) {
                    if (DEBUG_RC) Log.e(TAG, " error registering RCD");
                    return;
                }
                // add RCD to list of displays
                mRcDisplays.add(di);

                // let all the remote control clients know there is a new display (so the remote
                //   control stack traversal order doesn't matter).
                Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
                while(stackIterator.hasNext()) {
                    RemoteControlStackEntry rcse = stackIterator.next();
                    if(rcse.mRcClient != null) {
                        try {
                            rcse.mRcClient.plugRemoteControlDisplay(rcd, w, h);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error connecting RCD to client: ", e);
                        }
                    }
                }

                // we have a new display, of which all the clients are now aware: have it be
                // initialized wih the current gen ID and the current client info, do not
                // reset the information for the other (existing) displays
                sendMsg(mEventHandler, MSG_RCDISPLAY_INIT_INFO, SENDMSG_QUEUE,
                        w /*arg1*/, h /*arg2*/,
                        rcd /*obj*/, 0/*delay*/);
            }
        }
    }

    /**
     * Unregister an IRemoteControlDisplay.
     * No effect if the IRemoteControlDisplay hasn't been successfully registered.
     * @see android.media.IAudioService#unregisterRemoteControlDisplay(android.media.IRemoteControlDisplay)
     * @param rcd the IRemoteControlDisplay to unregister. No effect if null.
     */
    protected void unregisterRemoteControlDisplay(IRemoteControlDisplay rcd) {
        if (DEBUG_RC) Log.d(TAG, "<<< unregisterRemoteControlDisplay("+rcd+")");
        synchronized(mRCStack) {
            if (rcd == null) {
                return;
            }

            boolean displayWasPluggedIn = false;
            final Iterator<DisplayInfoForServer> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext() && !displayWasPluggedIn) {
                final DisplayInfoForServer di = (DisplayInfoForServer) displayIterator.next();
                if (di.mRcDisplay.asBinder().equals(rcd.asBinder())) {
                    displayWasPluggedIn = true;
                    di.release();
                    displayIterator.remove();
                }
            }

            if (displayWasPluggedIn) {
                // disconnect this remote control display from all the clients, so the remote
                //   control stack traversal order doesn't matter
                final Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
                while(stackIterator.hasNext()) {
                    final RemoteControlStackEntry rcse = stackIterator.next();
                    if(rcse.mRcClient != null) {
                        try {
                            rcse.mRcClient.unplugRemoteControlDisplay(rcd);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error disconnecting remote control display to client: ", e);
                        }
                    }
                }
            } else {
                if (DEBUG_RC) Log.w(TAG, "  trying to unregister unregistered RCD");
            }
        }
    }

    /**
     * Update the size of the artwork used by an IRemoteControlDisplay.
     * @see android.media.IAudioService#remoteControlDisplayUsesBitmapSize(android.media.IRemoteControlDisplay, int, int)
     * @param rcd the IRemoteControlDisplay with the new artwork size requirement
     * @param w the maximum width of the expected bitmap. Negative or zero values indicate this
     *   display doesn't need to receive artwork.
     * @param h the maximum height of the expected bitmap. Negative or zero values indicate this
     *   display doesn't need to receive artwork.
     */
    protected void remoteControlDisplayUsesBitmapSize(IRemoteControlDisplay rcd, int w, int h) {
        synchronized(mRCStack) {
            final Iterator<DisplayInfoForServer> displayIterator = mRcDisplays.iterator();
            boolean artworkSizeUpdate = false;
            while (displayIterator.hasNext() && !artworkSizeUpdate) {
                final DisplayInfoForServer di = (DisplayInfoForServer) displayIterator.next();
                if (di.mRcDisplay.asBinder().equals(rcd.asBinder())) {
                    if ((di.mArtworkExpectedWidth != w) || (di.mArtworkExpectedHeight != h)) {
                        di.mArtworkExpectedWidth = w;
                        di.mArtworkExpectedHeight = h;
                        artworkSizeUpdate = true;
                    }
                }
            }
            if (artworkSizeUpdate) {
                // RCD is currently plugged in and its artwork size has changed, notify all RCCs,
                // stack traversal order doesn't matter
                final Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
                while(stackIterator.hasNext()) {
                    final RemoteControlStackEntry rcse = stackIterator.next();
                    if(rcse.mRcClient != null) {
                        try {
                            rcse.mRcClient.setBitmapSizeForDisplay(rcd, w, h);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error setting bitmap size for RCD on RCC: ", e);
                        }
                    }
                }
            }
        }
    }

    /**
     * Controls whether a remote control display needs periodic checks of the RemoteControlClient
     * playback position to verify that the estimated position has not drifted from the actual
     * position. By default the check is not performed.
     * The IRemoteControlDisplay must have been previously registered for this to have any effect.
     * @param rcd the IRemoteControlDisplay for which the anti-drift mechanism will be enabled
     *     or disabled. Not null.
     * @param wantsSync if true, RemoteControlClient instances which expose their playback position
     *     to the framework will regularly compare the estimated playback position with the actual
     *     position, and will update the IRemoteControlDisplay implementation whenever a drift is
     *     detected.
     */
    protected void remoteControlDisplayWantsPlaybackPositionSync(IRemoteControlDisplay rcd,
            boolean wantsSync) {
        synchronized(mRCStack) {
            boolean rcdRegistered = false;
            // store the information about this display
            // (display stack traversal order doesn't matter).
            final Iterator<DisplayInfoForServer> displayIterator = mRcDisplays.iterator();
            while (displayIterator.hasNext()) {
                final DisplayInfoForServer di = (DisplayInfoForServer) displayIterator.next();
                if (di.mRcDisplay.asBinder().equals(rcd.asBinder())) {
                    di.mWantsPositionSync = wantsSync;
                    rcdRegistered = true;
                    break;
                }
            }
            if (!rcdRegistered) {
                return;
            }
            // notify all current RemoteControlClients
            // (stack traversal order doesn't matter as we notify all RCCs)
            final Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while (stackIterator.hasNext()) {
                final RemoteControlStackEntry rcse = stackIterator.next();
                if (rcse.mRcClient != null) {
                    try {
                        rcse.mRcClient.setWantsSyncForDisplay(rcd, wantsSync);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error setting position sync flag for RCD on RCC: ", e);
                    }
                }
            }
        }
    }

    protected void setRemoteControlClientPlaybackPosition(int generationId, long timeMs) {
        // ignore position change requests if invalid generation ID
        synchronized(mRCStack) {
            synchronized(mCurrentRcLock) {
                if (mCurrentRcClientGen != generationId) {
                    return;
                }
            }
        }
        // discard any unprocessed seek request in the message queue, and replace with latest
        sendMsg(mEventHandler, MSG_RCC_SEEK_REQUEST, SENDMSG_REPLACE, generationId /* arg1 */,
                0 /* arg2 ignored*/, new Long(timeMs) /* obj */, 0 /* delay */);
    }

    private void onSetRemoteControlClientPlaybackPosition(int generationId, long timeMs) {
        if(DEBUG_RC) Log.d(TAG, "onSetRemoteControlClientPlaybackPosition(genId=" + generationId +
                ", timeMs=" + timeMs + ")");
        synchronized(mRCStack) {
            synchronized(mCurrentRcLock) {
                if ((mCurrentRcClient != null) && (mCurrentRcClientGen == generationId)) {
                    // tell the current client to seek to the requested location
                    try {
                        mCurrentRcClient.seekTo(generationId, timeMs);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Current valid remote client is dead: "+e);
                        mCurrentRcClient = null;
                    }
                }
            }
        }
    }

    protected void updateRemoteControlClientMetadata(int genId, int key, Rating value) {
        sendMsg(mEventHandler, MSG_RCC_UPDATE_METADATA, SENDMSG_QUEUE,
                genId /* arg1 */, key /* arg2 */, value /* obj */, 0 /* delay */);
    }

    private void onUpdateRemoteControlClientMetadata(int genId, int key, Rating value) {
        if(DEBUG_RC) Log.d(TAG, "onUpdateRemoteControlClientMetadata(genId=" + genId +
                ", what=" + key + ",rating=" + value + ")");
        synchronized(mRCStack) {
            synchronized(mCurrentRcLock) {
                if ((mCurrentRcClient != null) && (mCurrentRcClientGen == genId)) {
                    try {
                        switch (key) {
                            case MediaMetadataEditor.RATING_KEY_BY_USER:
                                mCurrentRcClient.updateMetadata(genId, key, value);
                                break;
                            default:
                                Log.e(TAG, "unhandled metadata key " + key + " update for RCC "
                                        + genId);
                                break;
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Current valid remote client is dead", e);
                        mCurrentRcClient = null;
                    }
                }
            }
        }
    }

    protected void setPlaybackInfoForRcc(int rccId, int what, int value) {
        sendMsg(mEventHandler, MSG_RCC_NEW_PLAYBACK_INFO, SENDMSG_QUEUE,
                rccId /* arg1 */, what /* arg2 */, Integer.valueOf(value) /* obj */, 0 /* delay */);
    }

    // handler for MSG_RCC_NEW_PLAYBACK_INFO
    private void onNewPlaybackInfoForRcc(int rccId, int key, int value) {
        if(DEBUG_RC) Log.d(TAG, "onNewPlaybackInfoForRcc(id=" + rccId +
                ", what=" + key + ",val=" + value + ")");
        synchronized(mRCStack) {
            // iterating from top of stack as playback information changes are more likely
            //   on entries at the top of the remote control stack
            try {
                for (int index = mRCStack.size()-1; index >= 0; index--) {
                    final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                    if (rcse.mRccId == rccId) {
                        switch (key) {
                            case RemoteControlClient.PLAYBACKINFO_PLAYBACK_TYPE:
                                rcse.mPlaybackType = value;
                                postReevaluateRemote();
                                break;
                            case RemoteControlClient.PLAYBACKINFO_VOLUME:
                                rcse.mPlaybackVolume = value;
                                synchronized (mMainRemote) {
                                    if (rccId == mMainRemote.mRccId) {
                                        mMainRemote.mVolume = value;
                                        mVolumeController.postHasNewRemotePlaybackInfo();
                                    }
                                }
                                break;
                            case RemoteControlClient.PLAYBACKINFO_VOLUME_MAX:
                                rcse.mPlaybackVolumeMax = value;
                                synchronized (mMainRemote) {
                                    if (rccId == mMainRemote.mRccId) {
                                        mMainRemote.mVolumeMax = value;
                                        mVolumeController.postHasNewRemotePlaybackInfo();
                                    }
                                }
                                break;
                            case RemoteControlClient.PLAYBACKINFO_VOLUME_HANDLING:
                                rcse.mPlaybackVolumeHandling = value;
                                synchronized (mMainRemote) {
                                    if (rccId == mMainRemote.mRccId) {
                                        mMainRemote.mVolumeHandling = value;
                                        mVolumeController.postHasNewRemotePlaybackInfo();
                                    }
                                }
                                break;
                            case RemoteControlClient.PLAYBACKINFO_USES_STREAM:
                                rcse.mPlaybackStream = value;
                                break;
                            default:
                                Log.e(TAG, "unhandled key " + key + " for RCC " + rccId);
                                break;
                        }
                        return;
                    }
                }//for
            } catch (ArrayIndexOutOfBoundsException e) {
                // not expected to happen, indicates improper concurrent modification
                Log.e(TAG, "Wrong index mRCStack on onNewPlaybackInfoForRcc, lock error? ", e);
            }
        }
    }

    protected void setPlaybackStateForRcc(int rccId, int state, long timeMs, float speed) {
        sendMsg(mEventHandler, MSG_RCC_NEW_PLAYBACK_STATE, SENDMSG_QUEUE,
                rccId /* arg1 */, state /* arg2 */,
                new RccPlaybackState(state, timeMs, speed) /* obj */, 0 /* delay */);
    }

    private void onNewPlaybackStateForRcc(int rccId, int state, RccPlaybackState newState) {
        if(DEBUG_RC) Log.d(TAG, "onNewPlaybackStateForRcc(id=" + rccId + ", state=" + state
                + ", time=" + newState.mPositionMs + ", speed=" + newState.mSpeed + ")");
        synchronized(mRCStack) {
            // iterating from top of stack as playback information changes are more likely
            //   on entries at the top of the remote control stack
            try {
                for (int index = mRCStack.size()-1; index >= 0; index--) {
                    final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                    if (rcse.mRccId == rccId) {
                        rcse.mPlaybackState = newState;
                        synchronized (mMainRemote) {
                            if (rccId == mMainRemote.mRccId) {
                                mMainRemoteIsActive = isPlaystateActive(state);
                                postReevaluateRemote();
                            }
                        }
                        // an RCC moving to a "playing" state should become the media button
                        //   event receiver so it can be controlled, without requiring the
                        //   app to re-register its receiver
                        if (isPlaystateActive(state)) {
                            postPromoteRcc(rccId);
                        }
                    }
                }//for
            } catch (ArrayIndexOutOfBoundsException e) {
                // not expected to happen, indicates improper concurrent modification
                Log.e(TAG, "Wrong index on mRCStack in onNewPlaybackStateForRcc, lock error? ", e);
            }
        }
    }

    protected void registerRemoteVolumeObserverForRcc(int rccId, IRemoteVolumeObserver rvo) {
        sendMsg(mEventHandler, MSG_RCC_NEW_VOLUME_OBS, SENDMSG_QUEUE,
                rccId /* arg1 */, 0, rvo /* obj */, 0 /* delay */);
    }

    // handler for MSG_RCC_NEW_VOLUME_OBS
    private void onRegisterVolumeObserverForRcc(int rccId, IRemoteVolumeObserver rvo) {
        synchronized(mRCStack) {
            // The stack traversal order doesn't matter because there is only one stack entry
            //  with this RCC ID, but the matching ID is more likely at the top of the stack, so
            //  start iterating from the top.
            try {
                for (int index = mRCStack.size()-1; index >= 0; index--) {
                    final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                    if (rcse.mRccId == rccId) {
                        rcse.mRemoteVolumeObs = rvo;
                        break;
                    }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                // not expected to happen, indicates improper concurrent modification
                Log.e(TAG, "Wrong index accessing media button stack, lock error? ", e);
            }
        }
    }

    /**
     * Checks if a remote client is active on the supplied stream type. Update the remote stream
     * volume state if found and playing
     * @param streamType
     * @return false if no remote playing is currently playing
     */
    protected boolean checkUpdateRemoteStateIfActive(int streamType) {
        synchronized(mRCStack) {
            // iterating from top of stack as active playback is more likely on entries at the top
            try {
                for (int index = mRCStack.size()-1; index >= 0; index--) {
                    final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                    if ((rcse.mPlaybackType == RemoteControlClient.PLAYBACK_TYPE_REMOTE)
                            && isPlaystateActive(rcse.mPlaybackState.mState)
                            && (rcse.mPlaybackStream == streamType)) {
                        if (DEBUG_RC) Log.d(TAG, "remote playback active on stream " + streamType
                                + ", vol =" + rcse.mPlaybackVolume);
                        synchronized (mMainRemote) {
                            mMainRemote.mRccId = rcse.mRccId;
                            mMainRemote.mVolume = rcse.mPlaybackVolume;
                            mMainRemote.mVolumeMax = rcse.mPlaybackVolumeMax;
                            mMainRemote.mVolumeHandling = rcse.mPlaybackVolumeHandling;
                            mMainRemoteIsActive = true;
                        }
                        return true;
                    }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                // not expected to happen, indicates improper concurrent modification
                Log.e(TAG, "Wrong index accessing RC stack, lock error? ", e);
            }
        }
        synchronized (mMainRemote) {
            mMainRemoteIsActive = false;
        }
        return false;
    }

    /**
     * Returns true if the given playback state is considered "active", i.e. it describes a state
     * where playback is happening, or about to
     * @param playState the playback state to evaluate
     * @return true if active, false otherwise (inactive or unknown)
     */
    private static boolean isPlaystateActive(int playState) {
        switch (playState) {
            case RemoteControlClient.PLAYSTATE_PLAYING:
            case RemoteControlClient.PLAYSTATE_BUFFERING:
            case RemoteControlClient.PLAYSTATE_FAST_FORWARDING:
            case RemoteControlClient.PLAYSTATE_REWINDING:
            case RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS:
            case RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS:
                return true;
            default:
                return false;
        }
    }

    protected void adjustRemoteVolume(int streamType, int direction, int flags) {
        int rccId = RemoteControlClient.RCSE_ID_UNREGISTERED;
        boolean volFixed = false;
        synchronized (mMainRemote) {
            if (!mMainRemoteIsActive) {
                if (DEBUG_VOL) Log.w(TAG, "adjustRemoteVolume didn't find an active client");
                return;
            }
            rccId = mMainRemote.mRccId;
            volFixed = (mMainRemote.mVolumeHandling ==
                    RemoteControlClient.PLAYBACK_VOLUME_FIXED);
        }
        // unlike "local" stream volumes, we can't compute the new volume based on the direction,
        // we can only notify the remote that volume needs to be updated, and we'll get an async'
        // update through setPlaybackInfoForRcc()
        if (!volFixed) {
            sendVolumeUpdateToRemote(rccId, direction);
        }

        // fire up the UI
        mVolumeController.postRemoteVolumeChanged(streamType, flags);
    }

    private void sendVolumeUpdateToRemote(int rccId, int direction) {
        if (DEBUG_VOL) { Log.d(TAG, "sendVolumeUpdateToRemote(rccId="+rccId+" , dir="+direction); }
        if (direction == 0) {
            // only handling discrete events
            return;
        }
        IRemoteVolumeObserver rvo = null;
        synchronized (mRCStack) {
            // The stack traversal order doesn't matter because there is only one stack entry
            //  with this RCC ID, but the matching ID is more likely at the top of the stack, so
            //  start iterating from the top.
            try {
                for (int index = mRCStack.size()-1; index >= 0; index--) {
                    final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                    //FIXME OPTIMIZE store this info in mMainRemote so we don't have to iterate?
                    if (rcse.mRccId == rccId) {
                        rvo = rcse.mRemoteVolumeObs;
                        break;
                    }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                // not expected to happen, indicates improper concurrent modification
                Log.e(TAG, "Wrong index accessing media button stack, lock error? ", e);
            }
        }
        if (rvo != null) {
            try {
                rvo.dispatchRemoteVolumeUpdate(direction, -1);
            } catch (RemoteException e) {
                Log.e(TAG, "Error dispatching relative volume update", e);
            }
        }
    }

    protected int getRemoteStreamMaxVolume() {
        synchronized (mMainRemote) {
            if (mMainRemote.mRccId == RemoteControlClient.RCSE_ID_UNREGISTERED) {
                return 0;
            }
            return mMainRemote.mVolumeMax;
        }
    }

    protected int getRemoteStreamVolume() {
        synchronized (mMainRemote) {
            if (mMainRemote.mRccId == RemoteControlClient.RCSE_ID_UNREGISTERED) {
                return 0;
            }
            return mMainRemote.mVolume;
        }
    }

    protected void setRemoteStreamVolume(int vol) {
        if (DEBUG_VOL) { Log.d(TAG, "setRemoteStreamVolume(vol="+vol+")"); }
        int rccId = RemoteControlClient.RCSE_ID_UNREGISTERED;
        synchronized (mMainRemote) {
            if (mMainRemote.mRccId == RemoteControlClient.RCSE_ID_UNREGISTERED) {
                return;
            }
            rccId = mMainRemote.mRccId;
        }
        IRemoteVolumeObserver rvo = null;
        synchronized (mRCStack) {
            // The stack traversal order doesn't matter because there is only one stack entry
            //  with this RCC ID, but the matching ID is more likely at the top of the stack, so
            //  start iterating from the top.
            try {
                for (int index = mRCStack.size()-1; index >= 0; index--) {
                    final RemoteControlStackEntry rcse = mRCStack.elementAt(index);
                    //FIXME OPTIMIZE store this info in mMainRemote so we don't have to iterate?
                    if (rcse.mRccId == rccId) {
                        rvo = rcse.mRemoteVolumeObs;
                        break;
                    }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                // not expected to happen, indicates improper concurrent modification
                Log.e(TAG, "Wrong index accessing media button stack, lock error? ", e);
            }
        }
        if (rvo != null) {
            try {
                rvo.dispatchRemoteVolumeUpdate(0, vol);
            } catch (RemoteException e) {
                Log.e(TAG, "Error dispatching absolute volume update", e);
            }
        }
    }

    /**
     * Call to make AudioService reevaluate whether it's in a mode where remote players should
     * have their volume controlled. In this implementation this is only to reset whether
     * VolumePanel should display remote volumes
     */
    private void postReevaluateRemote() {
        sendMsg(mEventHandler, MSG_REEVALUATE_REMOTE, SENDMSG_QUEUE, 0, 0, null, 0);
    }

    private void onReevaluateRemote() {
        if (DEBUG_VOL) { Log.w(TAG, "onReevaluateRemote()"); }
        // is there a registered RemoteControlClient that is handling remote playback
        boolean hasRemotePlayback = false;
        synchronized (mRCStack) {
            // iteration stops when PLAYBACK_TYPE_REMOTE is found, so remote control stack
            //   traversal order doesn't matter
            Iterator<RemoteControlStackEntry> stackIterator = mRCStack.iterator();
            while(stackIterator.hasNext()) {
                RemoteControlStackEntry rcse = stackIterator.next();
                if (rcse.mPlaybackType == RemoteControlClient.PLAYBACK_TYPE_REMOTE) {
                    hasRemotePlayback = true;
                    break;
                }
            }
        }
        synchronized (mMainRemote) {
            if (mHasRemotePlayback != hasRemotePlayback) {
                mHasRemotePlayback = hasRemotePlayback;
                mVolumeController.postRemoteSliderVisibility(hasRemotePlayback);
            }
        }
    }

}
