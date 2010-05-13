/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.webkit.WebViewCore.EventHub;

/**
 * This class injects accessibility into WebViews with disabled JavaScript or
 * WebViews with enabled JavaScript but for which we have no accessibility
 * script to inject.
 */
class AccessibilityInjector {

    // Handle to the WebView this injector is associated with.
    private final WebView mWebView;

    /**
     * Creates a new injector associated with a given VwebView.
     *
     * @param webView The associated WebView.
     */
    public AccessibilityInjector(WebView webView) {
        mWebView = webView;
    }

    /**
     * Processes a key down <code>event</code>.
     *
     * @return True if the event was processed.
     */
    public boolean onKeyEvent(KeyEvent event) {

        // as a proof of concept let us do the simplest example

        if (event.getAction() != KeyEvent.ACTION_UP) {
            return false;
        }

        int keyCode = event.getKeyCode();

        switch (keyCode) {
            case KeyEvent.KEYCODE_N:
                modifySelection("extend", "forward", "sentence");
                break;
            case KeyEvent.KEYCODE_P:
                modifySelection("extend", "backward", "sentence");
                break;
        }

        return false;
    }

    /**
     * Called when the <code>selectionString</code> has changed.
     */
    public void onSelectionStringChange(String selectionString) {
        // put the selection string in an AccessibilityEvent and send it
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        event.getText().add(selectionString);
        mWebView.sendAccessibilityEventUnchecked(event);
    }

    /**
     * Modifies the current selection.
     *
     * @param alter Specifies how to alter the selection.
     * @param direction The direction in which to alter the selection.
     * @param granularity The granularity of the selection modification.
     */
    private void modifySelection(String alter, String direction, String granularity) {
        WebViewCore webViewCore = mWebView.getWebViewCore();

        if (webViewCore == null) {
            return;
        }

        WebViewCore.ModifySelectionData data = new WebViewCore.ModifySelectionData();
        data.mAlter = alter;
        data.mDirection = direction;
        data.mGranularity = granularity;
        webViewCore.sendMessage(EventHub.MODIFY_SELECTION, data);
    }
}
