/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.AttributionSource;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.pm.ApplicationInfo;
import android.database.MatrixCursor;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.VibrationEffect;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.MediaStore.Audio.AudioColumns;
import android.test.mock.MockContentResolver;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import com.google.common.base.Strings;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationChannelTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private final String CLASS = "android.app.NotificationChannel";

    Context mContext;
    ContentProvider mContentProvider;
    IContentProvider mIContentProvider;

    @Before
    public void setUp() throws Exception {
        mContext = mock(Context.class);
        when(mContext.getApplicationInfo()).thenReturn(new ApplicationInfo());
        MockContentResolver mContentResolver = new MockContentResolver(mContext);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        mContentProvider = mock(ContentProvider.class);
        mIContentProvider = mock(IContentProvider.class);
        when(mContentProvider.getIContentProvider()).thenReturn(mIContentProvider);
        doAnswer(
                invocation -> {
                        AttributionSource attributionSource = invocation.getArgument(0);
                        Uri uri = invocation.getArgument(1);
                        RemoteCallback cb = invocation.getArgument(2);
                        IContentProvider mock = (IContentProvider) (invocation.getMock());
                        AsyncTask.SERIAL_EXECUTOR.execute(
                                () -> {
                                final Bundle bundle = new Bundle();
                                try {
                                        bundle.putParcelable(
                                                ContentResolver.REMOTE_CALLBACK_RESULT,
                                                mock.canonicalize(attributionSource, uri));
                                } catch (RemoteException e) {
                                        /* consume */
                                }
                                cb.sendResult(bundle);
                                });
                        return null;
                })
            .when(mIContentProvider)
            .canonicalizeAsync(any(), any(), any());
        doAnswer(
                invocation -> {
                        AttributionSource attributionSource = invocation.getArgument(0);
                        Uri uri = invocation.getArgument(1);
                        RemoteCallback cb = invocation.getArgument(2);
                        IContentProvider mock = (IContentProvider) (invocation.getMock());
                        AsyncTask.SERIAL_EXECUTOR.execute(
                                () -> {
                                final Bundle bundle = new Bundle();
                                try {
                                        bundle.putParcelable(
                                                ContentResolver.REMOTE_CALLBACK_RESULT,
                                                mock.uncanonicalize(attributionSource, uri));
                                } catch (RemoteException e) {
                                        /* consume */
                                }
                                cb.sendResult(bundle);
                                });
                        return null;
                })
            .when(mIContentProvider)
            .uncanonicalizeAsync(any(), any(), any());
        doAnswer(
                invocation -> {
                        Uri uri = invocation.getArgument(0);
                        RemoteCallback cb = invocation.getArgument(1);
                        IContentProvider mock = (IContentProvider) (invocation.getMock());
                        AsyncTask.SERIAL_EXECUTOR.execute(
                                () -> {
                                final Bundle bundle = new Bundle();
                                try {
                                        bundle.putString(
                                                ContentResolver.REMOTE_CALLBACK_RESULT,
                                                mock.getType(uri));
                                } catch (RemoteException e) {
                                        /* consume */
                                }
                                cb.sendResult(bundle);
                                });
                        return null;
                })
            .when(mIContentProvider)
            .getTypeAsync(any(), any());

        mContentResolver.addProvider("media", mContentProvider);
    }

    @Test
    public void testLongStringFields() {
        NotificationChannel channel = new NotificationChannel("id", "name", 3);

        try {
            String longString = Strings.repeat("A", 65536);
            Field mName = Class.forName(CLASS).getDeclaredField("mName");
            mName.setAccessible(true);
            mName.set(channel, longString);
            Field mId = Class.forName(CLASS).getDeclaredField("mId");
            mId.setAccessible(true);
            mId.set(channel, longString);
            Field mDesc = Class.forName(CLASS).getDeclaredField("mDesc");
            mDesc.setAccessible(true);
            mDesc.set(channel, longString);
            Field mParentId = Class.forName(CLASS).getDeclaredField("mParentId");
            mParentId.setAccessible(true);
            mParentId.set(channel, longString);
            Field mGroup = Class.forName(CLASS).getDeclaredField("mGroup");
            mGroup.setAccessible(true);
            mGroup.set(channel, longString);
            Field mConversationId = Class.forName(CLASS).getDeclaredField("mConversationId");
            mConversationId.setAccessible(true);
            mConversationId.set(channel, longString);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        Parcel parcel = Parcel.obtain();
        channel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        NotificationChannel fromParcel = NotificationChannel.CREATOR.createFromParcel(parcel);
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH, fromParcel.getId().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH, fromParcel.getName().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getDescription().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getParentChannelId().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getGroup().length());
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getConversationId().length());
    }

    @Test
    public void testLongAlertFields() {
        NotificationChannel channel = new NotificationChannel("id", "name", 3);

        channel.setSound(Uri.parse("content://" + Strings.repeat("A",65536)),
                Notification.AUDIO_ATTRIBUTES_DEFAULT);
        channel.setVibrationPattern(new long[65550/2]);

        Parcel parcel = Parcel.obtain();
        channel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        NotificationChannel fromParcel = NotificationChannel.CREATOR.createFromParcel(parcel);
        assertEquals(NotificationChannel.MAX_VIBRATION_LENGTH,
                fromParcel.getVibrationPattern().length);
        assertEquals(NotificationChannel.MAX_TEXT_LENGTH,
                fromParcel.getSound().toString().length());
    }

    @Test
    public void testRestoreSoundUri_customLookup() throws Exception {
        Uri uriToBeRestoredUncanonicalized = Uri.parse("content://media/1");
        Uri uriToBeRestoredCanonicalized = Uri.parse("content://media/1?title=Song&canonical=1");
        Uri uriAfterRestoredUncanonicalized = Uri.parse("content://media/100");
        Uri uriAfterRestoredCanonicalized = Uri.parse("content://media/100?title=Song&canonical=1");

        NotificationChannel channel = new NotificationChannel("id", "name", 3);

        MatrixCursor cursor = new MatrixCursor(new String[] {"_id"});
        cursor.addRow(new Object[] {100L});

        when(mIContentProvider.canonicalize(any(), eq(uriToBeRestoredUncanonicalized)))
                .thenReturn(uriToBeRestoredCanonicalized);

        // Mock the failure of regular uncanonicalize.
        when(mIContentProvider.uncanonicalize(any(), eq(uriToBeRestoredCanonicalized)))
                .thenReturn(null);

        // Mock the custom lookup in RingtoneManager.getRingtoneUriForRestore.
        when(mIContentProvider.query(any(), any(), any(), any(), any())).thenReturn(cursor);

        // Mock the canonicalize in RingtoneManager.getRingtoneUriForRestore.
        when(mIContentProvider.canonicalize(any(), eq(uriAfterRestoredUncanonicalized)))
                .thenReturn(uriAfterRestoredCanonicalized);

        assertThat(
                        channel.restoreSoundUri(
                                mContext,
                                uriToBeRestoredUncanonicalized,
                                true,
                                AudioAttributes.USAGE_NOTIFICATION))
                .isEqualTo(uriAfterRestoredCanonicalized);
    }

    @Test
    public void testWriteXmlForBackup_customLookup_notificationUsage() throws Exception {
        testWriteXmlForBackup_customLookup(
                AudioAttributes.USAGE_NOTIFICATION, AudioColumns.IS_NOTIFICATION);
    }

    @Test
    public void testWriteXmlForBackup_customLookup_alarmUsage() throws Exception {
        testWriteXmlForBackup_customLookup(AudioAttributes.USAGE_ALARM, AudioColumns.IS_ALARM);
    }

    @Test
    public void testWriteXmlForBackup_customLookup_ringtoneUsage() throws Exception {
        testWriteXmlForBackup_customLookup(
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioColumns.IS_RINGTONE);
    }

    @Test
    public void testWriteXmlForBackup_customLookup_unknownUsage() throws Exception {
        testWriteXmlForBackup_customLookup(
                AudioAttributes.USAGE_UNKNOWN, AudioColumns.IS_NOTIFICATION);
    }

    private void testWriteXmlForBackup_customLookup(int usage, String customQuerySelection)
            throws Exception {
        Uri uriToBeRestoredUncanonicalized = Uri.parse("content://media/1");
        Uri uriToBeRestoredCanonicalized = Uri.parse("content://media/1?title=Song&canonical=1");
        Uri uriAfterRestoredUncanonicalized = Uri.parse("content://media/100");
        Uri uriAfterRestoredCanonicalized = Uri.parse("content://media/100?title=Song&canonical=1");

        AudioAttributes mAudioAttributes =
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .setUsage(usage)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build();

        NotificationChannel channel = new NotificationChannel("id", "name", 3);
        channel.setSound(uriToBeRestoredCanonicalized, mAudioAttributes);

        // mock the canonicalize in writeXmlForBackup -> getSoundForBackup
        when(mIContentProvider.canonicalize(any(), eq(uriToBeRestoredUncanonicalized)))
                .thenReturn(uriToBeRestoredCanonicalized);
        when(mIContentProvider.canonicalize(any(), eq(uriToBeRestoredCanonicalized)))
                .thenReturn(uriToBeRestoredCanonicalized);

        MatrixCursor cursor = new MatrixCursor(new String[] {"_id"});
        cursor.addRow(new Object[] {100L});

        when(mIContentProvider.canonicalize(any(), eq(uriToBeRestoredCanonicalized)))
                .thenReturn(uriToBeRestoredCanonicalized);

        // Mock the failure of regular uncanonicalize.
        when(mIContentProvider.uncanonicalize(any(), eq(uriToBeRestoredCanonicalized)))
                .thenReturn(null);

        Bundle expectedBundle =
                ContentResolver.createSqlQueryBundle(
                        customQuerySelection + "=1 AND title=?", new String[] {"Song"}, null);

        // Mock the custom lookup in RingtoneManager.getRingtoneUriForRestore.
        when(mIContentProvider.query(
                        any(),
                        any(),
                        any(),
                        // any(),
                        argThat(
                                queryBundle -> {
                                    return queryBundle != null
                                            && expectedBundle
                                                    .toString()
                                                    .equals(queryBundle.toString());
                                }),
                        any()))
                .thenReturn(cursor);

        // Mock the canonicalize in RingtoneManager.getRingtoneUriForRestore.
        when(mIContentProvider.canonicalize(any(), eq(uriAfterRestoredUncanonicalized)))
                .thenReturn(uriAfterRestoredCanonicalized);

        NotificationChannel restoredChannel = backUpAndRestore(channel);
        assertThat(restoredChannel.getSound()).isEqualTo(uriAfterRestoredCanonicalized);
    }

    @Test
    public void testVibrationGetters_nonPatternBasedVibrationEffect_waveform() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);
        // Note that the amplitude used (1) is not the default amplitude, meaning that this effect
        // does not have an equivalent pattern based effect.
        VibrationEffect effect = VibrationEffect.createOneShot(123, 1);

        channel.setVibrationEffect(effect);

        Consumer<NotificationChannel> assertions = (testedChannel) -> {
            assertThat(testedChannel.getVibrationEffect()).isEqualTo(effect);
            assertNull(testedChannel.getVibrationPattern());
            assertTrue(testedChannel.shouldVibrate());
        };
        assertions.accept(channel);
        assertions.accept(writeToAndReadFromParcel(channel));
        assertions.accept(backUpAndRestore(channel));
    }

    @Test
    public void testVibrationGetters_nonPatternBasedVibrationEffect_nonWaveform() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);
        VibrationEffect effect =
                VibrationEffect
                        .startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                        .compose();

        channel.setVibrationEffect(effect);

        Consumer<NotificationChannel> assertions = (testedChannel) -> {
            assertThat(testedChannel.getVibrationEffect()).isEqualTo(effect);
            assertNull(testedChannel.getVibrationPattern()); // amplitude not default.
            assertTrue(testedChannel.shouldVibrate());
        };
        assertions.accept(channel);
        assertions.accept(writeToAndReadFromParcel(channel));
        assertions.accept(backUpAndRestore(channel));
    }

    @Test
    public void testVibrationGetters_patternBasedVibrationEffect_nonRepeating() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);
        long[] pattern = new long[] {1, 2};
        VibrationEffect effect = VibrationEffect.createWaveform(pattern, /* repeatIndex= */ -1);

        channel.setVibrationEffect(effect);

        Consumer<NotificationChannel> assertions = (testedChannel) -> {
            assertThat(testedChannel.getVibrationEffect()).isEqualTo(effect);
            assertTrue(Arrays.equals(pattern, testedChannel.getVibrationPattern()));
            assertTrue(testedChannel.shouldVibrate());
        };
        assertions.accept(channel);
        assertions.accept(writeToAndReadFromParcel(channel));
        assertions.accept(backUpAndRestore(channel));
    }

    @Test
    public void testVibrationGetters_patternBasedVibrationEffect_wholeRepeating() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);
        long[] pattern = new long[] {1, 2};
        VibrationEffect effect = VibrationEffect.createWaveform(pattern, /* repeatIndex= */ 0);

        channel.setVibrationEffect(effect);

        Consumer<NotificationChannel> assertions = (testedChannel) -> {
            assertThat(testedChannel.getVibrationEffect()).isEqualTo(effect);
            assertNull(testedChannel.getVibrationPattern());
            assertTrue(testedChannel.shouldVibrate());
        };
        assertions.accept(channel);
        assertions.accept(writeToAndReadFromParcel(channel));
        assertions.accept(backUpAndRestore(channel));
    }

    @Test
    public void testVibrationGetters_patternBasedVibrationEffect_partialRepeating()
            throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);
        long[] pattern = new long[] {1, 2, 3, 4};
        VibrationEffect effect = VibrationEffect.createWaveform(pattern, /* repeatIndex= */ 2);

        channel.setVibrationEffect(effect);

        Consumer<NotificationChannel> assertions = (testedChannel) -> {
            assertThat(testedChannel.getVibrationEffect()).isEqualTo(effect);
            assertNull(testedChannel.getVibrationPattern());
            assertTrue(testedChannel.shouldVibrate());
        };
        assertions.accept(channel);
        assertions.accept(writeToAndReadFromParcel(channel));
        assertions.accept(backUpAndRestore(channel));
    }

    @Test
    public void testVibrationGetters_nullVibrationEffect() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);

        channel.setVibrationEffect(null);

        Consumer<NotificationChannel> assertions = (testedChannel) -> {
            assertNull(channel.getVibrationEffect());
            assertNull(channel.getVibrationPattern());
            assertFalse(channel.shouldVibrate());
        };
        assertions.accept(channel);
        assertions.accept(writeToAndReadFromParcel(channel));
        assertions.accept(backUpAndRestore(channel));
    }

    @Test
    public void testVibrationGetters_nullPattern() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);

        channel.setVibrationPattern(null);

        Consumer<NotificationChannel> assertions = (testedChannel) -> {
            assertThat(testedChannel.getVibrationEffect()).isNull();
            assertNull(testedChannel.getVibrationPattern());
            assertFalse(testedChannel.shouldVibrate());
        };
        assertions.accept(channel);
        assertions.accept(writeToAndReadFromParcel(channel));
        assertions.accept(backUpAndRestore(channel));
    }

    @Test
    public void testVibrationGetters_setEffectOverridesSetPattern() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);
        VibrationEffect effect =
                VibrationEffect.createOneShot(123, VibrationEffect.DEFAULT_AMPLITUDE);

        channel.setVibrationPattern(new long[] {60, 80});
        channel.setVibrationEffect(effect);

        assertThat(channel.getVibrationEffect()).isEqualTo(effect);
        assertTrue(Arrays.equals(new long[] {0, 123}, channel.getVibrationPattern()));
        assertTrue(channel.shouldVibrate());

        channel.setVibrationEffect(null);

        assertNull(channel.getVibrationEffect());
        assertNull(channel.getVibrationPattern());
        assertFalse(channel.shouldVibrate());
    }

    @Test
    public void testVibrationGetters_setPatternOverridesSetEffect() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);
        long[] pattern = new long[] {0, 123};

        channel.setVibrationEffect(
                VibrationEffect.createOneShot(123, VibrationEffect.DEFAULT_AMPLITUDE));
        channel.setVibrationPattern(pattern);

        assertThat(channel.getVibrationEffect())
                .isEqualTo(VibrationEffect.createWaveform(pattern, -1));
        assertTrue(Arrays.equals(pattern, channel.getVibrationPattern()));
        assertTrue(channel.shouldVibrate());

        channel.setVibrationPattern(null);

        assertNull(channel.getVibrationEffect());
        assertNull(channel.getVibrationPattern());
        assertFalse(channel.shouldVibrate());
    }

    @Test
    public void testEqualityDependsOnVibration() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel1 = new NotificationChannel("id", "name", 3);
        NotificationChannel channel2 = new NotificationChannel("id", "name", 3);
        assertThat(channel1).isEqualTo(channel2);

        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_POP);
        long[] pattern = new long[] {1, 2, 3};
        channel1.setVibrationEffect(effect);
        channel2.setVibrationEffect(effect);
        assertThat(channel1).isEqualTo(channel2);

        channel1.setVibrationPattern(pattern);
        channel2.setVibrationPattern(pattern);
        assertThat(channel1).isEqualTo(channel2);

        channel1.setVibrationPattern(pattern);
        channel2.setVibrationEffect(VibrationEffect.createWaveform(pattern, /* repeat= */ -1));
        // Channels should still be equal, because the pattern and the effect set are equivalent.
        assertThat(channel1).isEqualTo(channel2);

        channel1.setVibrationEffect(effect);
        channel2.setVibrationPattern(pattern);
        assertThat(channel1).isNotEqualTo(channel2);
    }

    @Test
    public void testSetVibrationPattern_flagOn_setsEffect() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);
        long[] pattern = new long[] {1, 2, 3};

        channel.setVibrationPattern(pattern);

        assertThat(channel.getVibrationEffect())
                .isEqualTo(VibrationEffect.createWaveform(pattern, -1));
    }

    @Test
    public void testSetVibrationPattern_flagNotOn_doesNotSetEffect() throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        NotificationChannel channel = new NotificationChannel("id", "name", 3);

        channel.setVibrationPattern(new long[] {1, 2, 3});

        assertNull(channel.getVibrationEffect());
    }

    /** Backs up a given channel to an XML, and returns the channel read from the XML. */
    private NotificationChannel backUpAndRestore(NotificationChannel channel) throws Exception {
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);

        channel.writeXmlForBackup(serializer, mContext);
        serializer.endDocument();
        serializer.flush();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        byte[] byteArray = baos.toByteArray();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(byteArray)), null);
        parser.nextTag();

        NotificationChannel restoredChannel =
                new NotificationChannel("default_id", "default_name", 3);
        restoredChannel.populateFromXmlForRestore(parser, true, mContext);

        return restoredChannel;
    }

    private NotificationChannel writeToAndReadFromParcel(NotificationChannel channel) {
        Parcel parcel = Parcel.obtain();
        channel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        return NotificationChannel.CREATOR.createFromParcel(parcel);
    }
}
