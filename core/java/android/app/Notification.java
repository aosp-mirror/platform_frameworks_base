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

import java.util.Date;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.widget.RemoteViews;

/**
 * A class that represents how a persistent notification is to be presented to
 * the user using the {@link android.app.NotificationManager}.
 *
 * <p>For a guide to creating notifications, see the
 * <a href="{@docRoot}guide/topics/ui/notifiers/notifications.html">Creating Status 
 * Bar Notifications</a> document in the Dev Guide.</p>
 */
public class Notification implements Parcelable
{
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
     * The timestamp for the notification.  The icons and expanded views
     * are sorted by this key.
     */
    public long when;

    /**
     * The resource id of a drawable to use as the icon in the status bar.
     */
    public int icon;

    /**
     * The number of events that this notification represents.  For example, if this is the
     * new mail notification, this would be the number of unread messages.  This number is
     * be superimposed over the icon in the status bar.  If the number is 0 or negative, it
     * is not shown in the status bar.
     */
    public int number;

    /**
     * The intent to execute when the expanded status entry is clicked.  If
     * this is an activity, it must include the
     * {@link android.content.Intent#FLAG_ACTIVITY_NEW_TASK} flag, which requires
     * that you take care of task management as described in the <em>Activities and Tasks</em>
     * section of the <a href="{@docRoot}guide/topics/fundamentals.html#acttask">Application 
     * Fundamentals</a> document.
     */
    public PendingIntent contentIntent;

    /**
     * The intent to execute when the status entry is deleted by the user
     * with the "Clear All Notifications" button. This probably shouldn't
     * be launching an activity since several of those will be sent at the
     * same time.
     */
    public PendingIntent deleteIntent;

    /**
     * Text to scroll across the screen when this item is added to
     * the status bar.
     */
    public CharSequence tickerText;

    /**
     * The view that shows when this notification is shown in the expanded status bar.
     */
    public RemoteViews contentView;

    /**
     * If the icon in the status bar is to have more than one level, you can set this.  Otherwise,
     * leave it at its default value of 0.
     *
     * @see android.widget.ImageView#setImageLevel
     * @see android.graphics.drawable#setLevel
     */
    public int iconLevel;

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
     * default stream type is STREAM_RING.
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

    public int flags;

    /**
     * Constructs a Notification object with everything set to 0.
     */
    public Notification()
    {
        this.when = System.currentTimeMillis();
    }

    /**
     * @deprecated use {@link #Notification(int,CharSequence,long)} and {@link #setLatestEventInfo}.
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
     */
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
            contentView = RemoteViews.CREATOR.createFromParcel(parcel);
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
        if (contentView != null) {
            parcel.writeInt(1);
            contentView.writeToParcel(parcel, 0);
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
     * that you take care of task management as described in 
     * <a href="{@docRoot}guide/topics/fundamentals.html#lcycles">Application Fundamentals: Activities and Tasks</a>.
     */
    public void setLatestEventInfo(Context context,
            CharSequence contentTitle, CharSequence contentText, PendingIntent contentIntent) {
        RemoteViews contentView = new RemoteViews(context.getPackageName(),
                com.android.internal.R.layout.status_bar_latest_event_content);
        if (this.icon != 0) {
            contentView.setImageViewResource(com.android.internal.R.id.icon, this.icon);
        }
        if (contentTitle != null) {
            contentView.setTextViewText(com.android.internal.R.id.title, contentTitle);
        }
        if (contentText != null) {
            contentView.setTextViewText(com.android.internal.R.id.text, contentText);
        }
        if (this.when != 0) {
            contentView.setLong(com.android.internal.R.id.time, "setTime", when);
        }

        this.contentView = contentView;
        this.contentIntent = contentIntent;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Notification(vibrate=");
        if (this.vibrate != null) {
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
        } else if ((this.defaults & DEFAULT_VIBRATE) != 0) {
            sb.append("default");
        } else {
            sb.append("null");
        }
        sb.append(",sound=");
        if (this.sound != null) {
            sb.append(this.sound.toString());
        } else if ((this.defaults & DEFAULT_SOUND) != 0) {
            sb.append("default");
        } else {
            sb.append("null");
        }
        sb.append(",defaults=0x");
        sb.append(Integer.toHexString(this.defaults));
        sb.append(")");
        return sb.toString();
    }
}
