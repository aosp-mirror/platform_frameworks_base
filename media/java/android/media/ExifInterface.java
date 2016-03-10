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

import android.annotation.NonNull;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import libcore.io.IoUtils;
import libcore.io.Streams;

/**
 * This is a class for reading and writing Exif tags in a JPEG file or a RAW image file.
 * <p>
 * Supported formats are: JPEG, DNG, CR2, NEF, NRW, ARW, RW2, ORF and RAF.
 * <p>
 * Attribute mutation is supported for JPEG image files.
 */
public class ExifInterface {
    private static final String TAG = "ExifInterface";
    private static final boolean DEBUG = false;

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
    /** Type is String. */
    public static final String TAG_DATETIME_DIGITIZED = "DateTimeDigitized";
    /** Type is int. */
    public static final String TAG_SUBSEC_TIME = "SubSecTime";
    /** Type is int. */
    public static final String TAG_SUBSEC_TIME_ORIG = "SubSecTimeOriginal";
    /** Type is int. */
    public static final String TAG_SUBSEC_TIME_DIG = "SubSecTimeDigitized";

    /**
     * @hide
     */
    public static final String TAG_SUBSECTIME = "SubSecTime";

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
    /** Type is double. */
    public static final String TAG_DIGITAL_ZOOM_RATIO = "DigitalZoomRatio";
    /** Type is double. */
    public static final String TAG_SUBJECT_DISTANCE = "SubjectDistance";
    /** Type is double. */
    public static final String TAG_EXPOSURE_BIAS_VALUE = "ExposureBiasValue";
    /** Type is int. */
    public static final String TAG_LIGHT_SOURCE = "LightSource";
    /** Type is int. */
    public static final String TAG_METERING_MODE = "MeteringMode";
    /** Type is int. */
    public static final String TAG_EXPOSURE_PROGRAM = "ExposureProgram";
    /** Type is int. */
    public static final String TAG_EXPOSURE_MODE = "ExposureMode";

    // Private tags used for thumbnail information.
    private static final String TAG_HAS_THUMBNAIL = "hasThumbnail";
    private static final String TAG_THUMBNAIL_OFFSET = "thumbnailOffset";
    private static final String TAG_THUMBNAIL_LENGTH = "thumbnailLength";
    private static final String TAG_THUMBNAIL_DATA = "thumbnailData";

    // Constants used for the Orientation Exif tag.
    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_NORMAL = 1;
    public static final int ORIENTATION_FLIP_HORIZONTAL = 2;  // left right reversed mirror
    public static final int ORIENTATION_ROTATE_180 = 3;
    public static final int ORIENTATION_FLIP_VERTICAL = 4;  // upside down mirror
    // flipped about top-left <--> bottom-right axis
    public static final int ORIENTATION_TRANSPOSE = 5;
    public static final int ORIENTATION_ROTATE_90 = 6;  // rotate 90 cw to right it
    // flipped about top-right <--> bottom-left axis
    public static final int ORIENTATION_TRANSVERSE = 7;
    public static final int ORIENTATION_ROTATE_270 = 8;  // rotate 270 to right it

    // Constants used for white balance
    public static final int WHITEBALANCE_AUTO = 0;
    public static final int WHITEBALANCE_MANUAL = 1;

    private static final byte[] JPEG_SIGNATURE = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final int JPEG_SIGNATURE_SIZE = 3;

    private static SimpleDateFormat sFormatter;

    // See Exchangeable image file format for digital still cameras: Exif version 2.2.
    // The following values are for parsing EXIF data area. There are tag groups in EXIF data area.
    // They are called "Image File Directory". They have multiple data formats to cover various
    // image metadata from GPS longitude to camera model name.

    // Types of Exif byte alignments (see JEITA CP-3451 page 10)
    private static final short BYTE_ALIGN_II = 0x4949;  // II: Intel order
    private static final short BYTE_ALIGN_MM = 0x4d4d;  // MM: Motorola order

    // Formats for the value in IFD entry (See TIFF 6.0 spec Types page 15).
    private static final int IFD_FORMAT_BYTE = 1;
    private static final int IFD_FORMAT_STRING = 2;
    private static final int IFD_FORMAT_USHORT = 3;
    private static final int IFD_FORMAT_ULONG = 4;
    private static final int IFD_FORMAT_URATIONAL = 5;
    private static final int IFD_FORMAT_SBYTE = 6;
    private static final int IFD_FORMAT_UNDEFINED = 7;
    private static final int IFD_FORMAT_SSHORT = 8;
    private static final int IFD_FORMAT_SLONG = 9;
    private static final int IFD_FORMAT_SRATIONAL = 10;
    private static final int IFD_FORMAT_SINGLE = 11;
    private static final int IFD_FORMAT_DOUBLE = 12;
    // Sizes of the components of each IFD value format
    private static final int[] IFD_FORMAT_BYTES_PER_FORMAT = new int[] {
            0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8
    };
    private static final byte[] EXIF_ASCII_PREFIX = new byte[] {
            0x41, 0x53, 0x43, 0x49, 0x49, 0x0, 0x0, 0x0
    };

    // A class for indicating EXIF tag.
    private static class ExifTag {
        public final int number;
        public final String name;

        private ExifTag(String name, int number) {
            this.name = name;
            this.number = number;
        }
    }

