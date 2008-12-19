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
      int size = ignoreTestList.length;
      for (int i = 0; i < size; i ++) {
          if (file.startsWith(ignoreTestList[i])) {
             Log.e("FileFilter", "File path in IgnoreTest: " + file); 
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
 
    static final String [] ignoreTestList = {
        ".", // ignore hidden directories and files
        "resources", // ignore resource directories
        "AppleScript", // AppleScript not supported
        "xpath", // xpath requires libxml2, not supported
        "xsl", //xsl requires libxml2 & libxslt, not sup.
        "kde", // don't run kde tests.
        ".svn", // don't run anything under .svn folder
        "gradients", //known crash
        "toString-stack-overflow.html", // Crashes #606688
        "frame-limit.html", // generates too many GREFs
        "css-insert-import-rule.html", // Crashes, #717414
        "input-text-enter.html", // Crashes. #735088
        "text-shadow-extreme-value.html", // Crashes #571671
        "001.html",
        "reflection-masks.html",
        "frame-creation-removal.html",
        "large-expressions.html",
        "null-page-show-modal-dialog-crash.html",
        "font-face-implicit-local-font.html",
        "font-face-locally-installed.html",
        "beforeSelectorOnCodeElement.html",
        "cssTarget-crash.html"
        };
    
    static void fillIgnoreResultSet() {
        // need test plugin
        ignoreResultList.add("fast/dom/Window/Plug-ins.html");
        // pixel depth
        ignoreResultList.add("fast/dom/Window/window-screen-properties.html");
        // missing space in textrun, ok as text is wrapped. ignore. #714933
        ignoreResultList.add("fast/events/onload-webkit-before-webcore.html");
        // missing support for textInputController.makeAttributedString()
        ignoreResultList.add("fast/forms/attributed-strings.html");
        // charset convert. #516936 ignore, won't fix
        ignoreResultList.add("fast/forms/form-data-encoding-2.html");
        // charset convert. #516936 ignore, won't fix
        ignoreResultList.add("fast/forms/form-data-encoding.html");
        // execCommand "insertText" not supported
        ignoreResultList.add("fast/forms/input-appearance-maxlength.html");
        // Copy&Paste commands not supported
        ignoreResultList.add("fast/forms/input-truncate-newline.html");
        ignoreResultList.add("fast/forms/textarea-paste-newline.html");
        // requires eventSender.mouseMoveTo, mouseDown & mouseUp and 
        // abs. position of mouse to select a word. ignore, won't fix #716583
        ignoreResultList.add("fast/forms/onselect-textarea.html");
        // requires eventSender.mouseMoveTo, mouseDown & mouseUp and 
        // abs. position of mouse to select a word. ignore, won't fix #716583
        ignoreResultList.add("fast/forms/onselect-textfield.html");
        // not implemented queryCommandEnabled:BackColor, Undo & Redo
        ignoreResultList.add("fast/forms/plaintext-mode-1.html");
        // Our text areas are a little thinner than Apples. Also RTL test failes
        ignoreResultList.add("fast/forms/textarea-appearance-wrap.html");
        // Our text areas are a little thinner than Apples
        ignoreResultList.add("fast/forms/textarea-hard-linewrap.html");
        // screen width&height are different
        ignoreResultList.add("fast/frames/frameElement-widthheight.html");
        ignoreResultList.add("fast/frames/frame-js-url-clientWidth.html");
        //  requires JS test API, textInputController
        ignoreResultList.add("fast/text/attributed-substring-from-range.html"); 
        ignoreResultList.add("fast/text/attributed-substring-from-range-001.html");
        // will not fix #619707
        ignoreResultList.add("fast/css/case-transform.html");
        // different platform defaults for font and different screen size
        ignoreResultList.add("fast/css/computed-style.html");
        // different screen size result in extra spaces in Apple compared to us
        ignoreResultList.add("fast/dom/Element/offsetLeft-offsetTop-body-quirk.html");
        // xslt and xpath elements missing from property list
        ignoreResultList.add("fast/dom/Window/window-properties.html");
        // requires textInputController.characterIndexForPoint
        ignoreResultList.add("fast/dom/character-index-for-point.html");
        // requires xpath support
        ignoreResultList.add("fast/dom/gc-9.html");
        // requires xslt and xpath support
        ignoreResultList.add("fast/dom/global-constructors.html");
        // dynamic plugins not supported
        ignoreResultList.add("fast/dom/object-embed-plugin-scripting.html");
        ignoreResultList.add("fast/js/navigator-mimeTypes-length.html");
        // there is extra spacing in the file due to multiple input boxes
        // fitting on one line on Apple, ours are wrapped. Space at line ends
        // are stripped.
        ignoreResultList.add("fast/dom/tabindex-clamp.html");
        
        // requires eventSender.mouseDown(),mouseUp()
        ignoreResultList.add("fast/dom/Window/window-xy-properties.html");
        ignoreResultList.add("fast/events/window-events-bubble.html");
        ignoreResultList.add("fast/events/window-events-bubble2.html");
        ignoreResultList.add("fast/events/window-events-capture.html");
        ignoreResultList.add("fast/forms/select-empty-list.html");
        ignoreResultList.add("fast/replaced/image-map.html");
        ignoreResultList.add("fast/events/capture-on-target.html");
        ignoreResultList.add("fast/events/dblclick-addEventListener.html");
        ignoreResultList.add("fast/events/drag-in-frames.html");
        ignoreResultList.add("fast/events/drag-outside-window.html");
        ignoreResultList.add("fast/events/event-sender-mouse-click.html");
        ignoreResultList.add("fast/events/event-view-toString.html");
        ignoreResultList.add("fast/events/frame-click-focus.html");
        ignoreResultList.add("fast/events/input-image-scrolled-x-y.html");
        ignoreResultList.add("fast/events/anchor-image-scrolled-x-y.html");
        ignoreResultList.add("fast/events/mouseclick-target-and-positioning.html");
        ignoreResultList.add("fast/events/mouseover-mouseout.html");
        ignoreResultList.add("fast/events/mouseover-mouseout2.html");
        ignoreResultList.add("fast/events/mouseup-outside-button.html");
        ignoreResultList.add("fast/events/mouseup-outside-document.html");
        ignoreResultList.add("fast/events/onclick-list-marker.html");
        ignoreResultList.add("fast/events/ondragenter.html");
        ignoreResultList.add("fast/forms/drag-into-textarea.html");
        ignoreResultList.add("fast/forms/input-select-on-click.html");
        ignoreResultList.add("fast/forms/listbox-onchange.html");
        ignoreResultList.add("fast/forms/search-cancel-button-mouseup.html");
        ignoreResultList.add("fast/forms/textarea-scrolled-endline-caret.html");
        
        // there is extra spacing in the file due to multiple frame boxes
        // fitting on one line on Apple, ours are wrapped. Space at line ends
        // are stripped.
        ignoreResultList.add("fast/events/iframe-object-onload.html");
        // eventSender.mouseDown(), mouseUp() and objc API missing
        ignoreResultList.add("fast/events/mouseup-outside-document.html");
        ignoreResultList.add("fast/events/objc-keyboard-event-creation.html");
        ignoreResultList.add("fast/events/objc-event-api.html");
        // not capturing the console messages
        ignoreResultList.add("fast/forms/selected-index-assert.html");
        ignoreResultList.add("fast/parser/script-tag-with-trailing-slash.html");
        // there is extra spacing as the text areas and input boxes fit next
        // to each other on Apple, but are wrapped on our screen.
        ignoreResultList.add("fast/forms/selection-functions.html");
        // Text selection done differently on our platform. When a inputbox
        // gets focus, the entire block is selected.
        ignoreResultList.add("fast/forms/textarea-initial-caret-position.html");
        ignoreResultList.add("fast/forms/textarea-no-scroll-on-blur.html");
        // Requires LayoutTests to exist at /tmp/LayoutTests
        ignoreResultList.add("fast/loader/local-JavaScript-from-local.html");
        ignoreResultList.add("fast/loader/local-iFrame-source-from-local.html");
        // extra spacing because iFrames rendered next to each other on Apple
        ignoreResultList.add("fast/loader/opaque-base-url.html");
        ignoreResultList.add("fast/text/plain-text-line-breaks.html");
        
        
    }
    
    static void fillBugTable() {
        bugList.put("fast/forms/check-box-enter-key.html", "716715");
        bugList.put("fast/forms/focus-control-to-page.html", "716638");
        bugList.put("fast/html/tab-order.html", "719289");
        bugList.put("fast/dom/attribute-namespaces-get-set.html", "733229");
        bugList.put("fast/dom/location-hash.html", "733822");
        bugList.put("fast/dom/set-innerHTML.html", "733823");
        bugList.put("fast/dom/xmlhttprequest-get.html", "733846");
        bugList.put("fast/encoding/css-charset-default.html", "733856");
        bugList.put("fast/encoding/default-xhtml-encoding.html", "733882");
        bugList.put("fast/encoding/meta-in-xhtml.html", "733882");
        bugList.put("fast/events/frame-tab-focus.html", "734308");
        bugList.put("fast/events/keydown-keypress-focus-change.html", "653224");
        bugList.put("fast/events/keypress-focus-change.html", "653224");
        bugList.put("fast/events/option-tab.html", "734308");
        bugList.put("fast/forms/focus2.html", "735111");
        bugList.put("fast/forms/listbox-selection.html", "735116");
        bugList.put("fast/forms/search-event-delay.html", "735120");
        bugList.put("fast/frames/iframe-window-focus.html", "735140");
        bugList.put("fast/innerHTML/004.html", "733882");
        bugList.put("fast/js/date-DST-time-cusps.html", "735144");
        bugList.put("fast/js/string-capitalization.html", "516936");
        bugList.put("fast/js/string-concatenate-outofmemory.html","735152");
        bugList.put("fast/parser/external-entities.html", "735176");
        bugList.put("fast/events/div-focus.html", "735185");
        bugList.put("fast/overflow/scroll-vertical-not-horizontal.html", "735196");
        bugList.put("fast/events/arrow-navigation.html", "735233");
        bugList.put("fast/forms/select-type-ahead-non-latin.html", "735244");
        bugList.put("fast/events/js-keyboard-event-creation.html", "735255");
        
    }
    
    
    
}
