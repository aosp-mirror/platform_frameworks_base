/*
* Copyright (C) 2014 The Android Open Source Project
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
package android.content.res;

import android.util.ArrayMap;
import android.util.LongSparseArray;
import java.lang.ref.WeakReference;

/**
 * A Cache class which can be used to cache resource objects that are easy to clone but more
 * expensive to inflate.
 * @hide
 */
public class ConfigurationBoundResourceCache<T> {

    private final ArrayMap<String, LongSparseArray<WeakReference<ConstantState<T>>>> mCache =
            new ArrayMap<String, LongSparseArray<WeakReference<ConstantState<T>>>>();

    final Resources mResources;

    /**
     * Creates a Resource cache for the given Resources instance.
     *
     * @param resources The Resource which can be used when creating new instances.
     */
    public ConfigurationBoundResourceCache(Resources resources) {
        mResources = resources;
    }

    /**
     * Adds a new item to the cache.
     *
     * @param key A custom key that uniquely identifies the resource.
     * @param theme The Theme instance where this resource was loaded.
     * @param constantState The constant state that can create new instances of the resource.
     *
     */
    public void put(long key, Resources.Theme theme, ConstantState<T> constantState) {
        if (constantState == null) {
            return;
        }
        final String themeKey = theme == null ? "" : theme.getKey();
        LongSparseArray<WeakReference<ConstantState<T>>> themedCache;
        synchronized (this) {
            themedCache = mCache.get(themeKey);
            if (themedCache == null) {
                themedCache = new LongSparseArray<WeakReference<ConstantState<T>>>(1);
                mCache.put(themeKey, themedCache);
            }
            themedCache.put(key, new WeakReference<ConstantState<T>>(constantState));
        }
    }

    /**
     * If the resource is cached, creates a new instance of it and returns.
     *
     * @param key The long key which can be used to uniquely identify the resource.
     * @param theme The The Theme instance where we want to load this resource.
     *
     * @return If this resources was loaded before, returns a new instance of it. Otherwise, returns
     *         null.
     */
    public T get(long key, Resources.Theme theme) {
        final String themeKey = theme != null ? theme.getKey() : "";
        final LongSparseArray<WeakReference<ConstantState<T>>> themedCache;
        final WeakReference<ConstantState<T>> wr;
        synchronized (this) {
            themedCache = mCache.get(themeKey);
            if (themedCache == null) {
                return null;
            }
            wr = themedCache.get(key);
        }
        if (wr == null) {
            return null;
        }
        final ConstantState entry = wr.get();
        if (entry != null) {
            return  (T) entry.newInstance(mResources, theme);
        } else {  // our entry has been purged
            synchronized (this) {
                // there is a potential race condition here where this entry may be put in
                // another thread. But we prefer it to minimize lock duration
                themedCache.delete(key);
            }
        }
        return null;
    }

    /**
     * Users of ConfigurationBoundResourceCache must call this method whenever a configuration
     * change happens. On this callback, the cache invalidates all resources that are not valid
     * anymore.
     *
     * @param configChanges The configuration changes
     */
    public void onConfigurationChange(final int configChanges) {
        synchronized (this) {
            final int size = mCache.size();
            for (int i = size - 1; i >= 0; i--) {
                final LongSparseArray<WeakReference<ConstantState<T>>>
                        themeCache = mCache.valueAt(i);
                onConfigurationChangeInt(themeCache, configChanges);
                if (themeCache.size() == 0) {
                    mCache.removeAt(i);
                }
            }
        }
    }

    private void onConfigurationChangeInt(
            final LongSparseArray<WeakReference<ConstantState<T>>> themeCache,
            final int configChanges) {
        final int size = themeCache.size();
        for (int i = size - 1; i >= 0; i--) {
            final WeakReference<ConstantState<T>> wr = themeCache.valueAt(i);
            final ConstantState<T> constantState = wr.get();
            if (constantState == null || Configuration.needNewResources(
                    configChanges, constantState.getChangingConfigurations())) {
                themeCache.removeAt(i);
            }
        }
    }

}
