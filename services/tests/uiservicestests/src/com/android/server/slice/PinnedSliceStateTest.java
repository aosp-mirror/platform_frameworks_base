package com.android.server.slice;

import static android.testing.TestableContentResolver.UNSTABLE;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.slice.ISliceListener;
import android.app.slice.SliceProvider;
import android.app.slice.SliceSpec;
import android.content.ContentProvider;
import android.content.IContentProvider;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

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
    private IBinder mToken = new Binder();

    @Before
    public void setup() {
        mSliceService = mock(SliceManagerService.class);
        when(mSliceService.getContext()).thenReturn(mContext);
        when(mSliceService.getLock()).thenReturn(new Object());
        when(mSliceService.getHandler()).thenReturn(
                new Handler(TestableLooper.get(this).getLooper()));
        mContentProvider = mock(ContentProvider.class);
        mIContentProvider = mock(IContentProvider.class);
        when(mContentProvider.getIContentProvider()).thenReturn(mIContentProvider);
        mContext.getContentResolver().addProvider(AUTH, mContentProvider, UNSTABLE);
        mPinnedSliceManager = new PinnedSliceState(mSliceService, TEST_URI, "pkg");
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
        mPinnedSliceManager.pin("pkg", FIRST_SPECS, mToken);
        TestableLooper.get(this).processAllMessages();

        verify(mIContentProvider).call(anyString(), anyString(), eq(SliceProvider.METHOD_PIN),
                eq(null), argThat(b -> {
                    assertEquals(TEST_URI, b.getParcelable(SliceProvider.EXTRA_BIND_URI));
                    return true;
                }));
    }

    @Test
    public void testPkgPin() {
        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.pin("pkg", FIRST_SPECS, mToken);
        assertTrue(mPinnedSliceManager.hasPinOrListener());

        assertTrue(mPinnedSliceManager.unpin("pkg", mToken));
        assertFalse(mPinnedSliceManager.hasPinOrListener());
    }

    @Test
    public void testMultiPkgPin() {
        IBinder t2 = new Binder();
        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.pin("pkg", FIRST_SPECS, mToken);
        assertTrue(mPinnedSliceManager.hasPinOrListener());
        mPinnedSliceManager.pin("pkg2", FIRST_SPECS, t2);

        assertFalse(mPinnedSliceManager.unpin("pkg", mToken));
        assertTrue(mPinnedSliceManager.unpin("pkg2", t2));
        assertFalse(mPinnedSliceManager.hasPinOrListener());
    }

    @Test
    public void testListenerDeath() throws RemoteException {
        ISliceListener listener = mock(ISliceListener.class);
        IBinder binder = mock(IBinder.class);
        when(binder.isBinderAlive()).thenReturn(true);
        when(listener.asBinder()).thenReturn(binder);
        assertFalse(mPinnedSliceManager.hasPinOrListener());

        mPinnedSliceManager.pin(mContext.getPackageName(), FIRST_SPECS, binder);
        assertTrue(mPinnedSliceManager.hasPinOrListener());

        ArgumentCaptor<DeathRecipient> arg = ArgumentCaptor.forClass(DeathRecipient.class);
        verify(binder).linkToDeath(arg.capture(), anyInt());

        when(binder.isBinderAlive()).thenReturn(false);
        arg.getValue().binderDied();

        verify(mSliceService).removePinnedSlice(eq(TEST_URI));
        assertFalse(mPinnedSliceManager.hasPinOrListener());
    }
}
