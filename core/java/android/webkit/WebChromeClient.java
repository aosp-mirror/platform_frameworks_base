/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Message;
import android.view.View;

public class WebChromeClient {

    /**
     * Tell the host application the current progress of loading a page.
     * @param view The WebView that initiated the callback.
     * @param newProgress Current page loading progress, represented by
     *                    an integer between 0 and 100.
     */
    public void onProgressChanged(WebView view, int newProgress) {}

    /**
     * Notify the host application of a change in the document title.
     * @param view The WebView that initiated the callback.
     * @param title A String containing the new title of the document.
     */
    public void onReceivedTitle(WebView view, String title) {}

    /**
     * Notify the host application of a new favicon for the current page.
     * @param view The WebView that initiated the callback.
     * @param icon A Bitmap containing the favicon for the current page.
     */
    public void onReceivedIcon(WebView view, Bitmap icon) {}

    /**
     * Notify the host application of the url for an apple-touch-icon.
     * @param view The WebView that initiated the callback.
     * @param url The icon url.
     * @param precomposed {@code true} if the url is for a precomposed touch icon.
     */
    public void onReceivedTouchIconUrl(WebView view, String url,
            boolean precomposed) {}

    /**
     * A callback interface used by the host application to notify
     * the current page that its custom view has been dismissed.
     */
    public interface CustomViewCallback {
        /**
         * Invoked when the host application dismisses the
         * custom view.
         */
        public void onCustomViewHidden();
    }

    /**
     * Notify the host application that the current page has entered full screen mode. After this
     * call, web content will no longer be rendered in the WebView, but will instead be rendered
     * in {@code view}. The host application should add this View to a Window which is configured
     * with {@link android.view.WindowManager.LayoutParams#FLAG_FULLSCREEN} flag in order to
     * actually display this web content full screen.
     *
     * <p>The application may explicitly exit fullscreen mode by invoking {@code callback} (ex. when
     * the user presses the back button). However, this is generally not necessary as the web page
     * will often show its own UI to close out of fullscreen. Regardless of how the WebView exits
     * fullscreen mode, WebView will invoke {@link #onHideCustomView()}, signaling for the
     * application to remove the custom View.
     *
     * <p>If this method is not overridden, WebView will report to the web page it does not support
     * fullscreen mode and will not honor the web page's request to run in fullscreen mode.
     *
     * <p class="note"><b>Note:</b> if overriding this method, the application must also override
     * {@link #onHideCustomView()}.
     *
     * @param view is the View object to be shown.
     * @param callback invoke this callback to request the page to exit
     * full screen mode.
     */
    public void onShowCustomView(View view, CustomViewCallback callback) {};

    /**
     * Notify the host application that the current page would
     * like to show a custom View in a particular orientation.
     * @param view is the View object to be shown.
     * @param requestedOrientation An orientation constant as used in
     * {@link ActivityInfo#screenOrientation ActivityInfo.screenOrientation}.
     * @param callback is the callback to be invoked if and when the view
     * is dismissed.
     * @deprecated This method supports the obsolete plugin mechanism,
     * and will not be invoked in future
     */
    @Deprecated
    public void onShowCustomView(View view, int requestedOrientation,
            CustomViewCallback callback) {};

    /**
     * Notify the host application that the current page has exited full screen mode. The host
     * application must hide the custom View (the View which was previously passed to {@link
     * #onShowCustomView(View, CustomViewCallback) onShowCustomView()}). After this call, web
     * content will render in the original WebView again.
     *
     * <p class="note"><b>Note:</b> if overriding this method, the application must also override
     * {@link #onShowCustomView(View, CustomViewCallback) onShowCustomView()}.
     */
    public void onHideCustomView() {}

