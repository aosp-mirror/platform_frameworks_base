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

package android.content;

import android.content.res.Configuration;

import androidx.annotation.NonNull;

public class TestComponentCallbacks2 implements ComponentCallbacks2 {
    public Configuration mConfiguration = null;
    public boolean mLowMemoryCalled = false;
    public int mLevel = 0;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        mConfiguration = newConfig;
    }

    @Override
    public void onLowMemory() {
        mLowMemoryCalled = true;
    }

    @Override
    public void onTrimMemory(int level) {
        mLevel = level;
    }
}
