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
import android.util.Slog;
import android.util.TimedRemoteCaller;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.TimeoutException;

import libcore.io.IoUtils;

/**
 * This represents the remote print spooler as a local object to the
 * PrintManagerService. It is responsible to connecting to the remote
 * spooler if needed, to make the timed remote calls, to handle
 * remote exceptions, and to bind/unbind to the remote instance as
 * needed.
 */
final class RemotePrintSpooler {

    private static final String LOG_TAG = "RemotePrintSpooler";

    private static final boolean DEBUG = false;

    private static final long BIND_SPOOLER_SERVICE_TIMEOUT = 10000;

    private final Object mLock = new Object();

    private final GetPrintJobInfosCaller mGetPrintJobInfosCaller = new GetPrintJobInfosCaller();

    private final GetPrintJobInfoCaller mGetPrintJobInfoCaller = new GetPrintJobInfoCaller();

    private final SetPrintJobStateCaller mSetPrintJobStatusCaller = new SetPrintJobStateCaller();

    private final SetPrintJobTagCaller mSetPrintJobTagCaller = new SetPrintJobTagCaller();

    private final ServiceConnection mServiceConnection = new MyServiceConnection();

    private final Context mContext;

    private final UserHandle mUserHandle;

    private final PrintSpoolerClient mClient;

    private final Intent mIntent;

    private final PrintSpoolerCallbacks mCallbacks;

    private IPrintSpooler mRemoteInstance;

    private boolean mDestroyed;

    private boolean mCanUnbind;

    public static interface PrintSpoolerCallbacks {
        public void onPrintJobQueued(PrintJobInfo printJob);
        public void onAllPrintJobsForServiceHandled(ComponentName printService);
        public void onPrintJobStateChanged(PrintJobInfo printJob);
    }

    public RemotePrintSpooler(Context context, int userId,
            PrintSpoolerCallbacks callbacks) {
        mContext = context;
        mUserHandle = new UserHandle(userId);
        mCallbacks = callbacks;
        mClient = new PrintSpoolerClient(this);
        mIntent = new Intent();
        mIntent.setComponent(new ComponentName("com.android.printspooler",
                "com.android.printspooler.model.PrintSpoolerService"));
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
            Slog.i(LOG_TAG, "[user: " + mUserHandle.getIdentifier() + "] bindLocked()");
        }

        mContext.bindServiceAsUser(mIntent, mServiceConnection,
                Context.BIND_AUTO_CREATE, mUserHandle);

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
