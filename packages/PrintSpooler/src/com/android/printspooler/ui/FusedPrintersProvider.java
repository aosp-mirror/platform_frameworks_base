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

package com.android.printspooler.ui;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.LoaderManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Loader;
import android.content.pm.ServiceInfo;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.print.PrintManager;
import android.print.PrintServicesLoader;
import android.print.PrinterDiscoverySession;
import android.print.PrinterDiscoverySession.OnPrintersChangeListener;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintServiceInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This class is responsible for loading printers by doing discovery
 * and merging the discovered printers with the previously used ones.
 */
public final class FusedPrintersProvider extends Loader<List<PrinterInfo>>
        implements LocationListener {
    private static final String LOG_TAG = "FusedPrintersProvider";

    private static final boolean DEBUG = false;

    private static final double WEIGHT_DECAY_COEFFICIENT = 0.95f;
    private static final int MAX_HISTORY_LENGTH = 50;

    private static final int MAX_FAVORITE_PRINTER_COUNT = 4;

    /** Interval of location updated in ms */
    private static final int LOCATION_UPDATE_MS = 30 * 1000;

    /** Maximum acceptable age of the location in ms */
    private static final int MAX_LOCATION_AGE_MS = 10 * 60 * 1000;

    /** The worst accuracy that is considered usable in m */
    private static final int MIN_LOCATION_ACCURACY = 50;

    /** Maximum distance where a printer is still considered "near" */
    private static final int MAX_PRINTER_DISTANCE = MIN_LOCATION_ACCURACY * 2;

    private final List<PrinterInfo> mPrinters =
            new ArrayList<>();

    private final List<Pair<PrinterInfo, Location>> mFavoritePrinters =
            new ArrayList<>();

    private final PersistenceManager mPersistenceManager;

    private PrinterDiscoverySession mDiscoverySession;

    private PrinterId mTrackedPrinter;

    private boolean mPrintersUpdatedBefore;

    /** Last known location, can be null or out of date */
    private final Object mLocationLock;
    private Location mLocation;

    /** Location used when the printers were updated the last time */
    private Location mLocationOfLastPrinterUpdate;

    /** Reference to the system's location manager */
    private final LocationManager mLocationManager;

    /**
     * Get a reference to the current location.
     */
    private Location getCurrentLocation() {
        synchronized (mLocationLock) {
            return mLocation;
        }
    }

    public FusedPrintersProvider(Activity activity, int internalLoaderId) {
        super(activity);
        mLocationLock = new Object();
        mPersistenceManager = new PersistenceManager(activity, internalLoaderId);
        mLocationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
    }

    public void addHistoricalPrinter(PrinterInfo printer) {
        mPersistenceManager.addPrinterAndWritePrinterHistory(printer);
    }

    /**
     * Add printer to dest, or if updatedPrinters add the updated printer. If the updated printer
     * was added, remove it from updatedPrinters.
     *
     * @param dest The list the printers should be added to
     * @param printer The printer to add
     * @param updatedPrinters The printer to add
     */
    private void updateAndAddPrinter(List<PrinterInfo> dest, PrinterInfo printer,
            Map<PrinterId, PrinterInfo> updatedPrinters) {
        PrinterInfo updatedPrinter = updatedPrinters.remove(printer.getId());
        if (updatedPrinter != null) {
            dest.add(updatedPrinter);
        } else {
            dest.add(printer);
        }
    }

    /**
     * Compute the printers, order them appropriately and deliver the printers to the clients. We
     * prefer printers that have been previously used (favorites) and printers that have been used
     * previously close to the current location (near printers).
     *
     * @param discoveredPrinters All printers currently discovered by the print discovery session.
     * @param favoritePrinters The ordered list of printers. The earlier in the list, the more
     *            preferred.
     */
    private void computeAndDeliverResult(Map<PrinterId, PrinterInfo> discoveredPrinters,
            List<Pair<PrinterInfo, Location>> favoritePrinters) {
        List<PrinterInfo> printers = new ArrayList<>();

        // Store the printerIds that have already been added. We cannot compare the printerInfos in
        // "printers" as they might have been taken from discoveredPrinters and the printerInfo does
        // not equals() anymore
        HashSet<PrinterId> alreadyAddedPrinter = new HashSet<>(MAX_FAVORITE_PRINTER_COUNT);

        Location location = getCurrentLocation();

        // Add the favorite printers that have last been used close to the current location
        final int favoritePrinterCount = favoritePrinters.size();
        if (location != null) {
            for (int i = 0; i < favoritePrinterCount; i++) {
                // Only add a certain amount of favorite printers
                if (printers.size() == MAX_FAVORITE_PRINTER_COUNT) {
                    break;
                }

                PrinterInfo favoritePrinter = favoritePrinters.get(i).first;
                Location printerLocation = favoritePrinters.get(i).second;

                if (printerLocation != null
                        && !alreadyAddedPrinter.contains(favoritePrinter.getId())) {
                    if (printerLocation.distanceTo(location) <= MAX_PRINTER_DISTANCE) {
                        updateAndAddPrinter(printers, favoritePrinter, discoveredPrinters);
                        alreadyAddedPrinter.add(favoritePrinter.getId());
                    }
                }
            }
        }

        // Add the other favorite printers
        for (int i = 0; i < favoritePrinterCount; i++) {
            // Only add a certain amount of favorite printers
            if (printers.size() == MAX_FAVORITE_PRINTER_COUNT) {
                break;
            }

            PrinterInfo favoritePrinter = favoritePrinters.get(i).first;
            if (!alreadyAddedPrinter.contains(favoritePrinter.getId())) {
                updateAndAddPrinter(printers, favoritePrinter, discoveredPrinters);
                alreadyAddedPrinter.add(favoritePrinter.getId());
            }
        }

        // Add other updated printers. Printers that have already been added have been removed from
        // discoveredPrinters in the calls to updateAndAddPrinter
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

        mLocationManager.requestLocationUpdates(LocationRequest.create()
                .setQuality(LocationRequest.POWER_LOW).setInterval(LOCATION_UPDATE_MS), this,
                Looper.getMainLooper());

        Location lastLocation = mLocationManager.getLastLocation();
        if (lastLocation != null) {
            onLocationChanged(lastLocation);
        }

        // Jumpstart location with a single forced update
        Criteria oneTimeCriteria = new Criteria();
        oneTimeCriteria.setAccuracy(Criteria.ACCURACY_FINE);
        mLocationManager.requestSingleUpdate(oneTimeCriteria, this, Looper.getMainLooper());

        // The contract is that if we already have a valid,
        // result the we have to deliver it immediately.
        (new Handler(Looper.getMainLooper())).post(new Runnable() {
            @Override public void run() {
                deliverResult(new ArrayList<>(mPrinters));
            }
        });

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

        mLocationManager.removeUpdates(this);
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

                    updatePrinters(mDiscoverySession.getPrinters(), mFavoritePrinters,
                            getCurrentLocation());
                }
            });
            final int favoriteCount = mFavoritePrinters.size();
            List<PrinterId> printerIds = new ArrayList<>(favoriteCount);
            for (int i = 0; i < favoriteCount; i++) {
                printerIds.add(mFavoritePrinters.get(i).first.getId());
            }
            mDiscoverySession.startPrinterDiscovery(printerIds);
            List<PrinterInfo> printers = mDiscoverySession.getPrinters();

            updatePrinters(printers, mFavoritePrinters, getCurrentLocation());
        }
    }

    private void updatePrinters(List<PrinterInfo> printers,
            List<Pair<PrinterInfo, Location>> favoritePrinters,
            Location location) {
        if (mPrintersUpdatedBefore && mPrinters.equals(printers)
                && mFavoritePrinters.equals(favoritePrinters)
                && Objects.equals(mLocationOfLastPrinterUpdate, location)) {
            return;
        }

        mLocationOfLastPrinterUpdate = location;
        mPrintersUpdatedBefore = true;

        // Some of the found printers may have be a printer that is in the
        // history but with its properties changed. Hence, we try to update the
        // printer to use its current properties instead of the historical one.
        mPersistenceManager.updateHistoricalPrintersIfNeeded(printers);

        Map<PrinterId, PrinterInfo> printersMap = new LinkedHashMap<>();
        final int printerCount = printers.size();
        for (int i = 0; i < printerCount; i++) {
            PrinterInfo printer = printers.get(i);
            printersMap.put(printer.getId(), printer);
        }

        computeAndDeliverResult(printersMap, favoritePrinters);
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
        }
    }

    @Override
    protected void onAbandon() {
        if (DEBUG) {
            Log.i(LOG_TAG, "onAbandon() " + FusedPrintersProvider.this.hashCode());
        }
        onStopLoading();
    }

    /**
     * Check if the location is acceptable. This is to filter out excessively old or inaccurate
     * location updates.
     *
     * @param location the location to check
     * @return true iff the location is usable.
     */
    private boolean isLocationAcceptable(Location location) {
        return location != null
                && location.getElapsedRealtimeNanos() > SystemClock.elapsedRealtimeNanos()
                        - MAX_LOCATION_AGE_MS * 1000_000L
                && location.hasAccuracy()
                && location.getAccuracy() < MIN_LOCATION_ACCURACY;
    }

    @Override
    public void onLocationChanged(Location location) {
        synchronized(mLocationLock) {
            // We expect the user to not move too fast while printing. Hence prefer more accurate
            // updates over more recent ones for LOCATION_UPDATE_MS. We add a 10% fudge factor here
            // as the location provider might send an update slightly too early.
            if (isLocationAcceptable(location)
                    && !location.equals(mLocation)
                    && (mLocation == null
                            || location
                                    .getElapsedRealtimeNanos() > mLocation.getElapsedRealtimeNanos()
                                            + LOCATION_UPDATE_MS * 0.9 * 1000_000L
                            || (!mLocation.hasAccuracy()
                                    || location.getAccuracy() < mLocation.getAccuracy()))) {
                // Other callers of updatePrinters might want to know the location, hence cache it
                mLocation = location;

                if (areHistoricalPrintersLoaded()) {
                    updatePrinters(mDiscoverySession.getPrinters(), mFavoritePrinters, mLocation);
                }
            }
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // nothing to do
    }

    @Override
    public void onProviderEnabled(String provider) {
        // nothing to do
    }

    @Override
    public void onProviderDisabled(String provider) {
        // nothing to do
    }

    public boolean areHistoricalPrintersLoaded() {
        return mPersistenceManager.mReadHistoryCompleted;
    }

    public void setTrackedPrinter(@Nullable PrinterId printerId) {
        if (isStarted() && mDiscoverySession != null
                && mDiscoverySession.isPrinterDiscoveryStarted()) {
            if (mTrackedPrinter != null) {
                if (mTrackedPrinter.equals(printerId)) {
                    return;
                }
                mDiscoverySession.stopPrinterStateTracking(mTrackedPrinter);
            }
            mTrackedPrinter = printerId;
            if (printerId != null) {
                mDiscoverySession.startPrinterStateTracking(printerId);
            }
        }
    }

    public boolean isFavoritePrinter(PrinterId printerId) {
        final int printerCount = mFavoritePrinters.size();
        for (int i = 0; i < printerCount; i++) {
            PrinterInfo favoritePritner = mFavoritePrinters.get(i).first;
            if (favoritePritner.getId().equals(printerId)) {
                return true;
            }
        }
        return false;
    }

    public void forgetFavoritePrinter(PrinterId printerId) {
        final int favoritePrinterCount = mFavoritePrinters.size();
        List<Pair<PrinterInfo, Location>> newFavoritePrinters = new ArrayList<>(
                favoritePrinterCount - 1);

        // Remove the printer from the favorites.
        for (int i = 0; i < favoritePrinterCount; i++) {
            if (!mFavoritePrinters.get(i).first.getId().equals(printerId)) {
                newFavoritePrinters.add(mFavoritePrinters.get(i));
            }
        }

        // Remove the printer from history and persist the latter.
        mPersistenceManager.removeHistoricalPrinterAndWritePrinterHistory(printerId);

        // Recompute and deliver the printers.
        updatePrinters(mDiscoverySession.getPrinters(), newFavoritePrinters, getCurrentLocation());
    }

    private final class PersistenceManager implements
            LoaderManager.LoaderCallbacks<List<PrintServiceInfo>> {
        private static final String PERSIST_FILE_NAME = "printer_history.xml";

        private static final String TAG_PRINTERS = "printers";

        private static final String TAG_PRINTER = "printer";
        private static final String TAG_LOCATION = "location";
        private static final String TAG_PRINTER_ID = "printerId";

        private static final String ATTR_LOCAL_ID = "localId";
        private static final String ATTR_SERVICE_NAME = "serviceName";

        private static final String ATTR_LONGITUDE = "longitude";
        private static final String ATTR_LATITUDE = "latitude";
        private static final String ATTR_ACCURACY = "accuracy";

        private static final String ATTR_NAME = "name";
        private static final String ATTR_DESCRIPTION = "description";

        private final AtomicFile mStatePersistFile;

        /**
         * Whether the enabled print services have been updated since last time the history was
         * read.
         */
        private boolean mAreEnabledServicesUpdated;

        /** The enabled services read when they were last updated */
        private @NonNull List<PrintServiceInfo> mEnabledServices;

        private List<Pair<PrinterInfo, Location>> mHistoricalPrinters = new ArrayList<>();

        private boolean mReadHistoryCompleted;

        private ReadTask mReadTask;

        private volatile long mLastReadHistoryTimestamp;

        private PersistenceManager(final Activity activity, final int internalLoaderId) {
            mStatePersistFile = new AtomicFile(new File(activity.getFilesDir(),
                    PERSIST_FILE_NAME), "printer-history");

            // Initialize enabled services to make sure they are set are the read task might be done
            // before the loader updated the services the first time.
            mEnabledServices = ((PrintManager) activity
                    .getSystemService(Context.PRINT_SERVICE))
                    .getPrintServices(PrintManager.ENABLED_SERVICES);

            mAreEnabledServicesUpdated = true;

            // Cannot start a loader while starting another, hence delay this loader
            (new Handler(activity.getMainLooper())).post(new Runnable() {
                @Override
                public void run() {
                    activity.getLoaderManager().initLoader(internalLoaderId, null,
                            PersistenceManager.this);
                }
            });
        }


        @Override
        public Loader<List<PrintServiceInfo>> onCreateLoader(int id, Bundle args) {
            return new PrintServicesLoader(
                    (PrintManager) getContext().getSystemService(Context.PRINT_SERVICE),
                    getContext(), PrintManager.ENABLED_SERVICES);
        }

        @Override
        public void onLoadFinished(Loader<List<PrintServiceInfo>> loader,
                List<PrintServiceInfo> services) {
            mAreEnabledServicesUpdated = true;
            mEnabledServices = services;

            // Ask the fused printer provider to reload which will cause the persistence manager to
            // reload the history and reconsider the enabled services.
            if (isStarted()) {
                forceLoad();
            }
        }

        @Override
        public void onLoaderReset(Loader<List<PrintServiceInfo>> loader) {
            // no data is cached
        }

        public boolean isReadHistoryInProgress() {
            return mReadTask != null;
        }

        public boolean isReadHistoryCompleted() {
            return mReadHistoryCompleted;
        }

        public boolean stopReadPrinterHistory() {
            return mReadTask.cancel(true);
        }

        public void readPrinterHistory() {
            if (DEBUG) {
                Log.i(LOG_TAG, "read history started "
                        + FusedPrintersProvider.this.hashCode());
            }
            mReadTask = new ReadTask();
            mReadTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }

        public void updateHistoricalPrintersIfNeeded(List<PrinterInfo> printers) {
            boolean writeHistory = false;

            final int printerCount = printers.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterInfo printer = printers.get(i);
                writeHistory |= updateHistoricalPrinterIfNeeded(printer);
            }

            if (writeHistory) {
                writePrinterHistory();
            }
        }

        /**
         * Updates the historical printer state with the given printer.
         *
         * @param printer the printer to update
         *
         * @return true iff the historical printer list needs to be updated
         */
        public boolean updateHistoricalPrinterIfNeeded(PrinterInfo printer) {
            boolean writeHistory = false;
            final int printerCount = mHistoricalPrinters.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterInfo historicalPrinter = mHistoricalPrinters.get(i).first;

                if (!historicalPrinter.getId().equals(printer.getId())) {
                    continue;
                }

                // Overwrite the historical printer with the updated printer as some properties
                // changed. We ignore the status as this is a volatile state.
                if (historicalPrinter.equalsIgnoringStatus(printer)) {
                    continue;
                }

                mHistoricalPrinters.set(i, new Pair<PrinterInfo, Location>(printer,
                                mHistoricalPrinters.get(i).second));

                // We only persist limited information in the printer history, hence check if
                // we need to persist the update.
                // @see PersistenceManager.WriteTask#doWritePrinterHistory
                if (!historicalPrinter.getName().equals(printer.getName())) {
                    if (Objects.equals(historicalPrinter.getDescription(),
                            printer.getDescription())) {
                        writeHistory = true;
                    }
                }
            }
            return writeHistory;
        }

        public void addPrinterAndWritePrinterHistory(PrinterInfo printer) {
            if (mHistoricalPrinters.size() >= MAX_HISTORY_LENGTH) {
                mHistoricalPrinters.remove(0);
            }

            Location location = getCurrentLocation();
            if (!isLocationAcceptable(location)) {
                location = null;
            }

            mHistoricalPrinters.add(new Pair<PrinterInfo, Location>(printer, location));

            writePrinterHistory();
        }

        public void removeHistoricalPrinterAndWritePrinterHistory(PrinterId printerId) {
            boolean writeHistory = false;
            final int printerCount = mHistoricalPrinters.size();
            for (int i = printerCount - 1; i >= 0; i--) {
                PrinterInfo historicalPrinter = mHistoricalPrinters.get(i).first;
                if (historicalPrinter.getId().equals(printerId)) {
                    mHistoricalPrinters.remove(i);
                    writeHistory = true;
                }
            }
            if (writeHistory) {
                writePrinterHistory();
            }
        }

        @SuppressWarnings("unchecked")
        private void writePrinterHistory() {
            new WriteTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,
                    new ArrayList<>(mHistoricalPrinters));
        }

        public boolean isHistoryChanged() {
            return mAreEnabledServicesUpdated ||
                    mLastReadHistoryTimestamp != mStatePersistFile.getBaseFile().lastModified();
        }

        /**
         * Sort the favorite printers by weight. If a printer is in the list multiple times for
         * different locations, all instances are considered to have the accumulative weight. The
         * actual favorite printers to display are computed in {@link #computeAndDeliverResult} as
         * only at this time we know the location to use to determine if a printer is close enough
         * to be preferred.
         *
         * @param printers The printers to sort.
         * @return The sorted printers.
         */
        private List<Pair<PrinterInfo, Location>> sortFavoritePrinters(
                List<Pair<PrinterInfo, Location>> printers) {
            Map<PrinterId, PrinterRecord> recordMap = new ArrayMap<>();

            // Compute the weights.
            float currentWeight = 1.0f;
            final int printerCount = printers.size();
            for (int i = printerCount - 1; i >= 0; i--) {
                PrinterId printerId = printers.get(i).first.getId();
                PrinterRecord record = recordMap.get(printerId);
                if (record == null) {
                    record = new PrinterRecord();
                    recordMap.put(printerId, record);
                }

                record.printers.add(printers.get(i));

                // Aggregate weight for the same printer
                record.weight += currentWeight;
                currentWeight *= WEIGHT_DECAY_COEFFICIENT;
            }

            // Sort the favorite printers.
            List<PrinterRecord> favoriteRecords = new ArrayList<>(
                    recordMap.values());
            Collections.sort(favoriteRecords);

            // Write the favorites to the output.
            final int recordCount = favoriteRecords.size();
            List<Pair<PrinterInfo, Location>> favoritePrinters = new ArrayList<>(printerCount);
            for (int i = 0; i < recordCount; i++) {
                favoritePrinters.addAll(favoriteRecords.get(i).printers);
            }

            return favoritePrinters;
        }

        /**
         * A set of printers with the same ID and the weight associated with them during
         * {@link #sortFavoritePrinters}.
         */
        private final class PrinterRecord implements Comparable<PrinterRecord> {
            /**
             * The printers, all with the same ID, but potentially different properties or locations
             */
            public final List<Pair<PrinterInfo, Location>> printers;

            /** The weight associated with the printers */
            public float weight;

            /**
             * Create a new record.
             */
            public PrinterRecord() {
                printers = new ArrayList<>();
            }

            /**
             * Compare two records by weight.
             */
            @Override
            public int compareTo(PrinterRecord another) {
                return Float.floatToIntBits(another.weight) - Float.floatToIntBits(weight);
            }
        }

        private final class ReadTask
                extends AsyncTask<Void, Void, List<Pair<PrinterInfo, Location>>> {
            @Override
            protected List<Pair<PrinterInfo, Location>> doInBackground(Void... args) {
               return doReadPrinterHistory();
            }

            @Override
            protected void onPostExecute(List<Pair<PrinterInfo, Location>> printers) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "read history completed "
                            + FusedPrintersProvider.this.hashCode());
                }

                // Ignore printer records whose target services are not enabled.
                Set<ComponentName> enabledComponents = new ArraySet<>();
                final int installedServiceCount = mEnabledServices.size();
                for (int i = 0; i < installedServiceCount; i++) {
                    ServiceInfo serviceInfo = mEnabledServices.get(i).getResolveInfo().serviceInfo;
                    ComponentName componentName = new ComponentName(
                            serviceInfo.packageName, serviceInfo.name);
                    enabledComponents.add(componentName);
                }
                mAreEnabledServicesUpdated = false;

                final int printerCount = printers.size();
                for (int i = printerCount - 1; i >= 0; i--) {
                    ComponentName printerServiceName = printers.get(i).first.getId()
                            .getServiceName();
                    if (!enabledComponents.contains(printerServiceName)) {
                        printers.remove(i);
                    }
                }

                // Store the filtered list.
                mHistoricalPrinters = printers;

                // Compute the favorite printers.
                mFavoritePrinters.clear();
                mFavoritePrinters.addAll(sortFavoritePrinters(mHistoricalPrinters));

                mReadHistoryCompleted = true;

                // Deliver the printers.
                updatePrinters(mDiscoverySession.getPrinters(), mFavoritePrinters,
                        getCurrentLocation());

                // We are done.
                mReadTask = null;

                // Loading the available printers if needed.
                loadInternal();
            }

            @Override
            protected void onCancelled(List<Pair<PrinterInfo, Location>> printerInfos) {
                // We are done.
                mReadTask = null;
            }

            private List<Pair<PrinterInfo, Location>> doReadPrinterHistory() {
                final FileInputStream in;
                try {
                    in = mStatePersistFile.openRead();
                } catch (FileNotFoundException fnfe) {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "No existing printer history "
                                + FusedPrintersProvider.this.hashCode());
                    }
                    return new ArrayList<>();
                }
                try {
                    List<Pair<PrinterInfo, Location>> printers = new ArrayList<>();
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(in, StandardCharsets.UTF_8.name());
                    parseState(parser, printers);
                    // Take a note which version of the history was read.
                    mLastReadHistoryTimestamp = mStatePersistFile.getBaseFile().lastModified();
                    return printers;
                } catch (IllegalStateException
                        | NullPointerException
                        | NumberFormatException
                        | XmlPullParserException
                        | IOException
                        | IndexOutOfBoundsException e) {
                    Slog.w(LOG_TAG, "Failed parsing ", e);
                } finally {
                    IoUtils.closeQuietly(in);
                }

                return Collections.emptyList();
            }

            private void parseState(XmlPullParser parser,
                    List<Pair<PrinterInfo, Location>> outPrinters)
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

            private boolean parsePrinter(XmlPullParser parser,
                    List<Pair<PrinterInfo, Location>> outPrinters)
                            throws IOException, XmlPullParserException {
                skipEmptyTextTags(parser);
                if (!accept(parser, XmlPullParser.START_TAG, TAG_PRINTER)) {
                    return false;
                }

                String name = parser.getAttributeValue(null, ATTR_NAME);
                String description = parser.getAttributeValue(null, ATTR_DESCRIPTION);

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

                skipEmptyTextTags(parser);
                Location location;
                if (accept(parser, XmlPullParser.START_TAG, TAG_LOCATION)) {
                    location = new Location("");
                    location.setLongitude(
                            Double.parseDouble(parser.getAttributeValue(null, ATTR_LONGITUDE)));
                    location.setLatitude(
                            Double.parseDouble(parser.getAttributeValue(null, ATTR_LATITUDE)));
                    location.setAccuracy(
                            Float.parseFloat(parser.getAttributeValue(null, ATTR_ACCURACY)));
                    parser.next();

                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_LOCATION);
                    parser.next();
                } else {
                    location = null;
                }

                // If the printer is available the printer will be replaced by the one read from the
                // discovery session, hence the only time when this object is used is when the
                // printer is unavailable.
                PrinterInfo.Builder builder = new PrinterInfo.Builder(printerId, name,
                        PrinterInfo.STATUS_UNAVAILABLE);
                builder.setDescription(description);
                PrinterInfo printer = builder.build();

                outPrinters.add(new Pair<PrinterInfo, Location>(printer, location));

                if (DEBUG) {
                    Log.i(LOG_TAG, "[RESTORED] " + printer);
                }

                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_PRINTER);

                return true;
            }

            private void expect(XmlPullParser parser, int type, String tag)
                    throws XmlPullParserException {
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
                    throws XmlPullParserException {
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

        private final class WriteTask
                extends AsyncTask<List<Pair<PrinterInfo, Location>>, Void, Void> {
            @Override
            protected Void doInBackground(
                    @SuppressWarnings("unchecked") List<Pair<PrinterInfo, Location>>... printers) {
                doWritePrinterHistory(printers[0]);
                return null;
            }

            private void doWritePrinterHistory(List<Pair<PrinterInfo, Location>> printers) {
                FileOutputStream out = null;
                try {
                    out = mStatePersistFile.startWrite();

                    XmlSerializer serializer = new FastXmlSerializer();
                    serializer.setOutput(out, StandardCharsets.UTF_8.name());
                    serializer.startDocument(null, true);
                    serializer.startTag(null, TAG_PRINTERS);

                    final int printerCount = printers.size();
                    for (int i = 0; i < printerCount; i++) {
                        PrinterInfo printer = printers.get(i).first;

                        serializer.startTag(null, TAG_PRINTER);

                        serializer.attribute(null, ATTR_NAME, printer.getName());
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

                        Location location = printers.get(i).second;
                        if (location != null) {
                            serializer.startTag(null, TAG_LOCATION);
                            serializer.attribute(null, ATTR_LONGITUDE,
                                    String.valueOf(location.getLongitude()));
                            serializer.attribute(null, ATTR_LATITUDE,
                                    String.valueOf(location.getLatitude()));
                            serializer.attribute(null, ATTR_ACCURACY,
                                    String.valueOf(location.getAccuracy()));
                            serializer.endTag(null, TAG_LOCATION);
                        }

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
        }
    }
}
