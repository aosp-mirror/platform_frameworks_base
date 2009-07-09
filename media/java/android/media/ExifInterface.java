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

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper for native Exif library
 * {@hide}
 */
public class ExifInterface {
    private static final String TAG = "ExifInterface";
    private String mFilename;

    // Constants used for the Orientation Exif tag.
    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_NORMAL = 1;

    // Constants used for white balance
    public static final int WHITEBALANCE_AUTO = 0;
    public static final int WHITEBALANCE_MANUAL = 1;

    // left right reversed mirror
    public static final int ORIENTATION_FLIP_HORIZONTAL = 2;
    public static final int ORIENTATION_ROTATE_180 = 3;

    // upside down mirror
    public static final int ORIENTATION_FLIP_VERTICAL = 4;

    // flipped about top-left <--> bottom-right axis
    public static final int ORIENTATION_TRANSPOSE = 5;

    // rotate 90 cw to right it
    public static final int ORIENTATION_ROTATE_90 = 6;

    // flipped about top-right <--> bottom-left axis
    public static final int ORIENTATION_TRANSVERSE = 7;

    // rotate 270 to right it
    public static final int ORIENTATION_ROTATE_270 = 8;

    // The Exif tag names
    public static final String TAG_ORIENTATION = "Orientation";

    public static final String TAG_DATE_TIME_ORIGINAL = "DateTimeOriginal";
    public static final String TAG_MAKE = "Make";
    public static final String TAG_MODEL = "Model";
    public static final String TAG_FLASH = "Flash";
    public static final String TAG_IMAGE_WIDTH = "ImageWidth";
    public static final String TAG_IMAGE_LENGTH = "ImageLength";

    public static final String TAG_GPS_LATITUDE = "GPSLatitude";
    public static final String TAG_GPS_LONGITUDE = "GPSLongitude";

    public static final String TAG_GPS_LATITUDE_REF = "GPSLatitudeRef";
    public static final String TAG_GPS_LONGITUDE_REF = "GPSLongitudeRef";
    public static final String TAG_WHITE_BALANCE = "WhiteBalance";

    private boolean mSavedAttributes = false;
    private boolean mHasThumbnail = false;
    private HashMap<String, String> mCachedAttributes = null;

    static {
        System.loadLibrary("exif");
    }

    private static ExifInterface sExifObj = null;
    /**
     * Since the underlying jhead native code is not thread-safe,
     * ExifInterface should use singleton interface instead of public
     * constructor.
     */
    private static synchronized ExifInterface instance() {
        if (sExifObj == null) {
            sExifObj = new ExifInterface();
        }

        return sExifObj;
    }

    /**
     * The following 3 static methods are handy routines for atomic operation
     * of underlying jhead library. It retrieves EXIF data and then release
     * ExifInterface immediately.
     */
    public static synchronized HashMap<String, String> loadExifData(String filename) {
        ExifInterface exif = instance();
        HashMap<String, String> exifData = null;
        if (exif != null) {
            exif.setFilename(filename);
            exifData = exif.getAttributes();
        }
        return exifData;
    }

    public static synchronized void saveExifData(String filename, HashMap<String, String> exifData) {
        ExifInterface exif = instance();
        if (exif != null) {
            exif.setFilename(filename);
            exif.saveAttributes(exifData);
        }
    }

    public static synchronized byte[] getExifThumbnail(String filename) {
        ExifInterface exif = instance();
        if (exif != null) {
            exif.setFilename(filename);
            return exif.getThumbnail();
        }
        return null;
    }

    public void setFilename(String filename) {
        mFilename = filename;
    }

