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
 * limitations under the License.
 */

package android.view.textclassifier;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import android.Manifest;
import android.content.Context;
import android.permission.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationManagerTest {

    private Context mContext;
    private TextClassificationManager mTcm;

    @Before
    public void setup() {
        mContext = ApplicationProvider.getApplicationContext();
        mTcm = mContext.getSystemService(TextClassificationManager.class);
    }

    @Test
    public void testSetTextClassifier() {
        TextClassifier classifier = mock(TextClassifier.class);
        mTcm.setTextClassifier(classifier);
        assertThat(mTcm.getTextClassifier()).isEqualTo(classifier);
    }

    @Test
    public void testGetLocalTextClassifier() {
        assertThat(mTcm.getTextClassifier(TextClassifier.LOCAL))
                .isSameInstanceAs(TextClassifier.NO_OP);
    }

    @Test
    public void testGetSystemTextClassifier() {
        assertThat(mTcm.getTextClassifier(TextClassifier.SYSTEM))
                .isInstanceOf(SystemTextClassifier.class);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_TEXT_CLASSIFIER_CHOICE_API_ENABLED)
    public void testGetClassifier() {
        Assume.assumeTrue(Flags.textClassifierChoiceApiEnabled());
        assertThrows(SecurityException.class,
                () -> mTcm.getClassifier(TextClassifier.CLASSIFIER_TYPE_DEVICE_DEFAULT));
        assertThrows(SecurityException.class,
                () -> mTcm.getClassifier(TextClassifier.CLASSIFIER_TYPE_ANDROID_DEFAULT));
        assertThrows(SecurityException.class,
                () -> mTcm.getClassifier(TextClassifier.CLASSIFIER_TYPE_SELF_PROVIDED));

        runWithShellPermissionIdentity(() -> {
            assertThat(
                    mTcm.getClassifier(TextClassifier.CLASSIFIER_TYPE_DEVICE_DEFAULT)).isInstanceOf(
                    SystemTextClassifier.class);
            assertThat(mTcm.getClassifier(
                    TextClassifier.CLASSIFIER_TYPE_ANDROID_DEFAULT)).isInstanceOf(
                    SystemTextClassifier.class);
            assertThat(mTcm.getClassifier(
                    TextClassifier.CLASSIFIER_TYPE_SELF_PROVIDED)).isSameInstanceAs(
                    TextClassifier.NO_OP);
        }, Manifest.permission.ACCESS_TEXT_CLASSIFIER_BY_TYPE);
    }
}
