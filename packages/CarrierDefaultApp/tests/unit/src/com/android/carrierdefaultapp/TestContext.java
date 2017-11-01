/*
 * Copyright (C) 2016 Google Inc.
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
package com.android.carrierdefaultapp;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;

import java.util.HashMap;

public class TestContext extends ContextWrapper {

    private final String TAG = this.getClass().getSimpleName();

    private HashMap<String, Object> mInjectedSystemServices = new HashMap<>();

    public TestContext(Context base) {
        super(base);
    }

    public <S> void injectSystemService(Class<S> cls, S service) {
        final String name = getSystemServiceName(cls);
        mInjectedSystemServices.put(name, service);
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Object getSystemService(String name) {
        if (mInjectedSystemServices.containsKey(name)) {
            Log.d(TAG, "return mocked system service for " + name);
            return mInjectedSystemServices.get(name);
        }
        Log.d(TAG, "return real system service for " + name);
        return super.getSystemService(name);
    }

    public static void waitForMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }
}
