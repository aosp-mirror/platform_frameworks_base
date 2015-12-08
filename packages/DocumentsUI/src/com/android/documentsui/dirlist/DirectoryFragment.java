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
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
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
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.design.widget.Snackbar;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.LayoutManager;
import android.support.v7.widget.RecyclerView.OnItemTouchListener;
import android.support.v7.widget.RecyclerView.RecyclerListener;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.text.format.Time;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
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
import com.android.documentsui.BaseActivity.DocumentContext;
import com.android.documentsui.CopyService;
import com.android.documentsui.DirectoryLoader;
import com.android.documentsui.DirectoryResult;
import com.android.documentsui.DocumentClipper;
import com.android.documentsui.DocumentsActivity;
import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.Events;
import com.android.documentsui.IconUtils;
import com.android.documentsui.Menus;
import com.android.documentsui.MessageBar;
import com.android.documentsui.MimePredicate;
import com.android.documentsui.ProviderExecutor;
import com.android.documentsui.ProviderExecutor.Preemptable;
import com.android.documentsui.R;
import com.android.documentsui.RecentLoader;
import com.android.documentsui.RecentsProvider;
import com.android.documentsui.RecentsProvider.StateColumns;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.RootsCache;
import com.android.documentsui.Shared;
import com.android.documentsui.Shared;
import com.android.documentsui.Snackbars;
import com.android.documentsui.State;
import com.android.documentsui.ThumbnailCache;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;
import com.android.internal.annotations.GuardedBy;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends Fragment {

    public static final String TAG = "DirectoryFragment";

    public static final int TYPE_NORMAL = 1;
    public static final int TYPE_SEARCH = 2;
    public static final int TYPE_RECENT_OPEN = 3;

    public static final int ANIM_NONE = 1;
    public static final int ANIM_SIDE = 2;
    public static final int ANIM_DOWN = 3;
    public static final int ANIM_UP = 4;

    public static final int REQUEST_COPY_DESTINATION = 1;

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

    private View mEmptyView;
    private RecyclerView mRecView;

    private int mType = TYPE_NORMAL;
    private String mStateKey;

    private int mLastMode = MODE_UNKNOWN;
    private int mLastSortOrder = SORT_ORDER_UNKNOWN;
    private boolean mLastShowSize;
    private boolean mHideGridTitles;
    private boolean mSvelteRecents;
    private Point mThumbSize;
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

    private int mSelectedItemColor;
    private int mDefaultItemColor;

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

        mAdapter = new DocumentsAdapter(context);
        mRecView.setAdapter(mAdapter);

        mDefaultItemColor = context.getResources().getColor(android.R.color.transparent);
        // Get the accent color.
        TypedValue selColor = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, selColor, true);
        // Set the opacity to 10%.
        mSelectedItemColor = (selColor.data & 0x00ffffff) | 0x16000000;

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
        mModel.addUpdateListener(mModelUpdateListener);

        mType = getArguments().getInt(EXTRA_TYPE);
        mStateKey = buildStateKey(root, doc);

        mTuner = FragmentTuner.pick(state);
        mClipper = new DocumentClipper(context);

        if (mType == TYPE_RECENT_OPEN) {
            // Hide titles when showing recents for picking images/videos
            mHideGridTitles = MimePredicate.mimeMatches(
                    MimePredicate.VISUAL_MIMES, state.acceptMimes);
        } else {
            mHideGridTitles = (doc != null) && doc.isGridTitlesHidden();
        }

        final ActivityManager am = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        mSvelteRecents = am.isLowRamDevice() && (mType == TYPE_RECENT_OPEN);

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

    private int getEventAdapterPosition(MotionEvent e) {
        View view = mRecView.findChildViewUnder(e.getX(), e.getY());
        return view != null ? mRecView.getChildAdapterPosition(view) : RecyclerView.NO_POSITION;
    }

    private boolean onSingleTapUp(MotionEvent e) {
        // Only respond to touch events.  Single-click mouse events are selection events and are
        // handled by the selection manager.  Tap events that occur while the selection manager is
        // active are also selection events.
        if (Events.isTouchEvent(e) && !mSelectionManager.hasSelection()) {
            int position = getEventAdapterPosition(e);
            if (position != RecyclerView.NO_POSITION) {
                return handleViewItem(position);
            }
        }
        return false;
    }

    protected boolean onDoubleTap(MotionEvent e) {
        if (Events.isMouseEvent(e)) {
            Log.d(TAG, "Handling double tap from mouse.");
            int position = getEventAdapterPosition(e);
            if (position != RecyclerView.NO_POSITION) {
                return handleViewItem(position);
            }
        }
        return false;
    }

    private boolean handleViewItem(int position) {
        final Cursor cursor = mModel.getItem(position);
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
        final int thumbSize;

        final LayoutManager layout;
        switch (mode) {
            case MODE_GRID:
                thumbSize = getResources().getDimensionPixelSize(R.dimen.grid_width);
                if (mGridLayout == null) {
                    mGridLayout = new GridLayoutManager(getContext(), mColumnCount );
                }
                layout = mGridLayout;
                break;
            case MODE_LIST:
                thumbSize = getResources().getDimensionPixelSize(R.dimen.icon_size);
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
        mThumbSize = new Point(thumbSize, thumbSize);
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
        public boolean onBeforeItemStateChange(int position, boolean selected) {
            if (selected) {
                final Cursor cursor = mModel.getItem(position);
                checkNotNull(cursor, "Cursor cannot be null.");
                final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
                final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
                return mTuner.canSelectType(docMimeType, docFlags);
            }
            return true;
        }

        @Override
        public void onItemStateChanged(int position, boolean selected) {
            final Cursor cursor = mModel.getItem(position);
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

    private static void cancelThumbnailTask(View view) {
        final ImageView iconThumb = (ImageView) view.findViewById(R.id.icon_thumb);
        if (iconThumb != null) {
            final ThumbnailAsyncTask oldTask = (ThumbnailAsyncTask) iconThumb.getTag();
            if (oldTask != null) {
                oldTask.preempt();
                iconThumb.setTag(null);
            }
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

        mModel.markForDeletion(selected);

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
                                    mModel.undoDeletion();
                                } else {
                                    mModel.finalizeDeletion(
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

    // Provide a reference to the views for each data item
    // Complex data items may need more than one view per item, and
    // you provide access to all the views for a data item in a view holder
    private final class DocumentHolder
            extends RecyclerView.ViewHolder
            implements View.OnKeyListener
    {
        public String docId;  // The stable document id.
        private ClickListener mClickListener;
        private View.OnKeyListener mKeyListener;

        public DocumentHolder(View view) {
            super(view);
            view.setOnKeyListener(this);
        }

        public void setSelected(boolean selected) {
            itemView.setActivated(selected);
        }

        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            // Intercept enter key-up events, and treat them as clicks.  Forward other events.
            if (event.getAction() == KeyEvent.ACTION_UP &&
                    keyCode == KeyEvent.KEYCODE_ENTER) {
                if (mClickListener != null) {
                    mClickListener.onClick(this);
                }
                return true;
            } else if (mKeyListener != null) {
                return mKeyListener.onKey(v, keyCode, event);
            }
            return false;
        }

        public void addClickListener(ClickListener listener) {
            // Just handle one for now; switch to a list if necessary.
            checkState(mClickListener == null);
            mClickListener = listener;
        }

        public void addOnKeyListener(View.OnKeyListener listener) {
            // Just handle one for now; switch to a list if necessary.
            checkState(mKeyListener == null);
            mKeyListener = listener;
        }
    }

    interface ClickListener {
        public void onClick(DocumentHolder doc);
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

    private final class DocumentsAdapter extends RecyclerView.Adapter<DocumentHolder> {

        private final Context mContext;
        private final LayoutInflater mInflater;

        public DocumentsAdapter(Context context) {
            mContext = context;
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public DocumentHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final State state = getDisplayState();
            final LayoutInflater inflater = LayoutInflater.from(getContext());
            View item = null;
            switch (state.derivedMode) {
                case MODE_GRID:
                    item = inflater.inflate(R.layout.item_doc_grid, parent, false);
                    break;
                case MODE_LIST:
                    item = inflater.inflate(R.layout.item_doc_list, parent, false);
                    break;
                case MODE_UNKNOWN:
                default:
                    throw new IllegalStateException("Unsupported layout mode.");
            }

            DocumentHolder holder = new DocumentHolder(item);
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
            final View itemView = holder.itemView;

            if (payload.contains(MultiSelectManager.SELECTION_CHANGED_MARKER)) {
                final boolean selected = isSelected(position);
                itemView.setActivated(selected);
                return;
            } else {
                onBindViewHolder(holder, position);
            }
        }

        @Override
        public void onBindViewHolder(DocumentHolder holder, int position) {

            final Context context = getContext();
            final State state = getDisplayState();
            final RootsCache roots = DocumentsApplication.getRootsCache(context);
            final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(
                    context, mThumbSize);

            final Cursor cursor = mModel.getItem(position);
            checkNotNull(cursor, "Cursor cannot be null.");

            final String docAuthority = getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY);
            final String docRootId = getCursorString(cursor, RootCursorWrapper.COLUMN_ROOT_ID);
            final String docId = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
            final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
            final String docDisplayName = getCursorString(cursor, Document.COLUMN_DISPLAY_NAME);
            final long docLastModified = getCursorLong(cursor, Document.COLUMN_LAST_MODIFIED);
            final int docIcon = getCursorInt(cursor, Document.COLUMN_ICON);
            final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            final String docSummary = getCursorString(cursor, Document.COLUMN_SUMMARY);
            final long docSize = getCursorLong(cursor, Document.COLUMN_SIZE);

            holder.docId = docId;
            final View itemView = holder.itemView;

            holder.setSelected(isSelected(position));

            final ImageView iconMime = (ImageView) itemView.findViewById(R.id.icon_mime);
            final ImageView iconThumb = (ImageView) itemView.findViewById(R.id.icon_thumb);
            final TextView title = (TextView) itemView.findViewById(android.R.id.title);
            final ImageView icon1 = (ImageView) itemView.findViewById(android.R.id.icon1);
            final TextView summary = (TextView) itemView.findViewById(android.R.id.summary);
            final TextView date = (TextView) itemView.findViewById(R.id.date);
            final TextView size = (TextView) itemView.findViewById(R.id.size);

            final ThumbnailAsyncTask oldTask = (ThumbnailAsyncTask) iconThumb.getTag();
            if (oldTask != null) {
                oldTask.preempt();
                iconThumb.setTag(null);
            }

            iconMime.animate().cancel();
            iconThumb.animate().cancel();

            final boolean supportsThumbnail = (docFlags & Document.FLAG_SUPPORTS_THUMBNAIL) != 0;
            final boolean allowThumbnail = (state.derivedMode == MODE_GRID)
                    || MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, docMimeType);
            final boolean showThumbnail = supportsThumbnail && allowThumbnail && !mSvelteRecents;

            final boolean enabled = mTuner.isDocumentEnabled(docMimeType, docFlags);
            final float iconAlpha = (state.derivedMode == MODE_LIST && !enabled) ? 0.5f : 1f;

            boolean cacheHit = false;
            if (showThumbnail) {
                final Uri uri = DocumentsContract.buildDocumentUri(docAuthority, docId);
                final Bitmap cachedResult = thumbs.get(uri);
                if (cachedResult != null) {
                    iconThumb.setImageBitmap(cachedResult);
                    cacheHit = true;
                } else {
                    iconThumb.setImageDrawable(null);
                    // TODO: Hang this off DocumentHolder?
                    final ThumbnailAsyncTask task = new ThumbnailAsyncTask(
                            uri, iconMime, iconThumb, mThumbSize, iconAlpha);
                    iconThumb.setTag(task);
                    ProviderExecutor.forAuthority(docAuthority).execute(task);
                }
            }

            // Always throw MIME icon into place, even when a thumbnail is being
            // loaded in background.
            if (cacheHit) {
                iconMime.setAlpha(0f);
                iconMime.setImageDrawable(null);
                iconThumb.setAlpha(1f);
            } else {
                iconMime.setAlpha(1f);
                iconThumb.setAlpha(0f);
                iconThumb.setImageDrawable(null);
                iconMime.setImageDrawable(
                        getDocumentIcon(mContext, docAuthority, docId, docMimeType, docIcon, state));
            }

            if ((state.derivedMode == MODE_GRID) && mHideGridTitles) {
                title.setVisibility(View.GONE);
            } else {
                title.setText(docDisplayName);
                title.setVisibility(View.VISIBLE);
            }

            Drawable iconDrawable = null;
            if (mType == TYPE_RECENT_OPEN) {
                // We've already had to enumerate roots before any results can
                // be shown, so this will never block.
                final RootInfo root = roots.getRootBlocking(docAuthority, docRootId);
                iconDrawable = root.loadIcon(mContext);

                if (summary != null) {
                    final boolean alwaysShowSummary = getResources()
                            .getBoolean(R.bool.always_show_summary);
                    if (alwaysShowSummary) {
                        summary.setText(root.getDirectoryString());
                        summary.setVisibility(View.VISIBLE);
                    } else {
                        if (iconDrawable != null && roots.isIconUniqueBlocking(root)) {
                            // No summary needed if icon speaks for itself
                            summary.setVisibility(View.INVISIBLE);
                        } else {
                            summary.setText(root.getDirectoryString());
                            summary.setVisibility(View.VISIBLE);
                            summary.setTextAlignment(TextView.TEXT_ALIGNMENT_TEXT_END);
                        }
                    }
                }
            } else {
                // Directories showing thumbnails in grid mode get a little icon
                // hint to remind user they're a directory.
                if (Document.MIME_TYPE_DIR.equals(docMimeType) && state.derivedMode == MODE_GRID
                        && showThumbnail) {
                    iconDrawable = IconUtils.applyTintAttr(mContext, R.drawable.ic_doc_folder,
                            android.R.attr.textColorPrimaryInverse);
                }

                if (summary != null) {
                    if (docSummary != null) {
                        summary.setText(docSummary);
                        summary.setVisibility(View.VISIBLE);
                    } else {
                        summary.setVisibility(View.INVISIBLE);
                    }
                }
            }

            if (iconDrawable != null) {
                icon1.setVisibility(View.VISIBLE);
                icon1.setImageDrawable(iconDrawable);
            } else {
                icon1.setVisibility(View.GONE);
            }

            if (docLastModified == -1) {
                date.setText(null);
            } else {
                date.setText(formatTime(mContext, docLastModified));
            }

            if (!state.showSize || Document.MIME_TYPE_DIR.equals(docMimeType) || docSize == -1) {
                size.setVisibility(View.GONE);
            } else {
                size.setVisibility(View.VISIBLE);
                size.setText(Formatter.formatFileSize(mContext, docSize));
            }

            setEnabledRecursive(itemView, enabled);

            iconMime.setAlpha(iconAlpha);
            iconThumb.setAlpha(iconAlpha);
            icon1.setAlpha(iconAlpha);

            if (DEBUG_ENABLE_DND) {
                setupDragAndDropOnDocumentView(itemView, cursor);
            }
        }

        @Override
        public int getItemCount() {
            return mModel.getItemCount();
        }

    }

    private static String formatTime(Context context, long when) {
        // TODO: DateUtils should make this easier
        Time then = new Time();
        then.set(when);
        Time now = new Time();
        now.setToNow();

        int flags = DateUtils.FORMAT_NO_NOON | DateUtils.FORMAT_NO_MIDNIGHT
                | DateUtils.FORMAT_ABBREV_ALL;

        if (then.year != now.year) {
            flags |= DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_DATE;
        } else if (then.yearDay != now.yearDay) {
            flags |= DateUtils.FORMAT_SHOW_DATE;
        } else {
            flags |= DateUtils.FORMAT_SHOW_TIME;
        }

        return DateUtils.formatDateTime(context, when, flags);
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

    private void setEnabledRecursive(View v, boolean enabled) {
        if (v == null) return;
        if (v.isEnabled() == enabled) return;
        v.setEnabled(enabled);

        if (v instanceof ViewGroup) {
            final ViewGroup vg = (ViewGroup) v;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                setEnabledRecursive(vg.getChildAt(i), enabled);
            }
        }
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
        boolean changed = mSelectionManager.setItemsSelected(0, mModel.getItemCount(), true);
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
                    int dstPosition = mRecView.getChildAdapterPosition(getContainingItemView(v));
                    DocumentInfo dstDir = null;
                    if (dstPosition != android.widget.AdapterView.INVALID_POSITION) {
                        Cursor dstCursor = mModel.getItem(dstPosition);
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

    private View getContainingItemView(View view) {
        while (true) {
            if (view.getLayoutParams() instanceof RecyclerView.LayoutParams) {
                return view;
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
        int position = mRecView.getChildAdapterPosition(getContainingItemView(currentItemView));
        if (position == android.widget.AdapterView.INVALID_POSITION) {
            return Collections.EMPTY_LIST;
        }

        final List<DocumentInfo> selectedDocs =
                mModel.getDocuments(mSelectionManager.getSelection());
        if (!selectedDocs.isEmpty()) {
            if (!isSelected(position)) {
                // There is a selection that does not include the current item, drag nothing.
                return Collections.EMPTY_LIST;
            }
            return selectedDocs;
        }

        final Cursor cursor = mModel.getItem(position);
        checkNotNull(cursor, "Cursor cannot be null.");
        final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);

        return Lists.newArrayList(doc);
    }

    private Drawable getDragShadowIcon(List<DocumentInfo> docs) {
        if (docs.size() == 1) {
            final DocumentInfo doc = docs.get(0);
            return getDocumentIcon(getActivity(), doc.authority, doc.documentId,
                    doc.mimeType, doc.icon, getDisplayState());
        }
        return getActivity().getDrawable(R.drawable.ic_doc_generic);
    }

    public static Drawable getDocumentIcon(Context context, String docAuthority, String docId,
            String docMimeType, int docIcon, State state) {
        if (docIcon != 0) {
            return IconUtils.loadPackageIcon(context, docAuthority, docIcon);
        } else {
            return IconUtils.loadMimeIcon(context, docMimeType, docAuthority, docId,
                    state.derivedMode);
        }
    }

    private static class ThumbnailAsyncTask extends AsyncTask<Uri, Void, Bitmap>
            implements Preemptable {
        private final Uri mUri;
        private final ImageView mIconMime;
        private final ImageView mIconThumb;
        private final Point mThumbSize;
        private final float mTargetAlpha;
        private final CancellationSignal mSignal;

        public ThumbnailAsyncTask(Uri uri, ImageView iconMime, ImageView iconThumb, Point thumbSize,
                float targetAlpha) {
            mUri = uri;
            mIconMime = iconMime;
            mIconThumb = iconThumb;
            mThumbSize = thumbSize;
            mTargetAlpha = targetAlpha;
            mSignal = new CancellationSignal();
        }

        @Override
        public void preempt() {
            cancel(false);
            mSignal.cancel();
        }

        @Override
        protected Bitmap doInBackground(Uri... params) {
            if (isCancelled()) return null;

            final Context context = mIconThumb.getContext();
            final ContentResolver resolver = context.getContentResolver();

            ContentProviderClient client = null;
            Bitmap result = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, mUri.getAuthority());
                result = DocumentsContract.getDocumentThumbnail(client, mUri, mThumbSize, mSignal);
                if (result != null) {
                    final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(
                            context, mThumbSize);
                    thumbs.put(mUri, result);
                }
            } catch (Exception e) {
                if (!(e instanceof OperationCanceledException)) {
                    Log.w(TAG, "Failed to load thumbnail for " + mUri + ": " + e);
                }
            } finally {
                ContentProviderClient.releaseQuietly(client);
            }
            return result;
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            if (mIconThumb.getTag() == this && result != null) {
                mIconThumb.setTag(null);
                mIconThumb.setImageBitmap(result);

                mIconMime.setAlpha(mTargetAlpha);
                mIconMime.animate().alpha(0f).start();
                mIconThumb.setAlpha(0f);
                mIconThumb.animate().alpha(mTargetAlpha).start();
            }
        }
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

    boolean isSelected(int position) {
        return mSelectionManager.getSelection().contains(position);
    }

    /**
     * The data model for the current loaded directory.
     */
    @VisibleForTesting
    public static final class Model implements DocumentContext {
        private RecyclerView.Adapter<?> mViewAdapter;
        private Context mContext;
        private int mCursorCount;
        private boolean mIsLoading;
        @GuardedBy("mPendingDelete")
        private Boolean mPendingDelete = false;
        @GuardedBy("mPendingDelete")
        private SparseBooleanArray mMarkedForDeletion = new SparseBooleanArray();
        private UpdateListener mUpdateListener;
        @Nullable private Cursor mCursor;
        @Nullable private String info;
        @Nullable private String error;

        Model(Context context, RecyclerView.Adapter<?> viewAdapter) {
            mContext = context;
            mViewAdapter = viewAdapter;
        }

        void update(DirectoryResult result) {
            if (DEBUG) Log.i(TAG, "Updating model with new result set.");

            if (result == null) {
                mCursor = null;
                mCursorCount = 0;
                info = null;
                error = null;
                mIsLoading = false;
                mUpdateListener.onModelUpdate(this);
                return;
            }

            if (result.exception != null) {
                Log.e(TAG, "Error while loading directory contents", result.exception);
                mUpdateListener.onModelUpdateFailed(result.exception);
                return;
            }

            mCursor = result.cursor;
            mCursorCount = mCursor.getCount();

            final Bundle extras = mCursor.getExtras();
            if (extras != null) {
                info = extras.getString(DocumentsContract.EXTRA_INFO);
                error = extras.getString(DocumentsContract.EXTRA_ERROR);
                mIsLoading = extras.getBoolean(DocumentsContract.EXTRA_LOADING, false);
            }

            mUpdateListener.onModelUpdate(this);
        }

        int getItemCount() {
            synchronized(mPendingDelete) {
                return mCursorCount - mMarkedForDeletion.size();
            }
        }

        Cursor getItem(int position) {
            synchronized(mPendingDelete) {
                // Items marked for deletion are masked out of the UI.  To do this, for every marked
                // item whose position is less than the requested item position, advance the requested
                // position by 1.
                final int originalPos = position;
                final int size = mMarkedForDeletion.size();
                for (int i = 0; i < size; ++i) {
                    // It'd be more concise, but less efficient, to iterate over positions while calling
                    // mMarkedForDeletion.get.  Instead, iterate over deleted entries.
                    if (mMarkedForDeletion.keyAt(i) <= position && mMarkedForDeletion.valueAt(i)) {
                        ++position;
                    }
                }

                if (DEBUG && position != originalPos) {
                    Log.d(TAG, "Item position adjusted for deletion.  Original: " + originalPos
                            + "  Adjusted: " + position);
                }

                if (position >= mCursorCount) {
                    throw new IndexOutOfBoundsException("Attempt to retrieve " + position + " of " +
                            mCursorCount + " items");
                }

                mCursor.moveToPosition(position);
                return mCursor;
            }
        }

        private boolean isEmpty() {
            return mCursorCount == 0;
        }

        private boolean isLoading() {
            return mIsLoading;
        }

        List<DocumentInfo> getDocuments(Selection items) {
            final int size = (items != null) ? items.size() : 0;

            final List<DocumentInfo> docs =  new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                final Cursor cursor = getItem(items.get(i));
                checkNotNull(cursor, "Cursor cannot be null.");
                final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
                docs.add(doc);
            }
            return docs;
        }

        @Override
        public Cursor getCursor() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("Can't call getCursor from non-main thread.");
            }
            return mCursor;
        }

        List<DocumentInfo> getDocumentsMarkedForDeletion() {
            synchronized (mPendingDelete) {
                final int size = mMarkedForDeletion.size();
                List<DocumentInfo> docs =  new ArrayList<>(size);

                for (int i = 0; i < size; ++i) {
                    final int position = mMarkedForDeletion.keyAt(i);
                    checkState(position < mCursorCount);
                    mCursor.moveToPosition(position);
                    final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(mCursor);
                    docs.add(doc);
                }
                return docs;
            }
        }

        /**
         * Marks the given files for deletion. This will remove them from the UI. Clients must then
         * call either {@link #undoDeletion()} or {@link #finalizeDeletion()} to cancel or confirm
         * the deletion, respectively. Only one deletion operation is allowed at a time.
         *
         * @param selected A selection representing the files to delete.
         */
        void markForDeletion(Selection selected) {
            synchronized (mPendingDelete) {
                mPendingDelete = true;
                // Only one deletion operation at a time.
                checkState(mMarkedForDeletion.size() == 0);
                // There should never be more to delete than what exists.
                checkState(mCursorCount >= selected.size());

                int[] positions = selected.getAll();
                Arrays.sort(positions);

                // Walk backwards through the set, since we're removing positions.
                // Otherwise, positions would change after the first modification.
                for (int p = positions.length - 1; p >= 0; p--) {
                    mMarkedForDeletion.append(positions[p], true);
                    mViewAdapter.notifyItemRemoved(positions[p]);
                    if (DEBUG) Log.d(TAG, "Scheduled " + positions[p] + " for delete.");
                }
            }
        }

        /**
         * Cancels an ongoing deletion operation. All files currently marked for deletion will be
         * unmarked, and restored in the UI.  See {@link #markForDeletion(Selection)}.
         */
        void undoDeletion() {
            synchronized (mPendingDelete) {
                // Iterate over deleted items, temporarily marking them false in the deletion list, and
                // re-adding them to the UI.
                final int size = mMarkedForDeletion.size();
                for (int i = 0; i < size; ++i) {
                    final int position = mMarkedForDeletion.keyAt(i);
                    mMarkedForDeletion.put(position, false);
                    mViewAdapter.notifyItemInserted(position);
                }
                resetDeleteData();
            }
        }

        private void resetDeleteData() {
            synchronized (mPendingDelete) {
                mPendingDelete = false;
                mMarkedForDeletion.clear();
            }
        }

        /**
         * Finalizes an ongoing deletion operation. All files currently marked for deletion will be
         * deleted.  See {@link #markForDeletion(Selection)}.
         *
         * @param view The view which will be used to interact with the user (e.g. surfacing
         * snackbars) for errors, info, etc.
         */
        void finalizeDeletion(DeletionListener listener) {
            synchronized (mPendingDelete) {
                if (mPendingDelete) {
                    // Necessary to avoid b/25072545. Even when that's resolved, this
                    // is a nice safe thing to day.
                    mPendingDelete = false;
                    final ContentResolver resolver = mContext.getContentResolver();
                    DeleteFilesTask task = new DeleteFilesTask(resolver, listener);
                    task.execute();
                }
            }
        }

        /**
         * A Task which collects the DocumentInfo for documents that have been marked for deletion,
         * and actually deletes them.
         */
        private class DeleteFilesTask extends AsyncTask<Void, Void, List<DocumentInfo>> {
            private ContentResolver mResolver;
            private DeletionListener mListener;

            /**
             * @param resolver A ContentResolver for performing the actual file deletions.
             * @param errorCallback A Runnable that is executed in the event that one or more errors
             *     occured while copying files.  Execution will occur on the UI thread.
             */
            public DeleteFilesTask(ContentResolver resolver, DeletionListener listener) {
                mResolver = resolver;
                mListener = listener;
            }

            @Override
            protected List<DocumentInfo> doInBackground(Void... params) {
                return getDocumentsMarkedForDeletion();
            }

            @Override
            protected void onPostExecute(List<DocumentInfo> docs) {
                boolean hadTrouble = false;
                for (DocumentInfo doc : docs) {
                    if (!doc.isDeleteSupported()) {
                        Log.w(TAG, doc + " could not be deleted.  Skipping...");
                        hadTrouble = true;
                        continue;
                    }

                    ContentProviderClient client = null;
                    try {
                        if (DEBUG) Log.d(TAG, "Deleting: " + doc.displayName);
                        client = DocumentsApplication.acquireUnstableProviderOrThrow(
                            mResolver, doc.derivedUri.getAuthority());
                        DocumentsContract.deleteDocument(client, doc.derivedUri);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to delete " + doc);
                        hadTrouble = true;
                    } finally {
                        ContentProviderClient.releaseQuietly(client);
                    }
                }

                if (hadTrouble) {
                    // TODO show which files failed? b/23720103
                    mListener.onError();
                    if (DEBUG) Log.d(TAG, "Deletion task completed.  Some deletions failed.");
                } else {
                    if (DEBUG) Log.d(TAG, "Deletion task completed successfully.");
                }
                resetDeleteData();

                mListener.onCompletion();
            }
        }

        static class DeletionListener {
            /**
             * Called when deletion has completed (regardless of whether an error occurred).
             */
            void onCompletion() {}

            /**
             * Called at the end of a deletion operation that produced one or more errors.
             */
            void onError() {}
        }

        void addUpdateListener(UpdateListener listener) {
            checkState(mUpdateListener == null);
            mUpdateListener = listener;
        }

        static class UpdateListener {
            /**
             * Called when a successful update has occurred.
             */
            void onModelUpdate(Model model) {}

            /**
             * Called when an update has been attempted but failed.
             */
            void onModelUpdateFailed(Exception e) {}
        }
    }

    private class ItemClickListener implements ClickListener {
        @Override
        public void onClick(DocumentHolder doc) {
            final int position = doc.getAdapterPosition();
            if (mSelectionManager.hasSelection()) {
                mSelectionManager.toggleSelection(position);
            } else {
                handleViewItem(position);
            }
        }
    }

    private class ModelUpdateListener extends Model.UpdateListener {
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
