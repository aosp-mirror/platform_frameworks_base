/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemfeatures;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.IOException;

// Note: This is a very simple argument test to validate certain behaviors for
// invalid arguments. Correctness and validity is largely exercised by
// SystemFeaturesGeneratorTest.
@RunWith(JUnit4.class)
public class SystemFeaturesGeneratorApiTest {

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private Appendable mOut;

    @Test
    public void testEmpty() throws IOException {
        final String[] args = new String[] {};
        // This should just print the commandline and return.
        SystemFeaturesGenerator.generate(args, mOut);
        verify(mOut, never()).append(any());
    }

    @Test
    public void testBasic() throws IOException {
        final String[] args = new String[] {
            "com.foo.Features",
            "--feature=TELEVISION:0",
        };
        SystemFeaturesGenerator.generate(args, mOut);
        verify(mOut, atLeastOnce()).append(any());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidFeatureVersion() throws IOException {
        final String[] args = new String[] {
            "com.foo.Features",
            "--feature=TELEVISION:blarg",
        };
        SystemFeaturesGenerator.generate(args, mOut);
        verify(mOut, never()).append(any());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidFeatureNameFromAndroidNamespace() throws IOException {
        final String[] args = new String[] {
            "com.foo.Features",
            "--feature=android.hardware.doesntexist:0",
        };
        SystemFeaturesGenerator.generate(args, mOut);
        verify(mOut, never()).append(any());
    }
}
