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

    private final View mView = new View(mContext);

    private final ViewStructureImpl mViewStructure = new ViewStructureImpl(mView);

    private final ViewNode mViewNode = mViewStructure.getNode();

    @Mock
    private HtmlInfo mHtmlInfoMock;

    @Test
    public void testUnsupportedProperties() {
        mViewStructure.setChildCount(1);
        assertThat(mViewNode.getChildCount()).isEqualTo(0);

        mViewStructure.addChildCount(1);
        assertThat(mViewNode.getChildCount()).isEqualTo(0);

        assertThat(mViewStructure.newChild(0)).isNull();
        assertThat(mViewNode.getChildCount()).isEqualTo(0);

        assertThat(mViewStructure.asyncNewChild(0)).isNull();
        assertThat(mViewNode.getChildCount()).isEqualTo(0);

        mViewStructure.asyncCommit();
        assertThat(mViewNode.getChildCount()).isEqualTo(0);

        mViewStructure.setWebDomain("Y U NO SET?");
        assertThat(mViewNode.getWebDomain()).isNull();

        assertThat(mViewStructure.newHtmlInfoBuilder("WHATEVER")).isNull();

        mViewStructure.setHtmlInfo(mHtmlInfoMock);
        assertThat(mViewNode.getHtmlInfo()).isNull();

        mViewStructure.setDataIsSensitive(true);

        assertThat(mViewStructure.getTempRect()).isNull();

        // Graphic properties
        mViewStructure.setElevation(6.66f);
        assertThat(mViewNode.getElevation()).isEqualTo(0f);
        mViewStructure.setAlpha(66.6f);
        assertThat(mViewNode.getAlpha()).isEqualTo(1.0f);
        mViewStructure.setTransformation(Matrix.IDENTITY_MATRIX);
        assertThat(mViewNode.getTransformation()).isNull();
    }

    @Test
    public void testGetSet_textIdEntry() {
        assertThat(mViewNode.getTextIdEntry()).isNull();

        String expected = "TEXT_ID_ENTRY";
        mViewNode.setTextIdEntry(expected);

        assertThat(mViewNode.getTextIdEntry()).isEqualTo(expected);
    }
}
