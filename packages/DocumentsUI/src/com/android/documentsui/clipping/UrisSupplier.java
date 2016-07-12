/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.clipping;

import static com.android.documentsui.clipping.DocumentClipper.OP_JUMBO_SELECTION_SIZE;
import static com.android.documentsui.clipping.DocumentClipper.OP_JUMBO_SELECTION_TAG;

import android.content.ClipData;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.Shared;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.services.FileOperation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * UrisSupplier provides doc uri list to {@link FileOperation}.
 *
 * <p>Under the hood it provides cross-process synchronization support such that its consumer doesn't
 * need to explicitly synchronize its access.
 */
public abstract class UrisSupplier implements Parcelable {

    public abstract int getItemCount();

    /**
     * Gets doc list. This may only be called once because it may read a file
     * to get the list.
     *
     * @param context We need context to obtain {@link ClipStorage}. It can't be sent in a parcel.
     */
    public Iterable<Uri> getUris(Context context) throws IOException {
        return getUris(DocumentsApplication.getClipStorage(context));
    }

    @VisibleForTesting
    abstract Iterable<Uri> getUris(ClipStorage storage) throws IOException;

    public void dispose() {}

    @Override
    public int describeContents() {
        return 0;
    }

    public static UrisSupplier create(ClipData clipData, Context context) throws IOException {
        UrisSupplier uris;
        PersistableBundle bundle = clipData.getDescription().getExtras();
        if (bundle.containsKey(OP_JUMBO_SELECTION_TAG)) {
            uris = new JumboUrisSupplier(clipData, context);
        } else {
            uris = new StandardUrisSupplier(clipData);
        }

        return uris;
    }

    public static UrisSupplier create(
            Selection selection, Function<String, Uri> uriBuilder, Context context)
            throws IOException {

        ClipStorage storage = DocumentsApplication.getClipStorage(context);

        List<Uri> uris = new ArrayList<>(selection.size());
        for (String id : selection) {
            uris.add(uriBuilder.apply(id));
        }

        return create(uris, storage);
    }

    @VisibleForTesting
    static UrisSupplier create(List<Uri> uris, ClipStorage storage) throws IOException {
        UrisSupplier urisSupplier = (uris.size() > Shared.MAX_DOCS_IN_INTENT)
                ? new JumboUrisSupplier(uris, storage)
                : new StandardUrisSupplier(uris);

        return urisSupplier;
    }

    private static class JumboUrisSupplier extends UrisSupplier {
        private static final String TAG = "JumboUrisSupplier";

        private final File mFile;
        private final int mSelectionSize;

        private final transient AtomicReference<ClipStorageReader> mReader =
                new AtomicReference<>();

        private JumboUrisSupplier(ClipData clipData, Context context) throws IOException {
            PersistableBundle bundle = clipData.getDescription().getExtras();
            final int tag = bundle.getInt(OP_JUMBO_SELECTION_TAG, ClipStorage.NO_SELECTION_TAG);
            assert(tag != ClipStorage.NO_SELECTION_TAG);
            mFile = DocumentsApplication.getClipStorage(context).getFile(tag);
            assert(mFile.exists());

            mSelectionSize = bundle.getInt(OP_JUMBO_SELECTION_SIZE);
            assert(mSelectionSize > Shared.MAX_DOCS_IN_INTENT);
        }

        private JumboUrisSupplier(Collection<Uri> uris, ClipStorage storage) throws IOException {
            final int tag = storage.claimStorageSlot();
            new ClipStorage.PersistTask(storage, uris, tag).execute();

            // There is a tiny race condition here. A job may starts to read before persist task
            // starts to write, but it has to beat an IPC and background task schedule, which is
            // pretty rare. Creating a symlink doesn't need that file to exist, but we can't assert
            // on its existence.
            mFile = storage.getFile(tag);
            mSelectionSize = uris.size();
        }

        @Override
        public int getItemCount() {
            return mSelectionSize;
        }

        @Override
        Iterable<Uri> getUris(ClipStorage storage) throws IOException {
            ClipStorageReader reader = mReader.getAndSet(storage.createReader(mFile));
            if (reader != null) {
                reader.close();
                mReader.get().close();
                throw new IllegalStateException("This method can only be called once.");
            }

            return mReader.get();
        }

        @Override
        public void dispose() {
            try {
                ClipStorageReader reader = mReader.get();
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to close the reader.", e);
            }

            // mFile is a symlink to the actual data file. Delete the symlink here so that we know
            // there is one fewer referrer that needs the data file. The actual data file will be
            // cleaned up during file slot rotation. See ClipStorage for more details.
            mFile.delete();
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("JumboUrisSupplier{");
            builder.append("file=").append(mFile.getAbsolutePath());
            builder.append(", selectionSize=").append(mSelectionSize);
            builder.append("}");
            return builder.toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mFile.getAbsolutePath());
            dest.writeInt(mSelectionSize);
        }

        private JumboUrisSupplier(Parcel in) {
            mFile = new File(in.readString());
            mSelectionSize = in.readInt();
        }

        public static final Parcelable.Creator<JumboUrisSupplier> CREATOR =
                new Parcelable.Creator<JumboUrisSupplier>() {

                    @Override
                    public JumboUrisSupplier createFromParcel(Parcel source) {
                        return new JumboUrisSupplier(source);
                    }

                    @Override
                    public JumboUrisSupplier[] newArray(int size) {
                        return new JumboUrisSupplier[size];
                    }
                };
    }

    /**
     * This class and its constructor is visible for testing to create test doubles of
     * {@link UrisSupplier}.
     */
    @VisibleForTesting
    public static class StandardUrisSupplier extends UrisSupplier {
        private final List<Uri> mDocs;

        private StandardUrisSupplier(ClipData clipData) {
            mDocs = listDocs(clipData);
        }

        @VisibleForTesting
        public StandardUrisSupplier(List<Uri> docs) {
            mDocs = docs;
        }

        private List<Uri> listDocs(ClipData clipData) {
            ArrayList<Uri> docs = new ArrayList<>(clipData.getItemCount());

            for (int i = 0; i < clipData.getItemCount(); ++i) {
                Uri uri = clipData.getItemAt(i).getUri();
                assert(uri != null);
                docs.add(uri);
            }

            return docs;
        }

        @Override
        public int getItemCount() {
            return mDocs.size();
        }

        @Override
        Iterable<Uri> getUris(ClipStorage storage) {
            return mDocs;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("StandardUrisSupplier{");
            builder.append("docs=").append(mDocs.toString());
            builder.append("}");
            return builder.toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeTypedList(mDocs);
        }

        private StandardUrisSupplier(Parcel in) {
            mDocs = in.createTypedArrayList(Uri.CREATOR);
        }

        public static final Parcelable.Creator<StandardUrisSupplier> CREATOR =
                new Parcelable.Creator<StandardUrisSupplier>() {

                    @Override
                    public StandardUrisSupplier createFromParcel(Parcel source) {
                        return new StandardUrisSupplier(source);
                    }

                    @Override
                    public StandardUrisSupplier[] newArray(int size) {
                        return new StandardUrisSupplier[size];
                    }
                };
    }
}
