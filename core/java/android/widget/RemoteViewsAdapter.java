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

import static android.widget.RemoteViews.EXTRA_REMOTEADAPTER_APPWIDGET_ID;
import static android.widget.RemoteViews.EXTRA_REMOTEADAPTER_ON_LIGHT_BACKGROUND;

import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.IServiceConnection;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.RemoteViews.InteractionHandler;

import com.android.internal.widget.IRemoteViewsFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Executor;

/**
 * An adapter to a RemoteViewsService which fetches and caches RemoteViews to be later inflated as
 * child views.
 *
 * The adapter runs in the host process, typically a Launcher app.
 *
 * It makes a service connection to the {@link RemoteViewsService} running in the
 * AppWidgetsProvider's process. This connection is made on a background thread (and proxied via
 * the platform to get the bind permissions) and all interaction with the service is done on the
 * background thread.
 *
 * On first bind, the adapter will load can cache the RemoteViews locally. Afterwards the
 * connection is only made when new RemoteViews are required.
 * @hide
 */
public class RemoteViewsAdapter extends BaseAdapter implements Handler.Callback {

    private static final String TAG = "RemoteViewsAdapter";

    // The max number of items in the cache
    private static final int DEFAULT_CACHE_SIZE = 40;
    // The delay (in millis) to wait until attempting to unbind from a service after a request.
    // This ensures that we don't stay continually bound to the service and that it can be destroyed
    // if we need the memory elsewhere in the system.
    private static final int UNBIND_SERVICE_DELAY = 5000;

    // Default height for the default loading view, in case we cannot get inflate the first view
    private static final int DEFAULT_LOADING_VIEW_HEIGHT = 50;

    // We cache the FixedSizeRemoteViewsCaches across orientation and re-inflation due to color
    // palette changes. These are the related data structures:
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

    private final Context mContext;
    private final Intent mIntent;
    private final int mAppWidgetId;
    private final boolean mOnLightBackground;
    private final Executor mAsyncViewLoadExecutor;

    private InteractionHandler mRemoteViewsInteractionHandler;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final FixedSizeRemoteViewsCache mCache;
    private int mVisibleWindowLowerBound;
    private int mVisibleWindowUpperBound;

    // The set of requested views that are to be notified when the associated RemoteViews are
    // loaded.
    private RemoteViewsFrameLayoutRefSet mRequestedViews;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final HandlerThread mWorkerThread;
    // items may be interrupted within the normally processed queues
    private final Handler mMainHandler;
    private final RemoteServiceHandler mServiceHandler;
    private final RemoteAdapterConnectionCallback mCallback;

    // Used to indicate to the AdapterView that it can use this Adapter immediately after
    // construction (happens when we have a cached FixedSizeRemoteViewsCache).
    private boolean mDataReady = false;

    /**
     * USed to dedupe {@link RemoteViews#mApplication} so that we do not hold on to
     * multiple copies of the same ApplicationInfo object.
     */
    private ApplicationInfo mLastRemoteViewAppInfo;

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

    static final int MSG_REQUEST_BIND = 1;
    static final int MSG_NOTIFY_DATA_SET_CHANGED = 2;
    static final int MSG_LOAD_NEXT_ITEM = 3;
    static final int MSG_UNBIND_SERVICE = 4;

    private static final int MSG_MAIN_HANDLER_COMMIT_METADATA = 1;
    private static final int MSG_MAIN_HANDLER_SUPER_NOTIFY_DATA_SET_CHANGED = 2;
    private static final int MSG_MAIN_HANDLER_REMOTE_ADAPTER_CONNECTED = 3;
    private static final int MSG_MAIN_HANDLER_REMOTE_ADAPTER_DISCONNECTED = 4;
    private static final int MSG_MAIN_HANDLER_REMOTE_VIEWS_LOADED = 5;

    /**
     * Handler for various interactions with the {@link RemoteViewsService}.
     */
    private static class RemoteServiceHandler extends Handler implements ServiceConnection {

        private final WeakReference<RemoteViewsAdapter> mAdapter;
        private final Context mContext;

        private IRemoteViewsFactory mRemoteViewsFactory;

        // The last call to notifyDataSetChanged didn't succeed, try again on next service bind.
        private boolean mNotifyDataSetChangedPending = false;
        private boolean mBindRequested = false;

