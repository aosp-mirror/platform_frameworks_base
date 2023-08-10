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

package com.android.printspooler.ui;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.LoaderManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.Loader;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintManager;
import android.print.PrintServicesLoader;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintService;
import android.printservice.PrintServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.printspooler.R;

import java.util.ArrayList;
import java.util.List;

/**
 * This is an activity for selecting a printer.
 */
public final class SelectPrinterActivity extends Activity implements
        LoaderManager.LoaderCallbacks<List<PrintServiceInfo>> {

    private static final String LOG_TAG = "SelectPrinterFragment";

    private static final int LOADER_ID_PRINT_REGISTRY = 1;
    private static final int LOADER_ID_PRINT_REGISTRY_INT = 2;
    private static final int LOADER_ID_ENABLED_PRINT_SERVICES = 3;

    private static final int INFO_INTENT_REQUEST_CODE = 1;

    public static final String INTENT_EXTRA_PRINTER = "INTENT_EXTRA_PRINTER";

    private static final String EXTRA_PRINTER = "EXTRA_PRINTER";
    private static final String EXTRA_PRINTER_ID = "EXTRA_PRINTER_ID";

    private static final String KEY_NOT_FIRST_CREATE = "KEY_NOT_FIRST_CREATE";
    private static final String KEY_DID_SEARCH = "DID_SEARCH";
    private static final String KEY_PRINTER_FOR_INFO_INTENT = "KEY_PRINTER_FOR_INFO_INTENT";

    // Constants for MetricsLogger.count and MetricsLogger.histo
    private static final String PRINTERS_LISTED_COUNT = "printers_listed";
    private static final String PRINTERS_ICON_COUNT = "printers_icon";
    private static final String PRINTERS_INFO_COUNT = "printers_info";

    /** The currently enabled print services by their ComponentName */
    private ArrayMap<ComponentName, PrintServiceInfo> mEnabledPrintServices;

    private PrinterRegistry mPrinterRegistry;

    private ListView mListView;

    private AnnounceFilterResult mAnnounceFilterResult;

    private boolean mDidSearch;

    /**
     * Printer we are currently in the info intent for. This is only non-null while this activity
     * started an info intent that has not yet returned
     */
    private @Nullable PrinterInfo mPrinterForInfoIntent;

    private void startAddPrinterActivity() {
        MetricsLogger.action(this, MetricsEvent.ACTION_PRINT_SERVICE_ADD);
        startActivity(new Intent(this, AddPrinterActivity.class));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setIcon(com.android.internal.R.drawable.ic_print);

        setContentView(R.layout.select_printer_activity);

        getActionBar().setDisplayHomeAsUpEnabled(true);

        mEnabledPrintServices = new ArrayMap<>();

        mPrinterRegistry = new PrinterRegistry(this, null, LOADER_ID_PRINT_REGISTRY,
                LOADER_ID_PRINT_REGISTRY_INT);

        // Hook up the list view.
        mListView = findViewById(android.R.id.list);
        final DestinationAdapter adapter = new DestinationAdapter();
        adapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                if (!isFinishing() && adapter.getCount() <= 0) {
                    updateEmptyView(adapter);
                }
            }

            @Override
            public void onInvalidated() {
                if (!isFinishing()) {
                    updateEmptyView(adapter);
                }
            }
        });
        mListView.setAdapter(adapter);

        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!((DestinationAdapter) mListView.getAdapter()).isActionable(position)) {
                    return;
                }

                PrinterInfo printer = (PrinterInfo) mListView.getAdapter().getItem(position);

                if (printer == null) {
                    startAddPrinterActivity();
                } else {
                    onPrinterSelected(printer);
                }
            }
        });

        findViewById(R.id.button).setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                startAddPrinterActivity();
            }
        });

        registerForContextMenu(mListView);

        getLoaderManager().initLoader(LOADER_ID_ENABLED_PRINT_SERVICES, null, this);

        // On first creation:
        //
        // If no services are installed, instantly open add printer dialog.
        // If some are disabled and some are enabled show a toast to notify the user
        if (savedInstanceState == null || !savedInstanceState.getBoolean(KEY_NOT_FIRST_CREATE)) {
            List<PrintServiceInfo> allServices =
                    ((PrintManager) getSystemService(Context.PRINT_SERVICE))
                            .getPrintServices(PrintManager.ALL_SERVICES);
            boolean hasEnabledServices = false;
            boolean hasDisabledServices = false;

            if (allServices != null) {
                final int numServices = allServices.size();
                for (int i = 0; i < numServices; i++) {
                    if (allServices.get(i).isEnabled()) {
                        hasEnabledServices = true;
                    } else {
                        hasDisabledServices = true;
                    }
                }
            }

            if (!hasEnabledServices) {
                startAddPrinterActivity();
            } else if (hasDisabledServices) {
                String disabledServicesSetting = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.DISABLED_PRINT_SERVICES);
                if (!TextUtils.isEmpty(disabledServicesSetting)) {
                    Toast.makeText(this, getString(R.string.print_services_disabled_toast),
                            Toast.LENGTH_LONG).show();
                }
            }
        }

        if (savedInstanceState != null) {
            mDidSearch = savedInstanceState.getBoolean(KEY_DID_SEARCH);
            mPrinterForInfoIntent = savedInstanceState.getParcelable(KEY_PRINTER_FOR_INFO_INTENT);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_NOT_FIRST_CREATE, true);
        outState.putBoolean(KEY_DID_SEARCH, mDidSearch);
        outState.putParcelable(KEY_PRINTER_FOR_INFO_INTENT, mPrinterForInfoIntent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.select_printer_activity, menu);

        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String searchString) {
                ((DestinationAdapter) mListView.getAdapter()).getFilter().filter(searchString);
                return true;
            }
        });
        searchView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (AccessibilityManager.getInstance(SelectPrinterActivity.this).isEnabled()) {
                    view.announceForAccessibility(getString(
                            R.string.print_search_box_shown_utterance));
                }
            }
            @Override
            public void onViewDetachedFromWindow(View view) {
                if (!isFinishing() && AccessibilityManager.getInstance(
                        SelectPrinterActivity.this).isEnabled()) {
                    view.announceForAccessibility(getString(
                            R.string.print_search_box_hidden_utterance));
                }
            }
        });

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
        if (view == mListView) {
            final int position = ((AdapterContextMenuInfo) menuInfo).position;
            PrinterInfo printer = (PrinterInfo) mListView.getAdapter().getItem(position);

            // Printer is null if this is a context menu for the "add printer" entry
            if (printer == null) {
                return;
            }

            menu.setHeaderTitle(printer.getName());

            // Add the select menu item if applicable.
            if (printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE) {
                MenuItem selectItem = menu.add(Menu.NONE, R.string.print_select_printer,
                        Menu.NONE, R.string.print_select_printer);
                Intent intent = new Intent();
                intent.putExtra(EXTRA_PRINTER, printer);
                selectItem.setIntent(intent);
            }

            // Add the forget menu item if applicable.
            if (mPrinterRegistry.isFavoritePrinter(printer.getId())) {
                MenuItem forgetItem = menu.add(Menu.NONE, R.string.print_forget_printer,
                        Menu.NONE, R.string.print_forget_printer);
                Intent intent = new Intent();
                intent.putExtra(EXTRA_PRINTER_ID, printer.getId());
                forgetItem.setIntent(intent);
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.string.print_select_printer) {
            PrinterInfo printer = item.getIntent().getParcelableExtra(EXTRA_PRINTER);
            onPrinterSelected(printer);
            return true;
        } else if (itemId == R.string.print_forget_printer) {
            PrinterId printerId = item.getIntent().getParcelableExtra(EXTRA_PRINTER_ID);
            mPrinterRegistry.forgetFavoritePrinter(printerId);
            return true;
        }
        return false;
    }

    /**
     * Adjust the UI if the enabled print services changed.
     */
    private synchronized void onPrintServicesUpdate() {
        updateEmptyView((DestinationAdapter)mListView.getAdapter());
        invalidateOptionsMenu();
    }

    @Override
    public void onStart() {
        super.onStart();
        onPrintServicesUpdate();
    }

    @Override
    public void onPause() {
        if (mAnnounceFilterResult != null) {
            mAnnounceFilterResult.remove();
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            DestinationAdapter adapter = (DestinationAdapter) mListView.getAdapter();
            List<PrinterInfo> printers = adapter.getPrinters();
            int numPrinters = adapter.getPrinters().size();

            MetricsLogger.action(this, MetricsEvent.PRINT_ALL_PRINTERS, numPrinters);
            MetricsLogger.count(this, PRINTERS_LISTED_COUNT, numPrinters);

            int numInfoPrinters = 0;
            int numIconPrinters = 0;
            for (int i = 0; i < numPrinters; i++) {
                PrinterInfo printer = printers.get(i);

                if (printer.getInfoIntent() != null) {
                    numInfoPrinters++;
                }

                if (printer.getHasCustomPrinterIcon()) {
                    numIconPrinters++;
                }
            }

            MetricsLogger.count(this, PRINTERS_INFO_COUNT, numInfoPrinters);
            MetricsLogger.count(this, PRINTERS_ICON_COUNT, numIconPrinters);
        }

        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case INFO_INTENT_REQUEST_CODE:
                if (resultCode == RESULT_OK &&
                        data != null &&
                        data.getBooleanExtra(PrintService.EXTRA_SELECT_PRINTER, false) &&
                        mPrinterForInfoIntent != null &&
                        mPrinterForInfoIntent.getStatus() != PrinterInfo.STATUS_UNAVAILABLE) {
                    onPrinterSelected(mPrinterForInfoIntent);
                }
                mPrinterForInfoIntent = null;
                break;
            default:
                // not reached
        }
    }

    private void onPrinterSelected(PrinterInfo printer) {
        Intent intent = new Intent();
        intent.putExtra(INTENT_EXTRA_PRINTER, printer);
        setResult(RESULT_OK, intent);
        finish();
    }

    public void updateEmptyView(DestinationAdapter adapter) {
        if (mListView.getEmptyView() == null) {
            View emptyView = findViewById(R.id.empty_print_state);
            mListView.setEmptyView(emptyView);
        }
        TextView titleView = findViewById(R.id.title);
        View progressBar = findViewById(R.id.progress_bar);
        if (mEnabledPrintServices.size() == 0) {
            titleView.setText(R.string.print_no_print_services);
            progressBar.setVisibility(View.GONE);
        } else if (adapter.getUnfilteredCount() <= 0) {
            titleView.setText(R.string.print_searching_for_printers);
            progressBar.setVisibility(View.VISIBLE);
        } else {
            titleView.setText(R.string.print_no_printers);
            progressBar.setVisibility(View.GONE);
        }
    }

    private void announceSearchResultIfNeeded() {
        if (AccessibilityManager.getInstance(this).isEnabled()) {
            if (mAnnounceFilterResult == null) {
                mAnnounceFilterResult = new AnnounceFilterResult();
            }
            mAnnounceFilterResult.post();
        }
    }

    @Override
    public Loader<List<PrintServiceInfo>> onCreateLoader(int id, Bundle args) {
        return new PrintServicesLoader((PrintManager) getSystemService(Context.PRINT_SERVICE), this,
                PrintManager.ENABLED_SERVICES);
    }

    @Override
    public void onLoadFinished(Loader<List<PrintServiceInfo>> loader,
            List<PrintServiceInfo> services) {
        mEnabledPrintServices.clear();

        if (services != null && !services.isEmpty()) {
            final int numServices = services.size();
            for (int i = 0; i < numServices; i++) {
                PrintServiceInfo service = services.get(i);

                mEnabledPrintServices.put(service.getComponentName(), service);
            }
        }

        onPrintServicesUpdate();
    }

    @Override
    public void onLoaderReset(Loader<List<PrintServiceInfo>> loader) {
        if (!isFinishing()) {
            onLoadFinished(loader, null);
        }
    }

    /**
     * Return the target SDK of the package that defined the printer.
     *
     * @param printer The printer
     *
     * @return The target SDK that defined a printer.
     */
    private int getTargetSDKOfPrintersService(@NonNull PrinterInfo printer) {
        ApplicationInfo serviceAppInfo;
        try {
            serviceAppInfo = getPackageManager().getApplicationInfo(
                    printer.getId().getServiceName().getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Could not find package that defined the printer", e);
            return Build.VERSION_CODES.KITKAT;
        }

        return serviceAppInfo.targetSdkVersion;
    }

    private final class DestinationAdapter extends BaseAdapter implements Filterable {

        private final Object mLock = new Object();

        private final List<PrinterInfo> mPrinters = new ArrayList<>();

        private final List<PrinterInfo> mFilteredPrinters = new ArrayList<>();

        private CharSequence mLastSearchString;

        /**
         * Get the currently known printers.
         *
         * @return The currently known printers
         */
        @NonNull List<PrinterInfo> getPrinters() {
            return mPrinters;
        }

        public DestinationAdapter() {
            mPrinterRegistry.setOnPrintersChangeListener(new PrinterRegistry.OnPrintersChangeListener() {
                @Override
                public void onPrintersChanged(List<PrinterInfo> printers) {
                    synchronized (mLock) {
                        mPrinters.clear();
                        mPrinters.addAll(printers);
                        mFilteredPrinters.clear();
                        mFilteredPrinters.addAll(printers);
                        if (!TextUtils.isEmpty(mLastSearchString)) {
                            getFilter().filter(mLastSearchString);
                        }
                    }
                    notifyDataSetChanged();
                }

                @Override
                public void onPrintersInvalid() {
                    synchronized (mLock) {
                        mPrinters.clear();
                        mFilteredPrinters.clear();
                    }
                    notifyDataSetInvalidated();
                }
            });
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    synchronized (mLock) {
                        if (TextUtils.isEmpty(constraint)) {
                            return null;
                        }
                        FilterResults results = new FilterResults();
                        List<PrinterInfo> filteredPrinters = new ArrayList<>();
                        String constraintLowerCase = constraint.toString().toLowerCase();
                        final int printerCount = mPrinters.size();
                        for (int i = 0; i < printerCount; i++) {
                            PrinterInfo printer = mPrinters.get(i);
                            String description = printer.getDescription();
                            if (printer.getName().toLowerCase().contains(constraintLowerCase)
                                    || description != null && description.toLowerCase()
                                            .contains(constraintLowerCase)) {
                                filteredPrinters.add(printer);
                            }
                        }
                        results.values = filteredPrinters;
                        results.count = filteredPrinters.size();
                        return results;
                    }
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    final boolean resultCountChanged;
                    synchronized (mLock) {
                        final int oldPrinterCount = mFilteredPrinters.size();
                        mLastSearchString = constraint;
                        mFilteredPrinters.clear();
                        if (results == null) {
                            mFilteredPrinters.addAll(mPrinters);
                        } else {
                            List<PrinterInfo> printers = (List<PrinterInfo>) results.values;
                            mFilteredPrinters.addAll(printers);
                        }
                        resultCountChanged = (oldPrinterCount != mFilteredPrinters.size());
                    }
                    if (resultCountChanged) {
                        announceSearchResultIfNeeded();
                    }

                    if (!mDidSearch) {
                        MetricsLogger.action(SelectPrinterActivity.this,
                                MetricsEvent.ACTION_PRINTER_SEARCH);
                        mDidSearch = true;
                    }
                    notifyDataSetChanged();
                }
            };
        }

        public int getUnfilteredCount() {
            synchronized (mLock) {
                return mPrinters.size();
            }
        }

        @Override
        public int getCount() {
            synchronized (mLock) {
                if (mFilteredPrinters.isEmpty()) {
                    return 0;
                } else {
                    // Add "add printer" item to the end of the list. If the list is empty there is
                    // a link on the empty view
                    return mFilteredPrinters.size() + 1;
                }
            }
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            // Use separate view types for the "add printer" item an the items referring to printers
            if (getItem(position) == null) {
                return 0;
            } else {
                return 1;
            }
        }

        @Override
        public Object getItem(int position) {
            synchronized (mLock) {
                if (position < mFilteredPrinters.size()) {
                    return mFilteredPrinters.get(position);
                } else {
                    // Return null to mark this as the "add printer item"
                    return null;
                }
            }
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return getView(position, convertView, parent);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final PrinterInfo printer = (PrinterInfo) getItem(position);

            // Handle "add printer item"
            if (printer == null) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.add_printer_list_item,
                            parent, false);
                }

                return convertView;
            }

            if (convertView == null) {
                convertView = getLayoutInflater().inflate(
                        R.layout.printer_list_item, parent, false);
            }

            convertView.setEnabled(isActionable(position));


            CharSequence title = printer.getName();
            Drawable icon = printer.loadIcon(SelectPrinterActivity.this);

            PrintServiceInfo service = mEnabledPrintServices.get(printer.getId().getServiceName());

            CharSequence printServiceLabel = null;
            if (service != null) {
                printServiceLabel = service.getResolveInfo().loadLabel(getPackageManager())
                        .toString();
            }

            CharSequence description = printer.getDescription();

            CharSequence subtitle;
            if (TextUtils.isEmpty(printServiceLabel)) {
                subtitle = description;
            } else if (TextUtils.isEmpty(description)) {
                subtitle = printServiceLabel;
            } else {
                subtitle = getString(R.string.printer_extended_description_template,
                        printServiceLabel, description);
            }

            TextView titleView = (TextView) convertView.findViewById(R.id.title);
            titleView.setText(title);

            TextView subtitleView = (TextView) convertView.findViewById(R.id.subtitle);
            if (!TextUtils.isEmpty(subtitle)) {
                subtitleView.setText(subtitle);
                subtitleView.setVisibility(View.VISIBLE);
            } else {
                subtitleView.setText(null);
                subtitleView.setVisibility(View.GONE);
            }

            LinearLayout moreInfoView = (LinearLayout) convertView.findViewById(R.id.more_info);
            if (printer.getInfoIntent() != null) {
                moreInfoView.setVisibility(View.VISIBLE);
                moreInfoView.setOnClickListener(v -> {
                    Intent fillInIntent = new Intent();
                    fillInIntent.putExtra(PrintService.EXTRA_CAN_SELECT_PRINTER, true);

                    try {
                        mPrinterForInfoIntent = printer;
                        Bundle options = ActivityOptions.makeBasic()
                                .setPendingIntentBackgroundActivityStartMode(
                                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                                .toBundle();
                        startIntentSenderForResult(printer.getInfoIntent().getIntentSender(),
                                INFO_INTENT_REQUEST_CODE, fillInIntent, 0, 0, 0,
                                options);
                    } catch (SendIntentException e) {
                        mPrinterForInfoIntent = null;
                        Log.e(LOG_TAG, "Could not execute pending info intent: %s", e);
                    }
                });
            } else {
                moreInfoView.setVisibility(View.GONE);
            }

            ImageView iconView = (ImageView) convertView.findViewById(R.id.icon);
            if (icon != null) {
                iconView.setVisibility(View.VISIBLE);
                if (!isActionable(position)) {
                    icon.mutate();

                    TypedValue value = new TypedValue();
                    getTheme().resolveAttribute(android.R.attr.disabledAlpha, value, true);
                    icon.setAlpha((int)(value.getFloat() * 255));
                }
                iconView.setImageDrawable(icon);
            } else {
                iconView.setVisibility(View.GONE);
            }

            return convertView;
        }

        public boolean isActionable(int position) {
            PrinterInfo printer =  (PrinterInfo) getItem(position);

            if (printer == null) {
                return true;
            } else {
                return printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE;
            }
        }
    }

    private final class AnnounceFilterResult implements Runnable {
        private static final int SEARCH_RESULT_ANNOUNCEMENT_DELAY = 1000; // 1 sec

        public void post() {
            remove();
            mListView.postDelayed(this, SEARCH_RESULT_ANNOUNCEMENT_DELAY);
        }

        public void remove() {
            mListView.removeCallbacks(this);
        }

        @Override
        public void run() {
            final int count = mListView.getAdapter().getCount();
            final String text;
            if (count <= 0) {
                text = getString(R.string.print_no_printers);
            } else {
                text = getResources().getQuantityString(
                    R.plurals.print_search_result_count_utterance, count, count);
            }
            mListView.announceForAccessibility(text);
        }
    }
}
