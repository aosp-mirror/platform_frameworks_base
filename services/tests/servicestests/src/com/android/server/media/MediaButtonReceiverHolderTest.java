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

package com.android.server.media;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.common.truth.Truth;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MediaButtonReceiverHolderTest {

    @Test
    public void createMediaButtonReceiverHolder_resolvesNullComponentName() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        PendingIntent pi = PendingIntent.getBroadcast(context, /* requestCode= */ 0, intent,
                PendingIntent.FLAG_IMMUTABLE);
        MediaButtonReceiverHolder a = MediaButtonReceiverHolder.create(/* userId= */ 0, pi,
                context.getPackageName());
        Truth.assertWithMessage("Component name must match PendingIntent creator package.").that(
                a.getComponentName()).isNull();
    }
}
