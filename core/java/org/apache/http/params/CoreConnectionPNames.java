/*
 * $HeadURL: http://svn.apache.org/repos/asf/httpcomponents/httpcore/trunk/module-main/src/main/java/org/apache/http/params/CoreConnectionPNames.java $
 * $Revision: 576077 $
 * $Date: 2007-09-16 04:50:22 -0700 (Sun, 16 Sep 2007) $
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

package org.apache.http.params;


/**
 * Defines parameter names for connections in HttpCore.
 * 
 * @version $Revision: 576077 $
 * 
 * @since 4.0
 *
 * @deprecated Please use {@link java.net.URL#openConnection} instead.
 *     Please visit <a href="http://android-developers.blogspot.com/2011/09/androids-http-clients.html">this webpage</a>
 *     for further details.
 */
@Deprecated
public interface CoreConnectionPNames {

    /**
     * Defines the default socket timeout (<tt>SO_TIMEOUT</tt>) in milliseconds which is the 
     * timeout for waiting for data. A timeout value of zero is interpreted as an infinite 
     * timeout. This value is used when no socket timeout is set in the 
     * method parameters. 
     * <p>
     * This parameter expects a value of type {@link Integer}.
     * </p>
     * @see java.net.SocketOptions#SO_TIMEOUT
     */
    public static final String SO_TIMEOUT = "http.socket.timeout"; 

    /**
     * Determines whether Nagle's algorithm is to be used. The Nagle's algorithm 
     * tries to conserve bandwidth by minimizing the number of segments that are 
     * sent. When applications wish to decrease network latency and increase 
     * performance, they can disable Nagle's algorithm (that is enable TCP_NODELAY). 
     * Data will be sent earlier, at the cost of an increase in bandwidth consumption. 
     * <p>
     * This parameter expects a value of type {@link Boolean}.
     * </p>
     * @see java.net.SocketOptions#TCP_NODELAY
     */
    public static final String TCP_NODELAY = "http.tcp.nodelay"; 

    /**
     * Determines the size of the internal socket buffer used to buffer data
     * while receiving / transmitting HTTP messages.
     * <p>
     * This parameter expects a value of type {@link Integer}.
     * </p>
     */
    public static final String SOCKET_BUFFER_SIZE = "http.socket.buffer-size"; 

    /**
     * Sets SO_LINGER with the specified linger time in seconds. The maximum timeout 
     * value is platform specific. Value <tt>0</tt> implies that the option is disabled.
     * Value <tt>-1</tt> implies that the JRE default is used. The setting only affects 
     * socket close.  
     * <p>
     * This parameter expects a value of type {@link Integer}.
     * </p>
     * @see java.net.SocketOptions#SO_LINGER
     */
    public static final String SO_LINGER = "http.socket.linger"; 

    /**
     * Determines the timeout until a connection is etablished. A value of zero 
     * means the timeout is not used. The default value is zero.
     * <p>
     * This parameter expects a value of type {@link Integer}.
     * </p>
     */
    public static final String CONNECTION_TIMEOUT = "http.connection.timeout"; 

    /**
     * Determines whether stale connection check is to be used. Disabling 
     * stale connection check may result in slight performance improvement 
     * at the risk of getting an I/O error when executing a request over a
     * connection that has been closed at the server side. 
     * <p>
     * This parameter expects a value of type {@link Boolean}.
     * </p>
     */
    public static final String STALE_CONNECTION_CHECK = "http.connection.stalecheck"; 

    /**
     * Determines the maximum line length limit. If set to a positive value, any HTTP 
     * line exceeding this limit will cause an IOException. A negative or zero value
     * will effectively disable the check.
     * <p>
     * This parameter expects a value of type {@link Integer}.
     * </p>
     */
    public static final String MAX_LINE_LENGTH = "http.connection.max-line-length";
    
    /**
     * Determines the maximum HTTP header count allowed. If set to a positive value, 
     * the number of HTTP headers received from the data stream exceeding this limit 
     * will cause an IOException. A negative or zero value will effectively disable 
     * the check. 
     * <p>
     * This parameter expects a value of type {@link Integer}.
     * </p>
     */
    public static final String MAX_HEADER_COUNT = "http.connection.max-header-count";

}
