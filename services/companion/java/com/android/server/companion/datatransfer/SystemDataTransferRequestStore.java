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

package com.android.server.companion.datatransfer;

import static android.companion.datatransfer.SystemDataTransferRequest.DATA_TYPE_PERMISSION_SYNC;

import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.server.companion.DataStoreUtils.createStorageFileForUser;
import static com.android.server.companion.DataStoreUtils.fileToByteArray;
import static com.android.server.companion.DataStoreUtils.isEndOfTag;
import static com.android.server.companion.DataStoreUtils.isStartOfTag;
import static com.android.server.companion.DataStoreUtils.writeToFileSafely;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.companion.datatransfer.PermissionSyncRequest;
import android.companion.datatransfer.SystemDataTransferRequest;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The class is responsible for reading/writing SystemDataTransferRequest records from/to the disk.
 * <p>
 * The following snippet is a sample XML file stored in the disk.
 * <pre>{@code
 * <requests>
 *   <request
 *     association_id="1"
 *     data_type="1"
 *     user_id="12"
 *     is_user_consented="true"
 *   </request>
 * </requests>
 * }</pre>
 */
public class SystemDataTransferRequestStore {

    private static final String LOG_TAG = "CDM_SystemDataTransferRequestStore";

    private static final String FILE_NAME = "companion_device_system_data_transfer_requests.xml";

    private static final String XML_TAG_REQUESTS = "requests";
    private static final String XML_TAG_REQUEST = "request";

    private static final String XML_ATTR_ASSOCIATION_ID = "association_id";
    private static final String XML_ATTR_DATA_TYPE = "data_type";
    private static final String XML_ATTR_USER_ID = "user_id";
    private static final String XML_ATTR_IS_USER_CONSENTED = "is_user_consented";

    private static final int READ_FROM_DISK_TIMEOUT = 5; // in seconds

    private final ExecutorService mExecutor;
    private final ConcurrentMap<Integer, AtomicFile> mUserIdToStorageFile =
            new ConcurrentHashMap<>();

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArray<ArrayList<SystemDataTransferRequest>> mCachedPerUser =
            new SparseArray<>();

    public SystemDataTransferRequestStore() {
        mExecutor = Executors.newSingleThreadExecutor();
    }

    @NonNull
    public List<SystemDataTransferRequest> readRequestsByAssociationId(@UserIdInt int userId,
            int associationId) {
        List<SystemDataTransferRequest> cachedRequests;
        synchronized (mLock) {
            cachedRequests = readRequestsFromCache(userId);
        }

        List<SystemDataTransferRequest> requestsByAssociationId = new ArrayList<>();
        for (SystemDataTransferRequest request : cachedRequests) {
            if (request.getAssociationId() == associationId) {
                requestsByAssociationId.add(request);
            }
        }
        return requestsByAssociationId;
    }

    public void writeRequest(@UserIdInt int userId, SystemDataTransferRequest request) {
        Slog.i(LOG_TAG, "Writing request=" + request + " to store.");
        ArrayList<SystemDataTransferRequest> cachedRequests;
        synchronized (mLock) {
            // Write to cache
            cachedRequests = readRequestsFromCache(userId);
            cachedRequests.removeIf(
                    request1 -> request1.getAssociationId() == request.getAssociationId());
            cachedRequests.add(request);
            mCachedPerUser.set(userId, cachedRequests);
        }
        // Write to store
        mExecutor.execute(() -> writeRequestsToStore(userId, cachedRequests));
    }

    /**
     * Remove requests by association id. userId must be the one which owns the associationId.
     */
    public void removeRequestsByAssociationId(@UserIdInt int userId, int associationId) {
        Slog.i(LOG_TAG, "Removing system data transfer requests for userId=" + userId
                + ", associationId=" + associationId);
        ArrayList<SystemDataTransferRequest> cachedRequests;
        synchronized (mLock) {
            // Remove requests from cache
            cachedRequests = readRequestsFromCache(userId);
            cachedRequests.removeIf(request -> request.getAssociationId() == associationId);
            mCachedPerUser.set(userId, cachedRequests);
        }
        // Remove requests from store
        mExecutor.execute(() -> writeRequestsToStore(userId, cachedRequests));
    }

    /**
     * Return the byte contents of the XML file storing current system data transfer requests.
     */
    public byte[] getBackupPayload(@UserIdInt int userId) {
        final AtomicFile file = getStorageFileForUser(userId);

        synchronized (file) {
            return fileToByteArray(file);
        }
    }

