/* //device/java/android/android/view/IWindow.aidl
**
** Copyright 2007, The Android Open Source Project
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
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.DisplayCutout;
import android.view.InsetsState;
import android.view.InsetsSourceControl;

import com.android.internal.os.IResultReceiver;
import android.util.MergedConfiguration;

/**
 * API back to a client window that the Window Manager uses to inform it of
 * interesting things happening.
 *
 * {@hide}
 */
oneway interface IWindow {
    /**
     * ===== NOTICE =====
     * The first method must remain the first method. Scripts
     * and tools rely on their transaction number to work properly.
     */

    /**
     * Invoked by the view server to tell a window to execute the specified
     * command. Any response from the receiver must be sent through the
     * specified file descriptor.
     */
    void executeCommand(String command, String parameters, in ParcelFileDescriptor descriptor);

    void resized(in Rect frame, in Rect overscanInsets, in Rect contentInsets,
            in Rect visibleInsets, in Rect stableInsets, in Rect outsets, boolean reportDraw,
            in MergedConfiguration newMergedConfiguration, in Rect backDropFrame,
            boolean forceLayout, boolean alwaysConsumeNavBar, int displayId,
            in DisplayCutout.ParcelableWrapper displayCutout);

    /**
     * Called when the window insets configuration has changed.
     */
    void insetsChanged(in InsetsState insetsState);

    /**
     * Called when this window retrieved control over a specified set of inset sources.
     */
    void insetsControlChanged(in InsetsState insetsState, in InsetsSourceControl[] activeControls);

    void moved(int newX, int newY);
    void dispatchAppVisibility(boolean visible);
    void dispatchGetNewSurface();

    /**
     * Tell the window that it is either gaining or losing focus.  Keep it up
     * to date on the current state showing navigational focus (touch mode) too.
     */
    void windowFocusChanged(boolean hasFocus, boolean inTouchMode);

    void closeSystemDialogs(String reason);

    /**
     * Called for wallpaper windows when their offsets change.
     */
    void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep, boolean sync);

    void dispatchWallpaperCommand(String action, int x, int y,
            int z, in Bundle extras, boolean sync);

    /**
     * Drag/drop events
     */
    void dispatchDragEvent(in DragEvent event);

    /**
     * Pointer icon events
     */
    void updatePointerIcon(float x, float y);

    /**
     * System chrome visibility changes
     */
    void dispatchSystemUiVisibilityChanged(int seq, int globalVisibility,
            int localValue, int localChanges);

    /**
     * Called for non-application windows when the enter animation has completed.
     */
    void dispatchWindowShown();

    /**
     * Called when Keyboard Shortcuts are requested for the window.
     */
    void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId);

    /**
     * Tell the window that it is either gaining or losing pointer capture.
     */
    void dispatchPointerCaptureChanged(boolean hasCapture);
}
