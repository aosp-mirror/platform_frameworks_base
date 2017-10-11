/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.Manifest;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.RemoteViews.OnClickHandler;

import com.android.internal.widget.IRemoteViewsAdapterConnection;
import com.android.internal.widget.IRemoteViewsFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Executor;

/**
 * An adapter to a RemoteViewsService which fetches and caches RemoteViews
 * to be later inflated as child views.
 */
/** @hide */
public class RemoteViewsAdapter extends BaseAdapter implements Handler.Callback {
    private static final String MULTI_USER_PERM = Manifest.permission.INTERACT_ACROSS_USERS_FULL;

    private static final String TAG = "RemoteViewsAdapter";

    // The max number of items in the cache
    private static final int sDefaultCacheSize = 40;
    // The delay (in millis) to wait until attempting to unbind from a service after a request.
    // This ensures that we don't stay continually bound to the service and that it can be destroyed
    // if we need the memory elsewhere in the system.
    private static final int sUnbindServiceDelay = 5000;

    // Default height for the default loading view, in case we cannot get inflate the first view
    private static final int sDefaultLoadingViewHeight = 50;

    // Type defs for controlling different messages across the main and worker message queues
    private static final int sDefaultMessageType = 0;
    private static final int sUnbindServiceMessageType = 1;

    private final Context mContext;
    private final Intent mIntent;
    private final int mAppWidgetId;
    private final Executor mAsyncViewLoadExecutor;

    private RemoteViewsAdapterServiceConnection mServiceConnection;
    private WeakReference<RemoteAdapterConnectionCallback> mCallback;
    private OnClickHandler mRemoteViewsOnClickHandler;
    private final FixedSizeRemoteViewsCache mCache;
    private int mVisibleWindowLowerBound;
    private int mVisibleWindowUpperBound;

    // A flag to determine whether we should notify data set changed after we connect
    private boolean mNotifyDataSetChangedAfterOnServiceConnected = false;

    // The set of requested views that are to be notified when the associated RemoteViews are
    // loaded.
    private RemoteViewsFrameLayoutRefSet mRequestedViews;

    private HandlerThread mWorkerThread;
    // items may be interrupted within the normally processed queues
    private Handler mWorkerQueue;
    private Handler mMainQueue;

    // We cache the FixedSizeRemoteViewsCaches across orientation. These are the related data
    // structures;
    private static final HashMap<RemoteViewsCacheKey, FixedSizeRemoteViewsCache>
            sCachedRemoteViewsCaches = new HashMap<>();
    private static final HashMap<RemoteViewsCacheKey, Runnable>
            sRemoteViewsCacheRemoveRunnables = new HashMap<>();

    private static HandlerThread sCacheRemovalThread;
    private static Handler sCacheRemovalQueue;

    // We keep the cache around for a duration after onSaveInstanceState for use on re-inflation.
    // If a new RemoteViewsAdapter with the same intent / widget id isn't constructed within this
    // duration, the cache is dropped.
    private static final int REMOTE_VIEWS_CACHE_DURATION = 5000;

    // Used to indicate to the AdapterView that it can use this Adapter immediately after
    // construction (happens when we have a cached FixedSizeRemoteViewsCache).
    private boolean mDataReady = false;

    /**
     * An interface for the RemoteAdapter to notify other classes when adapters
     * are actually connected to/disconnected from their actual services.
     */
    public interface RemoteAdapterConnectionCallback {
        /**
         * @return whether the adapter was set or not.
         */
        boolean onRemoteAdapterConnected();

        void onRemoteAdapterDisconnected();

        /**
         * This defers a notifyDataSetChanged on the pending RemoteViewsAdapter if it has not
         * connected yet.
         */
        void deferNotifyDataSetChanged();

        void setRemoteViewsAdapter(Intent intent, boolean isAsync);
    }

    public static class AsyncRemoteAdapterAction implements Runnable {

        private final RemoteAdapterConnectionCallback mCallback;
        private final Intent mIntent;

        public AsyncRemoteAdapterAction(RemoteAdapterConnectionCallback callback, Intent intent) {
            mCallback = callback;
            mIntent = intent;
        }

        @Override
        public void run() {
            mCallback.setRemoteViewsAdapter(mIntent, true);
        }
    }

