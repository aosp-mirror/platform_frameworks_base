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

package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.StrictMode;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;

/**
 * Immutable URI reference. A URI reference includes a URI and a fragment, the
 * component of the URI following a '#'. Builds and parses URI references
 * which conform to
 * <a href="http://www.faqs.org/rfcs/rfc2396.html">RFC 2396</a>.
 *
 * <p>In the interest of performance, this class performs little to no
 * validation. Behavior is undefined for invalid input. This class is very
 * forgiving--in the face of invalid input, it will return garbage
 * rather than throw an exception unless otherwise specified.
 */
public abstract class Uri implements Parcelable, Comparable<Uri> {

    /*

    This class aims to do as little up front work as possible. To accomplish
    that, we vary the implementation depending on what the user passes in.
    For example, we have one implementation if the user passes in a
    URI string (StringUri) and another if the user passes in the
    individual components (OpaqueUri).

    *Concurrency notes*: Like any truly immutable object, this class is safe
    for concurrent use. This class uses a caching pattern in some places where
    it doesn't use volatile or synchronized. This is safe to do with ints
    because getting or setting an int is atomic. It's safe to do with a String
    because the internal fields are final and the memory model guarantees other
    threads won't see a partially initialized instance. We are not guaranteed
    that some threads will immediately see changes from other threads on
    certain platforms, but we don't mind if those threads reconstruct the
    cached result. As a result, we get thread safe caching with no concurrency
    overhead, which means the most common case, access from a single thread,
    is as fast as possible.

    From the Java Language spec.:

    "17.5 Final Field Semantics

    ... when the object is seen by another thread, that thread will always
    see the correctly constructed version of that object's final fields.
    It will also see versions of any object or array referenced by
    those final fields that are at least as up-to-date as the final fields
    are."

    In that same vein, all non-transient fields within Uri
    implementations should be final and immutable so as to ensure true
    immutability for clients even when they don't use proper concurrency
    control.

    For reference, from RFC 2396:

    "4.3. Parsing a URI Reference

       A URI reference is typically parsed according to the four main
       components and fragment identifier in order to determine what
       components are present and whether the reference is relative or
       absolute.  The individual components are then parsed for their
       subparts and, if not opaque, to verify their validity.

       Although the BNF defines what is allowed in each component, it is
       ambiguous in terms of differentiating between an authority component
       and a path component that begins with two slash characters.  The
       greedy algorithm is used for disambiguation: the left-most matching
       rule soaks up as much of the URI reference string as it is capable of
       matching.  In other words, the authority component wins."

    The "four main components" of a hierarchical URI consist of
    <scheme>://<authority><path>?<query>

    */

    /** Log tag. */
    private static final String LOG = Uri.class.getSimpleName();

    /**
     *
     * Holds a placeholder for strings which haven't been cached. This enables us
     * to cache null. We intentionally create a new String instance so we can
     * compare its identity and there is no chance we will confuse it with
     * user data.
     *
     * NOTE This value is held in its own Holder class is so that referring to
     * {@link NotCachedHolder#NOT_CACHED} does not trigger {@code Uri.<clinit>}.
     * For example, {@code PathPart.<init>} uses {@code NotCachedHolder.NOT_CACHED}
     * but must not trigger {@code Uri.<clinit>}: Otherwise, the initialization of
     * {@code Uri.EMPTY} would see a {@code null} value for {@code PathPart.EMPTY}!
     *
     * @hide
     */
    static class NotCachedHolder {
        private NotCachedHolder() {
            // prevent instantiation
        }
        @SuppressWarnings("RedundantStringConstructorCall")
        static final String NOT_CACHED = new String("NOT CACHED");
    }

    /**
     * The empty URI, equivalent to "".
     */
    public static final Uri EMPTY = new HierarchicalUri(null, Part.NULL,
            PathPart.EMPTY, Part.NULL, Part.NULL);

    /**
     * Prevents external subclassing.
     */
    @UnsupportedAppUsage
    private Uri() {}

    /**
     * Returns true if this URI is hierarchical like "http://google.com".
     * Absolute URIs are hierarchical if the scheme-specific part starts with
     * a '/'. Relative URIs are always hierarchical.
     */
    public abstract boolean isHierarchical();

    /**
     * Returns true if this URI is opaque like "mailto:nobody@google.com". The
     * scheme-specific part of an opaque URI cannot start with a '/'.
     */
    public boolean isOpaque() {
        return !isHierarchical();
    }

    /**
     * Returns true if this URI is relative, i.e.&nbsp;if it doesn't contain an
     * explicit scheme.
     *
     * @return true if this URI is relative, false if it's absolute
     */
    public abstract boolean isRelative();

    /**
     * Returns true if this URI is absolute, i.e.&nbsp;if it contains an
     * explicit scheme.
     *
     * @return true if this URI is absolute, false if it's relative
     */
    public boolean isAbsolute() {
        return !isRelative();
    }

    /**
     * Gets the scheme of this URI. Example: "http"
     *
     * @return the scheme or null if this is a relative URI
     */
    @Nullable
    public abstract String getScheme();

    /**
     * Gets the scheme-specific part of this URI, i.e.&nbsp;everything between
     * the scheme separator ':' and the fragment separator '#'. If this is a
     * relative URI, this method returns the entire URI. Decodes escaped octets.
     *
     * <p>Example: "//www.google.com/search?q=android"
     *
     * @return the decoded scheme-specific-part
     */
    public abstract String getSchemeSpecificPart();

    /**
     * Gets the scheme-specific part of this URI, i.e.&nbsp;everything between
     * the scheme separator ':' and the fragment separator '#'. If this is a
     * relative URI, this method returns the entire URI. Leaves escaped octets
     * intact.
     *
     * <p>Example: "//www.google.com/search?q=android"
     *
     * @return the encoded scheme-specific-part
     */
    public abstract String getEncodedSchemeSpecificPart();

    /**
     * Gets the decoded authority part of this URI. For
     * server addresses, the authority is structured as follows:
     * {@code [ userinfo '@' ] host [ ':' port ]}
     *
     * <p>Examples: "google.com", "bob@google.com:80"
     *
     * @return the authority for this URI or null if not present
     */
    @Nullable
    public abstract String getAuthority();

    /**
     * Gets the encoded authority part of this URI. For
     * server addresses, the authority is structured as follows:
     * {@code [ userinfo '@' ] host [ ':' port ]}
     *
     * <p>Examples: "google.com", "bob@google.com:80"
     *
     * @return the authority for this URI or null if not present
     */
    @Nullable
    public abstract String getEncodedAuthority();

    /**
     * Gets the decoded user information from the authority.
     * For example, if the authority is "nobody@google.com", this method will
     * return "nobody".
     *
     * @return the user info for this URI or null if not present
     */
    @Nullable
    public abstract String getUserInfo();

    /**
     * Gets the encoded user information from the authority.
     * For example, if the authority is "nobody@google.com", this method will
     * return "nobody".
     *
     * @return the user info for this URI or null if not present
     */
    @Nullable
    public abstract String getEncodedUserInfo();

    /**
     * Gets the encoded host from the authority for this URI. For example,
     * if the authority is "bob@google.com", this method will return
     * "google.com".
     *
     * @return the host for this URI or null if not present
     */
    @Nullable
    public abstract String getHost();

    /**
     * Gets the port from the authority for this URI. For example,
     * if the authority is "google.com:80", this method will return 80.
     *
     * @return the port for this URI or -1 if invalid or not present
     */
    public abstract int getPort();

    /**
     * Gets the decoded path.
     *
     * @return the decoded path, or null if this is not a hierarchical URI
     * (like "mailto:nobody@google.com") or the URI is invalid
     */
    @Nullable
    public abstract String getPath();

    /**
     * Gets the encoded path.
     *
     * @return the encoded path, or null if this is not a hierarchical URI
     * (like "mailto:nobody@google.com") or the URI is invalid
     */
    @Nullable
    public abstract String getEncodedPath();

