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

package android.print;

import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.mockservice.AddPrintersActivity;
import android.print.mockservice.MockPrintService;

import android.print.mockservice.PrinterDiscoverySessionCallbacks;
import android.print.mockservice.StubbablePrinterDiscoverySession;
import android.print.pdf.PrintedPdfDocument;
import android.support.test.filters.LargeTest;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiSelector;
import android.util.Log;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;

/**
 * Tests for the basic printing workflows
 */
public class WorkflowTest extends BasePrintTest {
    private static final String LOG_TAG = WorkflowTest.class.getSimpleName();

    private static float sWindowAnimationScaleBefore;
    private static float sTransitionAnimationScaleBefore;
    private static float sAnimatiorDurationScaleBefore;

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
            long timeLeft = OPERATION_TIMEOUT - (System.currentTimeMillis() - startTime);
            if (timeLeft < 0) {
                throw new TimeoutException();
            }

            waiter.accept(timeLeft);
        }
    }

    /**
     * Executes a shell command using shell user identity, and return the standard output in
     * string.
     *
     * @param cmd the command to run
     *
     * @return the standard output of the command
     */
    private static String runShellCommand(String cmd) throws IOException {
        try (FileInputStream is = new ParcelFileDescriptor.AutoCloseInputStream(
                getInstrumentation().getUiAutomation().executeShellCommand(cmd))) {
            byte[] buf = new byte[64];
            int bytesRead;

            StringBuilder stdout = new StringBuilder();
            while ((bytesRead = is.read(buf)) != -1) {
                stdout.append(new String(buf, 0, bytesRead));
            }

            return stdout.toString();
        }
    }

    @BeforeClass
    public static void disableAnimations() throws Exception {
        try {
            sWindowAnimationScaleBefore = Float.parseFloat(runShellCommand(
                    "settings get global window_animation_scale"));

            runShellCommand("settings put global window_animation_scale 0");
        } catch (NumberFormatException e) {
            sWindowAnimationScaleBefore = Float.NaN;
        }
        try {
            sTransitionAnimationScaleBefore = Float.parseFloat(runShellCommand(
                    "settings get global transition_animation_scale"));

            runShellCommand("settings put global transition_animation_scale 0");
        } catch (NumberFormatException e) {
            sTransitionAnimationScaleBefore = Float.NaN;
        }
        try {
            sAnimatiorDurationScaleBefore = Float.parseFloat(runShellCommand(
                    "settings get global animator_duration_scale"));

            runShellCommand("settings put global animator_duration_scale 0");
        } catch (NumberFormatException e) {
            sAnimatiorDurationScaleBefore = Float.NaN;
        }
    }

    @AfterClass
    public static void enableAnimations() throws Exception {
        if (sWindowAnimationScaleBefore != Float.NaN) {
            runShellCommand(
                    "settings put global window_animation_scale " + sWindowAnimationScaleBefore);
        }
        if (sTransitionAnimationScaleBefore != Float.NaN) {
            runShellCommand(
                    "settings put global transition_animation_scale " +
                            sTransitionAnimationScaleBefore);
        }
        if (sAnimatiorDurationScaleBefore != Float.NaN) {
            runShellCommand(
                    "settings put global animator_duration_scale " + sAnimatiorDurationScaleBefore);
        }
    }

    /** Add a printer with a given name and supported mediasize to a session */
    private void addPrinter(StubbablePrinterDiscoverySession session,
            String name, PrintAttributes.MediaSize mediaSize) {
        PrinterId printerId = session.getService().generatePrinterId(name);
        List<PrinterInfo> printers = new ArrayList<>(1);

        PrinterCapabilitiesInfo.Builder builder =
                new PrinterCapabilitiesInfo.Builder(printerId);

        builder.setMinMargins(new PrintAttributes.Margins(0, 0, 0, 0))
                .setColorModes(PrintAttributes.COLOR_MODE_COLOR,
                        PrintAttributes.COLOR_MODE_COLOR)
                .addMediaSize(mediaSize, true)
                .addResolution(new PrintAttributes.Resolution("300x300", "300x300", 300, 300),
                        true);

        printers.add(new PrinterInfo.Builder(printerId, name,
                PrinterInfo.STATUS_IDLE).setCapabilities(builder.build()).build());

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
    private void setMockPrintServiceCallbacks(StubbablePrinterDiscoverySession[] sessionRef) {
        MockPrintService.setCallbacks(createMockPrintServiceCallbacks(
                inv -> createMockPrinterDiscoverySessionCallbacks(inv2 -> {
                            synchronized (sessionRef) {
                                sessionRef[0] = ((PrinterDiscoverySessionCallbacks) inv2.getMock())
                                        .getSession();

                                addPrinter(sessionRef[0], "1st printer",
                                        PrintAttributes.MediaSize.ISO_A0);

                                sessionRef.notifyAll();
                            }
                            return null;
                        },
                        null, null, null, null, null, inv2 -> {
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
        }, null);
    }

    @Test
    @LargeTest
    public void addAndSelectPrinter() throws Exception {
        final StubbablePrinterDiscoverySession session[] = new StubbablePrinterDiscoverySession[1];
        final PrintAttributes printAttributes[] = new PrintAttributes[1];

        setMockPrintServiceCallbacks(session);
        print(printAttributes);

        // We are now in the PrintActivity
        Log.i(LOG_TAG, "Waiting for session");
        synchronized (session) {
            waitWithTimeout(() -> session[0] == null, session::wait);
        }

        setPrinter("1st printer");

        Log.i(LOG_TAG, "Waiting for print attributes to change");
        synchronized (printAttributes) {
            waitWithTimeout(
                    () -> printAttributes[0] == null || !printAttributes[0].getMediaSize().equals(
                            PrintAttributes.MediaSize.ISO_A0), printAttributes::wait);
        }

        setPrinter("All printers\u2026");

        // We are now in the SelectPrinterActivity
        clickOnText("Add printer");

        // We are now in the AddPrinterActivity
        AddPrintersActivity.addObserver(
                () -> addPrinter(session[0], "2nd printer", PrintAttributes.MediaSize.ISO_A1));

        // This executes the observer registered above
        clickOn(new UiSelector().text(MockPrintService.class.getCanonicalName())
                        .resourceId("com.android.printspooler:id/title"));

        getUiDevice().pressBack();
        AddPrintersActivity.clearObservers();

        // We are now in the SelectPrinterActivity
        clickOnText("2nd printer");

        // We are now in the PrintActivity
        Log.i(LOG_TAG, "Waiting for print attributes to change");
        synchronized (printAttributes) {
            waitWithTimeout(
                    () -> printAttributes[0] == null || !printAttributes[0].getMediaSize().equals(
                            PrintAttributes.MediaSize.ISO_A1), printAttributes::wait);
        }

        getUiDevice().pressBack();

        // We are back in the test activity
        Log.i(LOG_TAG, "Waiting for session to end");
        synchronized (session) {
            waitWithTimeout(() -> session[0] != null, session::wait);
        }
    }

    @Test
    @LargeTest
    public void abortSelectingPrinter() throws Exception {
        final StubbablePrinterDiscoverySession session[] = new StubbablePrinterDiscoverySession[1];
        final PrintAttributes printAttributes[] = new PrintAttributes[1];

        setMockPrintServiceCallbacks(session);
        print(printAttributes);

        // We are now in the PrintActivity
        Log.i(LOG_TAG, "Waiting for session");
        synchronized (session) {
            waitWithTimeout(() -> session[0] == null, session::wait);
        }

        setPrinter("1st printer");

        Log.i(LOG_TAG, "Waiting for print attributes to change");
        synchronized (printAttributes) {
            waitWithTimeout(
                    () -> printAttributes[0] == null || !printAttributes[0].getMediaSize().equals(
                            PrintAttributes.MediaSize.ISO_A0), printAttributes::wait);
        }

        setPrinter("All printers\u2026");

        // We are now in the SelectPrinterActivity
        clickOnText("Add printer");

        // We are now in the AddPrinterActivity
        AddPrintersActivity.addObserver(
                () -> addPrinter(session[0], "2nd printer", PrintAttributes.MediaSize.ISO_A1));

        // This executes the observer registered above
        clickOn(new UiSelector().text(MockPrintService.class.getCanonicalName())
                .resourceId("com.android.printspooler:id/title"));

        getUiDevice().pressBack();
        AddPrintersActivity.clearObservers();

        // Do not select a new printer, just press back
        getUiDevice().pressBack();

        // We are now in the PrintActivity
        // The media size should not change
        Log.i(LOG_TAG, "Make sure print attributes did not change");
        Thread.sleep(100);
        assertEquals(PrintAttributes.MediaSize.ISO_A0, printAttributes[0].getMediaSize());

        getUiDevice().pressBack();

        // We are back in the test activity
        Log.i(LOG_TAG, "Waiting for session to end");
        synchronized (session) {
            waitWithTimeout(() -> session[0] != null, session::wait);
        }
    }
}
