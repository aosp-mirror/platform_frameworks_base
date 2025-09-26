/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.server.companion.utils;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.servicestests.R;
import com.android.server.companion.association.AssociationDiskStore;
import com.android.server.companion.association.Associations;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;

@Presubmit
@RunWith(AndroidTestingRunner.class)
public class AssociationDiskStoreTest {

    @Test
    public void readLegacyFileByNewLogic() throws XmlPullParserException, IOException {
        InputStream legacyXmlStream = InstrumentationRegistry.getInstrumentation().getContext()
                .getResources().openRawResource(R.raw.companion_android14_associations);

        Associations associations = AssociationDiskStore.readAssociationsFromInputStream(
                0, legacyXmlStream, "state");
        assertEquals(2, associations.getAssociations().size());
    }
}
