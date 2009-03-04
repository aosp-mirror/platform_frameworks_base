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

package com.android.internal.location;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import android.location.Location;
import android.os.Bundle;
import android.util.Log;

/**
 * {@hide}
 */
public class NmeaParser {

    private static final String TAG = "NmeaParser";

    private static final TimeZone sUtcTimeZone = TimeZone.getTimeZone("UTC");

    private static final float KNOTS_TO_METERS_PER_SECOND = 0.51444444444f;

    private final String mName;

    private int mYear = -1;
    private int mMonth;
    private int mDay;

    private long mTime = -1;
    private long mBaseTime;
    private double mLatitude;
    private double mLongitude;

    private boolean mHasAltitude;
    private double mAltitude;
    private boolean mHasBearing;
    private float mBearing;
    private boolean mHasSpeed;
    private float mSpeed;

    private boolean mNewWaypoint = false;
    private Location mLocation = null;
    private Bundle mExtras;

    public NmeaParser(String name) {
        mName = name;
    }

    private boolean updateTime(String time) {
        if (time.length() < 6) {
            return false;
        }
        if (mYear == -1) {
            // Since we haven't seen a day/month/year yet,
            // we can't construct a meaningful time stamp.
            // Clean up any old data.
            mLatitude = 0.0;
            mLongitude = 0.0;
            mHasAltitude = false;
            mHasBearing = false;
            mHasSpeed = false;
            mExtras = null;
            return false;
        }

        int hour, minute;
        float second;
        try {
            hour = Integer.parseInt(time.substring(0, 2));
            minute = Integer.parseInt(time.substring(2, 4));
            second = Float.parseFloat(time.substring(4, time.length()));
        } catch (NumberFormatException nfe) {
            Log.e(TAG, "Error parsing timestamp " + time);
            return false;
        }

        int isecond = (int) second;
        int millis = (int) ((second - isecond) * 1000);
        Calendar c = new GregorianCalendar(sUtcTimeZone);
        c.set(mYear, mMonth, mDay, hour, minute, isecond);
        long newTime = c.getTimeInMillis() + millis;

        if (mTime == -1) {
            mTime = 0;
            mBaseTime = newTime;
        }
        newTime -= mBaseTime;

        // If the timestamp has advanced, copy the temporary data
        // into a new Location
        if (newTime != mTime) {
            mNewWaypoint = true;
            mLocation = new Location(mName);
            mLocation.setTime(mTime);
            mLocation.setLatitude(mLatitude);
            mLocation.setLongitude(mLongitude);
            if (mHasAltitude) {
                mLocation.setAltitude(mAltitude);
            }
            if (mHasBearing) {
                mLocation.setBearing(mBearing);
            }
            if (mHasSpeed) {
                mLocation.setSpeed(mSpeed);
            }
            mLocation.setExtras(mExtras);
            mExtras = null;

            mTime = newTime;
            mHasAltitude = false;
            mHasBearing = false;
            mHasSpeed = false;
        }
        return true;
    }

    private boolean updateDate(String date) {
        if (date.length() != 6) {
            return false;
        }
        int month, day, year;
        try {
            day = Integer.parseInt(date.substring(0, 2));
            month = Integer.parseInt(date.substring(2, 4));
            year = 2000 + Integer.parseInt(date.substring(4, 6));
        } catch (NumberFormatException nfe) {
            Log.e(TAG, "Error parsing date " + date);
            return false;
        }

        mYear = year;
        mMonth = month;
        mDay = day;
        return true;
    }

    private boolean updateTime(String time, String date) {
        if (!updateDate(date)) {
                return false;
        }
        return updateTime(time);
    }

    private boolean updateIntExtra(String name, String value) {
        int val;
        try {
            val = Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            Log.e(TAG, "Exception parsing int " + name + ": " + value, nfe);
            return false;
        }
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putInt(name, val);
        return true;
    }

