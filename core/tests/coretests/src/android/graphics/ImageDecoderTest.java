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
package android.graphics;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.coretests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ImageDecoderTest {

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    @Test
    public void onDecodeHeader_png_returnsPopulatedData() throws IOException {
        ImageDecoder.Source src =
                ImageDecoder.createSource(mContext.getResources(), R.drawable.gettysburg);
        ImageDecoder.ImageInfo info = ImageDecoder.decodeHeader(src);
        assertThat(info.getSize().getWidth()).isEqualTo(432);
        assertThat(info.getSize().getHeight()).isEqualTo(291);
        assertThat(info.getMimeType()).isEqualTo("image/png");
        assertThat(info.getColorSpace()).isNotNull();
        assertThat(info.getColorSpace().getModel()).isEqualTo(ColorSpace.Model.RGB);
        assertThat(info.getColorSpace().getId()).isEqualTo(0);
        assertThat(info.isAnimated()).isFalse();
    }

    @Test
    public void onDecodeHeader_animatedWebP_returnsPopulatedData() throws IOException {
        ImageDecoder.Source src =
                ImageDecoder.createSource(mContext.getResources(), R.drawable.animated_webp);
        ImageDecoder.ImageInfo info = ImageDecoder.decodeHeader(src);
        assertThat(info.getSize().getWidth()).isEqualTo(278);
        assertThat(info.getSize().getHeight()).isEqualTo(183);
        assertThat(info.getMimeType()).isEqualTo("image/webp");
        assertThat(info.getColorSpace()).isNotNull();
        assertThat(info.getColorSpace().getModel()).isEqualTo(ColorSpace.Model.RGB);
        assertThat(info.getColorSpace().getId()).isEqualTo(0);
        assertThat(info.isAnimated()).isTrue();
    }

    @Test(expected = IOException.class)
    public void onDecodeHeader_invalidSource_throwsException() throws IOException {
        ImageDecoder.Source src = ImageDecoder.createSource(new File("/this/file/does/not/exist"));
        ImageDecoder.decodeHeader(src);
    }

    @Test(expected = IOException.class)
    public void onDecodeHeader_invalidResource_throwsException() throws IOException {
        ImageDecoder.Source src =
                ImageDecoder.createSource(mContext.getResources(), R.drawable.box);
        ImageDecoder.decodeHeader(src);
    }
}
