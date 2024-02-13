/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.webkit;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.content.Context;

/**
 * Class for performing registration for webviewupdate service.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_UPDATE_SERVICE_IPC_WRAPPER)
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class WebViewBootstrapFrameworkInitializer {
    private WebViewBootstrapFrameworkInitializer() {}

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers webviewupdate
     * service to {@link Context}, so that {@link Context#getSystemService} can return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides
     * {@link SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerForeverStaticService(Context.WEBVIEW_UPDATE_SERVICE,
                WebViewUpdateManager.class,
                (b) -> new WebViewUpdateManager(IWebViewUpdateService.Stub.asInterface(b)));
    }
}
