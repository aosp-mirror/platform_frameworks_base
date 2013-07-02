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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.text.format.DateUtils;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.CursorAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.android.documentsui.DocumentsActivity.DisplayState;
import com.android.documentsui.DocumentsActivity.Document;
import com.google.android.collect.Lists;

import java.util.ArrayList;

/**
 * Display the documents inside a single directory.
 */
public class DirectoryFragment extends Fragment {

    // TODO: show storage backend in item views when requested

    private static final String TAG_SORT = "sort";

    private ListView mListView;
    private GridView mGridView;

    private AbsListView mCurrentView;

    private DocumentsAdapter mAdapter;
    private LoaderCallbacks<Cursor> mCallbacks;

    private int mFlags;

    private static final String EXTRA_URI = "uri";

    private static final int LOADER_DOCUMENTS = 2;

    public static void show(FragmentManager fm, Uri uri, String displayName) {
        final Bundle args = new Bundle();
        args.putParcelable(EXTRA_URI, uri);

        final DirectoryFragment fragment = new DirectoryFragment();
        fragment.setArguments(args);

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.directory, fragment);
        ft.addToBackStack(displayName);
        ft.setBreadCrumbTitle(displayName);
        ft.commitAllowingStateLoss();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();

        final View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mListView = (ListView) view.findViewById(R.id.list);
        mListView.setOnItemClickListener(mItemListener);
        mListView.setMultiChoiceModeListener(mMultiListener);

        mGridView = (GridView) view.findViewById(R.id.grid);
        mGridView.setOnItemClickListener(mItemListener);
        mGridView.setMultiChoiceModeListener(mMultiListener);

        mAdapter = new DocumentsAdapter(context);
        updateMode();

        // TODO: migrate flags query to loader
        final Uri uri = getArguments().getParcelable(EXTRA_URI);
        mFlags = getDocumentFlags(context, uri);

        mCallbacks = new LoaderCallbacks<Cursor>() {
            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                final DisplayState state = getDisplayState(DirectoryFragment.this);
                final String sortOrder;
                if (state.sortBy == DisplayState.SORT_BY_NAME) {
                    sortOrder = DocumentColumns.DISPLAY_NAME + " ASC";
                } else if (state.sortBy == DisplayState.SORT_BY_DATE) {
                    sortOrder = DocumentColumns.LAST_MODIFIED + " DESC";
                } else {
                    sortOrder = null;
                }

                final Uri contentsUri = DocumentsContract.buildContentsUri(uri);
                return new CursorLoader(context, contentsUri, null, null, null, sortOrder);
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                mAdapter.swapCursor(data);
            }

