/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readThisListXml;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeListXml;
import static com.android.server.companion.DataStoreUtils.createStorageFileForUser;
import static com.android.server.companion.DataStoreUtils.isEndOfTag;
import static com.android.server.companion.DataStoreUtils.isStartOfTag;
import static com.android.server.companion.DataStoreUtils.writeToFileSafely;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.companion.SystemDataTransferRequest;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The class is responsible for reading/writing SystemDataTransferRequest records from/to the disk.
 *
 * The following snippet is a sample XML file stored in the disk.
 * <pre>{@code
 * <requests>
 *   <request
 *     association_id="1"
 *     is_permission_sync_all_packages="false">
 *     <list name="permission_sync_packages">
 *       <string>com.sample.app1</string>
 *       <string>com.sample.app2</string>
 *     </list>
 *   </request>
 * </requests>
 * }</pre>
 */
public class SystemDataTransferRequestDataStore {

    private static final String LOG_TAG = SystemDataTransferRequestDataStore.class.getSimpleName();

    private static final String FILE_NAME = "companion_device_system_data_transfer_requests.xml";

    private static final String XML_TAG_REQUESTS = "requests";
    private static final String XML_TAG_REQUEST = "request";
    private static final String XML_TAG_LIST = "list";

    private static final String XML_ATTR_ASSOCIATION_ID = "association_id";
    private static final String XML_ATTR_IS_PERMISSION_SYNC_ALL_PACKAGES =
            "is_permission_sync_all_packages";
    private static final String XML_ATTR_PERMISSION_SYNC_PACKAGES = "permission_sync_packages";

    private final ConcurrentMap<Integer, AtomicFile> mUserIdToStorageFile =
            new ConcurrentHashMap<>();

    /**
     * Reads previously persisted data for the given user
     *
     * @param userId Android UserID
     * @return a list of SystemDataTransferRequest
     */
    @NonNull
    List<SystemDataTransferRequest> readRequestsForUser(@UserIdInt int userId) {
        final AtomicFile file = getStorageFileForUser(userId);
        Slog.i(LOG_TAG, "Reading SystemDataTransferRequests for user " + userId + " from "
                + "file=" + file.getBaseFile().getPath());

        // getStorageFileForUser() ALWAYS returns the SAME OBJECT, which allows us to synchronize
        // accesses to the file on the file system using this AtomicFile object.
        synchronized (file) {
            if (!file.getBaseFile().exists()) {
                Slog.d(LOG_TAG, "File does not exist -> Abort");
                return Collections.emptyList();
            }
            try (FileInputStream in = file.openRead()) {
                final TypedXmlPullParser parser = Xml.resolvePullParser(in);
                XmlUtils.beginDocument(parser, XML_TAG_REQUESTS);

                return readRequests(parser);
            } catch (XmlPullParserException | IOException e) {
                Slog.e(LOG_TAG, "Error while reading requests file", e);
                return Collections.emptyList();
            }
        }
    }

    @NonNull
    private List<SystemDataTransferRequest> readRequests(@NonNull TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        if (!isStartOfTag(parser, XML_TAG_REQUESTS)) {
            throw new XmlPullParserException("The XML doesn't have start tag: " + XML_TAG_REQUESTS);
        }

        List<SystemDataTransferRequest> requests = new ArrayList<>();

        while (true) {
            parser.nextTag();
            if (isEndOfTag(parser, XML_TAG_REQUESTS)) break;
            if (isStartOfTag(parser, XML_TAG_REQUEST)) {
                requests.add(readRequest(parser));
            }
        }

        return requests;
    }

    private SystemDataTransferRequest readRequest(@NonNull TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        if (!isStartOfTag(parser, XML_TAG_REQUEST)) {
            throw new XmlPullParserException("XML doesn't have start tag: " + XML_TAG_REQUEST);
        }

        final int associationId = readIntAttribute(parser, XML_ATTR_ASSOCIATION_ID);
        final boolean isPermissionSyncAllPackages = readBooleanAttribute(parser,
                XML_ATTR_IS_PERMISSION_SYNC_ALL_PACKAGES);
        parser.nextTag();
        List<String> permissionSyncPackages = new ArrayList<>();
        if (isStartOfTag(parser, XML_TAG_LIST)) {
            parser.nextTag();
            permissionSyncPackages = readThisListXml(parser, XML_TAG_LIST,
                    new String[1]);
        }

        return new SystemDataTransferRequest(associationId, isPermissionSyncAllPackages,
                permissionSyncPackages);
    }

    /**
     * Persisted user's SystemDataTransferRequest data to the disk.
     *
     * @param userId   Android UserID
     * @param requests a list of user's SystemDataTransferRequest.
     */
    void writeRequestsForUser(@UserIdInt int userId,
            @NonNull List<SystemDataTransferRequest> requests) {
        final AtomicFile file = getStorageFileForUser(userId);
        Slog.i(LOG_TAG, "Writing SystemDataTransferRequests for user " + userId + " to file="
                + file.getBaseFile().getPath());

        // getStorageFileForUser() ALWAYS returns the SAME OBJECT, which allows us to synchronize
        // accesses to the file on the file system using this AtomicFile object.
        synchronized (file) {
            writeToFileSafely(file, out -> {
                final TypedXmlSerializer serializer = Xml.resolveSerializer(out);
                serializer.setFeature(
                        "http://xmlpull.org/v1/doc/features.html#indent-output", true);
                serializer.startDocument(null, true);
                writeRequests(serializer, requests);
                serializer.endDocument();
            });
        }
    }

    private void writeRequests(@NonNull TypedXmlSerializer serializer,
            @Nullable Collection<SystemDataTransferRequest> requests) throws IOException {
        serializer.startTag(null, XML_TAG_REQUESTS);

        for (SystemDataTransferRequest request : requests) {
            writeRequest(serializer, request);
        }

        serializer.endTag(null, XML_TAG_REQUESTS);
    }

    private void writeRequest(@NonNull TypedXmlSerializer serializer,
            @NonNull SystemDataTransferRequest request) throws IOException {
        serializer.startTag(null, XML_TAG_REQUEST);

        writeIntAttribute(serializer, XML_ATTR_ASSOCIATION_ID, request.getAssociationId());
        writeBooleanAttribute(serializer, XML_ATTR_IS_PERMISSION_SYNC_ALL_PACKAGES,
                request.isPermissionSyncAllPackages());
        try {
            writeListXml(request.getPermissionSyncPackages(), XML_ATTR_PERMISSION_SYNC_PACKAGES,
                    serializer);
        } catch (XmlPullParserException e) {
            Slog.e(LOG_TAG, "Error writing permission sync packages into XML. "
                    + request.getPermissionSyncPackages().toString());
        }

        serializer.endTag(null, XML_TAG_REQUEST);
    }

    /**
     * Creates and caches {@link AtomicFile} object that represents the back-up file for the given
     * user.
     *
     * IMPORTANT: the method will ALWAYS return the same {@link AtomicFile} object, which makes it
     * possible to synchronize reads and writes to the file using the returned object.
     */
    private @NonNull AtomicFile getStorageFileForUser(@UserIdInt int userId) {
        return mUserIdToStorageFile.computeIfAbsent(userId,
                u -> createStorageFileForUser(userId, FILE_NAME));
    }
}
