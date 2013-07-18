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
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.print.IPrintDocumentAdapter;
import android.print.IPrinterDiscoveryObserver;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintDocumentAdapter.LayoutResultCallback;
import android.print.PrintDocumentAdapter.WriteResultCallback;
import android.print.PrintDocumentInfo;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Activity for configuring a print job.
 */
public class PrintJobConfigActivity extends Activity {

    private static final boolean DEBUG = false;

    private static final String LOG_TAG = PrintJobConfigActivity.class.getSimpleName();

    public static final String EXTRA_PRINTABLE = "printable";
    public static final String EXTRA_APP_ID = "appId";
    public static final String EXTRA_ATTRIBUTES = "attributes";
    public static final String EXTRA_PRINT_JOB_ID = "printJobId";

    private static final int MIN_COPIES = 1;

    private static final Pattern PATTERN_DIGITS = Pattern.compile("\\d");

    private static final Pattern PATTERN_ESCAPE_SPECIAL_CHARS = Pattern.compile(
            "(?=[]\\[+&|!(){}^\"~*?:\\\\])");

    private static final Pattern PATTERN_PAGE_RANGE = Pattern.compile(
            "([0-9]+[\\s]*[\\-]?[\\s]*[0-9]*[\\s]*[,]?[\\s]*)+");

    private final PrintSpooler mPrintSpooler = PrintSpooler.getInstance(this);

    private IPrinterDiscoveryObserver mPrinterDiscoveryObserver;

    private int mAppId;
    private int mPrintJobId;

    private PrintAttributes mPrintAttributes;

    private RemotePrintDocumentAdapter mRemotePrintAdapter;

    private boolean mPrintConfirmed;

    private boolean mStarted;

    private IBinder mIPrintDocumentAdapter;

    private PrintDocumentInfo mPrintDocumentInfo;

    // UI elements

    private EditText mCopiesEditText;

    private EditText mRangeEditText;

    private Spinner mDestinationSpinner;
    public ArrayAdapter<SpinnerItem<PrinterInfo>> mDestinationSpinnerAdapter;

    private Spinner mMediaSizeSpinner;
    public ArrayAdapter<SpinnerItem<MediaSize>> mMediaSizeSpinnerAdapter;

    private Spinner mColorModeSpinner;
    public ArrayAdapter<SpinnerItem<Integer>> mColorModeSpinnerAdapter;

    private Spinner mOrientationSpinner;
    public ArrayAdapter<SpinnerItem<Integer>> mOrientationSpinnerAdapter;

    private Spinner mRangeOptionsSpinner;
    public ArrayAdapter<SpinnerItem<Integer>> mRangeOptionsSpinnerAdapter;

    private Button mPrintButton;

    // TODO: Implement store/restore state.

