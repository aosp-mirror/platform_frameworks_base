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
 * This test case class provides a framework for testing a single
 * {@link ContentProvider} and for testing your app code with an
 * isolated content provider. Instead of using the system map of
 * providers that is based on the manifests of other applications, the test
 * case creates its own internal map. It then uses this map to resolve providers
 * given an authority. This allows you to inject test providers and to null out
 * providers that you do not want to use.
 * <p>
 *      This test case also sets up the following mock objects:
 * </p>
 * <ul>
 *      <li>
 *          An {@link android.test.IsolatedContext} that stubs out Context methods that might
 *          affect the rest of the running system, while allowing tests to do real file and
 *          database work.
 *      </li>
 *      <li>
 *          A {@link android.test.mock.MockContentResolver} that provides the functionality of a
 *          regular content resolver, but uses {@link IsolatedContext}. It stubs out
 *          {@link ContentResolver#notifyChange(Uri, ContentObserver, boolean)} to
 *          prevent the test from affecting the running system.
 *      </li>
 *      <li>
 *          An instance of the provider under test, running in an {@link IsolatedContext}.
 *      </li>
 * </ul>
 * <p>
 *      This framework is set up automatically by the base class' {@link #setUp()} method. If you
 *      override this method, you must call the super method as the first statement in
 *      your override.
 * </p>
 * <p>
 *     In order for their tests to be run, concrete subclasses must provide their own
 *     constructor with no arguments. This constructor must call
 *     {@link #ProviderTestCase2(Class, String)} as  its first operation.
 * </p>
 * For more information on content provider testing, please see
 * <a href="{@docRoot}tools/testing/contentprovider_testing.html">Content Provider Testing</a>.
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
            // name the directory so the directory will be separated from
            // one created through the regular Context
            return getContext().getDir("mockcontext2_" + name, mode);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }
    /**
     * Constructor.
     *
     * @param providerClass The class name of the provider under test
     * @param providerAuthority The provider's authority string
     */
    public ProviderTestCase2(Class<T> providerClass, String providerAuthority) {
        mProviderClass = providerClass;
        mProviderAuthority = providerAuthority;
    }

    private T mProvider;

    /**
     * Returns the content provider created by this class in the {@link #setUp()} method.
     * @return T An instance of the provider class given as a parameter to the test case class.
     */
    public T getProvider() {
        return mProvider;
    }

    /**
     * Sets up the environment for the test fixture.
     * <p>
     * Creates a new
     * {@link android.test.mock.MockContentResolver}, a new IsolatedContext
     * that isolates the provider's file operations, and a new instance of
     * the provider under test within the isolated environment.
     * </p>
     *
     * @throws Exception
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mResolver = new MockContentResolver();
        final String filenamePrefix = "test.";
        RenamingDelegatingContext targetContextWrapper = new
                RenamingDelegatingContext(
                new MockContext2(), // The context that most methods are
                                    //delegated to
                getContext(), // The context that file methods are delegated to
                filenamePrefix);
        mProviderContext = new IsolatedContext(mResolver, targetContextWrapper);

        mProvider = mProviderClass.newInstance();
        mProvider.attachInfoForTesting(mProviderContext, null);
        assertNotNull(mProvider);
        mResolver.addProvider(mProviderAuthority, getProvider());
    }

    /**
     * Tears down the environment for the test fixture.
     * <p>
     * Calls {@link android.content.ContentProvider#shutdown()} on the
     * {@link android.content.ContentProvider} represented by mProvider.
     */
    @Override
    protected void tearDown() throws Exception {
        mProvider.shutdown();
        super.tearDown();
    }

    /**
     * Gets the {@link MockContentResolver} created by this class during initialization. You
     * must use the methods of this resolver to access the provider under test.
     *
     * @return A {@link MockContentResolver} instance.
     */
    public MockContentResolver getMockContentResolver() {
        return mResolver;
    }

    /**
     * Gets the {@link IsolatedContext} created by this class during initialization.
     * @return The {@link IsolatedContext} instance
     */
    public IsolatedContext getMockContext() {
        return mProviderContext;
    }

    /**
     * <p>
     *      Creates a new content provider of the same type as that passed to the test case class,
     *      with an authority name set to the authority parameter, and using an SQLite database as
     *      the underlying data source. The SQL statement parameter is used to create the database.
     *      This method also creates a new {@link MockContentResolver} and adds the provider to it.
     * </p>
     * <p>
     *      Both the new provider and the new resolver are put into an {@link IsolatedContext}
     *      that uses the targetContext parameter for file operations and a {@link MockContext}
     *      for everything else. The IsolatedContext prepends the filenamePrefix parameter to
     *      file, database, and directory names.
     * </p>
     * <p>
     *      This is a convenience method for creating a "mock" provider that can contain test data.
     * </p>
     *
     * @param targetContext The context to use as the basis of the IsolatedContext
     * @param filenamePrefix A string that is prepended to file, database, and directory names
     * @param providerClass The type of the provider being tested
     * @param authority The authority string to associated with the test provider
     * @param databaseName The name assigned to the database
     * @param databaseVersion The version assigned to the database
     * @param sql A string containing the SQL statements that are needed to create the desired
     * database and its tables. The format is the same as that generated by the
     * <a href="http://www.sqlite.org/sqlite.html">sqlite3</a> tool's <code>.dump</code> command.
     * @return ContentResolver A new {@link MockContentResolver} linked to the provider
     *
     * @throws IllegalAccessException
     * @throws InstantiationException
     */
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
        provider.attachInfoForTesting(context, null);
        resolver.addProvider(authority, provider);

        return resolver;
    }
}
