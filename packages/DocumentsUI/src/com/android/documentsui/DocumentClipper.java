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
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BaseBundle;
import android.os.PersistableBundle;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
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
public final class DocumentClipper implements ClipboardManager.OnPrimaryClipChangedListener {

    private static final String TAG = "DocumentClipper";

    static final String SRC_PARENT_KEY = "srcParent";
    static final String OP_TYPE_KEY = "opType";
    static final String OP_JUMBO_SELECTION_SIZE = "jumboSelection-size";
    static final String OP_JUMBO_SELECTION_TAG = "jumboSelection-tag";

    // Use shared preference to store last seen primary clip tag, so that we can delete the file
    // when we realize primary clip has been changed when we're not running.
    private static final String PREF_NAME = "DocumentClipperPref";
    private static final String LAST_PRIMARY_CLIP_TAG = "lastPrimaryClipTag";

    private final Context mContext;
    private final ClipStorage mClipStorage;
    private final ClipboardManager mClipboard;

    // Here we're tracking the last clipped tag ids so we can delete them later.
    private long mLastDragClipTag = ClipStorage.NO_SELECTION_TAG;
    private long mLastUnusedPrimaryClipTag = ClipStorage.NO_SELECTION_TAG;

    private final SharedPreferences mPref;

    DocumentClipper(Context context, ClipStorage storage) {
        mContext = context;
        mClipStorage = storage;
        mClipboard = context.getSystemService(ClipboardManager.class);

        mClipboard.addPrimaryClipChangedListener(this);

        // Primary clips may be changed when we're not running, now it's time to clean up the
        // remnant.
        mPref = context.getSharedPreferences(PREF_NAME, 0);
        mLastUnusedPrimaryClipTag =
                mPref.getLong(LAST_PRIMARY_CLIP_TAG, ClipStorage.NO_SELECTION_TAG);
        deleteLastUnusedPrimaryClip();
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
     *
     * This is specialized for drag and drop so that we know which file to delete if nobody accepts
     * the drop.
     */
    public @Nullable ClipData getClipDataForDrag(
            Function<String, Uri> uriBuilder, Selection selection, @OpType int opType) {
        ClipData data = getClipDataForDocuments(uriBuilder, selection, opType);

        mLastDragClipTag = getTag(data);

        return data;
    }

    /**
     * Returns {@link ClipData} representing the selection, or null if selection is empty,
     * or cannot be converted.
     */
    private @Nullable ClipData getClipDataForDocuments(
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
    private @Nullable ClipData createJumboClipData(
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
        long tag = mClipStorage.createTag();
        bundle.putLong(OP_JUMBO_SELECTION_TAG, tag);

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

        setPrimaryClip(data);
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

        setPrimaryClip(data);
    }

    private void setPrimaryClip(ClipData data) {
        deleteLastPrimaryClip();

        long tag = getTag(data);
        setLastUnusedPrimaryClipTag(tag);

        mClipboard.setPrimaryClip(data);
    }

    /**
     * Sets this primary tag to both class variable and shared preference.
     */
    private void setLastUnusedPrimaryClipTag(long tag) {
        mLastUnusedPrimaryClipTag = tag;
        mPref.edit().putLong(LAST_PRIMARY_CLIP_TAG, tag).commit();
    }

    /**
     * This is a good chance for us to remove previous clip file for cut/copy because we know a new
     * primary clip is set.
     */
    @Override
    public void onPrimaryClipChanged() {
        deleteLastUnusedPrimaryClip();
    }

    private void deleteLastUnusedPrimaryClip() {
        ClipData primary = mClipboard.getPrimaryClip();
        long primaryTag = getTag(primary);

        // onPrimaryClipChanged is also called after we call setPrimaryClip(), so make sure we don't
        // delete the clip file we just created.
        if (mLastUnusedPrimaryClipTag != primaryTag) {
            deleteLastPrimaryClip();
        }
    }

    private void deleteLastPrimaryClip() {
        deleteClip(mLastUnusedPrimaryClipTag);
        setLastUnusedPrimaryClipTag(ClipStorage.NO_SELECTION_TAG);
    }

    /**
     * Deletes the last seen drag clip file.
     */
    public void deleteDragClip() {
        deleteClip(mLastDragClipTag);
        mLastDragClipTag = ClipStorage.NO_SELECTION_TAG;
    }

    private void deleteClip(long tag) {
        try {
            mClipStorage.delete(tag);
        } catch (IOException e) {
            Log.w(TAG, "Error deleting clip file with tag: " + tag, e);
        }
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

        // The primary clip has been claimed by a file operation. It's now the operation's duty
        // to make sure the clip file is deleted after use.
        setLastUnusedPrimaryClipTag(ClipStorage.NO_SELECTION_TAG);

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

        ClipDetails details = ClipDetails.createClipDetails(clipData);

        if (!canCopy(destination)) {
            callback.onOperationResult(
                    FileOperations.Callback.STATUS_REJECTED, details.getOpType(), 0);
            return;
        }

        if (details.getItemCount() == 0) {
            callback.onOperationResult(
                    FileOperations.Callback.STATUS_ACCEPTED, details.getOpType(), 0);
            return;
        }

        DocumentStack dstStack = new DocumentStack();
        dstStack.push(destination);
        dstStack.addAll(docStack);

        // Pass root here so that we can perform "download" root check when
        dstStack.root = docStack.root;

        FileOperations.start(mContext, details, dstStack, callback);
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

    /**
     * Obtains tag from {@link ClipData}. Returns {@link ClipStorage#NO_SELECTION_TAG}
     * if it's not a jumbo clip.
     */
    private static long getTag(@Nullable ClipData data) {
        if (data == null) {
            return ClipStorage.NO_SELECTION_TAG;
        }

        ClipDescription description = data.getDescription();
        BaseBundle bundle = description.getExtras();
        return bundle.getLong(OP_JUMBO_SELECTION_TAG, ClipStorage.NO_SELECTION_TAG);
    }

}
