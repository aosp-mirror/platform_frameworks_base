/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.location;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.util.Config;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * This class provides access to the system location services.  These
 * services allow applications to obtain periodic updates of the
 * device's geographical location, or to fire an application-specified
 * {@link Intent} when the device enters the proximity of a given
 * geographical location.
 *
 * <p>You do not
 * instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService
 * Context.getSystemService(Context.LOCATION_SERVICE)}.
 */
public class LocationManager {
    private static final String TAG = "LocationManager";
    private ILocationManager mService;
    private HashMap<GpsStatusListener, GpsStatusListenerTransport> mGpsStatusListeners =
            new HashMap<GpsStatusListener, GpsStatusListenerTransport>();

    /**
     * Name of the network location provider.  This provider determines location based on
     * availability of cell tower and WiFi access points. Results are retrieved
     * by means of a network lookup.
     *
     * Requires either of the permissions android.permission.ACCESS_COARSE_LOCATION
     * or android.permission.ACCESS_FINE_LOCATION.
     */
    public static final String NETWORK_PROVIDER = "network";

    /**
     * Name of the GPS location provider. This provider determines location using
     * satellites. Depending on conditions, this provider may take a while to return
     * a location fix.
     *
     * Requires the permission android.permissions.ACCESS_FINE_LOCATION.
     *
     * <p> The extras Bundle for the GPS location provider can contain the
     * following key/value pairs:
     *
     * <ul>
     * <li> satellites - the number of satellites used to derive the fix
     * </ul>
     */
    public static final String GPS_PROVIDER = "gps";

    /**
     * Key used for the Bundle extra holding a boolean indicating whether
     * a proximity alert is entering (true) or exiting (false)..
     */
    public static final String KEY_PROXIMITY_ENTERING = "entering";

    /** @hide -- does this belong here? */
    public static final String PROVIDER_DIR = "/data/location";

    /** @hide */
    public static final String SYSTEM_DIR = "/data/system/location";

    // Map from LocationListeners to their associated ListenerTransport objects
    private HashMap<LocationListener,ListenerTransport> mListeners =
        new HashMap<LocationListener,ListenerTransport>();

    private class ListenerTransport extends ILocationListener.Stub {
        private static final int TYPE_LOCATION_CHANGED = 1;
        private static final int TYPE_STATUS_CHANGED = 2;
        private static final int TYPE_PROVIDER_ENABLED = 3;
        private static final int TYPE_PROVIDER_DISABLED = 4;

        private LocationListener mListener;
        private final Handler mListenerHandler;

