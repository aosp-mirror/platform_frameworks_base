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
     * @param precomposed True if the url is for a precomposed touch icon.
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
     * Notify the host application that the current page would
     * like to show a custom View.
     * @param view is the View object to be shown.
     * @param callback is the callback to be invoked if and when the view
     * is dismissed.
     */
    public void onShowCustomView(View view, CustomViewCallback callback) {};

    /**
     * Notify the host application that the current page would
     * like to hide its custom view.
     */
    public void onHideCustomView() {}

    /**
     * Request the host application to create a new Webview. The host
     * application should handle placement of the new WebView in the view
     * system. The default behavior returns null.
     * @param view The WebView that initiated the callback.
     * @param dialog True if the new window is meant to be a small dialog
     *               window.
     * @param userGesture True if the request was initiated by a user gesture
     *                    such as clicking a link.
     * @param resultMsg The message to send when done creating a new WebView.
     *                  Set the new WebView through resultMsg.obj which is 
     *                  WebView.WebViewTransport() and then call
     *                  resultMsg.sendToTarget();
     * @return Similar to javscript dialogs, this method should return true if
     *         the client is going to handle creating a new WebView. Note that
     *         the WebView will halt processing if this method returns true so
     *         make sure to call resultMsg.sendToTarget(). It is undefined
     *         behavior to call resultMsg.sendToTarget() after returning false
     *         from this method.
     */
    public boolean onCreateWindow(WebView view, boolean dialog,
            boolean userGesture, Message resultMsg) {
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
     * @param window The WebView that needs to be closed.
     */
    public void onCloseWindow(WebView window) {}

    /**
     * Tell the client to display a javascript alert dialog.  If the client
     * returns true, WebView will assume that the client will handle the
     * dialog.  If the client returns false, it will continue execution.
     * @param view The WebView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param result A JsResult to confirm that the user hit enter.
     * @return boolean Whether the client will handle the alert dialog.
     */
    public boolean onJsAlert(WebView view, String url, String message,
            JsResult result) {
        return false;
    }

    /**
     * Tell the client to display a confirm dialog to the user. If the client
     * returns true, WebView will assume that the client will handle the
     * confirm dialog and call the appropriate JsResult method. If the
     * client returns false, a default value of false will be returned to
     * javascript. The default behavior is to return false.
     * @param view The WebView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param result A JsResult used to send the user's response to
     *               javascript.
     * @return boolean Whether the client will handle the confirm dialog.
     */
    public boolean onJsConfirm(WebView view, String url, String message,
            JsResult result) {
        return false;
    }

    /**
     * Tell the client to display a prompt dialog to the user. If the client
     * returns true, WebView will assume that the client will handle the
     * prompt dialog and call the appropriate JsPromptResult method. If the
     * client returns false, a default value of false will be returned to to
     * javascript. The default behavior is to return false.
     * @param view The WebView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param defaultValue The default value displayed in the prompt dialog.
     * @param result A JsPromptResult used to send the user's reponse to
     *               javascript.
     * @return boolean Whether the client will handle the prompt dialog.
     */
    public boolean onJsPrompt(WebView view, String url, String message,
            String defaultValue, JsPromptResult result) {
        return false;
    }

    /**
     * Tell the client to display a dialog to confirm navigation away from the
     * current page. This is the result of the onbeforeunload javascript event.
     * If the client returns true, WebView will assume that the client will
     * handle the confirm dialog and call the appropriate JsResult method. If
     * the client returns false, a default value of true will be returned to
     * javascript to accept navigation away from the current page. The default
     * behavior is to return false. Setting the JsResult to true will navigate
     * away from the current page, false will cancel the navigation.
     * @param view The WebView that initiated the callback.
     * @param url The url of the page requesting the dialog.
     * @param message Message to be displayed in the window.
     * @param result A JsResult used to send the user's response to
     *               javascript.
     * @return boolean Whether the client will handle the confirm dialog.
     */
    public boolean onJsBeforeUnload(WebView view, String url, String message,
            JsResult result) {
        return false;
    }

   /**
    * Tell the client that the database quota for the origin has been exceeded.
    * @param url The URL that triggered the notification
    * @param databaseIdentifier The identifier of the database that caused the
    *     quota overflow.
    * @param currentQuota The current quota for the origin.
    * @param estimatedSize The estimated size of the database.
    * @param totalUsedQuota is the sum of all origins' quota.
    * @param quotaUpdater A callback to inform the WebCore thread that a new
    *     quota is available. This callback must always be executed at some
    *     point to ensure that the sleeping WebCore thread is woken up.
    */
    public void onExceededDatabaseQuota(String url, String databaseIdentifier,
        long currentQuota, long estimatedSize, long totalUsedQuota,
        WebStorage.QuotaUpdater quotaUpdater) {
        // This default implementation passes the current quota back to WebCore.
        // WebCore will interpret this that new quota was declined.
        quotaUpdater.updateQuota(currentQuota);
    }

   /**
    * Tell the client that the Application Cache has exceeded its max size.
    * @param spaceNeeded is the amount of disk space that would be needed
    * in order for the last appcache operation to succeed.
    * @param totalUsedQuota is the sum of all origins' quota.
    * @param quotaUpdater A callback to inform the WebCore thread that a new
    * app cache size is available. This callback must always be executed at
    * some point to ensure that the sleeping WebCore thread is woken up.
    */
    public void onReachedMaxAppCacheSize(long spaceNeeded, long totalUsedQuota,
            WebStorage.QuotaUpdater quotaUpdater) {
        quotaUpdater.updateQuota(0);
    }

    /**
     * Instructs the client to show a prompt to ask the user to set the
     * Geolocation permission state for the specified origin.
     */
    public void onGeolocationPermissionsShowPrompt(String origin,
            GeolocationPermissions.Callback callback) {}

    /**
     * Instructs the client to hide the Geolocation permissions prompt.
     */
    public void onGeolocationPermissionsHidePrompt() {}

    /**
     * Tell the client that a JavaScript execution timeout has occured. And the
     * client may decide whether or not to interrupt the execution. If the
     * client returns true, the JavaScript will be interrupted. If the client
     * returns false, the execution will continue. Note that in the case of
     * continuing execution, the timeout counter will be reset, and the callback
     * will continue to occur if the script does not finish at the next check
     * point.
     * @return boolean Whether the JavaScript execution should be interrupted.
     */
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
     * @return true if the message is handled by the client.
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
     * @return Bitmap The image to use as a default poster, or null if no such image is
     * available.
     */
    public Bitmap getDefaultVideoPoster() {
        return null;
    }

    /**
     * When the user starts to playback a video element, it may take time for enough
     * data to be buffered before the first frames can be rendered. While this buffering
     * is taking place, the ChromeClient can use this function to provide a View to be
     * displayed. For example, the ChromeClient could show a spinner animation.
     *
     * @return View The View to be displayed whilst the video is loading.
     */
    public View getVideoLoadingProgressView() {
        return null;
    }

    /** Obtains a list of all visited history items, used for link coloring
     */
    public void getVisitedHistory(ValueCallback<String[]> callback) {
    }

    /**
     * Tell the client to open a file chooser.
     * @param uploadFile A ValueCallback to set the URI of the file to upload.
     *      onReceiveValue must be called to wake up the thread.
     * @hide
     */
    public void openFileChooser(ValueCallback<Uri> uploadFile) {
        uploadFile.onReceiveValue(null);
    }
}
