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

package com.android.server.companion;

import static com.android.internal.util.CollectionUtils.forEach;
import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;

import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.content.pm.UserInfo;
import android.net.MacAddress;
import android.os.Environment;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.ExceptionUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The class responsible for persisting Association records and other related information (such as
 * previously used IDs) to a disk, and reading the data back from the disk.
 *
 * Before Android T the data was stored to `companion_device_manager_associations.xml` file in
 * {@link Environment#getUserSystemDirectory(int)}
 * (eg. `/data/system/users/0/companion_device_manager_associations.xml`)
 * @see #getBaseLegacyStorageFileForUser(int)
 *
 * Before Android T the data was stored using the v0 schema.
 *
 * @see #readAssociationsV0(TypedXmlPullParser, int, Collection)
 * @see #readAssociationV0(TypedXmlPullParser, int, int, Collection)
 *
 * The following snippet is a sample of a the file that is using v0 schema.
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
 *
 * Since Android T the data is stored to `companion_device_manager.xml` file in
 * {@link Environment#getDataSystemDeDirectory(int)}.
 * (eg. `/data/system_de/0/companion_device_manager.xml`)
 * @see #getBaseStorageFileForUser(int)

 * Since Android T the data is stored using the v1 schema.
 * In the v1 schema, a list of the previously used IDs is storead along with the association
 * records.
 * V1 schema adds a new optional `display_name` attribute, and makes the `mac_address` attribute
 * optional.
 *
 * @see #CURRENT_PERSISTENCE_VERSION
 * @see #readAssociationsV1(TypedXmlPullParser, int, Collection)
 * @see #readAssociationV1(TypedXmlPullParser, int, Collection)
 * @see #readPreviouslyUsedIdsV1(TypedXmlPullParser, Map)
 *
 * The following snippet is a sample of a the file that is using v0 schema.
 * <pre>{@code
 * <state persistence-version="1">
 *     <associations>
 *         <association
 *             id="1"
 *             package="com.sample.companion.app"
 *             mac_address="AA:BB:CC:DD:EE:00"
 *             self_managed="false"
 *             notify_device_nearby="false"
 *             time_approved="1634389553216"/>
 *
 *         <association
 *             id="3"
 *             profile="android.app.role.COMPANION_DEVICE_WATCH"
 *             package="com.sample.companion.another.app"
 *             display_name="Jhon's Chromebook"
 *             self_managed="true"
 *             notify_device_nearby="false"
 *             time_approved="1634641160229"/>
 *     </associations>
 *
 *     <previously-used-ids>
 *         <package package_name="com.sample.companion.app">
 *             <id>2</id>
 *         </package>
 *     </previously-used-ids>
 * </state>
 * }</pre>
 */
final class PersistentDataStore {
    private static final String LOG_TAG = CompanionDeviceManagerService.LOG_TAG + ".DataStore";
    private static final boolean DEBUG = CompanionDeviceManagerService.DEBUG;

    private static final int CURRENT_PERSISTENCE_VERSION = 1;

    private static final String FILE_NAME_LEGACY = "companion_device_manager_associations.xml";
    private static final String FILE_NAME = "companion_device_manager.xml";

    private static final String XML_TAG_STATE = "state";
    private static final String XML_TAG_ASSOCIATIONS = "associations";
    private static final String XML_TAG_ASSOCIATION = "association";
    private static final String XML_TAG_PREVIOUSLY_USED_IDS = "previously-used-ids";
    private static final String XML_TAG_PACKAGE = "package";
    private static final String XML_TAG_ID = "id";

    private static final String XML_ATTR_PERSISTENCE_VERSION = "persistence-version";
    private static final String XML_ATTR_ID = "id";
    // Used in <package> elements, nested within <previously-used-ids> elements.
    private static final String XML_ATTR_PACKAGE_NAME = "package_name";
    // Used in <association> elements, nested within <associations> elements.
    private static final String XML_ATTR_PACKAGE = "package";
    private static final String XML_ATTR_MAC_ADDRESS = "mac_address";
    private static final String XML_ATTR_DISPLAY_NAME = "display_name";
    private static final String XML_ATTR_PROFILE = "profile";
    private static final String XML_ATTR_SELF_MANAGED = "self_managed";
    private static final String XML_ATTR_NOTIFY_DEVICE_NEARBY = "notify_device_nearby";
    private static final String XML_ATTR_TIME_APPROVED = "time_approved";

    private static final String LEGACY_XML_ATTR_DEVICE = "device";

    private final @NonNull ConcurrentMap<Integer, AtomicFile> mUserIdToStorageFile =
            new ConcurrentHashMap<>();

    void readStateForUsers(@NonNull List<UserInfo> users,
            @NonNull Set<AssociationInfo> allAssociationsOut,
            @NonNull SparseArray<Map<String, Set<Integer>>> previouslyUsedIdsPerUserOut) {
        for (UserInfo user : users) {
            final int userId = user.id;
            // Previously used IDs are stored in the "out" collection per-user.
            final Map<String, Set<Integer>> previouslyUsedIds = new ArrayMap<>();

            // Associations for all users are stored in a single "flat" set: so we read directly
            // into it.
            readStateForUser(userId, allAssociationsOut, previouslyUsedIds);

            // Save previously used IDs for this user into the "out" structure.
            previouslyUsedIdsPerUserOut.append(userId, previouslyUsedIds);
        }
    }

    /**
     * Reads previously persisted data for the given user "into" the provided containers.
     *
     * @param userId Android UserID
     * @param associationsOut a container to read the {@link AssociationInfo}s "into".
     * @param previouslyUsedIdsPerPackageOut a container to read the used IDs "into".
     */
    void readStateForUser(@UserIdInt int userId,
            @NonNull Collection<AssociationInfo> associationsOut,
            @NonNull Map<String, Set<Integer>> previouslyUsedIdsPerPackageOut) {
        Slog.i(LOG_TAG, "Reading associations for user " + userId + " from disk");
        final AtomicFile file = getStorageFileForUser(userId);
        if (DEBUG) Slog.d(LOG_TAG, "  > File=" + file.getBaseFile().getPath());

        synchronized (file) {
            File legacyBaseFile = null;
            final AtomicFile readFrom;
            final String rootTag;
            if (!file.getBaseFile().exists()) {
                if (DEBUG) Slog.d(LOG_TAG, "  > File does not exist -> Try to read legacy file");

                legacyBaseFile = getBaseLegacyStorageFileForUser(userId);
                if (DEBUG) Slog.d(LOG_TAG, "  > Legacy file=" + legacyBaseFile.getPath());
                if (!legacyBaseFile.exists()) {
                    if (DEBUG) Slog.d(LOG_TAG, "  > Legacy file does not exist -> Abort");
                    return;
                }

                readFrom = new AtomicFile(legacyBaseFile);
                rootTag = XML_TAG_ASSOCIATIONS;
            } else {
                readFrom = file;
                rootTag = XML_TAG_STATE;
            }

            if (DEBUG) Slog.d(LOG_TAG, "  > Reading associations...");
            final int version = readStateFromFileLocked(userId, readFrom, rootTag,
                    associationsOut, previouslyUsedIdsPerPackageOut);
            if (DEBUG) {
                Slog.d(LOG_TAG, "  > Done reading: " + associationsOut);
                if (version < CURRENT_PERSISTENCE_VERSION) {
                    Slog.d(LOG_TAG, "  > File used old format: v." + version + " -> Re-write");
                }
            }

            if (legacyBaseFile != null || version < CURRENT_PERSISTENCE_VERSION) {
                // The data is either in the legacy file or in the legacy format, or both.
                // Save the data to right file in using the current format.
                if (DEBUG) {
                    Slog.d(LOG_TAG, "  > Writing the data to " + file.getBaseFile().getPath());
                }
                persistStateToFileLocked(file, associationsOut, previouslyUsedIdsPerPackageOut);

                if (legacyBaseFile != null) {
                    // We saved the data to the right file, can delete the old file now.
                    if (DEBUG) Slog.d(LOG_TAG, "  > Deleting legacy file");
                    legacyBaseFile.delete();
                }
            }
        }
    }

    /**
     * Persisted data to the disk.
     *
     * @param userId Android UserID
     * @param associations a set of user's associations.
     * @param previouslyUsedIdsPerPackage a set previously used Association IDs for the user.
     */
    void persistStateForUser(@UserIdInt int userId,
            @NonNull Collection<AssociationInfo> associations,
            @NonNull Map<String, Set<Integer>> previouslyUsedIdsPerPackage) {
        Slog.i(LOG_TAG, "Writing associations for user " + userId + " to disk");
        if (DEBUG) Slog.d(LOG_TAG, "  > " + associations);

        final AtomicFile file = getStorageFileForUser(userId);
        if (DEBUG) Slog.d(LOG_TAG, "  > File=" + file.getBaseFile().getPath());
        synchronized (file) {
            persistStateToFileLocked(file, associations, previouslyUsedIdsPerPackage);
        }
    }

    private int readStateFromFileLocked(@UserIdInt int userId, @NonNull AtomicFile file,
            @NonNull String rootTag, @Nullable Collection<AssociationInfo> associationsOut,
            @NonNull Map<String, Set<Integer>> previouslyUsedIdsPerPackageOut) {
        try (FileInputStream in = file.openRead()) {
            final TypedXmlPullParser parser = Xml.resolvePullParser(in);

            XmlUtils.beginDocument(parser, rootTag);
            final int version = readIntAttribute(parser, XML_ATTR_PERSISTENCE_VERSION, 0);
            switch (version) {
                case 0:
                    readAssociationsV0(parser, userId, associationsOut);
                    break;
                case 1:
                    while (true) {
                        parser.nextTag();
                        if (isStartOfTag(parser, XML_TAG_ASSOCIATIONS)) {
                            readAssociationsV1(parser, userId, associationsOut);
                        } else if (isStartOfTag(parser, XML_TAG_PREVIOUSLY_USED_IDS)) {
                            readPreviouslyUsedIdsV1(parser, previouslyUsedIdsPerPackageOut);
                        } else if (isEndOfTag(parser, rootTag)) {
                            break;
                        }
                    }
                    break;
            }
            return version;
        } catch (XmlPullParserException | IOException e) {
            Slog.e(LOG_TAG, "Error while reading associations file", e);
            return -1;
        }
    }

    private void persistStateToFileLocked(@NonNull AtomicFile file,
            @Nullable Collection<AssociationInfo> associations,
            @NonNull Map<String, Set<Integer>> previouslyUsedIdsPerPackage) {
        file.write(out -> {
            try {
                final TypedXmlSerializer serializer = Xml.resolveSerializer(out);
                serializer.setFeature(
                        "http://xmlpull.org/v1/doc/features.html#indent-output", true);

                serializer.startDocument(null, true);
                serializer.startTag(null, XML_TAG_STATE);
                writeIntAttribute(serializer,
                        XML_ATTR_PERSISTENCE_VERSION, CURRENT_PERSISTENCE_VERSION);

                writeAssociations(serializer, associations);
                writePreviouslyUsedIds(serializer, previouslyUsedIdsPerPackage);

                serializer.endTag(null, XML_TAG_STATE);
                serializer.endDocument();
            } catch (Exception e) {
                Slog.e(LOG_TAG, "Error while writing associations file", e);
                throw ExceptionUtils.propagate(e);
            }
        });
    }

    private @NonNull AtomicFile getStorageFileForUser(@UserIdInt int userId) {
        return mUserIdToStorageFile.computeIfAbsent(userId,
                u -> new AtomicFile(getBaseStorageFileForUser(userId)));
    }

    private static @NonNull File getBaseStorageFileForUser(@UserIdInt int userId) {
        return new File(Environment.getDataSystemDeDirectory(userId), FILE_NAME);
    }

    private static @NonNull File getBaseLegacyStorageFileForUser(@UserIdInt int userId) {
        return new File(Environment.getUserSystemDirectory(userId), FILE_NAME_LEGACY);
    }

    private static void readAssociationsV0(@NonNull TypedXmlPullParser parser,
            @UserIdInt int userId, @NonNull Collection<AssociationInfo> out)
            throws XmlPullParserException, IOException {
        requireStartOfTag(parser, XML_TAG_ASSOCIATIONS);

        // Before Android T Associations didn't have IDs, so when we are upgrading from S (reading
        // from V0) we need to generate and assign IDs to the existing Associations.
        // It's safe to do it here, because CDM cannot create new Associations before it reads
        // existing ones from the backup files. And the fact that we are reading from a V0 file,
        // means that CDM hasn't assigned any IDs yet, so we can just start from the first available
        // id for each user (eg. 1 for user 0; 100 001 - for user 1; 200 001 - for user 2; etc).
        int associationId = CompanionDeviceManagerService.getFirstAssociationIdForUser(userId);
        while (true) {
            parser.nextTag();
            if (isEndOfTag(parser, XML_TAG_ASSOCIATIONS)) break;
            if (!isStartOfTag(parser, XML_TAG_ASSOCIATION)) continue;

            readAssociationV0(parser, userId, associationId++, out);
        }
    }

    private static void readAssociationV0(@NonNull TypedXmlPullParser parser, @UserIdInt int userId,
            int associationId, @NonNull Collection<AssociationInfo> out)
            throws XmlPullParserException {
        requireStartOfTag(parser, XML_TAG_ASSOCIATION);

        final String appPackage = readStringAttribute(parser, XML_ATTR_PACKAGE);
        final String deviceAddress = readStringAttribute(parser, LEGACY_XML_ATTR_DEVICE);

        if (appPackage == null || deviceAddress == null) return;

        final String profile = readStringAttribute(parser, XML_ATTR_PROFILE);
        final boolean notify = readBooleanAttribute(parser, XML_ATTR_NOTIFY_DEVICE_NEARBY);
        final long timeApproved = readLongAttribute(parser, XML_ATTR_TIME_APPROVED, 0L);

        out.add(new AssociationInfo(associationId, userId, appPackage,
                MacAddress.fromString(deviceAddress), null, profile,
                /* managedByCompanionApp */false, notify, timeApproved));
    }

    private static void readAssociationsV1(@NonNull TypedXmlPullParser parser,
            @UserIdInt int userId, @NonNull Collection<AssociationInfo> out)
            throws XmlPullParserException, IOException {
        requireStartOfTag(parser, XML_TAG_ASSOCIATIONS);

        while (true) {
            parser.nextTag();
            if (isEndOfTag(parser, XML_TAG_ASSOCIATIONS)) break;
            if (!isStartOfTag(parser, XML_TAG_ASSOCIATION)) continue;

            readAssociationV1(parser, userId, out);
        }
    }

    private static void readAssociationV1(@NonNull TypedXmlPullParser parser, @UserIdInt int userId,
            @NonNull Collection<AssociationInfo> out) throws XmlPullParserException, IOException {
        requireStartOfTag(parser, XML_TAG_ASSOCIATION);

        final int associationId = readIntAttribute(parser, XML_ATTR_ID);
        final String profile = readStringAttribute(parser, XML_ATTR_PROFILE);
        final String appPackage = readStringAttribute(parser, XML_ATTR_PACKAGE);
        final MacAddress macAddress = stringToMacAddress(
                readStringAttribute(parser, XML_ATTR_MAC_ADDRESS));
        final String displayName = readStringAttribute(parser, XML_ATTR_DISPLAY_NAME);
        final boolean selfManaged = readBooleanAttribute(parser, XML_ATTR_SELF_MANAGED);
        final boolean notify = readBooleanAttribute(parser, XML_ATTR_NOTIFY_DEVICE_NEARBY);
        final long timeApproved = readLongAttribute(parser, XML_ATTR_TIME_APPROVED, 0L);

        final AssociationInfo associationInfo = createAssociationInfoNoThrow(associationId, userId,
                appPackage, macAddress, displayName, profile, selfManaged, notify, timeApproved);
        if (associationInfo != null) {
            out.add(associationInfo);
        }
    }

    private static void readPreviouslyUsedIdsV1(@NonNull TypedXmlPullParser parser,
            @NonNull Map<String, Set<Integer>> out) throws XmlPullParserException, IOException {
        requireStartOfTag(parser, XML_TAG_PREVIOUSLY_USED_IDS);

        while (true) {
            parser.nextTag();
            if (isEndOfTag(parser, XML_TAG_PREVIOUSLY_USED_IDS)) break;
            if (!isStartOfTag(parser, XML_TAG_PACKAGE)) continue;

            final String packageName = readStringAttribute(parser, XML_ATTR_PACKAGE_NAME);
            final Set<Integer> usedIds = new HashSet<>();

            while (true) {
                parser.nextTag();
                if (isEndOfTag(parser, XML_TAG_PACKAGE)) break;
                if (!isStartOfTag(parser, XML_TAG_ID)) continue;

                parser.nextToken();
                final int id = Integer.parseInt(parser.getText());
                usedIds.add(id);
            }

            out.put(packageName, usedIds);
        }
    }

    private static void writeAssociations(@NonNull XmlSerializer parent,
            @Nullable Collection<AssociationInfo> associations) throws IOException {
        final XmlSerializer serializer = parent.startTag(null, XML_TAG_ASSOCIATIONS);
        for (AssociationInfo association : associations) {
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
        writeStringAttribute(serializer, XML_ATTR_MAC_ADDRESS, a.getDeviceMacAddressAsString());
        writeStringAttribute(serializer, XML_ATTR_DISPLAY_NAME, a.getDisplayName());
        writeBooleanAttribute(serializer, XML_ATTR_SELF_MANAGED, a.isSelfManaged());
        writeBooleanAttribute(
                serializer, XML_ATTR_NOTIFY_DEVICE_NEARBY, a.isNotifyOnDeviceNearby());
        writeLongAttribute(serializer, XML_ATTR_TIME_APPROVED, a.getTimeApprovedMs());

        serializer.endTag(null, XML_TAG_ASSOCIATION);
    }

    private static void writePreviouslyUsedIds(@NonNull XmlSerializer parent,
            @NonNull Map<String, Set<Integer>> previouslyUsedIdsPerPackage) throws IOException {
        final XmlSerializer serializer = parent.startTag(null, XML_TAG_PREVIOUSLY_USED_IDS);
        for (Map.Entry<String, Set<Integer>> entry : previouslyUsedIdsPerPackage.entrySet()) {
            writePreviouslyUsedIdsForPackage(serializer, entry.getKey(), entry.getValue());
        }
        serializer.endTag(null, XML_TAG_PREVIOUSLY_USED_IDS);
    }

    private static void writePreviouslyUsedIdsForPackage(@NonNull XmlSerializer parent,
            @NonNull String packageName, @NonNull Set<Integer> previouslyUsedIds)
            throws IOException {
        final XmlSerializer serializer = parent.startTag(null, XML_TAG_PACKAGE);
        writeStringAttribute(serializer, XML_ATTR_PACKAGE_NAME, packageName);
        forEach(previouslyUsedIds, id -> serializer.startTag(null, XML_TAG_ID)
                .text(Integer.toString(id))
                .endTag(null, XML_TAG_ID));
        serializer.endTag(null, XML_TAG_PACKAGE);
    }

    private static boolean isStartOfTag(@NonNull XmlPullParser parser, @NonNull String tag)
            throws XmlPullParserException {
        return parser.getEventType() == START_TAG && tag.equals(parser.getName());
    }

    private static boolean isEndOfTag(@NonNull XmlPullParser parser, @NonNull String tag)
            throws XmlPullParserException {
        return parser.getEventType() == END_TAG && tag.equals(parser.getName());
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

    private static AssociationInfo createAssociationInfoNoThrow(int associationId,
            @UserIdInt int userId, @NonNull String appPackage, @Nullable MacAddress macAddress,
            @Nullable CharSequence displayName, @Nullable String profile, boolean selfManaged,
            boolean notify, long timeApproved) {
        AssociationInfo associationInfo = null;
        try {
            associationInfo = new AssociationInfo(associationId, userId, appPackage, macAddress,
                    displayName, profile, selfManaged, notify, timeApproved);
        } catch (Exception e) {
            if (DEBUG) Slog.w(LOG_TAG, "Could not create AssociationInfo", e);
        }
        return associationInfo;
    }
}
