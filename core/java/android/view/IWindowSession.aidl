/* //device/java/android/android/view/IWindowSession.aidl
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package android.view;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.IWindow;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.Surface;

/**
 * System private per-application interface to the window manager.
 *
 * {@hide}
 */
interface IWindowSession {
    int add(IWindow window, in WindowManager.LayoutParams attrs,
            in int viewVisibility, out Rect outCoveredInsets);
    void remove(IWindow window);
    
    /**
     * Change the parameters of a window.  You supply the
     * new parameters, it returns the new frame of the window on screen (the
     * position should be ignored) and surface of the window.  The surface
     * will be invalid if the window is currently hidden, else you can use it
     * to draw the window's contents.
     * 
     * @param window The window being modified.
     * @param attrs If non-null, new attributes to apply to the window.
     * @param requestedWidth The width the window wants to be.
     * @param requestedHeight The height the window wants to be.
     * @param viewVisibility Window root view's visibility.
     * @param outFrame Object in which is placed the new position/size on
     *                 screen.
     * @param outCoveredInsets Object in which is placed the insets for the areas covered by
 	 *                 system windows (e.g. status bar)
     * @param outSurface Object in which is placed the new display surface.
     * 
     * @return int Result flags: {@link WindowManagerImpl#RELAYOUT_SHOW_FOCUS},
     * {@link WindowManagerImpl#RELAYOUT_FIRST_TIME}.
     */
    int relayout(IWindow window, in WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewVisibility,
            out Rect outFrame, out Rect outCoveredInsets, out Surface outSurface);

    void finishDrawing(IWindow window);

    void finishKey(IWindow window);
    MotionEvent getPendingPointerMove(IWindow window);
    MotionEvent getPendingTrackballMove(IWindow window);
    
    void setTransparentRegion(IWindow window, in Region region);

    void setInTouchMode(boolean showFocus);
    boolean getInTouchMode();
}

