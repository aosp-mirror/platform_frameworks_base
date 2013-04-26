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

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.ListFragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.DocumentColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class DirectoryFragment extends ListFragment {
    private DocumentsAdapter mAdapter;
    private LoaderCallbacks<Cursor> mCallbacks;

    private static final String EXTRA_URI = "uri";

    private static final int LOADER_DOCUMENTS = 2;

    public static void show(FragmentManager fm, Uri uri, CharSequence title) {
        final Bundle args = new Bundle();
        args.putParcelable(EXTRA_URI, uri);

        final DirectoryFragment fragment = new DirectoryFragment();
        fragment.setArguments(args);

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(android.R.id.content, fragment);
        ft.addToBackStack(title.toString());
        ft.setBreadCrumbTitle(title);
        ft.commitAllowingStateLoss();
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();

        mAdapter = new DocumentsAdapter(context);
        setListAdapter(mAdapter);

        mCallbacks = new LoaderCallbacks<Cursor>() {
            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                final Uri uri = args.getParcelable(EXTRA_URI);
                return new CursorLoader(context, uri, null, null, null, null);
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

        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        getLoaderManager().restartLoader(LOADER_DOCUMENTS, getArguments(), mCallbacks);
    }

    @Override
    public void onStop() {
        super.onStop();
        getLoaderManager().destroyLoader(LOADER_DOCUMENTS);
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        final Cursor cursor = (Cursor) mAdapter.getItem(position);
        final String guid = getCursorString(cursor, DocumentColumns.GUID);
        final String mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);

        final Uri uri = getArguments().getParcelable(EXTRA_URI);
        final Uri childUri = DocumentsContract.buildDocumentUri(uri.getAuthority(), guid);

        if (DocumentsContract.MIME_TYPE_DIRECTORY.equals(mimeType)) {
            // Nested directory picked, recurse using new fragment
            final Uri childContentsUri = DocumentsContract.buildContentsUri(childUri);
            final String displayName = cursor.getString(
                    cursor.getColumnIndex(DocumentColumns.DISPLAY_NAME));
            DirectoryFragment.show(getFragmentManager(), childContentsUri, displayName);
        } else {
            // Explicit file picked, return
            ((DocumentsActivity) getActivity()).onDocumentPicked(childUri);
        }
    }

    private class DocumentsAdapter extends CursorAdapter {
        public DocumentsAdapter(Context context) {
            super(context, null, false);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return LayoutInflater.from(context)
                    .inflate(com.android.internal.R.layout.preference, parent, false);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            final TextView title = (TextView) view.findViewById(android.R.id.title);
            final TextView summary = (TextView) view.findViewById(android.R.id.summary);
            final ImageView icon = (ImageView) view.findViewById(android.R.id.icon);

            icon.setMaxWidth(128);
            icon.setMaxHeight(128);

            final String guid = getCursorString(cursor, DocumentColumns.GUID);
            final String displayName = getCursorString(cursor, DocumentColumns.DISPLAY_NAME);
            final String mimeType = getCursorString(cursor, DocumentColumns.MIME_TYPE);
            final int flags = getCursorInt(cursor, DocumentColumns.FLAGS);

            if ((flags & DocumentsContract.FLAG_SUPPORTS_THUMBNAIL) != 0) {
                final Uri uri = getArguments().getParcelable(EXTRA_URI);
                final Uri childUri = DocumentsContract.buildDocumentUri(uri.getAuthority(), guid);
                icon.setImageURI(childUri);
            } else {
                icon.setImageURI(null);
            }

            title.setText(displayName);
            summary.setText(mimeType);
        }
    }
    
    private static String getCursorString(Cursor cursor, String columnName) {
        return cursor.getString(cursor.getColumnIndex(columnName));
    }

    private static int getCursorInt(Cursor cursor, String columnName) {
        return cursor.getInt(cursor.getColumnIndex(columnName));
    }
}
