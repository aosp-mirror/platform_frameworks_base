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

package com.android.server.print;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.print.IPrinterDiscoveryObserver;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.IPrintService;
import android.printservice.IPrintServiceClient;
import android.util.Slog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * This class represents a remote print service. It abstracts away the binding
 * and unbinding from the remote implementation. Clients can call methods of
 * this class without worrying about when and how to bind/unbind.
 */
final class RemotePrintService {

    private static final String LOG_TAG = "RemotePrintService";

    private static final boolean DEBUG = true;

    private final Context mContext;

    private final ComponentName mComponentName;

    private final Intent mIntent;

    private final RemotePrintSpooler mSpooler;

    private final int mUserId;

    private final List<Runnable> mPendingCommands = new ArrayList<Runnable>();

    private final ServiceConnection mServiceConnection = new RemoteServiceConneciton();

    private final RemotePrintServiceClient mPrintServiceClient;

    private final Handler mHandler;

    private IPrintService mPrintService;

    private boolean mBinding;

    private boolean mDestroyed;

    public RemotePrintService(Context context, ComponentName componentName, int userId,
            RemotePrintSpooler spooler) {
        mContext = context;
        mComponentName = componentName;
        mIntent = new Intent().setComponent(mComponentName);
        mUserId = userId;
        mSpooler = spooler;
        mHandler = new MyHandler(context.getMainLooper());
        mPrintServiceClient = new RemotePrintServiceClient(this);
    }

    public void destroy() {
        mHandler.sendEmptyMessage(MyHandler.MSG_DESTROY);
    }

    private void handleDestroy() {
        throwIfDestroyed();
        ensureUnbound();
        mDestroyed = true;
    }

    public void onAllPrintJobsHandled() {
        mHandler.sendEmptyMessage(MyHandler.MSG_ALL_PRINT_JOBS_HANDLED);
    }

