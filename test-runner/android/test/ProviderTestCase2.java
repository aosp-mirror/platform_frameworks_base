package android.test;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.test.mock.MockContext;
import android.test.mock.MockContentResolver;
import android.database.DatabaseUtils;

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
                new MockContext(), // The context that most methods are delegated to
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
