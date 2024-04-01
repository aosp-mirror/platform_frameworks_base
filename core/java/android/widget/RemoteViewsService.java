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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;

import com.android.internal.widget.IRemoteViewsFactory;

import java.util.HashMap;

/**
 * The service to be connected to for a remote adapter to request RemoteViews.  Users should
 * extend the RemoteViewsService to provide the appropriate RemoteViewsFactory's used to
 * populate the remote collection view (ListView, GridView, etc).
 */
public abstract class RemoteViewsService extends Service {

    private static final String LOG_TAG = "RemoteViewsService";

    // Used for reference counting of RemoteViewsFactories
    // Because we are now unbinding when we are not using the Service (to allow them to be
    // reclaimed), the references to the factories that are created need to be stored and used when
    // the service is restarted (in response to user input for example).  When the process is
    // destroyed, so is this static cache of RemoteViewsFactories.
    private static final HashMap<Intent.FilterComparison, RemoteViewsFactory> sRemoteViewFactories =
            new HashMap<Intent.FilterComparison, RemoteViewsFactory>();
    private static final Object sLock = new Object();

    /**
     * An interface for an adapter between a remote collection view (ListView, GridView, etc) and
     * the underlying data for that view.  The implementor is responsible for making a RemoteView
     * for each item in the data set. This interface is a thin wrapper around {@link Adapter}.
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
         * Note: expensive tasks can be safely performed synchronously within this method. In the
         * interim, the old data will be displayed within the widget.
         *
         * @see android.appwidget.AppWidgetManager#notifyAppWidgetViewDataChanged(int[], int)
         */
        public void onDataSetChanged();

        /**
         * Called when the last RemoteViewsAdapter that is associated with this factory is
         * unbound.
         */
        public void onDestroy();

        /**
         * See {@link Adapter#getCount()}
         *
         * @return Count of items.
         */
        public int getCount();

        /**
         * See {@link Adapter#getView(int, android.view.View, android.view.ViewGroup)}.
         *
         * Note: expensive tasks can be safely performed synchronously within this method, and a
         * loading view will be displayed in the interim. See {@link #getLoadingView()}.
         *
         * @param position The position of the item within the Factory's data set of the item whose
         *        view we want.
         * @return A RemoteViews object corresponding to the data at the specified position.
         */
        public RemoteViews getViewAt(int position);

        /**
         * This allows for the use of a custom loading view which appears between the time that
         * {@link #getViewAt(int)} is called and returns. If null is returned, a default loading
         * view will be used.
         *
         * @return The RemoteViews representing the desired loading view.
         */
        public RemoteViews getLoadingView();

        /**
         * See {@link Adapter#getViewTypeCount()}.
         *
         * @return The number of types of Views that will be returned by this factory.
         */
        public int getViewTypeCount();

        /**
         * See {@link Adapter#getItemId(int)}.
         *
         * @param position The position of the item within the data set whose row id we want.
         * @return The id of the item at the specified position.
         */
        public long getItemId(int position);

        /**
         * See {@link Adapter#hasStableIds()}.
         *
         * @return True if the same id always refers to the same object.
         */
        public boolean hasStableIds();

