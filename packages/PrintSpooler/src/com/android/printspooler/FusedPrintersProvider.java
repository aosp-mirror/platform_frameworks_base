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
import android.content.Loader;
import android.os.AsyncTask;
import android.os.Build;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.printspooler.PrintSpoolerService.PrinterDiscoverySession;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is responsible for loading printers by doing discovery
 * and merging the discovered printers with the previously used ones.
 */
public class FusedPrintersProvider extends Loader<List<PrinterInfo>> {
    private static final String LOG_TAG = "FusedPrintersProvider";

    private static final boolean DEBUG = true && Build.IS_DEBUGGABLE;

    private static final double WEIGHT_DECAY_COEFFICIENT = 0.95f;

    private static final int MAX_HISTORY_LENGTH = 50;

    private static final int MAX_HISTORICAL_PRINTER_COUNT = 4;

    private final Map<PrinterId, PrinterInfo> mPrinters =
            new LinkedHashMap<PrinterId, PrinterInfo>();

    private final PersistenceManager mPersistenceManager;

    private PrinterDiscoverySession mDiscoverySession;

    private List<PrinterInfo> mFavoritePrinters;

    public FusedPrintersProvider(Context context) {
        super(context);
        mPersistenceManager = new PersistenceManager(context);
    }

    public void addHistoricalPrinter(PrinterInfo printer) {
        mPersistenceManager.addPrinterAndWritePrinterHistory(printer);
    }

    public List<PrinterInfo> getPrinters() {
        return new ArrayList<PrinterInfo>(mPrinters.values());
    }

    @Override
    public void deliverResult(List<PrinterInfo> printers) {
        if (isStarted()) {
            super.deliverResult(printers);
        }
    }

