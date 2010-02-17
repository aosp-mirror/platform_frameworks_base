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

import java.util.HashSet;
import java.util.Hashtable;
import android.util.*;

public class FileFilter {

    public static boolean ignoreTest(String file) {
      // treat files like directories for the time being.
      for (int i = 0; i < ignoreTestList.length; i ++) {
          if (file.endsWith(ignoreTestList[i])) {
             Log.e("FileFilter", "File path in IgnoreTest: " + file); 
             return true;
          }
      }
      for (int i = 0; i < ignoreTestDirs.length; i++) {
          if (file.endsWith(ignoreTestDirs[i])) {
              Log.e("FileFilter", "File path in ignore list: " + file);
              return true;
          }
      }
      
      return false;
    }
 
    public static boolean ignoreResults(String file) {
        int index = file.indexOf("fast");
        if (index != -1) {
            String sub = file.substring(index);
            if (ignoreResultList.contains(sub))
                return true;
        }
        return false;

    }

    public static String isKnownBug(String file) {
        int index = file.indexOf("fast");
        if (index != -1) {
            String sub = file.substring(index);
            // Log.e("FileFilter", "Looking for:"+sub);
            if (bugList.containsKey(sub))
                return bugList.get(sub);
        }
        return null;
    }

    final static HashSet<String> ignoreResultList = new HashSet<String>();
    final static Hashtable<String, String> bugList = 
        new Hashtable<String, String>();
 
    static {
        fillIgnoreResultSet();
        fillBugTable();
    }

    static final String[] ignoreTestDirs = {
        ".", // ignore hidden directories and files
        "resources", // ignore resource directories
        "AppleScript", // AppleScript not supported
        ".svn", // don't run anything under .svn folder
        "profiler",  // profiler is not supported
        "svg",  // svg is not supported
        "platform",  // platform specific
        "http/wml",
    };

    static final String [] ignoreTestList = {
        "editing/selection/move-left-right.html",
        "fast/events/touch/basic-multi-touch-events.html", // We do not support multi touch events.
        "fast/js/regexp-charclass-crash.html", // RegExp is too large, causing OOM
        "fast/regex/test1.html", // RegExp is exponential
        "fast/regex/slow.html", // RegExp is exponential
        "storage/domstorage/localstorage/iframe-events.html", // Expects test to be in LayoutTests
        "storage/domstorage/localstorage/private-browsing-affects-storage.html", // No notion of private browsing.
        "storage/domstorage/sessionstorage/iframe-events.html", // Expects test to be in LayoutTests
        "storage/domstorage/sessionstorage/private-browsing-affects-storage.html", // No notion of private browsing.
        "storage/private-browsing-readonly.html", // No notion of private browsing.
    };