    private void handleOnAllPrintJobsHandled() {
        throwIfDestroyed();
        if (isBound()) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] handleOnAllPrintJobsHandled()");
            }
            // If bound and all the work is completed, then unbind.
            ensureUnbound();
        }
    }

    public void onRequestCancelPrintJob(PrintJobInfo printJob) {
        mHandler.obtainMessage(MyHandler.MSG_REQUEST_CANCEL_PRINT_JOB,
                printJob).sendToTarget();
    }

    private void handleOnRequestCancelPrintJob(final PrintJobInfo printJob) {
        throwIfDestroyed();
        // If we are not bound, then we have no print jobs to handle
        // which means that there are no print jobs to be cancelled.
        if (isBound()) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] handleOnRequestCancelPrintJob()");
            }
            try {
                mPrintService.requestCancelPrintJob(printJob);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error canceling pring job.", re);
            }
        }
    }

    public void onPrintJobQueued(PrintJobInfo printJob) {
        mHandler.obtainMessage(MyHandler.MSG_PRINT_JOB_QUEUED,
                printJob).sendToTarget();
    }

    private void handleOnPrintJobQueued(final PrintJobInfo printJob) {
        throwIfDestroyed();
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                 public void run() {
                    handleOnPrintJobQueued(printJob);
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] handleOnPrintJobQueued()");
            }
            try {
                mPrintService.onPrintJobQueued(printJob);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error announcing queued pring job.", re);
            }
        }
    }

    public void onStartPrinterDiscovery(IPrinterDiscoveryObserver observer) {
        mHandler.obtainMessage(MyHandler.MSG_START_PRINTER_DISCOVERY, observer).sendToTarget();
    }

    private void handleOnStartPrinterDiscovery(final IPrinterDiscoveryObserver observer) {
        throwIfDestroyed();
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleOnStartPrinterDiscovery(observer);
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] onStartPrinterDiscovery()");
            }
            try {
                mPrintService.startPrinterDiscovery(observer);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error announcing start printer dicovery.", re);
            }
        }
    }

    public void onStopPrinterDiscovery() {
        mHandler.sendEmptyMessage(MyHandler.MSG_STOP_PRINTER_DISCOVERY);
    }

    private void handleStopPrinterDiscovery() {
        throwIfDestroyed();
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleStopPrinterDiscovery();
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] onStopPrinterDiscovery()");
            }
            try {
                mPrintService.stopPrinterDiscovery();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error announcing stop printer dicovery.", re);
            }
        }
    }

    private boolean isBound() {
        return mPrintService != null;
    }

    private void ensureBound() {
        if (isBound() || mBinding) {
            return;
        }
        if (DEBUG) {
            Slog.i(LOG_TAG, "[user: " + mUserId + "] ensureBound()");
        }
        mBinding = true;
        mContext.bindServiceAsUser(mIntent, mServiceConnection,
                Context.BIND_AUTO_CREATE, new UserHandle(mUserId));
    }

    private void ensureUnbound() {
        if (!isBound() && !mBinding) {
            return;
        }
        if (DEBUG) {
            Slog.i(LOG_TAG, "[user: " + mUserId + "] ensureUnbound()");
        }
        mBinding = false;
        mPendingCommands.clear();
        if (isBound()) {
            try {
                mPrintService.setClient(null);
            } catch (RemoteException re) {
                /* ignore */
            }
            mPrintService = null;
            mContext.unbindService(mServiceConnection);
        }
    }

    private void throwIfDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("Cannot interact with a destroyed service");
        }
    }

    private class RemoteServiceConneciton implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mDestroyed || !mBinding) {
                return;
            }
            mBinding = false;
            mPrintService = IPrintService.Stub.asInterface(service);
            try {
                mPrintService.setClient(mPrintServiceClient);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error setting client for: " + service, re);
                handleDestroy();
                return;
            }
            final int pendingCommandCount = mPendingCommands.size();
            for (int i = 0; i < pendingCommandCount; i++) {
                Runnable pendingCommand = mPendingCommands.get(i);
                pendingCommand.run();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinding = true;
        }
    }

    private final class MyHandler extends Handler {
        public static final int MSG_ALL_PRINT_JOBS_HANDLED = 1;
        public static final int MSG_REQUEST_CANCEL_PRINT_JOB = 2;
        public static final int MSG_PRINT_JOB_QUEUED = 3;
        public static final int MSG_START_PRINTER_DISCOVERY = 4;
        public static final int MSG_STOP_PRINTER_DISCOVERY = 5;
        public static final int MSG_DESTROY = 6;

        public MyHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_ALL_PRINT_JOBS_HANDLED: {
                    handleOnAllPrintJobsHandled();
                } break;

                case MSG_REQUEST_CANCEL_PRINT_JOB: {
                    PrintJobInfo printJob = (PrintJobInfo) message.obj;
                    handleOnRequestCancelPrintJob(printJob);
                } break;

                case MSG_PRINT_JOB_QUEUED: {
                    PrintJobInfo printJob = (PrintJobInfo) message.obj;
                    handleOnPrintJobQueued(printJob);
                } break;

                case MSG_START_PRINTER_DISCOVERY: {
                    IPrinterDiscoveryObserver observer = (IPrinterDiscoveryObserver) message.obj;
                    handleOnStartPrinterDiscovery(new SecurePrinterDiscoveryObserver(
                            mComponentName, observer));
                } break;

                case MSG_STOP_PRINTER_DISCOVERY: {
                    handleStopPrinterDiscovery();
                } break;

                case MSG_DESTROY: {
                    handleDestroy();
                } break;
            }
        }
    }

    private static final class RemotePrintServiceClient extends IPrintServiceClient.Stub {
        private final WeakReference<RemotePrintService> mWeakService;

        public RemotePrintServiceClient(RemotePrintService service) {
            mWeakService = new WeakReference<RemotePrintService>(service);
        }

        @Override
        public List<PrintJobInfo> getPrintJobInfos() {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return service.mSpooler.getPrintJobInfos(service.mComponentName,
                            PrintJobInfo.STATE_ANY_VISIBLE_TO_CLIENTS, PrintManager.APP_ID_ANY);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return null;
        }

        @Override
        public PrintJobInfo getPrintJobInfo(int printJobId) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return service.mSpooler.getPrintJobInfo(printJobId,
                            PrintManager.APP_ID_ANY);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return null;
        }

        @Override
        public boolean setPrintJobState(int printJobId, int state) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return service.mSpooler.setPrintJobState(printJobId, state);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return false;
        }

        @Override
        public boolean setPrintJobTag(int printJobId, String tag) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return service.mSpooler.setPrintJobTag(printJobId, tag);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return false;
        }

        @Override
        public void writePrintJobData(ParcelFileDescriptor fd, int printJobId) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mSpooler.writePrintJobData(fd, printJobId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }

    private static final class SecurePrinterDiscoveryObserver
            extends IPrinterDiscoveryObserver.Stub {
        private final ComponentName mComponentName;

        private final IPrinterDiscoveryObserver mDecoratedObsever;

        public SecurePrinterDiscoveryObserver(ComponentName componentName,
                IPrinterDiscoveryObserver observer) {
            mComponentName = componentName;
            mDecoratedObsever = observer;
        }

        @Override
        public void addDiscoveredPrinters(List<PrinterInfo> printers) {
            throwIfPrinterIdsForPrinterInfoTampered(printers);
            try {
                mDecoratedObsever.addDiscoveredPrinters(printers);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error delegating to addDiscoveredPrinters", re);
            }
        }

        @Override
        public void removeDiscoveredPrinters(List<PrinterId> printerIds) {
            throwIfPrinterIdsTampered(printerIds);
            try {
                mDecoratedObsever.removeDiscoveredPrinters(printerIds);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error delegating to removeDiscoveredPrinters", re);
            }
        }

        private void throwIfPrinterIdsForPrinterInfoTampered(
                List<PrinterInfo> printerInfos) {
            final int printerInfoCount = printerInfos.size();
            for (int i = 0; i < printerInfoCount; i++) {
                PrinterId printerId = printerInfos.get(i).getId();
                throwIfPrinterIdTampered(printerId);
            }
        }

        private void throwIfPrinterIdsTampered(List<PrinterId> printerIds) {
            final int printerIdCount = printerIds.size();
            for (int i = 0; i < printerIdCount; i++) {
                PrinterId printerId = printerIds.get(i);
                throwIfPrinterIdTampered(printerId);
            }
        }

        private void throwIfPrinterIdTampered(PrinterId printerId) {
            if (printerId == null || printerId.getService() == null
                    || !printerId.getService().equals(mComponentName)) {
                throw new IllegalArgumentException("Invalid printer id: " + printerId);
            }
        }
    }
}
