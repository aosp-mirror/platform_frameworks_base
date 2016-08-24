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

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.print.IPrintSpooler;
import android.print.IPrintSpoolerCallbacks;
import android.print.IPrintSpoolerClient;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterId;
import android.printservice.PrintService;
import android.util.Slog;
import android.util.TimedRemoteCaller;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * This represents the remote print spooler as a local object to the
 * PrintManagerService. It is responsible to connecting to the remote
 * spooler if needed, to make the timed remote calls, to handle
 * remote exceptions, and to bind/unbind to the remote instance as
 * needed.
 *
 * The calls might be blocking and need the main thread of to be unblocked to finish. Hence do not
 * call this while holding any monitors that might need to be acquired the main thread.
 */
final class RemotePrintSpooler {

    private static final String LOG_TAG = "RemotePrintSpooler";

    private static final boolean DEBUG = false;

    private static final long BIND_SPOOLER_SERVICE_TIMEOUT =
            ("eng".equals(Build.TYPE)) ? 120000 : 10000;

    private final Object mLock = new Object();

    private final GetPrintJobInfosCaller mGetPrintJobInfosCaller = new GetPrintJobInfosCaller();

    private final GetPrintJobInfoCaller mGetPrintJobInfoCaller = new GetPrintJobInfoCaller();

    private final SetPrintJobStateCaller mSetPrintJobStatusCaller = new SetPrintJobStateCaller();

    private final SetPrintJobTagCaller mSetPrintJobTagCaller = new SetPrintJobTagCaller();

    private final OnCustomPrinterIconLoadedCaller mCustomPrinterIconLoadedCaller =
            new OnCustomPrinterIconLoadedCaller();

    private final ClearCustomPrinterIconCacheCaller mClearCustomPrinterIconCache =
            new ClearCustomPrinterIconCacheCaller();

    private final GetCustomPrinterIconCaller mGetCustomPrinterIconCaller =
            new GetCustomPrinterIconCaller();

    private final ServiceConnection mServiceConnection = new MyServiceConnection();

    private final Context mContext;

    private final UserHandle mUserHandle;

    private final PrintSpoolerClient mClient;

    private final Intent mIntent;

    private final PrintSpoolerCallbacks mCallbacks;

    private boolean mIsLowPriority;

    private IPrintSpooler mRemoteInstance;

    private boolean mDestroyed;

    private boolean mCanUnbind;

    public static interface PrintSpoolerCallbacks {
        public void onPrintJobQueued(PrintJobInfo printJob);
        public void onAllPrintJobsForServiceHandled(ComponentName printService);
        public void onPrintJobStateChanged(PrintJobInfo printJob);
    }

    public RemotePrintSpooler(Context context, int userId, boolean lowPriority,
            PrintSpoolerCallbacks callbacks) {
        mContext = context;
        mUserHandle = new UserHandle(userId);
        mCallbacks = callbacks;
        mIsLowPriority = lowPriority;
        mClient = new PrintSpoolerClient(this);
        mIntent = new Intent();
        mIntent.setComponent(new ComponentName(PrintManager.PRINT_SPOOLER_PACKAGE_NAME,
                PrintManager.PRINT_SPOOLER_PACKAGE_NAME + ".model.PrintSpoolerService"));
    }

