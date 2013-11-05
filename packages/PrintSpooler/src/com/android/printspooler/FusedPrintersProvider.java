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
import android.content.pm.ServiceInfo;
import android.os.AsyncTask;
import android.print.PrintManager;
import android.print.PrinterDiscoverySession;
import android.print.PrinterDiscoverySession.OnPrintersChangeListener;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintServiceInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;

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
import java.util.List;
import java.util.Map;
import java.util.Set;

import libcore.io.IoUtils;

/**
 * This class is responsible for loading printers by doing discovery
 * and merging the discovered printers with the previously used ones.
 */
public class FusedPrintersProvider extends Loader<List<PrinterInfo>> {
    private static final String LOG_TAG = "FusedPrintersProvider";

    private static final boolean DEBUG = false;

    private static final double WEIGHT_DECAY_COEFFICIENT = 0.95f;
    private static final int MAX_HISTORY_LENGTH = 50;

    private static final int MAX_FAVORITE_PRINTER_COUNT = 4;

    private final List<PrinterInfo> mPrinters =
            new ArrayList<PrinterInfo>();

    private final List<PrinterInfo> mFavoritePrinters =
            new ArrayList<PrinterInfo>();

    private final PersistenceManager mPersistenceManager;

    private PrinterDiscoverySession mDiscoverySession;

    private PrinterId mTrackedPrinter;

    private boolean mPrintersUpdatedBefore;

    public FusedPrintersProvider(Context context) {
        super(context);
        mPersistenceManager = new PersistenceManager(context);
    }

    public void addHistoricalPrinter(PrinterInfo printer) {
        mPersistenceManager.addPrinterAndWritePrinterHistory(printer);
    }

    private void computeAndDeliverResult(ArrayMap<PrinterId, PrinterInfo> discoveredPrinters,
            ArrayMap<PrinterId, PrinterInfo> favoritePrinters) {
        List<PrinterInfo> printers = new ArrayList<PrinterInfo>();

        // Add the updated favorite printers.
        final int favoritePrinterCount = favoritePrinters.size();
        for (int i = 0; i < favoritePrinterCount; i++) {
            PrinterInfo favoritePrinter = favoritePrinters.valueAt(i);
            PrinterInfo updatedPrinter = discoveredPrinters.remove(
                    favoritePrinter.getId());
            if (updatedPrinter != null) {
                printers.add(updatedPrinter);
            } else {
                printers.add(favoritePrinter);
            }
        }

        // Add other updated printers.
        final int printerCount = mPrinters.size();
        for (int i = 0; i < printerCount; i++) {
            PrinterInfo printer = mPrinters.get(i);
            PrinterInfo updatedPrinter = discoveredPrinters.remove(
                    printer.getId());
            if (updatedPrinter != null) {
                printers.add(updatedPrinter);
            }
        }

        // Add the new printers, i.e. what is left.
        printers.addAll(discoveredPrinters.values());

        // Update the list of printers.
        mPrinters.clear();
        mPrinters.addAll(printers);

        if (isStarted()) {
            // If stated deliver the new printers.
            deliverResult(printers);
        } else {
            // Otherwise, take a note for the change.
            onContentChanged();
        }
    }

