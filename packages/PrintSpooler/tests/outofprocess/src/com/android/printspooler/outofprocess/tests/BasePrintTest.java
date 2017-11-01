/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.printspooler.outofprocess.tests;

import android.annotation.NonNull;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.print.PrinterId;
import com.android.printspooler.outofprocess.tests.mockservice.PrintServiceCallbacks;
import com.android.printspooler.outofprocess.tests.mockservice.PrinterDiscoverySessionCallbacks;
import com.android.printspooler.outofprocess.tests.mockservice.StubbablePrinterDiscoverySession;
import android.printservice.CustomPrinterIconCallback;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.uiautomator.UiDevice;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.mockito.stubbing.Answer;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This is the base class for print tests.
 */
abstract class BasePrintTest {
    protected static final long OPERATION_TIMEOUT = 30000;
    private static final String PM_CLEAR_SUCCESS_OUTPUT = "Success";
    private static final int CURRENT_USER_ID = -2; // Mirrors UserHandle.USER_CURRENT

    private android.print.PrintJob mPrintJob;

    private static Instrumentation sInstrumentation;
    private static UiDevice sUiDevice;

    @Rule
    public ActivityTestRule<PrintTestActivity> mActivityRule =
            new ActivityTestRule<>(PrintTestActivity.class, false, true);

    /**
     * Return the UI device
     *
     * @return the UI device
     */
    public UiDevice getUiDevice() {
        return sUiDevice;
    }

    protected static Instrumentation getInstrumentation() {
        return sInstrumentation;
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
        sInstrumentation = InstrumentationRegistry.getInstrumentation();
        assumeTrue(sInstrumentation.getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_PRINTING));

        sUiDevice = UiDevice.getInstance(sInstrumentation);

        // Make sure we start with a clean slate.
        clearPrintSpoolerData();

        // Workaround for dexmaker bug: https://code.google.com/p/dexmaker/issues/detail?id=2
        // Dexmaker is used by mockito.
        System.setProperty("dexmaker.dexcache", getInstrumentation()
                .getTargetContext().getCacheDir().getPath());
    }

    @After
    public void exitActivities() throws Exception {
        // Exit print spooler
        getUiDevice().pressBack();
        getUiDevice().pressBack();
    }

    protected android.print.PrintJob print(@NonNull final PrintDocumentAdapter adapter,
            final PrintAttributes attributes) {
        // Initiate printing as if coming from the app.
        getInstrumentation().runOnMainSync(() -> {
            PrintManager printManager = (PrintManager) getActivity()
                    .getSystemService(Context.PRINT_SERVICE);
            mPrintJob = printManager.print("Print job", adapter, attributes);
        });

        return mPrintJob;
    }

    protected PrintTestActivity getActivity() {
        return mActivityRule.getActivity();
    }

    public static String runShellCommand(Instrumentation instrumentation, String cmd)
            throws IOException {
        ParcelFileDescriptor pfd = instrumentation.getUiAutomation().executeShellCommand(cmd);
        byte[] buf = new byte[512];
        int bytesRead;
        FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        StringBuilder stdout = new StringBuilder();
        while ((bytesRead = fis.read(buf)) != -1) {
            stdout.append(new String(buf, 0, bytesRead));
        }
        fis.close();
        return stdout.toString();
    }

    protected static void clearPrintSpoolerData() throws Exception {
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
}
