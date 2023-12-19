/*
 * Copyright (C) 2022 The Android Open Source Project.
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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlSerializer;

import java.io.CharArrayWriter;
import java.lang.reflect.Constructor;
import java.util.Random;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class XmlSerializePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Object[] getParams() {
        return new Object[][] {
            new Object[] {"0.99 0.7 0.7 0.7 0.7 0.7", 854328},
            new Object[] {"0.999 0.3 0.3 0.95 0.9 0.9", 854328},
            new Object[] {"0.99 0.7 0.7 0.7 0.7 0.7", 312547},
            new Object[] {"0.999 0.3 0.3 0.95 0.9 0.9", 312547}
        };
    }

    double[] mDataset;
    private Constructor<? extends XmlSerializer> mKxmlConstructor;
    private Constructor<? extends XmlSerializer> mFastConstructor;

    private void serializeRandomXml(Constructor<? extends XmlSerializer> ctor, long mSeed)
            throws Exception {
        double contChance = mDataset[0];
        double levelUpChance = mDataset[1];
        double levelDownChance = mDataset[2];
        double attributeChance = mDataset[3];
        double writeChance1 = mDataset[4];
        double writeChance2 = mDataset[5];

        XmlSerializer serializer = (XmlSerializer) ctor.newInstance();

        CharArrayWriter w = new CharArrayWriter();
        serializer.setOutput(w);
        int level = 0;
        Random r = new Random(mSeed);
        char[] toWrite = {'a', 'b', 'c', 'd', 's', 'z'};
        serializer.startDocument("UTF-8", true);
        while (r.nextDouble() < contChance) {
            while (level > 0 && r.nextDouble() < levelUpChance) {
                serializer.endTag("aaaaaa", "bbbbbb");
                level--;
            }
            while (r.nextDouble() < levelDownChance) {
                serializer.startTag("aaaaaa", "bbbbbb");
                level++;
            }
            serializer.startTag("aaaaaa", "bbbbbb");
            level++;
            while (r.nextDouble() < attributeChance) {
                serializer.attribute("aaaaaa", "cccccc", "dddddd");
            }
            serializer.endTag("aaaaaa", "bbbbbb");
            level--;
            while (r.nextDouble() < writeChance1) serializer.text(toWrite, 0, 5);
            while (r.nextDouble() < writeChance2) serializer.text("Textxtsxtxtxt ");
        }
        serializer.endDocument();
    }

    @SuppressWarnings("unchecked")
    public void setUp(String datasetAsString) throws Exception {
        mKxmlConstructor =
                (Constructor)
                        Class.forName("com.android.org.kxml2.io.KXmlSerializer").getConstructor();
        mFastConstructor =
                (Constructor)
                        Class.forName("com.android.internal.util.FastXmlSerializer")
                                .getConstructor();
        String[] splitStrings = datasetAsString.split(" ");
        mDataset = new double[splitStrings.length];
        for (int i = 0; i < splitStrings.length; i++) {
            mDataset[i] = Double.parseDouble(splitStrings[i]);
        }
    }

    private void internalTimeSerializer(Constructor<? extends XmlSerializer> ctor, int seed)
            throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            serializeRandomXml(ctor, seed);
        }
    }

    @Test
    @Parameters(method = "getParams")
    public void timeKxml(String datasetAsString, int seed) throws Exception {
        setUp(datasetAsString);
        internalTimeSerializer(mKxmlConstructor, seed);
    }

    @Test
    @Parameters(method = "getParams")
    public void timeFast(String datasetAsString, int seed) throws Exception {
        setUp(datasetAsString);
        internalTimeSerializer(mFastConstructor, seed);
    }
}
