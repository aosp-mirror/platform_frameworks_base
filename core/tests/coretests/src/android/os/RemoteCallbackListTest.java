/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class RemoteCallbackListTest {
    private RemoteCallbackList<IRemoteCallback> mList;
    private MyRemoteCallback mRed;
    private MyRemoteCallback mGreen;
    private MyRemoteCallback mBlue;
    private Object mCookie;

    public static class MyRemoteCallback {
        public final CountDownLatch mLatch = new CountDownLatch(1);
        public final RemoteCallback mCallback = new RemoteCallback((bundle) -> {
            mLatch.countDown();
        });
        public final IRemoteCallback mInterface = mCallback.getInterface();
    }

    @Before
    public void setUp() {
        mList = new RemoteCallbackList<>();
        mRed = new MyRemoteCallback();
        mGreen = new MyRemoteCallback();
        mBlue = new MyRemoteCallback();
        mCookie = new Object();
    }

    @Test
    public void testInspection() {
        assertEquals(0, mList.getRegisteredCallbackCount());

        mList.register(mRed.mInterface);
        mList.register(mGreen.mInterface, mCookie);
        assertEquals(2, mList.getRegisteredCallbackCount());

        final List<IRemoteCallback> list = new ArrayList<>();
        for (int i = 0; i < mList.getRegisteredCallbackCount(); i++) {
            list.add(mList.getRegisteredCallbackItem(i));
        }
        final int redIndex = list.indexOf(mRed.mInterface);
        final int greenIndex = list.indexOf(mGreen.mInterface);
        assertTrue(redIndex >= 0);
        assertTrue(greenIndex >= 0);
        assertEquals(null, mList.getRegisteredCallbackCookie(redIndex));
        assertEquals(mCookie, mList.getRegisteredCallbackCookie(greenIndex));

        mList.unregister(mRed.mInterface);
        assertEquals(1, mList.getRegisteredCallbackCount());
        assertEquals(mGreen.mInterface, mList.getRegisteredCallbackItem(0));
        assertEquals(mCookie, mList.getRegisteredCallbackCookie(0));
    }

    @Test
    public void testEmpty_Manual() {
        final int num = mList.beginBroadcast();
        assertEquals(0, num);
        mList.finishBroadcast();
    }

    @Test
    public void testEmpty_Functional() {
        final AtomicInteger count = new AtomicInteger();
        mList.broadcast((e) -> {
            count.incrementAndGet();
        });
        assertEquals(0, count.get());
    }

    @Test
    public void testSimple_Manual() throws Exception {
        mList.register(mRed.mInterface);
        mList.register(mGreen.mInterface);

        final int num = mList.beginBroadcast();
        for (int i = num - 1; i >= 0; i--) {
            mList.getBroadcastItem(i).sendResult(Bundle.EMPTY);
        }
        mList.finishBroadcast();

        assertEquals(0, mRed.mLatch.getCount());
        assertEquals(0, mGreen.mLatch.getCount());
        assertEquals(1, mBlue.mLatch.getCount());
    }

    @Test
    public void testSimple_Functional() throws Exception {
        mList.register(mRed.mInterface);
        mList.register(mGreen.mInterface);

        mList.broadcast((e) -> {
            try {
                e.sendResult(Bundle.EMPTY);
            } catch (RemoteException ignored) {
            }
        });

        assertEquals(0, mRed.mLatch.getCount());
        assertEquals(0, mGreen.mLatch.getCount());
        assertEquals(1, mBlue.mLatch.getCount());
    }
}
