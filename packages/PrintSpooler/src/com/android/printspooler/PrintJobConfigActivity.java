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
import android.view.Choreographer;
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

    private Handler mHandler;

    private Editor mEditor;

    private IPrinterDiscoveryObserver mPrinterDiscoveryObserver;

    private int mAppId;
    private int mPrintJobId;

    private final PrintAttributes mOldPrintAttributes = new PrintAttributes.Builder().create();
    private final PrintAttributes mCurrPrintAttributes = new PrintAttributes.Builder().create();
    private final PrintAttributes mTempPrintAttributes = new PrintAttributes.Builder().create();

    private RemotePrintDocumentAdapter mRemotePrintAdapter;

    private boolean mPrintConfirmed;

    private boolean mStarted;

    private IBinder mIPrintDocumentAdapter;

    private PrintDocumentInfo mPrintDocumentInfo;

    private final DeathRecipient mDeathRecipient = new DeathRecipient() {
        @Override
        public void binderDied() {
            finish();
        }
    };

    @Override
    protected void onDestroy() {
        mIPrintDocumentAdapter.unlinkToDeath(mDeathRecipient, 0);
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.print_job_config_activity);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        mHandler = new MyHandler(Looper.getMainLooper());
        mEditor = new Editor();
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
        if (mEditor.getCurrentPrinter() == null
                || mStarted) {
            return;
        }
        mStarted = true;
        mRemotePrintAdapter.start();
    }

    private void updatePrintableContentIfNeeded() {
        if (!mStarted) {
            return;
        }

        mPrintSpooler.setPrintJobAttributes(mPrintJobId, mCurrPrintAttributes);

        mRemotePrintAdapter.cancel();
        mHandler.removeMessages(MyHandler.MSG_ON_LAYOUT_FINISHED);
        mHandler.removeMessages(MyHandler.MSG_ON_LAYOUT_FAILED);

        // TODO: Implement setting the print preview attribute
        mRemotePrintAdapter.layout(mOldPrintAttributes,
                mCurrPrintAttributes, new LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                mHandler.obtainMessage(MyHandler.MSG_ON_LAYOUT_FINISHED, changed ? 1 : 0,
                        0, info).sendToTarget();
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                mHandler.obtainMessage(MyHandler.MSG_ON_LAYOUT_FAILED, error).sendToTarget();
            }
        }, new Bundle());
    }

    private void handleOnLayoutFinished(PrintDocumentInfo info, boolean changed) {
        mPrintDocumentInfo = info;

        mEditor.updateUiIfNeeded();

        // TODO: Handle the case of unchanged content
        mPrintSpooler.setPrintJobPrintDocumentInfo(mPrintJobId, info);

        // TODO: Implement page selector.
        final List<PageRange> pages = new ArrayList<PageRange>();
        pages.add(PageRange.ALL_PAGES);

        mRemotePrintAdapter.write(pages, new WriteResultCallback() {
            @Override
            public void onWriteFinished(List<PageRange> pages) {
                mHandler.obtainMessage(MyHandler.MSG_ON_WRITE_FINISHED, pages).sendToTarget();
            }

            @Override
            public void onWriteFailed(CharSequence error) {
                mHandler.obtainMessage(MyHandler.MSG_ON_WRITE_FAILED, error).sendToTarget();
            }
        });
    }

    private void handleOnLayoutFailed(CharSequence error) {
        Log.e(LOG_TAG, "Error during layout: " + error);
        finishActivity(Activity.RESULT_CANCELED);
    }

    private void handleOnWriteFinished(List<PageRange> pages) {
        // TODO: Now we have to allow the preview button
        mEditor.updatePrintPreview(mRemotePrintAdapter.getFile());
    }

    private void handleOnWriteFailed(CharSequence error) {
        Log.e(LOG_TAG, "Error write layout: " + error);
        finishActivity(Activity.RESULT_CANCELED);
    }

    private void notifyPrintableFinishIfNeeded() {
        if (!mStarted) {
            return;
        }

        if (!mPrintConfirmed) {
            mRemotePrintAdapter.cancel();
        }
        mRemotePrintAdapter.finish();

        PrinterInfo printer = mEditor.getCurrentPrinter();
        // If canceled or no printer, nothing to do.
        if (!mPrintConfirmed || printer == null) {
            // Update the print job's status.
            mPrintSpooler.setPrintJobState(mPrintJobId,
                    PrintJobInfo.STATE_CANCELED);
            return;
        }

        // Update the print job's printer.
        mPrintSpooler.setPrintJobPrinterId(mPrintJobId, printer.getId());

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

    private boolean hasPdfViewer() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setType("application/pdf");
        return !getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY).isEmpty();
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
                            mEditor.addPrinters(printers);
                        } break;
                        case MESSAGE_REMOVE_DICOVERED_PRINTERS: {
                            List<PrinterId> printerIds = (List<PrinterId>) message.obj;
                            mEditor.removePrinters(printerIds);
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

        @Override
        public void setError(CharSequence error, Drawable icon) {
            setCompoundDrawables(null, null, icon, null);
        }

        protected void onFocusChanged(boolean gainFocus, int direction,
                Rect previouslyFocusedRect) {
            if (!gainFocus) {
                mClickedBeforeFocus = false;
            }
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        }
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
        @SuppressWarnings("unchecked")
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_ON_LAYOUT_FINISHED: {
                    PrintDocumentInfo info = (PrintDocumentInfo) message.obj;
                    final boolean changed = (message.arg1 == 1);
                    handleOnLayoutFinished(info, changed);
                } break;

                case MSG_ON_LAYOUT_FAILED: {
                    CharSequence error = (CharSequence) message.obj;
                    handleOnLayoutFailed(error);
                } break;

                case MSG_ON_WRITE_FINISHED: {
                    List<PageRange> pages = (List<PageRange>) message.obj;
                    handleOnWriteFinished(pages);
                } break;

                case MSG_ON_WRITE_FAILED: {
                    CharSequence error = (CharSequence) message.obj;
                    handleOnWriteFailed(error);
                } break;
            }
        }
    }

    private class Editor {
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

        private Button mPrintPreviewButton;

        private Button mPrintButton;

        private final OnItemSelectedListener mOnItemSelectedListener =
                new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> spinner, View view, int position, long id) {
                if (spinner == mDestinationSpinner) {
                    mOldPrintAttributes.copyFrom(mCurrPrintAttributes);
                    mCurrPrintAttributes.clear();
                    final int selectedIndex = mDestinationSpinner.getSelectedItemPosition();
                    if (selectedIndex >= 0) {
                        mDestinationSpinnerAdapter.getItem(selectedIndex).value.getDefaults(
                                mCurrPrintAttributes);
                    }
                    updateUiIfNeeded();
                    notifyPrintableStartIfNeeded();
                    updatePrintableContentIfNeeded();
                } else if (spinner == mMediaSizeSpinner) {
                    SpinnerItem<MediaSize> mediaItem = mMediaSizeSpinnerAdapter.getItem(position);
                    mOldPrintAttributes.copyFrom(mCurrPrintAttributes);
                    mCurrPrintAttributes.setMediaSize(mediaItem.value);
                    updatePrintableContentIfNeeded();
                } else if (spinner == mColorModeSpinner) {
                    SpinnerItem<Integer> colorModeItem =
                            mColorModeSpinnerAdapter.getItem(position);
                    mOldPrintAttributes.copyFrom(mCurrPrintAttributes);
                    mCurrPrintAttributes.setColorMode(colorModeItem.value);
                    updatePrintableContentIfNeeded();
                } else if (spinner == mOrientationSpinner) {
                    SpinnerItem<Integer> orientationItem =
                            mOrientationSpinnerAdapter.getItem(position);
                    mOldPrintAttributes.copyFrom(mCurrPrintAttributes);
                    mCurrPrintAttributes.setOrientation(orientationItem.value);
                    updatePrintableContentIfNeeded();
                } else if (spinner == mRangeOptionsSpinner) {
                    updateUiIfNeeded();
                    updatePrintableContentIfNeeded();
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
                    mCopiesEditText.setError("");
                    mPrintButton.setEnabled(false);
                    return;
                }
                final int copies = Integer.parseInt(editable.toString());
                if (copies < MIN_COPIES) {
                    mCopiesEditText.setError("");
                    mPrintButton.setEnabled(false);
                    return;
                }
                mOldPrintAttributes.copyFrom(mCurrPrintAttributes);
                mCurrPrintAttributes.setCopies(copies);
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
                    mRangeEditText.setError("");
                    mPrintButton.setEnabled(false);
                    return;
                }

                String escapedText = PATTERN_ESCAPE_SPECIAL_CHARS.matcher(text).replaceAll("////");
                if (!PATTERN_PAGE_RANGE.matcher(escapedText).matches()) {
                    mRangeEditText.setError("");
                    mPrintButton.setEnabled(false);
                    return;
                }

                Matcher matcher = PATTERN_DIGITS.matcher(text);
                while (matcher.find()) {
                    String numericString = text.substring(matcher.start(), matcher.end());
                    final int pageIndex = Integer.parseInt(numericString);
                    if (pageIndex < 1 || pageIndex > mPrintDocumentInfo.getPageCount()) {
                        mRangeEditText.setError("");
                        mPrintButton.setEnabled(false);
                        return;
                    }
                }

                mRangeEditText.setError(null);
                mPrintButton.setEnabled(true);
            }
        };

        public Editor() {
            Bundle extras = getIntent().getExtras();

            mPrintJobId = extras.getInt(EXTRA_PRINT_JOB_ID, -1);
            if (mPrintJobId < 0) {
                throw new IllegalArgumentException("Invalid print job id: " + mPrintJobId);
            }

            mAppId = extras.getInt(EXTRA_APP_ID, -1);
            if (mAppId < 0) {
                throw new IllegalArgumentException("Invalid app id: " + mAppId);
            }

            PrintAttributes attributes = getIntent().getParcelableExtra(EXTRA_ATTRIBUTES);
            if (attributes == null) {
                mCurrPrintAttributes.copyFrom(attributes);
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

        private void bindUi() {
            // Copies
            mCopiesEditText = (EditText) findViewById(R.id.copies_edittext);
            mCopiesEditText.setText(String.valueOf(MIN_COPIES));
            mCopiesEditText.addTextChangedListener(mCopiesTextWatcher);
            mCopiesEditText.setText(String.valueOf(
                    Math.max(mCurrPrintAttributes.getCopies(), MIN_COPIES)));
            mCopiesEditText.selectAll();

            // Destination.
            mDestinationSpinner = (Spinner) findViewById(R.id.destination_spinner);
            mDestinationSpinnerAdapter = new ArrayAdapter<SpinnerItem<PrinterInfo>>(
                    PrintJobConfigActivity.this, R.layout.spinner_dropdown_item) {
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
                            title.setText(printerInfo.getLabel());

                            try {
                                TextView subtitle = (TextView)
                                        convertView.findViewById(R.id.subtitle);
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
            mRangeEditText = (EditText) findViewById(R.id.page_range_edittext);
            mRangeEditText.addTextChangedListener(mRangeTextWatcher);

            // Range options
            mRangeOptionsSpinner = (Spinner) findViewById(R.id.range_options_spinner);
            mRangeOptionsSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(
                    PrintJobConfigActivity.this,
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

            mPrintPreviewButton = (Button) findViewById(R.id.print_preview_button);
            mPrintPreviewButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    // TODO: Implement
                }
            });

            mPrintButton = (Button) findViewById(R.id.print_button);
            mPrintButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mPrintConfirmed = true;
                    finish();
                }
            });
        }

        private void updateUiIfNeeded() {
            final int selectedIndex = mDestinationSpinner.getSelectedItemPosition();

            if (selectedIndex < 0) {
                // Destination
                mDestinationSpinner.setEnabled(false);

                // Copies
                mCopiesEditText.setText("1");
                mCopiesEditText.setEnabled(false);

                // Media size
                mMediaSizeSpinner.setOnItemSelectedListener(null);
                mMediaSizeSpinner.setSelection(AdapterView.INVALID_POSITION);
                mMediaSizeSpinner.setEnabled(false);

                // Color mode
                mColorModeSpinner.setOnItemSelectedListener(null);
                mColorModeSpinner.setSelection(AdapterView.INVALID_POSITION);
                mColorModeSpinner.setEnabled(false);

                // Orientation
                mOrientationSpinner.setOnItemSelectedListener(null);
                mOrientationSpinner.setSelection(AdapterView.INVALID_POSITION);
                mOrientationSpinner.setEnabled(false);

                // Range
                mRangeOptionsSpinner.setOnItemSelectedListener(null);
                mRangeOptionsSpinner.setSelection(0);
                mRangeOptionsSpinner.setEnabled(false);
                mRangeEditText.setText("");
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
                mDestinationSpinner.setEnabled(true);

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
                    if (mediaSizeCount > 0) {
                        mMediaSizeSpinner.setEnabled(true);
                        final int selectedMediaSizeIndex = Math.max(mediaSizes.indexOf(
                                defaultAttributes.getMediaSize()), 0);
                        mMediaSizeSpinner.setOnItemSelectedListener(null);
                        mMediaSizeSpinner.setSelection(selectedMediaSizeIndex);
                    }
                }

                // Color mode.
                final int colorModes = printer.getColorModes();
                boolean colorModesChanged = false;
                if (Integer.bitCount(colorModes) != mColorModeSpinnerAdapter.getCount()) {
                    colorModesChanged = true;
                } else {
                    int remainingColorModes = colorModes;
                    while (remainingColorModes != 0) {
                        final int colorBitOffset = Integer.numberOfTrailingZeros(
                                remainingColorModes);
                        final int colorMode = 1 << colorBitOffset;
                        remainingColorModes &= ~colorMode;
                        if (colorMode != mColorModeSpinnerAdapter.getItem(colorBitOffset).value) {
                            colorModesChanged = true;
                            break;
                        }
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
                    if (colorModes > 0) {
                        mColorModeSpinner.setEnabled(true);
                        final int selectedColorModeIndex = Integer.numberOfTrailingZeros(
                                (colorModes & defaultAttributes.getColorMode()));
                        mColorModeSpinner.setOnItemSelectedListener(null);
                        mColorModeSpinner.setSelection(selectedColorModeIndex);
                    }
                }

                // Orientation.
                final int orientations = printer.getOrientations();
                boolean orientationsChanged = false;
                if (Integer.bitCount(orientations) != mOrientationSpinnerAdapter.getCount()) {
                    orientationsChanged = true;
                } else {
                    int remainingOrientations = orientations;
                    while (remainingOrientations != 0) {
                        final int orientationBitOffset = Integer.numberOfTrailingZeros(
                                remainingOrientations);
                        final int orientation = 1 << orientationBitOffset;
                        remainingOrientations &= ~orientation;
                        if (orientation != mOrientationSpinnerAdapter.getItem(
                                orientationBitOffset).value) {
                            orientationsChanged = true;
                            break;
                        }
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
                    if (orientations > 0) {
                        mOrientationSpinner.setEnabled(true);
                        final int selectedOrientationIndex = Integer.numberOfTrailingZeros(
                                (orientations & defaultAttributes.getOrientation()));
                        mOrientationSpinner.setOnItemSelectedListener(null);
                        mOrientationSpinner.setSelection(selectedOrientationIndex);
                    }
                }

                // Range options
                if (mPrintDocumentInfo != null && (mPrintDocumentInfo.getPageCount() > 1
                        || mPrintDocumentInfo.getPageCount()
                            == PrintDocumentInfo.PAGE_COUNT_UNKNOWN)) {
                    mRangeOptionsSpinner.setEnabled(true);
                    if (mRangeOptionsSpinner.getSelectedItemPosition() > 0
                            && !mRangeEditText.isEnabled()) {
                        mRangeEditText.setEnabled(true);
                        mRangeEditText.setError("");
                        mRangeEditText.setVisibility(View.VISIBLE);
                        mRangeEditText.requestFocus();
                        InputMethodManager imm = (InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                        imm.showSoftInput(mRangeEditText, 0);
                    }
                } else {
                    mRangeOptionsSpinner.setOnItemSelectedListener(null);
                    mRangeOptionsSpinner.setSelection(0);
                    mRangeOptionsSpinner.setEnabled(false);
                    mRangeEditText.setEnabled(false);
                    mRangeEditText.setText("");
                    mRangeEditText.setVisibility(View.INVISIBLE);
                }

                // Print preview
                mPrintPreviewButton.setEnabled(true);
                if (hasPdfViewer()) {
                    mPrintPreviewButton.setText(getString(R.string.print_preview));
                } else {
                    mPrintPreviewButton.setText(getString(R.string.install_for_print_preview));
                }

                // Print
                mPrintButton.setEnabled(true);
            }

            // Here is some voodoo to circumvent the weird behavior of AdapterView
            // in which a selection listener may get a callback for an event that
            // happened before the listener was registered. The reason for that is
            // that the selection change is handled on the next layout pass.
            Choreographer.getInstance().postCallback(Choreographer.CALLBACK_TRAVERSAL,
                    new Runnable() {
                @Override
                public void run() {
                    mMediaSizeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
                    mColorModeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
                    mOrientationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
                    mRangeOptionsSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
                }
            }, null);
        }

        public PrinterInfo getCurrentPrinter() {
            final int selectedIndex = mDestinationSpinner.getSelectedItemPosition();
            if (selectedIndex >= 0) {
                return mDestinationSpinnerAdapter.getItem(selectedIndex).value;
            }
            return null;
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
                            addedPrinter, addedPrinter.getLabel()));
                } else {
                    Log.w(LOG_TAG, "Skipping a duplicate printer: " + addedPrinter);
                }
            }

            if (mDestinationSpinner.getSelectedItemPosition() == AdapterView.INVALID_POSITION
                    && mDestinationSpinnerAdapter.getCount() > 0) {
                mDestinationSpinner.setSelection(0);
            }
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

        private void updatePrintPreview(File file) {
            // TODO: Implement
        }
    }
}
