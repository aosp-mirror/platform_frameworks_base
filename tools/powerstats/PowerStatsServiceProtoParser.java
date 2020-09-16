/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.powerstats;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * This class implements a utility to parse ODPM data out
 * of incident reports contained in bugreports.  The data
 * is output to STDOUT in csv format.
 */
public class PowerStatsServiceProtoParser {
    private static void printRailInfo(PowerStatsServiceProto proto) {
        String csvHeader = new String();
        for (int i = 0; i < proto.getRailInfoCount(); i++) {
            RailInfoProto railInfo = proto.getRailInfo(i);
            csvHeader += "Index" + ","
                + "Timestamp" + ","
                + railInfo.getRailName() + "/" + railInfo.getSubsysName() + ",";
        }
        System.out.println(csvHeader);
    }

    private static void printEnergyData(PowerStatsServiceProto proto) {
        int railInfoCount = proto.getRailInfoCount();

        if (railInfoCount > 0) {
            int energyDataCount = proto.getEnergyDataCount();
            int energyDataSetCount = energyDataCount / railInfoCount;

            for (int i = 0; i < energyDataSetCount; i++) {
                String csvRow = new String();
                for (int j = 0; j < railInfoCount; j++) {
                    EnergyDataProto energyData = proto.getEnergyData(i * railInfoCount + j);
                    csvRow += energyData.getIndex() + ","
                        + energyData.getTimestampMs() + ","
                        + energyData.getEnergyUws() + ",";
                }
                System.out.println(csvRow);
            }
        } else {
            System.out.println("Error:  railInfoCount is zero");
        }
    }

    private static void generateCsvFile(String pathToIncidentReport) {
        try {
            IncidentReportProto irProto =
                    IncidentReportProto.parseFrom(new FileInputStream(pathToIncidentReport));

            if (irProto.hasIncidentReport()) {
                PowerStatsServiceProto pssProto = irProto.getIncidentReport();
                printRailInfo(pssProto);
                printEnergyData(pssProto);
            } else {
                System.out.println("Incident report not found.  Exiting.");
            }
        } catch (IOException e) {
            System.out.println("Unable to open incident report file: " + pathToIncidentReport);
            System.out.println(e);
        }
    }

    /**
     * This is the entry point to parse the ODPM data out of incident reports.
     * It requires one argument which is the path to the incident_report.proto
     * file captured in a bugreport.
     *
     * @param args Path to incident_report.proto passed in from command line.
     */
    public static void main(String[] args) {
        if (args.length > 0) {
            generateCsvFile(args[0]);
        } else {
            System.err.println("Usage: PowerStatsServiceProtoParser <incident_report.proto>");
            System.err.println("Missing path to incident_report.proto.  Exiting.");
            System.exit(1);
        }
    }
}
