/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.PersistableBundle;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.documentsui.ClipStorage.Writer;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;
import com.android.documentsui.services.FileOperations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final String SRC_PARENT_KEY = "srcParent";
    private static final String OP_TYPE_KEY = "opType";
    private static final String OP_JUMBO_SELECTION_SIZE = "jumboSelection-size";

    private final Context mContext;
    private final ClipStorage mClipStorage;
    private final ClipboardManager mClipboard;

    DocumentClipper(Context context, ClipStorage storage) {
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

    public ClipDetails getClipDetails(@Nullable ClipData clipData) {
        if (clipData == null) {
            return null;
        }

        String srcParent = clipData.getDescription().getExtras().getString(SRC_PARENT_KEY);

        ClipDetails clipDetails = new ClipDetails(
                clipData.getDescription().getExtras().getInt(OP_TYPE_KEY),
                getDocumentsFromClipData(clipData),
                createDocument((srcParent != null) ? Uri.parse(srcParent) : null));

        return clipDetails;
    }

    private List<DocumentInfo> getDocumentsFromClipData(ClipData clipData) {
        assert(clipData != null);

        int count = clipData.getItemCount();
        if (count == 0) {
            return Collections.EMPTY_LIST;
        }

        final List<DocumentInfo> srcDocs = new ArrayList<>();

        for (int i = 0; i < count; ++i) {
            ClipData.Item item = clipData.getItemAt(i);
            Uri itemUri = item.getUri();
            DocumentInfo docInfo = createDocument(itemUri);
            if (docInfo != null) {
                srcDocs.add(docInfo);
            } else {
                // This uri either doesn't exist, or is invalid.
                Log.w(TAG, "Can't create document info from uri: " + itemUri);
            }
        }

        return srcDocs;
    }

    /**
     * Returns {@link ClipData} representing the selection, or null if selection is empty,
     * or cannot be converted.
     */
    public @Nullable ClipData getClipDataForDocuments(
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
    private @Nullable ClipData createStandardClipData(
            Function<String, Uri> uriBuilder, Selection selection, @OpType int opType) {

        assert(!selection.isEmpty());

        final ContentResolver resolver = mContext.getContentResolver();
        final ArrayList<ClipData.Item> clipItems = new ArrayList<>();
        final Set<String> clipTypes = new HashSet<>();

        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(OP_TYPE_KEY, opType);

        int clipCount = 0;
        for (String id : selection) {
            assert(id != null);
            Uri uri = uriBuilder.apply(id);
            if (clipCount <= Shared.MAX_DOCS_IN_INTENT) {
                DocumentInfo.addMimeTypes(resolver, uri, clipTypes);
                clipItems.add(new ClipData.Item(uri));
            }
            clipCount++;
        }

        ClipDescription description = new ClipDescription(
                "", // Currently "label" is not displayed anywhere in the UI.
                clipTypes.toArray(new String[0]));
        description.setExtras(bundle);

        return new ClipData(description, clipItems);
    }

    /**
     * Returns ClipData representing the list of docs, or null if docs is empty,
     * or docs cannot be converted.
     */
    private @Nullable ClipData createJumboClipData(
            Function<String, Uri> uriBuilder, Selection selection, @OpType int opType) {

        assert(!selection.isEmpty());

        final ContentResolver resolver = mContext.getContentResolver();
        final ArrayList<ClipData.Item> clipItems = new ArrayList<>();
        final Set<String> clipTypes = new HashSet<>();

        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(OP_TYPE_KEY, opType);
        bundle.putInt(OP_JUMBO_SELECTION_SIZE, selection.size());

        int clipCount = 0;
        synchronized (mClipStorage) {
            try (Writer writer = mClipStorage.createWriter()) {
                for (String id : selection) {
                    assert(id != null);
                    Uri uri = uriBuilder.apply(id);
                    if (clipCount <= Shared.MAX_DOCS_IN_INTENT) {
                        DocumentInfo.addMimeTypes(resolver, uri, clipTypes);
                        clipItems.add(new ClipData.Item(uri));
                    }
                    writer.write(uri);
                    clipCount++;
                }
            } catch (IOException e) {
                Log.e(TAG, "Caught exception trying to write jumbo clip to disk.", e);
                return null;
            }
        }

        ClipDescription description = new ClipDescription(
                "", // Currently "label" is not displayed anywhere in the UI.
                clipTypes.toArray(new String[0]));
        description.setExtras(bundle);

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

    private DocumentInfo createDocument(Uri uri) {
        DocumentInfo doc = null;
        if (isDocumentUri(uri)) {
            ContentResolver resolver = mContext.getContentResolver();
            try {
                doc = DocumentInfo.fromUri(resolver, uri);
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
        }
        return doc;
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

        new AsyncTask<Void, Void, ClipDetails>() {

            @Override
            protected ClipDetails doInBackground(Void... params) {
                return getClipDetails(clipData);
            }

            @Override
            protected void onPostExecute(ClipDetails clipDetails) {
                if (clipDetails == null) {
                    Log.w(TAG,  "Received null clipDetails. Ignoring.");
                    return;
                }

                List<DocumentInfo> docs = clipDetails.docs;
                @OpType int type = clipDetails.opType;
                DocumentInfo srcParent = clipDetails.parent;
                moveDocuments(docs, destination, docStack, type, srcParent, callback);
            }
        }.execute();
    }

    /**
     * Moves {@code docs} from {@code srcParent} to {@code destination}.
     * operationType can be copy or cut
     * srcParent Must be non-null for move operations.
     */
    private void moveDocuments(
            List<DocumentInfo> docs,
            DocumentInfo destination,
            DocumentStack docStack,
            @OpType int operationType,
            DocumentInfo srcParent,
            FileOperations.Callback callback) {

        RootInfo destRoot = docStack.root;
        if (!canCopy(docs, destRoot, destination)) {
            callback.onOperationResult(FileOperations.Callback.STATUS_REJECTED, operationType, 0);
            return;
        }

        if (docs.isEmpty()) {
            callback.onOperationResult(FileOperations.Callback.STATUS_ACCEPTED, operationType, 0);
            return;
        }

        DocumentStack dstStack = new DocumentStack();
        dstStack.push(destination);
        dstStack.addAll(docStack);
        switch (operationType) {
            case FileOperationService.OPERATION_MOVE:
                FileOperations.move(mContext, docs, srcParent, dstStack, callback);
                break;
            case FileOperationService.OPERATION_COPY:
                FileOperations.copy(mContext, docs, dstStack, callback);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported operation: " + operationType);
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
    private static boolean canCopy(List<DocumentInfo> files, RootInfo root, DocumentInfo dest) {
        if (dest == null || !dest.isDirectory() || !dest.isCreateSupported()) {
            return false;
        }

        // Can't copy folders to downloads, because we don't show folders there.
        if (root.isDownloads()) {
            for (DocumentInfo docs : files) {
                if (docs.isDirectory()) {
                    return false;
                }
            }
        }

        return true;
    }

    public static class ClipDetails {
        public final @OpType int opType;
        public final List<DocumentInfo> docs;
        public final @Nullable DocumentInfo parent;

        ClipDetails(@OpType int opType, List<DocumentInfo> docs, @Nullable DocumentInfo parent) {
            this.opType = opType;
            this.docs = docs;
            this.parent = parent;
        }
    }
}
