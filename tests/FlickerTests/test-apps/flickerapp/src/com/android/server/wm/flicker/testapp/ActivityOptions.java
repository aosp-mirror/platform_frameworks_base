/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker.testapp;

import android.content.ComponentName;

public class ActivityOptions {
    public static final String FLICKER_APP_PACKAGE = "com.android.server.wm.flicker.testapp";

    public static class SimpleActivity {
        public static final String LABEL = "SimpleActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".SimpleActivity");
    }

    public static class SeamlessRotation {
        public static final String LABEL = "SeamlessRotationActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".SeamlessRotationActivity");

        public static final String EXTRA_STARVE_UI_THREAD = "StarveUiThread";
    }

    public static class Ime {
        public static class Default {
            public static final String LABEL = "ImeActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ImeActivity");
        }

        public static class AutoFocusActivity {
            public static final String LABEL = "ImeAppAutoFocus";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ImeActivityAutoFocus");
        }

        public static class StateInitializeActivity {
            public static final String LABEL = "ImeStateInitializeActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ImeStateInitializeActivity");
        }

        public static class EditorPopupDialogActivity {
            public static final String LABEL = "ImeEditorPopupDialogActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ImeEditorPopupDialogActivity");
        }
    }

    public static class NonResizeableActivity {
        public static final String LABEL = "NonResizeableActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".NonResizeableActivity");
    }

    public static class NonResizeablePortraitActivity {
        public static final String LABEL = "NonResizeablePortraitActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".NonResizeablePortraitActivity");
    }

    public static class DialogThemedActivity {
        public static final String LABEL = "DialogThemedActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".DialogThemedActivity");
    }

    public static class PortraitOnlyActivity {
        public static final String LABEL = "PortraitOnlyActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".PortraitOnlyActivity");
        public static final String EXTRA_FIXED_ORIENTATION = "fixed_orientation";
    }

    public static class ActivityEmbedding {
        public static class MainActivity {
            public static final String LABEL = "ActivityEmbeddingMainActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ActivityEmbeddingMainActivity");
        }

        public static class SecondaryActivity {
            public static final String LABEL = "ActivityEmbeddingSecondaryActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ActivityEmbeddingSecondaryActivity");
        }

        public static class AlwaysExpandActivity {
            public static final String LABEL = "ActivityEmbeddingAlwaysExpandActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".ActivityEmbeddingAlwaysExpandActivity");
        }

        public static class PlaceholderPrimaryActivity {
            public static final String LABEL = "ActivityEmbeddingPlaceholderPrimaryActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ActivityEmbeddingPlaceholderPrimaryActivity");
        }

        public static class PlaceholderSecondaryActivity {
            public static final String LABEL = "ActivityEmbeddingPlaceholderSecondaryActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".ActivityEmbeddingPlaceholderSecondaryActivity");
        }
    }

    public static class Notification {
        public static final String LABEL = "NotificationActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".NotificationActivity");
    }

    public static class Mail {
        public static final String LABEL = "MailActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".MailActivity");
    }

    public static class ShowWhenLockedActivity {
        public static final String LABEL = "ShowWhenLockedActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".ShowWhenLockedActivity");
    }

    public static class LaunchNewTask {
        public static final String LABEL = "LaunchNewTaskActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".LaunchNewTaskActivity");
    }

    public static class Game {
        public static final String LABEL = "GameActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".GameActivity");
    }

    public static class LaunchNewActivity {
        public static final String LABEL = "LaunchNewActivity";
        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".LaunchNewActivity");
    }

    public static class Pip {
        // Test App > Pip Activity
        public static final String LABEL = "PipActivity";
        public static final String MENU_ACTION_NO_OP = "No-Op";
        public static final String MENU_ACTION_ON = "On";
        public static final String MENU_ACTION_OFF = "Off";
        public static final String MENU_ACTION_CLEAR = "Clear";

        // Intent action that this activity dynamically registers to enter picture-in-picture
        public static final String ACTION_ENTER_PIP =
                FLICKER_APP_PACKAGE + ".PipActivity.ENTER_PIP";
        // Intent action that this activity dynamically registers to set requested orientation.
        // Will apply the oriention to the value set in the EXTRA_FIXED_ORIENTATION extra.
        public static final String ACTION_SET_REQUESTED_ORIENTATION =
                FLICKER_APP_PACKAGE + ".PipActivity.SET_REQUESTED_ORIENTATION";

        // Calls enterPictureInPicture() on creation
        public static final String EXTRA_ENTER_PIP = "enter_pip";
        // Sets the fixed orientation (can be one of {@link ActivityInfo.ScreenOrientation}
        public static final String EXTRA_PIP_ORIENTATION = "fixed_orientation";
        // Adds a click listener to finish this activity when it is clicked
        public static final String EXTRA_TAP_TO_FINISH = "tap_to_finish";

        public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                FLICKER_APP_PACKAGE + ".PipActivity");
    }

    public static class SplitScreen {
        public static class Primary {
            public static final String LABEL = "SplitScreenPrimaryActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".SplitScreenActivity");
        }

        public static class Secondary {
            public static final String LABEL = "SplitScreenSecondaryActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".SplitScreenSecondaryActivity");
        }
    }

    public static class Bubbles {
        public static class LaunchBubble {
            public static final String LABEL = "LaunchBubbleActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".LaunchBubbleActivity");
        }

        public static class BubbleActivity {
            public static final String LABEL = "BubbleActivity";
            public static final ComponentName COMPONENT = new ComponentName(FLICKER_APP_PACKAGE,
                    FLICKER_APP_PACKAGE + ".BubbleActivity");
        }
    }

    public static final String GAME_ACTIVITY_LAUNCHER_NAME = "GameApp";
    public static final ComponentName GAME_ACTIVITY_COMPONENT_NAME =
            new ComponentName(FLICKER_APP_PACKAGE, FLICKER_APP_PACKAGE + ".GameActivity");

    public static final ComponentName ASSISTANT_SERVICE_COMPONENT_NAME =
            new ComponentName(
                    FLICKER_APP_PACKAGE, FLICKER_APP_PACKAGE + ".AssistantInteractionService");
}
