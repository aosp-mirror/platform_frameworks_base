/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.screenshot;

import com.android.systemui.dagger.SysUISingleton;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

/**
 * LongScreenshotHolder holds on to 1 LongScreenshot reference to facilitate indirect in-process
 * passing.
 */
@SysUISingleton
public class LongScreenshotHolder {
    private final AtomicReference<ScrollCaptureController.LongScreenshot> mLongScreenshot;

    @Inject
    public LongScreenshotHolder() {
        mLongScreenshot = new AtomicReference<>();
    }

    /**
     * Set the holder's stored LongScreenshot.
     */
    public void setLongScreenshot(ScrollCaptureController.LongScreenshot longScreenshot) {
        mLongScreenshot.set(longScreenshot);
    }

    /**
     * @return true if the holder has a non-null LongScreenshot.
     */
    public boolean hasLongScreenshot() {
        return mLongScreenshot.get() != null;
    }

    /**
     * Return the current stored LongScreenshot, clear the holder's storage.
     */
    public ScrollCaptureController.LongScreenshot takeLongScreenshot() {
        return mLongScreenshot.getAndSet(null);
    }

}
