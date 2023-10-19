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

import static com.android.internal.config.sysui.SystemUiSystemPropertiesFlags.NotificationFlags.RANKING_UPDATE_ASHMEM;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import com.android.internal.config.sysui.SystemUiSystemPropertiesFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@SmallTest
@RunWith(Parameterized.class)
public class NotificationRankingUpdateTest {

    private static final String NOTIFICATION_CHANNEL_ID = "test_channel_id";
    private static final String TEST_KEY = "key";

    private NotificationChannel mNotificationChannel;

    // TODO(b/284297289): remove this flag set once resolved.
    @Parameterized.Parameters(name = "rankingUpdateAshmem={0}")
    public static Boolean[] getRankingUpdateAshmem() {
        return new Boolean[] { true, false };
    }

    @Parameterized.Parameter
    public boolean mRankingUpdateAshmem;

    @Before
    public void setUp() {
        mNotificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "test channel",
                NotificationManager.IMPORTANCE_DEFAULT);

        SystemUiSystemPropertiesFlags.TEST_RESOLVER = flag -> {
            if (flag.mSysPropKey.equals(RANKING_UPDATE_ASHMEM.mSysPropKey)) {
                return mRankingUpdateAshmem;
            }
            return new SystemUiSystemPropertiesFlags.DebugResolver().isEnabled(flag);
        };
    }

    @After
    public void tearDown() {
        SystemUiSystemPropertiesFlags.TEST_RESOLVER = null;
    }

    public NotificationListenerService.Ranking createTestRanking(String key, int rank) {
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
                /* smartActions= */ null,
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

    @Test
    public void testRankingUpdate_rankingConstructor() {
        NotificationListenerService.Ranking ranking = createTestRanking(TEST_KEY, 123);
        NotificationRankingUpdate rankingUpdate = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking});

        NotificationListenerService.RankingMap retrievedRankings = rankingUpdate.getRankingMap();
        NotificationListenerService.Ranking retrievedRanking =
                new NotificationListenerService.Ranking();
        assertTrue(retrievedRankings.getRanking(TEST_KEY, retrievedRanking));
        assertEquals(123, retrievedRanking.getRank());
    }

    @Test
    public void testRankingUpdate_parcelConstructor() {
        NotificationListenerService.Ranking ranking = createTestRanking(TEST_KEY, 123);
        NotificationRankingUpdate rankingUpdate = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking});

        Parcel parceledRankingUpdate = Parcel.obtain();
        rankingUpdate.writeToParcel(parceledRankingUpdate, 0);
        parceledRankingUpdate.setDataPosition(0);

        NotificationRankingUpdate retrievedRankingUpdate = new NotificationRankingUpdate(
                parceledRankingUpdate);

        NotificationListenerService.RankingMap retrievedRankings =
                retrievedRankingUpdate.getRankingMap();
        assertNotNull(retrievedRankings);
        // The rankingUpdate file descriptor is only non-null in the new path.
        if (SystemUiSystemPropertiesFlags.getResolver().isEnabled(
                SystemUiSystemPropertiesFlags.NotificationFlags.RANKING_UPDATE_ASHMEM)) {
            assertTrue(retrievedRankingUpdate.isFdNotNullAndClosed());
        }
        NotificationListenerService.Ranking retrievedRanking =
                new NotificationListenerService.Ranking();
        assertTrue(retrievedRankings.getRanking(TEST_KEY, retrievedRanking));
        assertEquals(123, retrievedRanking.getRank());
        assertTrue(retrievedRankingUpdate.equals(rankingUpdate));
        parceledRankingUpdate.recycle();
    }

    @Test
    public void testRankingUpdate_emptyParcelInCheck() {
        NotificationListenerService.Ranking ranking = createTestRanking(TEST_KEY, 123);
        NotificationRankingUpdate rankingUpdate = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking});

        Parcel parceledRankingUpdate = Parcel.obtain();
        rankingUpdate.writeToParcel(parceledRankingUpdate, 0);

        // This will fail to read the parceledRankingUpdate, because the data position hasn't
        // been reset, so it'll find no data to read.
        NotificationRankingUpdate retrievedRankingUpdate = new NotificationRankingUpdate(
                parceledRankingUpdate);
        assertNull(retrievedRankingUpdate.getRankingMap());
    }

    @Test
    public void testRankingUpdate_describeContents() {
        NotificationListenerService.Ranking ranking = createTestRanking(TEST_KEY, 123);
        NotificationRankingUpdate rankingUpdate = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking});
        assertEquals(0, rankingUpdate.describeContents());
    }

    @Test
    public void testRankingUpdate_equals() {
        NotificationListenerService.Ranking ranking = createTestRanking(TEST_KEY, 123);
        NotificationRankingUpdate rankingUpdate = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking});
        // Reflexive equality.
        assertTrue(rankingUpdate.equals(rankingUpdate));
        // Null or wrong class inequality.
        assertFalse(rankingUpdate.equals(null));
        assertFalse(rankingUpdate.equals(ranking));

        // Different ranking contents inequality.
        NotificationListenerService.Ranking ranking2 = createTestRanking(TEST_KEY, 456);
        NotificationRankingUpdate rankingUpdate2 = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking2});
        assertFalse(rankingUpdate.equals(rankingUpdate2));

        // Same ranking contents equality.
        ranking2 = createTestRanking(TEST_KEY, 123);
        rankingUpdate2 = new NotificationRankingUpdate(
                new NotificationListenerService.Ranking[]{ranking2});
        assertTrue(rankingUpdate.equals(rankingUpdate2));
    }
}
