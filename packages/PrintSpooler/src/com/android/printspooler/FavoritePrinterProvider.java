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
import android.os.Build;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.util.ArrayMap;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * This class provides the favorite printers based on past usage.
 */
final class FavoritePrinterProvider extends DataProvider<PrinterInfo> implements DataLoader {

    private static final String LOG_TAG = "FavoritePrinterProvider";

    private static final boolean DEBUG = true && Build.IS_DEBUGGABLE;

    private static final int MAX_HISTORY_LENGTH = 50;

    private static final double WEIGHT_DECAY_COEFFICIENT = 0.95f;

    private final List<PrinterRecord> mHistoricalPrinters = new ArrayList<PrinterRecord>();

    private final List<PrinterRecord> mFavoritePrinters = new ArrayList<PrinterRecord>();

    private final PersistenceManager mPersistenceManager;

    public FavoritePrinterProvider(Context context) {
        mPersistenceManager = new PersistenceManager(context);
    }

    public void addPrinter(PrinterInfo printer) {
        addPrinterInternal(printer);
        computeFavoritePrinters();
        mPersistenceManager.writeState();
    }

    @Override
    public int getItemCount() {
        return mFavoritePrinters.size();
    }

    @Override
    public PrinterInfo getItemAt(int index) {
        return mFavoritePrinters.get(index).printer;
    }

    @Override
    public int getItemIndex(PrinterInfo printer) {
        return mFavoritePrinters.indexOf(printer);
    }

    @Override
    public void startLoadData() {
        mPersistenceManager.readStateLocked();
        computeFavoritePrinters();
    }

    @Override
    public void stopLoadData() {
        /* do nothing */
    }

    private void addPrinterInternal(PrinterInfo printer) {
        if (mHistoricalPrinters.size() >= MAX_HISTORY_LENGTH) {
            mHistoricalPrinters.remove(0);
        }
        mHistoricalPrinters.add(new PrinterRecord(printer));
    }

    private void computeFavoritePrinters() {
        Map<PrinterId, PrinterRecord> recordMap =
                new ArrayMap<PrinterId, PrinterRecord>();

        // Recompute the weights.
        float currentWeight = 1.0f;
        final int printerCount = mHistoricalPrinters.size();
        for (int i = printerCount - 1; i >= 0; i--) {
            PrinterRecord record = mHistoricalPrinters.get(i);
            record.weight = currentWeight;
            // Aggregate weight for the same printer
            PrinterRecord oldRecord = recordMap.put(record.printer.getId(), record);
            if (oldRecord != null) {
                record.weight += oldRecord.weight;
            }
            currentWeight *= WEIGHT_DECAY_COEFFICIENT;
        }

        // Copy the unique printer records with computed weights.
        mFavoritePrinters.addAll(recordMap.values());

        // Soft the favorite printers.
        Collections.sort(mFavoritePrinters);
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

        private PersistenceManager(Context context) {
            mStatePersistFile = new AtomicFile(new File(context.getFilesDir(),
                    PERSIST_FILE_NAME));
        }

        @SuppressWarnings("unchecked")
        public void writeState() {

            new AsyncTask<List<PrinterRecord>, Void, Void>() {
                @Override
                protected Void doInBackground(List<PrinterRecord>... printers) {
                    doWriteState(printers[0]);
                    return null;
                }

                @Override
                protected void onPostExecute(Void result) {
                    notifyChanged();
                }

            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,
                    new ArrayList<PrinterRecord>(mHistoricalPrinters));
        }

        private void doWriteState(List<PrinterRecord> printers) {
            FileOutputStream out = null;
            try {
                out = mStatePersistFile.startWrite();

                XmlSerializer serializer = new FastXmlSerializer();
                serializer.setOutput(out, "utf-8");
                serializer.startDocument(null, true);
                serializer.startTag(null, TAG_PRINTERS);

                final int printerCount = printers.size();
                for (int i = printerCount - 1; i >= 0; i--) {
                    PrinterInfo printer = printers.get(i).printer;

                    serializer.startTag(null, TAG_PRINTER);

                    serializer.attribute(null, ATTR_NAME, printer.getName());
                    serializer.attribute(null, ATTR_STATUS, String.valueOf(printer.getStatus()));
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

        public void readStateLocked() {
            FileInputStream in = null;
            try {
                in = mStatePersistFile.openRead();
            } catch (FileNotFoundException e) {
                Log.i(LOG_TAG, "No existing printer history.");
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
                IoUtils.closeQuietly(in);
            }
            notifyChanged();
        }

        private void parseState(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            parser.next();
            skipEmptyTextTags(parser);
            expect(parser, XmlPullParser.START_TAG, TAG_PRINTERS);
            parser.next();

            while (parsePrinter(parser)) {
                parser.next();
            }

            skipEmptyTextTags(parser);
            expect(parser, XmlPullParser.END_TAG, TAG_PRINTERS);

            // We were reading the new records first and appended them first,
            // hence the historical list is in a reversed order, so fix that.
            Collections.reverse(mHistoricalPrinters);
        }

        private boolean parsePrinter(XmlPullParser parser)
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

            addPrinterInternal(printer);

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
    }
}
