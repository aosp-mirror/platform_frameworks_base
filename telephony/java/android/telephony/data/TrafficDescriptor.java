/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A traffic descriptor, as defined in 3GPP TS 24.526 Section 5.2. It is used for UE Route Selection
 * Policy(URSP) traffic matching as described in 3GPP TS 24.526 Section 4.2.2. It includes an
 * optional Data Network Name(DNN), which, if present, must be used for traffic matching; it does
 * not specify the end point to be used for the data call.
 */
public final class TrafficDescriptor implements Parcelable {
    /**
     * The OS/App id
     *
     * @hide
     */
    public static final class OsAppId {
        /**
         * OSId for "Android", using UUID version 5 with namespace ISO OSI.
         * Prepended to the OsAppId in TrafficDescriptor to use for URSP matching.
         */
        public static final UUID ANDROID_OS_ID =
                UUID.fromString("97a498e3-fc92-5c94-8986-0333d06e4e47");

        /**
         * Allowed app ids.
         */
        // The following app ids are the only apps id Android supports. OEMs or vendors are
        // prohibited to modify/extend the allowed list, especially passing the real package name to
        // the network.
        private static final Set<String> ALLOWED_APP_IDS = Set.of(
                "ENTERPRISE", "PRIORITIZE_LATENCY", "PRIORITIZE_BANDWIDTH", "CBS"
        );

        /** OS id in UUID format. */
        private final @NonNull UUID mOsId;

        /**
         * App id in string format. Note that Android will not allow use specific app id. This must
         * be a category/capability identifier.
         */
        private final @NonNull String mAppId;

        /**
         * The differentiator when multiple traffic descriptor has the same OS and app id. Must be
         * greater than 1.
         */
        private final int mDifferentiator;

        /**
         * Constructor
         *
         * @param osId OS id in UUID format.
         * @param appId App id in string format. Note that Android will not allow use specific app
         * id. This must be a category/capability identifier.
         */
        public OsAppId(@NonNull UUID osId, @NonNull String appId) {
            this(osId, appId, 1);
        }

        /**
         * Constructor
         *
         * @param osId OS id in UUID format.
         * @param appId App id in string format. Note that Android will not allow use specific app
         * id. This must be a category/capability identifier.
         * @param differentiator The differentiator when multiple traffic descriptor has the same
         * OS and app id. Must be greater than 0.
         */
        public OsAppId(@NonNull UUID osId, @NonNull String appId, int differentiator) {
            Objects.requireNonNull(osId);
            Objects.requireNonNull(appId);
            if (differentiator < 1) {
                throw new IllegalArgumentException("Invalid differentiator " + differentiator);
            }

            mOsId = osId;
            mAppId = appId;
            mDifferentiator = differentiator;
        }

        /**
         * Constructor from raw byte array.
         *
         * @param rawOsAppId The raw OS/App id.
         */
        public OsAppId(@NonNull byte[] rawOsAppId) {
            try {
                ByteBuffer bb = ByteBuffer.wrap(rawOsAppId);
                // OS id is the first 16 bytes.
                mOsId = new UUID(bb.getLong(), bb.getLong());
                // App id length is 1 byte.
                int appIdLen = bb.get();
                // The remaining is the app id + differentiator.
                byte[] appIdAndDifferentiator = new byte[appIdLen];
                bb.get(appIdAndDifferentiator, 0, appIdLen);
                // Extract trailing numbers, for example, "ENTERPRISE", "ENTERPRISE3".
                String appIdAndDifferentiatorStr = new String(appIdAndDifferentiator);
                Pattern pattern = Pattern.compile("[^0-9]+([0-9]+)$");
                Matcher matcher = pattern.matcher(new String(appIdAndDifferentiator));
                if (matcher.find()) {
                    mDifferentiator = Integer.parseInt(matcher.group(1));
                    mAppId = appIdAndDifferentiatorStr.replace(matcher.group(1), "");
                } else {
                    mDifferentiator = 1;
                    mAppId = appIdAndDifferentiatorStr;
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to decode " + (rawOsAppId != null
                        ? new BigInteger(1, rawOsAppId).toString(16) : null));
            }
        }

        /**
         * @return The OS id in UUID format.
         */
        public @NonNull UUID getOsId() {
            return mOsId;
        }

        /**
         * @return App id in string format. Note that Android will not allow use specific app id.
         * This must be a category/capability identifier.
         */
        public @NonNull String getAppId() {
            return mAppId;
        }

        /**
         * @return The differentiator when multiple traffic descriptor has the same OS and app id.
         * Must be greater than 1.
         */
        public int getDifferentiator() {
            return mDifferentiator;
        }

        /**
         * @return OS/App id in raw byte format.
         */
        public @NonNull byte[] getBytes() {
            byte[] osAppId = (mAppId + (mDifferentiator > 1 ? mDifferentiator : "")).getBytes();
            // 16 bytes for UUID, 1 byte for length of osAppId, and up to 255 bytes for osAppId
            ByteBuffer bb = ByteBuffer.allocate(16 + 1 + osAppId.length);
            bb.putLong(mOsId.getMostSignificantBits());
            bb.putLong(mOsId.getLeastSignificantBits());
            bb.put((byte) osAppId.length);
            bb.put(osAppId);
            return bb.array();
        }

