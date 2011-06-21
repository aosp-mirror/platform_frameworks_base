/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.dumprendertree;

import java.util.Vector;
import android.util.*;

public class FileFilter {

    private static final String LOGTAG = "FileFilter";

    // Returns whether we should ignore this test and skip running it.
    // Currently we use this only for tests that crash or hang DumpRenderTree.
    // TODO: Fix these and eliminate this method.
    public static boolean ignoreTest(String file) {
        for (int i = 0; i < ignoreTestList.length; i++) {
            if (file.endsWith(ignoreTestList[i])) {
                Log.v(LOGTAG, "File path in list of ignored tests: " + file);
                return true;
            }
        }
        return false;
    }

    // Returns whether a directory does not contain layout tests and so can be
    // ignored.
    public static boolean isNonTestDir(String file) {
        for (int i = 0; i < nonTestDirs.length; i++) {
            if (file.endsWith(nonTestDirs[i])) {
                return true;
            }
        }
        return false;
    }

    // Returns whether we should ignore the result of this test.
    public static boolean ignoreResult(String file) {
        for (int i = 0; i < ignoreResultList.size(); i++) {
            if (file.endsWith(ignoreResultList.get(i))) {
                Log.v(LOGTAG, "File path in list of ignored results: " + file);
                return true;
            }
        }
        return false;
    }

    final static Vector<String> ignoreResultList = new Vector<String>();

    static {
        fillIgnoreResultList();
    }

    static final String[] nonTestDirs = {
        ".", // ignore hidden directories and files
        "resources", // ignore resource directories
        ".svn", // don't run anything under .svn folder
        "platform"  // No-Android specific tests
    };

    static final String[] ignoreTestList = {
        "canvas/philip/tests/2d.drawImage.broken.html", // blocks test, http://b/2982500
        "editing/selection/move-left-right.html", // Causes DumpRenderTree to hang
        "fast/js/excessive-comma-usage.html", // Tests huge initializer list, causes OOM.
        "fast/js/regexp-charclass-crash.html", // RegExp is too large, causing OOM
        "fast/js/regexp-overflow.html", // Result is too large, causing OOM when reading by DRT, http://b/2697589
        "fast/regex/test1.html", // Causes DumpRenderTree to hang with V8
        "fast/regex/slow.html", // Causes DumpRenderTree to hang with V8
    };

