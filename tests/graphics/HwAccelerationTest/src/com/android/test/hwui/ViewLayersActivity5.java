/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

@SuppressWarnings({"UnusedDeclaration"})
public class ViewLayersActivity5 extends Activity {
    private final Paint mPaint = new Paint();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        init();

        setContentView(R.layout.view_layers_5);
        setupList(R.id.list1);
    }

    public static class ClipFrameLayout extends FrameLayout {
        private final Path mClipPath = new Path();
        private boolean mClipEnabled;

        public ClipFrameLayout(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public ClipFrameLayout(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        public boolean isClipEnabled() {
            return mClipEnabled;
        }

        public void setClipEnabled(boolean clipEnabled) {
            mClipEnabled = clipEnabled;
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (mClipEnabled) {
                mClipPath.reset();
                mClipPath.addCircle(getWidth() / 2.0f, getHeight() / 2.0f,
                        Math.min(getWidth(), getHeight()) / 3.0f, Path.Direction.CW);

                canvas.clipPath(mClipPath);
            }
            super.dispatchDraw(canvas);
        }
    }

    private void init() {
        mPaint.setColorFilter(new PorterDuffColorFilter(0xff00ff00, PorterDuff.Mode.MULTIPLY));
    }

    public void enableClip(View v) {
        ((ClipFrameLayout) findViewById(R.id.container)).setClipEnabled(true);
    }

    public void disableClip(View v) {
        ((ClipFrameLayout) findViewById(R.id.container)).setClipEnabled(false);
    }

    public void enableLayer(View v) {
        findViewById(R.id.container).setLayerType(View.LAYER_TYPE_HARDWARE, mPaint);
    }

    public void disableLayer(View v) {
        findViewById(R.id.container).setLayerType(View.LAYER_TYPE_NONE, null);
    }
    
    public void growLayer(View v) {
        findViewById(R.id.container).getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        findViewById(R.id.container).requestLayout();
    }

    public void shrinkLayer(View v) {
        findViewById(R.id.container).getLayoutParams().height = 300;
        findViewById(R.id.container).requestLayout();
    }
    
    private void setupList(int listId) {
        final ListView list = findViewById(listId);
        list.setAdapter(new SimpleListAdapter(this));
    }

    private static class SimpleListAdapter extends ArrayAdapter<String> {
        public SimpleListAdapter(Context context) {
            super(context, android.R.layout.simple_list_item_1, DATA_LIST);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView v = (TextView) super.getView(position, convertView, parent);
            final Resources r = getContext().getResources();
            final DisplayMetrics metrics = r.getDisplayMetrics();
            v.setCompoundDrawablePadding((int) (6 * metrics.density + 0.5f));
            v.setCompoundDrawablesWithIntrinsicBounds(r.getDrawable(R.drawable.icon),
                    null, null, null);
            return v;
        }
    }

    private static final String[] DATA_LIST = {
            "Afghanistan", "Albania", "Algeria", "American Samoa", "Andorra",
            "Angola", "Anguilla", "Antarctica", "Antigua and Barbuda", "Argentina",
            "Armenia", "Aruba", "Australia", "Austria", "Azerbaijan",
            "Bahrain", "Bangladesh", "Barbados", "Belarus", "Belgium",
            "Belize", "Benin", "Bermuda", "Bhutan", "Bolivia",
            "Bosnia and Herzegovina", "Botswana", "Bouvet Island", "Brazil",
            "British Indian Ocean Territory", "British Virgin Islands", "Brunei", "Bulgaria",
            "Burkina Faso", "Burundi", "Cote d'Ivoire", "Cambodia", "Cameroon", "Canada", "Cape Verde",
            "Cayman Islands", "Central African Republic", "Chad", "Chile", "China",
            "Christmas Island", "Cocos (Keeling) Islands", "Colombia", "Comoros", "Congo",
            "Cook Islands", "Costa Rica", "Croatia", "Cuba", "Cyprus", "Czechia",
            "Democratic Republic of the Congo", "Denmark", "Djibouti", "Dominica", "Dominican Republic",
            "East Timor", "Ecuador", "Egypt", "El Salvador", "Equatorial Guinea", "Eritrea",
            "Estonia", "Ethiopia", "Faeroe Islands", "Falkland Islands", "Fiji", "Finland",
            "Former Yugoslav Republic of Macedonia", "France", "French Guiana", "French Polynesia",
            "French Southern Territories", "Gabon", "Georgia", "Germany", "Ghana", "Gibraltar",
            "Greece", "Greenland", "Grenada", "Guadeloupe", "Guam", "Guatemala", "Guinea", "Guinea-Bissau",
            "Guyana", "Haiti", "Heard Island and McDonald Islands", "Honduras", "Hong Kong", "Hungary",
            "Iceland", "India", "Indonesia", "Iran", "Iraq", "Ireland", "Israel", "Italy", "Jamaica",
            "Japan", "Jordan", "Kazakhstan", "Kenya", "Kiribati", "Kuwait", "Kyrgyzstan", "Laos",
            "Latvia", "Lebanon", "Lesotho", "Liberia", "Libya", "Liechtenstein", "Lithuania", "Luxembourg",
            "Macau", "Madagascar", "Malawi", "Malaysia", "Maldives", "Mali", "Malta", "Marshall Islands",
            "Martinique", "Mauritania", "Mauritius", "Mayotte", "Mexico", "Micronesia", "Moldova",
            "Monaco", "Mongolia", "Montserrat", "Morocco", "Mozambique", "Myanmar", "Namibia",
            "Nauru", "Nepal", "Netherlands", "Netherlands Antilles", "New Caledonia", "New Zealand",
            "Nicaragua", "Niger", "Nigeria", "Niue", "Norfolk Island", "North Korea", "Northern Marianas",
            "Norway", "Oman", "Pakistan", "Palau", "Panama", "Papua New Guinea", "Paraguay", "Peru",
            "Philippines", "Pitcairn Islands", "Poland", "Portugal", "Puerto Rico", "Qatar",
            "Reunion", "Romania", "Russia", "Rwanda", "Sqo Tome and Principe", "Saint Helena",
            "Saint Kitts and Nevis", "Saint Lucia", "Saint Pierre and Miquelon",
            "Saint Vincent and the Grenadines", "Samoa", "San Marino", "Saudi Arabia", "Senegal",
            "Seychelles", "Sierra Leone", "Singapore", "Slovakia", "Slovenia", "Solomon Islands",
            "Somalia", "South Africa", "South Georgia and the South Sandwich Islands", "South Korea",
            "Spain", "Sri Lanka", "Sudan", "Suriname", "Svalbard and Jan Mayen", "Swaziland", "Sweden",
            "Switzerland", "Syria", "Taiwan", "Tajikistan", "Tanzania", "Thailand", "The Bahamas",
            "The Gambia", "Togo", "Tokelau", "Tonga", "Trinidad and Tobago", "Tunisia", "Turkey",
            "Turkmenistan", "Turks and Caicos Islands", "Tuvalu", "Virgin Islands", "Uganda",
            "Ukraine", "United Arab Emirates", "United Kingdom",
            "United States", "United States Minor Outlying Islands", "Uruguay", "Uzbekistan",
            "Vanuatu", "Vatican City", "Venezuela", "Vietnam", "Wallis and Futuna", "Western Sahara",
            "Yemen", "Yugoslavia", "Zambia", "Zimbabwe"
    };
}
