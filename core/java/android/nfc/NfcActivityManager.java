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
import android.app.Application;
import android.compat.annotation.UnsupportedAppUsage;
import android.nfc.NfcAdapter.ReaderCallback;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Manages NFC API's that are coupled to the life-cycle of an Activity.
 *
 * <p>Uses {@link Application#registerActivityLifecycleCallbacks} to hook
 * into activity life-cycle events such as onPause() and onResume().
 *
 * @hide
 */
public final class NfcActivityManager extends IAppCallback.Stub
        implements Application.ActivityLifecycleCallbacks {
    static final String TAG = NfcAdapter.TAG;
    static final Boolean DBG = false;

    @UnsupportedAppUsage
    final NfcAdapter mAdapter;

    // All objects in the lists are protected by this
    final List<NfcApplicationState> mApps;  // Application(s) that have NFC state. Usually one
    final List<NfcActivityState> mActivities;  // Activities that have NFC state

    /**
     * NFC State associated with an {@link Application}.
     */
    class NfcApplicationState {
        int refCount = 0;
        final Application app;
        public NfcApplicationState(Application app) {
            this.app = app;
        }
        public void register() {
            refCount++;
            if (refCount == 1) {
                this.app.registerActivityLifecycleCallbacks(NfcActivityManager.this);
            }
        }
        public void unregister() {
            refCount--;
            if (refCount == 0) {
                this.app.unregisterActivityLifecycleCallbacks(NfcActivityManager.this);
            } else if (refCount < 0) {
                Log.e(TAG, "-ve refcount for " + app);
            }
        }
    }

    NfcApplicationState findAppState(Application app) {
        for (NfcApplicationState appState : mApps) {
            if (appState.app == app) {
                return appState;
            }
        }
        return null;
    }

    void registerApplication(Application app) {
        NfcApplicationState appState = findAppState(app);
        if (appState == null) {
            appState = new NfcApplicationState(app);
            mApps.add(appState);
        }
        appState.register();
    }

    void unregisterApplication(Application app) {
        NfcApplicationState appState = findAppState(app);
        if (appState == null) {
            Log.e(TAG, "app was not registered " + app);
            return;
        }
        appState.unregister();
    }

    /**
     * NFC state associated with an {@link Activity}
     */
    class NfcActivityState {
        boolean resumed = false;
        Activity activity;
        NfcAdapter.ReaderCallback readerCallback = null;
        int readerModeFlags = 0;
        Bundle readerModeExtras = null;
        Binder token;

        public NfcActivityState(Activity activity) {
            if (activity.isDestroyed()) {
                throw new IllegalStateException("activity is already destroyed");
            }
            // Check if activity is resumed right now, as we will not
            // immediately get a callback for that.
            resumed = activity.isResumed();

            this.activity = activity;
            this.token = new Binder();
            registerApplication(activity.getApplication());
        }
        public void destroy() {
            unregisterApplication(activity.getApplication());
            resumed = false;
            activity = null;
            readerCallback = null;
            readerModeFlags = 0;
            readerModeExtras = null;
            token = null;
        }
        @Override
        public String toString() {
            StringBuilder s = new StringBuilder("[");
            s.append(readerCallback);
            s.append("]");
            return s.toString();
        }
    }

    /** find activity state from mActivities */
    synchronized NfcActivityState findActivityState(Activity activity) {
        for (NfcActivityState state : mActivities) {
            if (state.activity == activity) {
                return state;
            }
        }
        return null;
    }

    /** find or create activity state from mActivities */
    synchronized NfcActivityState getActivityState(Activity activity) {
        NfcActivityState state = findActivityState(activity);
        if (state == null) {
            state = new NfcActivityState(activity);
            mActivities.add(state);
        }
        return state;
    }

    synchronized NfcActivityState findResumedActivityState() {
        for (NfcActivityState state : mActivities) {
            if (state.resumed) {
                return state;
            }
        }
        return null;
    }

    synchronized void destroyActivityState(Activity activity) {
        NfcActivityState activityState = findActivityState(activity);
        if (activityState != null) {
            activityState.destroy();
            mActivities.remove(activityState);
        }
    }

    public NfcActivityManager(NfcAdapter adapter) {
        mAdapter = adapter;
        mActivities = new LinkedList<NfcActivityState>();
        mApps = new ArrayList<NfcApplicationState>(1);  // Android VM usually has 1 app
    }

    public void enableReaderMode(Activity activity, ReaderCallback callback, int flags,
            Bundle extras) {
        boolean isResumed;
        Binder token;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = getActivityState(activity);
            state.readerCallback = callback;
            state.readerModeFlags = flags;
            state.readerModeExtras = extras;
            token = state.token;
            isResumed = state.resumed;
        }
        if (isResumed) {
            setReaderMode(token, flags, extras);
        }
    }

    public void disableReaderMode(Activity activity) {
        boolean isResumed;
        Binder token;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = getActivityState(activity);
            state.readerCallback = null;
            state.readerModeFlags = 0;
            state.readerModeExtras = null;
            token = state.token;
            isResumed = state.resumed;
        }
        if (isResumed) {
            setReaderMode(token, 0, null);
        }

    }

    public void setReaderMode(Binder token, int flags, Bundle extras) {
        if (DBG) Log.d(TAG, "Setting reader mode");
        try {
            NfcAdapter.sService.setReaderMode(token, this, flags, extras);
        } catch (RemoteException e) {
            mAdapter.attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Request or unrequest NFC service callbacks.
     * Makes IPC call - do not hold lock.
     */
    void requestNfcServiceCallback() {
        try {
            NfcAdapter.sService.setAppCallback(this);
        } catch (RemoteException e) {
            mAdapter.attemptDeadServiceRecovery(e);
        }
    }

    void verifyNfcPermission() {
        try {
            NfcAdapter.sService.verifyNfcPermission();
        } catch (RemoteException e) {
            mAdapter.attemptDeadServiceRecovery(e);
        }
    }

    @Override
    public void onTagDiscovered(Tag tag) throws RemoteException {
        NfcAdapter.ReaderCallback callback;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findResumedActivityState();
            if (state == null) return;

            callback = state.readerCallback;
        }

        // Make callback without lock
        if (callback != null) {
            callback.onTagDiscovered(tag);
        }

    }
    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) { /* NO-OP */ }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityStarted(Activity activity) { /* NO-OP */ }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityResumed(Activity activity) {
        int readerModeFlags = 0;
        Bundle readerModeExtras = null;
        Binder token;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findActivityState(activity);
            if (DBG) Log.d(TAG, "onResume() for " + activity + " " + state);
            if (state == null) return;
            state.resumed = true;
            token = state.token;
            readerModeFlags = state.readerModeFlags;
            readerModeExtras = state.readerModeExtras;
        }
        if (readerModeFlags != 0) {
            setReaderMode(token, readerModeFlags, readerModeExtras);
        }
        requestNfcServiceCallback();
    }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityPaused(Activity activity) {
        boolean readerModeFlagsSet;
        Binder token;
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findActivityState(activity);
            if (DBG) Log.d(TAG, "onPause() for " + activity + " " + state);
            if (state == null) return;
            state.resumed = false;
            token = state.token;
            readerModeFlagsSet = state.readerModeFlags != 0;
        }
        if (readerModeFlagsSet) {
            // Restore default p2p modes
            setReaderMode(token, 0, null);
        }
    }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityStopped(Activity activity) { /* NO-OP */ }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) { /* NO-OP */ }

    /** Callback from Activity life-cycle, on main thread */
    @Override
    public void onActivityDestroyed(Activity activity) {
        synchronized (NfcActivityManager.this) {
            NfcActivityState state = findActivityState(activity);
            if (DBG) Log.d(TAG, "onDestroy() for " + activity + " " + state);
            if (state != null) {
                // release all associated references
                destroyActivityState(activity);
            }
        }
    }

}
