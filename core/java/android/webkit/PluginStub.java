/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.Context;
import android.view.View;

/**
 * This interface is used to implement plugins in a WebView. A plugin
 * package may extend this class and implement the abstract functions to create
 * embedded or fullscreeen views displayed in a WebView. The PluginStub
 * implementation will be provided the same NPP instance that is created
 * through the native interface.
 */
public interface PluginStub {

    /**
     * Return a custom embedded view to draw the plugin.
     * @param NPP The native NPP instance.
     * @param context The current application's Context.
     * @return A custom View that will be managed by WebView.
     */
    public abstract View getEmbeddedView(int NPP, Context context);

    /**
     * Return a custom full-screen view to be displayed when the user requests
     * a plugin display as full-screen. Note that the application may choose not
     * to display this View as completely full-screen.
     * @param NPP The native NPP instance.
     * @param context The current application's Context.
     * @return A custom View that will be managed by the application.
     */
    public abstract View getFullScreenView(int NPP, Context context);
}