    /**
     * Parse the byte array containing XML information of system data transfer requests into
     * an array list of requests.
     */
    public List<SystemDataTransferRequest> readRequestsFromPayload(byte[] payload) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload)) {
            final TypedXmlPullParser parser = Xml.resolvePullParser(in);
            XmlUtils.beginDocument(parser, XML_TAG_REQUESTS);

            return readRequestsFromXml(parser);
        } catch (XmlPullParserException | IOException e) {
            Slog.e(LOG_TAG, "Error while reading requests file", e);
            return new ArrayList<>();
        }
    }

    @GuardedBy("mLock")
    private ArrayList<SystemDataTransferRequest> readRequestsFromCache(@UserIdInt int userId) {
        ArrayList<SystemDataTransferRequest> cachedRequests = mCachedPerUser.get(userId);
        if (cachedRequests == null) {
            Future<ArrayList<SystemDataTransferRequest>> future =
                    mExecutor.submit(() -> readRequestsFromStore(userId));
            try {
                cachedRequests = future.get(READ_FROM_DISK_TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Slog.e(LOG_TAG, "Thread reading SystemDataTransferRequest from disk is "
                        + "interrupted.");
            } catch (ExecutionException e) {
                Slog.e(LOG_TAG, "Error occurred while reading SystemDataTransferRequest "
                        + "from disk.");
            } catch (TimeoutException e) {
                Slog.e(LOG_TAG, "Reading SystemDataTransferRequest from disk timed out.");
            }
            mCachedPerUser.set(userId, cachedRequests);
        }
        return cachedRequests;
    }

    /**
     * Reads previously persisted data for the given user
     *
     * @param userId Android UserID
     * @return a list of SystemDataTransferRequest
     */
    @NonNull
    private ArrayList<SystemDataTransferRequest> readRequestsFromStore(@UserIdInt int userId) {
        final AtomicFile file = getStorageFileForUser(userId);
        Slog.i(LOG_TAG, "Reading SystemDataTransferRequests for user " + userId + " from "
                + "file=" + file.getBaseFile().getPath());

        // getStorageFileForUser() ALWAYS returns the SAME OBJECT, which allows us to synchronize
        // accesses to the file on the file system using this AtomicFile object.
        synchronized (file) {
            if (!file.getBaseFile().exists()) {
                Slog.d(LOG_TAG, "File does not exist -> Abort");
                return new ArrayList<>();
            }
            try (FileInputStream in = file.openRead()) {
                final TypedXmlPullParser parser = Xml.resolvePullParser(in);
                XmlUtils.beginDocument(parser, XML_TAG_REQUESTS);

                return readRequestsFromXml(parser);
            } catch (XmlPullParserException | IOException e) {
                Slog.e(LOG_TAG, "Error while reading requests file", e);
                return new ArrayList<>();
            }
        }
    }

    @NonNull
    private ArrayList<SystemDataTransferRequest> readRequestsFromXml(
            @NonNull TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        if (!isStartOfTag(parser, XML_TAG_REQUESTS)) {
            throw new XmlPullParserException("The XML doesn't have start tag: " + XML_TAG_REQUESTS);
        }

        ArrayList<SystemDataTransferRequest> requests = new ArrayList<>();

        while (true) {
            parser.nextTag();
            if (isEndOfTag(parser, XML_TAG_REQUESTS)) {
                break;
            }
            if (isStartOfTag(parser, XML_TAG_REQUEST)) {
                requests.add(readRequestFromXml(parser));
            }
        }

        return requests;
    }

    private SystemDataTransferRequest readRequestFromXml(@NonNull TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        if (!isStartOfTag(parser, XML_TAG_REQUEST)) {
            throw new XmlPullParserException("XML doesn't have start tag: " + XML_TAG_REQUEST);
        }

        final int associationId = readIntAttribute(parser, XML_ATTR_ASSOCIATION_ID);
        final int dataType = readIntAttribute(parser, XML_ATTR_DATA_TYPE);
        final int userId = readIntAttribute(parser, XML_ATTR_USER_ID);
        final boolean isUserConsented = readBooleanAttribute(parser, XML_ATTR_IS_USER_CONSENTED);

        switch (dataType) {
            case DATA_TYPE_PERMISSION_SYNC:
                PermissionSyncRequest request = new PermissionSyncRequest(associationId);
                request.setUserId(userId);
                request.setUserConsented(isUserConsented);
                return request;
            default:
                return null;
        }
    }

    /**
     * Persisted user's SystemDataTransferRequest data to the disk.
     *
     * @param userId   Android UserID
     * @param requests a list of user's SystemDataTransferRequest.
     */
    void writeRequestsToStore(@UserIdInt int userId,
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
                writeRequestsToXml(serializer, requests);
                serializer.endDocument();
            });
        }
    }

    private void writeRequestsToXml(@NonNull TypedXmlSerializer serializer,
            @Nullable Collection<SystemDataTransferRequest> requests) throws IOException {
        serializer.startTag(null, XML_TAG_REQUESTS);

        for (SystemDataTransferRequest request : requests) {
            writeRequestToXml(serializer, request);
        }

        serializer.endTag(null, XML_TAG_REQUESTS);
    }

    private void writeRequestToXml(@NonNull TypedXmlSerializer serializer,
            @NonNull SystemDataTransferRequest request) throws IOException {
        serializer.startTag(null, XML_TAG_REQUEST);

        writeIntAttribute(serializer, XML_ATTR_ASSOCIATION_ID, request.getAssociationId());
        writeIntAttribute(serializer, XML_ATTR_DATA_TYPE, request.getDataType());
        writeIntAttribute(serializer, XML_ATTR_USER_ID, request.getUserId());
        writeBooleanAttribute(serializer, XML_ATTR_IS_USER_CONSENTED, request.isUserConsented());

        serializer.endTag(null, XML_TAG_REQUEST);
    }

    /**
     * Creates and caches {@link AtomicFile} object that represents the back-up file for the given
     * user.
     * <p>
     * IMPORTANT: the method will ALWAYS return the same {@link AtomicFile} object, which makes it
     * possible to synchronize reads and writes to the file using the returned object.
     */
    @NonNull
    private AtomicFile getStorageFileForUser(@UserIdInt int userId) {
        return mUserIdToStorageFile.computeIfAbsent(userId,
                u -> createStorageFileForUser(userId, FILE_NAME));
    }
}
