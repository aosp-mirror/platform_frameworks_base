// Copyright 2008 The Android Open Source Project

package com.android.internal.location;

import com.google.common.Config;
import com.google.common.android.AndroidConfig;
import com.google.common.io.protocol.ProtoBuf;
import com.google.masf.MobileServiceMux;
import com.google.masf.ServiceCallback;
import com.google.masf.protocol.PlainRequest;
import com.google.masf.protocol.Request;

import com.android.internal.location.protocol.GAddress;
import com.android.internal.location.protocol.GAddressComponent;
import com.android.internal.location.protocol.GAppProfile;
import com.android.internal.location.protocol.GCell;
import com.android.internal.location.protocol.GCellularPlatformProfile;
import com.android.internal.location.protocol.GCellularProfile;
import com.android.internal.location.protocol.GDebugProfile;
import com.android.internal.location.protocol.GDeviceLocation;
import com.android.internal.location.protocol.GFeature;
import com.android.internal.location.protocol.GGeocodeRequest;
import com.android.internal.location.protocol.GLatLng;
import com.android.internal.location.protocol.GLocReply;
import com.android.internal.location.protocol.GLocReplyElement;
import com.android.internal.location.protocol.GLocRequest;
import com.android.internal.location.protocol.GLocRequestElement;
import com.android.internal.location.protocol.GLocation;
import com.android.internal.location.protocol.GPlatformProfile;
import com.android.internal.location.protocol.GPrefetchMode;
import com.android.internal.location.protocol.GRectangle;
import com.android.internal.location.protocol.GWifiDevice;
import com.android.internal.location.protocol.GWifiProfile;
import com.android.internal.location.protocol.GcellularMessageTypes;
import com.android.internal.location.protocol.GdebugprofileMessageTypes;
import com.android.internal.location.protocol.GlatlngMessageTypes;
import com.android.internal.location.protocol.GlocationMessageTypes;
import com.android.internal.location.protocol.GrectangleMessageTypes;
import com.android.internal.location.protocol.GwifiMessageTypes;
import com.android.internal.location.protocol.LocserverMessageTypes;
import com.android.internal.location.protocol.ResponseCodes;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import android.content.Context;
import android.location.Address;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.telephony.NeighboringCellInfo;

/**
 * Service to communicate to the Google Location Server (GLS) via MASF server
 *
 * {@hide}
 */
public class LocationMasfClient {

    static final String TAG = "LocationMasfClient";

    // Address of the MASF server to connect to.
    private static final String MASF_SERVER_ADDRESS = "http://www.google.com/loc/m/api";

    // MobileServiceMux app/platform-specific values (application name matters!)
    private static final String APPLICATION_NAME = "location";
    private static final String APPLICATION_VERSION = "1.0";
    private static final String PLATFORM_ID = "android";
    private static final String DISTRIBUTION_CHANNEL = "android";
    private static String PLATFORM_BUILD = null;

    // Methods exposed by the MASF server
    private static final String REQUEST_QUERY_LOC = "g:loc/ql";
    private static final String REQUEST_UPLOAD_LOC = "g:loc/ul";

    // Max time to wait for request to end  
    private static final long REQUEST_TIMEOUT = 5000;

    // Constant to divide Lat, Lng returned by server
    private static final double E7 = 10000000.0;

    // Max wifi points to include
    private static final int MAX_WIFI_TO_INCLUDE = 25;

    // Location of GLS cookie
    private static final String PLATFORM_KEY_FILE = "gls.platform.key";
    private String mPlatformKey;

    // Cell cache
    private LocationCache mLocationCache;

    // Location object that the cache manages
    private Location mLocation = new Location(LocationManager.NETWORK_PROVIDER);

    // ProtoBuf objects we can reuse for subsequent requests
    private final int MAX_COLLECTION_BUFFER_SIZE = 30;
    private final long MIN_COLLECTION_INTERVAL = 15 * 60 * 1000; // 15 minutes
    private ProtoBuf mPlatformProfile = null;
    private ProtoBuf mCellularPlatformProfile = null;
    private ProtoBuf mCurrentCollectionRequest = null;
    private long mLastCollectionUploadTime = 0;

    // Objects for current request
    private List<ScanResult> mWifiScanResults = new ArrayList<ScanResult>();
    private CellState mCellState = null;
    private List<CellState> mCellHistory;

    // This tag is used for the event log.
    private static final int COLLECTION_EVENT_LOG_TAG = 2740;

    // Extra values to designate whether location is from cache or network request
    private static final String EXTRA_KEY_LOCATION_SOURCE = "networkLocationSource";
    private static final String EXTRA_VALUE_LOCATION_SOURCE_CACHED = "cached";
    private static final String EXTRA_VALUE_LOCATION_SOURCE_SERVER = "server";

    // Maximum accuracy tolerated for a valid location
    private static final int MAX_ACCURACY_ALLOWED = 5000; // 5km

    // Indicates whether this is the first message after a device restart
    private boolean mDeviceRestart = true;

    /**
     * Initializes the MobileServiceMux. Must be called before using any other function in the
     * class.
     */
    public LocationMasfClient(Context context) {
        MobileServiceMux mux = MobileServiceMux.getSingleton();
        if (mux == null) {
            AndroidConfig config = new AndroidConfig(context);
            Config.setConfig(config);

            MobileServiceMux.initialize
                (MASF_SERVER_ADDRESS,
                    APPLICATION_NAME,
                    APPLICATION_VERSION,
                    PLATFORM_ID,
                   DISTRIBUTION_CHANNEL);
        }
        mLocationCache = new LocationCache();

        if (Build.FINGERPRINT != null) {
            PLATFORM_BUILD = PLATFORM_ID + "/" + Build.FINGERPRINT;
        } else {
            PLATFORM_BUILD = PLATFORM_ID;
        }
    }

