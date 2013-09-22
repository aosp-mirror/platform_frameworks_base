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
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PrintDocumentAdapter.LayoutResultCallback;
import android.print.PrintDocumentAdapter.WriteResultCallback;
import android.printservice.PrintServiceInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.os.SomeArgs;

import libcore.io.IoUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    private Map<PrintJobStateChangeListener, PrintJobStateChangeListenerWrapper> mPrintJobStateChangeListeners;

    /** @hide */
    public interface PrintJobStateChangeListener {

        /**
         * Callback notifying that a print job state changed.
         *
         * @param printJobId The print job id.
         */
        public void onPrintJobsStateChanged(PrintJobId printJobId);
    }

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
     * @hide
     */
    public PrintManager getGlobalPrintManagerForUser(int userId) {
        return new PrintManager(mContext, mService, userId, APP_ID_ANY);
    }

    PrintJobInfo getPrintJobInfo(PrintJobId printJobId) {
        try {
            return mService.getPrintJobInfo(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting a print job info:" + printJobId, re);
        }
        return null;
    }

    /**
     * Adds a listener for observing the state of print jobs.
     *
     * @param listener The listener to add.
     *
     * @hide
     */
    public void addPrintJobStateChangeListener(PrintJobStateChangeListener listener) {
        if (mPrintJobStateChangeListeners == null) {
            mPrintJobStateChangeListeners = new ArrayMap<PrintJobStateChangeListener,
                    PrintJobStateChangeListenerWrapper>();
        }
        PrintJobStateChangeListenerWrapper wrappedListener =
                new PrintJobStateChangeListenerWrapper(listener);
        try {
            mService.addPrintJobStateChangeListener(wrappedListener, mAppId, mUserId);
            mPrintJobStateChangeListeners.put(listener, wrappedListener);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error adding print job state change listener", re);
        }
    }

    /**
     * Removes a listener for observing the state of print jobs.
     *
     * @param listener The listener to remove.
     *
     * @hide
     */
    public void removePrintJobStateChangeListener(PrintJobStateChangeListener listener) {
        if (mPrintJobStateChangeListeners == null) {
            return;
        }
        PrintJobStateChangeListenerWrapper wrappedListener =
                mPrintJobStateChangeListeners.remove(listener);
        if (wrappedListener == null) {
            return;
        }
        if (mPrintJobStateChangeListeners.isEmpty()) {
            mPrintJobStateChangeListeners = null;
        }
        try {
            mService.removePrintJobStateChangeListener(wrappedListener, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error removing print job state change listener", re);
        }
    }

    /**
     * Gets a print job given its id.
     *
     * @return The print job list.
     *
     * @see PrintJob
     *
     * @hide
     */
    public PrintJob getPrintJob(PrintJobId printJobId) {
        try {
            PrintJobInfo printJob = mService.getPrintJobInfo(printJobId, mAppId, mUserId);
            if (printJob != null) {
                return new PrintJob(printJob, this);
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting print job", re);
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

    void cancelPrintJob(PrintJobId printJobId) {
        try {
            mService.cancelPrintJob(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error cancleing a print job: " + printJobId, re);
        }
    }

    void restartPrintJob(PrintJobId printJobId) {
        try {
            mService.restartPrintJob(printJobId, mAppId, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error restarting a print job: " + printJobId, re);
        }
    }

    /**
     * Creates a print job for printing a {@link PrintDocumentAdapter} with default print
     * attributes.
     *
     * @param printJobName A name for the new print job.
     * @param documentAdapter An adapter that emits the document to print.
     * @param attributes The default print job attributes.
     * @return The created print job on success or null on failure.
     * @see PrintJob
     */
    public PrintJob print(String printJobName, PrintDocumentAdapter documentAdapter,
            PrintAttributes attributes) {
        if (TextUtils.isEmpty(printJobName)) {
            throw new IllegalArgumentException("priintJobName cannot be empty");
        }
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

    /**
     * Gets the list of enabled print services.
     *
     * @return The enabled service list or an empty list.
     *
     * @hide
     */
    public List<PrintServiceInfo> getEnabledPrintServices() {
        try {
            List<PrintServiceInfo> enabledServices = mService.getEnabledPrintServices(mUserId);
            if (enabledServices != null) {
                return enabledServices;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting the enalbed print services", re);
        }
        return Collections.emptyList();
    }

    /**
     * @hide
     */
    public PrinterDiscoverySession createPrinterDiscoverySession() {
        return new PrinterDiscoverySession(mService, mContext, mUserId);
    }

    private static final class PrintClient extends IPrintClient.Stub {

        private final WeakReference<PrintManager> mWeakPrintManager;

        public PrintClient(PrintManager manager) {
            mWeakPrintManager = new WeakReference<PrintManager>(manager);
        }

        @Override
        public void startPrintJobConfigActivity(IntentSender intent) {
            PrintManager manager = mWeakPrintManager.get();
            if (manager != null) {
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = manager.mContext;
                args.arg2 = intent;
                manager.mHandler.obtainMessage(0, args).sendToTarget();
            }
        }
    }

    private static final class PrintDocumentAdapterDelegate extends IPrintDocumentAdapter.Stub {

        private final Object mLock = new Object();

        private CancellationSignal mLayoutOrWriteCancellation;

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
                ILayoutResultCallback callback, Bundle metadata, int sequence) {
            synchronized (mLock) {
                if (mLayoutOrWriteCancellation != null) {
                    mLayoutOrWriteCancellation.cancel();
                }
            }
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = oldAttributes;
            args.arg2 = newAttributes;
            args.arg3 = callback;
            args.arg4 = metadata;
            args.argi1 = sequence;
            mHandler.removeMessages(MyHandler.MSG_LAYOUT);
            mHandler.obtainMessage(MyHandler.MSG_LAYOUT, args).sendToTarget();
        }

        @Override
        public void write(PageRange[] pages, ParcelFileDescriptor fd,
                IWriteResultCallback callback, int sequence) {
            synchronized (mLock) {
                if (mLayoutOrWriteCancellation != null) {
                    mLayoutOrWriteCancellation.cancel();
                }
            }
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = pages;
            args.arg2 = fd;
            args.arg3 = callback;
            args.argi1 = sequence;
            mHandler.removeMessages(MyHandler.MSG_WRITE);
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
            mLayoutOrWriteCancellation = null;
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
                        final int sequence = args.argi1;
                        args.recycle();

                        CancellationSignal cancellation = new CancellationSignal();
                        synchronized (mLock) {
                            mLayoutOrWriteCancellation = cancellation;
                        }

                        mDocumentAdapter.onLayout(oldAttributes, newAttributes, cancellation,
                                new MyLayoutResultCallback(callback, sequence), metadata);
                    } break;

                    case MSG_WRITE: {
                        SomeArgs args = (SomeArgs) message.obj;
                        PageRange[] pages = (PageRange[]) args.arg1;
                        ParcelFileDescriptor fd = (ParcelFileDescriptor) args.arg2;
                        IWriteResultCallback callback = (IWriteResultCallback) args.arg3;
                        final int sequence = args.argi1;
                        args.recycle();

                        CancellationSignal cancellation = new CancellationSignal();
                        synchronized (mLock) {
                            mLayoutOrWriteCancellation = cancellation;
                        }

                        mDocumentAdapter.onWrite(pages, fd, cancellation,
                                new MyWriteResultCallback(callback, fd, sequence));
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

        private final class MyLayoutResultCallback extends LayoutResultCallback {
            private ILayoutResultCallback mCallback;
            private final int mSequence;

            public MyLayoutResultCallback(ILayoutResultCallback callback,
                    int sequence) {
                mCallback = callback;
                mSequence = sequence;
            }

            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                if (info == null) {
                    throw new NullPointerException("document info cannot be null");
                }
                final ILayoutResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                    clearLocked();
                }
                if (callback != null) {
                    try {
                        callback.onLayoutFinished(info, changed, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onLayoutFinished", re);
                    }
                }
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                final ILayoutResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                    clearLocked();
                }
                if (callback != null) {
                    try {
                        callback.onLayoutFailed(error, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onLayoutFailed", re);
                    }
                }
            }

            @Override
            public void onLayoutCancelled() {
                synchronized (mLock) {
                    clearLocked();
                }
            }

            private void clearLocked() {
                mLayoutOrWriteCancellation = null;
                mCallback = null;
            }
        }

        private final class MyWriteResultCallback extends WriteResultCallback {
            private ParcelFileDescriptor mFd;
            private int mSequence;
            private IWriteResultCallback mCallback;

            public MyWriteResultCallback(IWriteResultCallback callback,
                    ParcelFileDescriptor fd, int sequence) {
                mFd = fd;
                mSequence = sequence;
                mCallback = callback;
            }

            @Override
            public void onWriteFinished(PageRange[] pages) {
                final IWriteResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                    clearLocked();
                }
                if (pages == null) {
                    throw new IllegalArgumentException("pages cannot be null");
                }
                if (pages.length == 0) {
                    throw new IllegalArgumentException("pages cannot be empty");
                }
                if (callback != null) {
                    try {
                        callback.onWriteFinished(pages, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onWriteFinished", re);
                    }
                }
            }

            @Override
            public void onWriteFailed(CharSequence error) {
                final IWriteResultCallback callback;
                synchronized (mLock) {
                    callback = mCallback;
                    clearLocked();
                }
                if (callback != null) {
                    try {
                        callback.onWriteFailed(error, mSequence);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error calling onWriteFailed", re);
                    }
                }
            }

            @Override
            public void onWriteCancelled() {
                synchronized (mLock) {
                    clearLocked();
                }
            }

            private void clearLocked() {
                mLayoutOrWriteCancellation = null;
                IoUtils.closeQuietly(mFd);
                mCallback = null;
                mFd = null;
            }
        }
    }

    private static final class PrintJobStateChangeListenerWrapper extends
            IPrintJobStateChangeListener.Stub {
        private final WeakReference<PrintJobStateChangeListener> mWeakListener;

        public PrintJobStateChangeListenerWrapper(PrintJobStateChangeListener listener) {
            mWeakListener = new WeakReference<PrintJobStateChangeListener>(listener);
        }

        @Override
        public void onPrintJobStateChanged(PrintJobId printJobId) {
            PrintJobStateChangeListener listener = mWeakListener.get();
            if (listener != null) {
                listener.onPrintJobsStateChanged(printJobId);
            }
        }
    }
}
