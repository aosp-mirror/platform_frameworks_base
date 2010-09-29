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

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;

import com.android.internal.widget.IRemoteViewsFactory;

/**
 * An adapter to a RemoteViewsService which fetches and caches RemoteViews
 * to be later inflated as child views.
 */
/** @hide */
public class RemoteViewsAdapter extends BaseAdapter {
    private static final String TAG = "RemoteViewsAdapter";

    private Context mContext;
    private Intent mIntent;
    private LayoutInflater mLayoutInflater;
    private RemoteViewsAdapterServiceConnection mServiceConnection;
    private WeakReference<RemoteAdapterConnectionCallback> mCallback;
    private FixedSizeRemoteViewsCache mCache;

    // The set of requested views that are to be notified when the associated RemoteViews are
    // loaded.
    private RemoteViewsFrameLayoutRefSet mRequestedViews;

    private HandlerThread mWorkerThread;
    // items may be interrupted within the normally processed queues
    private Handler mWorkerQueue;
    private Handler mMainQueue;

    /**
     * An interface for the RemoteAdapter to notify other classes when adapters
     * are actually connected to/disconnected from their actual services.
     */
    public interface RemoteAdapterConnectionCallback {
        public void onRemoteAdapterConnected();

        public void onRemoteAdapterDisconnected();
    }

    /**
     * The service connection that gets populated when the RemoteViewsService is
     * bound.  This must be a static inner class to ensure that no references to the outer
     * RemoteViewsAdapter instance is retained (this would prevent the RemoteViewsAdapter from being
     * garbage collected, and would cause us to leak activities due to the caching mechanism for
     * FrameLayouts in the adapter).
     */
    private static class RemoteViewsAdapterServiceConnection implements ServiceConnection {
        private boolean mConnected;
        private WeakReference<RemoteViewsAdapter> mAdapter;
        private IRemoteViewsFactory mRemoteViewsFactory;

        public RemoteViewsAdapterServiceConnection(RemoteViewsAdapter adapter) {
            mAdapter = new WeakReference<RemoteViewsAdapter>(adapter);
        }

        public void onServiceConnected(ComponentName name,
                IBinder service) {
            mRemoteViewsFactory = IRemoteViewsFactory.Stub.asInterface(service);
            mConnected = true;

            // Queue up work that we need to do for the callback to run
            final RemoteViewsAdapter adapter = mAdapter.get();
            if (adapter == null) return;
            adapter.mWorkerQueue.post(new Runnable() {
                @Override
                public void run() {
                    // Call back to the service to notify that the data set changed
                    if (adapter.mServiceConnection.isConnected()) {
                        IRemoteViewsFactory factory =
                            adapter.mServiceConnection.getRemoteViewsFactory();
                        try {
                            // call back to the factory
                            factory.onDataSetChanged();
                        } catch (Exception e) {
                            Log.e(TAG, "Error notifying factory of data set changed in " +
                                        "onServiceConnected(): " + e.getMessage());
                            e.printStackTrace();

                            // Return early to prevent anything further from being notified
                            // (effectively nothing has changed)
                            return;
                        }

                        // Request meta data so that we have up to date data when calling back to
                        // the remote adapter callback
                        adapter.updateMetaData();

                        // Post a runnable to call back to the view to notify it that we have
                        // connected
                        adapter.mMainQueue.post(new Runnable() {
                            @Override
                            public void run() {
                                final RemoteAdapterConnectionCallback callback =
                                    adapter.mCallback.get();
                                if (callback != null) {
                                    callback.onRemoteAdapterConnected();
                                }
                            }
                        });
                    }
                }
            });
        }

