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
import android.content.Intent;
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
import android.print.PrintAttributes.Resolution;
import android.print.PrintAttributes.Tray;
import android.print.PrintDocumentAdapter.LayoutResultCallback;
import android.print.PrintDocumentAdapter.WriteResultCallback;
import android.print.PrintDocumentInfo;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Choreographer;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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

    private final PrintSpooler mPrintSpooler = PrintSpooler.getInstance(this);

    private IPrinterDiscoveryObserver mPrinterDiscoveryObserver;

    private int mAppId;
    private int mPrintJobId;

    private PrintAttributes mPrintAttributes;

    private RemotePrintDocumentAdapter mRemotePrintAdapter;

    // UI elements

    private EditText mCopiesEditText;

    private Spinner mDestinationSpinner;
    public ArrayAdapter<SpinnerItem<PrinterInfo>> mDestinationSpinnerAdapter;

    private Spinner mMediaSizeSpinner;
    public ArrayAdapter<SpinnerItem<MediaSize>> mMediaSizeSpinnerAdapter;

    private Spinner mResolutionSpinner;
    public ArrayAdapter<SpinnerItem<Resolution>> mResolutionSpinnerAdapter;

    private Spinner mInputTraySpinner;
    public ArrayAdapter<SpinnerItem<Tray>> mInputTraySpinnerAdapter;

    private Spinner mOutputTraySpinner;
    public ArrayAdapter<SpinnerItem<Tray>> mOutputTraySpinnerAdapter;

    private Spinner mDuplexModeSpinner;
    public ArrayAdapter<SpinnerItem<Integer>> mDuplexModeSpinnerAdapter;

    private Spinner mColorModeSpinner;
    public ArrayAdapter<SpinnerItem<Integer>> mColorModeSpinnerAdapter;

    private Spinner mFittingModeSpinner;
    public ArrayAdapter<SpinnerItem<Integer>> mFittingModeSpinnerAdapter;

    private Spinner mOrientationSpinner;
    public ArrayAdapter<SpinnerItem<Integer>> mOrientationSpinnerAdapter;

    private boolean mPrintConfirmed;

    private boolean mStarted;

    private IBinder mIPrintDocumentAdapter;

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
            } else if (spinner == mResolutionSpinner) {
                SpinnerItem<Resolution> resolutionItem =
                        mResolutionSpinnerAdapter.getItem(position);
                mPrintAttributes.setResolution(resolutionItem.value);
                updatePrintableContentIfNeeded();
            } else if (spinner == mInputTraySpinner) {
                SpinnerItem<Tray> inputTrayItem =
                        mInputTraySpinnerAdapter.getItem(position);
                mPrintAttributes.setInputTray(inputTrayItem.value);
            } else if (spinner == mOutputTraySpinner) {
                SpinnerItem<Tray> outputTrayItem =
                        mOutputTraySpinnerAdapter.getItem(position);
                mPrintAttributes.setOutputTray(outputTrayItem.value);
            } else if (spinner == mDuplexModeSpinner) {
                SpinnerItem<Integer> duplexModeItem =
                        mDuplexModeSpinnerAdapter.getItem(position);
                mPrintAttributes.setDuplexMode(duplexModeItem.value);
            } else if (spinner == mColorModeSpinner) {
                SpinnerItem<Integer> colorModeItem =
                        mColorModeSpinnerAdapter.getItem(position);
                mPrintAttributes.setColorMode(colorModeItem.value);
            } else if (spinner == mFittingModeSpinner) {
                SpinnerItem<Integer> fittingModeItem =
                        mFittingModeSpinnerAdapter.getItem(position);
                mPrintAttributes.setFittingMode(fittingModeItem.value);
            } else if (spinner == mOrientationSpinner) {
                SpinnerItem<Integer> orientationItem =
                        mOrientationSpinnerAdapter.getItem(position);
                mPrintAttributes.setOrientation(orientationItem.value);
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            /* do nothing*/
        }
    };

    private final TextWatcher mTextWatcher = new TextWatcher() {
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            final int copies = Integer.parseInt(mCopiesEditText.getText().toString());
            mPrintAttributes.setCopies(copies);
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            /* do nothing */
        }

        @Override
        public void afterTextChanged(Editable s) {
            /* do nothing */
        }
    };

    private final InputFilter mInputFilter = new InputFilter() {
        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            StringBuffer text = new StringBuffer(dest.toString());
            text.replace(dstart, dend, source.subSequence(start, end).toString());
            if (TextUtils.isEmpty(text)) {
                return dest;
            }
            final int copies = Integer.parseInt(text.toString());
            if (copies < MIN_COPIES) {
                return dest;
            }
            return null;
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
        mCopiesEditText.addTextChangedListener(mTextWatcher);
        mCopiesEditText.setFilters(new InputFilter[] {mInputFilter});

        // Destination.
        mDestinationSpinner = (Spinner) findViewById(R.id.destination_spinner);
        mDestinationSpinnerAdapter = new ArrayAdapter<SpinnerItem<PrinterInfo>>(this,
                android.R.layout.simple_spinner_dropdown_item);
        mDestinationSpinner.setAdapter(mDestinationSpinnerAdapter);
        mDestinationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Media size.
        mMediaSizeSpinner = (Spinner) findViewById(R.id.media_size_spinner);
        mMediaSizeSpinnerAdapter = new ArrayAdapter<SpinnerItem<MediaSize>>(this,
                android.R.layout.simple_spinner_dropdown_item);
        mMediaSizeSpinner.setAdapter(mMediaSizeSpinnerAdapter);
        mMediaSizeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Resolution.
        mResolutionSpinner = (Spinner) findViewById(R.id.resolution_spinner);
        mResolutionSpinnerAdapter = new ArrayAdapter<SpinnerItem<Resolution>>(this,
                android.R.layout.simple_spinner_dropdown_item);
        mResolutionSpinner.setAdapter(mResolutionSpinnerAdapter);
        mResolutionSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Input tray.
        mInputTraySpinner = (Spinner) findViewById(R.id.input_tray_spinner);
        mInputTraySpinnerAdapter = new ArrayAdapter<SpinnerItem<Tray>>(this,
                android.R.layout.simple_spinner_dropdown_item);
        mInputTraySpinner.setAdapter(mInputTraySpinnerAdapter);

        // Output tray.
        mOutputTraySpinner = (Spinner) findViewById(R.id.output_tray_spinner);
        mOutputTraySpinnerAdapter = new ArrayAdapter<SpinnerItem<Tray>>(this,
                android.R.layout.simple_spinner_dropdown_item);
        mOutputTraySpinner.setAdapter(mOutputTraySpinnerAdapter);
        mOutputTraySpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Duplex mode.
        mDuplexModeSpinner = (Spinner) findViewById(R.id.duplex_mode_spinner);
        mDuplexModeSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(this,
                android.R.layout.simple_spinner_dropdown_item);
        mDuplexModeSpinner.setAdapter(mDuplexModeSpinnerAdapter);
        mDuplexModeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Color mode.
        mColorModeSpinner = (Spinner) findViewById(R.id.color_mode_spinner);
        mColorModeSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(this,
                android.R.layout.simple_spinner_dropdown_item);
        mColorModeSpinner.setAdapter(mColorModeSpinnerAdapter);
        mColorModeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Color mode.
        mFittingModeSpinner = (Spinner) findViewById(R.id.fitting_mode_spinner);
        mFittingModeSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(this,
                android.R.layout.simple_spinner_dropdown_item);
        mFittingModeSpinner.setAdapter(mFittingModeSpinnerAdapter);
        mFittingModeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);

        // Orientation
        mOrientationSpinner = (Spinner) findViewById(R.id.orientation_spinner);
        mOrientationSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(this,
                android.R.layout.simple_spinner_dropdown_item);
        mOrientationSpinner.setAdapter(mOrientationSpinnerAdapter);
        mOrientationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
    }

    private void updateUi() {
        final int selectedIndex = mDestinationSpinner.getSelectedItemPosition();
        PrinterInfo printer = mDestinationSpinnerAdapter.getItem(selectedIndex).value;
        printer.getDefaults(mPrintAttributes);

        // Copies.
        mCopiesEditText.setText(String.valueOf(
                Math.max(mPrintAttributes.getCopies(), MIN_COPIES)));

        // Media size.
        mMediaSizeSpinnerAdapter.clear();
        List<MediaSize> mediaSizes = printer.getMediaSizes();
        final int mediaSizeCount = mediaSizes.size();
        for (int i = 0; i < mediaSizeCount; i++) {
            MediaSize mediaSize = mediaSizes.get(i);
            mMediaSizeSpinnerAdapter.add(new SpinnerItem<MediaSize>(
                    mediaSize, mediaSize.getLabel(getPackageManager())));
        }
        final int selectedMediaSizeIndex = mediaSizes.indexOf(
                mPrintAttributes.getMediaSize());
        mMediaSizeSpinner.setOnItemSelectedListener(null);
        mMediaSizeSpinner.setSelection(selectedMediaSizeIndex);

        // Resolution.
        mResolutionSpinnerAdapter.clear();
        List<Resolution> resolutions = printer.getResolutions();
        final int resolutionCount = resolutions.size();
        for (int i = 0; i < resolutionCount; i++) {
            Resolution resolution = resolutions.get(i);
            mResolutionSpinnerAdapter.add(new SpinnerItem<Resolution>(
                    resolution, resolution.getLabel(getPackageManager())));
        }
        final int selectedResolutionIndex = resolutions.indexOf(
                mPrintAttributes.getResolution());
        mResolutionSpinner.setOnItemSelectedListener(null);
        mResolutionSpinner.setSelection(selectedResolutionIndex);

        // AdapterView has the weird behavior to notify the selection listener for a
        // selection event that occurred *before* the listener was registered because
        // it does the real selection change on the next layout pass. To avoid this
        // behavior we re-attach the listener in the next traversal window - fun!
        Choreographer.getInstance().postCallback(
                Choreographer.CALLBACK_TRAVERSAL, new Runnable() {
                    @Override
                    public void run() {
                        mMediaSizeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
                        mResolutionSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
                    }
                }, null);

        // Input tray.
        mInputTraySpinnerAdapter.clear();
        List<Tray> inputTrays = printer.getInputTrays();
        final int inputTrayCount = inputTrays.size();
        for (int i = 0; i < inputTrayCount; i++) {
            Tray inputTray = inputTrays.get(i);
            mInputTraySpinnerAdapter.add(new SpinnerItem<Tray>(
                    inputTray, inputTray.getLabel(getPackageManager())));
        }
        final int selectedInputTrayIndex = inputTrays.indexOf(
                mPrintAttributes.getInputTray());
        mInputTraySpinner.setSelection(selectedInputTrayIndex);

        // Output tray.
        mOutputTraySpinnerAdapter.clear();
        List<Tray> outputTrays = printer.getOutputTrays();
        final int outputTrayCount = outputTrays.size();
        for (int i = 0; i < outputTrayCount; i++) {
            Tray outputTray = outputTrays.get(i);
            mOutputTraySpinnerAdapter.add(new SpinnerItem<Tray>(
                    outputTray, outputTray.getLabel(getPackageManager())));
        }
        final int selectedOutputTrayIndex = outputTrays.indexOf(
                mPrintAttributes.getOutputTray());
        mOutputTraySpinner.setSelection(selectedOutputTrayIndex);

        // Duplex mode.
        final int duplexModes = printer.getDuplexModes();
        mDuplexModeSpinnerAdapter.clear();
        String[] duplexModeLabels = getResources().getStringArray(
                R.array.duplex_mode_labels);
        int remainingDuplexModes = duplexModes;
        while (remainingDuplexModes != 0) {
            final int duplexBitOffset = Integer.numberOfTrailingZeros(remainingDuplexModes);
            final int duplexMode = 1 << duplexBitOffset;
            remainingDuplexModes &= ~duplexMode;
            mDuplexModeSpinnerAdapter.add(new SpinnerItem<Integer>(duplexMode,
                    duplexModeLabels[duplexBitOffset]));
        }
        final int selectedDuplexModeIndex = Integer.numberOfTrailingZeros(
                (duplexModes & mPrintAttributes.getDuplexMode()));
        mDuplexModeSpinner.setSelection(selectedDuplexModeIndex);

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

        // Fitting mode.
        final int fittingModes = printer.getFittingModes();
        mFittingModeSpinnerAdapter.clear();
        String[] fittingModeLabels = getResources().getStringArray(
                R.array.fitting_mode_labels);
        int remainingFittingModes = fittingModes;
        while (remainingFittingModes != 0) {
            final int fittingBitOffset = Integer.numberOfTrailingZeros(remainingFittingModes);
            final int fittingMode = 1 << fittingBitOffset;
            remainingFittingModes &= ~fittingMode;
            mFittingModeSpinnerAdapter.add(new SpinnerItem<Integer>(fittingMode,
                    fittingModeLabels[fittingBitOffset]));
        }
        final int selectedFittingModeIndex = Integer.numberOfTrailingZeros(
                (fittingModes & mPrintAttributes.getFittingMode()));
        mFittingModeSpinner.setSelection(selectedFittingModeIndex);

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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.print_job_config_activity, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.print_button) {
            mPrintConfirmed = true;
            finish();
        }
        return super.onOptionsItemSelected(item);
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

        mRemotePrintAdapter.layout(new PrintAttributes.Builder().create(),
                mPrintAttributes, new LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
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
        });
    }

    private void notifyPrintableFinishIfNeeded() {
        if (!mStarted) {
            return;
        }

        if (!mPrintConfirmed) {
            mRemotePrintAdapter.cancel();
        }
        mRemotePrintAdapter.finish();

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
}
