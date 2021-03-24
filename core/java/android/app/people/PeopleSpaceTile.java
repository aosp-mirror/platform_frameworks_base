/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.people;

import android.annotation.NonNull;
import android.app.Person;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * The People Space tile contains all relevant information to render a tile in People Space: namely
 * the data of any visible conversation notification associated, associated statuses, and the last
 * interaction time.
 *
 * @hide
 */
public class PeopleSpaceTile implements Parcelable {

    private String mId;
    private CharSequence mUserName;
    private Icon mUserIcon;
    private UserHandle mUserHandle;
    private Uri mContactUri;
    private String mPackageName;
    private String mBirthdayText;
    private long mLastInteractionTimestamp;
    private boolean mIsImportantConversation;
    private String mNotificationKey;
    private CharSequence mNotificationContent;
    private String mNotificationCategory;
    private Uri mNotificationDataUri;
    private int mMessagesCount;
    private Intent mIntent;
    private long mNotificationTimestamp;
    private List<ConversationStatus> mStatuses;

    private PeopleSpaceTile(Builder b) {
        mId = b.mId;
        mUserName = b.mUserName;
        mUserIcon = b.mUserIcon;
        mContactUri = b.mContactUri;
        mUserHandle = b.mUserHandle;
        mPackageName = b.mPackageName;
        mBirthdayText = b.mBirthdayText;
        mLastInteractionTimestamp = b.mLastInteractionTimestamp;
        mIsImportantConversation = b.mIsImportantConversation;
        mNotificationKey = b.mNotificationKey;
        mNotificationContent = b.mNotificationContent;
        mNotificationCategory = b.mNotificationCategory;
        mNotificationDataUri = b.mNotificationDataUri;
        mMessagesCount = b.mMessagesCount;
        mIntent = b.mIntent;
        mNotificationTimestamp = b.mNotificationTimestamp;
        mStatuses = b.mStatuses;
    }

    public String getId() {
        return mId;
    }

    public CharSequence getUserName() {
        return mUserName;
    }

    public Icon getUserIcon() {
        return mUserIcon;
    }

    /** Returns the Uri associated with the user in Android Contacts database. */
    public Uri getContactUri() {
        return mContactUri;
    }

    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public String getBirthdayText() {
        return mBirthdayText;
    }

    /** Returns the timestamp of the last interaction. */
    public long getLastInteractionTimestamp() {
        return mLastInteractionTimestamp;
    }

    /**
     * Whether the conversation is important.
     */
    public boolean isImportantConversation() {
        return mIsImportantConversation;
    }

    /**
     * If a notification is currently active that maps to the relevant shortcut ID, provides the
     * associated notification's key.
     */
    public String getNotificationKey() {
        return mNotificationKey;
    }

    public CharSequence getNotificationContent() {
        return mNotificationContent;
    }

    public String getNotificationCategory() {
        return mNotificationCategory;
    }

    public Uri getNotificationDataUri() {
        return mNotificationDataUri;
    }

    public int getMessagesCount() {
        return mMessagesCount;
    }

    /**
     * Provides an intent to launch. If present, we should manually launch the intent on tile
     * click, rather than calling {@link android.content.pm.LauncherApps} to launch the shortcut ID.
     *
     * <p>This field should only be used if manually constructing a tile without an associated
     * shortcut to launch (i.e. birthday tiles).
     */
    public Intent getIntent() {
        return mIntent;
    }

    /** Returns the timestamp of the last notification. */
    public long getNotificationTimestamp() {
        return mNotificationTimestamp;
    }

    /** Returns the statuses associated with the tile. */
    public List<ConversationStatus> getStatuses() {
        return mStatuses;
    }

    /** Converts a {@link PeopleSpaceTile} into a {@link PeopleSpaceTile.Builder}. */
    public Builder toBuilder() {
        Builder builder =
                new Builder(mId, mUserName.toString(), mUserIcon, mIntent);
        builder.setContactUri(mContactUri);
        builder.setUserHandle(mUserHandle);
        builder.setPackageName(mPackageName);
        builder.setBirthdayText(mBirthdayText);
        builder.setLastInteractionTimestamp(mLastInteractionTimestamp);
        builder.setIsImportantConversation(mIsImportantConversation);
        builder.setNotificationKey(mNotificationKey);
        builder.setNotificationContent(mNotificationContent);
        builder.setNotificationCategory(mNotificationCategory);
        builder.setNotificationDataUri(mNotificationDataUri);
        builder.setMessagesCount(mMessagesCount);
        builder.setIntent(mIntent);
        builder.setNotificationTimestamp(mNotificationTimestamp);
        builder.setStatuses(mStatuses);
        return builder;
    }

