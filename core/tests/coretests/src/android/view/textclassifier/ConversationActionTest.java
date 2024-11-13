/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view.textclassifier;

import static com.google.common.truth.Truth.assertThat;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class ConversationActionTest {

    @Test
    public void toBuilder() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final PendingIntent intent = PendingIntent.getActivity(
                context, 0, new Intent(), PendingIntent.FLAG_IMMUTABLE);
        final Icon icon = Icon.createWithData(new byte[]{0}, 0, 1);
        final Bundle extras = new Bundle();
        extras.putInt("key", 5);
        final ConversationAction convAction =
                new ConversationAction.Builder(ConversationAction.TYPE_CALL_PHONE)
                        .setAction(new RemoteAction(icon, "title", "descr", intent))
                        .setConfidenceScore(0.5f)
                        .setExtras(extras)
                        .build();

        final ConversationAction fromBuilder = convAction.toBuilder().build();

        assertThat(fromBuilder.getType()).isEqualTo(convAction.getType());
        assertThat(fromBuilder.getAction()).isEqualTo(convAction.getAction());
        assertThat(fromBuilder.getConfidenceScore()).isEqualTo(convAction.getConfidenceScore());
        assertThat(fromBuilder.getExtras()).isEqualTo(convAction.getExtras());
        assertThat(fromBuilder.getTextReply()).isEqualTo(convAction.getTextReply());
    }

    @Test
    public void toBuilder_textReply() {
        final ConversationAction convAction =
                new ConversationAction.Builder(ConversationAction.TYPE_TEXT_REPLY)
                        .setTextReply(":P")
                        .build();

        final ConversationAction fromBuilder = convAction.toBuilder().build();

        assertThat(fromBuilder.getTextReply()).isEqualTo(convAction.getTextReply());
    }
}
