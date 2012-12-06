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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.RemoteViews.OnClickHandler;

import com.android.internal.widget.IRemoteViewsAdapterConnection;
import com.android.internal.widget.IRemoteViewsFactory;
import com.android.internal.widget.LockPatternUtils;

/**
 * An adapter to a RemoteViewsService which fetches and caches RemoteViews
 * to be later inflated as child views.
 */
/** @hide */
public class RemoteViewsAdapter extends BaseAdapter implements Handler.Callback {
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
    private LayoutInflater mLayoutInflater;
    private RemoteViewsAdapterServiceConnection mServiceConnection;
    private WeakReference<RemoteAdapterConnectionCallback> mCallback;
    private OnClickHandler mRemoteViewsOnClickHandler;
    private FixedSizeRemoteViewsCache mCache;
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
    private static final HashMap<RemoteViewsCacheKey,
            FixedSizeRemoteViewsCache> sCachedRemoteViewsCaches
            = new HashMap<RemoteViewsCacheKey,
                    FixedSizeRemoteViewsCache>();
    private static final HashMap<RemoteViewsCacheKey, Runnable>
            sRemoteViewsCacheRemoveRunnables
            = new HashMap<RemoteViewsCacheKey, Runnable>();

    private static HandlerThread sCacheRemovalThread;
    private static Handler sCacheRemovalQueue;

    // We keep the cache around for a duration after onSaveInstanceState for use on re-inflation.
    // If a new RemoteViewsAdapter with the same intent / widget id isn't constructed within this
    // duration, the cache is dropped.
    private static final int REMOTE_VIEWS_CACHE_DURATION = 5000;

    // Used to indicate to the AdapterView that it can use this Adapter immediately after
    // construction (happens when we have a cached FixedSizeRemoteViewsCache).
    private boolean mDataReady = false;

    int mUserId;

    /**
     * An interface for the RemoteAdapter to notify other classes when adapters
     * are actually connected to/disconnected from their actual services.
     */
    public interface RemoteAdapterConnectionCallback {
        /**
         * @return whether the adapter was set or not.
         */
        public boolean onRemoteAdapterConnected();

        public void onRemoteAdapterDisconnected();

