/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.google.android.maps;

import com.android.layoutlib.bridge.MockView;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.View;

/**
 * Mock version of the MapView.
 * Only non override public methods from the real MapView have been added in there.
 * Methods that take an unknown class as parameter or as return object, have been removed for now.
 * 
 * TODO: generate automatically.
 *
 */
public class MapView extends MockView {

    /**
     * Construct a new WebView with a Context object.
     * @param context A Context object used to access application assets.
     */
    public MapView(Context context) {
        this(context, null);
    }

    /**
     * Construct a new WebView with layout parameters.
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     */
    public MapView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.mapViewStyle);
    }

    /**
     * Construct a new WebView with layout parameters and a default style.
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     * @param defStyle The default style resource ID.
     */
    public MapView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }
    
    // START FAKE PUBLIC METHODS
    
    public void displayZoomControls(boolean takeFocus) {
    }

    public boolean canCoverCenter() {
        return false;
    }

    public void preLoad() {
    }

    public int getZoomLevel() {
        return 0;
    }

    public void setSatellite(boolean on) {
    }

    public boolean isSatellite() {
        return false;
    }

    public void setTraffic(boolean on) {
    }

    public boolean isTraffic() {
        return false;
    }

    public void setStreetView(boolean on) {
    }

    public boolean isStreetView() {
        return false;
    }

    public int getLatitudeSpan() {
        return 0;
    }

    public int getLongitudeSpan() {
        return 0;
    }

    public int getMaxZoomLevel() {
        return 0;
    }

    public void onSaveInstanceState(Bundle state) {
    }

    public void onRestoreInstanceState(Bundle state) {
    }

    public View getZoomControls() {
        return null;
    }
}