    /**
     * Returns the location for the given cell or wifi information.
     *
     * @param apps list of apps requesting location
     * @param trigger event that triggered this network request
     * @param cellState cell tower state
     * @param cellHistory history of acquired cell states
     * @param scanResults list of wifi scan results
     * @param scanTime time at which wireless scan was triggered
     * @param callback function to call with received location
     */
    public synchronized void getNetworkLocation(Collection<String> apps, int trigger,
        CellState cellState, List<CellState> cellHistory, List<ScanResult> scanResults,
        long scanTime, NetworkLocationProvider.Callback callback) {

        final NetworkLocationProvider.Callback finalCallback = callback;

        boolean foundInCache =
            mLocationCache.lookup(cellState, cellHistory, scanResults, mLocation);
        
        if (foundInCache) {

            if (SystemClock.elapsedRealtime() - mLastCollectionUploadTime > MIN_COLLECTION_INTERVAL) {
                uploadCollectionReport(true);
            }

            Bundle extras = mLocation.getExtras() == null ? new Bundle() : mLocation.getExtras();
            extras.putString(EXTRA_KEY_LOCATION_SOURCE, EXTRA_VALUE_LOCATION_SOURCE_CACHED);
            mLocation.setExtras(extras);

            Log.d(TAG, "getNetworkLocation(): Returning cache location with accuracy " +
                mLocation.getAccuracy());
            finalCallback.locationReceived(mLocation, true);
            return;
        }

        Log.d(TAG, "getNetworkLocation(): Location not found in cache, making network request");

        // Copy over to objects for this request
        mWifiScanResults.clear();
        if (scanResults != null) {
            mWifiScanResults.addAll(scanResults);
        }
        mCellState = cellState;
        mCellHistory = cellHistory;

        // Create a RequestElement
        ProtoBuf requestElement = new ProtoBuf(LocserverMessageTypes.GLOC_REQUEST_ELEMENT);

        // Debug profile
        ProtoBuf debugProfile = new ProtoBuf(GdebugprofileMessageTypes.GDEBUG_PROFILE);
        requestElement.setProtoBuf(GLocRequestElement.DEBUG_PROFILE, debugProfile);
        
        if (mDeviceRestart) {
            debugProfile.setBool(GDebugProfile.DEVICE_RESTART, true);
            mDeviceRestart = false;
        }

        if (trigger != -1) {
            debugProfile.setInt(GDebugProfile.TRIGGER, trigger);
        }

        // Cellular profile
        if (mCellState != null && mCellState.isValid()) {
            ProtoBuf cellularProfile = new ProtoBuf(GcellularMessageTypes.GCELLULAR_PROFILE);
            cellularProfile.setLong(GCellularProfile.TIMESTAMP, mCellState.getTime());
            cellularProfile.setInt(GCellularProfile.PREFETCH_MODE,
                GPrefetchMode.PREFETCH_MODE_MORE_NEIGHBORS);

            // Primary cell
            ProtoBuf primaryCell = new ProtoBuf(GcellularMessageTypes.GCELL);
            primaryCell.setInt(GCell.LAC, mCellState.getLac());
            primaryCell.setInt(GCell.CELLID, mCellState.getCid());

            if ((mCellState.getMcc() != -1) && (mCellState.getMnc() != -1)) {
                primaryCell.setInt(GCell.MCC, mCellState.getMcc());
                primaryCell.setInt(GCell.MNC, mCellState.getMnc());
            }

            if (mCellState.getSignalStrength() != -1) {
                primaryCell.setInt(GCell.RSSI, mCellState.getSignalStrength());
            }

            cellularProfile.setProtoBuf(GCellularProfile.PRIMARY_CELL, primaryCell);

            // History of cells
            for (CellState c : cellHistory) {
                ProtoBuf pastCell = new ProtoBuf(GcellularMessageTypes.GCELL);
                pastCell.setInt(GCell.LAC, c.getLac());
                pastCell.setInt(GCell.CELLID, c.getCid());
                if ((c.getMcc() != -1) && (c.getMnc() != -1)) {
                    pastCell.setInt(GCell.MCC, c.getMcc());
                    pastCell.setInt(GCell.MNC, c.getMnc());
                }

                if (c.getSignalStrength() != -1) {
                    pastCell.setInt(GCell.RSSI, c.getSignalStrength());
                }

                pastCell.setInt(GCell.AGE, (int)(mCellState.getTime() - c.getTime()));
                cellularProfile.addProtoBuf(GCellularProfile.HISTORICAL_CELLS, pastCell);
            }

            // Neighboring Cells
            addNeighborsToCellProfile(mCellState, cellularProfile);

            requestElement.setProtoBuf(GLocRequestElement.CELLULAR_PROFILE, cellularProfile);
        }

        // Wifi profile
        if (mWifiScanResults != null && mWifiScanResults.size() > 0) {
            ProtoBuf wifiProfile = new ProtoBuf(GwifiMessageTypes.GWIFI_PROFILE);
            wifiProfile.setLong(GWifiProfile.TIMESTAMP, scanTime);
            wifiProfile.setInt(GWifiProfile.PREFETCH_MODE,
                 GPrefetchMode.PREFETCH_MODE_MORE_NEIGHBORS);

            int count = 0;
            for (ScanResult s : mWifiScanResults) {
                ProtoBuf wifiDevice = new ProtoBuf(GwifiMessageTypes.GWIFI_DEVICE);
                wifiDevice.setString(GWifiDevice.MAC, s.BSSID);
                wifiProfile.addProtoBuf(GWifiProfile.WIFI_DEVICES, wifiDevice);
                count++;
                if (count >= MAX_WIFI_TO_INCLUDE) {
                    break;
                }
            }

            requestElement.setProtoBuf(GLocRequestElement.WIFI_PROFILE, wifiProfile);
        }

        // Request to send over wire
        ProtoBuf request = new ProtoBuf(LocserverMessageTypes.GLOC_REQUEST);
        request.addProtoBuf(GLocRequest.REQUEST_ELEMENTS, requestElement);

        // Create a Platform Profile
        ProtoBuf platformProfile = createPlatformProfile();
        if (mCellState != null && mCellState.isValid()) {
            // Include cellular platform Profile
            ProtoBuf cellularPlatform = createCellularPlatformProfile(mCellState);
            platformProfile.setProtoBuf(GPlatformProfile.CELLULAR_PLATFORM_PROFILE,
                cellularPlatform);
        }
        request.setProtoBuf(GLocRequest.PLATFORM_PROFILE, platformProfile);

        // Include App Profiles
        if (apps != null) {
            for (String app : apps) {
                ProtoBuf appProfile = new ProtoBuf(GlocationMessageTypes.GAPP_PROFILE);
                appProfile.setString(GAppProfile.APP_NAME, app);
                request.addProtoBuf(GLocRequest.APP_PROFILES, appProfile);
            }
        }

        // Queue any waiting collection events as well
        uploadCollectionReport(false);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            request.outputTo(payload);
        } catch (IOException e) {
            Log.e(TAG, "getNetworkLocation(): unable to write request to payload", e);
            return;
        }