        public void onServiceDisconnected(ComponentName name) {
            mConnected = false;
            mRemoteViewsFactory = null;

            final RemoteViewsAdapter adapter = mAdapter.get();
            if (adapter == null) return;
            
            // Clear the main/worker queues
            adapter.mMainQueue.removeMessages(0);
            adapter.mWorkerQueue.removeMessages(0);

            // Clear the cache (the meta data will be re-requested on service re-connection)
            synchronized (adapter.mCache) {
                adapter.mCache.reset();
            }

            final RemoteAdapterConnectionCallback callback = adapter.mCallback.get();
            if (callback != null) {
                callback.onRemoteAdapterDisconnected();
            }
        }

        public IRemoteViewsFactory getRemoteViewsFactory() {
            return mRemoteViewsFactory;
        }

        public boolean isConnected() {
            return mConnected;
        }
    }

    /**
     * A FrameLayout which contains a loading view, and manages the re/applying of RemoteViews when
     * they are loaded.
     */
    private class RemoteViewsFrameLayout extends FrameLayout {
        public RemoteViewsFrameLayout(Context context) {
            super(context);
        }

        /**
         * Updates this RemoteViewsFrameLayout depending on the view that was loaded.
         * @param view the RemoteViews that was loaded. If null, the RemoteViews was not loaded
         *             successfully.
         */
        public void onRemoteViewsLoaded(RemoteViews view) {
            try {
                // Remove all the children of this layout first
                removeAllViews();
                addView(view.apply(getContext(), this));
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply RemoteViews.");
            }
        }
    }

    /**
     * Stores the references of all the RemoteViewsFrameLayouts that have been returned by the
     * adapter that have not yet had their RemoteViews loaded.
     */
    private class RemoteViewsFrameLayoutRefSet {
        private HashMap<Integer, LinkedList<RemoteViewsFrameLayout>> mReferences;

        public RemoteViewsFrameLayoutRefSet() {
            mReferences = new HashMap<Integer, LinkedList<RemoteViewsFrameLayout>>();
        }

        /**
         * Adds a new reference to a RemoteViewsFrameLayout returned by the adapter.
         */
        public void add(int position, RemoteViewsFrameLayout layout) {
            final Integer pos = position;
            LinkedList<RemoteViewsFrameLayout> refs;

            // Create the list if necessary
            if (mReferences.containsKey(pos)) {
                refs = mReferences.get(pos);
            } else {
                refs = new LinkedList<RemoteViewsFrameLayout>();
                mReferences.put(pos, refs);
            }

            // Add the references to the list
            refs.add(layout);
        }

        /**
         * Notifies each of the RemoteViewsFrameLayouts associated with a particular position that
         * the associated RemoteViews has loaded.
         */
        public void notifyOnRemoteViewsLoaded(int position, RemoteViews view, int typeId) {
            if (view == null) return;

            final Integer pos = position;
            if (mReferences.containsKey(pos)) {
                // Notify all the references for that position of the newly loaded RemoteViews
                final LinkedList<RemoteViewsFrameLayout> refs = mReferences.get(pos);
                for (final RemoteViewsFrameLayout ref : refs) {
                    ref.onRemoteViewsLoaded(view);
                }
                refs.clear();

                // Remove this set from the original mapping
                mReferences.remove(pos);
            }
        }

        /**
         * Removes all references to all RemoteViewsFrameLayouts returned by the adapter.
         */
        public void clear() {
            // We currently just clear the references, and leave all the previous layouts returned
            // in their default state of the loading view.
            mReferences.clear();
        }
    }

    /**
     * The meta-data associated with the cache in it's current state.
     */
    private class RemoteViewsMetaData {
        int count;
        int viewTypeCount;
        boolean hasStableIds;
        boolean isDataDirty;

        // Used to determine how to construct loading views.  If a loading view is not specified
        // by the user, then we try and load the first view, and use its height as the height for
        // the default loading view.
        RemoteViews mUserLoadingView;
        RemoteViews mFirstView;
        int mFirstViewHeight;

        // A mapping from type id to a set of unique type ids
        private Map<Integer, Integer> mTypeIdIndexMap;

