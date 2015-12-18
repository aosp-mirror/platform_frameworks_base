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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Margins;
import android.print.PrintDocumentInfo;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.support.v7.widget.RecyclerView.LayoutManager;
import android.view.View;
import com.android.internal.os.SomeArgs;
import com.android.printspooler.R;
import com.android.printspooler.model.MutexFileProvider;
import com.android.printspooler.widget.PrintContentView;
import com.android.printspooler.widget.EmbeddedContentContainer;
import com.android.printspooler.widget.PrintOptionsLayout;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

class PrintPreviewController implements MutexFileProvider.OnReleaseRequestCallback,
        PageAdapter.PreviewArea, EmbeddedContentContainer.OnSizeChangeListener {

    private final PrintActivity mActivity;

    private final MutexFileProvider mFileProvider;
    private final MyHandler mHandler;

    private final PageAdapter mPageAdapter;
    private final GridLayoutManager mLayoutManger;

    private final PrintOptionsLayout mPrintOptionsLayout;
    private final RecyclerView mRecyclerView;
    private final PrintContentView mContentView;
    private final EmbeddedContentContainer mEmbeddedContentContainer;

    private final PreloadController mPreloadController;

    private int mDocumentPageCount;

    public PrintPreviewController(PrintActivity activity, MutexFileProvider fileProvider) {
        mActivity = activity;
        mHandler = new MyHandler(activity.getMainLooper());
        mFileProvider = fileProvider;

        mPrintOptionsLayout = (PrintOptionsLayout) activity.findViewById(R.id.options_container);
        mPageAdapter = new PageAdapter(activity, activity, this);

        final int columnCount = mActivity.getResources().getInteger(
                R.integer.preview_page_per_row_count);

        mLayoutManger = new GridLayoutManager(mActivity, columnCount);

        mRecyclerView = (RecyclerView) activity.findViewById(R.id.preview_content);
        mRecyclerView.setLayoutManager(mLayoutManger);
        mRecyclerView.setAdapter(mPageAdapter);
        mRecyclerView.setItemViewCacheSize(0);
        mPreloadController = new PreloadController();
        mRecyclerView.addOnScrollListener(mPreloadController);

        mContentView = (PrintContentView) activity.findViewById(R.id.options_content);
        mEmbeddedContentContainer = (EmbeddedContentContainer) activity.findViewById(
                R.id.embedded_content_container);
        mEmbeddedContentContainer.setOnSizeChangeListener(this);
    }

    @Override
    public void onSizeChanged(int width, int height) {
        mPageAdapter.onPreviewAreaSizeChanged();
    }

    public boolean isOptionsOpened() {
        return mContentView.isOptionsOpened();
    }

    public void closeOptions() {
        mContentView.closeOptions();
    }

    public void setUiShown(boolean shown) {
        if (shown) {
            mRecyclerView.setVisibility(View.VISIBLE);
        } else {
            mRecyclerView.setVisibility(View.GONE);
        }
    }

    public void onOrientationChanged() {
        // Adjust the print option column count.
        final int optionColumnCount = mActivity.getResources().getInteger(
                R.integer.print_option_column_count);
        mPrintOptionsLayout.setColumnCount(optionColumnCount);
        mPageAdapter.onOrientationChanged();
    }

    public int getFilePageCount() {
        return mPageAdapter.getFilePageCount();
    }

    public PageRange[] getSelectedPages() {
        return mPageAdapter.getSelectedPages();
    }

    public PageRange[] getRequestedPages() {
        return mPageAdapter.getRequestedPages();
    }

    public void onContentUpdated(boolean documentChanged, int documentPageCount,
            PageRange[] writtenPages, PageRange[] selectedPages, MediaSize mediaSize,
            Margins minMargins) {
        boolean contentChanged = false;

        if (documentChanged) {
            contentChanged = true;
        }

        if (documentPageCount != mDocumentPageCount) {
            mDocumentPageCount = documentPageCount;
            contentChanged = true;
        }

        if (contentChanged) {
            // If not closed, close as we start over.
            if (mPageAdapter.isOpened()) {
                Message operation = mHandler.obtainMessage(MyHandler.MSG_CLOSE);
                mHandler.enqueueOperation(operation);
            }
        }

        // The content changed. In this case we have to invalidate
        // all rendered pages and reopen the file...
        if ((contentChanged || !mPageAdapter.isOpened()) && writtenPages != null) {
            Message operation = mHandler.obtainMessage(MyHandler.MSG_OPEN);
            mHandler.enqueueOperation(operation);
        }

        // Update the attributes before after closed to avoid flicker.
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = writtenPages;
        args.arg2 = selectedPages;
        args.arg3 = mediaSize;
        args.arg4 = minMargins;
        args.argi1 = documentPageCount;

        Message operation = mHandler.obtainMessage(MyHandler.MSG_UPDATE, args);
        mHandler.enqueueOperation(operation);

        // If document changed and has pages we want to start preloading.
        if (contentChanged && writtenPages != null) {
            operation = mHandler.obtainMessage(MyHandler.MSG_START_PRELOAD);
            mHandler.enqueueOperation(operation);
        }
    }

    @Override
    public void onReleaseRequested(final File file) {
        // This is called from the async task's single threaded executor
        // thread, i.e. not on the main thread - so post a message.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // At this point the other end will write to the file, hence
                // we have to close it and reopen after the write completes.
                if (mPageAdapter.isOpened()) {
                    Message operation = mHandler.obtainMessage(MyHandler.MSG_CLOSE);
                    mHandler.enqueueOperation(operation);
                }
            }
        });
    }

    public void destroy(Runnable callback) {
        mHandler.cancelQueuedOperations();
        mRecyclerView.setAdapter(null);
        mPageAdapter.destroy(callback);
    }

    @Override
    public int getWidth() {
        return mEmbeddedContentContainer.getWidth();
    }

    @Override
    public int getHeight() {
        return mEmbeddedContentContainer.getHeight();
    }

    @Override
    public void setColumnCount(int columnCount) {
        mLayoutManger.setSpanCount(columnCount);
    }

    @Override
    public void setPadding(int left, int top , int right, int bottom) {
        mRecyclerView.setPadding(left, top, right, bottom);
    }

    private final class MyHandler extends Handler {
        public static final int MSG_OPEN = 1;
        public static final int MSG_CLOSE = 2;
        public static final int MSG_UPDATE = 4;
        public static final int MSG_START_PRELOAD = 5;

        private boolean mAsyncOperationInProgress;

        private final Runnable mOnAsyncOperationDoneCallback = new Runnable() {
            @Override
            public void run() {
                mAsyncOperationInProgress = false;
                handleNextOperation();
            }
        };

        private final List<Message> mPendingOperations = new ArrayList<>();

        public MyHandler(Looper looper) {
            super(looper, null, false);
        }

        public void cancelQueuedOperations() {
            mPendingOperations.clear();
        }

        public void enqueueOperation(Message message) {
            mPendingOperations.add(message);
            handleNextOperation();
        }

        public void handleNextOperation() {
            while (!mPendingOperations.isEmpty() && !mAsyncOperationInProgress) {
                Message operation = mPendingOperations.remove(0);
                handleMessage(operation);
            }
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_OPEN: {
                    try {
                        File file = mFileProvider.acquireFile(PrintPreviewController.this);
                        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file,
                                ParcelFileDescriptor.MODE_READ_ONLY);

                        mAsyncOperationInProgress = true;
                        mPageAdapter.open(pfd, new Runnable() {
                            @Override
                            public void run() {
                                if (mDocumentPageCount == PrintDocumentInfo.PAGE_COUNT_UNKNOWN) {
                                    mDocumentPageCount = mPageAdapter.getFilePageCount();
                                    mActivity.updateOptionsUi();
                                }
                                mOnAsyncOperationDoneCallback.run();
                            }
                        });
                    } catch (FileNotFoundException fnfe) {
                        /* ignore - file guaranteed to be there */
                    }
                } break;

                case MSG_CLOSE: {
                    mAsyncOperationInProgress = true;
                    mPageAdapter.close(new Runnable() {
                        @Override
                        public void run() {
                            mFileProvider.releaseFile();
                            mOnAsyncOperationDoneCallback.run();
                        }
                    });
                } break;

                case MSG_UPDATE: {
                    SomeArgs args = (SomeArgs) message.obj;
                    PageRange[] writtenPages = (PageRange[]) args.arg1;
                    PageRange[] selectedPages = (PageRange[]) args.arg2;
                    MediaSize mediaSize = (MediaSize) args.arg3;
                    Margins margins = (Margins) args.arg4;
                    final int pageCount = args.argi1;
                    args.recycle();

                    mPageAdapter.update(writtenPages, selectedPages, pageCount,
                            mediaSize, margins);

                } break;

                case MSG_START_PRELOAD: {
                    mPreloadController.startPreloadContent();
                } break;
            }
        }
    }

    private final class PreloadController extends RecyclerView.OnScrollListener {
        private int mOldScrollState;

        public PreloadController() {
            mOldScrollState = mRecyclerView.getScrollState();
        }

        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int state) {
            switch (mOldScrollState) {
                case RecyclerView.SCROLL_STATE_SETTLING: {
                    if (state == RecyclerView.SCROLL_STATE_IDLE
                            || state == RecyclerView.SCROLL_STATE_DRAGGING){
                        startPreloadContent();
                    }
                } break;

                case RecyclerView.SCROLL_STATE_IDLE:
                case RecyclerView.SCROLL_STATE_DRAGGING: {
                    if (state == RecyclerView.SCROLL_STATE_SETTLING) {
                        stopPreloadContent();
                    }
                } break;
            }
            mOldScrollState = state;
        }

        public void startPreloadContent() {
            PageAdapter pageAdapter = (PageAdapter) mRecyclerView.getAdapter();
            if (pageAdapter != null && pageAdapter.isOpened()) {
                PageRange shownPages = computeShownPages();
                if (shownPages != null) {
                    pageAdapter.startPreloadContent(shownPages);
                }
            }
        }

        public void stopPreloadContent() {
            PageAdapter pageAdapter = (PageAdapter) mRecyclerView.getAdapter();
            if (pageAdapter != null && pageAdapter.isOpened()) {
                pageAdapter.stopPreloadContent();
            }
        }

        private PageRange computeShownPages() {
            final int childCount = mRecyclerView.getChildCount();
            if (childCount > 0) {
                LayoutManager layoutManager = mRecyclerView.getLayoutManager();

                View firstChild = layoutManager.getChildAt(0);
                ViewHolder firstHolder = mRecyclerView.getChildViewHolder(firstChild);

                View lastChild = layoutManager.getChildAt(layoutManager.getChildCount() - 1);
                ViewHolder lastHolder = mRecyclerView.getChildViewHolder(lastChild);

                return new PageRange(firstHolder.getLayoutPosition(),
                        lastHolder.getLayoutPosition());
            }
            return null;
        }
    }
}