    /** Builder to create a {@link PeopleSpaceTile}. */
    public static class Builder {
        private String mId;
        private CharSequence mUserName;
        private Icon mUserIcon;
        private Uri mContactUri;
        private UserHandle mUserHandle;
        private String mPackageName;
        private String mBirthdayText;
        private long mLastInteractionTimestamp;
        private boolean mIsImportantConversation;
        private String mNotificationKey;
        private CharSequence mNotificationContent;
        private String mNotificationCategory;
        private Uri mNotificationDataUri;
        private int mMessagesCount;
        private Intent mIntent;
        private long mNotificationTimestamp;
        private List<ConversationStatus> mStatuses;

        /** Builder for use only if a shortcut is not available for the tile. */
        public Builder(String id, String userName, Icon userIcon, Intent intent) {
            mId = id;
            mUserName = userName;
            mUserIcon = userIcon;
            mIntent = intent;
            mPackageName = intent == null ? null : intent.getPackage();
        }

        public Builder(ShortcutInfo info, LauncherApps launcherApps) {
            mId = info.getId();
            mUserName = info.getLabel();
            mUserIcon = convertDrawableToIcon(launcherApps.getShortcutIconDrawable(info, 0));
            mUserHandle = info.getUserHandle();
            mPackageName = info.getPackage();
            mContactUri = getContactUri(info);
        }

        public Builder(ConversationChannel channel, LauncherApps launcherApps) {
            ShortcutInfo info = channel.getShortcutInfo();
            mId = info.getId();
            mUserName = info.getLabel();
            mUserIcon = convertDrawableToIcon(launcherApps.getShortcutIconDrawable(info, 0));
            mUserHandle = info.getUserHandle();
            mPackageName = info.getPackage();
            mContactUri = getContactUri(info);
            mStatuses = channel.getStatuses();
            mLastInteractionTimestamp = channel.getLastEventTimestamp();
            mIsImportantConversation = channel.getParentNotificationChannel() != null
                    && channel.getParentNotificationChannel().isImportantConversation();
        }

        /** Returns the Contact's Uri if present. */
        public Uri getContactUri(ShortcutInfo info) {
            if (info.getPersons() == null || info.getPersons().length != 1) {
                return null;
            }
            // TODO(b/175584929): Update to use the Uri from PeopleService directly
            Person person = info.getPersons()[0];
            return person.getUri() == null ? null : Uri.parse(person.getUri());
        }

        /** Sets the ID for the tile. */
        public Builder setId(String id) {
            mId = id;
            return this;
        }

        /** Sets the user name. */
        public Builder setUserName(CharSequence userName) {
            mUserName = userName;
            return this;
        }

        /** Sets the icon shown for the user. */
        public Builder setUserIcon(Icon userIcon) {
            mUserIcon = userIcon;
            return this;
        }

        /** Sets the Uri associated with the user in Android Contacts database. */
        public Builder setContactUri(Uri uri) {
            mContactUri = uri;
            return this;
        }

        /** Sets the associated {@code userHandle}. */
        public Builder setUserHandle(UserHandle userHandle) {
            mUserHandle = userHandle;
            return this;
        }

        /** Sets the package shown that provided the information. */
        public Builder setPackageName(String packageName) {
            mPackageName = packageName;
            return this;
        }

        /** Sets the status text. */
        public Builder setBirthdayText(String birthdayText) {
            mBirthdayText = birthdayText;
            return this;
        }

        /** Sets the last interaction timestamp. */
        public Builder setLastInteractionTimestamp(long lastInteractionTimestamp) {
            mLastInteractionTimestamp = lastInteractionTimestamp;
            return this;
        }