        ListenerTransport(LocationListener listener, Looper looper) {
            mListener = listener;

            if (looper == null) {
                mListenerHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        _handleMessage(msg);
                    }
                };
            } else {
                mListenerHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        _handleMessage(msg);
                    }
                };
            }
        }

        public void onLocationChanged(Location location) {
            Message msg = Message.obtain();
            msg.what = TYPE_LOCATION_CHANGED;
            msg.obj = location;
            mListenerHandler.sendMessage(msg);
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
            Message msg = Message.obtain();
            msg.what = TYPE_STATUS_CHANGED;
            Bundle b = new Bundle();
            b.putString("provider", provider);
            b.putInt("status", status);
            if (extras != null) {
                b.putBundle("extras", extras);
            }
            msg.obj = b;
            mListenerHandler.sendMessage(msg);
        }

        public void onProviderEnabled(String provider) {
            Message msg = Message.obtain();
            msg.what = TYPE_PROVIDER_ENABLED;
            msg.obj = provider;
            mListenerHandler.sendMessage(msg);
        }

        public void onProviderDisabled(String provider) {
            Message msg = Message.obtain();
            msg.what = TYPE_PROVIDER_DISABLED;
            msg.obj = provider;
            mListenerHandler.sendMessage(msg);
        }

        private void _handleMessage(Message msg) {
            switch (msg.what) {
                case TYPE_LOCATION_CHANGED:
                    Location location = new Location((Location) msg.obj);
                    mListener.onLocationChanged(location);
                    break;
                case TYPE_STATUS_CHANGED:
                    Bundle b = (Bundle) msg.obj;
                    String provider = b.getString("provider");
                    int status = b.getInt("status");
                    Bundle extras = b.getBundle("extras");
                    mListener.onStatusChanged(provider, status, extras);
                    break;
                case TYPE_PROVIDER_ENABLED:
                    mListener.onProviderEnabled((String) msg.obj);
                    break;
                case TYPE_PROVIDER_DISABLED:
                    mListener.onProviderDisabled((String) msg.obj);
                    break;
            }
        }
    }
    /**
     * @hide - hide this constructor because it has a parameter
     * of type ILocationManager, which is a system private class. The
     * right way to create an instance of this class is using the 
     * factory Context.getSystemService.
     */
    public LocationManager(ILocationManager service) {
        if (Config.LOGD) {
            Log.d(TAG, "Constructor: service = " + service);
        }
        mService = service;
    }

    private LocationProvider createProvider(String name, Bundle info) {
        DummyLocationProvider provider =
            new DummyLocationProvider(name);
        provider.setRequiresNetwork(info.getBoolean("network"));
        provider.setRequiresSatellite(info.getBoolean("satellite"));
        provider.setRequiresCell(info.getBoolean("cell"));
        provider.setHasMonetaryCost(info.getBoolean("cost"));
        provider.setSupportsAltitude(info.getBoolean("altitude"));
        provider.setSupportsSpeed(info.getBoolean("speed"));
        provider.setSupportsBearing(info.getBoolean("bearing"));
        provider.setPowerRequirement(info.getInt("power"));
        provider.setAccuracy(info.getInt("accuracy"));
        return provider;
    }

    /**
     * Returns a list of the names of all known location providers.  All
     * providers are returned, including ones that are not permitted to be
     * accessed by the calling activity or are currently disabled.
     *
     * @return list of Strings containing names of the providers
     */
    public List<String> getAllProviders() {
        if (Config.LOGD) {
            Log.d(TAG, "getAllProviders");
        }
        try {
            return mService.getAllProviders();
        } catch (RemoteException ex) {
            Log.e(TAG, "getAllProviders: RemoteException", ex);
        }
        return null;
    }

    /**
     * Returns a list of the names of location providers.  Only providers that
     * are permitted to be accessed by the calling activity will be returned.
     *
     * @param enabledOnly if true then only the providers which are currently
     * enabled are returned.
     * @return list of Strings containing names of the providers
     */
    public List<String> getProviders(boolean enabledOnly) {
        try {
            return mService.getProviders(enabledOnly);
        } catch (RemoteException ex) {
            Log.e(TAG, "getProviders: RemoteException", ex);
        }
        return null;
    }

    /**
     * Returns the information associated with the location provider of the
     * given name, or null if no provider exists by that name.
     *
     * @param name the provider name
     * @return a LocationProvider, or null
     *
     * @throws IllegalArgumentException if name is null
     * @throws SecurityException if the caller is not permitted to access the
     * given provider.
     */
    public LocationProvider getProvider(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name==null");
        }
        try {
            Bundle info = mService.getProviderInfo(name);
            if (info == null) {
                return null;
            }
            return createProvider(name, info);
        } catch (RemoteException ex) {
            Log.e(TAG, "getProvider: RemoteException", ex);
        }
        return null;
    }

    /**
     * Returns a list of the names of LocationProviders that satisfy the given
     * criteria, or null if none do.  Only providers that are permitted to be
     * accessed by the calling activity will be returned.
     *
     * @param criteria the criteria that the returned providers must match
     * @param enabledOnly if true then only the providers which are currently
     * enabled are returned.
     * @return list of Strings containing names of the providers
     */
    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        List<String> goodProviders = Collections.emptyList();
        List<String> providers = getProviders(enabledOnly);
        for (String providerName : providers) {
            LocationProvider provider = getProvider(providerName);
            if (provider.meetsCriteria(criteria)) {
                if (goodProviders.isEmpty()) {
                    goodProviders = new ArrayList<String>();
                }
                goodProviders.add(providerName);
            }
        }
        return goodProviders;
    }

    /**
     * Propagates the enabled/disabled state of the providers from the system
     * settings to the providers themselves.
     *
     * {@hide}
     */
    public void updateProviders() {
        try {
            mService.updateProviders();
        } catch (RemoteException ex) {
            Log.e(TAG, "updateProviders: RemoteException", ex);
        }
    }

    /**
     * Returns the next looser power requirement, in the sequence:
     *
     * POWER_LOW -> POWER_MEDIUM -> POWER_HIGH -> NO_REQUIREMENT
     */
    private int nextPower(int power) {
        switch (power) {
        case Criteria.POWER_LOW:
            return Criteria.POWER_MEDIUM;
        case Criteria.POWER_MEDIUM:
            return Criteria.POWER_HIGH;
        case Criteria.POWER_HIGH:
            return Criteria.NO_REQUIREMENT;
        case Criteria.NO_REQUIREMENT:
        default:
            return Criteria.NO_REQUIREMENT;
        }
    }

    /**
     * Returns the next looser accuracy requirement, in the sequence:
     *
     * ACCURACY_FINE -> ACCURACY_APPROXIMATE-> NO_REQUIREMENT
     */
    private int nextAccuracy(int accuracy) {
        if (accuracy == Criteria.ACCURACY_FINE) {
            return Criteria.ACCURACY_COARSE;
        } else {
            return Criteria.NO_REQUIREMENT;
        }
    }

    private abstract class LpComparator implements Comparator<LocationProvider> {

        public int compare(int a1, int a2) {
            if (a1 < a2) {
                return -1;
            } else if (a1 > a2) {
                return 1;
            } else {
                return 0;
            }
        }

        public int compare(float a1, float a2) {
            if (a1 < a2) {
                return -1;
            } else if (a1 > a2) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    private class LpPowerComparator extends LpComparator {
        public int compare(LocationProvider l1, LocationProvider l2) {
            int a1 = l1.getPowerRequirement();
            int a2 = l2.getPowerRequirement();
            return compare(a1, a2); // Smaller is better
         }

         public boolean equals(LocationProvider l1, LocationProvider l2) {
             int a1 = l1.getPowerRequirement();
             int a2 = l2.getPowerRequirement();
             return a1 == a2;
         }
    }

    private class LpAccuracyComparator extends LpComparator {
        public int compare(LocationProvider l1, LocationProvider l2) {
            int a1 = l1.getAccuracy();
            int a2 = l2.getAccuracy();
            return compare(a1, a2); // Smaller is better
         }

         public boolean equals(LocationProvider l1, LocationProvider l2) {
             int a1 = l1.getAccuracy();
             int a2 = l2.getAccuracy();
             return a1 == a2;
         }
    }

    private class LpCapabilityComparator extends LpComparator {

        private static final int ALTITUDE_SCORE = 4;
        private static final int BEARING_SCORE = 4;
        private static final int SPEED_SCORE = 4;

        private int score(LocationProvider p) {
            return (p.supportsAltitude() ? ALTITUDE_SCORE : 0) +
                (p.supportsBearing() ? BEARING_SCORE : 0) +
                (p.supportsSpeed() ? SPEED_SCORE : 0);
        }

        public int compare(LocationProvider l1, LocationProvider l2) {
            int a1 = score(l1);
            int a2 = score(l2);
            return compare(-a1, -a2); // Bigger is better
         }

         public boolean equals(LocationProvider l1, LocationProvider l2) {
             int a1 = score(l1);
             int a2 = score(l2);
             return a1 == a2;
         }
    }

    private LocationProvider best(List<String> providerNames) {
        List<LocationProvider> providers = new ArrayList<LocationProvider>(providerNames.size());
        for (String name : providerNames) {
            providers.add(getProvider(name));
        }

        if (providers.size() < 2) {
            return providers.get(0);
        }

        // First, sort by power requirement
        Collections.sort(providers, new LpPowerComparator());
        int power = providers.get(0).getPowerRequirement();
        if (power < providers.get(1).getPowerRequirement()) {
            return providers.get(0);
        }

        int idx, size;

        List<LocationProvider> tmp = new ArrayList<LocationProvider>();
        idx = 0;
        size = providers.size();
        while ((idx < size) && (providers.get(idx).getPowerRequirement() == power)) {
            tmp.add(providers.get(idx));
            idx++;
        }

        // Next, sort by accuracy
        Collections.sort(tmp, new LpAccuracyComparator());
        int acc = tmp.get(0).getAccuracy();
        if (acc < tmp.get(1).getAccuracy()) {
            return tmp.get(0);
        }

        List<LocationProvider> tmp2 = new ArrayList<LocationProvider>();
        idx = 0;
        size = tmp.size();
        while ((idx < size) && (tmp.get(idx).getAccuracy() == acc)) {
            tmp2.add(tmp.get(idx));
            idx++;
        }

        // Finally, sort by capability "score"
        Collections.sort(tmp2, new LpCapabilityComparator());
        return tmp2.get(0);
    }

    /**
     * Returns the name of the provider that best meets the given criteria. Only providers
     * that are permitted to be accessed by the calling activity will be
     * returned.  If several providers meet the criteria, the one with the best
     * accuracy is returned.  If no provider meets the criteria,
     * the criteria are loosened in the following sequence:
     *
     * <ul>
     * <li> power requirement
     * <li> accuracy
     * <li> bearing
     * <li> speed
     * <li> altitude
     * </ul>
     *
     * <p> Note that the requirement on monetary cost is not removed
     * in this process.
     *
     * @param criteria the criteria that need to be matched
     * @param enabledOnly if true then only a provider that is currently enabled is returned
     * @return name of the provider that best matches the requirements
     */
    public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        List<String> goodProviders = getProviders(criteria, enabledOnly);
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        // Make a copy of the criteria that we can modify
        criteria = new Criteria(criteria);

        // Loosen power requirement
        int power = criteria.getPowerRequirement();
        while (goodProviders.isEmpty() && (power != Criteria.NO_REQUIREMENT)) {
            power = nextPower(power);
            criteria.setPowerRequirement(power);
            goodProviders = getProviders(criteria, enabledOnly);
        }
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

//        // Loosen response time requirement
//        int responseTime = criteria.getPreferredResponseTime();
//        while (goodProviders.isEmpty() &&
//            (responseTime != Criteria.NO_REQUIREMENT)) {
//            responseTime += 1000;
//            if (responseTime > 60000) {
//                responseTime = Criteria.NO_REQUIREMENT;
//            }
//            criteria.setPreferredResponseTime(responseTime);
//            goodProviders = getProviders(criteria);
//        }
//        if (!goodProviders.isEmpty()) {
//            return best(goodProviders);
//        }

        // Loosen accuracy requirement
        int accuracy = criteria.getAccuracy();
        while (goodProviders.isEmpty() && (accuracy != Criteria.NO_REQUIREMENT)) {
            accuracy = nextAccuracy(accuracy);
            criteria.setAccuracy(accuracy);
            goodProviders = getProviders(criteria, enabledOnly);
        }
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        // Remove bearing requirement
        criteria.setBearingRequired(false);
        goodProviders = getProviders(criteria, enabledOnly);
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        // Remove speed requirement
        criteria.setSpeedRequired(false);
        goodProviders = getProviders(criteria, enabledOnly);
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        // Remove altitude requirement
        criteria.setAltitudeRequired(false);
        goodProviders = getProviders(criteria, enabledOnly);
        if (!goodProviders.isEmpty()) {
            return best(goodProviders).getName();
        }

        return null;
    }

    /**
     * Registers the current activity to be notified periodically by
     * the named provider.  Periodically, the supplied LocationListener will
     * be called with the current Location or with status updates.
     *
     * <p> It may take a while to receive the most recent location. If
     * an immediate location is required, applications may use the
     * {@link #getLastKnownLocation(String)} method.
     *
     * <p> In case the provider is disabled by the user, updates will stop,
     * and the {@link LocationListener#onProviderDisabled(String)}
     * method will be called. As soon as the provider is enabled again,
     * the {@link LocationListener#onProviderEnabled(String)} method will
     * be called and location updates will start again.
     *
     * <p> The frequency of notification may be controlled using the
     * minTime and minDistance parameters. If minTime is greater than 0,
     * the LocationManager could potentially rest for minTime milliseconds
     * between location updates to conserve power. If minDistance is greater than 0,
     * a location will only be broadcasted if the device moves by minDistance meters.
     * To obtain notifications as frequently as possible, set both parameters to 0.
     *
     * <p> Background services should be careful about setting a sufficiently high
     * minTime so that the device doesn't consume too much power by keeping the
     * GPS or wireless radios on all the time. In particular, values under 60000ms
     * are not recommended.
     *
     * <p> The calling thread must be a {@link android.os.Looper} thread such as
     * the main thread of the calling Activity.
     *
     * @param provider the name of the provider with which to register
     * @param minTime the minimum time interval for notifications, in
     * milliseconds. This field is only used as a hint to conserve power, and actual
     * time between location updates may be greater or lesser than this value.
     * @param minDistance the minimum distance interval for notifications,
     * in meters
     * @param listener a {#link LocationListener} whose
     * {@link LocationListener#onLocationChanged} method will be called for
     * each location update
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if listener is null
     * @throws RuntimeException if the calling thread has no Looper
     * @throws SecurityException if no suitable permission is present for the provider.
     */
    public void requestLocationUpdates(String provider,
        long minTime, float minDistance, LocationListener listener) {
        if (provider == null) {
            throw new IllegalArgumentException("provider==null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener==null");
        }
        _requestLocationUpdates(provider, minTime, minDistance, listener, null);
    }

    /**
     * Registers the current activity to be notified periodically by
     * the named provider.  Periodically, the supplied LocationListener will
     * be called with the current Location or with status updates.
     *
     * <p> It may take a while to receive the most recent location. If
     * an immediate location is required, applications may use the
     * {@link #getLastKnownLocation(String)} method.
     *
     * <p> In case the provider is disabled by the user, updates will stop,
     * and the {@link LocationListener#onProviderDisabled(String)}
     * method will be called. As soon as the provider is enabled again,
     * the {@link LocationListener#onProviderEnabled(String)} method will
     * be called and location updates will start again.
     *
     * <p> The frequency of notification may be controlled using the
     * minTime and minDistance parameters. If minTime is greater than 0,
     * the LocationManager could potentially rest for minTime milliseconds
     * between location updates to conserve power. If minDistance is greater than 0,
     * a location will only be broadcasted if the device moves by minDistance meters.
     * To obtain notifications as frequently as possible, set both parameters to 0.
     *
     * <p> Background services should be careful about setting a sufficiently high
     * minTime so that the device doesn't consume too much power by keeping the
     * GPS or wireless radios on all the time. In particular, values under 60000ms
     * are not recommended.
     *
     * <p> The supplied Looper is used to implement the callback mechanism.
     *
     * @param provider the name of the provider with which to register
     * @param minTime the minimum time interval for notifications, in
     * milliseconds. This field is only used as a hint to conserve power, and actual
     * time between location updates may be greater or lesser than this value.
     * @param minDistance the minimum distance interval for notifications,
     * in meters
     * @param listener a {#link LocationListener} whose
     * {@link LocationListener#onLocationChanged} method will be called for
     * each location update
     * @param looper a Looper object whose message queue will be used to
     * implement the callback mechanism.
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if listener is null
     * @throws IllegalArgumentException if looper is null
     * @throws SecurityException if no suitable permission is present for the provider.
     */
    public void requestLocationUpdates(String provider,
        long minTime, float minDistance, LocationListener listener,
        Looper looper) {
        if (provider == null) {
            throw new IllegalArgumentException("provider==null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener==null");
        }
        if (looper == null) {
            throw new IllegalArgumentException("looper==null");
        }
        _requestLocationUpdates(provider, minTime, minDistance, listener, looper);
    }

    private void _requestLocationUpdates(String provider,
        long minTime, float minDistance, LocationListener listener,
        Looper looper) {
        if (minTime < 0L) {
            minTime = 0L;
        }
        if (minDistance < 0.0f) {
            minDistance = 0.0f;
        }

        try {
            synchronized (mListeners) {
                ListenerTransport transport = mListeners.get(listener);
                if (transport == null) {
                    transport = new ListenerTransport(listener, looper);
                    mListeners.put(listener, transport);
                }
                mListeners.put(listener, transport);
                mService.requestLocationUpdates(provider, minTime, minDistance,
                    transport);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "requestLocationUpdates: DeadObjectException", ex);
        }
    }

    /**
     * Removes any current registration for location updates of the current activity
     * with the given LocationListener.  Following this call, updates will no longer
     * occur for this listener.
     *
     * @param listener {#link LocationListener} object that no longer needs location updates
     * @throws IllegalArgumentException if listener is null
     */
    public void removeUpdates(LocationListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener==null");
        }
        if (Config.LOGD) {
            Log.d(TAG, "removeUpdates: listener = " + listener);
        }
        try {
            ListenerTransport transport = mListeners.remove(listener);
            if (transport != null) {
                mService.removeUpdates(transport);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "removeUpdates: DeadObjectException", ex);
        }
    }

    /**
     * Sets a proximity alert for the location given by the position
     * (latitude, longitude) and the given radius.  When the device
     * detects that it has entered or exited the area surrounding the
     * location, the given PendingIntent will be used to create an Intent
     * to be fired.
     *
     * <p> The fired Intent will have a boolean extra added with key
     * {@link #KEY_PROXIMITY_ENTERING}. If the value is true, the device is
     * entering the proximity region; if false, it is exiting.
     *
     * <p> Due to the approximate nature of position estimation, if the
     * device passes through the given area briefly, it is possible
     * that no Intent will be fired.  Similarly, an Intent could be
     * fired if the device passes very close to the given area but
     * does not actually enter it.
     *
     * <p> After the number of milliseconds given by the expiration
     * parameter, the location manager will delete this proximity
     * alert and no longer monitor it.  A value of -1 indicates that
     * there should be no expiration time.
     *
     * <p> In case the screen goes to sleep, checks for proximity alerts
     * happen only once every 4 minutes. This conserves battery life by
     * ensuring that the device isn't perpetually awake.
     *
     * <p> Internally, this method uses both {@link #NETWORK_PROVIDER}
     * and {@link #GPS_PROVIDER}.
     *
     * @param latitude the latitude of the central point of the
     * alert region
     * @param longitude the longitude of the central point of the
     * alert region
     * @param radius the radius of the central point of the
     * alert region, in meters
     * @param expiration time for this proximity alert, in milliseconds,
     * or -1 to indicate no expiration
     * @param intent a PendingIntent that will be used to generate an Intent to
     * fire when entry to or exit from the alert region is detected
     *
     * @throws SecurityException if no permission exists for the required
     * providers.
     */
    public void addProximityAlert(double latitude, double longitude,
        float radius, long expiration, PendingIntent intent) {
        if (Config.LOGD) {
            Log.d(TAG, "addProximityAlert: latitude = " + latitude +
                ", longitude = " + longitude + ", radius = " + radius +
                ", expiration = " + expiration +
                ", intent = " + intent);
        }
        try {
            mService.addProximityAlert(latitude, longitude, radius,
                                       expiration, intent);
        } catch (RemoteException ex) {
            Log.e(TAG, "addProximityAlert: RemoteException", ex);
        }
    }

    /**
     * Removes the proximity alert with the given PendingIntent.
     *
     * @param intent the PendingIntent that no longer needs to be notified of
     * proximity alerts
     */
    public void removeProximityAlert(PendingIntent intent) {
        if (Config.LOGD) {
            Log.d(TAG, "removeProximityAlert: intent = " + intent);
        }
        try {
            mService.removeProximityAlert(intent);
        } catch (RemoteException ex) {
            Log.e(TAG, "removeProximityAlert: RemoteException", ex);
        }
    }

    /**
     * Returns the current enabled/disabled status of the given provider. If the
     * user has enabled this provider in the Settings menu, true is returned
     * otherwise false is returned
     *
     * @param provider the name of the provider
     * @return true if the provider is enabled
     *
     * @throws SecurityException if no suitable permission is present for the provider.
     * @throws IllegalArgumentException if provider is null or doesn't exist
     */
    public boolean isProviderEnabled(String provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider==null");
        }
        try {
            return mService.isProviderEnabled(provider);
        } catch (RemoteException ex) {
            Log.e(TAG, "isProviderEnabled: RemoteException", ex);
            return false;
        }
    }

    /**
     * Returns a Location indicating the data from the last known
     * location fix obtained from the given provider.  This can be done
     * without starting the provider.  Note that this location could
     * be out-of-date, for example if the device was turned off and
     * moved to another location.
     *
     * <p> If the provider is currently disabled, null is returned.
     *
     * @param provider the name of the provider
     * @return the last known location for the provider, or null
     *
     * @throws SecurityException if no suitable permission is present for the provider.
     * @throws IllegalArgumentException if provider is null or doesn't exist
     */
    public Location getLastKnownLocation(String provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider==null");
        }
        try {
            return mService.getLastKnownLocation(provider);
        } catch (RemoteException ex) {
            Log.e(TAG, "getLastKnowLocation: RemoteException", ex);
            return null;
        }
    }

    // Mock provider support

    /**
     * Creates a mock location provider and adds it to the set of active providers.
     *
     * @param name the provider name
     * @param requiresNetwork
     * @param requiresSatellite
     * @param requiresCell
     * @param hasMonetaryCost
     * @param supportsAltitude
     * @param supportsSpeed
     * @param supportsBearing
     * @param powerRequirement
     * @param accuracy
     *
     * @throws SecurityException if the ACCESS_MOCK_LOCATION permission is not present
     * @throws IllegalArgumentException if a provider with the given name already exists
     *
     * {@hide}
     */
    public void addTestProvider(String name, boolean requiresNetwork, boolean requiresSatellite,
        boolean requiresCell, boolean hasMonetaryCost, boolean supportsAltitude,
        boolean supportsSpeed, boolean supportsBearing, int powerRequirement, int accuracy) {
        try {
            mService.addTestProvider(name, requiresNetwork, requiresSatellite, requiresCell,
                hasMonetaryCost, supportsAltitude, supportsSpeed, supportsBearing, powerRequirement,
                accuracy);
        } catch (RemoteException ex) {
            Log.e(TAG, "addTestProvider: RemoteException", ex);
        }
    }

    /**
     * Removes the mock location provider with the given name.
     *
     * @param provider the provider name
     *
     * @throws SecurityException if the ACCESS_MOCK_LOCATION permission is not present
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * {@hide}
     */
    public void removeTestProvider(String provider) {
        try {
            mService.removeTestProvider(provider);
        } catch (RemoteException ex) {
            Log.e(TAG, "removeTestProvider: RemoteException", ex);
        }
    }

    /**
     * Sets a mock location for the given provider.  This location will be used in place
     * of any actual location from the provider.
     *
     * @param provider the provider name
     * @param loc the mock location
     *
     * @throws SecurityException if the ACCESS_MOCK_LOCATION permission is not present
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * {@hide}
     */
    public void setTestProviderLocation(String provider, Location loc) {
        try {
            mService.setTestProviderLocation(provider, loc);
        } catch (RemoteException ex) {
            Log.e(TAG, "setTestProviderLocation: RemoteException", ex);
        }
    }

    /**
     * Removes any mock location associated with the given provider.
     *
     * @param provider the provider name
     *
     * @throws SecurityException if the ACCESS_MOCK_LOCATION permission is not present
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * {@hide}
     */
    public void clearTestProviderLocation(String provider) {
        try {
            mService.clearTestProviderLocation(provider);
        } catch (RemoteException ex) {
            Log.e(TAG, "clearTestProviderLocation: RemoteException", ex);
        }
    }

    /**
     * Sets a mock enabled value for the given provider.  This value will be used in place
     * of any actual value from the provider.
     *
     * @param provider the provider name
     * @param enabled the mock enabled value
     *
     * @throws SecurityException if the ACCESS_MOCK_LOCATION permission is not present
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * {@hide}
     */
    public void setTestProviderEnabled(String provider, boolean enabled) {
        try {
            mService.setTestProviderEnabled(provider, enabled);
        } catch (RemoteException ex) {
            Log.e(TAG, "setTestProviderEnabled: RemoteException", ex);
        }
    }

    /**
     * Removes any mock enabled value associated with the given provider.
     *
     * @param provider the provider name
     *
     * @throws SecurityException if the ACCESS_MOCK_LOCATION permission is not present
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * {@hide}
     */
    public void clearTestProviderEnabled(String provider) {
        try {
            mService.clearTestProviderEnabled(provider);
        } catch (RemoteException ex) {
            Log.e(TAG, "clearTestProviderEnabled: RemoteException", ex);
        }

    }

    /**
     * Sets mock status values for the given provider.  These values will be used in place
     * of any actual values from the provider.
     *
     * @param provider the provider name
     * @param status the mock status
     * @param extras a Bundle containing mock extras
     * @param updateTime the mock update time
     *
     * @throws SecurityException if the ACCESS_MOCK_LOCATION permission is not present
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * {@hide}
     */
    public void setTestProviderStatus(String provider, int status, Bundle extras, long updateTime) {
        try {
            mService.setTestProviderStatus(provider, status, extras, updateTime);
        } catch (RemoteException ex) {
            Log.e(TAG, "setTestProviderStatus: RemoteException", ex);
        }
    }

    /**
     * Removes any mock status values associated with the given provider.
     *
     * @param provider the provider name
     *
     * @throws SecurityException if the ACCESS_MOCK_LOCATION permission is not present
     * @throws IllegalArgumentException if no provider with the given name exists
     *
     * {@hide}
     */
    public void clearTestProviderStatus(String provider) {
        try {
            mService.clearTestProviderStatus(provider);
        } catch (RemoteException ex) {
            Log.e(TAG, "clearTestProviderStatus: RemoteException", ex);
        }
    }

    // GPS-specific support

    // This class is used to send GPS status events to the client's main thread.
    private class GpsStatusListenerTransport extends IGpsStatusListener.Stub {

        private GpsStatusListener mListener;
        private int mTTFF;
        private int mSvCount;
        private int[] mPrns;
        private float[] mSnrs;
        private float[] mElevations;
        private float[] mAzimuths;
        private int mEphemerisMask;
        private int mAlmanacMask;
        private int mUsedInFixMask;

        private static final int GPS_STARTED = 0;
        private static final int GPS_STOPPED = 1;
        private static final int GPS_FIRST_FIX = 2;
        private static final int GPS_SV_STATUS = 3;

        GpsStatusListenerTransport(GpsStatusListener listener) {
            mListener = listener;
        }

        public void onGpsStarted() {
            Message msg = Message.obtain();
            msg.what = GPS_STARTED;
            mGpsHandler.sendMessage(msg);
        }

        public void onGpsStopped() {
            Message msg = Message.obtain();
            msg.what = GPS_STOPPED;
            mGpsHandler.sendMessage(msg);
        }

        public void onFirstFix(int ttff) {
            mTTFF = ttff;
            Message msg = Message.obtain();
            msg.what = GPS_FIRST_FIX;
            mGpsHandler.sendMessage(msg);
        }

        public void onSvStatusChanged(int svCount, int[] prns, float[] snrs,
                float[] elevations, float[] azimuths, int ephemerisMask,
                int almanacMask, int usedInFixMask) {
            // synchronize here to ensure SV count matches data in the arrays
            synchronized(this) {
                mSvCount = svCount;
                mPrns = prns;
                mSnrs = snrs;
                mElevations = elevations;
                mAzimuths = azimuths;
                mEphemerisMask = ephemerisMask;
                mAlmanacMask = almanacMask;
                mUsedInFixMask = usedInFixMask;
            }

            Message msg = Message.obtain();
            msg.what = GPS_SV_STATUS;
            // remove any SV status messages already in the queue
            mGpsHandler.removeMessages(GPS_SV_STATUS);
            mGpsHandler.sendMessage(msg);
        }

        private final Handler mGpsHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case GPS_STARTED:
                        mListener.onGpsStarted();
                        break;
                    case GPS_STOPPED:
                        mListener.onGpsStopped();
                        break;
                    case GPS_FIRST_FIX:
                        mListener.onFirstFix(mTTFF);
                        break;
                    case GPS_SV_STATUS:
                        // synchronize here to ensure SV count matches data in the arrays
                        synchronized(this) {
                            mListener.onSvStatusChanged(mSvCount, mPrns, mSnrs, mElevations,
                                    mAzimuths, mEphemerisMask, mAlmanacMask, mUsedInFixMask);
                        break;
                    }
                }
            }
        };
    }

    /**
     * Registers a GPS status listener.
     *
     * @param listener GPS status listener object to register.
     *
     * @return true if the listener was successfully registered.
     *
     * {@hide}
     */
    public boolean registerGpsStatusListener(GpsStatusListener listener) {
        boolean result;

        if (mGpsStatusListeners.get(listener) != null) {
            // listener is already registered
            return true;
        }
        try {
            GpsStatusListenerTransport transport = new GpsStatusListenerTransport(listener);
            result = mService.addGpsStatusListener(transport);
            if (result) {
                mGpsStatusListeners.put(listener, transport);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in registerGpsStatusListener: ", e);
            result = false;
        }

        return result;
    }

    /**
     * Unegisters a GPS status listener.
     *
     * @param listener GPS status listener object to unregister.
     *
     * {@hide}
     */
    public void unregisterGpsStatusListener(GpsStatusListener listener) {
        try {
            GpsStatusListenerTransport transport = mGpsStatusListeners.remove(listener);
            if (transport != null) {
                mService.removeGpsStatusListener(transport);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in unregisterGpsStatusListener: ", e);
        }
    }
    
    /**
     * Sends additional commands to a location provider.
     * Can be used to support provider specific extensions to the Location Manager API
     *
     * @param provider name of the location provider.
     * @param command name of the command to send to the provider.
     * @param extras optional arguments for the command (or null).
     * The provider may optionally fill the extras Bundle with results from the command.
     *
     * @return true if the command succeeds. 
     *
     * {@hide}
     */
    public boolean sendExtraCommand(String provider, String command, Bundle extras) {
        try {
            return mService.sendExtraCommand(provider, command, extras);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in sendExtraCommand: ", e);
            return false;
        }
    }
}
