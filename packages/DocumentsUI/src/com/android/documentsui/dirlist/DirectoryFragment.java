/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.Shared.MAX_DOCS_IN_INTENT;
import static com.android.documentsui.State.MODE_GRID;
import static com.android.documentsui.State.MODE_LIST;
import static com.android.documentsui.State.SORT_ORDER_UNKNOWN;
import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.annotation.IntDef;
import android.annotation.StringRes;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v13.view.DragStartHelper;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.GridLayoutManager.SpanSizeLookup;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.OnItemTouchListener;
import android.support.v7.widget.RecyclerView.RecyclerListener;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.text.BidiFormatter;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toolbar;

import com.android.documentsui.BaseActivity;
import com.android.documentsui.DirectoryLoader;
import com.android.documentsui.DirectoryResult;
import com.android.documentsui.DocumentClipper;
import com.android.documentsui.DocumentsActivity;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.Events;
import com.android.documentsui.Events.MotionInputEvent;
import com.android.documentsui.Menus;
import com.android.documentsui.MessageBar;
import com.android.documentsui.Metrics;
import com.android.documentsui.MimePredicate;
import com.android.documentsui.R;
import com.android.documentsui.RecentsLoader;
import com.android.documentsui.RootsCache;
import com.android.documentsui.Shared;
import com.android.documentsui.Snackbars;
import com.android.documentsui.State;
import com.android.documentsui.State.ViewMode;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;
import com.android.documentsui.services.FileOperations;

