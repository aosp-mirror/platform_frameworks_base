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

import static com.android.documentsui.DocumentsActivity.TAG;
import static com.android.documentsui.DocumentsActivity.State.ACTION_MANAGE;
import static com.android.documentsui.DocumentsActivity.State.MODE_GRID;
import static com.android.documentsui.DocumentsActivity.State.MODE_LIST;
import static com.android.documentsui.model.DocumentInfo.getCursorInt;
import static com.android.documentsui.model.DocumentInfo.getCursorLong;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.text.format.Time;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.documentsui.DocumentsActivity.State;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;
import com.android.internal.util.Predicate;
import com.google.android.collect.Lists;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends Fragment {

    private View mEmptyView;
    private ListView mListView;
    private GridView mGridView;

    private AbsListView mCurrentView;

    private Predicate<DocumentInfo> mFilter;

    public static final int TYPE_NORMAL = 1;
    public static final int TYPE_SEARCH = 2;
    public static final int TYPE_RECENT_OPEN = 3;

    private int mType = TYPE_NORMAL;

    private Point mThumbSize;

    private DocumentsAdapter mAdapter;
    private LoaderCallbacks<DirectoryResult> mCallbacks;

    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_AUTHORITY = "authority";
    private static final String EXTRA_ROOT_ID = "rootId";
    private static final String EXTRA_DOC_ID = "docId";
    private static final String EXTRA_QUERY = "query";

    private static AtomicInteger sLoaderId = new AtomicInteger(4000);

    private int mLastSortOrder = -1;

    private final int mLoaderId = sLoaderId.incrementAndGet();

    public static void showNormal(FragmentManager fm, Uri uri) {
        show(fm, TYPE_NORMAL, uri.getAuthority(), null, DocumentsContract.getDocumentId(uri), null);
    }

    public static void showSearch(FragmentManager fm, Uri uri, String query) {
        show(fm, TYPE_SEARCH, uri.getAuthority(), null, DocumentsContract.getDocumentId(uri),
                query);
    }

    public static void showRecentsOpen(FragmentManager fm) {
        show(fm, TYPE_RECENT_OPEN, null, null, null, null);
    }

    private static void show(FragmentManager fm, int type, String authority, String rootId,
            String docId, String query) {
        final Bundle args = new Bundle();
        args.putInt(EXTRA_TYPE, type);
        args.putString(EXTRA_AUTHORITY, authority);
        args.putString(EXTRA_ROOT_ID, rootId);
        args.putString(EXTRA_DOC_ID, docId);
        args.putString(EXTRA_QUERY, query);

        final DirectoryFragment fragment = new DirectoryFragment();
        fragment.setArguments(args);

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, fragment);
        ft.commitAllowingStateLoss();
    }

    public static DirectoryFragment get(FragmentManager fm) {
        // TODO: deal with multiple directories shown at once
        return (DirectoryFragment) fm.findFragmentById(R.id.container_directory);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();
        final View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mEmptyView = view.findViewById(android.R.id.empty);

        mListView = (ListView) view.findViewById(R.id.list);
        mListView.setOnItemClickListener(mItemListener);
        mListView.setMultiChoiceModeListener(mMultiListener);

        mGridView = (GridView) view.findViewById(R.id.grid);
        mGridView.setOnItemClickListener(mItemListener);
        mGridView.setMultiChoiceModeListener(mMultiListener);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Context context = getActivity();

        mAdapter = new DocumentsAdapter();
        mType = getArguments().getInt(EXTRA_TYPE);

        mCallbacks = new LoaderCallbacks<DirectoryResult>() {
            @Override
            public Loader<DirectoryResult> onCreateLoader(int id, Bundle args) {
                final State state = getDisplayState(DirectoryFragment.this);

                final String authority = getArguments().getString(EXTRA_AUTHORITY);
                final String rootId = getArguments().getString(EXTRA_ROOT_ID);
                final String docId = getArguments().getString(EXTRA_DOC_ID);
                final String query = getArguments().getString(EXTRA_QUERY);

                Uri contentsUri;
                switch (mType) {
                    case TYPE_NORMAL:
                        contentsUri = DocumentsContract.buildChildDocumentsUri(authority, docId);
                        return new DirectoryLoader(context, rootId, contentsUri, state.sortOrder);
                    case TYPE_SEARCH:
                        contentsUri = DocumentsContract.buildSearchDocumentsUri(
                                authority, docId, query);
                        return new DirectoryLoader(context, rootId, contentsUri, state.sortOrder);
                    case TYPE_RECENT_OPEN:
                        final RootsCache roots = DocumentsApplication.getRootsCache(context);
                        final List<RootInfo> matchingRoots = roots.getMatchingRoots(state);
                        return new RecentLoader(context, matchingRoots);
                    default:
                        throw new IllegalStateException("Unknown type " + mType);

                }
            }

            @Override
            public void onLoadFinished(Loader<DirectoryResult> loader, DirectoryResult result) {
                mAdapter.swapCursor(result.cursor);
            }

            @Override
            public void onLoaderReset(Loader<DirectoryResult> loader) {
                mAdapter.swapCursor(null);
            }
        };

        updateDisplayState();
    }

    public void updateDisplayState() {
        final State state = getDisplayState(this);

        if (mLastSortOrder != state.sortOrder) {
            getLoaderManager().restartLoader(mLoaderId, null, mCallbacks);
            mLastSortOrder = state.sortOrder;
        }

        mListView.smoothScrollToPosition(0);
        mGridView.smoothScrollToPosition(0);

        mListView.setVisibility(state.mode == MODE_LIST ? View.VISIBLE : View.GONE);
        mGridView.setVisibility(state.mode == MODE_GRID ? View.VISIBLE : View.GONE);

        mFilter = new MimePredicate(state.acceptMimes);

        final int choiceMode;
        if (state.allowMultiple) {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL;
        } else {
            choiceMode = ListView.CHOICE_MODE_NONE;
        }

        final int thumbSize;
        if (state.mode == MODE_GRID) {
            thumbSize = getResources().getDimensionPixelSize(R.dimen.grid_width);
            mListView.setAdapter(null);
            mListView.setChoiceMode(ListView.CHOICE_MODE_NONE);
            mGridView.setAdapter(mAdapter);
            mGridView.setColumnWidth(getResources().getDimensionPixelSize(R.dimen.grid_width));
            mGridView.setNumColumns(GridView.AUTO_FIT);
            mGridView.setChoiceMode(choiceMode);
            mCurrentView = mGridView;
        } else if (state.mode == MODE_LIST) {
            thumbSize = getResources().getDimensionPixelSize(android.R.dimen.app_icon_size);
            mGridView.setAdapter(null);
            mGridView.setChoiceMode(ListView.CHOICE_MODE_NONE);
            mListView.setAdapter(mAdapter);
            mListView.setChoiceMode(choiceMode);
            mCurrentView = mListView;
        } else {
            throw new IllegalStateException();
        }

        mThumbSize = new Point(thumbSize, thumbSize);
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final Cursor cursor = mAdapter.getItem(position);
            final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
            if (mFilter.apply(doc)) {
                ((DocumentsActivity) getActivity()).onDocumentPicked(doc);
            }
        }
    };

    private MultiChoiceModeListener mMultiListener = new MultiChoiceModeListener() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.mode_directory, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            final State state = getDisplayState(DirectoryFragment.this);

            final MenuItem open = menu.findItem(R.id.menu_open);
            final MenuItem share = menu.findItem(R.id.menu_share);
            final MenuItem delete = menu.findItem(R.id.menu_delete);

            final boolean manageMode = state.action == ACTION_MANAGE;
            open.setVisible(!manageMode);
            share.setVisible(manageMode);
            delete.setVisible(manageMode);

            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            final SparseBooleanArray checked = mCurrentView.getCheckedItemPositions();
            final ArrayList<DocumentInfo> docs = Lists.newArrayList();
            final int size = checked.size();
            for (int i = 0; i < size; i++) {
                if (checked.valueAt(i)) {
                    final Cursor cursor = mAdapter.getItem(checked.keyAt(i));
                    final DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
                    docs.add(doc);
                }
            }

            final int id = item.getItemId();
            if (id == R.id.menu_open) {
                DocumentsActivity.get(DirectoryFragment.this).onDocumentsPicked(docs);
                mode.finish();
                return true;

            } else if (id == R.id.menu_share) {
                onShareDocuments(docs);
                mode.finish();
                return true;

            } else if (id == R.id.menu_delete) {
                onDeleteDocuments(docs);
                mode.finish();
                return true;

            } else {
                return false;
            }
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            // ignored
        }

        @Override
        public void onItemCheckedStateChanged(
                ActionMode mode, int position, long id, boolean checked) {
            if (checked) {
                // Directories cannot be checked
                final Cursor cursor = mAdapter.getItem(position);
                final String docMimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
                if (Document.MIME_TYPE_DIR.equals(docMimeType)) {
                    mCurrentView.setItemChecked(position, false);
                }
            }

            mode.setTitle(getResources()
                    .getString(R.string.mode_selected_count, mCurrentView.getCheckedItemCount()));
        }
    };

    private void onShareDocuments(List<DocumentInfo> docs) {
        Intent intent;
        if (docs.size() == 1) {
            final DocumentInfo doc = docs.get(0);

            intent = new Intent(Intent.ACTION_SEND);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.setType(doc.mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, doc.uri);

        } else if (docs.size() > 1) {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addCategory(Intent.CATEGORY_DEFAULT);

            final ArrayList<String> mimeTypes = Lists.newArrayList();
            final ArrayList<Uri> uris = Lists.newArrayList();
            for (DocumentInfo doc : docs) {
                mimeTypes.add(doc.mimeType);
                uris.add(doc.uri);
            }

            intent.setType(findCommonMimeType(mimeTypes));
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);

        } else {
            return;
        }

        intent = Intent.createChooser(intent, getActivity().getText(R.string.share_via));
        startActivity(intent);
    }

    private void onDeleteDocuments(List<DocumentInfo> docs) {
        final Context context = getActivity();
        final ContentResolver resolver = context.getContentResolver();

        boolean hadTrouble = false;
        for (DocumentInfo doc : docs) {
            if (!doc.isDeleteSupported()) {
                Log.w(TAG, "Skipping " + doc);
                hadTrouble = true;
                continue;
            }

            if (!DocumentsContract.deleteDocument(resolver, doc.uri)) {
                Log.w(TAG, "Failed to delete " + doc);
                hadTrouble = true;
            }
        }

        if (hadTrouble) {
            Toast.makeText(context, R.string.toast_failed_delete, Toast.LENGTH_SHORT).show();
        }
    }

    private static State getDisplayState(Fragment fragment) {
        return ((DocumentsActivity) fragment.getActivity()).getDisplayState();
    }

    private interface Footer {
        public View getView(View convertView, ViewGroup parent);
    }

    private static class LoadingFooter implements Footer {
        @Override
        public View getView(View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(context);
                convertView = inflater.inflate(R.layout.item_loading, parent, false);
            }
            return convertView;
        }
    }

    private class MessageFooter implements Footer {
        private final int mIcon;
        private final String mMessage;

        public MessageFooter(int icon, String message) {
            mIcon = icon;
            mMessage = message;
        }

        @Override
        public View getView(View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            final State state = getDisplayState(DirectoryFragment.this);

            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(context);
                if (state.mode == MODE_LIST) {
                    convertView = inflater.inflate(R.layout.item_message_list, parent, false);
                } else if (state.mode == MODE_GRID) {
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

    private class DocumentsAdapter extends BaseAdapter {
        private Cursor mCursor;
        private int mCursorCount;

        private List<Footer> mFooters = Lists.newArrayList();

        public void swapCursor(Cursor cursor) {
            mCursor = cursor;
            mCursorCount = cursor != null ? cursor.getCount() : 0;

            mFooters.clear();

            final Bundle extras = cursor != null ? cursor.getExtras() : null;
            if (extras != null) {
                final String info = extras.getString(DocumentsContract.EXTRA_INFO);
                if (info != null) {
                    mFooters.add(new MessageFooter(
                            com.android.internal.R.drawable.ic_menu_info_details, info));
                }
                final String error = extras.getString(DocumentsContract.EXTRA_ERROR);
                if (error != null) {
                    mFooters.add(new MessageFooter(
                            com.android.internal.R.drawable.ic_dialog_alert, error));
                }
                if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                    mFooters.add(new LoadingFooter());
                }
            }

            if (isEmpty()) {
                mEmptyView.setVisibility(View.VISIBLE);
            } else {
                mEmptyView.setVisibility(View.GONE);
            }

            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position < mCursorCount) {
                return getDocumentView(position, convertView, parent);
            } else {
                position -= mCursorCount;
                return mFooters.get(position).getView(convertView, parent);
            }
        }

        private View getDocumentView(int position, View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            final State state = getDisplayState(DirectoryFragment.this);

            final RootsCache roots = DocumentsApplication.getRootsCache(context);
            final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(
                    context, mThumbSize);

            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(context);
                if (state.mode == MODE_LIST) {
                    convertView = inflater.inflate(R.layout.item_doc_list, parent, false);
                } else if (state.mode == MODE_GRID) {
                    convertView = inflater.inflate(R.layout.item_doc_grid, parent, false);
                } else {
                    throw new IllegalStateException();
                }
            }

            final Cursor cursor = getItem(position);

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

            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final View summaryGrid = convertView.findViewById(R.id.summary_grid);
            final ImageView icon1 = (ImageView) convertView.findViewById(android.R.id.icon1);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);
            final TextView date = (TextView) convertView.findViewById(R.id.date);
            final TextView size = (TextView) convertView.findViewById(R.id.size);

            final ThumbnailAsyncTask oldTask = (ThumbnailAsyncTask) icon.getTag();
            if (oldTask != null) {
                oldTask.cancel(false);
            }

            if ((docFlags & Document.FLAG_SUPPORTS_THUMBNAIL) != 0) {
                final Uri uri = DocumentsContract.buildDocumentUri(docAuthority, docId);
                final Bitmap cachedResult = thumbs.get(uri);
                if (cachedResult != null) {
                    icon.setImageBitmap(cachedResult);
                } else {
                    final ThumbnailAsyncTask task = new ThumbnailAsyncTask(icon, mThumbSize);
                    icon.setImageBitmap(null);
                    icon.setTag(task);
                    task.execute(uri);
                }
            } else if (docIcon != 0) {
                icon.setImageDrawable(DocumentInfo.loadIcon(context, docAuthority, docIcon));
            } else {
                icon.setImageDrawable(RootsCache.resolveDocumentIcon(context, docMimeType));
            }

            title.setText(docDisplayName);

            if (mType == TYPE_RECENT_OPEN) {
                final RootInfo root = roots.getRoot(docAuthority, docRootId);
                icon1.setVisibility(View.VISIBLE);
                icon1.setImageDrawable(root.loadIcon(context));
                summary.setText(root.getDirectoryString());
                summary.setVisibility(View.VISIBLE);
            } else {
                icon1.setVisibility(View.GONE);
                if (docSummary != null) {
                    summary.setText(docSummary);
                    summary.setVisibility(View.VISIBLE);
                } else {
                    summary.setVisibility(View.INVISIBLE);
                }
            }

            if (summaryGrid != null) {
                summaryGrid.setVisibility(
                        (summary.getVisibility() == View.VISIBLE) ? View.VISIBLE : View.GONE);
            }

            if (docLastModified == -1) {
                date.setText(null);
            } else {
                date.setText(formatTime(context, docLastModified));
            }

            if (state.showSize) {
                size.setVisibility(View.VISIBLE);
                if (Document.MIME_TYPE_DIR.equals(docMimeType) || docSize == -1) {
                    size.setText(null);
                } else {
                    size.setText(Formatter.formatFileSize(context, docSize));
                }
            } else {
                size.setVisibility(View.GONE);
            }

            return convertView;
        }

        @Override
        public int getCount() {
            return mCursorCount + mFooters.size();
        }

        @Override
        public Cursor getItem(int position) {
            if (position < mCursorCount) {
                mCursor.moveToPosition(position);
                return mCursor;
            } else {
                return null;
            }
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getItemViewType(int position) {
            if (position < mCursorCount) {
                return 0;
            } else {
                return IGNORE_ITEM_VIEW_TYPE;
            }
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return position < mCursorCount;
        }
    }

    private static class ThumbnailAsyncTask extends AsyncTask<Uri, Void, Bitmap> {
        private final ImageView mTarget;
        private final Point mThumbSize;

        public ThumbnailAsyncTask(ImageView target, Point thumbSize) {
            mTarget = target;
            mThumbSize = thumbSize;
        }

        @Override
        protected void onPreExecute() {
            mTarget.setTag(this);
        }

        @Override
        protected Bitmap doInBackground(Uri... params) {
            final Context context = mTarget.getContext();
            final Uri uri = params[0];

            Bitmap result = null;
            try {
                result = DocumentsContract.getDocumentThumbnail(
                        context.getContentResolver(), uri, mThumbSize, null);
                if (result != null) {
                    final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(
                            context, mThumbSize);
                    thumbs.put(uri, result);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to load thumbnail: " + e);
            }
            return result;
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            if (mTarget.getTag() == this) {
                mTarget.setImageBitmap(result);
                mTarget.setTag(null);
            }
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
}
