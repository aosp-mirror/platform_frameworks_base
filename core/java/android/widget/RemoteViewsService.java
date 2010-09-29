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

import java.util.HashMap;
import java.util.Map;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Pair;

import com.android.internal.widget.IRemoteViewsFactory;

/**
 * The service to be connected to for a remote adapter to request RemoteViews.  Users should
 * extend the RemoteViewsService to provide the appropriate RemoteViewsFactory's used to
 * populate the remote collection view (ListView, GridView, etc).
 */
public abstract class RemoteViewsService extends Service {

    private static final String LOG_TAG = "RemoteViewsService";

    // multimap implementation for reference counting
    private HashMap<Intent.FilterComparison, Pair<RemoteViewsFactory, Integer>> mRemoteViewFactories;
    private final Object mLock = new Object();

    /**
     * An interface for an adapter between a remote collection view (ListView, GridView, etc) and
     * the underlying data for that view.  The implementor is responsible for making a RemoteView
     * for each item in the data set.
     * 
     * @see android.widget.Adapter
     * @see android.appwidget.AppWidgetManager
     */
    public interface RemoteViewsFactory {
        /**
         * Called when your factory is first constructed. The same factory may be shared across
         * multiple RemoteViewAdapters depending on the intent passed.
         */
        public void onCreate();
        /**
         * Called when notifyDataSetChanged() is triggered on the remote adapter. This allows a
         * RemoteViewsFactory to respond to data changes by updating any internal references.
         *
         * @see android.appwidget.AppWidgetManager#notifyAppWidgetViewDataChanged(int[], int)
         */
        public void onDataSetChanged();
        /**
         * Called when the last RemoteViewsAdapter that is associated with this factory is
         * unbound.
         */
        public void onDestroy();

        public int getCount();
        public RemoteViews getViewAt(int position);
        public RemoteViews getLoadingView();
        public int getViewTypeCount();
        public long getItemId(int position);
        public boolean hasStableIds();
    }

    /**
     * A private proxy class for the private IRemoteViewsFactory interface through the
     * public RemoteViewsFactory interface.
     */
    private class RemoteViewsFactoryAdapter extends IRemoteViewsFactory.Stub {
        public RemoteViewsFactoryAdapter(RemoteViewsFactory factory) {
            mFactory = factory;
        }
        public synchronized void onDataSetChanged() {
            mFactory.onDataSetChanged();
        }
        public synchronized int getCount() {
            return mFactory.getCount();
        }
        public synchronized RemoteViews getViewAt(int position) {
            RemoteViews rv = mFactory.getViewAt(position);
            rv.setIsWidgetCollectionChild(true);
            return rv;
        }
        public synchronized RemoteViews getLoadingView() {
            return mFactory.getLoadingView();
        }
        public synchronized int getViewTypeCount() {
            return mFactory.getViewTypeCount();
        }
        public synchronized long getItemId(int position) {
            return mFactory.getItemId(position);
        }
        public synchronized boolean hasStableIds() {
            return mFactory.hasStableIds();
        }

        private RemoteViewsFactory mFactory;
    }

    public RemoteViewsService() {
        mRemoteViewFactories =
                new HashMap<Intent.FilterComparison, Pair<RemoteViewsFactory, Integer>>();
    }

    @Override
    public IBinder onBind(Intent intent) {
        synchronized (mLock) {
            // increment the reference count to the particular factory associated with this intent
            Intent.FilterComparison fc = new Intent.FilterComparison(intent);
            Pair<RemoteViewsFactory, Integer> factoryRef = null;
            RemoteViewsFactory factory = null;
            if (!mRemoteViewFactories.containsKey(fc)) {
                factory = onGetViewFactory(intent);
                factoryRef = new Pair<RemoteViewsFactory, Integer>(factory, 1);
                mRemoteViewFactories.put(fc, factoryRef);
                factory.onCreate();
            } else {
                Pair<RemoteViewsFactory, Integer> oldFactoryRef = mRemoteViewFactories.get(fc);
                factory = oldFactoryRef.first;
                int newRefCount = oldFactoryRef.second.intValue() + 1;
                factoryRef = new Pair<RemoteViewsFactory, Integer>(oldFactoryRef.first, newRefCount);
                mRemoteViewFactories.put(fc, factoryRef);
            }
            return new RemoteViewsFactoryAdapter(factory);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        synchronized (mLock) {
            Intent.FilterComparison fc = new Intent.FilterComparison(intent);
            if (mRemoteViewFactories.containsKey(fc)) {
                // this alleviates the user's responsibility of having to clear all factories
                Pair<RemoteViewsFactory, Integer> oldFactoryRef =
                        mRemoteViewFactories.get(fc);
                int newRefCount = oldFactoryRef.second.intValue() - 1;
                if (newRefCount <= 0) {
                    oldFactoryRef.first.onDestroy();
                    mRemoteViewFactories.remove(fc);
                } else {
                    Pair<RemoteViewsFactory, Integer> factoryRef =
                            new Pair<RemoteViewsFactory, Integer>(oldFactoryRef.first, newRefCount);
                    mRemoteViewFactories.put(fc, factoryRef);
                }
            }
        }
        return super.onUnbind(intent);
    }

    /**
     * To be implemented by the derived service to generate appropriate factories for
     * the data.
     */
    public abstract RemoteViewsFactory onGetViewFactory(Intent intent);
}
