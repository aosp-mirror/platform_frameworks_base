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

package com.android.documentsui;

import static com.android.documentsui.BaseActivity.State.ACTION_BROWSE;
import static com.android.documentsui.BaseActivity.State.ACTION_BROWSE_ALL;
import static com.android.documentsui.BaseActivity.State.ACTION_CREATE;
import static com.android.documentsui.BaseActivity.State.ACTION_MANAGE;
import static com.android.documentsui.BaseActivity.State.MODE_GRID;
import static com.android.documentsui.BaseActivity.State.MODE_LIST;
import static com.android.documentsui.BaseActivity.State.MODE_UNKNOWN;
import static com.android.documentsui.BaseActivity.State.SORT_ORDER_UNKNOWN;
import static com.android.documentsui.Shared.TAG;
import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkState;

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
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.LayoutManager;
import android.support.v7.widget.RecyclerView.RecyclerListener;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.text.format.Time;
import android.util.Log;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.documentsui.BaseActivity.DocumentContext;
import com.android.documentsui.BaseActivity.State;
import com.android.documentsui.MultiSelectManager.Selection;
import com.android.documentsui.ProviderExecutor.Preemptable;
import com.android.documentsui.RecentsProvider.StateColumns;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;
import com.android.internal.util.Preconditions;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private static final int LOADER_ID = 42;
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_ENABLE_DND = false;

    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_ROOT = "root";
    private static final String EXTRA_DOC = "doc";
    private static final String EXTRA_QUERY = "query";
    private static final String EXTRA_IGNORE_STATE = "ignoreState";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

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
    private FragmentTuner mFragmentTuner;
    private DocumentClipper mClipper;
    private MultiSelectManager mSelectionManager;
    // These are lazily initialized.
    private LinearLayoutManager mListLayout;
    private GridLayoutManager mGridLayout;
    private int mColumnCount = 1;  // This will get updated when layout changes.

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

    public static DirectoryFragment get(FragmentManager fm) {
        // TODO: deal with multiple directories shown at once
        return (DirectoryFragment) fm.findFragmentById(R.id.container_directory);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();
        final Resources res = context.getResources();
        final View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mEmptyView = view.findViewById(android.R.id.empty);

        mRecView = (RecyclerView) view.findViewById(R.id.recyclerView);
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
                new OnLayoutChangeListener() {

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
        final State state = getDisplayState(DirectoryFragment.this);

        final RootInfo root = getArguments().getParcelable(EXTRA_ROOT);
        final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);

        mAdapter = new DocumentsAdapter(context);
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

        mSelectionManager = new MultiSelectManager(
                mRecView,
                listener,
                state.allowMultiple
                    ? MultiSelectManager.MODE_MULTIPLE
                    : MultiSelectManager.MODE_SINGLE);

        mSelectionManager.addCallback(new SelectionModeListener());

        mType = getArguments().getInt(EXTRA_TYPE);
        mStateKey = buildStateKey(root, doc);

        mFragmentTuner = pickFragmentTuner(state);
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
                if (result == null || result.exception != null) {
                    // onBackPressed does a fragment transaction, which can't be done inside
                    // onLoadFinished
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            final Activity activity = getActivity();
                            if (activity != null) {
                                activity.onBackPressed();
                            }
                        }
                    });
                    return;
                }

                if (!isAdded()) return;

                mAdapter.replaceResult(result);

                // Push latest state up to UI
                // TODO: if mode change was racing with us, don't overwrite it
                if (result.mode != MODE_UNKNOWN) {
                    state.derivedMode = result.mode;
                }
                state.derivedSortOrder = result.sortOrder;
                ((BaseActivity) context).onStateChanged();

                updateDisplayState();

                // When launched into empty recents, show drawer
                if (mType == TYPE_RECENT_OPEN && mAdapter.isEmpty() && !state.stackTouched &&
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
                mAdapter.replaceResult(null);
            }
        };

        // Kick off loader at least once
        getLoaderManager().restartLoader(LOADER_ID, null, mCallbacks);

        mFragmentTuner.afterActivityCreated(this);
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

        CopyService.start(getActivity(), getDisplayState(this).selectedDocumentsForCopy,
                (DocumentStack) data.getParcelableExtra(CopyService.EXTRA_STACK),
                data.getIntExtra(CopyService.EXTRA_TRANSFER_MODE, CopyService.TRANSFER_MODE_NONE));
    }

    private int getEventAdapterPosition(MotionEvent e) {
        View view = mRecView.findChildViewUnder(e.getX(), e.getY());
        return view != null ? mRecView.getChildAdapterPosition(view) : RecyclerView.NO_POSITION;
    }

    private boolean onSingleTapUp(MotionEvent e) {
        if (Events.isTouchEvent(e) && mSelectionManager.getSelection().isEmpty()) {
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
        final Cursor cursor = mAdapter.getItem(position);
        checkNotNull(cursor, "Cursor cannot be null.");
        final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
        if (isDocumentEnabled(docMimeType, docFlags)) {
            final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
            ((BaseActivity) getActivity()).onDocumentPicked(doc, mAdapter);
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
        final State state = getDisplayState(this);
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
        final State state = getDisplayState(this);

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
        final State state = getDisplayState(this);

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
            // Directories and footer items cannot be checked
            if (selected) {
                final Cursor cursor = mAdapter.getItem(position);
                checkNotNull(cursor, "Cursor cannot be null.");
                final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
                final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
                return isDocumentEnabled(docMimeType, docFlags);
            }
            return true;
        }

        @Override
        public void onItemStateChanged(int position, boolean selected) {

            final Cursor cursor = mAdapter.getItem(position);
            checkNotNull(cursor, "Cursor cannot be null.");

            final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
            if ((docFlags & Document.FLAG_SUPPORTS_DELETE) == 0) {
                mNoDeleteCount += selected ? 1 : -1;
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
                    getActivity().getWindow().setStatusBarColor(
                        getResources().getColor(R.color.action_mode_status_bar_background));
                }
                updateActionMenu();
            } else {
                if (DEBUG) Log.d(TAG, "Finishing action mode.");
                if (mActionMode != null) {
                    mActionMode.finish();
                }
                getActivity().getWindow().setStatusBarColor(
                    getResources().getColor(R.color.status_bar_background));
            }

            if (mActionMode != null) {
                mActionMode.setTitle(TextUtils.formatSelectedCount(mSelected.size()));
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
            mode.getMenuInflater().inflate(R.menu.mode_directory, menu);
            mode.setTitle(TextUtils.formatSelectedCount(mSelectionManager.getSelection().size()));
            return mSelectionManager.getSelection().size() > 0;
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
            mFragmentTuner.updateActionMenu(mMenu, mType, mNoDeleteCount == 0);
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {

            Selection selection = new Selection();
            mSelectionManager.getSelection(selection);

            final int id = item.getItemId();
            if (id == R.id.menu_open) {
                openDocuments(selection);
                mode.finish();
                return true;

            } else if (id == R.id.menu_share) {
                shareDocuments(selection);
                mode.finish();
                return true;

            } else if (id == R.id.menu_delete) {
                deleteDocuments(selection);
                mode.finish();
                return true;

            } else if (id == R.id.menu_copy_to) {
                transferDocuments(selection, CopyService.TRANSFER_MODE_COPY);
                mode.finish();
                return true;

            } else if (id == R.id.menu_move_to) {
                transferDocuments(selection, CopyService.TRANSFER_MODE_MOVE);
                mode.finish();
                return true;

            } else if (id == R.id.menu_copy_to_clipboard) {
                copySelectionToClipboard(selection);
                mode.finish();
                return true;

            } else if (id == R.id.menu_select_all) {
                selectAllFiles();
                return true;

            } else {
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
                // TODO: Implement support in standalone for opening multiple docs.
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
        final Context context = getActivity();
        final ContentResolver resolver = context.getContentResolver();

        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                boolean hadTrouble = false;
                for (DocumentInfo doc : docs) {
                    if (!doc.isDeleteSupported()) {
                        Log.w(TAG, "Skipping " + doc);
                        hadTrouble = true;
                        continue;
                    }

                    ContentProviderClient client = null;
                    try {
                        client = DocumentsApplication.acquireUnstableProviderOrThrow(
                                resolver, doc.derivedUri.getAuthority());
                        DocumentsContract.deleteDocument(client, doc.derivedUri);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to delete " + doc);
                        hadTrouble = true;
                    } finally {
                        ContentProviderClient.releaseQuietly(client);
                    }
                }

                if (hadTrouble) {
                    Toast.makeText(
                            context,
                            R.string.toast_failed_delete,
                            Toast.LENGTH_SHORT).show();
                }
            }
        }.execute(selected);
    }

    private void transferDocuments(final Selection selected, final int mode) {
        // Pop up a dialog to pick a destination.  This is inadequate but works for now.
        // TODO: Implement a picker that is to spec.
        final Intent intent = new Intent(
                BaseActivity.DocumentsIntent.ACTION_OPEN_COPY_DESTINATION,
                Uri.EMPTY,
                getActivity(),
                DocumentsActivity.class);

        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                getDisplayState(DirectoryFragment.this).selectedDocumentsForCopy = docs;

                boolean directoryCopy = false;
                for (DocumentInfo info : docs) {
                    if (Document.MIME_TYPE_DIR.equals(info.mimeType)) {
                        directoryCopy = true;
                        break;
                    }
                }
                intent.putExtra(BaseActivity.DocumentsIntent.EXTRA_DIRECTORY_COPY, directoryCopy);
                intent.putExtra(CopyService.EXTRA_TRANSFER_MODE, mode);
                startActivityForResult(intent, REQUEST_COPY_DESTINATION);
            }
        }.execute(selected);
    }

    private static State getDisplayState(Fragment fragment) {
        return ((BaseActivity) fragment.getActivity()).getDisplayState();
    }

    private static abstract class Footer {
        private final int mItemViewType;

        public Footer(int itemViewType) {
            mItemViewType = itemViewType;
        }

        public abstract View getView(View convertView, ViewGroup parent);

        public int getItemViewType() {
            return mItemViewType;
        }
    }

    private class LoadingFooter extends Footer {
        public LoadingFooter() {
            super(1);
        }

        @Override
        public View getView(View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            final State state = getDisplayState(DirectoryFragment.this);

            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(context);
                if (state.derivedMode == MODE_LIST) {
                    convertView = inflater.inflate(R.layout.item_loading_list, parent, false);
                } else if (state.derivedMode == MODE_GRID) {
                    convertView = inflater.inflate(R.layout.item_loading_grid, parent, false);
                } else {
                    throw new IllegalStateException();
                }
            }

            return convertView;
        }
    }

    private class MessageFooter extends Footer {
        private final int mIcon;
        private final String mMessage;

        public MessageFooter(int itemViewType, int icon, String message) {
            super(itemViewType);
            mIcon = icon;
            mMessage = message;
        }

        @Override
        public View getView(View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            final State state = getDisplayState(DirectoryFragment.this);

            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(context);
                if (state.derivedMode == MODE_LIST) {
                    convertView = inflater.inflate(R.layout.item_message_list, parent, false);
                } else if (state.derivedMode == MODE_GRID) {
                    convertView = inflater.inflate(R.layout.item_message_grid, parent, false);
                } else {
                    throw new IllegalStateException();
                }
            }

            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            icon.setImageResource(mIcon);
            title.setText(mMessage);
            return convertView;
        }
    }

    // Provide a reference to the views for each data item
    // Complex data items may need more than one view per item, and
    // you provide access to all the views for a data item in a view holder
    private static final class DocumentHolder extends RecyclerView.ViewHolder {
        // each data item is just a string in this case
        public View view;
        public String docId;  // The stable document id.
        public DocumentHolder(View view) {
            super(view);
            this.view = view;
        }
    }

    private final class DocumentsAdapter extends RecyclerView.Adapter<DocumentHolder>
            implements DocumentContext {

        private final Context mContext;
        private final LayoutInflater mInflater;
        // TODO: Bring back support for footers.
        private final List<Footer> mFooters = new ArrayList<>();

        private Cursor mCursor;
        private int mCursorCount;

        public DocumentsAdapter(Context context) {
            mContext = context;
            mInflater = LayoutInflater.from(context);
        }

        public void replaceResult(DirectoryResult result) {
            if (DEBUG) Log.i(TAG, "Updating adapter with new result set.");
            mCursor = result != null ? result.cursor : null;
            mCursorCount = mCursor != null ? mCursor.getCount() : 0;

            mFooters.clear();

            final Bundle extras = mCursor != null ? mCursor.getExtras() : null;
            if (extras != null) {
                final String info = extras.getString(DocumentsContract.EXTRA_INFO);
                if (info != null) {
                    mFooters.add(new MessageFooter(2, R.drawable.ic_dialog_info, info));
                }
                final String error = extras.getString(DocumentsContract.EXTRA_ERROR);
                if (error != null) {
                    mFooters.add(new MessageFooter(3, R.drawable.ic_dialog_alert, error));
                }
                if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                    mFooters.add(new LoadingFooter());
                }
            }

            if (result != null && result.exception != null) {
                mFooters.add(new MessageFooter(
                        3, R.drawable.ic_dialog_alert, getString(R.string.query_error)));
            }

            if (isEmpty()) {
                mEmptyView.setVisibility(View.VISIBLE);
            } else {
                mEmptyView.setVisibility(View.GONE);
            }

            notifyDataSetChanged();
        }

        @Override
        public DocumentHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final State state = getDisplayState(DirectoryFragment.this);
            final LayoutInflater inflater = LayoutInflater.from(getContext());
            switch (state.derivedMode) {
                case MODE_GRID:
                    return new DocumentHolder(inflater.inflate(R.layout.item_doc_grid, parent, false));
                case MODE_LIST:
                    return new DocumentHolder(inflater.inflate(R.layout.item_doc_list, parent, false));
                case MODE_UNKNOWN:
                default:
                    throw new IllegalStateException("Unsupported layout mode.");
            }
        }

        @Override
        public void onBindViewHolder(DocumentHolder holder, int position) {

            final Context context = getContext();
            final State state = getDisplayState(DirectoryFragment.this);
            final DocumentInfo doc = getArguments().getParcelable(EXTRA_DOC);
            final RootsCache roots = DocumentsApplication.getRootsCache(context);
            final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(
                    context, mThumbSize);

            final Cursor cursor = getItem(position);
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
            final View itemView = holder.view;
            itemView.setActivated(mSelectionManager.getSelection().contains(position));

            final View line1 = itemView.findViewById(R.id.line1);
            final View line2 = itemView.findViewById(R.id.line2);

            final ImageView iconMime = (ImageView) itemView.findViewById(R.id.icon_mime);
            final ImageView iconThumb = (ImageView) itemView.findViewById(R.id.icon_thumb);
            final TextView title = (TextView) itemView.findViewById(android.R.id.title);
            final ImageView icon1 = (ImageView) itemView.findViewById(android.R.id.icon1);
            final ImageView icon2 = (ImageView) itemView.findViewById(android.R.id.icon2);
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

            final boolean enabled = isDocumentEnabled(docMimeType, docFlags);
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

            boolean hasLine1 = false;
            boolean hasLine2 = false;

            final boolean hideTitle = (state.derivedMode == MODE_GRID) && mHideGridTitles;
            if (!hideTitle) {
                title.setText(docDisplayName);
                hasLine1 = true;
            }

            Drawable iconDrawable = null;
            if (mType == TYPE_RECENT_OPEN) {
                // We've already had to enumerate roots before any results can
                // be shown, so this will never block.
                final RootInfo root = roots.getRootBlocking(docAuthority, docRootId);
                if (state.derivedMode == MODE_GRID) {
                    iconDrawable = root.loadGridIcon(mContext);
                } else {
                    iconDrawable = root.loadIcon(mContext);
                }

                if (summary != null) {
                    final boolean alwaysShowSummary = getResources()
                            .getBoolean(R.bool.always_show_summary);
                    if (alwaysShowSummary) {
                        summary.setText(root.getDirectoryString());
                        summary.setVisibility(View.VISIBLE);
                        hasLine2 = true;
                    } else {
                        if (iconDrawable != null && roots.isIconUniqueBlocking(root)) {
                            // No summary needed if icon speaks for itself
                            summary.setVisibility(View.INVISIBLE);
                        } else {
                            summary.setText(root.getDirectoryString());
                            summary.setVisibility(View.VISIBLE);
                            summary.setTextAlignment(TextView.TEXT_ALIGNMENT_TEXT_END);
                            hasLine2 = true;
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
                        hasLine2 = true;
                    } else {
                        summary.setVisibility(View.INVISIBLE);
                    }
                }
            }

            if (icon1 != null) icon1.setVisibility(View.GONE);
            if (icon2 != null) icon2.setVisibility(View.GONE);

            if (iconDrawable != null) {
                if (hasLine1) {
                    icon1.setVisibility(View.VISIBLE);
                    icon1.setImageDrawable(iconDrawable);
                } else {
                    icon2.setVisibility(View.VISIBLE);
                    icon2.setImageDrawable(iconDrawable);
                }
            }

            if (docLastModified == -1) {
                date.setText(null);
            } else {
                date.setText(formatTime(mContext, docLastModified));
                hasLine2 = true;
            }

            if (state.showSize) {
                size.setVisibility(View.VISIBLE);
                if (Document.MIME_TYPE_DIR.equals(docMimeType) || docSize == -1) {
                    size.setText(null);
                } else {
                    size.setText(Formatter.formatFileSize(mContext, docSize));
                    hasLine2 = true;
                }
            } else {
                size.setVisibility(View.GONE);
            }

            if (line1 != null) {
                line1.setVisibility(hasLine1 ? View.VISIBLE : View.GONE);
            }
            if (line2 != null) {
                line2.setVisibility(hasLine2 ? View.VISIBLE : View.GONE);
            }

            setEnabledRecursive(itemView, enabled);

            iconMime.setAlpha(iconAlpha);
            iconThumb.setAlpha(iconAlpha);
            if (icon1 != null) icon1.setAlpha(iconAlpha);
            if (icon2 != null) icon2.setAlpha(iconAlpha);

            if (DEBUG_ENABLE_DND) {
                setupDragAndDropOnDocumentView(itemView, cursor);
            }
        }

        @Override
        public Cursor getCursor() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("Can't call getCursor from non-main thread.");
            }
            return mCursor;
        }

        private Cursor getItem(int position) {
            if (position < mCursorCount) {
                mCursor.moveToPosition(position);
                return mCursor;
            }

            Log.w(TAG, "Returning null cursor for position: " + position);
            if (DEBUG) Log.d(TAG, "...Adapter size: " + mCursorCount);
            if (DEBUG) Log.d(TAG, "...Footer size: " + mFooters.size());
            return null;
        }

        @Override
        public int getItemCount() {
            return mCursorCount;
            // return mCursorCount + mFooters.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (position < mCursorCount) {
                return 0;
            } else {
                position -= mCursorCount;
                return mFooters.get(position).getItemViewType();
            }
        }

        private boolean isEmpty() {
            return getItemCount() > 0;
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

    private boolean isDocumentEnabled(String docMimeType, int docFlags) {
        final State state = getDisplayState(DirectoryFragment.this);

        // Directories are always enabled
        if (Document.MIME_TYPE_DIR.equals(docMimeType)) {
            return true;
        }

        // Read-only files are disabled when creating
        if (state.action == ACTION_CREATE && (docFlags & Document.FLAG_SUPPORTS_WRITE) == 0) {
            return false;
        }

        return MimePredicate.mimeMatches(state.acceptMimes, docMimeType);
    }

    private List<DocumentInfo> getSelectedDocuments() {
        Selection sel = mSelectionManager.getSelection(new Selection());
        return getItemsAsDocuments(sel);
    }

    private List<DocumentInfo> getItemsAsDocuments(Selection items) {
        if (items == null || items.size() == 0) {
            return new ArrayList<>(0);
        }

        final List<DocumentInfo> docs =  new ArrayList<>(items.size());
        final int size = items.size();
        for (int i = 0; i < size; i++) {
            final Cursor cursor = mAdapter.getItem(items.get(i));
            checkNotNull(cursor, "Cursor cannot be null.");
            final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
            docs.add(doc);
        }
        return docs;
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
            Toast.makeText(
                    getActivity(),
                    R.string.clipboard_files_cannot_paste, Toast.LENGTH_SHORT).show();
            return;
        }

        if (docs.isEmpty()) {
            return;
        }

        final DocumentStack curStack = getDisplayState(DirectoryFragment.this).stack;
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

    void copySelectedToClipboard() {
        Selection sel = mSelectionManager.getSelection(new Selection());
        copySelectionToClipboard(sel);
    }

    void copySelectionToClipboard(Selection items) {
        new GetDocumentsTask() {
            @Override
            void onDocumentsReady(List<DocumentInfo> docs) {
                mClipper.clipDocuments(docs);
                Activity activity = getActivity();
                Toast.makeText(activity,
                        activity.getResources().getQuantityString(
                                R.plurals.clipboard_files_clipped, docs.size(), docs.size()),
                                Toast.LENGTH_SHORT).show();
            }
        }.execute(items);
    }

    void pasteFromClipboard() {
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
        BaseActivity activity = (BaseActivity)getActivity();

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

    void selectAllFiles() {
        boolean changed = mSelectionManager.setItemsSelected(0, mAdapter.getItemCount(), true);
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
                    int dstPosition = mRecView.getChildAdapterPosition(v);
                    DocumentInfo dstDir = null;
                    if (dstPosition != android.widget.AdapterView.INVALID_POSITION) {
                        Cursor dstCursor = mAdapter.getItem(dstPosition);
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
                    View.DRAG_FLAG_GLOBAL
            );
            return true;
        }
    };

    private List<DocumentInfo> getDraggableDocuments(View currentItemView) {
        int position = mRecView.getChildAdapterPosition(currentItemView);
        if (position == android.widget.AdapterView.INVALID_POSITION) {
            return Collections.EMPTY_LIST;
        }

        final List<DocumentInfo> selectedDocs = getSelectedDocuments();
        if (!selectedDocs.isEmpty()) {
            if (!mSelectionManager.getSelection().contains(position)) {
                // There is a selection that does not include the current item, drag nothing.
                return Collections.EMPTY_LIST;
            }
            return selectedDocs;
        }

        final Cursor cursor = mAdapter.getItem(position);
        checkNotNull(cursor, "Cursor cannot be null.");
        final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);

        return Lists.newArrayList(doc);
    }

    private Drawable getDragShadowIcon(List<DocumentInfo> docs) {
        if (docs.size() == 1) {
            final DocumentInfo doc = docs.get(0);
            return getDocumentIcon(getActivity(), doc.authority, doc.documentId,
                    doc.mimeType, doc.icon, getDisplayState(this));
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

        public void onProvideShadowMetrics(
                Point shadowSize, Point shadowTouchPoint) {
            shadowSize.set(mShadowDimension, mShadowDimension);
            shadowTouchPoint.set(mShadowDimension / 2, mShadowDimension / 2);
        }

        public void onDrawShadow(Canvas canvas) {
            mShadow.draw(canvas);
        }
    }

    private FragmentTuner pickFragmentTuner(final State state) {
        return state.action == ACTION_BROWSE_ALL
                ? new StandaloneTuner()
                : new DefaultTuner(state);
    }

    /**
     * Interface for specializing the Fragment for the "host" Activity.
     * Feel free to expand the role of this class to handle other specializations.
     */
    private interface FragmentTuner {
        void updateActionMenu(Menu menu, int dirType, boolean canDelete);
        void afterActivityCreated(DirectoryFragment fragment);
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
            return getItemsAsDocuments(selected[0]);
        }

        @Override
        protected final void onPostExecute(List<DocumentInfo> docs) {
            onDocumentsReady(docs);
        }

        abstract void onDocumentsReady(List<DocumentInfo> docs);
    }

    /**
     * Provides support for Platform specific specializations of DirectoryFragment.
     */
    private static final class DefaultTuner implements FragmentTuner {

        private final State mState;

        public DefaultTuner(State state) {
            mState = state;
        }

        @Override
        public void updateActionMenu(Menu menu, int dirType, boolean canDelete) {
            Preconditions.checkState(mState.action != ACTION_BROWSE_ALL);

            final MenuItem open = menu.findItem(R.id.menu_open);
            final MenuItem share = menu.findItem(R.id.menu_share);
            final MenuItem delete = menu.findItem(R.id.menu_delete);
            final MenuItem copyTo = menu.findItem(R.id.menu_copy_to);
            final MenuItem moveTo = menu.findItem(R.id.menu_move_to);
            final MenuItem copyToClipboard = menu.findItem(R.id.menu_copy_to_clipboard);

            final boolean manageOrBrowse = (mState.action == ACTION_MANAGE
                    || mState.action == ACTION_BROWSE);

            open.setVisible(!manageOrBrowse);
            share.setVisible(manageOrBrowse);
            delete.setVisible(manageOrBrowse && canDelete);
            // Disable copying from the Recents view.
            copyTo.setVisible(manageOrBrowse && dirType != TYPE_RECENT_OPEN);
            moveTo.setVisible(SystemProperties.getBoolean("debug.documentsui.enable_move", false));

            // Only shown in standalone mode.
            copyToClipboard.setVisible(false);
        }

        @Override
        public void afterActivityCreated(DirectoryFragment fragment) {}
    }

    /**
     * Provides support for Standalone specific specializations of DirectoryFragment.
     */
    private static final class StandaloneTuner implements FragmentTuner {
        @Override
        public void updateActionMenu(Menu menu, int dirType, boolean canDelete) {
            menu.findItem(R.id.menu_share).setVisible(true);
            menu.findItem(R.id.menu_delete).setVisible(canDelete);
            menu.findItem(R.id.menu_copy_to_clipboard).setVisible(true);

            menu.findItem(R.id.menu_open).setVisible(false);
            menu.findItem(R.id.menu_copy_to).setVisible(false);
            menu.findItem(R.id.menu_move_to).setVisible(false);
        }

        @Override
        public void afterActivityCreated(DirectoryFragment fragment) {
            new BandSelectManager(fragment.mRecView, fragment.mSelectionManager);
        }
    }
}