    static void fillIgnoreResultList() {
        // This first block of tests are for features for which Android
        // should pass all tests. They are skipped only temporarily.
        // TODO: Fix these failing tests and remove them from this list.
        ignoreResultList.add("fast/dom/HTMLKeygenElement/keygen.html"); // Missing layoutTestController.shadowRoot()
        ignoreResultList.add("fast/dom/Geolocation/window-close-crash.html"); // Missing layoutTestContoller.setCloseRemainingWindowsWhenComplete()
        ignoreResultList.add("fast/dom/Geolocation/page-reload-cancel-permission-requests.html"); // Missing layoutTestController.numberOfPendingGeolocationPermissionRequests()
        ignoreResultList.add("fast/dom/HTMLLinkElement/link-and-subresource-test.html"); // Missing layoutTestController.dumpResourceResponseMIMETypes()
        ignoreResultList.add("fast/dom/HTMLLinkElement/prefetch.html"); // Missing layoutTestController.dumpResourceResponseMIMETypes()
        ignoreResultList.add("fast/dom/HTMLLinkElement/subresource.html"); // Missing layoutTestController.dumpResourceResponseMIMETypes()
        ignoreResultList.add("fast/encoding/char-decoding.html"); // fails in Java HTTP stack, see http://b/issue?id=3047156
        ignoreResultList.add("fast/encoding/hanarei-blog32-fc2-com.html"); // fails in Java HTTP stack, see http://b/issue?id=3046986
        ignoreResultList.add("fast/encoding/mailto-always-utf-8.html"); // Requires waitForPolicyDelegate(), see http://b/issue?id=3043468
        ignoreResultList.add("fast/encoding/percent-escaping.html"); // fails in Java HTTP stack, see http://b/issue?id=3046984
        ignoreResultList.add("http/tests/appcache/empty-manifest.html"); // flaky
        ignoreResultList.add("http/tests/appcache/fallback.html"); // http://b/issue?id=2713004
        ignoreResultList.add("http/tests/appcache/foreign-fallback.html"); // Flaky, may be due to DRT, see http://b/3285647
        ignoreResultList.add("http/tests/appcache/foreign-iframe-main.html"); // flaky - skips states
        ignoreResultList.add("http/tests/appcache/manifest-with-empty-file.html"); // flaky
        ignoreResultList.add("http/tests/appcache/origin-quota.html"); // needs clearAllApplicationCaches(), see http://b/issue?id=2944196
        ignoreResultList.add("storage/database-lock-after-reload.html"); // Succeeds but DumpRenderTree does not read result correctly
        ignoreResultList.add("storage/hash-change-with-xhr.html"); // Succeeds but DumpRenderTree does not read result correctly
        ignoreResultList.add("storage/open-database-creation-callback-isolated-world.html"); // Requires layoutTestController.evaluateScriptInIsolatedWorld()
        ignoreResultList.add("storage/statement-error-callback-isolated-world.html"); // Requires layoutTestController.evaluateScriptInIsolatedWorld()
        ignoreResultList.add("storage/statement-success-callback-isolated-world.html"); // Requires layoutTestController.evaluateScriptInIsolatedWorld()
        ignoreResultList.add("storage/storageinfo-query-usage.html"); // Need window.webkitStorageInfo
        ignoreResultList.add("storage/transaction-callback-isolated-world.html"); // Requires layoutTestController.evaluateScriptInIsolatedWorld()
        ignoreResultList.add("storage/transaction-error-callback-isolated-world.html"); // Requires layoutTestController.evaluateScriptInIsolatedWorld()
        ignoreResultList.add("storage/transaction-success-callback-isolated-world.html"); // Requires layoutTestController.evaluateScriptInIsolatedWorld()
        ignoreResultList.add("storage/domstorage/localstorage/storagetracker/storage-tracker-1-prepare.html"); // Missing layoutTestController.originsWithLocalStorage()
        ignoreResultList.add("storage/domstorage/localstorage/storagetracker/storage-tracker-2-create.html"); // Missing layoutTestController.originsWithLocalStorage()
        ignoreResultList.add("storage/domstorage/localstorage/storagetracker/storage-tracker-3-delete-all.html"); // Missing layoutTestController.originsWithLocalStorage()
        ignoreResultList.add("storage/domstorage/localstorage/storagetracker/storage-tracker-4-create.html"); // Missing layoutTestController.originsWithLocalStorage()
        ignoreResultList.add("storage/domstorage/localstorage/storagetracker/storage-tracker-5-delete-one.html"); // Missing layoutTestController.originsWithLocalStorage()


        // Expected failures due to unsupported features or tests unsuitable for Android.
        ignoreResultList.add("fast/encoding/char-decoding-mac.html"); // Mac-specific encodings (also marked Won't Fix in Chromium, bug 7388)
        ignoreResultList.add("fast/encoding/char-encoding-mac.html"); // Mac-specific encodings (also marked Won't Fix in Chromium, bug 7388)
        ignoreResultList.add("fast/encoding/idn-security.html"); // Mac-specific IDN checks (also marked Won't Fix in Chromium, bug 21814)
        ignoreResultList.add("fast/events/touch/basic-multi-touch-events.html"); // Requires multi-touch gestures not supported by Android system
        ignoreResultList.add("fast/events/touch/touch-coords-in-zoom-and-scroll.html"); // Requires eventSender.zoomPageIn(),zoomPageOut()
        ignoreResultList.add("fast/events/touch/touch-target.html"); // Requires multi-touch gestures not supported by Android system
        ignoreResultList.add("fast/workers"); // workers not supported
        ignoreResultList.add("http/tests/cookies/third-party-cookie-relaxing.html"); // We don't support conditional acceptance of third-party cookies
        ignoreResultList.add("http/tests/eventsource/workers"); // workers not supported
        ignoreResultList.add("http/tests/workers"); // workers not supported
        ignoreResultList.add("http/tests/xmlhttprequest/workers"); // workers not supported
        ignoreResultList.add("storage/domstorage/localstorage/private-browsing-affects-storage.html"); // private browsing not supported
        ignoreResultList.add("storage/domstorage/sessionstorage/private-browsing-affects-storage.html"); // private browsing not supported
        ignoreResultList.add("storage/indexeddb"); // indexeddb not supported
        ignoreResultList.add("storage/private-browsing-noread-nowrite.html"); // private browsing not supported
        ignoreResultList.add("storage/private-browsing-readonly.html"); // private browsing not supported
        ignoreResultList.add("websocket/tests/workers"); // workers not supported
        ignoreResultList.add("dom/xhtml/level2/html/htmldocument04.xhtml"); // /mnt/sdcard on SR uses lowercase filesystem, this test checks filename and is case senstive.
        ignoreResultList.add("dom/html/level2/html/htmldocument04.html"); // ditto

        // Expected failures due to missing expected results
        ignoreResultList.add("dom/xhtml/level3/core/canonicalform08.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/canonicalform09.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/documentgetinputencoding03.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/entitygetinputencoding02.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/entitygetxmlversion02.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodegetbaseuri05.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodegetbaseuri07.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodegetbaseuri09.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodegetbaseuri10.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodegetbaseuri11.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodegetbaseuri15.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodegetbaseuri17.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodegetbaseuri18.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodelookupnamespaceuri01.xhtml");
        ignoreResultList.add("dom/xhtml/level3/core/nodelookupprefix19.xhtml");

        // TODO: These need to be triaged
        ignoreResultList.add("fast/css/case-transform.html"); // will not fix #619707
        ignoreResultList.add("fast/dom/Element/offsetLeft-offsetTop-body-quirk.html"); // different screen size result in extra spaces in Apple compared to us
        ignoreResultList.add("fast/dom/Window/Plug-ins.html"); // need test plugin
        ignoreResultList.add("fast/dom/Window/window-screen-properties.html"); // pixel depth
        ignoreResultList.add("fast/dom/Window/window-xy-properties.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/dom/attribute-namespaces-get-set.html"); // http://b/733229
        ignoreResultList.add("fast/dom/object-embed-plugin-scripting.html"); // dynamic plugins not supported
        ignoreResultList.add("fast/dom/tabindex-clamp.html"); // there is extra spacing in the file due to multiple input boxes fitting on one line on Apple, ours are wrapped. Space at line ends are stripped.
        ignoreResultList.add("fast/events/anchor-image-scrolled-x-y.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/arrow-navigation.html"); // http://b/735233
        ignoreResultList.add("fast/events/capture-on-target.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/dblclick-addEventListener.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/drag-in-frames.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/drag-outside-window.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/event-view-toString.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/frame-click-focus.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/frame-tab-focus.html"); // http://b/734308
        ignoreResultList.add("fast/events/iframe-object-onload.html"); // there is extra spacing in the file due to multiple frame boxes fitting on one line on Apple, ours are wrapped. Space at line ends are stripped.
        ignoreResultList.add("fast/events/input-image-scrolled-x-y.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseclick-target-and-positioning.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseover-mouseout.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseover-mouseout2.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseup-outside-button.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseup-outside-document.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/onclick-list-marker.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/ondragenter.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/onload-webkit-before-webcore.html"); // missing space in textrun, ok as text is wrapped. ignore. #714933
        ignoreResultList.add("fast/events/option-tab.html"); // http://b/734308
        ignoreResultList.add("fast/events/window-events-bubble.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/window-events-bubble2.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/window-events-capture.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/drag-into-textarea.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/focus-control-to-page.html"); // http://b/716638
        ignoreResultList.add("fast/forms/focus2.html"); // http://b/735111
        ignoreResultList.add("fast/forms/form-data-encoding-2.html"); // charset convert. #516936 ignore, won't fix
        ignoreResultList.add("fast/forms/form-data-encoding.html"); // charset convert. #516936 ignore, won't fix
        ignoreResultList.add("fast/forms/input-appearance-maxlength.html"); // execCommand "insertText" not supported
        ignoreResultList.add("fast/forms/input-select-on-click.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/listbox-onchange.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/listbox-selection.html"); // http://b/735116
        ignoreResultList.add("fast/forms/onselect-textarea.html"); // requires eventSender.mouseMoveTo, mouseDown & mouseUp and abs. position of mouse to select a word. ignore, won't fix #716583
        ignoreResultList.add("fast/forms/onselect-textfield.html"); // requires eventSender.mouseMoveTo, mouseDown & mouseUp and abs. position of mouse to select a word. ignore, won't fix #716583
        ignoreResultList.add("fast/forms/plaintext-mode-1.html"); // not implemented queryCommandEnabled:BackColor, Undo & Redo
        ignoreResultList.add("fast/forms/search-cancel-button-mouseup.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/search-event-delay.html"); // http://b/735120
        ignoreResultList.add("fast/forms/select-empty-list.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/select-type-ahead-non-latin.html"); // http://b/735244
        ignoreResultList.add("fast/forms/selected-index-assert.html"); // not capturing the console messages
        ignoreResultList.add("fast/forms/selection-functions.html"); // there is extra spacing as the text areas and input boxes fit next to each other on Apple, but are wrapped on our screen.
        ignoreResultList.add("fast/forms/textarea-appearance-wrap.html"); // Our text areas are a little thinner than Apples. Also RTL test failes
        ignoreResultList.add("fast/forms/textarea-initial-caret-position.html"); // Text selection done differently on our platform. When a inputbox gets focus, the entire block is selected.
        ignoreResultList.add("fast/forms/textarea-no-scroll-on-blur.html"); // Text selection done differently on our platform. When a inputbox gets focus, the entire block is selected.
        ignoreResultList.add("fast/forms/textarea-paste-newline.html"); // Copy&Paste commands not supported
        ignoreResultList.add("fast/forms/textarea-scrolled-endline-caret.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/frames/iframe-window-focus.html"); // http://b/735140
        ignoreResultList.add("fast/frames/frameElement-widthheight.html"); // screen width&height are different
        ignoreResultList.add("fast/frames/frame-js-url-clientWidth.html"); // screen width&height are different
        ignoreResultList.add("fast/html/tab-order.html"); // http://b/719289
        ignoreResultList.add("fast/js/navigator-mimeTypes-length.html"); // dynamic plugins not supported
        ignoreResultList.add("fast/js/string-capitalization.html"); // http://b/516936
        ignoreResultList.add("fast/loader/local-JavaScript-from-local.html"); // Requires LayoutTests to exist at /tmp/LayoutTests
        ignoreResultList.add("fast/loader/local-iFrame-source-from-local.html"); // Requires LayoutTests to exist at /tmp/LayoutTests
        ignoreResultList.add("fast/loader/opaque-base-url.html"); // extra spacing because iFrames rendered next to each other on Apple
        ignoreResultList.add("fast/overflow/scroll-vertical-not-horizontal.html"); // http://b/735196
        ignoreResultList.add("fast/parser/script-tag-with-trailing-slash.html"); // not capturing the console messages
        ignoreResultList.add("fast/replaced/image-map.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/text/plain-text-line-breaks.html"); // extra spacing because iFrames rendered next to each other on Apple
        ignoreResultList.add("profiler"); // profiler is not supported
    }

}
