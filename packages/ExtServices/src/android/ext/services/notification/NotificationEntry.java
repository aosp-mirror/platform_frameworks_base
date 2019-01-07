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
package android.ext.services.notification;

import static android.app.Notification.CATEGORY_MESSAGE;
import static android.app.NotificationChannel.USER_LOCKED_IMPORTANCE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Person;
import android.app.RemoteInput;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.os.Build;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Holds data about notifications.
 */
public class NotificationEntry {
    static final String TAG = "NotificationEntry";

    private StatusBarNotification mSbn;
    private final IPackageManager mPackageManager;
    private int mTargetSdkVersion = Build.VERSION_CODES.N_MR1;
    private boolean mPreChannelsNotification = true;
    private AudioAttributes mAttributes;
    private NotificationChannel mChannel;
    private int mImportance;
    private boolean mSeen;
    private boolean mExpanded;
    private boolean mIsShowActionEventLogged;

    public NotificationEntry(IPackageManager packageManager, StatusBarNotification sbn,
            NotificationChannel channel) {
        mSbn = sbn;
        mChannel = channel;
        mPackageManager = packageManager;
        mPreChannelsNotification = isPreChannelsNotification();
        mAttributes = calculateAudioAttributes();
        mImportance = calculateInitialImportance();
    }

    private boolean isPreChannelsNotification() {
        try {
            ApplicationInfo info = mPackageManager.getApplicationInfo(
                    mSbn.getPackageName(), PackageManager.MATCH_ALL,
                    mSbn.getUserId());
            if (info != null) {
                mTargetSdkVersion = info.targetSdkVersion;
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Couldn't look up " + mSbn.getPackageName());
        }
        if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(getChannel().getId())) {
            if (mTargetSdkVersion < Build.VERSION_CODES.O) {
                return true;
            }
        }
        return false;
    }

    private AudioAttributes calculateAudioAttributes() {
        final Notification n = getNotification();
        AudioAttributes attributes = getChannel().getAudioAttributes();
        if (attributes == null) {
            attributes = Notification.AUDIO_ATTRIBUTES_DEFAULT;
        }

        if (mPreChannelsNotification
                && (getChannel().getUserLockedFields()
                & NotificationChannel.USER_LOCKED_SOUND) == 0) {
            if (n.audioAttributes != null) {
                // prefer audio attributes to stream type
                attributes = n.audioAttributes;
            } else if (n.audioStreamType >= 0
                    && n.audioStreamType < AudioSystem.getNumStreamTypes()) {
                // the stream type is valid, use it
                attributes = new AudioAttributes.Builder()
                        .setInternalLegacyStreamType(n.audioStreamType)
                        .build();
            } else if (n.audioStreamType != AudioSystem.STREAM_DEFAULT) {
                Log.w(TAG, String.format("Invalid stream type: %d", n.audioStreamType));
            }
        }
        return attributes;
    }

    private int calculateInitialImportance() {
        final Notification n = getNotification();
        int importance = getChannel().getImportance();
        int requestedImportance = IMPORTANCE_DEFAULT;

        // Migrate notification flags to scores
        if ((n.flags & Notification.FLAG_HIGH_PRIORITY) != 0) {
            n.priority = Notification.PRIORITY_MAX;
        }

        n.priority = clamp(n.priority, Notification.PRIORITY_MIN,
                Notification.PRIORITY_MAX);
        switch (n.priority) {
            case Notification.PRIORITY_MIN:
                requestedImportance = IMPORTANCE_MIN;
                break;
            case Notification.PRIORITY_LOW:
                requestedImportance = IMPORTANCE_LOW;
                break;
            case Notification.PRIORITY_DEFAULT:
                requestedImportance = IMPORTANCE_DEFAULT;
                break;
            case Notification.PRIORITY_HIGH:
            case Notification.PRIORITY_MAX:
                requestedImportance = IMPORTANCE_HIGH;
                break;
        }

        if (mPreChannelsNotification
                && (importance == IMPORTANCE_UNSPECIFIED
                || (getChannel().getUserLockedFields()
                & USER_LOCKED_IMPORTANCE) == 0)) {
            if (n.fullScreenIntent != null) {
                requestedImportance = IMPORTANCE_HIGH;
            }
            importance = requestedImportance;
        }

        return importance;
    }

    public boolean isCategory(String category) {
        return Objects.equals(getNotification().category, category);
    }

    /**
     * Similar to {@link #isCategory(String)}, but checking the public version of the notification,
     * if available.
     */
    public boolean isPublicVersionCategory(String category) {
        Notification publicVersion = getNotification().publicVersion;
        if (publicVersion == null) {
            return false;
        }
        return Objects.equals(publicVersion.category, category);
    }

    public boolean isAudioAttributesUsage(int usage) {
        return mAttributes != null && mAttributes.getUsage() == usage;
    }

    private boolean hasPerson() {
        // TODO: cache favorite and recent contacts to check contact affinity
        ArrayList<Person> people = getNotification().extras.getParcelableArrayList(
                Notification.EXTRA_PEOPLE_LIST);
        return people != null && !people.isEmpty();
    }

    protected boolean hasStyle(Class targetStyle) {
        Class<? extends Notification.Style> style = getNotification().getNotificationStyle();
        return targetStyle.equals(style);
    }

    protected boolean isOngoing() {
        return (getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
    }

    protected boolean involvesPeople() {
        return isMessaging()
                || hasStyle(Notification.InboxStyle.class)
                || hasPerson();
    }

    protected boolean isMessaging() {
        return isCategory(CATEGORY_MESSAGE)
                || isPublicVersionCategory(CATEGORY_MESSAGE)
                || hasStyle(Notification.MessagingStyle.class);
    }

    public boolean hasInlineReply() {
        Notification.Action[] actions = getNotification().actions;
        if (actions == null) {
            return false;
        }
        for (Notification.Action action : actions) {
            RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs == null) {
                continue;
            }
            for (RemoteInput remoteInput : remoteInputs) {
                if (remoteInput.getAllowFreeFormInput()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void setSeen() {
        mSeen = true;
    }

    public void setExpanded(boolean expanded) {
        mExpanded = expanded;
    }

    public void setShowActionEventLogged() {
        mIsShowActionEventLogged = true;
    }

    public boolean hasSeen() {
        return mSeen;
    }

    public boolean isShowActionEventLogged() {
        return mIsShowActionEventLogged;
    }

    public StatusBarNotification getSbn() {
        return mSbn;
    }

    public Notification getNotification() {
        return getSbn().getNotification();
    }

    public NotificationChannel getChannel() {
        return mChannel;
    }

    public int getImportance() {
        return mImportance;
    }

    private int clamp(int x, int low, int high) {
        return (x < low) ? low : ((x > high) ? high : x);
    }
}
