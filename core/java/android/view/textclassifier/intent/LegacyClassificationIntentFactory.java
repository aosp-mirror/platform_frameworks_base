/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.view.textclassifier.intent;

import static java.time.temporal.ChronoUnit.MILLIS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.SearchManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Browser;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.view.textclassifier.Log;
import android.view.textclassifier.TextClassifier;

import com.google.android.textclassifier.AnnotatorModel;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Creates intents based on the classification type.
 * @hide
 */
public final class LegacyClassificationIntentFactory implements ClassificationIntentFactory {

    private static final String TAG = "LegacyClassificationIntentFactory";
    private static final long MIN_EVENT_FUTURE_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long DEFAULT_EVENT_DURATION = TimeUnit.HOURS.toMillis(1);

    @NonNull
    @Override
    public List<LabeledIntent> create(Context context, String text, boolean foreignText,
            @Nullable Instant referenceTime,
            AnnotatorModel.ClassificationResult classification) {
        final String type = classification != null
                ? classification.getCollection().trim().toLowerCase(Locale.ENGLISH)
                : "";
        text = text.trim();
        final List<LabeledIntent> actions;
        switch (type) {
            case TextClassifier.TYPE_EMAIL:
                actions = createForEmail(context, text);
                break;
            case TextClassifier.TYPE_PHONE:
                actions = createForPhone(context, text);
                break;
            case TextClassifier.TYPE_ADDRESS:
                actions = createForAddress(context, text);
                break;
            case TextClassifier.TYPE_URL:
                actions = createForUrl(context, text);
                break;
            case TextClassifier.TYPE_DATE:  // fall through
            case TextClassifier.TYPE_DATE_TIME:
                if (classification.getDatetimeResult() != null) {
                    final Instant parsedTime = Instant.ofEpochMilli(
                            classification.getDatetimeResult().getTimeMsUtc());
                    actions = createForDatetime(context, type, referenceTime, parsedTime);
                } else {
                    actions = new ArrayList<>();
                }
                break;
            case TextClassifier.TYPE_FLIGHT_NUMBER:
                actions = createForFlight(context, text);
                break;
            case TextClassifier.TYPE_DICTIONARY:
                actions = createForDictionary(context, text);
                break;
            default:
                actions = new ArrayList<>();
                break;
        }
        if (foreignText) {
            ClassificationIntentFactory.insertTranslateAction(actions, context, text);
        }
        return actions;
    }

