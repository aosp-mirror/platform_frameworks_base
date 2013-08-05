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
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
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
import android.print.IPrinterDiscoveryObserver;
import android.print.IWriteResultCallback;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintJobInfo;
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
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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

    private static final int CONTROLLER_STATE_INITIALIZED = 1;
    private static final int CONTROLLER_STATE_STARTED = 2;
    private static final int CONTROLLER_STATE_LAYOUT_STARTED = 3;
    private static final int CONTROLLER_STATE_LAYOUT_COMPLETED = 4;
    private static final int CONTROLLER_STATE_WRITE_STARTED = 5;
    private static final int CONTROLLER_STATE_WRITE_COMPLETED = 6;
    private static final int CONTROLLER_STATE_FINISHED = 7;
    private static final int CONTROLLER_STATE_FAILED = 8;
    private static final int CONTROLLER_STATE_CANCELLED = 9;

    private static final int EDITOR_STATE_INITIALIZED = 1;
    private static final int EDITOR_STATE_CONFIRMED_PRINT = 2;
    private static final int EDITOR_STATE_CONFIRMED_PREVIEW = 3;
    private static final int EDITOR_STATE_CANCELLED = 4;

    private static final int MIN_COPIES = 1;

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

    private PrintSpooler mSpooler;
    private Editor mEditor;
    private Document mDocument;
    private PrintController mController;
    private PrinterDiscoveryObserver mPrinterDiscoveryObserver;

    private int mPrintJobId;

    private IBinder mIPrintDocumentAdapter;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.print_job_config_activity);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

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

        mSpooler = PrintSpooler.peekInstance();
        mEditor = new Editor();
        mDocument = new Document();
        mController = new PrintController(new RemotePrintDocumentAdapter(
                IPrintDocumentAdapter.Stub.asInterface(mIPrintDocumentAdapter),
                mSpooler.generateFileForPrintJob(mPrintJobId)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mIPrintDocumentAdapter.linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException re) {
            finish();
            return;
        }
        mController.initialize();
        mEditor.initialize();
        mPrinterDiscoveryObserver = new PrinterDiscoveryObserver(mEditor, getMainLooper());
        mSpooler.startPrinterDiscovery(mPrinterDiscoveryObserver);
    }

    @Override
    protected void onPause() {
        mSpooler.stopPrinterDiscovery();
        mPrinterDiscoveryObserver.destroy();
        mPrinterDiscoveryObserver = null;
        if (mController.isCancelled() || mController.isFailed()) {
            mSpooler.setPrintJobState(mPrintJobId,
                    PrintJobInfo.STATE_CANCELED, null);
        } else if (mController.hasStarted()) {
            mController.finish();
            if (mEditor.isPrintConfirmed()) {
                if (mController.isFinished()) {
                    mSpooler.setPrintJobState(mPrintJobId,
                            PrintJobInfo.STATE_QUEUED, null);
                } else {
                    mSpooler.setPrintJobState(mPrintJobId,
                            PrintJobInfo.STATE_CANCELED, null);
                }
            }
        }
        mIPrintDocumentAdapter.unlinkToDeath(mDeathRecipient, 0);
        super.onPause();
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!mEditor.isPrintConfirmed() && !mEditor.isPreviewConfirmed()
                && getWindow().shouldCloseOnTouch(this, event)) {
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
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking()
                && !event.isCanceled()) {
            if (!mController.isWorking()) {
                PrintJobConfigActivity.this.finish();
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

        private final Handler mHandler;

        private int mControllerState = CONTROLLER_STATE_INITIALIZED;

        private PageRange[] mRequestedPages;

        private Bundle mMetadata = new Bundle();

        private final ILayoutResultCallback mILayoutResultCallback =
                new ILayoutResultCallback.Stub() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed, int sequence) {
                if (mRequestCounter.get() == sequence) {
                    mHandler.obtainMessage(MyHandler.MSG_ON_LAYOUT_FINISHED, changed ? 1 : 0,
                            0, info).sendToTarget();
                }
            }

            @Override
            public void onLayoutFailed(CharSequence error, int sequence) {
                if (mRequestCounter.get() == sequence) {
                    mHandler.obtainMessage(MyHandler.MSG_ON_LAYOUT_FAILED, error).sendToTarget();
                }
            }
        };

        private IWriteResultCallback mIWriteResultCallback = new IWriteResultCallback.Stub() {
            @Override
            public void onWriteFinished(PageRange[] pages, int sequence) {
                if (mRequestCounter.get() == sequence) {
                    mHandler.obtainMessage(MyHandler.MSG_ON_WRITE_FINISHED, pages).sendToTarget();
                }
            }

            @Override
            public void onWriteFailed(CharSequence error, int sequence) {
                if (mRequestCounter.get() == sequence) {
                    mHandler.obtainMessage(MyHandler.MSG_ON_WRITE_FAILED, error).sendToTarget();
                }
            }
        };

        public PrintController(RemotePrintDocumentAdapter adapter) {
            mRemotePrintAdapter = adapter;
            mHandler = new MyHandler(Looper.getMainLooper());
        }

        public void initialize() {
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

        public boolean isFailed() {
            return (mControllerState == CONTROLLER_STATE_FAILED);
        }

        public boolean hasStarted() {
            return mControllerState >= CONTROLLER_STATE_STARTED;
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
            mRemotePrintAdapter.start();
        }

        public void update() {
            if (!printAttributesChanged()) {
                // If the attributes changes, then we do not do a layout but may
                // have to ask the app to write some pages. Hence, pretend layout
                // completed and nothing changed, so we handle writing as usual.
                handleOnLayoutFinished(mDocument.info, false);
            } else {
                mSpooler.setPrintJobAttributesNoPersistence(mPrintJobId, mCurrPrintAttributes);

                mMetadata.putBoolean(PrintDocumentAdapter.METADATA_KEY_PRINT_PREVIEW,
                        !mEditor.isPrintConfirmed());

                mControllerState = CONTROLLER_STATE_LAYOUT_STARTED;

                mRemotePrintAdapter.layout(mOldPrintAttributes, mCurrPrintAttributes,
                        mILayoutResultCallback, mMetadata, mRequestCounter.incrementAndGet());

                mOldPrintAttributes.copyFrom(mCurrPrintAttributes);
            }
        }

        public void finish() {
            mControllerState = CONTROLLER_STATE_FINISHED;
            mRemotePrintAdapter.finish();
        }

        private void handleOnLayoutFinished(PrintDocumentInfo info, boolean layoutChanged) {
            if (isCancelled()) {
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            mControllerState = CONTROLLER_STATE_LAYOUT_COMPLETED;

            // If the info changed, we update the document and the print job,
            // and update the UI since the the page range selection may have
            // become invalid.
            final boolean infoChanged = !info.equals(mDocument.info);
            if (infoChanged) {
                mDocument.info = info;
                mSpooler.setPrintJobPrintDocumentInfoNoPersistence(mPrintJobId, info);
                mEditor.updateUi();
            }

            // If the document info or the layout changed, then
            // drop the pages since we have to fetch them again.
            if (infoChanged || layoutChanged) {
                mDocument.pages = null;
            }

            // No pages means that the user selected an invalid range while we
            // were doing a layout or the layout returned a document info for
            // which the selected range is invalid. In such a case we do not
            // write anything and wait for the user to fix the range which will
            // trigger an update.
            mRequestedPages = mEditor.getRequestedPages();
            if (mRequestedPages == null) {
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
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            // Request a write of the pages of interest.
            mControllerState = CONTROLLER_STATE_WRITE_STARTED;
            mRemotePrintAdapter.write(mRequestedPages, mIWriteResultCallback,
                    mRequestCounter.incrementAndGet());
        }

        private void handleOnLayoutFailed(CharSequence error) {
            mControllerState = CONTROLLER_STATE_FAILED;
            // TODO: We need some UI for announcing an error.
            Log.e(LOG_TAG, "Error during layout: " + error);
            PrintJobConfigActivity.this.finish();
        }

        private void handleOnWriteFinished(PageRange[] pages) {
            if (isCancelled()) {
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            mControllerState = CONTROLLER_STATE_WRITE_COMPLETED;

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
                mSpooler.setPrintJobPagesNoPersistence(mPrintJobId, ALL_PAGES_ARRAY);
            } else if (Arrays.equals(mDocument.pages, ALL_PAGES_ARRAY)) {
                // We requested specific pages but got all of them. Hence,
                // the printer has to print only the requested pages.
                mSpooler.setPrintJobPagesNoPersistence(mPrintJobId, mRequestedPages);
            } else if (PageRangeUtils.contains(mDocument.pages, mRequestedPages)) {
                // We requested specific pages and got more but not all pages.
                // Hence, we have to offset appropriately the printed pages to
                // excle the pages we did not request. Note that pages is
                // guaranteed to be not null and not empty.
                final int offset = mDocument.pages[0].getStart() - pages[0].getStart();
                PageRange[] offsetPages = Arrays.copyOf(mDocument.pages, mDocument.pages.length);
                PageRangeUtils.offsetStart(offsetPages, offset);
                mSpooler.setPrintJobPagesNoPersistence(mPrintJobId, offsetPages);
            } else if (Arrays.equals(mRequestedPages, ALL_PAGES_ARRAY)
                    && mDocument.pages.length == 1 && mDocument.pages[0].getStart() == 0
                    && mDocument.pages[0].getEnd() == mDocument.info.getPageCount() - 1) {
                // We requested all pages via the special constant and got all
                // of them as an explicit enumeration. Hence, the printer has
                // to print only the requested pages.
                mSpooler.setPrintJobPagesNoPersistence(mPrintJobId, mDocument.pages);
            } else {
                // We did not get the pages we requested, then the application
                // misbehaves, so we fail quickly.
                // TODO: We need some UI for announcing an error.
                mControllerState = CONTROLLER_STATE_FAILED;
                Log.e(LOG_TAG, "Received invalid pages from the app");
                PrintJobConfigActivity.this.finish();
            }

            if (mEditor.isDone()) {
                PrintJobConfigActivity.this.finish();
            }
        }

        private void handleOnWriteFailed(CharSequence error) {
            mControllerState = CONTROLLER_STATE_FAILED;
            Log.e(LOG_TAG, "Error during write: " + error);
            PrintJobConfigActivity.this.finish();
        }

        private final class MyHandler extends Handler {
            public static final int MSG_ON_LAYOUT_FINISHED = 1;
            public static final int MSG_ON_LAYOUT_FAILED = 2;
            public static final int MSG_ON_WRITE_FINISHED = 3;
            public static final int MSG_ON_WRITE_FAILED = 4;

            public MyHandler(Looper looper) {
                super(looper, null, false);
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_ON_LAYOUT_FINISHED: {
                        PrintDocumentInfo info = (PrintDocumentInfo) message.obj;
                        final boolean changed = (message.arg1 == 1);
                        mController.handleOnLayoutFinished(info, changed);
                    } break;

                    case MSG_ON_LAYOUT_FAILED: {
                        CharSequence error = (CharSequence) message.obj;
                        mController.handleOnLayoutFailed(error);
                    } break;

                    case MSG_ON_WRITE_FINISHED: {
                        PageRange[] pages = (PageRange[]) message.obj;
                        mController.handleOnWriteFinished(pages);
                    } break;

                    case MSG_ON_WRITE_FAILED: {
                        CharSequence error = (CharSequence) message.obj;
                        mController.handleOnWriteFailed(error);
                    } break;
                }
            }
        }
    }

    private final class Editor {
        private final EditText mCopiesEditText;

        private final TextView mRangeTitle;
        private final EditText mRangeEditText;

        private final Spinner mDestinationSpinner;
        private final ArrayAdapter<SpinnerItem<PrinterInfo>> mDestinationSpinnerAdapter;

        private final Spinner mMediaSizeSpinner;
        private final ArrayAdapter<SpinnerItem<MediaSize>> mMediaSizeSpinnerAdapter;

        private final Spinner mColorModeSpinner;
        private final ArrayAdapter<SpinnerItem<Integer>> mColorModeSpinnerAdapter;

        private final Spinner mOrientationSpinner;
        private final  ArrayAdapter<SpinnerItem<Integer>> mOrientationSpinnerAdapter;

        private final Spinner mRangeOptionsSpinner;
        private final ArrayAdapter<SpinnerItem<Integer>> mRangeOptionsSpinnerAdapter;

        private final SimpleStringSplitter mStringCommaSplitter =
                new SimpleStringSplitter(',');

        private final Button mPrintPreviewButton;

        private final Button mPrintButton;

        private final OnItemSelectedListener mOnItemSelectedListener =
                new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> spinner, View view, int position, long id) {
                if (spinner == mDestinationSpinner) {
                    if (mIgnoreNextDestinationChange) {
                        mIgnoreNextDestinationChange = false;
                        return;
                    }
                    mCurrPrintAttributes.clear();
                    SpinnerItem<PrinterInfo> dstItem = mDestinationSpinnerAdapter.getItem(position);
                    if (dstItem != null) {
                        PrinterInfo printer = dstItem.value;
                        mSpooler.setPrintJobPrinterIdNoPersistence(mPrintJobId, printer.getId());
                        printer.getDefaults(mCurrPrintAttributes);
                        if (!printer.hasAllRequiredAttributes()) {
                            List<PrinterId> printerIds = new ArrayList<PrinterId>();
                            printerIds.add(printer.getId());
                            mSpooler.onReqeustUpdatePrinters(printerIds);
                            //TODO: We need a timeout for the update.
                        } else {
                            if (!mController.hasStarted()) {
                                mController.start();
                            }
                            if (!hasErrors()) {
                                mController.update();
                            }
                        }
                    }
                    updateUi();
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

                final int copies = Integer.parseInt(editable.toString());
                if (copies < MIN_COPIES) {
                    mCopiesEditText.setError("");
                    updateUi();
                    return;
                }

                mCopiesEditText.setError(null);
                mSpooler.setPrintJobCopiesNoPersistence(mPrintJobId, copies);
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
                    mRangeEditText.setError("");
                    updateUi();
                    return;
                }

                String escapedText = PATTERN_ESCAPE_SPECIAL_CHARS.matcher(text).replaceAll("////");
                if (!PATTERN_PAGE_RANGE.matcher(escapedText).matches()) {
                    mRangeEditText.setError("");
                    updateUi();
                    return;
                }

                Matcher matcher = PATTERN_DIGITS.matcher(text);
                while (matcher.find()) {
                    String numericString = text.substring(matcher.start(), matcher.end());
                    final int pageIndex = Integer.parseInt(numericString);
                    if (pageIndex < 1 || pageIndex > mDocument.info.getPageCount()) {
                        mRangeEditText.setError("");
                        updateUi();
                        return;
                    }
                }

                //TODO: Catch the error if start is less grater than the end.

                mRangeEditText.setError(null);
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

        public Editor() {
            // Copies
            mCopiesEditText = (EditText) findViewById(R.id.copies_edittext);
            mCopiesEditText.setText(String.valueOf(MIN_COPIES));
            mSpooler.setPrintJobCopiesNoPersistence(mPrintJobId, MIN_COPIES);
            mCopiesEditText.addTextChangedListener(mCopiesTextWatcher);
            mCopiesEditText.selectAll();

            // Destination.
            mDestinationSpinner = (Spinner) findViewById(R.id.destination_spinner);
            mDestinationSpinnerAdapter = new DestinationAdapter();
            mDestinationSpinner.setAdapter(mDestinationSpinnerAdapter);
            mDestinationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

            // Media size.
            mMediaSizeSpinner = (Spinner) findViewById(R.id.paper_size_spinner);
            mMediaSizeSpinnerAdapter = new ArrayAdapter<SpinnerItem<MediaSize>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);
            mMediaSizeSpinner.setAdapter(mMediaSizeSpinnerAdapter);
            mMediaSizeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

            // Color mode.
            mColorModeSpinner = (Spinner) findViewById(R.id.color_spinner);
            mColorModeSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);
            mColorModeSpinner.setAdapter(mColorModeSpinnerAdapter);
            mColorModeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

            // Orientation
            mOrientationSpinner = (Spinner) findViewById(R.id.orientation_spinner);
            mOrientationSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);
            mOrientationSpinner.setAdapter(mOrientationSpinnerAdapter);
            mOrientationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

            // Range
            mRangeTitle = (TextView) findViewById(R.id.page_range_title);
            mRangeEditText = (EditText) findViewById(R.id.page_range_edittext);
            mRangeEditText.addTextChangedListener(mRangeTextWatcher);

            // Range options
            mRangeOptionsSpinner = (Spinner) findViewById(R.id.range_options_spinner);
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
            mRangeOptionsSpinner.setAdapter(mRangeOptionsSpinnerAdapter);
            if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                mIgnoreNextRangeOptionChange = true;
                mRangeOptionsSpinner.setSelection(0);
            }

            // Preview button
            mPrintPreviewButton = (Button) findViewById(R.id.print_preview_button);
            mPrintPreviewButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mEditor.confirmPreview();
                    // TODO: Implement me
                    Toast.makeText(PrintJobConfigActivity.this,
                            "Stop poking me! Not implemented yet :)",
                            Toast.LENGTH_LONG).show();
                }
            });

            // Print button
            mPrintButton = (Button) findViewById(R.id.print_button);
            mPrintButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mEditor.confirmPrint();
                    updateUi();
                    mController.update();
                }
            });

            updateUi();
        }

        public void initialize() {
            mEditorState = EDITOR_STATE_INITIALIZED;
            if (mDestinationSpinner.getSelectedItemPosition() != AdapterView.INVALID_POSITION) {
                mIgnoreNextDestinationChange = true;
                mDestinationSpinner.setSelection(AdapterView.INVALID_POSITION);
            }
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
        }

        public boolean isPreviewConfirmed() {
            return mEditorState == EDITOR_STATE_CONFIRMED_PRINT;
        }

        public void confirmPreview() {
            mEditorState = EDITOR_STATE_CONFIRMED_PREVIEW;
        }

        public PageRange[] getRequestedPages() {
            if (hasErrors()) {
                return null;
            }
            if (mRangeOptionsSpinner.getSelectedItemPosition() > 0) {
                List<PageRange> pageRanges = new ArrayList<PageRange>();
                mStringCommaSplitter.setString(mRangeEditText.getText().toString());

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
                        fromIndex = toIndex = Integer.parseInt(range);
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

        public void updateUi() {
            if (isPrintConfirmed() || isPreviewConfirmed() || isCancelled()) {
                mDestinationSpinner.setEnabled(false);
                mCopiesEditText.setEnabled(false);
                mMediaSizeSpinner.setEnabled(false);
                mColorModeSpinner.setEnabled(false);
                mOrientationSpinner.setEnabled(false);
                mRangeOptionsSpinner.setEnabled(false);
                mRangeEditText.setEnabled(false);
                mPrintPreviewButton.setEnabled(false);
                mPrintButton.setEnabled(false);
                return;
            }

            final int selectedIndex = mDestinationSpinner.getSelectedItemPosition();

            if (selectedIndex < 0 || !mDestinationSpinnerAdapter.getItem(
                    selectedIndex).value.hasAllRequiredAttributes()) {

                // Destination
                mDestinationSpinner.setEnabled(false);

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
                mRangeTitle.setText(getString(R.string.label_pages,
                        getString(R.string.page_count_unknown)));
                if (!TextUtils.equals(mRangeEditText.getText(), "")) {
                    mIgnoreNextRangeChange = true;
                    mRangeEditText.setText("");
                }

                mRangeEditText.setEnabled(false);
                mRangeEditText.setVisibility(View.INVISIBLE);

                // Print preview
                mPrintPreviewButton.setEnabled(false);
                mPrintPreviewButton.setText(getString(R.string.print_preview));

                // Print
                mPrintButton.setEnabled(false);
            } else {
                PrintAttributes defaultAttributes = mTempPrintAttributes;
                PrinterInfo printer = mDestinationSpinnerAdapter.getItem(selectedIndex).value;
                printer.getDefaults(defaultAttributes);

                // Destination
                if (mDestinationSpinnerAdapter.getCount() > 1) {
                    mDestinationSpinner.setEnabled(true);
                } else {
                    mDestinationSpinner.setEnabled(false);
                }

                // Copies
                mCopiesEditText.setEnabled(true);

                // Media size.
                List<MediaSize> mediaSizes = printer.getMediaSizes();
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
                    mMediaSizeSpinnerAdapter.clear();
                    for (int i = 0; i < mediaSizeCount; i++) {
                        MediaSize mediaSize = mediaSizes.get(i);
                        mMediaSizeSpinnerAdapter.add(new SpinnerItem<MediaSize>(
                                mediaSize, mediaSize.getLabel()));
                    }
                    if (mediaSizeCount <= 0) {
                        mMediaSizeSpinner.setEnabled(false);
                        mMediaSizeSpinner.setSelection(AdapterView.INVALID_POSITION);
                    } else if (mediaSizeCount == 1) {
                        mMediaSizeSpinner.setEnabled(false);
                        mMediaSizeSpinner.setSelection(0);
                    } else {
                        mMediaSizeSpinner.setEnabled(true);
                        final int selectedMediaSizeIndex = Math.max(mediaSizes.indexOf(
                                defaultAttributes.getMediaSize()), 0);
                        if (mMediaSizeSpinner.getSelectedItemPosition() != selectedMediaSizeIndex) {
                            mIgnoreNextMediaSizeChange = true;
                            mMediaSizeSpinner.setSelection(selectedMediaSizeIndex);
                        }
                    }
                }

                // Color mode.
                final int colorModes = printer.getColorModes();
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
                    mColorModeSpinnerAdapter.clear();
                    String[] colorModeLabels = getResources().getStringArray(
                            R.array.color_mode_labels);
                    int remainingColorModes = colorModes;
                    while (remainingColorModes != 0) {
                        final int colorBitOffset = Integer.numberOfTrailingZeros(
                                remainingColorModes);
                        final int colorMode = 1 << colorBitOffset;
                        remainingColorModes &= ~colorMode;
                        mColorModeSpinnerAdapter.add(new SpinnerItem<Integer>(colorMode,
                                colorModeLabels[colorBitOffset]));
                    }
                    final int colorModeCount = Integer.bitCount(colorModes);
                    if (colorModeCount <= 0) {
                        mColorModeSpinner.setEnabled(false);
                        mColorModeSpinner.setSelection(AdapterView.INVALID_POSITION);
                    } else if (colorModeCount == 1) {
                        mColorModeSpinner.setEnabled(false);
                        mColorModeSpinner.setSelection(0);
                    } else {
                        mColorModeSpinner.setEnabled(true);
                        final int selectedColorModeIndex = Integer.numberOfTrailingZeros(
                                (colorModes & defaultAttributes.getColorMode()));
                        if (mColorModeSpinner.getSelectedItemPosition() != selectedColorModeIndex) {
                            mIgnoreNextColorModeChange = true;
                            mColorModeSpinner.setSelection(selectedColorModeIndex);
                        }
                    }
                }

                // Orientation.
                final int orientations = printer.getOrientations();
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
                    mOrientationSpinnerAdapter.clear();
                    String[] orientationLabels = getResources().getStringArray(
                            R.array.orientation_labels);
                    int remainingOrientations = orientations;
                    while (remainingOrientations != 0) {
                        final int orientationBitOffset = Integer.numberOfTrailingZeros(
                                remainingOrientations);
                        final int orientation = 1 << orientationBitOffset;
                        remainingOrientations &= ~orientation;
                        mOrientationSpinnerAdapter.add(new SpinnerItem<Integer>(orientation,
                                orientationLabels[orientationBitOffset]));
                    }
                    final int orientationCount = Integer.bitCount(orientations);
                    if (orientationCount <= 0) {
                        mOrientationSpinner.setEnabled(false);
                        mOrientationSpinner.setSelection(AdapterView.INVALID_POSITION);
                    } else if (orientationCount == 1) {
                        mOrientationSpinner.setEnabled(false);
                        mOrientationSpinner.setSelection(0);
                    } else {
                        mOrientationSpinner.setEnabled(true);
                        final int selectedOrientationIndex = Integer.numberOfTrailingZeros(
                                (orientations & defaultAttributes.getOrientation()));
                        if (mOrientationSpinner.getSelectedItemPosition()
                                != selectedOrientationIndex) {
                            mIgnoreNextOrientationChange = true;
                            mOrientationSpinner.setSelection(selectedOrientationIndex);
                        }
                    }
                }

                // Range options
                PrintDocumentInfo info = mDocument.info;
                if (info != null && (info.getPageCount() > 1
                        || info.getPageCount() == PrintDocumentInfo.PAGE_COUNT_UNKNOWN)) {
                    mRangeOptionsSpinner.setEnabled(true);
                    if (mRangeOptionsSpinner.getSelectedItemPosition() > 0
                            && !mRangeEditText.isEnabled()) {
                        mRangeEditText.setEnabled(true);
                        mRangeEditText.setVisibility(View.VISIBLE);
                        mRangeEditText.requestFocus();
                        InputMethodManager imm = (InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                        imm.showSoftInput(mRangeEditText, 0);
                    }
                    final int pageCount = mDocument.info.getPageCount();
                    mRangeTitle.setText(getString(R.string.label_pages,
                            (pageCount == PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                                    ? getString(R.string.page_count_unknown)
                                    : String.valueOf(pageCount)));
                } else {
                    if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                        mIgnoreNextRangeOptionChange = true;
                        mRangeOptionsSpinner.setSelection(0);
                    }
                    mRangeOptionsSpinner.setEnabled(false);
                    mRangeTitle.setText(getString(R.string.label_pages,
                            getString(R.string.page_count_unknown)));
                    mRangeEditText.setEnabled(false);
                    mRangeEditText.setVisibility(View.INVISIBLE);
                }

                // Print/Print preview
                if ((mRangeOptionsSpinner.getSelectedItemPosition() == 1
                            && (TextUtils.isEmpty(mRangeEditText.getText()) || hasErrors()))
                        || (mRangeOptionsSpinner.getSelectedItemPosition() == 0
                            && (!mController.hasPerformedLayout() || hasErrors()))) {
                    mPrintPreviewButton.setEnabled(false);
                    mPrintButton.setEnabled(false);
                } else {
                    mPrintPreviewButton.setEnabled(true);
                    if (hasPdfViewer()) {
                        mPrintPreviewButton.setText(getString(R.string.print_preview));
                    } else {
                        mPrintPreviewButton.setText(getString(R.string.install_for_print_preview));
                    }
                    mPrintButton.setEnabled(true);
                }

                // Copies
                if (mCopiesEditText.getError() == null
                        && TextUtils.isEmpty(mCopiesEditText.getText())) {
                    mIgnoreNextCopiesChange = true;
                    mCopiesEditText.setText(String.valueOf(MIN_COPIES));
                    mCopiesEditText.selectAll();
                    mCopiesEditText.requestFocus();
                }
            }
        }

        public void addPrinters(List<PrinterInfo> addedPrinters) {
            final int addedPrinterCount = addedPrinters.size();
            for (int i = 0; i < addedPrinterCount; i++) {
                PrinterInfo addedPrinter = addedPrinters.get(i);
                boolean duplicate = false;
                final int existingPrinterCount = mDestinationSpinnerAdapter.getCount();
                for (int j = 0; j < existingPrinterCount; j++) {
                    PrinterInfo existingPrinter = mDestinationSpinnerAdapter.getItem(j).value;
                    if (addedPrinter.getId().equals(existingPrinter.getId())) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    mDestinationSpinnerAdapter.add(new SpinnerItem<PrinterInfo>(
                            addedPrinter, addedPrinter.getId().getPrinterName()));
                } else {
                    Log.w(LOG_TAG, "Skipping a duplicate printer: " + addedPrinter);
                }
            }

            if (mDestinationSpinner.getSelectedItemPosition() == AdapterView.INVALID_POSITION
                    && mDestinationSpinnerAdapter.getCount() > 0) {
                mDestinationSpinner.setSelection(0);
            }

            mEditor.updateUi();
        }

        public void removePrinters(List<PrinterId> pritnerIds) {
            final int printerIdCount = pritnerIds.size();
            for (int i = 0; i < printerIdCount; i++) {
                PrinterId removedPrinterId = pritnerIds.get(i);
                boolean removed = false;
                final int existingPrinterCount = mDestinationSpinnerAdapter.getCount();
                for (int j = 0; j < existingPrinterCount; j++) {
                    PrinterInfo existingPrinter = mDestinationSpinnerAdapter.getItem(j).value;
                    if (removedPrinterId.equals(existingPrinter.getId())) {
                        mDestinationSpinnerAdapter.remove(mDestinationSpinnerAdapter.getItem(j));
                        removed = true;
                        break;
                    }
                }
                if (!removed) {
                    Log.w(LOG_TAG, "Ignoring not added printer with id: " + removedPrinterId);
                }
            }

            if (mDestinationSpinner.getSelectedItemPosition() != AdapterView.INVALID_POSITION
                    && mDestinationSpinnerAdapter.getCount() == 0) {
                mDestinationSpinner.setSelection(AdapterView.INVALID_POSITION);
            }
        }

        @SuppressWarnings("unchecked")
        public void updatePrinters(List<PrinterInfo> pritners) {
            SpinnerItem<PrinterInfo> selectedItem =
                    (SpinnerItem<PrinterInfo>) mDestinationSpinner.getSelectedItem();
            PrinterId selectedPrinterId = (selectedItem != null)
                    ? selectedItem.value.getId() : null;

            boolean updated = false;

            final int printerCount = pritners.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterInfo updatedPrinter = pritners.get(i);
                final int existingPrinterCount = mDestinationSpinnerAdapter.getCount();
                for (int j = 0; j < existingPrinterCount; j++) {
                    PrinterInfo existingPrinter = mDestinationSpinnerAdapter.getItem(j).value;
                    if (updatedPrinter.getId().equals(existingPrinter.getId())) {
                        existingPrinter.copyFrom(updatedPrinter);
                        updated = true;
                        if (selectedPrinterId != null
                                && selectedPrinterId.equals(updatedPrinter.getId())) {
                            // The selected printer was updated. We simulate a fake
                            // selection to reuse the normal printer change handling.
                            mOnItemSelectedListener.onItemSelected(mDestinationSpinner,
                                    mDestinationSpinner.getSelectedView(),
                                    mDestinationSpinner.getSelectedItemPosition(),
                                    mDestinationSpinner.getSelectedItemId());
                            // TODO: This will reset the UI to the defaults for the
                            // printer. We may need to revisit this.
                            
                        }
                        break;
                    }
                }
            }
            if (updated) {
                mDestinationSpinnerAdapter.notifyDataSetChanged();
            }
        }

        private boolean hasErrors() {
            return mRangeEditText.getError() != null
                    || mCopiesEditText.getError() != null;
        }

        private boolean hasPdfViewer() {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setType("application/pdf");
            return !getPackageManager().queryIntentActivities(intent,
                    PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
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

        private final class DestinationAdapter extends ArrayAdapter<SpinnerItem<PrinterInfo>> {

            public DestinationAdapter() {
                super( PrintJobConfigActivity.this, R.layout.spinner_dropdown_item);
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

                PrinterInfo printerInfo = getItem(position).value;
                TextView title = (TextView) convertView.findViewById(R.id.title);
                title.setText(printerInfo.getId().getPrinterName());

                try {
                    TextView subtitle = (TextView)
                            convertView.findViewById(R.id.subtitle);
                    PackageManager pm = getPackageManager();
                    PackageInfo packageInfo = pm.getPackageInfo(
                            printerInfo.getId().getServiceName().getPackageName(), 0);
                    subtitle.setText(packageInfo.applicationInfo.loadLabel(pm));
                    subtitle.setVisibility(View.VISIBLE);
                } catch (NameNotFoundException nnfe) {
                    /* ignore */
                }

                return convertView;
            }
        }
    }

    private static final class PrinterDiscoveryObserver extends IPrinterDiscoveryObserver.Stub {
        private static final int MSG_ON_PRINTERS_ADDED = 1;
        private static final int MSG_ON_PRINTERS_REMOVED = 2;
        private static final int MSG_ON_PRINTERS_UPDATED = 3;

        private Handler mHandler;
        private Editor mEditor;

        @SuppressWarnings("unchecked")
        public PrinterDiscoveryObserver(Editor editor, Looper looper) {
            mEditor = editor;
            mHandler = new Handler(looper, null, true) {
                @Override
                public void handleMessage(Message message) {
                    switch (message.what) {
                        case MSG_ON_PRINTERS_ADDED: {
                            List<PrinterInfo> printers = (List<PrinterInfo>) message.obj;
                            mEditor.addPrinters(printers);
                        } break;

                        case MSG_ON_PRINTERS_REMOVED: {
                            List<PrinterId> printerIds = (List<PrinterId>) message.obj;
                            mEditor.removePrinters(printerIds);
                        } break;

                        case MSG_ON_PRINTERS_UPDATED: {
                            List<PrinterInfo> printers = (List<PrinterInfo>) message.obj;
                            mEditor.updatePrinters(printers);
                        } break;
                    }
                }
            };
        }

        @Override
        public void onPrintersAdded(List<PrinterInfo> printers) {
            synchronized (this) {
                if (mHandler != null) {
                    mHandler.obtainMessage(MSG_ON_PRINTERS_ADDED, printers)
                        .sendToTarget();
                }
            }
        }

        @Override
        public void onPrintersRemoved(List<PrinterId> printers) {
            synchronized (this) {
                if (mHandler != null) {
                    mHandler.obtainMessage(MSG_ON_PRINTERS_REMOVED, printers)
                        .sendToTarget();
                }
            }
        }

        @Override
        public void onPrintersUpdated(List<PrinterInfo> printers) {
            synchronized (this) {
                if (mHandler != null) {
                    mHandler.obtainMessage(MSG_ON_PRINTERS_UPDATED, printers)
                        .sendToTarget();
                }
            }
        }

        public void destroy() {
            synchronized (this) {
                mHandler = null;
                mEditor = null;
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
