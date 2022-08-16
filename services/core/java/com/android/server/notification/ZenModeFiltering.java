/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static android.provider.Settings.Global.ZEN_MODE_OFF;
import static android.service.notification.ZenPolicy.CONVERSATION_SENDERS_ANYONE;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.service.notification.ZenModeConfig;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.util.NotificationMessagingUtil;

import java.io.PrintWriter;
import java.util.Date;

public class ZenModeFiltering {
    private static final String TAG = ZenModeHelper.TAG;
    private static final boolean DEBUG = ZenModeHelper.DEBUG;

    static final RepeatCallers REPEAT_CALLERS = new RepeatCallers();

    private final Context mContext;

    private ComponentName mDefaultPhoneApp;
    private final NotificationMessagingUtil mMessagingUtil;

    public ZenModeFiltering(Context context) {
        mContext = context;
        mMessagingUtil = new NotificationMessagingUtil(mContext);
    }

    public ZenModeFiltering(Context context, NotificationMessagingUtil messagingUtil) {
        mContext = context;
        mMessagingUtil = messagingUtil;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mDefaultPhoneApp="); pw.println(mDefaultPhoneApp);
        pw.print(prefix); pw.print("RepeatCallers.mThresholdMinutes=");
        pw.println(REPEAT_CALLERS.mThresholdMinutes);
        synchronized (REPEAT_CALLERS) {
            if (!REPEAT_CALLERS.mTelCalls.isEmpty()) {
                pw.print(prefix); pw.println("RepeatCallers.mTelCalls=");
                for (int i = 0; i < REPEAT_CALLERS.mTelCalls.size(); i++) {
                    pw.print(prefix); pw.print("  ");
                    pw.print(REPEAT_CALLERS.mTelCalls.keyAt(i));
                    pw.print(" at ");
                    pw.println(ts(REPEAT_CALLERS.mTelCalls.valueAt(i)));
                }
            }
            if (!REPEAT_CALLERS.mOtherCalls.isEmpty()) {
                pw.print(prefix); pw.println("RepeatCallers.mOtherCalls=");
                for (int i = 0; i < REPEAT_CALLERS.mOtherCalls.size(); i++) {
                    pw.print(prefix); pw.print("  ");
                    pw.print(REPEAT_CALLERS.mOtherCalls.keyAt(i));
                    pw.print(" at ");
                    pw.println(ts(REPEAT_CALLERS.mOtherCalls.valueAt(i)));
                }
            }
        }
    }

    private static String ts(long time) {
        return new Date(time) + " (" + time + ")";
    }

    /**
     * @param extras extras of the notification with EXTRA_PEOPLE populated
     * @param contactsTimeoutMs timeout in milliseconds to wait for contacts response
     * @param timeoutAffinity affinity to return when the timeout specified via
     *                        <code>contactsTimeoutMs</code> is hit
     */
    public static boolean matchesCallFilter(Context context, int zen, NotificationManager.Policy
            consolidatedPolicy, UserHandle userHandle, Bundle extras,
            ValidateNotificationPeople validator, int contactsTimeoutMs, float timeoutAffinity) {
        if (zen == Global.ZEN_MODE_NO_INTERRUPTIONS) {
            ZenLog.traceMatchesCallFilter(false, "no interruptions");
            return false; // nothing gets through
        }
        if (zen == Global.ZEN_MODE_ALARMS) {
            ZenLog.traceMatchesCallFilter(false, "alarms only");
            return false; // not an alarm
        }
        if (zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            if (consolidatedPolicy.allowRepeatCallers()
                    && REPEAT_CALLERS.isRepeat(context, extras, null)) {
                ZenLog.traceMatchesCallFilter(true, "repeat caller");
                return true;
            }
            if (!consolidatedPolicy.allowCalls()) {
                ZenLog.traceMatchesCallFilter(false, "calls not allowed");
                return false; // no other calls get through
            }
            if (validator != null) {
                final float contactAffinity = validator.getContactAffinity(userHandle, extras,
                        contactsTimeoutMs, timeoutAffinity);
                boolean match =
                        audienceMatches(consolidatedPolicy.allowCallsFrom(), contactAffinity);
                ZenLog.traceMatchesCallFilter(match, "contact affinity " + contactAffinity);
                return match;
            }
        }
        ZenLog.traceMatchesCallFilter(true, "no restrictions");
        return true;
    }

