/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentSender;
import android.graphics.drawable.Drawable;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.text.TextUtils;
import android.util.PluralsMessageFormatter;
import android.util.Slog;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.R;
import com.android.server.autofill.AutofillManagerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A dialog to show Autofill suggestions.
 *
 * This fill dialog UI shows as a bottom sheet style dialog. This dialog UI
 * provides a larger area to display the suggestions, it provides a more
 * conspicuous and efficient interface to the user. So it is easy for users
 * to pay attention to the datasets and selecting one of them.
 */
final class DialogFillUi {

    private static final String TAG = "DialogFillUi";
    private static final int THEME_ID_LIGHT =
            R.style.Theme_DeviceDefault_Light_Autofill_Save;
    private static final int THEME_ID_DARK =
            R.style.Theme_DeviceDefault_Autofill_Save;

    interface UiCallback {
        void onResponsePicked(@NonNull FillResponse response);
        void onDatasetPicked(@NonNull Dataset dataset);
        void onDismissed();
        void onCanceled();
        void startIntentSender(IntentSender intentSender);
    }

    private final @NonNull Dialog mDialog;
    private final @NonNull OverlayControl mOverlayControl;
    private final String mServicePackageName;
    private final ComponentName mComponentName;
    private final int mThemeId;
    private final @NonNull Context mContext;
    private final @NonNull UiCallback mCallback;
    private final @NonNull ListView mListView;
    private final @Nullable ItemsAdapter mAdapter;
    private final int mVisibleDatasetsMaxCount;

    private @Nullable String mFilterText;
    private @Nullable AnnounceFilterResult mAnnounceFilterResult;
    private boolean mDestroyed;

    DialogFillUi(@NonNull Context context, @NonNull FillResponse response,
            @NonNull AutofillId focusedViewId, @Nullable String filterText,
            @Nullable Drawable serviceIcon, @Nullable String servicePackageName,
            @Nullable ComponentName componentName, @NonNull OverlayControl overlayControl,
            boolean nightMode, @NonNull UiCallback callback) {
        if (sVerbose) Slog.v(TAG, "nightMode: " + nightMode);
        mThemeId = nightMode ? THEME_ID_DARK : THEME_ID_LIGHT;
        mCallback = callback;
        mOverlayControl = overlayControl;
        mServicePackageName = servicePackageName;
        mComponentName = componentName;

        mContext = new ContextThemeWrapper(context, mThemeId);
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        final View decor = inflater.inflate(R.layout.autofill_fill_dialog, null);

        setServiceIcon(decor, serviceIcon);
        setHeader(decor, response);

        mVisibleDatasetsMaxCount = getVisibleDatasetsMaxCount();

        if (response.getAuthentication() != null) {
            mListView = null;
            mAdapter = null;
            try {
                initialAuthenticationLayout(decor, response);
            } catch (RuntimeException e) {
                callback.onCanceled();
                Slog.e(TAG, "Error inflating remote views", e);
                mDialog = null;
                return;
            }
        } else {
            final List<ViewItem> items = createDatasetItems(response, focusedViewId);
            mAdapter = new ItemsAdapter(items);
            mListView = decor.findViewById(R.id.autofill_dialog_list);
            initialDatasetLayout(decor, filterText);
        }

        setDismissButton(decor);

        mDialog = new Dialog(mContext, mThemeId);
        mDialog.setContentView(decor);
        setDialogParamsAsBottomSheet();
        mDialog.setOnCancelListener((d) -> mCallback.onCanceled());

        show();
    }

    private int getVisibleDatasetsMaxCount() {
        if (AutofillManagerService.getVisibleDatasetsMaxCount() > 0) {
            final int maxCount = AutofillManagerService.getVisibleDatasetsMaxCount();
            if (sVerbose) {
                Slog.v(TAG, "overriding maximum visible datasets to " + maxCount);
            }
            return maxCount;
        } else {
            return mContext.getResources()
                    .getInteger(com.android.internal.R.integer.autofill_max_visible_datasets);
        }
    }

