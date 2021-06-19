/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.modules.annotation.MinSdk;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 ApplicationMediaCapabilities is an immutable class that encapsulates an application's capabilities
 for handling newer video codec format and media features.

 <p>
 Android 12 introduces Compatible media transcoding feature.  See
 <a href="https://developer.android.com/about/versions/12/features#compatible_media_transcoding">
 Compatible media transcoding</a>. By default, Android assumes apps can support playback of all
 media formats. Apps that would like to request that media be transcoded into a more compatible
 format should declare their media capabilities in a media_capabilities.xml resource file and add it
 as a property tag in the AndroidManifest.xml file. Here is a example:
 <pre>
 {@code
 <media-capabilities xmlns:android="http://schemas.android.com/apk/res/android">
     <format android:name="HEVC" supported="true"/>
     <format android:name="HDR10" supported="false"/>
     <format android:name="HDR10Plus" supported="false"/>
 </media-capabilities>
 }
 </pre>
 The ApplicationMediaCapabilities class is generated from this xml and used by the platform to
 represent an application's media capabilities in order to determine whether modern media files need
 to be transcoded for that application.
 </p>

 <p>
 ApplicationMediaCapabilities objects can also be built by applications at runtime for use with
 {@link ContentResolver#openTypedAssetFileDescriptor(Uri, String, Bundle)} to provide more
 control over the transcoding that is built into the platform. ApplicationMediaCapabilities
 provided by applications at runtime like this override the default manifest capabilities for that
 media access.The object could be build either through {@link #createFromXml(XmlPullParser)} or
 through the builder class {@link ApplicationMediaCapabilities.Builder}

 <h3> Video Codec Support</h3>
 <p>
 Newer video codes include HEVC, VP9 and AV1. Application only needs to indicate their support
 for newer format with this class as they are assumed to support older format like h.264.

 <h3>Capability of handling HDR(high dynamic range) video</h3>
 <p>
 There are four types of HDR video(Dolby-Vision, HDR10, HDR10+, HLG) supported by the platform,
 application will only need to specify individual types they supported.
 */
@MinSdk(Build.VERSION_CODES.S)
public final class ApplicationMediaCapabilities implements Parcelable {
    private static final String TAG = "ApplicationMediaCapabilities";

    /** List of supported video codec mime types. */
    private Set<String> mSupportedVideoMimeTypes = new HashSet<>();

    /** List of unsupported video codec mime types. */
    private Set<String> mUnsupportedVideoMimeTypes = new HashSet<>();

    /** List of supported hdr types. */
    private Set<String> mSupportedHdrTypes = new HashSet<>();

    /** List of unsupported hdr types. */
    private Set<String> mUnsupportedHdrTypes = new HashSet<>();

    private boolean mIsSlowMotionSupported = false;

    private ApplicationMediaCapabilities(Builder b) {
        mSupportedVideoMimeTypes.addAll(b.getSupportedVideoMimeTypes());
        mUnsupportedVideoMimeTypes.addAll(b.getUnsupportedVideoMimeTypes());
        mSupportedHdrTypes.addAll(b.getSupportedHdrTypes());
        mUnsupportedHdrTypes.addAll(b.getUnsupportedHdrTypes());
        mIsSlowMotionSupported = b.mIsSlowMotionSupported;
    }

    /**
     * Query if a video codec format is supported by the application.
     * <p>
     * If the application has not specified supporting the format or not, this will return false.
     * Use {@link #isFormatSpecified(String)} to query if a format is specified or not.
     *
     * @param videoMime The mime type of the video codec format. Must be the one used in
     * {@link MediaFormat#KEY_MIME}.
     * @return true if application supports the video codec format, false otherwise.
     */
    public boolean isVideoMimeTypeSupported(
            @NonNull String videoMime) {
        if (mSupportedVideoMimeTypes.contains(videoMime.toLowerCase())) {
            return true;
        }
        return false;
    }

    /**
     * Query if a HDR type is supported by the application.
     * <p>
     * If the application has not specified supporting the format or not, this will return false.
     * Use {@link #isFormatSpecified(String)} to query if a format is specified or not.
     *
     * @param hdrType The type of the HDR format.
     * @return true if application supports the HDR format, false otherwise.
     */
    public boolean isHdrTypeSupported(
            @NonNull @MediaFeature.MediaHdrType String hdrType) {
        if (mSupportedHdrTypes.contains(hdrType)) {
            return true;
        }
        return false;
    }

    /**
     * Query if a format is specified by the application.
     * <p>
     * The format could be either the video format or the hdr format.
     *
     * @param format The name of the format.
     * @return true if application specifies the format, false otherwise.
     */
    public boolean isFormatSpecified(@NonNull String format) {
        if (mSupportedVideoMimeTypes.contains(format) || mUnsupportedVideoMimeTypes.contains(format)
                || mSupportedHdrTypes.contains(format) || mUnsupportedHdrTypes.contains(format)) {
            return true;

        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // Write out the supported video mime types.
        dest.writeInt(mSupportedVideoMimeTypes.size());
        for (String cap : mSupportedVideoMimeTypes) {
            dest.writeString(cap);
        }
        // Write out the unsupported video mime types.
        dest.writeInt(mUnsupportedVideoMimeTypes.size());
        for (String cap : mUnsupportedVideoMimeTypes) {
            dest.writeString(cap);
        }
        // Write out the supported hdr types.
        dest.writeInt(mSupportedHdrTypes.size());
        for (String cap : mSupportedHdrTypes) {
            dest.writeString(cap);
        }
        // Write out the unsupported hdr types.
        dest.writeInt(mUnsupportedHdrTypes.size());
        for (String cap : mUnsupportedHdrTypes) {
            dest.writeString(cap);
        }
        // Write out the supported slow motion.
        dest.writeBoolean(mIsSlowMotionSupported);
    }

    @Override
    public String toString() {
        String caps = new String(
                "Supported Video MimeTypes: " + mSupportedVideoMimeTypes.toString());
        caps += "Unsupported Video MimeTypes: " + mUnsupportedVideoMimeTypes.toString();
        caps += "Supported HDR types: " + mSupportedHdrTypes.toString();
        caps += "Unsupported HDR types: " + mUnsupportedHdrTypes.toString();
        caps += "Supported slow motion: " + mIsSlowMotionSupported;
        return caps;
    }

    @NonNull
    public static final Creator<ApplicationMediaCapabilities> CREATOR =
            new Creator<ApplicationMediaCapabilities>() {
                public ApplicationMediaCapabilities createFromParcel(Parcel in) {
                    ApplicationMediaCapabilities.Builder builder =
                            new ApplicationMediaCapabilities.Builder();

                    // Parse supported video codec mime types.
                    int count = in.readInt();
                    for (int readCount = 0; readCount < count; ++readCount) {
                        builder.addSupportedVideoMimeType(in.readString());
                    }

                    // Parse unsupported video codec mime types.
                    count = in.readInt();
                    for (int readCount = 0; readCount < count; ++readCount) {
                        builder.addUnsupportedVideoMimeType(in.readString());
                    }

                    // Parse supported hdr types.
                    count = in.readInt();
                    for (int readCount = 0; readCount < count; ++readCount) {
                        builder.addSupportedHdrType(in.readString());
                    }

                    // Parse unsupported hdr types.
                    count = in.readInt();
                    for (int readCount = 0; readCount < count; ++readCount) {
                        builder.addUnsupportedHdrType(in.readString());
                    }

                    boolean supported = in.readBoolean();
                    builder.setSlowMotionSupported(supported);

                    return builder.build();
                }

                public ApplicationMediaCapabilities[] newArray(int size) {
                    return new ApplicationMediaCapabilities[size];
                }
            };

    /**
     * Query the video codec mime types supported by the application.
     * @return List of supported video codec mime types. The list will be empty if there are none.
     */
    @NonNull
    public List<String> getSupportedVideoMimeTypes() {
        return new ArrayList<>(mSupportedVideoMimeTypes);
    }

    /**
     * Query the video codec mime types that are not supported by the application.
     * @return List of unsupported video codec mime types. The list will be empty if there are none.
     */
    @NonNull
    public List<String> getUnsupportedVideoMimeTypes() {
        return new ArrayList<>(mUnsupportedVideoMimeTypes);
    }

    /**
     * Query all hdr types that are supported by the application.
     * @return List of supported hdr types. The list will be empty if there are none.
     */
    @NonNull
    public List<String> getSupportedHdrTypes() {
        return new ArrayList<>(mSupportedHdrTypes);
    }

    /**
     * Query all hdr types that are not supported by the application.
     * @return List of unsupported hdr types. The list will be empty if there are none.
     */
    @NonNull
    public List<String> getUnsupportedHdrTypes()  {
        return new ArrayList<>(mUnsupportedHdrTypes);
    }

    /**
     * Whether handling of slow-motion video is supported
     * @hide
     */
    public boolean isSlowMotionSupported() {
        return mIsSlowMotionSupported;
    }

    /**
     * Creates {@link ApplicationMediaCapabilities} from an xml.
     *
     * The xml's syntax is the same as the media_capabilities.xml used by the AndroidManifest.xml.
     * <p> Here is an example:
     *
     * <pre>
     * {@code
     * <media-capabilities xmlns:android="http://schemas.android.com/apk/res/android">
     *     <format android:name="HEVC" supported="true"/>
     *     <format android:name="HDR10" supported="false"/>
     *     <format android:name="HDR10Plus" supported="false"/>
     * </media-capabilities>
     * }
     * </pre>
     * <p>
     *
     * @param xmlParser The underlying {@link XmlPullParser} that will read the xml.
     * @return An ApplicationMediaCapabilities object.
     * @throws UnsupportedOperationException if the capabilities in xml config are invalid or
     * incompatible.
     */
    // TODO: Add developer.android.com link for the format of the xml.
    @NonNull
    public static ApplicationMediaCapabilities createFromXml(@NonNull XmlPullParser xmlParser) {
        ApplicationMediaCapabilities.Builder builder = new ApplicationMediaCapabilities.Builder();
        builder.parseXml(xmlParser);
        return builder.build();
    }

    /**
     * Builder class for {@link ApplicationMediaCapabilities} objects.
     * Use this class to configure and create an ApplicationMediaCapabilities instance. Builder
     * could be created from an existing ApplicationMediaCapabilities object, from a xml file or
     * MediaCodecList.
     * //TODO(hkuang): Add xml parsing support to the builder.
     */
    public final static class Builder {
        /** List of supported video codec mime types. */
        private Set<String> mSupportedVideoMimeTypes = new HashSet<>();

        /** List of supported hdr types. */
        private Set<String> mSupportedHdrTypes = new HashSet<>();

        /** List of unsupported video codec mime types. */
        private Set<String> mUnsupportedVideoMimeTypes = new HashSet<>();

        /** List of unsupported hdr types. */
        private Set<String> mUnsupportedHdrTypes = new HashSet<>();

        private boolean mIsSlowMotionSupported = false;

        /* Map to save the format read from the xml. */
        private Map<String, Boolean> mFormatSupportedMap =  new HashMap<String, Boolean>();

        /**
         * Constructs a new Builder with all the supports default to false.
         */
        public Builder() {
        }

        private void parseXml(@NonNull XmlPullParser xmlParser)
                throws UnsupportedOperationException {
            if (xmlParser == null) {
                throw new IllegalArgumentException("XmlParser must not be null");
            }

            try {
                while (xmlParser.next() != XmlPullParser.START_TAG) {
                    continue;
                }

                // Validates the tag is "media-capabilities".
                if (!xmlParser.getName().equals("media-capabilities")) {
                    throw new UnsupportedOperationException("Invalid tag");
                }

                xmlParser.next();
                while (xmlParser.getEventType() != XmlPullParser.END_TAG) {
                    while (xmlParser.getEventType() != XmlPullParser.START_TAG) {
                        if (xmlParser.getEventType() == XmlPullParser.END_DOCUMENT) {
                            return;
                        }
                        xmlParser.next();
                    }

                    // Validates the tag is "format".
                    if (xmlParser.getName().equals("format")) {
                        parseFormatTag(xmlParser);
                    } else {
                        throw new UnsupportedOperationException("Invalid tag");
                    }
                    while (xmlParser.getEventType() != XmlPullParser.END_TAG) {
                        xmlParser.next();
                    }
                    xmlParser.next();
                }
            } catch (XmlPullParserException xppe) {
                throw new UnsupportedOperationException("Ill-formatted xml file");
            } catch (java.io.IOException ioe) {
                throw new UnsupportedOperationException("Unable to read xml file");
            }
        }

        private void parseFormatTag(XmlPullParser xmlParser) {
            String name = null;
            String supported = null;
            for (int i = 0; i < xmlParser.getAttributeCount(); i++) {
                String attrName = xmlParser.getAttributeName(i);
                if (attrName.equals("name")) {
                    name = xmlParser.getAttributeValue(i);
                } else if (attrName.equals("supported")) {
                    supported = xmlParser.getAttributeValue(i);
                } else {
                    throw new UnsupportedOperationException("Invalid attribute name " + attrName);
                }
            }

            if (name != null && supported != null) {
                if (!supported.equals("true") && !supported.equals("false")) {
                    throw new UnsupportedOperationException(
                            ("Supported value must be either true or false"));
                }
                boolean isSupported = Boolean.parseBoolean(supported);

                // Check if the format is already found before.
                if (mFormatSupportedMap.get(name) != null && mFormatSupportedMap.get(name)
                        != isSupported) {
                    throw new UnsupportedOperationException(
                            "Format: " + name + " has conflict supported value");
                }

                switch (name) {
                    case "HEVC":
                        if (isSupported) {
                            mSupportedVideoMimeTypes.add(MediaFormat.MIMETYPE_VIDEO_HEVC);
                        } else {
                            mUnsupportedVideoMimeTypes.add(MediaFormat.MIMETYPE_VIDEO_HEVC);
                        }
                        break;
                    case "VP9":
                        if (isSupported) {
                            mSupportedVideoMimeTypes.add(MediaFormat.MIMETYPE_VIDEO_VP9);
                        } else {
                            mUnsupportedVideoMimeTypes.add(MediaFormat.MIMETYPE_VIDEO_VP9);
                        }
                        break;
                    case "AV1":
                        if (isSupported) {
                            mSupportedVideoMimeTypes.add(MediaFormat.MIMETYPE_VIDEO_AV1);
                        } else {
                            mUnsupportedVideoMimeTypes.add(MediaFormat.MIMETYPE_VIDEO_AV1);
                        }
                        break;
                    case "HDR10":
                        if (isSupported) {
                            mSupportedHdrTypes.add(MediaFeature.HdrType.HDR10);
                        } else {
                            mUnsupportedHdrTypes.add(MediaFeature.HdrType.HDR10);
                        }
                        break;
                    case "HDR10Plus":
                        if (isSupported) {
                            mSupportedHdrTypes.add(MediaFeature.HdrType.HDR10_PLUS);
                        } else {
                            mUnsupportedHdrTypes.add(MediaFeature.HdrType.HDR10_PLUS);
                        }
                        break;
                    case "Dolby-Vision":
                        if (isSupported) {
                            mSupportedHdrTypes.add(MediaFeature.HdrType.DOLBY_VISION);
                        } else {
                            mUnsupportedHdrTypes.add(MediaFeature.HdrType.DOLBY_VISION);
                        }
                        break;
                    case "HLG":
                        if (isSupported) {
                            mSupportedHdrTypes.add(MediaFeature.HdrType.HLG);
                        } else {
                            mUnsupportedHdrTypes.add(MediaFeature.HdrType.HLG);
                        }
                        break;
                    case "SlowMotion":
                        mIsSlowMotionSupported = isSupported;
                        break;
                    default:
                        Log.w(TAG, "Invalid format name " + name);
                }
                // Save the name and isSupported into the map for validate later.
                mFormatSupportedMap.put(name, isSupported);
            } else {
                throw new UnsupportedOperationException(
                        "Format name and supported must both be specified");
            }
        }

        /**
         * Builds a {@link ApplicationMediaCapabilities} object.
         *
         * @return a new {@link ApplicationMediaCapabilities} instance successfully initialized
         * with all the parameters set on this <code>Builder</code>.
         * @throws UnsupportedOperationException if the parameters set on the
         *                                       <code>Builder</code> were incompatible, or if they
         *                                       are not supported by the
         *                                       device.
         */
        @NonNull
        public ApplicationMediaCapabilities build() {
            Log.d(TAG,
                    "Building ApplicationMediaCapabilities with: (Supported HDR: "
                            + mSupportedHdrTypes.toString() + " Unsupported HDR: "
                            + mUnsupportedHdrTypes.toString() + ") (Supported Codec: "
                            + " " + mSupportedVideoMimeTypes.toString() + " Unsupported Codec:"
                            + mUnsupportedVideoMimeTypes.toString() + ") "
                            + mIsSlowMotionSupported);

            // If hdr is supported, application must also support hevc.
            if (!mSupportedHdrTypes.isEmpty() && !mSupportedVideoMimeTypes.contains(
                    MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                throw new UnsupportedOperationException("Only support HEVC mime type");
            }
            return new ApplicationMediaCapabilities(this);
        }

        /**
         * Adds a supported video codec mime type.
         *
         * @param codecMime Supported codec mime types. Must be one of the mime type defined
         *                  in {@link MediaFormat}.
         * @throws IllegalArgumentException if mime type is not valid.
         */
        @NonNull
        public Builder addSupportedVideoMimeType(
                @NonNull String codecMime) {
            mSupportedVideoMimeTypes.add(codecMime);
            return this;
        }

        private List<String> getSupportedVideoMimeTypes() {
            return new ArrayList<>(mSupportedVideoMimeTypes);
        }

        private boolean isValidVideoCodecMimeType(@NonNull String codecMime) {
            if (!codecMime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    && !codecMime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_VP9)
                    && !codecMime.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_AV1)) {
                return false;
            }
            return true;
        }

        /**
         * Adds an unsupported video codec mime type.
         *
         * @param codecMime Unsupported codec mime type. Must be one of the mime type defined
         *                  in {@link MediaFormat}.
         * @throws IllegalArgumentException if mime type is not valid.
         */
        @NonNull
        public Builder addUnsupportedVideoMimeType(
                @NonNull String codecMime) {
            if (!isValidVideoCodecMimeType(codecMime)) {
                throw new IllegalArgumentException("Invalid codec mime type: " + codecMime);
            }
            mUnsupportedVideoMimeTypes.add(codecMime);
            return this;
        }

        private List<String> getUnsupportedVideoMimeTypes() {
            return new ArrayList<>(mUnsupportedVideoMimeTypes);
        }

        /**
         * Adds a supported hdr type.
         *
         * @param hdrType Supported hdr type. Must be one of the String defined in
         *                {@link MediaFeature.HdrType}.
         * @throws IllegalArgumentException if hdrType is not valid.
         */
        @NonNull
        public Builder addSupportedHdrType(
                @NonNull @MediaFeature.MediaHdrType String hdrType) {
            if (!isValidVideoCodecHdrType(hdrType)) {
                throw new IllegalArgumentException("Invalid hdr type: " + hdrType);
            }
            mSupportedHdrTypes.add(hdrType);
            return this;
        }

        private List<String> getSupportedHdrTypes() {
            return new ArrayList<>(mSupportedHdrTypes);
        }

        private boolean isValidVideoCodecHdrType(@NonNull String hdrType) {
            if (!hdrType.equals(MediaFeature.HdrType.DOLBY_VISION)
                    && !hdrType.equals(MediaFeature.HdrType.HDR10)
                    && !hdrType.equals(MediaFeature.HdrType.HDR10_PLUS)
                    && !hdrType.equals(MediaFeature.HdrType.HLG)) {
                return false;
            }
            return true;
        }

        /**
         * Adds an unsupported hdr type.
         *
         * @param hdrType Unsupported hdr type. Must be one of the String defined in
         *                {@link MediaFeature.HdrType}.
         * @throws IllegalArgumentException if hdrType is not valid.
         */
        @NonNull
        public Builder addUnsupportedHdrType(
                @NonNull @MediaFeature.MediaHdrType String hdrType) {
            if (!isValidVideoCodecHdrType(hdrType)) {
                throw new IllegalArgumentException("Invalid hdr type: " + hdrType);
            }
            mUnsupportedHdrTypes.add(hdrType);
            return this;
        }

        private List<String> getUnsupportedHdrTypes() {
            return new ArrayList<>(mUnsupportedHdrTypes);
        }

        /**
         * Sets whether slow-motion video is supported.
         * If an application indicates support for slow-motion, it is application's responsibility
         * to parse the slow-motion videos using their own parser or using support library.
         * @see android.media.MediaFormat#KEY_SLOW_MOTION_MARKERS
         * @hide
         */
        @NonNull
        public Builder setSlowMotionSupported(boolean slowMotionSupported) {
            mIsSlowMotionSupported = slowMotionSupported;
            return this;
        }
    }
}
