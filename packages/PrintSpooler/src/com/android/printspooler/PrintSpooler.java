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

import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.print.IPrintClient;
import android.print.IPrinterDiscoveryObserver;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintAttributes.Tray;
import android.print.PrintDocumentInfo;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterId;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PrintSpooler {

    private static final String LOG_TAG = "PrintSpooler";

    private static final boolean DEBUG_PRINT_JOB_LIFECYCLE = true;

    private static final boolean DEBUG_PERSISTENCE = true;

    private static final boolean PERSISTNECE_MANAGER_ENABLED = true;

    private static final String PRINT_FILE_EXTENSION = "pdf";

    private static int sPrintJobIdCounter;

    private static final Object sLock = new Object();

    private static PrintSpooler sInstance;

    private final Object mLock = new Object();

    private final List<PrintJobInfo> mPrintJobs = new ArrayList<PrintJobInfo>();

    private final PersistenceManager mPersistanceManager;

    private final NotificationController mNotificationController;

    private final PrintSpoolerService mService;

    public static void destroyInstance() {
        synchronized (sLock) {
            sInstance = null;
        }
    }

    public static void createInstance(PrintSpoolerService service) {
        synchronized (sLock) {
            sInstance = new PrintSpooler(service);
        }
    }

    public static PrintSpooler peekInstance() {
        synchronized (sLock) {
            return sInstance;
        }
    }

    private PrintSpooler(PrintSpoolerService service) {
        mService = service;
        mPersistanceManager = new PersistenceManager(service);
        mNotificationController = new NotificationController(service);
        synchronized (mLock) {
            mPersistanceManager.readStateLocked();
            handleReadPrintJobsLocked();
        }
    }

    public List<PrintJobInfo> getPrintJobInfos(ComponentName componentName,
            int state, int appId) {
        List<PrintJobInfo> foundPrintJobs = null;
        synchronized (mLock) {
            final int printJobCount = mPrintJobs.size();
            for (int i = 0; i < printJobCount; i++) {
                PrintJobInfo printJob = mPrintJobs.get(i);
                PrinterId printerId = printJob.getPrinterId();
                final boolean sameComponent = (componentName == null
                        || (printerId != null
                        && componentName.equals(printerId.getServiceName())));
                final boolean sameAppId = appId == PrintManager.APP_ID_ANY
                        || printJob.getAppId() == appId;
                final boolean sameState = (state == printJob.getState())
                        || (state == PrintJobInfo.STATE_ANY)
                        || (state == PrintJobInfo.STATE_ANY_VISIBLE_TO_CLIENTS
                                && printJob.getState() > PrintJobInfo.STATE_CREATED);
                if (sameComponent && sameAppId && sameState) {
                    if (foundPrintJobs == null) {
                        foundPrintJobs = new ArrayList<PrintJobInfo>();
                    }
                    foundPrintJobs.add(printJob);
                }
            }
        }
        return foundPrintJobs;
    }

    public PrintJobInfo getPrintJobInfo(int printJobId, int appId) {
        synchronized (mLock) {
            final int printJobCount = mPrintJobs.size();
            for (int i = 0; i < printJobCount; i++) {
                PrintJobInfo printJob = mPrintJobs.get(i);
                if (printJob.getId() == printJobId
                        && (appId == PrintManager.APP_ID_ANY
                                || appId == printJob.getAppId())) {
                    return printJob;
                }
             }
             return null;
        }
    }

    public PrintJobInfo createPrintJob(CharSequence label, IPrintClient client,
            PrintAttributes attributes, int appId) {
        synchronized (mLock) {
            final int printJobId = generatePrintJobIdLocked();
            PrintJobInfo printJob = new PrintJobInfo();
            printJob.setId(printJobId);
            printJob.setAppId(appId);
            printJob.setLabel(label);
            printJob.setAttributes(attributes);
            printJob.setState(PrintJobInfo.STATE_CREATED);

            addPrintJobLocked(printJob);

            return printJob;
        }
    }

    private void handleReadPrintJobsLocked() {
        final int printJobCount = mPrintJobs.size();
        for (int i = 0; i < printJobCount; i++) {
            PrintJobInfo printJob = mPrintJobs.get(i);

            // Update the notification.
            mNotificationController.onPrintJobStateChanged(printJob);

            //TODO: Figure out what the right policy for read print jobs is.

            switch (printJob.getState()) {
                case PrintJobInfo.STATE_QUEUED: {
                    // Notify that we have a queued job.
                    mService.onPrintJobQueued(new PrintJobInfo(printJob));
                } break;

                case PrintJobInfo.STATE_STARTED: {
                    // We really want to restart this print job.
                    setPrintJobState(printJob.getId(), PrintJobInfo.STATE_QUEUED, null);
                } break;
            }
        }
    }

    public void checkAllPrintJobsHandled() {
        synchronized (mLock) {
            if (!hasActivePrintJobsLocked()) {
                notifyOnAllPrintJobsHandled();
            }
        }
    }

    public void startPrinterDiscovery(IPrinterDiscoveryObserver observer) {
        mService.startPrinterDiscovery(observer);
    }

    public void stopPrinterDiscovery() {
        mService.stopPrinterDiscovery();
    }

    public void onReqeustUpdatePrinters(List<PrinterId> printerIds) {
        mService.onReqeustUpdatePrinters(printerIds);
    }

    private int generatePrintJobIdLocked() {
        int printJobId = sPrintJobIdCounter++;
        while (isDuplicatePrintJobId(printJobId)) {
            printJobId = sPrintJobIdCounter++;
        }
        return printJobId;
    }

    private boolean isDuplicatePrintJobId(int printJobId) {
        final int printJobCount = mPrintJobs.size();
        for (int j = 0; j < printJobCount; j++) {
            PrintJobInfo printJob = mPrintJobs.get(j);
            if (printJob.getId() == printJobId) {
                return true;
            }
        }
        return false;
    }

    public void writePrintJobData(final ParcelFileDescriptor fd, final int printJobId) {
        final PrintJobInfo printJob;
        synchronized (mLock) {
            printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
        }
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                FileInputStream in = null;
                FileOutputStream out = null;
                try {
                    if (printJob != null) {
                        File file = generateFileForPrintJob(printJobId);
                        in = new FileInputStream(file);
                        out = new FileOutputStream(fd.getFileDescriptor());
                    }
                    final byte[] buffer = new byte[8192];
                    while (true) {
                        final int readByteCount = in.read(buffer);
                        if (readByteCount < 0) {
                            return null;
                        }
                        out.write(buffer, 0, readByteCount);
                    }
                } catch (FileNotFoundException fnfe) {
                    Log.e(LOG_TAG, "Error writing print job data!", fnfe);
                } catch (IOException ioe) {
                    Log.e(LOG_TAG, "Error writing print job data!", ioe);
                } finally {
                    IoUtils.closeQuietly(in);
                    IoUtils.closeQuietly(out);
                    IoUtils.closeQuietly(fd);
                }
                Log.i(LOG_TAG, "[END WRITE]");
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
    }

    public File generateFileForPrintJob(int printJobId) {
        return new File(mService.getFilesDir(), "print_job_"
                + printJobId + "." + PRINT_FILE_EXTENSION);
    }

    private void addPrintJobLocked(PrintJobInfo printJob) {
        mPrintJobs.add(printJob);
        if (DEBUG_PRINT_JOB_LIFECYCLE) {
            Slog.i(LOG_TAG, "[ADD] " + printJob);
        }
    }

    private void removePrintJobLocked(PrintJobInfo printJob) {
        if (mPrintJobs.remove(printJob)) {
            generateFileForPrintJob(printJob.getId()).delete();
            if (DEBUG_PRINT_JOB_LIFECYCLE) {
                Slog.i(LOG_TAG, "[REMOVE] " + printJob);
            }
        }
    }

    public boolean setPrintJobState(int printJobId, int state, CharSequence error) {
        boolean success = false;

        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                success = true;

                printJob.setState(state);
                printJob.setFailureReason(error);
                mNotificationController.onPrintJobStateChanged(printJob);

                if (DEBUG_PRINT_JOB_LIFECYCLE) {
                    Slog.i(LOG_TAG, "[STATE CHANGED] " + printJob);
                }

                switch (state) {
                    case PrintJobInfo.STATE_COMPLETED:
                    case PrintJobInfo.STATE_CANCELED:
                        removePrintJobLocked(printJob);
                        // $fall-through$
                    case PrintJobInfo.STATE_FAILED: {
                        PrinterId printerId = printJob.getPrinterId();
                        if (printerId != null) {
                            ComponentName service = printerId.getServiceName();
                            if (!hasActivePrintJobsForServiceLocked(service)) {
                                mService.onAllPrintJobsForServiceHandled(service);
                            }
                        }
                    } break;

                    case PrintJobInfo.STATE_QUEUED: {
                        mService.onPrintJobQueued(new PrintJobInfo(printJob));
                    } break;
                }

                if (shouldPersistPrintJob(printJob)) {
                    mPersistanceManager.writeStateLocked();
                }

                if (!hasActivePrintJobsLocked()) {
                    notifyOnAllPrintJobsHandled();
                }
            }
        }

        return success;
    }

    public boolean hasActivePrintJobsLocked() {
        final int printJobCount = mPrintJobs.size();
        for (int i = 0; i < printJobCount; i++) {
            PrintJobInfo printJob = mPrintJobs.get(i);
            if (isActiveState(printJob.getState())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasActivePrintJobsForServiceLocked(ComponentName service) {
        final int printJobCount = mPrintJobs.size();
        for (int i = 0; i < printJobCount; i++) {
            PrintJobInfo printJob = mPrintJobs.get(i);
            if (isActiveState(printJob.getState())
                    && printJob.getPrinterId().getServiceName().equals(service)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isActiveState(int printJobState) {
        return printJobState == PrintJobInfo.STATE_CREATED
                || printJobState == PrintJobInfo.STATE_QUEUED
                || printJobState == PrintJobInfo.STATE_STARTED;
    }

    public boolean setPrintJobTag(int printJobId, String tag) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                String printJobTag = printJob.getTag();
                if (printJobTag == null) {
                    if (tag == null) {
                        return false;
                    }
                } else if (printJobTag.equals(tag)) {
                    return false;
                }
                printJob.setTag(tag);
                if (shouldPersistPrintJob(printJob)) {
                    mPersistanceManager.writeStateLocked();
                }
                return true;
            }
        }
        return false;
    }

    public void setPrintJobCopiesNoPersistence(int printJobId, int copies) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                printJob.setCopies(copies);
            }
        }
    }

    public void setPrintJobPrintDocumentInfoNoPersistence(int printJobId, PrintDocumentInfo info) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                printJob.setDocumentInfo(info);
            }
        }
    }

    public void setPrintJobAttributesNoPersistence(int printJobId, PrintAttributes attributes) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                printJob.setAttributes(attributes);
            }
        }
    }

    public void setPrintJobPrinterIdNoPersistence(int printJobId, PrinterId printerId) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                printJob.setPrinterId(printerId);
            }
        }
    }

    public void setPrintJobPagesNoPersistence(int printJobId, PageRange[] pages) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                printJob.setPages(pages);
            }
        }
    }

    private boolean shouldPersistPrintJob(PrintJobInfo printJob) {
        return printJob.getState() >= PrintJobInfo.STATE_QUEUED;
    }

    private void notifyOnAllPrintJobsHandled() {
        // This has to run on the tread that is persisting the current state
        // since this call may result in the system unbinding from the spooler
        // and as a result the spooler process may get killed before the write
        // completes.
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                mService.onAllPrintJobsHandled();
                return null;
            }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
    }

    private final class PersistenceManager {
        private static final String PERSIST_FILE_NAME = "print_spooler_state.xml";

        private static final String TAG_SPOOLER = "spooler";
        private static final String TAG_JOB = "job";

        private static final String TAG_PRINTER_ID = "printerId";
        private static final String TAG_PAGE_RANGE = "pageRange";
        private static final String TAG_ATTRIBUTES = "attributes";
        private static final String TAG_DOCUMENT_INFO = "documentInfo";

        private static final String ATTR_ID = "id";
        private static final String ATTR_LABEL = "label";
        private static final String ATTR_STATE = "state";
        private static final String ATTR_APP_ID = "appId";
        private static final String ATTR_USER_ID = "userId";
        private static final String ATTR_TAG = "tag";
        private static final String ATTR_COPIES = "copies";

        private static final String TAG_MEDIA_SIZE = "mediaSize";
        private static final String TAG_RESOLUTION = "resolution";
        private static final String TAG_MARGINS = "margins";
        private static final String TAG_INPUT_TRAY = "inputTray";
        private static final String TAG_OUTPUT_TRAY = "outputTray";

        private static final String ATTR_DUPLEX_MODE = "duplexMode";
        private static final String ATTR_COLOR_MODE = "colorMode";
        private static final String ATTR_FITTING_MODE = "fittingMode";
        private static final String ATTR_ORIENTATION = "orientation";

        private static final String ATTR_PRINTER_NAME = "printerName";
        private static final String ATTR_SERVICE_NAME = "serviceName";

        private static final String ATTR_WIDTH_MILS = "widthMils";
        private static final String ATTR_HEIGHT_MILS = "heightMils";

        private static final String ATTR_HORIZONTAL_DPI = "horizontalDip";
        private static final String ATTR_VERTICAL_DPI = "verticalDpi";

        private static final String ATTR_LEFT_MILS = "leftMils";
        private static final String ATTR_TOP_MILS = "topMils";
        private static final String ATTR_RIGHT_MILS = "rightMils";
        private static final String ATTR_BOTTOM_MILS = "bottomMils";

        private static final String ATTR_START = "start";
        private static final String ATTR_END = "end";

        private static final String ATTR_PAGE_COUNT = "pageCount";
        private static final String ATTR_CONTENT_TYPE = "contentType";

        private final AtomicFile mStatePersistFile;

        private boolean mWriteStateScheduled;

        private PersistenceManager(Context context) {
            mStatePersistFile = new AtomicFile(new File(context.getFilesDir(),
                    PERSIST_FILE_NAME));
        }

        public void writeStateLocked() {
            if (!PERSISTNECE_MANAGER_ENABLED) {
                return;
            }
            if (mWriteStateScheduled) {
                return;
            }
            mWriteStateScheduled = true;
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    synchronized (mLock) {
                        mWriteStateScheduled = false;
                        doWriteStateLocked();
                    }
                    return null;
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }

        private void doWriteStateLocked() {
            if (DEBUG_PERSISTENCE) {
                Log.i(LOG_TAG, "[PERSIST START]");
            }
            FileOutputStream out = null;
            try {
                out = mStatePersistFile.startWrite();

                XmlSerializer serializer = new FastXmlSerializer();
                serializer.setOutput(out, "utf-8");
                serializer.startDocument(null, true);
                serializer.startTag(null, TAG_SPOOLER);

                List<PrintJobInfo> printJobs = mPrintJobs;

                final int printJobCount = printJobs.size();
                for (int j = 0; j < printJobCount; j++) {
                    PrintJobInfo printJob = printJobs.get(j);

                    final int state = printJob.getState();
                    if (state < PrintJobInfo.STATE_QUEUED
                            || state > PrintJobInfo.STATE_CANCELED) {
                        continue;
                    }

                    serializer.startTag(null, TAG_JOB);

                    serializer.attribute(null, ATTR_ID, String.valueOf(printJob.getId()));
                    serializer.attribute(null, ATTR_LABEL, printJob.getLabel().toString());
                    serializer.attribute(null, ATTR_STATE, String.valueOf(printJob.getState()));
                    serializer.attribute(null, ATTR_APP_ID, String.valueOf(printJob.getAppId()));
                    serializer.attribute(null, ATTR_USER_ID, String.valueOf(printJob.getUserId()));
                    String tag = printJob.getTag();
                    if (tag != null) {
                        serializer.attribute(null, ATTR_TAG, tag);
                    }
                    serializer.attribute(null, ATTR_COPIES, String.valueOf(printJob.getCopies()));

                    PrinterId printerId = printJob.getPrinterId();
                    if (printerId != null) {
                        serializer.startTag(null, TAG_PRINTER_ID);
                        serializer.attribute(null, ATTR_PRINTER_NAME, printerId.getPrinterName());
                        serializer.attribute(null, ATTR_SERVICE_NAME, printerId.getServiceName()
                                .flattenToString());
                        serializer.endTag(null, TAG_PRINTER_ID);
                    }

                    PageRange[] pages = printJob.getPages();
                    if (pages != null) {
                        for (int i = 0; i < pages.length; i++) {
                            serializer.startTag(null, TAG_PAGE_RANGE);
                            serializer.attribute(null, ATTR_START, String.valueOf(
                                    pages[i].getStart()));
                            serializer.attribute(null, ATTR_END, String.valueOf(
                                    pages[i].getEnd()));
                            serializer.endTag(null, TAG_PAGE_RANGE);
                        }
                    }

                    PrintAttributes attributes = printJob.getAttributes();
                    if (attributes != null) {
                        serializer.startTag(null, TAG_ATTRIBUTES);

                        final int duplexMode = attributes.getDuplexMode();
                        serializer.attribute(null, ATTR_DUPLEX_MODE,
                                String.valueOf(duplexMode));

                        final int colorMode = attributes.getColorMode();
                        serializer.attribute(null, ATTR_COLOR_MODE,
                                String.valueOf(colorMode));

                        final int fittingMode = attributes.getFittingMode();
                        serializer.attribute(null, ATTR_FITTING_MODE,
                                String.valueOf(fittingMode));

                        final int orientation = attributes.getOrientation();
                        serializer.attribute(null, ATTR_ORIENTATION,
                                String.valueOf(orientation));

                        MediaSize mediaSize = attributes.getMediaSize();
                        if (mediaSize != null) {
                            serializer.startTag(null, TAG_MEDIA_SIZE);
                            serializer.attribute(null, ATTR_ID, mediaSize.getId());
                            serializer.attribute(null, ATTR_LABEL, mediaSize.getLabel()
                                    .toString());
                            serializer.attribute(null, ATTR_WIDTH_MILS, String.valueOf(
                                    mediaSize.getWidthMils()));
                            serializer.attribute(null, ATTR_HEIGHT_MILS,String.valueOf(
                                    mediaSize.getHeightMils()));
                            serializer.endTag(null, TAG_MEDIA_SIZE);
                        }

                        Resolution resolution = attributes.getResolution();
                        if (resolution != null) {
                            serializer.startTag(null, TAG_RESOLUTION);
                            serializer.attribute(null, ATTR_ID, resolution.getId());
                            serializer.attribute(null, ATTR_LABEL, resolution.getLabel()
                                    .toString());
                            serializer.attribute(null, ATTR_HORIZONTAL_DPI, String.valueOf(
                                     resolution.getHorizontalDpi()));
                            serializer.attribute(null, ATTR_VERTICAL_DPI, String.valueOf(
                                    resolution.getVerticalDpi()));
                            serializer.endTag(null, TAG_RESOLUTION);
                        }

                        Margins margins = attributes.getMargins();
                        if (margins != null) {
                            serializer.startTag(null, TAG_MARGINS);
                            serializer.attribute(null, ATTR_LEFT_MILS, String.valueOf(
                                    margins.getLeftMils()));
                            serializer.attribute(null, ATTR_TOP_MILS, String.valueOf(
                                    margins.getTopMils()));
                            serializer.attribute(null, ATTR_RIGHT_MILS, String.valueOf(
                                    margins.getRightMils()));
                            serializer.attribute(null, ATTR_BOTTOM_MILS, String.valueOf(
                                    margins.getBottomMils()));
                            serializer.endTag(null, TAG_MARGINS);
                        }

                        Tray inputTray = attributes.getInputTray();
                        if (inputTray != null) {
                            serializer.startTag(null, TAG_INPUT_TRAY);
                            serializer.attribute(null, ATTR_ID, inputTray.getId());
                            serializer.attribute(null, ATTR_LABEL, inputTray.getLabel()
                                    .toString());
                            serializer.endTag(null, TAG_INPUT_TRAY);
                        }

                        Tray outputTray = attributes.getOutputTray();
                        if (outputTray != null) {
                            serializer.startTag(null, TAG_OUTPUT_TRAY);
                            serializer.attribute(null, ATTR_ID, outputTray.getId());
                            serializer.attribute(null, ATTR_LABEL, outputTray.getLabel()
                                    .toString());
                            serializer.endTag(null, TAG_OUTPUT_TRAY);
                        }

                        serializer.endTag(null, TAG_ATTRIBUTES);
                    }

                    PrintDocumentInfo documentInfo = printJob.getDocumentInfo();
                    if (documentInfo != null) {
                        serializer.startTag(null, TAG_DOCUMENT_INFO);
                        serializer.attribute(null, ATTR_CONTENT_TYPE, String.valueOf(
                                documentInfo.getContentType()));
                        serializer.attribute(null, ATTR_PAGE_COUNT, String.valueOf(
                                documentInfo.getPageCount()));
                        serializer.endTag(null, TAG_DOCUMENT_INFO);
                    }

                    serializer.endTag(null, TAG_JOB);

                    if (DEBUG_PERSISTENCE) {
                        Log.i(LOG_TAG, "[PERSISTED] " + printJob);
                    }
                }

                serializer.endTag(null, TAG_SPOOLER);
                serializer.endDocument();
                mStatePersistFile.finishWrite(out);
                if (DEBUG_PERSISTENCE) {
                    Log.i(LOG_TAG, "[PERSIST END]");
                }
            } catch (IOException e) {
                Slog.w(LOG_TAG, "Failed to write state, restoring backup.", e);
                mStatePersistFile.failWrite(out);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ioe) {
                        /* ignore */
                    }
                }
            }
        }

        public void readStateLocked() {
            if (!PERSISTNECE_MANAGER_ENABLED) {
                return;
            }
            FileInputStream in = null;
            try {
                in = mStatePersistFile.openRead();
            } catch (FileNotFoundException e) {
                Log.i(LOG_TAG, "No existing print spooler state.");
                return;
            }
            try {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(in, null);
                parseState(parser);
            } catch (IllegalStateException ise) {
                Slog.w(LOG_TAG, "Failed parsing ", ise);
            } catch (NullPointerException npe) {
                Slog.w(LOG_TAG, "Failed parsing ", npe);
            } catch (NumberFormatException nfe) {
                Slog.w(LOG_TAG, "Failed parsing ", nfe);
            } catch (XmlPullParserException xppe) {
                Slog.w(LOG_TAG, "Failed parsing ", xppe);
            } catch (IOException ioe) {
                Slog.w(LOG_TAG, "Failed parsing ", ioe);
            } catch (IndexOutOfBoundsException iobe) {
                Slog.w(LOG_TAG, "Failed parsing ", iobe);
            } finally {
                try {
                    in.close();
                } catch (IOException ioe) {
                    /* ignore */
                }
            }
        }

        private void parseState(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            parser.next();
            skipEmptyTextTags(parser);
            expect(parser, XmlPullParser.START_TAG, TAG_SPOOLER);
            parser.next();

            while (parsePrintJob(parser)) {
                parser.next();
            }

            skipEmptyTextTags(parser);
            expect(parser, XmlPullParser.END_TAG, TAG_SPOOLER);
        }

        private boolean parsePrintJob(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            skipEmptyTextTags(parser);
            if (!accept(parser, XmlPullParser.START_TAG, TAG_JOB)) {
                return false;
            }

            PrintJobInfo printJob = new PrintJobInfo();

            final int printJobId = Integer.parseInt(parser.getAttributeValue(null, ATTR_ID));
            printJob.setId(printJobId);
            String label = parser.getAttributeValue(null, ATTR_LABEL);
            printJob.setLabel(label);
            final int state = Integer.parseInt(parser.getAttributeValue(null, ATTR_STATE));
            printJob.setState(state);
            final int appId = Integer.parseInt(parser.getAttributeValue(null, ATTR_APP_ID));
            printJob.setAppId(appId);
            final int userId = Integer.parseInt(parser.getAttributeValue(null, ATTR_USER_ID));
            printJob.setUserId(userId);
            String tag = parser.getAttributeValue(null, ATTR_TAG);
            printJob.setTag(tag);
            String copies = parser.getAttributeValue(null, ATTR_COPIES);
            printJob.setCopies(Integer.parseInt(copies));

            parser.next();

            skipEmptyTextTags(parser);
            if (accept(parser, XmlPullParser.START_TAG, TAG_PRINTER_ID)) {
                String localId = parser.getAttributeValue(null, ATTR_PRINTER_NAME);
                ComponentName service = ComponentName.unflattenFromString(parser.getAttributeValue(
                        null, ATTR_SERVICE_NAME));
                printJob.setPrinterId(new PrinterId(service, localId));
                parser.next();
                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_PRINTER_ID);
                parser.next();
            }

            skipEmptyTextTags(parser);
            List<PageRange> pageRanges = null;
            while (accept(parser, XmlPullParser.START_TAG, TAG_PAGE_RANGE)) {
                final int start = Integer.parseInt(parser.getAttributeValue(null, ATTR_START));
                final int end = Integer.parseInt(parser.getAttributeValue(null, ATTR_END));
                PageRange pageRange = new PageRange(start, end);
                if (pageRanges == null) {
                    pageRanges = new ArrayList<PageRange>();
                }
                pageRanges.add(pageRange);
                parser.next();
                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_PAGE_RANGE);
                parser.next();
            }
            if (pageRanges != null) {
                PageRange[] pageRangesArray = new PageRange[pageRanges.size()];
                pageRanges.toArray(pageRangesArray);
                printJob.setPages(pageRangesArray);
            }

            skipEmptyTextTags(parser);
            if (accept(parser, XmlPullParser.START_TAG, TAG_ATTRIBUTES)) {

                PrintAttributes.Builder builder = new PrintAttributes.Builder();

                String duplexMode = parser.getAttributeValue(null, ATTR_DUPLEX_MODE);
                builder.setDuplexMode(Integer.parseInt(duplexMode));

                String colorMode = parser.getAttributeValue(null, ATTR_COLOR_MODE);
                builder.setColorMode(Integer.parseInt(colorMode));

                String fittingMode = parser.getAttributeValue(null, ATTR_FITTING_MODE);
                builder.setFittingMode(Integer.parseInt(fittingMode));

                String orientation = parser.getAttributeValue(null, ATTR_ORIENTATION);
                builder.setOrientation(Integer.parseInt(orientation));

                parser.next();

                skipEmptyTextTags(parser);
                if (accept(parser, XmlPullParser.START_TAG, TAG_MEDIA_SIZE)) {
                    String id = parser.getAttributeValue(null, ATTR_ID);
                    label = parser.getAttributeValue(null, ATTR_LABEL);
                    final int widthMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_WIDTH_MILS));
                    final int heightMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_HEIGHT_MILS));
                    MediaSize mediaSize = new MediaSize(id, label, widthMils, heightMils);
                    builder.setMediaSize(mediaSize);
                    parser.next();
                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_MEDIA_SIZE);
                    parser.next();
                }

                skipEmptyTextTags(parser);
                if (accept(parser, XmlPullParser.START_TAG, TAG_RESOLUTION)) {
                    String id = parser.getAttributeValue(null, ATTR_ID);
                    label = parser.getAttributeValue(null, ATTR_LABEL);
                    final int horizontalDpi = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_HORIZONTAL_DPI));
                    final int verticalDpi = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_VERTICAL_DPI));
                    Resolution resolution = new Resolution(id, label, horizontalDpi, verticalDpi);
                    builder.setResolution(resolution);
                    parser.next();
                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_RESOLUTION);
                    parser.next();
                }

                skipEmptyTextTags(parser);
                if (accept(parser, XmlPullParser.START_TAG, TAG_MARGINS)) {
                    final int leftMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_LEFT_MILS));
                    final int topMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_TOP_MILS));
                    final int rightMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_RIGHT_MILS));
                    final int bottomMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_BOTTOM_MILS));
                    Margins margins = new Margins(leftMils, topMils, rightMils, bottomMils);
                    builder.setMargins(margins);
                    parser.next();
                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_MARGINS);
                    parser.next();
                }

                skipEmptyTextTags(parser);
                if (accept(parser, XmlPullParser.START_TAG, TAG_INPUT_TRAY)) {
                    String id = parser.getAttributeValue(null, ATTR_ID);
                    label = parser.getAttributeValue(null, ATTR_LABEL);
                    Tray tray = new Tray(id, label);
                    builder.setInputTray(tray);
                    parser.next();
                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_INPUT_TRAY);
                    parser.next();
                }

                skipEmptyTextTags(parser);
                if (accept(parser, XmlPullParser.START_TAG, TAG_OUTPUT_TRAY)) {
                    String id = parser.getAttributeValue(null, ATTR_ID);
                    label = parser.getAttributeValue(null, ATTR_LABEL);
                    Tray tray = new Tray(id, label);
                    builder.setOutputTray(tray);
                    parser.next();
                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_OUTPUT_TRAY);
                    parser.next();
                }

                printJob.setAttributes(builder.create());

                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_ATTRIBUTES);
                parser.next();
            }

            skipEmptyTextTags(parser);
            if (accept(parser, XmlPullParser.START_TAG, TAG_DOCUMENT_INFO)) {
                final int pageCount = Integer.parseInt(parser.getAttributeValue(null,
                        ATTR_PAGE_COUNT));
                final int contentType = Integer.parseInt(parser.getAttributeValue(null,
                        ATTR_CONTENT_TYPE));
                PrintDocumentInfo info = new PrintDocumentInfo.Builder().setPageCount(pageCount)
                        .setContentType(contentType).create();
                printJob.setDocumentInfo(info);
                parser.next();
                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_DOCUMENT_INFO);
                parser.next();
            }

            mPrintJobs.add(printJob);

            if (DEBUG_PERSISTENCE) {
                Log.i(LOG_TAG, "[RESTORED] " + printJob);
            }

            skipEmptyTextTags(parser);
            expect(parser, XmlPullParser.END_TAG, TAG_JOB);

            return true;
        }

        private void expect(XmlPullParser parser, int type, String tag)
                throws IOException, XmlPullParserException {
            if (!accept(parser, type, tag)) {
                throw new XmlPullParserException("Exepected event: " + type
                        + " and tag: " + tag + " but got event: " + parser.getEventType()
                        + " and tag:" + parser.getName());
            }
        }

        private void skipEmptyTextTags(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            while (accept(parser, XmlPullParser.TEXT, null)
                    && "\n".equals(parser.getText())) {
                parser.next();
            }
        }

        private boolean accept(XmlPullParser parser, int type, String tag)
                throws IOException, XmlPullParserException {
            if (parser.getEventType() != type) {
                return false;
            }
            if (tag != null) {
                if (!tag.equals(parser.getName())) {
                    return false;
                }
            } else if (parser.getName() != null) {
                return false;
            }
            return true;
        }
    }
}
