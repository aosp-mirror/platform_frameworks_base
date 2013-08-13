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

package com.android.printspooler;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.print.IPrinterDiscoverySessionController;
import android.print.IPrinterDiscoverySessionObserver;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.util.ArraySet;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * This class is responsible to provide the available printers.
 * It starts and stops printer discovery and manages the returned
 * printers.
 */
public class AvailablePrinterProvider extends DataProvider<PrinterInfo>
        implements DataLoader {
    private static final String LOG_TAG = "AvailablePrinterProvider";

    private final Set<PrinterId> mPrinteIdsSet = new ArraySet<PrinterId>();

    private final List<PrinterInfo> mPrinters = new ArrayList<PrinterInfo>();

    private final List<PrinterId> mPriorityList;

    private PrinterDiscoverySession mDiscoverySession;

    public AvailablePrinterProvider(Context context, List<PrinterId> priorityList) {
        mDiscoverySession = new PrinterDiscoverySession(context.getMainLooper());
        mPriorityList = priorityList;
    }

    @Override
    public void startLoadData() {
        mDiscoverySession.open();
    }

    @Override
    public void stopLoadData() {
        mDiscoverySession.close();
    }

    @Override
    public int getItemCount() {
        return mPrinters.size();
    }

    @Override
    public int getItemIndex(PrinterInfo printer) {
        return mPrinters.indexOf(printer);
    }

    @Override
    public PrinterInfo getItemAt(int index) {
        return mPrinters.get(index);
    }

    public void refreshItem(int index) {
        PrinterInfo printer = getItemAt(index);
        mDiscoverySession.requestPrinterUpdate(printer.getId());
    }

    private void addPrinters(List<PrinterInfo> printers) {
        boolean addedPrinters = false;

        final int addedPrinterCount = printers.size();
        for (int i = 0; i < addedPrinterCount; i++) {
           PrinterInfo addedPrinter = printers.get(i);
           if (mPrinteIdsSet.add(addedPrinter.getId())) {
               mPrinters.add(addedPrinter);
               addedPrinters = true;
           }
        }

        if (addedPrinters) {
            notifyChanged();
        }
    }

    private void updatePrinters(List<PrinterInfo> printers) {
        boolean updatedPrinters = false;

        final int updatedPrinterCount = printers.size();
        for (int i = 0; i < updatedPrinterCount; i++) {
            PrinterInfo updatedPrinter = printers.get(i);
            if (mPrinteIdsSet.contains(updatedPrinter.getId())) {
                final int oldPrinterCount = mPrinters.size();
                for (int j = 0; j < oldPrinterCount; j++) {
                    PrinterInfo oldPrinter = mPrinters.get(j);
                    if (updatedPrinter.getId().equals(oldPrinter.getId())) {
                        mPrinters.set(j, updatedPrinter);
                        updatedPrinters = true;
                        break;
                    }
                }
            }
        }

        if (updatedPrinters) {
            notifyChanged();
        }
    }

    private void removePrinters(List<PrinterId> printers) {
        boolean removedPrinters = false;

        final int removedPrinterCount = printers.size();
        for (int i = 0; i < removedPrinterCount; i++) {
            PrinterId removedPrinter = printers.get(i);
            if (mPrinteIdsSet.contains(removedPrinter)) {
                mPrinteIdsSet.remove(removedPrinter);
                Iterator<PrinterInfo> iterator = mPrinters.iterator();
                while (iterator.hasNext()) {
                    PrinterInfo oldPrinter = iterator.next();
                    if (removedPrinter.equals(oldPrinter.getId())) {
                        iterator.remove();
                        break;
                    }
                }
            }
        }

        if (removedPrinters) {
            notifyChanged();
        }
    }

    private final class PrinterDiscoverySession {

        private final Handler mHandler;

        private final IPrinterDiscoverySessionObserver mObserver;

        private IPrinterDiscoverySessionController mController;

        public PrinterDiscoverySession(Looper looper) {
            mHandler = new SessionHandler(looper);
            mObserver = new PrinterDiscoverySessionObserver(this);
        }

        public void open() {
            PrintSpooler.peekInstance().createPrinterDiscoverySession(
                    mObserver);
        }

        public void close() {
            if (mController != null) {
                try {
                    mController.close();
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error closing printer discovery session", re);
                } finally {
                    mController = null;
                }
            }
        }

        public void requestPrinterUpdate(PrinterId printerId) {
            if (mController != null) {
                try {
                    mController.requestPrinterUpdate(printerId);
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error requesting printer udpdate", re);
                }
            }
        }

        private final class SessionHandler extends Handler {
            public static final int MSG_SET_CONTROLLER = 1;
            public static final int MSG_ON_PRINTERS_ADDED = 2;
            public static final int MSG_ON_PRINTERS_REMOVED = 3;
            public static final int MSG_ON_PRINTERS_UPDATED = 4;

            public SessionHandler(Looper looper) {
                super(looper, null, false);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_SET_CONTROLLER: {
                        mController = (IPrinterDiscoverySessionController) message.obj;
                        try {
                            mController.open(mPriorityList);
                        } catch (RemoteException e) {
                            Log.e(LOG_TAG, "Error starting printer discovery");
                        }
                    } break;

                    case MSG_ON_PRINTERS_ADDED: {
                        List<PrinterInfo> printers = (List<PrinterInfo>) message.obj;
                        addPrinters(printers);
                    } break;

                    case MSG_ON_PRINTERS_REMOVED: {
                        List<PrinterId> printers = (List<PrinterId>) message.obj;
                        removePrinters(printers);
                    } break;

                    case MSG_ON_PRINTERS_UPDATED: {
                        List<PrinterInfo> printers = (List<PrinterInfo>) message.obj;
                        updatePrinters(printers);
                    } break;
                };
            }
        }
    }

    private static final class PrinterDiscoverySessionObserver
            extends IPrinterDiscoverySessionObserver.Stub {

        private final WeakReference<PrinterDiscoverySession> mWeakSession;

        public PrinterDiscoverySessionObserver(PrinterDiscoverySession session) {
            mWeakSession = new WeakReference<PrinterDiscoverySession>(session);
        }

        @Override
        public void setController(IPrinterDiscoverySessionController controller) {
            PrinterDiscoverySession sesison = mWeakSession.get();
            if (sesison != null) {
                sesison.mHandler.obtainMessage(
                        PrinterDiscoverySession.SessionHandler.MSG_SET_CONTROLLER,
                        controller).sendToTarget();
            }
        }

        @Override
        public void onPrintersAdded(List<PrinterInfo> printers) {
            PrinterDiscoverySession sesison = mWeakSession.get();
            if (sesison != null) {
                sesison.mHandler.obtainMessage(
                        PrinterDiscoverySession.SessionHandler.MSG_ON_PRINTERS_ADDED,
                        printers).sendToTarget();
            }
        }

        @Override
        public void onPrintersRemoved(List<PrinterId> printers) {
            PrinterDiscoverySession session = mWeakSession.get();
            if (session != null) {
                session.mHandler.obtainMessage(
                        PrinterDiscoverySession.SessionHandler.MSG_ON_PRINTERS_REMOVED,
                        printers).sendToTarget();
            }
        }

        @Override
        public void onPrintersUpdated(List<PrinterInfo> printers) {
            PrinterDiscoverySession session = mWeakSession.get();
            if (session != null) {
                session.mHandler.obtainMessage(
                        PrinterDiscoverySession.SessionHandler.MSG_ON_PRINTERS_UPDATED,
                        printers).sendToTarget();
            }
        }
    };
}
