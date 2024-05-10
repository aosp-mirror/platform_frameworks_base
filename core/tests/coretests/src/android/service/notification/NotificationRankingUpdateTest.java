/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.spy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SharedMemory;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(Parameterized.class)
public class NotificationRankingUpdateTest {

    private static final String NOTIFICATION_CHANNEL_ID = "test_channel_id";
    private static final String TEST_KEY = "key";

    private NotificationChannel mNotificationChannel;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    // TODO(b/284297289): remove this flag set once resolved.
    @Parameterized.Parameters(name = "rankingUpdateAshmem={0}")
    public static Boolean[] getRankingUpdateAshmem() {
        return new Boolean[] { true, false };
    }

    @Parameterized.Parameter
    public boolean mRankingUpdateAshmem;

    @Rule
    public TestableContext mContext =
            spy(new TestableContext(InstrumentationRegistry.getContext(), null));

    protected TestableContext getContext() {
        return mContext;
    }

    public static String[] mKeys = new String[] { "key", "key1", "key2", "key3", "key4"};

    /**
     * Creates a NotificationRankingUpdate with prepopulated Ranking entries
     * @param context A testable context, used for PendingIntent creation
     * @return The NotificationRankingUpdate to be used as test data
     */
    public static NotificationRankingUpdate generateUpdate(TestableContext context) {
        NotificationListenerService.Ranking[] rankings =
                new NotificationListenerService.Ranking[mKeys.length];
        for (int i = 0; i < mKeys.length; i++) {
            final String key = mKeys[i];
            NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
            ranking.populate(
                    key,
                    i,
                    !isIntercepted(i),
                    getVisibilityOverride(i),
                    getSuppressedVisualEffects(i),
                    getImportance(i),
                    getExplanation(key),
                    getOverrideGroupKey(key),
                    getChannel(key, i),
                    getPeople(key, i),
                    getSnoozeCriteria(key, i),
                    getShowBadge(i),
                    getUserSentiment(i),
                    getHidden(i),
                    lastAudiblyAlerted(i),
                    getNoisy(i),
                    getSmartActions(key, i, context),
                    getSmartReplies(key, i),
                    canBubble(i),
                    isTextChanged(i),
                    isConversation(i),
                    getShortcutInfo(i),
                    getRankingAdjustment(i),
                    isBubble(i),
                    getProposedImportance(i),
                    hasSensitiveContent(i)
            );
            rankings[i] = ranking;
        }
        return new NotificationRankingUpdate(rankings);
    }

    /**
     * Produces a visibility override value based on the provided index.
     */
    public static int getVisibilityOverride(int index) {
        return index * 9;
    }

    /**
     * Produces a group key based on the provided key.
     */
    public static String getOverrideGroupKey(String key) {
        return key + key;
    }

    /**
     * Produces a boolean that can be used to represent isIntercepted, based on the provided index.
     */
    public static boolean isIntercepted(int index) {
        return index % 2 == 0;
    }

    /**
     * Produces a suppressed visual effects value based on the provided index
     */
    public static int getSuppressedVisualEffects(int index) {
        return index * 2;
    }

    /**
     * Produces an importance value, based on the provided index
     */
    public static int getImportance(int index) {
        return index;
    }

    /**
     * Produces an explanation value, based on the provided key
     */
    public static String getExplanation(String key) {
        return key + "explain";
    }

    /**
     * Produces a notification channel, based on the provided key and index
     */
    public static NotificationChannel getChannel(String key, int index) {
        return new NotificationChannel(key, key, getImportance(index));
    }

    /**
     * Produces a boolean that can be used to represent showBadge, based on the provided index
     */
    public static boolean getShowBadge(int index) {
        return index % 3 == 0;
    }

