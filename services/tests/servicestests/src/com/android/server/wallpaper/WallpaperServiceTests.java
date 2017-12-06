/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.WallpaperColors;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class WallpaperServiceTests {

    @UiThreadTest
    @Test
    public void testNotifyColorsChanged_rateLimit() throws Exception {
        long[] clockOffset = {0};
        boolean[] postDelayed = {false};
        Supplier<Long> clockFunction = () -> SystemClock.elapsedRealtime() + clockOffset[0];
        Handler handler = new Handler() {
            @Override
            public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
                postDelayed[0] = true;
                return super.sendMessageAtTime(msg, uptimeMillis);
            }
        };

        CountDownLatch eventCountdown = new CountDownLatch(2);
        WallpaperService service = new WallpaperService() {
            @Override
            public Engine onCreateEngine() {
                return new WallpaperService.Engine(clockFunction, handler) {
                    @Override
                    public WallpaperColors onComputeColors() {
                        eventCountdown.countDown();
                        return null;
                    }
                };
            }
        };
        WallpaperService.Engine engine = service.onCreateEngine();

        // Called because it's the first time.
        engine.notifyColorsChanged();
        assertEquals("OnComputeColors should have been called.",
                1, eventCountdown.getCount());

        // Ignored since the call should be throttled.
        engine.notifyColorsChanged();
        assertEquals("OnComputeColors should have been throttled.",
                1, eventCountdown.getCount());
        // Should have been posted to the handler.
        assertTrue("Event should have been delayed", postDelayed[0]);

        // Called again after being deferred.
        clockOffset[0] = 1500;
        engine.notifyColorsChanged();
        assertEquals("OnComputeColors should have been deferred.",
                0, eventCountdown.getCount());
    }
}
