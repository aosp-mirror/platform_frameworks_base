/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contains the User Capability Exchange capabilities corresponding to a contact's URI.
 * @hide
 */
public final class RcsContactUceCapability implements Parcelable {

    /** Supports 1-to-1 chat */
    public static final int CAPABILITY_CHAT_STANDALONE = (1 << 0);
    /** Supports group chat */
    public static final int CAPABILITY_CHAT_SESSION = (1 << 1);
    /** Supports full store and forward group chat information. */
    public static final int CAPABILITY_CHAT_SESSION_STORE_FORWARD = (1 << 2);
    /**
     * Supports file transfer via Message Session Relay Protocol (MSRP) without Store and Forward.
     */
    public static final int CAPABILITY_FILE_TRANSFER = (1 << 3);
    /** Supports File Transfer Thumbnail */
    public static final int CAPABILITY_FILE_TRANSFER_THUMBNAIL = (1 << 4);
    /** Supports File Transfer with Store and Forward */
    public static final int CAPABILITY_FILE_TRANSFER_STORE_FORWARD = (1 << 5);
    /** Supports File Transfer via HTTP */
    public static final int CAPABILITY_FILE_TRANSFER_HTTP = (1 << 6);
    /** Supports file transfer via SMS */
    public static final int CAPABILITY_FILE_TRANSFER_SMS = (1 << 7);
    /** Supports image sharing */
    public static final int CAPABILITY_IMAGE_SHARE = (1 << 8);
    /** Supports video sharing during a circuit-switch call (IR.74)*/
    public static final int CAPABILITY_VIDEO_SHARE_DURING_CS_CALL = (1 << 9);
    /** Supports video share outside of voice call (IR.84) */
    public static final int CAPABILITY_VIDEO_SHARE = (1 << 10);
    /** Supports social presence information */
    public static final int CAPABILITY_SOCIAL_PRESENCE = (1 << 11);
    /** Supports capability discovery via presence */
    public static final int CAPABILITY_DISCOVERY_VIA_PRESENCE = (1 << 12);
    /** Supports IP Voice calling over LTE or IWLAN (IR.92/IR.51) */
    public static final int CAPABILITY_IP_VOICE_CALL = (1 << 13);
    /** Supports IP video calling (IR.94) */
    public static final int CAPABILITY_IP_VIDEO_CALL = (1 << 14);
    /** Supports Geolocation PUSH during 1-to-1 or multiparty chat */
    public static final int CAPABILITY_GEOLOCATION_PUSH = (1 << 15);
    /** Supports Geolocation PUSH via SMS for fallback.  */
    public static final int CAPABILITY_GEOLOCATION_PUSH_SMS = (1 << 16);
    /** Supports Geolocation pull. */
    public static final int CAPABILITY_GEOLOCATION_PULL = (1 << 17);
    /** Supports Geolocation pull using file transfer support. */
    public static final int CAPABILITY_GEOLOCATION_PULL_FILE_TRANSFER = (1 << 18);
    /** Supports RCS voice calling */
    public static final int CAPABILITY_RCS_VOICE_CALL = (1 << 19);
    /** Supports RCS video calling */
    public static final int CAPABILITY_RCS_VIDEO_CALL = (1 << 20);
    /** Supports RCS video calling, where video media can not be dropped. */
    public static final int CAPABILITY_RCS_VIDEO_ONLY_CALL = (1 << 21);
    /** Supports call composer, where outgoing calls can be enriched with pre-call content.*/
    public static final int CAPABILITY_CALL_COMPOSER = (1 << 22);
    /** Supports post call information that is included in the call if the call is missed.*/
    public static final int CAPABILITY_POST_CALL = (1 << 23);
    /** Supports sharing a map where the user can draw, share markers, and share their position. */
    public static final int CAPABILITY_SHARED_MAP = (1 << 24);
    /** Supports sharing a canvas, where users can draw, add images, and change background colors.*/
    public static final int CAPABILITY_SHARED_SKETCH = (1 << 25);
    /** Supports communication with Chatbots. */
    public static final int CAPABILITY_CHAT_BOT = (1 << 26);
    /** Supports Chatbot roles. */
    public static final int CAPABILITY_CHAT_BOT_ROLE = (1 << 27);
    /** Supports the unidirectional plug-ins framework. */
    public static final int CAPABILITY_PLUG_IN = (1 << 28);
    /** Supports standalone Chatbot communication. */
    public static final int CAPABILITY_STANDALONE_CHAT_BOT = (1 << 29);
    /** Supports MMTEL based call composer. */
    public static final int CAPABILITY_MMTEL_CALL_COMPOSER = (1 << 30);



