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
import android.os.IBinder.DeathRecipient;
import android.print.IPrintAdapter;
import android.print.IPrintClient;
import android.print.IPrintSpoolerService;
import android.print.IPrintSpoolerServiceCallbacks;
import android.print.PrintAttributes;
import android.print.PrintJobInfo;
import android.util.Slog;
import android.util.TimedRemoteCaller;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * This represents the remote print spooler as a local object to the
 * PrintManagerSerivce. It is responsible to connecting to the remove
 * spooler if needed, to make the timed out remote calls, and to handle
 * remove exceptions.
 */
final class RemoteSpooler implements ServiceConnection, DeathRecipient {

    private static final String LOG_TAG = "Spooler";

    private static final long BIND_SPOOLER_SERVICE_TIMEOUT = 10000;

    private final Object mLock = new Object();

    private final Context mContext;

    private final Intent mIntent;

    private final GetPrintJobsCaller mGetPrintJobsCaller = new GetPrintJobsCaller();

    private final CreatePrintJobCaller mCreatePrintJobCaller = new CreatePrintJobCaller();

    private final CancelPrintJobCaller mCancelPrintJobCaller = new CancelPrintJobCaller();

    private final GetPrintJobCaller mGetPrintJobCaller = new GetPrintJobCaller();

    private final SetPrintJobStateCaller mSetPrintJobStatusCaller = new SetPrintJobStateCaller();

    private final SetPrintJobTagCaller mSetPrintJobTagCaller = new SetPrintJobTagCaller();

    private IPrintSpoolerService mRemoteInterface;

    private int mUserId = UserHandle.USER_NULL;

    public RemoteSpooler(Context context) {
        mContext = context;
        mIntent = new Intent();
        mIntent.setComponent(new ComponentName("com.android.printspooler",
                "com.android.printspooler.PrintSpoolerService"));
    }