    /**
     * Given a HashMap of Exif tags and associated values, an Exif section in
     * the JPG file is created and loaded with the tag data. saveAttributes()
     * is expensive because it involves copying all the JPG data from one file
     * to another and deleting the old file and renaming the other. It's best
     * to collect all the attributes to write and make a single call rather
     * than multiple calls for each attribute. You must call "commitChanges()"
     * at some point to commit the changes.
     */
    public void saveAttributes(HashMap<String, String> attributes) {
        // format of string passed to native C code:
        // "attrCnt attr1=valueLen value1attr2=value2Len value2..."
        // example:
        // "4 attrPtr ImageLength=4 1024Model=6 FooImageWidth=4 1280Make=3 FOO"
        StringBuilder sb = new StringBuilder();
        int size = attributes.size();
        if (attributes.containsKey("hasThumbnail")) {
            --size;
        }
        sb.append(size + " ");
        for (Map.Entry<String, String> iter : attributes.entrySet()) {
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
        saveAttributesNative(mFilename, s);
        commitChangesNative(mFilename);
        mSavedAttributes = true;
    }

    /**
     * Returns a HashMap loaded with the Exif attributes of the file. The key
     * is the standard tag name and the value is the tag's value: e.g.
     * Model -> Nikon. Numeric values are returned as strings.
     */
    public HashMap<String, String> getAttributes() {
        if (mCachedAttributes != null) {
            return mCachedAttributes;
        }
        // format of string passed from native C code:
        // "attrCnt attr1=valueLen value1attr2=value2Len value2..."
        // example:
        // "4 attrPtr ImageLength=4 1024Model=6 FooImageWidth=4 1280Make=3 FOO"
        mCachedAttributes = new HashMap<String, String>();

        String attrStr = getAttributesNative(mFilename);

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
                mCachedAttributes.put(attrName, attrValue);
            }
        }
        return mCachedAttributes;
    }

    /**
     * Given a numerical white balance value, return a
     * human-readable string describing it.
     */
    public static String whiteBalanceToString(int whitebalance) {
        switch (whitebalance) {
            case WHITEBALANCE_AUTO:
                return "Auto";
            case WHITEBALANCE_MANUAL:
                return "Manual";
            default:
                return "";
        }
    }

    /**
     * Given a numerical orientation, return a human-readable string describing
     * the orientation.
     */
    public static String orientationToString(int orientation) {
        // TODO: this function needs to be localized and use string resource ids
        // rather than strings
        String orientationString;
        switch (orientation) {
            case ORIENTATION_NORMAL:
                orientationString = "Normal";
                break;
            case ORIENTATION_FLIP_HORIZONTAL:
                orientationString = "Flipped horizontal";
                break;
            case ORIENTATION_ROTATE_180:
                orientationString = "Rotated 180 degrees";
                break;
            case ORIENTATION_FLIP_VERTICAL:
                orientationString = "Upside down mirror";
                break;
            case ORIENTATION_TRANSPOSE:
                orientationString = "Transposed";
                break;
            case ORIENTATION_ROTATE_90:
                orientationString = "Rotated 90 degrees";
                break;
            case ORIENTATION_TRANSVERSE:
                orientationString = "Transversed";
                break;
            case ORIENTATION_ROTATE_270:
                orientationString = "Rotated 270 degrees";
                break;
            default:
                orientationString = "Undefined";
                break;
        }
        return orientationString;
    }

    /**
     * Copies the thumbnail data out of the filename and puts it in the Exif
     * data associated with the file used to create this object. You must call
     * "commitChanges()" at some point to commit the changes.
     */
    public boolean appendThumbnail(String thumbnailFileName) {
        if (!mSavedAttributes) {
            throw new RuntimeException("Must call saveAttributes "
                    + "before calling appendThumbnail");
        }
        mHasThumbnail = appendThumbnailNative(mFilename, thumbnailFileName);
        return mHasThumbnail;
    }

    public boolean hasThumbnail() {
        if (!mSavedAttributes) {
            getAttributes();
        }
        return mHasThumbnail;
    }

    public byte[] getThumbnail() {
        return getThumbnailNative(mFilename);
    }

    public static float[] getLatLng(HashMap<String, String> exifData) {
        if (exifData == null) {
            return null;
        }

        String latValue = exifData.get(ExifInterface.TAG_GPS_LATITUDE);
        String latRef = exifData.get(ExifInterface.TAG_GPS_LATITUDE_REF);
        String lngValue = exifData.get(ExifInterface.TAG_GPS_LONGITUDE);
        String lngRef = exifData.get(ExifInterface.TAG_GPS_LONGITUDE_REF);
        float[] latlng = null;

        if (latValue != null && latRef != null
                && lngValue != null && lngRef != null) {
            latlng = new float[2];
            latlng[0] = ExifInterface.convertRationalLatLonToFloat(
                    latValue, latRef);
            latlng[1] = ExifInterface.convertRationalLatLonToFloat(
                    lngValue, lngRef);
        }

        return latlng;
    }

    public static float convertRationalLatLonToFloat(
            String rationalString, String ref) {
        try {
            String [] parts = rationalString.split(",");

            String [] pair;
            pair = parts[0].split("/");
            int degrees = (int) (Float.parseFloat(pair[0].trim())
                    / Float.parseFloat(pair[1].trim()));

            pair = parts[1].split("/");
            int minutes = (int) ((Float.parseFloat(pair[0].trim())
                    / Float.parseFloat(pair[1].trim())));

            pair = parts[2].split("/");
            float seconds = Float.parseFloat(pair[0].trim())
                    / Float.parseFloat(pair[1].trim());

            float result = degrees + (minutes / 60F) + (seconds / (60F * 60F));
            if ((ref.equals("S") || ref.equals("W"))) {
                return -result;
            }
            return result;
        } catch (RuntimeException ex) {
            // if for whatever reason we can't parse the lat long then return
            // null
            return 0f;
        }
    }

    public static String convertRationalLatLonToDecimalString(
            String rationalString, String ref, boolean usePositiveNegative) {
            float result = convertRationalLatLonToFloat(rationalString, ref);

            String preliminaryResult = String.valueOf(result);
            if (usePositiveNegative) {
                String neg = (ref.equals("S") || ref.equals("E")) ? "-" : "";
                return neg + preliminaryResult;
            } else {
                return preliminaryResult + String.valueOf((char) 186) + " "
                        + ref;
            }
    }

    public static String makeLatLongString(double d) {
        d = Math.abs(d);

        int degrees = (int) d;

        double remainder = d - degrees;
        int minutes = (int) (remainder * 60D);
        // really seconds * 1000
        int seconds = (int) (((remainder * 60D) - minutes) * 60D * 1000D);

        String retVal = degrees + "/1," + minutes + "/1," + seconds + "/1000";
        return retVal;
    }

    public static String makeLatStringRef(double lat) {
        return lat >= 0D ? "N" : "S";
    }

    public static String makeLonStringRef(double lon) {
        return lon >= 0D ? "W" : "E";
    }

    private native boolean appendThumbnailNative(String fileName,
            String thumbnailFileName);

    private native void saveAttributesNative(String fileName,
            String compressedAttributes);

    private native String getAttributesNative(String fileName);

    private native void commitChangesNative(String fileName);

    private native byte[] getThumbnailNative(String fileName);
}