    /**
     * Request the host application to create a new window. If the host
     * application chooses to honor this request, it should return {@code true} from
     * this method, create a new WebView to host the window, insert it into the
     * View system and send the supplied resultMsg message to its target with
     * the new WebView as an argument. If the host application chooses not to
     * honor the request, it should return {@code false} from this method. The default
     * implementation of this method does nothing and hence returns {@code false}.
     * <p>
     * Applications should typically not allow windows to be created when the
     * {@code isUserGesture} flag is false, as this may be an unwanted popup.
     * <p>
     * Applications should be careful how they display the new window: don't simply
     * overlay it over the existing WebView as this may mislead the user about which
     * site they are viewing. If your application displays the URL of the main page,
     * make sure to also display the URL of the new window in a similar fashion. If
     * your application does not display URLs, consider disallowing the creation of
     * new windows entirely.
     * <p class="note"><b>Note:</b> There is no trustworthy way to tell which page
     * requested the new window: the request might originate from a third-party iframe
     * inside the WebView.
     *
     * @param view The WebView from which the request for a new window
     *             originated.
     * @param isDialog {@code true} if the new window should be a dialog, rather than
     *                 a full-size window.
     * @param isUserGesture {@code true} if the request was initiated by a user gesture,
     *                      such as the user clicking a link.
     * @param resultMsg The message to send when once a new WebView has been
     *                  created. resultMsg.obj is a
     *                  {@link WebView.WebViewTransport} object. This should be
     *                  used to transport the new WebView, by calling
     *                  {@link WebView.WebViewTransport#setWebView(WebView)
     *                  WebView.WebViewTransport.setWebView(WebView)}.
     * @return This method should return {@code true} if the host application will
     *         create a new window, in which case resultMsg should be sent to
     *         its target. Otherwise, this method should return {@code false}. Returning
     *         {@code false} from this method but also sending resultMsg will result in
     *         undefined behavior.
     */
    public boolean onCreateWindow(WebView view, boolean isDialog,
            boolean isUserGesture, Message resultMsg) {
        return false;
    }

    /**
     * Request display and focus for this WebView. This may happen due to
     * another WebView opening a link in this WebView and requesting that this
     * WebView be displayed.
     * @param view The WebView that needs to be focused.
     */
    public void onRequestFocus(WebView view) {}

    /**
     * Notify the host application to close the given WebView and remove it
     * from the view system if necessary. At this point, WebCore has stopped
     * any loading in this window and has removed any cross-scripting ability
     * in javascript.
     * <p>
     * As with {@link #onCreateWindow}, the application should ensure that any
     * URL or security indicator displayed is updated so that the user can tell
     * that the page they were interacting with has been closed.
     *
     * @param window The WebView that needs to be closed.
     */
    public void onCloseWindow(WebView window) {}

    /**
     * Notify the host application that the web page wants to display a
     * JavaScript {@code alert()} dialog.
     * <p>The default behavior if this method returns {@code false} or is not
     * overridden is to show a dialog containing the alert message and suspend
     * JavaScript execution until the dialog is dismissed.
     * <p>To show a custom dialog, the app should return {@code true} from this
     * method, in which case the default dialog will not be shown and JavaScript
     * execution will be suspended. The app should call
     * {@code JsResult.confirm()} when the custom dialog is dismissed such that
     * JavaScript execution can be resumed.
     * <p>To suppress the dialog and allow JavaScript execution to
     * continue, call {@code JsResult.confirm()} immediately and then return
     * {@code true}.
     * <p>Note that if the {@link WebChromeClient} is set to be {@code null},
     * or if {@link WebChromeClient} is not set at all, the default dialog will
     * be suppressed and Javascript execution will continue immediately.
     * <p>Note that the default dialog does not inherit the {@link
     * android.view.Display#FLAG_SECURE} flag from the parent window.
     *
     * @param view The WebView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param result A JsResult to confirm that the user closed the window.
     * @return boolean {@code true} if the request is handled or ignored.
     * {@code false} if WebView needs to show the default dialog.
     */
    public boolean onJsAlert(WebView view, String url, String message,
            JsResult result) {
        return false;
    }

