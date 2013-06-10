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

import com.android.internal.R;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RemoteViews;

import java.text.NumberFormat;
import java.util.ArrayList;

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
     * Use all default values (where applicable).
     */
    public static final int DEFAULT_ALL = ~0;

    /**
     * Use the default notification sound. This will ignore any given
     * {@link #sound}.
     *

     * @see #defaults
     */

    public static final int DEFAULT_SOUND = 1;

    /**
     * Use the default notification vibrate. This will ignore any given
     * {@link #vibrate}. Using phone vibration requires the
     * {@link android.Manifest.permission#VIBRATE VIBRATE} permission.
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
     * @see android.graphics.drawable#setLevel
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
     * @see Notification.Builder#setFullScreenIntent
     */
    public PendingIntent fullScreenIntent;

    /**
     * Text to scroll across the screen when this item is added to
     * the status bar on large and smaller devices.
     *
     * @see #tickerView
     */
    public CharSequence tickerText;

    /**
     * The view to show as the ticker in the status bar when the notification
     * is posted.
     */
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
     * The bitmap that may escape the bounds of the panel and bar.
     */
    public Bitmap largeIcon;

    /**
     * The sound to play.
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
     */
    public static final int STREAM_DEFAULT = -1;

    /**
     * The audio stream type to use when playing the sound.
     * Should be one of the STREAM_ constants from
     * {@link android.media.AudioManager}.
     */
    public int audioStreamType = STREAM_DEFAULT;

    /**
     * The pattern with which to vibrate.
     *
     * <p>
     * To vibrate the default pattern, see {@link #defaults}.
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
     * set if you want the sound and/or vibration play each time the
     * notification is sent, even if it has not been canceled before that.
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

    public int flags;

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
     */
    public int priority;

    /**
     * @hide
     * Notification type: incoming call (voice or video) or similar synchronous communication request.
     */
    public static final String KIND_CALL = "android.call";

    /**
     * @hide
     * Notification type: incoming direct message (SMS, instant message, etc.).
     */
    public static final String KIND_MESSAGE = "android.message";

    /**
     * @hide
     * Notification type: asynchronous bulk message (email).
     */
    public static final String KIND_EMAIL = "android.email";

    /**
     * @hide
     * Notification type: calendar event.
     */
    public static final String KIND_EVENT = "android.event";

    /**
     * @hide
     * Notification type: promotion or advertisement.
     */
    public static final String KIND_PROMO = "android.promo";

    /**
     * @hide
     * If this notification matches of one or more special types (see the <code>KIND_*</code>
     * constants), add them here, best match first.
     */
    public String[] kind;

    /**
     * Additional semantic data to be carried around with this Notification.
     * @hide
     */
    public Bundle extras = new Bundle();

    // extras keys for Builder inputs
    /** @hide */
    public static final String EXTRA_TITLE = "android.title";
    /** @hide */
    public static final String EXTRA_TITLE_BIG = EXTRA_TITLE + ".big";
    /** @hide */
    public static final String EXTRA_TEXT = "android.text";
    /** @hide */
    public static final String EXTRA_SUB_TEXT = "android.subText";
    /** @hide */
    public static final String EXTRA_INFO_TEXT = "android.infoText";
    /** @hide */
    public static final String EXTRA_SUMMARY_TEXT = "android.summaryText";
    /** @hide */
    public static final String EXTRA_SMALL_ICON = "android.icon";
    /** @hide */
    public static final String EXTRA_LARGE_ICON = "android.largeIcon";
    /** @hide */
    public static final String EXTRA_LARGE_ICON_BIG = EXTRA_LARGE_ICON + ".big";
    /** @hide */
    public static final String EXTRA_PROGRESS = "android.progress";
    /** @hide */
    public static final String EXTRA_PROGRESS_MAX = "android.progressMax";
    /** @hide */
    public static final String EXTRA_PROGRESS_INDETERMINATE = "android.progressIndeterminate";
    /** @hide */
    public static final String EXTRA_SHOW_CHRONOMETER = "android.showChronometer";
    /** @hide */
    public static final String EXTRA_SHOW_WHEN = "android.showWhen";
    /** @hide from BigPictureStyle */
    public static final String EXTRA_PICTURE = "android.picture";
    /** @hide from InboxStyle */
    public static final String EXTRA_TEXT_LINES = "android.textLines";

    // extras keys for other interesting pieces of information
    /** @hide */
    public static final String EXTRA_PEOPLE = "android.people";

    /**
     * Structure to encapsulate an "action", including title and icon, that can be attached to a Notification.
     * @hide
     */
    public static class Action implements Parcelable {
        public int icon;
        public CharSequence title;
        public PendingIntent actionIntent;
        @SuppressWarnings("unused")
        public Action() { }
        private Action(Parcel in) {
            icon = in.readInt();
            title = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            if (in.readInt() == 1) {
                actionIntent = PendingIntent.CREATOR.createFromParcel(in);
            }
        }
        public Action(int icon_, CharSequence title_, PendingIntent intent_) {
            this.icon = icon_;
            this.title = title_;
            this.actionIntent = intent_;
        }
        @Override
        public Action clone() {
            return new Action(
                this.icon,
                this.title.toString(),
                this.actionIntent // safe to alias
            );
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
        }
        public static final Parcelable.Creator<Action> CREATOR
        = new Parcelable.Creator<Action>() {
            public Action createFromParcel(Parcel in) {
                return new Action(in);
            }
            public Action[] newArray(int size) {
                return new Action[size];
            }
        };
    }

    /**
     * @hide
     */
    public Action[] actions;

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
        vibrate = parcel.createLongArray();
        ledARGB = parcel.readInt();
        ledOnMS = parcel.readInt();
        ledOffMS = parcel.readInt();
        iconLevel = parcel.readInt();

        if (parcel.readInt() != 0) {
            fullScreenIntent = PendingIntent.CREATOR.createFromParcel(parcel);
        }

        priority = parcel.readInt();

        kind = parcel.createStringArray(); // may set kind to null

        extras = parcel.readBundle(); // may be null

        actions = parcel.createTypedArray(Action.CREATOR); // may be null

        if (parcel.readInt() != 0) {
            bigContentView = RemoteViews.CREATOR.createFromParcel(parcel);
        }
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

        final String[] thiskind = this.kind;
        if (thiskind != null) {
            final int N = thiskind.length;
            final String[] thatkind = that.kind = new String[N];
            System.arraycopy(thiskind, 0, thatkind, 0, N);
        }

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
        largeIcon = null;
        if (extras != null) {
            extras.remove(Notification.EXTRA_LARGE_ICON);
            extras.remove(Notification.EXTRA_LARGE_ICON_BIG);
            extras.remove(Notification.EXTRA_PICTURE);
        }
    }

    /**
     * Make sure this CharSequence is safe to put into a bundle, which basically
     * means it had better not be some custom Parcelable implementation.
     * @hide
     */
    public static CharSequence safeCharSequence(CharSequence cs) {
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

        parcel.writeStringArray(kind); // ok for null

        parcel.writeBundle(extras); // null ok

        parcel.writeTypedArray(actions, 0); // null ok

        if (bigContentView != null) {
            parcel.writeInt(1);
            bigContentView.writeToParcel(parcel, 0);
        } else {
            parcel.writeInt(0);
        }
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
        builder.mFlags = this.flags;
        builder.setSound(this.sound, this.audioStreamType);
        builder.setDefaults(this.defaults);
        builder.setVibrate(this.vibrate);

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
        // TODO(dsandler): defaults take precedence over local values, so reorder the branches below
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
        sb.append(" kind=[");
        if (this.kind == null) {
            sb.append("null");
        } else {
            for (int i=0; i<this.kind.length; i++) {
                if (i>0) sb.append(",");
                sb.append(this.kind[i]);
            }
        }
        sb.append("]");
        if (actions != null) {
            sb.append(" ");
            sb.append(actions.length);
            sb.append(" action");
            if (actions.length > 1) sb.append("s");
        }
        sb.append(")");
        return sb.toString();
    }

    /** {@hide} */
    public void setUser(UserHandle user) {
        if (user.getIdentifier() == UserHandle.USER_ALL) {
            user = UserHandle.OWNER;
        }
        if (tickerView != null) {
            tickerView.setUser(user);
        }
        if (contentView != null) {
            contentView.setUser(user);
        }
        if (bigContentView != null) {
            bigContentView.setUser(user);
        }
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
        private long[] mVibrate;
        private int mLedArgb;
        private int mLedOnMs;
        private int mLedOffMs;
        private int mDefaults;
        private int mFlags;
        private int mProgressMax;
        private int mProgress;
        private boolean mProgressIndeterminate;
        private ArrayList<String> mKindList = new ArrayList<String>(1);
        private Bundle mExtras;
        private int mPriority;
        private ArrayList<Action> mActions = new ArrayList<Action>(MAX_ACTION_BUTTONS);
        private boolean mUseChronometer;
        private Style mStyle;
        private boolean mShowWhen = true;

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
            mContext = context;

            // Set defaults to match the defaults of a Notification
            mWhen = System.currentTimeMillis();
            mAudioStreamType = STREAM_DEFAULT;
            mPriority = PRIORITY_DEFAULT;
        }

        /**
         * Add a timestamp pertaining to the notification (usually the time the event occurred).
         * It will be shown in the notification content view by default; use
         * {@link Builder#setShowWhen(boolean) setShowWhen} to control this.
         *
         * @see Notification#when
         */
        public Builder setWhen(long when) {
            mWhen = when;
            return this;
        }

        /**
         * Control whether the timestamp set with {@link Builder#setWhen(long) setWhen} is shown
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
         * Set the "ticker" text which is displayed in the status bar when the notification first
         * arrives.
         *
         * @see Notification#tickerText
         */
        public Builder setTicker(CharSequence tickerText) {
            mTickerText = safeCharSequence(tickerText);
            return this;
        }

        /**
         * Set the text that is displayed in the status bar when the notification first
         * arrives, and also a RemoteViews object that may be displayed instead on some
         * devices.
         *
         * @see Notification#tickerText
         * @see Notification#tickerView
         */
        public Builder setTicker(CharSequence tickerText, RemoteViews views) {
            mTickerText = safeCharSequence(tickerText);
            mTickerView = views;
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
         * It will be played on the {@link #STREAM_DEFAULT default stream} for notifications.
         *
         * @see Notification#sound
         */
        public Builder setSound(Uri sound) {
            mSound = sound;
            mAudioStreamType = STREAM_DEFAULT;
            return this;
        }

        /**
         * Set the sound to play, along with a specific stream on which to play it.
         *
         * See {@link android.media.AudioManager} for the <code>STREAM_</code> constants.
         *
         * @see Notification#sound
         */
        public Builder setSound(Uri sound, int streamType) {
            mSound = sound;
            mAudioStreamType = streamType;
            return this;
        }

        /**
         * Set the vibration pattern to use.
         *

         * See {@link android.os.Vibrator#vibrate(long[], int)} for a discussion of the
         * <code>pattern</code> parameter.
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
        public Builder setPriority(int pri) {
            mPriority = pri;
            return this;
        }

        /**
         * @hide
         *
         * Add a kind (category) to this notification. Optional.
         *
         * @see Notification#kind
         */
        public Builder addKind(String k) {
            mKindList.add(k);
            return this;
        }

        /**
         * Add metadata to this notification.
         *
         * A reference to the Bundle is held for the lifetime of this Builder, and the Bundle's
         * current contents are copied into the Notification each time {@link #build()} is
         * called.
         *
         * @see Notification#extras
         * @hide
         */
        public Builder setExtras(Bundle bag) {
            mExtras = bag;
            return this;
        }

        /**
         * Add an action to this notification. Actions are typically displayed by
         * the system as a button adjacent to the notification content.
         * <br>
         * A notification displays up to 3 actions, from left to right in the order they were added.
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

        private void setFlag(int mask, boolean value) {
            if (value) {
                mFlags |= mask;
            } else {
                mFlags &= ~mask;
            }
        }

        private RemoteViews applyStandardTemplate(int resId, boolean fitIn1U) {
            RemoteViews contentView = new RemoteViews(mContext.getPackageName(), resId);
            boolean showLine3 = false;
            boolean showLine2 = false;
            int smallIconImageViewId = R.id.icon;
            if (mLargeIcon != null) {
                contentView.setImageViewBitmap(R.id.icon, mLargeIcon);
                smallIconImageViewId = R.id.right_icon;
            }
            if (mPriority < PRIORITY_LOW) {
                contentView.setInt(R.id.icon,
                        "setBackgroundResource", R.drawable.notification_template_icon_low_bg);
                contentView.setInt(R.id.status_bar_latest_event_content,
                        "setBackgroundResource", R.drawable.notification_bg_low);
            }
            if (mSmallIcon != 0) {
                contentView.setImageViewResource(smallIconImageViewId, mSmallIcon);
                contentView.setViewVisibility(smallIconImageViewId, View.VISIBLE);
            } else {
                contentView.setViewVisibility(smallIconImageViewId, View.GONE);
            }
            if (mContentTitle != null) {
                contentView.setTextViewText(R.id.title, mContentTitle);
            }
            if (mContentText != null) {
                contentView.setTextViewText(R.id.text, mContentText);
                showLine3 = true;
            }
            if (mContentInfo != null) {
                contentView.setTextViewText(R.id.info, mContentInfo);
                contentView.setViewVisibility(R.id.info, View.VISIBLE);
                showLine3 = true;
            } else if (mNumber > 0) {
                final int tooBig = mContext.getResources().getInteger(
                        R.integer.status_bar_notification_info_maxnum);
                if (mNumber > tooBig) {
                    contentView.setTextViewText(R.id.info, mContext.getResources().getString(
                                R.string.status_bar_notification_info_overflow));
                } else {
                    NumberFormat f = NumberFormat.getIntegerInstance();
                    contentView.setTextViewText(R.id.info, f.format(mNumber));
                }
                contentView.setViewVisibility(R.id.info, View.VISIBLE);
                showLine3 = true;
            } else {
                contentView.setViewVisibility(R.id.info, View.GONE);
            }

            // Need to show three lines?
            if (mSubText != null) {
                contentView.setTextViewText(R.id.text, mSubText);
                if (mContentText != null) {
                    contentView.setTextViewText(R.id.text2, mContentText);
                    contentView.setViewVisibility(R.id.text2, View.VISIBLE);
                    showLine2 = true;
                } else {
                    contentView.setViewVisibility(R.id.text2, View.GONE);
                }
            } else {
                contentView.setViewVisibility(R.id.text2, View.GONE);
                if (mProgressMax != 0 || mProgressIndeterminate) {
                    contentView.setProgressBar(
                            R.id.progress, mProgressMax, mProgress, mProgressIndeterminate);
                    contentView.setViewVisibility(R.id.progress, View.VISIBLE);
                    showLine2 = true;
                } else {
                    contentView.setViewVisibility(R.id.progress, View.GONE);
                }
            }
            if (showLine2) {
                if (fitIn1U) {
                    // need to shrink all the type to make sure everything fits
                    final Resources res = mContext.getResources();
                    final float subTextSize = res.getDimensionPixelSize(
                            R.dimen.notification_subtext_size);
                    contentView.setTextViewTextSize(R.id.text, TypedValue.COMPLEX_UNIT_PX, subTextSize);
                }
                // vertical centering
                contentView.setViewPadding(R.id.line1, 0, 0, 0, 0);
            }

            if (mWhen != 0 && mShowWhen) {
                if (mUseChronometer) {
                    contentView.setViewVisibility(R.id.chronometer, View.VISIBLE);
                    contentView.setLong(R.id.chronometer, "setBase",
                            mWhen + (SystemClock.elapsedRealtime() - System.currentTimeMillis()));
                    contentView.setBoolean(R.id.chronometer, "setStarted", true);
                } else {
                    contentView.setViewVisibility(R.id.time, View.VISIBLE);
                    contentView.setLong(R.id.time, "setTime", mWhen);
                }
            } else {
                contentView.setViewVisibility(R.id.time, View.GONE);
            }

            contentView.setViewVisibility(R.id.line3, showLine3 ? View.VISIBLE : View.GONE);
            contentView.setViewVisibility(R.id.overflow_divider, showLine3 ? View.VISIBLE : View.GONE);
            return contentView;
        }

        private RemoteViews applyStandardTemplateWithActions(int layoutId) {
            RemoteViews big = applyStandardTemplate(layoutId, false);

            int N = mActions.size();
            if (N > 0) {
                // Log.d("Notification", "has actions: " + mContentText);
                big.setViewVisibility(R.id.actions, View.VISIBLE);
                big.setViewVisibility(R.id.action_divider, View.VISIBLE);
                if (N>MAX_ACTION_BUTTONS) N=MAX_ACTION_BUTTONS;
                big.removeAllViews(R.id.actions);
                for (int i=0; i<N; i++) {
                    final RemoteViews button = generateActionButton(mActions.get(i));
                    //Log.d("Notification", "adding action " + i + ": " + mActions.get(i).title);
                    big.addView(R.id.actions, button);
                }
            }
            return big;
        }

        private RemoteViews makeContentView() {
            if (mContentView != null) {
                return mContentView;
            } else {
                return applyStandardTemplate(R.layout.notification_template_base, true); // no more special large_icon flavor
            }
        }

        private RemoteViews makeTickerView() {
            if (mTickerView != null) {
                return mTickerView;
            } else {
                if (mContentView == null) {
                    return applyStandardTemplate(mLargeIcon == null
                            ? R.layout.status_bar_latest_event_ticker
                            : R.layout.status_bar_latest_event_ticker_large_icon, true);
                } else {
                    return null;
                }
            }
        }

        private RemoteViews makeBigContentView() {
            if (mActions.size() == 0) return null;

            return applyStandardTemplateWithActions(R.layout.notification_template_big_base);
        }

        private RemoteViews generateActionButton(Action action) {
            final boolean tombstone = (action.actionIntent == null);
            RemoteViews button = new RemoteViews(mContext.getPackageName(),
                    tombstone ? R.layout.notification_action_tombstone
                              : R.layout.notification_action);
            button.setTextViewCompoundDrawables(R.id.action0, action.icon, 0, 0, 0);
            button.setTextViewText(R.id.action0, action.title);
            if (!tombstone) {
                button.setOnClickPendingIntent(R.id.action0, action.actionIntent);
            }
            button.setContentDescription(R.id.action0, action.title);
            return button;
        }

        /**
         * Apply the unstyled operations and return a new {@link Notification} object.
         */
        private Notification buildUnstyled() {
            Notification n = new Notification();
            n.when = mWhen;
            n.icon = mSmallIcon;
            n.iconLevel = mSmallIconLevel;
            n.number = mNumber;
            n.contentView = makeContentView();
            n.contentIntent = mContentIntent;
            n.deleteIntent = mDeleteIntent;
            n.fullScreenIntent = mFullScreenIntent;
            n.tickerText = mTickerText;
            n.tickerView = makeTickerView();
            n.largeIcon = mLargeIcon;
            n.sound = mSound;
            n.audioStreamType = mAudioStreamType;
            n.vibrate = mVibrate;
            n.ledARGB = mLedArgb;
            n.ledOnMS = mLedOnMs;
            n.ledOffMS = mLedOffMs;
            n.defaults = mDefaults;
            n.flags = mFlags;
            n.bigContentView = makeBigContentView();
            if (mLedOnMs != 0 || mLedOffMs != 0) {
                n.flags |= FLAG_SHOW_LIGHTS;
            }
            if ((mDefaults & DEFAULT_LIGHTS) != 0) {
                n.flags |= FLAG_SHOW_LIGHTS;
            }
            if (mKindList.size() > 0) {
                n.kind = new String[mKindList.size()];
                mKindList.toArray(n.kind);
            } else {
                n.kind = null;
            }
            n.priority = mPriority;
            if (mActions.size() > 0) {
                n.actions = new Action[mActions.size()];
                mActions.toArray(n.actions);
            }

            return n;
        }

        /**
         * Capture, in the provided bundle, semantic information used in the construction of
         * this Notification object.
         * @hide
         */
        public void addExtras(Bundle extras) {
            // Store original information used in the construction of this object
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
            final Notification n;

            if (mStyle != null) {
                n = mStyle.build();
            } else {
                n = buildUnstyled();
            }

            n.extras = mExtras != null ? new Bundle(mExtras) : new Bundle();

            addExtras(n.extras);
            if (mStyle != null) {
                mStyle.addExtras(n.extras);
            }

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
    }

    /**
     * An object that can apply a rich notification style to a {@link Notification.Builder}
     * object.
     */
    public static abstract class Style
    {
        private CharSequence mBigContentTitle;
        private CharSequence mSummaryText = null;
        private boolean mSummaryTextSet = false;

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

            if (mBigContentTitle != null) {
                mBuilder.setContentTitle(mBigContentTitle);
            }

            RemoteViews contentView = mBuilder.applyStandardTemplateWithActions(layoutId);

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
                contentView.setTextViewText(R.id.text, overflowText);
                contentView.setViewVisibility(R.id.overflow_divider, View.VISIBLE);
                contentView.setViewVisibility(R.id.line3, View.VISIBLE);
            } else {
                contentView.setViewVisibility(R.id.overflow_divider, View.GONE);
                contentView.setViewVisibility(R.id.line3, View.GONE);
            }

            return contentView;
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
        }

        public abstract Notification build();
    }

    /**
     * Helper class for generating large-format notifications that include a large image attachment.
     *
     * This class is a "rebuilder": It consumes a Builder object and modifies its behavior, like so:
     * <pre class="prettyprint">
     * Notification noti = new Notification.BigPictureStyle(
     *      new Notification.Builder()
     *         .setContentTitle(&quot;New photo from &quot; + sender.toString())
     *         .setContentText(subject)
     *         .setSmallIcon(R.drawable.new_post)
     *         .setLargeIcon(aBitmap))
     *      .bigPicture(aBigBitmap)
     *      .build();
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
            RemoteViews contentView = getStandardView(R.layout.notification_template_big_picture);

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

        @Override
        public Notification build() {
            checkBuilder();
            Notification wip = mBuilder.buildUnstyled();
            if (mBigLargeIconSet ) {
                mBuilder.mLargeIcon = mBigLargeIcon;
            }
            wip.bigContentView = makeBigContentView();
            return wip;
        }
    }

    /**
     * Helper class for generating large-format notifications that include a lot of text.
     *
     * This class is a "rebuilder": It consumes a Builder object and modifies its behavior, like so:
     * <pre class="prettyprint">
     * Notification noti = new Notification.BigTextStyle(
     *      new Notification.Builder()
     *         .setContentTitle(&quot;New mail from &quot; + sender.toString())
     *         .setContentText(subject)
     *         .setSmallIcon(R.drawable.new_mail)
     *         .setLargeIcon(aBitmap))
     *      .bigText(aVeryLongString)
     *      .build();
     * </pre>
     *
     * @see Notification#bigContentView
     */
    public static class BigTextStyle extends Style {
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

            extras.putCharSequence(EXTRA_TEXT, mBigText);
        }

        private RemoteViews makeBigContentView() {
            // Remove the content text so line3 only shows if you have a summary
            final boolean hadThreeLines = (mBuilder.mContentText != null && mBuilder.mSubText != null);
            mBuilder.mContentText = null;

            RemoteViews contentView = getStandardView(R.layout.notification_template_big_text);

            if (hadThreeLines) {
                // vertical centering
                contentView.setViewPadding(R.id.line1, 0, 0, 0, 0);
            }

            contentView.setTextViewText(R.id.big_text, mBigText);
            contentView.setViewVisibility(R.id.big_text, View.VISIBLE);
            contentView.setViewVisibility(R.id.text2, View.GONE);

            return contentView;
        }

        @Override
        public Notification build() {
            checkBuilder();
            Notification wip = mBuilder.buildUnstyled();
            wip.bigContentView = makeBigContentView();

            wip.extras.putCharSequence(EXTRA_TEXT, mBigText);

            return wip;
        }
    }

    /**
     * Helper class for generating large-format notifications that include a list of (up to 5) strings.
     *
     * This class is a "rebuilder": It consumes a Builder object and modifies its behavior, like so:
     * <pre class="prettyprint">
     * Notification noti = new Notification.InboxStyle(
     *      new Notification.Builder()
     *         .setContentTitle(&quot;5 New mails from &quot; + sender.toString())
     *         .setContentText(subject)
     *         .setSmallIcon(R.drawable.new_mail)
     *         .setLargeIcon(aBitmap))
     *      .addLine(str1)
     *      .addLine(str2)
     *      .setContentTitle("")
     *      .setSummaryText(&quot;+3 more&quot;)
     *      .build();
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

        private RemoteViews makeBigContentView() {
            // Remove the content text so line3 disappears unless you have a summary
            mBuilder.mContentText = null;
            RemoteViews contentView = getStandardView(R.layout.notification_template_inbox);

            contentView.setViewVisibility(R.id.text2, View.GONE);

            int[] rowIds = {R.id.inbox_text0, R.id.inbox_text1, R.id.inbox_text2, R.id.inbox_text3,
                    R.id.inbox_text4, R.id.inbox_text5, R.id.inbox_text6};

            // Make sure all rows are gone in case we reuse a view.
            for (int rowId : rowIds) {
                contentView.setViewVisibility(rowId, View.GONE);
            }


            int i=0;
            while (i < mTexts.size() && i < rowIds.length) {
                CharSequence str = mTexts.get(i);
                if (str != null && !str.equals("")) {
                    contentView.setViewVisibility(rowIds[i], View.VISIBLE);
                    contentView.setTextViewText(rowIds[i], str);
                }
                i++;
            }

            contentView.setViewVisibility(R.id.inbox_end_pad,
                    mTexts.size() > 0 ? View.VISIBLE : View.GONE);

            contentView.setViewVisibility(R.id.inbox_more,
                    mTexts.size() > rowIds.length ? View.VISIBLE : View.GONE);

            return contentView;
        }

        @Override
        public Notification build() {
            checkBuilder();
            Notification wip = mBuilder.buildUnstyled();
            wip.bigContentView = makeBigContentView();

            return wip;
        }
    }
}
