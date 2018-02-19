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

import static org.junit.Assert.assertEquals;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassifierConstantsTest {

    @Test
    public void testEntityListParsing() {
        final TextClassifierConstants constants = TextClassifierConstants.loadFromString(
                "entity_list_default=phone,"
                        + "entity_list_not_editable=address:flight,"
                        + "entity_list_editable=date:datetime");
        assertEquals(1, constants.getEntityListDefault().size());
        assertEquals("phone", constants.getEntityListDefault().get(0));
        assertEquals(2, constants.getEntityListNotEditable().size());
        assertEquals("address", constants.getEntityListNotEditable().get(0));
        assertEquals("flight", constants.getEntityListNotEditable().get(1));
        assertEquals(2, constants.getEntityListEditable().size());
        assertEquals("date", constants.getEntityListEditable().get(0));
        assertEquals("datetime", constants.getEntityListEditable().get(1));
    }
}
