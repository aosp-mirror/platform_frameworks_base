/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.provider.Settings.Global;
import android.util.Log;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.plugins.VolumeDialogController.State;

import java.util.Arrays;

/**
 *  Interesting events related to the volume.
 */
public class Events {
    private static final String TAG = Util.logTag(Events.class);

    public static final int EVENT_SHOW_DIALOG = 0;  // (reason|int) (keyguard|bool)
    public static final int EVENT_DISMISS_DIALOG = 1; // (reason|int)
    public static final int EVENT_ACTIVE_STREAM_CHANGED = 2; // (stream|int)
    public static final int EVENT_EXPAND = 3; // (expand|bool)
    public static final int EVENT_KEY = 4;
    public static final int EVENT_COLLECTION_STARTED = 5;
    public static final int EVENT_COLLECTION_STOPPED = 6;
    public static final int EVENT_ICON_CLICK = 7; // (stream|int) (icon_state|int)
    public static final int EVENT_SETTINGS_CLICK = 8;
    public static final int EVENT_TOUCH_LEVEL_CHANGED = 9; // (stream|int) (level|int)
    public static final int EVENT_LEVEL_CHANGED = 10; // (stream|int) (level|int)
    public static final int EVENT_INTERNAL_RINGER_MODE_CHANGED = 11; // (mode|int)
    public static final int EVENT_EXTERNAL_RINGER_MODE_CHANGED = 12; // (mode|int)
    public static final int EVENT_ZEN_MODE_CHANGED = 13; // (mode|int)
    public static final int EVENT_SUPPRESSOR_CHANGED = 14;  // (component|string) (name|string)
    public static final int EVENT_MUTE_CHANGED = 15;  // (stream|int) (muted|bool)
    public static final int EVENT_TOUCH_LEVEL_DONE = 16;  // (stream|int) (level|bool)
    public static final int EVENT_ZEN_CONFIG_CHANGED = 17; // (allow/disallow|string)
    public static final int EVENT_RINGER_TOGGLE = 18; // (ringer_mode)
    public static final int EVENT_SHOW_OVERHEAT_ALARM = 19; // (reason|int) (keyguard|bool)
    public static final int EVENT_DISMISS_OVERHEAT_ALARM = 20; // (reason|int) (keyguard|bool)

    private static final String[] EVENT_TAGS = {
            "show_dialog",
            "dismiss_dialog",
            "active_stream_changed",
            "expand",
            "key",
            "collection_started",
            "collection_stopped",
            "icon_click",
            "settings_click",
            "touch_level_changed",
            "level_changed",
            "internal_ringer_mode_changed",
            "external_ringer_mode_changed",
            "zen_mode_changed",
            "suppressor_changed",
            "mute_changed",
            "touch_level_done",
            "zen_mode_config_changed",
            "ringer_toggle",
            "show_overheat_alarm",
            "dismiss_overheat_alarm"
    };

    public static final int DISMISS_REASON_UNKNOWN = 0;
    public static final int DISMISS_REASON_TOUCH_OUTSIDE = 1;
    public static final int DISMISS_REASON_VOLUME_CONTROLLER = 2;
    public static final int DISMISS_REASON_TIMEOUT = 3;
    public static final int DISMISS_REASON_SCREEN_OFF = 4;
    public static final int DISMISS_REASON_SETTINGS_CLICKED = 5;
    public static final int DISMISS_REASON_DONE_CLICKED = 6;
    public static final int DISMISS_STREAM_GONE = 7;
    public static final int DISMISS_REASON_OUTPUT_CHOOSER = 8;
    public static final String[] DISMISS_REASONS = {
            "unknown",
            "touch_outside",
            "volume_controller",
            "timeout",
            "screen_off",
            "settings_clicked",
            "done_clicked",
            "a11y_stream_changed",
            "output_chooser"
    };

    public static final int SHOW_REASON_UNKNOWN = 0;
    public static final int SHOW_REASON_VOLUME_CHANGED = 1;
    public static final int SHOW_REASON_REMOTE_VOLUME_CHANGED = 2;
    public static final int SHOW_REASON_OVERHEAD_ALARM_CHANGED = 3;
    public static final String[] SHOW_REASONS = {
        "unknown",
        "volume_changed",
        "remote_volume_changed",
        "overheat_alarm_changed"
    };

    public static final int ICON_STATE_UNKNOWN = 0;
    public static final int ICON_STATE_UNMUTE = 1;
    public static final int ICON_STATE_MUTE = 2;
    public static final int ICON_STATE_VIBRATE = 3;

    public static Callback sCallback;