        public RemoteViewsMetaData() {
            reset();
        }

        public void reset() {
            count = 0;
            // by default there is at least one dummy view type
            viewTypeCount = 1;
            hasStableIds = true;
            isDataDirty = false;
            mUserLoadingView = null;
            mFirstView = null;
            mFirstViewHeight = 0;
            mTypeIdIndexMap = new HashMap<Integer, Integer>();
        }

        public void setLoadingViewTemplates(RemoteViews loadingView, RemoteViews firstView) {
            mUserLoadingView = loadingView;
            if (firstView != null) {
                mFirstView = firstView;
                mFirstViewHeight = -1;
            }
        }

        public int getMappedViewType(int typeId) {
            if (mTypeIdIndexMap.containsKey(typeId)) {
                return mTypeIdIndexMap.get(typeId);
            } else {
                // We +1 because the loading view always has view type id of 0
                int incrementalTypeId = mTypeIdIndexMap.size() + 1;
                mTypeIdIndexMap.put(typeId, incrementalTypeId);
                return incrementalTypeId;
            }
        }

        private RemoteViewsFrameLayout createLoadingView(int position, View convertView,
                ViewGroup parent) {
            // Create and return a new FrameLayout, and setup the references for this position
            final Context context = parent.getContext();
            RemoteViewsFrameLayout layout = new RemoteViewsFrameLayout(context);

            // Create a new loading view
            synchronized (mCache) {
                if (mUserLoadingView != null) {
                    // A user-specified loading view
                    View loadingView = mUserLoadingView.apply(parent.getContext(), parent);
                    loadingView.setTag(new Integer(0));
                    layout.addView(loadingView);
                } else {
                    // A default loading view
                    // Use the size of the first row as a guide for the size of the loading view
                    if (mFirstViewHeight < 0) {
                        View firstView = mFirstView.apply(parent.getContext(), parent);
                        firstView.measure(
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                        mFirstViewHeight = firstView.getMeasuredHeight();
                        mFirstView = null;
                    }

                    // Compose the loading view text
                    TextView loadingTextView = (TextView) mLayoutInflater.inflate(
				com.android.internal.R.layout.remote_views_adapter_default_loading_view,
				layout, false);
                    loadingTextView.setHeight(mFirstViewHeight);
                    loadingTextView.setTag(new Integer(0));

                    layout.addView(loadingTextView);
                }
            }

            return layout;
        }
    }

    /**
     * The meta-data associated with a single item in the cache.
     */
    private class RemoteViewsIndexMetaData {
        int typeId;
        long itemId;

        public RemoteViewsIndexMetaData(RemoteViews v, long itemId) {
            set(v, itemId);
        }

        public void set(RemoteViews v, long id) {
            itemId = id;
            if (v != null)
                typeId = v.getLayoutId();
            else
                typeId = 0;
        }
    }

    /**
     *
     */
    private class FixedSizeRemoteViewsCache {
        private static final String TAG = "FixedSizeRemoteViewsCache";

        // The meta data related to all the RemoteViews, ie. count, is stable, etc.
        private RemoteViewsMetaData mMetaData;

        // The cache/mapping of position to RemoteViewsMetaData.  This set is guaranteed to be
        // greater than or equal to the set of RemoteViews.
        // Note: The reason that we keep this separate from the RemoteViews cache below is that this
        // we still need to be able to access the mapping of position to meta data, without keeping
        // the heavy RemoteViews around.  The RemoteViews cache is trimmed to fixed constraints wrt.
        // memory and size, but this metadata cache will retain information until the data at the
        // position is guaranteed as not being necessary any more (usually on notifyDataSetChanged).
        private HashMap<Integer, RemoteViewsIndexMetaData> mIndexMetaData;

        // The cache of actual RemoteViews, which may be pruned if the cache gets too large, or uses
        // too much memory.
        private HashMap<Integer, RemoteViews> mIndexRemoteViews;

