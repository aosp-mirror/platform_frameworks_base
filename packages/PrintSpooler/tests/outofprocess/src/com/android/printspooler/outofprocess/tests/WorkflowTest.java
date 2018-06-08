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

import static org.junit.Assert.assertNotNull;

import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.print.pdf.PrintedPdfDocument;
import android.print.test.BasePrintTest;
import android.print.test.services.AddPrintersActivity;
import android.print.test.services.FirstPrintService;
import android.print.test.services.PrinterDiscoverySessionCallbacks;
import android.print.test.services.StubbablePrinterDiscoverySession;
import android.support.test.filters.LargeTest;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.util.Log;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Tests for the basic printing workflows
 */
@RunWith(Parameterized.class)
public class WorkflowTest extends BasePrintTest {
    private static final String LOG_TAG = WorkflowTest.class.getSimpleName();

    private PrintAttributes.MediaSize mFirst;
    private boolean mSelectPrinter;
    private PrintAttributes.MediaSize mSecond;

    public WorkflowTest(PrintAttributes.MediaSize first, boolean selectPrinter,
            PrintAttributes.MediaSize second) {
        mFirst = first;
        mSelectPrinter = selectPrinter;
        mSecond = second;
    }

    interface InterruptableConsumer<T> {
        void accept(T t) throws InterruptedException;
    }

    /**
     * Execute {@code waiter} until {@code condition} is met.
     *
     * @param condition Conditions to wait for
     * @param waiter    Code to execute while waiting
     */
    private void waitWithTimeout(Supplier<Boolean> condition, InterruptableConsumer<Long> waiter)
            throws TimeoutException, InterruptedException {
        long startTime = System.currentTimeMillis();
        while (condition.get()) {
            long timeLeft = OPERATION_TIMEOUT_MILLIS - (System.currentTimeMillis() - startTime);
            if (timeLeft < 0) {
                throw new TimeoutException();
            }

            waiter.accept(timeLeft);
        }
    }

    /** Add a printer with a given name and supported mediasize to a session */
    private void addPrinter(StubbablePrinterDiscoverySession session,
            String name, PrintAttributes.MediaSize mediaSize) {
        PrinterId printerId = session.getService().generatePrinterId(name);
        List<PrinterInfo> printers = new ArrayList<>(1);

        PrinterCapabilitiesInfo.Builder builder =
                new PrinterCapabilitiesInfo.Builder(printerId);

        PrinterInfo printerInfo;
        if (mediaSize != null) {
            builder.setMinMargins(new PrintAttributes.Margins(0, 0, 0, 0))
                    .setColorModes(PrintAttributes.COLOR_MODE_COLOR,
                            PrintAttributes.COLOR_MODE_COLOR)
                    .addMediaSize(mediaSize, true)
                    .addResolution(new PrintAttributes.Resolution("300x300", "300x300", 300, 300),
                            true);

            printerInfo = new PrinterInfo.Builder(printerId, name,
                    PrinterInfo.STATUS_IDLE).setCapabilities(builder.build()).build();
        } else {
            printerInfo = (new PrinterInfo.Builder(printerId, name,
                    PrinterInfo.STATUS_IDLE)).build();
        }

        printers.add(printerInfo);
        session.addPrinters(printers);
    }

    /** Find a certain element in the UI and click on it */
    private void clickOn(UiSelector selector) throws UiObjectNotFoundException {
        Log.i(LOG_TAG, "Click on " + selector);
        UiObject view = getUiDevice().findObject(selector);
        view.click();
        getUiDevice().waitForIdle();
    }

    /** Find a certain text in the UI and click on it */
    private void clickOnText(String text) throws UiObjectNotFoundException {
        clickOn(new UiSelector().text(text));
    }

    /** Set the printer in the print activity */
    private void setPrinter(String printerName) throws UiObjectNotFoundException {
        clickOn(new UiSelector().resourceId("com.android.printspooler:id/destination_spinner"));

        clickOnText(printerName);
    }

