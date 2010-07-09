/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.content.Loader;
import android.content.Loader.OnLoadCompleteListener;
import android.os.Bundle;
import android.util.SparseArray;

/**
 * Object associated with an {@link Activity} or {@link Fragment} for managing
 * one or more {@link android.content.Loader} instances associated with it.
 */
public class LoaderManager {
    /**
     * Callback interface for a client to interact with the manager.
     */
    public interface LoaderCallbacks<D> {
        public Loader<D> onCreateLoader(int id, Bundle args);
        public void onLoadFinished(Loader<D> loader, D data);
    }
    
    final class LoaderInfo implements Loader.OnLoadCompleteListener<Object> {
        public Bundle args;
        public Loader<Object> loader;
        public LoaderManager.LoaderCallbacks<Object> callback;
        
        @Override public void onLoadComplete(Loader<Object> loader, Object data) {
            // Notify of the new data so the app can switch out the old data before
            // we try to destroy it.
            callback.onLoadFinished(loader, data);

            // Look for an inactive loader and destroy it if found
            int id = loader.getId();
            LoaderInfo info = mInactiveLoaders.get(id);
            if (info != null) {
                Loader<Object> oldLoader = info.loader;
                if (oldLoader != null) {
                    oldLoader.destroy();
                }
                mInactiveLoaders.remove(id);
            }
        }
    }

    SparseArray<LoaderInfo> mLoaders = new SparseArray<LoaderInfo>();
    SparseArray<LoaderInfo> mInactiveLoaders = new SparseArray<LoaderInfo>();
    boolean mStarted;
    
    LoaderManager(boolean started) {
        mStarted = started;
    }
    
    /**
     * Associates a loader with this managers, registers the callbacks on it,
     * and starts it loading.  If a loader with the same id has previously been
     * started it will automatically be destroyed when the new loader completes
     * its work. The callback will be delivered before the old loader
     * is destroyed.
     */
    @SuppressWarnings("unchecked")
    public <D> Loader<D> startLoading(int id, Bundle args, LoaderManager.LoaderCallbacks<D> callback) {
        LoaderInfo info = mLoaders.get(id);
        if (info != null) {
            // Keep track of the previous instance of this loader so we can destroy
            // it when the new one completes.
            mInactiveLoaders.put(id, info);
        }
        info = new LoaderInfo();
        info.args = args;
        info.callback = (LoaderManager.LoaderCallbacks<Object>)callback;
        mLoaders.put(id, info);
        Loader<D> loader = callback.onCreateLoader(id, args);
        info.loader = (Loader<Object>)loader;
        if (mStarted) {
            // The activity will start all existing loaders in it's onStart(), so only start them
            // here if we're past that point of the activitiy's life cycle
            loader.registerListener(id, (OnLoadCompleteListener<D>)info);
            loader.startLoading();
        }
        return loader;
        
    }
    
    /**
     * Stops and removes the loader with the given ID.
     */
    public void stopLoading(int id) {
        if (mLoaders != null) {
            int idx = mLoaders.indexOfKey(id);
            if (idx >= 0) {
                LoaderInfo info = mLoaders.valueAt(idx);
                mLoaders.removeAt(idx);
                Loader<Object> loader = info.loader;
                if (loader != null) {
                    loader.unregisterListener(info);
                    loader.destroy();
                }
            }
        }
    }

    /**
     * Return the Loader with the given id or null if no matching Loader
     * is found.
     */
    @SuppressWarnings("unchecked")
    public <D> Loader<D> getLoader(int id) {
        LoaderInfo loaderInfo = mLoaders.get(id);
        if (loaderInfo != null) {
            return (Loader<D>)mLoaders.get(id).loader;
        }
        return null;
    }
 
    void doStart() {
        // Call out to sub classes so they can start their loaders
        // Let the existing loaders know that we want to be notified when a load is complete
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            LoaderInfo info = mLoaders.valueAt(i);
            Loader<Object> loader = info.loader;
            int id = mLoaders.keyAt(i);
            if (loader == null) {
               loader = info.callback.onCreateLoader(id, info.args);
               info.loader = loader;
            }
            loader.registerListener(id, info);
            loader.startLoading();
        }

        mStarted = true;
    }
    
    void doStop() {
        for (int i = mLoaders.size()-1; i >= 0; i--) {
            LoaderInfo info = mLoaders.valueAt(i);
            Loader<Object> loader = info.loader;
            if (loader == null) {
                continue;
            }

            // Let the loader know we're done with it
            loader.unregisterListener(info);

            // The loader isn't getting passed along to the next instance so ask it to stop loading
            //if (!getActivity().isChangingConfigurations()) {
            //    loader.stopLoading();
            //}
        }

        mStarted = false;
    }
    
    void doDestroy() {
        if (mLoaders != null) {
            for (int i = mLoaders.size()-1; i >= 0; i--) {
                LoaderInfo info = mLoaders.valueAt(i);
                Loader<Object> loader = info.loader;
                if (loader == null) {
                    continue;
                }
                loader.destroy();
            }
        }
    }
}
