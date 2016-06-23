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
import android.content.pm.ResolveInfo;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintManager;
import android.printservice.recommendation.RecommendationInfo;
import android.print.PrintServiceRecommendationsLoader;
import android.print.PrintServicesLoader;
import android.printservice.PrintServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
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

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * This is an activity for adding a printer or. It consists of a list fed from three adapters:
 * <ul>
 *     <li>{@link #mEnabledServicesAdapter} for all enabled services. If a service has an {@link
 *         PrintServiceInfo#getAddPrintersActivityName() add printer activity} this is started
 *         when the item is clicked.</li>
 *     <li>{@link #mDisabledServicesAdapter} for all disabled services. Once clicked the settings page
 *         for this service is opened.</li>
 *     <li>{@link #mRecommendedServicesAdapter} for a link to all services. If this item is clicked
 *         the market app is opened to show all print services.</li>
 * </ul>
 */
public class AddPrinterActivity extends ListActivity implements AdapterView.OnItemClickListener {
    private static final String LOG_TAG = "AddPrinterActivity";

    /** Ids for the loaders */
    private static final int LOADER_ID_ENABLED_SERVICES = 1;
    private static final int LOADER_ID_DISABLED_SERVICES = 2;
    private static final int LOADER_ID_RECOMMENDED_SERVICES = 3;
    private static final int LOADER_ID_ALL_SERVICES = 4;

    /**
     * The enabled services list. This is filled from the {@link #LOADER_ID_ENABLED_SERVICES}
     * loader in {@link PrintServiceInfoLoaderCallbacks#onLoadFinished}.
     */
    private EnabledServicesAdapter mEnabledServicesAdapter;

    /**
     * The disabled services list. This is filled from the {@link #LOADER_ID_DISABLED_SERVICES}
     * loader in {@link PrintServiceInfoLoaderCallbacks#onLoadFinished}.
     */
    private DisabledServicesAdapter mDisabledServicesAdapter;

    /**
     * The recommended services list. This is filled from the
     * {@link #LOADER_ID_RECOMMENDED_SERVICES} loader in
     * {@link PrintServicePrintServiceRecommendationLoaderCallbacks#onLoadFinished}.
     */
    private RecommendedServicesAdapter mRecommendedServicesAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.add_printer_activity);

        mEnabledServicesAdapter = new EnabledServicesAdapter();
        mDisabledServicesAdapter = new DisabledServicesAdapter();
        mRecommendedServicesAdapter = new RecommendedServicesAdapter();

        ArrayList<ActionAdapter> adapterList = new ArrayList<>(3);
        adapterList.add(mEnabledServicesAdapter);
        adapterList.add(mRecommendedServicesAdapter);
        adapterList.add(mDisabledServicesAdapter);

        setListAdapter(new CombinedAdapter(adapterList));

        getListView().setOnItemClickListener(this);

        PrintServiceInfoLoaderCallbacks printServiceLoaderCallbacks =
                new PrintServiceInfoLoaderCallbacks();

        getLoaderManager().initLoader(LOADER_ID_ENABLED_SERVICES, null, printServiceLoaderCallbacks);
        getLoaderManager().initLoader(LOADER_ID_DISABLED_SERVICES, null, printServiceLoaderCallbacks);
        getLoaderManager().initLoader(LOADER_ID_RECOMMENDED_SERVICES, null,
                new PrintServicePrintServiceRecommendationLoaderCallbacks());
        getLoaderManager().initLoader(LOADER_ID_ALL_SERVICES, null, printServiceLoaderCallbacks);
    }

    /**
     * Callbacks for the loaders operating on list of {@link PrintServiceInfo print service infos}.
     */
    private class PrintServiceInfoLoaderCallbacks implements
            LoaderManager.LoaderCallbacks<List<PrintServiceInfo>> {
        @Override
        public Loader<List<PrintServiceInfo>> onCreateLoader(int id, Bundle args) {
            switch (id) {
                case LOADER_ID_ENABLED_SERVICES:
                    return new PrintServicesLoader(
                            (PrintManager) getSystemService(Context.PRINT_SERVICE),
                            AddPrinterActivity.this, PrintManager.ENABLED_SERVICES);
                case LOADER_ID_DISABLED_SERVICES:
                    return new PrintServicesLoader(
                            (PrintManager) getSystemService(Context.PRINT_SERVICE),
                            AddPrinterActivity.this, PrintManager.DISABLED_SERVICES);
                case LOADER_ID_ALL_SERVICES:
                    return new PrintServicesLoader(
                            (PrintManager) getSystemService(Context.PRINT_SERVICE),
                            AddPrinterActivity.this, PrintManager.ALL_SERVICES);
                default:
                    // not reached
                    return null;
            }
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
                case LOADER_ID_ALL_SERVICES:
                    mRecommendedServicesAdapter.updateInstalledServices(data);
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
                    case LOADER_ID_ALL_SERVICES:
                        mRecommendedServicesAdapter.updateInstalledServices(null);
                        break;
                    default:
                        // not reached
                }
            }
        }
    }

    /**
     * Callbacks for the loaders operating on list of {@link RecommendationInfo print service
     * recommendations}.
     */
    private class PrintServicePrintServiceRecommendationLoaderCallbacks implements
            LoaderManager.LoaderCallbacks<List<RecommendationInfo>> {
        @Override
        public Loader<List<RecommendationInfo>> onCreateLoader(int id, Bundle args) {
            return new PrintServiceRecommendationsLoader(
                    (PrintManager) getSystemService(Context.PRINT_SERVICE),
                    AddPrinterActivity.this);
        }


        @Override
        public void onLoadFinished(Loader<List<RecommendationInfo>> loader,
                List<RecommendationInfo> data) {
            mRecommendedServicesAdapter.updateRecommendations(data);
        }

        @Override
        public void onLoaderReset(Loader<List<RecommendationInfo>> loader) {
            if (!isFinishing()) {
                mRecommendedServicesAdapter.updateRecommendations(null);
            }
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        ((ActionAdapter) getListAdapter()).performAction(position);
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
            Intent intent = getAddPrinterIntent((PrintServiceInfo) getItem(position));
            if (intent != null) {
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException|SecurityException e) {
                    Log.e(LOG_TAG, "Cannot start add printers activity", e);
                }
            }
        }

        /**
         * Get the intent used to launch the add printers activity.
         *
         * @param service The service the printer should be added for
         *
         * @return The intent to launch the activity or null if the activity could not be launched.
         */
        private Intent getAddPrinterIntent(@NonNull PrintServiceInfo service) {
            String addPrinterActivityName = service.getAddPrintersActivityName();

            if (!TextUtils.isEmpty(addPrinterActivityName)) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setComponent(new ComponentName(service.getComponentName().getPackageName(),
                                addPrinterActivityName));

                List<ResolveInfo> resolvedActivities = getPackageManager().queryIntentActivities(
                        intent, 0);
                if (!resolvedActivities.isEmpty()) {
                    // The activity is a component name, therefore it is one or none.
                    if (resolvedActivities.get(0).activityInfo.exported) {
                        return intent;
                    }
                }
            }

            return null;
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

            if (getAddPrinterIntent(service) == null) {
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
        /** Package names of all installed print services */
        private @NonNull final ArraySet<String> mInstalledServices;

        /** All print service recommendations */
        private @Nullable List<RecommendationInfo> mRecommendations;

        /**
         * Sorted print service recommendations for services that are not installed
         *
         * @see #filterRecommendations
         */
        private @Nullable List<RecommendationInfo> mFilteredRecommendations;

        /**
         * Create a new adapter.
         */
        private RecommendedServicesAdapter() {
            mInstalledServices = new ArraySet<>();
        }

        @Override
        public int getCount() {
            if (mFilteredRecommendations == null) {
                return 2;
            } else {
                return mFilteredRecommendations.size() + 2;
            }
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        /**
         * @return The position the all services link is at.
         */
        private int getAllServicesPos() {
            return getCount() - 1;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return 0;
            } else if (getAllServicesPos() == position) {
                return 1;
            } else {
                return 2;
            }
        }

        @Override
        public Object getItem(int position) {
            if (position == 0 || position == getAllServicesPos()) {
                return null;
            } else {
                return mFilteredRecommendations.get(position - 1);
            }
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
            } else if (position == getAllServicesPos()) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.all_print_services_list_item,
                            parent, false);
                }
            } else {
                RecommendationInfo recommendation = (RecommendationInfo) getItem(position);

                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(
                            R.layout.print_service_recommendations_list_item, parent, false);
                }

                ((TextView) convertView.findViewById(R.id.title)).setText(recommendation.getName());

                ((TextView) convertView.findViewById(R.id.subtitle)).setText(getResources()
                        .getQuantityString(R.plurals.print_services_recommendation_subtitle,
                                recommendation.getNumDiscoveredPrinters(),
                                recommendation.getNumDiscoveredPrinters()));

                return convertView;
            }

            return convertView;
        }

        @Override
        public boolean isEnabled(int position) {
            return position != 0;
        }

        @Override
        public void performAction(@IntRange(from = 0) int position) {
            if (position == getAllServicesPos()) {
                String searchUri = Settings.Secure
                        .getString(getContentResolver(), Settings.Secure.PRINT_SERVICE_SEARCH_URI);

                if (searchUri != null) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(searchUri)));
                    } catch (ActivityNotFoundException e) {
                        Log.e(LOG_TAG, "Cannot start market", e);
                    }
                }
            } else {
                RecommendationInfo recommendation = (RecommendationInfo) getItem(position);

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(
                            R.string.uri_package_details, recommendation.getPackageName()))));
                } catch (ActivityNotFoundException e) {
                    Log.e(LOG_TAG, "Cannot start market", e);
                }
            }
        }

        /**
         * Filter recommended services.
         */
        private void filterRecommendations() {
            if (mRecommendations == null) {
                mFilteredRecommendations = null;
            } else {
                mFilteredRecommendations = new ArrayList<>();

                // Filter out recommendations for already installed services
                final int numRecommendations = mRecommendations.size();
                for (int i = 0; i < numRecommendations; i++) {
                    RecommendationInfo recommendation = mRecommendations.get(i);

                    if (!mInstalledServices.contains(recommendation.getPackageName())) {
                        mFilteredRecommendations.add(recommendation);
                    }
                }
            }

            notifyDataSetChanged();
        }

        /**
         * Update the installed print services.
         *
         * @param services The new set of services
         */
        public void updateInstalledServices(List<PrintServiceInfo> services) {
            mInstalledServices.clear();

            final int numServices = services.size();
            for (int i = 0; i < numServices; i++) {
                mInstalledServices.add(services.get(i).getComponentName().getPackageName());
            }

            filterRecommendations();
        }

        /**
         * Update the recommended print services.
         *
         * @param recommendations The new set of recommendations
         */
        public void updateRecommendations(List<RecommendationInfo> recommendations) {
            if (recommendations != null) {
                final Collator collator = Collator.getInstance();

                // Sort recommendations (early conditions are more important)
                // - higher number of discovered printers first
                // - single vendor services first
                // - alphabetically
                Collections.sort(recommendations,
                        new Comparator<RecommendationInfo>() {
                            @Override public int compare(RecommendationInfo o1,
                                    RecommendationInfo o2) {
                                if (o1.getNumDiscoveredPrinters() !=
                                        o2.getNumDiscoveredPrinters()) {
                                    return o2.getNumDiscoveredPrinters() -
                                            o1.getNumDiscoveredPrinters();
                                } else if (o1.recommendsMultiVendorService()
                                        != o2.recommendsMultiVendorService()) {
                                    if (o1.recommendsMultiVendorService()) {
                                        return 1;
                                    } else {
                                        return -1;
                                    }
                                } else {
                                    return collator.compare(o1.getName().toString(),
                                            o2.getName().toString());
                                }
                            }
                        });
            }

            mRecommendations = recommendations;

            filterRecommendations();
        }
    }
}
