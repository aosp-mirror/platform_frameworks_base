// Copyright 2007 The Android Open Source Project

package com.android.internal.location;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import android.location.Criteria;
import android.location.Location;
import android.location.LocationProviderImpl;
import android.os.Bundle;
import android.util.Config;
import android.util.Log;

/**
 * A dummy provider that returns positions interpolated from a sequence
 * of caller-supplied waypoints.  The waypoints are supplied as a
 * String containing one or more numeric quadruples of the form:
 * <br>
 * <code>
 * <time in millis> <latitude> <longitude> <altitude>
 * </code>
 *
 * <p> The waypoints must be supplied in increasing timestamp order.
 *
 * <p> The time at which the provider is constructed is considered to
 * be time 0, and further requests for positions will return a
 * position that is linearly interpolated between the waypoints whose
 * timestamps are closest to the amount of wall clock time that has
 * elapsed since time 0.
 *
 * <p> Following the time of the last waypoint, the position of that
 * waypoint will continue to be returned indefinitely.
 *
 * {@hide}
 */
public class TrackProvider extends LocationProviderImpl {
    static final String LOG_TAG = "TrackProvider";

    private static final long INTERVAL = 1000L;

    private boolean mEnabled = true;

    private double mLatitude;
    private double mLongitude;
    private boolean mHasAltitude;
    private boolean mHasBearing;
    private boolean mHasSpeed;
    private double mAltitude;
    private float mBearing;
    private float mSpeed;
    private Bundle mExtras;

    private long mBaseTime;
    private long mLastTime = -1L;
    private long mTime;

    private long mMinTime;
    private long mMaxTime;

    private List<Waypoint> mWaypoints = new ArrayList<Waypoint>();
    private int mWaypointIndex = 0;

    private boolean mRequiresNetwork = false;
    private boolean mRequiresSatellite = false;
    private boolean mRequiresCell = false;
    private boolean mHasMonetaryCost = false;
    private boolean mSupportsAltitude = true;
    private boolean mSupportsSpeed = true;
    private boolean mSupportsBearing = true;
    private boolean mRepeat = false;
    private int mPowerRequirement = Criteria.POWER_LOW;
    private int mAccuracy = Criteria.ACCURACY_COARSE;

    private float mTrackSpeed = 100.0f; // km/hr - default for kml tracks

    private Location mInitialLocation;

    private void close(Reader rdr) {
        try {
            if (rdr != null) {
                rdr.close();
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "Exception closing reader", e);
        }
    }

