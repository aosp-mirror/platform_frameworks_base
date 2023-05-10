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

import android.media.AudioManager;
import android.media.AudioSystem;
import android.provider.Settings.Global;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
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
    public static final int EVENT_KEY = 4; // (stream|int) (lastAudibleStreamVolume)
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
    public static final int EVENT_TOUCH_LEVEL_DONE = 16;  // (stream|int) (level|int)
    public static final int EVENT_ZEN_CONFIG_CHANGED = 17; // (allow/disallow|string)
    public static final int EVENT_RINGER_TOGGLE = 18; // (ringer_mode)
    public static final int EVENT_SHOW_USB_OVERHEAT_ALARM = 19; // (reason|int) (keyguard|bool)
    public static final int EVENT_DISMISS_USB_OVERHEAT_ALARM = 20; // (reason|int) (keyguard|bool)
    public static final int EVENT_ODI_CAPTIONS_CLICK = 21;
    public static final int EVENT_ODI_CAPTIONS_TOOLTIP_CLICK = 22;

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
            "show_usb_overheat_alarm",
            "dismiss_usb_overheat_alarm",
            "odi_captions_click",
            "odi_captions_tooltip_click"
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
    public static final int DISMISS_REASON_USB_OVERHEAD_ALARM_CHANGED = 9;
    public static final int DISMISS_REASON_CSD_WARNING_TIMEOUT = 10;
    public static final int DISMISS_REASON_POSTURE_CHANGED = 11;

    public static final String[] DISMISS_REASONS = {
            "unknown",
            "touch_outside",
            "volume_controller",
            "timeout",
            "screen_off",
            "settings_clicked",
            "done_clicked",
            "a11y_stream_changed",
            "output_chooser",
            "usb_temperature_below_threshold",
            "csd_warning_timeout",
            "posture_changed"
    };

    public static final int SHOW_REASON_UNKNOWN = 0;
    public static final int SHOW_REASON_VOLUME_CHANGED = 1;
    public static final int SHOW_REASON_REMOTE_VOLUME_CHANGED = 2;
    public static final int SHOW_REASON_USB_OVERHEAD_ALARM_CHANGED = 3;
    public static final String[] SHOW_REASONS = {
        "unknown",
        "volume_changed",
        "remote_volume_changed",
        "usb_temperature_above_threshold"
    };

    public static final int ICON_STATE_UNKNOWN = 0;
    public static final int ICON_STATE_UNMUTE = 1;
    public static final int ICON_STATE_MUTE = 2;
    public static final int ICON_STATE_VIBRATE = 3;

    @VisibleForTesting
    public enum VolumeDialogOpenEvent implements UiEventLogger.UiEventEnum {
        //TODO zap the lock/unlock distinction
        INVALID(0),
        @UiEvent(doc = "The volume dialog was shown because the volume changed")
        VOLUME_DIALOG_SHOW_VOLUME_CHANGED(128),
        @UiEvent(doc = "The volume dialog was shown because the volume changed remotely")
        VOLUME_DIALOG_SHOW_REMOTE_VOLUME_CHANGED(129),
        @UiEvent(doc = "The volume dialog was shown because the usb high temperature alarm changed")
        VOLUME_DIALOG_SHOW_USB_TEMP_ALARM_CHANGED(130);

        private final int mId;
        VolumeDialogOpenEvent(int id) {
            mId = id;
        }
        public int getId() {
            return mId;
        }
        static VolumeDialogOpenEvent fromReasons(int reason) {
            switch (reason) {
                case SHOW_REASON_VOLUME_CHANGED:
                    return VOLUME_DIALOG_SHOW_VOLUME_CHANGED;
                case SHOW_REASON_REMOTE_VOLUME_CHANGED:
                    return VOLUME_DIALOG_SHOW_REMOTE_VOLUME_CHANGED;
                case SHOW_REASON_USB_OVERHEAD_ALARM_CHANGED:
                    return VOLUME_DIALOG_SHOW_USB_TEMP_ALARM_CHANGED;
            }
            return INVALID;
        }
    }

    @VisibleForTesting
    public enum VolumeDialogCloseEvent implements UiEventLogger.UiEventEnum {
        INVALID(0),
        @UiEvent(doc = "The volume dialog was dismissed because of a touch outside the dialog")
        VOLUME_DIALOG_DISMISS_TOUCH_OUTSIDE(134),
        @UiEvent(doc = "The system asked the volume dialog to close, e.g. for a navigation bar "
                 + "touch, or ActivityManager ACTION_CLOSE_SYSTEM_DIALOGS broadcast.")
        VOLUME_DIALOG_DISMISS_SYSTEM(135),
        @UiEvent(doc = "The volume dialog was dismissed because it timed out")
        VOLUME_DIALOG_DISMISS_TIMEOUT(136),
        @UiEvent(doc = "The volume dialog was dismissed because the screen turned off")
        VOLUME_DIALOG_DISMISS_SCREEN_OFF(137),
        @UiEvent(doc = "The volume dialog was dismissed because the settings icon was clicked")
        VOLUME_DIALOG_DISMISS_SETTINGS(138),
        // reserving 139 for DISMISS_REASON_DONE_CLICKED which is currently unused
        @UiEvent(doc = "The volume dialog was dismissed because the stream no longer exists")
        VOLUME_DIALOG_DISMISS_STREAM_GONE(140),
        // reserving 141 for DISMISS_REASON_OUTPUT_CHOOSER which is currently unused
        @UiEvent(doc = "The volume dialog was dismissed because the usb high temperature alarm "
                 + "changed")
        VOLUME_DIALOG_DISMISS_USB_TEMP_ALARM_CHANGED(142);

        private final int mId;
        VolumeDialogCloseEvent(int id) {
            mId = id;
        }
        public int getId() {
            return mId;
        }

        static VolumeDialogCloseEvent fromReason(int reason) {
            switch (reason) {
                case DISMISS_REASON_TOUCH_OUTSIDE:
                    return VOLUME_DIALOG_DISMISS_TOUCH_OUTSIDE;
                case DISMISS_REASON_VOLUME_CONTROLLER:
                    return VOLUME_DIALOG_DISMISS_SYSTEM;
                case DISMISS_REASON_TIMEOUT:
                    return VOLUME_DIALOG_DISMISS_TIMEOUT;
                case DISMISS_REASON_SCREEN_OFF:
                    return VOLUME_DIALOG_DISMISS_SCREEN_OFF;
                case DISMISS_REASON_SETTINGS_CLICKED:
                    return VOLUME_DIALOG_DISMISS_SETTINGS;
                case DISMISS_STREAM_GONE:
                    return VOLUME_DIALOG_DISMISS_STREAM_GONE;
                case DISMISS_REASON_USB_OVERHEAD_ALARM_CHANGED:
                    return VOLUME_DIALOG_DISMISS_USB_TEMP_ALARM_CHANGED;
            }
            return INVALID;
        }
    }

    @VisibleForTesting
    public enum VolumeDialogEvent implements UiEventLogger.UiEventEnum {
        INVALID(0),
        @UiEvent(doc = "The volume dialog settings icon was clicked")
        VOLUME_DIALOG_SETTINGS_CLICK(143),
        @UiEvent(doc = "The volume dialog details were expanded")
        VOLUME_DIALOG_EXPAND_DETAILS(144),
        @UiEvent(doc = "The volume dialog details were collapsed")
        VOLUME_DIALOG_COLLAPSE_DETAILS(145),
        @UiEvent(doc = "The active audio stream changed")
        VOLUME_DIALOG_ACTIVE_STREAM_CHANGED(146),
        @UiEvent(doc = "The audio stream was muted via icon")
        VOLUME_DIALOG_MUTE_STREAM(147),
        @UiEvent(doc = "The audio stream was unmuted via icon")
        VOLUME_DIALOG_UNMUTE_STREAM(148),
        @UiEvent(doc = "The audio stream was set to vibrate via icon")
        VOLUME_DIALOG_TO_VIBRATE_STREAM(149),
        @UiEvent(doc = "The audio stream was set to non-silent via slider")
        VOLUME_DIALOG_SLIDER(150),
        @UiEvent(doc = "The audio stream was set to silent via slider")
        VOLUME_DIALOG_SLIDER_TO_ZERO(151),
        @UiEvent(doc = "The audio volume was adjusted to silent via key")
        VOLUME_KEY_TO_ZERO(152),
        @UiEvent(doc = "The audio volume was adjusted to non-silent via key")
        VOLUME_KEY(153),
        @UiEvent(doc = "The ringer mode was toggled to silent")
        RINGER_MODE_SILENT(154),
        @UiEvent(doc = "The ringer mode was toggled to vibrate")
        RINGER_MODE_VIBRATE(155),
        @UiEvent(doc = "The ringer mode was toggled to normal")
        RINGER_MODE_NORMAL(334),
        @UiEvent(doc = "USB Overheat alarm was raised")
        USB_OVERHEAT_ALARM(160),
        @UiEvent(doc = "USB Overheat alarm was dismissed")
        USB_OVERHEAT_ALARM_DISMISSED(161);

        private final int mId;

        VolumeDialogEvent(int id) {
            mId = id;
        }

        public int getId() {
            return mId;
        }

        static VolumeDialogEvent fromIconState(int iconState) {
            switch (iconState) {
                case ICON_STATE_UNMUTE:
                    return VOLUME_DIALOG_UNMUTE_STREAM;
                case ICON_STATE_MUTE:
                    return VOLUME_DIALOG_MUTE_STREAM;
                case ICON_STATE_VIBRATE:
                    return VOLUME_DIALOG_TO_VIBRATE_STREAM;
                default:
                    return INVALID;
            }
        }

        static VolumeDialogEvent fromSliderLevel(int level) {
            return level == 0 ? VOLUME_DIALOG_SLIDER_TO_ZERO : VOLUME_DIALOG_SLIDER;
        }

        static VolumeDialogEvent fromKeyLevel(int level) {
            return level == 0 ? VOLUME_KEY_TO_ZERO : VOLUME_KEY;
        }

        static VolumeDialogEvent fromRingerMode(int ringerMode) {
            switch (ringerMode) {
                case AudioManager.RINGER_MODE_SILENT:
                    return RINGER_MODE_SILENT;
                case AudioManager.RINGER_MODE_VIBRATE:
                    return RINGER_MODE_VIBRATE;
                case AudioManager.RINGER_MODE_NORMAL:
                    return RINGER_MODE_NORMAL;
                default:
                    return INVALID;
            }
        }
    }

    @VisibleForTesting
    public enum ZenModeEvent implements UiEventLogger.UiEventEnum {
        INVALID(0),
        @UiEvent(doc = "Zen (do not disturb) mode was toggled to off")
        ZEN_MODE_OFF(335),
        @UiEvent(doc = "Zen (do not disturb) mode was toggled to important interruptions only")
        ZEN_MODE_IMPORTANT_ONLY(157),
        @UiEvent(doc = "Zen (do not disturb) mode was toggled to alarms only")
        ZEN_MODE_ALARMS_ONLY(158),
        @UiEvent(doc = "Zen (do not disturb) mode was toggled to block all interruptions")
        ZEN_MODE_NO_INTERRUPTIONS(159);

        private final int mId;
        ZenModeEvent(int id) {
            mId = id;
        }
        public int getId() {
            return mId;
        }

        static ZenModeEvent fromZenMode(int zenMode) {
            switch (zenMode) {
                case Global.ZEN_MODE_OFF: return ZEN_MODE_OFF;
                case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS: return ZEN_MODE_IMPORTANT_ONLY;
                case Global.ZEN_MODE_ALARMS: return ZEN_MODE_ALARMS_ONLY;
                case Global.ZEN_MODE_NO_INTERRUPTIONS: return ZEN_MODE_NO_INTERRUPTIONS;
                default: return INVALID;
            }
        }
    }

    public static Callback sCallback;
    @VisibleForTesting
    static MetricsLogger sLegacyLogger = new MetricsLogger();
    @VisibleForTesting
    static UiEventLogger sUiEventLogger = new UiEventLoggerImpl();

    /**
     * Logs an event to the system log, to sCallback if present, and to the logEvent destinations.
     * @param tag One of the EVENT_* codes above.
     * @param list Any additional event-specific arguments, documented above.
     */
    public static void writeEvent(int tag, Object... list) {
        final long time = System.currentTimeMillis();
        Log.i(TAG, logEvent(tag, list));
        if (sCallback != null) {
            sCallback.writeEvent(time, tag, list);
        }
    }

    /**
     * Logs an event to the event log and UiEvent (statsd) logging. Compare writeEvent, which
     * adds more log destinations.
     * @param tag One of the EVENT_* codes above.
     * @param list Any additional event-specific arguments, documented above.
     * @return String a readable description of the event.  Begins "writeEvent <tag_description>"
     * if the tag is valid.
     */
    public static String logEvent(int tag, Object... list) {
        if (tag >= EVENT_TAGS.length) {
            return "";
        }
        final StringBuilder sb = new StringBuilder("writeEvent ").append(EVENT_TAGS[tag]);
        // Handle events without extra data
        if (list == null || list.length == 0) {
            if (tag == EVENT_SETTINGS_CLICK) {
                sLegacyLogger.action(MetricsEvent.ACTION_VOLUME_SETTINGS);
                sUiEventLogger.log(VolumeDialogEvent.VOLUME_DIALOG_SETTINGS_CLICK);
            }
            return sb.toString();
        }
        // Handle events with extra data. We've established list[0] exists.
        sb.append(" ");
        switch (tag) {
            case EVENT_SHOW_DIALOG:
                sLegacyLogger.visible(MetricsEvent.VOLUME_DIALOG);
                if (list.length > 1) {
                    final Integer reason = (Integer) list[0];
                    final Boolean keyguard = (Boolean) list[1];
                    sLegacyLogger.histogram("volume_from_keyguard", keyguard ? 1 : 0);
                    sUiEventLogger.log(VolumeDialogOpenEvent.fromReasons(reason));
                    sb.append(SHOW_REASONS[reason]).append(" keyguard=").append(keyguard);
                }
                break;
            case EVENT_EXPAND: {
                final Boolean expand = (Boolean) list[0];
                sLegacyLogger.visibility(MetricsEvent.VOLUME_DIALOG_DETAILS, expand);
                sUiEventLogger.log(expand ? VolumeDialogEvent.VOLUME_DIALOG_EXPAND_DETAILS
                        : VolumeDialogEvent.VOLUME_DIALOG_COLLAPSE_DETAILS);
                sb.append(expand);
                break;
            }
            case EVENT_DISMISS_DIALOG: {
                sLegacyLogger.hidden(MetricsEvent.VOLUME_DIALOG);
                final Integer reason = (Integer) list[0];
                sUiEventLogger.log(VolumeDialogCloseEvent.fromReason(reason));
                sb.append(DISMISS_REASONS[reason]);
                break;
            }
            case EVENT_ACTIVE_STREAM_CHANGED: {
                final Integer stream = (Integer) list[0];
                sLegacyLogger.action(MetricsEvent.ACTION_VOLUME_STREAM, stream);
                sUiEventLogger.log(VolumeDialogEvent.VOLUME_DIALOG_ACTIVE_STREAM_CHANGED);
                sb.append(AudioSystem.streamToString(stream));
                break;
            }
            case EVENT_ICON_CLICK:
                if (list.length > 1) {
                    final Integer stream = (Integer) list[0];
                    sLegacyLogger.action(MetricsEvent.ACTION_VOLUME_ICON, stream);
                    final Integer iconState = (Integer) list[1];
                    sUiEventLogger.log(VolumeDialogEvent.fromIconState(iconState));
                    sb.append(AudioSystem.streamToString(stream)).append(' ')
                            .append(iconStateToString(iconState));
                }
                break;
            case EVENT_TOUCH_LEVEL_DONE: // (stream|int) (level|int)
                if (list.length > 1) {
                    final Integer level = (Integer) list[1];
                    sLegacyLogger.action(MetricsEvent.ACTION_VOLUME_SLIDER, level);
                    sUiEventLogger.log(VolumeDialogEvent.fromSliderLevel(level));
                }
                // fall through
            case EVENT_TOUCH_LEVEL_CHANGED:
            case EVENT_LEVEL_CHANGED:
            case EVENT_MUTE_CHANGED:  // (stream|int) (level|int)
                if (list.length > 1) {
                    sb.append(AudioSystem.streamToString((Integer) list[0])).append(' ')
                            .append(list[1]);
                }
                break;
            case EVENT_KEY: // (stream|int) (lastAudibleStreamVolume)
                if (list.length > 1) {
                    final Integer stream = (Integer) list[0];
                    sLegacyLogger.action(MetricsEvent.ACTION_VOLUME_KEY, stream);
                    final Integer level = (Integer) list[1];
                    sUiEventLogger.log(VolumeDialogEvent.fromKeyLevel(level));
                    sb.append(AudioSystem.streamToString(stream)).append(' ').append(level);
                }
                break;
            case EVENT_RINGER_TOGGLE: {
                final Integer ringerMode = (Integer) list[0];
                sLegacyLogger.action(MetricsEvent.ACTION_VOLUME_RINGER_TOGGLE, ringerMode);
                sUiEventLogger.log(VolumeDialogEvent.fromRingerMode(ringerMode));
                sb.append(ringerModeToString(ringerMode));
                break;
            }
            case EVENT_EXTERNAL_RINGER_MODE_CHANGED: {
                final Integer ringerMode = (Integer) list[0];
                sLegacyLogger.action(MetricsEvent.ACTION_RINGER_MODE, ringerMode);
            }
                // fall through
            case EVENT_INTERNAL_RINGER_MODE_CHANGED: {
                final Integer ringerMode = (Integer) list[0];
                sb.append(ringerModeToString(ringerMode));
                break;
            }
            case EVENT_ZEN_MODE_CHANGED: {
                final Integer zenMode = (Integer) list[0];
                sb.append(zenModeToString(zenMode));
                sUiEventLogger.log(ZenModeEvent.fromZenMode(zenMode));
                break;
            }
            case EVENT_SUPPRESSOR_CHANGED:  // (component|string) (name|string)
                if (list.length > 1) {
                    sb.append(list[0]).append(' ').append(list[1]);
                }
                break;
            case EVENT_SHOW_USB_OVERHEAT_ALARM:
                sLegacyLogger.visible(MetricsEvent.POWER_OVERHEAT_ALARM);
                sUiEventLogger.log(VolumeDialogEvent.USB_OVERHEAT_ALARM);
                if (list.length > 1) {
                    final Boolean keyguard = (Boolean) list[1];
                    sLegacyLogger.histogram("show_usb_overheat_alarm", keyguard ? 1 : 0);
                    final Integer reason = (Integer) list[0];
                    sb.append(SHOW_REASONS[reason]).append(" keyguard=").append(keyguard);
                }
                break;
            case EVENT_DISMISS_USB_OVERHEAT_ALARM:
                sLegacyLogger.hidden(MetricsEvent.POWER_OVERHEAT_ALARM);
                sUiEventLogger.log(VolumeDialogEvent.USB_OVERHEAT_ALARM_DISMISSED);
                if (list.length > 1) {
                    final Boolean keyguard = (Boolean) list[1];
                    sLegacyLogger.histogram("dismiss_usb_overheat_alarm", keyguard ? 1 : 0);
                    final Integer reason = (Integer) list[0];
                    sb.append(DISMISS_REASONS[reason])
                            .append(" keyguard=").append(keyguard);
                }
                break;
            default:
                sb.append(Arrays.asList(list));
                break;
        }
        return sb.toString();
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
