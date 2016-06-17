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
import static com.android.documentsui.DocumentClipper.OP_TYPE_KEY;
import static com.android.documentsui.DocumentClipper.SRC_PARENT_KEY;

import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.content.ClipData;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * ClipDetails is a parcelable project providing information of different type of file
 * management operations like cut, move and copy.
 *
 * Under the hood it provides cross-process synchronization support such that its consumer doesn't
 * need to explicitly synchronize its access.
 */
public abstract class ClipDetails implements Parcelable {
    private final @OpType int mOpType;

    // This field is used only for moving and deleting. Currently it's not the case,
    // but in the future those files may be from multiple different parents. In
    // such case, this needs to be replaced with pairs of parent and child.
    private final @Nullable Uri mSrcParent;

    private ClipDetails(ClipData clipData) {
        PersistableBundle bundle = clipData.getDescription().getExtras();
        mOpType = bundle.getInt(OP_TYPE_KEY);

        String srcParentString = bundle.getString(SRC_PARENT_KEY);
        mSrcParent = (srcParentString == null) ? null : Uri.parse(srcParentString);

        // Only copy doesn't need src parent
        assert(mOpType == FileOperationService.OPERATION_COPY || mSrcParent != null);
    }

    private ClipDetails(@OpType int opType, @Nullable Uri srcParent) {
        mOpType = opType;
        mSrcParent = srcParent;

        // Only copy doesn't need src parent
        assert(mOpType == FileOperationService.OPERATION_COPY || mSrcParent != null);
    }

    public @OpType int getOpType() {
        return mOpType;
    }

    public @Nullable Uri getSrcParent() {
        return mSrcParent;
    }

    public abstract int getItemCount();

    /**
     * Gets doc list from this clip detail. This may only be called once because it may read a file
     * to get the list.
     */
    public Iterable<Uri> getDocs(Context context) throws IOException {
        ClipStorage storage = DocumentsApplication.getClipStorage(context);

        return getDocs(storage);
    }

    @VisibleForTesting
    abstract Iterable<Uri> getDocs(ClipStorage storage) throws IOException;

    public void dispose(Context context) {
        ClipStorage storage = DocumentsApplication.getClipStorage(context);
        dispose(storage);
    }

    @VisibleForTesting
    void dispose(ClipStorage storage) {}

    private ClipDetails(Parcel in) {
        mOpType = in.readInt();
        mSrcParent = in.readParcelable(ClassLoader.getSystemClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @CallSuper
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mOpType);
        dest.writeParcelable(mSrcParent, 0);
    }

    private void appendTo(StringBuilder builder) {
        builder.append("opType=").append(mOpType);
        builder.append(", srcParent=").append(mSrcParent);
    }

    public static ClipDetails createClipDetails(ClipData clipData) {
        ClipDetails details;
        PersistableBundle bundle = clipData.getDescription().getExtras();
        if (bundle.containsKey(OP_JUMBO_SELECTION_TAG)) {
            details = new JumboClipDetails(clipData);
        } else {
            details = new StandardClipDetails(clipData);
        }

        return details;
    }

    public static ClipDetails createClipDetails(@OpType int opType, @Nullable Uri srcParent,
            Selection selection, Function<String, Uri> uriBuilder, Context context) {
        ClipStorage storage = DocumentsApplication.getClipStorage(context);

        List<Uri> uris = new ArrayList<>(selection.size());
        for (String id : selection) {
            uris.add(uriBuilder.apply(id));
        }

        return createClipDetails(opType, srcParent, uris, storage);
    }

    @VisibleForTesting
    static ClipDetails createClipDetails(@OpType int opType, @Nullable Uri srcParent,
            List<Uri> uris, ClipStorage storage) {
        ClipDetails details = (uris.size() > Shared.MAX_DOCS_IN_INTENT)
                ? new JumboClipDetails(opType, srcParent, uris, storage)
                : new StandardClipDetails(opType, srcParent, uris);

        return details;
    }

