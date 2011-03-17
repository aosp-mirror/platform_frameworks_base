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
 * Defines constants that are used by the DRM framework.
 *
 */
public class DrmStore {
    /**
     * Interface definition for the columns that represent DRM constraints.
     */
    public interface ConstraintsColumns {
        /**
         * The maximum repeat count.
         * <p>
         * Type: INTEGER
         */
        public static final String MAX_REPEAT_COUNT = "max_repeat_count";

        /**
         * The remaining repeat count.
         * <p>
         * Type: INTEGER
         */
        public static final String REMAINING_REPEAT_COUNT = "remaining_repeat_count";

        /**
         * The time before which the rights-protected file cannot be played/viewed.
         * <p>
         * Type: TEXT
         */
        public static final String LICENSE_START_TIME = "license_start_time";

        /**
         * The time after which the rights-protected file cannot be played/viewed.
         * <p>
         * Type: TEXT
         */
        public static final String LICENSE_EXPIRY_TIME = "license_expiry_time";

        /**
         * The available time left before the license expires.
         * <p>
         * Type: TEXT
         */
        public static final String LICENSE_AVAILABLE_TIME = "license_available_time";

        /**
         * The data stream for extended metadata.
         * <p>
         * Type: TEXT
         */
        public static final String EXTENDED_METADATA = "extended_metadata";
    }

    /**
     * Defines DRM object types.
     */
    public static class DrmObjectType {
        /**
         * An unknown object type.
         */
        public static final int UNKNOWN = 0x00;
        /**
         * A rights-protected file object type.
         */
        public static final int CONTENT = 0x01;
        /**
         * A rights information object type.
         */
        public static final int RIGHTS_OBJECT = 0x02;
        /**
         * A trigger information object type.
         */
        public static final int TRIGGER_OBJECT = 0x03;
    }

    /**
     * Defines playback states for content.
     */
    public static class Playback {
        /**
         * Playback started.
         */
        public static final int START = 0x00;
        /**
         * Playback stopped.
         */
        public static final int STOP = 0x01;
        /**
         * Playback paused.
         */
        public static final int PAUSE = 0x02;
        /**
         * Playback resumed.
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
     * Defines actions that can be performed on rights-protected content.
     */
    public static class Action {
        /**
         * The default action.
         */
        public static final int DEFAULT = 0x00;
        /**
         * The rights-protected content can be played.
         */
        public static final int PLAY = 0x01;
        /**
         * The rights-protected content can be set as a ringtone.
         */
        public static final int RINGTONE = 0x02;
        /**
         * The rights-protected content can be transferred.
         */
        public static final int TRANSFER = 0x03;
        /**
         * The rights-protected content can be set as output.
         */
        public static final int OUTPUT = 0x04;
        /**
         * The rights-protected content can be previewed.
         */
        public static final int PREVIEW = 0x05;
        /**
         * The rights-protected content can be executed.
         */
        public static final int EXECUTE = 0x06;
        /**
         * The rights-protected content can be displayed.
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
     * Defines status notifications for digital rights.
     */
    public static class RightsStatus {
        /**
         * The digital rights are valid.
         */
        public static final int RIGHTS_VALID = 0x00;
        /**
         * The digital rights are invalid.
         */
        public static final int RIGHTS_INVALID = 0x01;
        /**
         * The digital rights have expired.
         */
        public static final int RIGHTS_EXPIRED = 0x02;
        /**
         * The digital rights have not been acquired for the rights-protected content.
         */
        public static final int RIGHTS_NOT_ACQUIRED = 0x03;
    }
}

