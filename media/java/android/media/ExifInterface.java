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

package android.media;

import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * This is a class for reading and writing Exif tags in a JPEG file.
 */
public class ExifInterface {
    // The Exif tag names
    /** Type is int. */
    public static final String TAG_ORIENTATION = "Orientation";
    /** Type is String. */
    public static final String TAG_DATETIME = "DateTime";
    /** Type is String. */
    public static final String TAG_MAKE = "Make";
    /** Type is String. */
    public static final String TAG_MODEL = "Model";
    /** Type is int. */
    public static final String TAG_FLASH = "Flash";
    /** Type is int. */
    public static final String TAG_IMAGE_WIDTH = "ImageWidth";
    /** Type is int. */
    public static final String TAG_IMAGE_LENGTH = "ImageLength";
    /** String. Format is "num1/denom1,num2/denom2,num3/denom3". */
    public static final String TAG_GPS_LATITUDE = "GPSLatitude";
    /** String. Format is "num1/denom1,num2/denom2,num3/denom3". */
    public static final String TAG_GPS_LONGITUDE = "GPSLongitude";
    /** Type is String. */
    public static final String TAG_GPS_LATITUDE_REF = "GPSLatitudeRef";
    /** Type is String. */
    public static final String TAG_GPS_LONGITUDE_REF = "GPSLongitudeRef";
    /** Type is String. */
    public static final String TAG_EXPOSURE_TIME = "ExposureTime";
    /** Type is String. */
    public static final String TAG_APERTURE = "FNumber";
    /** Type is String. */
    public static final String TAG_ISO = "ISOSpeedRatings";

    /**
     * The altitude (in meters) based on the reference in TAG_GPS_ALTITUDE_REF.
     * Type is rational.
     */
    public static final String TAG_GPS_ALTITUDE = "GPSAltitude";

    /**
     * 0 if the altitude is above sea level. 1 if the altitude is below sea
     * level. Type is int.
     */
    public static final String TAG_GPS_ALTITUDE_REF = "GPSAltitudeRef";

    /** Type is String. */
    public static final String TAG_GPS_TIMESTAMP = "GPSTimeStamp";
    /** Type is String. */
    public static final String TAG_GPS_DATESTAMP = "GPSDateStamp";
    /** Type is int. */
    public static final String TAG_WHITE_BALANCE = "WhiteBalance";
    /** Type is rational. */
    public static final String TAG_FOCAL_LENGTH = "FocalLength";
    /** Type is String. Name of GPS processing method used for location finding. */
    public static final String TAG_GPS_PROCESSING_METHOD = "GPSProcessingMethod";

    // Constants used for the Orientation Exif tag.
    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_NORMAL = 1;
    public static final int ORIENTATION_FLIP_HORIZONTAL = 2;  // left right reversed mirror
    public static final int ORIENTATION_ROTATE_180 = 3;
    public static final int ORIENTATION_FLIP_VERTICAL = 4;  // upside down mirror
    public static final int ORIENTATION_TRANSPOSE = 5;  // flipped about top-left <--> bottom-right axis
    public static final int ORIENTATION_ROTATE_90 = 6;  // rotate 90 cw to right it
    public static final int ORIENTATION_TRANSVERSE = 7;  // flipped about top-right <--> bottom-left axis
    public static final int ORIENTATION_ROTATE_270 = 8;  // rotate 270 to right it

    // Constants used for white balance
    public static final int WHITEBALANCE_AUTO = 0;
    public static final int WHITEBALANCE_MANUAL = 1;
    private static SimpleDateFormat sFormatter;

