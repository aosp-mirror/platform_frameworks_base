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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;

import java.util.Objects;

/**
 * The real {@link TimeDetectorInternal} local service implementation.
 *
 * @hide
 */
public class TimeDetectorInternalImpl implements TimeDetectorInternal {

    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final TimeDetectorStrategy mTimeDetectorStrategy;

    public TimeDetectorInternalImpl(@NonNull Context context, @NonNull Handler handler,
            @NonNull TimeDetectorStrategy timeDetectorStrategy) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mTimeDetectorStrategy = Objects.requireNonNull(timeDetectorStrategy);
    }

    @Override
    public void suggestNetworkTime(@NonNull NetworkTimeSuggestion timeSignal) {
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestNetworkTime(timeSignal));
    }

    @Override
    public void suggestGnssTime(@NonNull GnssTimeSuggestion timeSignal) {
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestGnssTime(timeSignal));
    }
}
