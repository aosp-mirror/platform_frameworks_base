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

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.StreamTokenizer;
import java.io.StringReader;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Tests for the StreamTokenizer
 */
public class StreamTokenizerTest extends TestCase {
    
    @MediumTest
    public void testStreamTokenizer() throws Exception {
        String str = "Testing 12345 \n alpha \r\n omega";
        String strb = "-3.8 'BLIND mice' \r sEe /* how */ they run";
        StringReader aa = new StringReader(str);
        StringReader ba = new StringReader(strb);
        StreamTokenizer a = new StreamTokenizer(aa);
        StreamTokenizer b = new StreamTokenizer(ba);

        assertEquals(1, a.lineno());
        assertEquals(StreamTokenizer.TT_WORD, a.nextToken());
        assertEquals("Token[Testing], line 1", a.toString());
        assertEquals(StreamTokenizer.TT_NUMBER, a.nextToken());
        assertEquals("Token[n=12345.0], line 1", a.toString());
        assertEquals(StreamTokenizer.TT_WORD, a.nextToken());
        assertEquals("Token[alpha], line 2", a.toString());
        assertEquals(StreamTokenizer.TT_WORD, a.nextToken());
        assertEquals("Token[omega], line 3", a.toString());
        assertEquals(StreamTokenizer.TT_EOF, a.nextToken());
        assertEquals("Token[EOF], line 3", a.toString());

        b.commentChar('u');
        b.eolIsSignificant(true);
        b.lowerCaseMode(true);
        b.ordinaryChar('y');
        b.slashStarComments(true);

        assertEquals(StreamTokenizer.TT_NUMBER, b.nextToken());
        assertEquals(-3.8, b.nval);
        assertEquals("Token[n=-3.8], line 1", b.toString());
        assertEquals(39, b.nextToken()); // '
        assertEquals("Token[BLIND mice], line 1", b.toString());
        assertEquals(10, b.nextToken()); // \n
        assertEquals("Token[EOL], line 2", b.toString());
        assertEquals(StreamTokenizer.TT_WORD, b.nextToken());
        assertEquals("Token[see], line 2", b.toString());
        assertEquals(StreamTokenizer.TT_WORD, b.nextToken());
        assertEquals("Token[the], line 2", b.toString());
        assertEquals(121, b.nextToken()); // y
        assertEquals("Token['y'], line 2", b.toString());
        assertEquals(StreamTokenizer.TT_WORD, b.nextToken());
        assertEquals("Token[r], line 2", b.toString());
        assertEquals(StreamTokenizer.TT_EOF, b.nextToken());
        assertEquals("Token[EOF], line 2", b.toString());

        // A harmony regression test
        byte[] data = new byte[]{(byte) '-'};
        StreamTokenizer tokenizer = new StreamTokenizer(new ByteArrayInputStream(data));
        tokenizer.nextToken();
        String result = tokenizer.toString();
        assertEquals("Token['-'], line 1", result);

        // another harmony regression test
        byte[] data2 = new byte[]{(byte) '"',
                (byte) 'H',
                (byte) 'e',
                (byte) 'l',
                (byte) 'l',
                (byte) 'o',
                (byte) '"'};
        StreamTokenizer tokenizer2 = new StreamTokenizer(new ByteArrayInputStream(data2));
        tokenizer2.nextToken();
        result = tokenizer2.toString();
        assertEquals("Token[Hello], line 1", result);
    }
}