    /**
     * Gets the decoded query component from this URI. The query comes after
     * the query separator ('?') and before the fragment separator ('#'). This
     * method would return "q=android" for
     * "http://www.google.com/search?q=android".
     *
     * @return the decoded query or null if there isn't one
     */
    @Nullable
    public abstract String getQuery();

    /**
     * Gets the encoded query component from this URI. The query comes after
     * the query separator ('?') and before the fragment separator ('#'). This
     * method would return "q=android" for
     * "http://www.google.com/search?q=android".
     *
     * @return the encoded query or null if there isn't one
     */
    @Nullable
    public abstract String getEncodedQuery();

    /**
     * Gets the decoded fragment part of this URI, everything after the '#'.
     *
     * @return the decoded fragment or null if there isn't one
     */
    @Nullable
    public abstract String getFragment();

    /**
     * Gets the encoded fragment part of this URI, everything after the '#'.
     *
     * @return the encoded fragment or null if there isn't one
     */
    @Nullable
    public abstract String getEncodedFragment();

    /**
     * Gets the decoded path segments.
     *
     * @return decoded path segments, each without a leading or trailing '/'
     */
    public abstract List<String> getPathSegments();

    /**
     * Gets the decoded last segment in the path.
     *
     * @return the decoded last segment or null if the path is empty
     */
    @Nullable
    public abstract String getLastPathSegment();