    /**
     * The service connection that gets populated when the RemoteViewsService is
     * bound.  This must be a static inner class to ensure that no references to the outer
     * RemoteViewsAdapter instance is retained (this would prevent the RemoteViewsAdapter from being
     * garbage collected, and would cause us to leak activities due to the caching mechanism for
     * FrameLayouts in the adapter).
     */
    private static class RemoteViewsAdapterServiceConnection extends
            IRemoteViewsAdapterConnection.Stub {
        private boolean mIsConnected;
        private boolean mIsConnecting;
        private WeakReference<RemoteViewsAdapter> mAdapter;
        private IRemoteViewsFactory mRemoteViewsFactory;

        public RemoteViewsAdapterServiceConnection(RemoteViewsAdapter adapter) {
            mAdapter = new WeakReference<RemoteViewsAdapter>(adapter);
        }

        public synchronized void bind(Context context, int appWidgetId, Intent intent) {
            if (!mIsConnecting) {
                try {
                    RemoteViewsAdapter adapter;
                    final AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                    if ((adapter = mAdapter.get()) != null) {
                        mgr.bindRemoteViewsService(context.getOpPackageName(), appWidgetId,
                                intent, asBinder());
                    } else {
                        Slog.w(TAG, "bind: adapter was null");
                    }
                    mIsConnecting = true;
                } catch (Exception e) {
                    Log.e("RVAServiceConnection", "bind(): " + e.getMessage());
                    mIsConnecting = false;
                    mIsConnected = false;
                }
            }
        }

        public synchronized void unbind(Context context, int appWidgetId, Intent intent) {
            try {
                RemoteViewsAdapter adapter;
                final AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                if ((adapter = mAdapter.get()) != null) {
                    mgr.unbindRemoteViewsService(context.getOpPackageName(), appWidgetId, intent);
                } else {
                    Slog.w(TAG, "unbind: adapter was null");
                }
                mIsConnecting = false;
            } catch (Exception e) {
                Log.e("RVAServiceConnection", "unbind(): " + e.getMessage());
                mIsConnecting = false;
                mIsConnected = false;
            }
        }

        public synchronized void onServiceConnected(IBinder service) {
            mRemoteViewsFactory = IRemoteViewsFactory.Stub.asInterface(service);

            // Remove any deferred unbind messages
            final RemoteViewsAdapter adapter = mAdapter.get();
            if (adapter == null) return;

            // Queue up work that we need to do for the callback to run
            adapter.mWorkerQueue.post(new Runnable() {
                @Override
                public void run() {
                    if (adapter.mNotifyDataSetChangedAfterOnServiceConnected) {
                        // Handle queued notifyDataSetChanged() if necessary
                        adapter.onNotifyDataSetChanged();
                    } else {
                        IRemoteViewsFactory factory =
                            adapter.mServiceConnection.getRemoteViewsFactory();
                        try {
                            if (!factory.isCreated()) {
                                // We only call onDataSetChanged() if this is the factory was just
                                // create in response to this bind
                                factory.onDataSetChanged();
                            }
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error notifying factory of data set changed in " +
                                        "onServiceConnected(): " + e.getMessage());

                            // Return early to prevent anything further from being notified
                            // (effectively nothing has changed)
                            return;
                        } catch (RuntimeException e) {
                            Log.e(TAG, "Error notifying factory of data set changed in " +
                                    "onServiceConnected(): " + e.getMessage());
                        }

                        // Request meta data so that we have up to date data when calling back to
                        // the remote adapter callback
                        adapter.updateTemporaryMetaData();

                        // Notify the host that we've connected
                        adapter.mMainQueue.post(new Runnable() {
                            @Override
                            public void run() {
                                synchronized (adapter.mCache) {
                                    adapter.mCache.commitTemporaryMetaData();
                                }

                                final RemoteAdapterConnectionCallback callback =
                                    adapter.mCallback.get();
                                if (callback != null) {
                                    callback.onRemoteAdapterConnected();
                                }
                            }
                        });
                    }

                    // Enqueue unbind message
                    adapter.enqueueDeferredUnbindServiceMessage();
                    mIsConnected = true;
                    mIsConnecting = false;
                }
            });
        }

        public synchronized void onServiceDisconnected() {
            mIsConnected = false;
            mIsConnecting = false;
            mRemoteViewsFactory = null;

            // Clear the main/worker queues
            final RemoteViewsAdapter adapter = mAdapter.get();
            if (adapter == null) return;

            adapter.mMainQueue.post(new Runnable() {
                @Override
                public void run() {
                    // Dequeue any unbind messages
                    adapter.mMainQueue.removeMessages(sUnbindServiceMessageType);

                    final RemoteAdapterConnectionCallback callback = adapter.mCallback.get();
                    if (callback != null) {
                        callback.onRemoteAdapterDisconnected();
                    }
                }
            });
        }

        public synchronized IRemoteViewsFactory getRemoteViewsFactory() {
            return mRemoteViewsFactory;
        }

