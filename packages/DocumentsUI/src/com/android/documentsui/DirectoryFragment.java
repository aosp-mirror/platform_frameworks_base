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
import static com.android.documentsui.DocumentsActivity.DisplayState.ACTION_MANAGE;
import static com.android.documentsui.DocumentsActivity.DisplayState.MODE_GRID;
import static com.android.documentsui.DocumentsActivity.DisplayState.MODE_LIST;
import static com.android.documentsui.DocumentsActivity.DisplayState.SORT_ORDER_DATE;
import static com.android.documentsui.DocumentsActivity.DisplayState.SORT_ORDER_NAME;
import static com.android.documentsui.DocumentsActivity.DisplayState.SORT_ORDER_SIZE;

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
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.documentsui.DocumentsActivity.DisplayState;
import com.android.documentsui.model.Document;
import com.android.documentsui.model.Root;
import com.android.internal.util.Predicate;
import com.google.android.collect.Lists;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends Fragment {

    private View mEmptyView;
    private ListView mListView;
    private GridView mGridView;
    private Button mMoreView;

    private AbsListView mCurrentView;

    private Predicate<Document> mFilter;

    public static final int TYPE_NORMAL = 1;
    public static final int TYPE_SEARCH = 2;
    public static final int TYPE_RECENT_OPEN = 3;

    private int mType = TYPE_NORMAL;

    private Point mThumbSize;

    private DocumentsAdapter mAdapter;
    private LoaderCallbacks<DirectoryResult> mCallbacks;

    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_URI = "uri";

    private static AtomicInteger sLoaderId = new AtomicInteger(4000);

    private final int mLoaderId = sLoaderId.incrementAndGet();

    public static void showNormal(FragmentManager fm, Uri uri) {
        show(fm, TYPE_NORMAL, uri);
    }

    public static void showSearch(FragmentManager fm, Uri uri, String query) {
        final Uri searchUri = DocumentsContract.buildSearchUri(uri, query);
        show(fm, TYPE_SEARCH, searchUri);
    }

    public static void showRecentsOpen(FragmentManager fm) {
        show(fm, TYPE_RECENT_OPEN, null);
    }

    private static void show(FragmentManager fm, int type, Uri uri) {
        final Bundle args = new Bundle();
        args.putInt(EXTRA_TYPE, type);
        args.putParcelable(EXTRA_URI, uri);

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

        mMoreView = (Button) view.findViewById(R.id.more);

        mAdapter = new DocumentsAdapter();

        final Uri uri = getArguments().getParcelable(EXTRA_URI);
        mType = getArguments().getInt(EXTRA_TYPE);

        mCallbacks = new LoaderCallbacks<DirectoryResult>() {
            @Override
            public Loader<DirectoryResult> onCreateLoader(int id, Bundle args) {
                final DisplayState state = getDisplayState(DirectoryFragment.this);
                mFilter = new MimePredicate(state.acceptMimes);

                Uri contentsUri;
                if (mType == TYPE_NORMAL) {
                    contentsUri = DocumentsContract.buildContentsUri(uri);
                } else if (mType == TYPE_RECENT_OPEN) {
                    contentsUri = RecentsProvider.buildRecentOpen();
                } else {
                    contentsUri = uri;
                }

                if (state.localOnly) {
                    contentsUri = DocumentsContract.setLocalOnly(contentsUri);
                }

                final Comparator<Document> sortOrder;
                if (state.sortOrder == SORT_ORDER_DATE || mType == TYPE_RECENT_OPEN) {
                    sortOrder = new Document.DateComparator();
                } else if (state.sortOrder == SORT_ORDER_NAME) {
                    sortOrder = new Document.NameComparator();
                } else if (state.sortOrder == SORT_ORDER_SIZE) {
                    sortOrder = new Document.SizeComparator();
                } else {
                    throw new IllegalArgumentException("Unknown sort order " + state.sortOrder);
                }

                return new DirectoryLoader(context, contentsUri, mType, null, sortOrder);
            }

            @Override
            public void onLoadFinished(Loader<DirectoryResult> loader, DirectoryResult result) {
                mAdapter.swapDocuments(result.contents);

                final Cursor cursor = result.cursor;
                if (cursor != null && cursor.getExtras()
                        .getBoolean(DocumentsContract.EXTRA_HAS_MORE, false)) {
                    mMoreView.setText(R.string.more);
                    mMoreView.setVisibility(View.VISIBLE);
                    mMoreView.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mMoreView.setText(R.string.loading);
                            final Bundle bundle = new Bundle();
                            bundle.putBoolean(DocumentsContract.EXTRA_REQUEST_MORE, true);
                            try {
                                cursor.respond(bundle);
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to respond: " + e);
                            }
                        }
                    });
                } else {
                    mMoreView.setVisibility(View.GONE);
                }
            }

            @Override
            public void onLoaderReset(Loader<DirectoryResult> loader) {
                mAdapter.swapDocuments(null);
            }
        };

        updateDisplayState();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        getLoaderManager().restartLoader(mLoaderId, getArguments(), mCallbacks);
    }

    @Override
    public void onStop() {
        super.onStop();
        getLoaderManager().destroyLoader(mLoaderId);
    }

    public void updateDisplayState() {
        final DisplayState state = getDisplayState(this);

        // TODO: avoid kicking loader when nothing changed
        getLoaderManager().restartLoader(mLoaderId, getArguments(), mCallbacks);
        mListView.smoothScrollToPosition(0);
        mGridView.smoothScrollToPosition(0);

        mListView.setVisibility(state.mode == MODE_LIST ? View.VISIBLE : View.GONE);
        mGridView.setVisibility(state.mode == MODE_GRID ? View.VISIBLE : View.GONE);

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
            final Document doc = mAdapter.getItem(position);
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
            final DisplayState state = getDisplayState(DirectoryFragment.this);

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
            final ArrayList<Document> docs = Lists.newArrayList();
            final int size = checked.size();
            for (int i = 0; i < size; i++) {
                if (checked.valueAt(i)) {
                    final Document doc = mAdapter.getItem(checked.keyAt(i));
                    docs.add(doc);
                }
            }

            final int id = item.getItemId();
            if (id == R.id.menu_open) {
                DocumentsActivity.get(DirectoryFragment.this).onDocumentsPicked(docs);
                return true;

            } else if (id == R.id.menu_share) {
                onShareDocuments(docs);
                return true;

            } else if (id == R.id.menu_delete) {
                onDeleteDocuments(docs);
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
                final Document doc = mAdapter.getItem(position);
                if (doc.isDirectory()) {
                    mCurrentView.setItemChecked(position, false);
                }
            }

            mode.setTitle(getResources()
                    .getString(R.string.mode_selected_count, mCurrentView.getCheckedItemCount()));
        }
    };

    private void onShareDocuments(List<Document> docs) {
        final ArrayList<Uri> uris = Lists.newArrayList();
        for (Document doc : docs) {
            uris.add(doc.uri);
        }

        final Intent intent;
        if (uris.size() > 1) {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            // TODO: find common mimetype
            intent.setType("*/*");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        } else {
            intent = new Intent(Intent.ACTION_SEND);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.setData(uris.get(0));
        }

        startActivity(intent);
    }

    private void onDeleteDocuments(List<Document> docs) {
        final Context context = getActivity();
        final ContentResolver resolver = context.getContentResolver();

        boolean hadTrouble = false;
        for (Document doc : docs) {
            if (!doc.isDeleteSupported()) {
                Log.w(TAG, "Skipping " + doc);
                hadTrouble = true;
                continue;
            }

            try {
                if (resolver.delete(doc.uri, null, null) != 1) {
                    Log.w(TAG, "Failed to delete " + doc);
                    hadTrouble = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to delete " + doc + ": " + e);
                hadTrouble = true;
            }
        }

        if (hadTrouble) {
            Toast.makeText(context, R.string.toast_failed_delete, Toast.LENGTH_SHORT).show();
        }
    }

    private static DisplayState getDisplayState(Fragment fragment) {
        return ((DocumentsActivity) fragment.getActivity()).getDisplayState();
    }

    private class DocumentsAdapter extends BaseAdapter {
        private List<Document> mDocuments;

        public DocumentsAdapter() {
        }

        public void swapDocuments(List<Document> documents) {
            mDocuments = documents;

            if (mDocuments != null && mDocuments.isEmpty()) {
                mEmptyView.setVisibility(View.VISIBLE);
            } else {
                mEmptyView.setVisibility(View.GONE);
            }

            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            final DisplayState state = getDisplayState(DirectoryFragment.this);

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

            final Document doc = getItem(position);

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

            if (doc.isThumbnailSupported()) {
                final Bitmap cachedResult = thumbs.get(doc.uri);
                if (cachedResult != null) {
                    icon.setImageBitmap(cachedResult);
                } else {
                    final ThumbnailAsyncTask task = new ThumbnailAsyncTask(icon, mThumbSize);
                    icon.setImageBitmap(null);
                    icon.setTag(task);
                    task.execute(doc.uri);
                }
            } else {
                icon.setImageDrawable(roots.resolveDocumentIcon(
                        context, doc.uri.getAuthority(), doc.mimeType));
            }

            title.setText(doc.displayName);

            if (mType == TYPE_NORMAL || mType == TYPE_SEARCH) {
                icon1.setVisibility(View.GONE);
                if (doc.summary != null) {
                    summary.setText(doc.summary);
                    summary.setVisibility(View.VISIBLE);
                } else {
                    summary.setVisibility(View.INVISIBLE);
                }
            } else if (mType == TYPE_RECENT_OPEN) {
                final Root root = roots.findRoot(doc);
                icon1.setVisibility(View.VISIBLE);
                icon1.setImageDrawable(root.icon);
                summary.setText(root.getDirectoryString());
                summary.setVisibility(View.VISIBLE);
            }

            if (summaryGrid != null) {
                summaryGrid.setVisibility(
                        (summary.getVisibility() == View.VISIBLE) ? View.VISIBLE : View.GONE);
            }

            if (doc.lastModified == -1) {
                date.setText(null);
            } else {
                date.setText(formatTime(context, doc.lastModified));
            }

            if (state.showSize) {
                size.setVisibility(View.VISIBLE);
                if (doc.isDirectory() || doc.size == -1) {
                    size.setText(null);
                } else {
                    size.setText(Formatter.formatFileSize(context, doc.size));
                }
            } else {
                size.setVisibility(View.GONE);
            }

            return convertView;
        }

        @Override
        public int getCount() {
            return mDocuments != null ? mDocuments.size() : 0;
        }

        @Override
        public Document getItem(int position) {
            return mDocuments.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).uri.hashCode();
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
                result = DocumentsContract.getThumbnail(
                        context.getContentResolver(), uri, mThumbSize);
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
}