        /**
         * This defers a notifyDataSetChanged on the pending RemoteViewsAdapter if it has not
         * connected yet.
         */
        public void deferNotifyDataSetChanged();
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
                    if (Process.myUid() == Process.SYSTEM_UID
                            && (adapter = mAdapter.get()) != null) {
                        mgr.bindRemoteViewsService(appWidgetId, intent, asBinder(),
                                new UserHandle(adapter.mUserId));
                    } else {
                        mgr.bindRemoteViewsService(appWidgetId, intent, asBinder(),
                                Process.myUserHandle());
                    }
                    mIsConnecting = true;
                } catch (Exception e) {
                    Log.e("RemoteViewsAdapterServiceConnection", "bind(): " + e.getMessage());
                    mIsConnecting = false;
                    mIsConnected = false;
                }
            }
        }

        public synchronized void unbind(Context context, int appWidgetId, Intent intent) {
            try {
                RemoteViewsAdapter adapter;
                final AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                if (Process.myUid() == Process.SYSTEM_UID
                        && (adapter = mAdapter.get()) != null) {
                    mgr.unbindRemoteViewsService(appWidgetId, intent,
                            new UserHandle(adapter.mUserId));
                } else {
                    mgr.unbindRemoteViewsService(appWidgetId, intent, Process.myUserHandle());
                }
                mIsConnecting = false;
            } catch (Exception e) {
                Log.e("RemoteViewsAdapterServiceConnection", "unbind(): " + e.getMessage());
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
    private static class RemoteViewsFrameLayout extends FrameLayout {
        public RemoteViewsFrameLayout(Context context) {
            super(context);
        }

        /**
         * Updates this RemoteViewsFrameLayout depending on the view that was loaded.
         * @param view the RemoteViews that was loaded. If null, the RemoteViews was not loaded
         *             successfully.
         */
        public void onRemoteViewsLoaded(RemoteViews view, OnClickHandler handler) {
            try {
                // Remove all the children of this layout first
                removeAllViews();
                addView(view.apply(getContext(), this, handler));
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
        private HashMap<RemoteViewsFrameLayout, LinkedList<RemoteViewsFrameLayout>>
                mViewToLinkedList;

        public RemoteViewsFrameLayoutRefSet() {
            mReferences = new HashMap<Integer, LinkedList<RemoteViewsFrameLayout>>();
            mViewToLinkedList =
                    new HashMap<RemoteViewsFrameLayout, LinkedList<RemoteViewsFrameLayout>>();
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

            final Integer pos = position;
            if (mReferences.containsKey(pos)) {
                // Notify all the references for that position of the newly loaded RemoteViews
                final LinkedList<RemoteViewsFrameLayout> refs = mReferences.get(pos);
                for (final RemoteViewsFrameLayout ref : refs) {
                    ref.onRemoteViewsLoaded(view, mRemoteViewsOnClickHandler);
                    if (mViewToLinkedList.containsKey(ref)) {
                        mViewToLinkedList.remove(ref);
                    }
                }
                refs.clear();
                // Remove this set from the original mapping
                mReferences.remove(pos);
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
        RemoteViews mUserLoadingView;
        RemoteViews mFirstView;
        int mFirstViewHeight;

        // A mapping from type id to a set of unique type ids
        private final HashMap<Integer, Integer> mTypeIdIndexMap = new HashMap<Integer, Integer>();

        public RemoteViewsMetaData() {
            reset();
        }

        public void set(RemoteViewsMetaData d) {
            synchronized (d) {
                count = d.count;
                viewTypeCount = d.viewTypeCount;
                hasStableIds = d.hasStableIds;
                setLoadingViewTemplates(d.mUserLoadingView, d.mFirstView);
            }
        }

        public void reset() {
            count = 0;

            // by default there is at least one dummy view type
            viewTypeCount = 1;
            hasStableIds = true;
            mUserLoadingView = null;
            mFirstView = null;
            mFirstViewHeight = 0;
            mTypeIdIndexMap.clear();
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

        public boolean isViewTypeInRange(int typeId) {
            int mappedType = getMappedViewType(typeId);
            if (mappedType >= viewTypeCount) {
                return false;
            } else {
                return true;
            }
        }

        private RemoteViewsFrameLayout createLoadingView(int position, View convertView,
                ViewGroup parent, Object lock, LayoutInflater layoutInflater, OnClickHandler
                handler) {
            // Create and return a new FrameLayout, and setup the references for this position
            final Context context = parent.getContext();
            RemoteViewsFrameLayout layout = new RemoteViewsFrameLayout(context);

            // Create a new loading view
            synchronized (lock) {
                boolean customLoadingViewAvailable = false;

                if (mUserLoadingView != null) {
                    // Try to inflate user-specified loading view
                    try {
                        View loadingView = mUserLoadingView.apply(parent.getContext(), parent,
                                handler);
                        loadingView.setTagInternal(com.android.internal.R.id.rowTypeId,
                                new Integer(0));
                        layout.addView(loadingView);
                        customLoadingViewAvailable = true;
                    } catch (Exception e) {
                        Log.w(TAG, "Error inflating custom loading view, using default loading" +
                                "view instead", e);
                    }
                }
                if (!customLoadingViewAvailable) {
                    // A default loading view
                    // Use the size of the first row as a guide for the size of the loading view
                    if (mFirstViewHeight < 0) {
                        try {
                            View firstView = mFirstView.apply(parent.getContext(), parent, handler);
                            firstView.measure(
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                            mFirstViewHeight = firstView.getMeasuredHeight();
                            mFirstView = null;
                        } catch (Exception e) {
                            float density = context.getResources().getDisplayMetrics().density;
                            mFirstViewHeight = (int)
                                    Math.round(sDefaultLoadingViewHeight * density);
                            mFirstView = null;
                            Log.w(TAG, "Error inflating first RemoteViews" + e);
                        }
                    }

                    // Compose the loading view text
                    TextView loadingTextView = (TextView) layoutInflater.inflate(
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
        private final RemoteViewsMetaData mMetaData;
        private final RemoteViewsMetaData mTemporaryMetaData;

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

        // We keep a reference of the last requested index to determine which item to prune the
        // farthest items from when we hit the memory limit
        private int mLastRequestedIndex;

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
        private static final int sMaxMemoryLimitInBytes = 2 * 1024 * 1024;

        public FixedSizeRemoteViewsCache(int maxCacheSize) {
            mMaxCount = maxCacheSize;
            mMaxCountSlack = Math.round(sMaxCountSlackPercent * (mMaxCount / 2));
            mPreloadLowerBound = 0;
            mPreloadUpperBound = -1;
            mMetaData = new RemoteViewsMetaData();
            mTemporaryMetaData = new RemoteViewsMetaData();
            mIndexMetaData = new HashMap<Integer, RemoteViewsIndexMetaData>();
            mIndexRemoteViews = new HashMap<Integer, RemoteViews>();
            mRequestedIndices = new HashSet<Integer>();
            mLastRequestedIndex = -1;
            mLoadIndices = new HashSet<Integer>();
        }

        public void insert(int position, RemoteViews v, long itemId,
                ArrayList<Integer> visibleWindow) {
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
                mIndexRemoteViews.remove(getFarthestPositionFrom(pruneFromPosition, visibleWindow));
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
        public RemoteViewsMetaData getTemporaryMetaData() {
            return mTemporaryMetaData;
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
            for (Integer i : mIndexRemoteViews.keySet()) {
                final RemoteViews v = mIndexRemoteViews.get(i);
                if (v != null) {
                    mem += v.estimateMemoryUsage();
                }
            }
            return mem;
        }

        private int getFarthestPositionFrom(int pos, ArrayList<Integer> visibleWindow) {
            // Find the index farthest away and remove that
            int maxDist = 0;
            int maxDistIndex = -1;
            int maxDistNotVisible = 0;
            int maxDistIndexNotVisible = -1;
            for (int i : mIndexRemoteViews.keySet()) {
                int dist = Math.abs(i-pos);
                if (dist > maxDistNotVisible && !visibleWindow.contains(i)) {
                    // maxDistNotVisible/maxDistIndexNotVisible will store the index of the
                    // farthest non-visible position
                    maxDistIndexNotVisible = i;
                    maxDistNotVisible = dist;
                }
                if (dist >= maxDist) {
                    // maxDist/maxDistIndex will store the index of the farthest position
                    // regardless of whether it is visible or not
                    maxDistIndex = i;
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
            synchronized (mLoadIndices) {
                mRequestedIndices.add(position);
                mLoadIndices.add(position);
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
            return true;
        }
        /** Returns the next index to load, and whether that index was directly requested or not */
        public int[] getNextIndexToLoad() {
            // We try and prioritize items that have been requested directly, instead
            // of items that are loaded as a result of the caching mechanism
            synchronized (mLoadIndices) {
                // Prioritize requested indices to be loaded first
                if (!mRequestedIndices.isEmpty()) {
                    Integer i = mRequestedIndices.iterator().next();
                    mRequestedIndices.remove(i);
                    mLoadIndices.remove(i);
                    return new int[]{i.intValue(), 1};
                }

                // Otherwise, preload other indices as necessary
                if (!mLoadIndices.isEmpty()) {
                    Integer i = mLoadIndices.iterator().next();
                    mLoadIndices.remove(i);
                    return new int[]{i.intValue(), 0};
                }

                return new int[]{-1, 0};
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
            mLastRequestedIndex = -1;
            mIndexRemoteViews.clear();
            mIndexMetaData.clear();
            synchronized (mLoadIndices) {
                mRequestedIndices.clear();
                mLoadIndices.clear();
            }
        }
    }

    static class RemoteViewsCacheKey {
        final Intent.FilterComparison filter;
        final int widgetId;
        final int userId;

        RemoteViewsCacheKey(Intent.FilterComparison filter, int widgetId, int userId) {
            this.filter = filter;
            this.widgetId = widgetId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RemoteViewsCacheKey)) {
                return false;
            }
            RemoteViewsCacheKey other = (RemoteViewsCacheKey) o;
            return other.filter.equals(filter) && other.widgetId == widgetId
                    && other.userId == userId;
        }

        @Override
        public int hashCode() {
            return (filter == null ? 0 : filter.hashCode()) ^ (widgetId << 2) ^ (userId << 10);
        }
    }

    public RemoteViewsAdapter(Context context, Intent intent, RemoteAdapterConnectionCallback callback) {
        mContext = context;
        mIntent = intent;
        mAppWidgetId = intent.getIntExtra(RemoteViews.EXTRA_REMOTEADAPTER_APPWIDGET_ID, -1);
        mLayoutInflater = LayoutInflater.from(context);
        if (mIntent == null) {
            throw new IllegalArgumentException("Non-null Intent must be specified.");
        }
        mRequestedViews = new RemoteViewsFrameLayoutRefSet();

        if (Process.myUid() == Process.SYSTEM_UID) {
            mUserId = new LockPatternUtils(context).getCurrentUser();
        } else {
            mUserId = UserHandle.myUserId();
        }
        // Strip the previously injected app widget id from service intent
        if (intent.hasExtra(RemoteViews.EXTRA_REMOTEADAPTER_APPWIDGET_ID)) {
            intent.removeExtra(RemoteViews.EXTRA_REMOTEADAPTER_APPWIDGET_ID);
        }

        // Initialize the worker thread
        mWorkerThread = new HandlerThread("RemoteViewsCache-loader");
        mWorkerThread.start();
        mWorkerQueue = new Handler(mWorkerThread.getLooper());
        mMainQueue = new Handler(Looper.myLooper(), this);

        if (sCacheRemovalThread == null) {
            sCacheRemovalThread = new HandlerThread("RemoteViewsAdapter-cachePruner");
            sCacheRemovalThread.start();
            sCacheRemovalQueue = new Handler(sCacheRemovalThread.getLooper());
        }

        // Initialize the cache and the service connection on startup
        mCallback = new WeakReference<RemoteAdapterConnectionCallback>(callback);
        mServiceConnection = new RemoteViewsAdapterServiceConnection(this);

        RemoteViewsCacheKey key = new RemoteViewsCacheKey(new Intent.FilterComparison(mIntent),
                mAppWidgetId, mUserId);

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
                new Intent.FilterComparison(mIntent), mAppWidgetId, mUserId);

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
                        int[] res = mCache.getNextIndexToLoad();
                        position = res[0];
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
            RemoteViews loadingView = factory.getLoadingView();
            RemoteViews firstView = null;
            if ((count > 0) && (loadingView == null)) {
                firstView = factory.getViewAt(0);
            }
            final RemoteViewsMetaData tmpMetaData = mCache.getTemporaryMetaData();
            synchronized (tmpMetaData) {
                tmpMetaData.hasStableIds = hasStableIds;
                // We +1 because the base view type is the loading view
                tmpMetaData.viewTypeCount = viewTypeCount + 1;
                tmpMetaData.count = count;
                tmpMetaData.setLoadingViewTemplates(loadingView, firstView);
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
            remoteViews.setUser(new UserHandle(mUserId));
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
                ArrayList<Integer> visibleWindow = getVisibleWindow(mVisibleWindowLowerBound,
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
     * Returns the item type id for the specified convert view.  Returns -1 if the convert view
     * is invalid.
     */
    private int getConvertViewTypeId(View convertView) {
        int typeId = -1;
        if (convertView != null) {
            Object tag = convertView.getTag(com.android.internal.R.id.rowTypeId);
            if (tag != null) {
                typeId = (Integer) tag;
            }
        }
        return typeId;
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
            boolean isInCache = mCache.containsRemoteViewAt(position);
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

            if (isInCache) {
                View convertViewChild = null;
                int convertViewTypeId = 0;
                RemoteViewsFrameLayout layout = null;

                if (convertView instanceof RemoteViewsFrameLayout) {
                    layout = (RemoteViewsFrameLayout) convertView;
                    convertViewChild = layout.getChildAt(0);
                    convertViewTypeId = getConvertViewTypeId(convertViewChild);
                }

                // Second, we try and retrieve the RemoteViews from the cache, returning a loading
                // view and queueing it to be loaded if it has not already been loaded.
                Context context = parent.getContext();
                RemoteViews rv = mCache.getRemoteViewsAt(position);
                RemoteViewsIndexMetaData indexMetaData = mCache.getMetaDataAt(position);
                int typeId = indexMetaData.typeId;

                try {
                    // Reuse the convert view where possible
                    if (layout != null) {
                        if (convertViewTypeId == typeId) {
                            rv.reapply(context, convertViewChild, mRemoteViewsOnClickHandler);
                            return layout;
                        }
                        layout.removeAllViews();
                    } else {
                        layout = new RemoteViewsFrameLayout(context);
                    }

                    // Otherwise, create a new view to be returned
                    View newView = rv.apply(context, parent, mRemoteViewsOnClickHandler);
                    newView.setTagInternal(com.android.internal.R.id.rowTypeId,
                            new Integer(typeId));
                    layout.addView(newView);
                    return layout;

                } catch (Exception e){
                    // We have to make sure that we successfully inflated the RemoteViews, if not
                    // we return the loading view instead.
                    Log.w(TAG, "Error inflating RemoteViews at position: " + position + ", using" +
                            "loading view instead" + e);

                    RemoteViewsFrameLayout loadingView = null;
                    final RemoteViewsMetaData metaData = mCache.getMetaData();
                    synchronized (metaData) {
                        loadingView = metaData.createLoadingView(position, convertView, parent,
                                mCache, mLayoutInflater, mRemoteViewsOnClickHandler);
                    }
                    return loadingView;
                } finally {
                    if (hasNewItems) loadNextIndexInBackground();
                }
            } else {
                // If the cache does not have the RemoteViews at this position, then create a
                // loading view and queue the actual position to be loaded in the background
                RemoteViewsFrameLayout loadingView = null;
                final RemoteViewsMetaData metaData = mCache.getMetaData();
                synchronized (metaData) {
                    loadingView = metaData.createLoadingView(position, convertView, parent,
                            mCache, mLayoutInflater, mRemoteViewsOnClickHandler);
                }

                mRequestedViews.add(position, loadingView);
                mCache.queueRequestedPositionToLoad(position);
                loadNextIndexInBackground();

                return loadingView;
            }
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
        ArrayList<Integer> visibleWindow;
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

    private ArrayList<Integer> getVisibleWindow(int lower, int upper, int count) {
        ArrayList<Integer> window = new ArrayList<Integer>();

        // In the case that the window is invalid or uninitialized, return an empty window.
        if ((lower == 0 && upper == 0) || lower < 0 || upper < 0) {
            return window;
        }

        if (lower <= upper) {
            for (int i = lower;  i <= upper; i++){
                window.add(i);
            }
        } else {
            // If the upper bound is less than the lower bound it means that the visible window
            // wraps around.
            for (int i = lower; i < count; i++) {
                window.add(i);
            }
            for (int i = 0; i <= upper; i++) {
                window.add(i);
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
            if (mNotifyDataSetChangedAfterOnServiceConnected) {
                return;
            }

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
}
