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

package android.testing;

import static android.testing.DexmakerShareClassLoaderRule.DEXMAKER_SHARE_CLASSLOADER_PROPERTY;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.model.Statement;

import java.util.ConcurrentModificationException;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DexmakerShareClassLoaderRuleTest {

    // Intentionally not @Rule, so we have more control.
    private final DexmakerShareClassLoaderRule mRule = new DexmakerShareClassLoaderRule();

    @Before
    public void setUp() throws Exception {
        System.clearProperty(DEXMAKER_SHARE_CLASSLOADER_PROPERTY);
    }

    @Test
    public void rule_setsProperty() throws Throwable {
        mRule.apply(ASSERT_STATEMENT, null).evaluate();
    }

    @Test
    public void rule_resetsProperty() throws Throwable {
        mRule.apply(ASSERT_STATEMENT, null).evaluate();
        assertThat(readProperty(), is(false));
    }

    @Test
    public void rule_resetsProperty_toExactValue() throws Throwable {
        System.setProperty(DEXMAKER_SHARE_CLASSLOADER_PROPERTY, "asdf");
        mRule.apply(ASSERT_STATEMENT, null).evaluate();
        assertThat(System.getProperty(DEXMAKER_SHARE_CLASSLOADER_PROPERTY), is("asdf"));
    }

    @Test
    public void rule_preventsOtherThreadFromInterfering() throws Throwable {
        mRule.apply(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                assertThat(readProperty(), is(true));

                final Throwable[] thrown = new Throwable[1];
                final Thread t = new Thread(() -> {
                    try {
                        new DexmakerShareClassLoaderRule().apply(ASSERT_STATEMENT, null).evaluate();
                        fail("Expected a ConcurrentModificationException");
                    } catch (ConcurrentModificationException e) {
                        // Success.
                    } catch (Throwable tr) {
                        thrown[0] = tr;
                    }
                });
                t.start();
                t.join();

                if (thrown[0] != null) {
                    throw  thrown[0];
                }
            }
        }, null).evaluate();
        assertThat(readProperty(), is(false));
    }

    @Test
    public void rule_isReentrant() throws Throwable {
        mRule.apply(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                assertThat(readProperty(), is(true));
                new DexmakerShareClassLoaderRule().apply(ASSERT_STATEMENT, null).evaluate();
                assertThat(readProperty(), is(true));
            }
        }, null).evaluate();
        assertThat(readProperty(), is(false));
    }

    private static boolean readProperty() {
        return Boolean.parseBoolean(System.getProperty(DEXMAKER_SHARE_CLASSLOADER_PROPERTY));
    }

    private static final Statement ASSERT_STATEMENT = new Statement() {
        @Override
        public void evaluate() throws Throwable {
            assertThat(readProperty(), is(true));
        }
    };

}
