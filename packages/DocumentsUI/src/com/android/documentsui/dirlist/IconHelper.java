/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.State.MODE_GRID;
import static com.android.documentsui.State.MODE_LIST;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.ImageView;

import com.android.documentsui.DocumentsApplication;
import com.android.documentsui.IconUtils;
import com.android.documentsui.MimePredicate;
import com.android.documentsui.ProviderExecutor;
import com.android.documentsui.ProviderExecutor.Preemptable;
import com.android.documentsui.R;
import com.android.documentsui.State;
import com.android.documentsui.State.ViewMode;
import com.android.documentsui.ThumbnailCache;

/**
 * A class to assist with loading and managing the Images (i.e. thumbnails and icons) associated
 * with items in the directory listing.
 */
public class IconHelper {
    private static String TAG = "IconHelper";

    private final Context mContext;

    // Updated when icon size is set.
    private ThumbnailCache mCache;
    private Point mThumbSize;
    // The display mode (MODE_GRID, MODE_LIST, etc).
    private int mMode;
    private boolean mThumbnailsEnabled = true;

    /**
     * @param context
     * @param mode MODE_GRID or MODE_LIST
     */
    public IconHelper(Context context, int mode) {
        mContext = context;
        setViewMode(mode);
        mCache = DocumentsApplication.getThumbnailsCache(context, mThumbSize);
    }

    /**
     * Enables or disables thumbnails. When thumbnails are disabled, mime icons (or custom icons, if
     * specified by the document) are used instead.
     *
     * @param enabled
     */
    public void setThumbnailsEnabled(boolean enabled) {
        mThumbnailsEnabled = enabled;
    }

    /**
     * Sets the current display mode.  This affects the thumbnail sizes that are loaded.
     * @param mode See {@link State.MODE_LIST} and {@link State.MODE_GRID}.
     */
    public void setViewMode(@ViewMode int mode) {
        mMode = mode;
        int thumbSize = getThumbSize(mode);
        mThumbSize = new Point(thumbSize, thumbSize);
        mCache = DocumentsApplication.getThumbnailsCache(mContext, mThumbSize);
    }

    private int getThumbSize(int mode) {
        int thumbSize;
        switch (mode) {
            case MODE_GRID:
                thumbSize = mContext.getResources().getDimensionPixelSize(R.dimen.grid_width);
                break;
            case MODE_LIST:
                thumbSize = mContext.getResources().getDimensionPixelSize(
                        R.dimen.list_item_thumbnail_size);
                break;
            default:
                throw new IllegalArgumentException("Unsupported layout mode: " + mode);
        }
        return thumbSize;
    }

    /**
     * Cancels any ongoing load operations associated with the given ImageView.
     * @param icon
     */
    public void stopLoading(ImageView icon) {
        final LoaderTask oldTask = (LoaderTask) icon.getTag();
        if (oldTask != null) {
            oldTask.preempt();
            icon.setTag(null);
        }
    }

