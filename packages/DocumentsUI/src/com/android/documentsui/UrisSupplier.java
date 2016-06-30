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

package com.android.documentsui;

import static com.android.documentsui.DocumentClipper.OP_JUMBO_SELECTION_SIZE;
import static com.android.documentsui.DocumentClipper.OP_JUMBO_SELECTION_TAG;

import android.content.ClipData;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.services.FileOperation;

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
    public Iterable<Uri> getDocs(Context context) throws IOException {
        return getDocs(DocumentsApplication.getClipStorage(context));
    }

    @VisibleForTesting
    abstract Iterable<Uri> getDocs(ClipStorage storage) throws IOException;

    public void dispose(Context context) {
        ClipStorage storage = DocumentsApplication.getClipStorage(context);
        dispose(storage);
    }

    @VisibleForTesting
    void dispose(ClipStorage storage) {}

    @Override
    public int describeContents() {
        return 0;
    }

    public static UrisSupplier create(ClipData clipData) {
        UrisSupplier uris;
        PersistableBundle bundle = clipData.getDescription().getExtras();
        if (bundle.containsKey(OP_JUMBO_SELECTION_TAG)) {
            uris = new JumboUrisSupplier(clipData);
        } else {
            uris = new StandardUrisSupplier(clipData);
        }

        return uris;
    }

    public static UrisSupplier create(
            Selection selection, Function<String, Uri> uriBuilder, Context context) {
        ClipStorage storage = DocumentsApplication.getClipStorage(context);

        List<Uri> uris = new ArrayList<>(selection.size());
        for (String id : selection) {
            uris.add(uriBuilder.apply(id));
        }

        return create(uris, storage);
    }

    @VisibleForTesting
    static UrisSupplier create(List<Uri> uris, ClipStorage storage) {
        UrisSupplier urisSupplier = (uris.size() > Shared.MAX_DOCS_IN_INTENT)
                ? new JumboUrisSupplier(uris, storage)
                : new StandardUrisSupplier(uris);

        return urisSupplier;
    }

    private static class JumboUrisSupplier extends UrisSupplier {
        private static final String TAG = "JumboUrisSupplier";

        private final long mSelectionTag;
        private final int mSelectionSize;

        private final transient AtomicReference<ClipStorage.Reader> mReader =
                new AtomicReference<>();

        private JumboUrisSupplier(ClipData clipData) {
            PersistableBundle bundle = clipData.getDescription().getExtras();
            mSelectionTag = bundle.getLong(OP_JUMBO_SELECTION_TAG, ClipStorage.NO_SELECTION_TAG);
            assert(mSelectionTag != ClipStorage.NO_SELECTION_TAG);

            mSelectionSize = bundle.getInt(OP_JUMBO_SELECTION_SIZE);
            assert(mSelectionSize > Shared.MAX_DOCS_IN_INTENT);
        }

        private JumboUrisSupplier(Collection<Uri> uris, ClipStorage storage) {
            mSelectionTag = storage.createTag();
            new ClipStorage.PersistTask(storage, uris, mSelectionTag).execute();
            mSelectionSize = uris.size();
        }

        @Override
        public int getItemCount() {
            return mSelectionSize;
        }

        @Override
        Iterable<Uri> getDocs(ClipStorage storage) throws IOException {
            ClipStorage.Reader reader = mReader.getAndSet(storage.createReader(mSelectionTag));
            if (reader != null) {
                reader.close();
                mReader.get().close();
                throw new IllegalStateException("This method can only be called once.");
            }

            return mReader.get();
        }

        @Override
        void dispose(ClipStorage storage) {
            try {
                ClipStorage.Reader reader = mReader.get();
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to close the reader.", e);
            }
            try {
                storage.delete(mSelectionTag);
            } catch(IOException e) {
                Log.w(TAG, "Failed to delete clip with tag: " + mSelectionTag + ".", e);
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("JumboUrisSupplier{");
            builder.append("selectionTag=").append(mSelectionTag);
            builder.append(", selectionSize=").append(mSelectionSize);
            builder.append("}");
            return builder.toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(mSelectionTag);
            dest.writeInt(mSelectionSize);
        }

        private JumboUrisSupplier(Parcel in) {
            mSelectionTag = in.readLong();
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
        Iterable<Uri> getDocs(ClipStorage storage) {
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
