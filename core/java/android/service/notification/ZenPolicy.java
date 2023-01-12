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

package android.service.notification;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.proto.ProtoOutputStream;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;

/**
 * ZenPolicy determines whether to allow certain notifications and their corresponding sounds to
 * play when a device is in Do Not Disturb mode.
 * ZenPolicy also dictates the visual effects of notifications that are intercepted when
 * a device is in Do Not Disturb mode.
 */
public final class ZenPolicy implements Parcelable {
    private ArrayList<Integer> mPriorityCategories;
    private ArrayList<Integer> mVisualEffects;
    private @PeopleType int mPriorityMessages = PEOPLE_TYPE_UNSET;
    private @PeopleType int mPriorityCalls = PEOPLE_TYPE_UNSET;
    private @ConversationSenders int mConversationSenders = CONVERSATION_SENDERS_UNSET;

    /** @hide */
    @IntDef(prefix = { "PRIORITY_CATEGORY_" }, value = {
            PRIORITY_CATEGORY_REMINDERS,
            PRIORITY_CATEGORY_EVENTS,
            PRIORITY_CATEGORY_MESSAGES,
            PRIORITY_CATEGORY_CALLS,
            PRIORITY_CATEGORY_REPEAT_CALLERS,
            PRIORITY_CATEGORY_ALARMS,
            PRIORITY_CATEGORY_MEDIA,
            PRIORITY_CATEGORY_SYSTEM,
            PRIORITY_CATEGORY_CONVERSATIONS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PriorityCategory {}

    /** @hide */
    public static final int PRIORITY_CATEGORY_REMINDERS = 0;
    /** @hide */
    public static final int PRIORITY_CATEGORY_EVENTS = 1;
    /** @hide */
    public static final int PRIORITY_CATEGORY_MESSAGES = 2;
    /** @hide */
    public static final int PRIORITY_CATEGORY_CALLS = 3;
    /** @hide */
    public static final int PRIORITY_CATEGORY_REPEAT_CALLERS = 4;
    /** @hide */
    public static final int PRIORITY_CATEGORY_ALARMS = 5;
    /** @hide */
    public static final int PRIORITY_CATEGORY_MEDIA = 6;
    /** @hide */
    public static final int PRIORITY_CATEGORY_SYSTEM = 7;
    /** @hide */
    public static final int PRIORITY_CATEGORY_CONVERSATIONS = 8;

    /**
     * Total number of priority categories. Keep updated with any updates to PriorityCategory enum.
     * @hide
     */
    public static final int NUM_PRIORITY_CATEGORIES = 9;

    /** @hide */
    @IntDef(prefix = { "VISUAL_EFFECT_" }, value = {
            VISUAL_EFFECT_FULL_SCREEN_INTENT,
            VISUAL_EFFECT_LIGHTS,
            VISUAL_EFFECT_PEEK,
            VISUAL_EFFECT_STATUS_BAR,
            VISUAL_EFFECT_BADGE,
            VISUAL_EFFECT_AMBIENT,
            VISUAL_EFFECT_NOTIFICATION_LIST,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VisualEffect {}

    /** @hide */
    public static final int VISUAL_EFFECT_FULL_SCREEN_INTENT = 0;
    /** @hide */
    public static final int VISUAL_EFFECT_LIGHTS = 1;
    /** @hide */
    public static final int VISUAL_EFFECT_PEEK = 2;
    /** @hide */
    public static final int VISUAL_EFFECT_STATUS_BAR = 3;
    /** @hide */
    public static final int VISUAL_EFFECT_BADGE = 4;
    /** @hide */
    public static final int VISUAL_EFFECT_AMBIENT = 5;
    /** @hide */
    public static final int VISUAL_EFFECT_NOTIFICATION_LIST = 6;

    /**
     * Total number of visual effects. Keep updated with any updates to VisualEffect enum.
     * @hide
     */
    public static final int NUM_VISUAL_EFFECTS = 7;

    /** @hide */
    @IntDef(prefix = { "PEOPLE_TYPE_" }, value = {
            PEOPLE_TYPE_UNSET,
            PEOPLE_TYPE_ANYONE,
            PEOPLE_TYPE_CONTACTS,
            PEOPLE_TYPE_STARRED,
            PEOPLE_TYPE_NONE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PeopleType {}

    /**
     * Used to indicate no preference for the type of people that can bypass dnd for either
     * calls or messages.
     */
    public static final int PEOPLE_TYPE_UNSET = 0;

    /**
     * Used to indicate all calls or messages can bypass dnd.
     */
    public static final int PEOPLE_TYPE_ANYONE = 1;

    /**
     * Used to indicate calls or messages from contacts can bypass dnd.
     */
    public static final int PEOPLE_TYPE_CONTACTS = 2;

    /**
     * Used to indicate calls or messages from starred contacts can bypass dnd.
     */
    public static final int PEOPLE_TYPE_STARRED = 3;

    /**
     * Used to indicate no calls or messages can bypass dnd.
     */
    public static final int PEOPLE_TYPE_NONE = 4;


    /** @hide */
    @IntDef(prefix = { "CONVERSATION_SENDERS_" }, value = {
            CONVERSATION_SENDERS_UNSET,
            CONVERSATION_SENDERS_ANYONE,
            CONVERSATION_SENDERS_IMPORTANT,
            CONVERSATION_SENDERS_NONE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConversationSenders {}

    /**
     * Used to indicate no preference for the type of conversations that can bypass dnd.
     */
    public static final int CONVERSATION_SENDERS_UNSET = 0;

    /**
     * Used to indicate all conversations can bypass dnd.
     */
    public static final int CONVERSATION_SENDERS_ANYONE = 1;

    /**
     * Used to indicate important conversations can bypass dnd.
     */
    public static final int CONVERSATION_SENDERS_IMPORTANT = 2;

    /**
     * Used to indicate no conversations can bypass dnd.
     */
    public static final int CONVERSATION_SENDERS_NONE = 3;

    /** @hide */
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_UNSET,
            STATE_ALLOW,
            STATE_DISALLOW,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * Indicates no preference for whether a type of sound or visual effect is or isn't allowed
     * to play/show when DND is active.  Will default to the current set policy.
     */
    public static final int STATE_UNSET = 0;

    /**
     * Indicates a type of sound or visual effect is allowed to play/show when DND is active.
     */
    public static final int STATE_ALLOW = 1;

    /**
     * Indicates a type of sound or visual effect is not allowed to play/show when DND is active.
     */
    public static final int STATE_DISALLOW = 2;

    /** @hide */
    public ZenPolicy() {
        mPriorityCategories = new ArrayList<>(Collections.nCopies(NUM_PRIORITY_CATEGORIES, 0));
        mVisualEffects = new ArrayList<>(Collections.nCopies(NUM_VISUAL_EFFECTS, 0));
    }

    /**
     * Conversation type that can bypass DND.
     * @return {@link #CONVERSATION_SENDERS_UNSET}, {@link #CONVERSATION_SENDERS_ANYONE},
     * {@link #CONVERSATION_SENDERS_IMPORTANT}, {@link #CONVERSATION_SENDERS_NONE}.
     */
    public @PeopleType int getPriorityConversationSenders() {
        return mConversationSenders;
    }

    /**
     * Message senders that can bypass DND.
     * @return {@link #PEOPLE_TYPE_UNSET}, {@link #PEOPLE_TYPE_ANYONE},
     * {@link #PEOPLE_TYPE_CONTACTS}, {@link #PEOPLE_TYPE_STARRED} or {@link #PEOPLE_TYPE_NONE}
     */
    public @PeopleType int getPriorityMessageSenders() {
        return mPriorityMessages;
    }

    /**
     * Callers that can bypass DND.
     * @return {@link #PEOPLE_TYPE_UNSET}, {@link #PEOPLE_TYPE_ANYONE},
     * {@link #PEOPLE_TYPE_CONTACTS}, {@link #PEOPLE_TYPE_STARRED} or {@link #PEOPLE_TYPE_NONE}
     */
    public @PeopleType int getPriorityCallSenders() {
        return mPriorityCalls;
    }

    /**
     * Whether this policy wants to allow conversation notifications
     * (see {@link NotificationChannel#getConversationId()}) to play sounds and visually appear
     * or to intercept them when DND is active.
     * @return {@link #STATE_UNSET}, {@link #STATE_ALLOW} or {@link #STATE_DISALLOW}
     */
    public @State int getPriorityCategoryConversations() {
        return mPriorityCategories.get(PRIORITY_CATEGORY_CONVERSATIONS);
    }

    /**
     * Whether this policy wants to allow notifications with category
     * {@link Notification#CATEGORY_REMINDER} to play sounds and visually appear
     * or to intercept them when DND is active.
     * @return {@link #STATE_UNSET}, {@link #STATE_ALLOW} or {@link #STATE_DISALLOW}
     */
    public @State int getPriorityCategoryReminders() {
        return mPriorityCategories.get(PRIORITY_CATEGORY_REMINDERS);
    }

    /**
     * Whether this policy wants to allow notifications with category
     * {@link Notification#CATEGORY_EVENT} to play sounds and visually appear
     * or to intercept them when DND is active.
     * @return {@link #STATE_UNSET}, {@link #STATE_ALLOW} or {@link #STATE_DISALLOW}
     */
    public @State int getPriorityCategoryEvents() {
        return mPriorityCategories.get(PRIORITY_CATEGORY_EVENTS);
    }

    /**
     * Whether this policy wants to allow notifications with category
     * {@link Notification#CATEGORY_MESSAGE} to play sounds and visually appear
     * or to intercept them when DND is active.  Types of message senders that are allowed
     * are specified by {@link #getPriorityMessageSenders}.
     * @return {@link #STATE_UNSET}, {@link #STATE_ALLOW} or {@link #STATE_DISALLOW}
     */
    public @State int getPriorityCategoryMessages() {
        return mPriorityCategories.get(PRIORITY_CATEGORY_MESSAGES);
    }

    /**
     * Whether this policy wants to allow notifications with category
     * {@link Notification#CATEGORY_CALL} to play sounds and visually appear
     * or to intercept them when DND is active.  Types of callers that are allowed
     * are specified by {@link #getPriorityCallSenders()}.
     * @return {@link #STATE_UNSET}, {@link #STATE_ALLOW} or {@link #STATE_DISALLOW}
     */
    public @State int getPriorityCategoryCalls() {
        return mPriorityCategories.get(PRIORITY_CATEGORY_CALLS);
    }

    /**
     * Whether this policy wants to allow repeat callers (notifications with category
     * {@link Notification#CATEGORY_CALL} that have recently called) to play sounds and
     * visually appear or to intercept them when DND is active.
     * @return {@link #STATE_UNSET}, {@link #STATE_ALLOW} or {@link #STATE_DISALLOW}
     */
    public @State int getPriorityCategoryRepeatCallers() {
        return mPriorityCategories.get(PRIORITY_CATEGORY_REPEAT_CALLERS);
    }

    /**
     * Whether this policy wants to allow notifications with category
     * {@link Notification#CATEGORY_ALARM} to play sounds and visually appear
     * or to intercept them when DND is active.
     * When alarms are {@link #STATE_DISALLOW disallowed}, the alarm stream will be muted when DND
     * is active.
     * @return {@link #STATE_UNSET}, {@link #STATE_ALLOW} or {@link #STATE_DISALLOW}
     */
    public @State int getPriorityCategoryAlarms() {
        return mPriorityCategories.get(PRIORITY_CATEGORY_ALARMS);
    }

    /**
     * Whether this policy wants to allow media notifications to play sounds and visually appear
     * or to intercept them when DND is active.
     * When media is {@link #STATE_DISALLOW disallowed}, the media stream will be muted when DND is
     * active.
     * @return {@link #STATE_UNSET}, {@link #STATE_ALLOW} or {@link #STATE_DISALLOW}
     */
    public @State int getPriorityCategoryMedia() {
        return mPriorityCategories.get(PRIORITY_CATEGORY_MEDIA);
    }

    /**
     * Whether this policy wants to allow system sounds when DND is active.
     * When system is {@link #STATE_DISALLOW}, the system stream will be muted when DND is active.
     * @return {@link #STATE_UNSET}, {@link #STATE_ALLOW} or {@link #STATE_DISALLOW}
     */
    public @State int getPriorityCategorySystem() {
        return mPriorityCategories.get(PRIORITY_CATEGORY_SYSTEM);
    }

    /**
     * Whether this policy allows {@link Notification#fullScreenIntent full screen intents} from
     * notifications intercepted by DND.
     */
    public @State int getVisualEffectFullScreenIntent() {
        return mVisualEffects.get(VISUAL_EFFECT_FULL_SCREEN_INTENT);
    }

    /**
     * Whether this policy allows {@link NotificationChannel#shouldShowLights() notification
     * lights} from notifications intercepted by DND.
     */
    public @State int getVisualEffectLights() {
        return mVisualEffects.get(VISUAL_EFFECT_LIGHTS);
    }

    /**
     * Whether this policy allows peeking from notifications intercepted by DND.
     */
    public @State int getVisualEffectPeek() {
        return mVisualEffects.get(VISUAL_EFFECT_PEEK);
    }

    /**
     * Whether this policy allows notifications intercepted by DND from appearing in the status bar
     * on devices that support status bars.
     */
    public @State int getVisualEffectStatusBar() {
        return mVisualEffects.get(VISUAL_EFFECT_STATUS_BAR);
    }

    /**
     * Whether this policy allows {@link NotificationChannel#canShowBadge() badges} from
     * notifications intercepted by DND on devices that support badging.
     */
    public @State int getVisualEffectBadge() {
        return mVisualEffects.get(VISUAL_EFFECT_BADGE);
    }

    /**
     * Whether this policy allows notifications intercepted by DND from appearing on ambient
     * displays on devices that support ambient display.
     */
    public @State int getVisualEffectAmbient() {
        return mVisualEffects.get(VISUAL_EFFECT_AMBIENT);
    }

    /**
     * Whether this policy allows notifications intercepted by DND from appearing in notification
     * list views like the notification shade or lockscreen on devices that support those
     * views.
     */
    public @State int getVisualEffectNotificationList() {
        return mVisualEffects.get(VISUAL_EFFECT_NOTIFICATION_LIST);
    }

    /**
     * Whether this policy hides all visual effects
     * @hide
     */
    public boolean shouldHideAllVisualEffects() {
        for (int i = 0; i < mVisualEffects.size(); i++) {
            if (mVisualEffects.get(i) != STATE_DISALLOW) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this policy shows all visual effects
     * @hide
     */
    public boolean shouldShowAllVisualEffects() {
        for (int i = 0; i < mVisualEffects.size(); i++) {
            if (mVisualEffects.get(i) != STATE_ALLOW) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builder class for {@link ZenPolicy} objects.
     * Provides a convenient way to set the various fields of a {@link ZenPolicy}.  If a field
     * is not set, it is (@link STATE_UNSET} and will not change the current set policy.
     */
    public static final class Builder {
        private ZenPolicy mZenPolicy;

        public Builder() {
            mZenPolicy = new ZenPolicy();
        }

        /**
         * @hide
         */
        public Builder(ZenPolicy policy) {
            if (policy != null) {
                mZenPolicy = policy.copy();
            } else {
                mZenPolicy = new ZenPolicy();
            }
        }

        /**
         * Builds the current ZenPolicy.
         */
        public @NonNull ZenPolicy build() {
            return mZenPolicy.copy();
        }

        /**
         * Allows all notifications to bypass DND and unmutes all streams.
         */
        public @NonNull Builder allowAllSounds() {
            for (int i = 0; i < mZenPolicy.mPriorityCategories.size(); i++) {
                mZenPolicy.mPriorityCategories.set(i, STATE_ALLOW);
            }
            mZenPolicy.mPriorityMessages = PEOPLE_TYPE_ANYONE;
            mZenPolicy.mPriorityCalls = PEOPLE_TYPE_ANYONE;
            mZenPolicy.mConversationSenders = CONVERSATION_SENDERS_ANYONE;
            return this;
        }

        /**
         * Intercepts all notifications and prevents them from playing sounds
         * when DND is active. Also mutes alarm, system and media streams.
         * Notification channels can still play sounds only if they
         * {@link NotificationChannel#canBypassDnd can bypass DND}. If no channels can bypass DND,
         * the ringer stream is also muted.
         */
        public @NonNull Builder disallowAllSounds() {
            for (int i = 0; i < mZenPolicy.mPriorityCategories.size(); i++) {
                mZenPolicy.mPriorityCategories.set(i, STATE_DISALLOW);
            }
            mZenPolicy.mPriorityMessages = PEOPLE_TYPE_NONE;
            mZenPolicy.mPriorityCalls = PEOPLE_TYPE_NONE;
            mZenPolicy.mConversationSenders = CONVERSATION_SENDERS_NONE;
            return this;
        }

        /**
         * Allows notifications intercepted by DND to show on all surfaces when DND is active.
         */
        public @NonNull Builder showAllVisualEffects() {
            for (int i = 0; i < mZenPolicy.mVisualEffects.size(); i++) {
                mZenPolicy.mVisualEffects.set(i, STATE_ALLOW);
            }
            return this;
        }

        /**
         * Disallows notifications intercepted by DND from showing when DND is active.
         */
        public @NonNull Builder hideAllVisualEffects() {
            for (int i = 0; i < mZenPolicy.mVisualEffects.size(); i++) {
                mZenPolicy.mVisualEffects.set(i, STATE_DISALLOW);
            }
            return this;
        }

        /**
         * Unsets a priority category, neither allowing or disallowing. When applying this policy,
         * unset categories will default to the current applied policy.
         * @hide
         */
        public @NonNull Builder unsetPriorityCategory(@PriorityCategory int category) {
            mZenPolicy.mPriorityCategories.set(category, STATE_UNSET);

            if (category == PRIORITY_CATEGORY_MESSAGES) {
                mZenPolicy.mPriorityMessages = PEOPLE_TYPE_UNSET;
            } else if (category == PRIORITY_CATEGORY_CALLS) {
                mZenPolicy.mPriorityCalls = PEOPLE_TYPE_UNSET;
            } else if (category == PRIORITY_CATEGORY_CONVERSATIONS) {
                mZenPolicy.mConversationSenders = CONVERSATION_SENDERS_UNSET;
            }

            return this;
        }

        /**
         * Unsets a visual effect, neither allowing or disallowing. When applying this policy,
         * unset effects will default to the current applied policy.
         * @hide
         */
        public @NonNull Builder unsetVisualEffect(@VisualEffect int effect) {
            mZenPolicy.mVisualEffects.set(effect, STATE_UNSET);
            return this;
        }

        /**
         * Whether to allow notifications with category {@link Notification#CATEGORY_REMINDER}
         * to play sounds and visually appear or to intercept them when DND is active.
         */
        public @NonNull Builder allowReminders(boolean allow) {
            mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_REMINDERS,
                    allow ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether to allow notifications with category {@link Notification#CATEGORY_EVENT}
         * to play sounds and visually appear or to intercept them when DND is active.
         */
        public @NonNull Builder allowEvents(boolean allow) {
            mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_EVENTS,
                    allow ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether to allow conversation notifications
         * (see {@link NotificationChannel#setConversationId(String, String)})
         * that match audienceType to play sounds and visually appear or to intercept
         * them when DND is active.
         * @param audienceType callers that are allowed to bypass DND
         */
        public @NonNull  Builder allowConversations(@ConversationSenders int audienceType) {
            if (audienceType == STATE_UNSET) {
                return unsetPriorityCategory(PRIORITY_CATEGORY_CONVERSATIONS);
            }

            if (audienceType == CONVERSATION_SENDERS_NONE) {
                mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_CONVERSATIONS, STATE_DISALLOW);
            } else if (audienceType == CONVERSATION_SENDERS_ANYONE
                    || audienceType == CONVERSATION_SENDERS_IMPORTANT) {
                mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_CONVERSATIONS, STATE_ALLOW);
            } else {
                return this;
            }

            mZenPolicy.mConversationSenders = audienceType;
            return this;
        }

        /**
         * Whether to allow notifications with category {@link Notification#CATEGORY_MESSAGE}
         * that match audienceType to play sounds and visually appear or to intercept
         * them when DND is active.
         * @param audienceType message senders that are allowed to bypass DND
         */
        public @NonNull Builder allowMessages(@PeopleType int audienceType) {
            if (audienceType == STATE_UNSET) {
                return unsetPriorityCategory(PRIORITY_CATEGORY_MESSAGES);
            }

            if (audienceType == PEOPLE_TYPE_NONE) {
                mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_MESSAGES, STATE_DISALLOW);
            } else if (audienceType == PEOPLE_TYPE_ANYONE || audienceType == PEOPLE_TYPE_CONTACTS
                    || audienceType == PEOPLE_TYPE_STARRED) {
                mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_MESSAGES, STATE_ALLOW);
            } else {
                return this;
            }

            mZenPolicy.mPriorityMessages = audienceType;
            return this;
        }

        /**
         * Whether to allow notifications with category {@link Notification#CATEGORY_CALL}
         * that match audienceType to play sounds and visually appear or to intercept
         * them when DND is active.
         * @param audienceType callers that are allowed to bypass DND
         */
        public @NonNull  Builder allowCalls(@PeopleType int audienceType) {
            if (audienceType == STATE_UNSET) {
                return unsetPriorityCategory(PRIORITY_CATEGORY_CALLS);
            }

            if (audienceType == PEOPLE_TYPE_NONE) {
                mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_CALLS, STATE_DISALLOW);
            } else if (audienceType == PEOPLE_TYPE_ANYONE || audienceType == PEOPLE_TYPE_CONTACTS
                    || audienceType == PEOPLE_TYPE_STARRED) {
                mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_CALLS, STATE_ALLOW);
            } else {
                return this;
            }

            mZenPolicy.mPriorityCalls = audienceType;
            return this;
        }

        /**
         * Whether to allow repeat callers (notifications with category
         * {@link Notification#CATEGORY_CALL} that have recently called
         * to play sounds and visually appear.
         */
        public @NonNull Builder allowRepeatCallers(boolean allow) {
            mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_REPEAT_CALLERS,
                    allow ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether to allow notifications with category {@link Notification#CATEGORY_ALARM}
         * to play sounds and visually appear or to intercept them when DND is active.
         * Disallowing alarms will mute the alarm stream when DND is active.
         */
        public @NonNull Builder allowAlarms(boolean allow) {
            mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_ALARMS,
                    allow ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether to allow media notifications to play sounds and visually
         * appear or to intercept them when DND is active.
         * Disallowing media will mute the media stream when DND is active.
         */
        public @NonNull Builder allowMedia(boolean allow) {
            mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_MEDIA,
                    allow ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether to allow system sounds to play when DND is active.
         * Disallowing system sounds will mute the system stream when DND is active.
         */
        public @NonNull Builder allowSystem(boolean allow) {
            mZenPolicy.mPriorityCategories.set(PRIORITY_CATEGORY_SYSTEM,
                    allow ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether to allow {@link PriorityCategory} sounds to play when DND is active.
         * @hide
         */
        public @NonNull Builder allowCategory(@PriorityCategory int category, boolean allow) {
            switch (category) {
                case PRIORITY_CATEGORY_ALARMS:
                    allowAlarms(allow);
                    break;
                case PRIORITY_CATEGORY_MEDIA:
                    allowMedia(allow);
                    break;
                case PRIORITY_CATEGORY_SYSTEM:
                    allowSystem(allow);
                    break;
                case PRIORITY_CATEGORY_REMINDERS:
                    allowReminders(allow);
                    break;
                case PRIORITY_CATEGORY_EVENTS:
                    allowEvents(allow);
                    break;
                case PRIORITY_CATEGORY_REPEAT_CALLERS:
                    allowRepeatCallers(allow);
                    break;
            }
            return this;
        }

        /**
         * Whether {@link Notification#fullScreenIntent full screen intents} that are intercepted
         * by DND are shown.
         */
        public @NonNull Builder showFullScreenIntent(boolean show) {
            mZenPolicy.mVisualEffects.set(VISUAL_EFFECT_FULL_SCREEN_INTENT,
                    show ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether {@link NotificationChannel#shouldShowLights() notification lights} from
         * notifications intercepted by DND are blocked.
         */
        public @NonNull Builder showLights(boolean show) {
            mZenPolicy.mVisualEffects.set(VISUAL_EFFECT_LIGHTS,
                    show ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether notifications intercepted by DND are prevented from peeking.
         */
        public @NonNull Builder showPeeking(boolean show) {
            mZenPolicy.mVisualEffects.set(VISUAL_EFFECT_PEEK,
                    show ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether notifications intercepted by DND are prevented from appearing in the status bar
         * on devices that support status bars.
         */
        public @NonNull Builder showStatusBarIcons(boolean show) {
            mZenPolicy.mVisualEffects.set(VISUAL_EFFECT_STATUS_BAR,
                    show ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether {@link NotificationChannel#canShowBadge() badges} from
         * notifications intercepted by DND are allowed on devices that support badging.
         */
        public @NonNull Builder showBadges(boolean show) {
            mZenPolicy.mVisualEffects.set(VISUAL_EFFECT_BADGE,
                    show ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether notification intercepted by DND are prevented from appearing on ambient displays
         * on devices that support ambient display.
         */
        public @NonNull Builder showInAmbientDisplay(boolean show) {
            mZenPolicy.mVisualEffects.set(VISUAL_EFFECT_AMBIENT,
                    show ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether notification intercepted by DND are prevented from appearing in notification
         * list views like the notification shade or lockscreen on devices that support those
         * views.
         */
        public @NonNull Builder showInNotificationList(boolean show) {
            mZenPolicy.mVisualEffects.set(VISUAL_EFFECT_NOTIFICATION_LIST,
                    show ? STATE_ALLOW : STATE_DISALLOW);
            return this;
        }

        /**
         * Whether notifications intercepted by DND are prevented from appearing for
         * {@link VisualEffect}
         * @hide
         */
        public @NonNull Builder showVisualEffect(@VisualEffect int effect, boolean show) {
            switch (effect) {
                case VISUAL_EFFECT_FULL_SCREEN_INTENT:
                    showFullScreenIntent(show);
                    break;
                case VISUAL_EFFECT_LIGHTS:
                    showLights(show);
                    break;
                case VISUAL_EFFECT_PEEK:
                    showPeeking(show);
                    break;
                case VISUAL_EFFECT_STATUS_BAR:
                    showStatusBarIcons(show);
                    break;
                case VISUAL_EFFECT_BADGE:
                    showBadges(show);
                    break;
                case VISUAL_EFFECT_AMBIENT:
                    showInAmbientDisplay(show);
                    break;
                case VISUAL_EFFECT_NOTIFICATION_LIST:
                    showInNotificationList(show);
                    break;
            }
            return this;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeList(mPriorityCategories);
        dest.writeList(mVisualEffects);
        dest.writeInt(mPriorityCalls);
        dest.writeInt(mPriorityMessages);
        dest.writeInt(mConversationSenders);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ZenPolicy> CREATOR =
            new Parcelable.Creator<ZenPolicy>() {
        @Override
        public ZenPolicy createFromParcel(Parcel source) {
            ZenPolicy policy = new ZenPolicy();
            policy.mPriorityCategories = trimList(
                    source.readArrayList(Integer.class.getClassLoader(), java.lang.Integer.class),
                    NUM_PRIORITY_CATEGORIES);
            policy.mVisualEffects = trimList(
                    source.readArrayList(Integer.class.getClassLoader(), java.lang.Integer.class),
                    NUM_VISUAL_EFFECTS);
            policy.mPriorityCalls = source.readInt();
            policy.mPriorityMessages = source.readInt();
            policy.mConversationSenders = source.readInt();
            return policy;
        }

        @Override
        public ZenPolicy[] newArray(int size) {
            return new ZenPolicy[size];
        }
    };

    @Override
    public String toString() {
        return new StringBuilder(ZenPolicy.class.getSimpleName())
                .append('{')
                .append("priorityCategories=[").append(priorityCategoriesToString())
                .append("], visualEffects=[").append(visualEffectsToString())
                .append("], priorityCallsSenders=").append(peopleTypeToString(mPriorityCalls))
                .append(", priorityMessagesSenders=").append(peopleTypeToString(mPriorityMessages))
                .append(", priorityConversationSenders=").append(
                        conversationTypeToString(mConversationSenders))
                .append('}')
                .toString();
    }

    // Returns a list containing the first maxLength elements of the input list if the list is
    // longer than that size. For the lists in ZenPolicy, this should not happen unless the input
    // is corrupt.
    private static ArrayList<Integer> trimList(ArrayList<Integer> list, int maxLength) {
        if (list == null || list.size() <= maxLength) {
            return list;
        }
        return new ArrayList<>(list.subList(0, maxLength));
    }

    private String priorityCategoriesToString() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < mPriorityCategories.size(); i++) {
            if (mPriorityCategories.get(i) != STATE_UNSET) {
                builder.append(indexToCategory(i))
                        .append("=")
                        .append(stateToString(mPriorityCategories.get(i)))
                        .append(" ");
            }

        }
        return builder.toString();
    }

    private String visualEffectsToString() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < mVisualEffects.size(); i++) {
            if (mVisualEffects.get(i) != STATE_UNSET) {
                builder.append(indexToVisualEffect(i))
                        .append("=")
                        .append(stateToString(mVisualEffects.get(i)))
                        .append(" ");
            }

        }
        return builder.toString();
    }

    private String indexToVisualEffect(@VisualEffect int visualEffectIndex) {
        switch (visualEffectIndex) {
            case VISUAL_EFFECT_FULL_SCREEN_INTENT:
                return "fullScreenIntent";
            case VISUAL_EFFECT_LIGHTS:
                return "lights";
            case VISUAL_EFFECT_PEEK:
                return "peek";
            case VISUAL_EFFECT_STATUS_BAR:
                return "statusBar";
            case VISUAL_EFFECT_BADGE:
                return "badge";
            case VISUAL_EFFECT_AMBIENT:
                return "ambient";
            case VISUAL_EFFECT_NOTIFICATION_LIST:
                return "notificationList";
        }
        return null;
    }

    private String indexToCategory(@PriorityCategory int categoryIndex) {
        switch (categoryIndex) {
            case PRIORITY_CATEGORY_REMINDERS:
                return "reminders";
            case PRIORITY_CATEGORY_EVENTS:
                return "events";
            case PRIORITY_CATEGORY_MESSAGES:
                return "messages";
            case PRIORITY_CATEGORY_CALLS:
                return "calls";
            case PRIORITY_CATEGORY_REPEAT_CALLERS:
                return "repeatCallers";
            case PRIORITY_CATEGORY_ALARMS:
                return "alarms";
            case PRIORITY_CATEGORY_MEDIA:
                return "media";
            case PRIORITY_CATEGORY_SYSTEM:
                return "system";
            case PRIORITY_CATEGORY_CONVERSATIONS:
                return "convs";
        }
        return null;
    }

    private String stateToString(@State int state) {
        switch (state) {
            case STATE_UNSET:
                return "unset";
            case STATE_DISALLOW:
                return "disallow";
            case STATE_ALLOW:
                return "allow";
        }
        return "invalidState{" + state + "}";
    }

    private String peopleTypeToString(@PeopleType int peopleType) {
        switch (peopleType) {
            case PEOPLE_TYPE_ANYONE:
                return "anyone";
            case PEOPLE_TYPE_CONTACTS:
                return "contacts";
            case PEOPLE_TYPE_NONE:
                return "none";
            case PEOPLE_TYPE_STARRED:
                return "starred_contacts";
            case STATE_UNSET:
                return "unset";
        }
        return "invalidPeopleType{" + peopleType + "}";
    }

    /**
     * @hide
     */
    public static String conversationTypeToString(@ConversationSenders int conversationType) {
        switch (conversationType) {
            case CONVERSATION_SENDERS_ANYONE:
                return "anyone";
            case CONVERSATION_SENDERS_IMPORTANT:
                return "important";
            case CONVERSATION_SENDERS_NONE:
                return "none";
            case CONVERSATION_SENDERS_UNSET:
                return "unset";
        }
        return "invalidConversationType{" + conversationType + "}";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof ZenPolicy)) return false;
        if (o == this) return true;
        final ZenPolicy other = (ZenPolicy) o;

        return Objects.equals(other.mPriorityCategories, mPriorityCategories)
                && Objects.equals(other.mVisualEffects, mVisualEffects)
                && other.mPriorityCalls == mPriorityCalls
                && other.mPriorityMessages == mPriorityMessages
                && other.mConversationSenders == mConversationSenders;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPriorityCategories, mVisualEffects, mPriorityCalls, mPriorityMessages,
                mConversationSenders);
    }

    private @ZenPolicy.State int getZenPolicyPriorityCategoryState(@PriorityCategory int
            category) {
        switch (category) {
            case PRIORITY_CATEGORY_REMINDERS:
                return getPriorityCategoryReminders();
            case PRIORITY_CATEGORY_EVENTS:
                return getPriorityCategoryEvents();
            case PRIORITY_CATEGORY_MESSAGES:
                return getPriorityCategoryMessages();
            case PRIORITY_CATEGORY_CALLS:
                return getPriorityCategoryCalls();
            case PRIORITY_CATEGORY_REPEAT_CALLERS:
                return getPriorityCategoryRepeatCallers();
            case PRIORITY_CATEGORY_ALARMS:
                return getPriorityCategoryAlarms();
            case PRIORITY_CATEGORY_MEDIA:
                return getPriorityCategoryMedia();
            case PRIORITY_CATEGORY_SYSTEM:
                return getPriorityCategorySystem();
            case PRIORITY_CATEGORY_CONVERSATIONS:
                return getPriorityCategoryConversations();
        }
        return -1;
    }

    private @ZenPolicy.State int getZenPolicyVisualEffectState(@VisualEffect int effect) {
        switch (effect) {
            case VISUAL_EFFECT_FULL_SCREEN_INTENT:
                return getVisualEffectFullScreenIntent();
            case VISUAL_EFFECT_LIGHTS:
                return getVisualEffectLights();
            case VISUAL_EFFECT_PEEK:
                return getVisualEffectPeek();
            case VISUAL_EFFECT_STATUS_BAR:
                return getVisualEffectStatusBar();
            case VISUAL_EFFECT_BADGE:
                return getVisualEffectBadge();
            case VISUAL_EFFECT_AMBIENT:
                return getVisualEffectAmbient();
            case VISUAL_EFFECT_NOTIFICATION_LIST:
                return getVisualEffectNotificationList();
        }
        return -1;
    }

    /** @hide */
    public boolean isCategoryAllowed(@PriorityCategory int category, boolean defaultVal) {
        switch (getZenPolicyPriorityCategoryState(category)) {
            case ZenPolicy.STATE_ALLOW:
                return true;
            case ZenPolicy.STATE_DISALLOW:
                return false;
            default:
                return defaultVal;
        }
    }

    /** @hide */
    public boolean isVisualEffectAllowed(@VisualEffect int effect, boolean defaultVal) {
        switch (getZenPolicyVisualEffectState(effect)) {
            case ZenPolicy.STATE_ALLOW:
                return true;
            case ZenPolicy.STATE_DISALLOW:
                return false;
            default:
                return defaultVal;
        }
    }

    /**
     * Applies another policy on top of this policy
     * @hide
     */
    public void apply(ZenPolicy policyToApply) {
        if (policyToApply == null) {
            return;
        }

        // apply priority categories
        for (int category = 0; category < mPriorityCategories.size(); category++) {
            if (mPriorityCategories.get(category) == STATE_DISALLOW) {
                // if a priority category is already disallowed by the policy, cannot allow
                continue;
            }

            @State int newState = policyToApply.mPriorityCategories.get(category);
            if (newState != STATE_UNSET) {
                mPriorityCategories.set(category, newState);

                if (category == PRIORITY_CATEGORY_MESSAGES
                        && mPriorityMessages < policyToApply.mPriorityMessages) {
                    mPriorityMessages = policyToApply.mPriorityMessages;
                } else if (category == PRIORITY_CATEGORY_CALLS
                        && mPriorityCalls < policyToApply.mPriorityCalls) {
                    mPriorityCalls = policyToApply.mPriorityCalls;
                } else if (category == PRIORITY_CATEGORY_CONVERSATIONS
                        && mConversationSenders < policyToApply.mConversationSenders) {
                    mConversationSenders = policyToApply.mConversationSenders;
                }
            }
        }

        // apply visual effects
        for (int visualEffect = 0; visualEffect < mVisualEffects.size(); visualEffect++) {
            if (mVisualEffects.get(visualEffect) == STATE_DISALLOW) {
                // if a visual effect is already disallowed by the policy, cannot allow
                continue;
            }

            if (policyToApply.mVisualEffects.get(visualEffect) != STATE_UNSET) {
                mVisualEffects.set(visualEffect, policyToApply.mVisualEffects.get(visualEffect));
            }
        }
    }

    /**
     * @hide
     */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        proto.write(ZenPolicyProto.REMINDERS, getPriorityCategoryReminders());
        proto.write(ZenPolicyProto.EVENTS, getPriorityCategoryEvents());
        proto.write(ZenPolicyProto.MESSAGES, getPriorityCategoryMessages());
        proto.write(ZenPolicyProto.CALLS, getPriorityCategoryCalls());
        proto.write(ZenPolicyProto.REPEAT_CALLERS, getPriorityCategoryRepeatCallers());
        proto.write(ZenPolicyProto.ALARMS, getPriorityCategoryAlarms());
        proto.write(ZenPolicyProto.MEDIA, getPriorityCategoryMedia());
        proto.write(ZenPolicyProto.SYSTEM, getPriorityCategorySystem());

        proto.write(ZenPolicyProto.FULL_SCREEN_INTENT, getVisualEffectFullScreenIntent());
        proto.write(ZenPolicyProto.LIGHTS, getVisualEffectLights());
        proto.write(ZenPolicyProto.PEEK, getVisualEffectPeek());
        proto.write(ZenPolicyProto.STATUS_BAR, getVisualEffectStatusBar());
        proto.write(ZenPolicyProto.BADGE, getVisualEffectBadge());
        proto.write(ZenPolicyProto.AMBIENT, getVisualEffectAmbient());
        proto.write(ZenPolicyProto.NOTIFICATION_LIST, getVisualEffectNotificationList());

        proto.write(ZenPolicyProto.PRIORITY_MESSAGES, getPriorityMessageSenders());
        proto.write(ZenPolicyProto.PRIORITY_CALLS, getPriorityCallSenders());
        proto.end(token);
    }

    /**
     * Converts a policy to a statsd proto.
     * @hides
     */
    public byte[] toProto() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ProtoOutputStream proto = new ProtoOutputStream(bytes);

        proto.write(DNDPolicyProto.CALLS, getPriorityCategoryCalls());
        proto.write(DNDPolicyProto.REPEAT_CALLERS, getPriorityCategoryRepeatCallers());
        proto.write(DNDPolicyProto.MESSAGES, getPriorityCategoryMessages());
        proto.write(DNDPolicyProto.CONVERSATIONS, getPriorityCategoryConversations());
        proto.write(DNDPolicyProto.REMINDERS, getPriorityCategoryReminders());
        proto.write(DNDPolicyProto.EVENTS, getPriorityCategoryEvents());
        proto.write(DNDPolicyProto.ALARMS, getPriorityCategoryAlarms());
        proto.write(DNDPolicyProto.MEDIA, getPriorityCategoryMedia());
        proto.write(DNDPolicyProto.SYSTEM, getPriorityCategorySystem());

        proto.write(DNDPolicyProto.FULLSCREEN, getVisualEffectFullScreenIntent());
        proto.write(DNDPolicyProto.LIGHTS, getVisualEffectLights());
        proto.write(DNDPolicyProto.PEEK, getVisualEffectPeek());
        proto.write(DNDPolicyProto.STATUS_BAR, getVisualEffectStatusBar());
        proto.write(DNDPolicyProto.BADGE, getVisualEffectBadge());
        proto.write(DNDPolicyProto.AMBIENT, getVisualEffectAmbient());
        proto.write(DNDPolicyProto.NOTIFICATION_LIST, getVisualEffectNotificationList());

        proto.write(DNDPolicyProto.ALLOW_CALLS_FROM, getPriorityCallSenders());
        proto.write(DNDPolicyProto.ALLOW_MESSAGES_FROM, getPriorityMessageSenders());
        proto.write(DNDPolicyProto.ALLOW_CONVERSATIONS_FROM, getPriorityConversationSenders());

        proto.flush();
        return bytes.toByteArray();
    }

    /**
     * Makes deep copy of this ZenPolicy.
     * @hide
     */
    public @NonNull ZenPolicy copy() {
        final Parcel parcel = Parcel.obtain();
        try {
            writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
