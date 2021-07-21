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

package com.android.server.location.test;

import static com.google.common.truth.Truth.assertThat;

import android.location.LocationResult;

import com.android.server.location.provider.AbstractLocationProvider;

import java.util.LinkedList;

public class ProviderListenerCapture implements AbstractLocationProvider.Listener {

    private final Object mLock;
    private final LinkedList<AbstractLocationProvider.State> mNewStates = new LinkedList<>();
    private final LinkedList<LocationResult> mLocations = new LinkedList<>();

    public ProviderListenerCapture(Object lock) {
        mLock = lock;
    }

    @Override
    public void onStateChanged(AbstractLocationProvider.State oldState,
            AbstractLocationProvider.State newState) {
        assertThat(Thread.holdsLock(mLock)).isTrue();
        mNewStates.add(newState);
    }

    public AbstractLocationProvider.State getNextNewState() {
        return mNewStates.poll();
    }

    @Override
    public void onReportLocation(LocationResult locationResult) {
        assertThat(Thread.holdsLock(mLock)).isTrue();
        mLocations.add(locationResult);
    }

    public LocationResult getNextLocationResult() {
        return mLocations.poll();
    }
}