    /** Internal task for loading thumbnails asynchronously. */
    private static class LoaderTask
            extends AsyncTask<Uri, Void, Bitmap>
            implements Preemptable {
        private final Uri mUri;
        private final ImageView mIconMime;
        private final ImageView mIconThumb;
        private final Point mThumbSize;
        private final CancellationSignal mSignal;

        public LoaderTask(Uri uri, ImageView iconMime, ImageView iconThumb,
                Point thumbSize) {
            mUri = uri;
            mIconMime = iconMime;
            mIconThumb = iconThumb;
            mThumbSize = thumbSize;
            mSignal = new CancellationSignal();
            if (DEBUG) Log.d(TAG, "Starting icon loader task for " + mUri);
        }

        @Override
        public void preempt() {
            if (DEBUG) Log.d(TAG, "Icon loader task for " + mUri + " was cancelled.");
            cancel(false);
            mSignal.cancel();
        }

        @Override
        protected Bitmap doInBackground(Uri... params) {
            if (isCancelled())
                return null;

            final Context context = mIconThumb.getContext();
            final ContentResolver resolver = context.getContentResolver();

            ContentProviderClient client = null;
            Bitmap result = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(
                        resolver, mUri.getAuthority());
                result = DocumentsContract.getDocumentThumbnail(client, mUri, mThumbSize, mSignal);
                if (result != null) {
                    final ThumbnailCache thumbs = DocumentsApplication.getThumbnailsCache(
                            context, mThumbSize);
                    thumbs.put(mUri, result);
                }
            } catch (Exception e) {
                if (!(e instanceof OperationCanceledException)) {
                    Log.w(TAG, "Failed to load thumbnail for " + mUri + ": " + e);
                }
            } finally {
                ContentProviderClient.releaseQuietly(client);
            }
            return result;
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            if (DEBUG) Log.d(TAG, "Loader task for " + mUri + " completed");

            if (mIconThumb.getTag() == this && result != null) {
                mIconThumb.setTag(null);
                mIconThumb.setImageBitmap(result);

                float alpha = mIconMime.getAlpha();
                mIconMime.animate().alpha(0f).start();
                mIconThumb.setAlpha(0f);
                mIconThumb.animate().alpha(alpha).start();
            }
        }
    }

    /**
     * Load thumbnails for a directory list item.
     * @param uri The URI for the file being represented.
     * @param mimeType The mime type of the file being represented.
     * @param docFlags Flags for the file being represented.
     * @param docIcon Custom icon (if any) for the file being requested.
     * @param iconThumb The itemview's thumbnail icon.
     * @param iconMime The itemview's mime icon. Hidden when iconThumb is shown.
     * @param subIconMime The second itemview's mime icon. Always visible.
     * @return
     */
    public void loadThumbnail(Uri uri, String mimeType, int docFlags, int docIcon,
            ImageView iconThumb, ImageView iconMime, @Nullable ImageView subIconMime) {
        boolean cacheHit = false;

        final String docAuthority = uri.getAuthority();

        final boolean supportsThumbnail = (docFlags & Document.FLAG_SUPPORTS_THUMBNAIL) != 0;
        final boolean allowThumbnail = (mMode == MODE_GRID)
                || MimePredicate.mimeMatches(MimePredicate.VISUAL_MIMES, mimeType);
        final boolean showThumbnail = supportsThumbnail && allowThumbnail && mThumbnailsEnabled;
        if (showThumbnail) {
            final Bitmap cachedResult = mCache.get(uri);
            if (cachedResult != null) {
                iconThumb.setImageBitmap(cachedResult);
                cacheHit = true;
            } else {
                iconThumb.setImageDrawable(null);
                final LoaderTask task = new LoaderTask(uri, iconMime, iconThumb, mThumbSize);
                iconThumb.setTag(task);
                ProviderExecutor.forAuthority(docAuthority).execute(task);
            }
        }

        final Drawable icon = getDocumentIcon(mContext, docAuthority,
                DocumentsContract.getDocumentId(uri), mimeType, docIcon);
        if (subIconMime != null) {
            subIconMime.setImageDrawable(icon);
        }

        if (cacheHit) {
            iconMime.setImageDrawable(null);
            iconMime.setAlpha(0f);
            iconThumb.setAlpha(1f);
        } else {
            // Add a mime icon if the thumbnail is being loaded in the background.
            iconThumb.setImageDrawable(null);
            iconMime.setImageDrawable(icon);
            iconMime.setAlpha(1f);
            iconThumb.setAlpha(0f);
        }
    }

    /**
     * Gets a mime icon or package icon for a file.
     * @param context
     * @param authority The authority string of the file.
     * @param id The document ID of the file.
     * @param mimeType The mime type of the file.
     * @param icon The custom icon (if any) of the file.
     * @return
     */
    public Drawable getDocumentIcon(Context context, String authority, String id,
            String mimeType, int icon) {
        if (icon != 0) {
            return IconUtils.loadPackageIcon(context, authority, icon);
        } else {
            return IconUtils.loadMimeIcon(context, mimeType, authority, id, mMode);
        }
    }

}