    public static void writeEvent(Context context, int tag, Object... list) {
        MetricsLogger logger = new MetricsLogger();
        final long time = System.currentTimeMillis();
        final StringBuilder sb = new StringBuilder("writeEvent ").append(EVENT_TAGS[tag]);
        if (list != null && list.length > 0) {
            sb.append(" ");
            switch (tag) {
                case EVENT_SHOW_DIALOG:
                    MetricsLogger.visible(context, MetricsEvent.VOLUME_DIALOG);
                    MetricsLogger.histogram(context, "volume_from_keyguard",
                            (Boolean) list[1] ? 1 : 0);
                    sb.append(SHOW_REASONS[(Integer) list[0]]).append(" keyguard=").append(list[1]);
                    break;
                case EVENT_EXPAND:
                    MetricsLogger.visibility(context, MetricsEvent.VOLUME_DIALOG_DETAILS,
                            (Boolean) list[0]);
                    sb.append(list[0]);
                    break;
                case EVENT_DISMISS_DIALOG:
                    MetricsLogger.hidden(context, MetricsEvent.VOLUME_DIALOG);
                    sb.append(DISMISS_REASONS[(Integer) list[0]]);
                    break;
                case EVENT_ACTIVE_STREAM_CHANGED:
                    MetricsLogger.action(context, MetricsEvent.ACTION_VOLUME_STREAM,
                            (Integer) list[0]);
                    sb.append(AudioSystem.streamToString((Integer) list[0]));
                    break;
                case EVENT_ICON_CLICK:
                    MetricsLogger.action(context, MetricsEvent.ACTION_VOLUME_ICON,
                            (Integer) list[0]);
                    sb.append(AudioSystem.streamToString((Integer) list[0])).append(' ')
                            .append(iconStateToString((Integer) list[1]));
                    break;
                case EVENT_TOUCH_LEVEL_DONE:
                    MetricsLogger.action(context, MetricsEvent.ACTION_VOLUME_SLIDER,
                            (Integer) list[1]);
                    // fall through
                case EVENT_TOUCH_LEVEL_CHANGED:
                case EVENT_LEVEL_CHANGED:
                case EVENT_MUTE_CHANGED:
                    sb.append(AudioSystem.streamToString((Integer) list[0])).append(' ')
                            .append(list[1]);
                    break;
                case EVENT_KEY:
                    MetricsLogger.action(context, MetricsEvent.ACTION_VOLUME_KEY,
                            (Integer) list[0]);
                    sb.append(AudioSystem.streamToString((Integer) list[0])).append(' ')
                            .append(list[1]);
                    break;
                case EVENT_RINGER_TOGGLE:
                    logger.action(MetricsEvent.ACTION_VOLUME_RINGER_TOGGLE, (Integer) list[0]);
                    break;
                case EVENT_SETTINGS_CLICK:
                    logger.action(MetricsEvent.ACTION_VOLUME_SETTINGS);
                    break;
                case EVENT_EXTERNAL_RINGER_MODE_CHANGED:
                    MetricsLogger.action(context, MetricsEvent.ACTION_RINGER_MODE,
                            (Integer) list[0]);
                    // fall through
                case EVENT_INTERNAL_RINGER_MODE_CHANGED:
                    sb.append(ringerModeToString((Integer) list[0]));
                    break;
                case EVENT_ZEN_MODE_CHANGED:
                    sb.append(zenModeToString((Integer) list[0]));
                    break;
                case EVENT_SUPPRESSOR_CHANGED:
                    sb.append(list[0]).append(' ').append(list[1]);
                    break;
                case EVENT_SHOW_OVERHEAT_ALARM:
                    MetricsLogger.visible(context, MetricsEvent.POWER_OVERHEAT_ALARM);
                    MetricsLogger.histogram(context, "show_overheat_alarm",
                            (Boolean) list[1] ? 1 : 0);
                    sb.append(SHOW_REASONS[(Integer) list[0]]).append(" keyguard=").append(list[1]);
                    break;
                case EVENT_DISMISS_OVERHEAT_ALARM:
                    MetricsLogger.hidden(context, MetricsEvent.POWER_OVERHEAT_ALARM);
                    MetricsLogger.histogram(context, "dismiss_overheat_alarm",
                            (Boolean) list[1] ? 1 : 0);
                    sb.append(DISMISS_REASONS[(Integer) list[0]]).append(" keyguard=").append(
                            list[1]);
                    break;
                default:
                    sb.append(Arrays.asList(list));
                    break;
            }
        }
        Log.i(TAG, sb.toString());
        if (sCallback != null) {
            sCallback.writeEvent(time, tag, list);
        }
    }

    public static void writeState(long time, State state) {
        if (sCallback != null) {
            sCallback.writeState(time, state);
        }
    }

    private static String iconStateToString(int iconState) {
        switch (iconState) {
            case ICON_STATE_UNMUTE: return "unmute";
            case ICON_STATE_MUTE: return "mute";
            case ICON_STATE_VIBRATE: return "vibrate";
            default: return "unknown_state_" + iconState;
        }
    }

    private static String ringerModeToString(int ringerMode) {
        switch (ringerMode) {
            case AudioManager.RINGER_MODE_SILENT: return "silent";
            case AudioManager.RINGER_MODE_VIBRATE: return "vibrate";
            case AudioManager.RINGER_MODE_NORMAL: return "normal";
            default: return "unknown";
        }
    }

    private static String zenModeToString(int zenMode) {
        switch (zenMode) {
            case Global.ZEN_MODE_OFF: return "off";
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS: return "important_interruptions";
            case Global.ZEN_MODE_ALARMS: return "alarms";
            case Global.ZEN_MODE_NO_INTERRUPTIONS: return "no_interruptions";
            default: return "unknown";
        }
    }

    public interface Callback {
        void writeEvent(long time, int tag, Object[] list);
        void writeState(long time, State state);
    }
}
