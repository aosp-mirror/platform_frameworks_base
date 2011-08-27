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

package android.nfc;

import android.app.Activity;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;

/**
 * Manages NFC API's that are coupled to the life-cycle of an Activity.
 *
 * <p>Uses a fragment to hook into onPause() and onResume() of the host
 * activities.
 *
 * <p>Ideally all of this management would be done in the NFC Service,
 * but right now it is much easier to do it in the application process.
 *
 * @hide
 */
public final class NfcActivityManager extends INdefPushCallback.Stub {
    static final String TAG = NfcAdapter.TAG;
    static final Boolean DBG = false;

    final NfcAdapter mAdapter;
    final HashMap<Activity, NfcActivityState> mNfcState;  // contents protected by this
    final NfcEvent mDefaultEvent;  // can re-use one NfcEvent because it just contains adapter

    /**
     * NFC state associated with an {@link Activity}
     */
    class NfcActivityState {
        boolean resumed = false;  // is the activity resumed
        NdefMessage ndefMessage;
        NfcAdapter.CreateNdefMessageCallback ndefMessageCallback;
        NfcAdapter.OnNdefPushCompleteCallback onNdefPushCompleteCallback;
        @Override
        public String toString() {
            StringBuilder s = new StringBuilder("[").append(resumed).append(" ");
            s.append(ndefMessage).append(" ").append(ndefMessageCallback).append(" ");
            s.append(onNdefPushCompleteCallback).append("]");
            return s.toString();
        }
    }

    public NfcActivityManager(NfcAdapter adapter) {
        mAdapter = adapter;
        mNfcState = new HashMap<Activity, NfcActivityState>();
        mDefaultEvent = new NfcEvent(mAdapter);
    }

    /**
     * onResume hook from fragment attached to activity
     */
    public synchronized void onResume(Activity activity) {
        NfcActivityState state = mNfcState.get(activity);
        if (DBG) Log.d(TAG, "onResume() for " + activity + " " + state);
        if (state != null) {
            state.resumed = true;
            updateNfcService(state);
        }
    }

    /**
     * onPause hook from fragment attached to activity
     */
    public synchronized void onPause(Activity activity) {
        NfcActivityState state = mNfcState.get(activity);
        if (DBG) Log.d(TAG, "onPause() for " + activity + " " + state);
        if (state != null) {
            state.resumed = false;
            updateNfcService(state);
        }
    }

    public synchronized void setNdefPushMessage(Activity activity, NdefMessage message) {
        NfcActivityState state = getOrCreateState(activity, message != null);
        if (state == null || state.ndefMessage == message) {
            return;  // nothing more to do;
        }
        state.ndefMessage = message;
        if (message == null) {
            maybeRemoveState(activity, state);
        }
        if (state.resumed) {
            updateNfcService(state);
        }
    }

    public synchronized void setNdefPushMessageCallback(Activity activity,
            NfcAdapter.CreateNdefMessageCallback callback) {
        NfcActivityState state = getOrCreateState(activity, callback != null);
        if (state == null || state.ndefMessageCallback == callback) {
            return;  // nothing more to do;
        }
        state.ndefMessageCallback = callback;
        if (callback == null) {
            maybeRemoveState(activity, state);
        }
        if (state.resumed) {
            updateNfcService(state);
        }
    }

    public synchronized void setOnNdefPushCompleteCallback(Activity activity,
            NfcAdapter.OnNdefPushCompleteCallback callback) {
        NfcActivityState state = getOrCreateState(activity, callback != null);
        if (state == null || state.onNdefPushCompleteCallback == callback) {
            return;  // nothing more to do;
        }
        state.onNdefPushCompleteCallback = callback;
        if (callback == null) {
            maybeRemoveState(activity, state);
        }
        if (state.resumed) {
            updateNfcService(state);
        }
    }

    /**
     * Get the NfcActivityState for the specified Activity.
     * If create is true, then create it if it doesn't already exist,
     * and ensure the NFC fragment is attached to the activity.
     */
    synchronized NfcActivityState getOrCreateState(Activity activity, boolean create) {
        if (DBG) Log.d(TAG, "getOrCreateState " + activity + " " + create);
        NfcActivityState state = mNfcState.get(activity);
        if (state == null && create) {
            state = new NfcActivityState();
            mNfcState.put(activity, state);
            NfcFragment.attach(activity);
        }
        return state;
    }

    /**
     * If the NfcActivityState is empty then remove it, and
     * detach it from the Activity.
     */
    synchronized void maybeRemoveState(Activity activity, NfcActivityState state) {
        if (state.ndefMessage == null && state.ndefMessageCallback == null &&
                state.onNdefPushCompleteCallback == null) {
            mNfcState.remove(activity);
        }
    }

    /**
     * Register NfcActivityState with the NFC service.
     */
    synchronized void updateNfcService(NfcActivityState state) {
        boolean serviceCallbackNeeded = state.ndefMessageCallback != null ||
                state.onNdefPushCompleteCallback != null;

        try {
            NfcAdapter.sService.setForegroundNdefPush(state.resumed ? state.ndefMessage : null,
                    state.resumed && serviceCallbackNeeded ? this : null);
        } catch (RemoteException e) {
            mAdapter.attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Callback from NFC service
     */
    @Override
    public NdefMessage createMessage() {
        NfcAdapter.CreateNdefMessageCallback callback = null;
        synchronized (NfcActivityManager.this) {
            for (NfcActivityState state : mNfcState.values()) {
                if (state.resumed) {
                    callback = state.ndefMessageCallback;
                }
            }
        }

        // drop lock before making callback
        if (callback != null) {
            return callback.createNdefMessage(mDefaultEvent);
        }
        return null;
    }

    /**
     * Callback from NFC service
     */
    @Override
    public void onNdefPushComplete() {
        NfcAdapter.OnNdefPushCompleteCallback callback = null;
        synchronized (NfcActivityManager.this) {
            for (NfcActivityState state : mNfcState.values()) {
                if (state.resumed) {
                    callback = state.onNdefPushCompleteCallback;
                }
            }
        }

        // drop lock before making callback
        if (callback != null) {
            callback.onNdefPushComplete(mDefaultEvent);
        }
    }
}
