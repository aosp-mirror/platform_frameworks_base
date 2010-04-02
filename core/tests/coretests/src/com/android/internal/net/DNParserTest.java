/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.internal.net;

import com.android.internal.net.DNParser;

import javax.security.auth.x500.X500Principal;

import junit.framework.TestCase;

public class DNParserTest extends TestCase {
    public void testFind() {
        checkFind("", "cn", null);
        checkFind("ou=xxx", "cn", null);
        checkFind("ou=xxx,cn=xxx", "cn", "xxx");
        checkFind("ou=xxx+cn=yyy,cn=zzz+cn=abc", "cn", "yyy");
        checkFind("2.5.4.3=a,ou=xxx", "cn", "a"); // OID
        checkFind("cn=a,cn=b", "cn", "a");
        checkFind("ou=Cc,ou=Bb,ou=Aa", "ou", "Cc");
        checkFind("cn=imap.gmail.com", "cn", "imap.gmail.com");

        // Quoted string (see http://www.ietf.org/rfc/rfc2253.txt)
        checkFind("o=\"\\\" a ,=<>#;\"", "o", "\" a ,=<>#;");
        checkFind("o=abc\\,def", "o", "abc,def");

        // UTF-8 (example in rfc 2253)
        checkFind("cn=Lu\\C4\\8Di\\C4\\87", "cn", "\u004c\u0075\u010d\u0069\u0107");

        // whitespaces
        checkFind("ou=a, o=  a  b  ,cn=x", "o", "a  b");
        checkFind("o=\"  a  b  \" ,cn=x", "o", "  a  b  ");
    }

    private void checkFind(String dn, String attrType, String expected) {
        String actual = new DNParser(new X500Principal(dn)).find(attrType);
        assertEquals("dn:" + dn + "  attr:" + attrType, expected, actual);
    }
}
