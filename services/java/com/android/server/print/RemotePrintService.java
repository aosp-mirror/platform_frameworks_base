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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
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
final class RemotePrintService implements DeathRecipient {

    private static final String LOG_TAG = "RemotePrintService";

    private static final boolean DEBUG = true && Build.IS_DEBUGGABLE;

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

    private boolean mAllPrintJobsHandled;

    private boolean mHasPrinterDiscoverySession;

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
        mAllPrintJobsHandled = false;
        mHasPrinterDiscoverySession = false;
        mDestroyed = true;
    }

    public void onAllPrintJobsHandled() {
        mHandler.sendEmptyMessage(MyHandler.MSG_ON_ALL_PRINT_JOBS_HANDLED);
    }

    @Override
    public void binderDied() {
        mHandler.sendEmptyMessage(MyHandler.MSG_BINDER_DIED);
    }

    private void handleBinderDied() {
        mAllPrintJobsHandled = false;
        mHasPrinterDiscoverySession = false;
        mPendingCommands.clear();
        ensureUnbound();
    }

    private void handleOnAllPrintJobsHandled() {
        throwIfDestroyed();

        mAllPrintJobsHandled = true;

        if (isBound()) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] handleOnAllPrintJobsHandled()");
            }

            // If the service has a printer discovery session
            // created we should not disconnect from it just yet.
            if (!mHasPrinterDiscoverySession) {
                ensureUnbound();
            }
        }
    }

    public void onRequestCancelPrintJob(PrintJobInfo printJob) {
        mHandler.obtainMessage(MyHandler.MSG_ON_REQUEST_CANCEL_PRINT_JOB,
                printJob).sendToTarget();
    }

    private void handleRequestCancelPrintJob(final PrintJobInfo printJob) {
        throwIfDestroyed();
        // If we are not bound, then we have no print jobs to handle
        // which means that there are no print jobs to be cancelled.
        if (isBound()) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] handleRequestCancelPrintJob()");
            }
            try {
                mPrintService.requestCancelPrintJob(printJob);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error canceling a pring job.", re);
            }
        }
    }

    public void onPrintJobQueued(PrintJobInfo printJob) {
        mHandler.obtainMessage(MyHandler.MSG_ON_PRINT_JOB_QUEUED,
                printJob).sendToTarget();
    }

    private void handleOnPrintJobQueued(final PrintJobInfo printJob) {
        throwIfDestroyed();

        mAllPrintJobsHandled = false;

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

    public void createPrinterDiscoverySession() {
        mHandler.sendEmptyMessage(MyHandler.MSG_CREATE_PRINTER_DISCOVERY_SESSION);
    }

    private void handleCreatePrinterDiscoverySession() {
        throwIfDestroyed();
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleCreatePrinterDiscoverySession();
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] createPrinterDiscoverySession()");
            }
            try {
                mPrintService.createPrinterDiscoverySession();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error creating printer dicovery session.", re);
            }

            mHasPrinterDiscoverySession = true;
        }
    }

    public void destroyPrinterDiscoverySession() {
        mHandler.sendEmptyMessage(MyHandler.MSG_DESTROY_PRINTER_DISCOVERY_SESSION);
    }

    private void handleDestroyPrinterDiscoverySession() {
        throwIfDestroyed();
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleDestroyPrinterDiscoverySession();
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] destroyPrinterDiscoverySession()");
            }

            mHasPrinterDiscoverySession = false;

            try {
                mPrintService.destroyPrinterDiscoverySession();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error destroying printer dicovery session.", re);
            }

            // If the service has no print jobs and no active discovery
            // session anymore we should disconnect from it.
            if (mAllPrintJobsHandled) {
                ensureUnbound();
            }
        }
    }

    public void startPrinterDiscovery(List<PrinterId> priorityList) {
        mHandler.obtainMessage(MyHandler.MSG_START_PRINTER_DISCOVERY,
                priorityList).sendToTarget();
    }

    private void handleStartPrinterDiscovery(final List<PrinterId> priorityList) {
        throwIfDestroyed();
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleStartPrinterDiscovery(priorityList);
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] startPrinterDiscovery()");
            }
            try {
                mPrintService.startPrinterDiscovery(priorityList);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error starting printer dicovery.", re);
            }
        }
    }

    public void stopPrinterDiscovery() {
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
                Slog.i(LOG_TAG, "[user: " + mUserId + "] stopPrinterDiscovery()");
            }
            try {
                mPrintService.stopPrinterDiscovery();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error stopping printer dicovery.", re);
            }
        }
    }

    public void requestPrinterUpdate(PrinterId printerId) {
        mHandler.obtainMessage(MyHandler.MSG_REQUEST_PRINTER_UPDATE,
                printerId).sendToTarget();
    }

    private void handleRequestPrinterUpdate(final PrinterId printerId) {
        throwIfDestroyed();
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleRequestPrinterUpdate(printerId);
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] requestPrinterUpdate()");
            }
            try {
                mPrintService.requestPrinterUpdate(printerId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error requesting a printer update.", re);
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
            mPrintService.asBinder().unlinkToDeath(this, 0);
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
                service.linkToDeath(RemotePrintService.this, 0);
            } catch (RemoteException re) {
                handleBinderDied();
                return;
            }
            try {
                mPrintService.setClient(mPrintServiceClient);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error setting client for: " + service, re);
                handleBinderDied();
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
        public static final int MSG_CREATE_PRINTER_DISCOVERY_SESSION = 1;
        public static final int MSG_DESTROY_PRINTER_DISCOVERY_SESSION = 2;
        public static final int MSG_START_PRINTER_DISCOVERY = 3;
        public static final int MSG_STOP_PRINTER_DISCOVERY = 4;
        public static final int MSG_REQUEST_PRINTER_UPDATE = 5;
        public static final int MSG_ON_ALL_PRINT_JOBS_HANDLED = 6;
        public static final int MSG_ON_REQUEST_CANCEL_PRINT_JOB = 7;
        public static final int MSG_ON_PRINT_JOB_QUEUED = 8;
        public static final int MSG_DESTROY = 9;
        public static final int MSG_BINDER_DIED = 10;

        public MyHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_CREATE_PRINTER_DISCOVERY_SESSION: {
                    handleCreatePrinterDiscoverySession();
                } break;

                case MSG_DESTROY_PRINTER_DISCOVERY_SESSION: {
                    handleDestroyPrinterDiscoverySession();
                } break;

                case MSG_START_PRINTER_DISCOVERY: {
                    List<PrinterId> priorityList = (ArrayList<PrinterId>) message.obj;
                    handleStartPrinterDiscovery(priorityList);
                } break;

                case MSG_STOP_PRINTER_DISCOVERY: {
                    handleStopPrinterDiscovery();
                } break;

                case MSG_REQUEST_PRINTER_UPDATE: {
                    PrinterId printerId = (PrinterId) message.obj;
                    handleRequestPrinterUpdate(printerId);
                } break;

                case MSG_ON_ALL_PRINT_JOBS_HANDLED: {
                    handleOnAllPrintJobsHandled();
                } break;

                case MSG_ON_REQUEST_CANCEL_PRINT_JOB: {
                    PrintJobInfo printJob = (PrintJobInfo) message.obj;
                    handleRequestCancelPrintJob(printJob);
                } break;

                case MSG_ON_PRINT_JOB_QUEUED: {
                    PrintJobInfo printJob = (PrintJobInfo) message.obj;
                    handleOnPrintJobQueued(printJob);
                } break;

                case MSG_DESTROY: {
                    handleDestroy();
                } break;

                case MSG_BINDER_DIED: {
                    handleBinderDied();
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
        public boolean setPrintJobState(int printJobId, int state, String error) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return service.mSpooler.setPrintJobState(printJobId, state, error);
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

        @Override
        public void onPrintersAdded(List<PrinterInfo> printers) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                throwIfPrinterIdsForPrinterInfoTampered(service.mComponentName, printers);
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mSpooler.onPrintersAdded(printers);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void onPrintersRemoved(List<PrinterId> printerIds) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                throwIfPrinterIdsTampered(service.mComponentName, printerIds);
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mSpooler.onPrintersRemoved(printerIds);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void onPrintersUpdated(List<PrinterInfo> printers) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                throwIfPrinterIdsForPrinterInfoTampered(service.mComponentName, printers);
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mSpooler.onPrintersUpdated(printers);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        private void throwIfPrinterIdsForPrinterInfoTampered(ComponentName serviceName,
                List<PrinterInfo> printerInfos) {
            final int printerInfoCount = printerInfos.size();
            for (int i = 0; i < printerInfoCount; i++) {
                PrinterId printerId = printerInfos.get(i).getId();
                throwIfPrinterIdTampered(serviceName, printerId);
            }
        }

        private void throwIfPrinterIdsTampered(ComponentName serviceName,
                List<PrinterId> printerIds) {
            final int printerIdCount = printerIds.size();
            for (int i = 0; i < printerIdCount; i++) {
                PrinterId printerId = printerIds.get(i);
                throwIfPrinterIdTampered(serviceName, printerId);
            }
        }

        private void throwIfPrinterIdTampered(ComponentName serviceName, PrinterId printerId) {
            if (printerId == null || printerId.getServiceName() == null
                    || !printerId.getServiceName().equals(serviceName)) {
                throw new IllegalArgumentException("Invalid printer id: " + printerId);
            }
        }
    }
}