import com.google.common.collect.Lists;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends Fragment
        implements DocumentsAdapter.Environment, LoaderCallbacks<DirectoryResult> {

    @IntDef(flag = true, value = {
            TYPE_NORMAL,
            TYPE_RECENT_OPEN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultType {}
    public static final int TYPE_NORMAL = 1;
    public static final int TYPE_RECENT_OPEN = 2;

    @IntDef(flag = true, value = {
            REQUEST_COPY_DESTINATION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestCode {}
    public static final int REQUEST_COPY_DESTINATION = 1;

    private static final String TAG = "DirectoryFragment";
    private static final int LOADER_ID = 42;

    private Model mModel;
    private MultiSelectManager mSelectionManager;
    private Model.UpdateListener mModelUpdateListener = new ModelUpdateListener();
    private ItemEventListener mItemEventListener = new ItemEventListener();
    private FocusManager mFocusManager;

    private IconHelper mIconHelper;

    private View mEmptyView;
    private RecyclerView mRecView;
    private ListeningGestureDetector mGestureDetector;

    private String mStateKey;

    private int mLastSortOrder = SORT_ORDER_UNKNOWN;
    private DocumentsAdapter mAdapter;
    private FragmentTuner mTuner;
    private DocumentClipper mClipper;
    private GridLayoutManager mLayout;
    private int mColumnCount = 1;  // This will get updated when layout changes.

    private LayoutInflater mInflater;
    private MessageBar mMessageBar;
    private View mProgressBar;

    // Directory fragment state is defined by: root, document, query, type, selection
    private @ResultType int mType = TYPE_NORMAL;
    private RootInfo mRoot;
    private DocumentInfo mDocument;
    private String mQuery = null;
    // Save selection found during creation so it can be restored during directory loading.
    private Selection mSelection = null;
    private boolean mSearchMode = false;
    private @Nullable ActionMode mActionMode;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mInflater = inflater;
        final View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mMessageBar = MessageBar.create(getChildFragmentManager());
        mProgressBar = view.findViewById(R.id.progressbar);
        mEmptyView = view.findViewById(android.R.id.empty);
        mRecView = (RecyclerView) view.findViewById(R.id.dir_list);
        mRecView.setRecyclerListener(
                new RecyclerListener() {
                    @Override
                    public void onViewRecycled(ViewHolder holder) {
                        cancelThumbnailTask(holder.itemView);
                    }
                });

        mRecView.setItemAnimator(new DirectoryItemAnimator(getActivity()));

        // Make the recycler and the empty views responsive to drop events.
        mRecView.setOnDragListener(mOnDragListener);
        mEmptyView.setOnDragListener(mOnDragListener);

        return view;
    }

    @Override
    public void onDestroyView() {
        mSelectionManager.clearSelection();

        // Cancel any outstanding thumbnail requests
        final int count = mRecView.getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = mRecView.getChildAt(i);
            cancelThumbnailTask(view);
        }

        super.onDestroyView();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Context context = getActivity();
        final State state = getDisplayState();

        // Read arguments when object created for the first time.
        // Restore state if fragment recreated.
        Bundle args = savedInstanceState == null ? getArguments() : savedInstanceState;
        mRoot = args.getParcelable(Shared.EXTRA_ROOT);
        mDocument = args.getParcelable(Shared.EXTRA_DOC);
        mStateKey = buildStateKey(mRoot, mDocument);
        mQuery = args.getString(Shared.EXTRA_QUERY);
        mType = args.getInt(Shared.EXTRA_TYPE);
        final Selection selection = args.getParcelable(Shared.EXTRA_SELECTION);
        mSelection = selection != null ? selection : new Selection();
        mSearchMode = args.getBoolean(Shared.EXTRA_SEARCH_MODE);

        mIconHelper = new IconHelper(context, MODE_GRID);

        mAdapter = new SectionBreakDocumentsAdapterWrapper(
                this, new ModelBackedDocumentsAdapter(this, mIconHelper));

        mRecView.setAdapter(mAdapter);

        mLayout = new GridLayoutManager(getContext(), mColumnCount);
        SpanSizeLookup lookup = mAdapter.createSpanSizeLookup();
        if (lookup != null) {
            mLayout.setSpanSizeLookup(lookup);
        }
        mRecView.setLayoutManager(mLayout);

        mGestureDetector =
                new ListeningGestureDetector(this.getContext(), mDragHelper, new GestureListener());

        mRecView.addOnItemTouchListener(mGestureDetector);

        // TODO: instead of inserting the view into the constructor, extract listener-creation code
        // and set the listener on the view after the fact.  Then the view doesn't need to be passed
        // into the selection manager.
        mSelectionManager = new MultiSelectManager(
                mRecView,
                mAdapter,
                state.allowMultiple
                    ? MultiSelectManager.MODE_MULTIPLE
                    : MultiSelectManager.MODE_SINGLE,
                null);

        mSelectionManager.addCallback(new SelectionModeListener());

        mModel = new Model();
        mModel.addUpdateListener(mAdapter);
        mModel.addUpdateListener(mModelUpdateListener);

        // Make sure this is done after the RecyclerView is set up.
        mFocusManager = new FocusManager(context, mRecView, mModel);

        mTuner = FragmentTuner.pick(getContext(), state);
        mClipper = new DocumentClipper(context);

        final ActivityManager am = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        boolean svelte = am.isLowRamDevice() && (mType == TYPE_RECENT_OPEN);
        mIconHelper.setThumbnailsEnabled(!svelte);

        // Kick off loader at least once
        getLoaderManager().restartLoader(LOADER_ID, null, this);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        mSelectionManager.getSelection(mSelection);

        outState.putInt(Shared.EXTRA_TYPE, mType);
        outState.putParcelable(Shared.EXTRA_ROOT, mRoot);
        outState.putParcelable(Shared.EXTRA_DOC, mDocument);
        outState.putString(Shared.EXTRA_QUERY, mQuery);

        // Workaround. To avoid crash, write only up to 512 KB of selection.
        // If more files are selected, then the selection will be lost.
        final Parcel parcel = Parcel.obtain();
        try {
            mSelection.writeToParcel(parcel, 0);
            if (parcel.dataSize() <= 512 * 1024) {
                outState.putParcelable(Shared.EXTRA_SELECTION, mSelection);
            }
        } finally {
            parcel.recycle();
        }

        outState.putBoolean(Shared.EXTRA_SEARCH_MODE, mSearchMode);
    }

    @Override
    public void onActivityResult(@RequestCode int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_COPY_DESTINATION:
                handleCopyResult(resultCode, data);
                break;
            default:
                throw new UnsupportedOperationException("Unknown request code: " + requestCode);
        }
    }

    private void handleCopyResult(int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_CANCELED || data == null) {
            // User pressed the back button or otherwise cancelled the destination pick. Don't
            // proceed with the copy.
            return;
        }

        @OpType int operationType = data.getIntExtra(
                FileOperationService.EXTRA_OPERATION,
                FileOperationService.OPERATION_COPY);

        FileOperations.start(
                getActivity(),
                getDisplayState().selectedDocumentsForCopy,
                getDisplayState().stack.peek(),
                (DocumentStack) data.getParcelableExtra(Shared.EXTRA_STACK),
                operationType);
    }

    protected boolean onDoubleTap(MotionEvent e) {
        if (Events.isMouseEvent(e)) {
            String id = getModelId(e);
            if (id != null) {
                return handleViewItem(id);
            }
        }
        return false;
    }

    private boolean handleViewItem(String id) {
        final Cursor cursor = mModel.getItem(id);

        if (cursor == null) {
            Log.w(TAG, "Can't view item. Can't obtain cursor for modeId" + id);
            return false;
        }

        final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
        if (mTuner.isDocumentEnabled(docMimeType, docFlags)) {
            final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
            ((BaseActivity) getActivity()).onDocumentPicked(doc, mModel);
            mSelectionManager.clearSelection();
            return true;
        }
        return false;
    }

    @Override
    public void onStop() {
        super.onStop();

        // Remember last scroll location
        final SparseArray<Parcelable> container = new SparseArray<Parcelable>();
        getView().saveHierarchyState(container);
        final State state = getDisplayState();
        state.dirState.put(mStateKey, container);
    }

    public void onDisplayStateChanged() {
        updateDisplayState();
    }

    public void onSortOrderChanged() {
        // Sort order is implemented as a sorting wrapper around directory
        // results. So when sort order changes, we force a reload of the directory.
        getLoaderManager().restartLoader(LOADER_ID, null, this);
    }

    public void onViewModeChanged() {
        // Mode change is just visual change; no need to kick loader.
        updateDisplayState();
    }

    private void updateDisplayState() {
        State state = getDisplayState();
        updateLayout(state.derivedMode);
        mRecView.setAdapter(mAdapter);
    }

    /**
     * Updates the layout after the view mode switches.
     * @param mode The new view mode.
     */
    private void updateLayout(@ViewMode int mode) {
        mColumnCount = calculateColumnCount(mode);
        if (mLayout != null) {
            mLayout.setSpanCount(mColumnCount);
        }

        int pad = getDirectoryPadding(mode);
        mRecView.setPadding(pad, pad, pad, pad);
        mRecView.requestLayout();
        mSelectionManager.handleLayoutChanged();  // RecyclerView doesn't do this for us
        mIconHelper.setViewMode(mode);
    }

    private int calculateColumnCount(@ViewMode int mode) {
        if (mode == MODE_LIST) {
            // List mode is a "grid" with 1 column.
            return 1;
        }

        int cellWidth = getResources().getDimensionPixelSize(R.dimen.grid_width);
        int cellMargin = 2 * getResources().getDimensionPixelSize(R.dimen.grid_item_margin);
        int viewPadding = mRecView.getPaddingLeft() + mRecView.getPaddingRight();

        // RecyclerView sometimes gets a width of 0 (see b/27150284).  Clamp so that we always lay
        // out the grid with at least 2 columns.
        int columnCount = Math.max(2,
                (mRecView.getWidth() - viewPadding) / (cellWidth + cellMargin));

        return columnCount;
    }

    private int getDirectoryPadding(@ViewMode int mode) {
        switch (mode) {
            case MODE_GRID:
                return getResources().getDimensionPixelSize(R.dimen.grid_container_padding);
            case MODE_LIST:
                return getResources().getDimensionPixelSize(R.dimen.list_container_padding);
            default:
                throw new IllegalArgumentException("Unsupported layout mode: " + mode);
        }
    }

    @Override
    public int getColumnCount() {
        return mColumnCount;
    }

    /**
     * Manages the integration between our ActionMode and MultiSelectManager, initiating
     * ActionMode when there is a selection, canceling it when there is no selection,
     * and clearing selection when action mode is explicitly exited by the user.
     */
    private final class SelectionModeListener implements MultiSelectManager.Callback,
            ActionMode.Callback, FragmentTuner.SelectionDetails {

        private Selection mSelected = new Selection();

        // Partial files are files that haven't been fully downloaded.
        private int mPartialCount = 0;
        private int mDirectoryCount = 0;
        private int mNoDeleteCount = 0;
        private int mNoRenameCount = 0;

        private Menu mMenu;

        @Override
        public boolean onBeforeItemStateChange(String modelId, boolean selected) {
            if (selected) {
                final Cursor cursor = mModel.getItem(modelId);
                if (cursor == null) {
                    Log.w(TAG, "Can't obtain cursor for modelId: " + modelId);
                    return false;
                }

                final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
                final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
                if (!mTuner.canSelectType(docMimeType, docFlags)) {
                    return false;
                }

                if (mSelected.size() >= MAX_DOCS_IN_INTENT) {
                    Snackbars.makeSnackbar(
                            getActivity(),
                            R.string.too_many_selected,
                            Snackbar.LENGTH_SHORT)
                            .show();
                    return false;
                }
            }
            return true;
        }

        @Override
        public void onItemStateChanged(String modelId, boolean selected) {
            final Cursor cursor = mModel.getItem(modelId);
            if (cursor == null) {
                Log.w(TAG, "Model returned null cursor for document: " + modelId
                        + ". Ignoring state changed event.");
                return;
            }

            // TODO: Should this be happening in onSelectionChanged? Technically this callback is
            // triggered on "silent" selection updates (i.e. we might be reacting to unfinalized
            // selection changes here)
            final String mimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            if (MimePredicate.isDirectoryType(mimeType)) {
                mDirectoryCount += selected ? 1 : -1;
            }

            final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            if ((docFlags & Document.FLAG_PARTIAL) != 0) {
                mPartialCount += selected ? 1 : -1;
            }
            if ((docFlags & Document.FLAG_SUPPORTS_DELETE) == 0) {
                mNoDeleteCount += selected ? 1 : -1;
            }
            if ((docFlags & Document.FLAG_SUPPORTS_RENAME) == 0) {
                mNoRenameCount += selected ? 1 : -1;
            }
        }

        @Override
        public void onSelectionChanged() {
            mSelectionManager.getSelection(mSelected);
            if (mSelected.size() > 0) {
                if (DEBUG) Log.d(TAG, "Maybe starting action mode.");
                if (mActionMode == null) {
                    if (DEBUG) Log.d(TAG, "Yeah. Starting action mode.");
                    mActionMode = getActivity().startActionMode(this);
                }
                updateActionMenu();
            } else {
                if (DEBUG) Log.d(TAG, "Finishing action mode.");
                if (mActionMode != null) {
                    mActionMode.finish();
                }
            }

            if (mActionMode != null) {
                assert(!mSelected.isEmpty());
                final String title = Shared.getQuantityString(getActivity(),
                        R.plurals.elements_selected, mSelected.size());
                mActionMode.setTitle(title);
                mRecView.announceForAccessibility(title);
            }
        }

        // Called when the user exits the action mode
        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (DEBUG) Log.d(TAG, "Handling action mode destroyed.");
            mActionMode = null;
            // clear selection
            mSelectionManager.clearSelection();
            mSelected.clear();

            mDirectoryCount = 0;
            mPartialCount = 0;
            mNoDeleteCount = 0;
            mNoRenameCount = 0;

            // Re-enable TalkBack for the toolbars, as they are no longer covered by action mode.
            final Toolbar toolbar = (Toolbar) getActivity().findViewById(R.id.toolbar);
            toolbar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);

            // This toolbar is not present in the fixed_layout
            final Toolbar rootsToolbar = (Toolbar) getActivity().findViewById(R.id.roots_toolbar);
            if (rootsToolbar != null) {
                rootsToolbar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mRecView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

            int size = mSelectionManager.getSelection().size();
            mode.getMenuInflater().inflate(R.menu.mode_directory, menu);
            mode.setTitle(TextUtils.formatSelectedCount(size));

            if (size > 0) {
                // Hide the toolbars if action mode is enabled, so TalkBack doesn't navigate to
                // these controls when using linear navigation.
                final Toolbar toolbar = (Toolbar) getActivity().findViewById(R.id.toolbar);
                toolbar.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

                // This toolbar is not present in the fixed_layout
                final Toolbar rootsToolbar = (Toolbar) getActivity().findViewById(
                        R.id.roots_toolbar);
                if (rootsToolbar != null) {
                    rootsToolbar.setImportantForAccessibility(
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                }
                return true;
            }

            return false;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            mMenu = menu;
            updateActionMenu();
            return true;
        }

        @Override
        public boolean containsDirectories() {
            return mDirectoryCount > 0;
        }

        @Override
        public boolean containsPartialFiles() {
            return mPartialCount > 0;
        }

        @Override
        public boolean canDelete() {
            return mNoDeleteCount == 0;
        }

        @Override
        public boolean canRename() {
            return mNoRenameCount == 0 && mSelectionManager.getSelection().size() == 1;
        }

        private void updateActionMenu() {
            assert(mMenu != null);
            mTuner.updateActionMenu(mMenu, this);
            Menus.disableHiddenItems(mMenu);
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            Selection selection = mSelectionManager.getSelection(new Selection());

            switch (item.getItemId()) {
                case R.id.menu_open:
                    openDocuments(selection);
                    mode.finish();
                    return true;

                case R.id.menu_share:
                    shareDocuments(selection);
                    // TODO: Only finish selection if share action is completed.
                    mode.finish();
                    return true;

                case R.id.menu_delete:
                    // deleteDocuments will end action mode if the documents are deleted.
                    // It won't end action mode if user cancels the delete.
                    deleteDocuments(selection);
                    return true;

                case R.id.menu_copy_to:
                    // TODO: Only finish selection mode if copy-to is not canceled.
                    // Need to plum down into handling the way we do with deleteDocuments.
                    mode.finish();
                    transferDocuments(selection, FileOperationService.OPERATION_COPY);
                    return true;

                case R.id.menu_move_to:
                    // Exit selection mode first, so we avoid deselecting deleted documents.
                    mode.finish();
                    transferDocuments(selection, FileOperationService.OPERATION_MOVE);
                    return true;

                case R.id.menu_copy_to_clipboard:
                    copySelectedToClipboard();
                    return true;

                case R.id.menu_select_all:
                    selectAllFiles();
                    return true;

                case R.id.menu_rename:
                    // Exit selection mode first, so we avoid deselecting deleted
                    // (renamed) documents.
                    mode.finish();
                    renameDocuments(selection);
                    return true;

                default:
                    if (DEBUG) Log.d(TAG, "Unhandled menu item selected: " + item);
                    return false;
            }
        }
    }

    public final boolean onBackPressed() {
        if (mSelectionManager.hasSelection()) {
            if (DEBUG) Log.d(TAG, "Clearing selection on selection manager.");
            mSelectionManager.clearSelection();
            return true;
        }
        return false;
    }

    private void cancelThumbnailTask(View view) {
        final ImageView iconThumb = (ImageView) view.findViewById(R.id.icon_thumb);
        if (iconThumb != null) {
            mIconHelper.stopLoading(iconThumb);
        }
    }

    private void openDocuments(final Selection selected) {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_OPEN);

        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                // TODO: Implement support in Files activity for opening multiple docs.
                BaseActivity.get(DirectoryFragment.this).onDocumentsPicked(docs);
            }
        }.execute(selected);
    }

    private void shareDocuments(final Selection selected) {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_SHARE);

        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                Intent intent;

                // Filter out directories and virtual files - those can't be shared.
                List<DocumentInfo> docsForSend = new ArrayList<>();
                for (DocumentInfo doc: docs) {
                    if (!doc.isDirectory() && !doc.isVirtualDocument()) {
                        docsForSend.add(doc);
                    }
                }

                if (docsForSend.size() == 1) {
                    final DocumentInfo doc = docsForSend.get(0);

                    intent = new Intent(Intent.ACTION_SEND);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.addCategory(Intent.CATEGORY_DEFAULT);
                    intent.setType(doc.mimeType);
                    intent.putExtra(Intent.EXTRA_STREAM, doc.derivedUri);

                } else if (docsForSend.size() > 1) {
                    intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.addCategory(Intent.CATEGORY_DEFAULT);

                    final ArrayList<String> mimeTypes = new ArrayList<>();
                    final ArrayList<Uri> uris = new ArrayList<>();
                    for (DocumentInfo doc : docsForSend) {
                        mimeTypes.add(doc.mimeType);
                        uris.add(doc.derivedUri);
                    }

                    intent.setType(findCommonMimeType(mimeTypes));
                    intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);

                } else {
                    return;
                }

                intent = Intent.createChooser(intent, getActivity().getText(R.string.share_via));
                startActivity(intent);
            }
        }.execute(selected);
    }

    private String generateDeleteMessage(final List<DocumentInfo> docs) {
        String message;
        int dirsCount = 0;

        for (DocumentInfo doc : docs) {
            if (doc.isDirectory()) {
                ++dirsCount;
            }
        }

        if (docs.size() == 1) {
            // Deleteing 1 file xor 1 folder in cwd

            // Address b/28772371, where including user strings in message can result in
            // broken bidirectional support.
            String displayName = BidiFormatter.getInstance().unicodeWrap(docs.get(0).displayName);
            message = dirsCount == 0
                    ? getActivity().getString(R.string.delete_filename_confirmation_message,
                            displayName)
                    : getActivity().getString(R.string.delete_foldername_confirmation_message,
                            displayName);
        } else if (dirsCount == 0) {
            // Deleting only files in cwd
            message = Shared.getQuantityString(getActivity(),
                    R.plurals.delete_files_confirmation_message, docs.size());
        } else if (dirsCount == docs.size()) {
            // Deleting only folders in cwd
            message = Shared.getQuantityString(getActivity(),
                    R.plurals.delete_folders_confirmation_message, docs.size());
        } else {
            // Deleting mixed items (files and folders) in cwd
            message = Shared.getQuantityString(getActivity(),
                    R.plurals.delete_items_confirmation_message, docs.size());
        }
        return message;
    }

    private void deleteDocuments(final Selection selected) {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_DELETE);

        assert(!selected.isEmpty());

        final DocumentInfo srcParent = getDisplayState().stack.peek();
        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(final List<DocumentInfo> docs) {

                TextView message =
                        (TextView) mInflater.inflate(R.layout.dialog_delete_confirmation, null);
                message.setText(generateDeleteMessage(docs));

                // This "insta-hides" files that are being deleted, because
                // the delete operation may be not execute immediately (it
                // may be queued up on the FileOperationService.)
                // To hide the files locally, we call the hide method on the adapter
                // ...which a live object...cannot be parceled.
                // For that reason, for now, we implement this dialog NOT
                // as a fragment (which can survive rotation and have its own state),
                // but as a simple runtime dialog. So rotating a device with an
                // active delete dialog...results in that dialog disappearing.
                // We can do better, but don't have cycles for it now.
                new AlertDialog.Builder(getActivity())
                    .setView(message)
                    .setPositiveButton(
                         android.R.string.yes,
                         new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                // Finish selection mode first which clears selection so we
                                // don't end up trying to deselect deleted documents.
                                // This is done here, rather in the onActionItemClicked
                                // so we can avoid de-selecting items in the case where
                                // the user cancels the delete.
                                if (mActionMode != null) {
                                    mActionMode.finish();
                                } else {
                                    Log.w(TAG, "Action mode is null before deleting documents.");
                                }
                                // Hide the files in the UI...since the operation
                                // might be queued up on FileOperationService.
                                // We're walking a line here.
                                mAdapter.hide(selected.getAll());
                                FileOperations.delete(
                                        getActivity(), docs, srcParent, getDisplayState().stack);
                            }
                        })
                    .setNegativeButton(android.R.string.no, null)
                    .show();
            }
        }.execute(selected);
    }

    private void transferDocuments(final Selection selected, final @OpType int mode) {
        if(mode == FileOperationService.OPERATION_COPY) {
            Metrics.logUserAction(getContext(), Metrics.USER_ACTION_COPY_TO);
        } else if (mode == FileOperationService.OPERATION_MOVE) {
            Metrics.logUserAction(getContext(), Metrics.USER_ACTION_MOVE_TO);
        }

        // Pop up a dialog to pick a destination.  This is inadequate but works for now.
        // TODO: Implement a picker that is to spec.
        final Intent intent = new Intent(
                Shared.ACTION_PICK_COPY_DESTINATION,
                Uri.EMPTY,
                getActivity(),
                DocumentsActivity.class);


        // Relay any config overrides bits present in the original intent.
        Intent original = getActivity().getIntent();
        if (original != null && original.hasExtra(Shared.EXTRA_PRODUCTIVITY_MODE)) {
            intent.putExtra(
                    Shared.EXTRA_PRODUCTIVITY_MODE,
                    original.getBooleanExtra(Shared.EXTRA_PRODUCTIVITY_MODE, false));
        }

        // Set an appropriate title on the drawer when it is shown in the picker.
        // Coupled with the fact that we auto-open the drawer for copy/move operations
        // it should basically be the thing people see first.
        int drawerTitleId = mode == FileOperationService.OPERATION_MOVE
                ? R.string.menu_move : R.string.menu_copy;
        intent.putExtra(DocumentsContract.EXTRA_PROMPT, getResources().getString(drawerTitleId));

        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                // TODO: Can this move to Fragment bundle state?
                getDisplayState().selectedDocumentsForCopy = docs;

                // Determine if there is a directory in the set of documents
                // to be copied? Why? Directory creation isn't supported by some roots
                // (like Downloads). This informs DocumentsActivity (the "picker")
                // to restrict available roots to just those with support.
                intent.putExtra(Shared.EXTRA_DIRECTORY_COPY, hasDirectory(docs));
                intent.putExtra(FileOperationService.EXTRA_OPERATION, mode);

                // This just identifies the type of request...we'll check it
                // when we reveive a response.
                startActivityForResult(intent, REQUEST_COPY_DESTINATION);
            }

        }.execute(selected);
    }

    private static boolean hasDirectory(List<DocumentInfo> docs) {
        for (DocumentInfo info : docs) {
            if (Document.MIME_TYPE_DIR.equals(info.mimeType)) {
                return true;
            }
        }
        return false;
    }

    private void renameDocuments(Selection selected) {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_RENAME);

        // Batch renaming not supported
        // Rename option is only available in menu when 1 document selected
        assert(selected.size() == 1);

        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                RenameDocumentFragment.show(getFragmentManager(), docs.get(0));
            }
        }.execute(selected);
    }

    @Override
    public void initDocumentHolder(DocumentHolder holder) {
        holder.addEventListener(mItemEventListener);
        holder.itemView.setOnFocusChangeListener(mFocusManager);
    }

    @Override
    public void onBindDocumentHolder(DocumentHolder holder, Cursor cursor) {
        setupDragAndDropOnDocumentView(holder.itemView, cursor);
    }

    @Override
    public State getDisplayState() {
        return ((BaseActivity) getActivity()).getDisplayState();
    }

    @Override
    public Model getModel() {
        return mModel;
    }

    @Override
    public boolean isDocumentEnabled(String docMimeType, int docFlags) {
        return mTuner.isDocumentEnabled(docMimeType, docFlags);
    }

    private void showEmptyDirectory() {
        showEmptyView(R.string.empty, R.drawable.cabinet);
    }

    private void showNoResults(RootInfo root) {
        CharSequence msg = getContext().getResources().getText(R.string.no_results);
        showEmptyView(String.format(String.valueOf(msg), root.title), R.drawable.cabinet);
    }

    private void showQueryError() {
        showEmptyView(R.string.query_error, R.drawable.hourglass);
    }

    private void showEmptyView(@StringRes int id, int drawable) {
        showEmptyView(getContext().getResources().getText(id), drawable);
    }

    private void showEmptyView(CharSequence msg, int drawable) {
        View content = mEmptyView.findViewById(R.id.content);
        TextView msgView = (TextView) mEmptyView.findViewById(R.id.message);
        ImageView imageView = (ImageView) mEmptyView.findViewById(R.id.artwork);
        msgView.setText(msg);
        imageView.setImageResource(drawable);

        mEmptyView.setVisibility(View.VISIBLE);
        mEmptyView.requestFocus();
        mRecView.setVisibility(View.GONE);
    }

    private void showDirectory() {
        mEmptyView.setVisibility(View.GONE);
        mRecView.setVisibility(View.VISIBLE);
        mRecView.requestFocus();
    }

    private String findCommonMimeType(List<String> mimeTypes) {
        String[] commonType = mimeTypes.get(0).split("/");
        if (commonType.length != 2) {
            return "*/*";
        }

        for (int i = 1; i < mimeTypes.size(); i++) {
            String[] type = mimeTypes.get(i).split("/");
            if (type.length != 2) continue;

            if (!commonType[1].equals(type[1])) {
                commonType[1] = "*";
            }

            if (!commonType[0].equals(type[0])) {
                commonType[0] = "*";
                commonType[1] = "*";
                break;
            }
        }

        return commonType[0] + "/" + commonType[1];
    }

    private void copyFromClipboard() {
        new AsyncTask<Void, Void, List<DocumentInfo>>() {

            @Override
            protected List<DocumentInfo> doInBackground(Void... params) {
                return mClipper.getClippedDocuments();
            }

            @Override
            protected void onPostExecute(List<DocumentInfo> docs) {
                DocumentInfo destination =
                        ((BaseActivity) getActivity()).getCurrentDirectory();
                copyDocuments(docs, destination);
            }
        }.execute();
    }

    private void copyFromClipData(final ClipData clipData, final DocumentInfo destination) {
        assert(clipData != null);

        new AsyncTask<Void, Void, List<DocumentInfo>>() {

            @Override
            protected List<DocumentInfo> doInBackground(Void... params) {
                return mClipper.getDocumentsFromClipData(clipData);
            }

            @Override
            protected void onPostExecute(List<DocumentInfo> docs) {
                copyDocuments(docs, destination);
            }
        }.execute();
    }

    private void copyDocuments(final List<DocumentInfo> docs, final DocumentInfo destination) {
        BaseActivity activity = (BaseActivity) getActivity();
        if (!canCopy(docs, activity.getCurrentRoot(), destination)) {
            Snackbars.makeSnackbar(
                    getActivity(),
                    R.string.clipboard_files_cannot_paste,
                    Snackbar.LENGTH_SHORT)
                    .show();
            return;
        }

        if (docs.isEmpty()) {
            return;
        }

        final DocumentStack curStack = getDisplayState().stack;
        DocumentStack tmpStack = new DocumentStack();
        if (destination != null) {
            tmpStack.push(destination);
            tmpStack.addAll(curStack);
        } else {
            tmpStack = curStack;
        }

        FileOperations.copy(getActivity(), docs, tmpStack);
    }

    public void copySelectedToClipboard() {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_COPY_CLIPBOARD);

        Selection selection = mSelectionManager.getSelection(new Selection());
        if (!selection.isEmpty()) {
            copySelectionToClipboard(selection);
            mSelectionManager.clearSelection();
        }
    }

    void copySelectionToClipboard(Selection selection) {
        assert(!selection.isEmpty());
        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                mClipper.clipDocuments(docs);
                Activity activity = getActivity();
                Snackbars.makeSnackbar(activity,
                        activity.getResources().getQuantityString(
                                R.plurals.clipboard_files_clipped, docs.size(), docs.size()),
                        Snackbar.LENGTH_SHORT).show();
            }
        }.execute(selection);
    }

    public void pasteFromClipboard() {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_PASTE_CLIPBOARD);

        copyFromClipboard();
        getActivity().invalidateOptionsMenu();
    }

    /**
     * Returns true if the list of files can be copied to destination. Note that this
     * is a policy check only. Currently the method does not attempt to verify
     * available space or any other environmental aspects possibly resulting in
     * failure to copy.
     *
     * @return true if the list of files can be copied to destination.
     */
    private boolean canCopy(List<DocumentInfo> files, RootInfo root, DocumentInfo dest) {
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

    public void selectAllFiles() {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_SELECT_ALL);

        // Exclude disabled files.
        Set<String> enabled = new HashSet<String>();
        List<String> modelIds = mAdapter.getModelIds();

        // Get the current selection.
        String[] alreadySelected = mSelectionManager.getSelection().getAll();
        for (String id : alreadySelected) {
           enabled.add(id);
        }

        for (String id : modelIds) {
            Cursor cursor = getModel().getItem(id);
            if (cursor == null) {
                Log.w(TAG, "Skipping selection. Can't obtain cursor for modeId: " + id);
                continue;
            }
            String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            if (mTuner.canSelectType(docMimeType, docFlags)) {
                if (enabled.size() >= MAX_DOCS_IN_INTENT) {
                    Snackbars.makeSnackbar(
                        getActivity(),
                        R.string.too_many_in_select_all,
                        Snackbar.LENGTH_SHORT)
                        .show();
                    break;
                }
                enabled.add(id);
            }
        }

        // Only select things currently visible in the adapter.
        boolean changed = mSelectionManager.setItemsSelected(enabled, true);
        if (changed) {
            updateDisplayState();
        }
    }

    /**
     * Attempts to restore focus on the directory listing.
     */
    public void requestFocus() {
        mFocusManager.restoreLastFocus();
    }

    private void setupDragAndDropOnDocumentView(View view, Cursor cursor) {
        final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        if (Document.MIME_TYPE_DIR.equals(docMimeType)) {
            // Make a directory item a drop target. Drop on non-directories and empty space
            // is handled at the list/grid view level.
            view.setOnDragListener(mOnDragListener);
        }

        if (mTuner.dragAndDropEnabled()) {
            // Make all items draggable.
            view.setOnLongClickListener(onLongClickListener);
        }
    }

    private View.OnDragListener mOnDragListener = new View.OnDragListener() {
        @Override
        public boolean onDrag(View v, DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    // TODO: Check if the event contains droppable data.
                    return true;

                // TODO: Expand drop target directory on hover?
                case DragEvent.ACTION_DRAG_ENTERED:
                    setDropTargetHighlight(v, true);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    setDropTargetHighlight(v, false);
                    return true;

                case DragEvent.ACTION_DRAG_LOCATION:
                    return true;

                case DragEvent.ACTION_DRAG_ENDED:
                    if (event.getResult()) {
                        // Exit selection mode if the drop was handled.
                        mSelectionManager.clearSelection();
                    }
                    return true;

                case DragEvent.ACTION_DROP:
                    // After a drop event, always stop highlighting the target.
                    setDropTargetHighlight(v, false);

                    ClipData clipData = event.getClipData();
                    if (clipData == null) {
                        Log.w(TAG, "Received invalid drop event with null clipdata. Ignoring.");
                        return false;
                    }

                    // Don't copy from the cwd into the cwd. Note: this currently doesn't work for
                    // multi-window drag, because localState isn't carried over from one process to
                    // another.
                    Object src = event.getLocalState();
                    DocumentInfo dst = getDestination(v);
                    if (Objects.equals(src, dst)) {
                        if (DEBUG) Log.d(TAG, "Drop target same as source. Ignoring.");
                        return false;
                    }

                    // Recognize multi-window drag and drop based on the fact that localState is not
                    // carried between processes. It will stop working when the localsState behavior
                    // is changed. The info about window should be passed in the localState then.
                    // The localState could also be null for copying from Recents in single window
                    // mode, but Recents doesn't offer this functionality (no directories).
                    Metrics.logUserAction(getContext(),
                            src == null ? Metrics.USER_ACTION_DRAG_N_DROP_MULTI_WINDOW
                                    : Metrics.USER_ACTION_DRAG_N_DROP);

                    copyFromClipData(clipData, dst);
                    return true;
            }
            return false;
        }

        private DocumentInfo getDestination(View v) {
            String id = getModelId(v);
            if (id != null) {
                Cursor dstCursor = mModel.getItem(id);
                if (dstCursor == null) {
                    Log.w(TAG, "Invalid destination. Can't obtain cursor for modelId: " + id);
                    return null;
                }
                return DocumentInfo.fromDirectoryCursor(dstCursor);
            }

            if (v == mRecView || v == mEmptyView) {
                return getDisplayState().stack.peek();
            }

            return null;
        }

        private void setDropTargetHighlight(View v, boolean highlight) {
            // Note: use exact comparison - this code is searching for views which are children of
            // the RecyclerView instance in the UI.
            if (v.getParent() == mRecView) {
                RecyclerView.ViewHolder vh = mRecView.getChildViewHolder(v);
                if (vh instanceof DocumentHolder) {
                    ((DocumentHolder) vh).setHighlighted(highlight);
                }
            }
        }
    };

    /**
     * Gets the model ID for a given motion event (using the event position)
     */
    private String getModelId(MotionEvent e) {
        View view = mRecView.findChildViewUnder(e.getX(), e.getY());
        if (view == null) {
            return null;
        }
        RecyclerView.ViewHolder vh = mRecView.getChildViewHolder(view);
        if (vh instanceof DocumentHolder) {
            return ((DocumentHolder) vh).modelId;
        } else {
            return null;
        }
    }

    /**
     * Gets the model ID for a given RecyclerView item.
     * @param view A View that is a document item view, or a child of a document item view.
     * @return The Model ID for the given document, or null if the given view is not associated with
     *     a document item view.
     */
    private String getModelId(View view) {
        View itemView = mRecView.findContainingItemView(view);
        if (itemView != null) {
            RecyclerView.ViewHolder vh = mRecView.getChildViewHolder(itemView);
            if (vh instanceof DocumentHolder) {
                return ((DocumentHolder) vh).modelId;
            }
        }
        return null;
    }

    private List<DocumentInfo> getDraggableDocuments(View currentItemView) {
        String modelId = getModelId(currentItemView);
        if (modelId == null) {
            return Collections.EMPTY_LIST;
        }

        final List<DocumentInfo> selectedDocs =
                mModel.getDocuments(mSelectionManager.getSelection());
        if (!selectedDocs.isEmpty()) {
            if (!isSelected(modelId)) {
                // There is a selection that does not include the current item, drag nothing.
                return Collections.EMPTY_LIST;
            }
            return selectedDocs;
        }

        final Cursor cursor = mModel.getItem(modelId);
        if (cursor == null) {
            Log.w(TAG, "Undraggable document. Can't obtain cursor for modelId " + modelId);
            return Collections.EMPTY_LIST;
        }

        return Lists.newArrayList(
                DocumentInfo.fromDirectoryCursor(cursor));
    }

    private static class DragShadowBuilder extends View.DragShadowBuilder {

        private final Context mContext;
        private final IconHelper mIconHelper;
        private final LayoutInflater mInflater;
        private final View mShadowView;
        private final TextView mTitle;
        private final ImageView mIcon;
        private final int mWidth;
        private final int mHeight;

        public DragShadowBuilder(Context context, IconHelper iconHelper, List<DocumentInfo> docs) {
            mContext = context;
            mIconHelper = iconHelper;
            mInflater = LayoutInflater.from(context);

            mWidth = mContext.getResources().getDimensionPixelSize(R.dimen.drag_shadow_width);
            mHeight= mContext.getResources().getDimensionPixelSize(R.dimen.drag_shadow_height);

            mShadowView = mInflater.inflate(R.layout.drag_shadow_layout, null);
            mTitle = (TextView) mShadowView.findViewById(android.R.id.title);
            mIcon = (ImageView) mShadowView.findViewById(android.R.id.icon);

            mTitle.setText(getTitle(docs));
            mIcon.setImageDrawable(getIcon(docs));
        }

        private Drawable getIcon(List<DocumentInfo> docs) {
            if (docs.size() == 1) {
                final DocumentInfo doc = docs.get(0);
                return mIconHelper.getDocumentIcon(mContext, doc.authority, doc.documentId,
                        doc.mimeType, doc.icon);
            }
            return mContext.getDrawable(com.android.internal.R.drawable.ic_doc_generic);
        }

        private String getTitle(List<DocumentInfo> docs) {
            if (docs.size() == 1) {
                final DocumentInfo doc = docs.get(0);
                return doc.displayName;
            }
            return Shared.getQuantityString(mContext, R.plurals.elements_dragged, docs.size());
        }

        @Override
        public void onProvideShadowMetrics(
                Point shadowSize, Point shadowTouchPoint) {
            shadowSize.set(mWidth, mHeight);
            shadowTouchPoint.set(mWidth, mHeight);
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            Rect r = canvas.getClipBounds();
            // Calling measure is necessary in order for all child views to get correctly laid out.
            mShadowView.measure(
                    View.MeasureSpec.makeMeasureSpec(r.right- r.left, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(r.top- r.bottom, View.MeasureSpec.EXACTLY));
            mShadowView.layout(r.left, r.top, r.right, r.bottom);
            mShadowView.draw(canvas);
        }
    }
    /**
     * Abstract task providing support for loading documents *off*
     * the main thread. And if it isn't obvious, creating a list
     * of documents (especially large lists) can be pretty expensive.
     */
    private abstract class GetDocumentsTask
            extends AsyncTask<Selection, Void, List<DocumentInfo>> {
        @Override
        protected final List<DocumentInfo> doInBackground(Selection... selected) {
            return mModel.getDocuments(selected[0]);
        }

        @Override
        protected final void onPostExecute(List<DocumentInfo> docs) {
            onDocumentsReady(docs);
        }

        abstract void onDocumentsReady(List<DocumentInfo> docs);
    }

    @Override
    public boolean isSelected(String modelId) {
        return mSelectionManager.getSelection().contains(modelId);
    }

    private class ItemEventListener implements DocumentHolder.EventListener {
        @Override
        public boolean onActivate(DocumentHolder doc) {
            // Toggle selection if we're in selection mode, othewise, view item.
            if (mSelectionManager.hasSelection()) {
                mSelectionManager.toggleSelection(doc.modelId);
            } else {
                handleViewItem(doc.modelId);
            }
            return true;
        }

        @Override
        public boolean onSelect(DocumentHolder doc) {
            mSelectionManager.toggleSelection(doc.modelId);
            mSelectionManager.setSelectionRangeBegin(doc.getAdapterPosition());
            return true;
        }

        @Override
        public boolean onKey(DocumentHolder doc, int keyCode, KeyEvent event) {
            // Only handle key-down events. This is simpler, consistent with most other UIs, and
            // enables the handling of repeated key events from holding down a key.
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            // Ignore tab key events.  Those should be handled by the top-level key handler.
            if (keyCode == KeyEvent.KEYCODE_TAB) {
                return false;
            }

            if (mFocusManager.handleKey(doc, keyCode, event)) {
                // Handle range selection adjustments. Extending the selection will adjust the
                // bounds of the in-progress range selection. Each time an unshifted navigation
                // event is received, the range selection is restarted.
                if (shouldExtendSelection(doc, event)) {
                    if (!mSelectionManager.isRangeSelectionActive()) {
                        // Start a range selection if one isn't active
                        mSelectionManager.startRangeSelection(doc.getAdapterPosition());
                    }
                    mSelectionManager.snapRangeSelection(mFocusManager.getFocusPosition());
                } else {
                    mSelectionManager.endRangeSelection();
                }
                return true;
            }

            // Handle enter key events
            switch (keyCode) {
                case KeyEvent.KEYCODE_ENTER:
                    if (event.isShiftPressed()) {
                        return onSelect(doc);
                    }
                    // For non-shifted enter keypresses, fall through.
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    return onActivate(doc);
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    // This has to be handled here instead of in a keyboard shortcut, because
                    // keyboard shortcuts all have to be modified with the 'Ctrl' key.
                    if (mSelectionManager.hasSelection()) {
                        Selection selection = mSelectionManager.getSelection(new Selection());
                        deleteDocuments(selection);
                    }
                    // Always handle the key, even if there was nothing to delete. This is a
                    // precaution to prevent other handlers from potentially picking up the event
                    // and triggering extra behaviours.
                    return true;
            }

            return false;
        }

        private boolean shouldExtendSelection(DocumentHolder doc, KeyEvent event) {
            if (!Events.isNavigationKeyCode(event.getKeyCode()) || !event.isShiftPressed()) {
                return false;
            }

            // TODO: Combine this method with onBeforeItemStateChange, as both of them are almost
            // the same, and responsible for the same thing (whether to select or not).
            final Cursor cursor = mModel.getItem(doc.modelId);
            if (cursor == null) {
                Log.w(TAG, "Couldn't obtain cursor for modelId: " + doc.modelId);
                return false;
            }

            final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            return mTuner.canSelectType(docMimeType, docFlags);
        }
    }

    private final class ModelUpdateListener implements Model.UpdateListener {
        @Override
        public void onModelUpdate(Model model) {
            if (model.info != null || model.error != null) {
                mMessageBar.setInfo(model.info);
                mMessageBar.setError(model.error);
                mMessageBar.show();
            }

            mProgressBar.setVisibility(model.isLoading() ? View.VISIBLE : View.GONE);

            if (model.isEmpty()) {
                if (mSearchMode) {
                    showNoResults(getDisplayState().stack.root);
                } else {
                    showEmptyDirectory();
                }
            } else {
                showDirectory();
                mAdapter.notifyDataSetChanged();
            }

            if (!model.isLoading()) {
                ((BaseActivity) getActivity()).notifyDirectoryLoaded(
                    model.doc != null ? model.doc.derivedUri : null);
            }
        }

        @Override
        public void onModelUpdateFailed(Exception e) {
            showQueryError();
        }
    }

    private DragStartHelper.OnDragStartListener mOnDragStartListener =
            new DragStartHelper.OnDragStartListener() {
        @Override
        public boolean onDragStart(View v, DragStartHelper helper) {
            if (isSelected(getModelId(v))) {
                List<DocumentInfo> docs = getDraggableDocuments(v);
                if (docs.isEmpty()) {
                    return false;
                }
                v.startDragAndDrop(
                        mClipper.getClipDataForDocuments(docs),
                        new DragShadowBuilder(getActivity(), mIconHelper, docs),
                        getDisplayState().stack.peek(),
                        View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ |
                                View.DRAG_FLAG_GLOBAL_URI_WRITE
                );
                return true;
            }

            return false;
        }
    };

    private DragStartHelper mDragHelper = new DragStartHelper(null, mOnDragStartListener);

    private View.OnLongClickListener onLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            return mDragHelper.onLongClick(v);
        }
    };

    // Previously we listened to events with one class, only to bounce them forward
    // to GestureDetector. We're still doing that here, but with a single class
    // that reduces overall complexity in our glue code.
    private static final class ListeningGestureDetector extends GestureDetector
            implements OnItemTouchListener {

        private int mLastTool = -1;
        private DragStartHelper mDragHelper;

        public ListeningGestureDetector(
                Context context, DragStartHelper dragHelper, GestureListener listener) {
            super(context, listener);
            mDragHelper = dragHelper;
            setOnDoubleTapListener(listener);
        }

        boolean mouseSpawnedLastEvent() {
            return Events.isMouseType(mLastTool);
        }

        boolean touchSpawnedLastEvent() {
            return Events.isTouchType(mLastTool);
        }

        @Override
        public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
            mLastTool = e.getToolType(0);

            // Detect drag events. When a drag is detected, intercept the rest of the gesture.
            View itemView = rv.findChildViewUnder(e.getX(), e.getY());
            if (itemView != null && mDragHelper.onTouch(itemView,  e)) {
                return true;
            }
            // Forward unhandled events to the GestureDetector.
            onTouchEvent(e);

            return false;
        }

        @Override
        public void onTouchEvent(RecyclerView rv, MotionEvent e) {
            View itemView = rv.findChildViewUnder(e.getX(), e.getY());
            mDragHelper.onTouch(itemView,  e);
            // Note: even though this event is being handled as part of a drag gesture, continue
            // forwarding to the GestureDetector. The detector needs to see the entire cluster of
            // events in order to properly interpret gestures.
            onTouchEvent(e);
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
    }

    /**
     * The gesture listener for items in the list/grid view. Interprets gestures and sends the
     * events to the target DocumentHolder, whence they are routed to the appropriate listener.
     */
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            // Single tap logic:
            // If the selection manager is active, it gets first whack at handling tap
            // events. Otherwise, tap events are routed to the target DocumentHolder.
            boolean handled = mSelectionManager.onSingleTapUp(
                        new MotionInputEvent(e, mRecView));

            if (handled) {
                return handled;
            }

            // Give the DocumentHolder a crack at the event.
            DocumentHolder holder = getTarget(e);
            if (holder != null) {
                handled = holder.onSingleTapUp(e);
            }

            return handled;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            // Long-press events get routed directly to the selection manager. They can be
            // changed to route through the DocumentHolder if necessary.
            mSelectionManager.onLongPress(new MotionInputEvent(e, mRecView));
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            // Double-tap events are handled directly by the DirectoryFragment. They can be changed
            // to route through the DocumentHolder if necessary.
            return DirectoryFragment.this.onDoubleTap(e);
        }

        private @Nullable DocumentHolder getTarget(MotionEvent e) {
            View childView = mRecView.findChildViewUnder(e.getX(), e.getY());
            if (childView != null) {
                return (DocumentHolder) mRecView.getChildViewHolder(childView);
            } else {
                return null;
            }
        }
    }

    public static void showDirectory(
            FragmentManager fm, RootInfo root, DocumentInfo doc, int anim) {
        create(fm, TYPE_NORMAL, root, doc, null, anim);
    }

    public static void showRecentsOpen(FragmentManager fm, int anim) {
        create(fm, TYPE_RECENT_OPEN, null, null, null, anim);
    }

    public static void reloadSearch(FragmentManager fm, RootInfo root, DocumentInfo doc,
            String query) {
        DirectoryFragment df = get(fm);

        df.mQuery = query;
        df.mRoot = root;
        df.mDocument = doc;
        df.mSearchMode =  query != null;
        df.getLoaderManager().restartLoader(LOADER_ID, null, df);
    }

    public static void reload(FragmentManager fm, int type, RootInfo root, DocumentInfo doc,
            String query) {
        DirectoryFragment df = get(fm);
        df.mType = type;
        df.mQuery = query;
        df.mRoot = root;
        df.mDocument = doc;
        df.mSearchMode =  query != null;
        df.getLoaderManager().restartLoader(LOADER_ID, null, df);
    }

    public static void create(FragmentManager fm, int type, RootInfo root, DocumentInfo doc,
            String query, int anim) {
        final Bundle args = new Bundle();
        args.putInt(Shared.EXTRA_TYPE, type);
        args.putParcelable(Shared.EXTRA_ROOT, root);
        args.putParcelable(Shared.EXTRA_DOC, doc);
        args.putString(Shared.EXTRA_QUERY, query);
        args.putParcelable(Shared.EXTRA_SELECTION, new Selection());

        final FragmentTransaction ft = fm.beginTransaction();
        AnimationView.setupAnimations(ft, anim, args);

        final DirectoryFragment fragment = new DirectoryFragment();
        fragment.setArguments(args);

        ft.replace(getFragmentId(), fragment);
        ft.commitAllowingStateLoss();
    }

    private static String buildStateKey(RootInfo root, DocumentInfo doc) {
        final StringBuilder builder = new StringBuilder();
        builder.append(root != null ? root.authority : "null").append(';');
        builder.append(root != null ? root.rootId : "null").append(';');
        builder.append(doc != null ? doc.documentId : "null");
        return builder.toString();
    }

    public static @Nullable DirectoryFragment get(FragmentManager fm) {
        // TODO: deal with multiple directories shown at once
        Fragment fragment = fm.findFragmentById(getFragmentId());
        return fragment instanceof DirectoryFragment
                ? (DirectoryFragment) fragment
                : null;
    }

    private static int getFragmentId() {
        return R.id.container_directory;
    }

    @Override
    public Loader<DirectoryResult> onCreateLoader(int id, Bundle args) {
        Context context = getActivity();
        State state = getDisplayState();

        Uri contentsUri;
        switch (mType) {
            case TYPE_NORMAL:
                contentsUri = mSearchMode ? DocumentsContract.buildSearchDocumentsUri(
                        mRoot.authority, mRoot.rootId, mQuery)
                        : DocumentsContract.buildChildDocumentsUri(
                                mDocument.authority, mDocument.documentId);
                if (mTuner.managedModeEnabled()) {
                    contentsUri = DocumentsContract.setManageMode(contentsUri);
                }
                return new DirectoryLoader(
                        context, mType, mRoot, mDocument, contentsUri, state.userSortOrder,
                        mSearchMode);
            case TYPE_RECENT_OPEN:
                final RootsCache roots = DocumentsApplication.getRootsCache(context);
                return new RecentsLoader(context, roots, state);

            default:
                throw new IllegalStateException("Unknown type " + mType);
        }
    }

    @Override
    public void onLoadFinished(Loader<DirectoryResult> loader, DirectoryResult result) {
        if (!isAdded()) return;

        if (mSearchMode) {
            Metrics.logUserAction(getContext(), Metrics.USER_ACTION_SEARCH);
        }

        State state = getDisplayState();

        mAdapter.notifyDataSetChanged();
        mModel.update(result);

        state.derivedSortOrder = result.sortOrder;

        updateLayout(state.derivedMode);

        if (mSelection != null) {
            mSelectionManager.setItemsSelected(mSelection.toList(), true);
            mSelection.clear();
        }

        // Restore any previous instance state
        final SparseArray<Parcelable> container = state.dirState.remove(mStateKey);
        if (container != null && !getArguments().getBoolean(Shared.EXTRA_IGNORE_STATE, false)) {
            getView().restoreHierarchyState(container);
        } else if (mLastSortOrder != state.derivedSortOrder) {
            // The derived sort order takes the user sort order into account, but applies
            // directory-specific defaults when the user doesn't explicitly set the sort
            // order. Scroll to the top if the sort order actually changed.
            mRecView.smoothScrollToPosition(0);
        }

        mLastSortOrder = state.derivedSortOrder;

        mTuner.onModelLoaded(mModel, mType, mSearchMode);

    }

    @Override
    public void onLoaderReset(Loader<DirectoryResult> loader) {
        mModel.update(null);
    }
  }
