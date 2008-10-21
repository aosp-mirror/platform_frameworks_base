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
import android.os.Message;

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
}