    public List<PrintJobInfo> getPrintJobs(ComponentName componentName, int state, int appId,
            int userId) {
        try {
            return mGetPrintJobsCaller.getPrintJobs(getRemoteInstance(userId),
                    componentName, state, appId);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error getting print jobs!", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error getting print jobs!", te);
        }
        return null;
    }

    public PrintJobInfo createPrintJob(String printJobName, IPrintClient client,
            IPrintAdapter printAdapter, PrintAttributes attributes, int appId, int userId) {
        try {
            return mCreatePrintJobCaller.createPrintJob(getRemoteInstance(userId),
                    printJobName, client, printAdapter, attributes, appId);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error creating print job!", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error creating print job!", te);
        }
        return null;
    }

    public boolean cancelPrintJob(int printJobId, int appId, int userId) {
        try {
            return mCancelPrintJobCaller.cancelPrintJob(getRemoteInstance(userId),
                    printJobId, appId);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error canceling print job!", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error canceling print job!", te);
        }
        return false;
    }

    public void writePrintJobData(ParcelFileDescriptor fd, int printJobId, int userId) {
        try {
            getRemoteInstance(userId).writePrintJobData(fd, printJobId);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error writing print job data!", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error writing print job data!", te);
        } finally {
            // We passed the file descriptor across and now the other
            // side is responsible to close it, so close the local copy.
            try {
                fd.close();
            } catch (IOException ioe) {
                /* ignore */
            }
        }
    }

    public PrintJobInfo getPrintJobInfo(int printJobId, int appId, int userId) {
        try {
            return mGetPrintJobCaller.getPrintJobInfo(getRemoteInstance(userId),
                    printJobId, appId);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error getting print job!", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error getting print job!", te);
        }
        return null;
    }

    public boolean setPrintJobState(int printJobId, int state, int userId) {
        try {
            return mSetPrintJobStatusCaller.setPrintJobState(getRemoteInstance(userId),
                    printJobId, state);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error setting print job status!", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error setting print job status!", te);
        }
        return false;
    }

    public boolean setPrintJobTag(int printJobId, String tag, int userId) {
        try {
            return mSetPrintJobTagCaller.setPrintJobTag(getRemoteInstance(userId),
                    printJobId, tag);
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error setting print job tag!", re);
        } catch (TimeoutException te) {
            Slog.e(LOG_TAG, "Error setting print job tag!", te);
        }
        return false;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        binderDied();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (mLock) {
            try {
                service.linkToDeath(this, 0);
                mRemoteInterface = IPrintSpoolerService.Stub.asInterface(service);
            } catch (RemoteException re) {
                /* ignore */
            }
        }
    }

    private IPrintSpoolerService getRemoteInstance(int userId) throws TimeoutException {
        synchronized (mLock) {
            if (mRemoteInterface != null && mUserId == userId) {
                return mRemoteInterface;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                if (mUserId != UserHandle.USER_NULL && mUserId != userId) {
                    unbind();
                }

                mContext.bindServiceAsUser(mIntent, this,
                        Context.BIND_AUTO_CREATE | Context.BIND_ALLOW_OOM_MANAGEMENT,
                        UserHandle.CURRENT);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            final long startMillis = SystemClock.uptimeMillis();
            while (true) {
                if (mRemoteInterface != null) {
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

            mUserId = userId;

            return mRemoteInterface;
        }
    }

    public void unbind() {
        synchronized (mLock) {
            if (mRemoteInterface != null) {
                mContext.unbindService(this);
                mRemoteInterface = null;
                mUserId = UserHandle.USER_NULL;
            }
        }
    }

    @Override
    public void binderDied() {
        synchronized (mLock) {
            if (mRemoteInterface != null) {
                mRemoteInterface.asBinder().unlinkToDeath(this, 0);
                mRemoteInterface = null;
            }
        }
    }

    private final class GetPrintJobsCaller extends TimedRemoteCaller<List<PrintJobInfo>> {
        private final IPrintSpoolerServiceCallbacks mCallback;

        public GetPrintJobsCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onGetPrintJobsResult(List<PrintJobInfo> printJobs, int sequence) {
                    onRemoteMethodResult(printJobs, sequence);
                }
            };
        }

        public List<PrintJobInfo> getPrintJobs(IPrintSpoolerService target,
                ComponentName componentName, int state, int appId)
                        throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.getPrintJobs(mCallback, componentName, state, appId, sequence);
            return getResultTimed(sequence);
        }
    }

    private final class CreatePrintJobCaller extends TimedRemoteCaller<PrintJobInfo> {
        private final IPrintSpoolerServiceCallbacks mCallback;

        public CreatePrintJobCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onCreatePrintJobResult(PrintJobInfo printJob, int sequence) {
                    onRemoteMethodResult(printJob, sequence);
                }
            };
        }

        public PrintJobInfo createPrintJob(IPrintSpoolerService target, String printJobName,
                IPrintClient client, IPrintAdapter printAdapter, PrintAttributes attributes,
                int appId) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.createPrintJob(printJobName, client, printAdapter, attributes,
                    mCallback, appId, sequence);
            return getResultTimed(sequence);
        }
    }

    private final class CancelPrintJobCaller extends TimedRemoteCaller<Boolean> {
        private final IPrintSpoolerServiceCallbacks mCallback;

        public CancelPrintJobCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onCancelPrintJobResult(boolean canceled, int sequence) {
                    onRemoteMethodResult(canceled, sequence);
                }
            };
        }

        public boolean cancelPrintJob(IPrintSpoolerService target, int printJobId,
                int appId) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.cancelPrintJob(printJobId, mCallback, appId, sequence);
            return getResultTimed(sequence);
        }
    }

    private final class GetPrintJobCaller extends TimedRemoteCaller<PrintJobInfo> {
        private final IPrintSpoolerServiceCallbacks mCallback;

        public GetPrintJobCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onGetPrintJobInfoResult(PrintJobInfo printJob, int sequence) {
                    onRemoteMethodResult(printJob, sequence);
                }
            };
        }

        public PrintJobInfo getPrintJobInfo(IPrintSpoolerService target, int printJobId,
                int appId) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.getPrintJob(printJobId, mCallback, appId, sequence);
            return getResultTimed(sequence);
        }
    }

    private final class SetPrintJobStateCaller extends TimedRemoteCaller<Boolean> {
        private final IPrintSpoolerServiceCallbacks mCallback;

        public SetPrintJobStateCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onSetPrintJobStateResult(boolean success, int sequence) {
                    onRemoteMethodResult(success, sequence);
                }
            };
        }

        public boolean setPrintJobState(IPrintSpoolerService target, int printJobId,
                int status) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.setPrintJobState(printJobId, status, mCallback, sequence);
            return getResultTimed(sequence);
        }
    }

    private final class SetPrintJobTagCaller extends TimedRemoteCaller<Boolean> {
        private final IPrintSpoolerServiceCallbacks mCallback;

        public SetPrintJobTagCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new BasePrintSpoolerServiceCallbacks() {
                @Override
                public void onSetPrintJobTagResult(boolean success, int sequence) {
                    onRemoteMethodResult(success, sequence);
                }
            };
        }

        public boolean setPrintJobTag(IPrintSpoolerService target, int printJobId,
                String tag) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.setPrintJobTag(printJobId, tag, mCallback, sequence);
            return getResultTimed(sequence);
        }
    }

    private abstract class BasePrintSpoolerServiceCallbacks
            extends IPrintSpoolerServiceCallbacks.Stub {
        @Override
        public void onGetPrintJobsResult(List<PrintJobInfo> printJobIds, int sequence) {
            /** do nothing */
        }

        @Override
        public void onGetPrintJobInfoResult(PrintJobInfo printJob, int sequence) {
            /** do nothing */
        }

        @Override
        public void onCreatePrintJobResult(PrintJobInfo printJob, int sequence) {
            /** do nothing */
        }

        @Override
        public void onCancelPrintJobResult(boolean canceled, int sequence) {
            /** do nothing */
        }

        @Override
        public void onSetPrintJobStateResult(boolean success, int sequece) {
            /** do nothing */
        }

        @Override
        public void onSetPrintJobTagResult(boolean success, int sequence) {
            /** do nothing */
        }
    }
}