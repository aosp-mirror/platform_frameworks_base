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

package com.android.printspooler;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintManager;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a fragment for selecting a printer.
 */
public final class SelectPrinterFragment extends Fragment {

    private static final String LOG_TAG = "SelectPrinterFragment";

    private static final int LOADER_ID_PRINTERS_LOADER = 1;

    private static final String FRAGMRNT_TAG_ADD_PRINTER_DIALOG =
            "FRAGMRNT_TAG_ADD_PRINTER_DIALOG";

    private static final String FRAGMRNT_ARGUMENT_PRINT_SERVICE_INFOS =
            "FRAGMRNT_ARGUMENT_PRINT_SERVICE_INFOS";

    private static final String EXTRA_PRINTER_ID = "EXTRA_PRINTER_ID";

    private final ArrayList<PrintServiceInfo> mAddPrinterServices =
            new ArrayList<PrintServiceInfo>();

    private ListView mListView;

    private AnnounceFilterResult mAnnounceFilterResult;

    public static interface OnPrinterSelectedListener {
        public void onPrinterSelected(PrinterId printerId);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        getActivity().getActionBar().setIcon(R.drawable.ic_menu_print);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View content = inflater.inflate(R.layout.select_printer_fragment, container, false);

        // Hook up the list view.
        mListView = (ListView) content.findViewById(android.R.id.list);
        final DestinationAdapter adapter = new DestinationAdapter();
        adapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                if (!getActivity().isFinishing() && adapter.getCount() <= 0) {
                    updateEmptyView(adapter);
                }
            }

