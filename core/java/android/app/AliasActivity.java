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

package android.app;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import java.io.IOException;

/**
 * Stub activity that launches another activity (and then finishes itself)
 * based on information in its component's manifest meta-data.  This is a
 * simple way to implement an alias-like mechanism.
 * 
 * To use this activity, you should include in the manifest for the associated
 * component an entry named "android.app.alias".  It is a reference to an XML
 * resource describing an intent that launches the real application.
 */
public class AliasActivity extends Activity {
    /**
     * This is the name under which you should store in your component the
     * meta-data information about the alias.  It is a reference to an XML
     * resource describing an intent that launches the real application.
     * {@hide}
     */
    public final String ALIAS_META_DATA = "android.app.alias";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        XmlResourceParser parser = null;
        try {
            ActivityInfo ai = getPackageManager().getActivityInfo(
                    getComponentName(), PackageManager.GET_META_DATA);
            parser = ai.loadXmlMetaData(getPackageManager(),
                    ALIAS_META_DATA);
            if (parser == null) {
                throw new RuntimeException("Alias requires a meta-data field "
                        + ALIAS_META_DATA);
            }
            
            Intent intent = parseAlias(parser);
            if (intent == null) {
                throw new RuntimeException(
                        "No <intent> tag found in alias description");
            }
            
            startActivity(intent);
            finish();
            
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Error parsing alias", e);
        } catch (XmlPullParserException e) {
            throw new RuntimeException("Error parsing alias", e);
        } catch (IOException e) {
            throw new RuntimeException("Error parsing alias", e);
        } finally {
            if (parser != null) parser.close();
        }
    }

    private Intent parseAlias(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        AttributeSet attrs = Xml.asAttributeSet(parser);
        
        Intent intent = null;
        
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                && type != XmlPullParser.START_TAG) {
        }
        
        String nodeName = parser.getName();
        if (!"alias".equals(nodeName)) {
            throw new RuntimeException(
                    "Alias meta-data must start with <alias> tag; found"
                    + nodeName + " at " + parser.getPositionDescription());
        }
        
        int outerDepth = parser.getDepth();
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            nodeName = parser.getName();
            if ("intent".equals(nodeName)) {
                Intent gotIntent = Intent.parseIntent(getResources(), parser, attrs);
                if (intent == null) intent = gotIntent;
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }
        
        return intent;
    }
    
}
