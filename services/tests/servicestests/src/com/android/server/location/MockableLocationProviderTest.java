/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.location;

import static androidx.test.ext.truth.location.LocationSubject.assertThat;

import static com.android.internal.location.ProviderRequest.EMPTY_REQUEST;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.location.Criteria;
import android.location.Location;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.server.location.test.FakeProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedList;
import java.util.List;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class MockableLocationProviderTest {

    private Object mLock;
    private ListenerCapture mListener;

    private AbstractLocationProvider mRealProvider;
    private MockProvider mMockProvider;

    private MockableLocationProvider mProvider;

    @Before
    public void setUp() {
        mLock = new Object();
        mListener = new ListenerCapture();

        mRealProvider = spy(new FakeProvider());
        mMockProvider = spy(new MockProvider(new ProviderProperties(
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE)));

        mProvider = new MockableLocationProvider(mLock, mListener);
        mProvider.setRealProvider(mRealProvider);
    }

    @Test
    public void testSetProvider() {
        assertThat(mProvider.getProvider()).isEqualTo(mRealProvider);

        mProvider.setMockProvider(mMockProvider);
        assertThat(mProvider.getProvider()).isEqualTo(mMockProvider);

        mProvider.setMockProvider(null);
        assertThat(mProvider.getProvider()).isEqualTo(mRealProvider);

        mProvider.setRealProvider(null);
        assertThat(mProvider.getProvider()).isNull();
    }

    @Test
    public void testSetRequest() {
        assertThat(mProvider.getCurrentRequest()).isEqualTo(EMPTY_REQUEST);
        verify(mRealProvider, times(1)).onSetRequest(EMPTY_REQUEST);

        ProviderRequest request = new ProviderRequest.Builder().setInterval(1).build();
        mProvider.setRequest(request);

        assertThat(mProvider.getCurrentRequest()).isEqualTo(request);
        verify(mRealProvider, times(1)).onSetRequest(request);

        mProvider.setMockProvider(mMockProvider);
        assertThat(mProvider.getCurrentRequest()).isEqualTo(request);
        verify(mRealProvider, times(2)).onSetRequest(EMPTY_REQUEST);
        verify(mMockProvider, times(1)).onSetRequest(request);

        mProvider.setMockProvider(null);
        assertThat(mProvider.getCurrentRequest()).isEqualTo(request);
        verify(mMockProvider, times(1)).onSetRequest(EMPTY_REQUEST);
        verify(mRealProvider, times(2)).onSetRequest(request);

        mProvider.setRealProvider(null);
        assertThat(mProvider.getCurrentRequest()).isEqualTo(request);
        verify(mRealProvider, times(3)).onSetRequest(EMPTY_REQUEST);
    }

    @Test
    public void testSendExtraCommand() {
        mProvider.sendExtraCommand(0, 0, "command", null);
        verify(mRealProvider, times(1)).onExtraCommand(0, 0, "command", null);

        mProvider.setMockProvider(mMockProvider);
        mProvider.sendExtraCommand(0, 0, "command", null);
        verify(mMockProvider, times(1)).onExtraCommand(0, 0, "command", null);
    }

    @Test
    public void testSetState() {
        assertThat(mProvider.getState().allowed).isFalse();

        AbstractLocationProvider.State newState;

        mRealProvider.setAllowed(true);
        newState = mListener.getNextNewState();
        assertThat(newState).isNotNull();
        assertThat(newState.allowed).isTrue();

        mProvider.setMockProvider(mMockProvider);
        newState = mListener.getNextNewState();
        assertThat(newState).isNotNull();
        assertThat(newState.allowed).isFalse();

        mMockProvider.setAllowed(true);
        newState = mListener.getNextNewState();
        assertThat(newState).isNotNull();
        assertThat(newState.allowed).isTrue();

        mRealProvider.setAllowed(false);
        assertThat(mListener.getNextNewState()).isNull();

        mProvider.setMockProvider(null);
        newState = mListener.getNextNewState();
        assertThat(newState).isNotNull();
        assertThat(newState.allowed).isFalse();
    }

    @Test
    public void testReportLocation() {
        Location realLocation = new Location("real");
        Location mockLocation = new Location("mock");

        mRealProvider.reportLocation(realLocation);
        assertThat(mListener.getNextLocation()).isEqualTo(realLocation);

        mProvider.setMockProvider(mMockProvider);
        mRealProvider.reportLocation(realLocation);
        mMockProvider.reportLocation(mockLocation);
        assertThat(mListener.getNextLocation()).isEqualTo(mockLocation);
    }

    private class ListenerCapture implements AbstractLocationProvider.Listener {

        private final LinkedList<AbstractLocationProvider.State> mNewStates = new LinkedList<>();
        private final LinkedList<Location> mLocations = new LinkedList<>();

        @Override
        public void onStateChanged(AbstractLocationProvider.State oldState,
                AbstractLocationProvider.State newState) {
            assertThat(Thread.holdsLock(mLock)).isTrue();
            mNewStates.add(newState);
        }

        private AbstractLocationProvider.State getNextNewState() {
            return mNewStates.poll();
        }

        @Override
        public void onReportLocation(Location location) {
            assertThat(Thread.holdsLock(mLock)).isTrue();
            mLocations.add(location);
        }

        private Location getNextLocation() {
            return mLocations.poll();
        }

        @Override
        public void onReportLocation(List<Location> locations) {}
    }
}
