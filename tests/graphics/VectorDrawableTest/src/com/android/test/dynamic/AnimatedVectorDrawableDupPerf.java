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
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.text.DecimalFormat;


@SuppressWarnings({"UnusedDeclaration"})
public class AnimatedVectorDrawableDupPerf extends Activity {

    private static final String LOGTAG = "AnimatedVectorDrawableDupPerf";
    protected int[] icon = {
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_linear_progress_bar,
   };

    /** @hide */
    public static AnimatedVectorDrawable create(Resources resources, int rid) {
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

            final AnimatedVectorDrawable drawable = new AnimatedVectorDrawable();
            drawable.inflate(resources, parser, attrs);

            return drawable;
        } catch (XmlPullParserException e) {
            Log.e(LOGTAG, "parser error", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "parser error", e);
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scrollView = new ScrollView(this);
        GridLayout container = new GridLayout(this);
        scrollView.addView(container);
        container.setColumnCount(5);
        Resources res = this.getResources();
        container.setBackgroundColor(0xFF888888);
        AnimatedVectorDrawable []d = new AnimatedVectorDrawable[icon.length];
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
