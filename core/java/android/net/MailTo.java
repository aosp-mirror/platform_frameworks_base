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

package android.net;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 *
 * MailTo URL parser
 *
 * This class parses a mailto scheme URL and then can be queried for
 * the parsed parameters. This implements RFC 2368.
 *
 */
public class MailTo {
    
    static public final String MAILTO_SCHEME = "mailto:";
    
    // All the parsed content is added to the headers.
    private HashMap<String, String> mHeaders;
    
    // Well known headers
    static private final String TO = "to";
    static private final String BODY = "body";
    static private final String CC = "cc";
    static private final String SUBJECT = "subject";

    
    /**
     * Test to see if the given string is a mailto URL
     * @param url string to be tested
     * @return true if the string is a mailto URL
     */
    public static boolean isMailTo(String url) {
        if (url != null && url.startsWith(MAILTO_SCHEME)) {
            return true;
        }
        return false;
    }
    
    /**
     * Parse and decode a mailto scheme string. This parser implements
     * RFC 2368. The returned object can be queried for the parsed parameters.
     * @param url String containing a mailto URL
     * @return MailTo object
     * @exception ParseException if the scheme is not a mailto URL
     */
    public static MailTo parse(String url) throws ParseException {
        if (url == null) {
            throw new NullPointerException();
        }
        if (!isMailTo(url)) {
             throw new ParseException("Not a mailto scheme");
        }
        // Strip the scheme as the Uri parser can't cope with it.
        String noScheme = url.substring(MAILTO_SCHEME.length());
        Uri email = Uri.parse(noScheme);
        MailTo m = new MailTo();
        
        // Parse out the query parameters
        String query = email.getQuery();
        if (query != null ) {
            String[] queries = query.split("&");
            for (String q : queries) {
                String[] nameval = q.split("=");
                if (nameval.length == 0) {
                    continue;
                }
                // insert the headers with the name in lowercase so that
                // we can easily find common headers
                m.mHeaders.put(Uri.decode(nameval[0]).toLowerCase(), 
                        nameval.length > 1 ? Uri.decode(nameval[1]) : null);
            }
        }
        
        // Address can be specified in both the headers and just after the
        // mailto line. Join the two together.
        String address = email.getPath();
        if (address != null) {
            String addr = m.getTo();
            if (addr != null) {
                address += ", " + addr;
            }
            m.mHeaders.put(TO, address);
        }
        
        return m;
    }
    
    /**
     * Retrieve the To address line from the parsed mailto URL. This could be
     * several email address that are comma-space delimited.
     * If no To line was specified, then null is return
     * @return comma delimited email addresses or null
     */
    public String getTo() {
        return mHeaders.get(TO);
    }
    
    /**
     * Retrieve the CC address line from the parsed mailto URL. This could be
     * several email address that are comma-space delimited.
     * If no CC line was specified, then null is return
     * @return comma delimited email addresses or null
     */
    public String getCc() {
        return mHeaders.get(CC);
    }
    
    /**
     * Retrieve the subject line from the parsed mailto URL.
     * If no subject line was specified, then null is return
     * @return subject or null
     */
    public String getSubject() {
        return mHeaders.get(SUBJECT);
    }
    
    /**
     * Retrieve the body line from the parsed mailto URL.
     * If no body line was specified, then null is return
     * @return body or null
     */
    public String getBody() {
        return mHeaders.get(BODY);
    }
    
    /**
     * Retrieve all the parsed email headers from the mailto URL
     * @return map containing all parsed values
     */
    public Map<String, String> getHeaders() {
        return mHeaders;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(MAILTO_SCHEME);
        sb.append('?');
        for (Map.Entry<String,String> header : mHeaders.entrySet()) {
            sb.append(Uri.encode(header.getKey()));
            sb.append('=');
            sb.append(Uri.encode(header.getValue()));
            sb.append('&');
        }
        return sb.toString();
    }
    
    /**
     * Private constructor. The only way to build a Mailto object is through
     * the parse() method.
     */
    private MailTo() {
        mHeaders = new HashMap<String, String>();
    }
}
