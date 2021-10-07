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

import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.app.appsearch.aidl.IAppSearchManager;
import android.content.Context;

/**
 * Class holding initialization code for the AppSearch module.
 *
 * @hide
 */
@SystemApi
public class AppSearchManagerFrameworkInitializer {
    private AppSearchManagerFrameworkInitializer() {}

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers all AppSearch
     * services to {@link Context}, so that {@link Context#getSystemService} can return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides
     *     {@link SystemServiceRegistry}
     */
    public static void initialize() {
        SystemServiceRegistry.registerContextAwareService(
                Context.APP_SEARCH_SERVICE, AppSearchManager.class,
                (context, service) ->
                        new AppSearchManager(context, IAppSearchManager.Stub.asInterface(service)));
    }
}