    /**
     * Compares this Uri to another object for equality. Returns true if the
     * encoded string representations of this Uri and the given Uri are
     * equal. Case counts. Paths are not normalized. If one Uri specifies a
     * default port explicitly and the other leaves it implicit, they will not
     * be considered equal.
     */
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof Uri)) {
            return false;
        }

        Uri other = (Uri) o;

        return toString().equals(other.toString());
    }

    /**
     * Hashes the encoded string represention of this Uri consistently with
     * {@link #equals(Object)}.
     */
    public int hashCode() {
        return toString().hashCode();
    }

    /**
     * Compares the string representation of this Uri with that of
     * another.
     */
    public int compareTo(Uri other) {
        return toString().compareTo(other.toString());
    }

    /**
     * Returns the encoded string representation of this URI.
     * Example: "http://google.com/"
     */
    public abstract String toString();

    /**
     * Return a string representation of this URI that has common forms of PII redacted,
     * making it safer to use for logging purposes.  For example, {@code tel:800-466-4411} is
     * returned as {@code tel:xxx-xxx-xxxx} and {@code http://example.com/path/to/item/} is
     * returned as {@code http://example.com/...}.
     * @return the common forms PII redacted string of this URI
     * @hide
     */
    @SystemApi
    public @NonNull String toSafeString() {
        String scheme = getScheme();
        String ssp = getSchemeSpecificPart();
        if (scheme != null) {
            if (scheme.equalsIgnoreCase("tel") || scheme.equalsIgnoreCase("sip")
                    || scheme.equalsIgnoreCase("sms") || scheme.equalsIgnoreCase("smsto")
                    || scheme.equalsIgnoreCase("mailto") || scheme.equalsIgnoreCase("nfc")) {
                StringBuilder builder = new StringBuilder(64);
                builder.append(scheme);
                builder.append(':');
                if (ssp != null) {
                    for (int i=0; i<ssp.length(); i++) {
                        char c = ssp.charAt(i);
                        if (c == '-' || c == '@' || c == '.') {
                            builder.append(c);
                        } else {
                            builder.append('x');
                        }
                    }
                }
                return builder.toString();
            } else if (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")
                    || scheme.equalsIgnoreCase("ftp") || scheme.equalsIgnoreCase("rtsp")) {
                ssp = "//" + ((getHost() != null) ? getHost() : "")
                        + ((getPort() != -1) ? (":" + getPort()) : "")
                        + "/...";
            }
        }
        // Not a sensitive scheme, but let's still be conservative about
        // the data we include -- only the ssp, not the query params or
        // fragment, because those can often have sensitive info.
        StringBuilder builder = new StringBuilder(64);
        if (scheme != null) {
            builder.append(scheme);
            builder.append(':');
        }
        if (ssp != null) {
            builder.append(ssp);
        }
        return builder.toString();
    }

    /**
     * Constructs a new builder, copying the attributes from this Uri.
     */
    public abstract Builder buildUpon();

    /** Index of a component which was not found. */
    private final static int NOT_FOUND = -1;

    /** Placeholder value for an index which hasn't been calculated yet. */
    private final static int NOT_CALCULATED = -2;

    /**
     * Error message presented when a user tries to treat an opaque URI as
     * hierarchical.
     */
    private static final String NOT_HIERARCHICAL
            = "This isn't a hierarchical URI.";

    /** Default encoding. */
    private static final String DEFAULT_ENCODING = "UTF-8";

    /**
     * Creates a Uri which parses the given encoded URI string.
     *
     * @param uriString an RFC 2396-compliant, encoded URI
     * @throws NullPointerException if uriString is null
     * @return Uri for this given uri string
     */
    public static Uri parse(String uriString) {
        return new StringUri(uriString);
    }

    /**
     * Creates a Uri from a file. The URI has the form
     * "file://<absolute path>". Encodes path characters with the exception of
     * '/'.
     *
     * <p>Example: "file:///tmp/android.txt"
     *
     * @throws NullPointerException if file is null
     * @return a Uri for the given file
     */
    public static Uri fromFile(File file) {
        if (file == null) {
            throw new NullPointerException("file");
        }

        PathPart path = PathPart.fromDecoded(file.getAbsolutePath());
        return new HierarchicalUri(
                "file", Part.EMPTY, path, Part.NULL, Part.NULL);
    }

    /**
     * An implementation which wraps a String URI. This URI can be opaque or
     * hierarchical, but we extend AbstractHierarchicalUri in case we need
     * the hierarchical functionality.
     */
    private static class StringUri extends AbstractHierarchicalUri {

        /** Used in parcelling. */
        static final int TYPE_ID = 1;

        /** URI string representation. */
        private final String uriString;

        private StringUri(String uriString) {
            if (uriString == null) {
                throw new NullPointerException("uriString");
            }

            this.uriString = uriString;
        }

        static Uri readFrom(Parcel parcel) {
            return new StringUri(parcel.readString8());
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(TYPE_ID);
            parcel.writeString8(uriString);
        }

        /** Cached scheme separator index. */
        private volatile int cachedSsi = NOT_CALCULATED;

        /** Finds the first ':'. Returns -1 if none found. */
        private int findSchemeSeparator() {
            return cachedSsi == NOT_CALCULATED
                    ? cachedSsi = uriString.indexOf(':')
                    : cachedSsi;
        }

        /** Cached fragment separator index. */
        private volatile int cachedFsi = NOT_CALCULATED;

        /** Finds the first '#'. Returns -1 if none found. */
        private int findFragmentSeparator() {
            return cachedFsi == NOT_CALCULATED
                    ? cachedFsi = uriString.indexOf('#', findSchemeSeparator())
                    : cachedFsi;
        }

        public boolean isHierarchical() {
            int ssi = findSchemeSeparator();

            if (ssi == NOT_FOUND) {
                // All relative URIs are hierarchical.
                return true;
            }

            if (uriString.length() == ssi + 1) {
                // No ssp.
                return false;
            }

            // If the ssp starts with a '/', this is hierarchical.
            return uriString.charAt(ssi + 1) == '/';
        }

        public boolean isRelative() {
            // Note: We return true if the index is 0
            return findSchemeSeparator() == NOT_FOUND;
        }

        private volatile String scheme = NotCachedHolder.NOT_CACHED;

        public String getScheme() {
            @SuppressWarnings("StringEquality")
            boolean cached = (scheme != NotCachedHolder.NOT_CACHED);
            return cached ? scheme : (scheme = parseScheme());
        }

        private String parseScheme() {
            int ssi = findSchemeSeparator();
            return ssi == NOT_FOUND ? null : uriString.substring(0, ssi);
        }

        private Part ssp;

        private Part getSsp() {
            return ssp == null ? ssp = Part.fromEncoded(parseSsp()) : ssp;
        }

        public String getEncodedSchemeSpecificPart() {
            return getSsp().getEncoded();
        }

        public String getSchemeSpecificPart() {
            return getSsp().getDecoded();
        }

        private String parseSsp() {
            int ssi = findSchemeSeparator();
            int fsi = findFragmentSeparator();

            // Return everything between ssi and fsi.
            return fsi == NOT_FOUND
                    ? uriString.substring(ssi + 1)
                    : uriString.substring(ssi + 1, fsi);
        }

        private Part authority;

        private Part getAuthorityPart() {
            if (authority == null) {
                String encodedAuthority
                        = parseAuthority(this.uriString, findSchemeSeparator());
                return authority = Part.fromEncoded(encodedAuthority);
            }

            return authority;
        }

        public String getEncodedAuthority() {
            return getAuthorityPart().getEncoded();
        }

        public String getAuthority() {
            return getAuthorityPart().getDecoded();
        }

        private PathPart path;

        private PathPart getPathPart() {
            return path == null
                    ? path = PathPart.fromEncoded(parsePath())
                    : path;
        }

        public String getPath() {
            return getPathPart().getDecoded();
        }

        public String getEncodedPath() {
            return getPathPart().getEncoded();
        }

        public List<String> getPathSegments() {
            return getPathPart().getPathSegments();
        }

        private String parsePath() {
            String uriString = this.uriString;
            int ssi = findSchemeSeparator();

            // If the URI is absolute.
            if (ssi > -1) {
                // Is there anything after the ':'?
                boolean schemeOnly = ssi + 1 == uriString.length();
                if (schemeOnly) {
                    // Opaque URI.
                    return null;
                }

                // A '/' after the ':' means this is hierarchical.
                if (uriString.charAt(ssi + 1) != '/') {
                    // Opaque URI.
                    return null;
                }
            } else {
                // All relative URIs are hierarchical.
            }

            return parsePath(uriString, ssi);
        }

        private Part query;

        private Part getQueryPart() {
            return query == null
                    ? query = Part.fromEncoded(parseQuery()) : query;
        }

        public String getEncodedQuery() {
            return getQueryPart().getEncoded();
        }

        private String parseQuery() {
            // It doesn't make sense to cache this index. We only ever
            // calculate it once.
            int qsi = uriString.indexOf('?', findSchemeSeparator());
            if (qsi == NOT_FOUND) {
                return null;
            }

            int fsi = findFragmentSeparator();

            if (fsi == NOT_FOUND) {
                return uriString.substring(qsi + 1);
            }

            if (fsi < qsi) {
                // Invalid.
                return null;
            }

            return uriString.substring(qsi + 1, fsi);
        }

        public String getQuery() {
            return getQueryPart().getDecoded();
        }

        private Part fragment;

        private Part getFragmentPart() {
            return fragment == null
                    ? fragment = Part.fromEncoded(parseFragment()) : fragment;
        }

        public String getEncodedFragment() {
            return getFragmentPart().getEncoded();
        }

        private String parseFragment() {
            int fsi = findFragmentSeparator();
            return fsi == NOT_FOUND ? null : uriString.substring(fsi + 1);
        }

        public String getFragment() {
            return getFragmentPart().getDecoded();
        }

        public String toString() {
            return uriString;
        }

        /**
         * Parses an authority out of the given URI string.
         *
         * @param uriString URI string
         * @param ssi scheme separator index, -1 for a relative URI
         *
         * @return the authority or null if none is found
         */
        static String parseAuthority(String uriString, int ssi) {
            int length = uriString.length();

            // If "//" follows the scheme separator, we have an authority.
            if (length > ssi + 2
                    && uriString.charAt(ssi + 1) == '/'
                    && uriString.charAt(ssi + 2) == '/') {
                // We have an authority.

                // Look for the start of the path, query, or fragment, or the
                // end of the string.
                int end = ssi + 3;
                LOOP: while (end < length) {
                    switch (uriString.charAt(end)) {
                        case '/': // Start of path
                        case '\\':// Start of path
                          // Per http://url.spec.whatwg.org/#host-state, the \ character
                          // is treated as if it were a / character when encountered in a
                          // host
                        case '?': // Start of query
                        case '#': // Start of fragment
                            break LOOP;
                    }
                    end++;
                }

                return uriString.substring(ssi + 3, end);
            } else {
                return null;
            }

        }

        /**
         * Parses a path out of this given URI string.
         *
         * @param uriString URI string
         * @param ssi scheme separator index, -1 for a relative URI
         *
         * @return the path
         */
        static String parsePath(String uriString, int ssi) {
            int length = uriString.length();

            // Find start of path.
            int pathStart;
            if (length > ssi + 2
                    && uriString.charAt(ssi + 1) == '/'
                    && uriString.charAt(ssi + 2) == '/') {
                // Skip over authority to path.
                pathStart = ssi + 3;
                LOOP: while (pathStart < length) {
                    switch (uriString.charAt(pathStart)) {
                        case '?': // Start of query
                        case '#': // Start of fragment
                            return ""; // Empty path.
                        case '/': // Start of path!
                        case '\\':// Start of path!
                          // Per http://url.spec.whatwg.org/#host-state, the \ character
                          // is treated as if it were a / character when encountered in a
                          // host
                            break LOOP;
                    }
                    pathStart++;
                }
            } else {
                // Path starts immediately after scheme separator.
                pathStart = ssi + 1;
            }

            // Find end of path.
            int pathEnd = pathStart;
            LOOP: while (pathEnd < length) {
                switch (uriString.charAt(pathEnd)) {
                    case '?': // Start of query
                    case '#': // Start of fragment
                        break LOOP;
                }
                pathEnd++;
            }

            return uriString.substring(pathStart, pathEnd);
        }

        public Builder buildUpon() {
            if (isHierarchical()) {
                return new Builder()
                        .scheme(getScheme())
                        .authority(getAuthorityPart())
                        .path(getPathPart())
                        .query(getQueryPart())
                        .fragment(getFragmentPart());
            } else {
                return new Builder()
                        .scheme(getScheme())
                        .opaquePart(getSsp())
                        .fragment(getFragmentPart());
            }
        }
    }

    /**
     * Creates an opaque Uri from the given components. Encodes the ssp
     * which means this method cannot be used to create hierarchical URIs.
     *
     * @param scheme of the URI
     * @param ssp scheme-specific-part, everything between the
     *  scheme separator (':') and the fragment separator ('#'), which will
     *  get encoded
     * @param fragment fragment, everything after the '#', null if undefined,
     *  will get encoded
     *
     * @throws NullPointerException if scheme or ssp is null
     * @return Uri composed of the given scheme, ssp, and fragment
     *
     * @see Builder if you don't want the ssp and fragment to be encoded
     */
    public static Uri fromParts(String scheme, String ssp,
            String fragment) {
        if (scheme == null) {
            throw new NullPointerException("scheme");
        }
        if (ssp == null) {
            throw new NullPointerException("ssp");
        }

        return new OpaqueUri(scheme, Part.fromDecoded(ssp),
                Part.fromDecoded(fragment));
    }

    /**
     * Opaque URI.
     */
    private static class OpaqueUri extends Uri {

        /** Used in parcelling. */
        static final int TYPE_ID = 2;

        private final String scheme;
        private final Part ssp;
        private final Part fragment;

        private OpaqueUri(String scheme, Part ssp, Part fragment) {
            this.scheme = scheme;
            this.ssp = ssp;
            this.fragment = fragment == null ? Part.NULL : fragment;
        }

        static Uri readFrom(Parcel parcel) {
            return new OpaqueUri(
                parcel.readString8(),
                Part.readFrom(parcel),
                Part.readFrom(parcel)
            );
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(TYPE_ID);
            parcel.writeString8(scheme);
            ssp.writeTo(parcel);
            fragment.writeTo(parcel);
        }

        public boolean isHierarchical() {
            return false;
        }

        public boolean isRelative() {
            return scheme == null;
        }

        public String getScheme() {
            return this.scheme;
        }

        public String getEncodedSchemeSpecificPart() {
            return ssp.getEncoded();
        }

        public String getSchemeSpecificPart() {
            return ssp.getDecoded();
        }

        public String getAuthority() {
            return null;
        }

        public String getEncodedAuthority() {
            return null;
        }

        public String getPath() {
            return null;
        }

        public String getEncodedPath() {
            return null;
        }

        public String getQuery() {
            return null;
        }

        public String getEncodedQuery() {
            return null;
        }

        public String getFragment() {
            return fragment.getDecoded();
        }

        public String getEncodedFragment() {
            return fragment.getEncoded();
        }

        public List<String> getPathSegments() {
            return Collections.emptyList();
        }

        public String getLastPathSegment() {
            return null;
        }

        public String getUserInfo() {
            return null;
        }

        public String getEncodedUserInfo() {
            return null;
        }

        public String getHost() {
            return null;
        }

        public int getPort() {
            return -1;
        }

        private volatile String cachedString = NotCachedHolder.NOT_CACHED;

        public String toString() {
            @SuppressWarnings("StringEquality")
            boolean cached = cachedString != NotCachedHolder.NOT_CACHED;
            if (cached) {
                return cachedString;
            }

            StringBuilder sb = new StringBuilder();

            sb.append(scheme).append(':');
            sb.append(getEncodedSchemeSpecificPart());

            if (!fragment.isEmpty()) {
                sb.append('#').append(fragment.getEncoded());
            }

            return cachedString = sb.toString();
        }

        public Builder buildUpon() {
            return new Builder()
                    .scheme(this.scheme)
                    .opaquePart(this.ssp)
                    .fragment(this.fragment);
        }
    }

    /**
     * Wrapper for path segment array.
     */
    static class PathSegments extends AbstractList<String>
            implements RandomAccess {

        static final PathSegments EMPTY = new PathSegments(null, 0);

        final String[] segments;
        final int size;

        PathSegments(String[] segments, int size) {
            this.segments = segments;
            this.size = size;
        }

        public String get(int index) {
            if (index >= size) {
                throw new IndexOutOfBoundsException();
            }

            return segments[index];
        }

        public int size() {
            return this.size;
        }
    }

    /**
     * Builds PathSegments.
     */
    static class PathSegmentsBuilder {

        String[] segments;
        int size = 0;

        void add(String segment) {
            if (segments == null) {
                segments = new String[4];
            } else if (size + 1 == segments.length) {
                String[] expanded = new String[segments.length * 2];
                System.arraycopy(segments, 0, expanded, 0, segments.length);
                segments = expanded;
            }

            segments[size++] = segment;
        }

        PathSegments build() {
            if (segments == null) {
                return PathSegments.EMPTY;
            }

            try {
                return new PathSegments(segments, size);
            } finally {
                // Makes sure this doesn't get reused.
                segments = null;
            }
        }
    }

    /**
     * Support for hierarchical URIs.
     */
    private abstract static class AbstractHierarchicalUri extends Uri {

        public String getLastPathSegment() {
            // TODO: If we haven't parsed all of the segments already, just
            // grab the last one directly so we only allocate one string.

            List<String> segments = getPathSegments();
            int size = segments.size();
            if (size == 0) {
                return null;
            }
            return segments.get(size - 1);
        }

        private Part userInfo;

        private Part getUserInfoPart() {
            return userInfo == null
                    ? userInfo = Part.fromEncoded(parseUserInfo()) : userInfo;
        }

        public final String getEncodedUserInfo() {
            return getUserInfoPart().getEncoded();
        }

        private String parseUserInfo() {
            String authority = getEncodedAuthority();
            if (authority == null) {
                return null;
            }

            int end = authority.lastIndexOf('@');
            return end == NOT_FOUND ? null : authority.substring(0, end);
        }

        public String getUserInfo() {
            return getUserInfoPart().getDecoded();
        }

        private volatile String host = NotCachedHolder.NOT_CACHED;

        public String getHost() {
            @SuppressWarnings("StringEquality")
            boolean cached = (host != NotCachedHolder.NOT_CACHED);
            return cached ? host : (host = parseHost());
        }

        private String parseHost() {
            final String authority = getEncodedAuthority();
            if (authority == null) {
                return null;
            }

            // Parse out user info and then port.
            int userInfoSeparator = authority.lastIndexOf('@');
            int portSeparator = findPortSeparator(authority);

            String encodedHost = portSeparator == NOT_FOUND
                    ? authority.substring(userInfoSeparator + 1)
                    : authority.substring(userInfoSeparator + 1, portSeparator);

            return decode(encodedHost);
        }

        private volatile int port = NOT_CALCULATED;

        public int getPort() {
            return port == NOT_CALCULATED
                    ? port = parsePort()
                    : port;
        }

        private int parsePort() {
            final String authority = getEncodedAuthority();
            int portSeparator = findPortSeparator(authority);
            if (portSeparator == NOT_FOUND) {
                return -1;
            }

            String portString = decode(authority.substring(portSeparator + 1));
            try {
                return Integer.parseInt(portString);
            } catch (NumberFormatException e) {
                Log.w(LOG, "Error parsing port string.", e);
                return -1;
            }
        }

        private int findPortSeparator(String authority) {
            if (authority == null) {
                return NOT_FOUND;
            }

            // Reverse search for the ':' character that breaks as soon as a char that is neither
            // a colon nor an ascii digit is encountered. Thanks to the goodness of UTF-16 encoding,
            // it's not possible that a surrogate matches one of these, so this loop can just
            // look for characters rather than care about code points.
            for (int i = authority.length() - 1; i >= 0; --i) {
                final int character = authority.charAt(i);
                if (':' == character) return i;
                // Character.isDigit would include non-ascii digits
                if (character < '0' || character > '9') return NOT_FOUND;
            }
            return NOT_FOUND;
        }
    }

    /**
     * Hierarchical Uri.
     */
    private static class HierarchicalUri extends AbstractHierarchicalUri {

        /** Used in parcelling. */
        static final int TYPE_ID = 3;

        private final String scheme; // can be null
        private final Part authority;
        private final PathPart path;
        private final Part query;
        private final Part fragment;

        private HierarchicalUri(String scheme, Part authority, PathPart path,
                Part query, Part fragment) {
            this.scheme = scheme;
            this.authority = Part.nonNull(authority);
            this.path = path == null ? PathPart.NULL : path;
            this.query = Part.nonNull(query);
            this.fragment = Part.nonNull(fragment);
        }

        static Uri readFrom(Parcel parcel) {
            final String scheme = parcel.readString8();
            final Part authority = Part.readFrom(parcel);
            // In RFC3986 the path should be determined based on whether there is a scheme or
            // authority present (https://www.rfc-editor.org/rfc/rfc3986.html#section-3.3).
            final boolean hasSchemeOrAuthority =
                    (scheme != null && scheme.length() > 0) || !authority.isEmpty();
            final PathPart path = PathPart.readFrom(hasSchemeOrAuthority, parcel);
            final Part query = Part.readFrom(parcel);
            final Part fragment = Part.readFrom(parcel);
            return new HierarchicalUri(scheme, authority, path, query, fragment);
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(TYPE_ID);
            parcel.writeString8(scheme);
            authority.writeTo(parcel);
            path.writeTo(parcel);
            query.writeTo(parcel);
            fragment.writeTo(parcel);
        }

        public boolean isHierarchical() {
            return true;
        }

        public boolean isRelative() {
            return scheme == null;
        }

        public String getScheme() {
            return scheme;
        }

        private Part ssp;

        private Part getSsp() {
            return ssp == null
                    ? ssp = Part.fromEncoded(makeSchemeSpecificPart()) : ssp;
        }

        public String getEncodedSchemeSpecificPart() {
            return getSsp().getEncoded();
        }

        public String getSchemeSpecificPart() {
            return getSsp().getDecoded();
        }

        /**
         * Creates the encoded scheme-specific part from its sub parts.
         */
        private String makeSchemeSpecificPart() {
            StringBuilder builder = new StringBuilder();
            appendSspTo(builder);
            return builder.toString();
        }

        private void appendSspTo(StringBuilder builder) {
            String encodedAuthority = authority.getEncoded();
            if (encodedAuthority != null) {
                // Even if the authority is "", we still want to append "//".
                builder.append("//").append(encodedAuthority);
            }

            String encodedPath = path.getEncoded();
            if (encodedPath != null) {
                builder.append(encodedPath);
            }

            if (!query.isEmpty()) {
                builder.append('?').append(query.getEncoded());
            }
        }

        public String getAuthority() {
            return this.authority.getDecoded();
        }

        public String getEncodedAuthority() {
            return this.authority.getEncoded();
        }

        public String getEncodedPath() {
            return this.path.getEncoded();
        }

        public String getPath() {
            return this.path.getDecoded();
        }

        public String getQuery() {
            return this.query.getDecoded();
        }

        public String getEncodedQuery() {
            return this.query.getEncoded();
        }

        public String getFragment() {
            return this.fragment.getDecoded();
        }

        public String getEncodedFragment() {
            return this.fragment.getEncoded();
        }

        public List<String> getPathSegments() {
            return this.path.getPathSegments();
        }

        private volatile String uriString = NotCachedHolder.NOT_CACHED;

        @Override
        public String toString() {
            @SuppressWarnings("StringEquality")
            boolean cached = (uriString != NotCachedHolder.NOT_CACHED);
            return cached ? uriString
                    : (uriString = makeUriString());
        }

        private String makeUriString() {
            StringBuilder builder = new StringBuilder();

            if (scheme != null) {
                builder.append(scheme).append(':');
            }

            appendSspTo(builder);

            if (!fragment.isEmpty()) {
                builder.append('#').append(fragment.getEncoded());
            }

            return builder.toString();
        }

        public Builder buildUpon() {
            return new Builder()
                    .scheme(scheme)
                    .authority(authority)
                    .path(path)
                    .query(query)
                    .fragment(fragment);
        }
    }

    /**
     * Helper class for building or manipulating URI references. Not safe for
     * concurrent use.
     *
     * <p>An absolute hierarchical URI reference follows the pattern:
     * {@code <scheme>://<authority><absolute path>?<query>#<fragment>}
     *
     * <p>Relative URI references (which are always hierarchical) follow one
     * of two patterns: {@code <relative or absolute path>?<query>#<fragment>}
     * or {@code //<authority><absolute path>?<query>#<fragment>}
     *
     * <p>An opaque URI follows this pattern:
     * {@code <scheme>:<opaque part>#<fragment>}
     *
     * <p>Use {@link Uri#buildUpon()} to obtain a builder representing an existing URI.
     */
    public static final class Builder {

        private String scheme;
        private Part opaquePart;
        private Part authority;
        private PathPart path;
        private Part query;
        private Part fragment;

        /**
         * Constructs a new Builder.
         */
        public Builder() {}

        /**
         * Sets the scheme.
         *
         * @param scheme name or {@code null} if this is a relative Uri
         */
        public Builder scheme(String scheme) {
            if (scheme != null) {
                this.scheme = scheme.replace("://", "");
            } else {
                this.scheme = null;
            }
            return this;
        }

        Builder opaquePart(Part opaquePart) {
            this.opaquePart = opaquePart;
            return this;
        }

        /**
         * Encodes and sets the given opaque scheme-specific-part.
         *
         * @param opaquePart decoded opaque part
         */
        public Builder opaquePart(String opaquePart) {
            return opaquePart(Part.fromDecoded(opaquePart));
        }

        /**
         * Sets the previously encoded opaque scheme-specific-part.
         *
         * @param opaquePart encoded opaque part
         */
        public Builder encodedOpaquePart(String opaquePart) {
            return opaquePart(Part.fromEncoded(opaquePart));
        }

        Builder authority(Part authority) {
            // This URI will be hierarchical.
            this.opaquePart = null;

            this.authority = authority;
            return this;
        }

        /**
         * Encodes and sets the authority.
         */
        public Builder authority(String authority) {
            return authority(Part.fromDecoded(authority));
        }

        /**
         * Sets the previously encoded authority.
         */
        public Builder encodedAuthority(String authority) {
            return authority(Part.fromEncoded(authority));
        }

        Builder path(PathPart path) {
            // This URI will be hierarchical.
            this.opaquePart = null;

            this.path = path;
            return this;
        }

        /**
         * Sets the path. Leaves '/' characters intact but encodes others as
         * necessary.
         *
         * <p>If the path is not null and doesn't start with a '/', and if
         * you specify a scheme and/or authority, the builder will prepend the
         * given path with a '/'.
         */
        public Builder path(String path) {
            return path(PathPart.fromDecoded(path));
        }

        /**
         * Sets the previously encoded path.
         *
         * <p>If the path is not null and doesn't start with a '/', and if
         * you specify a scheme and/or authority, the builder will prepend the
         * given path with a '/'.
         */
        public Builder encodedPath(String path) {
            return path(PathPart.fromEncoded(path));
        }

        /**
         * Encodes the given segment and appends it to the path.
         */
        public Builder appendPath(String newSegment) {
            return path(PathPart.appendDecodedSegment(path, newSegment));
        }

        /**
         * Appends the given segment to the path.
         */
        public Builder appendEncodedPath(String newSegment) {
            return path(PathPart.appendEncodedSegment(path, newSegment));
        }

        Builder query(Part query) {
            // This URI will be hierarchical.
            this.opaquePart = null;

            this.query = query;
            return this;
        }

        /**
         * Encodes and sets the query.
         */
        public Builder query(String query) {
            return query(Part.fromDecoded(query));
        }

        /**
         * Sets the previously encoded query.
         */
        public Builder encodedQuery(String query) {
            return query(Part.fromEncoded(query));
        }

        Builder fragment(Part fragment) {
            this.fragment = fragment;
            return this;
        }

        /**
         * Encodes and sets the fragment.
         */
        public Builder fragment(String fragment) {
            return fragment(Part.fromDecoded(fragment));
        }

        /**
         * Sets the previously encoded fragment.
         */
        public Builder encodedFragment(String fragment) {
            return fragment(Part.fromEncoded(fragment));
        }

        /**
         * Encodes the key and value and then appends the parameter to the
         * query string.
         *
         * @param key which will be encoded
         * @param value which will be encoded
         */
        public Builder appendQueryParameter(String key, String value) {
            // This URI will be hierarchical.
            this.opaquePart = null;

            String encodedParameter = encode(key, null) + "="
                    + encode(value, null);

            if (query == null) {
                query = Part.fromEncoded(encodedParameter);
                return this;
            }

            String oldQuery = query.getEncoded();
            if (oldQuery == null || oldQuery.length() == 0) {
                query = Part.fromEncoded(encodedParameter);
            } else {
                query = Part.fromEncoded(oldQuery + "&" + encodedParameter);
            }

            return this;
        }

        /**
         * Clears the the previously set query.
         */
        public Builder clearQuery() {
          return query((Part) null);
        }

        /**
         * Constructs a Uri with the current attributes.
         *
         * @throws UnsupportedOperationException if the URI is opaque and the
         *  scheme is null
         */
        public Uri build() {
            if (opaquePart != null) {
                if (this.scheme == null) {
                    throw new UnsupportedOperationException(
                            "An opaque URI must have a scheme.");
                }

                return new OpaqueUri(scheme, opaquePart, fragment);
            } else {
                // Hierarchical URIs should not return null for getPath().
                PathPart path = this.path;
                if (path == null || path == PathPart.NULL) {
                    path = PathPart.EMPTY;
                } else {
                    // If we have a scheme and/or authority, the path must
                    // be absolute. Prepend it with a '/' if necessary.
                    if (hasSchemeOrAuthority()) {
                        path = PathPart.makeAbsolute(path);
                    }
                }

                return new HierarchicalUri(
                        scheme, authority, path, query, fragment);
            }
        }

        private boolean hasSchemeOrAuthority() {
            return scheme != null
                    || (authority != null && authority != Part.NULL);

        }

        @Override
        public String toString() {
            return build().toString();
        }
    }

    /**
     * Returns a set of the unique names of all query parameters. Iterating
     * over the set will return the names in order of their first occurrence.
     *
     * @throws UnsupportedOperationException if this isn't a hierarchical URI
     *
     * @return a set of decoded names
     */
    public Set<String> getQueryParameterNames() {
        if (isOpaque()) {
            throw new UnsupportedOperationException(NOT_HIERARCHICAL);
        }

        String query = getEncodedQuery();
        if (query == null) {
            return Collections.emptySet();
        }

        Set<String> names = new LinkedHashSet<String>();
        int start = 0;
        do {
            int next = query.indexOf('&', start);
            int end = (next == -1) ? query.length() : next;

            int separator = query.indexOf('=', start);
            if (separator > end || separator == -1) {
                separator = end;
            }

            String name = query.substring(start, separator);
            names.add(decode(name));

            // Move start to end of name.
            start = end + 1;
        } while (start < query.length());

        return Collections.unmodifiableSet(names);
    }

    /**
     * Searches the query string for parameter values with the given key.
     *
     * @param key which will be encoded
     *
     * @throws UnsupportedOperationException if this isn't a hierarchical URI
     * @throws NullPointerException if key is null
     * @return a list of decoded values
     */
    public List<String> getQueryParameters(String key) {
        if (isOpaque()) {
            throw new UnsupportedOperationException(NOT_HIERARCHICAL);
        }
        if (key == null) {
          throw new NullPointerException("key");
        }

        String query = getEncodedQuery();
        if (query == null) {
            return Collections.emptyList();
        }

        String encodedKey;
        try {
            encodedKey = URLEncoder.encode(key, DEFAULT_ENCODING);
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }

        ArrayList<String> values = new ArrayList<String>();

        int start = 0;
        do {
            int nextAmpersand = query.indexOf('&', start);
            int end = nextAmpersand != -1 ? nextAmpersand : query.length();

            int separator = query.indexOf('=', start);
            if (separator > end || separator == -1) {
                separator = end;
            }

            if (separator - start == encodedKey.length()
                    && query.regionMatches(start, encodedKey, 0, encodedKey.length())) {
                if (separator == end) {
                  values.add("");
                } else {
                  values.add(decode(query.substring(separator + 1, end)));
                }
            }

            // Move start to end of name.
            if (nextAmpersand != -1) {
                start = nextAmpersand + 1;
            } else {
                break;
            }
        } while (true);

        return Collections.unmodifiableList(values);
    }

    /**
     * Searches the query string for the first value with the given key.
     *
     * <p><strong>Warning:</strong> Prior to Jelly Bean, this decoded
     * the '+' character as '+' rather than ' '.
     *
     * @param key which will be encoded
     * @throws UnsupportedOperationException if this isn't a hierarchical URI
     * @throws NullPointerException if key is null
     * @return the decoded value or null if no parameter is found
     */
    @Nullable
    public String getQueryParameter(String key) {
        if (isOpaque()) {
            throw new UnsupportedOperationException(NOT_HIERARCHICAL);
        }
        if (key == null) {
            throw new NullPointerException("key");
        }

        final String query = getEncodedQuery();
        if (query == null) {
            return null;
        }

        final String encodedKey = encode(key, null);
        final int length = query.length();
        int start = 0;
        do {
            int nextAmpersand = query.indexOf('&', start);
            int end = nextAmpersand != -1 ? nextAmpersand : length;

            int separator = query.indexOf('=', start);
            if (separator > end || separator == -1) {
                separator = end;
            }

            if (separator - start == encodedKey.length()
                    && query.regionMatches(start, encodedKey, 0, encodedKey.length())) {
                if (separator == end) {
                    return "";
                } else {
                    String encodedValue = query.substring(separator + 1, end);
                    return UriCodec.decode(encodedValue, true, StandardCharsets.UTF_8, false);
                }
            }

            // Move start to end of name.
            if (nextAmpersand != -1) {
                start = nextAmpersand + 1;
            } else {
                break;
            }
        } while (true);
        return null;
    }

    /**
     * Searches the query string for the first value with the given key and interprets it
     * as a boolean value. "false" and "0" are interpreted as <code>false</code>, everything
     * else is interpreted as <code>true</code>.
     *
     * @param key which will be decoded
     * @param defaultValue the default value to return if there is no query parameter for key
     * @return the boolean interpretation of the query parameter key
     */
    public boolean getBooleanQueryParameter(String key, boolean defaultValue) {
        String flag = getQueryParameter(key);
        if (flag == null) {
            return defaultValue;
        }
        flag = flag.toLowerCase(Locale.ROOT);
        return (!"false".equals(flag) && !"0".equals(flag));
    }

    /**
     * Return an equivalent URI with a lowercase scheme component.
     * This aligns the Uri with Android best practices for
     * intent filtering.
     *
     * <p>For example, "HTTP://www.android.com" becomes
     * "http://www.android.com"
     *
     * <p>All URIs received from outside Android (such as user input,
     * or external sources like Bluetooth, NFC, or the Internet) should
     * be normalized before they are used to create an Intent.
     *
     * <p class="note">This method does <em>not</em> validate bad URI's,
     * or 'fix' poorly formatted URI's - so do not use it for input validation.
     * A Uri will always be returned, even if the Uri is badly formatted to
     * begin with and a scheme component cannot be found.
     *
     * @return normalized Uri (never null)
     * @see android.content.Intent#setData
     * @see android.content.Intent#setDataAndNormalize
     */
    public Uri normalizeScheme() {
        String scheme = getScheme();
        if (scheme == null) return this;  // give up
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (scheme.equals(lowerScheme)) return this;  // no change

        return buildUpon().scheme(lowerScheme).build();
    }

    /** Identifies a null parcelled Uri. */
    private static final int NULL_TYPE_ID = 0;

    /**
     * Reads Uris from Parcels.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<Uri> CREATOR
            = new Parcelable.Creator<Uri>() {
        public Uri createFromParcel(Parcel in) {
            int type = in.readInt();
            switch (type) {
                case NULL_TYPE_ID: return null;
                case StringUri.TYPE_ID: return StringUri.readFrom(in);
                case OpaqueUri.TYPE_ID: return OpaqueUri.readFrom(in);
                case HierarchicalUri.TYPE_ID:
                    return HierarchicalUri.readFrom(in);
            }

            throw new IllegalArgumentException("Unknown URI type: " + type);
        }

        public Uri[] newArray(int size) {
            return new Uri[size];
        }
    };

    /**
     * Writes a Uri to a Parcel.
     *
     * @param out parcel to write to
     * @param uri to write, can be null
     */
    public static void writeToParcel(Parcel out, Uri uri) {
        if (uri == null) {
            out.writeInt(NULL_TYPE_ID);
        } else {
            uri.writeToParcel(out, 0);
        }
    }

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    /**
     * Encodes characters in the given string as '%'-escaped octets
     * using the UTF-8 scheme. Leaves letters ("A-Z", "a-z"), numbers
     * ("0-9"), and unreserved characters ("_-!.~'()*") intact. Encodes
     * all other characters.
     *
     * @param s string to encode
     * @return an encoded version of s suitable for use as a URI component,
     *  or null if s is null
     */
    public static String encode(String s) {
        return encode(s, null);
    }

    /**
     * Encodes characters in the given string as '%'-escaped octets
     * using the UTF-8 scheme. Leaves letters ("A-Z", "a-z"), numbers
     * ("0-9"), and unreserved characters ("_-!.~'()*") intact. Encodes
     * all other characters with the exception of those specified in the
     * allow argument.
     *
     * @param s string to encode
     * @param allow set of additional characters to allow in the encoded form,
     *  null if no characters should be skipped
     * @return an encoded version of s suitable for use as a URI component,
     *  or null if s is null
     */
    public static String encode(String s, String allow) {
        if (s == null) {
            return null;
        }

        // Lazily-initialized buffers.
        StringBuilder encoded = null;

        int oldLength = s.length();

        // This loop alternates between copying over allowed characters and
        // encoding in chunks. This results in fewer method calls and
        // allocations than encoding one character at a time.
        int current = 0;
        while (current < oldLength) {
            // Start in "copying" mode where we copy over allowed chars.

            // Find the next character which needs to be encoded.
            int nextToEncode = current;
            while (nextToEncode < oldLength
                    && isAllowed(s.charAt(nextToEncode), allow)) {
                nextToEncode++;
            }

            // If there's nothing more to encode...
            if (nextToEncode == oldLength) {
                if (current == 0) {
                    // We didn't need to encode anything!
                    return s;
                } else {
                    // Presumably, we've already done some encoding.
                    encoded.append(s, current, oldLength);
                    return encoded.toString();
                }
            }

            if (encoded == null) {
                encoded = new StringBuilder();
            }

            if (nextToEncode > current) {
                // Append allowed characters leading up to this point.
                encoded.append(s, current, nextToEncode);
            } else {
                // assert nextToEncode == current
            }

            // Switch to "encoding" mode.

            // Find the next allowed character.
            current = nextToEncode;
            int nextAllowed = current + 1;
            while (nextAllowed < oldLength
                    && !isAllowed(s.charAt(nextAllowed), allow)) {
                nextAllowed++;
            }

            // Convert the substring to bytes and encode the bytes as
            // '%'-escaped octets.
            String toEncode = s.substring(current, nextAllowed);
            try {
                byte[] bytes = toEncode.getBytes(DEFAULT_ENCODING);
                int bytesLength = bytes.length;
                for (int i = 0; i < bytesLength; i++) {
                    encoded.append('%');
                    encoded.append(HEX_DIGITS[(bytes[i] & 0xf0) >> 4]);
                    encoded.append(HEX_DIGITS[bytes[i] & 0xf]);
                }
            } catch (UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }

            current = nextAllowed;
        }

        // Encoded could still be null at this point if s is empty.
        return encoded == null ? s : encoded.toString();
    }

    /**
     * Returns true if the given character is allowed.
     *
     * @param c character to check
     * @param allow characters to allow
     * @return true if the character is allowed or false if it should be
     *  encoded
     */
    private static boolean isAllowed(char c, String allow) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || "_-!.~'()*".indexOf(c) != NOT_FOUND
                || (allow != null && allow.indexOf(c) != NOT_FOUND);
    }

    /**
     * Decodes '%'-escaped octets in the given string using the UTF-8 scheme.
     * Replaces invalid octets with the unicode replacement character
     * ("\\uFFFD").
     *
     * @param s encoded string to decode
     * @return the given string with escaped octets decoded, or null if
     *  s is null
     */
    public static String decode(String s) {
        if (s == null) {
            return null;
        }
        return UriCodec.decode(
                s, false /* convertPlus */, StandardCharsets.UTF_8, false /* throwOnFailure */);
    }

    /**
     * Support for part implementations.
     */
    static abstract class AbstractPart {

        // Possible values of mCanonicalRepresentation.
        static final int REPRESENTATION_ENCODED = 1;
        static final int REPRESENTATION_DECODED = 2;

        volatile String encoded;
        volatile String decoded;
        private final int mCanonicalRepresentation;

        AbstractPart(String encoded, String decoded) {
            if (encoded != NotCachedHolder.NOT_CACHED) {
                this.mCanonicalRepresentation = REPRESENTATION_ENCODED;
                this.encoded = encoded;
                this.decoded = NotCachedHolder.NOT_CACHED;
            } else if (decoded != NotCachedHolder.NOT_CACHED) {
                this.mCanonicalRepresentation = REPRESENTATION_DECODED;
                this.encoded = NotCachedHolder.NOT_CACHED;
                this.decoded = decoded;
            } else {
                throw new IllegalArgumentException("Neither encoded nor decoded");
            }
        }

        abstract String getEncoded();

        final String getDecoded() {
            @SuppressWarnings("StringEquality")
            boolean hasDecoded = decoded != NotCachedHolder.NOT_CACHED;
            return hasDecoded ? decoded : (decoded = decode(encoded));
        }

        final void writeTo(Parcel parcel) {
            final String canonicalValue;
            if (mCanonicalRepresentation == REPRESENTATION_ENCODED) {
                canonicalValue = encoded;
            } else if (mCanonicalRepresentation == REPRESENTATION_DECODED) {
                canonicalValue = decoded;
            } else {
                throw new IllegalArgumentException("Unknown representation: "
                    + mCanonicalRepresentation);
            }
            if (canonicalValue == NotCachedHolder.NOT_CACHED) {
                throw new AssertionError("Canonical value not cached ("
                    + mCanonicalRepresentation + ")");
            }
            parcel.writeInt(mCanonicalRepresentation);
            parcel.writeString8(canonicalValue);
        }
    }

    /**
     * Immutable wrapper of encoded and decoded versions of a URI part. Lazily
     * creates the encoded or decoded version from the other.
     */
    static class Part extends AbstractPart {

        /** A part with null values. */
        static final Part NULL = new EmptyPart(null);

        /** A part with empty strings for values. */
        static final Part EMPTY = new EmptyPart("");

        private Part(String encoded, String decoded) {
            super(encoded, decoded);
        }

        boolean isEmpty() {
            return false;
        }

        String getEncoded() {
            @SuppressWarnings("StringEquality")
            boolean hasEncoded = encoded != NotCachedHolder.NOT_CACHED;
            return hasEncoded ? encoded : (encoded = encode(decoded));
        }

        static Part readFrom(Parcel parcel) {
            int representation = parcel.readInt();
            String value = parcel.readString8();
            switch (representation) {
                case REPRESENTATION_ENCODED:
                    return fromEncoded(value);
                case REPRESENTATION_DECODED:
                    return fromDecoded(value);
                default:
                    throw new IllegalArgumentException("Unknown representation: "
                            + representation);
            }
        }

        /**
         * Returns given part or {@link #NULL} if the given part is null.
         */
        static Part nonNull(Part part) {
            return part == null ? NULL : part;
        }

        /**
         * Creates a part from the encoded string.
         *
         * @param encoded part string
         */
        static Part fromEncoded(String encoded) {
            return from(encoded, NotCachedHolder.NOT_CACHED);
        }

        /**
         * Creates a part from the decoded string.
         *
         * @param decoded part string
         */
        static Part fromDecoded(String decoded) {
            return from(NotCachedHolder.NOT_CACHED, decoded);
        }

        /**
         * Creates a part from the encoded and decoded strings.
         *
         * @param encoded part string
         * @param decoded part string
         */
        static Part from(String encoded, String decoded) {
            // We have to check both encoded and decoded in case one is
            // NotCachedHolder.NOT_CACHED.

            if (encoded == null) {
                return NULL;
            }
            if (encoded.length() == 0) {
                return EMPTY;
            }

            if (decoded == null) {
                return NULL;
            }
            if (decoded .length() == 0) {
                return EMPTY;
            }

            return new Part(encoded, decoded);
        }

        private static class EmptyPart extends Part {
            public EmptyPart(String value) {
                super(value, value);
                if (value != null && !value.isEmpty()) {
                    throw new IllegalArgumentException("Expected empty value, got: " + value);
                }
                // Avoid having to re-calculate the non-canonical value.
                encoded = decoded = value;
            }

            @Override
            boolean isEmpty() {
                return true;
            }
        }
    }

    /**
     * Immutable wrapper of encoded and decoded versions of a path part. Lazily
     * creates the encoded or decoded version from the other.
     */
    static class PathPart extends AbstractPart {

        /** A part with null values. */
        static final PathPart NULL = new PathPart(null, null);

        /** A part with empty strings for values. */
        static final PathPart EMPTY = new PathPart("", "");

        private PathPart(String encoded, String decoded) {
            super(encoded, decoded);
        }

        String getEncoded() {
            @SuppressWarnings("StringEquality")
            boolean hasEncoded = encoded != NotCachedHolder.NOT_CACHED;

            // Don't encode '/'.
            return hasEncoded ? encoded : (encoded = encode(decoded, "/"));
        }

        /**
         * Cached path segments. This doesn't need to be volatile--we don't
         * care if other threads see the result.
         */
        private PathSegments pathSegments;

        /**
         * Gets the individual path segments. Parses them if necessary.
         *
         * @return parsed path segments or null if this isn't a hierarchical
         *  URI
         */
        PathSegments getPathSegments() {
            if (pathSegments != null) {
                return pathSegments;
            }

            String path = getEncoded();
            if (path == null) {
                return pathSegments = PathSegments.EMPTY;
            }

            PathSegmentsBuilder segmentBuilder = new PathSegmentsBuilder();

            int previous = 0;
            int current;
            while ((current = path.indexOf('/', previous)) > -1) {
                // This check keeps us from adding a segment if the path starts
                // '/' and an empty segment for "//".
                if (previous < current) {
                    String decodedSegment
                            = decode(path.substring(previous, current));
                    segmentBuilder.add(decodedSegment);
                }
                previous = current + 1;
            }

            // Add in the final path segment.
            if (previous < path.length()) {
                segmentBuilder.add(decode(path.substring(previous)));
            }

            return pathSegments = segmentBuilder.build();
        }

        static PathPart appendEncodedSegment(PathPart oldPart,
                String newSegment) {
            // If there is no old path, should we make the new path relative
            // or absolute? I pick absolute.

            if (oldPart == null) {
                // No old path.
                return fromEncoded("/" + newSegment);
            }

            String oldPath = oldPart.getEncoded();

            if (oldPath == null) {
                oldPath = "";
            }

            int oldPathLength = oldPath.length();
            String newPath;
            if (oldPathLength == 0) {
                // No old path.
                newPath = "/" + newSegment;
            } else if (oldPath.charAt(oldPathLength - 1) == '/') {
                newPath = oldPath + newSegment;
            } else {
                newPath = oldPath + "/" + newSegment;
            }

            return fromEncoded(newPath);
        }

        static PathPart appendDecodedSegment(PathPart oldPart, String decoded) {
            String encoded = encode(decoded);

            // TODO: Should we reuse old PathSegments? Probably not.
            return appendEncodedSegment(oldPart, encoded);
        }

        static PathPart readFrom(Parcel parcel) {
            int representation = parcel.readInt();
            switch (representation) {
                case REPRESENTATION_ENCODED:
                    return fromEncoded(parcel.readString8());
                case REPRESENTATION_DECODED:
                    return fromDecoded(parcel.readString8());
                default:
                    throw new IllegalArgumentException("Unknown representation: " + representation);
            }
        }

        static PathPart readFrom(boolean hasSchemeOrAuthority, Parcel parcel) {
            final PathPart path = readFrom(parcel);
            return hasSchemeOrAuthority ? makeAbsolute(path) : path;
        }

        /**
         * Creates a path from the encoded string.
         *
         * @param encoded part string
         */
        static PathPart fromEncoded(String encoded) {
            return from(encoded, NotCachedHolder.NOT_CACHED);
        }

        /**
         * Creates a path from the decoded string.
         *
         * @param decoded part string
         */
        static PathPart fromDecoded(String decoded) {
            return from(NotCachedHolder.NOT_CACHED, decoded);
        }

        /**
         * Creates a path from the encoded and decoded strings.
         *
         * @param encoded part string
         * @param decoded part string
         */
        static PathPart from(String encoded, String decoded) {
            if (encoded == null) {
                return NULL;
            }

            if (encoded.length() == 0) {
                return EMPTY;
            }

            return new PathPart(encoded, decoded);
        }

        /**
         * Prepends path values with "/" if they're present, not empty, and
         * they don't already start with "/".
         */
        static PathPart makeAbsolute(PathPart oldPart) {
            @SuppressWarnings("StringEquality")
            boolean encodedCached = oldPart.encoded != NotCachedHolder.NOT_CACHED;

            // We don't care which version we use, and we don't want to force
            // unneccessary encoding/decoding.
            String oldPath = encodedCached ? oldPart.encoded : oldPart.decoded;

            if (oldPath == null || oldPath.length() == 0
                    || oldPath.startsWith("/")) {
                return oldPart;
            }

            // Prepend encoded string if present.
            String newEncoded = encodedCached
                    ? "/" + oldPart.encoded : NotCachedHolder.NOT_CACHED;

            // Prepend decoded string if present.
            @SuppressWarnings("StringEquality")
            boolean decodedCached = oldPart.decoded != NotCachedHolder.NOT_CACHED;
            String newDecoded = decodedCached
                    ? "/" + oldPart.decoded
                    : NotCachedHolder.NOT_CACHED;

            return new PathPart(newEncoded, newDecoded);
        }
    }

    /**
     * Creates a new Uri by appending an already-encoded path segment to a
     * base Uri.
     *
     * @param baseUri Uri to append path segment to
     * @param pathSegment encoded path segment to append
     * @return a new Uri based on baseUri with the given segment appended to
     *  the path
     * @throws NullPointerException if baseUri is null
     */
    public static Uri withAppendedPath(Uri baseUri, String pathSegment) {
        Builder builder = baseUri.buildUpon();
        builder = builder.appendEncodedPath(pathSegment);
        return builder.build();
    }

    /**
     * If this {@link Uri} is {@code file://}, then resolve and return its
     * canonical path. Also fixes legacy emulated storage paths so they are
     * usable across user boundaries. Should always be called from the app
     * process before sending elsewhere.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public Uri getCanonicalUri() {
        if ("file".equals(getScheme())) {
            final String canonicalPath;
            try {
                canonicalPath = new File(getPath()).getCanonicalPath();
            } catch (IOException e) {
                return this;
            }

            if (Environment.isExternalStorageEmulated()) {
                final String legacyPath = Environment.getLegacyExternalStorageDirectory()
                        .toString();

                // Splice in user-specific path when legacy path is found
                if (canonicalPath.startsWith(legacyPath)) {
                    return Uri.fromFile(new File(
                            Environment.getExternalStorageDirectory().toString(),
                            canonicalPath.substring(legacyPath.length() + 1)));
                }
            }

            return Uri.fromFile(new File(canonicalPath));
        } else {
            return this;
        }
    }

    /**
     * If this is a {@code file://} Uri, it will be reported to
     * {@link StrictMode}.
     *
     * @hide
     */
    public void checkFileUriExposed(String location) {
        if ("file".equals(getScheme())
                && (getPath() != null) && !getPath().startsWith("/system/")) {
            StrictMode.onFileUriExposed(this, location);
        }
    }

    /**
     * If this is a {@code content://} Uri without access flags, it will be
     * reported to {@link StrictMode}.
     *
     * @hide
     */
    public void checkContentUriWithoutPermission(String location, int flags) {
        if ("content".equals(getScheme()) && !Intent.isAccessUriMode(flags)) {
            StrictMode.onContentUriWithoutPermission(this, location);
        }
    }

    /**
     * Test if this is a path prefix match against the given Uri. Verifies that
     * scheme, authority, and atomic path segments match.
     *
     * @hide
     */
    public boolean isPathPrefixMatch(Uri prefix) {
        if (!Objects.equals(getScheme(), prefix.getScheme())) return false;
        if (!Objects.equals(getAuthority(), prefix.getAuthority())) return false;

        List<String> seg = getPathSegments();
        List<String> prefixSeg = prefix.getPathSegments();

        final int prefixSize = prefixSeg.size();
        if (seg.size() < prefixSize) return false;

        for (int i = 0; i < prefixSize; i++) {
            if (!Objects.equals(seg.get(i), prefixSeg.get(i))) {
                return false;
            }
        }

        return true;
    }
}
