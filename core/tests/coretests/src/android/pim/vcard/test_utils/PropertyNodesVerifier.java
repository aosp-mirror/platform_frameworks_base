/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard.test_utils;

import android.pim.vcard.VCardConfig;
import android.pim.vcard.VCardParser;
import android.pim.vcard.VCardParser_V21;
import android.pim.vcard.VCardParser_V30;
import android.pim.vcard.exception.VCardException;
import android.test.AndroidTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class PropertyNodesVerifier extends VNodeBuilder {
    private final List<PropertyNodesVerifierElem> mPropertyNodesVerifierElemList;
    private final AndroidTestCase mAndroidTestCase;
    private int mIndex;

    public PropertyNodesVerifier(AndroidTestCase testCase) {
        mPropertyNodesVerifierElemList = new ArrayList<PropertyNodesVerifierElem>();
        mAndroidTestCase = testCase;
    }

    public PropertyNodesVerifierElem addPropertyNodesVerifierElem() {
        PropertyNodesVerifierElem elem = new PropertyNodesVerifierElem(mAndroidTestCase);
        mPropertyNodesVerifierElemList.add(elem);
        return elem;
    }

    public void verify(int resId, int vCardType)
            throws IOException, VCardException {
        verify(mAndroidTestCase.getContext().getResources().openRawResource(resId), vCardType);
    }

    public void verify(int resId, int vCardType, final VCardParser vCardParser)
            throws IOException, VCardException {
        verify(mAndroidTestCase.getContext().getResources().openRawResource(resId),
                vCardType, vCardParser);
    }

    public void verify(InputStream is, int vCardType) throws IOException, VCardException {
        final VCardParser vCardParser;
        if (VCardConfig.isV30(vCardType)) {
            vCardParser = new VCardParser_V30();
        } else {
            vCardParser = new VCardParser_V21();
        }
        verify(is, vCardType, vCardParser);
    }

    public void verify(InputStream is, int vCardType, final VCardParser vCardParser)
            throws IOException, VCardException {
        try {
            vCardParser.parse(is, this);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                }
            }
        }
    }

    @Override
    public void endEntry() {
        super.endEntry();
        mAndroidTestCase.assertTrue(mIndex < mPropertyNodesVerifierElemList.size());
        mAndroidTestCase.assertTrue(mIndex < vNodeList.size());
        mPropertyNodesVerifierElemList.get(mIndex).verify(vNodeList.get(mIndex));
        mIndex++;
    }
}