        // The set of indices that have been explicitly requested by the collection view
        private HashSet<Integer> mRequestedIndices;

        // The set of indices to load, including those explicitly requested, as well as those
        // determined by the preloading algorithm to be prefetched
        private HashSet<Integer> mLoadIndices;

        // The lower and upper bounds of the preloaded range
        private int mPreloadLowerBound;
        private int mPreloadUpperBound;

        // The bounds of this fixed cache, we will try and fill as many items into the cache up to
        // the maxCount number of items, or the maxSize memory usage.
        // The maxCountSlack is used to determine if a new position in the cache to be loaded is
        // sufficiently ouside the old set, prompting a shifting of the "window" of items to be
        // preloaded.
        private int mMaxCount;
        private int mMaxCountSlack;
        private static final float sMaxCountSlackPercent = 0.75f;
        private static final int sMaxMemoryUsage = 1024 * 1024;

        public FixedSizeRemoteViewsCache(int maxCacheSize) {
            mMaxCount = maxCacheSize;
            mMaxCountSlack = Math.round(sMaxCountSlackPercent * (mMaxCount / 2));
            mPreloadLowerBound = 0;
            mPreloadUpperBound = -1;
            mMetaData = new RemoteViewsMetaData();
            mIndexMetaData = new HashMap<Integer, RemoteViewsIndexMetaData>();
            mIndexRemoteViews = new HashMap<Integer, RemoteViews>();
            mRequestedIndices = new HashSet<Integer>();
            mLoadIndices = new HashSet<Integer>();
        }

        public void insert(int position, RemoteViews v, long itemId) {
            // Trim the cache if we go beyond the count
            if (mIndexRemoteViews.size() >= mMaxCount) {
                mIndexRemoteViews.remove(getFarthestPositionFrom(position));
            }

            // Trim the cache if we go beyond the available memory size constraints
            while (getRemoteViewsBitmapMemoryUsage() >= sMaxMemoryUsage) {
                // Note: This is currently the most naive mechanism for deciding what to prune when
                // we hit the memory limit.  In the future, we may want to calculate which index to
                // remove based on both its position as well as it's current memory usage, as well
                // as whether it was directly requested vs. whether it was preloaded by our caching
                // mechanism.
                mIndexRemoteViews.remove(getFarthestPositionFrom(position));
            }

            // Update the metadata cache
            if (mIndexMetaData.containsKey(position)) {
                final RemoteViewsIndexMetaData metaData = mIndexMetaData.get(position);
                metaData.set(v, itemId);
            } else {
                mIndexMetaData.put(position, new RemoteViewsIndexMetaData(v, itemId));
            }
            mIndexRemoteViews.put(position, v);
        }

        public RemoteViewsMetaData getMetaData() {
            return mMetaData;
        }
        public RemoteViews getRemoteViewsAt(int position) {
            if (mIndexRemoteViews.containsKey(position)) {
                return mIndexRemoteViews.get(position);
            }
            return null;
        }
        public RemoteViewsIndexMetaData getMetaDataAt(int position) {
            if (mIndexMetaData.containsKey(position)) {
                return mIndexMetaData.get(position);
            }
            return null;
        }

        private int getRemoteViewsBitmapMemoryUsage() {
            // Calculate the memory usage of all the RemoteViews bitmaps being cached
            int mem = 0;
            for (Integer i : mIndexRemoteViews.keySet()) {
                final RemoteViews v = mIndexRemoteViews.get(i);
                mem += v.estimateBitmapMemoryUsage();
            }
            return mem;
        }
        private int getFarthestPositionFrom(int pos) {
            // Find the index farthest away and remove that
            int maxDist = 0;
            int maxDistIndex = -1;
            for (int i : mIndexRemoteViews.keySet()) {
                int dist = Math.abs(i-pos);
                if (dist > maxDist) {
                    maxDistIndex = i;
                    maxDist = dist;
                }
            }
            return maxDistIndex;
        }

