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

import static android.media.ExifInterfaceUtils.byteArrayToHexString;
import static android.media.ExifInterfaceUtils.closeFileDescriptor;
import static android.media.ExifInterfaceUtils.closeQuietly;
import static android.media.ExifInterfaceUtils.convertToLongArray;
import static android.media.ExifInterfaceUtils.copy;
import static android.media.ExifInterfaceUtils.startsWith;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * This is a class for reading and writing Exif tags in various image file formats.
 * <p>
 * Supported for reading: JPEG, PNG, WebP, HEIF, DNG, CR2, NEF, NRW, ARW, RW2, ORF, PEF, SRW, RAF,
 * AVIF.
 * <p>
 * Supported for writing: JPEG, PNG, WebP, DNG.
 * <p>
 * Note: JPEG and HEIF files may contain XMP data either inside the Exif data chunk or outside of
 * it. This class will search both locations for XMP data, but if XMP data exist both inside and
 * outside Exif, will favor the XMP data inside Exif over the one outside.
 * <p>
 * Note: It is recommended to use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/exifinterface/media/ExifInterface.html">ExifInterface
 * Library</a> since it is a superset of this class. In addition to the functionalities of this
 * class, it supports parsing extra metadata such as exposure and data compression information
 * as well as setting extra metadata such as GPS and datetime information.
 */
public class ExifInterface {
    private static final String TAG = "ExifInterface";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // The Exif tag names. See Tiff 6.0 Section 3 and Section 8.
    /** Type is String. */
    public static final String TAG_ARTIST = "Artist";
    /** Type is int. */
    public static final String TAG_BITS_PER_SAMPLE = "BitsPerSample";
    /** Type is int. */
    public static final String TAG_COMPRESSION = "Compression";
    /** Type is String. */
    public static final String TAG_COPYRIGHT = "Copyright";
    /** Type is String. */
    public static final String TAG_DATETIME = "DateTime";
    /** Type is String. */
    public static final String TAG_IMAGE_DESCRIPTION = "ImageDescription";
    /** Type is int. */
    public static final String TAG_IMAGE_LENGTH = "ImageLength";
    /** Type is int. */
    public static final String TAG_IMAGE_WIDTH = "ImageWidth";
    /** Type is int. */
    public static final String TAG_JPEG_INTERCHANGE_FORMAT = "JPEGInterchangeFormat";
    /** Type is int. */
    public static final String TAG_JPEG_INTERCHANGE_FORMAT_LENGTH = "JPEGInterchangeFormatLength";
    /** Type is String. */
    public static final String TAG_MAKE = "Make";
    /** Type is String. */
    public static final String TAG_MODEL = "Model";
    /** Type is int. */
    public static final String TAG_ORIENTATION = "Orientation";
    /** Type is int. */
    public static final String TAG_PHOTOMETRIC_INTERPRETATION = "PhotometricInterpretation";
    /** Type is int. */
    public static final String TAG_PLANAR_CONFIGURATION = "PlanarConfiguration";
    /** Type is rational. */
    public static final String TAG_PRIMARY_CHROMATICITIES = "PrimaryChromaticities";
    /** Type is rational. */
    public static final String TAG_REFERENCE_BLACK_WHITE = "ReferenceBlackWhite";
    /** Type is int. */
    public static final String TAG_RESOLUTION_UNIT = "ResolutionUnit";
    /** Type is int. */
    public static final String TAG_ROWS_PER_STRIP = "RowsPerStrip";
    /** Type is int. */
    public static final String TAG_SAMPLES_PER_PIXEL = "SamplesPerPixel";
    /** Type is String. */
    public static final String TAG_SOFTWARE = "Software";
    /** Type is int. */
    public static final String TAG_STRIP_BYTE_COUNTS = "StripByteCounts";
    /** Type is int. */
    public static final String TAG_STRIP_OFFSETS = "StripOffsets";
    /** Type is int. */
    public static final String TAG_TRANSFER_FUNCTION = "TransferFunction";
    /** Type is rational. */
    public static final String TAG_WHITE_POINT = "WhitePoint";
    /** Type is rational. */
    public static final String TAG_X_RESOLUTION = "XResolution";
    /** Type is rational. */
    public static final String TAG_Y_CB_CR_COEFFICIENTS = "YCbCrCoefficients";
    /** Type is int. */
    public static final String TAG_Y_CB_CR_POSITIONING = "YCbCrPositioning";
    /** Type is int. */
    public static final String TAG_Y_CB_CR_SUB_SAMPLING = "YCbCrSubSampling";
    /** Type is rational. */
    public static final String TAG_Y_RESOLUTION = "YResolution";
    /** Type is rational. */
    public static final String TAG_APERTURE_VALUE = "ApertureValue";
    /** Type is rational. */
    public static final String TAG_BRIGHTNESS_VALUE = "BrightnessValue";
    /** Type is String. */
    public static final String TAG_CFA_PATTERN = "CFAPattern";
    /** Type is int. */
    public static final String TAG_COLOR_SPACE = "ColorSpace";
    /** Type is String. */
    public static final String TAG_COMPONENTS_CONFIGURATION = "ComponentsConfiguration";
    /** Type is rational. */
    public static final String TAG_COMPRESSED_BITS_PER_PIXEL = "CompressedBitsPerPixel";
    /** Type is int. */
    public static final String TAG_CONTRAST = "Contrast";
    /** Type is int. */
    public static final String TAG_CUSTOM_RENDERED = "CustomRendered";
    /** Type is String. */
    public static final String TAG_DATETIME_DIGITIZED = "DateTimeDigitized";
    /** Type is String. */
    public static final String TAG_DATETIME_ORIGINAL = "DateTimeOriginal";
    /** Type is String. */
    public static final String TAG_DEVICE_SETTING_DESCRIPTION = "DeviceSettingDescription";
    /** Type is double. */
    public static final String TAG_DIGITAL_ZOOM_RATIO = "DigitalZoomRatio";
    /** Type is String. */
    public static final String TAG_EXIF_VERSION = "ExifVersion";
    /** Type is double. */
    public static final String TAG_EXPOSURE_BIAS_VALUE = "ExposureBiasValue";
    /** Type is rational. */
    public static final String TAG_EXPOSURE_INDEX = "ExposureIndex";
    /** Type is int. */
    public static final String TAG_EXPOSURE_MODE = "ExposureMode";
    /** Type is int. */
    public static final String TAG_EXPOSURE_PROGRAM = "ExposureProgram";
    /** Type is double. */
    public static final String TAG_EXPOSURE_TIME = "ExposureTime";
    /** Type is double. */
    public static final String TAG_F_NUMBER = "FNumber";
    /**
     * Type is double.
     *
     * @deprecated use {@link #TAG_F_NUMBER} instead
     */
    @Deprecated
    public static final String TAG_APERTURE = "FNumber";
    /** Type is String. */
    public static final String TAG_FILE_SOURCE = "FileSource";
    /** Type is int. */
    public static final String TAG_FLASH = "Flash";
    /** Type is rational. */
    public static final String TAG_FLASH_ENERGY = "FlashEnergy";
    /** Type is String. */
    public static final String TAG_FLASHPIX_VERSION = "FlashpixVersion";
    /** Type is rational. */
    public static final String TAG_FOCAL_LENGTH = "FocalLength";
    /** Type is int. */
    public static final String TAG_FOCAL_LENGTH_IN_35MM_FILM = "FocalLengthIn35mmFilm";
    /** Type is int. */
    public static final String TAG_FOCAL_PLANE_RESOLUTION_UNIT = "FocalPlaneResolutionUnit";
    /** Type is rational. */
    public static final String TAG_FOCAL_PLANE_X_RESOLUTION = "FocalPlaneXResolution";
    /** Type is rational. */
    public static final String TAG_FOCAL_PLANE_Y_RESOLUTION = "FocalPlaneYResolution";
    /** Type is int. */
    public static final String TAG_GAIN_CONTROL = "GainControl";
    /** Type is int. */
    public static final String TAG_ISO_SPEED_RATINGS = "ISOSpeedRatings";
    /**
     * Type is int.
     *
     * @deprecated use {@link #TAG_ISO_SPEED_RATINGS} instead
     */
    @Deprecated
    public static final String TAG_ISO = "ISOSpeedRatings";
    /** Type is String. */
    public static final String TAG_IMAGE_UNIQUE_ID = "ImageUniqueID";
    /** Type is int. */
    public static final String TAG_LIGHT_SOURCE = "LightSource";
    /** Type is String. */
    public static final String TAG_MAKER_NOTE = "MakerNote";
    /** Type is rational. */
    public static final String TAG_MAX_APERTURE_VALUE = "MaxApertureValue";
    /** Type is int. */
    public static final String TAG_METERING_MODE = "MeteringMode";
    /** Type is int. */
    public static final String TAG_NEW_SUBFILE_TYPE = "NewSubfileType";
    /** Type is String. */
    public static final String TAG_OECF = "OECF";
    /**
     *  <p>A tag used to record the offset from UTC (the time difference from Universal Time
     *  Coordinated including daylight saving time) of the time of DateTime tag. The format when
     *  recording the offset is "±HH:MM". The part of "±" shall be recorded as "+" or "-". When
     *  the offsets are unknown, all the character spaces except colons (":") should be filled
     *  with blank characters, or else the Interoperability field should be filled with blank
     *  characters. The character string length is 7 Bytes including NULL for termination. When
     *  the field is left blank, it is treated as unknown.</p>
     *
     *  <ul>
     *      <li>Tag = 36880</li>
     *      <li>Type = String</li>
     *      <li>Length = 7</li>
     *      <li>Default = None</li>
     *  </ul>
     */
    public static final String TAG_OFFSET_TIME = "OffsetTime";
    /**
     *  <p>A tag used to record the offset from UTC (the time difference from Universal Time
     *  Coordinated including daylight saving time) of the time of DateTimeOriginal tag. The format
     *  when recording the offset is "±HH:MM". The part of "±" shall be recorded as "+" or "-". When
     *  the offsets are unknown, all the character spaces except colons (":") should be filled
     *  with blank characters, or else the Interoperability field should be filled with blank
     *  characters. The character string length is 7 Bytes including NULL for termination. When
     *  the field is left blank, it is treated as unknown.</p>
     *
     *  <ul>
     *      <li>Tag = 36881</li>
     *      <li>Type = String</li>
     *      <li>Length = 7</li>
     *      <li>Default = None</li>
     *  </ul>
     */
    public static final String TAG_OFFSET_TIME_ORIGINAL = "OffsetTimeOriginal";
    /**
     *  <p>A tag used to record the offset from UTC (the time difference from Universal Time
     *  Coordinated including daylight saving time) of the time of DateTimeDigitized tag. The format
     *  when recording the offset is "±HH:MM". The part of "±" shall be recorded as "+" or "-". When
     *  the offsets are unknown, all the character spaces except colons (":") should be filled
     *  with blank characters, or else the Interoperability field should be filled with blank
     *  characters. The character string length is 7 Bytes including NULL for termination. When
     *  the field is left blank, it is treated as unknown.</p>
     *
     *  <ul>
     *      <li>Tag = 36882</li>
     *      <li>Type = String</li>
     *      <li>Length = 7</li>
     *      <li>Default = None</li>
     *  </ul>
     */
    public static final String TAG_OFFSET_TIME_DIGITIZED = "OffsetTimeDigitized";
    /** Type is int. */
    public static final String TAG_PIXEL_X_DIMENSION = "PixelXDimension";
    /** Type is int. */
    public static final String TAG_PIXEL_Y_DIMENSION = "PixelYDimension";
    /** Type is String. */
    public static final String TAG_RELATED_SOUND_FILE = "RelatedSoundFile";
    /** Type is int. */
    public static final String TAG_SATURATION = "Saturation";
    /** Type is int. */
    public static final String TAG_SCENE_CAPTURE_TYPE = "SceneCaptureType";
    /** Type is String. */
    public static final String TAG_SCENE_TYPE = "SceneType";
    /** Type is int. */
    public static final String TAG_SENSING_METHOD = "SensingMethod";
    /** Type is int. */
    public static final String TAG_SHARPNESS = "Sharpness";
    /** Type is rational. */
    public static final String TAG_SHUTTER_SPEED_VALUE = "ShutterSpeedValue";
    /** Type is String. */
    public static final String TAG_SPATIAL_FREQUENCY_RESPONSE = "SpatialFrequencyResponse";
    /** Type is String. */
    public static final String TAG_SPECTRAL_SENSITIVITY = "SpectralSensitivity";
    /** Type is int. */
    public static final String TAG_SUBFILE_TYPE = "SubfileType";
    /** Type is String. */
    public static final String TAG_SUBSEC_TIME = "SubSecTime";
    /**
     * Type is String.
     *
     * @deprecated use {@link #TAG_SUBSEC_TIME_DIGITIZED} instead
     */
    public static final String TAG_SUBSEC_TIME_DIG = "SubSecTimeDigitized";
    /** Type is String. */
    public static final String TAG_SUBSEC_TIME_DIGITIZED = "SubSecTimeDigitized";
    /**
     * Type is String.
     *
     * @deprecated use {@link #TAG_SUBSEC_TIME_ORIGINAL} instead
     */
    public static final String TAG_SUBSEC_TIME_ORIG = "SubSecTimeOriginal";
    /** Type is String. */
    public static final String TAG_SUBSEC_TIME_ORIGINAL = "SubSecTimeOriginal";
    /** Type is int. */
    public static final String TAG_SUBJECT_AREA = "SubjectArea";
    /** Type is double. */
    public static final String TAG_SUBJECT_DISTANCE = "SubjectDistance";
    /** Type is int. */
    public static final String TAG_SUBJECT_DISTANCE_RANGE = "SubjectDistanceRange";
    /** Type is int. */
    public static final String TAG_SUBJECT_LOCATION = "SubjectLocation";
    /** Type is String. */
    public static final String TAG_USER_COMMENT = "UserComment";
    /** Type is int. */
    public static final String TAG_WHITE_BALANCE = "WhiteBalance";
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
    public static final String TAG_GPS_AREA_INFORMATION = "GPSAreaInformation";
    /** Type is rational. */
    public static final String TAG_GPS_DOP = "GPSDOP";
    /** Type is String. */
    public static final String TAG_GPS_DATESTAMP = "GPSDateStamp";
    /** Type is rational. */
    public static final String TAG_GPS_DEST_BEARING = "GPSDestBearing";
    /** Type is String. */
    public static final String TAG_GPS_DEST_BEARING_REF = "GPSDestBearingRef";
    /** Type is rational. */
    public static final String TAG_GPS_DEST_DISTANCE = "GPSDestDistance";
    /** Type is String. */
    public static final String TAG_GPS_DEST_DISTANCE_REF = "GPSDestDistanceRef";
    /** Type is rational. */
    public static final String TAG_GPS_DEST_LATITUDE = "GPSDestLatitude";
    /** Type is String. */
    public static final String TAG_GPS_DEST_LATITUDE_REF = "GPSDestLatitudeRef";
    /** Type is rational. */
    public static final String TAG_GPS_DEST_LONGITUDE = "GPSDestLongitude";
    /** Type is String. */
    public static final String TAG_GPS_DEST_LONGITUDE_REF = "GPSDestLongitudeRef";
    /** Type is int. */
    public static final String TAG_GPS_DIFFERENTIAL = "GPSDifferential";
    /** Type is rational. */
    public static final String TAG_GPS_IMG_DIRECTION = "GPSImgDirection";
    /** Type is String. */
    public static final String TAG_GPS_IMG_DIRECTION_REF = "GPSImgDirectionRef";
    /** Type is rational. Format is "num1/denom1,num2/denom2,num3/denom3". */
    public static final String TAG_GPS_LATITUDE = "GPSLatitude";
    /** Type is String. */
    public static final String TAG_GPS_LATITUDE_REF = "GPSLatitudeRef";
    /** Type is rational. Format is "num1/denom1,num2/denom2,num3/denom3". */
    public static final String TAG_GPS_LONGITUDE = "GPSLongitude";
    /** Type is String. */
    public static final String TAG_GPS_LONGITUDE_REF = "GPSLongitudeRef";
    /** Type is String. */
    public static final String TAG_GPS_MAP_DATUM = "GPSMapDatum";
    /** Type is String. */
    public static final String TAG_GPS_MEASURE_MODE = "GPSMeasureMode";
    /** Type is String. Name of GPS processing method used for location finding. */
    public static final String TAG_GPS_PROCESSING_METHOD = "GPSProcessingMethod";
    /** Type is String. */
    public static final String TAG_GPS_SATELLITES = "GPSSatellites";
    /** Type is rational. */
    public static final String TAG_GPS_SPEED = "GPSSpeed";
    /** Type is String. */
    public static final String TAG_GPS_SPEED_REF = "GPSSpeedRef";
    /** Type is String. */
    public static final String TAG_GPS_STATUS = "GPSStatus";
    /** Type is String. Format is "hh:mm:ss". */
    public static final String TAG_GPS_TIMESTAMP = "GPSTimeStamp";
    /** Type is rational. */
    public static final String TAG_GPS_TRACK = "GPSTrack";
    /** Type is String. */
    public static final String TAG_GPS_TRACK_REF = "GPSTrackRef";
    /** Type is String. */
    public static final String TAG_GPS_VERSION_ID = "GPSVersionID";
    /** Type is String. */
    public static final String TAG_INTEROPERABILITY_INDEX = "InteroperabilityIndex";
    /** Type is int. */
    public static final String TAG_THUMBNAIL_IMAGE_LENGTH = "ThumbnailImageLength";
    /** Type is int. */
    public static final String TAG_THUMBNAIL_IMAGE_WIDTH = "ThumbnailImageWidth";
    /** Type is int. */
    public static final String TAG_THUMBNAIL_ORIENTATION = "ThumbnailOrientation";
    /** Type is int. DNG Specification 1.4.0.0. Section 4 */
    public static final String TAG_DNG_VERSION = "DNGVersion";
    /** Type is int. DNG Specification 1.4.0.0. Section 4 */
    public static final String TAG_DEFAULT_CROP_SIZE = "DefaultCropSize";
    /** Type is undefined. See Olympus MakerNote tags in http://www.exiv2.org/tags-olympus.html. */
    public static final String TAG_ORF_THUMBNAIL_IMAGE = "ThumbnailImage";
    /** Type is int. See Olympus Camera Settings tags in http://www.exiv2.org/tags-olympus.html. */
    public static final String TAG_ORF_PREVIEW_IMAGE_START = "PreviewImageStart";
    /** Type is int. See Olympus Camera Settings tags in http://www.exiv2.org/tags-olympus.html. */
    public static final String TAG_ORF_PREVIEW_IMAGE_LENGTH = "PreviewImageLength";
    /** Type is int. See Olympus Image Processing tags in http://www.exiv2.org/tags-olympus.html. */
    public static final String TAG_ORF_ASPECT_FRAME = "AspectFrame";
    /**
     * Type is int. See PanasonicRaw tags in
     * http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/PanasonicRaw.html
     */
    public static final String TAG_RW2_SENSOR_BOTTOM_BORDER = "SensorBottomBorder";
    /**
     * Type is int. See PanasonicRaw tags in
     * http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/PanasonicRaw.html
     */
    public static final String TAG_RW2_SENSOR_LEFT_BORDER = "SensorLeftBorder";
    /**
     * Type is int. See PanasonicRaw tags in
     * http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/PanasonicRaw.html
     */
    public static final String TAG_RW2_SENSOR_RIGHT_BORDER = "SensorRightBorder";
    /**
     * Type is int. See PanasonicRaw tags in
     * http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/PanasonicRaw.html
     */
    public static final String TAG_RW2_SENSOR_TOP_BORDER = "SensorTopBorder";
    /**
     * Type is int. See PanasonicRaw tags in
     * http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/PanasonicRaw.html
     */
    public static final String TAG_RW2_ISO = "ISO";
    /**
     * Type is undefined. See PanasonicRaw tags in
     * http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/PanasonicRaw.html
     */
    public static final String TAG_RW2_JPG_FROM_RAW = "JpgFromRaw";
    /**
     * Type is byte[]. See <a href=
     * "https://en.wikipedia.org/wiki/Extensible_Metadata_Platform">Extensible
     * Metadata Platform (XMP)</a> for details on contents.
     */
    public static final String TAG_XMP = "Xmp";

    /**
     * Private tags used for pointing the other IFD offsets.
     * The types of the following tags are int.
     * See JEITA CP-3451C Section 4.6.3: Exif-specific IFD.
     * For SubIFD, see Note 1 of Adobe PageMaker® 6.0 TIFF Technical Notes.
     */
    private static final String TAG_EXIF_IFD_POINTER = "ExifIFDPointer";
    private static final String TAG_GPS_INFO_IFD_POINTER = "GPSInfoIFDPointer";
    private static final String TAG_INTEROPERABILITY_IFD_POINTER = "InteroperabilityIFDPointer";
    private static final String TAG_SUB_IFD_POINTER = "SubIFDPointer";
    // Proprietary pointer tags used for ORF files.
    // See http://www.exiv2.org/tags-olympus.html
    private static final String TAG_ORF_CAMERA_SETTINGS_IFD_POINTER = "CameraSettingsIFDPointer";
    private static final String TAG_ORF_IMAGE_PROCESSING_IFD_POINTER = "ImageProcessingIFDPointer";

    // Private tags used for thumbnail information.
    private static final String TAG_HAS_THUMBNAIL = "HasThumbnail";
    private static final String TAG_THUMBNAIL_OFFSET = "ThumbnailOffset";
    private static final String TAG_THUMBNAIL_LENGTH = "ThumbnailLength";
    private static final String TAG_THUMBNAIL_DATA = "ThumbnailData";
    private static final int MAX_THUMBNAIL_SIZE = 512;

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

