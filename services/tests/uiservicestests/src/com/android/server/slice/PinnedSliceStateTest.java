package com.android.server.slice;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.slice.ISliceListener;
import android.app.slice.Slice;
import android.app.slice.SliceProvider;
import android.app.slice.SliceSpec;
import android.content.ContentProvider;
import android.content.Context;
import android.content.IContentProvider;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

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
        when(mSliceService.getContext()).thenReturn(mContext);
        when(mSliceService.getLock()).thenReturn(new Object());
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
    public void testSendPinnedOnPin() throws RemoteException {
        TestableLooper.get(this).processAllMessages();

        // When pinned for the first time, a pinned message should be sent.
        mPinnedSliceManager.pin("pkg", FIRST_SPECS);
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider).call(anyString(), eq(SliceProvider.METHOD_PIN), eq(null),
                argThat(b -> {
                    assertEquals(TEST_URI, b.getParcelable(SliceProvider.EXTRA_BIND_URI));
                    return true;
                }));
    }

    @Test
    public void testSendPinnedOnListen() throws RemoteException {
        TestableLooper.get(this).processAllMessages();

        // When a listener is added for the first time, a pinned message should be sent.
        ISliceListener listener = mock(ISliceListener.class);
        when(listener.asBinder()).thenReturn(new Binder());

        mPinnedSliceManager.addSliceListener(listener, mContext.getPackageName(), FIRST_SPECS,
                true);
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider).call(anyString(), eq(SliceProvider.METHOD_PIN), eq(null),
                argThat(b -> {
                    assertEquals(TEST_URI, b.getParcelable(SliceProvider.EXTRA_BIND_URI));
                    return true;
                }));
    }

    @Test
    public void testNoSendPinnedWithoutPermission() throws RemoteException {
        TestableLooper.get(this).processAllMessages();

        // When a listener is added for the first time, a pinned message should be sent.
        ISliceListener listener = mock(ISliceListener.class);
        when(listener.asBinder()).thenReturn(new Binder());

        mPinnedSliceManager.addSliceListener(listener, mContext.getPackageName(), FIRST_SPECS,
                false);
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider, never()).call(anyString(), eq(SliceProvider.METHOD_PIN), eq(null),
                any());
    }

    @Test
    public void testSendUnpinnedOnDestroy() throws RemoteException {
        TestableLooper.get(this).processAllMessages();
        clearInvocations(mIContentProvider);

        mPinnedSliceManager.pin("pkg", FIRST_SPECS);
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
        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.pin("pkg", FIRST_SPECS);
        assertTrue(mPinnedSliceManager.hasPinOrListener());

        assertTrue(mPinnedSliceManager.unpin("pkg"));
        assertFalse(mPinnedSliceManager.hasPinOrListener());
    }

    @Test
    public void testMultiPkgPin() {
        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.pin("pkg", FIRST_SPECS);
        assertTrue(mPinnedSliceManager.hasPinOrListener());
        mPinnedSliceManager.pin("pkg2", FIRST_SPECS);

        assertFalse(mPinnedSliceManager.unpin("pkg"));
        assertTrue(mPinnedSliceManager.unpin("pkg2"));
        assertFalse(mPinnedSliceManager.hasPinOrListener());
    }

    @Test
    public void testListenerPin() {
        ISliceListener listener = mock(ISliceListener.class);
        when(listener.asBinder()).thenReturn(new Binder());
        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.addSliceListener(listener, mContext.getPackageName(), FIRST_SPECS,
                true);
        assertTrue(mPinnedSliceManager.hasPinOrListener());

        assertTrue(mPinnedSliceManager.removeSliceListener(listener));
        assertFalse(mPinnedSliceManager.hasPinOrListener());
    }

    @Test
    public void testMultiListenerPin() {
        ISliceListener listener = mock(ISliceListener.class);
        Binder value = new Binder();
        when(listener.asBinder()).thenReturn(value);
        ISliceListener listener2 = mock(ISliceListener.class);
        Binder value2 = new Binder();
        when(listener2.asBinder()).thenReturn(value2);
        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.addSliceListener(listener, mContext.getPackageName(), FIRST_SPECS,
                true);
        assertTrue(mPinnedSliceManager.hasPinOrListener());
        mPinnedSliceManager.addSliceListener(listener2, mContext.getPackageName(), FIRST_SPECS,
                true);

        assertFalse(mPinnedSliceManager.removeSliceListener(listener));
        assertTrue(mPinnedSliceManager.removeSliceListener(listener2));
        assertFalse(mPinnedSliceManager.hasPinOrListener());
    }

    @Test
    public void testListenerDeath() throws RemoteException {
        ISliceListener listener = mock(ISliceListener.class);
        IBinder binder = mock(IBinder.class);
        when(binder.isBinderAlive()).thenReturn(true);
        when(listener.asBinder()).thenReturn(binder);
        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.addSliceListener(listener, mContext.getPackageName(), FIRST_SPECS,
                true);
        assertTrue(mPinnedSliceManager.hasPinOrListener());

        ArgumentCaptor<DeathRecipient> arg = ArgumentCaptor.forClass(DeathRecipient.class);
        verify(binder).linkToDeath(arg.capture(), anyInt());

        when(binder.isBinderAlive()).thenReturn(false);
        arg.getValue().binderDied();

        verify(mSliceService).unlisten(eq(TEST_URI));
        verify(mSliceService).removePinnedSlice(eq(TEST_URI));
        assertFalse(mPinnedSliceManager.hasPinOrListener());
    }

    @Test
    public void testPkgListenerPin() {
        ISliceListener listener = mock(ISliceListener.class);
        when(listener.asBinder()).thenReturn(new Binder());
        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.addSliceListener(listener, mContext.getPackageName(), FIRST_SPECS,
                true);
        assertTrue(mPinnedSliceManager.hasPinOrListener());
        mPinnedSliceManager.pin("pkg", FIRST_SPECS);

        assertFalse(mPinnedSliceManager.removeSliceListener(listener));
        assertTrue(mPinnedSliceManager.unpin("pkg"));
        assertFalse(mPinnedSliceManager.hasPinOrListener());
    }

    @Test
    public void testBind() throws RemoteException {
        TestableLooper.get(this).processAllMessages();
        clearInvocations(mIContentProvider);

        ISliceListener listener = mock(ISliceListener.class);
        when(listener.asBinder()).thenReturn(new Binder());
        Slice s = new Slice.Builder(TEST_URI).build();
        Bundle b = new Bundle();
        b.putParcelable(SliceProvider.EXTRA_SLICE, s);
        when(mIContentProvider.call(anyString(), eq(SliceProvider.METHOD_SLICE), eq(null),
                any())).thenReturn(b);

        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.addSliceListener(listener, mContext.getPackageName(), FIRST_SPECS,
                true);

        mPinnedSliceManager.onChange();
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider).call(anyString(), eq(SliceProvider.METHOD_SLICE), eq(null),
                argThat(bundle -> {
                    assertEquals(TEST_URI, bundle.getParcelable(SliceProvider.EXTRA_BIND_URI));
                    return true;
                }));
        verify(listener).onSliceUpdated(eq(s));
    }

    @Test
    public void testRecheckPackage() throws RemoteException {
        TestableLooper.get(this).processAllMessages();

        ISliceListener listener = mock(ISliceListener.class);
        when(listener.asBinder()).thenReturn(new Binder());

        mPinnedSliceManager.addSliceListener(listener, mContext.getPackageName(), FIRST_SPECS,
                false);
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider, never()).call(anyString(), eq(SliceProvider.METHOD_PIN), eq(null),
                any());

        when(mSliceService.checkAccess(any(), any(), anyInt(), anyInt()))
                .thenReturn(PERMISSION_GRANTED);
        mPinnedSliceManager.recheckPackage(mContext.getPackageName());
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider).call(anyString(), eq(SliceProvider.METHOD_PIN), eq(null),
                argThat(b -> {
                    assertEquals(TEST_URI, b.getParcelable(SliceProvider.EXTRA_BIND_URI));
                    return true;
                }));
    }
}