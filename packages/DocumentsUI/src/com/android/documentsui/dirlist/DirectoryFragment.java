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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.v13.view.DragStartHelper;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.GridLayoutManager.SpanSizeLookup;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.RecyclerListener;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.text.BidiFormatter;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
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
import com.android.documentsui.Events.MotionInputEvent;
import com.android.documentsui.ItemDragListener;
import com.android.documentsui.MenuManager;
import com.android.documentsui.Menus;
import com.android.documentsui.MessageBar;
import com.android.documentsui.Metrics;
import com.android.documentsui.MimePredicate;
import com.android.documentsui.R;
import com.android.documentsui.RecentsLoader;
import com.android.documentsui.RetainedState;
import com.android.documentsui.RootsCache;
import com.android.documentsui.Shared;
import com.android.documentsui.Snackbars;
import com.android.documentsui.State;
import com.android.documentsui.State.ViewMode;
import com.android.documentsui.UrisSupplier;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;
import com.android.documentsui.services.FileOperation;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;
import com.android.documentsui.services.FileOperations;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends Fragment
        implements DocumentsAdapter.Environment, LoaderCallbacks<DirectoryResult>,
        ItemDragListener.DragHost {

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
    private ItemEventListener mItemEventListener;
    private SelectionModeListener mSelectionModeListener;
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
    // Note, we use !null to indicate that selection was restored (from rotation).
    // So don't fiddle with this field unless you've got the bigger picture in mind.
    private @Nullable Selection mRestoredSelection = null;
    // Here we save the clip details of moveTo/copyTo actions when picker shows up.
    // This will be written to saved instance.
    private @Nullable FileOperation mPendingOperation;
    private boolean mSearchMode = false;

    private @Nullable BandController mBandController;
    private @Nullable ActionMode mActionMode;

    private DirectoryDragListener mOnDragListener;
    private MenuManager mMenuManager;

    /**
     * A callback to show snackbar at the beginning of moving and copying.
     */
    private final FileOperations.Callback mFileOpCallback = (status, opType, docCount) -> {
        if (status == FileOperations.Callback.STATUS_REJECTED) {
            Snackbars.showPasteFailed(getActivity());
            return;
        }

        if (docCount == 0) {
            // Nothing has been pasted, so there is no need to show a snackbar.
            return;
        }

        switch (opType) {
            case FileOperationService.OPERATION_MOVE:
                Snackbars.showMove(getActivity(), docCount);
                break;
            case FileOperationService.OPERATION_COPY:
                Snackbars.showCopy(getActivity(), docCount);
                break;
            case FileOperationService.OPERATION_DELETE:
                // We don't show anything for deletion.
                break;
            default:
                throw new UnsupportedOperationException("Unsupported Operation: " + opType);
        }
    };

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

        mOnDragListener = new DirectoryDragListener(this);

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
        mSearchMode = args.getBoolean(Shared.EXTRA_SEARCH_MODE);
        mPendingOperation = args.getParcelable(FileOperationService.EXTRA_OPERATION);

        // Restore any selection we may have squirreled away in retained state.
        @Nullable RetainedState retained = getBaseActivity().getRetainedState();
        if (retained != null && retained.hasSelection()) {
            // We claim the selection for ourselves and null it out once used
            // so we don't have a rando selection hanging around in RetainedState.
            mRestoredSelection = retained.selection;
            retained.selection = null;
        }

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

        // TODO: instead of inserting the view into the constructor, extract listener-creation code
        // and set the listener on the view after the fact.  Then the view doesn't need to be passed
        // into the selection manager.
        mSelectionManager = new MultiSelectManager(
                mAdapter,
                state.allowMultiple
                    ? MultiSelectManager.MODE_MULTIPLE
                    : MultiSelectManager.MODE_SINGLE);

        GestureListener gestureListener = new GestureListener(
                mSelectionManager,
                mRecView,
                this::getTarget,
                this::onDoubleTap,
                this::onRightClick);

        mGestureDetector =
                new ListeningGestureDetector(this.getContext(), mDragHelper, gestureListener);

        mRecView.addOnItemTouchListener(mGestureDetector);
        mEmptyView.setOnTouchListener(mGestureDetector);

        if (state.allowMultiple) {
            mBandController = new BandController(mRecView, mAdapter, mSelectionManager);
        }

        mSelectionModeListener = new SelectionModeListener();
        mSelectionManager.addCallback(mSelectionModeListener);

        mModel = new Model();
        mModel.addUpdateListener(mAdapter);
        mModel.addUpdateListener(mModelUpdateListener);

        // Make sure this is done after the RecyclerView is set up.
        mFocusManager = new FocusManager(context, mRecView, mModel);

        mItemEventListener = new ItemEventListener(
                mSelectionManager,
                mFocusManager,
                this::handleViewItem,
                this::deleteDocuments,
                this::canSelect);

        final BaseActivity activity = getBaseActivity();
        mTuner = activity.createFragmentTuner();
        mMenuManager = activity.getMenuManager();
        mClipper = DocumentsApplication.getDocumentClipper(getContext());

        final ActivityManager am = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        boolean svelte = am.isLowRamDevice() && (mType == TYPE_RECENT_OPEN);
        mIconHelper.setThumbnailsEnabled(!svelte);

        // Kick off loader at least once
        getLoaderManager().restartLoader(LOADER_ID, null, this);
    }

    public void retainState(RetainedState state) {
        state.selection = mSelectionManager.getSelection(new Selection());
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(Shared.EXTRA_TYPE, mType);
        outState.putParcelable(Shared.EXTRA_ROOT, mRoot);
        outState.putParcelable(Shared.EXTRA_DOC, mDocument);
        outState.putString(Shared.EXTRA_QUERY, mQuery);
        outState.putBoolean(Shared.EXTRA_SEARCH_MODE, mSearchMode);
        outState.putParcelable(FileOperationService.EXTRA_OPERATION, mPendingOperation);
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

    @Override
    public void onCreateContextMenu(ContextMenu menu,
            View v,
            ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.context_menu, menu);

        menu.add(Menu.NONE, R.id.menu_create_dir, Menu.NONE, R.string.menu_create_dir);
        menu.add(Menu.NONE, R.id.menu_delete, Menu.NONE, R.string.menu_delete);
        menu.add(Menu.NONE, R.id.menu_rename, Menu.NONE, R.string.menu_rename);

        if (v == mRecView || v == mEmptyView) {
            mMenuManager.updateContextMenu(menu, null, getBaseActivity().getDirectoryDetails());
        } else {
            mMenuManager.updateContextMenu(menu, mSelectionModeListener,
                    getBaseActivity().getDirectoryDetails());
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return handleMenuItemClick(item);
    }

    private void handleCopyResult(int resultCode, Intent data) {

        FileOperation operation = mPendingOperation;
        mPendingOperation = null;

        if (resultCode == Activity.RESULT_CANCELED || data == null) {
            // User pressed the back button or otherwise cancelled the destination pick. Don't
            // proceed with the copy.
            operation.dispose();
            return;
        }

        operation.setDestination(data.getParcelableExtra(Shared.EXTRA_STACK));

        FileOperations.start(getContext(), operation, mFileOpCallback);
    }

    protected boolean onDoubleTap(MotionInputEvent event) {
        if (event.isMouseEvent()) {
            String id = getModelId(event);
            if (id != null) {
                return handleViewItem(id);
            }
        }
        return false;
    }

    protected boolean onRightClick(MotionInputEvent e) {
        if (e.getItemPosition() != RecyclerView.NO_POSITION) {
            final DocumentHolder holder = getTarget(e);
            String modelId = getModelId(holder.itemView);
            if (!mSelectionManager.getSelection().contains(modelId)) {
                mSelectionManager.clearSelection();
                // Set selection on the one single item
                List<String> ids = Collections.singletonList(modelId);
                mSelectionManager.setItemsSelected(ids, true);
            }

            // We are registering for context menu here so long-press doesn't trigger this
            // floating context menu, and then quickly unregister right afterwards
            registerForContextMenu(holder.itemView);
            mRecView.showContextMenuForChild(holder.itemView,
                    e.getX() - holder.itemView.getLeft(), e.getY() - holder.itemView.getTop());
            unregisterForContextMenu(holder.itemView);
        }
        // If there was no corresponding item pos, that means user right-clicked on the blank
        // pane
        // We would want to show different options then, and not select any item
        // The blank pane could be the recyclerView or the emptyView, so we need to register
        // according to whichever one is visible
        else if (mEmptyView.getVisibility() == View.VISIBLE) {
            registerForContextMenu(mEmptyView);
            mEmptyView.showContextMenu(e.getX(), e.getY());
            unregisterForContextMenu(mEmptyView);
            return true;
        } else {
            registerForContextMenu(mRecView);
            mRecView.showContextMenu(e.getX(), e.getY());
            unregisterForContextMenu(mRecView);
        }
        return true;
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
            getBaseActivity().onDocumentPicked(doc, mModel);
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
        if (mBandController != null) {
            mBandController.handleLayoutChanged();
        }
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

    // Support method to replace getOwner().foo() with something
    // slightly less clumsy like: getOwner().foo().
    private BaseActivity getBaseActivity() {
        return (BaseActivity) getActivity();
    }

    /**
     * Manages the integration between our ActionMode and MultiSelectManager, initiating
     * ActionMode when there is a selection, canceling it when there is no selection,
     * and clearing selection when action mode is explicitly exited by the user.
     */
    private final class SelectionModeListener implements MultiSelectManager.Callback,
            ActionMode.Callback, MenuManager.SelectionDetails {

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
                return mTuner.canSelectType(docMimeType, docFlags);
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
            if (mRestoredSelection != null) {
                // This is a careful little song and dance to avoid haptic feedback
                // when selection has been restored after rotation. We're
                // also responsible for cleaning up restored selection so the
                // object dones't unnecessarily hang around.
                mRestoredSelection = null;
            } else {
                mRecView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }

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
            mMenuManager.updateActionMenu(mMenu, this);
            Menus.disableHiddenItems(mMenu);
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return handleMenuItemClick(item);
        }
    }

    private boolean handleMenuItemClick(MenuItem item) {
        Selection selection = mSelectionManager.getSelection(new Selection());

        switch (item.getItemId()) {
            case R.id.menu_open:
                openDocuments(selection);
                mActionMode.finish();
                return true;

            case R.id.menu_share:
                shareDocuments(selection);
                // TODO: Only finish selection if share action is completed.
                mActionMode.finish();
                return true;

            case R.id.menu_delete:
                // deleteDocuments will end action mode if the documents are deleted.
                // It won't end action mode if user cancels the delete.
                deleteDocuments(selection);
                return true;

            case R.id.menu_copy_to:
                transferDocuments(selection, FileOperationService.OPERATION_COPY);
                // TODO: Only finish selection mode if copy-to is not canceled.
                // Need to plum down into handling the way we do with deleteDocuments.
                mActionMode.finish();
                return true;

            case R.id.menu_move_to:
                // Exit selection mode first, so we avoid deselecting deleted documents.
                mActionMode.finish();
                transferDocuments(selection, FileOperationService.OPERATION_MOVE);
                return true;

            case R.id.menu_cut_to_clipboard:
                cutSelectedToClipboard();
                return true;

            case R.id.menu_copy_to_clipboard:
                copySelectedToClipboard();
                return true;

            case R.id.menu_paste_from_clipboard:
                pasteFromClipboard();
                return true;

            case R.id.menu_select_all:
                selectAllFiles();
                return true;

            case R.id.menu_rename:
                // Exit selection mode first, so we avoid deselecting deleted
                // (renamed) documents.
                mActionMode.finish();
                renameDocuments(selection);
                return true;

            default:
                // See if BaseActivity can handle this particular MenuItem
                if (!getBaseActivity().onOptionsItemSelected(item)) {
                    if (DEBUG) Log.d(TAG, "Unhandled menu item selected: " + item);
                    return false;
                }
                return true;
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

                // For now, we implement this dialog NOT
                // as a fragment (which can survive rotation and have its own state),
                // but as a simple runtime dialog. So rotating a device with an
                // active delete dialog...results in that dialog disappearing.
                // We can do better, but don't have cycles for it now.
                new AlertDialog.Builder(getActivity())
                    .setView(message)
                    .setPositiveButton(
                         android.R.string.ok,
                         new DialogInterface.OnClickListener() {
                            @Override
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

                                UrisSupplier srcs;
                                try {
                                    srcs = UrisSupplier.create(
                                            selected,
                                            mModel::getItemUri,
                                            getContext());
                                } catch(IOException e) {
                                    throw new RuntimeException("Failed to create uri supplier.", e);
                                }

                                FileOperation operation = new FileOperation.Builder()
                                        .withOpType(FileOperationService.OPERATION_DELETE)
                                        .withDestination(getDisplayState().stack)
                                        .withSrcs(srcs)
                                        .withSrcParent(srcParent.derivedUri)
                                        .build();

                                FileOperations.start(getActivity(), operation, mFileOpCallback);
                            }
                        })
                    .setNegativeButton(android.R.string.cancel, null)
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

        UrisSupplier srcs;
        try {
            srcs = UrisSupplier.create(selected, mModel::getItemUri, getContext());
        } catch(IOException e) {
            throw new RuntimeException("Failed to create uri supplier.", e);
        }

        Uri srcParent = getDisplayState().stack.peek().derivedUri;
        mPendingOperation = new FileOperation.Builder()
                .withOpType(mode)
                .withSrcParent(srcParent)
                .withSrcs(srcs)
                .build();

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
                // Determine if there is a directory in the set of documents
                // to be copied? Why? Directory creation isn't supported by some roots
                // (like Downloads). This informs DocumentsActivity (the "picker")
                // to restrict available roots to just those with support.
                intent.putExtra(Shared.EXTRA_DIRECTORY_COPY, hasDirectory(docs));
                intent.putExtra(FileOperationService.EXTRA_OPERATION_TYPE, mode);

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
        return getBaseActivity().getDisplayState();
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

    public void copySelectedToClipboard() {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_COPY_CLIPBOARD);

        Selection selection = mSelectionManager.getSelection(new Selection());
        if (selection.isEmpty()) {
            return;
        }
        mSelectionManager.clearSelection();

        mClipper.clipDocumentsForCopy(mModel::getItemUri, selection);

        Snackbars.showDocumentsClipped(getActivity(), selection.size());
    }

    public void cutSelectedToClipboard() {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_CUT_CLIPBOARD);

        Selection selection = mSelectionManager.getSelection(new Selection());
        if (selection.isEmpty()) {
            return;
        }
        mSelectionManager.clearSelection();

        mClipper.clipDocumentsForCut(mModel::getItemUri, selection, getDisplayState().stack.peek());

        Snackbars.showDocumentsClipped(getActivity(), selection.size());
    }

    public void pasteFromClipboard() {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_PASTE_CLIPBOARD);

        BaseActivity activity = (BaseActivity) getActivity();
        DocumentInfo destination = activity.getCurrentDirectory();
        mClipper.copyFromClipboard(destination, activity.getDisplayState().stack, mFileOpCallback);
        getActivity().invalidateOptionsMenu();
    }

    public void selectAllFiles() {
        Metrics.logUserAction(getContext(), Metrics.USER_ACTION_SELECT_ALL);

        // Exclude disabled files
        List<String> enabled = new ArrayList<String>();
        for (String id : mAdapter.getModelIds()) {
            Cursor cursor = getModel().getItem(id);
            if (cursor == null) {
                Log.w(TAG, "Skipping selection. Can't obtain cursor for modeId: " + id);
                continue;
            }
            String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            if (isDocumentEnabled(docMimeType, docFlags)) {
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

    void dragStarted() {
        // When files are selected for dragging, ActionMode is started. This obscures the breadcrumb
        // with an ActionBar. In order to make drag and drop to the breadcrumb possible, we first
        // end ActionMode so the breadcrumb is visible to the user.
        if (mActionMode != null) {
            mActionMode.finish();
        }
    }

    void dragStopped(boolean result) {
        if (result) {
            mSelectionManager.clearSelection();
        }
    }

    @Override
    public void runOnUiThread(Runnable runnable) {
        getActivity().runOnUiThread(runnable);
    }

    /**
     * {@inheritDoc}
     *
     * In DirectoryFragment, we spring loads the hovered folder.
     */
    @Override
    public void onViewHovered(View view) {
        BaseActivity activity = (BaseActivity) getActivity();
        if (getModelId(view) != null) {
           activity.springOpenDirectory(getDestination(view));
        }

        activity.setRootsDrawerOpen(false);
    }

    boolean handleDropEvent(View v, DragEvent event) {
        BaseActivity activity = (BaseActivity) getActivity();
        activity.setRootsDrawerOpen(false);

        ClipData clipData = event.getClipData();
        assert (clipData != null);

        assert(DocumentClipper.getOpType(clipData) == FileOperationService.OPERATION_COPY);

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

        mClipper.copyFromClipData(dst, getDisplayState().stack, clipData, mFileOpCallback);
        return true;
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

    @Override
    public void setDropTargetHighlight(View v, boolean highlight) {
        // Note: use exact comparison - this code is searching for views which are children of
        // the RecyclerView instance in the UI.
        if (v.getParent() == mRecView) {
            RecyclerView.ViewHolder vh = mRecView.getChildViewHolder(v);
            if (vh instanceof DocumentHolder) {
                ((DocumentHolder) vh).setHighlighted(highlight);
            }
        }
    }

    /**
     * Gets the model ID for a given motion event (using the event position)
     */
    private String getModelId(MotionInputEvent e) {
        RecyclerView.ViewHolder vh = getTarget(e);
        if (vh instanceof DocumentHolder) {
            return ((DocumentHolder) vh).modelId;
        } else {
            return null;
        }
    }

    private @Nullable DocumentHolder getTarget(MotionInputEvent e) {
        View childView = mRecView.findChildViewUnder(e.getX(), e.getY());
        if (childView != null) {
            return (DocumentHolder) mRecView.getChildViewHolder(childView);
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
    protected @Nullable String getModelId(View view) {
        View itemView = mRecView.findContainingItemView(view);
        if (itemView != null) {
            RecyclerView.ViewHolder vh = mRecView.getChildViewHolder(itemView);
            if (vh instanceof DocumentHolder) {
                return ((DocumentHolder) vh).modelId;
            }
        }
        return null;
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
                getBaseActivity().notifyDirectoryLoaded(
                    model.doc != null ? model.doc.derivedUri : null);
            }
        }

        @Override
        public void onModelUpdateFailed(Exception e) {
            showQueryError();
        }
    }

    private Drawable getDragIcon(Selection selection) {
        if (selection.size() == 1) {
            DocumentInfo doc = getSingleSelectedDocument(selection);
            return mIconHelper.getDocumentIcon(getContext(), doc);
        }
        return getContext().getDrawable(com.android.internal.R.drawable.ic_doc_generic);
    }

    private String getDragTitle(Selection selection) {
        assert (!selection.isEmpty());
        if (selection.size() == 1) {
            DocumentInfo doc = getSingleSelectedDocument(selection);
            return doc.displayName;
        }

        return Shared.getQuantityString(getContext(), R.plurals.elements_dragged, selection.size());
    }

    private DocumentInfo getSingleSelectedDocument(Selection selection) {
        assert (selection.size() == 1);
        final List<DocumentInfo> docs = mModel.getDocuments(mSelectionManager.getSelection());
        assert (docs.size() == 1);
        return docs.get(0);
    }

    private DragStartHelper.OnDragStartListener mOnDragStartListener =
            new DragStartHelper.OnDragStartListener() {
                @Override
                public boolean onDragStart(View v, DragStartHelper helper) {
                    Selection selection = mSelectionManager.getSelection();

                    if (v == null) {
                        Log.d(TAG, "Ignoring drag event, null view");
                        return false;
                    }
                    if (!isSelected(getModelId(v))) {
                        Log.d(TAG, "Ignoring drag event, unselected view.");
                        return false;
                    }

                    // NOTE: Preparation of the ClipData object can require a lot of time
                    // and ideally should be done in the background. Unfortunately
                    // the current code layout and framework assumptions don't support
                    // this. So for now, we could end up doing a bunch of i/o on main thread.
                    v.startDragAndDrop(
                            mClipper.getClipDataForDocuments(
                                    mModel::getItemUri,
                                    selection,
                                    FileOperationService.OPERATION_COPY),
                            new DragShadowBuilder(
                                    getActivity(),
                                    getDragTitle(selection),
                                    getDragIcon(selection)),
                            getDisplayState().stack.peek(),
                            View.DRAG_FLAG_GLOBAL
                                    | View.DRAG_FLAG_GLOBAL_URI_READ
                                    | View.DRAG_FLAG_GLOBAL_URI_WRITE);

                    return true;
                }
            };


    private DragStartHelper mDragHelper = new DragStartHelper(null, mOnDragStartListener);

    private View.OnLongClickListener onLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            return mDragHelper.onLongClick(v);
        }
    };

    private boolean canSelect(String modelId) {

        // TODO: Combine this method with onBeforeItemStateChange, as both of them are almost
        // the same, and responsible for the same thing (whether to select or not).
        final Cursor cursor = mModel.getItem(modelId);
        if (cursor == null) {
            Log.w(TAG, "Couldn't obtain cursor for modelId: " + modelId);
            return false;
        }

        final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
        return mTuner.canSelectType(docMimeType, docFlags);
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

        if (mRestoredSelection != null) {
            mSelectionManager.restoreSelection(mRestoredSelection);
            // Note, we'll take care of cleaning up retained selection
            // in the selection handler where we already have some
            // specialized code to handle when selection was restored.
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