    /**
     * Notify the host application that the web page wants to display a
     * JavaScript {@code confirm()} dialog.
     * <p>The default behavior if this method returns {@code false} or is not
     * overridden is to show a dialog containing the message and suspend
     * JavaScript execution until the dialog is dismissed. The default dialog
     * will return {@code true} to the JavaScript {@code confirm()} code when
     * the user presses the 'confirm' button, and will return {@code false} to
     * the JavaScript code when the user presses the 'cancel' button or
     * dismisses the dialog.
     * <p>To show a custom dialog, the app should return {@code true} from this
     * method, in which case the default dialog will not be shown and JavaScript
     * execution will be suspended. The app should call
     * {@code JsResult.confirm()} or {@code JsResult.cancel()} when the custom
     * dialog is dismissed.
     * <p>To suppress the dialog and allow JavaScript execution to continue,
     * call {@code JsResult.confirm()} or {@code JsResult.cancel()} immediately
     * and then return {@code true}.
     * <p>Note that if the {@link WebChromeClient} is set to be {@code null},
     * or if {@link WebChromeClient} is not set at all, the default dialog will
     * be suppressed and the default value of {@code false} will be returned to
     * the JavaScript code immediately.
     * <p>Note that the default dialog does not inherit the {@link
     * android.view.Display#FLAG_SECURE} flag from the parent window.
     *
     * @param view The WebView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param result A JsResult used to send the user's response to
     *               javascript.
     * @return boolean {@code true} if the request is handled or ignored.
     * {@code false} if WebView needs to show the default dialog.
     */
    public boolean onJsConfirm(WebView view, String url, String message,
            JsResult result) {
        return false;
    }

    /**
     * Notify the host application that the web page wants to display a
     * JavaScript {@code prompt()} dialog.
     * <p>The default behavior if this method returns {@code false} or is not
     * overridden is to show a dialog containing the message and suspend
     * JavaScript execution until the dialog is dismissed. Once the dialog is
     * dismissed, JavaScript {@code prompt()} will return the string that the
     * user typed in, or null if the user presses the 'cancel' button.
     * <p>To show a custom dialog, the app should return {@code true} from this
     * method, in which case the default dialog will not be shown and JavaScript
     * execution will be suspended. The app should call
     * {@code JsPromptResult.confirm(result)} when the custom dialog is
     * dismissed.
     * <p>To suppress the dialog and allow JavaScript execution to continue,
     * call {@code JsPromptResult.confirm(result)} immediately and then
     * return {@code true}.
     * <p>Note that if the {@link WebChromeClient} is set to be {@code null},
     * or if {@link WebChromeClient} is not set at all, the default dialog will
     * be suppressed and {@code null} will be returned to the JavaScript code
     * immediately.
     * <p>Note that the default dialog does not inherit the {@link
     * android.view.Display#FLAG_SECURE} flag from the parent window.
     *
     * @param view The WebView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param defaultValue The default value displayed in the prompt dialog.
     * @param result A JsPromptResult used to send the user's reponse to
     *               javascript.
     * @return boolean {@code true} if the request is handled or ignored.
     * {@code false} if WebView needs to show the default dialog.
     */
    public boolean onJsPrompt(WebView view, String url, String message,
            String defaultValue, JsPromptResult result) {
        return false;
    }

    /**
     * Notify the host application that the web page wants to confirm navigation
     * from JavaScript {@code onbeforeunload}.
     * <p>The default behavior if this method returns {@code false} or is not
     * overridden is to show a dialog containing the message and suspend
     * JavaScript execution until the dialog is dismissed. The default dialog
     * will continue the navigation if the user confirms the navigation, and
     * will stop the navigation if the user wants to stay on the current page.
     * <p>To show a custom dialog, the app should return {@code true} from this
     * method, in which case the default dialog will not be shown and JavaScript
     * execution will be suspended. When the custom dialog is dismissed, the
     * app should call {@code JsResult.confirm()} to continue the navigation or,
     * {@code JsResult.cancel()} to stay on the current page.
     * <p>To suppress the dialog and allow JavaScript execution to continue,
     * call {@code JsResult.confirm()} or {@code JsResult.cancel()} immediately
     * and then return {@code true}.
     * <p>Note that if the {@link WebChromeClient} is set to be {@code null},
     * or if {@link WebChromeClient} is not set at all, the default dialog will
     * be suppressed and the navigation will be resumed immediately.
     * <p>Note that the default dialog does not inherit the {@link
     * android.view.Display#FLAG_SECURE} flag from the parent window.
     *
     * @param view The WebView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param result A JsResult used to send the user's response to
     *               javascript.
     * @return boolean {@code true} if the request is handled or ignored.
     * {@code false} if WebView needs to show the default dialog.
     */
    public boolean onJsBeforeUnload(WebView view, String url, String message,
            JsResult result) {
        return false;
    }

