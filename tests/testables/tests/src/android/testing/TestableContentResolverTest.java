package android.testing;

import android.content.ContentProvider;
import android.content.IContentProvider;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class TestableContentResolverTest {

    @Rule
    public TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());
    private TestableContentResolver mContentResolver;

    @Before
    public void setup() {
        mContentResolver = new TestableContentResolver(mContext);
        mContentResolver.setFallbackToExisting(false);
    }

    @Test
    public void testDefaultContentProvider() {
        ContentProvider provider = Mockito.mock(ContentProvider.class);
        IContentProvider iprovider = Mockito.mock(IContentProvider.class);
        Mockito.when(provider.getIContentProvider()).thenReturn(iprovider);
        mContentResolver.addProvider("test", provider);

        Assert.assertEquals(iprovider, mContentResolver.acquireProvider(mContext, "test"));
        Assert.assertEquals(iprovider, mContentResolver.acquireUnstableProvider(mContext, "test"));
    }

    @Test
    public void testStableContentProvider() {
        ContentProvider provider = Mockito.mock(ContentProvider.class);
        IContentProvider iprovider = Mockito.mock(IContentProvider.class);
        Mockito.when(provider.getIContentProvider()).thenReturn(iprovider);
        mContentResolver.addProvider("test", provider, TestableContentResolver.STABLE);

        Assert.assertEquals(iprovider, mContentResolver.acquireProvider(mContext, "test"));
        Assert.assertNull(mContentResolver.acquireUnstableProvider(mContext, "test"));
    }

    @Test
    public void testUnstableContentProvider() {
        ContentProvider provider = Mockito.mock(ContentProvider.class);
        IContentProvider iprovider = Mockito.mock(IContentProvider.class);
        Mockito.when(provider.getIContentProvider()).thenReturn(iprovider);
        mContentResolver.addProvider("test", provider, TestableContentResolver.UNSTABLE);

        Assert.assertEquals(iprovider, mContentResolver.acquireUnstableProvider(mContext, "test"));
        Assert.assertNull(mContentResolver.acquireProvider(mContext, "test"));
    }
}
