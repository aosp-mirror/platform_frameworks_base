/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.server;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.location.ComprehensiveCountryDetector;

import android.content.Context;
import android.location.Country;
import android.location.CountryListener;
import android.location.ICountryDetector;
import android.location.ICountryListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;

/**
 * This class detects the country that the user is in through
 * {@link ComprehensiveCountryDetector}.
 *
 * @hide
 */
public class CountryDetectorService extends ICountryDetector.Stub implements Runnable {

    /**
     * The class represents the remote listener, it will also removes itself
     * from listener list when the remote process was died.
     */
    private final class Receiver implements IBinder.DeathRecipient {
        private final ICountryListener mListener;
        private final IBinder mKey;

        public Receiver(ICountryListener listener) {
            mListener = listener;
            mKey = listener.asBinder();
        }

        public void binderDied() {
            removeListener(mKey);
        }

        @Override
        public boolean equals(Object otherObj) {
            if (otherObj instanceof Receiver) {
                return mKey.equals(((Receiver) otherObj).mKey);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return mKey.hashCode();
        }

        public ICountryListener getListener() {
            return mListener;
        }
    }

    private final static String TAG = "CountryDetector";

    /** Whether to dump the state of the country detector service to bugreports */
    private static final boolean DEBUG = false;

    private final HashMap<IBinder, Receiver> mReceivers;
    private final Context mContext;
    private ComprehensiveCountryDetector mCountryDetector;
    private boolean mSystemReady;
    private Handler mHandler;
    private CountryListener mLocationBasedDetectorListener;

    public CountryDetectorService(Context context) {
        super();
        mReceivers = new HashMap<IBinder, Receiver>();
        mContext = context;
    }

    @Override
    public Country detectCountry() {
        if (!mSystemReady) {
            return null;   // server not yet active
        } else {
            return mCountryDetector.detectCountry();
        }
    }

    /**
     * Add the ICountryListener into the listener list.
     */
    @Override
    public void addCountryListener(ICountryListener listener) throws RemoteException {
        if (!mSystemReady) {
            throw new RemoteException();
        }
        addListener(listener);
    }

    /**
     * Remove the ICountryListener from the listener list.
     */
    @Override
    public void removeCountryListener(ICountryListener listener) throws RemoteException {
        if (!mSystemReady) {
            throw new RemoteException();
        }
        removeListener(listener.asBinder());
    }

    private void addListener(ICountryListener listener) {
        synchronized (mReceivers) {
            Receiver r = new Receiver(listener);
            try {
                listener.asBinder().linkToDeath(r, 0);
                mReceivers.put(listener.asBinder(), r);
                if (mReceivers.size() == 1) {
                    Slog.d(TAG, "The first listener is added");
                    setCountryListener(mLocationBasedDetectorListener);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "linkToDeath failed:", e);
            }
        }
    }

    private void removeListener(IBinder key) {
        synchronized (mReceivers) {
            mReceivers.remove(key);
            if (mReceivers.isEmpty()) {
                setCountryListener(null);
                Slog.d(TAG, "No listener is left");
            }
        }
    }


    protected void notifyReceivers(Country country) {
        synchronized(mReceivers) {
            for (Receiver receiver : mReceivers.values()) {
                try {
                    receiver.getListener().onCountryDetected(country);
                } catch (RemoteException e) {
                    // TODO: Shall we remove the receiver?
                    Slog.e(TAG, "notifyReceivers failed:", e);
                }
            }
        }
    }

    void systemRunning() {
        // Shall we wait for the initialization finish.
        BackgroundThread.getHandler().post(this);
    }

    private void initialize() {
        mCountryDetector = new ComprehensiveCountryDetector(mContext);
        mLocationBasedDetectorListener = new CountryListener() {
            public void onCountryDetected(final Country country) {
                mHandler.post(new Runnable() {
                    public void run() {
                        notifyReceivers(country);
                    }
                });
            }
        };
    }

    public void run() {
        mHandler = new Handler();
        initialize();
        mSystemReady = true;
    }

    protected void setCountryListener(final CountryListener listener) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mCountryDetector.setCountryListener(listener);
            }
        });
    }

    // For testing
    boolean isSystemReady() {
        return mSystemReady;
    }

    @SuppressWarnings("unused")
    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, fout)) return;
        if (!DEBUG) return;
        try {
            final Printer p = new PrintWriterPrinter(fout);
            p.println("CountryDetectorService state:");
            p.println("  Number of listeners=" + mReceivers.keySet().size());
            if (mCountryDetector == null) {
                p.println("  ComprehensiveCountryDetector not initialized");
            } else {
                p.println("  " + mCountryDetector.toString());
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to dump CountryDetectorService: ", e);
        }
    }
}
