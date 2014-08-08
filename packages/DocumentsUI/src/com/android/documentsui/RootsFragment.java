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

import static com.android.documentsui.DocumentsActivity.State.ACTION_GET_CONTENT;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.android.documentsui.DocumentsActivity.State;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;
import com.google.common.collect.Lists;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Display list of known storage backend roots.
 */
public class RootsFragment extends Fragment {

    private ListView mList;
    private RootsAdapter mAdapter;

    private LoaderCallbacks<Collection<RootInfo>> mCallbacks;

    private static final String EXTRA_INCLUDE_APPS = "includeApps";

    public static void show(FragmentManager fm, Intent includeApps) {
        final Bundle args = new Bundle();
        args.putParcelable(EXTRA_INCLUDE_APPS, includeApps);

        final RootsFragment fragment = new RootsFragment();
        fragment.setArguments(args);

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
        mList.setOnItemClickListener(mItemListener);
        mList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Context context = getActivity();
        final RootsCache roots = DocumentsApplication.getRootsCache(context);
        final State state = ((DocumentsActivity) context).getDisplayState();

        mCallbacks = new LoaderCallbacks<Collection<RootInfo>>() {
            @Override
            public Loader<Collection<RootInfo>> onCreateLoader(int id, Bundle args) {
                return new RootsLoader(context, roots, state);
            }

            @Override
            public void onLoadFinished(
                    Loader<Collection<RootInfo>> loader, Collection<RootInfo> result) {
                if (!isAdded()) return;

                final Intent includeApps = getArguments().getParcelable(EXTRA_INCLUDE_APPS);

                mAdapter = new RootsAdapter(context, result, includeApps);
                mList.setAdapter(mAdapter);

                onCurrentRootChanged();
            }

            @Override
            public void onLoaderReset(Loader<Collection<RootInfo>> loader) {
                mAdapter = null;
                mList.setAdapter(null);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        onDisplayStateChanged();
    }

    public void onDisplayStateChanged() {
        final Context context = getActivity();
        final State state = ((DocumentsActivity) context).getDisplayState();

        if (state.action == ACTION_GET_CONTENT) {
            mList.setOnItemLongClickListener(mItemLongClickListener);
        } else {
            mList.setOnItemLongClickListener(null);
            mList.setLongClickable(false);
        }

        getLoaderManager().restartLoader(2, null, mCallbacks);
    }

    public void onCurrentRootChanged() {
        if (mAdapter == null) return;

        final RootInfo root = ((DocumentsActivity) getActivity()).getCurrentRoot();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            final Object item = mAdapter.getItem(i);
            if (item instanceof RootItem) {
                final RootInfo testRoot = ((RootItem) item).root;
                if (Objects.equals(testRoot, root)) {
                    mList.setItemChecked(i, true);
                    return;
                }
            }
        }
    }

    private void showAppDetails(ResolveInfo ri) {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", ri.activityInfo.packageName, null));
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        startActivity(intent);
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final DocumentsActivity activity = DocumentsActivity.get(RootsFragment.this);
            final Item item = mAdapter.getItem(position);
            if (item instanceof RootItem) {
                activity.onRootPicked(((RootItem) item).root, true);
            } else if (item instanceof AppItem) {
                activity.onAppPicked(((AppItem) item).info);
            } else {
                throw new IllegalStateException("Unknown root: " + item);
            }
        }
    };

