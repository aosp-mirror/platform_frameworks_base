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

package com.android.printspooler.model;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.AsyncTask;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Margins;
import android.print.PrintDocumentInfo;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import dalvik.system.CloseGuard;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PageContentRepository {
    private static final String LOG_TAG = "PageContentRepository";

    private static final boolean DEBUG = false;

    private static final int INVALID_PAGE_INDEX = -1;

    private static final int STATE_CLOSED = 0;
    private static final int STATE_OPENED = 1;
    private static final int STATE_DESTROYED = 2;

    private static final int BYTES_PER_PIXEL = 4;

    private static final int BYTES_PER_MEGABYTE = 1048576;

    private static final int MILS_PER_INCH = 1000;
    private static final int POINTS_IN_INCH = 72;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final ArrayMap<Integer, PageContentProvider> mPageContentProviders =
            new ArrayMap<>();

    private final AsyncRenderer mRenderer;

    private RenderSpec mLastRenderSpec;

    private int mScheduledPreloadFirstShownPage = INVALID_PAGE_INDEX;

    private int mScheduledPreloadLastShownPage = INVALID_PAGE_INDEX;

    private int mState;

    public interface OnPageContentAvailableCallback {
        public void onPageContentAvailable(BitmapDrawable content);
    }

    public PageContentRepository(Context context) {
        mRenderer = new AsyncRenderer(context);
        mState = STATE_CLOSED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_CLOSED");
        }
        mCloseGuard.open("destroy");
    }

    public void open(ParcelFileDescriptor source, final Runnable callback) {
        throwIfNotClosed();
        mState = STATE_OPENED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_OPENED");
        }
        mRenderer.open(source, callback);
    }

    public void close(Runnable callback) {
        throwIfNotOpened();
        mState = STATE_CLOSED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_CLOSED");
        }

        mRenderer.close(callback);
    }

    public void destroy() {
        throwIfNotClosed();
        mState = STATE_DESTROYED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_DESTROYED");
        }
        throwIfNotClosed();
        doDestroy();
    }

    public void startPreload(int firstShownPage, int lastShownPage) {
        // If we do not have a render spec we have no clue what size the
        // preloaded bitmaps should be, so just take a note for what to do.
        if (mLastRenderSpec == null) {
            mScheduledPreloadFirstShownPage = firstShownPage;
            mScheduledPreloadLastShownPage = lastShownPage;
        } else {
            mRenderer.startPreload(firstShownPage, lastShownPage, mLastRenderSpec);
        }
    }

    public void stopPreload() {
        mRenderer.stopPreload();
    }

    public int getFilePageCount() {
        return mRenderer.getPageCount();
    }

    public PageContentProvider peekPageContentProvider(int pageIndex) {
        return mPageContentProviders.get(pageIndex);
    }

    public PageContentProvider acquirePageContentProvider(int pageIndex, View owner) {
        throwIfDestroyed();

        if (DEBUG) {
            Log.i(LOG_TAG, "Acquiring provider for page: " + pageIndex);
        }

        if (mPageContentProviders.get(pageIndex)!= null) {
            throw new IllegalStateException("Already acquired for page: " + pageIndex);
        }

        PageContentProvider provider = new PageContentProvider(pageIndex, owner);

        mPageContentProviders.put(pageIndex, provider);

        return provider;
    }

    public void releasePageContentProvider(PageContentProvider provider) {
        throwIfDestroyed();

        if (DEBUG) {
            Log.i(LOG_TAG, "Releasing provider for page: " + provider.mPageIndex);
        }

        if (mPageContentProviders.remove(provider.mPageIndex) == null) {
            throw new IllegalStateException("Not acquired");
        }

        provider.cancelLoad();
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

    private void doDestroy() {
        mState = STATE_DESTROYED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_DESTROYED");
        }
        mRenderer.destroy();
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

    private void throwIfDestroyed() {
        if (mState == STATE_DESTROYED) {
            throw new IllegalStateException("Destroyed");
        }
    }

    public final class PageContentProvider {
        private final int mPageIndex;
        private View mOwner;

        public PageContentProvider(int pageIndex, View owner) {
            mPageIndex = pageIndex;
            mOwner = owner;
        }

        public View getOwner() {
            return mOwner;
        }

        public int getPageIndex() {
            return mPageIndex;
        }

        public void getPageContent(RenderSpec renderSpec, OnPageContentAvailableCallback callback) {
            throwIfDestroyed();

            mLastRenderSpec = renderSpec;

            // We tired to preload but didn't know the bitmap size, now
            // that we know let us do the work.
            if (mScheduledPreloadFirstShownPage != INVALID_PAGE_INDEX
                    && mScheduledPreloadLastShownPage != INVALID_PAGE_INDEX) {
                startPreload(mScheduledPreloadFirstShownPage, mScheduledPreloadLastShownPage);
                mScheduledPreloadFirstShownPage = INVALID_PAGE_INDEX;
                mScheduledPreloadLastShownPage = INVALID_PAGE_INDEX;
            }

            if (mState == STATE_OPENED) {
                mRenderer.renderPage(mPageIndex, renderSpec, callback);
            } else {
                mRenderer.getCachedPage(mPageIndex, renderSpec, callback);
            }
        }

        void cancelLoad() {
            throwIfDestroyed();

            if (mState == STATE_OPENED) {
                mRenderer.cancelRendering(mPageIndex);
            }
        }
    }

    private static final class PageContentLruCache {
        private final LinkedHashMap<Integer, RenderedPage> mRenderedPages =
                new LinkedHashMap<>();

        private final int mMaxSizeInBytes;

        private int mSizeInBytes;

        public PageContentLruCache(int maxSizeInBytes) {
            mMaxSizeInBytes = maxSizeInBytes;
        }

        public RenderedPage getRenderedPage(int pageIndex) {
            return mRenderedPages.get(pageIndex);
        }

        public RenderedPage removeRenderedPage(int pageIndex) {
            RenderedPage page = mRenderedPages.remove(pageIndex);
            if (page != null) {
                mSizeInBytes -= page.getSizeInBytes();
            }
            return page;
        }

        public RenderedPage putRenderedPage(int pageIndex, RenderedPage renderedPage) {
            RenderedPage oldRenderedPage = mRenderedPages.remove(pageIndex);
            if (oldRenderedPage != null) {
                if (!oldRenderedPage.renderSpec.equals(renderedPage.renderSpec)) {
                    throw new IllegalStateException("Wrong page size");
                }
            } else {
                final int contentSizeInBytes = renderedPage.getSizeInBytes();
                if (mSizeInBytes + contentSizeInBytes > mMaxSizeInBytes) {
                    throw new IllegalStateException("Client didn't free space");
                }

                mSizeInBytes += contentSizeInBytes;
            }
            return mRenderedPages.put(pageIndex, renderedPage);
        }

        public void invalidate() {
            for (Map.Entry<Integer, RenderedPage> entry : mRenderedPages.entrySet()) {
                entry.getValue().state = RenderedPage.STATE_SCRAP;
            }
        }

        public RenderedPage removeLeastNeeded() {
            if (mRenderedPages.isEmpty()) {
                return null;
            }

            // First try to remove a rendered page that holds invalidated
            // or incomplete content, i.e. its render spec is null.
            for (Map.Entry<Integer, RenderedPage> entry : mRenderedPages.entrySet()) {
                RenderedPage renderedPage = entry.getValue();
                if (renderedPage.state == RenderedPage.STATE_SCRAP) {
                    Integer pageIndex = entry.getKey();
                    mRenderedPages.remove(pageIndex);
                    mSizeInBytes -= renderedPage.getSizeInBytes();
                    return renderedPage;
                }
            }

            // If all rendered pages contain rendered content, then use the oldest.
            final int pageIndex = mRenderedPages.eldest().getKey();
            RenderedPage renderedPage = mRenderedPages.remove(pageIndex);
            mSizeInBytes -= renderedPage.getSizeInBytes();
            return renderedPage;
        }

        public int getSizeInBytes() {
            return mSizeInBytes;
        }

        public int getMaxSizeInBytes() {
            return mMaxSizeInBytes;
        }

        public void clear() {
            Iterator<Map.Entry<Integer, RenderedPage>> iterator =
                    mRenderedPages.entrySet().iterator();
            while (iterator.hasNext()) {
                iterator.next().getValue().recycle();
                iterator.remove();
            }
        }
    }

    public static final class RenderSpec {
        final int bitmapWidth;
        final int bitmapHeight;
        final MediaSize mediaSize;
        final Margins minMargins;

        public RenderSpec(int bitmapWidth, int bitmapHeight,
                MediaSize mediaSize, Margins minMargins) {
            this.bitmapWidth = bitmapWidth;
            this.bitmapHeight = bitmapHeight;
            this.mediaSize = mediaSize;
            this.minMargins = minMargins;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            RenderSpec other = (RenderSpec) obj;
            if (bitmapHeight != other.bitmapHeight) {
                return false;
            }
            if (bitmapWidth != other.bitmapWidth) {
                return false;
            }
            if (mediaSize != null) {
                if (!mediaSize.equals(other.mediaSize)) {
                    return false;
                }
            } else if (other.mediaSize != null) {
                return false;
            }
            if (minMargins != null) {
                if (!minMargins.equals(other.minMargins)) {
                    return false;
                }
            } else if (other.minMargins != null) {
                return false;
            }
            return true;
        }

        public boolean hasSameSize(RenderedPage page) {
            Bitmap bitmap = page.content.getBitmap();
            return bitmap.getWidth() == bitmapWidth
                    && bitmap.getHeight() == bitmapHeight;
        }

        @Override
        public int hashCode() {
            int result = bitmapWidth;
            result = 31 * result + bitmapHeight;
            result = 31 * result + (mediaSize != null ? mediaSize.hashCode() : 0);
            result = 31 * result + (minMargins != null ? minMargins.hashCode() : 0);
            return result;
        }
    }

    private static final class RenderedPage {
        public static final int STATE_RENDERED = 0;
        public static final int STATE_RENDERING = 1;
        public static final int STATE_SCRAP = 2;

        final BitmapDrawable content;
        RenderSpec renderSpec;

        int state = STATE_SCRAP;

        RenderedPage(BitmapDrawable content) {
            this.content = content;
        }

        public int getSizeInBytes() {
            return content.getBitmap().getByteCount();
        }

        public void recycle() {
            content.getBitmap().recycle();
        }

        public void erase() {
            content.getBitmap().eraseColor(Color.WHITE);
        }
    }

    private static int pointsFromMils(int mils) {
        return (int) (((float) mils / MILS_PER_INCH) * POINTS_IN_INCH);
    }

    private static class AsyncRenderer {
        private final Context mContext;

        private final PageContentLruCache mPageContentCache;

        private final ArrayMap<Integer, RenderPageTask> mPageToRenderTaskMap = new ArrayMap<>();

        private int mPageCount = PrintDocumentInfo.PAGE_COUNT_UNKNOWN;

        // Accessed only by the executor thread.
        private PdfRenderer mRenderer;

        public AsyncRenderer(Context context) {
            mContext = context;

            ActivityManager activityManager = (ActivityManager)
                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
            final int cacheSizeInBytes = activityManager.getMemoryClass() * BYTES_PER_MEGABYTE / 4;
            mPageContentCache = new PageContentLruCache(cacheSizeInBytes);
        }

        public void open(final ParcelFileDescriptor source, final Runnable callback) {
            // Opening a new document invalidates the cache as it has pages
            // from the last document. We keep the cache even when the document
            // is closed to show pages while the other side is writing the new
            // document.
            mPageContentCache.invalidate();

            new AsyncTask<Void, Void, Integer>() {
                @Override
                protected Integer doInBackground(Void... params) {
                    try {
                        mRenderer = new PdfRenderer(source);
                        return mRenderer.getPageCount();
                    } catch (IOException ioe) {
                        throw new IllegalStateException("Cannot open PDF document");
                    }
                }

                @Override
                public void onPostExecute(Integer pageCount) {
                    mPageCount = pageCount;
                    if (callback != null) {
                        callback.run();
                    }
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }

        public void close(final Runnable callback) {
            cancelAllRendering();

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    mRenderer.close();
                    return null;
                }

                @Override
                public void onPostExecute(Void result) {
                    mPageCount = PrintDocumentInfo.PAGE_COUNT_UNKNOWN;
                    if (callback != null) {
                        callback.run();
                    }
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }

        public void destroy() {
            mPageContentCache.invalidate();
            mPageContentCache.clear();
        }

        public void startPreload(int firstShownPage, int lastShownPage, RenderSpec renderSpec) {
            if (DEBUG) {
                Log.i(LOG_TAG, "Preloading pages around [" + firstShownPage
                        + "-" + lastShownPage + "]");
            }

            final int bitmapSizeInBytes = renderSpec.bitmapWidth * renderSpec.bitmapHeight
                    * BYTES_PER_PIXEL;
            final int maxCachedPageCount = mPageContentCache.getMaxSizeInBytes()
                    / bitmapSizeInBytes;
            final int halfPreloadCount = (maxCachedPageCount - (lastShownPage - firstShownPage)) /2;

            final int excessFromStart;
            if (firstShownPage - halfPreloadCount < 0) {
                excessFromStart = halfPreloadCount - firstShownPage;
            } else {
                excessFromStart = 0;
            }

            final int excessFromEnd;
            if (lastShownPage + halfPreloadCount >= mPageCount) {
                excessFromEnd = (lastShownPage + halfPreloadCount) - mPageCount;
            } else {
                excessFromEnd = 0;
            }

            final int fromIndex = Math.max(firstShownPage - halfPreloadCount - excessFromEnd, 0);
            final int toIndex = Math.min(lastShownPage + halfPreloadCount + excessFromStart,
                    mPageCount - 1);

            for (int i = fromIndex; i <= toIndex; i++) {
                renderPage(i, renderSpec, null);
            }
        }

        public void stopPreload() {
            final int taskCount = mPageToRenderTaskMap.size();
            for (int i = 0; i < taskCount; i++) {
                RenderPageTask task = mPageToRenderTaskMap.valueAt(i);
                if (task.isPreload() && !task.isCancelled()) {
                    task.cancel(true);
                }
            }
        }

        public int getPageCount() {
            return mPageCount;
        }

        public void getCachedPage(int pageIndex, RenderSpec renderSpec,
                OnPageContentAvailableCallback callback) {
            RenderedPage renderedPage = mPageContentCache.getRenderedPage(pageIndex);
            if (renderedPage != null && renderedPage.state == RenderedPage.STATE_RENDERED
                    && renderedPage.renderSpec.equals(renderSpec)) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache hit for page: " + pageIndex);
                }

                // Announce if needed.
                if (callback != null) {
                    callback.onPageContentAvailable(renderedPage.content);
                }
            }
        }

        public void renderPage(int pageIndex, RenderSpec renderSpec,
                OnPageContentAvailableCallback callback) {
            // First, check if we have a rendered page for this index.
            RenderedPage renderedPage = mPageContentCache.getRenderedPage(pageIndex);
            if (renderedPage != null && renderedPage.state == RenderedPage.STATE_RENDERED) {
                // If we have rendered page with same constraints - done.
                if (renderedPage.renderSpec.equals(renderSpec)) {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "Cache hit for page: " + pageIndex);
                    }

                    // Announce if needed.
                    if (callback != null) {
                        callback.onPageContentAvailable(renderedPage.content);
                    }
                    return;
                } else {
                    // If the constraints changed, mark the page obsolete.
                    renderedPage.state = RenderedPage.STATE_SCRAP;
                }
            }

            // Next, check if rendering this page is scheduled.
            RenderPageTask renderTask = mPageToRenderTaskMap.get(pageIndex);
            if (renderTask != null && !renderTask.isCancelled()) {
                // If not rendered and constraints same....
                if (renderTask.mRenderSpec.equals(renderSpec)) {
                    if (renderTask.mCallback != null) {
                        // If someone else is already waiting for this page - bad state.
                        if (callback != null && renderTask.mCallback != callback) {
                            throw new IllegalStateException("Page rendering not cancelled");
                        }
                    } else {
                        // No callback means we are preloading so just let the argument
                        // callback be attached to our work in progress.
                        renderTask.mCallback = callback;
                    }
                    return;
                } else {
                    // If not rendered and constraints changed - cancel rendering.
                    renderTask.cancel(true);
                }
            }

            // Oh well, we will have work to do...
            renderTask = new RenderPageTask(pageIndex, renderSpec, callback);
            mPageToRenderTaskMap.put(pageIndex, renderTask);
            renderTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }

        public void cancelRendering(int pageIndex) {
            RenderPageTask task = mPageToRenderTaskMap.get(pageIndex);
            if (task != null && !task.isCancelled()) {
                task.cancel(true);
            }
        }

        private void cancelAllRendering() {
            final int taskCount = mPageToRenderTaskMap.size();
            for (int i = 0; i < taskCount; i++) {
                RenderPageTask task = mPageToRenderTaskMap.valueAt(i);
                if (!task.isCancelled()) {
                    task.cancel(true);
                }
            }
        }

        private final class RenderPageTask extends AsyncTask<Void, Void, RenderedPage> {
            final int mPageIndex;
            final RenderSpec mRenderSpec;
            OnPageContentAvailableCallback mCallback;
            RenderedPage mRenderedPage;

            public RenderPageTask(int pageIndex, RenderSpec renderSpec,
                    OnPageContentAvailableCallback callback) {
                mPageIndex = pageIndex;
                mRenderSpec = renderSpec;
                mCallback = callback;
            }

            @Override
            protected void onPreExecute() {
                mRenderedPage = mPageContentCache.getRenderedPage(mPageIndex);
                if (mRenderedPage != null && mRenderedPage.state == RenderedPage.STATE_RENDERED) {
                    throw new IllegalStateException("Trying to render a rendered page");
                }

                // Reuse bitmap for the page only if the right size.
                if (mRenderedPage != null && !mRenderSpec.hasSameSize(mRenderedPage)) {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "Recycling bitmap for page: " + mPageIndex
                                + " with different size.");
                    }
                    mPageContentCache.removeRenderedPage(mPageIndex);
                    mRenderedPage.recycle();
                    mRenderedPage = null;
                }

                final int bitmapSizeInBytes = mRenderSpec.bitmapWidth
                        * mRenderSpec.bitmapHeight * BYTES_PER_PIXEL;

                // Try to find a bitmap to reuse.
                while (mRenderedPage == null) {

                    // Fill the cache greedily.
                    if (mPageContentCache.getSizeInBytes() <= 0
                            || mPageContentCache.getSizeInBytes() + bitmapSizeInBytes
                            <= mPageContentCache.getMaxSizeInBytes()) {
                        break;
                    }

                    RenderedPage renderedPage = mPageContentCache.removeLeastNeeded();

                    if (!mRenderSpec.hasSameSize(renderedPage)) {
                        if (DEBUG) {
                            Log.i(LOG_TAG, "Recycling bitmap for page: " + mPageIndex
                                   + " with different size.");
                        }
                        renderedPage.recycle();
                        continue;
                    }

                    mRenderedPage = renderedPage;
                    renderedPage.erase();

                    if (DEBUG) {
                        Log.i(LOG_TAG, "Reused bitmap for page: " + mPageIndex + " cache size: "
                                + mPageContentCache.getSizeInBytes() + " bytes");
                    }

                    break;
                }

