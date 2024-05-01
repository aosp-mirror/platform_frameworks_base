/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.app;

import static android.app.Notification.CarExtender.UnreadConversation.KEY_ON_READ;
import static android.app.Notification.CarExtender.UnreadConversation.KEY_ON_REPLY;
import static android.app.Notification.CarExtender.UnreadConversation.KEY_REMOTE_INPUT;
import static android.app.Notification.DEFAULT_SOUND;
import static android.app.Notification.DEFAULT_VIBRATE;
import static android.app.Notification.EXTRA_ANSWER_INTENT;
import static android.app.Notification.EXTRA_BUILDER_APPLICATION_INFO;
import static android.app.Notification.EXTRA_CALL_PERSON;
import static android.app.Notification.EXTRA_CONVERSATION_ICON;
import static android.app.Notification.EXTRA_DECLINE_INTENT;
import static android.app.Notification.EXTRA_HANG_UP_INTENT;
import static android.app.Notification.EXTRA_LARGE_ICON;
import static android.app.Notification.EXTRA_LARGE_ICON_BIG;
import static android.app.Notification.EXTRA_MEDIA_REMOTE_INTENT;
import static android.app.Notification.EXTRA_MEDIA_SESSION;
import static android.app.Notification.EXTRA_MESSAGING_PERSON;
import static android.app.Notification.EXTRA_PEOPLE_LIST;
import static android.app.Notification.EXTRA_PICTURE;
import static android.app.Notification.EXTRA_PICTURE_ICON;
import static android.app.Notification.EXTRA_SUMMARY_TEXT;
import static android.app.Notification.EXTRA_TITLE;
import static android.app.Notification.GROUP_ALERT_CHILDREN;
import static android.app.Notification.GROUP_ALERT_SUMMARY;
import static android.app.Notification.GROUP_KEY_SILENT;
import static android.app.Notification.MessagingStyle.Message.KEY_DATA_URI;
import static android.app.Notification.MessagingStyle.Message.KEY_SENDER_PERSON;
import static android.app.Notification.MessagingStyle.Message.KEY_TEXT;
import static android.app.Notification.MessagingStyle.Message.KEY_TIMESTAMP;
import static android.app.Notification.TvExtender.EXTRA_CONTENT_INTENT;
import static android.app.Notification.TvExtender.EXTRA_DELETE_INTENT;
import static android.app.Notification.WearableExtender.KEY_BACKGROUND;
import static android.app.Notification.WearableExtender.KEY_DISPLAY_INTENT;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.internal.util.ContrastColorUtilTest.assertContrastIsAtLeast;
import static com.android.internal.util.ContrastColorUtilTest.assertContrastIsWithinRange;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TextAppearanceSpan;
import android.util.Pair;
import android.widget.RemoteViews;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.util.ContrastColorUtil;

import junit.framework.Assert;

