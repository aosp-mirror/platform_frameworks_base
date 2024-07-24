/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.presence;

import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static com.android.server.companion.utils.DataStoreUtils.createStorageFileForUser;
import static com.android.server.companion.utils.DataStoreUtils.isEndOfTag;
import static com.android.server.companion.utils.DataStoreUtils.isStartOfTag;
import static com.android.server.companion.utils.DataStoreUtils.writeToFileSafely;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.ParcelUuid;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

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
 * This store manages the cache and disk data for observable uuids.
 */
public class ObservableUuidStore {
    private static final String TAG = "CDM_ObservableUuidStore";
    private static final String FILE_NAME = "observing_uuids_presence.xml";
    private static final String XML_TAG_UUIDS = "uuids";
    private static final String XML_TAG_UUID = "uuid";
    private static final String XML_ATTR_UUID = "uuid";
    private static final String XML_ATTR_TIME_APPROVED = "time_approved";
    private static final String XML_ATTR_USER_ID = "user_id";
    private static final String XML_ATTR_PACKAGE = "package_name";
    private static final int READ_FROM_DISK_TIMEOUT = 5; // in seconds


    private final ExecutorService mExecutor;
    private final ConcurrentMap<Integer, AtomicFile> mUserIdToStorageFile =
            new ConcurrentHashMap<>();

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArray<List<ObservableUuid>> mCachedPerUser =
            new SparseArray<>();