   /**
    * Tell the client that the quota has been exceeded for the Web SQL Database
    * API for a particular origin and request a new quota. The client must
    * respond by invoking the
    * {@link WebStorage.QuotaUpdater#updateQuota(long) updateQuota(long)}
    * method of the supplied {@link WebStorage.QuotaUpdater} instance. The
    * minimum value that can be set for the new quota is the current quota. The
    * default implementation responds with the current quota, so the quota will
    * not be increased.
    * @param url The URL of the page that triggered the notification
    * @param databaseIdentifier The identifier of the database where the quota
    *                           was exceeded.
    * @param quota The quota for the origin, in bytes
    * @param estimatedDatabaseSize The estimated size of the offending
    *                              database, in bytes
    * @param totalQuota The total quota for all origins, in bytes
    * @param quotaUpdater An instance of {@link WebStorage.QuotaUpdater} which
    *                     must be used to inform the WebView of the new quota.
    * @deprecated This method is no longer called; WebView now uses the HTML5 / JavaScript Quota
    *             Management API.
    */
    @Deprecated
    public void onExceededDatabaseQuota(String url, String databaseIdentifier,
            long quota, long estimatedDatabaseSize, long totalQuota,
            WebStorage.QuotaUpdater quotaUpdater) {
        // This default implementation passes the current quota back to WebCore.
        // WebCore will interpret this that new quota was declined.
        quotaUpdater.updateQuota(quota);
    }

   /**
    * Notify the host application that the Application Cache has reached the
    * maximum size. The client must respond by invoking the
    * {@link WebStorage.QuotaUpdater#updateQuota(long) updateQuota(long)}
    * method of the supplied {@link WebStorage.QuotaUpdater} instance. The
    * minimum value that can be set for the new quota is the current quota. The
    * default implementation responds with the current quota, so the quota will
    * not be increased.
    * @param requiredStorage The amount of storage required by the Application
    *                        Cache operation that triggered this notification,
    *                        in bytes.
    * @param quota the current maximum Application Cache size, in bytes
    * @param quotaUpdater An instance of {@link WebStorage.QuotaUpdater} which
    *                     must be used to inform the WebView of the new quota.
    * @deprecated This method is no longer called; WebView now uses the HTML5 / JavaScript Quota
    *             Management API.
    * @removed This method is no longer called; WebView now uses the HTML5 / JavaScript Quota
    *          Management API.
    */
    @Deprecated
    public void onReachedMaxAppCacheSize(long requiredStorage, long quota,
            WebStorage.QuotaUpdater quotaUpdater) {
        quotaUpdater.updateQuota(quota);
    }

    /**
     * Notify the host application that web content from the specified origin
     * is attempting to use the Geolocation API, but no permission state is
     * currently set for that origin. The host application should invoke the
     * specified callback with the desired permission state. See
     * {@link GeolocationPermissions} for details.
     *
     * <p>Note that for applications targeting Android N and later SDKs
     * (API level > {@link android.os.Build.VERSION_CODES#M})
     * this method is only called for requests originating from secure
     * origins such as https. On non-secure origins geolocation requests
     * are automatically denied.
     *
     * @param origin The origin of the web content attempting to use the
     *               Geolocation API.
     * @param callback The callback to use to set the permission state for the
     *                 origin.
     */
    public void onGeolocationPermissionsShowPrompt(String origin,
            GeolocationPermissions.Callback callback) {}

    /**
     * Notify the host application that a request for Geolocation permissions,
     * made with a previous call to
     * {@link #onGeolocationPermissionsShowPrompt(String,GeolocationPermissions.Callback) onGeolocationPermissionsShowPrompt()}
     * has been canceled. Any related UI should therefore be hidden.
     */
    public void onGeolocationPermissionsHidePrompt() {}

    /**
     * Notify the host application that web content is requesting permission to
     * access the specified resources and the permission currently isn't granted
     * or denied. The host application must invoke {@link PermissionRequest#grant(String[])}
     * or {@link PermissionRequest#deny()}.
     *
     * If this method isn't overridden, the permission is denied.
     *
     * @param request the PermissionRequest from current web content.
     */
    public void onPermissionRequest(PermissionRequest request) {
        request.deny();
    }

    /**
     * Notify the host application that the given permission request
     * has been canceled. Any related UI should therefore be hidden.
     *
     * @param request the PermissionRequest that needs be canceled.
     */
    public void onPermissionRequestCanceled(PermissionRequest request) {}