        // Creates  request and a listener with a call back function
        ProtoBuf reply = new ProtoBuf(LocserverMessageTypes.GLOC_REPLY);
        Request plainRequest =
            new PlainRequest(REQUEST_QUERY_LOC, (short)0, payload.toByteArray());

        ProtoRequestListener listener = new ProtoRequestListener(reply, new ServiceCallback() {
            public void onRequestComplete(Object result) {
                ProtoBuf response = (ProtoBuf) result;
                boolean successful = parseNetworkLocationReply(response);
                finalCallback.locationReceived(mLocation, successful);

            }
        });
        plainRequest.setListener(listener);

        // Send request
        MobileServiceMux serviceMux = MobileServiceMux.getSingleton();
        serviceMux.submitRequest(plainRequest, true);
    }

    private synchronized boolean parseNetworkLocationReply(ProtoBuf response) {
        if (response == null) {
            Log.e(TAG, "getNetworkLocation(): response is null");
            return false;
        }

        int status1 = response.getInt(GLocReply.STATUS);
        if (status1 != ResponseCodes.STATUS_STATUS_SUCCESS) {
            Log.e(TAG, "getNetworkLocation(): RPC failed with status " + status1);
            return false;
        }

        if (response.has(GLocReply.PLATFORM_KEY)) {
            String platformKey = response.getString(GLocReply.PLATFORM_KEY);
            if (!TextUtils.isEmpty(platformKey)) {
                setPlatformKey(platformKey);
            }
        }

        if (!response.has(GLocReply.REPLY_ELEMENTS)) {
            Log.e(TAG, "getNetworkLocation(): no ReplyElement");
            return false;
        }
        ProtoBuf replyElement = response.getProtoBuf(GLocReply.REPLY_ELEMENTS);

        // Get prefetched data to add to the device cache
        Log.d(TAG, "getNetworkLocation(): Number of prefetched entries " +
            replyElement.getCount(GLocReplyElement.DEVICE_LOCATION));
        long now = System.currentTimeMillis();
        for (int i = 0; i < replyElement.getCount(GLocReplyElement.DEVICE_LOCATION); i++ ) {
            ProtoBuf device = replyElement.getProtoBuf(GLocReplyElement.DEVICE_LOCATION, i);
            double lat = 0;
            double lng = 0;
            int accuracy = -1;
            int confidence = -1;
            int locType = -1;
            if (device.has(GDeviceLocation.LOCATION)) {
                ProtoBuf deviceLocation = device.getProtoBuf(GDeviceLocation.LOCATION);
                if (deviceLocation.has(GLocation.ACCURACY) &&
                    deviceLocation.has(GLocation.LAT_LNG)
                    && deviceLocation.has(GLocation.CONFIDENCE)) {
                    lat = deviceLocation.getProtoBuf(GLocation.LAT_LNG).
                        getInt(GLatLng.LAT_E7) / E7;
                    lng = deviceLocation.getProtoBuf(GLocation.LAT_LNG).
                        getInt(GLatLng.LNG_E7) / E7;
                    accuracy = deviceLocation.getInt(GLocation.ACCURACY);
                    confidence = deviceLocation.getInt(GLocation.CONFIDENCE);
                }
                if (deviceLocation.has(GLocation.LOC_TYPE)) {
                    locType = deviceLocation.getInt(GLocation.LOC_TYPE);
                }
            }

            // Get cell key
            if (device.has(GDeviceLocation.CELL) && locType != GLocation.LOCTYPE_TOWER_LOCATION) {
                ProtoBuf deviceCell = device.getProtoBuf(GDeviceLocation.CELL);
                int cid = deviceCell.getInt(GCell.CELLID);
                int lac = deviceCell.getInt(GCell.LAC);
                int mcc = -1;
                int mnc = -1;
                if (deviceCell.has(GCell.MNC) && deviceCell.has(GCell.MCC)) {
                    mcc = deviceCell.getInt(GCell.MCC);
                    mnc = deviceCell.getInt(GCell.MNC);
                }
                mLocationCache.
                    insert(mcc, mnc, lac, cid, lat, lng, accuracy, confidence, now);
            }

            // Get wifi key
            if (device.has(GDeviceLocation.WIFI_DEVICE)) {
                ProtoBuf deviceWifi = device.getProtoBuf(GDeviceLocation.WIFI_DEVICE);
                String bssid = deviceWifi.getString(GWifiDevice.MAC);
                mLocationCache.insert(bssid, lat, lng, accuracy, confidence, now);
            }
        }

        mLocationCache.save();

        int status2 = replyElement.getInt(GLocReplyElement.STATUS);
        if (status2 != ResponseCodes.STATUS_STATUS_SUCCESS &&
            status2 != ResponseCodes.STATUS_STATUS_FAILED) {
            Log.e(TAG, "getNetworkLocation(): GLS failed with status " + status2);
            return false;
        }

        // For consistent results for user, always return cache computed location
        boolean foundInCache =
            mLocationCache.lookup(mCellState, mCellHistory, mWifiScanResults, mLocation);

        if (foundInCache) {

            Bundle extras = mLocation.getExtras() == null ? new Bundle() : mLocation.getExtras();
            extras.putString(EXTRA_KEY_LOCATION_SOURCE, EXTRA_VALUE_LOCATION_SOURCE_SERVER);
            mLocation.setExtras(extras);

            Log.d(TAG, "getNetworkLocation(): Returning network location with accuracy " +
                mLocation.getAccuracy());
            return true;
        }

        if (status2 == ResponseCodes.STATUS_STATUS_FAILED) {
            Log.e(TAG, "getNetworkLocation(): GLS does not have location");
            // We return true here since even though there is no location, there is no need to retry
            // since server doesn't have location
            return true;
        }

        // Get server computed location to return for now
        if (!replyElement.has(GLocReplyElement.LOCATION)) {
            Log.e(TAG, "getNetworkLocation(): no location in ReplyElement");
            return false;
        }
        ProtoBuf location = replyElement.getProtoBuf(GLocReplyElement.LOCATION);

        if (!location.has(GLocation.LAT_LNG)) {
            Log.e(TAG, "getNetworkLocation(): no Lat,Lng in location");
            return false;
        }

        ProtoBuf point = location.getProtoBuf(GLocation.LAT_LNG);
        double lat = point.getInt(GLatLng.LAT_E7) / E7;
        double lng = point.getInt(GLatLng.LNG_E7) / E7;

        int accuracy = 0;
        if (location.has(GLocation.ACCURACY)) {
            accuracy = location.getInt(GLocation.ACCURACY);
        }

        if (accuracy > MAX_ACCURACY_ALLOWED) {
            Log.e(TAG, "getNetworkLocation(): accuracy is too high " + accuracy);
            return false;
        }

        mLocation.setLatitude(lat);
        mLocation.setLongitude(lng);
        mLocation.setTime(System.currentTimeMillis());
        mLocation.setAccuracy(accuracy);

        Bundle extras = mLocation.getExtras() == null ? new Bundle() : mLocation.getExtras();
        extras.putString(EXTRA_KEY_LOCATION_SOURCE, EXTRA_VALUE_LOCATION_SOURCE_SERVER);
        mLocation.setExtras(extras);

        Log.e(TAG, "getNetworkLocation(): Returning *server* computed location with accuracy " +
            accuracy);

        return true;
    }

    /**
     * Gets a reverse geocoded location from the given lat,lng point. Also attaches the name
     * of the requesting application with the request
     *
     * @param locale locale for geocoded location
     * @param appPackageName name of the package, may be null
     * @param lat latitude
     * @param lng longitude
     * @param maxResults maximum number of addresses to return
     * @param addrs the list of addresses to fill up
     * @throws IOException if network is unavailable or some other issue
     */
    public void reverseGeocode(Locale locale, String appPackageName,
        double lat, double lng, int maxResults, List<Address> addrs) throws IOException {

        // Reverse geocoding request element
        ProtoBuf requestElement = new ProtoBuf(LocserverMessageTypes.GLOC_REQUEST_ELEMENT);

        ProtoBuf latlngElement = new ProtoBuf(GlatlngMessageTypes.GLAT_LNG);
        latlngElement.setInt(GLatLng.LAT_E7, (int)(lat * E7));
        latlngElement.setInt(GLatLng.LNG_E7, (int)(lng * E7));

        ProtoBuf locationElement = new ProtoBuf(GlocationMessageTypes.GLOCATION);
        locationElement.setProtoBuf(GLocation.LAT_LNG, latlngElement);
        locationElement.setLong(GLocation.TIMESTAMP, System.currentTimeMillis());
        requestElement.setProtoBuf(GLocRequestElement.LOCATION, locationElement);

        ProtoBuf geocodeElement =
            new ProtoBuf(LocserverMessageTypes.GGEOCODE_REQUEST);
        geocodeElement.setInt(GGeocodeRequest.NUM_FEATURE_LIMIT, maxResults);
        requestElement.setProtoBuf(GLocRequestElement.GEOCODE, geocodeElement);

        // Request to send over wire
        ProtoBuf request = new ProtoBuf(LocserverMessageTypes.GLOC_REQUEST);
        request.addProtoBuf(GLocRequest.REQUEST_ELEMENTS, requestElement);

        // Create platform profile
        ProtoBuf platformProfile = createPlatformProfile(locale);
        request.setProtoBuf(GLocRequest.PLATFORM_PROFILE, platformProfile);

        // Include app name
        if (appPackageName != null) {
            ProtoBuf appProfile = new ProtoBuf(GlocationMessageTypes.GAPP_PROFILE);
            appProfile.setString(GAppProfile.APP_NAME, appPackageName);
            request.setProtoBuf(GLocRequest.APP_PROFILES, appProfile);
        }

        // Queue any waiting collection events as well
        uploadCollectionReport(false);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            request.outputTo(payload);
        } catch (IOException e) {
            Log.e(TAG, "reverseGeocode(): unable to write request to payload");
            throw e;
        }

        // Creates  request and a listener with no callback function
        ProtoBuf reply = new ProtoBuf(LocserverMessageTypes.GLOC_REPLY);
        Request plainRequest =
            new PlainRequest(REQUEST_QUERY_LOC, (short)0, payload.toByteArray());
        ProtoRequestListener listener = new ProtoRequestListener(reply, null);
        plainRequest.setListener(listener);

        // Immediately send request and block for response until REQUEST_TIMEOUT
        MobileServiceMux serviceMux = MobileServiceMux.getSingleton();
        serviceMux.submitRequest(plainRequest, true);
        ProtoBuf response;
        try {
            response = (ProtoBuf)listener.getAsyncResult().get(REQUEST_TIMEOUT);
        } catch (InterruptedException e) {
            Log.e(TAG, "reverseGeocode(): response timeout");
            throw new IOException("response time-out");
        }

        if (response == null) {
            throw new IOException("Unable to parse response from server");
        }

        // Parse the response
        int status1 = response.getInt(GLocReply.STATUS);
        if (status1 != ResponseCodes.STATUS_STATUS_SUCCESS) {
            Log.e(TAG, "reverseGeocode(): RPC failed with status " + status1);
            throw new IOException("RPC failed with status " + status1);
        }

        if (response.has(GLocReply.PLATFORM_KEY)) {
            String platformKey = response.getString(GLocReply.PLATFORM_KEY);
            if (!TextUtils.isEmpty(platformKey)) {
                setPlatformKey(platformKey);
            }
        }

        if (!response.has(GLocReply.REPLY_ELEMENTS)) {
            Log.e(TAG, "reverseGeocode(): no ReplyElement");
            return;
        }
        ProtoBuf replyElement = response.getProtoBuf(GLocReply.REPLY_ELEMENTS);

        int status2 = replyElement.getInt(GLocReplyElement.STATUS);
        if (status2 != ResponseCodes.STATUS_STATUS_SUCCESS) {
            Log.e(TAG, "reverseGeocode(): GLS failed with status " + status2);
            return;
        }

        if (!replyElement.has(GLocReplyElement.LOCATION)) {
            Log.e(TAG, "reverseGeocode(): no location in ReplyElement");
            return;
        }

        ProtoBuf location = replyElement.getProtoBuf(GLocReplyElement.LOCATION);
        if (!location.has(GLocation.FEATURE)) {
            Log.e(TAG, "reverseGeocode(): no feature in GLocation");
            return;
        }

        getAddressFromProtoBuf(location, locale, addrs);
    }

    /**
     * Gets a forward geocoded location from the given location string. Also attaches the name
     * of the requesting application with the request
     *
     * Optionally, can specify the bounding box that the search results should be restricted to
     *
     * @param locale locale for geocoded location
     * @param appPackageName name of the package, may be null
     * @param locationString string to forward geocode
     * @param lowerLeftLatitude latitude of lower left point of bounding box
     * @param lowerLeftLongitude longitude of lower left point of bounding box
     * @param upperRightLatitude latitude of upper right point of bounding box
     * @param upperRightLongitude longitude of upper right point of bounding box
     * @param maxResults maximum number of results to return
     * @param addrs the list of addresses to fill up
     * @throws IOException if network is unavailable or some other issue
     */
    public void forwardGeocode(Locale locale, String appPackageName, String locationString,
        double lowerLeftLatitude, double lowerLeftLongitude,
        double upperRightLatitude, double upperRightLongitude, int maxResults, List<Address> addrs)
        throws IOException {

        // Forward geocoding request element
        ProtoBuf requestElement = new ProtoBuf(LocserverMessageTypes.GLOC_REQUEST_ELEMENT);

        ProtoBuf locationElement = new ProtoBuf(GlocationMessageTypes.GLOCATION);
        locationElement.setLong(GLocation.TIMESTAMP, System.currentTimeMillis());
        locationElement.setString(GLocation.LOCATION_STRING, locationString);
        requestElement.setProtoBuf(GLocRequestElement.LOCATION, locationElement);

        ProtoBuf geocodeElement =
            new ProtoBuf(LocserverMessageTypes.GGEOCODE_REQUEST);
        geocodeElement.setInt(GGeocodeRequest.NUM_FEATURE_LIMIT, maxResults);

        if (lowerLeftLatitude != 0 && lowerLeftLongitude !=0 &&
            upperRightLatitude !=0 && upperRightLongitude !=0) {
            ProtoBuf lowerLeft = new ProtoBuf(GlatlngMessageTypes.GLAT_LNG);
            lowerLeft.setInt(GLatLng.LAT_E7, (int)(lowerLeftLatitude * E7));
            lowerLeft.setInt(GLatLng.LNG_E7, (int)(lowerLeftLongitude * E7));

            ProtoBuf upperRight = new ProtoBuf(GlatlngMessageTypes.GLAT_LNG);
            upperRight.setInt(GLatLng.LAT_E7, (int)(upperRightLatitude * E7));
            upperRight.setInt(GLatLng.LNG_E7, (int)(upperRightLongitude * E7));

            ProtoBuf boundingBox = new ProtoBuf(GrectangleMessageTypes.GRECTANGLE);
            boundingBox.setProtoBuf(GRectangle.LOWER_LEFT, lowerLeft);
            boundingBox.setProtoBuf(GRectangle.UPPER_RIGHT, upperRight);
            geocodeElement.setProtoBuf(GGeocodeRequest.BOUNDING_BOX, boundingBox);
        }
        requestElement.setProtoBuf(GLocRequestElement.GEOCODE, geocodeElement);

        // Request to send over wire
        ProtoBuf request = new ProtoBuf(LocserverMessageTypes.GLOC_REQUEST);
        request.addProtoBuf(GLocRequest.REQUEST_ELEMENTS, requestElement);

        // Create platform profile
        ProtoBuf platformProfile = createPlatformProfile(locale);
        request.setProtoBuf(GLocRequest.PLATFORM_PROFILE, platformProfile);

        // Include app name
        if (appPackageName != null) {
            ProtoBuf appProfile = new ProtoBuf(GlocationMessageTypes.GAPP_PROFILE);
            appProfile.setString(GAppProfile.APP_NAME, appPackageName);
            request.setProtoBuf(GLocRequest.APP_PROFILES, appProfile);
        }

        // Queue any waiting collection events as well
        uploadCollectionReport(false);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            request.outputTo(payload);
        } catch (IOException e) {
            Log.e(TAG, "forwardGeocode(): unable to write request to payload");
            throw e;
        }

        // Creates  request and a listener with no callback function
        ProtoBuf reply = new ProtoBuf(LocserverMessageTypes.GLOC_REPLY);
        Request plainRequest =
            new PlainRequest(REQUEST_QUERY_LOC, (short)0, payload.toByteArray());
        ProtoRequestListener listener = new ProtoRequestListener(reply, null);
        plainRequest.setListener(listener);

        // Immediately send request and block for response until REQUEST_TIMEOUT
        MobileServiceMux serviceMux = MobileServiceMux.getSingleton();
        serviceMux.submitRequest(plainRequest, true);
        ProtoBuf response;
        try {
            response = (ProtoBuf)listener.getAsyncResult().get(REQUEST_TIMEOUT);
        } catch (InterruptedException e) {
            Log.e(TAG, "forwardGeocode(): response timeout");
            throw new IOException("response time-out");
        }

        if (response == null) {
            throw new IOException("Unable to parse response from server");
        }

        // Parse the response
        int status1 = response.getInt(GLocReply.STATUS);
        if (status1 != ResponseCodes.STATUS_STATUS_SUCCESS) {
            Log.e(TAG, "forwardGeocode(): RPC failed with status " + status1);
            throw new IOException("RPC failed with status " + status1);
        }

        if (response.has(GLocReply.PLATFORM_KEY)) {
            String platformKey = response.getString(GLocReply.PLATFORM_KEY);
            if (!TextUtils.isEmpty(platformKey)) {
                setPlatformKey(platformKey);
            }
        }

        if (!response.has(GLocReply.REPLY_ELEMENTS)) {
            Log.e(TAG, "forwardGeocode(): no ReplyElement");
            return;
        }
        ProtoBuf replyElement = response.getProtoBuf(GLocReply.REPLY_ELEMENTS);

        int status2 = replyElement.getInt(GLocReplyElement.STATUS);
        if (status2 != ResponseCodes.STATUS_STATUS_SUCCESS) {
            Log.e(TAG, "forwardGeocode(): GLS failed with status " + status2);
            return;
        }

        if (!replyElement.has(GLocReplyElement.LOCATION)) {
            Log.e(TAG, "forwardGeocode(): no location in ReplyElement");
            return;
        }

        ProtoBuf location = replyElement.getProtoBuf(GLocReplyElement.LOCATION);
        if (!location.has(GLocation.FEATURE)) {
            Log.e(TAG, "forwardGeocode(): no feature in GLocation");
            return;
        }

        getAddressFromProtoBuf(location, locale, addrs);
    }

    /**
     * Queues a location collection request to be sent to the server
     *
     * @param trigger what triggered this collection event
     * @param location last known location
     * @param cellState cell tower state
     * @param scanResults list of wifi points
     * @param scanTime real time at which wifi scan happened
     * @param immediate true if request should be sent immediately instead of being queued
     */
    public synchronized void queueCollectionReport(int trigger, Location location,
        CellState cellState, List<ScanResult> scanResults, long scanTime, boolean immediate) {

        // Create a RequestElement
        ProtoBuf requestElement = new ProtoBuf(LocserverMessageTypes.GLOC_REQUEST_ELEMENT);

        // Include debug profile
        ProtoBuf debugProfile = new ProtoBuf(GdebugprofileMessageTypes.GDEBUG_PROFILE);
        requestElement.setProtoBuf(GLocRequestElement.DEBUG_PROFILE, debugProfile);

        if (mDeviceRestart) {
            debugProfile.setBool(GDebugProfile.DEVICE_RESTART, true);
            mDeviceRestart = false;
        }

        if (trigger != -1) {
            debugProfile.setInt(GDebugProfile.TRIGGER, trigger);
            EventLog.writeEvent(COLLECTION_EVENT_LOG_TAG, trigger);            
        }

        // Include cell profile
        if (cellState != null && cellState.isValid()) {
            ProtoBuf cellularProfile = new ProtoBuf(GcellularMessageTypes.GCELLULAR_PROFILE);
            cellularProfile.setLong(GCellularProfile.TIMESTAMP, cellState.getTime());

            // Primary cell
            ProtoBuf primaryCell = new ProtoBuf(GcellularMessageTypes.GCELL);
            primaryCell.setInt(GCell.LAC, cellState.getLac());
            primaryCell.setInt(GCell.CELLID, cellState.getCid());
            if ((cellState.getMcc() != -1) && (cellState.getMnc() != -1)) {
                primaryCell.setInt(GCell.MCC, cellState.getMcc());
                primaryCell.setInt(GCell.MNC, cellState.getMnc());
            }

            if (cellState.getSignalStrength() != -1) {
                primaryCell.setInt(GCell.RSSI, cellState.getSignalStrength());
            }

            cellularProfile.setProtoBuf(GCellularProfile.PRIMARY_CELL, primaryCell);

            // Cell neighbors
            addNeighborsToCellProfile(cellState, cellularProfile);

            requestElement.setProtoBuf(GLocRequestElement.CELLULAR_PROFILE, cellularProfile);
        }

        // Include Wifi profile
        if (scanResults != null && scanResults.size() > 0) {
            ProtoBuf wifiProfile = new ProtoBuf(GwifiMessageTypes.GWIFI_PROFILE);
            wifiProfile.setLong(GWifiProfile.TIMESTAMP, scanTime);

            int count = 0;
            for (ScanResult s : scanResults) {
                ProtoBuf wifiDevice = new ProtoBuf(GwifiMessageTypes.GWIFI_DEVICE);
                wifiDevice.setString(GWifiDevice.MAC, s.BSSID);
                wifiDevice.setString(GWifiDevice.SSID, s.SSID);
                wifiDevice.setInt(GWifiDevice.RSSI, s.level);
                wifiProfile.addProtoBuf(GWifiProfile.WIFI_DEVICES, wifiDevice);
                count++;
                if (count >= MAX_WIFI_TO_INCLUDE) {
                    break;
                }
            }

            requestElement.setProtoBuf(GLocRequestElement.WIFI_PROFILE, wifiProfile);
        }

        // Location information
        if (location != null) {
            ProtoBuf latlngElement = new ProtoBuf(GlatlngMessageTypes.GLAT_LNG);
            latlngElement.setInt(GLatLng.LAT_E7, (int)(location.getLatitude() * E7));
            latlngElement.setInt(GLatLng.LNG_E7, (int)(location.getLongitude() * E7));

            ProtoBuf locationElement = new ProtoBuf(GlocationMessageTypes.GLOCATION);
            locationElement.setProtoBuf(GLocation.LAT_LNG, latlngElement);
            locationElement.setInt(GLocation.LOC_TYPE, GLocation.LOCTYPE_GPS);
            locationElement.setLong(GLocation.TIMESTAMP, location.getTime());
            if (location.hasAccuracy()) {
                locationElement.setInt(GLocation.ACCURACY, (int)location.getAccuracy());
            }
            if (location.hasSpeed()) {
                locationElement.setInt(GLocation.VELOCITY, (int)location.getSpeed());
            }
            if (location.hasBearing()) {
                locationElement.setInt(GLocation.HEADING, (int)location.getBearing());
            }

            requestElement.setProtoBuf(GLocRequestElement.LOCATION, locationElement);
        }

        if (mCurrentCollectionRequest == null) {
            mCurrentCollectionRequest = new ProtoBuf(LocserverMessageTypes.GLOC_REQUEST);

            // Create a Platform Profile
            ProtoBuf platformProfile = createPlatformProfile();
            if (cellState != null && cellState.isValid()) {
                ProtoBuf cellularPlatform = createCellularPlatformProfile(cellState);
                platformProfile.setProtoBuf(GPlatformProfile.CELLULAR_PLATFORM_PROFILE,
                    cellularPlatform);
            }

            mCurrentCollectionRequest.setProtoBuf(GLocRequest.PLATFORM_PROFILE, platformProfile);

        } else {

            ProtoBuf platformProfile =
                mCurrentCollectionRequest.getProtoBuf(GLocRequest.PLATFORM_PROFILE);
            if (platformProfile == null) {
                platformProfile = createPlatformProfile();
                mCurrentCollectionRequest.setProtoBuf(
                    GLocRequest.PLATFORM_PROFILE, platformProfile);
            }

            // Add cellular platform profile is not already included
            if (platformProfile.getProtoBuf(GPlatformProfile.CELLULAR_PLATFORM_PROFILE) == null &&
                cellState != null && cellState.isValid()) {
                ProtoBuf cellularPlatform = createCellularPlatformProfile(cellState);
                platformProfile.setProtoBuf(GPlatformProfile.CELLULAR_PLATFORM_PROFILE,
                    cellularPlatform);
            }
        }

        mCurrentCollectionRequest.addProtoBuf(GLocRequest.REQUEST_ELEMENTS, requestElement);

        // Immediately upload collection events if buffer exceeds certain size
        if (mCurrentCollectionRequest.getCount(GLocRequest.REQUEST_ELEMENTS)
            >= MAX_COLLECTION_BUFFER_SIZE) {
            immediate = true;
        }

        if (immediate) {
            // Request to send over wire
            uploadCollectionReport(immediate);
        }
    }

    /**
     * Uploads the collection report either immediately or based on MASF's queueing logic.
     * Does not need a reply back
     *
     * @param immediate true if request should be sent immediately instead of being queued
     */
    private synchronized void uploadCollectionReport(boolean immediate) {
        // There may be nothing to upload
        if (mCurrentCollectionRequest == null ||
            mCurrentCollectionRequest.getCount(GLocRequest.REQUEST_ELEMENTS) == 0) {
            return;
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            mCurrentCollectionRequest.outputTo(payload);
        } catch (IOException e) {
            Log.e(TAG, "uploadCollectionReport(): unable to write request to payload");
            return;
        }

        mLastCollectionUploadTime = SystemClock.elapsedRealtime();

        // Since this has already been written to the wire, we can clear this request
        int count = mCurrentCollectionRequest.getCount(GLocRequest.REQUEST_ELEMENTS);
        while (count > 0) {
            mCurrentCollectionRequest.remove(GLocRequest.REQUEST_ELEMENTS, count - 1);
            count--;
        }

        // Creates  request and a listener with a call back function
        ProtoBuf reply = new ProtoBuf(LocserverMessageTypes.GLOC_REPLY);
        Request plainRequest =
            new PlainRequest(REQUEST_UPLOAD_LOC, (short)0, payload.toByteArray());

        ProtoRequestListener listener = new ProtoRequestListener(reply, new ServiceCallback() {
            public void onRequestComplete(Object result) {
                ProtoBuf response = (ProtoBuf) result;

                if (response == null) {
                    Log.e(TAG, "uploadCollectionReport(): response is null");
                    return;
                }

                int status1 = response.getInt(GLocReply.STATUS);
                if (status1 != ResponseCodes.STATUS_STATUS_SUCCESS) {
                    Log.w(TAG, "uploadCollectionReport(): RPC failed with status " + status1);
                    return;
                }

                if (response.has(GLocReply.PLATFORM_KEY)) {
                    String platformKey = response.getString(GLocReply.PLATFORM_KEY);
                    if (!TextUtils.isEmpty(platformKey)) {
                        setPlatformKey(platformKey);
                    }
                }

                if (!response.has(GLocReply.REPLY_ELEMENTS)) {
                    Log.w(TAG, "uploadCollectionReport(): no ReplyElement");
                    return;
                }

                int count = response.getCount(GLocReply.REPLY_ELEMENTS);
                for (int i = 0; i < count; i++) {
                    ProtoBuf replyElement = response.getProtoBuf(GLocReply.REPLY_ELEMENTS, i);
                    int status2 = replyElement.getInt(GLocReplyElement.STATUS);
                    if (status2 != ResponseCodes.STATUS_STATUS_SUCCESS) {
                        Log.w(TAG, "uploadCollectionReport(): GLS failed with " + status2);
                    }
                }

            }
        });
        plainRequest.setListener(listener);

        // Send request
        MobileServiceMux serviceMux = MobileServiceMux.getSingleton();
        serviceMux.submitRequest(plainRequest, immediate);

    }

    private String getPlatformKey() {
        if (mPlatformKey != null) {
            return mPlatformKey;
        }

        try {
            File file = new File(LocationManager.SYSTEM_DIR, PLATFORM_KEY_FILE);
            FileInputStream istream = new FileInputStream(file);
            DataInputStream dataInput = new DataInputStream(istream);
            String platformKey = dataInput.readUTF();
            dataInput.close();
            mPlatformKey = platformKey;
            return mPlatformKey;
        } catch(FileNotFoundException e) {
            // No file, just ignore
            return null;
        } catch(IOException e) {
            // Unable to read from file, just ignore
            return null;
        }
    }

    private void setPlatformKey(String platformKey) {
        File systemDir = new File(LocationManager.SYSTEM_DIR);
        if (!systemDir.exists()) {
            if (!systemDir.mkdirs()) {
                Log.w(TAG, "setPlatformKey(): couldn't create directory");
                return;
            }
        }

        try {
            File file = new File(LocationManager.SYSTEM_DIR, PLATFORM_KEY_FILE);
            FileOutputStream ostream = new FileOutputStream(file);
            DataOutputStream dataOut = new DataOutputStream(ostream);
            dataOut.writeUTF(platformKey);
            dataOut.close();
            mPlatformKey = platformKey;
        } catch (FileNotFoundException e) {
            Log.w(TAG, "setPlatformKey(): unable to create platform key file");
        } catch (IOException e) {
            Log.w(TAG, "setPlatformKey(): unable to write to platform key");
        }
    }

    private ProtoBuf createPlatformProfile() {
        Locale locale = Locale.getDefault();
        return createPlatformProfile(locale);
    }

    private ProtoBuf createPlatformProfile(Locale locale) {
        if (mPlatformProfile == null) {
            mPlatformProfile = new ProtoBuf(GlocationMessageTypes.GPLATFORM_PROFILE);
            mPlatformProfile.setString(GPlatformProfile.VERSION, APPLICATION_VERSION);
            mPlatformProfile.setString(GPlatformProfile.PLATFORM, PLATFORM_BUILD);
        }

        // Add Locale
        if ((locale != null) && (locale.toString() != null)) {
            mPlatformProfile.setString(GPlatformProfile.LOCALE, locale.toString());
        }

        // Add Platform Key
        String platformKey = getPlatformKey();
        if (!TextUtils.isEmpty(platformKey)) {
            mPlatformProfile.setString(GPlatformProfile.PLATFORM_KEY, platformKey);
        }

        // Clear out cellular platform profile
        mPlatformProfile.setProtoBuf(GPlatformProfile.CELLULAR_PLATFORM_PROFILE, null);

        return mPlatformProfile;
    }

    private ProtoBuf createCellularPlatformProfile(CellState cellState) {
        // Radio type
        int radioType = -1;
        if (cellState.getRadioType() == CellState.RADIO_TYPE_GPRS) {
            radioType = GCellularPlatformProfile.RADIO_TYPE_GPRS;
        } else if (cellState.getRadioType() == CellState.RADIO_TYPE_CDMA) {
            radioType = GCellularPlatformProfile.RADIO_TYPE_CDMA;
        } else if (cellState.getRadioType() == CellState.RADIO_TYPE_WCDMA) {
            radioType = GCellularPlatformProfile.RADIO_TYPE_WCDMA;
        }

        if (mCellularPlatformProfile == null) {
            mCellularPlatformProfile =
                new ProtoBuf(GlocationMessageTypes.GCELLULAR_PLATFORM_PROFILE);
        }

        mCellularPlatformProfile.setInt(GCellularPlatformProfile.RADIO_TYPE, radioType);
        if ((cellState.getHomeMcc() != -1) && (cellState.getHomeMnc() != -1)) {
            mCellularPlatformProfile.setInt(GCellularPlatformProfile.HOME_MCC,
                cellState.getHomeMcc());
            mCellularPlatformProfile.setInt(GCellularPlatformProfile.HOME_MNC,
                cellState.getHomeMnc());
        }
        if (cellState.getCarrier() != null) {
            mCellularPlatformProfile.setString(GCellularPlatformProfile.CARRIER,
                cellState.getCarrier());
        }
        
        return mCellularPlatformProfile;
    }

    private void getAddressFromProtoBuf(ProtoBuf location, Locale locale, List<Address> addrs) {

        double lat = -1;
        double lng = -1;

        if (location.has(GLocation.LAT_LNG)) {
            ProtoBuf latlng = location.getProtoBuf(GLocation.LAT_LNG);
            lat = latlng.getInt(GLatLng.LAT_E7)/E7;
            lng = latlng.getInt(GLatLng.LNG_E7)/E7;
        }

        for (int a = 0; a < location.getCount(GLocation.FEATURE); a++) {

            Address output = new Address(locale);

            ProtoBuf feature = location.getProtoBuf(GLocation.FEATURE, a);
            output.setFeatureName(feature.getString(GFeature.NAME));

            if (feature.has(GFeature.CENTER)) {
                ProtoBuf center = feature.getProtoBuf(GFeature.CENTER);
                output.setLatitude(center.getInt(GLatLng.LAT_E7)/E7);
                output.setLongitude(center.getInt(GLatLng.LNG_E7)/E7);

            } else if (location.has(GLocation.LAT_LNG)) {
                output.setLatitude(lat);
                output.setLongitude(lng);
            }

            ProtoBuf address = feature.getProtoBuf(GFeature.ADDRESS);

            for (int i = 0; i < address.getCount(GAddress.FORMATTED_ADDRESS_LINE); i++) {
                String line = address.getString(GAddress.FORMATTED_ADDRESS_LINE, i);
                output.setAddressLine(i, line);
            }

            for (int i = 0; i < address.getCount(GAddress.COMPONENT); i++) {
                ProtoBuf component = address.getProtoBuf(GAddress.COMPONENT, i);
                int type = component.getInt(GAddressComponent.FEATURE_TYPE);
                String name = component.getString(GAddressComponent.NAME);

                switch(type) {
                    case GFeature.FEATURE_TYPE_ADMINISTRATIVE_AREA :
                        output.setAdminArea(name);
                        break;

                    case GFeature.FEATURE_TYPE_SUB_ADMINISTRATIVE_AREA :
                        output.setSubAdminArea(name);
                        break;

                    case GFeature.FEATURE_TYPE_LOCALITY :
                        output.setLocality(name);
                        break;

                    case GFeature.FEATURE_TYPE_THOROUGHFARE :
                        output.setThoroughfare(name);
                        break;

                    case GFeature.FEATURE_TYPE_POST_CODE :
                        output.setPostalCode(name);
                        break;

                    case GFeature.FEATURE_TYPE_COUNTRY :
                        output.setCountryName(name);
                        break;

                    case GFeature.FEATURE_TYPE_COUNTRY_CODE :
                        output.setCountryCode(name);
                        break;

                    default :
                        if (android.util.Config.LOGD) {
                        Log.d(TAG, "getAddressFromProtoBuf(): Ignore feature " + type + "," + name);
                        }
                        break;
                }
            }

            addrs.add(output);
        }
    }

    private void addNeighborsToCellProfile(CellState cellState, ProtoBuf cellularProfile) {
        List<CellState.NeighborCell> neighbors = cellState.getNeighbors();

        int mPrimaryMcc = cellState.getMcc();
        int mPrimaryMnc = cellState.getMnc();

        if (neighbors != null) {
            for (CellState.NeighborCell neighbor : neighbors) {
                ProtoBuf nCell = new ProtoBuf(GcellularMessageTypes.GCELL);
                nCell.setInt(GCell.CELLID, neighbor.getCid());
                nCell.setInt(GCell.LAC, neighbor.getLac());
                nCell.setInt(GCell.RSSI, neighbor.getRssi());
                if (neighbor.getPsc() != -1) {
                    nCell.setInt(GCell.PRIMARY_SCRAMBLING_CODE, neighbor.getPsc());
                }
                if (mPrimaryMcc != -1) {
                    nCell.setInt(GCell.MCC, mPrimaryMcc);
                }
                if (mPrimaryMnc != -1) {
                    nCell.setInt(GCell.MNC, mPrimaryMnc);
                }
                cellularProfile.addProtoBuf(GCellularProfile.NEIGHBORS, nCell);
            }
        }
    }

}
