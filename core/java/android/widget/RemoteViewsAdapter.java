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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.MeasureSpec;

import com.android.internal.widget.IRemoteViewsFactory;

/**
 * An adapter to a RemoteViewsService which fetches and caches RemoteViews
 * to be later inflated as child views.
 */
/** @hide */
public class RemoteViewsAdapter extends BaseAdapter {

    private static final String LOG_TAG = "RemoteViewsAdapter";

    private Context mContext;
    private Intent mIntent;
    private RemoteViewsAdapterServiceConnection mServiceConnection;
    private RemoteViewsCache mViewCache;

    private HandlerThread mWorkerThread;
    // items may be interrupted within the normally processed queues
    private Handler mWorkerQueue;
    private Handler mMainQueue;
    // items are never dequeued from the priority queue and must run
    private Handler mWorkerPriorityQueue;
    private Handler mMainPriorityQueue;

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
     * bound.
     */
    private class RemoteViewsAdapterServiceConnection implements ServiceConnection {
        private boolean mConnected;
        private IRemoteViewsFactory mRemoteViewsFactory;
        private RemoteAdapterConnectionCallback mCallback;

        public RemoteViewsAdapterServiceConnection(RemoteAdapterConnectionCallback callback) {
            mCallback = callback;
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            mRemoteViewsFactory = IRemoteViewsFactory.Stub.asInterface(service);
            mConnected = true;
            // notifyDataSetChanged should be called first, to ensure that the
            // views are not updated twice
            notifyDataSetChanged();

            // post a new runnable to load the appropriate data, then callback
            mWorkerPriorityQueue.post(new Runnable() {
                @Override
                public void run() {
                    // we need to get the viewTypeCount specifically, so just get all the
                    // metadata
                    mViewCache.requestMetaData();

                    // post a runnable to call the callback on the main thread
                    mMainPriorityQueue.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mCallback != null)
                                mCallback.onRemoteAdapterConnected();
                        }
                    });
                }
            });
        }

        public void onServiceDisconnected(ComponentName name) {
            mRemoteViewsFactory = null;
            mConnected = false;
            if (mCallback != null)
                mCallback.onRemoteAdapterDisconnected();

            // clear the main/worker queues
            mMainQueue.removeMessages(0);
            mWorkerQueue.removeMessages(0);
        }

        public IRemoteViewsFactory getRemoteViewsFactory() {
            return mRemoteViewsFactory;
        }

        public boolean isConnected() {
            return mConnected;
        }
    }

    /**
     * An internal cache of remote views.
     */
    private class RemoteViewsCache {
        private RemoteViewsInfo mViewCacheInfo;
        private RemoteViewsIndexInfo[] mViewCache;

        // if a user loading view is not provided, then we create a temporary one
        // for the user using the height of the first view
        private RemoteViews mUserLoadingView;
        private RemoteViews mFirstView;
        private int mFirstViewHeight;

        // determines when the current cache window needs to be updated with new
        // items (ie. when there is not enough slack)
        private int mViewCacheStartPosition;
        private int mViewCacheEndPosition;
        private int mHalfCacheSize;
        private int mCacheSlack;
        private final float mCacheSlackPercentage = 0.75f;

        // determines whether to reorder the posted items on the worker thread
        // so that the items in the current window can be loaded first
        private int mPriorityLoadingWindowSize;
        private int mPriorityLoadingWindowStart;
        private int mPriorityLoadingWindowEnd;

        // determines which way to load items in the current window based on how
        // the window shifted last
        private boolean mLoadUpwards;

        /**
         * The data structure stored at each index of the cache. Any member 
         * that is not invalidated persists throughout the lifetime of the cache.
         */
        private class RemoteViewsIndexInfo {
            FrameLayout flipper;
            RemoteViews view;
            long itemId;
            int typeId;

            RemoteViewsIndexInfo() {
                invalidate();
            }

            void set(RemoteViews v, long id) {
                view = v;
                itemId = id;
                if (v != null)
                    typeId = v.getLayoutId();
                else
                    typeId = 0;
            }

            void invalidate() {
                view = null;
                itemId = 0;
                typeId = 0;
            }

            final boolean isValid() {
                return (view != null);
            }
        }

        /**
         * Remote adapter metadata. Useful for when we have to lock on something
         * before updating the metadata.
         */
        private class RemoteViewsInfo {
            int count;
            int viewTypeCount;
            boolean hasStableIds;
            Map<Integer, Integer> mTypeIdIndexMap;

            RemoteViewsInfo() {
                count = 0;
                // by default there is at least one dummy view type
                viewTypeCount = 1;
                hasStableIds = true;
                mTypeIdIndexMap = new HashMap<Integer, Integer>();
            }
        }

        public RemoteViewsCache(int halfCacheSize) {
            mHalfCacheSize = halfCacheSize;
            mCacheSlack = Math.round(mCacheSlackPercentage * mHalfCacheSize);
            mViewCacheStartPosition = 0;
            mViewCacheEndPosition = -1;
            mPriorityLoadingWindowSize = 4;
            mPriorityLoadingWindowStart = 0;
            mPriorityLoadingWindowEnd = 0;
            mLoadUpwards = false;

            // initialize the cache
            mViewCacheInfo = new RemoteViewsInfo();
            mViewCache = new RemoteViewsIndexInfo[2 * mHalfCacheSize + 1];
            for (int i = 0; i < mViewCache.length; ++i) {
                mViewCache[i] = new RemoteViewsIndexInfo();
            }
        }

        private final boolean contains(int position) {
            // take the modulo of the position
            return (mViewCacheStartPosition <= position) && (position < mViewCacheEndPosition);
        }

        private final boolean containsAndIsValid(int position) {
            if (contains(position)) {
                RemoteViewsIndexInfo indexInfo = mViewCache[getCacheIndex(position)];
                if (indexInfo.isValid()) {
                    return true;
                }
            }
            return false;
        }

        private final int getCacheIndex(int position) {
            return (mViewCache.length + (position % mViewCache.length)) % mViewCache.length;
        }

        public void requestMetaData() {
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
                    synchronized (mViewCacheInfo) {
                        RemoteViewsInfo info = mViewCacheInfo;
                        info.hasStableIds = hasStableIds;
                        info.viewTypeCount = viewTypeCount + 1;
                        info.count = count;
                        mUserLoadingView = loadingView;
                        if (firstView != null) {
                            mFirstView = firstView;
                            mFirstViewHeight = -1;
                        }
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        protected void updateRemoteViewsInfo(int position) {
            if (mServiceConnection.isConnected()) {
                IRemoteViewsFactory factory = mServiceConnection.getRemoteViewsFactory();

                // load the item information
                RemoteViews remoteView = null;
                long itemId = 0;
                try {
                    remoteView = factory.getViewAt(position);
                    itemId = factory.getItemId(position);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }

                synchronized (mViewCache) {
                    // skip if the window has moved
                    if (position < mViewCacheStartPosition || position >= mViewCacheEndPosition)
                        return;

                    final int positionIndex = position;
                    final int cacheIndex = getCacheIndex(position);
                    mViewCache[cacheIndex].set(remoteView, itemId);

                    // notify the main thread when done loading
                    // flush pending updates
                    mMainQueue.post(new Runnable() {
                        @Override
                        public void run() {
                            // swap the loader view for this view
                            synchronized (mViewCache) {
                                if (containsAndIsValid(positionIndex)) {
                                    RemoteViewsIndexInfo indexInfo = mViewCache[cacheIndex];
                                    FrameLayout flipper = indexInfo.flipper;

                                    // recompose the flipper
                                    View loadingView = flipper.getChildAt(0);
                                    loadingView.setVisibility(View.GONE);
                                    flipper.removeAllViews();
                                    flipper.addView(loadingView);
                                    flipper.addView(indexInfo.view.apply(mContext, flipper));

                                    // hide the loader view and bring the new view to the front
                                    flipper.requestLayout();
                                    flipper.invalidate();
                                }
                            }
                        }
                    });
                }
            }
        }

        private RemoteViewsIndexInfo requestCachedIndexInfo(final int position) {
            int indicesToLoadCount = 0;
            int[] indicesToLoad = null;

            synchronized (mViewCache) {
                indicesToLoad = new int[mViewCache.length];
                Arrays.fill(indicesToLoad, 0);

                if (containsAndIsValid(position)) {
                    // return the info if it exists in the window and is loaded
                    return mViewCache[getCacheIndex(position)];
                } 

                // if necessary update the window and load the new information
                int centerPosition = (mViewCacheEndPosition + mViewCacheStartPosition) / 2;
                if ((mViewCacheEndPosition <= mViewCacheStartPosition) || (Math.abs(position - centerPosition) > mCacheSlack)) {
                    int newStartPosition = position - mHalfCacheSize;
                    int newEndPosition = position + mHalfCacheSize;

                    // prune/add before the current start position
                    int effectiveStart = Math.max(newStartPosition, 0);
                    int effectiveEnd = Math.min(newEndPosition, getCount());

                    mWorkerQueue.removeMessages(0);

                    // invalidate items in the queue
                    boolean loadFromBeginning = effectiveStart < mViewCacheStartPosition;
                    int numLoadFromBeginning = mViewCacheStartPosition - effectiveStart;
                    boolean loadFromEnd = effectiveEnd > mViewCacheEndPosition;
                    int overlapStart = Math.max(mViewCacheStartPosition, effectiveStart);
                    int overlapEnd = Math.min(Math.max(mViewCacheStartPosition, mViewCacheEndPosition), effectiveEnd);
                    for (int i = newStartPosition; i < newEndPosition; ++i) {
                        if (loadFromBeginning && (effectiveStart <= i) && (i < overlapStart)) {
                            // load new items at the beginning in reverse order
                            mViewCache[getCacheIndex(i)].invalidate();
                            indicesToLoad[indicesToLoadCount++] = effectiveStart
                                    + (numLoadFromBeginning - (i - effectiveStart) - 1);
                        } else if (loadFromEnd && (overlapEnd <= i) && (i < effectiveEnd)) {
                            mViewCache[getCacheIndex(i)].invalidate();
                            indicesToLoad[indicesToLoadCount++] = i;
                        } else if ((overlapStart <= i) && (i < overlapEnd)) {
                            // load the stuff in the middle that has not already
                            // been loaded
                            if (!mViewCache[getCacheIndex(i)].isValid()) {
                                indicesToLoad[indicesToLoadCount++] = i;
                            }
                        } else {
                            // invalidate all other cache indices (outside the effective start/end)
                            mViewCache[getCacheIndex(i)].invalidate();
                        }
                    }

                    mViewCacheStartPosition = newStartPosition;
                    mViewCacheEndPosition = newEndPosition;
                    mPriorityLoadingWindowStart = position;
                    mPriorityLoadingWindowEnd = position + mPriorityLoadingWindowSize;
                    mLoadUpwards = loadFromBeginning && !loadFromEnd;
                } else if (contains(position)) {
                    // prioritize items around this position so that they load first
                    if (position < mPriorityLoadingWindowStart || position > mPriorityLoadingWindowEnd) {
                        mWorkerQueue.removeMessages(0);

                        int index;
                        int effectiveStart = Math.max(position - mPriorityLoadingWindowSize, 0);
                        int effectiveEnd = 0;
                        synchronized (mViewCacheInfo) {
                            effectiveEnd = Math.min(position + mPriorityLoadingWindowSize - 1,
                                    mViewCacheInfo.count - 1);
                        }

                        for (int i = 0; i < mViewCache.length; ++i) {
                            if (mLoadUpwards) {
                                index = effectiveEnd - i;
                            } else {
                                index = effectiveStart + i;
                            }
                            if (!mViewCache[getCacheIndex(index)].isValid()) {
                                indicesToLoad[indicesToLoadCount++] = index;
                            }
                        }

                        mPriorityLoadingWindowStart = effectiveStart;
                        mPriorityLoadingWindowEnd = position + mPriorityLoadingWindowSize;
                    }
                }
            }

            // post items to be loaded
            int length = 0;
            synchronized (mViewCacheInfo) {
                length = mViewCacheInfo.count;
            }
            for (int i = 0; i < indicesToLoadCount; ++i) {
                final int index = indicesToLoad[i];
                if (0 <= index && index < length) {
                    mWorkerQueue.post(new Runnable() {
                        @Override
                        public void run() {
                            updateRemoteViewsInfo(index);
                        }
                    });
                }
            }

            // return null so that a dummy view can be retrieved
            return null;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            if (mServiceConnection.isConnected()) {
                // create the flipper views if necessary (we have to do this now
                // for all the flippers while we have the reference to the parent)
                createInitialLoadingFlipperViews(parent);

                // request the item from the cache (queueing it to load if not
                // in the cache already)
                RemoteViewsIndexInfo indexInfo = requestCachedIndexInfo(position);

                // update the flipper appropriately
                synchronized (mViewCache) {
                    int cacheIndex = getCacheIndex(position);
                    FrameLayout flipper = mViewCache[cacheIndex].flipper;

                    if (indexInfo == null) {
                        // hide the item view and show the loading view
                        flipper.getChildAt(0).setVisibility(View.VISIBLE);
                        for (int i = 1; i < flipper.getChildCount(); ++i) {
                            flipper.getChildAt(i).setVisibility(View.GONE);
                        }
                        flipper.requestLayout();
                        flipper.invalidate();
                    } else {
                        // hide the loading view and show the item view
                        flipper.setVisibility(View.VISIBLE);
                        for (int i = 0; i < flipper.getChildCount() - 1; ++i) {
                            flipper.getChildAt(i).setVisibility(View.GONE);
                        }
                        flipper.getChildAt(flipper.getChildCount() - 1).setVisibility(View.VISIBLE);
                        flipper.requestLayout();
                        flipper.invalidate();
                    }
                    return flipper;
                }
            }
            return new View(mContext);
        }

        private void createInitialLoadingFlipperViews(ViewGroup parent) {
            // ensure that the cache has the appropriate initial flipper
            synchronized (mViewCache) {
                if (mViewCache[0].flipper == null) {
                    for (int i = 0; i < mViewCache.length; ++i) {
                        FrameLayout flipper = new FrameLayout(mContext);
                        if (mUserLoadingView != null) {
                            // use the user-specified loading view
                            flipper.addView(mUserLoadingView.apply(mContext, parent));
                        } else {
                            // calculate the original size of the first row for the loader view
                            synchronized (mViewCacheInfo) {
                                if (mFirstViewHeight < 0) {
                                    View firstView = mFirstView.apply(mContext, parent);
                                    firstView.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                                    mFirstViewHeight = firstView.getMeasuredHeight();
                                }
                            }

                            // construct a new loader and add it to the flipper as the fallback
                            // default view
                            TextView textView = new TextView(mContext);
                            textView.setText("Loading...");
                            textView.setHeight(mFirstViewHeight);
                            textView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
                            textView.setTextSize(18.0f);
                            textView.setTextColor(Color.argb(96, 255, 255, 255));
                            textView.setShadowLayer(2.0f, 0.0f, 1.0f, Color.BLACK);

                            flipper.addView(textView);
                        }
                        mViewCache[i].flipper = flipper;
                    }
                }
            }
        }

        public long getItemId(int position) {
            synchronized (mViewCache) {
                if (containsAndIsValid(position)) {
                    return mViewCache[getCacheIndex(position)].itemId;
                }
            }
            return 0;
        }

        public int getItemViewType(int position) {
            // synchronize to ensure that the type id/index map is updated synchronously
            synchronized (mViewCache) {
                if (containsAndIsValid(position)) {
                    int viewId = mViewCache[getCacheIndex(position)].typeId;
                    Map<Integer, Integer> typeMap = mViewCacheInfo.mTypeIdIndexMap;
                    // we +1 because the default dummy view get view type 0
                    if (typeMap.containsKey(viewId)) {
                        return typeMap.get(viewId);
                    } else {
                        int newIndex = typeMap.size() + 1;
                        typeMap.put(viewId, newIndex);
                        return newIndex;
                    }
                }
            }
            // return the type of the default item
            return 0;
        }

        public int getCount() {
            synchronized (mViewCacheInfo) {
                return mViewCacheInfo.count;
            }
        }

        public int getViewTypeCount() {
            synchronized (mViewCacheInfo) {
                return mViewCacheInfo.viewTypeCount;
            }
        }

        public boolean hasStableIds() {
            synchronized (mViewCacheInfo) {
                return mViewCacheInfo.hasStableIds;
            }
        }

        public void flushCache() {
            synchronized (mViewCache) {
                // flush the internal cache and invalidate the adapter for future loads
                mWorkerQueue.removeMessages(0);
                mMainQueue.removeMessages(0);

                for (int i = 0; i < mViewCache.length; ++i) {
                    mViewCache[i].invalidate();
                }

                mViewCacheStartPosition = 0;
                mViewCacheEndPosition = -1;
            }
        }
    }

    public RemoteViewsAdapter(Context context, Intent intent, RemoteAdapterConnectionCallback callback) {
        mContext = context;
        mIntent = intent;

        // initialize the worker thread
        mWorkerThread = new HandlerThread("RemoteViewsCache-loader");
        mWorkerThread.start();
        mWorkerQueue = new Handler(mWorkerThread.getLooper());
        mWorkerPriorityQueue = new Handler(mWorkerThread.getLooper());
        mMainQueue = new Handler(Looper.myLooper());
        mMainPriorityQueue = new Handler(Looper.myLooper());

        // initialize the cache and the service connection on startup
        mViewCache = new RemoteViewsCache(25);
        mServiceConnection = new RemoteViewsAdapterServiceConnection(callback);
        requestBindService();
    }

    protected void finalize() throws Throwable {
        // remember to unbind from the service when finalizing
        unbindService();
    }

    public int getCount() {
        requestBindService();
        return mViewCache.getCount();
    }

    public Object getItem(int position) {
        // disallow arbitrary object to be associated with an item for the time being
        return null;
    }

    public long getItemId(int position) {
        requestBindService();
        return mViewCache.getItemId(position);
    }

    public int getItemViewType(int position) {
        requestBindService();
        return mViewCache.getItemViewType(position);
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        requestBindService();
        return mViewCache.getView(position, convertView, parent);
    }

    public int getViewTypeCount() {
        requestBindService();
        return mViewCache.getViewTypeCount();
    }

    public boolean hasStableIds() {
        requestBindService();
        return mViewCache.hasStableIds();
    }

    public boolean isEmpty() {
        return getCount() <= 0;
    }

    public void notifyDataSetChanged() {
        // flush the cache so that we can reload new items from the service
        mViewCache.flushCache();
        super.notifyDataSetChanged();
    }

    private boolean requestBindService() {
        // try binding the service (which will start it if it's not already running)
        if (!mServiceConnection.isConnected()) {
            mContext.bindService(mIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }

        return mServiceConnection.isConnected();
    }

    private void unbindService() {
        if (mServiceConnection.isConnected()) {
            mContext.unbindService(mServiceConnection);
        }
    }
}
