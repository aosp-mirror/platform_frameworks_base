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

package android.print;

import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * @hide
 */
public final class PrinterDiscoverySession {

    private static final String LOG_TAG ="PrinterDiscoverySession";

    private static final int MSG_PRINTERS_ADDED = 1;
    private static final int MSG_PRINTERS_REMOVED = 2;

    private final LinkedHashMap<PrinterId, PrinterInfo> mPrinters =
            new LinkedHashMap<PrinterId, PrinterInfo>();

    private final IPrintManager mPrintManager;

    private final int mUserId;

    private final Handler mHandler;

    private IPrinterDiscoveryObserver mObserver;

    private OnPrintersChangeListener mListener;

    private boolean mIsPrinterDiscoveryStarted;

    public static interface OnPrintersChangeListener {
        public void onPrintersChanged();
    }

    PrinterDiscoverySession(IPrintManager printManager, Context context, int userId) {
        mPrintManager = printManager;
        mUserId = userId;
        mHandler = new SessionHandler(context.getMainLooper());
        mObserver = new PrinterDiscoveryObserver(this);
        try {
            mPrintManager.createPrinterDiscoverySession(mObserver, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error creating printer discovery session", re);
        }
    }

    public final void startPrinterDisovery(List<PrinterId> priorityList) {
        if (isDestroyed()) {
            Log.w(LOG_TAG, "Ignoring start printers dsicovery - session destroyed");
            return;
        }
        if (!mIsPrinterDiscoveryStarted) {
            mIsPrinterDiscoveryStarted = true;
            try {
                mPrintManager.startPrinterDiscovery(mObserver, priorityList, mUserId);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error starting printer discovery", re);
            }
        }
    }

    public final void stopPrinterDiscovery() {
        if (isDestroyed()) {
            Log.w(LOG_TAG, "Ignoring stop printers discovery - session destroyed");
            return;
        }
        if (mIsPrinterDiscoveryStarted) {
            mIsPrinterDiscoveryStarted = false;
            try {
                mPrintManager.stopPrinterDiscovery(mObserver, mUserId);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error stopping printer discovery", re);
            }
        }
    }

    public final void startPrinterStateTracking(PrinterId printerId) {
        if (isDestroyed()) {
            Log.w(LOG_TAG, "Ignoring start printer state tracking - session destroyed");
            return;
        }
        try {
            mPrintManager.startPrinterStateTracking(printerId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error starting printer state tracking", re);
        }
    }

    public final void stopPrinterStateTracking(PrinterId printerId) {
        if (isDestroyed()) {
            Log.w(LOG_TAG, "Ignoring stop printer state tracking - session destroyed");
            return;
        }
        try {
            mPrintManager.stopPrinterStateTracking(printerId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error stoping printer state tracking", re);
        }
    }

    public final void validatePrinters(List<PrinterId> printerIds) {
        if (isDestroyed()) {
            Log.w(LOG_TAG, "Ignoring validate printers - session destroyed");
            return;
        }
        try {
            mPrintManager.validatePrinters(printerIds, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error validating printers", re);
        }
    }

    public final void destroy() {
        if (isDestroyed()) {
            Log.w(LOG_TAG, "Ignoring destroy - session destroyed");
        }
        destroyNoCheck();
    }

    public final List<PrinterInfo> getPrinters() {
        if (isDestroyed()) {
            Log.w(LOG_TAG, "Ignoring get printers - session destroyed");
            return Collections.emptyList();
        }
        return new ArrayList<PrinterInfo>(mPrinters.values());
    }

    public final boolean isDestroyed() {
        throwIfNotCalledOnMainThread();
        return isDestroyedNoCheck();
    }

    public final boolean isPrinterDiscoveryStarted() {
        throwIfNotCalledOnMainThread();
        return mIsPrinterDiscoveryStarted;
    }

    public final void setOnPrintersChangeListener(OnPrintersChangeListener listener) {
        throwIfNotCalledOnMainThread();
        mListener = listener;
    }

    @Override
    protected final void finalize() throws Throwable {
        if (!isDestroyedNoCheck()) {
            Log.e(LOG_TAG, "Destroying leaked printer discovery session");
            destroyNoCheck();
        }
        super.finalize();
    }

    private boolean isDestroyedNoCheck() {
        return (mObserver == null);
    }

    private void destroyNoCheck() {
        stopPrinterDiscovery();
        try {
            mPrintManager.destroyPrinterDiscoverySession(mObserver, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error destroying printer discovery session", re);
        } finally {
            mObserver = null;
            mPrinters.clear();
        }
    }

    private void handlePrintersAdded(List<PrinterInfo> addedPrinters) {
        if (isDestroyed()) {
            return;
        }

        // No old printers - do not bother keeping their position.
        if (mPrinters.isEmpty()) {
            final int printerCount = addedPrinters.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterInfo printer = addedPrinters.get(i);
                mPrinters.put(printer.getId(), printer);
            }
            notifyOnPrintersChanged();
            return;
        }

        // Add the printers to a map.
        ArrayMap<PrinterId, PrinterInfo> addedPrintersMap =
                new ArrayMap<PrinterId, PrinterInfo>();
        final int printerCount = addedPrinters.size();
        for (int i = 0; i < printerCount; i++) {
            PrinterInfo printer = addedPrinters.get(i);
            addedPrintersMap.put(printer.getId(), printer);
        }

        // Update printers we already have.
        for (PrinterId oldPrinterId : mPrinters.keySet()) {
            PrinterInfo updatedPrinter = addedPrintersMap.remove(oldPrinterId);
            if (updatedPrinter != null) {
                mPrinters.put(oldPrinterId, updatedPrinter);
            }
        }

        // Add the new printers, i.e. what is left.
        mPrinters.putAll(addedPrintersMap);

        // Announce the change.
        notifyOnPrintersChanged();
    }

    private void handlePrintersRemoved(List<PrinterId> printerIds) {
        if (isDestroyed()) {
            return;
        }
        boolean printersChanged = false;
        final int removedPrinterIdCount = printerIds.size();
        for (int i = 0; i < removedPrinterIdCount; i++) {
            PrinterId removedPrinterId = printerIds.get(i);
            if (mPrinters.remove(removedPrinterId) != null) {
                printersChanged = true;
            }
        }
        if (printersChanged) {
            notifyOnPrintersChanged();
        }
    }

    private void notifyOnPrintersChanged() {
        if (mListener != null) {
            mListener.onPrintersChanged();
        }
    }

    private static void throwIfNotCalledOnMainThread() {
        if (!Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalAccessError("must be called from the main thread");
        }
    }

    private final class SessionHandler extends Handler {

        public SessionHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_PRINTERS_ADDED: {
                    List<PrinterInfo> printers = (List<PrinterInfo>) message.obj;
                    handlePrintersAdded(printers);
                } break;

                case MSG_PRINTERS_REMOVED: {
                    List<PrinterId> printerIds = (List<PrinterId>) message.obj;
                    handlePrintersRemoved(printerIds);
                } break;
            }
        }
    }

    private static final class PrinterDiscoveryObserver extends IPrinterDiscoveryObserver.Stub {

        private final WeakReference<PrinterDiscoverySession> mWeakSession;

        public PrinterDiscoveryObserver(PrinterDiscoverySession session) {
            mWeakSession = new WeakReference<PrinterDiscoverySession>(session);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void onPrintersAdded(ParceledListSlice printers) {
            PrinterDiscoverySession session = mWeakSession.get();
            if (session != null) {
                session.mHandler.obtainMessage(MSG_PRINTERS_ADDED,
                        printers.getList()).sendToTarget();
            }
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void onPrintersRemoved(ParceledListSlice printerIds) {
            PrinterDiscoverySession session = mWeakSession.get();
            if (session != null) {
                session.mHandler.obtainMessage(MSG_PRINTERS_REMOVED,
                        printerIds.getList()).sendToTarget();
            }
        }
    }
}