    /**
     * Tell the client that a JavaScript execution timeout has occured. And the
     * client may decide whether or not to interrupt the execution. If the
     * client returns {@code true}, the JavaScript will be interrupted. If the client
     * returns {@code false}, the execution will continue. Note that in the case of
     * continuing execution, the timeout counter will be reset, and the callback
     * will continue to occur if the script does not finish at the next check
     * point.
     * @return boolean Whether the JavaScript execution should be interrupted.
     * @deprecated This method is no longer supported and will not be invoked.
     */
    // This method was only called when using the JSC javascript engine. V8 became
    // the default JS engine with Froyo and support for building with JSC was
    // removed in b/5495373. V8 does not have a mechanism for making a callback such
    // as this.
    @Deprecated
    public boolean onJsTimeout() {
        return true;
    }

    /**
     * Report a JavaScript error message to the host application. The ChromeClient
     * should override this to process the log message as they see fit.
     * @param message The error message to report.
     * @param lineNumber The line number of the error.
     * @param sourceID The name of the source file that caused the error.
     * @deprecated Use {@link #onConsoleMessage(ConsoleMessage) onConsoleMessage(ConsoleMessage)}
     *      instead.
     */
    @Deprecated
    public void onConsoleMessage(String message, int lineNumber, String sourceID) { }