    @Override
    protected void onStartLoading() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onStartLoading() " + FusedPrintersProvider.this.hashCode());
        }
        // The contract is that if we already have a valid,
        // result the we have to deliver it immediately.
        if (!mPrinters.isEmpty()) {
            deliverResult(new ArrayList<PrinterInfo>(mPrinters));
        }
        // Always load the data to ensure discovery period is
        // started and to make sure obsolete printers are updated.
        onForceLoad();
    }

    @Override
    protected void onStopLoading() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onStopLoading() " + FusedPrintersProvider.this.hashCode());
        }
        onCancelLoad();
    }

    @Override
    protected void onForceLoad() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onForceLoad() " + FusedPrintersProvider.this.hashCode());
        }
        loadInternal();
    }

    private void loadInternal() {
        if (mDiscoverySession == null) {
            PrintManager printManager = (PrintManager) getContext()
                    .getSystemService(Context.PRINT_SERVICE);
            mDiscoverySession = printManager.createPrinterDiscoverySession();
            mPersistenceManager.readPrinterHistory();
        } else if (mPersistenceManager.isHistoryChanged()) {
            mPersistenceManager.readPrinterHistory();
        }
        if (mPersistenceManager.isReadHistoryCompleted()
                && !mDiscoverySession.isPrinterDiscoveryStarted()) {
            mDiscoverySession.setOnPrintersChangeListener(new OnPrintersChangeListener() {
                @Override
                public void onPrintersChanged() {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "onPrintersChanged() count:"
                                + mDiscoverySession.getPrinters().size()
                                + " " + FusedPrintersProvider.this.hashCode());
                    }
                    updatePrinters(mDiscoverySession.getPrinters(), mFavoritePrinters);
                }
            });
            final int favoriteCount = mFavoritePrinters.size();
            List<PrinterId> printerIds = new ArrayList<PrinterId>(favoriteCount);
            for (int i = 0; i < favoriteCount; i++) {
                printerIds.add(mFavoritePrinters.get(i).getId());
            }
            mDiscoverySession.startPrinterDisovery(printerIds);
            List<PrinterInfo> printers = mDiscoverySession.getPrinters();
            if (!printers.isEmpty()) {
                updatePrinters(printers, mFavoritePrinters);
            }
        }
    }

    private void updatePrinters(List<PrinterInfo> printers, List<PrinterInfo> favoritePrinters) {
        if (mPrintersUpdatedBefore && mPrinters.equals(printers)
                && mFavoritePrinters.equals(favoritePrinters)) {
            return;
        }

        mPrintersUpdatedBefore = true;

        ArrayMap<PrinterId, PrinterInfo> printersMap =
                new ArrayMap<PrinterId, PrinterInfo>();
        final int printerCount = printers.size();
        for (int i = 0; i < printerCount; i++) {
            PrinterInfo printer = printers.get(i);
            printersMap.put(printer.getId(), printer);
        }

        ArrayMap<PrinterId, PrinterInfo> favoritePrintersMap =
                new ArrayMap<PrinterId, PrinterInfo>();
        final int favoritePrinterCount = favoritePrinters.size();
        for (int i = 0; i < favoritePrinterCount; i++) {
            PrinterInfo favoritePrinter = favoritePrinters.get(i);
            favoritePrintersMap.put(favoritePrinter.getId(), favoritePrinter);
        }

        computeAndDeliverResult(printersMap, favoritePrintersMap);
    }

    @Override
    protected boolean onCancelLoad() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onCancelLoad() " + FusedPrintersProvider.this.hashCode());
        }
        return cancelInternal();
    }

    private boolean cancelInternal() {
        if (mDiscoverySession != null
                && mDiscoverySession.isPrinterDiscoveryStarted()) {
            if (mTrackedPrinter != null) {
                mDiscoverySession.stopPrinterStateTracking(mTrackedPrinter);
                mTrackedPrinter = null;
            }
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
            Log.i(LOG_TAG, "onReset() " + FusedPrintersProvider.this.hashCode());
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
            Log.i(LOG_TAG, "onAbandon() " + FusedPrintersProvider.this.hashCode());
        }
        onStopLoading();
    }

    public void setTrackedPrinter(PrinterId printerId) {
        if (isStarted() && mDiscoverySession != null
                && mDiscoverySession.isPrinterDiscoveryStarted()) {
            if (mTrackedPrinter != null) {
                if (mTrackedPrinter.equals(printerId)) {
                    return;
                }
                mDiscoverySession.stopPrinterStateTracking(mTrackedPrinter);
            }
            mTrackedPrinter = printerId;
            mDiscoverySession.startPrinterStateTracking(printerId);
        }
    }

    public boolean isFavoritePrinter(PrinterId printerId) {
        final int printerCount = mFavoritePrinters.size();
        for (int i = 0; i < printerCount; i++) {
            PrinterInfo favoritePritner = mFavoritePrinters.get(i);
            if (favoritePritner.getId().equals(printerId)) {
                return true;
            }
        }
        return false;
    }

    public void forgetFavoritePrinter(PrinterId printerId) {
        List<PrinterInfo> newFavoritePrinters = null;

        // Remove the printer from the favorites.
        final int favoritePrinterCount = mFavoritePrinters.size();
        for (int i = 0; i < favoritePrinterCount; i++) {
            PrinterInfo favoritePrinter = mFavoritePrinters.get(i);
            if (favoritePrinter.getId().equals(printerId)) {
                newFavoritePrinters = new ArrayList<PrinterInfo>();
                newFavoritePrinters.addAll(mPrinters);
                newFavoritePrinters.remove(i);
                break;
            }
        }

        // If we removed a favorite printer, we have work to do.
        if (newFavoritePrinters != null) {
            // Remove the printer from history and persist the latter.
            mPersistenceManager.removeHistoricalPrinterAndWritePrinterHistory(printerId);

            // Recompute and deliver the printers.
            updatePrinters(mDiscoverySession.getPrinters(), newFavoritePrinters);
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

        private List<PrinterInfo> mHistoricalPrinters = new ArrayList<PrinterInfo>();

        private boolean mReadHistoryCompleted;
        private boolean mReadHistoryInProgress;

        private ReadTask mReadTask;

        private volatile long mLastReadHistoryTimestamp;

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
            final boolean cancelled = mReadTask.cancel(true);
            mReadTask = null;
            return cancelled;
        }

        public void readPrinterHistory() {
            if (DEBUG) {
                Log.i(LOG_TAG, "read history started "
                        + FusedPrintersProvider.this.hashCode());
            }
            mReadHistoryInProgress = true;
            mReadTask = new ReadTask();
            mReadTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }

        @SuppressWarnings("unchecked")
        public void addPrinterAndWritePrinterHistory(PrinterInfo printer) {
            if (mHistoricalPrinters.size() >= MAX_HISTORY_LENGTH) {
                mHistoricalPrinters.remove(0);
            }
            mHistoricalPrinters.add(printer);
            new WriteTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,
                    new ArrayList<PrinterInfo>(mHistoricalPrinters));
        }

        @SuppressWarnings("unchecked")
        public void removeHistoricalPrinterAndWritePrinterHistory(PrinterId printerId) {
            boolean writeHistory = false;
            final int printerCount = mHistoricalPrinters.size();
            for (int i = printerCount - 1; i >= 0; i--) {
                PrinterInfo historicalPrinter = mHistoricalPrinters.get(i);
                if (historicalPrinter.getId().equals(printerId)) {
                    mHistoricalPrinters.remove(i);
                    writeHistory = true;
                }
            }
            if (writeHistory) {
                new WriteTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,
                        new ArrayList<PrinterInfo>(mHistoricalPrinters));
            }
        }

        public boolean isHistoryChanged() {
            return mLastReadHistoryTimestamp != mStatePersistFile.getBaseFile().lastModified();
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
            final int favoriteCount = Math.min(favoriteRecords.size(),
                    MAX_FAVORITE_PRINTER_COUNT);
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

        private final class ReadTask extends AsyncTask<Void, Void, List<PrinterInfo>> {
            @Override
            protected List<PrinterInfo> doInBackground(Void... args) {
               return doReadPrinterHistory();
            }

            @Override
            protected void onPostExecute(List<PrinterInfo> printers) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "read history completed "
                            + FusedPrintersProvider.this.hashCode());
                }

                // Ignore printer records whose target services are not enabled.
                PrintManager printManager = (PrintManager) getContext()
                        .getSystemService(Context.PRINT_SERVICE);
                List<PrintServiceInfo> services = printManager
                        .getEnabledPrintServices();

                Set<ComponentName> enabledComponents = new ArraySet<ComponentName>();
                final int installedServiceCount = services.size();
                for (int i = 0; i < installedServiceCount; i++) {
                    ServiceInfo serviceInfo = services.get(i).getResolveInfo().serviceInfo;
                    ComponentName componentName = new ComponentName(
                            serviceInfo.packageName, serviceInfo.name);
                    enabledComponents.add(componentName);
                }

                final int printerCount = printers.size();
                for (int i = printerCount - 1; i >= 0; i--) {
                    ComponentName printerServiceName = printers.get(i).getId().getServiceName();
                    if (!enabledComponents.contains(printerServiceName)) {
                        printers.remove(i);
                    }
                }

                // Store the filtered list.
                mHistoricalPrinters = printers;

                // Compute the favorite printers.
                mFavoritePrinters.clear();
                mFavoritePrinters.addAll(computeFavoritePrinters(mHistoricalPrinters));

                mReadHistoryInProgress = false;
                mReadHistoryCompleted = true;

                // Deliver the printers.
                updatePrinters(mDiscoverySession.getPrinters(), mHistoricalPrinters);

                // Loading the available printers if needed.
                loadInternal();

                // We are done.
                mReadTask = null;
            }

            private List<PrinterInfo> doReadPrinterHistory() {
                FileInputStream in = null;
                try {
                    in = mStatePersistFile.openRead();
                } catch (FileNotFoundException fnfe) {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "No existing printer history "
                                + FusedPrintersProvider.this.hashCode());
                    }
                    return new ArrayList<PrinterInfo>();
                }
                try {
                    List<PrinterInfo> printers = new ArrayList<PrinterInfo>();
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(in, null);
                    parseState(parser, printers);
                    // Take a note which version of the history was read.
                    mLastReadHistoryTimestamp = mStatePersistFile.getBaseFile().lastModified();
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
                PrinterInfo printer = builder.build();

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

        private final class WriteTask extends AsyncTask<List<PrinterInfo>, Void, Void> {
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
                        // Historical printers are always stored as unavailable.
                        serializer.attribute(null, ATTR_STATUS, String.valueOf(
                                PrinterInfo.STATUS_UNAVAILABLE));
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
    }
}