    public ObservableUuidStore() {
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Remove the observable uuid.
     */
    public void removeObservableUuid(@UserIdInt int userId, ParcelUuid uuid, String packageName) {
        List<ObservableUuid> cachedObservableUuids;

        synchronized (mLock) {
            // Remove requests from cache
            cachedObservableUuids = readObservableUuidsFromCache(userId);
            cachedObservableUuids.removeIf(
                    uuid1 -> uuid1.getPackageName().equals(packageName)
                            && uuid1.getUuid().equals(uuid));
            mCachedPerUser.set(userId, cachedObservableUuids);
        }
        // Remove requests from store
        mExecutor.execute(() -> writeObservableUuidToStore(userId, cachedObservableUuids));
    }

    /**
     * Write the observable uuid.
     */
    public void writeObservableUuid(@UserIdInt int userId, ObservableUuid uuid) {
        Slog.i(TAG, "Writing uuid=" + uuid.getUuid() + " to store.");

        List<ObservableUuid> cachedObservableUuids;
        synchronized (mLock) {
            // Write to cache
            cachedObservableUuids = readObservableUuidsFromCache(userId);
            cachedObservableUuids.removeIf(uuid1 -> uuid1.getUuid().equals(
                    uuid.getUuid()) && uuid1.getPackageName().equals(uuid.getPackageName()));
            cachedObservableUuids.add(uuid);
            mCachedPerUser.set(userId, cachedObservableUuids);
        }
        // Write to store
        mExecutor.execute(() -> writeObservableUuidToStore(userId, cachedObservableUuids));
    }

    private void writeObservableUuidToStore(@UserIdInt int userId,
            @NonNull List<ObservableUuid> cachedObservableUuids) {
        final AtomicFile file = getStorageFileForUser(userId);
        Slog.i(TAG, "Writing ObservableUuid for user " + userId + " to file="
                + file.getBaseFile().getPath());

        // getStorageFileForUser() ALWAYS returns the SAME OBJECT, which allows us to synchronize
        // accesses to the file on the file system using this AtomicFile object.
        synchronized (file) {
            writeToFileSafely(file, out -> {
                final TypedXmlSerializer serializer = Xml.resolveSerializer(out);
                serializer.setFeature(
                        "http://xmlpull.org/v1/doc/features.html#indent-output", true);
                serializer.startDocument(null, true);
                writeObservableUuidToXml(serializer, cachedObservableUuids);
                serializer.endDocument();
            });
        }
    }

    private void writeObservableUuidToXml(@NonNull TypedXmlSerializer serializer,
            @Nullable Collection<ObservableUuid> uuids) throws IOException {
        serializer.startTag(null, XML_TAG_UUIDS);

        for (ObservableUuid uuid : uuids) {
            writeUuidToXml(serializer, uuid);
        }

        serializer.endTag(null, XML_TAG_UUIDS);
    }

    private void writeUuidToXml(@NonNull TypedXmlSerializer serializer,
            @NonNull ObservableUuid uuid) throws IOException {
        serializer.startTag(null, XML_TAG_UUID);

        writeIntAttribute(serializer, XML_ATTR_USER_ID, uuid.getUserId());
        writeStringAttribute(serializer, XML_ATTR_UUID, uuid.getUuid().toString());
        writeStringAttribute(serializer, XML_ATTR_PACKAGE, uuid.getPackageName());
        writeLongAttribute(serializer, XML_ATTR_TIME_APPROVED, uuid.getTimeApprovedMs());

        serializer.endTag(null, XML_TAG_UUID);
    }

    /**
     * Read the observable UUIDs from the cache.
     */
    @GuardedBy("mLock")
    private List<ObservableUuid> readObservableUuidsFromCache(@UserIdInt int userId) {
        List<ObservableUuid> cachedObservableUuids = mCachedPerUser.get(userId);
        if (cachedObservableUuids == null) {
            Future<List<ObservableUuid>> future =
                    mExecutor.submit(() -> readObservableUuidFromStore(userId));
            try {
                cachedObservableUuids = future.get(READ_FROM_DISK_TIMEOUT, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Slog.e(TAG, "Thread reading ObservableUuid from disk is "
                        + "interrupted.");
            } catch (ExecutionException e) {
                Slog.e(TAG, "Error occurred while reading ObservableUuid "
                        + "from disk.");
            } catch (TimeoutException e) {
                Slog.e(TAG, "Reading ObservableUuid from disk timed out.");
            }
            mCachedPerUser.set(userId, cachedObservableUuids);
        }
        return cachedObservableUuids;
    }

    /**
     * Reads previously persisted data for the given user
     *
     * @param userId Android UserID
     * @return a list of ObservableUuid
     */
    @NonNull
    public List<ObservableUuid> readObservableUuidFromStore(@UserIdInt int userId) {
        final AtomicFile file = getStorageFileForUser(userId);
        Slog.i(TAG, "Reading ObservableUuid for user " + userId + " from "
                + "file=" + file.getBaseFile().getPath());

        // getStorageFileForUser() ALWAYS returns the SAME OBJECT, which allows us to synchronize
        // accesses to the file on the file system using this AtomicFile object.
        synchronized (file) {
            if (!file.getBaseFile().exists()) {
                Slog.d(TAG, "File does not exist -> Abort");
                return new ArrayList<>();
            }
            try (FileInputStream in = file.openRead()) {
                final TypedXmlPullParser parser = Xml.resolvePullParser(in);
                XmlUtils.beginDocument(parser, XML_TAG_UUIDS);

                return readObservableUuidFromXml(parser);
            } catch (XmlPullParserException | IOException e) {
                Slog.e(TAG, "Error while reading requests file", e);
                return new ArrayList<>();
            }
        }
    }

    @NonNull
    private List<ObservableUuid> readObservableUuidFromXml(
            @NonNull TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        if (!isStartOfTag(parser, XML_TAG_UUIDS)) {
            throw new XmlPullParserException("The XML doesn't have start tag: " + XML_TAG_UUIDS);
        }

        List<ObservableUuid> observableUuids = new ArrayList<>();

        while (true) {
            parser.nextTag();
            if (isEndOfTag(parser, XML_TAG_UUIDS)) {
                break;
            }
            if (isStartOfTag(parser, XML_TAG_UUID)) {
                observableUuids.add(readUuidFromXml(parser));
            }
        }

        return observableUuids;
    }

    private ObservableUuid readUuidFromXml(@NonNull TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        if (!isStartOfTag(parser, XML_TAG_UUID)) {
            throw new XmlPullParserException("XML doesn't have start tag: " + XML_TAG_UUID);
        }

        final int userId = readIntAttribute(parser, XML_ATTR_USER_ID);
        final ParcelUuid uuid = ParcelUuid.fromString(readStringAttribute(parser, XML_ATTR_UUID));
        final String packageName = readStringAttribute(parser, XML_ATTR_PACKAGE);
        final Long timeApproved = readLongAttribute(parser, XML_ATTR_TIME_APPROVED);

        return new ObservableUuid(userId, uuid, packageName, timeApproved);
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

    /**
     * @return A list of ObservableUuids per package.
     */
    public List<ObservableUuid> getObservableUuidsForPackage(
            @UserIdInt int userId, @NonNull String packageName) {
        final List<ObservableUuid> uuidsTobeObservedPerPackage = new ArrayList<>();
        synchronized (mLock) {
            final List<ObservableUuid> uuids = readObservableUuidsFromCache(userId);

            for (ObservableUuid uuid : uuids) {
                if (uuid.getPackageName().equals(packageName)) {
                    uuidsTobeObservedPerPackage.add(uuid);
                }
            }
        }

        return uuidsTobeObservedPerPackage;
    }

    /**
     * @return A list of ObservableUuids per user.
     */
    public List<ObservableUuid> getObservableUuidsForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            return readObservableUuidsFromCache(userId);
        }
    }
}
