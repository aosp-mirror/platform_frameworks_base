/*
 * $HeadURL: http://svn.apache.org/repos/asf/httpcomponents/httpclient/trunk/module-client/src/main/java/org/apache/http/conn/ssl/StrictHostnameVerifier.java $
 * $Revision: 617642 $
 * $Date: 2008-02-01 12:54:07 -0800 (Fri, 01 Feb 2008) $
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

import javax.net.ssl.SSLException;

/**
 * The Strict HostnameVerifier works the same way as Sun Java 1.4, Sun
 * Java 5, Sun Java 6-rc.  It's also pretty close to IE6.  This
 * implementation appears to be compliant with RFC 2818 for dealing with
 * wildcards.
 * <p/>
 * The hostname must match either the first CN, or any of the subject-alts.
 * A wildcard can occur in the CN, and in any of the subject-alts.  The
 * one divergence from IE6 is how we only check the first CN.  IE6 allows
 * a match against any of the CNs present.  We decided to follow in
 * Sun Java 1.4's footsteps and only check the first CN.  (If you need
 * to check all the CN's, feel free to write your own implementation!).
 * <p/>
 * A wildcard such as "*.foo.com" matches only subdomains in the same
 * level, for example "a.foo.com".  It does not match deeper subdomains
 * such as "a.b.foo.com".
 * 
 * @author Julius Davies
 *
 * @deprecated Please use {@link java.net.URL#openConnection} instead.
 *     Please visit <a href="http://android-developers.blogspot.com/2011/09/androids-http-clients.html">this webpage</a>
 *     for further details.
 */
@Deprecated
public class StrictHostnameVerifier extends AbstractVerifier {

    public final void verify(
            final String host, 
            final String[] cns,
            final String[] subjectAlts) throws SSLException {
        verify(host, cns, subjectAlts, true);
    }

    @Override
    public final String toString() { 
        return "STRICT"; 
    }
    
}