    // Primary image IFD TIFF tags (See JEITA CP-3451 Table 14. page 54).
    private static final ExifTag[] IFD_TIFF_TAGS = new ExifTag[] {
            new ExifTag("ImageWidth", 256),
            new ExifTag("ImageLength", 257),
            new ExifTag("BitsPerSample", 258),
            new ExifTag("Compression", 259),
            new ExifTag("PhotometricInterpretation", 262),
            new ExifTag("ImageDescription", 270),
            new ExifTag("Make", 271),
            new ExifTag("Model", 272),
            new ExifTag("StripOffsets", 273),
            new ExifTag("Orientation", 274),
            new ExifTag("SamplesPerPixel", 277),
            new ExifTag("RowsPerStrip", 278),
            new ExifTag("StripByteCounts", 279),
            new ExifTag("XResolution", 282),
            new ExifTag("YResolution", 283),
            new ExifTag("PlanarConfiguration", 284),
            new ExifTag("ResolutionUnit", 296),
            new ExifTag("TransferFunction", 301),
            new ExifTag("Software", 305),
            new ExifTag("DateTime", 306),
            new ExifTag("Artist", 315),
            new ExifTag("WhitePoint", 318),
            new ExifTag("PrimaryChromaticities", 319),
            new ExifTag("JPEGInterchangeFormat", 513),
            new ExifTag("JPEGInterchangeFormatLength", 514),
            new ExifTag("YCbCrCoefficients", 529),
            new ExifTag("YCbCrSubSampling", 530),
            new ExifTag("YCbCrPositioning", 531),
            new ExifTag("ReferenceBlackWhite", 532),
            new ExifTag("Copyright", 33432),
            new ExifTag("ExifIFDPointer", 34665),
            new ExifTag("GPSInfoIFDPointer", 34853),
    };
    // Primary image IFD Exif Private tags (See JEITA CP-3451 Table 15. page 55).
    private static final ExifTag[] IFD_EXIF_TAGS = new ExifTag[] {
            new ExifTag("ExposureTime", 33434),
            new ExifTag("FNumber", 33437),
            new ExifTag("ExposureProgram", 34850),
            new ExifTag("SpectralSensitivity", 34852),
            new ExifTag("ISOSpeedRatings", 34855),
            new ExifTag("OECF", 34856),
            new ExifTag("ExifVersion", 36864),
            new ExifTag("DateTimeOriginal", 36867),
            new ExifTag("DateTimeDigitized", 36868),
            new ExifTag("ComponentsConfiguration", 37121),
            new ExifTag("CompressedBitsPerPixel", 37122),
            new ExifTag("ShutterSpeedValue", 37377),
            new ExifTag("ApertureValue", 37378),
            new ExifTag("BrightnessValue", 37379),
            new ExifTag("ExposureBiasValue", 37380),
            new ExifTag("MaxApertureValue", 37381),
            new ExifTag("SubjectDistance", 37382),
            new ExifTag("MeteringMode", 37383),
            new ExifTag("LightSource", 37384),
            new ExifTag("Flash", 37385),
            new ExifTag("FocalLength", 37386),
            new ExifTag("SubjectArea", 37396),
            new ExifTag("MakerNote", 37500),
            new ExifTag("UserComment", 37510),
            new ExifTag("SubSecTime", 37520),
            new ExifTag("SubSecTimeOriginal", 37521),
            new ExifTag("SubSecTimeDigitized", 37522),
            new ExifTag("FlashpixVersion", 40960),
            new ExifTag("ColorSpace", 40961),
            new ExifTag("PixelXDimension", 40962),
            new ExifTag("PixelYDimension", 40963),
            new ExifTag("RelatedSoundFile", 40964),
            new ExifTag("InteroperabilityIFDPointer", 40965),
            new ExifTag("FlashEnergy", 41483),
            new ExifTag("SpatialFrequencyResponse", 41484),
            new ExifTag("FocalPlaneXResolution", 41486),
            new ExifTag("FocalPlaneYResolution", 41487),
            new ExifTag("FocalPlaneResolutionUnit", 41488),
            new ExifTag("SubjectLocation", 41492),
            new ExifTag("ExposureIndex", 41493),
            new ExifTag("SensingMethod", 41495),
            new ExifTag("FileSource", 41728),
            new ExifTag("SceneType", 41729),
            new ExifTag("CFAPattern", 41730),
            new ExifTag("CustomRendered", 41985),
            new ExifTag("ExposureMode", 41986),
            new ExifTag("WhiteBalance", 41987),
            new ExifTag("DigitalZoomRatio", 41988),
            new ExifTag("FocalLengthIn35mmFilm", 41989),
            new ExifTag("SceneCaptureType", 41990),
            new ExifTag("GainControl", 41991),
            new ExifTag("Contrast", 41992),
            new ExifTag("Saturation", 41993),
            new ExifTag("Sharpness", 41994),
            new ExifTag("DeviceSettingDescription", 41995),
            new ExifTag("SubjectDistanceRange", 41996),
            new ExifTag("ImageUniqueID", 42016),
    };
    // Primary image IFD GPS Info tags (See JEITA CP-3451 Table 16. page 56).
    private static final ExifTag[] IFD_GPS_TAGS = new ExifTag[] {
            new ExifTag("GPSVersionID", 0),
            new ExifTag("GPSLatitudeRef", 1),
            new ExifTag("GPSLatitude", 2),
            new ExifTag("GPSLongitudeRef", 3),
            new ExifTag("GPSLongitude", 4),
            new ExifTag("GPSAltitudeRef", 5),
            new ExifTag("GPSAltitude", 6),
            new ExifTag("GPSTimeStamp", 7),
            new ExifTag("GPSSatellites", 8),
            new ExifTag("GPSStatus", 9),
            new ExifTag("GPSMeasureMode", 10),
            new ExifTag("GPSDOP", 11),
            new ExifTag("GPSSpeedRef", 12),
            new ExifTag("GPSSpeed", 13),
            new ExifTag("GPSTrackRef", 14),
            new ExifTag("GPSTrack", 15),
            new ExifTag("GPSImgDirectionRef", 16),
            new ExifTag("GPSImgDirection", 17),
            new ExifTag("GPSMapDatum", 18),
            new ExifTag("GPSDestLatitudeRef", 19),
            new ExifTag("GPSDestLatitude", 20),
            new ExifTag("GPSDestLongitudeRef", 21),
            new ExifTag("GPSDestLongitude", 22),
            new ExifTag("GPSDestBearingRef", 23),
            new ExifTag("GPSDestBearing", 24),
            new ExifTag("GPSDestDistanceRef", 25),
            new ExifTag("GPSDestDistance", 26),
            new ExifTag("GPSProcessingMethod", 27),
            new ExifTag("GPSAreaInformation", 28),
            new ExifTag("GPSDateStamp", 29),
            new ExifTag("GPSDifferential", 30),
    };
    // Primary image IFD Interoperability tag (See JEITA CP-3451 Table 17. page 56).
    private static final ExifTag[] IFD_INTEROPERABILITY_TAGS = new ExifTag[] {
            new ExifTag("InteroperabilityIndex", 1),
    };
    // IFD Thumbnail tags (See JEITA CP-3451 Table 18. page 57).
    private static final ExifTag[] IFD_THUMBNAIL_TAGS = new ExifTag[] {
            new ExifTag("ThumbnailImageWidth", 256),
            new ExifTag("ThumbnailImageLength", 257),
            new ExifTag("BitsPerSample", 258),
            new ExifTag("Compression", 259),
            new ExifTag("PhotometricInterpretation", 262),
            new ExifTag("ImageDescription", 270),
            new ExifTag("Make", 271),
            new ExifTag("Model", 272),
            new ExifTag("StripOffsets", 273),
            new ExifTag("Orientation", 274),
            new ExifTag("SamplesPerPixel", 277),
            new ExifTag("RowsPerStrip", 278),
            new ExifTag("StripByteCounts", 279),
            new ExifTag("XResolution", 282),
            new ExifTag("YResolution", 283),
            new ExifTag("PlanarConfiguration", 284),
            new ExifTag("ResolutionUnit", 296),
            new ExifTag("TransferFunction", 301),
            new ExifTag("Software", 305),
            new ExifTag("DateTime", 306),
            new ExifTag("Artist", 315),
            new ExifTag("WhitePoint", 318),
            new ExifTag("PrimaryChromaticities", 319),
            new ExifTag("JPEGInterchangeFormat", 513),
            new ExifTag("JPEGInterchangeFormatLength", 514),
            new ExifTag("YCbCrCoefficients", 529),
            new ExifTag("YCbCrSubSampling", 530),
            new ExifTag("YCbCrPositioning", 531),
            new ExifTag("ReferenceBlackWhite", 532),
            new ExifTag("Copyright", 33432),
            new ExifTag("ExifIFDPointer", 34665),
            new ExifTag("GPSInfoIFDPointer", 34853),
    };

    // See JEITA CP-3451 Figure 5. page 9.
    // The following values are used for indicating pointers to the other Image File Directorys.

    // Indices of Exif Ifd tag groups
    private static final int IFD_TIFF_HINT = 0;
    private static final int IFD_EXIF_HINT = 1;
    private static final int IFD_GPS_HINT = 2;
    private static final int IFD_INTEROPERABILITY_HINT = 3;
    private static final int IFD_THUMBNAIL_HINT = 4;
    // List of Exif tag groups
    private static final ExifTag[][] EXIF_TAGS = new ExifTag[][] {
            IFD_TIFF_TAGS, IFD_EXIF_TAGS, IFD_GPS_TAGS, IFD_INTEROPERABILITY_TAGS,
            IFD_THUMBNAIL_TAGS
    };
    // List of tags for pointing to the other image file directory offset.
    private static final ExifTag[] IFD_POINTER_TAGS = new ExifTag[] {
            new ExifTag("ExifIFDPointer", 34665),
            new ExifTag("GPSInfoPointer", 34853),
            new ExifTag("InteroperabilityIFDPointer", 40965),
    };
    // List of indices of the indicated tag groups according to the IFD_POINTER_TAGS
    private static final int[] IFD_POINTER_TAG_HINTS = new int[] {
            IFD_EXIF_HINT, IFD_GPS_HINT, IFD_INTEROPERABILITY_HINT
    };
    // Tags for indicating the thumbnail offset and length
    private static final ExifTag JPEG_INTERCHANGE_FORMAT_TAG =
            new ExifTag("JPEGInterchangeFormat", 513);
    private static final ExifTag JPEG_INTERCHANGE_FORMAT_LENGTH_TAG =
            new ExifTag("JPEGInterchangeFormatLength", 514);

    // Mappings from tag number to tag name and each item represents one IFD tag group.
    private static final HashMap[] sExifTagMapsForReading = new HashMap[EXIF_TAGS.length];
    // Mapping from tag name to tag number and the corresponding tag group.
    private static final HashMap<String, Pair<Integer, Integer>> sExifTagMapForWriting =
            new HashMap<>();

    // See JPEG File Interchange Format Version 1.02.
    // The following values are defined for handling JPEG streams. In this implementation, we are
    // not only getting information from EXIF but also from some JPEG special segments such as
    // MARKER_COM for user comment and MARKER_SOFx for image width and height.

    // Identifier for APP1 segment in JPEG
    private static final byte[] IDENTIFIER_APP1 = "Exif\0\0".getBytes(Charset.forName("US-ASCII"));
    // JPEG segment markers, that each marker consumes two bytes beginning with 0xff and ending with
    // the indicator. There is no SOF4, SOF8, SOF16 markers in JPEG and SOFx markers indicates start
    // of frame(baseline DCT) and the image size info exists in its beginning part.
    private static final byte MARKER = (byte) 0xff;
    private static final byte MARKER_SOI = (byte) 0xd8;
    private static final byte MARKER_SOF0 = (byte) 0xc0;
    private static final byte MARKER_SOF1 = (byte) 0xc1;
    private static final byte MARKER_SOF2 = (byte) 0xc2;
    private static final byte MARKER_SOF3 = (byte) 0xc3;
    private static final byte MARKER_SOF5 = (byte) 0xc5;
    private static final byte MARKER_SOF6 = (byte) 0xc6;
    private static final byte MARKER_SOF7 = (byte) 0xc7;
    private static final byte MARKER_SOF9 = (byte) 0xc9;
    private static final byte MARKER_SOF10 = (byte) 0xca;
    private static final byte MARKER_SOF11 = (byte) 0xcb;
    private static final byte MARKER_SOF13 = (byte) 0xcd;
    private static final byte MARKER_SOF14 = (byte) 0xce;
    private static final byte MARKER_SOF15 = (byte) 0xcf;
    private static final byte MARKER_SOS = (byte) 0xda;
    private static final byte MARKER_APP1 = (byte) 0xe1;
    private static final byte MARKER_COM = (byte) 0xfe;
    private static final byte MARKER_EOI = (byte) 0xd9;

