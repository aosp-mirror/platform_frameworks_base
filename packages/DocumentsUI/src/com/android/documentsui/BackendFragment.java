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
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.provider.DocumentsContract;
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
 * Display all known storage backends.
 */
public class BackendFragment extends Fragment {

    // TODO: handle multiple accounts from single backend

    private GridView mGridView;
    private BackendAdapter mAdapter;

    public static void show(FragmentManager fm) {
        final BackendFragment fragment = new BackendFragment();

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.directory, fragment);
        ft.setBreadCrumbTitle("TOP");
        ft.commitAllowingStateLoss();
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();

        // Gather known storage providers
        final List<ProviderInfo> providers = context.getPackageManager()
                .queryContentProviders(null, -1, PackageManager.GET_META_DATA);
        final List<ProviderInfo> backends = Lists.newArrayList();
        for (ProviderInfo info : providers) {
            if (info.metaData != null
                    && info.metaData.containsKey(DocumentsContract.META_DATA_DOCUMENT_PROVIDER)) {
                backends.add(info);
            }
        }

        final View view = inflater.inflate(R.layout.fragment_backend, container, false);

        mGridView = (GridView) view.findViewById(R.id.grid);
        mGridView.setOnItemClickListener(mItemListener);

        mAdapter = new BackendAdapter(context, backends);
        mGridView.setAdapter(mAdapter);
        mGridView.setNumColumns(GridView.AUTO_FIT);

        return view;
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final ProviderInfo info = mAdapter.getItem(position);
            ((DocumentsActivity) getActivity()).onBackendPicked(info);
        }
    };

    public static class BackendAdapter extends ArrayAdapter<ProviderInfo> {
        public BackendAdapter(Context context, List<ProviderInfo> list) {
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
            final ProviderInfo info = getItem(position);
            icon.setImageDrawable(info.loadIcon(pm));
            text1.setText(info.loadLabel(pm));

            return convertView;
        }
    }
}
