/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view.contentcapture;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.Matrix;
import android.view.View;
import android.view.ViewStructure.HtmlInfo;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import androidx.test.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for {@link ViewNode}.
 *
 * <p>To run it: {@code atest FrameworksCoreTests:android.view.contentcapture.ViewNodeTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class ViewNodeTest {

    private final Context mContext = InstrumentationRegistry.getTargetContext();

    @Mock
    private HtmlInfo mHtmlInfoMock;

    @Test
    public void testUnsupportedProperties() {
        View view = new View(mContext);

        ViewStructureImpl structure = new ViewStructureImpl(view);
        ViewNode node = structure.getNode();

        structure.setChildCount(1);
        assertThat(node.getChildCount()).isEqualTo(0);

        structure.addChildCount(1);
        assertThat(node.getChildCount()).isEqualTo(0);

        assertThat(structure.newChild(0)).isNull();
        assertThat(node.getChildCount()).isEqualTo(0);

        assertThat(structure.asyncNewChild(0)).isNull();
        assertThat(node.getChildCount()).isEqualTo(0);

        structure.asyncCommit();
        assertThat(node.getChildCount()).isEqualTo(0);

        structure.setWebDomain("Y U NO SET?");
        assertThat(node.getWebDomain()).isNull();

        assertThat(structure.newHtmlInfoBuilder("WHATEVER")).isNull();

        structure.setHtmlInfo(mHtmlInfoMock);
        assertThat(node.getHtmlInfo()).isNull();

        structure.setDataIsSensitive(true);

        assertThat(structure.getTempRect()).isNull();

        // Graphic properties
        structure.setElevation(6.66f);
        assertThat(node.getElevation()).isWithin(1.0e-10f).of(0f);
        structure.setAlpha(66.6f);
        assertThat(node.getAlpha()).isWithin(1.0e-10f).of(1.0f);
        structure.setTransformation(Matrix.IDENTITY_MATRIX);
        assertThat(node.getTransformation()).isNull();
    }
}