    /** @hide*/
    @Retention(RetentionPolicy.SOURCE)
    @LongDef(prefix = "CAPABILITY_", flag = true, value = {
            CAPABILITY_CHAT_STANDALONE,
            CAPABILITY_CHAT_SESSION,
            CAPABILITY_CHAT_SESSION_STORE_FORWARD,
            CAPABILITY_FILE_TRANSFER,
            CAPABILITY_FILE_TRANSFER_THUMBNAIL,
            CAPABILITY_FILE_TRANSFER_STORE_FORWARD,
            CAPABILITY_FILE_TRANSFER_HTTP,
            CAPABILITY_FILE_TRANSFER_SMS,
            CAPABILITY_IMAGE_SHARE,
            CAPABILITY_VIDEO_SHARE_DURING_CS_CALL,
            CAPABILITY_VIDEO_SHARE,
            CAPABILITY_SOCIAL_PRESENCE,
            CAPABILITY_DISCOVERY_VIA_PRESENCE,
            CAPABILITY_IP_VOICE_CALL,
            CAPABILITY_IP_VIDEO_CALL,
            CAPABILITY_GEOLOCATION_PUSH,
            CAPABILITY_GEOLOCATION_PUSH_SMS,
            CAPABILITY_GEOLOCATION_PULL,
            CAPABILITY_GEOLOCATION_PULL_FILE_TRANSFER,
            CAPABILITY_RCS_VOICE_CALL,
            CAPABILITY_RCS_VIDEO_CALL,
            CAPABILITY_RCS_VIDEO_ONLY_CALL,
            CAPABILITY_CALL_COMPOSER,
            CAPABILITY_POST_CALL,
            CAPABILITY_SHARED_MAP,
            CAPABILITY_SHARED_SKETCH,
            CAPABILITY_CHAT_BOT,
            CAPABILITY_CHAT_BOT_ROLE,
            CAPABILITY_PLUG_IN,
            CAPABILITY_STANDALONE_CHAT_BOT,
            CAPABILITY_MMTEL_CALL_COMPOSER
    })
    public @interface CapabilityFlag {}

    /**
     * Builder to help construct {@link RcsContactUceCapability} instances.
     */
    public static class Builder {

        private final RcsContactUceCapability mCapabilities;

        /**
         * Create the Builder, which can be used to set UCE capabilities as well as custom
         * capability extensions.
         * @param contact The contact URI that the capabilities are attached to.
         */
        public Builder(@NonNull Uri contact) {
            mCapabilities = new RcsContactUceCapability(contact);
        }

        /**
         * Add a UCE capability bit-field as well as the associated URI that the framework should
         * use for those services. This is mainly used for capabilities that may use a URI separate
         * from the contact's URI, for example the URI to use for VT calls.
         * @param type The capability to map to a service URI that is different from the contact's
         *         URI.
         */
        public @NonNull Builder add(@CapabilityFlag long type, @NonNull Uri serviceUri) {
            mCapabilities.mCapabilities |= type;
            // Put each of these capabilities into the map separately.
            for (long shift = 0; shift < Integer.SIZE; shift++) {
                long cap = type & (1 << shift);
                if (cap != 0) {
                    mCapabilities.mServiceMap.put(cap, serviceUri);
                    // remove that capability from the field.
                    type &= ~cap;
                }
                if (type == 0) {
                    // no need to keep going, end early.
                    break;
                }
            }
            return this;
        }