    private OnItemLongClickListener mItemLongClickListener = new OnItemLongClickListener() {
        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
            final Item item = mAdapter.getItem(position);
            if (item instanceof AppItem) {
                showAppDetails(((AppItem) item).info);
                return true;
            } else {
                return false;
            }
        }
    };

    private static abstract class Item {
        private final int mLayoutId;

        public Item(int layoutId) {
            mLayoutId = layoutId;
        }

        public View getView(View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(mLayoutId, parent, false);
            }
            bindView(convertView);
            return convertView;
        }

        public abstract void bindView(View convertView);
    }

    private static class RootItem extends Item {
        public final RootInfo root;

        public RootItem(RootInfo root) {
            super(R.layout.item_root);
            this.root = root;
        }

        @Override
        public void bindView(View convertView) {
            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);

            final Context context = convertView.getContext();
            icon.setImageDrawable(root.loadIcon(context));
            title.setText(root.title);

            // Show available space if no summary
            String summaryText = root.summary;
            if (TextUtils.isEmpty(summaryText) && root.availableBytes >= 0) {
                summaryText = context.getString(R.string.root_available_bytes,
                        Formatter.formatFileSize(context, root.availableBytes));
            }

            summary.setText(summaryText);
            summary.setVisibility(TextUtils.isEmpty(summaryText) ? View.GONE : View.VISIBLE);
        }
    }

    private static class SpacerItem extends Item {
        public SpacerItem() {
            super(R.layout.item_root_spacer);
        }

        @Override
        public void bindView(View convertView) {
            // Nothing to bind
        }
    }

    private static class AppItem extends Item {
        public final ResolveInfo info;

        public AppItem(ResolveInfo info) {
            super(R.layout.item_root);
            this.info = info;
        }

        @Override
        public void bindView(View convertView) {
            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);

            final PackageManager pm = convertView.getContext().getPackageManager();
            icon.setImageDrawable(info.loadIcon(pm));
            title.setText(info.loadLabel(pm));

            // TODO: match existing summary behavior from disambig dialog
            summary.setVisibility(View.GONE);
        }
    }

    private static class RootsAdapter extends ArrayAdapter<Item> {
        public RootsAdapter(Context context, Collection<RootInfo> roots, Intent includeApps) {
            super(context, 0);

            RootItem recents = null;
            RootItem images = null;
            RootItem videos = null;
            RootItem audio = null;
            RootItem downloads = null;

            final List<RootInfo> clouds = Lists.newArrayList();
            final List<RootInfo> locals = Lists.newArrayList();

            for (RootInfo root : roots) {
                if (root.isRecents()) {
                    recents = new RootItem(root);
                } else if (root.isExternalStorage()) {
                    locals.add(root);
                } else if (root.isDownloads()) {
                    downloads = new RootItem(root);
                } else if (root.isImages()) {
                    images = new RootItem(root);
                } else if (root.isVideos()) {
                    videos = new RootItem(root);
                } else if (root.isAudio()) {
                    audio = new RootItem(root);
                } else {
                    clouds.add(root);
                }
            }

            final RootComparator comp = new RootComparator();
            Collections.sort(clouds, comp);
            Collections.sort(locals, comp);

            if (recents != null) add(recents);

            for (RootInfo cloud : clouds) {
                add(new RootItem(cloud));
            }

            if (images != null) add(images);
            if (videos != null) add(videos);
            if (audio != null) add(audio);
            if (downloads != null) add(downloads);

            for (RootInfo local : locals) {
                add(new RootItem(local));
            }

            if (includeApps != null) {
                final PackageManager pm = context.getPackageManager();
                final List<ResolveInfo> infos = pm.queryIntentActivities(
                        includeApps, PackageManager.MATCH_DEFAULT_ONLY);

                final List<AppItem> apps = Lists.newArrayList();

                // Omit ourselves from the list
                for (ResolveInfo info : infos) {
                    if (!context.getPackageName().equals(info.activityInfo.packageName)) {
                        apps.add(new AppItem(info));
                    }
                }

                if (apps.size() > 0) {
                    add(new SpacerItem());
                    for (Item item : apps) {
                        add(item);
                    }
                }
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Item item = getItem(position);
            return item.getView(convertView, parent);
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItemViewType(position) != 1;
        }

        @Override
        public int getItemViewType(int position) {
            final Item item = getItem(position);
            if (item instanceof RootItem || item instanceof AppItem) {
                return 0;
            } else {
                return 1;
            }
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }
    }

    public static class RootComparator implements Comparator<RootInfo> {
        @Override
        public int compare(RootInfo lhs, RootInfo rhs) {
            final int score = DocumentInfo.compareToIgnoreCaseNullable(lhs.title, rhs.title);
            if (score != 0) {
                return score;
            } else {
                return DocumentInfo.compareToIgnoreCaseNullable(lhs.summary, rhs.summary);
            }
        }
    }
}