    private final OnItemSelectedListener mOnItemSelectedListener =
            new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> spinner, View view, int position, long id) {
            if (spinner == mDestinationSpinner) {
                updateUi();
                notifyPrintableStartIfNeeded();
            } else if (spinner == mMediaSizeSpinner) {
                SpinnerItem<MediaSize> mediaItem = mMediaSizeSpinnerAdapter.getItem(position);
                mPrintAttributes.setMediaSize(mediaItem.value);
                updatePrintableContentIfNeeded();
            } else if (spinner == mColorModeSpinner) {
                SpinnerItem<Integer> colorModeItem =
                        mColorModeSpinnerAdapter.getItem(position);
                mPrintAttributes.setColorMode(colorModeItem.value);
            } else if (spinner == mOrientationSpinner) {
                SpinnerItem<Integer> orientationItem =
                        mOrientationSpinnerAdapter.getItem(position);
                mPrintAttributes.setOrientation(orientationItem.value);
            } else if (spinner == mRangeOptionsSpinner) {
                SpinnerItem<Integer> rangeOptionItem =
                        mRangeOptionsSpinnerAdapter.getItem(position);
                if (rangeOptionItem.value == getResources().getInteger(
                        R.integer.page_option_value_all)) {
                    mRangeEditText.setVisibility(View.INVISIBLE);
                    mRangeEditText.setEnabled(false);
                    mRangeEditText.setText(null);
                    mRangeEditText.setError(null);
                    mPrintButton.setEnabled(true);
                } else if (rangeOptionItem.value ==  getResources().getInteger(
                        R.integer.page_option_value_page_range)) {
                    mRangeEditText.setVisibility(View.VISIBLE);
                    mRangeEditText.setEnabled(true);
                    mRangeEditText.requestFocus();
                    mRangeEditText.setError(getString(R.string.invalid_input));
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
                    imm.showSoftInput(mRangeEditText, 0);
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
            if (editable.length() == 0) {
                mCopiesEditText.setError(getString(R.string.invalid_input));
                mPrintButton.setEnabled(false);
                return;
            }
            final int copies = Integer.parseInt(editable.toString());
            if (copies < MIN_COPIES) {
                mCopiesEditText.setError(getString(R.string.invalid_input));
                mPrintButton.setEnabled(false);
                return;
            }
            mPrintAttributes.setCopies(copies);
            mPrintButton.setEnabled(true);
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
            String text = editable.toString();

            if (TextUtils.isEmpty(text)) {
                mRangeEditText.setError(getString(R.string.invalid_input));
                mPrintButton.setEnabled(false);
                return;
            }

            String escapedText = PATTERN_ESCAPE_SPECIAL_CHARS.matcher(text).replaceAll("////");
            if (!PATTERN_PAGE_RANGE.matcher(escapedText).matches()) {
                mRangeEditText.setError(getString(R.string.invalid_input));
                mPrintButton.setEnabled(false);
                return;
            }

            Matcher matcher = PATTERN_DIGITS.matcher(text);
            while (matcher.find()) {
                String numericString = text.substring(matcher.start(), matcher.end());
                final int pageIndex = Integer.parseInt(numericString);
                if (pageIndex < 1 || pageIndex > mPrintDocumentInfo.getPageCount()) {
                    mRangeEditText.setError(getString(R.string.invalid_input));
                    mPrintButton.setEnabled(false);
                    return;
                }
            }

            mRangeEditText.setError(null);
            mPrintButton.setEnabled(true);
        }
    };

    private final DeathRecipient mDeathRecipient = new DeathRecipient() {
        @Override
        public void binderDied() {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.print_job_config_activity);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        Bundle extras = getIntent().getExtras();

        mPrintJobId = extras.getInt(EXTRA_PRINT_JOB_ID, -1);
        if (mPrintJobId < 0) {
            throw new IllegalArgumentException("Invalid print job id: " + mPrintJobId);
        }

        mAppId = extras.getInt(EXTRA_APP_ID, -1);
        if (mAppId < 0) {
            throw new IllegalArgumentException("Invalid app id: " + mAppId);
        }

        mPrintAttributes = getIntent().getParcelableExtra(EXTRA_ATTRIBUTES);
        if (mPrintAttributes == null) {
            mPrintAttributes = new PrintAttributes.Builder().create();
        }

        mIPrintDocumentAdapter = extras.getBinder(EXTRA_PRINTABLE);
        if (mIPrintDocumentAdapter == null) {
            throw new IllegalArgumentException("Printable cannot be null");
        }
        mRemotePrintAdapter = new RemotePrintDocumentAdapter(
                IPrintDocumentAdapter.Stub.asInterface(mIPrintDocumentAdapter),
                mPrintSpooler.generateFileForPrintJob(mPrintJobId));

        try {
            mIPrintDocumentAdapter.linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException re) {
            finish();
        }

        mPrinterDiscoveryObserver = new PrintDiscoveryObserver(getMainLooper());

        bindUi();
    }

    @Override
    protected void onDestroy() {
        mIPrintDocumentAdapter.unlinkToDeath(mDeathRecipient, 0);
        super.onDestroy();
    }

    private void bindUi() {
        // Copies
        mCopiesEditText = (EditText) findViewById(R.id.copies_edittext);
        mCopiesEditText.setText(String.valueOf(MIN_COPIES));
        mCopiesEditText.addTextChangedListener(mCopiesTextWatcher);

        // Destination.
        mDestinationSpinner = (Spinner) findViewById(R.id.destination_spinner);
        mDestinationSpinnerAdapter = new ArrayAdapter<SpinnerItem<PrinterInfo>>(this,
                R.layout.spinner_dropdown_item) {
                    @Override
                    public View getDropDownView(int position, View convertView, ViewGroup parent) {
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
                        title.setText(printerInfo.getLabel());

                        try {
                            TextView subtitle = (TextView) convertView.findViewById(R.id.subtitle);
                            PackageManager pm = getPackageManager();
                            PackageInfo packageInfo = pm.getPackageInfo(
                                    printerInfo.getId().getService().getPackageName(), 0);
                            subtitle.setText(packageInfo.applicationInfo.loadLabel(pm));
                            subtitle.setVisibility(View.VISIBLE);
                        } catch (NameNotFoundException nnfe) {
                            /* ignore */
                        }

                        return convertView;
                    }
        };
        mDestinationSpinner.setAdapter(mDestinationSpinnerAdapter);
        mDestinationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Media size.
        mMediaSizeSpinner = (Spinner) findViewById(R.id.paper_size_spinner);
        mMediaSizeSpinnerAdapter = new ArrayAdapter<SpinnerItem<MediaSize>>(this,
                R.layout.spinner_dropdown_item, R.id.title);
        mMediaSizeSpinner.setAdapter(mMediaSizeSpinnerAdapter);
        mMediaSizeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Color mode.
        mColorModeSpinner = (Spinner) findViewById(R.id.color_spinner);
        mColorModeSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(this,
                R.layout.spinner_dropdown_item, R.id.title);
        mColorModeSpinner.setAdapter(mColorModeSpinnerAdapter);
        mColorModeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Orientation
        mOrientationSpinner = (Spinner) findViewById(R.id.orientation_spinner);
        mOrientationSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(this,
                R.layout.spinner_dropdown_item, R.id.title);
        mOrientationSpinner.setAdapter(mOrientationSpinnerAdapter);
        mOrientationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Range
        mRangeEditText = (EditText) findViewById(R.id.page_range_edittext);
        mRangeEditText.addTextChangedListener(mRangeTextWatcher);

        // Range options
        mRangeOptionsSpinner = (Spinner) findViewById(R.id.range_options_spinner);
        mRangeOptionsSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(this,
                R.layout.spinner_dropdown_item, R.id.title);
        mRangeOptionsSpinner.setAdapter(mRangeOptionsSpinnerAdapter);
        mRangeOptionsSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
        final int[] rangeOptionsValues = getResources().getIntArray(
                R.array.page_options_values);
        String[] rangeOptionsLabels = getResources().getStringArray(
                R.array.page_options_labels);
        final int rangeOptionsCount = rangeOptionsLabels.length;
        for (int i = 0; i < rangeOptionsCount; i++) {
            mRangeOptionsSpinnerAdapter.add(new SpinnerItem<Integer>(
                    rangeOptionsValues[i], rangeOptionsLabels[i]));
        }
        mRangeOptionsSpinner.setSelection(0);

        mPrintButton = (Button) findViewById(R.id.print_button);
        mPrintButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mPrintConfirmed = true;
                finish();
            }
        });
    }

    private void updateUi() {
        final int selectedIndex = mDestinationSpinner.getSelectedItemPosition();
        PrinterInfo printer = mDestinationSpinnerAdapter.getItem(selectedIndex).value;
        printer.getDefaults(mPrintAttributes);

        // Copies.
        mCopiesEditText.setText(String.valueOf(
                Math.max(mPrintAttributes.getCopies(), MIN_COPIES)));
        mCopiesEditText.selectAll();

        // Media size.
        mMediaSizeSpinnerAdapter.clear();
        List<MediaSize> mediaSizes = printer.getMediaSizes();
        final int mediaSizeCount = mediaSizes.size();
        for (int i = 0; i < mediaSizeCount; i++) {
            MediaSize mediaSize = mediaSizes.get(i);
            mMediaSizeSpinnerAdapter.add(new SpinnerItem<MediaSize>(
                    mediaSize, mediaSize.getLabel()));
        }
        final int selectedMediaSizeIndex = mediaSizes.indexOf(
                mPrintAttributes.getMediaSize());
        mMediaSizeSpinner.setOnItemSelectedListener(null);
        mMediaSizeSpinner.setSelection(selectedMediaSizeIndex);

        // Color mode.
        final int colorModes = printer.getColorModes();
        mColorModeSpinnerAdapter.clear();
        String[] colorModeLabels = getResources().getStringArray(
                R.array.color_mode_labels);
        int remainingColorModes = colorModes;
        while (remainingColorModes != 0) {
            final int colorBitOffset = Integer.numberOfTrailingZeros(remainingColorModes);
            final int colorMode = 1 << colorBitOffset;
            remainingColorModes &= ~colorMode;
            mColorModeSpinnerAdapter.add(new SpinnerItem<Integer>(colorMode,
                    colorModeLabels[colorBitOffset]));
        }
        final int selectedColorModeIndex = Integer.numberOfTrailingZeros(
                (colorModes & mPrintAttributes.getColorMode()));
        mColorModeSpinner.setSelection(selectedColorModeIndex);

        // Orientation.
        final int orientations = printer.getOrientations();
        mOrientationSpinnerAdapter.clear();
        String[] orientationLabels = getResources().getStringArray(
                R.array.orientation_labels);
        int remainingOrientations = orientations;
        while (remainingOrientations != 0) {
            final int orientationBitOffset = Integer.numberOfTrailingZeros(remainingOrientations);
            final int orientation = 1 << orientationBitOffset;
            remainingOrientations &= ~orientation;
            mOrientationSpinnerAdapter.add(new SpinnerItem<Integer>(orientation,
                    orientationLabels[orientationBitOffset]));
        }
        final int selectedOrientationIndex = Integer.numberOfTrailingZeros(
                (orientations & mPrintAttributes.getOrientation()));
        mOrientationSpinner.setSelection(selectedOrientationIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPrintSpooler.startPrinterDiscovery(mPrinterDiscoveryObserver);
        notifyPrintableStartIfNeeded();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPrintSpooler.stopPrinterDiscovery();
        notifyPrintableFinishIfNeeded();
    }

    private void notifyPrintableStartIfNeeded() {
        if (mDestinationSpinner.getSelectedItemPosition() < 0
                || mStarted) {
            return;
        }
        mStarted = true;
        mRemotePrintAdapter.start();
        updatePrintableContentIfNeeded();
    }

    private void updatePrintableContentIfNeeded() {
        if (!mStarted) {
            return;
        }

        // TODO: Implement old attributes tracking
        mPrintSpooler.setPrintJobAttributes(mPrintJobId, mPrintAttributes);

        // TODO: Implement setting the print preview attribute
        mRemotePrintAdapter.layout(new PrintAttributes.Builder().create(),
                mPrintAttributes, new LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                mPrintDocumentInfo = info;

                // TODO: Handle the case of unchanged content
                mPrintSpooler.setPrintJobPrintDocumentInfo(mPrintJobId, info);

                // TODO: Implement page selector.
                final List<PageRange> pages = new ArrayList<PageRange>();
                pages.add(PageRange.ALL_PAGES);

                mRemotePrintAdapter.write(pages, new WriteResultCallback() {
                    @Override
                    public void onWriteFinished(List<PageRange> pages) {
                        updatePrintPreview(mRemotePrintAdapter.getFile());
                    }

                    @Override
                    public void onWriteFailed(CharSequence error) {
                        Log.e(LOG_TAG, "Error write layout: " + error);
                        finishActivity(Activity.RESULT_CANCELED);
                    }
                });
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                Log.e(LOG_TAG, "Error during layout: " + error);
                finishActivity(Activity.RESULT_CANCELED);
            }
        }, new Bundle());
    }

    private void notifyPrintableFinishIfNeeded() {
        if (!mStarted) {
            return;
        }

        mRemotePrintAdapter.finish(!mPrintConfirmed);

        // If canceled or no printer, nothing to do.
        final int selectedIndex = mDestinationSpinner.getSelectedItemPosition();
        if (!mPrintConfirmed || selectedIndex < 0) {
            // Update the print job's status.
            mPrintSpooler.setPrintJobState(mPrintJobId,
                    PrintJobInfo.STATE_CANCELED);
            return;
        }

        // Update the print job's printer.
        SpinnerItem<PrinterInfo> printerItem =
                mDestinationSpinnerAdapter.getItem(selectedIndex);
        PrinterId printerId =  printerItem.value.getId();
        mPrintSpooler.setPrintJobPrinterId(mPrintJobId, printerId);

        // Update the print job's status.
        mPrintSpooler.setPrintJobState(mPrintJobId,
                PrintJobInfo.STATE_QUEUED);

        if (DEBUG) {
            if (mPrintConfirmed) {
                File file = mRemotePrintAdapter.getFile();
                if (file.exists()) {
                    new ViewSpooledFileAsyncTask(file).executeOnExecutor(
                          AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
                }
            }
        }
    }

    private void updatePrintPreview(File file) {
        // TODO: Implement
    }

    private void addPrinters(List<PrinterInfo> addedPrinters) {
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
                        addedPrinter, addedPrinter.getLabel()));
            } else {
                Log.w(LOG_TAG, "Skipping a duplicate printer: " + addedPrinter);
            }
        }
    }

    private void removePrinters(List<PrinterId> pritnerIds) {
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
    }

    // Caution: Use this only for debugging
    private final class ViewSpooledFileAsyncTask extends AsyncTask<Void, Void, Void> {

        private final File mFile;

        public ViewSpooledFileAsyncTask(File file) {
            mFile = file;
        }

        @Override
        protected Void doInBackground(Void... params) {
            mFile.setExecutable(true, false);
            mFile.setWritable(true, false);
            mFile.setReadable(true, false);

            final long identity = Binder.clearCallingIdentity();
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(mFile), "application/pdf");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAsUser(intent, null, UserHandle.CURRENT);
            Binder.restoreCallingIdentity(identity);
            return null;
        }
    }

    private final class PrintDiscoveryObserver extends IPrinterDiscoveryObserver.Stub {
        private static final int MESSAGE_ADD_DICOVERED_PRINTERS = 1;
        private static final int MESSAGE_REMOVE_DICOVERED_PRINTERS = 2;

        private final Handler mHandler;

        @SuppressWarnings("unchecked")
        public PrintDiscoveryObserver(Looper looper) {
            mHandler = new Handler(looper, null, true) {
                @Override
                public void handleMessage(Message message) {
                    switch (message.what) {
                        case MESSAGE_ADD_DICOVERED_PRINTERS: {
                            List<PrinterInfo> printers = (List<PrinterInfo>) message.obj;
                            addPrinters(printers);
                            // Just added the first printer, so select it and start printing.
                            if (mDestinationSpinnerAdapter.getCount() == 1) {
                                mDestinationSpinner.setSelection(0);
                            }
                        } break;
                        case MESSAGE_REMOVE_DICOVERED_PRINTERS: {
                            List<PrinterId> printerIds = (List<PrinterId>) message.obj;
                            removePrinters(printerIds);
                            // TODO: Handle removing the last printer.
                        } break;
                    }
                }
            };
        }

        @Override
        public void addDiscoveredPrinters(List<PrinterInfo> printers) {
            mHandler.obtainMessage(MESSAGE_ADD_DICOVERED_PRINTERS, printers).sendToTarget();
        }

        @Override
        public void removeDiscoveredPrinters(List<PrinterId> printers) {
            mHandler.obtainMessage(MESSAGE_REMOVE_DICOVERED_PRINTERS, printers).sendToTarget();
        }
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

    /**
     * An instance of this class class is intended to be the first focusable
     * in a layout to which the system automatically gives focus. It performs
     * some voodoo to avoid the first tap on it to start an edit mode, rather
     * to bring up the IME, i.e. to get the behavior as if the view was not
     * focused.
     */
    public static final class CustomEditText extends EditText {
        private boolean mClickedBeforeFocus;

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

        protected void onFocusChanged(boolean gainFocus, int direction,
                Rect previouslyFocusedRect) {
            if (!gainFocus) {
                mClickedBeforeFocus = false;
            }
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        }
    }
}
