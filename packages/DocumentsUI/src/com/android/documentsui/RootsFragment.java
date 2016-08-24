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

import static com.android.documentsui.Shared.DEBUG;

import android.app.Activity;
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
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
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

import com.android.documentsui.model.RootInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Display list of known storage backend roots.
 */
public class RootsFragment extends Fragment {

    private static final String TAG = "RootsFragment";
    private static final String EXTRA_INCLUDE_APPS = "includeApps";

    private ListView mList;
    private RootsAdapter mAdapter;
    private LoaderCallbacks<Collection<RootInfo>> mCallbacks;


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

        final View view = inflater.inflate(R.layout.fragment_roots, container, false);
        mList = (ListView) view.findViewById(R.id.roots_list);
        mList.setOnItemClickListener(mItemListener);
        mList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Context context = getActivity();
        final RootsCache roots = DocumentsApplication.getRootsCache(context);
        final State state = ((BaseActivity) context).getDisplayState();

        mCallbacks = new LoaderCallbacks<Collection<RootInfo>>() {
            @Override
            public Loader<Collection<RootInfo>> onCreateLoader(int id, Bundle args) {
                return new RootsLoader(context, roots, state);
            }

            @Override
            public void onLoadFinished(
                    Loader<Collection<RootInfo>> loader, Collection<RootInfo> result) {
                if (!isAdded()) {
                    return;
                }

                Intent handlerAppIntent = getArguments().getParcelable(EXTRA_INCLUDE_APPS);

                mAdapter = new RootsAdapter(context, result, handlerAppIntent, state);
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
        final State state = ((BaseActivity) context).getDisplayState();

        if (state.action == State.ACTION_GET_CONTENT) {
            mList.setOnItemLongClickListener(mItemLongClickListener);
        } else {
            mList.setOnItemLongClickListener(null);
            mList.setLongClickable(false);
        }

        getLoaderManager().restartLoader(2, null, mCallbacks);
    }

    public void onCurrentRootChanged() {
        if (mAdapter == null) {
            return;
        }

        final RootInfo root = ((BaseActivity) getActivity()).getCurrentRoot();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            final Object item = mAdapter.getItem(i);
            if (item instanceof RootItem) {
                final RootInfo testRoot = ((RootItem) item).root;
                if (Objects.equals(testRoot, root)) {
                    mList.setItemChecked(i, true);
                    mList.setSelection(i);
                    return;
                }
            }
        }
    }

    /**
     * Attempts to shift focus back to the navigation drawer.
     */
    public void requestFocus() {
        mList.requestFocus();
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
            Item item = mAdapter.getItem(position);
            if (item instanceof RootItem) {
                BaseActivity activity = BaseActivity.get(RootsFragment.this);
                RootInfo newRoot = ((RootItem) item).root;
                Metrics.logRootVisited(getActivity(), newRoot);
                activity.onRootPicked(newRoot);
            } else if (item instanceof AppItem) {
                DocumentsActivity activity = DocumentsActivity.get(RootsFragment.this);
                ResolveInfo info = ((AppItem) item).info;
                Metrics.logAppVisited(getActivity(), info);
                activity.onAppPicked(info);
            } else if (item instanceof SpacerItem) {
                if (DEBUG) Log.d(TAG, "Ignoring click on spacer item.");
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
            // Disable recycling views because 1) it's very unlikely a view can be recycled here;
            // 2) there is no easy way for us to know with which layout id the convertView was
            // inflated; and 3) simplicity is much appreciated at this time.
            convertView = LayoutInflater.from(parent.getContext())
                        .inflate(mLayoutId, parent, false);
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
            icon.setImageDrawable(root.loadDrawerIcon(context));
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

        /**
         * @param handlerAppIntent When not null, apps capable of handling the original
         *     intent will be included in list of roots (in special section at bottom).
         */
        public RootsAdapter(Context context, Collection<RootInfo> roots,
                @Nullable Intent handlerAppIntent, State state) {
            super(context, 0);

            final List<RootItem> libraries = new ArrayList<>();
            final List<RootItem> others = new ArrayList<>();

            for (final RootInfo root : roots) {
                final RootItem item = new RootItem(root);

                if (root.isHome() &&
                        !Shared.shouldShowDocumentsRoot(context, ((Activity) context).getIntent())) {
                    continue;
                } else if (root.isLibrary()) {
                    if (DEBUG) Log.d(TAG, "Adding " + root + " as library.");
                    libraries.add(item);
                } else {
                    if (DEBUG) Log.d(TAG, "Adding " + root + " as non-library.");
                    others.add(item);
                }
            }

            final RootComparator comp = new RootComparator();
            Collections.sort(libraries, comp);
            Collections.sort(others, comp);

            addAll(libraries);
            // Only add the spacer if it is actually separating something.
            if (!libraries.isEmpty() && !others.isEmpty()) {
                add(new SpacerItem());
            }
            addAll(others);

            // Include apps that can handle this intent too.
            if (handlerAppIntent != null) {
                includeHandlerApps(context, handlerAppIntent);
            }
        }

        /**
         * Adds apps capable of handling the original intent will be included
         * in list of roots (in special section at bottom).
         */
        private void includeHandlerApps(Context context, Intent handlerAppIntent) {
            final PackageManager pm = context.getPackageManager();
            final List<ResolveInfo> infos = pm.queryIntentActivities(
                    handlerAppIntent, PackageManager.MATCH_DEFAULT_ONLY);

            final List<AppItem> apps = new ArrayList<>();

            // Omit ourselves from the list
            for (ResolveInfo info : infos) {
                if (!context.getPackageName().equals(info.activityInfo.packageName)) {
                    apps.add(new AppItem(info));
                }
            }

            if (apps.size() > 0) {
                add(new SpacerItem());
                addAll(apps);
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

    public static class RootComparator implements Comparator<RootItem> {
        @Override
        public int compare(RootItem lhs, RootItem rhs) {
            return lhs.root.compareTo(rhs.root);
        }
    }
}