    public void increasePriority() {
        if (mIsLowPriority) {
            mIsLowPriority = false;

            synchronized (mLock) {
                throwIfDestroyedLocked();

                while (!mCanUnbind) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        Slog.e(LOG_TAG, "Interrupted while waiting for operation to complete");
                    }
                }

                if (DEBUG) {
                    Slog.i(LOG_TAG, "Unbinding as previous binding was low priority");
                }

                unbindLocked();
            }
        }
    }

    public final List<PrintJobInfo> getPrintJobInfos(ComponentName componentName, int state,
            int appId) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            return mGetPrintJobInfosCaller.getPrintJobInfos(getRemoteInstanceLazy(),
                    componentName, state, appId);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error getting print jobs.", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error getting print jobs.", te);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] getPrintJobInfos()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
        return null;
    }

    public final void createPrintJob(PrintJobInfo printJob) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            getRemoteInstanceLazy().createPrintJob(printJob);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error creating print job.", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error creating print job.", te);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] createPrintJob()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    public final void writePrintJobData(ParcelFileDescriptor fd, PrintJobId printJobId) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            getRemoteInstanceLazy().writePrintJobData(fd, printJobId);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error writing print job data.", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error writing print job data.", te);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] writePrintJobData()");
            }
            // We passed the file descriptor across and now the other
            // side is responsible to close it, so close the local copy.
            IoUtils.closeQuietly(fd);
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    public final PrintJobInfo getPrintJobInfo(PrintJobId printJobId, int appId) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            return mGetPrintJobInfoCaller.getPrintJobInfo(getRemoteInstanceLazy(),
                    printJobId, appId);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error getting print job info.", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error getting print job info.", te);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] getPrintJobInfo()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
        return null;
    }

    public final boolean setPrintJobState(PrintJobId printJobId, int state, String error) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            return mSetPrintJobStatusCaller.setPrintJobState(getRemoteInstanceLazy(),
                    printJobId, state, error);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error setting print job state.", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error setting print job state.", te);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] setPrintJobState()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
        return false;
    }

    /**
     * Set progress of a print job.
     *
     * @param printJobId The print job to update
     * @param progress The new progress
     */
    public final void setProgress(@NonNull PrintJobId printJobId,
            @FloatRange(from=0.0, to=1.0) float progress) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            getRemoteInstanceLazy().setProgress(printJobId, progress);
        } catch (RemoteException|TimeoutException re) {
            Slog.e(LOG_TAG, "Error setting progress.", re);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] setProgress()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    /**
     * Set status of a print job.
     *
     * @param printJobId The print job to update
     * @param status The new status
     */
    public final void setStatus(@NonNull PrintJobId printJobId, @Nullable CharSequence status) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            getRemoteInstanceLazy().setStatus(printJobId, status);
        } catch (RemoteException|TimeoutException re) {
            Slog.e(LOG_TAG, "Error setting status.", re);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] setStatus()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    /**
     * Set status of a print job.
     *
     * @param printJobId The print job to update
     * @param status The new status as a string resource
     * @param appPackageName The app package name the string res belongs to
     */
    public final void setStatus(@NonNull PrintJobId printJobId, @StringRes int status,
            @NonNull CharSequence appPackageName) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            getRemoteInstanceLazy().setStatusRes(printJobId, status, appPackageName);
        } catch (RemoteException|TimeoutException re) {
            Slog.e(LOG_TAG, "Error setting status.", re);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] setStatus()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    /**
     * Handle that a custom icon for a printer was loaded.
     *
     * @param printerId the id of the printer the icon belongs to
     * @param icon the icon that was loaded
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon()
     */
    public final void onCustomPrinterIconLoaded(@NonNull PrinterId printerId,
            @Nullable Icon icon) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            mCustomPrinterIconLoadedCaller.onCustomPrinterIconLoaded(getRemoteInstanceLazy(),
                    printerId, icon);
        } catch (RemoteException|TimeoutException re) {
            Slog.e(LOG_TAG, "Error loading new custom printer icon.", re);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG,
                        "[user: " + mUserHandle.getIdentifier() + "] onCustomPrinterIconLoaded()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    /**
     * Get the custom icon for a printer. If the icon is not cached, the icon is
     * requested asynchronously. Once it is available the printer is updated.
     *
     * @param printerId the id of the printer the icon should be loaded for
     * @return the custom icon to be used for the printer or null if the icon is
     *         not yet available
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon()
     */
    public final @Nullable Icon getCustomPrinterIcon(@NonNull PrinterId printerId) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            return mGetCustomPrinterIconCaller.getCustomPrinterIcon(getRemoteInstanceLazy(),
                    printerId);
        } catch (RemoteException|TimeoutException re) {
            Slog.e(LOG_TAG, "Error getting custom printer icon.", re);
            return null;
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG,
                        "[user: " + mUserHandle.getIdentifier() + "] getCustomPrinterIcon()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    /**
     * Clear the custom printer icon cache
     */
    public void clearCustomPrinterIconCache() {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            mClearCustomPrinterIconCache.clearCustomPrinterIconCache(getRemoteInstanceLazy());
        } catch (RemoteException|TimeoutException re) {
            Slog.e(LOG_TAG, "Error clearing custom printer icon cache.", re);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG,
                        "[user: " + mUserHandle.getIdentifier()
                                + "] clearCustomPrinterIconCache()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    public final boolean setPrintJobTag(PrintJobId printJobId, String tag) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            return mSetPrintJobTagCaller.setPrintJobTag(getRemoteInstanceLazy(),
                    printJobId, tag);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error setting print job tag.", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error setting print job tag.", te);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] setPrintJobTag()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
        return false;
    }

    public final void setPrintJobCancelling(PrintJobId printJobId, boolean cancelling) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            getRemoteInstanceLazy().setPrintJobCancelling(printJobId,
                    cancelling);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error setting print job cancelling.", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error setting print job cancelling.", te);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier()
                        + "] setPrintJobCancelling()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    /**
     * Remove all approved {@link PrintService print services} that are not in the given set.
     *
     * @param servicesToKeep The {@link ComponentName names } of the services to keep
     */
    public final void pruneApprovedPrintServices(List<ComponentName> servicesToKeep) {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            getRemoteInstanceLazy().pruneApprovedPrintServices(servicesToKeep);
        } catch (RemoteException|TimeoutException re) {
            Slog.e(LOG_TAG, "Error pruning approved print services.", re);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier()
                        + "] pruneApprovedPrintServices()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    public final void removeObsoletePrintJobs() {
        throwIfCalledOnMainThread();
        synchronized (mLock) {
            throwIfDestroyedLocked();
            mCanUnbind = false;
        }
        try {
            getRemoteInstanceLazy().removeObsoletePrintJobs();
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error removing obsolete print jobs .", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error removing obsolete print jobs .", te);
        } finally {
            if (DEBUG) {
                Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier()
                        + "] removeObsoletePrintJobs()");
            }
            synchronized (mLock) {
                mCanUnbind = true;
                mLock.notifyAll();
            }
        }
    }

    public final void destroy() {
        throwIfCalledOnMainThread();
        if (DEBUG) {
            Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] destroy()");
        }
        synchronized (mLock) {
            throwIfDestroyedLocked();
            unbindLocked();
            mDestroyed = true;
            mCanUnbind = false;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.append(prefix).append("destroyed=")
                    .append(String.valueOf(mDestroyed)).println();
            pw.append(prefix).append("bound=")
                    .append((mRemoteInstance != null) ? "true" : "false").println();

            pw.flush();

            try {
                getRemoteInstanceLazy().asBinder().dump(fd, new String[]{prefix});
            } catch (TimeoutException te) {
                /* ignore */
            } catch (RemoteException re) {
                /* ignore */
            }
        }
    }

    private void onAllPrintJobsHandled() {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            unbindLocked();
        }
    }

    private void onPrintJobStateChanged(PrintJobInfo printJob) {
        mCallbacks.onPrintJobStateChanged(printJob);
    }

    private IPrintSpooler getRemoteInstanceLazy() throws TimeoutException {
        synchronized (mLock) {
            if (mRemoteInstance != null) {
                return mRemoteInstance;
            }
            bindLocked();
            return mRemoteInstance;
        }
    }

    private void bindLocked() throws TimeoutException {
        if (mRemoteInstance != null) {
            return;
        }
        if (DEBUG) {
            Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] bindLocked() " +
                    (mIsLowPriority ? "low priority" : ""));
        }

        int flags;
        if (mIsLowPriority) {
            flags = Context.BIND_AUTO_CREATE;
        } else {
            flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE;
        }

        mContext.bindServiceAsUser(mIntent, mServiceConnection, flags, mUserHandle);

        final long startMillis = SystemClock.uptimeMillis();
        while (true) {
            if (mRemoteInstance != null) {
                break;
            }
            final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
            final long remainingMillis = BIND_SPOOLER_SERVICE_TIMEOUT - elapsedMillis;
            if (remainingMillis <= 0) {
                throw new TimeoutException("Cannot get spooler!");
            }
            try {
                mLock.wait(remainingMillis);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }

        mCanUnbind = true;
        mLock.notifyAll();
    }

    private void unbindLocked() {
        if (mRemoteInstance == null) {
            return;
        }
        while (true) {
            if (mCanUnbind) {
                if (DEBUG) {
                    Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] unbindLocked()");
                }
                clearClientLocked();
                mRemoteInstance = null;
                mContext.unbindService(mServiceConnection);
                return;
            }
            try {
                mLock.wait();
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }

    }

    private void setClientLocked() {
        try {
            mRemoteInstance.setClient(mClient);
        } catch (RemoteException re) {
            Slog.d(LOG_TAG, "Error setting print spooler client", re);
        }
    }

    private void clearClientLocked() {
        try {
            mRemoteInstance.setClient(null);
        } catch (RemoteException re) {
            Slog.d(LOG_TAG, "Error clearing print spooler client", re);
        }

    }

    private void throwIfDestroyedLocked() {
        if (mDestroyed) {
            throw new IllegalStateException("Cannot interact with a destroyed instance.");
        }
    }

    private void throwIfCalledOnMainThread() {
        if (Thread.currentThread() == mContext.getMainLooper().getThread()) {
            throw new RuntimeException("Cannot invoke on the main thread");
        }
    }

    private final class MyServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mRemoteInstance = IPrintSpooler.Stub.asInterface(service);
                setClientLocked();
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                clearClientLocked();
                mRemoteInstance = null;
            }
        }
    }

    private static final class GetPrintJobInfosCaller
            extends TimedRemoteCaller<List<PrintJobInfo>> {
        private final IPrintSpoolerCallbacks mCallback;

        public GetPrintJobInfosCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onGetPrintJobInfosResult(List<PrintJobInfo> printJobs, int sequence) {
                    onRemoteMethodResult(printJobs, sequence);
                }
            };
        }

        public List<PrintJobInfo> getPrintJobInfos(IPrintSpooler target,
                ComponentName componentName, int state, int appId)
                        throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.getPrintJobInfos(mCallback, componentName, state, appId, sequence);
            return getResultTimed(sequence);
        }
    }

    private static final class GetPrintJobInfoCaller extends TimedRemoteCaller<PrintJobInfo> {
        private final IPrintSpoolerCallbacks mCallback;

        public GetPrintJobInfoCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onGetPrintJobInfoResult(PrintJobInfo printJob, int sequence) {
                    onRemoteMethodResult(printJob, sequence);
                }
            };
        }

        public PrintJobInfo getPrintJobInfo(IPrintSpooler target, PrintJobId printJobId,
                int appId) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.getPrintJobInfo(printJobId, mCallback, appId, sequence);
            return getResultTimed(sequence);
        }
    }

    private static final class SetPrintJobStateCaller extends TimedRemoteCaller<Boolean> {
        private final IPrintSpoolerCallbacks mCallback;

        public SetPrintJobStateCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onSetPrintJobStateResult(boolean success, int sequence) {
                    onRemoteMethodResult(success, sequence);
                }
            };
        }

        public boolean setPrintJobState(IPrintSpooler target, PrintJobId printJobId,
                int status, String error) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.setPrintJobState(printJobId, status, error, mCallback, sequence);
            return getResultTimed(sequence);
        }
    }

    private static final class SetPrintJobTagCaller extends TimedRemoteCaller<Boolean> {
        private final IPrintSpoolerCallbacks mCallback;

        public SetPrintJobTagCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onSetPrintJobTagResult(boolean success, int sequence) {
                    onRemoteMethodResult(success, sequence);
                }
            };
        }

        public boolean setPrintJobTag(IPrintSpooler target, PrintJobId printJobId,
                String tag) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.setPrintJobTag(printJobId, tag, mCallback, sequence);
            return getResultTimed(sequence);
        }
    }

    private static final class OnCustomPrinterIconLoadedCaller extends TimedRemoteCaller<Void> {
        private final IPrintSpoolerCallbacks mCallback;

        public OnCustomPrinterIconLoadedCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onCustomPrinterIconCached(int sequence) {
                    onRemoteMethodResult(null, sequence);
                }
            };
        }

        public Void onCustomPrinterIconLoaded(IPrintSpooler target, PrinterId printerId,
                Icon icon) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.onCustomPrinterIconLoaded(printerId, icon, mCallback, sequence);
            return getResultTimed(sequence);
        }
    }

    private static final class ClearCustomPrinterIconCacheCaller extends TimedRemoteCaller<Void> {
        private final IPrintSpoolerCallbacks mCallback;

        public ClearCustomPrinterIconCacheCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void customPrinterIconCacheCleared(int sequence) {
                    onRemoteMethodResult(null, sequence);
                }
            };
        }

        public Void clearCustomPrinterIconCache(IPrintSpooler target)
                throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.clearCustomPrinterIconCache(mCallback, sequence);
            return getResultTimed(sequence);
        }
    }

    private static final class GetCustomPrinterIconCaller extends TimedRemoteCaller<Icon> {
        private final IPrintSpoolerCallbacks mCallback;

        public GetCustomPrinterIconCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onGetCustomPrinterIconResult(Icon icon, int sequence) {
                    onRemoteMethodResult(icon, sequence);
                }
            };
        }

        public Icon getCustomPrinterIcon(IPrintSpooler target, PrinterId printerId)
                throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.getCustomPrinterIcon(printerId, mCallback, sequence);
            return getResultTimed(sequence);
        }
    }

    private static abstract class BasePrintSpoolerServiceCallbacks
            extends IPrintSpoolerCallbacks.Stub {
        @Override
        public void onGetPrintJobInfosResult(List<PrintJobInfo> printJobIds, int sequence) {
            /* do nothing */
        }

        @Override
        public void onGetPrintJobInfoResult(PrintJobInfo printJob, int sequence) {
            /* do nothing */
        }

        @Override
        public void onCancelPrintJobResult(boolean canceled, int sequence) {
            /* do nothing */
        }

        @Override
        public void onSetPrintJobStateResult(boolean success, int sequece) {
            /* do nothing */
        }

        @Override
        public void onSetPrintJobTagResult(boolean success, int sequence) {
            /* do nothing */
        }

        @Override
        public void onCustomPrinterIconCached(int sequence) {
            /* do nothing */
        }

        @Override
        public void onGetCustomPrinterIconResult(@Nullable Icon icon, int sequence) {
            /* do nothing */
        }

        @Override
        public void customPrinterIconCacheCleared(int sequence) {
            /* do nothing */
        }
    }

    private static final class PrintSpoolerClient extends IPrintSpoolerClient.Stub {

        private final WeakReference<RemotePrintSpooler> mWeakSpooler;

        public PrintSpoolerClient(RemotePrintSpooler spooler) {
            mWeakSpooler = new WeakReference<RemotePrintSpooler>(spooler);
        }

        @Override
        public void onPrintJobQueued(PrintJobInfo printJob) {
            RemotePrintSpooler spooler = mWeakSpooler.get();
            if (spooler != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    spooler.mCallbacks.onPrintJobQueued(printJob);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void onAllPrintJobsForServiceHandled(ComponentName printService) {
            RemotePrintSpooler spooler = mWeakSpooler.get();
            if (spooler != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    spooler.mCallbacks.onAllPrintJobsForServiceHandled(printService);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void onAllPrintJobsHandled() {
            RemotePrintSpooler spooler = mWeakSpooler.get();
            if (spooler != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    spooler.onAllPrintJobsHandled();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }

        @Override
        public void onPrintJobStateChanged(PrintJobInfo printJob) {
            RemotePrintSpooler spooler = mWeakSpooler.get();
            if (spooler != null) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    spooler.onPrintJobStateChanged(printJob);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }
}