    static void fillIgnoreResultSet() {
        ignoreResultList.add("fast/css/case-transform.html"); // will not fix #619707
        ignoreResultList.add("fast/css/computed-style.html"); // different platform defaults for font and different screen size
        ignoreResultList.add("fast/dom/Element/offsetLeft-offsetTop-body-quirk.html"); // different screen size result in extra spaces in Apple compared to us
        ignoreResultList.add("fast/dom/Window/Plug-ins.html"); // need test plugin
        ignoreResultList.add("fast/dom/Window/window-properties.html"); // xslt and xpath elements missing from property list
        ignoreResultList.add("fast/dom/Window/window-screen-properties.html"); // pixel depth
        ignoreResultList.add("fast/dom/Window/window-xy-properties.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/dom/character-index-for-point.html"); // requires textInputController.characterIndexForPoint
        ignoreResultList.add("fast/dom/gc-9.html"); // requires xpath support
        ignoreResultList.add("fast/dom/global-constructors.html"); // requires xslt and xpath support
        ignoreResultList.add("fast/dom/object-embed-plugin-scripting.html"); // dynamic plugins not supported
        ignoreResultList.add("fast/dom/tabindex-clamp.html"); // there is extra spacing in the file due to multiple input boxes fitting on one line on Apple, ours are wrapped. Space at line ends are stripped.
        ignoreResultList.add("fast/events/anchor-image-scrolled-x-y.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/capture-on-target.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/dblclick-addEventListener.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/drag-in-frames.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/drag-outside-window.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/event-sender-mouse-click.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/event-view-toString.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/frame-click-focus.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/iframe-object-onload.html"); // there is extra spacing in the file due to multiple frame boxes fitting on one line on Apple, ours are wrapped. Space at line ends are stripped.
        ignoreResultList.add("fast/events/input-image-scrolled-x-y.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseclick-target-and-positioning.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseover-mouseout.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseover-mouseout2.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseup-outside-button.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/mouseup-outside-document.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/objc-event-api.html"); // eventSender.mouseDown(), mouseUp() and objc API missing
        ignoreResultList.add("fast/events/objc-keyboard-event-creation.html"); // eventSender.mouseDown(), mouseUp() and objc API missing
        ignoreResultList.add("fast/events/onclick-list-marker.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/ondragenter.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/onload-webkit-before-webcore.html"); // missing space in textrun, ok as text is wrapped. ignore. #714933
        ignoreResultList.add("fast/events/window-events-bubble.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/window-events-bubble2.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/events/window-events-capture.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/attributed-strings.html"); // missing support for textInputController.makeAttributedString()
        ignoreResultList.add("fast/forms/drag-into-textarea.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/form-data-encoding-2.html"); // charset convert. #516936 ignore, won't fix
        ignoreResultList.add("fast/forms/form-data-encoding.html"); // charset convert. #516936 ignore, won't fix
        ignoreResultList.add("fast/forms/input-appearance-maxlength.html"); // execCommand "insertText" not supported
        ignoreResultList.add("fast/forms/input-select-on-click.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/input-truncate-newline.html"); // Copy&Paste commands not supported
        ignoreResultList.add("fast/forms/listbox-onchange.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/onselect-textarea.html"); // requires eventSender.mouseMoveTo, mouseDown & mouseUp and abs. position of mouse to select a word. ignore, won't fix #716583
        ignoreResultList.add("fast/forms/onselect-textfield.html"); // requires eventSender.mouseMoveTo, mouseDown & mouseUp and abs. position of mouse to select a word. ignore, won't fix #716583
        ignoreResultList.add("fast/forms/plaintext-mode-1.html"); // not implemented queryCommandEnabled:BackColor, Undo & Redo
        ignoreResultList.add("fast/forms/search-cancel-button-mouseup.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/select-empty-list.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/forms/selected-index-assert.html"); // not capturing the console messages
        ignoreResultList.add("fast/forms/selection-functions.html"); // there is extra spacing as the text areas and input boxes fit next to each other on Apple, but are wrapped on our screen.
        ignoreResultList.add("fast/forms/textarea-appearance-wrap.html"); // Our text areas are a little thinner than Apples. Also RTL test failes
        ignoreResultList.add("fast/forms/textarea-hard-linewrap.html"); // Our text areas are a little thinner than Apples
        ignoreResultList.add("fast/forms/textarea-initial-caret-position.html"); // Text selection done differently on our platform. When a inputbox gets focus, the entire block is selected.
        ignoreResultList.add("fast/forms/textarea-no-scroll-on-blur.html"); // Text selection done differently on our platform. When a inputbox gets focus, the entire block is selected.
        ignoreResultList.add("fast/forms/textarea-paste-newline.html"); // Copy&Paste commands not supported
        ignoreResultList.add("fast/forms/textarea-scrolled-endline-caret.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/frames/frameElement-widthheight.html"); // screen width&height are different
        ignoreResultList.add("fast/frames/frame-js-url-clientWidth.html"); // screen width&height are different
        ignoreResultList.add("fast/js/navigator-mimeTypes-length.html"); // dynamic plugins not supported
        ignoreResultList.add("fast/loader/local-JavaScript-from-local.html"); // Requires LayoutTests to exist at /tmp/LayoutTests
        ignoreResultList.add("fast/loader/local-iFrame-source-from-local.html"); // Requires LayoutTests to exist at /tmp/LayoutTests
        ignoreResultList.add("fast/loader/opaque-base-url.html"); // extra spacing because iFrames rendered next to each other on Apple
        ignoreResultList.add("fast/parser/script-tag-with-trailing-slash.html"); // not capturing the console messages
        ignoreResultList.add("fast/replaced/image-map.html"); // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/text/attributed-substring-from-range.html"); //  requires JS test API, textInputController
        ignoreResultList.add("fast/text/attributed-substring-from-range-001.html"); //  requires JS test API, textInputController
        ignoreResultList.add("fast/text/plain-text-line-breaks.html"); // extra spacing because iFrames rendered next to each other on Apple
    }

    static void fillBugTable() {
        bugList.put("fast/forms/focus-control-to-page.html", "716638");
        bugList.put("fast/html/tab-order.html", "719289");
        bugList.put("fast/dom/attribute-namespaces-get-set.html", "733229");
        bugList.put("fast/dom/set-innerHTML.html", "733823");
        bugList.put("fast/dom/xmlhttprequest-get.html", "733846");
        bugList.put("fast/encoding/css-charset-default.html", "733856");
        bugList.put("fast/encoding/default-xhtml-encoding.html", "733882");
        bugList.put("fast/encoding/meta-in-xhtml.html", "733882");
        bugList.put("fast/events/frame-tab-focus.html", "734308");
        bugList.put("fast/events/option-tab.html", "734308");
        bugList.put("fast/forms/focus2.html", "735111");
        bugList.put("fast/forms/listbox-selection.html", "735116");
        bugList.put("fast/forms/search-event-delay.html", "735120");
        bugList.put("fast/frames/iframe-window-focus.html", "735140");
        bugList.put("fast/innerHTML/004.html", "733882");
        bugList.put("fast/js/string-capitalization.html", "516936");
        bugList.put("fast/js/string-concatenate-outofmemory.html","735152");
        bugList.put("fast/parser/external-entities.html", "735176");
        bugList.put("fast/overflow/scroll-vertical-not-horizontal.html", "735196");
        bugList.put("fast/events/arrow-navigation.html", "735233");
        bugList.put("fast/forms/select-type-ahead-non-latin.html", "735244");
    }

}
