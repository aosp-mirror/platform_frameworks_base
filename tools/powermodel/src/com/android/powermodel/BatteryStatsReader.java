/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel;

import java.io.InputStream;
import java.io.IOException;
import com.android.powermodel.component.ModemBatteryStatsReader;

public class BatteryStatsReader {
    /**
     * Construct a reader.
     */
    public BatteryStatsReader() {
    }

    /**
     * Parse a powermodel.xml file and return a PowerProfile object.
     *
     * @param stream An InputStream containing the batterystats output.
     *
     * @throws ParseException Thrown when the xml file can not be parsed.
     * @throws IOException When there is a problem reading the stream.
     */
    public static ActivityReport parse(InputStream stream) throws ParseException, IOException {
        final Parser parser = new Parser(stream);
        return parser.parse();
    }

    /**
     * Implements the reading and power model logic.
     */
    private static class Parser {
        final InputStream mStream;
        final ActivityReport mResult;
        RawBatteryStats mBs;

        /**
         * Constructor to capture the parameters to read.
         */
        Parser(InputStream stream) {
            mStream = stream;
            mResult = new ActivityReport();
        }

        /**
         * Read the stream, parse it, and apply the power model.
         * Do not call this more than once.
         */
        ActivityReport parse() throws ParseException, IOException {
            mBs = RawBatteryStats.parse(mStream);

            final ActivityReport.Builder report = new ActivityReport.Builder();

            report.addActivity(Component.MODEM, ModemBatteryStatsReader.createActivities(mBs));

            return report.build();
        }
    }
}