        /**
         * @hide
         */
        default RemoteViews.RemoteCollectionItems getRemoteCollectionItems(int capSize) {
            RemoteViews.RemoteCollectionItems items = new RemoteViews.RemoteCollectionItems
                    .Builder().build();
            Parcel capSizeTestParcel = Parcel.obtain();
            // restore allowSquashing to reduce the noise in error messages
            boolean prevAllowSquashing = capSizeTestParcel.allowSquashing();

            try {
                RemoteViews.RemoteCollectionItems.Builder itemsBuilder =
                        new RemoteViews.RemoteCollectionItems.Builder();
                onDataSetChanged();

                itemsBuilder.setHasStableIds(hasStableIds());
                final int numOfEntries = getCount();

                for (int i = 0; i < numOfEntries; i++) {
                    final long currentItemId = getItemId(i);
                    final RemoteViews currentView = getViewAt(i);
                    currentView.writeToParcel(capSizeTestParcel, 0);
                    if (capSizeTestParcel.dataSize() > capSize) {
                        break;
                    }
                    itemsBuilder.addItem(currentItemId, currentView);
                }

                items = itemsBuilder.build();
            } finally {
                capSizeTestParcel.restoreAllowSquashing(prevAllowSquashing);
                // Recycle the parcel
                capSizeTestParcel.recycle();
            }
            return items;
        }
    }

    /**
     * A private proxy class for the private IRemoteViewsFactory interface through the
     * public RemoteViewsFactory interface.
     */
    private static class RemoteViewsFactoryAdapter extends IRemoteViewsFactory.Stub {
        public RemoteViewsFactoryAdapter(RemoteViewsFactory factory, boolean isCreated) {
            mFactory = factory;
            mIsCreated = isCreated;
        }
        public synchronized boolean isCreated() {
            return mIsCreated;
        }
        public synchronized void onDataSetChanged() {
            try {
                mFactory.onDataSetChanged();
            } catch (Exception ex) {
                Thread t = Thread.currentThread();
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
            }
        }
        public synchronized void onDataSetChangedAsync() {
            onDataSetChanged();
        }
        public synchronized int getCount() {
            int count = 0;
            try {
                count = mFactory.getCount();
            } catch (Exception ex) {
                Thread t = Thread.currentThread();
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
            }
            return count;
        }
        public synchronized RemoteViews getViewAt(int position) {
            RemoteViews rv = null;
            try {
                rv = mFactory.getViewAt(position);
                if (rv != null) {
                    rv.addFlags(RemoteViews.FLAG_WIDGET_IS_COLLECTION_CHILD);
                }
            } catch (Exception ex) {
                Thread t = Thread.currentThread();
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
            }
            return rv;
        }
        public synchronized RemoteViews getLoadingView() {
            RemoteViews rv = null;
            try {
                rv = mFactory.getLoadingView();
            } catch (Exception ex) {
                Thread t = Thread.currentThread();
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
            }
            return rv;
        }
        public synchronized int getViewTypeCount() {
            int count = 0;
            try {
                count = mFactory.getViewTypeCount();
            } catch (Exception ex) {
                Thread t = Thread.currentThread();
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
            }
            return count;
        }
        public synchronized long getItemId(int position) {
            long id = 0;
            try {
                id = mFactory.getItemId(position);
            } catch (Exception ex) {
                Thread t = Thread.currentThread();
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
            }
            return id;
        }
        public synchronized boolean hasStableIds() {
            boolean hasStableIds = false;
            try {
                hasStableIds = mFactory.hasStableIds();
            } catch (Exception ex) {
                Thread t = Thread.currentThread();
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
            }
            return hasStableIds;
        }
        public void onDestroy(Intent intent) {
            synchronized (sLock) {
                Intent.FilterComparison fc = new Intent.FilterComparison(intent);
                if (RemoteViewsService.sRemoteViewFactories.containsKey(fc)) {
                    RemoteViewsFactory factory = RemoteViewsService.sRemoteViewFactories.get(fc);
                    try {
                        factory.onDestroy();
                    } catch (Exception ex) {
                        Thread t = Thread.currentThread();
                        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
                    }
                    RemoteViewsService.sRemoteViewFactories.remove(fc);
                }
            }
        }

        @Override
        public RemoteViews.RemoteCollectionItems getRemoteCollectionItems(int capSize) {
            RemoteViews.RemoteCollectionItems items = new RemoteViews.RemoteCollectionItems
                    .Builder().build();
            try {
                items = mFactory.getRemoteCollectionItems(capSize);
            } catch (Exception ex) {
                Thread t = Thread.currentThread();
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, ex);
            }
            return items;
        }

        private RemoteViewsFactory mFactory;
        private boolean mIsCreated;
    }

    @Override
    public IBinder onBind(Intent intent) {
        synchronized (sLock) {
            Intent.FilterComparison fc = new Intent.FilterComparison(intent);
            RemoteViewsFactory factory = null;
            boolean isCreated = false;
            if (!sRemoteViewFactories.containsKey(fc)) {
                factory = onGetViewFactory(intent);
                sRemoteViewFactories.put(fc, factory);
                factory.onCreate();
                isCreated = false;
            } else {
                factory = sRemoteViewFactories.get(fc);
                isCreated = true;
            }
            return new RemoteViewsFactoryAdapter(factory, isCreated);
        }
    }

    /**
     * To be implemented by the derived service to generate appropriate factories for
     * the data.
     */
    public abstract RemoteViewsFactory onGetViewFactory(Intent intent);
}
