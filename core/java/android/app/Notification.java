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

import android.annotation.IntDef;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.internal.util.NotificationColorUtil;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
     * containing application. Optional but recommended for apps that post
     * {@link android.app.Notification Notifications}.
     */
    @SdkConstant(SdkConstantType.INTENT_CATEGORY)
    public static final String INTENT_CATEGORY_NOTIFICATION_PREFERENCES
            = "android.intent.category.NOTIFICATION_PREFERENCES";

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
     */
    public long when;

    /**
     * The resource id of a drawable to use as the icon in the status bar.
     * This is required; notifications with an invalid icon resource will not be shown.
     */
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
     * The system may or may not use this field to modify the appearance of the notification. For
     * example, before {@link android.os.Build.VERSION_CODES#HONEYCOMB}, this number was
     * superimposed over the icon in the status bar. Starting with
     * {@link android.os.Build.VERSION_CODES#HONEYCOMB}, the template used by
     * {@link Notification.Builder} has displayed the number in the expanded notification view.
     *
     * If the number is 0 or negative, it is never shown.
     */
    public int number;

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
     * The view that will represent this notification in the expanded status bar.
     */
    public RemoteViews contentView;

    /**
     * A large-format version of {@link #contentView}, giving the Notification an
     * opportunity to show more detail. The system UI may choose to show this
     * instead of the normal content view at its discretion.
     */
    public RemoteViews bigContentView;


    /**
     * A medium-format version of {@link #contentView}, providing the Notification an
     * opportunity to add action buttons to contentView. At its discretion, the system UI may
     * choose to show this as a heads-up notification, which will pop up so the user can see
     * it without leaving their current activity.
     */
    public RemoteViews headsUpContentView;

    /**
     * The bitmap that may escape the bounds of the panel and bar.
     */
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
     */
    public Uri sound;

    /**
     * Use this constant as the value for audioStreamType to request that
     * the default stream type for notifications be used.  Currently the
     * default stream type is {@link AudioManager#STREAM_NOTIFICATION}.
     *
     * @deprecated Use {@link #audioAttributes} instead.
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
     */
    public AudioAttributes audioAttributes = AUDIO_ATTRIBUTES_DEFAULT;

    /**
     * The pattern with which to vibrate.
     *
     * <p>
     * To vibrate the default pattern, see {@link #defaults}.
     * </p>
     *
     * <p>
     * A notification that vibrates is more likely to be presented as a heads-up notification.
     * </p>
     *
     * @see android.os.Vibrator#vibrate(long[],int)
     */
    public long[] vibrate;

    /**
     * The color of the led.  The hardware will do its best approximation.
     *
     * @see #FLAG_SHOW_LIGHTS
     * @see #flags
     */
    public int ledARGB;

    /**
     * The number of milliseconds for the LED to be on while it's flashing.
     * The hardware will do its best approximation.
     *
     * @see #FLAG_SHOW_LIGHTS
     * @see #flags
     */
    public int ledOnMS;

    /**
     * The number of milliseconds for the LED to be off while it's flashing.
     * The hardware will do its best approximation.
     *
     * @see #FLAG_SHOW_LIGHTS
     * @see #flags
     */
    public int ledOffMS;

    /**
     * Specifies which values should be taken from the defaults.
     * <p>
     * To set, OR the desired from {@link #DEFAULT_SOUND},
     * {@link #DEFAULT_VIBRATE}, {@link #DEFAULT_LIGHTS}. For all default
     * values, use {@link #DEFAULT_ALL}.
     * </p>
     */
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
     * you pass are honored exactly.  Use the system defaults (TODO) if possible
     * because they will be set to values that work on any given hardware.
     * <p>
     * The alpha channel must be set for forward compatibility.
     *
     */
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

    public int flags;

    /** @hide */
    @IntDef({PRIORITY_DEFAULT,PRIORITY_LOW,PRIORITY_MIN,PRIORITY_HIGH,PRIORITY_MAX})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Priority {}

    /**
     * Default notification {@link #priority}. If your application does not prioritize its own
     * notifications, use this value for all notifications.
     */
    public static final int PRIORITY_DEFAULT = 0;

    /**
     * Lower {@link #priority}, for items that are less important. The UI may choose to show these
     * items smaller, or at a different position in the list, compared with your app's
     * {@link #PRIORITY_DEFAULT} items.
     */
    public static final int PRIORITY_LOW = -1;

    /**
     * Lowest {@link #priority}; these items might not be shown to the user except under special
     * circumstances, such as detailed notification logs.
     */
    public static final int PRIORITY_MIN = -2;

    /**
     * Higher {@link #priority}, for more important notifications or alerts. The UI may choose to
     * show these items larger, or at a different position in notification lists, compared with
     * your app's {@link #PRIORITY_DEFAULT} items.
     */
    public static final int PRIORITY_HIGH = 1;

    /**
     * Highest {@link #priority}, for your application's most important items that require the
     * user's prompt attention or input.
     */
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
     */
    @Priority
    public int priority;

    /**
     * Accent color (an ARGB integer like the constants in {@link android.graphics.Color})
     * to be applied by the standard Style templates when presenting this notification.
     *
     * The current template design constructs a colorful header image by overlaying the
     * {@link #icon} image (stenciled in white) atop a field of this color. Alpha components are
     * ignored.
     */
    public int color = COLOR_DEFAULT;

    /**
     * Special value of {@link #color} telling the system not to decorate this notification with
     * any special color but instead use default colors when presenting this notification.
     */
    public static final int COLOR_DEFAULT = 0; // AKA Color.TRANSPARENT

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
    public int visibility;

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
     * One of the predefined notification categories (see the <code>CATEGORY_*</code> constants)
     * that best describes this Notification.  May be used by the system for ranking and filtering.
     */
    public String category;

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
     */
    public static final String EXTRA_SMALL_ICON = "android.icon";

    /**
     * {@link #extras} key: this is a bitmap to be used instead of the small icon when showing the
     * notification payload, as
     * supplied to {@link Builder#setLargeIcon(android.graphics.Bitmap)}.
     */
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
     */
    public static final String EXTRA_PEOPLE = "android.people";

    /**
     * {@link #extras} key: used to provide hints about the appropriateness of
     * displaying this notification as a heads-up notification.
     * @hide
     */
    public static final String EXTRA_AS_HEADS_UP = "headsup";

    /**
     * Allow certain system-generated notifications to appear before the device is provisioned.
     * Only available to notifications coming from the android package.
     * @hide
     */
    public static final String EXTRA_ALLOW_DURING_SETUP = "android.allowDuringSetup";

    /**
     * {@link #extras} key: A
     * {@link android.content.ContentUris content URI} pointing to an image that can be displayed
     * in the background when the notification is selected. The URI must point to an image stream
     * suitable for passing into
     * {@link android.graphics.BitmapFactory#decodeStream(java.io.InputStream)
     * BitmapFactory.decodeStream}; all other content types will be ignored. The content provider
     * URI used for this purpose must require no permissions to read the image data.
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
     * {@link #extras} key: the user that built the notification.
     *
     * @hide
     */
    public static final String EXTRA_ORIGINATING_USERID = "android.originatingUserId";

    /**
     * Value for {@link #EXTRA_AS_HEADS_UP} that indicates this notification should not be
     * displayed in the heads up space.
     *
     * <p>
     * If this notification has a {@link #fullScreenIntent}, then it will always launch the
     * full-screen intent when posted.
     * </p>
     * @hide
     */
    public static final int HEADS_UP_NEVER = 0;

    /**
     * Default value for {@link #EXTRA_AS_HEADS_UP} that indicates this notification may be
     * displayed as a heads up.
     * @hide
     */
    public static final int HEADS_UP_ALLOWED = 1;

    /**
     * Value for {@link #EXTRA_AS_HEADS_UP} that indicates this notification is a
     * good candidate for display as a heads up.
     * @hide
     */
    public static final int HEADS_UP_REQUESTED = 2;

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
        private final Bundle mExtras;
        private final RemoteInput[] mRemoteInputs;

        /**
         * Small icon representing the action.
         */
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
            icon = in.readInt();
            title = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            if (in.readInt() == 1) {
                actionIntent = PendingIntent.CREATOR.createFromParcel(in);
            }
            mExtras = in.readBundle();
            mRemoteInputs = in.createTypedArray(RemoteInput.CREATOR);
        }

        /**
         * Use {@link Notification.Builder#addAction(int, CharSequence, PendingIntent)}.
         */
        public Action(int icon, CharSequence title, PendingIntent intent) {
            this(icon, title, intent, new Bundle(), null);
        }

        private Action(int icon, CharSequence title, PendingIntent intent, Bundle extras,
                RemoteInput[] remoteInputs) {
            this.icon = icon;
            this.title = title;
            this.actionIntent = intent;
            this.mExtras = extras != null ? extras : new Bundle();
            this.mRemoteInputs = remoteInputs;
        }

        /**
         * Get additional metadata carried around with this Action.
         */
        public Bundle getExtras() {
            return mExtras;
        }

        /**
         * Get the list of inputs to be collected from the user when this action is sent.
         * May return null if no remote inputs were added.
         */
        public RemoteInput[] getRemoteInputs() {
            return mRemoteInputs;
        }

        /**
         * Builder class for {@link Action} objects.
         */
        public static final class Builder {
            private final int mIcon;
            private final CharSequence mTitle;
            private final PendingIntent mIntent;
            private final Bundle mExtras;
            private ArrayList<RemoteInput> mRemoteInputs;

            /**
             * Construct a new builder for {@link Action} object.
             * @param icon icon to show for this action
             * @param title the title of the action
             * @param intent the {@link PendingIntent} to fire when users trigger this action
             */
            public Builder(int icon, CharSequence title, PendingIntent intent) {
                this(icon, title, intent, new Bundle(), null);
            }

            /**
             * Construct a new builder for {@link Action} object using the fields from an
             * {@link Action}.
             * @param action the action to read fields from.
             */
            public Builder(Action action) {
                this(action.icon, action.title, action.actionIntent, new Bundle(action.mExtras),
                        action.getRemoteInputs());
            }

            private Builder(int icon, CharSequence title, PendingIntent intent, Bundle extras,
                    RemoteInput[] remoteInputs) {
                mIcon = icon;
                mTitle = title;
                mIntent = intent;
                mExtras = extras;
                if (remoteInputs != null) {
                    mRemoteInputs = new ArrayList<RemoteInput>(remoteInputs.length);
                    Collections.addAll(mRemoteInputs, remoteInputs);
                }
            }

            /**
             * Merge additional metadata into this builder.
             *
             * <p>Values within the Bundle will replace existing extras values in this Builder.
             *
             * @see Notification.Action#extras
             */
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
            public Builder addRemoteInput(RemoteInput remoteInput) {
                if (mRemoteInputs == null) {
                    mRemoteInputs = new ArrayList<RemoteInput>();
                }
                mRemoteInputs.add(remoteInput);
                return this;
            }

            /**
             * Apply an extender to this action builder. Extenders may be used to add
             * metadata or change options on this builder.
             */
            public Builder extend(Extender extender) {
                extender.extend(this);
                return this;
            }

            /**
             * Combine all of the options that have been set and return a new {@link Action}
             * object.
             * @return the built action
             */
            public Action build() {
                RemoteInput[] remoteInputs = mRemoteInputs != null
                        ? mRemoteInputs.toArray(new RemoteInput[mRemoteInputs.size()]) : null;
                return new Action(mIcon, mTitle, mIntent, mExtras, remoteInputs);
            }
        }

        @Override
        public Action clone() {
            return new Action(
                    icon,
                    title,
                    actionIntent, // safe to alias
                    new Bundle(mExtras),
                    getRemoteInputs());
        }
        @Override
        public int describeContents() {
            return 0;
        }
        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(icon);
            TextUtils.writeToParcel(title, out, flags);
            if (actionIntent != null) {
                out.writeInt(1);
                actionIntent.writeToParcel(out, flags);
            } else {
                out.writeInt(0);
            }
            out.writeBundle(mExtras);
            out.writeTypedArray(mRemoteInputs, flags);
        }
        public static final Parcelable.Creator<Action> CREATOR =
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
            public CharSequence getCancelLabel() {
                return mCancelLabel;
            }
        }
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
        this.priority = PRIORITY_DEFAULT;
    }

    /**
     * @hide
     */
    public Notification(Context context, int icon, CharSequence tickerText, long when,
            CharSequence contentTitle, CharSequence contentText, Intent contentIntent)
    {
        this.when = when;
        this.icon = icon;
        this.tickerText = tickerText;
        setLatestEventInfo(context, contentTitle, contentText,
                PendingIntent.getActivity(context, 0, contentIntent, 0));
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
    }

    /**
     * Unflatten the notification from a parcel.
     */
    public Notification(Parcel parcel)
    {
        int version = parcel.readInt();

        when = parcel.readLong();
        icon = parcel.readInt();
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
            largeIcon = Bitmap.CREATOR.createFromParcel(parcel);
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

        extras = parcel.readBundle(); // may be null

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
        that.when = this.when;
        that.icon = this.icon;
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
        if (heavy && this.largeIcon != null) {
            that.largeIcon = Bitmap.createBitmap(this.largeIcon);
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

        if (this.actions != null) {
            that.actions = new Action[this.actions.length];
            for(int i=0; i<this.actions.length; i++) {
                that.actions[i] = this.actions[i].clone();
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

        if (!heavy) {
            that.lightenPayload(); // will clean out extras
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
        largeIcon = null;
        if (extras != null) {
            extras.remove(Notification.EXTRA_LARGE_ICON);
            extras.remove(Notification.EXTRA_LARGE_ICON_BIG);
            extras.remove(Notification.EXTRA_PICTURE);
            extras.remove(Notification.EXTRA_BIG_TEXT);
            // Prevent light notifications from being rebuilt.
            extras.remove(Builder.EXTRA_NEEDS_REBUILD);
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

        return cs;
    }

    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this notification from a parcel.
     */
    public void writeToParcel(Parcel parcel, int flags)
    {
        parcel.writeInt(1);

        parcel.writeLong(when);
        parcel.writeInt(icon);
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
        if (largeIcon != null) {
            parcel.writeInt(1);
            largeIcon.writeToParcel(parcel, 0);
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
    }

    /**
     * Parcelable.Creator that instantiates Notification objects
     */
    public static final Parcelable.Creator<Notification> CREATOR
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
     */
    @Deprecated
    public void setLatestEventInfo(Context context,
            CharSequence contentTitle, CharSequence contentText, PendingIntent contentIntent) {
        Notification.Builder builder = new Notification.Builder(context);

        // First, ensure that key pieces of information that may have been set directly
        // are preserved
        builder.setWhen(this.when);
        builder.setSmallIcon(this.icon);
        builder.setPriority(this.priority);
        builder.setTicker(this.tickerText);
        builder.setNumber(this.number);
        builder.setColor(this.color);
        builder.mFlags = this.flags;
        builder.setSound(this.sound, this.audioStreamType);
        builder.setDefaults(this.defaults);
        builder.setVibrate(this.vibrate);
        builder.setDeleteIntent(this.deleteIntent);

        // now apply the latestEventInfo fields
        if (contentTitle != null) {
            builder.setContentTitle(contentTitle);
        }
        if (contentText != null) {
            builder.setContentText(contentText);
        }
        builder.setContentIntent(contentIntent);
        builder.buildInto(this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Notification(pri=");
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
     * @hide
     */
    public boolean isValid() {
        // Would like to check for icon!=0 here, too, but NotificationManagerService accepts that
        // for legacy reasons.
        return contentView != null || extras.getBoolean(Builder.EXTRA_REBUILD_CONTENT_VIEW);
    }

    /**
     * @hide
     */
    public boolean isGroupSummary() {
        return mGroupKey != null && (flags & FLAG_GROUP_SUMMARY) != 0;
    }

    /**
     * @hide
     */
    public boolean isGroupChild() {
        return mGroupKey != null && (flags & FLAG_GROUP_SUMMARY) == 0;
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
        private static final int MAX_ACTION_BUTTONS = 3;
        private static final float LARGE_TEXT_SCALE = 1.3f;

        /**
         * @hide
         */
        public static final String EXTRA_NEEDS_REBUILD = "android.rebuild";

        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_LARGE_ICON = "android.rebuild.largeIcon";
        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_CONTENT_VIEW = "android.rebuild.contentView";
        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT =
                "android.rebuild.contentViewActionCount";
        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_BIG_CONTENT_VIEW
                = "android.rebuild.bigView";
        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT
                = "android.rebuild.bigViewActionCount";
        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW
                = "android.rebuild.hudView";
        /**
         * @hide
         */
        public static final String EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT
                = "android.rebuild.hudViewActionCount";

        /**
         * The ApplicationInfo of the package that created the notification, used to create
         * a context to rebuild the notification via a Builder.
         * @hide
         */
        private static final String EXTRA_REBUILD_CONTEXT_APPLICATION_INFO =
                "android.rebuild.applicationInfo";

        // Whether to enable stripping (at post time) & rebuilding (at listener receive time) of
        // memory intensive resources.
        private static final boolean STRIP_AND_REBUILD = true;

        private Context mContext;

        private long mWhen;
        private int mSmallIcon;
        private int mSmallIconLevel;
        private int mNumber;
        private CharSequence mContentTitle;
        private CharSequence mContentText;
        private CharSequence mContentInfo;
        private CharSequence mSubText;
        private PendingIntent mContentIntent;
        private RemoteViews mContentView;
        private PendingIntent mDeleteIntent;
        private PendingIntent mFullScreenIntent;
        private CharSequence mTickerText;
        private RemoteViews mTickerView;
        private Bitmap mLargeIcon;
        private Uri mSound;
        private int mAudioStreamType;
        private AudioAttributes mAudioAttributes;
        private long[] mVibrate;
        private int mLedArgb;
        private int mLedOnMs;
        private int mLedOffMs;
        private int mDefaults;
        private int mFlags;
        private int mProgressMax;
        private int mProgress;
        private boolean mProgressIndeterminate;
        private String mCategory;
        private String mGroupKey;
        private String mSortKey;
        private Bundle mExtras;
        private int mPriority;
        private ArrayList<Action> mActions = new ArrayList<Action>(MAX_ACTION_BUTTONS);
        private boolean mUseChronometer;
        private Style mStyle;
        private boolean mShowWhen = true;
        private int mVisibility = VISIBILITY_PRIVATE;
        private Notification mPublicVersion = null;
        private final NotificationColorUtil mColorUtil;
        private ArrayList<String> mPeople;
        private int mColor = COLOR_DEFAULT;

        /**
         * The user that built the notification originally.
         */
        private int mOriginatingUserId;

        /**
         * Contains extras related to rebuilding during the build phase.
         */
        private Bundle mRebuildBundle = new Bundle();
        /**
         * Contains the notification to rebuild when this Builder is in "rebuild" mode.
         * Null otherwise.
         */
        private Notification mRebuildNotification = null;

        /**
         * Whether the build notification has three lines. This is used to make the top padding for
         * both the contracted and expanded layout consistent.
         *
         * <p>
         * This field is only valid during the build phase.
         */
        private boolean mHasThreeLines;

        /**
         * Constructs a new Builder with the defaults:
         *

         * <table>
         * <tr><th align=right>priority</th>
         *     <td>{@link #PRIORITY_DEFAULT}</td></tr>
         * <tr><th align=right>when</th>
         *     <td>now ({@link System#currentTimeMillis()})</td></tr>
         * <tr><th align=right>audio stream</th>
         *     <td>{@link #STREAM_DEFAULT}</td></tr>
         * </table>
         *

         * @param context
         *            A {@link Context} that will be used by the Builder to construct the
         *            RemoteViews. The Context will not be held past the lifetime of this Builder
         *            object.
         */
        public Builder(Context context) {
            /*
             * Important compatibility note!
             * Some apps out in the wild create a Notification.Builder in their Activity subclass
             * constructor for later use. At this point Activities - themselves subclasses of
             * ContextWrapper - do not have their inner Context populated yet. This means that
             * any calls to Context methods from within this constructor can cause NPEs in existing
             * apps. Any data populated from mContext should therefore be populated lazily to
             * preserve compatibility.
             */
            mContext = context;

            // Set defaults to match the defaults of a Notification
            mWhen = System.currentTimeMillis();
            mAudioStreamType = STREAM_DEFAULT;
            mAudioAttributes = AUDIO_ATTRIBUTES_DEFAULT;
            mPriority = PRIORITY_DEFAULT;
            mPeople = new ArrayList<String>();

            mColorUtil = context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.LOLLIPOP ?
                    NotificationColorUtil.getInstance(mContext) : null;
        }

        /**
         * Creates a Builder for rebuilding the given Notification.
         * <p>
         * Call {@link #rebuild()} to retrieve the rebuilt version of 'n'.
         */
        private Builder(Context context, Notification n) {
            this(context);
            mRebuildNotification = n;
            restoreFromNotification(n);

            Style style = null;
            Bundle extras = n.extras;
            String templateClass = extras.getString(EXTRA_TEMPLATE);
            if (!TextUtils.isEmpty(templateClass)) {
                Class<? extends Style> styleClass = getNotificationStyleClass(templateClass);
                if (styleClass == null) {
                    Log.d(TAG, "Unknown style class: " + styleClass);
                    return;
                }

                try {
                    Constructor<? extends Style> constructor = styleClass.getConstructor();
                    style = constructor.newInstance();
                    style.restoreFromExtras(extras);
                } catch (Throwable t) {
                    Log.e(TAG, "Could not create Style", t);
                    return;
                }
            }
            if (style != null) {
                setStyle(style);
            }
        }

        /**
         * Add a timestamp pertaining to the notification (usually the time the event occurred).
         * It will be shown in the notification content view by default; use
         * {@link #setShowWhen(boolean) setShowWhen} to control this.
         *
         * @see Notification#when
         */
        public Builder setWhen(long when) {
            mWhen = when;
            return this;
        }

        /**
         * Control whether the timestamp set with {@link #setWhen(long) setWhen} is shown
         * in the content view.
         */
        public Builder setShowWhen(boolean show) {
            mShowWhen = show;
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
         * @see android.widget.Chronometer
         * @see Notification#when
         */
        public Builder setUsesChronometer(boolean b) {
            mUseChronometer = b;
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
        public Builder setSmallIcon(int icon) {
            mSmallIcon = icon;
            return this;
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
        public Builder setSmallIcon(int icon, int level) {
            mSmallIcon = icon;
            mSmallIconLevel = level;
            return this;
        }

        /**
         * Set the first line of text in the platform notification template.
         */
        public Builder setContentTitle(CharSequence title) {
            mContentTitle = safeCharSequence(title);
            return this;
        }

        /**
         * Set the second line of text in the platform notification template.
         */
        public Builder setContentText(CharSequence text) {
            mContentText = safeCharSequence(text);
            return this;
        }

        /**
         * Set the third line of text in the platform notification template.
         * Don't use if you're also using {@link #setProgress(int, int, boolean)}; they occupy the
         * same location in the standard template.
         */
        public Builder setSubText(CharSequence text) {
            mSubText = safeCharSequence(text);
            return this;
        }

        /**
         * Set the large number at the right-hand side of the notification.  This is
         * equivalent to setContentInfo, although it might show the number in a different
         * font size for readability.
         */
        public Builder setNumber(int number) {
            mNumber = number;
            return this;
        }

        /**
         * A small piece of additional information pertaining to this notification.
         *
         * The platform template will draw this on the last line of the notification, at the far
         * right (to the right of a smallIcon if it has been placed there).
         */
        public Builder setContentInfo(CharSequence info) {
            mContentInfo = safeCharSequence(info);
            return this;
        }

        /**
         * Set the progress this notification represents.
         *
         * The platform template will represent this using a {@link ProgressBar}.
         */
        public Builder setProgress(int max, int progress, boolean indeterminate) {
            mProgressMax = max;
            mProgress = progress;
            mProgressIndeterminate = indeterminate;
            return this;
        }

        /**
         * Supply a custom RemoteViews to use instead of the platform template.
         *
         * @see Notification#contentView
         */
        public Builder setContent(RemoteViews views) {
            mContentView = views;
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
        public Builder setContentIntent(PendingIntent intent) {
            mContentIntent = intent;
            return this;
        }

        /**
         * Supply a {@link PendingIntent} to send when the notification is cleared explicitly by the user.
         *
         * @see Notification#deleteIntent
         */
        public Builder setDeleteIntent(PendingIntent intent) {
            mDeleteIntent = intent;
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
         *
         * @param intent The pending intent to launch.
         * @param highPriority Passing true will cause this notification to be sent
         *          even if other notifications are suppressed.
         *
         * @see Notification#fullScreenIntent
         */
        public Builder setFullScreenIntent(PendingIntent intent, boolean highPriority) {
            mFullScreenIntent = intent;
            setFlag(FLAG_HIGH_PRIORITY, highPriority);
            return this;
        }

        /**
         * Set the "ticker" text which is sent to accessibility services.
         *
         * @see Notification#tickerText
         */
        public Builder setTicker(CharSequence tickerText) {
            mTickerText = safeCharSequence(tickerText);
            return this;
        }

        /**
         * Obsolete version of {@link #setTicker(CharSequence)}.
         *
         */
        @Deprecated
        public Builder setTicker(CharSequence tickerText, RemoteViews views) {
            mTickerText = safeCharSequence(tickerText);
            mTickerView = views; // we'll save it for you anyway
            return this;
        }

        /**
         * Add a large icon to the notification (and the ticker on some devices).
         *
         * In the platform template, this image will be shown on the left of the notification view
         * in place of the {@link #setSmallIcon(int) small icon} (which will move to the right side).
         *
         * @see Notification#largeIcon
         */
        public Builder setLargeIcon(Bitmap icon) {
            mLargeIcon = icon;
            return this;
        }

        /**
         * Set the sound to play.
         *
         * It will be played using the {@link #AUDIO_ATTRIBUTES_DEFAULT default audio attributes}
         * for notifications.
         *
         * <p>
         * A notification that is noisy is more likely to be presented as a heads-up notification.
         * </p>
         *
         * @see Notification#sound
         */
        public Builder setSound(Uri sound) {
            mSound = sound;
            mAudioAttributes = AUDIO_ATTRIBUTES_DEFAULT;
            return this;
        }

        /**
         * Set the sound to play, along with a specific stream on which to play it.
         *
         * See {@link android.media.AudioManager} for the <code>STREAM_</code> constants.
         *
         * <p>
         * A notification that is noisy is more likely to be presented as a heads-up notification.
         * </p>
         * @deprecated use {@link #setSound(Uri, AudioAttributes)} instead.
         * @see Notification#sound
         */
        @Deprecated
        public Builder setSound(Uri sound, int streamType) {
            mSound = sound;
            mAudioStreamType = streamType;
            return this;
        }

        /**
         * Set the sound to play, along with specific {@link AudioAttributes audio attributes} to
         * use during playback.
         *
         * <p>
         * A notification that is noisy is more likely to be presented as a heads-up notification.
         * </p>
         *
         * @see Notification#sound
         */
        public Builder setSound(Uri sound, AudioAttributes audioAttributes) {
            mSound = sound;
            mAudioAttributes = audioAttributes;
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
         * @see Notification#vibrate
         */
        public Builder setVibrate(long[] pattern) {
            mVibrate = pattern;
            return this;
        }

        /**
         * Set the desired color for the indicator LED on the device, as well as the
         * blink duty cycle (specified in milliseconds).
         *

         * Not all devices will honor all (or even any) of these values.
         *

         * @see Notification#ledARGB
         * @see Notification#ledOnMS
         * @see Notification#ledOffMS
         */
        public Builder setLights(int argb, int onMs, int offMs) {
            mLedArgb = argb;
            mLedOnMs = onMs;
            mLedOffMs = offMs;
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
         * @see Service#setForeground(boolean)
         */
        public Builder setOngoing(boolean ongoing) {
            setFlag(FLAG_ONGOING_EVENT, ongoing);
            return this;
        }

        /**
         * Set this flag if you would only like the sound, vibrate
         * and ticker to be played if the notification is not already showing.
         *
         * @see Notification#FLAG_ONLY_ALERT_ONCE
         */
        public Builder setOnlyAlertOnce(boolean onlyAlertOnce) {
            setFlag(FLAG_ONLY_ALERT_ONCE, onlyAlertOnce);
            return this;
        }

        /**
         * Make this notification automatically dismissed when the user touches it. The
         * PendingIntent set with {@link #setDeleteIntent} will be sent when this happens.
         *
         * @see Notification#FLAG_AUTO_CANCEL
         */
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
         */
        public Builder setDefaults(int defaults) {
            mDefaults = defaults;
            return this;
        }

        /**
         * Set the priority of this notification.
         *
         * @see Notification#priority
         */
        public Builder setPriority(@Priority int pri) {
            mPriority = pri;
            return this;
        }

        /**
         * Set the notification category.
         *
         * @see Notification#category
         */
        public Builder setCategory(String category) {
            mCategory = category;
            return this;
        }

        /**
         * Add a person that is relevant to this notification.
         *
         * <P>
         * Depending on user preferences, this annotation may allow the notification to pass
         * through interruption filters, and to appear more prominently in the user interface.
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
         * </P>
         *
         * @param uri A URI for the person.
         * @see Notification#EXTRA_PEOPLE
         */
        public Builder addPerson(String uri) {
            mPeople.add(uri);
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
        public Builder setGroup(String groupKey) {
            mGroupKey = groupKey;
            return this;
        }

        /**
         * Set this notification to be the group summary for a group of notifications.
         * Grouped notifications may display in a cluster or stack on devices which
         * support such rendering. Requires a group key also be set using {@link #setGroup}.
         * @param isGroupSummary Whether this notification should be a group summary.
         * @return this object for method chaining
         */
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
        public Builder setSortKey(String sortKey) {
            mSortKey = sortKey;
            return this;
        }

        /**
         * Merge additional metadata into this notification.
         *
         * <p>Values within the Bundle will replace existing extras values in this Builder.
         *
         * @see Notification#extras
         */
        public Builder addExtras(Bundle extras) {
            if (extras != null) {
                if (mExtras == null) {
                    mExtras = new Bundle(extras);
                } else {
                    mExtras.putAll(extras);
                }
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
        public Builder setExtras(Bundle extras) {
            mExtras = extras;
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
            if (mExtras == null) {
                mExtras = new Bundle();
            }
            return mExtras;
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
         */
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
        public Builder addAction(Action action) {
            mActions.add(action);
            return this;
        }

        /**
         * Add a rich notification style to be applied at build time.
         *
         * @param style Object responsible for modifying the notification style.
         */
        public Builder setStyle(Style style) {
            if (mStyle != style) {
                mStyle = style;
                if (mStyle != null) {
                    mStyle.setBuilder(this);
                }
            }
            return this;
        }

        /**
         * Specify the value of {@link #visibility}.
         *
         * @param visibility One of {@link #VISIBILITY_PRIVATE} (the default),
         * {@link #VISIBILITY_SECRET}, or {@link #VISIBILITY_PUBLIC}.
         *
         * @return The same Builder.
         */
        public Builder setVisibility(int visibility) {
            mVisibility = visibility;
            return this;
        }

        /**
         * Supply a replacement Notification whose contents should be shown in insecure contexts
         * (i.e. atop the secure lockscreen). See {@link #visibility} and {@link #VISIBILITY_PUBLIC}.
         * @param n A replacement notification, presumably with some or all info redacted.
         * @return The same Builder.
         */
        public Builder setPublicVersion(Notification n) {
            mPublicVersion = n;
            return this;
        }

        /**
         * Apply an extender to this notification builder. Extenders may be used to add
         * metadata or change options on this builder.
         */
        public Builder extend(Extender extender) {
            extender.extend(this);
            return this;
        }

        private void setFlag(int mask, boolean value) {
            if (value) {
                mFlags |= mask;
            } else {
                mFlags &= ~mask;
            }
        }

        /**
         * Sets {@link Notification#color}.
         *
         * @param argb The accent color to use
         *
         * @return The same Builder.
         */
        public Builder setColor(int argb) {
            mColor = argb;
            return this;
        }

        private Drawable getProfileBadgeDrawable() {
            // Note: This assumes that the current user can read the profile badge of the
            // originating user.
            return mContext.getPackageManager().getUserBadgeForDensity(
                    new UserHandle(mOriginatingUserId), 0);
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

        private boolean addProfileBadge(RemoteViews contentView, int resId) {
            Bitmap profileBadge = getProfileBadge();

            contentView.setViewVisibility(R.id.profile_badge_large_template, View.GONE);
            contentView.setViewVisibility(R.id.profile_badge_line2, View.GONE);
            contentView.setViewVisibility(R.id.profile_badge_line3, View.GONE);

            if (profileBadge != null) {
                contentView.setImageViewBitmap(resId, profileBadge);
                contentView.setViewVisibility(resId, View.VISIBLE);

                // Make sure Line 3 is visible. As badge will be here if there
                // is no text to display.
                if (resId == R.id.profile_badge_line3) {
                    contentView.setViewVisibility(R.id.line3, View.VISIBLE);
                }
                return true;
            }
            return false;
        }

        private void shrinkLine3Text(RemoteViews contentView) {
            float subTextSize = mContext.getResources().getDimensionPixelSize(
                    R.dimen.notification_subtext_size);
            contentView.setTextViewTextSize(R.id.text, TypedValue.COMPLEX_UNIT_PX, subTextSize);
        }

        private void unshrinkLine3Text(RemoteViews contentView) {
            float regularTextSize = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.notification_text_size);
            contentView.setTextViewTextSize(R.id.text, TypedValue.COMPLEX_UNIT_PX, regularTextSize);
        }

        private void resetStandardTemplate(RemoteViews contentView) {
            removeLargeIconBackground(contentView);
            contentView.setViewPadding(R.id.icon, 0, 0, 0, 0);
            contentView.setImageViewResource(R.id.icon, 0);
            contentView.setInt(R.id.icon, "setBackgroundResource", 0);
            contentView.setViewVisibility(R.id.right_icon, View.GONE);
            contentView.setInt(R.id.right_icon, "setBackgroundResource", 0);
            contentView.setImageViewResource(R.id.right_icon, 0);
            contentView.setImageViewResource(R.id.icon, 0);
            contentView.setTextViewText(R.id.title, null);
            contentView.setTextViewText(R.id.text, null);
            unshrinkLine3Text(contentView);
            contentView.setTextViewText(R.id.text2, null);
            contentView.setViewVisibility(R.id.text2, View.GONE);
            contentView.setViewVisibility(R.id.info, View.GONE);
            contentView.setViewVisibility(R.id.time, View.GONE);
            contentView.setViewVisibility(R.id.line3, View.GONE);
            contentView.setViewVisibility(R.id.overflow_divider, View.GONE);
            contentView.setViewVisibility(R.id.progress, View.GONE);
            contentView.setViewVisibility(R.id.chronometer, View.GONE);
            contentView.setViewVisibility(R.id.time, View.GONE);
        }

        private RemoteViews applyStandardTemplate(int resId) {
            return applyStandardTemplate(resId, true /* hasProgress */);
        }

        /**
         * @param hasProgress whether the progress bar should be shown and set
         */
        private RemoteViews applyStandardTemplate(int resId, boolean hasProgress) {
            RemoteViews contentView = new BuilderRemoteViews(mContext.getApplicationInfo(), resId);

            resetStandardTemplate(contentView);

            boolean showLine3 = false;
            boolean showLine2 = false;
            boolean contentTextInLine2 = false;

            if (mLargeIcon != null) {
                contentView.setImageViewBitmap(R.id.icon, mLargeIcon);
                processLargeLegacyIcon(mLargeIcon, contentView);
                contentView.setImageViewResource(R.id.right_icon, mSmallIcon);
                contentView.setViewVisibility(R.id.right_icon, View.VISIBLE);
                processSmallRightIcon(mSmallIcon, contentView);
            } else { // small icon at left
                contentView.setImageViewResource(R.id.icon, mSmallIcon);
                contentView.setViewVisibility(R.id.icon, View.VISIBLE);
                processSmallIconAsLarge(mSmallIcon, contentView);
            }
            if (mContentTitle != null) {
                contentView.setTextViewText(R.id.title, processLegacyText(mContentTitle));
            }
            if (mContentText != null) {
                contentView.setTextViewText(R.id.text, processLegacyText(mContentText));
                showLine3 = true;
            }
            if (mContentInfo != null) {
                contentView.setTextViewText(R.id.info, processLegacyText(mContentInfo));
                contentView.setViewVisibility(R.id.info, View.VISIBLE);
                showLine3 = true;
            } else if (mNumber > 0) {
                final int tooBig = mContext.getResources().getInteger(
                        R.integer.status_bar_notification_info_maxnum);
                if (mNumber > tooBig) {
                    contentView.setTextViewText(R.id.info, processLegacyText(
                            mContext.getResources().getString(
                                    R.string.status_bar_notification_info_overflow)));
                } else {
                    NumberFormat f = NumberFormat.getIntegerInstance();
                    contentView.setTextViewText(R.id.info, processLegacyText(f.format(mNumber)));
                }
                contentView.setViewVisibility(R.id.info, View.VISIBLE);
                showLine3 = true;
            } else {
                contentView.setViewVisibility(R.id.info, View.GONE);
            }

            // Need to show three lines?
            if (mSubText != null) {
                contentView.setTextViewText(R.id.text, processLegacyText(mSubText));
                if (mContentText != null) {
                    contentView.setTextViewText(R.id.text2, processLegacyText(mContentText));
                    contentView.setViewVisibility(R.id.text2, View.VISIBLE);
                    showLine2 = true;
                    contentTextInLine2 = true;
                } else {
                    contentView.setViewVisibility(R.id.text2, View.GONE);
                }
            } else {
                contentView.setViewVisibility(R.id.text2, View.GONE);
                if (hasProgress && (mProgressMax != 0 || mProgressIndeterminate)) {
                    contentView.setViewVisibility(R.id.progress, View.VISIBLE);
                    contentView.setProgressBar(
                            R.id.progress, mProgressMax, mProgress, mProgressIndeterminate);
                    contentView.setProgressBackgroundTintList(
                            R.id.progress, ColorStateList.valueOf(mContext.getResources().getColor(
                                    R.color.notification_progress_background_color)));
                    if (mColor != COLOR_DEFAULT) {
                        ColorStateList colorStateList = ColorStateList.valueOf(mColor);
                        contentView.setProgressTintList(R.id.progress, colorStateList);
                        contentView.setProgressIndeterminateTintList(R.id.progress, colorStateList);
                    }
                    showLine2 = true;
                } else {
                    contentView.setViewVisibility(R.id.progress, View.GONE);
                }
            }
            if (showLine2) {

                // need to shrink all the type to make sure everything fits
                shrinkLine3Text(contentView);
            }

            if (showsTimeOrChronometer()) {
                if (mUseChronometer) {
                    contentView.setViewVisibility(R.id.chronometer, View.VISIBLE);
                    contentView.setLong(R.id.chronometer, "setBase",
                            mWhen + (SystemClock.elapsedRealtime() - System.currentTimeMillis()));
                    contentView.setBoolean(R.id.chronometer, "setStarted", true);
                } else {
                    contentView.setViewVisibility(R.id.time, View.VISIBLE);
                    contentView.setLong(R.id.time, "setTime", mWhen);
                }
            }

            // Adjust padding depending on line count and font size.
            contentView.setViewPadding(R.id.line1, 0, calculateTopPadding(mContext,
                    mHasThreeLines, mContext.getResources().getConfiguration().fontScale),
                    0, 0);

            // We want to add badge to first line of text.
            boolean addedBadge = addProfileBadge(contentView,
                    contentTextInLine2 ? R.id.profile_badge_line2 : R.id.profile_badge_line3);
            // If we added the badge to line 3 then we should show line 3.
            if (addedBadge && !contentTextInLine2) {
                showLine3 = true;
            }

            // Note getStandardView may hide line 3 again.
            contentView.setViewVisibility(R.id.line3, showLine3 ? View.VISIBLE : View.GONE);
            contentView.setViewVisibility(R.id.overflow_divider, showLine3 ? View.VISIBLE : View.GONE);
            return contentView;
        }

        /**
         * @return true if the built notification will show the time or the chronometer; false
         *         otherwise
         */
        private boolean showsTimeOrChronometer() {
            return mWhen != 0 && mShowWhen;
        }

        /**
         * Logic to find out whether the notification is going to have three lines in the contracted
         * layout. This is used to adjust the top padding.
         *
         * @return true if the notification is going to have three lines; false if the notification
         *         is going to have one or two lines
         */
        private boolean hasThreeLines() {
            boolean contentTextInLine2 = mSubText != null && mContentText != null;
            boolean hasProgress = mStyle == null || mStyle.hasProgress();
            // If we have content text in line 2, badge goes into line 2, or line 3 otherwise
            boolean badgeInLine3 = getProfileBadgeDrawable() != null && !contentTextInLine2;
            boolean hasLine3 = mContentText != null || mContentInfo != null || mNumber > 0
                    || badgeInLine3;
            boolean hasLine2 = (mSubText != null && mContentText != null) ||
                    (hasProgress && mSubText == null
                            && (mProgressMax != 0 || mProgressIndeterminate));
            return hasLine2 && hasLine3;
        }

        /**
         * @hide
         */
        public static int calculateTopPadding(Context ctx, boolean hasThreeLines,
                float fontScale) {
            int padding = ctx.getResources().getDimensionPixelSize(hasThreeLines
                    ? R.dimen.notification_top_pad_narrow
                    : R.dimen.notification_top_pad);
            int largePadding = ctx.getResources().getDimensionPixelSize(hasThreeLines
                    ? R.dimen.notification_top_pad_large_text_narrow
                    : R.dimen.notification_top_pad_large_text);
            float largeFactor = (MathUtils.constrain(fontScale, 1.0f, LARGE_TEXT_SCALE) - 1f)
                    / (LARGE_TEXT_SCALE - 1f);

            // Linearly interpolate the padding between large and normal with the font scale ranging
            // from 1f to LARGE_TEXT_SCALE
            return Math.round((1 - largeFactor) * padding + largeFactor * largePadding);
        }

        private void resetStandardTemplateWithActions(RemoteViews big) {
            big.setViewVisibility(R.id.actions, View.GONE);
            big.setViewVisibility(R.id.action_divider, View.GONE);
            big.removeAllViews(R.id.actions);
        }

        private RemoteViews applyStandardTemplateWithActions(int layoutId) {
            RemoteViews big = applyStandardTemplate(layoutId);

            resetStandardTemplateWithActions(big);

            int N = mActions.size();
            if (N > 0) {
                big.setViewVisibility(R.id.actions, View.VISIBLE);
                big.setViewVisibility(R.id.action_divider, View.VISIBLE);
                if (N>MAX_ACTION_BUTTONS) N=MAX_ACTION_BUTTONS;
                for (int i=0; i<N; i++) {
                    final RemoteViews button = generateActionButton(mActions.get(i));
                    big.addView(R.id.actions, button);
                }
            }
            return big;
        }

        private RemoteViews makeContentView() {
            if (mContentView != null) {
                return mContentView;
            } else {
                return applyStandardTemplate(getBaseLayoutResource());
            }
        }

        private RemoteViews makeTickerView() {
            if (mTickerView != null) {
                return mTickerView;
            }
            return null; // tickers are not created by default anymore
        }

        private RemoteViews makeBigContentView() {
            if (mActions.size() == 0) return null;

            return applyStandardTemplateWithActions(getBigBaseLayoutResource());
        }

        private RemoteViews makeHeadsUpContentView() {
            if (mActions.size() == 0) return null;

            return applyStandardTemplateWithActions(getBigBaseLayoutResource());
        }


        private RemoteViews generateActionButton(Action action) {
            final boolean tombstone = (action.actionIntent == null);
            RemoteViews button = new RemoteViews(mContext.getPackageName(),
                    tombstone ? getActionTombstoneLayoutResource()
                              : getActionLayoutResource());
            button.setTextViewCompoundDrawablesRelative(R.id.action0, action.icon, 0, 0, 0);
            button.setTextViewText(R.id.action0, processLegacyText(action.title));
            if (!tombstone) {
                button.setOnClickPendingIntent(R.id.action0, action.actionIntent);
            }
            button.setContentDescription(R.id.action0, action.title);
            processLegacyAction(action, button);
            return button;
        }

        /**
         * @return Whether we are currently building a notification from a legacy (an app that
         *         doesn't create material notifications by itself) app.
         */
        private boolean isLegacy() {
            return mColorUtil != null;
        }

        private void processLegacyAction(Action action, RemoteViews button) {
            if (!isLegacy() || mColorUtil.isGrayscaleIcon(mContext, action.icon)) {
                button.setTextViewCompoundDrawablesRelativeColorFilter(R.id.action0, 0,
                        mContext.getResources().getColor(R.color.notification_action_color_filter),
                        PorterDuff.Mode.MULTIPLY);
            }
        }

        private CharSequence processLegacyText(CharSequence charSequence) {
            if (isLegacy()) {
                return mColorUtil.invertCharSequenceColors(charSequence);
            } else {
                return charSequence;
            }
        }

        /**
         * Apply any necessary background to smallIcons being used in the largeIcon spot.
         */
        private void processSmallIconAsLarge(int largeIconId, RemoteViews contentView) {
            if (!isLegacy()) {
                contentView.setDrawableParameters(R.id.icon, false, -1,
                        0xFFFFFFFF,
                        PorterDuff.Mode.SRC_ATOP, -1);
            }
            if (!isLegacy() || mColorUtil.isGrayscaleIcon(mContext, largeIconId)) {
                applyLargeIconBackground(contentView);
            }
        }

        /**
         * Apply any necessary background to a largeIcon if it's a fake smallIcon (that is,
         * if it's grayscale).
         */
        // TODO: also check bounds, transparency, that sort of thing.
        private void processLargeLegacyIcon(Bitmap largeIcon, RemoteViews contentView) {
            if (isLegacy() && mColorUtil.isGrayscaleIcon(largeIcon)) {
                applyLargeIconBackground(contentView);
            } else {
                removeLargeIconBackground(contentView);
            }
        }

        /**
         * Add a colored circle behind the largeIcon slot.
         */
        private void applyLargeIconBackground(RemoteViews contentView) {
            contentView.setInt(R.id.icon, "setBackgroundResource",
                    R.drawable.notification_icon_legacy_bg);

            contentView.setDrawableParameters(
                    R.id.icon,
                    true,
                    -1,
                    resolveColor(),
                    PorterDuff.Mode.SRC_ATOP,
                    -1);

            int padding = mContext.getResources().getDimensionPixelSize(
                    R.dimen.notification_large_icon_circle_padding);
            contentView.setViewPadding(R.id.icon, padding, padding, padding, padding);
        }

        private void removeLargeIconBackground(RemoteViews contentView) {
            contentView.setInt(R.id.icon, "setBackgroundResource", 0);
        }

        /**
         * Recolor small icons when used in the R.id.right_icon slot.
         */
        private void processSmallRightIcon(int smallIconDrawableId,
                RemoteViews contentView) {
            if (!isLegacy()) {
                contentView.setDrawableParameters(R.id.right_icon, false, -1,
                        0xFFFFFFFF,
                        PorterDuff.Mode.SRC_ATOP, -1);
            }
            if (!isLegacy() || mColorUtil.isGrayscaleIcon(mContext, smallIconDrawableId)) {
                contentView.setInt(R.id.right_icon,
                        "setBackgroundResource",
                        R.drawable.notification_icon_legacy_bg);

                contentView.setDrawableParameters(
                        R.id.right_icon,
                        true,
                        -1,
                        resolveColor(),
                        PorterDuff.Mode.SRC_ATOP,
                        -1);
            }
        }

        private int sanitizeColor() {
            if (mColor != COLOR_DEFAULT) {
                mColor |= 0xFF000000; // no alpha for custom colors
            }
            return mColor;
        }

        private int resolveColor() {
            if (mColor == COLOR_DEFAULT) {
                return mContext.getResources().getColor(R.color.notification_icon_bg_color);
            }
            return mColor;
        }

        /**
         * Apply the unstyled operations and return a new {@link Notification} object.
         * @hide
         */
        public Notification buildUnstyled() {
            Notification n = new Notification();
            n.when = mWhen;
            n.icon = mSmallIcon;
            n.iconLevel = mSmallIconLevel;
            n.number = mNumber;

            n.color = sanitizeColor();

            setBuilderContentView(n, makeContentView());
            n.contentIntent = mContentIntent;
            n.deleteIntent = mDeleteIntent;
            n.fullScreenIntent = mFullScreenIntent;
            n.tickerText = mTickerText;
            n.tickerView = makeTickerView();
            n.largeIcon = mLargeIcon;
            n.sound = mSound;
            n.audioStreamType = mAudioStreamType;
            n.audioAttributes = mAudioAttributes;
            n.vibrate = mVibrate;
            n.ledARGB = mLedArgb;
            n.ledOnMS = mLedOnMs;
            n.ledOffMS = mLedOffMs;
            n.defaults = mDefaults;
            n.flags = mFlags;
            setBuilderBigContentView(n, makeBigContentView());
            setBuilderHeadsUpContentView(n, makeHeadsUpContentView());
            if (mLedOnMs != 0 || mLedOffMs != 0) {
                n.flags |= FLAG_SHOW_LIGHTS;
            }
            if ((mDefaults & DEFAULT_LIGHTS) != 0) {
                n.flags |= FLAG_SHOW_LIGHTS;
            }
            n.category = mCategory;
            n.mGroupKey = mGroupKey;
            n.mSortKey = mSortKey;
            n.priority = mPriority;
            if (mActions.size() > 0) {
                n.actions = new Action[mActions.size()];
                mActions.toArray(n.actions);
            }
            n.visibility = mVisibility;

            if (mPublicVersion != null) {
                n.publicVersion = new Notification();
                mPublicVersion.cloneInto(n.publicVersion, true);
            }
            // Note: If you're adding new fields, also update restoreFromNotitification().
            return n;
        }

        /**
         * Capture, in the provided bundle, semantic information used in the construction of
         * this Notification object.
         * @hide
         */
        public void populateExtras(Bundle extras) {
            // Store original information used in the construction of this object
            extras.putInt(EXTRA_ORIGINATING_USERID, mOriginatingUserId);
            extras.putParcelable(EXTRA_REBUILD_CONTEXT_APPLICATION_INFO,
                    mContext.getApplicationInfo());
            extras.putCharSequence(EXTRA_TITLE, mContentTitle);
            extras.putCharSequence(EXTRA_TEXT, mContentText);
            extras.putCharSequence(EXTRA_SUB_TEXT, mSubText);
            extras.putCharSequence(EXTRA_INFO_TEXT, mContentInfo);
            extras.putInt(EXTRA_SMALL_ICON, mSmallIcon);
            extras.putInt(EXTRA_PROGRESS, mProgress);
            extras.putInt(EXTRA_PROGRESS_MAX, mProgressMax);
            extras.putBoolean(EXTRA_PROGRESS_INDETERMINATE, mProgressIndeterminate);
            extras.putBoolean(EXTRA_SHOW_CHRONOMETER, mUseChronometer);
            extras.putBoolean(EXTRA_SHOW_WHEN, mShowWhen);
            if (mLargeIcon != null) {
                extras.putParcelable(EXTRA_LARGE_ICON, mLargeIcon);
            }
            if (!mPeople.isEmpty()) {
                extras.putStringArray(EXTRA_PEOPLE, mPeople.toArray(new String[mPeople.size()]));
            }
            // NOTE: If you're adding new extras also update restoreFromNotification().
        }


        /**
         * @hide
         */
        public static void stripForDelivery(Notification n) {
            if (!STRIP_AND_REBUILD) {
                return;
            }

            String templateClass = n.extras.getString(EXTRA_TEMPLATE);
            // Only strip views for known Styles because we won't know how to
            // re-create them otherwise.
            boolean stripViews = TextUtils.isEmpty(templateClass) ||
                    getNotificationStyleClass(templateClass) != null;

            boolean isStripped = false;

            if (n.largeIcon != null && n.extras.containsKey(EXTRA_LARGE_ICON)) {
                // TODO: Would like to check for equality here, but if the notification
                // has been cloned, we can't.
                n.largeIcon = null;
                n.extras.putBoolean(EXTRA_REBUILD_LARGE_ICON, true);
                isStripped = true;
            }
            // Get rid of unmodified BuilderRemoteViews.

            if (stripViews &&
                    n.contentView instanceof BuilderRemoteViews &&
                    n.extras.getInt(EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT, -1) ==
                            n.contentView.getSequenceNumber()) {
                n.contentView = null;
                n.extras.putBoolean(EXTRA_REBUILD_CONTENT_VIEW, true);
                n.extras.remove(EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT);
                isStripped = true;
            }
            if (stripViews &&
                    n.bigContentView instanceof BuilderRemoteViews &&
                    n.extras.getInt(EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT, -1) ==
                            n.bigContentView.getSequenceNumber()) {
                n.bigContentView = null;
                n.extras.putBoolean(EXTRA_REBUILD_BIG_CONTENT_VIEW, true);
                n.extras.remove(EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT);
                isStripped = true;
            }
            if (stripViews &&
                    n.headsUpContentView instanceof BuilderRemoteViews &&
                    n.extras.getInt(EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT, -1) ==
                            n.headsUpContentView.getSequenceNumber()) {
                n.headsUpContentView = null;
                n.extras.putBoolean(EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW, true);
                n.extras.remove(EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT);
                isStripped = true;
            }

            if (isStripped) {
                n.extras.putBoolean(EXTRA_NEEDS_REBUILD, true);
            }
        }

        /**
         * @hide
         */
        public static Notification rebuild(Context context, Notification n) {
            Bundle extras = n.extras;
            if (!extras.getBoolean(EXTRA_NEEDS_REBUILD)) return n;
            extras.remove(EXTRA_NEEDS_REBUILD);

            // Re-create notification context so we can access app resources.
            ApplicationInfo applicationInfo = extras.getParcelable(
                    EXTRA_REBUILD_CONTEXT_APPLICATION_INFO);
            Context builderContext;
            try {
                builderContext = context.createApplicationContext(applicationInfo,
                        Context.CONTEXT_RESTRICTED);
            } catch (NameNotFoundException e) {
                Log.e(TAG, "ApplicationInfo " + applicationInfo + " not found");
                builderContext = context;  // try with our context
            }

            Builder b = new Builder(builderContext, n);
            return b.rebuild();
        }

        /**
         * Rebuilds the notification passed in to the rebuild-constructor
         * {@link #Builder(Context, Notification)}.
         *
         * <p>
         * Throws IllegalStateException when invoked on a Builder that isn't in rebuild mode.
         *
         * @hide
         */
        private Notification rebuild() {
            if (mRebuildNotification == null) {
                throw new IllegalStateException("rebuild() only valid when in 'rebuild' mode.");
            }
            mHasThreeLines = hasThreeLines();

            Bundle extras = mRebuildNotification.extras;

            if (extras.getBoolean(EXTRA_REBUILD_LARGE_ICON)) {
                mRebuildNotification.largeIcon = extras.getParcelable(EXTRA_LARGE_ICON);
            }
            extras.remove(EXTRA_REBUILD_LARGE_ICON);

            if (extras.getBoolean(EXTRA_REBUILD_CONTENT_VIEW)) {
                setBuilderContentView(mRebuildNotification, makeContentView());
                if (mStyle != null) {
                    mStyle.populateContentView(mRebuildNotification);
                }
            }
            extras.remove(EXTRA_REBUILD_CONTENT_VIEW);

            if (extras.getBoolean(EXTRA_REBUILD_BIG_CONTENT_VIEW)) {
                setBuilderBigContentView(mRebuildNotification, makeBigContentView());
                if (mStyle != null) {
                    mStyle.populateBigContentView(mRebuildNotification);
                }
            }
            extras.remove(EXTRA_REBUILD_BIG_CONTENT_VIEW);

            if (extras.getBoolean(EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW)) {
                setBuilderHeadsUpContentView(mRebuildNotification, makeHeadsUpContentView());
                if (mStyle != null) {
                    mStyle.populateHeadsUpContentView(mRebuildNotification);
                }
            }
            extras.remove(EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW);

            mHasThreeLines = false;
            return mRebuildNotification;
        }

        private static Class<? extends Style> getNotificationStyleClass(String templateClass) {
            Class<? extends Style>[] classes = new Class[]{
                    BigTextStyle.class, BigPictureStyle.class, InboxStyle.class, MediaStyle.class};
            for (Class<? extends Style> innerClass : classes) {
                if (templateClass.equals(innerClass.getName())) {
                    return innerClass;
                }
            }
            return null;
        }

        private void setBuilderContentView(Notification n, RemoteViews contentView) {
            n.contentView = contentView;
            if (contentView instanceof BuilderRemoteViews) {
                mRebuildBundle.putInt(Builder.EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT,
                        contentView.getSequenceNumber());
            }
        }

        private void setBuilderBigContentView(Notification n, RemoteViews bigContentView) {
            n.bigContentView = bigContentView;
            if (bigContentView instanceof BuilderRemoteViews) {
                mRebuildBundle.putInt(Builder.EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT,
                        bigContentView.getSequenceNumber());
            }
        }

        private void setBuilderHeadsUpContentView(Notification n,
                RemoteViews headsUpContentView) {
            n.headsUpContentView = headsUpContentView;
            if (headsUpContentView instanceof BuilderRemoteViews) {
                mRebuildBundle.putInt(Builder.EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT,
                        headsUpContentView.getSequenceNumber());
            }
        }

        private void restoreFromNotification(Notification n) {

            // Notification fields.
            mWhen = n.when;
            mSmallIcon = n.icon;
            mSmallIconLevel = n.iconLevel;
            mNumber = n.number;

            mColor = n.color;

            mContentView = n.contentView;
            mDeleteIntent = n.deleteIntent;
            mFullScreenIntent = n.fullScreenIntent;
            mTickerText = n.tickerText;
            mTickerView = n.tickerView;
            mLargeIcon = n.largeIcon;
            mSound = n.sound;
            mAudioStreamType = n.audioStreamType;
            mAudioAttributes = n.audioAttributes;

            mVibrate = n.vibrate;
            mLedArgb = n.ledARGB;
            mLedOnMs = n.ledOnMS;
            mLedOffMs = n.ledOffMS;
            mDefaults = n.defaults;
            mFlags = n.flags;

            mCategory = n.category;
            mGroupKey = n.mGroupKey;
            mSortKey = n.mSortKey;
            mPriority = n.priority;
            mActions.clear();
            if (n.actions != null) {
                Collections.addAll(mActions, n.actions);
            }
            mVisibility = n.visibility;

            mPublicVersion = n.publicVersion;

            // Extras.
            Bundle extras = n.extras;
            mOriginatingUserId = extras.getInt(EXTRA_ORIGINATING_USERID);
            mContentTitle = extras.getCharSequence(EXTRA_TITLE);
            mContentText = extras.getCharSequence(EXTRA_TEXT);
            mSubText = extras.getCharSequence(EXTRA_SUB_TEXT);
            mContentInfo = extras.getCharSequence(EXTRA_INFO_TEXT);
            mSmallIcon = extras.getInt(EXTRA_SMALL_ICON);
            mProgress = extras.getInt(EXTRA_PROGRESS);
            mProgressMax = extras.getInt(EXTRA_PROGRESS_MAX);
            mProgressIndeterminate = extras.getBoolean(EXTRA_PROGRESS_INDETERMINATE);
            mUseChronometer = extras.getBoolean(EXTRA_SHOW_CHRONOMETER);
            mShowWhen = extras.getBoolean(EXTRA_SHOW_WHEN);
            if (extras.containsKey(EXTRA_LARGE_ICON)) {
                mLargeIcon = extras.getParcelable(EXTRA_LARGE_ICON);
            }
            if (extras.containsKey(EXTRA_PEOPLE)) {
                mPeople.clear();
                Collections.addAll(mPeople, extras.getStringArray(EXTRA_PEOPLE));
            }
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
        public Notification build() {
            mOriginatingUserId = mContext.getUserId();
            mHasThreeLines = hasThreeLines();

            Notification n = buildUnstyled();

            if (mStyle != null) {
                n = mStyle.buildStyled(n);
            }

            if (mExtras != null) {
                n.extras.putAll(mExtras);
            }

            if (mRebuildBundle.size() > 0) {
                n.extras.putAll(mRebuildBundle);
                mRebuildBundle.clear();
            }

            populateExtras(n.extras);
            if (mStyle != null) {
                mStyle.addExtras(n.extras);
            }

            mHasThreeLines = false;
            return n;
        }

        /**
         * Apply this Builder to an existing {@link Notification} object.
         *
         * @hide
         */
        public Notification buildInto(Notification n) {
            build().cloneInto(n, true);
            return n;
        }

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

        private int getActionLayoutResource() {
            return R.layout.notification_material_action;
        }

        private int getActionTombstoneLayoutResource() {
            return R.layout.notification_material_action_tombstone;
        }
    }

    /**
     * An object that can apply a rich notification style to a {@link Notification.Builder}
     * object.
     */
    public static abstract class Style {
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
            checkBuilder();

            // Nasty.
            CharSequence oldBuilderContentTitle = mBuilder.mContentTitle;
            if (mBigContentTitle != null) {
                mBuilder.setContentTitle(mBigContentTitle);
            }

            RemoteViews contentView = mBuilder.applyStandardTemplateWithActions(layoutId);

            mBuilder.mContentTitle = oldBuilderContentTitle;

            if (mBigContentTitle != null && mBigContentTitle.equals("")) {
                contentView.setViewVisibility(R.id.line1, View.GONE);
            } else {
                contentView.setViewVisibility(R.id.line1, View.VISIBLE);
            }

            // The last line defaults to the subtext, but can be replaced by mSummaryText
            final CharSequence overflowText =
                    mSummaryTextSet ? mSummaryText
                                    : mBuilder.mSubText;
            if (overflowText != null) {
                contentView.setTextViewText(R.id.text, mBuilder.processLegacyText(overflowText));
                contentView.setViewVisibility(R.id.overflow_divider, View.VISIBLE);
                contentView.setViewVisibility(R.id.line3, View.VISIBLE);
            } else {
                // Clear text in case we use the line to show the profile badge.
                contentView.setTextViewText(R.id.text, "");
                contentView.setViewVisibility(R.id.overflow_divider, View.GONE);
                contentView.setViewVisibility(R.id.line3, View.GONE);
            }

            return contentView;
        }

        /**
         * Changes the padding of the first line such that the big and small content view have the
         * same top padding.
         *
         * @hide
         */
        protected void applyTopPadding(RemoteViews contentView) {
            int topPadding = Builder.calculateTopPadding(mBuilder.mContext,
                    mBuilder.mHasThreeLines,
                    mBuilder.mContext.getResources().getConfiguration().fontScale);
            contentView.setViewPadding(R.id.line1, 0, topPadding, 0, 0);
        }

        /**
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
            populateTickerView(wip);
            populateContentView(wip);
            populateBigContentView(wip);
            populateHeadsUpContentView(wip);
            return wip;
        }

        // The following methods are split out so we can re-create notification partially.
        /**
         * @hide
         */
        protected void populateTickerView(Notification wip) {}
        /**
         * @hide
         */
        protected void populateContentView(Notification wip) {}

        /**
         * @hide
         */
        protected void populateBigContentView(Notification wip) {}

        /**
         * @hide
         */
        protected void populateHeadsUpContentView(Notification wip) {}

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
        private Bitmap mBigLargeIcon;
        private boolean mBigLargeIconSet = false;

        public BigPictureStyle() {
        }

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
            mBigLargeIconSet = true;
            mBigLargeIcon = b;
            return this;
        }

        private RemoteViews makeBigContentView() {

            // Replace mLargeIcon with mBigLargeIcon if mBigLargeIconSet
            // This covers the following cases:
            //   1. mBigLargeIconSet -> mBigLargeIcon (null or non-null) applies, overrides
            //          mLargeIcon
            //   2. !mBigLargeIconSet -> mLargeIcon applies
            Bitmap oldLargeIcon = null;
            if (mBigLargeIconSet) {
                oldLargeIcon = mBuilder.mLargeIcon;
                mBuilder.mLargeIcon = mBigLargeIcon;
            }

            RemoteViews contentView = getStandardView(mBuilder.getBigPictureLayoutResource());

            if (mBigLargeIconSet) {
                mBuilder.mLargeIcon = oldLargeIcon;
            }

            contentView.setImageViewBitmap(R.id.big_picture, mPicture);

            applyTopPadding(contentView);

            boolean twoTextLines = mBuilder.mSubText != null && mBuilder.mContentText != null;
            mBuilder.addProfileBadge(contentView,
                    twoTextLines ? R.id.profile_badge_line2 : R.id.profile_badge_line3);
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
        public void populateBigContentView(Notification wip) {
            mBuilder.setBuilderBigContentView(wip, makeBigContentView());
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

        private static final int MAX_LINES = 13;
        private static final int LINES_CONSUMED_BY_ACTIONS = 3;
        private static final int LINES_CONSUMED_BY_SUMMARY = 2;

        private CharSequence mBigText;

        public BigTextStyle() {
        }

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

        private RemoteViews makeBigContentView() {

            // Nasty
            CharSequence oldBuilderContentText = mBuilder.mContentText;
            mBuilder.mContentText = null;

            RemoteViews contentView = getStandardView(mBuilder.getBigTextLayoutResource());

            mBuilder.mContentText = oldBuilderContentText;

            contentView.setTextViewText(R.id.big_text, mBuilder.processLegacyText(mBigText));
            contentView.setViewVisibility(R.id.big_text, View.VISIBLE);
            contentView.setInt(R.id.big_text, "setMaxLines", calculateMaxLines());
            contentView.setViewVisibility(R.id.text2, View.GONE);

            applyTopPadding(contentView);

            mBuilder.shrinkLine3Text(contentView);

            mBuilder.addProfileBadge(contentView, R.id.profile_badge_large_template);

            return contentView;
        }

        private int calculateMaxLines() {
            int lineCount = MAX_LINES;
            boolean hasActions = mBuilder.mActions.size() > 0;
            boolean hasSummary = (mSummaryTextSet ? mSummaryText : mBuilder.mSubText) != null;
            if (hasActions) {
                lineCount -= LINES_CONSUMED_BY_ACTIONS;
            }
            if (hasSummary) {
                lineCount -= LINES_CONSUMED_BY_SUMMARY;
            }

            // If we have less top padding at the top, we can fit less lines.
            if (!mBuilder.mHasThreeLines) {
                lineCount--;
            }
            return lineCount;
        }

        /**
         * @hide
         */
        @Override
        public void populateBigContentView(Notification wip) {
            mBuilder.setBuilderBigContentView(wip, makeBigContentView());
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
        private ArrayList<CharSequence> mTexts = new ArrayList<CharSequence>(5);

        public InboxStyle() {
        }

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

        private RemoteViews makeBigContentView() {
            // Remove the content text so line3 disappears unless you have a summary

            // Nasty
            CharSequence oldBuilderContentText = mBuilder.mContentText;
            mBuilder.mContentText = null;

            RemoteViews contentView = getStandardView(mBuilder.getInboxLayoutResource());

            mBuilder.mContentText = oldBuilderContentText;

            contentView.setViewVisibility(R.id.text2, View.GONE);

            int[] rowIds = {R.id.inbox_text0, R.id.inbox_text1, R.id.inbox_text2, R.id.inbox_text3,
                    R.id.inbox_text4, R.id.inbox_text5, R.id.inbox_text6};

            // Make sure all rows are gone in case we reuse a view.
            for (int rowId : rowIds) {
                contentView.setViewVisibility(rowId, View.GONE);
            }

            final boolean largeText =
                    mBuilder.mContext.getResources().getConfiguration().fontScale > 1f;
            final float subTextSize = mBuilder.mContext.getResources().getDimensionPixelSize(
                    R.dimen.notification_subtext_size);
            int i=0;
            while (i < mTexts.size() && i < rowIds.length) {
                CharSequence str = mTexts.get(i);
                if (str != null && !str.equals("")) {
                    contentView.setViewVisibility(rowIds[i], View.VISIBLE);
                    contentView.setTextViewText(rowIds[i], mBuilder.processLegacyText(str));
                    if (largeText) {
                        contentView.setTextViewTextSize(rowIds[i], TypedValue.COMPLEX_UNIT_PX,
                                subTextSize);
                    }
                }
                i++;
            }

            contentView.setViewVisibility(R.id.inbox_end_pad,
                    mTexts.size() > 0 ? View.VISIBLE : View.GONE);

            contentView.setViewVisibility(R.id.inbox_more,
                    mTexts.size() > rowIds.length ? View.VISIBLE : View.GONE);

            applyTopPadding(contentView);

            mBuilder.shrinkLine3Text(contentView);

            mBuilder.addProfileBadge(contentView, R.id.profile_badge_large_template);

            return contentView;
        }

        /**
         * @hide
         */
        @Override
        public void populateBigContentView(Notification wip) {
            mBuilder.setBuilderBigContentView(wip, makeBigContentView());
        }
    }

    /**
     * Notification style for media playback notifications.
     *
     * In the expanded form, {@link Notification#bigContentView}, up to 5
     * {@link Notification.Action}s specified with
     * {@link Notification.Builder#addAction(int, CharSequence, PendingIntent) addAction} will be
     * shown as icon-only pushbuttons, suitable for transport controls. The Bitmap given to
     * {@link Notification.Builder#setLargeIcon(android.graphics.Bitmap) setLargeIcon()} will be
     * treated as album artwork.
     *
     * Unlike the other styles provided here, MediaStyle can also modify the standard-size
     * {@link Notification#contentView}; by providing action indices to
     * {@link #setShowActionsInCompactView(int...)} you can promote up to 3 actions to be displayed
     * in the standard view alongside the usual content.
     *
     * Notifications created with MediaStyle will have their category set to
     * {@link Notification#CATEGORY_TRANSPORT CATEGORY_TRANSPORT} unless you set a different
     * category using {@link Notification.Builder#setCategory(String) setCategory()}.
     *
     * Finally, if you attach a {@link android.media.session.MediaSession.Token} using
     * {@link android.app.Notification.MediaStyle#setMediaSession(MediaSession.Token)},
     * the System UI can identify this as a notification representing an active media session
     * and respond accordingly (by showing album artwork in the lockscreen, for example).
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
     */
    public static class MediaStyle extends Style {
        static final int MAX_MEDIA_BUTTONS_IN_COMPACT = 3;
        static final int MAX_MEDIA_BUTTONS = 5;

        private int[] mActionsToShowInCompact = null;
        private MediaSession.Token mToken;

        public MediaStyle() {
        }

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
        public void populateContentView(Notification wip) {
            mBuilder.setBuilderContentView(wip, makeMediaContentView());
        }

        /**
         * @hide
         */
        @Override
        public void populateBigContentView(Notification wip) {
            mBuilder.setBuilderBigContentView(wip, makeMediaBigContentView());
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

        private RemoteViews generateMediaActionButton(Action action) {
            final boolean tombstone = (action.actionIntent == null);
            RemoteViews button = new RemoteViews(mBuilder.mContext.getPackageName(),
                    R.layout.notification_material_media_action);
            button.setImageViewResource(R.id.action0, action.icon);
            button.setDrawableParameters(R.id.action0, false, -1,
                    0xFFFFFFFF,
                    PorterDuff.Mode.SRC_ATOP, -1);
            if (!tombstone) {
                button.setOnClickPendingIntent(R.id.action0, action.actionIntent);
            }
            button.setContentDescription(R.id.action0, action.title);
            return button;
        }

        private RemoteViews makeMediaContentView() {
            RemoteViews view = mBuilder.applyStandardTemplate(
                    R.layout.notification_template_material_media, false /* hasProgress */);

            final int numActions = mBuilder.mActions.size();
            final int N = mActionsToShowInCompact == null
                    ? 0
                    : Math.min(mActionsToShowInCompact.length, MAX_MEDIA_BUTTONS_IN_COMPACT);
            if (N > 0) {
                view.removeAllViews(com.android.internal.R.id.media_actions);
                for (int i = 0; i < N; i++) {
                    if (i >= numActions) {
                        throw new IllegalArgumentException(String.format(
                                "setShowActionsInCompactView: action %d out of bounds (max %d)",
                                i, numActions - 1));
                    }

                    final Action action = mBuilder.mActions.get(mActionsToShowInCompact[i]);
                    final RemoteViews button = generateMediaActionButton(action);
                    view.addView(com.android.internal.R.id.media_actions, button);
                }
            }
            styleText(view);
            hideRightIcon(view);
            return view;
        }

        private RemoteViews makeMediaBigContentView() {
            final int actionCount = Math.min(mBuilder.mActions.size(), MAX_MEDIA_BUTTONS);
            RemoteViews big = mBuilder.applyStandardTemplate(getBigLayoutResource(actionCount),
                    false /* hasProgress */);

            if (actionCount > 0) {
                big.removeAllViews(com.android.internal.R.id.media_actions);
                for (int i = 0; i < actionCount; i++) {
                    final RemoteViews button = generateMediaActionButton(mBuilder.mActions.get(i));
                    big.addView(com.android.internal.R.id.media_actions, button);
                }
            }
            styleText(big);
            hideRightIcon(big);
            applyTopPadding(big);
            big.setViewVisibility(android.R.id.progress, View.GONE);
            return big;
        }

        private int getBigLayoutResource(int actionCount) {
            if (actionCount <= 3) {
                return R.layout.notification_template_material_big_media_narrow;
            } else {
                return R.layout.notification_template_material_big_media;
            }
        }

        private void hideRightIcon(RemoteViews contentView) {
            contentView.setViewVisibility(R.id.right_icon, View.GONE);
        }

        /**
         * Applies the special text colors for media notifications to all text views.
         */
        private void styleText(RemoteViews contentView) {
            int primaryColor = mBuilder.mContext.getResources().getColor(
                    R.color.notification_media_primary_color);
            int secondaryColor = mBuilder.mContext.getResources().getColor(
                    R.color.notification_media_secondary_color);
            contentView.setTextColor(R.id.title, primaryColor);
            if (mBuilder.showsTimeOrChronometer()) {
                if (mBuilder.mUseChronometer) {
                    contentView.setTextColor(R.id.chronometer, secondaryColor);
                } else {
                    contentView.setTextColor(R.id.time, secondaryColor);
                }
            }
            contentView.setTextColor(R.id.text2, secondaryColor);
            contentView.setTextColor(R.id.text, secondaryColor);
            contentView.setTextColor(R.id.info, secondaryColor);
        }

        /**
         * @hide
         */
        @Override
        protected boolean hasProgress() {
            return false;
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
         * the default is {@link #SIZE_LARGE}. All other notifications size automatically based
         * on their content.
         */
        public static final int SIZE_DEFAULT = 0;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * with an extra small size.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         */
        public static final int SIZE_XSMALL = 1;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * with a small size.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         */
        public static final int SIZE_SMALL = 2;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * with a medium size.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         */
        public static final int SIZE_MEDIUM = 3;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * with a large size.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         */
        public static final int SIZE_LARGE = 4;

        /**
         * Size value for use with {@link #setCustomSizePreset} to show this notification
         * full screen.
         * <p>This value is only applicable for custom display notifications created using
         * {@link #setDisplayIntent}.
         */
        public static final int SIZE_FULL_SCREEN = 5;

        /**
         * Sentinel value for use with {@link #setHintScreenTimeout} to keep the screen on for a
         * short amount of time when this notification is displayed on the screen. This
         * is the default value.
         */
        public static final int SCREEN_TIMEOUT_SHORT = 0;

        /**
         * Sentinel value for use with {@link #setHintScreenTimeout} to keep the screen on
         * for a longer amount of time when this notification is displayed on the screen.
         */
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

        // Flags bitwise-ored to mFlags
        private static final int FLAG_CONTENT_INTENT_AVAILABLE_OFFLINE = 0x1;
        private static final int FLAG_HINT_HIDE_ICON = 1 << 1;
        private static final int FLAG_HINT_SHOW_BACKGROUND_ONLY = 1 << 2;
        private static final int FLAG_START_SCROLL_BOTTOM = 1 << 3;
        private static final int FLAG_HINT_AVOID_BACKGROUND_CLIPPING = 1 << 4;

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
         */
        public WearableExtender setDisplayIntent(PendingIntent intent) {
            mDisplayIntent = intent;
            return this;
        }

        /**
         * Get the intent to launch inside of an activity view when displaying this
         * notification. This {@code PendingIntent} should be for an activity.
         */
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
         */
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
         */
        public WearableExtender addPages(List<Notification> pages) {
            mPages.addAll(pages);
            return this;
        }

        /**
         * Clear all additional pages present on this builder.
         * @return this object for method chaining.
         * @see #addPage
         */
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
         */
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
         */
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
         */
        public Bitmap getBackground() {
            return mBackground;
        }

        /**
         * Set an icon that goes with the content of this notification.
         */
        public WearableExtender setContentIcon(int icon) {
            mContentIcon = icon;
            return this;
        }

        /**
         * Get an icon that goes with the content of this notification.
         */
        public int getContentIcon() {
            return mContentIcon;
        }

        /**
         * Set the gravity that the content icon should have within the notification display.
         * Supported values include {@link android.view.Gravity#START} and
         * {@link android.view.Gravity#END}. The default value is {@link android.view.Gravity#END}.
         * @see #setContentIcon
         */
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
        public int getContentIconGravity() {
            return mContentIconGravity;
        }

        /**
         * Set an action from this notification's actions to be clickable with the content of
         * this notification. This action will no longer display separately from the
         * notification's content.
         *
         * <p>For notifications with multiple pages, child pages can also have content actions
         * set, although the list of available actions comes from the main notification and not
         * from the child page's notification.
         *
         * @param actionIndex The index of the action to hoist onto the current notification page.
         *                    If wearable actions were added to the main notification, this index
         *                    will apply to that list, otherwise it will apply to the regular
         *                    actions list.
         */
        public WearableExtender setContentAction(int actionIndex) {
            mContentActionIndex = actionIndex;
            return this;
        }

        /**
         * Get the index of the notification action, if any, that was specified as being clickable
         * with the content of this notification. This action will no longer display separately
         * from the notification's content.
         *
         * <p>For notifications with multiple pages, child pages can also have content actions
         * set, although the list of available actions comes from the main notification and not
         * from the child page's notification.
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
        public WearableExtender setHintHideIcon(boolean hintHideIcon) {
            setFlag(FLAG_HINT_HIDE_ICON, hintHideIcon);
            return this;
        }

        /**
         * Get a hint that this notification's icon should not be displayed.
         * @return {@code true} if this icon should not be displayed, false otherwise.
         * The default value is {@code false} if this was never set.
         */
        public boolean getHintHideIcon() {
            return (mFlags & FLAG_HINT_HIDE_ICON) != 0;
        }

        /**
         * Set a visual hint that only the background image of this notification should be
         * displayed, and other semantic content should be hidden. This hint is only applicable
         * to sub-pages added using {@link #addPage}.
         */
        public WearableExtender setHintShowBackgroundOnly(boolean hintShowBackgroundOnly) {
            setFlag(FLAG_HINT_SHOW_BACKGROUND_ONLY, hintShowBackgroundOnly);
            return this;
        }

        /**
         * Get a visual hint that only the background image of this notification should be
         * displayed, and other semantic content should be hidden. This hint is only applicable
         * to sub-pages added using {@link android.app.Notification.WearableExtender#addPage}.
         */
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
        public int getHintScreenTimeout() {
            return mHintScreenTimeout;
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
}
