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

import android.app.Activity;
import android.app.Dialog;
import android.app.LoaderManager;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.print.ILayoutResultCallback;
import android.print.IPrintDocumentAdapter;
import android.print.IWriteResultCallback;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnClickListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Activity for configuring a print job.
 */
public class PrintJobConfigActivity extends Activity {

    private static final String LOG_TAG = "PrintJobConfigActivity";

    private static final boolean DEBUG = true && Build.IS_DEBUGGABLE;

    private static final boolean LIVE_PREVIEW_SUPPORTED = false;

    public static final String EXTRA_PRINT_DOCUMENT_ADAPTER = "printDocumentAdapter";
    public static final String EXTRA_PRINT_ATTRIBUTES = "printAttributes";
    public static final String EXTRA_PRINT_JOB_ID = "printJobId";

    public static final String INTENT_EXTRA_PRINTER_ID = "INTENT_EXTRA_PRINTER_ID";

    private static final int LOADER_ID_PRINTERS_LOADER = 1;

    private static final int DEST_ADAPTER_MAX_ITEM_COUNT = 9;

    private static final int DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF = Integer.MAX_VALUE;
    private static final int DEST_ADAPTER_ITEM_ID_ALL_PRINTERS = Integer.MAX_VALUE - 1;

    private static final int ACTIVITY_REQUEST_CREATE_FILE = 1;
    private static final int ACTIVITY_REQUEST_SELECT_PRINTER = 2;

    private static final int CONTROLLER_STATE_FINISHED = 1;
    private static final int CONTROLLER_STATE_FAILED = 2;
    private static final int CONTROLLER_STATE_CANCELLED = 3;
    private static final int CONTROLLER_STATE_INITIALIZED = 4;
    private static final int CONTROLLER_STATE_STARTED = 5;
    private static final int CONTROLLER_STATE_LAYOUT_STARTED = 6;
    private static final int CONTROLLER_STATE_LAYOUT_COMPLETED = 7;
    private static final int CONTROLLER_STATE_WRITE_STARTED = 8;
    private static final int CONTROLLER_STATE_WRITE_COMPLETED = 9;

    private static final int EDITOR_STATE_INITIALIZED = 1;
    private static final int EDITOR_STATE_CONFIRMED_PRINT = 2;
//    private static final int EDITOR_STATE_CONFIRMED_PREVIEW = 3;
    private static final int EDITOR_STATE_CANCELLED = 4;

    private static final int MIN_COPIES = 1;
    private static final String MIN_COPIES_STRING = String.valueOf(MIN_COPIES);

    private static final Pattern PATTERN_DIGITS = Pattern.compile("\\d");

    private static final Pattern PATTERN_ESCAPE_SPECIAL_CHARS = Pattern.compile(
            "(?=[]\\[+&|!(){}^\"~*?:\\\\])");

    private static final Pattern PATTERN_PAGE_RANGE = Pattern.compile(
            "([0-9]+[\\s]*[\\-]?[\\s]*[0-9]*[\\s]*[,]?[\\s]*)+");

    public static final PageRange[] ALL_PAGES_ARRAY = new PageRange[] {PageRange.ALL_PAGES};

    private final PrintAttributes mOldPrintAttributes = new PrintAttributes.Builder().create();
    private final PrintAttributes mCurrPrintAttributes = new PrintAttributes.Builder().create();
    private final PrintAttributes mTempPrintAttributes = new PrintAttributes.Builder().create();

    private final DeathRecipient mDeathRecipient = new DeathRecipient() {
        @Override
        public void binderDied() {
            finish();
        }
    };

    private Editor mEditor;
    private Document mDocument;
    private PrintController mController;

    private int mPrintJobId;

    private IBinder mIPrintDocumentAdapter;

    private Dialog mGeneratingPrintJobDialog;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        Bundle extras = getIntent().getExtras();

        mPrintJobId = extras.getInt(EXTRA_PRINT_JOB_ID, -1);
        if (mPrintJobId < 0) {
            throw new IllegalArgumentException("Invalid print job id: " + mPrintJobId);
        }

        mIPrintDocumentAdapter = extras.getBinder(EXTRA_PRINT_DOCUMENT_ADAPTER);
        if (mIPrintDocumentAdapter == null) {
            throw new IllegalArgumentException("PrintDocumentAdapter cannot be null");
        }

        PrintAttributes attributes = getIntent().getParcelableExtra(EXTRA_PRINT_ATTRIBUTES);
        if (attributes != null) {
            mCurrPrintAttributes.copyFrom(attributes);
        }

        setContentView(R.layout.print_job_config_activity_container);

        mDocument = new Document();
        mController = new PrintController(new RemotePrintDocumentAdapter(
                IPrintDocumentAdapter.Stub.asInterface(mIPrintDocumentAdapter),
                PrintSpoolerService.peekInstance().generateFileForPrintJob(mPrintJobId)));
        mEditor = new Editor();

        try {
            mIPrintDocumentAdapter.linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException re) {
            finish();
            return;
        }

