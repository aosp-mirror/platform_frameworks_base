/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.util.ArraySet;

import java.io.PrintWriter;
import java.util.List;

/**
 * Abstract class to control the policies of the windows that can be displayed on the virtual
 * display.
 *
 * @hide
 */
public abstract class DisplayWindowPolicyController {
    /**
     * The window flags that we are interested in.
     * @see android.view.WindowManager.LayoutParams
     * @see #keepActivityOnWindowFlagsChanged
     */
    private int mWindowFlags;

    /**
     * The system window flags that we are interested in.
     * @see android.view.WindowManager.LayoutParams
     * @see #keepActivityOnWindowFlagsChanged
     */
    private int mSystemWindowFlags;

    /**
     * Returns {@code true} if the given window flags contain the flags that we're interested in.
     */
    public final boolean isInterestedWindowFlags(int windowFlags, int systemWindowFlags) {
        return (mWindowFlags & windowFlags) != 0 || (mSystemWindowFlags & systemWindowFlags) != 0;
    }

    /**
     * Sets the window flags that we’re interested in and expected
     * #keepActivityOnWindowFlagsChanged to be called if any changes.
     */
    public final void setInterestedWindowFlags(int windowFlags, int systemWindowFlags) {
        mWindowFlags = windowFlags;
        mSystemWindowFlags = systemWindowFlags;
    }

    /**
     * Returns {@code true} if the given activities can be displayed on this virtual display.
     */
    public abstract boolean canContainActivities(@NonNull List<ActivityInfo> activities);

    /**
     * Called when an Activity window is layouted with the new changes where contains the
     * window flags that we’re interested in.
     * Returns {@code false} if the Activity cannot remain on the display and the activity task will
     * be moved back to default display.
     */
    public abstract boolean keepActivityOnWindowFlagsChanged(
            ActivityInfo activityInfo, int windowFlags, int systemWindowFlags);

    /**
     * This is called when the top activity of the display is changed.
     */
    public void onTopActivityChanged(ComponentName topActivity, int uid) {}

    /**
     * This is called when the apps that contains running activities on the display has changed.
     * The running activities refer to the non-finishing activities regardless of they are running
     * in a process.
     */
    public void onRunningAppsChanged(ArraySet<Integer> runningUids) {}

    /** Dump debug data */
    public void dump(String prefix, final PrintWriter pw) {
        pw.println(prefix + "DisplayWindowPolicyController{" + super.toString() + "}");
        pw.println(prefix + "  mWindowFlags=" + mWindowFlags);
        pw.println(prefix + "  mSystemWindowFlags=" + mSystemWindowFlags);
    }
}
