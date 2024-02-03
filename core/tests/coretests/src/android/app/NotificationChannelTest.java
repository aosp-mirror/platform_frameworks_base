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
import android.provider.MediaStore.Audio.AudioColumns;
import android.test.mock.MockContentResolver;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import com.google.common.base.Strings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationChannelTest {
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

        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);

        // mock the canonicalize in writeXmlForBackup -> getSoundForBackup
        when(mIContentProvider.canonicalize(any(), eq(uriToBeRestoredUncanonicalized)))
                .thenReturn(uriToBeRestoredCanonicalized);
        when(mIContentProvider.canonicalize(any(), eq(uriToBeRestoredCanonicalized)))
                .thenReturn(uriToBeRestoredCanonicalized);

        channel.writeXmlForBackup(serializer, mContext);
        serializer.endDocument();
        serializer.flush();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        byte[] byteArray = baos.toByteArray();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(byteArray)), null);
        parser.nextTag();

        NotificationChannel targetChannel = new NotificationChannel("id", "name", 3);

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

        targetChannel.populateFromXmlForRestore(parser, true, mContext);
        assertThat(targetChannel.getSound()).isEqualTo(uriAfterRestoredCanonicalized);
    }
}