        mController.initialize();
        mEditor.initialize();
    }

    @Override
    public void onResume() {
        super.onResume();
        mEditor.refreshCurrentPrinter();
    }

    @Override
    protected void onDestroy() {
        // We can safely do the work in here since at this point
        // the system is bound to our (spooler) process which
        // guarantees that this process will not be killed.
        if (mController.hasStarted()) {
            mController.finish();
        }
        if (mEditor.isPrintConfirmed() && mController.isFinished()) {
            PrintSpoolerService.peekInstance().setPrintJobState(mPrintJobId,
                    PrintJobInfo.STATE_QUEUED, null);
        } else {
            PrintSpoolerService.peekInstance().setPrintJobState(mPrintJobId,
                    PrintJobInfo.STATE_CANCELED, null);
        }
        mIPrintDocumentAdapter.unlinkToDeath(mDeathRecipient, 0);
        if (mGeneratingPrintJobDialog != null) {
            mGeneratingPrintJobDialog.dismiss();
            mGeneratingPrintJobDialog = null;
        }
        super.onDestroy();
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!mEditor.isPrintConfirmed() && !mEditor.isPreviewConfirmed()
                && mEditor.shouldCloseOnTouch(event)) {
            if (!mController.isWorking()) {
                PrintJobConfigActivity.this.finish();
            }
            mEditor.cancel();
            return true;
        }
        return super.onTouchEvent(event);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
        }
        return super.onKeyDown(keyCode, event);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mEditor.isShwoingGeneratingPrintJobUi()) {
                return true;
            }
            if (event.isTracking() && !event.isCanceled()) {
                if (!mController.isWorking()) {
                    PrintJobConfigActivity.this.finish();
                }
            }
            mEditor.cancel();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean printAttributesChanged() {
        return !mOldPrintAttributes.equals(mCurrPrintAttributes);
    }

    private class PrintController {
        private final AtomicInteger mRequestCounter = new AtomicInteger();

        private final RemotePrintDocumentAdapter mRemotePrintAdapter;

        private final Bundle mMetadata;

        private final ControllerHandler mHandler;

        private final LayoutResultCallback mLayoutResultCallback;

        private final WriteResultCallback mWriteResultCallback;

        private int mControllerState = CONTROLLER_STATE_INITIALIZED;

        private boolean mHasStarted;

        private PageRange[] mRequestedPages;

        public PrintController(RemotePrintDocumentAdapter adapter) {
            mRemotePrintAdapter = adapter;
            mMetadata = new Bundle();
            mHandler = new ControllerHandler(getMainLooper());
            mLayoutResultCallback = new LayoutResultCallback(mHandler);
            mWriteResultCallback = new WriteResultCallback(mHandler);
        }

        public void initialize() {
            mHasStarted = false;
            mControllerState = CONTROLLER_STATE_INITIALIZED;
        }

        public void cancel() {
            mControllerState = CONTROLLER_STATE_CANCELLED;
        }

        public boolean isCancelled() {
            return (mControllerState == CONTROLLER_STATE_CANCELLED);
        }

        public boolean isFinished() {
            return (mControllerState == CONTROLLER_STATE_FINISHED);
        }

        public boolean hasStarted() {
            return mHasStarted;
        }

        public boolean hasPerformedLayout() {
            return mControllerState >= CONTROLLER_STATE_LAYOUT_COMPLETED;
        }

        public boolean isWorking() {
            return mControllerState == CONTROLLER_STATE_LAYOUT_STARTED
                    || mControllerState == CONTROLLER_STATE_WRITE_STARTED;
        }

        public void start() {
            mControllerState = CONTROLLER_STATE_STARTED;
            mHasStarted = true;
            mRemotePrintAdapter.start();
        }

        public void update() {
            if (!mController.hasStarted()) {
                mController.start();
            }
            if (!printAttributesChanged()) {
                // If the attributes changed, then we do not do a layout but may
                // have to ask the app to write some pages. Hence, pretend layout
                // completed and nothing changed, so we handle writing as usual.
                handleOnLayoutFinished(mDocument.info, false, mRequestCounter.get());
            } else {
                PrintSpoolerService.peekInstance().setPrintJobAttributesNoPersistence(
                        mPrintJobId, mCurrPrintAttributes);

                mMetadata.putBoolean(PrintDocumentAdapter.METADATA_KEY_PRINT_PREVIEW,
                        !mEditor.isPrintConfirmed());

                mControllerState = CONTROLLER_STATE_LAYOUT_STARTED;

                mRemotePrintAdapter.layout(mOldPrintAttributes, mCurrPrintAttributes,
                        mLayoutResultCallback, mMetadata, mRequestCounter.incrementAndGet());

                mOldPrintAttributes.copyFrom(mCurrPrintAttributes);
            }
        }

        public void finish() {
            mControllerState = CONTROLLER_STATE_FINISHED;
            mRemotePrintAdapter.finish();
        }

        private void handleOnLayoutFinished(PrintDocumentInfo info,
                boolean layoutChanged, int sequence) {
            if (mRequestCounter.get() != sequence) {
                return;
            }

            if (isCancelled()) {
                mEditor.updateUi();
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            mControllerState = CONTROLLER_STATE_LAYOUT_COMPLETED;

            // If the info changed, we update the document and the print job.
            final boolean infoChanged = !info.equals(mDocument.info);
            if (infoChanged) {
                mDocument.info = info;
                // Set the info.
                PrintSpoolerService.peekInstance().setPrintJobPrintDocumentInfoNoPersistence(
                        mPrintJobId, info);
            }

            // Update the fitting mode based on the document type.
            updateCurrentFittingMode(info);

            // If the document info or the layout changed, then
            // drop the pages since we have to fetch them again.
            if (infoChanged || layoutChanged) {
                mDocument.pages = null;
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(
                        mPrintJobId, null);
            }

            // No pages means that the user selected an invalid range while we
            // were doing a layout or the layout returned a document info for
            // which the selected range is invalid. In such a case we do not
            // write anything and wait for the user to fix the range which will
            // trigger an update.
            mRequestedPages = mEditor.getRequestedPages();
            if (mRequestedPages == null) {
                mEditor.updateUi();
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            // If the info and the layout did not change and we already have
            // the requested pages, then nothing else to do.
            if (!infoChanged && !layoutChanged
                    && PageRangeUtils.contains(mDocument.pages, mRequestedPages)) {
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            // If we do not support live preview and the current layout is
            // not for preview purposes, i.e. the user did not poke the
            // preview button, then just skip the write.
            if (!LIVE_PREVIEW_SUPPORTED && !mEditor.isPreviewConfirmed()
                    && mMetadata.getBoolean(PrintDocumentAdapter.METADATA_KEY_PRINT_PREVIEW)) {
                mEditor.updateUi();
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            mEditor.updateUi();

            // Request a write of the pages of interest.
            mControllerState = CONTROLLER_STATE_WRITE_STARTED;
            mRemotePrintAdapter.write(mRequestedPages, mWriteResultCallback,
                    mRequestCounter.incrementAndGet());
        }

        private void handleOnLayoutFailed(CharSequence error, int sequence) {
            if (mRequestCounter.get() != sequence) {
                return;
            }
            mControllerState = CONTROLLER_STATE_FAILED;
            // TODO: We need some UI for announcing an error.
            Log.e(LOG_TAG, "Error during layout: " + error);
            PrintJobConfigActivity.this.finish();
        }

        private void handleOnWriteFinished(PageRange[] pages, int sequence) {
            if (mRequestCounter.get() != sequence) {
                return;
            }

            if (isCancelled()) {
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            mControllerState = CONTROLLER_STATE_WRITE_COMPLETED;

            // Update the document size.
            File file = PrintSpoolerService.peekInstance()
                    .generateFileForPrintJob(mPrintJobId);
            mDocument.info.setDataSize(file.length());

            // Update which pages we have fetched.
            mDocument.pages = PageRangeUtils.normalize(pages);

            if (DEBUG) {
                Log.i(LOG_TAG, "Requested: " + Arrays.toString(mRequestedPages)
                        + " and got: " + Arrays.toString(mDocument.pages));
            }

            // Adjust the print job pages based on what was requested and written.
            // The cases are ordered in the most expected to the least expected.
            if (Arrays.equals(mDocument.pages, mRequestedPages)) {
                // We got a document with exactly the pages we wanted. Hence,
                // the printer has to print all pages in the data.
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(mPrintJobId,
                        ALL_PAGES_ARRAY);
            } else if (Arrays.equals(mDocument.pages, ALL_PAGES_ARRAY)) {
                // We requested specific pages but got all of them. Hence,
                // the printer has to print only the requested pages.
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(mPrintJobId,
                        mRequestedPages);
            } else if (PageRangeUtils.contains(mDocument.pages, mRequestedPages)) {
                // We requested specific pages and got more but not all pages.
                // Hence, we have to offset appropriately the printed pages to
                // exclude the pages we did not request. Note that pages is
                // guaranteed to be not null and not empty.
                final int offset = mDocument.pages[0].getStart() - pages[0].getStart();
                PageRange[] offsetPages = Arrays.copyOf(mDocument.pages, mDocument.pages.length);
                PageRangeUtils.offsetStart(offsetPages, offset);
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(mPrintJobId,
                        offsetPages);
            } else if (Arrays.equals(mRequestedPages, ALL_PAGES_ARRAY)
                    && mDocument.pages.length == 1 && mDocument.pages[0].getStart() == 0
                    && mDocument.pages[0].getEnd() == mDocument.info.getPageCount() - 1) {
                // We requested all pages via the special constant and got all
                // of them as an explicit enumeration. Hence, the printer has
                // to print only the requested pages.
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(mPrintJobId,
                        mDocument.pages);
            } else {
                // We did not get the pages we requested, then the application
                // misbehaves, so we fail quickly.
                // TODO: We need some UI for announcing an error.
                mControllerState = CONTROLLER_STATE_FAILED;
                Log.e(LOG_TAG, "Received invalid pages from the app");
                PrintJobConfigActivity.this.finish();
            }

            if (mEditor.isDone()) {
                // Update the print attributes based on whether the application
                // handled some of the print attribute constraints, e.g. rotation.
                updateAndSaveCurrentPrintAttributes(mDocument.info);

                if (mEditor.isPrintingToPdf()) {
                    PrintJobInfo printJob = PrintSpoolerService.peekInstance()
                            .getPrintJobInfo(mPrintJobId, PrintManager.APP_ID_ANY);
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.setType("application/pdf");
                    intent.putExtra(Intent.EXTRA_TITLE, printJob.getLabel());
                    startActivityForResult(intent, ACTIVITY_REQUEST_CREATE_FILE);
                } else {
                    PrintJobConfigActivity.this.finish();
                }
            }
        }

        private void handleOnWriteFailed(CharSequence error, int sequence) {
            if (mRequestCounter.get() != sequence) {
                return;
            }
            mControllerState = CONTROLLER_STATE_FAILED;
            Log.e(LOG_TAG, "Error during write: " + error);
            PrintJobConfigActivity.this.finish();
        }

        private void updateCurrentFittingMode(PrintDocumentInfo document) {
            // Update the fitting mode based on content type.
            switch (document.getContentType()) {
                case PrintDocumentInfo.CONTENT_TYPE_DOCUMENT: {
                    mCurrPrintAttributes.setFittingMode(
                            PrintAttributes.FITTING_MODE_SCALE_TO_FIT);
                } break;

                case PrintDocumentInfo.CONTENT_TYPE_PHOTO: {
                    mCurrPrintAttributes.setFittingMode(
                            PrintAttributes.FITTING_MODE_SCALE_TO_FILL);
                }
            }
        }

        private void updateAndSaveCurrentPrintAttributes(PrintDocumentInfo document) {
            PrintAttributes attributes = mTempPrintAttributes;
            attributes.copyFrom(mCurrPrintAttributes);

            // Update the orientation
            if (document.getOrientation() == PrintAttributes.ORIENTATION_LANDSCAPE) {
                if (attributes.getOrientation() == PrintAttributes.ORIENTATION_LANDSCAPE) {
                    // If the document is in landscape and we want to print it in
                    // landscape, then we do not need to rotate, so portrait.
                    attributes.setOrientation(PrintAttributes.ORIENTATION_PORTRAIT);
                } else {
                    // If the document is in landscape and we want to print it in
                    // portrait, then we have to rotate the content, so landscape.
                    attributes.setOrientation(PrintAttributes.ORIENTATION_LANDSCAPE);
                }
            }

            // Update margins.
            Margins documentMargins = document.getMargins();
            if (documentMargins.getLeftMils() != 0
                    || documentMargins.getTopMils() != 0
                    || documentMargins.getRightMils() != 0
                    || documentMargins.getBottomMils() != 0) {
                // If the application has applied some of the margins, then
                // the printer should only apply the difference.
                Margins oldMargins = attributes.getMargins();
                attributes.setMargins(new Margins(
                        oldMargins.getLeftMils() - documentMargins.getLeftMils(),
                        oldMargins.getTopMils() - documentMargins.getTopMils(),
                        oldMargins.getRightMils() - documentMargins.getRightMils(),
                        oldMargins.getBottomMils() - documentMargins.getBottomMils()));
            }

            PrintSpoolerService.peekInstance().setPrintJobAttributesNoPersistence(
                    mPrintJobId, attributes);
        }

        private final class ControllerHandler extends Handler {
            public static final int MSG_ON_LAYOUT_FINISHED = 1;
            public static final int MSG_ON_LAYOUT_FAILED = 2;
            public static final int MSG_ON_WRITE_FINISHED = 3;
            public static final int MSG_ON_WRITE_FAILED = 4;

            public ControllerHandler(Looper looper) {
                super(looper, null, false);
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_ON_LAYOUT_FINISHED: {
                        PrintDocumentInfo info = (PrintDocumentInfo) message.obj;
                        final boolean changed = (message.arg1 == 1);
                        final int sequence = message.arg2;
                        handleOnLayoutFinished(info, changed, sequence);
                    } break;

                    case MSG_ON_LAYOUT_FAILED: {
                        CharSequence error = (CharSequence) message.obj;
                        final int sequence = message.arg1;
                        handleOnLayoutFailed(error, sequence);
                    } break;

                    case MSG_ON_WRITE_FINISHED: {
                        PageRange[] pages = (PageRange[]) message.obj;
                        final int sequence = message.arg1;
                        handleOnWriteFinished(pages, sequence);
                    } break;

                    case MSG_ON_WRITE_FAILED: {
                        CharSequence error = (CharSequence) message.obj;
                        final int sequence = message.arg1;
                        handleOnWriteFailed(error, sequence);
                    } break;
                }
            }
        }
    }

    private static final class LayoutResultCallback extends ILayoutResultCallback.Stub {
        private final WeakReference<PrintController.ControllerHandler> mWeakHandler;

        public LayoutResultCallback(PrintController.ControllerHandler handler) {
            mWeakHandler = new WeakReference<PrintController.ControllerHandler>(handler);
        }

        @Override
        public void onLayoutFinished(PrintDocumentInfo info, boolean changed, int sequence) {
            Handler handler = mWeakHandler.get();
            if (handler != null) {
                handler.obtainMessage(PrintController.ControllerHandler.MSG_ON_LAYOUT_FINISHED,
                        changed ? 1 : 0, sequence, info).sendToTarget();
            }
        }

        @Override
        public void onLayoutFailed(CharSequence error, int sequence) {
            Handler handler = mWeakHandler.get();
            if (handler != null) {
                handler.obtainMessage(PrintController.ControllerHandler.MSG_ON_LAYOUT_FAILED,
                        sequence, 0, error).sendToTarget();
            }
        }
    }

    private static final class WriteResultCallback extends IWriteResultCallback.Stub {
        private final WeakReference<PrintController.ControllerHandler> mWeakHandler;

        public WriteResultCallback(PrintController.ControllerHandler handler) {
            mWeakHandler = new WeakReference<PrintController.ControllerHandler>(handler);
        }

        @Override
        public void onWriteFinished(PageRange[] pages, int sequence) {
            Handler handler = mWeakHandler.get();
            if (handler != null) {
                handler.obtainMessage(PrintController.ControllerHandler.MSG_ON_WRITE_FINISHED,
                        sequence, 0, pages).sendToTarget();
            }
        }

        @Override
        public void onWriteFailed(CharSequence error, int sequence) {
            Handler handler = mWeakHandler.get();
            if (handler != null) {
                handler.obtainMessage(PrintController.ControllerHandler.MSG_ON_WRITE_FAILED,
                    sequence, 0, error).sendToTarget();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case ACTIVITY_REQUEST_CREATE_FILE: {
                if (data != null) {
                    Uri uri = data.getData();
                    writePrintJobDataAndFinish(uri);
                } else {
                    mEditor.showUi(Editor.UI_EDITING_PRINT_JOB,
                            new Runnable() {
                        @Override
                        public void run() {
                            mEditor.initialize();
                            mEditor.bindUi();
                            mEditor.updateUi();
                        }
                    });
                }
            } break;

            case ACTIVITY_REQUEST_SELECT_PRINTER: {
                if (resultCode == RESULT_OK) {
                    PrinterId printerId = (PrinterId) data.getParcelableExtra(
                            INTENT_EXTRA_PRINTER_ID);
                    mEditor.selectPrinter(printerId);
                }
            } break;
        }
    }

    private void writePrintJobDataAndFinish(final Uri uri) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                InputStream in = null;
                OutputStream out = null;
                try {
                    PrintJobInfo printJob = PrintSpoolerService.peekInstance()
                            .getPrintJobInfo(mPrintJobId, PrintManager.APP_ID_ANY);
                    if (printJob == null) {
                        return null;
                    }
                    File file = PrintSpoolerService.peekInstance()
                            .generateFileForPrintJob(mPrintJobId);
                    in = new FileInputStream(file);
                    out = getContentResolver().openOutputStream(uri);
                    final byte[] buffer = new byte[8192];
                    while (true) {
                        final int readByteCount = in.read(buffer);
                        if (readByteCount < 0) {
                            break;
                        }
                        out.write(buffer, 0, readByteCount);
                    }
                } catch (FileNotFoundException fnfe) {
                    Log.e(LOG_TAG, "Error writing print job data!", fnfe);
                } catch (IOException ioe) {
                    Log.e(LOG_TAG, "Error writing print job data!", ioe);
                } finally {
                    IoUtils.closeQuietly(in);
                    IoUtils.closeQuietly(out);
                }
                return null;
            }

            @Override
            public void onPostExecute(Void result) {
                mEditor.cancel();
                PrintJobConfigActivity.this.finish();
            }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
    }

    private final class Editor {
        private static final int UI_NONE = 0;
        private static final int UI_EDITING_PRINT_JOB = 1;
        private static final int UI_GENERATING_PRINT_JOB = 2;

        private EditText mCopiesEditText;

        private TextView mRangeOptionsTitle;
        private TextView mPageRangeTitle;
        private EditText mPageRangeEditText;

        private Spinner mDestinationSpinner;
        private final DestinationAdapter mDestinationSpinnerAdapter;

        private Spinner mMediaSizeSpinner;
        private final ArrayAdapter<SpinnerItem<MediaSize>> mMediaSizeSpinnerAdapter;

        private Spinner mColorModeSpinner;
        private final ArrayAdapter<SpinnerItem<Integer>> mColorModeSpinnerAdapter;

        private Spinner mOrientationSpinner;
        private final  ArrayAdapter<SpinnerItem<Integer>> mOrientationSpinnerAdapter;

        private Spinner mRangeOptionsSpinner;
        private final ArrayAdapter<SpinnerItem<Integer>> mRangeOptionsSpinnerAdapter;

        private final SimpleStringSplitter mStringCommaSplitter =
                new SimpleStringSplitter(',');

        private View mContentContainer;

        private Button mPrintButton;

        private PrinterInfo mCurrentPrinter;

        private final OnItemSelectedListener mOnItemSelectedListener =
                new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> spinner, View view, int position, long id) {
                if (spinner == mDestinationSpinner) {
                    if (mIgnoreNextDestinationChange) {
                        mIgnoreNextDestinationChange = false;
                        return;
                    }

                    if (id == DEST_ADAPTER_ITEM_ID_ALL_PRINTERS) {
                        startSelectPrinterActivity();
                        return;
                    }

                    mCurrPrintAttributes.clear();

                    PrinterInfo printer = (PrinterInfo) mDestinationSpinnerAdapter
                            .getItem(position);

                    PrintSpoolerService.peekInstance().setPrintJobPrinterNoPersistence(
                            mPrintJobId, printer);

                    if (printer != null) {
                        PrinterCapabilitiesInfo capabilities = printer.getCapabilities();
                        if (capabilities == null) {
                            //TODO: We need a timeout for the update.
                            mEditor.refreshCurrentPrinter();
                        } else {
                            capabilities.getDefaults(mCurrPrintAttributes);
                            if (!mController.hasStarted()) {
                                mController.start();
                            }
                            mController.update();
                        }
                    }

                    mCurrentPrinter = printer;

                    updateUiForNewPrinterCapabilities();
                } else if (spinner == mMediaSizeSpinner) {
                    if (mIgnoreNextMediaSizeChange) {
                        mIgnoreNextMediaSizeChange = false;
                        return;
                    }
                    SpinnerItem<MediaSize> mediaItem = mMediaSizeSpinnerAdapter.getItem(position);
                    mCurrPrintAttributes.setMediaSize(mediaItem.value);
                    if (!hasErrors()) {
                        mController.update();
                    }
                } else if (spinner == mColorModeSpinner) {
                    if (mIgnoreNextColorModeChange) {
                        mIgnoreNextColorModeChange = false;
                        return;
                    }
                    SpinnerItem<Integer> colorModeItem =
                            mColorModeSpinnerAdapter.getItem(position);
                    mCurrPrintAttributes.setColorMode(colorModeItem.value);
                    if (!hasErrors()) {
                        mController.update();
                    }
                } else if (spinner == mOrientationSpinner) {
                    if (mIgnoreNextOrientationChange) {
                        mIgnoreNextOrientationChange = false;
                        return;
                    }
                    SpinnerItem<Integer> orientationItem =
                            mOrientationSpinnerAdapter.getItem(position);
                    mCurrPrintAttributes.setOrientation(orientationItem.value);
                    if (!hasErrors()) {
                        mController.update();
                    }
                } else if (spinner == mRangeOptionsSpinner) {
                    if (mIgnoreNextRangeOptionChange) {
                        mIgnoreNextRangeOptionChange = false;
                        return;
                    }
                    updateUi();
                    if (!hasErrors()) {
                        mController.update();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                /* do nothing*/
            }
        };

        private final TextWatcher mCopiesTextWatcher = new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                /* do nothing */
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                /* do nothing */
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (mIgnoreNextCopiesChange) {
                    mIgnoreNextCopiesChange = false;
                    return;
                }

                final boolean hadErrors = hasErrors();

                if (editable.length() == 0) {
                    mCopiesEditText.setError("");
                    updateUi();
                    return;
                }

                int copies = 0;
                try {
                    copies = Integer.parseInt(editable.toString());
                } catch (NumberFormatException nfe) {
                    /* ignore */
                }

                if (copies < MIN_COPIES) {
                    mCopiesEditText.setError("");
                    updateUi();
                    return;
                }

                mCopiesEditText.setError(null);
                PrintSpoolerService.peekInstance().setPrintJobCopiesNoPersistence(
                        mPrintJobId, copies);
                updateUi();

                if (hadErrors && !hasErrors() && printAttributesChanged()) {
                    mController.update();
                }
            }
        };

        private final TextWatcher mRangeTextWatcher = new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                /* do nothing */
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                /* do nothing */
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (mIgnoreNextRangeChange) {
                    mIgnoreNextRangeChange = false;
                    return;
                }

                final boolean hadErrors = hasErrors();

                String text = editable.toString();

                if (TextUtils.isEmpty(text)) {
                    mPageRangeEditText.setError("");
                    updateUi();
                    return;
                }

                String escapedText = PATTERN_ESCAPE_SPECIAL_CHARS.matcher(text).replaceAll("////");
                if (!PATTERN_PAGE_RANGE.matcher(escapedText).matches()) {
                    mPageRangeEditText.setError("");
                    updateUi();
                    return;
                }

                Matcher matcher = PATTERN_DIGITS.matcher(text);
                while (matcher.find()) {
                    String numericString = text.substring(matcher.start(), matcher.end());
                    final int pageIndex = Integer.parseInt(numericString);
                    if (pageIndex < 1 || pageIndex > mDocument.info.getPageCount()) {
                        mPageRangeEditText.setError("");
                        updateUi();
                        return;
                    }
                }

                //TODO: Catch the error if start is less grater than the end.

                mPageRangeEditText.setError(null);
                mPrintButton.setEnabled(true);
                updateUi();

                if (hadErrors && !hasErrors() && printAttributesChanged()) {
                    updateUi();
                }
            }
        };

        private int mEditorState;

        private boolean mIgnoreNextDestinationChange;
        private boolean mIgnoreNextMediaSizeChange;
        private boolean mIgnoreNextColorModeChange;
        private boolean mIgnoreNextOrientationChange;
        private boolean mIgnoreNextRangeOptionChange;
        private boolean mIgnoreNextCopiesChange;
        private boolean mIgnoreNextRangeChange;

        private int mCurrentUi = UI_NONE;

        private boolean mFavoritePrinterSelected;

        public Editor() {
            // Destination.
            mDestinationSpinnerAdapter = new DestinationAdapter();
            mDestinationSpinnerAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    // Initially, we have only sage to PDF as a printer but after some
                    // printers are loaded we want to select the user's favorite one
                    // which is the first.
                    if (!mFavoritePrinterSelected && mDestinationSpinnerAdapter.getCount() > 2) {
                        mFavoritePrinterSelected = true;
                        mDestinationSpinner.setSelection(0);
                    }

                    // If the current printer properties changed, we update the UI.
                    if (mCurrentPrinter != null) {
                        final int printerCount = mDestinationSpinnerAdapter.getCount();
                        for (int i = 0; i < printerCount; i++) {
                            Object item = mDestinationSpinnerAdapter.getItem(i);
                            // Some items are not printers
                            if (item instanceof PrinterInfo) {
                                PrinterInfo printer = (PrinterInfo) item;
                                if (!printer.getId().equals(mCurrentPrinter.getId())) {
                                    continue;
                                }

                                // Update the UI if capabilities changed.
                                boolean capabilitiesChanged = false;

                                if (mCurrentPrinter.getCapabilities() == null) {
                                    if (printer.getCapabilities() != null) {
                                        capabilitiesChanged = true;
                                    }
                                } else if (!mCurrentPrinter.getCapabilities().equals(
                                        printer.getCapabilities())) {
                                    capabilitiesChanged = true;
                                }

                                if (capabilitiesChanged) {
                                    // Update the current printer.
                                    mCurrentPrinter.copyFrom(printer);

                                    // If something changed during UI update...
                                    if (updateUi()) {
                                        // Update current attributes.
                                        printer.getCapabilities().getDefaults(mCurrPrintAttributes);
                                        // Update the document.
                                        mController.update();
                                    }
                                }

                                break;
                            }
                        }
                    }
                }

                @Override
                public void onInvalidated() {
                    updateUiForNewPrinterCapabilities();
                }
            });

            // Media size.
            mMediaSizeSpinnerAdapter = new ArrayAdapter<SpinnerItem<MediaSize>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);

            // Color mode.
            mColorModeSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);

            // Orientation
            mOrientationSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);

            // Range options
            mRangeOptionsSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);
            final int[] rangeOptionsValues = getResources().getIntArray(
                    R.array.page_options_values);
            String[] rangeOptionsLabels = getResources().getStringArray(
                    R.array.page_options_labels);
            final int rangeOptionsCount = rangeOptionsLabels.length;
            for (int i = 0; i < rangeOptionsCount; i++) {
                mRangeOptionsSpinnerAdapter.add(new SpinnerItem<Integer>(
                        rangeOptionsValues[i], rangeOptionsLabels[i]));
            }

            showUi(UI_EDITING_PRINT_JOB, null);
            bindUi();
            updateUi();
        }

        public void refreshCurrentPrinter() {
            PrinterInfo printer = (PrinterInfo) mDestinationSpinner.getSelectedItem();
            if (printer != null) {
                FusedPrintersProvider printersLoader = (FusedPrintersProvider)
                        (Loader<?>) getLoaderManager().getLoader(
                                LOADER_ID_PRINTERS_LOADER);
                if (printersLoader != null) {
                    printersLoader.setTrackedPrinter(printer.getId());
                }
            }
        }

        public void selectPrinter(PrinterId printerId) {
            mDestinationSpinnerAdapter.ensurePrinterShownPrinterShown(printerId);
            final int position = mDestinationSpinnerAdapter.getPrinterIndex(printerId);
            mDestinationSpinner.setSelection(position);
        }

        public boolean isPrintingToPdf() {
            return mDestinationSpinner.getSelectedItem()
                    == mDestinationSpinnerAdapter.mFakePdfPrinter;
        }

        public boolean shouldCloseOnTouch(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_DOWN) {
                return false;
            }

            final int[] locationInWindow = new int[2];
            mContentContainer.getLocationInWindow(locationInWindow);

            final int windowTouchSlop = ViewConfiguration.get(PrintJobConfigActivity.this)
                    .getScaledWindowTouchSlop();
            final int eventX = (int) event.getX();
            final int eventY = (int) event.getY();
            final int lenientWindowLeft = locationInWindow[0] - windowTouchSlop;
            final int lenientWindowRight = lenientWindowLeft + mContentContainer.getWidth()
                    + windowTouchSlop;
            final int lenientWindowTop = locationInWindow[1] - windowTouchSlop;
            final int lenientWindowBottom = lenientWindowTop + mContentContainer.getHeight()
                    + windowTouchSlop;

            if (eventX < lenientWindowLeft || eventX > lenientWindowRight
                    || eventY < lenientWindowTop || eventY > lenientWindowBottom) {
                return true;
            }
            return false;
        }

        public boolean isShwoingGeneratingPrintJobUi() {
            return (mCurrentUi == UI_GENERATING_PRINT_JOB);
        }

        public void showUi(int ui, final Runnable postSwitchCallback) {
            if (ui == UI_NONE) {
                throw new IllegalStateException("cannot remove the ui");
            }

            if (mCurrentUi == ui) {
                return;
            }

            switch (mCurrentUi) {
                case UI_NONE: {
                    switch (ui) {
                        case UI_EDITING_PRINT_JOB: {
                            doUiSwitch(R.layout.print_job_config_activity_content_editing);
                            registerPrintButtonClickListener();
                            if (postSwitchCallback != null) {
                                postSwitchCallback.run();
                            }
                        } break;

                        case UI_GENERATING_PRINT_JOB: {
                            doUiSwitch(R.layout.print_job_config_activity_content_generating);
                            registerCancelButtonClickListener();
                            if (postSwitchCallback != null) {
                                postSwitchCallback.run();
                            }
                        } break;
                    }
                } break;

                case UI_EDITING_PRINT_JOB: {
                    switch (ui) {
                        case UI_GENERATING_PRINT_JOB: {
                            animateUiSwitch(R.layout.print_job_config_activity_content_generating,
                                    new Runnable() {
                                @Override
                                public void run() {
                                    registerCancelButtonClickListener();
                                    if (postSwitchCallback != null) {
                                        postSwitchCallback.run();
                                    }
                                }
                            });
                        } break;
                    }
                } break;

                case UI_GENERATING_PRINT_JOB: {
                    switch (ui) {
                        case UI_EDITING_PRINT_JOB: {
                            animateUiSwitch(R.layout.print_job_config_activity_content_editing,
                                    new Runnable() {
                                @Override
                                public void run() {
                                    registerPrintButtonClickListener();
                                    if (postSwitchCallback != null) {
                                        postSwitchCallback.run();
                                    }
                                }
                            });
                        } break;
                    }
                } break;
            }

            mCurrentUi = ui;
        }

        private void registerPrintButtonClickListener() {
            Button printButton = (Button) findViewById(R.id.print_button);
            printButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    PrinterInfo printer = (PrinterInfo) mDestinationSpinner.getSelectedItem();
                    if (printer != null) {
                        mEditor.confirmPrint();
                        mController.update();
                        if (!printer.equals(mDestinationSpinnerAdapter.mFakePdfPrinter)) {
                            mEditor.refreshCurrentPrinter();
                        }
                    } else {
                        mEditor.cancel();
                        PrintJobConfigActivity.this.finish();
                    }
                }
            });
        }

        private void registerCancelButtonClickListener() {
            Button cancelButton = (Button) findViewById(R.id.cancel_button);
            cancelButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!mController.isWorking()) {
                        PrintJobConfigActivity.this.finish();
                    }
                    mEditor.cancel();
                }
            });
        }

        private void doUiSwitch(int showLayoutId) {
            ViewGroup contentContainer = (ViewGroup) findViewById(R.id.content_container);
            contentContainer.removeAllViews();
            getLayoutInflater().inflate(showLayoutId, contentContainer, true);
        }

        private void animateUiSwitch(int showLayoutId, final Runnable postAnimateCommand) {
            // Find everything we will shuffle around.
            final ViewGroup contentContainer = (ViewGroup) findViewById(R.id.content_container);
            final View hidingView = contentContainer.getChildAt(0);
            final View showingView = getLayoutInflater().inflate(showLayoutId,
                    null, false);

            // First animation - fade out the old content.
            hidingView.animate().alpha(0.0f).withLayer().withEndAction(new Runnable() {
                @Override
                public void run() {
                    hidingView.setVisibility(View.INVISIBLE);

                    // Prepare the new content with correct size and alpha.
                    showingView.setMinimumWidth(contentContainer.getWidth());
                    showingView.setAlpha(0.0f);

                    // Compute how to much shrink /stretch the content.
                    final int widthSpec = MeasureSpec.makeMeasureSpec(
                            contentContainer.getWidth(), MeasureSpec.UNSPECIFIED);
                    final int heightSpec = MeasureSpec.makeMeasureSpec(
                            contentContainer.getHeight(), MeasureSpec.UNSPECIFIED);
                    showingView.measure(widthSpec, heightSpec);
                    final float scaleY = (float) showingView.getMeasuredHeight()
                            / (float) contentContainer.getHeight();

                    // Second animation - resize the container.
                    contentContainer.animate().scaleY(scaleY).withLayer().withEndAction(
                            new Runnable() {
                        @Override
                        public void run() {
                            // Swap the old and the new content.
                            contentContainer.removeAllViews();
                            contentContainer.setScaleY(1.0f);
                            contentContainer.addView(showingView);

                            // Third animation - show the new content.
                            showingView.animate().withLayer().alpha(1.0f).withEndAction(
                                    new Runnable() {
                                @Override
                                public void run() {
                                    postAnimateCommand.run();
                                }
                            });
                        }
                    });
                }
            });
        }

        public void initialize() {
            mEditorState = EDITOR_STATE_INITIALIZED;
        }

        public boolean isCancelled() {
            return mEditorState == EDITOR_STATE_CANCELLED;
        }

        public void cancel() {
            mEditorState = EDITOR_STATE_CANCELLED;
            mController.cancel();
            updateUi();
        }

        public boolean isDone() {
            return isPrintConfirmed() || isPreviewConfirmed() || isCancelled();
        }

        public boolean isPrintConfirmed() {
            return mEditorState == EDITOR_STATE_CONFIRMED_PRINT;
        }

        public void confirmPrint() {
            mEditorState = EDITOR_STATE_CONFIRMED_PRINT;
            showUi(UI_GENERATING_PRINT_JOB, null);
        }

        public boolean isPreviewConfirmed() {
            return mEditorState == EDITOR_STATE_CONFIRMED_PRINT;
        }

        public PageRange[] getRequestedPages() {
            if (hasErrors()) {
                return null;
            }
            if (mRangeOptionsSpinner.getSelectedItemPosition() > 0) {
                List<PageRange> pageRanges = new ArrayList<PageRange>();
                mStringCommaSplitter.setString(mPageRangeEditText.getText().toString());

                while (mStringCommaSplitter.hasNext()) {
                    String range = mStringCommaSplitter.next().trim();
                    final int dashIndex = range.indexOf('-');
                    final int fromIndex;
                    final int toIndex;

                    if (dashIndex > 0) {
                        fromIndex = Integer.parseInt(range.substring(0, dashIndex)) - 1;
                        toIndex = Integer.parseInt(range.substring(
                                dashIndex + 1, range.length())) - 1;
                    } else {
                        fromIndex = toIndex = Integer.parseInt(range) - 1;
                    }

                    PageRange pageRange = new PageRange(fromIndex, toIndex);
                    pageRanges.add(pageRange);
                }

                PageRange[] pageRangesArray = new PageRange[pageRanges.size()];
                pageRanges.toArray(pageRangesArray);

                return PageRangeUtils.normalize(pageRangesArray);
            }

            return ALL_PAGES_ARRAY;
        }

        private void bindUi() {
            if (mCurrentUi != UI_EDITING_PRINT_JOB) {
                return;
            }

            // Content container
            mContentContainer = findViewById(R.id.content_container);

            // Copies
            mCopiesEditText = (EditText) findViewById(R.id.copies_edittext);
            mCopiesEditText.setText(MIN_COPIES_STRING);
            mCopiesEditText.addTextChangedListener(mCopiesTextWatcher);
            mCopiesEditText.selectAll();
            if (!TextUtils.equals(mCopiesEditText.getText(), MIN_COPIES_STRING)) {
                mIgnoreNextCopiesChange = true;
            }
            PrintSpoolerService.peekInstance().setPrintJobCopiesNoPersistence(
                    mPrintJobId, MIN_COPIES);

            // Destination.
            mDestinationSpinner = (Spinner) findViewById(R.id.destination_spinner);
            mDestinationSpinner.setAdapter(mDestinationSpinnerAdapter);
            mDestinationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mDestinationSpinnerAdapter.getCount() > 0 && mController.hasStarted()) {
                mIgnoreNextDestinationChange = true;
            }

            // Media size.
            mMediaSizeSpinner = (Spinner) findViewById(R.id.paper_size_spinner);
            mMediaSizeSpinner.setAdapter(mMediaSizeSpinnerAdapter);
            mMediaSizeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mMediaSizeSpinnerAdapter.getCount() > 0) {
                mIgnoreNextMediaSizeChange = true;
            }

            // Color mode.
            mColorModeSpinner = (Spinner) findViewById(R.id.color_spinner);
            mColorModeSpinner.setAdapter(mColorModeSpinnerAdapter);
            mColorModeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mColorModeSpinnerAdapter.getCount() > 0) {
                mIgnoreNextColorModeChange = true;
            }

            // Orientation
            mOrientationSpinner = (Spinner) findViewById(R.id.orientation_spinner);
            mOrientationSpinner.setAdapter(mOrientationSpinnerAdapter);
            mOrientationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mOrientationSpinnerAdapter.getCount() > 0) {
                mIgnoreNextOrientationChange = true;
            }

            // Range options
            mRangeOptionsTitle = (TextView) findViewById(R.id.range_options_title);
            mRangeOptionsSpinner = (Spinner) findViewById(R.id.range_options_spinner);
            mRangeOptionsSpinner.setAdapter(mRangeOptionsSpinnerAdapter);
            mRangeOptionsSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mRangeOptionsSpinnerAdapter.getCount() > 0) {
                mIgnoreNextRangeOptionChange = true;
            }

            // Page range
            mPageRangeTitle = (TextView) findViewById(R.id.page_range_title);
            mPageRangeEditText = (EditText) findViewById(R.id.page_range_edittext);
            mPageRangeEditText.addTextChangedListener(mRangeTextWatcher);

            // Print button
            mPrintButton = (Button) findViewById(R.id.print_button);
            registerPrintButtonClickListener();
        }

        public boolean updateUi() {
            if (mCurrentUi != UI_EDITING_PRINT_JOB) {
                return false;
            }
            if (isPrintConfirmed() || isPreviewConfirmed() || isCancelled()) {
                mDestinationSpinner.setEnabled(false);
                mCopiesEditText.setEnabled(false);
                mMediaSizeSpinner.setEnabled(false);
                mColorModeSpinner.setEnabled(false);
                mOrientationSpinner.setEnabled(false);
                mRangeOptionsSpinner.setEnabled(false);
                mPageRangeEditText.setEnabled(false);
                mPrintButton.setEnabled(false);
                return false;
            }

            // If a printer with capabilities is selected, then we enabled all options.
            boolean allOptionsEnabled = false;
            final int selectedIndex = mDestinationSpinner.getSelectedItemPosition();
            if (selectedIndex >= 0) {
                Object item = mDestinationSpinnerAdapter.getItem(selectedIndex);
                if (item instanceof PrinterInfo) {
                    PrinterInfo printer = (PrinterInfo) item;
                    if (printer.getCapabilities() != null) {
                        allOptionsEnabled = true;
                    }
                }
            }

            if (!allOptionsEnabled) {
                String minCopiesString = String.valueOf(MIN_COPIES);
                if (!TextUtils.equals(mCopiesEditText.getText(), minCopiesString)) {
                    mIgnoreNextCopiesChange = true;
                    mCopiesEditText.setText(minCopiesString);
                }
                mCopiesEditText.setEnabled(false);

                // Media size
                if (mMediaSizeSpinner.getSelectedItemPosition() != AdapterView.INVALID_POSITION) {
                    mIgnoreNextMediaSizeChange = true;
                    mMediaSizeSpinner.setSelection(AdapterView.INVALID_POSITION);
                }
                mMediaSizeSpinner.setEnabled(false);

                // Color mode
                if (mColorModeSpinner.getSelectedItemPosition() != AdapterView.INVALID_POSITION) {
                    mIgnoreNextColorModeChange = true;
                    mColorModeSpinner.setSelection(AdapterView.INVALID_POSITION);
                }
                mColorModeSpinner.setEnabled(false);

                // Orientation
                if (mOrientationSpinner.getSelectedItemPosition() != AdapterView.INVALID_POSITION) {
                    mIgnoreNextOrientationChange = true;
                    mOrientationSpinner.setSelection(AdapterView.INVALID_POSITION);
                }
                mOrientationSpinner.setEnabled(false);

                // Range
                if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                    mIgnoreNextRangeOptionChange = true;
                    mRangeOptionsSpinner.setSelection(0);
                }
                mRangeOptionsSpinner.setEnabled(false);
                mRangeOptionsTitle.setText(getString(R.string.label_pages,
                        getString(R.string.page_count_unknown)));
                if (!TextUtils.equals(mPageRangeEditText.getText(), "")) {
                    mIgnoreNextRangeChange = true;
                    mPageRangeEditText.setText("");
                }

                mPageRangeEditText.setEnabled(false);
                mPageRangeEditText.setVisibility(View.INVISIBLE);
                mPageRangeTitle.setVisibility(View.INVISIBLE);

                // Print
                mPrintButton.setEnabled(false);

                return false;
            } else {
                boolean someAttributeSelectionChanged = false;

                PrintAttributes defaultAttributes = mTempPrintAttributes;
                PrinterInfo printer = (PrinterInfo) mDestinationSpinner.getSelectedItem();
                PrinterCapabilitiesInfo capabilities = printer.getCapabilities();
                printer.getCapabilities().getDefaults(defaultAttributes);

                // Media size.
                List<MediaSize> mediaSizes = capabilities.getMediaSizes();

                // If the media sizes changed, we update the adapter and the spinner.
                boolean mediaSizesChanged = false;
                final int mediaSizeCount = mediaSizes.size();
                if (mediaSizeCount != mMediaSizeSpinnerAdapter.getCount()) {
                    mediaSizesChanged = true;
                } else {
                    for (int i = 0; i < mediaSizeCount; i++) {
                        if (!mediaSizes.get(i).equals(mMediaSizeSpinnerAdapter.getItem(i).value)) {
                            mediaSizesChanged = true;
                            break;
                        }
                    }
                }
                if (mediaSizesChanged) {
                    // Remember the old media size to try selecting it again.
                    int oldMediaSizeNewIndex = AdapterView.INVALID_POSITION;
                    MediaSize oldMediaSize = mCurrPrintAttributes.getMediaSize();

                    // Rebuild the adapter data.
                    mMediaSizeSpinnerAdapter.clear();
                    for (int i = 0; i < mediaSizeCount; i++) {
                        MediaSize mediaSize = mediaSizes.get(i);
                        if (mediaSize.equals(oldMediaSize)) {
                            // Update the index of the old selection.
                            oldMediaSizeNewIndex = i;
                        }
                        mMediaSizeSpinnerAdapter.add(new SpinnerItem<MediaSize>(
                                mediaSize, mediaSize.getLabel()));
                    }

                    if (mediaSizeCount <= 0) {
                        // No media sizes - clear the selection.
                        mMediaSizeSpinner.setEnabled(false);
                        // Clear selection and mark if selection changed.
                        someAttributeSelectionChanged = setMediaSizeSpinnerSelectionNoCallback(
                                AdapterView.INVALID_POSITION);
                    } else {
                        mMediaSizeSpinner.setEnabled(true);

                        if (oldMediaSizeNewIndex != AdapterView.INVALID_POSITION) {
                            // Select the old media size - nothing really changed.
                            setMediaSizeSpinnerSelectionNoCallback(oldMediaSizeNewIndex);
                        } else {
                            // Select the first or the default and mark if selection changed.
                            final int mediaSizeIndex = Math.max(mediaSizes.indexOf(
                                    defaultAttributes.getMediaSize()), 0);
                            someAttributeSelectionChanged = setMediaSizeSpinnerSelectionNoCallback(
                                    mediaSizeIndex);
                        }
                    }
                }
                mMediaSizeSpinner.setEnabled(true);

                // Color mode.
                final int colorModes = capabilities.getColorModes();

                // If the color modes changed, we update the adapter and the spinner.
                boolean colorModesChanged = false;
                if (Integer.bitCount(colorModes) != mColorModeSpinnerAdapter.getCount()) {
                    colorModesChanged = true;
                } else {
                    int remainingColorModes = colorModes;
                    int adapterIndex = 0;
                    while (remainingColorModes != 0) {
                        final int colorBitOffset = Integer.numberOfTrailingZeros(
                                remainingColorModes);
                        final int colorMode = 1 << colorBitOffset;
                        remainingColorModes &= ~colorMode;
                        if (colorMode != mColorModeSpinnerAdapter.getItem(adapterIndex).value) {
                            colorModesChanged = true;
                            break;
                        }
                        adapterIndex++;
                    }
                }
                if (colorModesChanged) {
                    // Remember the old color mode to try selecting it again.
                    int oldColorModeNewIndex = AdapterView.INVALID_POSITION;
                    final int oldColorMode = mCurrPrintAttributes.getColorMode();

                    // Rebuild the adapter data.
                    mColorModeSpinnerAdapter.clear();
                    String[] colorModeLabels = getResources().getStringArray(
                            R.array.color_mode_labels);
                    int remainingColorModes = colorModes;
                    while (remainingColorModes != 0) {
                        final int colorBitOffset = Integer.numberOfTrailingZeros(
                                remainingColorModes);
                        final int colorMode = 1 << colorBitOffset;
                        if (colorMode == oldColorMode) {
                            // Update the index of the old selection.
                            oldColorModeNewIndex = colorBitOffset;
                        }
                        remainingColorModes &= ~colorMode;
                        mColorModeSpinnerAdapter.add(new SpinnerItem<Integer>(colorMode,
                                colorModeLabels[colorBitOffset]));
                    }
                    final int colorModeCount = Integer.bitCount(colorModes);
                    if (colorModeCount <= 0) {
                        mColorModeSpinner.setEnabled(false);
                        mColorModeSpinner.setSelection(AdapterView.INVALID_POSITION);
                    } else {
                        mColorModeSpinner.setEnabled(true);
                        if (oldColorModeNewIndex != AdapterView.INVALID_POSITION) {
                            // Select the old color mode - nothing really changed.
                            setColorModeSpinnerSelectionNoCallback(oldColorModeNewIndex);
                        } else {
                            final int selectedColorModeIndex = Integer.numberOfTrailingZeros(
                                    (colorModes & defaultAttributes.getColorMode()));
                            someAttributeSelectionChanged = setColorModeSpinnerSelectionNoCallback(
                                    selectedColorModeIndex);
                        }
                    }
                }
                mColorModeSpinner.setEnabled(true);

                // Orientation.
                final int orientations = capabilities.getOrientations();

                // If the orientations changed, we update the adapter and the spinner.
                boolean orientationsChanged = false;
                if (Integer.bitCount(orientations) != mOrientationSpinnerAdapter.getCount()) {
                    orientationsChanged = true;
                } else {
                    int remainingOrientations = orientations;
                    int adapterIndex = 0;
                    while (remainingOrientations != 0) {
                        final int orientationBitOffset = Integer.numberOfTrailingZeros(
                                remainingOrientations);
                        final int orientation = 1 << orientationBitOffset;
                        remainingOrientations &= ~orientation;
                        if (orientation != mOrientationSpinnerAdapter.getItem(
                                adapterIndex).value) {
                            orientationsChanged = true;
                            break;
                        }
                        adapterIndex++;
                    }
                }
                if (orientationsChanged) {
                    // Remember the old orientation to try selecting it again.
                    int oldOrientationNewIndex = AdapterView.INVALID_POSITION;
                    final int oldOrientation = mCurrPrintAttributes.getOrientation();

                    mOrientationSpinnerAdapter.clear();
                    String[] orientationLabels = getResources().getStringArray(
                            R.array.orientation_labels);
                    int remainingOrientations = orientations;
                    while (remainingOrientations != 0) {
                        final int orientationBitOffset = Integer.numberOfTrailingZeros(
                                remainingOrientations);
                        final int orientation = 1 << orientationBitOffset;
                        if (orientation == oldOrientation) {
                            // Update the index of the old selection.
                            oldOrientationNewIndex = orientationBitOffset;
                        }
                        remainingOrientations &= ~orientation;
                        mOrientationSpinnerAdapter.add(new SpinnerItem<Integer>(orientation,
                                orientationLabels[orientationBitOffset]));
                    }
                    final int orientationCount = Integer.bitCount(orientations);
                    if (orientationCount <= 0) {
                        mOrientationSpinner.setEnabled(false);
                        mOrientationSpinner.setSelection(AdapterView.INVALID_POSITION);
                    } else {
                        mOrientationSpinner.setEnabled(true);
                        if (oldOrientationNewIndex != AdapterView.INVALID_POSITION) {
                            // Select the old orientation - nothing really changed.
                            setOrientationSpinnerSelectionNoCallback(oldOrientationNewIndex);
                        } else {
                            final int selectedOrientationIndex = Integer.numberOfTrailingZeros(
                                    (orientations & defaultAttributes.getOrientation()));
                            someAttributeSelectionChanged =
                                    setOrientationSpinnerSelectionNoCallback(
                                            selectedOrientationIndex);
                        }
                    }
                }
                mOrientationSpinner.setEnabled(true);

                // Range options
                PrintDocumentInfo info = mDocument.info;
                if (info != null && (info.getPageCount() > 0
                        || info.getPageCount() == PrintDocumentInfo.PAGE_COUNT_UNKNOWN)) {
                    if (info.getPageCount() == 1) {
                        mRangeOptionsSpinner.setEnabled(false);
                    } else {
                        mRangeOptionsSpinner.setEnabled(true);
                        if (mRangeOptionsSpinner.getSelectedItemPosition() > 0) {
                            if (!mPageRangeEditText.isEnabled()) {
                                mPageRangeEditText.setEnabled(true);
                                mPageRangeEditText.setVisibility(View.VISIBLE);
                                mPageRangeTitle.setVisibility(View.VISIBLE);
                                mPageRangeEditText.requestFocus();
                                InputMethodManager imm = (InputMethodManager)
                                        getSystemService(INPUT_METHOD_SERVICE);
                                imm.showSoftInput(mPageRangeEditText, 0);
                            }
                        } else {
                            mPageRangeEditText.setEnabled(false);
                            mPageRangeEditText.setVisibility(View.INVISIBLE);
                            mPageRangeTitle.setVisibility(View.INVISIBLE);
                        }
                    }
                    final int pageCount = mDocument.info.getPageCount();
                    mRangeOptionsTitle.setText(getString(R.string.label_pages,
                            (pageCount == PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                                    ? getString(R.string.page_count_unknown)
                                    : String.valueOf(pageCount)));
                } else {
                    if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                        mIgnoreNextRangeOptionChange = true;
                        mRangeOptionsSpinner.setSelection(0);
                    }
                    mRangeOptionsSpinner.setEnabled(false);
                    mRangeOptionsTitle.setText(getString(R.string.label_pages,
                            getString(R.string.page_count_unknown)));
                    mPageRangeEditText.setEnabled(false);
                    mPageRangeEditText.setVisibility(View.INVISIBLE);
                    mPageRangeTitle.setVisibility(View.INVISIBLE);
                }
                mRangeOptionsSpinner.setEnabled(true);

                // Print/Print preview
                if (mDestinationSpinner.getSelectedItemId()
                        != DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF) {
                    String newText = getString(R.string.print_button);
                    if (!TextUtils.equals(newText, mPrintButton.getText())) {
                        mPrintButton.setText(R.string.print_button);
                    }
                } else {
                    String newText = getString(R.string.save_button);
                    if (!TextUtils.equals(newText, mPrintButton.getText())) {
                        mPrintButton.setText(R.string.save_button);
                    }
                }
                if ((mRangeOptionsSpinner.getSelectedItemPosition() == 1
                            && (TextUtils.isEmpty(mPageRangeEditText.getText()) || hasErrors()))
                        || (mRangeOptionsSpinner.getSelectedItemPosition() == 0
                            && (!mController.hasPerformedLayout() || hasErrors()))) {
                    mPrintButton.setEnabled(false);
                } else {
                    mPrintButton.setEnabled(true);
                }

                // Copies
                if (mDestinationSpinner.getSelectedItemId()
                        != DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF) {
                    mCopiesEditText.setEnabled(true);
                } else {
                    mCopiesEditText.setEnabled(false);
                }
                if (mCopiesEditText.getError() == null
                        && TextUtils.isEmpty(mCopiesEditText.getText())) {
                    mIgnoreNextCopiesChange = true;
                    mCopiesEditText.setText(String.valueOf(MIN_COPIES));
                    mCopiesEditText.selectAll();
                    mCopiesEditText.requestFocus();
                }

                return someAttributeSelectionChanged;
            }
        }

        private boolean setMediaSizeSpinnerSelectionNoCallback(int position) {
            if (mMediaSizeSpinner.getSelectedItemPosition() != position) {
                mIgnoreNextMediaSizeChange = true;
                mMediaSizeSpinner.setSelection(position);
                return true;
            }
            return false;
        }

        private boolean setColorModeSpinnerSelectionNoCallback(int position) {
            if (mColorModeSpinner.getSelectedItemPosition() != position) {
                mIgnoreNextColorModeChange = true;
                mColorModeSpinner.setSelection(position);
                return true;
            }
            return false;
        }

        private boolean setOrientationSpinnerSelectionNoCallback(int position) {
            if (mOrientationSpinner.getSelectedItemPosition() != position) {
                mIgnoreNextOrientationChange = true;
                mOrientationSpinner.setSelection(position);
                return true;
            }
            return false;
        }

        private void updateUiForNewPrinterCapabilities() {
            // The printer changed so we want to start with a clean slate
            // for the print options and let them be populated from the
            // printer capabilities and use the printer defaults.
            if (!mMediaSizeSpinnerAdapter.isEmpty()) {
                mIgnoreNextMediaSizeChange = true;
                mMediaSizeSpinnerAdapter.clear();
            }
            if (!mColorModeSpinnerAdapter.isEmpty()) {
                mIgnoreNextColorModeChange = true;
                mColorModeSpinnerAdapter.clear();
            }
            if (!mOrientationSpinnerAdapter.isEmpty()) {
                mIgnoreNextOrientationChange = true;
                mOrientationSpinnerAdapter.clear();
            }
            if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                mIgnoreNextRangeOptionChange = true;
                mRangeOptionsSpinner.setSelection(0);
            }
            if (!TextUtils.isEmpty(mCopiesEditText.getText())) {
                mIgnoreNextCopiesChange = true;
                mCopiesEditText.setText(MIN_COPIES_STRING);
            }

            updateUi();
        }

        private void startSelectPrinterActivity() {
            mIgnoreNextDestinationChange = true;
            mDestinationSpinner.setSelection(0);
            Intent intent = new Intent(PrintJobConfigActivity.this,
                    SelectPrinterActivity.class);
            startActivityForResult(intent, ACTIVITY_REQUEST_SELECT_PRINTER);
        }

        private boolean hasErrors() {
            if (mCopiesEditText.getError() != null) {
                return true;
            }
            return mPageRangeEditText.getVisibility() == View.VISIBLE
                    && mPageRangeEditText.getError() != null;
        }

        private final class SpinnerItem<T> {
            final T value;
            CharSequence label;

            public SpinnerItem(T value, CharSequence label) {
                this.value = value;
                this.label = label;
            }

            public String toString() {
                return label.toString();
            }
        }

        private final class DestinationAdapter extends BaseAdapter
                implements LoaderManager.LoaderCallbacks<List<PrinterInfo>>{
            private final List<PrinterInfo> mPrinters = new ArrayList<PrinterInfo>();

            private final PrinterInfo mFakePdfPrinter;

            private PrinterId mLastShownPrinterId;

            public DestinationAdapter() {
                getLoaderManager().initLoader(LOADER_ID_PRINTERS_LOADER, null, this);
                mFakePdfPrinter = createFakePdfPrinter();
            }

            public int getPrinterIndex(PrinterId printerId) {
                for (int i = 0; i < getCount(); i++) {
                    PrinterInfo printer = (PrinterInfo) getItem(i);
                    if (printer != null && printer.getId().equals(printerId)) {
                        return i;
                    }
                }
                return AdapterView.INVALID_POSITION;
            }

            public void ensurePrinterShownPrinterShown(PrinterId printerId) {
                mLastShownPrinterId = printerId;
                ensureLastShownPrinterInPosition();
            }

            @Override
            public int getCount() {
                return Math.min(mPrinters.size() + 2, DEST_ADAPTER_MAX_ITEM_COUNT);
            }

            @Override
            public Object getItem(int position) {
                if (mPrinters.isEmpty()) {
                    if (position == 0) {
                        return mFakePdfPrinter;
                    }
                } else {
                    if (position < 1) {
                        return mPrinters.get(position);
                    }
                    if (position == 1) {
                        return mFakePdfPrinter;
                    }
                    if (position < getCount() - 1) {
                        return mPrinters.get(position - 1);
                    }
                }
                return null;
            }

            @Override
            public long getItemId(int position) {
                if (mPrinters.isEmpty()) {
                    if (position == 0) {
                        return DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF;
                    }
                } else {
                    if (position == 1) {
                        return DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF;
                    }
                    if (position == getCount() - 1) {
                        return DEST_ADAPTER_ITEM_ID_ALL_PRINTERS;
                    }
                }
                return position;
            }

            @Override
            public View getDropDownView(int position, View convertView,
                    ViewGroup parent) {
                return getView(position, convertView, parent);
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(
                            R.layout.spinner_dropdown_item, parent, false);
                }

                CharSequence title = null;
                CharSequence subtitle = null;

                if (mPrinters.isEmpty()) {
                    if (position == 0) {
                        PrinterInfo printer = (PrinterInfo) getItem(position);
                        title = printer.getName();
                    } else if (position == 1) {
                        title = getString(R.string.all_printers);
                    }
                } else {
                    if (position == 1) {
                        PrinterInfo printer = (PrinterInfo) getItem(position);
                        title = printer.getName();
                    } else if (position == getCount() - 1) {
                        title = getString(R.string.all_printers);
                    } else {
                        PrinterInfo printer = (PrinterInfo) getItem(position);
                        title = printer.getName();
                        try {
                            PackageInfo packageInfo = getPackageManager().getPackageInfo(
                                    printer.getId().getServiceName().getPackageName(), 0);
                            subtitle = packageInfo.applicationInfo.loadLabel(getPackageManager());
                        } catch (NameNotFoundException nnfe) {
                            /* ignore */
                        }
                    }
                }

                TextView titleView = (TextView) convertView.findViewById(R.id.title);
                titleView.setText(title);

                TextView subtitleView = (TextView) convertView.findViewById(R.id.subtitle);
                if (!TextUtils.isEmpty(subtitle)) {
                    subtitleView.setText(subtitle);
                    subtitleView.setVisibility(View.VISIBLE);
                } else {
                    subtitleView.setText(null);
                    subtitleView.setVisibility(View.GONE);
                }

                return convertView;
            }

            @Override
            public Loader<List<PrinterInfo>> onCreateLoader(int id, Bundle args) {
                if (id == LOADER_ID_PRINTERS_LOADER) {
                    return new FusedPrintersProvider(PrintJobConfigActivity.this);
                }
                return null;
            }

            @Override
            public void onLoadFinished(Loader<List<PrinterInfo>> loader,
                    List<PrinterInfo> printers) {
                mPrinters.clear();
                mPrinters.addAll(printers);
                ensureLastShownPrinterInPosition();
                notifyDataSetChanged();
            }

            @Override
            public void onLoaderReset(Loader<List<PrinterInfo>> loader) {
                mPrinters.clear();
                notifyDataSetInvalidated();
            }

            private void ensureLastShownPrinterInPosition() {
                if (mLastShownPrinterId == null) {
                    return;
                }
                final int printerCount = mPrinters.size();
                for (int i = 0; i < printerCount; i++) {
                    PrinterInfo printer = (PrinterInfo) mPrinters.get(i);
                    if (printer.getId().equals(mLastShownPrinterId)) {
                        // If already in the list - do nothing.
                        if (i < getCount() - 1) {
                            return;
                        }
                        // Else replace the last one.
                        final int lastPrinter = getCount() - 2;
                        mPrinters.set(i, mPrinters.get(lastPrinter - 1));
                        mPrinters.set(lastPrinter - 1, printer);
                        return;
                    }
                }
            }

            private PrinterInfo createFakePdfPrinter() {
                PrinterId printerId = new PrinterId(getComponentName(), "PDF printer");

                PrinterCapabilitiesInfo capabilities =
                        new PrinterCapabilitiesInfo.Builder(printerId)
                    .addMediaSize(MediaSize.createMediaSize(getPackageManager(),
                            MediaSize.ISO_A4), true)
                    .addMediaSize(MediaSize.createMediaSize(getPackageManager(),
                            MediaSize.NA_LETTER), false)
                    .addResolution(new Resolution("PDF resolution", "PDF resolution",
                            300, 300), true)
                    .setColorModes(PrintAttributes.COLOR_MODE_COLOR
                            | PrintAttributes.COLOR_MODE_MONOCHROME,
                            PrintAttributes.COLOR_MODE_COLOR)
                    .setOrientations(PrintAttributes.ORIENTATION_PORTRAIT
                            | PrintAttributes.ORIENTATION_LANDSCAPE,
                            PrintAttributes.ORIENTATION_PORTRAIT)
                    .create();

                return new PrinterInfo.Builder(printerId, getString(R.string.save_as_pdf),
                        PrinterInfo.STATUS_IDLE)
                    .setCapabilities(capabilities)
                    .create();
            }
        }
    }

    /**
     * An instance of this class class is intended to be the first focusable
     * in a layout to which the system automatically gives focus. It performs
     * some voodoo to avoid the first tap on it to start an edit mode, rather
     * to bring up the IME, i.e. to get the behavior as if the view was not
     * focused.
     */
    public static final class CustomEditText extends EditText {
        private boolean mClickedBeforeFocus;
        private CharSequence mError;

        public CustomEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (isFocused() && !mClickedBeforeFocus) {
                clearFocus();
                requestFocus();
            }
            mClickedBeforeFocus = true;
            return true;
        }

        @Override
        public CharSequence getError() {
            return mError;
        }

        @Override
        public void setError(CharSequence error, Drawable icon) {
            setCompoundDrawables(null, null, icon, null);
            mError = error;
        }

        protected void onFocusChanged(boolean gainFocus, int direction,
                Rect previouslyFocusedRect) {
            if (!gainFocus) {
                mClickedBeforeFocus = false;
            }
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        }
    }

    private static final class Document {
        public PrintDocumentInfo info;
        public PageRange[] pages;
    }

    private static final class PageRangeUtils {

        private static final Comparator<PageRange> sComparator = new Comparator<PageRange>() {
            @Override
            public int compare(PageRange lhs, PageRange rhs) {
                return lhs.getStart() - rhs.getStart();
            }
        };

        private PageRangeUtils() {
            throw new UnsupportedOperationException();
        }

        public static boolean contains(PageRange[] ourPageRanges, PageRange[] otherPageRanges) {
            if (ourPageRanges == null || otherPageRanges == null) {
                return false;
            }

            otherPageRanges = normalize(otherPageRanges);

            int otherPageIdx = 0;
            final int myPageCount = ourPageRanges.length;
            final int otherPageCount = otherPageRanges.length;
            for (int i= 0; i < myPageCount; i++) {
                PageRange myPage = ourPageRanges[i];
                for (; otherPageIdx < otherPageCount; otherPageIdx++) {
                    PageRange otherPage = otherPageRanges[otherPageIdx];
                    if (otherPage.getStart() > myPage.getStart()) {
                        break;
                    }
                    if ((otherPage.getStart() < myPage.getStart()
                                    && otherPage.getEnd() > myPage.getStart())
                            || (otherPage.getEnd() > myPage.getEnd()
                                    && otherPage.getStart() < myPage.getEnd())
                            || (otherPage.getEnd() < myPage.getStart())) {
                        return false;
                    }
                }
            }
            if (otherPageIdx < otherPageCount) {
                return false;
            }
            return true;
        }

        public static PageRange[] normalize(PageRange[] pageRanges) {
            if (pageRanges == null) {
                return null;
            }
            final int oldPageCount = pageRanges.length;
            if (oldPageCount <= 1) {
                return pageRanges;
            }
            Arrays.sort(pageRanges, sComparator);
            int newRangeCount = 0;
            for (int i = 0; i < oldPageCount - 1; i++) {
                newRangeCount++;
                PageRange currentRange = pageRanges[i];
                PageRange nextRange = pageRanges[i + 1];
                if (currentRange.getEnd() >= nextRange.getStart()) {
                    newRangeCount--;
                    pageRanges[i] = null;
                    pageRanges[i + 1] = new PageRange(currentRange.getStart(),
                            nextRange.getEnd());
                }
            }
            if (newRangeCount == oldPageCount) {
                return pageRanges;
            }
            return Arrays.copyOfRange(pageRanges, oldPageCount - newRangeCount,
                    oldPageCount - 1);
        }

        public static void offsetStart(PageRange[] pageRanges, int offset) {
            if (offset == 0) {
                return;
            }
            final int pageRangeCount = pageRanges.length;
            for (int i = 0; i < pageRangeCount; i++) {
                final int start = pageRanges[i].getStart() + offset;
                final int end = pageRanges[i].getEnd() + offset;
                pageRanges[i] = new PageRange(start, end);
            }
        }
    }
}
