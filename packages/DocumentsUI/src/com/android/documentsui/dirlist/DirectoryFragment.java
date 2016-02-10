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
import static com.android.documentsui.State.ACTION_MANAGE;
import static com.android.documentsui.State.MODE_GRID;
import static com.android.documentsui.State.MODE_LIST;
import static com.android.documentsui.State.SORT_ORDER_UNKNOWN;
import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorString;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;
import static com.google.common.base.Preconditions.checkArgument;

import android.annotation.IntDef;
import android.annotation.StringRes;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.GridLayoutManager.SpanSizeLookup;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.OnItemTouchListener;
import android.support.v7.widget.RecyclerView.RecyclerListener;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

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
import com.android.documentsui.MimePredicate;
import com.android.documentsui.R;
import com.android.documentsui.RecentLoader;
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
import java.util.List;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends Fragment implements DocumentsAdapter.Environment {

    @IntDef(flag = true, value = {
            TYPE_NORMAL,
            TYPE_SEARCH,
            TYPE_RECENT_OPEN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultType {}
    public static final int TYPE_NORMAL = 1;
    public static final int TYPE_SEARCH = 2;
    public static final int TYPE_RECENT_OPEN = 3;

    public static final int ANIM_NONE = 1;
    public static final int ANIM_SIDE = 2;
    public static final int ANIM_LEAVE = 3;
    public static final int ANIM_ENTER = 4;

    public static final int REQUEST_COPY_DESTINATION = 1;

    static final boolean DEBUG_ENABLE_DND = true;

    private static final String TAG = "DirectoryFragment";
    private static final int LOADER_ID = 42;
    private static final int DELETE_UNDO_TIMEOUT = 5000;
    private static final int DELETE_JOB_DELAY = 5500;
    private static final int EMPTY_REVEAL_DURATION = 250;

    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_ROOT = "root";
    private static final String EXTRA_DOC = "doc";
    private static final String EXTRA_QUERY = "query";
    private static final String EXTRA_IGNORE_STATE = "ignoreState";

    private Model mModel;
    private MultiSelectManager mSelectionManager;
    private Model.UpdateListener mModelUpdateListener = new ModelUpdateListener();
    private ItemEventListener mItemEventListener = new ItemEventListener();

    private IconHelper mIconHelper;

    private View mEmptyView;
    private RecyclerView mRecView;
    private ListeningGestureDetector mGestureDetector;

    private @ResultType int mType = TYPE_NORMAL;
    private String mStateKey;

    private int mLastSortOrder = SORT_ORDER_UNKNOWN;
    private DocumentsAdapter mAdapter;
    private LoaderCallbacks<DirectoryResult> mCallbacks;
    private FragmentTuner mTuner;
    private DocumentClipper mClipper;
    private GridLayoutManager mLayout;
    private int mColumnCount = 1;  // This will get updated when layout changes.

    private MessageBar mMessageBar;
    private View mProgressBar;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mMessageBar = MessageBar.create(getChildFragmentManager());
        mProgressBar = view.findViewById(R.id.progressbar);

        mEmptyView = view.findViewById(android.R.id.empty);

        mRecView = (RecyclerView) view.findViewById(R.id.list);
        mRecView.setRecyclerListener(
                new RecyclerListener() {
                    @Override
                    public void onViewRecycled(ViewHolder holder) {
                        cancelThumbnailTask(holder.itemView);
                    }
                });

        mRecView.setItemAnimator(new DirectoryItemAnimator(getActivity()));

        // TODO: Add a divider between views (which might use RecyclerView.ItemDecoration).
        if (DEBUG_ENABLE_DND) {
            setupDragAndDropOnDirectoryView(mRecView);
        }

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // Cancel any outstanding thumbnail requests
        final int count = mRecView.getChildCount();
        for (int i = 0; i < count; i++) {
            final View view = mRecView.getChildAt(i);
            cancelThumbnailTask(view);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Context context = getActivity();
        final State state = getDisplayState();

        final RootInfo root = getArguments().getParcelable(EXTRA_ROOT);
        final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);
        mStateKey = buildStateKey(root, doc);

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

        mGestureDetector = new ListeningGestureDetector(this.getContext(), new GestureListener());

        mRecView.addOnItemTouchListener(mGestureDetector);

        // final here because we'll manually bump the listener iwhen we had an initial selection,
        // but only after the model is fully loaded.
        final SelectionModeListener selectionListener = new SelectionModeListener();
        final Selection initialSelection = state.selectedDocuments.hasDirectoryKey(mStateKey)
            ? state.selectedDocuments
            : null;

        // TODO: instead of inserting the view into the constructor, extract listener-creation code
        // and set the listener on the view after the fact.  Then the view doesn't need to be passed
        // into the selection manager.
        mSelectionManager = new MultiSelectManager(
                mRecView,
                mAdapter,
                state.allowMultiple
                    ? MultiSelectManager.MODE_MULTIPLE
                    : MultiSelectManager.MODE_SINGLE,
                initialSelection);

        mSelectionManager.addCallback(selectionListener);

        mModel = new Model();
        mModel.addUpdateListener(mAdapter);
        mModel.addUpdateListener(mModelUpdateListener);

        mType = getArguments().getInt(EXTRA_TYPE);

        mTuner = FragmentTuner.pick(getContext(), state);
        mClipper = new DocumentClipper(context);

        boolean hideGridTitles;
        if (mType == TYPE_RECENT_OPEN) {
            // Hide titles when showing recents for picking images/videos
            hideGridTitles = MimePredicate.mimeMatches(
                    MimePredicate.VISUAL_MIMES, state.acceptMimes);
        } else {
            hideGridTitles = (doc != null) && doc.isGridTitlesHidden();
        }
        GridDocumentHolder.setHideTitles(hideGridTitles);

        final ActivityManager am = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        boolean svelte = am.isLowRamDevice() && (mType == TYPE_RECENT_OPEN);
        mIconHelper.setThumbnailsEnabled(!svelte);

        mCallbacks = new LoaderCallbacks<DirectoryResult>() {
            @Override
            public Loader<DirectoryResult> onCreateLoader(int id, Bundle args) {
                final String query = getArguments().getString(EXTRA_QUERY);

                Uri contentsUri;
                switch (mType) {
                    case TYPE_NORMAL:
                        contentsUri = DocumentsContract.buildChildDocumentsUri(
                                doc.authority, doc.documentId);
                        if (state.action == ACTION_MANAGE) {
                            contentsUri = DocumentsContract.setManageMode(contentsUri);
                        }
                        return new DirectoryLoader(
                                context, mType, root, doc, contentsUri, state.userSortOrder);
                    case TYPE_SEARCH:
                        contentsUri = DocumentsContract.buildSearchDocumentsUri(
                                root.authority, root.rootId, query);
                        if (state.action == ACTION_MANAGE) {
                            contentsUri = DocumentsContract.setManageMode(contentsUri);
                        }
                        return new DirectoryLoader(
                                context, mType, root, doc, contentsUri, state.userSortOrder);
                    case TYPE_RECENT_OPEN:
                        final RootsCache roots = DocumentsApplication.getRootsCache(context);
                        return new RecentLoader(context, roots, state);
                    default:
                        throw new IllegalStateException("Unknown type " + mType);
                }
            }

            @Override
            public void onLoadFinished(Loader<DirectoryResult> loader, DirectoryResult result) {
                if (!isAdded()) return;

                mModel.update(result);
                state.derivedSortOrder = result.sortOrder;

                updateDisplayState();

                if (initialSelection != null) {
                    selectionListener.onSelectionChanged();
                }

                // Restore any previous instance state
                final SparseArray<Parcelable> container = state.dirState.remove(mStateKey);
                if (container != null && !getArguments().getBoolean(EXTRA_IGNORE_STATE, false)) {
                    getView().restoreHierarchyState(container);
                } else if (mLastSortOrder != state.derivedSortOrder) {
                    // The derived sort order takes the user sort order into account, but applies
                    // directory-specific defaults when the user doesn't explicitly set the sort
                    // order. Scroll to the top if the sort order actually changed.
                    mRecView.smoothScrollToPosition(0);
                }

                mLastSortOrder = state.derivedSortOrder;

                mTuner.onModelLoaded(mModel, mType);
            }

            @Override
            public void onLoaderReset(Loader<DirectoryResult> loader) {
                mModel.update(null);
            }
        };

        // Kick off loader at least once
        getLoaderManager().restartLoader(LOADER_ID, null, mCallbacks);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        State state = getDisplayState();
        if (mSelectionManager.hasSelection()) {
            mSelectionManager.getSelection(state.selectedDocuments);
            state.selectedDocuments.setDirectoryKey(mStateKey);
            if (!state.selectedDocuments.isEmpty()) {
                if (DEBUG) Log.d(TAG, "Persisted selection: " + state.selectedDocuments);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // There's only one request code right now. Replace this with a switch statement or
        // something more scalable when more codes are added.
        if (requestCode != REQUEST_COPY_DESTINATION) {
            return;
        }
        if (resultCode == Activity.RESULT_CANCELED || data == null) {
            // User pressed the back button or otherwise cancelled the destination pick. Don't
            // proceed with the copy.
            return;
        }

        int operationType = data.getIntExtra(
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
        checkNotNull(cursor, "Cursor cannot be null.");
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
        getLoaderManager().restartLoader(LOADER_ID, null, mCallbacks);
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

        checkState(mRecView.getWidth() > 0);
        int columnCount = Math.max(1,
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
    private final class SelectionModeListener
            implements MultiSelectManager.Callback, ActionMode.Callback {

        private Selection mSelected = new Selection();
        private ActionMode mActionMode;
        private int mNoDeleteCount = 0;
        private int mNoRenameCount = -1;
        private Menu mMenu;

        @Override
        public boolean onBeforeItemStateChange(String modelId, boolean selected) {
            if (selected) {
                final Cursor cursor = mModel.getItem(modelId);
                checkNotNull(cursor, "Cursor cannot be null.");
                final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
                final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
                return mTuner.canSelectType(docMimeType, docFlags);
            }
            return true;
        }

        @Override
        public void onItemStateChanged(String modelId, boolean selected) {
            final Cursor cursor = mModel.getItem(modelId);
            checkNotNull(cursor, "Cursor cannot be null.");

            // TODO: Should this be happening in onSelectionChanged? Technically this callback is
            // triggered on "silent" selection updates (i.e. we might be reacting to unfinalized
            // selection changes here)
            final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            if ((docFlags & Document.FLAG_SUPPORTS_DELETE) == 0) {
                mNoDeleteCount += selected ? 1 : -1;
            }
            if ((docFlags & Document.FLAG_SUPPORTS_RENAME) != 0) {
                mNoRenameCount += selected ? 1 : -1;
            }
        }

        @Override
        public void onSelectionChanged() {
            mSelectionManager.getSelection(mSelected);
            TypedValue color = new TypedValue();
            if (mSelected.size() > 0) {
                if (DEBUG) Log.d(TAG, "Maybe starting action mode.");
                if (mActionMode == null) {
                    if (DEBUG) Log.d(TAG, "Yeah. Starting action mode.");
                    mActionMode = getActivity().startActionMode(this);
                }
                getActivity().getTheme().resolveAttribute(R.attr.colorActionMode, color, true);
                updateActionMenu();
            } else {
                if (DEBUG) Log.d(TAG, "Finishing action mode.");
                if (mActionMode != null) {
                    mActionMode.finish();
                }
                getActivity().getTheme().resolveAttribute(
                    android.R.attr.colorPrimaryDark, color, true);
            }
            getActivity().getWindow().setStatusBarColor(color.data);

            if (mActionMode != null) {
                mActionMode.setTitle(String.valueOf(mSelected.size()));
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
            mNoDeleteCount = 0;
            mNoRenameCount = -1;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            int size = mSelectionManager.getSelection().size();
            mode.getMenuInflater().inflate(R.menu.mode_directory, menu);
            mode.setTitle(TextUtils.formatSelectedCount(size));
            return (size > 0);
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            mMenu = menu;
            updateActionMenu();
            return true;
        }

        boolean canRenameSelection() {
            return mNoRenameCount == 0 && mSelectionManager.getSelection().size() == 1;
        }

        boolean canDeleteSelection() {
            return mNoDeleteCount == 0;
        }

        private void updateActionMenu() {
            checkNotNull(mMenu);

            // Delegate update logic to our owning action, since specialized logic is desired.
            mTuner.updateActionMenu(mMenu, mType, canDeleteSelection(), canRenameSelection());
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
                    mode.finish();
                    return true;

                case R.id.menu_delete:
                    // Exit selection mode first, so we avoid deselecting deleted documents.
                    mode.finish();
                    deleteDocuments(selection);
                    return true;

                case R.id.menu_copy_to:
                    transferDocuments(selection, FileOperationService.OPERATION_COPY);
                    mode.finish();
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
                    renameDocuments(selection);
                    mode.finish();
                    return true;

                default:
                    if (DEBUG) Log.d(TAG, "Unhandled menu item selected: " + item);
                    return false;
            }
        }
    }

    public final boolean onBackPressed() {
        if (mSelectionManager.hasSelection()) {
            if (DEBUG) Log.d(TAG, "Clearing selection on back pressed.");
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
        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                // TODO: Implement support in Files activity for opening multiple docs.
                BaseActivity.get(DirectoryFragment.this).onDocumentsPicked(docs);
            }
        }.execute(selected);
    }

    private void shareDocuments(final Selection selected) {
        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                Intent intent;

                // Filter out directories - those can't be shared.
                List<DocumentInfo> docsForSend = new ArrayList<>();
                for (DocumentInfo doc: docs) {
                    if (!Document.MIME_TYPE_DIR.equals(doc.mimeType)) {
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

    private void deleteDocuments(final Selection selected) {

        checkArgument(!selected.isEmpty());
        final DocumentInfo srcParent = getDisplayState().stack.peek();
        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                // Hide the files in the UI.
                final SparseArray<String> hidden = mAdapter.hide(selected.getAll());

                checkState(DELETE_JOB_DELAY > DELETE_UNDO_TIMEOUT);
                String operationId = FileOperations.delete(
                        getActivity(), docs, srcParent, getDisplayState().stack,
                        DELETE_JOB_DELAY);
                showDeleteSnackbar(hidden, operationId);
            }
        }.execute(selected);
    }

    private void showDeleteSnackbar(final SparseArray<String> hidden, final String jobId) {

        Context context = getActivity();
        String message = Shared.getQuantityString(context, R.plurals.deleting, hidden.size());

        // Show a snackbar informing the user that files will be deleted, and give them an option to
        // cancel.
        final Activity activity = getActivity();
        Snackbars.makeSnackbar(activity, message, DELETE_UNDO_TIMEOUT)
                .setAction(
                        R.string.undo,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {}
                        })
                .setCallback(
                        new Snackbar.Callback() {
                            @Override
                            public void onDismissed(Snackbar snackbar, int event) {
                                if (event == Snackbar.Callback.DISMISS_EVENT_ACTION) {
                                    // If the delete was cancelled, just unhide the files.
                                    FileOperations.cancel(activity, jobId);
                                    mAdapter.unhide(hidden);
                                }
                            }
                        })
                .show();
    }

    private void transferDocuments(final Selection selected, final @OpType int mode) {
        // Pop up a dialog to pick a destination.  This is inadequate but works for now.
        // TODO: Implement a picker that is to spec.
        final Intent intent = new Intent(
                Shared.ACTION_PICK_COPY_DESTINATION,
                Uri.EMPTY,
                getActivity(),
                DocumentsActivity.class);

        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                getDisplayState().selectedDocumentsForCopy = docs;

                boolean directoryCopy = false;
                for (DocumentInfo info : docs) {
                    if (Document.MIME_TYPE_DIR.equals(info.mimeType)) {
                        directoryCopy = true;
                        break;
                    }
                }
                intent.putExtra(Shared.EXTRA_DIRECTORY_COPY, directoryCopy);
                intent.putExtra(FileOperationService.EXTRA_OPERATION, mode);
                startActivityForResult(intent, REQUEST_COPY_DESTINATION);
            }
        }.execute(selected);
    }

    private void renameDocuments(Selection selected) {
        // Batch renaming not supported
        // Rename option is only available in menu when 1 document selected
        checkArgument(selected.size() == 1);

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
    }

    @Override
    public void onBindDocumentHolder(DocumentHolder holder, Cursor cursor) {
        if (DEBUG_ENABLE_DND) {
            setupDragAndDropOnDocumentView(holder.itemView, cursor);
        }
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
        mRecView.setVisibility(View.GONE);
    }

    private void showDirectory() {
        mEmptyView.setVisibility(View.GONE);
        mRecView.setVisibility(View.VISIBLE);
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
        checkNotNull(clipData);
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
        if (!canCopy(docs, destination)) {
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

    private ClipData getClipDataFromDocuments(List<DocumentInfo> docs) {
        Context context = getActivity();
        final ContentResolver resolver = context.getContentResolver();
        ClipData clipData = null;
        for (DocumentInfo doc : docs) {
            final Uri uri = DocumentsContract.buildDocumentUri(doc.authority, doc.documentId);
            if (clipData == null) {
                // TODO: figure out what this string should be.
                // Currently it is not displayed anywhere in the UI, but this might change.
                final String label = "";
                clipData = ClipData.newUri(resolver, label, uri);
            } else {
                // TODO: update list of mime types in ClipData.
                clipData.addItem(new ClipData.Item(uri));
            }
        }
        return clipData;
    }

    public void copySelectedToClipboard() {
        Selection selection = mSelectionManager.getSelection(new Selection());
        if (!selection.isEmpty()) {
            copySelectionToClipboard(selection);
            mSelectionManager.clearSelection();
        }
    }

    void copySelectionToClipboard(Selection selection) {
        checkArgument(!selection.isEmpty());
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
    boolean canCopy(List<DocumentInfo> files, DocumentInfo dest) {
        BaseActivity activity = (BaseActivity) getActivity();

        final RootInfo root = activity.getCurrentRoot();

        // Can't copy folders to Downloads.
        if (root.isDownloads()) {
            for (DocumentInfo docs : files) {
                if (docs.isDirectory()) {
                    return false;
                }
            }
        }

        return dest != null && dest.isDirectory() && dest.isCreateSupported();
    }

    public void selectAllFiles() {
        // Only select things currently visible in the adapter.
        boolean changed = mSelectionManager.setItemsSelected(mAdapter.getModelIds(), true);
        if (changed) {
            updateDisplayState();
        }
    }

    private void setupDragAndDropOnDirectoryView(View view) {
        // Listen for drops on non-directory items and empty space.
        view.setOnDragListener(mOnDragListener);
    }

    private void setupDragAndDropOnDocumentView(View view, Cursor cursor) {
        final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        if (Document.MIME_TYPE_DIR.equals(docMimeType)) {
            // Make a directory item a drop target. Drop on non-directories and empty space
            // is handled at the list/grid view level.
            view.setOnDragListener(mOnDragListener);
        }

        view.setOnLongClickListener(mLongClickListener);
    }

    private View.OnDragListener mOnDragListener = new View.OnDragListener() {
        @Override
        public boolean onDrag(View v, DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    // TODO: Check if the event contains droppable data.
                    return true;

                // TODO: Highlight potential drop target directory?
                // TODO: Expand drop target directory on hover?
                case DragEvent.ACTION_DRAG_ENTERED:
                case DragEvent.ACTION_DRAG_LOCATION:
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    return true;

                case DragEvent.ACTION_DROP:
                    String dstId = getModelId(v);
                    DocumentInfo dstDir = null;
                    if (dstId != null) {
                        Cursor dstCursor = mModel.getItem(dstId);
                        checkNotNull(dstCursor, "Cursor cannot be null.");
                        dstDir = DocumentInfo.fromDirectoryCursor(dstCursor);
                        // TODO: Do not drop into the directory where the documents came from.
                    }
                    copyFromClipData(event.getClipData(), dstDir);
                    return true;
            }
            return false;
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
        while (true) {
            if (view.getLayoutParams() instanceof RecyclerView.LayoutParams) {
                RecyclerView.ViewHolder vh = mRecView.getChildViewHolder(view);
                if (vh instanceof DocumentHolder) {
                    return ((DocumentHolder) vh).modelId;
                } else {
                    return null;
                }
            }
            ViewParent parent = view.getParent();
            if (parent == null || !(parent instanceof View)) {
                return null;
            }
            view = (View) parent;
        }
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
        checkNotNull(cursor, "Cursor cannot be null.");
        final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);

        return Lists.newArrayList(doc);
    }

    private Drawable getDragShadowIcon(List<DocumentInfo> docs) {
        if (docs.size() == 1) {
            final DocumentInfo doc = docs.get(0);
            return mIconHelper.getDocumentIcon(getActivity(), doc.authority, doc.documentId,
                    doc.mimeType, doc.icon);
        }
        return getActivity().getDrawable(R.drawable.ic_doc_generic);
    }

    private class DrawableShadowBuilder extends View.DragShadowBuilder {

        private final Drawable mShadow;

        private final int mShadowDimension;

        public DrawableShadowBuilder(Drawable shadow) {
            mShadow = shadow;
            mShadowDimension = getResources().getDimensionPixelSize(
                    R.dimen.drag_shadow_size);
            mShadow.setBounds(0, 0, mShadowDimension, mShadowDimension);
        }

        @Override
        public void onProvideShadowMetrics(
                Point shadowSize, Point shadowTouchPoint) {
            shadowSize.set(mShadowDimension, mShadowDimension);
            shadowTouchPoint.set(mShadowDimension / 2, mShadowDimension / 2);
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            mShadow.draw(canvas);
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

            boolean handled = false;
            if (Events.isNavigationKeyCode(keyCode)) {
                // Find the target item and focus it.
                int endPos = findTargetPosition(doc.itemView, keyCode);

                if (endPos != RecyclerView.NO_POSITION) {
                    focusItem(endPos);

                    // Handle any necessary adjustments to selection.
                    boolean extendSelection = event.isShiftPressed();
                    if (extendSelection) {
                        int startPos = doc.getAdapterPosition();
                        mSelectionManager.selectRange(startPos, endPos);
                    }
                    handled = true;
                }
            } else {
                // Handle enter key events
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    handled = onActivate(doc);
                }
            }

            return handled;
        }

        /**
         * Finds the destination position where the focus should land for a given navigation event.
         *
         * @param view The view that received the event.
         * @param keyCode The key code for the event.
         * @return The adapter position of the destination item. Could be RecyclerView.NO_POSITION.
         */
        private int findTargetPosition(View view, int keyCode) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MOVE_HOME:
                    return 0;
                case KeyEvent.KEYCODE_MOVE_END:
                    return mAdapter.getItemCount() - 1;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    return findTargetPositionByPage(view, keyCode);
            }

            // Find a navigation target based on the arrow key that the user pressed.
            int searchDir = -1;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    searchDir = View.FOCUS_UP;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    searchDir = View.FOCUS_DOWN;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    searchDir = View.FOCUS_LEFT;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    searchDir = View.FOCUS_RIGHT;
                    break;
            }

            if (searchDir != -1) {
                View targetView = view.focusSearch(searchDir);
                // TargetView can be null, for example, if the user pressed <down> at the bottom
                // of the list.
                if (targetView != null) {
                    // Ignore navigation targets that aren't items in the RecyclerView.
                    if (targetView.getParent() == mRecView) {
                        return mRecView.getChildAdapterPosition(targetView);
                    }
                }
            }

            return RecyclerView.NO_POSITION;
        }

        /**
         * Given a PgUp/PgDn event and the current view, find the position of the target view.
         * This returns:
         * <li>The position of the topmost (or bottom-most) visible item, if the current item is not
         *     the top- or bottom-most visible item.
         * <li>The position of an item that is one page's worth of items up (or down) if the current
         *      item is the top- or bottom-most visible item.
         * <li>The first (or last) item, if paging up (or down) would go past those limits.
         * @param view The view that received the key event.
         * @param keyCode Must be KEYCODE_PAGE_UP or KEYCODE_PAGE_DOWN.
         * @return The adapter position of the target item.
         */
        private int findTargetPositionByPage(View view, int keyCode) {
            int first = mLayout.findFirstVisibleItemPosition();
            int last = mLayout.findLastVisibleItemPosition();
            int current = mRecView.getChildAdapterPosition(view);
            int pageSize = last - first + 1;

            if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                if (current > first) {
                    // If the current item isn't the first item, target the first item.
                    return first;
                } else {
                    // If the current item is the first item, target the item one page up.
                    int target = current - pageSize;
                    return target < 0 ? 0 : target;
                }
            }

            if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                if (current < last) {
                    // If the current item isn't the last item, target the last item.
                    return last;
                } else {
                    // If the current item is the last item, target the item one page down.
                    int target = current + pageSize;
                    int max = mAdapter.getItemCount() - 1;
                    return target < max ? target : max;
                }
            }

            throw new IllegalArgumentException("Unsupported keyCode: " + keyCode);
        }

        /**
         * Requests focus for the item in the given adapter position, scrolling the RecyclerView if
         * necessary.
         *
         * @param pos
         */
        public void focusItem(final int pos) {
            // If the item is already in view, focus it; otherwise, scroll to it and focus it.
            RecyclerView.ViewHolder vh = mRecView.findViewHolderForAdapterPosition(pos);
            if (vh != null) {
                vh.itemView.requestFocus();
            } else {
                mRecView.smoothScrollToPosition(pos);
                // Set a one-time listener to request focus when the scroll has completed.
                mRecView.addOnScrollListener(
                    new RecyclerView.OnScrollListener() {
                        @Override
                        public void onScrollStateChanged (RecyclerView view, int newState) {
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                // When scrolling stops, find the item and focus it.
                                RecyclerView.ViewHolder vh =
                                        view.findViewHolderForAdapterPosition(pos);
                                if (vh != null) {
                                    vh.itemView.requestFocus();
                                } else {
                                    // This might happen in weird corner cases, e.g. if the user is
                                    // scrolling while a delete operation is in progress.  In that
                                    // case, just don't attempt to focus the missing item.
                                    Log.w(
                                        TAG, "Unable to focus position " + pos + " after a scroll");
                                }
                                view.removeOnScrollListener(this);
                            }
                        }
                    });
            }
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
                if (getDisplayState().currentSearch != null) {
                    showNoResults(getDisplayState().stack.root);
                } else {
                    showEmptyDirectory();
                }
            } else {
                showDirectory();
                mAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onModelUpdateFailed(Exception e) {
            showQueryError();
        }
    }

    private View.OnLongClickListener mLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            if (mGestureDetector.mouseSpawnedLastEvent()) {
                List<DocumentInfo> docs = getDraggableDocuments(v);
                if (docs.isEmpty()) {
                    return false;
                }
                v.startDrag(
                        getClipDataFromDocuments(docs),
                        new DrawableShadowBuilder(getDragShadowIcon(docs)),
                        null,
                        View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ |
                                View.DRAG_FLAG_GLOBAL_URI_WRITE
                );
                return true;
            }

            return false;
        }
    };

    // Previously we listened to events with one class, only to bounce them forward
    // to GestureDetector. We're still doing that here, but with a single class
    // that reduces overall complexity in our glue code.
    private static final class ListeningGestureDetector extends GestureDetector
            implements OnItemTouchListener {

        private int mLastTool = -1;

        public ListeningGestureDetector(Context context, GestureListener listener) {
            super(context, listener);
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
            onTouchEvent(e);  // bounce this forward to our detecty heart
            return false;
        }

        @Override
        public void onTouchEvent(RecyclerView rv, MotionEvent e) {}

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
        show(fm, TYPE_NORMAL, root, doc, null, anim);
    }

    public static void showSearch(FragmentManager fm, RootInfo root, String query, int anim) {
        show(fm, TYPE_SEARCH, root, null, query, anim);
    }

    public static void showRecentsOpen(FragmentManager fm, int anim) {
        show(fm, TYPE_RECENT_OPEN, null, null, null, anim);
    }

    private static void show(FragmentManager fm, int type, RootInfo root, DocumentInfo doc,
            String query, int anim) {
        final Bundle args = new Bundle();
        args.putInt(EXTRA_TYPE, type);
        args.putParcelable(EXTRA_ROOT, root);
        args.putParcelable(EXTRA_DOC, doc);
        args.putString(EXTRA_QUERY, query);

        final FragmentTransaction ft = fm.beginTransaction();
        switch (anim) {
            case ANIM_SIDE:
                args.putBoolean(EXTRA_IGNORE_STATE, true);
                break;
            case ANIM_ENTER:
                args.putBoolean(EXTRA_IGNORE_STATE, true);
                ft.setCustomAnimations(R.animator.dir_enter, R.animator.dir_frozen);
                break;
            case ANIM_LEAVE:
                ft.setCustomAnimations(R.animator.dir_frozen, R.animator.dir_leave);
                break;
        }

        final DirectoryFragment fragment = new DirectoryFragment();
        fragment.setArguments(args);

        ft.replace(R.id.container_directory, fragment);
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
        Fragment fragment = fm.findFragmentById(R.id.container_directory);
        return fragment instanceof DirectoryFragment
                ? (DirectoryFragment) fragment
                : null;
    }
}
