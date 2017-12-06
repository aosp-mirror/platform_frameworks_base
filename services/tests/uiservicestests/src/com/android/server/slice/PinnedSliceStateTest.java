package com.android.server.slice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.slice.ISliceListener;
import android.app.slice.Slice;
import android.app.slice.SliceProvider;
import android.app.slice.SliceSpec;
import android.content.ContentProvider;
import android.content.IContentProvider;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class PinnedSliceStateTest extends UiServiceTestCase {

    private static final String AUTH = "my.authority";
    private static final Uri TEST_URI = Uri.parse("content://" + AUTH + "/path");

    private static final SliceSpec[] FIRST_SPECS = new SliceSpec[]{
            new SliceSpec("spec1", 3),
            new SliceSpec("spec2", 3),
            new SliceSpec("spec3", 2),
            new SliceSpec("spec4", 1),
    };

    private static final SliceSpec[] SECOND_SPECS = new SliceSpec[]{
            new SliceSpec("spec2", 1),
            new SliceSpec("spec3", 2),
            new SliceSpec("spec4", 3),
            new SliceSpec("spec5", 4),
    };

    private SliceManagerService mSliceService;
    private PinnedSliceState mPinnedSliceManager;
    private IContentProvider mIContentProvider;
    private ContentProvider mContentProvider;

    @Before
    public void setup() {
        mSliceService = mock(SliceManagerService.class);
        when(mSliceService.getLock()).thenReturn(new Object());
        when(mSliceService.getContext()).thenReturn(mContext);
        when(mSliceService.getHandler()).thenReturn(new Handler(TestableLooper.get(this).getLooper()));
        mContentProvider = mock(ContentProvider.class);
        mIContentProvider = mock(IContentProvider.class);
        when(mContentProvider.getIContentProvider()).thenReturn(mIContentProvider);
        mContext.getContentResolver().addProvider(AUTH, mContentProvider);
        mPinnedSliceManager = new PinnedSliceState(mSliceService, TEST_URI);
    }

    @Test
    public void testMergeSpecs() {
        // No annotations to start.
        assertNull(mPinnedSliceManager.getSpecs());

        mPinnedSliceManager.mergeSpecs(FIRST_SPECS);
        assertArrayEquals(FIRST_SPECS, mPinnedSliceManager.getSpecs());

        mPinnedSliceManager.mergeSpecs(SECOND_SPECS);
        assertArrayEquals(new SliceSpec[]{
                // spec1 is gone because it's not in the second set.
                new SliceSpec("spec2", 1), // spec2 is 1 because it's smaller in the second set.
                new SliceSpec("spec3", 2), // spec3 is the same in both sets
                new SliceSpec("spec4", 1), // spec4 is 1 because it's smaller in the first set.
                // spec5 is gone because it's not in the first set.
        }, mPinnedSliceManager.getSpecs());
    }

    @Test
    public void testSendPinnedOnCreate() throws RemoteException {
        // When created, a pinned message should be sent.
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider).call(anyString(), eq(SliceProvider.METHOD_PIN), eq(null),
                argThat(b -> {
                    assertEquals(TEST_URI, b.getParcelable(SliceProvider.EXTRA_BIND_URI));
                    return true;
                }));
    }

    @Test
    public void testSendUnpinnedOnDestroy() throws RemoteException {
        TestableLooper.get(this).processAllMessages();
        clearInvocations(mIContentProvider);

        mPinnedSliceManager.destroy();
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider).call(anyString(), eq(SliceProvider.METHOD_UNPIN), eq(null),
                argThat(b -> {
                    assertEquals(TEST_URI, b.getParcelable(SliceProvider.EXTRA_BIND_URI));
                    return true;
                }));
    }

    @Test
    public void testPkgPin() {
        assertFalse(mPinnedSliceManager.isPinned());

        mPinnedSliceManager.pin("pkg", FIRST_SPECS);
        assertTrue(mPinnedSliceManager.isPinned());

        assertTrue(mPinnedSliceManager.unpin("pkg"));
        assertFalse(mPinnedSliceManager.isPinned());
    }

    @Test
    public void testMultiPkgPin() {
        assertFalse(mPinnedSliceManager.isPinned());

        mPinnedSliceManager.pin("pkg", FIRST_SPECS);
        assertTrue(mPinnedSliceManager.isPinned());
        mPinnedSliceManager.pin("pkg2", FIRST_SPECS);

        assertFalse(mPinnedSliceManager.unpin("pkg"));
        assertTrue(mPinnedSliceManager.unpin("pkg2"));
        assertFalse(mPinnedSliceManager.isPinned());
    }

    @Test
    public void testListenerPin() {
        ISliceListener listener = mock(ISliceListener.class);
        assertFalse(mPinnedSliceManager.isPinned());

        mPinnedSliceManager.addSliceListener(listener, FIRST_SPECS);
        assertTrue(mPinnedSliceManager.isPinned());

        assertTrue(mPinnedSliceManager.removeSliceListener(listener));
        assertFalse(mPinnedSliceManager.isPinned());
    }

    @Test
    public void testMultiListenerPin() {
        ISliceListener listener = mock(ISliceListener.class);
        ISliceListener listener2 = mock(ISliceListener.class);
        assertFalse(mPinnedSliceManager.isPinned());

        mPinnedSliceManager.addSliceListener(listener, FIRST_SPECS);
        assertTrue(mPinnedSliceManager.isPinned());
        mPinnedSliceManager.addSliceListener(listener2, FIRST_SPECS);

        assertFalse(mPinnedSliceManager.removeSliceListener(listener));
        assertTrue(mPinnedSliceManager.removeSliceListener(listener2));
        assertFalse(mPinnedSliceManager.isPinned());
    }

    @Test
    public void testPkgListenerPin() {
        ISliceListener listener = mock(ISliceListener.class);
        assertFalse(mPinnedSliceManager.isPinned());

        mPinnedSliceManager.addSliceListener(listener, FIRST_SPECS);
        assertTrue(mPinnedSliceManager.isPinned());
        mPinnedSliceManager.pin("pkg", FIRST_SPECS);

        assertFalse(mPinnedSliceManager.removeSliceListener(listener));
        assertTrue(mPinnedSliceManager.unpin("pkg"));
        assertFalse(mPinnedSliceManager.isPinned());
    }

    @Test
    public void testBind() throws RemoteException {
        TestableLooper.get(this).processAllMessages();
        clearInvocations(mIContentProvider);

        ISliceListener listener = mock(ISliceListener.class);
        Slice s = new Slice.Builder(TEST_URI).build();
        Bundle b = new Bundle();
        b.putParcelable(SliceProvider.EXTRA_SLICE, s);
        when(mIContentProvider.call(anyString(), eq(SliceProvider.METHOD_SLICE), eq(null),
                any())).thenReturn(b);

        assertFalse(mPinnedSliceManager.isPinned());

        mPinnedSliceManager.addSliceListener(listener, FIRST_SPECS);

        mPinnedSliceManager.onChange();
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider).call(anyString(), eq(SliceProvider.METHOD_SLICE), eq(null),
                argThat(bundle -> {
                    assertEquals(TEST_URI, bundle.getParcelable(SliceProvider.EXTRA_BIND_URI));
                    return true;
                }));
        verify(listener).onSliceUpdated(eq(s));
    }
}