    @Override
    protected void onStartLoading() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onStartLoading()");
        }
        // The contract is that if we already have a valid,
        // result the we have to deliver it immediately.
        if (!mPrinters.isEmpty()) {
            deliverResult(new ArrayList<PrinterInfo>(mPrinters.values()));
        }
        // If the data has changed since the last load
        // or is not available, start a load.
        if (takeContentChanged() || mPrinters.isEmpty()) {
            onForceLoad();
        }
    }

    @Override
    protected void onStopLoading() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onStopLoading()");
        }
        onCancelLoad();
    }

    @Override
    protected void onForceLoad() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onForceLoad()");
        }
        onCancelLoad();
        loadInternal();
    }

    private void loadInternal() {
        if (mDiscoverySession == null) {
            mDiscoverySession = new MyPrinterDiscoverySession();
            mPersistenceManager.readPrinterHistory();
        }
        if (mPersistenceManager.isReadHistoryCompleted()
                && !mDiscoverySession.isStarted()) {
            final int favoriteCount = Math.min(MAX_HISTORICAL_PRINTER_COUNT,
                    mFavoritePrinters.size());
            List<PrinterId> printerIds = new ArrayList<PrinterId>(favoriteCount);
            for (int i = 0; i < favoriteCount; i++) {
                printerIds.add(mFavoritePrinters.get(i).getId());
            }
            mDiscoverySession.startPrinterDisovery(printerIds);
        }
    }

    @Override
    protected boolean onCancelLoad() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onCancelLoad()");
        }
        return cancelInternal();
    }

    private boolean cancelInternal() {
        if (mDiscoverySession != null && mDiscoverySession.isStarted()) {
            mDiscoverySession.stopPrinterDiscovery();
            return true;
        } else if (mPersistenceManager.isReadHistoryInProgress()) {
            return mPersistenceManager.stopReadPrinterHistory();
        }
        return false;
    }

    @Override
    protected void onReset() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onReset()");
        }
        onStopLoading();
        mPrinters.clear();
        if (mDiscoverySession != null) {
            mDiscoverySession.destroy();
            mDiscoverySession = null;
        }
    }

    @Override
    protected void onAbandon() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onAbandon()");
        }
        onStopLoading();
    }

    public void refreshPrinter(PrinterId printerId) {
        if (isStarted() && mDiscoverySession != null && mDiscoverySession.isStarted()) {
            mDiscoverySession.requestPrinterUpdated(printerId);
        }
    }

    private final class MyPrinterDiscoverySession extends PrinterDiscoverySession {

        @Override
        public void onPrintersAdded(List<PrinterInfo> printers) {
            if (DEBUG) {
                Log.i(LOG_TAG, "MyPrinterDiscoverySession#onPrintersAdded()");
            }
            boolean printersAdded = false;
            final int addedPrinterCount = printers.size();
            for (int i = 0; i < addedPrinterCount; i++) {
                PrinterInfo printer = printers.get(i);
                if (!mPrinters.containsKey(printer.getId())) {
                    mPrinters.put(printer.getId(), printer);
                    printersAdded = true;
                }
            }
            if (printersAdded) {
                deliverResult(new ArrayList<PrinterInfo>(mPrinters.values()));
            }
        }

        @Override
        public void onPrintersRemoved(List<PrinterId> printerIds) {
            if (DEBUG) {
                Log.i(LOG_TAG, "MyPrinterDiscoverySession#onPrintersRemoved()");
            }
            boolean removedPrinters = false;
            final int removedPrinterCount = printerIds.size();
            for (int i = 0; i < removedPrinterCount; i++) {
                PrinterId removedPrinterId = printerIds.get(i);
                if (mPrinters.remove(removedPrinterId) != null) {
                    removedPrinters = true;
                }
            }
            if (removedPrinters) {
                deliverResult(new ArrayList<PrinterInfo>(mPrinters.values()));
            }
        }

        @Override
        public void onPrintersUpdated(List<PrinterInfo> printers) {
            if (DEBUG) {
                Log.i(LOG_TAG, "MyPrinterDiscoverySession#onPrintersUpdated()");
            }
            boolean updatedPrinters = false;
            final int updatedPrinterCount = printers.size();
            for (int i = 0; i < updatedPrinterCount; i++) {
                PrinterInfo updatedPrinter = printers.get(i);
                if (mPrinters.containsKey(updatedPrinter.getId())) {
                    mPrinters.put(updatedPrinter.getId(), updatedPrinter);
                    updatedPrinters = true;
                }
            }
            if (updatedPrinters) {
                deliverResult(new ArrayList<PrinterInfo>(mPrinters.values()));
            }
        }
    }

    private final class PersistenceManager {
        private static final String PERSIST_FILE_NAME = "printer_history.xml";

        private static final String TAG_PRINTERS = "printers";

        private static final String TAG_PRINTER = "printer";
        private static final String TAG_PRINTER_ID = "printerId";

        private static final String ATTR_LOCAL_ID = "localId";
        private static final String ATTR_SERVICE_NAME = "serviceName";

        private static final String ATTR_NAME = "name";
        private static final String ATTR_DESCRIPTION = "description";
        private static final String ATTR_STATUS = "status";

        private final AtomicFile mStatePersistFile;

        private List<PrinterInfo> mHistoricalPrinters;

        private boolean mReadHistoryCompleted;
        private boolean mReadHistoryInProgress;

        private final AsyncTask<Void, Void, List<PrinterInfo>> mReadTask =
                new AsyncTask<Void, Void, List<PrinterInfo>>() {
            @Override
            protected List<PrinterInfo> doInBackground(Void... args) {
               return doReadPrinterHistory();
            }

            @Override
            protected void onPostExecute(List<PrinterInfo> printers) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "read history completed");
                }

                mHistoricalPrinters = printers;

                // Compute the favorite printers.
                mFavoritePrinters = computeFavoritePrinters(printers);

                // We want the first few favorite printers on top of the list.
                final int favoriteCount = Math.min(mFavoritePrinters.size(),
                        MAX_HISTORICAL_PRINTER_COUNT);
                for (int i = 0; i < favoriteCount; i++) {
                    PrinterInfo favoritePrinter = mFavoritePrinters.get(i);
                    mPrinters.put(favoritePrinter.getId(), favoritePrinter);
                }

                mReadHistoryInProgress = false;
                mReadHistoryCompleted = true;

                loadInternal();
            }

            private List<PrinterInfo> doReadPrinterHistory() {
                FileInputStream in = null;
                try {
                    in = mStatePersistFile.openRead();
                } catch (FileNotFoundException fnfe) {
                    Log.i(LOG_TAG, "No existing printer history.");
                    return new ArrayList<PrinterInfo>();
                }
                try {
                    List<PrinterInfo> printers = new ArrayList<PrinterInfo>();
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(in, null);
                    parseState(parser, printers);
                    return printers;
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
                    IoUtils.closeQuietly(in);
                }

                return Collections.emptyList();
            }

            private void parseState(XmlPullParser parser, List<PrinterInfo> outPrinters)
                    throws IOException, XmlPullParserException {
                parser.next();
                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.START_TAG, TAG_PRINTERS);
                parser.next();

                while (parsePrinter(parser, outPrinters)) {
                    // Be nice and respond to cancellation
                    if (isCancelled()) {
                        return;
                    }
                    parser.next();
                }

                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_PRINTERS);
            }

            private boolean parsePrinter(XmlPullParser parser, List<PrinterInfo> outPrinters)
                    throws IOException, XmlPullParserException {
                skipEmptyTextTags(parser);
                if (!accept(parser, XmlPullParser.START_TAG, TAG_PRINTER)) {
                    return false;
                }

                String name = parser.getAttributeValue(null, ATTR_NAME);
                String description = parser.getAttributeValue(null, ATTR_DESCRIPTION);
                final int status = Integer.parseInt(parser.getAttributeValue(null, ATTR_STATUS));

                parser.next();

                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.START_TAG, TAG_PRINTER_ID);
                String localId = parser.getAttributeValue(null, ATTR_LOCAL_ID);
                ComponentName service = ComponentName.unflattenFromString(parser.getAttributeValue(
                        null, ATTR_SERVICE_NAME));
                PrinterId printerId =  new PrinterId(service, localId);
                parser.next();
                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_PRINTER_ID);
                parser.next();

                PrinterInfo.Builder builder = new PrinterInfo.Builder(printerId, name, status);
                builder.setDescription(description);
                PrinterInfo printer = builder.create();

                outPrinters.add(printer);

                if (DEBUG) {
                    Log.i(LOG_TAG, "[RESTORED] " + printer);
                }

                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_PRINTER);

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
        };

        private final AsyncTask<List<PrinterInfo>, Void, Void> mWriteTask =
                new AsyncTask<List<PrinterInfo>, Void, Void>() {
            @Override
            protected Void doInBackground(List<PrinterInfo>... printers) {
                doWritePrinterHistory(printers[0]);
                return null;
            }

            private void doWritePrinterHistory(List<PrinterInfo> printers) {
                FileOutputStream out = null;
                try {
                    out = mStatePersistFile.startWrite();

                    XmlSerializer serializer = new FastXmlSerializer();
                    serializer.setOutput(out, "utf-8");
                    serializer.startDocument(null, true);
                    serializer.startTag(null, TAG_PRINTERS);

                    final int printerCount = printers.size();
                    for (int i = 0; i < printerCount; i++) {
                        PrinterInfo printer = printers.get(i);

                        serializer.startTag(null, TAG_PRINTER);

                        serializer.attribute(null, ATTR_NAME, printer.getName());
                        serializer.attribute(null, ATTR_STATUS, String.valueOf(
                                printer.getStatus()));
                        String description = printer.getDescription();
                        if (description != null) {
                            serializer.attribute(null, ATTR_DESCRIPTION, description);
                        }

                        PrinterId printerId = printer.getId();
                        serializer.startTag(null, TAG_PRINTER_ID);
                        serializer.attribute(null, ATTR_LOCAL_ID, printerId.getLocalId());
                        serializer.attribute(null, ATTR_SERVICE_NAME, printerId.getServiceName()
                                .flattenToString());
                        serializer.endTag(null, TAG_PRINTER_ID);

                        serializer.endTag(null, TAG_PRINTER);

                        if (DEBUG) {
                            Log.i(LOG_TAG, "[PERSISTED] " + printer);
                        }
                    }

                    serializer.endTag(null, TAG_PRINTERS);
                    serializer.endDocument();
                    mStatePersistFile.finishWrite(out);

                    if (DEBUG) {
                        Log.i(LOG_TAG, "[PERSIST END]");
                    }
                } catch (IOException ioe) {
                    Slog.w(LOG_TAG, "Failed to write printer history, restoring backup.", ioe);
                    mStatePersistFile.failWrite(out);
                } finally {
                    IoUtils.closeQuietly(out);
                }
            }
        };

        private PersistenceManager(Context context) {
            mStatePersistFile = new AtomicFile(new File(context.getFilesDir(),
                    PERSIST_FILE_NAME));
        }

        public boolean isReadHistoryInProgress() {
            return mReadHistoryInProgress;
        }

        public boolean isReadHistoryCompleted() {
            return mReadHistoryCompleted;
        }

        public boolean stopReadPrinterHistory() {
            return mReadTask.cancel(true);
        }

        public void readPrinterHistory() {
            if (DEBUG) {
                Log.i(LOG_TAG, "read history started");
            }
            mReadHistoryInProgress = true;
            mReadTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }

        @SuppressWarnings("unchecked")
        public void addPrinterAndWritePrinterHistory(PrinterInfo printer) {
            if (mHistoricalPrinters.size() >= MAX_HISTORY_LENGTH) {
                mHistoricalPrinters.remove(0);
            }
            mHistoricalPrinters.add(printer);
            mWriteTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, mHistoricalPrinters);
        }

        private List<PrinterInfo> computeFavoritePrinters(List<PrinterInfo> printers) {
            Map<PrinterId, PrinterRecord> recordMap =
                    new ArrayMap<PrinterId, PrinterRecord>();

            // Recompute the weights.
            float currentWeight = 1.0f;
            final int printerCount = printers.size();
            for (int i = printerCount - 1; i >= 0; i--) {
                PrinterInfo printer = printers.get(i);
                // Aggregate weight for the same printer
                PrinterRecord record = recordMap.get(printer.getId());
                if (record == null) {
                    record = new PrinterRecord(printer);
                    recordMap.put(printer.getId(), record);
                }
                record.weight += currentWeight;
                currentWeight *= WEIGHT_DECAY_COEFFICIENT;
            }

            // Soft the favorite printers.
            List<PrinterRecord> favoriteRecords = new ArrayList<PrinterRecord>(
                    recordMap.values());
            Collections.sort(favoriteRecords);

            // Write the favorites to the output.
            final int favoriteCount = favoriteRecords.size();
            List<PrinterInfo> favoritePrinters = new ArrayList<PrinterInfo>(favoriteCount);
            for (int i = 0; i < favoriteCount; i++) {
                PrinterInfo printer = favoriteRecords.get(i).printer;
                favoritePrinters.add(printer);
            }

            return favoritePrinters;
        }

        private final class PrinterRecord implements Comparable<PrinterRecord> {
            public final PrinterInfo printer;
            public float weight;

            public PrinterRecord(PrinterInfo printer) {
                this.printer = printer;
            }

            @Override
            public int compareTo(PrinterRecord another) {
                return Float.floatToIntBits(another.weight) - Float.floatToIntBits(weight);
            }
        }
    }
}
