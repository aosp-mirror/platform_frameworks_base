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

package android.telephony.ims;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Defines a mapping between a local identifier and a {@link Uri} which identifies an RTP header
 * extension.
 * <p>
 * According to RFC8285, SDP (Session Description Protocol) signalling for a call provides a means
 * for the supported RTP header extensions for a call to be negotiated at call initiation time.
 * The types of RTP header extensions potentially usable in a session are identified by a local
 * identifier ({@link #getLocalIdentifier()}) when RTP header extensions are present on RTP packets.
 * A {@link Uri} ({@link #getUri()}) provides a unique identifier for the RTP header extension
 * format which parties in a call can use to identify supported RTP header extensions.
 * @hide
 */
@SystemApi
public final class RtpHeaderExtensionType implements Parcelable {
    private int mLocalIdentifier;
    private Uri mUri;

    /**
     * Create a new RTP header extension type.
     * @param localIdentifier the local identifier.
     * @param uri the {@link Uri} identifying the RTP header extension type.
     * @throws IllegalArgumentException if {@code localIdentifier} is out of the expected range.
     * @throws NullPointerException if {@code uri} is null.
     */
    public RtpHeaderExtensionType(@IntRange(from = 1, to = 14) int localIdentifier,
            @NonNull Uri uri) {
        if (localIdentifier < 1 || localIdentifier > 13) {
            throw new IllegalArgumentException("localIdentifier must be in range 1-14");
        }
        if (uri == null) {
            throw new NullPointerException("uri is required.");
        }
        mLocalIdentifier = localIdentifier;
        mUri = uri;
    }

    private RtpHeaderExtensionType(Parcel in) {
        mLocalIdentifier = in.readInt();
        mUri = in.readParcelable(Uri.class.getClassLoader());
    }

    public static final @NonNull Creator<RtpHeaderExtensionType> CREATOR =
            new Creator<RtpHeaderExtensionType>() {
                @Override
                public RtpHeaderExtensionType createFromParcel(@NonNull Parcel in) {
                    return new RtpHeaderExtensionType(in);
                }

                @Override
                public @NonNull RtpHeaderExtensionType[] newArray(int size) {
                    return new RtpHeaderExtensionType[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mLocalIdentifier);
        dest.writeParcelable(mUri, flags);
    }

    /**
     * The local identifier for this RTP header extension type.
     * <p>
     * {@link RtpHeaderExtension}s which indicate a {@link RtpHeaderExtension#getLocalIdentifier()}
     * matching this local identifier will have the format specified by {@link #getUri()}.
     * <p>
     * Per RFC8285, the extension ID is a value in the range 1-14 (0 is reserved for padding and
     * 15 is reserved for the one-byte header form.
     *
     * @return The local identifier associated with this {@link #getUri()}.
     */
    public @IntRange(from = 1, to = 14) int getLocalIdentifier() {
        return mLocalIdentifier;
    }

    /**
     * A {@link Uri} which identifies the format of the RTP extension header.
     * <p>
     * According to RFC8285 section 5, URIs MUST be absolute and SHOULD contain a month/date pair
     * in the form mmyyyy to indicate versioning of the extension.  Extension headers defined in an
     * RFC are typically defined using URNs starting with {@code urn:ietf:params:rtp-hdrext:}.
     * For example, RFC6464 defines {@code urn:ietf:params:rtp-hdrext:ssrc-audio-level} which is an
     * RTP header extension for communicating client to mixer audio level indications.
     *
     * @return A unique {@link Uri} identifying the format of the RTP extension header.
     */
    public @NonNull Uri getUri() {
        return mUri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RtpHeaderExtensionType that = (RtpHeaderExtensionType) o;
        return mLocalIdentifier == that.mLocalIdentifier
                && mUri.equals(that.mUri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLocalIdentifier, mUri);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("RtpHeaderExtensionType{mLocalIdentifier=");
        sb.append(mLocalIdentifier);
        sb.append(", mUri=");
        sb.append(mUri);
        sb.append("}");

        return sb.toString();
    }
}
