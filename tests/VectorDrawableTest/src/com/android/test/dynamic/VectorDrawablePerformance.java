/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.test.dynamic;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.drawable.VectorDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.widget.TextView;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ScrollView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.text.DecimalFormat;

@SuppressWarnings({"UnusedDeclaration"})
public class VectorDrawablePerformance extends Activity {
    private static final String LOGCAT = "VectorDrawable1";
    protected int[] icon = {
            R.drawable.vector_icon_filltype_nonzero,
            R.drawable.vector_icon_filltype_evenodd,
            R.drawable.vector_icon_gradient_1,
            R.drawable.vector_icon_gradient_2,
            R.drawable.vector_icon_gradient_3,
            R.drawable.vector_icon_gradient_1_clamp,
            R.drawable.vector_icon_gradient_2_repeat,
            R.drawable.vector_icon_gradient_3_mirror,
            R.drawable.vector_icon_state_list_simple,
            R.drawable.vector_icon_state_list_theme,
            R.drawable.vector_drawable01,
            R.drawable.vector_drawable02,
            R.drawable.vector_drawable03,
            R.drawable.vector_drawable04,
            R.drawable.vector_drawable05,
            R.drawable.vector_drawable06,
            R.drawable.vector_drawable07,
            R.drawable.vector_drawable08,
            R.drawable.vector_drawable09,
            R.drawable.vector_drawable10,
            R.drawable.vector_drawable11,
            R.drawable.vector_drawable12,
            R.drawable.vector_drawable13,
            R.drawable.vector_drawable14,
            R.drawable.vector_drawable15,
            R.drawable.vector_drawable16,
            R.drawable.vector_drawable17,
            R.drawable.vector_drawable18,
            R.drawable.vector_drawable19,
            R.drawable.vector_drawable20,
            R.drawable.vector_drawable21,
            R.drawable.vector_drawable22,
            R.drawable.vector_drawable23,
            R.drawable.vector_drawable24,
            R.drawable.vector_drawable25,
            R.drawable.vector_drawable26,
            R.drawable.vector_drawable27,
            R.drawable.vector_drawable28,
            R.drawable.vector_drawable29,
            R.drawable.vector_drawable30,
            R.drawable.vector_drawable_scale0,
            R.drawable.vector_drawable_scale1,
            R.drawable.vector_drawable_scale2,
            R.drawable.vector_drawable_scale3,
    };

    public static VectorDrawable create(Resources resources, int rid) {
        try {
            final XmlPullParser parser = resources.getXml(rid);
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            int type;
            while ((type=parser.next()) != XmlPullParser.START_TAG &&
                    type != XmlPullParser.END_DOCUMENT) {
                // Empty loop
            }
            if (type != XmlPullParser.START_TAG) {
                throw new XmlPullParserException("No start tag found");
            }

            final VectorDrawable drawable = new VectorDrawable();
            drawable.inflate(resources, parser, attrs);

            return drawable;
        } catch (XmlPullParserException e) {
            Log.e(LOGCAT, "parser error", e);
        } catch (IOException e) {
            Log.e(LOGCAT, "parser error", e);
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scrollView = new ScrollView(this);
        GridLayout container = new GridLayout(this);
        scrollView.addView(container);
        container.setColumnCount(4);
        Resources res = this.getResources();
        container.setBackgroundColor(0xFF888888);
        VectorDrawable []d = new VectorDrawable[icon.length];
        long time =  android.os.SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < icon.length; i++) {
             d[i] = create(res,icon[i]);
        }
        time =  android.os.SystemClock.elapsedRealtimeNanos()-time;
        TextView t = new TextView(this);
        DecimalFormat df = new DecimalFormat("#.##");
        t.setText("avgL=" + df.format(time / (icon.length * 1000000.)) + " ms");
        container.addView(t);
        time =  android.os.SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < icon.length; i++) {
            Button button = new Button(this);
            button.setWidth(200);
            button.setBackgroundResource(icon[i]);
            container.addView(button);
        }
        setContentView(scrollView);
        time =  android.os.SystemClock.elapsedRealtimeNanos()-time;
        t = new TextView(this);
        t.setText("avgS=" + df.format(time / (icon.length * 1000000.)) + " ms");
        container.addView(t);
    }
}