    static {
        System.loadLibrary("media_jni");
        nativeInitRaw();
        sFormatter = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        sFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));

        // Build up the hash tables to look up Exif tags for reading Exif tags.
        for (int hint = 0; hint < EXIF_TAGS.length; ++hint) {
            sExifTagMapsForReading[hint] = new HashMap();
            for (ExifTag tag : EXIF_TAGS[hint]) {
                sExifTagMapsForReading[hint].put(tag.number, tag.name);
            }
        }

        // Build up the hash tables to look up Exif tags for writing Exif tags.
        // There are some tags that have the same tag name in the different group. For that tags,
        // Primary image TIFF IFD and Exif private IFD have a higher priority to map than the other
        // tag groups. For the same tags, it writes one tag in the only one IFD group, which has the
        // higher priority group.
        for (int hint = EXIF_TAGS.length - 1; hint >= 0; --hint) {
            for (ExifTag tag : EXIF_TAGS[hint]) {
                sExifTagMapForWriting.put(tag.name, new Pair<>(tag.number, hint));
            }
        }
    }

    private final String mFilename;
    private final FileDescriptor mSeekableFileDescriptor;
    private final AssetManager.AssetInputStream mAssetInputStream;
    private final HashMap<String, String> mAttributes = new HashMap<>();
    private boolean mIsRaw;
    private boolean mHasThumbnail;
    // The following values used for indicating a thumbnail position.
    private int mThumbnailOffset;
    private int mThumbnailLength;
    private byte[] mThumbnailBytes;

    // Pattern to check non zero timestamp
    private static final Pattern sNonZeroTimePattern = Pattern.compile(".*[1-9].*");

    /**
     * Reads Exif tags from the specified image file.
     */
    public ExifInterface(String filename) throws IOException {
        if (filename == null) {
            throw new IllegalArgumentException("filename cannot be null");
        }
        FileInputStream in = new FileInputStream(filename);
        mAssetInputStream = null;
        mFilename = filename;
        if (isSeekableFD(in.getFD())) {
            mSeekableFileDescriptor = in.getFD();
        } else {
            mSeekableFileDescriptor = null;
        }
        loadAttributes(in);
    }

    /**
     * Reads Exif tags from the specified image file descriptor. Attribute mutation is supported
     * for seekable file descriptors only.
     */
    public ExifInterface(FileDescriptor fileDescriptor) throws IOException {
        if (fileDescriptor == null) {
            throw new IllegalArgumentException("parcelFileDescriptor cannot be null");
        }
        mAssetInputStream = null;
        mFilename = null;
        if (isSeekableFD(fileDescriptor)) {
            mSeekableFileDescriptor = fileDescriptor;
        } else {
            mSeekableFileDescriptor = null;
        }
        loadAttributes(new FileInputStream(fileDescriptor));
    }

    /**
     * Reads Exif tags from the specified image input stream. Attribute mutation is not supported
     * for input streams.
     */
    public ExifInterface(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream cannot be null");
        }
        mFilename = null;
        if (inputStream instanceof AssetManager.AssetInputStream) {
            mAssetInputStream = (AssetManager.AssetInputStream) inputStream;
            mSeekableFileDescriptor = null;
        } else if (inputStream instanceof FileInputStream
                && isSeekableFD(((FileInputStream) inputStream).getFD())) {
            mAssetInputStream = null;
            mSeekableFileDescriptor = ((FileInputStream) inputStream).getFD();
        } else {
            mAssetInputStream = null;
            mSeekableFileDescriptor = null;
        }
        loadAttributes(inputStream);
    }

    /**
     * Returns the value of the specified tag or {@code null} if there
     * is no such tag in the image file.
     *
     * @param tag the name of the tag.
     */
    public String getAttribute(String tag) {
        return mAttributes.get(tag);
    }

    /**
     * Returns the integer value of the specified tag. If there is no such tag
     * in the image file or the value cannot be parsed as integer, return
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
     * Returns the double value of the tag that is specified as rational or contains a
     * double-formatted value. If there is no such tag in the image file or the value cannot be
     * parsed as double, return <var>defaultValue</var>.
     *
     * @param tag the name of the tag.
     * @param defaultValue the value to return if the tag is not available.
     */
    public double getAttributeDouble(String tag, double defaultValue) {
        String value = mAttributes.get(tag);
        if (value == null) return defaultValue;
        try {
            int index = value.indexOf("/");
            if (index == -1) return Double.parseDouble(value);
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
        if (value == null) {
            mAttributes.remove(tag);
            return;
        }
        mAttributes.put(tag, value);
    }

    /**
     * This function decides which parser to read the image data according to the given input stream
     * type and the content of the input stream. In each case, it reads the first three bytes to
     * determine whether the image data format is JPEG or not.
     */
    private void loadAttributes(@NonNull InputStream in) throws IOException {
        // Process RAW input stream
        if (mAssetInputStream != null) {
            long asset = mAssetInputStream.getNativeAsset();
            if (handleRawResult(nativeGetRawAttributesFromAsset(asset))) {
                return;
            }
        } else if (mSeekableFileDescriptor != null) {
            if (handleRawResult(nativeGetRawAttributesFromFileDescriptor(
                    mSeekableFileDescriptor))) {
                return;
            }
        } else {
            in = new BufferedInputStream(in, JPEG_SIGNATURE_SIZE);
            if (!isJpegInputStream((BufferedInputStream) in) && handleRawResult(
                    nativeGetRawAttributesFromInputStream(in))) {
                return;
            }
        }

        // Process JPEG input stream
        getJpegAttributes(in);

        if (DEBUG) {
            printAttributes();
        }
    }

    private static boolean isJpegInputStream(BufferedInputStream in) throws IOException {
        in.mark(JPEG_SIGNATURE_SIZE);
        byte[] signatureBytes = new byte[JPEG_SIGNATURE_SIZE];
        if (in.read(signatureBytes) != JPEG_SIGNATURE_SIZE) {
            throw new EOFException();
        }
        boolean isJpeg = Arrays.equals(JPEG_SIGNATURE, signatureBytes);
        in.reset();
        return isJpeg;
    }

    private boolean handleRawResult(HashMap map) {
        if (map == null) {
            return false;
        }

        // Mark for disabling the save feature.
        mIsRaw = true;

        for (Object obj : map.entrySet()) {
            Map.Entry entry = (Map.Entry) obj;
            String attrName = (String) entry.getKey();

            switch (attrName) {
                case TAG_HAS_THUMBNAIL:
                    mHasThumbnail = ((String) entry.getValue()).equalsIgnoreCase("true");
                    break;
                case TAG_THUMBNAIL_OFFSET:
                    mThumbnailOffset = Integer.parseInt((String) entry.getValue());
                    break;
                case TAG_THUMBNAIL_LENGTH:
                    mThumbnailLength = Integer.parseInt((String) entry.getValue());
                    break;
                case TAG_THUMBNAIL_DATA:
                    mThumbnailBytes = (byte[]) entry.getValue();
                    break;
                default:
                    mAttributes.put(attrName, (String) entry.getValue());
                    break;
            }
        }

        if (DEBUG) {
            printAttributes();
        }
        return true;
    }

    private static boolean isSeekableFD(FileDescriptor fd) throws IOException {
        try {
            Os.lseek(fd, 0, OsConstants.SEEK_CUR);
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }

    // Prints out attributes for debugging.
    private void printAttributes() {
        Log.d(TAG, "The size of tags: " + mAttributes.size());
        for (Map.Entry<String, String> entry : mAttributes.entrySet()) {
            Log.d(TAG, "tagName: " + entry.getKey() + ", tagValue: " + entry.getValue());
        }
    }

    /**
     * Save the tag data into the original image file. This is expensive because it involves
     * copying all the data from one file to another and deleting the old file and renaming the
     * other. It's best to use{@link #setAttribute(String,String)} to set all attributes to write
     * and make a single call rather than multiple calls for each attribute.
     */
    public void saveAttributes() throws IOException {
        if (mIsRaw) {
            throw new UnsupportedOperationException(
                    "ExifInterface does not support saving attributes on RAW formats.");
        }
        if (mSeekableFileDescriptor == null && mFilename == null) {
            throw new UnsupportedOperationException(
                    "ExifInterface does not support saving attributes for the current input.");
        }

        // Keep the thumbnail in memory
        mThumbnailBytes = getThumbnail();

        FileInputStream in = null;
        FileOutputStream out = null;
        File tempFile = null;
        try {
            // Move the original file to temporary file.
            if (mFilename != null) {
                tempFile = new File(mFilename + ".tmp");
                File originalFile = new File(mFilename);
                if (!originalFile.renameTo(tempFile)) {
                    throw new IOException("Could'nt rename to " + tempFile.getAbsolutePath());
                }
            } else if (mSeekableFileDescriptor != null) {
                tempFile = File.createTempFile("temp", "jpg");
                Os.lseek(mSeekableFileDescriptor, 0, OsConstants.SEEK_SET);
                in = new FileInputStream(mSeekableFileDescriptor);
                out = new FileOutputStream(tempFile);
                Streams.copy(in, out);
            }
        } catch (ErrnoException e) {
            e.rethrowAsIOException();
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
        }

        in = null;
        out = null;
        try {
            // Save the new file.
            in = new FileInputStream(tempFile);
            if (mFilename != null) {
                out = new FileOutputStream(mFilename);
            } else if (mSeekableFileDescriptor != null) {
                Os.lseek(mSeekableFileDescriptor, 0, OsConstants.SEEK_SET);
                out = new FileOutputStream(mSeekableFileDescriptor);
            }
            saveJpegAttributes(in, out);
        } catch (ErrnoException e) {
            e.rethrowAsIOException();
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
            tempFile.delete();
        }

        // Discard the thumbnail in memory
        mThumbnailBytes = null;
    }

    /**
     * Returns true if the image file has a thumbnail.
     */
    public boolean hasThumbnail() {
        return mHasThumbnail;
    }

    /**
     * Returns the thumbnail inside the image file, or {@code null} if there is no thumbnail.
     * The returned data is in JPEG format and can be decoded using
     * {@link android.graphics.BitmapFactory#decodeByteArray(byte[],int,int)}
     */
    public byte[] getThumbnail() {
        if (!mHasThumbnail) {
            return null;
        }
        if (mThumbnailBytes != null) {
            return mThumbnailBytes;
        }

        // Read the thumbnail.
        FileInputStream in = null;
        try {
            if (mAssetInputStream != null) {
                return nativeGetThumbnailFromAsset(
                        mAssetInputStream.getNativeAsset(), mThumbnailOffset, mThumbnailLength);
            } else if (mFilename != null) {
                in = new FileInputStream(mFilename);
            } else if (mSeekableFileDescriptor != null) {
                Os.lseek(mSeekableFileDescriptor, 0, OsConstants.SEEK_SET);
                in = new FileInputStream(mSeekableFileDescriptor);
            }
            if (in == null) {
                // Should not be reached this.
                throw new FileNotFoundException();
            }
            if (in.skip(mThumbnailOffset) != mThumbnailOffset) {
                throw new IOException("Corrupted image");
            }
            byte[] buffer = new byte[mThumbnailLength];
            if (in.read(buffer) != mThumbnailLength) {
                throw new IOException("Corrupted image");
            }
            return buffer;
        } catch (IOException | ErrnoException e) {
            // Couldn't get a thumbnail image.
        } finally {
            IoUtils.closeQuietly(in);
        }
        return null;
    }

    /**
     * Returns the offset and length of thumbnail inside the image file, or
     * {@code null} if there is no thumbnail.
     *
     * @return two-element array, the offset in the first value, and length in
     *         the second, or {@code null} if no thumbnail was found.
     * @hide
     */
    public long[] getThumbnailRange() {
        long[] range = new long[2];
        range[0] = mThumbnailOffset;
        range[1] = mThumbnailLength;
        return range;
    }

    /**
     * Stores the latitude and longitude value in a float array. The first element is
     * the latitude, and the second element is the longitude. Returns false if the
     * Exif tags are not available.
     */
    public boolean getLatLong(float output[]) {
        String latValue = mAttributes.get(TAG_GPS_LATITUDE);
        String latRef = mAttributes.get(TAG_GPS_LATITUDE_REF);
        String lngValue = mAttributes.get(TAG_GPS_LONGITUDE);
        String lngRef = mAttributes.get(TAG_GPS_LONGITUDE_REF);

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
            return (altitude * ((ref == 1) ? -1 : 1));
        } else {
            return defaultValue;
        }
    }

    /**
     * Returns number of milliseconds since Jan. 1, 1970, midnight local time.
     * Returns -1 if the date time information if not available.
     * @hide
     */
    public long getDateTime() {
        String dateTimeString = mAttributes.get(TAG_DATETIME);
        if (dateTimeString == null
                || !sNonZeroTimePattern.matcher(dateTimeString).matches()) return -1;

        ParsePosition pos = new ParsePosition(0);
        try {
            // The exif field is in local time. Parsing it as if it is UTC will yield time
            // since 1/1/1970 local time
            Date datetime = sFormatter.parse(dateTimeString, pos);
            if (datetime == null) return -1;
            long msecs = datetime.getTime();

            String subSecs = mAttributes.get(TAG_SUBSECTIME);
            if (subSecs != null) {
                try {
                    long sub = Long.valueOf(subSecs);
                    while (sub > 1000) {
                        sub /= 10;
                    }
                    msecs += sub;
                } catch (NumberFormatException e) {
                    // Ignored
                }
            }
            return msecs;
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
        if (date == null || time == null
                || (!sNonZeroTimePattern.matcher(date).matches()
                && !sNonZeroTimePattern.matcher(time).matches())) return -1;

        String dateTimeString = date + ' ' + time;

        ParsePosition pos = new ParsePosition(0);
        try {
            Date datetime = sFormatter.parse(dateTimeString, pos);
            if (datetime == null) return -1;
            return datetime.getTime();
        } catch (IllegalArgumentException ex) {
            return -1;
        }
    }

    private static float convertRationalLatLonToFloat(String rationalString, String ref) {
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
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            // Not valid
            throw new IllegalArgumentException();
        }
    }

    // Loads EXIF attributes from a JPEG input stream.
    private void getJpegAttributes(InputStream inputStream) throws IOException {
        // See JPEG File Interchange Format Specification page 5.
        if (DEBUG) {
            Log.d(TAG, "getJpegAttributes starting with: " + inputStream);
        }
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        byte marker;
        int bytesRead = 0;
        ++bytesRead;
        if ((marker = dataInputStream.readByte()) != MARKER) {
            throw new IOException("Invalid marker: " + Integer.toHexString(marker & 0xff));
        }
        ++bytesRead;
        if (dataInputStream.readByte() != MARKER_SOI) {
            throw new IOException("Invalid marker: " + Integer.toHexString(marker & 0xff));
        }
        while (true) {
            ++bytesRead;
            marker = dataInputStream.readByte();
            if (marker != MARKER) {
                throw new IOException("Invalid marker:" + Integer.toHexString(marker & 0xff));
            }
            ++bytesRead;
            marker = dataInputStream.readByte();
            if (DEBUG) {
                Log.d(TAG, "Found JPEG segment indicator: " + Integer.toHexString(marker & 0xff));
            }

            // EOI indicates the end of an image and in case of SOS, JPEG image stream starts and
            // the image data will terminate right after.
            if (marker == MARKER_EOI || marker == MARKER_SOS) {
                break;
            }
            bytesRead += 2;
            int length = dataInputStream.readUnsignedShort() - 2;
            if (length < 0)
                throw new IOException("Invalid length");
            bytesRead += length;
            switch (marker) {
                case MARKER_APP1: {
                    if (DEBUG) {
                        Log.d(TAG, "MARKER_APP1");
                    }
                    bytesRead -= length;
                    if (length < 6) {
                        throw new IOException("Invalid exif");
                    }
                    byte[] identifier = new byte[6];
                    if (inputStream.read(identifier) != 6) {
                        throw new IOException("Invalid exif");
                    }
                    if (!Arrays.equals(identifier, IDENTIFIER_APP1)) {
                        throw new IOException("Invalid app1 identifier");
                    }
                    bytesRead += 6;
                    length -= 6;
                    if (length <= 0) {
                        throw new IOException("Invalid exif");
                    }
                    byte[] bytes = new byte[length];
                    if (dataInputStream.read(bytes) != length) {
                        throw new IOException("Invalid exif");
                    }
                    readExifSegment(bytes, bytesRead);
                    bytesRead += length;
                    length = 0;
                    break;
                }

                case MARKER_COM: {
                    byte[] bytes = new byte[length];
                    if (dataInputStream.read(bytes) != length) {
                        throw new IOException("Invalid exif");
                    }
                    mAttributes.put("UserComment",
                            new String(bytes, Charset.forName("US-ASCII")));
                    break;
                }

                case MARKER_SOF0:
                case MARKER_SOF1:
                case MARKER_SOF2:
                case MARKER_SOF3:
                case MARKER_SOF5:
                case MARKER_SOF6:
                case MARKER_SOF7:
                case MARKER_SOF9:
                case MARKER_SOF10:
                case MARKER_SOF11:
                case MARKER_SOF13:
                case MARKER_SOF14:
                case MARKER_SOF15: {
                    dataInputStream.skipBytes(1);
                    mAttributes.put("ImageLength",
                            String.valueOf(dataInputStream.readUnsignedShort()));
                    mAttributes.put("ImageWidth",
                            String.valueOf(dataInputStream.readUnsignedShort()));
                    length -= 5;
                    break;
                }

                default: {
                    break;
                }
            }
            if (length < 0) {
                throw new IOException("Invalid length");
            }
            dataInputStream.skipBytes(length);
        }
    }

    // Stores a new JPEG image with EXIF attributes into a given output stream.
    private void saveJpegAttributes(InputStream inputStream, OutputStream outputStream)
            throws IOException {
        // See JPEG File Interchange Format Specification page 5.
        if (DEBUG) {
            Log.d(TAG, "saveJpegAttributes starting with (inputStream: " + inputStream
                    + ", outputStream: " + outputStream + ")");
        }
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        ExifDataOutputStream dataOutputStream = new ExifDataOutputStream(outputStream);
        int bytesRead = 0;
        ++bytesRead;
        if (dataInputStream.readByte() != MARKER) {
            throw new IOException("Invalid marker");
        }
        dataOutputStream.writeByte(MARKER);
        ++bytesRead;
        if (dataInputStream.readByte() != MARKER_SOI) {
            throw new IOException("Invalid marker");
        }
        dataOutputStream.writeByte(MARKER_SOI);

        byte[] bytes = new byte[4096];

        while (true) {
            ++bytesRead;
            if (dataInputStream.readByte() != MARKER) {
                throw new IOException("Invalid marker");
            }
            dataOutputStream.writeByte(MARKER);
            ++bytesRead;
            byte marker = dataInputStream.readByte();
            dataOutputStream.writeByte(marker);
            switch (marker) {
                case MARKER_APP1: {
                    // Rewrite EXIF segment
                    int length = dataInputStream.readUnsignedShort() - 2;
                    if (length < 0)
                        throw new IOException("Invalid length");
                    bytesRead += 2;
                    int read;
                    while ((read = dataInputStream.read(
                            bytes, 0, Math.min(length, bytes.length))) > 0) {
                        length -= read;
                    }
                    bytesRead += length;
                    writeExifSegment(dataOutputStream, bytesRead);
                    break;
                }
                case MARKER_EOI:
                case MARKER_SOS: {
                    // Copy all the remaining data
                    Streams.copy(dataInputStream, dataOutputStream);
                    return;
                }
                default: {
                    // Copy JPEG segment
                    int length = dataInputStream.readUnsignedShort();
                    dataOutputStream.writeUnsignedShort(length);
                    if (length < 0)
                        throw new IOException("Invalid length");
                    length -= 2;
                    bytesRead += 2;
                    int read;
                    while ((read = dataInputStream.read(
                            bytes, 0, Math.min(length, bytes.length))) > 0) {
                        dataOutputStream.write(bytes, 0, read);
                        length -= read;
                    }
                    bytesRead += length;
                    break;
                }
            }
        }
    }

    // Reads the given EXIF byte area and save its tag data into attributes.
    private void readExifSegment(byte[] exifBytes, int exifOffsetFromBeginning) throws IOException {
        // Parse TIFF Headers. See JEITA CP-3451C Table 1. page 10.
        ByteOrderAwarenessDataInputStream dataInputStream =
                new ByteOrderAwarenessDataInputStream(exifBytes);

        // Read byte align
        short byteOrder = dataInputStream.readShort();
        switch (byteOrder) {
            case BYTE_ALIGN_II:
                if (DEBUG) {
                    Log.d(TAG, "readExifSegment: Byte Align II");
                }
                dataInputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
                break;
            case BYTE_ALIGN_MM:
                if (DEBUG) {
                    Log.d(TAG, "readExifSegment: Byte Align MM");
                }
                dataInputStream.setByteOrder(ByteOrder.BIG_ENDIAN);
                break;
            default:
                throw new IOException("Invalid byte order: " + Integer.toHexString(byteOrder));
        }

        int startCode = dataInputStream.readUnsignedShort();
        if (startCode != 0x2a) {
            throw new IOException("Invalid exif start: " + Integer.toHexString(startCode));
        }

        // Read first ifd offset
        long firstIfdOffset = dataInputStream.readUnsignedInt();
        if (firstIfdOffset < 8 || firstIfdOffset >= exifBytes.length) {
            throw new IOException("Invalid first Ifd offset: " + firstIfdOffset);
        }
        firstIfdOffset -= 8;
        if (firstIfdOffset > 0) {
            if (dataInputStream.skip(firstIfdOffset) != firstIfdOffset)
                throw new IOException("Couldn't jump to first Ifd: " + firstIfdOffset);
        }

        // Read primary image TIFF image file directory.
        readImageFileDirectory(dataInputStream, IFD_TIFF_HINT);

        // Process thumbnail.
        try {
            int jpegInterchangeFormat = Integer.parseInt(
                    mAttributes.get(JPEG_INTERCHANGE_FORMAT_TAG.name));
            int jpegInterchangeFormatLength = Integer.parseInt(
                    mAttributes.get(JPEG_INTERCHANGE_FORMAT_LENGTH_TAG.name));
            // The following code limits the size of thumbnail size not to overflow EXIF data area.
            jpegInterchangeFormatLength = Math.min(jpegInterchangeFormat
                    + jpegInterchangeFormatLength, exifOffsetFromBeginning + exifBytes.length)
                    - jpegInterchangeFormat;
            if (jpegInterchangeFormat > 0 && jpegInterchangeFormatLength > 0) {
                mHasThumbnail = true;
                mThumbnailOffset = exifOffsetFromBeginning + jpegInterchangeFormat;
                mThumbnailLength = jpegInterchangeFormatLength;

                if (mFilename == null && mAssetInputStream == null
                        && mSeekableFileDescriptor == null) {
                    // Save the thumbnail in memory if the input doesn't support reading again.
                    byte[] thumbnailBytes = new byte[jpegInterchangeFormatLength];
                    dataInputStream.seek(jpegInterchangeFormat);
                    dataInputStream.readFully(thumbnailBytes);
                    mThumbnailBytes = thumbnailBytes;

                    if (DEBUG) {
                        Bitmap bitmap = BitmapFactory.decodeByteArray(
                                thumbnailBytes, 0, thumbnailBytes.length);
                        Log.d(TAG, "Thumbnail offset: " + mThumbnailOffset + ", length: "
                                + mThumbnailLength + ", width: " + bitmap.getWidth() + ", height: "
                                + bitmap.getHeight());
                    }
                }
            }
        } catch (NumberFormatException e) {
            // Ignored the corrupted image.
        }

        // For compatibility, keep data formats as follows.
        convertToInt(TAG_IMAGE_WIDTH);
        convertToInt(TAG_IMAGE_LENGTH);
        convertToInt(TAG_ORIENTATION);
        convertToInt(TAG_FLASH);
        convertToRational(TAG_FOCAL_LENGTH);
        convertToDouble(TAG_DIGITAL_ZOOM_RATIO);
        convertToDouble(TAG_EXPOSURE_TIME);
        convertToDouble(TAG_APERTURE);
        convertToDouble(TAG_SUBJECT_DISTANCE);
        convertToInt(TAG_ISO);
        convertToDouble(TAG_EXPOSURE_BIAS_VALUE);
        convertToInt(TAG_WHITE_BALANCE);
        convertToInt(TAG_LIGHT_SOURCE);
        convertToInt(TAG_METERING_MODE);
        convertToInt(TAG_EXPOSURE_PROGRAM);
        convertToInt(TAG_EXPOSURE_MODE);
        convertToRational(TAG_GPS_ALTITUDE);
        convertToInt(TAG_GPS_ALTITUDE_REF);
        convertToRational(TAG_GPS_LONGITUDE);
        convertToRational(TAG_GPS_LATITUDE);
        convertToTimetamp(TAG_GPS_TIMESTAMP);

        // The value of DATETIME tag has the same value of DATETIME_ORIGINAL tag.
        String valueOfDateTimeOriginal = mAttributes.get("DateTimeOriginal");
        if (valueOfDateTimeOriginal != null) {
            mAttributes.put(TAG_DATETIME, valueOfDateTimeOriginal);
        }

        // Add the default value.
        if (!mAttributes.containsKey(TAG_IMAGE_WIDTH)) {
            mAttributes.put(TAG_IMAGE_WIDTH, "0");
        }
        if (!mAttributes.containsKey(TAG_IMAGE_LENGTH)) {
            mAttributes.put(TAG_IMAGE_LENGTH, "0");
        }
        if (!mAttributes.containsKey(TAG_ORIENTATION)) {
            mAttributes.put(TAG_ORIENTATION, "0");
        }
        if (!mAttributes.containsKey(TAG_LIGHT_SOURCE)) {
            mAttributes.put(TAG_LIGHT_SOURCE, "0");
        }
    }

    // Converts the tag value to timestamp; Otherwise deletes the given tag.
    private void convertToTimetamp(String tagName) {
        String entryValue = mAttributes.get(tagName);
        if (entryValue == null) return;
        int dataFormat = getDataFormatOfExifEntryValue(entryValue);
        String[] components = entryValue.split(",");
        if (dataFormat == IFD_FORMAT_SRATIONAL && components.length == 3) {
            StringBuilder stringBuilder = new StringBuilder();
            for (String component : components) {
                if (stringBuilder.length() > 0) {
                    stringBuilder.append(":");
                }
                String[] rationalNumber = component.split("/");
                int numerator = Integer.parseInt(rationalNumber[0]);
                int denominator = Integer.parseInt(rationalNumber[1]);
                if (denominator == 0) {
                    numerator = 0;
                    denominator = 1;
                }
                int value = numerator / denominator;
                stringBuilder.append(String.format("%02d", value));
            }
            mAttributes.put(tagName, stringBuilder.toString());
        } else if (dataFormat != IFD_FORMAT_STRING) {
            mAttributes.remove(tagName);
        }
    }

    // Checks the tag value of a given tag formatted in double type; Otherwise try to convert it to
    // double type or delete it.
    private void convertToDouble(String tagName) {
        String entryValue = mAttributes.get(tagName);
        if (entryValue == null) return;
        int dataFormat = getDataFormatOfExifEntryValue(entryValue);
        switch (dataFormat) {
            case IFD_FORMAT_SRATIONAL: {
                StringBuilder stringBuilder = new StringBuilder();
                String[] components = entryValue.split(",");
                for (String component : components) {
                    if (stringBuilder.length() > 0) {
                        stringBuilder.append(",");
                    }
                    String[] rationalNumber = component.split("/");
                    int numerator = Integer.parseInt(rationalNumber[0]);
                    int denominator = Integer.parseInt(rationalNumber[1]);
                    if (denominator == 0) {
                        numerator = 0;
                        denominator = 1;
                    }
                    stringBuilder.append((double) numerator / denominator);
                }
                mAttributes.put(tagName, stringBuilder.toString());
                break;
            }
            case IFD_FORMAT_DOUBLE:
                // Keep it as is.
                break;
            default:
                mAttributes.remove(tagName);
                break;
        }
    }

    // Checks the tag value of a given tag formatted in int type; Otherwise deletes the tag value.
    private void convertToRational(String tagName) {
        String entryValue = mAttributes.get(tagName);
        if (entryValue == null) return;
        int dataFormat = getDataFormatOfExifEntryValue(entryValue);
        switch (dataFormat) {
            case IFD_FORMAT_SLONG:
            case IFD_FORMAT_DOUBLE: {
                StringBuilder stringBuilder = new StringBuilder();
                String[] components = entryValue.split(",");
                for (String component : components) {
                    if (stringBuilder.length() > 0) {
                        stringBuilder.append(",");
                    }
                    double doubleValue = Double.parseDouble(component);
                    stringBuilder.append((int) (doubleValue * 10000.0)).append("/").append(10000);
                }
                mAttributes.put(tagName, stringBuilder.toString());
                break;
            }
            case IFD_FORMAT_SRATIONAL:
                // Keep it as is.
                break;
            default:
                mAttributes.remove(tagName);
                break;
        }
    }

    // Checks the tag value of a given tag formatted in int type; Otherwise deletes the tag value.
    private void convertToInt(String tagName) {
        String entryValue = mAttributes.get(tagName);
        if (entryValue == null) return;
        int dataFormat = getDataFormatOfExifEntryValue(entryValue);
        if (dataFormat != IFD_FORMAT_SLONG) {
            mAttributes.remove(tagName);
        }
    }

    // Reads image file directory, which is a tag group in EXIF.
    private void readImageFileDirectory(ByteOrderAwarenessDataInputStream dataInputStream, int hint)
            throws IOException {
        // See JEITA CP-3451 Figure 5. page 9.
        short numberOfDirectoryEntry = dataInputStream.readShort();

        if (DEBUG) {
            Log.d(TAG, "numberOfDirectoryEntry: " + numberOfDirectoryEntry);
        }

        for (short i = 0; i < numberOfDirectoryEntry; ++i) {
            int tagNumber = dataInputStream.readUnsignedShort();
            int dataFormat = dataInputStream.readUnsignedShort();
            int numberOfComponents = dataInputStream.readInt();
            long nextEntryOffset = dataInputStream.peek() + 4;  // next four bytes is for data
                                                                // offset or value.

            if (DEBUG) {
                Log.d(TAG, String.format("tagNumber: %d, dataFormat: %d, numberOfComponents: %d",
                        tagNumber, dataFormat, numberOfComponents));
            }

            // Read a value from data field or seek to the value offset which is stored in data
            // field if the size of the entry value is bigger than 4.
            int byteCount = numberOfComponents * IFD_FORMAT_BYTES_PER_FORMAT[dataFormat];
            if (byteCount > 4) {
                long offset = dataInputStream.readUnsignedInt();
                if (DEBUG) {
                    Log.d(TAG, "seek to data offset: " + offset);
                }
                dataInputStream.seek(offset);
            }

            // Look up a corresponding tag from tag number
            String tagName = (String) sExifTagMapsForReading[hint].get(tagNumber);
            // Skip if the parsed tag number is not defined.
            if (tagName == null) {
                dataInputStream.seek(nextEntryOffset);
                continue;
            }

            // Recursively parse IFD when a IFD pointer tag appears.
            int innerIfdHint = getIfdHintFromTagNumber(tagNumber);
            if (innerIfdHint >= 0) {
                long offset = -1L;
                // Get offset from data field
                switch (dataFormat) {
                    case IFD_FORMAT_USHORT: {
                        offset = dataInputStream.readUnsignedShort();
                        break;
                    }
                    case IFD_FORMAT_SSHORT: {
                        offset = dataInputStream.readShort();
                        break;
                    }
                    case IFD_FORMAT_ULONG: {
                        offset = dataInputStream.readUnsignedInt();
                        break;
                    }
                    case IFD_FORMAT_SLONG: {
                        offset = dataInputStream.readInt();
                        break;
                    }
                    default: {
                        // Nothing to do
                        break;
                    }
                }
                if (DEBUG) {
                    Log.d(TAG, String.format("Offset: %d, tagName: %s", offset, tagName));
                }
                if (offset > 0L) {
                    dataInputStream.seek(offset);
                    readImageFileDirectory(dataInputStream, innerIfdHint);
                }

                dataInputStream.seek(nextEntryOffset);
                continue;
            }

            if (numberOfComponents == 1 || dataFormat == IFD_FORMAT_STRING
                    || dataFormat == IFD_FORMAT_UNDEFINED) {
                String entryValue = readExifEntryValue(
                        dataInputStream, dataFormat, numberOfComponents);
                if (entryValue != null) {
                    mAttributes.put(tagName, entryValue);
                }
            } else {
                StringBuilder entryValueBuilder = new StringBuilder();
                for (int c = 0; c < numberOfComponents; ++c) {
                    if (entryValueBuilder.length() > 0) {
                        entryValueBuilder.append(",");
                    }
                    entryValueBuilder.append(readExifEntryValue(
                            dataInputStream, dataFormat, numberOfComponents));
                }
                mAttributes.put(tagName, entryValueBuilder.toString());
            }

            if (dataInputStream.peek() != nextEntryOffset) {
                dataInputStream.seek(nextEntryOffset);
            }
        }

        long nextIfdOffset = dataInputStream.readUnsignedInt();
        if (DEBUG) {
            Log.d(TAG, String.format("nextIfdOffset: %d", nextIfdOffset));
        }
        // The next IFD offset needs to be bigger than 8 since the first IFD offset is at least 8.
        if (nextIfdOffset > 8) {
            dataInputStream.seek(nextIfdOffset);
            readImageFileDirectory(dataInputStream, IFD_THUMBNAIL_HINT);
        }
    }

    // Reads a value from where the entry value are stored.
    private String readExifEntryValue(ByteOrderAwarenessDataInputStream dataInputStream,
            int dataFormat, int numberOfComponents) throws IOException {
        // See TIFF 6.0 spec Types. page 15.
        switch (dataFormat) {
            case IFD_FORMAT_BYTE: {
                return String.valueOf(dataInputStream.readByte());
            }
            case IFD_FORMAT_SBYTE: {
                return String.valueOf(dataInputStream.readByte() & 0xff);
            }
            case IFD_FORMAT_USHORT: {
                return String.valueOf(dataInputStream.readUnsignedShort());
            }
            case IFD_FORMAT_SSHORT: {
                return String.valueOf(dataInputStream.readUnsignedInt());
            }
            case IFD_FORMAT_ULONG: {
                return String.valueOf(dataInputStream.readInt());
            }
            case IFD_FORMAT_SLONG: {
                return String.valueOf(dataInputStream.readInt());
            }
            case IFD_FORMAT_URATIONAL:
            case IFD_FORMAT_SRATIONAL: {
                int numerator = dataInputStream.readInt();
                int denominator = dataInputStream.readInt();
                return numerator + "/" + denominator;
            }
            case IFD_FORMAT_SINGLE: {
                return String.valueOf(dataInputStream.readFloat());
            }
            case IFD_FORMAT_DOUBLE: {
                return String.valueOf(dataInputStream.readDouble());
            }
            case IFD_FORMAT_UNDEFINED:  // Usually UNDEFINED format is ASCII.
            case IFD_FORMAT_STRING: {
                byte[] bytes = new byte[numberOfComponents];
                dataInputStream.readFully(bytes);
                int index = 0;
                if (numberOfComponents >= EXIF_ASCII_PREFIX.length) {
                    boolean same = true;
                    for (int i = 0; i < EXIF_ASCII_PREFIX.length; ++i) {
                        if (bytes[i] != EXIF_ASCII_PREFIX[i]) {
                            same = false;
                            break;
                        }
                    }
                    if (same) {
                        index = EXIF_ASCII_PREFIX.length;
                    }
                }

                StringBuilder stringBuilder = new StringBuilder();
                while (true) {
                    int ch = bytes[index];
                    if (ch == 0)
                        break;
                    if (ch >= 32)
                        stringBuilder.append((char) ch);
                    else
                        stringBuilder.append('?');
                    ++index;
                    if (index == numberOfComponents)
                        break;
                }
                return stringBuilder.toString();
            }
            default: {
                // Nothing to do
                return null;
            }
        }
    }

    // Gets the corresponding IFD group index of the given tag number for writing Exif Tags.
    private static int getIfdHintFromTagNumber(int tagNumber) {
        for (int i = 0; i < IFD_POINTER_TAG_HINTS.length; ++i) {
            if (IFD_POINTER_TAGS[i].number == tagNumber)
                return IFD_POINTER_TAG_HINTS[i];
        }
        return -1;
    }

    // Writes an Exif segment into the given output stream.
    private int writeExifSegment(ExifDataOutputStream dataOutputStream, int exifOffsetFromBeginning)
            throws IOException {
        // The following variables are for calculating each IFD tag group size in bytes.
        int[] ifdOffsets = new int[EXIF_TAGS.length];
        int[] ifdDataSizes = new int[EXIF_TAGS.length];

        // Maps to store tags per IFD tag group
        HashMap[] ifdTags = new HashMap[EXIF_TAGS.length];
        for (int i = 0; i < EXIF_TAGS.length; ++i) {
            ifdTags[i] = new HashMap();
        }

        // Remove IFD pointer tags (we'll re-add it later.)
        for (ExifTag tag : IFD_POINTER_TAGS) {
            mAttributes.remove(tag.name);
        }

        // Assign tags to the corresponding group
        for (Map.Entry<String, String> entry : mAttributes.entrySet()) {
            Pair<Integer, Integer> pair = sExifTagMapForWriting.get(entry.getKey());
            if (pair != null) {
                int tagNumber = pair.first;
                int hint = pair.second;
                ifdTags[hint].put(tagNumber, entry.getValue());
            }
        }

        // Add IFD pointer tags. The next offset of primary image TIFF IFD will have thumbnail IFD
        // offset when there is one or more tags in the thumbnail IFD.
        if (!ifdTags[IFD_INTEROPERABILITY_HINT].isEmpty()) {
            ifdTags[IFD_EXIF_HINT].put(IFD_POINTER_TAGS[2].number, "0");
        }
        if (!ifdTags[IFD_EXIF_HINT].isEmpty()) {
            ifdTags[IFD_TIFF_HINT].put(IFD_POINTER_TAGS[0].number, "0");
        }
        if (!ifdTags[IFD_GPS_HINT].isEmpty()) {
            ifdTags[IFD_TIFF_HINT].put(IFD_POINTER_TAGS[1].number, "0");
        }
        if (mHasThumbnail) {
            ifdTags[IFD_TIFF_HINT].put(JPEG_INTERCHANGE_FORMAT_TAG.number, "0");
            ifdTags[IFD_TIFF_HINT].put(JPEG_INTERCHANGE_FORMAT_LENGTH_TAG.number,
                    String.valueOf(mThumbnailLength));
        }

        // Calculate IFD group data area sizes. IFD group data area is assigned to save the entry
        // value which has a bigger size than 4 bytes.
        for (int i = 0; i < 5; ++i) {
            int sum = 0;
            for (Object entry : ifdTags[i].entrySet()) {
                String entryValue = (String) ((Map.Entry) entry).getValue();
                int dataFormat = getDataFormatOfExifEntryValue(entryValue);
                int size = getSizeOfExifEntryValue(dataFormat, entryValue);
                if (size > 4) {
                    sum += size;
                }
            }
            ifdDataSizes[i] += sum;
        }

        // Calculate IFD offsets.
        int position = 8;
        for (int hint = 0; hint < EXIF_TAGS.length; ++hint) {
            if (!ifdTags[hint].isEmpty()) {
                ifdOffsets[hint] = position;
                position += 2 + ifdTags[hint].size() * 12 + 4 + ifdDataSizes[hint];
            }
        }
        if (mHasThumbnail) {
            int thumbnailOffset = position;
            ifdTags[IFD_TIFF_HINT].put(JPEG_INTERCHANGE_FORMAT_TAG.number,
                    String.valueOf(thumbnailOffset));
            ifdTags[IFD_TIFF_HINT].put(JPEG_INTERCHANGE_FORMAT_LENGTH_TAG.number,
                    String.valueOf(mThumbnailLength));
            mThumbnailOffset = exifOffsetFromBeginning + thumbnailOffset;
            position += mThumbnailLength;
        }

        // Calculate the total size
        int totalSize = position + 8;  // eight bytes is for header part.
        if (DEBUG) {
            Log.d(TAG, "totalSize length: " + totalSize);
            for (int i = 0; i < 5; ++i) {
                Log.d(TAG, String.format("index: %d, offsets: %d, tag count: %d, data sizes: %d",
                        i, ifdOffsets[i], ifdTags[i].size(), ifdDataSizes[i]));
            }
        }

        // Update IFD pointer tags with the calculated offsets.
        if (!ifdTags[IFD_EXIF_HINT].isEmpty()) {
            ifdTags[IFD_TIFF_HINT].put(IFD_POINTER_TAGS[0].number,
                    String.valueOf(ifdOffsets[IFD_EXIF_HINT]));
        }
        if (!ifdTags[IFD_GPS_HINT].isEmpty()) {
            ifdTags[IFD_TIFF_HINT].put(IFD_POINTER_TAGS[1].number,
                    String.valueOf(ifdOffsets[IFD_GPS_HINT]));
        }
        if (!ifdTags[IFD_INTEROPERABILITY_HINT].isEmpty()) {
            ifdTags[IFD_EXIF_HINT].put(IFD_POINTER_TAGS[2].number,
                    String.valueOf(ifdOffsets[IFD_INTEROPERABILITY_HINT]));
        }

        // Write TIFF Headers. See JEITA CP-3451C Table 1. page 10.
        dataOutputStream.writeUnsignedShort(totalSize);
        dataOutputStream.write(IDENTIFIER_APP1);
        dataOutputStream.writeShort(BYTE_ALIGN_MM);
        dataOutputStream.writeUnsignedShort(0x2a);
        dataOutputStream.writeUnsignedInt(8);

        // Write IFD groups. See JEITA CP-3451C Figure 7. page 12.
        for (int hint = 0; hint < EXIF_TAGS.length; ++hint) {
            if (!ifdTags[hint].isEmpty()) {
                // See JEITA CP-3451C 4.6.2 IFD structure. page 13.
                // Write entry count
                dataOutputStream.writeUnsignedShort(ifdTags[hint].size());

                // Write entry info
                int dataOffset = ifdOffsets[hint] + 2 + ifdTags[hint].size() * 12 + 4;
                for (Object obj : ifdTags[hint].entrySet()) {
                    Map.Entry entry = (Map.Entry) obj;
                    int tagNumber = (int) entry.getKey();
                    String entryValue = (String) entry.getValue();

                    int dataFormat = getDataFormatOfExifEntryValue(entryValue);
                    int numberOfComponents = getNumberOfComponentsInExifEntryValue(dataFormat,
                            entryValue);
                    int byteCount = getSizeOfExifEntryValue(dataFormat, entryValue);

                    dataOutputStream.writeUnsignedShort(tagNumber);
                    dataOutputStream.writeUnsignedShort(dataFormat);
                    dataOutputStream.writeInt(numberOfComponents);
                    if (byteCount > 4) {
                        dataOutputStream.writeUnsignedInt(dataOffset);
                        dataOffset += byteCount;
                    } else {
                        int bytesWritten = writeExifEntryValue(dataOutputStream, entryValue);
                        // Fill zero up to 4 bytes
                        if (bytesWritten < 4) {
                            for (int i = bytesWritten; i < 4; ++i) {
                                dataOutputStream.write(0);
                            }
                        }
                    }
                }

                // Write the next offset. It writes the offset of thumbnail IFD if there is one or
                // more tags in the thumbnail IFD when the current IFD is the primary image TIFF
                // IFD; Otherwise 0.
                if (hint == 0 && !ifdTags[IFD_THUMBNAIL_HINT].isEmpty()) {
                    dataOutputStream.writeUnsignedInt(ifdOffsets[IFD_THUMBNAIL_HINT]);
                } else {
                    dataOutputStream.writeUnsignedInt(0);
                }

                // Write values of data field exceeding 4 bytes after the next offset.
                for (Object obj : ifdTags[hint].entrySet()) {
                    Map.Entry entry = (Map.Entry) obj;
                    String entryValue = (String) entry.getValue();

                    int dataFormat = getDataFormatOfExifEntryValue(entryValue);
                    int byteCount = getSizeOfExifEntryValue(dataFormat, entryValue);
                    if (byteCount > 4) {
                        writeExifEntryValue(dataOutputStream, entryValue);
                    }
                }
            }
        }

        // Write thumbnail
        if (mHasThumbnail) {
            dataOutputStream.write(getThumbnail());
        }

        return totalSize;
    }

    // Writes EXIF entry value and its entry value type will be automatically determined.
    private static int writeExifEntryValue(ExifDataOutputStream dataOutputStream, String entryValue)
            throws IOException {
        int bytesWritten = 0;
        int dataFormat = getDataFormatOfExifEntryValue(entryValue);

        // Values can be composed of several components. Each component is separated by char ','.
        String[] components = entryValue.split(",");
        for (String component : components) {
            switch (dataFormat) {
                case IFD_FORMAT_SLONG:
                    dataOutputStream.writeInt(Integer.parseInt(component));
                    bytesWritten += 4;
                    break;
                case IFD_FORMAT_DOUBLE:
                    dataOutputStream.writeDouble(Double.parseDouble(component));
                    bytesWritten += 8;
                    break;
                case IFD_FORMAT_STRING:
                    byte[] asciiArray = (component + '\0').getBytes(Charset.forName("US-ASCII"));
                    dataOutputStream.write(asciiArray);
                    bytesWritten += asciiArray.length;
                    break;
                case IFD_FORMAT_SRATIONAL:
                    String[] rationalNumber = component.split("/");
                    dataOutputStream.writeInt(Integer.parseInt(rationalNumber[0]));
                    dataOutputStream.writeInt(Integer.parseInt(rationalNumber[1]));
                    bytesWritten += 8;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
        }
        return bytesWritten;
    }

    // Determines the data format of EXIF entry value.
    private static int getDataFormatOfExifEntryValue(String entryValue) {
        // See TIFF 6.0 spec Types. page 15.
        // Take the first component if there are more than one component.
        if (entryValue.contains(",")) {
            entryValue = entryValue.split(",")[0];
        }

        if (entryValue.contains("/")) {
            return IFD_FORMAT_SRATIONAL;
        }
        try {
            Integer.parseInt(entryValue);
            return IFD_FORMAT_SLONG;
        } catch (NumberFormatException e) {
            // Ignored
        }
        try {
            Double.parseDouble(entryValue);
            return IFD_FORMAT_DOUBLE;
        } catch (NumberFormatException e) {
            // Ignored
        }
        return IFD_FORMAT_STRING;
    }

    // Determines the size of EXIF entry value.
    private static int getSizeOfExifEntryValue(int dataFormat, String entryValue) {
        // See TIFF 6.0 spec Types page 15.
        int bytesEstimated = 0;
        String[] components = entryValue.split(",");
        for (String component : components) {
            switch (dataFormat) {
                case IFD_FORMAT_SLONG:
                    bytesEstimated += 4;
                    break;
                case IFD_FORMAT_DOUBLE:
                    bytesEstimated += 8;
                    break;
                case IFD_FORMAT_STRING:
                    bytesEstimated
                            += (component + '\0').getBytes(Charset.forName("US-ASCII")).length;
                    break;
                case IFD_FORMAT_SRATIONAL:
                    bytesEstimated += 8;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
        }
        return bytesEstimated;
    }

    // Determines the number of components of EXIF entry value.
    private static int getNumberOfComponentsInExifEntryValue(int dataFormat, String entryValue) {
        if (dataFormat == IFD_FORMAT_STRING) {
            return (entryValue + '\0').getBytes(Charset.forName("US-ASCII")).length;
        }
        int count = 1;
        for (int i = 0; i < entryValue.length(); ++i) {
            if (entryValue.charAt(i) == ',') {
                ++count;
            }
        }
        return count;
    }

    // An input stream to parse EXIF data area, which can be written in either little or big endian
    // order.
    private static class ByteOrderAwarenessDataInputStream extends ByteArrayInputStream {
        private static final ByteOrder LITTLE_ENDIAN = ByteOrder.LITTLE_ENDIAN;
        private static final ByteOrder BIG_ENDIAN = ByteOrder.BIG_ENDIAN;

        private ByteOrder mByteOrder = ByteOrder.BIG_ENDIAN;
        private final long mLength;
        private long mPosition;

        public ByteOrderAwarenessDataInputStream(byte[] bytes) {
            super(bytes);
            mLength = bytes.length;
            mPosition = 0L;
        }

        public void setByteOrder(ByteOrder byteOrder) {
            mByteOrder = byteOrder;
        }

        public void seek(long byteCount) throws IOException {
            mPosition = 0L;
            reset();
            if (skip(byteCount) != byteCount)
                throw new IOException("Couldn't seek up to the byteCount");
        }

        public long peek() {
            return mPosition;
        }

        public void readFully(byte[] buffer) throws IOException {
            mPosition += buffer.length;
            if (mPosition > mLength)
                throw new EOFException();
            if (super.read(buffer, 0, buffer.length) != buffer.length) {
                throw new IOException("Couldn't read up to the length of buffer");
            }
        }

        public byte readByte() throws IOException {
            ++mPosition;
            if (mPosition > mLength)
                throw new EOFException();
            int ch = super.read();
            if (ch < 0)
                throw new EOFException();
            return (byte) ch;
        }

        public short readShort() throws IOException {
            mPosition += 2;
            if (mPosition > mLength)
                throw new EOFException();
            int ch1 = super.read();
            int ch2 = super.read();
            if ((ch1 | ch2) < 0)
                throw new EOFException();
            if (mByteOrder == LITTLE_ENDIAN) {
                return (short) ((ch2 << 8) + (ch1));
            } else if (mByteOrder == BIG_ENDIAN) {
                return (short) ((ch1 << 8) + (ch2));
            }
            throw new IOException("Invalid byte order: " + mByteOrder);
        }

        public int readInt() throws IOException {
            mPosition += 4;
            if (mPosition > mLength)
                throw new EOFException();
            int ch1 = super.read();
            int ch2 = super.read();
            int ch3 = super.read();
            int ch4 = super.read();
            if ((ch1 | ch2 | ch3 | ch4) < 0)
                throw new EOFException();
            if (mByteOrder == LITTLE_ENDIAN) {
                return ((ch4 << 24) + (ch3 << 16) + (ch2 << 8) + ch1);
            } else if (mByteOrder == BIG_ENDIAN) {
                return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + ch4);
            }
            throw new IOException("Invalid byte order: " + mByteOrder);
        }

        @Override
        public long skip(long byteCount) {
            long skipped = super.skip(Math.min(byteCount, mLength - mPosition));
            mPosition += skipped;
            return skipped;
        }

        public int readUnsignedShort() throws IOException {
            mPosition += 2;
            if (mPosition > mLength)
                throw new EOFException();
            int ch1 = super.read();
            int ch2 = super.read();
            if ((ch1 | ch2) < 0)
                throw new EOFException();
            if (mByteOrder == LITTLE_ENDIAN) {
                return ((ch2 << 8) + (ch1));
            } else if (mByteOrder == BIG_ENDIAN) {
                return ((ch1 << 8) + (ch2));
            }
            throw new IOException("Invalid byte order: " + mByteOrder);
        }

        public long readUnsignedInt() throws IOException {
            return readInt() & 0xffffffffL;
        }

        public long readLong() throws IOException {
            mPosition += 8;
            if (mPosition > mLength)
                throw new EOFException();
            int ch1 = super.read();
            int ch2 = super.read();
            int ch3 = super.read();
            int ch4 = super.read();
            int ch5 = super.read();
            int ch6 = super.read();
            int ch7 = super.read();
            int ch8 = super.read();
            if ((ch1 | ch2 | ch3 | ch4 | ch5 | ch6 | ch7 | ch8) < 0)
                throw new EOFException();
            if (mByteOrder == LITTLE_ENDIAN) {
                return (((long) ch8 << 56) + ((long) ch7 << 48) + ((long) ch6 << 40)
                        + ((long) ch5 << 32) + ((long) ch4 << 24) + ((long) ch3 << 16)
                        + ((long) ch2 << 8) + (long) ch1);
            } else if (mByteOrder == BIG_ENDIAN) {
                return (((long) ch1 << 56) + ((long) ch2 << 48) + ((long) ch3 << 40)
                        + ((long) ch4 << 32) + ((long) ch5 << 24) + ((long) ch6 << 16)
                        + ((long) ch7 << 8) + (long) ch8);
            }
            throw new IOException("Invalid byte order: " + mByteOrder);
        }

        public float readFloat() throws IOException {
            return Float.intBitsToFloat(readInt());
        }

        public double readDouble() throws IOException {
            return Double.longBitsToDouble(readLong());
        }
    }

    // An output stream to write EXIF data area, that will be written in big endian byte order.
    private static class ExifDataOutputStream extends DataOutputStream {
        public ExifDataOutputStream(OutputStream out) {
            super(out);
        }

        public void writeUnsignedShort(int val) throws IOException {
            writeShort((short) val);
        }

        public void writeUnsignedInt(long val) throws IOException {
            writeInt((int) val);
        }
    }

    // JNI methods for RAW formats.
    private static native void nativeInitRaw();
    private static native byte[] nativeGetThumbnailFromAsset(
            long asset, int thumbnailOffset, int thumbnailLength);
    private static native HashMap nativeGetRawAttributesFromAsset(long asset);
    private static native HashMap nativeGetRawAttributesFromFileDescriptor(FileDescriptor fd);
    private static native HashMap nativeGetRawAttributesFromInputStream(InputStream in);
}
