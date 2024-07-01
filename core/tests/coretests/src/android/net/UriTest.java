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

import android.content.ContentUris;
import android.os.Parcel;
import android.platform.test.annotations.AsbSecurityTest;

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class UriTest extends TestCase {

    @SmallTest
    public void testToStringWithPathOnly() {
        Uri.Builder builder = new Uri.Builder();

        // Not a valid path, but this came from a user's test case.
        builder.path("//foo");
        Uri uri = builder.build();
        assertEquals("//foo", uri.toString());
    }

    @SmallTest
    public void testParcelling() {
        parcelAndUnparcel(Uri.parse("foo:bob%20lee"));
        parcelAndUnparcel(Uri.fromParts("foo", "bob lee", "fragment"));
        parcelAndUnparcel(Uri.fromParts("https", "www.google.com", null));
        parcelAndUnparcel(new Uri.Builder()
            .scheme("http")
            .authority("crazybob.org")
            .path("/rss/")
            .encodedQuery("a=b")
            .fragment("foo")
            .build());
    }

    private void parcelAndUnparcel(Uri u) {
        Parcel p = Parcel.obtain();
        try {
            Uri.writeToParcel(p, u);
            p.setDataPosition(0);
            assertEquals(u, Uri.CREATOR.createFromParcel(p));

            p.setDataPosition(0);
            u = u.buildUpon().build();
            Uri.writeToParcel(p, u);
            p.setDataPosition(0);
            assertEquals(u, Uri.CREATOR.createFromParcel(p));
        }
        finally {
            p.recycle();
        }
    }

    @SmallTest
    public void testBuildUponOpaqueStringUri() {
        Uri u = Uri.parse("bob:lee").buildUpon().scheme("robert").build();
        assertEquals("robert", u.getScheme());
        assertEquals("lee", u.getEncodedSchemeSpecificPart());
        assertEquals("lee", u.getSchemeSpecificPart());
        assertNull(u.getQuery());
        assertNull(u.getPath());
        assertNull(u.getAuthority());
        assertNull(u.getHost());
    }

    @AsbSecurityTest(cveBugId = 261721900)
    @SmallTest
    public void testSchemeSanitization() {
        Uri uri = new Uri.Builder()
                .scheme("http://https://evil.com:/te:st/")
                .authority("google.com").path("one/way").build();
        assertEquals("httphttpsevil.com:/te:st/", uri.getScheme());
        assertEquals("httphttpsevil.com:/te:st/://google.com/one/way", uri.toString());
    }

    @SmallTest
    public void testStringUri() {
        assertEquals("bob lee",
                Uri.parse("foo:bob%20lee").getSchemeSpecificPart());
        assertEquals("bob%20lee",
                Uri.parse("foo:bob%20lee").getEncodedSchemeSpecificPart());
        assertEquals("/bob%20lee",
                Uri.parse("foo:/bob%20lee").getEncodedPath());
        assertNull(Uri.parse("foo:bob%20lee").getPath());
        assertEquals("bob%20lee",
                Uri.parse("foo:?bob%20lee").getEncodedQuery());
        assertNull(Uri.parse("foo:bar#?bob%20lee").getQuery());
        assertEquals("bob%20lee",
                Uri.parse("foo:#bob%20lee").getEncodedFragment());
    }

    @SmallTest
    public void testStringUriIsHierarchical() {
        assertTrue(Uri.parse("bob").isHierarchical());
        assertFalse(Uri.parse("bob:").isHierarchical());
    }

    @SmallTest
    public void testNullUriString() {
        try {
            Uri.parse(null);
            fail();
        } catch (NullPointerException e) {}
    }

    @SmallTest
    public void testNullFile() {
        try {
            Uri.fromFile(null);
            fail();
        } catch (NullPointerException e) {}
    }

    @SmallTest
    public void testCompareTo() {
        Uri a = Uri.parse("foo:a");
        Uri b = Uri.parse("foo:b");
        Uri b2 = Uri.parse("foo:b");

        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
        assertEquals(0, b.compareTo(b2));
    }

    /**
     * Check that {@link Uri#EMPTY} is properly initialized to guard against a
     * regression based on a problematic initialization order (b/159907422).
     *
     * The problematic initialization order happened when {@code Uri$PathPart<clinit>}
     * ran before {@code Uri.<clinit>}. De facto this test would probably never have
     * failed on Android because {@code Uri.<clinit>} will almost certainly have run
     * somewhere in the Zygote, but just in case and in case this test is ever run on
     * a platform that doesn't perform Zygote initialization, this test attempts to
     * trigger {@code Uri$PathPart<clinit>} prior to inspecting {@link Uri#EMPTY}.
     */
    @SmallTest
    public void testEmpty_initializerOrder() {
        new Uri.Builder().scheme("http").path("path").build();
        assertEquals("", Uri.EMPTY.toString());
    }

    @SmallTest
    public void testEqualsAndHashCode() {

        Uri a = Uri.parse("http://crazybob.org/test/?foo=bar#tee");

        Uri b = new Uri.Builder()
                .scheme("http")
                .authority("crazybob.org")
                .path("/test/")
                .encodedQuery("foo=bar")
                .fragment("tee")
                .build();

        // Try alternate builder methods.
        Uri c = new Uri.Builder()
                .scheme("http")
                .encodedAuthority("crazybob.org")
                .encodedPath("/test/")
                .encodedQuery("foo=bar")
                .encodedFragment("tee")
                .build();

        assertFalse(Uri.EMPTY.equals(null));

        assertEquals(a, b);
        assertEquals(b, c);
        assertEquals(c, a);

        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(b.hashCode(), c.hashCode());
    }

    @SmallTest
    public void testAuthorityParsing() {
        Uri uri = Uri.parse("http://localhost:42");
        assertEquals("localhost", uri.getHost());
        assertEquals(42, uri.getPort());

        uri = Uri.parse("http://bob@localhost:42");
        assertEquals("bob", uri.getUserInfo());
        assertEquals("localhost", uri.getHost());
        assertEquals(42, uri.getPort());

        uri = Uri.parse("http://bob%20lee@localhost:42");
        assertEquals("bob lee", uri.getUserInfo());
        assertEquals("bob%20lee", uri.getEncodedUserInfo());

        uri = Uri.parse("http://bob%40lee%3ajr@local%68ost:4%32");
        assertEquals("bob@lee:jr", uri.getUserInfo());
        assertEquals("localhost:42", uri.getHost());

        uri = Uri.parse("http://localhost");
        assertEquals("localhost", uri.getHost());
        assertEquals(-1, uri.getPort());

        uri = Uri.parse("http://a:a@example.com:a@example2.com/path");
        assertEquals("a:a@example.com:a@example2.com", uri.getAuthority());
        assertEquals("example2.com", uri.getHost());
        assertEquals(-1, uri.getPort());
        assertEquals("/path", uri.getPath());

        uri = Uri.parse("http://a.foo.com\\.example.com/path");
        assertEquals("a.foo.com", uri.getHost());
        assertEquals(-1, uri.getPort());
        assertEquals("\\.example.com/path", uri.getPath());
    }

    @SmallTest
    public void testBuildUponOpaqueUri() {
        Uri a = Uri.fromParts("foo", "bar", "tee");
        Uri b = a.buildUpon().fragment("new").build();
        assertEquals("new", b.getFragment());
        assertEquals("bar", b.getSchemeSpecificPart());
        assertEquals("foo", b.getScheme());
    }

    @SmallTest
    public void testBuildUponEncodedOpaqueUri() {
        Uri a = new Uri.Builder()
                .scheme("foo")
                .encodedOpaquePart("bar")
                .fragment("tee")
                .build();
        Uri b = a.buildUpon().fragment("new").build();
        assertEquals("new", b.getFragment());
        assertEquals("bar", b.getSchemeSpecificPart());
        assertEquals("foo", b.getScheme());
    }

    @SmallTest
    public void testPathSegmentDecoding() {
        Uri uri = Uri.parse("foo://bar/a%20a/b%20b");
        assertEquals("a a", uri.getPathSegments().get(0));
        assertEquals("b b", uri.getPathSegments().get(1));
    }

    @SmallTest
    public void testSms() {
        Uri base = Uri.parse("content://sms");
        Uri appended = base.buildUpon()
                .appendEncodedPath("conversations/addr=555-1212")
                .build();
        assertEquals("content://sms/conversations/addr=555-1212",
                appended.toString());
        assertEquals(2, appended.getPathSegments().size());
        assertEquals("conversations", appended.getPathSegments().get(0));
        assertEquals("addr=555-1212", appended.getPathSegments().get(1));
    }

    @SmallTest
    public void testEncodeWithAllowedChars() {
        String encoded = Uri.encode("Bob:/", "/");
        assertEquals(-1, encoded.indexOf(':'));
        assertTrue(encoded.indexOf('/') > -1);
    }

    @SmallTest
    public void testEncodeDecode() {
        code(null);
        code("");
        code("Bob");
        code(":Bob");
        code("::Bob");
        code("Bob::Lee");
        code("Bob:Lee");
        code("Bob::");
        code("Bob:");
        code("::Bob::");
    }

    private void code(String s) {
        assertEquals(s, Uri.decode(Uri.encode(s, null)));
    }

    @SmallTest
    public void testFile() {
        File f = new File("/tmp/bob");

        Uri uri = Uri.fromFile(f);

        assertEquals("file:///tmp/bob", uri.toString());
    }

    @SmallTest
    public void testQueryParameters() {
        Uri uri = Uri.parse("content://user");

        assertEquals(null, uri.getQueryParameter("a"));

        uri = uri.buildUpon().appendQueryParameter("a", "b").build();

        assertEquals("b", uri.getQueryParameter("a"));

        uri = uri.buildUpon().appendQueryParameter("a", "b2").build();

        assertEquals(Arrays.asList("b", "b2"), uri.getQueryParameters("a"));

        uri = uri.buildUpon().appendQueryParameter("c", "d").build();

        assertEquals(Arrays.asList("b", "b2"), uri.getQueryParameters("a"));
        assertEquals("d", uri.getQueryParameter("c"));
    }

    // http://b/2337042
    @SmallTest
    public void testHostWithTrailingDot() {
        Uri uri = Uri.parse("http://google.com./b/c/g");
        assertEquals("google.com.", uri.getHost());
        assertEquals("/b/c/g", uri.getPath());
    }

    @SmallTest
    public void testSchemeOnly() {
        Uri uri = Uri.parse("empty:");
        assertEquals("empty", uri.getScheme());
        assertTrue(uri.isAbsolute());
        assertNull(uri.getPath());
    }

    @SmallTest
    public void testEmptyPath() {
        Uri uri = Uri.parse("content://user");
        assertEquals(0, uri.getPathSegments().size());
    }

    @SmallTest
    public void testPathOperations() {
        Uri uri = Uri.parse("content://user/a/b");

        assertEquals(2, uri.getPathSegments().size());
        assertEquals("b", uri.getLastPathSegment());

        Uri first = uri;
        uri = uri.buildUpon().appendPath("c").build();

        assertEquals(3, uri.getPathSegments().size());
        assertEquals("c", uri.getLastPathSegment());
        assertEquals("content://user/a/b/c", uri.toString());

        uri = ContentUris.withAppendedId(uri, 100);

        assertEquals(4, uri.getPathSegments().size());
        assertEquals("100", uri.getLastPathSegment());
        assertEquals(100, ContentUris.parseId(uri));
        assertEquals("content://user/a/b/c/100", uri.toString());

        // Make sure the original URI is still intact.
        assertEquals(2, first.getPathSegments().size());
        assertEquals("b", first.getLastPathSegment());

        try {
            first.getPathSegments().get(2);
            fail();
        } catch (IndexOutOfBoundsException e) {}

        assertEquals(null, Uri.EMPTY.getLastPathSegment());

        Uri withC = Uri.parse("foo:/a/b/").buildUpon().appendPath("c").build();
        assertEquals("/a/b/c", withC.getPath());
    }

    @SmallTest
    public void testOpaqueUri() {
        Uri uri = Uri.parse("mailto:nobody");
        testOpaqueUri(uri);

        uri = uri.buildUpon().build();
        testOpaqueUri(uri);

        uri = Uri.fromParts("mailto", "nobody", null);
        testOpaqueUri(uri);

        uri = uri.buildUpon().build();
        testOpaqueUri(uri);

        uri = new Uri.Builder()
                .scheme("mailto")
                .opaquePart("nobody")
                .build();
        testOpaqueUri(uri);

        uri = uri.buildUpon().build();
        testOpaqueUri(uri);
    }

    private void testOpaqueUri(Uri uri) {
        assertEquals("mailto", uri.getScheme());
        assertEquals("nobody", uri.getSchemeSpecificPart());
        assertEquals("nobody", uri.getEncodedSchemeSpecificPart());

        assertNull(uri.getFragment());
        assertTrue(uri.isAbsolute());
        assertTrue(uri.isOpaque());
        assertFalse(uri.isRelative());
        assertFalse(uri.isHierarchical());

        assertNull(uri.getAuthority());
        assertNull(uri.getEncodedAuthority());
        assertNull(uri.getPath());
        assertNull(uri.getEncodedPath());
        assertNull(uri.getUserInfo());
        assertNull(uri.getEncodedUserInfo());
        assertNull(uri.getQuery());
        assertNull(uri.getEncodedQuery());
        assertNull(uri.getHost());
        assertEquals(-1, uri.getPort());

        assertTrue(uri.getPathSegments().isEmpty());
        assertNull(uri.getLastPathSegment());

        assertEquals("mailto:nobody", uri.toString());

        Uri withFragment = uri.buildUpon().fragment("top").build();
        assertEquals("mailto:nobody#top", withFragment.toString());
    }

    @SmallTest
    public void testHierarchicalUris() {
        testHierarchical("http", "google.com", "/p1/p2", "query", "fragment");
        testHierarchical("file", null, "/p1/p2", null, null);
        testHierarchical("content", "contact", "/p1/p2", null, null);
        testHierarchical("http", "google.com", "/p1/p2", null, "fragment");
        testHierarchical("http", "google.com", "", null, "fragment");
        testHierarchical("http", "google.com", "", "query", "fragment");
        testHierarchical("http", "google.com", "", "query", null);
        testHierarchical("http", null, "/", "query", null);
    }

    private static void testHierarchical(String scheme, String authority,
            String path, String query, String fragment) {
        StringBuilder sb = new StringBuilder();

        if (authority != null) {
            sb.append("//").append(authority);
        }
        if (path != null) {
            sb.append(path);
        }
        if (query != null) {
            sb.append('?').append(query);
        }

        String ssp = sb.toString();

        if (scheme != null) {
            sb.insert(0, scheme + ":");
        }
        if (fragment != null) {
            sb.append('#').append(fragment);
        }

        String uriString = sb.toString();

        Uri uri = Uri.parse(uriString);

        // Run these twice to test caching.
        compareHierarchical(
                uriString, ssp, uri, scheme, authority, path, query, fragment);
        compareHierarchical(
                uriString, ssp, uri, scheme, authority, path, query, fragment);

        // Test rebuilt version.
        uri = uri.buildUpon().build();

        // Run these twice to test caching.
        compareHierarchical(
                uriString, ssp, uri, scheme, authority, path, query, fragment);
        compareHierarchical(
                uriString, ssp, uri, scheme, authority, path, query, fragment);

        // The decoded and encoded versions of the inputs are all the same.
        // We'll test the actual encoding decoding separately.

        // Test building with encoded versions.
        Uri built = new Uri.Builder()
                .scheme(scheme)
                .encodedAuthority(authority)
                .encodedPath(path)
                .encodedQuery(query)
                .encodedFragment(fragment)
                .build();

        compareHierarchical(
                uriString, ssp, built, scheme, authority, path, query, fragment);
        compareHierarchical(
                uriString, ssp, built, scheme, authority, path, query, fragment);

        // Test building with decoded versions.
        built = new Uri.Builder()
                .scheme(scheme)
                .authority(authority)
                .path(path)
                .query(query)
                .fragment(fragment)
                .build();

        compareHierarchical(
                uriString, ssp, built, scheme, authority, path, query, fragment);
        compareHierarchical(
                uriString, ssp, built, scheme, authority, path, query, fragment);

        // Rebuild.
        built = built.buildUpon().build();

        compareHierarchical(
                uriString, ssp, built, scheme, authority, path, query, fragment);
        compareHierarchical(
                uriString, ssp, built, scheme, authority, path, query, fragment);
    }

    private static void compareHierarchical(String uriString, String ssp,
            Uri uri,
            String scheme, String authority, String path, String query,
            String fragment) {
        assertEquals(scheme, uri.getScheme());
        assertEquals(authority, uri.getAuthority());
        assertEquals(authority, uri.getEncodedAuthority());
        assertEquals(path, uri.getPath());
        assertEquals(path, uri.getEncodedPath());
        assertEquals(query, uri.getQuery());
        assertEquals(query, uri.getEncodedQuery());
        assertEquals(fragment, uri.getFragment());
        assertEquals(fragment, uri.getEncodedFragment());
        assertEquals(ssp, uri.getSchemeSpecificPart());

        if (scheme != null) {
            assertTrue(uri.isAbsolute());
            assertFalse(uri.isRelative());
        } else {
            assertFalse(uri.isAbsolute());
            assertTrue(uri.isRelative());
        }

        assertFalse(uri.isOpaque());
        assertTrue(uri.isHierarchical());

        assertEquals(uriString, uri.toString());
    }

    public void testEmptyToStringNotNull() {
        assertNotNull(Uri.EMPTY.toString());
    }

    @SmallTest
    public void testParcellingWithoutFragment() {
        parcelAndUnparcel(Uri.parse("foo:bob%20lee"));
        parcelAndUnparcel(Uri.fromParts("foo", "bob lee", "fragment"));
        parcelAndUnparcel(new Uri.Builder()
            .scheme("http")
            .authority("crazybob.org")
            .path("/rss/")
            .encodedQuery("a=b")
            .build());
    }

    public void testGetQueryParameter() {
        String nestedUrl = "http://crazybob.org/?a=1&b=2";
        Uri uri = Uri.parse("http://test/").buildUpon()
                .appendQueryParameter("foo", "bar")
                .appendQueryParameter("nested", nestedUrl).build();
        assertEquals(nestedUrl, uri.getQueryParameter("nested"));
        assertEquals(nestedUrl, uri.getQueryParameters("nested").get(0));
    }

    public void testGetQueryParameterWorkaround() {
        // This was a workaround for a bug where getQueryParameter called
        // getQuery() instead of getEncodedQuery().
        String nestedUrl = "http://crazybob.org/?a=1&b=2";
        Uri uri = Uri.parse("http://test/").buildUpon()
                .appendQueryParameter("foo", "bar")
                .appendQueryParameter("nested", Uri.encode(nestedUrl)).build();
        assertEquals(nestedUrl, Uri.decode(uri.getQueryParameter("nested")));
        assertEquals(nestedUrl,
                Uri.decode(uri.getQueryParameters("nested").get(0)));
    }

    public void testGetQueryParameterEdgeCases() {
        Uri uri;

        // key at beginning of URL
        uri = Uri.parse("http://test/").buildUpon()
            .appendQueryParameter("key", "a b")
            .appendQueryParameter("keya", "c d")
            .appendQueryParameter("bkey", "e f")
            .build();
        assertEquals("a b", uri.getQueryParameter("key"));

        // key in middle of URL
        uri = Uri.parse("http://test/").buildUpon()
            .appendQueryParameter("akeyb", "a b")
            .appendQueryParameter("keya", "c d")
            .appendQueryParameter("key", "e f")
            .appendQueryParameter("bkey", "g h")
            .build();
        assertEquals("e f", uri.getQueryParameter("key"));

        // key at end of URL
        uri = Uri.parse("http://test/").buildUpon()
            .appendQueryParameter("akeyb", "a b")
            .appendQueryParameter("keya", "c d")
            .appendQueryParameter("key", "y z")
            .build();
        assertEquals("y z", uri.getQueryParameter("key"));

        // key is a substring of parameters, but not present
        uri = Uri.parse("http://test/").buildUpon()
            .appendQueryParameter("akeyb", "a b")
            .appendQueryParameter("keya", "c d")
            .appendQueryParameter("bkey", "e f")
            .build();
        assertNull(uri.getQueryParameter("key"));

        // key is a prefix or suffix of the query
        uri = Uri.parse("http://test/?qq=foo");
        assertNull(uri.getQueryParameter("q"));
        assertNull(uri.getQueryParameter("oo"));

        // escaped keys
        uri = Uri.parse("http://www.google.com/?a%20b=foo&c%20d=");
        assertEquals("foo", uri.getQueryParameter("a b"));
        assertEquals("", uri.getQueryParameter("c d"));
        assertNull(uri.getQueryParameter("e f"));
        assertNull(uri.getQueryParameter("b"));
        assertNull(uri.getQueryParameter("c"));
        assertNull(uri.getQueryParameter(" d"));

        // empty values
        uri = Uri.parse("http://www.google.com/?a=&b=&&c=");
        assertEquals("", uri.getQueryParameter("a"));
        assertEquals("", uri.getQueryParameter("b"));
        assertEquals("", uri.getQueryParameter("c"));
    }

    public void testGetQueryParameterEmptyKey() {
        Uri uri = Uri.parse("http://www.google.com/?=b");
        assertEquals("b", uri.getQueryParameter(""));
    }

    public void testGetQueryParameterEmptyKey2() {
      Uri uri = Uri.parse("http://www.google.com/?a=b&&c=d");
      assertEquals("", uri.getQueryParameter(""));
    }

    public void testGetQueryParameterEmptyKey3() {
      Uri uri = Uri.parse("http://www.google.com?");
      assertEquals("", uri.getQueryParameter(""));
    }

    public void testGetQueryParameterEmptyKey4() {
      Uri uri = Uri.parse("http://www.google.com?a=b&");
      assertEquals("", uri.getQueryParameter(""));
    }

    public void testGetQueryParametersEmptyKey() {
        Uri uri = Uri.parse("http://www.google.com/?=b&");
        List<String> values = uri.getQueryParameters("");
        assertEquals(2, values.size());
        assertEquals("b", values.get(0));
        assertEquals("", values.get(1));
    }

    public void testGetQueryParametersEmptyKey2() {
        Uri uri = Uri.parse("http://www.google.com?");
        List<String> values = uri.getQueryParameters("");
        assertEquals(1, values.size());
        assertEquals("", values.get(0));
    }

    public void testGetQueryParametersEmptyKey3() {
      Uri uri = Uri.parse("http://www.google.com/?a=b&&c=d");
      List<String> values = uri.getQueryParameters("");
      assertEquals(1, values.size());
      assertEquals("", values.get(0));
    }

    public void testGetQueryParameterNames() {
        Uri uri = Uri.parse("http://test?a=1");
        Set<String> names = uri.getQueryParameterNames();
        assertEquals(1, names.size());
        assertEquals("a", names.iterator().next());
    }

    public void testGetQueryParameterNamesEmptyKey() {
        Uri uri = Uri.parse("http://www.google.com/?a=x&&c=z");
        Set<String> names = uri.getQueryParameterNames();
        Iterator<String> iter = names.iterator();
        assertEquals(3, names.size());
        assertEquals("a", iter.next());
        assertEquals("", iter.next());
        assertEquals("c", iter.next());
    }

    public void testGetQueryParameterNamesEmptyKey2() {
        Uri uri = Uri.parse("http://www.google.com/?a=x&=d&c=z");
        Set<String> names = uri.getQueryParameterNames();
        Iterator<String> iter = names.iterator();
        assertEquals(3, names.size());
        assertEquals("a", iter.next());
        assertEquals("", iter.next());
        assertEquals("c", iter.next());
    }

    public void testGetQueryParameterNamesEmptyValues() {
        Uri uri = Uri.parse("http://www.google.com/?a=foo&b=&c=");
        Set<String> names = uri.getQueryParameterNames();
        Iterator<String> iter = names.iterator();
        assertEquals(3, names.size());
        assertEquals("a", iter.next());
        assertEquals("b", iter.next());
        assertEquals("c", iter.next());
    }

    public void testGetQueryParameterNamesEdgeCases() {
        Uri uri = Uri.parse("http://foo?a=bar&b=bar&c=&&d=baz&e&f&g=buzz&&&a&b=bar&h");
        Set<String> names = uri.getQueryParameterNames();
        Iterator<String> iter = names.iterator();
        assertEquals(9, names.size());
        assertEquals("a", iter.next());
        assertEquals("b", iter.next());
        assertEquals("c", iter.next());
        assertEquals("", iter.next());
        assertEquals("d", iter.next());
        assertEquals("e", iter.next());
        assertEquals("f", iter.next());
        assertEquals("g", iter.next());
        assertEquals("h", iter.next());
    }

    public void testGetQueryParameterNamesEscapedKeys() {
        Uri uri = Uri.parse("http://www.google.com/?a%20b=foo&c%20d=");
        Set<String> names = uri.getQueryParameterNames();
        assertEquals(2, names.size());
        Iterator<String> iter = names.iterator();
        assertEquals("a b", iter.next());
        assertEquals("c d", iter.next());
    }

    public void testGetQueryParameterEscapedKeys() {
        Uri uri = Uri.parse("http://www.google.com/?a%20b=foo&c%20d=");
        String value = uri.getQueryParameter("a b");
        assertEquals("foo", value);
    }

    public void testClearQueryParameters() {
        Uri uri = Uri.parse("http://www.google.com/?a=x&b=y&c=z").buildUpon()
            .clearQuery().appendQueryParameter("foo", "bar").build();
        Set<String> names = uri.getQueryParameterNames();
        assertEquals(1, names.size());
        assertEquals("foo", names.iterator().next());
    }

    /**
     * Query parameters may omit the '='. http://b/3124097
     */
    public void testGetQueryParametersEmptyValue() {
        assertEquals(Arrays.asList(""),
                Uri.parse("http://foo/path?abc").getQueryParameters("abc"));
        assertEquals(Arrays.asList(""),
                Uri.parse("http://foo/path?foo=bar&abc").getQueryParameters("abc"));
        assertEquals(Arrays.asList(""),
                Uri.parse("http://foo/path?abcd=abc&abc").getQueryParameters("abc"));
        assertEquals(Arrays.asList("a", "", ""),
                Uri.parse("http://foo/path?abc=a&abc=&abc").getQueryParameters("abc"));
        assertEquals(Arrays.asList("a", "", ""),
                Uri.parse("http://foo/path?abc=a&abc=&abc=").getQueryParameters("abc"));
    }

    // http://code.google.com/p/android/issues/detail?id=21064
    public void testPlusCharacterInQuery() {
        assertEquals("d e", Uri.parse("http://a/b?c=d%20e").getQueryParameter("c"));
        assertEquals("d e", Uri.parse("http://a/b?c=d+e").getQueryParameter("c"));
    }

    public void testPathPrefixMatch() {
        // Exact match
        assertTrue(Uri.parse("content://com.example/path").isPathPrefixMatch(
                Uri.parse("content://com.example/path/")));
        assertTrue(Uri.parse("content://com.example/path").isPathPrefixMatch(
                Uri.parse("content://com.example/path")));
        assertTrue(Uri.parse("content://com.example///path///").isPathPrefixMatch(
                Uri.parse("content://com.example/path/")));
        assertTrue(Uri.parse("content://com.example/path").isPathPrefixMatch(
                Uri.parse("content://com.example///path///")));

        // Child match
        assertTrue(Uri.parse("content://com.example/path/to/child").isPathPrefixMatch(
                Uri.parse("content://com.example/path/")));
        assertTrue(Uri.parse("content://com.example/path/to/child").isPathPrefixMatch(
                Uri.parse("content://com.example/path")));

        // Extra parameters
        assertTrue(Uri.parse("content://com.example/path#fragment").isPathPrefixMatch(
                Uri.parse("content://com.example/path/")));
        assertTrue(Uri.parse("content://com.example/path?q=v").isPathPrefixMatch(
                Uri.parse("content://com.example/path/")));
        assertTrue(Uri.parse("content://com.example/path/?q=v").isPathPrefixMatch(
                Uri.parse("content://com.example/path/")));

        // Different path
        assertFalse(Uri.parse("content://com.example/path").isPathPrefixMatch(
                Uri.parse("content://com.example/path/deeper/")));
        assertFalse(Uri.parse("content://com.example/path2").isPathPrefixMatch(
                Uri.parse("content://com.example/path")));

        // Top-level match
        assertTrue(Uri.parse("content://com.example/path/").isPathPrefixMatch(
                Uri.parse("content://com.example/")));
        assertTrue(Uri.parse("content://com.example/path/").isPathPrefixMatch(
                Uri.parse("content://com.example")));

        // Different prefixes
        assertFalse(Uri.parse("content://com.example/path/").isPathPrefixMatch(
                Uri.parse("file://com.example/path/")));
        assertFalse(Uri.parse("content://com.example/path/").isPathPrefixMatch(
                Uri.parse("content://org.example/path/")));

        // Escaping
        assertTrue(Uri.parse("content://com.example/path path/").isPathPrefixMatch(
                Uri.parse("content://com.example/path%20path/")));
        assertFalse(Uri.parse("content://com.example/path/path").isPathPrefixMatch(
                Uri.parse("content://com.example/path%2Fpath")));
    }


    /**
     * Check that calling Part(String, String) with inconsistent Strings does not lead
     * to the Part's encoded vs. decoded values being inconsistent.
     */
    public void testPart_consistentEncodedVsDecoded() throws Exception {
        Object authority = createPart(Class.forName("android.net.Uri$Part"), "a.com", "b.com");
        Object path = createPart(Class.forName("android.net.Uri$PathPart"), "/foo/a", "/foo/b");
        Uri uri = makeHierarchicalUri(authority, path);
        // In these cases, decoding/encoding the encoded/decoded representation yields the same
        // String, so we can just assert equality.
        // assertEquals(uri.getPath(), uri.getEncodedPath());
        assertEquals(uri.getAuthority(), uri.getEncodedAuthority());

        // When both encoded and decoded strings are given, the encoded one is preferred.
        assertEquals("a.com", uri.getAuthority());
        assertEquals("/foo/a", uri.getPath());
    }

    private Object createPart(Class partClass, String encoded, String decoded) throws Exception {
        Constructor partConstructor = partClass.getDeclaredConstructor(String.class, String.class);
        partConstructor.setAccessible(true);
        return partConstructor.newInstance(encoded, decoded);
    }

    private static Uri makeHierarchicalUri(Object authority, Object path) throws Exception {
        Class hierarchicalUriClass = Class.forName("android.net.Uri$HierarchicalUri");
        Constructor hierarchicalUriConstructor = hierarchicalUriClass.getDeclaredConstructors()[0];
        hierarchicalUriConstructor.setAccessible(true);
        return (Uri) hierarchicalUriConstructor.newInstance("https", authority, path, null, null);
    }

    /** Attempting to unparcel a legacy parcel format of Uri.{,Path}Part should fail. */
    public void testUnparcelLegacyPart_fails() throws Exception {
        assertUnparcelLegacyPart_fails(Class.forName("android.net.Uri$Part"));
        assertUnparcelLegacyPart_fails(Class.forName("android.net.Uri$PathPart"));
    }

    private static void assertUnparcelLegacyPart_fails(Class partClass) throws Exception {
        Parcel parcel = Parcel.obtain();
        parcel.writeInt(0 /* BOTH */);
        parcel.writeString("encoded");
        parcel.writeString("decoded");
        parcel.setDataPosition(0);

        Method readFromMethod = partClass.getDeclaredMethod("readFrom", Parcel.class);
        readFromMethod.setAccessible(true);
        try {
            readFromMethod.invoke(null, parcel);
            fail();
        } catch (InvocationTargetException expected) {
            Throwable targetException = expected.getTargetException();
            // Check that the exception was thrown for the correct reason.
            assertEquals("Unknown representation: 0", targetException.getMessage());
        } finally {
            parcel.recycle();
        }
    }

    private Uri buildUriFromRawParcel(boolean argumentsEncoded,
                                      String scheme,
                                      String authority,
                                      String path,
                                      String query,
                                      String fragment) {
        // Representation value (from AbstractPart.REPRESENTATION_{ENCODED,DECODED}).
        final int representation = argumentsEncoded ? 1 : 2;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(3);  // hierarchical
            parcel.writeString8(scheme);
            parcel.writeInt(representation);
            parcel.writeString8(authority);
            parcel.writeInt(representation);
            parcel.writeString8(path);
            parcel.writeInt(representation);
            parcel.writeString8(query);
            parcel.writeInt(representation);
            parcel.writeString8(fragment);
            parcel.setDataPosition(0);
            return Uri.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    public void testUnparcelMalformedPath() {
        // Regression tests for b/171966843.

        // Test cases with arguments encoded (covering testing `scheme` * `authority` options).
        Uri uri0 = buildUriFromRawParcel(true, "https", "google.com", "@evil.com", null, null);
        assertEquals("https://google.com/@evil.com", uri0.toString());
        Uri uri1 = buildUriFromRawParcel(true, null, "google.com", "@evil.com", "name=spark", "x");
        assertEquals("//google.com/@evil.com?name=spark#x", uri1.toString());
        Uri uri2 = buildUriFromRawParcel(true, "http:", null, "@evil.com", null, null);
        assertEquals("http::/@evil.com", uri2.toString());
        Uri uri3 = buildUriFromRawParcel(true, null, null, "@evil.com", null, null);
        assertEquals("@evil.com", uri3.toString());

        // Test cases with arguments not encoded (covering testing `scheme` * `authority` options).
        Uri uriA = buildUriFromRawParcel(false, "https", "google.com", "@evil.com", null, null);
        assertEquals("https://google.com/%40evil.com", uriA.toString());
        Uri uriB = buildUriFromRawParcel(false, null, "google.com", "@evil.com", null, null);
        assertEquals("//google.com/%40evil.com", uriB.toString());
        Uri uriC = buildUriFromRawParcel(false, "http:", null, "@evil.com", null, null);
        assertEquals("http::/%40evil.com", uriC.toString());
        Uri uriD = buildUriFromRawParcel(false, null, null, "@evil.com", "name=spark", "y");
        assertEquals("%40evil.com?name%3Dspark#y", uriD.toString());
    }

    public void testToSafeString() {
        checkToSafeString("tel:xxxxxx", "tel:Google");
        checkToSafeString("tel:xxxxxxxxxx", "tel:1234567890");
        checkToSafeString("tEl:xxx.xxx-xxxx", "tEl:123.456-7890");

        checkToSafeString("sms:xxxxxx", "sms:123abc");
        checkToSafeString("smS:xxx.xxx-xxxx", "smS:123.456-7890");

        checkToSafeString("smsto:xxxxxx", "smsto:123abc");
        checkToSafeString("SMSTo:xxx.xxx-xxxx", "SMSTo:123.456-7890");

        checkToSafeString("mailto:xxxxxxx@xxxxxxx.xxx", "mailto:android@android.com");
        checkToSafeString("Mailto:xxxxxxx@xxxxxxx.xxxxxxxxxx",
                "Mailto:android@android.com/secret");

        checkToSafeString("sip:xxxxxxx@xxxxxxx.xxxxxxxx", "sip:android@android.com:1234");
        checkToSafeString("sIp:xxxxxxx@xxxxxxx.xxx", "sIp:android@android.com");

        checkToSafeString("http://www.android.com/...", "http://www.android.com");
        checkToSafeString("HTTP://www.android.com/...", "HTTP://www.android.com");
        checkToSafeString("http://www.android.com/...", "http://www.android.com/");
        checkToSafeString("http://www.android.com/...", "http://www.android.com/secretUrl?param");
        checkToSafeString("http://www.android.com/...",
                "http://user:pwd@www.android.com/secretUrl?param");
        checkToSafeString("http://www.android.com/...",
                "http://user@www.android.com/secretUrl?param");
        checkToSafeString("http://www.android.com/...", "http://www.android.com/secretUrl?param");
        checkToSafeString("http:///...", "http:///path?param");
        checkToSafeString("http:///...", "http://");
        checkToSafeString("http://:12345/...", "http://:12345/");

        checkToSafeString("https://www.android.com/...", "https://www.android.com/secretUrl?param");
        checkToSafeString("https://www.android.com:8443/...",
                "https://user:pwd@www.android.com:8443/secretUrl?param");
        checkToSafeString("https://www.android.com/...", "https://user:pwd@www.android.com");
        checkToSafeString("Https://www.android.com/...", "Https://user:pwd@www.android.com");

        checkToSafeString("ftp://ftp.android.com/...", "ftp://ftp.android.com/");
        checkToSafeString("ftP://ftp.android.com/...", "ftP://anonymous@ftp.android.com/");
        checkToSafeString("ftp://ftp.android.com:2121/...",
                "ftp://root:love@ftp.android.com:2121/");

        checkToSafeString("unsupported://ajkakjah/...",
                "unsupported://ajkakjah/askdha/secret?secret");
        checkToSafeString("unsupported:",
                "unsupported:ajkakjah/askdha/secret?secret");
        checkToSafeString("unsupported:/...",
                "unsupported:/ajkakjah/askdha/secret?secret");
        checkToSafeString("file:///...",
                "file:///path/to/secret.doc");
    }

    private void checkToSafeString(String expectedSafeString, String original) {
        assertEquals(expectedSafeString, Uri.parse(original).toSafeString());
    }
}
