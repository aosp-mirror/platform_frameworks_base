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

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.util.Config;
import android.util.Log;

import java.util.WeakHashMap;

public final class AttributeCache extends BroadcastReceiver {
    private static AttributeCache sInstance = null;
    
    private final Context mContext;
    private final WeakHashMap<Key, Entry> mMap =
            new WeakHashMap<Key, Entry>();
    private final WeakHashMap<String, Context> mContexts =
            new WeakHashMap<String, Context>();
    
    final static class Key {
        public final String packageName;
        public final int resId;
        public final int[] styleable;
        
        public Key(String inPackageName, int inResId, int[] inStyleable) {
            packageName = inPackageName;
            resId = inResId;
            styleable = inStyleable;
        }
        
        @Override public boolean equals(Object obj) {
            try {
                if (obj != null) {
                    Key other = (Key)obj;
                    return packageName.equals(other.packageName)
                            && resId == other.resId
                            && styleable == other.styleable;
                }
            } catch (ClassCastException e) {
            }
            return false;
        }

        @Override public int hashCode() {
            return packageName.hashCode() + resId;
        }
    }
    
    public final static class Entry {
        public final Context context;
        public final TypedArray array;
        
        public Entry(Context c, TypedArray ta) {
            context = c;
            array = ta;
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
    
    public Entry get(String packageName, int resId, int[] styleable) {
        synchronized (this) {
            Key key = new Key(packageName, resId, styleable);
            Entry ent = mMap.get(key);
            if (ent != null) {
                return ent;
            }
            Context context = mContexts.get(packageName);
            if (context == null) {
                try {
                    context = mContext.createPackageContext(packageName, 0);
                    if (context == null) {
                        return null;
                    }
                    mContexts.put(packageName, context);
                } catch (PackageManager.NameNotFoundException e) {
                    return null;
                }
            }
            try {
                ent = new Entry(context,
                        context.obtainStyledAttributes(resId, styleable));
                mMap.put(key, ent);
            } catch (Resources.NotFoundException e) {
                return null;
            }
            return ent;
        }
    }
    @Override public void onReceive(Context context, Intent intent) {
    }
}