    @NonNull
    private static List<LabeledIntent> createForEmail(Context context, String text) {
        final List<LabeledIntent> actions = new ArrayList<>();
        actions.add(new LabeledIntent(
                context.getString(com.android.internal.R.string.email),
                /* titleWithEntity */ null,
                context.getString(com.android.internal.R.string.email_desc),
                new Intent(Intent.ACTION_SENDTO)
                        .setData(Uri.parse(String.format("mailto:%s", text))),
                LabeledIntent.DEFAULT_REQUEST_CODE));
        actions.add(new LabeledIntent(
                context.getString(com.android.internal.R.string.add_contact),
                /* titleWithEntity */ null,
                context.getString(com.android.internal.R.string.add_contact_desc),
                new Intent(Intent.ACTION_INSERT_OR_EDIT)
                        .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.EMAIL, text),
                text.hashCode()));
        return actions;
    }

    @NonNull
    private static List<LabeledIntent> createForPhone(Context context, String text) {
        final List<LabeledIntent> actions = new ArrayList<>();
        final UserManager userManager = context.getSystemService(UserManager.class);
        final Bundle userRestrictions = userManager != null
                ? userManager.getUserRestrictions() : new Bundle();
        if (!userRestrictions.getBoolean(UserManager.DISALLOW_OUTGOING_CALLS, false)) {
            actions.add(new LabeledIntent(
                    context.getString(com.android.internal.R.string.dial),
                    /* titleWithEntity */ null,
                    context.getString(com.android.internal.R.string.dial_desc),
                    new Intent(Intent.ACTION_DIAL).setData(
                            Uri.parse(String.format("tel:%s", text))),
                    LabeledIntent.DEFAULT_REQUEST_CODE));
        }
        actions.add(new LabeledIntent(
                context.getString(com.android.internal.R.string.add_contact),
                /* titleWithEntity */ null,
                context.getString(com.android.internal.R.string.add_contact_desc),
                new Intent(Intent.ACTION_INSERT_OR_EDIT)
                        .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.PHONE, text),
                text.hashCode()));
        if (!userRestrictions.getBoolean(UserManager.DISALLOW_SMS, false)) {
            actions.add(new LabeledIntent(
                    context.getString(com.android.internal.R.string.sms),
                    /* titleWithEntity */ null,
                    context.getString(com.android.internal.R.string.sms_desc),
                    new Intent(Intent.ACTION_SENDTO)
                            .setData(Uri.parse(String.format("smsto:%s", text))),
                    LabeledIntent.DEFAULT_REQUEST_CODE));
        }
        return actions;
    }

    @NonNull
    private static List<LabeledIntent> createForAddress(Context context, String text) {
        final List<LabeledIntent> actions = new ArrayList<>();
        try {
            final String encText = URLEncoder.encode(text, "UTF-8");
            actions.add(new LabeledIntent(
                    context.getString(com.android.internal.R.string.map),
                    /* titleWithEntity */ null,
                    context.getString(com.android.internal.R.string.map_desc),
                    new Intent(Intent.ACTION_VIEW)
                            .setData(Uri.parse(String.format("geo:0,0?q=%s", encText))),
                    LabeledIntent.DEFAULT_REQUEST_CODE));
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Could not encode address", e);
        }
        return actions;
    }

    @NonNull
    private static List<LabeledIntent> createForUrl(Context context, String text) {
        if (Uri.parse(text).getScheme() == null) {
            text = "http://" + text;
        }
        final List<LabeledIntent> actions = new ArrayList<>();
        actions.add(new LabeledIntent(
                context.getString(com.android.internal.R.string.browse),
                /* titleWithEntity */ null,
                context.getString(com.android.internal.R.string.browse_desc),
                new Intent(Intent.ACTION_VIEW)
                        .setDataAndNormalize(Uri.parse(text))
                        .putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName()),
                LabeledIntent.DEFAULT_REQUEST_CODE));
        return actions;
    }

    @NonNull
    private static List<LabeledIntent> createForDatetime(
            Context context, String type, @Nullable Instant referenceTime,
            Instant parsedTime) {
        if (referenceTime == null) {
            // If no reference time was given, use now.
            referenceTime = Instant.now();
        }
        List<LabeledIntent> actions = new ArrayList<>();
        actions.add(createCalendarViewIntent(context, parsedTime));
        final long millisUntilEvent = referenceTime.until(parsedTime, MILLIS);
        if (millisUntilEvent > MIN_EVENT_FUTURE_MILLIS) {
            actions.add(createCalendarCreateEventIntent(context, parsedTime, type));
        }
        return actions;
    }

    @NonNull
    private static List<LabeledIntent> createForFlight(Context context, String text) {
        final List<LabeledIntent> actions = new ArrayList<>();
        actions.add(new LabeledIntent(
                context.getString(com.android.internal.R.string.view_flight),
                /* titleWithEntity */ null,
                context.getString(com.android.internal.R.string.view_flight_desc),
                new Intent(Intent.ACTION_WEB_SEARCH)
                        .putExtra(SearchManager.QUERY, text),
                text.hashCode()));
        return actions;
    }

    @NonNull
    private static LabeledIntent createCalendarViewIntent(Context context, Instant parsedTime) {
        Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
        builder.appendPath("time");
        ContentUris.appendId(builder, parsedTime.toEpochMilli());
        return new LabeledIntent(
                context.getString(com.android.internal.R.string.view_calendar),
                /* titleWithEntity */ null,
                context.getString(com.android.internal.R.string.view_calendar_desc),
                new Intent(Intent.ACTION_VIEW).setData(builder.build()),
                LabeledIntent.DEFAULT_REQUEST_CODE);
    }

    @NonNull
    private static LabeledIntent createCalendarCreateEventIntent(
            Context context, Instant parsedTime, @TextClassifier.EntityType String type) {
        final boolean isAllDay = TextClassifier.TYPE_DATE.equals(type);
        return new LabeledIntent(
                context.getString(com.android.internal.R.string.add_calendar_event),
                /* titleWithEntity */ null,
                context.getString(com.android.internal.R.string.add_calendar_event_desc),
                new Intent(Intent.ACTION_INSERT)
                        .setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, isAllDay)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                                parsedTime.toEpochMilli())
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                                parsedTime.toEpochMilli() + DEFAULT_EVENT_DURATION),
                parsedTime.hashCode());
    }

    @NonNull
    private static List<LabeledIntent> createForDictionary(Context context, String text) {
        final List<LabeledIntent> actions = new ArrayList<>();
        actions.add(new LabeledIntent(
                context.getString(com.android.internal.R.string.define),
                /* titleWithEntity */ null,
                context.getString(com.android.internal.R.string.define_desc),
                new Intent(Intent.ACTION_DEFINE)
                        .putExtra(Intent.EXTRA_TEXT, text),
                text.hashCode()));
        return actions;
    }
}