    static {
        System.loadLibrary("exif_jni");
        sFormatter = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        sFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private String mFilename;
    private HashMap<String, String> mAttributes;
    private boolean mHasThumbnail;

    // Because the underlying implementation (jhead) uses static variables,
    // there can only be one user at a time for the native functions (and
    // they cannot keep state in the native code across function calls). We
    // use sLock to serialize the accesses.
    private static final Object sLock = new Object();

    /**
     * Reads Exif tags from the specified JPEG file.
     */
    public ExifInterface(String filename) throws IOException {
        if (filename == null) {
            throw new IllegalArgumentException("filename cannot be null");
        }
        mFilename = filename;
        loadAttributes();
    }

    /**
     * Returns the value of the specified tag or {@code null} if there
     * is no such tag in the JPEG file.
     *
     * @param tag the name of the tag.
     */
    public String getAttribute(String tag) {
        return mAttributes.get(tag);
    }

    /**
     * Returns the integer value of the specified tag. If there is no such tag
     * in the JPEG file or the value cannot be parsed as integer, return
     * <var>defaultValue</var>.
     *
     * @param tag the name of the tag.
     * @param defaultValue the value to return if the tag is not available.
     */
    public int getAttributeInt(String tag, int defaultValue) {
        String value = mAttributes.get(tag);
        if (value == null) return defaultValue;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * Returns the double value of the specified rational tag. If there is no
     * such tag in the JPEG file or the value cannot be parsed as double, return
     * <var>defaultValue</var>.
     *
     * @param tag the name of the tag.
     * @param defaultValue the value to return if the tag is not available.
     */
    public double getAttributeDouble(String tag, double defaultValue) {
        String value = mAttributes.get(tag);
        if (value == null) return defaultValue;
        try {
            int index = value.indexOf("/");
            if (index == -1) return defaultValue;
            double denom = Double.parseDouble(value.substring(index + 1));
            if (denom == 0) return defaultValue;
            double num = Double.parseDouble(value.substring(0, index));
            return num / denom;
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * Set the value of the specified tag.
     *
     * @param tag the name of the tag.
     * @param value the value of the tag.
     */
    public void setAttribute(String tag, String value) {
        mAttributes.put(tag, value);
    }

    /**
     * Initialize mAttributes with the attributes from the file mFilename.
     *
     * mAttributes is a HashMap which stores the Exif attributes of the file.
     * The key is the standard tag name and the value is the tag's value: e.g.
     * Model -> Nikon. Numeric values are stored as strings.
     *
     * This function also initialize mHasThumbnail to indicate whether the
     * file has a thumbnail inside.
     */
    private void loadAttributes() throws IOException {
        // format of string passed from native C code:
        // "attrCnt attr1=valueLen value1attr2=value2Len value2..."
        // example:
        // "4 attrPtr ImageLength=4 1024Model=6 FooImageWidth=4 1280Make=3 FOO"
        mAttributes = new HashMap<String, String>();

        String attrStr;
        synchronized (sLock) {
            attrStr = getAttributesNative(mFilename);
        }

        // get count
        int ptr = attrStr.indexOf(' ');
        int count = Integer.parseInt(attrStr.substring(0, ptr));
        // skip past the space between item count and the rest of the attributes
        ++ptr;

        for (int i = 0; i < count; i++) {
            // extract the attribute name
            int equalPos = attrStr.indexOf('=', ptr);
            String attrName = attrStr.substring(ptr, equalPos);
            ptr = equalPos + 1;     // skip past =

            // extract the attribute value length
            int lenPos = attrStr.indexOf(' ', ptr);
            int attrLen = Integer.parseInt(attrStr.substring(ptr, lenPos));
            ptr = lenPos + 1;       // skip pas the space

            // extract the attribute value
            String attrValue = attrStr.substring(ptr, ptr + attrLen);
            ptr += attrLen;

            if (attrName.equals("hasThumbnail")) {
                mHasThumbnail = attrValue.equalsIgnoreCase("true");
            } else {
                mAttributes.put(attrName, attrValue);
            }
        }
    }

    /**
     * Save the tag data into the JPEG file. This is expensive because it involves
     * copying all the JPG data from one file to another and deleting the old file
     * and renaming the other. It's best to use {@link #setAttribute(String,String)}
     * to set all attributes to write and make a single call rather than multiple
     * calls for each attribute.
     */
    public void saveAttributes() throws IOException {
        // format of string passed to native C code:
        // "attrCnt attr1=valueLen value1attr2=value2Len value2..."
        // example:
        // "4 attrPtr ImageLength=4 1024Model=6 FooImageWidth=4 1280Make=3 FOO"
        StringBuilder sb = new StringBuilder();
        int size = mAttributes.size();
        if (mAttributes.containsKey("hasThumbnail")) {
            --size;
        }
        sb.append(size + " ");
        for (Map.Entry<String, String> iter : mAttributes.entrySet()) {
            String key = iter.getKey();
            if (key.equals("hasThumbnail")) {
                // this is a fake attribute not saved as an exif tag
                continue;
            }
            String val = iter.getValue();
            sb.append(key + "=");
            sb.append(val.length() + " ");
            sb.append(val);
        }
        String s = sb.toString();
        synchronized (sLock) {
            saveAttributesNative(mFilename, s);
            commitChangesNative(mFilename);
        }
    }

    /**
     * Returns true if the JPEG file has a thumbnail.
     */
    public boolean hasThumbnail() {
        return mHasThumbnail;
    }

    /**
     * Returns the thumbnail inside the JPEG file, or {@code null} if there is no thumbnail.
     * The returned data is in JPEG format and can be decoded using
     * {@link android.graphics.BitmapFactory#decodeByteArray(byte[],int,int)}
     */
    public byte[] getThumbnail() {
        synchronized (sLock) {
            return getThumbnailNative(mFilename);
        }
    }

    /**
     * Returns the offset and length of thumbnail inside the JPEG file, or
     * {@code null} if there is no thumbnail.
     *
     * @return two-element array, the offset in the first value, and length in
     *         the second, or {@code null} if no thumbnail was found.
     * @hide
     */
    public long[] getThumbnailRange() {
        synchronized (sLock) {
            return getThumbnailRangeNative(mFilename);
        }
    }

    /**
     * Stores the latitude and longitude value in a float array. The first element is
     * the latitude, and the second element is the longitude. Returns false if the
     * Exif tags are not available.
     */
    public boolean getLatLong(float output[]) {
        String latValue = mAttributes.get(ExifInterface.TAG_GPS_LATITUDE);
        String latRef = mAttributes.get(ExifInterface.TAG_GPS_LATITUDE_REF);
        String lngValue = mAttributes.get(ExifInterface.TAG_GPS_LONGITUDE);
        String lngRef = mAttributes.get(ExifInterface.TAG_GPS_LONGITUDE_REF);

        if (latValue != null && latRef != null && lngValue != null && lngRef != null) {
            try {
                output[0] = convertRationalLatLonToFloat(latValue, latRef);
                output[1] = convertRationalLatLonToFloat(lngValue, lngRef);
                return true;
            } catch (IllegalArgumentException e) {
                // if values are not parseable
            }
        }

        return false;
    }

    /**
     * Return the altitude in meters. If the exif tag does not exist, return
     * <var>defaultValue</var>.
     *
     * @param defaultValue the value to return if the tag is not available.
     */
    public double getAltitude(double defaultValue) {
        double altitude = getAttributeDouble(TAG_GPS_ALTITUDE, -1);
        int ref = getAttributeInt(TAG_GPS_ALTITUDE_REF, -1);

        if (altitude >= 0 && ref >= 0) {
            return (double) (altitude * ((ref == 1) ? -1 : 1));
        } else {
            return defaultValue;
        }
    }

    /**
     * Returns number of milliseconds since Jan. 1, 1970, midnight.
     * Returns -1 if the date time information if not available.
     * @hide
     */
    public long getDateTime() {
        String dateTimeString = mAttributes.get(TAG_DATETIME);
        if (dateTimeString == null) return -1;

        ParsePosition pos = new ParsePosition(0);
        try {
            Date datetime = sFormatter.parse(dateTimeString, pos);
            if (datetime == null) return -1;
            return datetime.getTime();
        } catch (IllegalArgumentException ex) {
            return -1;
        }
    }

    /**
     * Returns number of milliseconds since Jan. 1, 1970, midnight UTC.
     * Returns -1 if the date time information if not available.
     * @hide
     */
    public long getGpsDateTime() {
        String date = mAttributes.get(TAG_GPS_DATESTAMP);
        String time = mAttributes.get(TAG_GPS_TIMESTAMP);
        if (date == null || time == null) return -1;

        String dateTimeString = date + ' ' + time;
        if (dateTimeString == null) return -1;

        ParsePosition pos = new ParsePosition(0);
        try {
            Date datetime = sFormatter.parse(dateTimeString, pos);
            if (datetime == null) return -1;
            return datetime.getTime();
        } catch (IllegalArgumentException ex) {
            return -1;
        }
    }

    private static float convertRationalLatLonToFloat(
            String rationalString, String ref) {
        try {
            String [] parts = rationalString.split(",");

            String [] pair;
            pair = parts[0].split("/");
            double degrees = Double.parseDouble(pair[0].trim())
                    / Double.parseDouble(pair[1].trim());

            pair = parts[1].split("/");
            double minutes = Double.parseDouble(pair[0].trim())
                    / Double.parseDouble(pair[1].trim());

            pair = parts[2].split("/");
            double seconds = Double.parseDouble(pair[0].trim())
                    / Double.parseDouble(pair[1].trim());

            double result = degrees + (minutes / 60.0) + (seconds / 3600.0);
            if ((ref.equals("S") || ref.equals("W"))) {
                return (float) -result;
            }
            return (float) result;
        } catch (NumberFormatException e) {
            // Some of the nubmers are not valid
            throw new IllegalArgumentException();
        } catch (ArrayIndexOutOfBoundsException e) {
            // Some of the rational does not follow the correct format
            throw new IllegalArgumentException();
        }
    }

    private native boolean appendThumbnailNative(String fileName,
            String thumbnailFileName);

    private native void saveAttributesNative(String fileName,
            String compressedAttributes);

    private native String getAttributesNative(String fileName);

    private native void commitChangesNative(String fileName);

    private native byte[] getThumbnailNative(String fileName);

    private native long[] getThumbnailRangeNative(String fileName);
}
