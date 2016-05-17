/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.CancellationSignal;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.print.PrinterId;
import android.print.mockservice.PrintServiceCallbacks;
import android.print.mockservice.PrinterDiscoverySessionCallbacks;
import android.print.mockservice.StubbablePrinterDiscoverySession;
import android.printservice.CustomPrinterIconCallback;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.test.InstrumentationTestCase;
import android.util.DisplayMetrics;

import org.mockito.stubbing.Answer;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * This is the base class for print tests.
 */
public abstract class BasePrintTest extends InstrumentationTestCase {

    private static final long OPERATION_TIMEOUT = 30000;
    private static final String PM_CLEAR_SUCCESS_OUTPUT = "Success";
    private static final String COMMAND_LIST_ENABLED_IME_COMPONENTS = "ime list -s";
    private static final String COMMAND_PREFIX_ENABLE_IME = "ime enable ";
    private static final String COMMAND_PREFIX_DISABLE_IME = "ime disable ";
    private static final int CURRENT_USER_ID = -2; // Mirrors UserHandle.USER_CURRENT

    private PrintTestActivity mActivity;
    private android.print.PrintJob mPrintJob;

    private LocaleList mOldLocale;

    private CallCounter mStartCallCounter;
    private CallCounter mStartSessionCallCounter;

    private String[] mEnabledImes;

