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

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.printservice.PrintServiceInfo;
import android.printservice.recommendation.IRecommendationsChangeListener;

import android.print.mockservice.MockPrintService;
import android.print.mockservice.PrintServiceCallbacks;
import android.print.mockservice.PrinterDiscoverySessionCallbacks;
import android.print.mockservice.StubbablePrinterDiscoverySession;

import android.support.test.filters.LargeTest;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * tests feeding all possible parameters to the IPrintManager Binder.
 */
@RunWith(AndroidJUnit4.class)
public class IPrintManagerParametersTest extends BasePrintTest {

    private final int BAD_APP_ID = 0xffffffff;

    private final int mAppId;
    private final int mUserId;
    private final PrintJobId mBadPrintJobId;

    private PrintJob mGoodPrintJob;
    private PrinterId mGoodPrinterId;
    private ComponentName mBadComponentName;

    private IPrintManager mIPrintManager;

    /**
     * Create a new IPrintManagerParametersTest and setup basic fields.
     */
    public IPrintManagerParametersTest() {
        super();

        mAppId = UserHandle.getAppId(Process.myUid());
        mUserId = UserHandle.myUserId();
        mBadPrintJobId = new PrintJobId();
        mBadComponentName = new ComponentName("bad", "bad");
    }

    /**
     * Create a mock PrintDocumentAdapter.
     *
     * @return The adapter
     */
    private @NonNull PrintDocumentAdapter createMockAdapter() {
        return new PrintDocumentAdapter() {
            @Override
            public void onStart() {
                onStartCalled();
            }

            @Override
            public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                    CancellationSignal cancellationSignal, LayoutResultCallback callback,
                    Bundle extras) {
                callback.onLayoutFailed("not implemented");
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                    CancellationSignal cancellationSignal, WriteResultCallback callback) {
            }
        };
    }

    /**
     * Create mock print service callbacks.
     *
     * @return the callbacks
     */
    private PrintServiceCallbacks createMockCallbacks() {
        return createMockPrintServiceCallbacks(
                invocation -> createMockPrinterDiscoverySessionCallbacks(invocation1 -> {
                    // Get the session.
                    StubbablePrinterDiscoverySession session =
                            ((PrinterDiscoverySessionCallbacks) invocation1
                            .getMock()).getSession();

                    if (session.getPrinters().isEmpty()) {
                        final String PRINTER_NAME = "good printer";
                        List<PrinterInfo> printers = new ArrayList<>();

                        // Add the printer.
                        mGoodPrinterId = session.getService()
                                .generatePrinterId(PRINTER_NAME);

                        PrinterCapabilitiesInfo capabilities =
                                new PrinterCapabilitiesInfo.Builder(mGoodPrinterId)
                                        .setMinMargins(
                                                new Margins(200, 200, 200, 200))
                                        .addMediaSize(MediaSize.ISO_A4, true)
                                        .addResolution(new Resolution("300x300",
                                                "300x300", 300, 300),
                                                true)
                                        .setColorModes(
                                                PrintAttributes.COLOR_MODE_COLOR,
                                                PrintAttributes.COLOR_MODE_COLOR)
                                        .build();

                        PrinterInfo printer = new PrinterInfo.Builder(
                                mGoodPrinterId,
                                PRINTER_NAME,
                                PrinterInfo.STATUS_IDLE)
                                        .setCapabilities(capabilities)
                                        .build();
                        printers.add(printer);

                        session.addPrinters(printers);
                    }
                    onPrinterDiscoverySessionStartCalled();
                    return null;
                }, null, null, null, null, null, null),
                null, null);
    }

    /**
     * Create a IPrintJobStateChangeListener object.
     *
     * @return the object
     * @throws Exception if the object could not be created.
     */
    private IPrintJobStateChangeListener createMockIPrintJobStateChangeListener() throws Exception {
        return new PrintManager.PrintJobStateChangeListenerWrapper(null,
                new Handler(Looper.getMainLooper()));
    }

    /**
     * Create a IPrintServicesChangeListener object.
     *
     * @return the object
     * @throws Exception if the object could not be created.
     */
    private IPrintServicesChangeListener createMockIPrintServicesChangeListener() throws Exception {
        return new PrintManager.PrintServicesChangeListenerWrapper(null,
                new Handler(Looper.getMainLooper()));
    }

    /**
     * Create a IPrintServiceRecommendationsChangeListener object.
     *
     * @return the object
     * @throws Exception if the object could not be created.
     */
    private IRecommendationsChangeListener
    createMockIPrintServiceRecommendationsChangeListener() throws Exception {
        return new PrintManager.PrintServiceRecommendationsChangeListenerWrapper(null,
                new Handler(Looper.getMainLooper()));
    }

    /**
     * Create a IPrinterDiscoveryObserver object.
     *
     * @return the object
     * @throws Exception if the object could not be created.
     */
    private IPrinterDiscoveryObserver createMockIPrinterDiscoveryObserver() throws Exception {
        return new PrinterDiscoverySession.PrinterDiscoveryObserver(null);
    }

    private void startPrinting() {
        mGoodPrintJob = print(createMockAdapter(), null);

        // Wait for PrintActivity to be ready
        waitForStartAdapterCallbackCalled();

        // Wait for printer discovery session to be ready
        waitForPrinterDiscoverySessionStartCallbackCalled();
    }

    /**
     * Return a printer Id that is not from any print service
     *
     * @return The bad printer id.
     */
    private PrinterId getBadPrinterId() {
        return new PrinterId(getActivity().getComponentName(), "dummy printer");
    }

    @Before
    public void setUpMockService() throws Exception {
        MockPrintService.setCallbacks(createMockCallbacks());

        mIPrintManager = IPrintManager.Stub
                .asInterface(ServiceManager.getService(Context.PRINT_SERVICE));
    }

    /**
     * test IPrintManager.getPrintJobInfo
     */
    @LargeTest
    @Test
    public void testGetPrintJobInfo() throws Exception {
        startPrinting();

        assertEquals(mGoodPrintJob.getId(), mIPrintManager.getPrintJobInfo(mGoodPrintJob.getId(),
                        mAppId, mUserId).getId());
        assertEquals(null, mIPrintManager.getPrintJobInfo(mBadPrintJobId, mAppId, mUserId));
        assertEquals(null, mIPrintManager.getPrintJobInfo(null, mAppId, mUserId));

        assertException(
                () -> mIPrintManager.getPrintJobInfo(mGoodPrintJob.getId(), BAD_APP_ID, mUserId),
                SecurityException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.getPrintJobInfos
     */
    @LargeTest
    @Test
    public void testGetPrintJobInfos() throws Exception {
        startPrinting();

        List<PrintJobInfo> infos = mIPrintManager.getPrintJobInfos(mAppId, mUserId);

        boolean foundPrintJob = false;
        for (PrintJobInfo info : infos) {
            if (info.getId().equals(mGoodPrintJob.getId())) {
                assertEquals(PrintJobInfo.STATE_CREATED, info.getState());
                foundPrintJob = true;
            }
        }
        assertTrue(foundPrintJob);

        assertException(() -> mIPrintManager.getPrintJobInfos(BAD_APP_ID, mUserId),
                SecurityException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.print
     */
    @LargeTest
    @Test
    public void testPrint() throws Exception {
        final String name = "dummy print job";

        final IPrintDocumentAdapter adapter = new PrintManager
                .PrintDocumentAdapterDelegate(getActivity(), createMockAdapter());

        startPrinting();

        assertException(() -> mIPrintManager.print(null, adapter, null,
                getActivity().getPackageName(), mAppId, mUserId),
                IllegalArgumentException.class);

        assertException(() -> mIPrintManager.print(name, null, null,
                getActivity().getPackageName(), mAppId, mUserId),
                NullPointerException.class);

        assertException(() -> mIPrintManager.print(name, adapter, null, null, mAppId, mUserId),
                IllegalArgumentException.class);

        assertException(() -> mIPrintManager.print(name, adapter, null,
                mBadComponentName.getPackageName(), mAppId, mUserId),
                IllegalArgumentException.class);

        assertException(() -> mIPrintManager.print(name, adapter, null,
                getActivity().getPackageName(), BAD_APP_ID, mUserId), SecurityException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.cancelPrintJob
     */
    @LargeTest
    @Test
    public void testCancelPrintJob() throws Exception {
        startPrinting();

        // Invalid print jobs IDs do not produce an exception
        mIPrintManager.cancelPrintJob(mBadPrintJobId, mAppId, mUserId);
        mIPrintManager.cancelPrintJob(null, mAppId, mUserId);

        assertException(
                () -> mIPrintManager.cancelPrintJob(mGoodPrintJob.getId(), BAD_APP_ID, mUserId),
                SecurityException.class);

        // Cannot test bad user Id as these tests are allowed to call across users

        // Must be last as otherwise mGoodPrintJob will not be good anymore
        mIPrintManager.cancelPrintJob(mGoodPrintJob.getId(), mAppId, mUserId);
    }

    /**
     * test IPrintManager.restartPrintJob
     */
    @LargeTest
    @Test
    public void testRestartPrintJob() throws Exception {
        startPrinting();

        mIPrintManager.restartPrintJob(mGoodPrintJob.getId(), mAppId, mUserId);

        // Invalid print jobs IDs do not produce an exception
        mIPrintManager.restartPrintJob(mBadPrintJobId, mAppId, mUserId);
        mIPrintManager.restartPrintJob(null, mAppId, mUserId);

        assertException(
                () -> mIPrintManager.restartPrintJob(mGoodPrintJob.getId(), BAD_APP_ID, mUserId),
                SecurityException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.addPrintJobStateChangeListener
     */
    @MediumTest
    @Test
    public void testAddPrintJobStateChangeListener() throws Exception {
        final IPrintJobStateChangeListener listener = createMockIPrintJobStateChangeListener();

        mIPrintManager.addPrintJobStateChangeListener(listener, mAppId, mUserId);

        assertException(() -> mIPrintManager.addPrintJobStateChangeListener(null, mAppId, mUserId),
                NullPointerException.class);

        assertException(
                () -> mIPrintManager.addPrintJobStateChangeListener(listener, BAD_APP_ID, mUserId),
                SecurityException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.removePrintJobStateChangeListener
     */
    @MediumTest
    @Test
    public void testRemovePrintJobStateChangeListener() throws Exception {
        final IPrintJobStateChangeListener listener = createMockIPrintJobStateChangeListener();

        mIPrintManager.addPrintJobStateChangeListener(listener, mAppId, mUserId);
        mIPrintManager.removePrintJobStateChangeListener(listener, mUserId);

        // Removing unknown listeners is a no-op
        mIPrintManager.removePrintJobStateChangeListener(listener, mUserId);

        mIPrintManager.addPrintJobStateChangeListener(listener, mAppId, mUserId);
        assertException(() -> mIPrintManager.removePrintJobStateChangeListener(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.addPrintServicesChangeListener
     */
    @MediumTest
    @Test
    public void testAddPrintServicesChangeListener() throws Exception {
        final IPrintServicesChangeListener listener = createMockIPrintServicesChangeListener();

        mIPrintManager.addPrintServicesChangeListener(listener, mUserId);

        assertException(() -> mIPrintManager.addPrintServicesChangeListener(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.removePrintServicesChangeListener
     */
    @MediumTest
    @Test
    public void testRemovePrintServicesChangeListener() throws Exception {
        final IPrintServicesChangeListener listener = createMockIPrintServicesChangeListener();

        mIPrintManager.addPrintServicesChangeListener(listener, mUserId);
        mIPrintManager.removePrintServicesChangeListener(listener, mUserId);

        // Removing unknown listeners is a no-op
        mIPrintManager.removePrintServicesChangeListener(listener, mUserId);

        mIPrintManager.addPrintServicesChangeListener(listener, mUserId);
        assertException(() -> mIPrintManager.removePrintServicesChangeListener(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.getPrintServices
     */
    @MediumTest
    @Test
    public void testGetPrintServices() throws Exception {
        List<PrintServiceInfo> printServices = mIPrintManager.getPrintServices(
                PrintManager.ALL_SERVICES, mUserId);
        assertTrue(printServices.size() >= 1);

        printServices = mIPrintManager.getPrintServices(0, mUserId);
        assertEquals(printServices, null);

        assertException(() -> mIPrintManager.getPrintServices(~PrintManager.ALL_SERVICES, mUserId),
                IllegalArgumentException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.setPrintServiceEnabled
     */
    @MediumTest
    @Test
    public void testSetPrintServiceEnabled() throws Exception {
        final ComponentName printService = mIPrintManager.getPrintServices(
                PrintManager.ALL_SERVICES, mUserId).get(0).getComponentName();

        assertException(() -> mIPrintManager.setPrintServiceEnabled(printService, false, mUserId),
                SecurityException.class);

        assertException(() -> mIPrintManager.setPrintServiceEnabled(printService, true, mUserId),
                SecurityException.class);

        assertException(
                () -> mIPrintManager.setPrintServiceEnabled(new ComponentName("bad", "name"), true,
                                mUserId), SecurityException.class);

        assertException(() -> mIPrintManager.setPrintServiceEnabled(null, true, mUserId),
                SecurityException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.addPrintServiceRecommendationsChangeListener
     */
    @MediumTest
    @Test
    public void testAddPrintServiceRecommendationsChangeListener() throws Exception {
        final IRecommendationsChangeListener listener =
                createMockIPrintServiceRecommendationsChangeListener();

        mIPrintManager.addPrintServiceRecommendationsChangeListener(listener, mUserId);

        assertException(
                () -> mIPrintManager.addPrintServiceRecommendationsChangeListener(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.removePrintServicesChangeListener
     */
    @MediumTest
    @Test
    public void testRemovePrintServiceRecommendationsChangeListener() throws Exception {
        final IRecommendationsChangeListener listener =
                createMockIPrintServiceRecommendationsChangeListener();

        mIPrintManager.addPrintServiceRecommendationsChangeListener(listener, mUserId);
        mIPrintManager.removePrintServiceRecommendationsChangeListener(listener, mUserId);

        // Removing unknown listeners is a no-op
        mIPrintManager.removePrintServiceRecommendationsChangeListener(listener, mUserId);

        mIPrintManager.addPrintServiceRecommendationsChangeListener(listener, mUserId);
        assertException(
                () -> mIPrintManager.removePrintServiceRecommendationsChangeListener(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.getPrintServiceRecommendations
     */
    @MediumTest
    @Test
    public void testGetPrintServiceRecommendations() throws Exception {
        mIPrintManager.getPrintServiceRecommendations(mUserId);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.createPrinterDiscoverySession
     */
    @MediumTest
    @Test
    public void testCreatePrinterDiscoverySession() throws Exception {
        final IPrinterDiscoveryObserver listener = createMockIPrinterDiscoveryObserver();

        mIPrintManager.createPrinterDiscoverySession(listener, mUserId);

        try {
            assertException(() -> mIPrintManager.createPrinterDiscoverySession(null, mUserId),
                    NullPointerException.class);

            // Cannot test bad user Id as these tests are allowed to call across users
        } finally {
            // Remove discovery session so that the next test create a new one. Usually a leaked
            // session is removed on the next call from the print service. But in this case we want
            // to force a new call to onPrinterDiscoverySessionStart in the next test.
            mIPrintManager.destroyPrinterDiscoverySession(listener, mUserId);
        }
    }

    /**
     * test IPrintManager.startPrinterDiscovery
     */
    @LargeTest
    @Test
    public void testStartPrinterDiscovery() throws Exception {
        startPrinting();

        final IPrinterDiscoveryObserver listener = createMockIPrinterDiscoveryObserver();
        final List<PrinterId> goodPrinters = new ArrayList<>();
        goodPrinters.add(mGoodPrinterId);

        final List<PrinterId> badPrinters = new ArrayList<>();
        badPrinters.add(getBadPrinterId());

        final List<PrinterId> emptyPrinters = new ArrayList<>();

        final List<PrinterId> nullPrinters = new ArrayList<>();
        nullPrinters.add(null);

        mIPrintManager.startPrinterDiscovery(listener, goodPrinters, mUserId);

        // Bad or no printers do no cause exceptions
        mIPrintManager.startPrinterDiscovery(listener, badPrinters, mUserId);
        mIPrintManager.startPrinterDiscovery(listener, emptyPrinters, mUserId);
        mIPrintManager.startPrinterDiscovery(listener, null, mUserId);

        assertException(() -> mIPrintManager.startPrinterDiscovery(listener, nullPrinters, mUserId),
                NullPointerException.class);

        assertException(() -> mIPrintManager.startPrinterDiscovery(null, goodPrinters, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.stopPrinterDiscovery
     */
    @MediumTest
    @Test
    public void testStopPrinterDiscovery() throws Exception {
        final IPrinterDiscoveryObserver listener = createMockIPrinterDiscoveryObserver();

        mIPrintManager.startPrinterDiscovery(listener, null, mUserId);
        mIPrintManager.stopPrinterDiscovery(listener, mUserId);

        // Stopping an already stopped session is a no-op
        mIPrintManager.stopPrinterDiscovery(listener, mUserId);

        mIPrintManager.startPrinterDiscovery(listener, null, mUserId);
        assertException(() -> mIPrintManager.stopPrinterDiscovery(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.validatePrinters
     */
    @LargeTest
    @Test
    public void testValidatePrinters() throws Exception {
        startPrinting();

        final List<PrinterId> goodPrinters = new ArrayList<>();
        goodPrinters.add(mGoodPrinterId);

        final List<PrinterId> badPrinters = new ArrayList<>();
        badPrinters.add(getBadPrinterId());

        final List<PrinterId> emptyPrinters = new ArrayList<>();

        final List<PrinterId> nullPrinters = new ArrayList<>();
        nullPrinters.add(null);

        mIPrintManager.validatePrinters(goodPrinters, mUserId);

        // Bad or empty list of printers do no cause exceptions
        mIPrintManager.validatePrinters(badPrinters, mUserId);
        mIPrintManager.validatePrinters(emptyPrinters, mUserId);

        assertException(() -> mIPrintManager.validatePrinters(null, mUserId),
                NullPointerException.class);

        assertException(() -> mIPrintManager.validatePrinters(nullPrinters, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.startPrinterStateTracking
     */
    @LargeTest
    @Test
    public void testStartPrinterStateTracking() throws Exception {
        startPrinting();

        mIPrintManager.startPrinterStateTracking(mGoodPrinterId, mUserId);

        // Bad printers do no cause exceptions
        mIPrintManager.startPrinterStateTracking(getBadPrinterId(), mUserId);

        assertException(() -> mIPrintManager.startPrinterStateTracking(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.getCustomPrinterIcon
     */
    @LargeTest
    @Test
    public void testGetCustomPrinterIcon() throws Exception {
        startPrinting();

        mIPrintManager.getCustomPrinterIcon(mGoodPrinterId, mUserId);

        // Bad printers do no cause exceptions
        mIPrintManager.getCustomPrinterIcon(getBadPrinterId(), mUserId);

        assertException(() -> mIPrintManager.getCustomPrinterIcon(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.stopPrinterStateTracking
     */
    @LargeTest
    @Test
    public void testStopPrinterStateTracking() throws Exception {
        startPrinting();

        mIPrintManager.startPrinterStateTracking(mGoodPrinterId, mUserId);
        mIPrintManager.stopPrinterStateTracking(mGoodPrinterId, mUserId);

        // Stop to track a non-tracked printer is a no-op
        mIPrintManager.stopPrinterStateTracking(mGoodPrinterId, mUserId);

        // Bad printers do no cause exceptions
        mIPrintManager.startPrinterStateTracking(getBadPrinterId(), mUserId);
        mIPrintManager.stopPrinterStateTracking(getBadPrinterId(), mUserId);

        assertException(() -> mIPrintManager.stopPrinterStateTracking(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }

    /**
     * test IPrintManager.destroyPrinterDiscoverySession
     */
    @MediumTest
    @Test
    public void testDestroyPrinterDiscoverySession() throws Exception {
        final IPrinterDiscoveryObserver listener = createMockIPrinterDiscoveryObserver();

        mIPrintManager.createPrinterDiscoverySession(listener, mUserId);
        mIPrintManager.destroyPrinterDiscoverySession(listener, mUserId);

        // Destroying already destroyed session is a no-op
        mIPrintManager.destroyPrinterDiscoverySession(listener, mUserId);

        assertException(() -> mIPrintManager.destroyPrinterDiscoverySession(null, mUserId),
                NullPointerException.class);

        // Cannot test bad user Id as these tests are allowed to call across users
    }
}
