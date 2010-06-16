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

package com.android.internal.http.multipart;

import junit.framework.TestCase;
import org.apache.http.Header;
import org.apache.http.util.EncodingUtils;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;

public class MultipartTest extends TestCase {

    public void testParts() throws Exception {
        StringBuffer filebuffer = new StringBuffer();
        String filepartStr = "this is file part";
        filebuffer.append(filepartStr);
        File upload = File.createTempFile("Multipart", "test");

        FileWriter outFile = new FileWriter(upload);
        BufferedWriter out = new BufferedWriter(outFile);
        try {
            out.write(filebuffer.toString());
            out.flush();
        } finally {
            out.close();
        }

        Part[] parts = new Part[3];
        parts[0] = new StringPart("stringpart", "PART1!!");
        parts[1] = new FilePart(upload.getName(), upload);
        parts[2] = new StringPart("stringpart", "PART2!!");

        MultipartEntity me = new MultipartEntity(parts);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        me.writeTo(os);
        Header h = me.getContentType();
        String boundry = EncodingUtils.getAsciiString(me.getMultipartBoundary());
        StringBuffer contentType = new StringBuffer("multipart/form-data");
        contentType.append("; boundary=");
        contentType.append(boundry);
        assertEquals("Multipart content type error", contentType.toString(), h.getValue());
        final String CRLF = "\r\n";
        StringBuffer output = new StringBuffer();

        output.append("--");
        output.append(boundry);
        output.append(CRLF);

        output.append("Content-Disposition: form-data; name=\"stringpart\"");
        output.append(CRLF);
        output.append("Content-Type: text/plain; charset=US-ASCII");
        output.append(CRLF);
        output.append("Content-Transfer-Encoding: 8bit");
        output.append(CRLF);
        output.append(CRLF);
        output.append("PART1!!");
        output.append(CRLF);

        output.append("--");
        output.append(boundry);
        output.append(CRLF);

        output.append("Content-Disposition: form-data; name=\"");
        output.append(upload.getName());
        output.append("\"; filename=\"");
        output.append(upload.getName());
        output.append("\"");

        output.append(CRLF);
        output.append("Content-Type: application/octet-stream; charset=ISO-8859-1");
        output.append(CRLF);
        output.append("Content-Transfer-Encoding: binary");
        output.append(CRLF);
        output.append(CRLF);
        output.append(filepartStr);
        output.append(CRLF);

        output.append("--");
        output.append(boundry);
        output.append(CRLF);

        output.append("Content-Disposition: form-data; name=\"stringpart\"");
        output.append(CRLF);
        output.append("Content-Type: text/plain; charset=US-ASCII");
        output.append(CRLF);
        output.append("Content-Transfer-Encoding: 8bit");
        output.append(CRLF);
        output.append(CRLF);
        output.append("PART2!!");
        output.append(CRLF);

        output.append("--");
        output.append(boundry);
        output.append("--");
        output.append(CRLF);
        // System.out.print(output.toString());
        assertEquals("Multipart content error", output.toString(), os.toString());

        // System.out.print(os.toString());
    }
}
