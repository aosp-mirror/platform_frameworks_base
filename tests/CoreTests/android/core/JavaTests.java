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

package android.core;

import junit.framework.TestSuite;

public class JavaTests {
    public static TestSuite suite() {
        TestSuite suite = new TestSuite(JavaTests.class.getName());

        //Disabling until bug http://b/issue?id=1200337 is resolved
        //suite.addTestSuite(RequestAPITest.class);
        suite.addTestSuite(MathTest.class);
        suite.addTestSuite(StrictMathTest.class);
        suite.addTestSuite(HashMapPerfTest.class);
        suite.addTestSuite(TreeMapTest.class);
        suite.addTestSuite(FloatDoubleTest.class);
        suite.addTestSuite(Sha1Test.class);
        suite.addTestSuite(NIOTest.class);
        suite.addTestSuite(ReflectArrayTest.class);
        //Commenting out until we find a better way to exclude from continuous testing.
        //suite.addTestSuite(URLTest.class);
        suite.addTestSuite(URITest.class);
        suite.addTestSuite(RegexTest.class);
        suite.addTestSuite(HashMapTest.class);
        suite.addTestSuite(ArrayListTest.class);
        suite.addTestSuite(BooleanTest.class);
        suite.addTestSuite(StringTest.class);
        suite.addTestSuite(BufferedReaderTest.class);
        suite.addTestSuite(CharArrayReaderTest.class);
        suite.addTestSuite(PushbackReaderTest.class);
        suite.addTestSuite(StringReaderTest.class);
        suite.addTestSuite(StreamTokenizerTest.class);
        suite.addTestSuite(ByteArrayInputStreamTest.class);
        suite.addTestSuite(DataInputStreamTest.class);
        suite.addTestSuite(BufferedInputStreamTest.class);
        suite.addTestSuite(PushbackInputStreamTest.class);
        suite.addTestSuite(ByteArrayOutputStreamTest.class);
        suite.addTestSuite(DataOutputStreamTest.class);
        suite.addTestSuite(BufferedOutputStreamTest.class);
        suite.addTestSuite(CharArrayWriterTest.class);
        suite.addTestSuite(StringWriterTest.class);
        suite.addTestSuite(PrintWriterTest.class);
        suite.addTestSuite(BufferedWriterTest.class);
        suite.addTestSuite(ClassTest.class);
        //To be unccommented when Bug #799327 is fixed.
        //suite.addTestSuite(ClassLoaderTest.class);
        suite.addTestSuite(LineNumberReaderTest.class);
        suite.addTestSuite(InputStreamReaderTest.class);
        suite.addTestSuite(OutputStreamWriterTest.class);
        suite.addTestSuite(EnumTest.class);
        suite.addTestSuite(ParseIntTest.class);
        suite.addTestSuite(PipedStreamTest.class);
        suite.addTestSuite(LocaleTest.class);
        //Commenting out until we find a better way to exclude from continuous testing.
        //suite.addTestSuite(InetAddrTest.class);
        suite.addTestSuite(SocketTest.class);
        suite.addTestSuite(ChecksumTest.class);
        suite.addTestSuite(DeflateTest.class);
        suite.addTestSuite(ZipStreamTest.class);
        suite.addTestSuite(GZIPStreamTest.class);
        suite.addTestSuite(ZipFileTest.class);
        suite.addTestSuite(FileTest.class);
        suite.addTestSuite(SQLiteJDBCDriverTest.class);
        suite.addTestSuite(AtParserTest.class);
        suite.addTestSuite(DatagramTest.class);
        suite.addTestSuite(CryptoTest.class);
        suite.addTestSuite(MiscRegressionTest.class);

        return suite;
    }
}