    private String[] getEnabledImes() throws IOException {
        List<String> imeList = new ArrayList<>();

        ParcelFileDescriptor pfd = getInstrumentation().getUiAutomation()
                .executeShellCommand(COMMAND_LIST_ENABLED_IME_COMPONENTS);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(pfd.getFileDescriptor())))) {

            String line;
            while ((line = reader.readLine()) != null) {
                imeList.add(line);
            }
        }

        String[] imeArray = new String[imeList.size()];
        imeList.toArray(imeArray);

        return imeArray;
    }

    private void disableImes() throws Exception {
        mEnabledImes = getEnabledImes();
        for (String ime : mEnabledImes) {
            String disableImeCommand = COMMAND_PREFIX_DISABLE_IME + ime;
            runShellCommand(getInstrumentation(), disableImeCommand);
        }
    }

    private void enableImes() throws Exception {
        for (String ime : mEnabledImes) {
            String enableImeCommand = COMMAND_PREFIX_ENABLE_IME + ime;
            runShellCommand(getInstrumentation(), enableImeCommand);
        }
        mEnabledImes = null;
    }

    @Override
    protected void runTest() throws Throwable {
        // Do nothing if the device does not support printing.
        if (supportsPrinting()) {
            super.runTest();
        }
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        if (!supportsPrinting()) {
            return;
        }

        // Make sure we start with a clean slate.
        clearPrintSpoolerData();
        disableImes();

        // Workaround for dexmaker bug: https://code.google.com/p/dexmaker/issues/detail?id=2
        // Dexmaker is used by mockito.
        System.setProperty("dexmaker.dexcache", getInstrumentation()
                .getTargetContext().getCacheDir().getPath());

        // Set to US locale.
        Resources resources = getInstrumentation().getTargetContext().getResources();
        Configuration oldConfiguration = resources.getConfiguration();
        if (!oldConfiguration.getLocales().get(0).equals(Locale.US)) {
            mOldLocale = oldConfiguration.getLocales();
            DisplayMetrics displayMetrics = resources.getDisplayMetrics();
            Configuration newConfiguration = new Configuration(oldConfiguration);
            newConfiguration.setLocale(Locale.US);
            resources.updateConfiguration(newConfiguration, displayMetrics);
        }

        // Initialize the latches.
        mStartCallCounter = new CallCounter();
        mStartSessionCallCounter = new CallCounter();

        // Create the activity for the right locale.
        createActivity();
    }

    @Override
    public void tearDown() throws Exception {
        if (!supportsPrinting()) {
            return;
        }

        // Done with the activity.
        getActivity().finish();
        enableImes();

        // Restore the locale if needed.
        if (mOldLocale != null) {
            Resources resources = getInstrumentation().getTargetContext().getResources();
            DisplayMetrics displayMetrics = resources.getDisplayMetrics();
            Configuration newConfiguration = new Configuration(resources.getConfiguration());
            newConfiguration.setLocales(mOldLocale);
            mOldLocale = null;
            resources.updateConfiguration(newConfiguration, displayMetrics);
        }

        // Make sure the spooler is cleaned, this also un-approves all services
        clearPrintSpoolerData();

        super.tearDown();
    }

    protected android.print.PrintJob print(@NonNull final PrintDocumentAdapter adapter,
            final PrintAttributes attributes) {
        // Initiate printing as if coming from the app.
        getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                PrintManager printManager = (PrintManager) getActivity()
                        .getSystemService(Context.PRINT_SERVICE);
                mPrintJob = printManager.print("Print job", adapter, attributes);
            }
        });

        return mPrintJob;
    }

    protected void onStartCalled() {
        mStartCallCounter.call();
    }

    protected void onPrinterDiscoverySessionStartCalled() {
        mStartSessionCallCounter.call();
    }

    protected void waitForPrinterDiscoverySessionStartCallbackCalled() {
        waitForCallbackCallCount(mStartSessionCallCounter, 1,
                "Did not get expected call to onStartPrinterDiscoverySession.");
    }

    protected void waitForStartAdapterCallbackCalled() {
        waitForCallbackCallCount(mStartCallCounter, 1, "Did not get expected call to start.");
    }

    private void waitForCallbackCallCount(CallCounter counter, int count, String message) {
        try {
            counter.waitForCount(count, OPERATION_TIMEOUT);
        } catch (TimeoutException te) {
            fail(message);
        }
    }

    protected PrintTestActivity getActivity() {
        return mActivity;
    }

    protected void createActivity() {
        mActivity = launchActivity(getInstrumentation().getTargetContext().getPackageName(),
                PrintTestActivity.class, null);
    }

    public static String runShellCommand(Instrumentation instrumentation, String cmd)
            throws IOException {
        ParcelFileDescriptor pfd = instrumentation.getUiAutomation().executeShellCommand(cmd);
        byte[] buf = new byte[512];
        int bytesRead;
        FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        StringBuffer stdout = new StringBuffer();
        while ((bytesRead = fis.read(buf)) != -1) {
            stdout.append(new String(buf, 0, bytesRead));
        }
        fis.close();
        return stdout.toString();
    }

    protected void clearPrintSpoolerData() throws Exception {
        assertTrue("failed to clear print spooler data",
                runShellCommand(getInstrumentation(), String.format(
                        "pm clear --user %d %s", CURRENT_USER_ID,
                        PrintManager.PRINT_SPOOLER_PACKAGE_NAME))
                        .contains(PM_CLEAR_SUCCESS_OUTPUT));
    }

    @SuppressWarnings("unchecked")
    protected PrinterDiscoverySessionCallbacks createMockPrinterDiscoverySessionCallbacks(
            Answer<Void> onStartPrinterDiscovery, Answer<Void> onStopPrinterDiscovery,
            Answer<Void> onValidatePrinters, Answer<Void> onStartPrinterStateTracking,
            Answer<Void> onRequestCustomPrinterIcon, Answer<Void> onStopPrinterStateTracking,
            Answer<Void> onDestroy) {
        PrinterDiscoverySessionCallbacks callbacks = mock(PrinterDiscoverySessionCallbacks.class);

        doCallRealMethod().when(callbacks).setSession(any(StubbablePrinterDiscoverySession.class));
        when(callbacks.getSession()).thenCallRealMethod();

        if (onStartPrinterDiscovery != null) {
            doAnswer(onStartPrinterDiscovery).when(callbacks).onStartPrinterDiscovery(
                    any(List.class));
        }
        if (onStopPrinterDiscovery != null) {
            doAnswer(onStopPrinterDiscovery).when(callbacks).onStopPrinterDiscovery();
        }
        if (onValidatePrinters != null) {
            doAnswer(onValidatePrinters).when(callbacks).onValidatePrinters(
                    any(List.class));
        }
        if (onStartPrinterStateTracking != null) {
            doAnswer(onStartPrinterStateTracking).when(callbacks).onStartPrinterStateTracking(
                    any(PrinterId.class));
        }
        if (onRequestCustomPrinterIcon != null) {
            doAnswer(onRequestCustomPrinterIcon).when(callbacks).onRequestCustomPrinterIcon(
                    any(PrinterId.class), any(CancellationSignal.class),
                    any(CustomPrinterIconCallback.class));
        }
        if (onStopPrinterStateTracking != null) {
            doAnswer(onStopPrinterStateTracking).when(callbacks).onStopPrinterStateTracking(
                    any(PrinterId.class));
        }
        if (onDestroy != null) {
            doAnswer(onDestroy).when(callbacks).onDestroy();
        }

        return callbacks;
    }

    protected PrintServiceCallbacks createMockPrintServiceCallbacks(
            Answer<PrinterDiscoverySessionCallbacks> onCreatePrinterDiscoverySessionCallbacks,
            Answer<Void> onPrintJobQueued, Answer<Void> onRequestCancelPrintJob) {
        final PrintServiceCallbacks service = mock(PrintServiceCallbacks.class);

        doCallRealMethod().when(service).setService(any(PrintService.class));
        when(service.getService()).thenCallRealMethod();

        if (onCreatePrinterDiscoverySessionCallbacks != null) {
            doAnswer(onCreatePrinterDiscoverySessionCallbacks).when(service)
                    .onCreatePrinterDiscoverySessionCallbacks();
        }
        if (onPrintJobQueued != null) {
            doAnswer(onPrintJobQueued).when(service).onPrintJobQueued(any(PrintJob.class));
        }
        if (onRequestCancelPrintJob != null) {
            doAnswer(onRequestCancelPrintJob).when(service).onRequestCancelPrintJob(
                    any(PrintJob.class));
        }

        return service;
    }

    protected final class CallCounter {
        private final Object mLock = new Object();

        private int mCallCount;

        public void call() {
            synchronized (mLock) {
                mCallCount++;
                mLock.notifyAll();
            }
        }

        public int getCallCount() {
            synchronized (mLock) {
                return mCallCount;
            }
        }

        public void waitForCount(int count, long timeoutMillis) throws TimeoutException {
            synchronized (mLock) {
                final long startTimeMillis = SystemClock.uptimeMillis();
                while (mCallCount < count) {
                    try {
                        final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                        final long remainingTimeMillis = timeoutMillis - elapsedTimeMillis;
                        if (remainingTimeMillis <= 0) {
                            throw new TimeoutException();
                        }
                        mLock.wait(timeoutMillis);
                    } catch (InterruptedException ie) {
                        /* ignore */
                    }
                }
            }
        }
    }

    protected boolean supportsPrinting() {
        return getInstrumentation().getContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_PRINTING);
    }
}