import libcore.junit.util.compat.CoreCompatChangeRule;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class NotificationTest {

    private Context mContext;

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        // TODO(b/169435530): remove this flag set once resolved.
        SystemProperties.set("persist.sysui.notification.builder_extras_override",
                Boolean.toString(false));
    }

    @Test
    public void testColorizedByPermission() {
        Notification n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_CAN_COLORIZE, true)
                .setColorized(true).setColor(Color.WHITE)
                .build();
        assertTrue(n.isColorized());

        n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_CAN_COLORIZE, true)
                .build();
        assertFalse(n.isColorized());

        n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_CAN_COLORIZE, false)
                .setColorized(true).setColor(Color.WHITE)
                .build();
        assertFalse(n.isColorized());
    }

    @Test
    public void testColorizedByForeground() {
        Notification n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .setColorized(true).setColor(Color.WHITE)
                .build();
        assertTrue(n.isColorized());

        n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        assertFalse(n.isColorized());

        n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, false)
                .setColorized(true).setColor(Color.WHITE)
                .build();
        assertFalse(n.isColorized());
    }

    @Test
    public void testHasCompletedProgress_noProgress() {
        Notification n = new Notification.Builder(mContext).build();

        assertFalse(n.hasCompletedProgress());
    }

    @Test
    public void testHasCompletedProgress_complete() {
        Notification n = new Notification.Builder(mContext)
                .setProgress(100, 100, true)
                .build();
        Notification n2 = new Notification.Builder(mContext)
                .setProgress(10, 10, false)
                .build();
        assertTrue(n.hasCompletedProgress());
        assertTrue(n2.hasCompletedProgress());
    }

    @Test
    public void testHasCompletedProgress_notComplete() {
        Notification n = new Notification.Builder(mContext)
                .setProgress(100, 99, true)
                .build();
        Notification n2 = new Notification.Builder(mContext)
                .setProgress(10, 4, false)
                .build();
        assertFalse(n.hasCompletedProgress());
        assertFalse(n2.hasCompletedProgress());
    }

    @Test
    public void testHasCompletedProgress_zeroMax() {
        Notification n = new Notification.Builder(mContext)
                .setProgress(0, 0, true)
                .build();
        assertFalse(n.hasCompletedProgress());
    }

    @Test
    public void largeIconMultipleReferences_keptAfterParcelling() {
        Icon originalIcon = Icon.createWithBitmap(BitmapFactory.decodeResource(
                mContext.getResources(), com.android.frameworks.coretests.R.drawable.test128x96));

        Notification n = new Notification.Builder(mContext).setLargeIcon(originalIcon).build();
        assertSame(n.getLargeIcon(), originalIcon);

        Notification q = writeAndReadParcelable(n);
        assertNotSame(q.getLargeIcon(), n.getLargeIcon());

        assertTrue(q.getLargeIcon().getBitmap().sameAs(n.getLargeIcon().getBitmap()));
        assertSame(q.getLargeIcon(), q.extras.getParcelable(EXTRA_LARGE_ICON));
    }

    @Test
    public void largeIconReferenceInExtrasOnly_keptAfterParcelling() {
        Icon originalIcon = Icon.createWithBitmap(BitmapFactory.decodeResource(
                mContext.getResources(), com.android.frameworks.coretests.R.drawable.test128x96));

        Notification n = new Notification.Builder(mContext).build();
        n.extras.putParcelable(EXTRA_LARGE_ICON, originalIcon);
        assertSame(n.getLargeIcon(), null);

        Notification q = writeAndReadParcelable(n);
        assertSame(q.getLargeIcon(), null);
        assertTrue(((Icon) q.extras.getParcelable(EXTRA_LARGE_ICON)).getBitmap()
                .sameAs(originalIcon.getBitmap()));
    }

    @Test
    public void allPendingIntents_recollectedAfterReusingBuilder() {
        PendingIntent intent1 = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent intent2 = PendingIntent.getActivity(
                mContext, 0, new Intent("test2"), PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(mContext, "channel");
        builder.setContentIntent(intent1);

        Parcel p = Parcel.obtain();

        Notification n1 = builder.build();
        n1.writeToParcel(p, 0);

        builder.setContentIntent(intent2);
        Notification n2 = builder.build();
        n2.writeToParcel(p, 0);

        assertTrue(n2.allPendingIntents.contains(intent2));
    }

    @Test
    public void allPendingIntents_containsCustomRemoteViews() {
        PendingIntent intent = PendingIntent.getActivity(mContext, 0,
                new Intent("test").setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_MUTABLE);

        RemoteViews contentView = new RemoteViews(mContext.getPackageName(), 0 /* layoutId */);
        contentView.setOnClickPendingIntent(1 /* id */, intent);

        Notification.Builder builder = new Notification.Builder(mContext, "channel");
        builder.setCustomContentView(contentView);

        Parcel p = Parcel.obtain();

        Notification n = builder.build();
        n.writeToParcel(p, 0);

        assertTrue(n.allPendingIntents.contains(intent));
    }

    @Test
    public void allPendingIntents_resilientToAnotherNotificationInExtras() {
        PendingIntent contentIntent = createPendingIntent("content");
        PendingIntent actionIntent = createPendingIntent("action");
        Notification another = new Notification.Builder(mContext, "channel").build();
        Bundle bundleContainingAnotherNotification = new Bundle();
        bundleContainingAnotherNotification.putParcelable(null, another);
        Notification source = new Notification.Builder(mContext, "channel")
                .setContentIntent(contentIntent)
                .addAction(new Notification.Action.Builder(null, "action", actionIntent).build())
                .setExtras(bundleContainingAnotherNotification)
                .build();

        Parcel p = Parcel.obtain();
        source.writeToParcel(p, 0);
        p.setDataPosition(0);
        Notification unparceled = new Notification(p);

        assertThat(unparceled.allPendingIntents).containsExactly(contentIntent, actionIntent);
    }

    @Test
    public void allPendingIntents_alsoInPublicVersion() {
        PendingIntent contentIntent = createPendingIntent("content");
        PendingIntent actionIntent = createPendingIntent("action");
        PendingIntent publicContentIntent = createPendingIntent("publicContent");
        PendingIntent publicActionIntent = createPendingIntent("publicAction");
        Notification source = new Notification.Builder(mContext, "channel")
                .setContentIntent(contentIntent)
                .addAction(new Notification.Action.Builder(null, "action", actionIntent).build())
                .setPublicVersion(new Notification.Builder(mContext, "channel")
                        .setContentIntent(publicContentIntent)
                        .addAction(new Notification.Action.Builder(
                                null, "publicAction", publicActionIntent).build())
                        .build())
                .build();

        Parcel p = Parcel.obtain();
        source.writeToParcel(p, 0);
        p.setDataPosition(0);
        Notification unparceled = new Notification(p);

        assertThat(unparceled.allPendingIntents).containsExactly(contentIntent, actionIntent,
                publicContentIntent, publicActionIntent);
        assertThat(unparceled.publicVersion.allPendingIntents).containsExactly(publicContentIntent,
                publicActionIntent);
    }

    @Test
    public void messagingStyle_isGroupConversation() {
        mContext.getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.P;
        Notification.MessagingStyle messagingStyle = new Notification.MessagingStyle("self name")
                .setGroupConversation(true)
                .setConversationTitle("test conversation title");
        Notification notification = new Notification.Builder(mContext, "test id")
                .setSmallIcon(1)
                .setContentTitle("test title")
                .setStyle(messagingStyle)
                .build();

        assertTrue(messagingStyle.isGroupConversation());
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION));
    }

    @Test
    public void messagingStyle_isGroupConversation_noConversationTitle() {
        mContext.getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.P;
        Notification.MessagingStyle messagingStyle = new Notification.MessagingStyle("self name")
                .setGroupConversation(true)
                .setConversationTitle(null);
        Notification notification = new Notification.Builder(mContext, "test id")
                .setSmallIcon(1)
                .setContentTitle("test title")
                .setStyle(messagingStyle)
                .build();

        assertTrue(messagingStyle.isGroupConversation());
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION));
    }

    @Test
    public void messagingStyle_isGroupConversation_withConversationTitle_legacy() {
        // In legacy (version < P), isGroupConversation is controlled by conversationTitle.
        mContext.getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.O;
        Notification.MessagingStyle messagingStyle = new Notification.MessagingStyle("self name")
                .setGroupConversation(false)
                .setConversationTitle("test conversation title");
        Notification notification = new Notification.Builder(mContext, "test id")
                .setSmallIcon(1)
                .setContentTitle("test title")
                .setStyle(messagingStyle)
                .build();

        assertTrue(messagingStyle.isGroupConversation());
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION));
    }

    @Test
    public void messagingStyle_isGroupConversation_withoutConversationTitle_legacy() {
        // In legacy (version < P), isGroupConversation is controlled by conversationTitle.
        mContext.getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.O;
        Notification.MessagingStyle messagingStyle = new Notification.MessagingStyle("self name")
                .setGroupConversation(true)
                .setConversationTitle(null);
        Notification notification = new Notification.Builder(mContext, "test id")
                .setSmallIcon(1)
                .setContentTitle("test title")
                .setStyle(messagingStyle)
                .build();

        assertFalse(messagingStyle.isGroupConversation());
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION));
    }

    @Test
    public void action_builder_hasDefault() {
        Notification.Action action = makeNotificationAction(null);
        assertEquals(Notification.Action.SEMANTIC_ACTION_NONE, action.getSemanticAction());
    }

    @Test
    public void action_builder_setSemanticAction() {
        Notification.Action action = makeNotificationAction(
                builder -> builder.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY));
        assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, action.getSemanticAction());
    }

    @Test
    public void action_parcel() {
        Notification.Action action = writeAndReadParcelable(
                makeNotificationAction(builder -> {
                    builder.setSemanticAction(Notification.Action.SEMANTIC_ACTION_ARCHIVE);
                    builder.setAllowGeneratedReplies(true);
                }));

        assertEquals(Notification.Action.SEMANTIC_ACTION_ARCHIVE, action.getSemanticAction());
        assertTrue(action.getAllowGeneratedReplies());
    }

    @Test
    public void action_clone() {
        Notification.Action action = makeNotificationAction(
                builder -> builder.setSemanticAction(Notification.Action.SEMANTIC_ACTION_DELETE));
        assertEquals(
                Notification.Action.SEMANTIC_ACTION_DELETE,
                action.clone().getSemanticAction());
    }

    @Test
    public void testBuilder_setLocusId() {
        LocusId locusId = new LocusId("4815162342");
        Notification notification = new Notification.Builder(mContext, "whatever")
                .setLocusId(locusId).build();
        assertEquals(locusId, notification.getLocusId());

        Notification clone = writeAndReadParcelable(notification);
        assertEquals(locusId, clone.getLocusId());
    }

    @Test
    public void testBuilder_setLocusId_null() {
        Notification notification = new Notification.Builder(mContext, "whatever")
                .setLocusId(null).build();
        assertNull(notification.getLocusId());

        Notification clone = writeAndReadParcelable(notification);
        assertNull(clone.getLocusId());
    }

    @Test
    public void testBuilder_getFullLengthSpanColor_returnsNullForString() {
        assertThat(Notification.Builder.getFullLengthSpanColor("String")).isNull();
    }

    @Test
    public void testBuilder_getFullLengthSpanColor_returnsNullWithPartialSpan() {
        CharSequence text = new SpannableStringBuilder()
                .append("text with ")
                .append("some red", new ForegroundColorSpan(Color.RED),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertThat(Notification.Builder.getFullLengthSpanColor(text)).isNull();
    }

    @Test
    public void testBuilder_getFullLengthSpanColor_worksWithSingleSpan() {
        CharSequence text = new SpannableStringBuilder()
                .append("text that is all red", new ForegroundColorSpan(Color.RED),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertThat(Notification.Builder.getFullLengthSpanColor(text)).isEqualTo(Color.RED);
    }

    @Test
    public void testBuilder_getFullLengthSpanColor_worksWithFullAndPartialSpans() {
        Spannable text = new SpannableString("blue text with yellow and green");
        text.setSpan(new ForegroundColorSpan(Color.YELLOW), 15, 21,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.BLUE), 0, text.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.GREEN), 26, 31,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertThat(Notification.Builder.getFullLengthSpanColor(text)).isEqualTo(Color.BLUE);
    }

    @Test
    public void testBuilder_getFullLengthSpanColor_worksWithTextAppearance() {
        Spannable text = new SpannableString("title text with yellow and green");
        text.setSpan(new ForegroundColorSpan(Color.YELLOW), 15, 21,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextAppearanceSpan textAppearanceSpan = new TextAppearanceSpan(mContext,
                R.style.TextAppearance_DeviceDefault_Notification_Title);
        int expectedTextColor = textAppearanceSpan.getTextColor().getDefaultColor();
        text.setSpan(textAppearanceSpan, 0, text.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        text.setSpan(new ForegroundColorSpan(Color.GREEN), 26, 31,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertThat(Notification.Builder.getFullLengthSpanColor(text)).isEqualTo(expectedTextColor);
    }



    @Test
    public void testBuilder_ensureButtonFillContrast_adjustsDarker() {
        int background = Color.LTGRAY;
        int foreground = Color.LTGRAY;
        int result = Notification.Builder.ensureButtonFillContrast(foreground, background);
        assertContrastIsWithinRange(result, background, 1.3, 1.5);
        assertThat(ContrastColorUtil.calculateLuminance(result))
                .isLessThan(ContrastColorUtil.calculateLuminance(background));
    }

    @Test
    public void testBuilder_ensureButtonFillContrast_adjustsLighter() {
        int background = Color.DKGRAY;
        int foreground = Color.DKGRAY;
        int result = Notification.Builder.ensureButtonFillContrast(foreground, background);
        assertContrastIsWithinRange(result, background, 1.3, 1.5);
        assertThat(ContrastColorUtil.calculateLuminance(result))
                .isGreaterThan(ContrastColorUtil.calculateLuminance(background));
    }

    @Test
    public void testBuilder_setSilent_summaryBehavior_groupAlertChildren() {
        Notification summaryNotif = new Notification.Builder(mContext, "channelId")
                .setGroupSummary(true)
                .setGroup("groupKey")
                .setSilent(true)
                .build();
        assertEquals(GROUP_ALERT_CHILDREN, summaryNotif.getGroupAlertBehavior());
    }

    @Test
    public void testBuilder_setSilent_childBehavior_groupAlertSummary() {
        Notification childNotif = new Notification.Builder(mContext, "channelId")
                .setGroupSummary(false)
                .setGroup("groupKey")
                .setSilent(true)
                .build();
        assertEquals(GROUP_ALERT_SUMMARY, childNotif.getGroupAlertBehavior());
    }

    @Test
    public void testBuilder_setSilent_emptyGroupKey_groupKeySilent() {
        Notification emptyGroupKeyNotif = new Notification.Builder(mContext, "channelId")
                .setGroup("")
                .setSilent(true)
                .build();
        assertEquals(GROUP_KEY_SILENT, emptyGroupKeyNotif.getGroup());
    }

    @Test
    public void testBuilder_setSilent_vibrateNull() {
        Notification silentNotif = new Notification.Builder(mContext, "channelId")
                .setGroup("")
                .setSilent(true)
                .build();

        assertNull(silentNotif.vibrate);
    }

    @Test
    public void testBuilder_setSilent_soundNull() {
        Notification silentNotif = new Notification.Builder(mContext, "channelId")
                .setGroup("")
                .setSilent(true)
                .build();

        assertNull(silentNotif.sound);
    }

    @Test
    public void testBuilder_setSilent_noDefaultSound() {
        Notification silentNotif = new Notification.Builder(mContext, "channelId")
                .setGroup("")
                .setSilent(true)
                .build();

        assertEquals(0, (silentNotif.defaults & DEFAULT_SOUND));
    }

    @Test
    public void testBuilder_setSilent_noDefaultVibrate() {
        Notification silentNotif = new Notification.Builder(mContext, "channelId")
                .setGroup("")
                .setSilent(true)
                .build();

        assertEquals(0, (silentNotif.defaults & DEFAULT_VIBRATE));
    }

    @Test
    public void testCallStyle_getSystemActions_forIncomingCall() {
        PendingIntent answerIntent = createPendingIntent("answer");
        PendingIntent declineIntent = createPendingIntent("decline");
        Notification.CallStyle style = Notification.CallStyle.forIncomingCall(
                new Person.Builder().setName("A Caller").build(),
                declineIntent,
                answerIntent
        );
        style.setBuilder(new Notification.Builder(mContext, "Channel"));

        List<Notification.Action> actions = style.getActionsListWithSystemActions();

        assertEquals(2, actions.size());
        assertEquals(declineIntent, actions.get(0).actionIntent);
        assertEquals(answerIntent, actions.get(1).actionIntent);
    }

    @Test
    public void testCallStyle_getSystemActions_forOngoingCall() {
        PendingIntent hangUpIntent = createPendingIntent("hangUp");
        Notification.CallStyle style = Notification.CallStyle.forOngoingCall(
                new Person.Builder().setName("A Caller").build(),
                hangUpIntent
        );
        style.setBuilder(new Notification.Builder(mContext, "Channel"));

        List<Notification.Action> actions = style.getActionsListWithSystemActions();

        assertEquals(1, actions.size());
        assertEquals(hangUpIntent, actions.get(0).actionIntent);
    }

    @Test
    public void testCallStyle_getSystemActions_forIncomingCallWithOtherActions() {
        PendingIntent answerIntent = createPendingIntent("answer");
        PendingIntent declineIntent = createPendingIntent("decline");
        Notification.CallStyle style = Notification.CallStyle.forIncomingCall(
                new Person.Builder().setName("A Caller").build(),
                declineIntent,
                answerIntent
        );
        Notification.Action actionToKeep = makeNotificationAction(null);
        Notification.Action actionToDrop = makeNotificationAction(null);
        Notification.Builder notifBuilder = new Notification.Builder(mContext, "Channel")
                .addAction(actionToKeep)
                .addAction(actionToDrop); //expect to move this action to the end
        style.setBuilder(notifBuilder); //add a builder with actions

        List<Notification.Action> actions = style.getActionsListWithSystemActions();

        assertEquals(4, actions.size());
        assertEquals(declineIntent, actions.get(0).actionIntent);
        assertEquals(actionToKeep, actions.get(1));
        assertEquals(answerIntent, actions.get(2).actionIntent);
        assertEquals(actionToDrop, actions.get(3));
    }

    @Test
    public void testCallStyle_getSystemActions_forOngoingCallWithOtherActions() {
        PendingIntent hangUpIntent = createPendingIntent("hangUp");
        Notification.CallStyle style = Notification.CallStyle.forOngoingCall(
                new Person.Builder().setName("A Caller").build(),
                hangUpIntent
        );
        Notification.Action firstAction = makeNotificationAction(null);
        Notification.Action secondAction = makeNotificationAction(null);
        Notification.Builder notifBuilder = new Notification.Builder(mContext, "Channel")
                .addAction(firstAction)
                .addAction(secondAction);
        style.setBuilder(notifBuilder); //add a builder with actions

        List<Notification.Action> actions = style.getActionsListWithSystemActions();

        assertEquals(3, actions.size());
        assertEquals(hangUpIntent, actions.get(0).actionIntent);
        assertEquals(firstAction, actions.get(1));
        assertEquals(secondAction, actions.get(2));
    }

    @Test
    public void testCallStyle_getSystemActions_dropsOldSystemActions() {
        PendingIntent hangUpIntent = createPendingIntent("decline");
        Notification.CallStyle style = Notification.CallStyle.forOngoingCall(
                new Person.Builder().setName("A Caller").build(),
                hangUpIntent
        );
        Bundle actionExtras = new Bundle();
        actionExtras.putBoolean("key_action_priority", true);
        Notification.Action oldSystemAction = makeNotificationAction(
                builder -> builder.addExtras(actionExtras)
        );
        Notification.Builder notifBuilder = new Notification.Builder(mContext, "Channel")
                .addAction(oldSystemAction);
        style.setBuilder(notifBuilder); //add a builder with actions

        List<Notification.Action> actions = style.getActionsListWithSystemActions();

        assertFalse("Old versions of system actions should be dropped.",
                actions.contains(oldSystemAction));
    }

    @Test
    public void testBuild_ensureSmallIconIsNotTooBig_resizesIcon() {
        Icon hugeIcon = Icon.createWithBitmap(
                Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888));
        Notification notification = new Notification.Builder(mContext, "Channel").setSmallIcon(
                hugeIcon).build();

        Bitmap smallNotificationIcon = notification.getSmallIcon().getBitmap();
        assertThat(smallNotificationIcon.getWidth()).isEqualTo(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.notification_small_icon_size));
        assertThat(smallNotificationIcon.getHeight()).isEqualTo(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.notification_small_icon_size));
    }

    @Test
    public void testBuild_ensureMessagingUserIsNotTooBig_resizesIcon() {
        Icon hugeIcon = Icon.createWithBitmap(
                Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888));
        Icon hugeMessageAvatar = Icon.createWithBitmap(
                Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888));
        Icon hugeHistoricMessageAvatar = Icon.createWithBitmap(
                Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888));

        Notification.MessagingStyle style = new Notification.MessagingStyle(
                new Person.Builder().setIcon(hugeIcon).setName("A User").build());
        style.addMessage(new Notification.MessagingStyle.Message("A message", 123456,
                new Person.Builder().setIcon(hugeMessageAvatar).setName("A Sender").build()));
        style.addHistoricMessage(new Notification.MessagingStyle.Message("A message", 123456,
                new Person.Builder().setIcon(hugeHistoricMessageAvatar).setName(
                        "A Historic Sender").build()));
        Notification notification = new Notification.Builder(mContext, "Channel").setStyle(
                style).build();

        int targetSize = mContext.getResources().getDimensionPixelSize(
                ActivityManager.isLowRamDeviceStatic()
                        ? R.dimen.notification_person_icon_max_size_low_ram
                        : R.dimen.notification_person_icon_max_size);

        Bitmap personIcon = style.getUser().getIcon().getBitmap();
        assertThat(personIcon.getWidth()).isEqualTo(targetSize);
        assertThat(personIcon.getHeight()).isEqualTo(targetSize);

        Bitmap avatarIcon = style.getMessages().get(0).getSenderPerson().getIcon().getBitmap();
        assertThat(avatarIcon.getWidth()).isEqualTo(targetSize);
        assertThat(avatarIcon.getHeight()).isEqualTo(targetSize);

        Bitmap historicAvatarIcon = style.getHistoricMessages().get(
                0).getSenderPerson().getIcon().getBitmap();
        assertThat(historicAvatarIcon.getWidth()).isEqualTo(targetSize);
        assertThat(historicAvatarIcon.getHeight()).isEqualTo(targetSize);
    }

    @Test
    public void testBuild_ensureMessagingShortcutIconIsNotTooBig_resizesIcon() {
        Icon hugeIcon = Icon.createWithBitmap(
                Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888));
        Notification.MessagingStyle style = new Notification.MessagingStyle(
                new Person.Builder().setName("A User").build()).setShortcutIcon(hugeIcon);

        Notification notification = new Notification.Builder(mContext, "Channel").setStyle(
                style).build();
        Bitmap shortcutIcon = style.getShortcutIcon().getBitmap();

        assertThat(shortcutIcon.getWidth()).isEqualTo(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.notification_small_icon_size));
        assertThat(shortcutIcon.getHeight()).isEqualTo(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.notification_small_icon_size));
    }

    @Test
    @Ignore // TODO: b/329389261 - Restore or delete
    public void testColors_ensureColors_dayMode_producesValidPalette() {
        Notification.Colors c = new Notification.Colors();
        boolean colorized = false;
        boolean nightMode = false;
        resolveColorsInNightMode(nightMode, c, Color.BLUE, colorized);
        assertValid(c);
    }

    @Test
    public void testColors_ensureColors_nightMode_producesValidPalette() {
        Notification.Colors c = new Notification.Colors();
        boolean colorized = false;
        boolean nightMode = true;
        resolveColorsInNightMode(nightMode, c, Color.BLUE, colorized);
        assertValid(c);
    }

    @Test
    public void testColors_ensureColors_colorized_producesValidPalette_default() {
        validateColorizedPaletteForColor(Notification.COLOR_DEFAULT);
    }

    @Test
    public void testColors_ensureColors_colorized_producesValidPalette_blue() {
        validateColorizedPaletteForColor(Color.BLUE);
    }

    @Test
    @Ignore // TODO: b/329389261 - Restore or delete
    public void testColors_ensureColors_colorized_producesValidPalette_red() {
        validateColorizedPaletteForColor(Color.RED);
    }

    @Test
    public void testColors_ensureColors_colorized_producesValidPalette_white() {
        validateColorizedPaletteForColor(Color.WHITE);
    }

    @Test
    public void testColors_ensureColors_colorized_producesValidPalette_black() {
        validateColorizedPaletteForColor(Color.BLACK);
    }

    @Test
    public void testIsMediaNotification_nullSession_returnsFalse() {
        // Null media session
        Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
        Notification notification = new Notification.Builder(mContext, "test id")
                .setStyle(mediaStyle)
                .build();
        assertFalse(notification.isMediaNotification());
    }

    @Test
    public void testIsMediaNotification_invalidSession_returnsFalse() {
        // Extra was set manually to an invalid type
        Bundle extras = new Bundle();
        extras.putParcelable(EXTRA_MEDIA_SESSION, new Intent());
        Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
        Notification notification = new Notification.Builder(mContext, "test id")
                .setStyle(mediaStyle)
                .addExtras(extras)
                .build();
        assertFalse(notification.isMediaNotification());
    }

    public void validateColorizedPaletteForColor(int rawColor) {
        Notification.Colors cDay = new Notification.Colors();
        Notification.Colors cNight = new Notification.Colors();
        boolean colorized = true;

        resolveColorsInNightMode(false, cDay, rawColor, colorized);
        resolveColorsInNightMode(true, cNight, rawColor, colorized);

        if (rawColor != Notification.COLOR_DEFAULT) {
            assertEquals(rawColor, cDay.getBackgroundColor());
            assertEquals(rawColor, cNight.getBackgroundColor());
        }

        assertValid(cDay);
        assertValid(cNight);

        if (rawColor != Notification.COLOR_DEFAULT) {
            // When a color is provided, night mode should have no effect on the notification
            assertEquals(cDay.getBackgroundColor(), cNight.getBackgroundColor());
            assertEquals(cDay.getPrimaryTextColor(), cNight.getPrimaryTextColor());
            assertEquals(cDay.getSecondaryTextColor(), cNight.getSecondaryTextColor());
            assertEquals(cDay.getPrimaryAccentColor(), cNight.getPrimaryAccentColor());
            assertEquals(cDay.getSecondaryAccentColor(), cNight.getSecondaryAccentColor());
            assertEquals(cDay.getTertiaryAccentColor(), cNight.getTertiaryAccentColor());
            assertEquals(cDay.getOnTertiaryAccentTextColor(),
                    cNight.getOnTertiaryAccentTextColor());
            assertEquals(cDay.getTertiaryFixedDimAccentColor(),
                    cNight.getTertiaryFixedDimAccentColor());
            assertEquals(cDay.getOnTertiaryFixedAccentTextColor(),
                    cNight.getOnTertiaryFixedAccentTextColor());
            assertEquals(cDay.getProtectionColor(), cNight.getProtectionColor());
            assertEquals(cDay.getContrastColor(), cNight.getContrastColor());
            assertEquals(cDay.getRippleAlpha(), cNight.getRippleAlpha());
        }
    }

    @Test
    public void testRecoverBuilder_nullExtraPeopleList_noCrash() {
        Bundle extras = new Bundle();
        extras.putParcelable(EXTRA_PEOPLE_LIST, null);

        Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(0)
                .addExtras(extras)
                .build();
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_BUILDER_APPLICATION_INFO, new Bundle());

        Notification.Builder.recoverBuilder(mContext, n);

        // no crash, good
    }

    @Test
    public void testVisitUris_invalidExtra_noCrash() {
        Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(0)
                .build();
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_LARGE_ICON_BIG, new Bundle());
        fakeTypes.putParcelable(EXTRA_PICTURE_ICON, new Bundle());
        fakeTypes.putParcelable(EXTRA_MESSAGING_PERSON, new Bundle());

        Consumer<Uri> visitor = (Consumer<Uri>) spy(Consumer.class);
        n.visitUris(visitor);

        // no crash, good
    }

    @Test
    public void testRecoverBuilder_invalidExtra_noCrash() {
        Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(0)
                .build();
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_BUILDER_APPLICATION_INFO, new Bundle());

        Notification.Builder.recoverBuilder(mContext, n);

        // no crash, good
    }

    @Test
    public void testIsMediaNotification_invalidExtra_noCrash() {
        Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(0)
                .setStyle(new Notification.MediaStyle())
                .build();
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_MEDIA_SESSION, new Bundle());

        n.isMediaNotification();

        // no crash, good
    }

    @Test
    public void testRestoreFromExtras_BigText_invalidExtra_noCrash() {
        Notification.Style style = new Notification.BigTextStyle();
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_LARGE_ICON_BIG, new Bundle());


        // no crash, good
    }

    @Test
    public void testRestoreFromExtras_Messaging_invalidExtra_noCrash() {
        Notification.Style style = new Notification.MessagingStyle("test");
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_MESSAGING_PERSON, new Bundle());
        fakeTypes.putParcelable(EXTRA_CONVERSATION_ICON, new Bundle());

        Notification n = new Notification.Builder(mContext, "test")
                .setStyle(style)
                .setExtras(fakeTypes)
                .build();
        Notification.Builder.recoverBuilder(mContext, n);

        // no crash, good
    }

    @Test
    public void testRestoreFromExtras_Media_invalidExtra_noCrash() {
        Notification.Style style = new Notification.MediaStyle();
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_MEDIA_SESSION, new Bundle());
        fakeTypes.putParcelable(EXTRA_MEDIA_REMOTE_INTENT, new Bundle());

        Notification n = new Notification.Builder(mContext, "test")
                .setStyle(style)
                .setExtras(fakeTypes)
                .build();
        Notification.Builder.recoverBuilder(mContext, n);

        // no crash, good
    }

    @Test
    public void testRestoreFromExtras_Call_invalidExtra_noCrash() {
        PendingIntent intent1 = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Style style = Notification.CallStyle.forIncomingCall(
                new Person.Builder().setName("hi").build(), intent1, intent1);

        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_CALL_PERSON, new Bundle());
        fakeTypes.putParcelable(EXTRA_ANSWER_INTENT, new Bundle());
        fakeTypes.putParcelable(EXTRA_DECLINE_INTENT, new Bundle());
        fakeTypes.putParcelable(EXTRA_HANG_UP_INTENT, new Bundle());

        Notification n = new Notification.Builder(mContext, "test")
                .setStyle(style)
                .setExtras(fakeTypes)
                .build();
        Notification.Builder.recoverBuilder(mContext, n);
        // no crash, good
    }

    @Test
    public void testGetPictureIcon_invalidExtra_noCrash() {
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_PICTURE, new Bundle());
        fakeTypes.putParcelable(EXTRA_PICTURE_ICON, new Bundle());

        Notification.BigPictureStyle.getPictureIcon(fakeTypes);

        // no crash, good
    }

    @Test
    public void testWearableExtender_invalidExtra_noCrash() {
        Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(0)
                .setStyle(new Notification.MediaStyle())
                .build();
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(KEY_DISPLAY_INTENT, new Bundle());
        fakeTypes.putParcelable(KEY_BACKGROUND, new Bundle());
        Notification.WearableExtender extender = new Notification.WearableExtender(n);

        // no crash, good
    }

    @Test
    public void testCarExtender_invalidExtra_noCrash() {
        Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(0)
                .setStyle(new Notification.MediaStyle())
                .build();
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_LARGE_ICON, new Bundle());
        Notification.CarExtender extender = new Notification.CarExtender(n);

        // no crash, good
    }

    @Test
    public void testTvExtender_invalidExtra_noCrash() {
        Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(0)
                .setStyle(new Notification.MediaStyle())
                .build();
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(EXTRA_CONTENT_INTENT, new Bundle());
        fakeTypes.putParcelable(EXTRA_DELETE_INTENT, new Bundle());
        Notification.TvExtender extender = new Notification.TvExtender(n);

        // no crash, good
    }

    @Test
    public void testGetUnreadConversationFromBundle_invalidExtra_noCrash() {
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(KEY_ON_READ, new Bundle());
        fakeTypes.putParcelable(KEY_ON_REPLY, new Bundle());
        fakeTypes.putParcelable(KEY_REMOTE_INPUT, new Bundle());

        Notification n = new Notification.Builder(mContext, "test")
                .setExtras(fakeTypes)
                .build();
        Notification.CarExtender extender = new Notification.CarExtender(n);

        // no crash, good
    }

    @Test
    public void testGetMessageFromBundle_invalidExtra_noCrash() {
        Bundle fakeTypes = new Bundle();
        fakeTypes.putParcelable(KEY_SENDER_PERSON, new Bundle());
        fakeTypes.putString(KEY_TEXT, "text");
        fakeTypes.putLong(KEY_TIMESTAMP, 0);
        fakeTypes.putParcelable(KEY_REMOTE_INPUT, new Bundle());
        fakeTypes.putParcelable(KEY_DATA_URI, new Bundle());
        Notification.MessagingStyle.Message.getMessageFromBundle(fakeTypes);

        // no crash, good
    }

    @Test
    public void testToBundle_getMessageFromBundle_returnsSameData() {
        Notification.MessagingStyle.Message message =
                new Notification.MessagingStyle.Message(
                        "a", 100, new Person.Builder().setName("hi").build());
        message.setData("text", Uri.parse("http://test/uri"));

        Notification.MessagingStyle.Message convertedMessage =
                Notification.MessagingStyle.Message.getMessageFromBundle(message.toBundle());

        assertThat(convertedMessage).isNotNull();
        assertThat(message.getText()).isEqualTo(convertedMessage.getText());
        assertThat(message.getTimestamp()).isEqualTo(convertedMessage.getTimestamp());
        assertThat(message.getExtras().size()).isEqualTo(convertedMessage.getExtras().size());
        assertThat(message.getSender()).isEqualTo(convertedMessage.getSender());
        assertThat(message.getSenderPerson()).isEqualTo(convertedMessage.getSenderPerson());
        assertThat(message.getDataMimeType()).isEqualTo(convertedMessage.getDataMimeType());
        assertThat(message.getDataUri()).isEqualTo(convertedMessage.getDataUri());
        assertThat(message.isRemoteInputHistory())
                .isEqualTo(convertedMessage.isRemoteInputHistory());
    }

    @Test
    public void testDoesNotStripsExtenders() {
        Notification.Builder nb = new Notification.Builder(mContext, "channel");
        nb.extend(new Notification.CarExtender().setColor(Color.RED));
        nb.extend(new Notification.TvExtender().setChannelId("different channel"));
        nb.extend(new Notification.WearableExtender().setDismissalId("dismiss"));
        Notification before = nb.build();
        Notification after = Notification.Builder.maybeCloneStrippedForDelivery(before);

        assertTrue(before == after);

        Assert.assertEquals("different channel",
                new Notification.TvExtender(before).getChannelId());
        Assert.assertEquals(Color.RED, new Notification.CarExtender(before).getColor());
        Assert.assertEquals("dismiss", new Notification.WearableExtender(before).getDismissalId());
    }

    @Test
    public void areIconsDifferent_sameSmallIcon_false() {
        Notification n1 = new Notification.Builder(mContext, "test").setSmallIcon(1).build();
        Notification n2 = new Notification.Builder(mContext, "test").setSmallIcon(1).build();

        assertThat(Notification.areIconsDifferent(n1, n2)).isFalse();
    }

    @Test
    public void areIconsDifferent_differentSmallIcon_true() {
        Notification n1 = new Notification.Builder(mContext, "test").setSmallIcon(1).build();
        Notification n2 = new Notification.Builder(mContext, "test").setSmallIcon(2).build();

        assertThat(Notification.areIconsDifferent(n1, n2)).isTrue();
    }

    @Test
    public void areIconsDifferent_sameLargeIcon_false() {
        Icon icon1 = Icon.createWithContentUri("uri");
        Icon icon2 = Icon.createWithContentUri("uri");
        Notification n1 = new Notification.Builder(mContext, "test")
                .setSmallIcon(1).setLargeIcon(icon1).build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .setSmallIcon(1).setLargeIcon(icon2).build();

        // Note that this will almost certainly not happen for Icons created from Bitmaps, since
        // their serialization/deserialization of Bitmaps (app -> system_process) results in a
        // different getGenerationId() value. :(
        assertThat(Notification.areIconsDifferent(n1, n2)).isFalse();
    }

    @Test
    public void areIconsDifferent_differentLargeIcon_true() {
        Icon icon1 = Icon.createWithContentUri("uri1");
        Icon icon2 = Icon.createWithContentUri("uri2");
        Notification n1 = new Notification.Builder(mContext, "test")
                .setSmallIcon(1).setLargeIcon(icon1).build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .setSmallIcon(2).setLargeIcon(icon2).build();

        assertThat(Notification.areIconsDifferent(n1, n2)).isTrue();
    }

    @Test
    public void areIconsDifferent_addedLargeIcon_true() {
        Icon icon = Icon.createWithContentUri("uri");
        Notification n1 = new Notification.Builder(mContext, "test").setSmallIcon(1).build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .setSmallIcon(2).setLargeIcon(icon).build();

        assertThat(Notification.areIconsDifferent(n1, n2)).isTrue();
    }

    @Test
    public void areIconsDifferent_removedLargeIcon_true() {
        Icon icon = Icon.createWithContentUri("uri");
        Notification n1 = new Notification.Builder(mContext, "test")
                .setSmallIcon(1).setLargeIcon(icon).build();
        Notification n2 = new Notification.Builder(mContext, "test").setSmallIcon(2).build();

        assertThat(Notification.areIconsDifferent(n1, n2)).isTrue();
    }

    @Test
    public void testStyleChangeVisiblyDifferent_noStyles() {
        Notification.Builder n1 = new Notification.Builder(mContext, "test");
        Notification.Builder n2 = new Notification.Builder(mContext, "test");

        assertFalse(Notification.areStyledNotificationsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testStyleChangeVisiblyDifferent_noStyleToStyle() {
        Notification.Builder n1 = new Notification.Builder(mContext, "test");
        Notification.Builder n2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.BigTextStyle());

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testStyleChangeVisiblyDifferent_styleToNoStyle() {
        Notification.Builder n2 = new Notification.Builder(mContext, "test");
        Notification.Builder n1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.BigTextStyle());

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testStyleChangeVisiblyDifferent_changeStyle() {
        Notification.Builder n1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.InboxStyle());
        Notification.Builder n2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.BigTextStyle());

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testInboxTextChange() {
        Notification.Builder nInbox1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.InboxStyle().addLine("a").addLine("b"));
        Notification.Builder nInbox2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.InboxStyle().addLine("b").addLine("c"));

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(nInbox1, nInbox2));
    }

    @Test
    public void testBigTextTextChange() {
        Notification.Builder nBigText1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.BigTextStyle().bigText("something"));
        Notification.Builder nBigText2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.BigTextStyle().bigText("else"));

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(nBigText1, nBigText2));
    }

    @Test
    public void testBigPictureChange() {
        Bitmap bitA = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Bitmap bitB = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);

        Notification.Builder nBigPic1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.BigPictureStyle().bigPicture(bitA));
        Notification.Builder nBigPic2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.BigPictureStyle().bigPicture(bitB));

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(nBigPic1, nBigPic2));
    }

    @Test
    public void testBigPictureStyle_setExtras_pictureIconNull_noPictureIconKey() {
        Notification.BigPictureStyle bpStyle = new Notification.BigPictureStyle();
        bpStyle.bigPicture((Bitmap) null);

        Bundle extras = new Bundle();
        bpStyle.addExtras(extras);

        assertThat(extras.containsKey(EXTRA_PICTURE_ICON)).isFalse();
    }

    @Test
    public void testBigPictureStyle_setExtras_pictureIconNull_noPictureKey() {
        Notification.BigPictureStyle bpStyle = new Notification.BigPictureStyle();
        bpStyle.bigPicture((Bitmap) null);

        Bundle extras = new Bundle();
        bpStyle.addExtras(extras);

        assertThat(extras.containsKey(EXTRA_PICTURE)).isFalse();
    }

    @Test
    public void testBigPictureStyle_setExtras_pictureIconTypeBitmap_pictureIconKeyNull() {
        Bitmap bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Notification.BigPictureStyle bpStyle = new Notification.BigPictureStyle();
        bpStyle.bigPicture(bitmap);

        Bundle extras = new Bundle();
        bpStyle.addExtras(extras);

        assertThat(extras.containsKey(EXTRA_PICTURE_ICON)).isTrue();
        final Parcelable pictureIcon = extras.getParcelable(EXTRA_PICTURE_ICON);
        assertThat(pictureIcon).isNull();
    }

    @Test
    public void testBigPictureStyle_setExtras_pictureIconTypeIcon_pictureKeyNull() {
        Icon icon = Icon.createWithResource(mContext, R.drawable.btn_plus);
        Notification.BigPictureStyle bpStyle = new Notification.BigPictureStyle();
        bpStyle.bigPicture(icon);

        Bundle extras = new Bundle();
        bpStyle.addExtras(extras);

        assertThat(extras.containsKey(EXTRA_PICTURE)).isTrue();
        final Parcelable picture = extras.getParcelable(EXTRA_PICTURE);
        assertThat(picture).isNull();
    }

    @Test
    public void testMessagingChange_text() {
        Notification.Builder nM1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message(
                                "a", 100, new Person.Builder().setName("hi").build())));
        Notification.Builder nM2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message(
                                "a", 100, new Person.Builder().setName("hi").build()))
                        .addMessage(new Notification.MessagingStyle.Message(
                                "b", 100, new Person.Builder().setName("hi").build()))
                );

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(nM1, nM2));
    }

    @Test
    public void testMessagingChange_data() {
        Notification.Builder nM1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message(
                                "a", 100, new Person.Builder().setName("hi").build())
                                .setData("text", mock(Uri.class))));
        Notification.Builder nM2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message(
                                "a", 100, new Person.Builder().setName("hi").build())));

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(nM1, nM2));
    }

    @Test
    public void testMessagingChange_sender() {
        Person a = new Person.Builder().setName("A").build();
        Person b = new Person.Builder().setName("b").build();
        Notification.Builder nM1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message("a", 100, b)));
        Notification.Builder nM2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message("a", 100, a)));

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(nM1, nM2));
    }

    @Test
    public void testMessagingChange_key() {
        Person a = new Person.Builder().setName("hi").setKey("A").build();
        Person b = new Person.Builder().setName("hi").setKey("b").build();
        Notification.Builder nM1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message("a", 100, a)));
        Notification.Builder nM2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message("a", 100, b)));

        assertTrue(Notification.areStyledNotificationsVisiblyDifferent(nM1, nM2));
    }

    @Test
    public void testMessagingChange_ignoreTimeChange() {
        Notification.Builder nM1 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message(
                                "a", 100, new Person.Builder().setName("hi").build())));
        Notification.Builder nM2 = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle("")
                        .addMessage(new Notification.MessagingStyle.Message(
                                "a", 1000, new Person.Builder().setName("hi").build()))
                );

        assertFalse(Notification.areStyledNotificationsVisiblyDifferent(nM1, nM2));
    }

    @Test
    public void testRemoteViews_nullChange() {
        Notification.Builder n1 = new Notification.Builder(mContext, "test")
                .setContent(mock(RemoteViews.class));
        Notification.Builder n2 = new Notification.Builder(mContext, "test");
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test");
        n2 = new Notification.Builder(mContext, "test")
                .setContent(mock(RemoteViews.class));
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test")
                .setCustomBigContentView(mock(RemoteViews.class));
        n2 = new Notification.Builder(mContext, "test");
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test");
        n2 = new Notification.Builder(mContext, "test")
                .setCustomBigContentView(mock(RemoteViews.class));
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test");
        n2 = new Notification.Builder(mContext, "test");
        assertFalse(Notification.areRemoteViewsChanged(n1, n2));
    }

    @Test
    public void testRemoteViews_layoutChange() {
        RemoteViews a = mock(RemoteViews.class);
        when(a.getLayoutId()).thenReturn(234);
        RemoteViews b = mock(RemoteViews.class);
        when(b.getLayoutId()).thenReturn(189);

        Notification.Builder n1 = new Notification.Builder(mContext, "test").setContent(a);
        Notification.Builder n2 = new Notification.Builder(mContext, "test").setContent(b);
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test").setCustomBigContentView(a);
        n2 = new Notification.Builder(mContext, "test").setCustomBigContentView(b);
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test").setCustomHeadsUpContentView(a);
        n2 = new Notification.Builder(mContext, "test").setCustomHeadsUpContentView(b);
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));
    }

    @Test
    public void testRemoteViews_layoutSame() {
        RemoteViews a = mock(RemoteViews.class);
        when(a.getLayoutId()).thenReturn(234);
        RemoteViews b = mock(RemoteViews.class);
        when(b.getLayoutId()).thenReturn(234);

        Notification.Builder n1 = new Notification.Builder(mContext, "test").setContent(a);
        Notification.Builder n2 = new Notification.Builder(mContext, "test").setContent(b);
        assertFalse(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test").setCustomBigContentView(a);
        n2 = new Notification.Builder(mContext, "test").setCustomBigContentView(b);
        assertFalse(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test").setCustomHeadsUpContentView(a);
        n2 = new Notification.Builder(mContext, "test").setCustomHeadsUpContentView(b);
        assertFalse(Notification.areRemoteViewsChanged(n1, n2));
    }

    @Test
    public void testRemoteViews_sequenceChange() {
        RemoteViews a = mock(RemoteViews.class);
        when(a.getLayoutId()).thenReturn(234);
        when(a.getSequenceNumber()).thenReturn(1);
        RemoteViews b = mock(RemoteViews.class);
        when(b.getLayoutId()).thenReturn(234);
        when(b.getSequenceNumber()).thenReturn(2);

        Notification.Builder n1 = new Notification.Builder(mContext, "test").setContent(a);
        Notification.Builder n2 = new Notification.Builder(mContext, "test").setContent(b);
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test").setCustomBigContentView(a);
        n2 = new Notification.Builder(mContext, "test").setCustomBigContentView(b);
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test").setCustomHeadsUpContentView(a);
        n2 = new Notification.Builder(mContext, "test").setCustomHeadsUpContentView(b);
        assertTrue(Notification.areRemoteViewsChanged(n1, n2));
    }

    @Test
    public void testRemoteViews_sequenceSame() {
        RemoteViews a = mock(RemoteViews.class);
        when(a.getLayoutId()).thenReturn(234);
        when(a.getSequenceNumber()).thenReturn(1);
        RemoteViews b = mock(RemoteViews.class);
        when(b.getLayoutId()).thenReturn(234);
        when(b.getSequenceNumber()).thenReturn(1);

        Notification.Builder n1 = new Notification.Builder(mContext, "test").setContent(a);
        Notification.Builder n2 = new Notification.Builder(mContext, "test").setContent(b);
        assertFalse(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test").setCustomBigContentView(a);
        n2 = new Notification.Builder(mContext, "test").setCustomBigContentView(b);
        assertFalse(Notification.areRemoteViewsChanged(n1, n2));

        n1 = new Notification.Builder(mContext, "test").setCustomHeadsUpContentView(a);
        n2 = new Notification.Builder(mContext, "test").setCustomHeadsUpContentView(b);
        assertFalse(Notification.areRemoteViewsChanged(n1, n2));
    }

    @Test
    public void testActionsDifferent_null() {
        Notification n1 = new Notification.Builder(mContext, "test")
                .build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .build();

        assertFalse(Notification.areActionsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testActionsDifferentSame() {
        PendingIntent intent = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);;
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));

        Notification n1 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent).build())
                .build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent).build())
                .build();

        assertFalse(Notification.areActionsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testActionsDifferentText() {
        PendingIntent intent = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);;
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));

        Notification n1 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent).build())
                .build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 2", intent).build())
                .build();

        assertTrue(Notification.areActionsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testActionsDifferentSpannables() {
        PendingIntent intent = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);;
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));

        Notification n1 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon,
                        new SpannableStringBuilder().append("test1",
                                new StyleSpan(Typeface.BOLD),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE),
                        intent).build())
                .build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "test1", intent).build())
                .build();

        assertFalse(Notification.areActionsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testActionsDifferentNumber() {
        PendingIntent intent = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));

        Notification n1 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent).build())
                .build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent).build())
                .addAction(new Notification.Action.Builder(icon, "TEXT 2", intent).build())
                .build();

        assertTrue(Notification.areActionsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testActionsDifferentIntent() {
        PendingIntent intent1 = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent intent2 = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));

        Notification n1 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent1).build())
                .build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent2).build())
                .build();

        assertFalse(Notification.areActionsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testActionsIgnoresRemoteInputs() {
        PendingIntent intent = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);;
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));

        Notification n1 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent)
                        .addRemoteInput(new RemoteInput.Builder("a")
                                .setChoices(new CharSequence[] {"i", "m"})
                                .build())
                        .build())
                .build();
        Notification n2 = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent)
                        .addRemoteInput(new RemoteInput.Builder("a")
                                .setChoices(new CharSequence[] {"t", "m"})
                                .build())
                        .build())
                .build();

        assertFalse(Notification.areActionsVisiblyDifferent(n1, n2));
    }

    @Test
    public void testFreeformRemoteInputActionPair_noRemoteInput() {
        PendingIntent intent = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);;
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));
        Notification notification = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent)
                        .build())
                .build();
        Assert.assertNull(notification.findRemoteInputActionPair(false));
    }

    @Test
    public void testFreeformRemoteInputActionPair_hasRemoteInput() {
        PendingIntent intent = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);;
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));

        RemoteInput remoteInput = new RemoteInput.Builder("a").build();

        Notification.Action actionWithRemoteInput =
                new Notification.Action.Builder(icon, "TEXT 1", intent)
                        .addRemoteInput(remoteInput)
                        .addRemoteInput(remoteInput)
                        .build();

        Notification.Action actionWithoutRemoteInput =
                new Notification.Action.Builder(icon, "TEXT 2", intent)
                        .build();

        Notification notification = new Notification.Builder(mContext, "test")
                .addAction(actionWithoutRemoteInput)
                .addAction(actionWithRemoteInput)
                .build();

        Pair<RemoteInput, Notification.Action> remoteInputActionPair =
                notification.findRemoteInputActionPair(false);

        assertNotNull(remoteInputActionPair);
        Assert.assertEquals(remoteInput, remoteInputActionPair.first);
        Assert.assertEquals(actionWithRemoteInput, remoteInputActionPair.second);
    }

    @Test
    public void testFreeformRemoteInputActionPair_requestFreeform_noFreeformRemoteInput() {
        PendingIntent intent = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);;
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));
        Notification notification = new Notification.Builder(mContext, "test")
                .addAction(new Notification.Action.Builder(icon, "TEXT 1", intent)
                        .addRemoteInput(
                                new RemoteInput.Builder("a")
                                        .setAllowFreeFormInput(false).build())
                        .build())
                .build();
        Assert.assertNull(notification.findRemoteInputActionPair(true));
    }

    @Test
    public void testFreeformRemoteInputActionPair_requestFreeform_hasFreeformRemoteInput() {
        PendingIntent intent = PendingIntent.getActivity(
                mContext, 0, new Intent("test1"), PendingIntent.FLAG_IMMUTABLE);;
        Icon icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888));

        RemoteInput remoteInput =
                new RemoteInput.Builder("a").setAllowFreeFormInput(false).build();
        RemoteInput freeformRemoteInput =
                new RemoteInput.Builder("b").setAllowFreeFormInput(true).build();

        Notification.Action actionWithFreeformRemoteInput =
                new Notification.Action.Builder(icon, "TEXT 1", intent)
                        .addRemoteInput(remoteInput)
                        .addRemoteInput(freeformRemoteInput)
                        .build();

        Notification.Action actionWithoutFreeformRemoteInput =
                new Notification.Action.Builder(icon, "TEXT 2", intent)
                        .addRemoteInput(remoteInput)
                        .build();

        Notification notification = new Notification.Builder(mContext, "test")
                .addAction(actionWithoutFreeformRemoteInput)
                .addAction(actionWithFreeformRemoteInput)
                .build();

        Pair<RemoteInput, Notification.Action> remoteInputActionPair =
                notification.findRemoteInputActionPair(true);

        assertNotNull(remoteInputActionPair);
        Assert.assertEquals(freeformRemoteInput, remoteInputActionPair.first);
        Assert.assertEquals(actionWithFreeformRemoteInput, remoteInputActionPair.second);
    }

    // Ensures that extras in a Notification Builder can be updated.
    @Test
    public void testExtras_cachedExtrasOverwrittenByUserProvided() {
        // Sets the flag to new state.
        // TODO(b/169435530): remove this set value once resolved.
        SystemProperties.set("persist.sysui.notification.builder_extras_override",
                Boolean.toString(true));
        Bundle extras = new Bundle();
        extras.putCharSequence(EXTRA_TITLE, "test title");
        extras.putCharSequence(EXTRA_SUMMARY_TEXT, "summary text");

        Notification.Builder builder = new Notification.Builder(mContext, "test id")
                .addExtras(extras);

        Notification notification = builder.build();
        assertThat(notification.extras.getCharSequence(EXTRA_TITLE).toString()).isEqualTo(
                "test title");
        assertThat(notification.extras.getCharSequence(EXTRA_SUMMARY_TEXT).toString()).isEqualTo(
                "summary text");

        extras.putCharSequence(EXTRA_TITLE, "new title");
        builder.addExtras(extras);
        notification = builder.build();
        assertThat(notification.extras.getCharSequence(EXTRA_TITLE).toString()).isEqualTo(
                "new title");
        assertThat(notification.extras.getCharSequence(EXTRA_SUMMARY_TEXT).toString()).isEqualTo(
                "summary text");
    }

    // Ensures that extras in a Notification Builder can be updated by an extender.
    @Test
    public void testExtras_cachedExtrasOverwrittenByExtender() {
        // Sets the flag to new state.
        // TODO(b/169435530): remove this set value once resolved.
        SystemProperties.set("persist.sysui.notification.builder_extras_override",
                Boolean.toString(true));
        Notification.CarExtender extender = new Notification.CarExtender().setColor(1234);

        Notification notification = new Notification.Builder(mContext, "test id")
                .extend(extender).build();

        extender.setColor(5678);

        Notification.Builder.recoverBuilder(mContext, notification).extend(extender).build();

        Notification.CarExtender recoveredExtender = new Notification.CarExtender(notification);
        assertThat(recoveredExtender.getColor()).isEqualTo(5678);
    }

    // Validates pre-flag flip behavior, that extras in a Notification Builder cannot be updated.
    // TODO(b/169435530): remove this test once resolved.
    @Test
    public void testExtras_cachedExtrasOverwrittenByUserProvidedOld() {
        // Sets the flag to old state.
        SystemProperties.set("persist.sysui.notification.builder_extras_override",
                Boolean.toString(false));

        Bundle extras = new Bundle();
        extras.putCharSequence(EXTRA_TITLE, "test title");
        extras.putCharSequence(EXTRA_SUMMARY_TEXT, "summary text");

        Notification.Builder builder = new Notification.Builder(mContext, "test id")
                .addExtras(extras);

        Notification notification = builder.build();
        assertThat(notification.extras.getCharSequence(EXTRA_TITLE).toString()).isEqualTo(
                "test title");
        assertThat(notification.extras.getCharSequence(EXTRA_SUMMARY_TEXT).toString()).isEqualTo(
                "summary text");

        extras.putCharSequence(EXTRA_TITLE, "new title");
        builder.addExtras(extras);
        notification = builder.build();
        assertThat(notification.extras.getCharSequence(EXTRA_TITLE).toString()).isEqualTo(
                "test title");
        assertThat(notification.extras.getCharSequence(EXTRA_SUMMARY_TEXT).toString()).isEqualTo(
                "summary text");
    }

    // Validates pre-flag flip behavior, that extras in a Notification Builder cannot be updated
    // by an extender.
    // TODO(b/169435530): remove this test once resolved.
    @Test
    public void testExtras_cachedExtrasOverwrittenByExtenderOld() {
        // Sets the flag to old state.
        SystemProperties.set("persist.sysui.notification.builder_extras_override",
                Boolean.toString(false));

        Notification.CarExtender extender = new Notification.CarExtender().setColor(1234);

        Notification notification = new Notification.Builder(mContext, "test id")
                .extend(extender).build();

        extender.setColor(5678);

        Notification.Builder.recoverBuilder(mContext, notification).extend(extender).build();

        Notification.CarExtender recoveredExtender = new Notification.CarExtender(notification);
        assertThat(recoveredExtender.getColor()).isEqualTo(1234);
    }

    @Test
    @CoreCompatChangeRule.EnableCompatChanges({Notification.WEARABLE_EXTENDER_BACKGROUND_BLOCKED})
    public void wearableBackgroundBlockEnabled_wearableBackgroundSet_valueRemainsNull() {
        Notification.WearableExtender extender = new Notification.WearableExtender();
        Bitmap bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
        extender.setBackground(bitmap);
        Notification notif =
                new Notification.Builder(mContext, "test id")
                        .setSmallIcon(1)
                        .setContentTitle("test_title")
                        .extend(extender)
                        .build();

        Notification.WearableExtender result = new Notification.WearableExtender(notif);
        Assert.assertNull(result.getBackground());
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges({Notification.WEARABLE_EXTENDER_BACKGROUND_BLOCKED})
    public void wearableBackgroundBlockDisabled_wearableBackgroundSet_valueKeepsBitmap() {
        Notification.WearableExtender extender = new Notification.WearableExtender();
        Bitmap bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
        extender.setBackground(bitmap);
        Notification notif =
                new Notification.Builder(mContext, "test id")
                        .setSmallIcon(1)
                        .setContentTitle("test_title")
                        .extend(extender)
                        .build();

        Notification.WearableExtender result = new Notification.WearableExtender(notif);
        Bitmap resultBitmap = result.getBackground();
        assertNotNull(resultBitmap);
        Assert.assertEquals(bitmap, resultBitmap);
    }

    @Test
    public void testGetWhen_zero() {
        Notification n = new Notification.Builder(mContext, "test")
                .setWhen(0)
                .build();

        mSetFlagsRule.disableFlags(Flags.FLAG_SORT_SECTION_BY_TIME);

        assertThat(n.getWhen()).isEqualTo(0);

        mSetFlagsRule.enableFlags(Flags.FLAG_SORT_SECTION_BY_TIME);

        assertThat(n.getWhen()).isEqualTo(n.creationTime);
    }

    @Test
    public void testGetWhen_devProvidedNonZero() {
        Notification n = new Notification.Builder(mContext, "test")
                .setWhen(9)
                .build();

        mSetFlagsRule.disableFlags(Flags.FLAG_SORT_SECTION_BY_TIME);

        assertThat(n.getWhen()).isEqualTo(9);

        mSetFlagsRule.enableFlags(Flags.FLAG_SORT_SECTION_BY_TIME);

        assertThat(n.getWhen()).isEqualTo(9);
    }

    private void assertValid(Notification.Colors c) {
        // Assert that all colors are populated
        assertThat(c.getBackgroundColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getProtectionColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getPrimaryTextColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getSecondaryTextColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getPrimaryAccentColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getSecondaryAccentColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getTertiaryAccentColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getOnTertiaryAccentTextColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getTertiaryFixedDimAccentColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getOnTertiaryFixedAccentTextColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getErrorColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getContrastColor()).isNotEqualTo(Notification.COLOR_INVALID);
        assertThat(c.getRippleAlpha()).isAtLeast(0x00);
        assertThat(c.getRippleAlpha()).isAtMost(0xff);

        // Assert that various colors have sufficient contrast with the background
        assertContrastIsAtLeast(c.getPrimaryTextColor(), c.getBackgroundColor(), 4.5);
        assertContrastIsAtLeast(c.getSecondaryTextColor(), c.getBackgroundColor(), 4.5);
        assertContrastIsAtLeast(c.getPrimaryAccentColor(), c.getBackgroundColor(), 4.5);
        assertContrastIsAtLeast(c.getErrorColor(), c.getBackgroundColor(), 4.5);
        assertContrastIsAtLeast(c.getContrastColor(), c.getBackgroundColor(), 4.5);

        // These colors are only used for emphasized buttons; they do not need contrast
        assertContrastIsAtLeast(c.getSecondaryAccentColor(), c.getBackgroundColor(), 1);
        assertContrastIsAtLeast(c.getTertiaryAccentColor(), c.getBackgroundColor(), 1);
        assertContrastIsAtLeast(c.getTertiaryFixedDimAccentColor(), c.getBackgroundColor(), 1);

        // The text that is used within the accent color DOES need to have contrast
        assertContrastIsAtLeast(c.getOnTertiaryAccentTextColor(), c.getTertiaryAccentColor(), 4.5);
        assertContrastIsAtLeast(c.getOnTertiaryFixedAccentTextColor(),
                c.getTertiaryFixedDimAccentColor(), 4.5);
    }

    private void resolveColorsInNightMode(boolean nightMode, Notification.Colors c, int rawColor,
            boolean colorized) {
        runInNightMode(nightMode,
                () -> c.resolvePalette(mContext, rawColor, colorized, nightMode));
    }

    private void runInNightMode(boolean nightMode, Runnable task) {
        final String initialNightMode = changeNightMode(nightMode);
        try {
            Configuration currentConfig = mContext.getResources().getConfiguration();
            boolean isNightMode = (currentConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            assertEquals(nightMode, isNightMode);
            task.run();
        } finally {
            runShellCommand("cmd uimode night " + initialNightMode);
        }
    }


    // Change the night mode and return the previous mode
    private String changeNightMode(boolean nightMode) {
        final String nightModeText = runShellCommand("cmd uimode night");
        final String[] nightModeSplit = nightModeText.split(":");
        if (nightModeSplit.length != 2) {
            fail("Failed to get initial night mode value from " + nightModeText);
        }
        String previousMode = nightModeSplit[1].trim();
        runShellCommand("cmd uimode night " + (nightMode ? "yes" : "no"));
        return previousMode;
    }

    /**
      * Writes an arbitrary {@link Parcelable} into a {@link Parcel} using its writeToParcel
      * method before reading it out again to check that it was sent properly.
      */
    private static <T extends Parcelable> T writeAndReadParcelable(T original) {
        Parcel p = Parcel.obtain();
        p.writeParcelable(original, /* flags */ 0);
        p.setDataPosition(0);
        return p.readParcelable(/* classLoader */ null);
    }

    /**
     * Creates a Notification.Action by mocking initial dependencies and then applying
     * transformations if they're defined.
     */
    private Notification.Action makeNotificationAction(
            @Nullable Consumer<Notification.Action.Builder> transformation) {
        Notification.Action.Builder actionBuilder =
                new Notification.Action.Builder(null, "Test Title", null);
        if (transformation != null) {
            transformation.accept(actionBuilder);
        }
        return actionBuilder.build();
    }

    /**
     * Creates a PendingIntent with the given action.
     */
    private PendingIntent createPendingIntent(String action) {
        return PendingIntent.getActivity(mContext, 0,
                new Intent(action).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_MUTABLE);
    }
}
