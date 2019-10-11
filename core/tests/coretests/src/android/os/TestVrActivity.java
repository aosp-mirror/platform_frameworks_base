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

package android.os;

import android.app.Activity;
import android.service.vr.VrListenerService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An activity for enabling/disabling VrMode.
 */
public class TestVrActivity extends Activity {
    private CountDownLatch mLatch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mLatch = new CountDownLatch(1);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mLatch.countDown();
    }

    public static class TestVrListenerService extends VrListenerService {
        @Override
        public void onCreate() {
            super.onCreate();
        }
    }

    public boolean waitForActivityStart() {
        boolean result = false;
        try {
            result = mLatch.await(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        return result;
    }
}
