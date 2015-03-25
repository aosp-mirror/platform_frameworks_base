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

package com.android.layoutlib.bridge.bars;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.layoutlib.bridge.impl.ParserFactory;
import com.android.resources.Density;

import org.xmlpull.v1.XmlPullParserException;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

public class StatusBar extends CustomBar {

    private final int mSimulatedPlatformVersion;
    /** Status bar background color attribute name. */
    private static final String ATTR_COLOR = "colorPrimaryDark";

    public StatusBar(BridgeContext context, Density density, int direction, boolean RtlEnabled,
            int simulatedPlatformVersion) throws XmlPullParserException {
        // FIXME: if direction is RTL but it's not enabled in application manifest, mirror this bar.
        super(context, LinearLayout.HORIZONTAL, "/bars/status_bar.xml", "status_bar.xml",
                simulatedPlatformVersion);
        mSimulatedPlatformVersion = simulatedPlatformVersion;

        // FIXME: use FILL_H?
        setGravity(Gravity.START | Gravity.TOP | Gravity.RIGHT);
        int color = getThemeAttrColor(ATTR_COLOR, true);
        setBackgroundColor(color == 0 ? Config.getStatusBarColor(simulatedPlatformVersion) : color);

        // Cannot access the inside items through id because no R.id values have been
        // created for them.
        // We do know the order though.
        // 0 is the spacer
        loadIcon(1, "stat_sys_wifi_signal_4_fully."
                        + Config.getWifiIconType(simulatedPlatformVersion), density);
        loadIcon(2, "stat_sys_battery_100.png", density);
        setText(3, Config.getTime(simulatedPlatformVersion), false)
                .setTextColor(Config.getTimeColor(simulatedPlatformVersion));
    }

    @Override
    protected void loadIcon(int index, String iconName, Density density) {
        if (!iconName.endsWith(".xml")) {
            super.loadIcon(index, iconName, density);
            return;
        }
        View child = getChildAt(index);
        if (child instanceof ImageView) {
            ImageView imageView = (ImageView) child;
            // The xml is stored only in xhdpi.
            IconLoader iconLoader = new IconLoader(iconName, Density.XHIGH,
                    mSimulatedPlatformVersion, null);
            InputStream stream = iconLoader.getIcon();

            if (stream != null) {
                try {
                    BridgeXmlBlockParser parser = new BridgeXmlBlockParser(
                            ParserFactory.create(stream, null), (BridgeContext) mContext, true);
                    imageView.setImageDrawable(
                            Drawable.createFromXml(mContext.getResources(), parser));
                } catch (XmlPullParserException e) {
                    Bridge.getLog().error(LayoutLog.TAG_BROKEN, "Unable to draw wifi icon", e,
                            null);
                } catch (IOException e) {
                    Bridge.getLog().error(LayoutLog.TAG_BROKEN, "Unable to draw wifi icon", e,
                            null);
                }
            }
        }
    }

    @Override
    protected TextView getStyleableTextView() {
        return null;
    }
}