        public void queueRequestedPositionToLoad(int position) {
            synchronized (mLoadIndices) {
                mRequestedIndices.add(position);
                mLoadIndices.add(position);
            }
        }
        public void queuePositionsToBePreloadedFromRequestedPosition(int position) {
            // Check if we need to preload any items
            if (mPreloadLowerBound <= position && position <= mPreloadUpperBound) {
                int center = (mPreloadUpperBound + mPreloadLowerBound) / 2;
                if (Math.abs(position - center) < mMaxCountSlack) {
                    return;
                }
            }

            int count = 0;
            synchronized (mMetaData) {
                count = mMetaData.count;
            }
            synchronized (mLoadIndices) {
                mLoadIndices.clear();

                // Add all the requested indices
                mLoadIndices.addAll(mRequestedIndices);

                // Add all the preload indices
                int halfMaxCount = mMaxCount / 2;
                mPreloadLowerBound = position - halfMaxCount;
                mPreloadUpperBound = position + halfMaxCount;
                int effectiveLowerBound = Math.max(0, mPreloadLowerBound);
                int effectiveUpperBound = Math.min(mPreloadUpperBound, count - 1);
                for (int i = effectiveLowerBound; i <= effectiveUpperBound; ++i) {
                    mLoadIndices.add(i);
                }

                // But remove all the indices that have already been loaded and are cached
                mLoadIndices.removeAll(mIndexRemoteViews.keySet());
            }
        }
        public int getNextIndexToLoad() {
            // We try and prioritize items that have been requested directly, instead
            // of items that are loaded as a result of the caching mechanism
            synchronized (mLoadIndices) {
                // Prioritize requested indices to be loaded first
                if (!mRequestedIndices.isEmpty()) {
                    Integer i = mRequestedIndices.iterator().next();
                    mRequestedIndices.remove(i);
                    mLoadIndices.remove(i);
                    return i.intValue();
                }

                // Otherwise, preload other indices as necessary
                if (!mLoadIndices.isEmpty()) {
                    Integer i = mLoadIndices.iterator().next();
                    mLoadIndices.remove(i);
                    return i.intValue();
                }

                return -1;
            }
        }

        public boolean containsRemoteViewAt(int position) {
            return mIndexRemoteViews.containsKey(position);
        }
        public boolean containsMetaDataAt(int position) {
            return mIndexMetaData.containsKey(position);
        }

        public void reset() {
            // Note: We do not try and reset the meta data, since that information is still used by
            // collection views to validate it's own contents (and will be re-requested if the data
            // is invalidated through the notifyDataSetChanged() flow).

            mPreloadLowerBound = 0;
            mPreloadUpperBound = -1;
            mIndexRemoteViews.clear();
            mIndexMetaData.clear();
            synchronized (mLoadIndices) {
                mRequestedIndices.clear();
                mLoadIndices.clear();
            }
        }
    }

    public RemoteViewsAdapter(Context context, Intent intent, RemoteAdapterConnectionCallback callback) {
        mContext = context;
        mIntent = intent;
        mLayoutInflater = LayoutInflater.from(context);
        if (mIntent == null) {
            throw new IllegalArgumentException("Non-null Intent must be specified.");
        }
        mRequestedViews = new RemoteViewsFrameLayoutRefSet();

        // initialize the worker thread
        mWorkerThread = new HandlerThread("RemoteViewsCache-loader");
        mWorkerThread.start();
        mWorkerQueue = new Handler(mWorkerThread.getLooper());
        mMainQueue = new Handler(Looper.myLooper());

        // initialize the cache and the service connection on startup
        mCache = new FixedSizeRemoteViewsCache(50);
        mCallback = new WeakReference<RemoteAdapterConnectionCallback>(callback);
        mServiceConnection = new RemoteViewsAdapterServiceConnection(this);
        requestBindService();
    }