    private boolean updateFloatExtra(String name, String value) {
        float val;
        try {
            val = Float.parseFloat(value);
        } catch (NumberFormatException nfe) {
            Log.e(TAG, "Exception parsing float " + name + ": " + value, nfe);
            return false;
        }
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putFloat(name, val);
        return true;
    }

    private boolean updateDoubleExtra(String name, String value) {
        double val;
        try {
            val = Double.parseDouble(value);
        } catch (NumberFormatException nfe) {
            Log.e(TAG, "Exception parsing double " + name + ": " + value, nfe);
            return false;
        }
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        mExtras.putDouble(name, val);
        return true;
    }

    private double convertFromHHMM(String coord) {
        double val = Double.parseDouble(coord);
        int degrees = ((int) Math.floor(val)) / 100;
        double minutes = val - (degrees * 100);
        double dcoord = degrees + minutes / 60.0;
        return dcoord;
    }

    private boolean updateLatLon(String latitude, String latitudeHemi,
        String longitude, String longitudeHemi) {
        if (latitude.length() == 0 || longitude.length() == 0) {
            return false;
        }

        // Lat/long values are expressed as {D}DDMM.MMMM
        double lat, lon;
        try {
            lat = convertFromHHMM(latitude);
            if (latitudeHemi.charAt(0) == 'S') {
                lat = -lat;
            }
        } catch (NumberFormatException nfe1) {
            Log.e(TAG, "Exception parsing lat/long: " + nfe1, nfe1);
            return false;
        }

        try {
            lon = convertFromHHMM(longitude);
            if (longitudeHemi.charAt(0) == 'W') {
                lon = -lon;
            }
        } catch (NumberFormatException nfe2) {
            Log.e(TAG, "Exception parsing lat/long: " + nfe2, nfe2);
            return false;
        }

        // Only update if both were parsed cleanly
        mLatitude = lat;
        mLongitude = lon;
        return true;
    }

    private boolean updateAltitude(String altitude) {
        if (altitude.length() == 0) {
            return false;
        }
        double alt;
        try {
            alt = Double.parseDouble(altitude);
        } catch (NumberFormatException nfe) {
            Log.e(TAG, "Exception parsing altitude " + altitude + ": " + nfe,
                  nfe);
            return false;
        }

        mHasAltitude = true;
        mAltitude = alt;
        return true;
    }

    private boolean updateBearing(String bearing) {
        float brg;
        try {
            brg = Float.parseFloat(bearing);
        } catch (NumberFormatException nfe) {
            Log.e(TAG, "Exception parsing bearing " + bearing + ": " + nfe,
                  nfe);
            return false;
        }

        mHasBearing = true;
        mBearing = brg;
        return true;
    }

    private boolean updateSpeed(String speed) {
        float spd;
        try {
            spd = Float.parseFloat(speed) * KNOTS_TO_METERS_PER_SECOND;
        } catch (NumberFormatException nfe) {
            Log.e(TAG, "Exception parsing speed " + speed + ": " + nfe, nfe);
            return false;
        }

        mHasSpeed = true;
        mSpeed = spd;
        return true;
    }

