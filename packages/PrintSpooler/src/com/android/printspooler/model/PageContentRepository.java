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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PrintAttributes;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Margins;
import android.print.PrintDocumentInfo;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import com.android.internal.annotations.GuardedBy;
import com.android.printspooler.renderer.IPdfRenderer;
import com.android.printspooler.renderer.PdfManipulationService;
import com.android.printspooler.util.BitmapSerializeUtils;
import dalvik.system.CloseGuard;
import libcore.io.IoUtils;

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

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final AsyncRenderer mRenderer;

    private RenderSpec mLastRenderSpec;

    private int mScheduledPreloadFirstShownPage = INVALID_PAGE_INDEX;
    private int mScheduledPreloadLastShownPage = INVALID_PAGE_INDEX;

    private int mState;

    public interface OnPageContentAvailableCallback {
        void onPageContentAvailable(BitmapDrawable content);
    }

    public PageContentRepository(Context context) {
        mRenderer = new AsyncRenderer(context);
        mState = STATE_CLOSED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_CLOSED");
        }
        mCloseGuard.open("destroy");
    }

    public void open(ParcelFileDescriptor source, final OpenDocumentCallback callback) {
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

    public void destroy(final Runnable callback) {
        if (mState == STATE_OPENED) {
            close(new Runnable() {
                @Override
                public void run() {
                    destroy(callback);
                }
            });
            return;
        }
        mCloseGuard.close();

        mState = STATE_DESTROYED;
        if (DEBUG) {
            Log.i(LOG_TAG, "STATE_DESTROYED");
        }
        mRenderer.destroy();

        if (callback != null) {
            callback.run();
        }
    }

    public void startPreload(int firstShownPage, int lastShownPage) {
        // If we do not have a render spec we have no clue what size the
        // preloaded bitmaps should be, so just take a note for what to do.
        if (mLastRenderSpec == null) {
            mScheduledPreloadFirstShownPage = firstShownPage;
            mScheduledPreloadLastShownPage = lastShownPage;
        } else if (mState == STATE_OPENED) {
            mRenderer.startPreload(firstShownPage, lastShownPage, mLastRenderSpec);
        }
    }

    public void stopPreload() {
        mRenderer.stopPreload();
    }

    public int getFilePageCount() {
        return mRenderer.getPageCount();
    }

    public PageContentProvider acquirePageContentProvider(int pageIndex, View owner) {
        throwIfDestroyed();

        if (DEBUG) {
            Log.i(LOG_TAG, "Acquiring provider for page: " + pageIndex);
        }

        return new PageContentProvider(pageIndex, owner);
    }

    public void releasePageContentProvider(PageContentProvider provider) {
        throwIfDestroyed();

        if (DEBUG) {
            Log.i(LOG_TAG, "Releasing provider for page: " + provider.mPageIndex);
        }

        provider.cancelLoad();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mState != STATE_DESTROYED) {
                mCloseGuard.warnIfOpen();
                destroy(null);
            }
        } finally {
            super.finalize();
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
                iterator.next();
                iterator.remove();
            }
        }
    }

    public static final class RenderSpec {
        final int bitmapWidth;
        final int bitmapHeight;
        final PrintAttributes printAttributes = new PrintAttributes.Builder().build();

        public RenderSpec(int bitmapWidth, int bitmapHeight,
                MediaSize mediaSize, Margins minMargins) {
            this.bitmapWidth = bitmapWidth;
            this.bitmapHeight = bitmapHeight;
            printAttributes.setMediaSize(mediaSize);
            printAttributes.setMinMargins(minMargins);
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
            if (printAttributes != null) {
                if (!printAttributes.equals(other.printAttributes)) {
                    return false;
                }
            } else if (other.printAttributes != null) {
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
            result = 31 * result + (printAttributes != null ? printAttributes.hashCode() : 0);
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

        public void erase() {
            content.getBitmap().eraseColor(Color.WHITE);
        }
    }

    private static final class AsyncRenderer implements ServiceConnection {
        private final Object mLock = new Object();

        private final Context mContext;

        private final PageContentLruCache mPageContentCache;

        private final ArrayMap<Integer, RenderPageTask> mPageToRenderTaskMap = new ArrayMap<>();

        private int mPageCount = PrintDocumentInfo.PAGE_COUNT_UNKNOWN;

        @GuardedBy("mLock")
        private IPdfRenderer mRenderer;

        private OpenTask mOpenTask;

        private boolean mBoundToService;
        private boolean mDestroyed;

        public AsyncRenderer(Context context) {
            mContext = context;

            ActivityManager activityManager = (ActivityManager)
                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
            final int cacheSizeInBytes = activityManager.getMemoryClass() * BYTES_PER_MEGABYTE / 4;
            mPageContentCache = new PageContentLruCache(cacheSizeInBytes);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mRenderer = IPdfRenderer.Stub.asInterface(service);
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mRenderer = null;
            }
        }

        public void open(ParcelFileDescriptor source, OpenDocumentCallback callback) {
            // Opening a new document invalidates the cache as it has pages
            // from the last document. We keep the cache even when the document
            // is closed to show pages while the other side is writing the new
            // document.
            mPageContentCache.invalidate();

            mOpenTask = new OpenTask(source, callback);
            mOpenTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
        }

        public void close(final Runnable callback) {
            cancelAllRendering();

            if (mOpenTask != null) {
                mOpenTask.cancel();
            }

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected void onPreExecute() {
                    if (mDestroyed) {
                        cancel(true);
                        return;
                    }
                }

                @Override
                protected Void doInBackground(Void... params) {
                    synchronized (mLock) {
                        try {
                            if (mRenderer != null) {
                                mRenderer.closeDocument();
                            }
                        } catch (RemoteException re) {
                            /* ignore */
                        }
                    }
                    return null;
                }

                @Override
                public void onPostExecute(Void result) {
                    mPageCount = PrintDocumentInfo.PAGE_COUNT_UNKNOWN;
                    if (callback != null) {
                        callback.run();
                    }
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
        }

        public void destroy() {
            if (mBoundToService) {
                mBoundToService = false;
                try {
                    mContext.unbindService(AsyncRenderer.this);
                } catch (IllegalArgumentException e) {
                    // Service might have been forcefully unbound in onDestroy()
                    Log.e(LOG_TAG, "Cannot unbind service", e);
                }
            }

            mPageContentCache.invalidate();
            mPageContentCache.clear();
            mDestroyed = true;
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
            final int halfPreloadCount = (maxCachedPageCount
                    - (lastShownPage - firstShownPage)) / 2 - 1;

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
            renderTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
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

        private final class OpenTask extends AsyncTask<Void, Void, Integer> {
            private final ParcelFileDescriptor mSource;
            private final OpenDocumentCallback mCallback;

            public OpenTask(ParcelFileDescriptor source, OpenDocumentCallback callback) {
                mSource = source;
                mCallback = callback;
            }

            @Override
            protected void onPreExecute() {
                if (mDestroyed) {
                    cancel(true);
                    return;
                }
                Intent intent = new Intent(PdfManipulationService.ACTION_GET_RENDERER);
                intent.setClass(mContext, PdfManipulationService.class);
                intent.setData(Uri.fromParts("fake-scheme", String.valueOf(
                        AsyncRenderer.this.hashCode()), null));
                mContext.bindService(intent, AsyncRenderer.this, Context.BIND_AUTO_CREATE);
                mBoundToService = true;
            }

            @Override
            protected Integer doInBackground(Void... params) {
                synchronized (mLock) {
                    while (mRenderer == null && !isCancelled()) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException ie) {
                                /* ignore */
                        }
                    }
                    try {
                        return mRenderer.openDocument(mSource);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Cannot open PDF document");
                        return PdfManipulationService.ERROR_MALFORMED_PDF_FILE;
                    } finally {
                        // Close the fd as we passed it to another process
                        // which took ownership.
                        IoUtils.closeQuietly(mSource);
                    }
                }
            }

            @Override
            public void onPostExecute(Integer pageCount) {
                switch (pageCount) {
                    case PdfManipulationService.ERROR_MALFORMED_PDF_FILE: {
                        mPageCount = PrintDocumentInfo.PAGE_COUNT_UNKNOWN;
                        if (mCallback != null) {
                            mCallback.onFailure(OpenDocumentCallback.ERROR_MALFORMED_PDF_FILE);
                        }
                    } break;
                    case PdfManipulationService.ERROR_SECURE_PDF_FILE: {
                        mPageCount = PrintDocumentInfo.PAGE_COUNT_UNKNOWN;
                        if (mCallback != null) {
                            mCallback.onFailure(OpenDocumentCallback.ERROR_SECURE_PDF_FILE);
                        }
                    } break;
                    default: {
                        mPageCount = pageCount;
                        if (mCallback != null) {
                            mCallback.onSuccess();
                        }
                    } break;
                }

                mOpenTask = null;
            }

            @Override
            protected void onCancelled(Integer integer) {
                mOpenTask = null;
            }

            public void cancel() {
                cancel(true);
                synchronized(mLock) {
                    mLock.notifyAll();
                }
            }
        }

        private final class RenderPageTask extends AsyncTask<Void, Void, RenderedPage> {
            final int mPageIndex;
            final RenderSpec mRenderSpec;
            OnPageContentAvailableCallback mCallback;
            RenderedPage mRenderedPage;
            private boolean mIsFailed;

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

                Bitmap bitmap = mRenderedPage.content.getBitmap();

                ParcelFileDescriptor[] pipe;
                try {
                    pipe = ParcelFileDescriptor.createPipe();

                    try (ParcelFileDescriptor source = pipe[0]) {
                        try (ParcelFileDescriptor destination = pipe[1]) {

                            mRenderer.renderPage(mPageIndex, bitmap.getWidth(), bitmap.getHeight(),
                                    mRenderSpec.printAttributes, destination);
                        }

                        BitmapSerializeUtils.readBitmapPixels(bitmap, source);
                    }

                    mIsFailed = false;
                } catch (IOException|RemoteException|IllegalStateException e) {
                    Log.e(LOG_TAG, "Error rendering page " + mPageIndex, e);
                    mIsFailed = true;
                }

                return mRenderedPage;
            }

            @Override
            public void onPostExecute(RenderedPage renderedPage) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Completed rendering page: " + mPageIndex);
                }

                // This task is done.
                mPageToRenderTaskMap.remove(mPageIndex);

                if (mIsFailed) {
                    renderedPage.state = RenderedPage.STATE_SCRAP;
                } else {
                    renderedPage.state = RenderedPage.STATE_RENDERED;
                }

                // Invalidate all caches of the old state of the bitmap
                mRenderedPage.content.invalidateSelf();

                // Announce success if needed.
                if (mCallback != null) {
                    if (mIsFailed) {
                        mCallback.onPageContentAvailable(null);
                    } else {
                        mCallback.onPageContentAvailable(renderedPage.content);
                    }
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
