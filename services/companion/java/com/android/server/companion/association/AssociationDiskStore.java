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

package com.android.server.companion.association;

import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static com.android.server.companion.utils.AssociationUtils.getFirstAssociationIdForUser;
import static com.android.server.companion.utils.DataStoreUtils.createStorageFileForUser;
import static com.android.server.companion.utils.DataStoreUtils.fileToByteArray;
import static com.android.server.companion.utils.DataStoreUtils.isEndOfTag;
import static com.android.server.companion.utils.DataStoreUtils.isStartOfTag;
import static com.android.server.companion.utils.DataStoreUtils.writeToFileSafely;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.net.MacAddress;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * IMPORTANT: This class should NOT be directly used except {@link AssociationStore}
 *
 * The class responsible for persisting Association records and other related information (such as
 * previously used IDs) to a disk, and reading the data back from the disk.
 *
 * <p>
 * Before Android T the data was stored in "companion_device_manager_associations.xml" file in
 * {@link Environment#getUserSystemDirectory(int) /data/system/user/}.
 *
 * See {@link #getBaseLegacyStorageFileForUser(int) getBaseLegacyStorageFileForUser()}.
 *
 * <p>
 * Before Android T the data was stored using the v0 schema. See:
 * <ul>
 * <li>{@link #readAssociationsV0(TypedXmlPullParser, int) readAssociationsV0()}.
 * <li>{@link #readAssociationV0(TypedXmlPullParser, int, int) readAssociationV0()}.
 * </ul>
 *
 * The following snippet is a sample of a file that is using v0 schema.
 * <pre>{@code
 * <associations>
 *   <association
 *     package="com.sample.companion.app"
 *     device="AA:BB:CC:DD:EE:00"
 *     time_approved="1634389553216" />
 *   <association
 *     package="com.another.sample.companion.app"
 *     device="AA:BB:CC:DD:EE:01"
 *     profile="android.app.role.COMPANION_DEVICE_WATCH"
 *     notify_device_nearby="false"
 *     time_approved="1634389752662" />
 * </associations>
 * }</pre>
 *
 * <p>
 * Since Android T the data is stored to "companion_device_manager.xml" file in
 * {@link Environment#getDataSystemDeDirectory(int) /data/system_de/}.
 *
 * <p>
 * Since Android T the data is stored using the v1 schema.
 *
 * In the v1 schema, a list of the previously used IDs is stored along with the association
 * records.
 *
 * V1 schema adds a new optional "display_name" attribute, and makes the "mac_address" attribute
 * optional.
 * <ul>
 * <li> {@link #CURRENT_PERSISTENCE_VERSION}
 * <li> {@link #readAssociationsV1(TypedXmlPullParser, int) readAssociationsV1()}
 * <li> {@link #readAssociationV1(TypedXmlPullParser, int) readAssociationV1()}
 * </ul>
 *
 * The following snippet is a sample of a file that is using v1 schema.
 * <pre>{@code
 * <state persistence-version="1">
 *     <associations max-id="3">
 *         <association
 *             id="1"
 *             package="com.sample.companion.app"
 *             mac_address="AA:BB:CC:DD:EE:00"
 *             self_managed="false"
 *             notify_device_nearby="false"
 *             revoked="false"
 *             last_time_connected="1634641160229"
 *             time_approved="1634389553216"
 *             system_data_sync_flags="0"/>
 *
 *         <association
 *             id="3"
 *             profile="android.app.role.COMPANION_DEVICE_WATCH"
 *             package="com.sample.companion.another.app"
 *             display_name="Jhon's Chromebook"
 *             self_managed="true"
 *             notify_device_nearby="false"
 *             revoked="false"
 *             last_time_connected="1634641160229"
 *             time_approved="1634641160229"
 *             system_data_sync_flags="1"/>
 *     </associations>
 * </state>
 * }</pre>
 */
@SuppressLint("LongLogTag")
public final class AssociationDiskStore {
    private static final String TAG = "CDM_AssociationDiskStore";

    private static final int CURRENT_PERSISTENCE_VERSION = 1;

    private static final String FILE_NAME_LEGACY = "companion_device_manager_associations.xml";
    private static final String FILE_NAME = "companion_device_manager.xml";

    private static final String XML_TAG_STATE = "state";
    private static final String XML_TAG_ASSOCIATIONS = "associations";
    private static final String XML_TAG_ASSOCIATION = "association";
    private static final String XML_TAG_TAG = "tag";

    private static final String XML_ATTR_PERSISTENCE_VERSION = "persistence-version";
    private static final String XML_ATTR_MAX_ID = "max-id";
    private static final String XML_ATTR_ID = "id";
    private static final String XML_ATTR_PACKAGE = "package";
    private static final String XML_ATTR_MAC_ADDRESS = "mac_address";
    private static final String XML_ATTR_DISPLAY_NAME = "display_name";
    private static final String XML_ATTR_PROFILE = "profile";
    private static final String XML_ATTR_SELF_MANAGED = "self_managed";
    private static final String XML_ATTR_NOTIFY_DEVICE_NEARBY = "notify_device_nearby";
    private static final String XML_ATTR_REVOKED = "revoked";
    private static final String XML_ATTR_PENDING = "pending";
    private static final String XML_ATTR_TIME_APPROVED = "time_approved";
    private static final String XML_ATTR_LAST_TIME_CONNECTED = "last_time_connected";
    private static final String XML_ATTR_SYSTEM_DATA_SYNC_FLAGS = "system_data_sync_flags";

    private static final String LEGACY_XML_ATTR_DEVICE = "device";

    private final @NonNull ConcurrentMap<Integer, AtomicFile> mUserIdToStorageFile =
            new ConcurrentHashMap<>();

    /**
     * Read all associations for given users
     */
    public Map<Integer, Associations> readAssociationsByUsers(@NonNull List<Integer> userIds) {
        Map<Integer, Associations> userToAssociationsMap = new HashMap<>();
        for (int userId : userIds) {
            userToAssociationsMap.put(userId, readAssociationsByUser(userId));
        }
        return userToAssociationsMap;
    }

    /**
     * Reads previously persisted data for the given user "into" the provided containers.
     *
     * Note that {@link AssociationInfo#getAssociatedDevice()} will always be {@code null} after
     * retrieval from this datastore because it is not persisted (by design). This means that
     * persisted data is not guaranteed to be identical to the initial data that was stored at the
     * time of association.
     */
    @NonNull
    private Associations readAssociationsByUser(@UserIdInt int userId) {
        Slog.i(TAG, "Reading associations for user " + userId + " from disk.");
        final AtomicFile file = getStorageFileForUser(userId);
        Associations associations;

        // getStorageFileForUser() ALWAYS returns the SAME OBJECT, which allows us to synchronize
        // accesses to the file on the file system using this AtomicFile object.
        synchronized (file) {
            File legacyBaseFile = null;
            final AtomicFile readFrom;
            final String rootTag;
            if (!file.getBaseFile().exists()) {
                legacyBaseFile = getBaseLegacyStorageFileForUser(userId);
                if (!legacyBaseFile.exists()) {
                    return new Associations();
                }

                readFrom = new AtomicFile(legacyBaseFile);
                rootTag = XML_TAG_ASSOCIATIONS;
            } else {
                readFrom = file;
                rootTag = XML_TAG_STATE;
            }

            associations = readAssociationsFromFile(userId, readFrom, rootTag);

            if (legacyBaseFile != null || associations.getVersion() < CURRENT_PERSISTENCE_VERSION) {
                // The data is either in the legacy file or in the legacy format, or both.
                // Save the data to right file in using the current format.
                writeAssociationsToFile(file, associations);

                if (legacyBaseFile != null) {
                    // We saved the data to the right file, can delete the old file now.
                    legacyBaseFile.delete();
                }
            }
        }
        return associations;
    }

    /**
     * Write associations to disk for the user.
     */
    public void writeAssociationsForUser(@UserIdInt int userId,
            @NonNull Associations associations) {
        Slog.i(TAG, "Writing associations for user " + userId + " to disk");

        final AtomicFile file = getStorageFileForUser(userId);
        // getStorageFileForUser() ALWAYS returns the SAME OBJECT, which allows us to synchronize
        // accesses to the file on the file system using this AtomicFile object.
        synchronized (file) {
            writeAssociationsToFile(file, associations);
        }
    }

    @NonNull
    private static Associations readAssociationsFromFile(@UserIdInt int userId,
            @NonNull AtomicFile file, @NonNull String rootTag) {
        try (FileInputStream in = file.openRead()) {
            return readAssociationsFromInputStream(userId, in, rootTag);
        } catch (XmlPullParserException | IOException e) {
            Slog.e(TAG, "Error while reading associations file", e);
            return new Associations();
        }
    }

    @NonNull
    private static Associations readAssociationsFromInputStream(@UserIdInt int userId,
            @NonNull InputStream in, @NonNull String rootTag)
            throws XmlPullParserException, IOException {
        final TypedXmlPullParser parser = Xml.resolvePullParser(in);
        XmlUtils.beginDocument(parser, rootTag);

        final int version = readIntAttribute(parser, XML_ATTR_PERSISTENCE_VERSION, 0);
        Associations associations = new Associations();

        switch (version) {
            case 0:
                associations = readAssociationsV0(parser, userId);
                break;
            case 1:
                while (true) {
                    parser.nextTag();
                    if (isStartOfTag(parser, XML_TAG_ASSOCIATIONS)) {
                        associations = readAssociationsV1(parser, userId);
                    } else if (isEndOfTag(parser, rootTag)) {
                        break;
                    }
                }
                break;
        }
        return associations;
    }

    private void writeAssociationsToFile(@NonNull AtomicFile file,
            @NonNull Associations associations) {
        // Writing to file could fail, for example, if the user has been recently removed and so was
        // their DE (/data/system_de/<user-id>/) directory.
        writeToFileSafely(file, out -> {
            final TypedXmlSerializer serializer = Xml.resolveSerializer(out);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);
            serializer.startTag(null, XML_TAG_STATE);
            writeIntAttribute(serializer,
                    XML_ATTR_PERSISTENCE_VERSION, CURRENT_PERSISTENCE_VERSION);
            writeAssociations(serializer, associations);
            serializer.endTag(null, XML_TAG_STATE);
            serializer.endDocument();
        });
    }

    /**
     * Creates and caches {@link AtomicFile} object that represents the back-up file for the given
     * user.
     *
     * IMPORTANT: the method will ALWAYS return the same {@link AtomicFile} object, which makes it
     * possible to synchronize reads and writes to the file using the returned object.
     */
    @NonNull
    private AtomicFile getStorageFileForUser(@UserIdInt int userId) {
        return mUserIdToStorageFile.computeIfAbsent(userId,
                u -> createStorageFileForUser(userId, FILE_NAME));
    }

    /**
     * Get associations backup payload from disk
     */
    public byte[] getBackupPayload(@UserIdInt int userId) {
        Slog.i(TAG, "Fetching stored state data for user " + userId + " from disk");
        final AtomicFile file = getStorageFileForUser(userId);

        synchronized (file) {
            return fileToByteArray(file);
        }
    }

    /**
     * Convert payload to a set of associations
     */
    public static Associations readAssociationsFromPayload(byte[] payload, @UserIdInt int userId) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload)) {
            return readAssociationsFromInputStream(userId, in, XML_TAG_STATE);
        } catch (XmlPullParserException | IOException e) {
            Slog.e(TAG, "Error while reading associations file", e);
            return new Associations();
        }
    }

    private static @NonNull File getBaseLegacyStorageFileForUser(@UserIdInt int userId) {
        return new File(Environment.getUserSystemDirectory(userId), FILE_NAME_LEGACY);
    }

    private static Associations readAssociationsV0(@NonNull TypedXmlPullParser parser,
            @UserIdInt int userId)
            throws XmlPullParserException, IOException {
        requireStartOfTag(parser, XML_TAG_ASSOCIATIONS);

        // Before Android T Associations didn't have IDs, so when we are upgrading from S (reading
        // from V0) we need to generate and assign IDs to the existing Associations.
        // It's safe to do it here, because CDM cannot create new Associations before it reads
        // existing ones from the backup files. And the fact that we are reading from a V0 file,
        // means that CDM hasn't assigned any IDs yet, so we can just start from the first available
        // id for each user (eg. 1 for user 0; 100 001 - for user 1; 200 001 - for user 2; etc).
        int associationId = getFirstAssociationIdForUser(userId);
        Associations associations = new Associations();
        associations.setVersion(0);

        while (true) {
            parser.nextTag();
            if (isEndOfTag(parser, XML_TAG_ASSOCIATIONS)) break;
            if (!isStartOfTag(parser, XML_TAG_ASSOCIATION)) continue;

            associations.addAssociation(readAssociationV0(parser, userId, associationId++));
        }

        associations.setMaxId(associationId - 1);

        return associations;
    }

    private static AssociationInfo readAssociationV0(@NonNull TypedXmlPullParser parser,
            @UserIdInt int userId, int associationId)
            throws XmlPullParserException {
        requireStartOfTag(parser, XML_TAG_ASSOCIATION);

        final String appPackage = readStringAttribute(parser, XML_ATTR_PACKAGE);
        final String tag = readStringAttribute(parser, XML_TAG_TAG);
        final String deviceAddress = readStringAttribute(parser, LEGACY_XML_ATTR_DEVICE);
        final String profile = readStringAttribute(parser, XML_ATTR_PROFILE);
        final boolean notify = readBooleanAttribute(parser, XML_ATTR_NOTIFY_DEVICE_NEARBY);
        final long timeApproved = readLongAttribute(parser, XML_ATTR_TIME_APPROVED, 0L);

        return new AssociationInfo(associationId, userId, appPackage, tag,
                MacAddress.fromString(deviceAddress), null, profile, null,
                /* managedByCompanionApp */ false, notify, /* revoked */ false, /* pending */ false,
                timeApproved, Long.MAX_VALUE, /* systemDataSyncFlags */ 0);
    }

    private static Associations readAssociationsV1(@NonNull TypedXmlPullParser parser,
            @UserIdInt int userId)
            throws XmlPullParserException, IOException {
        requireStartOfTag(parser, XML_TAG_ASSOCIATIONS);

        // For old builds that don't have max-id attr,
        // default maxId to 0 and get the maxId out of all association ids.
        int maxId = readIntAttribute(parser, XML_ATTR_MAX_ID, 0);
        Associations associations = new Associations();
        associations.setVersion(1);

        while (true) {
            parser.nextTag();
            if (isEndOfTag(parser, XML_TAG_ASSOCIATIONS)) break;
            if (!isStartOfTag(parser, XML_TAG_ASSOCIATION)) continue;

            AssociationInfo association = readAssociationV1(parser, userId);
            associations.addAssociation(association);

            maxId = Math.max(maxId, association.getId());
        }

        associations.setMaxId(maxId);

        return associations;
    }

    private static AssociationInfo readAssociationV1(@NonNull TypedXmlPullParser parser,
            @UserIdInt int userId)
            throws XmlPullParserException, IOException {
        requireStartOfTag(parser, XML_TAG_ASSOCIATION);

        final int associationId = readIntAttribute(parser, XML_ATTR_ID);
        final String profile = readStringAttribute(parser, XML_ATTR_PROFILE);
        final String appPackage = readStringAttribute(parser, XML_ATTR_PACKAGE);
        final String tag = readStringAttribute(parser, XML_TAG_TAG);
        final MacAddress macAddress = stringToMacAddress(
                readStringAttribute(parser, XML_ATTR_MAC_ADDRESS));
        final String displayName = readStringAttribute(parser, XML_ATTR_DISPLAY_NAME);
        final boolean selfManaged = readBooleanAttribute(parser, XML_ATTR_SELF_MANAGED);
        final boolean notify = readBooleanAttribute(parser, XML_ATTR_NOTIFY_DEVICE_NEARBY);
        final boolean revoked = readBooleanAttribute(parser, XML_ATTR_REVOKED, false);
        final boolean pending = readBooleanAttribute(parser, XML_ATTR_PENDING, false);
        final long timeApproved = readLongAttribute(parser, XML_ATTR_TIME_APPROVED, 0L);
        final long lastTimeConnected = readLongAttribute(
                parser, XML_ATTR_LAST_TIME_CONNECTED, Long.MAX_VALUE);
        final int systemDataSyncFlags = readIntAttribute(parser,
                XML_ATTR_SYSTEM_DATA_SYNC_FLAGS, 0);

        return new AssociationInfo(associationId, userId, appPackage, tag, macAddress, displayName,
                profile, null, selfManaged, notify, revoked, pending, timeApproved,
                lastTimeConnected, systemDataSyncFlags);
    }

    private static void writeAssociations(@NonNull XmlSerializer parent,
            @NonNull Associations associations)
            throws IOException {
        final XmlSerializer serializer = parent.startTag(null, XML_TAG_ASSOCIATIONS);
        writeIntAttribute(serializer, XML_ATTR_MAX_ID, associations.getMaxId());
        for (AssociationInfo association : associations.getAssociations()) {
            writeAssociation(serializer, association);
        }
        serializer.endTag(null, XML_TAG_ASSOCIATIONS);
    }

    private static void writeAssociation(@NonNull XmlSerializer parent, @NonNull AssociationInfo a)
            throws IOException {
        final XmlSerializer serializer = parent.startTag(null, XML_TAG_ASSOCIATION);

        writeIntAttribute(serializer, XML_ATTR_ID, a.getId());
        writeStringAttribute(serializer, XML_ATTR_PROFILE, a.getDeviceProfile());
        writeStringAttribute(serializer, XML_ATTR_PACKAGE, a.getPackageName());
        writeStringAttribute(serializer, XML_TAG_TAG, a.getTag());
        writeStringAttribute(serializer, XML_ATTR_MAC_ADDRESS, a.getDeviceMacAddressAsString());
        writeStringAttribute(serializer, XML_ATTR_DISPLAY_NAME, a.getDisplayName());
        writeBooleanAttribute(serializer, XML_ATTR_SELF_MANAGED, a.isSelfManaged());
        writeBooleanAttribute(
                serializer, XML_ATTR_NOTIFY_DEVICE_NEARBY, a.isNotifyOnDeviceNearby());
        writeBooleanAttribute(serializer, XML_ATTR_REVOKED, a.isRevoked());
        writeBooleanAttribute(serializer, XML_ATTR_PENDING, a.isPending());
        writeLongAttribute(serializer, XML_ATTR_TIME_APPROVED, a.getTimeApprovedMs());
        writeLongAttribute(
                serializer, XML_ATTR_LAST_TIME_CONNECTED, a.getLastTimeConnectedMs());
        writeIntAttribute(serializer, XML_ATTR_SYSTEM_DATA_SYNC_FLAGS, a.getSystemDataSyncFlags());

        serializer.endTag(null, XML_TAG_ASSOCIATION);
    }

    private static void requireStartOfTag(@NonNull XmlPullParser parser, @NonNull String tag)
            throws XmlPullParserException {
        if (isStartOfTag(parser, tag)) return;
        throw new XmlPullParserException(
                "Should be at the start of \"" + XML_TAG_ASSOCIATIONS + "\" tag");
    }

    private static @Nullable MacAddress stringToMacAddress(@Nullable String address) {
        return address != null ? MacAddress.fromString(address) : null;
    }
}
