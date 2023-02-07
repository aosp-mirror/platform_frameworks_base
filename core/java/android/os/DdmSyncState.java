/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.os;

import java.util.Arrays;

/**
 * Keep track of an app boot state. The main purpose is to stream back DDM packet so a DDM client
 * can synchronize with the app state.
 *
 * The state is static so it can be accessed from HELO handler.
 *
 * @hide
 **/
public final class DdmSyncState {

    /**
     * @hide
     */
    public enum Stage {
        // From zygote to attach
        Boot("BOOT"),

        // From attach to handleBindApplication
        Attach("ATCH"),

        // When handleBindApplication is finally reached
        Bind("BIND"),

        // When the actual package name is known (not the early "<preinitalized>" value).
        Named("NAMD"),

        // Can be skipped if the app is not debugged.
        Debugger("DEBG"),

        // App is in RunLoop
        Running("A_GO");

        final String mLabel;

        Stage(String label) {
            if (label.length() != 4) {
                throw new IllegalStateException(
                    "Bad stage id '" + label + "'. Must be four letters");
            }
            this.mLabel = label;
        }

        /**
         * To be included in a DDM packet payload, the stage is encoded in a big-endian int
         * @hide
         */
        public int toInt() {
            int result = 0;
            for (int i = 0; i < 4; ++i) {
                result = ((result << 8) | (mLabel.charAt(i) & 0xff));
            }
            return result;
        }
    }

    private static int sCurrentStageIndex = 0;

    /**
     * @hide
     */
    public static synchronized Stage getStage() {
        return Stage.values()[sCurrentStageIndex];
    }

    /**
     * @hide
     */
    public static void reset() {
        sCurrentStageIndex = 0;
    }

    /**
     * Search for the next level down the list of Stage. Only succeed if the next stage
     * if a later stage (no cycling allowed).
     *
     * @hide
     */
    public static synchronized void next(Stage nextStage) {
        Stage[] stages = Stage.values();
        // Search for the requested next stage
        int rover = sCurrentStageIndex;
        while (rover < stages.length && stages[rover] != nextStage) {
            rover++;
        }

        if (rover == stages.length || stages[rover] != nextStage) {
            throw new IllegalStateException(
                "Cannot go to " + nextStage + " from:" + getInternalState());
        }

        sCurrentStageIndex = rover;
    }

    /**
     * Use to build error messages
     * @hide
     */
    private static String getInternalState() {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("level = ").append(sCurrentStageIndex);
        sb.append("\n");
        sb.append("stages = ");
        sb.append(Arrays.toString(Arrays.stream(Stage.values()).map(Enum::name).toArray()));
        sb.append("\n");
        return sb.toString();
    }
}
