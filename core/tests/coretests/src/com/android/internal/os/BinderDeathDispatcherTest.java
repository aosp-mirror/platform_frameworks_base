/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BinderDeathDispatcherTest {
    private static class MyTarget implements IInterface, IBinder {
        public boolean isAlive = true;
        public DeathRecipient mRecipient;

        @Override
        public String getInterfaceDescriptor() throws RemoteException {
            return null;
        }

        @Override
        public boolean pingBinder() {
            return false;
        }

        @Override
        public boolean isBinderAlive() {
            return isAlive;
        }

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public void dump(FileDescriptor fd, String[] args) throws RemoteException {

        }

        @Override
        public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {

        }

        @Override
        public void shellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback shellCallback, ResultReceiver resultReceiver)
                throws RemoteException {

        }

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            return false;
        }

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
            // In any situation, a single binder object should only have at most one death
            // recipient.
            assertThat(mRecipient).isNull();

            if (!isAlive) {
                throw new DeadObjectException();
            }

            mRecipient = recipient;
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            if (!isAlive) {
                return false;
            }
            assertThat(mRecipient).isSameAs(recipient);
            mRecipient = null;
            return true;
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        public void die() {
            isAlive = false;
            if (mRecipient != null) {
                mRecipient.binderDied();
            }
            mRecipient = null;
        }

        public boolean hasDeathRecipient() {
            return mRecipient != null;
        }
    }

    @Test
    public void testRegisterAndUnregister() {
        BinderDeathDispatcher<MyTarget> d = new BinderDeathDispatcher<>();

        MyTarget t1 = new MyTarget();
        MyTarget t2 = new MyTarget();
        MyTarget t3 = new MyTarget();

        DeathRecipient r1 = mock(DeathRecipient.class);
        DeathRecipient r2 = mock(DeathRecipient.class);
        DeathRecipient r3 = mock(DeathRecipient.class);
        DeathRecipient r4 = mock(DeathRecipient.class);
        DeathRecipient r5 = mock(DeathRecipient.class);

        // Start hooking up.

        // Link 3 recipients to t1 -- only one real recipient will be set.
        assertThat(d.linkToDeath(t1, r1)).isEqualTo(1);
        assertThat(d.getTargetsForTest().size()).isEqualTo(1);

        assertThat(d.linkToDeath(t1, r2)).isEqualTo(2);
        assertThat(d.linkToDeath(t1, r3)).isEqualTo(3);
        assertThat(d.getTargetsForTest().size()).isEqualTo(1);

        // Unlink two -- the real recipient is still set.
        d.unlinkToDeath(t1, r1);
        d.unlinkToDeath(t1, r2);

        assertThat(t1.hasDeathRecipient()).isTrue();
        assertThat(d.getTargetsForTest().size()).isEqualTo(1);

        // Unlink the last one. The real recipient is also unlinked.
        d.unlinkToDeath(t1, r3);
        assertThat(t1.hasDeathRecipient()).isFalse();
        assertThat(d.getTargetsForTest().size()).isEqualTo(0);

        // Set recipients to t1, t2 and t3. t3 has two.
        assertThat(d.linkToDeath(t1, r1)).isEqualTo(1);
        assertThat(d.linkToDeath(t2, r1)).isEqualTo(1);
        assertThat(d.linkToDeath(t3, r1)).isEqualTo(1);
        assertThat(d.linkToDeath(t3, r2)).isEqualTo(2);


        // They should all have a real recipient.
        assertThat(t1.hasDeathRecipient()).isTrue();
        assertThat(t2.hasDeathRecipient()).isTrue();
        assertThat(t3.hasDeathRecipient()).isTrue();

        assertThat(d.getTargetsForTest().size()).isEqualTo(3);

        // Unlink r1 from t3. t3 still has r2, so it should still have a real recipient.
        d.unlinkToDeath(t3, r1);
        assertThat(t1.hasDeathRecipient()).isTrue();
        assertThat(t2.hasDeathRecipient()).isTrue();
        assertThat(t3.hasDeathRecipient()).isTrue();
        assertThat(d.getTargetsForTest().size()).isEqualTo(3);

        // Unlink r2 from t3. Now t3 has no real recipient.
        d.unlinkToDeath(t3, r2);
        assertThat(t3.hasDeathRecipient()).isFalse();
        assertThat(d.getTargetsForTest().size()).isEqualTo(2);
    }

    @Test
    public void testRegisterAndKill() {
        BinderDeathDispatcher<MyTarget> d = new BinderDeathDispatcher<>();

        MyTarget t1 = new MyTarget();
        MyTarget t2 = new MyTarget();
        MyTarget t3 = new MyTarget();

        DeathRecipient r1 = mock(DeathRecipient.class);
        DeathRecipient r2 = mock(DeathRecipient.class);
        DeathRecipient r3 = mock(DeathRecipient.class);
        DeathRecipient r4 = mock(DeathRecipient.class);
        DeathRecipient r5 = mock(DeathRecipient.class);

        // Hook them up.

        d.linkToDeath(t1, r1);
        d.linkToDeath(t1, r2);
        d.linkToDeath(t1, r3);

        // r4 is linked then unlinked. It shouldn't be notified.
        d.linkToDeath(t1, r4);
        d.unlinkToDeath(t1, r4);

        d.linkToDeath(t2, r1);

        d.linkToDeath(t3, r3);
        d.linkToDeath(t3, r5);

        assertThat(d.getTargetsForTest().size()).isEqualTo(3);

        // Kill the targets.

        t1.die();
        verify(r1, times(1)).binderDied();
        verify(r2, times(1)).binderDied();
        verify(r3, times(1)).binderDied();
        verify(r4, times(0)).binderDied();
        verify(r5, times(0)).binderDied();

        assertThat(d.getTargetsForTest().size()).isEqualTo(2);

        reset(r1, r2, r3, r4, r5);

        t2.die();
        verify(r1, times(1)).binderDied();
        verify(r2, times(0)).binderDied();
        verify(r3, times(0)).binderDied();
        verify(r4, times(0)).binderDied();
        verify(r5, times(0)).binderDied();

        assertThat(d.getTargetsForTest().size()).isEqualTo(1);

        reset(r1, r2, r3, r4, r5);

        t3.die();
        verify(r1, times(0)).binderDied();
        verify(r2, times(0)).binderDied();
        verify(r3, times(1)).binderDied();
        verify(r4, times(0)).binderDied();
        verify(r5, times(1)).binderDied();

        assertThat(d.getTargetsForTest().size()).isEqualTo(0);

        // Try to register to a dead object -> should return -1.
        assertThat(d.linkToDeath(t1, r1)).isEqualTo(-1);

        assertThat(d.getTargetsForTest().size()).isEqualTo(0);
    }
}
