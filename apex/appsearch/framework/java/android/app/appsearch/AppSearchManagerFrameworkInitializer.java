/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.app.appsearch;

import android.app.SystemServiceRegistry;
import android.content.Context;

/**
 * This is where the AppSearchManagerService wrapper is registered.
 *
 * TODO(b/142567528): add comments when implement this class
 * @hide
 */
public class AppSearchManagerFrameworkInitializer {

    /**
     * TODO(b/142567528): add comments when implement this class
     */
    public static void initialize() {
        SystemServiceRegistry.registerStaticService(
                Context.APP_SEARCH_SERVICE, AppSearchManager.class,
                (service) -> new AppSearchManager(IAppSearchManager.Stub.asInterface(service)));
    }
}
