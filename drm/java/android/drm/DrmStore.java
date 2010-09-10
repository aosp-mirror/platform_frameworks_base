/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.drm;

/**
 * This class defines all the constants used by DRM framework
 *
 */
public class DrmStore {
    /**
     * Columns representing drm constraints
     */
    public interface ConstraintsColumns {
        /**
         * The max repeat count
         * <P>Type: INTEGER</P>
         */
        public static final String MAX_REPEAT_COUNT = "max_repeat_count";

        /**
         * The remaining repeat count
         * <P>Type: INTEGER</P>
         */
        public static final String REMAINING_REPEAT_COUNT = "remaining_repeat_count";

        /**
         * The time before which the protected file can not be played/viewed
         * <P>Type: TEXT</P>
         */
        public static final String LICENSE_START_TIME = "license_start_time";

        /**
         * The time after which the protected file can not be played/viewed
         * <P>Type: TEXT</P>
         */
        public static final String LICENSE_EXPIRY_TIME = "license_expiry_time";

        /**
         * The available time for license
         * <P>Type: TEXT</P>
         */
        public static final String LICENSE_AVAILABLE_TIME = "license_available_time";

        /**
         * The data stream for extended metadata
         * <P>Type: TEXT</P>
         */
        public static final String EXTENDED_METADATA = "extended_metadata";
    }

    /**
     * Defines constants related to DRM types
     */
    public static class DrmObjectType {
        /**
         * Field specifies the unknown type
         */
        public static final int UNKNOWN = 0x00;
        /**
         * Field specifies the protected content type
         */
        public static final int CONTENT = 0x01;
        /**
         * Field specifies the rights information
         */
        public static final int RIGHTS_OBJECT = 0x02;
        /**
         * Field specifies the trigger information
         */
        public static final int TRIGGER_OBJECT = 0x03;
    }

    /**
     * Defines constants related to playback
     */
    public static class Playback {
        /**
         * Constant field signifies playback start
         */
        public static final int START = 0x00;
        /**
         * Constant field signifies playback stop
         */
        public static final int STOP = 0x01;
        /**
         * Constant field signifies playback paused
         */
        public static final int PAUSE = 0x02;
        /**
         * Constant field signifies playback resumed
         */
        public static final int RESUME = 0x03;

        /* package */ static boolean isValid(int playbackStatus) {
            boolean isValid = false;

            switch (playbackStatus) {
                case START:
                case STOP:
                case PAUSE:
                case RESUME:
                    isValid = true;
            }
            return isValid;
        }
    }

    /**
     * Defines actions that can be performed on protected content
     */
    public static class Action {
        /**
         * Constant field signifies that the default action
         */
        public static final int DEFAULT = 0x00;
        /**
         * Constant field signifies that the content can be played
         */
        public static final int PLAY = 0x01;
        /**
         * Constant field signifies that the content can be set as ring tone
         */
        public static final int RINGTONE = 0x02;
        /**
         * Constant field signifies that the content can be transfered
         */
        public static final int TRANSFER = 0x03;
        /**
         * Constant field signifies that the content can be set as output
         */
        public static final int OUTPUT = 0x04;
        /**
         * Constant field signifies that preview is allowed
         */
        public static final int PREVIEW = 0x05;
        /**
         * Constant field signifies that the content can be executed
         */
        public static final int EXECUTE = 0x06;
        /**
         * Constant field signifies that the content can displayed
         */
        public static final int DISPLAY = 0x07;

        /* package */ static boolean isValid(int action) {
            boolean isValid = false;

            switch (action) {
                case DEFAULT:
                case PLAY:
                case RINGTONE:
                case TRANSFER:
                case OUTPUT:
                case PREVIEW:
                case EXECUTE:
                case DISPLAY:
                    isValid = true;
            }
            return isValid;
        }
    }

    /**
     * Defines constants related to status of the rights
     */
    public static class RightsStatus {
        /**
         * Constant field signifies that the rights are valid
         */
        public static final int RIGHTS_VALID = 0x00;
        /**
         * Constant field signifies that the rights are invalid
         */
        public static final int RIGHTS_INVALID = 0x01;
        /**
         * Constant field signifies that the rights are expired for the content
         */
        public static final int RIGHTS_EXPIRED = 0x02;
        /**
         * Constant field signifies that the rights are not acquired for the content
         */
        public static final int RIGHTS_NOT_ACQUIRED = 0x03;
    }
}

