/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import static com.android.internal.util.ContrastColorUtil.satisfiesTextContrast;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.IdRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ShortcutInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.PlayerBase;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.BidiFormatter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TextAppearanceSpan;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.Gravity;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ContrastColorUtil;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A class that represents how a persistent notification is to be presented to
 * the user using the {@link android.app.NotificationManager}.
 *
 * <p>The {@link Notification.Builder Notification.Builder} has been added to make it
 * easier to construct Notifications.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For a guide to creating notifications, read the
 * <a href="{@docRoot}guide/topics/ui/notifiers/notifications.html">Status Bar Notifications</a>
 * developer guide.</p>
 * </div>
 */
public class Notification implements Parcelable
{
    private static final String TAG = "Notification";

    /**
     * An activity that provides a user interface for adjusting notification preferences for its
     * containing application.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String INTENT_CATEGORY_NOTIFICATION_PREFERENCES
            = "android.intent.category.NOTIFICATION_PREFERENCES";

    /**
     * Optional extra for {@link #INTENT_CATEGORY_NOTIFICATION_PREFERENCES}. If provided, will
     * contain a {@link NotificationChannel#getId() channel id} that can be used to narrow down
     * what settings should be shown in the target app.
     */
    public static final String EXTRA_CHANNEL_ID = "android.intent.extra.CHANNEL_ID";

    /**
     * Optional extra for {@link #INTENT_CATEGORY_NOTIFICATION_PREFERENCES}. If provided, will
     * contain a {@link NotificationChannelGroup#getId() group id} that can be used to narrow down
     * what settings should be shown in the target app.
     */
    public static final String EXTRA_CHANNEL_GROUP_ID = "android.intent.extra.CHANNEL_GROUP_ID";

    /**
     * Optional extra for {@link #INTENT_CATEGORY_NOTIFICATION_PREFERENCES}. If provided, will
     * contain the tag provided to {@link NotificationManager#notify(String, int, Notification)}
     * that can be used to narrow down what settings should be shown in the target app.
     */
    public static final String EXTRA_NOTIFICATION_TAG = "android.intent.extra.NOTIFICATION_TAG";

    /**
     * Optional extra for {@link #INTENT_CATEGORY_NOTIFICATION_PREFERENCES}. If provided, will
     * contain the id provided to {@link NotificationManager#notify(String, int, Notification)}
     * that can be used to narrow down what settings should be shown in the target app.
     */
    public static final String EXTRA_NOTIFICATION_ID = "android.intent.extra.NOTIFICATION_ID";

    /**
     * Use all default values (where applicable).
     */
    public static final int DEFAULT_ALL = ~0;

    /**
     * Use the default notification sound. This will ignore any given
     * {@link #sound}.
     *
     * <p>
     * A notification that is noisy is more likely to be presented as a heads-up notification.
     * </p>
     *
     * @see #defaults
     */

    public static final int DEFAULT_SOUND = 1;

    /**
     * Use the default notification vibrate. This will ignore any given
     * {@link #vibrate}. Using phone vibration requires the
     * {@link android.Manifest.permission#VIBRATE VIBRATE} permission.
     *
     * <p>
     * A notification that vibrates is more likely to be presented as a heads-up notification.
     * </p>
     *
     * @see #defaults
     */

    public static final int DEFAULT_VIBRATE = 2;

    /**
     * Use the default notification lights. This will ignore the
     * {@link #FLAG_SHOW_LIGHTS} bit, and {@link #ledARGB}, {@link #ledOffMS}, or
     * {@link #ledOnMS}.
     *
     * @see #defaults
     */

    public static final int DEFAULT_LIGHTS = 4;

    /**
     * Maximum length of CharSequences accepted by Builder and friends.
     *
     * <p>
     * Avoids spamming the system with overly large strings such as full e-mails.
     */
    private static final int MAX_CHARSEQUENCE_LENGTH = 5 * 1024;

    /**
     * Maximum entries of reply text that are accepted by Builder and friends.
     */
    private static final int MAX_REPLY_HISTORY = 5;

    /**
     * Maximum number of (generic) action buttons in a notification (contextual action buttons are
     * handled separately).
     * @hide
     */
    public static final int MAX_ACTION_BUTTONS = 3;

    /**
     * If the notification contained an unsent draft for a RemoteInput when the user clicked on it,
     * we're adding the draft as a String extra to the {@link #contentIntent} using this key.
     *
     * <p>Apps may use this extra to prepopulate text fields in the app, where the user usually
     * sends messages.</p>
     */
    public static final String EXTRA_REMOTE_INPUT_DRAFT = "android.remoteInputDraft";

    /**
     * A timestamp related to this notification, in milliseconds since the epoch.
     *
     * Default value: {@link System#currentTimeMillis() Now}.
     *
     * Choose a timestamp that will be most relevant to the user. For most finite events, this
     * corresponds to the time the event happened (or will happen, in the case of events that have
     * yet to occur but about which the user is being informed). Indefinite events should be
     * timestamped according to when the activity began.
     *
     * Some examples:
     *
     * <ul>
     *   <li>Notification of a new chat message should be stamped when the message was received.</li>
     *   <li>Notification of an ongoing file download (with a progress bar, for example) should be stamped when the download started.</li>
     *   <li>Notification of a completed file download should be stamped when the download finished.</li>
     *   <li>Notification of an upcoming meeting should be stamped with the time the meeting will begin (that is, in the future).</li>
     *   <li>Notification of an ongoing stopwatch (increasing timer) should be stamped with the watch's start time.
     *   <li>Notification of an ongoing countdown timer should be stamped with the timer's end time.
     * </ul>
     *
     * For apps targeting {@link android.os.Build.VERSION_CODES#N} and above, this time is not shown
     * anymore by default and must be opted into by using
     * {@link android.app.Notification.Builder#setShowWhen(boolean)}
     */
    public long when;

    /**
     * The creation time of the notification
     */
    private long creationTime;

    /**
     * The resource id of a drawable to use as the icon in the status bar.
     *
     * @deprecated Use {@link Builder#setSmallIcon(Icon)} instead.
     */
    @Deprecated
    @DrawableRes
    public int icon;

    /**
     * If the icon in the status bar is to have more than one level, you can set this.  Otherwise,
     * leave it at its default value of 0.
     *
     * @see android.widget.ImageView#setImageLevel
     * @see android.graphics.drawable.Drawable#setLevel
     */
    public int iconLevel;

    /**
     * The number of events that this notification represents. For example, in a new mail
     * notification, this could be the number of unread messages.
     *
     * The system may or may not use this field to modify the appearance of the notification.
     * Starting with {@link android.os.Build.VERSION_CODES#O}, the number may be displayed as a
     * badge icon in Launchers that support badging.
     */
    public int number = 0;

    /**
     * The intent to execute when the expanded status entry is clicked.  If
     * this is an activity, it must include the
     * {@link android.content.Intent#FLAG_ACTIVITY_NEW_TASK} flag, which requires
     * that you take care of task management as described in the
     * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back
     * Stack</a> document.  In particular, make sure to read the notification section
     * <a href="{@docRoot}guide/topics/ui/notifiers/notifications.html#HandlingNotifications">Handling
     * Notifications</a> for the correct ways to launch an application from a
     * notification.
     */
    public PendingIntent contentIntent;

    /**
     * The intent to execute when the notification is explicitly dismissed by the user, either with
     * the "Clear All" button or by swiping it away individually.
     *
     * This probably shouldn't be launching an activity since several of those will be sent
     * at the same time.
     */
    public PendingIntent deleteIntent;

    /**
     * An intent to launch instead of posting the notification to the status bar.
     *
     * <p>
     * The system UI may choose to display a heads-up notification, instead of
     * launching this intent, while the user is using the device.
     * </p>
     *
     * @see Notification.Builder#setFullScreenIntent
     */
    public PendingIntent fullScreenIntent;

    /**
     * Text that summarizes this notification for accessibility services.
     *
     * As of the L release, this text is no longer shown on screen, but it is still useful to
     * accessibility services (where it serves as an audible announcement of the notification's
     * appearance).
     *
     * @see #tickerView
     */
    public CharSequence tickerText;

    /**
     * Formerly, a view showing the {@link #tickerText}.
     *
     * No longer displayed in the status bar as of API 21.
     */
    @Deprecated
    public RemoteViews tickerView;

    /**
     * The view that will represent this notification in the notification list (which is pulled
     * down from the status bar).
     *
     * As of N, this field may be null. The notification view is determined by the inputs
     * to {@link Notification.Builder}; a custom RemoteViews can optionally be
     * supplied with {@link Notification.Builder#setCustomContentView(RemoteViews)}.
     */
    @Deprecated
    public RemoteViews contentView;

    /**
     * A large-format version of {@link #contentView}, giving the Notification an
     * opportunity to show more detail. The system UI may choose to show this
     * instead of the normal content view at its discretion.
     *
     * As of N, this field may be null. The expanded notification view is determined by the
     * inputs to {@link Notification.Builder}; a custom RemoteViews can optionally be
     * supplied with {@link Notification.Builder#setCustomBigContentView(RemoteViews)}.
     */
    @Deprecated
    public RemoteViews bigContentView;


    /**
     * A medium-format version of {@link #contentView}, providing the Notification an
     * opportunity to add action buttons to contentView. At its discretion, the system UI may
     * choose to show this as a heads-up notification, which will pop up so the user can see
     * it without leaving their current activity.
     *
     * As of N, this field may be null. The heads-up notification view is determined by the
     * inputs to {@link Notification.Builder}; a custom RemoteViews can optionally be
     * supplied with {@link Notification.Builder#setCustomHeadsUpContentView(RemoteViews)}.
     */
    @Deprecated
    public RemoteViews headsUpContentView;

    private boolean mUsesStandardHeader;

    private static final ArraySet<Integer> STANDARD_LAYOUTS = new ArraySet<>();
    static {
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_base);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_base);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_picture);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_text);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_inbox);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_messaging);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_media);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_media);
        STANDARD_LAYOUTS.add(R.layout.notification_template_header);
    }

    /**
     * A large bitmap to be shown in the notification content area.
     *
     * @deprecated Use {@link Builder#setLargeIcon(Icon)} instead.
     */
    @Deprecated
    public Bitmap largeIcon;

    /**
     * The sound to play.
     *
     * <p>
     * A notification that is noisy is more likely to be presented as a heads-up notification.
     * </p>
     *
     * <p>
     * To play the default notification sound, see {@link #defaults}.
     * </p>
     * @deprecated use {@link NotificationChannel#getSound()}.
     */
    @Deprecated
    public Uri sound;

    /**
     * Use this constant as the value for audioStreamType to request that
     * the default stream type for notifications be used.  Currently the
     * default stream type is {@link AudioManager#STREAM_NOTIFICATION}.
     *
     * @deprecated Use {@link NotificationChannel#getAudioAttributes()} instead.
     */
    @Deprecated
    public static final int STREAM_DEFAULT = -1;

    /**
     * The audio stream type to use when playing the sound.
     * Should be one of the STREAM_ constants from
     * {@link android.media.AudioManager}.
     *
     * @deprecated Use {@link #audioAttributes} instead.
     */
    @Deprecated
    public int audioStreamType = STREAM_DEFAULT;

    /**
     * The default value of {@link #audioAttributes}.
     */
    public static final AudioAttributes AUDIO_ATTRIBUTES_DEFAULT = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build();

    /**
     * The {@link AudioAttributes audio attributes} to use when playing the sound.
     *
     * @deprecated use {@link NotificationChannel#getAudioAttributes()} instead.
     */
    @Deprecated
    public AudioAttributes audioAttributes = AUDIO_ATTRIBUTES_DEFAULT;

    /**
     * The pattern with which to vibrate.
     *
     * <p>
     * To vibrate the default pattern, see {@link #defaults}.
     * </p>
     *
     * @see android.os.Vibrator#vibrate(long[],int)
     * @deprecated use {@link NotificationChannel#getVibrationPattern()}.
     */
    @Deprecated
    public long[] vibrate;

    /**
     * The color of the led.  The hardware will do its best approximation.
     *
     * @see #FLAG_SHOW_LIGHTS
     * @see #flags
     * @deprecated use {@link NotificationChannel#shouldShowLights()}.
     */
    @ColorInt
    @Deprecated
    public int ledARGB;

    /**
     * The number of milliseconds for the LED to be on while it's flashing.
     * The hardware will do its best approximation.
     *
     * @see #FLAG_SHOW_LIGHTS
     * @see #flags
     * @deprecated use {@link NotificationChannel#shouldShowLights()}.
     */
    @Deprecated
    public int ledOnMS;

    /**
     * The number of milliseconds for the LED to be off while it's flashing.
     * The hardware will do its best approximation.
     *
     * @see #FLAG_SHOW_LIGHTS
     * @see #flags
     *
     * @deprecated use {@link NotificationChannel#shouldShowLights()}.
     */
    @Deprecated
    public int ledOffMS;

    /**
     * Specifies which values should be taken from the defaults.
     * <p>
     * To set, OR the desired from {@link #DEFAULT_SOUND},
     * {@link #DEFAULT_VIBRATE}, {@link #DEFAULT_LIGHTS}. For all default
     * values, use {@link #DEFAULT_ALL}.
     * </p>
     *
     * @deprecated use {@link NotificationChannel#getSound()} and
     * {@link NotificationChannel#shouldShowLights()} and
     * {@link NotificationChannel#shouldVibrate()}.
     */
    @Deprecated
    public int defaults;

    /**
     * Bit to be bitwise-ored into the {@link #flags} field that should be
     * set if you want the LED on for this notification.
     * <ul>
     * <li>To turn the LED off, pass 0 in the alpha channel for colorARGB
     *      or 0 for both ledOnMS and ledOffMS.</li>
     * <li>To turn the LED on, pass 1 for ledOnMS and 0 for ledOffMS.</li>
     * <li>To flash the LED, pass the number of milliseconds that it should
     *      be on and off to ledOnMS and ledOffMS.</li>
     * </ul>
     * <p>
     * Since hardware varies, you are not guaranteed that any of the values
     * you pass are honored exactly.  Use the system defaults if possible
     * because they will be set to values that work on any given hardware.
     * <p>
     * The alpha channel must be set for forward compatibility.
     *
     * @deprecated use {@link NotificationChannel#shouldShowLights()}.
     */
    @Deprecated
    public static final int FLAG_SHOW_LIGHTS        = 0x00000001;

    /**
     * Bit to be bitwise-ored into the {@link #flags} field that should be
     * set if this notification is in reference to something that is ongoing,
     * like a phone call.  It should not be set if this notification is in
     * reference to something that happened at a particular point in time,
     * like a missed phone call.
     */
    public static final int FLAG_ONGOING_EVENT      = 0x00000002;

    /**
     * Bit to be bitwise-ored into the {@link #flags} field that if set,
     * the audio will be repeated until the notification is
     * cancelled or the notification window is opened.
     */
    public static final int FLAG_INSISTENT          = 0x00000004;

    /**
     * Bit to be bitwise-ored into the {@link #flags} field that should be
     * set if you would only like the sound, vibrate and ticker to be played
     * if the notification was not already showing.
     */
    public static final int FLAG_ONLY_ALERT_ONCE    = 0x00000008;

    /**
     * Bit to be bitwise-ored into the {@link #flags} field that should be
     * set if the notification should be canceled when it is clicked by the
     * user.
     */
    public static final int FLAG_AUTO_CANCEL        = 0x00000010;

    /**
     * Bit to be bitwise-ored into the {@link #flags} field that should be
     * set if the notification should not be canceled when the user clicks
     * the Clear all button.
     */
    public static final int FLAG_NO_CLEAR           = 0x00000020;

    /**
     * Bit to be bitwise-ored into the {@link #flags} field that should be
     * set if this notification represents a currently running service.  This
     * will normally be set for you by {@link Service#startForeground}.
     */
    public static final int FLAG_FOREGROUND_SERVICE = 0x00000040;

    /**
     * Obsolete flag indicating high-priority notifications; use the priority field instead.
     *
     * @deprecated Use {@link #priority} with a positive value.
     */
    @Deprecated
    public static final int FLAG_HIGH_PRIORITY      = 0x00000080;

    /**
     * Bit to be bitswise-ored into the {@link #flags} field that should be
     * set if this notification is relevant to the current device only
     * and it is not recommended that it bridge to other devices.
     */
    public static final int FLAG_LOCAL_ONLY         = 0x00000100;

    /**
     * Bit to be bitswise-ored into the {@link #flags} field that should be
     * set if this notification is the group summary for a group of notifications.
     * Grouped notifications may display in a cluster or stack on devices which
     * support such rendering. Requires a group key also be set using {@link Builder#setGroup}.
     */
    public static final int FLAG_GROUP_SUMMARY      = 0x00000200;

    /**
     * Bit to be bitswise-ored into the {@link #flags} field that should be
     * set if this notification is the group summary for an auto-group of notifications.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_AUTOGROUP_SUMMARY  = 0x00000400;

    /**
     * @hide
     */
    public static final int FLAG_CAN_COLORIZE = 0x00000800;

    public int flags;

    /** @hide */
    @IntDef(prefix = { "PRIORITY_" }, value = {
            PRIORITY_DEFAULT,
            PRIORITY_LOW,
            PRIORITY_MIN,
            PRIORITY_HIGH,
            PRIORITY_MAX
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Priority {}

    /**
     * Default notification {@link #priority}. If your application does not prioritize its own
     * notifications, use this value for all notifications.
     *
     * @deprecated use {@link NotificationManager#IMPORTANCE_DEFAULT} instead.
     */
    @Deprecated
    public static final int PRIORITY_DEFAULT = 0;

    /**
     * Lower {@link #priority}, for items that are less important. The UI may choose to show these
     * items smaller, or at a different position in the list, compared with your app's
     * {@link #PRIORITY_DEFAULT} items.
     *
     * @deprecated use {@link NotificationManager#IMPORTANCE_LOW} instead.
     */
    @Deprecated
    public static final int PRIORITY_LOW = -1;

    /**
     * Lowest {@link #priority}; these items might not be shown to the user except under special
     * circumstances, such as detailed notification logs.
     *
     * @deprecated use {@link NotificationManager#IMPORTANCE_MIN} instead.
     */
    @Deprecated
    public static final int PRIORITY_MIN = -2;

    /**
     * Higher {@link #priority}, for more important notifications or alerts. The UI may choose to
     * show these items larger, or at a different position in notification lists, compared with
     * your app's {@link #PRIORITY_DEFAULT} items.
     *
     * @deprecated use {@link NotificationManager#IMPORTANCE_HIGH} instead.
     */
    @Deprecated
    public static final int PRIORITY_HIGH = 1;

    /**
     * Highest {@link #priority}, for your application's most important items that require the
     * user's prompt attention or input.
     *
     * @deprecated use {@link NotificationManager#IMPORTANCE_HIGH} instead.
     */
    @Deprecated
    public static final int PRIORITY_MAX = 2;

    /**
     * Relative priority for this notification.
     *
     * Priority is an indication of how much of the user's valuable attention should be consumed by
     * this notification. Low-priority notifications may be hidden from the user in certain
     * situations, while the user might be interrupted for a higher-priority notification. The
     * system will make a determination about how to interpret this priority when presenting
     * the notification.
     *
     * <p>
     * A notification that is at least {@link #PRIORITY_HIGH} is more likely to be presented
     * as a heads-up notification.
     * </p>
     *
     * @deprecated use {@link NotificationChannel#getImportance()} instead.
     */
    @Priority
    @Deprecated
    public int priority;

    /**
     * Accent color (an ARGB integer like the constants in {@link android.graphics.Color})
     * to be applied by the standard Style templates when presenting this notification.
     *
     * The current template design constructs a colorful header image by overlaying the
     * {@link #icon} image (stenciled in white) atop a field of this color. Alpha components are
     * ignored.
     */
    @ColorInt
    public int color = COLOR_DEFAULT;

    /**
     * Special value of {@link #color} telling the system not to decorate this notification with
     * any special color but instead use default colors when presenting this notification.
     */
    @ColorInt
    public static final int COLOR_DEFAULT = 0; // AKA Color.TRANSPARENT

    /**
     * Special value of {@link #color} used as a place holder for an invalid color.
     * @hide
     */
    @ColorInt
    public static final int COLOR_INVALID = 1;

    /**
     * Sphere of visibility of this notification, which affects how and when the SystemUI reveals
     * the notification's presence and contents in untrusted situations (namely, on the secure
     * lockscreen).
     *
     * The default level, {@link #VISIBILITY_PRIVATE}, behaves exactly as notifications have always
     * done on Android: The notification's {@link #icon} and {@link #tickerText} (if available) are
     * shown in all situations, but the contents are only available if the device is unlocked for
     * the appropriate user.
     *
     * A more permissive policy can be expressed by {@link #VISIBILITY_PUBLIC}; such a notification
     * can be read even in an "insecure" context (that is, above a secure lockscreen).
     * To modify the public version of this notification—for example, to redact some portions—see
     * {@link Builder#setPublicVersion(Notification)}.
     *
     * Finally, a notification can be made {@link #VISIBILITY_SECRET}, which will suppress its icon
     * and ticker until the user has bypassed the lockscreen.
     */
    public @Visibility int visibility;

    /** @hide */
    @IntDef(prefix = { "VISIBILITY_" }, value = {
            VISIBILITY_PUBLIC,
            VISIBILITY_PRIVATE,
            VISIBILITY_SECRET,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Visibility {}

    /**
     * Notification visibility: Show this notification in its entirety on all lockscreens.
     *
     * {@see #visibility}
     */
    public static final int VISIBILITY_PUBLIC = 1;

    /**
     * Notification visibility: Show this notification on all lockscreens, but conceal sensitive or
     * private information on secure lockscreens.
     *
     * {@see #visibility}
     */
    public static final int VISIBILITY_PRIVATE = 0;

    /**
     * Notification visibility: Do not reveal any part of this notification on a secure lockscreen.
     *
     * {@see #visibility}
     */
    public static final int VISIBILITY_SECRET = -1;

    /**
     * Notification category: incoming call (voice or video) or similar synchronous communication request.
     */
    public static final String CATEGORY_CALL = "call";

    /**
     * Notification category: map turn-by-turn navigation.
     */
    public static final String CATEGORY_NAVIGATION = "navigation";

    /**
     * Notification category: incoming direct message (SMS, instant message, etc.).
     */
    public static final String CATEGORY_MESSAGE = "msg";

    /**
     * Notification category: asynchronous bulk message (email).
     */
    public static final String CATEGORY_EMAIL = "email";

    /**
     * Notification category: calendar event.
     */
    public static final String CATEGORY_EVENT = "event";

    /**
     * Notification category: promotion or advertisement.
     */
    public static final String CATEGORY_PROMO = "promo";

    /**
     * Notification category: alarm or timer.
     */
    public static final String CATEGORY_ALARM = "alarm";

    /**
     * Notification category: progress of a long-running background operation.
     */
    public static final String CATEGORY_PROGRESS = "progress";

    /**
     * Notification category: social network or sharing update.
     */
    public static final String CATEGORY_SOCIAL = "social";

    /**
     * Notification category: error in background operation or authentication status.
     */
    public static final String CATEGORY_ERROR = "err";

    /**
     * Notification category: media transport control for playback.
     */
    public static final String CATEGORY_TRANSPORT = "transport";

    /**
     * Notification category: system or device status update.  Reserved for system use.
     */
    public static final String CATEGORY_SYSTEM = "sys";

    /**
     * Notification category: indication of running background service.
     */
    public static final String CATEGORY_SERVICE = "service";

    /**
     * Notification category: a specific, timely recommendation for a single thing.
     * For example, a news app might want to recommend a news story it believes the user will
     * want to read next.
     */
    public static final String CATEGORY_RECOMMENDATION = "recommendation";

    /**
     * Notification category: ongoing information about device or contextual status.
     */
    public static final String CATEGORY_STATUS = "status";

    /**
     * Notification category: user-scheduled reminder.
     */
    public static final String CATEGORY_REMINDER = "reminder";

    /**
     * Notification category: extreme car emergencies.
     * @hide
     */
    @SystemApi
    public static final String CATEGORY_CAR_EMERGENCY = "car_emergency";

    /**
     * Notification category: car warnings.
     * @hide
     */
    @SystemApi
    public static final String CATEGORY_CAR_WARNING = "car_warning";

    /**
     * Notification category: general car system information.
     * @hide
     */
    @SystemApi
    public static final String CATEGORY_CAR_INFORMATION = "car_information";

    /**
     * One of the predefined notification categories (see the <code>CATEGORY_*</code> constants)
     * that best describes this Notification.  May be used by the system for ranking and filtering.
     */
    public String category;

    @UnsupportedAppUsage
    private String mGroupKey;

    /**
     * Get the key used to group this notification into a cluster or stack
     * with other notifications on devices which support such rendering.
     */
    public String getGroup() {
        return mGroupKey;
    }

    private String mSortKey;

    /**
     * Get a sort key that orders this notification among other notifications from the
     * same package. This can be useful if an external sort was already applied and an app
     * would like to preserve this. Notifications will be sorted lexicographically using this
     * value, although providing different priorities in addition to providing sort key may
     * cause this value to be ignored.
     *
     * <p>This sort key can also be used to order members of a notification group. See
     * {@link Builder#setGroup}.
     *
     * @see String#compareTo(String)
     */
    public String getSortKey() {
        return mSortKey;
    }

    /**
     * Additional semantic data to be carried around with this Notification.
     * <p>
     * The extras keys defined here are intended to capture the original inputs to {@link Builder}
     * APIs, and are intended to be used by
     * {@link android.service.notification.NotificationListenerService} implementations to extract
     * detailed information from notification objects.
     */
    public Bundle extras = new Bundle();

    /**
     * All pending intents in the notification as the system needs to be able to access them but
     * touching the extras bundle in the system process is not safe because the bundle may contain
     * custom parcelable objects.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public ArraySet<PendingIntent> allPendingIntents;

    /**
     * Token identifying the notification that is applying doze/bgcheck whitelisting to the
     * pending intents inside of it, so only those will get the behavior.
     *
     * @hide
     */
    private IBinder mWhitelistToken;

    /**
     * Must be set by a process to start associating tokens with Notification objects
     * coming in to it.  This is set by NotificationManagerService.
     *
     * @hide
     */
    static public IBinder processWhitelistToken;

    /**
     * {@link #extras} key: this is the title of the notification,
     * as supplied to {@link Builder#setContentTitle(CharSequence)}.
     */
    public static final String EXTRA_TITLE = "android.title";

    /**
     * {@link #extras} key: this is the title of the notification when shown in expanded form,
     * e.g. as supplied to {@link BigTextStyle#setBigContentTitle(CharSequence)}.
     */
    public static final String EXTRA_TITLE_BIG = EXTRA_TITLE + ".big";

    /**
     * {@link #extras} key: this is the main text payload, as supplied to
     * {@link Builder#setContentText(CharSequence)}.
     */
    public static final String EXTRA_TEXT = "android.text";

    /**
     * {@link #extras} key: this is a third line of text, as supplied to
     * {@link Builder#setSubText(CharSequence)}.
     */
    public static final String EXTRA_SUB_TEXT = "android.subText";

    /**
     * {@link #extras} key: this is the remote input history, as supplied to
     * {@link Builder#setRemoteInputHistory(CharSequence[])}.
     *
     * Apps can fill this through {@link Builder#setRemoteInputHistory(CharSequence[])}
     * with the most recent inputs that have been sent through a {@link RemoteInput} of this
     * Notification and are expected to clear it once the it is no longer relevant (e.g. for chat
     * notifications once the other party has responded).
     *
     * The extra with this key is of type CharSequence[] and contains the most recent entry at
     * the 0 index, the second most recent at the 1 index, etc.
     *
     * @see Builder#setRemoteInputHistory(CharSequence[])
     */
    public static final String EXTRA_REMOTE_INPUT_HISTORY = "android.remoteInputHistory";

    /**
     * {@link #extras} key: boolean as supplied to
     * {@link Builder#setShowRemoteInputSpinner(boolean)}.
     *
     * If set to true, then the view displaying the remote input history from
     * {@link Builder#setRemoteInputHistory(CharSequence[])} will have a progress spinner.
     *
     * @see Builder#setShowRemoteInputSpinner(boolean)
     * @hide
     */
    public static final String EXTRA_SHOW_REMOTE_INPUT_SPINNER = "android.remoteInputSpinner";

    /**
     * {@link #extras} key: boolean as supplied to
     * {@link Builder#setHideSmartReplies(boolean)}.
     *
     * If set to true, then any smart reply buttons will be hidden.
     *
     * @see Builder#setHideSmartReplies(boolean)
     * @hide
     */
    public static final String EXTRA_HIDE_SMART_REPLIES = "android.hideSmartReplies";

    /**
     * {@link #extras} key: this is a small piece of additional text as supplied to
     * {@link Builder#setContentInfo(CharSequence)}.
     */
    public static final String EXTRA_INFO_TEXT = "android.infoText";

    /**
     * {@link #extras} key: this is a line of summary information intended to be shown
     * alongside expanded notifications, as supplied to (e.g.)
     * {@link BigTextStyle#setSummaryText(CharSequence)}.
     */
    public static final String EXTRA_SUMMARY_TEXT = "android.summaryText";

    /**
     * {@link #extras} key: this is the longer text shown in the big form of a
     * {@link BigTextStyle} notification, as supplied to
     * {@link BigTextStyle#bigText(CharSequence)}.
     */
    public static final String EXTRA_BIG_TEXT = "android.bigText";

    /**
     * {@link #extras} key: this is the resource ID of the notification's main small icon, as
     * supplied to {@link Builder#setSmallIcon(int)}.
     *
     * @deprecated Use {@link #getSmallIcon()}, which supports a wider variety of icon sources.
     */
    @Deprecated
    public static final String EXTRA_SMALL_ICON = "android.icon";

    /**
     * {@link #extras} key: this is a bitmap to be used instead of the small icon when showing the
     * notification payload, as
     * supplied to {@link Builder#setLargeIcon(android.graphics.Bitmap)}.
     *
     * @deprecated Use {@link #getLargeIcon()}, which supports a wider variety of icon sources.
     */
    @Deprecated
    public static final String EXTRA_LARGE_ICON = "android.largeIcon";

    /**
     * {@link #extras} key: this is a bitmap to be used instead of the one from
     * {@link Builder#setLargeIcon(android.graphics.Bitmap)} when the notification is
     * shown in its expanded form, as supplied to
     * {@link BigPictureStyle#bigLargeIcon(android.graphics.Bitmap)}.
     */
    public static final String EXTRA_LARGE_ICON_BIG = EXTRA_LARGE_ICON + ".big";

    /**
     * {@link #extras} key: this is the progress value supplied to
     * {@link Builder#setProgress(int, int, boolean)}.
     */
    public static final String EXTRA_PROGRESS = "android.progress";

    /**
     * {@link #extras} key: this is the maximum value supplied to
     * {@link Builder#setProgress(int, int, boolean)}.
     */
    public static final String EXTRA_PROGRESS_MAX = "android.progressMax";

    /**
     * {@link #extras} key: whether the progress bar is indeterminate, supplied to
     * {@link Builder#setProgress(int, int, boolean)}.
     */
    public static final String EXTRA_PROGRESS_INDETERMINATE = "android.progressIndeterminate";

    /**
     * {@link #extras} key: whether {@link #when} should be shown as a count-up timer (specifically
     * a {@link android.widget.Chronometer}) instead of a timestamp, as supplied to
     * {@link Builder#setUsesChronometer(boolean)}.
     */
    public static final String EXTRA_SHOW_CHRONOMETER = "android.showChronometer";

    /**
     * {@link #extras} key: whether the chronometer set on the notification should count down
     * instead of counting up. Is only relevant if key {@link #EXTRA_SHOW_CHRONOMETER} is present.
     * This extra is a boolean. The default is false.
     */
    public static final String EXTRA_CHRONOMETER_COUNT_DOWN = "android.chronometerCountDown";

    /**
     * {@link #extras} key: whether {@link #when} should be shown,
     * as supplied to {@link Builder#setShowWhen(boolean)}.
     */
    public static final String EXTRA_SHOW_WHEN = "android.showWhen";

    /**
     * {@link #extras} key: this is a bitmap to be shown in {@link BigPictureStyle} expanded
     * notifications, supplied to {@link BigPictureStyle#bigPicture(android.graphics.Bitmap)}.
     */
    public static final String EXTRA_PICTURE = "android.picture";

    /**
     * {@link #extras} key: An array of CharSequences to show in {@link InboxStyle} expanded
     * notifications, each of which was supplied to {@link InboxStyle#addLine(CharSequence)}.
     */
    public static final String EXTRA_TEXT_LINES = "android.textLines";

    /**
     * {@link #extras} key: A string representing the name of the specific
     * {@link android.app.Notification.Style} used to create this notification.
     */
    public static final String EXTRA_TEMPLATE = "android.template";

    /**
     * {@link #extras} key: A String array containing the people that this notification relates to,
     * each of which was supplied to {@link Builder#addPerson(String)}.
     *
     * @deprecated the actual objects are now in {@link #EXTRA_PEOPLE_LIST}
     */
    public static final String EXTRA_PEOPLE = "android.people";

    /**
     * {@link #extras} key: An arrayList of {@link Person} objects containing the people that
     * this notification relates to.
     */
    public static final String EXTRA_PEOPLE_LIST = "android.people.list";

    /**
     * Allow certain system-generated notifications to appear before the device is provisioned.
     * Only available to notifications coming from the android package.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.NOTIFICATION_DURING_SETUP)
    public static final String EXTRA_ALLOW_DURING_SETUP = "android.allowDuringSetup";

    /**
     * {@link #extras} key:
     * flat {@link String} representation of a {@link android.content.ContentUris content URI}
     * pointing to an image that can be displayed in the background when the notification is
     * selected. Used on television platforms. The URI must point to an image stream suitable for
     * passing into {@link android.graphics.BitmapFactory#decodeStream(java.io.InputStream)
     * BitmapFactory.decodeStream}; all other content types will be ignored.
     */
    public static final String EXTRA_BACKGROUND_IMAGE_URI = "android.backgroundImageUri";

    /**
     * {@link #extras} key: A
     * {@link android.media.session.MediaSession.Token} associated with a
     * {@link android.app.Notification.MediaStyle} notification.
     */
    public static final String EXTRA_MEDIA_SESSION = "android.mediaSession";

    /**
     * {@link #extras} key: the indices of actions to be shown in the compact view,
     * as supplied to (e.g.) {@link MediaStyle#setShowActionsInCompactView(int...)}.
     */
    public static final String EXTRA_COMPACT_ACTIONS = "android.compactActions";

    /**
     * {@link #extras} key: the username to be displayed for all messages sent by the user including
     * direct replies
     * {@link android.app.Notification.MessagingStyle} notification. This extra is a
     * {@link CharSequence}
     *
     * @deprecated use {@link #EXTRA_MESSAGING_PERSON}
     */
    public static final String EXTRA_SELF_DISPLAY_NAME = "android.selfDisplayName";

    /**
     * {@link #extras} key: the person to be displayed for all messages sent by the user including
     * direct replies
     * {@link android.app.Notification.MessagingStyle} notification. This extra is a
     * {@link Person}
     */
    public static final String EXTRA_MESSAGING_PERSON = "android.messagingUser";

    /**
     * {@link #extras} key: a {@link CharSequence} to be displayed as the title to a conversation
     * represented by a {@link android.app.Notification.MessagingStyle}
     */
    public static final String EXTRA_CONVERSATION_TITLE = "android.conversationTitle";

    /**
     * {@link #extras} key: an array of {@link android.app.Notification.MessagingStyle.Message}
     * bundles provided by a
     * {@link android.app.Notification.MessagingStyle} notification. This extra is a parcelable
     * array of bundles.
     */
    public static final String EXTRA_MESSAGES = "android.messages";

    /**
     * {@link #extras} key: an array of
     * {@link android.app.Notification.MessagingStyle#addHistoricMessage historic}
     * {@link android.app.Notification.MessagingStyle.Message} bundles provided by a
     * {@link android.app.Notification.MessagingStyle} notification. This extra is a parcelable
     * array of bundles.
     */
    public static final String EXTRA_HISTORIC_MESSAGES = "android.messages.historic";

    /**
     * {@link #extras} key: whether the {@link android.app.Notification.MessagingStyle} notification
     * represents a group conversation.
     */
    public static final String EXTRA_IS_GROUP_CONVERSATION = "android.isGroupConversation";

    /**
     * {@link #extras} key: whether the notification should be colorized as
     * supplied to {@link Builder#setColorized(boolean)}}.
     */
    public static final String EXTRA_COLORIZED = "android.colorized";

    /**
     * @hide
     */
    public static final String EXTRA_BUILDER_APPLICATION_INFO = "android.appInfo";

    /**
     * @hide
     */
    public static final String EXTRA_CONTAINS_CUSTOM_VIEW = "android.contains.customView";

    /**
     * @hide
     */
    public static final String EXTRA_REDUCED_IMAGES = "android.reduced.images";

    /**
     * {@link #extras} key: the audio contents of this notification.
     *
     * This is for use when rendering the notification on an audio-focused interface;
     * the audio contents are a complete sound sample that contains the contents/body of the
     * notification. This may be used in substitute of a Text-to-Speech reading of the
     * notification. For example if the notification represents a voice message this should point
     * to the audio of that message.
     *
     * The data stored under this key should be a String representation of a Uri that contains the
     * audio contents in one of the following formats: WAV, PCM 16-bit, AMR-WB.
     *
     * This extra is unnecessary if you are using {@code MessagingStyle} since each {@code Message}
     * has a field for holding data URI. That field can be used for audio.
     * See {@code Message#setData}.
     *
     * Example usage:
     * <pre>
     * {@code
     * Notification.Builder myBuilder = (build your Notification as normal);
     * myBuilder.getExtras().putString(EXTRA_AUDIO_CONTENTS_URI, myAudioUri.toString());
     * }
     * </pre>
     */
    public static final String EXTRA_AUDIO_CONTENTS_URI = "android.audioContents";

    /** @hide */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SUBSTITUTE_NOTIFICATION_APP_NAME)
    public static final String EXTRA_SUBSTITUTE_APP_NAME = "android.substName";

    /**
     * This is set on the notifications shown by system_server about apps running foreground
     * services. It indicates that the notification should be shown
     * only if any of the given apps do not already have a properly tagged
     * {@link #FLAG_FOREGROUND_SERVICE} notification currently visible to the user.
     * This is a string array of all package names of the apps.
     * @hide
     */
    public static final String EXTRA_FOREGROUND_APPS = "android.foregroundApps";

    @UnsupportedAppUsage
    private Icon mSmallIcon;
    @UnsupportedAppUsage
    private Icon mLargeIcon;

    @UnsupportedAppUsage
    private String mChannelId;
    private long mTimeout;

    private String mShortcutId;
    private CharSequence mSettingsText;

    private BubbleMetadata mBubbleMetadata;

    /** @hide */
    @IntDef(prefix = { "GROUP_ALERT_" }, value = {
            GROUP_ALERT_ALL, GROUP_ALERT_CHILDREN, GROUP_ALERT_SUMMARY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GroupAlertBehavior {}

    /**
     * Constant for {@link Builder#setGroupAlertBehavior(int)}, meaning that all notifications in a
     * group with sound or vibration ought to make sound or vibrate (respectively), so this
     * notification will not be muted when it is in a group.
     */
    public static final int GROUP_ALERT_ALL = 0;

    /**
     * Constant for {@link Builder#setGroupAlertBehavior(int)}, meaning that all children
     * notification in a group should be silenced (no sound or vibration) even if they are posted
     * to a {@link NotificationChannel} that has sound and/or vibration. Use this constant to
     * mute this notification if this notification is a group child. This must be applied to all
     * children notifications you want to mute.
     *
     * <p> For example, you might want to use this constant if you post a number of children
     * notifications at once (say, after a periodic sync), and only need to notify the user
     * audibly once.
     */
    public static final int GROUP_ALERT_SUMMARY = 1;

    /**
     * Constant for {@link Builder#setGroupAlertBehavior(int)}, meaning that the summary
     * notification in a group should be silenced (no sound or vibration) even if they are
     * posted to a {@link NotificationChannel} that has sound and/or vibration. Use this constant
     * to mute this notification if this notification is a group summary.
     *
     * <p>For example, you might want to use this constant if only the children notifications
     * in your group have content and the summary is only used to visually group notifications
     * rather than to alert the user that new information is available.
     */
    public static final int GROUP_ALERT_CHILDREN = 2;

    private int mGroupAlertBehavior = GROUP_ALERT_ALL;

    /**
     * If this notification is being shown as a badge, always show as a number.
     */
    public static final int BADGE_ICON_NONE = 0;

    /**
     * If this notification is being shown as a badge, use the {@link #getSmallIcon()} to
     * represent this notification.
     */
    public static final int BADGE_ICON_SMALL = 1;

    /**
     * If this notification is being shown as a badge, use the {@link #getLargeIcon()} to
     * represent this notification.
     */
    public static final int BADGE_ICON_LARGE = 2;
    private int mBadgeIcon = BADGE_ICON_NONE;

    /**
     * Determines whether the platform can generate contextual actions for a notification.
     */
    private boolean mAllowSystemGeneratedContextualActions = true;

    /**
     * Structure to encapsulate a named action that can be shown as part of this notification.
     * It must include an icon, a label, and a {@link PendingIntent} to be fired when the action is
     * selected by the user.
     * <p>
     * Apps should use {@link Notification.Builder#addAction(int, CharSequence, PendingIntent)}
     * or {@link Notification.Builder#addAction(Notification.Action)}
     * to attach actions.
     */
    public static class Action implements Parcelable {
        /**
         * {@link #extras} key: Keys to a {@link Parcelable} {@link ArrayList} of
         * {@link RemoteInput}s.
         *
         * This is intended for {@link RemoteInput}s that only accept data, meaning
         * {@link RemoteInput#getAllowFreeFormInput} is false, {@link RemoteInput#getChoices}
         * is null or empty, and {@link RemoteInput#getAllowedDataTypes is non-null and not
         * empty. These {@link RemoteInput}s will be ignored by devices that do not
         * support non-text-based {@link RemoteInput}s. See {@link Builder#build}.
         *
         * You can test if a RemoteInput matches these constraints using
         * {@link RemoteInput#isDataOnly}.
         */
        private static final String EXTRA_DATA_ONLY_INPUTS = "android.extra.DATA_ONLY_INPUTS";

        /**
         * {@link }: No semantic action defined.
         */
        public static final int SEMANTIC_ACTION_NONE = 0;

        /**
         * {@code SemanticAction}: Reply to a conversation, chat, group, or wherever replies
         * may be appropriate.
         */
        public static final int SEMANTIC_ACTION_REPLY = 1;

        /**
         * {@code SemanticAction}: Mark content as read.
         */
        public static final int SEMANTIC_ACTION_MARK_AS_READ = 2;

        /**
         * {@code SemanticAction}: Mark content as unread.
         */
        public static final int SEMANTIC_ACTION_MARK_AS_UNREAD = 3;

        /**
         * {@code SemanticAction}: Delete the content associated with the notification. This
         * could mean deleting an email, message, etc.
         */
        public static final int SEMANTIC_ACTION_DELETE = 4;

        /**
         * {@code SemanticAction}: Archive the content associated with the notification. This
         * could mean archiving an email, message, etc.
         */
        public static final int SEMANTIC_ACTION_ARCHIVE = 5;

        /**
         * {@code SemanticAction}: Mute the content associated with the notification. This could
         * mean silencing a conversation or currently playing media.
         */
        public static final int SEMANTIC_ACTION_MUTE = 6;

        /**
         * {@code SemanticAction}: Unmute the content associated with the notification. This could
         * mean un-silencing a conversation or currently playing media.
         */
        public static final int SEMANTIC_ACTION_UNMUTE = 7;

        /**
         * {@code SemanticAction}: Mark content with a thumbs up.
         */
        public static final int SEMANTIC_ACTION_THUMBS_UP = 8;

        /**
         * {@code SemanticAction}: Mark content with a thumbs down.
         */
        public static final int SEMANTIC_ACTION_THUMBS_DOWN = 9;

        /**
         * {@code SemanticAction}: Call a contact, group, etc.
         */
        public static final int SEMANTIC_ACTION_CALL = 10;

        private final Bundle mExtras;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        private Icon mIcon;
        private final RemoteInput[] mRemoteInputs;
        private boolean mAllowGeneratedReplies = true;
        private final @SemanticAction int mSemanticAction;
        private final boolean mIsContextual;

        /**
         * Small icon representing the action.
         *
         * @deprecated Use {@link Action#getIcon()} instead.
         */
        @Deprecated
        public int icon;

        /**
         * Title of the action.
         */
        public CharSequence title;

        /**
         * Intent to send when the user invokes this action. May be null, in which case the action
         * may be rendered in a disabled presentation by the system UI.
         */
        public PendingIntent actionIntent;

        private Action(Parcel in) {
            if (in.readInt() != 0) {
                mIcon = Icon.CREATOR.createFromParcel(in);
                if (mIcon.getType() == Icon.TYPE_RESOURCE) {
                    icon = mIcon.getResId();
                }
            }
            title = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            if (in.readInt() == 1) {
                actionIntent = PendingIntent.CREATOR.createFromParcel(in);
            }
            mExtras = Bundle.setDefusable(in.readBundle(), true);
            mRemoteInputs = in.createTypedArray(RemoteInput.CREATOR);
            mAllowGeneratedReplies = in.readInt() == 1;
            mSemanticAction = in.readInt();
            mIsContextual = in.readInt() == 1;
        }

        /**
         * @deprecated Use {@link android.app.Notification.Action.Builder}.
         */
        @Deprecated
        public Action(int icon, CharSequence title, PendingIntent intent) {
            this(Icon.createWithResource("", icon), title, intent, new Bundle(), null, true,
                    SEMANTIC_ACTION_NONE, false /* isContextual */);
        }

        /** Keep in sync with {@link Notification.Action.Builder#Builder(Action)}! */
        private Action(Icon icon, CharSequence title, PendingIntent intent, Bundle extras,
                RemoteInput[] remoteInputs, boolean allowGeneratedReplies,
                       @SemanticAction int semanticAction, boolean isContextual) {
            this.mIcon = icon;
            if (icon != null && icon.getType() == Icon.TYPE_RESOURCE) {
                this.icon = icon.getResId();
            }
            this.title = title;
            this.actionIntent = intent;
            this.mExtras = extras != null ? extras : new Bundle();
            this.mRemoteInputs = remoteInputs;
            this.mAllowGeneratedReplies = allowGeneratedReplies;
            this.mSemanticAction = semanticAction;
            this.mIsContextual = isContextual;
        }

        /**
         * Return an icon representing the action.
         */
        public Icon getIcon() {
            if (mIcon == null && icon != 0) {
                // you snuck an icon in here without using the builder; let's try to keep it
                mIcon = Icon.createWithResource("", icon);
            }
            return mIcon;
        }

        /**
         * Get additional metadata carried around with this Action.
         */
        public Bundle getExtras() {
            return mExtras;
        }

        /**
         * Return whether the platform should automatically generate possible replies for this
         * {@link Action}
         */
        public boolean getAllowGeneratedReplies() {
            return mAllowGeneratedReplies;
        }

        /**
         * Get the list of inputs to be collected from the user when this action is sent.
         * May return null if no remote inputs were added. Only returns inputs which accept
         * a text input. For inputs which only accept data use {@link #getDataOnlyRemoteInputs}.
         */
        public RemoteInput[] getRemoteInputs() {
            return mRemoteInputs;
        }

        /**
         * Returns the {@code SemanticAction} associated with this {@link Action}. A
         * {@code SemanticAction} denotes what an {@link Action}'s {@link PendingIntent} will do
         * (eg. reply, mark as read, delete, etc).
         */
        public @SemanticAction int getSemanticAction() {
            return mSemanticAction;
        }

        /**
         * Returns whether this is a contextual Action, i.e. whether the action is dependent on the
         * notification message body. An example of a contextual action could be an action opening a
         * map application with an address shown in the notification.
         */
        public boolean isContextual() {
            return mIsContextual;
        }

        /**
         * Get the list of inputs to be collected from the user that ONLY accept data when this
         * action is sent. These remote inputs are guaranteed to return true on a call to
         * {@link RemoteInput#isDataOnly}.
         *
         * Returns null if there are no data-only remote inputs.
         *
         * This method exists so that legacy RemoteInput collectors that pre-date the addition
         * of non-textual RemoteInputs do not access these remote inputs.
         */
        public RemoteInput[] getDataOnlyRemoteInputs() {
            return (RemoteInput[]) mExtras.getParcelableArray(EXTRA_DATA_ONLY_INPUTS);
        }

        /**
         * Builder class for {@link Action} objects.
         */
        public static final class Builder {
            private final Icon mIcon;
            private final CharSequence mTitle;
            private final PendingIntent mIntent;
            private boolean mAllowGeneratedReplies = true;
            private final Bundle mExtras;
            private ArrayList<RemoteInput> mRemoteInputs;
            private @SemanticAction int mSemanticAction;
            private boolean mIsContextual;

            /**
             * Construct a new builder for {@link Action} object.
             * @param icon icon to show for this action
             * @param title the title of the action
             * @param intent the {@link PendingIntent} to fire when users trigger this action
             */
            @Deprecated
            public Builder(int icon, CharSequence title, PendingIntent intent) {
                this(Icon.createWithResource("", icon), title, intent);
            }

            /**
             * Construct a new builder for {@link Action} object.
             * @param icon icon to show for this action
             * @param title the title of the action
             * @param intent the {@link PendingIntent} to fire when users trigger this action
             */
            public Builder(Icon icon, CharSequence title, PendingIntent intent) {
                this(icon, title, intent, new Bundle(), null, true, SEMANTIC_ACTION_NONE);
            }

            /**
             * Construct a new builder for {@link Action} object using the fields from an
             * {@link Action}.
             * @param action the action to read fields from.
             */
            public Builder(Action action) {
                this(action.getIcon(), action.title, action.actionIntent,
                        new Bundle(action.mExtras), action.getRemoteInputs(),
                        action.getAllowGeneratedReplies(), action.getSemanticAction());
            }

            private Builder(Icon icon, CharSequence title, PendingIntent intent, Bundle extras,
                    RemoteInput[] remoteInputs, boolean allowGeneratedReplies,
                            @SemanticAction int semanticAction) {
                mIcon = icon;
                mTitle = title;
                mIntent = intent;
                mExtras = extras;
                if (remoteInputs != null) {
                    mRemoteInputs = new ArrayList<RemoteInput>(remoteInputs.length);
                    Collections.addAll(mRemoteInputs, remoteInputs);
                }
                mAllowGeneratedReplies = allowGeneratedReplies;
                mSemanticAction = semanticAction;
            }

            /**
             * Merge additional metadata into this builder.
             *
             * <p>Values within the Bundle will replace existing extras values in this Builder.
             *
             * @see Notification.Action#extras
             */
            @NonNull
            public Builder addExtras(Bundle extras) {
                if (extras != null) {
                    mExtras.putAll(extras);
                }
                return this;
            }

            /**
             * Get the metadata Bundle used by this Builder.
             *
             * <p>The returned Bundle is shared with this Builder.
             */
            public Bundle getExtras() {
                return mExtras;
            }

            /**
             * Add an input to be collected from the user when this action is sent.
             * Response values can be retrieved from the fired intent by using the
             * {@link RemoteInput#getResultsFromIntent} function.
             * @param remoteInput a {@link RemoteInput} to add to the action
             * @return this object for method chaining
             */
            @NonNull
            public Builder addRemoteInput(RemoteInput remoteInput) {
                if (mRemoteInputs == null) {
                    mRemoteInputs = new ArrayList<RemoteInput>();
                }
                mRemoteInputs.add(remoteInput);
                return this;
            }

            /**
             * Set whether the platform should automatically generate possible replies to add to
             * {@link RemoteInput#getChoices()}. If the {@link Action} doesn't have a
             * {@link RemoteInput}, this has no effect.
             * @param allowGeneratedReplies {@code true} to allow generated replies, {@code false}
             * otherwise
             * @return this object for method chaining
             * The default value is {@code true}
             */
            @NonNull
            public Builder setAllowGeneratedReplies(boolean allowGeneratedReplies) {
                mAllowGeneratedReplies = allowGeneratedReplies;
                return this;
            }

            /**
             * Sets the {@code SemanticAction} for this {@link Action}. A
             * {@code SemanticAction} denotes what an {@link Action}'s
             * {@link PendingIntent} will do (eg. reply, mark as read, delete, etc).
             * @param semanticAction a SemanticAction defined within {@link Action} with
             * {@code SEMANTIC_ACTION_} prefixes
             * @return this object for method chaining
             */
            @NonNull
            public Builder setSemanticAction(@SemanticAction int semanticAction) {
                mSemanticAction = semanticAction;
                return this;
            }

            /**
             * Sets whether this {@link Action} is a contextual action, i.e. whether the action is
             * dependent on the notification message body. An example of a contextual action could
             * be an action opening a map application with an address shown in the notification.
             */
            @NonNull
            public Builder setContextual(boolean isContextual) {
                mIsContextual = isContextual;
                return this;
            }

            /**
             * Apply an extender to this action builder. Extenders may be used to add
             * metadata or change options on this builder.
             */
            @NonNull
            public Builder extend(Extender extender) {
                extender.extend(this);
                return this;
            }

            /**
             * Throws an NPE if we are building a contextual action missing one of the fields
             * necessary to display the action.
             */
            private void checkContextualActionNullFields() {
                if (!mIsContextual) return;

                if (mIcon == null) {
                    throw new NullPointerException("Contextual Actions must contain a valid icon");
                }

                if (mIntent == null) {
                    throw new NullPointerException(
                            "Contextual Actions must contain a valid PendingIntent");
                }
            }

            /**
             * Combine all of the options that have been set and return a new {@link Action}
             * object.
             * @return the built action
             */
            @NonNull
            public Action build() {
                checkContextualActionNullFields();

                ArrayList<RemoteInput> dataOnlyInputs = new ArrayList<>();
                RemoteInput[] previousDataInputs =
                    (RemoteInput[]) mExtras.getParcelableArray(EXTRA_DATA_ONLY_INPUTS);
                if (previousDataInputs != null) {
                    for (RemoteInput input : previousDataInputs) {
                        dataOnlyInputs.add(input);
                    }
                }
                List<RemoteInput> textInputs = new ArrayList<>();
                if (mRemoteInputs != null) {
                    for (RemoteInput input : mRemoteInputs) {
                        if (input.isDataOnly()) {
                            dataOnlyInputs.add(input);
                        } else {
                            textInputs.add(input);
                        }
                    }
                }
                if (!dataOnlyInputs.isEmpty()) {
                    RemoteInput[] dataInputsArr =
                            dataOnlyInputs.toArray(new RemoteInput[dataOnlyInputs.size()]);
                    mExtras.putParcelableArray(EXTRA_DATA_ONLY_INPUTS, dataInputsArr);
                }
                RemoteInput[] textInputsArr = textInputs.isEmpty()
                        ? null : textInputs.toArray(new RemoteInput[textInputs.size()]);
                return new Action(mIcon, mTitle, mIntent, mExtras, textInputsArr,
                        mAllowGeneratedReplies, mSemanticAction, mIsContextual);
            }
        }

        @Override
        public Action clone() {
            return new Action(
                    getIcon(),
                    title,
                    actionIntent, // safe to alias
                    mExtras == null ? new Bundle() : new Bundle(mExtras),
                    getRemoteInputs(),
                    getAllowGeneratedReplies(),
                    getSemanticAction(),
                    isContextual());
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            final Icon ic = getIcon();
            if (ic != null) {
                out.writeInt(1);
                ic.writeToParcel(out, 0);
            } else {
                out.writeInt(0);
            }
            TextUtils.writeToParcel(title, out, flags);
            if (actionIntent != null) {
                out.writeInt(1);
                actionIntent.writeToParcel(out, flags);
            } else {
                out.writeInt(0);
            }
            out.writeBundle(mExtras);
            out.writeTypedArray(mRemoteInputs, flags);
            out.writeInt(mAllowGeneratedReplies ? 1 : 0);
            out.writeInt(mSemanticAction);
            out.writeInt(mIsContextual ? 1 : 0);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Action> CREATOR =
                new Parcelable.Creator<Action>() {
            public Action createFromParcel(Parcel in) {
                return new Action(in);
            }
            public Action[] newArray(int size) {
                return new Action[size];
            }
        };

        /**
         * Extender interface for use with {@link Builder#extend}. Extenders may be used to add
         * metadata or change options on an action builder.
         */
        public interface Extender {
            /**
             * Apply this extender to a notification action builder.
             * @param builder the builder to be modified.
             * @return the build object for chaining.
             */
            public Builder extend(Builder builder);
        }

        /**
         * Wearable extender for notification actions. To add extensions to an action,
         * create a new {@link android.app.Notification.Action.WearableExtender} object using
         * the {@code WearableExtender()} constructor and apply it to a
         * {@link android.app.Notification.Action.Builder} using
         * {@link android.app.Notification.Action.Builder#extend}.
         *
         * <pre class="prettyprint">
         * Notification.Action action = new Notification.Action.Builder(
         *         R.drawable.archive_all, "Archive all", actionIntent)
         *         .extend(new Notification.Action.WearableExtender()
         *                 .setAvailableOffline(false))
         *         .build();</pre>
         */
        public static final class WearableExtender implements Extender {
            /** Notification action extra which contains wearable extensions */
            private static final String EXTRA_WEARABLE_EXTENSIONS = "android.wearable.EXTENSIONS";

            // Keys within EXTRA_WEARABLE_EXTENSIONS for wearable options.
            private static final String KEY_FLAGS = "flags";
            private static final String KEY_IN_PROGRESS_LABEL = "inProgressLabel";
            private static final String KEY_CONFIRM_LABEL = "confirmLabel";
            private static final String KEY_CANCEL_LABEL = "cancelLabel";

            // Flags bitwise-ored to mFlags
            private static final int FLAG_AVAILABLE_OFFLINE = 0x1;
            private static final int FLAG_HINT_LAUNCHES_ACTIVITY = 1 << 1;
            private static final int FLAG_HINT_DISPLAY_INLINE = 1 << 2;

            // Default value for flags integer
            private static final int DEFAULT_FLAGS = FLAG_AVAILABLE_OFFLINE;

            private int mFlags = DEFAULT_FLAGS;

            private CharSequence mInProgressLabel;
            private CharSequence mConfirmLabel;
            private CharSequence mCancelLabel;

            /**
             * Create a {@link android.app.Notification.Action.WearableExtender} with default
             * options.
             */
            public WearableExtender() {
            }

            /**
             * Create a {@link android.app.Notification.Action.WearableExtender} by reading
             * wearable options present in an existing notification action.
             * @param action the notification action to inspect.
             */
            public WearableExtender(Action action) {
                Bundle wearableBundle = action.getExtras().getBundle(EXTRA_WEARABLE_EXTENSIONS);
                if (wearableBundle != null) {
                    mFlags = wearableBundle.getInt(KEY_FLAGS, DEFAULT_FLAGS);
                    mInProgressLabel = wearableBundle.getCharSequence(KEY_IN_PROGRESS_LABEL);
                    mConfirmLabel = wearableBundle.getCharSequence(KEY_CONFIRM_LABEL);
                    mCancelLabel = wearableBundle.getCharSequence(KEY_CANCEL_LABEL);
                }
            }

            /**
             * Apply wearable extensions to a notification action that is being built. This is
             * typically called by the {@link android.app.Notification.Action.Builder#extend}
             * method of {@link android.app.Notification.Action.Builder}.
             */
            @Override
            public Action.Builder extend(Action.Builder builder) {
                Bundle wearableBundle = new Bundle();

                if (mFlags != DEFAULT_FLAGS) {
                    wearableBundle.putInt(KEY_FLAGS, mFlags);
                }
                if (mInProgressLabel != null) {
                    wearableBundle.putCharSequence(KEY_IN_PROGRESS_LABEL, mInProgressLabel);
                }
                if (mConfirmLabel != null) {
                    wearableBundle.putCharSequence(KEY_CONFIRM_LABEL, mConfirmLabel);
                }
                if (mCancelLabel != null) {
                    wearableBundle.putCharSequence(KEY_CANCEL_LABEL, mCancelLabel);
                }

                builder.getExtras().putBundle(EXTRA_WEARABLE_EXTENSIONS, wearableBundle);
                return builder;
            }

            @Override
            public WearableExtender clone() {
                WearableExtender that = new WearableExtender();
                that.mFlags = this.mFlags;
                that.mInProgressLabel = this.mInProgressLabel;
                that.mConfirmLabel = this.mConfirmLabel;
                that.mCancelLabel = this.mCancelLabel;
                return that;
            }

            /**
             * Set whether this action is available when the wearable device is not connected to
             * a companion device. The user can still trigger this action when the wearable device is
             * offline, but a visual hint will indicate that the action may not be available.
             * Defaults to true.
             */
            public WearableExtender setAvailableOffline(boolean availableOffline) {
                setFlag(FLAG_AVAILABLE_OFFLINE, availableOffline);
                return this;
            }

            /**
             * Get whether this action is available when the wearable device is not connected to
             * a companion device. The user can still trigger this action when the wearable device is
             * offline, but a visual hint will indicate that the action may not be available.
             * Defaults to true.
             */
            public boolean isAvailableOffline() {
                return (mFlags & FLAG_AVAILABLE_OFFLINE) != 0;
            }

            private void setFlag(int mask, boolean value) {
                if (value) {
                    mFlags |= mask;
                } else {
                    mFlags &= ~mask;
                }
            }

            /**
             * Set a label to display while the wearable is preparing to automatically execute the
             * action. This is usually a 'ing' verb ending in ellipsis like "Sending..."
             *
             * @param label the label to display while the action is being prepared to execute
             * @return this object for method chaining
             */
            @Deprecated
            public WearableExtender setInProgressLabel(CharSequence label) {
                mInProgressLabel = label;
                return this;
            }

            /**
             * Get the label to display while the wearable is preparing to automatically execute
             * the action. This is usually a 'ing' verb ending in ellipsis like "Sending..."
             *
             * @return the label to display while the action is being prepared to execute
             */
            @Deprecated
            public CharSequence getInProgressLabel() {
                return mInProgressLabel;
            }

            /**
             * Set a label to display to confirm that the action should be executed.
             * This is usually an imperative verb like "Send".
             *
             * @param label the label to confirm the action should be executed
             * @return this object for method chaining
             */
            @Deprecated
            public WearableExtender setConfirmLabel(CharSequence label) {
                mConfirmLabel = label;
                return this;
            }

            /**
             * Get the label to display to confirm that the action should be executed.
             * This is usually an imperative verb like "Send".
             *
             * @return the label to confirm the action should be executed
             */
            @Deprecated
            public CharSequence getConfirmLabel() {
                return mConfirmLabel;
            }

            /**
             * Set a label to display to cancel the action.
             * This is usually an imperative verb, like "Cancel".
             *
             * @param label the label to display to cancel the action
             * @return this object for method chaining
             */
            @Deprecated
            public WearableExtender setCancelLabel(CharSequence label) {
                mCancelLabel = label;
                return this;
            }

            /**
             * Get the label to display to cancel the action.
             * This is usually an imperative verb like "Cancel".
             *
             * @return the label to display to cancel the action
             */
            @Deprecated
            public CharSequence getCancelLabel() {
                return mCancelLabel;
            }

            /**
             * Set a hint that this Action will launch an {@link Activity} directly, telling the
             * platform that it can generate the appropriate transitions.
             * @param hintLaunchesActivity {@code true} if the content intent will launch
             * an activity and transitions should be generated, false otherwise.
             * @return this object for method chaining
             */
            public WearableExtender setHintLaunchesActivity(
                    boolean hintLaunchesActivity) {
                setFlag(FLAG_HINT_LAUNCHES_ACTIVITY, hintLaunchesActivity);
                return this;
            }

            /**
             * Get a hint that this Action will launch an {@link Activity} directly, telling the
             * platform that it can generate the appropriate transitions
             * @return {@code true} if the content intent will launch an activity and transitions
             * should be generated, false otherwise. The default value is {@code false} if this was
             * never set.
             */
            public boolean getHintLaunchesActivity() {
                return (mFlags & FLAG_HINT_LAUNCHES_ACTIVITY) != 0;
            }

            /**
             * Set a hint that this Action should be displayed inline.
             *
             * @param hintDisplayInline {@code true} if action should be displayed inline, false
             *        otherwise
             * @return this object for method chaining
             */
            public WearableExtender setHintDisplayActionInline(
                    boolean hintDisplayInline) {
                setFlag(FLAG_HINT_DISPLAY_INLINE, hintDisplayInline);
                return this;
            }

            /**
             * Get a hint that this Action should be displayed inline.
             *
             * @return {@code true} if the Action should be displayed inline, {@code false}
             *         otherwise. The default value is {@code false} if this was never set.
             */
            public boolean getHintDisplayActionInline() {
                return (mFlags & FLAG_HINT_DISPLAY_INLINE) != 0;
            }
        }

        /**
         * Provides meaning to an {@link Action} that hints at what the associated
         * {@link PendingIntent} will do. For example, an {@link Action} with a
         * {@link PendingIntent} that replies to a text message notification may have the
         * {@link #SEMANTIC_ACTION_REPLY} {@code SemanticAction} set within it.
         *
         * @hide
         */
        @IntDef(prefix = { "SEMANTIC_ACTION_" }, value = {
                SEMANTIC_ACTION_NONE,
                SEMANTIC_ACTION_REPLY,
                SEMANTIC_ACTION_MARK_AS_READ,
                SEMANTIC_ACTION_MARK_AS_UNREAD,
                SEMANTIC_ACTION_DELETE,
                SEMANTIC_ACTION_ARCHIVE,
                SEMANTIC_ACTION_MUTE,
                SEMANTIC_ACTION_UNMUTE,
                SEMANTIC_ACTION_THUMBS_UP,
                SEMANTIC_ACTION_THUMBS_DOWN,
                SEMANTIC_ACTION_CALL
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface SemanticAction {}
    }

    /**
     * Array of all {@link Action} structures attached to this notification by
     * {@link Builder#addAction(int, CharSequence, PendingIntent)}. Mostly useful for instances of
     * {@link android.service.notification.NotificationListenerService} that provide an alternative
     * interface for invoking actions.
     */
    public Action[] actions;

    /**
     * Replacement version of this notification whose content will be shown
     * in an insecure context such as atop a secure keyguard. See {@link #visibility}
     * and {@link #VISIBILITY_PUBLIC}.
     */
    public Notification publicVersion;

    /**
     * Constructs a Notification object with default values.
     * You might want to consider using {@link Builder} instead.
     */
    public Notification()
    {
        this.when = System.currentTimeMillis();
        this.creationTime = System.currentTimeMillis();
        this.priority = PRIORITY_DEFAULT;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public Notification(Context context, int icon, CharSequence tickerText, long when,
            CharSequence contentTitle, CharSequence contentText, Intent contentIntent)
    {
        new Builder(context)
                .setWhen(when)
                .setSmallIcon(icon)
                .setTicker(tickerText)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setContentIntent(PendingIntent.getActivity(context, 0, contentIntent, 0))
                .buildInto(this);
    }

    /**
     * Constructs a Notification object with the information needed to
     * have a status bar icon without the standard expanded view.
     *
     * @param icon          The resource id of the icon to put in the status bar.
     * @param tickerText    The text that flows by in the status bar when the notification first
     *                      activates.
     * @param when          The time to show in the time field.  In the System.currentTimeMillis
     *                      timebase.
     *
     * @deprecated Use {@link Builder} instead.
     */
    @Deprecated
    public Notification(int icon, CharSequence tickerText, long when)
    {
        this.icon = icon;
        this.tickerText = tickerText;
        this.when = when;
        this.creationTime = System.currentTimeMillis();
    }

    /**
     * Unflatten the notification from a parcel.
     */
    @SuppressWarnings("unchecked")
    public Notification(Parcel parcel) {
        // IMPORTANT: Add unmarshaling code in readFromParcel as the pending
        // intents in extras are always written as the last entry.
        readFromParcelImpl(parcel);
        // Must be read last!
        allPendingIntents = (ArraySet<PendingIntent>) parcel.readArraySet(null);
    }

    private void readFromParcelImpl(Parcel parcel)
    {
        int version = parcel.readInt();

        mWhitelistToken = parcel.readStrongBinder();
        if (mWhitelistToken == null) {
            mWhitelistToken = processWhitelistToken;
        }
        // Propagate this token to all pending intents that are unmarshalled from the parcel.
        parcel.setClassCookie(PendingIntent.class, mWhitelistToken);

        when = parcel.readLong();
        creationTime = parcel.readLong();
        if (parcel.readInt() != 0) {
            mSmallIcon = Icon.CREATOR.createFromParcel(parcel);
            if (mSmallIcon.getType() == Icon.TYPE_RESOURCE) {
                icon = mSmallIcon.getResId();
            }
        }
        number = parcel.readInt();
        if (parcel.readInt() != 0) {
            contentIntent = PendingIntent.CREATOR.createFromParcel(parcel);
        }
        if (parcel.readInt() != 0) {
            deleteIntent = PendingIntent.CREATOR.createFromParcel(parcel);
        }
        if (parcel.readInt() != 0) {
            tickerText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        }
        if (parcel.readInt() != 0) {
            tickerView = RemoteViews.CREATOR.createFromParcel(parcel);
        }
        if (parcel.readInt() != 0) {
            contentView = RemoteViews.CREATOR.createFromParcel(parcel);
        }
        if (parcel.readInt() != 0) {
            mLargeIcon = Icon.CREATOR.createFromParcel(parcel);
        }
        defaults = parcel.readInt();
        flags = parcel.readInt();
        if (parcel.readInt() != 0) {
            sound = Uri.CREATOR.createFromParcel(parcel);
        }

        audioStreamType = parcel.readInt();
        if (parcel.readInt() != 0) {
            audioAttributes = AudioAttributes.CREATOR.createFromParcel(parcel);
        }
        vibrate = parcel.createLongArray();
        ledARGB = parcel.readInt();
        ledOnMS = parcel.readInt();
        ledOffMS = parcel.readInt();
        iconLevel = parcel.readInt();

        if (parcel.readInt() != 0) {
            fullScreenIntent = PendingIntent.CREATOR.createFromParcel(parcel);
        }

        priority = parcel.readInt();

        category = parcel.readString();

        mGroupKey = parcel.readString();

        mSortKey = parcel.readString();

        extras = Bundle.setDefusable(parcel.readBundle(), true); // may be null
        fixDuplicateExtras();

        actions = parcel.createTypedArray(Action.CREATOR); // may be null

        if (parcel.readInt() != 0) {
            bigContentView = RemoteViews.CREATOR.createFromParcel(parcel);
        }

        if (parcel.readInt() != 0) {
            headsUpContentView = RemoteViews.CREATOR.createFromParcel(parcel);
        }

        visibility = parcel.readInt();

        if (parcel.readInt() != 0) {
            publicVersion = Notification.CREATOR.createFromParcel(parcel);
        }

        color = parcel.readInt();

        if (parcel.readInt() != 0) {
            mChannelId = parcel.readString();
        }
        mTimeout = parcel.readLong();

        if (parcel.readInt() != 0) {
            mShortcutId = parcel.readString();
        }

        mBadgeIcon = parcel.readInt();

        if (parcel.readInt() != 0) {
            mSettingsText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
        }

        mGroupAlertBehavior = parcel.readInt();
        if (parcel.readInt() != 0) {
            mBubbleMetadata = BubbleMetadata.CREATOR.createFromParcel(parcel);
        }

        mAllowSystemGeneratedContextualActions = parcel.readBoolean();
    }

    @Override
    public Notification clone() {
        Notification that = new Notification();
        cloneInto(that, true);
        return that;
    }

    /**
     * Copy all (or if heavy is false, all except Bitmaps and RemoteViews) members
     * of this into that.
     * @hide
     */
    public void cloneInto(Notification that, boolean heavy) {
        that.mWhitelistToken = this.mWhitelistToken;
        that.when = this.when;
        that.creationTime = this.creationTime;
        that.mSmallIcon = this.mSmallIcon;
        that.number = this.number;

        // PendingIntents are global, so there's no reason (or way) to clone them.
        that.contentIntent = this.contentIntent;
        that.deleteIntent = this.deleteIntent;
        that.fullScreenIntent = this.fullScreenIntent;

        if (this.tickerText != null) {
            that.tickerText = this.tickerText.toString();
        }
        if (heavy && this.tickerView != null) {
            that.tickerView = this.tickerView.clone();
        }
        if (heavy && this.contentView != null) {
            that.contentView = this.contentView.clone();
        }
        if (heavy && this.mLargeIcon != null) {
            that.mLargeIcon = this.mLargeIcon;
        }
        that.iconLevel = this.iconLevel;
        that.sound = this.sound; // android.net.Uri is immutable
        that.audioStreamType = this.audioStreamType;
        if (this.audioAttributes != null) {
            that.audioAttributes = new AudioAttributes.Builder(this.audioAttributes).build();
        }

        final long[] vibrate = this.vibrate;
        if (vibrate != null) {
            final int N = vibrate.length;
            final long[] vib = that.vibrate = new long[N];
            System.arraycopy(vibrate, 0, vib, 0, N);
        }

        that.ledARGB = this.ledARGB;
        that.ledOnMS = this.ledOnMS;
        that.ledOffMS = this.ledOffMS;
        that.defaults = this.defaults;

        that.flags = this.flags;

        that.priority = this.priority;

        that.category = this.category;

        that.mGroupKey = this.mGroupKey;

        that.mSortKey = this.mSortKey;

        if (this.extras != null) {
            try {
                that.extras = new Bundle(this.extras);
                // will unparcel
                that.extras.size();
            } catch (BadParcelableException e) {
                Log.e(TAG, "could not unparcel extras from notification: " + this, e);
                that.extras = null;
            }
        }

        if (!ArrayUtils.isEmpty(allPendingIntents)) {
            that.allPendingIntents = new ArraySet<>(allPendingIntents);
        }

        if (this.actions != null) {
            that.actions = new Action[this.actions.length];
            for(int i=0; i<this.actions.length; i++) {
                if ( this.actions[i] != null) {
                    that.actions[i] = this.actions[i].clone();
                }
            }
        }

        if (heavy && this.bigContentView != null) {
            that.bigContentView = this.bigContentView.clone();
        }

        if (heavy && this.headsUpContentView != null) {
            that.headsUpContentView = this.headsUpContentView.clone();
        }

        that.visibility = this.visibility;

        if (this.publicVersion != null) {
            that.publicVersion = new Notification();
            this.publicVersion.cloneInto(that.publicVersion, heavy);
        }

        that.color = this.color;

        that.mChannelId = this.mChannelId;
        that.mTimeout = this.mTimeout;
        that.mShortcutId = this.mShortcutId;
        that.mBadgeIcon = this.mBadgeIcon;
        that.mSettingsText = this.mSettingsText;
        that.mGroupAlertBehavior = this.mGroupAlertBehavior;
        that.mBubbleMetadata = this.mBubbleMetadata;
        that.mAllowSystemGeneratedContextualActions = this.mAllowSystemGeneratedContextualActions;

        if (!heavy) {
            that.lightenPayload(); // will clean out extras
        }
    }

    /**
     * Note all {@link Uri} that are referenced internally, with the expectation
     * that Uri permission grants will need to be issued to ensure the recipient
     * of this object is able to render its contents.
     *
     * @hide
     */
    public void visitUris(@NonNull Consumer<Uri> visitor) {
        visitor.accept(sound);

        if (tickerView != null) tickerView.visitUris(visitor);
        if (contentView != null) contentView.visitUris(visitor);
        if (bigContentView != null) bigContentView.visitUris(visitor);
        if (headsUpContentView != null) headsUpContentView.visitUris(visitor);

        if (extras != null) {
            visitor.accept(extras.getParcelable(EXTRA_AUDIO_CONTENTS_URI));
            if (extras.containsKey(EXTRA_BACKGROUND_IMAGE_URI)) {
                visitor.accept(Uri.parse(extras.getString(EXTRA_BACKGROUND_IMAGE_URI)));
            }
        }

        if (MessagingStyle.class.equals(getNotificationStyle()) && extras != null) {
            final Parcelable[] messages = extras.getParcelableArray(EXTRA_MESSAGES);
            if (!ArrayUtils.isEmpty(messages)) {
                for (MessagingStyle.Message message : MessagingStyle.Message
                        .getMessagesFromBundleArray(messages)) {
                    visitor.accept(message.getDataUri());
                }
            }

            final Parcelable[] historic = extras.getParcelableArray(EXTRA_HISTORIC_MESSAGES);
            if (!ArrayUtils.isEmpty(historic)) {
                for (MessagingStyle.Message message : MessagingStyle.Message
                        .getMessagesFromBundleArray(historic)) {
                    visitor.accept(message.getDataUri());
                }
            }
        }
    }

    /**
     * Removes heavyweight parts of the Notification object for archival or for sending to
     * listeners when the full contents are not necessary.
     * @hide
     */
    public final void lightenPayload() {
        tickerView = null;
        contentView = null;
        bigContentView = null;
        headsUpContentView = null;
        mLargeIcon = null;
        if (extras != null && !extras.isEmpty()) {
            final Set<String> keyset = extras.keySet();
            final int N = keyset.size();
            final String[] keys = keyset.toArray(new String[N]);
            for (int i=0; i<N; i++) {
                final String key = keys[i];
                if (TvExtender.EXTRA_TV_EXTENDER.equals(key)) {
                    continue;
                }
                final Object obj = extras.get(key);
                if (obj != null &&
                    (  obj instanceof Parcelable
                    || obj instanceof Parcelable[]
                    || obj instanceof SparseArray
                    || obj instanceof ArrayList)) {
                    extras.remove(key);
                }
            }
        }
    }

    /**
     * Make sure this CharSequence is safe to put into a bundle, which basically
     * means it had better not be some custom Parcelable implementation.
     * @hide
     */
    public static CharSequence safeCharSequence(CharSequence cs) {
        if (cs == null) return cs;
        if (cs.length() > MAX_CHARSEQUENCE_LENGTH) {
            cs = cs.subSequence(0, MAX_CHARSEQUENCE_LENGTH);
        }
        if (cs instanceof Parcelable) {
            Log.e(TAG, "warning: " + cs.getClass().getCanonicalName()
                    + " instance is a custom Parcelable and not allowed in Notification");
            return cs.toString();
        }
        return removeTextSizeSpans(cs);
    }

    private static CharSequence removeTextSizeSpans(CharSequence charSequence) {
        if (charSequence instanceof Spanned) {
            Spanned ss = (Spanned) charSequence;
            Object[] spans = ss.getSpans(0, ss.length(), Object.class);
            SpannableStringBuilder builder = new SpannableStringBuilder(ss.toString());
            for (Object span : spans) {
                Object resultSpan = span;
                if (resultSpan instanceof CharacterStyle) {
                    resultSpan = ((CharacterStyle) span).getUnderlying();
                }
                if (resultSpan instanceof TextAppearanceSpan) {
                    TextAppearanceSpan originalSpan = (TextAppearanceSpan) resultSpan;
                    resultSpan = new TextAppearanceSpan(
                            originalSpan.getFamily(),
                            originalSpan.getTextStyle(),
                            -1,
                            originalSpan.getTextColor(),
                            originalSpan.getLinkTextColor());
                } else if (resultSpan instanceof RelativeSizeSpan
                        || resultSpan instanceof AbsoluteSizeSpan) {
                    continue;
                } else {
                    resultSpan = span;
                }
                builder.setSpan(resultSpan, ss.getSpanStart(span), ss.getSpanEnd(span),
                        ss.getSpanFlags(span));
            }
            return builder;
        }
        return charSequence;
    }

    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this notification into a parcel.
     */
    public void writeToParcel(Parcel parcel, int flags) {
        // We need to mark all pending intents getting into the notification
        // system as being put there to later allow the notification ranker
        // to launch them and by doing so add the app to the battery saver white
        // list for a short period of time. The problem is that the system
        // cannot look into the extras as there may be parcelables there that
        // the platform does not know how to handle. To go around that we have
        // an explicit list of the pending intents in the extras bundle.
        final boolean collectPendingIntents = (allPendingIntents == null);
        if (collectPendingIntents) {
            PendingIntent.setOnMarshaledListener(
                    (PendingIntent intent, Parcel out, int outFlags) -> {
                if (parcel == out) {
                    if (allPendingIntents == null) {
                        allPendingIntents = new ArraySet<>();
                    }
                    allPendingIntents.add(intent);
                }
            });
        }
        try {
            // IMPORTANT: Add marshaling code in writeToParcelImpl as we
            // want to intercept all pending events written to the parcel.
            writeToParcelImpl(parcel, flags);
            // Must be written last!
            parcel.writeArraySet(allPendingIntents);
        } finally {
            if (collectPendingIntents) {
                PendingIntent.setOnMarshaledListener(null);
            }
        }
    }

    private void writeToParcelImpl(Parcel parcel, int flags) {
        parcel.writeInt(1);

        parcel.writeStrongBinder(mWhitelistToken);
        parcel.writeLong(when);
        parcel.writeLong(creationTime);
        if (mSmallIcon == null && icon != 0) {
            // you snuck an icon in here without using the builder; let's try to keep it
            mSmallIcon = Icon.createWithResource("", icon);
        }
        if (mSmallIcon != null) {
            parcel.writeInt(1);
            mSmallIcon.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }
        parcel.writeInt(number);
        if (contentIntent != null) {
            parcel.writeInt(1);
            contentIntent.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }
        if (deleteIntent != null) {
            parcel.writeInt(1);
            deleteIntent.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }
        if (tickerText != null) {
            parcel.writeInt(1);
            TextUtils.writeToParcel(tickerText, parcel, flags);
        } else {
            parcel.writeInt(0);
        }
        if (tickerView != null) {
            parcel.writeInt(1);
            tickerView.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }
        if (contentView != null) {
            parcel.writeInt(1);
            contentView.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }
        if (mLargeIcon == null && largeIcon != null) {
            // you snuck an icon in here without using the builder; let's try to keep it
            mLargeIcon = Icon.createWithBitmap(largeIcon);
        }
        if (mLargeIcon != null) {
            parcel.writeInt(1);
            mLargeIcon.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }

        parcel.writeInt(defaults);
        parcel.writeInt(this.flags);

        if (sound != null) {
            parcel.writeInt(1);
            sound.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }
        parcel.writeInt(audioStreamType);

        if (audioAttributes != null) {
            parcel.writeInt(1);
            audioAttributes.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }

        parcel.writeLongArray(vibrate);
        parcel.writeInt(ledARGB);
        parcel.writeInt(ledOnMS);
        parcel.writeInt(ledOffMS);
        parcel.writeInt(iconLevel);

        if (fullScreenIntent != null) {
            parcel.writeInt(1);
            fullScreenIntent.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }

        parcel.writeInt(priority);

        parcel.writeString(category);

        parcel.writeString(mGroupKey);

        parcel.writeString(mSortKey);

        parcel.writeBundle(extras); // null ok

        parcel.writeTypedArray(actions, 0); // null ok

        if (bigContentView != null) {
            parcel.writeInt(1);
            bigContentView.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }

        if (headsUpContentView != null) {
            parcel.writeInt(1);
            headsUpContentView.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }

        parcel.writeInt(visibility);

        if (publicVersion != null) {
            parcel.writeInt(1);
            publicVersion.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }

        parcel.writeInt(color);

        if (mChannelId != null) {
            parcel.writeInt(1);
            parcel.writeString(mChannelId);
        } else {
            parcel.writeInt(0);
        }
        parcel.writeLong(mTimeout);

        if (mShortcutId != null) {
            parcel.writeInt(1);
            parcel.writeString(mShortcutId);
        } else {
            parcel.writeInt(0);
        }

        parcel.writeInt(mBadgeIcon);

        if (mSettingsText != null) {
            parcel.writeInt(1);
            TextUtils.writeToParcel(mSettingsText, parcel, flags);
        } else {
            parcel.writeInt(0);
        }

        parcel.writeInt(mGroupAlertBehavior);

        if (mBubbleMetadata != null) {
            parcel.writeInt(1);
            mBubbleMetadata.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }

        parcel.writeBoolean(mAllowSystemGeneratedContextualActions);

        // mUsesStandardHeader is not written because it should be recomputed in listeners
    }

    /**
     * Parcelable.Creator that instantiates Notification objects
     */
    public static final @android.annotation.NonNull Parcelable.Creator<Notification> CREATOR
            = new Parcelable.Creator<Notification>()
    {
        public Notification createFromParcel(Parcel parcel)
        {
            return new Notification(parcel);
        }

        public Notification[] newArray(int size)
        {
            return new Notification[size];
        }
    };

    /**
     * @hide
     */
    public static boolean areActionsVisiblyDifferent(Notification first, Notification second) {
        Notification.Action[] firstAs = first.actions;
        Notification.Action[] secondAs = second.actions;
        if (firstAs == null && secondAs != null || firstAs != null && secondAs == null) {
            return true;
        }
        if (firstAs != null && secondAs != null) {
            if (firstAs.length != secondAs.length) {
                return true;
            }
            for (int i = 0; i < firstAs.length; i++) {
                if (!Objects.equals(String.valueOf(firstAs[i].title),
                        String.valueOf(secondAs[i].title))) {
                    return true;
                }
                RemoteInput[] firstRs = firstAs[i].getRemoteInputs();
                RemoteInput[] secondRs = secondAs[i].getRemoteInputs();
                if (firstRs == null) {
                    firstRs = new RemoteInput[0];
                }
                if (secondRs == null) {
                    secondRs = new RemoteInput[0];
                }
                if (firstRs.length != secondRs.length) {
                    return true;
                }
                for (int j = 0; j < firstRs.length; j++) {
                    if (!Objects.equals(String.valueOf(firstRs[j].getLabel()),
                            String.valueOf(secondRs[j].getLabel()))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @hide
     */
    public static boolean areStyledNotificationsVisiblyDifferent(Builder first, Builder second) {
        if (first.getStyle() == null) {
            return second.getStyle() != null;
        }
        if (second.getStyle() == null) {
            return true;
        }
        return first.getStyle().areNotificationsVisiblyDifferent(second.getStyle());
    }

    /**
     * @hide
     */
    public static boolean areRemoteViewsChanged(Builder first, Builder second) {
        if (!Objects.equals(first.usesStandardHeader(), second.usesStandardHeader())) {
            return true;
        }

        if (areRemoteViewsChanged(first.mN.contentView, second.mN.contentView)) {
            return true;
        }
        if (areRemoteViewsChanged(first.mN.bigContentView, second.mN.bigContentView)) {
            return true;
        }
        if (areRemoteViewsChanged(first.mN.headsUpContentView, second.mN.headsUpContentView)) {
            return true;
        }

        return false;
    }

    private static boolean areRemoteViewsChanged(RemoteViews first, RemoteViews second) {
        if (first == null && second == null) {
            return false;
        }
        if (first == null && second != null || first != null && second == null) {
            return true;
        }

        if (!Objects.equals(first.getLayoutId(), second.getLayoutId())) {
            return true;
        }

        if (!Objects.equals(first.getSequenceNumber(), second.getSequenceNumber())) {
            return true;
        }

        return false;
    }

    /**
     * Parcelling creates multiple copies of objects in {@code extras}. Fix them.
     * <p>
     * For backwards compatibility {@code extras} holds some references to "real" member data such
     * as {@link getLargeIcon()} which is mirrored by {@link #EXTRA_LARGE_ICON}. This is mostly
     * fine as long as the object stays in one process.
     * <p>
     * However, once the notification goes into a parcel each reference gets marshalled separately,
     * wasting memory. Especially with large images on Auto and TV, this is worth fixing.
     */
    private void fixDuplicateExtras() {
        if (extras != null) {
            fixDuplicateExtra(mLargeIcon, EXTRA_LARGE_ICON);
        }
    }

    /**
     * If we find an extra that's exactly the same as one of the "real" fields but refers to a
     * separate object, replace it with the field's version to avoid holding duplicate copies.
     */
    private void fixDuplicateExtra(@Nullable Parcelable original, @NonNull String extraName) {
        if (original != null && extras.getParcelable(extraName) != null) {
            extras.putParcelable(extraName, original);
        }
    }

    /**
     * Sets the {@link #contentView} field to be a view with the standard "Latest Event"
     * layout.
     *
     * <p>Uses the {@link #icon} and {@link #when} fields to set the icon and time fields
     * in the view.</p>
     * @param context       The context for your application / activity.
     * @param contentTitle The title that goes in the expanded entry.
     * @param contentText  The text that goes in the expanded entry.
     * @param contentIntent The intent to launch when the user clicks the expanded notification.
     * If this is an activity, it must include the
     * {@link android.content.Intent#FLAG_ACTIVITY_NEW_TASK} flag, which requires
     * that you take care of task management as described in the
     * <a href="{@docRoot}guide/topics/fundamentals/tasks-and-back-stack.html">Tasks and Back
     * Stack</a> document.
     *
     * @deprecated Use {@link Builder} instead.
     * @removed
     */
    @Deprecated
    public void setLatestEventInfo(Context context,
            CharSequence contentTitle, CharSequence contentText, PendingIntent contentIntent) {
        if (context.getApplicationInfo().targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1){
            Log.e(TAG, "setLatestEventInfo() is deprecated and you should feel deprecated.",
                    new Throwable());
        }

        if (context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.N) {
            extras.putBoolean(EXTRA_SHOW_WHEN, true);
        }

        // ensure that any information already set directly is preserved
        final Notification.Builder builder = new Notification.Builder(context, this);

        // now apply the latestEventInfo fields
        if (contentTitle != null) {
            builder.setContentTitle(contentTitle);
        }
        if (contentText != null) {
            builder.setContentText(contentText);
        }
        builder.setContentIntent(contentIntent);

        builder.build(); // callers expect this notification to be ready to use
    }

    /**
     * @hide
     */
    public static void addFieldsFromContext(Context context, Notification notification) {
        addFieldsFromContext(context.getApplicationInfo(), notification);
    }

    /**
     * @hide
     */
    public static void addFieldsFromContext(ApplicationInfo ai, Notification notification) {
        notification.extras.putParcelable(EXTRA_BUILDER_APPLICATION_INFO, ai);
    }

    /**
     * @hide
     */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(NotificationProto.CHANNEL_ID, getChannelId());
        proto.write(NotificationProto.HAS_TICKER_TEXT, this.tickerText != null);
        proto.write(NotificationProto.FLAGS, this.flags);
        proto.write(NotificationProto.COLOR, this.color);
        proto.write(NotificationProto.CATEGORY, this.category);
        proto.write(NotificationProto.GROUP_KEY, this.mGroupKey);
        proto.write(NotificationProto.SORT_KEY, this.mSortKey);
        if (this.actions != null) {
            proto.write(NotificationProto.ACTION_LENGTH, this.actions.length);
        }
        if (this.visibility >= VISIBILITY_SECRET && this.visibility <= VISIBILITY_PUBLIC) {
            proto.write(NotificationProto.VISIBILITY, this.visibility);
        }
        if (publicVersion != null) {
            publicVersion.writeToProto(proto, NotificationProto.PUBLIC_VERSION);
        }
        proto.end(token);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Notification(channel=");
        sb.append(getChannelId());
        sb.append(" pri=");
        sb.append(priority);
        sb.append(" contentView=");
        if (contentView != null) {
            sb.append(contentView.getPackage());
            sb.append("/0x");
            sb.append(Integer.toHexString(contentView.getLayoutId()));
        } else {
            sb.append("null");
        }
        sb.append(" vibrate=");
        if ((this.defaults & DEFAULT_VIBRATE) != 0) {
            sb.append("default");
        } else if (this.vibrate != null) {
            int N = this.vibrate.length-1;
            sb.append("[");
            for (int i=0; i<N; i++) {
                sb.append(this.vibrate[i]);
                sb.append(',');
            }
            if (N != -1) {
                sb.append(this.vibrate[N]);
            }
            sb.append("]");
        } else {
            sb.append("null");
        }
        sb.append(" sound=");
        if ((this.defaults & DEFAULT_SOUND) != 0) {
            sb.append("default");
        } else if (this.sound != null) {
            sb.append(this.sound.toString());
        } else {
            sb.append("null");
        }
        if (this.tickerText != null) {
            sb.append(" tick");
        }
        sb.append(" defaults=0x");
        sb.append(Integer.toHexString(this.defaults));
        sb.append(" flags=0x");
        sb.append(Integer.toHexString(this.flags));
        sb.append(String.format(" color=0x%08x", this.color));
        if (this.category != null) {
            sb.append(" category=");
            sb.append(this.category);
        }
        if (this.mGroupKey != null) {
            sb.append(" groupKey=");
            sb.append(this.mGroupKey);
        }
        if (this.mSortKey != null) {
            sb.append(" sortKey=");
            sb.append(this.mSortKey);
        }
        if (actions != null) {
            sb.append(" actions=");
            sb.append(actions.length);
        }
        sb.append(" vis=");
        sb.append(visibilityToString(this.visibility));
        if (this.publicVersion != null) {
            sb.append(" publicVersion=");
            sb.append(publicVersion.toString());
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * {@hide}
     */
    public static String visibilityToString(int vis) {
        switch (vis) {
            case VISIBILITY_PRIVATE:
                return "PRIVATE";
            case VISIBILITY_PUBLIC:
                return "PUBLIC";
            case VISIBILITY_SECRET:
                return "SECRET";
            default:
                return "UNKNOWN(" + String.valueOf(vis) + ")";
        }
    }

    /**
     * {@hide}
     */
    public static String priorityToString(@Priority int pri) {
        switch (pri) {
            case PRIORITY_MIN:
                return "MIN";
            case PRIORITY_LOW:
                return "LOW";
            case PRIORITY_DEFAULT:
                return "DEFAULT";
            case PRIORITY_HIGH:
                return "HIGH";
            case PRIORITY_MAX:
                return "MAX";
            default:
                return "UNKNOWN(" + String.valueOf(pri) + ")";
        }
    }

    /**
     * @hide
     */
    public boolean hasCompletedProgress() {
        // not a progress notification; can't be complete
        if (!extras.containsKey(EXTRA_PROGRESS)
                || !extras.containsKey(EXTRA_PROGRESS_MAX)) {
            return false;
        }
        // many apps use max 0 for 'indeterminate'; not complete
        if (extras.getInt(EXTRA_PROGRESS_MAX) == 0) {
            return false;
        }
        return extras.getInt(EXTRA_PROGRESS) == extras.getInt(EXTRA_PROGRESS_MAX);
    }

    /** @removed */
    @Deprecated
    public String getChannel() {
        return mChannelId;
    }

    /**
     * Returns the id of the channel this notification posts to.
     */
    public String getChannelId() {
        return mChannelId;
    }

    /** @removed */
    @Deprecated
    public long getTimeout() {
        return mTimeout;
    }

    /**
     * Returns the duration from posting after which this notification should be canceled by the
     * system, if it's not canceled already.
     */
    public long getTimeoutAfter() {
        return mTimeout;
    }

    /**
     * Returns what icon should be shown for this notification if it is being displayed in a
     * Launcher that supports badging. Will be one of {@link #BADGE_ICON_NONE},
     * {@link #BADGE_ICON_SMALL}, or {@link #BADGE_ICON_LARGE}.
     */
    public int getBadgeIconType() {
        return mBadgeIcon;
    }

    /**
     * Returns the {@link ShortcutInfo#getId() id} that this notification supersedes, if any.
     *
     * <p>Used by some Launchers that display notification content to hide shortcuts that duplicate
     * notifications.
     */
    public String getShortcutId() {
        return mShortcutId;
    }


    /**
     * Returns the settings text provided to {@link Builder#setSettingsText(CharSequence)}.
     */
    public CharSequence getSettingsText() {
        return mSettingsText;
    }

    /**
     * Returns which type of notifications in a group are responsible for audibly alerting the
     * user. See {@link #GROUP_ALERT_ALL}, {@link #GROUP_ALERT_CHILDREN},
     * {@link #GROUP_ALERT_SUMMARY}.
     */
    public @GroupAlertBehavior int getGroupAlertBehavior() {
        return mGroupAlertBehavior;
    }

    /**
     * Returns the bubble metadata that will be used to display app content in a floating window
     * over the existing foreground activity.
     */
    public BubbleMetadata getBubbleMetadata() {
        return mBubbleMetadata;
    }

    /**
     * Returns whether the platform is allowed (by the app developer) to generate contextual actions
     * for this notification.
     */
    public boolean getAllowSystemGeneratedContextualActions() {
        return mAllowSystemGeneratedContextualActions;
    }

    /**
     * The small icon representing this notification in the status bar and content view.
     *
     * @return the small icon representing this notification.
     *
     * @see Builder#getSmallIcon()
     * @see Builder#setSmallIcon(Icon)
     */
    public Icon getSmallIcon() {
        return mSmallIcon;
    }

    /**
     * Used when notifying to clean up legacy small icons.
     * @hide
     */
    @UnsupportedAppUsage
    public void setSmallIcon(Icon icon) {
        mSmallIcon = icon;
    }

    /**
     * The large icon shown in this notification's content view.
     * @see Builder#getLargeIcon()
     * @see Builder#setLargeIcon(Icon)
     */
    public Icon getLargeIcon() {
        return mLargeIcon;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isGroupSummary() {
        return mGroupKey != null && (flags & FLAG_GROUP_SUMMARY) != 0;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isGroupChild() {
        return mGroupKey != null && (flags & FLAG_GROUP_SUMMARY) == 0;
    }

    /**
     * @hide
     */
    public boolean suppressAlertingDueToGrouping() {
        if (isGroupSummary()
                && getGroupAlertBehavior() == Notification.GROUP_ALERT_CHILDREN) {
            return true;
        } else if (isGroupChild()
                && getGroupAlertBehavior() == Notification.GROUP_ALERT_SUMMARY) {
            return true;
        }
        return false;
    }


    /**
     * Finds and returns a remote input and its corresponding action.
     *
     * @param requiresFreeform requires the remoteinput to allow freeform or not.
     * @return the result pair, {@code null} if no result is found.
     *
     * @hide
     */
    @Nullable
    public Pair<RemoteInput, Action> findRemoteInputActionPair(boolean requiresFreeform) {
        if (actions == null) {
            return null;
        }
        for (Notification.Action action : actions) {
            if (action.getRemoteInputs() == null) {
                continue;
            }
            RemoteInput resultRemoteInput = null;
            for (RemoteInput remoteInput : action.getRemoteInputs()) {
                if (remoteInput.getAllowFreeFormInput() || !requiresFreeform) {
                    resultRemoteInput = remoteInput;
                }
            }
            if (resultRemoteInput != null) {
                return Pair.create(resultRemoteInput, action);
            }
        }
        return null;
    }

    /**
     * Returns the actions that are contextual out of the actions in this notification.
     *
     * @hide
     */
    public List<Notification.Action> getContextualActions() {
        if (actions == null) return Collections.emptyList();

        List<Notification.Action> contextualActions = new ArrayList<>();
        for (Notification.Action action : actions) {
            if (action.isContextual()) {
                contextualActions.add(action);
            }
        }
        return contextualActions;
    }

    /**
     * Builder class for {@link Notification} objects.
     *
     * Provides a convenient way to set the various fields of a {@link Notification} and generate
     * content views using the platform's notification layout template. If your app supports
     * versions of Android as old as API level 4, you can instead use
     * {@link android.support.v4.app.NotificationCompat.Builder NotificationCompat.Builder},
     * available in the <a href="{@docRoot}tools/extras/support-library.html">Android Support
     * library</a>.
     *
     * <p>Example:
     *
     * <pre class="prettyprint">
     * Notification noti = new Notification.Builder(mContext)
     *         .setContentTitle(&quot;New mail from &quot; + sender.toString())
     *         .setContentText(subject)
     *         .setSmallIcon(R.drawable.new_mail)
     *         .setLargeIcon(aBitmap)
     *         .build();
     * </pre>
     */
    public static class Builder {
        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT =
                "android.rebuild.contentViewActionCount";
        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT
                = "android.rebuild.bigViewActionCount";
        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT
                = "android.rebuild.hudViewActionCount";

        private static final boolean USE_ONLY_TITLE_IN_LOW_PRIORITY_SUMMARY =
                SystemProperties.getBoolean("notifications.only_title", true);

        /**
         * The lightness difference that has to be added to the primary text color to obtain the
         * secondary text color when the background is light.
         */
        private static final int LIGHTNESS_TEXT_DIFFERENCE_LIGHT = 20;

        /**
         * The lightness difference that has to be added to the primary text color to obtain the
         * secondary text color when the background is dark.
         * A bit less then the above value, since it looks better on dark backgrounds.
         */
        private static final int LIGHTNESS_TEXT_DIFFERENCE_DARK = -10;

        private Context mContext;
        private Notification mN;
        private Bundle mUserExtras = new Bundle();
        private Style mStyle;
        @UnsupportedAppUsage
        private ArrayList<Action> mActions = new ArrayList<Action>(MAX_ACTION_BUTTONS);
        private ArrayList<Person> mPersonList = new ArrayList<>();
        private ContrastColorUtil mColorUtil;
        private boolean mIsLegacy;
        private boolean mIsLegacyInitialized;

        /**
         * Caches a contrast-enhanced version of {@link #mCachedContrastColorIsFor}.
         */
        private int mCachedContrastColor = COLOR_INVALID;
        private int mCachedContrastColorIsFor = COLOR_INVALID;
        /**
         * Caches a ambient version of {@link #mCachedAmbientColorIsFor}.
         */
        private int mCachedAmbientColor = COLOR_INVALID;
        private int mCachedAmbientColorIsFor = COLOR_INVALID;
        /**
         * A neutral color color that can be used for icons.
         */
        private int mNeutralColor = COLOR_INVALID;

        /**
         * Caches an instance of StandardTemplateParams. Note that this may have been used before,
         * so make sure to call {@link StandardTemplateParams#reset()} before using it.
         */
        StandardTemplateParams mParams = new StandardTemplateParams();
        private int mTextColorsAreForBackground = COLOR_INVALID;
        private int mPrimaryTextColor = COLOR_INVALID;
        private int mSecondaryTextColor = COLOR_INVALID;
        private int mBackgroundColor = COLOR_INVALID;
        private int mForegroundColor = COLOR_INVALID;
        /**
         * A temporary location where actions are stored. If != null the view originally has action
         * but doesn't have any for this inflation.
         */
        private ArrayList<Action> mOriginalActions;
        private boolean mRebuildStyledRemoteViews;

        private boolean mTintActionButtons;
        private boolean mInNightMode;

        /**
         * Constructs a new Builder with the defaults:
         *
         * @param context
         *            A {@link Context} that will be used by the Builder to construct the
         *            RemoteViews. The Context will not be held past the lifetime of this Builder
         *            object.
         * @param channelId
         *            The constructed Notification will be posted on this
         *            {@link NotificationChannel}. To use a NotificationChannel, it must first be
         *            created using {@link NotificationManager#createNotificationChannel}.
         */
        public Builder(Context context, String channelId) {
            this(context, (Notification) null);
            mN.mChannelId = channelId;
        }

        /**
         * @deprecated use {@link Notification.Builder#Notification.Builder(Context, String)}
         * instead. All posted Notifications must specify a NotificationChannel Id.
         */
        @Deprecated
        public Builder(Context context) {
            this(context, (Notification) null);
        }

        /**
         * @hide
         */
        public Builder(Context context, Notification toAdopt) {
            mContext = context;
            Resources res = mContext.getResources();
            mTintActionButtons = res.getBoolean(R.bool.config_tintNotificationActionButtons);

            if (res.getBoolean(R.bool.config_enableNightMode)) {
                Configuration currentConfig = res.getConfiguration();
                mInNightMode = (currentConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
            }

            if (toAdopt == null) {
                mN = new Notification();
                if (context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.N) {
                    mN.extras.putBoolean(EXTRA_SHOW_WHEN, true);
                }
                mN.priority = PRIORITY_DEFAULT;
                mN.visibility = VISIBILITY_PRIVATE;
            } else {
                mN = toAdopt;
                if (mN.actions != null) {
                    Collections.addAll(mActions, mN.actions);
                }

                if (mN.extras.containsKey(EXTRA_PEOPLE_LIST)) {
                    ArrayList<Person> people = mN.extras.getParcelableArrayList(EXTRA_PEOPLE_LIST);
                    mPersonList.addAll(people);
                }

                if (mN.getSmallIcon() == null && mN.icon != 0) {
                    setSmallIcon(mN.icon);
                }

                if (mN.getLargeIcon() == null && mN.largeIcon != null) {
                    setLargeIcon(mN.largeIcon);
                }

                String templateClass = mN.extras.getString(EXTRA_TEMPLATE);
                if (!TextUtils.isEmpty(templateClass)) {
                    final Class<? extends Style> styleClass
                            = getNotificationStyleClass(templateClass);
                    if (styleClass == null) {
                        Log.d(TAG, "Unknown style class: " + templateClass);
                    } else {
                        try {
                            final Constructor<? extends Style> ctor =
                                    styleClass.getDeclaredConstructor();
                            ctor.setAccessible(true);
                            final Style style = ctor.newInstance();
                            style.restoreFromExtras(mN.extras);

                            if (style != null) {
                                setStyle(style);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "Could not create Style", t);
                        }
                    }
                }

            }
        }

        private ContrastColorUtil getColorUtil() {
            if (mColorUtil == null) {
                mColorUtil = ContrastColorUtil.getInstance(mContext);
            }
            return mColorUtil;
        }

        /**
         * If this notification is duplicative of a Launcher shortcut, sets the
         * {@link ShortcutInfo#getId() id} of the shortcut, in case the Launcher wants to hide
         * the shortcut.
         *
         * This field will be ignored by Launchers that don't support badging, don't show
         * notification content, or don't show {@link android.content.pm.ShortcutManager shortcuts}.
         *
         * @param shortcutId the {@link ShortcutInfo#getId() id} of the shortcut this notification
         *                   supersedes
         */
        @NonNull
        public Builder setShortcutId(String shortcutId) {
            mN.mShortcutId = shortcutId;
            return this;
        }

        /**
         * Sets which icon to display as a badge for this notification.
         *
         * Must be one of {@link #BADGE_ICON_NONE}, {@link #BADGE_ICON_SMALL},
         * {@link #BADGE_ICON_LARGE}.
         *
         * Note: This value might be ignored, for launchers that don't support badge icons.
         */
        @NonNull
        public Builder setBadgeIconType(int icon) {
            mN.mBadgeIcon = icon;
            return this;
        }

        /**
         * Sets the group alert behavior for this notification. Use this method to mute this
         * notification if alerts for this notification's group should be handled by a different
         * notification. This is only applicable for notifications that belong to a
         * {@link #setGroup(String) group}. This must be called on all notifications you want to
         * mute. For example, if you want only the summary of your group to make noise, all
         * children in the group should have the group alert behavior {@link #GROUP_ALERT_SUMMARY}.
         *
         * <p> The default value is {@link #GROUP_ALERT_ALL}.</p>
         */
        @NonNull
        public Builder setGroupAlertBehavior(@GroupAlertBehavior int groupAlertBehavior) {
            mN.mGroupAlertBehavior = groupAlertBehavior;
            return this;
        }

        /**
         * Sets the {@link BubbleMetadata} that will be used to display app content in a floating
         * window over the existing foreground activity.
         *
         * <p>This data will be ignored unless the notification is posted to a channel that
         * allows {@link NotificationChannel#canBubble() bubbles}.</p>
         *
         * <b>Notifications with a valid and allowed bubble metadata will display in collapsed state
         * outside of the notification shade on unlocked devices. When a user interacts with the
         * collapsed state, the bubble intent will be invoked and displayed.</b>
         */
        @NonNull
        public Builder setBubbleMetadata(BubbleMetadata data) {
            mN.mBubbleMetadata = data;
            return this;
        }

        /** @removed */
        @Deprecated
        public Builder setChannel(String channelId) {
            mN.mChannelId = channelId;
            return this;
        }

        /**
         * Specifies the channel the notification should be delivered on.
         */
        @NonNull
        public Builder setChannelId(String channelId) {
            mN.mChannelId = channelId;
            return this;
        }

        /** @removed */
        @Deprecated
        public Builder setTimeout(long durationMs) {
            mN.mTimeout = durationMs;
            return this;
        }

        /**
         * Specifies a duration in milliseconds after which this notification should be canceled,
         * if it is not already canceled.
         */
        @NonNull
        public Builder setTimeoutAfter(long durationMs) {
            mN.mTimeout = durationMs;
            return this;
        }

        /**
         * Add a timestamp pertaining to the notification (usually the time the event occurred).
         *
         * For apps targeting {@link android.os.Build.VERSION_CODES#N} and above, this time is not
         * shown anymore by default and must be opted into by using
         * {@link android.app.Notification.Builder#setShowWhen(boolean)}
         *
         * @see Notification#when
         */
        @NonNull
        public Builder setWhen(long when) {
            mN.when = when;
            return this;
        }

        /**
         * Control whether the timestamp set with {@link #setWhen(long) setWhen} is shown
         * in the content view.
         * For apps targeting {@link android.os.Build.VERSION_CODES#N} and above, this defaults to
         * {@code false}. For earlier apps, the default is {@code true}.
         */
        @NonNull
        public Builder setShowWhen(boolean show) {
            mN.extras.putBoolean(EXTRA_SHOW_WHEN, show);
            return this;
        }

        /**
         * Show the {@link Notification#when} field as a stopwatch.
         *
         * Instead of presenting <code>when</code> as a timestamp, the notification will show an
         * automatically updating display of the minutes and seconds since <code>when</code>.
         *
         * Useful when showing an elapsed time (like an ongoing phone call).
         *
         * The counter can also be set to count down to <code>when</code> when using
         * {@link #setChronometerCountDown(boolean)}.
         *
         * @see android.widget.Chronometer
         * @see Notification#when
         * @see #setChronometerCountDown(boolean)
         */
        @NonNull
        public Builder setUsesChronometer(boolean b) {
            mN.extras.putBoolean(EXTRA_SHOW_CHRONOMETER, b);
            return this;
        }

        /**
         * Sets the Chronometer to count down instead of counting up.
         *
         * <p>This is only relevant if {@link #setUsesChronometer(boolean)} has been set to true.
         * If it isn't set the chronometer will count up.
         *
         * @see #setUsesChronometer(boolean)
         */
        @NonNull
        public Builder setChronometerCountDown(boolean countDown) {
            mN.extras.putBoolean(EXTRA_CHRONOMETER_COUNT_DOWN, countDown);
            return this;
        }

        /**
         * Set the small icon resource, which will be used to represent the notification in the
         * status bar.
         *

         * The platform template for the expanded view will draw this icon in the left, unless a
         * {@link #setLargeIcon(Bitmap) large icon} has also been specified, in which case the small
         * icon will be moved to the right-hand side.
         *

         * @param icon
         *            A resource ID in the application's package of the drawable to use.
         * @see Notification#icon
         */
        @NonNull
        public Builder setSmallIcon(@DrawableRes int icon) {
            return setSmallIcon(icon != 0
                    ? Icon.createWithResource(mContext, icon)
                    : null);
        }

        /**
         * A variant of {@link #setSmallIcon(int) setSmallIcon(int)} that takes an additional
         * level parameter for when the icon is a {@link android.graphics.drawable.LevelListDrawable
         * LevelListDrawable}.
         *
         * @param icon A resource ID in the application's package of the drawable to use.
         * @param level The level to use for the icon.
         *
         * @see Notification#icon
         * @see Notification#iconLevel
         */
        @NonNull
        public Builder setSmallIcon(@DrawableRes int icon, int level) {
            mN.iconLevel = level;
            return setSmallIcon(icon);
        }

        /**
         * Set the small icon, which will be used to represent the notification in the
         * status bar and content view (unless overridden there by a
         * {@link #setLargeIcon(Bitmap) large icon}).
         *
         * @param icon An Icon object to use.
         * @see Notification#icon
         */
        @NonNull
        public Builder setSmallIcon(Icon icon) {
            mN.setSmallIcon(icon);
            if (icon != null && icon.getType() == Icon.TYPE_RESOURCE) {
                mN.icon = icon.getResId();
            }
            return this;
        }

        /**
         * Set the first line of text in the platform notification template.
         */
        @NonNull
        public Builder setContentTitle(CharSequence title) {
            mN.extras.putCharSequence(EXTRA_TITLE, safeCharSequence(title));
            return this;
        }

        /**
         * Set the second line of text in the platform notification template.
         */
        @NonNull
        public Builder setContentText(CharSequence text) {
            mN.extras.putCharSequence(EXTRA_TEXT, safeCharSequence(text));
            return this;
        }

        /**
         * This provides some additional information that is displayed in the notification. No
         * guarantees are given where exactly it is displayed.
         *
         * <p>This information should only be provided if it provides an essential
         * benefit to the understanding of the notification. The more text you provide the
         * less readable it becomes. For example, an email client should only provide the account
         * name here if more than one email account has been added.</p>
         *
         * <p>As of {@link android.os.Build.VERSION_CODES#N} this information is displayed in the
         * notification header area.
         *
         * On Android versions before {@link android.os.Build.VERSION_CODES#N}
         * this will be shown in the third line of text in the platform notification template.
         * You should not be using {@link #setProgress(int, int, boolean)} at the
         * same time on those versions; they occupy the same place.
         * </p>
         */
        @NonNull
        public Builder setSubText(CharSequence text) {
            mN.extras.putCharSequence(EXTRA_SUB_TEXT, safeCharSequence(text));
            return this;
        }

        /**
         * Provides text that will appear as a link to your application's settings.
         *
         * <p>This text does not appear within notification {@link Style templates} but may
         * appear when the user uses an affordance to learn more about the notification.
         * Additionally, this text will not appear unless you provide a valid link target by
         * handling {@link #INTENT_CATEGORY_NOTIFICATION_PREFERENCES}.
         *
         * <p>This text is meant to be concise description about what the user can customize
         * when they click on this link. The recommended maximum length is 40 characters.
         * @param text
         * @return
         */
        @NonNull
        public Builder setSettingsText(CharSequence text) {
            mN.mSettingsText = safeCharSequence(text);
            return this;
        }

        /**
         * Set the remote input history.
         *
         * This should be set to the most recent inputs that have been sent
         * through a {@link RemoteInput} of this Notification and cleared once the it is no
         * longer relevant (e.g. for chat notifications once the other party has responded).
         *
         * The most recent input must be stored at the 0 index, the second most recent at the
         * 1 index, etc. Note that the system will limit both how far back the inputs will be shown
         * and how much of each individual input is shown.
         *
         * <p>Note: The reply text will only be shown on notifications that have least one action
         * with a {@code RemoteInput}.</p>
         */
        @NonNull
        public Builder setRemoteInputHistory(CharSequence[] text) {
            if (text == null) {
                mN.extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, null);
            } else {
                final int N = Math.min(MAX_REPLY_HISTORY, text.length);
                CharSequence[] safe = new CharSequence[N];
                for (int i = 0; i < N; i++) {
                    safe[i] = safeCharSequence(text[i]);
                }
                mN.extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, safe);
            }
            return this;
        }

        /**
         * Sets whether remote history entries view should have a spinner.
         * @hide
         */
        @NonNull
        public Builder setShowRemoteInputSpinner(boolean showSpinner) {
            mN.extras.putBoolean(EXTRA_SHOW_REMOTE_INPUT_SPINNER, showSpinner);
            return this;
        }

        /**
         * Sets whether smart reply buttons should be hidden.
         * @hide
         */
        @NonNull
        public Builder setHideSmartReplies(boolean hideSmartReplies) {
            mN.extras.putBoolean(EXTRA_HIDE_SMART_REPLIES, hideSmartReplies);
            return this;
        }

        /**
         * Sets the number of items this notification represents. May be displayed as a badge count
         * for Launchers that support badging.
         */
        @NonNull
        public Builder setNumber(int number) {
            mN.number = number;
            return this;
        }

        /**
         * A small piece of additional information pertaining to this notification.
         *
         * The platform template will draw this on the last line of the notification, at the far
         * right (to the right of a smallIcon if it has been placed there).
         *
         * @deprecated use {@link #setSubText(CharSequence)} instead to set a text in the header.
         * For legacy apps targeting a version below {@link android.os.Build.VERSION_CODES#N} this
         * field will still show up, but the subtext will take precedence.
         */
        @Deprecated
        public Builder setContentInfo(CharSequence info) {
            mN.extras.putCharSequence(EXTRA_INFO_TEXT, safeCharSequence(info));
            return this;
        }

        /**
         * Set the progress this notification represents.
         *
         * The platform template will represent this using a {@link ProgressBar}.
         */
        @NonNull
        public Builder setProgress(int max, int progress, boolean indeterminate) {
            mN.extras.putInt(EXTRA_PROGRESS, progress);
            mN.extras.putInt(EXTRA_PROGRESS_MAX, max);
            mN.extras.putBoolean(EXTRA_PROGRESS_INDETERMINATE, indeterminate);
            return this;
        }

        /**
         * Supply a custom RemoteViews to use instead of the platform template.
         *
         * Use {@link #setCustomContentView(RemoteViews)} instead.
         */
        @Deprecated
        public Builder setContent(RemoteViews views) {
            return setCustomContentView(views);
        }

        /**
         * Supply custom RemoteViews to use instead of the platform template.
         *
         * This will override the layout that would otherwise be constructed by this Builder
         * object.
         */
        @NonNull
        public Builder setCustomContentView(RemoteViews contentView) {
            mN.contentView = contentView;
            return this;
        }

        /**
         * Supply custom RemoteViews to use instead of the platform template in the expanded form.
         *
         * This will override the expanded layout that would otherwise be constructed by this
         * Builder object.
         */
        @NonNull
        public Builder setCustomBigContentView(RemoteViews contentView) {
            mN.bigContentView = contentView;
            return this;
        }

        /**
         * Supply custom RemoteViews to use instead of the platform template in the heads up dialog.
         *
         * This will override the heads-up layout that would otherwise be constructed by this
         * Builder object.
         */
        @NonNull
        public Builder setCustomHeadsUpContentView(RemoteViews contentView) {
            mN.headsUpContentView = contentView;
            return this;
        }

        /**
         * Supply a {@link PendingIntent} to be sent when the notification is clicked.
         *
         * As of {@link android.os.Build.VERSION_CODES#HONEYCOMB}, if this field is unset and you
         * have specified a custom RemoteViews with {@link #setContent(RemoteViews)}, you can use
         * {@link RemoteViews#setOnClickPendingIntent RemoteViews.setOnClickPendingIntent(int,PendingIntent)}
         * to assign PendingIntents to individual views in that custom layout (i.e., to create
         * clickable buttons inside the notification view).
         *
         * @see Notification#contentIntent Notification.contentIntent
         */
        @NonNull
        public Builder setContentIntent(PendingIntent intent) {
            mN.contentIntent = intent;
            return this;
        }

        /**
         * Supply a {@link PendingIntent} to send when the notification is cleared explicitly by the user.
         *
         * @see Notification#deleteIntent
         */
        @NonNull
        public Builder setDeleteIntent(PendingIntent intent) {
            mN.deleteIntent = intent;
            return this;
        }

        /**
         * An intent to launch instead of posting the notification to the status bar.
         * Only for use with extremely high-priority notifications demanding the user's
         * <strong>immediate</strong> attention, such as an incoming phone call or
         * alarm clock that the user has explicitly set to a particular time.
         * If this facility is used for something else, please give the user an option
         * to turn it off and use a normal notification, as this can be extremely
         * disruptive.
         *
         * <p>
         * The system UI may choose to display a heads-up notification, instead of
         * launching this intent, while the user is using the device.
         * </p>
         * <p>Apps targeting {@link Build.VERSION_CODES#Q} and above will have to request
         * a permission ({@link android.Manifest.permission#USE_FULL_SCREEN_INTENT}) in order to
         * use full screen intents.</p>
         *
         * @param intent The pending intent to launch.
         * @param highPriority Passing true will cause this notification to be sent
         *          even if other notifications are suppressed.
         *
         * @see Notification#fullScreenIntent
         */
        @NonNull
        public Builder setFullScreenIntent(PendingIntent intent, boolean highPriority) {
            mN.fullScreenIntent = intent;
            setFlag(FLAG_HIGH_PRIORITY, highPriority);
            return this;
        }

        /**
         * Set the "ticker" text which is sent to accessibility services.
         *
         * @see Notification#tickerText
         */
        @NonNull
        public Builder setTicker(CharSequence tickerText) {
            mN.tickerText = safeCharSequence(tickerText);
            return this;
        }

        /**
         * Obsolete version of {@link #setTicker(CharSequence)}.
         *
         */
        @Deprecated
        public Builder setTicker(CharSequence tickerText, RemoteViews views) {
            setTicker(tickerText);
            // views is ignored
            return this;
        }

        /**
         * Add a large icon to the notification content view.
         *
         * In the platform template, this image will be shown on the left of the notification view
         * in place of the {@link #setSmallIcon(Icon) small icon} (which will be placed in a small
         * badge atop the large icon).
         */
        @NonNull
        public Builder setLargeIcon(Bitmap b) {
            return setLargeIcon(b != null ? Icon.createWithBitmap(b) : null);
        }

        /**
         * Add a large icon to the notification content view.
         *
         * In the platform template, this image will be shown on the left of the notification view
         * in place of the {@link #setSmallIcon(Icon) small icon} (which will be placed in a small
         * badge atop the large icon).
         */
        @NonNull
        public Builder setLargeIcon(Icon icon) {
            mN.mLargeIcon = icon;
            mN.extras.putParcelable(EXTRA_LARGE_ICON, icon);
            return this;
        }

        /**
         * Set the sound to play.
         *
         * It will be played using the {@link #AUDIO_ATTRIBUTES_DEFAULT default audio attributes}
         * for notifications.
         *
         * @deprecated use {@link NotificationChannel#setSound(Uri, AudioAttributes)} instead.
         */
        @Deprecated
        public Builder setSound(Uri sound) {
            mN.sound = sound;
            mN.audioAttributes = AUDIO_ATTRIBUTES_DEFAULT;
            return this;
        }

        /**
         * Set the sound to play, along with a specific stream on which to play it.
         *
         * See {@link android.media.AudioManager} for the <code>STREAM_</code> constants.
         *
         * @deprecated use {@link NotificationChannel#setSound(Uri, AudioAttributes)}.
         */
        @Deprecated
        public Builder setSound(Uri sound, int streamType) {
            PlayerBase.deprecateStreamTypeForPlayback(streamType, "Notification", "setSound()");
            mN.sound = sound;
            mN.audioStreamType = streamType;
            return this;
        }

        /**
         * Set the sound to play, along with specific {@link AudioAttributes audio attributes} to
         * use during playback.
         *
         * @deprecated use {@link NotificationChannel#setSound(Uri, AudioAttributes)} instead.
         * @see Notification#sound
         */
        @Deprecated
        public Builder setSound(Uri sound, AudioAttributes audioAttributes) {
            mN.sound = sound;
            mN.audioAttributes = audioAttributes;
            return this;
        }

        /**
         * Set the vibration pattern to use.
         *
         * See {@link android.os.Vibrator#vibrate(long[], int)} for a discussion of the
         * <code>pattern</code> parameter.
         *
         * <p>
         * A notification that vibrates is more likely to be presented as a heads-up notification.
         * </p>
         *
         * @deprecated use {@link NotificationChannel#setVibrationPattern(long[])} instead.
         * @see Notification#vibrate
         */
        @Deprecated
        public Builder setVibrate(long[] pattern) {
            mN.vibrate = pattern;
            return this;
        }

        /**
         * Set the desired color for the indicator LED on the device, as well as the
         * blink duty cycle (specified in milliseconds).
         *

         * Not all devices will honor all (or even any) of these values.
         *
         * @deprecated use {@link NotificationChannel#enableLights(boolean)} instead.
         * @see Notification#ledARGB
         * @see Notification#ledOnMS
         * @see Notification#ledOffMS
         */
        @Deprecated
        public Builder setLights(@ColorInt int argb, int onMs, int offMs) {
            mN.ledARGB = argb;
            mN.ledOnMS = onMs;
            mN.ledOffMS = offMs;
            if (onMs != 0 || offMs != 0) {
                mN.flags |= FLAG_SHOW_LIGHTS;
            }
            return this;
        }

        /**
         * Set whether this is an "ongoing" notification.
         *

         * Ongoing notifications cannot be dismissed by the user, so your application or service
         * must take care of canceling them.
         *

         * They are typically used to indicate a background task that the user is actively engaged
         * with (e.g., playing music) or is pending in some way and therefore occupying the device
         * (e.g., a file download, sync operation, active network connection).
         *

         * @see Notification#FLAG_ONGOING_EVENT
         */
        @NonNull
        public Builder setOngoing(boolean ongoing) {
            setFlag(FLAG_ONGOING_EVENT, ongoing);
            return this;
        }

        /**
         * Set whether this notification should be colorized. When set, the color set with
         * {@link #setColor(int)} will be used as the background color of this notification.
         * <p>
         * This should only be used for high priority ongoing tasks like navigation, an ongoing
         * call, or other similarly high-priority events for the user.
         * <p>
         * For most styles, the coloring will only be applied if the notification is for a
         * foreground service notification.
         * However, for {@link MediaStyle} and {@link DecoratedMediaCustomViewStyle} notifications
         * that have a media session attached there is no such requirement.
         *
         * @see #setColor(int)
         * @see MediaStyle#setMediaSession(MediaSession.Token)
         */
        @NonNull
        public Builder setColorized(boolean colorize) {
            mN.extras.putBoolean(EXTRA_COLORIZED, colorize);
            return this;
        }

        /**
         * Set this flag if you would only like the sound, vibrate
         * and ticker to be played if the notification is not already showing.
         *
         * @see Notification#FLAG_ONLY_ALERT_ONCE
         */
        @NonNull
        public Builder setOnlyAlertOnce(boolean onlyAlertOnce) {
            setFlag(FLAG_ONLY_ALERT_ONCE, onlyAlertOnce);
            return this;
        }

        /**
         * Make this notification automatically dismissed when the user touches it.
         *
         * @see Notification#FLAG_AUTO_CANCEL
         */
        @NonNull
        public Builder setAutoCancel(boolean autoCancel) {
            setFlag(FLAG_AUTO_CANCEL, autoCancel);
            return this;
        }

        /**
         * Set whether or not this notification should not bridge to other devices.
         *
         * <p>Some notifications can be bridged to other devices for remote display.
         * This hint can be set to recommend this notification not be bridged.
         */
        @NonNull
        public Builder setLocalOnly(boolean localOnly) {
            setFlag(FLAG_LOCAL_ONLY, localOnly);
            return this;
        }

        /**
         * Set which notification properties will be inherited from system defaults.
         * <p>
         * The value should be one or more of the following fields combined with
         * bitwise-or:
         * {@link #DEFAULT_SOUND}, {@link #DEFAULT_VIBRATE}, {@link #DEFAULT_LIGHTS}.
         * <p>
         * For all default values, use {@link #DEFAULT_ALL}.
         *
         * @deprecated use {@link NotificationChannel#enableVibration(boolean)} and
         * {@link NotificationChannel#enableLights(boolean)} and
         * {@link NotificationChannel#setSound(Uri, AudioAttributes)} instead.
         */
        @Deprecated
        public Builder setDefaults(int defaults) {
            mN.defaults = defaults;
            return this;
        }

        /**
         * Set the priority of this notification.
         *
         * @see Notification#priority
         * @deprecated use {@link NotificationChannel#setImportance(int)} instead.
         */
        @Deprecated
        public Builder setPriority(@Priority int pri) {
            mN.priority = pri;
            return this;
        }

        /**
         * Set the notification category.
         *
         * @see Notification#category
         */
        @NonNull
        public Builder setCategory(String category) {
            mN.category = category;
            return this;
        }

        /**
         * Add a person that is relevant to this notification.
         *
         * <P>
         * Depending on user preferences, this annotation may allow the notification to pass
         * through interruption filters, if this notification is of category {@link #CATEGORY_CALL}
         * or {@link #CATEGORY_MESSAGE}. The addition of people may also cause this notification to
         * appear more prominently in the user interface.
         * </P>
         *
         * <P>
         * The person should be specified by the {@code String} representation of a
         * {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI}.
         * </P>
         *
         * <P>The system will also attempt to resolve {@code mailto:} and {@code tel:} schema
         * URIs.  The path part of these URIs must exist in the contacts database, in the
         * appropriate column, or the reference will be discarded as invalid. Telephone schema
         * URIs will be resolved by {@link android.provider.ContactsContract.PhoneLookup}.
         * It is also possible to provide a URI with the schema {@code name:} in order to uniquely
         * identify a person without an entry in the contacts database.
         * </P>
         *
         * @param uri A URI for the person.
         * @see Notification#EXTRA_PEOPLE
         * @deprecated use {@link #addPerson(Person)}
         */
        public Builder addPerson(String uri) {
            addPerson(new Person.Builder().setUri(uri).build());
            return this;
        }

        /**
         * Add a person that is relevant to this notification.
         *
         * <P>
         * Depending on user preferences, this annotation may allow the notification to pass
         * through interruption filters, if this notification is of category {@link #CATEGORY_CALL}
         * or {@link #CATEGORY_MESSAGE}. The addition of people may also cause this notification to
         * appear more prominently in the user interface.
         * </P>
         *
         * <P>
         * A person should usually contain a uri in order to benefit from the ranking boost.
         * However, even if no uri is provided, it's beneficial to provide other people in the
         * notification, such that listeners and voice only devices can announce and handle them
         * properly.
         * </P>
         *
         * @param person the person to add.
         * @see Notification#EXTRA_PEOPLE_LIST
         */
        @NonNull
        public Builder addPerson(Person person) {
            mPersonList.add(person);
            return this;
        }

        /**
         * Set this notification to be part of a group of notifications sharing the same key.
         * Grouped notifications may display in a cluster or stack on devices which
         * support such rendering.
         *
         * <p>To make this notification the summary for its group, also call
         * {@link #setGroupSummary}. A sort order can be specified for group members by using
         * {@link #setSortKey}.
         * @param groupKey The group key of the group.
         * @return this object for method chaining
         */
        @NonNull
        public Builder setGroup(String groupKey) {
            mN.mGroupKey = groupKey;
            return this;
        }

        /**
         * Set this notification to be the group summary for a group of notifications.
         * Grouped notifications may display in a cluster or stack on devices which
         * support such rendering. If thereRequires a group key also be set using {@link #setGroup}.
         * The group summary may be suppressed if too few notifications are included in the group.
         * @param isGroupSummary Whether this notification should be a group summary.
         * @return this object for method chaining
         */
        @NonNull
        public Builder setGroupSummary(boolean isGroupSummary) {
            setFlag(FLAG_GROUP_SUMMARY, isGroupSummary);
            return this;
        }

        /**
         * Set a sort key that orders this notification among other notifications from the
         * same package. This can be useful if an external sort was already applied and an app
         * would like to preserve this. Notifications will be sorted lexicographically using this
         * value, although providing different priorities in addition to providing sort key may
         * cause this value to be ignored.
         *
         * <p>This sort key can also be used to order members of a notification group. See
         * {@link #setGroup}.
         *
         * @see String#compareTo(String)
         */
        @NonNull
        public Builder setSortKey(String sortKey) {
            mN.mSortKey = sortKey;
            return this;
        }

        /**
         * Merge additional metadata into this notification.
         *
         * <p>Values within the Bundle will replace existing extras values in this Builder.
         *
         * @see Notification#extras
         */
        @NonNull
        public Builder addExtras(Bundle extras) {
            if (extras != null) {
                mUserExtras.putAll(extras);
            }
            return this;
        }

        /**
         * Set metadata for this notification.
         *
         * <p>A reference to the Bundle is held for the lifetime of this Builder, and the Bundle's
         * current contents are copied into the Notification each time {@link #build()} is
         * called.
         *
         * <p>Replaces any existing extras values with those from the provided Bundle.
         * Use {@link #addExtras} to merge in metadata instead.
         *
         * @see Notification#extras
         */
        @NonNull
        public Builder setExtras(Bundle extras) {
            if (extras != null) {
                mUserExtras = extras;
            }
            return this;
        }

        /**
         * Get the current metadata Bundle used by this notification Builder.
         *
         * <p>The returned Bundle is shared with this Builder.
         *
         * <p>The current contents of this Bundle are copied into the Notification each time
         * {@link #build()} is called.
         *
         * @see Notification#extras
         */
        public Bundle getExtras() {
            return mUserExtras;
        }

        private Bundle getAllExtras() {
            final Bundle saveExtras = (Bundle) mUserExtras.clone();
            saveExtras.putAll(mN.extras);
            return saveExtras;
        }

        /**
         * Add an action to this notification. Actions are typically displayed by
         * the system as a button adjacent to the notification content.
         * <p>
         * Every action must have an icon (32dp square and matching the
         * <a href="{@docRoot}design/style/iconography.html#action-bar">Holo
         * Dark action bar</a> visual style), a textual label, and a {@link PendingIntent}.
         * <p>
         * A notification in its expanded form can display up to 3 actions, from left to right in
         * the order they were added. Actions will not be displayed when the notification is
         * collapsed, however, so be sure that any essential functions may be accessed by the user
         * in some other way (for example, in the Activity pointed to by {@link #contentIntent}).
         *
         * @param icon Resource ID of a drawable that represents the action.
         * @param title Text describing the action.
         * @param intent PendingIntent to be fired when the action is invoked.
         *
         * @deprecated Use {@link #addAction(Action)} instead.
         */
        @Deprecated
        public Builder addAction(int icon, CharSequence title, PendingIntent intent) {
            mActions.add(new Action(icon, safeCharSequence(title), intent));
            return this;
        }

        /**
         * Add an action to this notification. Actions are typically displayed by
         * the system as a button adjacent to the notification content.
         * <p>
         * Every action must have an icon (32dp square and matching the
         * <a href="{@docRoot}design/style/iconography.html#action-bar">Holo
         * Dark action bar</a> visual style), a textual label, and a {@link PendingIntent}.
         * <p>
         * A notification in its expanded form can display up to 3 actions, from left to right in
         * the order they were added. Actions will not be displayed when the notification is
         * collapsed, however, so be sure that any essential functions may be accessed by the user
         * in some other way (for example, in the Activity pointed to by {@link #contentIntent}).
         *
         * @param action The action to add.
         */
        @NonNull
        public Builder addAction(Action action) {
            if (action != null) {
                mActions.add(action);
            }
            return this;
        }

        /**
         * Alter the complete list of actions attached to this notification.
         * @see #addAction(Action).
         *
         * @param actions
         * @return
         */
        @NonNull
        public Builder setActions(Action... actions) {
            mActions.clear();
            for (int i = 0; i < actions.length; i++) {
                if (actions[i] != null) {
                    mActions.add(actions[i]);
                }
            }
            return this;
        }

        /**
         * Add a rich notification style to be applied at build time.
         *
         * @param style Object responsible for modifying the notification style.
         */
        @NonNull
        public Builder setStyle(Style style) {
            if (mStyle != style) {
                mStyle = style;
                if (mStyle != null) {
                    mStyle.setBuilder(this);
                    mN.extras.putString(EXTRA_TEMPLATE, style.getClass().getName());
                }  else {
                    mN.extras.remove(EXTRA_TEMPLATE);
                }
            }
            return this;
        }

        /**
         * Returns the style set by {@link #setStyle(Style)}.
         */
        public Style getStyle() {
            return mStyle;
        }

        /**
         * Specify the value of {@link #visibility}.
         *
         * @return The same Builder.
         */
        @NonNull
        public Builder setVisibility(@Visibility int visibility) {
            mN.visibility = visibility;
            return this;
        }

        /**
         * Supply a replacement Notification whose contents should be shown in insecure contexts
         * (i.e. atop the secure lockscreen). See {@link #visibility} and {@link #VISIBILITY_PUBLIC}.
         * @param n A replacement notification, presumably with some or all info redacted.
         * @return The same Builder.
         */
        @NonNull
        public Builder setPublicVersion(Notification n) {
            if (n != null) {
                mN.publicVersion = new Notification();
                n.cloneInto(mN.publicVersion, /*heavy=*/ true);
            } else {
                mN.publicVersion = null;
            }
            return this;
        }

        /**
         * Apply an extender to this notification builder. Extenders may be used to add
         * metadata or change options on this builder.
         */
        @NonNull
        public Builder extend(Extender extender) {
            extender.extend(this);
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setFlag(int mask, boolean value) {
            if (value) {
                mN.flags |= mask;
            } else {
                mN.flags &= ~mask;
            }
            return this;
        }

        /**
         * Sets {@link Notification#color}.
         *
         * @param argb The accent color to use
         *
         * @return The same Builder.
         */
        @NonNull
        public Builder setColor(@ColorInt int argb) {
            mN.color = argb;
            sanitizeColor();
            return this;
        }

        private Drawable getProfileBadgeDrawable() {
            if (mContext.getUserId() == UserHandle.USER_SYSTEM) {
                // This user can never be a badged profile,
                // and also includes USER_ALL system notifications.
                return null;
            }
            // Note: This assumes that the current user can read the profile badge of the
            // originating user.
            return mContext.getPackageManager().getUserBadgeForDensityNoBackground(
                    new UserHandle(mContext.getUserId()), 0);
        }

        private Bitmap getProfileBadge() {
            Drawable badge = getProfileBadgeDrawable();
            if (badge == null) {
                return null;
            }
            final int size = mContext.getResources().getDimensionPixelSize(
                    R.dimen.notification_badge_size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            badge.setBounds(0, 0, size, size);
            badge.draw(canvas);
            return bitmap;
        }

        private void bindProfileBadge(RemoteViews contentView, StandardTemplateParams p) {
            Bitmap profileBadge = getProfileBadge();

            if (profileBadge != null) {
                contentView.setImageViewBitmap(R.id.profile_badge, profileBadge);
                contentView.setViewVisibility(R.id.profile_badge, View.VISIBLE);
                if (isColorized(p)) {
                    contentView.setDrawableTint(R.id.profile_badge, false,
                            getPrimaryTextColor(p), PorterDuff.Mode.SRC_ATOP);
                }
            }
        }

        private void bindAlertedIcon(RemoteViews contentView, StandardTemplateParams p) {
            contentView.setDrawableTint(
                    R.id.alerted_icon,
                    false /* targetBackground */,
                    getNeutralColor(p),
                    PorterDuff.Mode.SRC_ATOP);
        }

        /**
         * @hide
         */
        public boolean usesStandardHeader() {
            if (mN.mUsesStandardHeader) {
                return true;
            }
            if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.N) {
                if (mN.contentView == null && mN.bigContentView == null) {
                    return true;
                }
            }
            boolean contentViewUsesHeader = mN.contentView == null
                    || STANDARD_LAYOUTS.contains(mN.contentView.getLayoutId());
            boolean bigContentViewUsesHeader = mN.bigContentView == null
                    || STANDARD_LAYOUTS.contains(mN.bigContentView.getLayoutId());
            return contentViewUsesHeader && bigContentViewUsesHeader;
        }

        private void resetStandardTemplate(RemoteViews contentView) {
            resetNotificationHeader(contentView);
            contentView.setViewVisibility(R.id.right_icon, View.GONE);
            contentView.setViewVisibility(R.id.title, View.GONE);
            contentView.setTextViewText(R.id.title, null);
            contentView.setViewVisibility(R.id.text, View.GONE);
            contentView.setTextViewText(R.id.text, null);
            contentView.setViewVisibility(R.id.text_line_1, View.GONE);
            contentView.setTextViewText(R.id.text_line_1, null);
        }

        /**
         * Resets the notification header to its original state
         */
        private void resetNotificationHeader(RemoteViews contentView) {
            // Small icon doesn't need to be reset, as it's always set. Resetting would prevent
            // re-using the drawable when the notification is updated.
            contentView.setBoolean(R.id.notification_header, "setExpanded", false);
            contentView.setTextViewText(R.id.app_name_text, null);
            contentView.setViewVisibility(R.id.chronometer, View.GONE);
            contentView.setViewVisibility(R.id.header_text, View.GONE);
            contentView.setTextViewText(R.id.header_text, null);
            contentView.setViewVisibility(R.id.header_text_secondary, View.GONE);
            contentView.setTextViewText(R.id.header_text_secondary, null);
            contentView.setViewVisibility(R.id.header_text_divider, View.GONE);
            contentView.setViewVisibility(R.id.header_text_secondary_divider, View.GONE);
            contentView.setViewVisibility(R.id.time_divider, View.GONE);
            contentView.setViewVisibility(R.id.time, View.GONE);
            contentView.setImageViewIcon(R.id.profile_badge, null);
            contentView.setViewVisibility(R.id.profile_badge, View.GONE);
            contentView.setViewVisibility(R.id.alerted_icon, View.GONE);
            mN.mUsesStandardHeader = false;
        }

        private RemoteViews applyStandardTemplate(int resId, TemplateBindResult result) {
            return applyStandardTemplate(resId, mParams.reset().fillTextsFrom(this),
                    result);
        }

        private RemoteViews applyStandardTemplate(int resId, StandardTemplateParams p,
                TemplateBindResult result) {
            RemoteViews contentView = new BuilderRemoteViews(mContext.getApplicationInfo(), resId);

            resetStandardTemplate(contentView);

            final Bundle ex = mN.extras;
            updateBackgroundColor(contentView, p);
            bindNotificationHeader(contentView, p);
            bindLargeIconAndReply(contentView, p, result);
            boolean showProgress = handleProgressBar(contentView, ex, p);
            if (p.title != null) {
                contentView.setViewVisibility(R.id.title, View.VISIBLE);
                contentView.setTextViewText(R.id.title, processTextSpans(p.title));
                setTextViewColorPrimary(contentView, R.id.title, p);
                contentView.setViewLayoutWidth(R.id.title, showProgress
                        ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : ViewGroup.LayoutParams.MATCH_PARENT);
            }
            if (p.text != null) {
                int textId = showProgress ? com.android.internal.R.id.text_line_1
                        : com.android.internal.R.id.text;
                contentView.setTextViewText(textId, processTextSpans(p.text));
                setTextViewColorSecondary(contentView, textId, p);
                contentView.setViewVisibility(textId, View.VISIBLE);
            }

            setContentMinHeight(contentView, showProgress || mN.hasLargeIcon());

            return contentView;
        }

        private CharSequence processTextSpans(CharSequence text) {
            if (hasForegroundColor() || mInNightMode) {
                return ContrastColorUtil.clearColorSpans(text);
            }
            return text;
        }

        private void setTextViewColorPrimary(RemoteViews contentView, int id,
                StandardTemplateParams p) {
            ensureColors(p);
            contentView.setTextColor(id, mPrimaryTextColor);
        }

        private boolean hasForegroundColor() {
            return mForegroundColor != COLOR_INVALID;
        }

        /**
         * Return the primary text color using the existing template params
         * @hide
         */
        @VisibleForTesting
        public int getPrimaryTextColor() {
            return getPrimaryTextColor(mParams);
        }

        /**
         * @param p the template params to inflate this with
         * @return the primary text color
         * @hide
         */
        @VisibleForTesting
        public int getPrimaryTextColor(StandardTemplateParams p) {
            ensureColors(p);
            return mPrimaryTextColor;
        }

        /**
         * Return the secondary text color using the existing template params
         * @hide
         */
        @VisibleForTesting
        public int getSecondaryTextColor() {
            return getSecondaryTextColor(mParams);
        }

        /**
         * @param p the template params to inflate this with
         * @return the secondary text color
         * @hide
         */
        @VisibleForTesting
        public int getSecondaryTextColor(StandardTemplateParams p) {
            ensureColors(p);
            return mSecondaryTextColor;
        }

        private void setTextViewColorSecondary(RemoteViews contentView, int id,
                StandardTemplateParams p) {
            ensureColors(p);
            contentView.setTextColor(id, mSecondaryTextColor);
        }

        private void ensureColors(StandardTemplateParams p) {
            int backgroundColor = getBackgroundColor(p);
            if (mPrimaryTextColor == COLOR_INVALID
                    || mSecondaryTextColor == COLOR_INVALID
                    || mTextColorsAreForBackground != backgroundColor) {
                mTextColorsAreForBackground = backgroundColor;
                if (!hasForegroundColor() || !isColorized(p)) {
                    mPrimaryTextColor = ContrastColorUtil.resolvePrimaryColor(mContext,
                            backgroundColor, mInNightMode);
                    mSecondaryTextColor = ContrastColorUtil.resolveSecondaryColor(mContext,
                            backgroundColor, mInNightMode);
                    if (backgroundColor != COLOR_DEFAULT && isColorized(p)) {
                        mPrimaryTextColor = ContrastColorUtil.findAlphaToMeetContrast(
                                mPrimaryTextColor, backgroundColor, 4.5);
                        mSecondaryTextColor = ContrastColorUtil.findAlphaToMeetContrast(
                                mSecondaryTextColor, backgroundColor, 4.5);
                    }
                } else {
                    double backLum = ContrastColorUtil.calculateLuminance(backgroundColor);
                    double textLum = ContrastColorUtil.calculateLuminance(mForegroundColor);
                    double contrast = ContrastColorUtil.calculateContrast(mForegroundColor,
                            backgroundColor);
                    // We only respect the given colors if worst case Black or White still has
                    // contrast
                    boolean backgroundLight = backLum > textLum
                                    && satisfiesTextContrast(backgroundColor, Color.BLACK)
                            || backLum <= textLum
                                    && !satisfiesTextContrast(backgroundColor, Color.WHITE);
                    if (contrast < 4.5f) {
                        if (backgroundLight) {
                            mSecondaryTextColor = ContrastColorUtil.findContrastColor(
                                    mForegroundColor,
                                    backgroundColor,
                                    true /* findFG */,
                                    4.5f);
                            mPrimaryTextColor = ContrastColorUtil.changeColorLightness(
                                    mSecondaryTextColor, -LIGHTNESS_TEXT_DIFFERENCE_LIGHT);
                        } else {
                            mSecondaryTextColor =
                                    ContrastColorUtil.findContrastColorAgainstDark(
                                    mForegroundColor,
                                    backgroundColor,
                                    true /* findFG */,
                                    4.5f);
                            mPrimaryTextColor = ContrastColorUtil.changeColorLightness(
                                    mSecondaryTextColor, -LIGHTNESS_TEXT_DIFFERENCE_DARK);
                        }
                    } else {
                        mPrimaryTextColor = mForegroundColor;
                        mSecondaryTextColor = ContrastColorUtil.changeColorLightness(
                                mPrimaryTextColor, backgroundLight ? LIGHTNESS_TEXT_DIFFERENCE_LIGHT
                                        : LIGHTNESS_TEXT_DIFFERENCE_DARK);
                        if (ContrastColorUtil.calculateContrast(mSecondaryTextColor,
                                backgroundColor) < 4.5f) {
                            // oh well the secondary is not good enough
                            if (backgroundLight) {
                                mSecondaryTextColor = ContrastColorUtil.findContrastColor(
                                        mSecondaryTextColor,
                                        backgroundColor,
                                        true /* findFG */,
                                        4.5f);
                            } else {
                                mSecondaryTextColor
                                        = ContrastColorUtil.findContrastColorAgainstDark(
                                        mSecondaryTextColor,
                                        backgroundColor,
                                        true /* findFG */,
                                        4.5f);
                            }
                            mPrimaryTextColor = ContrastColorUtil.changeColorLightness(
                                    mSecondaryTextColor, backgroundLight
                                            ? -LIGHTNESS_TEXT_DIFFERENCE_LIGHT
                                            : -LIGHTNESS_TEXT_DIFFERENCE_DARK);
                        }
                    }
                }
            }
        }

        private void updateBackgroundColor(RemoteViews contentView,
                StandardTemplateParams p) {
            if (isColorized(p)) {
                contentView.setInt(R.id.status_bar_latest_event_content, "setBackgroundColor",
                        getBackgroundColor(p));
            } else {
                // Clear it!
                contentView.setInt(R.id.status_bar_latest_event_content, "setBackgroundResource",
                        0);
            }
        }

        /**
         * @param remoteView the remote view to update the minheight in
         * @param hasMinHeight does it have a mimHeight
         * @hide
         */
        void setContentMinHeight(RemoteViews remoteView, boolean hasMinHeight) {
            int minHeight = 0;
            if (hasMinHeight) {
                // we need to set the minHeight of the notification
                minHeight = mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.notification_min_content_height);
            }
            remoteView.setInt(R.id.notification_main_column, "setMinimumHeight", minHeight);
        }

        private boolean handleProgressBar(RemoteViews contentView, Bundle ex,
                StandardTemplateParams p) {
            final int max = ex.getInt(EXTRA_PROGRESS_MAX, 0);
            final int progress = ex.getInt(EXTRA_PROGRESS, 0);
            final boolean ind = ex.getBoolean(EXTRA_PROGRESS_INDETERMINATE);
            if (p.hasProgress && (max != 0 || ind)) {
                contentView.setViewVisibility(com.android.internal.R.id.progress, View.VISIBLE);
                contentView.setProgressBar(
                        R.id.progress, max, progress, ind);
                contentView.setProgressBackgroundTintList(
                        R.id.progress, ColorStateList.valueOf(mContext.getColor(
                                R.color.notification_progress_background_color)));
                if (getRawColor(p) != COLOR_DEFAULT) {
                    ColorStateList colorStateList = ColorStateList.valueOf(resolveContrastColor(p));
                    contentView.setProgressTintList(R.id.progress, colorStateList);
                    contentView.setProgressIndeterminateTintList(R.id.progress, colorStateList);
                }
                return true;
            } else {
                contentView.setViewVisibility(R.id.progress, View.GONE);
                return false;
            }
        }

        private void bindLargeIconAndReply(RemoteViews contentView, StandardTemplateParams p,
                TemplateBindResult result) {
            boolean largeIconShown = bindLargeIcon(contentView, p);
            boolean replyIconShown = bindReplyIcon(contentView, p);
            boolean iconContainerVisible = largeIconShown || replyIconShown;
            contentView.setViewVisibility(R.id.right_icon_container,
                    iconContainerVisible ? View.VISIBLE : View.GONE);
            int marginEnd = calculateMarginEnd(largeIconShown, replyIconShown);
            contentView.setViewLayoutMarginEnd(R.id.line1, marginEnd);
            contentView.setViewLayoutMarginEnd(R.id.text, marginEnd);
            contentView.setViewLayoutMarginEnd(R.id.progress, marginEnd);
            if (result != null) {
                result.setIconMarginEnd(marginEnd);
                result.setRightIconContainerVisible(iconContainerVisible);
            }
        }

        private int calculateMarginEnd(boolean largeIconShown, boolean replyIconShown) {
            int marginEnd = 0;
            int contentMargin = mContext.getResources().getDimensionPixelSize(
                    R.dimen.notification_content_margin_end);
            int iconSize = mContext.getResources().getDimensionPixelSize(
                    R.dimen.notification_right_icon_size);
            if (replyIconShown) {
                // The size of the reply icon
                marginEnd += iconSize;

                int replyInset = mContext.getResources().getDimensionPixelSize(
                        R.dimen.notification_reply_inset);
                // We're subtracting the inset of the reply icon to make sure it's
                // aligned nicely on the right, and remove it from the following padding
                marginEnd -= replyInset * 2;
            }
            if (largeIconShown) {
                // adding size of the right icon
                marginEnd += iconSize;

                if (replyIconShown) {
                    // We also add some padding to the reply icon if it's around
                    marginEnd += contentMargin;
                }
            }
            if (replyIconShown || largeIconShown) {
                // The padding to the content
                marginEnd += contentMargin;
            }
            return marginEnd;
        }

        /**
         * Bind the large icon.
         * @return if the largeIcon is visible
         */
        private boolean bindLargeIcon(RemoteViews contentView, StandardTemplateParams p) {
            if (mN.mLargeIcon == null && mN.largeIcon != null) {
                mN.mLargeIcon = Icon.createWithBitmap(mN.largeIcon);
            }
            boolean showLargeIcon = mN.mLargeIcon != null && !p.hideLargeIcon;
            if (showLargeIcon) {
                contentView.setViewVisibility(R.id.right_icon, View.VISIBLE);
                contentView.setImageViewIcon(R.id.right_icon, mN.mLargeIcon);
                processLargeLegacyIcon(mN.mLargeIcon, contentView, p);
            }
            return showLargeIcon;
        }

        /**
         * Bind the reply icon.
         * @return if the reply icon is visible
         */
        private boolean bindReplyIcon(RemoteViews contentView, StandardTemplateParams p) {
            boolean actionVisible = !p.hideReplyIcon;
            Action action = null;
            if (actionVisible) {
                action = findReplyAction();
                actionVisible = action != null;
            }
            if (actionVisible) {
                contentView.setViewVisibility(R.id.reply_icon_action, View.VISIBLE);
                contentView.setDrawableTint(R.id.reply_icon_action,
                        false /* targetBackground */,
                        getNeutralColor(p),
                        PorterDuff.Mode.SRC_ATOP);
                contentView.setOnClickPendingIntent(R.id.reply_icon_action, action.actionIntent);
                contentView.setRemoteInputs(R.id.reply_icon_action, action.mRemoteInputs);
            } else {
                contentView.setRemoteInputs(R.id.reply_icon_action, null);
            }
            contentView.setViewVisibility(R.id.reply_icon_action,
                    actionVisible ? View.VISIBLE : View.GONE);
            return actionVisible;
        }

        private Action findReplyAction() {
            ArrayList<Action> actions = mActions;
            if (mOriginalActions != null) {
                actions = mOriginalActions;
            }
            int numActions = actions.size();
            for (int i = 0; i < numActions; i++) {
                Action action = actions.get(i);
                if (hasValidRemoteInput(action)) {
                    return action;
                }
            }
            return null;
        }

        private void bindNotificationHeader(RemoteViews contentView, StandardTemplateParams p) {
            bindSmallIcon(contentView, p);
            bindHeaderAppName(contentView, p);
            bindHeaderText(contentView, p);
            bindHeaderTextSecondary(contentView, p);
            bindHeaderChronometerAndTime(contentView, p);
            bindProfileBadge(contentView, p);
            bindAlertedIcon(contentView, p);
            bindActivePermissions(contentView, p);
            bindExpandButton(contentView, p);
            mN.mUsesStandardHeader = true;
        }

        private void bindActivePermissions(RemoteViews contentView, StandardTemplateParams p) {
            int color = getNeutralColor(p);
            contentView.setDrawableTint(R.id.camera, false, color, PorterDuff.Mode.SRC_ATOP);
            contentView.setDrawableTint(R.id.mic, false, color, PorterDuff.Mode.SRC_ATOP);
            contentView.setDrawableTint(R.id.overlay, false, color, PorterDuff.Mode.SRC_ATOP);
        }

        private void bindExpandButton(RemoteViews contentView, StandardTemplateParams p) {
            int color = isColorized(p) ? getPrimaryTextColor(p) : getSecondaryTextColor(p);
            contentView.setDrawableTint(R.id.expand_button, false, color,
                    PorterDuff.Mode.SRC_ATOP);
            contentView.setInt(R.id.notification_header, "setOriginalNotificationColor",
                    color);
        }

        private void bindHeaderChronometerAndTime(RemoteViews contentView,
                StandardTemplateParams p) {
            if (showsTimeOrChronometer()) {
                contentView.setViewVisibility(R.id.time_divider, View.VISIBLE);
                setTextViewColorSecondary(contentView, R.id.time_divider, p);
                if (mN.extras.getBoolean(EXTRA_SHOW_CHRONOMETER)) {
                    contentView.setViewVisibility(R.id.chronometer, View.VISIBLE);
                    contentView.setLong(R.id.chronometer, "setBase",
                            mN.when + (SystemClock.elapsedRealtime() - System.currentTimeMillis()));
                    contentView.setBoolean(R.id.chronometer, "setStarted", true);
                    boolean countsDown = mN.extras.getBoolean(EXTRA_CHRONOMETER_COUNT_DOWN);
                    contentView.setChronometerCountDown(R.id.chronometer, countsDown);
                    setTextViewColorSecondary(contentView, R.id.chronometer, p);
                } else {
                    contentView.setViewVisibility(R.id.time, View.VISIBLE);
                    contentView.setLong(R.id.time, "setTime", mN.when);
                    setTextViewColorSecondary(contentView, R.id.time, p);
                }
            } else {
                // We still want a time to be set but gone, such that we can show and hide it
                // on demand in case it's a child notification without anything in the header
                contentView.setLong(R.id.time, "setTime", mN.when != 0 ? mN.when : mN.creationTime);
            }
        }

        private void bindHeaderText(RemoteViews contentView, StandardTemplateParams p) {
            CharSequence summaryText = p.summaryText;
            if (summaryText == null && mStyle != null && mStyle.mSummaryTextSet
                    && mStyle.hasSummaryInHeader()) {
                summaryText = mStyle.mSummaryText;
            }
            if (summaryText == null
                    && mContext.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.N
                    && mN.extras.getCharSequence(EXTRA_INFO_TEXT) != null) {
                summaryText = mN.extras.getCharSequence(EXTRA_INFO_TEXT);
            }
            if (summaryText != null) {
                // TODO: Remove the span entirely to only have the string with propper formating.
                contentView.setTextViewText(R.id.header_text, processTextSpans(
                        processLegacyText(summaryText)));
                setTextViewColorSecondary(contentView, R.id.header_text, p);
                contentView.setViewVisibility(R.id.header_text, View.VISIBLE);
                contentView.setViewVisibility(R.id.header_text_divider, View.VISIBLE);
                setTextViewColorSecondary(contentView, R.id.header_text_divider, p);
            }
        }

        private void bindHeaderTextSecondary(RemoteViews contentView, StandardTemplateParams p) {
            if (!TextUtils.isEmpty(p.headerTextSecondary)) {
                contentView.setTextViewText(R.id.header_text_secondary, processTextSpans(
                        processLegacyText(p.headerTextSecondary)));
                setTextViewColorSecondary(contentView, R.id.header_text_secondary, p);
                contentView.setViewVisibility(R.id.header_text_secondary, View.VISIBLE);
                contentView.setViewVisibility(R.id.header_text_secondary_divider, View.VISIBLE);
                setTextViewColorSecondary(contentView, R.id.header_text_secondary_divider, p);
            }
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public String loadHeaderAppName() {
            CharSequence name = null;
            final PackageManager pm = mContext.getPackageManager();
            if (mN.extras.containsKey(EXTRA_SUBSTITUTE_APP_NAME)) {
                // only system packages which lump together a bunch of unrelated stuff
                // may substitute a different name to make the purpose of the
                // notification more clear. the correct package label should always
                // be accessible via SystemUI.
                final String pkg = mContext.getPackageName();
                final String subName = mN.extras.getString(EXTRA_SUBSTITUTE_APP_NAME);
                if (PackageManager.PERMISSION_GRANTED == pm.checkPermission(
                        android.Manifest.permission.SUBSTITUTE_NOTIFICATION_APP_NAME, pkg)) {
                    name = subName;
                } else {
                    Log.w(TAG, "warning: pkg "
                            + pkg + " attempting to substitute app name '" + subName
                            + "' without holding perm "
                            + android.Manifest.permission.SUBSTITUTE_NOTIFICATION_APP_NAME);
                }
            }
            if (TextUtils.isEmpty(name)) {
                name = pm.getApplicationLabel(mContext.getApplicationInfo());
            }
            if (TextUtils.isEmpty(name)) {
                // still nothing?
                return null;
            }

            return String.valueOf(name);
        }
        private void bindHeaderAppName(RemoteViews contentView, StandardTemplateParams p) {
            contentView.setTextViewText(R.id.app_name_text, loadHeaderAppName());
            if (isColorized(p)) {
                setTextViewColorPrimary(contentView, R.id.app_name_text, p);
            } else {
                contentView.setTextColor(R.id.app_name_text, getSecondaryTextColor(p));
            }
        }

        private boolean isColorized(StandardTemplateParams p) {
            return p.allowColorization && mN.isColorized();
        }

        private void bindSmallIcon(RemoteViews contentView, StandardTemplateParams p) {
            if (mN.mSmallIcon == null && mN.icon != 0) {
                mN.mSmallIcon = Icon.createWithResource(mContext, mN.icon);
            }
            contentView.setImageViewIcon(R.id.icon, mN.mSmallIcon);
            contentView.setInt(R.id.icon, "setImageLevel", mN.iconLevel);
            processSmallIconColor(mN.mSmallIcon, contentView, p);
        }

        /**
         * @return true if the built notification will show the time or the chronometer; false
         *         otherwise
         */
        private boolean showsTimeOrChronometer() {
            return mN.showsTime() || mN.showsChronometer();
        }

        private void resetStandardTemplateWithActions(RemoteViews big) {
            // actions_container is only reset when there are no actions to avoid focus issues with
            // remote inputs.
            big.setViewVisibility(R.id.actions, View.GONE);
            big.removeAllViews(R.id.actions);

            big.setViewVisibility(R.id.notification_material_reply_container, View.GONE);
            big.setTextViewText(R.id.notification_material_reply_text_1, null);
            big.setViewVisibility(R.id.notification_material_reply_text_1_container, View.GONE);
            big.setViewVisibility(R.id.notification_material_reply_progress, View.GONE);

            big.setViewVisibility(R.id.notification_material_reply_text_2, View.GONE);
            big.setTextViewText(R.id.notification_material_reply_text_2, null);
            big.setViewVisibility(R.id.notification_material_reply_text_3, View.GONE);
            big.setTextViewText(R.id.notification_material_reply_text_3, null);

            big.setViewLayoutMarginBottomDimen(R.id.notification_action_list_margin_target,
                    R.dimen.notification_content_margin);
        }

        private RemoteViews applyStandardTemplateWithActions(int layoutId,
                TemplateBindResult result) {
            return applyStandardTemplateWithActions(layoutId, mParams.reset().fillTextsFrom(this),
                    result);
        }

        private static List<Notification.Action> filterOutContextualActions(
                List<Notification.Action> actions) {
            List<Notification.Action> nonContextualActions = new ArrayList<>();
            for (Notification.Action action : actions) {
                if (!action.isContextual()) {
                    nonContextualActions.add(action);
                }
            }
            return nonContextualActions;
        }

        private RemoteViews applyStandardTemplateWithActions(int layoutId,
                StandardTemplateParams p, TemplateBindResult result) {
            RemoteViews big = applyStandardTemplate(layoutId, p, result);

            resetStandardTemplateWithActions(big);

            boolean validRemoteInput = false;

            // In the UI contextual actions appear separately from the standard actions, so we
            // filter them out here.
            List<Notification.Action> nonContextualActions = filterOutContextualActions(mActions);

            int N = nonContextualActions.size();
            boolean emphazisedMode = mN.fullScreenIntent != null;
            big.setBoolean(R.id.actions, "setEmphasizedMode", emphazisedMode);
            if (N > 0) {
                big.setViewVisibility(R.id.actions_container, View.VISIBLE);
                big.setViewVisibility(R.id.actions, View.VISIBLE);
                big.setViewLayoutMarginBottomDimen(R.id.notification_action_list_margin_target, 0);
                if (N>MAX_ACTION_BUTTONS) N=MAX_ACTION_BUTTONS;
                for (int i=0; i<N; i++) {
                    Action action = nonContextualActions.get(i);

                    boolean actionHasValidInput = hasValidRemoteInput(action);
                    validRemoteInput |= actionHasValidInput;

                    final RemoteViews button = generateActionButton(action, emphazisedMode, p);
                    if (actionHasValidInput && !emphazisedMode) {
                        // Clear the drawable
                        button.setInt(R.id.action0, "setBackgroundResource", 0);
                    }
                    big.addView(R.id.actions, button);
                }
            } else {
                big.setViewVisibility(R.id.actions_container, View.GONE);
            }

            CharSequence[] replyText = mN.extras.getCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY);
            if (validRemoteInput && replyText != null
                    && replyText.length > 0 && !TextUtils.isEmpty(replyText[0])
                    && p.maxRemoteInputHistory > 0) {
                boolean showSpinner = mN.extras.getBoolean(EXTRA_SHOW_REMOTE_INPUT_SPINNER);
                big.setViewVisibility(R.id.notification_material_reply_container, View.VISIBLE);
                big.setViewVisibility(R.id.notification_material_reply_text_1_container,
                        View.VISIBLE);
                big.setTextViewText(R.id.notification_material_reply_text_1,
                        processTextSpans(replyText[0]));
                setTextViewColorSecondary(big, R.id.notification_material_reply_text_1, p);
                big.setViewVisibility(R.id.notification_material_reply_progress,
                        showSpinner ? View.VISIBLE : View.GONE);
                big.setProgressIndeterminateTintList(
                        R.id.notification_material_reply_progress,
                        ColorStateList.valueOf(
                                isColorized(p) ? getPrimaryTextColor(p) : resolveContrastColor(p)));

                if (replyText.length > 1 && !TextUtils.isEmpty(replyText[1])
                        && p.maxRemoteInputHistory > 1) {
                    big.setViewVisibility(R.id.notification_material_reply_text_2, View.VISIBLE);
                    big.setTextViewText(R.id.notification_material_reply_text_2,
                            processTextSpans(replyText[1]));
                    setTextViewColorSecondary(big, R.id.notification_material_reply_text_2, p);

                    if (replyText.length > 2 && !TextUtils.isEmpty(replyText[2])
                            && p.maxRemoteInputHistory > 2) {
                        big.setViewVisibility(
                                R.id.notification_material_reply_text_3, View.VISIBLE);
                        big.setTextViewText(R.id.notification_material_reply_text_3,
                                processTextSpans(replyText[2]));
                        setTextViewColorSecondary(big, R.id.notification_material_reply_text_3, p);
                    }
                }
            }

            return big;
        }

        private boolean hasValidRemoteInput(Action action) {
            if (TextUtils.isEmpty(action.title) || action.actionIntent == null) {
                // Weird actions
                return false;
            }

            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs == null) {
                return false;
            }

            for (RemoteInput r : remoteInputs) {
                CharSequence[] choices = r.getChoices();
                if (r.getAllowFreeFormInput() || (choices != null && choices.length != 0)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Construct a RemoteViews for the final 1U notification layout. In order:
         *   1. Custom contentView from the caller
         *   2. Style's proposed content view
         *   3. Standard template view
         */
        public RemoteViews createContentView() {
            return createContentView(false /* increasedheight */ );
        }

        /**
         * Construct a RemoteViews for the smaller content view.
         *
         *   @param increasedHeight true if this layout be created with an increased height. Some
         *   styles may support showing more then just that basic 1U size
         *   and the system may decide to render important notifications
         *   slightly bigger even when collapsed.
         *
         *   @hide
         */
        public RemoteViews createContentView(boolean increasedHeight) {
            if (mN.contentView != null && useExistingRemoteView()) {
                return mN.contentView;
            } else if (mStyle != null) {
                final RemoteViews styleView = mStyle.makeContentView(increasedHeight);
                if (styleView != null) {
                    return styleView;
                }
            }
            return applyStandardTemplate(getBaseLayoutResource(), null /* result */);
        }

        private boolean useExistingRemoteView() {
            return mStyle == null || (!mStyle.displayCustomViewInline()
                    && !mRebuildStyledRemoteViews);
        }

        /**
         * Construct a RemoteViews for the final big notification layout.
         */
        public RemoteViews createBigContentView() {
            RemoteViews result = null;
            if (mN.bigContentView != null && useExistingRemoteView()) {
                return mN.bigContentView;
            } else if (mStyle != null) {
                result = mStyle.makeBigContentView();
                hideLine1Text(result);
            } else if (mActions.size() != 0) {
                result = applyStandardTemplateWithActions(getBigBaseLayoutResource(),
                        null /* result */);
            }
            makeHeaderExpanded(result);
            return result;
        }

        /**
         * Construct a RemoteViews for the final notification header only. This will not be
         * colorized.
         *
         * @hide
         */
        public RemoteViews makeNotificationHeader() {
            return makeNotificationHeader(mParams.reset().fillTextsFrom(this));
        }

        /**
         * Construct a RemoteViews for the final notification header only. This will not be
         * colorized.
         *
         * @param p the template params to inflate this with
         */
        private RemoteViews makeNotificationHeader(StandardTemplateParams p) {
            // Headers on their own are never colorized
            p.disallowColorization();
            RemoteViews header = new BuilderRemoteViews(mContext.getApplicationInfo(),
                    R.layout.notification_template_header);
            resetNotificationHeader(header);
            bindNotificationHeader(header, p);
            return header;
        }

        /**
         * Construct a RemoteViews for the ambient version of the notification.
         *
         * @hide
         */
        public RemoteViews makeAmbientNotification() {
            RemoteViews headsUpContentView = createHeadsUpContentView(false /* increasedHeight */);
            if (headsUpContentView != null) {
                return headsUpContentView;
            }
            return createContentView();
        }

        private void hideLine1Text(RemoteViews result) {
            if (result != null) {
                result.setViewVisibility(R.id.text_line_1, View.GONE);
            }
        }

        /**
         * Adapt the Notification header if this view is used as an expanded view.
         *
         * @hide
         */
        public static void makeHeaderExpanded(RemoteViews result) {
            if (result != null) {
                result.setBoolean(R.id.notification_header, "setExpanded", true);
            }
        }

        /**
         * Construct a RemoteViews for the final heads-up notification layout.
         *
         * @param increasedHeight true if this layout be created with an increased height. Some
         * styles may support showing more then just that basic 1U size
         * and the system may decide to render important notifications
         * slightly bigger even when collapsed.
         *
         * @hide
         */
        public RemoteViews createHeadsUpContentView(boolean increasedHeight) {
            if (mN.headsUpContentView != null && useExistingRemoteView()) {
                return mN.headsUpContentView;
            } else if (mStyle != null) {
                final RemoteViews styleView = mStyle.makeHeadsUpContentView(increasedHeight);
                if (styleView != null) {
                    return styleView;
                }
            } else if (mActions.size() == 0) {
                return null;
            }

            // We only want at most a single remote input history to be shown here, otherwise
            // the content would become squished.
            StandardTemplateParams p = mParams.reset().fillTextsFrom(this)
                    .setMaxRemoteInputHistory(1);
            return applyStandardTemplateWithActions(getBigBaseLayoutResource(),
                    p,
                    null /* result */);
        }

        /**
         * Construct a RemoteViews for the final heads-up notification layout.
         */
        public RemoteViews createHeadsUpContentView() {
            return createHeadsUpContentView(false /* useIncreasedHeight */);
        }

        /**
         * Construct a RemoteViews for the display in public contexts like on the lockscreen.
         *
         * @hide
         */
        @UnsupportedAppUsage
        public RemoteViews makePublicContentView() {
            return makePublicView(false /* ambient */);
        }

        /**
         * Construct a RemoteViews for the display in public contexts like on the lockscreen.
         *
         * @hide
         */
        public RemoteViews makePublicAmbientNotification() {
            return makePublicView(true /* ambient */);
        }

        private RemoteViews makePublicView(boolean ambient) {
            if (mN.publicVersion != null) {
                final Builder builder = recoverBuilder(mContext, mN.publicVersion);
                return ambient ? builder.makeAmbientNotification() : builder.createContentView();
            }
            Bundle savedBundle = mN.extras;
            Style style = mStyle;
            mStyle = null;
            Icon largeIcon = mN.mLargeIcon;
            mN.mLargeIcon = null;
            Bitmap largeIconLegacy = mN.largeIcon;
            mN.largeIcon = null;
            ArrayList<Action> actions = mActions;
            mActions = new ArrayList<>();
            Bundle publicExtras = new Bundle();
            publicExtras.putBoolean(EXTRA_SHOW_WHEN,
                    savedBundle.getBoolean(EXTRA_SHOW_WHEN));
            publicExtras.putBoolean(EXTRA_SHOW_CHRONOMETER,
                    savedBundle.getBoolean(EXTRA_SHOW_CHRONOMETER));
            publicExtras.putBoolean(EXTRA_CHRONOMETER_COUNT_DOWN,
                    savedBundle.getBoolean(EXTRA_CHRONOMETER_COUNT_DOWN));
            String appName = savedBundle.getString(EXTRA_SUBSTITUTE_APP_NAME);
            if (appName != null) {
                publicExtras.putString(EXTRA_SUBSTITUTE_APP_NAME, appName);
            }
            mN.extras = publicExtras;
            RemoteViews view;
            view = makeNotificationHeader();
            view.setBoolean(R.id.notification_header, "setExpandOnlyOnButton", true);
            mN.extras = savedBundle;
            mN.mLargeIcon = largeIcon;
            mN.largeIcon = largeIconLegacy;
            mActions = actions;
            mStyle = style;
            return view;
        }

        /**
         * Construct a content view for the display when low - priority
         *
         * @param useRegularSubtext uses the normal subtext set if there is one available. Otherwise
         *                          a new subtext is created consisting of the content of the
         *                          notification.
         * @hide
         */
        public RemoteViews makeLowPriorityContentView(boolean useRegularSubtext) {
            StandardTemplateParams p = mParams.reset()
                    .forceDefaultColor()
                    .fillTextsFrom(this);
            if (!useRegularSubtext || TextUtils.isEmpty(mParams.summaryText)) {
                p.summaryText(createSummaryText());
            }
            RemoteViews header = makeNotificationHeader(p);
            header.setBoolean(R.id.notification_header, "setAcceptAllTouches", true);
            return header;
        }

        private CharSequence createSummaryText() {
            CharSequence titleText = mN.extras.getCharSequence(Notification.EXTRA_TITLE);
            if (USE_ONLY_TITLE_IN_LOW_PRIORITY_SUMMARY) {
                return titleText;
            }
            SpannableStringBuilder summary = new SpannableStringBuilder();
            if (titleText == null) {
                titleText = mN.extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
            }
            BidiFormatter bidi = BidiFormatter.getInstance();
            if (titleText != null) {
                summary.append(bidi.unicodeWrap(titleText));
            }
            CharSequence contentText = mN.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (titleText != null && contentText != null) {
                summary.append(bidi.unicodeWrap(mContext.getText(
                        R.string.notification_header_divider_symbol_with_spaces)));
            }
            if (contentText != null) {
                summary.append(bidi.unicodeWrap(contentText));
            }
            return summary;
        }

        private RemoteViews generateActionButton(Action action, boolean emphazisedMode,
                StandardTemplateParams p) {
            final boolean tombstone = (action.actionIntent == null);
            RemoteViews button = new BuilderRemoteViews(mContext.getApplicationInfo(),
                    emphazisedMode ? getEmphasizedActionLayoutResource()
                            : tombstone ? getActionTombstoneLayoutResource()
                                    : getActionLayoutResource());
            if (!tombstone) {
                button.setOnClickPendingIntent(R.id.action0, action.actionIntent);
            }
            button.setContentDescription(R.id.action0, action.title);
            if (action.mRemoteInputs != null) {
                button.setRemoteInputs(R.id.action0, action.mRemoteInputs);
            }
            if (emphazisedMode) {
                // change the background bgColor
                CharSequence title = action.title;
                ColorStateList[] outResultColor = null;
                int background = resolveBackgroundColor(p);
                if (isLegacy()) {
                    title = ContrastColorUtil.clearColorSpans(title);
                } else {
                    outResultColor = new ColorStateList[1];
                    title = ensureColorSpanContrast(title, background, outResultColor);
                }
                button.setTextViewText(R.id.action0, processTextSpans(title));
                setTextViewColorPrimary(button, R.id.action0, p);
                int rippleColor;
                boolean hasColorOverride = outResultColor != null && outResultColor[0] != null;
                if (hasColorOverride) {
                    // There's a span spanning the full text, let's take it and use it as the
                    // background color
                    background = outResultColor[0].getDefaultColor();
                    int textColor = ContrastColorUtil.resolvePrimaryColor(mContext,
                            background, mInNightMode);
                    button.setTextColor(R.id.action0, textColor);
                    rippleColor = textColor;
                } else if (getRawColor(p) != COLOR_DEFAULT && !isColorized(p)
                        && mTintActionButtons) {
                    rippleColor = resolveContrastColor(p);
                    button.setTextColor(R.id.action0, rippleColor);
                } else {
                    rippleColor = getPrimaryTextColor(p);
                }
                // We only want about 20% alpha for the ripple
                rippleColor = (rippleColor & 0x00ffffff) | 0x33000000;
                button.setColorStateList(R.id.action0, "setRippleColor",
                        ColorStateList.valueOf(rippleColor));
                button.setColorStateList(R.id.action0, "setButtonBackground",
                        ColorStateList.valueOf(background));
                button.setBoolean(R.id.action0, "setHasStroke", !hasColorOverride);
            } else {
                button.setTextViewText(R.id.action0, processTextSpans(
                        processLegacyText(action.title)));
                if (isColorized(p)) {
                    setTextViewColorPrimary(button, R.id.action0, p);
                } else if (getRawColor(p) != COLOR_DEFAULT && mTintActionButtons) {
                    button.setTextColor(R.id.action0, resolveContrastColor(p));
                }
            }
            button.setIntTag(R.id.action0, R.id.notification_action_index_tag,
                    mActions.indexOf(action));
            return button;
        }

        /**
         * Ensures contrast on color spans against a background color. also returns the color of the
         * text if a span was found that spans over the whole text.
         *
         * @param charSequence the charSequence on which the spans are
         * @param background the background color to ensure the contrast against
         * @param outResultColor an array in which a color will be returned as the first element if
         *                    there exists a full length color span.
         * @return the contrasted charSequence
         */
        private CharSequence ensureColorSpanContrast(CharSequence charSequence, int background,
                ColorStateList[] outResultColor) {
            if (charSequence instanceof Spanned) {
                Spanned ss = (Spanned) charSequence;
                Object[] spans = ss.getSpans(0, ss.length(), Object.class);
                SpannableStringBuilder builder = new SpannableStringBuilder(ss.toString());
                for (Object span : spans) {
                    Object resultSpan = span;
                    int spanStart = ss.getSpanStart(span);
                    int spanEnd = ss.getSpanEnd(span);
                    boolean fullLength = (spanEnd - spanStart) == charSequence.length();
                    if (resultSpan instanceof CharacterStyle) {
                        resultSpan = ((CharacterStyle) span).getUnderlying();
                    }
                    if (resultSpan instanceof TextAppearanceSpan) {
                        TextAppearanceSpan originalSpan = (TextAppearanceSpan) resultSpan;
                        ColorStateList textColor = originalSpan.getTextColor();
                        if (textColor != null) {
                            int[] colors = textColor.getColors();
                            int[] newColors = new int[colors.length];
                            for (int i = 0; i < newColors.length; i++) {
                                newColors[i] = ContrastColorUtil.ensureLargeTextContrast(
                                        colors[i], background, mInNightMode);
                            }
                            textColor = new ColorStateList(textColor.getStates().clone(),
                                    newColors);
                            if (fullLength) {
                                outResultColor[0] = textColor;
                                // Let's drop the color from the span
                                textColor = null;
                            }
                            resultSpan = new TextAppearanceSpan(
                                    originalSpan.getFamily(),
                                    originalSpan.getTextStyle(),
                                    originalSpan.getTextSize(),
                                    textColor,
                                    originalSpan.getLinkTextColor());
                        }
                    } else if (resultSpan instanceof ForegroundColorSpan) {
                        ForegroundColorSpan originalSpan = (ForegroundColorSpan) resultSpan;
                        int foregroundColor = originalSpan.getForegroundColor();
                        foregroundColor = ContrastColorUtil.ensureLargeTextContrast(
                                foregroundColor, background, mInNightMode);
                        if (fullLength) {
                            outResultColor[0] = ColorStateList.valueOf(foregroundColor);
                            resultSpan = null;
                        } else {
                            resultSpan = new ForegroundColorSpan(foregroundColor);
                        }
                    } else {
                        resultSpan = span;
                    }
                    if (resultSpan != null) {
                        builder.setSpan(resultSpan, spanStart, spanEnd, ss.getSpanFlags(span));
                    }
                }
                return builder;
            }
            return charSequence;
        }

        /**
         * @return Whether we are currently building a notification from a legacy (an app that
         *         doesn't create material notifications by itself) app.
         */
        private boolean isLegacy() {
            if (!mIsLegacyInitialized) {
                mIsLegacy = mContext.getApplicationInfo().targetSdkVersion
                        < Build.VERSION_CODES.LOLLIPOP;
                mIsLegacyInitialized = true;
            }
            return mIsLegacy;
        }

        private CharSequence processLegacyText(CharSequence charSequence) {
            boolean isAlreadyLightText = isLegacy() || textColorsNeedInversion();
            if (isAlreadyLightText) {
                return getColorUtil().invertCharSequenceColors(charSequence);
            } else {
                return charSequence;
            }
        }

        /**
         * Apply any necessariy colors to the small icon
         */
        private void processSmallIconColor(Icon smallIcon, RemoteViews contentView,
                StandardTemplateParams p) {
            boolean colorable = !isLegacy() || getColorUtil().isGrayscaleIcon(mContext, smallIcon);
            int color;
            if (isColorized(p)) {
                color = getPrimaryTextColor(p);
            } else {
                color = resolveContrastColor(p);
            }
            if (colorable) {
                contentView.setDrawableTint(R.id.icon, false, color,
                        PorterDuff.Mode.SRC_ATOP);

            }
            contentView.setInt(R.id.notification_header, "setOriginalIconColor",
                    colorable ? color : NotificationHeaderView.NO_COLOR);
        }

        /**
         * Make the largeIcon dark if it's a fake smallIcon (that is,
         * if it's grayscale).
         */
        // TODO: also check bounds, transparency, that sort of thing.
        private void processLargeLegacyIcon(Icon largeIcon, RemoteViews contentView,
                StandardTemplateParams p) {
            if (largeIcon != null && isLegacy()
                    && getColorUtil().isGrayscaleIcon(mContext, largeIcon)) {
                // resolve color will fall back to the default when legacy
                contentView.setDrawableTint(R.id.icon, false, resolveContrastColor(p),
                        PorterDuff.Mode.SRC_ATOP);
            }
        }

        private void sanitizeColor() {
            if (mN.color != COLOR_DEFAULT) {
                mN.color |= 0xFF000000; // no alpha for custom colors
            }
        }

        int resolveContrastColor(StandardTemplateParams p) {
            int rawColor = getRawColor(p);
            if (mCachedContrastColorIsFor == rawColor && mCachedContrastColor != COLOR_INVALID) {
                return mCachedContrastColor;
            }

            int color;
            int background = mContext.getColor(
                    com.android.internal.R.color.notification_material_background_color);
            if (rawColor == COLOR_DEFAULT) {
                ensureColors(p);
                color = ContrastColorUtil.resolveDefaultColor(mContext, background, mInNightMode);
            } else {
                color = ContrastColorUtil.resolveContrastColor(mContext, rawColor,
                        background, mInNightMode);
            }
            if (Color.alpha(color) < 255) {
                // alpha doesn't go well for color filters, so let's blend it manually
                color = ContrastColorUtil.compositeColors(color, background);
            }
            mCachedContrastColorIsFor = rawColor;
            return mCachedContrastColor = color;
        }

        /**
         * Return the raw color of this Notification, which doesn't necessarily satisfy contrast.
         *
         * @see #resolveContrastColor(StandardTemplateParams) for the contrasted color
         * @param p the template params to inflate this with
         */
        private int getRawColor(StandardTemplateParams p) {
            if (p.forceDefaultColor) {
                return COLOR_DEFAULT;
            }
            return mN.color;
        }

        int resolveNeutralColor() {
            if (mNeutralColor != COLOR_INVALID) {
                return mNeutralColor;
            }
            int background = mContext.getColor(
                    com.android.internal.R.color.notification_material_background_color);
            mNeutralColor = ContrastColorUtil.resolveDefaultColor(mContext, background,
                    mInNightMode);
            if (Color.alpha(mNeutralColor) < 255) {
                // alpha doesn't go well for color filters, so let's blend it manually
                mNeutralColor = ContrastColorUtil.compositeColors(mNeutralColor, background);
            }
            return mNeutralColor;
        }

        /**
         * Apply the unstyled operations and return a new {@link Notification} object.
         * @hide
         */
        @NonNull
        public Notification buildUnstyled() {
            if (mActions.size() > 0) {
                mN.actions = new Action[mActions.size()];
                mActions.toArray(mN.actions);
            }
            if (!mPersonList.isEmpty()) {
                mN.extras.putParcelableArrayList(EXTRA_PEOPLE_LIST, mPersonList);
            }
            if (mN.bigContentView != null || mN.contentView != null
                    || mN.headsUpContentView != null) {
                mN.extras.putBoolean(EXTRA_CONTAINS_CUSTOM_VIEW, true);
            }
            return mN;
        }

        /**
         * Creates a Builder from an existing notification so further changes can be made.
         * @param context The context for your application / activity.
         * @param n The notification to create a Builder from.
         */
        @NonNull
        public static Notification.Builder recoverBuilder(Context context, Notification n) {
            // Re-create notification context so we can access app resources.
            ApplicationInfo applicationInfo = n.extras.getParcelable(
                    EXTRA_BUILDER_APPLICATION_INFO);
            Context builderContext;
            if (applicationInfo != null) {
                try {
                    builderContext = context.createApplicationContext(applicationInfo,
                            Context.CONTEXT_RESTRICTED);
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "ApplicationInfo " + applicationInfo + " not found");
                    builderContext = context;  // try with our context
                }
            } else {
                builderContext = context; // try with given context
            }

            return new Builder(builderContext, n);
        }

        /**
         * Determines whether the platform can generate contextual actions for a notification.
         * By default this is true.
         */
        @NonNull
        public Builder setAllowSystemGeneratedContextualActions(boolean allowed) {
            mN.mAllowSystemGeneratedContextualActions = allowed;
            return this;
        }

        /**
         * @deprecated Use {@link #build()} instead.
         */
        @Deprecated
        public Notification getNotification() {
            return build();
        }

        /**
         * Combine all of the options that have been set and return a new {@link Notification}
         * object.
         */
        @NonNull
        public Notification build() {
            // first, add any extras from the calling code
            if (mUserExtras != null) {
                mN.extras = getAllExtras();
            }

            mN.creationTime = System.currentTimeMillis();

            // lazy stuff from mContext; see comment in Builder(Context, Notification)
            Notification.addFieldsFromContext(mContext, mN);

            buildUnstyled();

            if (mStyle != null) {
                mStyle.reduceImageSizes(mContext);
                mStyle.purgeResources();
                mStyle.validate(mContext);
                mStyle.buildStyled(mN);
            }
            mN.reduceImageSizes(mContext);

            if (mContext.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.N
                    && (useExistingRemoteView())) {
                if (mN.contentView == null) {
                    mN.contentView = createContentView();
                    mN.extras.putInt(EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT,
                            mN.contentView.getSequenceNumber());
                }
                if (mN.bigContentView == null) {
                    mN.bigContentView = createBigContentView();
                    if (mN.bigContentView != null) {
                        mN.extras.putInt(EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT,
                                mN.bigContentView.getSequenceNumber());
                    }
                }
                if (mN.headsUpContentView == null) {
                    mN.headsUpContentView = createHeadsUpContentView();
                    if (mN.headsUpContentView != null) {
                        mN.extras.putInt(EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT,
                                mN.headsUpContentView.getSequenceNumber());
                    }
                }
            }

            if ((mN.defaults & DEFAULT_LIGHTS) != 0) {
                mN.flags |= FLAG_SHOW_LIGHTS;
            }

            mN.allPendingIntents = null;

            return mN;
        }

        /**
         * Apply this Builder to an existing {@link Notification} object.
         *
         * @hide
         */
        @NonNull
        public Notification buildInto(@NonNull Notification n) {
            build().cloneInto(n, true);
            return n;
        }

        /**
         * Removes RemoteViews that were created for compatibility from {@param n}, if they did not
         * change. Also removes extenders on low ram devices, as
         * {@link android.service.notification.NotificationListenerService} services are disabled.
         *
         * @return {@param n}, if no stripping is needed, otherwise a stripped clone of {@param n}.
         *
         * @hide
         */
        public static Notification maybeCloneStrippedForDelivery(Notification n, boolean isLowRam,
                Context context) {
            String templateClass = n.extras.getString(EXTRA_TEMPLATE);

            // Only strip views for known Styles because we won't know how to
            // re-create them otherwise.
            if (!isLowRam
                    && !TextUtils.isEmpty(templateClass)
                    && getNotificationStyleClass(templateClass) == null) {
                return n;
            }

            // Only strip unmodified BuilderRemoteViews.
            boolean stripContentView = n.contentView instanceof BuilderRemoteViews &&
                    n.extras.getInt(EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT, -1) ==
                            n.contentView.getSequenceNumber();
            boolean stripBigContentView = n.bigContentView instanceof BuilderRemoteViews &&
                    n.extras.getInt(EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT, -1) ==
                            n.bigContentView.getSequenceNumber();
            boolean stripHeadsUpContentView = n.headsUpContentView instanceof BuilderRemoteViews &&
                    n.extras.getInt(EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT, -1) ==
                            n.headsUpContentView.getSequenceNumber();

            // Nothing to do here, no need to clone.
            if (!isLowRam
                    && !stripContentView && !stripBigContentView && !stripHeadsUpContentView) {
                return n;
            }

            Notification clone = n.clone();
            if (stripContentView) {
                clone.contentView = null;
                clone.extras.remove(EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT);
            }
            if (stripBigContentView) {
                clone.bigContentView = null;
                clone.extras.remove(EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT);
            }
            if (stripHeadsUpContentView) {
                clone.headsUpContentView = null;
                clone.extras.remove(EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT);
            }
            if (isLowRam) {
                String[] allowedServices = context.getResources().getStringArray(
                        R.array.config_allowedManagedServicesOnLowRamDevices);
                if (allowedServices.length == 0) {
                    clone.extras.remove(Notification.TvExtender.EXTRA_TV_EXTENDER);
                    clone.extras.remove(WearableExtender.EXTRA_WEARABLE_EXTENSIONS);
                    clone.extras.remove(CarExtender.EXTRA_CAR_EXTENDER);
                }
            }
            return clone;
        }

        @UnsupportedAppUsage
        private int getBaseLayoutResource() {
            return R.layout.notification_template_material_base;
        }

        private int getBigBaseLayoutResource() {
            return R.layout.notification_template_material_big_base;
        }

        private int getBigPictureLayoutResource() {
            return R.layout.notification_template_material_big_picture;
        }

        private int getBigTextLayoutResource() {
            return R.layout.notification_template_material_big_text;
        }

        private int getInboxLayoutResource() {
            return R.layout.notification_template_material_inbox;
        }

        private int getMessagingLayoutResource() {
            return R.layout.notification_template_material_messaging;
        }

        private int getActionLayoutResource() {
            return R.layout.notification_material_action;
        }

        private int getEmphasizedActionLayoutResource() {
            return R.layout.notification_material_action_emphasized;
        }

        private int getActionTombstoneLayoutResource() {
            return R.layout.notification_material_action_tombstone;
        }

        private int getBackgroundColor(StandardTemplateParams p) {
            if (isColorized(p)) {
                return mBackgroundColor != COLOR_INVALID ? mBackgroundColor : getRawColor(p);
            } else {
                return COLOR_DEFAULT;
            }
        }

        /**
         * Gets a neutral color that can be used for icons or similar that should not stand out.
         * @param p the template params to inflate this with
         */
        private int getNeutralColor(StandardTemplateParams p) {
            if (isColorized(p)) {
                return getSecondaryTextColor(p);
            } else {
                return resolveNeutralColor();
            }
        }

        /**
         * Same as getBackgroundColor but also resolved the default color to the background.
         * @param p the template params to inflate this with
         */
        private int resolveBackgroundColor(StandardTemplateParams p) {
            int backgroundColor = getBackgroundColor(p);
            if (backgroundColor == COLOR_DEFAULT) {
                backgroundColor = mContext.getColor(
                        com.android.internal.R.color.notification_material_background_color);
            }
            return backgroundColor;
        }

        private boolean shouldTintActionButtons() {
            return mTintActionButtons;
        }

        private boolean textColorsNeedInversion() {
            if (mStyle == null || !MediaStyle.class.equals(mStyle.getClass())) {
                return false;
            }
            int targetSdkVersion = mContext.getApplicationInfo().targetSdkVersion;
            return targetSdkVersion > Build.VERSION_CODES.M
                    && targetSdkVersion < Build.VERSION_CODES.O;
        }

        /**
         * Set a color palette to be used as the background and textColors
         *
         * @param backgroundColor the color to be used as the background
         * @param foregroundColor the color to be used as the foreground
         *
         * @hide
         */
        public void setColorPalette(int backgroundColor, int foregroundColor) {
            mBackgroundColor = backgroundColor;
            mForegroundColor = foregroundColor;
            mTextColorsAreForBackground = COLOR_INVALID;
            ensureColors(mParams.reset().fillTextsFrom(this));
        }

        /**
         * Forces all styled remoteViews to be built from scratch and not use any cached
         * RemoteViews.
         * This is needed for legacy apps that are baking in their remoteviews into the
         * notification.
         *
         * @hide
         */
        public void setRebuildStyledRemoteViews(boolean rebuild) {
            mRebuildStyledRemoteViews = rebuild;
        }

        /**
         * Get the text that should be displayed in the statusBar when heads upped. This is
         * usually just the app name, but may be different depending on the style.
         *
         * @param publicMode If true, return a text that is safe to display in public.
         *
         * @hide
         */
        public CharSequence getHeadsUpStatusBarText(boolean publicMode) {
            if (mStyle != null && !publicMode) {
                CharSequence text = mStyle.getHeadsUpStatusBarText();
                if (!TextUtils.isEmpty(text)) {
                    return text;
                }
            }
            return loadHeaderAppName();
        }
    }

    /**
     * Reduces the image sizes to conform to a maximum allowed size. This also processes all custom
     * remote views.
     *
     * @hide
     */
    void reduceImageSizes(Context context) {
        if (extras.getBoolean(EXTRA_REDUCED_IMAGES)) {
            return;
        }
        boolean isLowRam = ActivityManager.isLowRamDeviceStatic();
        if (mLargeIcon != null || largeIcon != null) {
            Resources resources = context.getResources();
            Class<? extends Style> style = getNotificationStyle();
            int maxWidth = resources.getDimensionPixelSize(isLowRam
                    ? R.dimen.notification_right_icon_size_low_ram
                    : R.dimen.notification_right_icon_size);
            int maxHeight = maxWidth;
            if (MediaStyle.class.equals(style)
                    || DecoratedMediaCustomViewStyle.class.equals(style)) {
                maxHeight = resources.getDimensionPixelSize(isLowRam
                        ? R.dimen.notification_media_image_max_height_low_ram
                        : R.dimen.notification_media_image_max_height);
                maxWidth = resources.getDimensionPixelSize(isLowRam
                        ? R.dimen.notification_media_image_max_width_low_ram
                        : R.dimen.notification_media_image_max_width);
            }
            if (mLargeIcon != null) {
                mLargeIcon.scaleDownIfNecessary(maxWidth, maxHeight);
            }
            if (largeIcon != null) {
                largeIcon = Icon.scaleDownIfNecessary(largeIcon, maxWidth, maxHeight);
            }
        }
        reduceImageSizesForRemoteView(contentView, context, isLowRam);
        reduceImageSizesForRemoteView(headsUpContentView, context, isLowRam);
        reduceImageSizesForRemoteView(bigContentView, context, isLowRam);
        extras.putBoolean(EXTRA_REDUCED_IMAGES, true);
    }

    private void reduceImageSizesForRemoteView(RemoteViews remoteView, Context context,
            boolean isLowRam) {
        if (remoteView != null) {
            Resources resources = context.getResources();
            int maxWidth = resources.getDimensionPixelSize(isLowRam
                    ? R.dimen.notification_custom_view_max_image_width_low_ram
                    : R.dimen.notification_custom_view_max_image_width);
            int maxHeight = resources.getDimensionPixelSize(isLowRam
                    ? R.dimen.notification_custom_view_max_image_height_low_ram
                    : R.dimen.notification_custom_view_max_image_height);
            remoteView.reduceImageSizes(maxWidth, maxHeight);
        }
    }

    /**
     * @return whether this notification is a foreground service notification
     * @hide
     */
    public boolean isForegroundService() {
        return (flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
    }

    /**
     * @return whether this notification has a media session attached
     * @hide
     */
    public boolean hasMediaSession() {
        return extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) != null;
    }

    /**
     * @return the style class of this notification
     * @hide
     */
    public Class<? extends Notification.Style> getNotificationStyle() {
        String templateClass = extras.getString(Notification.EXTRA_TEMPLATE);

        if (!TextUtils.isEmpty(templateClass)) {
            return Notification.getNotificationStyleClass(templateClass);
        }
        return null;
    }

    /**
     * @return true if this notification is colorized.
     *
     * @hide
     */
    public boolean isColorized() {
        if (isColorizedMedia()) {
            return true;
        }
        return extras.getBoolean(EXTRA_COLORIZED)
                && (hasColorizedPermission() || isForegroundService());
    }

    /**
     * Returns whether an app can colorize due to the android.permission.USE_COLORIZED_NOTIFICATIONS
     * permission. The permission is checked when a notification is enqueued.
     */
    private boolean hasColorizedPermission() {
        return (flags & Notification.FLAG_CAN_COLORIZE) != 0;
    }

    /**
     * @return true if this notification is colorized and it is a media notification
     *
     * @hide
     */
    public boolean isColorizedMedia() {
        Class<? extends Style> style = getNotificationStyle();
        if (MediaStyle.class.equals(style)) {
            Boolean colorized = (Boolean) extras.get(EXTRA_COLORIZED);
            if ((colorized == null || colorized) && hasMediaSession()) {
                return true;
            }
        } else if (DecoratedMediaCustomViewStyle.class.equals(style)) {
            if (extras.getBoolean(EXTRA_COLORIZED) && hasMediaSession()) {
                return true;
            }
        }
        return false;
    }


    /**
     * @return true if this is a media notification
     *
     * @hide
     */
    public boolean isMediaNotification() {
        Class<? extends Style> style = getNotificationStyle();
        if (MediaStyle.class.equals(style)) {
            return true;
        } else if (DecoratedMediaCustomViewStyle.class.equals(style)) {
            return true;
        }
        return false;
    }

    private boolean hasLargeIcon() {
        return mLargeIcon != null || largeIcon != null;
    }

    /**
     * @return true if the notification will show the time; false otherwise
     * @hide
     */
    public boolean showsTime() {
        return when != 0 && extras.getBoolean(EXTRA_SHOW_WHEN);
    }

    /**
     * @return true if the notification will show a chronometer; false otherwise
     * @hide
     */
    public boolean showsChronometer() {
        return when != 0 && extras.getBoolean(EXTRA_SHOW_CHRONOMETER);
    }

    /**
     * @removed
     */
    @SystemApi
    public static Class<? extends Style> getNotificationStyleClass(String templateClass) {
        Class<? extends Style>[] classes = new Class[] {
                BigTextStyle.class, BigPictureStyle.class, InboxStyle.class, MediaStyle.class,
                DecoratedCustomViewStyle.class, DecoratedMediaCustomViewStyle.class,
                MessagingStyle.class };
        for (Class<? extends Style> innerClass : classes) {
            if (templateClass.equals(innerClass.getName())) {
                return innerClass;
            }
        }
        return null;
    }

    /**
     * An object that can apply a rich notification style to a {@link Notification.Builder}
     * object.
     */
    public static abstract class Style {

        /**
         * The number of items allowed simulatanously in the remote input history.
         * @hide
         */
        static final int MAX_REMOTE_INPUT_HISTORY_LINES = 3;
        private CharSequence mBigContentTitle;

        /**
         * @hide
         */
        protected CharSequence mSummaryText = null;

        /**
         * @hide
         */
        protected boolean mSummaryTextSet = false;

        protected Builder mBuilder;

        /**
         * Overrides ContentTitle in the big form of the template.
         * This defaults to the value passed to setContentTitle().
         */
        protected void internalSetBigContentTitle(CharSequence title) {
            mBigContentTitle = title;
        }

        /**
         * Set the first line of text after the detail section in the big form of the template.
         */
        protected void internalSetSummaryText(CharSequence cs) {
            mSummaryText = cs;
            mSummaryTextSet = true;
        }

        public void setBuilder(Builder builder) {
            if (mBuilder != builder) {
                mBuilder = builder;
                if (mBuilder != null) {
                    mBuilder.setStyle(this);
                }
            }
        }

        protected void checkBuilder() {
            if (mBuilder == null) {
                throw new IllegalArgumentException("Style requires a valid Builder object");
            }
        }

        protected RemoteViews getStandardView(int layoutId) {
            StandardTemplateParams p = mBuilder.mParams.reset().fillTextsFrom(mBuilder);
            return getStandardView(layoutId, p, null);
        }


        /**
         * Get the standard view for this style.
         *
         * @param layoutId The layout id to use.
         * @param p the params for this inflation.
         * @param result The result where template bind information is saved.
         * @return A remoteView for this style.
         * @hide
         */
        protected RemoteViews getStandardView(int layoutId, StandardTemplateParams p,
                TemplateBindResult result) {
            checkBuilder();

            if (mBigContentTitle != null) {
                p.title = mBigContentTitle;
            }

            RemoteViews contentView = mBuilder.applyStandardTemplateWithActions(layoutId, p,
                    result);

            if (mBigContentTitle != null && mBigContentTitle.equals("")) {
                contentView.setViewVisibility(R.id.line1, View.GONE);
            } else {
                contentView.setViewVisibility(R.id.line1, View.VISIBLE);
            }

            return contentView;
        }

        /**
         * Construct a Style-specific RemoteViews for the collapsed notification layout.
         * The default implementation has nothing additional to add.
         *
         * @param increasedHeight true if this layout be created with an increased height.
         * @hide
         */
        public RemoteViews makeContentView(boolean increasedHeight) {
            return null;
        }

        /**
         * Construct a Style-specific RemoteViews for the final big notification layout.
         * @hide
         */
        public RemoteViews makeBigContentView() {
            return null;
        }

        /**
         * Construct a Style-specific RemoteViews for the final HUN layout.
         *
         * @param increasedHeight true if this layout be created with an increased height.
         * @hide
         */
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            return null;
        }

        /**
         * Apply any style-specific extras to this notification before shipping it out.
         * @hide
         */
        public void addExtras(Bundle extras) {
            if (mSummaryTextSet) {
                extras.putCharSequence(EXTRA_SUMMARY_TEXT, mSummaryText);
            }
            if (mBigContentTitle != null) {
                extras.putCharSequence(EXTRA_TITLE_BIG, mBigContentTitle);
            }
            extras.putString(EXTRA_TEMPLATE, this.getClass().getName());
        }

        /**
         * Reconstruct the internal state of this Style object from extras.
         * @hide
         */
        protected void restoreFromExtras(Bundle extras) {
            if (extras.containsKey(EXTRA_SUMMARY_TEXT)) {
                mSummaryText = extras.getCharSequence(EXTRA_SUMMARY_TEXT);
                mSummaryTextSet = true;
            }
            if (extras.containsKey(EXTRA_TITLE_BIG)) {
                mBigContentTitle = extras.getCharSequence(EXTRA_TITLE_BIG);
            }
        }


        /**
         * @hide
         */
        public Notification buildStyled(Notification wip) {
            addExtras(wip.extras);
            return wip;
        }

        /**
         * @hide
         */
        public void purgeResources() {}

        /**
         * Calls {@link android.app.Notification.Builder#build()} on the Builder this Style is
         * attached to.
         *
         * @return the fully constructed Notification.
         */
        public Notification build() {
            checkBuilder();
            return mBuilder.build();
        }

        /**
         * @hide
         * @return true if the style positions the progress bar on the second line; false if the
         *         style hides the progress bar
         */
        protected boolean hasProgress() {
            return true;
        }

        /**
         * @hide
         * @return Whether we should put the summary be put into the notification header
         */
        public boolean hasSummaryInHeader() {
            return true;
        }

        /**
         * @hide
         * @return Whether custom content views are displayed inline in the style
         */
        public boolean displayCustomViewInline() {
            return false;
        }

        /**
         * Reduces the image sizes contained in this style.
         *
         * @hide
         */
        public void reduceImageSizes(Context context) {
        }

        /**
         * Validate that this style was properly composed. This is called at build time.
         * @hide
         */
        public void validate(Context context) {
        }

        /**
         * @hide
         */
        public abstract boolean areNotificationsVisiblyDifferent(Style other);

        /**
         * @return the text that should be displayed in the statusBar when heads-upped.
         * If {@code null} is returned, the default implementation will be used.
         *
         * @hide
         */
        public CharSequence getHeadsUpStatusBarText() {
            return null;
        }
    }

    /**
     * Helper class for generating large-format notifications that include a large image attachment.
     *
     * Here's how you'd set the <code>BigPictureStyle</code> on a notification:
     * <pre class="prettyprint">
     * Notification notif = new Notification.Builder(mContext)
     *     .setContentTitle(&quot;New photo from &quot; + sender.toString())
     *     .setContentText(subject)
     *     .setSmallIcon(R.drawable.new_post)
     *     .setLargeIcon(aBitmap)
     *     .setStyle(new Notification.BigPictureStyle()
     *         .bigPicture(aBigBitmap))
     *     .build();
     * </pre>
     *
     * @see Notification#bigContentView
     */
    public static class BigPictureStyle extends Style {
        private Bitmap mPicture;
        private Icon mBigLargeIcon;
        private boolean mBigLargeIconSet = false;

        public BigPictureStyle() {
        }

        /**
         * @deprecated use {@code BigPictureStyle()}.
         */
        @Deprecated
        public BigPictureStyle(Builder builder) {
            setBuilder(builder);
        }

        /**
         * Overrides ContentTitle in the big form of the template.
         * This defaults to the value passed to setContentTitle().
         */
        public BigPictureStyle setBigContentTitle(CharSequence title) {
            internalSetBigContentTitle(safeCharSequence(title));
            return this;
        }

        /**
         * Set the first line of text after the detail section in the big form of the template.
         */
        public BigPictureStyle setSummaryText(CharSequence cs) {
            internalSetSummaryText(safeCharSequence(cs));
            return this;
        }

        /**
         * @hide
         */
        public Bitmap getBigPicture() {
            return mPicture;
        }

        /**
         * Provide the bitmap to be used as the payload for the BigPicture notification.
         */
        public BigPictureStyle bigPicture(Bitmap b) {
            mPicture = b;
            return this;
        }

        /**
         * Override the large icon when the big notification is shown.
         */
        public BigPictureStyle bigLargeIcon(Bitmap b) {
            return bigLargeIcon(b != null ? Icon.createWithBitmap(b) : null);
        }

        /**
         * Override the large icon when the big notification is shown.
         */
        public BigPictureStyle bigLargeIcon(Icon icon) {
            mBigLargeIconSet = true;
            mBigLargeIcon = icon;
            return this;
        }

        /** @hide */
        public static final int MIN_ASHMEM_BITMAP_SIZE = 128 * (1 << 10);

        /**
         * @hide
         */
        @Override
        public void purgeResources() {
            super.purgeResources();
            if (mPicture != null &&
                mPicture.isMutable() &&
                mPicture.getAllocationByteCount() >= MIN_ASHMEM_BITMAP_SIZE) {
                mPicture = mPicture.createAshmemBitmap();
            }
            if (mBigLargeIcon != null) {
                mBigLargeIcon.convertToAshmem();
            }
        }

        /**
         * @hide
         */
        @Override
        public void reduceImageSizes(Context context) {
            super.reduceImageSizes(context);
            Resources resources = context.getResources();
            boolean isLowRam = ActivityManager.isLowRamDeviceStatic();
            if (mPicture != null) {
                int maxPictureWidth = resources.getDimensionPixelSize(isLowRam
                        ? R.dimen.notification_big_picture_max_height_low_ram
                        : R.dimen.notification_big_picture_max_height);
                int maxPictureHeight = resources.getDimensionPixelSize(isLowRam
                        ? R.dimen.notification_big_picture_max_width_low_ram
                        : R.dimen.notification_big_picture_max_width);
                mPicture = Icon.scaleDownIfNecessary(mPicture, maxPictureWidth, maxPictureHeight);
            }
            if (mBigLargeIcon != null) {
                int rightIconSize = resources.getDimensionPixelSize(isLowRam
                        ? R.dimen.notification_right_icon_size_low_ram
                        : R.dimen.notification_right_icon_size);
                mBigLargeIcon.scaleDownIfNecessary(rightIconSize, rightIconSize);
            }
        }

        /**
         * @hide
         */
        public RemoteViews makeBigContentView() {
            // Replace mN.mLargeIcon with mBigLargeIcon if mBigLargeIconSet
            // This covers the following cases:
            //   1. mBigLargeIconSet -> mBigLargeIcon (null or non-null) applies, overrides
            //          mN.mLargeIcon
            //   2. !mBigLargeIconSet -> mN.mLargeIcon applies
            Icon oldLargeIcon = null;
            Bitmap largeIconLegacy = null;
            if (mBigLargeIconSet) {
                oldLargeIcon = mBuilder.mN.mLargeIcon;
                mBuilder.mN.mLargeIcon = mBigLargeIcon;
                // The legacy largeIcon might not allow us to clear the image, as it's taken in
                // replacement if the other one is null. Because we're restoring these legacy icons
                // for old listeners, this is in general non-null.
                largeIconLegacy = mBuilder.mN.largeIcon;
                mBuilder.mN.largeIcon = null;
            }

            StandardTemplateParams p = mBuilder.mParams.reset().fillTextsFrom(mBuilder);
            RemoteViews contentView = getStandardView(mBuilder.getBigPictureLayoutResource(),
                    p, null /* result */);
            if (mSummaryTextSet) {
                contentView.setTextViewText(R.id.text, mBuilder.processTextSpans(
                        mBuilder.processLegacyText(mSummaryText)));
                mBuilder.setTextViewColorSecondary(contentView, R.id.text, p);
                contentView.setViewVisibility(R.id.text, View.VISIBLE);
            }
            mBuilder.setContentMinHeight(contentView, mBuilder.mN.hasLargeIcon());

            if (mBigLargeIconSet) {
                mBuilder.mN.mLargeIcon = oldLargeIcon;
                mBuilder.mN.largeIcon = largeIconLegacy;
            }

            contentView.setImageViewBitmap(R.id.big_picture, mPicture);
            return contentView;
        }

        /**
         * @hide
         */
        public void addExtras(Bundle extras) {
            super.addExtras(extras);

            if (mBigLargeIconSet) {
                extras.putParcelable(EXTRA_LARGE_ICON_BIG, mBigLargeIcon);
            }
            extras.putParcelable(EXTRA_PICTURE, mPicture);
        }

        /**
         * @hide
         */
        @Override
        protected void restoreFromExtras(Bundle extras) {
            super.restoreFromExtras(extras);

            if (extras.containsKey(EXTRA_LARGE_ICON_BIG)) {
                mBigLargeIconSet = true;
                mBigLargeIcon = extras.getParcelable(EXTRA_LARGE_ICON_BIG);
            }
            mPicture = extras.getParcelable(EXTRA_PICTURE);
        }

        /**
         * @hide
         */
        @Override
        public boolean hasSummaryInHeader() {
            return false;
        }

        /**
         * @hide
         * Note that we aren't actually comparing the contents of the bitmaps here, so this
         * is only doing a cursory inspection. Bitmaps of equal size will appear the same.
         */
        @Override
        public boolean areNotificationsVisiblyDifferent(Style other) {
            if (other == null || getClass() != other.getClass()) {
                return true;
            }
            BigPictureStyle otherS = (BigPictureStyle) other;
            return areBitmapsObviouslyDifferent(getBigPicture(), otherS.getBigPicture());
        }

        private static boolean areBitmapsObviouslyDifferent(Bitmap a, Bitmap b) {
            if (a == b) {
                return false;
            }
            if (a == null || b == null) {
                return true;
            }
            return a.getWidth() != b.getWidth()
                    || a.getHeight() != b.getHeight()
                    || a.getConfig() != b.getConfig()
                    || a.getGenerationId() != b.getGenerationId();
        }
    }

    /**
     * Helper class for generating large-format notifications that include a lot of text.
     *
     * Here's how you'd set the <code>BigTextStyle</code> on a notification:
     * <pre class="prettyprint">
     * Notification notif = new Notification.Builder(mContext)
     *     .setContentTitle(&quot;New mail from &quot; + sender.toString())
     *     .setContentText(subject)
     *     .setSmallIcon(R.drawable.new_mail)
     *     .setLargeIcon(aBitmap)
     *     .setStyle(new Notification.BigTextStyle()
     *         .bigText(aVeryLongString))
     *     .build();
     * </pre>
     *
     * @see Notification#bigContentView
     */
    public static class BigTextStyle extends Style {

        private CharSequence mBigText;

        public BigTextStyle() {
        }

        /**
         * @deprecated use {@code BigTextStyle()}.
         */
        @Deprecated
        public BigTextStyle(Builder builder) {
            setBuilder(builder);
        }

        /**
         * Overrides ContentTitle in the big form of the template.
         * This defaults to the value passed to setContentTitle().
         */
        public BigTextStyle setBigContentTitle(CharSequence title) {
            internalSetBigContentTitle(safeCharSequence(title));
            return this;
        }

        /**
         * Set the first line of text after the detail section in the big form of the template.
         */
        public BigTextStyle setSummaryText(CharSequence cs) {
            internalSetSummaryText(safeCharSequence(cs));
            return this;
        }

        /**
         * Provide the longer text to be displayed in the big form of the
         * template in place of the content text.
         */
        public BigTextStyle bigText(CharSequence cs) {
            mBigText = safeCharSequence(cs);
            return this;
        }

        /**
         * @hide
         */
        public CharSequence getBigText() {
            return mBigText;
        }

        /**
         * @hide
         */
        public void addExtras(Bundle extras) {
            super.addExtras(extras);

            extras.putCharSequence(EXTRA_BIG_TEXT, mBigText);
        }

        /**
         * @hide
         */
        @Override
        protected void restoreFromExtras(Bundle extras) {
            super.restoreFromExtras(extras);

            mBigText = extras.getCharSequence(EXTRA_BIG_TEXT);
        }

        /**
         * @param increasedHeight true if this layout be created with an increased height.
         *
         * @hide
         */
        @Override
        public RemoteViews makeContentView(boolean increasedHeight) {
            if (increasedHeight) {
                mBuilder.mOriginalActions = mBuilder.mActions;
                mBuilder.mActions = new ArrayList<>();
                RemoteViews remoteViews = makeBigContentView();
                mBuilder.mActions = mBuilder.mOriginalActions;
                mBuilder.mOriginalActions = null;
                return remoteViews;
            }
            return super.makeContentView(increasedHeight);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            if (increasedHeight && mBuilder.mActions.size() > 0) {
                return makeBigContentView();
            }
            return super.makeHeadsUpContentView(increasedHeight);
        }

        /**
         * @hide
         */
        public RemoteViews makeBigContentView() {
            StandardTemplateParams p = mBuilder.mParams.reset().fillTextsFrom(mBuilder).text(null);
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews contentView = getStandardView(mBuilder.getBigTextLayoutResource(), p,
                    result);
            contentView.setInt(R.id.big_text, "setImageEndMargin", result.getIconMarginEnd());

            CharSequence bigTextText = mBuilder.processLegacyText(mBigText);
            if (TextUtils.isEmpty(bigTextText)) {
                // In case the bigtext is null / empty fall back to the normal text to avoid a weird
                // experience
                bigTextText = mBuilder.processLegacyText(
                        mBuilder.getAllExtras().getCharSequence(EXTRA_TEXT));
            }
            contentView.setTextViewText(R.id.big_text, mBuilder.processTextSpans(bigTextText));
            mBuilder.setTextViewColorSecondary(contentView, R.id.big_text, p);
            contentView.setViewVisibility(R.id.big_text,
                    TextUtils.isEmpty(bigTextText) ? View.GONE : View.VISIBLE);
            contentView.setBoolean(R.id.big_text, "setHasImage",
                    result.isRightIconContainerVisible());

            return contentView;
        }

        /**
         * @hide
         * Spans are ignored when comparing text for visual difference.
         */
        @Override
        public boolean areNotificationsVisiblyDifferent(Style other) {
            if (other == null || getClass() != other.getClass()) {
                return true;
            }
            BigTextStyle newS = (BigTextStyle) other;
            return !Objects.equals(String.valueOf(getBigText()), String.valueOf(newS.getBigText()));
        }

    }

    /**
     * Helper class for generating large-format notifications that include multiple back-and-forth
     * messages of varying types between any number of people.
     *
     * <p>
     * If the platform does not provide large-format notifications, this method has no effect. The
     * user will always see the normal notification view.
     *
     * <p>
     * If the app is targeting Android P and above, it is required to use the {@link Person}
     * class in order to get an optimal rendering of the notification and its avatars. For
     * conversations involving multiple people, the app should also make sure that it marks the
     * conversation as a group with {@link #setGroupConversation(boolean)}.
     *
     * <p>
     * This class is a "rebuilder": It attaches to a Builder object and modifies its behavior.
     * Here's an example of how this may be used:
     * <pre class="prettyprint">
     *
     * Person user = new Person.Builder().setIcon(userIcon).setName(userName).build();
     * MessagingStyle style = new MessagingStyle(user)
     *      .addMessage(messages[1].getText(), messages[1].getTime(), messages[1].getPerson())
     *      .addMessage(messages[2].getText(), messages[2].getTime(), messages[2].getPerson())
     *      .setGroupConversation(hasMultiplePeople());
     *
     * Notification noti = new Notification.Builder()
     *     .setContentTitle(&quot;2 new messages with &quot; + sender.toString())
     *     .setContentText(subject)
     *     .setSmallIcon(R.drawable.new_message)
     *     .setLargeIcon(aBitmap)
     *     .setStyle(style)
     *     .build();
     * </pre>
     */
    public static class MessagingStyle extends Style {

        /**
         * The maximum number of messages that will be retained in the Notification itself (the
         * number displayed is up to the platform).
         */
        public static final int MAXIMUM_RETAINED_MESSAGES = 25;

        @NonNull Person mUser;
        @Nullable CharSequence mConversationTitle;
        List<Message> mMessages = new ArrayList<>();
        List<Message> mHistoricMessages = new ArrayList<>();
        boolean mIsGroupConversation;

        MessagingStyle() {
        }

        /**
         * @param userDisplayName Required - the name to be displayed for any replies sent by the
         * user before the posting app reposts the notification with those messages after they've
         * been actually sent and in previous messages sent by the user added in
         * {@link #addMessage(Notification.MessagingStyle.Message)}
         *
         * @deprecated use {@code MessagingStyle(Person)}
         */
        public MessagingStyle(@NonNull CharSequence userDisplayName) {
            this(new Person.Builder().setName(userDisplayName).build());
        }

        /**
         * @param user Required - The person displayed for any messages that are sent by the
         * user. Any messages added with {@link #addMessage(Notification.MessagingStyle.Message)}
         * who don't have a Person associated with it will be displayed as if they were sent
         * by this user. The user also needs to have a valid name associated with it, which will
         * be enforced starting in Android P.
         */
        public MessagingStyle(@NonNull Person user) {
            mUser = user;
        }

        /**
         * Validate that this style was properly composed. This is called at build time.
         * @hide
         */
        @Override
        public void validate(Context context) {
            super.validate(context);
            if (context.getApplicationInfo().targetSdkVersion
                    >= Build.VERSION_CODES.P && (mUser == null || mUser.getName() == null)) {
                throw new RuntimeException("User must be valid and have a name.");
            }
        }

        /**
         * @return the text that should be displayed in the statusBar when heads upped.
         * If {@code null} is returned, the default implementation will be used.
         *
         * @hide
         */
        @Override
        public CharSequence getHeadsUpStatusBarText() {
            CharSequence conversationTitle = !TextUtils.isEmpty(super.mBigContentTitle)
                    ? super.mBigContentTitle
                    : mConversationTitle;
            if (!TextUtils.isEmpty(conversationTitle) && !hasOnlyWhiteSpaceSenders()) {
                return conversationTitle;
            }
            return null;
        }

        /**
         * @return the user to be displayed for any replies sent by the user
         */
        @NonNull
        public Person getUser() {
            return mUser;
        }

        /**
         * Returns the name to be displayed for any replies sent by the user
         *
         * @deprecated use {@link #getUser()} instead
         */
        public CharSequence getUserDisplayName() {
            return mUser.getName();
        }

        /**
         * Sets the title to be displayed on this conversation. May be set to {@code null}.
         *
         * <p>This API's behavior was changed in SDK version {@link Build.VERSION_CODES#P}. If your
         * application's target version is less than {@link Build.VERSION_CODES#P}, setting a
         * conversation title to a non-null value will make {@link #isGroupConversation()} return
         * {@code true} and passing {@code null} will make it return {@code false}. In
         * {@link Build.VERSION_CODES#P} and beyond, use {@link #setGroupConversation(boolean)}
         * to set group conversation status.
         *
         * @param conversationTitle Title displayed for this conversation
         * @return this object for method chaining
         */
        public MessagingStyle setConversationTitle(@Nullable CharSequence conversationTitle) {
            mConversationTitle = conversationTitle;
            return this;
        }

        /**
         * Return the title to be displayed on this conversation. May return {@code null}.
         */
        @Nullable
        public CharSequence getConversationTitle() {
            return mConversationTitle;
        }

        /**
         * Adds a message for display by this notification. Convenience call for a simple
         * {@link Message} in {@link #addMessage(Notification.MessagingStyle.Message)}.
         * @param text A {@link CharSequence} to be displayed as the message content
         * @param timestamp Time at which the message arrived
         * @param sender A {@link CharSequence} to be used for displaying the name of the
         * sender. Should be <code>null</code> for messages by the current user, in which case
         * the platform will insert {@link #getUserDisplayName()}.
         * Should be unique amongst all individuals in the conversation, and should be
         * consistent during re-posts of the notification.
         *
         * @see Message#Notification.MessagingStyle.Message(CharSequence, long, CharSequence)
         *
         * @return this object for method chaining
         *
         * @deprecated use {@link #addMessage(CharSequence, long, Person)}
         */
        public MessagingStyle addMessage(CharSequence text, long timestamp, CharSequence sender) {
            return addMessage(text, timestamp,
                    sender == null ? null : new Person.Builder().setName(sender).build());
        }

        /**
         * Adds a message for display by this notification. Convenience call for a simple
         * {@link Message} in {@link #addMessage(Notification.MessagingStyle.Message)}.
         * @param text A {@link CharSequence} to be displayed as the message content
         * @param timestamp Time at which the message arrived
         * @param sender The {@link Person} who sent the message.
         * Should be <code>null</code> for messages by the current user, in which case
         * the platform will insert the user set in {@code MessagingStyle(Person)}.
         *
         * @see Message#Notification.MessagingStyle.Message(CharSequence, long, CharSequence)
         *
         * @return this object for method chaining
         */
        public MessagingStyle addMessage(@NonNull CharSequence text, long timestamp,
                @Nullable Person sender) {
            return addMessage(new Message(text, timestamp, sender));
        }

        /**
         * Adds a {@link Message} for display in this notification.
         *
         * <p>The messages should be added in chronologic order, i.e. the oldest first,
         * the newest last.
         *
         * @param message The {@link Message} to be displayed
         * @return this object for method chaining
         */
        public MessagingStyle addMessage(Message message) {
            mMessages.add(message);
            if (mMessages.size() > MAXIMUM_RETAINED_MESSAGES) {
                mMessages.remove(0);
            }
            return this;
        }

        /**
         * Adds a {@link Message} for historic context in this notification.
         *
         * <p>Messages should be added as historic if they are not the main subject of the
         * notification but may give context to a conversation. The system may choose to present
         * them only when relevant, e.g. when replying to a message through a {@link RemoteInput}.
         *
         * <p>The messages should be added in chronologic order, i.e. the oldest first,
         * the newest last.
         *
         * @param message The historic {@link Message} to be added
         * @return this object for method chaining
         */
        public MessagingStyle addHistoricMessage(Message message) {
            mHistoricMessages.add(message);
            if (mHistoricMessages.size() > MAXIMUM_RETAINED_MESSAGES) {
                mHistoricMessages.remove(0);
            }
            return this;
        }

        /**
         * Gets the list of {@code Message} objects that represent the notification
         */
        public List<Message> getMessages() {
            return mMessages;
        }

        /**
         * Gets the list of historic {@code Message}s in the notification.
         */
        public List<Message> getHistoricMessages() {
            return mHistoricMessages;
        }

        /**
         * Sets whether this conversation notification represents a group. If the app is targeting
         * Android P, this is required if the app wants to display the largeIcon set with
         * {@link Notification.Builder#setLargeIcon(Bitmap)}, otherwise it will be hidden.
         *
         * @param isGroupConversation {@code true} if the conversation represents a group,
         * {@code false} otherwise.
         * @return this object for method chaining
         */
        public MessagingStyle setGroupConversation(boolean isGroupConversation) {
            mIsGroupConversation = isGroupConversation;
            return this;
        }

        /**
         * Returns {@code true} if this notification represents a group conversation, otherwise
         * {@code false}.
         *
         * <p> If the application that generated this {@link MessagingStyle} targets an SDK version
         * less than {@link Build.VERSION_CODES#P}, this method becomes dependent on whether or
         * not the conversation title is set; returning {@code true} if the conversation title is
         * a non-null value, or {@code false} otherwise. From {@link Build.VERSION_CODES#P} forward,
         * this method returns what's set by {@link #setGroupConversation(boolean)} allowing for
         * named, non-group conversations.
         *
         * @see #setConversationTitle(CharSequence)
         */
        public boolean isGroupConversation() {
            // When target SDK version is < P, a non-null conversation title dictates if this is
            // as group conversation.
            if (mBuilder != null
                    && mBuilder.mContext.getApplicationInfo().targetSdkVersion
                            < Build.VERSION_CODES.P) {
                return mConversationTitle != null;
            }

            return mIsGroupConversation;
        }

        /**
         * @hide
         */
        @Override
        public void addExtras(Bundle extras) {
            super.addExtras(extras);
            if (mUser != null) {
                // For legacy usages
                extras.putCharSequence(EXTRA_SELF_DISPLAY_NAME, mUser.getName());
                extras.putParcelable(EXTRA_MESSAGING_PERSON, mUser);
            }
            if (mConversationTitle != null) {
                extras.putCharSequence(EXTRA_CONVERSATION_TITLE, mConversationTitle);
            }
            if (!mMessages.isEmpty()) { extras.putParcelableArray(EXTRA_MESSAGES,
                    Message.getBundleArrayForMessages(mMessages));
            }
            if (!mHistoricMessages.isEmpty()) { extras.putParcelableArray(EXTRA_HISTORIC_MESSAGES,
                    Message.getBundleArrayForMessages(mHistoricMessages));
            }

            fixTitleAndTextExtras(extras);
            extras.putBoolean(EXTRA_IS_GROUP_CONVERSATION, mIsGroupConversation);
        }

        private void fixTitleAndTextExtras(Bundle extras) {
            Message m = findLatestIncomingMessage();
            CharSequence text = (m == null) ? null : m.mText;
            CharSequence sender = m == null ? null
                    : m.mSender == null || TextUtils.isEmpty(m.mSender.getName())
                            ? mUser.getName() : m.mSender.getName();
            CharSequence title;
            if (!TextUtils.isEmpty(mConversationTitle)) {
                if (!TextUtils.isEmpty(sender)) {
                    BidiFormatter bidi = BidiFormatter.getInstance();
                    title = mBuilder.mContext.getString(
                            com.android.internal.R.string.notification_messaging_title_template,
                            bidi.unicodeWrap(mConversationTitle), bidi.unicodeWrap(sender));
                } else {
                    title = mConversationTitle;
                }
            } else {
                title = sender;
            }

            if (title != null) {
                extras.putCharSequence(EXTRA_TITLE, title);
            }
            if (text != null) {
                extras.putCharSequence(EXTRA_TEXT, text);
            }
        }

        /**
         * @hide
         */
        @Override
        protected void restoreFromExtras(Bundle extras) {
            super.restoreFromExtras(extras);

            mUser = extras.getParcelable(EXTRA_MESSAGING_PERSON);
            if (mUser == null) {
                CharSequence displayName = extras.getCharSequence(EXTRA_SELF_DISPLAY_NAME);
                mUser = new Person.Builder().setName(displayName).build();
            }
            mConversationTitle = extras.getCharSequence(EXTRA_CONVERSATION_TITLE);
            Parcelable[] messages = extras.getParcelableArray(EXTRA_MESSAGES);
            mMessages = Message.getMessagesFromBundleArray(messages);
            Parcelable[] histMessages = extras.getParcelableArray(EXTRA_HISTORIC_MESSAGES);
            mHistoricMessages = Message.getMessagesFromBundleArray(histMessages);
            mIsGroupConversation = extras.getBoolean(EXTRA_IS_GROUP_CONVERSATION);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeContentView(boolean increasedHeight) {
            mBuilder.mOriginalActions = mBuilder.mActions;
            mBuilder.mActions = new ArrayList<>();
            RemoteViews remoteViews = makeMessagingView(true /* displayImagesAtEnd */,
                    false /* hideLargeIcon */);
            mBuilder.mActions = mBuilder.mOriginalActions;
            mBuilder.mOriginalActions = null;
            return remoteViews;
        }

        /**
         * @hide
         * Spans are ignored when comparing text for visual difference.
         */
        @Override
        public boolean areNotificationsVisiblyDifferent(Style other) {
            if (other == null || getClass() != other.getClass()) {
                return true;
            }
            MessagingStyle newS = (MessagingStyle) other;
            List<MessagingStyle.Message> oldMs = getMessages();
            List<MessagingStyle.Message> newMs = newS.getMessages();

            if (oldMs == null || newMs == null) {
                newMs = new ArrayList<>();
            }

            int n = oldMs.size();
            if (n != newMs.size()) {
                return true;
            }
            for (int i = 0; i < n; i++) {
                MessagingStyle.Message oldM = oldMs.get(i);
                MessagingStyle.Message newM = newMs.get(i);
                if (!Objects.equals(
                        String.valueOf(oldM.getText()),
                        String.valueOf(newM.getText()))) {
                    return true;
                }
                if (!Objects.equals(oldM.getDataUri(), newM.getDataUri())) {
                    return true;
                }
                String oldSender = String.valueOf(oldM.getSenderPerson() == null
                        ? oldM.getSender()
                        : oldM.getSenderPerson().getName());
                String newSender = String.valueOf(newM.getSenderPerson() == null
                        ? newM.getSender()
                        : newM.getSenderPerson().getName());
                if (!Objects.equals(oldSender, newSender)) {
                    return true;
                }

                String oldKey = oldM.getSenderPerson() == null
                        ? null : oldM.getSenderPerson().getKey();
                String newKey = newM.getSenderPerson() == null
                        ? null : newM.getSenderPerson().getKey();
                if (!Objects.equals(oldKey, newKey)) {
                    return true;
                }
                // Other fields (like timestamp) intentionally excluded
            }
            return false;
        }

        private Message findLatestIncomingMessage() {
            return findLatestIncomingMessage(mMessages);
        }

        /**
         * @hide
         */
        @Nullable
        public static Message findLatestIncomingMessage(
                List<Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                // Incoming messages have a non-empty sender.
                if (m.mSender != null && !TextUtils.isEmpty(m.mSender.getName())) {
                    return m;
                }
            }
            if (!messages.isEmpty()) {
                // No incoming messages, fall back to outgoing message
                return messages.get(messages.size() - 1);
            }
            return null;
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeBigContentView() {
            return makeMessagingView(false /* displayImagesAtEnd */, true /* hideLargeIcon */);
        }

        /**
         * Create a messaging layout.
         *
         * @param displayImagesAtEnd should images be displayed at the end of the content instead
         *                           of inline.
         * @param hideRightIcons Should the reply affordance be shown at the end of the notification
         * @return the created remoteView.
         */
        @NonNull
        private RemoteViews makeMessagingView(boolean displayImagesAtEnd, boolean hideRightIcons) {
            CharSequence conversationTitle = !TextUtils.isEmpty(super.mBigContentTitle)
                    ? super.mBigContentTitle
                    : mConversationTitle;
            boolean atLeastP = mBuilder.mContext.getApplicationInfo().targetSdkVersion
                    >= Build.VERSION_CODES.P;
            boolean isOneToOne;
            CharSequence nameReplacement = null;
            Icon avatarReplacement = null;
            if (!atLeastP) {
                isOneToOne = TextUtils.isEmpty(conversationTitle);
                avatarReplacement = mBuilder.mN.mLargeIcon;
                if (hasOnlyWhiteSpaceSenders()) {
                    isOneToOne = true;
                    nameReplacement = conversationTitle;
                    conversationTitle = null;
                }
            } else {
                isOneToOne = !isGroupConversation();
            }
            TemplateBindResult bindResult = new TemplateBindResult();
            StandardTemplateParams p = mBuilder.mParams.reset().hasProgress(false).title(
                    conversationTitle).text(null)
                    .hideLargeIcon(hideRightIcons || isOneToOne)
                    .hideReplyIcon(hideRightIcons)
                    .headerTextSecondary(conversationTitle);
            RemoteViews contentView = mBuilder.applyStandardTemplateWithActions(
                    mBuilder.getMessagingLayoutResource(),
                    p,
                    bindResult);
            addExtras(mBuilder.mN.extras);
            // also update the end margin if there is an image
            contentView.setViewLayoutMarginEnd(R.id.notification_messaging,
                    bindResult.getIconMarginEnd());
            contentView.setInt(R.id.status_bar_latest_event_content, "setLayoutColor",
                    mBuilder.isColorized(p) ? mBuilder.getPrimaryTextColor(p)
                            : mBuilder.resolveContrastColor(p));
            contentView.setInt(R.id.status_bar_latest_event_content, "setSenderTextColor",
                    mBuilder.getPrimaryTextColor(p));
            contentView.setInt(R.id.status_bar_latest_event_content, "setMessageTextColor",
                    mBuilder.getSecondaryTextColor(p));
            contentView.setBoolean(R.id.status_bar_latest_event_content, "setDisplayImagesAtEnd",
                    displayImagesAtEnd);
            contentView.setIcon(R.id.status_bar_latest_event_content, "setAvatarReplacement",
                    avatarReplacement);
            contentView.setCharSequence(R.id.status_bar_latest_event_content, "setNameReplacement",
                    nameReplacement);
            contentView.setBoolean(R.id.status_bar_latest_event_content, "setIsOneToOne",
                    isOneToOne);
            contentView.setBundle(R.id.status_bar_latest_event_content, "setData",
                    mBuilder.mN.extras);
            return contentView;
        }

        private boolean hasOnlyWhiteSpaceSenders() {
            for (int i = 0; i < mMessages.size(); i++) {
                Message m = mMessages.get(i);
                Person sender = m.getSenderPerson();
                if (sender != null && !isWhiteSpace(sender.getName())) {
                    return false;
                }
            }
            return true;
        }

        private boolean isWhiteSpace(CharSequence sender) {
            if (TextUtils.isEmpty(sender)) {
                return true;
            }
            if (sender.toString().matches("^\\s*$")) {
                return true;
            }
            // Let's check if we only have 0 whitespace chars. Some apps did this as a workaround
            // For the presentation that we had.
            for (int i = 0; i < sender.length(); i++) {
                char c = sender.charAt(i);
                if (c != '\u200B') {
                    return false;
                }
            }
            return true;
        }

        private CharSequence createConversationTitleFromMessages() {
            ArraySet<CharSequence> names = new ArraySet<>();
            for (int i = 0; i < mMessages.size(); i++) {
                Message m = mMessages.get(i);
                Person sender = m.getSenderPerson();
                if (sender != null) {
                    names.add(sender.getName());
                }
            }
            SpannableStringBuilder title = new SpannableStringBuilder();
            int size = names.size();
            for (int i = 0; i < size; i++) {
                CharSequence name = names.valueAt(i);
                if (!TextUtils.isEmpty(title)) {
                    title.append(", ");
                }
                title.append(BidiFormatter.getInstance().unicodeWrap(name));
            }
            return title;
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            RemoteViews remoteViews = makeMessagingView(true /* displayImagesAtEnd */,
                    true /* hideLargeIcon */);
            remoteViews.setInt(R.id.notification_messaging, "setMaxDisplayedLines", 1);
            return remoteViews;
        }

        private static TextAppearanceSpan makeFontColorSpan(int color) {
            return new TextAppearanceSpan(null, 0, 0,
                    ColorStateList.valueOf(color), null);
        }

        public static final class Message {
            /** @hide */
            public static final String KEY_TEXT = "text";
            static final String KEY_TIMESTAMP = "time";
            static final String KEY_SENDER = "sender";
            static final String KEY_SENDER_PERSON = "sender_person";
            static final String KEY_DATA_MIME_TYPE = "type";
            static final String KEY_DATA_URI= "uri";
            static final String KEY_EXTRAS_BUNDLE = "extras";
            static final String KEY_REMOTE_INPUT_HISTORY = "remote_input_history";

            private final CharSequence mText;
            private final long mTimestamp;
            @Nullable
            private final Person mSender;
            /** True if this message was generated from the extra
             *  {@link Notification#EXTRA_REMOTE_INPUT_HISTORY}
             */
            private final boolean mRemoteInputHistory;

            private Bundle mExtras = new Bundle();
            private String mDataMimeType;
            private Uri mDataUri;

            /**
             * Constructor
             * @param text A {@link CharSequence} to be displayed as the message content
             * @param timestamp Time at which the message arrived
             * @param sender A {@link CharSequence} to be used for displaying the name of the
             * sender. Should be <code>null</code> for messages by the current user, in which case
             * the platform will insert {@link MessagingStyle#getUserDisplayName()}.
             * Should be unique amongst all individuals in the conversation, and should be
             * consistent during re-posts of the notification.
             *
             *  @deprecated use {@code Message(CharSequence, long, Person)}
             */
            public Message(CharSequence text, long timestamp, CharSequence sender){
                this(text, timestamp, sender == null ? null
                        : new Person.Builder().setName(sender).build());
            }

            /**
             * Constructor
             * @param text A {@link CharSequence} to be displayed as the message content
             * @param timestamp Time at which the message arrived
             * @param sender The {@link Person} who sent the message.
             * Should be <code>null</code> for messages by the current user, in which case
             * the platform will insert the user set in {@code MessagingStyle(Person)}.
             * <p>
             * The person provided should contain an Icon, set with
             * {@link Person.Builder#setIcon(Icon)} and also have a name provided
             * with {@link Person.Builder#setName(CharSequence)}. If multiple users have the same
             * name, consider providing a key with {@link Person.Builder#setKey(String)} in order
             * to differentiate between the different users.
             * </p>
             */
            public Message(@NonNull CharSequence text, long timestamp, @Nullable Person sender) {
                this(text, timestamp, sender, false /* remoteHistory */);
            }

            /**
             * Constructor
             * @param text A {@link CharSequence} to be displayed as the message content
             * @param timestamp Time at which the message arrived
             * @param sender The {@link Person} who sent the message.
             * Should be <code>null</code> for messages by the current user, in which case
             * the platform will insert the user set in {@code MessagingStyle(Person)}.
             * @param remoteInputHistory True if the messages was generated from the extra
             * {@link Notification#EXTRA_REMOTE_INPUT_HISTORY}.
             * <p>
             * The person provided should contain an Icon, set with
             * {@link Person.Builder#setIcon(Icon)} and also have a name provided
             * with {@link Person.Builder#setName(CharSequence)}. If multiple users have the same
             * name, consider providing a key with {@link Person.Builder#setKey(String)} in order
             * to differentiate between the different users.
             * </p>
             * @hide
             */
            public Message(@NonNull CharSequence text, long timestamp, @Nullable Person sender,
                    boolean remoteInputHistory) {
                mText = text;
                mTimestamp = timestamp;
                mSender = sender;
                mRemoteInputHistory = remoteInputHistory;
            }

            /**
             * Sets a binary blob of data and an associated MIME type for a message. In the case
             * where the platform doesn't support the MIME type, the original text provided in the
             * constructor will be used.
             * @param dataMimeType The MIME type of the content. See
             * <a href="{@docRoot}notifications/messaging.html"> for the list of supported MIME
             * types on Android and Android Wear.
             * @param dataUri The uri containing the content whose type is given by the MIME type.
             * <p class="note">
             * <ol>
             *   <li>Notification Listeners including the System UI need permission to access the
             *       data the Uri points to. The recommended ways to do this are:</li>
             *   <li>Store the data in your own ContentProvider, making sure that other apps have
             *       the correct permission to access your provider. The preferred mechanism for
             *       providing access is to use per-URI permissions which are temporary and only
             *       grant access to the receiving application. An easy way to create a
             *       ContentProvider like this is to use the FileProvider helper class.</li>
             *   <li>Use the system MediaStore. The MediaStore is primarily aimed at video, audio
             *       and image MIME types, however beginning with Android 3.0 (API level 11) it can
             *       also store non-media types (see MediaStore.Files for more info). Files can be
             *       inserted into the MediaStore using scanFile() after which a content:// style
             *       Uri suitable for sharing is passed to the provided onScanCompleted() callback.
             *       Note that once added to the system MediaStore the content is accessible to any
             *       app on the device.</li>
             * </ol>
             * @return this object for method chaining
             */
            public Message setData(String dataMimeType, Uri dataUri) {
                mDataMimeType = dataMimeType;
                mDataUri = dataUri;
                return this;
            }

            /**
             * Get the text to be used for this message, or the fallback text if a type and content
             * Uri have been set
             */
            public CharSequence getText() {
                return mText;
            }

            /**
             * Get the time at which this message arrived
             */
            public long getTimestamp() {
                return mTimestamp;
            }

            /**
             * Get the extras Bundle for this message.
             */
            public Bundle getExtras() {
                return mExtras;
            }

            /**
             * Get the text used to display the contact's name in the messaging experience
             *
             * @deprecated use {@link #getSenderPerson()}
             */
            public CharSequence getSender() {
                return mSender == null ? null : mSender.getName();
            }

            /**
             * Get the sender associated with this message.
             */
            @Nullable
            public Person getSenderPerson() {
                return mSender;
            }

            /**
             * Get the MIME type of the data pointed to by the Uri
             */
            public String getDataMimeType() {
                return mDataMimeType;
            }

            /**
             * Get the Uri pointing to the content of the message. Can be null, in which case
             * {@see #getText()} is used.
             */
            public Uri getDataUri() {
                return mDataUri;
            }

            /**
             * @return True if the message was generated from
             * {@link Notification#EXTRA_REMOTE_INPUT_HISTORY}.
             * @hide
             */
            public boolean isRemoteInputHistory() {
                return mRemoteInputHistory;
            }

            /**
             * @hide
             */
            @VisibleForTesting
            public Bundle toBundle() {
                Bundle bundle = new Bundle();
                if (mText != null) {
                    bundle.putCharSequence(KEY_TEXT, mText);
                }
                bundle.putLong(KEY_TIMESTAMP, mTimestamp);
                if (mSender != null) {
                    // Legacy listeners need this
                    bundle.putCharSequence(KEY_SENDER, mSender.getName());
                    bundle.putParcelable(KEY_SENDER_PERSON, mSender);
                }
                if (mDataMimeType != null) {
                    bundle.putString(KEY_DATA_MIME_TYPE, mDataMimeType);
                }
                if (mDataUri != null) {
                    bundle.putParcelable(KEY_DATA_URI, mDataUri);
                }
                if (mExtras != null) {
                    bundle.putBundle(KEY_EXTRAS_BUNDLE, mExtras);
                }
                if (mRemoteInputHistory) {
                    bundle.putBoolean(KEY_REMOTE_INPUT_HISTORY, mRemoteInputHistory);
                }
                return bundle;
            }

            static Bundle[] getBundleArrayForMessages(List<Message> messages) {
                Bundle[] bundles = new Bundle[messages.size()];
                final int N = messages.size();
                for (int i = 0; i < N; i++) {
                    bundles[i] = messages.get(i).toBundle();
                }
                return bundles;
            }

            /**
             * @return A list of messages read from the bundles.
             *
             * @hide
             */
            public static List<Message> getMessagesFromBundleArray(Parcelable[] bundles) {
                if (bundles == null) {
                    return new ArrayList<>();
                }
                List<Message> messages = new ArrayList<>(bundles.length);
                for (int i = 0; i < bundles.length; i++) {
                    if (bundles[i] instanceof Bundle) {
                        Message message = getMessageFromBundle((Bundle)bundles[i]);
                        if (message != null) {
                            messages.add(message);
                        }
                    }
                }
                return messages;
            }

            /**
             * @return The message that is stored in the bundle or null if the message couldn't be
             * resolved.
             *
             * @hide
             */
            @Nullable
            public static Message getMessageFromBundle(Bundle bundle) {
                try {
                    if (!bundle.containsKey(KEY_TEXT) || !bundle.containsKey(KEY_TIMESTAMP)) {
                        return null;
                    } else {

                        Person senderPerson = bundle.getParcelable(KEY_SENDER_PERSON);
                        if (senderPerson == null) {
                            // Legacy apps that use compat don't actually provide the sender objects
                            // We need to fix the compat version to provide people / use
                            // the native api instead
                            CharSequence senderName = bundle.getCharSequence(KEY_SENDER);
                            if (senderName != null) {
                                senderPerson = new Person.Builder().setName(senderName).build();
                            }
                        }
                        Message message = new Message(bundle.getCharSequence(KEY_TEXT),
                                bundle.getLong(KEY_TIMESTAMP),
                                senderPerson,
                                bundle.getBoolean(KEY_REMOTE_INPUT_HISTORY, false));
                        if (bundle.containsKey(KEY_DATA_MIME_TYPE) &&
                                bundle.containsKey(KEY_DATA_URI)) {
                            message.setData(bundle.getString(KEY_DATA_MIME_TYPE),
                                    (Uri) bundle.getParcelable(KEY_DATA_URI));
                        }
                        if (bundle.containsKey(KEY_EXTRAS_BUNDLE)) {
                            message.getExtras().putAll(bundle.getBundle(KEY_EXTRAS_BUNDLE));
                        }
                        return message;
                    }
                } catch (ClassCastException e) {
                    return null;
                }
            }
        }
    }

    /**
     * Helper class for generating large-format notifications that include a list of (up to 5) strings.
     *
     * Here's how you'd set the <code>InboxStyle</code> on a notification:
     * <pre class="prettyprint">
     * Notification notif = new Notification.Builder(mContext)
     *     .setContentTitle(&quot;5 New mails from &quot; + sender.toString())
     *     .setContentText(subject)
     *     .setSmallIcon(R.drawable.new_mail)
     *     .setLargeIcon(aBitmap)
     *     .setStyle(new Notification.InboxStyle()
     *         .addLine(str1)
     *         .addLine(str2)
     *         .setContentTitle(&quot;&quot;)
     *         .setSummaryText(&quot;+3 more&quot;))
     *     .build();
     * </pre>
     *
     * @see Notification#bigContentView
     */
    public static class InboxStyle extends Style {

        /**
         * The number of lines of remote input history allowed until we start reducing lines.
         */
        private static final int NUMBER_OF_HISTORY_ALLOWED_UNTIL_REDUCTION = 1;
        private ArrayList<CharSequence> mTexts = new ArrayList<CharSequence>(5);

        public InboxStyle() {
        }

        /**
         * @deprecated use {@code InboxStyle()}.
         */
        @Deprecated
        public InboxStyle(Builder builder) {
            setBuilder(builder);
        }

        /**
         * Overrides ContentTitle in the big form of the template.
         * This defaults to the value passed to setContentTitle().
         */
        public InboxStyle setBigContentTitle(CharSequence title) {
            internalSetBigContentTitle(safeCharSequence(title));
            return this;
        }

        /**
         * Set the first line of text after the detail section in the big form of the template.
         */
        public InboxStyle setSummaryText(CharSequence cs) {
            internalSetSummaryText(safeCharSequence(cs));
            return this;
        }

        /**
         * Append a line to the digest section of the Inbox notification.
         */
        public InboxStyle addLine(CharSequence cs) {
            mTexts.add(safeCharSequence(cs));
            return this;
        }

        /**
         * @hide
         */
        public ArrayList<CharSequence> getLines() {
            return mTexts;
        }

        /**
         * @hide
         */
        public void addExtras(Bundle extras) {
            super.addExtras(extras);

            CharSequence[] a = new CharSequence[mTexts.size()];
            extras.putCharSequenceArray(EXTRA_TEXT_LINES, mTexts.toArray(a));
        }

        /**
         * @hide
         */
        @Override
        protected void restoreFromExtras(Bundle extras) {
            super.restoreFromExtras(extras);

            mTexts.clear();
            if (extras.containsKey(EXTRA_TEXT_LINES)) {
                Collections.addAll(mTexts, extras.getCharSequenceArray(EXTRA_TEXT_LINES));
            }
        }

        /**
         * @hide
         */
        public RemoteViews makeBigContentView() {
            StandardTemplateParams p = mBuilder.mParams.reset().fillTextsFrom(mBuilder).text(null);
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews contentView = getStandardView(mBuilder.getInboxLayoutResource(), p, result);

            int[] rowIds = {R.id.inbox_text0, R.id.inbox_text1, R.id.inbox_text2, R.id.inbox_text3,
                    R.id.inbox_text4, R.id.inbox_text5, R.id.inbox_text6};

            // Make sure all rows are gone in case we reuse a view.
            for (int rowId : rowIds) {
                contentView.setViewVisibility(rowId, View.GONE);
            }

            int i=0;
            int topPadding = mBuilder.mContext.getResources().getDimensionPixelSize(
                    R.dimen.notification_inbox_item_top_padding);
            boolean first = true;
            int onlyViewId = 0;
            int maxRows = rowIds.length;
            if (mBuilder.mActions.size() > 0) {
                maxRows--;
            }
            CharSequence[] remoteInputHistory = mBuilder.mN.extras.getCharSequenceArray(
                    EXTRA_REMOTE_INPUT_HISTORY);
            if (remoteInputHistory != null
                    && remoteInputHistory.length > NUMBER_OF_HISTORY_ALLOWED_UNTIL_REDUCTION) {
                // Let's remove some messages to make room for the remote input history.
                // 1 is always able to fit, but let's remove them if they are 2 or 3
                int numRemoteInputs = Math.min(remoteInputHistory.length,
                        MAX_REMOTE_INPUT_HISTORY_LINES);
                int totalNumRows = mTexts.size() + numRemoteInputs
                        - NUMBER_OF_HISTORY_ALLOWED_UNTIL_REDUCTION;
                if (totalNumRows > maxRows) {
                    int overflow = totalNumRows - maxRows;
                    if (mTexts.size() > maxRows) {
                        // Heuristic: if the Texts don't fit anyway, we'll rather drop the last
                        // few messages, even with the remote input
                        maxRows -= overflow;
                    } else  {
                        // otherwise we drop the first messages
                        i = overflow;
                    }
                }
            }
            while (i < mTexts.size() && i < maxRows) {
                CharSequence str = mTexts.get(i);
                if (!TextUtils.isEmpty(str)) {
                    contentView.setViewVisibility(rowIds[i], View.VISIBLE);
                    contentView.setTextViewText(rowIds[i],
                            mBuilder.processTextSpans(mBuilder.processLegacyText(str)));
                    mBuilder.setTextViewColorSecondary(contentView, rowIds[i], p);
                    contentView.setViewPadding(rowIds[i], 0, topPadding, 0, 0);
                    handleInboxImageMargin(contentView, rowIds[i], first,
                            result.getIconMarginEnd());
                    if (first) {
                        onlyViewId = rowIds[i];
                    } else {
                        onlyViewId = 0;
                    }
                    first = false;
                }
                i++;
            }
            if (onlyViewId != 0) {
                // We only have 1 entry, lets make it look like the normal Text of a Bigtext
                topPadding = mBuilder.mContext.getResources().getDimensionPixelSize(
                        R.dimen.notification_text_margin_top);
                contentView.setViewPadding(onlyViewId, 0, topPadding, 0, 0);
            }

            return contentView;
        }

        /**
         * @hide
         */
        @Override
        public boolean areNotificationsVisiblyDifferent(Style other) {
            if (other == null || getClass() != other.getClass()) {
                return true;
            }
            InboxStyle newS = (InboxStyle) other;

            final ArrayList<CharSequence> myLines = getLines();
            final ArrayList<CharSequence> newLines = newS.getLines();
            final int n = myLines.size();
            if (n != newLines.size()) {
                return true;
            }

            for (int i = 0; i < n; i++) {
                if (!Objects.equals(
                        String.valueOf(myLines.get(i)),
                        String.valueOf(newLines.get(i)))) {
                    return true;
                }
            }
            return false;
        }

        private void handleInboxImageMargin(RemoteViews contentView, int id, boolean first,
                int marginEndValue) {
            int endMargin = 0;
            if (first) {
                final int max = mBuilder.mN.extras.getInt(EXTRA_PROGRESS_MAX, 0);
                final boolean ind = mBuilder.mN.extras.getBoolean(EXTRA_PROGRESS_INDETERMINATE);
                boolean hasProgress = max != 0 || ind;
                if (!hasProgress) {
                    endMargin = marginEndValue;
                }
            }
            contentView.setViewLayoutMarginEnd(id, endMargin);
        }
    }

    /**
     * Notification style for media playback notifications.
     *
     * In the expanded form, {@link Notification#bigContentView}, up to 5
     * {@link Notification.Action}s specified with
     * {@link Notification.Builder#addAction(Action) addAction} will be
     * shown as icon-only pushbuttons, suitable for transport controls. The Bitmap given to
     * {@link Notification.Builder#setLargeIcon(android.graphics.Bitmap) setLargeIcon()} will be
     * treated as album artwork.
     * <p>
     * Unlike the other styles provided here, MediaStyle can also modify the standard-size
     * {@link Notification#contentView}; by providing action indices to
     * {@link #setShowActionsInCompactView(int...)} you can promote up to 3 actions to be displayed
     * in the standard view alongside the usual content.
     * <p>
     * Notifications created with MediaStyle will have their category set to
     * {@link Notification#CATEGORY_TRANSPORT CATEGORY_TRANSPORT} unless you set a different
     * category using {@link Notification.Builder#setCategory(String) setCategory()}.
     * <p>
     * Finally, if you attach a {@link android.media.session.MediaSession.Token} using
     * {@link android.app.Notification.MediaStyle#setMediaSession(MediaSession.Token)},
     * the System UI can identify this as a notification representing an active media session
     * and respond accordingly (by showing album artwork in the lockscreen, for example).
     *
     * <p>
     * Starting at {@link android.os.Build.VERSION_CODES#O Android O} any notification that has a
     * media session attached with {@link #setMediaSession(MediaSession.Token)} will be colorized.
     * You can opt-out of this behavior by using {@link Notification.Builder#setColorized(boolean)}.
     * <p>
     *
     * To use this style with your Notification, feed it to
     * {@link Notification.Builder#setStyle(android.app.Notification.Style)} like so:
     * <pre class="prettyprint">
     * Notification noti = new Notification.Builder()
     *     .setSmallIcon(R.drawable.ic_stat_player)
     *     .setContentTitle(&quot;Track title&quot;)
     *     .setContentText(&quot;Artist - Album&quot;)
     *     .setLargeIcon(albumArtBitmap))
     *     .setStyle(<b>new Notification.MediaStyle()</b>
     *         .setMediaSession(mySession))
     *     .build();
     * </pre>
     *
     * @see Notification#bigContentView
     * @see Notification.Builder#setColorized(boolean)
     */
    public static class MediaStyle extends Style {
        // Changing max media buttons requires also changing templates
        // (notification_template_material_media and notification_template_material_big_media).
        static final int MAX_MEDIA_BUTTONS_IN_COMPACT = 3;
        static final int MAX_MEDIA_BUTTONS = 5;
        @IdRes private static final int[] MEDIA_BUTTON_IDS = {
                R.id.action0,
                R.id.action1,
                R.id.action2,
                R.id.action3,
                R.id.action4,
        };

        private int[] mActionsToShowInCompact = null;
        private MediaSession.Token mToken;

        public MediaStyle() {
        }

        /**
         * @deprecated use {@code MediaStyle()}.
         */
        @Deprecated
        public MediaStyle(Builder builder) {
            setBuilder(builder);
        }

        /**
         * Request up to 3 actions (by index in the order of addition) to be shown in the compact
         * notification view.
         *
         * @param actions the indices of the actions to show in the compact notification view
         */
        public MediaStyle setShowActionsInCompactView(int...actions) {
            mActionsToShowInCompact = actions;
            return this;
        }

        /**
         * Attach a {@link android.media.session.MediaSession.Token} to this Notification
         * to provide additional playback information and control to the SystemUI.
         */
        public MediaStyle setMediaSession(MediaSession.Token token) {
            mToken = token;
            return this;
        }

        /**
         * @hide
         */
        @Override
        @UnsupportedAppUsage
        public Notification buildStyled(Notification wip) {
            super.buildStyled(wip);
            if (wip.category == null) {
                wip.category = Notification.CATEGORY_TRANSPORT;
            }
            return wip;
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeContentView(boolean increasedHeight) {
            return makeMediaContentView();
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeBigContentView() {
            return makeMediaBigContentView();
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            RemoteViews expanded = makeMediaBigContentView();
            return expanded != null ? expanded : makeMediaContentView();
        }

        /** @hide */
        @Override
        public void addExtras(Bundle extras) {
            super.addExtras(extras);

            if (mToken != null) {
                extras.putParcelable(EXTRA_MEDIA_SESSION, mToken);
            }
            if (mActionsToShowInCompact != null) {
                extras.putIntArray(EXTRA_COMPACT_ACTIONS, mActionsToShowInCompact);
            }
        }

        /**
         * @hide
         */
        @Override
        protected void restoreFromExtras(Bundle extras) {
            super.restoreFromExtras(extras);

            if (extras.containsKey(EXTRA_MEDIA_SESSION)) {
                mToken = extras.getParcelable(EXTRA_MEDIA_SESSION);
            }
            if (extras.containsKey(EXTRA_COMPACT_ACTIONS)) {
                mActionsToShowInCompact = extras.getIntArray(EXTRA_COMPACT_ACTIONS);
            }
        }

        /**
         * @hide
         */
        @Override
        public boolean areNotificationsVisiblyDifferent(Style other) {
            if (other == null || getClass() != other.getClass()) {
                return true;
            }
            // All fields to compare are on the Notification object
            return false;
        }

        private void bindMediaActionButton(RemoteViews container, @IdRes int buttonId,
                Action action, StandardTemplateParams p) {
            final boolean tombstone = (action.actionIntent == null);
            container.setViewVisibility(buttonId, View.VISIBLE);
            container.setImageViewIcon(buttonId, action.getIcon());

            // If the action buttons should not be tinted, then just use the default
            // notification color. Otherwise, just use the passed-in color.
            Resources resources = mBuilder.mContext.getResources();
            Configuration currentConfig = resources.getConfiguration();
            boolean inNightMode = (currentConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int tintColor = mBuilder.shouldTintActionButtons() || mBuilder.isColorized(p)
                    ? getActionColor(p)
                    : ContrastColorUtil.resolveColor(mBuilder.mContext,
                            Notification.COLOR_DEFAULT, inNightMode);

            container.setDrawableTint(buttonId, false, tintColor,
                    PorterDuff.Mode.SRC_ATOP);

            final TypedArray typedArray = mBuilder.mContext.obtainStyledAttributes(
                    new int[]{ android.R.attr.colorControlHighlight });
            int rippleAlpha = Color.alpha(typedArray.getColor(0, 0));
            typedArray.recycle();
            int rippleColor = Color.argb(rippleAlpha, Color.red(tintColor), Color.green(tintColor),
                    Color.blue(tintColor));
            container.setRippleDrawableColor(buttonId, ColorStateList.valueOf(rippleColor));

            if (!tombstone) {
                container.setOnClickPendingIntent(buttonId, action.actionIntent);
            }
            container.setContentDescription(buttonId, action.title);
        }

        private RemoteViews makeMediaContentView() {
            StandardTemplateParams p = mBuilder.mParams.reset().hasProgress(false).fillTextsFrom(
                    mBuilder);
            RemoteViews view = mBuilder.applyStandardTemplate(
                    R.layout.notification_template_material_media, p,
                    null /* result */);

            final int numActions = mBuilder.mActions.size();
            final int numActionsToShow = mActionsToShowInCompact == null
                    ? 0
                    : Math.min(mActionsToShowInCompact.length, MAX_MEDIA_BUTTONS_IN_COMPACT);
            if (numActionsToShow > numActions) {
                throw new IllegalArgumentException(String.format(
                        "setShowActionsInCompactView: action %d out of bounds (max %d)",
                        numActions, numActions - 1));
            }
            for (int i = 0; i < MAX_MEDIA_BUTTONS_IN_COMPACT; i++) {
                if (i < numActionsToShow) {
                    final Action action = mBuilder.mActions.get(mActionsToShowInCompact[i]);
                    bindMediaActionButton(view, MEDIA_BUTTON_IDS[i], action, p);
                } else {
                    view.setViewVisibility(MEDIA_BUTTON_IDS[i], View.GONE);
                }
            }
            handleImage(view);
            // handle the content margin
            int endMargin = R.dimen.notification_content_margin_end;
            if (mBuilder.mN.hasLargeIcon()) {
                endMargin = R.dimen.notification_media_image_margin_end;
            }
            view.setViewLayoutMarginEndDimen(R.id.notification_main_column, endMargin);
            return view;
        }

        private int getActionColor(StandardTemplateParams p) {
            return mBuilder.isColorized(p) ? mBuilder.getPrimaryTextColor(p)
                    : mBuilder.resolveContrastColor(p);
        }

        private RemoteViews makeMediaBigContentView() {
            final int actionCount = Math.min(mBuilder.mActions.size(), MAX_MEDIA_BUTTONS);
            // Dont add an expanded view if there is no more content to be revealed
            int actionsInCompact = mActionsToShowInCompact == null
                    ? 0
                    : Math.min(mActionsToShowInCompact.length, MAX_MEDIA_BUTTONS_IN_COMPACT);
            if (!mBuilder.mN.hasLargeIcon() && actionCount <= actionsInCompact) {
                return null;
            }
            StandardTemplateParams p = mBuilder.mParams.reset().hasProgress(false).fillTextsFrom(
                    mBuilder);
            RemoteViews big = mBuilder.applyStandardTemplate(
                    R.layout.notification_template_material_big_media, p , null /* result */);

            for (int i = 0; i < MAX_MEDIA_BUTTONS; i++) {
                if (i < actionCount) {
                    bindMediaActionButton(big, MEDIA_BUTTON_IDS[i], mBuilder.mActions.get(i), p);
                } else {
                    big.setViewVisibility(MEDIA_BUTTON_IDS[i], View.GONE);
                }
            }
            bindMediaActionButton(big, R.id.media_seamless, new Action(R.drawable.ic_media_seamless,
                    mBuilder.mContext.getString(
                            com.android.internal.R.string.ext_media_seamless_action), null), p);
            big.setViewVisibility(R.id.media_seamless, View.GONE);
            handleImage(big);
            return big;
        }

        private void handleImage(RemoteViews contentView) {
            if (mBuilder.mN.hasLargeIcon()) {
                contentView.setViewLayoutMarginEndDimen(R.id.line1, 0);
                contentView.setViewLayoutMarginEndDimen(R.id.text, 0);
            }
        }

        /**
         * @hide
         */
        @Override
        protected boolean hasProgress() {
            return false;
        }
    }

    /**
     * Notification style for custom views that are decorated by the system
     *
     * <p>Instead of providing a notification that is completely custom, a developer can set this
     * style and still obtain system decorations like the notification header with the expand
     * affordance and actions.
     *
     * <p>Use {@link android.app.Notification.Builder#setCustomContentView(RemoteViews)},
     * {@link android.app.Notification.Builder#setCustomBigContentView(RemoteViews)} and
     * {@link android.app.Notification.Builder#setCustomHeadsUpContentView(RemoteViews)} to set the
     * corresponding custom views to display.
     *
     * To use this style with your Notification, feed it to
     * {@link Notification.Builder#setStyle(android.app.Notification.Style)} like so:
     * <pre class="prettyprint">
     * Notification noti = new Notification.Builder()
     *     .setSmallIcon(R.drawable.ic_stat_player)
     *     .setLargeIcon(albumArtBitmap))
     *     .setCustomContentView(contentView);
     *     .setStyle(<b>new Notification.DecoratedCustomViewStyle()</b>)
     *     .build();
     * </pre>
     */
    public static class DecoratedCustomViewStyle extends Style {

        public DecoratedCustomViewStyle() {
        }

        /**
         * @hide
         */
        public boolean displayCustomViewInline() {
            return true;
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeContentView(boolean increasedHeight) {
            return makeStandardTemplateWithCustomContent(mBuilder.mN.contentView);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeBigContentView() {
            return makeDecoratedBigContentView();
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            return makeDecoratedHeadsUpContentView();
        }

        private RemoteViews makeDecoratedHeadsUpContentView() {
            RemoteViews headsUpContentView = mBuilder.mN.headsUpContentView == null
                    ? mBuilder.mN.contentView
                    : mBuilder.mN.headsUpContentView;
            if (mBuilder.mActions.size() == 0) {
               return makeStandardTemplateWithCustomContent(headsUpContentView);
            }
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews remoteViews = mBuilder.applyStandardTemplateWithActions(
                        mBuilder.getBigBaseLayoutResource(), result);
            buildIntoRemoteViewContent(remoteViews, headsUpContentView, result);
            return remoteViews;
        }

        private RemoteViews makeStandardTemplateWithCustomContent(RemoteViews customContent) {
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews remoteViews = mBuilder.applyStandardTemplate(
                    mBuilder.getBaseLayoutResource(), result);
            buildIntoRemoteViewContent(remoteViews, customContent, result);
            return remoteViews;
        }

        private RemoteViews makeDecoratedBigContentView() {
            RemoteViews bigContentView = mBuilder.mN.bigContentView == null
                    ? mBuilder.mN.contentView
                    : mBuilder.mN.bigContentView;
            if (mBuilder.mActions.size() == 0) {
                return makeStandardTemplateWithCustomContent(bigContentView);
            }
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews remoteViews = mBuilder.applyStandardTemplateWithActions(
                    mBuilder.getBigBaseLayoutResource(), result);
            buildIntoRemoteViewContent(remoteViews, bigContentView, result);
            return remoteViews;
        }

        private void buildIntoRemoteViewContent(RemoteViews remoteViews,
                RemoteViews customContent, TemplateBindResult result) {
            int childIndex = -1;
            if (customContent != null) {
                // Need to clone customContent before adding, because otherwise it can no longer be
                // parceled independently of remoteViews.
                customContent = customContent.clone();
                remoteViews.removeAllViewsExceptId(R.id.notification_main_column, R.id.progress);
                remoteViews.addView(R.id.notification_main_column, customContent, 0 /* index */);
                remoteViews.addFlags(RemoteViews.FLAG_REAPPLY_DISALLOWED);
                childIndex = 0;
            }
            remoteViews.setIntTag(R.id.notification_main_column,
                    com.android.internal.R.id.notification_custom_view_index_tag,
                    childIndex);
            // also update the end margin if there is an image
            Resources resources = mBuilder.mContext.getResources();
            int endMargin = resources.getDimensionPixelSize(
                    R.dimen.notification_content_margin_end) + result.getIconMarginEnd();
            remoteViews.setViewLayoutMarginEnd(R.id.notification_main_column, endMargin);
        }

        /**
         * @hide
         */
        @Override
        public boolean areNotificationsVisiblyDifferent(Style other) {
            if (other == null || getClass() != other.getClass()) {
                return true;
            }
            // Comparison done for all custom RemoteViews, independent of style
            return false;
        }
    }

    /**
     * Notification style for media custom views that are decorated by the system
     *
     * <p>Instead of providing a media notification that is completely custom, a developer can set
     * this style and still obtain system decorations like the notification header with the expand
     * affordance and actions.
     *
     * <p>Use {@link android.app.Notification.Builder#setCustomContentView(RemoteViews)},
     * {@link android.app.Notification.Builder#setCustomBigContentView(RemoteViews)} and
     * {@link android.app.Notification.Builder#setCustomHeadsUpContentView(RemoteViews)} to set the
     * corresponding custom views to display.
     * <p>
     * Contrary to {@link MediaStyle} a developer has to opt-in to the colorizing of the
     * notification by using {@link Notification.Builder#setColorized(boolean)}.
     * <p>
     * To use this style with your Notification, feed it to
     * {@link Notification.Builder#setStyle(android.app.Notification.Style)} like so:
     * <pre class="prettyprint">
     * Notification noti = new Notification.Builder()
     *     .setSmallIcon(R.drawable.ic_stat_player)
     *     .setLargeIcon(albumArtBitmap))
     *     .setCustomContentView(contentView);
     *     .setStyle(<b>new Notification.DecoratedMediaCustomViewStyle()</b>
     *          .setMediaSession(mySession))
     *     .build();
     * </pre>
     *
     * @see android.app.Notification.DecoratedCustomViewStyle
     * @see android.app.Notification.MediaStyle
     */
    public static class DecoratedMediaCustomViewStyle extends MediaStyle {

        public DecoratedMediaCustomViewStyle() {
        }

        /**
         * @hide
         */
        public boolean displayCustomViewInline() {
            return true;
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeContentView(boolean increasedHeight) {
            RemoteViews remoteViews = super.makeContentView(false /* increasedHeight */);
            return buildIntoRemoteView(remoteViews, R.id.notification_content_container,
                    mBuilder.mN.contentView);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeBigContentView() {
            RemoteViews customRemoteView = mBuilder.mN.bigContentView != null
                    ? mBuilder.mN.bigContentView
                    : mBuilder.mN.contentView;
            return makeBigContentViewWithCustomContent(customRemoteView);
        }

        private RemoteViews makeBigContentViewWithCustomContent(RemoteViews customRemoteView) {
            RemoteViews remoteViews = super.makeBigContentView();
            if (remoteViews != null) {
                return buildIntoRemoteView(remoteViews, R.id.notification_main_column,
                        customRemoteView);
            } else if (customRemoteView != mBuilder.mN.contentView){
                remoteViews = super.makeContentView(false /* increasedHeight */);
                return buildIntoRemoteView(remoteViews, R.id.notification_content_container,
                        customRemoteView);
            } else {
                return null;
            }
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            RemoteViews customRemoteView = mBuilder.mN.headsUpContentView != null
                    ? mBuilder.mN.headsUpContentView
                    : mBuilder.mN.contentView;
            return makeBigContentViewWithCustomContent(customRemoteView);
        }

        /**
         * @hide
         */
        @Override
        public boolean areNotificationsVisiblyDifferent(Style other) {
            if (other == null || getClass() != other.getClass()) {
                return true;
            }
            // Comparison done for all custom RemoteViews, independent of style
            return false;
        }

        private RemoteViews buildIntoRemoteView(RemoteViews remoteViews, int id,
                RemoteViews customContent) {
            if (customContent != null) {
                // Need to clone customContent before adding, because otherwise it can no longer be
                // parceled independently of remoteViews.
                customContent = customContent.clone();
                customContent.overrideTextColors(mBuilder.getPrimaryTextColor(mBuilder.mParams));
                remoteViews.removeAllViews(id);
                remoteViews.addView(id, customContent);
                remoteViews.addFlags(RemoteViews.FLAG_REAPPLY_DISALLOWED);
            }
            return remoteViews;
        }
    }

    /**
     * Encapsulates the information needed to display a notification as a bubble.
     *
     * <p>A bubble is used to display app content in a floating window over the existing
     * foreground activity. A bubble has a collapsed state represented by an icon,
     * {@link BubbleMetadata.Builder#setIcon(Icon)} and an expanded state which is populated
     * via {@link BubbleMetadata.Builder#setIntent(PendingIntent)}.</p>
     *
     * <b>Notifications with a valid and allowed bubble will display in collapsed state
     * outside of the notification shade on unlocked devices. When a user interacts with the
     * collapsed bubble, the bubble intent will be invoked and displayed.</b>
     *
     * @see Notification.Builder#setBubbleMetadata(BubbleMetadata)
     */
    public static final class BubbleMetadata implements Parcelable {

        private PendingIntent mPendingIntent;
        private PendingIntent mDeleteIntent;
        private Icon mIcon;
        private int mDesiredHeight;
        private int mFlags;

        /**
         * If set and the app creating the bubble is in the foreground, the bubble will be posted
         * in its expanded state, with the contents of {@link #getIntent()} in a floating window.
         *
         * <p>If the app creating the bubble is not in the foreground this flag has no effect.</p>
         *
         * <p>Generally this flag should only be set if the user has performed an action to request
         * or create a bubble.</p>
         */
        private static final int FLAG_AUTO_EXPAND_BUBBLE = 0x00000001;

        /**
         * If set and the app creating the bubble is in the foreground, the bubble will be posted
         * <b>without</b> the associated notification in the notification shade. Subsequent update
         * notifications to this bubble will post a notification in the shade.
         *
         * <p>If the app creating the bubble is not in the foreground this flag has no effect.</p>
         *
         * <p>Generally this flag should only be set if the user has performed an action to request
         * or create a bubble.</p>
         */
        private static final int FLAG_SUPPRESS_INITIAL_NOTIFICATION = 0x00000002;

        private BubbleMetadata(PendingIntent expandIntent, PendingIntent deleteIntent,
                Icon icon, int height) {
            mPendingIntent = expandIntent;
            mIcon = icon;
            mDesiredHeight = height;
            mDeleteIntent = deleteIntent;
        }

        private BubbleMetadata(Parcel in) {
            mPendingIntent = PendingIntent.CREATOR.createFromParcel(in);
            mIcon = Icon.CREATOR.createFromParcel(in);
            mDesiredHeight = in.readInt();
            mFlags = in.readInt();
            if (in.readInt() != 0) {
                mDeleteIntent = PendingIntent.CREATOR.createFromParcel(in);
            }
        }

        /**
         * @return the pending intent used to populate the floating window for this bubble.
         */
        public PendingIntent getIntent() {
            return mPendingIntent;
        }

        /**
         * @return the pending intent to send when the bubble is dismissed by a user, if one exists.
         */
        public PendingIntent getDeleteIntent() {
            return mDeleteIntent;
        }

        /**
         * @return the title that will appear along with the app content defined by
         * {@link #getIntent()} for this bubble.
         *
         * @deprecated titles are no longer required or shown.
         */
        @Deprecated
        public CharSequence getTitle() {
            return "";
        }
        /**
         * @return the icon that will be displayed for this bubble when it is collapsed.
         */
        public Icon getIcon() {
            return mIcon;
        }

        /**
         * @return the ideal height for the floating window that app content defined by
         * {@link #getIntent()} for this bubble.
         */
        public int getDesiredHeight() {
            return mDesiredHeight;
        }

        /**
         * @return whether this bubble should auto expand when it is posted.
         *
         * @see BubbleMetadata.Builder#setAutoExpandBubble(boolean)
         */
        public boolean getAutoExpandBubble() {
            return (mFlags & FLAG_AUTO_EXPAND_BUBBLE) != 0;
        }

        /**
         * @return whether this bubble should suppress the initial notification when it is posted.
         *
         * @see BubbleMetadata.Builder#setSuppressInitialNotification(boolean)
         */
        public boolean getSuppressInitialNotification() {
            return (mFlags & FLAG_SUPPRESS_INITIAL_NOTIFICATION) != 0;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<BubbleMetadata> CREATOR =
                new Parcelable.Creator<BubbleMetadata>() {

                    @Override
                    public BubbleMetadata createFromParcel(Parcel source) {
                        return new BubbleMetadata(source);
                    }

                    @Override
                    public BubbleMetadata[] newArray(int size) {
                        return new BubbleMetadata[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            mPendingIntent.writeToParcel(out, 0);
            mIcon.writeToParcel(out, 0);
            out.writeInt(mDesiredHeight);
            out.writeInt(mFlags);
            out.writeInt(mDeleteIntent != null ? 1 : 0);
            if (mDeleteIntent != null) {
                mDeleteIntent.writeToParcel(out, 0);
            }
        }

        private void setFlags(int flags) {
            mFlags = flags;
        }

        /**
         * Builder to construct a {@link BubbleMetadata} object.
         */
        public static class Builder {

            private PendingIntent mPendingIntent;
            private Icon mIcon;
            private int mDesiredHeight;
            private int mFlags;
            private PendingIntent mDeleteIntent;

            /**
             * Constructs a new builder object.
             */
            public Builder() {
            }

            /**
             * Sets the intent that will be used when the bubble is expanded. This will display the
             * app content in a floating window over the existing foreground activity.
             */
            public BubbleMetadata.Builder setIntent(PendingIntent intent) {
                if (intent == null) {
                    throw new IllegalArgumentException("Bubble requires non-null pending intent");
                }
                mPendingIntent = intent;
                return this;
            }

            /**
             * Sets the title that will appear along with the app content for this bubble.
             *
             * <p>A title is required and should expect to fit on a single line and make sense when
             * shown with the content defined by {@link #setIntent(PendingIntent)}.</p>
             *
             * @deprecated titles are no longer required or shown.
             */
            @Deprecated
            public BubbleMetadata.Builder setTitle(CharSequence title) {
                return this;
            }

            /**
             * Sets the icon that will represent the bubble when it is collapsed.
             *
             * <p>An icon is required and should be representative of the content within the bubble.
             * If your app produces multiple bubbles, the image should be unique for each of them.
             * </p>
             */
            public BubbleMetadata.Builder setIcon(Icon icon) {
                if (icon == null) {
                    throw new IllegalArgumentException("Bubbles require non-null icon");
                }
                mIcon = icon;
                return this;
            }

            /**
             * Sets the desired height for the app content defined by
             * {@link #setIntent(PendingIntent)}, this height may not be respected if there is not
             * enough space on the screen or if the provided height is too small to be useful.
             */
            public BubbleMetadata.Builder setDesiredHeight(int height) {
                mDesiredHeight = Math.max(height, 0);
                return this;
            }

            /**
             * If set and the app creating the bubble is in the foreground, the bubble will be
             * posted in its expanded state, with the contents of {@link #getIntent()} in a
             * floating window.
             *
             * <p>If the app creating the bubble is not in the foreground this flag has no effect.
             * </p>
             *
             * <p>Generally this flag should only be set if the user has performed an action to
             * request or create a bubble.</p>
             */
            public BubbleMetadata.Builder setAutoExpandBubble(boolean shouldExpand) {
                setFlag(FLAG_AUTO_EXPAND_BUBBLE, shouldExpand);
                return this;
            }

            /**
             * If set and the app creating the bubble is in the foreground, the bubble will be
             * posted <b>without</b> the associated notification in the notification shade.
             * Subsequent update notifications to this bubble will post a notification in the shade.
             *
             * <p>If the app creating the bubble is not in the foreground this flag has no effect.
             * </p>
             *
             * <p>Generally this flag should only be set if the user has performed an action to
             * request or create a bubble.</p>
             */
            public BubbleMetadata.Builder setSuppressInitialNotification(
                    boolean shouldSupressNotif) {
                setFlag(FLAG_SUPPRESS_INITIAL_NOTIFICATION, shouldSupressNotif);
                return this;
            }

            /**
             * Sets an optional intent to send when this bubble is explicitly removed by the user.
             */
            public BubbleMetadata.Builder setDeleteIntent(PendingIntent deleteIntent) {
                mDeleteIntent = deleteIntent;
                return this;
            }

            /**
             * Creates the {@link BubbleMetadata} defined by this builder.
             * <p>Will throw {@link IllegalStateException} if required fields have not been set
             * on this builder.</p>
             */
            public BubbleMetadata build() {
                if (mPendingIntent == null) {
                    throw new IllegalStateException("Must supply pending intent to bubble");
                }
                if (mIcon == null) {
                    throw new IllegalStateException("Must supply an icon for the bubble");
                }
                BubbleMetadata data = new BubbleMetadata(mPendingIntent, mDeleteIntent,
                        mIcon, mDesiredHeight);
                data.setFlags(mFlags);
                return data;
            }

            /**
             * @hide
             */
            public BubbleMetadata.Builder setFlag(int mask, boolean value) {
                if (value) {
                    mFlags |= mask;
                } else {
                    mFlags &= ~mask;
                }
                return this;
            }
        }
    }


    // When adding a new Style subclass here, don't forget to update
    // Builder.getNotificationStyleClass.

    /**
     * Extender interface for use with {@link Builder#extend}. Extenders may be used to add
     * metadata or change options on a notification builder.
     */
    public interface Extender {
        /**
         * Apply this extender to a notification builder.
         * @param builder the builder to be modified.
         * @return the build object for chaining.
         */
        public Builder extend(Builder builder);
    }

    /**
     * Helper class to add wearable extensions to notifications.
     * <p class="note"> See
     * <a href="{@docRoot}wear/notifications/creating.html">Creating Notifications
     * for Android Wear</a> for more information on how to use this class.
     * <p>
     * To create a notification with wearable extensions:
     * <ol>
     *   <li>Create a {@link android.app.Notification.Builder}, setting any desired
     *   properties.
     *   <li>Create a {@link android.app.Notification.WearableExtender}.
     *   <li>Set wearable-specific properties using the
     *   {@code add} and {@code set} methods of {@link android.app.Notification.WearableExtender}.
     *   <li>Call {@link android.app.Notification.Builder#extend} to apply the extensions to a
     *   notification.
     *   <li>Post the notification to the notification system with the
     *   {@code NotificationManager.notify(...)} methods.
     * </ol>
     *
     * <pre class="prettyprint">
     * Notification notif = new Notification.Builder(mContext)
     *         .setContentTitle(&quot;New mail from &quot; + sender.toString())
     *         .setContentText(subject)
     *         .setSmallIcon(R.drawable.new_mail)
     *         .extend(new Notification.WearableExtender()
     *                 .setContentIcon(R.drawable.new_mail))
     *         .build();
     * NotificationManager notificationManger =
     *         (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
     * notificationManger.notify(0, notif);</pre>
     *
     * <p>Wearable extensions can be accessed on an existing notification by using the
     * {@code WearableExtender(Notification)} constructor,
     * and then using the {@code get} methods to access values.
     *
     * <pre class="prettyprint">
     * Notification.WearableExtender wearableExtender = new Notification.WearableExtender(
     *         notification);
     * List&lt;Notification&gt; pages = wearableExtender.getPages();</pre>
     */
    public static final class WearableExtender implements Extender {
        /**
         * Sentinel value for an action index that is unset.
         */
        public static final int UNSET_ACTION_INDEX = -1;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification with
         * default sizing.
         * <p>For custom display notifications created using {@link #setDisplayIntent},
         * the default is {@link #SIZE_MEDIUM}. All other notifications size automatically based
         * on their content.
         *
         * @deprecated Display intents are no longer supported.
         */
        @Deprecated
        public static final int SIZE_DEFAULT = 0;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * with an extra small size.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         *
         * @deprecated Display intents are no longer supported.
         */
        @Deprecated
        public static final int SIZE_XSMALL = 1;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * with a small size.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         *
         * @deprecated Display intents are no longer supported.
         */
        @Deprecated
        public static final int SIZE_SMALL = 2;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * with a medium size.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         *
         * @deprecated Display intents are no longer supported.
         */
        @Deprecated
        public static final int SIZE_MEDIUM = 3;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * with a large size.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         *
         * @deprecated Display intents are no longer supported.
         */
        @Deprecated
        public static final int SIZE_LARGE = 4;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * full screen.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         *
         * @deprecated Display intents are no longer supported.
         */
        @Deprecated
        public static final int SIZE_FULL_SCREEN = 5;

        /**
         * Sentinel value for use with {@link #setHintScreenTimeout} to keep the screen on for a
         * short amount of time when this notification is displayed on the screen. This
         * is the default value.
         *
         * @deprecated This feature is no longer supported.
         */
        @Deprecated
        public static final int SCREEN_TIMEOUT_SHORT = 0;

        /**
         * Sentinel value for use with {@link #setHintScreenTimeout} to keep the screen on
         * for a longer amount of time when this notification is displayed on the screen.
         *
         * @deprecated This feature is no longer supported.
         */
        @Deprecated
        public static final int SCREEN_TIMEOUT_LONG = -1;

        /** Notification extra which contains wearable extensions */
        private static final String EXTRA_WEARABLE_EXTENSIONS = "android.wearable.EXTENSIONS";

        // Keys within EXTRA_WEARABLE_EXTENSIONS for wearable options.
        private static final String KEY_ACTIONS = "actions";
        private static final String KEY_FLAGS = "flags";
        private static final String KEY_DISPLAY_INTENT = "displayIntent";
        private static final String KEY_PAGES = "pages";
        private static final String KEY_BACKGROUND = "background";
        private static final String KEY_CONTENT_ICON = "contentIcon";
        private static final String KEY_CONTENT_ICON_GRAVITY = "contentIconGravity";
        private static final String KEY_CONTENT_ACTION_INDEX = "contentActionIndex";
        private static final String KEY_CUSTOM_SIZE_PRESET = "customSizePreset";
        private static final String KEY_CUSTOM_CONTENT_HEIGHT = "customContentHeight";
        private static final String KEY_GRAVITY = "gravity";
        private static final String KEY_HINT_SCREEN_TIMEOUT = "hintScreenTimeout";
        private static final String KEY_DISMISSAL_ID = "dismissalId";
        private static final String KEY_BRIDGE_TAG = "bridgeTag";

        // Flags bitwise-ored to mFlags
        private static final int FLAG_CONTENT_INTENT_AVAILABLE_OFFLINE = 0x1;
        private static final int FLAG_HINT_HIDE_ICON = 1 << 1;
        private static final int FLAG_HINT_SHOW_BACKGROUND_ONLY = 1 << 2;
        private static final int FLAG_START_SCROLL_BOTTOM = 1 << 3;
        private static final int FLAG_HINT_AVOID_BACKGROUND_CLIPPING = 1 << 4;
        private static final int FLAG_BIG_PICTURE_AMBIENT = 1 << 5;
        private static final int FLAG_HINT_CONTENT_INTENT_LAUNCHES_ACTIVITY = 1 << 6;

        // Default value for flags integer
        private static final int DEFAULT_FLAGS = FLAG_CONTENT_INTENT_AVAILABLE_OFFLINE;

        private static final int DEFAULT_CONTENT_ICON_GRAVITY = Gravity.END;
        private static final int DEFAULT_GRAVITY = Gravity.BOTTOM;

        private ArrayList<Action> mActions = new ArrayList<Action>();
        private int mFlags = DEFAULT_FLAGS;
        private PendingIntent mDisplayIntent;
        private ArrayList<Notification> mPages = new ArrayList<Notification>();
        private Bitmap mBackground;
        private int mContentIcon;
        private int mContentIconGravity = DEFAULT_CONTENT_ICON_GRAVITY;
        private int mContentActionIndex = UNSET_ACTION_INDEX;
        private int mCustomSizePreset = SIZE_DEFAULT;
        private int mCustomContentHeight;
        private int mGravity = DEFAULT_GRAVITY;
        private int mHintScreenTimeout;
        private String mDismissalId;
        private String mBridgeTag;

        /**
         * Create a {@link android.app.Notification.WearableExtender} with default
         * options.
         */
        public WearableExtender() {
        }

        public WearableExtender(Notification notif) {
            Bundle wearableBundle = notif.extras.getBundle(EXTRA_WEARABLE_EXTENSIONS);
            if (wearableBundle != null) {
                List<Action> actions = wearableBundle.getParcelableArrayList(KEY_ACTIONS);
                if (actions != null) {
                    mActions.addAll(actions);
                }

                mFlags = wearableBundle.getInt(KEY_FLAGS, DEFAULT_FLAGS);
                mDisplayIntent = wearableBundle.getParcelable(KEY_DISPLAY_INTENT);

                Notification[] pages = getNotificationArrayFromBundle(
                        wearableBundle, KEY_PAGES);
                if (pages != null) {
                    Collections.addAll(mPages, pages);
                }

                mBackground = wearableBundle.getParcelable(KEY_BACKGROUND);
                mContentIcon = wearableBundle.getInt(KEY_CONTENT_ICON);
                mContentIconGravity = wearableBundle.getInt(KEY_CONTENT_ICON_GRAVITY,
                        DEFAULT_CONTENT_ICON_GRAVITY);
                mContentActionIndex = wearableBundle.getInt(KEY_CONTENT_ACTION_INDEX,
                        UNSET_ACTION_INDEX);
                mCustomSizePreset = wearableBundle.getInt(KEY_CUSTOM_SIZE_PRESET,
                        SIZE_DEFAULT);
                mCustomContentHeight = wearableBundle.getInt(KEY_CUSTOM_CONTENT_HEIGHT);
                mGravity = wearableBundle.getInt(KEY_GRAVITY, DEFAULT_GRAVITY);
                mHintScreenTimeout = wearableBundle.getInt(KEY_HINT_SCREEN_TIMEOUT);
                mDismissalId = wearableBundle.getString(KEY_DISMISSAL_ID);
                mBridgeTag = wearableBundle.getString(KEY_BRIDGE_TAG);
            }
        }

        /**
         * Apply wearable extensions to a notification that is being built. This is typically
         * called by the {@link android.app.Notification.Builder#extend} method of
         * {@link android.app.Notification.Builder}.
         */
        @Override
        public Notification.Builder extend(Notification.Builder builder) {
            Bundle wearableBundle = new Bundle();

            if (!mActions.isEmpty()) {
                wearableBundle.putParcelableArrayList(KEY_ACTIONS, mActions);
            }
            if (mFlags != DEFAULT_FLAGS) {
                wearableBundle.putInt(KEY_FLAGS, mFlags);
            }
            if (mDisplayIntent != null) {
                wearableBundle.putParcelable(KEY_DISPLAY_INTENT, mDisplayIntent);
            }
            if (!mPages.isEmpty()) {
                wearableBundle.putParcelableArray(KEY_PAGES, mPages.toArray(
                        new Notification[mPages.size()]));
            }
            if (mBackground != null) {
                wearableBundle.putParcelable(KEY_BACKGROUND, mBackground);
            }
            if (mContentIcon != 0) {
                wearableBundle.putInt(KEY_CONTENT_ICON, mContentIcon);
            }
            if (mContentIconGravity != DEFAULT_CONTENT_ICON_GRAVITY) {
                wearableBundle.putInt(KEY_CONTENT_ICON_GRAVITY, mContentIconGravity);
            }
            if (mContentActionIndex != UNSET_ACTION_INDEX) {
                wearableBundle.putInt(KEY_CONTENT_ACTION_INDEX,
                        mContentActionIndex);
            }
            if (mCustomSizePreset != SIZE_DEFAULT) {
                wearableBundle.putInt(KEY_CUSTOM_SIZE_PRESET, mCustomSizePreset);
            }
            if (mCustomContentHeight != 0) {
                wearableBundle.putInt(KEY_CUSTOM_CONTENT_HEIGHT, mCustomContentHeight);
            }
            if (mGravity != DEFAULT_GRAVITY) {
                wearableBundle.putInt(KEY_GRAVITY, mGravity);
            }
            if (mHintScreenTimeout != 0) {
                wearableBundle.putInt(KEY_HINT_SCREEN_TIMEOUT, mHintScreenTimeout);
            }
            if (mDismissalId != null) {
                wearableBundle.putString(KEY_DISMISSAL_ID, mDismissalId);
            }
            if (mBridgeTag != null) {
                wearableBundle.putString(KEY_BRIDGE_TAG, mBridgeTag);
            }

            builder.getExtras().putBundle(EXTRA_WEARABLE_EXTENSIONS, wearableBundle);
            return builder;
        }

        @Override
        public WearableExtender clone() {
            WearableExtender that = new WearableExtender();
            that.mActions = new ArrayList<Action>(this.mActions);
            that.mFlags = this.mFlags;
            that.mDisplayIntent = this.mDisplayIntent;
            that.mPages = new ArrayList<Notification>(this.mPages);
            that.mBackground = this.mBackground;
            that.mContentIcon = this.mContentIcon;
            that.mContentIconGravity = this.mContentIconGravity;
            that.mContentActionIndex = this.mContentActionIndex;
            that.mCustomSizePreset = this.mCustomSizePreset;
            that.mCustomContentHeight = this.mCustomContentHeight;
            that.mGravity = this.mGravity;
            that.mHintScreenTimeout = this.mHintScreenTimeout;
            that.mDismissalId = this.mDismissalId;
            that.mBridgeTag = this.mBridgeTag;
            return that;
        }

        /**
         * Add a wearable action to this notification.
         *
         * <p>When wearable actions are added using this method, the set of actions that
         * show on a wearable device splits from devices that only show actions added
         * using {@link android.app.Notification.Builder#addAction}. This allows for customization
         * of which actions display on different devices.
         *
         * @param action the action to add to this notification
         * @return this object for method chaining
         * @see android.app.Notification.Action
         */
        public WearableExtender addAction(Action action) {
            mActions.add(action);
            return this;
        }

        /**
         * Adds wearable actions to this notification.
         *
         * <p>When wearable actions are added using this method, the set of actions that
         * show on a wearable device splits from devices that only show actions added
         * using {@link android.app.Notification.Builder#addAction}. This allows for customization
         * of which actions display on different devices.
         *
         * @param actions the actions to add to this notification
         * @return this object for method chaining
         * @see android.app.Notification.Action
         */
        public WearableExtender addActions(List<Action> actions) {
            mActions.addAll(actions);
            return this;
        }

        /**
         * Clear all wearable actions present on this builder.
         * @return this object for method chaining.
         * @see #addAction
         */
        public WearableExtender clearActions() {
            mActions.clear();
            return this;
        }

        /**
         * Get the wearable actions present on this notification.
         */
        public List<Action> getActions() {
            return mActions;
        }

        /**
         * Set an intent to launch inside of an activity view when displaying
         * this notification. The {@link PendingIntent} provided should be for an activity.
         *
         * <pre class="prettyprint">
         * Intent displayIntent = new Intent(context, MyDisplayActivity.class);
         * PendingIntent displayPendingIntent = PendingIntent.getActivity(context,
         *         0, displayIntent, PendingIntent.FLAG_UPDATE_CURRENT);
         * Notification notif = new Notification.Builder(context)
         *         .extend(new Notification.WearableExtender()
         *                 .setDisplayIntent(displayPendingIntent)
         *                 .setCustomSizePreset(Notification.WearableExtender.SIZE_MEDIUM))
         *         .build();</pre>
         *
         * <p>The activity to launch needs to allow embedding, must be exported, and
         * should have an empty task affinity. It is also recommended to use the device
         * default light theme.
         *
         * <p>Example AndroidManifest.xml entry:
         * <pre class="prettyprint">
         * &lt;activity android:name=&quot;com.example.MyDisplayActivity&quot;
         *     android:exported=&quot;true&quot;
         *     android:allowEmbedded=&quot;true&quot;
         *     android:taskAffinity=&quot;&quot;
         *     android:theme=&quot;@android:style/Theme.DeviceDefault.Light&quot; /&gt;</pre>
         *
         * @param intent the {@link PendingIntent} for an activity
         * @return this object for method chaining
         * @see android.app.Notification.WearableExtender#getDisplayIntent
         * @deprecated Display intents are no longer supported.
         */
        @Deprecated
        public WearableExtender setDisplayIntent(PendingIntent intent) {
            mDisplayIntent = intent;
            return this;
        }

        /**
         * Get the intent to launch inside of an activity view when displaying this
         * notification. This {@code PendingIntent} should be for an activity.
         *
         * @deprecated Display intents are no longer supported.
         */
        @Deprecated
        public PendingIntent getDisplayIntent() {
            return mDisplayIntent;
        }

        /**
         * Add an additional page of content to display with this notification. The current
         * notification forms the first page, and pages added using this function form
         * subsequent pages. This field can be used to separate a notification into multiple
         * sections.
         *
         * @param page the notification to add as another page
         * @return this object for method chaining
         * @see android.app.Notification.WearableExtender#getPages
         * @deprecated Multiple content pages are no longer supported.
         */
        @Deprecated
        public WearableExtender addPage(Notification page) {
            mPages.add(page);
            return this;
        }

        /**
         * Add additional pages of content to display with this notification. The current
         * notification forms the first page, and pages added using this function form
         * subsequent pages. This field can be used to separate a notification into multiple
         * sections.
         *
         * @param pages a list of notifications
         * @return this object for method chaining
         * @see android.app.Notification.WearableExtender#getPages
         * @deprecated Multiple content pages are no longer supported.
         */
        @Deprecated
        public WearableExtender addPages(List<Notification> pages) {
            mPages.addAll(pages);
            return this;
        }

        /**
         * Clear all additional pages present on this builder.
         * @return this object for method chaining.
         * @see #addPage
         * @deprecated Multiple content pages are no longer supported.
         */
        @Deprecated
        public WearableExtender clearPages() {
            mPages.clear();
            return this;
        }

        /**
         * Get the array of additional pages of content for displaying this notification. The
         * current notification forms the first page, and elements within this array form
         * subsequent pages. This field can be used to separate a notification into multiple
         * sections.
         * @return the pages for this notification
         * @deprecated Multiple content pages are no longer supported.
         */
        @Deprecated
        public List<Notification> getPages() {
            return mPages;
        }

        /**
         * Set a background image to be displayed behind the notification content.
         * Contrary to the {@link android.app.Notification.BigPictureStyle}, this background
         * will work with any notification style.
         *
         * @param background the background bitmap
         * @return this object for method chaining
         * @see android.app.Notification.WearableExtender#getBackground
         * @deprecated Background images are no longer supported.
         */
        @Deprecated
        public WearableExtender setBackground(Bitmap background) {
            mBackground = background;
            return this;
        }

        /**
         * Get a background image to be displayed behind the notification content.
         * Contrary to the {@link android.app.Notification.BigPictureStyle}, this background
         * will work with any notification style.
         *
         * @return the background image
         * @see android.app.Notification.WearableExtender#setBackground
         * @deprecated Background images are no longer supported.
         */
        @Deprecated
        public Bitmap getBackground() {
            return mBackground;
        }

        /**
         * Set an icon that goes with the content of this notification.
         */
        @Deprecated
        public WearableExtender setContentIcon(int icon) {
            mContentIcon = icon;
            return this;
        }

        /**
         * Get an icon that goes with the content of this notification.
         */
        @Deprecated
        public int getContentIcon() {
            return mContentIcon;
        }

        /**
         * Set the gravity that the content icon should have within the notification display.
         * Supported values include {@link android.view.Gravity#START} and
         * {@link android.view.Gravity#END}. The default value is {@link android.view.Gravity#END}.
         * @see #setContentIcon
         */
        @Deprecated
        public WearableExtender setContentIconGravity(int contentIconGravity) {
            mContentIconGravity = contentIconGravity;
            return this;
        }

        /**
         * Get the gravity that the content icon should have within the notification display.
         * Supported values include {@link android.view.Gravity#START} and
         * {@link android.view.Gravity#END}. The default value is {@link android.view.Gravity#END}.
         * @see #getContentIcon
         */
        @Deprecated
        public int getContentIconGravity() {
            return mContentIconGravity;
        }

        /**
         * Set an action from this notification's actions as the primary action. If the action has a
         * {@link RemoteInput} associated with it, shortcuts to the options for that input are shown
         * directly on the notification.
         *
         * @param actionIndex The index of the primary action.
         *                    If wearable actions were added to the main notification, this index
         *                    will apply to that list, otherwise it will apply to the regular
         *                    actions list.
         */
        public WearableExtender setContentAction(int actionIndex) {
            mContentActionIndex = actionIndex;
            return this;
        }

        /**
         * Get the index of the notification action, if any, that was specified as the primary
         * action.
         *
         * <p>If wearable specific actions were added to the main notification, this index will
         * apply to that list, otherwise it will apply to the regular actions list.
         *
         * @return the action index or {@link #UNSET_ACTION_INDEX} if no action was selected.
         */
        public int getContentAction() {
            return mContentActionIndex;
        }

        /**
         * Set the gravity that this notification should have within the available viewport space.
         * Supported values include {@link android.view.Gravity#TOP},
         * {@link android.view.Gravity#CENTER_VERTICAL} and {@link android.view.Gravity#BOTTOM}.
         * The default value is {@link android.view.Gravity#BOTTOM}.
         */
        @Deprecated
        public WearableExtender setGravity(int gravity) {
            mGravity = gravity;
            return this;
        }

        /**
         * Get the gravity that this notification should have within the available viewport space.
         * Supported values include {@link android.view.Gravity#TOP},
         * {@link android.view.Gravity#CENTER_VERTICAL} and {@link android.view.Gravity#BOTTOM}.
         * The default value is {@link android.view.Gravity#BOTTOM}.
         */
        @Deprecated
        public int getGravity() {
            return mGravity;
        }

        /**
         * Set the custom size preset for the display of this notification out of the available
         * presets found in {@link android.app.Notification.WearableExtender}, e.g.
         * {@link #SIZE_LARGE}.
         * <p>Some custom size presets are only applicable for custom display notifications created
         * using {@link android.app.Notification.WearableExtender#setDisplayIntent}. Check the
         * documentation for the preset in question. See also
         * {@link #setCustomContentHeight} and {@link #getCustomSizePreset}.
         */
        @Deprecated
        public WearableExtender setCustomSizePreset(int sizePreset) {
            mCustomSizePreset = sizePreset;
            return this;
        }

        /**
         * Get the custom size preset for the display of this notification out of the available
         * presets found in {@link android.app.Notification.WearableExtender}, e.g.
         * {@link #SIZE_LARGE}.
         * <p>Some custom size presets are only applicable for custom display notifications created
         * using {@link #setDisplayIntent}. Check the documentation for the preset in question.
         * See also {@link #setCustomContentHeight} and {@link #setCustomSizePreset}.
         */
        @Deprecated
        public int getCustomSizePreset() {
            return mCustomSizePreset;
        }

        /**
         * Set the custom height in pixels for the display of this notification's content.
         * <p>This option is only available for custom display notifications created
         * using {@link android.app.Notification.WearableExtender#setDisplayIntent}. See also
         * {@link android.app.Notification.WearableExtender#setCustomSizePreset} and
         * {@link #getCustomContentHeight}.
         */
        @Deprecated
        public WearableExtender setCustomContentHeight(int height) {
            mCustomContentHeight = height;
            return this;
        }

        /**
         * Get the custom height in pixels for the display of this notification's content.
         * <p>This option is only available for custom display notifications created
         * using {@link #setDisplayIntent}. See also {@link #setCustomSizePreset} and
         * {@link #setCustomContentHeight}.
         */
        @Deprecated
        public int getCustomContentHeight() {
            return mCustomContentHeight;
        }

        /**
         * Set whether the scrolling position for the contents of this notification should start
         * at the bottom of the contents instead of the top when the contents are too long to
         * display within the screen.  Default is false (start scroll at the top).
         */
        public WearableExtender setStartScrollBottom(boolean startScrollBottom) {
            setFlag(FLAG_START_SCROLL_BOTTOM, startScrollBottom);
            return this;
        }

        /**
         * Get whether the scrolling position for the contents of this notification should start
         * at the bottom of the contents instead of the top when the contents are too long to
         * display within the screen. Default is false (start scroll at the top).
         */
        public boolean getStartScrollBottom() {
            return (mFlags & FLAG_START_SCROLL_BOTTOM) != 0;
        }

        /**
         * Set whether the content intent is available when the wearable device is not connected
         * to a companion device.  The user can still trigger this intent when the wearable device
         * is offline, but a visual hint will indicate that the content intent may not be available.
         * Defaults to true.
         */
        public WearableExtender setContentIntentAvailableOffline(
                boolean contentIntentAvailableOffline) {
            setFlag(FLAG_CONTENT_INTENT_AVAILABLE_OFFLINE, contentIntentAvailableOffline);
            return this;
        }

        /**
         * Get whether the content intent is available when the wearable device is not connected
         * to a companion device.  The user can still trigger this intent when the wearable device
         * is offline, but a visual hint will indicate that the content intent may not be available.
         * Defaults to true.
         */
        public boolean getContentIntentAvailableOffline() {
            return (mFlags & FLAG_CONTENT_INTENT_AVAILABLE_OFFLINE) != 0;
        }

        /**
         * Set a hint that this notification's icon should not be displayed.
         * @param hintHideIcon {@code true} to hide the icon, {@code false} otherwise.
         * @return this object for method chaining
         */
        @Deprecated
        public WearableExtender setHintHideIcon(boolean hintHideIcon) {
            setFlag(FLAG_HINT_HIDE_ICON, hintHideIcon);
            return this;
        }

        /**
         * Get a hint that this notification's icon should not be displayed.
         * @return {@code true} if this icon should not be displayed, false otherwise.
         * The default value is {@code false} if this was never set.
         */
        @Deprecated
        public boolean getHintHideIcon() {
            return (mFlags & FLAG_HINT_HIDE_ICON) != 0;
        }

        /**
         * Set a visual hint that only the background image of this notification should be
         * displayed, and other semantic content should be hidden. This hint is only applicable
         * to sub-pages added using {@link #addPage}.
         */
        @Deprecated
        public WearableExtender setHintShowBackgroundOnly(boolean hintShowBackgroundOnly) {
            setFlag(FLAG_HINT_SHOW_BACKGROUND_ONLY, hintShowBackgroundOnly);
            return this;
        }

        /**
         * Get a visual hint that only the background image of this notification should be
         * displayed, and other semantic content should be hidden. This hint is only applicable
         * to sub-pages added using {@link android.app.Notification.WearableExtender#addPage}.
         */
        @Deprecated
        public boolean getHintShowBackgroundOnly() {
            return (mFlags & FLAG_HINT_SHOW_BACKGROUND_ONLY) != 0;
        }

        /**
         * Set a hint that this notification's background should not be clipped if possible,
         * and should instead be resized to fully display on the screen, retaining the aspect
         * ratio of the image. This can be useful for images like barcodes or qr codes.
         * @param hintAvoidBackgroundClipping {@code true} to avoid clipping if possible.
         * @return this object for method chaining
         */
        @Deprecated
        public WearableExtender setHintAvoidBackgroundClipping(
                boolean hintAvoidBackgroundClipping) {
            setFlag(FLAG_HINT_AVOID_BACKGROUND_CLIPPING, hintAvoidBackgroundClipping);
            return this;
        }

        /**
         * Get a hint that this notification's background should not be clipped if possible,
         * and should instead be resized to fully display on the screen, retaining the aspect
         * ratio of the image. This can be useful for images like barcodes or qr codes.
         * @return {@code true} if it's ok if the background is clipped on the screen, false
         * otherwise. The default value is {@code false} if this was never set.
         */
        @Deprecated
        public boolean getHintAvoidBackgroundClipping() {
            return (mFlags & FLAG_HINT_AVOID_BACKGROUND_CLIPPING) != 0;
        }

        /**
         * Set a hint that the screen should remain on for at least this duration when
         * this notification is displayed on the screen.
         * @param timeout The requested screen timeout in milliseconds. Can also be either
         *     {@link #SCREEN_TIMEOUT_SHORT} or {@link #SCREEN_TIMEOUT_LONG}.
         * @return this object for method chaining
         */
        @Deprecated
        public WearableExtender setHintScreenTimeout(int timeout) {
            mHintScreenTimeout = timeout;
            return this;
        }

        /**
         * Get the duration, in milliseconds, that the screen should remain on for
         * when this notification is displayed.
         * @return the duration in milliseconds if > 0, or either one of the sentinel values
         *     {@link #SCREEN_TIMEOUT_SHORT} or {@link #SCREEN_TIMEOUT_LONG}.
         */
        @Deprecated
        public int getHintScreenTimeout() {
            return mHintScreenTimeout;
        }

        /**
         * Set a hint that this notification's {@link BigPictureStyle} (if present) should be
         * converted to low-bit and displayed in ambient mode, especially useful for barcodes and
         * qr codes, as well as other simple black-and-white tickets.
         * @param hintAmbientBigPicture {@code true} to enable converstion and ambient.
         * @return this object for method chaining
         * @deprecated This feature is no longer supported.
         */
        @Deprecated
        public WearableExtender setHintAmbientBigPicture(boolean hintAmbientBigPicture) {
            setFlag(FLAG_BIG_PICTURE_AMBIENT, hintAmbientBigPicture);
            return this;
        }

        /**
         * Get a hint that this notification's {@link BigPictureStyle} (if present) should be
         * converted to low-bit and displayed in ambient mode, especially useful for barcodes and
         * qr codes, as well as other simple black-and-white tickets.
         * @return {@code true} if it should be displayed in ambient, false otherwise
         * otherwise. The default value is {@code false} if this was never set.
         * @deprecated This feature is no longer supported.
         */
        @Deprecated
        public boolean getHintAmbientBigPicture() {
            return (mFlags & FLAG_BIG_PICTURE_AMBIENT) != 0;
        }

        /**
         * Set a hint that this notification's content intent will launch an {@link Activity}
         * directly, telling the platform that it can generate the appropriate transitions.
         * @param hintContentIntentLaunchesActivity {@code true} if the content intent will launch
         * an activity and transitions should be generated, false otherwise.
         * @return this object for method chaining
         */
        public WearableExtender setHintContentIntentLaunchesActivity(
                boolean hintContentIntentLaunchesActivity) {
            setFlag(FLAG_HINT_CONTENT_INTENT_LAUNCHES_ACTIVITY, hintContentIntentLaunchesActivity);
            return this;
        }

        /**
         * Get a hint that this notification's content intent will launch an {@link Activity}
         * directly, telling the platform that it can generate the appropriate transitions
         * @return {@code true} if the content intent will launch an activity and transitions should
         * be generated, false otherwise. The default value is {@code false} if this was never set.
         */
        public boolean getHintContentIntentLaunchesActivity() {
            return (mFlags & FLAG_HINT_CONTENT_INTENT_LAUNCHES_ACTIVITY) != 0;
        }

        /**
         * Sets the dismissal id for this notification. If a notification is posted with a
         * dismissal id, then when that notification is canceled, notifications on other wearables
         * and the paired Android phone having that same dismissal id will also be canceled. See
         * <a href="{@docRoot}wear/notifications/index.html">Adding Wearable Features to
         * Notifications</a> for more information.
         * @param dismissalId the dismissal id of the notification.
         * @return this object for method chaining
         */
        public WearableExtender setDismissalId(String dismissalId) {
            mDismissalId = dismissalId;
            return this;
        }

        /**
         * Returns the dismissal id of the notification.
         * @return the dismissal id of the notification or null if it has not been set.
         */
        public String getDismissalId() {
            return mDismissalId;
        }

        /**
         * Sets a bridge tag for this notification. A bridge tag can be set for notifications
         * posted from a phone to provide finer-grained control on what notifications are bridged
         * to wearables. See <a href="{@docRoot}wear/notifications/index.html">Adding Wearable
         * Features to Notifications</a> for more information.
         * @param bridgeTag the bridge tag of the notification.
         * @return this object for method chaining
         */
        public WearableExtender setBridgeTag(String bridgeTag) {
            mBridgeTag = bridgeTag;
            return this;
        }

        /**
         * Returns the bridge tag of the notification.
         * @return the bridge tag or null if not present.
         */
        public String getBridgeTag() {
            return mBridgeTag;
        }

        private void setFlag(int mask, boolean value) {
            if (value) {
                mFlags |= mask;
            } else {
                mFlags &= ~mask;
            }
        }
    }

    /**
     * <p>Helper class to add Android Auto extensions to notifications. To create a notification
     * with car extensions:
     *
     * <ol>
     *  <li>Create an {@link Notification.Builder}, setting any desired
     *  properties.
     *  <li>Create a {@link CarExtender}.
     *  <li>Set car-specific properties using the {@code add} and {@code set} methods of
     *  {@link CarExtender}.
     *  <li>Call {@link Notification.Builder#extend(Notification.Extender)}
     *  to apply the extensions to a notification.
     * </ol>
     *
     * <pre class="prettyprint">
     * Notification notification = new Notification.Builder(context)
     *         ...
     *         .extend(new CarExtender()
     *                 .set*(...))
     *         .build();
     * </pre>
     *
     * <p>Car extensions can be accessed on an existing notification by using the
     * {@code CarExtender(Notification)} constructor, and then using the {@code get} methods
     * to access values.
     */
    public static final class CarExtender implements Extender {
        private static final String TAG = "CarExtender";

        private static final String EXTRA_CAR_EXTENDER = "android.car.EXTENSIONS";
        private static final String EXTRA_LARGE_ICON = "large_icon";
        private static final String EXTRA_CONVERSATION = "car_conversation";
        private static final String EXTRA_COLOR = "app_color";

        private Bitmap mLargeIcon;
        private UnreadConversation mUnreadConversation;
        private int mColor = Notification.COLOR_DEFAULT;

        /**
         * Create a {@link CarExtender} with default options.
         */
        public CarExtender() {
        }

        /**
         * Create a {@link CarExtender} from the CarExtender options of an existing Notification.
         *
         * @param notif The notification from which to copy options.
         */
        public CarExtender(Notification notif) {
            Bundle carBundle = notif.extras == null ?
                    null : notif.extras.getBundle(EXTRA_CAR_EXTENDER);
            if (carBundle != null) {
                mLargeIcon = carBundle.getParcelable(EXTRA_LARGE_ICON);
                mColor = carBundle.getInt(EXTRA_COLOR, Notification.COLOR_DEFAULT);

                Bundle b = carBundle.getBundle(EXTRA_CONVERSATION);
                mUnreadConversation = UnreadConversation.getUnreadConversationFromBundle(b);
            }
        }

        /**
         * Apply car extensions to a notification that is being built. This is typically called by
         * the {@link Notification.Builder#extend(Notification.Extender)}
         * method of {@link Notification.Builder}.
         */
        @Override
        public Notification.Builder extend(Notification.Builder builder) {
            Bundle carExtensions = new Bundle();

            if (mLargeIcon != null) {
                carExtensions.putParcelable(EXTRA_LARGE_ICON, mLargeIcon);
            }
            if (mColor != Notification.COLOR_DEFAULT) {
                carExtensions.putInt(EXTRA_COLOR, mColor);
            }

            if (mUnreadConversation != null) {
                Bundle b = mUnreadConversation.getBundleForUnreadConversation();
                carExtensions.putBundle(EXTRA_CONVERSATION, b);
            }

            builder.getExtras().putBundle(EXTRA_CAR_EXTENDER, carExtensions);
            return builder;
        }

        /**
         * Sets the accent color to use when Android Auto presents the notification.
         *
         * Android Auto uses the color set with {@link Notification.Builder#setColor(int)}
         * to accent the displayed notification. However, not all colors are acceptable in an
         * automotive setting. This method can be used to override the color provided in the
         * notification in such a situation.
         */
        public CarExtender setColor(@ColorInt int color) {
            mColor = color;
            return this;
        }

        /**
         * Gets the accent color.
         *
         * @see #setColor
         */
        @ColorInt
        public int getColor() {
            return mColor;
        }

        /**
         * Sets the large icon of the car notification.
         *
         * If no large icon is set in the extender, Android Auto will display the icon
         * specified by {@link Notification.Builder#setLargeIcon(android.graphics.Bitmap)}
         *
         * @param largeIcon The large icon to use in the car notification.
         * @return This object for method chaining.
         */
        public CarExtender setLargeIcon(Bitmap largeIcon) {
            mLargeIcon = largeIcon;
            return this;
        }

        /**
         * Gets the large icon used in this car notification, or null if no icon has been set.
         *
         * @return The large icon for the car notification.
         * @see CarExtender#setLargeIcon
         */
        public Bitmap getLargeIcon() {
            return mLargeIcon;
        }

        /**
         * Sets the unread conversation in a message notification.
         *
         * @param unreadConversation The unread part of the conversation this notification conveys.
         * @return This object for method chaining.
         */
        public CarExtender setUnreadConversation(UnreadConversation unreadConversation) {
            mUnreadConversation = unreadConversation;
            return this;
        }

        /**
         * Returns the unread conversation conveyed by this notification.
         * @see #setUnreadConversation(UnreadConversation)
         */
        public UnreadConversation getUnreadConversation() {
            return mUnreadConversation;
        }

        /**
         * A class which holds the unread messages from a conversation.
         */
        public static class UnreadConversation {
            private static final String KEY_AUTHOR = "author";
            private static final String KEY_TEXT = "text";
            private static final String KEY_MESSAGES = "messages";
            private static final String KEY_REMOTE_INPUT = "remote_input";
            private static final String KEY_ON_REPLY = "on_reply";
            private static final String KEY_ON_READ = "on_read";
            private static final String KEY_PARTICIPANTS = "participants";
            private static final String KEY_TIMESTAMP = "timestamp";

            private final String[] mMessages;
            private final RemoteInput mRemoteInput;
            private final PendingIntent mReplyPendingIntent;
            private final PendingIntent mReadPendingIntent;
            private final String[] mParticipants;
            private final long mLatestTimestamp;

            UnreadConversation(String[] messages, RemoteInput remoteInput,
                    PendingIntent replyPendingIntent, PendingIntent readPendingIntent,
                    String[] participants, long latestTimestamp) {
                mMessages = messages;
                mRemoteInput = remoteInput;
                mReadPendingIntent = readPendingIntent;
                mReplyPendingIntent = replyPendingIntent;
                mParticipants = participants;
                mLatestTimestamp = latestTimestamp;
            }

            /**
             * Gets the list of messages conveyed by this notification.
             */
            public String[] getMessages() {
                return mMessages;
            }

            /**
             * Gets the remote input that will be used to convey the response to a message list, or
             * null if no such remote input exists.
             */
            public RemoteInput getRemoteInput() {
                return mRemoteInput;
            }

            /**
             * Gets the pending intent that will be triggered when the user replies to this
             * notification.
             */
            public PendingIntent getReplyPendingIntent() {
                return mReplyPendingIntent;
            }

            /**
             * Gets the pending intent that Android Auto will send after it reads aloud all messages
             * in this object's message list.
             */
            public PendingIntent getReadPendingIntent() {
                return mReadPendingIntent;
            }

            /**
             * Gets the participants in the conversation.
             */
            public String[] getParticipants() {
                return mParticipants;
            }

            /**
             * Gets the firs participant in the conversation.
             */
            public String getParticipant() {
                return mParticipants.length > 0 ? mParticipants[0] : null;
            }

            /**
             * Gets the timestamp of the conversation.
             */
            public long getLatestTimestamp() {
                return mLatestTimestamp;
            }

            Bundle getBundleForUnreadConversation() {
                Bundle b = new Bundle();
                String author = null;
                if (mParticipants != null && mParticipants.length > 1) {
                    author = mParticipants[0];
                }
                Parcelable[] messages = new Parcelable[mMessages.length];
                for (int i = 0; i < messages.length; i++) {
                    Bundle m = new Bundle();
                    m.putString(KEY_TEXT, mMessages[i]);
                    m.putString(KEY_AUTHOR, author);
                    messages[i] = m;
                }
                b.putParcelableArray(KEY_MESSAGES, messages);
                if (mRemoteInput != null) {
                    b.putParcelable(KEY_REMOTE_INPUT, mRemoteInput);
                }
                b.putParcelable(KEY_ON_REPLY, mReplyPendingIntent);
                b.putParcelable(KEY_ON_READ, mReadPendingIntent);
                b.putStringArray(KEY_PARTICIPANTS, mParticipants);
                b.putLong(KEY_TIMESTAMP, mLatestTimestamp);
                return b;
            }

            static UnreadConversation getUnreadConversationFromBundle(Bundle b) {
                if (b == null) {
                    return null;
                }
                Parcelable[] parcelableMessages = b.getParcelableArray(KEY_MESSAGES);
                String[] messages = null;
                if (parcelableMessages != null) {
                    String[] tmp = new String[parcelableMessages.length];
                    boolean success = true;
                    for (int i = 0; i < tmp.length; i++) {
                        if (!(parcelableMessages[i] instanceof Bundle)) {
                            success = false;
                            break;
                        }
                        tmp[i] = ((Bundle) parcelableMessages[i]).getString(KEY_TEXT);
                        if (tmp[i] == null) {
                            success = false;
                            break;
                        }
                    }
                    if (success) {
                        messages = tmp;
                    } else {
                        return null;
                    }
                }

                PendingIntent onRead = b.getParcelable(KEY_ON_READ);
                PendingIntent onReply = b.getParcelable(KEY_ON_REPLY);

                RemoteInput remoteInput = b.getParcelable(KEY_REMOTE_INPUT);

                String[] participants = b.getStringArray(KEY_PARTICIPANTS);
                if (participants == null || participants.length != 1) {
                    return null;
                }

                return new UnreadConversation(messages,
                        remoteInput,
                        onReply,
                        onRead,
                        participants, b.getLong(KEY_TIMESTAMP));
            }
        };

        /**
         * Builder class for {@link CarExtender.UnreadConversation} objects.
         */
        public static class Builder {
            private final List<String> mMessages = new ArrayList<String>();
            private final String mParticipant;
            private RemoteInput mRemoteInput;
            private PendingIntent mReadPendingIntent;
            private PendingIntent mReplyPendingIntent;
            private long mLatestTimestamp;

            /**
             * Constructs a new builder for {@link CarExtender.UnreadConversation}.
             *
             * @param name The name of the other participant in the conversation.
             */
            public Builder(String name) {
                mParticipant = name;
            }

            /**
             * Appends a new unread message to the list of messages for this conversation.
             *
             * The messages should be added from oldest to newest.
             *
             * @param message The text of the new unread message.
             * @return This object for method chaining.
             */
            public Builder addMessage(String message) {
                mMessages.add(message);
                return this;
            }

            /**
             * Sets the pending intent and remote input which will convey the reply to this
             * notification.
             *
             * @param pendingIntent The pending intent which will be triggered on a reply.
             * @param remoteInput The remote input parcelable which will carry the reply.
             * @return This object for method chaining.
             *
             * @see CarExtender.UnreadConversation#getRemoteInput
             * @see CarExtender.UnreadConversation#getReplyPendingIntent
             */
            public Builder setReplyAction(
                    PendingIntent pendingIntent, RemoteInput remoteInput) {
                mRemoteInput = remoteInput;
                mReplyPendingIntent = pendingIntent;

                return this;
            }

            /**
             * Sets the pending intent that will be sent once the messages in this notification
             * are read.
             *
             * @param pendingIntent The pending intent to use.
             * @return This object for method chaining.
             */
            public Builder setReadPendingIntent(PendingIntent pendingIntent) {
                mReadPendingIntent = pendingIntent;
                return this;
            }

            /**
             * Sets the timestamp of the most recent message in an unread conversation.
             *
             * If a messaging notification has been posted by your application and has not
             * yet been cancelled, posting a later notification with the same id and tag
             * but without a newer timestamp may result in Android Auto not displaying a
             * heads up notification for the later notification.
             *
             * @param timestamp The timestamp of the most recent message in the conversation.
             * @return This object for method chaining.
             */
            public Builder setLatestTimestamp(long timestamp) {
                mLatestTimestamp = timestamp;
                return this;
            }

            /**
             * Builds a new unread conversation object.
             *
             * @return The new unread conversation object.
             */
            public UnreadConversation build() {
                String[] messages = mMessages.toArray(new String[mMessages.size()]);
                String[] participants = { mParticipant };
                return new UnreadConversation(messages, mRemoteInput, mReplyPendingIntent,
                        mReadPendingIntent, participants, mLatestTimestamp);
            }
        }
    }

    /**
     * <p>Helper class to add Android TV extensions to notifications. To create a notification
     * with a TV extension:
     *
     * <ol>
     *  <li>Create an {@link Notification.Builder}, setting any desired properties.
     *  <li>Create a {@link TvExtender}.
     *  <li>Set TV-specific properties using the {@code set} methods of
     *  {@link TvExtender}.
     *  <li>Call {@link Notification.Builder#extend(Notification.Extender)}
     *  to apply the extension to a notification.
     * </ol>
     *
     * <pre class="prettyprint">
     * Notification notification = new Notification.Builder(context)
     *         ...
     *         .extend(new TvExtender()
     *                 .set*(...))
     *         .build();
     * </pre>
     *
     * <p>TV extensions can be accessed on an existing notification by using the
     * {@code TvExtender(Notification)} constructor, and then using the {@code get} methods
     * to access values.
     *
     * @hide
     */
    @SystemApi
    public static final class TvExtender implements Extender {
        private static final String TAG = "TvExtender";

        private static final String EXTRA_TV_EXTENDER = "android.tv.EXTENSIONS";
        private static final String EXTRA_FLAGS = "flags";
        private static final String EXTRA_CONTENT_INTENT = "content_intent";
        private static final String EXTRA_DELETE_INTENT = "delete_intent";
        private static final String EXTRA_CHANNEL_ID = "channel_id";
        private static final String EXTRA_SUPPRESS_SHOW_OVER_APPS = "suppressShowOverApps";

        // Flags bitwise-ored to mFlags
        private static final int FLAG_AVAILABLE_ON_TV = 0x1;

        private int mFlags;
        private String mChannelId;
        private PendingIntent mContentIntent;
        private PendingIntent mDeleteIntent;
        private boolean mSuppressShowOverApps;

        /**
         * Create a {@link TvExtender} with default options.
         */
        public TvExtender() {
            mFlags = FLAG_AVAILABLE_ON_TV;
        }

        /**
         * Create a {@link TvExtender} from the TvExtender options of an existing Notification.
         *
         * @param notif The notification from which to copy options.
         */
        public TvExtender(Notification notif) {
            Bundle bundle = notif.extras == null ?
                null : notif.extras.getBundle(EXTRA_TV_EXTENDER);
            if (bundle != null) {
                mFlags = bundle.getInt(EXTRA_FLAGS);
                mChannelId = bundle.getString(EXTRA_CHANNEL_ID);
                mSuppressShowOverApps = bundle.getBoolean(EXTRA_SUPPRESS_SHOW_OVER_APPS);
                mContentIntent = bundle.getParcelable(EXTRA_CONTENT_INTENT);
                mDeleteIntent = bundle.getParcelable(EXTRA_DELETE_INTENT);
            }
        }

        /**
         * Apply a TV extension to a notification that is being built. This is typically called by
         * the {@link Notification.Builder#extend(Notification.Extender)}
         * method of {@link Notification.Builder}.
         */
        @Override
        public Notification.Builder extend(Notification.Builder builder) {
            Bundle bundle = new Bundle();

            bundle.putInt(EXTRA_FLAGS, mFlags);
            bundle.putString(EXTRA_CHANNEL_ID, mChannelId);
            bundle.putBoolean(EXTRA_SUPPRESS_SHOW_OVER_APPS, mSuppressShowOverApps);
            if (mContentIntent != null) {
                bundle.putParcelable(EXTRA_CONTENT_INTENT, mContentIntent);
            }

            if (mDeleteIntent != null) {
                bundle.putParcelable(EXTRA_DELETE_INTENT, mDeleteIntent);
            }

            builder.getExtras().putBundle(EXTRA_TV_EXTENDER, bundle);
            return builder;
        }

        /**
         * Returns true if this notification should be shown on TV. This method return true
         * if the notification was extended with a TvExtender.
         */
        public boolean isAvailableOnTv() {
            return (mFlags & FLAG_AVAILABLE_ON_TV) != 0;
        }

        /**
         * Specifies the channel the notification should be delivered on when shown on TV.
         * It can be different from the channel that the notification is delivered to when
         * posting on a non-TV device.
         */
        public TvExtender setChannel(String channelId) {
            mChannelId = channelId;
            return this;
        }

        /**
         * Specifies the channel the notification should be delivered on when shown on TV.
         * It can be different from the channel that the notification is delivered to when
         * posting on a non-TV device.
         */
        public TvExtender setChannelId(String channelId) {
            mChannelId = channelId;
            return this;
        }

        /** @removed */
        @Deprecated
        public String getChannel() {
            return mChannelId;
        }

        /**
         * Returns the id of the channel this notification posts to on TV.
         */
        public String getChannelId() {
            return mChannelId;
        }

        /**
         * Supplies a {@link PendingIntent} to be sent when the notification is selected on TV.
         * If provided, it is used instead of the content intent specified
         * at the level of Notification.
         */
        public TvExtender setContentIntent(PendingIntent intent) {
            mContentIntent = intent;
            return this;
        }

        /**
         * Returns the TV-specific content intent.  If this method returns null, the
         * main content intent on the notification should be used.
         *
         * @see {@link Notification#contentIntent}
         */
        public PendingIntent getContentIntent() {
            return mContentIntent;
        }

        /**
         * Supplies a {@link PendingIntent} to send when the notification is cleared explicitly
         * by the user on TV.  If provided, it is used instead of the delete intent specified
         * at the level of Notification.
         */
        public TvExtender setDeleteIntent(PendingIntent intent) {
            mDeleteIntent = intent;
            return this;
        }

        /**
         * Returns the TV-specific delete intent.  If this method returns null, the
         * main delete intent on the notification should be used.
         *
         * @see {@link Notification#deleteIntent}
         */
        public PendingIntent getDeleteIntent() {
            return mDeleteIntent;
        }

        /**
         * Specifies whether this notification should suppress showing a message over top of apps
         * outside of the launcher.
         */
        public TvExtender setSuppressShowOverApps(boolean suppress) {
            mSuppressShowOverApps = suppress;
            return this;
        }

        /**
         * Returns true if this notification should not show messages over top of apps
         * outside of the launcher.
         */
        public boolean getSuppressShowOverApps() {
            return mSuppressShowOverApps;
        }
    }

    /**
     * Get an array of Notification objects from a parcelable array bundle field.
     * Update the bundle to have a typed array so fetches in the future don't need
     * to do an array copy.
     */
    private static Notification[] getNotificationArrayFromBundle(Bundle bundle, String key) {
        Parcelable[] array = bundle.getParcelableArray(key);
        if (array instanceof Notification[] || array == null) {
            return (Notification[]) array;
        }
        Notification[] typedArray = Arrays.copyOf(array, array.length,
                Notification[].class);
        bundle.putParcelableArray(key, typedArray);
        return typedArray;
    }

    private static class BuilderRemoteViews extends RemoteViews {
        public BuilderRemoteViews(Parcel parcel) {
            super(parcel);
        }

        public BuilderRemoteViews(ApplicationInfo appInfo, int layoutId) {
            super(appInfo, layoutId);
        }

        @Override
        public BuilderRemoteViews clone() {
            Parcel p = Parcel.obtain();
            writeToParcel(p, 0);
            p.setDataPosition(0);
            BuilderRemoteViews brv = new BuilderRemoteViews(p);
            p.recycle();
            return brv;
        }
    }

    /**
     * A result object where information about the template that was created is saved.
     */
    private static class TemplateBindResult {
        int mIconMarginEnd;
        boolean mRightIconContainerVisible;

        /**
         * Get the margin end that needs to be added to any fields that may overlap
         * with the right actions.
         */
        public int getIconMarginEnd() {
            return mIconMarginEnd;
        }

        /**
         * Is the icon container visible on the right size because of the reply button or the
         * right icon.
         */
        public boolean isRightIconContainerVisible() {
            return mRightIconContainerVisible;
        }

        public void setIconMarginEnd(int iconMarginEnd) {
            this.mIconMarginEnd = iconMarginEnd;
        }

        public void setRightIconContainerVisible(boolean iconContainerVisible) {
            mRightIconContainerVisible = iconContainerVisible;
        }
    }

    private static class StandardTemplateParams {
        boolean hasProgress = true;
        CharSequence title;
        CharSequence text;
        CharSequence headerTextSecondary;
        CharSequence summaryText;
        int maxRemoteInputHistory = Style.MAX_REMOTE_INPUT_HISTORY_LINES;
        boolean hideLargeIcon;
        boolean hideReplyIcon;
        boolean allowColorization  = true;
        boolean forceDefaultColor = false;

        final StandardTemplateParams reset() {
            hasProgress = true;
            title = null;
            text = null;
            summaryText = null;
            headerTextSecondary = null;
            maxRemoteInputHistory = Style.MAX_REMOTE_INPUT_HISTORY_LINES;
            allowColorization = true;
            forceDefaultColor = false;
            return this;
        }

        final StandardTemplateParams hasProgress(boolean hasProgress) {
            this.hasProgress = hasProgress;
            return this;
        }

        final StandardTemplateParams title(CharSequence title) {
            this.title = title;
            return this;
        }

        final StandardTemplateParams text(CharSequence text) {
            this.text = text;
            return this;
        }

        final StandardTemplateParams summaryText(CharSequence text) {
            this.summaryText = text;
            return this;
        }

        final StandardTemplateParams headerTextSecondary(CharSequence text) {
            this.headerTextSecondary = text;
            return this;
        }

        final StandardTemplateParams hideLargeIcon(boolean hideLargeIcon) {
            this.hideLargeIcon = hideLargeIcon;
            return this;
        }

        final StandardTemplateParams hideReplyIcon(boolean hideReplyIcon) {
            this.hideReplyIcon = hideReplyIcon;
            return this;
        }

        final StandardTemplateParams disallowColorization() {
            this.allowColorization = false;
            return this;
        }

        final StandardTemplateParams forceDefaultColor() {
            this.forceDefaultColor = true;
            return this;
        }

        final StandardTemplateParams fillTextsFrom(Builder b) {
            Bundle extras = b.mN.extras;
            this.title = b.processLegacyText(extras.getCharSequence(EXTRA_TITLE));

            CharSequence text = extras.getCharSequence(EXTRA_BIG_TEXT);
            if (TextUtils.isEmpty(text)) {
                text = extras.getCharSequence(EXTRA_TEXT);
            }
            this.text = b.processLegacyText(text);
            this.summaryText = extras.getCharSequence(EXTRA_SUB_TEXT);
            return this;
        }

        /**
         * Set the maximum lines of remote input history lines allowed.
         * @param maxRemoteInputHistory The number of lines.
         * @return The builder for method chaining.
         */
        public StandardTemplateParams setMaxRemoteInputHistory(int maxRemoteInputHistory) {
            this.maxRemoteInputHistory = maxRemoteInputHistory;
            return this;
        }
    }
}