    public void readTrack(File trackFile) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(trackFile), 8192);
            String s;

            long lastTime = -Long.MAX_VALUE;
            while ((s = br.readLine()) != null) {
                String[] tokens = s.split("\\s+");
                if (tokens.length != 4 && tokens.length != 6) {
                    Log.e(LOG_TAG, "Got track \"" + s +
                        "\", wanted <time> <long> <lat> <alt> [<bearing> <speed>]");
                    continue;
                }
                long time;
                double longitude, latitude, altitude;
                try {
                    time = Long.parseLong(tokens[0]);
                    longitude = Double.parseDouble(tokens[1]);
                    latitude = Double.parseDouble(tokens[2]);
                    altitude = Double.parseDouble(tokens[3]);
                } catch (NumberFormatException e) {
                    Log.e(LOG_TAG, "Got track \"" + s +
                        "\", wanted <time> <long> <lat> <alt> " +
                        "[<bearing> <speed>]", e);
                    continue;
                }

                Waypoint w = new Waypoint(getName(), time, latitude, longitude, altitude);
                if (tokens.length >= 6) {
                    float bearing, speed;
                    try {
                        bearing = Float.parseFloat(tokens[4]);
                        speed = Float.parseFloat(tokens[5]);
                        w.setBearing(bearing);
                        w.setSpeed(speed);
                    } catch (NumberFormatException e) {
                        Log.e(LOG_TAG, "Ignoring bearing and speed \"" +
                            tokens[4] + "\", \"" + tokens[5] + "\"", e);
                    }
                }

                if (mInitialLocation == null) {
                    mInitialLocation = w.getLocation();
                }

                // Ignore waypoints whose time is less than or equal to 0 or
                // the time of the previous waypoint
                if (time < 0) {
                    Log.e(LOG_TAG, "Ignoring waypoint at negative time=" + time);
                    continue;
                }
                if (time <= lastTime) {
                    Log.e(LOG_TAG, "Ignoring waypoint at time=" + time +
                        " (< " + lastTime + ")");
                    continue;
                }

                mWaypoints.add(w);
                lastTime = time;
            }

            setTimes();
            return;
        } catch (IOException e) {
            Log.e(LOG_TAG, "Exception reading track file", e);
            mWaypoints.clear();
        } finally {
            close(br);
        }
    }

    public void readKml(File kmlFile) {
        FileReader kmlReader = null;
        try {
            kmlReader = new FileReader(kmlFile);
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser xpp = factory.newPullParser();
            xpp.setInput(kmlReader);

            // Concatenate the text of each <coordinates> tag
            boolean inCoordinates = false;
            StringBuilder sb = new StringBuilder();
            int eventType = xpp.getEventType();
            do {
                if (eventType == XmlPullParser.START_DOCUMENT) {
                    // do nothing
                } else if (eventType == XmlPullParser.END_DOCUMENT) {
                    // do nothing
                } else if (eventType == XmlPullParser.START_TAG) {
                    String startTagName = xpp.getName();
                    if (startTagName.equals("coordinates")) {
                        inCoordinates = true;
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String endTagName = xpp.getName();
                    if (endTagName.equals("coordinates")) {
                        inCoordinates = false;
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if (inCoordinates) {
                        sb.append(xpp.getText());
                        sb.append(' ');
                    }
                }
                eventType = xpp.next();
            } while (eventType != XmlPullParser.END_DOCUMENT);

            String coordinates = sb.toString();

            // Parse the "lon,lat,alt" triples and supply times
            // for each waypoint based on a constant speed
            Location loc = null;
            double KM_PER_HOUR = mTrackSpeed;
            double KM_PER_METER = 1.0 / 1000.0;
            double MILLIS_PER_HOUR = 60.0 * 60.0 * 1000.0;
            double MILLIS_PER_METER =
                (1.0 / KM_PER_HOUR) * (KM_PER_METER) * (MILLIS_PER_HOUR);
            long time = 0L;

            StringTokenizer st = new StringTokenizer(coordinates, ", ");
            while (st.hasMoreTokens()) {
                try {
                    String lon = st.nextToken();
                    String lat = st.nextToken();
                    String alt = st.nextToken();
                    if (Config.LOGD) {
                        Log.d(LOG_TAG,
                            "lon=" + lon + ", lat=" + lat + ", alt=" + alt);
                    }

                    double nLongitude = Double.parseDouble(lon);
                    double nLatitude = Double.parseDouble(lat);
                    double nAltitude = Double.parseDouble(alt);

                    Location nLoc = new Location(getName());
                    nLoc.setLatitude(nLatitude);
                    nLoc.setLongitude(nLongitude);
                    if (loc != null) {
                        double distance = loc.distanceTo(nLoc);
                        if (Config.LOGD) {
                            Log.d(LOG_TAG, "distance = " + distance);
                        }
                        time += (long) (distance * MILLIS_PER_METER);
                    }

                    Waypoint w = new Waypoint(getName(), time,
                        nLatitude, nLongitude, nAltitude);
                    if (supportsSpeed()) {
                        w.setSpeed(mTrackSpeed);
                    }
                    if (supportsBearing()) {
                        w.setBearing(0.0f);
                    }
                    mWaypoints.add(w);

                    if (mInitialLocation == null) {
                        mInitialLocation = w.getLocation();
                    }

                    loc = nLoc;
                } catch (NumberFormatException nfe) {
                    Log.e(LOG_TAG, "Got NumberFormatException reading KML data: " +
                        nfe, nfe);
                }
            }

            setTimes();
            return;
        } catch (IOException ioe) {
            mWaypoints.clear();
            Log.e(LOG_TAG, "Exception reading KML data: " + ioe, ioe);
            // fall through
        } catch (XmlPullParserException xppe) {
            mWaypoints.clear();
            Log.e(LOG_TAG, "Exception reading KML data: " + xppe, xppe);
            // fall through
        } finally {
            close(kmlReader);
        }
    }

    public void readNmea(String name, File file) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file), 8192);
            String s;

            String provider = getName();
            NmeaParser parser = new NmeaParser(name);
            while ((s = br.readLine()) != null) {
                boolean newWaypoint = parser.parseSentence(s);
                if (newWaypoint) {
                    Location loc = parser.getLocation();
                    Waypoint w = new Waypoint(loc);
                    mWaypoints.add(w);
                    // Log.i(TAG, "Got waypoint " + w);
                }
            }

            setTimes();
            return;
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "Exception reading NMEA data: " + ioe);
            mWaypoints.clear();
        } finally {
            close(br);
        }
    }

    private static boolean booleanVal(String tf) {
        return (tf == null) || (tf.equalsIgnoreCase("true"));
    }

    private static int intVal(String val) {
        try {
            return (val == null) ? 0 : Integer.parseInt(val);
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    private static float floatVal(String val) {
        try {
            return (val == null) ? 0 : Float.parseFloat(val);
        } catch (NumberFormatException nfe) {
            return 0.0f;
        }
    }

    public void readProperties(File propertiesFile) {
        BufferedReader br = null;
        if (!propertiesFile.exists()) {
            return;
        }
        try {
            if (Config.LOGD) {
                Log.d(LOG_TAG, "Loading properties file " +
                    propertiesFile.getPath());
            }
            br = new BufferedReader(new FileReader(propertiesFile), 8192);

            String s;
            while ((s = br.readLine()) != null) {
                StringTokenizer st = new StringTokenizer(s);
                String command = null;
                String value = null;
                if (!st.hasMoreTokens()) {
                    continue;
                }
                command = st.nextToken();
                if (st.hasMoreTokens()) {
                    value = st.nextToken();
                }

                if (command.equalsIgnoreCase("requiresNetwork")) {
                    setRequiresNetwork(booleanVal(value));
                } else if (command.equalsIgnoreCase("requiresSatellite")) {
                    setRequiresSatellite(booleanVal(value));
                } else if (command.equalsIgnoreCase("requiresCell")) {
                    setRequiresCell(booleanVal(value));
                } else if (command.equalsIgnoreCase("hasMonetaryCost")) {
                    setHasMonetaryCost(booleanVal(value));
                } else if (command.equalsIgnoreCase("supportsAltitude")) {
                    setSupportsAltitude(booleanVal(value));
                } else if (command.equalsIgnoreCase("supportsBearing")) {
                    setSupportsBearing(booleanVal(value));
                } else if (command.equalsIgnoreCase("repeat")) {
                    setRepeat(booleanVal(value));
                } else if (command.equalsIgnoreCase("supportsSpeed")) {
                    setSupportsSpeed(booleanVal(value));
                } else if (command.equalsIgnoreCase("powerRequirement")) {
                    setPowerRequirement(intVal(value));
                } else if (command.equalsIgnoreCase("accuracy")) {
                    setAccuracy(intVal(value));
                } else if (command.equalsIgnoreCase("trackspeed")) {
                    setTrackSpeed(floatVal(value));
                } else {
                    Log.e(LOG_TAG, "Unknown command \"" + command + "\"");
                }
            }
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "IOException reading properties file " +
                propertiesFile.getPath(), ioe);
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException e) {
                Log.w(LOG_TAG, "IOException closing properties file " +
                    propertiesFile.getPath(), e);
            }
        }
    }

    public TrackProvider(String name) {
        super(name);
        setTimes();
    }

    public TrackProvider(String name, File file) {
        this(name);

        String filename = file.getName();
        if (filename.endsWith("kml")) {
            readKml(file);
        } else if (filename.endsWith("nmea")) {
            readNmea(getName(), file);
        } else if (filename.endsWith("track")) {
            readTrack(file);
        } else {
            Log.e(LOG_TAG, "Can't initialize TrackProvider from file " +
                filename + " (not *kml, *nmea, or *track)");
        }
        setTimes();
    }

    private void setTimes() {
        mBaseTime = System.currentTimeMillis();
        if (mWaypoints.size() >= 2) {
            mMinTime = mWaypoints.get(0).getTime();
            mMaxTime = mWaypoints.get(mWaypoints.size() - 1).getTime();
        } else {
            mMinTime = mMaxTime = 0;
        }
    }

    private double interp(double d0, double d1, float frac) {
        return d0 + frac * (d1 - d0);
    }

    private void update() {
        // Don't update the position at all unless INTERVAL milliseconds
        // have passed since the last request
        long time = System.currentTimeMillis() - mBaseTime;
        if (time - mLastTime < INTERVAL) {
            return;
        }

        List<Waypoint> waypoints = mWaypoints;
        if (waypoints == null) {
            return;
        }
        int size = waypoints.size();
        if (size < 2) {
            return;
        }

        long t = time;
        if (t < mMinTime) {
            t = mMinTime;
        }
        if (mRepeat) {
            t -= mMinTime;
            long deltaT = mMaxTime - mMinTime;
            t %= 2 * deltaT;
            if (t > deltaT) {
                t = 2 * deltaT - t;
            }
            t += mMinTime;
        } else if (t > mMaxTime) {
            t = mMaxTime;
        }

        // Locate the time interval for the current time
        // We slide the window since we don't expect to move
        // much between calls

        Waypoint w0 = waypoints.get(mWaypointIndex);
        Waypoint w1 = waypoints.get(mWaypointIndex + 1);

        // If the right end of the current interval is too early,
        // move forward to the next waypoint
        while (t > w1.getTime()) {
            w0 = w1;
            w1 = waypoints.get(++mWaypointIndex + 1);
        }
        // If the left end of the current interval is too late,
        // move back to the previous waypoint
        while (t < w0.getTime()) {
            w1 = w0;
            w0 = waypoints.get(--mWaypointIndex);
        }

        // Now we know that w0.mTime <= t <= w1.mTime
        long w0Time = w0.getTime();
        long w1Time = w1.getTime();
        long dt = w1Time - w0Time;

        float frac = (dt == 0) ? 0 : ((float) (t - w0Time) / dt);
        mLatitude  = interp(w0.getLatitude(), w1.getLatitude(), frac);
        mLongitude = interp(w0.getLongitude(), w1.getLongitude(), frac);
        mHasAltitude = w0.hasAltitude() && w1.hasAltitude();
        if (mSupportsAltitude && mHasAltitude) {
            mAltitude  = interp(w0.getAltitude(), w1.getAltitude(), frac);
        }
        if (mSupportsBearing) {
            mHasBearing = frac <= 0.5f ? w0.hasBearing() : w1.hasBearing();
            if (mHasBearing) {
                mBearing  = frac <= 0.5f ? w0.getBearing() : w1.getBearing();
            }
        }
        if (mSupportsSpeed) {
            mHasSpeed = frac <= 0.5f ? w0.hasSpeed() : w1.hasSpeed();
            if (mHasSpeed) {
                mSpeed  = frac <= 0.5f ? w0.getSpeed() : w1.getSpeed();
            }
        }
        mLastTime = time;
        mTime = time;
    }

    public void setRequiresNetwork(boolean requiresNetwork) {
        mRequiresNetwork = requiresNetwork;
    }

    @Override public boolean requiresNetwork() {
        return mRequiresNetwork;
    }

    public void setRequiresSatellite(boolean requiresSatellite) {
        mRequiresSatellite = requiresSatellite;
    }

    @Override public boolean requiresSatellite() {
        return mRequiresSatellite;
    }

    public void setRequiresCell(boolean requiresCell) {
        mRequiresCell = requiresCell;
    }

    @Override public boolean requiresCell() {
        return mRequiresCell;
    }

    public void setHasMonetaryCost(boolean hasMonetaryCost) {
        mHasMonetaryCost = hasMonetaryCost;
    }

    @Override public boolean hasMonetaryCost() {
        return mHasMonetaryCost;
    }

    public void setSupportsAltitude(boolean supportsAltitude) {
        mSupportsAltitude = supportsAltitude;
    }

    @Override public boolean supportsAltitude() {
        return mSupportsAltitude;
    }

    public void setSupportsSpeed(boolean supportsSpeed) {
        mSupportsSpeed = supportsSpeed;
    }

    @Override public boolean supportsSpeed() {
        return mSupportsSpeed;
    }

    public void setSupportsBearing(boolean supportsBearing) {
        mSupportsBearing = supportsBearing;
    }

    @Override public boolean supportsBearing() {
        return mSupportsBearing;
    }

    public void setRepeat(boolean repeat) {
        mRepeat = repeat;
    }

    public void setPowerRequirement(int powerRequirement) {
        if (powerRequirement < Criteria.POWER_LOW ||
            powerRequirement > Criteria.POWER_HIGH) {
            throw new IllegalArgumentException("powerRequirement = " +
                powerRequirement);
        }
        mPowerRequirement = powerRequirement;
    }

    @Override public int getPowerRequirement() {
        return mPowerRequirement;
    }

    public void setAccuracy(int accuracy) {
        mAccuracy = accuracy;
    }

    @Override public int getAccuracy() {
        return mAccuracy;
    }

    public void setTrackSpeed(float trackSpeed) {
        mTrackSpeed = trackSpeed;
    }

    @Override public void enable() {
        mEnabled = true;
    }

    @Override public void disable() {
        mEnabled = false;
    }

    @Override public boolean isEnabled() {
        return mEnabled;
    }

    @Override public int getStatus(Bundle extras) {
        return AVAILABLE;
    }

    @Override public boolean getLocation(Location l) {
        if (mEnabled) {
            update();
            l.setProvider(getName());
            l.setTime(mTime + mBaseTime);
            l.setLatitude(mLatitude);
            l.setLongitude(mLongitude);
            if (mSupportsAltitude && mHasAltitude) {
                l.setAltitude(mAltitude);
            }
            if (mSupportsBearing && mHasBearing) {
                l.setBearing(mBearing);
            }
            if (mSupportsSpeed && mHasSpeed) {
                l.setSpeed(mSpeed);
            }
            if (mExtras != null) {
                l.setExtras(mExtras);
            }
            return true;
        } else {
            return false;
        }
    }

    public Location getInitialLocation() {
        return mInitialLocation;
    }
}

