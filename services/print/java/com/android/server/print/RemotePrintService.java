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

import static com.android.internal.print.DumpUtils.writePrinterId;
import static com.android.internal.util.dump.DumpUtils.writeComponentName;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ParceledListSlice;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.IPrintService;
import android.printservice.IPrintServiceClient;
import android.service.print.ActivePrintServiceProto;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.dump.DualDumpOutputStream;

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

    private static final boolean DEBUG = false;

    private final Object mLock = new Object();

    private final Context mContext;

    private final ComponentName mComponentName;

    private final Intent mIntent;

    private final RemotePrintSpooler mSpooler;

    private final PrintServiceCallbacks mCallbacks;

    private final int mUserId;

    private final List<Runnable> mPendingCommands = new ArrayList<Runnable>();

    private final ServiceConnection mServiceConnection = new RemoteServiceConneciton();

    private final RemotePrintServiceClient mPrintServiceClient;

    private IPrintService mPrintService;

    private boolean mBinding;

    private boolean mDestroyed;

    private boolean mHasActivePrintJobs;

    private boolean mHasPrinterDiscoverySession;

    private boolean mServiceDied;

    private List<PrinterId> mDiscoveryPriorityList;

    @GuardedBy("mLock")
    private List<PrinterId> mTrackedPrinterList;

    public static interface PrintServiceCallbacks {
        public void onPrintersAdded(List<PrinterInfo> printers);
        public void onPrintersRemoved(List<PrinterId> printerIds);
        public void onServiceDied(RemotePrintService service);

        /**
         * Handle that a custom icon for a printer was loaded.
         *
         * @param printerId the id of the printer the icon belongs to
         * @param icon the icon that was loaded
         * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon()
         */
        public void onCustomPrinterIconLoaded(PrinterId printerId, Icon icon);
    }

    public RemotePrintService(Context context, ComponentName componentName, int userId,
            RemotePrintSpooler spooler, PrintServiceCallbacks callbacks) {
        mContext = context;
        mCallbacks = callbacks;
        mComponentName = componentName;
        mIntent = new Intent().setComponent(mComponentName);
        mUserId = userId;
        mSpooler = spooler;
        mPrintServiceClient = new RemotePrintServiceClient(this);
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    public void destroy() {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleDestroy, this));
    }

    private void handleDestroy() {
        // Stop tracking printers.
        stopTrackingAllPrinters();

        // Stop printer discovery.
        if (mDiscoveryPriorityList != null) {
            handleStopPrinterDiscovery();
        }

        // Destroy the discovery session.
        if (mHasPrinterDiscoverySession) {
            handleDestroyPrinterDiscoverySession();
        }

        // Unbind.
        ensureUnbound();

        // Done
        mDestroyed = true;
    }

    @Override
    public void binderDied() {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleBinderDied, this));
    }

    private void handleBinderDied() {
        if (mPrintService != null) {
            mPrintService.asBinder().unlinkToDeath(this, 0);
        }

        mPrintService = null;
        mServiceDied = true;
        mCallbacks.onServiceDied(this);
    }

    public void onAllPrintJobsHandled() {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleOnAllPrintJobsHandled, this));
    }

    private void handleOnAllPrintJobsHandled() {
        mHasActivePrintJobs = false;
        if (!isBound()) {
            // The service is dead and neither has active jobs nor discovery
            // session, so ensure we are unbound since the service has no work.
            if (mServiceDied && !mHasPrinterDiscoverySession) {
                ensureUnbound();
                return;
            }
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleOnAllPrintJobsHandled();
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] onAllPrintJobsHandled()");
            }
            // If the service has a printer discovery session
            // created we should not disconnect from it just yet.
            if (!mHasPrinterDiscoverySession) {
                ensureUnbound();
            }
        }
    }

    public void onRequestCancelPrintJob(PrintJobInfo printJob) {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleRequestCancelPrintJob, this, printJob));
    }

    private void handleRequestCancelPrintJob(final PrintJobInfo printJob) {
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleRequestCancelPrintJob(printJob);
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] requestCancelPrintJob()");
            }
            try {
                mPrintService.requestCancelPrintJob(printJob);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error canceling a pring job.", re);
            }
        }
    }

    public void onPrintJobQueued(PrintJobInfo printJob) {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleOnPrintJobQueued, this, printJob));
    }

    private void handleOnPrintJobQueued(final PrintJobInfo printJob) {
        mHasActivePrintJobs = true;
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
                Slog.i(LOG_TAG, "[user: " + mUserId + "] onPrintJobQueued()");
            }
            try {
                mPrintService.onPrintJobQueued(printJob);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error announcing queued pring job.", re);
            }
        }
    }

    public void createPrinterDiscoverySession() {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleCreatePrinterDiscoverySession, this));
    }

    private void handleCreatePrinterDiscoverySession() {
        mHasPrinterDiscoverySession = true;
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
                Slog.e(LOG_TAG, "Error creating printer discovery session.", re);
            }
        }
    }

    public void destroyPrinterDiscoverySession() {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleDestroyPrinterDiscoverySession, this));
    }

    private void handleDestroyPrinterDiscoverySession() {
        mHasPrinterDiscoverySession = false;
        if (!isBound()) {
            // The service is dead and neither has active jobs nor discovery
            // session, so ensure we are unbound since the service has no work.
            if (mServiceDied && !mHasActivePrintJobs) {
                ensureUnbound();
                return;
            }
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
            try {
                mPrintService.destroyPrinterDiscoverySession();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error destroying printer dicovery session.", re);
            }
            // If the service has no print jobs and no active discovery
            // session anymore we should disconnect from it.
            if (!mHasActivePrintJobs) {
                ensureUnbound();
            }
        }
    }

    public void startPrinterDiscovery(List<PrinterId> priorityList) {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleStartPrinterDiscovery, this, priorityList));
    }

    private void handleStartPrinterDiscovery(final List<PrinterId> priorityList) {
        // Take a note that we are doing discovery.
        mDiscoveryPriorityList = new ArrayList<PrinterId>();
        if (priorityList != null) {
            mDiscoveryPriorityList.addAll(priorityList);
        }
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
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleStopPrinterDiscovery, this));
    }

    private void handleStopPrinterDiscovery() {
        // We are not doing discovery anymore.
        mDiscoveryPriorityList = null;
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

            // Stop tracking printers.
            stopTrackingAllPrinters();

            try {
                mPrintService.stopPrinterDiscovery();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error stopping printer discovery.", re);
            }
        }
    }

    public void validatePrinters(List<PrinterId> printerIds) {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleValidatePrinters, this, printerIds));
    }

    private void handleValidatePrinters(final List<PrinterId> printerIds) {
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleValidatePrinters(printerIds);
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] validatePrinters()");
            }
            try {
                mPrintService.validatePrinters(printerIds);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error requesting printers validation.", re);
            }
        }
    }

    public void startPrinterStateTracking(@NonNull PrinterId printerId) {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleStartPrinterStateTracking, this, printerId));
    }

    /**
     * Queue a request for a custom printer icon for a printer.
     *
     * @param printerId the id of the printer the icon should be loaded for
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon
     */
    public void requestCustomPrinterIcon(@NonNull PrinterId printerId) {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleRequestCustomPrinterIcon, this, printerId));
    }

    /**
     * Request a custom printer icon for a printer.
     *
     * @param printerId the id of the printer the icon should be loaded for
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon
     */
    private void handleRequestCustomPrinterIcon(@NonNull PrinterId printerId) {
        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(() -> handleRequestCustomPrinterIcon(printerId));
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] requestCustomPrinterIcon()");
            }

            try {
                mPrintService.requestCustomPrinterIcon(printerId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error requesting icon for " + printerId, re);
            }
        }
    }

    private void handleStartPrinterStateTracking(final @NonNull PrinterId printerId) {
        synchronized (mLock) {
            // Take a note we are tracking the printer.
            if (mTrackedPrinterList == null) {
                mTrackedPrinterList = new ArrayList<PrinterId>();
            }
            mTrackedPrinterList.add(printerId);
        }

        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleStartPrinterStateTracking(printerId);
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] startPrinterTracking()");
            }
            try {
                mPrintService.startPrinterStateTracking(printerId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error requesting start printer tracking.", re);
            }
        }
    }

    public void stopPrinterStateTracking(PrinterId printerId) {
        Handler.getMain().sendMessage(obtainMessage(
                RemotePrintService::handleStopPrinterStateTracking, this, printerId));
    }

    private void handleStopPrinterStateTracking(final PrinterId printerId) {
        synchronized (mLock) {
            // We are no longer tracking the printer.
            if (mTrackedPrinterList == null || !mTrackedPrinterList.remove(printerId)) {
                return;
            }
            if (mTrackedPrinterList.isEmpty()) {
                mTrackedPrinterList = null;
            }
        }

        if (!isBound()) {
            ensureBound();
            mPendingCommands.add(new Runnable() {
                @Override
                public void run() {
                    handleStopPrinterStateTracking(printerId);
                }
            });
        } else {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] stopPrinterTracking()");
            }
            try {
                mPrintService.stopPrinterStateTracking(printerId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error requesting stop printer tracking.", re);
            }
        }
    }

    private void stopTrackingAllPrinters() {
        synchronized (mLock) {
            if (mTrackedPrinterList == null) {
                return;
            }
            final int trackedPrinterCount = mTrackedPrinterList.size();
            for (int i = trackedPrinterCount - 1; i >= 0; i--) {
                PrinterId printerId = mTrackedPrinterList.get(i);
                if (printerId.getServiceName().equals(mComponentName)) {
                    handleStopPrinterStateTracking(printerId);
                }
            }
        }
    }

    public void dump(@NonNull DualDumpOutputStream proto) {
        writeComponentName(proto, "component_name", ActivePrintServiceProto.COMPONENT_NAME,
                mComponentName);

        proto.write("is_destroyed", ActivePrintServiceProto.IS_DESTROYED, mDestroyed);
        proto.write("is_bound", ActivePrintServiceProto.IS_BOUND, isBound());
        proto.write("has_discovery_session", ActivePrintServiceProto.HAS_DISCOVERY_SESSION,
                mHasPrinterDiscoverySession);
        proto.write("has_active_print_jobs", ActivePrintServiceProto.HAS_ACTIVE_PRINT_JOBS,
                mHasActivePrintJobs);
        proto.write("is_discovering_printers", ActivePrintServiceProto.IS_DISCOVERING_PRINTERS,
                mDiscoveryPriorityList != null);

        synchronized (mLock) {
            if (mTrackedPrinterList != null) {
                int numTrackedPrinters = mTrackedPrinterList.size();
                for (int i = 0; i < numTrackedPrinters; i++) {
                    writePrinterId(proto, "tracked_printers",
                            ActivePrintServiceProto.TRACKED_PRINTERS, mTrackedPrinterList.get(i));
                }
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

        boolean wasBound = mContext.bindServiceAsUser(mIntent, mServiceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
                        | Context.BIND_INCLUDE_CAPABILITIES | Context.BIND_ALLOW_INSTANT
                        | Context.BIND_DENY_ACTIVITY_STARTS_PRE_34,
                new UserHandle(mUserId));

        if (!wasBound) {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserId + "] could not bind to " + mIntent);
            }
            mBinding = false;

            if (!mServiceDied) {
                handleBinderDied();
            }
        }
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
        mHasActivePrintJobs = false;
        mHasPrinterDiscoverySession = false;
        mDiscoveryPriorityList = null;

        synchronized (mLock) {
            mTrackedPrinterList = null;
        }

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

    private class RemoteServiceConneciton implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (mDestroyed || !mBinding) {
                mContext.unbindService(mServiceConnection);
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
            // If the service died and there is a discovery session, recreate it.
            if (mServiceDied && mHasPrinterDiscoverySession) {
                handleCreatePrinterDiscoverySession();
            }
            // If the service died and there is discovery started, restart it.
            if (mServiceDied && mDiscoveryPriorityList != null) {
                handleStartPrinterDiscovery(mDiscoveryPriorityList);
            }
            synchronized (mLock) {
                // If the service died and printers were tracked, start tracking.
                if (mServiceDied && mTrackedPrinterList != null) {
                    final int trackedPrinterCount = mTrackedPrinterList.size();
                    for (int i = 0; i < trackedPrinterCount; i++) {
                        handleStartPrinterStateTracking(mTrackedPrinterList.get(i));
                    }
                }
            }
            // Finally, do all the pending work.
            while (!mPendingCommands.isEmpty()) {
                Runnable pendingCommand = mPendingCommands.remove(0);
                pendingCommand.run();
            }
            // We did a best effort to get to the last state if we crashed.
            // If we do not have print jobs and no discovery is in progress,
            // then no need to be bound.
            if (!mHasPrinterDiscoverySession && !mHasActivePrintJobs) {
                ensureUnbound();
            }
            mServiceDied = false;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinding = true;
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
                            PrintJobInfo.STATE_ANY_SCHEDULED, PrintManager.APP_ID_ANY);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return null;
        }

        @Override
        public PrintJobInfo getPrintJobInfo(PrintJobId printJobId) {
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
        public boolean setPrintJobState(PrintJobId printJobId, int state, String error) {
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
        public boolean setPrintJobTag(PrintJobId printJobId, String tag) {
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
        public void writePrintJobData(ParcelFileDescriptor fd, PrintJobId printJobId) {
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
        public void setProgress(@NonNull PrintJobId printJobId,
                @FloatRange(from=0.0, to=1.0) float progress) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mSpooler.setProgress(printJobId, progress);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void setStatus(@NonNull PrintJobId printJobId, @Nullable CharSequence status) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mSpooler.setStatus(printJobId, status);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void setStatusRes(@NonNull PrintJobId printJobId, @StringRes int status,
                @NonNull CharSequence appPackageName) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mSpooler.setStatus(printJobId, status, appPackageName);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void onPrintersAdded(ParceledListSlice printers) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                List<PrinterInfo> addedPrinters = (List<PrinterInfo>) printers.getList();
                throwIfPrinterIdsForPrinterInfoTampered(service.mComponentName, addedPrinters);
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mCallbacks.onPrintersAdded(addedPrinters);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void onPrintersRemoved(ParceledListSlice printerIds) {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                List<PrinterId> removedPrinterIds = (List<PrinterId>) printerIds.getList();
                throwIfPrinterIdsTampered(service.mComponentName, removedPrinterIds);
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mCallbacks.onPrintersRemoved(removedPrinterIds);
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
            if (printerId == null || !printerId.getServiceName().equals(serviceName)) {
                throw new IllegalArgumentException("Invalid printer id: " + printerId);
            }
        }

        @Override
        public void onCustomPrinterIconLoaded(PrinterId printerId, Icon icon)
                throws RemoteException {
            RemotePrintService service = mWeakService.get();
            if (service != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    service.mCallbacks.onCustomPrinterIconLoaded(printerId, icon);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }
}