    /**
     * Init mock print servic that returns a single printer by default.
     *
     * @param sessionRef Where to store the reference to the session once started
     */
    private void setMockPrintServiceCallbacks(StubbablePrinterDiscoverySession[] sessionRef,
            ArrayList<String> trackedPrinters, PrintAttributes.MediaSize mediaSize) {
        FirstPrintService.setCallbacks(createMockPrintServiceCallbacks(
                inv -> createMockPrinterDiscoverySessionCallbacks(inv2 -> {
                            synchronized (sessionRef) {
                                sessionRef[0] = ((PrinterDiscoverySessionCallbacks) inv2.getMock())
                                        .getSession();

                                addPrinter(sessionRef[0], "1st printer", mediaSize);

                                sessionRef.notifyAll();
                            }
                            return null;
                        },
                        null, null, inv2 -> {
                            synchronized (trackedPrinters) {
                                trackedPrinters
                                        .add(((PrinterId) inv2.getArguments()[0]).getLocalId());
                                trackedPrinters.notifyAll();
                            }
                            return null;
                        }, null, inv2 -> {
                            synchronized (trackedPrinters) {
                                trackedPrinters
                                        .remove(((PrinterId) inv2.getArguments()[0]).getLocalId());
                                trackedPrinters.notifyAll();
                            }
                            return null;
                        }, inv2 -> {
                            synchronized (sessionRef) {
                                sessionRef[0] = null;
                                sessionRef.notifyAll();
                            }
                            return null;
                        }
                ), null, null));
    }

