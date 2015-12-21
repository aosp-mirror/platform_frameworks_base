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
import static com.android.documentsui.State.MODE_UNKNOWN;
import static com.android.documentsui.State.SORT_ORDER_UNKNOWN;
import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorString;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;
import static com.google.common.base.Preconditions.checkArgument;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
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
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.LayoutManager;
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
import com.android.documentsui.CopyService;
import com.android.documentsui.DirectoryLoader;
import com.android.documentsui.DirectoryResult;
import com.android.documentsui.DocumentClipper;
import com.android.documentsui.DocumentsActivity;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.Events;
import com.android.documentsui.Menus;
import com.android.documentsui.MessageBar;
import com.android.documentsui.MimePredicate;
import com.android.documentsui.R;
import com.android.documentsui.RecentLoader;
import com.android.documentsui.RecentsProvider;
import com.android.documentsui.RecentsProvider.StateColumns;
import com.android.documentsui.RootsCache;
import com.android.documentsui.Shared;
import com.android.documentsui.Snackbars;
import com.android.documentsui.State;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends Fragment {

    public static final int TYPE_NORMAL = 1;
    public static final int TYPE_SEARCH = 2;
    public static final int TYPE_RECENT_OPEN = 3;

    public static final int ANIM_NONE = 1;
    public static final int ANIM_SIDE = 2;
    public static final int ANIM_DOWN = 3;
    public static final int ANIM_UP = 4;

    public static final int REQUEST_COPY_DESTINATION = 1;

    private static final String TAG = "DirectoryFragment";

    private static final int LOADER_ID = 42;
    private static final boolean DEBUG_ENABLE_DND = true;

    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_ROOT = "root";
    private static final String EXTRA_DOC = "doc";
    private static final String EXTRA_QUERY = "query";
    private static final String EXTRA_IGNORE_STATE = "ignoreState";

    private Model mModel;
    private MultiSelectManager mSelectionManager;
    private Model.UpdateListener mModelUpdateListener = new ModelUpdateListener();
    private ItemClickListener mItemClickListener = new ItemClickListener();

    private IconHelper mIconHelper;

    private View mEmptyView;
    private RecyclerView mRecView;

    private int mType = TYPE_NORMAL;
    private String mStateKey;

    private int mLastMode = MODE_UNKNOWN;
    private int mLastSortOrder = SORT_ORDER_UNKNOWN;
    private boolean mLastShowSize;
    private DocumentsAdapter mAdapter;
    private LoaderCallbacks<DirectoryResult> mCallbacks;
    private FragmentTuner mTuner;
    private DocumentClipper mClipper;
    // These are lazily initialized.
    private LinearLayoutManager mListLayout;
    private GridLayoutManager mGridLayout;
    private int mColumnCount = 1;  // This will get updated when layout changes.

    private MessageBar mMessageBar;
    private View mProgressBar;

    public static void showNormal(FragmentManager fm, RootInfo root, DocumentInfo doc, int anim) {
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
            case ANIM_DOWN:
                args.putBoolean(EXTRA_IGNORE_STATE, true);
                ft.setCustomAnimations(R.animator.dir_down, R.animator.dir_frozen);
                break;
            case ANIM_UP:
                ft.setCustomAnimations(R.animator.dir_frozen, R.animator.dir_up);
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

        // TODO: Rather than update columns on layout changes, push this
        // code (or something like it) into GridLayoutManager.
        mRecView.addOnLayoutChangeListener(
                new View.OnLayoutChangeListener() {

                    @Override
                    public void onLayoutChange(
                            View v, int left, int top, int right, int bottom, int oldLeft,
                            int oldTop, int oldRight, int oldBottom) {
                        mColumnCount = calculateColumnCount();
                        if (mGridLayout != null) {
                            mGridLayout.setSpanCount(mColumnCount);
                        }
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

        // Clear any outstanding selection
        mSelectionManager.clearSelection();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Context context = getActivity();
        final State state = getDisplayState();

        final RootInfo root = getArguments().getParcelable(EXTRA_ROOT);
        final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);

        mAdapter = new DocumentsAdapter();
        mRecView.setAdapter(mAdapter);

        GestureDetector.SimpleOnGestureListener listener =
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        return DirectoryFragment.this.onSingleTapUp(e);
                    }
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        Log.d(TAG, "Handling double tap.");
                        return DirectoryFragment.this.onDoubleTap(e);
                    }
                };

        final GestureDetector detector = new GestureDetector(this.getContext(), listener);
        detector.setOnDoubleTapListener(listener);

        mRecView.addOnItemTouchListener(
                new OnItemTouchListener() {
                    @Override
                    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                        detector.onTouchEvent(e);
                        return false;
                    }

                    @Override
                    public void onTouchEvent(RecyclerView rv, MotionEvent e) {}

                    @Override
                    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
                });

        // TODO: instead of inserting the view into the constructor, extract listener-creation code
        // and set the listener on the view after the fact.  Then the view doesn't need to be passed
        // into the selection manager.
        mSelectionManager = new MultiSelectManager(
                mRecView,
                state.allowMultiple
                    ? MultiSelectManager.MODE_MULTIPLE
                    : MultiSelectManager.MODE_SINGLE);
        mSelectionManager.addCallback(new SelectionModeListener());

        mModel = new Model(context, mAdapter);
        mModel.addUpdateListener(mAdapter);
        mModel.addUpdateListener(mModelUpdateListener);

        mType = getArguments().getInt(EXTRA_TYPE);
        mStateKey = buildStateKey(root, doc);

        mTuner = FragmentTuner.pick(state);
        mClipper = new DocumentClipper(context);

        mIconHelper = new IconHelper(context, state.derivedMode);

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

                // Push latest state up to UI
                // TODO: if mode change was racing with us, don't overwrite it
                if (result.mode != MODE_UNKNOWN) {
                    state.derivedMode = result.mode;
                }
                state.derivedSortOrder = result.sortOrder;
                ((BaseActivity) context).onStateChanged();

                updateDisplayState();

                // When launched into empty recents, show drawer
                if (mType == TYPE_RECENT_OPEN && mModel.isEmpty() && !state.stackTouched &&
                        context instanceof DocumentsActivity) {
                    ((DocumentsActivity) context).setRootsDrawerOpen(true);
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
            }

            @Override
            public void onLoaderReset(Loader<DirectoryResult> loader) {
                mModel.update(null);
            }
        };

        // Kick off loader at least once
        getLoaderManager().restartLoader(LOADER_ID, null, mCallbacks);

        updateDisplayState();
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

        CopyService.start(getActivity(), getDisplayState().selectedDocumentsForCopy,
                (DocumentStack) data.getParcelableExtra(Shared.EXTRA_STACK),
                data.getIntExtra(CopyService.EXTRA_TRANSFER_MODE, CopyService.TRANSFER_MODE_COPY));
    }

    private boolean onSingleTapUp(MotionEvent e) {
        // Only respond to touch events.  Single-click mouse events are selection events and are
        // handled by the selection manager.  Tap events that occur while the selection manager is
        // active are also selection events.
        if (Events.isTouchEvent(e) && !mSelectionManager.hasSelection()) {
            String id = getModelId(e);
            if (id != null) {
                return handleViewItem(id);
            }
        }
        return false;
    }

    protected boolean onDoubleTap(MotionEvent e) {
        if (Events.isMouseEvent(e)) {
            Log.d(TAG, "Handling double tap from mouse.");
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

    @Override
    public void onResume() {
        super.onResume();
        updateDisplayState();
    }

    public void onDisplayStateChanged() {
        updateDisplayState();
    }

    public void onUserSortOrderChanged() {
        // Sort order change always triggers reload; we'll trigger state change
        // on the flip side.
        getLoaderManager().restartLoader(LOADER_ID, null, mCallbacks);
    }

    public void onUserModeChanged() {
        final ContentResolver resolver = getActivity().getContentResolver();
        final State state = getDisplayState();

        final RootInfo root = getArguments().getParcelable(EXTRA_ROOT);
        final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);

        if (root != null && doc != null) {
            final Uri stateUri = RecentsProvider.buildState(
                    root.authority, root.rootId, doc.documentId);
            final ContentValues values = new ContentValues();
            values.put(StateColumns.MODE, state.userMode);

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    resolver.insert(stateUri, values);
                    return null;
                }
            }.execute();
        }

        // Mode change is just visual change; no need to kick loader, and
        // deliver change event immediately.
        state.derivedMode = state.userMode;
        ((BaseActivity) getActivity()).onStateChanged();

        updateDisplayState();
    }

    private void updateDisplayState() {
        final State state = getDisplayState();

        if (mLastMode == state.derivedMode && mLastShowSize == state.showSize) return;
        mLastMode = state.derivedMode;
        mLastShowSize = state.showSize;

        updateLayout(state.derivedMode);

        mRecView.setAdapter(mAdapter);
    }

    /**
     * Returns a {@code LayoutManager} for {@code mode}, lazily initializing
     * classes as needed.
     */
    private void updateLayout(int mode) {
        final LayoutManager layout;
        switch (mode) {
            case MODE_GRID:
                if (mGridLayout == null) {
                    mGridLayout = new GridLayoutManager(getContext(), mColumnCount);
                    mGridLayout.setSpanSizeLookup(mAdapter.createSpanSizeLookup());
                }
                layout = mGridLayout;
                break;
            case MODE_LIST:
                if (mListLayout == null) {
                    mListLayout = new LinearLayoutManager(getContext());
                }
                layout = mListLayout;
                break;
            case MODE_UNKNOWN:
            default:
                throw new IllegalArgumentException("Unsupported layout mode: " + mode);
        }

        mRecView.setLayoutManager(layout);
        // TODO: Once b/23691541 is resolved, use a listener within MultiSelectManager instead of
        // imperatively calling this function.
        mSelectionManager.handleLayoutChanged();
        // setting layout manager automatically invalidates existing ViewHolders.
        mIconHelper.setMode(mode);
    }

    private int calculateColumnCount() {
        int cellWidth = getResources().getDimensionPixelSize(R.dimen.grid_width);
        int cellMargin = 2 * getResources().getDimensionPixelSize(R.dimen.grid_item_margin);
        int viewPadding = mRecView.getPaddingLeft() + mRecView.getPaddingRight();

        checkState(mRecView.getWidth() > 0);
        int columnCount = Math.max(1,
                (mRecView.getWidth() - viewPadding) / (cellWidth + cellMargin));

        return columnCount;
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

            final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            if ((docFlags & Document.FLAG_SUPPORTS_DELETE) == 0) {
                mNoDeleteCount += selected ? 1 : -1;
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

        private void updateActionMenu() {
            checkNotNull(mMenu);
            // Delegate update logic to our owning action, since specialized logic is desired.
            mTuner.updateActionMenu(mMenu, mType, mNoDeleteCount == 0);
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
                    transferDocuments(selection, CopyService.TRANSFER_MODE_COPY);
                    mode.finish();
                    return true;

                case R.id.menu_move_to:
                    // Exit selection mode first, so we avoid deselecting deleted documents.
                    mode.finish();
                    transferDocuments(selection, CopyService.TRANSFER_MODE_MOVE);
                    return true;

                case R.id.menu_copy_to_clipboard:
                    if (!selection.isEmpty()) {
                        copySelectionToClipboard(selection);
                    }
                    return true;

                case R.id.menu_select_all:
                    selectAllFiles();
                    return true;

                default:
                    if (DEBUG) Log.d(TAG, "Unhandled menu item selected: " + item);
                    return false;
            }
        }
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
        Context context = getActivity();
        String message = Shared.getQuantityString(context, R.plurals.deleting, selected.size());

        // Hide the files in the UI.
        final SparseArray<String> toDelete = mAdapter.hide(selected.getAll());

        // Show a snackbar informing the user that files will be deleted, and give them an option to
        // cancel.
        final Activity activity = getActivity();
        Snackbars.makeSnackbar(activity, message, Snackbar.LENGTH_LONG)
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
                                    mAdapter.unhide(toDelete);
                                } else {
                                    // Actually kick off the delete.
                                    mModel.delete(
                                            selected,
                                            new Model.DeletionListener() {
                                                @Override
                                                  public void onError() {
                                                      Snackbars.makeSnackbar(
                                                              activity,
                                                              R.string.toast_failed_delete,
                                                              Snackbar.LENGTH_LONG)
                                                              .show();

                                                  }
                                            });
                                }
                            }
                        })
                .show();
    }

    private void transferDocuments(final Selection selected, final int mode) {
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
                intent.putExtra(CopyService.EXTRA_TRANSFER_MODE, mode);
                startActivityForResult(intent, REQUEST_COPY_DESTINATION);
            }
        }.execute(selected);
    }

    private State getDisplayState() {
        return ((BaseActivity) getActivity()).getDisplayState();
    }

    void showEmptyView() {
        mEmptyView.setVisibility(View.VISIBLE);
        mRecView.setVisibility(View.GONE);
        TextView msg = (TextView) mEmptyView.findViewById(R.id.message);
        msg.setText(R.string.empty);
        // No retry button for the empty view.
        mEmptyView.findViewById(R.id.button_retry).setVisibility(View.GONE);
    }

    void showErrorView() {
        mEmptyView.setVisibility(View.VISIBLE);
        mRecView.setVisibility(View.GONE);
        TextView msg = (TextView) mEmptyView.findViewById(R.id.message);
        msg.setText(R.string.query_error);
        // TODO: Enable this once the retry button does something.
        mEmptyView.findViewById(R.id.button_retry).setVisibility(View.GONE);
    }

    void showRecyclerView() {
        mEmptyView.setVisibility(View.GONE);
        mRecView.setVisibility(View.VISIBLE);
    }

    final class DocumentsAdapter
            extends RecyclerView.Adapter<DocumentHolder>
            implements Model.UpdateListener {

        private static final String TAG = "DocumentsAdapter";
        public static final int ITEM_TYPE_LAYOUT_DIVIDER = 0;
        public static final int ITEM_TYPE_DOCUMENT = 1;
        public static final int ITEM_TYPE_DIRECTORY = 2;

        /**
         * An ordered list of model IDs. This is the data structure that determines what shows up in
         * the UI, and where.
         */
        private List<String> mModelIds = new ArrayList<>();

        // The list is divided into two segments - directories, and everything else. Record the
        // position where the transition happens.
        private int mDividerPosition;

        public GridLayoutManager.SpanSizeLookup createSpanSizeLookup() {
            return new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    // Make layout whitespace span the grid. This has the effect of breaking
                    // grid rows whenever layout whitespace is encountered.
                    if (getItemViewType(position) == ITEM_TYPE_LAYOUT_DIVIDER) {
                        return mColumnCount;
                    } else {
                        return 1;
                    }
                }
            };
        }

        @Override
        public DocumentHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == ITEM_TYPE_LAYOUT_DIVIDER) {
                return new EmptyDocumentHolder(getContext());
            };

            DocumentHolder holder = null;
            final State state = getDisplayState();
            switch (state.derivedMode) {
                case MODE_GRID:
                    holder = new GridDocumentHolder(getContext(), parent, mIconHelper, viewType);
                    break;
                case MODE_LIST:
                    holder = new ListDocumentHolder(getContext(), parent, mIconHelper);
                    break;
                case MODE_UNKNOWN:
                default:
                    throw new IllegalStateException("Unsupported layout mode.");
            }

            holder.addClickListener(mItemClickListener);
            holder.addOnKeyListener(mSelectionManager);
            return holder;
        }

        /**
         * Deal with selection changed events by using a custom ItemAnimator that just changes the
         * background color.  This works around focus issues (otherwise items lose focus when their
         * selection state changes) but also optimizes change animations for selection.
         */
        @Override
        public void onBindViewHolder(DocumentHolder holder, int position, List<Object> payload) {
            if (holder.getItemViewType() == ITEM_TYPE_LAYOUT_DIVIDER) {
                // Whitespace items are hidden elements with no data to bind.
                return;
            }

            final View itemView = holder.itemView;

            if (payload.contains(MultiSelectManager.SELECTION_CHANGED_MARKER)) {
                final boolean selected = isSelected(mModelIds.get(position));
                itemView.setActivated(selected);
                return;
            } else {
                onBindViewHolder(holder, position);
            }
        }

        @Override
        public void onBindViewHolder(DocumentHolder holder, int position) {
            if (holder.getItemViewType() == ITEM_TYPE_LAYOUT_DIVIDER) {
                // Whitespace items are hidden elements with no data to bind.
                return;
            }

            String modelId = mModelIds.get(position);
            Cursor cursor = mModel.getItem(modelId);
            holder.bind(cursor, modelId, getDisplayState());

            final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);

            holder.setSelected(isSelected(modelId));
            holder.setEnabled(mTuner.isDocumentEnabled(docMimeType, docFlags));
            if (DEBUG_ENABLE_DND) {
                setupDragAndDropOnDocumentView(holder.itemView, cursor);
            }
        }

        @Override
        public int getItemCount() {
            return mModelIds.size();
        }

        @Override
        public void onModelUpdate(Model model) {
            mModelIds = Lists.newArrayList(model.getModelIds());
            mDividerPosition = 0;

            // Walk down the list of IDs till we encounter something that's not a directory, and
            // insert a whitespace element - this introduces a visual break in the grid between
            // folders and documents.
            // TODO: This code makes assumptions about the model, namely, that it performs a
            // bucketed sort where directories will always be ordered before other files.  CBB.
            for (int i = 0; i < mModelIds.size(); ++i) {
                final String mimeType = getCursorString(
                        model.getItem(mModelIds.get(i)), Document.COLUMN_MIME_TYPE);
                if (!Document.MIME_TYPE_DIR.equals(mimeType)) {
                    mDividerPosition = i;
                    break;
                }
            }

            mModelIds.add(mDividerPosition, null);
        }

        @Override
        public void onModelUpdateFailed(Exception e) {
            if (DEBUG) Log.d(TAG, "onModelUpdateFailed called ");
            mModelIds.clear();
        }

        /**
         * @return The model ID of the item at the given adapter position.
         */
        public String getModelId(int adapterPosition) {
            return mModelIds.get(adapterPosition);
        }

        /**
         * Hides a set of items from the associated RecyclerView.
         *
         * @param ids The Model IDs of the items to hide.
         * @return A SparseArray that maps the hidden IDs to their old positions. This can be used
         *         to {@link #unhide} the items if necessary.
         */
        public SparseArray<String> hide(String... ids) {
            Set<String> toHide = Sets.newHashSet(ids);

            // Proceed backwards through the list of items, because each removal causes the
            // positions of all subsequent items to change.
            SparseArray<String> hiddenItems = new SparseArray<>();
            for (int i = mModelIds.size() - 1; i >= 0; --i) {
                String id = mModelIds.get(i);
                if (toHide.contains(id)) {
                    hiddenItems.put(i, mModelIds.remove(i));
                    notifyItemRemoved(i);
                }
            }

            return hiddenItems;
        }

        /**
         * Unhides a set of previously hidden items.
         *
         * @param ids A sparse array of IDs from a previous call to {@link #hide}.
         */
        public void unhide(SparseArray<String> ids) {
            // Proceed backwards through the list of items, because each addition causes the
            // positions of all subsequent items to change.
            for (int i = ids.size() - 1; i >= 0; --i) {
                int pos = ids.keyAt(i);
                String id = ids.get(pos);
                mModelIds.add(pos, id);
                notifyItemInserted(pos);
            }
        }

        /**
         * Returns a list of model IDs of items currently in the adapter. Excludes items that are
         * currently hidden (see {@link #hide(String...)}).
         *
         * @return A list of Model IDs.
         */
        public List<String> getModelIds() {
            return mModelIds;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < mDividerPosition) {
                return ITEM_TYPE_DIRECTORY;
            } else if (position == mDividerPosition) {
                return ITEM_TYPE_LAYOUT_DIVIDER;
            } else {
                return ITEM_TYPE_DOCUMENT;
            }
        }

        /**
         * Triggers item-change notifications by stable ID. Passing an unrecognized ID will result
         * in a warning in logcat, but no other error.
         *
         * @param id
         * @param selectionChangedMarker
         */
        public void notifyItemChanged(String id, String selectionChangedMarker) {
            int position = mModelIds.indexOf(id);

            if (position >= 0) {
                notifyItemChanged(position, selectionChangedMarker);
            } else {
                Log.w(TAG, "Item change notification received for unknown item: " + id);
            }
        }
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

        CopyService.start(getActivity(), docs, tmpStack, CopyService.TRANSFER_MODE_COPY);
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

        // Temporary: attaching the listener to the title only.
        // Attaching to the entire item conflicts with the item long click handler responsible
        // for item selection.
        final View title = view.findViewById(android.R.id.title);
        title.setOnLongClickListener(mLongClickListener);
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

    private View.OnLongClickListener mLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            final List<DocumentInfo> docs = getDraggableDocuments(v);
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
    };

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

    boolean isSelected(String modelId) {
        return mSelectionManager.getSelection().contains(modelId);
    }

    private class ItemClickListener implements DocumentHolder.ClickListener {
        @Override
        public void onClick(DocumentHolder doc) {
            if (mSelectionManager.hasSelection()) {
                mSelectionManager.toggleSelection(doc.modelId);
                mSelectionManager.setSelectionRangeBegin(doc.getAdapterPosition());
            } else {
                handleViewItem(doc.modelId);
            }
        }
    }

    private class ModelUpdateListener implements Model.UpdateListener {
        @Override
        public void onModelUpdate(Model model) {
            if (model.info != null || model.error != null) {
                mMessageBar.setInfo(model.info);
                mMessageBar.setError(model.error);
                mMessageBar.show();
            }

            mProgressBar.setVisibility(model.isLoading() ? View.VISIBLE : View.GONE);

            if (model.isEmpty()) {
                showEmptyView();
            } else {
                showRecyclerView();
                mAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onModelUpdateFailed(Exception e) {
            showErrorView();
        }
    }
}
