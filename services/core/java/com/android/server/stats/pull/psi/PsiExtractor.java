/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.stats.pull.psi;

import static java.util.stream.Collectors.joining;

import android.annotation.Nullable;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PsiExtractor {
    private static final String TAG = "PsiExtractor";

    // Paths for PSI files are guarded by SELinux policy. PCS needs to be explicitly
    // allowlisted to access these files.
    private static final String PSI_MEMORY_PATH = "/proc/pressure/memory";
    private static final String PSI_IO_PATH = "/proc/pressure/io";
    private static final String PSI_CPU_PATH = "/proc/pressure/cpu";

    // The patterns matching a line of PSI output such as
    // "some avg10=0.12 avg60=0.34 avg300=0.56 total=123456" or
    // "full avg10=0.12 avg60=0.34 avg300=0.56 total=123456" to extract the stalling percentage
    // values for "some" and "full" line of PSI output respectively.
    private static final String PSI_PATTERN_TEMPLATE =
            ".*{0} avg10=(\\d+.\\d+) avg60=(\\d+.\\d+) avg300=(\\d+.\\d+) total=(\\d+).*";
    private static final String SOME = "some";
    private static final String FULL = "full";
    private final PsiReader mPsiReader;

    public PsiExtractor() {
        mPsiReader = new PsiReader();
    }
    public PsiExtractor(PsiReader psiReader) {
        mPsiReader = psiReader;
    }

    /**
    * Parses /pressure/proc/{resourceType} kernel file to extract the Pressure Stall Information
    * (PSI), more information: can be found at https://docs.kernel.org/accounting/psi.html.
    *
    * @param resourceType (Memory/CPU/IO) to get the PSI for.
    */
    @Nullable
    public PsiData getPsiData(PsiData.ResourceType resourceType) {
        String psiFileData;
        if (resourceType == PsiData.ResourceType.MEMORY) {
            psiFileData = mPsiReader.read(PSI_MEMORY_PATH);
        } else if (resourceType == PsiData.ResourceType.IO) {
            psiFileData = mPsiReader.read(PSI_IO_PATH);
        } else if (resourceType == PsiData.ResourceType.CPU) {
            psiFileData = mPsiReader.read(PSI_CPU_PATH);
        } else {
            Log.w(TAG, "PsiExtractor failure: cannot read kernel source file, returning null");
            return null;
        }
        return parsePsiData(psiFileData, resourceType);
    }

    @Nullable
    private static PsiData.AppsStallInfo parsePsiString(
            String psiFileData, String appType, PsiData.ResourceType resourceType) {
        // There is an extra case of file content: the CPU full is undefined and isn't reported for
        // earlier versions. It should be always propagated as 0, but for the current logic purposes
        // we will report atom only if at least one value (some/full) is presented. Thus, hardcoding
        // the "full" line as 0 only when the "some" line is presented.
        if (appType == FULL && resourceType == PsiData.ResourceType.CPU) {
            if (psiFileData.contains(SOME) && !psiFileData.contains(FULL)) {
                return new PsiData.AppsStallInfo((float) 0.0, (float) 0.0, (float) 0.0, 0);
            }
        }

        Pattern psiStringPattern = Pattern.compile(
                MessageFormat.format(PSI_PATTERN_TEMPLATE, appType));
        Matcher psiLineMatcher = psiStringPattern.matcher(psiFileData);

        // Parsing the line starts with "some" in the expected output.
        // The line for "some" should always be present in PSI output. The output must be somehow
        // malformed if the line cannot be matched.
        if (!psiLineMatcher.find()) {
            Log.w(TAG,
                    "Returning null: the line \"" +  appType + "\" is not in expected pattern.");
            return null;
        }
        try {
            return new PsiData.AppsStallInfo(
                    Float.parseFloat(psiLineMatcher.group(1)),
                    Float.parseFloat(psiLineMatcher.group(2)),
                    Float.parseFloat(psiLineMatcher.group(3)),
                    Long.parseLong(psiLineMatcher.group(4)));
        } catch (NumberFormatException e) {
            Log.w(TAG,
                    "Returning null: some value in line \"" +  appType
                            + "\" cannot be parsed as numeric.");
            return null;
        }
    }

    @Nullable
    private static PsiData parsePsiData(
                                         String psiFileData, PsiData.ResourceType resourceType) {
        PsiData.AppsStallInfo someAppsStallInfo = parsePsiString(psiFileData, SOME, resourceType);
        PsiData.AppsStallInfo fullAppsStallInfo = parsePsiString(psiFileData, FULL, resourceType);

        if (someAppsStallInfo == null && fullAppsStallInfo == null) {
            Log.w(TAG, "Returning empty PSI: some or full line are failed to parse");
            return null;
        } else if (someAppsStallInfo == null) {
            Log.d(TAG, "Replacing some info with empty PSI record for the resource type "
                    + resourceType);
            someAppsStallInfo = new PsiData.AppsStallInfo(
                    (float) -1.0, (float) -1.0, (float) -1.0, -1);
        } else if (fullAppsStallInfo == null) {
            Log.d(TAG, "Replacing full info with empty PSI record for the resource type "
                    + resourceType);
            fullAppsStallInfo = new PsiData.AppsStallInfo(
                    (float) -1.0, (float) -1.0, (float) -1.0, -1);
        }
        return new PsiData(resourceType, someAppsStallInfo, fullAppsStallInfo);
    }

    /** Dependency class */
    public static class PsiReader {
        /**
        * Reads file from provided path and returns its content if the file found, null otherwise.
        *
        * @param filePath file path to read.
        */
        @Nullable
        public String read(String filePath) {
            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(
                                 new FileInputStream(filePath)))) {
                return br.lines().collect(joining(System.lineSeparator()));
            } catch (IOException e) {
                Log.w(TAG, "Cannot read file " +  filePath);
                return null;
            }
        }
    }
}
