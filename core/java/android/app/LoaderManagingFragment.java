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
import android.os.Bundle;

import java.util.HashMap;

/**
 * A Fragment that has utility methods for managing {@link Loader}s.
 *
 * @param <D> The type of data returned by the Loader. If you're using multiple Loaders with
 * different return types use Object and case the results.
 */
public abstract class LoaderManagingFragment<D> extends Fragment
        implements Loader.OnLoadCompleteListener<D> {
    private boolean mStarted = false;

    static final class LoaderInfo<D> {
        public Bundle args;
        public Loader<D> loader;
    }
    private HashMap<Integer, LoaderInfo<D>> mLoaders;
    private HashMap<Integer, LoaderInfo<D>> mInactiveLoaders;

    /**
     * Registers a loader with this activity, registers the callbacks on it, and starts it loading.
     * If a loader with the same id has previously been started it will automatically be destroyed
     * when the new loader completes it's work. The callback will be delivered before the old loader
     * is destroyed.
     */
    public Loader<D> startLoading(int id, Bundle args) {
        LoaderInfo<D> info = mLoaders.get(id);
        if (info != null) {
            // Keep track of the previous instance of this loader so we can destroy
            // it when the new one completes.
            mInactiveLoaders.put(id, info);
        }
        info = new LoaderInfo<D>();
        info.args = args;
        mLoaders.put(id, info);
        Loader<D> loader = onCreateLoader(id, args);
        info.loader = loader;
        if (mStarted) {
            // The activity will start all existing loaders in it's onStart(), so only start them
            // here if we're past that point of the activitiy's life cycle
            loader.registerListener(id, this);
            loader.startLoading();
        }
        return loader;
    }

    protected abstract Loader<D> onCreateLoader(int id, Bundle args);
    protected abstract void onInitializeLoaders();
    protected abstract void onLoadFinished(Loader<D> loader, D data);

    public final void onLoadComplete(Loader<D> loader, D data) {
        // Notify of the new data so the app can switch out the old data before
        // we try to destroy it.
        onLoadFinished(loader, data);

        // Look for an inactive loader and destroy it if found
        int id = loader.getId();
        LoaderInfo<D> info = mInactiveLoaders.get(id);
        if (info != null) {
            Loader<D> oldLoader = info.loader;
            if (oldLoader != null) {
                oldLoader.destroy();
            }
            mInactiveLoaders.remove(id);
        }
    }

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        if (mLoaders == null) {
            // Look for a passed along loader and create a new one if it's not there
// TODO: uncomment once getLastNonConfigurationInstance method is available
//            mLoaders = (HashMap<Integer, LoaderInfo>) getLastNonConfigurationInstance();
            if (mLoaders == null) {
                mLoaders = new HashMap<Integer, LoaderInfo<D>>();
                onInitializeLoaders();
            }
        }
        if (mInactiveLoaders == null) {
            mInactiveLoaders = new HashMap<Integer, LoaderInfo<D>>();
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        // Call out to sub classes so they can start their loaders
        // Let the existing loaders know that we want to be notified when a load is complete
        for (HashMap.Entry<Integer, LoaderInfo<D>> entry : mLoaders.entrySet()) {
            LoaderInfo<D> info = entry.getValue();
            Loader<D> loader = info.loader;
            int id = entry.getKey();
            if (loader == null) {
               loader = onCreateLoader(id, info.args);
               info.loader = loader;
            }
            loader.registerListener(id, this);
            loader.startLoading();
        }

        mStarted = true;
    }

    @Override
    public void onStop() {
        super.onStop();

        for (HashMap.Entry<Integer, LoaderInfo<D>> entry : mLoaders.entrySet()) {
            LoaderInfo<D> info = entry.getValue();
            Loader<D> loader = info.loader;
            if (loader == null) {
                continue;
            }

            // Let the loader know we're done with it
            loader.unregisterListener(this);

            // The loader isn't getting passed along to the next instance so ask it to stop loading
            if (!getActivity().isChangingConfigurations()) {
                loader.stopLoading();
            }
        }

        mStarted = false;
    }

    /** TO DO: This needs to be turned into a retained fragment.
    @Override
    public Object onRetainNonConfigurationInstance() {
        // Pass the loader along to the next guy
        Object result = mLoaders;
        mLoaders = null;
        return result;
    }
    **/

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mLoaders != null) {
            for (HashMap.Entry<Integer, LoaderInfo<D>> entry : mLoaders.entrySet()) {
                LoaderInfo<D> info = entry.getValue();
                Loader<D> loader = info.loader;
                if (loader == null) {
                    continue;
                }
                loader.destroy();
            }
        }
    }

    /**
     * Stops and removes the loader with the given ID.
     */
    public void stopLoading(int id) {
        if (mLoaders != null) {
            LoaderInfo<D> info = mLoaders.remove(id);
            if (info != null) {
                Loader<D> loader = info.loader;
                if (loader != null) {
                    loader.unregisterListener(this);
                    loader.destroy();
                }
            }
        }
    }

    /**
     * @return the Loader with the given id or null if no matching Loader
     * is found.
     */
    public Loader<D> getLoader(int id) {
        LoaderInfo<D> loaderInfo = mLoaders.get(id);
        if (loaderInfo != null) {
            return mLoaders.get(id).loader;
        }
        return null;
    }
}
