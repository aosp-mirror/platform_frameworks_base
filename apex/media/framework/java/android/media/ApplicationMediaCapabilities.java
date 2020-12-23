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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ApplicationMediaCapabilities is an immutable class that encapsulates an application's
 * capabilities for handling newer video codec format and media features.
 *
 * The ApplicationMediaCapabilities class is used by the platform to represent an application's
 * media capabilities as defined in their manifest(TODO: Add link) in order to determine
 * whether modern media files need to be transcoded for that application (TODO: Add link).
 *
 * ApplicationMediaCapabilities objects can also be built by applications at runtime for use with
 * {@link ContentResolver#openTypedAssetFileDescriptor(Uri, String, Bundle)} to provide more
 * control over the transcoding that is built into the platform. ApplicationMediaCapabilities
 * provided by applications at runtime like this override the default manifest capabilities for that
 * media access.
 *
 * <h3> Video Codec Support</h3>
 * Newer video codes include HEVC, VP9 and AV1. Application only needs to indicate their support
 * for newer format with this class as they are assumed to support older format like h.264.
 *
 * <h4>Capability of handling HDR(high dynamic range) video</h4>
 * There are four types of HDR video(Dolby-Vision, HDR10, HDR10+, HLG) supported by the platform,
 * application will only need to specify individual types they supported.
 *
 * <h4>Capability of handling Slow Motion video</h4>
 * There is no standard format for slow motion yet. If an application indicates support for slow
 * motion, it is application's responsibility to parse the slow motion videos using their own parser
 * or using support library.
 */
// TODO(huang): Correct openTypedAssetFileDescriptor with the new API after it is added.
// TODO(hkuang): Add a link to seamless transcoding detail when it is published
// TODO(hkuang): Add code sample on how to build a capability object with MediaCodecList
// TODO(hkuang): Add the support library page on parsing slow motion video.
public final class ApplicationMediaCapabilities implements Parcelable {
    private static final String TAG = "ApplicationMediaCapabilities";

    /** List of supported video codec mime types. */
    // TODO: init it with avc and mpeg4 as application is assuming to support them.
    private Set<String> mSupportedVideoMimeTypes = new HashSet<>();

    /** List of supported hdr types. */
    private Set<String> mSupportedHdrTypes = new HashSet<>();

    private boolean mIsSlowMotionSupported = false;

    private ApplicationMediaCapabilities(Builder b) {
        mSupportedVideoMimeTypes.addAll(b.getSupportedVideoMimeTypes());
        mSupportedHdrTypes.addAll(b.getSupportedHdrTypes());
        mIsSlowMotionSupported = b.mIsSlowMotionSupported;
    }

    /**
     * Query if an video codec is supported by the application.
     */
    public boolean isVideoMimeTypeSupported(
            @NonNull String videoMime) {
        return mSupportedVideoMimeTypes.contains(videoMime);
    }

    /**
     * Query if a hdr type is supported by the application.
     */
    public boolean isHdrTypeSupported(
            @NonNull @MediaFeature.MediaHdrType String hdrType) {
        return mSupportedHdrTypes.contains(hdrType);
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
        // Write out the supported hdr types.
        dest.writeInt(mSupportedHdrTypes.size());
        for (String cap : mSupportedHdrTypes) {
            dest.writeString(cap);
        }
        // Write out the supported slow motion.
        dest.writeBoolean(mIsSlowMotionSupported);
    }

    @Override
    public String toString() {
        String caps = new String(
                "Supported Video MimeTypes: " + mSupportedVideoMimeTypes.toString());
        caps += "Supported HDR types: " + mSupportedHdrTypes.toString();
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
                    // Parse supported hdr types.
                    count = in.readInt();
                    for (int readCount = 0; readCount < count; ++readCount) {
                        builder.addSupportedHdrType(in.readString());
                    }

                    boolean supported = in.readBoolean();
                    builder.setSlowMotionSupported(supported);

                    return builder.build();
                }

                public ApplicationMediaCapabilities[] newArray(int size) {
                    return new ApplicationMediaCapabilities[size];
                }
            };

    /*
     * Returns a list that contains all the video codec mime types supported by the application.
     * The list will be empty if no codecs are supported by the application.
     * @return List of supported video codec mime types.
     */
    @NonNull
    public List<String> getSupportedVideoMimeTypes() {
        return new ArrayList<>(mSupportedVideoMimeTypes);
    }

    /*
     * Returns a list that contains all hdr types supported by the application.
     * The list will be empty if no hdr types are supported by the application.
     * @return List of supported hdr types.
     */
    @NonNull
    public List<String> getSupportedHdrTypes() {
        return new ArrayList<>(mSupportedHdrTypes);
    }

    /*
     * Whether handling of slow-motion video is supported
     */
    public boolean isSlowMotionSupported() {
        return mIsSlowMotionSupported;
    }

    /**
     * Creates {@link ApplicationMediaCapabilities} from an xml.
     * @param xmlParser The underlying {@link XmlPullParser} that will read the xml.
     * @return An ApplicationMediaCapabilities object.
     * @throws UnsupportedOperationException if the capabilities in xml config are invalid or
     * incompatible.
     */
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
                        }
                        break;
                    case "HDR10":
                        if (isSupported) {
                            mSupportedHdrTypes.add(MediaFeature.HdrType.HDR10);
                        }
                        break;
                    case "HDR10Plus":
                        if (isSupported) {
                            mSupportedHdrTypes.add(MediaFeature.HdrType.HDR10_PLUS);
                        }
                        break;
                    case "Dolby-Vision":
                        if (isSupported) {
                            mSupportedHdrTypes.add(MediaFeature.HdrType.DOLBY_VISION);
                        }
                        break;
                    case "HLG":
                        if (isSupported) {
                            mSupportedHdrTypes.add(MediaFeature.HdrType.HLG);
                        }
                        break;
                    case "SlowMotion":
                        mIsSlowMotionSupported = isSupported;
                        break;
                    default:
                        throw new UnsupportedOperationException("Invalid format name " + name);
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
                    "Building ApplicationMediaCapabilities with: " + mSupportedHdrTypes.toString()
                            + " " + mSupportedVideoMimeTypes.toString() + " "
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
         * @throws UnsupportedOperationException if the codec mime type is not supported.
         * @throws IllegalArgumentException      if mime type is not valid.
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

        /**
         * Adds a supported hdr type.
         *
         * @param hdrType Supported hdr types. Must be one of the String defined in
         *                {@link MediaFeature.HdrType}.
         * @throws IllegalArgumentException if hdrType is not valid.
         */
        @NonNull
        public Builder addSupportedHdrType(
                @NonNull @MediaFeature.MediaHdrType String hdrType) {
            mSupportedHdrTypes.add(hdrType);
            return this;
        }

        private List<String> getSupportedHdrTypes() {
            return new ArrayList<>(mSupportedHdrTypes);
        }

        /**
         * Sets whether slow-motion video is supported.
         * If an application indicates support for slow-motion, it is application's responsibility
         * to parse the slow-motion videos using their own parser or using support library.
         * @see android.media.MediaFormat#KEY_SLOW_MOTION_MARKERS
         */
        @NonNull
        public Builder setSlowMotionSupported(boolean slowMotionSupported) {
            mIsSlowMotionSupported = slowMotionSupported;
            return this;
        }
    }
}