    private void loadNextIndexInBackground() {
        mWorkerQueue.post(new Runnable() {
            @Override
            public void run() {
                // Get the next index to load
                int position = -1;
                synchronized (mCache) {
                    position = mCache.getNextIndexToLoad();
                }
                if (position > -1) {
                    // Load the item, and notify any existing RemoteViewsFrameLayouts
                    updateRemoteViews(position);

                    // Queue up for the next one to load
                    loadNextIndexInBackground();
                }
            }
        });
    }

    private void updateMetaData() {
        if (mServiceConnection.isConnected()) {
            try {
                IRemoteViewsFactory factory = mServiceConnection.getRemoteViewsFactory();

                // get the properties/first view (so that we can use it to
                // measure our dummy views)
                boolean hasStableIds = factory.hasStableIds();
                int viewTypeCount = factory.getViewTypeCount();
                int count = factory.getCount();
                RemoteViews loadingView = factory.getLoadingView();
                RemoteViews firstView = null;
                if ((count > 0) && (loadingView == null)) {
                    firstView = factory.getViewAt(0);
                }
                final RemoteViewsMetaData metaData = mCache.getMetaData();
                synchronized (metaData) {
                    metaData.hasStableIds = hasStableIds;
                    metaData.viewTypeCount = viewTypeCount + 1;
                    metaData.count = count;
                    metaData.setLoadingViewTemplates(loadingView, firstView);
                }
            } catch (Exception e) {
                // print the error
                Log.e(TAG, "Error in requestMetaData(): " + e.getMessage());

                // reset any members after the failed call
                final RemoteViewsMetaData metaData = mCache.getMetaData();
                synchronized (metaData) {
                    metaData.reset();
                }
            }
        }
    }

    private void updateRemoteViews(final int position) {
        if (mServiceConnection.isConnected()) {
            IRemoteViewsFactory factory = mServiceConnection.getRemoteViewsFactory();

            // Load the item information from the remote service
            RemoteViews remoteViews = null;
            long itemId = 0;
            try {
                remoteViews = factory.getViewAt(position);
                itemId = factory.getItemId(position);
            } catch (Exception e) {
                // Print the error
                Log.e(TAG, "Error in updateRemoteViewsInfo(" + position + "): " +
                        e.getMessage());
                e.printStackTrace();

                // Return early to prevent additional work in re-centering the view cache, and
                // swapping from the loading view
                return;
            }

            synchronized (mCache) {
                // Cache the RemoteViews we loaded
                mCache.insert(position, remoteViews, itemId);

                // Notify all the views that we have previously returned for this index that
                // there is new data for it.
                final RemoteViews rv = remoteViews;
                final int typeId = mCache.getMetaDataAt(position).typeId;
                mMainQueue.post(new Runnable() {
                    @Override
                    public void run() {
                        mRequestedViews.notifyOnRemoteViewsLoaded(position, rv, typeId);
                    }
                });
            }
        }
    }

    public Intent getRemoteViewsServiceIntent() {
        return mIntent;
    }

    public int getCount() {
        requestBindService();
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
        requestBindService();
        synchronized (mCache) {
            if (mCache.containsMetaDataAt(position)) {
                return mCache.getMetaDataAt(position).itemId;
            }
            return 0;
        }
    }

