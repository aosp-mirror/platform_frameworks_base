/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.hardware.sidekick;


/**
 * Sidekick local system service interface.
 *
 * @hide Only for use within the system server, and maybe by Clockwork Home.
 */
public abstract class SidekickInternal {

    /**
     * Tell Sidekick to reset back to newly-powered-on state.
     *
     * @return true on success (Sidekick is reset), false if Sidekick is not
     * available (failed or not present). Either way, upon return Sidekick is
     * guaranteed not to be controlling the display.
     */
    public abstract boolean reset();

    /**
     * Tell Sidekick it can start controlling the display.
     *
     * SidekickServer may choose not to actually control the display, if it's been told
     * via other channels to leave the previous image on the display (same as SUSPEND in
     * a non-Sidekick system).
     *
     * @param displayState - one of Display.STATE_DOZE_SUSPEND, Display.STATE_ON_SUSPEND
     * @return true on success, false on failure (no sidekick available)
     */
    public abstract boolean startDisplayControl(int displayState);

    /**
     * Tell Sidekick it must stop controlling the display.
     *
     * No return code because this must always succeed - after return, Sidekick
     * is guaranteed to not be controlling the display.
     */
    public abstract void endDisplayControl();

}
