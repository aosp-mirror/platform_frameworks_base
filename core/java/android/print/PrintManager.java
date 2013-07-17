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
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PrintDocumentAdapter.LayoutResultCallback;
import android.print.PrintDocumentAdapter.WriteResultCallback;
import android.util.Log;

import com.android.internal.os.SomeArgs;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * System level service for accessing the printing capabilities of the platform.
 * <p>
 * To obtain a handle to the print manager do the following:
 * </p>
 * <pre>
 * PrintManager printManager =
 *         (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
 * </pre>
 */
public final class PrintManager {

    private static final String LOG_TAG = "PrintManager";

    /** @hide */
    public static final int APP_ID_ANY = -2;

    private final Context mContext;

    private final IPrintManager mService;

    private final int mUserId;

    private final int mAppId;

    private final PrintClient mPrintClient;

    private final Handler mHandler;

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate.
     * @param service The backing system service.
     *
     * @hide
     */
    public PrintManager(Context context, IPrintManager service, int userId, int appId) {
        mContext = context;
        mService = service;
        mUserId = userId;
        mAppId = appId;
        mPrintClient = new PrintClient(this);
        mHandler = new Handler(context.getMainLooper(), null, false) {
            @Override
            public void handleMessage(Message message) {
                SomeArgs args = (SomeArgs) message.obj;
                Context context = (Context) args.arg1;
                IntentSender intent = (IntentSender) args.arg2;
                args.recycle();
                try {
                    context.startIntentSender(intent, null, 0, 0, 0);
                } catch (SendIntentException sie) {
                    Log.e(LOG_TAG, "Couldn't start print job config activity.", sie);
                }
            }
        };
    }

    /**
     * Creates an instance that can access all print jobs.
     *
     * @param userId The user id for which to get all print jobs.
     * @return An instance if the caller has the permission to access
     * all print jobs, null otherwise.
     *
     * @hide
     */
    public PrintManager getGlobalPrintManagerForUser(int userId) {
        return new PrintManager(mContext, mService, userId, APP_ID_ANY);
    }

    PrintJobInfo getPrintJobInfo(int printJobId) {
        try {
            return mService.getPrintJobInfo(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting a print job info:" + printJobId, re);
        }
        return null;
    }

    /**
     * Gets the print jobs for this application.
     *
     * @return The print job list.
     *
     * @see PrintJob
     */
    public List<PrintJob> getPrintJobs() {
        try {
            List<PrintJobInfo> printJobInfos = mService.getPrintJobInfos(mAppId, mUserId);
            if (printJobInfos == null) {
                return Collections.emptyList();
            }
            final int printJobCount = printJobInfos.size();
            List<PrintJob> printJobs = new ArrayList<PrintJob>(printJobCount);
            for (int i = 0; i < printJobCount; i++) {
                printJobs.add(new PrintJob(printJobInfos.get(i), this));
            }
            return printJobs;
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting print jobs", re);
        }
        return Collections.emptyList();
    }

    void cancelPrintJob(int printJobId) {
        try {
            mService.cancelPrintJob(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error cancleing a print job: " + printJobId, re);
        }
    }

    /**
     * Creates a print job for printing a file with default print attributes.
     *
     * @param printJobName A name for the new print job.
     * @param pdfFile The PDF file to print.
     * @param attributes The default print job attributes.
     * @return The created print job.
     *
     * @see PrintJob
     */
    public PrintJob print(String printJobName, File pdfFile, PrintAttributes attributes) {
        FileDocumentAdapter documentAdapter = new FileDocumentAdapter(mContext, pdfFile);
        return print(printJobName, documentAdapter, attributes);
    }

    /**
     * Creates a print job for printing a {@link PrintDocumentAdapter} with default print
     * attributes.
     *
     * @param printJobName A name for the new print job.
     * @param documentAdapter An adapter that emits the document to print.
     * @param attributes The default print job attributes.
     * @return The created print job.
     *
     * @see PrintJob
     */
    public PrintJob print(String printJobName, PrintDocumentAdapter documentAdapter,
            PrintAttributes attributes) {
        PrintDocumentAdapterDelegate delegate = new PrintDocumentAdapterDelegate(documentAdapter,
                mContext.getMainLooper());
        try {
            PrintJobInfo printJob = mService.print(printJobName, mPrintClient, delegate,
                    attributes, mAppId, mUserId);
            if (printJob != null) {
                return new PrintJob(printJob, this);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error creating a print job", re);
        }
        return null;
    }

    private static final class PrintClient extends IPrintClient.Stub {

        private final WeakReference<PrintManager> mWeakPrintManager;

        public PrintClient(PrintManager manager) {
            mWeakPrintManager = new WeakReference<PrintManager>(manager);
        }

        @Override
        public void startPrintJobConfigActivity(IntentSender intent)  {
            PrintManager manager = mWeakPrintManager.get();
            if (manager != null) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 =  manager.mContext;
                args.arg2 = intent;
                manager.mHandler.obtainMessage(0, args).sendToTarget();
            }
        }
    }

    private static final class PrintDocumentAdapterDelegate extends IPrintDocumentAdapter.Stub {
        private PrintDocumentAdapter mDocumentAdapter; // Strong reference OK - cleared in finish()

        private Handler mHandler; // Strong reference OK - cleared in finish()

        public PrintDocumentAdapterDelegate(PrintDocumentAdapter documentAdapter, Looper looper) {
            mDocumentAdapter = documentAdapter;
            mHandler = new MyHandler(looper);
        }

        @Override
        public void start() {
            mHandler.sendEmptyMessage(MyHandler.MSG_START);
        }

        @Override
        public void layout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                ILayoutResultCallback callback, Bundle metadata) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = oldAttributes;
            args.arg2 = newAttributes;
            args.arg3 = callback;
            args.arg4 = metadata;
            mHandler.obtainMessage(MyHandler.MSG_LAYOUT, args).sendToTarget();
        }

        @Override
        public void write(List<PageRange> pages, ParcelFileDescriptor fd,
            IWriteResultCallback callback) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = pages;
            args.arg2 = fd.getFileDescriptor();
            args.arg3 = callback;
            mHandler.obtainMessage(MyHandler.MSG_WRITE, args).sendToTarget();
        }

        @Override
        public void finish() {
            mHandler.sendEmptyMessage(MyHandler.MSG_FINISH);
        }

        private boolean isFinished() {
            return mDocumentAdapter == null;
        }

        private void doFinish() {
            mDocumentAdapter = null;
            mHandler = null;
        }

        private final class MyHandler extends Handler {
            public static final int MSG_START = 1;
            public static final int MSG_LAYOUT = 2;
            public static final int MSG_WRITE = 3;
            public static final int MSG_FINISH = 4;

            public MyHandler(Looper looper) {
                super(looper, null, true);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleMessage(Message message) {
                if (isFinished()) {
                    return;
                }
                switch (message.what) {
                    case MSG_START: {
                        mDocumentAdapter.onStart();
                    } break;

                    case MSG_LAYOUT: {
                        SomeArgs args = (SomeArgs) message.obj;
                        PrintAttributes oldAttributes = (PrintAttributes) args.arg1;
                        PrintAttributes newAttributes = (PrintAttributes) args.arg2;
                        ILayoutResultCallback callback = (ILayoutResultCallback) args.arg3;
                        Bundle metadata = (Bundle) args.arg4;
                        args.recycle();

                        try {
                            ICancellationSignal remoteSignal = CancellationSignal.createTransport();
                            callback.onLayoutStarted(remoteSignal);

                            mDocumentAdapter.onLayout(oldAttributes, newAttributes,
                                    CancellationSignal.fromTransport(remoteSignal),
                                    new LayoutResultCallbackWrapper(callback), metadata);
                        } catch (RemoteException re) {
                            Log.e(LOG_TAG, "Error printing", re);
                        }
                    } break;

                    case MSG_WRITE: {
                        SomeArgs args = (SomeArgs) message.obj;
                        List<PageRange> pages = (List<PageRange>) args.arg1;
                        FileDescriptor fd = (FileDescriptor) args.arg2;
                        IWriteResultCallback callback = (IWriteResultCallback) args.arg3;
                        args.recycle();

                        try {
                            ICancellationSignal remoteSignal = CancellationSignal.createTransport();
                            callback.onWriteStarted(remoteSignal);

                            mDocumentAdapter.onWrite(pages, fd,
                                    CancellationSignal.fromTransport(remoteSignal),
                                    new WriteResultCallbackWrapper(callback, fd));
                        } catch (RemoteException re) {
                            Log.e(LOG_TAG, "Error printing", re);
                            IoUtils.closeQuietly(fd);
                        }
                    } break;

                    case MSG_FINISH: {
                        mDocumentAdapter.onFinish();
                        doFinish();
                    } break;

                    default: {
                        throw new IllegalArgumentException("Unknown message: "
                                + message.what);
                    }
                }
            }
        }
    }

    private static final class WriteResultCallbackWrapper extends WriteResultCallback {

        private final IWriteResultCallback mWrappedCallback;
        private final FileDescriptor mFd;

        public WriteResultCallbackWrapper(IWriteResultCallback callback,
                FileDescriptor fd) {
            mWrappedCallback = callback;
            mFd = fd;
        }

        @Override
        public void onWriteFinished(List<PageRange> pages) {
            try {
                // Close before notifying the other end. We want
                // to be ready by the time we announce it.
                IoUtils.closeQuietly(mFd);
                mWrappedCallback.onWriteFinished(pages);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error calling onWriteFinished", re);
            }
        }

        @Override
        public void onWriteFailed(CharSequence error) {
            try {
                // Close before notifying the other end. We want
                // to be ready by the time we announce it.
                IoUtils.closeQuietly(mFd);
                mWrappedCallback.onWriteFailed(error);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error calling onWriteFailed", re);
            }
        }
    }

    private static final class LayoutResultCallbackWrapper extends LayoutResultCallback {

        private final ILayoutResultCallback mWrappedCallback;

        public LayoutResultCallbackWrapper(ILayoutResultCallback callback) {
            mWrappedCallback = callback;
        }

        @Override
        public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
            try {
                mWrappedCallback.onLayoutFinished(info, changed);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error calling onLayoutFinished", re);
            }
        }

        @Override
        public void onLayoutFailed(CharSequence error) {
            try {
                mWrappedCallback.onLayoutFailed(error);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error calling onLayoutFailed", re);
            }
        }
    }
}
