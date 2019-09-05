/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.text;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * PackedIntVectorTest tests the features of android.util.PackedIntVector.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class PackedIntVectorTest {

    @Test
    public void testBasic() {
        for (int width = 0; width < 10; width++) {
            PackedIntVector p = new PackedIntVector(width);
            int[] ins = new int[width];

            for (int height = width * 2; height < width * 4; height++) {
                assertEquals(p.width(), width);

                // Test adding rows.

                for (int i = 0; i < height; i++) {
                    int at;

                    if (i % 2 == 0) {
                        at = i;
                    } else {
                        at = p.size() - i;
                    }

                    for (int j = 0; j < width; j++) {
                        ins[j] = i + j;
                    }

                    if (i == height / 2) {
                        p.insertAt(at, null);
                    } else {
                        p.insertAt(at, ins);
                    }

                    assertEquals(p.size(), i + 1);

                    for (int j = 0; j < width; j++) {
                        if (i == height / 2) {
                            assertEquals(0, p.getValue(at, j));
                        } else {
                            assertEquals(p.getValue(at, j), i + j);
                        }
                    }
                }

                // Test setting values.

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        p.setValue(i, j, i * j);

                        assertEquals(p.getValue(i, j), i * j);
                    }
                }

                // Test offsetting values.

                for (int j = 0; j < width; j++) {
                    p.adjustValuesBelow(j * 2, j, j + 27);
                }

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int expect = i * j;

                        if (i >= j * 2) {
                            expect += j + 27;
                        }

                        assertEquals(p.getValue(i, j), expect);
                    }
                }

                for (int j = 0; j < width; j++) {
                    p.adjustValuesBelow(j, j, j * j + 14);
                }

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int expect = i * j;

                        if (i >= j * 2) {
                            expect += j + 27;
                        }
                        if (i >= j) {
                            expect += j * j + 14;
                        }

                        assertEquals(p.getValue(i, j), expect);
                    }
                }

                // Test undoing offsets.

                for (int j = 0; j < width; j++) {
                    p.adjustValuesBelow(j * 2, j, -(j + 27));
                    p.adjustValuesBelow(j, j, -(j * j + 14));
                }

                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        assertEquals(p.getValue(i, j), i * j);
                    }
                }

                // Test deleting rows.

                while (p.size() > 0) {
                    int osize = p.size();
                    int del = osize / 3;

                    if (del == 0) {
                        del = 1;
                    }

                    int at = (osize - del) / 2;
                    p.deleteAt(at, del);

                    assertEquals(p.size(), osize - del);

                    for (int i = 0; i < at; i++) {
                        for (int j = 0; j < width; j++) {
                            assertEquals(p.getValue(i, j), i * j);
                        }
                    }

                    for (int i = at; i < p.size(); i++) {
                        for (int j = 0; j < width; j++) {
                            assertEquals(p.getValue(i, j), (i + height - p.size()) * j);
                        }
                    }
                }

                assertEquals(0, p.size());
            }
        }
    }
}
