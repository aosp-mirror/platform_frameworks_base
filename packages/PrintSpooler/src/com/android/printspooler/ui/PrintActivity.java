/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.IPrintDocumentAdapter;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintDocumentInfo;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrintServicesLoader;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintService;
import android.printservice.PrintServiceInfo;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import android.widget.Toast;
import com.android.internal.logging.MetricsLogger;
import com.android.printspooler.R;
import com.android.printspooler.model.MutexFileProvider;
import com.android.printspooler.model.PrintSpoolerProvider;
import com.android.printspooler.model.PrintSpoolerService;
import com.android.printspooler.model.RemotePrintDocument;
import com.android.printspooler.model.RemotePrintDocument.RemotePrintDocumentInfo;
import com.android.printspooler.renderer.IPdfEditor;
import com.android.printspooler.renderer.PdfManipulationService;
import com.android.printspooler.util.ApprovedPrintServices;
import com.android.printspooler.util.MediaSizeUtils;
import com.android.printspooler.util.MediaSizeUtils.MediaSizeComparator;
import com.android.printspooler.util.PageRangeUtils;
import com.android.printspooler.widget.PrintContentView;
import com.android.printspooler.widget.PrintContentView.OptionsStateChangeListener;
import com.android.printspooler.widget.PrintContentView.OptionsStateController;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class PrintActivity extends Activity implements RemotePrintDocument.UpdateResultCallbacks,
        PrintErrorFragment.OnActionListener, PageAdapter.ContentCallbacks,
        OptionsStateChangeListener, OptionsStateController,
        LoaderManager.LoaderCallbacks<List<PrintServiceInfo>> {
    private static final String LOG_TAG = "PrintActivity";

    private static final boolean DEBUG = false;

    private static final String FRAGMENT_TAG = "FRAGMENT_TAG";

    private static final String HAS_PRINTED_PREF = "has_printed";

    private static final int LOADER_ID_ENABLED_PRINT_SERVICES = 1;
    private static final int LOADER_ID_PRINT_REGISTRY = 2;
    private static final int LOADER_ID_PRINT_REGISTRY_INT = 3;

    private static final int ORIENTATION_PORTRAIT = 0;
    private static final int ORIENTATION_LANDSCAPE = 1;

    private static final int ACTIVITY_REQUEST_CREATE_FILE = 1;
    private static final int ACTIVITY_REQUEST_SELECT_PRINTER = 2;
    private static final int ACTIVITY_REQUEST_POPULATE_ADVANCED_PRINT_OPTIONS = 3;

    private static final int DEST_ADAPTER_MAX_ITEM_COUNT = 9;

    private static final int DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF = Integer.MAX_VALUE;
    private static final int DEST_ADAPTER_ITEM_ID_MORE = Integer.MAX_VALUE - 1;

    private static final int STATE_INITIALIZING = 0;
    private static final int STATE_CONFIGURING = 1;
    private static final int STATE_PRINT_CONFIRMED = 2;
    private static final int STATE_PRINT_CANCELED = 3;
    private static final int STATE_UPDATE_FAILED = 4;
    private static final int STATE_CREATE_FILE_FAILED = 5;
    private static final int STATE_PRINTER_UNAVAILABLE = 6;
    private static final int STATE_UPDATE_SLOW = 7;
    private static final int STATE_PRINT_COMPLETED = 8;

    private static final int UI_STATE_PREVIEW = 0;
    private static final int UI_STATE_ERROR = 1;
    private static final int UI_STATE_PROGRESS = 2;

    private static final int MIN_COPIES = 1;
    private static final String MIN_COPIES_STRING = String.valueOf(MIN_COPIES);

    private boolean mIsOptionsUiBound = false;

    private final PrinterAvailabilityDetector mPrinterAvailabilityDetector =
            new PrinterAvailabilityDetector();

    private final OnFocusChangeListener mSelectAllOnFocusListener = new SelectAllOnFocusListener();

    private PrintSpoolerProvider mSpoolerProvider;

    private PrintPreviewController mPrintPreviewController;

    private PrintJobInfo mPrintJob;
    private RemotePrintDocument mPrintedDocument;
    private PrinterRegistry mPrinterRegistry;

    private EditText mCopiesEditText;

    private TextView mPageRangeTitle;
    private EditText mPageRangeEditText;

    private Spinner mDestinationSpinner;
    private DestinationAdapter mDestinationSpinnerAdapter;
    private boolean mShowDestinationPrompt;

    private Spinner mMediaSizeSpinner;
    private ArrayAdapter<SpinnerItem<MediaSize>> mMediaSizeSpinnerAdapter;

    private Spinner mColorModeSpinner;
    private ArrayAdapter<SpinnerItem<Integer>> mColorModeSpinnerAdapter;

    private Spinner mDuplexModeSpinner;
    private ArrayAdapter<SpinnerItem<Integer>> mDuplexModeSpinnerAdapter;

    private Spinner mOrientationSpinner;
    private ArrayAdapter<SpinnerItem<Integer>> mOrientationSpinnerAdapter;

    private Spinner mRangeOptionsSpinner;

    private PrintContentView mOptionsContent;

    private View mSummaryContainer;
    private TextView mSummaryCopies;
    private TextView mSummaryPaperSize;

    private Button mMoreOptionsButton;

    private ImageView mPrintButton;

    private ProgressMessageController mProgressMessageController;
    private MutexFileProvider mFileProvider;

    private MediaSizeComparator mMediaSizeComparator;

    private PrinterInfo mCurrentPrinter;

    private PageRange[] mSelectedPages;

    private String mCallingPackageName;

    private int mCurrentPageCount;

    private int mState = STATE_INITIALIZING;

    private int mUiState = UI_STATE_PREVIEW;

    /** Observer for changes to the printers */
    private PrintersObserver mPrintersObserver;

    /** Advances options activity name for current printer */
    private ComponentName mAdvancedPrintOptionsActivity;

    /** Whether at least one print services is enabled or not */
    private boolean mArePrintServicesEnabled;

    /** Is doFinish() already in progress */
    private boolean mIsFinishing;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();

        mPrintJob = extras.getParcelable(PrintManager.EXTRA_PRINT_JOB);
        if (mPrintJob == null) {
            throw new IllegalArgumentException(PrintManager.EXTRA_PRINT_JOB
                    + " cannot be null");
        }
        if (mPrintJob.getAttributes() == null) {
            mPrintJob.setAttributes(new PrintAttributes.Builder().build());
        }

        final IBinder adapter = extras.getBinder(PrintManager.EXTRA_PRINT_DOCUMENT_ADAPTER);
        if (adapter == null) {
            throw new IllegalArgumentException(PrintManager.EXTRA_PRINT_DOCUMENT_ADAPTER
                    + " cannot be null");
        }

        mCallingPackageName = extras.getString(DocumentsContract.EXTRA_PACKAGE_NAME);

        // This will take just a few milliseconds, so just wait to
        // bind to the local service before showing the UI.
        mSpoolerProvider = new PrintSpoolerProvider(this,
                new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    // onPause might have not been able to cancel the job, see PrintActivity#onPause
                    // To be sure, cancel the job again. Double canceling does no harm.
                    mSpoolerProvider.getSpooler().setPrintJobState(mPrintJob.getId(),
                            PrintJobInfo.STATE_CANCELED, null);
                } else {
                    onConnectedToPrintSpooler(adapter);
                }
            }
        });

        getLoaderManager().initLoader(LOADER_ID_ENABLED_PRINT_SERVICES, null, this);
    }

    private void onConnectedToPrintSpooler(final IBinder documentAdapter) {
        // Now that we are bound to the print spooler service,
        // create the printer registry and wait for it to get
        // the first batch of results which will be delivered
        // after reading historical data. This should be pretty
        // fast, so just wait before showing the UI.
        mPrinterRegistry = new PrinterRegistry(PrintActivity.this, () -> {
            (new Handler(getMainLooper())).post(() -> onPrinterRegistryReady(documentAdapter));
        }, LOADER_ID_PRINT_REGISTRY, LOADER_ID_PRINT_REGISTRY_INT);
    }

    private void onPrinterRegistryReady(IBinder documentAdapter) {
        // Now that we are bound to the local print spooler service
        // and the printer registry loaded the historical printers
        // we can show the UI without flickering.
        setTitle(R.string.print_dialog);
        setContentView(R.layout.print_activity);

        try {
            mFileProvider = new MutexFileProvider(
                    PrintSpoolerService.generateFileForPrintJob(
                            PrintActivity.this, mPrintJob.getId()));
        } catch (IOException ioe) {
            // At this point we cannot recover, so just take it down.
            throw new IllegalStateException("Cannot create print job file", ioe);
        }

        mPrintPreviewController = new PrintPreviewController(PrintActivity.this,
                mFileProvider);
        mPrintedDocument = new RemotePrintDocument(PrintActivity.this,
                IPrintDocumentAdapter.Stub.asInterface(documentAdapter),
                mFileProvider, new RemotePrintDocument.RemoteAdapterDeathObserver() {
            @Override
            public void onDied() {
                Log.w(LOG_TAG, "Printing app died unexpectedly");

                // If we are finishing or we are in a state that we do not need any
                // data from the printing app, then no need to finish.
                if (isFinishing() || isDestroyed() ||
                        (isFinalState(mState) && !mPrintedDocument.isUpdating())) {
                    return;
                }
                setState(STATE_PRINT_CANCELED);
                mPrintedDocument.cancel(true);
                doFinish();
            }
        }, PrintActivity.this);
        mProgressMessageController = new ProgressMessageController(
                PrintActivity.this);
        mMediaSizeComparator = new MediaSizeComparator(PrintActivity.this);
        mDestinationSpinnerAdapter = new DestinationAdapter();

        bindUi();
        updateOptionsUi();

        // Now show the updated UI to avoid flicker.
        mOptionsContent.setVisibility(View.VISIBLE);
        mSelectedPages = computeSelectedPages();
        mPrintedDocument.start();

        ensurePreviewUiShown();

        setState(STATE_CONFIGURING);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mPrinterRegistry != null && mCurrentPrinter != null) {
            mPrinterRegistry.setTrackedPrinter(mCurrentPrinter.getId());
        }
        MetricsLogger.count(this, "print_preview", 1);
    }

    @Override
    public void onPause() {
        PrintSpoolerService spooler = mSpoolerProvider.getSpooler();

        if (mState == STATE_INITIALIZING) {
            if (isFinishing()) {
                if (spooler != null) {
                    spooler.setPrintJobState(mPrintJob.getId(), PrintJobInfo.STATE_CANCELED, null);
                }
            }
            super.onPause();
            return;
        }

        if (isFinishing()) {
            spooler.updatePrintJobUserConfigurableOptionsNoPersistence(mPrintJob);

            switch (mState) {
                case STATE_PRINT_COMPLETED: {
                    if (mCurrentPrinter == mDestinationSpinnerAdapter.getPdfPrinter()) {
                        spooler.setPrintJobState(mPrintJob.getId(), PrintJobInfo.STATE_COMPLETED,
                                null);
                    } else {
                        spooler.setPrintJobState(mPrintJob.getId(), PrintJobInfo.STATE_QUEUED,
                                null);
                    }
                } break;

                case STATE_CREATE_FILE_FAILED: {
                    spooler.setPrintJobState(mPrintJob.getId(), PrintJobInfo.STATE_FAILED,
                            getString(R.string.print_write_error_message));
                } break;

                default: {
                    spooler.setPrintJobState(mPrintJob.getId(), PrintJobInfo.STATE_CANCELED, null);
                } break;
            }
        }

        super.onPause();
    }

    @Override
    protected void onStop() {
        mPrinterAvailabilityDetector.cancel();

        if (mPrinterRegistry != null) {
            mPrinterRegistry.setTrackedPrinter(null);
        }

        super.onStop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mState == STATE_INITIALIZING) {
            doFinish();
            return true;
        }

        if (mState == STATE_PRINT_CANCELED || mState == STATE_PRINT_CONFIRMED
                || mState == STATE_PRINT_COMPLETED) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK
                && event.isTracking() && !event.isCanceled()) {
            if (mPrintPreviewController != null && mPrintPreviewController.isOptionsOpened()
                    && !hasErrors()) {
                mPrintPreviewController.closeOptions();
            } else {
                cancelPrint();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onRequestContentUpdate() {
        if (canUpdateDocument()) {
            updateDocument(false);
        }
    }

    @Override
    public void onMalformedPdfFile() {
        onPrintDocumentError("Cannot print a malformed PDF file");
    }

    @Override
    public void onSecurePdfFile() {
        onPrintDocumentError("Cannot print a password protected PDF file");
    }

    private void onPrintDocumentError(String message) {
        setState(mProgressMessageController.cancel());
        ensureErrorUiShown(null, PrintErrorFragment.ACTION_RETRY);

        setState(STATE_UPDATE_FAILED);

        updateOptionsUi();

        mPrintedDocument.kill(message);
    }

    @Override
    public void onActionPerformed() {
        if (mState == STATE_UPDATE_FAILED
                && canUpdateDocument() && updateDocument(true)) {
            ensurePreviewUiShown();
            setState(STATE_CONFIGURING);
            updateOptionsUi();
        }
    }

    @Override
    public void onUpdateCanceled() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onUpdateCanceled()");
        }

        setState(mProgressMessageController.cancel());
        ensurePreviewUiShown();

        switch (mState) {
            case STATE_PRINT_CONFIRMED: {
                requestCreatePdfFileOrFinish();
            } break;

            case STATE_CREATE_FILE_FAILED:
            case STATE_PRINT_COMPLETED:
            case STATE_PRINT_CANCELED: {
                doFinish();
            } break;
        }
    }

    @Override
    public void onUpdateCompleted(RemotePrintDocumentInfo document) {
        if (DEBUG) {
            Log.i(LOG_TAG, "onUpdateCompleted()");
        }

        setState(mProgressMessageController.cancel());
        ensurePreviewUiShown();

        // Update the print job with the info for the written document. The page
        // count we get from the remote document is the pages in the document from
        // the app perspective but the print job should contain the page count from
        // print service perspective which is the pages in the written PDF not the
        // pages in the printed document.
        PrintDocumentInfo info = document.info;
        if (info != null) {
            final int pageCount = PageRangeUtils.getNormalizedPageCount(document.writtenPages,
                    getAdjustedPageCount(info));
            PrintDocumentInfo adjustedInfo = new PrintDocumentInfo.Builder(info.getName())
                    .setContentType(info.getContentType())
                    .setPageCount(pageCount)
                    .build();
            mPrintJob.setDocumentInfo(adjustedInfo);
            mPrintJob.setPages(document.printedPages);
        }

        switch (mState) {
            case STATE_PRINT_CONFIRMED: {
                requestCreatePdfFileOrFinish();
            } break;

            case STATE_CREATE_FILE_FAILED:
            case STATE_PRINT_COMPLETED:
            case STATE_PRINT_CANCELED: {
                updateOptionsUi();

                doFinish();
            } break;

            default: {
                updatePrintPreviewController(document.changed);

                setState(STATE_CONFIGURING);
                updateOptionsUi();
            } break;
        }
    }

    @Override
    public void onUpdateFailed(CharSequence error) {
        if (DEBUG) {
            Log.i(LOG_TAG, "onUpdateFailed()");
        }

        setState(mProgressMessageController.cancel());
        ensureErrorUiShown(error, PrintErrorFragment.ACTION_RETRY);

        if (mState == STATE_CREATE_FILE_FAILED
                || mState == STATE_PRINT_COMPLETED
                || mState == STATE_PRINT_CANCELED) {
            doFinish();
        }

        setState(STATE_UPDATE_FAILED);

        updateOptionsUi();
    }

    @Override
    public void onOptionsOpened() {
        updateSelectedPagesFromPreview();
    }

    @Override
    public void onOptionsClosed() {
        // Make sure the IME is not on the way of preview as
        // the user may have used it to type copies or range.
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(mDestinationSpinner.getWindowToken(), 0);
    }

    private void updatePrintPreviewController(boolean contentUpdated) {
        // If we have not heard from the application, do nothing.
        RemotePrintDocumentInfo documentInfo = mPrintedDocument.getDocumentInfo();
        if (!documentInfo.laidout) {
            return;
        }

        // Update the preview controller.
        mPrintPreviewController.onContentUpdated(contentUpdated,
                getAdjustedPageCount(documentInfo.info),
                mPrintedDocument.getDocumentInfo().writtenPages,
                mSelectedPages, mPrintJob.getAttributes().getMediaSize(),
                mPrintJob.getAttributes().getMinMargins());
    }


    @Override
    public boolean canOpenOptions() {
        return true;
    }

    @Override
    public boolean canCloseOptions() {
        return !hasErrors();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mMediaSizeComparator.onConfigurationChanged(newConfig);

        if (mPrintPreviewController != null) {
            mPrintPreviewController.onOrientationChanged();
        }
    }

    @Override
    protected void onDestroy() {
        if (mPrintedDocument != null) {
            mPrintedDocument.cancel(true);
        }

        doFinish();

        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case ACTIVITY_REQUEST_CREATE_FILE: {
                onStartCreateDocumentActivityResult(resultCode, data);
            } break;

            case ACTIVITY_REQUEST_SELECT_PRINTER: {
                onSelectPrinterActivityResult(resultCode, data);
            } break;

            case ACTIVITY_REQUEST_POPULATE_ADVANCED_PRINT_OPTIONS: {
                onAdvancedPrintOptionsActivityResult(resultCode, data);
            } break;
        }
    }

    private void startCreateDocumentActivity() {
        if (!isResumed()) {
            return;
        }
        PrintDocumentInfo info = mPrintedDocument.getDocumentInfo().info;
        if (info == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, info.getName());
        intent.putExtra(DocumentsContract.EXTRA_PACKAGE_NAME, mCallingPackageName);

        try {
            startActivityForResult(intent, ACTIVITY_REQUEST_CREATE_FILE);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Could not create file", e);
            Toast.makeText(this, getString(R.string.could_not_create_file),
                    Toast.LENGTH_SHORT).show();
            onStartCreateDocumentActivityResult(RESULT_CANCELED, null);
        }
    }

    private void onStartCreateDocumentActivityResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null) {
            updateOptionsUi();
            final Uri uri = data.getData();
            // Calling finish here does not invoke lifecycle callbacks but we
            // update the print job in onPause if finishing, hence post a message.
            mDestinationSpinner.post(new Runnable() {
                @Override
                public void run() {
                    transformDocumentAndFinish(uri);
                }
            });
        } else if (resultCode == RESULT_CANCELED) {
            if (DEBUG) {
                Log.i(LOG_TAG, "[state]" + STATE_CONFIGURING);
            }

            mState = STATE_CONFIGURING;

            // The previous update might have been canceled
            updateDocument(false);

            updateOptionsUi();
        } else {
            setState(STATE_CREATE_FILE_FAILED);
            updateOptionsUi();
            // Calling finish here does not invoke lifecycle callbacks but we
            // update the print job in onPause if finishing, hence post a message.
            mDestinationSpinner.post(new Runnable() {
                @Override
                public void run() {
                    doFinish();
                }
            });
        }
    }

    private void startSelectPrinterActivity() {
        Intent intent = new Intent(this, SelectPrinterActivity.class);
        startActivityForResult(intent, ACTIVITY_REQUEST_SELECT_PRINTER);
    }

    private void onSelectPrinterActivityResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null) {
            PrinterInfo printerInfo = data.getParcelableExtra(
                    SelectPrinterActivity.INTENT_EXTRA_PRINTER);
            if (printerInfo != null) {
                mCurrentPrinter = printerInfo;
                mPrintJob.setPrinterId(printerInfo.getId());
                mPrintJob.setPrinterName(printerInfo.getName());

                mDestinationSpinnerAdapter.ensurePrinterInVisibleAdapterPosition(printerInfo);
            }
        }

        if (mCurrentPrinter != null) {
            // Trigger PrintersObserver.onChanged() to adjust selection back to current printer
            mDestinationSpinnerAdapter.notifyDataSetChanged();
        }
    }

    private void startAdvancedPrintOptionsActivity(PrinterInfo printer) {
        if (mAdvancedPrintOptionsActivity == null) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(mAdvancedPrintOptionsActivity);

        List<ResolveInfo> resolvedActivities = getPackageManager()
                .queryIntentActivities(intent, 0);
        if (resolvedActivities.isEmpty()) {
            return;
        }

        // The activity is a component name, therefore it is one or none.
        if (resolvedActivities.get(0).activityInfo.exported) {
            PrintJobInfo.Builder printJobBuilder = new PrintJobInfo.Builder(mPrintJob);
            printJobBuilder.setPages(mSelectedPages);

            intent.putExtra(PrintService.EXTRA_PRINT_JOB_INFO, printJobBuilder.build());
            intent.putExtra(PrintService.EXTRA_PRINTER_INFO, printer);
            intent.putExtra(PrintService.EXTRA_PRINT_DOCUMENT_INFO,
                    mPrintedDocument.getDocumentInfo().info);

            // This is external activity and may not be there.
            try {
                startActivityForResult(intent, ACTIVITY_REQUEST_POPULATE_ADVANCED_PRINT_OPTIONS);
            } catch (ActivityNotFoundException anfe) {
                Log.e(LOG_TAG, "Error starting activity for intent: " + intent, anfe);
            }
        }
    }

    private void onAdvancedPrintOptionsActivityResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        PrintJobInfo printJobInfo = data.getParcelableExtra(PrintService.EXTRA_PRINT_JOB_INFO);

        if (printJobInfo == null) {
            return;
        }

        // Take the advanced options without interpretation.
        mPrintJob.setAdvancedOptions(printJobInfo.getAdvancedOptions());

        if (printJobInfo.getCopies() < 1) {
            Log.w(LOG_TAG, "Cannot apply return value from advanced options activity. Copies " +
                    "must be 1 or more. Actual value is: " + printJobInfo.getCopies() + ". " +
                    "Ignoring.");
        } else {
            mCopiesEditText.setText(String.valueOf(printJobInfo.getCopies()));
            mPrintJob.setCopies(printJobInfo.getCopies());
        }

        PrintAttributes currAttributes = mPrintJob.getAttributes();
        PrintAttributes newAttributes = printJobInfo.getAttributes();

        if (newAttributes != null) {
            // Take the media size only if the current printer supports is.
            MediaSize oldMediaSize = currAttributes.getMediaSize();
            MediaSize newMediaSize = newAttributes.getMediaSize();
            if (newMediaSize != null && !oldMediaSize.equals(newMediaSize)) {
                final int mediaSizeCount = mMediaSizeSpinnerAdapter.getCount();
                MediaSize newMediaSizePortrait = newAttributes.getMediaSize().asPortrait();
                for (int i = 0; i < mediaSizeCount; i++) {
                    MediaSize supportedSizePortrait = mMediaSizeSpinnerAdapter.getItem(i)
                            .value.asPortrait();
                    if (supportedSizePortrait.equals(newMediaSizePortrait)) {
                        currAttributes.setMediaSize(newMediaSize);
                        mMediaSizeSpinner.setSelection(i);
                        if (currAttributes.getMediaSize().isPortrait()) {
                            if (mOrientationSpinner.getSelectedItemPosition() != 0) {
                                mOrientationSpinner.setSelection(0);
                            }
                        } else {
                            if (mOrientationSpinner.getSelectedItemPosition() != 1) {
                                mOrientationSpinner.setSelection(1);
                            }
                        }
                        break;
                    }
                }
            }

            // Take the resolution only if the current printer supports is.
            Resolution oldResolution = currAttributes.getResolution();
            Resolution newResolution = newAttributes.getResolution();
            if (!oldResolution.equals(newResolution)) {
                PrinterCapabilitiesInfo capabilities = mCurrentPrinter.getCapabilities();
                if (capabilities != null) {
                    List<Resolution> resolutions = capabilities.getResolutions();
                    final int resolutionCount = resolutions.size();
                    for (int i = 0; i < resolutionCount; i++) {
                        Resolution resolution = resolutions.get(i);
                        if (resolution.equals(newResolution)) {
                            currAttributes.setResolution(resolution);
                            break;
                        }
                    }
                }
            }

            // Take the color mode only if the current printer supports it.
            final int currColorMode = currAttributes.getColorMode();
            final int newColorMode = newAttributes.getColorMode();
            if (currColorMode != newColorMode) {
                final int colorModeCount = mColorModeSpinner.getCount();
                for (int i = 0; i < colorModeCount; i++) {
                    final int supportedColorMode = mColorModeSpinnerAdapter.getItem(i).value;
                    if (supportedColorMode == newColorMode) {
                        currAttributes.setColorMode(newColorMode);
                        mColorModeSpinner.setSelection(i);
                        break;
                    }
                }
            }

            // Take the duplex mode only if the current printer supports it.
            final int currDuplexMode = currAttributes.getDuplexMode();
            final int newDuplexMode = newAttributes.getDuplexMode();
            if (currDuplexMode != newDuplexMode) {
                final int duplexModeCount = mDuplexModeSpinner.getCount();
                for (int i = 0; i < duplexModeCount; i++) {
                    final int supportedDuplexMode = mDuplexModeSpinnerAdapter.getItem(i).value;
                    if (supportedDuplexMode == newDuplexMode) {
                        currAttributes.setDuplexMode(newDuplexMode);
                        mDuplexModeSpinner.setSelection(i);
                        break;
                    }
                }
            }
        }

        // Handle selected page changes making sure they are in the doc.
        PrintDocumentInfo info = mPrintedDocument.getDocumentInfo().info;
        final int pageCount = (info != null) ? getAdjustedPageCount(info) : 0;
        PageRange[] pageRanges = printJobInfo.getPages();
        if (pageRanges != null && pageCount > 0) {
            pageRanges = PageRangeUtils.normalize(pageRanges);

            List<PageRange> validatedList = new ArrayList<>();
            final int rangeCount = pageRanges.length;
            for (int i = 0; i < rangeCount; i++) {
                PageRange pageRange = pageRanges[i];
                if (pageRange.getEnd() >= pageCount) {
                    final int rangeStart = pageRange.getStart();
                    final int rangeEnd = pageCount - 1;
                    if (rangeStart <= rangeEnd) {
                        pageRange = new PageRange(rangeStart, rangeEnd);
                        validatedList.add(pageRange);
                    }
                    break;
                }
                validatedList.add(pageRange);
            }

            if (!validatedList.isEmpty()) {
                PageRange[] validatedArray = new PageRange[validatedList.size()];
                validatedList.toArray(validatedArray);
                updateSelectedPages(validatedArray, pageCount);
            }
        }

        // Update the content if needed.
        if (canUpdateDocument()) {
            updateDocument(false);
        }
    }

    private void setState(int state) {
        if (isFinalState(mState)) {
            if (isFinalState(state)) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "[state]" + state);
                }
                mState = state;
            }
        } else {
            if (DEBUG) {
                Log.i(LOG_TAG, "[state]" + state);
            }
            mState = state;
        }
    }

    private static boolean isFinalState(int state) {
        return state == STATE_PRINT_CANCELED
                || state == STATE_PRINT_COMPLETED
                || state == STATE_CREATE_FILE_FAILED;
    }

    private void updateSelectedPagesFromPreview() {
        PageRange[] selectedPages = mPrintPreviewController.getSelectedPages();
        if (!Arrays.equals(mSelectedPages, selectedPages)) {
            updateSelectedPages(selectedPages,
                    getAdjustedPageCount(mPrintedDocument.getDocumentInfo().info));
        }
    }

    private void updateSelectedPages(PageRange[] selectedPages, int pageInDocumentCount) {
        if (selectedPages == null || selectedPages.length <= 0) {
            return;
        }

        selectedPages = PageRangeUtils.normalize(selectedPages);

        // Handle the case where all pages are specified explicitly
        // instead of the *all pages* constant.
        if (PageRangeUtils.isAllPages(selectedPages, pageInDocumentCount)) {
            selectedPages = new PageRange[] {PageRange.ALL_PAGES};
        }

        if (Arrays.equals(mSelectedPages, selectedPages)) {
            return;
        }

        mSelectedPages = selectedPages;
        mPrintJob.setPages(selectedPages);

        if (Arrays.equals(selectedPages, PageRange.ALL_PAGES_ARRAY)) {
            if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                mRangeOptionsSpinner.setSelection(0);
                mPageRangeEditText.setText("");
            }
        } else if (selectedPages[0].getStart() >= 0
                && selectedPages[selectedPages.length - 1].getEnd() < pageInDocumentCount) {
            if (mRangeOptionsSpinner.getSelectedItemPosition() != 1) {
                mRangeOptionsSpinner.setSelection(1);
            }

            StringBuilder builder = new StringBuilder();
            final int pageRangeCount = selectedPages.length;
            for (int i = 0; i < pageRangeCount; i++) {
                if (builder.length() > 0) {
                    builder.append(',');
                }

                final int shownStartPage;
                final int shownEndPage;
                PageRange pageRange = selectedPages[i];
                if (pageRange.equals(PageRange.ALL_PAGES)) {
                    shownStartPage = 1;
                    shownEndPage = pageInDocumentCount;
                } else {
                    shownStartPage = pageRange.getStart() + 1;
                    shownEndPage = pageRange.getEnd() + 1;
                }

                builder.append(shownStartPage);

                if (shownStartPage != shownEndPage) {
                    builder.append('-');
                    builder.append(shownEndPage);
                }
            }

            mPageRangeEditText.setText(builder.toString());
        }
    }

    private void ensureProgressUiShown() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (mUiState != UI_STATE_PROGRESS) {
            mUiState = UI_STATE_PROGRESS;
            mPrintPreviewController.setUiShown(false);
            Fragment fragment = PrintProgressFragment.newInstance();
            showFragment(fragment);
        }
    }

    private void ensurePreviewUiShown() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (mUiState != UI_STATE_PREVIEW) {
            mUiState = UI_STATE_PREVIEW;
            mPrintPreviewController.setUiShown(true);
            showFragment(null);
        }
    }

    private void ensureErrorUiShown(CharSequence message, int action) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (mUiState != UI_STATE_ERROR) {
            mUiState = UI_STATE_ERROR;
            mPrintPreviewController.setUiShown(false);
            Fragment fragment = PrintErrorFragment.newInstance(message, action);
            showFragment(fragment);
        }
    }

    private void showFragment(Fragment newFragment) {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        Fragment oldFragment = getFragmentManager().findFragmentByTag(FRAGMENT_TAG);
        if (oldFragment != null) {
            transaction.remove(oldFragment);
        }
        if (newFragment != null) {
            transaction.add(R.id.embedded_content_container, newFragment, FRAGMENT_TAG);
        }
        transaction.commitAllowingStateLoss();
        getFragmentManager().executePendingTransactions();
    }

    private void requestCreatePdfFileOrFinish() {
        mPrintedDocument.cancel(false);

        if (mCurrentPrinter == mDestinationSpinnerAdapter.getPdfPrinter()) {
            startCreateDocumentActivity();
        } else {
            transformDocumentAndFinish(null);
        }
    }

    /**
     * Clear the selected page range and update the preview if needed.
     */
    private void clearPageRanges() {
        mRangeOptionsSpinner.setSelection(0);
        mPageRangeEditText.setError(null);
        mPageRangeEditText.setText("");
        mSelectedPages = PageRange.ALL_PAGES_ARRAY;

        if (!Arrays.equals(mSelectedPages, mPrintPreviewController.getSelectedPages())) {
            updatePrintPreviewController(false);
        }
    }

    private void updatePrintAttributesFromCapabilities(PrinterCapabilitiesInfo capabilities) {
        boolean clearRanges = false;
        PrintAttributes defaults = capabilities.getDefaults();

        // Sort the media sizes based on the current locale.
        List<MediaSize> sortedMediaSizes = new ArrayList<>(capabilities.getMediaSizes());
        Collections.sort(sortedMediaSizes, mMediaSizeComparator);

        PrintAttributes attributes = mPrintJob.getAttributes();

        // Media size.
        MediaSize currMediaSize = attributes.getMediaSize();
        if (currMediaSize == null) {
            clearRanges = true;
            attributes.setMediaSize(defaults.getMediaSize());
        } else {
            MediaSize newMediaSize = null;
            boolean isPortrait = currMediaSize.isPortrait();

            // Try to find the current media size in the capabilities as
            // it may be in a different orientation.
            MediaSize currMediaSizePortrait = currMediaSize.asPortrait();
            final int mediaSizeCount = sortedMediaSizes.size();
            for (int i = 0; i < mediaSizeCount; i++) {
                MediaSize mediaSize = sortedMediaSizes.get(i);
                if (currMediaSizePortrait.equals(mediaSize.asPortrait())) {
                    newMediaSize = mediaSize;
                    break;
                }
            }
            // If we did not find the current media size fall back to default.
            if (newMediaSize == null) {
                clearRanges = true;
                newMediaSize = defaults.getMediaSize();
            }

            if (newMediaSize != null) {
                if (isPortrait) {
                    attributes.setMediaSize(newMediaSize.asPortrait());
                } else {
                    attributes.setMediaSize(newMediaSize.asLandscape());
                }
            }
        }

        // Color mode.
        final int colorMode = attributes.getColorMode();
        if ((capabilities.getColorModes() & colorMode) == 0) {
            attributes.setColorMode(defaults.getColorMode());
        }

        // Duplex mode.
        final int duplexMode = attributes.getDuplexMode();
        if ((capabilities.getDuplexModes() & duplexMode) == 0) {
            attributes.setDuplexMode(defaults.getDuplexMode());
        }

        // Resolution
        Resolution resolution = attributes.getResolution();
        if (resolution == null || !capabilities.getResolutions().contains(resolution)) {
            attributes.setResolution(defaults.getResolution());
        }

        // Margins.
        if (!Objects.equals(attributes.getMinMargins(), defaults.getMinMargins())) {
            clearRanges = true;
        }
        attributes.setMinMargins(defaults.getMinMargins());

        if (clearRanges) {
            clearPageRanges();
        }
    }

    private boolean updateDocument(boolean clearLastError) {
        if (!clearLastError && mPrintedDocument.hasUpdateError()) {
            return false;
        }

        if (clearLastError && mPrintedDocument.hasUpdateError()) {
            mPrintedDocument.clearUpdateError();
        }

        final boolean preview = mState != STATE_PRINT_CONFIRMED;
        final PageRange[] pages;
        if (preview) {
            pages = mPrintPreviewController.getRequestedPages();
        } else {
            pages = mPrintPreviewController.getSelectedPages();
        }

        final boolean willUpdate = mPrintedDocument.update(mPrintJob.getAttributes(),
                pages, preview);

        if (willUpdate && !mPrintedDocument.hasLaidOutPages()) {
            // When the update is done we update the print preview.
            mProgressMessageController.post();
            return true;
        } else if (!willUpdate) {
            // Update preview.
            updatePrintPreviewController(false);
        }

        return false;
    }

    private void addCurrentPrinterToHistory() {
        if (mCurrentPrinter != null) {
            PrinterId fakePdfPrinterId = mDestinationSpinnerAdapter.getPdfPrinter().getId();
            if (!mCurrentPrinter.getId().equals(fakePdfPrinterId)) {
                mPrinterRegistry.addHistoricalPrinter(mCurrentPrinter);
            }
        }
    }

    private void cancelPrint() {
        setState(STATE_PRINT_CANCELED);
        updateOptionsUi();
        mPrintedDocument.cancel(true);
        doFinish();
    }

    /**
     * Update the selected pages from the text field.
     */
    private void updateSelectedPagesFromTextField() {
        PageRange[] selectedPages = computeSelectedPages();
        if (!Arrays.equals(mSelectedPages, selectedPages)) {
            mSelectedPages = selectedPages;
            // Update preview.
            updatePrintPreviewController(false);
        }
    }

    private void confirmPrint() {
        setState(STATE_PRINT_CONFIRMED);

        MetricsLogger.count(this, "print_confirmed", 1);

        updateOptionsUi();
        addCurrentPrinterToHistory();
        setUserPrinted();

        // updateSelectedPagesFromTextField migth update the preview, hence apply the preview first
        updateSelectedPagesFromPreview();
        updateSelectedPagesFromTextField();

        mPrintPreviewController.closeOptions();

        if (canUpdateDocument()) {
            updateDocument(false);
        }

        if (!mPrintedDocument.isUpdating()) {
            requestCreatePdfFileOrFinish();
        }
    }

    private void bindUi() {
        // Summary
        mSummaryContainer = findViewById(R.id.summary_content);
        mSummaryCopies = (TextView) findViewById(R.id.copies_count_summary);
        mSummaryPaperSize = (TextView) findViewById(R.id.paper_size_summary);

        // Options container
        mOptionsContent = (PrintContentView) findViewById(R.id.options_content);
        mOptionsContent.setOptionsStateChangeListener(this);
        mOptionsContent.setOpenOptionsController(this);

        OnItemSelectedListener itemSelectedListener = new MyOnItemSelectedListener();
        OnClickListener clickListener = new MyClickListener();

        // Copies
        mCopiesEditText = (EditText) findViewById(R.id.copies_edittext);
        mCopiesEditText.setOnFocusChangeListener(mSelectAllOnFocusListener);
        mCopiesEditText.setText(MIN_COPIES_STRING);
        mCopiesEditText.setSelection(mCopiesEditText.getText().length());
        mCopiesEditText.addTextChangedListener(new EditTextWatcher());

        // Destination.
        mPrintersObserver = new PrintersObserver();
        mDestinationSpinnerAdapter.registerDataSetObserver(mPrintersObserver);
        mDestinationSpinner = (Spinner) findViewById(R.id.destination_spinner);
        mDestinationSpinner.setAdapter(mDestinationSpinnerAdapter);
        mDestinationSpinner.setOnItemSelectedListener(itemSelectedListener);

        // Media size.
        mMediaSizeSpinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, android.R.id.text1);
        mMediaSizeSpinner = (Spinner) findViewById(R.id.paper_size_spinner);
        mMediaSizeSpinner.setAdapter(mMediaSizeSpinnerAdapter);
        mMediaSizeSpinner.setOnItemSelectedListener(itemSelectedListener);

        // Color mode.
        mColorModeSpinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, android.R.id.text1);
        mColorModeSpinner = (Spinner) findViewById(R.id.color_spinner);
        mColorModeSpinner.setAdapter(mColorModeSpinnerAdapter);
        mColorModeSpinner.setOnItemSelectedListener(itemSelectedListener);

        // Duplex mode.
        mDuplexModeSpinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, android.R.id.text1);
        mDuplexModeSpinner = (Spinner) findViewById(R.id.duplex_spinner);
        mDuplexModeSpinner.setAdapter(mDuplexModeSpinnerAdapter);
        mDuplexModeSpinner.setOnItemSelectedListener(itemSelectedListener);

        // Orientation
        mOrientationSpinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, android.R.id.text1);
        String[] orientationLabels = getResources().getStringArray(
                R.array.orientation_labels);
        mOrientationSpinnerAdapter.add(new SpinnerItem<>(
                ORIENTATION_PORTRAIT, orientationLabels[0]));
        mOrientationSpinnerAdapter.add(new SpinnerItem<>(
                ORIENTATION_LANDSCAPE, orientationLabels[1]));
        mOrientationSpinner = (Spinner) findViewById(R.id.orientation_spinner);
        mOrientationSpinner.setAdapter(mOrientationSpinnerAdapter);
        mOrientationSpinner.setOnItemSelectedListener(itemSelectedListener);

        // Range options
        ArrayAdapter<SpinnerItem<Integer>> rangeOptionsSpinnerAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, android.R.id.text1);
        mRangeOptionsSpinner = (Spinner) findViewById(R.id.range_options_spinner);
        mRangeOptionsSpinner.setAdapter(rangeOptionsSpinnerAdapter);
        mRangeOptionsSpinner.setOnItemSelectedListener(itemSelectedListener);
        updatePageRangeOptions(PrintDocumentInfo.PAGE_COUNT_UNKNOWN);

        // Page range
        mPageRangeTitle = (TextView) findViewById(R.id.page_range_title);
        mPageRangeEditText = (EditText) findViewById(R.id.page_range_edittext);
        mPageRangeEditText.setVisibility(View.INVISIBLE);
        mPageRangeTitle.setVisibility(View.INVISIBLE);
        mPageRangeEditText.setOnFocusChangeListener(mSelectAllOnFocusListener);
        mPageRangeEditText.addTextChangedListener(new RangeTextWatcher());

        // Advanced options button.
        mMoreOptionsButton = (Button) findViewById(R.id.more_options_button);
        mMoreOptionsButton.setOnClickListener(clickListener);

        // Print button
        mPrintButton = (ImageView) findViewById(R.id.print_button);
        mPrintButton.setOnClickListener(clickListener);

        // The UI is now initialized
        mIsOptionsUiBound = true;

        // Special prompt instead of destination spinner for the first time the user printed
        if (!hasUserEverPrinted()) {
            mShowDestinationPrompt = true;

            mSummaryCopies.setEnabled(false);
            mSummaryPaperSize.setEnabled(false);

            mDestinationSpinner.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    mShowDestinationPrompt = false;
                    mSummaryCopies.setEnabled(true);
                    mSummaryPaperSize.setEnabled(true);
                    updateOptionsUi();

                    mDestinationSpinner.setOnTouchListener(null);
                    mDestinationSpinnerAdapter.notifyDataSetChanged();

                    return false;
                }
            });
        }
    }

    @Override
    public Loader<List<PrintServiceInfo>> onCreateLoader(int id, Bundle args) {
        return new PrintServicesLoader((PrintManager) getSystemService(Context.PRINT_SERVICE), this,
                PrintManager.ENABLED_SERVICES);
    }

    @Override
    public void onLoadFinished(Loader<List<PrintServiceInfo>> loader,
            List<PrintServiceInfo> services) {
        ComponentName newAdvancedPrintOptionsActivity = null;
        if (mCurrentPrinter != null && services != null) {
            final int numServices = services.size();
            for (int i = 0; i < numServices; i++) {
                PrintServiceInfo service = services.get(i);

                if (service.getComponentName().equals(mCurrentPrinter.getId().getServiceName())) {
                    String advancedOptionsActivityName = service.getAdvancedOptionsActivityName();

                    if (!TextUtils.isEmpty(advancedOptionsActivityName)) {
                        newAdvancedPrintOptionsActivity = new ComponentName(
                                service.getComponentName().getPackageName(),
                                advancedOptionsActivityName);

                        break;
                    }
                }
            }
        }

        if (!Objects.equals(newAdvancedPrintOptionsActivity, mAdvancedPrintOptionsActivity)) {
            mAdvancedPrintOptionsActivity = newAdvancedPrintOptionsActivity;
            updateOptionsUi();
        }

        boolean newArePrintServicesEnabled = services != null && !services.isEmpty();
        if (mArePrintServicesEnabled != newArePrintServicesEnabled) {
            mArePrintServicesEnabled = newArePrintServicesEnabled;

            // Reload mDestinationSpinnerAdapter as mArePrintServicesEnabled changed and the adapter
            // reads that in DestinationAdapter#getMoreItemTitle
            if (mDestinationSpinnerAdapter != null) {
                mDestinationSpinnerAdapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public void onLoaderReset(Loader<List<PrintServiceInfo>> loader) {
        if (!(isFinishing() || isDestroyed())) {
            onLoadFinished(loader, null);
        }
    }

    /**
     * A dialog that asks the user to approve a {@link PrintService}. This dialog is automatically
     * dismissed if the same {@link PrintService} gets approved by another
     * {@link PrintServiceApprovalDialog}.
     */
    private static final class PrintServiceApprovalDialog extends DialogFragment
            implements OnSharedPreferenceChangeListener {
        private static final String PRINTSERVICE_KEY = "PRINTSERVICE";
        private ApprovedPrintServices mApprovedServices;

        /**
         * Create a new {@link PrintServiceApprovalDialog} that ask the user to approve a
         * {@link PrintService}.
         *
         * @param printService The {@link ComponentName} of the service to approve
         * @return A new {@link PrintServiceApprovalDialog} that might approve the service
         */
        static PrintServiceApprovalDialog newInstance(ComponentName printService) {
            PrintServiceApprovalDialog dialog = new PrintServiceApprovalDialog();

            Bundle args = new Bundle();
            args.putParcelable(PRINTSERVICE_KEY, printService);
            dialog.setArguments(args);

            return dialog;
        }

        @Override
        public void onStop() {
            super.onStop();

            mApprovedServices.unregisterChangeListener(this);
        }

        @Override
        public void onStart() {
            super.onStart();

            ComponentName printService = getArguments().getParcelable(PRINTSERVICE_KEY);
            synchronized (ApprovedPrintServices.sLock) {
                if (mApprovedServices.isApprovedService(printService)) {
                    dismiss();
                } else {
                    mApprovedServices.registerChangeListenerLocked(this);
                }
            }
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            super.onCreateDialog(savedInstanceState);

            mApprovedServices = new ApprovedPrintServices(getActivity());

            PackageManager packageManager = getActivity().getPackageManager();
            CharSequence serviceLabel;
            try {
                ComponentName printService = getArguments().getParcelable(PRINTSERVICE_KEY);

                serviceLabel = packageManager.getApplicationInfo(printService.getPackageName(), 0)
                        .loadLabel(packageManager);
            } catch (NameNotFoundException e) {
                serviceLabel = null;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle(getString(R.string.print_service_security_warning_title,
                    serviceLabel))
                    .setMessage(getString(R.string.print_service_security_warning_summary,
                            serviceLabel))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int id) {
                            ComponentName printService =
                                    getArguments().getParcelable(PRINTSERVICE_KEY);
                            // Prevent onSharedPreferenceChanged from getting triggered
                            mApprovedServices
                                    .unregisterChangeListener(PrintServiceApprovalDialog.this);

                            mApprovedServices.addApprovedService(printService);
                            ((PrintActivity) getActivity()).confirmPrint();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null);

            return builder.create();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            ComponentName printService = getArguments().getParcelable(PRINTSERVICE_KEY);

            synchronized (ApprovedPrintServices.sLock) {
                if (mApprovedServices.isApprovedService(printService)) {
                    dismiss();
                }
            }
        }
    }

    private final class MyClickListener implements OnClickListener {
        @Override
        public void onClick(View view) {
            if (view == mPrintButton) {
                if (mCurrentPrinter != null) {
                    if (mDestinationSpinnerAdapter.getPdfPrinter() == mCurrentPrinter) {
                        confirmPrint();
                    } else {
                        ApprovedPrintServices approvedServices =
                                new ApprovedPrintServices(PrintActivity.this);

                        ComponentName printService = mCurrentPrinter.getId().getServiceName();
                        if (approvedServices.isApprovedService(printService)) {
                            confirmPrint();
                        } else {
                            PrintServiceApprovalDialog.newInstance(printService)
                                    .show(getFragmentManager(), "approve");
                        }
                    }
                } else {
                    cancelPrint();
                }
            } else if (view == mMoreOptionsButton) {
                if (mPageRangeEditText.getError() == null) {
                    // The selected pages is only applied once the user leaves the text field. A click
                    // on this button, does not count as leaving.
                    updateSelectedPagesFromTextField();
                }

                if (mCurrentPrinter != null) {
                    startAdvancedPrintOptionsActivity(mCurrentPrinter);
                }
            }
        }
    }

    private static boolean canPrint(PrinterInfo printer) {
        return printer.getCapabilities() != null
                && printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE;
    }

    /**
     * Disable all options UI elements, beside the {@link #mDestinationSpinner}
     */
    private void disableOptionsUi() {
        mCopiesEditText.setEnabled(false);
        mCopiesEditText.setFocusable(false);
        mMediaSizeSpinner.setEnabled(false);
        mColorModeSpinner.setEnabled(false);
        mDuplexModeSpinner.setEnabled(false);
        mOrientationSpinner.setEnabled(false);
        mRangeOptionsSpinner.setEnabled(false);
        mPageRangeEditText.setEnabled(false);
        mPrintButton.setVisibility(View.GONE);
        mMoreOptionsButton.setEnabled(false);
    }

    void updateOptionsUi() {
        if (!mIsOptionsUiBound) {
            return;
        }

        // Always update the summary.
        updateSummary();

        if (mState == STATE_PRINT_CONFIRMED
                || mState == STATE_PRINT_COMPLETED
                || mState == STATE_PRINT_CANCELED
                || mState == STATE_UPDATE_FAILED
                || mState == STATE_CREATE_FILE_FAILED
                || mState == STATE_PRINTER_UNAVAILABLE
                || mState == STATE_UPDATE_SLOW) {
            if (mState != STATE_PRINTER_UNAVAILABLE) {
                mDestinationSpinner.setEnabled(false);
            }
            disableOptionsUi();
            return;
        }

        // If no current printer, or it has no capabilities, or it is not
        // available, we disable all print options except the destination.
        if (mCurrentPrinter == null || !canPrint(mCurrentPrinter)) {
            disableOptionsUi();
            return;
        }

        PrinterCapabilitiesInfo capabilities = mCurrentPrinter.getCapabilities();
        PrintAttributes defaultAttributes = capabilities.getDefaults();

        // Destination.
        mDestinationSpinner.setEnabled(true);

        // Media size.
        mMediaSizeSpinner.setEnabled(true);

        List<MediaSize> mediaSizes = new ArrayList<>(capabilities.getMediaSizes());
        // Sort the media sizes based on the current locale.
        Collections.sort(mediaSizes, mMediaSizeComparator);

        PrintAttributes attributes = mPrintJob.getAttributes();

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
            MediaSize oldMediaSize = attributes.getMediaSize();

            // Rebuild the adapter data.
            mMediaSizeSpinnerAdapter.clear();
            for (int i = 0; i < mediaSizeCount; i++) {
                MediaSize mediaSize = mediaSizes.get(i);
                if (oldMediaSize != null
                        && mediaSize.asPortrait().equals(oldMediaSize.asPortrait())) {
                    // Update the index of the old selection.
                    oldMediaSizeNewIndex = i;
                }
                mMediaSizeSpinnerAdapter.add(new SpinnerItem<>(
                        mediaSize, mediaSize.getLabel(getPackageManager())));
            }

            if (oldMediaSizeNewIndex != AdapterView.INVALID_POSITION) {
                // Select the old media size - nothing really changed.
                if (mMediaSizeSpinner.getSelectedItemPosition() != oldMediaSizeNewIndex) {
                    mMediaSizeSpinner.setSelection(oldMediaSizeNewIndex);
                }
            } else {
                // Select the first or the default.
                final int mediaSizeIndex = Math.max(mediaSizes.indexOf(
                        defaultAttributes.getMediaSize()), 0);
                if (mMediaSizeSpinner.getSelectedItemPosition() != mediaSizeIndex) {
                    mMediaSizeSpinner.setSelection(mediaSizeIndex);
                }
                // Respect the orientation of the old selection.
                if (oldMediaSize != null) {
                    if (oldMediaSize.isPortrait()) {
                        attributes.setMediaSize(mMediaSizeSpinnerAdapter
                                .getItem(mediaSizeIndex).value.asPortrait());
                    } else {
                        attributes.setMediaSize(mMediaSizeSpinnerAdapter
                                .getItem(mediaSizeIndex).value.asLandscape());
                    }
                }
            }
        }

        // Color mode.
        mColorModeSpinner.setEnabled(true);
        final int colorModes = capabilities.getColorModes();

        // If the color modes changed, we update the adapter and the spinner.
        boolean colorModesChanged = false;
        if (Integer.bitCount(colorModes) != mColorModeSpinnerAdapter.getCount()) {
            colorModesChanged = true;
        } else {
            int remainingColorModes = colorModes;
            int adapterIndex = 0;
            while (remainingColorModes != 0) {
                final int colorBitOffset = Integer.numberOfTrailingZeros(remainingColorModes);
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
            final int oldColorMode = attributes.getColorMode();

            // Rebuild the adapter data.
            mColorModeSpinnerAdapter.clear();
            String[] colorModeLabels = getResources().getStringArray(R.array.color_mode_labels);
            int remainingColorModes = colorModes;
            while (remainingColorModes != 0) {
                final int colorBitOffset = Integer.numberOfTrailingZeros(remainingColorModes);
                final int colorMode = 1 << colorBitOffset;
                if (colorMode == oldColorMode) {
                    // Update the index of the old selection.
                    oldColorModeNewIndex = mColorModeSpinnerAdapter.getCount();
                }
                remainingColorModes &= ~colorMode;
                mColorModeSpinnerAdapter.add(new SpinnerItem<>(colorMode,
                        colorModeLabels[colorBitOffset]));
            }
            if (oldColorModeNewIndex != AdapterView.INVALID_POSITION) {
                // Select the old color mode - nothing really changed.
                if (mColorModeSpinner.getSelectedItemPosition() != oldColorModeNewIndex) {
                    mColorModeSpinner.setSelection(oldColorModeNewIndex);
                }
            } else {
                // Select the default.
                final int selectedColorMode = colorModes & defaultAttributes.getColorMode();
                final int itemCount = mColorModeSpinnerAdapter.getCount();
                for (int i = 0; i < itemCount; i++) {
                    SpinnerItem<Integer> item = mColorModeSpinnerAdapter.getItem(i);
                    if (selectedColorMode == item.value) {
                        if (mColorModeSpinner.getSelectedItemPosition() != i) {
                            mColorModeSpinner.setSelection(i);
                        }
                        attributes.setColorMode(selectedColorMode);
                        break;
                    }
                }
            }
        }

        // Duplex mode.
        mDuplexModeSpinner.setEnabled(true);
        final int duplexModes = capabilities.getDuplexModes();

        // If the duplex modes changed, we update the adapter and the spinner.
        // Note that we use bit count +1 to account for the no duplex option.
        boolean duplexModesChanged = false;
        if (Integer.bitCount(duplexModes) != mDuplexModeSpinnerAdapter.getCount()) {
            duplexModesChanged = true;
        } else {
            int remainingDuplexModes = duplexModes;
            int adapterIndex = 0;
            while (remainingDuplexModes != 0) {
                final int duplexBitOffset = Integer.numberOfTrailingZeros(remainingDuplexModes);
                final int duplexMode = 1 << duplexBitOffset;
                remainingDuplexModes &= ~duplexMode;
                if (duplexMode != mDuplexModeSpinnerAdapter.getItem(adapterIndex).value) {
                    duplexModesChanged = true;
                    break;
                }
                adapterIndex++;
            }
        }
        if (duplexModesChanged) {
            // Remember the old duplex mode to try selecting it again. Also the fallback
            // is no duplexing which is always the first item in the dropdown.
            int oldDuplexModeNewIndex = AdapterView.INVALID_POSITION;
            final int oldDuplexMode = attributes.getDuplexMode();

            // Rebuild the adapter data.
            mDuplexModeSpinnerAdapter.clear();
            String[] duplexModeLabels = getResources().getStringArray(R.array.duplex_mode_labels);
            int remainingDuplexModes = duplexModes;
            while (remainingDuplexModes != 0) {
                final int duplexBitOffset = Integer.numberOfTrailingZeros(remainingDuplexModes);
                final int duplexMode = 1 << duplexBitOffset;
                if (duplexMode == oldDuplexMode) {
                    // Update the index of the old selection.
                    oldDuplexModeNewIndex = mDuplexModeSpinnerAdapter.getCount();
                }
                remainingDuplexModes &= ~duplexMode;
                mDuplexModeSpinnerAdapter.add(new SpinnerItem<>(duplexMode,
                        duplexModeLabels[duplexBitOffset]));
            }

            if (oldDuplexModeNewIndex != AdapterView.INVALID_POSITION) {
                // Select the old duplex mode - nothing really changed.
                if (mDuplexModeSpinner.getSelectedItemPosition() != oldDuplexModeNewIndex) {
                    mDuplexModeSpinner.setSelection(oldDuplexModeNewIndex);
                }
            } else {
                // Select the default.
                final int selectedDuplexMode = defaultAttributes.getDuplexMode();
                final int itemCount = mDuplexModeSpinnerAdapter.getCount();
                for (int i = 0; i < itemCount; i++) {
                    SpinnerItem<Integer> item = mDuplexModeSpinnerAdapter.getItem(i);
                    if (selectedDuplexMode == item.value) {
                        if (mDuplexModeSpinner.getSelectedItemPosition() != i) {
                            mDuplexModeSpinner.setSelection(i);
                        }
                        attributes.setDuplexMode(selectedDuplexMode);
                        break;
                    }
                }
            }
        }

        mDuplexModeSpinner.setEnabled(mDuplexModeSpinnerAdapter.getCount() > 1);

        // Orientation
        mOrientationSpinner.setEnabled(true);
        MediaSize mediaSize = attributes.getMediaSize();
        if (mediaSize != null) {
            if (mediaSize.isPortrait()
                    && mOrientationSpinner.getSelectedItemPosition() != 0) {
                mOrientationSpinner.setSelection(0);
            } else if (!mediaSize.isPortrait()
                    && mOrientationSpinner.getSelectedItemPosition() != 1) {
                mOrientationSpinner.setSelection(1);
            }
        }

        // Range options
        PrintDocumentInfo info = mPrintedDocument.getDocumentInfo().info;
        final int pageCount = getAdjustedPageCount(info);
        if (pageCount > 0) {
            if (info != null) {
                if (pageCount == 1) {
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
                                    getSystemService(Context.INPUT_METHOD_SERVICE);
                            imm.showSoftInput(mPageRangeEditText, 0);
                        }
                    } else {
                        mPageRangeEditText.setEnabled(false);
                        mPageRangeEditText.setVisibility(View.INVISIBLE);
                        mPageRangeTitle.setVisibility(View.INVISIBLE);
                    }
                }
            } else {
                if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                    mRangeOptionsSpinner.setSelection(0);
                    mPageRangeEditText.setText("");
                }
                mRangeOptionsSpinner.setEnabled(false);
                mPageRangeEditText.setEnabled(false);
                mPageRangeEditText.setVisibility(View.INVISIBLE);
                mPageRangeTitle.setVisibility(View.INVISIBLE);
            }
        }

        final int newPageCount = getAdjustedPageCount(info);
        if (newPageCount != mCurrentPageCount) {
            mCurrentPageCount = newPageCount;
            updatePageRangeOptions(newPageCount);
        }

        // Advanced print options
        if (mAdvancedPrintOptionsActivity != null) {
            mMoreOptionsButton.setVisibility(View.VISIBLE);
            mMoreOptionsButton.setEnabled(true);
        } else {
            mMoreOptionsButton.setVisibility(View.GONE);
            mMoreOptionsButton.setEnabled(false);
        }

        // Print
        if (mDestinationSpinnerAdapter.getPdfPrinter() != mCurrentPrinter) {
            mPrintButton.setImageResource(com.android.internal.R.drawable.ic_print);
            mPrintButton.setContentDescription(getString(R.string.print_button));
        } else {
            mPrintButton.setImageResource(R.drawable.ic_menu_savetopdf);
            mPrintButton.setContentDescription(getString(R.string.savetopdf_button));
        }
        if (!mPrintedDocument.getDocumentInfo().laidout
                ||(mRangeOptionsSpinner.getSelectedItemPosition() == 1
                && (TextUtils.isEmpty(mPageRangeEditText.getText()) || hasErrors()))
                || (mRangeOptionsSpinner.getSelectedItemPosition() == 0
                && (mPrintedDocument.getDocumentInfo() == null || hasErrors()))) {
            mPrintButton.setVisibility(View.GONE);
        } else {
            mPrintButton.setVisibility(View.VISIBLE);
        }

        // Copies
        if (mDestinationSpinnerAdapter.getPdfPrinter() != mCurrentPrinter) {
            mCopiesEditText.setEnabled(true);
            mCopiesEditText.setFocusableInTouchMode(true);
        } else {
            CharSequence text = mCopiesEditText.getText();
            if (TextUtils.isEmpty(text) || !MIN_COPIES_STRING.equals(text.toString())) {
                mCopiesEditText.setText(MIN_COPIES_STRING);
            }
            mCopiesEditText.setEnabled(false);
            mCopiesEditText.setFocusable(false);
        }
        if (mCopiesEditText.getError() == null
                && TextUtils.isEmpty(mCopiesEditText.getText())) {
            mCopiesEditText.setText(MIN_COPIES_STRING);
            mCopiesEditText.requestFocus();
        }

        if (mShowDestinationPrompt) {
            disableOptionsUi();
        }
    }

    private void updateSummary() {
        if (!mIsOptionsUiBound) {
            return;
        }

        CharSequence copiesText = null;
        CharSequence mediaSizeText = null;

        if (!TextUtils.isEmpty(mCopiesEditText.getText())) {
            copiesText = mCopiesEditText.getText();
            mSummaryCopies.setText(copiesText);
        }

        final int selectedMediaIndex = mMediaSizeSpinner.getSelectedItemPosition();
        if (selectedMediaIndex >= 0) {
            SpinnerItem<MediaSize> mediaItem = mMediaSizeSpinnerAdapter.getItem(selectedMediaIndex);
            mediaSizeText = mediaItem.label;
            mSummaryPaperSize.setText(mediaSizeText);
        }

        if (!TextUtils.isEmpty(copiesText) && !TextUtils.isEmpty(mediaSizeText)) {
            String summaryText = getString(R.string.summary_template, copiesText, mediaSizeText);
            mSummaryContainer.setContentDescription(summaryText);
        }
    }

    private void updatePageRangeOptions(int pageCount) {
        @SuppressWarnings("unchecked")
        ArrayAdapter<SpinnerItem<Integer>> rangeOptionsSpinnerAdapter =
                (ArrayAdapter<SpinnerItem<Integer>>) mRangeOptionsSpinner.getAdapter();
        rangeOptionsSpinnerAdapter.clear();

        final int[] rangeOptionsValues = getResources().getIntArray(
                R.array.page_options_values);

        String pageCountLabel = (pageCount > 0) ? String.valueOf(pageCount) : "";
        String[] rangeOptionsLabels = new String[] {
            getString(R.string.template_all_pages, pageCountLabel),
            getString(R.string.template_page_range, pageCountLabel)
        };

        final int rangeOptionsCount = rangeOptionsLabels.length;
        for (int i = 0; i < rangeOptionsCount; i++) {
            rangeOptionsSpinnerAdapter.add(new SpinnerItem<>(
                    rangeOptionsValues[i], rangeOptionsLabels[i]));
        }
    }

    private PageRange[] computeSelectedPages() {
        if (hasErrors()) {
            return null;
        }

        if (mRangeOptionsSpinner.getSelectedItemPosition() > 0) {
            PrintDocumentInfo info = mPrintedDocument.getDocumentInfo().info;
            final int pageCount = (info != null) ? getAdjustedPageCount(info) : 0;

            return PageRangeUtils.parsePageRanges(mPageRangeEditText.getText(), pageCount);
        }

        return PageRange.ALL_PAGES_ARRAY;
    }

    private int getAdjustedPageCount(PrintDocumentInfo info) {
        if (info != null) {
            final int pageCount = info.getPageCount();
            if (pageCount != PrintDocumentInfo.PAGE_COUNT_UNKNOWN) {
                return pageCount;
            }
        }
        // If the app does not tell us how many pages are in the
        // doc we ask for all pages and use the document page count.
        return mPrintPreviewController.getFilePageCount();
    }

    private boolean hasErrors() {
        return (mCopiesEditText.getError() != null)
                || (mPageRangeEditText.getVisibility() == View.VISIBLE
                && mPageRangeEditText.getError() != null);
    }

    public void onPrinterAvailable(PrinterInfo printer) {
        if (mCurrentPrinter != null && mCurrentPrinter.equals(printer)) {
            setState(STATE_CONFIGURING);
            if (canUpdateDocument()) {
                updateDocument(false);
            }
            ensurePreviewUiShown();
            updateOptionsUi();
        }
    }

    public void onPrinterUnavailable(PrinterInfo printer) {
        if (mCurrentPrinter.getId().equals(printer.getId())) {
            setState(STATE_PRINTER_UNAVAILABLE);
            mPrintedDocument.cancel(false);
            ensureErrorUiShown(getString(R.string.print_error_printer_unavailable),
                    PrintErrorFragment.ACTION_NONE);
            updateOptionsUi();
        }
    }

    private boolean canUpdateDocument() {
        if (mPrintedDocument.isDestroyed()) {
            return false;
        }

        if (hasErrors()) {
            return false;
        }

        PrintAttributes attributes = mPrintJob.getAttributes();

        final int colorMode = attributes.getColorMode();
        if (colorMode != PrintAttributes.COLOR_MODE_COLOR
                && colorMode != PrintAttributes.COLOR_MODE_MONOCHROME) {
            return false;
        }
        if (attributes.getMediaSize() == null) {
            return false;
        }
        if (attributes.getMinMargins() == null) {
            return false;
        }
        if (attributes.getResolution() == null) {
            return false;
        }

        if (mCurrentPrinter == null) {
            return false;
        }
        PrinterCapabilitiesInfo capabilities = mCurrentPrinter.getCapabilities();
        if (capabilities == null) {
            return false;
        }
        if (mCurrentPrinter.getStatus() == PrinterInfo.STATUS_UNAVAILABLE) {
            return false;
        }

        return true;
    }

    private void transformDocumentAndFinish(final Uri writeToUri) {
        // If saving to PDF, apply the attibutes as we are acting as a print service.
        PrintAttributes attributes = mDestinationSpinnerAdapter.getPdfPrinter() == mCurrentPrinter
                ?  mPrintJob.getAttributes() : null;
        new DocumentTransformer(this, mPrintJob, mFileProvider, attributes, new Runnable() {
            @Override
            public void run() {
                if (writeToUri != null) {
                    mPrintedDocument.writeContent(getContentResolver(), writeToUri);
                }
                setState(STATE_PRINT_COMPLETED);
                doFinish();
            }
        }).transform();
    }

    private void doFinish() {
        if (mPrintedDocument != null && mPrintedDocument.isUpdating()) {
            // The printedDocument will call doFinish() when the current command finishes
            return;
        }

        if (mIsFinishing) {
            return;
        }

        mIsFinishing = true;

        if (mPrinterRegistry != null) {
            mPrinterRegistry.setTrackedPrinter(null);
        }

        if (mPrintersObserver != null) {
            mDestinationSpinnerAdapter.unregisterDataSetObserver(mPrintersObserver);
        }

        if (mSpoolerProvider != null) {
            mSpoolerProvider.destroy();
        }

        if (mProgressMessageController != null) {
            setState(mProgressMessageController.cancel());
        }

        if (mState != STATE_INITIALIZING) {
            mPrintedDocument.finish();
            mPrintedDocument.destroy();
            mPrintPreviewController.destroy(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        } else {
            finish();
        }
    }

    private final class SpinnerItem<T> {
        final T value;
        final CharSequence label;

        public SpinnerItem(T value, CharSequence label) {
            this.value = value;
            this.label = label;
        }

        @Override
        public String toString() {
            return label.toString();
        }
    }

    private final class PrinterAvailabilityDetector implements Runnable {
        private static final long UNAVAILABLE_TIMEOUT_MILLIS = 10000; // 10sec

        private boolean mPosted;

        private boolean mPrinterUnavailable;

        private PrinterInfo mPrinter;

        public void updatePrinter(PrinterInfo printer) {
            if (printer.equals(mDestinationSpinnerAdapter.getPdfPrinter())) {
                return;
            }

            final boolean available = printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE
                    && printer.getCapabilities() != null;
            final boolean notifyIfAvailable;

            if (mPrinter == null || !mPrinter.getId().equals(printer.getId())) {
                notifyIfAvailable = true;
                unpostIfNeeded();
                mPrinterUnavailable = false;
                mPrinter = new PrinterInfo.Builder(printer).build();
            } else {
                notifyIfAvailable =
                        (mPrinter.getStatus() == PrinterInfo.STATUS_UNAVAILABLE
                                && printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE)
                                || (mPrinter.getCapabilities() == null
                                && printer.getCapabilities() != null);
                mPrinter = printer;
            }

            if (available) {
                unpostIfNeeded();
                mPrinterUnavailable = false;
                if (notifyIfAvailable) {
                    onPrinterAvailable(mPrinter);
                }
            } else {
                if (!mPrinterUnavailable) {
                    postIfNeeded();
                }
            }
        }

        public void cancel() {
            unpostIfNeeded();
            mPrinterUnavailable = false;
        }

        private void postIfNeeded() {
            if (!mPosted) {
                mPosted = true;
                mDestinationSpinner.postDelayed(this, UNAVAILABLE_TIMEOUT_MILLIS);
            }
        }

        private void unpostIfNeeded() {
            if (mPosted) {
                mPosted = false;
                mDestinationSpinner.removeCallbacks(this);
            }
        }

        @Override
        public void run() {
            mPosted = false;
            mPrinterUnavailable = true;
            onPrinterUnavailable(mPrinter);
        }
    }

    private static final class PrinterHolder {
        PrinterInfo printer;
        boolean removed;

        public PrinterHolder(PrinterInfo printer) {
            this.printer = printer;
        }
    }


    /**
     * Check if the user has ever printed a document
     *
     * @return true iff the user has ever printed a document
     */
    private boolean hasUserEverPrinted() {
        SharedPreferences preferences = getSharedPreferences(HAS_PRINTED_PREF, MODE_PRIVATE);

        return preferences.getBoolean(HAS_PRINTED_PREF, false);
    }

    /**
     * Remember that the user printed a document
     */
    private void setUserPrinted() {
        SharedPreferences preferences = getSharedPreferences(HAS_PRINTED_PREF, MODE_PRIVATE);

        if (!preferences.getBoolean(HAS_PRINTED_PREF, false)) {
            SharedPreferences.Editor edit = preferences.edit();

            edit.putBoolean(HAS_PRINTED_PREF, true);
            edit.apply();
        }
    }

    private final class DestinationAdapter extends BaseAdapter
            implements PrinterRegistry.OnPrintersChangeListener {
        private final List<PrinterHolder> mPrinterHolders = new ArrayList<>();

        private final PrinterHolder mFakePdfPrinterHolder;

        private boolean mHistoricalPrintersLoaded;

        /**
         * Has the {@link #mDestinationSpinner} ever used a view from printer_dropdown_prompt
         */
        private boolean hadPromptView;

        public DestinationAdapter() {
            mHistoricalPrintersLoaded = mPrinterRegistry.areHistoricalPrintersLoaded();
            if (mHistoricalPrintersLoaded) {
                addPrinters(mPrinterHolders, mPrinterRegistry.getPrinters());
            }
            mPrinterRegistry.setOnPrintersChangeListener(this);
            mFakePdfPrinterHolder = new PrinterHolder(createFakePdfPrinter());
        }

        public PrinterInfo getPdfPrinter() {
            return mFakePdfPrinterHolder.printer;
        }

        public int getPrinterIndex(PrinterId printerId) {
            for (int i = 0; i < getCount(); i++) {
                PrinterHolder printerHolder = (PrinterHolder) getItem(i);
                if (printerHolder != null && !printerHolder.removed
                        && printerHolder.printer.getId().equals(printerId)) {
                    return i;
                }
            }
            return AdapterView.INVALID_POSITION;
        }

        public void ensurePrinterInVisibleAdapterPosition(PrinterInfo printer) {
            final int printerCount = mPrinterHolders.size();
            boolean isKnownPrinter = false;
            for (int i = 0; i < printerCount; i++) {
                PrinterHolder printerHolder = mPrinterHolders.get(i);

                if (printerHolder.printer.getId().equals(printer.getId())) {
                    isKnownPrinter = true;

                    // If already in the list - do nothing.
                    if (i < getCount() - 2) {
                        break;
                    }
                    // Else replace the last one (two items are not printers).
                    final int lastPrinterIndex = getCount() - 3;
                    mPrinterHolders.set(i, mPrinterHolders.get(lastPrinterIndex));
                    mPrinterHolders.set(lastPrinterIndex, printerHolder);
                    break;
                }
            }

            if (!isKnownPrinter) {
                PrinterHolder printerHolder = new PrinterHolder(printer);
                printerHolder.removed = true;

                mPrinterHolders.add(Math.max(0, getCount() - 3), printerHolder);
            }

            // Force reload to adjust selection in PrintersObserver.onChanged()
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            if (mHistoricalPrintersLoaded) {
                return Math.min(mPrinterHolders.size() + 2, DEST_ADAPTER_MAX_ITEM_COUNT);
            }
            return 0;
        }

        @Override
        public boolean isEnabled(int position) {
            Object item = getItem(position);
            if (item instanceof PrinterHolder) {
                PrinterHolder printerHolder = (PrinterHolder) item;
                return !printerHolder.removed
                        && printerHolder.printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE;
            }
            return true;
        }

        @Override
        public Object getItem(int position) {
            if (mPrinterHolders.isEmpty()) {
                if (position == 0) {
                    return mFakePdfPrinterHolder;
                }
            } else {
                if (position < 1) {
                    return mPrinterHolders.get(position);
                }
                if (position == 1) {
                    return mFakePdfPrinterHolder;
                }
                if (position < getCount() - 1) {
                    return mPrinterHolders.get(position - 1);
                }
            }
            return null;
        }

        @Override
        public long getItemId(int position) {
            if (mPrinterHolders.isEmpty()) {
                if (position == 0) {
                    return DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF;
                } else if (position == 1) {
                    return DEST_ADAPTER_ITEM_ID_MORE;
                }
            } else {
                if (position == 1) {
                    return DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF;
                }
                if (position == getCount() - 1) {
                    return DEST_ADAPTER_ITEM_ID_MORE;
                }
            }
            return position;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View view = getView(position, convertView, parent);
            view.setEnabled(isEnabled(position));
            return view;
        }

        private String getMoreItemTitle() {
            if (mArePrintServicesEnabled) {
                return getString(R.string.all_printers);
            } else {
                return getString(R.string.print_add_printer);
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (mShowDestinationPrompt) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(
                            R.layout.printer_dropdown_prompt, parent, false);
                    hadPromptView = true;
                }

                return convertView;
            } else {
                // We don't know if we got an recyled printer_dropdown_prompt, hence do not use it
                if (hadPromptView || convertView == null) {
                    convertView = getLayoutInflater().inflate(
                            R.layout.printer_dropdown_item, parent, false);
                }
            }

            CharSequence title = null;
            CharSequence subtitle = null;
            Drawable icon = null;

            if (mPrinterHolders.isEmpty()) {
                if (position == 0 && getPdfPrinter() != null) {
                    PrinterHolder printerHolder = (PrinterHolder) getItem(position);
                    title = printerHolder.printer.getName();
                    icon = getResources().getDrawable(R.drawable.ic_menu_savetopdf, null);
                } else if (position == 1) {
                    title = getMoreItemTitle();
                }
            } else {
                if (position == 1 && getPdfPrinter() != null) {
                    PrinterHolder printerHolder = (PrinterHolder) getItem(position);
                    title = printerHolder.printer.getName();
                    icon = getResources().getDrawable(R.drawable.ic_menu_savetopdf, null);
                } else if (position == getCount() - 1) {
                    title = getMoreItemTitle();
                } else {
                    PrinterHolder printerHolder = (PrinterHolder) getItem(position);
                    PrinterInfo printInfo = printerHolder.printer;

                    title = printInfo.getName();
                    icon = printInfo.loadIcon(PrintActivity.this);
                    subtitle = printInfo.getDescription();
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

            ImageView iconView = (ImageView) convertView.findViewById(R.id.icon);
            if (icon != null) {
                iconView.setVisibility(View.VISIBLE);
                if (!isEnabled(position)) {
                    icon.mutate();

                    TypedValue value = new TypedValue();
                    getTheme().resolveAttribute(android.R.attr.disabledAlpha, value, true);
                    icon.setAlpha((int)(value.getFloat() * 255));
                }
                iconView.setImageDrawable(icon);
            } else {
                iconView.setVisibility(View.INVISIBLE);
            }

            return convertView;
        }

        @Override
        public void onPrintersChanged(List<PrinterInfo> printers) {
            // We rearrange the printers if the user selects a printer
            // not shown in the initial short list. Therefore, we have
            // to keep the printer order.

            // Check if historical printers are loaded as this adapter is open
            // for busyness only if they are. This member is updated here and
            // when the adapter is created because the historical printers may
            // be loaded before or after the adapter is created.
            mHistoricalPrintersLoaded = mPrinterRegistry.areHistoricalPrintersLoaded();

            // No old printers - do not bother keeping their position.
            if (mPrinterHolders.isEmpty()) {
                addPrinters(mPrinterHolders, printers);
                notifyDataSetChanged();
                return;
            }

            // Add the new printers to a map.
            ArrayMap<PrinterId, PrinterInfo> newPrintersMap = new ArrayMap<>();
            final int printerCount = printers.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterInfo printer = printers.get(i);
                newPrintersMap.put(printer.getId(), printer);
            }

            List<PrinterHolder> newPrinterHolders = new ArrayList<>();

            // Update printers we already have which are either updated or removed.
            // We do not remove the currently selected printer.
            final int oldPrinterCount = mPrinterHolders.size();
            for (int i = 0; i < oldPrinterCount; i++) {
                PrinterHolder printerHolder = mPrinterHolders.get(i);
                PrinterId oldPrinterId = printerHolder.printer.getId();
                PrinterInfo updatedPrinter = newPrintersMap.remove(oldPrinterId);

                if (updatedPrinter != null) {
                    printerHolder.printer = updatedPrinter;
                    printerHolder.removed = false;
                    onPrinterAvailable(printerHolder.printer);
                    newPrinterHolders.add(printerHolder);
                } else if (mCurrentPrinter != null && mCurrentPrinter.getId().equals(oldPrinterId)){
                    printerHolder.removed = true;
                    onPrinterUnavailable(printerHolder.printer);
                    newPrinterHolders.add(printerHolder);
                }
            }

            // Add the rest of the new printers, i.e. what is left.
            addPrinters(newPrinterHolders, newPrintersMap.values());

            mPrinterHolders.clear();
            mPrinterHolders.addAll(newPrinterHolders);

            notifyDataSetChanged();
        }

        @Override
        public void onPrintersInvalid() {
            mPrinterHolders.clear();
            notifyDataSetInvalidated();
        }

        public PrinterHolder getPrinterHolder(PrinterId printerId) {
            final int itemCount = getCount();
            for (int i = 0; i < itemCount; i++) {
                Object item = getItem(i);
                if (item instanceof PrinterHolder) {
                    PrinterHolder printerHolder = (PrinterHolder) item;
                    if (printerId.equals(printerHolder.printer.getId())) {
                        return printerHolder;
                    }
                }
            }
            return null;
        }

        /**
         * Remove a printer from the holders if it is marked as removed.
         *
         * @param printerId the id of the printer to remove.
         *
         * @return true iff the printer was removed.
         */
        public boolean pruneRemovedPrinter(PrinterId printerId) {
            final int holderCounts = mPrinterHolders.size();
            for (int i = holderCounts - 1; i >= 0; i--) {
                PrinterHolder printerHolder = mPrinterHolders.get(i);

                if (printerHolder.printer.getId().equals(printerId) && printerHolder.removed) {
                    mPrinterHolders.remove(i);
                    return true;
                }
            }

            return false;
        }

        private void addPrinters(List<PrinterHolder> list, Collection<PrinterInfo> printers) {
            for (PrinterInfo printer : printers) {
                PrinterHolder printerHolder = new PrinterHolder(printer);
                list.add(printerHolder);
            }
        }

        private PrinterInfo createFakePdfPrinter() {
            ArraySet<MediaSize> allMediaSizes = MediaSize.getAllPredefinedSizes();
            MediaSize defaultMediaSize = MediaSizeUtils.getDefault(PrintActivity.this);

            PrinterId printerId = new PrinterId(getComponentName(), "PDF printer");

            PrinterCapabilitiesInfo.Builder builder =
                    new PrinterCapabilitiesInfo.Builder(printerId);

            final int mediaSizeCount = allMediaSizes.size();
            for (int i = 0; i < mediaSizeCount; i++) {
                MediaSize mediaSize = allMediaSizes.valueAt(i);
                builder.addMediaSize(mediaSize, mediaSize.equals(defaultMediaSize));
            }

            builder.addResolution(new Resolution("PDF resolution", "PDF resolution", 300, 300),
                    true);
            builder.setColorModes(PrintAttributes.COLOR_MODE_COLOR
                    | PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_COLOR);

            return new PrinterInfo.Builder(printerId, getString(R.string.save_as_pdf),
                    PrinterInfo.STATUS_IDLE).setCapabilities(builder.build()).build();
        }
    }

    private final class PrintersObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            PrinterInfo oldPrinterState = mCurrentPrinter;
            if (oldPrinterState == null) {
                return;
            }

            PrinterHolder printerHolder = mDestinationSpinnerAdapter.getPrinterHolder(
                    oldPrinterState.getId());
            PrinterInfo newPrinterState = printerHolder.printer;

            if (printerHolder.removed) {
                onPrinterUnavailable(newPrinterState);
            }

            if (mDestinationSpinner.getSelectedItem() != printerHolder) {
                mDestinationSpinner.setSelection(
                        mDestinationSpinnerAdapter.getPrinterIndex(newPrinterState.getId()));
            }

            if (oldPrinterState.equals(newPrinterState)) {
                return;
            }

            PrinterCapabilitiesInfo oldCapab = oldPrinterState.getCapabilities();
            PrinterCapabilitiesInfo newCapab = newPrinterState.getCapabilities();

            final boolean hadCabab = oldCapab != null;
            final boolean hasCapab = newCapab != null;
            final boolean gotCapab = oldCapab == null && newCapab != null;
            final boolean lostCapab = oldCapab != null && newCapab == null;
            final boolean capabChanged = capabilitiesChanged(oldCapab, newCapab);

            final int oldStatus = oldPrinterState.getStatus();
            final int newStatus = newPrinterState.getStatus();

            final boolean isActive = newStatus != PrinterInfo.STATUS_UNAVAILABLE;
            final boolean becameActive = (oldStatus == PrinterInfo.STATUS_UNAVAILABLE
                    && oldStatus != newStatus);
            final boolean becameInactive = (newStatus == PrinterInfo.STATUS_UNAVAILABLE
                    && oldStatus != newStatus);

            mPrinterAvailabilityDetector.updatePrinter(newPrinterState);

            mCurrentPrinter = newPrinterState;

            final boolean updateNeeded = ((capabChanged && hasCapab && isActive)
                    || (becameActive && hasCapab) || (isActive && gotCapab));

            if (capabChanged && hasCapab) {
                updatePrintAttributesFromCapabilities(newCapab);
            }

            if (updateNeeded) {
                updatePrintPreviewController(false);
            }

            if ((isActive && gotCapab) || (becameActive && hasCapab)) {
                onPrinterAvailable(newPrinterState);
            } else if ((becameInactive && hadCabab) || (isActive && lostCapab)) {
                onPrinterUnavailable(newPrinterState);
            }

            if (updateNeeded && canUpdateDocument()) {
                updateDocument(false);
            }

            // Force a reload of the enabled print services to update mAdvancedPrintOptionsActivity
            // in onLoadFinished();
            getLoaderManager().getLoader(LOADER_ID_ENABLED_PRINT_SERVICES).forceLoad();

            updateOptionsUi();
            updateSummary();
        }

        private boolean capabilitiesChanged(PrinterCapabilitiesInfo oldCapabilities,
                PrinterCapabilitiesInfo newCapabilities) {
            if (oldCapabilities == null) {
                if (newCapabilities != null) {
                    return true;
                }
            } else if (!oldCapabilities.equals(newCapabilities)) {
                return true;
            }
            return false;
        }
    }

    private final class MyOnItemSelectedListener implements AdapterView.OnItemSelectedListener {
        @Override
        public void onItemSelected(AdapterView<?> spinner, View view, int position, long id) {
            boolean clearRanges = false;

            if (spinner == mDestinationSpinner) {
                if (position == AdapterView.INVALID_POSITION) {
                    return;
                }

                if (id == DEST_ADAPTER_ITEM_ID_MORE) {
                    startSelectPrinterActivity();
                    return;
                }

                PrinterHolder currentItem = (PrinterHolder) mDestinationSpinner.getSelectedItem();
                PrinterInfo currentPrinter = (currentItem != null) ? currentItem.printer : null;

                // Why on earth item selected is called if no selection changed.
                if (mCurrentPrinter == currentPrinter) {
                    return;
                }

                PrinterId oldId = null;
                if (mCurrentPrinter != null) {
                    oldId = mCurrentPrinter.getId();
                }

                mCurrentPrinter = currentPrinter;

                if (oldId != null) {
                    boolean printerRemoved = mDestinationSpinnerAdapter.pruneRemovedPrinter(oldId);

                    if (printerRemoved) {
                        // Trigger PrinterObserver.onChanged to adjust selection. This will call
                        // this function again.
                        mDestinationSpinnerAdapter.notifyDataSetChanged();
                        return;
                    }
                }

                PrinterHolder printerHolder = mDestinationSpinnerAdapter.getPrinterHolder(
                        currentPrinter.getId());
                if (!printerHolder.removed) {
                    setState(STATE_CONFIGURING);
                    ensurePreviewUiShown();
                }

                mPrintJob.setPrinterId(currentPrinter.getId());
                mPrintJob.setPrinterName(currentPrinter.getName());

                mPrinterRegistry.setTrackedPrinter(currentPrinter.getId());

                PrinterCapabilitiesInfo capabilities = currentPrinter.getCapabilities();
                if (capabilities != null) {
                    updatePrintAttributesFromCapabilities(capabilities);
                }

                mPrinterAvailabilityDetector.updatePrinter(currentPrinter);

                // Force a reload of the enabled print services to update
                // mAdvancedPrintOptionsActivity in onLoadFinished();
                getLoaderManager().getLoader(LOADER_ID_ENABLED_PRINT_SERVICES).forceLoad();
            } else if (spinner == mMediaSizeSpinner) {
                SpinnerItem<MediaSize> mediaItem = mMediaSizeSpinnerAdapter.getItem(position);
                PrintAttributes attributes = mPrintJob.getAttributes();

                MediaSize newMediaSize;
                if (mOrientationSpinner.getSelectedItemPosition() == 0) {
                    newMediaSize = mediaItem.value.asPortrait();
                } else {
                    newMediaSize = mediaItem.value.asLandscape();
                }

                if (newMediaSize != attributes.getMediaSize()) {
                    clearRanges = true;
                    attributes.setMediaSize(newMediaSize);
                }
            } else if (spinner == mColorModeSpinner) {
                SpinnerItem<Integer> colorModeItem = mColorModeSpinnerAdapter.getItem(position);
                mPrintJob.getAttributes().setColorMode(colorModeItem.value);
            } else if (spinner == mDuplexModeSpinner) {
                SpinnerItem<Integer> duplexModeItem = mDuplexModeSpinnerAdapter.getItem(position);
                mPrintJob.getAttributes().setDuplexMode(duplexModeItem.value);
            } else if (spinner == mOrientationSpinner) {
                SpinnerItem<Integer> orientationItem = mOrientationSpinnerAdapter.getItem(position);
                PrintAttributes attributes = mPrintJob.getAttributes();
                if (mMediaSizeSpinner.getSelectedItem() != null) {
                    boolean isPortrait = attributes.isPortrait();

                    if (isPortrait != (orientationItem.value == ORIENTATION_PORTRAIT)) {
                        clearRanges = true;
                        if (orientationItem.value == ORIENTATION_PORTRAIT) {
                            attributes.copyFrom(attributes.asPortrait());
                        } else {
                            attributes.copyFrom(attributes.asLandscape());
                        }
                    }
                }
            } else if (spinner == mRangeOptionsSpinner) {
                if (mRangeOptionsSpinner.getSelectedItemPosition() == 0) {
                    clearRanges = true;
                    mPageRangeEditText.setText("");
                } else if (TextUtils.isEmpty(mPageRangeEditText.getText())) {
                    mPageRangeEditText.setError("");
                }
            }

            if (clearRanges) {
                clearPageRanges();
            }

            updateOptionsUi();

            if (canUpdateDocument()) {
                updateDocument(false);
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            /* do nothing*/
        }
    }

    private final class SelectAllOnFocusListener implements OnFocusChangeListener {
        @Override
        public void onFocusChange(View view, boolean hasFocus) {
            EditText editText = (EditText) view;
            if (!TextUtils.isEmpty(editText.getText())) {
                editText.setSelection(editText.getText().length());
            }

            if (view == mPageRangeEditText && !hasFocus && mPageRangeEditText.getError() == null) {
                updateSelectedPagesFromTextField();
            }
        }
    }

    private final class RangeTextWatcher implements TextWatcher {
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
            final boolean hadErrors = hasErrors();

            PrintDocumentInfo info = mPrintedDocument.getDocumentInfo().info;
            final int pageCount = (info != null) ? getAdjustedPageCount(info) : 0;
            PageRange[] ranges = PageRangeUtils.parsePageRanges(editable, pageCount);

            if (ranges.length == 0) {
                if (mPageRangeEditText.getError() == null) {
                    mPageRangeEditText.setError("");
                    updateOptionsUi();
                }
                return;
            }

            if (mPageRangeEditText.getError() != null) {
                mPageRangeEditText.setError(null);
                updateOptionsUi();
            }

            if (hadErrors && canUpdateDocument()) {
                updateDocument(false);
            }
        }
    }

    private final class EditTextWatcher implements TextWatcher {
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
            final boolean hadErrors = hasErrors();

            if (editable.length() == 0) {
                if (mCopiesEditText.getError() == null) {
                    mCopiesEditText.setError("");
                    updateOptionsUi();
                }
                return;
            }

            int copies = 0;
            try {
                copies = Integer.parseInt(editable.toString());
            } catch (NumberFormatException nfe) {
                /* ignore */
            }

            if (copies < MIN_COPIES) {
                if (mCopiesEditText.getError() == null) {
                    mCopiesEditText.setError("");
                    updateOptionsUi();
                }
                return;
            }

            mPrintJob.setCopies(copies);

            if (mCopiesEditText.getError() != null) {
                mCopiesEditText.setError(null);
                updateOptionsUi();
            }

            if (hadErrors && canUpdateDocument()) {
                updateDocument(false);
            }
        }
    }

    private final class ProgressMessageController implements Runnable {
        private static final long PROGRESS_TIMEOUT_MILLIS = 1000;

        private final Handler mHandler;

        private boolean mPosted;

        /** State before run was executed */
        private int mPreviousState = -1;

        public ProgressMessageController(Context context) {
            mHandler = new Handler(context.getMainLooper(), null, false);
        }

        public void post() {
            if (mState == STATE_UPDATE_SLOW) {
                setState(STATE_UPDATE_SLOW);
                ensureProgressUiShown();
                updateOptionsUi();

                return;
            } else if (mPosted) {
                return;
            }
            mPreviousState = -1;
            mPosted = true;
            mHandler.postDelayed(this, PROGRESS_TIMEOUT_MILLIS);
        }

        private int getStateAfterCancel() {
            if (mPreviousState == -1) {
                return mState;
            } else {
                return mPreviousState;
            }
        }

        public int cancel() {
            int state;

            if (!mPosted) {
                state = getStateAfterCancel();
            } else {
                mPosted = false;
                mHandler.removeCallbacks(this);

                state = getStateAfterCancel();
            }

            mPreviousState = -1;

            return state;
        }

        @Override
        public void run() {
            mPosted = false;
            mPreviousState = mState;
            setState(STATE_UPDATE_SLOW);
            ensureProgressUiShown();
            updateOptionsUi();
        }
    }

    private static final class DocumentTransformer implements ServiceConnection {
        private static final String TEMP_FILE_PREFIX = "print_job";
        private static final String TEMP_FILE_EXTENSION = ".pdf";

        private final Context mContext;

        private final MutexFileProvider mFileProvider;

        private final PrintJobInfo mPrintJob;

        private final PageRange[] mPagesToShred;

        private final PrintAttributes mAttributesToApply;

        private final Runnable mCallback;

        public DocumentTransformer(Context context, PrintJobInfo printJob,
                MutexFileProvider fileProvider, PrintAttributes attributes,
                Runnable callback) {
            mContext = context;
            mPrintJob = printJob;
            mFileProvider = fileProvider;
            mCallback = callback;
            mPagesToShred = computePagesToShred(mPrintJob);
            mAttributesToApply = attributes;
        }

        public void transform() {
            // If we have only the pages we want, done.
            if (mPagesToShred.length <= 0 && mAttributesToApply == null) {
                mCallback.run();
                return;
            }

            // Bind to the manipulation service and the work
            // will be performed upon connection to the service.
            Intent intent = new Intent(PdfManipulationService.ACTION_GET_EDITOR);
            intent.setClass(mContext, PdfManipulationService.class);
            mContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            final IPdfEditor editor = IPdfEditor.Stub.asInterface(service);
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    // It's OK to access the data members as they are
                    // final and this code is the last one to touch
                    // them as shredding is the very last step, so the
                    // UI is not interactive at this point.
                    doTransform(editor);
                    updatePrintJob();
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                    mContext.unbindService(DocumentTransformer.this);
                    mCallback.run();
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            /* do nothing */
        }

        private void doTransform(IPdfEditor editor) {
            File tempFile = null;
            ParcelFileDescriptor src = null;
            ParcelFileDescriptor dst = null;
            InputStream in = null;
            OutputStream out = null;
            try {
                File jobFile = mFileProvider.acquireFile(null);
                src = ParcelFileDescriptor.open(jobFile, ParcelFileDescriptor.MODE_READ_WRITE);

                // Open the document.
                editor.openDocument(src);

                // We passed the fd over IPC, close this one.
                src.close();

                // Drop the pages.
                editor.removePages(mPagesToShred);

                // Apply print attributes if needed.
                if (mAttributesToApply != null) {
                    editor.applyPrintAttributes(mAttributesToApply);
                }

                // Write the modified PDF to a temp file.
                tempFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_EXTENSION,
                        mContext.getCacheDir());
                dst = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE);
                editor.write(dst);
                dst.close();

                // Close the document.
                editor.closeDocument();

                // Copy the temp file over the print job file.
                jobFile.delete();
                in = new FileInputStream(tempFile);
                out = new FileOutputStream(jobFile);
                Streams.copy(in, out);
            } catch (IOException|RemoteException e) {
                Log.e(LOG_TAG, "Error dropping pages", e);
            } finally {
                IoUtils.closeQuietly(src);
                IoUtils.closeQuietly(dst);
                IoUtils.closeQuietly(in);
                IoUtils.closeQuietly(out);
                if (tempFile != null) {
                    tempFile.delete();
                }
                mFileProvider.releaseFile();
            }
        }

        private void updatePrintJob() {
            // Update the print job pages.
            final int newPageCount = PageRangeUtils.getNormalizedPageCount(
                    mPrintJob.getPages(), 0);
            mPrintJob.setPages(new PageRange[]{PageRange.ALL_PAGES});

            // Update the print job document info.
            PrintDocumentInfo oldDocInfo = mPrintJob.getDocumentInfo();
            PrintDocumentInfo newDocInfo = new PrintDocumentInfo
                    .Builder(oldDocInfo.getName())
                    .setContentType(oldDocInfo.getContentType())
                    .setPageCount(newPageCount)
                    .build();
            mPrintJob.setDocumentInfo(newDocInfo);
        }

        private static PageRange[] computePagesToShred(PrintJobInfo printJob) {
            List<PageRange> rangesToShred = new ArrayList<>();
            PageRange previousRange = null;

            PageRange[] printedPages = printJob.getPages();
            final int rangeCount = printedPages.length;
            for (int i = 0; i < rangeCount; i++) {
                PageRange range = printedPages[i];

                if (previousRange == null) {
                    final int startPageIdx = 0;
                    final int endPageIdx = range.getStart() - 1;
                    if (startPageIdx <= endPageIdx) {
                        PageRange removedRange = new PageRange(startPageIdx, endPageIdx);
                        rangesToShred.add(removedRange);
                    }
                } else {
                    final int startPageIdx = previousRange.getEnd() + 1;
                    final int endPageIdx = range.getStart() - 1;
                    if (startPageIdx <= endPageIdx) {
                        PageRange removedRange = new PageRange(startPageIdx, endPageIdx);
                        rangesToShred.add(removedRange);
                    }
                }

                if (i == rangeCount - 1) {
                    if (range.getEnd() != Integer.MAX_VALUE) {
                        rangesToShred.add(new PageRange(range.getEnd() + 1, Integer.MAX_VALUE));
                    }
                }

                previousRange = range;
            }

            PageRange[] result = new PageRange[rangesToShred.size()];
            rangesToShred.toArray(result);
            return result;
        }
    }
}