    public int getItemViewType(int position) {
        requestBindService();
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
     * Returns the item type id for the specified convert view.  Returns -1 if the convert view
     * is invalid.
     */
    private int getConvertViewTypeId(View convertView) {
        int typeId = -1;
        if (convertView != null && convertView.getTag() != null) {
            typeId = (Integer) convertView.getTag();
        }
        return typeId;
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        requestBindService();
        if (mServiceConnection.isConnected()) {
            // "Request" an index so that we can queue it for loading, initiate subsequent
            // preloading, etc.
            synchronized (mCache) {
                // Queue up other indices to be preloaded based on this position
                mCache.queuePositionsToBePreloadedFromRequestedPosition(position);

                RemoteViewsFrameLayout layout = (RemoteViewsFrameLayout) convertView;
                View convertViewChild = null;
                int convertViewTypeId = 0;
                if (convertView != null) {
                    convertViewChild = layout.getChildAt(0);
                    convertViewTypeId = getConvertViewTypeId(convertViewChild);
                }

                // Second, we try and retrieve the RemoteViews from the cache, returning a loading
                // view and queueing it to be loaded if it has not already been loaded.
                if (mCache.containsRemoteViewAt(position)) {
                    Context context = parent.getContext();
                    RemoteViews rv = mCache.getRemoteViewsAt(position);
                    int typeId = mCache.getMetaDataAt(position).typeId;

                    // Reuse the convert view where possible
                    if (convertView != null) {
                        if (convertViewTypeId == typeId) {
                            rv.reapply(context, convertViewChild);
                            return convertView;
                        }
                    }

                    // Otherwise, create a new view to be returned
                    View newView = rv.apply(context, parent);
                    newView.setTag(new Integer(typeId));
                    if (convertView != null) {
                        layout.removeAllViews();
                    } else {
                        layout = new RemoteViewsFrameLayout(context);
                    }
                    layout.addView(newView);
                    return layout;
                } else {
                    // If the cache does not have the RemoteViews at this position, then create a
                    // loading view and queue the actual position to be loaded in the background
                    RemoteViewsFrameLayout loadingView = null;
                    final RemoteViewsMetaData metaData = mCache.getMetaData();
                    synchronized (metaData) {
                        loadingView = metaData.createLoadingView(position, convertView, parent);
                    }

                    mRequestedViews.add(position, loadingView);
                    mCache.queueRequestedPositionToLoad(position);
                    loadNextIndexInBackground();

                    return loadingView;
                }
            }
        }
        return new View(parent.getContext());
    }

    public int getViewTypeCount() {
        requestBindService();
        final RemoteViewsMetaData metaData = mCache.getMetaData();
        synchronized (metaData) {
            return metaData.viewTypeCount;
        }
    }

    public boolean hasStableIds() {
        requestBindService();
        final RemoteViewsMetaData metaData = mCache.getMetaData();
        synchronized (metaData) {
            return metaData.hasStableIds;
        }
    }

    public boolean isEmpty() {
        return getCount() <= 0;
    }

    public void notifyDataSetChanged() {
        mWorkerQueue.post(new Runnable() {
            @Override
            public void run() {
                // Complete the actual notifyDataSetChanged() call initiated earlier
                if (mServiceConnection.isConnected()) {
                    IRemoteViewsFactory factory = mServiceConnection.getRemoteViewsFactory();
                    try {
                        factory.onDataSetChanged();
                    } catch (Exception e) {
                        Log.e(TAG, "Error in updateNotifyDataSetChanged(): " + e.getMessage());

                        // Return early to prevent from further being notified (since nothing has
                        // changed)
                        return;
                    }
                }

                // Flush the cache so that we can reload new items from the service
                synchronized (mCache) {
                    mCache.reset();
                }

                // Re-request the new metadata (only after the notification to the factory)
                updateMetaData();

                // Propagate the notification back to the base adapter
                mMainQueue.post(new Runnable() {
                    @Override
                    public void run() {
                        superNotifyDataSetChanged();
                    }
                });
            }
        });

        // Note: we do not call super.notifyDataSetChanged() until the RemoteViewsFactory has had
        // a chance to update itself and return new meta data associated with the new data.
    }

    private void superNotifyDataSetChanged() {
        super.notifyDataSetChanged();
    }

    private boolean requestBindService() {
        // try binding the service (which will start it if it's not already running)
        if (!mServiceConnection.isConnected()) {
            mContext.bindService(mIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }

        return mServiceConnection.isConnected();
    }
}
