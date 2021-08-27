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

package android.app.appsearch;

import static android.app.appsearch.AppSearchResult.RESULT_INVALID_SCHEMA;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_WRITE_ONLY;

import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.appsearch.aidl.AppSearchResultParcel;
import android.app.appsearch.aidl.IAppSearchManager;
import android.app.appsearch.aidl.IAppSearchResultCallback;
import android.app.appsearch.exceptions.AppSearchException;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * The helper class for {@link AppSearchSchema} migration.
 *
 * <p>It will query and migrate {@link GenericDocument} in given type to a new version.
 * @hide
 */
public class AppSearchMigrationHelper implements Closeable {
    private final IAppSearchManager mService;
    private final String mPackageName;
    private final String mDatabaseName;
    private final UserHandle mUserHandle;
    private final File mMigratedFile;
    private final Set<String> mDestinationTypes;
    private boolean mAreDocumentsMigrated = false;

    AppSearchMigrationHelper(@NonNull IAppSearchManager service,
            @NonNull UserHandle userHandle,
            @NonNull String packageName,
            @NonNull String databaseName,
            @NonNull Set<AppSearchSchema> newSchemas) throws IOException {
        mService = Objects.requireNonNull(service);
        mUserHandle = Objects.requireNonNull(userHandle);
        mPackageName = Objects.requireNonNull(packageName);
        mDatabaseName = Objects.requireNonNull(databaseName);
        mMigratedFile = File.createTempFile(/*prefix=*/"appsearch", /*suffix=*/null);
        mDestinationTypes = new ArraySet<>(newSchemas.size());
        for (AppSearchSchema newSchema : newSchemas) {
            mDestinationTypes.add(newSchema.getSchemaType());
        }
    }

