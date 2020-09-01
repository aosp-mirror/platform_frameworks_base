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

package com.android.example.loaders;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

public class LoaderActivityIsolated extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        final String loaderPresentOnAttach =
                newBase.getResources().getString(R.string.loader_present);
        if (loaderPresentOnAttach == null || !loaderPresentOnAttach.endsWith("true")) {
            throw new AssertionError("Loader not present in attachBaseContext");
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_isolated);
    }
}
