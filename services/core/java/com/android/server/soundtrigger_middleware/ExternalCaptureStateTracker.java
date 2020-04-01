/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.media.ICaptureStateListener;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * This is a never-give-up listener for sound trigger external capture state notifications, as
 * published by the audio policy service.
 *
 * This class will constantly try to connect to the service over a background thread and tolerate
 * its death. The client will be notified by a single provided function that is called in a
 * synchronized manner.
 * For simplicity, there is currently no way to stop the tracker. This is possible to add if the
 * need ever arises.
 */
public class ExternalCaptureStateTracker {
    private static final String TAG = "CaptureStateTracker";
    /** Our client's listener. */
    private final Consumer<Boolean> mListener;
    /** A lock used to ensure synchronized access to mListener. */
    private final Object mListenerLock = new Object();
    /**
     * The binder listener that we're providing to the audio policy service. Ensures synchronized
     * access to mListener.
     */
    private final Listener mSyncListener = new Listener();
    /** The name of the audio policy service. */
    private final String mAudioPolicyServiceName;
    /** This semaphore will get a permit every time we need to reconnect. */
    private final Semaphore mNeedToConnect = new Semaphore(1);
    /**
     * We must hold a reference to the APM service, even though we're not actually using it after
     * installing the callback. Otherwise, binder silently un-links our death listener.
     */
    private IBinder mService;

    /**
     * Constructor. Will start a background thread to do the work.
     *
     * @param audioPolicyServiceName The name of the audio policy service to connect to.
     * @param listener               A client provided listener that will be called on state
     *                               changes. May be
     *                               called multiple consecutive times with the same value. Never
     *                               called
     *                               concurrently.
     */
    public ExternalCaptureStateTracker(String audioPolicyServiceName,
            Consumer<Boolean> listener) {
        mAudioPolicyServiceName = audioPolicyServiceName;
        mListener = listener;
        new Thread(this::run).start();
    }

    /**
     * Routine for the background thread. Keeps trying to reconnect.
     */
    private void run() {
        while (true) {
            mNeedToConnect.acquireUninterruptibly();
            connectWithRetry();
        }
    }

    /**
     * Try to connect. Retry in case of RemoteException.
     */
    private void connectWithRetry() {
        while (true) {
            try {
                connect();
                return;
            } catch (RemoteException e) {
                Log.w(TAG, "Exception caught trying to connect", e);
            } catch (ServiceManager.ServiceNotFoundException e) {
                Log.w(TAG, "Service not yet available, waiting", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                }
            } catch (Exception e) {
                Log.e(TAG, "Unexpected exception caught trying to connect", e);
                throw e;
            }
        }
    }

    /**
     * Connect to the service, install listener and death notifier.
     *
     * @throws RemoteException In case of a binder issue.
     */
    private void connect() throws RemoteException, ServiceManager.ServiceNotFoundException {
        Log.d(TAG, "Connecting to audio policy service: " + mAudioPolicyServiceName);
        mService = ServiceManager.getServiceOrThrow(mAudioPolicyServiceName);

        synchronized (mListenerLock) {
            boolean active = registerSoundTriggerCaptureStateListener(mService, mSyncListener);
            mListener.accept(active);
        }

        mService.linkToDeath(() -> {
            Log.w(TAG, "Audio policy service died");
            mNeedToConnect.release();
        }, 0);
    }

    /**
     * Since the audio policy service does not have an AIDL interface, this method does the
     * necessary manual marshalling.
     *
     * @param service  The service binder object.
     * @param listener The listener binder object to register.
     * @return The active state at the time of registration.
     */
    private boolean registerSoundTriggerCaptureStateListener(IBinder service,
            ICaptureStateListener listener) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        request.writeInterfaceToken("android.media.IAudioPolicyService");
        request.writeStrongBinder(listener.asBinder());
        service.transact(82 /* REGISTER_SOUNDTRIGGER_CAPTURE_STATE_LISTENER */, request, response,
                0);
        return response.readBoolean();
    }

    private class Listener extends ICaptureStateListener.Stub {
        @Override
        public void setCaptureState(boolean active) {
            synchronized (mListenerLock) {
                mListener.accept(active);
            }
        }
    }
}
