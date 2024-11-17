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

import static org.testng.AssertJUnit.assertEquals;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)

public class PsiExtractorTest {
    @Mock
    private PsiExtractor.PsiReader mPsiReader;
    private PsiExtractor mPsiExtractor;
    // PSI file content with both some and full lines.
    private static final String PSI_FILE_CONTENT_BOTH_LINES =
            "some avg10=0.12 avg60=0.34 avg300=0.56 total=12345678\n"
                    + "full avg10=0.21 avg60=0.43 avg300=0.65 total=87654321";
    // PSI file content with only some line.
    private static final String PSI_FILE_CONTENT_ONLY_SOME_LINE =
            "some avg10=0.12 avg60=0.34 avg300=0.56 total=12345678";

    // PSI file content with only full line.
    private static final String PSI_FILE_CONTENT_ONLY_FULL_LINE =
            "\nfull avg10=0.21 avg60=0.43 avg300=0.65 total=87654321";

    // PSI file content that is malformed with "avg60" missing from the both lines.
    private static final String BOTH_AVG60_MISSING_PSI_FILE_CONTENT =
            "some avg10=0.12 avg300=0.56 total=12345678\n"
                    + "full avg10=0.21 avg300=0.65 total=87654321";

    // PSI file content that is malformed with non number "avg10" from the both lines.
    private static final String NON_NUM_AVG10_PSI_FILE_CONTENT =
            "some avg10=1.a2 avg300=0.56 total=12345678\n"
                    + "full avg10=0.2s1 avg60=0.43 avg300=0.65 total=87654321";

    // PSI file content that is malformed with non number "avg300" from the both lines.
    private static final String NON_NUM_AVG300_PSI_FILE_CONTENT =
            "some avg10=0.2 avg60=0.43 avg300=0.5ss6 total=12345678\n"
                    + "full avg10=0.21 avg60=0.43 avg300=0.6b5 total=87654321";

