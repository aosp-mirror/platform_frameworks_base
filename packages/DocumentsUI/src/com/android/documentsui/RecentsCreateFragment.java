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

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.DocumentsContract.DocumentRoot;
import android.text.TextUtils.TruncateAt;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.android.documentsui.model.DocumentStack;
import com.google.android.collect.Lists;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Display directories where recent creates took place.
 */
public class RecentsCreateFragment extends Fragment {

    private ListView mListView;

    private DocumentStackAdapter mAdapter;
    private LoaderCallbacks<List<DocumentStack>> mCallbacks;

    private static final int LOADER_RECENTS = 3;

    public static void show(FragmentManager fm) {
        final RecentsCreateFragment fragment = new RecentsCreateFragment();
        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_directory, fragment);
        ft.commitAllowingStateLoss();
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();

        final View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mListView = (ListView) view.findViewById(R.id.list);
        mListView.setOnItemClickListener(mItemListener);

        mAdapter = new DocumentStackAdapter();
        mListView.setAdapter(mAdapter);

        mCallbacks = new LoaderCallbacks<List<DocumentStack>>() {
            @Override
            public Loader<List<DocumentStack>> onCreateLoader(int id, Bundle args) {
                return new RecentsCreateLoader(context);
            }

            @Override
            public void onLoadFinished(
                    Loader<List<DocumentStack>> loader, List<DocumentStack> data) {
                mAdapter.swapStacks(data);
            }

            @Override
            public void onLoaderReset(Loader<List<DocumentStack>> loader) {
                mAdapter.swapStacks(null);
            }
        };

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        getLoaderManager().restartLoader(LOADER_RECENTS, getArguments(), mCallbacks);
    }

    @Override
    public void onStop() {
        super.onStop();
        getLoaderManager().destroyLoader(LOADER_RECENTS);
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final DocumentStack stack = mAdapter.getItem(position);
            ((DocumentsActivity) getActivity()).onStackPicked(stack);
        }
    };

    public static class RecentsCreateLoader extends UriDerivativeLoader<Uri, List<DocumentStack>> {
        public RecentsCreateLoader(Context context) {
            super(context, RecentsProvider.buildRecentCreate());
        }

        @Override
        public List<DocumentStack> loadInBackground(Uri uri, CancellationSignal signal) {
            final ArrayList<DocumentStack> result = Lists.newArrayList();

            final ContentResolver resolver = getContext().getContentResolver();
            final Cursor cursor = resolver.query(
                    uri, null, null, null, RecentsProvider.COL_TIMESTAMP + " DESC", signal);
            try {
                while (cursor != null && cursor.moveToNext()) {
                    final String rawStack = cursor.getString(
                            cursor.getColumnIndex(RecentsProvider.COL_PATH));
                    try {
                        final DocumentStack stack = DocumentStack.deserialize(resolver, rawStack);
                        result.add(stack);
                    } catch (FileNotFoundException e) {
                        Log.w(TAG, "Failed to resolve stack: " + e);
                    }
                }
            } finally {
                IoUtils.closeQuietly(cursor);
            }

            return result;
        }
    }

    private class DocumentStackAdapter extends BaseAdapter {
        private List<DocumentStack> mStacks;

        public DocumentStackAdapter() {
        }

        public void swapStacks(List<DocumentStack> stacks) {
            mStacks = stacks;
            notifyDataSetChanged();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            final RootsCache roots = DocumentsApplication.getRootsCache(context);

            if (convertView == null) {
                final LayoutInflater inflater = LayoutInflater.from(context);
                convertView = inflater.inflate(R.layout.item_doc_list, parent, false);
            }

            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final View summaryList = convertView.findViewById(R.id.summary_list);

            final DocumentStack stack = getItem(position);
            final DocumentRoot root = stack.getRoot(roots);
            icon.setImageDrawable(root.loadIcon(context));

            final StringBuilder builder = new StringBuilder();
            for (int i = stack.size() - 1; i >= 0; i--) {
                builder.append(stack.get(i).displayName);
                if (i > 0) {
                    builder.append(" \u232a ");
                }
            }
            title.setText(builder.toString());
            title.setEllipsize(TruncateAt.MIDDLE);

            summaryList.setVisibility(View.GONE);

            return convertView;
        }

        @Override
        public int getCount() {
            return mStacks != null ? mStacks.size() : 0;
        }

        @Override
        public DocumentStack getItem(int position) {
            return mStacks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).hashCode();
        }
    }
}
