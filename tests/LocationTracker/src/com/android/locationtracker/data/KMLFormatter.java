/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.locationtracker.data;

import com.android.locationtracker.data.TrackerEntry.EntryType;

import android.location.Location;

/**
 * Formats tracker data as KML output
 */
class KMLFormatter implements IFormatter {

    public String getHeader() {
        LineBuilder builder = new LineBuilder();
        builder.addLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        builder.addLine("<kml xmlns=\"http://earth.google.com/kml/2.2\">");
        builder.addLine("<Document>");
        return builder.toString();
    }

    public String getFooter() {
        LineBuilder builder = new LineBuilder();
        builder.addLine("</Document>");
        builder.addLine("</kml>");
        return builder.toString();
    }

    public String getOutput(TrackerEntry entry) {
        LineBuilder builder = new LineBuilder();

        if (entry.getType() == EntryType.LOCATION_TYPE) {

            Location loc = entry.getLocation();
            builder.addLine("<Placemark>");
            builder.addLine("<description>");
            builder.addLine("accuracy = " + loc.getAccuracy());
            builder.addLine("distance from last network location  = "
                    + entry.getDistFromNetLocation());
            builder.addLine("</description>");
            builder.addLine("<TimeStamp>");
            builder.addLine("<when>" + entry.getTimestamp() + "</when>");
            builder.addLine("</TimeStamp>");
            builder.addLine("<Point>");
            builder.addLine("<coordinates>");
            builder.addLine(loc.getLongitude() + "," + loc.getLatitude() + ","
                    + loc.getAltitude());
            builder.addLine("</coordinates>");
            builder.addLine("</Point>");
            builder.addLine("</Placemark>");
        }
        return builder.toString();
    }

    private static class LineBuilder {
        private StringBuilder mBuilder;

        public LineBuilder() {
            mBuilder = new StringBuilder();
        }

        public void addLine(String line) {
            mBuilder.append(line);
            mBuilder.append("\n");
        }

        @Override
        public String toString() {
            return mBuilder.toString();
        }

    }

}