    private static class JumboClipDetails extends ClipDetails {
        private static final String TAG = "JumboClipDetails";

        private final long mSelectionTag;
        private final int mSelectionSize;

        private transient ClipStorage.Reader mReader;

        private JumboClipDetails(ClipData clipData) {
            super(clipData);

            PersistableBundle bundle = clipData.getDescription().getExtras();
            mSelectionTag = bundle.getLong(OP_JUMBO_SELECTION_TAG, ClipStorage.NO_SELECTION_TAG);
            assert(mSelectionTag != ClipStorage.NO_SELECTION_TAG);

            mSelectionSize = bundle.getInt(OP_JUMBO_SELECTION_SIZE);
            assert(mSelectionSize > Shared.MAX_DOCS_IN_INTENT);
        }

        private JumboClipDetails(@OpType int opType, @Nullable Uri srcParent, Collection<Uri> uris,
                ClipStorage storage) {
            super(opType, srcParent);

            mSelectionTag = storage.createTag();
            new ClipStorage.PersistTask(storage, uris, mSelectionTag).execute();
            mSelectionSize = uris.size();
        }

        @Override
        public int getItemCount() {
            return mSelectionSize;
        }

        @Override
        public Iterable<Uri> getDocs(ClipStorage storage) throws IOException {
            if (mReader != null) {
                throw new IllegalStateException(
                        "JumboClipDetails#getDocs() can only be called once.");
            }

            mReader = storage.createReader(mSelectionTag);

            return mReader;
        }

        @Override
        void dispose(ClipStorage storage) {
            if (mReader != null) {
                try {
                    mReader.close();
                } catch (IOException e) {
                    Log.w(TAG, "Failed to close the reader.", e);
                }
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
            builder.append("JumboClipDetails{");
            super.appendTo(builder);
            builder.append(", selectionTag=").append(mSelectionTag);
            builder.append(", selectionSize=").append(mSelectionSize);
            builder.append("}");
            return builder.toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);

            dest.writeLong(mSelectionTag);
            dest.writeInt(mSelectionSize);
        }

        private JumboClipDetails(Parcel in) {
            super(in);

            mSelectionTag = in.readLong();
            mSelectionSize = in.readInt();
        }

        public static final Parcelable.Creator<JumboClipDetails> CREATOR =
                new Parcelable.Creator<JumboClipDetails>() {

                    @Override
                    public JumboClipDetails createFromParcel(Parcel source) {
                        return new JumboClipDetails(source);
                    }

                    @Override
                    public JumboClipDetails[] newArray(int size) {
                        return new JumboClipDetails[size];
                    }
                };
    }

    @VisibleForTesting
    public static class StandardClipDetails extends ClipDetails {
        private final List<Uri> mDocs;

        private StandardClipDetails(ClipData clipData) {
            super(clipData);
            mDocs = listDocs(clipData);
        }

        @VisibleForTesting
        public StandardClipDetails(@OpType int opType, @Nullable Uri srcParent, List<Uri> docs) {
            super(opType, srcParent);

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
        public Iterable<Uri> getDocs(ClipStorage storage) {
            return mDocs;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("StandardClipDetails{");
            super.appendTo(builder);
            builder.append(", ").append("docs=").append(mDocs.toString());
            builder.append("}");
            return builder.toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);

            dest.writeTypedList(mDocs);
        }

        private StandardClipDetails(Parcel in) {
            super(in);

            mDocs = in.createTypedArrayList(Uri.CREATOR);
        }

        public static final Parcelable.Creator<StandardClipDetails> CREATOR =
                new Parcelable.Creator<StandardClipDetails>() {

                    @Override
                    public StandardClipDetails createFromParcel(Parcel source) {
                        return new StandardClipDetails(source);
                    }

                    @Override
                    public StandardClipDetails[] newArray(int size) {
                        return new StandardClipDetails[size];
                    }
                };
    }
}
