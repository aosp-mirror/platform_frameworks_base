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
import android.media.browse.MediaBrowser;
import android.service.media.MediaBrowserService;
import android.os.Bundle;
import android.net.Uri;
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

public class BrowserListFragment extends ListFragment {
    private static final String TAG = "BrowserListFragment";

    // Hints
    public static final String HINT_DISPLAY = "com.example.android.musicbrowserdemo.DISPLAY";

    // For args
    public static final String ARG_COMPONENT = "component";
    public static final String ARG_ID = "uri";

    private Adapter mAdapter;
    private List<Item> mItems = new ArrayList();
    private ComponentName mComponent;
    private String mNodeId;
    private MediaBrowser mBrowser;

    private static class Item {
        final MediaBrowser.MediaItem media;

        Item(MediaBrowser.MediaItem m) {
            this.media = m;
        }
    }

    public BrowserListFragment() {
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Log.d(TAG, "onActivityCreated -- " + hashCode());
        mAdapter = new Adapter();
        setListAdapter(mAdapter);

        // Get our arguments
        final Bundle args = getArguments();
        mComponent = args.getParcelable(ARG_COMPONENT);
        mNodeId = args.getString(ARG_ID);

        // A hint about who we are, so the service can customize the results if it wants to.
        final Bundle rootHints = new Bundle();
        rootHints.putBoolean(HINT_DISPLAY, true);

        mBrowser = new MediaBrowser(getActivity(), mComponent, mConnectionCallbacks, rootHints);
    }

    @Override
    public void onStart() {
        super.onStart();
        mBrowser.connect();
    }

    @Override
    public void onStop() {
        super.onStop();
        mBrowser.disconnect();
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        final Item item = mItems.get(position);

        Log.i("BrowserListFragment", "Item clicked: " + position + " -- "
                + mAdapter.getItem(position).media.getDescription().getIconUri());

        final BrowserListFragment fragment = new BrowserListFragment();

        final Bundle args = new Bundle();
        args.putParcelable(BrowserListFragment.ARG_COMPONENT, mComponent);
        args.putParcelable(BrowserListFragment.ARG_ID, item.media.getDescription().getIconUri());
        fragment.setArguments(args);

        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                .addToBackStack(null)
                .commit();

    }

    final MediaBrowser.ConnectionCallback mConnectionCallbacks
            = new MediaBrowser.ConnectionCallback() {
        @Override
        public void onConnected() {
            Log.d(TAG, "mConnectionCallbacks.onConnected");
            if (mNodeId == null) {
                mNodeId = mBrowser.getRoot();
            }
            mBrowser.subscribe(mNodeId, new MediaBrowser.SubscriptionCallback() {
                    @Override
                public void onChildrenLoaded(String parentId,
                            List<MediaBrowser.MediaItem> children) {
                    Log.d(TAG, "onChildrenLoaded parentId=" + parentId
                                + " children= " + children);
                        mItems.clear();
                        final int N = children.size();
                        for (int i=0; i<N; i++) {
                            mItems.add(new Item(children.get(i)));
                        }
                        mAdapter.notifyDataSetChanged();
                    }

                    @Override
                public void onError(String parentId) {
                    Log.d(TAG, "onError parentId=" + parentId);
                    }
                });
        }

        @Override
        public void onConnectionSuspended() {
            Log.d(TAG, "mConnectionCallbacks.onConnectionSuspended");
        }

        @Override
        public void onConnectionFailed() {
            Log.d(TAG, "mConnectionCallbacks.onConnectionFailed");
        }
    };

    private class Adapter extends BaseAdapter {
        private final LayoutInflater mInflater;

        Adapter() {
            super();

            final Context context = getActivity();
            mInflater = LayoutInflater.from(context);
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
            tv.setText(item.media.getDescription().getTitle());

            return convertView;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }
    }
}


