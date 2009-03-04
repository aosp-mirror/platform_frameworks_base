/*
 * Copyright (C) 2007 The Android Open Source Project
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

interface HttpConstants {
    /** 2XX: generally "OK" */
    public static final int HTTP_OK = 200;
    public static final int HTTP_CREATED = 201;
    public static final int HTTP_ACCEPTED = 202;
    public static final int HTTP_NOT_AUTHORITATIVE = 203;
    public static final int HTTP_NO_CONTENT = 204;
    public static final int HTTP_RESET = 205;
    public static final int HTTP_PARTIAL = 206;

    /** 3XX: relocation/redirect */
    public static final int HTTP_MULT_CHOICE = 300;
    public static final int HTTP_MOVED_PERM = 301;
    public static final int HTTP_MOVED_TEMP = 302;
    public static final int HTTP_SEE_OTHER = 303;
    public static final int HTTP_NOT_MODIFIED = 304;
    public static final int HTTP_USE_PROXY = 305;

    /** 4XX: client error */
    public static final int HTTP_BAD_REQUEST = 400;
    public static final int HTTP_UNAUTHORIZED = 401;
    public static final int HTTP_PAYMENT_REQUIRED = 402;
    public static final int HTTP_FORBIDDEN = 403;
    public static final int HTTP_NOT_FOUND = 404;
    public static final int HTTP_BAD_METHOD = 405;
    public static final int HTTP_NOT_ACCEPTABLE = 406;
    public static final int HTTP_PROXY_AUTH = 407;
    public static final int HTTP_CLIENT_TIMEOUT = 408;
    public static final int HTTP_CONFLICT = 409;
    public static final int HTTP_GONE = 410;
    public static final int HTTP_LENGTH_REQUIRED = 411;
    public static final int HTTP_PRECON_FAILED = 412;
    public static final int HTTP_ENTITY_TOO_LARGE = 413;
    public static final int HTTP_REQ_TOO_LONG = 414;
    public static final int HTTP_UNSUPPORTED_TYPE = 415;

    /** 5XX: server error */
    public static final int HTTP_SERVER_ERROR = 500;
    public static final int HTTP_INTERNAL_ERROR = 501;
    public static final int HTTP_BAD_GATEWAY = 502;
    public static final int HTTP_UNAVAILABLE = 503;
    public static final int HTTP_GATEWAY_TIMEOUT = 504;
    public static final int HTTP_VERSION = 505;

    /** Method IDs */
    public static final int UNKNOWN_METHOD = 0;
    public static final int GET_METHOD = 1;
    public static final int HEAD_METHOD = 2;
    public static final int POST_METHOD = 3;

    public static final String[] requestHeaders = {
        "cache-control",
        "connection",
        "date",
        "pragma",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "via",
        "warning",
        "accept",
        "accept-charset",
        "accept-encoding",
        "accept-language",
        "authorization",
        "expect",
        "from",
        "host",
        "if-match",
        "if-modified-since",
        "if-none-match",
        "if-range",
        "if-unmodified-since",
        "max-forwards",
        "proxy-authentication",
        "range",
        "referer",
        "te",
        "user-agent",
        "keep-alive",
        "allow",
        "content-encoding",
        "content-language",
        "content-length",
        "content-location",
        "content-md5",
        "content-range",
        "content-type",
        "expires",
        "last-modified",
        "location",
        "server"

    };

    public static final int REQ_UNKNOWN = -1;
    public static final int REQ_CACHE_CONTROL = 0;
    public static final int REQ_CONNECTION = 1;
    public static final int REQ_DATE = 2;
    public static final int REQ_PRAGMA = 3;
    public static final int REQ_TRAILER = 4;
    public static final int REQ_TRANSFER_ENCODING = 5;
    public static final int REQ_UPGRADE = 6;
    public static final int REQ_VIA = 7;
    public static final int REQ_WARNING = 8;
    public static final int REQ_ACCEPT = 9;
    public static final int REQ_ACCEPT_CHARSET = 10;
    public static final int REQ_ACCEPT_ENCODING = 11;
    public static final int REQ_ACCEPT_LANGUAGE = 12;
    public static final int REQ_AUTHORIZATION = 13;
    public static final int REQ_EXPECT = 14;
    public static final int REQ_FROM = 15;
    public static final int REQ_HOST = 16;
    public static final int REQ_IF_MATCH = 17;
    public static final int REQ_IF_MODIFIED_SINCE = 18;
    public static final int REQ_IF_NONE_MATCH = 19;
    public static final int REQ_IF_RANGE = 20;
    public static final int REQ_IF_UNMODIFIED_SINCE = 21;
    public static final int REQ_MAX_FORWARDS = 22;
    public static final int REQ_PROXY_AUTHENTICATION = 23;
    public static final int REQ_RANGE = 24;
    public static final int REQ_REFERER = 25;
    public static final int REQ_TE = 26;
    public static final int REQ_USER_AGENT = 27;
    public static final int REQ_KEEP_ALIVE = 28;
    public static final int REQ_ALLOW = 29;
    public static final int REQ_CONTENT_ENCODING = 30;
    public static final int REQ_CONTENT_LANGUAGE = 31;
    public static final int REQ_CONTENT_LENGTH = 32;
    public static final int REQ_CONTENT_LOCATION = 33;
    public static final int REQ_CONTENT_MD5 = 34;
    public static final int REQ_CONTENT_RANGE = 35;
    public static final int REQ_CONTENT_TYPE = 36;
    public static final int REQ_EXPIRES = 37;
    public static final int REQ_LAST_MODIFIED = 38;
    public static final int REQ_LOCATION = 39;
    public static final int REQ_SERVER = 40;

}
