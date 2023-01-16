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

import static android.annotation.Dimension.DP;
import static android.app.admin.DevicePolicyResources.Drawables.Source.NOTIFICATION;
import static android.app.admin.DevicePolicyResources.Drawables.Style.SOLID_COLORED;
import static android.app.admin.DevicePolicyResources.Drawables.WORK_PROFILE_ICON;
import static android.app.admin.DevicePolicyResources.UNDEFINED;
import static android.graphics.drawable.Icon.TYPE_URI;
import static android.graphics.drawable.Icon.TYPE_URI_ADAPTIVE_BITMAP;

import static java.util.Objects.requireNonNull;

import android.annotation.ColorInt;
import android.annotation.ColorRes;
import android.annotation.DimenRes;
import android.annotation.Dimension;
import android.annotation.DrawableRes;
import android.annotation.IdRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.StringRes;
import android.annotation.StyleableRes;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.admin.DevicePolicyManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
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
import android.os.UserManager;
import android.provider.Settings;
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
import android.util.TypedValue;
import android.util.proto.ProtoOutputStream;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.contentcapture.ContentCaptureContext;
import android.widget.ProgressBar;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ContrastColorUtil;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
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
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            FOREGROUND_SERVICE_DEFAULT,
            FOREGROUND_SERVICE_IMMEDIATE,
            FOREGROUND_SERVICE_DEFERRED
    })
    public @interface ServiceNotificationPolicy {};

    /**
     * If the Notification associated with starting a foreground service has been
     * built using setForegroundServiceBehavior() with this behavior, display of
     * the notification will usually be suppressed for a short time to avoid visual
     * disturbances to the user.
     * @see Notification.Builder#setForegroundServiceBehavior(int)
     * @see #FOREGROUND_SERVICE_IMMEDIATE
     * @see #FOREGROUND_SERVICE_DEFERRED
     */
    public static final @ServiceNotificationPolicy int FOREGROUND_SERVICE_DEFAULT = 0;

    /**
     * If the Notification associated with starting a foreground service has been
     * built using setForegroundServiceBehavior() with this behavior, display of
     * the notification will be immediate even if the default behavior would be
     * to defer visibility for a short time.
     * @see Notification.Builder#setForegroundServiceBehavior(int)
     * @see #FOREGROUND_SERVICE_DEFAULT
     * @see #FOREGROUND_SERVICE_DEFERRED
     */
    public static final @ServiceNotificationPolicy int FOREGROUND_SERVICE_IMMEDIATE = 1;

    /**
     * If the Notification associated with starting a foreground service has been
     * built using setForegroundServiceBehavior() with this behavior, display of
     * the notification will usually be suppressed for a short time to avoid visual
     * disturbances to the user.
     * @see Notification.Builder#setForegroundServiceBehavior(int)
     * @see #FOREGROUND_SERVICE_DEFAULT
     * @see #FOREGROUND_SERVICE_IMMEDIATE
     */
    public static final @ServiceNotificationPolicy int FOREGROUND_SERVICE_DEFERRED = 2;

    @ServiceNotificationPolicy
    private int mFgsDeferBehavior;

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
    private static final int MAX_CHARSEQUENCE_LENGTH = 1024;

    /**
     * Maximum entries of reply text that are accepted by Builder and friends.
     */
    private static final int MAX_REPLY_HISTORY = 5;

    /**
     * Maximum aspect ratio of the large icon. 16:9
     */
    private static final float MAX_LARGE_ICON_ASPECT_RATIO = 16f / 9f;

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
     * Stack</a> document.  In particular, make sure to read the
     * <a href="{@docRoot}/training/notify-user/navigation">Start
     * an Activity from a Notification</a> page for the correct ways to launch an application from a
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
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_heads_up_base);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_base);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_picture);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_text);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_inbox);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_messaging);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_messaging);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_conversation);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_media);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_media);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_call);
        STANDARD_LAYOUTS.add(R.layout.notification_template_material_big_call);
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
     *
     * Note that using this flag will stop any ongoing alerting behaviour such
     * as sound, vibration or blinking notification LED.
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

    /**
     * Bit to be bitswised-ored into the {@link #flags} field that should be
     * set by the system if this notification is showing as a bubble.
     *
     * Applications cannot set this flag directly; they should instead call
     * {@link Notification.Builder#setBubbleMetadata(BubbleMetadata)} to
     * request that a notification be displayed as a bubble, and then check
     * this flag to see whether that request was honored by the system.
     */
    public static final int FLAG_BUBBLE = 0x00001000;

    private static final List<Class<? extends Style>> PLATFORM_STYLE_CLASSES = Arrays.asList(
            BigTextStyle.class, BigPictureStyle.class, InboxStyle.class, MediaStyle.class,
            DecoratedCustomViewStyle.class, DecoratedMediaCustomViewStyle.class,
            MessagingStyle.class, CallStyle.class);

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {FLAG_SHOW_LIGHTS, FLAG_ONGOING_EVENT,
            FLAG_INSISTENT, FLAG_ONLY_ALERT_ONCE,
            FLAG_AUTO_CANCEL, FLAG_NO_CLEAR, FLAG_FOREGROUND_SERVICE, FLAG_HIGH_PRIORITY,
            FLAG_LOCAL_ONLY, FLAG_GROUP_SUMMARY, FLAG_AUTOGROUP_SUMMARY, FLAG_BUBBLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface NotificationFlags{};

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
     * @hide
     */
    @IntDef(prefix = "VISIBILITY_", value = {
            VISIBILITY_PUBLIC,
            VISIBILITY_PRIVATE,
            VISIBILITY_SECRET,
            NotificationManager.VISIBILITY_NO_OVERRIDE
    })
    public @interface NotificationVisibilityOverride{};

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
     * Notification category: tracking a user's workout.
     */
    public static final String CATEGORY_WORKOUT = "workout";

    /**
     * Notification category: temporarily sharing location.
     */
    public static final String CATEGORY_LOCATION_SHARING = "location_sharing";

    /**
     * Notification category: running stopwatch.
     */
    public static final String CATEGORY_STOPWATCH = "stopwatch";

    /**
     * Notification category: missed call.
     */
    public static final String CATEGORY_MISSED_CALL = "missed_call";

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
     * Token identifying the notification that is applying doze/bgcheck allowlisting to the
     * pending intents inside of it, so only those will get the behavior.
     *
     * @hide
     */
    private IBinder mAllowlistToken;

    /**
     * Must be set by a process to start associating tokens with Notification objects
     * coming in to it.  This is set by NotificationManagerService.
     *
     * @hide
     */
    static public IBinder processAllowlistToken;

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
     * {@link #extras} key: this is a remote input history which can include media messages
     * in addition to text, as supplied to
     * {@link Builder#setRemoteInputHistory(RemoteInputHistoryItem[])} or
     * {@link Builder#setRemoteInputHistory(CharSequence[])}.
     *
     * SystemUI can populate this through
     * {@link Builder#setRemoteInputHistory(RemoteInputHistoryItem[])} with the most recent inputs
     * that have been sent through a {@link RemoteInput} of this Notification. These items can
     * represent either media content (specified by a URI and a MIME type) or a text message
     * (described by a CharSequence).
     *
     * To maintain compatibility, this can also be set by apps with
     * {@link Builder#setRemoteInputHistory(CharSequence[])}, which will create a
     * {@link RemoteInputHistoryItem} for each of the provided text-only messages.
     *
     * The extra with this key is of type {@link RemoteInputHistoryItem[]} and contains the most
     * recent entry at the 0 index, the second most recent at the 1 index, etc.
     *
     * @see Builder#setRemoteInputHistory(RemoteInputHistoryItem[])
     * @hide
     */
    public static final String EXTRA_REMOTE_INPUT_HISTORY_ITEMS = "android.remoteInputHistoryItems";

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
     * {@link #extras} key: this is an {@link Icon} of an image to be
     * shown in {@link BigPictureStyle} expanded notifications, supplied to
     * {@link BigPictureStyle#bigPicture(Icon)}.
     */
    public static final String EXTRA_PICTURE_ICON = "android.pictureIcon";

    /**
     * {@link #extras} key: this is a content description of the big picture supplied from
     * {@link BigPictureStyle#bigPicture(Bitmap)}, supplied to
     * {@link BigPictureStyle#setContentDescription(CharSequence)}.
     */
    public static final String EXTRA_PICTURE_CONTENT_DESCRIPTION =
            "android.pictureContentDescription";

    /**
     * {@link #extras} key: this is a boolean to indicate that the
     * {@link BigPictureStyle#bigPicture(Bitmap) big picture} is to be shown in the collapsed state
     * of a {@link BigPictureStyle} notification.  This will replace a
     * {@link Builder#setLargeIcon(Icon) large icon} in that state if one was provided.
     */
    public static final String EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED =
            "android.showBigPictureWhenCollapsed";

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
     * {@link #extras} key: A {@code CharSequence} name of a remote device used for a media session
     * associated with a {@link Notification.MediaStyle} notification. This will show in the media
     * controls output switcher instead of the local device name.
     * @hide
     */
    @TestApi
    public static final String EXTRA_MEDIA_REMOTE_DEVICE = "android.mediaRemoteDevice";

    /**
     * {@link #extras} key: A {@code int} resource ID for an icon that should show in the output
     * switcher of the media controls for a {@link Notification.MediaStyle} notification.
     * @hide
     */
    @TestApi
    public static final String EXTRA_MEDIA_REMOTE_ICON = "android.mediaRemoteIcon";

    /**
     * {@link #extras} key: A {@code PendingIntent} that will replace the default action for the
     * media controls output switcher chip, associated with a {@link Notification.MediaStyle}
     * notification. This should launch an activity.
     * @hide
     */
    @TestApi
    public static final String EXTRA_MEDIA_REMOTE_INTENT = "android.mediaRemoteIntent";

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

    /** @hide */
    public static final String EXTRA_CONVERSATION_ICON = "android.conversationIcon";

    /** @hide */
    public static final String EXTRA_CONVERSATION_UNREAD_MESSAGE_COUNT =
            "android.conversationUnreadMessageCount";

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
     * {@link #extras} key: the type of call represented by the
     * {@link android.app.Notification.CallStyle} notification. This extra is an int.
     */
    public static final String EXTRA_CALL_TYPE = "android.callType";

    /**
     * {@link #extras} key: whether the  {@link android.app.Notification.CallStyle} notification
     * is for a call that will activate video when answered. This extra is a boolean.
     */
    public static final String EXTRA_CALL_IS_VIDEO = "android.callIsVideo";

    /**
     * {@link #extras} key: the person to be displayed as calling for the
     * {@link android.app.Notification.CallStyle} notification. This extra is a {@link Person}.
     */
    public static final String EXTRA_CALL_PERSON = "android.callPerson";

    /**
     * {@link #extras} key: the icon to be displayed as a verification status of the caller on a
     * {@link android.app.Notification.CallStyle} notification. This extra is an {@link Icon}.
     */
    public static final String EXTRA_VERIFICATION_ICON = "android.verificationIcon";

    /**
     * {@link #extras} key: the text to be displayed as a verification status of the caller on a
     * {@link android.app.Notification.CallStyle} notification. This extra is a
     * {@link CharSequence}.
     */
    public static final String EXTRA_VERIFICATION_TEXT = "android.verificationText";

    /**
     * {@link #extras} key: the intent to be sent when the users answers a
     * {@link android.app.Notification.CallStyle} notification. This extra is a
     * {@link PendingIntent}.
     */
    public static final String EXTRA_ANSWER_INTENT = "android.answerIntent";

    /**
     * {@link #extras} key: the intent to be sent when the users declines a
     * {@link android.app.Notification.CallStyle} notification. This extra is a
     * {@link PendingIntent}.
     */
    public static final String EXTRA_DECLINE_INTENT = "android.declineIntent";

    /**
     * {@link #extras} key: the intent to be sent when the users hangs up a
     * {@link android.app.Notification.CallStyle} notification. This extra is a
     * {@link PendingIntent}.
     */
    public static final String EXTRA_HANG_UP_INTENT = "android.hangUpIntent";

    /**
     * {@link #extras} key: the color used as a hint for the Answer action button of a
     * {@link android.app.Notification.CallStyle} notification. This extra is a {@link ColorInt}.
     */
    public static final String EXTRA_ANSWER_COLOR = "android.answerColor";

    /**
     * {@link #extras} key: the color used as a hint for the Decline or Hang Up action button of a
     * {@link android.app.Notification.CallStyle} notification. This extra is a {@link ColorInt}.
     */
    public static final String EXTRA_DECLINE_COLOR = "android.declineColor";

    /**
     * {@link #extras} key: whether the notification should be colorized as
     * supplied to {@link Builder#setColorized(boolean)}.
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
    private LocusId mLocusId;
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
     * <p>
     * As of Android {@link android.os.Build.VERSION_CODES#S}, apps targeting API level {@link
     * android.os.Build.VERSION_CODES#S} or higher won't be able to start activities while
     * processing broadcast receivers or services in response to notification action clicks. To
     * launch an activity in those cases, provide a {@link PendingIntent} for the activity itself.
     */
    public static class Action implements Parcelable {
        /**
         * {@link #extras} key: Keys to a {@link Parcelable} {@link ArrayList} of
         * {@link RemoteInput}s.
         *
         * This is intended for {@link RemoteInput}s that only accept data, meaning
         * {@link RemoteInput#getAllowFreeFormInput} is false, {@link RemoteInput#getChoices}
         * is null or empty, and {@link RemoteInput#getAllowedDataTypes} is non-null and not
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

        /**
         * {@code SemanticAction}: Mark the conversation associated with the notification as a
         * priority. Note that this is only for use by the notification assistant services. The
         * type will be ignored for actions an app adds to its own notifications.
         * @hide
         */
        @SystemApi
        public static final int SEMANTIC_ACTION_MARK_CONVERSATION_AS_PRIORITY = 11;

        /**
         * {@code SemanticAction}: Mark content as a potential phishing attempt.
         * Note that this is only for use by the notification assistant services. The type will
         * be ignored for actions an app adds to its own notifications.
         * @hide
         */
        @SystemApi
        public static final int SEMANTIC_ACTION_CONVERSATION_IS_PHISHING = 12;

        private final Bundle mExtras;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        private Icon mIcon;
        private final RemoteInput[] mRemoteInputs;
        private boolean mAllowGeneratedReplies = true;
        private final @SemanticAction int mSemanticAction;
        private final boolean mIsContextual;
        private boolean mAuthenticationRequired;

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
            mAuthenticationRequired = in.readInt() == 1;
        }

        /**
         * @deprecated Use {@link android.app.Notification.Action.Builder}.
         */
        @Deprecated
        public Action(int icon, CharSequence title, @Nullable PendingIntent intent) {
            this(Icon.createWithResource("", icon), title, intent, new Bundle(), null, true,
                    SEMANTIC_ACTION_NONE, false /* isContextual */, false /* requireAuth */);
        }

        /** Keep in sync with {@link Notification.Action.Builder#Builder(Action)}! */
        private Action(Icon icon, CharSequence title, PendingIntent intent, Bundle extras,
                RemoteInput[] remoteInputs, boolean allowGeneratedReplies,
                @SemanticAction int semanticAction, boolean isContextual,
                boolean requireAuth) {
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
            this.mAuthenticationRequired = requireAuth;
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
            return getParcelableArrayFromBundle(mExtras, EXTRA_DATA_ONLY_INPUTS, RemoteInput.class);
        }

        /**
         * Returns whether the OS should only send this action's {@link PendingIntent} on an
         * unlocked device.
         *
         * If the device is locked when the action is invoked, the OS should show the keyguard and
         * require successful authentication before invoking the intent.
         */
        public boolean isAuthenticationRequired() {
            return mAuthenticationRequired;
        }

        /**
         * Builder class for {@link Action} objects.
         */
        public static final class Builder {
            @Nullable private final Icon mIcon;
            @Nullable private final CharSequence mTitle;
            @Nullable private final PendingIntent mIntent;
            private boolean mAllowGeneratedReplies = true;
            @NonNull private final Bundle mExtras;
            @Nullable private ArrayList<RemoteInput> mRemoteInputs;
            private @SemanticAction int mSemanticAction;
            private boolean mIsContextual;
            private boolean mAuthenticationRequired;

            /**
             * Construct a new builder for {@link Action} object.
             * <p>As of Android {@link android.os.Build.VERSION_CODES#N},
             * action button icons will not be displayed on action buttons, but are still required
             * and are available to
             * {@link android.service.notification.NotificationListenerService notification listeners},
             * which may display them in other contexts, for example on a wearable device.
             * @param icon icon to show for this action
             * @param title the title of the action
             * @param intent the {@link PendingIntent} to fire when users trigger this action. May
             * be null, in which case the action may be rendered in a disabled presentation by the
             * system UI.
             */
            @Deprecated
            public Builder(int icon, CharSequence title, @Nullable PendingIntent intent) {
                this(Icon.createWithResource("", icon), title, intent);
            }

            /**
             * Construct a new builder for {@link Action} object.
             *
             * <p>As of Android {@link android.os.Build.VERSION_CODES#S}, apps targeting API level
             * {@link android.os.Build.VERSION_CODES#S} or higher won't be able to start activities
             * while processing broadcast receivers or services in response to notification action
             * clicks. To launch an activity in those cases, provide a {@link PendingIntent} for the
             * activity itself.
             *
             * <p>How an Action is displayed, including whether the {@code icon}, {@code text}, or
             * both are displayed or required, depends on where and how the action is used, and the
             * {@link Style} applied to the Notification.
             *
             * <p>As of Android {@link android.os.Build.VERSION_CODES#N}, action button icons
             * will not be displayed on action buttons, but are still required and are available
             * to {@link android.service.notification.NotificationListenerService notification
             * listeners}, which may display them in other contexts, for example on a wearable
             * device.
             *
             * <p>When the {@code title} is a {@link android.text.Spanned}, any colors set by a
             * {@link ForegroundColorSpan} or {@link TextAppearanceSpan} may be removed or displayed
             * with an altered in luminance to ensure proper contrast within the Notification.
             *
             * @param icon icon to show for this action
             * @param title the title of the action
             * @param intent the {@link PendingIntent} to fire when users trigger this action. May
             * be null, in which case the action may be rendered in a disabled presentation by the
             * system UI.
             */
            public Builder(Icon icon, CharSequence title, @Nullable PendingIntent intent) {
                this(icon, title, intent, new Bundle(), null, true, SEMANTIC_ACTION_NONE, false);
            }

            /**
             * Construct a new builder for {@link Action} object using the fields from an
             * {@link Action}.
             * @param action the action to read fields from.
             */
            public Builder(Action action) {
                this(action.getIcon(), action.title, action.actionIntent,
                        new Bundle(action.mExtras), action.getRemoteInputs(),
                        action.getAllowGeneratedReplies(), action.getSemanticAction(),
                        action.isAuthenticationRequired());
            }

            private Builder(@Nullable Icon icon, @Nullable CharSequence title,
                    @Nullable PendingIntent intent, @NonNull Bundle extras,
                    @Nullable RemoteInput[] remoteInputs, boolean allowGeneratedReplies,
                    @SemanticAction int semanticAction, boolean authRequired) {
                mIcon = icon;
                mTitle = title;
                mIntent = intent;
                mExtras = extras;
                if (remoteInputs != null) {
                    mRemoteInputs = new ArrayList<>(remoteInputs.length);
                    Collections.addAll(mRemoteInputs, remoteInputs);
                }
                mAllowGeneratedReplies = allowGeneratedReplies;
                mSemanticAction = semanticAction;
                mAuthenticationRequired = authRequired;
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
            @NonNull
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
             * Sets whether the OS should only send this action's {@link PendingIntent} on an
             * unlocked device.
             *
             * If this is true and the device is locked when the action is invoked, the OS will
             * show the keyguard and require successful authentication before invoking the intent.
             * If this is false and the device is locked, the OS will decide whether authentication
             * should be required.
             */
            @NonNull
            public Builder setAuthenticationRequired(boolean authenticationRequired) {
                mAuthenticationRequired = authenticationRequired;
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
                RemoteInput[] previousDataInputs = getParcelableArrayFromBundle(
                        mExtras, EXTRA_DATA_ONLY_INPUTS, RemoteInput.class);
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
                        mAllowGeneratedReplies, mSemanticAction, mIsContextual,
                        mAuthenticationRequired);
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
                    isContextual(),
                    isAuthenticationRequired());
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
            out.writeInt(mAuthenticationRequired ? 1 : 0);
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
                SEMANTIC_ACTION_CALL,
                SEMANTIC_ACTION_MARK_CONVERSATION_AS_PRIORITY,
                SEMANTIC_ACTION_CONVERSATION_IS_PHISHING
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
                .setContentIntent(PendingIntent.getActivity(
                        context, 0, contentIntent, PendingIntent.FLAG_MUTABLE))
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

        mAllowlistToken = parcel.readStrongBinder();
        if (mAllowlistToken == null) {
            mAllowlistToken = processAllowlistToken;
        }
        // Propagate this token to all pending intents that are unmarshalled from the parcel.
        parcel.setClassCookie(PendingIntent.class, mAllowlistToken);

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

        category = parcel.readString8();

        mGroupKey = parcel.readString8();

        mSortKey = parcel.readString8();

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
            mChannelId = parcel.readString8();
        }
        mTimeout = parcel.readLong();

        if (parcel.readInt() != 0) {
            mShortcutId = parcel.readString8();
        }

        if (parcel.readInt() != 0) {
            mLocusId = LocusId.CREATOR.createFromParcel(parcel);
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

        mFgsDeferBehavior = parcel.readInt();
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
        that.mAllowlistToken = this.mAllowlistToken;
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
        that.mLocusId = this.mLocusId;
        that.mBadgeIcon = this.mBadgeIcon;
        that.mSettingsText = this.mSettingsText;
        that.mGroupAlertBehavior = this.mGroupAlertBehavior;
        that.mFgsDeferBehavior = this.mFgsDeferBehavior;
        that.mBubbleMetadata = this.mBubbleMetadata;
        that.mAllowSystemGeneratedContextualActions = this.mAllowSystemGeneratedContextualActions;

        if (!heavy) {
            that.lightenPayload(); // will clean out extras
        }
    }

    private static void visitIconUri(@NonNull Consumer<Uri> visitor, @Nullable Icon icon) {
        if (icon == null) return;
        final int iconType = icon.getType();
        if (iconType == TYPE_URI || iconType == TYPE_URI_ADAPTIVE_BITMAP) {
            visitor.accept(icon.getUri());
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

        visitIconUri(visitor, mSmallIcon);
        visitIconUri(visitor, mLargeIcon);

        if (actions != null) {
            for (Action action : actions) {
                visitIconUri(visitor, action.getIcon());
            }
        }

        if (extras != null) {
            visitIconUri(visitor, extras.getParcelable(EXTRA_LARGE_ICON_BIG, Icon.class));
            visitIconUri(visitor, extras.getParcelable(EXTRA_PICTURE_ICON, Icon.class));

            // NOTE: The documentation of EXTRA_AUDIO_CONTENTS_URI explicitly says that it is a
            // String representation of a Uri, but the previous implementation (and unit test) of
            // this method has always treated it as a Uri object. Given the inconsistency,
            // supporting both going forward is the safest choice.
            Object audioContentsUri = extras.get(EXTRA_AUDIO_CONTENTS_URI);
            if (audioContentsUri instanceof Uri) {
                visitor.accept((Uri) audioContentsUri);
            } else if (audioContentsUri instanceof String) {
                visitor.accept(Uri.parse((String) audioContentsUri));
            }

            if (extras.containsKey(EXTRA_BACKGROUND_IMAGE_URI)) {
                visitor.accept(Uri.parse(extras.getString(EXTRA_BACKGROUND_IMAGE_URI)));
            }

            ArrayList<Person> people = extras.getParcelableArrayList(EXTRA_PEOPLE_LIST, android.app.Person.class);
            if (people != null && !people.isEmpty()) {
                for (Person p : people) {
                    visitor.accept(p.getIconUri());
                }
            }

            final Person person = extras.getParcelable(EXTRA_MESSAGING_PERSON, Person.class);
            if (person != null) {
                visitor.accept(person.getIconUri());
            }
        }

        if (isStyle(MessagingStyle.class) && extras != null) {
            final Parcelable[] messages = extras.getParcelableArray(EXTRA_MESSAGES);
            if (!ArrayUtils.isEmpty(messages)) {
                for (MessagingStyle.Message message : MessagingStyle.Message
                        .getMessagesFromBundleArray(messages)) {
                    visitor.accept(message.getDataUri());

                    Person senderPerson = message.getSenderPerson();
                    if (senderPerson != null) {
                        visitor.accept(senderPerson.getIconUri());
                    }
                }
            }

            final Parcelable[] historic = extras.getParcelableArray(EXTRA_HISTORIC_MESSAGES);
            if (!ArrayUtils.isEmpty(historic)) {
                for (MessagingStyle.Message message : MessagingStyle.Message
                        .getMessagesFromBundleArray(historic)) {
                    visitor.accept(message.getDataUri());

                    Person senderPerson = message.getSenderPerson();
                    if (senderPerson != null) {
                        visitor.accept(senderPerson.getIconUri());
                    }
                }
            }
        }

        if (mBubbleMetadata != null) {
            visitIconUri(visitor, mBubbleMetadata.getIcon());
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
                    synchronized (this) {
                        if (allPendingIntents == null) {
                            allPendingIntents = new ArraySet<>();
                        }
                        allPendingIntents.add(intent);
                    }
                }
            });
        }
        try {
            // IMPORTANT: Add marshaling code in writeToParcelImpl as we
            // want to intercept all pending events written to the parcel.
            writeToParcelImpl(parcel, flags);
            synchronized (this) {
                // Must be written last!
                parcel.writeArraySet(allPendingIntents);
            }
        } finally {
            if (collectPendingIntents) {
                PendingIntent.setOnMarshaledListener(null);
            }
        }
    }

    private void writeToParcelImpl(Parcel parcel, int flags) {
        parcel.writeInt(1);

        parcel.writeStrongBinder(mAllowlistToken);
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

        parcel.writeString8(category);

        parcel.writeString8(mGroupKey);

        parcel.writeString8(mSortKey);

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
            parcel.writeString8(mChannelId);
        } else {
            parcel.writeInt(0);
        }
        parcel.writeLong(mTimeout);

        if (mShortcutId != null) {
            parcel.writeInt(1);
            parcel.writeString8(mShortcutId);
        } else {
            parcel.writeInt(0);
        }

        if (mLocusId != null) {
            parcel.writeInt(1);
            mLocusId.writeToParcel(parcel, 0);
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

        parcel.writeInt(mFgsDeferBehavior);

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
     * Sets the token used for background operations for the pending intents associated with this
     * notification.
     *
     * This token is automatically set during deserialization for you, you usually won't need to
     * call this unless you want to change the existing token, if any.
     *
     * @hide
     */
    public void setAllowlistToken(@Nullable IBinder token) {
        mAllowlistToken = token;
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
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
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
            publicVersion.dumpDebug(proto, NotificationProto.PUBLIC_VERSION);
        }
        proto.end(token);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Notification(channel=");
        sb.append(getChannelId());
        sb.append(" shortcut=");
        sb.append(getShortcutId());
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
        if (this.mLocusId != null) {
            sb.append(" locusId=");
            sb.append(this.mLocusId); // LocusId.toString() is PII safe.
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
     * Gets the {@link LocusId} associated with this notification.
     *
     * <p>Used by the device's intelligence services to correlate objects (such as
     * {@link ShortcutInfo} and {@link ContentCaptureContext}) that are correlated.
     */
    @Nullable
    public LocusId getLocusId() {
        return mLocusId;
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
    @Nullable
    public BubbleMetadata getBubbleMetadata() {
        return mBubbleMetadata;
    }

    /**
     * Sets the {@link BubbleMetadata} for this notification.
     * @hide
     */
    public void setBubbleMetadata(BubbleMetadata data) {
        mBubbleMetadata = data;
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
     * Returns the actions that are contextual (that is, suggested because of the content of the
     * notification) out of the actions in this notification.
     */
    public @NonNull List<Notification.Action> getContextualActions() {
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
     * {@link androidx.core.app.NotificationCompat.Builder NotificationCompat.Builder},
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
        private ArrayList<Action> mActions = new ArrayList<>(MAX_ACTION_BUTTONS);
        private ArrayList<Person> mPersonList = new ArrayList<>();
        private ContrastColorUtil mColorUtil;
        private boolean mIsLegacy;
        private boolean mIsLegacyInitialized;

        /**
         * Caches an instance of StandardTemplateParams. Note that this may have been used before,
         * so make sure to call {@link StandardTemplateParams#reset()} before using it.
         */
        StandardTemplateParams mParams = new StandardTemplateParams();
        Colors mColors = new Colors();

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
         * @deprecated use {@link #Builder(Context, String)}
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
                    ArrayList<Person> people = mN.extras.getParcelableArrayList(EXTRA_PEOPLE_LIST, android.app.Person.class);
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
         * From Android 11, messaging notifications (those that use {@link MessagingStyle}) that
         * use this method to link to a published long-lived sharing shortcut may appear in a
         * dedicated Conversation section of the shade and may show configuration options that
         * are unique to conversations. This behavior should be reserved for person to person(s)
         * conversations where there is a likely social obligation for an individual to respond.
         * <p>
         * For example, the following are some examples of notifications that belong in the
         * conversation space:
         * <ul>
         * <li>1:1 conversations between two individuals</li>
         * <li>Group conversations between individuals where everyone can contribute</li>
         * </ul>
         * And the following are some examples of notifications that do not belong in the
         * conversation space:
         * <ul>
         * <li>Advertisements from a bot (even if personal and contextualized)</li>
         * <li>Engagement notifications from a bot</li>
         * <li>Directional conversations where there is an active speaker and many passive
         * individuals</li>
         * <li>Stream / posting updates from other individuals</li>
         * <li>Email, document comments, or other conversation types that are not real-time</li>
         * </ul>
         * </p>
         *
         * <p>
         * Additionally, this method can be used for all types of notifications to mark this
         * notification as duplicative of a Launcher shortcut. Launchers that show badges or
         * notification content may then suppress the shortcut in favor of the content of this
         * notification.
         * <p>
         * If this notification has {@link BubbleMetadata} attached that was created with
         * a shortcutId a check will be performed to ensure the shortcutId supplied to bubble
         * metadata matches the shortcutId set here, if one was set. If the shortcutId's were
         * specified but do not match, an exception is thrown.
         *
         * @param shortcutId the {@link ShortcutInfo#getId() id} of the shortcut this notification
         *                   is linked to
         *
         * @see BubbleMetadata.Builder#Builder(String)
         */
        @NonNull
        public Builder setShortcutId(String shortcutId) {
            mN.mShortcutId = shortcutId;
            return this;
        }

        /**
         * Sets the {@link LocusId} associated with this notification.
         *
         * <p>This method should be called when the {@link LocusId} is used in other places (such
         * as {@link ShortcutInfo} and {@link ContentCaptureContext}) so the device's intelligence
         * services can correlate them.
         */
        @NonNull
        public Builder setLocusId(@Nullable LocusId locusId) {
            mN.mLocusId = locusId;
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
         * <p>Notifications allowed to bubble that have valid bubble metadata will display in
         * collapsed state outside of the notification shade on unlocked devices. When a user
         * interacts with the collapsed state, the bubble intent will be invoked and displayed.</p>
         */
        @NonNull
        public Builder setBubbleMetadata(@Nullable BubbleMetadata data) {
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
                final int itemCount = Math.min(MAX_REPLY_HISTORY, text.length);
                CharSequence[] safe = new CharSequence[itemCount];
                RemoteInputHistoryItem[] items = new RemoteInputHistoryItem[itemCount];
                for (int i = 0; i < itemCount; i++) {
                    safe[i] = safeCharSequence(text[i]);
                    items[i] = new RemoteInputHistoryItem(text[i]);
                }
                mN.extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, safe);

                // Also add these messages as structured history items.
                mN.extras.putParcelableArray(EXTRA_REMOTE_INPUT_HISTORY_ITEMS, items);
            }
            return this;
        }

        /**
         * Set the remote input history, with support for embedding URIs and mime types for
         * images and other media.
         * @hide
         */
        @NonNull
        public Builder setRemoteInputHistory(RemoteInputHistoryItem[] items) {
            if (items == null) {
                mN.extras.putParcelableArray(EXTRA_REMOTE_INPUT_HISTORY_ITEMS, null);
            } else {
                final int itemCount = Math.min(MAX_REPLY_HISTORY, items.length);
                RemoteInputHistoryItem[] history = new RemoteInputHistoryItem[itemCount];
                for (int i = 0; i < itemCount; i++) {
                    history[i] = items[i];
                }
                mN.extras.putParcelableArray(EXTRA_REMOTE_INPUT_HISTORY_ITEMS, history);
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
         * <p>As of Android {@link android.os.Build.VERSION_CODES#S}, apps targeting API level
         * {@link android.os.Build.VERSION_CODES#S} or higher won't be able to start activities
         * while processing broadcast receivers or services in response to notification clicks. To
         * launch an activity in those cases, provide a {@link PendingIntent} for the activity
         * itself.
         *
         * <p>As of {@link android.os.Build.VERSION_CODES#HONEYCOMB}, if this field is unset and you
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
         * <p>
         * To be launched as a full screen intent, the notification must also be posted to a
         * channel with importance level set to IMPORTANCE_HIGH or higher.
         * </p>
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
         * In the platform template, this image will be shown either on the right of the
         * notification, with an aspect ratio of up to 16:9, or (when the notification is grouped)
         * on the left in place of the {@link #setSmallIcon(Icon) small icon}.
         */
        @NonNull
        public Builder setLargeIcon(Bitmap b) {
            return setLargeIcon(b != null ? Icon.createWithBitmap(b) : null);
        }

        /**
         * Add a large icon to the notification content view.
         *
         * In the platform template, this image will be shown either on the right of the
         * notification, with an aspect ratio of up to 16:9, or (when the notification is grouped)
         * on the left in place of the {@link #setSmallIcon(Icon) small icon}.
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
         * Note that using this flag will stop any ongoing alerting behaviour such
         * as sound, vibration or blinking notification LED.
         *
         * @see Notification#FLAG_ONLY_ALERT_ONCE
         */
        @NonNull
        public Builder setOnlyAlertOnce(boolean onlyAlertOnce) {
            setFlag(FLAG_ONLY_ALERT_ONCE, onlyAlertOnce);
            return this;
        }

        /**
         * Specify a desired visibility policy for a Notification associated with a
         * foreground service.  By default, the system can choose to defer
         * visibility of the notification for a short time after the service is
         * started.  Pass
         * {@link Notification#FOREGROUND_SERVICE_IMMEDIATE FOREGROUND_SERVICE_IMMEDIATE}
         * to this method in order to guarantee that visibility is never deferred.  Pass
         * {@link Notification#FOREGROUND_SERVICE_DEFERRED FOREGROUND_SERVICE_DEFERRED}
         * to request that visibility is deferred whenever possible.
         *
         * <p class="note">Note that deferred visibility is not guaranteed.  There
         * may be some circumstances under which the system will show the foreground
         * service's associated Notification immediately even when the app has used
         * this method to explicitly request deferred display.</p>
         * @param behavior One of
         * {@link Notification#FOREGROUND_SERVICE_DEFAULT FOREGROUND_SERVICE_DEFAULT},
         * {@link Notification#FOREGROUND_SERVICE_IMMEDIATE FOREGROUND_SERVICE_IMMEDIATE},
         * or {@link Notification#FOREGROUND_SERVICE_DEFERRED FOREGROUND_SERVICE_DEFERRED}
         * @return
         */
        @NonNull
        public Builder setForegroundServiceBehavior(@ServiceNotificationPolicy int behavior) {
            mN.mFgsDeferBehavior = behavior;
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
         * <p>
         * As of Android {@link android.os.Build.VERSION_CODES#S}, apps targeting API level
         * {@link android.os.Build.VERSION_CODES#S} or higher won't be able to start activities
         * while processing broadcast receivers or services in response to notification action
         * clicks. To launch an activity in those cases, provide a {@link PendingIntent} to the
         * activity itself.
         * <p>
         * As of Android {@link android.os.Build.VERSION_CODES#N},
         * action button icons will not be displayed on action buttons, but are still required
         * and are available to
         * {@link android.service.notification.NotificationListenerService notification listeners},
         * which may display them in other contexts, for example on a wearable device.
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
         * Set the value for a notification flag
         *
         * @param mask Bit mask of the flag
         * @param value Status (on/off) of the flag
         *
         * @return The same Builder.
         */
        @NonNull
        public Builder setFlag(@NotificationFlags int mask, boolean value) {
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

        private void bindPhishingAlertIcon(RemoteViews contentView, StandardTemplateParams p) {
            contentView.setDrawableTint(
                    R.id.phishing_alert,
                    false /* targetBackground */,
                    getColors(p).getErrorColor(),
                    PorterDuff.Mode.SRC_ATOP);
        }

        private Drawable getProfileBadgeDrawable() {
            if (mContext.getUserId() == UserHandle.USER_SYSTEM) {
                // This user can never be a badged profile,
                // and also includes USER_ALL system notifications.
                return null;
            }
            // Note: This assumes that the current user can read the profile badge of the
            // originating user.
            DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
            return dpm.getResources().getDrawable(
                    getUpdatableProfileBadgeId(), SOLID_COLORED, NOTIFICATION,
                    this::getDefaultProfileBadgeDrawable);
        }

        private String getUpdatableProfileBadgeId() {
            return mContext.getSystemService(UserManager.class).isManagedProfile()
                    ? WORK_PROFILE_ICON : UNDEFINED;
        }

        private Drawable getDefaultProfileBadgeDrawable() {
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
                if (isBackgroundColorized(p)) {
                    contentView.setDrawableTint(R.id.profile_badge, false,
                            getPrimaryTextColor(p), PorterDuff.Mode.SRC_ATOP);
                }
            }
        }

        private void bindAlertedIcon(RemoteViews contentView, StandardTemplateParams p) {
            contentView.setDrawableTint(
                    R.id.alerted_icon,
                    false /* targetBackground */,
                    getColors(p).getSecondaryTextColor(),
                    PorterDuff.Mode.SRC_IN);
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
        }

        /**
         * Resets the notification header to its original state
         */
        private void resetNotificationHeader(RemoteViews contentView) {
            // Small icon doesn't need to be reset, as it's always set. Resetting would prevent
            // re-using the drawable when the notification is updated.
            contentView.setBoolean(R.id.expand_button, "setExpanded", false);
            contentView.setViewVisibility(R.id.app_name_text, View.GONE);
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
            mN.mUsesStandardHeader = false;
        }

        private RemoteViews applyStandardTemplate(int resId, StandardTemplateParams p,
                TemplateBindResult result) {
            p.headerless(resId == getBaseLayoutResource()
                    || resId == getHeadsUpBaseLayoutResource()
                    || resId == getMessagingLayoutResource()
                    || resId == R.layout.notification_template_material_media);
            RemoteViews contentView = new BuilderRemoteViews(mContext.getApplicationInfo(), resId);

            resetStandardTemplate(contentView);

            final Bundle ex = mN.extras;
            updateBackgroundColor(contentView, p);
            bindNotificationHeader(contentView, p);
            bindLargeIconAndApplyMargin(contentView, p, result);
            boolean showProgress = handleProgressBar(contentView, ex, p);
            boolean hasSecondLine = showProgress;
            if (p.hasTitle()) {
                contentView.setViewVisibility(p.mTitleViewId, View.VISIBLE);
                contentView.setTextViewText(p.mTitleViewId, processTextSpans(p.title));
                setTextViewColorPrimary(contentView, p.mTitleViewId, p);
            } else if (p.mTitleViewId != R.id.title) {
                // This alternate title view ID is not cleared by resetStandardTemplate
                contentView.setViewVisibility(p.mTitleViewId, View.GONE);
                contentView.setTextViewText(p.mTitleViewId, null);
            }
            if (p.text != null && p.text.length() != 0
                    && (!showProgress || p.mAllowTextWithProgress)) {
                contentView.setViewVisibility(p.mTextViewId, View.VISIBLE);
                contentView.setTextViewText(p.mTextViewId, processTextSpans(p.text));
                setTextViewColorSecondary(contentView, p.mTextViewId, p);
                hasSecondLine = true;
            } else if (p.mTextViewId != R.id.text) {
                // This alternate text view ID is not cleared by resetStandardTemplate
                contentView.setViewVisibility(p.mTextViewId, View.GONE);
                contentView.setTextViewText(p.mTextViewId, null);
            }
            setHeaderlessVerticalMargins(contentView, p, hasSecondLine);

            return contentView;
        }

        private static void setHeaderlessVerticalMargins(RemoteViews contentView,
                StandardTemplateParams p, boolean hasSecondLine) {
            if (!p.mHeaderless) {
                return;
            }
            int marginDimen = hasSecondLine
                    ? R.dimen.notification_headerless_margin_twoline
                    : R.dimen.notification_headerless_margin_oneline;
            contentView.setViewLayoutMarginDimen(R.id.notification_headerless_view_column,
                    RemoteViews.MARGIN_TOP, marginDimen);
            contentView.setViewLayoutMarginDimen(R.id.notification_headerless_view_column,
                    RemoteViews.MARGIN_BOTTOM, marginDimen);
        }

        private CharSequence processTextSpans(CharSequence text) {
            if (mInNightMode) {
                return ContrastColorUtil.clearColorSpans(text);
            }
            return text;
        }

        private void setTextViewColorPrimary(RemoteViews contentView, @IdRes int id,
                StandardTemplateParams p) {
            contentView.setTextColor(id, getPrimaryTextColor(p));
        }

        /**
         * @param p the template params to inflate this with
         * @return the primary text color
         * @hide
         */
        @VisibleForTesting
        public @ColorInt int getPrimaryTextColor(StandardTemplateParams p) {
            return getColors(p).getPrimaryTextColor();
        }

        /**
         * @param p the template params to inflate this with
         * @return the secondary text color
         * @hide
         */
        @VisibleForTesting
        public @ColorInt int getSecondaryTextColor(StandardTemplateParams p) {
            return getColors(p).getSecondaryTextColor();
        }

        private void setTextViewColorSecondary(RemoteViews contentView, @IdRes int id,
                StandardTemplateParams p) {
            contentView.setTextColor(id, getSecondaryTextColor(p));
        }

        private Colors getColors(StandardTemplateParams p) {
            mColors.resolvePalette(mContext, mN.color, isBackgroundColorized(p), mInNightMode);
            return mColors;
        }

        private void updateBackgroundColor(RemoteViews contentView,
                StandardTemplateParams p) {
            if (isBackgroundColorized(p)) {
                contentView.setInt(R.id.status_bar_latest_event_content, "setBackgroundColor",
                        getBackgroundColor(p));
            } else {
                // Clear it!
                contentView.setInt(R.id.status_bar_latest_event_content, "setBackgroundResource",
                        0);
            }
        }

        private boolean handleProgressBar(RemoteViews contentView, Bundle ex,
                StandardTemplateParams p) {
            final int max = ex.getInt(EXTRA_PROGRESS_MAX, 0);
            final int progress = ex.getInt(EXTRA_PROGRESS, 0);
            final boolean ind = ex.getBoolean(EXTRA_PROGRESS_INDETERMINATE);
            if (!p.mHideProgress && (max != 0 || ind)) {
                contentView.setViewVisibility(com.android.internal.R.id.progress, View.VISIBLE);
                contentView.setProgressBar(R.id.progress, max, progress, ind);
                contentView.setProgressBackgroundTintList(R.id.progress,
                        mContext.getColorStateList(R.color.notification_progress_background_color));
                ColorStateList progressTint = ColorStateList.valueOf(getPrimaryAccentColor(p));
                contentView.setProgressTintList(R.id.progress, progressTint);
                contentView.setProgressIndeterminateTintList(R.id.progress, progressTint);
                return true;
            } else {
                contentView.setViewVisibility(R.id.progress, View.GONE);
                return false;
            }
        }

        private void bindLargeIconAndApplyMargin(RemoteViews contentView,
                @NonNull StandardTemplateParams p,
                @Nullable TemplateBindResult result) {
            if (result == null) {
                result = new TemplateBindResult();
            }
            bindLargeIcon(contentView, p, result);
            if (!p.mHeaderless) {
                // views in states with a header (big states)
                result.mHeadingExtraMarginSet.applyToView(contentView, R.id.notification_header);
                result.mTitleMarginSet.applyToView(contentView, R.id.title);
                // If there is no title, the text (or big_text) needs to wrap around the image
                result.mTitleMarginSet.applyToView(contentView, p.mTextViewId);
                contentView.setInt(p.mTextViewId, "setNumIndentLines", p.hasTitle() ? 0 : 1);
            }
        }

        // This code is executed on behalf of other apps' notifications, sometimes even by 3p apps,
        // a use case that is not supported by the Compat Framework library.  Workarounds to resolve
        // the change's state in NotificationManagerService were very complex. These behavior
        // changes are entirely visual, and should otherwise be undetectable by apps.
        @SuppressWarnings("AndroidFrameworkCompatChange")
        private void calculateRightIconDimens(Icon rightIcon, boolean isPromotedPicture,
                @NonNull TemplateBindResult result) {
            final Resources resources = mContext.getResources();
            final float density = resources.getDisplayMetrics().density;
            final float iconMarginDp = resources.getDimension(
                    R.dimen.notification_right_icon_content_margin) / density;
            final float contentMarginDp = resources.getDimension(
                    R.dimen.notification_content_margin_end) / density;
            final float expanderSizeDp = resources.getDimension(
                    R.dimen.notification_header_expand_icon_size) / density - contentMarginDp;
            final float viewHeightDp = resources.getDimension(
                    R.dimen.notification_right_icon_size) / density;
            float viewWidthDp = viewHeightDp;  // icons are 1:1 by default
            if (rightIcon != null && (isPromotedPicture
                    || mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.S)) {
                Drawable drawable = rightIcon.loadDrawable(mContext);
                if (drawable != null) {
                    int iconWidth = drawable.getIntrinsicWidth();
                    int iconHeight = drawable.getIntrinsicHeight();
                    if (iconWidth > iconHeight && iconHeight > 0) {
                        final float maxViewWidthDp = viewHeightDp * MAX_LARGE_ICON_ASPECT_RATIO;
                        viewWidthDp = Math.min(viewHeightDp * iconWidth / iconHeight,
                                maxViewWidthDp);
                    }
                }
            }
            final float extraMarginEndDpIfVisible = viewWidthDp + iconMarginDp;
            result.setRightIconState(rightIcon != null /* visible */, viewWidthDp,
                    viewHeightDp, extraMarginEndDpIfVisible, expanderSizeDp);
        }

        /**
         * Bind the large icon.
         */
        private void bindLargeIcon(RemoteViews contentView, @NonNull StandardTemplateParams p,
                @NonNull TemplateBindResult result) {
            if (mN.mLargeIcon == null && mN.largeIcon != null) {
                mN.mLargeIcon = Icon.createWithBitmap(mN.largeIcon);
            }

            // Determine the left and right icons
            Icon leftIcon = p.mHideLeftIcon ? null : mN.mLargeIcon;
            Icon rightIcon = p.mHideRightIcon ? null
                    : (p.mPromotedPicture != null ? p.mPromotedPicture : mN.mLargeIcon);

            // Apply the left icon (without duplicating the bitmap)
            if (leftIcon != rightIcon || leftIcon == null) {
                // If the leftIcon is explicitly hidden or different from the rightIcon, then set it
                // explicitly and make sure it won't take the right_icon drawable.
                contentView.setImageViewIcon(R.id.left_icon, leftIcon);
                contentView.setIntTag(R.id.left_icon, R.id.tag_uses_right_icon_drawable, 0);
            } else {
                // If the leftIcon equals the rightIcon, just set the flag to use the right_icon
                // drawable.  This avoids the view having two copies of the same bitmap.
                contentView.setIntTag(R.id.left_icon, R.id.tag_uses_right_icon_drawable, 1);
            }

            // Always calculate dimens to populate `result` for the GONE case
            boolean isPromotedPicture = p.mPromotedPicture != null;
            calculateRightIconDimens(rightIcon, isPromotedPicture, result);

            // Bind the right icon
            if (rightIcon != null) {
                contentView.setViewLayoutWidth(R.id.right_icon,
                        result.mRightIconWidthDp, TypedValue.COMPLEX_UNIT_DIP);
                contentView.setViewLayoutHeight(R.id.right_icon,
                        result.mRightIconHeightDp, TypedValue.COMPLEX_UNIT_DIP);
                contentView.setViewVisibility(R.id.right_icon, View.VISIBLE);
                contentView.setImageViewIcon(R.id.right_icon, rightIcon);
                contentView.setIntTag(R.id.right_icon, R.id.tag_keep_when_showing_left_icon,
                        isPromotedPicture ? 1 : 0);
                processLargeLegacyIcon(rightIcon, contentView, p);
            } else {
                // The "reset" doesn't clear the drawable, so we do it here.  This clear is
                // important because the presence of a drawable in this view (regardless of the
                // visibility) is used by NotificationGroupingUtil to set the visibility.
                contentView.setImageViewIcon(R.id.right_icon, null);
                contentView.setIntTag(R.id.right_icon, R.id.tag_keep_when_showing_left_icon, 0);
            }
        }

        private void bindNotificationHeader(RemoteViews contentView, StandardTemplateParams p) {
            bindSmallIcon(contentView, p);
            // Populate text left-to-right so that separators are only shown between strings
            boolean hasTextToLeft = bindHeaderAppName(contentView, p, false /* force */);
            hasTextToLeft |= bindHeaderTextSecondary(contentView, p, hasTextToLeft);
            hasTextToLeft |= bindHeaderText(contentView, p, hasTextToLeft);
            if (!hasTextToLeft) {
                // If there's still no text, force add the app name so there is some text.
                hasTextToLeft |= bindHeaderAppName(contentView, p, true /* force */);
            }
            bindHeaderChronometerAndTime(contentView, p, hasTextToLeft);
            bindPhishingAlertIcon(contentView, p);
            bindProfileBadge(contentView, p);
            bindAlertedIcon(contentView, p);
            bindExpandButton(contentView, p);
            mN.mUsesStandardHeader = true;
        }

        private void bindExpandButton(RemoteViews contentView, StandardTemplateParams p) {
            // set default colors
            int bgColor = getBackgroundColor(p);
            int pillColor = Colors.flattenAlpha(getColors(p).getProtectionColor(), bgColor);
            int textColor = Colors.flattenAlpha(getPrimaryTextColor(p), pillColor);
            contentView.setInt(R.id.expand_button, "setDefaultTextColor", textColor);
            contentView.setInt(R.id.expand_button, "setDefaultPillColor", pillColor);
            // Use different highlighted colors for conversations' unread count
            if (p.mHighlightExpander) {
                pillColor = Colors.flattenAlpha(getColors(p).getTertiaryAccentColor(), bgColor);
                textColor = Colors.flattenAlpha(getColors(p).getOnAccentTextColor(), pillColor);
            }
            contentView.setInt(R.id.expand_button, "setHighlightTextColor", textColor);
            contentView.setInt(R.id.expand_button, "setHighlightPillColor", pillColor);
        }

        private void bindHeaderChronometerAndTime(RemoteViews contentView,
                StandardTemplateParams p, boolean hasTextToLeft) {
            if (!p.mHideTime && showsTimeOrChronometer()) {
                if (hasTextToLeft) {
                    contentView.setViewVisibility(R.id.time_divider, View.VISIBLE);
                    setTextViewColorSecondary(contentView, R.id.time_divider, p);
                }
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
                setTextViewColorSecondary(contentView, R.id.time, p);
            }
        }

        /**
         * @return true if the header text will be visible
         */
        private boolean bindHeaderText(RemoteViews contentView, StandardTemplateParams p,
                boolean hasTextToLeft) {
            if (p.mHideSubText) {
                return false;
            }
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
            if (!TextUtils.isEmpty(summaryText)) {
                // TODO: Remove the span entirely to only have the string with propper formating.
                contentView.setTextViewText(R.id.header_text, processTextSpans(
                        processLegacyText(summaryText)));
                setTextViewColorSecondary(contentView, R.id.header_text, p);
                contentView.setViewVisibility(R.id.header_text, View.VISIBLE);
                if (hasTextToLeft) {
                    contentView.setViewVisibility(R.id.header_text_divider, View.VISIBLE);
                    setTextViewColorSecondary(contentView, R.id.header_text_divider, p);
                }
                return true;
            }
            return false;
        }

        /**
         * @return true if the secondary header text will be visible
         */
        private boolean bindHeaderTextSecondary(RemoteViews contentView, StandardTemplateParams p,
                boolean hasTextToLeft) {
            if (p.mHideSubText) {
                return false;
            }
            if (!TextUtils.isEmpty(p.headerTextSecondary)) {
                contentView.setTextViewText(R.id.header_text_secondary, processTextSpans(
                        processLegacyText(p.headerTextSecondary)));
                setTextViewColorSecondary(contentView, R.id.header_text_secondary, p);
                contentView.setViewVisibility(R.id.header_text_secondary, View.VISIBLE);
                if (hasTextToLeft) {
                    contentView.setViewVisibility(R.id.header_text_secondary_divider, View.VISIBLE);
                    setTextViewColorSecondary(contentView, R.id.header_text_secondary_divider, p);
                }
                return true;
            }
            return false;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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

        /**
         * @return true if the app name will be visible
         */
        private boolean bindHeaderAppName(RemoteViews contentView, StandardTemplateParams p,
                boolean force) {
            if (p.mViewType == StandardTemplateParams.VIEW_TYPE_MINIMIZED && !force) {
                // unless the force flag is set, don't show the app name in the minimized state.
                return false;
            }
            if (p.mHeaderless && p.hasTitle()) {
                // the headerless template will have the TITLE in this position; return true to
                // keep the divider visible between that title and the next text element.
                return true;
            }
            if (p.mHideAppName) {
                // The app name is being hidden, so we definitely want to return here.
                // Assume that there is a title which will replace it in the header.
                return p.hasTitle();
            }
            contentView.setViewVisibility(R.id.app_name_text, View.VISIBLE);
            contentView.setTextViewText(R.id.app_name_text, loadHeaderAppName());
            contentView.setTextColor(R.id.app_name_text, getSecondaryTextColor(p));
            return true;
        }

        /**
         * Determines if the notification should be colorized *for the purposes of applying colors*.
         * If this is the minimized view of a colorized notification, this will return false so that
         * internal coloring logic can still render the notification normally.
         */
        private boolean isBackgroundColorized(StandardTemplateParams p) {
            return p.allowColorization && mN.isColorized();
        }

        private boolean isCallActionColorCustomizable() {
            // NOTE: this doesn't need to check StandardTemplateParams.allowColorization because
            //  that is only used for disallowing colorization of headers for the minimized state,
            //  and neither of those conditions applies when showing actions.
            //  Not requiring StandardTemplateParams as an argument simplifies the creation process.
            return mN.isColorized() && mContext.getResources().getBoolean(
                    R.bool.config_callNotificationActionColorsRequireColorized);
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

            // This may get erased by bindSnoozeAction
            big.setViewLayoutMarginDimen(R.id.notification_action_list_margin_target,
                    RemoteViews.MARGIN_BOTTOM, R.dimen.notification_content_margin);
        }

        private void bindSnoozeAction(RemoteViews big, StandardTemplateParams p) {
            boolean hideSnoozeButton = mN.isForegroundService() || mN.fullScreenIntent != null
                    || isBackgroundColorized(p)
                    || p.mViewType != StandardTemplateParams.VIEW_TYPE_BIG;
            big.setBoolean(R.id.snooze_button, "setEnabled", !hideSnoozeButton);
            if (hideSnoozeButton) {
                // Only hide; NotificationContentView will show it when it adds the click listener
                big.setViewVisibility(R.id.snooze_button, View.GONE);
            }

            final boolean snoozeEnabled = !hideSnoozeButton
                    && mContext.getContentResolver() != null
                    && isSnoozeSettingEnabled();
            if (snoozeEnabled) {
                big.setViewLayoutMarginDimen(R.id.notification_action_list_margin_target,
                        RemoteViews.MARGIN_BOTTOM, 0);
            }
        }

        private boolean isSnoozeSettingEnabled() {
            try {
                return Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.SHOW_NOTIFICATION_SNOOZE, 0) == 1;
            } catch (SecurityException ex) {
                // Most 3p apps can't access this snooze setting, so their NotificationListeners
                // would be unable to create notification views if we propagated this exception.
                return false;
            }
        }

        /**
         * Returns the actions that are not contextual.
         */
        private @NonNull List<Notification.Action> getNonContextualActions() {
            if (mActions == null) return Collections.emptyList();
            List<Notification.Action> standardActions = new ArrayList<>();
            for (Notification.Action action : mActions) {
                if (!action.isContextual()) {
                    standardActions.add(action);
                }
            }
            return standardActions;
        }

        private RemoteViews applyStandardTemplateWithActions(int layoutId,
                StandardTemplateParams p, TemplateBindResult result) {
            RemoteViews big = applyStandardTemplate(layoutId, p, result);

            resetStandardTemplateWithActions(big);
            bindSnoozeAction(big, p);
            // color the snooze and bubble actions with the theme color
            ColorStateList actionColor = ColorStateList.valueOf(getStandardActionColor(p));
            big.setColorStateList(R.id.snooze_button, "setImageTintList", actionColor);
            big.setColorStateList(R.id.bubble_button, "setImageTintList", actionColor);

            boolean validRemoteInput = false;

            // In the UI, contextual actions appear separately from the standard actions, so we
            // filter them out here.
            List<Notification.Action> nonContextualActions = getNonContextualActions();

            int numActions = Math.min(nonContextualActions.size(), MAX_ACTION_BUTTONS);
            boolean emphazisedMode = mN.fullScreenIntent != null || p.mCallStyleActions;
            if (p.mCallStyleActions) {
                // Clear view padding to allow buttons to start on the left edge.
                // This must be done before 'setEmphasizedMode' which sets top/bottom margins.
                big.setViewPadding(R.id.actions, 0, 0, 0, 0);
                // Add an optional indent that will make buttons start at the correct column when
                // there is enough space to do so (and fall back to the left edge if not).
                big.setInt(R.id.actions, "setCollapsibleIndentDimen",
                        R.dimen.call_notification_collapsible_indent);
            }
            big.setBoolean(R.id.actions, "setEmphasizedMode", emphazisedMode);
            if (numActions > 0 && !p.mHideActions) {
                big.setViewVisibility(R.id.actions_container, View.VISIBLE);
                big.setViewVisibility(R.id.actions, View.VISIBLE);
                big.setViewLayoutMarginDimen(R.id.notification_action_list_margin_target,
                        RemoteViews.MARGIN_BOTTOM, 0);
                for (int i = 0; i < numActions; i++) {
                    Action action = nonContextualActions.get(i);

                    boolean actionHasValidInput = hasValidRemoteInput(action);
                    validRemoteInput |= actionHasValidInput;

                    final RemoteViews button = generateActionButton(action, emphazisedMode, p);
                    if (actionHasValidInput && !emphazisedMode) {
                        // Clear the drawable
                        button.setInt(R.id.action0, "setBackgroundResource", 0);
                    }
                    if (emphazisedMode && i > 0) {
                        // Clear start margin from non-first buttons to reduce the gap between them.
                        //  (8dp remaining gap is from all buttons' standard 4dp inset).
                        button.setViewLayoutMarginDimen(R.id.action0, RemoteViews.MARGIN_START, 0);
                    }
                    big.addView(R.id.actions, button);
                }
            } else {
                big.setViewVisibility(R.id.actions_container, View.GONE);
            }

            RemoteInputHistoryItem[] replyText = getParcelableArrayFromBundle(
                    mN.extras, EXTRA_REMOTE_INPUT_HISTORY_ITEMS, RemoteInputHistoryItem.class);
            if (validRemoteInput && replyText != null && replyText.length > 0
                    && !TextUtils.isEmpty(replyText[0].getText())
                    && p.maxRemoteInputHistory > 0) {
                boolean showSpinner = mN.extras.getBoolean(EXTRA_SHOW_REMOTE_INPUT_SPINNER);
                big.setViewVisibility(R.id.notification_material_reply_container, View.VISIBLE);
                big.setViewVisibility(R.id.notification_material_reply_text_1_container,
                        View.VISIBLE);
                big.setTextViewText(R.id.notification_material_reply_text_1,
                        processTextSpans(replyText[0].getText()));
                setTextViewColorSecondary(big, R.id.notification_material_reply_text_1, p);
                big.setViewVisibility(R.id.notification_material_reply_progress,
                        showSpinner ? View.VISIBLE : View.GONE);
                big.setProgressIndeterminateTintList(
                        R.id.notification_material_reply_progress,
                        ColorStateList.valueOf(getPrimaryAccentColor(p)));

                if (replyText.length > 1 && !TextUtils.isEmpty(replyText[1].getText())
                        && p.maxRemoteInputHistory > 1) {
                    big.setViewVisibility(R.id.notification_material_reply_text_2, View.VISIBLE);
                    big.setTextViewText(R.id.notification_material_reply_text_2,
                            processTextSpans(replyText[1].getText()));
                    setTextViewColorSecondary(big, R.id.notification_material_reply_text_2, p);

                    if (replyText.length > 2 && !TextUtils.isEmpty(replyText[2].getText())
                            && p.maxRemoteInputHistory > 2) {
                        big.setViewVisibility(
                                R.id.notification_material_reply_text_3, View.VISIBLE);
                        big.setTextViewText(R.id.notification_material_reply_text_3,
                                processTextSpans(replyText[2].getText()));
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

        // This code is executed on behalf of other apps' notifications, sometimes even by 3p apps,
        // a use case that is not supported by the Compat Framework library.  Workarounds to resolve
        // the change's state in NotificationManagerService were very complex. While it's possible
        // apps can detect the change, it's most likely that the changes will simply result in
        // visual regressions.
        @SuppressWarnings("AndroidFrameworkCompatChange")
        private boolean fullyCustomViewRequiresDecoration(boolean fromStyle) {
            // Custom views which come from a platform style class are safe, and thus do not need to
            // be wrapped.  Any subclass of those styles has the opportunity to make arbitrary
            // changes to the RemoteViews, and thus can't be trusted as a fully vetted view.
            if (fromStyle && PLATFORM_STYLE_CLASSES.contains(mStyle.getClass())) {
                return false;
            }
            return mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.S;
        }

        private RemoteViews minimallyDecoratedContentView(@NonNull RemoteViews customContent) {
            StandardTemplateParams p = mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_NORMAL)
                    .decorationType(StandardTemplateParams.DECORATION_MINIMAL)
                    .fillTextsFrom(this);
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews standard = applyStandardTemplate(getBaseLayoutResource(), p, result);
            buildCustomContentIntoTemplate(mContext, standard, customContent,
                    p, result);
            return standard;
        }

        private RemoteViews minimallyDecoratedBigContentView(@NonNull RemoteViews customContent) {
            StandardTemplateParams p = mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_BIG)
                    .decorationType(StandardTemplateParams.DECORATION_MINIMAL)
                    .fillTextsFrom(this);
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews standard = applyStandardTemplateWithActions(getBigBaseLayoutResource(),
                    p, result);
            buildCustomContentIntoTemplate(mContext, standard, customContent,
                    p, result);
            makeHeaderExpanded(standard);
            return standard;
        }

        private RemoteViews minimallyDecoratedHeadsUpContentView(
                @NonNull RemoteViews customContent) {
            StandardTemplateParams p = mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_HEADS_UP)
                    .decorationType(StandardTemplateParams.DECORATION_MINIMAL)
                    .fillTextsFrom(this);
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews standard = applyStandardTemplateWithActions(getHeadsUpBaseLayoutResource(),
                    p, result);
            buildCustomContentIntoTemplate(mContext, standard, customContent,
                    p, result);
            return standard;
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
            if (useExistingRemoteView(mN.contentView)) {
                return fullyCustomViewRequiresDecoration(false /* fromStyle */)
                        ? minimallyDecoratedContentView(mN.contentView) : mN.contentView;
            } else if (mStyle != null) {
                final RemoteViews styleView = mStyle.makeContentView(increasedHeight);
                if (styleView != null) {
                    return fullyCustomViewRequiresDecoration(true /* fromStyle */)
                            ? minimallyDecoratedContentView(styleView) : styleView;
                }
            }
            StandardTemplateParams p = mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_NORMAL)
                    .fillTextsFrom(this);
            return applyStandardTemplate(getBaseLayoutResource(), p, null /* result */);
        }

        private boolean useExistingRemoteView(RemoteViews customContent) {
            if (customContent == null) {
                return false;
            }
            if (styleDisplaysCustomViewInline()) {
                // the provided custom view is intended to be wrapped by the style.
                return false;
            }
            if (fullyCustomViewRequiresDecoration(false)
                    && STANDARD_LAYOUTS.contains(customContent.getLayoutId())) {
                // If the app's custom views are objects returned from Builder.create*ContentView()
                // then the app is most likely attempting to spoof the user.  Even if they are not,
                // the result would be broken (b/189189308) so we will ignore it.
                Log.w(TAG, "For apps targeting S, a custom content view that is a modified "
                        + "version of any standard layout is disallowed.");
                return false;
            }
            return true;
        }

        /**
         * Construct a RemoteViews for the final big notification layout.
         */
        public RemoteViews createBigContentView() {
            RemoteViews result = null;
            if (useExistingRemoteView(mN.bigContentView)) {
                return fullyCustomViewRequiresDecoration(false /* fromStyle */)
                        ? minimallyDecoratedBigContentView(mN.bigContentView) : mN.bigContentView;
            }
            if (mStyle != null) {
                result = mStyle.makeBigContentView();
                if (fullyCustomViewRequiresDecoration(true /* fromStyle */)) {
                    result = minimallyDecoratedBigContentView(result);
                }
            }
            if (result == null) {
                if (bigContentViewRequired()) {
                    StandardTemplateParams p = mParams.reset()
                            .viewType(StandardTemplateParams.VIEW_TYPE_BIG)
                            .allowTextWithProgress(true)
                            .fillTextsFrom(this);
                    result = applyStandardTemplateWithActions(getBigBaseLayoutResource(), p,
                            null /* result */);
                }
            }
            makeHeaderExpanded(result);
            return result;
        }

        // This code is executed on behalf of other apps' notifications, sometimes even by 3p apps,
        // a use case that is not supported by the Compat Framework library.  Workarounds to resolve
        // the change's state in NotificationManagerService were very complex. While it's possible
        // apps can detect the change, it's most likely that the changes will simply result in
        // visual regressions.
        @SuppressWarnings("AndroidFrameworkCompatChange")
        private boolean bigContentViewRequired() {
            if (mContext.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.S) {
                return true;
            }
            // Notifications with contentView and without a bigContentView, style, or actions would
            // not have an expanded state before S, so showing the standard template expanded state
            // usually looks wrong, so we keep it simple and don't show the expanded state.
            boolean exempt = mN.contentView != null && mN.bigContentView == null
                    && mStyle == null && mActions.size() == 0;
            return !exempt;
        }

        /**
         * Construct a RemoteViews for the final notification header only. This will not be
         * colorized.
         *
         * @hide
         */
        public RemoteViews makeNotificationGroupHeader() {
            return makeNotificationHeader(mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_GROUP_HEADER)
                    .fillTextsFrom(this));
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

        /**
         * Adapt the Notification header if this view is used as an expanded view.
         *
         * @hide
         */
        public static void makeHeaderExpanded(RemoteViews result) {
            if (result != null) {
                result.setBoolean(R.id.expand_button, "setExpanded", true);
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
            if (useExistingRemoteView(mN.headsUpContentView)) {
                return fullyCustomViewRequiresDecoration(false /* fromStyle */)
                        ? minimallyDecoratedHeadsUpContentView(mN.headsUpContentView)
                        : mN.headsUpContentView;
            } else if (mStyle != null) {
                final RemoteViews styleView = mStyle.makeHeadsUpContentView(increasedHeight);
                if (styleView != null) {
                    return fullyCustomViewRequiresDecoration(true /* fromStyle */)
                            ? minimallyDecoratedHeadsUpContentView(styleView) : styleView;
                }
            } else if (mActions.size() == 0) {
                return null;
            }

            // We only want at most a single remote input history to be shown here, otherwise
            // the content would become squished.
            StandardTemplateParams p = mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_HEADS_UP)
                    .fillTextsFrom(this)
                    .setMaxRemoteInputHistory(1);
            return applyStandardTemplateWithActions(getHeadsUpBaseLayoutResource(), p,
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
         * @param isLowPriority is this notification low priority
         * @hide
         */
        @UnsupportedAppUsage
        public RemoteViews makePublicContentView(boolean isLowPriority) {
            if (mN.publicVersion != null) {
                final Builder builder = recoverBuilder(mContext, mN.publicVersion);
                return builder.createContentView();
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
            StandardTemplateParams params = mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_PUBLIC)
                    .fillTextsFrom(this);
            if (isLowPriority) {
                params.highlightExpander(false);
            }
            view = makeNotificationHeader(params);
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
                    .viewType(StandardTemplateParams.VIEW_TYPE_MINIMIZED)
                    .highlightExpander(false)
                    .fillTextsFrom(this);
            if (!useRegularSubtext || TextUtils.isEmpty(p.summaryText)) {
                p.summaryText(createSummaryText());
            }
            RemoteViews header = makeNotificationHeader(p);
            header.setBoolean(R.id.notification_header, "setAcceptAllTouches", true);
            // The low priority header has no app name and shows the text
            header.setBoolean(R.id.notification_header, "styleTextAsTitle", true);
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

        private RemoteViews generateActionButton(Action action, boolean emphasizedMode,
                StandardTemplateParams p) {
            final boolean tombstone = (action.actionIntent == null);
            RemoteViews button = new BuilderRemoteViews(mContext.getApplicationInfo(),
                    emphasizedMode ? getEmphasizedActionLayoutResource()
                            : tombstone ? getActionTombstoneLayoutResource()
                                    : getActionLayoutResource());
            if (!tombstone) {
                button.setOnClickPendingIntent(R.id.action0, action.actionIntent);
            }
            button.setContentDescription(R.id.action0, action.title);
            if (action.mRemoteInputs != null) {
                button.setRemoteInputs(R.id.action0, action.mRemoteInputs);
            }
            if (emphasizedMode) {
                // change the background bgColor
                CharSequence title = action.title;
                int buttonFillColor = getColors(p).getSecondaryAccentColor();
                if (isLegacy()) {
                    title = ContrastColorUtil.clearColorSpans(title);
                } else {
                    // Check for a full-length span color to use as the button fill color.
                    Integer fullLengthColor = getFullLengthSpanColor(title);
                    if (fullLengthColor != null) {
                        // Ensure the custom button fill has 1.3:1 contrast w/ notification bg.
                        int notifBackgroundColor = getColors(p).getBackgroundColor();
                        buttonFillColor = ensureButtonFillContrast(
                                fullLengthColor, notifBackgroundColor);
                    }
                    // Remove full-length color spans and ensure text contrast with the button fill.
                    title = ensureColorSpanContrast(title, buttonFillColor);
                }
                button.setTextViewText(R.id.action0, processTextSpans(title));
                final int textColor = ContrastColorUtil.resolvePrimaryColor(mContext,
                        buttonFillColor, mInNightMode);
                button.setTextColor(R.id.action0, textColor);
                // We only want about 20% alpha for the ripple
                final int rippleColor = (textColor & 0x00ffffff) | 0x33000000;
                button.setColorStateList(R.id.action0, "setRippleColor",
                        ColorStateList.valueOf(rippleColor));
                button.setColorStateList(R.id.action0, "setButtonBackground",
                        ColorStateList.valueOf(buttonFillColor));
                if (p.mCallStyleActions) {
                    button.setImageViewIcon(R.id.action0, action.getIcon());
                    boolean priority = action.getExtras().getBoolean(CallStyle.KEY_ACTION_PRIORITY);
                    button.setBoolean(R.id.action0, "setIsPriority", priority);
                    int minWidthDimen =
                            priority ? R.dimen.call_notification_system_action_min_width : 0;
                    button.setIntDimen(R.id.action0, "setMinimumWidth", minWidthDimen);
                }
            } else {
                button.setTextViewText(R.id.action0, processTextSpans(
                        processLegacyText(action.title)));
                button.setTextColor(R.id.action0, getStandardActionColor(p));
            }
            // CallStyle notifications add action buttons which don't actually exist in mActions,
            //  so we have to omit the index in that case.
            int actionIndex = mActions.indexOf(action);
            if (actionIndex != -1) {
                button.setIntTag(R.id.action0, R.id.notification_action_index_tag, actionIndex);
            }
            return button;
        }

        /**
         * Extract the color from a full-length span from the text.
         *
         * @param charSequence the charSequence containing spans
         * @return the raw color of the text's last full-length span containing a color, or null if
         * no full-length span sets the text color.
         * @hide
         */
        @VisibleForTesting
        @Nullable
        public static Integer getFullLengthSpanColor(CharSequence charSequence) {
            // NOTE: this method preserves the functionality that for a CharSequence with multiple
            // full-length spans, the color of the last one is used.
            Integer result = null;
            if (charSequence instanceof Spanned) {
                Spanned ss = (Spanned) charSequence;
                Object[] spans = ss.getSpans(0, ss.length(), Object.class);
                // First read through all full-length spans to get the button fill color, which will
                //  be used as the background color for ensuring contrast of non-full-length spans.
                for (Object span : spans) {
                    int spanStart = ss.getSpanStart(span);
                    int spanEnd = ss.getSpanEnd(span);
                    boolean fullLength = (spanEnd - spanStart) == charSequence.length();
                    if (!fullLength) {
                        continue;
                    }
                    if (span instanceof TextAppearanceSpan) {
                        TextAppearanceSpan originalSpan = (TextAppearanceSpan) span;
                        ColorStateList textColor = originalSpan.getTextColor();
                        if (textColor != null) {
                            result = textColor.getDefaultColor();
                        }
                    } else if (span instanceof ForegroundColorSpan) {
                        ForegroundColorSpan originalSpan = (ForegroundColorSpan) span;
                        result = originalSpan.getForegroundColor();
                    }
                }
            }
            return result;
        }

        /**
         * Ensures contrast on color spans against a background color.
         * Note that any full-length color spans will be removed instead of being contrasted.
         *
         * @param charSequence the charSequence on which the spans are
         * @param background the background color to ensure the contrast against
         * @return the contrasted charSequence
         * @hide
         */
        @VisibleForTesting
        public static CharSequence ensureColorSpanContrast(CharSequence charSequence,
                int background) {
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
                            if (fullLength) {
                                // Let's drop the color from the span
                                textColor = null;
                            } else {
                                int[] colors = textColor.getColors();
                                int[] newColors = new int[colors.length];
                                for (int i = 0; i < newColors.length; i++) {
                                    boolean isBgDark = isColorDark(background);
                                    newColors[i] = ContrastColorUtil.ensureLargeTextContrast(
                                            colors[i], background, isBgDark);
                                }
                                textColor = new ColorStateList(textColor.getStates().clone(),
                                        newColors);
                            }
                            resultSpan = new TextAppearanceSpan(
                                    originalSpan.getFamily(),
                                    originalSpan.getTextStyle(),
                                    originalSpan.getTextSize(),
                                    textColor,
                                    originalSpan.getLinkTextColor());
                        }
                    } else if (resultSpan instanceof ForegroundColorSpan) {
                        if (fullLength) {
                            resultSpan = null;
                        } else {
                            ForegroundColorSpan originalSpan = (ForegroundColorSpan) resultSpan;
                            int foregroundColor = originalSpan.getForegroundColor();
                            boolean isBgDark = isColorDark(background);
                            foregroundColor = ContrastColorUtil.ensureLargeTextContrast(
                                    foregroundColor, background, isBgDark);
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
         * Determines if the color is light or dark.  Specifically, this is using the same metric as
         * {@link ContrastColorUtil#resolvePrimaryColor(Context, int, boolean)} and peers so that
         * the direction of color shift is consistent.
         *
         * @param color the color to check
         * @return true if the color has higher contrast with white than black
         * @hide
         */
        public static boolean isColorDark(int color) {
            // as per ContrastColorUtil.shouldUseDark, this uses the color contrast midpoint.
            return ContrastColorUtil.calculateLuminance(color) <= 0.17912878474;
        }

        /**
         * Finds a button fill color with sufficient contrast over bg (1.3:1) that has the same hue
         * as the original color, but is lightened or darkened depending on whether the background
         * is dark or light.
         *
         * @hide
         */
        @VisibleForTesting
        public static int ensureButtonFillContrast(int color, int bg) {
            return isColorDark(bg)
                    ? ContrastColorUtil.findContrastColorAgainstDark(color, bg, true, 1.3)
                    : ContrastColorUtil.findContrastColor(color, bg, true, 1.3);
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
         * Apply any necessary colors to the small icon
         */
        private void processSmallIconColor(Icon smallIcon, RemoteViews contentView,
                StandardTemplateParams p) {
            boolean colorable = !isLegacy() || getColorUtil().isGrayscaleIcon(mContext, smallIcon);
            int color = getSmallIconColor(p);
            contentView.setInt(R.id.icon, "setBackgroundColor",
                    getBackgroundColor(p));
            contentView.setInt(R.id.icon, "setOriginalIconColor",
                    colorable ? color : COLOR_INVALID);
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
                int color = getSmallIconColor(p);
                contentView.setInt(R.id.icon, "setOriginalIconColor", color);
            }
        }

        private void sanitizeColor() {
            if (mN.color != COLOR_DEFAULT) {
                mN.color |= 0xFF000000; // no alpha for custom colors
            }
        }

        /**
         * Gets the standard action button color
         */
        private @ColorInt int getStandardActionColor(Notification.StandardTemplateParams p) {
            return mTintActionButtons || isBackgroundColorized(p)
                    ? getPrimaryAccentColor(p) : getSecondaryTextColor(p);
        }

        /**
         * Gets the foreground color of the small icon.  If the notification is colorized, this
         * is the primary text color, otherwise it's the contrast-adjusted app-provided color.
         */
        private @ColorInt int getSmallIconColor(StandardTemplateParams p) {
            return getColors(p).getContrastColor();
        }

        /** @return the theme's accent color for colored UI elements. */
        private @ColorInt int getPrimaryAccentColor(StandardTemplateParams p) {
            return getColors(p).getPrimaryAccentColor();
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
                    EXTRA_BUILDER_APPLICATION_INFO, ApplicationInfo.class);
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
         *
         * If this notification has {@link BubbleMetadata} attached that was created with
         * a shortcutId a check will be performed to ensure the shortcutId supplied to bubble
         * metadata matches the shortcutId set on the  notification builder, if one was set.
         * If the shortcutId's were specified but do not match, an exception is thrown here.
         *
         * @see BubbleMetadata.Builder#Builder(String)
         * @see #setShortcutId(String)
         */
        @NonNull
        public Notification build() {
            // Check shortcut id matches
            if (mN.mShortcutId != null
                    && mN.mBubbleMetadata != null
                    && mN.mBubbleMetadata.getShortcutId() != null
                    && !mN.mShortcutId.equals(mN.mBubbleMetadata.getShortcutId())) {
                throw new IllegalArgumentException(
                        "Notification and BubbleMetadata shortcut id's don't match,"
                                + " notification: " + mN.mShortcutId
                                + " vs bubble: " + mN.mBubbleMetadata.getShortcutId());
            }

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
                    && !styleDisplaysCustomViewInline()) {
                RemoteViews newContentView = mN.contentView;
                RemoteViews newBigContentView = mN.bigContentView;
                RemoteViews newHeadsUpContentView = mN.headsUpContentView;
                if (newContentView == null) {
                    newContentView = createContentView();
                    mN.extras.putInt(EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT,
                            newContentView.getSequenceNumber());
                }
                if (newBigContentView == null) {
                    newBigContentView = createBigContentView();
                    if (newBigContentView != null) {
                        mN.extras.putInt(EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT,
                                newBigContentView.getSequenceNumber());
                    }
                }
                if (newHeadsUpContentView == null) {
                    newHeadsUpContentView = createHeadsUpContentView();
                    if (newHeadsUpContentView != null) {
                        mN.extras.putInt(EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT,
                                newHeadsUpContentView.getSequenceNumber());
                    }
                }
                // Don't set any of the content views until after they have all been generated,
                //  to avoid the generated .contentView triggering the logic which skips generating
                //  the .bigContentView.
                mN.contentView = newContentView;
                mN.bigContentView = newBigContentView;
                mN.headsUpContentView = newHeadsUpContentView;
            }

            if ((mN.defaults & DEFAULT_LIGHTS) != 0) {
                mN.flags |= FLAG_SHOW_LIGHTS;
            }

            mN.allPendingIntents = null;

            return mN;
        }

        private boolean styleDisplaysCustomViewInline() {
            return mStyle != null && mStyle.displayCustomViewInline();
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
         * change.
         *
         * @return {@param n}, if no stripping is needed, otherwise a stripped clone of {@param n}.
         *
         * @hide
         */
        public static Notification maybeCloneStrippedForDelivery(Notification n) {
            String templateClass = n.extras.getString(EXTRA_TEMPLATE);

            // Only strip views for known Styles because we won't know how to
            // re-create them otherwise.
            if (!TextUtils.isEmpty(templateClass)
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
            if (!stripContentView && !stripBigContentView && !stripHeadsUpContentView) {
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
            return clone;
        }

        @UnsupportedAppUsage
        private int getBaseLayoutResource() {
            return R.layout.notification_template_material_base;
        }

        private int getHeadsUpBaseLayoutResource() {
            return R.layout.notification_template_material_heads_up_base;
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

        private int getBigMessagingLayoutResource() {
            return R.layout.notification_template_material_big_messaging;
        }

        private int getConversationLayoutResource() {
            return R.layout.notification_template_material_conversation;
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

        private @ColorInt int getBackgroundColor(StandardTemplateParams p) {
            return getColors(p).getBackgroundColor();
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

        /**
         * @return if this builder uses a template
         *
         * @hide
         */
        public boolean usesTemplate() {
            return (mN.contentView == null && mN.headsUpContentView == null
                    && mN.bigContentView == null)
                    || styleDisplaysCustomViewInline();
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

        if (mSmallIcon != null
                // Only bitmap icons can be downscaled.
                && (mSmallIcon.getType() == Icon.TYPE_BITMAP
                        || mSmallIcon.getType() == Icon.TYPE_ADAPTIVE_BITMAP)) {
            Resources resources = context.getResources();
            int maxSize = resources.getDimensionPixelSize(
                    isLowRam ? R.dimen.notification_small_icon_size_low_ram
                            : R.dimen.notification_small_icon_size);
            mSmallIcon.scaleDownIfNecessary(maxSize, maxSize);
        }

        if (mLargeIcon != null || largeIcon != null) {
            Resources resources = context.getResources();
            Class<? extends Style> style = getNotificationStyle();
            int maxSize = resources.getDimensionPixelSize(isLowRam
                    ? R.dimen.notification_right_icon_size_low_ram
                    : R.dimen.notification_right_icon_size);
            if (mLargeIcon != null) {
                mLargeIcon.scaleDownIfNecessary(maxSize, maxSize);
            }
            if (largeIcon != null) {
                largeIcon = Icon.scaleDownIfNecessary(largeIcon, maxSize, maxSize);
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
     * Describe whether this notification's content such that it should always display
     * immediately when tied to a foreground service, even if the system might generally
     * avoid showing the notifications for short-lived foreground service lifetimes.
     *
     * Immediate visibility of the Notification is indicated when:
     * <ul>
     *     <li>The app specifically indicated it with
     *         {@link Notification.Builder#setForegroundServiceBehavior(int)
     *         setForegroundServiceBehavior(BEHAVIOR_IMMEDIATE_DISPLAY)}</li>
     *     <li>It is a media notification or has an associated media session</li>
     *     <li>It is a call or navigation notification</li>
     *     <li>It provides additional action affordances</li>
     * </ul>
     *
     * If the app has specified
     * {@code NotificationBuilder.setForegroundServiceBehavior(BEHAVIOR_DEFERRED_DISPLAY)}
     * then this method will return {@code false} and notification visibility will be
     * deferred following the service's transition to the foreground state even in the
     * circumstances described above.
     *
     * @return whether this notification should be displayed immediately when
     * its associated service transitions to the foreground state
     * @hide
     */
    @TestApi
    public boolean shouldShowForegroundImmediately() {
        // Has the app demanded immediate display?
        if (mFgsDeferBehavior == FOREGROUND_SERVICE_IMMEDIATE) {
            return true;
        }

        // Has the app demanded deferred display?
        if (mFgsDeferBehavior == FOREGROUND_SERVICE_DEFERRED) {
            return false;
        }

        // We show these sorts of notifications immediately in the absence of
        // any explicit app declaration
        if (isMediaNotification()
                    || CATEGORY_CALL.equals(category)
                    || CATEGORY_NAVIGATION.equals(category)
                    || (actions != null && actions.length > 0)) {
            return true;
        }

        // No extenuating circumstances: defer visibility
        return false;
    }

    /**
     * Has forced deferral for FGS purposes been specified?
     * @hide
     */
    public boolean isForegroundDisplayForceDeferred() {
        return FOREGROUND_SERVICE_DEFERRED == mFgsDeferBehavior;
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
     * @return whether the style of this notification is the one provided
     * @hide
     */
    public boolean isStyle(@NonNull Class<? extends Style> styleClass) {
        String templateClass = extras.getString(Notification.EXTRA_TEMPLATE);
        return Objects.equals(templateClass, styleClass.getName());
    }

    /**
     * @return true if this notification is colorized *for the purposes of ranking*.  If the
     * {@link #color} is {@link #COLOR_DEFAULT} this will be true, even though the actual
     * appearance of the notification may not be "colorized".
     *
     * @hide
     */
    public boolean isColorized() {
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
     * @return true if this is a media style notification with a media session
     *
     * @hide
     */
    public boolean isMediaNotification() {
        Class<? extends Style> style = getNotificationStyle();
        boolean isMediaStyle = (MediaStyle.class.equals(style)
                || DecoratedMediaCustomViewStyle.class.equals(style));

        boolean hasMediaSession = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION,
                MediaSession.Token.class) != null;

        return isMediaStyle && hasMediaSession;
    }

    /**
     * @return true if this notification is showing as a bubble
     *
     * @hide
     */
    public boolean isBubbleNotification() {
        return (flags & Notification.FLAG_BUBBLE) != 0;
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
     * @return true if the notification has image
     */
    public boolean hasImage() {
        if (isStyle(MessagingStyle.class) && extras != null) {
            final Parcelable[] messages = extras.getParcelableArray(EXTRA_MESSAGES);
            if (!ArrayUtils.isEmpty(messages)) {
                for (MessagingStyle.Message m : MessagingStyle.Message
                        .getMessagesFromBundleArray(messages)) {
                    if (m.getDataUri() != null
                            && m.getDataMimeType() != null
                            && m.getDataMimeType().startsWith("image/")) {
                        return true;
                    }
                }
            }
        } else if (hasLargeIcon()) {
            return true;
        } else if (extras.containsKey(EXTRA_BACKGROUND_IMAGE_URI)) {
            return true;
        }
        return false;
    }


    /**
     * @removed
     */
    @SystemApi
    public static Class<? extends Style> getNotificationStyleClass(String templateClass) {
        for (Class<? extends Style> innerClass : PLATFORM_STYLE_CLASSES) {
            if (templateClass.equals(innerClass.getName())) {
                return innerClass;
            }
        }
        return null;
    }

    private static void buildCustomContentIntoTemplate(@NonNull Context context,
            @NonNull RemoteViews template, @Nullable RemoteViews customContent,
            @NonNull StandardTemplateParams p, @NonNull TemplateBindResult result) {
        int childIndex = -1;
        if (customContent != null) {
            // Need to clone customContent before adding, because otherwise it can no longer be
            // parceled independently of remoteViews.
            customContent = customContent.clone();
            if (p.mHeaderless) {
                template.removeFromParent(R.id.notification_top_line);
                // We do not know how many lines ar emote view has, so we presume it has 2;  this
                // ensures that we don't under-pad the content, which could lead to abuse, at the
                // cost of making single-line custom content over-padded.
                Builder.setHeaderlessVerticalMargins(template, p, true /* hasSecondLine */);
            } else {
                // also update the end margin to account for the large icon or expander
                Resources resources = context.getResources();
                result.mTitleMarginSet.applyToView(template, R.id.notification_main_column,
                        resources.getDimension(R.dimen.notification_content_margin_end)
                                / resources.getDisplayMetrics().density);
            }
            template.removeAllViewsExceptId(R.id.notification_main_column, R.id.progress);
            template.addView(R.id.notification_main_column, customContent, 0 /* index */);
            template.addFlags(RemoteViews.FLAG_REAPPLY_DISALLOWED);
            childIndex = 0;
        }
        template.setIntTag(R.id.notification_main_column,
                com.android.internal.R.id.notification_custom_view_index_tag,
                childIndex);
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
            // TODO(jeffdq): set the view type based on the layout resource?
            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_UNSPECIFIED)
                    .fillTextsFrom(mBuilder);
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

            return mBuilder.applyStandardTemplateWithActions(layoutId, p, result);
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
        private Icon mPictureIcon;
        private Icon mBigLargeIcon;
        private boolean mBigLargeIconSet = false;
        private CharSequence mPictureContentDescription;
        private boolean mShowBigPictureWhenCollapsed;

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
        @NonNull
        public BigPictureStyle setBigContentTitle(@Nullable CharSequence title) {
            internalSetBigContentTitle(safeCharSequence(title));
            return this;
        }

        /**
         * Set the first line of text after the detail section in the big form of the template.
         */
        @NonNull
        public BigPictureStyle setSummaryText(@Nullable CharSequence cs) {
            internalSetSummaryText(safeCharSequence(cs));
            return this;
        }

        /**
         * Set the content description of the big picture.
         */
        @NonNull
        public BigPictureStyle setContentDescription(
                @Nullable CharSequence contentDescription) {
            mPictureContentDescription = contentDescription;
            return this;
        }

        /**
         * @hide
         */
        @Nullable
        public Icon getBigPicture() {
            if (mPictureIcon != null) {
                return mPictureIcon;
            }
            return null;
        }

        /**
         * Provide the bitmap to be used as the payload for the BigPicture notification.
         */
        @NonNull
        public BigPictureStyle bigPicture(@Nullable Bitmap b) {
            mPictureIcon = b == null ? null : Icon.createWithBitmap(b);
            return this;
        }

        /**
         * Provide the content Uri to be used as the payload for the BigPicture notification.
         */
        @NonNull
        public BigPictureStyle bigPicture(@Nullable Icon icon) {
            mPictureIcon = icon;
            return this;
        }

        /**
         * When set, the {@link #bigPicture(Bitmap) big picture} of this style will be promoted and
         * shown in place of the {@link Builder#setLargeIcon(Icon) large icon} in the collapsed
         * state of this notification.
         */
        @NonNull
        public BigPictureStyle showBigPictureWhenCollapsed(boolean show) {
            mShowBigPictureWhenCollapsed = show;
            return this;
        }

        /**
         * Override the large icon when the big notification is shown.
         */
        @NonNull
        public BigPictureStyle bigLargeIcon(@Nullable Bitmap b) {
            return bigLargeIcon(b != null ? Icon.createWithBitmap(b) : null);
        }

        /**
         * Override the large icon when the big notification is shown.
         */
        @NonNull
        public BigPictureStyle bigLargeIcon(@Nullable Icon icon) {
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
            if (mPictureIcon != null) {
                mPictureIcon.convertToAshmem();
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
            if (mPictureIcon != null) {
                int maxPictureWidth = resources.getDimensionPixelSize(isLowRam
                        ? R.dimen.notification_big_picture_max_height_low_ram
                        : R.dimen.notification_big_picture_max_height);
                int maxPictureHeight = resources.getDimensionPixelSize(isLowRam
                        ? R.dimen.notification_big_picture_max_width_low_ram
                        : R.dimen.notification_big_picture_max_width);
                mPictureIcon.scaleDownIfNecessary(maxPictureWidth, maxPictureHeight);
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
        @Override
        public RemoteViews makeContentView(boolean increasedHeight) {
            if (mPictureIcon == null || !mShowBigPictureWhenCollapsed) {
                return super.makeContentView(increasedHeight);
            }

            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_NORMAL)
                    .fillTextsFrom(mBuilder)
                    .promotedPicture(mPictureIcon);
            return getStandardView(mBuilder.getBaseLayoutResource(), p, null /* result */);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            if (mPictureIcon == null || !mShowBigPictureWhenCollapsed) {
                return super.makeHeadsUpContentView(increasedHeight);
            }

            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_HEADS_UP)
                    .fillTextsFrom(mBuilder)
                    .promotedPicture(mPictureIcon);
            return getStandardView(mBuilder.getHeadsUpBaseLayoutResource(), p, null /* result */);
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

            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_BIG).fillTextsFrom(mBuilder);
            RemoteViews contentView = getStandardView(mBuilder.getBigPictureLayoutResource(),
                    p, null /* result */);
            if (mSummaryTextSet) {
                contentView.setTextViewText(R.id.text, mBuilder.processTextSpans(
                        mBuilder.processLegacyText(mSummaryText)));
                mBuilder.setTextViewColorSecondary(contentView, R.id.text, p);
                contentView.setViewVisibility(R.id.text, View.VISIBLE);
            }

            if (mBigLargeIconSet) {
                mBuilder.mN.mLargeIcon = oldLargeIcon;
                mBuilder.mN.largeIcon = largeIconLegacy;
            }

            contentView.setImageViewIcon(R.id.big_picture, mPictureIcon);

            if (mPictureContentDescription != null) {
                contentView.setContentDescription(R.id.big_picture, mPictureContentDescription);
            }

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
            if (mPictureContentDescription != null) {
                extras.putCharSequence(EXTRA_PICTURE_CONTENT_DESCRIPTION,
                        mPictureContentDescription);
            }
            extras.putBoolean(EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED, mShowBigPictureWhenCollapsed);

            // If the icon contains a bitmap, use the old extra so that listeners which look for
            // that extra can still find the picture.  Don't include the new extra in that case,
            // to avoid duplicating data.
            if (mPictureIcon != null && mPictureIcon.getType() == Icon.TYPE_BITMAP) {
                extras.putParcelable(EXTRA_PICTURE, mPictureIcon.getBitmap());
                extras.putParcelable(EXTRA_PICTURE_ICON, null);
            } else {
                extras.putParcelable(EXTRA_PICTURE, null);
                extras.putParcelable(EXTRA_PICTURE_ICON, mPictureIcon);
            }
        }

        /**
         * @hide
         */
        @Override
        protected void restoreFromExtras(Bundle extras) {
            super.restoreFromExtras(extras);

            if (extras.containsKey(EXTRA_LARGE_ICON_BIG)) {
                mBigLargeIconSet = true;
                mBigLargeIcon = extras.getParcelable(EXTRA_LARGE_ICON_BIG, Icon.class);
            }

            if (extras.containsKey(EXTRA_PICTURE_CONTENT_DESCRIPTION)) {
                mPictureContentDescription =
                        extras.getCharSequence(EXTRA_PICTURE_CONTENT_DESCRIPTION);
            }

            mShowBigPictureWhenCollapsed = extras.getBoolean(EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED);

            mPictureIcon = getPictureIcon(extras);
        }

        /** @hide */
        @Nullable
        public static Icon getPictureIcon(@Nullable Bundle extras) {
            if (extras == null) return null;
            // When this style adds a picture, we only add one of the keys.  If both were added,
            // it would most likely be a legacy app trying to override the picture in some way.
            // Because of that case it's better to give precedence to the legacy field.
            Bitmap bitmapPicture = extras.getParcelable(EXTRA_PICTURE, Bitmap.class);
            if (bitmapPicture != null) {
                return Icon.createWithBitmap(bitmapPicture);
            } else {
                return extras.getParcelable(EXTRA_PICTURE_ICON, Icon.class);
            }
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
            return areIconsObviouslyDifferent(getBigPicture(), otherS.getBigPicture());
        }

        private static boolean areIconsObviouslyDifferent(Icon a, Icon b) {
            if (a == b) {
                return false;
            }
            if (a == null || b == null) {
                return true;
            }
            if (a.sameAs(b)) {
                return false;
            }
            final int aType = a.getType();
            if (aType != b.getType()) {
                return true;
            }
            if (aType == Icon.TYPE_BITMAP || aType == Icon.TYPE_ADAPTIVE_BITMAP) {
                final Bitmap aBitmap = a.getBitmap();
                final Bitmap bBitmap = b.getBitmap();
                return aBitmap.getWidth() != bBitmap.getWidth()
                        || aBitmap.getHeight() != bBitmap.getHeight()
                        || aBitmap.getConfig() != bBitmap.getConfig()
                        || aBitmap.getGenerationId() != bBitmap.getGenerationId();
            }
            return true;
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
                ArrayList<Action> originalActions = mBuilder.mActions;
                mBuilder.mActions = new ArrayList<>();
                RemoteViews remoteViews = makeBigContentView();
                mBuilder.mActions = originalActions;
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
                // TODO(b/163626038): pass VIEW_TYPE_HEADS_UP?
                return makeBigContentView();
            }
            return super.makeHeadsUpContentView(increasedHeight);
        }

        /**
         * @hide
         */
        public RemoteViews makeBigContentView() {
            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_BIG)
                    .allowTextWithProgress(true)
                    .textViewId(R.id.big_text)
                    .fillTextsFrom(mBuilder);

            // Replace the text with the big text, but only if the big text is not empty.
            CharSequence bigTextText = mBuilder.processLegacyText(mBigText);
            if (!TextUtils.isEmpty(bigTextText)) {
                p.text(bigTextText);
            }

            return getStandardView(mBuilder.getBigTextLayoutResource(), p, null /* result */);
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
     * If the app is targeting Android {@link android.os.Build.VERSION_CODES#P} and above, it is
     * required to use the {@link Person} class in order to get an optimal rendering of the
     * notification and its avatars. For conversations involving multiple people, the app should
     * also make sure that it marks the conversation as a group with
     * {@link #setGroupConversation(boolean)}.
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


        /** @hide */
        public static final int CONVERSATION_TYPE_LEGACY = 0;
        /** @hide */
        public static final int CONVERSATION_TYPE_NORMAL = 1;
        /** @hide */
        public static final int CONVERSATION_TYPE_IMPORTANT = 2;

        /** @hide */
        @IntDef(prefix = {"CONVERSATION_TYPE_"}, value = {
                CONVERSATION_TYPE_LEGACY, CONVERSATION_TYPE_NORMAL, CONVERSATION_TYPE_IMPORTANT
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ConversationType {}

        @NonNull Person mUser;
        @Nullable CharSequence mConversationTitle;
        @Nullable Icon mShortcutIcon;
        List<Message> mMessages = new ArrayList<>();
        List<Message> mHistoricMessages = new ArrayList<>();
        boolean mIsGroupConversation;
        @ConversationType int mConversationType = CONVERSATION_TYPE_LEGACY;
        int mUnreadMessageCount;

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
         * by this user. The user also needs to have a valid name associated with it, which is
         * enforced starting in Android {@link android.os.Build.VERSION_CODES#P}.
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
            if (mConversationType == CONVERSATION_TYPE_LEGACY
                    && !TextUtils.isEmpty(conversationTitle) && !hasOnlyWhiteSpaceSenders()) {
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
         * <p>Starting in {@link Build.VERSION_CODES#R}, this conversation title will be ignored
         * if a valid shortcutId is added via {@link Notification.Builder#setShortcutId(String)}.
         * In this case, {@link ShortcutInfo#getLongLabel()} (or, if missing,
         * {@link ShortcutInfo#getShortLabel()}) will be shown as the conversation title
         * instead.
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
         * Sets the icon to be displayed on the conversation, derived from the shortcutId.
         *
         * @hide
         */
        public MessagingStyle setShortcutIcon(@Nullable Icon conversationIcon) {
            // TODO(b/228941516): This icon should be downscaled to avoid using too much memory,
            // see reduceImageSizes.
            mShortcutIcon = conversationIcon;
            return this;
        }

        /**
         * Return the icon to be displayed on this conversation, derived from the shortcutId. May
         * return {@code null}.
         *
         * @hide
         */
        @Nullable
        public Icon getShortcutIcon() {
            return mShortcutIcon;
        }

        /**
         * Sets the conversation type of this MessageStyle notification.
         * {@link #CONVERSATION_TYPE_LEGACY} will use the "older" layout from pre-R,
         * {@link #CONVERSATION_TYPE_NORMAL} will use the new "conversation" layout, and
         * {@link #CONVERSATION_TYPE_IMPORTANT} will add additional "important" treatments.
         *
         * @hide
         */
        public MessagingStyle setConversationType(@ConversationType int conversationType) {
            mConversationType = conversationType;
            return this;
        }

        /** @hide */
        @ConversationType
        public int getConversationType() {
            return mConversationType;
        }

        /** @hide */
        public int getUnreadMessageCount() {
            return mUnreadMessageCount;
        }

        /** @hide */
        public MessagingStyle setUnreadMessageCount(int unreadMessageCount) {
            mUnreadMessageCount = unreadMessageCount;
            return this;
        }

        /**
         * Adds a message for display by this notification. Convenience call for a simple
         * {@link Message} in {@link #addMessage(Notification.MessagingStyle.Message)}.
         * @param text A {@link CharSequence} to be displayed as the message content
         * @param timestamp Time in milliseconds at which the message arrived
         * @param sender A {@link CharSequence} to be used for displaying the name of the
         * sender. Should be <code>null</code> for messages by the current user, in which case
         * the platform will insert {@link #getUserDisplayName()}.
         * Should be unique amongst all individuals in the conversation, and should be
         * consistent during re-posts of the notification.
         *
         * @see Message#Message(CharSequence, long, CharSequence)
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
         * @param timestamp Time in milliseconds at which the message arrived
         * @param sender The {@link Person} who sent the message.
         * Should be <code>null</code> for messages by the current user, in which case
         * the platform will insert the user set in {@code MessagingStyle(Person)}.
         *
         * @see Message#Message(CharSequence, long, CharSequence)
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
         * Gets the list of {@code Message} objects that represent the notification.
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
            if (mShortcutIcon != null) {
                extras.putParcelable(EXTRA_CONVERSATION_ICON, mShortcutIcon);
            }
            extras.putInt(EXTRA_CONVERSATION_UNREAD_MESSAGE_COUNT, mUnreadMessageCount);

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

            mUser = extras.getParcelable(EXTRA_MESSAGING_PERSON, Person.class);
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
            mUnreadMessageCount = extras.getInt(EXTRA_CONVERSATION_UNREAD_MESSAGE_COUNT);
            mShortcutIcon = extras.getParcelable(EXTRA_CONVERSATION_ICON, Icon.class);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeContentView(boolean increasedHeight) {
            // All messaging templates contain the actions
            ArrayList<Action> originalActions = mBuilder.mActions;
            try {
                mBuilder.mActions = new ArrayList<>();
                return makeMessagingView(StandardTemplateParams.VIEW_TYPE_NORMAL);
            } finally {
                mBuilder.mActions = originalActions;
            }
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
            return makeMessagingView(StandardTemplateParams.VIEW_TYPE_BIG);
        }

        /**
         * Create a messaging layout.
         *
         * @param viewType one of StandardTemplateParams.VIEW_TYPE_NORMAL, VIEW_TYPE_BIG,
         *                VIEW_TYPE_HEADS_UP
         * @return the created remoteView.
         */
        @NonNull
        private RemoteViews makeMessagingView(int viewType) {
            boolean isCollapsed = viewType != StandardTemplateParams.VIEW_TYPE_BIG;
            boolean hideRightIcons = viewType != StandardTemplateParams.VIEW_TYPE_NORMAL;
            boolean isConversationLayout = mConversationType != CONVERSATION_TYPE_LEGACY;
            boolean isImportantConversation = mConversationType == CONVERSATION_TYPE_IMPORTANT;
            boolean isHeaderless = !isConversationLayout && isCollapsed;

            CharSequence conversationTitle = !TextUtils.isEmpty(super.mBigContentTitle)
                    ? super.mBigContentTitle
                    : mConversationTitle;
            boolean atLeastP = mBuilder.mContext.getApplicationInfo().targetSdkVersion
                    >= Build.VERSION_CODES.P;
            boolean isOneToOne;
            CharSequence nameReplacement = null;
            if (!atLeastP) {
                isOneToOne = TextUtils.isEmpty(conversationTitle);
                if (hasOnlyWhiteSpaceSenders()) {
                    isOneToOne = true;
                    nameReplacement = conversationTitle;
                    conversationTitle = null;
                }
            } else {
                isOneToOne = !isGroupConversation();
            }
            if (isHeaderless && isOneToOne && TextUtils.isEmpty(conversationTitle)) {
                conversationTitle = getOtherPersonName();
            }

            Icon largeIcon = mBuilder.mN.mLargeIcon;
            TemplateBindResult bindResult = new TemplateBindResult();
            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(viewType)
                    .highlightExpander(isConversationLayout)
                    .hideProgress(true)
                    .title(isHeaderless ? conversationTitle : null)
                    .text(null)
                    .hideLeftIcon(isOneToOne)
                    .hideRightIcon(hideRightIcons || isOneToOne)
                    .headerTextSecondary(isHeaderless ? null : conversationTitle);
            RemoteViews contentView = mBuilder.applyStandardTemplateWithActions(
                    isConversationLayout
                            ? mBuilder.getConversationLayoutResource()
                            : isCollapsed
                                    ? mBuilder.getMessagingLayoutResource()
                                    : mBuilder.getBigMessagingLayoutResource(),
                    p,
                    bindResult);
            if (isConversationLayout) {
                mBuilder.setTextViewColorPrimary(contentView, R.id.conversation_text, p);
                mBuilder.setTextViewColorSecondary(contentView, R.id.app_name_divider, p);
            }

            addExtras(mBuilder.mN.extras);
            contentView.setInt(R.id.status_bar_latest_event_content, "setLayoutColor",
                    mBuilder.getSmallIconColor(p));
            contentView.setInt(R.id.status_bar_latest_event_content, "setSenderTextColor",
                    mBuilder.getPrimaryTextColor(p));
            contentView.setInt(R.id.status_bar_latest_event_content, "setMessageTextColor",
                    mBuilder.getSecondaryTextColor(p));
            contentView.setInt(R.id.status_bar_latest_event_content,
                    "setNotificationBackgroundColor",
                    mBuilder.getBackgroundColor(p));
            contentView.setBoolean(R.id.status_bar_latest_event_content, "setIsCollapsed",
                    isCollapsed);
            contentView.setIcon(R.id.status_bar_latest_event_content, "setAvatarReplacement",
                    mBuilder.mN.mLargeIcon);
            contentView.setCharSequence(R.id.status_bar_latest_event_content, "setNameReplacement",
                    nameReplacement);
            contentView.setBoolean(R.id.status_bar_latest_event_content, "setIsOneToOne",
                    isOneToOne);
            contentView.setCharSequence(R.id.status_bar_latest_event_content,
                    "setConversationTitle", conversationTitle);
            if (isConversationLayout) {
                contentView.setIcon(R.id.status_bar_latest_event_content,
                        "setShortcutIcon", mShortcutIcon);
                contentView.setBoolean(R.id.status_bar_latest_event_content,
                        "setIsImportantConversation", isImportantConversation);
            }
            if (isHeaderless) {
                // Collapsed legacy messaging style has a 1-line limit.
                contentView.setInt(R.id.notification_messaging, "setMaxDisplayedLines", 1);
            }
            contentView.setIcon(R.id.status_bar_latest_event_content, "setLargeIcon",
                    largeIcon);
            contentView.setBundle(R.id.status_bar_latest_event_content, "setData",
                    mBuilder.mN.extras);
            return contentView;
        }

        private CharSequence getKey(Person person) {
            return person == null ? null
                    : person.getKey() == null ? person.getName() : person.getKey();
        }

        private CharSequence getOtherPersonName() {
            CharSequence userKey = getKey(mUser);
            for (int i = mMessages.size() - 1; i >= 0; i--) {
                Person sender = mMessages.get(i).getSenderPerson();
                if (sender != null && !TextUtils.equals(userKey, getKey(sender))) {
                    return sender.getName();
                }
            }
            return null;
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

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            return makeMessagingView(StandardTemplateParams.VIEW_TYPE_HEADS_UP);
        }

        /**
         * @hide
         */
        @Override
        public void reduceImageSizes(Context context) {
            super.reduceImageSizes(context);
            Resources resources = context.getResources();
            boolean isLowRam = ActivityManager.isLowRamDeviceStatic();
            if (mShortcutIcon != null) {
                int maxSize = resources.getDimensionPixelSize(
                        isLowRam ? R.dimen.notification_small_icon_size_low_ram
                                : R.dimen.notification_small_icon_size);
                mShortcutIcon.scaleDownIfNecessary(maxSize, maxSize);
            }

            int maxAvatarSize = resources.getDimensionPixelSize(
                    isLowRam ? R.dimen.notification_person_icon_max_size_low_ram
                            : R.dimen.notification_person_icon_max_size);
            if (mUser != null && mUser.getIcon() != null) {
                mUser.getIcon().scaleDownIfNecessary(maxAvatarSize, maxAvatarSize);
            }

            reduceMessagesIconSizes(mMessages, maxAvatarSize);
            reduceMessagesIconSizes(mHistoricMessages, maxAvatarSize);
        }

        /**
         * @hide
         */
        private static void reduceMessagesIconSizes(@Nullable List<Message> messages, int maxSize) {
            if (messages == null) {
                return;
            }

            for (Message message : messages) {
                Person sender = message.mSender;
                if (sender != null) {
                    Icon icon = sender.getIcon();
                    if (icon != null) {
                        icon.scaleDownIfNecessary(maxSize, maxSize);
                    }
                }
            }
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
             *  {@link Notification#EXTRA_REMOTE_INPUT_HISTORY_ITEMS}
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
             * {@link Notification#EXTRA_REMOTE_INPUT_HISTORY_ITEMS}.
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
                mText = safeCharSequence(text);
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
             * {@link Notification#EXTRA_REMOTE_INPUT_HISTORY_ITEMS}.
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
                    bundle.putCharSequence(KEY_SENDER, safeCharSequence(mSender.getName()));
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
             * Returns a list of messages read from the given bundle list, e.g.
             * {@link #EXTRA_MESSAGES} or {@link #EXTRA_HISTORIC_MESSAGES}.
             */
            @NonNull
            public static List<Message> getMessagesFromBundleArray(@Nullable Parcelable[] bundles) {
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
             * Returns the message that is stored in the bundle (e.g. one of the values in the lists
             * in {@link #EXTRA_MESSAGES} or {@link #EXTRA_HISTORIC_MESSAGES}) or null if the
             * message couldn't be resolved.
             * @hide
             */
            @Nullable
            public static Message getMessageFromBundle(@NonNull Bundle bundle) {
                try {
                    if (!bundle.containsKey(KEY_TEXT) || !bundle.containsKey(KEY_TIMESTAMP)) {
                        return null;
                    } else {

                        Person senderPerson = bundle.getParcelable(KEY_SENDER_PERSON, Person.class);
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
                                    bundle.getParcelable(KEY_DATA_URI, Uri.class));
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
            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_BIG)
                    .fillTextsFrom(mBuilder).text(null);
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
            RemoteInputHistoryItem[] remoteInputHistory = getParcelableArrayFromBundle(
                    mBuilder.mN.extras, EXTRA_REMOTE_INPUT_HISTORY_ITEMS,
                    RemoteInputHistoryItem.class);
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
        private CharSequence mDeviceName;
        private int mDeviceIcon;
        private PendingIntent mDeviceIntent;

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
         * For media notifications associated with playback on a remote device, provide device
         * information that will replace the default values for the output switcher chip on the
         * media control, as well as an intent to use when the output switcher chip is tapped,
         * on devices where this is supported.
         * <p>
         * This method is intended for system applications to provide information and/or
         * functionality that would otherwise be unavailable to the default output switcher because
         * the media originated on a remote device.
         *
         * @param deviceName The name of the remote device to display
         * @param iconResource Icon resource representing the device
         * @param chipIntent PendingIntent to send when the output switcher is tapped. May be
         *                   {@code null}, in which case the output switcher will be disabled.
         *                   This intent should open an Activity or it will be ignored.
         * @return MediaStyle
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
        @NonNull
        public MediaStyle setRemotePlaybackInfo(@NonNull CharSequence deviceName,
                @DrawableRes int iconResource, @Nullable PendingIntent chipIntent) {
            mDeviceName = deviceName;
            mDeviceIcon = iconResource;
            mDeviceIntent = chipIntent;
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
            return makeMediaContentView(null /* customContent */);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeBigContentView() {
            return makeMediaBigContentView(null /* customContent */);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            return makeMediaContentView(null /* customContent */);
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
            if (mDeviceName != null) {
                extras.putCharSequence(EXTRA_MEDIA_REMOTE_DEVICE, mDeviceName);
            }
            if (mDeviceIcon > 0) {
                extras.putInt(EXTRA_MEDIA_REMOTE_ICON, mDeviceIcon);
            }
            if (mDeviceIntent != null) {
                extras.putParcelable(EXTRA_MEDIA_REMOTE_INTENT, mDeviceIntent);
            }
        }

        /**
         * @hide
         */
        @Override
        protected void restoreFromExtras(Bundle extras) {
            super.restoreFromExtras(extras);

            if (extras.containsKey(EXTRA_MEDIA_SESSION)) {
                mToken = extras.getParcelable(EXTRA_MEDIA_SESSION, MediaSession.Token.class);
            }
            if (extras.containsKey(EXTRA_COMPACT_ACTIONS)) {
                mActionsToShowInCompact = extras.getIntArray(EXTRA_COMPACT_ACTIONS);
            }
            if (extras.containsKey(EXTRA_MEDIA_REMOTE_DEVICE)) {
                mDeviceName = extras.getCharSequence(EXTRA_MEDIA_REMOTE_DEVICE);
            }
            if (extras.containsKey(EXTRA_MEDIA_REMOTE_ICON)) {
                mDeviceIcon = extras.getInt(EXTRA_MEDIA_REMOTE_ICON);
            }
            if (extras.containsKey(EXTRA_MEDIA_REMOTE_INTENT)) {
                mDeviceIntent = extras.getParcelable(
                        EXTRA_MEDIA_REMOTE_INTENT, PendingIntent.class);
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
            int tintColor = mBuilder.getStandardActionColor(p);

            container.setDrawableTint(buttonId, false, tintColor,
                    PorterDuff.Mode.SRC_ATOP);

            int rippleAlpha = mBuilder.getColors(p).getRippleAlpha();
            int rippleColor = Color.argb(rippleAlpha, Color.red(tintColor), Color.green(tintColor),
                    Color.blue(tintColor));
            container.setRippleDrawableColor(buttonId, ColorStateList.valueOf(rippleColor));

            if (!tombstone) {
                container.setOnClickPendingIntent(buttonId, action.actionIntent);
            }
            container.setContentDescription(buttonId, action.title);
        }

        /** @hide */
        protected RemoteViews makeMediaContentView(@Nullable RemoteViews customContent) {
            final int numActions = mBuilder.mActions.size();
            final int numActionsToShow = Math.min(mActionsToShowInCompact == null
                    ? 0 : mActionsToShowInCompact.length, MAX_MEDIA_BUTTONS_IN_COMPACT);
            if (numActionsToShow > numActions) {
                throw new IllegalArgumentException(String.format(
                        "setShowActionsInCompactView: action %d out of bounds (max %d)",
                        numActions, numActions - 1));
            }

            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_NORMAL)
                    .hideTime(numActionsToShow > 1)       // hide if actions wider than a right icon
                    .hideSubText(numActionsToShow > 1)    // hide if actions wider than a right icon
                    .hideLeftIcon(false)                  // allow large icon on left when grouped
                    .hideRightIcon(numActionsToShow > 0)  // right icon or actions; not both
                    .hideProgress(true)
                    .fillTextsFrom(mBuilder);
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews template = mBuilder.applyStandardTemplate(
                    R.layout.notification_template_material_media, p,
                    null /* result */);

            for (int i = 0; i < MAX_MEDIA_BUTTONS_IN_COMPACT; i++) {
                if (i < numActionsToShow) {
                    final Action action = mBuilder.mActions.get(mActionsToShowInCompact[i]);
                    bindMediaActionButton(template, MEDIA_BUTTON_IDS[i], action, p);
                } else {
                    template.setViewVisibility(MEDIA_BUTTON_IDS[i], View.GONE);
                }
            }
            // Prevent a swooping expand animation when there are no actions
            boolean hasActions = numActionsToShow != 0;
            template.setViewVisibility(R.id.media_actions, hasActions ? View.VISIBLE : View.GONE);

            // Add custom view if provided by subclass.
            buildCustomContentIntoTemplate(mBuilder.mContext, template, customContent, p, result);
            return template;
        }

        /** @hide */
        protected RemoteViews makeMediaBigContentView(@Nullable RemoteViews customContent) {
            final int actionCount = Math.min(mBuilder.mActions.size(), MAX_MEDIA_BUTTONS);
            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_BIG)
                    .hideProgress(true)
                    .fillTextsFrom(mBuilder);
            TemplateBindResult result = new TemplateBindResult();
            RemoteViews template = mBuilder.applyStandardTemplate(
                    R.layout.notification_template_material_big_media, p , result);

            for (int i = 0; i < MAX_MEDIA_BUTTONS; i++) {
                if (i < actionCount) {
                    bindMediaActionButton(template,
                            MEDIA_BUTTON_IDS[i], mBuilder.mActions.get(i), p);
                } else {
                    template.setViewVisibility(MEDIA_BUTTON_IDS[i], View.GONE);
                }
            }
            buildCustomContentIntoTemplate(mBuilder.mContext, template, customContent, p, result);
            return template;
        }
    }

    /**
     * Helper class for generating large-format notifications that include a large image attachment.
     *
     * Here's how you'd set the <code>CallStyle</code> on a notification:
     * <pre class="prettyprint">
     * Notification notif = new Notification.Builder(mContext)
     *     .setSmallIcon(R.drawable.new_post)
     *     .setStyle(Notification.CallStyle.forIncomingCall(caller, declineIntent, answerIntent))
     *     .build();
     * </pre>
     */
    public static class CallStyle extends Style {

        /**
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({
                CALL_TYPE_UNKNOWN,
                CALL_TYPE_INCOMING,
                CALL_TYPE_ONGOING,
                CALL_TYPE_SCREENING
        })
        public @interface CallType {};

        /**
         * Unknown call type.
         *
         * See {@link #EXTRA_CALL_TYPE}.
         */
        public static final int CALL_TYPE_UNKNOWN = 0;

        /**
         *  Call type for incoming calls.
         *
         *  See {@link #EXTRA_CALL_TYPE}.
         */
        public static final int CALL_TYPE_INCOMING = 1;
        /**
         * Call type for ongoing calls.
         *
         * See {@link #EXTRA_CALL_TYPE}.
         */
        public static final int CALL_TYPE_ONGOING = 2;
        /**
         * Call type for calls that are being screened.
         *
         * See {@link #EXTRA_CALL_TYPE}.
         */
        public static final int CALL_TYPE_SCREENING = 3;

        /**
         * This is a key used privately on the action.extras to give spacing priority
         * to the required call actions
         */
        private static final String KEY_ACTION_PRIORITY = "key_action_priority";

        private int mCallType;
        private Person mPerson;
        private PendingIntent mAnswerIntent;
        private PendingIntent mDeclineIntent;
        private PendingIntent mHangUpIntent;
        private boolean mIsVideo;
        private Integer mAnswerButtonColor;
        private Integer mDeclineButtonColor;
        private Icon mVerificationIcon;
        private CharSequence mVerificationText;

        CallStyle() {
        }

        /**
         * Create a CallStyle for an incoming call.
         * This notification will have a decline and an answer action, will allow a single
         * custom {@link Builder#addAction(Action) action}, and will have a default
         * {@link Builder#setContentText(CharSequence) content text} for an incoming call.
         *
         * @param person        The person displayed as the caller.
         *                      The person also needs to have a non-empty name associated with it.
         * @param declineIntent The intent to be sent when the user taps the decline action
         * @param answerIntent  The intent to be sent when the user taps the answer action
         */
        @NonNull
        public static CallStyle forIncomingCall(@NonNull Person person,
                @NonNull PendingIntent declineIntent, @NonNull PendingIntent answerIntent) {
            return new CallStyle(CALL_TYPE_INCOMING, person,
                    null /* hangUpIntent */,
                    requireNonNull(declineIntent, "declineIntent is required"),
                    requireNonNull(answerIntent, "answerIntent is required")
            );
        }

        /**
         * Create a CallStyle for an ongoing call.
         * This notification will have a hang up action, will allow up to two
         * custom {@link Builder#addAction(Action) actions}, and will have a default
         * {@link Builder#setContentText(CharSequence) content text} for an ongoing call.
         *
         * @param person       The person displayed as being on the other end of the call.
         *                     The person also needs to have a non-empty name associated with it.
         * @param hangUpIntent The intent to be sent when the user taps the hang up action
         */
        @NonNull
        public static CallStyle forOngoingCall(@NonNull Person person,
                @NonNull PendingIntent hangUpIntent) {
            return new CallStyle(CALL_TYPE_ONGOING, person,
                    requireNonNull(hangUpIntent, "hangUpIntent is required"),
                    null /* declineIntent */,
                    null /* answerIntent */
            );
        }

        /**
         * Create a CallStyle for a call that is being screened.
         * This notification will have a hang up and an answer action, will allow a single
         * custom {@link Builder#addAction(Action) action}, and will have a default
         * {@link Builder#setContentText(CharSequence) content text} for a call that is being
         * screened.
         *
         * @param person       The person displayed as the caller.
         *                     The person also needs to have a non-empty name associated with it.
         * @param hangUpIntent The intent to be sent when the user taps the hang up action
         * @param answerIntent The intent to be sent when the user taps the answer action
         */
        @NonNull
        public static CallStyle forScreeningCall(@NonNull Person person,
                @NonNull PendingIntent hangUpIntent, @NonNull PendingIntent answerIntent) {
            return new CallStyle(CALL_TYPE_SCREENING, person,
                    requireNonNull(hangUpIntent, "hangUpIntent is required"),
                    null /* declineIntent */,
                    requireNonNull(answerIntent, "answerIntent is required")
            );
        }

        /**
         * @param callType The type of the call
         * @param person The person displayed for the incoming call.
         *             The user also needs to have a non-empty name associated with it.
         * @param hangUpIntent The intent to be sent when the user taps the hang up action
         * @param declineIntent The intent to be sent when the user taps the decline action
         * @param answerIntent The intent to be sent when the user taps the answer action
         */
        private CallStyle(@CallType int callType, @NonNull Person person,
                @Nullable PendingIntent hangUpIntent, @Nullable PendingIntent declineIntent,
                @Nullable PendingIntent answerIntent) {
            if (person == null || TextUtils.isEmpty(person.getName())) {
                throw new IllegalArgumentException("person must have a non-empty a name");
            }
            mCallType = callType;
            mPerson = person;
            mAnswerIntent = answerIntent;
            mDeclineIntent = declineIntent;
            mHangUpIntent = hangUpIntent;
        }

        /**
         * Sets whether the call is a video call, which may affect the icons or text used on the
         * required action buttons.
         */
        @NonNull
        public CallStyle setIsVideo(boolean isVideo) {
            mIsVideo = isVideo;
            return this;
        }

        /**
         * Optional icon to be displayed with {@link #setVerificationText(CharSequence) text}
         * as a verification status of the caller.
         */
        @NonNull
        public CallStyle setVerificationIcon(@Nullable Icon verificationIcon) {
            mVerificationIcon = verificationIcon;
            return this;
        }

        /**
         * Optional text to be displayed with an {@link #setVerificationIcon(Icon) icon}
         * as a verification status of the caller.
         */
        @NonNull
        public CallStyle setVerificationText(@Nullable CharSequence verificationText) {
            mVerificationText = safeCharSequence(verificationText);
            return this;
        }

        /**
         * Optional color to be used as a hint for the Answer action button's color.
         * The system may change this color to ensure sufficient contrast with the background.
         * The system may choose to disregard this hint if the notification is not colorized.
         */
        @NonNull
        public CallStyle setAnswerButtonColorHint(@ColorInt int color) {
            mAnswerButtonColor = color;
            return this;
        }

        /**
         * Optional color to be used as a hint for the Decline or Hang Up action button's color.
         * The system may change this color to ensure sufficient contrast with the background.
         * The system may choose to disregard this hint if the notification is not colorized.
         */
        @NonNull
        public CallStyle setDeclineButtonColorHint(@ColorInt int color) {
            mDeclineButtonColor = color;
            return this;
        }

        /** @hide */
        @Override
        public Notification buildStyled(Notification wip) {
            wip = super.buildStyled(wip);
            // ensure that the actions in the builder and notification are corrected.
            mBuilder.mActions = getActionsListWithSystemActions();
            wip.actions = new Action[mBuilder.mActions.size()];
            mBuilder.mActions.toArray(wip.actions);
            return wip;
        }

        /**
         * @hide
         */
        public boolean displayCustomViewInline() {
            // This is a lie; True is returned to make sure that the custom view is not used
            // instead of the template, but it will not actually be included.
            return true;
        }

        /**
         * @hide
         */
        @Override
        public void purgeResources() {
            super.purgeResources();
            if (mVerificationIcon != null) {
                mVerificationIcon.convertToAshmem();
            }
        }

        /**
         * @hide
         */
        @Override
        public void reduceImageSizes(Context context) {
            super.reduceImageSizes(context);
            if (mVerificationIcon != null) {
                int rightIconSize = context.getResources().getDimensionPixelSize(
                        ActivityManager.isLowRamDeviceStatic()
                                ? R.dimen.notification_right_icon_size_low_ram
                                : R.dimen.notification_right_icon_size);
                mVerificationIcon.scaleDownIfNecessary(rightIconSize, rightIconSize);
            }
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeContentView(boolean increasedHeight) {
            return makeCallLayout(StandardTemplateParams.VIEW_TYPE_NORMAL);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            return makeCallLayout(StandardTemplateParams.VIEW_TYPE_HEADS_UP);
        }

        /**
         * @hide
         */
        public RemoteViews makeBigContentView() {
            return makeCallLayout(StandardTemplateParams.VIEW_TYPE_BIG);
        }

        @NonNull
        private Action makeNegativeAction() {
            if (mDeclineIntent == null) {
                return makeAction(R.drawable.ic_call_decline,
                        R.string.call_notification_hang_up_action,
                        mDeclineButtonColor, R.color.call_notification_decline_color,
                        mHangUpIntent);
            } else {
                return makeAction(R.drawable.ic_call_decline,
                        R.string.call_notification_decline_action,
                        mDeclineButtonColor, R.color.call_notification_decline_color,
                        mDeclineIntent);
            }
        }

        @Nullable
        private Action makeAnswerAction() {
            return mAnswerIntent == null ? null : makeAction(
                    mIsVideo ? R.drawable.ic_call_answer_video : R.drawable.ic_call_answer,
                    mIsVideo ? R.string.call_notification_answer_video_action
                            : R.string.call_notification_answer_action,
                    mAnswerButtonColor, R.color.call_notification_answer_color,
                    mAnswerIntent);
        }

        @NonNull
        private Action makeAction(@DrawableRes int icon, @StringRes int title,
                @ColorInt Integer colorInt, @ColorRes int defaultColorRes, PendingIntent intent) {
            if (colorInt == null || !mBuilder.isCallActionColorCustomizable()) {
                colorInt = mBuilder.mContext.getColor(defaultColorRes);
            }
            Action action = new Action.Builder(Icon.createWithResource("", icon),
                    new SpannableStringBuilder().append(mBuilder.mContext.getString(title),
                            new ForegroundColorSpan(colorInt),
                            SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE),
                    intent).build();
            action.getExtras().putBoolean(KEY_ACTION_PRIORITY, true);
            return action;
        }

        private boolean isActionAddedByCallStyle(Action action) {
            // This is an internal extra added by the style to these actions. If an app were to add
            // this extra to the action themselves, the action would be dropped.  :shrug:
            return action != null && action.getExtras().getBoolean(KEY_ACTION_PRIORITY);
        }

        /**
         * Gets the actions list for the call with the answer/decline/hangUp actions inserted in
         * the correct place.  This returns the correct result even if the system actions have
         * already been added, and even if more actions were added since then.
         * @hide
         */
        @NonNull
        public ArrayList<Action> getActionsListWithSystemActions() {
            // Define the system actions we expect to see
            final Action firstAction = makeNegativeAction();
            final Action lastAction = makeAnswerAction();

            // Start creating the result list.
            int nonContextualActionSlotsRemaining = MAX_ACTION_BUTTONS;
            ArrayList<Action> resultActions = new ArrayList<>(MAX_ACTION_BUTTONS);

            // Always have a first action.
            resultActions.add(firstAction);
            --nonContextualActionSlotsRemaining;

            // Copy actions into the new list, correcting system actions.
            if (mBuilder.mActions != null) {
                for (Notification.Action action : mBuilder.mActions) {
                    if (action.isContextual()) {
                        // Always include all contextual actions
                        resultActions.add(action);
                    } else if (isActionAddedByCallStyle(action)) {
                        // Drop any old versions of system actions
                    } else {
                        // Copy non-contextual actions; decrement the remaining action slots.
                        resultActions.add(action);
                        --nonContextualActionSlotsRemaining;
                    }
                    // If there's exactly one action slot left, fill it with the lastAction.
                    if (lastAction != null && nonContextualActionSlotsRemaining == 1) {
                        resultActions.add(lastAction);
                        --nonContextualActionSlotsRemaining;
                    }
                }
            }
            // If there are any action slots left, the lastAction still needs to be added.
            if (lastAction != null && nonContextualActionSlotsRemaining >= 1) {
                resultActions.add(lastAction);
            }
            return resultActions;
        }

        private RemoteViews makeCallLayout(int viewType) {
            final boolean isCollapsed = viewType == StandardTemplateParams.VIEW_TYPE_NORMAL;
            Bundle extras = mBuilder.mN.extras;
            CharSequence title = mPerson != null ? mPerson.getName() : null;
            CharSequence text = mBuilder.processLegacyText(extras.getCharSequence(EXTRA_TEXT));
            if (text == null) {
                text = getDefaultText();
            }

            // Bind standard template
            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(viewType)
                    .callStyleActions(true)
                    .allowTextWithProgress(true)
                    .hideLeftIcon(true)
                    .hideRightIcon(true)
                    .hideAppName(isCollapsed)
                    .titleViewId(R.id.conversation_text)
                    .title(title)
                    .text(text)
                    .summaryText(mBuilder.processLegacyText(mVerificationText));
            mBuilder.mActions = getActionsListWithSystemActions();
            final RemoteViews contentView;
            if (isCollapsed) {
                contentView = mBuilder.applyStandardTemplate(
                        R.layout.notification_template_material_call, p, null /* result */);
            } else {
                contentView = mBuilder.applyStandardTemplateWithActions(
                        R.layout.notification_template_material_big_call, p, null /* result */);
            }

            // Bind some extra conversation-specific header fields.
            if (!p.mHideAppName) {
                mBuilder.setTextViewColorSecondary(contentView, R.id.app_name_divider, p);
                contentView.setViewVisibility(R.id.app_name_divider, View.VISIBLE);
            }
            bindCallerVerification(contentView, p);

            // Bind some custom CallLayout properties
            contentView.setInt(R.id.status_bar_latest_event_content, "setLayoutColor",
                    mBuilder.getSmallIconColor(p));
            contentView.setInt(R.id.status_bar_latest_event_content,
                    "setNotificationBackgroundColor", mBuilder.getBackgroundColor(p));
            contentView.setIcon(R.id.status_bar_latest_event_content, "setLargeIcon",
                    mBuilder.mN.mLargeIcon);
            contentView.setBundle(R.id.status_bar_latest_event_content, "setData",
                    mBuilder.mN.extras);

            return contentView;
        }

        private void bindCallerVerification(RemoteViews contentView, StandardTemplateParams p) {
            String iconContentDescription = null;
            boolean showDivider = true;
            if (mVerificationIcon != null) {
                contentView.setImageViewIcon(R.id.verification_icon, mVerificationIcon);
                contentView.setDrawableTint(R.id.verification_icon, false /* targetBackground */,
                        mBuilder.getSecondaryTextColor(p), PorterDuff.Mode.SRC_ATOP);
                contentView.setViewVisibility(R.id.verification_icon, View.VISIBLE);
                iconContentDescription = mBuilder.mContext.getString(
                        R.string.notification_verified_content_description);
                showDivider = false;  // the icon replaces the divider
            } else {
                contentView.setViewVisibility(R.id.verification_icon, View.GONE);
            }
            if (!TextUtils.isEmpty(mVerificationText)) {
                contentView.setTextViewText(R.id.verification_text, mVerificationText);
                mBuilder.setTextViewColorSecondary(contentView, R.id.verification_text, p);
                contentView.setViewVisibility(R.id.verification_text, View.VISIBLE);
                iconContentDescription = null;  // let the app's text take precedence
            } else {
                contentView.setViewVisibility(R.id.verification_text, View.GONE);
                showDivider = false;  // no divider if no text
            }
            contentView.setContentDescription(R.id.verification_icon, iconContentDescription);
            if (showDivider) {
                contentView.setViewVisibility(R.id.verification_divider, View.VISIBLE);
                mBuilder.setTextViewColorSecondary(contentView, R.id.verification_divider, p);
            } else {
                contentView.setViewVisibility(R.id.verification_divider, View.GONE);
            }
        }

        @Nullable
        private String getDefaultText() {
            switch (mCallType) {
                case CALL_TYPE_INCOMING:
                    return mBuilder.mContext.getString(R.string.call_notification_incoming_text);
                case CALL_TYPE_ONGOING:
                    return mBuilder.mContext.getString(R.string.call_notification_ongoing_text);
                case CALL_TYPE_SCREENING:
                    return mBuilder.mContext.getString(R.string.call_notification_screening_text);
            }
            return null;
        }

        /**
         * @hide
         */
        public void addExtras(Bundle extras) {
            super.addExtras(extras);
            extras.putInt(EXTRA_CALL_TYPE, mCallType);
            extras.putBoolean(EXTRA_CALL_IS_VIDEO, mIsVideo);
            extras.putParcelable(EXTRA_CALL_PERSON, mPerson);
            if (mVerificationIcon != null) {
                extras.putParcelable(EXTRA_VERIFICATION_ICON, mVerificationIcon);
            }
            if (mVerificationText != null) {
                extras.putCharSequence(EXTRA_VERIFICATION_TEXT, mVerificationText);
            }
            if (mAnswerIntent != null) {
                extras.putParcelable(EXTRA_ANSWER_INTENT, mAnswerIntent);
            }
            if (mDeclineIntent != null) {
                extras.putParcelable(EXTRA_DECLINE_INTENT, mDeclineIntent);
            }
            if (mHangUpIntent != null) {
                extras.putParcelable(EXTRA_HANG_UP_INTENT, mHangUpIntent);
            }
            if (mAnswerButtonColor != null) {
                extras.putInt(EXTRA_ANSWER_COLOR, mAnswerButtonColor);
            }
            if (mDeclineButtonColor != null) {
                extras.putInt(EXTRA_DECLINE_COLOR, mDeclineButtonColor);
            }
            fixTitleAndTextExtras(extras);
        }

        private void fixTitleAndTextExtras(Bundle extras) {
            CharSequence sender = mPerson != null ? mPerson.getName() : null;
            if (sender != null) {
                extras.putCharSequence(EXTRA_TITLE, sender);
            }
            if (extras.getCharSequence(EXTRA_TEXT) == null) {
                extras.putCharSequence(EXTRA_TEXT, getDefaultText());
            }
        }

        /**
         * @hide
         */
        @Override
        protected void restoreFromExtras(Bundle extras) {
            super.restoreFromExtras(extras);
            mCallType = extras.getInt(EXTRA_CALL_TYPE);
            mIsVideo = extras.getBoolean(EXTRA_CALL_IS_VIDEO);
            mPerson = extras.getParcelable(EXTRA_CALL_PERSON, Person.class);
            mVerificationIcon = extras.getParcelable(EXTRA_VERIFICATION_ICON, android.graphics.drawable.Icon.class);
            mVerificationText = extras.getCharSequence(EXTRA_VERIFICATION_TEXT);
            mAnswerIntent = extras.getParcelable(EXTRA_ANSWER_INTENT, PendingIntent.class);
            mDeclineIntent = extras.getParcelable(EXTRA_DECLINE_INTENT, PendingIntent.class);
            mHangUpIntent = extras.getParcelable(EXTRA_HANG_UP_INTENT, PendingIntent.class);
            mAnswerButtonColor = extras.containsKey(EXTRA_ANSWER_COLOR)
                    ? extras.getInt(EXTRA_ANSWER_COLOR) : null;
            mDeclineButtonColor = extras.containsKey(EXTRA_DECLINE_COLOR)
                    ? extras.getInt(EXTRA_DECLINE_COLOR) : null;
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
         */
        @Override
        public boolean areNotificationsVisiblyDifferent(Style other) {
            if (other == null || getClass() != other.getClass()) {
                return true;
            }
            CallStyle otherS = (CallStyle) other;
            return !Objects.equals(mCallType, otherS.mCallType)
                    || !Objects.equals(mPerson, otherS.mPerson)
                    || !Objects.equals(mVerificationText, otherS.mVerificationText);
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
            if (headsUpContentView == null) {
                return null;  // no custom view; use the default behavior
            }
            if (mBuilder.mActions.size() == 0) {
               return makeStandardTemplateWithCustomContent(headsUpContentView);
            }
            TemplateBindResult result = new TemplateBindResult();
            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_HEADS_UP)
                    .decorationType(StandardTemplateParams.DECORATION_PARTIAL)
                    .fillTextsFrom(mBuilder);
            RemoteViews remoteViews = mBuilder.applyStandardTemplateWithActions(
                    mBuilder.getHeadsUpBaseLayoutResource(), p, result);
            buildCustomContentIntoTemplate(mBuilder.mContext, remoteViews, headsUpContentView,
                    p, result);
            return remoteViews;
        }

        private RemoteViews makeStandardTemplateWithCustomContent(RemoteViews customContent) {
            if (customContent == null) {
                return null;  // no custom view; use the default behavior
            }
            TemplateBindResult result = new TemplateBindResult();
            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_NORMAL)
                    .decorationType(StandardTemplateParams.DECORATION_PARTIAL)
                    .fillTextsFrom(mBuilder);
            RemoteViews remoteViews = mBuilder.applyStandardTemplate(
                    mBuilder.getBaseLayoutResource(), p, result);
            buildCustomContentIntoTemplate(mBuilder.mContext, remoteViews, customContent,
                    p, result);
            return remoteViews;
        }

        private RemoteViews makeDecoratedBigContentView() {
            RemoteViews bigContentView = mBuilder.mN.bigContentView == null
                    ? mBuilder.mN.contentView
                    : mBuilder.mN.bigContentView;
            if (bigContentView == null) {
                return null;  // no custom view; use the default behavior
            }
            TemplateBindResult result = new TemplateBindResult();
            StandardTemplateParams p = mBuilder.mParams.reset()
                    .viewType(StandardTemplateParams.VIEW_TYPE_BIG)
                    .decorationType(StandardTemplateParams.DECORATION_PARTIAL)
                    .fillTextsFrom(mBuilder);
            RemoteViews remoteViews = mBuilder.applyStandardTemplateWithActions(
                    mBuilder.getBigBaseLayoutResource(), p, result);
            buildCustomContentIntoTemplate(mBuilder.mContext, remoteViews, bigContentView,
                    p, result);
            return remoteViews;
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
            return makeMediaContentView(mBuilder.mN.contentView);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeBigContentView() {
            RemoteViews customContent = mBuilder.mN.bigContentView != null
                    ? mBuilder.mN.bigContentView
                    : mBuilder.mN.contentView;
            return makeMediaBigContentView(customContent);
        }

        /**
         * @hide
         */
        @Override
        public RemoteViews makeHeadsUpContentView(boolean increasedHeight) {
            RemoteViews customContent = mBuilder.mN.headsUpContentView != null
                    ? mBuilder.mN.headsUpContentView
                    : mBuilder.mN.contentView;
            return makeMediaBigContentView(customContent);
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
     * Encapsulates the information needed to display a notification as a bubble.
     *
     * <p>A bubble is used to display app content in a floating window over the existing
     * foreground activity. A bubble has a collapsed state represented by an icon and an
     * expanded state that displays an activity. These may be defined via
     * {@link Builder#Builder(PendingIntent, Icon)} or they may
     * be defined via an existing shortcut using {@link Builder#Builder(String)}.
     * </p>
     *
     * <b>Notifications with a valid and allowed bubble will display in collapsed state
     * outside of the notification shade on unlocked devices. When a user interacts with the
     * collapsed bubble, the bubble activity will be invoked and displayed.</b>
     *
     * @see Notification.Builder#setBubbleMetadata(BubbleMetadata)
     */
    public static final class BubbleMetadata implements Parcelable {

        private PendingIntent mPendingIntent;
        private PendingIntent mDeleteIntent;
        private Icon mIcon;
        private int mDesiredHeight;
        @DimenRes private int mDesiredHeightResId;
        private int mFlags;
        private String mShortcutId;

        /**
         * If set and the app creating the bubble is in the foreground, the bubble will be posted
         * in its expanded state.
         *
         * <p>This flag has no effect if the app posting the bubble is not in the foreground.
         * The app is considered foreground if it is visible and on the screen, note that
         * a foreground service does not qualify.
         * </p>
         *
         * <p>Generally this flag should only be set if the user has performed an action to request
         * or create a bubble.</p>
         *
         * @hide
         */
        public static final int FLAG_AUTO_EXPAND_BUBBLE = 0x00000001;

        /**
         * Indicates whether the notification associated with the bubble is being visually
         * suppressed from the notification shade. When <code>true</code> the notification is
         * hidden, when <code>false</code> the notification shows as normal.
         *
         * <p>Apps sending bubbles may set this flag so that the bubble is posted <b>without</b>
         * the associated notification in the notification shade.</p>
         *
         * <p>Generally this flag should only be set by the app if the user has performed an
         * action to request or create a bubble, or if the user has seen the content in the
         * notification and the notification is no longer relevant. </p>
         *
         * <p>The system will also update this flag with <code>true</code> to hide the notification
         * from the user once the bubble has been expanded. </p>
         *
         * @hide
         */
        public static final int FLAG_SUPPRESS_NOTIFICATION = 0x00000002;

        /**
         * Indicates whether the bubble should be visually suppressed from the bubble stack if the
         * user is viewing the same content outside of the bubble. For example, the user has a
         * bubble with Alice and then opens up the main app and navigates to Alice's page.
         *
         * @hide
         */
        public static final int FLAG_SUPPRESSABLE_BUBBLE = 0x00000004;

        /**
         * Indicates whether the bubble is visually suppressed from the bubble stack.
         *
         * @hide
         */
        public static final int FLAG_SUPPRESS_BUBBLE = 0x00000008;

        private BubbleMetadata(PendingIntent expandIntent, PendingIntent deleteIntent,
                Icon icon, int height, @DimenRes int heightResId, String shortcutId) {
            mPendingIntent = expandIntent;
            mIcon = icon;
            mDesiredHeight = height;
            mDesiredHeightResId = heightResId;
            mDeleteIntent = deleteIntent;
            mShortcutId = shortcutId;
        }

        private BubbleMetadata(Parcel in) {
            if (in.readInt() != 0) {
                mPendingIntent = PendingIntent.CREATOR.createFromParcel(in);
            }
            if (in.readInt() != 0) {
                mIcon = Icon.CREATOR.createFromParcel(in);
            }
            mDesiredHeight = in.readInt();
            mFlags = in.readInt();
            if (in.readInt() != 0) {
                mDeleteIntent = PendingIntent.CREATOR.createFromParcel(in);
            }
            mDesiredHeightResId = in.readInt();
            if (in.readInt() != 0) {
                mShortcutId = in.readString8();
            }
        }

        /**
         * @return the shortcut id used for this bubble if created via
         * {@link Builder#Builder(String)} or null if created
         * via {@link Builder#Builder(PendingIntent, Icon)}.
         */
        @Nullable
        public String getShortcutId() {
            return mShortcutId;
        }

        /**
         * @return the pending intent used to populate the floating window for this bubble, or
         * null if this bubble is created via {@link Builder#Builder(String)}.
         */
        @SuppressLint("InvalidNullConversion")
        @Nullable
        public PendingIntent getIntent() {
            return mPendingIntent;
        }

        /**
         * @deprecated use {@link #getIntent()} instead.
         * @removed Removed from the R SDK but was never publicly stable.
         */
        @Nullable
        @Deprecated
        public PendingIntent getBubbleIntent() {
            return mPendingIntent;
        }

        /**
         * @return the pending intent to send when the bubble is dismissed by a user, if one exists.
         */
        @Nullable
        public PendingIntent getDeleteIntent() {
            return mDeleteIntent;
        }

        /**
         * @return the icon that will be displayed for this bubble when it is collapsed, or null
         * if the bubble is created via {@link Builder#Builder(String)}.
         */
        @SuppressLint("InvalidNullConversion")
        @Nullable
        public Icon getIcon() {
            return mIcon;
        }

        /**
         * @deprecated use {@link #getIcon()} instead.
         * @removed Removed from the R SDK but was never publicly stable.
         */
        @Nullable
        @Deprecated
        public Icon getBubbleIcon() {
            return mIcon;
        }

        /**
         * @return the ideal height, in DPs, for the floating window that app content defined by
         * {@link #getIntent()} for this bubble. A value of 0 indicates a desired height has
         * not been set.
         */
        @Dimension(unit = DP)
        public int getDesiredHeight() {
            return mDesiredHeight;
        }

        /**
         * @return the resId of ideal height for the floating window that app content defined by
         * {@link #getIntent()} for this bubble. A value of 0 indicates a res value has not
         * been provided for the desired height.
         */
        @DimenRes
        public int getDesiredHeightResId() {
            return mDesiredHeightResId;
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
         * Indicates whether the notification associated with the bubble is being visually
         * suppressed from the notification shade. When <code>true</code> the notification is
         * hidden, when <code>false</code> the notification shows as normal.
         *
         * <p>Apps sending bubbles may set this flag so that the bubble is posted <b>without</b>
         * the associated notification in the notification shade.</p>
         *
         * <p>Generally the app should only set this flag if the user has performed an
         * action to request or create a bubble, or if the user has seen the content in the
         * notification and the notification is no longer relevant. </p>
         *
         * <p>The system will update this flag with <code>true</code> to hide the notification
         * from the user once the bubble has been expanded.</p>
         *
         * @return whether this bubble should suppress the notification when it is posted.
         *
         * @see BubbleMetadata.Builder#setSuppressNotification(boolean)
         */
        public boolean isNotificationSuppressed() {
            return (mFlags & FLAG_SUPPRESS_NOTIFICATION) != 0;
        }

        /**
         * Indicates whether the bubble should be visually suppressed from the bubble stack if the
         * user is viewing the same content outside of the bubble. For example, the user has a
         * bubble with Alice and then opens up the main app and navigates to Alice's page.
         *
         * To match the activity and the bubble notification, the bubble notification should
         * have a locus id set that matches a locus id set on the activity.
         *
         * @return whether this bubble should be suppressed when the same content is visible
         * outside of the bubble.
         *
         * @see BubbleMetadata.Builder#setSuppressableBubble(boolean)
         */
        public boolean isBubbleSuppressable() {
            return (mFlags & FLAG_SUPPRESSABLE_BUBBLE) != 0;
        }

        /**
         * Indicates whether the bubble is currently visually suppressed from the bubble stack.
         *
         * @see BubbleMetadata.Builder#setSuppressableBubble(boolean)
         */
        public boolean isBubbleSuppressed() {
            return (mFlags & FLAG_SUPPRESS_BUBBLE) != 0;
        }

        /**
         * Sets whether the notification associated with the bubble is being visually
         * suppressed from the notification shade. When <code>true</code> the notification is
         * hidden, when <code>false</code> the notification shows as normal.
         *
         * @hide
         */
        public void setSuppressNotification(boolean suppressed) {
            if (suppressed) {
                mFlags |= Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
            } else {
                mFlags &= ~Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
            }
        }

        /**
         * Sets whether the bubble should be visually suppressed from the bubble stack if the
         * user is viewing the same content outside of the bubble. For example, the user has a
         * bubble with Alice and then opens up the main app and navigates to Alice's page.
         *
         * @hide
         */
        public void setSuppressBubble(boolean suppressed) {
            if (suppressed) {
                mFlags |= Notification.BubbleMetadata.FLAG_SUPPRESS_BUBBLE;
            } else {
                mFlags &= ~Notification.BubbleMetadata.FLAG_SUPPRESS_BUBBLE;
            }
        }

        /**
         * @hide
         */
        public void setFlags(int flags) {
            mFlags = flags;
        }

        /**
         * @hide
         */
        public int getFlags() {
            return mFlags;
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
            out.writeInt(mPendingIntent != null ? 1 : 0);
            if (mPendingIntent != null) {
                mPendingIntent.writeToParcel(out, 0);
            }
            out.writeInt(mIcon != null ? 1 : 0);
            if (mIcon != null) {
                mIcon.writeToParcel(out, 0);
            }
            out.writeInt(mDesiredHeight);
            out.writeInt(mFlags);
            out.writeInt(mDeleteIntent != null ? 1 : 0);
            if (mDeleteIntent != null) {
                mDeleteIntent.writeToParcel(out, 0);
            }
            out.writeInt(mDesiredHeightResId);
            out.writeInt(TextUtils.isEmpty(mShortcutId) ? 0 : 1);
            if (!TextUtils.isEmpty(mShortcutId)) {
                out.writeString8(mShortcutId);
            }
        }

        /**
         * Builder to construct a {@link BubbleMetadata} object.
         */
        public static final class Builder {

            private PendingIntent mPendingIntent;
            private Icon mIcon;
            private int mDesiredHeight;
            @DimenRes private int mDesiredHeightResId;
            private int mFlags;
            private PendingIntent mDeleteIntent;
            private String mShortcutId;

            /**
             * @deprecated use {@link Builder#Builder(String)} for a bubble created via a
             * {@link ShortcutInfo} or {@link Builder#Builder(PendingIntent, Icon)} for a bubble
             * created via a {@link PendingIntent}.
             */
            @Deprecated
            public Builder() {
            }

            /**
             * Creates a {@link BubbleMetadata.Builder} based on a {@link ShortcutInfo}. To create
             * a shortcut bubble, ensure that the shortcut associated with the provided
             * {@param shortcutId} is published as a dynamic shortcut that was built with
             * {@link ShortcutInfo.Builder#setLongLived(boolean)} being true, otherwise your
             * notification will not be able to bubble.
             *
             * <p>The shortcut icon will be used to represent the bubble when it is collapsed.</p>
             *
             * <p>The shortcut activity will be used when the bubble is expanded. This will display
             * the shortcut activity in a floating window over the existing foreground activity.</p>
             *
             * <p>When the activity is launched from a bubble,
             * {@link Activity#isLaunchedFromBubble()} will return with {@code true}.
             * </p>
             *
             * <p>If the shortcut has not been published when the bubble notification is sent,
             * no bubble will be produced. If the shortcut is deleted while the bubble is active,
             * the bubble will be removed.</p>
             *
             * @throws NullPointerException if shortcutId is null.
             *
             * @see ShortcutInfo
             * @see ShortcutInfo.Builder#setLongLived(boolean)
             * @see android.content.pm.ShortcutManager#addDynamicShortcuts(List)
             */
            public Builder(@NonNull String shortcutId) {
                if (TextUtils.isEmpty(shortcutId)) {
                    throw new NullPointerException("Bubble requires a non-null shortcut id");
                }
                mShortcutId = shortcutId;
            }

            /**
             * Creates a {@link BubbleMetadata.Builder} based on the provided intent and icon.
             *
             * <p>The icon will be used to represent the bubble when it is collapsed. An icon
             * should be representative of the content within the bubble. If your app produces
             * multiple bubbles, the icon should be unique for each of them.</p>
             *
             * <p>The intent that will be used when the bubble is expanded. This will display the
             * app content in a floating window over the existing foreground activity. The intent
             * should point to a resizable activity. </p>
             *
             * <p>When the activity is launched from a bubble,
             * {@link Activity#isLaunchedFromBubble()} will return with {@code true}.
             * </p>
             *
             * Note that the pending intent used here requires PendingIntent.FLAG_MUTABLE.
             *
             * @throws NullPointerException if intent is null.
             * @throws NullPointerException if icon is null.
             */
            public Builder(@NonNull PendingIntent intent, @NonNull Icon icon) {
                if (intent == null) {
                    throw new NullPointerException("Bubble requires non-null pending intent");
                }
                if (icon == null) {
                    throw new NullPointerException("Bubbles require non-null icon");
                }
                if (icon.getType() != TYPE_URI_ADAPTIVE_BITMAP
                        && icon.getType() != TYPE_URI) {
                    Log.w(TAG, "Bubbles work best with icons of TYPE_URI or "
                            + "TYPE_URI_ADAPTIVE_BITMAP. "
                            + "In the future, using an icon of this type will be required.");
                }
                mPendingIntent = intent;
                mIcon = icon;
            }

            /**
             * @deprecated use {@link Builder#Builder(String)} instead.
             * @removed Removed from the R SDK but was never publicly stable.
             */
            @NonNull
            @Deprecated
            public BubbleMetadata.Builder createShortcutBubble(@NonNull String shortcutId) {
                if (!TextUtils.isEmpty(shortcutId)) {
                    // If shortcut id is set, we don't use these if they were previously set.
                    mPendingIntent = null;
                    mIcon = null;
                }
                mShortcutId = shortcutId;
                return this;
            }

            /**
             * @deprecated use {@link Builder#Builder(PendingIntent, Icon)} instead.
             * @removed Removed from the R SDK but was never publicly stable.
             */
            @NonNull
            @Deprecated
            public BubbleMetadata.Builder createIntentBubble(@NonNull PendingIntent intent,
                    @NonNull Icon icon) {
                if (intent == null) {
                    throw new IllegalArgumentException("Bubble requires non-null pending intent");
                }
                if (icon == null) {
                    throw new IllegalArgumentException("Bubbles require non-null icon");
                }
                if (icon.getType() != TYPE_URI_ADAPTIVE_BITMAP
                        && icon.getType() != TYPE_URI) {
                    Log.w(TAG, "Bubbles work best with icons of TYPE_URI or "
                            + "TYPE_URI_ADAPTIVE_BITMAP. "
                            + "In the future, using an icon of this type will be required.");
                }
                mShortcutId = null;
                mPendingIntent = intent;
                mIcon = icon;
                return this;
            }

            /**
             * Sets the intent for the bubble.
             *
             * <p>The intent that will be used when the bubble is expanded. This will display the
             * app content in a floating window over the existing foreground activity. The intent
             * should point to a resizable activity. </p>
             *
             * @throws NullPointerException  if intent is null.
             * @throws IllegalStateException if this builder was created via
             *                               {@link Builder#Builder(String)}.
             */
            @NonNull
            public BubbleMetadata.Builder setIntent(@NonNull PendingIntent intent) {
                if (mShortcutId != null) {
                    throw new IllegalStateException("Created as a shortcut bubble, cannot set a "
                            + "PendingIntent. Consider using "
                            + "BubbleMetadata.Builder(PendingIntent,Icon) instead.");
                }
                if (intent == null) {
                    throw new NullPointerException("Bubble requires non-null pending intent");
                }
                mPendingIntent = intent;
                return this;
            }

            /**
             * Sets the icon for the bubble. Can only be used if the bubble was created
             * via {@link Builder#Builder(PendingIntent, Icon)}.
             *
             * <p>The icon will be used to represent the bubble when it is collapsed. An icon
             * should be representative of the content within the bubble. If your app produces
             * multiple bubbles, the icon should be unique for each of them.</p>
             *
             * <p>It is recommended to use an {@link Icon} of type {@link Icon#TYPE_URI}
             * or {@link Icon#TYPE_URI_ADAPTIVE_BITMAP}</p>
             *
             * @throws NullPointerException  if icon is null.
             * @throws IllegalStateException if this builder was created via
             *                               {@link Builder#Builder(String)}.
             */
            @NonNull
            public BubbleMetadata.Builder setIcon(@NonNull Icon icon) {
                if (mShortcutId != null) {
                    throw new IllegalStateException("Created as a shortcut bubble, cannot set an "
                            + "Icon. Consider using "
                            + "BubbleMetadata.Builder(PendingIntent,Icon) instead.");
                }
                if (icon == null) {
                    throw new NullPointerException("Bubbles require non-null icon");
                }
                if (icon.getType() != TYPE_URI_ADAPTIVE_BITMAP
                        && icon.getType() != TYPE_URI) {
                    Log.w(TAG, "Bubbles work best with icons of TYPE_URI or "
                            + "TYPE_URI_ADAPTIVE_BITMAP. "
                            + "In the future, using an icon of this type will be required.");
                }
                mIcon = icon;
                return this;
            }

            /**
             * Sets the desired height in DPs for the expanded content of the bubble.
             *
             * <p>This height may not be respected if there is not enough space on the screen or if
             * the provided height is too small to be useful.</p>
             *
             * <p>If {@link #setDesiredHeightResId(int)} was previously called on this builder, the
             * previous value set will be cleared after calling this method, and this value will
             * be used instead.</p>
             *
             * <p>A desired height (in DPs or via resID) is optional.</p>
             *
             * @see #setDesiredHeightResId(int)
             */
            @NonNull
            public BubbleMetadata.Builder setDesiredHeight(@Dimension(unit = DP) int height) {
                mDesiredHeight = Math.max(height, 0);
                mDesiredHeightResId = 0;
                return this;
            }


            /**
             * Sets the desired height via resId for the expanded content of the bubble.
             *
             * <p>This height may not be respected if there is not enough space on the screen or if
             * the provided height is too small to be useful.</p>
             *
             * <p>If {@link #setDesiredHeight(int)} was previously called on this builder, the
             * previous value set will be cleared after calling this method, and this value will
             * be used instead.</p>
             *
             * <p>A desired height (in DPs or via resID) is optional.</p>
             *
             * @see #setDesiredHeight(int)
             */
            @NonNull
            public BubbleMetadata.Builder setDesiredHeightResId(@DimenRes int heightResId) {
                mDesiredHeightResId = heightResId;
                mDesiredHeight = 0;
                return this;
            }

            /**
             * Sets whether the bubble will be posted in its expanded state.
             *
             * <p>This flag has no effect if the app posting the bubble is not in the foreground.
             * The app is considered foreground if it is visible and on the screen, note that
             * a foreground service does not qualify.
             * </p>
             *
             * <p>Generally, this flag should only be set if the user has performed an action to
             * request or create a bubble.</p>
             *
             * <p>Setting this flag is optional; it defaults to false.</p>
             */
            @NonNull
            public BubbleMetadata.Builder setAutoExpandBubble(boolean shouldExpand) {
                setFlag(FLAG_AUTO_EXPAND_BUBBLE, shouldExpand);
                return this;
            }

            /**
             * Sets whether the bubble will be posted <b>without</b> the associated notification in
             * the notification shade.
             *
             * <p>Generally, this flag should only be set if the user has performed an action to
             * request or create a bubble, or if the user has seen the content in the notification
             * and the notification is no longer relevant.</p>
             *
             * <p>Setting this flag is optional; it defaults to false.</p>
             */
            @NonNull
            public BubbleMetadata.Builder setSuppressNotification(boolean shouldSuppressNotif) {
                setFlag(FLAG_SUPPRESS_NOTIFICATION, shouldSuppressNotif);
                return this;
            }

            /**
             * Indicates whether the bubble should be visually suppressed from the bubble stack if
             * the user is viewing the same content outside of the bubble. For example, the user has
             * a bubble with Alice and then opens up the main app and navigates to Alice's page.
             *
             * To match the activity and the bubble notification, the bubble notification should
             * have a locus id set that matches a locus id set on the activity.
             *
             * {@link Notification.Builder#setLocusId(LocusId)}
             * {@link Activity#setLocusContext(LocusId, Bundle)}
             */
            @NonNull
            public BubbleMetadata.Builder setSuppressableBubble(boolean suppressBubble) {
                setFlag(FLAG_SUPPRESSABLE_BUBBLE, suppressBubble);
                return this;
            }

            /**
             * Sets an intent to send when this bubble is explicitly removed by the user.
             *
             * <p>Setting a delete intent is optional.</p>
             */
            @NonNull
            public BubbleMetadata.Builder setDeleteIntent(@Nullable PendingIntent deleteIntent) {
                mDeleteIntent = deleteIntent;
                return this;
            }

            /**
             * Creates the {@link BubbleMetadata} defined by this builder.
             *
             * @throws NullPointerException if required elements have not been set.
             */
            @NonNull
            public BubbleMetadata build() {
                if (mShortcutId == null && mPendingIntent == null) {
                    throw new NullPointerException(
                            "Must supply pending intent or shortcut to bubble");
                }
                if (mShortcutId == null && mIcon == null) {
                    throw new NullPointerException(
                            "Must supply an icon or shortcut for the bubble");
                }
                BubbleMetadata data = new BubbleMetadata(mPendingIntent, mDeleteIntent,
                        mIcon, mDesiredHeight, mDesiredHeightResId, mShortcutId);
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
        static final String KEY_DISPLAY_INTENT = "displayIntent";
        private static final String KEY_PAGES = "pages";
        static final String KEY_BACKGROUND = "background";
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
                List<Action> actions = wearableBundle.getParcelableArrayList(KEY_ACTIONS, android.app.Notification.Action.class);
                if (actions != null) {
                    mActions.addAll(actions);
                }

                mFlags = wearableBundle.getInt(KEY_FLAGS, DEFAULT_FLAGS);
                mDisplayIntent = wearableBundle.getParcelable(
                        KEY_DISPLAY_INTENT, PendingIntent.class);

                Notification[] pages = getParcelableArrayFromBundle(
                        wearableBundle, KEY_PAGES, Notification.class);
                if (pages != null) {
                    Collections.addAll(mPages, pages);
                }

                mBackground = wearableBundle.getParcelable(KEY_BACKGROUND, Bitmap.class);
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
         *         0, displayIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED);
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
                mLargeIcon = carBundle.getParcelable(EXTRA_LARGE_ICON, Bitmap.class);
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
            static final String KEY_REMOTE_INPUT = "remote_input";
            static final String KEY_ON_REPLY = "on_reply";
            static final String KEY_ON_READ = "on_read";
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

                PendingIntent onRead = b.getParcelable(KEY_ON_READ, PendingIntent.class);
                PendingIntent onReply = b.getParcelable(KEY_ON_REPLY, PendingIntent.class);

                RemoteInput remoteInput = b.getParcelable(KEY_REMOTE_INPUT, RemoteInput.class);

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
        static final String EXTRA_CONTENT_INTENT = "content_intent";
        static final String EXTRA_DELETE_INTENT = "delete_intent";
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
                mContentIntent = bundle.getParcelable(EXTRA_CONTENT_INTENT, PendingIntent.class);
                mDeleteIntent = bundle.getParcelable(EXTRA_DELETE_INTENT, PendingIntent.class);
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
     * Get an array of Parcelable objects from a parcelable array bundle field.
     * Update the bundle to have a typed array so fetches in the future don't need
     * to do an array copy.
     */
    @Nullable
    private static <T extends Parcelable> T[] getParcelableArrayFromBundle(
            Bundle bundle, String key, Class<T> itemClass) {
        final Parcelable[] array = bundle.getParcelableArray(key);
        final Class<?> arrayClass = Array.newInstance(itemClass, 0).getClass();
        if (arrayClass.isInstance(array) || array == null) {
            return (T[]) array;
        }
        final T[] typedArray = (T[]) Array.newInstance(itemClass, array.length);
        for (int i = 0; i < array.length; i++) {
            typedArray[i] = (T) array[i];
        }
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

        /**
         * Override and return true, since {@link RemoteViews#onLoadClass(Class)} is not overridden.
         *
         * @see RemoteViews#shouldUseStaticFilter()
         */
        @Override
        protected boolean shouldUseStaticFilter() {
            return true;
        }
    }

    /**
     * A result object where information about the template that was created is saved.
     */
    private static class TemplateBindResult {
        boolean mRightIconVisible;
        float mRightIconWidthDp;
        float mRightIconHeightDp;

        /**
         * The margin end that needs to be added to the heading so that it won't overlap
         * with the large icon.  This value includes the space required to accommodate the large
         * icon, but should be added to the space needed to accommodate the expander. This does
         * not include the 16dp content margin that all notification views must have.
         */
        public final MarginSet mHeadingExtraMarginSet = new MarginSet();

        /**
         * The margin end that needs to be added to the heading so that it won't overlap
         * with the large icon.  This value includes the space required to accommodate the large
         * icon as well as the expander.  This does not include the 16dp content margin that all
         * notification views must have.
         */
        public final MarginSet mHeadingFullMarginSet = new MarginSet();

        /**
         * The margin end that needs to be added to the title text of the big state
         * so that it won't overlap with the large icon, but assuming the text can run under
         * the expander when that icon is not visible.
         */
        public final MarginSet mTitleMarginSet = new MarginSet();

        public void setRightIconState(boolean visible, float widthDp, float heightDp,
                float marginEndDpIfVisible, float expanderSizeDp) {
            mRightIconVisible = visible;
            mRightIconWidthDp = widthDp;
            mRightIconHeightDp = heightDp;
            mHeadingExtraMarginSet.setValues(0, marginEndDpIfVisible);
            mHeadingFullMarginSet.setValues(expanderSizeDp, marginEndDpIfVisible + expanderSizeDp);
            mTitleMarginSet.setValues(0, marginEndDpIfVisible + expanderSizeDp);
        }

        /**
         * This contains the end margins for a view when the right icon is visible or not.  These
         * values are both needed so that NotificationGroupingUtil can 'move' the right_icon to the
         * left_icon and adjust the margins, and to undo that change as well.
         */
        private class MarginSet {
            private float mValueIfGone;
            private float mValueIfVisible;

            public void setValues(float valueIfGone, float valueIfVisible) {
                mValueIfGone = valueIfGone;
                mValueIfVisible = valueIfVisible;
            }

            public void applyToView(@NonNull RemoteViews views, @IdRes int viewId) {
                applyToView(views, viewId, 0);
            }

            public void applyToView(@NonNull RemoteViews views, @IdRes int viewId,
                    float extraMarginDp) {
                final float marginEndDp = getDpValue() + extraMarginDp;
                if (viewId == R.id.notification_header) {
                    views.setFloat(R.id.notification_header,
                            "setTopLineExtraMarginEndDp", marginEndDp);
                } else if (viewId == R.id.text || viewId == R.id.big_text) {
                    if (mValueIfGone != 0) {
                        throw new RuntimeException("Programming error: `text` and `big_text` use "
                                + "ImageFloatingTextView which can either show a margin or not; "
                                + "thus mValueIfGone must be 0, but it was " + mValueIfGone);
                    }
                    // Note that the caller must set "setNumIndentLines" to a positive int in order
                    //  for this margin to do anything at all.
                    views.setFloat(viewId, "setImageEndMarginDp", mValueIfVisible);
                    views.setBoolean(viewId, "setHasImage", mRightIconVisible);
                    // Apply just the *extra* margin as the view layout margin; this will be
                    //  unchanged depending on the visibility of the image, but it means that the
                    //  extra margin applies to *every* line of text instead of just indented lines.
                    views.setViewLayoutMargin(viewId, RemoteViews.MARGIN_END,
                            extraMarginDp, TypedValue.COMPLEX_UNIT_DIP);
                } else {
                    views.setViewLayoutMargin(viewId, RemoteViews.MARGIN_END,
                                    marginEndDp, TypedValue.COMPLEX_UNIT_DIP);
                }
                if (mRightIconVisible) {
                    views.setIntTag(viewId, R.id.tag_margin_end_when_icon_visible,
                            TypedValue.createComplexDimension(
                                    mValueIfVisible + extraMarginDp, TypedValue.COMPLEX_UNIT_DIP));
                    views.setIntTag(viewId, R.id.tag_margin_end_when_icon_gone,
                            TypedValue.createComplexDimension(
                                    mValueIfGone + extraMarginDp, TypedValue.COMPLEX_UNIT_DIP));
                }
            }

            public float getDpValue() {
                return mRightIconVisible ? mValueIfVisible : mValueIfGone;
            }
        }
    }

    private static class StandardTemplateParams {
        /**
         * Notifications will be minimally decorated with ONLY an icon and expander:
         * <li>A large icon is never shown.
         * <li>A progress bar is never shown.
         * <li>The expanded and heads up states do not show actions, even if provided.
         */
        public static final int DECORATION_MINIMAL = 1;

        /**
         * Notifications will be partially decorated with AT LEAST an icon and expander:
         * <li>A large icon is shown if provided.
         * <li>A progress bar is shown if provided and enough space remains below the content.
         * <li>Actions are shown in the expanded and heads up states.
         */
        public static final int DECORATION_PARTIAL = 2;

        public static int VIEW_TYPE_UNSPECIFIED = 0;
        public static int VIEW_TYPE_NORMAL = 1;
        public static int VIEW_TYPE_BIG = 2;
        public static int VIEW_TYPE_HEADS_UP = 3;
        public static int VIEW_TYPE_MINIMIZED = 4;    // header only for minimized state
        public static int VIEW_TYPE_PUBLIC = 5;       // header only for automatic public version
        public static int VIEW_TYPE_GROUP_HEADER = 6; // header only for top of group

        int mViewType = VIEW_TYPE_UNSPECIFIED;
        boolean mHeaderless;
        boolean mHideAppName;
        boolean mHideTitle;
        boolean mHideSubText;
        boolean mHideTime;
        boolean mHideActions;
        boolean mHideProgress;
        boolean mHideSnoozeButton;
        boolean mHideLeftIcon;
        boolean mHideRightIcon;
        Icon mPromotedPicture;
        boolean mCallStyleActions;
        boolean mAllowTextWithProgress;
        int mTitleViewId;
        int mTextViewId;
        CharSequence title;
        CharSequence text;
        CharSequence headerTextSecondary;
        CharSequence summaryText;
        int maxRemoteInputHistory = Style.MAX_REMOTE_INPUT_HISTORY_LINES;
        boolean allowColorization  = true;
        boolean mHighlightExpander = false;

        final StandardTemplateParams reset() {
            mViewType = VIEW_TYPE_UNSPECIFIED;
            mHeaderless = false;
            mHideAppName = false;
            mHideTitle = false;
            mHideSubText = false;
            mHideTime = false;
            mHideActions = false;
            mHideProgress = false;
            mHideSnoozeButton = false;
            mHideLeftIcon = false;
            mHideRightIcon = false;
            mPromotedPicture = null;
            mCallStyleActions = false;
            mAllowTextWithProgress = false;
            mTitleViewId = R.id.title;
            mTextViewId = R.id.text;
            title = null;
            text = null;
            summaryText = null;
            headerTextSecondary = null;
            maxRemoteInputHistory = Style.MAX_REMOTE_INPUT_HISTORY_LINES;
            allowColorization = true;
            mHighlightExpander = false;
            return this;
        }

        final boolean hasTitle() {
            return !TextUtils.isEmpty(title) && !mHideTitle;
        }

        final StandardTemplateParams viewType(int viewType) {
            mViewType = viewType;
            return this;
        }

        public StandardTemplateParams headerless(boolean headerless) {
            mHeaderless = headerless;
            return this;
        }

        public StandardTemplateParams hideAppName(boolean hideAppName) {
            mHideAppName = hideAppName;
            return this;
        }

        public StandardTemplateParams hideSubText(boolean hideSubText) {
            mHideSubText = hideSubText;
            return this;
        }

        public StandardTemplateParams hideTime(boolean hideTime) {
            mHideTime = hideTime;
            return this;
        }

        final StandardTemplateParams hideActions(boolean hideActions) {
            this.mHideActions = hideActions;
            return this;
        }

        final StandardTemplateParams hideProgress(boolean hideProgress) {
            this.mHideProgress = hideProgress;
            return this;
        }

        final StandardTemplateParams hideTitle(boolean hideTitle) {
            this.mHideTitle = hideTitle;
            return this;
        }

        final StandardTemplateParams callStyleActions(boolean callStyleActions) {
            this.mCallStyleActions = callStyleActions;
            return this;
        }

        final StandardTemplateParams allowTextWithProgress(boolean allowTextWithProgress) {
            this.mAllowTextWithProgress = allowTextWithProgress;
            return this;
        }

        final StandardTemplateParams hideSnoozeButton(boolean hideSnoozeButton) {
            this.mHideSnoozeButton = hideSnoozeButton;
            return this;
        }

        final StandardTemplateParams promotedPicture(Icon promotedPicture) {
            this.mPromotedPicture = promotedPicture;
            return this;
        }

        public StandardTemplateParams titleViewId(int titleViewId) {
            mTitleViewId = titleViewId;
            return this;
        }

        public StandardTemplateParams textViewId(int textViewId) {
            mTextViewId = textViewId;
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


        final StandardTemplateParams hideLeftIcon(boolean hideLeftIcon) {
            this.mHideLeftIcon = hideLeftIcon;
            return this;
        }

        final StandardTemplateParams hideRightIcon(boolean hideRightIcon) {
            this.mHideRightIcon = hideRightIcon;
            return this;
        }

        final StandardTemplateParams disallowColorization() {
            this.allowColorization = false;
            return this;
        }

        final StandardTemplateParams highlightExpander(boolean highlight) {
            this.mHighlightExpander = highlight;
            return this;
        }

        final StandardTemplateParams fillTextsFrom(Builder b) {
            Bundle extras = b.mN.extras;
            this.title = b.processLegacyText(extras.getCharSequence(EXTRA_TITLE));
            this.text = b.processLegacyText(extras.getCharSequence(EXTRA_TEXT));
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

        public StandardTemplateParams decorationType(int decorationType) {
            hideTitle(true);
            // Minimally decorated custom views do not show certain pieces of chrome that have
            // always been shown when using DecoratedCustomViewStyle.
            boolean hideOtherFields = decorationType <= DECORATION_MINIMAL;
            hideLeftIcon(false);  // The left icon decoration is better than showing nothing.
            hideRightIcon(hideOtherFields);
            hideProgress(hideOtherFields);
            hideActions(hideOtherFields);
            return this;
        }
    }

    /**
     * A utility which stores and calculates the palette of colors used to color notifications.
     * @hide
     */
    @VisibleForTesting
    public static class Colors {
        private int mPaletteIsForRawColor = COLOR_INVALID;
        private boolean mPaletteIsForColorized = false;
        private boolean mPaletteIsForNightMode = false;
        // The following colors are the palette
        private int mBackgroundColor = COLOR_INVALID;
        private int mProtectionColor = COLOR_INVALID;
        private int mPrimaryTextColor = COLOR_INVALID;
        private int mSecondaryTextColor = COLOR_INVALID;
        private int mPrimaryAccentColor = COLOR_INVALID;
        private int mSecondaryAccentColor = COLOR_INVALID;
        private int mTertiaryAccentColor = COLOR_INVALID;
        private int mOnAccentTextColor = COLOR_INVALID;
        private int mErrorColor = COLOR_INVALID;
        private int mContrastColor = COLOR_INVALID;
        private int mRippleAlpha = 0x33;

        /**
         * A utility for obtaining a TypedArray of the given DayNight-styled attributes, which
         * returns null when the context is a mock with no theme.
         *
         * NOTE: Calling this method is expensive, as creating a new ContextThemeWrapper
         * instances can allocate as much as 5MB of memory, so its important to call this method
         * only when necessary, getting as many attributes as possible from each call.
         *
         * @see Resources.Theme#obtainStyledAttributes(int[])
         */
        @Nullable
        private static TypedArray obtainDayNightAttributes(@NonNull Context ctx,
                @NonNull @StyleableRes int[] attrs) {
            // when testing, the mock context may have no theme
            if (ctx.getTheme() == null) {
                return null;
            }
            Resources.Theme theme = new ContextThemeWrapper(ctx,
                    R.style.Theme_DeviceDefault_DayNight).getTheme();
            return theme.obtainStyledAttributes(attrs);
        }

        /** A null-safe wrapper of TypedArray.getColor because mocks return null */
        private static @ColorInt int getColor(@Nullable TypedArray ta, int index,
                @ColorInt int defValue) {
            return ta == null ? defValue : ta.getColor(index, defValue);
        }

        /**
         * Resolve the palette.  If the inputs have not changed, this will be a no-op.
         * This does not handle invalidating the resolved colors when the context itself changes,
         * because that case does not happen in the current notification inflation pipeline; we will
         * recreate a new builder (and thus a new palette) when reinflating notifications for a new
         * theme (admittedly, we do the same for night mode, but that's easy to check).
         *
         * @param ctx the builder context.
         * @param rawColor the notification's color; may be COLOR_DEFAULT, but may never have alpha.
         * @param isColorized whether the notification is colorized.
         * @param nightMode whether the UI is in night mode.
         */
        public void resolvePalette(Context ctx, int rawColor,
                boolean isColorized, boolean nightMode) {
            if (mPaletteIsForRawColor == rawColor
                    && mPaletteIsForColorized == isColorized
                    && mPaletteIsForNightMode == nightMode) {
                return;
            }
            mPaletteIsForRawColor = rawColor;
            mPaletteIsForColorized = isColorized;
            mPaletteIsForNightMode = nightMode;

            if (isColorized) {
                if (rawColor == COLOR_DEFAULT) {
                    int[] attrs = {R.attr.colorAccentSecondary};
                    try (TypedArray ta = obtainDayNightAttributes(ctx, attrs)) {
                        mBackgroundColor = getColor(ta, 0, Color.WHITE);
                    }
                } else {
                    mBackgroundColor = rawColor;
                }
                mProtectionColor = COLOR_INVALID;  // filled in at the end
                mPrimaryTextColor = ContrastColorUtil.findAlphaToMeetContrast(
                        ContrastColorUtil.resolvePrimaryColor(ctx, mBackgroundColor, nightMode),
                        mBackgroundColor, 4.5);
                mSecondaryTextColor = ContrastColorUtil.findAlphaToMeetContrast(
                        ContrastColorUtil.resolveSecondaryColor(ctx, mBackgroundColor, nightMode),
                        mBackgroundColor, 4.5);
                mContrastColor = mPrimaryTextColor;
                mPrimaryAccentColor = mPrimaryTextColor;
                mSecondaryAccentColor = mSecondaryTextColor;
                mTertiaryAccentColor = flattenAlpha(mPrimaryTextColor, mBackgroundColor);
                mOnAccentTextColor = mBackgroundColor;
                mErrorColor = mPrimaryTextColor;
                mRippleAlpha = 0x33;
            } else {
                int[] attrs = {
                        R.attr.colorSurface,
                        R.attr.colorBackgroundFloating,
                        R.attr.textColorPrimary,
                        R.attr.textColorSecondary,
                        R.attr.colorAccent,
                        R.attr.colorAccentSecondary,
                        R.attr.colorAccentTertiary,
                        R.attr.textColorOnAccent,
                        R.attr.colorError,
                        R.attr.colorControlHighlight
                };
                try (TypedArray ta = obtainDayNightAttributes(ctx, attrs)) {
                    mBackgroundColor = getColor(ta, 0, nightMode ? Color.BLACK : Color.WHITE);
                    mProtectionColor = getColor(ta, 1, COLOR_INVALID);
                    mPrimaryTextColor = getColor(ta, 2, COLOR_INVALID);
                    mSecondaryTextColor = getColor(ta, 3, COLOR_INVALID);
                    mPrimaryAccentColor = getColor(ta, 4, COLOR_INVALID);
                    mSecondaryAccentColor = getColor(ta, 5, COLOR_INVALID);
                    mTertiaryAccentColor = getColor(ta, 6, COLOR_INVALID);
                    mOnAccentTextColor = getColor(ta, 7, COLOR_INVALID);
                    mErrorColor = getColor(ta, 8, COLOR_INVALID);
                    mRippleAlpha = Color.alpha(getColor(ta, 9, 0x33ffffff));
                }
                mContrastColor = calculateContrastColor(ctx, rawColor, mPrimaryAccentColor,
                        mBackgroundColor, nightMode);

                // make sure every color has a valid value
                if (mPrimaryTextColor == COLOR_INVALID) {
                    mPrimaryTextColor = ContrastColorUtil.resolvePrimaryColor(
                            ctx, mBackgroundColor, nightMode);
                }
                if (mSecondaryTextColor == COLOR_INVALID) {
                    mSecondaryTextColor = ContrastColorUtil.resolveSecondaryColor(
                            ctx, mBackgroundColor, nightMode);
                }
                if (mPrimaryAccentColor == COLOR_INVALID) {
                    mPrimaryAccentColor = mContrastColor;
                }
                if (mSecondaryAccentColor == COLOR_INVALID) {
                    mSecondaryAccentColor = mContrastColor;
                }
                if (mTertiaryAccentColor == COLOR_INVALID) {
                    mTertiaryAccentColor = mContrastColor;
                }
                if (mOnAccentTextColor == COLOR_INVALID) {
                    mOnAccentTextColor = ColorUtils.setAlphaComponent(
                            ContrastColorUtil.resolvePrimaryColor(
                                    ctx, mTertiaryAccentColor, nightMode), 0xFF);
                }
                if (mErrorColor == COLOR_INVALID) {
                    mErrorColor = mPrimaryTextColor;
                }
            }
            // make sure every color has a valid value
            if (mProtectionColor == COLOR_INVALID) {
                mProtectionColor = ColorUtils.blendARGB(mPrimaryTextColor, mBackgroundColor, 0.8f);
            }
        }

        /** calculates the contrast color for the non-colorized notifications */
        private static @ColorInt int calculateContrastColor(Context ctx, @ColorInt int rawColor,
                @ColorInt int accentColor, @ColorInt int backgroundColor, boolean nightMode) {
            int color;
            if (rawColor == COLOR_DEFAULT) {
                color = accentColor;
                if (color == COLOR_INVALID) {
                    color = ContrastColorUtil.resolveDefaultColor(ctx, backgroundColor, nightMode);
                }
            } else {
                color = ContrastColorUtil.resolveContrastColor(ctx, rawColor, backgroundColor,
                        nightMode);
            }
            return flattenAlpha(color, backgroundColor);
        }

        /** remove any alpha by manually blending it with the given background. */
        private static @ColorInt int flattenAlpha(@ColorInt int color, @ColorInt int background) {
            return Color.alpha(color) == 0xff ? color
                    : ContrastColorUtil.compositeColors(color, background);
        }

        /** @return the notification's background color */
        public @ColorInt int getBackgroundColor() {
            return mBackgroundColor;
        }

        /**
         * @return the "surface protection" color from the theme,
         * or a variant of the normal background color when colorized.
         */
        public @ColorInt int getProtectionColor() {
            return mProtectionColor;
        }

        /** @return the color for the most prominent text */
        public @ColorInt int getPrimaryTextColor() {
            return mPrimaryTextColor;
        }

        /** @return the color for less prominent text */
        public @ColorInt int getSecondaryTextColor() {
            return mSecondaryTextColor;
        }

        /** @return the theme's accent color for colored UI elements. */
        public @ColorInt int getPrimaryAccentColor() {
            return mPrimaryAccentColor;
        }

        /** @return the theme's secondary accent color for colored UI elements. */
        public @ColorInt int getSecondaryAccentColor() {
            return mSecondaryAccentColor;
        }

        /** @return the theme's tertiary accent color for colored UI elements. */
        public @ColorInt int getTertiaryAccentColor() {
            return mTertiaryAccentColor;
        }

        /** @return the theme's text color to be used on the tertiary accent color. */
        public @ColorInt int getOnAccentTextColor() {
            return mOnAccentTextColor;
        }

        /**
         * @return the contrast-adjusted version of the color provided by the app, or the
         * primary text color when colorized.
         */
        public @ColorInt int getContrastColor() {
            return mContrastColor;
        }

        /** @return the theme's error color, or the primary text color when colorized. */
        public @ColorInt int getErrorColor() {
            return mErrorColor;
        }

        /** @return the alpha component of the current theme's control highlight color. */
        public int getRippleAlpha() {
            return mRippleAlpha;
        }
    }
}