    // PSI file content that is malformed with non number "total"  from the both lines.
    private static final String BOTH_TOTAL_MISSING_PSI_FILE_CONTENT =
            "some avg10=0.2 avg60=0.43 avg300=0.56\n"
                    + "full avg10=0.21 avg60=0.43 avg300=0.65";


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mPsiExtractor = new PsiExtractor(mPsiReader);
    }

    @Test
    public void getPsiData_bothLinesPresentedAndValidMemory() {
        Mockito.when(mPsiReader.read("/proc/pressure/memory")).thenReturn(
                PSI_FILE_CONTENT_BOTH_LINES);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.MEMORY);
        assertEquals(psiData.getResourceType(), PsiData.ResourceType.MEMORY);
        assertEquals(psiData.getSomeAvg10SecPercentage(), (float) 0.12);
        assertEquals(psiData.getSomeAvg60SecPercentage(), (float) 0.34);
        assertEquals(psiData.getSomeAvg300SecPercentage(), (float) 0.56);
        assertEquals(psiData.getSomeTotalUsec(), 12345678);
        assertEquals(psiData.getFullAvg10SecPercentage(), (float) 0.21);
        assertEquals(psiData.getFullAvg60SecPercentage(), (float) 0.43);
        assertEquals(psiData.getFullAvg300SecPercentage(), (float) 0.65);
        assertEquals(psiData.getFullTotalUsec(), 87654321);
    }

    @Test
    public void getPsiData_bothLinesPresentedAndValidCpu() {
        Mockito.when(mPsiReader.read("/proc/pressure/cpu")).thenReturn(
                PSI_FILE_CONTENT_BOTH_LINES);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.CPU);
        assertEquals(psiData.getResourceType(), PsiData.ResourceType.CPU);
        assertEquals(psiData.getSomeAvg10SecPercentage(), (float) 0.12);
        assertEquals(psiData.getSomeAvg60SecPercentage(), (float) 0.34);
        assertEquals(psiData.getSomeAvg300SecPercentage(), (float) 0.56);
        assertEquals(psiData.getSomeTotalUsec(), 12345678);
        assertEquals(psiData.getFullAvg10SecPercentage(), (float) 0.21);
        assertEquals(psiData.getFullAvg60SecPercentage(), (float) 0.43);
        assertEquals(psiData.getFullAvg300SecPercentage(), (float) 0.65);
        assertEquals(psiData.getFullTotalUsec(), 87654321);
    }

    @Test
    public void getPsiData_bothLinesPresentedAndValidIO() {
        Mockito.when(mPsiReader.read("/proc/pressure/io")).thenReturn(
                PSI_FILE_CONTENT_BOTH_LINES);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.IO);
        assertEquals(psiData.getResourceType(), PsiData.ResourceType.IO);
        assertEquals(psiData.getSomeAvg10SecPercentage(), (float) 0.12);
        assertEquals(psiData.getSomeAvg60SecPercentage(), (float) 0.34);
        assertEquals(psiData.getSomeAvg300SecPercentage(), (float) 0.56);
        assertEquals(psiData.getSomeTotalUsec(), 12345678);
        assertEquals(psiData.getFullAvg10SecPercentage(), (float) 0.21);
        assertEquals(psiData.getFullAvg60SecPercentage(), (float) 0.43);
        assertEquals(psiData.getFullAvg300SecPercentage(), (float) 0.65);
        assertEquals(psiData.getFullTotalUsec(), 87654321);
    }

    @Test
    public void getPsiData_onlySomePresentedAndValidMemory() {
        Mockito.when(mPsiReader.read("/proc/pressure/memory")).thenReturn(
                PSI_FILE_CONTENT_ONLY_SOME_LINE);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.MEMORY);
        assertEquals(psiData.getResourceType(), PsiData.ResourceType.MEMORY);
        assertEquals(psiData.getSomeAvg10SecPercentage(), (float) 0.12);
        assertEquals(psiData.getSomeAvg60SecPercentage(), (float) 0.34);
        assertEquals(psiData.getSomeAvg300SecPercentage(), (float) 0.56);
        assertEquals(psiData.getSomeTotalUsec(), 12345678);
        assertEquals(psiData.getFullAvg10SecPercentage(), (float) -1.0);
        assertEquals(psiData.getFullAvg60SecPercentage(), (float) -1.0);
        assertEquals(psiData.getFullAvg300SecPercentage(), (float) -1.0);
        assertEquals(psiData.getFullTotalUsec(), -1);
    }

    @Test
    public void getPsiData_onlySomePresentedAndValidCpu() {
        Mockito.when(mPsiReader.read("/proc/pressure/cpu")).thenReturn(
                PSI_FILE_CONTENT_ONLY_SOME_LINE);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.CPU);
        assertEquals(psiData.getResourceType(), PsiData.ResourceType.CPU);
        assertEquals(psiData.getSomeAvg10SecPercentage(), (float) 0.12);
        assertEquals(psiData.getSomeAvg60SecPercentage(), (float) 0.34);
        assertEquals(psiData.getSomeAvg300SecPercentage(), (float) 0.56);
        assertEquals(psiData.getSomeTotalUsec(), 12345678);
        assertEquals(psiData.getFullAvg10SecPercentage(), (float) 0.0);
        assertEquals(psiData.getFullAvg60SecPercentage(), (float) 0.0);
        assertEquals(psiData.getFullAvg300SecPercentage(), (float) 0.0);
        assertEquals(psiData.getFullTotalUsec(), 0);
    }

    @Test
    public void getPsiData_onlySomePresentedAndValidIO() {
        Mockito.when(mPsiReader.read("/proc/pressure/io")).thenReturn(
                PSI_FILE_CONTENT_ONLY_SOME_LINE);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.IO);
        assertEquals(psiData.getResourceType(), PsiData.ResourceType.IO);
        assertEquals(psiData.getSomeAvg10SecPercentage(), (float) 0.12);
        assertEquals(psiData.getSomeAvg60SecPercentage(), (float) 0.34);
        assertEquals(psiData.getSomeAvg300SecPercentage(), (float) 0.56);
        assertEquals(psiData.getSomeTotalUsec(), 12345678);
        assertEquals(psiData.getFullAvg10SecPercentage(), (float) -1.0);
        assertEquals(psiData.getFullAvg60SecPercentage(), (float) -1.0);
        assertEquals(psiData.getFullAvg300SecPercentage(), (float) -1.0);
        assertEquals(psiData.getFullTotalUsec(), -1);
    }

    @Test
    public void getPsiData_onlyFullPresentedAndValidMemory() {
        Mockito.when(mPsiReader.read("/proc/pressure/memory")).thenReturn(
                PSI_FILE_CONTENT_ONLY_FULL_LINE);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.MEMORY);
        assertEquals(psiData.getResourceType(), PsiData.ResourceType.MEMORY);
        assertEquals(psiData.getSomeAvg10SecPercentage(), (float) -1.0);
        assertEquals(psiData.getSomeAvg60SecPercentage(), (float) -1.0);
        assertEquals(psiData.getSomeAvg300SecPercentage(), (float) -1.0);
        assertEquals(psiData.getSomeTotalUsec(), -1);
        assertEquals(psiData.getFullAvg10SecPercentage(), (float) 0.21);
        assertEquals(psiData.getFullAvg60SecPercentage(), (float) 0.43);
        assertEquals(psiData.getFullAvg300SecPercentage(), (float) 0.65);
        assertEquals(psiData.getFullTotalUsec(), 87654321);
    }

    @Test
    public void getPsiData_onlyFullPresentedAndValidCpu() {
        Mockito.when(mPsiReader.read("/proc/pressure/cpu")).thenReturn(
                PSI_FILE_CONTENT_ONLY_FULL_LINE);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.CPU);
        assertEquals(psiData.getResourceType(), PsiData.ResourceType.CPU);
        assertEquals(psiData.getSomeAvg10SecPercentage(), (float) -1.0);
        assertEquals(psiData.getSomeAvg60SecPercentage(), (float) -1.0);
        assertEquals(psiData.getSomeAvg300SecPercentage(), (float) -1.0);
        assertEquals(psiData.getSomeTotalUsec(), -1);
        assertEquals(psiData.getFullAvg10SecPercentage(), (float) 0.21);
        assertEquals(psiData.getFullAvg60SecPercentage(), (float) 0.43);
        assertEquals(psiData.getFullAvg300SecPercentage(), (float) 0.65);
        assertEquals(psiData.getFullTotalUsec(), 87654321);
    }

    @Test
    public void getPsiData_onlyFullPresentedAndValidIO() {
        Mockito.when(mPsiReader.read("/proc/pressure/io")).thenReturn(
                PSI_FILE_CONTENT_ONLY_FULL_LINE);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.IO);
        assertEquals(psiData.getResourceType(), PsiData.ResourceType.IO);
        assertEquals(psiData.getSomeAvg10SecPercentage(), (float) -1.0);
        assertEquals(psiData.getSomeAvg60SecPercentage(), (float) -1.0);
        assertEquals(psiData.getSomeAvg300SecPercentage(), (float) -1.0);
        assertEquals(psiData.getSomeTotalUsec(), -1);
        assertEquals(psiData.getFullAvg10SecPercentage(), (float) 0.21);
        assertEquals(psiData.getFullAvg60SecPercentage(), (float) 0.43);
        assertEquals(psiData.getFullAvg300SecPercentage(), (float) 0.65);
        assertEquals(psiData.getFullTotalUsec(), 87654321);
    }

    @Test
    public void getPsiData_emptyFile() {
        Mockito.when(mPsiReader.read("/proc/pressure/memory")).thenReturn("");
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.MEMORY);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/cpu")).thenReturn("");
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.CPU);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/io")).thenReturn("");
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.IO);
        assertEquals(psiData, null);
    }

    @Test
    public void getPsiData_avg60Missing() {
        Mockito.when(mPsiReader.read("/proc/pressure/memory")).thenReturn(
                BOTH_AVG60_MISSING_PSI_FILE_CONTENT);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.MEMORY);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/cpu")).thenReturn(
                BOTH_AVG60_MISSING_PSI_FILE_CONTENT);
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.CPU);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/io")).thenReturn(
                BOTH_AVG60_MISSING_PSI_FILE_CONTENT);
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.IO);
        assertEquals(psiData, null);
    }

    @Test
    public void getPsiData_totalMissing() {
        Mockito.when(mPsiReader.read("/proc/pressure/memory")).thenReturn(
                BOTH_TOTAL_MISSING_PSI_FILE_CONTENT);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.MEMORY);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/cpu")).thenReturn(
                BOTH_TOTAL_MISSING_PSI_FILE_CONTENT);
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.CPU);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/io")).thenReturn(
                BOTH_TOTAL_MISSING_PSI_FILE_CONTENT);
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.IO);
        assertEquals(psiData, null);
    }

    @Test
    public void getPsiData_avg10NonNum() {
        Mockito.when(mPsiReader.read("/proc/pressure/memory")).thenReturn(
                NON_NUM_AVG10_PSI_FILE_CONTENT);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.MEMORY);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/cpu")).thenReturn(
                NON_NUM_AVG10_PSI_FILE_CONTENT);
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.CPU);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/io")).thenReturn(
                NON_NUM_AVG10_PSI_FILE_CONTENT);
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.IO);
        assertEquals(psiData, null);
    }

    @Test
    public void getPsiData_avg300NonNum() {
        Mockito.when(mPsiReader.read("/proc/pressure/memory")).thenReturn(
                NON_NUM_AVG300_PSI_FILE_CONTENT);
        PsiData psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.MEMORY);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/cpu")).thenReturn(
                NON_NUM_AVG300_PSI_FILE_CONTENT);
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.CPU);
        assertEquals(psiData, null);

        Mockito.when(mPsiReader.read("/proc/pressure/io")).thenReturn(
                NON_NUM_AVG300_PSI_FILE_CONTENT);
        psiData = mPsiExtractor.getPsiData(PsiData.ResourceType.IO);
        assertEquals(psiData, null);
    }
}