            @Override
            public void onInvalidated() {
                if (!getActivity().isFinishing()) {
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
                Activity activity = getActivity();
                if (activity instanceof OnPrinterSelectedListener) {
                    ((OnPrinterSelectedListener) activity).onPrinterSelected(printer.getId());
                } else {
                    throw new IllegalStateException("the host activity must implement"
                            + " OnPrinterSelectedListener");
                }
            }
        });

        registerForContextMenu(mListView);

        return content;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.select_printer_activity, menu);

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
                if (AccessibilityManager.getInstance(getActivity()).isEnabled()) {
                    view.announceForAccessibility(getString(
                            R.string.print_search_box_shown_utterance));
                }
            }
            @Override
            public void onViewDetachedFromWindow(View view) {
                Activity activity = getActivity();
                if (activity != null && !activity.isFinishing()
                        && AccessibilityManager.getInstance(activity).isEnabled()) {
                    view.announceForAccessibility(getString(
                            R.string.print_search_box_hidden_utterance));
                }
            }
        });

        if (mAddPrinterServices.isEmpty()) {
            menu.removeItem(R.id.action_add_printer);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
        if (view == mListView) {
            final int position = ((AdapterContextMenuInfo) menuInfo).position;
            PrinterInfo printer = (PrinterInfo) mListView.getAdapter().getItem(position);

            menu.setHeaderTitle(printer.getName());

            // Add the select menu item if applicable.
            if (printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE) {
                MenuItem selectItem = menu.add(Menu.NONE, R.string.print_select_printer,
                        Menu.NONE, R.string.print_select_printer);
                Intent intent = new Intent();
                intent.putExtra(EXTRA_PRINTER_ID, printer.getId());
                selectItem.setIntent(intent);
            }

            // Add the forget menu item if applicable.
            FusedPrintersProvider provider = (FusedPrintersProvider) (Loader<?>)
                    getLoaderManager().getLoader(LOADER_ID_PRINTERS_LOADER);
            if (provider.isFavoritePrinter(printer.getId())) {
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
        switch (item.getItemId()) {
            case R.string.print_select_printer: {
                PrinterId printerId = (PrinterId) item.getIntent().getParcelableExtra(
                        EXTRA_PRINTER_ID);
                Activity activity = getActivity();
                if (activity instanceof OnPrinterSelectedListener) {
                    ((OnPrinterSelectedListener) activity).onPrinterSelected(printerId);
                } else {
                    throw new IllegalStateException("the host activity must implement"
                            + " OnPrinterSelectedListener");
                }
            } return true;

            case R.string.print_forget_printer: {
                PrinterId printerId = (PrinterId) item.getIntent().getParcelableExtra(
                        EXTRA_PRINTER_ID);
                FusedPrintersProvider provider = (FusedPrintersProvider) (Loader<?>)
                        getLoaderManager().getLoader(LOADER_ID_PRINTERS_LOADER);
                provider.forgetFavoritePrinter(printerId);
            } return true;
        }
        return false;
    }

    @Override
    public void onResume() {
        updateAddPrintersAdapter();
        getActivity().invalidateOptionsMenu();
        super.onResume();
    }

    @Override
    public void onPause() {
        if (mAnnounceFilterResult != null) {
            mAnnounceFilterResult.remove();
        }
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_add_printer) {
            showAddPrinterSelectionDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateAddPrintersAdapter() {
        mAddPrinterServices.clear();

        // Get all enabled print services.
        PrintManager printManager = (PrintManager) getActivity()
                .getSystemService(Context.PRINT_SERVICE);
        List<PrintServiceInfo> enabledServices = printManager.getEnabledPrintServices();

        // No enabled print services - done.
        if (enabledServices.isEmpty()) {
            return;
        }

        // Find the services with valid add printers activities.
        final int enabledServiceCount = enabledServices.size();
        for (int i = 0; i < enabledServiceCount; i++) {
            PrintServiceInfo enabledService = enabledServices.get(i);

            // No add printers activity declared - done.
            if (TextUtils.isEmpty(enabledService.getAddPrintersActivityName())) {
                continue;
            }

            ServiceInfo serviceInfo = enabledService.getResolveInfo().serviceInfo;
            ComponentName addPrintersComponentName = new ComponentName(
                    serviceInfo.packageName, enabledService.getAddPrintersActivityName());
            Intent addPritnersIntent = new Intent()
                .setComponent(addPrintersComponentName);

            // The add printers activity is valid - add it.
            PackageManager pm = getActivity().getPackageManager();
            List<ResolveInfo> resolvedActivities = pm.queryIntentActivities(addPritnersIntent, 0);
            if (!resolvedActivities.isEmpty()) {
                // The activity is a component name, therefore it is one or none.
                ActivityInfo activityInfo = resolvedActivities.get(0).activityInfo;
                if (activityInfo.exported
                        && (activityInfo.permission == null
                                || pm.checkPermission(activityInfo.permission,
                                        getActivity().getPackageName())
                                        == PackageManager.PERMISSION_GRANTED)) {
                    mAddPrinterServices.add(enabledService);
                }
            }
        }
    }

    private void showAddPrinterSelectionDialog() {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        Fragment oldFragment = getFragmentManager().findFragmentByTag(
                FRAGMRNT_TAG_ADD_PRINTER_DIALOG);
        if (oldFragment != null) {
            transaction.remove(oldFragment);
        }
        AddPrinterAlertDialogFragment newFragment = new AddPrinterAlertDialogFragment();
        Bundle arguments = new Bundle();
        arguments.putParcelableArrayList(FRAGMRNT_ARGUMENT_PRINT_SERVICE_INFOS,
                mAddPrinterServices);
        newFragment.setArguments(arguments);
        transaction.add(newFragment, FRAGMRNT_TAG_ADD_PRINTER_DIALOG);
        transaction.commit();
    }

    public void updateEmptyView(DestinationAdapter adapter) {
        if (mListView.getEmptyView() == null) {
            View emptyView = getActivity().findViewById(R.id.empty_print_state);
            mListView.setEmptyView(emptyView);
        }
        TextView titleView = (TextView) getActivity().findViewById(R.id.title);
        View progressBar = getActivity().findViewById(R.id.progress_bar);
        if (adapter.getUnfilteredCount() <= 0) {
            titleView.setText(R.string.print_searching_for_printers);
            progressBar.setVisibility(View.VISIBLE);
        } else {
            titleView.setText(R.string.print_no_printers);
            progressBar.setVisibility(View.GONE);
        }
    }

    private void announceSearchResultIfNeeded() {
        if (AccessibilityManager.getInstance(getActivity()).isEnabled()) {
            if (mAnnounceFilterResult == null) {
                mAnnounceFilterResult = new AnnounceFilterResult();
            }
            mAnnounceFilterResult.post();
        }
    }

    public static class AddPrinterAlertDialogFragment extends DialogFragment {

        private String mAddPrintServiceItem;

        @Override
        @SuppressWarnings("unchecked")
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.choose_print_service);

            final List<PrintServiceInfo> printServices = (List<PrintServiceInfo>) (List<?>)
                    getArguments().getParcelableArrayList(FRAGMRNT_ARGUMENT_PRINT_SERVICE_INFOS);

            final ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                    getActivity(), android.R.layout.simple_list_item_1);
            final int printServiceCount = printServices.size();
            for (int i = 0; i < printServiceCount; i++) {
                PrintServiceInfo printService = printServices.get(i);
                adapter.add(printService.getResolveInfo().loadLabel(
                        getActivity().getPackageManager()).toString());
            }
            final String searchUri = Settings.Secure.getString(getActivity().getContentResolver(),
                    Settings.Secure.PRINT_SERVICE_SEARCH_URI);
            final Intent marketIntent;
            if (!TextUtils.isEmpty(searchUri)) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(searchUri));
                if (getActivity().getPackageManager().resolveActivity(intent, 0) != null) {
                    marketIntent = intent;
                    mAddPrintServiceItem = getString(R.string.add_print_service_label);
                    adapter.add(mAddPrintServiceItem);
                } else {
                    marketIntent = null;
                }
            } else {
                marketIntent = null;
            }

            builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String item = adapter.getItem(which);
                    if (item == mAddPrintServiceItem) {
                        try {
                          startActivity(marketIntent);
                      } catch (ActivityNotFoundException anfe) {
                          Log.w(LOG_TAG, "Couldn't start add printer activity", anfe);
                      }
                    } else {
                        PrintServiceInfo printService = printServices.get(which);
                        ComponentName componentName = new ComponentName(
                                printService.getResolveInfo().serviceInfo.packageName,
                                printService.getAddPrintersActivityName());
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.setComponent(componentName);
                        try {
                            startActivity(intent);
                        } catch (ActivityNotFoundException anfe) {
                            Log.w(LOG_TAG, "Couldn't start settings activity", anfe);
                        }
                    }
                }
            });

            return builder.create();
        }
    }

    private final class DestinationAdapter extends BaseAdapter
            implements LoaderManager.LoaderCallbacks<List<PrinterInfo>>, Filterable {

        private final Object mLock = new Object();

        private final List<PrinterInfo> mPrinters = new ArrayList<PrinterInfo>();

        private final List<PrinterInfo> mFilteredPrinters = new ArrayList<PrinterInfo>();

        private CharSequence mLastSearchString;

        public DestinationAdapter() {
            getLoaderManager().initLoader(LOADER_ID_PRINTERS_LOADER, null, this);
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
                        List<PrinterInfo> filteredPrinters = new ArrayList<PrinterInfo>();
                        String constraintLowerCase = constraint.toString().toLowerCase();
                        final int printerCount = mPrinters.size();
                        for (int i = 0; i < printerCount; i++) {
                            PrinterInfo printer = mPrinters.get(i);
                            if (printer.getName().toLowerCase().contains(constraintLowerCase)) {
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
                return mFilteredPrinters.size();
            }
        }

        @Override
        public Object getItem(int position) {
            synchronized (mLock) {
                return mFilteredPrinters.get(position);
            }
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getDropDownView(int position, View convertView,
                ViewGroup parent) {
            return getView(position, convertView, parent);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getActivity().getLayoutInflater().inflate(
                        R.layout.printer_list_item, parent, false);
            }

            convertView.setEnabled(isActionable(position));

            CharSequence title = null;
            CharSequence subtitle = null;
            Drawable icon = null;

            PrinterInfo printer = (PrinterInfo) getItem(position);
            title = printer.getName();
            try {
                PackageManager pm = getActivity().getPackageManager();
                PackageInfo packageInfo = pm.getPackageInfo(printer.getId()
                        .getServiceName().getPackageName(), 0);
                subtitle = packageInfo.applicationInfo.loadLabel(pm);
                icon = packageInfo.applicationInfo.loadIcon(pm);
            } catch (NameNotFoundException nnfe) {
                /* ignore */
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


            ImageView iconView = (ImageView) convertView.findViewById(R.id.icon);
            if (icon != null) {
                iconView.setImageDrawable(icon);
                iconView.setVisibility(View.VISIBLE);
            } else {
                iconView.setVisibility(View.GONE);
            }

            return convertView;
        }

        public boolean isActionable(int position) {
            PrinterInfo printer =  (PrinterInfo) getItem(position);
            return printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE;
        }

        @Override
        public Loader<List<PrinterInfo>> onCreateLoader(int id, Bundle args) {
            if (id == LOADER_ID_PRINTERS_LOADER) {
                return new FusedPrintersProvider(getActivity());
            }
            return null;
        }

        @Override
        public void onLoadFinished(Loader<List<PrinterInfo>> loader,
                List<PrinterInfo> printers) {
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
        public void onLoaderReset(Loader<List<PrinterInfo>> loader) {
            synchronized (mLock) {
                mPrinters.clear();
                mFilteredPrinters.clear();
            }
            notifyDataSetInvalidated();
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
                text = getActivity().getResources().getQuantityString(
                    R.plurals.print_search_result_count_utterance, count, count);
            }
            mListView.announceForAccessibility(text);
        }
    }
}
