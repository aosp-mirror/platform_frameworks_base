/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app.activity;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.test.AndroidTestCase;

import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Tests for meta-data associated with application components.
 */
public class MetaDataTest extends AndroidTestCase {

    private void checkMetaData(ComponentName cn, PackageItemInfo ci)
            throws IOException, XmlPullParserException {
        assertNotNull("Unable to find component " + cn, ci);

        Bundle md = ci.metaData;
        assertNotNull("No meta data found", md);

        assertEquals("foo", md.getString("com.android.frameworks.coretests.string"));
        assertTrue(md.getBoolean("com.android.frameworks.coretests.boolean"));
        assertEquals(100, md.getInt("com.android.frameworks.coretests.integer"));
        assertEquals(0xff000000, md.getInt("com.android.frameworks.coretests.color"));

        assertEquals((double) 1001,
                Math.floor(md.getFloat("com.android.frameworks.coretests.float") * 10 + .5));

        assertEquals(R.xml.metadata, md.getInt("com.android.frameworks.coretests.reference"));

        XmlResourceParser xml = ci.loadXmlMetaData(mContext.getPackageManager(),
                "com.android.frameworks.coretests.reference");
        assertNotNull(xml);

        int type;
        while ((type = xml.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
        }
        assertEquals(XmlPullParser.START_TAG, type);
        assertEquals("thedata", xml.getName());

        // method 1: direct access
        final String rawAttr = xml.getAttributeValue(null, "rawText");
        assertEquals("some raw text", rawAttr);

        // method 2: direct access of typed value
        final int rawColorIntAttr = xml.getAttributeIntValue(null, "rawColor", 0);
        assertEquals(0xffffff00, rawColorIntAttr);
        final String rawColorStrAttr = xml.getAttributeValue(null, "rawColor");
        assertEquals("#ffffff00", rawColorStrAttr);

        // method 2: direct access of resource attribute
        final String nameSpace = "http://schemas.android.com/apk/res/android";
        final int colorIntAttr = xml.getAttributeIntValue(nameSpace, "color", 0);
        assertEquals(0xffff0000, colorIntAttr);
        final String colorStrAttr = xml.getAttributeValue(nameSpace, "color");
        assertEquals("#ffff0000", colorStrAttr);

        // method 3: styled access (borrowing an attr from view system here)
        TypedArray a = mContext.obtainStyledAttributes(xml,
                android.R.styleable.TextView);
        String styledAttr = a.getString(android.R.styleable.TextView_text);
        assertEquals("text", styledAttr);
        a.recycle();
        
        xml.close();
    }

    @SmallTest
    public void testActivityWithData() throws Exception {
        ComponentName cn = new ComponentName(mContext, LocalActivity.class);
        ActivityInfo ai = mContext.getPackageManager().getActivityInfo(
                cn, PackageManager.GET_META_DATA);

        checkMetaData(cn, ai);

        ai = mContext.getPackageManager().getActivityInfo(cn, 0);

        assertNull("Meta data returned when not requested", ai.metaData);
    }

    @SmallTest
    public void testReceiverWithData() throws Exception {
        ComponentName cn = new ComponentName(mContext, LocalReceiver.class);
        ActivityInfo ai = mContext.getPackageManager().getReceiverInfo(
                cn, PackageManager.GET_META_DATA);

        checkMetaData(cn, ai);

        ai = mContext.getPackageManager().getReceiverInfo(cn, 0);

        assertNull("Meta data returned when not requested", ai.metaData);
    }

    @SmallTest
    public void testServiceWithData() throws Exception {
        ComponentName cn = new ComponentName(mContext, LocalService.class);
        ServiceInfo si = mContext.getPackageManager().getServiceInfo(
                cn, PackageManager.GET_META_DATA);

        checkMetaData(cn, si);

        si = mContext.getPackageManager().getServiceInfo(cn, 0);

        assertNull("Meta data returned when not requested", si.metaData);
    }

    @SmallTest
    public void testProviderWithData() throws Exception {
        ComponentName cn = new ComponentName(mContext, LocalProvider.class);
        ProviderInfo pi = mContext.getPackageManager().resolveContentProvider(
                "com.android.frameworks.coretests.LocalProvider",
                PackageManager.GET_META_DATA);
        checkMetaData(cn, pi);

        pi = mContext.getPackageManager().resolveContentProvider(
                "com.android.frameworks.coretests.LocalProvider", 0);

        assertNull("Meta data returned when not requested", pi.metaData);
    }

    @SmallTest
    public void testPermissionWithData() throws Exception {
        ComponentName cn = new ComponentName("foo",
                "com.android.frameworks.coretests.permission.TEST_GRANTED");
        PermissionInfo pi = mContext.getPackageManager().getPermissionInfo(
                cn.getClassName(), PackageManager.GET_META_DATA);
        checkMetaData(cn, pi);

        pi = mContext.getPackageManager().getPermissionInfo(
                cn.getClassName(), 0);

        assertNull("Meta data returned when not requested", pi.metaData);
    }
}


