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

/**
 * ApplicationMediaCapabilities is an immutable class that encapsulates an application's
 * capabilities of handling advanced media formats.
 *
 * The ApplicationMediaCapabilities class is used by the platform to to represent an application's
 * media capabilities as defined in their manifest(TODO: Add link) in order to determine
 * whether modern media files need to be transcoded for that application (TODO: Add link).
 *
 * ApplicationMediaCapabilities objects can also be built by applications at runtime for use with
 * {@link ContentResolver#openTypedAssetFileDescriptor(Uri, String, Bundle)} to provide more
 * control over the transcoding that is built into the platform. ApplicationMediaCapabilities
 * provided by applications at runtime like this override the default manifest capabilities for that
 * media access.
 *
 * TODO(huang): Correct openTypedAssetFileDescriptor with the new API after it is added.
 * TODO(hkuang): Add a link to seamless transcoding detail when it is published
 * TODO(hkuang): Add code sample on how to build a capability object with MediaCodecList
 *
 * @hide
 */
public final class ApplicationMediaCapabilities implements Parcelable {
    private static final String TAG = "ApplicationMediaCapabilities";

    /** Whether handling of HEVC video is supported. */
    private final boolean mIsHevcSupported;

    /** Whether handling of slow-motion video is supported. */
    private final boolean mIsSlowMotionSupported;

    /** Whether handling of high dynamic range video is supported. */
    private final boolean mIsHdrSupported;

    private ApplicationMediaCapabilities(Builder b) {
        mIsHevcSupported = b.mIsHevcSupported;
        mIsHdrSupported = b.mIsHdrSupported;
        mIsSlowMotionSupported = b.mIsSlowMotionSupported;
    }

    /** Whether handling of HEVC video is supported. */
    public boolean isHevcSupported() {
        return mIsHevcSupported;
    }

    /** Whether handling of slow-motion video is supported. */
    public boolean isSlowMotionSupported() {
        return mIsSlowMotionSupported;
    }

    /** Whether handling of high dynamic range video is supported. */
    public boolean isHdrSupported() {
        return mIsHdrSupported;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mIsHevcSupported);
        dest.writeBoolean(mIsHdrSupported);
        dest.writeBoolean(mIsSlowMotionSupported);
    }

    /**
     * Builder class for {@link ApplicationMediaCapabilities} objects.
     * Use this class to configure and create an ApplicationMediaCapabilities instance. Builder
     * could be created from an existing ApplicationMediaCapabilities object, from a xml file or
     * MediaCodecList.
     * //TODO(hkuang): Add xml parsing support to the builder.
     */
    public final static class Builder {
        private boolean mIsHevcSupported = false;
        private boolean mIsHdrSupported = false;
        private boolean mIsSlowMotionSupported = false;

        /**
         * Constructs a new Builder with all the supports default to false.
         */
        public Builder() {
        }

        /**
         * Builds a {@link ApplicationMediaCapabilities} object.
         *
         * @return a new {@link ApplicationMediaCapabilities} instance successfully initialized
         * with all the parameters set on this <code>Builder</code>.
         * @throws UnsupportedOperationException if the parameters set on the
         *         <code>Builder</code> were incompatible, or if they are not supported by the
         *         device.
         */
        @NonNull
        public ApplicationMediaCapabilities build() {
            if (mIsHdrSupported && !mIsHevcSupported) {
                throw new UnsupportedOperationException("Must also support HEVC if support HDR.");
            }
            return new ApplicationMediaCapabilities(this);
        }

        /**
         * Sets whether supports HEVC encoded video.
         */
        @NonNull
        public Builder setHevcSupported(boolean hevcSupported) {
            mIsHevcSupported = hevcSupported;
            return this;
        }

        /**
         * Sets whether supports high dynamic range video.
         */
        @NonNull
        public Builder setHdrSupported(boolean hdrSupported) {
            mIsHdrSupported = hdrSupported;
            return this;
        }

        /**
         * Sets whether supports slow-motion video.
         */
        @NonNull
        public Builder setSlowMotionSupported(boolean slowMotionSupported) {
            mIsSlowMotionSupported = slowMotionSupported;
            return this;
        }
    }
}
