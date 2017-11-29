/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Point;
import android.graphics.Rect;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.text.TextUtils;
import android.util.Slog;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutofillWindowPresenter;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ListView;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.server.UiThread;
import libcore.util.Objects;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FillUi {
    private static final String TAG = "FillUi";

    private static final int VISIBLE_OPTIONS_MAX_COUNT = 3;

    private static final TypedValue sTempTypedValue = new TypedValue();

    interface Callback {
        void onResponsePicked(@NonNull FillResponse response);
        void onDatasetPicked(@NonNull Dataset dataset);
        void onCanceled();
        void onDestroy();
        void requestShowFillUi(int width, int height,
                IAutofillWindowPresenter windowPresenter);
        void requestHideFillUi();
        void startIntentSender(IntentSender intentSender);
    }

    private final @NonNull Point mTempPoint = new Point();

    private final @NonNull AutofillWindowPresenter mWindowPresenter =
            new AutofillWindowPresenter();

    private final @NonNull Context mContext;

    private final @NonNull AnchoredWindow mWindow;

    private final @NonNull Callback mCallback;

    private final @NonNull ListView mListView;

    private final @Nullable ItemsAdapter mAdapter;

    private @Nullable String mFilterText;

    private @Nullable AnnounceFilterResult mAnnounceFilterResult;

    private int mContentWidth;
    private int mContentHeight;

    private boolean mDestroyed;

    FillUi(@NonNull Context context, @NonNull FillResponse response,
            @NonNull AutofillId focusedViewId, @NonNull @Nullable String filterText,
            @NonNull OverlayControl overlayControl, @NonNull Callback callback) {
        mContext = context;
        mCallback = callback;

        final LayoutInflater inflater = LayoutInflater.from(context);
        final ViewGroup decor = (ViewGroup) inflater.inflate(
                R.layout.autofill_dataset_picker, null);

        final RemoteViews.OnClickHandler interceptionHandler = new RemoteViews.OnClickHandler() {
            @Override
            public boolean onClickHandler(View view, PendingIntent pendingIntent,
                    Intent fillInIntent) {
                if (pendingIntent != null) {
                    mCallback.startIntentSender(pendingIntent.getIntentSender());
                }
                return true;
            }
        };

        if (response.getAuthentication() != null) {
            mListView = null;
            mAdapter = null;

            final View content;
            try {
                content = response.getPresentation().apply(context, decor, interceptionHandler);
                decor.addView(content);
            } catch (RuntimeException e) {
                callback.onCanceled();
                Slog.e(TAG, "Error inflating remote views", e);
                mWindow = null;
                return;
            }

            Point maxSize = mTempPoint;
            resolveMaxWindowSize(context, maxSize);
            final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxSize.x,
                    MeasureSpec.AT_MOST);
            final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxSize.y,
                    MeasureSpec.AT_MOST);

            decor.measure(widthMeasureSpec, heightMeasureSpec);
            decor.setOnClickListener(v -> mCallback.onResponsePicked(response));
            mContentWidth = content.getMeasuredWidth();
            mContentHeight = content.getMeasuredHeight();

            mWindow = new AnchoredWindow(decor, overlayControl);
            mCallback.requestShowFillUi(mContentWidth, mContentHeight, mWindowPresenter);
        } else {
            final int datasetCount = response.getDatasets().size();
            final ArrayList<ViewItem> items = new ArrayList<>(datasetCount);
            for (int i = 0; i < datasetCount; i++) {
                final Dataset dataset = response.getDatasets().get(i);
                final int index = dataset.getFieldIds().indexOf(focusedViewId);
                if (index >= 0) {
                    final RemoteViews presentation = dataset.getFieldPresentation(index);
                    final View view;
                    try {
                        if (sVerbose) Slog.v(TAG, "setting remote view for " + focusedViewId);
                        view = presentation.apply(context, null, interceptionHandler);
                    } catch (RuntimeException e) {
                        Slog.e(TAG, "Error inflating remote views", e);
                        continue;
                    }
                    final AutofillValue value = dataset.getFieldValues().get(index);
                    String valueText = null;
                    // If the dataset needs auth - don't add its text to allow guessing
                    // its content based on how filtering behaves.
                    if (value != null && value.isText() && dataset.getAuthentication() == null) {
                        valueText = value.getTextValue().toString().toLowerCase();
                    }

                    items.add(new ViewItem(dataset, valueText, view));
                }
            }

            mAdapter = new ItemsAdapter(items);

            mListView = decor.findViewById(R.id.autofill_dataset_list);
            mListView.setAdapter(mAdapter);
            mListView.setVisibility(View.VISIBLE);
            mListView.setOnItemClickListener((adapter, view, position, id) -> {
                final ViewItem vi = mAdapter.getItem(position);
                mCallback.onDatasetPicked(vi.getDataset());
            });

            if (filterText == null) {
                mFilterText = null;
            } else {
                mFilterText = filterText.toLowerCase();
            }

            applyNewFilterText();
            mWindow = new AnchoredWindow(decor, overlayControl);
        }
    }

    private void applyNewFilterText() {
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
                mCallback.requestHideFillUi();
            } else {
                if (updateContentSize()) {
                    mCallback.requestShowFillUi(mContentWidth, mContentHeight, mWindowPresenter);
                }
                if (mAdapter.getCount() > VISIBLE_OPTIONS_MAX_COUNT) {
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

    public void setFilterText(@Nullable String filterText) {
        throwIfDestroyed();
        if (mAdapter == null) {
            // ViewState doesn't not support filtering - typically when it's for an authenticated
            // FillResponse.
            if (TextUtils.isEmpty(filterText)) {
                mCallback.requestShowFillUi(mContentWidth, mContentHeight, mWindowPresenter);
            } else {
                mCallback.requestHideFillUi();
            }
            return;
        }

        if (filterText == null) {
            filterText = null;
        } else {
            filterText = filterText.toLowerCase();
        }

        if (Objects.equal(mFilterText, filterText)) {
            return;
        }
        mFilterText = filterText;

        applyNewFilterText();
    }

    public void destroy() {
        throwIfDestroyed();
        mCallback.onDestroy();
        mCallback.requestHideFillUi();
        mDestroyed = true;
    }

    private boolean updateContentSize() {
        if (mAdapter == null) {
            return false;
        }
        boolean changed = false;
        if (mAdapter.getCount() <= 0) {
            if (mContentWidth != 0) {
                mContentWidth = 0;
                changed = true;
            }
            if (mContentHeight != 0) {
                mContentHeight = 0;
                changed = true;
            }
            return changed;
        }

        Point maxSize = mTempPoint;
        resolveMaxWindowSize(mContext, maxSize);

        mContentWidth = 0;
        mContentHeight = 0;

        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxSize.x,
                MeasureSpec.AT_MOST);
        final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxSize.y,
                MeasureSpec.AT_MOST);
        final int itemCount = mAdapter.getCount();
        for (int i = 0; i < itemCount; i++) {
            View view = mAdapter.getItem(i).getView();
            view.measure(widthMeasureSpec, heightMeasureSpec);
            final int clampedMeasuredWidth = Math.min(view.getMeasuredWidth(), maxSize.x);
            final int newContentWidth = Math.max(mContentWidth, clampedMeasuredWidth);
            if (newContentWidth != mContentWidth) {
                mContentWidth = newContentWidth;
                changed = true;
            }
            // Update the width to fit only the first items up to max count
            if (i < VISIBLE_OPTIONS_MAX_COUNT) {
                final int clampedMeasuredHeight = Math.min(view.getMeasuredHeight(), maxSize.y);
                final int newContentHeight = mContentHeight + clampedMeasuredHeight;
                if (newContentHeight != mContentHeight) {
                    mContentHeight = newContentHeight;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void throwIfDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }

    private static void resolveMaxWindowSize(Context context, Point outPoint) {
        context.getDisplay().getSize(outPoint);
        TypedValue typedValue = sTempTypedValue;
        context.getTheme().resolveAttribute(R.attr.autofillDatasetPickerMaxWidth,
                typedValue, true);
        outPoint.x = (int) typedValue.getFraction(outPoint.x, outPoint.x);
        context.getTheme().resolveAttribute(R.attr.autofillDatasetPickerMaxHeight,
                typedValue, true);
        outPoint.y = (int) typedValue.getFraction(outPoint.y, outPoint.y);
    }

    private static class ViewItem {
        private final String mValue;
        private final Dataset mDataset;
        private final View mView;

        ViewItem(Dataset dataset, String value, View view) {
            mDataset = dataset;
            mValue = value;
            mView = view;
        }

        public View getView() {
            return mView;
        }

        public Dataset getDataset() {
            return mDataset;
        }

        public String getValue() {
            return mValue;
        }

        @Override
        public String toString() {
            // Used for filtering in the adapter
            return mValue;
        }
    }

    private final class AutofillWindowPresenter extends IAutofillWindowPresenter.Stub {
        @Override
        public void show(WindowManager.LayoutParams p, Rect transitionEpicenter,
                boolean fitsSystemWindows, int layoutDirection) {
            if (sVerbose) {
                Slog.v(TAG, "AutofillWindowPresenter.show(): fit=" + fitsSystemWindows
                        + ", epicenter="+ transitionEpicenter + ", dir=" + layoutDirection
                        + ", params=" + p);
            }
            UiThread.getHandler().post(() -> mWindow.show(p));
        }

        @Override
        public void hide(Rect transitionEpicenter) {
            UiThread.getHandler().post(mWindow::hide);
        }
    }

    final class AnchoredWindow implements View.OnTouchListener {
        private final @NonNull OverlayControl mOverlayControl;
        private final WindowManager mWm;
        private final View mContentView;
        private boolean mShowing;

        /**
         * Constructor.
         *
         * @param contentView content of the window
         */
        AnchoredWindow(View contentView, @NonNull OverlayControl overlayControl) {
            mWm = contentView.getContext().getSystemService(WindowManager.class);
            mContentView = contentView;
            mOverlayControl = overlayControl;
        }

        /**
         * Shows the window.
         */
        public void show(WindowManager.LayoutParams params) {
            if (sVerbose) Slog.v(TAG, "show(): showing=" + mShowing + ", params="+  params);
            try {
                if (!mShowing) {
                    params.accessibilityTitle = mContentView.getContext()
                            .getString(R.string.autofill_picker_accessibility_title);
                    mWm.addView(mContentView, params);
                    mContentView.setOnTouchListener(this);
                    mOverlayControl.hideOverlays();
                    mShowing = true;
                } else {
                    mWm.updateViewLayout(mContentView, params);
                }
            } catch (WindowManager.BadTokenException e) {
                if (sDebug) Slog.d(TAG, "Filed with with token " + params.token + " gone.");
                mCallback.onDestroy();
            } catch (IllegalStateException e) {
                // WM throws an ISE if mContentView was added twice; this should never happen -
                // since show() and hide() are always called in the UIThread - but when it does,
                // it should not crash the system.
                Slog.e(TAG, "Exception showing window " + params, e);
                mCallback.onDestroy();
            }
        }

        /**
         * Hides the window.
         */
        void hide() {
            try {
                if (mShowing) {
                    mContentView.setOnTouchListener(null);
                    mWm.removeView(mContentView);
                    mShowing = false;
                }
            } catch (IllegalStateException e) {
                // WM might thrown an ISE when removing the mContentView; this should never
                // happen - since show() and hide() are always called in the UIThread - but if it
                // does, it should not crash the system.
                Slog.e(TAG, "Exception hiding window ", e);
                mCallback.onDestroy();
            } finally {
                mOverlayControl.showOverlays();
            }
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            // When the window is touched outside, hide the window.
            if (view == mContentView && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                mCallback.onCanceled();
                return true;
            }
            return false;
        }

    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mCallback: "); pw.println(mCallback != null);
        pw.print(prefix); pw.print("mListView: "); pw.println(mListView);
        pw.print(prefix); pw.print("mAdapter: "); pw.println(mAdapter != null);
        pw.print(prefix); pw.print("mFilterText: "); pw.println(mFilterText);
        pw.print(prefix); pw.print("mContentWidth: "); pw.println(mContentWidth);
        pw.print(prefix); pw.print("mContentHeight: "); pw.println(mContentHeight);
        pw.print(prefix); pw.print("mDestroyed: "); pw.println(mDestroyed);
        pw.print(prefix); pw.print("mWindow: ");
        if (mWindow == null) {
            pw.println("N/A");
        } else {
            final String prefix2 = prefix + "  ";
            pw.println();
            pw.print(prefix2); pw.print("showing: "); pw.println(mWindow.mShowing);
            pw.print(prefix2); pw.print("view: "); pw.println(mWindow.mContentView);
            pw.print(prefix2); pw.print("screen coordinates: ");
            if (mWindow.mContentView == null) {
                pw.println("N/A");
            } else {
                final int[] coordinates = mWindow.mContentView.getLocationOnScreen();
                pw.print(coordinates[0]); pw.print("x"); pw.println(coordinates[1]);
            }
        }
    }

    private void announceSearchResultIfNeeded() {
        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            if (mAnnounceFilterResult == null) {
                mAnnounceFilterResult = new AnnounceFilterResult();
            }
            mAnnounceFilterResult.post();
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
                protected FilterResults performFiltering(CharSequence constraint) {
                    // No locking needed as mAllItems is final an immutable
                    final FilterResults results = new FilterResults();
                    if (TextUtils.isEmpty(constraint)) {
                        results.values = mAllItems;
                        results.count = mAllItems.size();
                        return results;
                    }
                    final List<ViewItem> filteredItems = new ArrayList<>();
                    final String constraintLowerCase = constraint.toString().toLowerCase();
                    final int itemCount = mAllItems.size();
                    for (int i = 0; i < itemCount; i++) {
                        final ViewItem item = mAllItems.get(i);
                        final String value = item.getValue();
                        // No value, i.e. null, matches any filter
                        if ((value == null && item.mDataset.getAuthentication() == null)
                                || (value != null
                                        && value.toLowerCase().startsWith(constraintLowerCase))) {
                            filteredItems.add(item);
                        }
                    }
                    results.values = filteredItems;
                    results.count = filteredItems.size();
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    final boolean resultCountChanged;
                    final int oldItemCount = mFilteredItems.size();
                    mFilteredItems.clear();
                    if (results.count > 0) {
                        @SuppressWarnings("unchecked")
                        final List<ViewItem> items = (List<ViewItem>) results.values;
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
            return getItem(position).getView();
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
                text = mContext.getString(R.string.autofill_picker_no_suggestions);
            } else {
                text = mContext.getResources().getQuantityString(
                        R.plurals.autofill_picker_some_suggestions, count, count);
            }
            mListView.announceForAccessibility(text);
        }
    }
}