    /**
     * Queries all documents that need to be migrated to a different version and transform
     * documents to that version by passing them to the provided {@link Migrator}.
     *
     * <p>The method will be executed on the executor provided to
     * {@link AppSearchSession#setSchema}.
     *
     * @param schemaType The schema type that needs to be updated and whose {@link GenericDocument}
     *                   need to be migrated.
     * @param migrator The {@link Migrator} that will upgrade or downgrade a {@link
     *     GenericDocument} to new version.
     */
    @WorkerThread
    public void queryAndTransform(@NonNull String schemaType, @NonNull Migrator migrator,
            int currentVersion, int finalVersion)
            throws IOException, AppSearchException, InterruptedException, ExecutionException {
        File queryFile = File.createTempFile(/*prefix=*/"appsearch", /*suffix=*/null);
        try (ParcelFileDescriptor fileDescriptor =
                     ParcelFileDescriptor.open(queryFile, MODE_WRITE_ONLY)) {
            CompletableFuture<AppSearchResult<Void>> future = new CompletableFuture<>();
            mService.writeQueryResultsToFile(mPackageName, mDatabaseName,
                    fileDescriptor,
                    /*queryExpression=*/ "",
                    new SearchSpec.Builder()
                            .addFilterSchemas(schemaType)
                            .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                            .build().getBundle(),
                    mUserHandle,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            future.complete(resultParcel.getResult());
                        }
                    });
            AppSearchResult<Void> result = future.get();
            if (!result.isSuccess()) {
                throw new AppSearchException(result.getResultCode(), result.getErrorMessage());
            }
            readAndTransform(queryFile, migrator, currentVersion, finalVersion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            queryFile.delete();
        }
    }

    /**
     * Puts all {@link GenericDocument} migrated from the previous call to
     * {@link #queryAndTransform} into AppSearch.
     *
     * <p> This method should be only called once.
     *
     * @param responseBuilder a SetSchemaResponse builder whose result will be returned by this
     *                        function with any
     *                        {@link android.app.appsearch.SetSchemaResponse.MigrationFailure}
     *                        added in.
     * @return the {@link SetSchemaResponse} for {@link AppSearchSession#setSchema} call.
     */
    @NonNull
    AppSearchResult<SetSchemaResponse> putMigratedDocuments(
            @NonNull SetSchemaResponse.Builder responseBuilder) {
        if (!mAreDocumentsMigrated) {
            return AppSearchResult.newSuccessfulResult(responseBuilder.build());
        }
        try (ParcelFileDescriptor fileDescriptor =
                     ParcelFileDescriptor.open(mMigratedFile, MODE_READ_ONLY)) {
            CompletableFuture<AppSearchResult<List<Bundle>>> future = new CompletableFuture<>();
            mService.putDocumentsFromFile(mPackageName, mDatabaseName, fileDescriptor, mUserHandle,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResultParcel resultParcel) {
                            future.complete(resultParcel.getResult());
                        }
                    });
            AppSearchResult<List<Bundle>> result = future.get();
            if (!result.isSuccess()) {
                return AppSearchResult.newFailedResult(result);
            }
            List<Bundle> migratedFailureBundles = result.getResultValue();
            for (int i = 0; i < migratedFailureBundles.size(); i++) {
                responseBuilder.addMigrationFailure(
                        new SetSchemaResponse.MigrationFailure(migratedFailureBundles.get(i)));
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (Throwable t) {
            return AppSearchResult.throwableToFailedResult(t);
        } finally {
            mMigratedFile.delete();
        }
        return AppSearchResult.newSuccessfulResult(responseBuilder.build());
    }

    /**
     * Reads all saved {@link GenericDocument}s from the given {@link File}.
     *
     * <p>Transforms those {@link GenericDocument}s to the final version.
     *
     * <p>Save migrated {@link GenericDocument}s to the {@link #mMigratedFile}.
     */
    private void readAndTransform(@NonNull File file, @NonNull Migrator migrator,
            int currentVersion, int finalVersion)
            throws IOException, AppSearchException {
        try (DataInputStream inputStream = new DataInputStream(new FileInputStream(file));
             DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(
                     mMigratedFile, /*append=*/ true))) {
            GenericDocument document;
            while (true) {
                try {
                    document = readDocumentFromInputStream(inputStream);
                } catch (EOFException e) {
                    break;
                    // Nothing wrong. We just finished reading.
                }

                GenericDocument newDocument;
                if (currentVersion < finalVersion) {
                    newDocument = migrator.onUpgrade(currentVersion, finalVersion, document);
                } else {
                    // currentVersion == finalVersion case won't trigger migration and get here.
                    newDocument = migrator.onDowngrade(currentVersion, finalVersion, document);
                }

                if (!mDestinationTypes.contains(newDocument.getSchemaType())) {
                    // we exit before the new schema has been set to AppSearch. So no
                    // observable changes will be applied to stored schemas and documents.
                    // And the temp file will be deleted at close(), which will be triggered at
                    // the end of try-with-resources block of SearchSessionImpl.
                    throw new AppSearchException(
                            RESULT_INVALID_SCHEMA,
                            "Receive a migrated document with schema type: "
                                    + newDocument.getSchemaType()
                                    + ". But the schema types doesn't exist in the request");
                }
                writeBundleToOutputStream(outputStream, newDocument.getBundle());
            }
            mAreDocumentsMigrated = true;
        }
    }

    /**
     * Reads the {@link Bundle} of a {@link GenericDocument} from given {@link DataInputStream}.
     *
     * @param inputStream The inputStream to read from
     *
     * @throws IOException        on read failure.
     * @throws EOFException       if {@link java.io.InputStream} reaches the end.
     */
    @NonNull
    public static GenericDocument readDocumentFromInputStream(
            @NonNull DataInputStream inputStream) throws IOException {
        int length = inputStream.readInt();
        if (length == 0) {
            throw new EOFException();
        }
        byte[] serializedMessage = new byte[length];
        inputStream.read(serializedMessage);

        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(serializedMessage, 0, serializedMessage.length);
            parcel.setDataPosition(0);
            Bundle bundle = parcel.readBundle();
            return new GenericDocument(bundle);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * Serializes a {@link Bundle} and writes into the given {@link DataOutputStream}.
     */
    public static void writeBundleToOutputStream(
            @NonNull DataOutputStream outputStream, @NonNull Bundle bundle)
            throws IOException {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(bundle);
            byte[] serializedMessage = parcel.marshall();
            outputStream.writeInt(serializedMessage.length);
            outputStream.write(serializedMessage);
        } finally {
            parcel.recycle();
        }
    }

    @Override
    public void close() throws IOException {
        mMigratedFile.delete();
    }
}
