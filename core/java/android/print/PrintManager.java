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
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PrintAdapter.PrintProgressCallback;
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
     * @return An instance of the caller has the permission to access
     * all print jobs, null otherwise.
     *
     * @hide
     */
    public PrintManager getGlobalPrintManagerForUser(int userId) {
        return new PrintManager(mContext, mService, userId, APP_ID_ANY);
    }

    PrintJobInfo getPrintJob(int printJobId) {
        try {
            return mService.getPrintJob(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting print job:" + printJobId, re);
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
            List<PrintJobInfo> printJobInfos = mService.getPrintJobs(mAppId, mUserId);
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
            Log.e(LOG_TAG, "Error getting print jobs!", re);
        }
        return Collections.emptyList();
    }

    ICancellationSignal cancelPrintJob(int printJobId) {
        try {
            mService.cancelPrintJob(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error cancleing a print job:" + printJobId, re);
        }
        return null;
    }

    /**
     * Creates a print job for printing a file with default print attributes.
     *
     * @param printJobName A name for the new print job.
     * @param pdfFile The PDF file to print.
     * @param attributes The default print job attributes.
     * @return The created print job.
     */
    public PrintJob print(String printJobName, File pdfFile, PrintAttributes attributes) {
        PrintFileAdapter printable = new PrintFileAdapter(pdfFile);
        return print(printJobName, printable, attributes);
    }

    /**
     * Creates a print job for printing a {@link PrintAdapter} with default print
     * attributes.
     *
     * @param printJobName A name for the new print job.
     * @param printAdapter The printable adapter to print.
     * @param attributes The default print job attributes.
     * @return The created print job.
     */
    public PrintJob print(String printJobName, PrintAdapter printAdapter,
            PrintAttributes attributes) {
        PrintAdapterDelegate delegate = new PrintAdapterDelegate(printAdapter,
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

    private static final class PrintAdapterDelegate extends IPrintAdapter.Stub {
        private final Object mLock = new Object();

        private PrintAdapter mPrintAdapter;

        private Handler mHandler;

        public PrintAdapterDelegate(PrintAdapter printAdapter, Looper looper) {
            mPrintAdapter = printAdapter;
            mHandler = new MyHandler(looper);
        }

        @Override
        public void start() {
            synchronized (mLock) {
                if (isFinishedLocked()) {
                    return;
                }
                mHandler.obtainMessage(MyHandler.MESSAGE_START,
                        mPrintAdapter).sendToTarget();
            }
        }

        @Override
        public void printAttributesChanged(PrintAttributes attributes) {
            synchronized (mLock) {
                if (isFinishedLocked()) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = mPrintAdapter;
                args.arg2 = attributes;
                mHandler.obtainMessage(MyHandler.MESSAGE_PRINT_ATTRIBUTES_CHANGED,
                        args).sendToTarget();
            }
        }

        @Override
        public void print(List<PageRange> pages, ParcelFileDescriptor fd,
                IPrintProgressListener progressListener) {
            synchronized (mLock) {
                if (isFinishedLocked()) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = mPrintAdapter;
                args.arg2 = pages;
                args.arg3 = fd.getFileDescriptor();
                args.arg4 = progressListener;
                mHandler.obtainMessage(MyHandler.MESSAGE_PRINT, args).sendToTarget();
            }
        }

        @Override
        public void finish() {
            synchronized (mLock) {
                if (isFinishedLocked()) {
                    return;
                }
                mHandler.obtainMessage(MyHandler.MESSAGE_FINIS,
                        mPrintAdapter).sendToTarget();
            }
        }

        private boolean isFinishedLocked() {
            return mPrintAdapter == null;
        }

        private void finishLocked() {
            mPrintAdapter = null;
            mHandler = null;
        }

        private final class MyHandler extends Handler {
            public static final int MESSAGE_START = 1;
            public static final int MESSAGE_PRINT_ATTRIBUTES_CHANGED = 2;
            public static final int MESSAGE_PRINT = 3;
            public static final int MESSAGE_FINIS = 4;

            public MyHandler(Looper looper) {
                super(looper, null, true);
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MESSAGE_START: {
                        PrintAdapter adapter = (PrintAdapter) message.obj;
                        adapter.onStart();
                    } break;

                    case MESSAGE_PRINT_ATTRIBUTES_CHANGED: {
                        SomeArgs args = (SomeArgs) message.obj;
                        PrintAdapter adapter = (PrintAdapter) args.arg1;
                        PrintAttributes attributes = (PrintAttributes) args.arg2;
                        args.recycle();
                        adapter.onPrintAttributesChanged(attributes);
                    } break;

                    case MESSAGE_PRINT: {
                        SomeArgs args = (SomeArgs) message.obj;
                        PrintAdapter adapter = (PrintAdapter) args.arg1;
                        @SuppressWarnings("unchecked")
                        List<PageRange> pages = (List<PageRange>) args.arg2;
                        final FileDescriptor fd = (FileDescriptor) args.arg3;
                        IPrintProgressListener listener = (IPrintProgressListener) args.arg4;
                        args.recycle();
                        try {
                            ICancellationSignal remoteSignal = CancellationSignal.createTransport();
                            listener.onWriteStarted(adapter.getInfo(), remoteSignal);

                            CancellationSignal localSignal = CancellationSignal.fromTransport(
                                    remoteSignal);
                            adapter.onPrint(pages, fd, localSignal,
                                    new PrintProgressListenerWrapper(listener) {
                                        @Override
                                        public void onPrintFinished(List<PageRange> pages) {
                                            IoUtils.closeQuietly(fd);
                                            super.onPrintFinished(pages);
                                        }

                                        @Override
                                        public void onPrintFailed(CharSequence error) {
                                            IoUtils.closeQuietly(fd);
                                            super.onPrintFailed(error);
                                        }
                                    });
                        } catch (RemoteException re) {
                            Log.e(LOG_TAG, "Error printing", re);
                            IoUtils.closeQuietly(fd);
                        }
                    } break;

                    case MESSAGE_FINIS: {
                        PrintAdapter adapter = (PrintAdapter) message.obj;
                        adapter.onFinish();
                        synchronized (mLock) {
                            finishLocked();
                        }
                    } break;

                    default: {
                        throw new IllegalArgumentException("Unknown message: "
                                + message.what);
                    }
                }
            }
        }
    }

    private static abstract class PrintProgressListenerWrapper extends PrintProgressCallback {

        private final IPrintProgressListener mWrappedListener;

        public PrintProgressListenerWrapper(IPrintProgressListener listener) {
            mWrappedListener = listener;
        }

        @Override
        public void onPrintFinished(List<PageRange> pages) {
            try {
                mWrappedListener.onWriteFinished(pages);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error calling onWriteFinished", re);
            }
        }

        @Override
        public void onPrintFailed(CharSequence error) {
            try {
                mWrappedListener.onWriteFailed(error);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error calling onWriteFailed", re);
            }
        }
    }
}