//                if (mRenderedPage == null) {
//                    final int bitmapSizeInBytes = mRenderSpec.bitmapWidth
//                            * mRenderSpec.bitmapHeight * BYTES_PER_PIXEL;
//
//                    while (mPageContentCache.getSizeInBytes() > 0
//                            && mPageContentCache.getSizeInBytes() + bitmapSizeInBytes
//                            > mPageContentCache.getMaxSizeInBytes()) {
//                        RenderedPage renderedPage = mPageContentCache.removeLeastNeeded();
//
//                        // If not the right size - recycle and keep freeing space.
//                        Bitmap bitmap = renderedPage.content.getBitmap();
//                        if (!mRenderSpec.hasSameSize(bitmap.getWidth(), bitmap.getHeight())) {
//                            if (DEBUG) {
//                                Log.i(LOG_TAG, "Recycling bitmap for page: " + mPageIndex
//                                        + " with different size.");
//                            }
//                            bitmap.recycle();
//                            continue;
//                        }
//
//                        mRenderedPage = renderedPage;
//                        bitmap.eraseColor(Color.WHITE);
//
//                        if (DEBUG) {
//                            Log.i(LOG_TAG, "Reused bitmap for page: " + mPageIndex + " cache size: "
//                                    + mPageContentCache.getSizeInBytes() + " bytes");
//                        }
//                    }
//                }

                if (mRenderedPage == null) {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "Created bitmap for page: " + mPageIndex + " cache size: "
                                + mPageContentCache.getSizeInBytes() + " bytes");
                    }
                    Bitmap bitmap = Bitmap.createBitmap(mRenderSpec.bitmapWidth,
                            mRenderSpec.bitmapHeight, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    BitmapDrawable content = new BitmapDrawable(mContext.getResources(), bitmap);
                    mRenderedPage = new RenderedPage(content);
                }

                mRenderedPage.renderSpec = mRenderSpec;
                mRenderedPage.state = RenderedPage.STATE_RENDERING;

                mPageContentCache.putRenderedPage(mPageIndex, mRenderedPage);
            }

            @Override
            protected RenderedPage doInBackground(Void... params) {
                if (isCancelled()) {
                    return mRenderedPage;
                }

                PdfRenderer.Page page = mRenderer.openPage(mPageIndex);

                if (isCancelled()) {
                    page.close();
                    return mRenderedPage;
                }

                Bitmap bitmap = mRenderedPage.content.getBitmap();

                final int srcWidthPts = page.getWidth();
                final int srcHeightPts = page.getHeight();

                final int dstWidthPts = pointsFromMils(mRenderSpec.mediaSize.getWidthMils());
                final int dstHeightPts = pointsFromMils(mRenderSpec.mediaSize.getHeightMils());

                final boolean scaleContent = mRenderer.shouldScaleForPrinting();
                final boolean contentLandscape = !mRenderSpec.mediaSize.isPortrait();

                final float displayScale;
                Matrix matrix = new Matrix();

                if (scaleContent) {
                    displayScale = Math.min((float) bitmap.getWidth() / srcWidthPts,
                            (float) bitmap.getHeight() / srcHeightPts);
                } else {
                    if (contentLandscape) {
                        displayScale = (float) bitmap.getHeight() / dstHeightPts;
                    } else {
                        displayScale = (float) bitmap.getWidth() / dstWidthPts;
                    }
                }
                matrix.postScale(displayScale, displayScale);

                Configuration configuration = mContext.getResources().getConfiguration();
                if (configuration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                    matrix.postTranslate(bitmap.getWidth() - srcWidthPts * displayScale, 0);
                }

                final int paddingLeftPts = pointsFromMils(mRenderSpec.minMargins.getLeftMils());
                final int paddingTopPts = pointsFromMils(mRenderSpec.minMargins.getTopMils());
                final int paddingRightPts = pointsFromMils(mRenderSpec.minMargins.getRightMils());
                final int paddingBottomPts = pointsFromMils(mRenderSpec.minMargins.getBottomMils());

                Rect clip = new Rect();
                clip.left = (int) (paddingLeftPts * displayScale);
                clip.top = (int) (paddingTopPts * displayScale);
                clip.right = (int) (bitmap.getWidth() - paddingRightPts * displayScale);
                clip.bottom = (int) (bitmap.getHeight() - paddingBottomPts * displayScale);

                if (DEBUG) {
                    Log.i(LOG_TAG, "Rendering page:" + mPageIndex + " of " + mPageCount);
                }

                page.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                page.close();

                return mRenderedPage;
            }

            @Override
            public void onPostExecute(RenderedPage renderedPage) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Completed rendering page: " + mPageIndex);
                }

                // This task is done.
                mPageToRenderTaskMap.remove(mPageIndex);

                // Take a note that the content is rendered.
                renderedPage.state = RenderedPage.STATE_RENDERED;

                // Announce success if needed.
                if (mCallback != null) {
                    mCallback.onPageContentAvailable(renderedPage.content);
                }
            }

            @Override
            protected void onCancelled(RenderedPage renderedPage) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cancelled rendering page: " + mPageIndex);
                }

                // This task is done.
                mPageToRenderTaskMap.remove(mPageIndex);

                // If canceled before on pre-execute.
                if (renderedPage == null) {
                    return;
                }

                // Take a note that the content is not rendered.
                renderedPage.state = RenderedPage.STATE_SCRAP;
            }

            public boolean isPreload() {
                return mCallback == null;
            }
        }
    }
}
