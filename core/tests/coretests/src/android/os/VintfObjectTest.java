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

package android.os;

import static com.google.common.truth.Truth.assertThat;

import static java.util.stream.Collectors.toList;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = VintfObject.class)
public class VintfObjectTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    /**
     * Quick check for {@link VintfObject#report VintfObject.report()}.
     */
    @Test
    public void testReport() {
        String[] xmls = VintfObject.report();

        assertThat(Stream.of(xmls).map(xml -> rootAndType(xml)).collect(toList()))
                .containsExactly(
                    Pair.create("manifest", "framework"),
                    Pair.create("compatibility-matrix", "framework"),
                    Pair.create("manifest", "device"),
                    Pair.create("compatibility-matrix", "device")
                );
    }

    private static Pair<String, String> rootAndType(String content) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            var builder = factory.newDocumentBuilder();
            var inputSource = new InputSource(new StringReader(content));
            var document = builder.parse(inputSource);
            var root = document.getDocumentElement();
            return Pair.create(root.getTagName(), root.getAttribute("type"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
