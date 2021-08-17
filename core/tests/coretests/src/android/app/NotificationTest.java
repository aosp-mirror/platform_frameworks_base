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

import static androidx.core.graphics.ColorUtils.calculateContrast;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.widget.RemoteViews;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
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
        assertSame(q.getLargeIcon(), q.extras.getParcelable(Notification.EXTRA_LARGE_ICON));
    }

    @Test
    public void largeIconReferenceInExtrasOnly_keptAfterParcelling() {
        Icon originalIcon = Icon.createWithBitmap(BitmapFactory.decodeResource(
                mContext.getResources(), com.android.frameworks.coretests.R.drawable.test128x96));

        Notification n = new Notification.Builder(mContext).build();
        n.extras.putParcelable(Notification.EXTRA_LARGE_ICON, originalIcon);
        assertSame(n.getLargeIcon(), null);

        Notification q = writeAndReadParcelable(n);
        assertSame(q.getLargeIcon(), null);
        assertTrue(((Icon) q.extras.getParcelable(Notification.EXTRA_LARGE_ICON)).getBitmap()
                .sameAs(originalIcon.getBitmap()));
    }

    @Test
    public void allPendingIntents_recollectedAfterReusingBuilder() {
        PendingIntent intent1 = PendingIntent.getActivity(mContext, 0, new Intent("test1"), PendingIntent.FLAG_MUTABLE_UNAUDITED);
        PendingIntent intent2 = PendingIntent.getActivity(mContext, 0, new Intent("test2"), PendingIntent.FLAG_MUTABLE_UNAUDITED);

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
        PendingIntent intent = PendingIntent.getActivity(mContext, 0, new Intent("test"), PendingIntent.FLAG_MUTABLE_UNAUDITED);

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
            assertEquals(cDay.getOnAccentTextColor(), cNight.getOnAccentTextColor());
            assertEquals(cDay.getProtectionColor(), cNight.getProtectionColor());
            assertEquals(cDay.getContrastColor(), cNight.getContrastColor());
            assertEquals(cDay.getRippleAlpha(), cNight.getRippleAlpha());
        }
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
        assertThat(c.getOnAccentTextColor()).isNotEqualTo(Notification.COLOR_INVALID);
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

        // The text that is used within the accent color DOES need to have contrast
        assertContrastIsAtLeast(c.getOnAccentTextColor(), c.getTertiaryAccentColor(), 4.5);
    }

    private void assertContrastIsAtLeast(int foreground, int background, double minContrast) {
        try {
            assertThat(calculateContrast(foreground, background)).isAtLeast(minContrast);
        } catch (AssertionError e) {
            throw new AssertionError(
                    String.format("Insufficient contrast: foreground=#%08x background=#%08x",
                            foreground, background), e);
        }
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
}
