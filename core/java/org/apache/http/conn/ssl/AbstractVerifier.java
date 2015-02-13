/*
 * $HeadURL: http://svn.apache.org/repos/asf/httpcomponents/httpclient/trunk/module-client/src/main/java/org/apache/http/conn/ssl/AbstractVerifier.java $
 * $Revision: 653041 $
 * $Date: 2008-05-03 03:39:28 -0700 (Sat, 03 May 2008) $
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.conn.ssl;

import java.util.regex.Pattern;

import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.logging.Level;

import javax.net.ssl.DistinguishedNameParser;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * Abstract base class for all standard {@link X509HostnameVerifier} 
 * implementations.
 * 
 * @author Julius Davies
 *
 * @deprecated Please use {@link java.net.URL#openConnection} instead.
 *     Please visit <a href="http://android-developers.blogspot.com/2011/09/androids-http-clients.html">this webpage</a>
 *     for further details.
 */
@Deprecated
public abstract class AbstractVerifier implements X509HostnameVerifier {

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|[0-1]?\\d?\\d)){3}$");

    /**
     * This contains a list of 2nd-level domains that aren't allowed to
     * have wildcards when combined with country-codes.
     * For example: [*.co.uk].
     * <p/>
     * The [*.co.uk] problem is an interesting one.  Should we just hope
     * that CA's would never foolishly allow such a certificate to happen?
     * Looks like we're the only implementation guarding against this.
     * Firefox, Curl, Sun Java 1.4, 5, 6 don't bother with this check.
     */
    private final static String[] BAD_COUNTRY_2LDS =
          { "ac", "co", "com", "ed", "edu", "go", "gouv", "gov", "info",
            "lg", "ne", "net", "or", "org" };

    static {
        // Just in case developer forgot to manually sort the array.  :-)
        Arrays.sort(BAD_COUNTRY_2LDS);
    }

    public AbstractVerifier() {
        super();
    }

    public final void verify(String host, SSLSocket ssl)
          throws IOException {
        if(host == null) {
            throw new NullPointerException("host to verify is null");
        }

        SSLSession session = ssl.getSession();
        Certificate[] certs = session.getPeerCertificates();
        X509Certificate x509 = (X509Certificate) certs[0];
        verify(host, x509);
    }

    public final boolean verify(String host, SSLSession session) {
        try {
            Certificate[] certs = session.getPeerCertificates();
            X509Certificate x509 = (X509Certificate) certs[0];
            verify(host, x509);
            return true;
        }
        catch(SSLException e) {
            return false;
        }
    }

    public final void verify(String host, X509Certificate cert)
          throws SSLException {
        String[] cns = getCNs(cert);
        String[] subjectAlts = getDNSSubjectAlts(cert);
        verify(host, cns, subjectAlts);
    }

    public final void verify(final String host, final String[] cns,
                             final String[] subjectAlts,
                             final boolean strictWithSubDomains)
          throws SSLException {

        // Build the list of names we're going to check.  Our DEFAULT and
        // STRICT implementations of the HostnameVerifier only use the
        // first CN provided.  All other CNs are ignored.
        // (Firefox, wget, curl, Sun Java 1.4, 5, 6 all work this way).
        LinkedList<String> names = new LinkedList<String>();
        if(cns != null && cns.length > 0 && cns[0] != null) {
            names.add(cns[0]);
        }
        if(subjectAlts != null) {
            for (String subjectAlt : subjectAlts) {
                if (subjectAlt != null) {
                    names.add(subjectAlt);
                }
            }
        }

        if(names.isEmpty()) {
            String msg = "Certificate for <" + host + "> doesn't contain CN or DNS subjectAlt";
            throw new SSLException(msg);
        }

        // StringBuffer for building the error message.
        StringBuffer buf = new StringBuffer();

        // We're can be case-insensitive when comparing the host we used to
        // establish the socket to the hostname in the certificate.
        String hostName = host.trim().toLowerCase(Locale.ENGLISH);
        boolean match = false;
        for(Iterator<String> it = names.iterator(); it.hasNext();) {
            // Don't trim the CN, though!
            String cn = it.next();
            cn = cn.toLowerCase(Locale.ENGLISH);
            // Store CN in StringBuffer in case we need to report an error.
            buf.append(" <");
            buf.append(cn);
            buf.append('>');
            if(it.hasNext()) {
                buf.append(" OR");
            }

            // The CN better have at least two dots if it wants wildcard
            // action.  It also can't be [*.co.uk] or [*.co.jp] or
            // [*.org.uk], etc...
            boolean doWildcard = cn.startsWith("*.") &&
                                 cn.indexOf('.', 2) != -1 &&
                                 acceptableCountryWildcard(cn) &&
                                 !isIPv4Address(host);

            if(doWildcard) {
                match = hostName.endsWith(cn.substring(1));
                if(match && strictWithSubDomains) {
                    // If we're in strict mode, then [*.foo.com] is not
                    // allowed to match [a.b.foo.com]
                    match = countDots(hostName) == countDots(cn);
                }
            } else {
                match = hostName.equals(cn);
            }
            if(match) {
                break;
            }
        }
        if(!match) {
            throw new SSLException("hostname in certificate didn't match: <" + host + "> !=" + buf);
        }
    }

    public static boolean acceptableCountryWildcard(String cn) {
        int cnLen = cn.length();
        if(cnLen >= 7 && cnLen <= 9) {
            // Look for the '.' in the 3rd-last position:
            if(cn.charAt(cnLen - 3) == '.') {
                // Trim off the [*.] and the [.XX].
                String s = cn.substring(2, cnLen - 3);
                // And test against the sorted array of bad 2lds:
                int x = Arrays.binarySearch(BAD_COUNTRY_2LDS, s);
                return x < 0;
            }
        }
        return true;
    }

    public static String[] getCNs(X509Certificate cert) {
        DistinguishedNameParser dnParser =
                new DistinguishedNameParser(cert.getSubjectX500Principal());
        List<String> cnList = dnParser.getAllMostSpecificFirst("cn");

        if(!cnList.isEmpty()) {
            String[] cns = new String[cnList.size()];
            cnList.toArray(cns);
            return cns;
        } else {
            return null;
        }
    }


    /**
     * Extracts the array of SubjectAlt DNS names from an X509Certificate.
     * Returns null if there aren't any.
     * <p/>
     * Note:  Java doesn't appear able to extract international characters
     * from the SubjectAlts.  It can only extract international characters
     * from the CN field.
     * <p/>
     * (Or maybe the version of OpenSSL I'm using to test isn't storing the
     * international characters correctly in the SubjectAlts?).
     *
     * @param cert X509Certificate
     * @return Array of SubjectALT DNS names stored in the certificate.
     */
    public static String[] getDNSSubjectAlts(X509Certificate cert) {
        LinkedList<String> subjectAltList = new LinkedList<String>();
        Collection<List<?>> c = null;
        try {
            c = cert.getSubjectAlternativeNames();
        }
        catch(CertificateParsingException cpe) {
            Logger.getLogger(AbstractVerifier.class.getName())
                    .log(Level.FINE, "Error parsing certificate.", cpe);
        }
        if(c != null) {
            for (List<?> aC : c) {
                List<?> list = aC;
                int type = ((Integer) list.get(0)).intValue();
                // If type is 2, then we've got a dNSName
                if (type == 2) {
                    String s = (String) list.get(1);
                    subjectAltList.add(s);
                }
            }
        }
        if(!subjectAltList.isEmpty()) {
            String[] subjectAlts = new String[subjectAltList.size()];
            subjectAltList.toArray(subjectAlts);
            return subjectAlts;
        } else {
            return null;
        }
    }

    /**
     * Counts the number of dots "." in a string.
     * @param s  string to count dots from
     * @return  number of dots
     */
    public static int countDots(final String s) {
        int count = 0;
        for(int i = 0; i < s.length(); i++) {
            if(s.charAt(i) == '.') {
                count++;
            }
        }
        return count;
    }

    private static boolean isIPv4Address(final String input) {
        return IPV4_PATTERN.matcher(input).matches();
    }
}