/**
 * A simple tuple of (time stamp, latitude, longitude, altitude), plus optional
 * extras.
 *
 * {@hide}
 */
class Waypoint {
    public Location mLocation;

    public Waypoint(Location location) {
        mLocation = location;
    }

    public Waypoint(String providerName, long time, double latitude, double longitude,
        double altitude) {
        mLocation = new Location(providerName);
        mLocation.setTime(time);
        mLocation.setLatitude(latitude);
        mLocation.setLongitude(longitude);
        mLocation.setAltitude(altitude);
    }

    public long getTime() {
        return mLocation.getTime();
    }

    public double getLatitude() {
        return mLocation.getLatitude();
    }

    public double getLongitude() {
        return mLocation.getLongitude();
    }

    public boolean hasAltitude() {
        return mLocation.hasAltitude();
    }

    public double getAltitude() {
        return mLocation.getAltitude();
    }

    public boolean hasBearing() {
        return mLocation.hasBearing();
    }

    public void setBearing(float bearing) {
        mLocation.setBearing(bearing);
    }

    public float getBearing() {
        return mLocation.getBearing();
    }

    public boolean hasSpeed() {
        return mLocation.hasSpeed();
    }

    public void setSpeed(float speed) {
        mLocation.setSpeed(speed);
    }

    public float getSpeed() {
        return mLocation.getSpeed();
    }

    public Bundle getExtras() {
        return mLocation.getExtras();
    }

    public Location getLocation() {
        return new Location(mLocation);
    }

    @Override public String toString() {
        return "Waypoint[mLocation=" + mLocation + "]";
    }
}