    private static Bundle extras(NotificationRecord record) {
        return record != null && record.getSbn() != null && record.getSbn().getNotification() != null
                ? record.getSbn().getNotification().extras : null;
    }

    protected void recordCall(NotificationRecord record) {
        REPEAT_CALLERS.recordCall(mContext, extras(record), record.getPhoneNumbers());
    }

    /**
     * Whether to intercept the notification based on the policy
     */
    public boolean shouldIntercept(int zen, NotificationManager.Policy policy,
            NotificationRecord record) {
        // Zen mode is ignored for critical notifications.
        if (zen == ZEN_MODE_OFF || isCritical(record)) {
            return false;
        }
        // Make an exception to policy for the notification saying that policy has changed
        if (NotificationManager.Policy.areAllVisualEffectsSuppressed(policy.suppressedVisualEffects)
                && "android".equals(record.getSbn().getPackageName())
                && SystemMessageProto.SystemMessage.NOTE_ZEN_UPGRADE == record.getSbn().getId()) {
            ZenLog.traceNotIntercepted(record, "systemDndChangedNotification");
            return false;
        }
        switch (zen) {
            case Global.ZEN_MODE_NO_INTERRUPTIONS:
                // #notevenalarms
                ZenLog.traceIntercepted(record, "none");
                return true;
            case Global.ZEN_MODE_ALARMS:
                if (isAlarm(record)) {
                    // Alarms only
                    return false;
                }
                ZenLog.traceIntercepted(record, "alarmsOnly");
                return true;
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                // allow user-prioritized packages through in priority mode
                if (record.getPackagePriority() == Notification.PRIORITY_MAX) {
                    ZenLog.traceNotIntercepted(record, "priorityApp");
                    return false;
                }

                if (isAlarm(record)) {
                    if (!policy.allowAlarms()) {
                        ZenLog.traceIntercepted(record, "!allowAlarms");
                        return true;
                    }
                    return false;
                }
                if (isEvent(record)) {
                    if (!policy.allowEvents()) {
                        ZenLog.traceIntercepted(record, "!allowEvents");
                        return true;
                    }
                    return false;
                }
                if (isReminder(record)) {
                    if (!policy.allowReminders()) {
                        ZenLog.traceIntercepted(record, "!allowReminders");
                        return true;
                    }
                    return false;
                }
                if (isMedia(record)) {
                    if (!policy.allowMedia()) {
                        ZenLog.traceIntercepted(record, "!allowMedia");
                        return true;
                    }
                    return false;
                }
                if (isSystem(record)) {
                    if (!policy.allowSystem()) {
                        ZenLog.traceIntercepted(record, "!allowSystem");
                        return true;
                    }
                    return false;
                }
                if (isConversation(record)) {
                    if (policy.allowConversations()) {
                        if (policy.priorityConversationSenders == CONVERSATION_SENDERS_ANYONE) {
                            ZenLog.traceNotIntercepted(record, "conversationAnyone");
                            return false;
                        } else if (policy.priorityConversationSenders
                                == NotificationManager.Policy.CONVERSATION_SENDERS_IMPORTANT
                                && record.getChannel().isImportantConversation()) {
                            ZenLog.traceNotIntercepted(record, "conversationMatches");
                            return false;
                        }
                    }
                    // if conversations aren't allowed record might still be allowed thanks
                    // to call or message metadata, so don't return yet
                }
                if (isCall(record)) {
                    if (policy.allowRepeatCallers()
                            && REPEAT_CALLERS.isRepeat(
                                    mContext, extras(record), record.getPhoneNumbers())) {
                        ZenLog.traceNotIntercepted(record, "repeatCaller");
                        return false;
                    }
                    if (!policy.allowCalls()) {
                        ZenLog.traceIntercepted(record, "!allowCalls");
                        return true;
                    }
                    return shouldInterceptAudience(policy.allowCallsFrom(), record);
                }
                if (isMessage(record)) {
                    if (!policy.allowMessages()) {
                        ZenLog.traceIntercepted(record, "!allowMessages");
                        return true;
                    }
                    return shouldInterceptAudience(policy.allowMessagesFrom(), record);
                }

                ZenLog.traceIntercepted(record, "!priority");
                return true;
            default:
                return false;
        }
    }

