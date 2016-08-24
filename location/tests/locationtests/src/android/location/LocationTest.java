/*
 * Copyright (C) 2007 Google Inc.
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

import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * Unit tests for android.location.Location
 */
@SmallTest
public class LocationTest extends TestCase {

    // ***** Tests for Location.convert
    public void testConvert_DegreesToDouble(){
        String testDegreesCoord = "-80.075";
        String message;
        double result;

        result = Location.convert(testDegreesCoord);
        message = "degreesToDoubleTest: Double should be -80.075, actual value is " +
                String.valueOf(result);
        assertEquals(message, -80.075, result);
    }
    
    public void testConvert_MinutesToDouble(){
        String testMinutesCoord = "-80:05.10000";
        String message;
        double result;

        result = Location.convert(testMinutesCoord);
        message = "minutesToDoubleTest: Double should be -80.085, actual value is " +
                String.valueOf(result);
        assertEquals(message, -80.085, result);
    }
    
    public void testConvert_SecondsToDouble(){
        String testSecondsCoord = "-80:04:03.00000";
        String message;
        double result;

        result = Location.convert(testSecondsCoord);
        message = "secondsToDoubleTest: Double should be -80.0675, actual value is " +
                String.valueOf(result);
        assertEquals(message, -80.0675, result);
    }
    
    public void testConvert_SecondsToDouble2(){
        String testSecondsCoord = "-80:4:3";
        String message;
        double result;

        result = Location.convert(testSecondsCoord);
        message = "secondsToDouble2Test: Double should be -80.0675, actual value is " +
                String.valueOf(result);
        assertEquals(message, -80.0675, result);
    }

    // Testing the Convert(Double, Int)
    public void testConvert_CoordinateToDegrees(){
        String message;
        String result;

        result = Location.convert(-80.075, Location.FORMAT_DEGREES);
        message = "coordinateToDegreesTest: Should return a string -80.075, but returned " + result;
        assertEquals(message, "-80.075", result);
    }
    
    public void testConvert_CoordinateToDegrees2(){
        String message;
        String result;
        result = Location.convert(-80.0, Location.FORMAT_DEGREES);
        message = "coordinateToDegrees2Test: Should return a string -80, but returned " + result;
        assertEquals(message, "-80", result);
    }
    
    public void testConvert_CoordinateToMinutes(){
        String message;
        String result;
        double input = -80.085;
        result = Location.convert(input, Location.FORMAT_MINUTES);
        message = "coordinateToMinuteTest: Should return a string -80:5.1, but returned " +
                result;
        assertEquals(message, "-80:5.1", result);
    }
    
    public void testConvert_CoordinateToMinutes2(){
        String message;
        String result;
        double input = -80;
        result = Location.convert(input, Location.FORMAT_MINUTES);
        message = "coordinateToMinute2Test: Should return a string -80:0, but returned " +
                result;
        assertEquals(message, "-80:0", result);
    }
    
    public void testConvert_CoordinateToSeconds(){
        String message;
        String result;

        result = Location.convert(-80.075, Location.FORMAT_SECONDS);
        message = "coordinateToSecondsTest: Should return a string -80:4:30, but returned " +
                result;
        assertEquals(message, "-80:4:30", result);
    }
    // **** end tests for Location.convert


    public void testBearingTo(){
        String message;
        float bearing;
        Location zeroLocation = new Location("");
        zeroLocation.setLatitude(0);
        zeroLocation.setLongitude(0);

        Location testLocation = new Location("");
        testLocation.setLatitude(1000000);
        testLocation.setLongitude(0);

        bearing = zeroLocation.bearingTo(zeroLocation);
        message = "bearingToTest: Bearing should be 0, actual value is " + String.valueOf(bearing);
        assertEquals(message, 0, bearing, 0);

        bearing = zeroLocation.bearingTo(testLocation);
        message = "bearingToTest: Bearing should be 180, actual value is " +
                String.valueOf(bearing);
        assertEquals(message, 180, bearing, 0);

        testLocation.setLatitude(0);
        testLocation.setLongitude(1000000);
        bearing = zeroLocation.bearingTo(testLocation);
        message = "bearingToTest: Bearing should be -90, actual value is " +
                String.valueOf(bearing);
        assertEquals(message, -90, bearing, 0);

        //TODO: Test a Random Middle Value
    }
    