    private void setDialogParamsAsBottomSheet() {
        final Window window = mDialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.6f);
        window.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER);
        window.setCloseOnTouchOutside(true);
        final WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.accessibilityTitle =
                mContext.getString(R.string.autofill_picker_accessibility_title);
        params.windowAnimations = R.style.AutofillSaveAnimation;
    }

    private void setServiceIcon(View decor, Drawable serviceIcon) {
        if (serviceIcon == null) {
            return;
        }

        final ImageView iconView = decor.findViewById(R.id.autofill_service_icon);
        final int actualWidth = serviceIcon.getMinimumWidth();
        final int actualHeight = serviceIcon.getMinimumHeight();
        if (sDebug) {
            Slog.d(TAG, "Adding service icon "
                    + "(" + actualWidth + "x" + actualHeight + ")");
        }
        iconView.setImageDrawable(serviceIcon);
        iconView.setVisibility(View.VISIBLE);
    }

    private void setHeader(View decor, FillResponse response) {
        final RemoteViews presentation = response.getDialogHeader();
        if (presentation == null) {
            return;
        }

        final ViewGroup container = decor.findViewById(R.id.autofill_dialog_header);
        final RemoteViews.InteractionHandler interceptionHandler = (view, pendingIntent, r) -> {
            if (pendingIntent != null) {
                mCallback.startIntentSender(pendingIntent.getIntentSender());
            }
            return true;
        };

        final View content = presentation.applyWithTheme(
                mContext, (ViewGroup) decor, interceptionHandler, mThemeId);
        container.addView(content);
        container.setVisibility(View.VISIBLE);
    }

    private void setDismissButton(View decor) {
        final TextView noButton = decor.findViewById(R.id.autofill_dialog_no);
        // set "No thinks" by default
        noButton.setText(R.string.autofill_save_no);
        noButton.setOnClickListener((v) -> mCallback.onDismissed());
    }

    private void setContinueButton(View decor, View.OnClickListener listener) {
        final TextView yesButton = decor.findViewById(R.id.autofill_dialog_yes);
        // set "Continue" by default
        yesButton.setText(R.string.autofill_continue_yes);
        yesButton.setOnClickListener(listener);
        yesButton.setVisibility(View.VISIBLE);
    }

    private void initialAuthenticationLayout(View decor, FillResponse response) {
        RemoteViews presentation = response.getDialogPresentation();
        if (presentation == null) {
            presentation = response.getPresentation();
        }
        if (presentation == null) {
            throw new RuntimeException("No presentation for fill dialog authentication");
        }

        // insert authentication item under autofill_dialog_container
        final ViewGroup container = decor.findViewById(R.id.autofill_dialog_container);
        final RemoteViews.InteractionHandler interceptionHandler = (view, pendingIntent, r) -> {
            if (pendingIntent != null) {
                mCallback.startIntentSender(pendingIntent.getIntentSender());
            }
            return true;
        };
        final View content = presentation.applyWithTheme(
                mContext, (ViewGroup) decor, interceptionHandler, mThemeId);
        container.addView(content);
        container.setVisibility(View.VISIBLE);
        container.setFocusable(true);
        container.setOnClickListener(v -> mCallback.onResponsePicked(response));
        // just single item, set up continue button
        setContinueButton(decor, v -> mCallback.onResponsePicked(response));
    }

    private ArrayList<ViewItem> createDatasetItems(FillResponse response,
            AutofillId focusedViewId) {
        final int datasetCount = response.getDatasets().size();
        if (sVerbose) {
            Slog.v(TAG, "Number datasets: " + datasetCount + " max visible: "
                    + mVisibleDatasetsMaxCount);
        }

        final RemoteViews.InteractionHandler interceptionHandler = (view, pendingIntent, r) -> {
            if (pendingIntent != null) {
                mCallback.startIntentSender(pendingIntent.getIntentSender());
            }
            return true;
        };

        final ArrayList<ViewItem> items = new ArrayList<>(datasetCount);
        for (int i = 0; i < datasetCount; i++) {
            final Dataset dataset = response.getDatasets().get(i);
            final int index = dataset.getFieldIds().indexOf(focusedViewId);
            if (index >= 0) {
                RemoteViews presentation = dataset.getFieldDialogPresentation(index);
                if (presentation == null) {
                    if (sDebug) {
                        Slog.w(TAG, "not displaying UI on field " + focusedViewId + " because "
                                + "service didn't provide a presentation for it on " + dataset);
                    }
                    continue;
                }
                final View view;
                try {
                    if (sVerbose) Slog.v(TAG, "setting remote view for " + focusedViewId);
                    view = presentation.applyWithTheme(
                            mContext, null, interceptionHandler, mThemeId);
                } catch (RuntimeException e) {
                    Slog.e(TAG, "Error inflating remote views", e);
                    continue;
                }
                // TODO: Extract the shared filtering logic here and in FillUi to a common
                //  method.
                final Dataset.DatasetFieldFilter filter = dataset.getFilter(index);
                Pattern filterPattern = null;
                String valueText = null;
                boolean filterable = true;
                if (filter == null) {
                    final AutofillValue value = dataset.getFieldValues().get(index);
                    if (value != null && value.isText()) {
                        valueText = value.getTextValue().toString().toLowerCase();
                    }
                } else {
                    filterPattern = filter.pattern;
                    if (filterPattern == null) {
                        if (sVerbose) {
                            Slog.v(TAG, "Explicitly disabling filter at id " + focusedViewId
                                    + " for dataset #" + index);
                        }
                        filterable = false;
                    }
                }

                items.add(new ViewItem(dataset, filterPattern, filterable, valueText, view));
            }
        }
        return items;
    }

    private void initialDatasetLayout(View decor, String filterText) {
        final AdapterView.OnItemClickListener onItemClickListener =
                (adapter, view, position, id) -> {
                    final ViewItem vi = mAdapter.getItem(position);
                    mCallback.onDatasetPicked(vi.dataset);
                };

        mListView.setAdapter(mAdapter);
        mListView.setVisibility(View.VISIBLE);
        mListView.setOnItemClickListener(onItemClickListener);

        if (mAdapter.getCount() == 1) {
            // just single item, set up continue button
            setContinueButton(decor, (v) ->
                    onItemClickListener.onItemClick(null, null, 0, 0));
        }

        if (filterText == null) {
            mFilterText = null;
        } else {
            mFilterText = filterText.toLowerCase();
        }

        final int oldCount = mAdapter.getCount();
        mAdapter.getFilter().filter(mFilterText, (count) -> {
            if (mDestroyed) {
                return;
            }
            if (count <= 0) {
                if (sDebug) {
                    final int size = mFilterText == null ? 0 : mFilterText.length();
                    Slog.d(TAG, "No dataset matches filter with " + size + " chars");
                }
                mCallback.onCanceled();
            } else {

                if (mAdapter.getCount() > mVisibleDatasetsMaxCount) {
                    mListView.setVerticalScrollBarEnabled(true);
                    mListView.onVisibilityAggregated(true);
                } else {
                    mListView.setVerticalScrollBarEnabled(false);
                }
                if (mAdapter.getCount() != oldCount) {
                    mListView.requestLayout();
                }
            }
        });
    }

    private void show() {
        Slog.i(TAG, "Showing fill dialog");
        mDialog.show();
        mOverlayControl.hideOverlays();
    }

    boolean isShowing() {
        return mDialog.isShowing();
    }

    void hide() {
        if (sVerbose) Slog.v(TAG, "Hiding fill dialog.");
        try {
            mDialog.hide();
        } finally {
            mOverlayControl.showOverlays();
        }
    }

    void destroy() {
        try {
            if (sDebug) Slog.d(TAG, "destroy()");
            throwIfDestroyed();

            mDialog.dismiss();
            mDestroyed = true;
        } finally {
            mOverlayControl.showOverlays();
        }
    }

    private void throwIfDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }

    @Override
    public String toString() {
        // TODO toString
        return "NO TITLE";
    }

    void dump(PrintWriter pw, String prefix) {

        pw.print(prefix); pw.print("service: "); pw.println(mServicePackageName);
        pw.print(prefix); pw.print("app: "); pw.println(mComponentName.toShortString());
        pw.print(prefix); pw.print("theme id: "); pw.print(mThemeId);
        switch (mThemeId) {
            case THEME_ID_DARK:
                pw.println(" (dark)");
                break;
            case THEME_ID_LIGHT:
                pw.println(" (light)");
                break;
            default:
                pw.println("(UNKNOWN_MODE)");
                break;
        }
        final View view = mDialog.getWindow().getDecorView();
        final int[] loc = view.getLocationOnScreen();
        pw.print(prefix); pw.print("coordinates: ");
            pw.print('('); pw.print(loc[0]); pw.print(','); pw.print(loc[1]); pw.print(')');
            pw.print('(');
                pw.print(loc[0] + view.getWidth()); pw.print(',');
                pw.print(loc[1] + view.getHeight()); pw.println(')');
        pw.print(prefix); pw.print("destroyed: "); pw.println(mDestroyed);
    }

    private void announceSearchResultIfNeeded() {
        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            if (mAnnounceFilterResult == null) {
                mAnnounceFilterResult = new AnnounceFilterResult();
            }
            mAnnounceFilterResult.post();
        }
    }

    // TODO: Below code copied from FullUi, Extract the shared filtering logic here
    // and in FillUi to a common method.
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
                text = mContext.getString(R.string.autofill_picker_no_suggestions);
            } else {
                Map<String, Object> arguments = new HashMap<>();
                arguments.put("count", count);
                text = PluralsMessageFormatter.format(mContext.getResources(),
                        arguments,
                        R.string.autofill_picker_some_suggestions);
            }
            mListView.announceForAccessibility(text);
        }
    }

    private final class ItemsAdapter extends BaseAdapter implements Filterable {
        private @NonNull final List<ViewItem> mAllItems;

        private @NonNull final List<ViewItem> mFilteredItems = new ArrayList<>();

        ItemsAdapter(@NonNull List<ViewItem> items) {
            mAllItems = Collections.unmodifiableList(new ArrayList<>(items));
            mFilteredItems.addAll(items);
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence filterText) {
                    // No locking needed as mAllItems is final an immutable
                    final List<ViewItem> filtered = mAllItems.stream()
                            .filter((item) -> item.matches(filterText))
                            .collect(Collectors.toList());
                    final FilterResults results = new FilterResults();
                    results.values = filtered;
                    results.count = filtered.size();
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    final boolean resultCountChanged;
                    final int oldItemCount = mFilteredItems.size();
                    mFilteredItems.clear();
                    if (results.count > 0) {
                        @SuppressWarnings("unchecked") final List<ViewItem> items =
                                (List<ViewItem>) results.values;
                        mFilteredItems.addAll(items);
                    }
                    resultCountChanged = (oldItemCount != mFilteredItems.size());
                    if (resultCountChanged) {
                        announceSearchResultIfNeeded();
                    }
                    notifyDataSetChanged();
                }
            };
        }

        @Override
        public int getCount() {
            return mFilteredItems.size();
        }

        @Override
        public ViewItem getItem(int position) {
            return mFilteredItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return getItem(position).view;
        }

        @Override
        public String toString() {
            return "ItemsAdapter: [all=" + mAllItems + ", filtered=" + mFilteredItems + "]";
        }
    }


    /**
     * An item for the list view - either a (clickable) dataset or a (read-only) header / footer.
     */
    private static class ViewItem {
        public final @Nullable String value;
        public final @Nullable Dataset dataset;
        public final @NonNull View view;
        public final @Nullable Pattern filter;
        public final boolean filterable;

        /**
         * Default constructor.
         *
         * @param dataset dataset associated with the item
         * @param filter optional filter set by the service to determine how the item should be
         * filtered
         * @param filterable optional flag set by the service to indicate this item should not be
         * filtered (typically used when the dataset has value but it's sensitive, like a password)
         * @param value dataset value
         * @param view dataset presentation.
         */
        ViewItem(@NonNull Dataset dataset, @Nullable Pattern filter, boolean filterable,
                @Nullable String value, @NonNull View view) {
            this.dataset = dataset;
            this.value = value;
            this.view = view;
            this.filter = filter;
            this.filterable = filterable;
        }

        /**
         * Returns whether this item matches the value input by the user so it can be included
         * in the filtered datasets.
         */
        public boolean matches(CharSequence filterText) {
            if (TextUtils.isEmpty(filterText)) {
                // Always show item when the user input is empty
                return true;
            }
            if (!filterable) {
                // Service explicitly disabled filtering using a null Pattern.
                return false;
            }
            final String constraintLowerCase = filterText.toString().toLowerCase();
            if (filter != null) {
                // Uses pattern provided by service
                return filter.matcher(constraintLowerCase).matches();
            } else {
                // Compares it with dataset value with dataset
                return (value == null)
                        ? (dataset.getAuthentication() == null)
                        : value.toLowerCase().startsWith(constraintLowerCase);
            }
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("ViewItem:[view=")
                    .append(view.getAutofillId());
            final String datasetId = dataset == null ? null : dataset.getId();
            if (datasetId != null) {
                builder.append(", dataset=").append(datasetId);
            }
            if (value != null) {
                // Cannot print value because it could contain PII
                builder.append(", value=").append(value.length()).append("_chars");
            }
            if (filterable) {
                builder.append(", filterable");
            }
            if (filter != null) {
                // Filter should not have PII, but it could be a huge regexp
                builder.append(", filter=").append(filter.pattern().length()).append("_chars");
            }
            return builder.append(']').toString();
        }
    }
}