    /**
     * Constant used to indicate that the input stream contains the full image data.
     * <p>
     * The format of the image data should follow one of the image formats supported by this class.
     */
    public static final int STREAM_TYPE_FULL_IMAGE_DATA = 0;
    /**
     * Constant used to indicate that the input stream contains only Exif data.
     * <p>
     * The format of the Exif-only data must follow the below structure:
     *     Exif Identifier Code ("Exif\0\0") + TIFF header + IFD data
     * See JEITA CP-3451C Section 4.5.2 and 4.5.4 specifications for more details.
     */
    public static final int STREAM_TYPE_EXIF_DATA_ONLY = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STREAM_TYPE_FULL_IMAGE_DATA, STREAM_TYPE_EXIF_DATA_ONLY})
    public @interface ExifStreamType {}

    // Maximum size for checking file type signature (see image_type_recognition_lite.cc)
    private static final int SIGNATURE_CHECK_SIZE = 5000;

    private static final byte[] JPEG_SIGNATURE = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final String RAF_SIGNATURE = "FUJIFILMCCD-RAW";
    private static final int RAF_OFFSET_TO_JPEG_IMAGE_OFFSET = 84;
    private static final int RAF_INFO_SIZE = 160;
    private static final int RAF_JPEG_LENGTH_VALUE_SIZE = 4;

    private static final byte[] HEIF_TYPE_FTYP = new byte[] {'f', 't', 'y', 'p'};
    private static final byte[] HEIF_BRAND_MIF1 = new byte[] {'m', 'i', 'f', '1'};
    private static final byte[] HEIF_BRAND_HEIC = new byte[] {'h', 'e', 'i', 'c'};
    private static final byte[] HEIF_BRAND_AVIF = new byte[] {'a', 'v', 'i', 'f'};
    private static final byte[] HEIF_BRAND_AVIS = new byte[] {'a', 'v', 'i', 's'};

    // See http://fileformats.archiveteam.org/wiki/Olympus_ORF
    private static final short ORF_SIGNATURE_1 = 0x4f52;
    private static final short ORF_SIGNATURE_2 = 0x5352;
    // There are two formats for Olympus Makernote Headers. Each has different identifiers and
    // offsets to the actual data.
    // See http://www.exiv2.org/makernote.html#R1
    private static final byte[] ORF_MAKER_NOTE_HEADER_1 = new byte[] {(byte) 0x4f, (byte) 0x4c,
            (byte) 0x59, (byte) 0x4d, (byte) 0x50, (byte) 0x00}; // "OLYMP\0"
    private static final byte[] ORF_MAKER_NOTE_HEADER_2 = new byte[] {(byte) 0x4f, (byte) 0x4c,
            (byte) 0x59, (byte) 0x4d, (byte) 0x50, (byte) 0x55, (byte) 0x53, (byte) 0x00,
            (byte) 0x49, (byte) 0x49}; // "OLYMPUS\0II"
    private static final int ORF_MAKER_NOTE_HEADER_1_SIZE = 8;
    private static final int ORF_MAKER_NOTE_HEADER_2_SIZE = 12;

    // See http://fileformats.archiveteam.org/wiki/RW2
    private static final short RW2_SIGNATURE = 0x0055;

    // See http://fileformats.archiveteam.org/wiki/Pentax_PEF
    private static final String PEF_SIGNATURE = "PENTAX";
    // See http://www.exiv2.org/makernote.html#R11
    private static final int PEF_MAKER_NOTE_SKIP_SIZE = 6;

    // See PNG (Portable Network Graphics) Specification, Version 1.2,
    // 3.1. PNG file signature
    private static final byte[] PNG_SIGNATURE = new byte[] {(byte) 0x89, (byte) 0x50, (byte) 0x4e,
            (byte) 0x47, (byte) 0x0d, (byte) 0x0a, (byte) 0x1a, (byte) 0x0a};
    // See "Extensions to the PNG 1.2 Specification, Version 1.5.0",
    // 3.7. eXIf Exchangeable Image File (Exif) Profile
    private static final byte[] PNG_CHUNK_TYPE_EXIF = new byte[]{(byte) 0x65, (byte) 0x58,
            (byte) 0x49, (byte) 0x66};
    private static final byte[] PNG_CHUNK_TYPE_IHDR = new byte[]{(byte) 0x49, (byte) 0x48,
            (byte) 0x44, (byte) 0x52};
    private static final byte[] PNG_CHUNK_TYPE_IEND = new byte[]{(byte) 0x49, (byte) 0x45,
            (byte) 0x4e, (byte) 0x44};
    private static final int PNG_CHUNK_TYPE_BYTE_LENGTH = 4;
    private static final int PNG_CHUNK_CRC_BYTE_LENGTH = 4;

    // See https://developers.google.com/speed/webp/docs/riff_container, Section "WebP File Header"
    private static final byte[] WEBP_SIGNATURE_1 = new byte[] {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_SIGNATURE_2 = new byte[] {'W', 'E', 'B', 'P'};
    private static final int WEBP_FILE_SIZE_BYTE_LENGTH = 4;
    private static final byte[] WEBP_CHUNK_TYPE_EXIF = new byte[]{(byte) 0x45, (byte) 0x58,
            (byte) 0x49, (byte) 0x46};
    private static final byte[] WEBP_VP8_SIGNATURE = new byte[]{(byte) 0x9d, (byte) 0x01,
            (byte) 0x2a};
    private static final byte WEBP_VP8L_SIGNATURE = (byte) 0x2f;
    private static final byte[] WEBP_CHUNK_TYPE_VP8X = "VP8X".getBytes(Charset.defaultCharset());
    private static final byte[] WEBP_CHUNK_TYPE_VP8L = "VP8L".getBytes(Charset.defaultCharset());
    private static final byte[] WEBP_CHUNK_TYPE_VP8 = "VP8 ".getBytes(Charset.defaultCharset());
    private static final byte[] WEBP_CHUNK_TYPE_ANIM = "ANIM".getBytes(Charset.defaultCharset());
    private static final byte[] WEBP_CHUNK_TYPE_ANMF = "ANMF".getBytes(Charset.defaultCharset());
    private static final int WEBP_CHUNK_TYPE_VP8X_DEFAULT_LENGTH = 10;
    private static final int WEBP_CHUNK_TYPE_BYTE_LENGTH = 4;
    private static final int WEBP_CHUNK_SIZE_BYTE_LENGTH = 4;

    @GuardedBy("sFormatter")
    private static SimpleDateFormat sFormatter;
    @GuardedBy("sFormatterTz")
    private static SimpleDateFormat sFormatterTz;

    // See Exchangeable image file format for digital still cameras: Exif version 2.2.
    // The following values are for parsing EXIF data area. There are tag groups in EXIF data area.
    // They are called "Image File Directory". They have multiple data formats to cover various
    // image metadata from GPS longitude to camera model name.

    // Types of Exif byte alignments (see JEITA CP-3451C Section 4.5.2)
    private static final short BYTE_ALIGN_II = 0x4949;  // II: Intel order
    private static final short BYTE_ALIGN_MM = 0x4d4d;  // MM: Motorola order

    // TIFF Header Fixed Constant (see JEITA CP-3451C Section 4.5.2)
    private static final byte START_CODE = 0x2a; // 42
    private static final int IFD_OFFSET = 8;

    // Formats for the value in IFD entry (See TIFF 6.0 Section 2, "Image File Directory".)
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
    // Format indicating a new IFD entry (See Adobe PageMaker® 6.0 TIFF Technical Notes, "New Tag")
    private static final int IFD_FORMAT_IFD = 13;
    // Names for the data formats for debugging purpose.
    private static final String[] IFD_FORMAT_NAMES = new String[] {
            "", "BYTE", "STRING", "USHORT", "ULONG", "URATIONAL", "SBYTE", "UNDEFINED", "SSHORT",
            "SLONG", "SRATIONAL", "SINGLE", "DOUBLE", "IFD"
    };
    // Sizes of the components of each IFD value format
    private static final int[] IFD_FORMAT_BYTES_PER_FORMAT = new int[] {
            0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 1
    };
    private static final byte[] EXIF_ASCII_PREFIX = new byte[] {
            0x41, 0x53, 0x43, 0x49, 0x49, 0x0, 0x0, 0x0
    };

    /**
     * Constants used for Compression tag.
     * For Value 1, 2, 32773, see TIFF 6.0 Spec Section 3: Bilevel Images, Compression
     * For Value 6, see TIFF 6.0 Spec Section 22: JPEG Compression, Extensions to Existing Fields
     * For Value 7, 8, 34892, see DNG Specification 1.4.0.0. Section 3, Compression
     */
    private static final int DATA_UNCOMPRESSED = 1;
    private static final int DATA_HUFFMAN_COMPRESSED = 2;
    private static final int DATA_JPEG = 6;
    private static final int DATA_JPEG_COMPRESSED = 7;
    private static final int DATA_DEFLATE_ZIP = 8;
    private static final int DATA_PACK_BITS_COMPRESSED = 32773;
    private static final int DATA_LOSSY_JPEG = 34892;

    /**
     * Constants used for BitsPerSample tag.
     * For RGB, see TIFF 6.0 Spec Section 6, Differences from Palette Color Images
     * For Greyscale, see TIFF 6.0 Spec Section 4, Differences from Bilevel Images
     */
    private static final int[] BITS_PER_SAMPLE_RGB = new int[] { 8, 8, 8 };
    private static final int[] BITS_PER_SAMPLE_GREYSCALE_1 = new int[] { 4 };
    private static final int[] BITS_PER_SAMPLE_GREYSCALE_2 = new int[] { 8 };

    /**
     * Constants used for PhotometricInterpretation tag.
     * For White/Black, see Section 3, Color.
     * See TIFF 6.0 Spec Section 22, Minimum Requirements for TIFF with JPEG Compression.
     */
    private static final int PHOTOMETRIC_INTERPRETATION_WHITE_IS_ZERO = 0;
    private static final int PHOTOMETRIC_INTERPRETATION_BLACK_IS_ZERO = 1;
    private static final int PHOTOMETRIC_INTERPRETATION_RGB = 2;
    private static final int PHOTOMETRIC_INTERPRETATION_YCBCR = 6;

    /**
     * Constants used for NewSubfileType tag.
     * See TIFF 6.0 Spec Section 8
     * */
    private static final int ORIGINAL_RESOLUTION_IMAGE = 0;
    private static final int REDUCED_RESOLUTION_IMAGE = 1;

    // A class for indicating EXIF rational type.
    private static class Rational {
        public final long numerator;
        public final long denominator;

        private Rational(long numerator, long denominator) {
            // Handle erroneous case
            if (denominator == 0) {
                this.numerator = 0;
                this.denominator = 1;
                return;
            }
            this.numerator = numerator;
            this.denominator = denominator;
        }

        @Override
        public String toString() {
            return numerator + "/" + denominator;
        }

        public double calculate() {
            return (double) numerator / denominator;
        }
    }

    // A class for indicating EXIF attribute.
    private static class ExifAttribute {
        public final int format;
        public final int numberOfComponents;
        public final long bytesOffset;
        public final byte[] bytes;

        public static final long BYTES_OFFSET_UNKNOWN = -1;

        private ExifAttribute(int format, int numberOfComponents, byte[] bytes) {
            this(format, numberOfComponents, BYTES_OFFSET_UNKNOWN, bytes);
        }

        private ExifAttribute(int format, int numberOfComponents, long bytesOffset, byte[] bytes) {
            this.format = format;
            this.numberOfComponents = numberOfComponents;
            this.bytesOffset = bytesOffset;
            this.bytes = bytes;
        }

        public static ExifAttribute createUShort(int[] values, ByteOrder byteOrder) {
            final ByteBuffer buffer = ByteBuffer.wrap(
                    new byte[IFD_FORMAT_BYTES_PER_FORMAT[IFD_FORMAT_USHORT] * values.length]);
            buffer.order(byteOrder);
            for (int value : values) {
                buffer.putShort((short) value);
            }
            return new ExifAttribute(IFD_FORMAT_USHORT, values.length, buffer.array());
        }

        public static ExifAttribute createUShort(int value, ByteOrder byteOrder) {
            return createUShort(new int[] {value}, byteOrder);
        }

        public static ExifAttribute createULong(long[] values, ByteOrder byteOrder) {
            final ByteBuffer buffer = ByteBuffer.wrap(
                    new byte[IFD_FORMAT_BYTES_PER_FORMAT[IFD_FORMAT_ULONG] * values.length]);
            buffer.order(byteOrder);
            for (long value : values) {
                buffer.putInt((int) value);
            }
            return new ExifAttribute(IFD_FORMAT_ULONG, values.length, buffer.array());
        }

        public static ExifAttribute createULong(long value, ByteOrder byteOrder) {
            return createULong(new long[] {value}, byteOrder);
        }

        public static ExifAttribute createSLong(int[] values, ByteOrder byteOrder) {
            final ByteBuffer buffer = ByteBuffer.wrap(
                    new byte[IFD_FORMAT_BYTES_PER_FORMAT[IFD_FORMAT_SLONG] * values.length]);
            buffer.order(byteOrder);
            for (int value : values) {
                buffer.putInt(value);
            }
            return new ExifAttribute(IFD_FORMAT_SLONG, values.length, buffer.array());
        }

        public static ExifAttribute createSLong(int value, ByteOrder byteOrder) {
            return createSLong(new int[] {value}, byteOrder);
        }

        public static ExifAttribute createByte(String value) {
            // Exception for GPSAltitudeRef tag
            if (value.length() == 1 && value.charAt(0) >= '0' && value.charAt(0) <= '1') {
                final byte[] bytes = new byte[] { (byte) (value.charAt(0) - '0') };
                return new ExifAttribute(IFD_FORMAT_BYTE, bytes.length, bytes);
            }
            final byte[] ascii = value.getBytes(ASCII);
            return new ExifAttribute(IFD_FORMAT_BYTE, ascii.length, ascii);
        }

        public static ExifAttribute createString(String value) {
            final byte[] ascii = (value + '\0').getBytes(ASCII);
            return new ExifAttribute(IFD_FORMAT_STRING, ascii.length, ascii);
        }

        public static ExifAttribute createURational(Rational[] values, ByteOrder byteOrder) {
            final ByteBuffer buffer = ByteBuffer.wrap(
                    new byte[IFD_FORMAT_BYTES_PER_FORMAT[IFD_FORMAT_URATIONAL] * values.length]);
            buffer.order(byteOrder);
            for (Rational value : values) {
                buffer.putInt((int) value.numerator);
                buffer.putInt((int) value.denominator);
            }
            return new ExifAttribute(IFD_FORMAT_URATIONAL, values.length, buffer.array());
        }

        public static ExifAttribute createURational(Rational value, ByteOrder byteOrder) {
            return createURational(new Rational[] {value}, byteOrder);
        }

        public static ExifAttribute createSRational(Rational[] values, ByteOrder byteOrder) {
            final ByteBuffer buffer = ByteBuffer.wrap(
                    new byte[IFD_FORMAT_BYTES_PER_FORMAT[IFD_FORMAT_SRATIONAL] * values.length]);
            buffer.order(byteOrder);
            for (Rational value : values) {
                buffer.putInt((int) value.numerator);
                buffer.putInt((int) value.denominator);
            }
            return new ExifAttribute(IFD_FORMAT_SRATIONAL, values.length, buffer.array());
        }

        public static ExifAttribute createSRational(Rational value, ByteOrder byteOrder) {
            return createSRational(new Rational[] {value}, byteOrder);
        }

        public static ExifAttribute createDouble(double[] values, ByteOrder byteOrder) {
            final ByteBuffer buffer = ByteBuffer.wrap(
                    new byte[IFD_FORMAT_BYTES_PER_FORMAT[IFD_FORMAT_DOUBLE] * values.length]);
            buffer.order(byteOrder);
            for (double value : values) {
                buffer.putDouble(value);
            }
            return new ExifAttribute(IFD_FORMAT_DOUBLE, values.length, buffer.array());
        }

        public static ExifAttribute createDouble(double value, ByteOrder byteOrder) {
            return createDouble(new double[] {value}, byteOrder);
        }

        @Override
        public String toString() {
            return "(" + IFD_FORMAT_NAMES[format] + ", data length:" + bytes.length + ")";
        }

        private Object getValue(ByteOrder byteOrder) {
            try {
                ByteOrderedDataInputStream inputStream =
                        new ByteOrderedDataInputStream(bytes);
                inputStream.setByteOrder(byteOrder);
                switch (format) {
                    case IFD_FORMAT_BYTE:
                    case IFD_FORMAT_SBYTE: {
                        // Exception for GPSAltitudeRef tag
                        if (bytes.length == 1 && bytes[0] >= 0 && bytes[0] <= 1) {
                            return new String(new char[] { (char) (bytes[0] + '0') });
                        }
                        return new String(bytes, ASCII);
                    }
                    case IFD_FORMAT_UNDEFINED:
                    case IFD_FORMAT_STRING: {
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
                        while (index < numberOfComponents) {
                            int ch = bytes[index];
                            if (ch == 0) {
                                break;
                            }
                            if (ch >= 32) {
                                stringBuilder.append((char) ch);
                            } else {
                                stringBuilder.append('?');
                            }
                            ++index;
                        }
                        return stringBuilder.toString();
                    }
                    case IFD_FORMAT_USHORT: {
                        final int[] values = new int[numberOfComponents];
                        for (int i = 0; i < numberOfComponents; ++i) {
                            values[i] = inputStream.readUnsignedShort();
                        }
                        return values;
                    }
                    case IFD_FORMAT_ULONG: {
                        final long[] values = new long[numberOfComponents];
                        for (int i = 0; i < numberOfComponents; ++i) {
                            values[i] = inputStream.readUnsignedInt();
                        }
                        return values;
                    }
                    case IFD_FORMAT_URATIONAL: {
                        final Rational[] values = new Rational[numberOfComponents];
                        for (int i = 0; i < numberOfComponents; ++i) {
                            final long numerator = inputStream.readUnsignedInt();
                            final long denominator = inputStream.readUnsignedInt();
                            values[i] = new Rational(numerator, denominator);
                        }
                        return values;
                    }
                    case IFD_FORMAT_SSHORT: {
                        final int[] values = new int[numberOfComponents];
                        for (int i = 0; i < numberOfComponents; ++i) {
                            values[i] = inputStream.readShort();
                        }
                        return values;
                    }
                    case IFD_FORMAT_SLONG: {
                        final int[] values = new int[numberOfComponents];
                        for (int i = 0; i < numberOfComponents; ++i) {
                            values[i] = inputStream.readInt();
                        }
                        return values;
                    }
                    case IFD_FORMAT_SRATIONAL: {
                        final Rational[] values = new Rational[numberOfComponents];
                        for (int i = 0; i < numberOfComponents; ++i) {
                            final long numerator = inputStream.readInt();
                            final long denominator = inputStream.readInt();
                            values[i] = new Rational(numerator, denominator);
                        }
                        return values;
                    }
                    case IFD_FORMAT_SINGLE: {
                        final double[] values = new double[numberOfComponents];
                        for (int i = 0; i < numberOfComponents; ++i) {
                            values[i] = inputStream.readFloat();
                        }
                        return values;
                    }
                    case IFD_FORMAT_DOUBLE: {
                        final double[] values = new double[numberOfComponents];
                        for (int i = 0; i < numberOfComponents; ++i) {
                            values[i] = inputStream.readDouble();
                        }
                        return values;
                    }
                    default:
                        return null;
                }
            } catch (IOException e) {
                Log.w(TAG, "IOException occurred during reading a value", e);
                return null;
            }
        }

        public double getDoubleValue(ByteOrder byteOrder) {
            Object value = getValue(byteOrder);
            if (value == null) {
                throw new NumberFormatException("NULL can't be converted to a double value");
            }
            if (value instanceof String) {
                return Double.parseDouble((String) value);
            }
            if (value instanceof long[]) {
                long[] array = (long[]) value;
                if (array.length == 1) {
                    return array[0];
                }
                throw new NumberFormatException("There are more than one component");
            }
            if (value instanceof int[]) {
                int[] array = (int[]) value;
                if (array.length == 1) {
                    return array[0];
                }
                throw new NumberFormatException("There are more than one component");
            }
            if (value instanceof double[]) {
                double[] array = (double[]) value;
                if (array.length == 1) {
                    return array[0];
                }
                throw new NumberFormatException("There are more than one component");
            }
            if (value instanceof Rational[]) {
                Rational[] array = (Rational[]) value;
                if (array.length == 1) {
                    return array[0].calculate();
                }
                throw new NumberFormatException("There are more than one component");
            }
            throw new NumberFormatException("Couldn't find a double value");
        }

        public int getIntValue(ByteOrder byteOrder) {
            Object value = getValue(byteOrder);
            if (value == null) {
                throw new NumberFormatException("NULL can't be converted to a integer value");
            }
            if (value instanceof String) {
                return Integer.parseInt((String) value);
            }
            if (value instanceof long[]) {
                long[] array = (long[]) value;
                if (array.length == 1) {
                    return (int) array[0];
                }
                throw new NumberFormatException("There are more than one component");
            }
            if (value instanceof int[]) {
                int[] array = (int[]) value;
                if (array.length == 1) {
                    return array[0];
                }
                throw new NumberFormatException("There are more than one component");
            }
            throw new NumberFormatException("Couldn't find a integer value");
        }

        public String getStringValue(ByteOrder byteOrder) {
            Object value = getValue(byteOrder);
            if (value == null) {
                return null;
            }
            if (value instanceof String) {
                return (String) value;
            }

            final StringBuilder stringBuilder = new StringBuilder();
            if (value instanceof long[]) {
                long[] array = (long[]) value;
                for (int i = 0; i < array.length; ++i) {
                    stringBuilder.append(array[i]);
                    if (i + 1 != array.length) {
                        stringBuilder.append(",");
                    }
                }
                return stringBuilder.toString();
            }
            if (value instanceof int[]) {
                int[] array = (int[]) value;
                for (int i = 0; i < array.length; ++i) {
                    stringBuilder.append(array[i]);
                    if (i + 1 != array.length) {
                        stringBuilder.append(",");
                    }
                }
                return stringBuilder.toString();
            }
            if (value instanceof double[]) {
                double[] array = (double[]) value;
                for (int i = 0; i < array.length; ++i) {
                    stringBuilder.append(array[i]);
                    if (i + 1 != array.length) {
                        stringBuilder.append(",");
                    }
                }
                return stringBuilder.toString();
            }
            if (value instanceof Rational[]) {
                Rational[] array = (Rational[]) value;
                for (int i = 0; i < array.length; ++i) {
                    stringBuilder.append(array[i].numerator);
                    stringBuilder.append('/');
                    stringBuilder.append(array[i].denominator);
                    if (i + 1 != array.length) {
                        stringBuilder.append(",");
                    }
                }
                return stringBuilder.toString();
            }
            return null;
        }

        public int size() {
            return IFD_FORMAT_BYTES_PER_FORMAT[format] * numberOfComponents;
        }
    }

    // A class for indicating EXIF tag.
    private static class ExifTag {
        public final int number;
        public final String name;
        public final int primaryFormat;
        public final int secondaryFormat;

        private ExifTag(String name, int number, int format) {
            this.name = name;
            this.number = number;
            this.primaryFormat = format;
            this.secondaryFormat = -1;
        }

        private ExifTag(String name, int number, int primaryFormat, int secondaryFormat) {
            this.name = name;
            this.number = number;
            this.primaryFormat = primaryFormat;
            this.secondaryFormat = secondaryFormat;
        }
    }

    // Primary image IFD TIFF tags (See JEITA CP-3451C Section 4.6.8 Tag Support Levels)
    private static final ExifTag[] IFD_TIFF_TAGS = new ExifTag[] {
            // For below two, see TIFF 6.0 Spec Section 3: Bilevel Images.
            new ExifTag(TAG_NEW_SUBFILE_TYPE, 254, IFD_FORMAT_ULONG),
            new ExifTag(TAG_SUBFILE_TYPE, 255, IFD_FORMAT_ULONG),
            new ExifTag(TAG_IMAGE_WIDTH, 256, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_IMAGE_LENGTH, 257, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_BITS_PER_SAMPLE, 258, IFD_FORMAT_USHORT),
            new ExifTag(TAG_COMPRESSION, 259, IFD_FORMAT_USHORT),
            new ExifTag(TAG_PHOTOMETRIC_INTERPRETATION, 262, IFD_FORMAT_USHORT),
            new ExifTag(TAG_IMAGE_DESCRIPTION, 270, IFD_FORMAT_STRING),
            new ExifTag(TAG_MAKE, 271, IFD_FORMAT_STRING),
            new ExifTag(TAG_MODEL, 272, IFD_FORMAT_STRING),
            new ExifTag(TAG_STRIP_OFFSETS, 273, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_ORIENTATION, 274, IFD_FORMAT_USHORT),
            new ExifTag(TAG_SAMPLES_PER_PIXEL, 277, IFD_FORMAT_USHORT),
            new ExifTag(TAG_ROWS_PER_STRIP, 278, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_STRIP_BYTE_COUNTS, 279, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_X_RESOLUTION, 282, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_Y_RESOLUTION, 283, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_PLANAR_CONFIGURATION, 284, IFD_FORMAT_USHORT),
            new ExifTag(TAG_RESOLUTION_UNIT, 296, IFD_FORMAT_USHORT),
            new ExifTag(TAG_TRANSFER_FUNCTION, 301, IFD_FORMAT_USHORT),
            new ExifTag(TAG_SOFTWARE, 305, IFD_FORMAT_STRING),
            new ExifTag(TAG_DATETIME, 306, IFD_FORMAT_STRING),
            new ExifTag(TAG_ARTIST, 315, IFD_FORMAT_STRING),
            new ExifTag(TAG_WHITE_POINT, 318, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_PRIMARY_CHROMATICITIES, 319, IFD_FORMAT_URATIONAL),
            // See Adobe PageMaker® 6.0 TIFF Technical Notes, Note 1.
            new ExifTag(TAG_SUB_IFD_POINTER, 330, IFD_FORMAT_ULONG),
            new ExifTag(TAG_JPEG_INTERCHANGE_FORMAT, 513, IFD_FORMAT_ULONG),
            new ExifTag(TAG_JPEG_INTERCHANGE_FORMAT_LENGTH, 514, IFD_FORMAT_ULONG),
            new ExifTag(TAG_Y_CB_CR_COEFFICIENTS, 529, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_Y_CB_CR_SUB_SAMPLING, 530, IFD_FORMAT_USHORT),
            new ExifTag(TAG_Y_CB_CR_POSITIONING, 531, IFD_FORMAT_USHORT),
            new ExifTag(TAG_REFERENCE_BLACK_WHITE, 532, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_COPYRIGHT, 33432, IFD_FORMAT_STRING),
            new ExifTag(TAG_EXIF_IFD_POINTER, 34665, IFD_FORMAT_ULONG),
            new ExifTag(TAG_GPS_INFO_IFD_POINTER, 34853, IFD_FORMAT_ULONG),
            // RW2 file tags
            // See http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/PanasonicRaw.html)
            new ExifTag(TAG_RW2_SENSOR_TOP_BORDER, 4, IFD_FORMAT_ULONG),
            new ExifTag(TAG_RW2_SENSOR_LEFT_BORDER, 5, IFD_FORMAT_ULONG),
            new ExifTag(TAG_RW2_SENSOR_BOTTOM_BORDER, 6, IFD_FORMAT_ULONG),
            new ExifTag(TAG_RW2_SENSOR_RIGHT_BORDER, 7, IFD_FORMAT_ULONG),
            new ExifTag(TAG_RW2_ISO, 23, IFD_FORMAT_USHORT),
            new ExifTag(TAG_RW2_JPG_FROM_RAW, 46, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_XMP, 700, IFD_FORMAT_BYTE),
    };

    // Primary image IFD Exif Private tags (See JEITA CP-3451C Section 4.6.8 Tag Support Levels)
    private static final ExifTag[] IFD_EXIF_TAGS = new ExifTag[] {
            new ExifTag(TAG_EXPOSURE_TIME, 33434, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_F_NUMBER, 33437, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_EXPOSURE_PROGRAM, 34850, IFD_FORMAT_USHORT),
            new ExifTag(TAG_SPECTRAL_SENSITIVITY, 34852, IFD_FORMAT_STRING),
            new ExifTag(TAG_ISO_SPEED_RATINGS, 34855, IFD_FORMAT_USHORT),
            new ExifTag(TAG_OECF, 34856, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_EXIF_VERSION, 36864, IFD_FORMAT_STRING),
            new ExifTag(TAG_DATETIME_ORIGINAL, 36867, IFD_FORMAT_STRING),
            new ExifTag(TAG_DATETIME_DIGITIZED, 36868, IFD_FORMAT_STRING),
            new ExifTag(TAG_OFFSET_TIME, 36880, IFD_FORMAT_STRING),
            new ExifTag(TAG_OFFSET_TIME_ORIGINAL, 36881, IFD_FORMAT_STRING),
            new ExifTag(TAG_OFFSET_TIME_DIGITIZED, 36882, IFD_FORMAT_STRING),
            new ExifTag(TAG_COMPONENTS_CONFIGURATION, 37121, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_COMPRESSED_BITS_PER_PIXEL, 37122, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_SHUTTER_SPEED_VALUE, 37377, IFD_FORMAT_SRATIONAL),
            new ExifTag(TAG_APERTURE_VALUE, 37378, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_BRIGHTNESS_VALUE, 37379, IFD_FORMAT_SRATIONAL),
            new ExifTag(TAG_EXPOSURE_BIAS_VALUE, 37380, IFD_FORMAT_SRATIONAL),
            new ExifTag(TAG_MAX_APERTURE_VALUE, 37381, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_SUBJECT_DISTANCE, 37382, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_METERING_MODE, 37383, IFD_FORMAT_USHORT),
            new ExifTag(TAG_LIGHT_SOURCE, 37384, IFD_FORMAT_USHORT),
            new ExifTag(TAG_FLASH, 37385, IFD_FORMAT_USHORT),
            new ExifTag(TAG_FOCAL_LENGTH, 37386, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_SUBJECT_AREA, 37396, IFD_FORMAT_USHORT),
            new ExifTag(TAG_MAKER_NOTE, 37500, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_USER_COMMENT, 37510, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_SUBSEC_TIME, 37520, IFD_FORMAT_STRING),
            new ExifTag(TAG_SUBSEC_TIME_ORIG, 37521, IFD_FORMAT_STRING),
            new ExifTag(TAG_SUBSEC_TIME_DIG, 37522, IFD_FORMAT_STRING),
            new ExifTag(TAG_FLASHPIX_VERSION, 40960, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_COLOR_SPACE, 40961, IFD_FORMAT_USHORT),
            new ExifTag(TAG_PIXEL_X_DIMENSION, 40962, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_PIXEL_Y_DIMENSION, 40963, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_RELATED_SOUND_FILE, 40964, IFD_FORMAT_STRING),
            new ExifTag(TAG_INTEROPERABILITY_IFD_POINTER, 40965, IFD_FORMAT_ULONG),
            new ExifTag(TAG_FLASH_ENERGY, 41483, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_SPATIAL_FREQUENCY_RESPONSE, 41484, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_FOCAL_PLANE_X_RESOLUTION, 41486, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_FOCAL_PLANE_Y_RESOLUTION, 41487, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_FOCAL_PLANE_RESOLUTION_UNIT, 41488, IFD_FORMAT_USHORT),
            new ExifTag(TAG_SUBJECT_LOCATION, 41492, IFD_FORMAT_USHORT),
            new ExifTag(TAG_EXPOSURE_INDEX, 41493, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_SENSING_METHOD, 41495, IFD_FORMAT_USHORT),
            new ExifTag(TAG_FILE_SOURCE, 41728, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_SCENE_TYPE, 41729, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_CFA_PATTERN, 41730, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_CUSTOM_RENDERED, 41985, IFD_FORMAT_USHORT),
            new ExifTag(TAG_EXPOSURE_MODE, 41986, IFD_FORMAT_USHORT),
            new ExifTag(TAG_WHITE_BALANCE, 41987, IFD_FORMAT_USHORT),
            new ExifTag(TAG_DIGITAL_ZOOM_RATIO, 41988, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_FOCAL_LENGTH_IN_35MM_FILM, 41989, IFD_FORMAT_USHORT),
            new ExifTag(TAG_SCENE_CAPTURE_TYPE, 41990, IFD_FORMAT_USHORT),
            new ExifTag(TAG_GAIN_CONTROL, 41991, IFD_FORMAT_USHORT),
            new ExifTag(TAG_CONTRAST, 41992, IFD_FORMAT_USHORT),
            new ExifTag(TAG_SATURATION, 41993, IFD_FORMAT_USHORT),
            new ExifTag(TAG_SHARPNESS, 41994, IFD_FORMAT_USHORT),
            new ExifTag(TAG_DEVICE_SETTING_DESCRIPTION, 41995, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_SUBJECT_DISTANCE_RANGE, 41996, IFD_FORMAT_USHORT),
            new ExifTag(TAG_IMAGE_UNIQUE_ID, 42016, IFD_FORMAT_STRING),
            new ExifTag(TAG_DNG_VERSION, 50706, IFD_FORMAT_BYTE),
            new ExifTag(TAG_DEFAULT_CROP_SIZE, 50720, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG)
    };

    // Primary image IFD GPS Info tags (See JEITA CP-3451C Section 4.6.8 Tag Support Levels)
    private static final ExifTag[] IFD_GPS_TAGS = new ExifTag[] {
            new ExifTag(TAG_GPS_VERSION_ID, 0, IFD_FORMAT_BYTE),
            new ExifTag(TAG_GPS_LATITUDE_REF, 1, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_LATITUDE, 2, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_LONGITUDE_REF, 3, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_LONGITUDE, 4, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_ALTITUDE_REF, 5, IFD_FORMAT_BYTE),
            new ExifTag(TAG_GPS_ALTITUDE, 6, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_TIMESTAMP, 7, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_SATELLITES, 8, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_STATUS, 9, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_MEASURE_MODE, 10, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_DOP, 11, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_SPEED_REF, 12, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_SPEED, 13, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_TRACK_REF, 14, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_TRACK, 15, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_IMG_DIRECTION_REF, 16, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_IMG_DIRECTION, 17, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_MAP_DATUM, 18, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_DEST_LATITUDE_REF, 19, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_DEST_LATITUDE, 20, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_DEST_LONGITUDE_REF, 21, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_DEST_LONGITUDE, 22, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_DEST_BEARING_REF, 23, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_DEST_BEARING, 24, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_DEST_DISTANCE_REF, 25, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_DEST_DISTANCE, 26, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_GPS_PROCESSING_METHOD, 27, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_GPS_AREA_INFORMATION, 28, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_GPS_DATESTAMP, 29, IFD_FORMAT_STRING),
            new ExifTag(TAG_GPS_DIFFERENTIAL, 30, IFD_FORMAT_USHORT)
    };
    // Primary image IFD Interoperability tag (See JEITA CP-3451C Section 4.6.8 Tag Support Levels)
    private static final ExifTag[] IFD_INTEROPERABILITY_TAGS = new ExifTag[] {
            new ExifTag(TAG_INTEROPERABILITY_INDEX, 1, IFD_FORMAT_STRING)
    };
    // IFD Thumbnail tags (See JEITA CP-3451C Section 4.6.8 Tag Support Levels)
    private static final ExifTag[] IFD_THUMBNAIL_TAGS = new ExifTag[] {
            // For below two, see TIFF 6.0 Spec Section 3: Bilevel Images.
            new ExifTag(TAG_NEW_SUBFILE_TYPE, 254, IFD_FORMAT_ULONG),
            new ExifTag(TAG_SUBFILE_TYPE, 255, IFD_FORMAT_ULONG),
            new ExifTag(TAG_THUMBNAIL_IMAGE_WIDTH, 256, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_THUMBNAIL_IMAGE_LENGTH, 257, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_BITS_PER_SAMPLE, 258, IFD_FORMAT_USHORT),
            new ExifTag(TAG_COMPRESSION, 259, IFD_FORMAT_USHORT),
            new ExifTag(TAG_PHOTOMETRIC_INTERPRETATION, 262, IFD_FORMAT_USHORT),
            new ExifTag(TAG_IMAGE_DESCRIPTION, 270, IFD_FORMAT_STRING),
            new ExifTag(TAG_MAKE, 271, IFD_FORMAT_STRING),
            new ExifTag(TAG_MODEL, 272, IFD_FORMAT_STRING),
            new ExifTag(TAG_STRIP_OFFSETS, 273, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_THUMBNAIL_ORIENTATION, 274, IFD_FORMAT_USHORT),
            new ExifTag(TAG_SAMPLES_PER_PIXEL, 277, IFD_FORMAT_USHORT),
            new ExifTag(TAG_ROWS_PER_STRIP, 278, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_STRIP_BYTE_COUNTS, 279, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG),
            new ExifTag(TAG_X_RESOLUTION, 282, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_Y_RESOLUTION, 283, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_PLANAR_CONFIGURATION, 284, IFD_FORMAT_USHORT),
            new ExifTag(TAG_RESOLUTION_UNIT, 296, IFD_FORMAT_USHORT),
            new ExifTag(TAG_TRANSFER_FUNCTION, 301, IFD_FORMAT_USHORT),
            new ExifTag(TAG_SOFTWARE, 305, IFD_FORMAT_STRING),
            new ExifTag(TAG_DATETIME, 306, IFD_FORMAT_STRING),
            new ExifTag(TAG_ARTIST, 315, IFD_FORMAT_STRING),
            new ExifTag(TAG_WHITE_POINT, 318, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_PRIMARY_CHROMATICITIES, 319, IFD_FORMAT_URATIONAL),
            // See Adobe PageMaker® 6.0 TIFF Technical Notes, Note 1.
            new ExifTag(TAG_SUB_IFD_POINTER, 330, IFD_FORMAT_ULONG),
            new ExifTag(TAG_JPEG_INTERCHANGE_FORMAT, 513, IFD_FORMAT_ULONG),
            new ExifTag(TAG_JPEG_INTERCHANGE_FORMAT_LENGTH, 514, IFD_FORMAT_ULONG),
            new ExifTag(TAG_Y_CB_CR_COEFFICIENTS, 529, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_Y_CB_CR_SUB_SAMPLING, 530, IFD_FORMAT_USHORT),
            new ExifTag(TAG_Y_CB_CR_POSITIONING, 531, IFD_FORMAT_USHORT),
            new ExifTag(TAG_REFERENCE_BLACK_WHITE, 532, IFD_FORMAT_URATIONAL),
            new ExifTag(TAG_XMP, 700, IFD_FORMAT_BYTE),
            new ExifTag(TAG_COPYRIGHT, 33432, IFD_FORMAT_STRING),
            new ExifTag(TAG_EXIF_IFD_POINTER, 34665, IFD_FORMAT_ULONG),
            new ExifTag(TAG_GPS_INFO_IFD_POINTER, 34853, IFD_FORMAT_ULONG),
            new ExifTag(TAG_DNG_VERSION, 50706, IFD_FORMAT_BYTE),
            new ExifTag(TAG_DEFAULT_CROP_SIZE, 50720, IFD_FORMAT_USHORT, IFD_FORMAT_ULONG)
    };

    // RAF file tag (See piex.cc line 372)
    private static final ExifTag TAG_RAF_IMAGE_SIZE =
            new ExifTag(TAG_STRIP_OFFSETS, 273, IFD_FORMAT_USHORT);

    // ORF file tags (See http://www.exiv2.org/tags-olympus.html)
    private static final ExifTag[] ORF_MAKER_NOTE_TAGS = new ExifTag[] {
            new ExifTag(TAG_ORF_THUMBNAIL_IMAGE, 256, IFD_FORMAT_UNDEFINED),
            new ExifTag(TAG_ORF_CAMERA_SETTINGS_IFD_POINTER, 8224, IFD_FORMAT_ULONG),
            new ExifTag(TAG_ORF_IMAGE_PROCESSING_IFD_POINTER, 8256, IFD_FORMAT_ULONG)
    };
    private static final ExifTag[] ORF_CAMERA_SETTINGS_TAGS = new ExifTag[] {
            new ExifTag(TAG_ORF_PREVIEW_IMAGE_START, 257, IFD_FORMAT_ULONG),
            new ExifTag(TAG_ORF_PREVIEW_IMAGE_LENGTH, 258, IFD_FORMAT_ULONG)
    };
    private static final ExifTag[] ORF_IMAGE_PROCESSING_TAGS = new ExifTag[] {
            new ExifTag(TAG_ORF_ASPECT_FRAME, 4371, IFD_FORMAT_USHORT)
    };
    // PEF file tag (See http://www.sno.phy.queensu.ca/~phil/exiftool/TagNames/Pentax.html)
    private static final ExifTag[] PEF_TAGS = new ExifTag[] {
            new ExifTag(TAG_COLOR_SPACE, 55, IFD_FORMAT_USHORT)
    };

    // See JEITA CP-3451C Section 4.6.3: Exif-specific IFD.
    // The following values are used for indicating pointers to the other Image File Directories.

    // Indices of Exif Ifd tag groups
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({IFD_TYPE_PRIMARY, IFD_TYPE_EXIF, IFD_TYPE_GPS, IFD_TYPE_INTEROPERABILITY,
            IFD_TYPE_THUMBNAIL, IFD_TYPE_PREVIEW, IFD_TYPE_ORF_MAKER_NOTE,
            IFD_TYPE_ORF_CAMERA_SETTINGS, IFD_TYPE_ORF_IMAGE_PROCESSING, IFD_TYPE_PEF})
    public @interface IfdType {}

    private static final int IFD_TYPE_PRIMARY = 0;
    private static final int IFD_TYPE_EXIF = 1;
    private static final int IFD_TYPE_GPS = 2;
    private static final int IFD_TYPE_INTEROPERABILITY = 3;
    private static final int IFD_TYPE_THUMBNAIL = 4;
    private static final int IFD_TYPE_PREVIEW = 5;
    private static final int IFD_TYPE_ORF_MAKER_NOTE = 6;
    private static final int IFD_TYPE_ORF_CAMERA_SETTINGS = 7;
    private static final int IFD_TYPE_ORF_IMAGE_PROCESSING = 8;
    private static final int IFD_TYPE_PEF = 9;

    // List of Exif tag groups
    private static final ExifTag[][] EXIF_TAGS = new ExifTag[][] {
            IFD_TIFF_TAGS, IFD_EXIF_TAGS, IFD_GPS_TAGS, IFD_INTEROPERABILITY_TAGS,
            IFD_THUMBNAIL_TAGS, IFD_TIFF_TAGS, ORF_MAKER_NOTE_TAGS, ORF_CAMERA_SETTINGS_TAGS,
            ORF_IMAGE_PROCESSING_TAGS, PEF_TAGS
    };
    // List of tags for pointing to the other image file directory offset.
    private static final ExifTag[] EXIF_POINTER_TAGS = new ExifTag[] {
            new ExifTag(TAG_SUB_IFD_POINTER, 330, IFD_FORMAT_ULONG),
            new ExifTag(TAG_EXIF_IFD_POINTER, 34665, IFD_FORMAT_ULONG),
            new ExifTag(TAG_GPS_INFO_IFD_POINTER, 34853, IFD_FORMAT_ULONG),
            new ExifTag(TAG_INTEROPERABILITY_IFD_POINTER, 40965, IFD_FORMAT_ULONG),
            new ExifTag(TAG_ORF_CAMERA_SETTINGS_IFD_POINTER, 8224, IFD_FORMAT_BYTE),
            new ExifTag(TAG_ORF_IMAGE_PROCESSING_IFD_POINTER, 8256, IFD_FORMAT_BYTE)
    };

    // Mappings from tag number to tag name and each item represents one IFD tag group.
    private static final HashMap[] sExifTagMapsForReading = new HashMap[EXIF_TAGS.length];
    // Mappings from tag name to tag number and each item represents one IFD tag group.
    private static final HashMap[] sExifTagMapsForWriting = new HashMap[EXIF_TAGS.length];
    private static final HashSet<String> sTagSetForCompatibility = new HashSet<>(Arrays.asList(
            TAG_F_NUMBER, TAG_DIGITAL_ZOOM_RATIO, TAG_EXPOSURE_TIME, TAG_SUBJECT_DISTANCE,
            TAG_GPS_TIMESTAMP));
    // Mappings from tag number to IFD type for pointer tags.
    private static final HashMap<Integer, Integer> sExifPointerTagMap = new HashMap();

    // See JPEG File Interchange Format Version 1.02.
    // The following values are defined for handling JPEG streams. In this implementation, we are
    // not only getting information from EXIF but also from some JPEG special segments such as
    // MARKER_COM for user comment and MARKER_SOFx for image width and height.

    private static final Charset ASCII = Charset.forName("US-ASCII");
    // Identifier for EXIF APP1 segment in JPEG
    private static final byte[] IDENTIFIER_EXIF_APP1 = "Exif\0\0".getBytes(ASCII);
    // Identifier for XMP APP1 segment in JPEG
    private static final byte[] IDENTIFIER_XMP_APP1 = "http://ns.adobe.com/xap/1.0/\0".getBytes(ASCII);
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

    // Supported Image File Types
    private static final int IMAGE_TYPE_UNKNOWN = 0;
    private static final int IMAGE_TYPE_ARW = 1;
    private static final int IMAGE_TYPE_CR2 = 2;
    private static final int IMAGE_TYPE_DNG = 3;
    private static final int IMAGE_TYPE_JPEG = 4;
    private static final int IMAGE_TYPE_NEF = 5;
    private static final int IMAGE_TYPE_NRW = 6;
    private static final int IMAGE_TYPE_ORF = 7;
    private static final int IMAGE_TYPE_PEF = 8;
    private static final int IMAGE_TYPE_RAF = 9;
    private static final int IMAGE_TYPE_RW2 = 10;
    private static final int IMAGE_TYPE_SRW = 11;
    private static final int IMAGE_TYPE_HEIF = 12;
    private static final int IMAGE_TYPE_PNG = 13;
    private static final int IMAGE_TYPE_WEBP = 14;

    static {
        sFormatter = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
        sFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        sFormatterTz = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss XXX", Locale.US);
        sFormatterTz.setTimeZone(TimeZone.getTimeZone("UTC"));

        // Build up the hash tables to look up Exif tags for reading Exif tags.
        for (int ifdType = 0; ifdType < EXIF_TAGS.length; ++ifdType) {
            sExifTagMapsForReading[ifdType] = new HashMap();
            sExifTagMapsForWriting[ifdType] = new HashMap();
            for (ExifTag tag : EXIF_TAGS[ifdType]) {
                sExifTagMapsForReading[ifdType].put(tag.number, tag);
                sExifTagMapsForWriting[ifdType].put(tag.name, tag);
            }
        }

        // Build up the hash table to look up Exif pointer tags.
        sExifPointerTagMap.put(EXIF_POINTER_TAGS[0].number, IFD_TYPE_PREVIEW); // 330
        sExifPointerTagMap.put(EXIF_POINTER_TAGS[1].number, IFD_TYPE_EXIF); // 34665
        sExifPointerTagMap.put(EXIF_POINTER_TAGS[2].number, IFD_TYPE_GPS); // 34853
        sExifPointerTagMap.put(EXIF_POINTER_TAGS[3].number, IFD_TYPE_INTEROPERABILITY); // 40965
        sExifPointerTagMap.put(EXIF_POINTER_TAGS[4].number, IFD_TYPE_ORF_CAMERA_SETTINGS); // 8224
        sExifPointerTagMap.put(EXIF_POINTER_TAGS[5].number, IFD_TYPE_ORF_IMAGE_PROCESSING); // 8256
    }

    private String mFilename;
    private FileDescriptor mSeekableFileDescriptor;
    private AssetManager.AssetInputStream mAssetInputStream;
    private boolean mIsInputStream;
    private int mMimeType;
    private boolean mIsExifDataOnly;
    @UnsupportedAppUsage(publicAlternatives = "Use {@link #getAttribute(java.lang.String)} "
            + "instead.")
    private final HashMap[] mAttributes = new HashMap[EXIF_TAGS.length];
    private Set<Integer> mHandledIfdOffsets = new HashSet<>(EXIF_TAGS.length);
    private ByteOrder mExifByteOrder = ByteOrder.BIG_ENDIAN;
    private boolean mHasThumbnail;
    private boolean mHasThumbnailStrips;
    private boolean mAreThumbnailStripsConsecutive;
    // Used to indicate the position of the thumbnail (includes offset to EXIF data segment).
    private int mThumbnailOffset;
    private int mThumbnailLength;
    private byte[] mThumbnailBytes;
    private int mThumbnailCompression;
    private int mExifOffset;
    private int mOrfMakerNoteOffset;
    private int mOrfThumbnailOffset;
    private int mOrfThumbnailLength;
    private int mRw2JpgFromRawOffset;
    private boolean mIsSupportedFile;
    private boolean mModified;
    // XMP data can be contained as either part of the EXIF data (tag number 700), or as a
    // separate data marker (a separate MARKER_APP1).
    private boolean mXmpIsFromSeparateMarker;

    // Pattern to check non zero timestamp
    private static final Pattern sNonZeroTimePattern = Pattern.compile(".*[1-9].*");
    // Pattern to check gps timestamp
    private static final Pattern sGpsTimestampPattern =
            Pattern.compile("^([0-9][0-9]):([0-9][0-9]):([0-9][0-9])$");

    /**
     * Reads Exif tags from the specified image file.
     *
     * @param file the file of the image data
     * @throws NullPointerException if file is null
     * @throws IOException if an I/O error occurs while retrieving file descriptor via
     *         {@link FileInputStream#getFD()}.
     */
    public ExifInterface(@NonNull File file) throws IOException {
        if (file == null) {
            throw new NullPointerException("file cannot be null");
        }
        initForFilename(file.getAbsolutePath());
    }

    /**
     * Reads Exif tags from the specified image file.
     *
     * @param filename the name of the file of the image data
     * @throws NullPointerException if file name is null
     * @throws IOException if an I/O error occurs while retrieving file descriptor via
     *         {@link FileInputStream#getFD()}.
     */
    public ExifInterface(@NonNull String filename) throws IOException {
        if (filename == null) {
            throw new NullPointerException("filename cannot be null");
        }
        initForFilename(filename);
    }

    /**
     * Reads Exif tags from the specified image file descriptor. Attribute mutation is supported
     * for writable and seekable file descriptors only. This constructor will not rewind the offset
     * of the given file descriptor. Developers should close the file descriptor after use.
     *
     * @param fileDescriptor the file descriptor of the image data
     * @throws NullPointerException if file descriptor is null
     * @throws IOException if an error occurs while duplicating the file descriptor via
     *         {@link Os#dup(FileDescriptor)}.
     */
    public ExifInterface(@NonNull FileDescriptor fileDescriptor) throws IOException {
        if (fileDescriptor == null) {
            throw new NullPointerException("fileDescriptor cannot be null");
        }
        // If a file descriptor has a modern file descriptor, this means that the file can be
        // transcoded and not using the modern file descriptor will trigger the transcoding
        // operation. Thus, to avoid unnecessary transcoding, need to convert to modern file
        // descriptor if it exists. As of Android S, transcoding is not supported for image files,
        // so this is for protecting against non-image files sent to ExifInterface, but support may
        // be added in the future.
        ParcelFileDescriptor modernFd = FileUtils.convertToModernFd(fileDescriptor);
        if (modernFd != null) {
            fileDescriptor = modernFd.getFileDescriptor();
        }

        mAssetInputStream = null;
        mFilename = null;

        boolean isFdDuped = false;
        // Can't save attributes to files with transcoding because apps get a different copy of
        // that file when they're not using it through framework libraries like ExifInterface.
        if (isSeekableFD(fileDescriptor) && modernFd == null) {
            mSeekableFileDescriptor = fileDescriptor;
            // Keep the original file descriptor in order to save attributes when it's seekable.
            // Otherwise, just close the given file descriptor after reading it because the save
            // feature won't be working.
            try {
                fileDescriptor = Os.dup(fileDescriptor);
                isFdDuped = true;
            } catch (ErrnoException e) {
                throw e.rethrowAsIOException();
            }
        } else {
            mSeekableFileDescriptor = null;
        }
        mIsInputStream = false;
        FileInputStream in = null;
        try {
            in = new FileInputStream(fileDescriptor);
            loadAttributes(in);
        } finally {
            closeQuietly(in);
            if (isFdDuped) {
                closeFileDescriptor(fileDescriptor);
            }
            if (modernFd != null) {
                modernFd.close();
            }
        }
    }

    /**
     * Reads Exif tags from the specified image input stream. Attribute mutation is not supported
     * for input streams. The given input stream will proceed from its current position. Developers
     * should close the input stream after use. This constructor is not intended to be used with an
     * input stream that performs any networking operations.
     *
     * @param inputStream the input stream that contains the image data
     * @throws NullPointerException if the input stream is null
     */
    public ExifInterface(@NonNull InputStream inputStream) throws IOException {
        this(inputStream, false);
    }

    /**
     * Reads Exif tags from the specified image input stream based on the stream type. Attribute
     * mutation is not supported for input streams. The given input stream will proceed from its
     * current position. Developers should close the input stream after use. This constructor is not
     * intended to be used with an input stream that performs any networking operations.
     *
     * @param inputStream the input stream that contains the image data
     * @param streamType the type of input stream
     * @throws NullPointerException if the input stream is null
     * @throws IOException if an I/O error occurs while retrieving file descriptor via
     *         {@link FileInputStream#getFD()}.
     */
    public ExifInterface(@NonNull InputStream inputStream, @ExifStreamType int streamType)
            throws IOException {
        this(inputStream, (streamType == STREAM_TYPE_EXIF_DATA_ONLY) ? true : false);
    }

    private ExifInterface(@NonNull InputStream inputStream, boolean shouldBeExifDataOnly)
            throws IOException {
        if (inputStream == null) {
            throw new NullPointerException("inputStream cannot be null");
        }
        mFilename = null;

        if (shouldBeExifDataOnly) {
            inputStream = new BufferedInputStream(inputStream, SIGNATURE_CHECK_SIZE);
            if (!isExifDataOnly((BufferedInputStream) inputStream)) {
                Log.w(TAG, "Given data does not follow the structure of an Exif-only data.");
                return;
            }
            mIsExifDataOnly = true;
            mAssetInputStream = null;
            mSeekableFileDescriptor = null;
        } else {
            if (inputStream instanceof AssetManager.AssetInputStream) {
                mAssetInputStream = (AssetManager.AssetInputStream) inputStream;
                mSeekableFileDescriptor = null;
            } else if (inputStream instanceof FileInputStream
                    && (isSeekableFD(((FileInputStream) inputStream).getFD()))) {
                mAssetInputStream = null;
                mSeekableFileDescriptor = ((FileInputStream) inputStream).getFD();
            } else {
                mAssetInputStream = null;
                mSeekableFileDescriptor = null;
            }
        }
        loadAttributes(inputStream);
    }

    /**
     * Returns whether ExifInterface currently supports reading data from the specified mime type
     * or not.
     *
     * @param mimeType the string value of mime type
     */
    public static boolean isSupportedMimeType(@NonNull String mimeType) {
        if (mimeType == null) {
            throw new NullPointerException("mimeType shouldn't be null");
        }

        switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg":
            case "image/x-adobe-dng":
            case "image/x-canon-cr2":
            case "image/x-nikon-nef":
            case "image/x-nikon-nrw":
            case "image/x-sony-arw":
            case "image/x-panasonic-rw2":
            case "image/x-olympus-orf":
            case "image/x-pentax-pef":
            case "image/x-samsung-srw":
            case "image/x-fuji-raf":
            case "image/heic":
            case "image/heif":
            case "image/png":
            case "image/webp":
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns the EXIF attribute of the specified tag or {@code null} if there is no such tag in
     * the image file.
     *
     * @param tag the name of the tag.
     */
    private @Nullable ExifAttribute getExifAttribute(@NonNull String tag) {
        if (tag == null) {
            throw new NullPointerException("tag shouldn't be null");
        }
        // Retrieves all tag groups. The value from primary image tag group has a higher priority
        // than the value from the thumbnail tag group if there are more than one candidates.
        for (int i = 0; i < EXIF_TAGS.length; ++i) {
            Object value = mAttributes[i].get(tag);
            if (value != null) {
                return (ExifAttribute) value;
            }
        }
        return null;
    }

    /**
     * Returns the value of the specified tag or {@code null} if there
     * is no such tag in the image file.
     *
     * @param tag the name of the tag.
     */
    public @Nullable String getAttribute(@NonNull String tag) {
        if (tag == null) {
            throw new NullPointerException("tag shouldn't be null");
        }
        ExifAttribute attribute = getExifAttribute(tag);
        if (attribute != null) {
            if (!sTagSetForCompatibility.contains(tag)) {
                return attribute.getStringValue(mExifByteOrder);
            }
            if (tag.equals(TAG_GPS_TIMESTAMP)) {
                // Convert the rational values to the custom formats for backwards compatibility.
                if (attribute.format != IFD_FORMAT_URATIONAL
                        && attribute.format != IFD_FORMAT_SRATIONAL) {
                    return null;
                }
                Rational[] array = (Rational[]) attribute.getValue(mExifByteOrder);
                if (array.length != 3) {
                    return null;
                }
                return String.format("%02d:%02d:%02d",
                        (int) ((float) array[0].numerator / array[0].denominator),
                        (int) ((float) array[1].numerator / array[1].denominator),
                        (int) ((float) array[2].numerator / array[2].denominator));
            }
            try {
                return Double.toString(attribute.getDoubleValue(mExifByteOrder));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Returns the integer value of the specified tag. If there is no such tag
     * in the image file or the value cannot be parsed as integer, return
     * <var>defaultValue</var>.
     *
     * @param tag the name of the tag.
     * @param defaultValue the value to return if the tag is not available.
     */
    public int getAttributeInt(@NonNull String tag, int defaultValue) {
        if (tag == null) {
            throw new NullPointerException("tag shouldn't be null");
        }
        ExifAttribute exifAttribute = getExifAttribute(tag);
        if (exifAttribute == null) {
            return defaultValue;
        }

        try {
            return exifAttribute.getIntValue(mExifByteOrder);
        } catch (NumberFormatException e) {
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
    public double getAttributeDouble(@NonNull String tag, double defaultValue) {
        if (tag == null) {
            throw new NullPointerException("tag shouldn't be null");
        }
        ExifAttribute exifAttribute = getExifAttribute(tag);
        if (exifAttribute == null) {
            return defaultValue;
        }

        try {
            return exifAttribute.getDoubleValue(mExifByteOrder);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Set the value of the specified tag.
     *
     * @param tag the name of the tag.
     * @param value the value of the tag.
     */
    public void setAttribute(@NonNull String tag, @Nullable String value) {
        if (tag == null) {
            throw new NullPointerException("tag shouldn't be null");
        }
        // Convert the given value to rational values for backwards compatibility.
        if (value != null && sTagSetForCompatibility.contains(tag)) {
            if (tag.equals(TAG_GPS_TIMESTAMP)) {
                Matcher m = sGpsTimestampPattern.matcher(value);
                if (!m.find()) {
                    Log.w(TAG, "Invalid value for " + tag + " : " + value);
                    return;
                }
                value = Integer.parseInt(m.group(1)) + "/1," + Integer.parseInt(m.group(2)) + "/1,"
                        + Integer.parseInt(m.group(3)) + "/1";
            } else {
                try {
                    double doubleValue = Double.parseDouble(value);
                    value = (long) (doubleValue * 10000L) + "/10000";
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Invalid value for " + tag + " : " + value);
                    return;
                }
            }
        }

        for (int i = 0 ; i < EXIF_TAGS.length; ++i) {
            if (i == IFD_TYPE_THUMBNAIL && !mHasThumbnail) {
                continue;
            }
            final Object obj = sExifTagMapsForWriting[i].get(tag);
            if (obj != null) {
                if (value == null) {
                    mAttributes[i].remove(tag);
                    continue;
                }
                final ExifTag exifTag = (ExifTag) obj;
                Pair<Integer, Integer> guess = guessDataFormat(value);
                int dataFormat;
                if (exifTag.primaryFormat == guess.first || exifTag.primaryFormat == guess.second) {
                    dataFormat = exifTag.primaryFormat;
                } else if (exifTag.secondaryFormat != -1 && (exifTag.secondaryFormat == guess.first
                        || exifTag.secondaryFormat == guess.second)) {
                    dataFormat = exifTag.secondaryFormat;
                } else if (exifTag.primaryFormat == IFD_FORMAT_BYTE
                        || exifTag.primaryFormat == IFD_FORMAT_UNDEFINED
                        || exifTag.primaryFormat == IFD_FORMAT_STRING) {
                    dataFormat = exifTag.primaryFormat;
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Given tag (" + tag
                                + ") value didn't match with one of expected "
                                + "formats: " + IFD_FORMAT_NAMES[exifTag.primaryFormat]
                                + (exifTag.secondaryFormat == -1 ? "" : ", "
                                + IFD_FORMAT_NAMES[exifTag.secondaryFormat]) + " (guess: "
                                + IFD_FORMAT_NAMES[guess.first] + (guess.second == -1 ? "" : ", "
                                + IFD_FORMAT_NAMES[guess.second]) + ")");
                    }
                    continue;
                }
                switch (dataFormat) {
                    case IFD_FORMAT_BYTE: {
                        mAttributes[i].put(tag, ExifAttribute.createByte(value));
                        break;
                    }
                    case IFD_FORMAT_UNDEFINED:
                    case IFD_FORMAT_STRING: {
                        mAttributes[i].put(tag, ExifAttribute.createString(value));
                        break;
                    }
                    case IFD_FORMAT_USHORT: {
                        final String[] values = value.split(",");
                        final int[] intArray = new int[values.length];
                        for (int j = 0; j < values.length; ++j) {
                            intArray[j] = Integer.parseInt(values[j]);
                        }
                        mAttributes[i].put(tag,
                                ExifAttribute.createUShort(intArray, mExifByteOrder));
                        break;
                    }
                    case IFD_FORMAT_SLONG: {
                        final String[] values = value.split(",");
                        final int[] intArray = new int[values.length];
                        for (int j = 0; j < values.length; ++j) {
                            intArray[j] = Integer.parseInt(values[j]);
                        }
                        mAttributes[i].put(tag,
                                ExifAttribute.createSLong(intArray, mExifByteOrder));
                        break;
                    }
                    case IFD_FORMAT_ULONG: {
                        final String[] values = value.split(",");
                        final long[] longArray = new long[values.length];
                        for (int j = 0; j < values.length; ++j) {
                            longArray[j] = Long.parseLong(values[j]);
                        }
                        mAttributes[i].put(tag,
                                ExifAttribute.createULong(longArray, mExifByteOrder));
                        break;
                    }
                    case IFD_FORMAT_URATIONAL: {
                        final String[] values = value.split(",");
                        final Rational[] rationalArray = new Rational[values.length];
                        for (int j = 0; j < values.length; ++j) {
                            final String[] numbers = values[j].split("/");
                            rationalArray[j] = new Rational((long) Double.parseDouble(numbers[0]),
                                    (long) Double.parseDouble(numbers[1]));
                        }
                        mAttributes[i].put(tag,
                                ExifAttribute.createURational(rationalArray, mExifByteOrder));
                        break;
                    }
                    case IFD_FORMAT_SRATIONAL: {
                        final String[] values = value.split(",");
                        final Rational[] rationalArray = new Rational[values.length];
                        for (int j = 0; j < values.length; ++j) {
                            final String[] numbers = values[j].split("/");
                            rationalArray[j] = new Rational((long) Double.parseDouble(numbers[0]),
                                    (long) Double.parseDouble(numbers[1]));
                        }
                        mAttributes[i].put(tag,
                                ExifAttribute.createSRational(rationalArray, mExifByteOrder));
                        break;
                    }
                    case IFD_FORMAT_DOUBLE: {
                        final String[] values = value.split(",");
                        final double[] doubleArray = new double[values.length];
                        for (int j = 0; j < values.length; ++j) {
                            doubleArray[j] = Double.parseDouble(values[j]);
                        }
                        mAttributes[i].put(tag,
                                ExifAttribute.createDouble(doubleArray, mExifByteOrder));
                        break;
                    }
                    default:
                        if (DEBUG) {
                            Log.d(TAG, "Data format isn't one of expected formats: " + dataFormat);
                        }
                        continue;
                }
            }
        }
    }

    /**
     * Update the values of the tags in the tag groups if any value for the tag already was stored.
     *
     * @param tag the name of the tag.
     * @param value the value of the tag in a form of {@link ExifAttribute}.
     * @return Returns {@code true} if updating is placed.
     */
    private boolean updateAttribute(String tag, ExifAttribute value) {
        boolean updated = false;
        for (int i = 0 ; i < EXIF_TAGS.length; ++i) {
            if (mAttributes[i].containsKey(tag)) {
                mAttributes[i].put(tag, value);
                updated = true;
            }
        }
        return updated;
    }

    /**
     * Remove any values of the specified tag.
     *
     * @param tag the name of the tag.
     */
    private void removeAttribute(String tag) {
        for (int i = 0 ; i < EXIF_TAGS.length; ++i) {
            mAttributes[i].remove(tag);
        }
    }

    /**
     * This function decides which parser to read the image data according to the given input stream
     * type and the content of the input stream.
     */
    private void loadAttributes(@NonNull InputStream in) {
        if (in == null) {
            throw new NullPointerException("inputstream shouldn't be null");
        }
        try {
            // Initialize mAttributes.
            for (int i = 0; i < EXIF_TAGS.length; ++i) {
                mAttributes[i] = new HashMap();
            }

            // Check file type
            if (!mIsExifDataOnly) {
                in = new BufferedInputStream(in, SIGNATURE_CHECK_SIZE);
                mMimeType = getMimeType((BufferedInputStream) in);
            }

            // Create byte-ordered input stream
            ByteOrderedDataInputStream inputStream = new ByteOrderedDataInputStream(in);

            if (!mIsExifDataOnly) {
                switch (mMimeType) {
                    case IMAGE_TYPE_JPEG: {
                        getJpegAttributes(inputStream, 0, IFD_TYPE_PRIMARY); // 0 is offset
                        break;
                    }
                    case IMAGE_TYPE_RAF: {
                        getRafAttributes(inputStream);
                        break;
                    }
                    case IMAGE_TYPE_HEIF: {
                        getHeifAttributes(inputStream);
                        break;
                    }
                    case IMAGE_TYPE_ORF: {
                        getOrfAttributes(inputStream);
                        break;
                    }
                    case IMAGE_TYPE_RW2: {
                        getRw2Attributes(inputStream);
                        break;
                    }
                    case IMAGE_TYPE_PNG: {
                        getPngAttributes(inputStream);
                        break;
                    }
                    case IMAGE_TYPE_WEBP: {
                        getWebpAttributes(inputStream);
                        break;
                    }
                    case IMAGE_TYPE_ARW:
                    case IMAGE_TYPE_CR2:
                    case IMAGE_TYPE_DNG:
                    case IMAGE_TYPE_NEF:
                    case IMAGE_TYPE_NRW:
                    case IMAGE_TYPE_PEF:
                    case IMAGE_TYPE_SRW:
                    case IMAGE_TYPE_UNKNOWN: {
                        getRawAttributes(inputStream);
                        break;
                    }
                    default: {
                        break;
                    }
                }
            } else {
                getStandaloneAttributes(inputStream);
            }
            // Set thumbnail image offset and length
            setThumbnailData(inputStream);
            mIsSupportedFile = true;
        } catch (IOException | OutOfMemoryError e) {
            // Ignore exceptions in order to keep the compatibility with the old versions of
            // ExifInterface.
            mIsSupportedFile = false;
            Log.w(TAG, "Invalid image: ExifInterface got an unsupported image format file"
                    + "(ExifInterface supports JPEG and some RAW image formats only) "
                    + "or a corrupted JPEG file to ExifInterface.", e);
        } finally {
            addDefaultValuesForCompatibility();

            if (DEBUG) {
                printAttributes();
            }
        }
    }

    private static boolean isSeekableFD(FileDescriptor fd) {
        try {
            Os.lseek(fd, 0, OsConstants.SEEK_CUR);
            return true;
        } catch (ErrnoException e) {
            if (DEBUG) {
                Log.d(TAG, "The file descriptor for the given input is not seekable");
            }
            return false;
        }
    }

    // Prints out attributes for debugging.
    private void printAttributes() {
        for (int i = 0; i < mAttributes.length; ++i) {
            Log.d(TAG, "The size of tag group[" + i + "]: " + mAttributes[i].size());
            for (Map.Entry entry : (Set<Map.Entry>) mAttributes[i].entrySet()) {
                final ExifAttribute tagValue = (ExifAttribute) entry.getValue();
                Log.d(TAG, "tagName: " + entry.getKey() + ", tagType: " + tagValue.toString()
                        + ", tagValue: '" + tagValue.getStringValue(mExifByteOrder) + "'");
            }
        }
    }

    /**
     * Save the tag data into the original image file. This is expensive because
     * it involves copying all the data from one file to another and deleting
     * the old file and renaming the other. It's best to use
     * {@link #setAttribute(String,String)} to set all attributes to write and
     * make a single call rather than multiple calls for each attribute.
     * <p>
     * This method is supported for JPEG, PNG, WebP, and DNG files.
     * <p class="note">
     * Note: after calling this method, any attempts to obtain range information
     * from {@link #getAttributeRange(String)} or {@link #getThumbnailRange()}
     * will throw {@link IllegalStateException}, since the offsets may have
     * changed in the newly written file.
     * <p>
     * For WebP format, the Exif data will be stored as an Extended File Format, and it may not be
     * supported for older readers.
     * <p>
     * For PNG format, the Exif data will be stored as an "eXIf" chunk as per
     * "Extensions to the PNG 1.2 Specification, Version 1.5.0".
     */
    public void saveAttributes() throws IOException {
        if (!isSupportedFormatForSavingAttributes()) {
            throw new IOException("ExifInterface only supports saving attributes for JPEG, PNG, "
                    + "WebP, and DNG formats.");
        }
        if (mIsInputStream || (mSeekableFileDescriptor == null && mFilename == null)) {
            throw new IOException(
                    "ExifInterface does not support saving attributes for the current input.");
        }
        if (mHasThumbnail && mHasThumbnailStrips && !mAreThumbnailStripsConsecutive) {
            throw new IOException("ExifInterface does not support saving attributes when the image "
                    + "file has non-consecutive thumbnail strips");
        }

        // Remember the fact that we've changed the file on disk from what was
        // originally parsed, meaning we can't answer range questions
        mModified = true;

        // Keep the thumbnail in memory
        mThumbnailBytes = getThumbnail();

        FileInputStream in = null;
        FileOutputStream out = null;
        File tempFile = null;
        try {
            // Copy the original file to temporary file.
            tempFile = File.createTempFile("temp", "tmp");
            if (mFilename != null) {
                in = new FileInputStream(mFilename);
            } else if (mSeekableFileDescriptor != null) {
                Os.lseek(mSeekableFileDescriptor, 0, OsConstants.SEEK_SET);
                in = new FileInputStream(mSeekableFileDescriptor);
            }
            out = new FileOutputStream(tempFile);
            copy(in, out);
        } catch (Exception e) {
            throw new IOException("Failed to copy original file to temp file", e);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
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
            try (BufferedInputStream bufferedIn = new BufferedInputStream(in);
                 BufferedOutputStream bufferedOut = new BufferedOutputStream(out)) {
                if (mMimeType == IMAGE_TYPE_JPEG) {
                    saveJpegAttributes(bufferedIn, bufferedOut);
                } else if (mMimeType == IMAGE_TYPE_PNG) {
                    savePngAttributes(bufferedIn, bufferedOut);
                } else if (mMimeType == IMAGE_TYPE_WEBP) {
                    saveWebpAttributes(bufferedIn, bufferedOut);
                } else if (mMimeType == IMAGE_TYPE_DNG || mMimeType == IMAGE_TYPE_UNKNOWN) {
                    ByteOrderedDataOutputStream dataOutputStream =
                            new ByteOrderedDataOutputStream(bufferedOut, ByteOrder.BIG_ENDIAN);
                    writeExifSegment(dataOutputStream);
                }
            }
        } catch (Exception e) {
            // Restore original file
            in = new FileInputStream(tempFile);
            if (mFilename != null) {
                out = new FileOutputStream(mFilename);
            } else if (mSeekableFileDescriptor != null) {
                try {
                    Os.lseek(mSeekableFileDescriptor, 0, OsConstants.SEEK_SET);
                } catch (ErrnoException exception) {
                    throw new IOException("Failed to save new file. Original file may be "
                            + "corrupted since error occurred while trying to restore it.",
                            exception);
                }
                out = new FileOutputStream(mSeekableFileDescriptor);
            }
            copy(in, out);
            closeQuietly(in);
            closeQuietly(out);
            throw new IOException("Failed to save new file", e);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
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
     * Returns true if the image file has the given attribute defined.
     *
     * @param tag the name of the tag.
     */
    public boolean hasAttribute(@NonNull String tag) {
        return (getExifAttribute(tag) != null);
    }

    /**
     * Returns the JPEG compressed thumbnail inside the image file, or {@code null} if there is no
     * JPEG compressed thumbnail.
     * The returned data can be decoded using
     * {@link android.graphics.BitmapFactory#decodeByteArray(byte[],int,int)}
     */
    public byte[] getThumbnail() {
        if (mThumbnailCompression == DATA_JPEG || mThumbnailCompression == DATA_JPEG_COMPRESSED) {
            return getThumbnailBytes();
        }
        return null;
    }

    /**
     * Returns the thumbnail bytes inside the image file, regardless of the compression type of the
     * thumbnail image.
     */
    public byte[] getThumbnailBytes() {
        if (!mHasThumbnail) {
            return null;
        }
        if (mThumbnailBytes != null) {
            return mThumbnailBytes;
        }

        // Read the thumbnail.
        InputStream in = null;
        FileDescriptor newFileDescriptor = null;
        try {
            if (mAssetInputStream != null) {
                in = mAssetInputStream;
                if (in.markSupported()) {
                    in.reset();
                } else {
                    Log.d(TAG, "Cannot read thumbnail from inputstream without mark/reset support");
                    return null;
                }
            } else if (mFilename != null) {
                in = new FileInputStream(mFilename);
            } else if (mSeekableFileDescriptor != null) {
                newFileDescriptor = Os.dup(mSeekableFileDescriptor);
                Os.lseek(newFileDescriptor, 0, OsConstants.SEEK_SET);
                in = new FileInputStream(newFileDescriptor);
            }
            if (in == null) {
                // Should not be reached this.
                throw new FileNotFoundException();
            }
            if (in.skip(mThumbnailOffset) != mThumbnailOffset) {
                throw new IOException("Corrupted image");
            }
            // TODO: Need to handle potential OutOfMemoryError
            byte[] buffer = new byte[mThumbnailLength];
            if (in.read(buffer) != mThumbnailLength) {
                throw new IOException("Corrupted image");
            }
            mThumbnailBytes = buffer;
            return buffer;
        } catch (IOException | ErrnoException e) {
            // Couldn't get a thumbnail image.
            Log.d(TAG, "Encountered exception while getting thumbnail", e);
        } finally {
            closeQuietly(in);
            if (newFileDescriptor != null) {
                closeFileDescriptor(newFileDescriptor);
            }
        }
        return null;
    }

    /**
     * Creates and returns a Bitmap object of the thumbnail image based on the byte array and the
     * thumbnail compression value, or {@code null} if the compression type is unsupported.
     */
    public Bitmap getThumbnailBitmap() {
        if (!mHasThumbnail) {
            return null;
        } else if (mThumbnailBytes == null) {
            mThumbnailBytes = getThumbnailBytes();
        }

        if (mThumbnailCompression == DATA_JPEG || mThumbnailCompression == DATA_JPEG_COMPRESSED) {
            return BitmapFactory.decodeByteArray(mThumbnailBytes, 0, mThumbnailLength);
        } else if (mThumbnailCompression == DATA_UNCOMPRESSED) {
            int[] rgbValues = new int[mThumbnailBytes.length / 3];
            byte alpha = (byte) 0xff000000;
            for (int i = 0; i < rgbValues.length; i++) {
                rgbValues[i] = alpha + (mThumbnailBytes[3 * i] << 16)
                        + (mThumbnailBytes[3 * i + 1] << 8) + mThumbnailBytes[3 * i + 2];
            }

            ExifAttribute imageLengthAttribute =
                    (ExifAttribute) mAttributes[IFD_TYPE_THUMBNAIL].get(TAG_THUMBNAIL_IMAGE_LENGTH);
            ExifAttribute imageWidthAttribute =
                    (ExifAttribute) mAttributes[IFD_TYPE_THUMBNAIL].get(TAG_THUMBNAIL_IMAGE_WIDTH);
            if (imageLengthAttribute != null && imageWidthAttribute != null) {
                int imageLength = imageLengthAttribute.getIntValue(mExifByteOrder);
                int imageWidth = imageWidthAttribute.getIntValue(mExifByteOrder);
                return Bitmap.createBitmap(
                        rgbValues, imageWidth, imageLength, Bitmap.Config.ARGB_8888);
            }
        }
        return null;
    }

    /**
     * Returns true if thumbnail image is JPEG Compressed, or false if either thumbnail image does
     * not exist or thumbnail image is uncompressed.
     */
    public boolean isThumbnailCompressed() {
        if (!mHasThumbnail) {
            return false;
        }
        if (mThumbnailCompression == DATA_JPEG || mThumbnailCompression == DATA_JPEG_COMPRESSED) {
            return true;
        }
        return false;
    }

    /**
     * Returns the offset and length of thumbnail inside the image file, or
     * {@code null} if either there is no thumbnail or the thumbnail bytes are stored
     * non-consecutively.
     *
     * @return two-element array, the offset in the first value, and length in
     *         the second, or {@code null} if no thumbnail was found or the thumbnail strips are
     *         not placed consecutively.
     * @throws IllegalStateException if {@link #saveAttributes()} has been
     *             called since the underlying file was initially parsed, since
     *             that means offsets may have changed.
     */
    public @Nullable long[] getThumbnailRange() {
        if (mModified) {
            throw new IllegalStateException(
                    "The underlying file has been modified since being parsed");
        }

        if (mHasThumbnail) {
            if (mHasThumbnailStrips && !mAreThumbnailStripsConsecutive) {
                return null;
            }
            return new long[] { mThumbnailOffset, mThumbnailLength };
        }
        return null;
    }

    /**
     * Returns the offset and length of the requested tag inside the image file,
     * or {@code null} if the tag is not contained.
     *
     * @return two-element array, the offset in the first value, and length in
     *         the second, or {@code null} if no tag was found.
     * @throws IllegalStateException if {@link #saveAttributes()} has been
     *             called since the underlying file was initially parsed, since
     *             that means offsets may have changed.
     */
    public @Nullable long[] getAttributeRange(@NonNull String tag) {
        if (tag == null) {
            throw new NullPointerException("tag shouldn't be null");
        }
        if (mModified) {
            throw new IllegalStateException(
                    "The underlying file has been modified since being parsed");
        }

        final ExifAttribute attribute = getExifAttribute(tag);
        if (attribute != null) {
            return new long[] { attribute.bytesOffset, attribute.bytes.length };
        } else {
            return null;
        }
    }

    /**
     * Returns the raw bytes for the value of the requested tag inside the image
     * file, or {@code null} if the tag is not contained.
     *
     * @return raw bytes for the value of the requested tag, or {@code null} if
     *         no tag was found.
     */
    public @Nullable byte[] getAttributeBytes(@NonNull String tag) {
        if (tag == null) {
            throw new NullPointerException("tag shouldn't be null");
        }
        final ExifAttribute attribute = getExifAttribute(tag);
        if (attribute != null) {
            return attribute.bytes;
        } else {
            return null;
        }
    }

    /**
     * Stores the latitude and longitude value in a float array. The first element is
     * the latitude, and the second element is the longitude. Returns false if the
     * Exif tags are not available.
     */
    public boolean getLatLong(float output[]) {
        String latValue = getAttribute(TAG_GPS_LATITUDE);
        String latRef = getAttribute(TAG_GPS_LATITUDE_REF);
        String lngValue = getAttribute(TAG_GPS_LONGITUDE);
        String lngRef = getAttribute(TAG_GPS_LONGITUDE_REF);

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
     * Returns parsed {@link #TAG_DATETIME} value, or -1 if unavailable or invalid.
     */
    public @CurrentTimeMillisLong long getDateTime() {
        return parseDateTime(getAttribute(TAG_DATETIME),
                getAttribute(TAG_SUBSEC_TIME),
                getAttribute(TAG_OFFSET_TIME));
    }

    /**
     * Returns parsed {@link #TAG_DATETIME_DIGITIZED} value, or -1 if unavailable or invalid.
     */
    public @CurrentTimeMillisLong long getDateTimeDigitized() {
        return parseDateTime(getAttribute(TAG_DATETIME_DIGITIZED),
                getAttribute(TAG_SUBSEC_TIME_DIGITIZED),
                getAttribute(TAG_OFFSET_TIME_DIGITIZED));
    }

    /**
     * Returns parsed {@link #TAG_DATETIME_ORIGINAL} value, or -1 if unavailable or invalid.
     */
    public @CurrentTimeMillisLong long getDateTimeOriginal() {
        return parseDateTime(getAttribute(TAG_DATETIME_ORIGINAL),
                getAttribute(TAG_SUBSEC_TIME_ORIGINAL),
                getAttribute(TAG_OFFSET_TIME_ORIGINAL));
    }

    private static @CurrentTimeMillisLong long parseDateTime(@Nullable String dateTimeString,
            @Nullable String subSecs, @Nullable String offsetString) {
        if (dateTimeString == null
                || !sNonZeroTimePattern.matcher(dateTimeString).matches()) return -1;

        ParsePosition pos = new ParsePosition(0);
        try {
            // The exif field is in local time. Parsing it as if it is UTC will yield time
            // since 1/1/1970 local time
            Date datetime;
            synchronized (sFormatter) {
                datetime = sFormatter.parse(dateTimeString, pos);
            }

            if (offsetString != null) {
                dateTimeString = dateTimeString + " " + offsetString;
                ParsePosition position = new ParsePosition(0);
                synchronized (sFormatterTz) {
                    datetime = sFormatterTz.parse(dateTimeString, position);
                }
            }

            if (datetime == null) return -1;
            long msecs = datetime.getTime();

            if (subSecs != null) {
                try {
                    long sub = Long.parseLong(subSecs);
                    while (sub > 1000) {
                        sub /= 10;
                    }
                    msecs += sub;
                } catch (NumberFormatException e) {
                    // Ignored
                }
            }
            return msecs;
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /**
     * Returns number of milliseconds since Jan. 1, 1970, midnight UTC.
     * Returns -1 if the date time information if not available.
     */
    public long getGpsDateTime() {
        String date = getAttribute(TAG_GPS_DATESTAMP);
        String time = getAttribute(TAG_GPS_TIMESTAMP);
        if (date == null || time == null
                || (!sNonZeroTimePattern.matcher(date).matches()
                && !sNonZeroTimePattern.matcher(time).matches())) {
            return -1;
        }

        String dateTimeString = date + ' ' + time;

        ParsePosition pos = new ParsePosition(0);
        try {
            final Date datetime;
            synchronized (sFormatter) {
                datetime = sFormatter.parse(dateTimeString, pos);
            }
            if (datetime == null) return -1;
            return datetime.getTime();
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return -1;
        }
    }

    /** {@hide} */
    public static float convertRationalLatLonToFloat(String rationalString, String ref) {
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

    private void initForFilename(String filename) throws IOException {
        FileInputStream in = null;
        ParcelFileDescriptor modernFd = null;
        mAssetInputStream = null;
        mFilename = filename;
        mIsInputStream = false;
        try {
            in = new FileInputStream(filename);
            modernFd = FileUtils.convertToModernFd(in.getFD());
            if (modernFd != null) {
                closeQuietly(in);
                in = new FileInputStream(modernFd.getFileDescriptor());
                mSeekableFileDescriptor = null;
            } else if (isSeekableFD(in.getFD())) {
                mSeekableFileDescriptor = in.getFD();
            }
            loadAttributes(in);
        } finally {
            closeQuietly(in);
            if (modernFd != null) {
                modernFd.close();
            }
        }
    }

    // Checks the type of image file
    private int getMimeType(BufferedInputStream in) throws IOException {
        // TODO (b/142218289): Need to handle case where input stream does not support mark
        in.mark(SIGNATURE_CHECK_SIZE);
        byte[] signatureCheckBytes = new byte[SIGNATURE_CHECK_SIZE];
        in.read(signatureCheckBytes);
        in.reset();
        if (isJpegFormat(signatureCheckBytes)) {
            return IMAGE_TYPE_JPEG;
        } else if (isRafFormat(signatureCheckBytes)) {
            return IMAGE_TYPE_RAF;
        } else if (isHeifFormat(signatureCheckBytes)) {
            return IMAGE_TYPE_HEIF;
        } else if (isOrfFormat(signatureCheckBytes)) {
            return IMAGE_TYPE_ORF;
        } else if (isRw2Format(signatureCheckBytes)) {
            return IMAGE_TYPE_RW2;
        } else if (isPngFormat(signatureCheckBytes)) {
            return IMAGE_TYPE_PNG;
        } else if (isWebpFormat(signatureCheckBytes)) {
            return IMAGE_TYPE_WEBP;
        }
        // Certain file formats (PEF) are identified in readImageFileDirectory()
        return IMAGE_TYPE_UNKNOWN;
    }

    /**
     * This method looks at the first 3 bytes to determine if this file is a JPEG file.
     * See http://www.media.mit.edu/pia/Research/deepview/exif.html, "JPEG format and Marker"
     */
    private static boolean isJpegFormat(byte[] signatureCheckBytes) throws IOException {
        for (int i = 0; i < JPEG_SIGNATURE.length; i++) {
            if (signatureCheckBytes[i] != JPEG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * This method looks at the first 15 bytes to determine if this file is a RAF file.
     * There is no official specification for RAF files from Fuji, but there is an online archive of
     * image file specifications:
     * http://fileformats.archiveteam.org/wiki/Fujifilm_RAF
     */
    private boolean isRafFormat(byte[] signatureCheckBytes) throws IOException {
        byte[] rafSignatureBytes = RAF_SIGNATURE.getBytes();
        for (int i = 0; i < rafSignatureBytes.length; i++) {
            if (signatureCheckBytes[i] != rafSignatureBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isHeifFormat(byte[] signatureCheckBytes) throws IOException {
        ByteOrderedDataInputStream signatureInputStream = null;
        try {
            signatureInputStream = new ByteOrderedDataInputStream(signatureCheckBytes);

            long chunkSize = signatureInputStream.readInt();
            byte[] chunkType = new byte[4];
            signatureInputStream.read(chunkType);

            if (!Arrays.equals(chunkType, HEIF_TYPE_FTYP)) {
                return false;
            }

            long chunkDataOffset = 8;
            if (chunkSize == 1) {
                // This indicates that the next 8 bytes represent the chunk size,
                // and chunk data comes after that.
                chunkSize = signatureInputStream.readLong();
                if (chunkSize < 16) {
                    // The smallest valid chunk is 16 bytes long in this case.
                    return false;
                }
                chunkDataOffset += 8;
            }

            // only sniff up to signatureCheckBytes.length
            if (chunkSize > signatureCheckBytes.length) {
                chunkSize = signatureCheckBytes.length;
            }

            long chunkDataSize = chunkSize - chunkDataOffset;

            // It should at least have major brand (4-byte) and minor version (4-byte).
            // The rest of the chunk (if any) is a list of (4-byte) compatible brands.
            if (chunkDataSize < 8) {
                return false;
            }

            byte[] brand = new byte[4];
            boolean isMif1 = false;
            boolean isHeic = false;
            boolean isAvif = false;
            for (long i = 0; i < chunkDataSize / 4;  ++i) {
                if (signatureInputStream.read(brand) != brand.length) {
                    return false;
                }
                if (i == 1) {
                    // Skip this index, it refers to the minorVersion, not a brand.
                    continue;
                }
                if (Arrays.equals(brand, HEIF_BRAND_MIF1)) {
                    isMif1 = true;
                } else if (Arrays.equals(brand, HEIF_BRAND_HEIC)) {
                    isHeic = true;
                } else if (Arrays.equals(brand, HEIF_BRAND_AVIF)
                        || Arrays.equals(brand, HEIF_BRAND_AVIS)) {
                    isAvif = true;
                }
                if (isMif1 && (isHeic || isAvif)) {
                    return true;
                }
            }
        } catch (Exception e) {
            if (DEBUG) {
                Log.d(TAG, "Exception parsing HEIF file type box.", e);
            }
        } finally {
            if (signatureInputStream != null) {
                signatureInputStream.close();
                signatureInputStream = null;
            }
        }
        return false;
    }

    /**
     * ORF has a similar structure to TIFF but it contains a different signature at the TIFF Header.
     * This method looks at the 2 bytes following the Byte Order bytes to determine if this file is
     * an ORF file.
     * There is no official specification for ORF files from Olympus, but there is an online archive
     * of image file specifications:
     * http://fileformats.archiveteam.org/wiki/Olympus_ORF
     */
    private boolean isOrfFormat(byte[] signatureCheckBytes) throws IOException {
        ByteOrderedDataInputStream signatureInputStream = null;

        try {
            signatureInputStream = new ByteOrderedDataInputStream(signatureCheckBytes);

            // Read byte order
            mExifByteOrder = readByteOrder(signatureInputStream);
            // Set byte order
            signatureInputStream.setByteOrder(mExifByteOrder);

            short orfSignature = signatureInputStream.readShort();
            return orfSignature == ORF_SIGNATURE_1 || orfSignature == ORF_SIGNATURE_2;
        } catch (Exception e) {
            // Do nothing
        } finally {
            if (signatureInputStream != null) {
                signatureInputStream.close();
            }
        }
        return false;
    }

    /**
     * RW2 is TIFF-based, but stores 0x55 signature byte instead of 0x42 at the header
     * See http://lclevy.free.fr/raw/
     */
    private boolean isRw2Format(byte[] signatureCheckBytes) throws IOException {
        ByteOrderedDataInputStream signatureInputStream = null;

        try {
            signatureInputStream = new ByteOrderedDataInputStream(signatureCheckBytes);

            // Read byte order
            mExifByteOrder = readByteOrder(signatureInputStream);
            // Set byte order
            signatureInputStream.setByteOrder(mExifByteOrder);

            short signatureByte = signatureInputStream.readShort();
            signatureInputStream.close();
            return signatureByte == RW2_SIGNATURE;
        } catch (Exception e) {
            // Do nothing
        } finally {
            if (signatureInputStream != null) {
                signatureInputStream.close();
            }
        }
        return false;
    }

    /**
     * PNG's file signature is first 8 bytes.
     * See PNG (Portable Network Graphics) Specification, Version 1.2, 3.1. PNG file signature
     */
    private boolean isPngFormat(byte[] signatureCheckBytes) throws IOException {
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (signatureCheckBytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * WebP's file signature is composed of 12 bytes:
     *   'RIFF' (4 bytes) + file length value (4 bytes) + 'WEBP' (4 bytes)
     * See https://developers.google.com/speed/webp/docs/riff_container, Section "WebP File Header"
     */
    private boolean isWebpFormat(byte[] signatureCheckBytes) throws IOException {
        for (int i = 0; i < WEBP_SIGNATURE_1.length; i++) {
            if (signatureCheckBytes[i] != WEBP_SIGNATURE_1[i]) {
                return false;
            }
        }
        for (int i = 0; i < WEBP_SIGNATURE_2.length; i++) {
            if (signatureCheckBytes[i + WEBP_SIGNATURE_1.length + WEBP_FILE_SIZE_BYTE_LENGTH]
                    != WEBP_SIGNATURE_2[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExifDataOnly(BufferedInputStream in) throws IOException {
        in.mark(IDENTIFIER_EXIF_APP1.length);
        byte[] signatureCheckBytes = new byte[IDENTIFIER_EXIF_APP1.length];
        in.read(signatureCheckBytes);
        in.reset();
        for (int i = 0; i < IDENTIFIER_EXIF_APP1.length; i++) {
            if (signatureCheckBytes[i] != IDENTIFIER_EXIF_APP1[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Loads EXIF attributes from a JPEG input stream.
     *
     * @param in The input stream that starts with the JPEG data.
     * @param jpegOffset The offset value in input stream for JPEG data.
     * @param imageType The image type from which to retrieve metadata. Use IFD_TYPE_PRIMARY for
     *                   primary image, IFD_TYPE_PREVIEW for preview image, and
     *                   IFD_TYPE_THUMBNAIL for thumbnail image.
     * @throws IOException If the data contains invalid JPEG markers, offsets, or length values.
     */
    private void getJpegAttributes(ByteOrderedDataInputStream in, int jpegOffset, int imageType)
            throws IOException {
        // See JPEG File Interchange Format Specification, "JFIF Specification"
        if (DEBUG) {
            Log.d(TAG, "getJpegAttributes starting with: " + in);
        }

        // JPEG uses Big Endian by default. See https://people.cs.umass.edu/~verts/cs32/endian.html
        in.setByteOrder(ByteOrder.BIG_ENDIAN);

        // Skip to JPEG data
        in.seek(jpegOffset);
        int bytesRead = jpegOffset;

        byte marker;
        if ((marker = in.readByte()) != MARKER) {
            throw new IOException("Invalid marker: " + Integer.toHexString(marker & 0xff));
        }
        ++bytesRead;
        if (in.readByte() != MARKER_SOI) {
            throw new IOException("Invalid marker: " + Integer.toHexString(marker & 0xff));
        }
        ++bytesRead;
        while (true) {
            marker = in.readByte();
            if (marker != MARKER) {
                throw new IOException("Invalid marker:" + Integer.toHexString(marker & 0xff));
            }
            ++bytesRead;
            marker = in.readByte();
            if (DEBUG) {
                Log.d(TAG, "Found JPEG segment indicator: " + Integer.toHexString(marker & 0xff));
            }
            ++bytesRead;

            // EOI indicates the end of an image and in case of SOS, JPEG image stream starts and
            // the image data will terminate right after.
            if (marker == MARKER_EOI || marker == MARKER_SOS) {
                break;
            }
            int length = in.readUnsignedShort() - 2;
            bytesRead += 2;
            if (DEBUG) {
                Log.d(TAG, "JPEG segment: " + Integer.toHexString(marker & 0xff) + " (length: "
                        + (length + 2) + ")");
            }
            if (length < 0) {
                throw new IOException("Invalid length");
            }
            switch (marker) {
                case MARKER_APP1: {
                    final int start = bytesRead;
                    final byte[] bytes = new byte[length];
                    in.readFully(bytes);
                    bytesRead += length;
                    length = 0;

                    if (startsWith(bytes, IDENTIFIER_EXIF_APP1)) {
                        final long offset = start + IDENTIFIER_EXIF_APP1.length;
                        final byte[] value = Arrays.copyOfRange(bytes,
                                IDENTIFIER_EXIF_APP1.length, bytes.length);
                        // Save offset values for handleThumbnailFromJfif() function
                        mExifOffset = (int) offset;
                        readExifSegment(value, imageType);
                    } else if (startsWith(bytes, IDENTIFIER_XMP_APP1)) {
                        // See XMP Specification Part 3: Storage in Files, 1.1.3 JPEG, Table 6
                        final long offset = start + IDENTIFIER_XMP_APP1.length;
                        final byte[] value = Arrays.copyOfRange(bytes,
                                IDENTIFIER_XMP_APP1.length, bytes.length);
                        // TODO: check if ignoring separate XMP data when tag 700 already exists is
                        //  valid.
                        if (getAttribute(TAG_XMP) == null) {
                            mAttributes[IFD_TYPE_PRIMARY].put(TAG_XMP, new ExifAttribute(
                                    IFD_FORMAT_BYTE, value.length, offset, value));
                            mXmpIsFromSeparateMarker = true;
                        }
                    }
                    break;
                }

                case MARKER_COM: {
                    byte[] bytes = new byte[length];
                    if (in.read(bytes) != length) {
                        throw new IOException("Invalid exif");
                    }
                    length = 0;
                    if (getAttribute(TAG_USER_COMMENT) == null) {
                        mAttributes[IFD_TYPE_EXIF].put(TAG_USER_COMMENT, ExifAttribute.createString(
                                new String(bytes, ASCII)));
                    }
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
                    if (in.skipBytes(1) != 1) {
                        throw new IOException("Invalid SOFx");
                    }
                    mAttributes[imageType].put(imageType != IFD_TYPE_THUMBNAIL
                                    ? TAG_IMAGE_LENGTH : TAG_THUMBNAIL_IMAGE_LENGTH,
                            ExifAttribute.createULong(in.readUnsignedShort(), mExifByteOrder));
                    mAttributes[imageType].put(imageType != IFD_TYPE_THUMBNAIL
                                    ? TAG_IMAGE_WIDTH : TAG_THUMBNAIL_IMAGE_WIDTH,
                            ExifAttribute.createULong(in.readUnsignedShort(), mExifByteOrder));
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
            if (in.skipBytes(length) != length) {
                throw new IOException("Invalid JPEG segment");
            }
            bytesRead += length;
        }
        // Restore original byte order
        in.setByteOrder(mExifByteOrder);
    }

    private void getRawAttributes(ByteOrderedDataInputStream in) throws IOException {
        // Parse TIFF Headers. See JEITA CP-3451C Section 4.5.2. Table 1.
        parseTiffHeaders(in, in.available());

        // Read TIFF image file directories. See JEITA CP-3451C Section 4.5.2. Figure 6.
        readImageFileDirectory(in, IFD_TYPE_PRIMARY);

        // Update ImageLength/Width tags for all image data.
        updateImageSizeValues(in, IFD_TYPE_PRIMARY);
        updateImageSizeValues(in, IFD_TYPE_PREVIEW);
        updateImageSizeValues(in, IFD_TYPE_THUMBNAIL);

        // Check if each image data is in valid position.
        validateImages();

        if (mMimeType == IMAGE_TYPE_PEF) {
            // PEF files contain a MakerNote data, which contains the data for ColorSpace tag.
            // See http://lclevy.free.fr/raw/ and piex.cc PefGetPreviewData()
            ExifAttribute makerNoteAttribute =
                    (ExifAttribute) mAttributes[IFD_TYPE_EXIF].get(TAG_MAKER_NOTE);
            if (makerNoteAttribute != null) {
                // Create an ordered DataInputStream for MakerNote
                ByteOrderedDataInputStream makerNoteDataInputStream =
                        new ByteOrderedDataInputStream(makerNoteAttribute.bytes);
                makerNoteDataInputStream.setByteOrder(mExifByteOrder);

                // Seek to MakerNote data
                makerNoteDataInputStream.seek(PEF_MAKER_NOTE_SKIP_SIZE);

                // Read IFD data from MakerNote
                readImageFileDirectory(makerNoteDataInputStream, IFD_TYPE_PEF);

                // Update ColorSpace tag
                ExifAttribute colorSpaceAttribute =
                        (ExifAttribute) mAttributes[IFD_TYPE_PEF].get(TAG_COLOR_SPACE);
                if (colorSpaceAttribute != null) {
                    mAttributes[IFD_TYPE_EXIF].put(TAG_COLOR_SPACE, colorSpaceAttribute);
                }
            }
        }
    }

    /**
     * RAF files contains a JPEG and a CFA data.
     * The JPEG contains two images, a preview and a thumbnail, while the CFA contains a RAW image.
     * This method looks at the first 160 bytes of a RAF file to retrieve the offset and length
     * values for the JPEG and CFA data.
     * Using that data, it parses the JPEG data to retrieve the preview and thumbnail image data,
     * then parses the CFA metadata to retrieve the primary image length/width values.
     * For data format details, see http://fileformats.archiveteam.org/wiki/Fujifilm_RAF
     */
    private void getRafAttributes(ByteOrderedDataInputStream in) throws IOException {
        // Retrieve offset & length values
        in.skipBytes(RAF_OFFSET_TO_JPEG_IMAGE_OFFSET);
        byte[] jpegOffsetBytes = new byte[4];
        byte[] cfaHeaderOffsetBytes = new byte[4];
        in.read(jpegOffsetBytes);
        // Skip JPEG length value since it is not needed
        in.skipBytes(RAF_JPEG_LENGTH_VALUE_SIZE);
        in.read(cfaHeaderOffsetBytes);
        int rafJpegOffset = ByteBuffer.wrap(jpegOffsetBytes).getInt();
        int rafCfaHeaderOffset = ByteBuffer.wrap(cfaHeaderOffsetBytes).getInt();

        // Retrieve JPEG image metadata
        getJpegAttributes(in, rafJpegOffset, IFD_TYPE_PREVIEW);

        // Skip to CFA header offset.
        in.seek(rafCfaHeaderOffset);

        // Retrieve primary image length/width values, if TAG_RAF_IMAGE_SIZE exists
        in.setByteOrder(ByteOrder.BIG_ENDIAN);
        int numberOfDirectoryEntry = in.readInt();
        if (DEBUG) {
            Log.d(TAG, "numberOfDirectoryEntry: " + numberOfDirectoryEntry);
        }
        // CFA stores some metadata about the RAW image. Since CFA uses proprietary tags, can only
        // find and retrieve image size information tags, while skipping others.
        // See piex.cc RafGetDimension()
        for (int i = 0; i < numberOfDirectoryEntry; ++i) {
            int tagNumber = in.readUnsignedShort();
            int numberOfBytes = in.readUnsignedShort();
            if (tagNumber == TAG_RAF_IMAGE_SIZE.number) {
                int imageLength = in.readShort();
                int imageWidth = in.readShort();
                ExifAttribute imageLengthAttribute =
                        ExifAttribute.createUShort(imageLength, mExifByteOrder);
                ExifAttribute imageWidthAttribute =
                        ExifAttribute.createUShort(imageWidth, mExifByteOrder);
                mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_LENGTH, imageLengthAttribute);
                mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_WIDTH, imageWidthAttribute);
                if (DEBUG) {
                    Log.d(TAG, "Updated to length: " + imageLength + ", width: " + imageWidth);
                }
                return;
            }
            in.skipBytes(numberOfBytes);
        }
    }

    private void getHeifAttributes(ByteOrderedDataInputStream in) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(new MediaDataSource() {
                long mPosition;

                @Override
                public void close() throws IOException {}

                @Override
                public int readAt(long position, byte[] buffer, int offset, int size)
                        throws IOException {
                    if (size == 0) {
                        return 0;
                    }
                    if (position < 0) {
                        return -1;
                    }
                    try {
                        if (mPosition != position) {
                            // We don't allow seek to positions after the available bytes,
                            // the input stream won't be able to seek back then.
                            // However, if we hit an exception before (mPosition set to -1),
                            // let it try the seek in hope it might recover.
                            if (mPosition >= 0 && position >= mPosition + in.available()) {
                                return -1;
                            }
                            in.seek(position);
                            mPosition = position;
                        }

                        // If the read will cause us to go over the available bytes,
                        // reduce the size so that we stay in the available range.
                        // Otherwise the input stream may not be able to seek back.
                        if (size > in.available()) {
                            size = in.available();
                        }

                        int bytesRead = in.read(buffer, offset, size);
                        if (bytesRead >= 0) {
                            mPosition += bytesRead;
                            return bytesRead;
                        }
                    } catch (IOException e) {}
                    mPosition = -1; // need to seek on next read
                    return -1;
                }

                @Override
                public long getSize() throws IOException {
                    return -1;
                }
            });

            String exifOffsetStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_EXIF_OFFSET);
            String exifLengthStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_EXIF_LENGTH);
            String hasImage = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_IMAGE);
            String hasVideo = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);

            String width = null;
            String height = null;
            String rotation = null;
            final String METADATA_VALUE_YES = "yes";
            // If the file has both image and video, prefer image info over video info.
            // App querying ExifInterface is most likely using the bitmap path which
            // picks the image first.
            if (METADATA_VALUE_YES.equals(hasImage)) {
                width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH);
                height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT);
                rotation = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_IMAGE_ROTATION);
            } else if (METADATA_VALUE_YES.equals(hasVideo)) {
                width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                rotation = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            }

            if (width != null) {
                mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_WIDTH,
                        ExifAttribute.createUShort(Integer.parseInt(width), mExifByteOrder));
            }

            if (height != null) {
                mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_LENGTH,
                        ExifAttribute.createUShort(Integer.parseInt(height), mExifByteOrder));
            }

            if (rotation != null) {
                int orientation = ExifInterface.ORIENTATION_NORMAL;

                // all rotation angles in CW
                switch (Integer.parseInt(rotation)) {
                    case 90:
                        orientation = ExifInterface.ORIENTATION_ROTATE_90;
                        break;
                    case 180:
                        orientation = ExifInterface.ORIENTATION_ROTATE_180;
                        break;
                    case 270:
                        orientation = ExifInterface.ORIENTATION_ROTATE_270;
                        break;
                }

                mAttributes[IFD_TYPE_PRIMARY].put(TAG_ORIENTATION,
                        ExifAttribute.createUShort(orientation, mExifByteOrder));
            }

            if (exifOffsetStr != null && exifLengthStr != null) {
                int offset = Integer.parseInt(exifOffsetStr);
                int length = Integer.parseInt(exifLengthStr);
                if (length <= 6) {
                    throw new IOException("Invalid exif length");
                }
                in.seek(offset);
                byte[] identifier = new byte[6];
                if (in.read(identifier) != 6) {
                    throw new IOException("Can't read identifier");
                }
                offset += 6;
                length -= 6;
                if (!Arrays.equals(identifier, IDENTIFIER_EXIF_APP1)) {
                    throw new IOException("Invalid identifier");
                }

                // TODO: Need to handle potential OutOfMemoryError
                byte[] bytes = new byte[length];
                if (in.read(bytes) != length) {
                    throw new IOException("Can't read exif");
                }
                // Save offset values for handling thumbnail and attribute offsets.
                mExifOffset = offset;
                readExifSegment(bytes, IFD_TYPE_PRIMARY);
            }

            String xmpOffsetStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_XMP_OFFSET);
            String xmpLengthStr = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_XMP_LENGTH);
            if (xmpOffsetStr != null && xmpLengthStr != null) {
                int offset = Integer.parseInt(xmpOffsetStr);
                int length = Integer.parseInt(xmpLengthStr);
                in.seek(offset);
                byte[] xmpBytes = new byte[length];
                if (in.read(xmpBytes) != length) {
                    throw new IOException("Failed to read XMP from HEIF");
                }
                if (getAttribute(TAG_XMP) == null) {
                    mAttributes[IFD_TYPE_PRIMARY].put(TAG_XMP, new ExifAttribute(
                            IFD_FORMAT_BYTE, xmpBytes.length, offset, xmpBytes));
                }
            }

            if (DEBUG) {
                Log.d(TAG, "Heif meta: " + width + "x" + height + ", rotation " + rotation);
            }
        } finally {
            retriever.release();
        }
    }

    private void getStandaloneAttributes(ByteOrderedDataInputStream in) throws IOException {
        in.skipBytes(IDENTIFIER_EXIF_APP1.length);
        // TODO: Need to handle potential OutOfMemoryError
        byte[] data = new byte[in.available()];
        in.readFully(data);
        // Save offset values for handling thumbnail and attribute offsets.
        mExifOffset = IDENTIFIER_EXIF_APP1.length;
        readExifSegment(data, IFD_TYPE_PRIMARY);
    }

    /**
     * ORF files contains a primary image data and a MakerNote data that contains preview/thumbnail
     * images. Both data takes the form of IFDs and can therefore be read with the
     * readImageFileDirectory() method.
     * This method reads all the necessary data and updates the primary/preview/thumbnail image
     * information according to the GetOlympusPreviewImage() method in piex.cc.
     * For data format details, see the following:
     * http://fileformats.archiveteam.org/wiki/Olympus_ORF
     * https://libopenraw.freedesktop.org/wiki/Olympus_ORF
     */
    private void getOrfAttributes(ByteOrderedDataInputStream in) throws IOException {
        // Retrieve primary image data
        // Other Exif data will be located in the Makernote.
        getRawAttributes(in);

        // Additionally retrieve preview/thumbnail information from MakerNote tag, which contains
        // proprietary tags and therefore does not have offical documentation
        // See GetOlympusPreviewImage() in piex.cc & http://www.exiv2.org/tags-olympus.html
        ExifAttribute makerNoteAttribute =
                (ExifAttribute) mAttributes[IFD_TYPE_EXIF].get(TAG_MAKER_NOTE);
        if (makerNoteAttribute != null) {
            // Create an ordered DataInputStream for MakerNote
            ByteOrderedDataInputStream makerNoteDataInputStream =
                    new ByteOrderedDataInputStream(makerNoteAttribute.bytes);
            makerNoteDataInputStream.setByteOrder(mExifByteOrder);

            // There are two types of headers for Olympus MakerNotes
            // See http://www.exiv2.org/makernote.html#R1
            byte[] makerNoteHeader1Bytes = new byte[ORF_MAKER_NOTE_HEADER_1.length];
            makerNoteDataInputStream.readFully(makerNoteHeader1Bytes);
            makerNoteDataInputStream.seek(0);
            byte[] makerNoteHeader2Bytes = new byte[ORF_MAKER_NOTE_HEADER_2.length];
            makerNoteDataInputStream.readFully(makerNoteHeader2Bytes);
            // Skip the corresponding amount of bytes for each header type
            if (Arrays.equals(makerNoteHeader1Bytes, ORF_MAKER_NOTE_HEADER_1)) {
                makerNoteDataInputStream.seek(ORF_MAKER_NOTE_HEADER_1_SIZE);
            } else if (Arrays.equals(makerNoteHeader2Bytes, ORF_MAKER_NOTE_HEADER_2)) {
                makerNoteDataInputStream.seek(ORF_MAKER_NOTE_HEADER_2_SIZE);
            }

            // Read IFD data from MakerNote
            readImageFileDirectory(makerNoteDataInputStream, IFD_TYPE_ORF_MAKER_NOTE);

            // Retrieve & update preview image offset & length values
            ExifAttribute imageLengthAttribute = (ExifAttribute)
                    mAttributes[IFD_TYPE_ORF_CAMERA_SETTINGS].get(TAG_ORF_PREVIEW_IMAGE_START);
            ExifAttribute bitsPerSampleAttribute = (ExifAttribute)
                    mAttributes[IFD_TYPE_ORF_CAMERA_SETTINGS].get(TAG_ORF_PREVIEW_IMAGE_LENGTH);

            if (imageLengthAttribute != null && bitsPerSampleAttribute != null) {
                mAttributes[IFD_TYPE_PREVIEW].put(TAG_JPEG_INTERCHANGE_FORMAT,
                        imageLengthAttribute);
                mAttributes[IFD_TYPE_PREVIEW].put(TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
                        bitsPerSampleAttribute);
            }

            // TODO: Check this behavior in other ORF files
            // Retrieve primary image length & width values
            // See piex.cc GetOlympusPreviewImage()
            ExifAttribute aspectFrameAttribute = (ExifAttribute)
                    mAttributes[IFD_TYPE_ORF_IMAGE_PROCESSING].get(TAG_ORF_ASPECT_FRAME);
            if (aspectFrameAttribute != null) {
                int[] aspectFrameValues = new int[4];
                aspectFrameValues = (int[]) aspectFrameAttribute.getValue(mExifByteOrder);
                if (aspectFrameValues[2] > aspectFrameValues[0] &&
                        aspectFrameValues[3] > aspectFrameValues[1]) {
                    int primaryImageWidth = aspectFrameValues[2] - aspectFrameValues[0] + 1;
                    int primaryImageLength = aspectFrameValues[3] - aspectFrameValues[1] + 1;
                    // Swap width & length values
                    if (primaryImageWidth < primaryImageLength) {
                        primaryImageWidth += primaryImageLength;
                        primaryImageLength = primaryImageWidth - primaryImageLength;
                        primaryImageWidth -= primaryImageLength;
                    }
                    ExifAttribute primaryImageWidthAttribute =
                            ExifAttribute.createUShort(primaryImageWidth, mExifByteOrder);
                    ExifAttribute primaryImageLengthAttribute =
                            ExifAttribute.createUShort(primaryImageLength, mExifByteOrder);

                    mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_WIDTH, primaryImageWidthAttribute);
                    mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_LENGTH, primaryImageLengthAttribute);
                }
            }
        }
    }

    // RW2 contains the primary image data in IFD0 and the preview and/or thumbnail image data in
    // the JpgFromRaw tag
    // See https://libopenraw.freedesktop.org/wiki/Panasonic_RAW/ and piex.cc Rw2GetPreviewData()
    private void getRw2Attributes(ByteOrderedDataInputStream in) throws IOException {
        // Retrieve primary image data
        getRawAttributes(in);

        // Retrieve preview and/or thumbnail image data
        ExifAttribute jpgFromRawAttribute =
                (ExifAttribute) mAttributes[IFD_TYPE_PRIMARY].get(TAG_RW2_JPG_FROM_RAW);
        if (jpgFromRawAttribute != null) {
            getJpegAttributes(in, mRw2JpgFromRawOffset, IFD_TYPE_PREVIEW);
        }

        // Set ISO tag value if necessary
        ExifAttribute rw2IsoAttribute =
                (ExifAttribute) mAttributes[IFD_TYPE_PRIMARY].get(TAG_RW2_ISO);
        ExifAttribute exifIsoAttribute =
                (ExifAttribute) mAttributes[IFD_TYPE_EXIF].get(TAG_ISO_SPEED_RATINGS);
        if (rw2IsoAttribute != null && exifIsoAttribute == null) {
            // Place this attribute only if it doesn't exist
            mAttributes[IFD_TYPE_EXIF].put(TAG_ISO_SPEED_RATINGS, rw2IsoAttribute);
        }
    }

    // PNG contains the EXIF data as a Special-Purpose Chunk
    private void getPngAttributes(ByteOrderedDataInputStream in) throws IOException {
        if (DEBUG) {
            Log.d(TAG, "getPngAttributes starting with: " + in);
        }

        // PNG uses Big Endian by default.
        // See PNG (Portable Network Graphics) Specification, Version 1.2,
        // 2.1. Integers and byte order
        in.setByteOrder(ByteOrder.BIG_ENDIAN);

        int bytesRead = 0;

        // Skip the signature bytes
        in.skipBytes(PNG_SIGNATURE.length);
        bytesRead += PNG_SIGNATURE.length;

        // Each chunk is made up of four parts:
        //   1) Length: 4-byte unsigned integer indicating the number of bytes in the
        //   Chunk Data field. Excludes Chunk Type and CRC bytes.
        //   2) Chunk Type: 4-byte chunk type code.
        //   3) Chunk Data: The data bytes. Can be zero-length.
        //   4) CRC: 4-byte data calculated on the preceding bytes in the chunk. Always
        //   present.
        // --> 4 (length bytes) + 4 (type bytes) + X (data bytes) + 4 (CRC bytes)
        // See PNG (Portable Network Graphics) Specification, Version 1.2,
        // 3.2. Chunk layout
        try {
            while (true) {
                int length = in.readInt();
                bytesRead += 4;

                byte[] type = new byte[PNG_CHUNK_TYPE_BYTE_LENGTH];
                if (in.read(type) != type.length) {
                    throw new IOException("Encountered invalid length while parsing PNG chunk"
                            + "type");
                }
                bytesRead += PNG_CHUNK_TYPE_BYTE_LENGTH;

                // The first chunk must be the IHDR chunk
                if (bytesRead == 16 && !Arrays.equals(type, PNG_CHUNK_TYPE_IHDR)) {
                    throw new IOException("Encountered invalid PNG file--IHDR chunk should appear"
                            + "as the first chunk");
                }

                if (Arrays.equals(type, PNG_CHUNK_TYPE_IEND)) {
                    // IEND marks the end of the image.
                    break;
                } else if (Arrays.equals(type, PNG_CHUNK_TYPE_EXIF)) {
                    // TODO: Need to handle potential OutOfMemoryError
                    byte[] data = new byte[length];
                    if (in.read(data) != length) {
                        throw new IOException("Failed to read given length for given PNG chunk "
                                + "type: " + byteArrayToHexString(type));
                    }

                    // Compare CRC values for potential data corruption.
                    int dataCrcValue = in.readInt();
                    // Cyclic Redundancy Code used to check for corruption of the data
                    CRC32 crc = new CRC32();
                    crc.update(type);
                    crc.update(data);
                    if ((int) crc.getValue() != dataCrcValue) {
                        throw new IOException("Encountered invalid CRC value for PNG-EXIF chunk."
                                + "\n recorded CRC value: " + dataCrcValue + ", calculated CRC "
                                + "value: " + crc.getValue());
                    }
                    // Save offset values for handleThumbnailFromJfif() function
                    mExifOffset = bytesRead;
                    readExifSegment(data, IFD_TYPE_PRIMARY);

                    validateImages();
                    break;
                } else {
                    // Skip to next chunk
                    in.skipBytes(length + PNG_CHUNK_CRC_BYTE_LENGTH);
                    bytesRead += length + PNG_CHUNK_CRC_BYTE_LENGTH;
                }
            }
        } catch (EOFException e) {
            // Should not reach here. Will only reach here if the file is corrupted or
            // does not follow the PNG specifications
            throw new IOException("Encountered corrupt PNG file.");
        }
    }

    // WebP contains EXIF data as a RIFF File Format Chunk
    // All references below can be found in the following link.
    // https://developers.google.com/speed/webp/docs/riff_container
    private void getWebpAttributes(ByteOrderedDataInputStream in) throws IOException {
        if (DEBUG) {
            Log.d(TAG, "getWebpAttributes starting with: " + in);
        }
        // WebP uses little-endian by default.
        // See Section "Terminology & Basics"
        in.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        in.skipBytes(WEBP_SIGNATURE_1.length);
        // File size corresponds to the size of the entire file from offset 8.
        // See Section "WebP File Header"
        int fileSize = in.readInt() + 8;
        int bytesRead = 8;
        bytesRead += in.skipBytes(WEBP_SIGNATURE_2.length);
        try {
            while (true) {
                // TODO: Check the first Chunk Type, and if it is VP8X, check if the chunks are
                // ordered properly.

                // Each chunk is made up of three parts:
                //   1) Chunk FourCC: 4-byte concatenating four ASCII characters.
                //   2) Chunk Size: 4-byte unsigned integer indicating the size of the chunk.
                //                  Excludes Chunk FourCC and Chunk Size bytes.
                //   3) Chunk Payload: data payload. A single padding byte ('0') is added if
                //                     Chunk Size is odd.
                // See Section "RIFF File Format"
                byte[] code = new byte[WEBP_CHUNK_TYPE_BYTE_LENGTH];
                if (in.read(code) != code.length) {
                    throw new IOException("Encountered invalid length while parsing WebP chunk"
                            + "type");
                }
                bytesRead += 4;
                int chunkSize = in.readInt();
                bytesRead += 4;
                if (Arrays.equals(WEBP_CHUNK_TYPE_EXIF, code)) {
                    // TODO: Need to handle potential OutOfMemoryError
                    byte[] payload = new byte[chunkSize];
                    if (in.read(payload) != chunkSize) {
                        throw new IOException("Failed to read given length for given PNG chunk "
                                + "type: " + byteArrayToHexString(code));
                    }
                    // Save offset values for handling thumbnail and attribute offsets.
                    mExifOffset = bytesRead;
                    readExifSegment(payload, IFD_TYPE_PRIMARY);

                    // Save offset values for handleThumbnailFromJfif() function
                    mExifOffset = bytesRead;
                    break;
                } else {
                    // Add a single padding byte at end if chunk size is odd
                    chunkSize = (chunkSize % 2 == 1) ? chunkSize + 1 : chunkSize;
                    // Check if skipping to next chunk is necessary
                    if (bytesRead + chunkSize == fileSize) {
                        // Reached end of file
                        break;
                    } else if (bytesRead + chunkSize > fileSize) {
                        throw new IOException("Encountered WebP file with invalid chunk size");
                    }
                    // Skip to next chunk
                    int skipped = in.skipBytes(chunkSize);
                    if (skipped != chunkSize) {
                        throw new IOException("Encountered WebP file with invalid chunk size");
                    }
                    bytesRead += skipped;
                }
            }
        } catch (EOFException e) {
            // Should not reach here. Will only reach here if the file is corrupted or
            // does not follow the WebP specifications
            throw new IOException("Encountered corrupt WebP file.");
        }
    }

    // Stores a new JPEG image with EXIF attributes into a given output stream.
    private void saveJpegAttributes(InputStream inputStream, OutputStream outputStream)
            throws IOException {
        // See JPEG File Interchange Format Specification, "JFIF Specification"
        if (DEBUG) {
            Log.d(TAG, "saveJpegAttributes starting with (inputStream: " + inputStream
                    + ", outputStream: " + outputStream + ")");
        }
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        ByteOrderedDataOutputStream dataOutputStream =
                new ByteOrderedDataOutputStream(outputStream, ByteOrder.BIG_ENDIAN);
        if (dataInputStream.readByte() != MARKER) {
            throw new IOException("Invalid marker");
        }
        dataOutputStream.writeByte(MARKER);
        if (dataInputStream.readByte() != MARKER_SOI) {
            throw new IOException("Invalid marker");
        }
        dataOutputStream.writeByte(MARKER_SOI);

        // Remove XMP data if it is from a separate marker (IDENTIFIER_XMP_APP1, not
        // IDENTIFIER_EXIF_APP1)
        // Will re-add it later after the rest of the file is written
        ExifAttribute xmpAttribute = null;
        if (getAttribute(TAG_XMP) != null && mXmpIsFromSeparateMarker) {
            xmpAttribute = (ExifAttribute) mAttributes[IFD_TYPE_PRIMARY].remove(TAG_XMP);
        }

        // Write EXIF APP1 segment
        dataOutputStream.writeByte(MARKER);
        dataOutputStream.writeByte(MARKER_APP1);
        writeExifSegment(dataOutputStream);

        // Re-add previously removed XMP data.
        if (xmpAttribute != null) {
            mAttributes[IFD_TYPE_PRIMARY].put(TAG_XMP, xmpAttribute);
        }

        byte[] bytes = new byte[4096];

        while (true) {
            byte marker = dataInputStream.readByte();
            if (marker != MARKER) {
                throw new IOException("Invalid marker");
            }
            marker = dataInputStream.readByte();
            switch (marker) {
                case MARKER_APP1: {
                    int length = dataInputStream.readUnsignedShort() - 2;
                    if (length < 0) {
                        throw new IOException("Invalid length");
                    }
                    byte[] identifier = new byte[6];
                    if (length >= 6) {
                        if (dataInputStream.read(identifier) != 6) {
                            throw new IOException("Invalid exif");
                        }
                        if (Arrays.equals(identifier, IDENTIFIER_EXIF_APP1)) {
                            // Skip the original EXIF APP1 segment.
                            if (dataInputStream.skipBytes(length - 6) != length - 6) {
                                throw new IOException("Invalid length");
                            }
                            break;
                        }
                    }
                    // Copy non-EXIF APP1 segment.
                    dataOutputStream.writeByte(MARKER);
                    dataOutputStream.writeByte(marker);
                    dataOutputStream.writeUnsignedShort(length + 2);
                    if (length >= 6) {
                        length -= 6;
                        dataOutputStream.write(identifier);
                    }
                    int read;
                    while (length > 0 && (read = dataInputStream.read(
                            bytes, 0, Math.min(length, bytes.length))) >= 0) {
                        dataOutputStream.write(bytes, 0, read);
                        length -= read;
                    }
                    break;
                }
                case MARKER_EOI:
                case MARKER_SOS: {
                    dataOutputStream.writeByte(MARKER);
                    dataOutputStream.writeByte(marker);
                    // Copy all the remaining data
                    copy(dataInputStream, dataOutputStream);
                    return;
                }
                default: {
                    // Copy JPEG segment
                    dataOutputStream.writeByte(MARKER);
                    dataOutputStream.writeByte(marker);
                    int length = dataInputStream.readUnsignedShort();
                    dataOutputStream.writeUnsignedShort(length);
                    length -= 2;
                    if (length < 0) {
                        throw new IOException("Invalid length");
                    }
                    int read;
                    while (length > 0 && (read = dataInputStream.read(
                            bytes, 0, Math.min(length, bytes.length))) >= 0) {
                        dataOutputStream.write(bytes, 0, read);
                        length -= read;
                    }
                    break;
                }
            }
        }
    }

    private void savePngAttributes(InputStream inputStream, OutputStream outputStream)
            throws IOException {
        if (DEBUG) {
            Log.d(TAG, "savePngAttributes starting with (inputStream: " + inputStream
                    + ", outputStream: " + outputStream + ")");
        }
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        ByteOrderedDataOutputStream dataOutputStream =
                new ByteOrderedDataOutputStream(outputStream, ByteOrder.BIG_ENDIAN);
        // Copy PNG signature bytes
        copy(dataInputStream, dataOutputStream, PNG_SIGNATURE.length);
        // EXIF chunk can appear anywhere between the first (IHDR) and last (IEND) chunks, except
        // between IDAT chunks.
        // Adhering to these rules,
        //   1) if EXIF chunk did not exist in the original file, it will be stored right after the
        //      first chunk,
        //   2) if EXIF chunk existed in the original file, it will be stored in the same location.
        if (mExifOffset == 0) {
            // Copy IHDR chunk bytes
            int ihdrChunkLength = dataInputStream.readInt();
            dataOutputStream.writeInt(ihdrChunkLength);
            copy(dataInputStream, dataOutputStream, PNG_CHUNK_TYPE_BYTE_LENGTH
                    + ihdrChunkLength + PNG_CHUNK_CRC_BYTE_LENGTH);
        } else {
            // Copy up until the point where EXIF chunk length information is stored.
            int copyLength = mExifOffset - PNG_SIGNATURE.length
                    - 4 /* PNG EXIF chunk length bytes */
                    - PNG_CHUNK_TYPE_BYTE_LENGTH;
            copy(dataInputStream, dataOutputStream, copyLength);
            // Skip to the start of the chunk after the EXIF chunk
            int exifChunkLength = dataInputStream.readInt();
            dataInputStream.skipBytes(PNG_CHUNK_TYPE_BYTE_LENGTH + exifChunkLength
                    + PNG_CHUNK_CRC_BYTE_LENGTH);
        }
        // Write EXIF data
        try (ByteArrayOutputStream exifByteArrayOutputStream = new ByteArrayOutputStream()) {
            // A byte array is needed to calculate the CRC value of this chunk which requires
            // the chunk type bytes and the chunk data bytes.
            ByteOrderedDataOutputStream exifDataOutputStream =
                    new ByteOrderedDataOutputStream(exifByteArrayOutputStream,
                            ByteOrder.BIG_ENDIAN);
            // Store Exif data in separate byte array
            writeExifSegment(exifDataOutputStream);
            byte[] exifBytes =
                    ((ByteArrayOutputStream) exifDataOutputStream.mOutputStream).toByteArray();
            // Write EXIF chunk data
            dataOutputStream.write(exifBytes);
            // Write EXIF chunk CRC
            CRC32 crc = new CRC32();
            crc.update(exifBytes, 4 /* skip length bytes */, exifBytes.length - 4);
            dataOutputStream.writeInt((int) crc.getValue());
        }
        // Copy the rest of the file
        copy(dataInputStream, dataOutputStream);
    }

    // A WebP file has a header and a series of chunks.
    // The header is composed of:
    //   "RIFF" + File Size + "WEBP"
    //
    // The structure of the chunks can be divided largely into two categories:
    //   1) Contains only image data,
    //   2) Contains image data and extra data.
    // In the first category, there is only one chunk: type "VP8" (compression with loss) or "VP8L"
    // (lossless compression).
    // In the second category, the first chunk will be of type "VP8X", which contains flags
    // indicating which extra data exist in later chunks. The proceeding chunks must conform to
    // the following order based on type (if they exist):
    //   Color Profile ("ICCP") + Animation Control Data ("ANIM") + Image Data ("VP8"/"VP8L")
    //   + Exif metadata ("EXIF") + XMP metadata ("XMP")
    //
    // And in order to have EXIF data, a WebP file must be of the second structure and thus follow
    // the following rules:
    //   1) "VP8X" chunk as the first chunk,
    //   2) flag for EXIF inside "VP8X" chunk set to 1, and
    //   3) contain the "EXIF" chunk in the correct order amongst other chunks.
    //
    // Based on these rules, this API will support three different cases depending on the contents
    // of the original file:
    //   1) "EXIF" chunk already exists
    //     -> replace it with the new "EXIF" chunk
    //   2) "EXIF" chunk does not exist and the first chunk is "VP8" or "VP8L"
    //     -> add "VP8X" before the "VP8"/"VP8L" chunk (with EXIF flag set to 1), and add new
    //     "EXIF" chunk after the "VP8"/"VP8L" chunk.
    //   3) "EXIF" chunk does not exist and the first chunk is "VP8X"
    //     -> set EXIF flag in "VP8X" chunk to 1, and add new "EXIF" chunk at the proper location.
    //
    // See https://developers.google.com/speed/webp/docs/riff_container for more details.
    private void saveWebpAttributes(InputStream inputStream, OutputStream outputStream)
            throws IOException {
        if (DEBUG) {
            Log.d(TAG, "saveWebpAttributes starting with (inputStream: " + inputStream
                    + ", outputStream: " + outputStream + ")");
        }
        ByteOrderedDataInputStream totalInputStream =
                new ByteOrderedDataInputStream(inputStream, ByteOrder.LITTLE_ENDIAN);
        ByteOrderedDataOutputStream totalOutputStream =
                new ByteOrderedDataOutputStream(outputStream, ByteOrder.LITTLE_ENDIAN);

        // WebP signature
        copy(totalInputStream, totalOutputStream, WEBP_SIGNATURE_1.length);
        // File length will be written after all the chunks have been written
        totalInputStream.skipBytes(WEBP_FILE_SIZE_BYTE_LENGTH + WEBP_SIGNATURE_2.length);

        // Create a separate byte array to calculate file length
        ByteArrayOutputStream nonHeaderByteArrayOutputStream = null;
        try {
            nonHeaderByteArrayOutputStream = new ByteArrayOutputStream();
            ByteOrderedDataOutputStream nonHeaderOutputStream =
                    new ByteOrderedDataOutputStream(nonHeaderByteArrayOutputStream,
                            ByteOrder.LITTLE_ENDIAN);

            if (mExifOffset != 0) {
                // EXIF chunk exists in the original file
                // Tested by webp_with_exif.webp
                int bytesRead = WEBP_SIGNATURE_1.length + WEBP_FILE_SIZE_BYTE_LENGTH
                        + WEBP_SIGNATURE_2.length;
                copy(totalInputStream, nonHeaderOutputStream,
                        mExifOffset - bytesRead - WEBP_CHUNK_TYPE_BYTE_LENGTH
                                - WEBP_CHUNK_SIZE_BYTE_LENGTH);

                // Skip input stream to the end of the EXIF chunk
                totalInputStream.skipBytes(WEBP_CHUNK_TYPE_BYTE_LENGTH);
                int exifChunkLength = totalInputStream.readInt();
                totalInputStream.skipBytes(exifChunkLength);

                // Write new EXIF chunk to output stream
                int exifSize = writeExifSegment(nonHeaderOutputStream);
            } else {
                // EXIF chunk does not exist in the original file
                byte[] firstChunkType = new byte[WEBP_CHUNK_TYPE_BYTE_LENGTH];
                if (totalInputStream.read(firstChunkType) != firstChunkType.length) {
                    throw new IOException("Encountered invalid length while parsing WebP chunk "
                            + "type");
                }

                if (Arrays.equals(firstChunkType, WEBP_CHUNK_TYPE_VP8X)) {
                    // Original file already includes other extra data
                    int size = totalInputStream.readInt();
                    // WebP files have a single padding byte at the end if the chunk size is odd.
                    byte[] data = new byte[(size % 2) == 1 ? size + 1 : size];
                    totalInputStream.read(data);

                    // Set the EXIF flag to 1
                    data[0] = (byte) (data[0] | (1 << 3));

                    // Retrieve Animation flag--in order to check where EXIF data should start
                    boolean containsAnimation = ((data[0] >> 1) & 1) == 1;

                    // Write the original VP8X chunk
                    nonHeaderOutputStream.write(WEBP_CHUNK_TYPE_VP8X);
                    nonHeaderOutputStream.writeInt(size);
                    nonHeaderOutputStream.write(data);

                    // Animation control data is composed of 1 ANIM chunk and multiple ANMF
                    // chunks and since the image data (VP8/VP8L) chunks are included in the ANMF
                    // chunks, EXIF data should come after the last ANMF chunk.
                    // Also, because there is no value indicating the amount of ANMF chunks, we need
                    // to keep iterating through chunks until we either reach the end of the file or
                    // the XMP chunk (if it exists).
                    // Tested by webp_with_anim_without_exif.webp
                    if (containsAnimation) {
                        copyChunksUpToGivenChunkType(totalInputStream, nonHeaderOutputStream,
                                WEBP_CHUNK_TYPE_ANIM, null);

                        while (true) {
                            byte[] type = new byte[WEBP_CHUNK_TYPE_BYTE_LENGTH];
                            int read = inputStream.read(type);
                            if (!Arrays.equals(type, WEBP_CHUNK_TYPE_ANMF)) {
                                // Either we have reached EOF or the start of a non-ANMF chunk
                                writeExifSegment(nonHeaderOutputStream);
                                break;
                            }
                            copyWebPChunk(totalInputStream, nonHeaderOutputStream, type);
                        }
                    } else {
                        // Skip until we find the VP8 or VP8L chunk
                        copyChunksUpToGivenChunkType(totalInputStream, nonHeaderOutputStream,
                                WEBP_CHUNK_TYPE_VP8, WEBP_CHUNK_TYPE_VP8L);
                        writeExifSegment(nonHeaderOutputStream);
                    }
                } else if (Arrays.equals(firstChunkType, WEBP_CHUNK_TYPE_VP8)
                        || Arrays.equals(firstChunkType, WEBP_CHUNK_TYPE_VP8L)) {
                    int size = totalInputStream.readInt();
                    int bytesToRead = size;
                    // WebP files have a single padding byte at the end if the chunk size is odd.
                    if (size % 2 == 1) {
                        bytesToRead += 1;
                    }

                    // Retrieve image width/height
                    int widthAndHeight = 0;
                    int width = 0;
                    int height = 0;
                    int alpha = 0;
                    // Save VP8 frame data for later
                    byte[] vp8Frame = new byte[3];

                    if (Arrays.equals(firstChunkType, WEBP_CHUNK_TYPE_VP8)) {
                        totalInputStream.read(vp8Frame);

                        // Check signature
                        byte[] vp8Signature = new byte[3];
                        if (totalInputStream.read(vp8Signature) != vp8Signature.length
                                || !Arrays.equals(WEBP_VP8_SIGNATURE, vp8Signature)) {
                            throw new IOException("Encountered error while checking VP8 "
                                    + "signature");
                        }

                        // Retrieve image width/height
                        widthAndHeight = totalInputStream.readInt();
                        width = (widthAndHeight << 18) >> 18;
                        height = (widthAndHeight << 2) >> 18;
                        bytesToRead -= (vp8Frame.length + vp8Signature.length + 4);
                    } else if (Arrays.equals(firstChunkType, WEBP_CHUNK_TYPE_VP8L)) {
                        // Check signature
                        byte vp8lSignature = totalInputStream.readByte();
                        if (vp8lSignature != WEBP_VP8L_SIGNATURE) {
                            throw new IOException("Encountered error while checking VP8L "
                                    + "signature");
                        }

                        // Retrieve image width/height
                        widthAndHeight = totalInputStream.readInt();
                        // VP8L stores width - 1 and height - 1 values. See "2 RIFF Header" of
                        // "WebP Lossless Bitstream Specification"
                        width = ((widthAndHeight << 18) >> 18) + 1;
                        height = ((widthAndHeight << 4) >> 18) + 1;
                        // Retrieve alpha bit
                        alpha = widthAndHeight & (1 << 3);
                        bytesToRead -= (1 /* VP8L signature */ + 4);
                    }

                    // Create VP8X with Exif flag set to 1
                    nonHeaderOutputStream.write(WEBP_CHUNK_TYPE_VP8X);
                    nonHeaderOutputStream.writeInt(WEBP_CHUNK_TYPE_VP8X_DEFAULT_LENGTH);
                    byte[] data = new byte[WEBP_CHUNK_TYPE_VP8X_DEFAULT_LENGTH];
                    // EXIF flag
                    data[0] = (byte) (data[0] | (1 << 3));
                    // ALPHA flag
                    data[0] = (byte) (data[0] | (alpha << 4));
                    // VP8X stores Width - 1 and Height - 1 values
                    width -= 1;
                    height -= 1;
                    data[4] = (byte) width;
                    data[5] = (byte) (width >> 8);
                    data[6] = (byte) (width >> 16);
                    data[7] = (byte) height;
                    data[8] = (byte) (height >> 8);
                    data[9] = (byte) (height >> 16);
                    nonHeaderOutputStream.write(data);

                    // Write VP8 or VP8L data
                    nonHeaderOutputStream.write(firstChunkType);
                    nonHeaderOutputStream.writeInt(size);
                    if (Arrays.equals(firstChunkType, WEBP_CHUNK_TYPE_VP8)) {
                        nonHeaderOutputStream.write(vp8Frame);
                        nonHeaderOutputStream.write(WEBP_VP8_SIGNATURE);
                        nonHeaderOutputStream.writeInt(widthAndHeight);
                    } else if (Arrays.equals(firstChunkType, WEBP_CHUNK_TYPE_VP8L)) {
                        nonHeaderOutputStream.write(WEBP_VP8L_SIGNATURE);
                        nonHeaderOutputStream.writeInt(widthAndHeight);
                    }
                    copy(totalInputStream, nonHeaderOutputStream, bytesToRead);

                    // Write EXIF chunk
                    writeExifSegment(nonHeaderOutputStream);
                }
            }

            // Copy the rest of the file
            copy(totalInputStream, nonHeaderOutputStream);

            // Write file length + second signature
            totalOutputStream.writeInt(nonHeaderByteArrayOutputStream.size()
                    + WEBP_SIGNATURE_2.length);
            totalOutputStream.write(WEBP_SIGNATURE_2);
            nonHeaderByteArrayOutputStream.writeTo(totalOutputStream);
        } catch (Exception e) {
            throw new IOException("Failed to save WebP file", e);
        } finally {
            closeQuietly(nonHeaderByteArrayOutputStream);
        }
    }

    private void copyChunksUpToGivenChunkType(ByteOrderedDataInputStream inputStream,
            ByteOrderedDataOutputStream outputStream, byte[] firstGivenType,
            byte[] secondGivenType) throws IOException {
        while (true) {
            byte[] type = new byte[WEBP_CHUNK_TYPE_BYTE_LENGTH];
            if (inputStream.read(type) != type.length) {
                throw new IOException("Encountered invalid length while copying WebP chunks up to"
                        + "chunk type " + new String(firstGivenType, ASCII)
                        + ((secondGivenType == null) ? "" : " or " + new String(secondGivenType,
                        ASCII)));
            }
            copyWebPChunk(inputStream, outputStream, type);
            if (Arrays.equals(type, firstGivenType)
                    || (secondGivenType != null && Arrays.equals(type, secondGivenType))) {
                break;
            }
        }
    }

    private void copyWebPChunk(ByteOrderedDataInputStream inputStream,
            ByteOrderedDataOutputStream outputStream, byte[] type) throws IOException {
        int size = inputStream.readInt();
        outputStream.write(type);
        outputStream.writeInt(size);
        // WebP files have a single padding byte at the end if the chunk size is odd.
        copy(inputStream, outputStream, (size % 2) == 1 ? size + 1 : size);
    }

    // Reads the given EXIF byte area and save its tag data into attributes.
    private void readExifSegment(byte[] exifBytes, int imageType) throws IOException {
        ByteOrderedDataInputStream dataInputStream =
                new ByteOrderedDataInputStream(exifBytes);

        // Parse TIFF Headers. See JEITA CP-3451C Section 4.5.2. Table 1.
        parseTiffHeaders(dataInputStream, exifBytes.length);

        // Read TIFF image file directories. See JEITA CP-3451C Section 4.5.2. Figure 6.
        readImageFileDirectory(dataInputStream, imageType);
    }

    private void addDefaultValuesForCompatibility() {
        // If DATETIME tag has no value, then set the value to DATETIME_ORIGINAL tag's.
        String valueOfDateTimeOriginal = getAttribute(TAG_DATETIME_ORIGINAL);
        if (valueOfDateTimeOriginal != null && getAttribute(TAG_DATETIME) == null) {
            mAttributes[IFD_TYPE_PRIMARY].put(TAG_DATETIME,
                    ExifAttribute.createString(valueOfDateTimeOriginal));
        }

        // Add the default value.
        if (getAttribute(TAG_IMAGE_WIDTH) == null) {
            mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_WIDTH,
                    ExifAttribute.createULong(0, mExifByteOrder));
        }
        if (getAttribute(TAG_IMAGE_LENGTH) == null) {
            mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_LENGTH,
                    ExifAttribute.createULong(0, mExifByteOrder));
        }
        if (getAttribute(TAG_ORIENTATION) == null) {
            mAttributes[IFD_TYPE_PRIMARY].put(TAG_ORIENTATION,
                    ExifAttribute.createUShort(0, mExifByteOrder));
        }
        if (getAttribute(TAG_LIGHT_SOURCE) == null) {
            mAttributes[IFD_TYPE_EXIF].put(TAG_LIGHT_SOURCE,
                    ExifAttribute.createULong(0, mExifByteOrder));
        }
    }

    private ByteOrder readByteOrder(ByteOrderedDataInputStream dataInputStream)
            throws IOException {
        // Read byte order.
        short byteOrder = dataInputStream.readShort();
        switch (byteOrder) {
            case BYTE_ALIGN_II:
                if (DEBUG) {
                    Log.d(TAG, "readExifSegment: Byte Align II");
                }
                return ByteOrder.LITTLE_ENDIAN;
            case BYTE_ALIGN_MM:
                if (DEBUG) {
                    Log.d(TAG, "readExifSegment: Byte Align MM");
                }
                return ByteOrder.BIG_ENDIAN;
            default:
                throw new IOException("Invalid byte order: " + Integer.toHexString(byteOrder));
        }
    }

    private void parseTiffHeaders(ByteOrderedDataInputStream dataInputStream,
            int exifBytesLength) throws IOException {
        // Read byte order
        mExifByteOrder = readByteOrder(dataInputStream);
        // Set byte order
        dataInputStream.setByteOrder(mExifByteOrder);

        // Check start code
        int startCode = dataInputStream.readUnsignedShort();
        if (mMimeType != IMAGE_TYPE_ORF && mMimeType != IMAGE_TYPE_RW2 && startCode != START_CODE) {
            throw new IOException("Invalid start code: " + Integer.toHexString(startCode));
        }

        // Read and skip to first ifd offset
        int firstIfdOffset = dataInputStream.readInt();
        if (firstIfdOffset < 8 || firstIfdOffset >= exifBytesLength) {
            throw new IOException("Invalid first Ifd offset: " + firstIfdOffset);
        }
        firstIfdOffset -= 8;
        if (firstIfdOffset > 0) {
            if (dataInputStream.skipBytes(firstIfdOffset) != firstIfdOffset) {
                throw new IOException("Couldn't jump to first Ifd: " + firstIfdOffset);
            }
        }
    }

    // Reads image file directory, which is a tag group in EXIF.
    private void readImageFileDirectory(ByteOrderedDataInputStream dataInputStream,
            @IfdType int ifdType) throws IOException {
        // Save offset of current IFD to prevent reading an IFD that is already read.
        mHandledIfdOffsets.add(dataInputStream.mPosition);

        if (dataInputStream.mPosition + 2 > dataInputStream.mLength) {
            // Return if there is no data from the offset.
            return;
        }
        // See TIFF 6.0 Section 2: TIFF Structure, Figure 1.
        short numberOfDirectoryEntry = dataInputStream.readShort();
        if (dataInputStream.mPosition + 12 * numberOfDirectoryEntry > dataInputStream.mLength
                || numberOfDirectoryEntry <= 0) {
            // Return if the size of entries is either too big or negative.
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "numberOfDirectoryEntry: " + numberOfDirectoryEntry);
        }

        // See TIFF 6.0 Section 2: TIFF Structure, "Image File Directory".
        for (short i = 0; i < numberOfDirectoryEntry; ++i) {
            int tagNumber = dataInputStream.readUnsignedShort();
            int dataFormat = dataInputStream.readUnsignedShort();
            int numberOfComponents = dataInputStream.readInt();
            // Next four bytes is for data offset or value.
            long nextEntryOffset = dataInputStream.peek() + 4;

            // Look up a corresponding tag from tag number
            ExifTag tag = (ExifTag) sExifTagMapsForReading[ifdType].get(tagNumber);

            if (DEBUG) {
                Log.d(TAG, String.format("ifdType: %d, tagNumber: %d, tagName: %s, dataFormat: %d, "
                        + "numberOfComponents: %d", ifdType, tagNumber,
                        tag != null ? tag.name : null, dataFormat, numberOfComponents));
            }

            long byteCount = 0;
            boolean valid = false;
            if (tag == null) {
                if (DEBUG) {
                    Log.d(TAG, "Skip the tag entry since tag number is not defined: " + tagNumber);
                }
            } else if (dataFormat <= 0 || dataFormat >= IFD_FORMAT_BYTES_PER_FORMAT.length) {
                if (DEBUG) {
                    Log.d(TAG, "Skip the tag entry since data format is invalid: " + dataFormat);
                }
            } else {
                byteCount = (long) numberOfComponents * IFD_FORMAT_BYTES_PER_FORMAT[dataFormat];
                if (byteCount < 0 || byteCount > Integer.MAX_VALUE) {
                    if (DEBUG) {
                        Log.d(TAG, "Skip the tag entry since the number of components is invalid: "
                                + numberOfComponents);
                    }
                } else {
                    valid = true;
                }
            }
            if (!valid) {
                dataInputStream.seek(nextEntryOffset);
                continue;
            }

            // Read a value from data field or seek to the value offset which is stored in data
            // field if the size of the entry value is bigger than 4.
            if (byteCount > 4) {
                int offset = dataInputStream.readInt();
                if (DEBUG) {
                    Log.d(TAG, "seek to data offset: " + offset);
                }
                if (mMimeType == IMAGE_TYPE_ORF) {
                    if (tag.name == TAG_MAKER_NOTE) {
                        // Save offset value for reading thumbnail
                        mOrfMakerNoteOffset = offset;
                    } else if (ifdType == IFD_TYPE_ORF_MAKER_NOTE
                            && tag.name == TAG_ORF_THUMBNAIL_IMAGE) {
                        // Retrieve & update values for thumbnail offset and length values for ORF
                        mOrfThumbnailOffset = offset;
                        mOrfThumbnailLength = numberOfComponents;

                        ExifAttribute compressionAttribute =
                                ExifAttribute.createUShort(DATA_JPEG, mExifByteOrder);
                        ExifAttribute jpegInterchangeFormatAttribute =
                                ExifAttribute.createULong(mOrfThumbnailOffset, mExifByteOrder);
                        ExifAttribute jpegInterchangeFormatLengthAttribute =
                                ExifAttribute.createULong(mOrfThumbnailLength, mExifByteOrder);

                        mAttributes[IFD_TYPE_THUMBNAIL].put(TAG_COMPRESSION, compressionAttribute);
                        mAttributes[IFD_TYPE_THUMBNAIL].put(TAG_JPEG_INTERCHANGE_FORMAT,
                                jpegInterchangeFormatAttribute);
                        mAttributes[IFD_TYPE_THUMBNAIL].put(TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
                                jpegInterchangeFormatLengthAttribute);
                    }
                } else if (mMimeType == IMAGE_TYPE_RW2) {
                    if (tag.name == TAG_RW2_JPG_FROM_RAW) {
                        mRw2JpgFromRawOffset = offset;
                    }
                }
                if (offset + byteCount <= dataInputStream.mLength) {
                    dataInputStream.seek(offset);
                } else {
                    // Skip if invalid data offset.
                    if (DEBUG) {
                        Log.d(TAG, "Skip the tag entry since data offset is invalid: " + offset);
                    }
                    dataInputStream.seek(nextEntryOffset);
                    continue;
                }
            }

            // Recursively parse IFD when a IFD pointer tag appears.
            Integer nextIfdType = sExifPointerTagMap.get(tagNumber);
            if (DEBUG) {
                Log.d(TAG, "nextIfdType: " + nextIfdType + " byteCount: " + byteCount);
            }

            if (nextIfdType != null) {
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
                    case IFD_FORMAT_SLONG:
                    case IFD_FORMAT_IFD: {
                        offset = dataInputStream.readInt();
                        break;
                    }
                    default: {
                        // Nothing to do
                        break;
                    }
                }
                if (DEBUG) {
                    Log.d(TAG, String.format("Offset: %d, tagName: %s", offset, tag.name));
                }

                // Check if the next IFD offset
                // 1. Exists within the boundaries of the input stream
                // 2. Does not point to a previously read IFD.
                if (offset > 0L && offset < dataInputStream.mLength) {
                    if (!mHandledIfdOffsets.contains((int) offset)) {
                        dataInputStream.seek(offset);
                        readImageFileDirectory(dataInputStream, nextIfdType);
                    } else {
                        if (DEBUG) {
                            Log.d(TAG, "Skip jump into the IFD since it has already been read: "
                                    + "IfdType " + nextIfdType + " (at " + offset + ")");
                        }
                    }
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Skip jump into the IFD since its offset is invalid: " + offset);
                    }
                }

                dataInputStream.seek(nextEntryOffset);
                continue;
            }

            final int bytesOffset = dataInputStream.peek() + mExifOffset;
            final byte[] bytes = new byte[(int) byteCount];
            dataInputStream.readFully(bytes);
            ExifAttribute attribute = new ExifAttribute(dataFormat, numberOfComponents,
                    bytesOffset, bytes);
            mAttributes[ifdType].put(tag.name, attribute);

            // DNG files have a DNG Version tag specifying the version of specifications that the
            // image file is following.
            // See http://fileformats.archiveteam.org/wiki/DNG
            if (tag.name == TAG_DNG_VERSION) {
                mMimeType = IMAGE_TYPE_DNG;
            }

            // PEF files have a Make or Model tag that begins with "PENTAX" or a compression tag
            // that is 65535.
            // See http://fileformats.archiveteam.org/wiki/Pentax_PEF
            if (((tag.name == TAG_MAKE || tag.name == TAG_MODEL)
                    && attribute.getStringValue(mExifByteOrder).contains(PEF_SIGNATURE))
                    || (tag.name == TAG_COMPRESSION
                    && attribute.getIntValue(mExifByteOrder) == 65535)) {
                mMimeType = IMAGE_TYPE_PEF;
            }

            // Seek to next tag offset
            if (dataInputStream.peek() != nextEntryOffset) {
                dataInputStream.seek(nextEntryOffset);
            }
        }

        if (dataInputStream.peek() + 4 <= dataInputStream.mLength) {
            int nextIfdOffset = dataInputStream.readInt();
            if (DEBUG) {
                Log.d(TAG, String.format("nextIfdOffset: %d", nextIfdOffset));
            }
            // Check if the next IFD offset
            // 1. Exists within the boundaries of the input stream
            // 2. Does not point to a previously read IFD.
            if (nextIfdOffset > 0L && nextIfdOffset < dataInputStream.mLength) {
                if (!mHandledIfdOffsets.contains(nextIfdOffset)) {
                    dataInputStream.seek(nextIfdOffset);
                    // Do not overwrite thumbnail IFD data if it alreay exists.
                    if (mAttributes[IFD_TYPE_THUMBNAIL].isEmpty()) {
                        readImageFileDirectory(dataInputStream, IFD_TYPE_THUMBNAIL);
                    } else if (mAttributes[IFD_TYPE_PREVIEW].isEmpty()) {
                        readImageFileDirectory(dataInputStream, IFD_TYPE_PREVIEW);
                    }
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Stop reading file since re-reading an IFD may cause an "
                                + "infinite loop: " + nextIfdOffset);
                    }
                }
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Stop reading file since a wrong offset may cause an infinite loop: "
                            + nextIfdOffset);
                }
            }
        }
    }

    /**
     * JPEG compressed images do not contain IMAGE_LENGTH & IMAGE_WIDTH tags.
     * This value uses JpegInterchangeFormat(JPEG data offset) value, and calls getJpegAttributes()
     * to locate SOF(Start of Frame) marker and update the image length & width values.
     * See JEITA CP-3451C Table 5 and Section 4.8.1. B.
     */
    private void retrieveJpegImageSize(ByteOrderedDataInputStream in, int imageType)
            throws IOException {
        // Check if image already has IMAGE_LENGTH & IMAGE_WIDTH values
        ExifAttribute imageLengthAttribute =
                (ExifAttribute) mAttributes[imageType].get(TAG_IMAGE_LENGTH);
        ExifAttribute imageWidthAttribute =
                (ExifAttribute) mAttributes[imageType].get(TAG_IMAGE_WIDTH);

        if (imageLengthAttribute == null || imageWidthAttribute == null) {
            // Find if offset for JPEG data exists
            ExifAttribute jpegInterchangeFormatAttribute =
                    (ExifAttribute) mAttributes[imageType].get(TAG_JPEG_INTERCHANGE_FORMAT);
            if (jpegInterchangeFormatAttribute != null) {
                int jpegInterchangeFormat =
                        jpegInterchangeFormatAttribute.getIntValue(mExifByteOrder);

                // Searches for SOF marker in JPEG data and updates IMAGE_LENGTH & IMAGE_WIDTH tags
                getJpegAttributes(in, jpegInterchangeFormat, imageType);
            }
        }
    }

    // Sets thumbnail offset & length attributes based on JpegInterchangeFormat or StripOffsets tags
    private void setThumbnailData(ByteOrderedDataInputStream in) throws IOException {
        HashMap thumbnailData = mAttributes[IFD_TYPE_THUMBNAIL];

        ExifAttribute compressionAttribute =
                (ExifAttribute) thumbnailData.get(TAG_COMPRESSION);
        if (compressionAttribute != null) {
            mThumbnailCompression = compressionAttribute.getIntValue(mExifByteOrder);
            switch (mThumbnailCompression) {
                case DATA_JPEG: {
                    handleThumbnailFromJfif(in, thumbnailData);
                    break;
                }
                case DATA_UNCOMPRESSED:
                case DATA_JPEG_COMPRESSED: {
                    if (isSupportedDataType(thumbnailData)) {
                        handleThumbnailFromStrips(in, thumbnailData);
                    }
                    break;
                }
            }
        } else {
            // Thumbnail data may not contain Compression tag value
            handleThumbnailFromJfif(in, thumbnailData);
        }
    }

    // Check JpegInterchangeFormat(JFIF) tags to retrieve thumbnail offset & length values
    // and reads the corresponding bytes if stream does not support seek function
    private void handleThumbnailFromJfif(ByteOrderedDataInputStream in, HashMap thumbnailData)
            throws IOException {
        ExifAttribute jpegInterchangeFormatAttribute =
                (ExifAttribute) thumbnailData.get(TAG_JPEG_INTERCHANGE_FORMAT);
        ExifAttribute jpegInterchangeFormatLengthAttribute =
                (ExifAttribute) thumbnailData.get(TAG_JPEG_INTERCHANGE_FORMAT_LENGTH);
        if (jpegInterchangeFormatAttribute != null
                && jpegInterchangeFormatLengthAttribute != null) {
            int thumbnailOffset = jpegInterchangeFormatAttribute.getIntValue(mExifByteOrder);
            int thumbnailLength = jpegInterchangeFormatLengthAttribute.getIntValue(mExifByteOrder);

            if (mMimeType == IMAGE_TYPE_ORF) {
                // Update offset value since RAF files have IFD data preceding MakerNote data.
                thumbnailOffset += mOrfMakerNoteOffset;
            }
            // The following code limits the size of thumbnail size not to overflow EXIF data area.
            thumbnailLength = Math.min(thumbnailLength, in.getLength() - thumbnailOffset);

            if (thumbnailOffset > 0 && thumbnailLength > 0) {
                mHasThumbnail = true;
                // Need to add mExifOffset, which is the offset to the EXIF data segment
                mThumbnailOffset = thumbnailOffset + mExifOffset;
                mThumbnailLength = thumbnailLength;
                mThumbnailCompression = DATA_JPEG;

                if (mFilename == null && mAssetInputStream == null
                        && mSeekableFileDescriptor == null) {
                    // TODO: Need to handle potential OutOfMemoryError
                    // Save the thumbnail in memory if the input doesn't support reading again.
                    byte[] thumbnailBytes = new byte[mThumbnailLength];
                    in.seek(mThumbnailOffset);
                    in.readFully(thumbnailBytes);
                    mThumbnailBytes = thumbnailBytes;
                }
            }
            if (DEBUG) {
                Log.d(TAG, "Setting thumbnail attributes with offset: " + thumbnailOffset
                        + ", length: " + thumbnailLength);
            }
        }
    }

    // Check StripOffsets & StripByteCounts tags to retrieve thumbnail offset & length values
    private void handleThumbnailFromStrips(ByteOrderedDataInputStream in, HashMap thumbnailData)
            throws IOException {
        ExifAttribute stripOffsetsAttribute =
                (ExifAttribute) thumbnailData.get(TAG_STRIP_OFFSETS);
        ExifAttribute stripByteCountsAttribute =
                (ExifAttribute) thumbnailData.get(TAG_STRIP_BYTE_COUNTS);

        if (stripOffsetsAttribute != null && stripByteCountsAttribute != null) {
            long[] stripOffsets =
                    convertToLongArray(stripOffsetsAttribute.getValue(mExifByteOrder));
            long[] stripByteCounts =
                    convertToLongArray(stripByteCountsAttribute.getValue(mExifByteOrder));

            if (stripOffsets == null || stripOffsets.length == 0) {
                Log.w(TAG, "stripOffsets should not be null or have zero length.");
                return;
            }
            if (stripByteCounts == null || stripByteCounts.length == 0) {
                Log.w(TAG, "stripByteCounts should not be null or have zero length.");
                return;
            }
            if (stripOffsets.length != stripByteCounts.length) {
                Log.w(TAG, "stripOffsets and stripByteCounts should have same length.");
                return;
            }

            // TODO: Need to handle potential OutOfMemoryError
            // Set thumbnail byte array data for non-consecutive strip bytes
            byte[] totalStripBytes =
                    new byte[(int) Arrays.stream(stripByteCounts).sum()];

            int bytesRead = 0;
            int bytesAdded = 0;
            mHasThumbnail = mHasThumbnailStrips = mAreThumbnailStripsConsecutive = true;
            for (int i = 0; i < stripOffsets.length; i++) {
                int stripOffset = (int) stripOffsets[i];
                int stripByteCount = (int) stripByteCounts[i];

                // Check if strips are consecutive
                // TODO: Add test for non-consecutive thumbnail image
                if (i < stripOffsets.length - 1
                        && stripOffset + stripByteCount != stripOffsets[i + 1]) {
                    mAreThumbnailStripsConsecutive = false;
                }

                // Skip to offset
                int skipBytes = stripOffset - bytesRead;
                if (skipBytes < 0) {
                    Log.d(TAG, "Invalid strip offset value");
                }
                in.seek(skipBytes);
                bytesRead += skipBytes;

                // TODO: Need to handle potential OutOfMemoryError
                // Read strip bytes
                byte[] stripBytes = new byte[stripByteCount];
                in.read(stripBytes);
                bytesRead += stripByteCount;

                // Add bytes to array
                System.arraycopy(stripBytes, 0, totalStripBytes, bytesAdded,
                        stripBytes.length);
                bytesAdded += stripBytes.length;
            }
            mThumbnailBytes = totalStripBytes;

            if (mAreThumbnailStripsConsecutive) {
                // Need to add mExifOffset, which is the offset to the EXIF data segment
                mThumbnailOffset = (int) stripOffsets[0] + mExifOffset;
                mThumbnailLength = totalStripBytes.length;
            }
        }
    }

    // Check if thumbnail data type is currently supported or not
    private boolean isSupportedDataType(HashMap thumbnailData) throws IOException {
        ExifAttribute bitsPerSampleAttribute =
                (ExifAttribute) thumbnailData.get(TAG_BITS_PER_SAMPLE);
        if (bitsPerSampleAttribute != null) {
            int[] bitsPerSampleValue = (int[]) bitsPerSampleAttribute.getValue(mExifByteOrder);

            if (Arrays.equals(BITS_PER_SAMPLE_RGB, bitsPerSampleValue)) {
                return true;
            }

            // See DNG Specification 1.4.0.0. Section 3, Compression.
            if (mMimeType == IMAGE_TYPE_DNG) {
                ExifAttribute photometricInterpretationAttribute =
                        (ExifAttribute) thumbnailData.get(TAG_PHOTOMETRIC_INTERPRETATION);
                if (photometricInterpretationAttribute != null) {
                    int photometricInterpretationValue
                            = photometricInterpretationAttribute.getIntValue(mExifByteOrder);
                    if ((photometricInterpretationValue == PHOTOMETRIC_INTERPRETATION_BLACK_IS_ZERO
                            && Arrays.equals(bitsPerSampleValue, BITS_PER_SAMPLE_GREYSCALE_2))
                            || ((photometricInterpretationValue == PHOTOMETRIC_INTERPRETATION_YCBCR)
                            && (Arrays.equals(bitsPerSampleValue, BITS_PER_SAMPLE_RGB)))) {
                        return true;
                    } else {
                        // TODO: Add support for lossless Huffman JPEG data
                    }
                }
            }
        }
        if (DEBUG) {
            Log.d(TAG, "Unsupported data type value");
        }
        return false;
    }

    // Returns true if the image length and width values are <= 512.
    // See Section 4.8 of http://standardsproposals.bsigroup.com/Home/getPDF/567
    private boolean isThumbnail(HashMap map) throws IOException {
        ExifAttribute imageLengthAttribute = (ExifAttribute) map.get(TAG_IMAGE_LENGTH);
        ExifAttribute imageWidthAttribute = (ExifAttribute) map.get(TAG_IMAGE_WIDTH);

        if (imageLengthAttribute != null && imageWidthAttribute != null) {
            int imageLengthValue = imageLengthAttribute.getIntValue(mExifByteOrder);
            int imageWidthValue = imageWidthAttribute.getIntValue(mExifByteOrder);
            if (imageLengthValue <= MAX_THUMBNAIL_SIZE && imageWidthValue <= MAX_THUMBNAIL_SIZE) {
                return true;
            }
        }
        return false;
    }

    // Validate primary, preview, thumbnail image data by comparing image size
    private void validateImages() throws IOException {
        // Swap images based on size (primary > preview > thumbnail)
        swapBasedOnImageSize(IFD_TYPE_PRIMARY, IFD_TYPE_PREVIEW);
        swapBasedOnImageSize(IFD_TYPE_PRIMARY, IFD_TYPE_THUMBNAIL);
        swapBasedOnImageSize(IFD_TYPE_PREVIEW, IFD_TYPE_THUMBNAIL);

        // TODO (b/142296453): Revise image width/height setting logic
        // Check if image has PixelXDimension/PixelYDimension tags, which contain valid image
        // sizes, excluding padding at the right end or bottom end of the image to make sure that
        // the values are multiples of 64. See JEITA CP-3451C Table 5 and Section 4.8.1. B.
        ExifAttribute pixelXDimAttribute =
                (ExifAttribute) mAttributes[IFD_TYPE_EXIF].get(TAG_PIXEL_X_DIMENSION);
        ExifAttribute pixelYDimAttribute =
                (ExifAttribute) mAttributes[IFD_TYPE_EXIF].get(TAG_PIXEL_Y_DIMENSION);
        if (pixelXDimAttribute != null && pixelYDimAttribute != null) {
            mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_WIDTH, pixelXDimAttribute);
            mAttributes[IFD_TYPE_PRIMARY].put(TAG_IMAGE_LENGTH, pixelYDimAttribute);
        }

        // Check whether thumbnail image exists and whether preview image satisfies the thumbnail
        // image requirements
        if (mAttributes[IFD_TYPE_THUMBNAIL].isEmpty()) {
            if (isThumbnail(mAttributes[IFD_TYPE_PREVIEW])) {
                mAttributes[IFD_TYPE_THUMBNAIL] = mAttributes[IFD_TYPE_PREVIEW];
                mAttributes[IFD_TYPE_PREVIEW] = new HashMap();
            }
        }

        // Check if the thumbnail image satisfies the thumbnail size requirements
        if (!isThumbnail(mAttributes[IFD_TYPE_THUMBNAIL])) {
            Log.d(TAG, "No image meets the size requirements of a thumbnail image.");
        }

        // TAG_THUMBNAIL_* tags should be replaced with TAG_* equivalents and vice versa if needed.
        replaceInvalidTags(IFD_TYPE_PRIMARY, TAG_THUMBNAIL_ORIENTATION, TAG_ORIENTATION);
        replaceInvalidTags(IFD_TYPE_PRIMARY, TAG_THUMBNAIL_IMAGE_LENGTH, TAG_IMAGE_LENGTH);
        replaceInvalidTags(IFD_TYPE_PRIMARY, TAG_THUMBNAIL_IMAGE_WIDTH, TAG_IMAGE_WIDTH);
        replaceInvalidTags(IFD_TYPE_PREVIEW, TAG_THUMBNAIL_ORIENTATION, TAG_ORIENTATION);
        replaceInvalidTags(IFD_TYPE_PREVIEW, TAG_THUMBNAIL_IMAGE_LENGTH, TAG_IMAGE_LENGTH);
        replaceInvalidTags(IFD_TYPE_PREVIEW, TAG_THUMBNAIL_IMAGE_WIDTH, TAG_IMAGE_WIDTH);
        replaceInvalidTags(IFD_TYPE_THUMBNAIL, TAG_ORIENTATION, TAG_THUMBNAIL_ORIENTATION);
        replaceInvalidTags(IFD_TYPE_THUMBNAIL, TAG_IMAGE_LENGTH, TAG_THUMBNAIL_IMAGE_LENGTH);
        replaceInvalidTags(IFD_TYPE_THUMBNAIL, TAG_IMAGE_WIDTH, TAG_THUMBNAIL_IMAGE_WIDTH);
    }

    /**
     * If image is uncompressed, ImageWidth/Length tags are used to store size info.
     * However, uncompressed images often store extra pixels around the edges of the final image,
     * which results in larger values for TAG_IMAGE_WIDTH and TAG_IMAGE_LENGTH tags.
     * This method corrects those tag values by checking first the values of TAG_DEFAULT_CROP_SIZE
     * See DNG Specification 1.4.0.0. Section 4. (DefaultCropSize)
     *
     * If image is a RW2 file, valid image sizes are stored in SensorBorder tags.
     * See tiff_parser.cc GetFullDimension32()
     * */
    private void updateImageSizeValues(ByteOrderedDataInputStream in, int imageType)
            throws IOException {
        // Uncompressed image valid image size values
        ExifAttribute defaultCropSizeAttribute =
                (ExifAttribute) mAttributes[imageType].get(TAG_DEFAULT_CROP_SIZE);
        // RW2 image valid image size values
        ExifAttribute topBorderAttribute =
                (ExifAttribute) mAttributes[imageType].get(TAG_RW2_SENSOR_TOP_BORDER);
        ExifAttribute leftBorderAttribute =
                (ExifAttribute) mAttributes[imageType].get(TAG_RW2_SENSOR_LEFT_BORDER);
        ExifAttribute bottomBorderAttribute =
                (ExifAttribute) mAttributes[imageType].get(TAG_RW2_SENSOR_BOTTOM_BORDER);
        ExifAttribute rightBorderAttribute =
                (ExifAttribute) mAttributes[imageType].get(TAG_RW2_SENSOR_RIGHT_BORDER);

        if (defaultCropSizeAttribute != null) {
            // Update for uncompressed image
            ExifAttribute defaultCropSizeXAttribute, defaultCropSizeYAttribute;
            if (defaultCropSizeAttribute.format == IFD_FORMAT_URATIONAL) {
                Rational[] defaultCropSizeValue =
                        (Rational[]) defaultCropSizeAttribute.getValue(mExifByteOrder);
                defaultCropSizeXAttribute =
                        ExifAttribute.createURational(defaultCropSizeValue[0], mExifByteOrder);
                defaultCropSizeYAttribute =
                        ExifAttribute.createURational(defaultCropSizeValue[1], mExifByteOrder);
            } else {
                int[] defaultCropSizeValue =
                        (int[]) defaultCropSizeAttribute.getValue(mExifByteOrder);
                defaultCropSizeXAttribute =
                        ExifAttribute.createUShort(defaultCropSizeValue[0], mExifByteOrder);
                defaultCropSizeYAttribute =
                        ExifAttribute.createUShort(defaultCropSizeValue[1], mExifByteOrder);
            }
            mAttributes[imageType].put(TAG_IMAGE_WIDTH, defaultCropSizeXAttribute);
            mAttributes[imageType].put(TAG_IMAGE_LENGTH, defaultCropSizeYAttribute);
        } else if (topBorderAttribute != null && leftBorderAttribute != null &&
                bottomBorderAttribute != null && rightBorderAttribute != null) {
            // Update for RW2 image
            int topBorderValue = topBorderAttribute.getIntValue(mExifByteOrder);
            int bottomBorderValue = bottomBorderAttribute.getIntValue(mExifByteOrder);
            int rightBorderValue = rightBorderAttribute.getIntValue(mExifByteOrder);
            int leftBorderValue = leftBorderAttribute.getIntValue(mExifByteOrder);
            if (bottomBorderValue > topBorderValue && rightBorderValue > leftBorderValue) {
                int length = bottomBorderValue - topBorderValue;
                int width = rightBorderValue - leftBorderValue;
                ExifAttribute imageLengthAttribute =
                        ExifAttribute.createUShort(length, mExifByteOrder);
                ExifAttribute imageWidthAttribute =
                        ExifAttribute.createUShort(width, mExifByteOrder);
                mAttributes[imageType].put(TAG_IMAGE_LENGTH, imageLengthAttribute);
                mAttributes[imageType].put(TAG_IMAGE_WIDTH, imageWidthAttribute);
            }
        } else {
            retrieveJpegImageSize(in, imageType);
        }
    }

    // Writes an Exif segment into the given output stream.
    private int writeExifSegment(ByteOrderedDataOutputStream dataOutputStream) throws IOException {
        // The following variables are for calculating each IFD tag group size in bytes.
        int[] ifdOffsets = new int[EXIF_TAGS.length];
        int[] ifdDataSizes = new int[EXIF_TAGS.length];

        // Remove IFD pointer tags (we'll re-add it later.)
        for (ExifTag tag : EXIF_POINTER_TAGS) {
            removeAttribute(tag.name);
        }
        // Remove old thumbnail data
        if (mHasThumbnail) {
            if (mHasThumbnailStrips) {
                removeAttribute(TAG_STRIP_OFFSETS);
                removeAttribute(TAG_STRIP_BYTE_COUNTS);
            } else {
                removeAttribute(TAG_JPEG_INTERCHANGE_FORMAT);
                removeAttribute(TAG_JPEG_INTERCHANGE_FORMAT_LENGTH);
            }
        }

        // Remove null value tags.
        for (int ifdType = 0; ifdType < EXIF_TAGS.length; ++ifdType) {
            for (Object obj : mAttributes[ifdType].entrySet().toArray()) {
                final Map.Entry entry = (Map.Entry) obj;
                if (entry.getValue() == null) {
                    mAttributes[ifdType].remove(entry.getKey());
                }
            }
        }

        // Add IFD pointer tags. The next offset of primary image TIFF IFD will have thumbnail IFD
        // offset when there is one or more tags in the thumbnail IFD.
        if (!mAttributes[IFD_TYPE_EXIF].isEmpty()) {
            mAttributes[IFD_TYPE_PRIMARY].put(EXIF_POINTER_TAGS[1].name,
                    ExifAttribute.createULong(0, mExifByteOrder));
        }
        if (!mAttributes[IFD_TYPE_GPS].isEmpty()) {
            mAttributes[IFD_TYPE_PRIMARY].put(EXIF_POINTER_TAGS[2].name,
                    ExifAttribute.createULong(0, mExifByteOrder));
        }
        if (!mAttributes[IFD_TYPE_INTEROPERABILITY].isEmpty()) {
            mAttributes[IFD_TYPE_EXIF].put(EXIF_POINTER_TAGS[3].name,
                    ExifAttribute.createULong(0, mExifByteOrder));
        }
        if (mHasThumbnail) {
            if (mHasThumbnailStrips) {
                mAttributes[IFD_TYPE_THUMBNAIL].put(TAG_STRIP_OFFSETS,
                        ExifAttribute.createUShort(0, mExifByteOrder));
                mAttributes[IFD_TYPE_THUMBNAIL].put(TAG_STRIP_BYTE_COUNTS,
                        ExifAttribute.createUShort(mThumbnailLength, mExifByteOrder));
            } else {
                mAttributes[IFD_TYPE_THUMBNAIL].put(TAG_JPEG_INTERCHANGE_FORMAT,
                        ExifAttribute.createULong(0, mExifByteOrder));
                mAttributes[IFD_TYPE_THUMBNAIL].put(TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
                        ExifAttribute.createULong(mThumbnailLength, mExifByteOrder));
            }
        }

        // Calculate IFD group data area sizes. IFD group data area is assigned to save the entry
        // value which has a bigger size than 4 bytes.
        for (int i = 0; i < EXIF_TAGS.length; ++i) {
            int sum = 0;
            for (Map.Entry entry : (Set<Map.Entry>) mAttributes[i].entrySet()) {
                final ExifAttribute exifAttribute = (ExifAttribute) entry.getValue();
                final int size = exifAttribute.size();
                if (size > 4) {
                    sum += size;
                }
            }
            ifdDataSizes[i] += sum;
        }

        // Calculate IFD offsets.
        // 8 bytes are for TIFF headers: 2 bytes (byte order) + 2 bytes (identifier) + 4 bytes
        // (offset of IFDs)
        int position = 8;
        for (int ifdType = 0; ifdType < EXIF_TAGS.length; ++ifdType) {
            if (!mAttributes[ifdType].isEmpty()) {
                ifdOffsets[ifdType] = position;
                position += 2 + mAttributes[ifdType].size() * 12 + 4 + ifdDataSizes[ifdType];
            }
        }
        if (mHasThumbnail) {
            int thumbnailOffset = position;
            if (mHasThumbnailStrips) {
                mAttributes[IFD_TYPE_THUMBNAIL].put(TAG_STRIP_OFFSETS,
                        ExifAttribute.createUShort(thumbnailOffset, mExifByteOrder));
            } else {
                mAttributes[IFD_TYPE_THUMBNAIL].put(TAG_JPEG_INTERCHANGE_FORMAT,
                        ExifAttribute.createULong(thumbnailOffset, mExifByteOrder));
            }
            // Need to add mExifOffset, which is the offset to the EXIF data segment
            mThumbnailOffset = thumbnailOffset + mExifOffset;
            position += mThumbnailLength;
        }

        int totalSize = position;
        if (mMimeType == IMAGE_TYPE_JPEG) {
            // Add 8 bytes for APP1 size and identifier data
            totalSize += 8;
        }
        if (DEBUG) {
            for (int i = 0; i < EXIF_TAGS.length; ++i) {
                Log.d(TAG, String.format("index: %d, offsets: %d, tag count: %d, data sizes: %d, "
                                + "total size: %d", i, ifdOffsets[i], mAttributes[i].size(),
                        ifdDataSizes[i], totalSize));
            }
        }

        // Update IFD pointer tags with the calculated offsets.
        if (!mAttributes[IFD_TYPE_EXIF].isEmpty()) {
            mAttributes[IFD_TYPE_PRIMARY].put(EXIF_POINTER_TAGS[1].name,
                    ExifAttribute.createULong(ifdOffsets[IFD_TYPE_EXIF], mExifByteOrder));
        }
        if (!mAttributes[IFD_TYPE_GPS].isEmpty()) {
            mAttributes[IFD_TYPE_PRIMARY].put(EXIF_POINTER_TAGS[2].name,
                    ExifAttribute.createULong(ifdOffsets[IFD_TYPE_GPS], mExifByteOrder));
        }
        if (!mAttributes[IFD_TYPE_INTEROPERABILITY].isEmpty()) {
            mAttributes[IFD_TYPE_EXIF].put(EXIF_POINTER_TAGS[3].name, ExifAttribute.createULong(
                    ifdOffsets[IFD_TYPE_INTEROPERABILITY], mExifByteOrder));
        }

        switch (mMimeType) {
            case IMAGE_TYPE_JPEG:
                // Write JPEG specific data (APP1 size, APP1 identifier)
                dataOutputStream.writeUnsignedShort(totalSize);
                dataOutputStream.write(IDENTIFIER_EXIF_APP1);
                break;
            case IMAGE_TYPE_PNG:
                // Write PNG specific data (chunk size, chunk type)
                dataOutputStream.writeInt(totalSize);
                dataOutputStream.write(PNG_CHUNK_TYPE_EXIF);
                break;
            case IMAGE_TYPE_WEBP:
                // Write WebP specific data (chunk type, chunk size)
                dataOutputStream.write(WEBP_CHUNK_TYPE_EXIF);
                dataOutputStream.writeInt(totalSize);
                break;
        }

        // Write TIFF Headers. See JEITA CP-3451C Section 4.5.2. Table 1.
        dataOutputStream.writeShort(mExifByteOrder == ByteOrder.BIG_ENDIAN
                ? BYTE_ALIGN_MM : BYTE_ALIGN_II);
        dataOutputStream.setByteOrder(mExifByteOrder);
        dataOutputStream.writeUnsignedShort(START_CODE);
        dataOutputStream.writeUnsignedInt(IFD_OFFSET);

        // Write IFD groups. See JEITA CP-3451C Section 4.5.8. Figure 9.
        for (int ifdType = 0; ifdType < EXIF_TAGS.length; ++ifdType) {
            if (!mAttributes[ifdType].isEmpty()) {
                // See JEITA CP-3451C Section 4.6.2: IFD structure.
                // Write entry count
                dataOutputStream.writeUnsignedShort(mAttributes[ifdType].size());

                // Write entry info
                int dataOffset = ifdOffsets[ifdType] + 2 + mAttributes[ifdType].size() * 12 + 4;
                for (Map.Entry entry : (Set<Map.Entry>) mAttributes[ifdType].entrySet()) {
                    // Convert tag name to tag number.
                    final ExifTag tag =
                            (ExifTag) sExifTagMapsForWriting[ifdType].get(entry.getKey());
                    final int tagNumber = tag.number;
                    final ExifAttribute attribute = (ExifAttribute) entry.getValue();
                    final int size = attribute.size();

                    dataOutputStream.writeUnsignedShort(tagNumber);
                    dataOutputStream.writeUnsignedShort(attribute.format);
                    dataOutputStream.writeInt(attribute.numberOfComponents);
                    if (size > 4) {
                        dataOutputStream.writeUnsignedInt(dataOffset);
                        dataOffset += size;
                    } else {
                        dataOutputStream.write(attribute.bytes);
                        // Fill zero up to 4 bytes
                        if (size < 4) {
                            for (int i = size; i < 4; ++i) {
                                dataOutputStream.writeByte(0);
                            }
                        }
                    }
                }

                // Write the next offset. It writes the offset of thumbnail IFD if there is one or
                // more tags in the thumbnail IFD when the current IFD is the primary image TIFF
                // IFD; Otherwise 0.
                if (ifdType == 0 && !mAttributes[IFD_TYPE_THUMBNAIL].isEmpty()) {
                    dataOutputStream.writeUnsignedInt(ifdOffsets[IFD_TYPE_THUMBNAIL]);
                } else {
                    dataOutputStream.writeUnsignedInt(0);
                }

                // Write values of data field exceeding 4 bytes after the next offset.
                for (Map.Entry entry : (Set<Map.Entry>) mAttributes[ifdType].entrySet()) {
                    ExifAttribute attribute = (ExifAttribute) entry.getValue();

                    if (attribute.bytes.length > 4) {
                        dataOutputStream.write(attribute.bytes, 0, attribute.bytes.length);
                    }
                }
            }
        }

        // Write thumbnail
        if (mHasThumbnail) {
            dataOutputStream.write(getThumbnailBytes());
        }

        // For WebP files, add a single padding byte at end if chunk size is odd
        if (mMimeType == IMAGE_TYPE_WEBP && totalSize % 2 == 1) {
            dataOutputStream.writeByte(0);
        }

        // Reset the byte order to big endian in order to write remaining parts of the JPEG file.
        dataOutputStream.setByteOrder(ByteOrder.BIG_ENDIAN);

        return totalSize;
    }

    /**
     * Determines the data format of EXIF entry value.
     *
     * @param entryValue The value to be determined.
     * @return Returns two data formats guessed as a pair in integer. If there is no two candidate
               data formats for the given entry value, returns {@code -1} in the second of the pair.
     */
    private static Pair<Integer, Integer> guessDataFormat(String entryValue) {
        // See TIFF 6.0 Section 2, "Image File Directory".
        // Take the first component if there are more than one component.
        if (entryValue.contains(",")) {
            String[] entryValues = entryValue.split(",");
            Pair<Integer, Integer> dataFormat = guessDataFormat(entryValues[0]);
            if (dataFormat.first == IFD_FORMAT_STRING) {
                return dataFormat;
            }
            for (int i = 1; i < entryValues.length; ++i) {
                final Pair<Integer, Integer> guessDataFormat = guessDataFormat(entryValues[i]);
                int first = -1, second = -1;
                if (Objects.equals(guessDataFormat.first, dataFormat.first)
                        || Objects.equals(guessDataFormat.second, dataFormat.first)) {
                    first = dataFormat.first;
                }
                if (dataFormat.second != -1
                        && (Objects.equals(guessDataFormat.first, dataFormat.second)
                        || Objects.equals(guessDataFormat.second, dataFormat.second))) {
                    second = dataFormat.second;
                }
                if (first == -1 && second == -1) {
                    return new Pair<>(IFD_FORMAT_STRING, -1);
                }
                if (first == -1) {
                    dataFormat = new Pair<>(second, -1);
                    continue;
                }
                if (second == -1) {
                    dataFormat = new Pair<>(first, -1);
                    continue;
                }
            }
            return dataFormat;
        }

        if (entryValue.contains("/")) {
            String[] rationalNumber = entryValue.split("/");
            if (rationalNumber.length == 2) {
                try {
                    long numerator = (long) Double.parseDouble(rationalNumber[0]);
                    long denominator = (long) Double.parseDouble(rationalNumber[1]);
                    if (numerator < 0L || denominator < 0L) {
                        return new Pair<>(IFD_FORMAT_SRATIONAL, -1);
                    }
                    if (numerator > Integer.MAX_VALUE || denominator > Integer.MAX_VALUE) {
                        return new Pair<>(IFD_FORMAT_URATIONAL, -1);
                    }
                    return new Pair<>(IFD_FORMAT_SRATIONAL, IFD_FORMAT_URATIONAL);
                } catch (NumberFormatException e)  {
                    // Ignored
                }
            }
            return new Pair<>(IFD_FORMAT_STRING, -1);
        }
        try {
            Long longValue = Long.parseLong(entryValue);
            if (longValue >= 0 && longValue <= 65535) {
                return new Pair<>(IFD_FORMAT_USHORT, IFD_FORMAT_ULONG);
            }
            if (longValue < 0) {
                return new Pair<>(IFD_FORMAT_SLONG, -1);
            }
            return new Pair<>(IFD_FORMAT_ULONG, -1);
        } catch (NumberFormatException e) {
            // Ignored
        }
        try {
            Double.parseDouble(entryValue);
            return new Pair<>(IFD_FORMAT_DOUBLE, -1);
        } catch (NumberFormatException e) {
            // Ignored
        }
        return new Pair<>(IFD_FORMAT_STRING, -1);
    }

    // An input stream to parse EXIF data area, which can be written in either little or big endian
    // order.
    private static class ByteOrderedDataInputStream extends InputStream implements DataInput {
        private static final ByteOrder LITTLE_ENDIAN = ByteOrder.LITTLE_ENDIAN;
        private static final ByteOrder BIG_ENDIAN = ByteOrder.BIG_ENDIAN;

        private DataInputStream mDataInputStream;
        private InputStream mInputStream;
        private ByteOrder mByteOrder = ByteOrder.BIG_ENDIAN;
        private final int mLength;
        private int mPosition;

        public ByteOrderedDataInputStream(InputStream in) throws IOException {
            this(in, ByteOrder.BIG_ENDIAN);
        }

        ByteOrderedDataInputStream(InputStream in, ByteOrder byteOrder) throws IOException {
            mInputStream = in;
            mDataInputStream = new DataInputStream(in);
            mLength = mDataInputStream.available();
            mPosition = 0;
            // TODO (b/142218289): Need to handle case where input stream does not support mark
            mDataInputStream.mark(mLength);
            mByteOrder = byteOrder;
        }

        public ByteOrderedDataInputStream(byte[] bytes) throws IOException {
            this(new ByteArrayInputStream(bytes));
        }

        public void setByteOrder(ByteOrder byteOrder) {
            mByteOrder = byteOrder;
        }

        public void seek(long byteCount) throws IOException {
            if (mPosition > byteCount) {
                mPosition = 0;
                mDataInputStream.reset();
                // TODO (b/142218289): Need to handle case where input stream does not support mark
                mDataInputStream.mark(mLength);
            } else {
                byteCount -= mPosition;
            }

            if (skipBytes((int) byteCount) != (int) byteCount) {
                throw new IOException("Couldn't seek up to the byteCount");
            }
        }

        public int peek() {
            return mPosition;
        }

        @Override
        public int available() throws IOException {
            return mDataInputStream.available();
        }

        @Override
        public int read() throws IOException {
            ++mPosition;
            return mDataInputStream.read();
        }

        @Override
        public int readUnsignedByte() throws IOException {
            ++mPosition;
            return mDataInputStream.readUnsignedByte();
        }

        @Override
        public String readLine() throws IOException {
            Log.d(TAG, "Currently unsupported");
            return null;
        }

        @Override
        public boolean readBoolean() throws IOException {
            ++mPosition;
            return mDataInputStream.readBoolean();
        }

        @Override
        public char readChar() throws IOException {
            mPosition += 2;
            return mDataInputStream.readChar();
        }

        @Override
        public String readUTF() throws IOException {
            mPosition += 2;
            return mDataInputStream.readUTF();
        }

        @Override
        public void readFully(byte[] buffer, int offset, int length) throws IOException {
            mPosition += length;
            if (mPosition > mLength) {
                throw new EOFException();
            }
            if (mDataInputStream.read(buffer, offset, length) != length) {
                throw new IOException("Couldn't read up to the length of buffer");
            }
        }

        @Override
        public void readFully(byte[] buffer) throws IOException {
            mPosition += buffer.length;
            if (mPosition > mLength) {
                throw new EOFException();
            }
            if (mDataInputStream.read(buffer, 0, buffer.length) != buffer.length) {
                throw new IOException("Couldn't read up to the length of buffer");
            }
        }

        @Override
        public byte readByte() throws IOException {
            ++mPosition;
            if (mPosition > mLength) {
                throw new EOFException();
            }
            int ch = mDataInputStream.read();
            if (ch < 0) {
                throw new EOFException();
            }
            return (byte) ch;
        }

        @Override
        public short readShort() throws IOException {
            mPosition += 2;
            if (mPosition > mLength) {
                throw new EOFException();
            }
            int ch1 = mDataInputStream.read();
            int ch2 = mDataInputStream.read();
            if ((ch1 | ch2) < 0) {
                throw new EOFException();
            }
            if (mByteOrder == LITTLE_ENDIAN) {
                return (short) ((ch2 << 8) + (ch1));
            } else if (mByteOrder == BIG_ENDIAN) {
                return (short) ((ch1 << 8) + (ch2));
            }
            throw new IOException("Invalid byte order: " + mByteOrder);
        }

        @Override
        public int readInt() throws IOException {
            mPosition += 4;
            if (mPosition > mLength) {
                throw new EOFException();
            }
            int ch1 = mDataInputStream.read();
            int ch2 = mDataInputStream.read();
            int ch3 = mDataInputStream.read();
            int ch4 = mDataInputStream.read();
            if ((ch1 | ch2 | ch3 | ch4) < 0) {
                throw new EOFException();
            }
            if (mByteOrder == LITTLE_ENDIAN) {
                return ((ch4 << 24) + (ch3 << 16) + (ch2 << 8) + ch1);
            } else if (mByteOrder == BIG_ENDIAN) {
                return ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + ch4);
            }
            throw new IOException("Invalid byte order: " + mByteOrder);
        }

        @Override
        public int skipBytes(int byteCount) throws IOException {
            int totalBytesToSkip = Math.min(byteCount, mLength - mPosition);
            int totalSkipped = 0;
            while (totalSkipped < totalBytesToSkip) {
                int skipped = mDataInputStream.skipBytes(totalBytesToSkip - totalSkipped);
                if (skipped > 0) {
                    totalSkipped += skipped;
                } else {
                    break;
                }
            }
            mPosition += totalSkipped;
            return totalSkipped;
        }

        public int readUnsignedShort() throws IOException {
            mPosition += 2;
            if (mPosition > mLength) {
                throw new EOFException();
            }
            int ch1 = mDataInputStream.read();
            int ch2 = mDataInputStream.read();
            if ((ch1 | ch2) < 0) {
                throw new EOFException();
            }
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

        @Override
        public long readLong() throws IOException {
            mPosition += 8;
            if (mPosition > mLength) {
                throw new EOFException();
            }
            int ch1 = mDataInputStream.read();
            int ch2 = mDataInputStream.read();
            int ch3 = mDataInputStream.read();
            int ch4 = mDataInputStream.read();
            int ch5 = mDataInputStream.read();
            int ch6 = mDataInputStream.read();
            int ch7 = mDataInputStream.read();
            int ch8 = mDataInputStream.read();
            if ((ch1 | ch2 | ch3 | ch4 | ch5 | ch6 | ch7 | ch8) < 0) {
                throw new EOFException();
            }
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

        @Override
        public float readFloat() throws IOException {
            return Float.intBitsToFloat(readInt());
        }

        @Override
        public double readDouble() throws IOException {
            return Double.longBitsToDouble(readLong());
        }

        public int getLength() {
            return mLength;
        }
    }

    // An output stream to write EXIF data area, which can be written in either little or big endian
    // order.
    private static class ByteOrderedDataOutputStream extends FilterOutputStream {
        final OutputStream mOutputStream;
        private ByteOrder mByteOrder;

        public ByteOrderedDataOutputStream(OutputStream out, ByteOrder byteOrder) {
            super(out);
            mOutputStream = out;
            mByteOrder = byteOrder;
        }

        public void setByteOrder(ByteOrder byteOrder) {
            mByteOrder = byteOrder;
        }

        public void write(byte[] bytes) throws IOException {
            mOutputStream.write(bytes);
        }

        public void write(byte[] bytes, int offset, int length) throws IOException {
            mOutputStream.write(bytes, offset, length);
        }

        public void writeByte(int val) throws IOException {
            mOutputStream.write(val);
        }

        public void writeShort(short val) throws IOException {
            if (mByteOrder == ByteOrder.LITTLE_ENDIAN) {
                mOutputStream.write((val >>> 0) & 0xFF);
                mOutputStream.write((val >>> 8) & 0xFF);
            } else if (mByteOrder == ByteOrder.BIG_ENDIAN) {
                mOutputStream.write((val >>> 8) & 0xFF);
                mOutputStream.write((val >>> 0) & 0xFF);
            }
        }

        public void writeInt(int val) throws IOException {
            if (mByteOrder == ByteOrder.LITTLE_ENDIAN) {
                mOutputStream.write((val >>> 0) & 0xFF);
                mOutputStream.write((val >>> 8) & 0xFF);
                mOutputStream.write((val >>> 16) & 0xFF);
                mOutputStream.write((val >>> 24) & 0xFF);
            } else if (mByteOrder == ByteOrder.BIG_ENDIAN) {
                mOutputStream.write((val >>> 24) & 0xFF);
                mOutputStream.write((val >>> 16) & 0xFF);
                mOutputStream.write((val >>> 8) & 0xFF);
                mOutputStream.write((val >>> 0) & 0xFF);
            }
        }

        public void writeUnsignedShort(int val) throws IOException {
            writeShort((short) val);
        }

        public void writeUnsignedInt(long val) throws IOException {
            writeInt((int) val);
        }
    }

    // Swaps image data based on image size
    private void swapBasedOnImageSize(@IfdType int firstIfdType, @IfdType int secondIfdType)
            throws IOException {
        if (mAttributes[firstIfdType].isEmpty() || mAttributes[secondIfdType].isEmpty()) {
            if (DEBUG) {
                Log.d(TAG, "Cannot perform swap since only one image data exists");
            }
            return;
        }

        ExifAttribute firstImageLengthAttribute =
                (ExifAttribute) mAttributes[firstIfdType].get(TAG_IMAGE_LENGTH);
        ExifAttribute firstImageWidthAttribute =
                (ExifAttribute) mAttributes[firstIfdType].get(TAG_IMAGE_WIDTH);
        ExifAttribute secondImageLengthAttribute =
                (ExifAttribute) mAttributes[secondIfdType].get(TAG_IMAGE_LENGTH);
        ExifAttribute secondImageWidthAttribute =
                (ExifAttribute) mAttributes[secondIfdType].get(TAG_IMAGE_WIDTH);

        if (firstImageLengthAttribute == null || firstImageWidthAttribute == null) {
            if (DEBUG) {
                Log.d(TAG, "First image does not contain valid size information");
            }
        } else if (secondImageLengthAttribute == null || secondImageWidthAttribute == null) {
            if (DEBUG) {
                Log.d(TAG, "Second image does not contain valid size information");
            }
        } else {
            int firstImageLengthValue = firstImageLengthAttribute.getIntValue(mExifByteOrder);
            int firstImageWidthValue = firstImageWidthAttribute.getIntValue(mExifByteOrder);
            int secondImageLengthValue = secondImageLengthAttribute.getIntValue(mExifByteOrder);
            int secondImageWidthValue = secondImageWidthAttribute.getIntValue(mExifByteOrder);

            if (firstImageLengthValue < secondImageLengthValue &&
                    firstImageWidthValue < secondImageWidthValue) {
                HashMap tempMap = mAttributes[firstIfdType];
                mAttributes[firstIfdType] = mAttributes[secondIfdType];
                mAttributes[secondIfdType] = tempMap;
            }
        }
    }

    private void replaceInvalidTags(@IfdType int ifdType, String invalidTag, String validTag) {
        if (!mAttributes[ifdType].isEmpty()) {
            if (mAttributes[ifdType].get(invalidTag) != null) {
                mAttributes[ifdType].put(validTag,
                        mAttributes[ifdType].get(invalidTag));
                mAttributes[ifdType].remove(invalidTag);
            }
        }
    }

    private boolean isSupportedFormatForSavingAttributes() {
        if (mIsSupportedFile && (mMimeType == IMAGE_TYPE_JPEG || mMimeType == IMAGE_TYPE_PNG
                || mMimeType == IMAGE_TYPE_WEBP || mMimeType == IMAGE_TYPE_DNG
                || mMimeType == IMAGE_TYPE_UNKNOWN)) {
            return true;
        }
        return false;
    }
}
