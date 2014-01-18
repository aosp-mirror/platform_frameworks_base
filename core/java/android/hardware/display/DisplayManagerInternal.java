/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.display;

import android.view.DisplayInfo;

/**
 * Display manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class DisplayManagerInternal {
    /**
     * Called by the power manager to blank all displays.
     */
    public abstract void blankAllDisplaysFromPowerManager();

    /**
     * Called by the power manager to unblank all displays.
     */
    public abstract void unblankAllDisplaysFromPowerManager();

    /**
     * Returns information about the specified logical display.
     *
     * @param displayId The logical display id.
     * @return The logical display info, or null if the display does not exist.  The
     * returned object must be treated as immutable.
     */
    public abstract DisplayInfo getDisplayInfo(int displayId);

    /**
     * Registers a display transaction listener to provide the client a chance to
     * update its surfaces within the same transaction as any display layout updates.
     *
     * @param listener The listener to register.
     */
    public abstract void registerDisplayTransactionListener(DisplayTransactionListener listener);

    /**
     * Unregisters a display transaction listener to provide the client a chance to
     * update its surfaces within the same transaction as any display layout updates.
     *
     * @param listener The listener to unregister.
     */
    public abstract void unregisterDisplayTransactionListener(DisplayTransactionListener listener);

    /**
     * Overrides the display information of a particular logical display.
     * This is used by the window manager to control the size and characteristics
     * of the default display.  It is expected to apply the requested change
     * to the display information synchronously so that applications will immediately
     * observe the new state.
     *
     * NOTE: This method must be the only entry point by which the window manager
     * influences the logical configuration of displays.
     *
     * @param displayId The logical display id.
     * @param info The new data to be stored.
     */
    public abstract void setDisplayInfoOverrideFromWindowManager(
            int displayId, DisplayInfo info);

    /**
     * Called by the window manager to perform traversals while holding a
     * surface flinger transaction.
     */
    public abstract void performTraversalInTransactionFromWindowManager();

    /**
     * Tells the display manager whether there is interesting unique content on the
     * specified logical display.  This is used to control automatic mirroring.
     * <p>
     * If the display has unique content, then the display manager arranges for it
     * to be presented on a physical display if appropriate.  Otherwise, the display manager
     * may choose to make the physical display mirror some other logical display.
     * </p>
     *
     * @param displayId The logical display id to update.
     * @param hasContent True if the logical display has content.
     * @param inTraversal True if called from WindowManagerService during a window traversal
     * prior to call to performTraversalInTransactionFromWindowManager.
     */
    public abstract void setDisplayHasContent(int displayId, boolean hasContent,
            boolean inTraversal);

    /**
     * Called within a Surface transaction whenever the size or orientation of a
     * display may have changed.  Provides an opportunity for the client to
     * update the position of its surfaces as part of the same transaction.
     */
    public interface DisplayTransactionListener {
        void onDisplayTransaction();
    }
}
