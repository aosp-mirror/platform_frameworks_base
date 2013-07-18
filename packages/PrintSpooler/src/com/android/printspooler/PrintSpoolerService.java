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

import java.util.List;

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
import android.print.IPrintDocumentAdapter;
import android.print.IPrintClient;
import android.print.IPrintSpoolerClient;
import android.print.IPrintSpooler;
import android.print.IPrintSpoolerCallbacks;
import android.print.PrintAttributes;
import android.print.PrintJobInfo;
import android.util.Slog;

import com.android.internal.os.SomeArgs;

/**
 * Service for exposing some of the {@link PrintSpooler} functionality to
 * another process.
 */
public final class PrintSpoolerService extends Service {

    private static final String LOG_TAG = "PrintSpoolerService";

    private Intent mStartPrintJobConfigActivityIntent;

    private PrintSpooler mSpooler;

    private Handler mHanlder;

    @Override
    public void onCreate() {
        super.onCreate();
        mStartPrintJobConfigActivityIntent = new Intent(PrintSpoolerService.this,
                PrintJobConfigActivity.class);
        mSpooler = PrintSpooler.getInstance(this);
        mHanlder = new MyHandler(getMainLooper());
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
                    printJobs = mSpooler.getPrintJobInfos(componentName, state, appId);
                } finally {
                    callback.onGetPrintJobInfosResult(printJobs, sequence);
                }
            }

            @Override
            public void getPrintJobInfo(int printJobId, IPrintSpoolerCallbacks callback,
                    int appId, int sequence) throws RemoteException {
                PrintJobInfo printJob = null;
                try {
                    printJob = mSpooler.getPrintJobInfo(printJobId, appId);
                } finally {
                    callback.onGetPrintJobInfoResult(printJob, sequence);
                }
            }

            @Override
            public void cancelPrintJob(int printJobId, IPrintSpoolerCallbacks callback,
                    int appId, int sequence) throws RemoteException {
                boolean success = false;
                try {
                    success = mSpooler.cancelPrintJob(printJobId, appId);
                } finally {
                    callback.onCancelPrintJobResult(success, sequence);
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
                    printJob = mSpooler.createPrintJob(printJobName, client,
                            attributes, appId);
                    if (printJob != null) {
                        Intent intent = mStartPrintJobConfigActivityIntent;
                        intent.putExtra(PrintJobConfigActivity.EXTRA_PRINTABLE,
                                printAdapter.asBinder());
                        intent.putExtra(PrintJobConfigActivity.EXTRA_APP_ID, appId);
                        intent.putExtra(PrintJobConfigActivity.EXTRA_PRINT_JOB_ID,
                                printJob.getId());
                        intent.putExtra(PrintJobConfigActivity.EXTRA_ATTRIBUTES, attributes);

                        IntentSender sender = PendingIntent.getActivity(
                                PrintSpoolerService.this, 0, intent, PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_CANCEL_CURRENT).getIntentSender();

                        SomeArgs args = SomeArgs.obtain();
                        args.arg1 = client;
                        args.arg2 = sender;
                        mHanlder.obtainMessage(0, args).sendToTarget();
                    }
                } finally {
                    callback.onCreatePrintJobResult(printJob, sequence);
                }
            }

            @Override
            public void setPrintJobState(int printJobId, int state,
                    IPrintSpoolerCallbacks callback, int sequece)
                            throws RemoteException {
                boolean success = false;
                try {
                    // TODO: Make sure the clients (print services) can set the state
                    //       only to acceptable ones, e.g. not settings STATE_CREATED.
                    success = mSpooler.setPrintJobState(printJobId, state);
                } finally {
                    callback.onSetPrintJobStateResult(success, sequece);
                }
            }

            @Override
            public void setPrintJobTag(int printJobId, String tag,
                    IPrintSpoolerCallbacks callback, int sequece)
                            throws RemoteException {
                boolean success = false;
                try {
                    success = mSpooler.setPrintJobTag(printJobId, tag);
                } finally {
                    callback.onSetPrintJobTagResult(success, sequece);
                }
            }

            @Override
            public void writePrintJobData(ParcelFileDescriptor fd, int printJobId) {
                mSpooler.writePrintJobData(fd, printJobId);
            }

            @Override
            public void setClient(IPrintSpoolerClient client)  {
                mSpooler.setCleint(client);
            }

            @Override
            public void notifyClientForActivteJobs() {
                mSpooler.notifyClientForActivteJobs();
            }
        };
    }

    private static final class MyHandler extends Handler {

        public MyHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message message) {
            SomeArgs args = (SomeArgs) message.obj;
            IPrintClient client = (IPrintClient) args.arg1;
            IntentSender sender = (IntentSender) args.arg2;
            args.recycle();
            try {
                client.startPrintJobConfigActivity(sender);
            } catch (RemoteException re) {
                Slog.i(LOG_TAG, "Error starting print job config activity!", re);
            }
        }
    }
}
