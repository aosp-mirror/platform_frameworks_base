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

package com.android.printspooler.ui;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Margins;
import android.print.PrintDocumentInfo;
import android.support.v7.widget.RecyclerView.Adapter;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.TextView;
import com.android.printspooler.R;
import com.android.printspooler.model.PageContentRepository;
import com.android.printspooler.model.PageContentRepository.PageContentProvider;
import com.android.printspooler.util.PageRangeUtils;
import com.android.printspooler.widget.PageContentView;
import dalvik.system.CloseGuard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This class represents the adapter for the pages in the print preview list.
 */
public final class PageAdapter extends Adapter implements
        PageContentRepository.OnMalformedPdfFileListener{
    private static final String LOG_TAG = "PageAdapter";

    private static final int MAX_PREVIEW_PAGES_BATCH = 50;

    private static final boolean DEBUG = true;

    private static final PageRange[] ALL_PAGES_ARRAY = new PageRange[] {
            PageRange.ALL_PAGES
    };

    private static final int INVALID_PAGE_INDEX = -1;

    private static final int STATE_CLOSED = 0;
    private static final int STATE_OPENED = 1;
    private static final int STATE_DESTROYED = 2;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final SparseArray<Void> mBoundPagesInAdapter = new SparseArray<>();
    private final SparseArray<Void> mConfirmedPagesInDocument = new SparseArray<>();

    private final PageClickListener mPageClickListener = new PageClickListener();

    private final Context mContext;
    private final LayoutInflater mLayoutInflater;

    private final ContentCallbacks mCallbacks;
    private final PageContentRepository mPageContentRepository;
    private final PreviewArea mPreviewArea;

    // Which document pages to be written.
    private PageRange[] mRequestedPages;
    // Pages written in the current file.
    private PageRange[] mWrittenPages;
    // Pages the user selected in the UI.
    private PageRange[] mSelectedPages;

    private int mDocumentPageCount = PrintDocumentInfo.PAGE_COUNT_UNKNOWN;
    private int mSelectedPageCount;

    private float mSelectedPageElevation;
    private float mSelectedPageAlpha;

    private float mUnselectedPageElevation;
    private float mUnselectedPageAlpha;

    private int mPreviewPageMargin;
    private int mPreviewPageMinWidth;
    private int mPreviewListPadding;
    private int mFooterHeight;

    private int mColumnCount;

    private MediaSize mMediaSize;
    private Margins mMinMargins;

    private int mState;

    private int mPageContentWidth;
    private int mPageContentHeight;

    public interface ContentCallbacks {
        public void onRequestContentUpdate();
        public void onMalformedPdfFile();
    }

    public interface PreviewArea {
        public int getWidth();
        public int getHeight();
        public void setColumnCount(int columnCount);
        public void setPadding(int left, int top, int right, int bottom);
    }

    public PageAdapter(Context context, ContentCallbacks callbacks, PreviewArea previewArea) {
        mContext = context;
        mCallbacks = callbacks;
        mLayoutInflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        mPageContentRepository = new PageContentRepository(context, this);

        mSelectedPageElevation = mContext.getResources().getDimension(
                R.dimen.selected_page_elevation);
        mSelectedPageAlpha = mContext.getResources().getFraction(
                R.fraction.page_selected_alpha, 1, 1);

        mUnselectedPageElevation = mContext.getResources().getDimension(
                R.dimen.unselected_page_elevation);
        mUnselectedPageAlpha = mContext.getResources().getFraction(
                R.fraction.page_unselected_alpha, 1, 1);

        mPreviewPageMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.preview_page_margin);

        mPreviewPageMinWidth = mContext.getResources().getDimensionPixelSize(
                R.dimen.preview_page_min_width);

        mPreviewListPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.preview_list_padding);

        mColumnCount = mContext.getResources().getInteger(
                R.integer.preview_page_per_row_count);

        mFooterHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.preview_page_footer_height);

        mPreviewArea = previewArea;

        mCloseGuard.open("destroy");

        setHasStableIds(true);

        mState = STATE_CLOSED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_CLOSED");
        }
    }

    @Override
    public void onMalformedPdfFile() {
        mCallbacks.onMalformedPdfFile();
    }

    public void onOrientationChanged() {
        mColumnCount = mContext.getResources().getInteger(
                R.integer.preview_page_per_row_count);
    }

    public boolean isOpened() {
        return mState == STATE_OPENED;
    }

    public int getFilePageCount() {
        return mPageContentRepository.getFilePageCount();
    }

    public void open(ParcelFileDescriptor source, Runnable callback) {
        throwIfNotClosed();
        mState = STATE_OPENED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_OPENED");
        }
        mPageContentRepository.open(source, callback);
    }

    public void update(PageRange[] writtenPages, PageRange[] selectedPages,
            int documentPageCount, MediaSize mediaSize, Margins minMargins) {
        boolean documentChanged = false;
        boolean updatePreviewAreaAndPageSize = false;

        // If the app does not tell how many pages are in the document we cannot
        // optimize and ask for all pages whose count we get from the renderer.
        if (documentPageCount == PrintDocumentInfo.PAGE_COUNT_UNKNOWN) {
            if (writtenPages == null) {
                // If we already requested all pages, just wait.
                if (!Arrays.equals(ALL_PAGES_ARRAY, mRequestedPages)) {
                    mRequestedPages = ALL_PAGES_ARRAY;
                    mCallbacks.onRequestContentUpdate();
                }
                return;
            } else {
                documentPageCount = mPageContentRepository.getFilePageCount();
                if (documentPageCount <= 0) {
                    return;
                }
            }
        }

        if (!Arrays.equals(mSelectedPages, selectedPages)) {
            mSelectedPages = selectedPages;
            mSelectedPageCount = PageRangeUtils.getNormalizedPageCount(
                    mSelectedPages, documentPageCount);
            setConfirmedPages(mSelectedPages, documentPageCount);
            updatePreviewAreaAndPageSize = true;
            documentChanged = true;
        }

        if (mDocumentPageCount != documentPageCount) {
            mDocumentPageCount = documentPageCount;
            documentChanged = true;
        }

        if (mMediaSize == null || !mMediaSize.equals(mediaSize)) {
            mMediaSize = mediaSize;
            updatePreviewAreaAndPageSize = true;
            documentChanged = true;
        }

        if (mMinMargins == null || !mMinMargins.equals(minMargins)) {
            mMinMargins = minMargins;
            updatePreviewAreaAndPageSize = true;
            documentChanged = true;
        }

        // If *all pages* is selected we need to convert that to absolute
        // range as we will be checking if some pages are written or not.
        if (writtenPages != null) {
            // If we get all pages, this means all pages that we requested.
            if (PageRangeUtils.isAllPages(writtenPages)) {
                writtenPages = mRequestedPages;
            }
            if (!Arrays.equals(mWrittenPages, writtenPages)) {
                // TODO: Do a surgical invalidation of only written pages changed.
                mWrittenPages = writtenPages;
                documentChanged = true;
            }
        }

        if (updatePreviewAreaAndPageSize) {
            updatePreviewAreaAndPageSize();
        }

        if (documentChanged) {
            notifyDataSetChanged();
        }
    }

    public void close(Runnable callback) {
        throwIfNotOpened();
        mState = STATE_CLOSED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_CLOSED");
        }
        mPageContentRepository.close(callback);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View page = mLayoutInflater.inflate(R.layout.preview_page, parent, false);
        ViewHolder holder = new MyViewHolder(page);
        holder.setIsRecyclable(true);
        return holder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        if (DEBUG) {
            Log.i(LOG_TAG, "Binding holder: " + holder + " with id: " + getItemId(position)
                    + " for position: " + position);
        }

        final int pageCount = getItemCount();
        MyViewHolder myHolder = (MyViewHolder) holder;

        View page = holder.itemView;
        if (pageCount > 1) {
            page.setOnClickListener(mPageClickListener);
        } else {
            page.setOnClickListener(null);
        }
        page.setTag(holder);

        myHolder.mPageInAdapter = position;

        final int pageInDocument = computePageIndexInDocument(position);
        final int pageIndexInFile = computePageIndexInFile(pageInDocument);

        PageContentView content = (PageContentView) page.findViewById(R.id.page_content);

        LayoutParams params = content.getLayoutParams();
        params.width = mPageContentWidth;
        params.height = mPageContentHeight;

        PageContentProvider provider = content.getPageContentProvider();

        if (pageIndexInFile != INVALID_PAGE_INDEX) {
            if (DEBUG) {
                Log.i(LOG_TAG, "Binding provider:"
                        + " pageIndexInAdapter: " + position
                        + ", pageInDocument: " + pageInDocument
                        + ", pageIndexInFile: " + pageIndexInFile);
            }

            // OK, there are bugs in recycler view which tries to bind views
            // without recycling them which would give us a chane to clean up.
            PageContentProvider boundProvider = mPageContentRepository
                   .peekPageContentProvider(pageIndexInFile);
            if (boundProvider != null) {
                PageContentView owner = (PageContentView) boundProvider.getOwner();
                owner.init(null, mMediaSize, mMinMargins);
                mPageContentRepository.releasePageContentProvider(boundProvider);
            }

            provider = mPageContentRepository.acquirePageContentProvider(
                    pageIndexInFile, content);
            mBoundPagesInAdapter.put(position, null);
        } else {
            onSelectedPageNotInFile(pageInDocument);
        }
        content.init(provider, mMediaSize, mMinMargins);


        View pageSelector = page.findViewById(R.id.page_selector);
        pageSelector.setTag(myHolder);
        if (pageCount > 1) {
            pageSelector.setOnClickListener(mPageClickListener);
            pageSelector.setVisibility(View.VISIBLE);
        } else {
            pageSelector.setOnClickListener(null);
            pageSelector.setVisibility(View.GONE);
        }

        if (mConfirmedPagesInDocument.indexOfKey(pageInDocument) >= 0) {
            pageSelector.setSelected(true);
            page.setTranslationZ(mSelectedPageElevation);
            page.setAlpha(mSelectedPageAlpha);
        } else {
            pageSelector.setSelected(false);
            page.setTranslationZ(mUnselectedPageElevation);
            page.setAlpha(mUnselectedPageAlpha);
        }

        TextView pageNumberView = (TextView) page.findViewById(R.id.page_number);
        String text = mContext.getString(R.string.current_page_template,
                pageInDocument + 1, mDocumentPageCount);
        pageNumberView.setText(text);
    }

    @Override
    public int getItemCount() {
        return mSelectedPageCount;
    }

    @Override
    public long getItemId(int position) {
        return computePageIndexInDocument(position);
    }

    @Override
    public void onViewRecycled(ViewHolder holder) {
        MyViewHolder myHolder = (MyViewHolder) holder;
        PageContentView content = (PageContentView) holder.itemView
                .findViewById(R.id.page_content);
        recyclePageView(content, myHolder.mPageInAdapter);
        myHolder.mPageInAdapter = INVALID_PAGE_INDEX;
    }

    public PageRange[] getRequestedPages() {
        return mRequestedPages;
    }

    public PageRange[] getSelectedPages() {
        PageRange[] selectedPages = computeSelectedPages();
        if (!Arrays.equals(mSelectedPages, selectedPages)) {
            mSelectedPages = selectedPages;
            mSelectedPageCount = PageRangeUtils.getNormalizedPageCount(
                    mSelectedPages, mDocumentPageCount);
            updatePreviewAreaAndPageSize();
            notifyDataSetChanged();
        }
        return mSelectedPages;
    }

    public void onPreviewAreaSizeChanged() {
        if (mMediaSize != null) {
            updatePreviewAreaAndPageSize();
            notifyDataSetChanged();
        }
    }

    private void updatePreviewAreaAndPageSize() {
        final int availableWidth = mPreviewArea.getWidth();
        final int availableHeight = mPreviewArea.getHeight();

        // Page aspect ratio to keep.
        final float pageAspectRatio = (float) mMediaSize.getWidthMils()
                / mMediaSize.getHeightMils();

        // Make sure we have no empty columns.
        final int columnCount = Math.min(mSelectedPageCount, mColumnCount);
        mPreviewArea.setColumnCount(columnCount);

        // Compute max page width.
        final int horizontalMargins = 2 * columnCount * mPreviewPageMargin;
        final int horizontalPaddingAndMargins = horizontalMargins + 2 * mPreviewListPadding;
        final int pageContentDesiredWidth = (int) ((((float) availableWidth
                - horizontalPaddingAndMargins) / columnCount) + 0.5f);

        // Compute max page height.
        final int pageContentDesiredHeight = (int) (((float) pageContentDesiredWidth
                / pageAspectRatio) + 0.5f);

        // If the page does not fit entirely in a vertial direction,
        // we shirk it but not less than the minimal page width.
        final int pageContentMinHeight = (int) (mPreviewPageMinWidth / pageAspectRatio + 0.5f);
        final int pageContentMaxHeight = Math.max(pageContentMinHeight,
                availableHeight - 2 * (mPreviewListPadding + mPreviewPageMargin) - mFooterHeight);

        mPageContentHeight = Math.min(pageContentDesiredHeight, pageContentMaxHeight);
        mPageContentWidth = (int) ((mPageContentHeight * pageAspectRatio) + 0.5f);

        final int totalContentWidth = columnCount * mPageContentWidth + horizontalMargins;
        final int horizontalPadding = (availableWidth - totalContentWidth) / 2;

        final int rowCount = mSelectedPageCount / columnCount
                + ((mSelectedPageCount % columnCount) > 0 ? 1 : 0);
        final int totalContentHeight = rowCount * (mPageContentHeight + mFooterHeight + 2
                * mPreviewPageMargin);

        final int verticalPadding;
        if (mPageContentHeight + mFooterHeight + mPreviewListPadding > availableHeight) {
            verticalPadding = Math.max(mPreviewPageMargin,
                    (availableHeight - totalContentHeight) / 2);
        } else {
            verticalPadding = Math.max(mPreviewListPadding,
                    (availableHeight - totalContentHeight) / 2);
        }

        mPreviewArea.setPadding(horizontalPadding, verticalPadding,
                horizontalPadding, verticalPadding);
    }

    private PageRange[] computeSelectedPages() {
        ArrayList<PageRange> selectedPagesList = new ArrayList<>();

        int startPageIndex = INVALID_PAGE_INDEX;
        int endPageIndex = INVALID_PAGE_INDEX;

        final int pageCount = mConfirmedPagesInDocument.size();
        for (int i = 0; i < pageCount; i++) {
            final int pageIndex = mConfirmedPagesInDocument.keyAt(i);
            if (startPageIndex == INVALID_PAGE_INDEX) {
                startPageIndex = endPageIndex = pageIndex;
            }
            if (endPageIndex + 1 < pageIndex) {
                PageRange pageRange = new PageRange(startPageIndex, endPageIndex);
                selectedPagesList.add(pageRange);
                startPageIndex = pageIndex;
            }
            endPageIndex = pageIndex;
        }

        if (startPageIndex != INVALID_PAGE_INDEX
                && endPageIndex != INVALID_PAGE_INDEX) {
            PageRange pageRange = new PageRange(startPageIndex, endPageIndex);
            selectedPagesList.add(pageRange);
        }

        PageRange[] selectedPages = new PageRange[selectedPagesList.size()];
        selectedPagesList.toArray(selectedPages);

        return selectedPages;
    }

    public void destroy() {
        throwIfNotClosed();
        doDestroy();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mState != STATE_DESTROYED) {
                mCloseGuard.warnIfOpen();
                doDestroy();
            }
        } finally {
            super.finalize();
        }
    }

    private int computePageIndexInDocument(int indexInAdapter) {
        int skippedAdapterPages = 0;
        final int selectedPagesCount = mSelectedPages.length;
        for (int i = 0; i < selectedPagesCount; i++) {
            PageRange pageRange = PageRangeUtils.asAbsoluteRange(
                    mSelectedPages[i], mDocumentPageCount);
            skippedAdapterPages += pageRange.getSize();
            if (skippedAdapterPages > indexInAdapter) {
                final int overshoot = skippedAdapterPages - indexInAdapter - 1;
                return pageRange.getEnd() - overshoot;
            }
        }
        return INVALID_PAGE_INDEX;
    }

    private int computePageIndexInFile(int pageIndexInDocument) {
        if (!PageRangeUtils.contains(mSelectedPages, pageIndexInDocument)) {
            return INVALID_PAGE_INDEX;
        }
        if (mWrittenPages == null) {
            return INVALID_PAGE_INDEX;
        }

        int indexInFile = INVALID_PAGE_INDEX;
        final int rangeCount = mWrittenPages.length;
        for (int i = 0; i < rangeCount; i++) {
            PageRange pageRange = mWrittenPages[i];
            if (!pageRange.contains(pageIndexInDocument)) {
                indexInFile += pageRange.getSize();
            } else {
                indexInFile += pageIndexInDocument - pageRange.getStart() + 1;
                return indexInFile;
            }
        }
        return INVALID_PAGE_INDEX;
    }

    private void setConfirmedPages(PageRange[] pagesInDocument, int documentPageCount) {
        mConfirmedPagesInDocument.clear();
        final int rangeCount = pagesInDocument.length;
        for (int i = 0; i < rangeCount; i++) {
            PageRange pageRange = PageRangeUtils.asAbsoluteRange(pagesInDocument[i],
                    documentPageCount);
            for (int j = pageRange.getStart(); j <= pageRange.getEnd(); j++) {
                mConfirmedPagesInDocument.put(j, null);
            }
        }
    }

    private void onSelectedPageNotInFile(int pageInDocument) {
        PageRange[] requestedPages = computeRequestedPages(pageInDocument);
        if (!Arrays.equals(mRequestedPages, requestedPages)) {
            mRequestedPages = requestedPages;
            if (DEBUG) {
                Log.i(LOG_TAG, "Requesting pages: " + Arrays.toString(mRequestedPages));
            }
            mCallbacks.onRequestContentUpdate();
        }
    }

    private PageRange[] computeRequestedPages(int pageInDocument) {
        if (mRequestedPages != null &&
                PageRangeUtils.contains(mRequestedPages, pageInDocument)) {
            return mRequestedPages;
        }

        List<PageRange> pageRangesList = new ArrayList<>();

        int remainingPagesToRequest = MAX_PREVIEW_PAGES_BATCH;
        final int selectedPagesCount = mSelectedPages.length;

        // We always request the pages that are bound, i.e. shown on screen.
        PageRange[] boundPagesInDocument = computeBoundPagesInDocument();

        final int boundRangeCount = boundPagesInDocument.length;
        for (int i = 0; i < boundRangeCount; i++) {
            PageRange boundRange = boundPagesInDocument[i];
            pageRangesList.add(boundRange);
        }
        remainingPagesToRequest -= PageRangeUtils.getNormalizedPageCount(
                boundPagesInDocument, mDocumentPageCount);

        final boolean requestFromStart = mRequestedPages == null
                || pageInDocument > mRequestedPages[mRequestedPages.length - 1].getEnd();

        if (!requestFromStart) {
            if (DEBUG) {
                Log.i(LOG_TAG, "Requesting from end");
            }

            // Reminder that ranges are always normalized.
            for (int i = selectedPagesCount - 1; i >= 0; i--) {
                if (remainingPagesToRequest <= 0) {
                    break;
                }

                PageRange selectedRange = PageRangeUtils.asAbsoluteRange(mSelectedPages[i],
                        mDocumentPageCount);
                if (pageInDocument < selectedRange.getStart()) {
                    continue;
                }

                PageRange pagesInRange;
                int rangeSpan;

                if (selectedRange.contains(pageInDocument)) {
                    rangeSpan = pageInDocument - selectedRange.getStart() + 1;
                    rangeSpan = Math.min(rangeSpan, remainingPagesToRequest);
                    final int fromPage = Math.max(pageInDocument - rangeSpan - 1, 0);
                    rangeSpan = Math.max(rangeSpan, 0);
                    pagesInRange = new PageRange(fromPage, pageInDocument);
                } else {
                    rangeSpan = selectedRange.getSize();
                    rangeSpan = Math.min(rangeSpan, remainingPagesToRequest);
                    rangeSpan = Math.max(rangeSpan, 0);
                    final int fromPage = Math.max(selectedRange.getEnd() - rangeSpan - 1, 0);
                    final int toPage = selectedRange.getEnd();
                    pagesInRange = new PageRange(fromPage, toPage);
                }

                pageRangesList.add(pagesInRange);
                remainingPagesToRequest -= rangeSpan;
            }
        } else {
            if (DEBUG) {
                Log.i(LOG_TAG, "Requesting from start");
            }

            // Reminder that ranges are always normalized.
            for (int i = 0; i < selectedPagesCount; i++) {
                if (remainingPagesToRequest <= 0) {
                    break;
                }

                PageRange selectedRange = PageRangeUtils.asAbsoluteRange(mSelectedPages[i],
                        mDocumentPageCount);
                if (pageInDocument > selectedRange.getEnd()) {
                    continue;
                }

                PageRange pagesInRange;
                int rangeSpan;

                if (selectedRange.contains(pageInDocument)) {
                    rangeSpan = selectedRange.getEnd() - pageInDocument + 1;
                    rangeSpan = Math.min(rangeSpan, remainingPagesToRequest);
                    final int toPage = Math.min(pageInDocument + rangeSpan - 1,
                            mDocumentPageCount - 1);
                    pagesInRange = new PageRange(pageInDocument, toPage);
                } else {
                    rangeSpan = selectedRange.getSize();
                    rangeSpan = Math.min(rangeSpan, remainingPagesToRequest);
                    final int fromPage = selectedRange.getStart();
                    final int toPage = Math.min(selectedRange.getStart() + rangeSpan - 1,
                            mDocumentPageCount - 1);
                    pagesInRange = new PageRange(fromPage, toPage);
                }

                if (DEBUG) {
                    Log.i(LOG_TAG, "computeRequestedPages() Adding range:" + pagesInRange);
                }
                pageRangesList.add(pagesInRange);
                remainingPagesToRequest -= rangeSpan;
            }
        }

        PageRange[] pageRanges = new PageRange[pageRangesList.size()];
        pageRangesList.toArray(pageRanges);

        return PageRangeUtils.normalize(pageRanges);
    }

    private PageRange[] computeBoundPagesInDocument() {
        List<PageRange> pagesInDocumentList = new ArrayList<>();

        int fromPage = INVALID_PAGE_INDEX;
        int toPage = INVALID_PAGE_INDEX;

        final int boundPageCount = mBoundPagesInAdapter.size();
        for (int i = 0; i < boundPageCount; i++) {
            // The container is a sparse array, so keys are sorted in ascending order.
            final int boundPageInAdapter = mBoundPagesInAdapter.keyAt(i);
            final int boundPageInDocument = computePageIndexInDocument(boundPageInAdapter);

            if (fromPage == INVALID_PAGE_INDEX) {
                fromPage = boundPageInDocument;
            }

            if (toPage == INVALID_PAGE_INDEX) {
                toPage = boundPageInDocument;
            }

            if (boundPageInDocument > toPage + 1) {
                PageRange pageRange = new PageRange(fromPage, toPage);
                pagesInDocumentList.add(pageRange);
                fromPage = toPage = boundPageInDocument;
            } else {
                toPage = boundPageInDocument;
            }
        }

        if (fromPage != INVALID_PAGE_INDEX && toPage != INVALID_PAGE_INDEX) {
            PageRange pageRange = new PageRange(fromPage, toPage);
            pagesInDocumentList.add(pageRange);
        }

        PageRange[] pageInDocument = new PageRange[pagesInDocumentList.size()];
        pagesInDocumentList.toArray(pageInDocument);

        if (DEBUG) {
            Log.i(LOG_TAG, "Bound pages: " + Arrays.toString(pageInDocument));
        }

        return pageInDocument;
    }

    private void recyclePageView(PageContentView page, int pageIndexInAdapter) {
        PageContentProvider provider = page.getPageContentProvider();
        if (provider != null) {
            page.init(null, null, null);
            mPageContentRepository.releasePageContentProvider(provider);
            mBoundPagesInAdapter.remove(pageIndexInAdapter);
        }
        page.setTag(null);
    }

    public void startPreloadContent(PageRange pageRangeInAdapter) {
        final int startPageInDocument = computePageIndexInDocument(pageRangeInAdapter.getStart());
        final int startPageInFile = computePageIndexInFile(startPageInDocument);
        final int endPageInDocument = computePageIndexInDocument(pageRangeInAdapter.getEnd());
        final int endPageInFile = computePageIndexInFile(endPageInDocument);
        if (startPageInDocument != INVALID_PAGE_INDEX && endPageInDocument != INVALID_PAGE_INDEX) {
            mPageContentRepository.startPreload(startPageInFile, endPageInFile);
        }
    }

    public void stopPreloadContent() {
        mPageContentRepository.stopPreload();
    }

    private void doDestroy() {
        mPageContentRepository.destroy();
        mCloseGuard.close();
        mState = STATE_DESTROYED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_DESTROYED");
        }
    }

    private void throwIfNotOpened() {
        if (mState != STATE_OPENED) {
            throw new IllegalStateException("Not opened");
        }
    }

    private void throwIfNotClosed() {
        if (mState != STATE_CLOSED) {
            throw new IllegalStateException("Not closed");
        }
    }

    private final class MyViewHolder extends ViewHolder {
        int mPageInAdapter;

        private MyViewHolder(View itemView) {
            super(itemView);
        }
    }

    private final class PageClickListener implements OnClickListener {
        @Override
        public void onClick(View page) {
            MyViewHolder holder = (MyViewHolder) page.getTag();
            final int pageInAdapter = holder.mPageInAdapter;
            final int pageInDocument = computePageIndexInDocument(pageInAdapter);
            View pageSelector = page.findViewById(R.id.page_selector);
            if (mConfirmedPagesInDocument.indexOfKey(pageInDocument) < 0) {
                mConfirmedPagesInDocument.put(pageInDocument, null);
                pageSelector.setSelected(true);
                page.animate().translationZ(mSelectedPageElevation)
                        .alpha(mSelectedPageAlpha);
            } else {
                mConfirmedPagesInDocument.remove(pageInDocument);
                pageSelector.setSelected(false);
                page.animate().translationZ(mUnselectedPageElevation)
                        .alpha(mUnselectedPageAlpha);
            }
        }
    }
}