        /** Sets whether the conversation is important. */
        public Builder setIsImportantConversation(boolean isImportantConversation) {
            mIsImportantConversation = isImportantConversation;
            return this;
        }

        /** Sets the associated notification's key. */
        public Builder setNotificationKey(String notificationKey) {
            mNotificationKey = notificationKey;
            return this;
        }

        /** Sets the associated notification's content. */
        public Builder setNotificationContent(CharSequence notificationContent) {
            mNotificationContent = notificationContent;
            return this;
        }

        /** Sets the associated notification's category. */
        public Builder setNotificationCategory(String notificationCategory) {
            mNotificationCategory = notificationCategory;
            return this;
        }

        /** Sets the associated notification's data URI. */
        public Builder setNotificationDataUri(Uri notificationDataUri) {
            mNotificationDataUri = notificationDataUri;
            return this;
        }

        /** Sets the number of messages associated with the Tile. */
        public Builder setMessagesCount(int messagesCount) {
            mMessagesCount = messagesCount;
            return this;
        }

        /** Sets an intent to launch on click. */
        public Builder setIntent(Intent intent) {
            mIntent = intent;
            return this;
        }

        /** Sets the notification timestamp. */
        public Builder setNotificationTimestamp(long notificationTimestamp) {
            mNotificationTimestamp = notificationTimestamp;
            return this;
        }

        /** Sets the statuses. */
        public Builder setStatuses(List<ConversationStatus> statuses) {
            mStatuses = statuses;
            return this;
        }

        /** Builds a {@link PeopleSpaceTile}. */
        @NonNull
        public PeopleSpaceTile build() {
            return new PeopleSpaceTile(this);
        }
    }

    public PeopleSpaceTile(Parcel in) {
        mId = in.readString();
        mUserName = in.readCharSequence();
        mUserIcon = in.readParcelable(Icon.class.getClassLoader());
        mContactUri = in.readParcelable(Uri.class.getClassLoader());
        mUserHandle = in.readParcelable(UserHandle.class.getClassLoader());
        mPackageName = in.readString();
        mBirthdayText = in.readString();
        mLastInteractionTimestamp = in.readLong();
        mIsImportantConversation = in.readBoolean();
        mNotificationKey = in.readString();
        mNotificationContent = in.readCharSequence();
        mNotificationCategory = in.readString();
        mNotificationDataUri = in.readParcelable(Uri.class.getClassLoader());
        mMessagesCount = in.readInt();
        mIntent = in.readParcelable(Intent.class.getClassLoader());
        mNotificationTimestamp = in.readLong();
        mStatuses = new ArrayList<>();
        in.readParcelableList(mStatuses, ConversationStatus.class.getClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeCharSequence(mUserName);
        dest.writeParcelable(mUserIcon, flags);
        dest.writeParcelable(mContactUri, flags);
        dest.writeParcelable(mUserHandle, flags);
        dest.writeString(mPackageName);
        dest.writeString(mBirthdayText);
        dest.writeLong(mLastInteractionTimestamp);
        dest.writeBoolean(mIsImportantConversation);
        dest.writeString(mNotificationKey);
        dest.writeCharSequence(mNotificationContent);
        dest.writeString(mNotificationCategory);
        dest.writeParcelable(mNotificationDataUri, flags);
        dest.writeInt(mMessagesCount);
        dest.writeParcelable(mIntent, flags);
        dest.writeLong(mNotificationTimestamp);
        dest.writeParcelableList(mStatuses, flags);
    }

    public static final @android.annotation.NonNull
            Creator<PeopleSpaceTile> CREATOR = new Creator<PeopleSpaceTile>() {
                public PeopleSpaceTile createFromParcel(Parcel source) {
                    return new PeopleSpaceTile(source);
                }

                public PeopleSpaceTile[] newArray(int size) {
                    return new PeopleSpaceTile[size];
                }
            };

    /** Converts {@code drawable} to a {@link Icon}. */
    public static Icon convertDrawableToIcon(Drawable drawable) {
        if (drawable == null) {
            return null;
        }

        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return Icon.createWithBitmap(bitmapDrawable.getBitmap());
            }
        }

        Bitmap bitmap;
        if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return Icon.createWithBitmap(bitmap);
    }
}
