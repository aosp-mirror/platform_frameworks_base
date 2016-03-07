/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.printspooler.ui;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ListActivity;
import android.app.LoaderManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintManager;
import android.print.PrintServicesLoader;
import android.printservice.PrintServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.printspooler.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This is an activity for adding a printer or. It consists of a list fed from three adapters:
 * <ul>
 *     <li>{@link #mEnabledServicesAdapter} for all enabled services. If a service has an {@link
 *         PrintServiceInfo#getAddPrintersActivityName() add printer activity} this is started
 *         when the item is clicked.</li>
 *     <li>{@link #mDisabledServicesAdapter} for all disabled services. Once clicked the settings page
 *         for this service is opened.</li>
 *     <li>{@link RecommendedServicesAdapter} for a link to all services. If this item is clicked
 *         the market app is opened to show all print services.</li>
 * </ul>
 */
public class AddPrinterActivity extends ListActivity implements
        LoaderManager.LoaderCallbacks<List<PrintServiceInfo>>,
        AdapterView.OnItemClickListener {
    private static final String LOG_TAG = "AddPrinterActivity";

    /** Ids for the loaders */
    private static final int LOADER_ID_ENABLED_SERVICES = 1;
    private static final int LOADER_ID_DISABLED_SERVICES = 2;

    /**
     * The enabled services list. This is filled from the {@link #LOADER_ID_ENABLED_SERVICES}
     * loader in {@link #onLoadFinished}.
     */
    private EnabledServicesAdapter mEnabledServicesAdapter;

    /**
     * The disabled services list. This is filled from the {@link #LOADER_ID_DISABLED_SERVICES}
     * loader in {@link #onLoadFinished}.
     */
    private DisabledServicesAdapter mDisabledServicesAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.add_printer_activity);

        mEnabledServicesAdapter = new EnabledServicesAdapter();
        mDisabledServicesAdapter = new DisabledServicesAdapter();

        ArrayList<ActionAdapter> adapterList = new ArrayList<>(3);
        adapterList.add(mEnabledServicesAdapter);
        adapterList.add(new RecommendedServicesAdapter());
        adapterList.add(mDisabledServicesAdapter);

        setListAdapter(new CombinedAdapter(adapterList));

        getListView().setOnItemClickListener(this);

        getLoaderManager().initLoader(LOADER_ID_ENABLED_SERVICES, null, this);
        getLoaderManager().initLoader(LOADER_ID_DISABLED_SERVICES, null, this);
        // TODO: Load recommended services
    }

    @Override
    public Loader<List<PrintServiceInfo>> onCreateLoader(int id, Bundle args) {
        switch (id) {
            case LOADER_ID_ENABLED_SERVICES:
                return new PrintServicesLoader(
                        (PrintManager) getSystemService(Context.PRINT_SERVICE), this,
                        PrintManager.ENABLED_SERVICES);
            case LOADER_ID_DISABLED_SERVICES:
                return new PrintServicesLoader(
                        (PrintManager) getSystemService(Context.PRINT_SERVICE), this,
                        PrintManager.DISABLED_SERVICES);
            // TODO: Load recommended services
            default:
                // not reached
                return null;
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        ((ActionAdapter) getListAdapter()).performAction(position);
    }

    @Override
    public void onLoadFinished(Loader<List<PrintServiceInfo>> loader,
            List<PrintServiceInfo> data) {
        switch (loader.getId()) {
            case LOADER_ID_ENABLED_SERVICES:
                mEnabledServicesAdapter.updateData(data);
                break;
            case LOADER_ID_DISABLED_SERVICES:
                mDisabledServicesAdapter.updateData(data);
                break;
            // TODO: Load recommended services
            default:
                // not reached
        }
    }

    @Override
    public void onLoaderReset(Loader<List<PrintServiceInfo>> loader) {
        if (!isFinishing()) {
            switch (loader.getId()) {
                case LOADER_ID_ENABLED_SERVICES:
                    mEnabledServicesAdapter.updateData(null);
                    break;
                case LOADER_ID_DISABLED_SERVICES:
                    mDisabledServicesAdapter.updateData(null);
                    break;
                // TODO: Reset recommended services
                default:
                    // not reached
            }
        }
    }

    /**
     * Marks an adapter that can can perform an action for a position in it's list.
     */
    private abstract class ActionAdapter extends BaseAdapter {
        /**
         * Perform the action for a position in the list.
         *
         * @param position The position of the item
         */
        abstract void performAction(@IntRange(from = 0) int position);

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }
    }

    /**
     * An adapter presenting multiple sub adapters as a single combined adapter.
     */
    private class CombinedAdapter extends ActionAdapter {
        /** The adapters to combine */
        private final @NonNull ArrayList<ActionAdapter> mAdapters;

        /**
         * Create a combined adapter.
         *
         * @param adapters the list of adapters to combine
         */
        CombinedAdapter(@NonNull ArrayList<ActionAdapter> adapters) {
            mAdapters = adapters;

            final int numAdapters = mAdapters.size();
            for (int i = 0; i < numAdapters; i++) {
                mAdapters.get(i).registerDataSetObserver(new DataSetObserver() {
                    @Override
                    public void onChanged() {
                        notifyDataSetChanged();
                    }

                    @Override
                    public void onInvalidated() {
                        notifyDataSetChanged();
                    }
                });
            }
        }

        @Override
        public int getCount() {
            int totalCount = 0;

            final int numAdapters = mAdapters.size();
            for (int i = 0; i < numAdapters; i++) {
                totalCount += mAdapters.get(i).getCount();
            }

            return totalCount;
        }

        /**
         * Find the sub adapter and the position in the sub-adapter the position in the combined
         * adapter refers to.
         *
         * @param position The position in the combined adapter
         *
         * @return The pair of adapter and position in sub adapter
         */
        private @NonNull Pair<ActionAdapter, Integer> getSubAdapter(int position) {
            final int numAdapters = mAdapters.size();
            for (int i = 0; i < numAdapters; i++) {
                ActionAdapter adapter = mAdapters.get(i);

                if (position < adapter.getCount()) {
                    return new Pair<>(adapter, position);
                } else {
                    position -= adapter.getCount();
                }
            }

            throw new IllegalArgumentException("Invalid position");
        }

        @Override
        public int getItemViewType(int position) {
            int numLowerViewTypes = 0;

            final int numAdapters = mAdapters.size();
            for (int i = 0; i < numAdapters; i++) {
                Adapter adapter = mAdapters.get(i);

                if (position < adapter.getCount()) {
                    return numLowerViewTypes + adapter.getItemViewType(position);
                } else {
                    numLowerViewTypes += adapter.getViewTypeCount();
                    position -= adapter.getCount();
                }
            }

            throw new IllegalArgumentException("Invalid position");
        }

        @Override
        public int getViewTypeCount() {
            int totalViewCount = 0;

            final int numAdapters = mAdapters.size();
            for (int i = 0; i < numAdapters; i++) {
                totalViewCount += mAdapters.get(i).getViewTypeCount();
            }

            return totalViewCount;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Pair<ActionAdapter, Integer> realPosition = getSubAdapter(position);

            return realPosition.first.getView(realPosition.second, convertView, parent);
        }

        @Override
        public Object getItem(int position) {
            Pair<ActionAdapter, Integer> realPosition = getSubAdapter(position);

            return realPosition.first.getItem(realPosition.second);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            Pair<ActionAdapter, Integer> realPosition = getSubAdapter(position);

            return realPosition.first.isEnabled(realPosition.second);
        }

        @Override
        public void performAction(@IntRange(from = 0) int position) {
            Pair<ActionAdapter, Integer> realPosition = getSubAdapter(position);

            realPosition.first.performAction(realPosition.second);
        }
    }
    
    /**
     * Superclass for all adapters that just display a list of {@link PrintServiceInfo}.
     */
    private abstract class PrintServiceInfoAdapter extends ActionAdapter {
        /**
         * Raw data of the list.
         *
         * @see #updateData(List)
         */
        private @NonNull List<PrintServiceInfo> mServices;

        /**
         * Create a new adapter.
         */
        PrintServiceInfoAdapter() {
            mServices = Collections.emptyList();
        }

        /**
         * Update the data.
         *
         * @param services The new raw data.
         */
        void updateData(@Nullable List<PrintServiceInfo> services) {
            if (services == null || services.isEmpty()) {
                mServices = Collections.emptyList();
            } else {
                mServices = services;
            }

            notifyDataSetChanged();
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return 0;
            } else {
                return 1;
            }
        }

        @Override
        public int getCount() {
            if (mServices.isEmpty()) {
                return 0;
            } else {
                return mServices.size() + 1;
            }
        }

        @Override
        public Object getItem(int position) {
            if (position == 0) {
                return null;
            } else {
                return mServices.get(position - 1);
            }
        }

        @Override
        public boolean isEnabled(int position) {
            return position != 0;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }
    }

    /**
     * Adapter for the enabled services.
     */
    private class EnabledServicesAdapter extends PrintServiceInfoAdapter {
        @Override
        public void performAction(@IntRange(from = 0) int position) {
            PrintServiceInfo service = (PrintServiceInfo) getItem(position);
            String addPrinterActivityName = service.getAddPrintersActivityName();

            if (!TextUtils.isEmpty(addPrinterActivityName)) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setComponent(new ComponentName(service.getComponentName().getPackageName(),
                        addPrinterActivityName));

                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Log.e(LOG_TAG, "Cannot start add printers activity", e);
                }
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position == 0) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.add_printer_list_header,
                            parent, false);
                }

                ((TextView) convertView.findViewById(R.id.text))
                        .setText(R.string.enabled_services_title);

                return convertView;
            }

            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.enabled_print_services_list_item,
                        parent, false);
            }

            PrintServiceInfo service = (PrintServiceInfo) getItem(position);

            TextView title = (TextView) convertView.findViewById(R.id.title);
            ImageView icon = (ImageView) convertView.findViewById(R.id.icon);
            TextView subtitle = (TextView) convertView.findViewById(R.id.subtitle);

            title.setText(service.getResolveInfo().loadLabel(getPackageManager()));
            icon.setImageDrawable(service.getResolveInfo().loadIcon(getPackageManager()));

            if (TextUtils.isEmpty(service.getAddPrintersActivityName())) {
                subtitle.setText(getString(R.string.cannot_add_printer));
            } else {
                subtitle.setText(getString(R.string.select_to_add_printers));
            }

            return convertView;
        }
    }

    /**
     * Adapter for the disabled services.
     */
    private class DisabledServicesAdapter extends PrintServiceInfoAdapter {
        @Override
        public void performAction(@IntRange(from = 0) int position) {
            ((PrintManager) getSystemService(Context.PRINT_SERVICE)).setPrintServiceEnabled(
                    ((PrintServiceInfo) getItem(position)).getComponentName(), true);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position == 0) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.add_printer_list_header,
                            parent, false);
                }

                ((TextView) convertView.findViewById(R.id.text))
                        .setText(R.string.disabled_services_title);

                return convertView;
            }

            if (convertView == null) {
                convertView = getLayoutInflater().inflate(
                        R.layout.disabled_print_services_list_item, parent, false);
            }

            PrintServiceInfo service = (PrintServiceInfo) getItem(position);

            TextView title = (TextView) convertView.findViewById(R.id.title);
            ImageView icon = (ImageView) convertView.findViewById(R.id.icon);

            title.setText(service.getResolveInfo().loadLabel(getPackageManager()));
            icon.setImageDrawable(service.getResolveInfo().loadIcon(getPackageManager()));

            return convertView;
        }
    }

    /**
     * Adapter for the recommended services.
     */
    private class RecommendedServicesAdapter extends ActionAdapter {
        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return 0;
            } else {
                return 1;
            }
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position == 0) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.add_printer_list_header,
                            parent, false);
                }

                ((TextView) convertView.findViewById(R.id.text))
                        .setText(R.string.recommended_services_title);

                return convertView;
            }

            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.all_print_services_list_item,
                        parent, false);
            }

            return convertView;
        }

        @Override
        public boolean isEnabled(int position) {
            return position != 0;
        }

        @Override
        public void performAction(@IntRange(from = 0) int position) {
            String searchUri = Settings.Secure
                    .getString(getContentResolver(), Settings.Secure.PRINT_SERVICE_SEARCH_URI);

            if (searchUri != null) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(searchUri)));
                } catch (ActivityNotFoundException e) {
                    Log.e(LOG_TAG, "Cannot start market", e);
                }
            }
        }
    }
}
