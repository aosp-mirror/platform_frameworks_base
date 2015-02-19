/*
* Copyright (C) 2014 The Android Open Source Project
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

package android.content.res;

import android.annotation.Nullable;
import android.app.Activity;
import android.os.Bundle;

import java.lang.ref.WeakReference;

public class ResourceCacheActivity extends Activity {
    static WeakReference<ResourceCacheActivity> lastCreatedInstance;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lastCreatedInstance = new WeakReference<ResourceCacheActivity>(this);
    }

    public static ResourceCacheActivity getLastCreatedInstance() {
        return lastCreatedInstance == null ? null : lastCreatedInstance.get();
    }
}
