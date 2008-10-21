// Copyright 2007 The Android Open Source Project

package com.android.internal.location;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.util.Log;

/**
 * Data store to cache cell-id and wifi locations from the network
 *
 * {@hide}
 */
public class LocationCache {
    private static final String TAG = "LocationCache";

    // Version of cell cache
    private static final int CACHE_DB_VERSION = 1;

    // Don't save cache more than once every minute
    private static final long SAVE_FREQUENCY = 60 * 1000;

    // Location of the cache file;
    private static final String mCellCacheFile = "cache.cell";
    private static final String mWifiCacheFile = "cache.wifi";

    // Maximum time (in millis) that a record is valid for, before it needs
    // to be refreshed from the server.
    private static final long MAX_CELL_REFRESH_RECORD_AGE = 12 * 60 * 60 * 1000; // 12 hours
    private static final long MAX_WIFI_REFRESH_RECORD_AGE = 48 * 60 * 60 * 1000; // 48 hours

    // Cache sizes
    private static final int MAX_CELL_RECORDS = 50;
    private static final int MAX_WIFI_RECORDS = 200;

    // Cache constants
    private static final long CELL_SMOOTHING_WINDOW = 30 * 1000; // 30 seconds
    private static final int WIFI_MIN_AP_REQUIRED = 2;
    private static final int WIFI_MAX_MISS_ALLOWED = 5;
    private static final int MAX_ACCURACY_ALLOWED = 5000; // 5km

    // Caches
    private final Cache<Record> mCellCache;
    private final Cache<Record> mWifiCache;

    // Currently calculated centroids
    private final LocationCentroid mCellCentroid = new LocationCentroid();
    private final LocationCentroid mWifiCentroid = new LocationCentroid();

    // Extra key and values
    private final String EXTRA_KEY_LOCATION_TYPE = "networkLocationType";
    private final String EXTRA_VALUE_LOCATION_TYPE_CELL = "cell";
    private final String EXTRA_VALUE_LOCATION_TYPE_WIFI = "wifi";

    public LocationCache() {
        mCellCache = new Cache<Record>(LocationManager.SYSTEM_DIR, mCellCacheFile,
            MAX_CELL_RECORDS, MAX_CELL_REFRESH_RECORD_AGE);
        mWifiCache = new Cache<Record>(LocationManager.SYSTEM_DIR, mWifiCacheFile,
            MAX_WIFI_RECORDS, MAX_WIFI_REFRESH_RECORD_AGE);
    }

