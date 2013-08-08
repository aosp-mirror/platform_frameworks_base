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
import android.content.Context;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.android.documentsui.SectionedListAdapter.SectionAdapter;
import com.android.documentsui.model.Root;
import com.android.documentsui.model.Root.RootComparator;

import java.util.Collection;

/**
 * Display list of known storage backend roots.
 */
public class RootsFragment extends Fragment {

    private ListView mList;
    private SectionedRootsAdapter mAdapter;

    public static void show(FragmentManager fm) {
        final RootsFragment fragment = new RootsFragment();

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_roots, fragment);
        ft.commitAllowingStateLoss();
    }

    public static RootsFragment get(FragmentManager fm) {
        return (RootsFragment) fm.findFragmentById(R.id.container_roots);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();

        final View view = inflater.inflate(R.layout.fragment_roots, container, false);
        mList = (ListView) view.findViewById(android.R.id.list);

        mAdapter = new SectionedRootsAdapter(context, RootsCache.getRoots(context));
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener(mItemListener);

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();

        final Context context = getActivity();
        mAdapter.setShowAdvanced(SettingsActivity.getDisplayAdvancedDevices(context));
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final Root root = (Root) mAdapter.getItem(position);
            ((DocumentsActivity) getActivity()).onRootPicked(root, true);
        }
    };

    public static class RootsAdapter extends ArrayAdapter<Root> implements SectionAdapter {
        private int mHeaderId;

        public RootsAdapter(Context context, int headerId) {
            super(context, 0);
            mHeaderId = headerId;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(R.layout.item_root, parent, false);
            }

            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);

            final Root root = getItem(position);
            icon.setImageDrawable(root.icon);
            title.setText(root.title);

            // Device summary is always available space
            final String summaryText;
            if ((root.rootType == DocumentsContract.ROOT_TYPE_DEVICE
                    || root.rootType == DocumentsContract.ROOT_TYPE_DEVICE_ADVANCED)
                    && root.availableBytes >= 0) {
                summaryText = context.getString(R.string.root_available_bytes,
                        Formatter.formatFileSize(context, root.availableBytes));
            } else {
                summaryText = root.summary;
            }

            summary.setText(summaryText);
            summary.setVisibility(summaryText != null ? View.VISIBLE : View.GONE);

            return convertView;
        }

        @Override
        public View getHeaderView(View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_root_header, parent, false);
            }

            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            title.setText(mHeaderId);

            return convertView;
        }
    }

    public static class SectionedRootsAdapter extends SectionedListAdapter {
        private final RootsAdapter mServices;
        private final RootsAdapter mShortcuts;
        private final RootsAdapter mDevices;
        private final RootsAdapter mDevicesAdvanced;

        public SectionedRootsAdapter(Context context, Collection<Root> roots) {
            mServices = new RootsAdapter(context, R.string.root_type_service);
            mShortcuts = new RootsAdapter(context, R.string.root_type_shortcut);
            mDevices = new RootsAdapter(context, R.string.root_type_device);
            mDevicesAdvanced = new RootsAdapter(context, R.string.root_type_device);

            for (Root root : roots) {
                Log.d(TAG, "Found rootType=" + root.rootType);
                switch (root.rootType) {
                    case DocumentsContract.ROOT_TYPE_SERVICE:
                        mServices.add(root);
                        break;
                    case DocumentsContract.ROOT_TYPE_SHORTCUT:
                        mShortcuts.add(root);
                        break;
                    case DocumentsContract.ROOT_TYPE_DEVICE:
                        mDevices.add(root);
                        mDevicesAdvanced.add(root);
                        break;
                    case DocumentsContract.ROOT_TYPE_DEVICE_ADVANCED:
                        mDevicesAdvanced.add(root);
                        break;
                }
            }

            final RootComparator comp = new RootComparator();
            mServices.sort(comp);
            mShortcuts.sort(comp);
            mDevices.sort(comp);
            mDevicesAdvanced.sort(comp);
        }

        public void setShowAdvanced(boolean showAdvanced) {
            clearSections();
            if (mServices.getCount() > 0) {
                addSection(mServices);
            }
            if (mShortcuts.getCount() > 0) {
                addSection(mShortcuts);
            }

            final RootsAdapter devices = showAdvanced ? mDevicesAdvanced : mDevices;
            if (devices.getCount() > 0) {
                addSection(devices);
            }
        }
    }
}
