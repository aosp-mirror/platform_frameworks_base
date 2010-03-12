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

package android.test;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.test.mock.MockContext;
import android.test.mock.MockContentResolver;
import android.database.DatabaseUtils;

import java.io.File;

/**
 * This TestCase class provides a framework for isolated testing of a single
 * ContentProvider.  It uses a {@link android.test.mock.MockContentResolver} to
 * access the provider, restricts the provider to an isolated area of the
 * filesystem (for safely creating & modifying databases & files), and injects
 * {@link android.test.IsolatedContext} to isolate the ContentProvider from the
 * rest of the running system.
 *
 * <p>This environment is created automatically by {@link #setUp} and {@link
 * #tearDown}.
 */
public abstract class ProviderTestCase2<T extends ContentProvider> extends AndroidTestCase {

    Class<T> mProviderClass;
    String mProviderAuthority;

    private IsolatedContext mProviderContext;
    private MockContentResolver mResolver;

       private class MockContext2 extends MockContext {

        @Override
        public Resources getResources() {
            return getContext().getResources();
        }

        @Override
        public File getDir(String name, int mode) {
            // name the directory so the directory will be seperated from
            // one created through the regular Context
            return getContext().getDir("mockcontext2_" + name, mode);
        }
    }

    public ProviderTestCase2(Class<T> providerClass, String providerAuthority) {
        mProviderClass = providerClass;
        mProviderAuthority = providerAuthority;
    }

    /**
     * The content provider that will be set up for use in each test method.
     */
    private T mProvider;

    public T getProvider() {
        return mProvider;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mResolver = new MockContentResolver();
        final String filenamePrefix = "test.";
        RenamingDelegatingContext targetContextWrapper = new RenamingDelegatingContext(
                new MockContext2(), // The context that most methods are delegated to
                getContext(), // The context that file methods are delegated to
                filenamePrefix);
        mProviderContext = new IsolatedContext(mResolver, targetContextWrapper);

        mProvider = mProviderClass.newInstance();
        mProvider.attachInfo(mProviderContext, null);
        assertNotNull(mProvider);
        mResolver.addProvider(mProviderAuthority, getProvider());
    }

    public MockContentResolver getMockContentResolver() {
        return mResolver;
    }

    public IsolatedContext getMockContext() {
        return mProviderContext;
    }

    public static <T extends ContentProvider> ContentResolver newResolverWithContentProviderFromSql(
            Context targetContext, String filenamePrefix, Class<T> providerClass, String authority,
            String databaseName, int databaseVersion, String sql)
            throws IllegalAccessException, InstantiationException {
        MockContentResolver resolver = new MockContentResolver();
        RenamingDelegatingContext targetContextWrapper = new RenamingDelegatingContext(
                new MockContext(), // The context that most methods are delegated to
                targetContext, // The context that file methods are delegated to
                filenamePrefix);
        Context context = new IsolatedContext(resolver, targetContextWrapper);
        DatabaseUtils.createDbFromSqlStatements(context, databaseName, databaseVersion, sql);

        T provider = providerClass.newInstance();
        provider.attachInfo(context, null);
        resolver.addProvider(authority, provider);

        return resolver;
    }
}
