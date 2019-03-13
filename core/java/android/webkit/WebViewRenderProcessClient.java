/*
 * Copyright 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Used to receive callbacks on {@link WebView} renderer events.
 *
 * WebViewRenderProcessClient instances may be set or retrieved via {@link
 * WebView#setWebViewRenderProcessClient(WebViewRenderProcessClient)} and {@link
 * WebView#getWebViewRenderProcessClient()}.
 *
 * Instances may be attached to multiple WebViews, and thus a single renderer event may cause
 * a callback to be called multiple times with different WebView parameters.
 */
public abstract class WebViewRenderProcessClient {
    /**
     * Called when the renderer currently associated with {@code view} becomes unresponsive as a
     * result of a long running blocking task such as the execution of JavaScript.
     *
     * <p>If a WebView fails to process an input event, or successfully navigate to a new URL within
     * a reasonable time frame, the renderer is considered to be unresponsive, and this callback
     * will be called.
     *
     * <p>This callback will continue to be called at regular intervals as long as the renderer
     * remains unresponsive. If the renderer becomes responsive again, {@link
     * WebViewRenderProcessClient#onRenderProcessResponsive} will be called once, and this method
     * will not subsequently be called unless another period of unresponsiveness is detected.
     *
     * <p>The minimum interval between successive calls to {@code onRenderProcessUnresponsive} is 5
     * seconds.
     *
     * <p>No action is taken by WebView as a result of this method call. Applications may
     * choose to terminate the associated renderer via the object that is passed to this callback,
     * if in multiprocess mode, however this must be accompanied by correctly handling
     * {@link WebViewClient#onRenderProcessGone} for this WebView, and all other WebViews associated
     * with the same renderer. Failure to do so will result in application termination.
     *
     * @param view The {@link WebView} for which unresponsiveness was detected.
     * @param renderer The {@link WebViewRenderProcess} that has become unresponsive,
     * or {@code null} if WebView is running in single process mode.
     */
    public abstract void onRenderProcessUnresponsive(
            @NonNull WebView view, @Nullable WebViewRenderProcess renderer);

    /**
     * Called once when an unresponsive renderer currently associated with {@code view} becomes
     * responsive.
     *
     * <p>After a WebView renderer becomes unresponsive, which is notified to the application by
     * {@link WebViewRenderProcessClient#onRenderProcessUnresponsive}, it is possible for the
     * blocking renderer task to complete, returning the renderer to a responsive state. In that
     * case, this method is called once to indicate responsiveness.
     *
     * <p>No action is taken by WebView as a result of this method call.
     *
     * @param view The {@link WebView} for which responsiveness was detected.
     *
     * @param renderer The {@link WebViewRenderProcess} that has become responsive, or {@code null}
     *                 if WebView is running in single process mode.
     */
    public abstract void onRenderProcessResponsive(
            @NonNull WebView view, @Nullable WebViewRenderProcess renderer);
}