    /**
     * Produces a user sentiment value, based on the provided index
     */
    public static int getUserSentiment(int index) {
        switch(index % 3) {
            case 0:
                return USER_SENTIMENT_NEGATIVE;
            case 1:
                return USER_SENTIMENT_NEUTRAL;
            case 2:
                return USER_SENTIMENT_POSITIVE;
        }
        return USER_SENTIMENT_NEUTRAL;
    }

    /**
     * Produces a boolean that can be used to represent "hidden," based on the provided index.
     */
    public static boolean getHidden(int index) {
        return index % 2 == 0;
    }

    /**
     * Produces a long to represent lastAudiblyAlerted based on the provided index.
     */
    public static long lastAudiblyAlerted(int index) {
        return index * 2000L;
    }

    /**
     * Produces a boolean that can be used to represent "noisy," based on the provided index.
     */
    public static boolean getNoisy(int index) {
        return index < 1;
    }

    /**
     * Produces strings that can be used to represent people, based on the provided key and index.
     */
    public static ArrayList<String> getPeople(String key, int index) {
        ArrayList<String> people = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            people.add(i + key);
        }
        return people;
    }

    /**
     * Produces a number of snoozeCriteria, based on the provided key and index.
     */
    public static ArrayList<SnoozeCriterion> getSnoozeCriteria(String key, int index) {
        ArrayList<SnoozeCriterion> snooze = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            snooze.add(new SnoozeCriterion(key + i, getExplanation(key), key));
        }
        return snooze;
    }

    /**
     * Produces a list of Actions which can be used to represent smartActions.
     * These actions are built from pending intents with intent titles based on the provided
     * key, and ids based on the provided index.
     */
    public static ArrayList<Notification.Action> getSmartActions(String key,
                                                                 int index,
                                                                 TestableContext context) {
        ArrayList<Notification.Action> actions = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            PendingIntent intent = PendingIntent.getBroadcast(
                    context,
                    index /*requestCode*/,
                    new Intent("ACTION_" + key),
                    PendingIntent.FLAG_IMMUTABLE /*flags*/);
            actions.add(new Notification.Action.Builder(null /*icon*/, key, intent).build());
        }
        return actions;
    }

    /**
     * Produces index number of "smart replies," all based on the provided key and index
     */
    public static ArrayList<CharSequence> getSmartReplies(String key, int index) {
        ArrayList<CharSequence> choices = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            choices.add("choice_" + key + "_" + i);
        }
        return choices;
    }

    /**
     * Produces a boolean that can be  used to represent canBubble, based on the provided index
     */
    public static boolean canBubble(int index) {
        return index % 4 == 0;
    }

    /**
     * Produces a boolean that can be used to represent isTextChanged, based on the provided index.
     */
    public static boolean isTextChanged(int index) {
        return index % 4 == 0;
    }

    /**
     * Produces a boolean that can be used to represent isConversation, based on the provided index.
     */
    public static boolean isConversation(int index) {
        return index % 4 == 0;
    }

    /**
     * Produces a ShortcutInfo value based on the provided index.
     */
    public static ShortcutInfo getShortcutInfo(int index) {
        ShortcutInfo si = new ShortcutInfo(
                index, String.valueOf(index), "packageName", new ComponentName("1", "1"), null,
                "title", 0, "titleResName", "text", 0, "textResName",
                "disabledMessage", 0, "disabledMessageResName",
                null, null, 0, null, 0, 0,
                0, "iconResName", "bitmapPath", null, 0,
                null, null, null, null);
        return si;
    }

    /**
     * Produces a rankingAdjustment value, based on the provided index.
     */
    public static int getRankingAdjustment(int index) {
        return index % 3 - 1;
    }

    /**
     * Produces a proposedImportance, based on the provided index.
     */
    public static int getProposedImportance(int index) {
        return index % 5 - 1;
    }

    /**
     * Produces a boolean that can be used to represent hasSensitiveContent, based on the provided
     * index.
     */
    public static boolean hasSensitiveContent(int index) {
        return index % 3 == 0;
    }

    /**
     * Produces a boolean that can be used to represent isBubble, based on the provided index.
     */
    public static boolean isBubble(int index) {
        return index % 4 == 0;
    }

    /**
     * Checks that each of the pairs of actions in the two provided lists has identical titles,
     * and that the lists have the same number of elements.
     */
    public void assertActionsEqual(
            List<Notification.Action> expecteds, List<Notification.Action> actuals) {
        Assert.assertEquals(expecteds.size(), actuals.size());
        for (int i = 0; i < expecteds.size(); i++) {
            Notification.Action expected = expecteds.get(i);
            Notification.Action actual = actuals.get(i);
            Assert.assertEquals(expected.title.toString(), actual.title.toString());
        }
    }

    /**
     * Checks that all subelements of the provided NotificationRankingUpdates are equal.
     */
    public void detailedAssertEquals(NotificationRankingUpdate a, NotificationRankingUpdate b) {
        detailedAssertEquals(a.getRankingMap(), b.getRankingMap());
    }

    /**
     * Checks that all subelements of the provided Ranking objects are equal.
     */
    public void detailedAssertEquals(String comment, NotificationListenerService.Ranking a,
                                     NotificationListenerService.Ranking b) {
        Assert.assertEquals(comment, a.getKey(), b.getKey());
        Assert.assertEquals(comment, a.getRank(), b.getRank());
        Assert.assertEquals(comment, a.matchesInterruptionFilter(), b.matchesInterruptionFilter());
        Assert.assertEquals(comment, a.getLockscreenVisibilityOverride(),
                b.getLockscreenVisibilityOverride());
        Assert.assertEquals(comment, a.getSuppressedVisualEffects(),
                b.getSuppressedVisualEffects());
        Assert.assertEquals(comment, a.getImportance(), b.getImportance());
        Assert.assertEquals(comment, a.getImportanceExplanation(), b.getImportanceExplanation());
        Assert.assertEquals(comment, a.getOverrideGroupKey(), b.getOverrideGroupKey());
        Assert.assertEquals(comment, a.getChannel().toString(), b.getChannel().toString());
        Assert.assertEquals(comment, a.getAdditionalPeople(), b.getAdditionalPeople());
        Assert.assertEquals(comment, a.getSnoozeCriteria(), b.getSnoozeCriteria());
        Assert.assertEquals(comment, a.canShowBadge(), b.canShowBadge());
        Assert.assertEquals(comment, a.getUserSentiment(), b.getUserSentiment());
        Assert.assertEquals(comment, a.isSuspended(), b.isSuspended());
        Assert.assertEquals(comment, a.getLastAudiblyAlertedMillis(),
                b.getLastAudiblyAlertedMillis());
        Assert.assertEquals(comment, a.isNoisy(), b.isNoisy());
        Assert.assertEquals(comment, a.getSmartReplies(), b.getSmartReplies());
        Assert.assertEquals(comment, a.canBubble(), b.canBubble());
        Assert.assertEquals(comment, a.isConversation(), b.isConversation());
        if (a.getConversationShortcutInfo() != null && b.getConversationShortcutInfo() != null) {
            Assert.assertEquals(comment, a.getConversationShortcutInfo().getId(),
                    b.getConversationShortcutInfo().getId());
        } else {
            // One or both must be null, so we can check for equality.
            Assert.assertEquals(a.getConversationShortcutInfo(), b.getConversationShortcutInfo());
        }
        assertActionsEqual(a.getSmartActions(), b.getSmartActions());
        Assert.assertEquals(a.getProposedImportance(), b.getProposedImportance());
        Assert.assertEquals(a.hasSensitiveContent(), b.hasSensitiveContent());
    }

    /**
     * Checks that the two RankingMaps have identical keys, and that each Ranking object for
     * each of those keys is identical.
     */
    public void detailedAssertEquals(NotificationListenerService.RankingMap a,
                                     NotificationListenerService.RankingMap b) {
        NotificationListenerService.Ranking arank = new NotificationListenerService.Ranking();
        NotificationListenerService.Ranking brank = new NotificationListenerService.Ranking();
        assertArrayEquals(a.getOrderedKeys(), b.getOrderedKeys());
        for (String key : a.getOrderedKeys()) {
            a.getRanking(key, arank);
            b.getRanking(key, brank);
            detailedAssertEquals("ranking for key <" + key + ">", arank, brank);
        }
    }

    @Before
    public void setUp() {
        mNotificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "test channel",
                NotificationManager.IMPORTANCE_DEFAULT);

        if (mRankingUpdateAshmem) {
            mSetFlagsRule.enableFlags(Flags.FLAG_RANKING_UPDATE_ASHMEM);
        } else {
            mSetFlagsRule.disableFlags(Flags.FLAG_RANKING_UPDATE_ASHMEM);
        }
    }

    /**
     * Creates a mostly empty Test Ranking object with the specified key, rank, and smartActions.
     */
    public NotificationListenerService.Ranking createEmptyTestRanking(
            String key, int rank, ArrayList<Notification.Action> actions) {
        NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();

        ranking.populate(
                /* key= */ key,
                /* rank= */ rank,
                /* matchesInterruptionFilter= */ false,
                /* visibilityOverride= */ 0,
                /* suppressedVisualEffects= */ 0,
                mNotificationChannel.getImportance(),
                /* explanation= */ null,
                /* overrideGroupKey= */ null,
                mNotificationChannel,
                /* overridePeople= */ null,
                /* snoozeCriteria= */ null,
                /* showBadge= */ true,
                /* userSentiment= */ 0,
                /* hidden= */ false,
                /* lastAudiblyAlertedMs= */ -1,
                /* noisy= */ false,
                /* smartActions= */ actions,
                /* smartReplies= */ null,
                /* canBubble= */ false,
                /* isTextChanged= */ false,
                /* isConversation= */ false,
                /* shortcutInfo= */ null,
                /* rankingAdjustment= */ 0,
                /* isBubble= */ false,
                /* proposedImportance= */ 0,
                /* sensitiveContent= */ false
        );
        return ranking;
    }

    // Tests parceling of NotificationRankingUpdate, and by extension, RankingMap and Ranking.
    @Test
    public void testRankingUpdate_parcel() {
        NotificationRankingUpdate nru = generateUpdate(getContext());
        Parcel parcel = Parcel.obtain();
        nru.writeToParcel(parcel, 0);
        if (Flags.rankingUpdateAshmem()) {
            assertTrue(nru.isFdNotNullAndClosed());
        }
        parcel.setDataPosition(0);
        NotificationRankingUpdate nru1 = NotificationRankingUpdate.CREATOR.createFromParcel(parcel);
        // The rankingUpdate file descriptor is only non-null in the new path.
        if (Flags.rankingUpdateAshmem()) {
            assertTrue(nru1.isFdNotNullAndClosed());
        }
        detailedAssertEquals(nru, nru1);
        parcel.recycle();
    }

    // Tests parceling of RankingMap and RankingMap.equals
    @Test
    public void testRankingMap_parcel() {
        NotificationListenerService.RankingMap rmap = generateUpdate(getContext()).getRankingMap();
        Parcel parcel = Parcel.obtain();
        rmap.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NotificationListenerService.RankingMap rmap1 =
                NotificationListenerService.RankingMap.CREATOR.createFromParcel(parcel);

        detailedAssertEquals(rmap, rmap1);
        Assert.assertEquals(rmap, rmap1);
        parcel.recycle();
    }

    // Tests parceling of Ranking and Ranking.equals
    @Test
    public void testRanking_parcel() {
        NotificationListenerService.Ranking ranking =
                generateUpdate(getContext()).getRankingMap().getRawRankingObject(mKeys[0]);
        Parcel parcel = Parcel.obtain();
        ranking.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NotificationListenerService.Ranking ranking1 =
                new NotificationListenerService.Ranking(parcel);
        detailedAssertEquals("rankings differ: ", ranking, ranking1);
        Assert.assertEquals(ranking, ranking1);
        parcel.recycle();
    }

    // Tests NotificationRankingUpdate.equals(), and by extension, RankingMap and Ranking.
    @Test
    public void testRankingUpdate_equals_legacy() {
        NotificationRankingUpdate nru = generateUpdate(getContext());
        NotificationRankingUpdate nru2 = generateUpdate(getContext());
        detailedAssertEquals(nru, nru2);
        Assert.assertEquals(nru, nru2);
        NotificationListenerService.Ranking tweak =
                nru2.getRankingMap().getRawRankingObject(mKeys[0]);
        tweak.populate(
                tweak.getKey(),
                tweak.getRank(),
                !tweak.matchesInterruptionFilter(), // note the inversion here!
                tweak.getLockscreenVisibilityOverride(),
                tweak.getSuppressedVisualEffects(),
                tweak.getImportance(),
                tweak.getImportanceExplanation(),
                tweak.getOverrideGroupKey(),
                tweak.getChannel(),
                (ArrayList) tweak.getAdditionalPeople(),
                (ArrayList) tweak.getSnoozeCriteria(),
                tweak.canShowBadge(),
                tweak.getUserSentiment(),
                tweak.isSuspended(),
                tweak.getLastAudiblyAlertedMillis(),
                tweak.isNoisy(),
                (ArrayList) tweak.getSmartActions(),
                (ArrayList) tweak.getSmartReplies(),
                tweak.canBubble(),
                tweak.isTextChanged(),
                tweak.isConversation(),
                tweak.getConversationShortcutInfo(),
                tweak.getRankingAdjustment(),
                tweak.isBubble(),
                tweak.getProposedImportance(),
                tweak.hasSensitiveContent()
        );
        assertNotEquals(nru, nru2);
    }

    @Test
    public void testRankingUpdate_rankingConstructor() {
        NotificationRankingUpdate nru = generateUpdate(getContext());
        NotificationRankingUpdate constructedNru = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{
                        nru.getRankingMap().getRawRankingObject(mKeys[0]),
                        nru.getRankingMap().getRawRankingObject(mKeys[1]),
                        nru.getRankingMap().getRawRankingObject(mKeys[2]),
                        nru.getRankingMap().getRawRankingObject(mKeys[3]),
                        nru.getRankingMap().getRawRankingObject(mKeys[4])
                });

        detailedAssertEquals(nru, constructedNru);
    }

    @Test
    public void testRankingUpdate_emptyParcelInCheck() {
        NotificationRankingUpdate rankingUpdate = generateUpdate(getContext());
        Parcel parceledRankingUpdate = Parcel.obtain();
        rankingUpdate.writeToParcel(parceledRankingUpdate, 0);

        // This will fail to read the parceledRankingUpdate, because the data position hasn't
        // been reset, so it'll find no data to read.
        NotificationRankingUpdate retrievedRankingUpdate = new NotificationRankingUpdate(
                parceledRankingUpdate);
        assertNull(retrievedRankingUpdate.getRankingMap());
        parceledRankingUpdate.recycle();
    }

    @Test
    public void testRankingUpdate_describeContents() {
        NotificationRankingUpdate rankingUpdate = generateUpdate(getContext());
        assertEquals(0, rankingUpdate.describeContents());
    }

    @Test
    public void testRankingUpdate_equals() {
        NotificationListenerService.Ranking ranking = createEmptyTestRanking(TEST_KEY, 123, null);
        NotificationRankingUpdate rankingUpdate = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking});
        // Reflexive equality, including handling nulls properly
        detailedAssertEquals(rankingUpdate, rankingUpdate);
        // Null or wrong class inequality
        assertFalse(rankingUpdate.equals(null));
        assertFalse(rankingUpdate.equals(ranking));

        // Different rank inequality
        NotificationListenerService.Ranking ranking2 = createEmptyTestRanking(TEST_KEY, 456, null);
        NotificationRankingUpdate rankingUpdate2 = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking2});
        assertFalse(rankingUpdate.equals(rankingUpdate2));

        // Different key inequality
        ranking2 = createEmptyTestRanking(TEST_KEY + "DIFFERENT", 123, null);
        rankingUpdate2 = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking2});
        assertFalse(rankingUpdate.equals(rankingUpdate2));
    }

    @Test
    public void testRankingUpdate_writesSmartActionToParcel() {
        if (!Flags.rankingUpdateAshmem()) {
            return;
        }
        ArrayList<Notification.Action> actions = new ArrayList<>();
        PendingIntent intent = PendingIntent.getBroadcast(
                getContext(),
                0 /*requestCode*/,
                new Intent("ACTION_" + TEST_KEY),
                PendingIntent.FLAG_IMMUTABLE /*flags*/);
        actions.add(new Notification.Action.Builder(null /*icon*/, TEST_KEY, intent).build());

        NotificationListenerService.Ranking ranking =
                createEmptyTestRanking(TEST_KEY, 123, actions);
        NotificationRankingUpdate rankingUpdate = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking});

        Parcel parcel = Parcel.obtain();
        rankingUpdate.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        SharedMemory fd = parcel.readParcelable(getClass().getClassLoader(), SharedMemory.class);
        Bundle smartActionsBundle = parcel.readBundle(getClass().getClassLoader());

        // Assert the file descriptor is valid
        assertNotNull(fd);
        assertFalse(fd.getFd() == -1);

        // Assert that the smart action is in the parcel
        assertNotNull(smartActionsBundle);
        ArrayList<Notification.Action> recoveredActions =
                smartActionsBundle.getParcelableArrayList(TEST_KEY, Notification.Action.class);
        assertNotNull(recoveredActions);
        assertEquals(actions.size(), recoveredActions.size());
        assertEquals(actions.get(0).title.toString(), recoveredActions.get(0).title.toString());
        parcel.recycle();
    }

    @Test
    public void testRankingUpdate_handlesEmptySmartActionList() {
        if (!Flags.rankingUpdateAshmem()) {
            return;
        }
        ArrayList<Notification.Action> actions = new ArrayList<>();
        NotificationListenerService.Ranking ranking =
                createEmptyTestRanking(TEST_KEY, 123, actions);
        NotificationRankingUpdate rankingUpdate = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking});

        Parcel parcel = Parcel.obtain();
        rankingUpdate.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        // Ensure that despite an empty actions list, we can still unparcel the update.
        NotificationRankingUpdate newRankingUpdate = new NotificationRankingUpdate(parcel);
        assertNotNull(newRankingUpdate);
        assertNotNull(newRankingUpdate.getRankingMap());
        detailedAssertEquals(rankingUpdate, newRankingUpdate);
        parcel.recycle();
    }

    @Test
    public void testRankingUpdate_handlesNullSmartActionList() {
        if (!Flags.rankingUpdateAshmem()) {
            return;
        }
        NotificationListenerService.Ranking ranking =
                createEmptyTestRanking(TEST_KEY, 123, null);
        NotificationRankingUpdate rankingUpdate = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking});

        Parcel parcel = Parcel.obtain();
        rankingUpdate.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        // Ensure that despite an empty actions list, we can still unparcel the update.
        NotificationRankingUpdate newRankingUpdate = new NotificationRankingUpdate(parcel);
        assertNotNull(newRankingUpdate);
        assertNotNull(newRankingUpdate.getRankingMap());
        detailedAssertEquals(rankingUpdate, newRankingUpdate);
        parcel.recycle();
    }
}
