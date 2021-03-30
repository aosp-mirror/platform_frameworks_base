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

import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_WRITE_ONLY;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.appsearch.exceptions.AppSearchException;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.android.internal.infra.AndroidFuture;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final int mUserId;
    private final File mMigratedFile;
    private final Map<String, Integer> mCurrentVersionMap;
    private final Map<String, Integer> mFinalVersionMap;
    private boolean mAreDocumentsMigrated = false;

    AppSearchMigrationHelper(@NonNull IAppSearchManager service,
            @UserIdInt int userId,
            @NonNull Map<String, Integer> currentVersionMap,
            @NonNull Map<String, Integer> finalVersionMap,
            @NonNull String packageName,
            @NonNull String databaseName) throws IOException {
        mService = Objects.requireNonNull(service);
        mCurrentVersionMap = Objects.requireNonNull(currentVersionMap);
        mFinalVersionMap = Objects.requireNonNull(finalVersionMap);
        mPackageName = Objects.requireNonNull(packageName);
        mDatabaseName = Objects.requireNonNull(databaseName);
        mUserId = userId;
        mMigratedFile = File.createTempFile(/*prefix=*/"appsearch", /*suffix=*/null);
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
    public void queryAndTransform(@NonNull String schemaType, @NonNull Migrator migrator)
            throws IOException, AppSearchException, InterruptedException, ExecutionException {
        File queryFile = File.createTempFile(/*prefix=*/"appsearch", /*suffix=*/null);
        try (ParcelFileDescriptor fileDescriptor =
                     ParcelFileDescriptor.open(queryFile, MODE_WRITE_ONLY)) {
            AndroidFuture<AppSearchResult<Void>> androidFuture = new AndroidFuture<>();
            mService.writeQueryResultsToFile(mPackageName, mDatabaseName,
                    fileDescriptor,
                    /*queryExpression=*/ "",
                    new SearchSpec.Builder()
                            .addFilterSchemas(schemaType)
                            .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                            .build().getBundle(),
                    mUserId,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResult result) throws RemoteException {
                            androidFuture.complete(result);
                        }
                    });
            AppSearchResult<Void> result = androidFuture.get();
            if (!result.isSuccess()) {
                throw new AppSearchException(result.getResultCode(), result.getErrorMessage());
            }
            readAndTransform(queryFile, migrator);
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
            AndroidFuture<AppSearchResult<List<Bundle>>> androidFuture = new AndroidFuture<>();
            mService.putDocumentsFromFile(mPackageName, mDatabaseName, fileDescriptor, mUserId,
                    new IAppSearchResultCallback.Stub() {
                        @Override
                        public void onResult(AppSearchResult result) throws RemoteException {
                            androidFuture.complete(result);
                        }
                    });
            AppSearchResult<List<Bundle>> result = androidFuture.get();
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
    private void readAndTransform(@NonNull File file, @NonNull Migrator migrator)
            throws IOException {
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

                int currentVersion = mCurrentVersionMap.get(document.getSchemaType());
                int finalVersion = mFinalVersionMap.get(document.getSchemaType());

                GenericDocument newDocument;
                if (currentVersion < finalVersion) {
                    newDocument = migrator.onUpgrade(currentVersion, finalVersion, document);
                } else {
                    // currentVersion == finalVersion case won't trigger migration and get here.
                    newDocument = migrator.onDowngrade(currentVersion, finalVersion, document);
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