    public boolean parseSentence(String s) {
        int len = s.length();
        if (len < 9) {
            return false;
        }
        if (s.charAt(len - 3) == '*') {
            // String checksum = s.substring(len - 4, len);
            s = s.substring(0, len - 3);
        }
        String[] tokens = s.split(",");
        String sentenceId = tokens[0].substring(3, 6);

        int idx = 1;
        try {
            if (sentenceId.equals("GGA")) {
                String time = tokens[idx++];
                String latitude = tokens[idx++];
                String latitudeHemi = tokens[idx++];
                String longitude = tokens[idx++];
                String longitudeHemi = tokens[idx++];
                String fixQuality = tokens[idx++];
                String numSatellites = tokens[idx++];
                String horizontalDilutionOfPrecision = tokens[idx++];
                String altitude = tokens[idx++];
                String altitudeUnits = tokens[idx++];
                String heightOfGeoid = tokens[idx++];
                String heightOfGeoidUnits = tokens[idx++];
                String timeSinceLastDgpsUpdate = tokens[idx++];

                updateTime(time);
                updateLatLon(latitude, latitudeHemi,
                        longitude, longitudeHemi);
                updateAltitude(altitude);
                // updateQuality(fixQuality);
                updateIntExtra("numSatellites", numSatellites);
                updateFloatExtra("hdop", horizontalDilutionOfPrecision);

                if (mNewWaypoint) {
                    mNewWaypoint = false;
                    return true;
                }
            } else if (sentenceId.equals("GSA")) {
                // DOP and active satellites
                String selectionMode = tokens[idx++]; // m=manual, a=auto 2d/3d
                String mode = tokens[idx++]; // 1=no fix, 2=2d, 3=3d
                for (int i = 0; i < 12; i++) {
                    String id = tokens[idx++];
                }
                String pdop = tokens[idx++];
                String hdop = tokens[idx++];
                String vdop = tokens[idx++];

                // TODO - publish satellite ids
                updateFloatExtra("pdop", pdop);
                updateFloatExtra("hdop", hdop);
                updateFloatExtra("vdop", vdop);
            } else if (sentenceId.equals("GSV")) {
                // Satellites in view
                String numMessages = tokens[idx++];
                String messageNum = tokens[idx++];
                String svsInView = tokens[idx++];
                for (int i = 0; i < 4; i++) {
                    if (idx + 2 < tokens.length) {
                        String prnNumber = tokens[idx++];
                        String elevation = tokens[idx++];
                        String azimuth = tokens[idx++];
                        if (idx < tokens.length) {
                            String snr = tokens[idx++];
                        }
                    }
                }
                // TODO - publish this info
            } else if (sentenceId.equals("RMC")) {
                // Recommended minimum navigation information
                String time = tokens[idx++];
                String fixStatus = tokens[idx++];
                String latitude = tokens[idx++];
                String latitudeHemi = tokens[idx++];
                String longitude = tokens[idx++];
                String longitudeHemi = tokens[idx++];
                String speed = tokens[idx++];
                String bearing = tokens[idx++];
                String utcDate = tokens[idx++];
                String magneticVariation = tokens[idx++];
                String magneticVariationDir = tokens[idx++];
                String mode = tokens[idx++];

                if (fixStatus.charAt(0) == 'A') {
                    updateTime(time, utcDate);
                    updateLatLon(latitude, latitudeHemi,
                        longitude, longitudeHemi);
                    updateBearing(bearing);
                    updateSpeed(speed);
                }

                if (mNewWaypoint) {
                    return true;
                }
            } else {
                Log.e(TAG, "Unknown sentence: " + s);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            // do nothing - sentence will have no effect
            Log.e(TAG, "AIOOBE", e);

            for (int i = 0; i < tokens.length; i++) {
                Log.e(TAG, "Got token #" + i + " = " + tokens[i]);
            }
        }

        return false;
    }

//  } else if (sentenceId.equals("GLL")) {
//  // Geographics position lat/long
//  String latitude = tokens[idx++];
//  String latitudeHemi = tokens[idx++];
//  String longitude = tokens[idx++];
//  String longitudeHemi = tokens[idx++];
//  String time = tokens[idx++];
//  String status = tokens[idx++];
//  String mode = tokens[idx++];
//  String checksum = tokens[idx++];
//
//  if (status.charAt(0) == 'A') {
//      updateTime(time);
//      updateLatLon(latitude, latitudeHemi, longitude, longitudeHemi);
//  }
//} else if (sentenceId.equals("VTG")) {
//    String trackMadeGood = tokens[idx++];
//    String t = tokens[idx++];
//    String unused1 = tokens[idx++];
//    String unused2 = tokens[idx++];
//    String groundSpeedKnots = tokens[idx++];
//    String n = tokens[idx++];
//    String groundSpeedKph = tokens[idx++];
//    String k = tokens[idx++];
//    String checksum = tokens[idx++];
//
//    updateSpeed(groundSpeedKph);

    public Location getLocation() {
        return mLocation;
    }
}