    /**
     * Check if the notification is too critical to be suppressed.
     *
     * @param record the record to test for criticality
     * @return {@code true} if notification is considered critical
     *
     * @see CriticalNotificationExtractor for criteria
     */
    private boolean isCritical(NotificationRecord record) {
        // 0 is the most critical
        return record.getCriticality() < CriticalNotificationExtractor.NORMAL;
    }

    private static boolean shouldInterceptAudience(int source, NotificationRecord record) {
        if (!audienceMatches(source, record.getContactAffinity())) {
            ZenLog.traceIntercepted(record, "!audienceMatches");
            return true;
        }
        return false;
    }

    protected static boolean isAlarm(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_ALARM)
                || record.isAudioAttributesUsage(AudioAttributes.USAGE_ALARM);
    }

    private static boolean isEvent(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_EVENT);
    }

    private static boolean isReminder(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_REMINDER);
    }

    public boolean isCall(NotificationRecord record) {
        return record != null && (isDefaultPhoneApp(record.getSbn().getPackageName())
                || record.isCategory(Notification.CATEGORY_CALL));
    }

    public boolean isMedia(NotificationRecord record) {
        AudioAttributes aa = record.getAudioAttributes();
        return aa != null && AudioAttributes.SUPPRESSIBLE_USAGES.get(aa.getUsage()) ==
                AudioAttributes.SUPPRESSIBLE_MEDIA;
    }

    public boolean isSystem(NotificationRecord record) {
        AudioAttributes aa = record.getAudioAttributes();
        return aa != null && AudioAttributes.SUPPRESSIBLE_USAGES.get(aa.getUsage()) ==
                AudioAttributes.SUPPRESSIBLE_SYSTEM;
    }

    private boolean isDefaultPhoneApp(String pkg) {
        if (mDefaultPhoneApp == null) {
            final TelecomManager telecomm =
                    (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
            mDefaultPhoneApp = telecomm != null ? telecomm.getDefaultPhoneApp() : null;
            if (DEBUG) Slog.d(TAG, "Default phone app: " + mDefaultPhoneApp);
        }
        return pkg != null && mDefaultPhoneApp != null
                && pkg.equals(mDefaultPhoneApp.getPackageName());
    }

    protected boolean isMessage(NotificationRecord record) {
        return mMessagingUtil.isMessaging(record.getSbn());
    }

    protected boolean isConversation(NotificationRecord record) {
        return record.isConversation();
    }

    private static boolean audienceMatches(int source, float contactAffinity) {
        switch (source) {
            case ZenModeConfig.SOURCE_ANYONE:
                return true;
            case ZenModeConfig.SOURCE_CONTACT:
                return contactAffinity >= ValidateNotificationPeople.VALID_CONTACT;
            case ZenModeConfig.SOURCE_STAR:
                return contactAffinity >= ValidateNotificationPeople.STARRED_CONTACT;
            default:
                Slog.w(TAG, "Encountered unknown source: " + source);
                return true;
        }
    }

    protected void cleanUpCallersAfter(long timeThreshold) {
        REPEAT_CALLERS.cleanUpCallsAfter(timeThreshold);
    }

    private static class RepeatCallers {
        // We keep a separate map per uri scheme to do more generous number-matching
        // handling on telephone numbers specifically. For other inputs, we
        // simply match directly on the string.
        private final ArrayMap<String, Long> mTelCalls = new ArrayMap<>();
        private final ArrayMap<String, Long> mOtherCalls = new ArrayMap<>();
        private int mThresholdMinutes;

        // Record all people URIs in the extras bundle as well as the provided phoneNumbers set
        // as callers. The phoneNumbers set is used to pass in any additional phone numbers
        // associated with the people URIs as separately retrieved from contacts.
        private synchronized void recordCall(Context context, Bundle extras,
                ArraySet<String> phoneNumbers) {
            setThresholdMinutes(context);
            if (mThresholdMinutes <= 0 || extras == null) return;
            final String[] extraPeople = ValidateNotificationPeople.getExtraPeople(extras);
            if (extraPeople == null || extraPeople.length == 0) return;
            final long now = System.currentTimeMillis();
            cleanUp(mTelCalls, now);
            cleanUp(mOtherCalls, now);
            recordCallers(extraPeople, phoneNumbers, now);
        }

        // Determine whether any people in the provided extras bundle or phone number set is
        // a repeat caller. The extras bundle contains the people associated with a specific
        // notification, and will suffice for most callers; the phoneNumbers array may be used
        // to additionally check any specific phone numbers previously retrieved from contacts
        // associated with the people in the extras bundle.
        private synchronized boolean isRepeat(Context context, Bundle extras,
                ArraySet<String> phoneNumbers) {
            setThresholdMinutes(context);
            if (mThresholdMinutes <= 0 || extras == null) return false;
            final String[] extraPeople = ValidateNotificationPeople.getExtraPeople(extras);
            if (extraPeople == null || extraPeople.length == 0) return false;
            final long now = System.currentTimeMillis();
            cleanUp(mTelCalls, now);
            cleanUp(mOtherCalls, now);
            return checkCallers(context, extraPeople, phoneNumbers);
        }

        private synchronized void cleanUp(ArrayMap<String, Long> calls, long now) {
            final int N = calls.size();
            for (int i = N - 1; i >= 0; i--) {
                final long time = calls.valueAt(i);
                if (time > now || (now - time) > mThresholdMinutes * 1000 * 60) {
                    calls.removeAt(i);
                }
            }
        }

        // Clean up all calls that occurred after the given time.
        // Used only for tests, to clean up after testing.
        private synchronized void cleanUpCallsAfter(long timeThreshold) {
            for (int i = mTelCalls.size() - 1; i >= 0; i--) {
                final long time = mTelCalls.valueAt(i);
                if (time > timeThreshold) {
                    mTelCalls.removeAt(i);
                }
            }
            for (int j = mOtherCalls.size() - 1; j >= 0; j--) {
                final long time = mOtherCalls.valueAt(j);
                if (time > timeThreshold) {
                    mOtherCalls.removeAt(j);
                }
            }
        }

        private void setThresholdMinutes(Context context) {
            if (mThresholdMinutes <= 0) {
                mThresholdMinutes = context.getResources().getInteger(com.android.internal.R.integer
                        .config_zen_repeat_callers_threshold);
            }
        }

        private synchronized void recordCallers(String[] people, ArraySet<String> phoneNumbers,
                long now) {
            for (int i = 0; i < people.length; i++) {
                String person = people[i];
                if (person == null) continue;
                final Uri uri = Uri.parse(person);
                if ("tel".equals(uri.getScheme())) {
                    // while ideally we should not need to decode this, sometimes we have seen tel
                    // numbers given in an encoded format
                    String tel = Uri.decode(uri.getSchemeSpecificPart());
                    if (tel != null) mTelCalls.put(tel, now);
                } else {
                    // for non-tel calls, store the entire string, uri-component and all
                    mOtherCalls.put(person, now);
                }
            }

            // record any additional numbers from the notification record if
            // provided; these are in the format of just a phone number string
            if (phoneNumbers != null) {
                for (String num : phoneNumbers) {
                    if (num != null) mTelCalls.put(num, now);
                }
            }
        }

        // helper function to check mTelCalls array for a number, and also check its decoded
        // version
        private synchronized boolean checkForNumber(String number, String defaultCountryCode) {
            if (mTelCalls.containsKey(number)) {
                // check directly via map first
                return true;
            } else {
                // see if a number that matches via areSameNumber exists
                String numberToCheck = Uri.decode(number);
                if (numberToCheck != null) {
                    for (String prev : mTelCalls.keySet()) {
                        if (PhoneNumberUtils.areSamePhoneNumber(
                                numberToCheck, prev, defaultCountryCode)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        // Check whether anyone in the provided array of people URIs or phone number set matches a
        // previously recorded phone call.
        private synchronized boolean checkCallers(Context context, String[] people,
                ArraySet<String> phoneNumbers) {
            // get the default country code for checking telephone numbers
            final String defaultCountryCode =
                    context.getSystemService(TelephonyManager.class).getNetworkCountryIso();
            for (int i = 0; i < people.length; i++) {
                String person = people[i];
                if (person == null) continue;
                final Uri uri = Uri.parse(person);
                if ("tel".equals(uri.getScheme())) {
                    String number = uri.getSchemeSpecificPart();
                    if (checkForNumber(number, defaultCountryCode)) {
                        return true;
                    }
                } else {
                    if (mOtherCalls.containsKey(person)) {
                        return true;
                    }
                }
            }

            // also check any passed-in phone numbers
            if (phoneNumbers != null) {
                for (String num : phoneNumbers) {
                    if (checkForNumber(num, defaultCountryCode)) {
                        return true;
                    }
                }
            }

            // no matches
            return false;
        }
    }

}