    /**
     * Report a JavaScript console message to the host application. The ChromeClient
     * should override this to process the log message as they see fit.
     * @param consoleMessage Object containing details of the console message.
     * @return {@code true} if the message is handled by the client.
     */
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        // Call the old version of this function for backwards compatability.
        onConsoleMessage(consoleMessage.message(), consoleMessage.lineNumber(),
                consoleMessage.sourceId());
        return false;
    }

    /**
     * When not playing, video elements are represented by a 'poster' image. The
     * image to use can be specified by the poster attribute of the video tag in
     * HTML. If the attribute is absent, then a default poster will be used. This
     * method allows the ChromeClient to provide that default image.
     *
     * @return Bitmap The image to use as a default poster, or {@code null} if no such image is
     * available.
     */
    @Nullable
    public Bitmap getDefaultVideoPoster() {
        return null;
    }

    /**
     * Obtains a View to be displayed while buffering of full screen video is taking
     * place. The host application can override this method to provide a View
     * containing a spinner or similar.
     *
     * @return View The View to be displayed whilst the video is loading.
     */
    @Nullable
    public View getVideoLoadingProgressView() {
        return null;
    }

    /** Obtains a list of all visited history items, used for link coloring
     */
    public void getVisitedHistory(ValueCallback<String[]> callback) {
    }

    /**
     * Tell the client to show a file chooser.
     *
     * This is called to handle HTML forms with 'file' input type, in response to the
     * user pressing the "Select File" button.
     * To cancel the request, call <code>filePathCallback.onReceiveValue(null)</code> and
     * return {@code true}.
     *
     * <p class="note"><b>Note:</b> WebView does not enforce any restrictions on
     * the chosen file(s). WebView can access all files that your app can access.
     * In case the file(s) are chosen through an untrusted source such as a third-party
     * app, it is your own app's responsibility to check what the returned Uris
     * refer to before calling the <code>filePathCallback</code>. See
     * {@link #createIntent} and {@link #parseResult} for more details.</p>
     *
     * @param webView The WebView instance that is initiating the request.
     * @param filePathCallback Invoke this callback to supply the list of paths to files to upload,
     *                         or {@code null} to cancel. Must only be called if the
     *                         {@link #onShowFileChooser} implementation returns {@code true}.
     * @param fileChooserParams Describes the mode of file chooser to be opened, and options to be
     *                          used with it.
     * @return {@code true} if filePathCallback will be invoked, {@code false} to use default
     *         handling.
     *
     * @see FileChooserParams
     */
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
            FileChooserParams fileChooserParams) {
        return false;
    }

    /**
     * Parameters used in the {@link #onShowFileChooser} method.
     */
    public static abstract class FileChooserParams {
        /** Open single file. Requires that the file exists before allowing the user to pick it. */
        public static final int MODE_OPEN = 0;
        /** Like Open but allows multiple files to be selected. */
        public static final int MODE_OPEN_MULTIPLE = 1;
        /** Like Open but allows a folder to be selected. The implementation should enumerate
            all files selected by this operation.
            This feature is not supported at the moment.
            @hide */
        public static final int MODE_OPEN_FOLDER = 2;
        /**  Allows picking a nonexistent file and saving it. */
        public static final int MODE_SAVE = 3;

        /**
         * Parse the result returned by the file picker activity. This method should be used with
         * {@link #createIntent}. Refer to {@link #createIntent} for how to use it.
         *
         * <p class="note"><b>Note:</b> The intent returned by the file picker activity
         * should be treated as untrusted. A third-party app handling the implicit
         * intent created by {@link #createIntent} might return Uris that the third-party
         * app itself does not have access to, such as your own app's sensitive data files.
         * WebView does not enforce any restrictions on the returned Uris. It is the
         * app's responsibility to ensure that the untrusted source (such as a third-party
         * app) has access the Uris it has returned and that the Uris are not pointing
         * to any sensitive data files.</p>
         *
         * @param resultCode the integer result code returned by the file picker activity.
         * @param data the intent returned by the file picker activity.
         * @return the Uris of selected file(s) or {@code null} if the resultCode indicates
         *         activity canceled or any other error.
         */
        @Nullable
        public static Uri[] parseResult(int resultCode, Intent data) {
            return WebViewFactory.getProvider().getStatics().parseFileChooserResult(resultCode, data);
        }

        /**
         * Returns file chooser mode.
         */
        public abstract int getMode();

        /**
         * Returns an array of acceptable MIME types. The returned MIME type
         * could be partial such as audio/*. The array will be empty if no
         * acceptable types are specified.
         */
        public abstract String[] getAcceptTypes();

        /**
         * Returns preference for a live media captured value (e.g. Camera, Microphone).
         * True indicates capture is enabled, {@code false} disabled.
         *
         * Use <code>getAcceptTypes</code> to determine suitable capture devices.
         */
        public abstract boolean isCaptureEnabled();

        /**
         * Returns the title to use for this file selector. If {@code null} a default title should
         * be used.
         */
        @Nullable
        public abstract CharSequence getTitle();

        /**
         * The file name of a default selection if specified, or {@code null}.
         */
        @Nullable
        public abstract String getFilenameHint();

        /**
         * Creates an intent that would start a file picker for file selection.
         * The Intent supports choosing files from simple file sources available
         * on the device. Some advanced sources (for example, live media capture)
         * may not be supported and applications wishing to support these sources
         * or more advanced file operations should build their own Intent.
         *
         * <p>How to use:
         * <ol>
         *   <li>Build an intent using {@link #createIntent}</li>
         *   <li>Fire the intent using {@link android.app.Activity#startActivityForResult}.</li>
         *   <li>Check for ActivityNotFoundException and take a user friendly action if thrown.</li>
         *   <li>Listen the result using {@link android.app.Activity#onActivityResult}</li>
         *   <li>Parse the result using {@link #parseResult} only if media capture was not
         *   requested.</li>
         *   <li>Send the result using filePathCallback of {@link
         *   WebChromeClient#onShowFileChooser}</li>
         * </ol>
         *
         * <p class="note"><b>Note:</b> The created intent may be handled by
         * third-party applications on device. The received result must be treated
         * as untrusted as it can contain Uris pointing to your own app's sensitive
         * data files. Your app should check the resultant Uris in {@link #parseResult}
         * before calling the <code>filePathCallback</code>.</p>
         *
         * @return an Intent that supports basic file chooser sources.
         */
        public abstract Intent createIntent();
    }

    /**
     * Tell the client to open a file chooser.
     * @param uploadFile A ValueCallback to set the URI of the file to upload.
     *      onReceiveValue must be called to wake up the thread.a
     * @param acceptType The value of the 'accept' attribute of the input tag
     *         associated with this file picker.
     * @param capture The value of the 'capture' attribute of the input tag
     *         associated with this file picker.
     *
     * @deprecated Use {@link #onShowFileChooser} instead.
     * @hide This method was not published in any SDK version.
     */
    @SystemApi
    @Deprecated
    public void openFileChooser(ValueCallback<Uri> uploadFile, String acceptType, String capture) {
        uploadFile.onReceiveValue(null);
    }
}