        public synchronized boolean isConnected() {
            return mIsConnected;
        }
    }

    /**
     * A FrameLayout which contains a loading view, and manages the re/applying of RemoteViews when
     * they are loaded.
     */
    static class RemoteViewsFrameLayout extends AppWidgetHostView {
        private final FixedSizeRemoteViewsCache mCache;

        public RemoteViewsFrameLayout(Context context, FixedSizeRemoteViewsCache cache) {
            super(context);
            mCache = cache;
        }

        /**
         * Updates this RemoteViewsFrameLayout depending on the view that was loaded.
         * @param view the RemoteViews that was loaded. If null, the RemoteViews was not loaded
         *             successfully.
         * @param forceApplyAsync when true, the host will always try to inflate the view
         *                        asynchronously (for eg, when we are already showing the loading
         *                        view)
         */
        public void onRemoteViewsLoaded(RemoteViews view, OnClickHandler handler,
                boolean forceApplyAsync) {
            setOnClickHandler(handler);
            applyRemoteViews(view, forceApplyAsync || ((view != null) && view.prefersAsyncApply()));
        }

        /**
         * Creates a default loading view. Uses the size of the first row as a guide for the
         * size of the loading view.
         */
        @Override
        protected View getDefaultView() {
            int viewHeight = mCache.getMetaData().getLoadingTemplate(getContext()).defaultHeight;
            // Compose the loading view text
            TextView loadingTextView = (TextView) LayoutInflater.from(getContext()).inflate(
                    com.android.internal.R.layout.remote_views_adapter_default_loading_view,
                    this, false);
            loadingTextView.setHeight(viewHeight);
            return loadingTextView;
        }

        @Override
        protected Context getRemoteContext() {
            return null;
        }

        @Override
        protected View getErrorView() {
            // Use the default loading view as the error view.
            return getDefaultView();
        }
    }

    /**
     * Stores the references of all the RemoteViewsFrameLayouts that have been returned by the
     * adapter that have not yet had their RemoteViews loaded.
     */
    private class RemoteViewsFrameLayoutRefSet {
        private final SparseArray<LinkedList<RemoteViewsFrameLayout>> mReferences =
                new SparseArray<>();
        private final HashMap<RemoteViewsFrameLayout, LinkedList<RemoteViewsFrameLayout>>
                mViewToLinkedList = new HashMap<>();

        /**
         * Adds a new reference to a RemoteViewsFrameLayout returned by the adapter.
         */
        public void add(int position, RemoteViewsFrameLayout layout) {
            LinkedList<RemoteViewsFrameLayout> refs = mReferences.get(position);

            // Create the list if necessary
            if (refs == null) {
                refs = new LinkedList<RemoteViewsFrameLayout>();
                mReferences.put(position, refs);
            }
            mViewToLinkedList.put(layout, refs);

            // Add the references to the list
            refs.add(layout);
        }

        /**
         * Notifies each of the RemoteViewsFrameLayouts associated with a particular position that
         * the associated RemoteViews has loaded.
         */
        public void notifyOnRemoteViewsLoaded(int position, RemoteViews view) {
            if (view == null) return;

            final LinkedList<RemoteViewsFrameLayout> refs = mReferences.get(position);
            if (refs != null) {
                // Notify all the references for that position of the newly loaded RemoteViews
                for (final RemoteViewsFrameLayout ref : refs) {
                    ref.onRemoteViewsLoaded(view, mRemoteViewsOnClickHandler, true);
                    if (mViewToLinkedList.containsKey(ref)) {
                        mViewToLinkedList.remove(ref);
                    }
                }
                refs.clear();
                // Remove this set from the original mapping
                mReferences.remove(position);
            }
        }

        /**
         * We need to remove views from this set if they have been recycled by the AdapterView.
         */
        public void removeView(RemoteViewsFrameLayout rvfl) {
            if (mViewToLinkedList.containsKey(rvfl)) {
                mViewToLinkedList.get(rvfl).remove(rvfl);
                mViewToLinkedList.remove(rvfl);
            }
        }

        /**
         * Removes all references to all RemoteViewsFrameLayouts returned by the adapter.
         */
        public void clear() {
            // We currently just clear the references, and leave all the previous layouts returned
            // in their default state of the loading view.
            mReferences.clear();
            mViewToLinkedList.clear();
        }
    }

    /**
     * The meta-data associated with the cache in it's current state.
     */
    private static class RemoteViewsMetaData {
        int count;
        int viewTypeCount;
        boolean hasStableIds;

        // Used to determine how to construct loading views.  If a loading view is not specified
        // by the user, then we try and load the first view, and use its height as the height for
        // the default loading view.
        LoadingViewTemplate loadingTemplate;

        // A mapping from type id to a set of unique type ids
        private final SparseIntArray mTypeIdIndexMap = new SparseIntArray();

        public RemoteViewsMetaData() {
            reset();
        }

        public void set(RemoteViewsMetaData d) {
            synchronized (d) {
                count = d.count;
                viewTypeCount = d.viewTypeCount;
                hasStableIds = d.hasStableIds;
                loadingTemplate = d.loadingTemplate;
            }
        }

        public void reset() {
            count = 0;

            // by default there is at least one dummy view type
            viewTypeCount = 1;
            hasStableIds = true;
            loadingTemplate = null;
            mTypeIdIndexMap.clear();
        }

        public int getMappedViewType(int typeId) {
            int mappedTypeId = mTypeIdIndexMap.get(typeId, -1);
            if (mappedTypeId == -1) {
                // We +1 because the loading view always has view type id of 0
                mappedTypeId = mTypeIdIndexMap.size() + 1;
                mTypeIdIndexMap.put(typeId, mappedTypeId);
            }
            return mappedTypeId;
        }

        public boolean isViewTypeInRange(int typeId) {
            int mappedType = getMappedViewType(typeId);
            return (mappedType < viewTypeCount);
        }

        public synchronized LoadingViewTemplate getLoadingTemplate(Context context) {
            if (loadingTemplate == null) {
                loadingTemplate = new LoadingViewTemplate(null, context);
            }
            return loadingTemplate;
        }
    }

    /**
     * The meta-data associated with a single item in the cache.
     */
    private static class RemoteViewsIndexMetaData {
        int typeId;
        long itemId;

        public RemoteViewsIndexMetaData(RemoteViews v, long itemId) {
            set(v, itemId);
        }

        public void set(RemoteViews v, long id) {
            itemId = id;
            if (v != null) {
                typeId = v.getLayoutId();
            } else {
                typeId = 0;
            }
        }
    }

    /**
     *
     */
    private static class FixedSizeRemoteViewsCache {
        private static final String TAG = "FixedSizeRemoteViewsCache";

        // The meta data related to all the RemoteViews, ie. count, is stable, etc.
        // The meta data objects are made final so that they can be locked on independently
        // of the FixedSizeRemoteViewsCache. If we ever lock on both meta data objects, it is in
        // the order mTemporaryMetaData followed by mMetaData.
        private final RemoteViewsMetaData mMetaData = new RemoteViewsMetaData();
        private final RemoteViewsMetaData mTemporaryMetaData = new RemoteViewsMetaData();

        // The cache/mapping of position to RemoteViewsMetaData.  This set is guaranteed to be
        // greater than or equal to the set of RemoteViews.
        // Note: The reason that we keep this separate from the RemoteViews cache below is that this
        // we still need to be able to access the mapping of position to meta data, without keeping
        // the heavy RemoteViews around.  The RemoteViews cache is trimmed to fixed constraints wrt.
        // memory and size, but this metadata cache will retain information until the data at the
        // position is guaranteed as not being necessary any more (usually on notifyDataSetChanged).
        private final SparseArray<RemoteViewsIndexMetaData> mIndexMetaData = new SparseArray<>();

        // The cache of actual RemoteViews, which may be pruned if the cache gets too large, or uses
        // too much memory.
        private final SparseArray<RemoteViews> mIndexRemoteViews = new SparseArray<>();

        // An array of indices to load, Indices which are explicitely requested are set to true,
        // and those determined by the preloading algorithm to prefetch are set to false.
        private final SparseBooleanArray mIndicesToLoad = new SparseBooleanArray();

        // We keep a reference of the last requested index to determine which item to prune the
        // farthest items from when we hit the memory limit
        private int mLastRequestedIndex;


        // The lower and upper bounds of the preloaded range
        private int mPreloadLowerBound;
        private int mPreloadUpperBound;

        // The bounds of this fixed cache, we will try and fill as many items into the cache up to
        // the maxCount number of items, or the maxSize memory usage.
        // The maxCountSlack is used to determine if a new position in the cache to be loaded is
        // sufficiently ouside the old set, prompting a shifting of the "window" of items to be
        // preloaded.
        private final int mMaxCount;
        private final int mMaxCountSlack;
        private static final float sMaxCountSlackPercent = 0.75f;
        private static final int sMaxMemoryLimitInBytes = 2 * 1024 * 1024;

        public FixedSizeRemoteViewsCache(int maxCacheSize) {
            mMaxCount = maxCacheSize;
            mMaxCountSlack = Math.round(sMaxCountSlackPercent * (mMaxCount / 2));
            mPreloadLowerBound = 0;
            mPreloadUpperBound = -1;
            mLastRequestedIndex = -1;
        }

        public void insert(int position, RemoteViews v, long itemId, int[] visibleWindow) {
            // Trim the cache if we go beyond the count
            if (mIndexRemoteViews.size() >= mMaxCount) {
                mIndexRemoteViews.remove(getFarthestPositionFrom(position, visibleWindow));
            }

            // Trim the cache if we go beyond the available memory size constraints
            int pruneFromPosition = (mLastRequestedIndex > -1) ? mLastRequestedIndex : position;
            while (getRemoteViewsBitmapMemoryUsage() >= sMaxMemoryLimitInBytes) {
                // Note: This is currently the most naive mechanism for deciding what to prune when
                // we hit the memory limit.  In the future, we may want to calculate which index to
                // remove based on both its position as well as it's current memory usage, as well
                // as whether it was directly requested vs. whether it was preloaded by our caching
                // mechanism.
                int trimIndex = getFarthestPositionFrom(pruneFromPosition, visibleWindow);

                // Need to check that this is a valid index, to cover the case where you have only
                // a single view in the cache, but it's larger than the max memory limit
                if (trimIndex < 0) {
                    break;
                }

                mIndexRemoteViews.remove(trimIndex);
            }

            // Update the metadata cache
            final RemoteViewsIndexMetaData metaData = mIndexMetaData.get(position);
            if (metaData != null) {
                metaData.set(v, itemId);
            } else {
                mIndexMetaData.put(position, new RemoteViewsIndexMetaData(v, itemId));
            }
            mIndexRemoteViews.put(position, v);
        }

        public RemoteViewsMetaData getMetaData() {
            return mMetaData;
        }
        public RemoteViewsMetaData getTemporaryMetaData() {
            return mTemporaryMetaData;
        }
        public RemoteViews getRemoteViewsAt(int position) {
            return mIndexRemoteViews.get(position);
        }
        public RemoteViewsIndexMetaData getMetaDataAt(int position) {
            return mIndexMetaData.get(position);
        }

        public void commitTemporaryMetaData() {
            synchronized (mTemporaryMetaData) {
                synchronized (mMetaData) {
                    mMetaData.set(mTemporaryMetaData);
                }
            }
        }

        private int getRemoteViewsBitmapMemoryUsage() {
            // Calculate the memory usage of all the RemoteViews bitmaps being cached
            int mem = 0;
            for (int i = mIndexRemoteViews.size() - 1; i >= 0; i--) {
                final RemoteViews v = mIndexRemoteViews.valueAt(i);
                if (v != null) {
                    mem += v.estimateMemoryUsage();
                }
            }
            return mem;
        }

        private int getFarthestPositionFrom(int pos, int[] visibleWindow) {
            // Find the index farthest away and remove that
            int maxDist = 0;
            int maxDistIndex = -1;
            int maxDistNotVisible = 0;
            int maxDistIndexNotVisible = -1;
            for (int i = mIndexRemoteViews.size() - 1; i >= 0; i--) {
                int index = mIndexRemoteViews.keyAt(i);
                int dist = Math.abs(index-pos);
                if (dist > maxDistNotVisible && Arrays.binarySearch(visibleWindow, index) < 0) {
                    // maxDistNotVisible/maxDistIndexNotVisible will store the index of the
                    // farthest non-visible position
                    maxDistIndexNotVisible = index;
                    maxDistNotVisible = dist;
                }
                if (dist >= maxDist) {
                    // maxDist/maxDistIndex will store the index of the farthest position
                    // regardless of whether it is visible or not
                    maxDistIndex = index;
                    maxDist = dist;
                }
            }
            if (maxDistIndexNotVisible > -1) {
                return maxDistIndexNotVisible;
            }
            return maxDistIndex;
        }

        public void queueRequestedPositionToLoad(int position) {
            mLastRequestedIndex = position;
            synchronized (mIndicesToLoad) {
                mIndicesToLoad.put(position, true);
            }
        }
        public boolean queuePositionsToBePreloadedFromRequestedPosition(int position) {
            // Check if we need to preload any items
            if (mPreloadLowerBound <= position && position <= mPreloadUpperBound) {
                int center = (mPreloadUpperBound + mPreloadLowerBound) / 2;
                if (Math.abs(position - center) < mMaxCountSlack) {
                    return false;
                }
            }

            int count = 0;
            synchronized (mMetaData) {
                count = mMetaData.count;
            }
            synchronized (mIndicesToLoad) {
                // Remove all indices which have not been previously requested.
                for (int i = mIndicesToLoad.size() - 1; i >= 0; i--) {
                    if (!mIndicesToLoad.valueAt(i)) {
                        mIndicesToLoad.removeAt(i);
                    }
                }

                // Add all the preload indices
                int halfMaxCount = mMaxCount / 2;
                mPreloadLowerBound = position - halfMaxCount;
                mPreloadUpperBound = position + halfMaxCount;
                int effectiveLowerBound = Math.max(0, mPreloadLowerBound);
                int effectiveUpperBound = Math.min(mPreloadUpperBound, count - 1);
                for (int i = effectiveLowerBound; i <= effectiveUpperBound; ++i) {
                    if (mIndexRemoteViews.indexOfKey(i) < 0 && !mIndicesToLoad.get(i)) {
                        // If the index has not been requested, and has not been loaded.
                        mIndicesToLoad.put(i, false);
                    }
                }
            }
            return true;
        }
        /** Returns the next index to load */
        public int getNextIndexToLoad() {
            // We try and prioritize items that have been requested directly, instead
            // of items that are loaded as a result of the caching mechanism
            synchronized (mIndicesToLoad) {
                // Prioritize requested indices to be loaded first
                int index = mIndicesToLoad.indexOfValue(true);
                if (index < 0) {
                    // Otherwise, preload other indices as necessary
                    index = mIndicesToLoad.indexOfValue(false);
                }
                if (index < 0) {
                    return -1;
                } else {
                    int key = mIndicesToLoad.keyAt(index);
                    mIndicesToLoad.removeAt(index);
                    return key;
                }
            }
        }

        public boolean containsRemoteViewAt(int position) {
            return mIndexRemoteViews.indexOfKey(position) >= 0;
        }
        public boolean containsMetaDataAt(int position) {
            return mIndexMetaData.indexOfKey(position) >= 0;
        }

        public void reset() {
            // Note: We do not try and reset the meta data, since that information is still used by
            // collection views to validate it's own contents (and will be re-requested if the data
            // is invalidated through the notifyDataSetChanged() flow).

            mPreloadLowerBound = 0;
            mPreloadUpperBound = -1;
            mLastRequestedIndex = -1;
            mIndexRemoteViews.clear();
            mIndexMetaData.clear();
            synchronized (mIndicesToLoad) {
                mIndicesToLoad.clear();
            }
        }
    }

    static class RemoteViewsCacheKey {
        final Intent.FilterComparison filter;
        final int widgetId;

        RemoteViewsCacheKey(Intent.FilterComparison filter, int widgetId) {
            this.filter = filter;
            this.widgetId = widgetId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RemoteViewsCacheKey)) {
                return false;
            }
            RemoteViewsCacheKey other = (RemoteViewsCacheKey) o;
            return other.filter.equals(filter) && other.widgetId == widgetId;
        }

        @Override
        public int hashCode() {
            return (filter == null ? 0 : filter.hashCode()) ^ (widgetId << 2);
        }
    }

    public RemoteViewsAdapter(Context context, Intent intent,
            RemoteAdapterConnectionCallback callback, boolean useAsyncLoader) {
        mContext = context;
        mIntent = intent;

        if (mIntent == null) {
            throw new IllegalArgumentException("Non-null Intent must be specified.");
        }

        mAppWidgetId = intent.getIntExtra(RemoteViews.EXTRA_REMOTEADAPTER_APPWIDGET_ID, -1);
        mRequestedViews = new RemoteViewsFrameLayoutRefSet();

        // Strip the previously injected app widget id from service intent
        if (intent.hasExtra(RemoteViews.EXTRA_REMOTEADAPTER_APPWIDGET_ID)) {
            intent.removeExtra(RemoteViews.EXTRA_REMOTEADAPTER_APPWIDGET_ID);
        }

        // Initialize the worker thread
        mWorkerThread = new HandlerThread("RemoteViewsCache-loader");
        mWorkerThread.start();
        mWorkerQueue = new Handler(mWorkerThread.getLooper());
        mMainQueue = new Handler(Looper.myLooper(), this);
        mAsyncViewLoadExecutor = useAsyncLoader ? new HandlerThreadExecutor(mWorkerThread) : null;

        if (sCacheRemovalThread == null) {
            sCacheRemovalThread = new HandlerThread("RemoteViewsAdapter-cachePruner");
            sCacheRemovalThread.start();
            sCacheRemovalQueue = new Handler(sCacheRemovalThread.getLooper());
        }

        // Initialize the cache and the service connection on startup
        mCallback = new WeakReference<RemoteAdapterConnectionCallback>(callback);
        mServiceConnection = new RemoteViewsAdapterServiceConnection(this);

        RemoteViewsCacheKey key = new RemoteViewsCacheKey(new Intent.FilterComparison(mIntent),
                mAppWidgetId);

        synchronized(sCachedRemoteViewsCaches) {
            if (sCachedRemoteViewsCaches.containsKey(key)) {
                mCache = sCachedRemoteViewsCaches.get(key);
                synchronized (mCache.mMetaData) {
                    if (mCache.mMetaData.count > 0) {
                        // As a precautionary measure, we verify that the meta data indicates a
                        // non-zero count before declaring that data is ready.
                        mDataReady = true;
                    }
                }
            } else {
                mCache = new FixedSizeRemoteViewsCache(sDefaultCacheSize);
            }
            if (!mDataReady) {
                requestBindService();
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mWorkerThread != null) {
                mWorkerThread.quit();
            }
        } finally {
            super.finalize();
        }
    }

    public boolean isDataReady() {
        return mDataReady;
    }

    public void setRemoteViewsOnClickHandler(OnClickHandler handler) {
        mRemoteViewsOnClickHandler = handler;
    }

    public void saveRemoteViewsCache() {
        final RemoteViewsCacheKey key = new RemoteViewsCacheKey(
                new Intent.FilterComparison(mIntent), mAppWidgetId);

        synchronized(sCachedRemoteViewsCaches) {
            // If we already have a remove runnable posted for this key, remove it.
            if (sRemoteViewsCacheRemoveRunnables.containsKey(key)) {
                sCacheRemovalQueue.removeCallbacks(sRemoteViewsCacheRemoveRunnables.get(key));
                sRemoteViewsCacheRemoveRunnables.remove(key);
            }

            int metaDataCount = 0;
            int numRemoteViewsCached = 0;
            synchronized (mCache.mMetaData) {
                metaDataCount = mCache.mMetaData.count;
            }
            synchronized (mCache) {
                numRemoteViewsCached = mCache.mIndexRemoteViews.size();
            }
            if (metaDataCount > 0 && numRemoteViewsCached > 0) {
                sCachedRemoteViewsCaches.put(key, mCache);
            }

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    synchronized (sCachedRemoteViewsCaches) {
                        if (sCachedRemoteViewsCaches.containsKey(key)) {
                            sCachedRemoteViewsCaches.remove(key);
                        }
                        if (sRemoteViewsCacheRemoveRunnables.containsKey(key)) {
                            sRemoteViewsCacheRemoveRunnables.remove(key);
                        }
                    }
                }
            };
            sRemoteViewsCacheRemoveRunnables.put(key, r);
            sCacheRemovalQueue.postDelayed(r, REMOTE_VIEWS_CACHE_DURATION);
        }
    }

    private void loadNextIndexInBackground() {
        mWorkerQueue.post(new Runnable() {
            @Override
            public void run() {
                if (mServiceConnection.isConnected()) {
                    // Get the next index to load
                    int position = -1;
                    synchronized (mCache) {
                        position = mCache.getNextIndexToLoad();
                    }
                    if (position > -1) {
                        // Load the item, and notify any existing RemoteViewsFrameLayouts
                        updateRemoteViews(position, true);

                        // Queue up for the next one to load
                        loadNextIndexInBackground();
                    } else {
                        // No more items to load, so queue unbind
                        enqueueDeferredUnbindServiceMessage();
                    }
                }
            }
        });
    }

    private void processException(String method, Exception e) {
        Log.e("RemoteViewsAdapter", "Error in " + method + ": " + e.getMessage());

        // If we encounter a crash when updating, we should reset the metadata & cache and trigger
        // a notifyDataSetChanged to update the widget accordingly
        final RemoteViewsMetaData metaData = mCache.getMetaData();
        synchronized (metaData) {
            metaData.reset();
        }
        synchronized (mCache) {
            mCache.reset();
        }
        mMainQueue.post(new Runnable() {
            @Override
            public void run() {
                superNotifyDataSetChanged();
            }
        });
    }

    private void updateTemporaryMetaData() {
        IRemoteViewsFactory factory = mServiceConnection.getRemoteViewsFactory();

        try {
            // get the properties/first view (so that we can use it to
            // measure our dummy views)
            boolean hasStableIds = factory.hasStableIds();
            int viewTypeCount = factory.getViewTypeCount();
            int count = factory.getCount();
            LoadingViewTemplate loadingTemplate =
                    new LoadingViewTemplate(factory.getLoadingView(), mContext);
            if ((count > 0) && (loadingTemplate.remoteViews == null)) {
                RemoteViews firstView = factory.getViewAt(0);
                if (firstView != null) {
                    loadingTemplate.loadFirstViewHeight(firstView, mContext,
                            new HandlerThreadExecutor(mWorkerThread));
                }
            }
            final RemoteViewsMetaData tmpMetaData = mCache.getTemporaryMetaData();
            synchronized (tmpMetaData) {
                tmpMetaData.hasStableIds = hasStableIds;
                // We +1 because the base view type is the loading view
                tmpMetaData.viewTypeCount = viewTypeCount + 1;
                tmpMetaData.count = count;
                tmpMetaData.loadingTemplate = loadingTemplate;
            }
        } catch(RemoteException e) {
            processException("updateMetaData", e);
        } catch(RuntimeException e) {
            processException("updateMetaData", e);
        }
    }

    private void updateRemoteViews(final int position, boolean notifyWhenLoaded) {
        IRemoteViewsFactory factory = mServiceConnection.getRemoteViewsFactory();

        // Load the item information from the remote service
        RemoteViews remoteViews = null;
        long itemId = 0;
        try {
            remoteViews = factory.getViewAt(position);
            itemId = factory.getItemId(position);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in updateRemoteViews(" + position + "): " + e.getMessage());

            // Return early to prevent additional work in re-centering the view cache, and
            // swapping from the loading view
            return;
        } catch (RuntimeException e) {
            Log.e(TAG, "Error in updateRemoteViews(" + position + "): " + e.getMessage());
            return;
        }

        if (remoteViews == null) {
            // If a null view was returned, we break early to prevent it from getting
            // into our cache and causing problems later. The effect is that the child  at this
            // position will remain as a loading view until it is updated.
            Log.e(TAG, "Error in updateRemoteViews(" + position + "): " + " null RemoteViews " +
                    "returned from RemoteViewsFactory.");
            return;
        }

        int layoutId = remoteViews.getLayoutId();
        RemoteViewsMetaData metaData = mCache.getMetaData();
        boolean viewTypeInRange;
        int cacheCount;
        synchronized (metaData) {
            viewTypeInRange = metaData.isViewTypeInRange(layoutId);
            cacheCount = mCache.mMetaData.count;
        }
        synchronized (mCache) {
            if (viewTypeInRange) {
                int[] visibleWindow = getVisibleWindow(mVisibleWindowLowerBound,
                        mVisibleWindowUpperBound, cacheCount);
                // Cache the RemoteViews we loaded
                mCache.insert(position, remoteViews, itemId, visibleWindow);

                // Notify all the views that we have previously returned for this index that
                // there is new data for it.
                final RemoteViews rv = remoteViews;
                if (notifyWhenLoaded) {
                    mMainQueue.post(new Runnable() {
                        @Override
                        public void run() {
                            mRequestedViews.notifyOnRemoteViewsLoaded(position, rv);
                        }
                    });
                }
            } else {
                // We need to log an error here, as the the view type count specified by the
                // factory is less than the number of view types returned. We don't return this
                // view to the AdapterView, as this will cause an exception in the hosting process,
                // which contains the associated AdapterView.
                Log.e(TAG, "Error: widget's RemoteViewsFactory returns more view types than " +
                        " indicated by getViewTypeCount() ");
            }
        }
    }

    public Intent getRemoteViewsServiceIntent() {
        return mIntent;
    }

    public int getCount() {
        final RemoteViewsMetaData metaData = mCache.getMetaData();
        synchronized (metaData) {
            return metaData.count;
        }
    }

    public Object getItem(int position) {
        // Disallow arbitrary object to be associated with an item for the time being
        return null;
    }

    public long getItemId(int position) {
        synchronized (mCache) {
            if (mCache.containsMetaDataAt(position)) {
                return mCache.getMetaDataAt(position).itemId;
            }
            return 0;
        }
    }

    public int getItemViewType(int position) {
        int typeId = 0;
        synchronized (mCache) {
            if (mCache.containsMetaDataAt(position)) {
                typeId = mCache.getMetaDataAt(position).typeId;
            } else {
                return 0;
            }
        }

        final RemoteViewsMetaData metaData = mCache.getMetaData();
        synchronized (metaData) {
            return metaData.getMappedViewType(typeId);
        }
    }

    /**
     * This method allows an AdapterView using this Adapter to provide information about which
     * views are currently being displayed. This allows for certain optimizations and preloading
     * which  wouldn't otherwise be possible.
     */
    public void setVisibleRangeHint(int lowerBound, int upperBound) {
        mVisibleWindowLowerBound = lowerBound;
        mVisibleWindowUpperBound = upperBound;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        // "Request" an index so that we can queue it for loading, initiate subsequent
        // preloading, etc.
        synchronized (mCache) {
            RemoteViews rv = mCache.getRemoteViewsAt(position);
            boolean isInCache = (rv != null);
            boolean isConnected = mServiceConnection.isConnected();
            boolean hasNewItems = false;

            if (convertView != null && convertView instanceof RemoteViewsFrameLayout) {
                mRequestedViews.removeView((RemoteViewsFrameLayout) convertView);
            }

            if (!isInCache && !isConnected) {
                // Requesting bind service will trigger a super.notifyDataSetChanged(), which will
                // in turn trigger another request to getView()
                requestBindService();
            } else {
                // Queue up other indices to be preloaded based on this position
                hasNewItems = mCache.queuePositionsToBePreloadedFromRequestedPosition(position);
            }

            final RemoteViewsFrameLayout layout;
            if (convertView instanceof RemoteViewsFrameLayout) {
                layout = (RemoteViewsFrameLayout) convertView;
            } else {
                layout = new RemoteViewsFrameLayout(parent.getContext(), mCache);
                layout.setExecutor(mAsyncViewLoadExecutor);
            }

            if (isInCache) {
                // Apply the view synchronously if possible, to avoid flickering
                layout.onRemoteViewsLoaded(rv, mRemoteViewsOnClickHandler, false);
                if (hasNewItems) loadNextIndexInBackground();
            } else {
                // If the views is not loaded, apply the loading view. If the loading view doesn't
                // exist, the layout will create a default view based on the firstView height.
                layout.onRemoteViewsLoaded(
                        mCache.getMetaData().getLoadingTemplate(mContext).remoteViews,
                        mRemoteViewsOnClickHandler,
                        false);
                mRequestedViews.add(position, layout);
                mCache.queueRequestedPositionToLoad(position);
                loadNextIndexInBackground();
            }
            return layout;
        }
    }

    public int getViewTypeCount() {
        final RemoteViewsMetaData metaData = mCache.getMetaData();
        synchronized (metaData) {
            return metaData.viewTypeCount;
        }
    }

    public boolean hasStableIds() {
        final RemoteViewsMetaData metaData = mCache.getMetaData();
        synchronized (metaData) {
            return metaData.hasStableIds;
        }
    }

    public boolean isEmpty() {
        return getCount() <= 0;
    }

    private void onNotifyDataSetChanged() {
        // Complete the actual notifyDataSetChanged() call initiated earlier
        IRemoteViewsFactory factory = mServiceConnection.getRemoteViewsFactory();
        try {
            factory.onDataSetChanged();
        } catch (RemoteException e) {
            Log.e(TAG, "Error in updateNotifyDataSetChanged(): " + e.getMessage());

            // Return early to prevent from further being notified (since nothing has
            // changed)
            return;
        } catch (RuntimeException e) {
            Log.e(TAG, "Error in updateNotifyDataSetChanged(): " + e.getMessage());
            return;
        }

        // Flush the cache so that we can reload new items from the service
        synchronized (mCache) {
            mCache.reset();
        }

        // Re-request the new metadata (only after the notification to the factory)
        updateTemporaryMetaData();
        int newCount;
        int[] visibleWindow;
        synchronized(mCache.getTemporaryMetaData()) {
            newCount = mCache.getTemporaryMetaData().count;
            visibleWindow = getVisibleWindow(mVisibleWindowLowerBound,
                    mVisibleWindowUpperBound, newCount);
        }

        // Pre-load (our best guess of) the views which are currently visible in the AdapterView.
        // This mitigates flashing and flickering of loading views when a widget notifies that
        // its data has changed.
        for (int i: visibleWindow) {
            // Because temporary meta data is only ever modified from this thread (ie.
            // mWorkerThread), it is safe to assume that count is a valid representation.
            if (i < newCount) {
                updateRemoteViews(i, false);
            }
        }

        // Propagate the notification back to the base adapter
        mMainQueue.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mCache) {
                    mCache.commitTemporaryMetaData();
                }

                superNotifyDataSetChanged();
                enqueueDeferredUnbindServiceMessage();
            }
        });

        // Reset the notify flagflag
        mNotifyDataSetChangedAfterOnServiceConnected = false;
    }

    /**
     * Returns a sorted array of all integers between lower and upper.
     */
    private int[] getVisibleWindow(int lower, int upper, int count) {
        // In the case that the window is invalid or uninitialized, return an empty window.
        if ((lower == 0 && upper == 0) || lower < 0 || upper < 0) {
            return new int[0];
        }

        int[] window;
        if (lower <= upper) {
            window = new int[upper + 1 - lower];
            for (int i = lower, j = 0;  i <= upper; i++, j++){
                window[j] = i;
            }
        } else {
            // If the upper bound is less than the lower bound it means that the visible window
            // wraps around.
            count = Math.max(count, lower);
            window = new int[count - lower + upper + 1];
            int j = 0;
            // Add the entries in sorted order
            for (int i = 0; i <= upper; i++, j++) {
                window[j] = i;
            }
            for (int i = lower; i < count; i++, j++) {
                window[j] = i;
            }
        }
        return window;
    }

    public void notifyDataSetChanged() {
        // Dequeue any unbind messages
        mMainQueue.removeMessages(sUnbindServiceMessageType);

        // If we are not connected, queue up the notifyDataSetChanged to be handled when we do
        // connect
        if (!mServiceConnection.isConnected()) {
            mNotifyDataSetChangedAfterOnServiceConnected = true;
            requestBindService();
            return;
        }

        mWorkerQueue.post(new Runnable() {
            @Override
            public void run() {
                onNotifyDataSetChanged();
            }
        });
    }

    void superNotifyDataSetChanged() {
        super.notifyDataSetChanged();
    }

    @Override
    public boolean handleMessage(Message msg) {
        boolean result = false;
        switch (msg.what) {
        case sUnbindServiceMessageType:
            if (mServiceConnection.isConnected()) {
                mServiceConnection.unbind(mContext, mAppWidgetId, mIntent);
            }
            result = true;
            break;
        default:
            break;
        }
        return result;
    }

    private void enqueueDeferredUnbindServiceMessage() {
        // Remove any existing deferred-unbind messages
        mMainQueue.removeMessages(sUnbindServiceMessageType);
        mMainQueue.sendEmptyMessageDelayed(sUnbindServiceMessageType, sUnbindServiceDelay);
    }

    private boolean requestBindService() {
        // Try binding the service (which will start it if it's not already running)
        if (!mServiceConnection.isConnected()) {
            mServiceConnection.bind(mContext, mAppWidgetId, mIntent);
        }

        // Remove any existing deferred-unbind messages
        mMainQueue.removeMessages(sUnbindServiceMessageType);
        return mServiceConnection.isConnected();
    }

    private static class HandlerThreadExecutor implements Executor {
        private final HandlerThread mThread;

        HandlerThreadExecutor(HandlerThread thread) {
            mThread = thread;
        }

        @Override
        public void execute(Runnable runnable) {
            if (Thread.currentThread().getId() == mThread.getId()) {
                runnable.run();
            } else {
                new Handler(mThread.getLooper()).post(runnable);
            }
        }
    }

    private static class LoadingViewTemplate {
        public final RemoteViews remoteViews;
        public int defaultHeight;

        LoadingViewTemplate(RemoteViews views, Context context) {
            remoteViews = views;

            float density = context.getResources().getDisplayMetrics().density;
            defaultHeight = Math.round(sDefaultLoadingViewHeight * density);
        }

        public void loadFirstViewHeight(
                RemoteViews firstView, Context context, Executor executor) {
            // Inflate the first view on the worker thread
            firstView.applyAsync(context, new RemoteViewsFrameLayout(context, null), executor,
                    new RemoteViews.OnViewAppliedListener() {
                        @Override
                        public void onViewApplied(View v) {
                            try {
                                v.measure(
                                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                                defaultHeight = v.getMeasuredHeight();
                            } catch (Exception e) {
                                onError(e);
                            }
                        }

                        @Override
                        public void onError(Exception e) {
                            // Do nothing. The default height will stay the same.
                            Log.w(TAG, "Error inflating first RemoteViews", e);
                        }
                    });
        }
    }
}
