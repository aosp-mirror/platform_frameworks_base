/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.log;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.OperationContext;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Presubmit
@SmallTest
public class BiometricContextProviderTest {

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private IStatusBarService mStatusBarService;

    private OperationContext mOpContext = new OperationContext();
    private IBiometricContextListener mListener;
    private BiometricContextProvider mProvider;

    @Before
    public void setup() throws RemoteException {
        mProvider = new BiometricContextProvider(mStatusBarService, null /* handler */);
        ArgumentCaptor<IBiometricContextListener> captor =
                ArgumentCaptor.forClass(IBiometricContextListener.class);
        verify(mStatusBarService).setBiometicContextListener(captor.capture());
        mListener = captor.getValue();
    }

    @Test
    public void testIsAoD() throws RemoteException {
        mListener.onDozeChanged(true);
        assertThat(mProvider.isAoD()).isTrue();
        mListener.onDozeChanged(false);
        assertThat(mProvider.isAoD()).isFalse();
    }

    @Test
    public void testSubscribesToAoD() throws RemoteException {
        final List<Boolean> expected = ImmutableList.of(true, false, true, true, false);
        final List<Boolean> actual = new ArrayList<>();

        mProvider.subscribe(mOpContext, ctx -> {
            assertThat(ctx).isSameInstanceAs(mOpContext);
            actual.add(ctx.isAoD);
        });

        for (boolean v : expected) {
            mListener.onDozeChanged(v);
        }

        assertThat(actual).containsExactlyElementsIn(expected).inOrder();
    }

    @Test
    public void testUnsubscribes() throws RemoteException {
        final Consumer<OperationContext> emptyConsumer = mock(Consumer.class);
        mProvider.subscribe(mOpContext, emptyConsumer);
        mProvider.unsubscribe(mOpContext);

        mListener.onDozeChanged(true);

        final Consumer<OperationContext> nonEmptyConsumer = mock(Consumer.class);
        mProvider.subscribe(mOpContext, nonEmptyConsumer);
        mListener.onDozeChanged(false);
        mProvider.unsubscribe(mOpContext);
        mListener.onDozeChanged(true);

        verify(emptyConsumer, never()).accept(any());
        verify(nonEmptyConsumer).accept(same(mOpContext));
    }
}