    /**
     * Start print operation that just prints a single empty page
     *
     * @param printAttributesRef Where to store the reference to the print attributes once started
     */
    private void print(PrintAttributes[] printAttributesRef) {
        print(new PrintDocumentAdapter() {
            @Override
            public void onStart() {
            }

            @Override
            public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                    CancellationSignal cancellationSignal, LayoutResultCallback callback,
                    Bundle extras) {
                callback.onLayoutFinished((new PrintDocumentInfo.Builder("doc")).build(),
                        !newAttributes.equals(printAttributesRef[0]));

                synchronized (printAttributesRef) {
                    printAttributesRef[0] = newAttributes;
                    printAttributesRef.notifyAll();
                }
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                    CancellationSignal cancellationSignal, WriteResultCallback callback) {
                try {
                    try {
                        PrintedPdfDocument document = new PrintedPdfDocument(getActivity(),
                                printAttributesRef[0]);
                        try {
                            PdfDocument.Page page = document.startPage(0);
                            document.finishPage(page);
                            try (FileOutputStream os = new FileOutputStream(
                                    destination.getFileDescriptor())) {
                                document.writeTo(os);
                                os.flush();
                            }
                        } finally {
                            document.close();
                        }
                    } finally {
                        destination.close();
                    }

                    callback.onWriteFinished(pages);
                } catch (IOException e) {
                    callback.onWriteFailed(e.getMessage());
                }
            }
        }, (PrintAttributes) null);
    }

    @Parameterized.Parameters
    public static Collection<Object[]> getParameters() {
        ArrayList<Object[]> tests = new ArrayList<>();

        for (PrintAttributes.MediaSize first : new PrintAttributes.MediaSize[]{
                PrintAttributes.MediaSize.ISO_A0, null}) {
            for (Boolean selectPrinter : new Boolean[]{true, false}) {
                for (PrintAttributes.MediaSize second : new PrintAttributes.MediaSize[]{
                        PrintAttributes.MediaSize.ISO_A1, null}) {
                    // If we do not use the second printer, no need to try various options
                    if (!selectPrinter && second == null) {
                        continue;
                    }
                    tests.add(new Object[]{first, selectPrinter, second});
                }
            }
        }

        return tests;
    }

    @Test
    @LargeTest
    public void addAndSelectPrinter() throws Exception {
        final StubbablePrinterDiscoverySession session[] = new StubbablePrinterDiscoverySession[1];
        final PrintAttributes printAttributes[] = new PrintAttributes[1];
        ArrayList<String> trackedPrinters = new ArrayList<>();

        Log.i(LOG_TAG, "Running " + mFirst + " " + mSelectPrinter + " " + mSecond);

        setMockPrintServiceCallbacks(session, trackedPrinters, mFirst);
        print(printAttributes);

        // We are now in the PrintActivity
        Log.i(LOG_TAG, "Waiting for session");
        synchronized (session) {
            waitWithTimeout(() -> session[0] == null, session::wait);
        }

        setPrinter("1st printer");

        Log.i(LOG_TAG, "Waiting for 1st printer to be tracked");
        synchronized (trackedPrinters) {
            waitWithTimeout(() -> !trackedPrinters.contains("1st printer"), trackedPrinters::wait);
        }

        if (mFirst != null) {
            Log.i(LOG_TAG, "Waiting for print attributes to change");
            synchronized (printAttributes) {
                waitWithTimeout(
                        () -> printAttributes[0] == null ||
                                !printAttributes[0].getMediaSize().equals(
                                        mFirst), printAttributes::wait);
            }
        } else {
            Log.i(LOG_TAG, "Waiting for error message");
            assertNotNull(getUiDevice().wait(Until.findObject(
                    By.text("This printer isn't available right now.")), OPERATION_TIMEOUT_MILLIS));
        }

        setPrinter("All printers\u2026");

        // We are now in the SelectPrinterActivity
        clickOnText("Add printer");

        // We are now in the AddPrinterActivity
        AddPrintersActivity.addObserver(
                () -> addPrinter(session[0], "2nd printer", mSecond));

        // This executes the observer registered above
        clickOn(new UiSelector().text(FirstPrintService.class.getCanonicalName())
                .resourceId("com.android.printspooler:id/title"));

        getUiDevice().pressBack();
        AddPrintersActivity.clearObservers();

        if (mSelectPrinter) {
            // We are now in the SelectPrinterActivity
            clickOnText("2nd printer");
        } else {
            getUiDevice().pressBack();
        }

        // We are now in the PrintActivity
        if (mSelectPrinter) {
            if (mSecond != null) {
                Log.i(LOG_TAG, "Waiting for print attributes to change");
                synchronized (printAttributes) {
                    waitWithTimeout(
                            () -> printAttributes[0] == null ||
                                    !printAttributes[0].getMediaSize().equals(
                                            mSecond), printAttributes::wait);
                }
            } else {
                Log.i(LOG_TAG, "Waiting for error message");
                assertNotNull(getUiDevice().wait(Until.findObject(
                        By.text("This printer isn't available right now.")),
                        OPERATION_TIMEOUT_MILLIS));
            }

            Log.i(LOG_TAG, "Waiting for 1st printer to be not tracked");
            synchronized (trackedPrinters) {
                waitWithTimeout(() -> trackedPrinters.contains("1st printer"),
                        trackedPrinters::wait);
            }

            Log.i(LOG_TAG, "Waiting for 2nd printer to be tracked");
            synchronized (trackedPrinters) {
                waitWithTimeout(() -> !trackedPrinters.contains("2nd printer"),
                        trackedPrinters::wait);
            }
        } else {
            Thread.sleep(100);

            if (mFirst != null) {
                Log.i(LOG_TAG, "Waiting for print attributes to change");
                synchronized (printAttributes) {
                    waitWithTimeout(
                            () -> printAttributes[0] == null ||
                                    !printAttributes[0].getMediaSize().equals(
                                            mFirst), printAttributes::wait);
                }
            } else {
                Log.i(LOG_TAG, "Waiting for error message");
                assertNotNull(getUiDevice().wait(Until.findObject(
                        By.text("This printer isn't available right now.")),
                        OPERATION_TIMEOUT_MILLIS));
            }

            Log.i(LOG_TAG, "Waiting for 1st printer to be tracked");
            synchronized (trackedPrinters) {
                waitWithTimeout(() -> !trackedPrinters.contains("1st printer"),
                        trackedPrinters::wait);
            }
        }

        getUiDevice().pressBack();

        // We are back in the test activity
        Log.i(LOG_TAG, "Waiting for session to end");
        synchronized (session) {
            waitWithTimeout(() -> session[0] != null, session::wait);
        }
    }
}
