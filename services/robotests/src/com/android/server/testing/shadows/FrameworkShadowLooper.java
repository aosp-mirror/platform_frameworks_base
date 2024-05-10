/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.testing.shadows;

import android.os.Looper;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.LooperShadowPicker;
import org.robolectric.shadows.ShadowLegacyLooper;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPausedLooper;

import java.util.Optional;

@Implements(value = Looper.class, shadowPicker = FrameworkShadowLooper.Picker.class)
public class FrameworkShadowLooper extends ShadowLegacyLooper {
    @RealObject private Looper mLooper;
    private Optional<Boolean> mIsCurrentThread = Optional.empty();

    public void setCurrentThread(boolean currentThread) {
        mIsCurrentThread = Optional.of(currentThread);
    }

    public void reset() {
        mIsCurrentThread = Optional.empty();
    }

    @Implementation
    protected boolean isCurrentThread() {
        if (mIsCurrentThread.isPresent()) {
            return mIsCurrentThread.get();
        }
        return Thread.currentThread() == mLooper.getThread();
    }

    public static class Picker extends LooperShadowPicker<ShadowLooper> {
        public Picker() {
            super(FrameworkShadowLooper.class, ShadowPausedLooper.class);
        }
    }
}