        RemoteServiceHandler(Looper workerLooper, RemoteViewsAdapter adapter, Context context) {
            super(workerLooper);
            mAdapter = new WeakReference<>(adapter);
            mContext = context;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // This is called on the same thread.
            mRemoteViewsFactory = IRemoteViewsFactory.Stub.asInterface(service);
            enqueueDeferredUnbindServiceMessage();

            RemoteViewsAdapter adapter = mAdapter.get();
            if (adapter == null) {
                return;
            }

            if (mNotifyDataSetChangedPending) {
                mNotifyDataSetChangedPending = false;
                Message msg = Message.obtain(this, MSG_NOTIFY_DATA_SET_CHANGED);
                handleMessage(msg);
                msg.recycle();
            } else {
                if (!sendNotifyDataSetChange(false)) {
                    return;
                }

                // Request meta data so that we have up to date data when calling back to
                // the remote adapter callback
                adapter.updateTemporaryMetaData(mRemoteViewsFactory);
                adapter.mMainHandler.sendEmptyMessage(MSG_MAIN_HANDLER_COMMIT_METADATA);
                adapter.mMainHandler.sendEmptyMessage(MSG_MAIN_HANDLER_REMOTE_ADAPTER_CONNECTED);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mRemoteViewsFactory = null;
            RemoteViewsAdapter adapter = mAdapter.get();
            if (adapter != null) {
                adapter.mMainHandler.sendEmptyMessage(MSG_MAIN_HANDLER_REMOTE_ADAPTER_DISCONNECTED);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            RemoteViewsAdapter adapter = mAdapter.get();

            switch (msg.what) {
                case MSG_REQUEST_BIND: {
                    if (adapter == null || mRemoteViewsFactory != null) {
                        enqueueDeferredUnbindServiceMessage();
                    }
                    if (mBindRequested) {
                        return;
                    }
                    int flags = Context.BIND_AUTO_CREATE
                            | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE;
                    final IServiceConnection sd = mContext.getServiceDispatcher(this, this, flags);
                    Intent intent = (Intent) msg.obj;
                    int appWidgetId = msg.arg1;
                    try {
                        mBindRequested = AppWidgetManager.getInstance(mContext)
                                .bindRemoteViewsService(mContext, appWidgetId, intent, sd, flags);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to bind remoteViewsService: " + e.getMessage());
                    }
                    return;
                }
                case MSG_NOTIFY_DATA_SET_CHANGED: {
                    enqueueDeferredUnbindServiceMessage();
                    if (adapter == null) {
                        return;
                    }
                    if (mRemoteViewsFactory == null) {
                        mNotifyDataSetChangedPending = true;
                        adapter.requestBindService();
                        return;
                    }
                    if (!sendNotifyDataSetChange(true)) {
                        return;
                    }

                    // Flush the cache so that we can reload new items from the service
                    synchronized (adapter.mCache) {
                        adapter.mCache.reset();
                    }

                    // Re-request the new metadata (only after the notification to the factory)
                    adapter.updateTemporaryMetaData(mRemoteViewsFactory);
                    int newCount;
                    int[] visibleWindow;
                    synchronized (adapter.mCache.getTemporaryMetaData()) {
                        newCount = adapter.mCache.getTemporaryMetaData().count;
                        visibleWindow = adapter.getVisibleWindow(newCount);
                    }

                    // Pre-load (our best guess of) the views which are currently visible in the
                    // AdapterView. This mitigates flashing and flickering of loading views when a
                    // widget notifies that its data has changed.
                    for (int position : visibleWindow) {
                        // Because temporary meta data is only ever modified from this thread
                        // (ie. mWorkerThread), it is safe to assume that count is a valid
                        // representation.
                        if (position < newCount) {
                            adapter.updateRemoteViews(mRemoteViewsFactory, position, false);
                        }
                    }

                    // Propagate the notification back to the base adapter
                    adapter.mMainHandler.sendEmptyMessage(MSG_MAIN_HANDLER_COMMIT_METADATA);
                    adapter.mMainHandler.sendEmptyMessage(
                            MSG_MAIN_HANDLER_SUPER_NOTIFY_DATA_SET_CHANGED);
                    return;
                }

                case MSG_LOAD_NEXT_ITEM: {
                    if (adapter == null || mRemoteViewsFactory == null) {
                        return;
                    }
                    removeMessages(MSG_UNBIND_SERVICE);
                    // Get the next index to load
                    final int position = adapter.mCache.getNextIndexToLoad();
                    if (position > -1) {
                        // Load the item, and notify any existing RemoteViewsFrameLayouts
                        adapter.updateRemoteViews(mRemoteViewsFactory, position, true);

                        // Queue up for the next one to load
                        sendEmptyMessage(MSG_LOAD_NEXT_ITEM);
                    } else {
                        // No more items to load, so queue unbind
                        enqueueDeferredUnbindServiceMessage();
                    }
                    return;
                }
                case MSG_UNBIND_SERVICE: {
                    unbindNow();
                    return;
                }
            }
        }

        protected void unbindNow() {
            if (mBindRequested) {
                mBindRequested = false;
                mContext.unbindService(this);
            }
            mRemoteViewsFactory = null;
        }

        private boolean sendNotifyDataSetChange(boolean always) {
            try {
                if (always || !mRemoteViewsFactory.isCreated()) {
                    mRemoteViewsFactory.onDataSetChanged();
                }
                return true;
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "Error in updateNotifyDataSetChanged(): " + e.getMessage());
                return false;
            }
        }

        private void enqueueDeferredUnbindServiceMessage() {
            removeMessages(MSG_UNBIND_SERVICE);
            sendEmptyMessageDelayed(MSG_UNBIND_SERVICE, UNBIND_SERVICE_DELAY);
        }
    }

