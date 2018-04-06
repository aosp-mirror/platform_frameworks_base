/*
 * Copyright (C) 2017 The Android Open Source Project
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
/*
 * Copyright (c) 2015-2017, The Linux Foundation. All rights reserved.
 */
/*
 * Contributed by: Giesecke & Devrient GmbH.
 */

package android.se.omapi;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.concurrent.Executor;

/**
 * The SEService realises the communication to available Secure Elements on the
 * device. This is the entry point of this API. It is used to connect to the
 * infrastructure and get access to a list of Secure Element Readers.
 *
 * @see <a href="http://simalliance.org">SIMalliance Open Mobile API  v3.0</a>
 */
public final class SEService {

    /**
     * Error code used with ServiceSpecificException.
     * Thrown if there was an error communicating with the Secure Element.
     *
     * @hide
     */
    public static final int IO_ERROR = 1;

    /**
     * Error code used with ServiceSpecificException.
     * Thrown if AID cannot be selected or is not available when opening
     * a logical channel.
     *
     * @hide
     */
    public static final int NO_SUCH_ELEMENT_ERROR = 2;

    /**
     * Interface to send call-backs to the application when the service is connected.
     */
    public interface OnConnectedListener {
        /**
         * Called by the framework when the service is connected.
         */
        void onConnected();
    }

    /**
     * Listener object that allows the notification of the caller if this
     * SEService could be bound to the backend.
     */
    private class SEListener extends ISecureElementListener.Stub {
        public OnConnectedListener mListener = null;
        public Executor mExecutor = null;

        @Override
        public IBinder asBinder() {
            return this;
        }

        public void onConnected() {
            if (mListener != null && mExecutor != null) {
                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onConnected();
                    }
                });
            }
        }
    }
    private SEListener mSEListener = new SEListener();

    private static final String TAG = "OMAPI.SEService";

    private final Object mLock = new Object();

    /** The client context (e.g. activity). */
    private final Context mContext;

    /** The backend system. */
    private volatile ISecureElementService mSecureElementService;

    /**
     * Class for interacting with the main interface of the backend.
     */
    private ServiceConnection mConnection;

    /**
     * Collection of available readers
     */
    private final HashMap<String, Reader> mReaders = new HashMap<String, Reader>();

    /**
     * Establishes a new connection that can be used to connect to all the
     * Secure Elements available in the system. The connection process can be
     * quite long, so it happens in an asynchronous way. It is usable only if
     * the specified listener is called or if isConnected() returns
     * <code>true</code>. <br>
     * The call-back object passed as a parameter will have its
     * onConnected() method called when the connection actually happen.
     *
     * @param context
     *            the context of the calling application. Cannot be
     *            <code>null</code>.
     * @param listener
     *            a OnConnectedListener object.
     * @param executor
     *            an Executor which will be used when invoking the callback.
     */
    public SEService(@NonNull Context context, @NonNull Executor executor,
            @NonNull OnConnectedListener listener) {

        if (context == null || listener == null || executor == null) {
            throw new NullPointerException("Arguments must not be null");
        }

        mContext = context;
        mSEListener.mListener = listener;
        mSEListener.mExecutor = executor;

        mConnection = new ServiceConnection() {

            public synchronized void onServiceConnected(
                    ComponentName className, IBinder service) {

                mSecureElementService = ISecureElementService.Stub.asInterface(service);
                if (mSEListener != null) {
                    mSEListener.onConnected();
                }
                Log.i(TAG, "Service onServiceConnected");
            }

            public void onServiceDisconnected(ComponentName className) {
                mSecureElementService = null;
                Log.i(TAG, "Service onServiceDisconnected");
            }
        };

        Intent intent = new Intent(ISecureElementService.class.getName());
        intent.setClassName("com.android.se",
                            "com.android.se.SecureElementService");
        boolean bindingSuccessful =
                mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        if (bindingSuccessful) {
            Log.i(TAG, "bindService successful");
        }
    }

    /**
     * Tells whether or not the service is connected.
     *
     * @return <code>true</code> if the service is connected.
     */
    public boolean isConnected() {
        return mSecureElementService != null;
    }

    /**
     * Returns an array of available Secure Element readers.
     * There must be no duplicated objects in the returned list.
     * All available readers shall be listed even if no card is inserted.
     *
     * @return An array of Readers. If there are no readers the returned array
     * is of length 0.
     */
    public @NonNull Reader[] getReaders() {
        if (mSecureElementService == null) {
            throw new IllegalStateException("service not connected to system");
        }
        String[] readerNames;
        try {
            readerNames = mSecureElementService.getReaders();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        Reader[] readers = new Reader[readerNames.length];
        int i = 0;
        for (String readerName : readerNames) {
            if (mReaders.get(readerName) == null) {
                try {
                    mReaders.put(readerName, new Reader(this, readerName,
                            getReader(readerName)));
                    readers[i++] = mReaders.get(readerName);
                } catch (Exception e) {
                    Log.e(TAG, "Error adding Reader: " + readerName, e);
                }
            } else {
                readers[i++] = mReaders.get(readerName);
            }
        }
        return readers;
    }

    /**
     * Releases all Secure Elements resources allocated by this SEService
     * (including any binding to an underlying service).
     * As a result isConnected() will return false after shutdown() was called.
     * After this method call, the SEService object is not connected.
     * This method should be called when connection to the Secure Element is not needed
     * or in the termination method of the calling application
     * (or part of this application) which is bound to this SEService.
     */
    public void shutdown() {
        synchronized (mLock) {
            if (mSecureElementService != null) {
                for (Reader reader : mReaders.values()) {
                    try {
                        reader.closeSessions();
                    } catch (Exception ignore) { }
                }
            }
            try {
                mContext.unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                // Do nothing and fail silently since an error here indicates
                // that binding never succeeded in the first place.
            }
            mSecureElementService = null;
        }
    }

    /**
     * Returns the version of the OpenMobile API specification this
     * implementation is based on.
     *
     * @return String containing the OpenMobile API version (e.g. "3.0").
     */
    public @NonNull String getVersion() {
        return "3.2";
    }

    @NonNull ISecureElementListener getListener() {
        return mSEListener;
    }

    /**
     * Obtain a Reader instance from the SecureElementService
     */
    private @NonNull ISecureElementReader getReader(String name) {
        try {
            return mSecureElementService.getReader(name);
        } catch (RemoteException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }
}
