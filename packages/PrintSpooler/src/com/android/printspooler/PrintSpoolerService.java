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

import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.IPrintClient;
import android.print.IPrintDocumentAdapter;
import android.print.IPrintSpooler;
import android.print.IPrintSpoolerCallbacks;
import android.print.IPrintSpoolerClient;
import android.print.IPrinterDiscoverySessionObserver;
import android.print.PrintAttributes;
import android.print.PrintJobInfo;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.SomeArgs;

import java.util.List;

/**
 * Service for exposing some of the {@link PrintSpooler} functionality to
 * another process.
 */
public final class PrintSpoolerService extends Service {

    private static final long CHECK_ALL_PRINTJOBS_HANDLED_DELAY = 5000;

    private static final String LOG_TAG = "PrintSpoolerService";

    private Intent mStartPrintJobConfigActivityIntent;

    private IPrintSpoolerClient mClient;

    private Handler mHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        mStartPrintJobConfigActivityIntent = new Intent(PrintSpoolerService.this,
                PrintJobConfigActivity.class);
        mHandler = new MyHandler(getMainLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new IPrintSpooler.Stub() {
            @Override
            public void getPrintJobInfos(IPrintSpoolerCallbacks callback,
                    ComponentName componentName, int state, int appId, int sequence)
                            throws RemoteException {
                List<PrintJobInfo> printJobs = null;
                try {
                    printJobs = PrintSpooler.peekInstance().getPrintJobInfos(
                            componentName, state, appId);
                } finally {
                    callback.onGetPrintJobInfosResult(printJobs, sequence);
                }
            }

            @Override
            public void getPrintJobInfo(int printJobId, IPrintSpoolerCallbacks callback,
                    int appId, int sequence) throws RemoteException {
                PrintJobInfo printJob = null;
                try {
                    printJob = PrintSpooler.peekInstance().getPrintJobInfo(printJobId, appId);
                } finally {
                    callback.onGetPrintJobInfoResult(printJob, sequence);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void createPrintJob(String printJobName, IPrintClient client,
                    IPrintDocumentAdapter printAdapter, PrintAttributes attributes,
                    IPrintSpoolerCallbacks callback, int appId, int sequence)
                            throws RemoteException {
                PrintJobInfo printJob = null;
                try {
                    printJob = PrintSpooler.peekInstance().createPrintJob(printJobName, client,
                            attributes, appId);
                    if (printJob != null) {
                        Intent intent = mStartPrintJobConfigActivityIntent;
                        intent.putExtra(PrintJobConfigActivity.EXTRA_PRINT_DOCUMENT_ADAPTER,
                                printAdapter.asBinder());
                        intent.putExtra(PrintJobConfigActivity.EXTRA_PRINT_JOB_ID,
                                printJob.getId());
                        intent.putExtra(PrintJobConfigActivity.EXTRA_PRINT_ATTRIBUTES, attributes);

                        IntentSender sender = PendingIntent.getActivity(
                                PrintSpoolerService.this, 0, intent, PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_CANCEL_CURRENT).getIntentSender();

                        SomeArgs args = SomeArgs.obtain();
                        args.arg1 = client;
                        args.arg2 = sender;
                        mHandler.obtainMessage(MyHandler.MSG_START_PRINT_JOB_CONFIG_ACTIVITY,
                                args).sendToTarget();
                    }
                } finally {
                    callback.onCreatePrintJobResult(printJob, sequence);
                }
            }

            @Override
            public void setPrintJobState(int printJobId, int state, CharSequence error,
                    IPrintSpoolerCallbacks callback, int sequece) throws RemoteException {
                boolean success = false;
                try {
                    success = PrintSpooler.peekInstance().setPrintJobState(
                            printJobId, state, error);
                } finally {
                    callback.onSetPrintJobStateResult(success, sequece);
                }
            }

            @Override
            public void setPrintJobTag(int printJobId, String tag,
                    IPrintSpoolerCallbacks callback, int sequece) throws RemoteException {
                boolean success = false;
                try {
                    success = PrintSpooler.peekInstance().setPrintJobTag(printJobId, tag);
                } finally {
                    callback.onSetPrintJobTagResult(success, sequece);
                }
            }

            @Override
            public void writePrintJobData(ParcelFileDescriptor fd, int printJobId) {
                PrintSpooler.peekInstance().writePrintJobData(fd, printJobId);
            }

            @Override
            public void setClient(IPrintSpoolerClient client) {
                mHandler.obtainMessage(MyHandler.MSG_SET_CLIENT, client).sendToTarget();
            }
        };
    }

    public void onPrintJobQueued(PrintJobInfo printJob) {
        mHandler.obtainMessage(MyHandler.MSG_ON_PRINT_JOB_QUEUED,
                printJob).sendToTarget();
    }

    public void createPrinterDiscoverySession(IPrinterDiscoverySessionObserver observer) {
        mHandler.obtainMessage(MyHandler.MSG_CREATE_PRINTER_DISCOVERY_SESSION,
                observer).sendToTarget();
    }

    public void onAllPrintJobsForServiceHandled(ComponentName service) {
        mHandler.obtainMessage(MyHandler.MSG_ON_ALL_PRINT_JOBS_FOR_SERIVICE_HANDLED,
                service).sendToTarget();
    }

    public void onAllPrintJobsHandled() {
        mHandler.sendEmptyMessage(MyHandler.MSG_ON_ALL_PRINT_JOBS_HANDLED);
    }

    private final class MyHandler extends Handler {
        public static final int MSG_SET_CLIENT = 1;
        public static final int MSG_START_PRINT_JOB_CONFIG_ACTIVITY = 2;
        public static final int MSG_CREATE_PRINTER_DISCOVERY_SESSION = 3;
        public static final int MSG_ON_PRINT_JOB_QUEUED = 5;
        public static final int MSG_ON_ALL_PRINT_JOBS_FOR_SERIVICE_HANDLED = 6;
        public static final int MSG_ON_ALL_PRINT_JOBS_HANDLED = 7;
        public static final int MSG_CHECK_ALL_PRINTJOBS_HANDLED = 9;

        public MyHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_SET_CLIENT: {
                    mClient = (IPrintSpoolerClient) message.obj;
                    if (mClient != null) {
                        PrintSpooler.createInstance(PrintSpoolerService.this);
                        mHandler.sendEmptyMessageDelayed(
                                MyHandler.MSG_CHECK_ALL_PRINTJOBS_HANDLED,
                                CHECK_ALL_PRINTJOBS_HANDLED_DELAY);
                    } else {
                        PrintSpooler.destroyInstance();
                    }
                } break;

                case MSG_START_PRINT_JOB_CONFIG_ACTIVITY: {
                    SomeArgs args = (SomeArgs) message.obj;
                    IPrintClient client = (IPrintClient) args.arg1;
                    IntentSender sender = (IntentSender) args.arg2;
                    args.recycle();
                    try {
                        client.startPrintJobConfigActivity(sender);
                    } catch (RemoteException re) {
                        Slog.i(LOG_TAG, "Error starting print job config activity!", re);
                    }
                } break;

                case MSG_CREATE_PRINTER_DISCOVERY_SESSION: {
                    IPrinterDiscoverySessionObserver observer =
                            (IPrinterDiscoverySessionObserver) message.obj;
                    if (mClient != null) {
                        try {
                            mClient.createPrinterDiscoverySession(observer);
                        } catch (RemoteException re) {
                            Log.e(LOG_TAG, "Error creating printer discovery session.", re);
                        }
                    }
                } break;

                case MSG_ON_PRINT_JOB_QUEUED: {
                    PrintJobInfo printJob = (PrintJobInfo) message.obj;
                    if (mClient != null) {
                        try {
                            mClient.onPrintJobQueued(printJob);
                        } catch (RemoteException re) {
                            Slog.e(LOG_TAG, "Error notify for a queued print job.", re);
                        }
                    }
                } break;

                case MSG_ON_ALL_PRINT_JOBS_FOR_SERIVICE_HANDLED: {
                    ComponentName service = (ComponentName) message.obj;
                    if (mClient != null) {
                        try {
                            mClient.onAllPrintJobsForServiceHandled(service);
                        } catch (RemoteException re) {
                            Slog.e(LOG_TAG, "Error notify for all print jobs per service"
                                    + " handled.", re);
                        }
                    }
                } break;

                case MSG_ON_ALL_PRINT_JOBS_HANDLED: {
                    if (mClient != null) {
                        try {
                            mClient.onAllPrintJobsHandled();
                        } catch (RemoteException re) {
                            Slog.e(LOG_TAG, "Error notify for all print job handled.", re);
                        }
                    }
                } break;

                case MSG_CHECK_ALL_PRINTJOBS_HANDLED: {
                    PrintSpooler spooler = PrintSpooler.peekInstance();
                    if (spooler != null) {
                        spooler.checkAllPrintJobsHandled();
                    }
                } break;
            }
        }
    }
}
