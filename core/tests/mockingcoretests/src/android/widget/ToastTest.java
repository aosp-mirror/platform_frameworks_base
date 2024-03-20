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

package android.widget;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.content.Context;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.View;
import android.widget.flags.Flags;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;


/**
 * ToastTest tests {@link Toast}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ToastTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;
    private MockitoSession mMockingSession;
    private static INotificationManager.Stub sMockNMS;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
        mMockingSession =
              ExtendedMockito.mockitoSession()
                  .strictness(Strictness.LENIENT)
                  .mockStatic(ServiceManager.class)
                  .startMocking();

        //Toast caches the NotificationManager service as static class member
        if (sMockNMS == null) {
            sMockNMS = mock(INotificationManager.Stub.class);
        }
        doReturn(sMockNMS).when(sMockNMS).queryLocalInterface("android.app.INotificationManager");
        doReturn(sMockNMS).when(() -> ServiceManager.getService(Context.NOTIFICATION_SERVICE));
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
        reset(sMockNMS);
    }

    @Test
    @EnableFlags(Flags.FLAG_TOAST_NO_WEAKREF)
    public void enqueueFail_nullifiesNextView() throws RemoteException {
        Looper.prepare();

        // allow 1st toast and fail on the 2nd
        when(sMockNMS.enqueueToast(anyString(), any(), any(), anyInt(), anyBoolean(),
              anyInt())).thenReturn(true, false);

        // first toast is enqueued
        Toast t = Toast.makeText(mContext, "Toast1", Toast.LENGTH_SHORT);
        t.setView(mock(View.class));
        t.show();
        Toast.TN tn = t.getTn();
        assertThat(tn.getNextView()).isNotNull();

        // second toast is not enqueued
        t = Toast.makeText(mContext, "Toast2", Toast.LENGTH_SHORT);
        t.setView(mock(View.class));
        t.show();
        tn = t.getTn();
        assertThat(tn.getNextView()).isNull();
    }

    @Test
    @DisableFlags(Flags.FLAG_TOAST_NO_WEAKREF)
    public void enqueueFail_doesNotNullifyNextView() throws RemoteException {
        Looper.prepare();

        // allow 1st toast and fail on the 2nd
        when(sMockNMS.enqueueToast(anyString(), any(), any(), anyInt(), anyBoolean(),
              anyInt())).thenReturn(true, false);

        // first toast is enqueued
        Toast t = Toast.makeText(mContext, "Toast1", Toast.LENGTH_SHORT);
        t.setView(mock(View.class));
        t.show();
        Toast.TN tn = t.getTn();
        assertThat(tn.getNextView()).isNotNull();

        // second toast is not enqueued
        t = Toast.makeText(mContext, "Toast2", Toast.LENGTH_SHORT);
        t.setView(mock(View.class));
        t.show();
        tn = t.getTn();
        assertThat(tn.getNextView()).isNotNull();
    }
}
