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

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.RootColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.collect.Lists;

import java.util.List;

/**
 * Display all known storage roots.
 */
public class BackendFragment extends Fragment {

    // TODO: cluster backends by type

    private GridView mGridView;
    private BackendAdapter mAdapter;

    public static void show(FragmentManager fm) {
        final BackendFragment fragment = new BackendFragment();

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.directory, fragment);
        ft.setBreadCrumbTitle("TOP");
        ft.commitAllowingStateLoss();
    }

    public static class Root {
        public int rootType;
        public Uri uri;
        public Drawable icon;
        public String title;
        public String summary;

        public static Root fromCursor(Context context, ProviderInfo info, Cursor cursor) {
            final Root root = new Root();

            root.rootType = cursor.getInt(cursor.getColumnIndex(RootColumns.ROOT_TYPE));
            root.uri = DocumentsContract.buildDocumentUri(
                    info.authority, cursor.getString(cursor.getColumnIndex(RootColumns.GUID)));

            final PackageManager pm = context.getPackageManager();
            final int icon = cursor.getInt(cursor.getColumnIndex(RootColumns.ICON));
            if (icon != 0) {
                try {
                    root.icon = pm.getResourcesForApplication(info.applicationInfo)
                            .getDrawable(icon);
                } catch (NotFoundException e) {
                    throw new RuntimeException(e);
                } catch (NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            } else {
                root.icon = info.loadIcon(pm);
            }

            root.title = cursor.getString(cursor.getColumnIndex(RootColumns.TITLE));
            if (root.title == null) {
                root.title = info.loadLabel(pm).toString();
            }

            root.summary = cursor.getString(cursor.getColumnIndex(RootColumns.SUMMARY));

            return root;
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();

        // Gather roots from known storage providers
        final List<ProviderInfo> providers = context.getPackageManager()
                .queryContentProviders(null, -1, PackageManager.GET_META_DATA);
        final List<Root> roots = Lists.newArrayList();
        for (ProviderInfo info : providers) {
            if (info.metaData != null
                    && info.metaData.containsKey(DocumentsContract.META_DATA_DOCUMENT_PROVIDER)) {
                // TODO: populate roots on background thread, and cache results
                final Uri uri = DocumentsContract.buildRootsUri(info.authority);
                final Cursor cursor = context.getContentResolver()
                        .query(uri, null, null, null, null);
                try {
                    while (cursor.moveToNext()) {
                        roots.add(Root.fromCursor(context, info, cursor));
                    }
                } finally {
                    cursor.close();
                }
            }
        }

        final View view = inflater.inflate(R.layout.fragment_backend, container, false);

        mGridView = (GridView) view.findViewById(R.id.grid);
        mGridView.setOnItemClickListener(mItemListener);

        mAdapter = new BackendAdapter(context, roots);
        mGridView.setAdapter(mAdapter);
        mGridView.setNumColumns(GridView.AUTO_FIT);

        return view;
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final Root root = mAdapter.getItem(position);
            ((DocumentsActivity) getActivity()).onRootPicked(root);
        }
    };

    public static class BackendAdapter extends ArrayAdapter<Root> {
        public BackendAdapter(Context context, List<Root> list) {
            super(context, android.R.layout.simple_list_item_1, list);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_backend, parent, false);
            }

            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView text1 = (TextView) convertView.findViewById(android.R.id.text1);

            final PackageManager pm = parent.getContext().getPackageManager();
            final Root root = getItem(position);
            icon.setImageDrawable(root.icon);
            text1.setText(root.title);

            return convertView;
        }
    }
}