    public void testDistanceTo() {
        String message;
        boolean result = true;
        float distance;
        Location zeroLocation = new Location("");
        zeroLocation.setLatitude(0);
        zeroLocation.setLongitude(0);

        Location testLocation = new Location("");
        testLocation.setLatitude(1000000);
        testLocation.setLongitude(0);

        distance = zeroLocation.distanceTo(zeroLocation);
        message = "distanceToTest: Distance should be 0, actual value is " +
        String.valueOf(distance);
        assertEquals(message, distance, 0, 0);

        distance = zeroLocation.distanceTo(testLocation);
        message = "distanceToTest: Distance should be 8885140, actual value is " +
        String.valueOf(distance);
        assertEquals(message, distance, 8885140.0, 1);
    }
    
    public void testAltitude() {
        String message;
        Location loc = new Location("");

        loc.setAltitude(1);
        message = "altitudeTest: set/getAltitude to 1 didn't work.";
        assertEquals(message, loc.getAltitude(), 1, 0);
        message = "altitudeTest: hasAltitude (a) didn't work.";
        assertTrue(message, loc.hasAltitude());

        loc.removeAltitude();
        message = "altitudeTest: hasAltitude (b) didn't work.";
        assertFalse(message, loc.hasAltitude());
        message = "altitudeTest: getAltitude didn't return 0 when there was no altitude.";
        assertEquals(message, loc.getAltitude(), 0, 0);
    }

    public void testSpeed() {
        String message;
        Location loc = new Location("");

        loc.setSpeed(1);
        message = "speedTest: set/getSpeed to 1 didn't work.";
        assertEquals(message, loc.getSpeed(), 1, 0);
        message = "speedTest: hasSpeed (a) didn't work.";
        assertTrue(message, loc.hasSpeed());

        loc.removeSpeed();
        message = "speedTest: hasSpeed (b) didn't work.";
        assertFalse(message, loc.hasSpeed());
        message = "speedTest: getSpeed didn't return 0 when there was no speed.";
        assertEquals(message, loc.getSpeed(), 0, 0);
    }

    public void testBearing() {
        String message;
        Location loc = new Location("");

        loc.setBearing(1);
        message = "bearingTest: set/getBearing to 1 didn't work.";
        assertEquals(message, loc.getBearing(), 1, 0);
        message = "bearingTest: hasBearing (a) didn't work.";
        assertTrue(message, loc.hasBearing());

        loc.removeBearing();
        message = "bearingTest: hasBearing (b) didn't work.";
        assertFalse(message, loc.hasBearing());
        message = "bearingTest: getBearing didn't return 0 when there was no bearing.";
        assertEquals(message, loc.getBearing(), 0, 0);
    }

    public void testParcel() {
        final double expectedLat = 33;
        final double expectedLon = -122;
        final float expectedAccuracy = 15;
        final float expectedSpeed = 5;
        Location loc = new Location("test");
        loc.setLatitude(expectedLat);
        loc.setLongitude(expectedLon);
        loc.setAccuracy(expectedAccuracy);
        loc.setSpeed(expectedSpeed);

        // Serialize location object into bytes via parcelable capability
        Parcel parcel = Parcel.obtain();
        loc.writeToParcel(parcel, 0);
        byte[] rawBytes = parcel.marshall();
        parcel.recycle();

        // Turn the bytes back into a location object
        parcel = Parcel.obtain();
        parcel.unmarshall(rawBytes, 0, rawBytes.length);
        parcel.setDataPosition(0);
        Location deserialized = Location.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertEquals(expectedLat, deserialized.getLatitude());
        assertEquals(expectedLon, deserialized.getLongitude());
        assertEquals(expectedAccuracy, deserialized.getAccuracy());
        assertTrue(deserialized.hasAccuracy());
        assertEquals(expectedSpeed, deserialized.getSpeed());
        assertTrue(deserialized.hasSpeed());
        assertFalse(deserialized.hasBearing());
        assertFalse(deserialized.hasAltitude());
        assertFalse(deserialized.isFromMockProvider());
    }
}