            @Override
            public void onLoaderReset(Loader<Cursor> loader) {
                mAdapter.swapCursor(null);
            }
        };

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        getLoaderManager().restartLoader(LOADER_DOCUMENTS, getArguments(), mCallbacks);

        // TODO: clean up tracking of current directory
        final Uri uri = getArguments().getParcelable(EXTRA_URI);
        ((DocumentsActivity) getActivity()).onDirectoryChanged(uri, mFlags);
    }

    @Override
    public void onStop() {
        super.onStop();
        getLoaderManager().destroyLoader(LOADER_DOCUMENTS);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.directory, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        final DisplayState state = getDisplayState(this);
        menu.findItem(R.id.menu_grid).setVisible(state.mode != DisplayState.MODE_GRID);
        menu.findItem(R.id.menu_list).setVisible(state.mode != DisplayState.MODE_LIST);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final DisplayState state = getDisplayState(this);
        final int id = item.getItemId();
        if (id == R.id.menu_grid) {
            state.mode = DisplayState.MODE_GRID;
            updateMode();
            getFragmentManager().invalidateOptionsMenu();
            return true;
        } else if (id == R.id.menu_list) {
            state.mode = DisplayState.MODE_LIST;
            updateMode();
            getFragmentManager().invalidateOptionsMenu();
            return true;
        } else if (id == R.id.menu_sort) {
            SortFragment.show(this);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void updateMode() {
        final DisplayState state = getDisplayState(this);

        mListView.setVisibility(state.mode == DisplayState.MODE_LIST ? View.VISIBLE : View.GONE);
        mGridView.setVisibility(state.mode == DisplayState.MODE_GRID ? View.VISIBLE : View.GONE);

        final int choiceMode;
        if (state.allowMultiple) {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL;
        } else {
            choiceMode = ListView.CHOICE_MODE_NONE;
        }

        if (state.mode == DisplayState.MODE_GRID) {
            mListView.setAdapter(null);
            mListView.setChoiceMode(ListView.CHOICE_MODE_NONE);
            mGridView.setAdapter(mAdapter);
            mGridView.setColumnWidth(getResources().getDimensionPixelSize(R.dimen.grid_width));
            mGridView.setNumColumns(GridView.AUTO_FIT);
            mGridView.setChoiceMode(choiceMode);
            mCurrentView = mGridView;
        } else if (state.mode == DisplayState.MODE_LIST) {
            mGridView.setAdapter(null);
            mGridView.setChoiceMode(ListView.CHOICE_MODE_NONE);
            mListView.setAdapter(mAdapter);
            mListView.setChoiceMode(choiceMode);
            mCurrentView = mListView;
        } else {
            throw new IllegalStateException();
        }
    }

    private void updateSortBy() {
        getLoaderManager().restartLoader(LOADER_DOCUMENTS, getArguments(), mCallbacks);
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final Cursor cursor = (Cursor) mAdapter.getItem(position);
            final Uri uri = getArguments().getParcelable(EXTRA_URI);
            final Document doc = Document.fromCursor(uri.getAuthority(), cursor);
            ((DocumentsActivity) getActivity()).onDocumentPicked(doc);
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
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.menu_open) {
                final Uri uri = getArguments().getParcelable(EXTRA_URI);
                final SparseBooleanArray checked = mCurrentView.getCheckedItemPositions();
                final ArrayList<Document> docs = Lists.newArrayList();

                final int size = checked.size();
                for (int i = 0; i < size; i++) {
                    if (checked.valueAt(i)) {
                        final Cursor cursor = (Cursor) mAdapter.getItem(checked.keyAt(i));
                        docs.add(Document.fromCursor(uri.getAuthority(), cursor));
                    }
                }

                ((DocumentsActivity) getActivity()).onDocumentsPicked(docs);
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
                final Cursor cursor = (Cursor) mAdapter.getItem(position);
                final String mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);

                // Directories cannot be checked
                if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(mimeType)) {
                    mCurrentView.setItemChecked(position, false);
                }
            }

            mode.setTitle(getResources()
                    .getString(R.string.mode_selected_count, mCurrentView.getCheckedItemCount()));
        }
    };

    private static DisplayState getDisplayState(Fragment fragment) {
        return ((DocumentsActivity) fragment.getActivity()).getDisplayState();
    }

    private class DocumentsAdapter extends CursorAdapter {
        public DocumentsAdapter(Context context) {
            super(context, null, false);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            final LayoutInflater inflater = LayoutInflater.from(context);
            final DisplayState state = getDisplayState(DirectoryFragment.this);
            if (state.mode == DisplayState.MODE_LIST) {
                return inflater.inflate(R.layout.item_doc_list, parent, false);
            } else if (state.mode == DisplayState.MODE_GRID) {
                return inflater.inflate(R.layout.item_doc_grid, parent, false);
            } else {
                throw new IllegalStateException();
            }
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            final TextView title = (TextView) view.findViewById(android.R.id.title);
            final TextView summary = (TextView) view.findViewById(android.R.id.summary);
            final ImageView icon = (ImageView) view.findViewById(android.R.id.icon);

            final String guid = getCursorString(cursor, DocumentColumns.GUID);
            final String displayName = getCursorString(cursor, DocumentColumns.DISPLAY_NAME);
            final String mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);
            final long lastModified = getCursorLong(cursor, DocumentColumns.LAST_MODIFIED);
            final int flags = getCursorInt(cursor, DocumentColumns.FLAGS);

            if ((flags & DocumentsContract.FLAG_SUPPORTS_THUMBNAIL) != 0) {
                final Uri uri = getArguments().getParcelable(EXTRA_URI);
                final Uri childUri = DocumentsContract.buildDocumentUri(uri.getAuthority(), guid);
                icon.setImageURI(childUri);
            } else {
                icon.setImageDrawable(DocumentsActivity.resolveDocumentIcon(context, mimeType));
            }

            title.setText(displayName);
            if (summary != null) {
                summary.setText(DateUtils.getRelativeTimeSpanString(lastModified));
            }
        }
    }

    public static class SortFragment extends DialogFragment {
        public static void show(DirectoryFragment parent) {
            if (!parent.isAdded()) return;

            final SortFragment dialog = new SortFragment();
            dialog.setTargetFragment(parent, 0);
            dialog.show(parent.getFragmentManager(), TAG_SORT);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();
            final DisplayState state = getDisplayState(this);

            final AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(R.string.menu_sort);
            builder.setSingleChoiceItems(new CharSequence[] {
                    getText(R.string.sort_name),
                    getText(R.string.sort_date),
            }, state.sortBy, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    state.sortBy = which;
                    ((DirectoryFragment) getTargetFragment()).updateSortBy();
                    dismiss();
                }
            });

            return builder.create();
        }
    }

    private static int getDocumentFlags(Context context, Uri uri) {
        final Cursor cursor = context.getContentResolver().query(uri, new String[] {
                DocumentColumns.FLAGS }, null, null, null);
        try {
            if (cursor.moveToFirst()) {
                return getCursorInt(cursor, DocumentColumns.FLAGS);
            } else {
                return 0;
            }
        } finally {
            cursor.close();
        }
    }

    public static String getCursorString(Cursor cursor, String columnName) {
        return cursor.getString(cursor.getColumnIndex(columnName));
    }

    public static long getCursorLong(Cursor cursor, String columnName) {
        return cursor.getLong(cursor.getColumnIndex(columnName));
    }

    public static int getCursorInt(Cursor cursor, String columnName) {
        return cursor.getInt(cursor.getColumnIndex(columnName));
    }
}
