/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.test.mock;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.database.ContentObserver;
import android.net.Uri;

import com.google.android.collect.Maps;

import java.util.Map;

/**
 * A mock {@link android.content.ContentResolver} class that isolates the test code from the real
 * content system.  All methods are non-functional and throw
 * {@link java.lang.UnsupportedOperationException}.  
 *
 * <p>This only isolates the test code in ways that have proven useful so far. More should be
 * added as they become a problem.
 */
public class MockContentResolver extends ContentResolver {
    Map<String, ContentProvider> mProviders;

    public MockContentResolver() {
        super(null);
        mProviders = Maps.newHashMap();
    }

    public void addProvider(String name, ContentProvider provider) {
        mProviders.put(name, provider);
    }

    /** @hide */
    @Override
    protected IContentProvider acquireProvider(Context context, String name) {
        final ContentProvider provider = mProviders.get(name);
        if (provider != null) {
            return provider.getIContentProvider();
        } else {
            return null;
        }
    }

    /** @hide */
    @Override
    public boolean releaseProvider(IContentProvider provider) {
        return true;
    }

    @Override
    public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
    }
}
