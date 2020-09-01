/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.chunking;

import android.content.Context;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.encryption.protos.nano.ChunksMetadataProto;
import com.android.server.backup.encryption.protos.nano.KeyValueListingProto;

import com.google.protobuf.nano.MessageNano;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores a nano proto for each package, persisting the proto to disk.
 *
 * <p>This is used to store {@link ChunksMetadataProto.ChunkListing}.
 *
 * @param <T> the type of nano proto to store.
 */
public class ProtoStore<T extends MessageNano> {
    private static final String CHUNK_LISTING_FOLDER = "backup_chunk_listings";
    private static final String KEY_VALUE_LISTING_FOLDER = "backup_kv_listings";

    private static final String TAG = "BupEncProtoStore";

    private final File mStoreFolder;
    private final Class<T> mClazz;

    /** Creates a new instance which stores chunk listings at the default location. */
    public static ProtoStore<ChunksMetadataProto.ChunkListing> createChunkListingStore(
            Context context) throws IOException {
        return new ProtoStore<>(
                ChunksMetadataProto.ChunkListing.class,
                new File(context.getFilesDir().getAbsoluteFile(), CHUNK_LISTING_FOLDER));
    }

    /** Creates a new instance which stores key value listings in the default location. */
    public static ProtoStore<KeyValueListingProto.KeyValueListing> createKeyValueListingStore(
            Context context) throws IOException {
        return new ProtoStore<>(
                KeyValueListingProto.KeyValueListing.class,
                new File(context.getFilesDir().getAbsoluteFile(), KEY_VALUE_LISTING_FOLDER));
    }

    /**
     * Creates a new instance which stores protos in the given folder.
     *
     * @param storeFolder The location where the serialized form is stored.
     */
    @VisibleForTesting
    ProtoStore(Class<T> clazz, File storeFolder) throws IOException {
        mClazz = Objects.requireNonNull(clazz);
        mStoreFolder = ensureDirectoryExistsOrThrow(storeFolder);
    }

    private static File ensureDirectoryExistsOrThrow(File directory) throws IOException {
        if (directory.exists() && !directory.isDirectory()) {
            throw new IOException("Store folder already exists, but isn't a directory.");
        }

        if (!directory.exists() && !directory.mkdir()) {
            throw new IOException("Unable to create store folder.");
        }

        return directory;
    }

    /**
     * Returns the chunk listing for the given package, or {@link Optional#empty()} if no listing
     * exists.
     */
    public Optional<T> loadProto(String packageName)
            throws IOException, IllegalAccessException, InstantiationException,
            NoSuchMethodException, InvocationTargetException {
        File file = getFileForPackage(packageName);

        if (!file.exists()) {
            Slog.d(
                    TAG,
                    "No chunk listing existed for " + packageName + ", returning empty listing.");
            return Optional.empty();
        }

        AtomicFile protoStore = new AtomicFile(file);
        byte[] data = protoStore.readFully();

        Constructor<T> constructor = mClazz.getDeclaredConstructor();
        T proto = constructor.newInstance();
        MessageNano.mergeFrom(proto, data);
        return Optional.of(proto);
    }

    /** Saves a proto to disk, associating it with the given package. */
    public void saveProto(String packageName, T proto) throws IOException {
        Objects.requireNonNull(proto);
        File file = getFileForPackage(packageName);

        try (FileOutputStream os = new FileOutputStream(file)) {
            os.write(MessageNano.toByteArray(proto));
        } catch (IOException e) {
            Slog.e(
                    TAG,
                    "Exception occurred when saving the listing for "
                            + packageName
                            + ", deleting saved listing.",
                    e);

            // If a problem occurred when writing the listing then it might be corrupt, so delete
            // it.
            file.delete();

            throw e;
        }
    }

    /** Deletes the proto for the given package, or does nothing if the package has no proto. */
    public void deleteProto(String packageName) {
        File file = getFileForPackage(packageName);
        file.delete();
    }

    /** Deletes every proto of this type, for all package names. */
    public void deleteAllProtos() {
        File[] files = mStoreFolder.listFiles();

        // We ensure that the storeFolder exists in the constructor, but check just in case it has
        // mysteriously disappeared.
        if (files == null) {
            return;
        }

        for (File file : files) {
            file.delete();
        }
    }

    private File getFileForPackage(String packageName) {
        checkPackageName(packageName);
        return new File(mStoreFolder, packageName);
    }

    private static void checkPackageName(String packageName) {
        if (TextUtils.isEmpty(packageName) || packageName.contains("/")) {
            throw new IllegalArgumentException(
                    "Package name must not contain '/' or be empty: " + packageName);
        }
    }
}
