/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app.wearable;

import android.app.Notification;
import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Gravity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper class that contains wearable extensions for notifications.
 * <p class="note"> See
 * <a href="{@docRoot}wear/notifications/creating.html">Creating Notifications
 * for Android Wear</a> for more information on how to use this class.
 * <p>
 * To create a notification with wearable extensions:
 * <ol>
 *   <li>Create a {@link Notification.Builder}, setting any desired
 *   properties.
 *   <li>Create a {@link WearableNotificationExtensions.Builder}.
 *   <li>Set wearable-specific properties using the
 *   {@code add} and {@code set} methods of {@link WearableNotificationExtensions.Builder}.
 *   <li>Call {@link WearableNotificationExtensions.Builder#build} to build the extensions
 *   object.
 *   <li>Call {@link Notification.Builder#apply} to apply the extensions to a notification.
 *   <li>Post the notification to the notification system with the
 *   {@code NotificationManager.notify(...)} methods.
 * </ol>
 *
 * <pre class="prettyprint">
 * Notification notif = new Notification.Builder(mContext)
 *         .setContentTitle(&quot;New mail from &quot; + sender.toString())
 *         .setContentText(subject)
 *         .setSmallIcon(R.drawable.new_mail)
 *         .apply(new new WearableNotificationExtensions.Builder()
 *                 .setContentIcon(R.drawable.new_mail)
 *                 .build())
 *         .build();
 * NotificationManager notificationManger =
 *         (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
 * notificationManger.notify(0, notif);</pre>
 *
 * <p>Wearable extensions can be accessed on an existing notification by using the
 * {@link WearableNotificationExtensions#from} function.
 *
 * <pre class="prettyprint">
 * WearableNotificationExtensions wearableExtensions = WearableNotificationExtensions.from(
 *         notification);
 * Notification[] pages = wearableExtensions.getPages();
 * </pre>
 */
public final class WearableNotificationExtensions implements Notification.Builder.Extender,
        Parcelable {
    /**
     * Sentinel value for an action index that is unset.
     */
    public static final int UNSET_ACTION_INDEX = -1;

    /**
     * Size value for use with {@link Builder#setCustomSizePreset} to show this notification with
     * default sizing.
     * <p>For custom display notifications created using {@link Builder#setDisplayIntent},
     * the default is {@link #SIZE_LARGE}. All other notifications size automatically based
     * on their content.
     */
    public static final int SIZE_DEFAULT = 0;

    /**
     * Size value for use with {@link Builder#setCustomSizePreset} to show this notification
     * with an extra small size.
     * <p>This value is only applicable for custom display notifications created using
     * {@link Builder#setDisplayIntent}.
     */
    public static final int SIZE_XSMALL = 1;

    /**
     * Size value for use with {@link Builder#setCustomSizePreset} to show this notification
     * with a small size.
     * <p>This value is only applicable for custom display notifications created using
     * {@link Builder#setDisplayIntent}.
     */
    public static final int SIZE_SMALL = 2;

    /**
     * Size value for use with {@link Builder#setCustomSizePreset} to show this notification
     * with a medium size.
     * <p>This value is only applicable for custom display notifications created using
     * {@link Builder#setDisplayIntent}.
     */
    public static final int SIZE_MEDIUM = 3;

    /**
     * Size value for use with {@link Builder#setCustomSizePreset} to show this notification
     * with a large size.
     * <p>This value is only applicable for custom display notifications created using
     * {@link Builder#setDisplayIntent}.
     */
    public static final int SIZE_LARGE = 4;

    /** Notification extra which contains wearable extensions */
    static final String EXTRA_WEARABLE_EXTENSIONS = "android.wearable.EXTENSIONS";

    // Flags bitwise-ored to mFlags
    static final int FLAG_CONTENT_INTENT_AVAILABLE_OFFLINE = 1 << 0;
    static final int FLAG_HINT_HIDE_ICON = 1 << 1;
    static final int FLAG_HINT_SHOW_BACKGROUND_ONLY = 1 << 2;
    static final int FLAG_START_SCROLL_BOTTOM = 1 << 3;

    // Default value for flags integer
    static final int DEFAULT_FLAGS = FLAG_CONTENT_INTENT_AVAILABLE_OFFLINE;

    private final Notification.Action[] mActions;
    private final int mFlags;
    private final PendingIntent mDisplayIntent;
    private final Notification[] mPages;
    private final Bitmap mBackground;
    private final int mContentIcon;
    private final int mContentIconGravity;
    private final int mContentActionIndex;
    private final int mCustomSizePreset;
    private final int mCustomContentHeight;
    private final int mGravity;

    private WearableNotificationExtensions(Notification.Action[] actions, int flags,
            PendingIntent displayIntent, Notification[] pages, Bitmap background,
            int contentIcon, int contentIconGravity, int contentActionIndex,
            int customSizePreset, int customContentHeight, int gravity) {
        mActions = actions;
        mFlags = flags;
        mDisplayIntent = displayIntent;
        mPages = pages;
        mBackground = background;
        mContentIcon = contentIcon;
        mContentIconGravity = contentIconGravity;
        mContentActionIndex = contentActionIndex;
        mCustomSizePreset = customSizePreset;
        mCustomContentHeight = customContentHeight;
        mGravity = gravity;
    }

    private WearableNotificationExtensions(Parcel in) {
        mActions = in.createTypedArray(Notification.Action.CREATOR);
        mFlags = in.readInt();
        mDisplayIntent = in.readParcelable(PendingIntent.class.getClassLoader());
        mPages = in.createTypedArray(Notification.CREATOR);
        mBackground = in.readParcelable(Bitmap.class.getClassLoader());
        mContentIcon = in.readInt();
        mContentIconGravity = in.readInt();
        mContentActionIndex = in.readInt();
        mCustomSizePreset = in.readInt();
        mCustomContentHeight = in.readInt();
        mGravity = in.readInt();
    }

    /**
     * Create a {@link WearableNotificationExtensions} by reading wearable extensions present on an
     * existing notification.
     * @param notif the notification to inspect.
     * @return a new {@link WearableNotificationExtensions} object.
     */
    public static WearableNotificationExtensions from(Notification notif) {
        WearableNotificationExtensions extensions = notif.extras.getParcelable(
                EXTRA_WEARABLE_EXTENSIONS);
        if (extensions != null) {
            return extensions;
        } else {
            // Return a WearableNotificationExtensions with default values.
            return new Builder().build();
        }
    }

    /**
     * Apply wearable extensions to a notification that is being built. This is typically
     * called by {@link Notification.Builder#apply} method of {@link Notification.Builder}.
     */
    @Override
    public Notification.Builder applyTo(Notification.Builder builder) {
        builder.getExtras().putParcelable(EXTRA_WEARABLE_EXTENSIONS, this);
        return builder;
    }

    /**
     * Get the number of wearable actions present on this notification.
     *
     * @return the number of wearable actions for this notification
     */
    public int getActionCount() {
        return mActions.length;
    }

    /**
     * Get a {@link Notification.Action} for the wearable action at {@code actionIndex}.
     * @param actionIndex the index of the desired wearable action
     */
    public Notification.Action getAction(int actionIndex) {
        return mActions[actionIndex];
    }

    /**
     * Get the wearable actions present on this notification.
     */
    public Notification.Action[] getActions() {
        return mActions;
    }

    /**
     * Get the intent to launch inside of an activity view when displaying this
     * notification. This {@code PendingIntent} should be for an activity.
     */
    public PendingIntent getDisplayIntent() {
        return mDisplayIntent;
    }

    /**
     * Get the array of additional pages of content for displaying this notification. The
     * current notification forms the first page, and elements within this array form
     * subsequent pages. This field can be used to separate a notification into multiple
     * sections.
     * @return the pages for this notification
     */
    public Notification[] getPages() {
        return mPages;
    }

    /**
     * Get a background image to be displayed behind the notification content.
     * Contrary to the {@link Notification.BigPictureStyle}, this background
     * will work with any notification style.
     *
     * @return the background image
     * @see Builder#setBackground
     */
    public Bitmap getBackground() {
        return mBackground;
    }

    /**
     * Get an icon that goes with the content of this notification.
     */
    public int getContentIcon() {
        return mContentIcon;
    }

    /**
     * Get the gravity that the content icon should have within the notification display.
     * Supported values include {@link Gravity#START} and {@link Gravity#END}. The default
     * value is {@link android.view.Gravity#END}.
     * @see #getContentIcon
     */
    public int getContentIconGravity() {
        return mContentIconGravity;
    }

    /**
     * Get the action index of an action from this notification to show as clickable with
     * the content of this notification page. When the user clicks this notification page,
     * this action will trigger. This action will no longer display separately from the
     * notification content. The action's icon will display with optional subtext provided
     * by the action's title.
     *
     * <p>If wearable specific actions are present, this index will apply to that list,
     * otherwise it will apply to the main notification's actions list.
     */
    public int getContentAction() {
        return mContentActionIndex;
    }

    /**
     * Get the gravity that this notification should have within the available viewport space.
     * Supported values include {@link Gravity#TOP}, {@link Gravity#CENTER_VERTICAL} and
     * {@link android.view.Gravity#BOTTOM}. The default value is
     * {@link android.view.Gravity#BOTTOM}.
     */
    public int getGravity() {
        return mGravity;
    }

    /**
     * Get the custom size preset for the display of this notification out of the available
     * presets found in {@link WearableNotificationExtensions}, e.g. {@link #SIZE_LARGE}.
     * <p>Some custom size presets are only applicable for custom display notifications created
     * using {@link Builder#setDisplayIntent}. Check the documentation for the preset in question.
     * See also {@link Builder#setCustomContentHeight} and {@link Builder#setCustomSizePreset}.
     */
    public int getCustomSizePreset() {
        return mCustomSizePreset;
    }

    /**
     * Get the custom height in pixels for the display of this notification's content.
     * <p>This option is only available for custom display notifications created
     * using {@link Builder#setDisplayIntent}. See also {@link Builder#setCustomSizePreset} and
     * {@link Builder#setCustomContentHeight}.
     */
    public int getCustomContentHeight() {
        return mCustomContentHeight;
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
     * Get whether the content intent is available when the wearable device is not connected
     * to a companion device.  The user can still trigger this intent when the wearable device is
     * offline, but a visual hint will indicate that the content intent may not be available.
     * Defaults to true.
     */
    public boolean getContentIntentAvailableOffline() {
        return (mFlags & FLAG_CONTENT_INTENT_AVAILABLE_OFFLINE) != 0;
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
     * Get a visual hint that only the background image of this notification should be
     * displayed, and other semantic content should be hidden. This hint is only applicable
     * to sub-pages added using {@link Builder#addPage}.
     */
    public boolean getHintShowBackgroundOnly() {
        return (mFlags & FLAG_HINT_SHOW_BACKGROUND_ONLY) != 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedArray(mActions, flags);
        out.writeInt(mFlags);
        out.writeParcelable(mDisplayIntent, flags);
        out.writeTypedArray(mPages, flags);
        out.writeParcelable(mBackground, flags);
        out.writeInt(mContentIcon);
        out.writeInt(mContentIconGravity);
        out.writeInt(mContentActionIndex);
        out.writeInt(mCustomSizePreset);
        out.writeInt(mCustomContentHeight);
        out.writeInt(mGravity);
    }

    /**
     * Builder to apply wearable notification extensions to a {@link Notification.Builder}
     * object.
     *
     * <p>You can chain the "set" methods for this builder in any order,
     * but you must call the {@link #build} method and then the {@link Notification.Builder#apply}
     * method to apply your extensions to a notification.
     *
     * <pre class="prettyprint">
     * Notification notif = new Notification.Builder(mContext)
     *         .setContentTitle(&quot;New mail from &quot; + sender.toString())
     *         .setContentText(subject)
     *         .setSmallIcon(R.drawable.new_mail);
     *         .apply(new WearableNotificationExtensions.Builder()
     *                 .setContentIcon(R.drawable.new_mail)
     *                 .build())
     *         .build();
     * NotificationManager notificationManger =
     *         (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
     * notificationManager.notify(0, notif);</pre>
     */
    public static final class Builder {
        private final List<Notification.Action> mActions =
                new ArrayList<Notification.Action>();
        private int mFlags = DEFAULT_FLAGS;
        private PendingIntent mDisplayIntent;
        private final List<Notification> mPages = new ArrayList<Notification>();
        private Bitmap mBackground;
        private int mContentIcon;
        private int mContentIconGravity = Gravity.END;
        private int mContentActionIndex = UNSET_ACTION_INDEX;
        private int mCustomContentHeight;
        private int mCustomSizePreset = SIZE_DEFAULT;
        private int mGravity = Gravity.BOTTOM;

        /**
         * Construct a builder to be used for adding wearable extensions to notifications.
         *
         * <pre class="prettyprint">
         * Notification notif = new Notification.Builder(mContext)
         *         .setContentTitle(&quot;New mail from &quot; + sender.toString())
         *         .setContentText(subject)
         *         .setSmallIcon(R.drawable.new_mail);
         *         .apply(new WearableNotificationExtensions.Builder()
         *                 .setContentIcon(R.drawable.new_mail)
         *                 .build())
         *         .build();
         * NotificationManager notificationManger =
         *         (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
         * notificationManager.notify(0, notif);</pre>
         */
        public Builder() {
        }

        /**
         * Create a {@link Builder} by reading wearable extensions present on an
         * existing {@code WearableNotificationExtensions} object.
         * @param other the existing extensions to inspect.
         */
        public Builder(WearableNotificationExtensions other) {
            Collections.addAll(mActions, other.mActions);
            mFlags = other.mFlags;
            mDisplayIntent = other.mDisplayIntent;
            Collections.addAll(mPages, other.mPages);
            mBackground = other.mBackground;
            mContentIcon = other.mContentIcon;
            mContentIconGravity = other.mContentIconGravity;
            mContentActionIndex = other.mContentActionIndex;
            mCustomContentHeight = other.mCustomContentHeight;
            mCustomSizePreset = other.mCustomSizePreset;
            mGravity = other.mGravity;
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
         * @see Notification.Action
         */
        public Builder addAction(Notification.Action action) {
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
         * @see Notification.Action
         */
        public Builder addActions(List<Notification.Action> actions) {
            mActions.addAll(actions);
            return this;
        }

        /**
         * Clear all wearable actions present on this builder.
         * @return this object for method chaining.
         * @see #addAction
         */
        public Builder clearActions() {
            mActions.clear();
            return this;
        }

        /**
         * Set an intent to launch inside of an activity view when displaying
         * this notification. This {@link android.app.PendingIntent} should be for an activity.
         *
         * @param intent the {@link android.app.PendingIntent} for an activity
         * @return this object for method chaining
         * @see WearableNotificationExtensions#getDisplayIntent
         */
        public Builder setDisplayIntent(PendingIntent intent) {
            mDisplayIntent = intent;
            return this;
        }

        /**
         * Add an additional page of content to display with this notification. The current
         * notification forms the first page, and pages added using this function form
         * subsequent pages. This field can be used to separate a notification into multiple
         * sections.
         *
         * @param page the notification to add as another page
         * @return this object for method chaining
         * @see WearableNotificationExtensions#getPages
         */
        public Builder addPage(Notification page) {
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
         * @see WearableNotificationExtensions#getPages
         */
        public Builder addPages(List<Notification> pages) {
            mPages.addAll(pages);
            return this;
        }

        /**
         * Clear all additional pages present on this builder.
         * @return this object for method chaining.
         * @see #addPage
         */
        public Builder clearPages() {
            mPages.clear();
            return this;
        }

        /**
         * Set a background image to be displayed behind the notification content.
         * Contrary to the {@link Notification.BigPictureStyle}, this background
         * will work with any notification style.
         *
         * @param background the background bitmap
         * @return this object for method chaining
         * @see WearableNotificationExtensions#getBackground
         */
        public Builder setBackground(Bitmap background) {
            mBackground = background;
            return this;
        }

        /**
         * Set an icon that goes with the content of this notification.
         */
        public Builder setContentIcon(int icon) {
            mContentIcon = icon;
            return this;
        }

        /**
         * Set the gravity that the content icon should have within the notification display.
         * Supported values include {@link Gravity#START} and {@link Gravity#END}. The default
         * value is {@link android.view.Gravity#END}.
         * @see #setContentIcon
         */
        public Builder setContentIconGravity(int contentIconGravity) {
            mContentIconGravity = contentIconGravity;
            return this;
        }

        /**
         * Set an action from this notification's actions to be clickable with the content of
         * this notification page. This action will no longer display separately from the
         * notification content. This action's icon will display with optional subtext provided
         * by the action's title.
         * @param actionIndex The index of the action to hoist on the current notification page.
         *                    If wearable actions are present, this index will apply to that list,
         *                    otherwise it will apply to the main notification's actions list.
         */
        public Builder setContentAction(int actionIndex) {
            mContentActionIndex = actionIndex;
            return this;
        }

        /**
         * Set the gravity that this notification should have within the available viewport space.
         * Supported values include {@link Gravity#TOP}, {@link Gravity#CENTER_VERTICAL} and
         * {@link Gravity#BOTTOM}. The default value is {@link Gravity#BOTTOM}.
         */
        public Builder setGravity(int gravity) {
            mGravity = gravity;
            return this;
        }

        /**
         * Set the custom size preset for the display of this notification out of the available
         * presets found in {@link WearableNotificationExtensions}, e.g. {@link #SIZE_LARGE}.
         * <p>Some custom size presets are only applicable for custom display notifications created
         * using {@link Builder#setDisplayIntent}. Check the documentation for the preset in
         * question. See also {@link Builder#setCustomContentHeight} and
         * {@link #getCustomSizePreset}.
         */
        public Builder setCustomSizePreset(int sizePreset) {
            mCustomSizePreset = sizePreset;
            return this;
        }

        /**
         * Set the custom height in pixels for the display of this notification's content.
         * <p>This option is only available for custom display notifications created
         * using {@link Builder#setDisplayIntent}. See also {@link Builder#setCustomSizePreset} and
         * {@link #getCustomContentHeight}.
         */
        public Builder setCustomContentHeight(int height) {
            mCustomContentHeight = height;
            return this;
        }

        /**
         * Set whether the scrolling position for the contents of this notification should start
         * at the bottom of the contents instead of the top when the contents are too long to
         * display within the screen.  Default is false (start scroll at the top).
         */
        public Builder setStartScrollBottom(boolean startScrollBottom) {
            setFlag(FLAG_START_SCROLL_BOTTOM, startScrollBottom);
            return this;
        }

        /**
         * Set whether the content intent is available when the wearable device is not connected
         * to a companion device.  The user can still trigger this intent when the wearable device
         * is offline, but a visual hint will indicate that the content intent may not be available.
         * Defaults to true.
         */
        public Builder setContentIntentAvailableOffline(boolean contentIntentAvailableOffline) {
            setFlag(FLAG_CONTENT_INTENT_AVAILABLE_OFFLINE, contentIntentAvailableOffline);
            return this;
        }

        /**
         * Set a hint that this notification's icon should not be displayed.
         * @param hintHideIcon {@code true} to hide the icon, {@code false} otherwise.
         * @return this object for method chaining
         */
        public Builder setHintHideIcon(boolean hintHideIcon) {
            setFlag(FLAG_HINT_HIDE_ICON, hintHideIcon);
            return this;
        }

        /**
         * Set a visual hint that only the background image of this notification should be
         * displayed, and other semantic content should be hidden. This hint is only applicable
         * to sub-pages added using {@link #addPage}.
         */
        public Builder setHintShowBackgroundOnly(boolean hintShowBackgroundOnly) {
            setFlag(FLAG_HINT_SHOW_BACKGROUND_ONLY, hintShowBackgroundOnly);
            return this;
        }

        /**
         * Build a new {@link WearableNotificationExtensions} object with the extensions
         * currently present on this builder.
         * @return the extensions object.
         */
        public WearableNotificationExtensions build() {
            return new WearableNotificationExtensions(
                    mActions.toArray(new Notification.Action[mActions.size()]), mFlags,
                    mDisplayIntent, mPages.toArray(new Notification[mPages.size()]),
                    mBackground, mContentIcon, mContentIconGravity, mContentActionIndex,
                    mCustomSizePreset, mCustomContentHeight, mGravity);
        }

        private void setFlag(int mask, boolean value) {
            if (value) {
                mFlags |= mask;
            } else {
                mFlags &= ~mask;
            }
        }
    }

    public static final Creator<WearableNotificationExtensions> CREATOR =
            new Creator<WearableNotificationExtensions>() {
        @Override
        public WearableNotificationExtensions createFromParcel(Parcel in) {
            return new WearableNotificationExtensions(in);
        }

        @Override
        public WearableNotificationExtensions[] newArray(int size) {
            return new WearableNotificationExtensions[size];
        }
    };
}