        /**
         * Add a UCE capability flag that this contact supports.
         * @param type the capability that the contact supports.
         */
        public @NonNull Builder add(@CapabilityFlag long type) {
            mCapabilities.mCapabilities |= type;
            return this;
        }

        /**
         * Add a carrier specific service tag.
         * @param extension A string containing a carrier specific service tag that is an extension
         *         of the {@link CapabilityFlag}s that are defined here.
         */
        public @NonNull Builder add(@NonNull String extension) {
            mCapabilities.mExtensionTags.add(extension);
            return this;
        }

        /**
         * @return the constructed instance.
         */
        public @NonNull RcsContactUceCapability build() {
            return mCapabilities;
        }
    }

    private final Uri mContactUri;
    private long mCapabilities;
    private List<String> mExtensionTags = new ArrayList<>();
    private Map<Long, Uri> mServiceMap = new HashMap<>();

    /**
     * Use {@link Builder} to build an instance of this interface.
     * @param contact The URI associated with this capability information.
     * @hide
     */
    RcsContactUceCapability(@NonNull Uri contact) {
        mContactUri = contact;
    }

    private RcsContactUceCapability(Parcel in) {
        mContactUri = in.readParcelable(Uri.class.getClassLoader());
        mCapabilities = in.readLong();
        in.readStringList(mExtensionTags);
        // read mServiceMap as key,value pair
        int mapSize = in.readInt();
        for (int i = 0; i < mapSize; i++) {
            mServiceMap.put(in.readLong(), in.readParcelable(Uri.class.getClassLoader()));
        }
    }

    public static final @NonNull Creator<RcsContactUceCapability> CREATOR =
            new Creator<RcsContactUceCapability>() {
        @Override
        public RcsContactUceCapability createFromParcel(Parcel in) {
            return new RcsContactUceCapability(in);
        }

        @Override
        public RcsContactUceCapability[] newArray(int size) {
            return new RcsContactUceCapability[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(mContactUri, 0);
        out.writeLong(mCapabilities);
        out.writeStringList(mExtensionTags);
        // write mServiceMap as key,value pairs
        int mapSize = mServiceMap.keySet().size();
        out.writeInt(mapSize);
        for (long key : mServiceMap.keySet()) {
            out.writeLong(key);
            out.writeParcelable(mServiceMap.get(key), 0);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Query for a capability
     * @param type The capability flag to query.
     * @return true if the capability flag specified is set, false otherwise.
     */
    public boolean isCapable(@CapabilityFlag long type) {
        return (mCapabilities & type) > 0;
    }

    /**
     * @return true if the extension service tag is set, false otherwise.
     */
    public boolean isCapable(@NonNull String extensionTag) {
        return mExtensionTags.contains(extensionTag);
    }

    /**
     * @return An immutable list containing all of the extension tags that have been set as capable.
     * @throws UnsupportedOperationException if this list is modified.
     */
    public @NonNull List<String> getCapableExtensionTags() {
        return Collections.unmodifiableList(mExtensionTags);
    }

    /**
     * Retrieves the {@link Uri} associated with the capability being queried.
     * <p>
     * This will typically be the contact {@link Uri} available via {@link #getContactUri()} unless
     * a different service {@link Uri} was associated with this capability using
     * {@link Builder#add(long, Uri)}.
     *
     * @return a String containing the {@link Uri} associated with the service tag or
     * {@code null} if this capability is not set as capable.
     * @see #isCapable(long)
     */
    public @Nullable Uri getServiceUri(@CapabilityFlag long type) {
        Uri result = mServiceMap.getOrDefault(type, null);
        // If the capability is capable, but does not have a service URI associated, use the default
        // contact URI.
        if (result == null) {
            return isCapable(type) ? getContactUri() : null;
        }
        return result;
    }

    /**
     * @return the URI representing the contact associated with the capabilities.
     */
    public @NonNull Uri getContactUri() {
        return mContactUri;
    }
}
