/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package com.android.server;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.LruCache;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

/**
 * TODO: This should be better integrated into the system so it doesn't need
 * special calls from the activity manager to clear it.
 */
public final class AttributeCache {
    private static final int CACHE_SIZE = 4;
    private static AttributeCache sInstance = null;

    private final Context mContext;

    @GuardedBy("this")
    private final LruCache<String, Package> mPackages = new LruCache<>(CACHE_SIZE);

    @GuardedBy("this")
    private final Configuration mConfiguration = new Configuration();

    public final static class Package {
        public final Context context;
        private final SparseArray<ArrayMap<int[], Entry>> mMap = new SparseArray<>();

        public Package(Context c) {
            context = c;
        }
    }
    
    public final static class Entry {
        public final Context context;
        public final TypedArray array;
        
        public Entry(Context c, TypedArray ta) {
            context = c;
            array = ta;
        }

        void recycle() {
            if (array != null) {
                array.recycle();
            }
        }
    }
    
    public static void init(Context context) {
        if (sInstance == null) {
            sInstance = new AttributeCache(context);
        }
    }
    
    public static AttributeCache instance() {
        return sInstance;
    }
    
    public AttributeCache(Context context) {
        mContext = context;
    }

    public void removePackage(String packageName) {
        synchronized (this) {
            final Package pkg = mPackages.remove(packageName);
            if (pkg != null) {
                for (int i = 0; i < pkg.mMap.size(); i++) {
                    final ArrayMap<int[], Entry> map = pkg.mMap.valueAt(i);
                    for (int j = 0; j < map.size(); j++) {
                        map.valueAt(j).recycle();
                    }
                }

                final Resources res = pkg.context.getResources();
                res.flushLayoutCache();
            }
        }
    }

    public void updateConfiguration(Configuration config) {
        synchronized (this) {
            int changes = mConfiguration.updateFrom(config);
            if ((changes & ~(ActivityInfo.CONFIG_FONT_SCALE |
                    ActivityInfo.CONFIG_KEYBOARD_HIDDEN |
                    ActivityInfo.CONFIG_ORIENTATION)) != 0) {
                // The configurations being masked out are ones that commonly
                // change so we don't want flushing the cache... all others
                // will flush the cache.
                mPackages.evictAll();
            }
        }
    }
    
    public Entry get(String packageName, int resId, int[] styleable, int userId) {
        synchronized (this) {
            Package pkg = mPackages.get(packageName);
            ArrayMap<int[], Entry> map = null;
            Entry ent = null;
            if (pkg != null) {
                map = pkg.mMap.get(resId);
                if (map != null) {
                    ent = map.get(styleable);
                    if (ent != null) {
                        return ent;
                    }
                }
            } else {
                Context context;
                try {
                    context = mContext.createPackageContextAsUser(packageName, 0,
                            new UserHandle(userId));
                    if (context == null) {
                        return null;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    return null;
                }
                pkg = new Package(context);
                mPackages.put(packageName, pkg);
            }
            
            if (map == null) {
                map = new ArrayMap<>();
                pkg.mMap.put(resId, map);
            }
            
            try {
                ent = new Entry(pkg.context,
                        pkg.context.obtainStyledAttributes(resId, styleable));
                map.put(styleable, ent);
            } catch (Resources.NotFoundException e) {
                return null;
            }
            
            return ent;
        }
    }
}

