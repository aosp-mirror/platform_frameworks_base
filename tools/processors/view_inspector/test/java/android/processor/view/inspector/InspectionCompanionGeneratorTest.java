/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.processor.view.inspector;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.fail;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.squareup.javapoet.ClassName;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Optional;

/**
 * Tests for {@link InspectionCompanionGenerator}
 */
public class InspectionCompanionGeneratorTest {
    private static final String RESOURCE_PATH_TEMPLATE =
            "android/processor/view/inspector/InspectionCompanionGeneratorTest/%s.java.txt";
    private static final ClassName TEST_CLASS_NAME =
            ClassName.get("com.android.inspectable", "TestInspectable");
    private InspectableClassModel mModel;
    private InspectionCompanionGenerator mGenerator;

    @Before
    public void setup() {
        mModel = new InspectableClassModel(TEST_CLASS_NAME);
        mGenerator = new InspectionCompanionGenerator(null, getClass());
    }

    @Test
    public void testNodeName() {
        mModel.setNodeName(Optional.of("NodeName"));
        assertGeneratedFileEquals("NodeName");
    }

    @Test
    public void testNestedClass() {
        mModel = new InspectableClassModel(
                ClassName.get("com.android.inspectable", "Outer", "Inner"));
        assertGeneratedFileEquals("NestedClass");
    }

    private void assertGeneratedFileEquals(String fileName) {
        assertEquals(
                loadTextResource(String.format(RESOURCE_PATH_TEMPLATE, fileName)),
                mGenerator.generateFile(mModel).toString());
    }

    private String loadTextResource(String path) {
        try {
            final URL url = Resources.getResource(path);
            assertNotNull(String.format("Resource file not found: %s", path), url);
            return Resources.toString(url, Charsets.UTF_8);
        } catch (IOException e) {
            fail(e.getMessage());
            return null;
        }
    }
}