        @Override
        public String toString() {
            return "[OsAppId: OS=" + mOsId + ", App=" + mAppId + ", differentiator="
                    + mDifferentiator + ", raw="
                    + new BigInteger(1, getBytes()).toString(16) + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OsAppId osAppId = (OsAppId) o;
            return mDifferentiator == osAppId.mDifferentiator && mOsId.equals(osAppId.mOsId)
                    && mAppId.equals(osAppId.mAppId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mOsId, mAppId, mDifferentiator);
        }
    }

    private final String mDnn;
    private final OsAppId mOsAppId;

    private TrafficDescriptor(@NonNull Parcel in) {
        mDnn = in.readString();
        byte[] osAppIdBytes = in.createByteArray();
        OsAppId osAppId = null;
        if (osAppIdBytes != null) {
            osAppId = new OsAppId(osAppIdBytes);
        }
        mOsAppId = osAppId;

        enforceAllowedIds();
    }

    /**
     * Create a traffic descriptor, as defined in 3GPP TS 24.526 Section 5.2
     * @param dnn optional DNN, which must be used for traffic matching, if present
     * @param osAppIdRawBytes Raw bytes of OsId + osAppId of the traffic descriptor
     *
     * @hide
     */
    public TrafficDescriptor(String dnn, @Nullable byte[] osAppIdRawBytes) {
        mDnn = dnn;
        OsAppId osAppId = null;
        if (osAppIdRawBytes != null) {
            osAppId = new OsAppId(osAppIdRawBytes);
        }
        mOsAppId = osAppId;

        enforceAllowedIds();
    }

    /**
     * Enforce the OS id and app id are in the allowed list.
     *
     * @throws IllegalArgumentException if ids are not allowed.
     */
    private void enforceAllowedIds() {
        if (mOsAppId != null && !mOsAppId.getOsId().equals(OsAppId.ANDROID_OS_ID)) {
            throw new IllegalArgumentException("OS id " + mOsAppId.getOsId() + " does not match "
                    + OsAppId.ANDROID_OS_ID);
        }

        if (mOsAppId != null && !OsAppId.ALLOWED_APP_IDS.contains(mOsAppId.getAppId())) {
            throw new IllegalArgumentException("Illegal app id " + mOsAppId.getAppId()
                    + ". Only allowing one of the following " + OsAppId.ALLOWED_APP_IDS);
        }
    }

    /**
     * DNN stands for Data Network Name and represents an APN as defined in 3GPP TS 23.003.
     * @return the DNN of this traffic descriptor if one is included by the network, null
     * otherwise.
     */
    public @Nullable String getDataNetworkName() {
        return mDnn;
    }

    /**
     * OsAppId identifies a broader traffic category. Although it names Os/App id, it only includes
     * OS version with a general/broader category id used as app id.
     *
     * @return The id in byte format. {@code null} if not available.
     */
    public @Nullable byte[] getOsAppId() {
        return mOsAppId != null ? mOsAppId.getBytes() : null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull @Override
    public String toString() {
        return "TrafficDescriptor={mDnn=" + mDnn + ", " + mOsAppId + "}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDnn);
        dest.writeByteArray(mOsAppId != null ? mOsAppId.getBytes() : null);
    }

    public static final @NonNull Parcelable.Creator<TrafficDescriptor> CREATOR =
            new Parcelable.Creator<TrafficDescriptor>() {
                @Override
                public @NonNull TrafficDescriptor createFromParcel(@NonNull Parcel source) {
                    return new TrafficDescriptor(source);
                }

                @Override
                public @NonNull TrafficDescriptor[] newArray(int size) {
                    return new TrafficDescriptor[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrafficDescriptor that = (TrafficDescriptor) o;
        return Objects.equals(mDnn, that.mDnn) && Objects.equals(mOsAppId, that.mOsAppId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDnn, mOsAppId);
    }

    /**
     * Provides a convenient way to set the fields of a {@link TrafficDescriptor} when creating a
     * new instance.
     *
     * <p>The example below shows how you might create a new {@code TrafficDescriptor}:
     *
     * <pre><code>
     *
     * TrafficDescriptor response = new TrafficDescriptor.Builder()
     *     .setDnn("")
     *     .build();
     * </code></pre>
     *
     */
    public static final class Builder {
        private String mDnn = null;
        private byte[] mOsAppId = null;

        /**
         * Default constructor for Builder.
         */
        public Builder() {
        }

        /**
         * Set the Data Network Name(DNN).
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setDataNetworkName(@NonNull String dnn) {
            this.mDnn = dnn;
            return this;
        }

        /**
         * Set the OS App ID (including OS Id as defined in the specs).
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setOsAppId(@NonNull byte[] osAppId) {
            this.mOsAppId = osAppId;
            return this;
        }

        /**
         * Build the {@link TrafficDescriptor}.
         *
         * @throws IllegalArgumentException if DNN and OS App ID are null.
         *
         * @return the {@link TrafficDescriptor} object.
         */
        @NonNull
        public TrafficDescriptor build() {
            if (this.mDnn == null && this.mOsAppId == null) {
                throw new IllegalArgumentException("DNN and OS App ID are null");
            }
            return new TrafficDescriptor(this.mDnn, this.mOsAppId);
        }
    }
}
