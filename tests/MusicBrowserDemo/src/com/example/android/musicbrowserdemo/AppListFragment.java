/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.musicbrowserdemo;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.service.media.MediaBrowserService;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.ListFragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

// TODO: Include an icon.

public class AppListFragment extends ListFragment {

    private Adapter mAdapter;
    private List<Item> mItems;

    public AppListFragment() {
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mAdapter = new Adapter();
        setListAdapter(mAdapter);
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        final Item item = mItems.get(position);

        Log.i("AppListFragment", "Item clicked: " + position + " -- " + item.component);

        final BrowserListFragment fragment = new BrowserListFragment();

        final Bundle args = new Bundle();
        args.putParcelable(BrowserListFragment.ARG_COMPONENT, item.component);
        fragment.setArguments(args);

        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .addToBackStack(null)
                .commit();
    }

    private static class Item {
        final String label;
        final ComponentName component;

        Item(String l, ComponentName c) {
            this.label = l;
            this.component = c;
        }
    }

    private class Adapter extends BaseAdapter {
        private final LayoutInflater mInflater;

        Adapter() {
            super();

            final Context context = getActivity();
            mInflater = LayoutInflater.from(context);

            // Load the data
            final PackageManager pm = context.getPackageManager();
            final Intent intent = new Intent(MediaBrowserService.SERVICE_INTERFACE);
            final List<ResolveInfo> list = pm.queryIntentServices(intent, 0);
            final int N = list.size();
            mItems = new ArrayList(N);
            for (int i=0; i<N; i++) {
                final ResolveInfo ri = list.get(i);
                mItems.add(new Item(ri.loadLabel(pm).toString(), new ComponentName(
                            ri.serviceInfo.applicationInfo.packageName,
                            ri.serviceInfo.name)));
            }
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Item getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getItemViewType(int position) {
            return 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(android.R.layout.simple_list_item_1, parent, false);
            }

            final TextView tv = (TextView)convertView;
            final Item item = mItems.get(position);
            tv.setText(item.label);

            return convertView;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }
    }
}


