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
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.print.IPrintDocumentAdapter;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintDocumentInfo;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintService;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.text.TextWatcher;
import android.util.ArrayMap;
import android.util.Log;
import android.view.KeyEvent;
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

import com.android.printspooler.R;
import com.android.printspooler.model.PrintSpoolerProvider;
import com.android.printspooler.model.PrintSpoolerService;
import com.android.printspooler.model.RemotePrintDocument;
import com.android.printspooler.util.MediaSizeUtils;
import com.android.printspooler.util.MediaSizeUtils.MediaSizeComparator;
import com.android.printspooler.util.PageRangeUtils;
import com.android.printspooler.util.PrintOptionUtils;
import com.android.printspooler.widget.ContentView;
import com.android.printspooler.widget.ContentView.OptionsStateChangeListener;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PrintActivity extends Activity implements RemotePrintDocument.UpdateResultCallbacks,
        PrintErrorFragment.OnActionListener, PrintProgressFragment.OnCancelRequestListener {
    private static final String LOG_TAG = "PrintActivity";

    public static final String INTENT_EXTRA_PRINTER_ID = "INTENT_EXTRA_PRINTER_ID";

    private static final int ORIENTATION_PORTRAIT = 0;
    private static final int ORIENTATION_LANDSCAPE = 1;

    private static final int ACTIVITY_REQUEST_CREATE_FILE = 1;
    private static final int ACTIVITY_REQUEST_SELECT_PRINTER = 2;
    private static final int ACTIVITY_REQUEST_POPULATE_ADVANCED_PRINT_OPTIONS = 3;

    private static final int DEST_ADAPTER_MAX_ITEM_COUNT = 9;

    private static final int DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF = Integer.MAX_VALUE;
    private static final int DEST_ADAPTER_ITEM_ID_ALL_PRINTERS = Integer.MAX_VALUE - 1;

    private static final int STATE_CONFIGURING = 0;
    private static final int STATE_PRINT_CONFIRMED = 1;
    private static final int STATE_PRINT_CANCELED = 2;
    private static final int STATE_UPDATE_FAILED = 3;
    private static final int STATE_CREATE_FILE_FAILED = 4;
    private static final int STATE_PRINTER_UNAVAILABLE = 5;

    private static final int UI_STATE_PREVIEW = 0;
    private static final int UI_STATE_ERROR = 1;
    private static final int UI_STATE_PROGRESS = 2;

    private static final int MIN_COPIES = 1;
    private static final String MIN_COPIES_STRING = String.valueOf(MIN_COPIES);

    private static final Pattern PATTERN_DIGITS = Pattern.compile("[\\d]+");

    private static final Pattern PATTERN_ESCAPE_SPECIAL_CHARS = Pattern.compile(
            "(?=[]\\[+&|!(){}^\"~*?:\\\\])");

    private static final Pattern PATTERN_PAGE_RANGE = Pattern.compile(
            "[\\s]*[0-9]*[\\s]*[\\-]?[\\s]*[0-9]*[\\s]*?(([,])"
            + "[\\s]*[0-9]*[\\s]*[\\-]?[\\s]*[0-9]*[\\s]*|[\\s]*)+");

    public static final PageRange[] ALL_PAGES_ARRAY = new PageRange[] {PageRange.ALL_PAGES};

    private final PrinterAvailabilityDetector mPrinterAvailabilityDetector =
            new PrinterAvailabilityDetector();

    private final SimpleStringSplitter mStringCommaSplitter = new SimpleStringSplitter(',');

    private final OnFocusChangeListener mSelectAllOnFocusListener = new SelectAllOnFocusListener();

    private PrintSpoolerProvider mSpoolerProvider;

    private PrintJobInfo mPrintJob;
    private RemotePrintDocument mPrintedDocument;
    private PrinterRegistry mPrinterRegistry;

    private EditText mCopiesEditText;

    private TextView mPageRangeOptionsTitle;
    private TextView mPageRangeTitle;
    private EditText mPageRangeEditText;

    private Spinner mDestinationSpinner;
    private DestinationAdapter mDestinationSpinnerAdapter;

    private Spinner mMediaSizeSpinner;
    private ArrayAdapter<SpinnerItem<MediaSize>> mMediaSizeSpinnerAdapter;

    private Spinner mColorModeSpinner;
    private ArrayAdapter<SpinnerItem<Integer>> mColorModeSpinnerAdapter;

    private Spinner mOrientationSpinner;
    private ArrayAdapter<SpinnerItem<Integer>> mOrientationSpinnerAdapter;

    private Spinner mRangeOptionsSpinner;

    private ContentView mOptionsContent;

    private TextView mSummaryCopies;
    private TextView mSummaryPaperSize;

    private View mAdvancedPrintOptionsContainer;

    private Button mMoreOptionsButton;

    private ImageView mPrintButton;

    private ProgressMessageController mProgressMessageController;

    private MediaSizeComparator mMediaSizeComparator;

    private PageRange[] mRequestedPages;

    private PrinterInfo mOldCurrentPrinter;

    private String mCallingPackageName;

    private int mState = STATE_CONFIGURING;

    private int mUiState = UI_STATE_PREVIEW;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();

        mPrintJob = extras.getParcelable(PrintManager.EXTRA_PRINT_JOB);
        if (mPrintJob == null) {
            throw new IllegalArgumentException(PrintManager.EXTRA_PRINT_JOB
                    + " cannot be null");
        }
        mPrintJob.setAttributes(new PrintAttributes.Builder().build());

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
                // Now that we are bound to the print spooler service,
                // create the printer registry and wait for it to get
                // the first batch of results which will be delivered
                // after reading historical data. This should be pretty
                // fast, so just wait before showing the UI.
                mPrinterRegistry = new PrinterRegistry(PrintActivity.this,
                        new Runnable() {
                    @Override
                    public void run() {
                        setTitle(R.string.print_dialog);
                        setContentView(R.layout.print_activity);

                        mPrintedDocument = new RemotePrintDocument(PrintActivity.this,
                                IPrintDocumentAdapter.Stub.asInterface(adapter),
                                PrintSpoolerService.generateFileForPrintJob(PrintActivity.this,
                                        mPrintJob.getId()),
                                new RemotePrintDocument.DocumentObserver() {
                                    @Override
                                    public void onDestroy() {
                                        finish();
                                    }
                                }, PrintActivity.this);

                        mProgressMessageController = new ProgressMessageController(PrintActivity.this);

                        mMediaSizeComparator = new MediaSizeComparator(PrintActivity.this);

                        mDestinationSpinnerAdapter = new DestinationAdapter();

                        bindUi();

                        updateOptionsUi();

                        // Now show the updated UI to avoid flicker.
                        mOptionsContent.setVisibility(View.VISIBLE);

                        mRequestedPages = computeRequestedPages();

                        mPrintedDocument.start();

                        ensurePreviewUiShown();
                    }
                });
            }
        });
    }

    @Override
    public void onPause() {
        if (isFinishing()) {
            PrintSpoolerService spooler = mSpoolerProvider.getSpooler();
            if (mState == STATE_PRINT_CONFIRMED) {
                spooler.updatePrintJobUserConfigurableOptionsNoPersistence(mPrintJob);
                spooler.setPrintJobState(mPrintJob.getId(), PrintJobInfo.STATE_QUEUED, null);
            } else {
                spooler.setPrintJobState(mPrintJob.getId(), PrintJobInfo.STATE_CANCELED, null);
            }
            mProgressMessageController.cancel();
            mPrinterRegistry.setTrackedPrinter(null);
            mSpoolerProvider.destroy();
            mPrintedDocument.finish();
            mPrintedDocument.destroy();
        }

        mPrinterAvailabilityDetector.cancel();

        super.onPause();
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
        if (keyCode == KeyEvent.KEYCODE_BACK
                && event.isTracking() && !event.isCanceled()) {
            cancelPrint();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onActionPerformed() {
        switch (mState) {
            case STATE_UPDATE_FAILED: {
                if (canUpdateDocument()) {
                    updateDocument(true, true);
                    ensurePreviewUiShown();
                    mState = STATE_CONFIGURING;
                    updateOptionsUi();
                }
            } break;

            case STATE_CREATE_FILE_FAILED: {
                mState = STATE_CONFIGURING;
                ensurePreviewUiShown();
                updateOptionsUi();
            } break;
        }
    }

    @Override
    public void onCancelRequest() {
        if (mPrintedDocument.isUpdating()) {
            mPrintedDocument.cancel();
        }
    }

    public void onUpdateCanceled() {
        mProgressMessageController.cancel();
        ensurePreviewUiShown();
        finishIfConfirmedOrCanceled();
        updateOptionsUi();
    }

    @Override
    public void onUpdateCompleted(RemotePrintDocument.RemotePrintDocumentInfo document) {
        mProgressMessageController.cancel();
        ensurePreviewUiShown();

        // Update the print job with the info for the written document. The page
        // count we get from the remote document is the pages in the document from
        // the app perspective but the print job should contain the page count from
        // print service perspective which is the pages in the written PDF not the
        // pages in the printed document.
        PrintDocumentInfo info = document.info;
        if (info == null) {
            return;
        }
        final int pageCount = PageRangeUtils.getNormalizedPageCount(document.writtenPages,
                info.getPageCount());
        PrintDocumentInfo adjustedInfo = new PrintDocumentInfo.Builder(info.getName())
                .setContentType(info.getContentType())
                .setPageCount(pageCount)
                .build();
        mPrintJob.setDocumentInfo(adjustedInfo);
        mPrintJob.setPages(document.printedPages);
        finishIfConfirmedOrCanceled();
        updateOptionsUi();
    }

    @Override
    public void onUpdateFailed(CharSequence error) {
        mState = STATE_UPDATE_FAILED;
        ensureErrorUiShown(error, PrintErrorFragment.ACTION_RETRY);
        updateOptionsUi();
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
        PrintDocumentInfo info = mPrintedDocument.getDocumentInfo().info;
        if (info == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, info.getName());
        intent.putExtra(DocumentsContract.EXTRA_PACKAGE_NAME, mCallingPackageName);
        startActivityForResult(intent, ACTIVITY_REQUEST_CREATE_FILE);
    }

    private void onStartCreateDocumentActivityResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            mPrintedDocument.writeContent(getContentResolver(), uri);
            finish();
        } else if (resultCode == RESULT_CANCELED) {
            mState = STATE_CONFIGURING;
            updateOptionsUi();
        } else {
            ensureErrorUiShown(getString(R.string.print_write_error_message),
                    PrintErrorFragment.ACTION_CONFIRM);
            mState = STATE_CREATE_FILE_FAILED;
            updateOptionsUi();
        }
    }

    private void startSelectPrinterActivity() {
        Intent intent = new Intent(this, SelectPrinterActivity.class);
        startActivityForResult(intent, ACTIVITY_REQUEST_SELECT_PRINTER);
    }

    private void onSelectPrinterActivityResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null) {
            PrinterId printerId = data.getParcelableExtra(INTENT_EXTRA_PRINTER_ID);
            if (printerId != null) {
                mDestinationSpinnerAdapter.ensurePrinterInVisibleAdapterPosition(printerId);
                final int index = mDestinationSpinnerAdapter.getPrinterIndex(printerId);
                if (index != AdapterView.INVALID_POSITION) {
                    mDestinationSpinner.setSelection(index);
                    return;
                }
            }
        }

        PrinterId printerId = mOldCurrentPrinter.getId();
        final int index = mDestinationSpinnerAdapter.getPrinterIndex(printerId);
        mDestinationSpinner.setSelection(index);
    }

    private void startAdvancedPrintOptionsActivity(PrinterInfo printer) {
        ComponentName serviceName = printer.getId().getServiceName();

        String activityName = PrintOptionUtils.getAdvancedOptionsActivityName(this, serviceName);
        if (TextUtils.isEmpty(activityName)) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(serviceName.getPackageName(), activityName));

        List<ResolveInfo> resolvedActivities = getPackageManager()
                .queryIntentActivities(intent, 0);
        if (resolvedActivities.isEmpty()) {
            return;
        }

        // The activity is a component name, therefore it is one or none.
        if (resolvedActivities.get(0).activityInfo.exported) {
            intent.putExtra(PrintService.EXTRA_PRINT_JOB_INFO, mPrintJob);
            intent.putExtra(PrintService.EXTRA_PRINTER_INFO, printer);

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

        // Take copies without interpretation as the advanced print dialog
        // cannot create a print job info with invalid copies.
        mCopiesEditText.setText(String.valueOf(printJobInfo.getCopies()));
        mPrintJob.setCopies(printJobInfo.getCopies());

        PrintAttributes currAttributes = mPrintJob.getAttributes();
        PrintAttributes newAttributes = printJobInfo.getAttributes();

        // Take the media size only if the current printer supports is.
        MediaSize oldMediaSize = currAttributes.getMediaSize();
        MediaSize newMediaSize = newAttributes.getMediaSize();
        if (!oldMediaSize.equals(newMediaSize)) {
            final int mediaSizeCount = mMediaSizeSpinnerAdapter.getCount();
            MediaSize newMediaSizePortrait = newAttributes.getMediaSize().asPortrait();
            for (int i = 0; i < mediaSizeCount; i++) {
                MediaSize supportedSizePortrait = mMediaSizeSpinnerAdapter.getItem(i).value.asPortrait();
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

        // Take the page range only if it is valid.
        PageRange[] pageRanges = printJobInfo.getPages();
        if (pageRanges != null && pageRanges.length > 0) {
            pageRanges = PageRangeUtils.normalize(pageRanges);

            PrintDocumentInfo info = mPrintedDocument.getDocumentInfo().info;
            final int pageCount = (info != null) ? info.getPageCount() : 0;

            // Handle the case where all pages are specified explicitly
            // instead of the *all pages* constant.
            if (pageRanges.length == 1) {
                if (pageRanges[0].getStart() == 0 && pageRanges[0].getEnd() == pageCount - 1) {
                    pageRanges[0] = PageRange.ALL_PAGES;
                }
            }

            if (Arrays.equals(pageRanges, ALL_PAGES_ARRAY)) {
                mPrintJob.setPages(pageRanges);

                if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                    mRangeOptionsSpinner.setSelection(0);
                }
            } else if (pageRanges[0].getStart() >= 0
                    && pageRanges[pageRanges.length - 1].getEnd() < pageCount) {
                mPrintJob.setPages(pageRanges);

                if (mRangeOptionsSpinner.getSelectedItemPosition() != 1) {
                    mRangeOptionsSpinner.setSelection(1);
                }

                StringBuilder builder = new StringBuilder();
                final int pageRangeCount = pageRanges.length;
                for (int i = 0; i < pageRangeCount; i++) {
                    if (builder.length() > 0) {
                         builder.append(',');
                    }

                    final int shownStartPage;
                    final int shownEndPage;
                    PageRange pageRange = pageRanges[i];
                    if (pageRange.equals(PageRange.ALL_PAGES)) {
                        shownStartPage = 1;
                        shownEndPage = pageCount;
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

        // Update the content if needed.
        if (canUpdateDocument()) {
            updateDocument(true, false);
        }
    }

    private void ensureProgressUiShown() {
        if (mUiState != UI_STATE_PROGRESS) {
            mUiState = UI_STATE_PROGRESS;
            Fragment fragment = PrintProgressFragment.newInstance();
            showFragment(fragment);
        }
    }

    private void ensurePreviewUiShown() {
        if (mUiState != UI_STATE_PREVIEW) {
            mUiState = UI_STATE_PREVIEW;
            Fragment fragment = PrintPreviewFragment.newInstance();
            showFragment(fragment);
        }
    }

    private void ensureErrorUiShown(CharSequence message, int action) {
        if (mUiState != UI_STATE_ERROR) {
            mUiState = UI_STATE_ERROR;
            Fragment fragment = PrintErrorFragment.newInstance(message, action);
            showFragment(fragment);
        }
    }

    private void showFragment(Fragment fragment) {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        Fragment oldFragment = getFragmentManager().findFragmentById(
                R.id.embedded_content_container);
        if (oldFragment != null) {
            transaction.remove(oldFragment);
        }
        transaction.add(R.id.embedded_content_container, fragment);
        transaction.commit();
    }

    private void requestCreatePdfFileOrFinish() {
        if (getCurrentPrinter() == mDestinationSpinnerAdapter.getPdfPrinter()) {
            startCreateDocumentActivity();
        } else {
            finish();
        }
    }

    private void finishIfConfirmedOrCanceled() {
        if (mState == STATE_PRINT_CONFIRMED) {
            requestCreatePdfFileOrFinish();
        } else if (mState == STATE_PRINT_CANCELED) {
            finish();
        }
    }

    private void updatePrintAttributesFromCapabilities(PrinterCapabilitiesInfo capabilities) {
        PrintAttributes defaults = capabilities.getDefaults();

        // Sort the media sizes based on the current locale.
        List<MediaSize> sortedMediaSizes = new ArrayList<>(capabilities.getMediaSizes());
        Collections.sort(sortedMediaSizes, mMediaSizeComparator);

        PrintAttributes attributes = mPrintJob.getAttributes();

        // Media size.
        MediaSize currMediaSize = attributes.getMediaSize();
        if (currMediaSize == null) {
            attributes.setMediaSize(defaults.getMediaSize());
        } else {
            boolean foundCurrentMediaSize = false;
            // Try to find the current media size in the capabilities as
            // it may be in a different orientation.
            MediaSize currMediaSizePortrait = currMediaSize.asPortrait();
            final int mediaSizeCount = sortedMediaSizes.size();
            for (int i = 0; i < mediaSizeCount; i++) {
                MediaSize mediaSize = sortedMediaSizes.get(i);
                if (currMediaSizePortrait.equals(mediaSize.asPortrait())) {
                    attributes.setMediaSize(currMediaSize);
                    foundCurrentMediaSize = true;
                    break;
                }
            }
            // If we did not find the current media size fall back to default.
            if (!foundCurrentMediaSize) {
                attributes.setMediaSize(defaults.getMediaSize());
            }
        }

        // Color mode.
        final int colorMode = attributes.getColorMode();
        if ((capabilities.getColorModes() & colorMode) == 0) {
            attributes.setColorMode(defaults.getColorMode());
        }

        // Resolution
        Resolution resolution = attributes.getResolution();
        if (resolution == null || !capabilities.getResolutions().contains(resolution)) {
            attributes.setResolution(defaults.getResolution());
        }

        // Margins.
        attributes.setMinMargins(defaults.getMinMargins());
    }

    private boolean updateDocument(boolean preview, boolean clearLastError) {
        if (!clearLastError && mPrintedDocument.hasUpdateError()) {
            return false;
        }

        if (clearLastError && mPrintedDocument.hasUpdateError()) {
            mPrintedDocument.clearUpdateError();
        }

        if (mRequestedPages != null && mRequestedPages.length > 0) {
            final PageRange[] pages;
            if (preview) {
                final int firstPage = mRequestedPages[0].getStart();
                pages = new PageRange[]{new PageRange(firstPage, firstPage)};
            } else {
                pages = mRequestedPages;
            }
            final boolean willUpdate = mPrintedDocument.update(mPrintJob.getAttributes(),
                    pages, preview);

            if (willUpdate) {
                mProgressMessageController.post();
                return true;
            }
        }

        return false;
    }

    private void addCurrentPrinterToHistory() {
        PrinterInfo currentPrinter = getCurrentPrinter();
        if (currentPrinter != null) {
            PrinterId fakePdfPrinterId = mDestinationSpinnerAdapter.getPdfPrinter().getId();
            if (!currentPrinter.getId().equals(fakePdfPrinterId)) {
                mPrinterRegistry.addHistoricalPrinter(currentPrinter);
            }
        }
    }

    private PrinterInfo getCurrentPrinter() {
        return ((PrinterHolder) mDestinationSpinner.getSelectedItem()).printer;
    }

    private void cancelPrint() {
        mState = STATE_PRINT_CANCELED;
        updateOptionsUi();
        if (mPrintedDocument.isUpdating()) {
            mPrintedDocument.cancel();
        }
        finish();
    }

    private void confirmPrint() {
        mState = STATE_PRINT_CONFIRMED;
        updateOptionsUi();
        if (canUpdateDocument()) {
            updateDocument(false, false);
        }
        addCurrentPrinterToHistory();
        if (!mPrintedDocument.isUpdating()) {
            requestCreatePdfFileOrFinish();
        }
    }

    private void bindUi() {
        // Summary
        mSummaryCopies = (TextView) findViewById(R.id.copies_count_summary);
        mSummaryPaperSize = (TextView) findViewById(R.id.paper_size_summary);

        // Options container
        mOptionsContent = (ContentView) findViewById(R.id.options_content);
        mOptionsContent.setOptionsStateChangeListener(new OptionsStateChangeListener() {
            @Override
            public void onOptionsOpened() {
                // TODO: Update preview.
            }

            @Override
            public void onOptionsClosed() {
                // TODO: Update preview.
            }
        });

        OnItemSelectedListener itemSelectedListener = new MyOnItemSelectedListener();
        OnClickListener clickListener = new MyClickListener();

        // Copies
        mCopiesEditText = (EditText) findViewById(R.id.copies_edittext);
        mCopiesEditText.setOnFocusChangeListener(mSelectAllOnFocusListener);
        mCopiesEditText.setText(MIN_COPIES_STRING);
        mCopiesEditText.setSelection(mCopiesEditText.getText().length());
        mCopiesEditText.addTextChangedListener(new EditTextWatcher());

        // Destination.
        mDestinationSpinnerAdapter.registerDataSetObserver(new PrintersObserver());
        mDestinationSpinner = (Spinner) findViewById(R.id.destination_spinner);
        mDestinationSpinner.setAdapter(mDestinationSpinnerAdapter);
        mDestinationSpinner.setOnItemSelectedListener(itemSelectedListener);
        mDestinationSpinner.setSelection(0);

        // Media size.
        mMediaSizeSpinnerAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_dropdown_item, R.id.title);
        mMediaSizeSpinner = (Spinner) findViewById(R.id.paper_size_spinner);
        mMediaSizeSpinner.setAdapter(mMediaSizeSpinnerAdapter);
        mMediaSizeSpinner.setOnItemSelectedListener(itemSelectedListener);

        // Color mode.
        mColorModeSpinnerAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_dropdown_item, R.id.title);
        mColorModeSpinner = (Spinner) findViewById(R.id.color_spinner);
        mColorModeSpinner.setAdapter(mColorModeSpinnerAdapter);
        mColorModeSpinner.setOnItemSelectedListener(itemSelectedListener);

        // Orientation
        mOrientationSpinnerAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_dropdown_item, R.id.title);
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
        ArrayAdapter<SpinnerItem<Integer>> rangeOptionsSpinnerAdapter =
                new ArrayAdapter<>(this, R.layout.spinner_dropdown_item, R.id.title);
        final int[] rangeOptionsValues = getResources().getIntArray(
                R.array.page_options_values);
        String[] rangeOptionsLabels = getResources().getStringArray(
                R.array.page_options_labels);
        final int rangeOptionsCount = rangeOptionsLabels.length;
        for (int i = 0; i < rangeOptionsCount; i++) {
            rangeOptionsSpinnerAdapter.add(new SpinnerItem<>(
                    rangeOptionsValues[i], rangeOptionsLabels[i]));
        }
        mPageRangeOptionsTitle = (TextView) findViewById(R.id.range_options_title);
        mRangeOptionsSpinner = (Spinner) findViewById(R.id.range_options_spinner);
        mRangeOptionsSpinner.setAdapter(rangeOptionsSpinnerAdapter);
        mRangeOptionsSpinner.setOnItemSelectedListener(itemSelectedListener);

        // Page range
        mPageRangeTitle = (TextView) findViewById(R.id.page_range_title);
        mPageRangeEditText = (EditText) findViewById(R.id.page_range_edittext);
        mPageRangeEditText.setOnFocusChangeListener(mSelectAllOnFocusListener);
        mPageRangeEditText.addTextChangedListener(new RangeTextWatcher());

        // Advanced options button.
        mAdvancedPrintOptionsContainer = findViewById(R.id.more_options_container);
        mMoreOptionsButton = (Button) findViewById(R.id.more_options_button);
        mMoreOptionsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PrinterInfo currentPrinter = getCurrentPrinter();
                if (currentPrinter != null) {
                    startAdvancedPrintOptionsActivity(currentPrinter);
                }
            }
        });

        // Print button
        mPrintButton = (ImageView) findViewById(R.id.print_button);
        mPrintButton.setOnClickListener(clickListener);
    }

    private final class MyClickListener implements OnClickListener {
        @Override
        public void onClick(View view) {
            if (view == mPrintButton) {
                PrinterInfo currentPrinter = getCurrentPrinter();
                if (currentPrinter != null) {
                    confirmPrint();
                } else {
                    cancelPrint();
                }
            } else if (view == mMoreOptionsButton) {
                PrinterInfo currentPrinter = getCurrentPrinter();
                if (currentPrinter != null) {
                    startAdvancedPrintOptionsActivity(currentPrinter);
                }
            }
        }
    }

    private static boolean canPrint(PrinterInfo printer) {
        return printer.getCapabilities() != null
                && printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE;
    }

    private void updateOptionsUi() {
        // Always update the summary.
        if (!TextUtils.isEmpty(mCopiesEditText.getText())) {
            mSummaryCopies.setText(mCopiesEditText.getText());
        }

        final int selectedMediaIndex = mMediaSizeSpinner.getSelectedItemPosition();
        if (selectedMediaIndex >= 0) {
            SpinnerItem<MediaSize> mediaItem = mMediaSizeSpinnerAdapter.getItem(selectedMediaIndex);
            mSummaryPaperSize.setText(mediaItem.label);
        }

        if (mState == STATE_PRINT_CONFIRMED
                || mState == STATE_PRINT_CANCELED
                || mState == STATE_UPDATE_FAILED
                || mState == STATE_CREATE_FILE_FAILED
                || mState == STATE_PRINTER_UNAVAILABLE) {
            if (mState != STATE_PRINTER_UNAVAILABLE) {
                mDestinationSpinner.setEnabled(false);
            }
            mCopiesEditText.setEnabled(false);
            mMediaSizeSpinner.setEnabled(false);
            mColorModeSpinner.setEnabled(false);
            mOrientationSpinner.setEnabled(false);
            mRangeOptionsSpinner.setEnabled(false);
            mPageRangeEditText.setEnabled(false);
            mPrintButton.setEnabled(false);
            mMoreOptionsButton.setEnabled(false);
            return;
        }

        // If no current printer, or it has no capabilities, or it is not
        // available, we disable all print options except the destination.
        PrinterInfo currentPrinter =  getCurrentPrinter();
        if (currentPrinter == null || !canPrint(currentPrinter)) {
            mCopiesEditText.setEnabled(false);
            mMediaSizeSpinner.setEnabled(false);
            mColorModeSpinner.setEnabled(false);
            mOrientationSpinner.setEnabled(false);
            mRangeOptionsSpinner.setEnabled(false);
            mPageRangeEditText.setEnabled(false);
            mPrintButton.setEnabled(false);
            mMoreOptionsButton.setEnabled(false);
            return;
        }

        PrinterCapabilitiesInfo capabilities = currentPrinter.getCapabilities();
        PrintAttributes defaultAttributes = capabilities.getDefaults();

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
                    oldColorModeNewIndex = colorBitOffset;
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
                    }
                }
            }
        }

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
        if (info != null && info.getPageCount() > 0) {
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
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.showSoftInput(mPageRangeEditText, 0);
                    }
                } else {
                    mPageRangeEditText.setEnabled(false);
                    mPageRangeEditText.setVisibility(View.INVISIBLE);
                    mPageRangeTitle.setVisibility(View.INVISIBLE);
                }
            }
            String title = (info.getPageCount() != PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    ? getString(R.string.label_pages, String.valueOf(info.getPageCount()))
                    : getString(R.string.page_count_unknown);
            mPageRangeOptionsTitle.setText(title);
        } else {
            if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                mRangeOptionsSpinner.setSelection(0);
            }
            mRangeOptionsSpinner.setEnabled(false);
            mPageRangeOptionsTitle.setText(getString(R.string.page_count_unknown));
            mPageRangeEditText.setEnabled(false);
            mPageRangeEditText.setVisibility(View.INVISIBLE);
            mPageRangeTitle.setVisibility(View.INVISIBLE);
        }

        // Advanced print options
        ComponentName serviceName = currentPrinter.getId().getServiceName();
        if (!TextUtils.isEmpty(PrintOptionUtils.getAdvancedOptionsActivityName(
                this, serviceName))) {
            mAdvancedPrintOptionsContainer.setVisibility(View.VISIBLE);
            mMoreOptionsButton.setEnabled(true);
        } else {
            mAdvancedPrintOptionsContainer.setVisibility(View.GONE);
            mMoreOptionsButton.setEnabled(false);
        }

        // Print
        if (mDestinationSpinnerAdapter.getPdfPrinter() != currentPrinter) {
            mPrintButton.setImageResource(com.android.internal.R.drawable.ic_print);
        } else {
            mPrintButton.setImageResource(com.android.internal.R.drawable.ic_menu_save);
        }
        if ((mRangeOptionsSpinner.getSelectedItemPosition() == 1
                && (TextUtils.isEmpty(mPageRangeEditText.getText()) || hasErrors()))
            || (mRangeOptionsSpinner.getSelectedItemPosition() == 0
                && (mPrintedDocument.getDocumentInfo() == null || hasErrors()))) {
            mPrintButton.setEnabled(false);
        } else {
            mPrintButton.setEnabled(true);
        }

        // Copies
        if (mDestinationSpinnerAdapter.getPdfPrinter() != currentPrinter) {
            mCopiesEditText.setEnabled(true);
        } else {
            mCopiesEditText.setEnabled(false);
        }
        if (mCopiesEditText.getError() == null
                && TextUtils.isEmpty(mCopiesEditText.getText())) {
            mCopiesEditText.setText(String.valueOf(MIN_COPIES));
            mCopiesEditText.requestFocus();
        }
    }

    private PageRange[] computeRequestedPages() {
        if (hasErrors()) {
            return null;
        }

        if (mRangeOptionsSpinner.getSelectedItemPosition() > 0) {
            List<PageRange> pageRanges = new ArrayList<>();
            mStringCommaSplitter.setString(mPageRangeEditText.getText().toString());

            while (mStringCommaSplitter.hasNext()) {
                String range = mStringCommaSplitter.next().trim();
                if (TextUtils.isEmpty(range)) {
                    continue;
                }
                final int dashIndex = range.indexOf('-');
                final int fromIndex;
                final int toIndex;

                if (dashIndex > 0) {
                    fromIndex = Integer.parseInt(range.substring(0, dashIndex).trim()) - 1;
                    // It is possible that the dash is at the end since the input
                    // verification can has to allow the user to keep entering if
                    // this would lead to a valid input. So we handle this.
                    if (dashIndex < range.length() - 1) {
                        String fromString = range.substring(dashIndex + 1, range.length()).trim();
                        toIndex = Integer.parseInt(fromString) - 1;
                    } else {
                        toIndex = fromIndex;
                    }
                } else {
                    fromIndex = toIndex = Integer.parseInt(range) - 1;
                }

                PageRange pageRange = new PageRange(Math.min(fromIndex, toIndex),
                        Math.max(fromIndex, toIndex));
                pageRanges.add(pageRange);
            }

            PageRange[] pageRangesArray = new PageRange[pageRanges.size()];
            pageRanges.toArray(pageRangesArray);

            return PageRangeUtils.normalize(pageRangesArray);
        }

        return ALL_PAGES_ARRAY;
    }

    private boolean hasErrors() {
        return (mCopiesEditText.getError() != null)
                || (mPageRangeEditText.getVisibility() == View.VISIBLE
                    && mPageRangeEditText.getError() != null);
    }

    public void onPrinterAvailable(PrinterInfo printer) {
        PrinterInfo currentPrinter = getCurrentPrinter();
        if (currentPrinter.equals(printer)) {
            mState = STATE_CONFIGURING;
            if (canUpdateDocument()) {
                updateDocument(true, false);
            }
            ensurePreviewUiShown();
            updateOptionsUi();
        }
    }

    public void onPrinterUnavailable(PrinterInfo printer) {
        if (getCurrentPrinter().getId().equals(printer.getId())) {
            mState = STATE_PRINTER_UNAVAILABLE;
            if (mPrintedDocument.isUpdating()) {
                mPrintedDocument.cancel();
            }
            ensureErrorUiShown(getString(R.string.print_error_printer_unavailable),
                    PrintErrorFragment.ACTION_NONE);
            updateOptionsUi();
        }
    }

    private final class SpinnerItem<T> {
        final T value;
        final CharSequence label;

        public SpinnerItem(T value, CharSequence label) {
            this.value = value;
            this.label = label;
        }

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
                mPrinter.copyFrom(printer);
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

    private final class DestinationAdapter extends BaseAdapter
            implements PrinterRegistry.OnPrintersChangeListener {
        private final List<PrinterHolder> mPrinterHolders = new ArrayList<>();

        private final PrinterHolder mFakePdfPrinterHolder;

        public DestinationAdapter() {
            addPrinters(mPrinterHolders, mPrinterRegistry.getPrinters());
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

        public void ensurePrinterInVisibleAdapterPosition(PrinterId printerId) {
            final int printerCount = mPrinterHolders.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterHolder printerHolder = mPrinterHolders.get(i);
                if (printerHolder.printer.getId().equals(printerId)) {
                    // If already in the list - do nothing.
                    if (i < getCount() - 2) {
                        return;
                    }
                    // Else replace the last one (two items are not printers).
                    final int lastPrinterIndex = getCount() - 3;
                    mPrinterHolders.set(i, mPrinterHolders.get(lastPrinterIndex));
                    mPrinterHolders.set(lastPrinterIndex, printerHolder);
                    notifyDataSetChanged();
                    return;
                }
            }
        }

        @Override
        public int getCount() {
            return Math.min(mPrinterHolders.size() + 2, DEST_ADAPTER_MAX_ITEM_COUNT);
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
                    return DEST_ADAPTER_ITEM_ID_ALL_PRINTERS;
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
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View view = getView(position, convertView, parent);
            view.setEnabled(isEnabled(position));
            return view;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(
                        R.layout.printer_dropdown_item, parent, false);
            }

            CharSequence title = null;
            CharSequence subtitle = null;
            Drawable icon = null;

            if (mPrinterHolders.isEmpty()) {
                if (position == 0 && getPdfPrinter() != null) {
                    PrinterHolder printerHolder = (PrinterHolder) getItem(position);
                    title = printerHolder.printer.getName();
                    icon = getResources().getDrawable(com.android.internal.R.drawable.ic_menu_save);
                } else if (position == 1) {
                    title = getString(R.string.all_printers);
                }
            } else {
                if (position == 1 && getPdfPrinter() != null) {
                    PrinterHolder printerHolder = (PrinterHolder) getItem(position);
                    title = printerHolder.printer.getName();
                    icon = getResources().getDrawable(com.android.internal.R.drawable.ic_menu_save);
                } else if (position == getCount() - 1) {
                    title = getString(R.string.all_printers);
                } else {
                    PrinterHolder printerHolder = (PrinterHolder) getItem(position);
                    title = printerHolder.printer.getName();
                    try {
                        PackageInfo packageInfo = getPackageManager().getPackageInfo(
                                printerHolder.printer.getId().getServiceName().getPackageName(), 0);
                        subtitle = packageInfo.applicationInfo.loadLabel(getPackageManager());
                        icon = packageInfo.applicationInfo.loadIcon(getPackageManager());
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

            ImageView iconView = (ImageView) convertView.findViewById(R.id.icon);
            if (icon != null) {
                iconView.setImageDrawable(icon);
                iconView.setVisibility(View.VISIBLE);
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
            // We do not remove printers if the currently selected printer is removed
            // to prevent the user printing to a wrong printer.
            final int oldPrinterCount = mPrinterHolders.size();
            for (int i = 0; i < oldPrinterCount; i++) {
                PrinterHolder printerHolder = mPrinterHolders.get(i);
                PrinterId oldPrinterId = printerHolder.printer.getId();
                PrinterInfo updatedPrinter = newPrintersMap.remove(oldPrinterId);
                if (updatedPrinter != null) {
                    printerHolder.printer = updatedPrinter;
                } else {
                    printerHolder.removed = true;
                }
                newPrinterHolders.add(printerHolder);
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

        public void pruneRemovedPrinters() {
            final int holderCounts = mPrinterHolders.size();
            for (int i = holderCounts - 1; i >= 0; i--) {
                PrinterHolder printerHolder = mPrinterHolders.get(i);
                if (printerHolder.removed) {
                    mPrinterHolders.remove(i);
                }
            }
        }

        private void addPrinters(List<PrinterHolder> list, Collection<PrinterInfo> printers) {
            for (PrinterInfo printer : printers) {
                PrinterHolder printerHolder = new PrinterHolder(printer);
                list.add(printerHolder);
            }
        }

        private PrinterInfo createFakePdfPrinter() {
            MediaSize defaultMediaSize = MediaSizeUtils.getDefault(PrintActivity.this);

            PrinterId printerId = new PrinterId(getComponentName(), "PDF printer");

            PrinterCapabilitiesInfo.Builder builder =
                    new PrinterCapabilitiesInfo.Builder(printerId);

            String[] mediaSizeIds = getResources().getStringArray(R.array.pdf_printer_media_sizes);
            final int mediaSizeIdCount = mediaSizeIds.length;
            for (int i = 0; i < mediaSizeIdCount; i++) {
                String id = mediaSizeIds[i];
                MediaSize mediaSize = MediaSize.getStandardMediaSizeById(id);
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
            PrinterInfo oldPrinterState = mOldCurrentPrinter;
            if (oldPrinterState == null) {
                return;
            }

            PrinterHolder printerHolder = mDestinationSpinnerAdapter.getPrinterHolder(
                    oldPrinterState.getId());
            if (printerHolder == null) {
                return;
            }
            PrinterInfo newPrinterState = printerHolder.printer;

            if (!printerHolder.removed) {
                mDestinationSpinnerAdapter.pruneRemovedPrinters();
            } else {
                onPrinterUnavailable(newPrinterState);
            }

            if (oldPrinterState.equals(newPrinterState)) {
                return;
            }

            PrinterCapabilitiesInfo oldCapab = oldPrinterState.getCapabilities();
            PrinterCapabilitiesInfo newCapab = newPrinterState.getCapabilities();

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

            oldPrinterState.copyFrom(newPrinterState);

            if ((isActive && gotCapab) || (becameActive && hasCapab)) {
                onPrinterAvailable(newPrinterState);
            } else if ((becameInactive && hasCapab)|| (isActive && lostCapab)) {
                onPrinterUnavailable(newPrinterState);
            }

            if (hasCapab && capabChanged) {
                updatePrintAttributesFromCapabilities(newCapab);
            }

            final boolean updateNeeded = ((capabChanged && hasCapab && isActive)
                    || (becameActive && hasCapab) || (isActive && gotCapab));

            if (updateNeeded && canUpdateDocument()) {
                updateDocument(true, false);
            }

            updateOptionsUi();
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
            if (spinner == mDestinationSpinner) {
                if (position == AdapterView.INVALID_POSITION) {
                    return;
                }

                if (id == DEST_ADAPTER_ITEM_ID_ALL_PRINTERS) {
                    startSelectPrinterActivity();
                    return;
                }

                PrinterInfo currentPrinter = getCurrentPrinter();

                // Why on earth item selected is called if no selection changed.
                if (mOldCurrentPrinter == currentPrinter) {
                    return;
                }
                mOldCurrentPrinter = currentPrinter;

                PrinterHolder printerHolder = mDestinationSpinnerAdapter.getPrinterHolder(
                        currentPrinter.getId());
                if (!printerHolder.removed) {
                    mDestinationSpinnerAdapter.pruneRemovedPrinters();
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
            } else if (spinner == mMediaSizeSpinner) {
                SpinnerItem<MediaSize> mediaItem = mMediaSizeSpinnerAdapter.getItem(position);
                if (mOrientationSpinner.getSelectedItemPosition() == 0) {
                    mPrintJob.getAttributes().setMediaSize(mediaItem.value.asPortrait());
                } else {
                    mPrintJob.getAttributes().setMediaSize(mediaItem.value.asLandscape());
                }
            } else if (spinner == mColorModeSpinner) {
                SpinnerItem<Integer> colorModeItem = mColorModeSpinnerAdapter.getItem(position);
                mPrintJob.getAttributes().setColorMode(colorModeItem.value);
            } else if (spinner == mOrientationSpinner) {
                SpinnerItem<Integer> orientationItem = mOrientationSpinnerAdapter.getItem(position);
                PrintAttributes attributes = mPrintJob.getAttributes();
                if (mMediaSizeSpinner.getSelectedItem() != null) {
                    if (orientationItem.value == ORIENTATION_PORTRAIT) {
                        attributes.copyFrom(attributes.asPortrait());
                    } else {
                        attributes.copyFrom(attributes.asLandscape());
                    }
                }
            }

            if (canUpdateDocument()) {
                updateDocument(true, false);
            }

            updateOptionsUi();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            /* do nothing*/
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

        PrinterInfo currentPrinter = getCurrentPrinter();
        if (currentPrinter == null) {
            return false;
        }
        PrinterCapabilitiesInfo capabilities = currentPrinter.getCapabilities();
        if (capabilities == null) {
            return false;
        }
        if (currentPrinter.getStatus() == PrinterInfo.STATUS_UNAVAILABLE) {
            return false;
        }

        return true;
    }

    private final class SelectAllOnFocusListener implements OnFocusChangeListener {
        @Override
        public void onFocusChange(View view, boolean hasFocus) {
            EditText editText = (EditText) view;
            if (!TextUtils.isEmpty(editText.getText())) {
                editText.setSelection(editText.getText().length());
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

            String text = editable.toString();

            if (TextUtils.isEmpty(text)) {
                mPageRangeEditText.setError("");
                updateOptionsUi();
                return;
            }

            String escapedText = PATTERN_ESCAPE_SPECIAL_CHARS.matcher(text).replaceAll("////");
            if (!PATTERN_PAGE_RANGE.matcher(escapedText).matches()) {
                mPageRangeEditText.setError("");
                updateOptionsUi();
                return;
            }

            PrintDocumentInfo info = mPrintedDocument.getDocumentInfo().info;
            final int pageCount = (info != null) ? info.getPageCount() : 0;

            // The range
            Matcher matcher = PATTERN_DIGITS.matcher(text);
            while (matcher.find()) {
                String numericString = text.substring(matcher.start(), matcher.end()).trim();
                if (TextUtils.isEmpty(numericString)) {
                    continue;
                }
                final int pageIndex = Integer.parseInt(numericString);
                if (pageIndex < 1 || pageIndex > pageCount) {
                    mPageRangeEditText.setError("");
                    updateOptionsUi();
                    return;
                }
            }

            // We intentionally do not catch the case of the from page being
            // greater than the to page. When computing the requested pages
            // we just swap them if necessary.

            // Keep the print job up to date with the selected pages if we
            // know how many pages are there in the document.
            mRequestedPages = computeRequestedPages();

            mPageRangeEditText.setError(null);
            mPrintButton.setEnabled(true);
            updateOptionsUi();

            if (hadErrors && !hasErrors()) {
                updateOptionsUi();
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
                mCopiesEditText.setError("");
                updateOptionsUi();
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
                updateOptionsUi();
                return;
            }

            mPrintJob.setCopies(copies);

            mCopiesEditText.setError(null);

            updateOptionsUi();

            if (hadErrors && canUpdateDocument()) {
                updateDocument(true, false);
            }
        }
    }

    private final class ProgressMessageController implements Runnable {
        private static final long PROGRESS_TIMEOUT_MILLIS = 1000;

        private final Handler mHandler;

        private boolean mPosted;

        public ProgressMessageController(Context context) {
            mHandler = new Handler(context.getMainLooper(), null, false);
        }

        public void post() {
            if (mPosted) {
                return;
            }
            mPosted = true;
            mHandler.postDelayed(this, PROGRESS_TIMEOUT_MILLIS);
        }

        public void cancel() {
            if (!mPosted) {
                return;
            }
            mPosted = false;
            mHandler.removeCallbacks(this);
        }

        @Override
        public void run() {
            ensureProgressUiShown();
        }
    }
}
