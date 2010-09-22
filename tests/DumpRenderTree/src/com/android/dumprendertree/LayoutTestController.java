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

public interface LayoutTestController {

    public void dumpAsText(boolean enablePixelTests);
    public void dumpChildFramesAsText();
    public void waitUntilDone();
    public void notifyDone();

    // Force a redraw of the page
    public void display();
    // Used with pixel dumps of content
    public void testRepaint();

    // If the page title changes, add the information to the output.
    public void dumpTitleChanges();
    public void dumpBackForwardList();
    public void dumpChildFrameScrollPositions();
    public void dumpEditingCallbacks();

    // Show/Hide window for window.onBlur() testing
    public void setWindowIsKey(boolean b);
    // Mac function, used to disable events going to the window
    public void setMainFrameIsFirstResponder(boolean b);

    public void dumpSelectionRect();

    // invalidate and draw one line at a time of the web view.
    public void repaintSweepHorizontally();
    
    // History testing functions
    public void keepWebHistory();
    public void clearBackForwardList();
    // navigate after page load has finished
    public void queueBackNavigation(int howfar);
    public void queueForwardNavigation(int howfar);
    
    // Reload when the page load has finished
    public void queueReload();
    // Execute the provided script in current context when page load has finished.
    public void queueScript(String scriptToRunInCurrentContext);
    // Load the provided URL into the provided frame
    public void queueLoad(String Url, String frameTarget);

    public void setAcceptsEditing(boolean b);

    // For storage tests
    public void dumpDatabaseCallbacks();
    public void setCanOpenWindows();

    // For Geolocation tests
    public void setGeolocationPermission(boolean allow);

    public void overridePreference(String key, boolean value);

    // For XSSAuditor tests
    public void setXSSAuditorEnabled(boolean flag);

    // For DeviceOrientation tests
    public void setMockDeviceOrientation(boolean canProvideAlpha, double alpha,
            boolean canProvideBeta, double beta, boolean canProvideGamma, double gamma);
}
