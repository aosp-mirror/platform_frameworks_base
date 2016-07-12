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

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.PersistableBundle;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.documentsui.Shared;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.FileOperation;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;
import com.android.documentsui.services.FileOperations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * ClipboardManager wrapper class providing higher level logical
 * support for dealing with Documents.
 */
public final class DocumentClipper {

    private static final String TAG = "DocumentClipper";

    static final String SRC_PARENT_KEY = "srcParent";
    static final String OP_TYPE_KEY = "opType";
    static final String OP_JUMBO_SELECTION_SIZE = "jumboSelection-size";
    static final String OP_JUMBO_SELECTION_TAG = "jumboSelection-tag";

    private final Context mContext;
    private final ClipStorage mClipStorage;
    private final ClipboardManager mClipboard;

    public DocumentClipper(Context context, ClipStorage storage) {
        mContext = context;
        mClipStorage = storage;
        mClipboard = context.getSystemService(ClipboardManager.class);
    }

    public boolean hasItemsToPaste() {
        if (mClipboard.hasPrimaryClip()) {
            ClipData clipData = mClipboard.getPrimaryClip();

            int count = clipData.getItemCount();
            if (count > 0) {
                for (int i = 0; i < count; ++i) {
                    ClipData.Item item = clipData.getItemAt(i);
                    Uri uri = item.getUri();
                    if (isDocumentUri(uri)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isDocumentUri(@Nullable Uri uri) {
        return uri != null && DocumentsContract.isDocumentUri(mContext, uri);
    }

    /**
     * Returns {@link ClipData} representing the selection, or null if selection is empty,
     * or cannot be converted.
     */
    public ClipData getClipDataForDocuments(
        Function<String, Uri> uriBuilder, Selection selection, @OpType int opType) {

        assert(selection != null);

        if (selection.isEmpty()) {
            Log.w(TAG, "Attempting to clip empty selection. Ignoring.");
            return null;
        }

        return (selection.size() > Shared.MAX_DOCS_IN_INTENT)
                ? createJumboClipData(uriBuilder, selection, opType)
                : createStandardClipData(uriBuilder, selection, opType);
    }

    /**
     * Returns ClipData representing the selection.
     */
    private ClipData createStandardClipData(
            Function<String, Uri> uriBuilder, Selection selection, @OpType int opType) {

        assert(!selection.isEmpty());
        assert(selection.size() <= Shared.MAX_DOCS_IN_INTENT);

        final ContentResolver resolver = mContext.getContentResolver();
        final ArrayList<ClipData.Item> clipItems = new ArrayList<>();
        final Set<String> clipTypes = new HashSet<>();

        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(OP_TYPE_KEY, opType);

        for (String id : selection) {
            assert(id != null);
            Uri uri = uriBuilder.apply(id);
            DocumentInfo.addMimeTypes(resolver, uri, clipTypes);
            clipItems.add(new ClipData.Item(uri));
        }

        ClipDescription description = new ClipDescription(
                "", // Currently "label" is not displayed anywhere in the UI.
                clipTypes.toArray(new String[0]));
        description.setExtras(bundle);

        return new ClipData(description, clipItems);
    }

    /**
     * Returns ClipData representing the list of docs
     */
    private ClipData createJumboClipData(
            Function<String, Uri> uriBuilder, Selection selection, @OpType int opType) {

        assert(!selection.isEmpty());
        assert(selection.size() > Shared.MAX_DOCS_IN_INTENT);

        final List<Uri> uris = new ArrayList<>(selection.size());

        final int capacity = Math.min(selection.size(), Shared.MAX_DOCS_IN_INTENT);
        final ArrayList<ClipData.Item> clipItems = new ArrayList<>(capacity);

        // Set up mime types for the first Shared.MAX_DOCS_IN_INTENT
        final ContentResolver resolver = mContext.getContentResolver();
        final Set<String> clipTypes = new HashSet<>();
        int docCount = 0;
        for (String id : selection) {
            assert(id != null);
            Uri uri = uriBuilder.apply(id);
            if (docCount++ < Shared.MAX_DOCS_IN_INTENT) {
                DocumentInfo.addMimeTypes(resolver, uri, clipTypes);
                clipItems.add(new ClipData.Item(uri));
            }

            uris.add(uri);
        }

        // Prepare metadata
        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(OP_TYPE_KEY, opType);
        bundle.putInt(OP_JUMBO_SELECTION_SIZE, selection.size());

        // Creates a clip tag
        int tag = mClipStorage.claimStorageSlot();
        bundle.putInt(OP_JUMBO_SELECTION_TAG, tag);

        ClipDescription description = new ClipDescription(
                "", // Currently "label" is not displayed anywhere in the UI.
                clipTypes.toArray(new String[0]));
        description.setExtras(bundle);

        // Persists clip items
        new ClipStorage.PersistTask(mClipStorage, uris, tag).execute();

        return new ClipData(description, clipItems);
    }

    /**
     * Puts {@code ClipData} in a primary clipboard, describing a copy operation
     */
    public void clipDocumentsForCopy(Function<String, Uri> uriBuilder, Selection selection) {
        ClipData data =
                getClipDataForDocuments(uriBuilder, selection, FileOperationService.OPERATION_COPY);
        assert(data != null);

        mClipboard.setPrimaryClip(data);
    }

    /**
     *  Puts {@Code ClipData} in a primary clipboard, describing a cut operation
     */
    public void clipDocumentsForCut(
            Function<String, Uri> uriBuilder, Selection selection, DocumentInfo parent) {
        assert(!selection.isEmpty());
        assert(parent.derivedUri != null);

        ClipData data = getClipDataForDocuments(uriBuilder, selection,
                FileOperationService.OPERATION_MOVE);
        assert(data != null);

        PersistableBundle bundle = data.getDescription().getExtras();
        bundle.putString(SRC_PARENT_KEY, parent.derivedUri.toString());

        mClipboard.setPrimaryClip(data);
    }

    /**
     * Copies documents from clipboard. It's the same as {@link #copyFromClipData} with clipData
     * returned from {@link ClipboardManager#getPrimaryClip()}.
     *
     * @param destination destination document.
     * @param docStack the document stack to the destination folder,
     * @param callback callback to notify when operation finishes.
     */
    public void copyFromClipboard(
            DocumentInfo destination,
            DocumentStack docStack,
            FileOperations.Callback callback) {

        copyFromClipData(destination, docStack, mClipboard.getPrimaryClip(), callback);
    }

    /**
     * Copies documents from given clip data.
     *
     * @param destination destination document
     * @param docStack the document stack to the destination folder
     * @param clipData the clipData to copy from, or null to copy from clipboard
     * @param callback callback to notify when operation finishes
     */
    public void copyFromClipData(
            final DocumentInfo destination,
            DocumentStack docStack,
            final @Nullable ClipData clipData,
            final FileOperations.Callback callback) {

        if (clipData == null) {
            Log.i(TAG, "Received null clipData. Ignoring.");
            return;
        }

        PersistableBundle bundle = clipData.getDescription().getExtras();
        @OpType int opType = getOpType(bundle);
        try {
            UrisSupplier uris = UrisSupplier.create(clipData, mContext);
            if (!canCopy(destination)) {
                callback.onOperationResult(
                        FileOperations.Callback.STATUS_REJECTED, opType, 0);
                return;
            }

            if (uris.getItemCount() == 0) {
                callback.onOperationResult(
                        FileOperations.Callback.STATUS_ACCEPTED, opType, 0);
                return;
            }

            DocumentStack dstStack = new DocumentStack(docStack, destination);

            String srcParentString = bundle.getString(SRC_PARENT_KEY);
            Uri srcParent = srcParentString == null ? null : Uri.parse(srcParentString);

            FileOperation operation = new FileOperation.Builder()
                    .withOpType(opType)
                    .withSrcParent(srcParent)
                    .withDestination(dstStack)
                    .withSrcs(uris)
                    .build();

            FileOperations.start(mContext, operation, callback);
        } catch(IOException e) {
            Log.e(TAG, "Cannot create uris supplier.", e);
            callback.onOperationResult(FileOperations.Callback.STATUS_REJECTED, opType, 0);
            return;
        }
    }

    /**
     * Returns true if the list of files can be copied to destination. Note that this
     * is a policy check only. Currently the method does not attempt to verify
     * available space or any other environmental aspects possibly resulting in
     * failure to copy.
     *
     * @return true if the list of files can be copied to destination.
     */
    private static boolean canCopy(DocumentInfo dest) {
        if (dest == null || !dest.isDirectory() || !dest.isCreateSupported()) {
            return false;
        }

        return true;
    }

    public static @OpType int getOpType(ClipData data) {
        PersistableBundle bundle = data.getDescription().getExtras();
        return getOpType(bundle);
    }

    private static @OpType int getOpType(PersistableBundle bundle) {
        return bundle.getInt(OP_TYPE_KEY);
    }
}