    /**
     * A FrameLayout which contains a loading view, and manages the re/applying of RemoteViews when
     * they are loaded.
     */
    static class RemoteViewsFrameLayout extends AppWidgetHostView {
        private final FixedSizeRemoteViewsCache mCache;

        public int cacheIndex = -1;

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
        public void onRemoteViewsLoaded(RemoteViews view, InteractionHandler handler,
                boolean forceApplyAsync) {
            setInteractionHandler(handler);
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
    private class RemoteViewsFrameLayoutRefSet
            extends SparseArray<LinkedList<RemoteViewsFrameLayout>> {

        /**
         * Adds a new reference to a RemoteViewsFrameLayout returned by the adapter.
         */
        public void add(int position, RemoteViewsFrameLayout layout) {
            LinkedList<RemoteViewsFrameLayout> refs = get(position);

            // Create the list if necessary
            if (refs == null) {
                refs = new LinkedList<>();
                put(position, refs);
            }

            // Add the references to the list
            layout.cacheIndex = position;
            refs.add(layout);
        }

        /**
         * Notifies each of the RemoteViewsFrameLayouts associated with a particular position that
         * the associated RemoteViews has loaded.
         */
        public void notifyOnRemoteViewsLoaded(int position, RemoteViews view) {
            if (view == null) return;

            // Remove this set from the original mapping
            final LinkedList<RemoteViewsFrameLayout> refs = removeReturnOld(position);
            if (refs != null) {
                // Notify all the references for that position of the newly loaded RemoteViews
                for (final RemoteViewsFrameLayout ref : refs) {
                    ref.onRemoteViewsLoaded(view, mRemoteViewsInteractionHandler, true);
                }
            }
        }

        /**
         * We need to remove views from this set if they have been recycled by the AdapterView.
         */
        public void removeView(RemoteViewsFrameLayout rvfl) {
            if (rvfl.cacheIndex < 0) {
                return;
            }
            final LinkedList<RemoteViewsFrameLayout> refs = get(rvfl.cacheIndex);
            if (refs != null) {
                refs.remove(rvfl);
            }
            rvfl.cacheIndex = -1;
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

            // by default there is at least one placeholder view type
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
     * Config diff flags for which the cache should be reset
     */
    private static final int CACHE_RESET_CONFIG_FLAGS = ActivityInfo.CONFIG_FONT_SCALE
            | ActivityInfo.CONFIG_UI_MODE | ActivityInfo.CONFIG_DENSITY
            | ActivityInfo.CONFIG_ASSETS_PATHS;
    /**
     *
     */
    private static class FixedSizeRemoteViewsCache {

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

        // An array of indices to load, Indices which are explicitly requested are set to true,
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

        // Configuration for which the cache was created
        private final Configuration mConfiguration;

        FixedSizeRemoteViewsCache(int maxCacheSize, Configuration configuration) {
            mMaxCount = maxCacheSize;
            mMaxCountSlack = Math.round(sMaxCountSlackPercent * (mMaxCount / 2));
            mPreloadLowerBound = 0;
            mPreloadUpperBound = -1;
            mLastRequestedIndex = -1;

            mConfiguration = new Configuration(configuration);
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

            int count;
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
        public boolean equals(@Nullable Object o) {
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

        mAppWidgetId = intent.getIntExtra(EXTRA_REMOTEADAPTER_APPWIDGET_ID, -1);
        mRequestedViews = new RemoteViewsFrameLayoutRefSet();
        mOnLightBackground = intent.getBooleanExtra(EXTRA_REMOTEADAPTER_ON_LIGHT_BACKGROUND, false);

        // Strip the previously injected app widget id from service intent
        intent.removeExtra(EXTRA_REMOTEADAPTER_APPWIDGET_ID);
        intent.removeExtra(EXTRA_REMOTEADAPTER_ON_LIGHT_BACKGROUND);

        // Initialize the worker thread
        mWorkerThread = new HandlerThread("RemoteViewsCache-loader");
        mWorkerThread.start();
        mMainHandler = new Handler(Looper.myLooper(), this);
        mServiceHandler = new RemoteServiceHandler(mWorkerThread.getLooper(), this,
                context.getApplicationContext());
        mAsyncViewLoadExecutor = useAsyncLoader ? new HandlerThreadExecutor(mWorkerThread) : null;
        mCallback = callback;

        if (sCacheRemovalThread == null) {
            sCacheRemovalThread = new HandlerThread("RemoteViewsAdapter-cachePruner");
            sCacheRemovalThread.start();
            sCacheRemovalQueue = new Handler(sCacheRemovalThread.getLooper());
        }

        RemoteViewsCacheKey key = new RemoteViewsCacheKey(new Intent.FilterComparison(mIntent),
                mAppWidgetId);

        synchronized(sCachedRemoteViewsCaches) {
            FixedSizeRemoteViewsCache cache = sCachedRemoteViewsCaches.get(key);
            Configuration config = context.getResources().getConfiguration();
            if (cache == null
                    || (cache.mConfiguration.diff(config) & CACHE_RESET_CONFIG_FLAGS) != 0) {
                mCache = new FixedSizeRemoteViewsCache(DEFAULT_CACHE_SIZE, config);
            } else {
                mCache = sCachedRemoteViewsCaches.get(key);
                synchronized (mCache.mMetaData) {
                    if (mCache.mMetaData.count > 0) {
                        // As a precautionary measure, we verify that the meta data indicates a
                        // non-zero count before declaring that data is ready.
                        mDataReady = true;
                    }
                }
            }
            if (!mDataReady) {
                requestBindService();
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mServiceHandler.unbindNow();
            mWorkerThread.quit();
        } finally {
            super.finalize();
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isDataReady() {
        return mDataReady;
    }

    /** @hide */
    public void setRemoteViewsInteractionHandler(InteractionHandler handler) {
        mRemoteViewsInteractionHandler = handler;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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

            Runnable r = () -> {
                synchronized (sCachedRemoteViewsCaches) {
                    if (sCachedRemoteViewsCaches.containsKey(key)) {
                        sCachedRemoteViewsCaches.remove(key);
                    }
                    if (sRemoteViewsCacheRemoveRunnables.containsKey(key)) {
                        sRemoteViewsCacheRemoveRunnables.remove(key);
                    }
                }
            };
            sRemoteViewsCacheRemoveRunnables.put(key, r);
            sCacheRemovalQueue.postDelayed(r, REMOTE_VIEWS_CACHE_DURATION);
        }
    }

    @WorkerThread
    private void updateTemporaryMetaData(IRemoteViewsFactory factory) {
        try {
            // get the properties/first view (so that we can use it to
            // measure our placeholder views)
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
        } catch (RemoteException | RuntimeException e) {
            Log.e("RemoteViewsAdapter", "Error in updateMetaData: " + e.getMessage());

            // If we encounter a crash when updating, we should reset the metadata & cache
            // and trigger a notifyDataSetChanged to update the widget accordingly
            synchronized (mCache.getMetaData()) {
                mCache.getMetaData().reset();
            }
            synchronized (mCache) {
                mCache.reset();
            }
            mMainHandler.sendEmptyMessage(MSG_MAIN_HANDLER_SUPER_NOTIFY_DATA_SET_CHANGED);
        }
    }

    @WorkerThread
    private void updateRemoteViews(IRemoteViewsFactory factory, int position,
            boolean notifyWhenLoaded) {
        // Load the item information from the remote service
        final RemoteViews remoteViews;
        final long itemId;
        try {
            remoteViews = factory.getViewAt(position);
            itemId = factory.getItemId(position);

            if (remoteViews == null) {
                throw new RuntimeException("Null remoteViews");
            }
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Error in updateRemoteViews(" + position + "): " + e.getMessage());

            // Return early to prevent additional work in re-centering the view cache, and
            // swapping from the loading view
            return;
        }

        if (remoteViews.mApplication != null) {
            // We keep track of last application info. This helps when all the remoteViews have
            // same applicationInfo, which should be the case for a typical adapter. But if every
            // view has different application info, there will not be any optimization.
            if (mLastRemoteViewAppInfo != null
                    && remoteViews.hasSameAppInfo(mLastRemoteViewAppInfo)) {
                // We should probably also update the remoteViews for nested ViewActions.
                // Hopefully, RemoteViews in an adapter would be less complicated.
                remoteViews.mApplication = mLastRemoteViewAppInfo;
            } else {
                mLastRemoteViewAppInfo = remoteViews.mApplication;
            }
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
                int[] visibleWindow = getVisibleWindow(cacheCount);
                // Cache the RemoteViews we loaded
                mCache.insert(position, remoteViews, itemId, visibleWindow);

                if (notifyWhenLoaded) {
                    // Notify all the views that we have previously returned for this index that
                    // there is new data for it.
                    Message.obtain(mMainHandler, MSG_MAIN_HANDLER_REMOTE_VIEWS_LOADED, position, 0,
                            remoteViews).sendToTarget();
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

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
        final int typeId;
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
            boolean hasNewItems = false;

            if (convertView != null && convertView instanceof RemoteViewsFrameLayout) {
                mRequestedViews.removeView((RemoteViewsFrameLayout) convertView);
            }

            if (!isInCache) {
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
                layout.setOnLightBackground(mOnLightBackground);
            }

            if (isInCache) {
                // Apply the view synchronously if possible, to avoid flickering
                layout.onRemoteViewsLoaded(rv, mRemoteViewsInteractionHandler, false);
                if (hasNewItems) {
                    mServiceHandler.sendEmptyMessage(MSG_LOAD_NEXT_ITEM);
                }
            } else {
                // If the views is not loaded, apply the loading view. If the loading view doesn't
                // exist, the layout will create a default view based on the firstView height.
                layout.onRemoteViewsLoaded(
                        mCache.getMetaData().getLoadingTemplate(mContext).remoteViews,
                        mRemoteViewsInteractionHandler,
                        false);
                mRequestedViews.add(position, layout);
                mCache.queueRequestedPositionToLoad(position);
                mServiceHandler.sendEmptyMessage(MSG_LOAD_NEXT_ITEM);
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

    /**
     * Returns a sorted array of all integers between lower and upper.
     */
    private int[] getVisibleWindow(int count) {
        int lower = mVisibleWindowLowerBound;
        int upper = mVisibleWindowUpperBound;
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
        mServiceHandler.removeMessages(MSG_UNBIND_SERVICE);
        mServiceHandler.sendEmptyMessage(MSG_NOTIFY_DATA_SET_CHANGED);
    }

    void superNotifyDataSetChanged() {
        super.notifyDataSetChanged();
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_MAIN_HANDLER_COMMIT_METADATA: {
                mCache.commitTemporaryMetaData();
                return true;
            }
            case MSG_MAIN_HANDLER_SUPER_NOTIFY_DATA_SET_CHANGED: {
                superNotifyDataSetChanged();
                return true;
            }
            case MSG_MAIN_HANDLER_REMOTE_ADAPTER_CONNECTED: {
                if (mCallback != null) {
                    mCallback.onRemoteAdapterConnected();
                }
                return true;
            }
            case MSG_MAIN_HANDLER_REMOTE_ADAPTER_DISCONNECTED: {
                if (mCallback != null) {
                    mCallback.onRemoteAdapterDisconnected();
                }
                return true;
            }
            case MSG_MAIN_HANDLER_REMOTE_VIEWS_LOADED: {
                mRequestedViews.notifyOnRemoteViewsLoaded(msg.arg1, (RemoteViews) msg.obj);
                return true;
            }
        }
        return false;
    }

    private void requestBindService() {
        mServiceHandler.removeMessages(MSG_UNBIND_SERVICE);
        Message.obtain(mServiceHandler, MSG_REQUEST_BIND, mAppWidgetId, 0, mIntent).sendToTarget();
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
            defaultHeight = Math.round(DEFAULT_LOADING_VIEW_HEIGHT * density);
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