    /**
     * Looks up network location on device cache
     *
     * @param cellState primary cell state
     * @param cellHistory history of cell states
     * @param scanResults wifi scan results
     * @param result location object to fill if location is found
     * @return true if cache was able to answer query (successfully or not), false if call to
     * server is required
     */
    public synchronized boolean lookup(CellState cellState, List<CellState> cellHistory,
        List<ScanResult> scanResults, Location result) {

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "including cell:" + (cellState != null) +
                ", wifi:" + ((scanResults != null)? scanResults.size() : "null"));
        }

        long now = System.currentTimeMillis();

        mCellCentroid.reset();
        mWifiCentroid.reset();

        if (cellState != null) {
            String primaryCellKey = getCellCacheKey(cellState.getMcc(), cellState.getMnc(),
                cellState.getLac(), cellState.getCid());
            Record record = mCellCache.lookup(primaryCellKey);

            if (record == null) {
                // Make a server request if primary cell doesn't exist in DB
                return false;
            }

            if (record.isValid()) {
                mCellCentroid.addLocation(record.getLat(), record.getLng(), record.getAccuracy(),
                    record.getConfidence());
            }
        }

        if (cellHistory != null) {
            for (CellState historicalCell : cellHistory) {
                // Cell location might need to be smoothed if you are on the border of two cells
                if (now - historicalCell.getTime() < CELL_SMOOTHING_WINDOW) {
                    String historicalCellKey = getCellCacheKey(historicalCell.getMcc(),
                        historicalCell.getMnc(), historicalCell.getLac(), historicalCell.getCid());
                    Record record = mCellCache.lookup(historicalCellKey);
                    if (record != null && record.isValid()) {
                        mCellCentroid.addLocation(record.getLat(), record.getLng(),
                            record.getAccuracy(), record.getConfidence());
                    }
                }
            }
        }

        if (scanResults != null) {
            int miss = 0;
            for (ScanResult scanResult : scanResults) {
                String wifiKey = scanResult.BSSID;
                Record record = mWifiCache.lookup(wifiKey);
                if (record == null) {
                    miss++;
                } else {
                    if (record.isValid()) {
                        mWifiCentroid.addLocation(record.getLat(), record.getLng(),
                            record.getAccuracy(), record.getConfidence());
                    }
                }
            }

            if (mWifiCentroid.getNumber() >= WIFI_MIN_AP_REQUIRED) {
                // Try to return best out of the available cell or wifi location
            } else if (miss > Math.min(WIFI_MAX_MISS_ALLOWED, (scanResults.size()+1)/2)) {
                // Make a server request
                return false;
            } else {
                 // Don't use wifi location, only consider using cell location
                 mWifiCache.save();
                 mWifiCentroid.reset();
            }
        }

        if (mCellCentroid.getNumber() > 0) {
            mCellCache.save();
        }
        if (mWifiCentroid.getNumber() > 0) {
            mWifiCache.save();
        }

        int cellAccuracy = mCellCentroid.getAccuracy();
        int wifiAccuracy = mWifiCentroid.getAccuracy();

        int cellConfidence = mCellCentroid.getConfidence();
        int wifiConfidence = mWifiCentroid.getConfidence();

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "cellAccuracy:" + cellAccuracy+ ", wifiAccuracy:" + wifiAccuracy);
        }

        if (mCellCentroid.getNumber() != 0 && cellAccuracy <= MAX_ACCURACY_ALLOWED &&
            (mWifiCentroid.getNumber() == 0 || cellConfidence >= wifiConfidence ||
                cellAccuracy < wifiAccuracy)) {
            // Use cell results
            result.setAccuracy(cellAccuracy);
            result.setLatitude(mCellCentroid.getCentroidLat());
            result.setLongitude(mCellCentroid.getCentroidLng());
            result.setTime(now);
            
            Bundle extras = result.getExtras() == null ? new Bundle() : result.getExtras();
            extras.putString(EXTRA_KEY_LOCATION_TYPE, EXTRA_VALUE_LOCATION_TYPE_CELL);
            result.setExtras(extras);

        } else if (mWifiCentroid.getNumber() != 0 && wifiAccuracy <= MAX_ACCURACY_ALLOWED) {
            // Use wifi results
            result.setAccuracy(wifiAccuracy);
            result.setLatitude(mWifiCentroid.getCentroidLat());
            result.setLongitude(mWifiCentroid.getCentroidLng());
            result.setTime(now);

            Bundle extras = result.getExtras() == null ? new Bundle() : result.getExtras();
            extras.putString(EXTRA_KEY_LOCATION_TYPE, EXTRA_VALUE_LOCATION_TYPE_WIFI);
            result.setExtras(extras);

        } else {
            // Return invalid location
            result.setAccuracy(-1);
        }

        // don't make a server request
        return true;
    }

    public synchronized void insert(int mcc, int mnc, int lac, int cid, double lat, double lng,
        int accuracy, int confidence, long time) {
        String key = getCellCacheKey(mcc, mnc, lac, cid);
        if (accuracy <= 0) {
            mCellCache.insert(key, new Record());
        } else {
            mCellCache.insert(key, new Record(accuracy, confidence, lat, lng, time));
        }
    }

    public synchronized void insert(String bssid, double lat, double lng, int accuracy,
        int confidence, long time) {
        if (accuracy <= 0) {
            mWifiCache.insert(bssid, new Record());
        } else {
            mWifiCache.insert(bssid, new Record(accuracy, confidence, lat, lng, time));
        }
    }

    public synchronized void save() {
        mCellCache.save();
        mWifiCache.save();
    }

    /**
     * Cell or Wifi location record
     */
    public static class Record {

        private final double lat;
        private final double lng;
        private final int accuracy;
        private final int confidence;

        // Time (since the epoch) of original reading.
        private final long originTime;

        public static Record read(DataInput dataInput) throws IOException {
            final int accuracy = dataInput.readInt();
            final int confidence = dataInput.readInt();
            final double lat = dataInput.readDouble();
            final double lng = dataInput.readDouble();
            final long readingTime = dataInput.readLong();
            return new Record(accuracy, confidence, lat, lng, readingTime);
        }

        /**
         * Creates an "invalid" record indicating there was no location data
         * available for the given data
         */
        public Record() {
            this(-1, 0, 0, 0, System.currentTimeMillis());
        }

        /**
         * Creates a Record
         *
         * @param accuracy acuracy in meters. If < 0, then this is an invalid record.
         * @param confidence confidence (0-100)
         * @param lat latitude
         * @param lng longitude
         * @param time  Time of the original location reading from the server
         */
        public Record(int accuracy, int confidence, double lat, double lng, long time) {
            this.accuracy = accuracy;
            this.confidence = confidence;
            this.originTime = time;
            this.lat = lat;
            this.lng = lng;
        }

        public double getLat() {
            return lat;
        }

        public double getLng() {
            return lng;
        }

        public int getAccuracy() {
            return accuracy;
        }

        public int getConfidence() {
            return confidence;
        }

        public boolean isValid() {
            return accuracy > 0;
        }

        public long getTime() {
            return originTime;
        }

        public void write(DataOutput dataOut) throws IOException {
            dataOut.writeInt(accuracy);
            dataOut.writeInt(confidence);
            dataOut.writeDouble(lat);
            dataOut.writeDouble(lng);
            dataOut.writeLong(originTime);
        }

        @Override
        public String toString() {
            return lat + "," + lng + "," + originTime +"," + accuracy + "," + confidence;
        }
    }

    public class Cache<T> extends LinkedHashMap {
        private final long mMaxAge;
        private final int mCapacity;
        private final String mDir;
        private final String mFile;
        private long mLastSaveTime = 0;

        public Cache(String dir, String file, int capacity, long maxAge) {
            super(capacity + 1, 1.1f, true);
            this.mCapacity = capacity;
            this.mDir = dir;
            this.mFile = file;
            this.mMaxAge = maxAge;
            load();
        }

        private LocationCache.Record lookup(String key) {
            LocationCache.Record result = (LocationCache.Record) get(key);

            if (result == null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.d(TAG, "lookup: " + key + " failed");
                }
                return null;
            }

            // Cache entry needs refresh
            if (result.getTime() + mMaxAge < System.currentTimeMillis()) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.d(TAG, "lookup: " + key + " expired");
                }
                return null;
            }

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "lookup: " + key + " " + result.toString());
            }

            return result;
        }

        private void insert(String key, LocationCache.Record record) {
            remove(key);
            put(key, record);

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "insert: " + key + " " + record.toString());
            }
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry eldest) {
            // Remove cache entries when it has more than capacity
            return size() > mCapacity;
        }

        private void load() {
            FileInputStream istream;
            try {
                File f = new File(mDir, mFile);
                istream = new FileInputStream(f);
            } catch (FileNotFoundException e) {
                // No existing DB - return new CellCache
                return;
            }

            DataInputStream dataInput = new DataInputStream(istream);

            try {
                int version = dataInput.readUnsignedShort();
                if (version != CACHE_DB_VERSION) {
                    // Ignore records - invalid version ID.
                    dataInput.close();
                    return;
                }
                int records = dataInput.readUnsignedShort();

                for (int i = 0; i < records; i++) {
                    final String key = dataInput.readUTF();
                    final LocationCache.Record record = LocationCache.Record.read(dataInput);
                    //Log.d(TAG, key + " " + record.toString());
                    put(key, record);
                }

                dataInput.close();
            } catch (IOException e) {
                // Something's corrupted - return a new CellCache
            }
        }

        private void save() {
            long now = System.currentTimeMillis();
            if (mLastSaveTime != 0 && (now - mLastSaveTime < SAVE_FREQUENCY)) {
                // Don't save to file more often than SAVE_FREQUENCY
                return;
            }

            FileOutputStream ostream;

            File systemDir = new File(mDir);
            if (!systemDir.exists()) {
                if (!systemDir.mkdirs()) {
                    Log.e(TAG, "Cache.save(): couldn't create directory");
                    return;
                }
            }

            try {
                File f = new File(mDir, mFile);
                ostream = new FileOutputStream(f);
            } catch (FileNotFoundException e) {
                Log.d(TAG, "Cache.save(): unable to create cache file", e);
                return;
            }

            DataOutputStream dataOut = new DataOutputStream(ostream);
            try {
                dataOut.writeShort(CACHE_DB_VERSION);

                dataOut.writeShort(size());

                for (Iterator iter = entrySet().iterator(); iter.hasNext();) {
                    Map.Entry entry = (Map.Entry) iter.next();
                    String key = (String) entry.getKey();
                    LocationCache.Record record = (LocationCache.Record) entry.getValue();
                    dataOut.writeUTF(key);
                    record.write(dataOut);
                }

                dataOut.close();
                mLastSaveTime = now;
                
            } catch (IOException e) {
                Log.e(TAG, "Cache.save(): unable to write cache", e);
                // This should never happen
            }
        }
    }

    public class LocationCentroid {

        double mLatSum = 0;
        double mLngSum = 0;
        int mNumber = 0;
        int mConfidenceSum = 0;

        double mCentroidLat = 0;
        double mCentroidLng = 0;

        // Probably never have to calculate centroid for more than 10 locations
        final static int MAX_SIZE = 10;
        double[] mLats = new double[MAX_SIZE];
        double[] mLngs = new double[MAX_SIZE];
        int[] mRadii = new int[MAX_SIZE];

        LocationCentroid() {
            reset();
        }

        public void reset() {
            mLatSum = 0;
            mLngSum = 0;
            mNumber = 0;
            mConfidenceSum = 0;

            mCentroidLat = 0;
            mCentroidLng = 0;

            for (int i = 0; i < MAX_SIZE; i++) {
                mLats[i] = 0;
                mLngs[i] = 0;
                mRadii[i] = 0;
            }
        }

        public void addLocation(double lat, double lng, int accuracy, int confidence) {
            if (mNumber < MAX_SIZE && accuracy <= MAX_ACCURACY_ALLOWED) {
                mLatSum += lat;
                mLngSum += lng;
                mConfidenceSum += confidence;

                mLats[mNumber] = lat;
                mLngs[mNumber] = lng;
                mRadii[mNumber] = accuracy;
                mNumber++;
            }
        }

        public int getNumber() {
            return mNumber;
        }

        public double getCentroidLat() {
            if (mCentroidLat == 0 && mNumber != 0) {
                mCentroidLat = mLatSum/mNumber;
            }
            return mCentroidLat;
        }

        public double getCentroidLng() {
            if (mCentroidLng == 0 && mNumber != 0) {
                mCentroidLng = mLngSum/mNumber;
            }
            return mCentroidLng;
        }

        public int getConfidence() {
            if (mNumber != 0) {
                return mConfidenceSum/mNumber;
            } else {
                return 0;
            }
        }

        public int getAccuracy() {
            if (mNumber == 0) {
                return 0;
            }

            if (mNumber == 1) {
                return mRadii[0];
            }

            double cLat = getCentroidLat();
            double cLng = getCentroidLng();

            int meanDistanceSum = 0;
            int meanRadiiSum = 0;
            int smallestCircle = MAX_ACCURACY_ALLOWED;
            int smallestCircleDistance = MAX_ACCURACY_ALLOWED;
            float[] distance = new float[1];
            boolean outlierExists = false;

            for (int i = 0; i < mNumber; i++) {
                Location.distanceBetween(cLat, cLng, mLats[i], mLngs[i], distance);
                meanDistanceSum += (int)distance[0];
                if (distance[0] > mRadii[i]) {
                    outlierExists = true;
                }
                if (mRadii[i] < smallestCircle) {
                    smallestCircle = mRadii[i];
                    smallestCircleDistance = (int)distance[0];
                }
                meanRadiiSum += mRadii[i];
            }

            if (outlierExists) {
                return (meanDistanceSum + meanRadiiSum)/mNumber;
            } else {
                return Math.max(smallestCircle, smallestCircleDistance);
            }
        }

    }

    private String getCellCacheKey(int mcc, int mnc, int lac, int cid) {
        return mcc + ":" + mnc + ":" + lac + ":" + cid;
    }

}
