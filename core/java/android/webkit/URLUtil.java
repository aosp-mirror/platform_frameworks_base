/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.webkit;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.net.ParseException;
import android.net.Uri;
import android.net.WebAddress;
import android.os.Build;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class URLUtil {

    /**
     * This feature enables parsing of Content-Disposition headers that conform to RFC 6266. In
     * particular, this enables parsing of {@code filename*} values which can use a different
     * character encoding.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    static final long PARSE_CONTENT_DISPOSITION_USING_RFC_6266 = 319400769L;

    private static final String LOGTAG = "webkit";
    private static final boolean TRACE = false;

    // to refer to bar.png under your package's asset/foo/ directory, use
    // "file:///android_asset/foo/bar.png".
    static final String ASSET_BASE = "file:///android_asset/";
    // to refer to bar.png under your package's res/drawable/ directory, use
    // "file:///android_res/drawable/bar.png". Use "drawable" to refer to
    // "drawable-hdpi" directory as well.
    static final String RESOURCE_BASE = "file:///android_res/";
    static final String FILE_BASE = "file:";
    static final String PROXY_BASE = "file:///cookieless_proxy/";
    static final String CONTENT_BASE = "content:";

    /** Cleans up (if possible) user-entered web addresses */
    public static String guessUrl(String inUrl) {

        String retVal = inUrl;
        WebAddress webAddress;

        if (TRACE) Log.v(LOGTAG, "guessURL before queueRequest: " + inUrl);

        if (inUrl.length() == 0) return inUrl;
        if (inUrl.startsWith("about:")) return inUrl;
        // Do not try to interpret data scheme URLs
        if (inUrl.startsWith("data:")) return inUrl;
        // Do not try to interpret file scheme URLs
        if (inUrl.startsWith("file:")) return inUrl;
        // Do not try to interpret javascript scheme URLs
        if (inUrl.startsWith("javascript:")) return inUrl;

        // bug 762454: strip period off end of url
        if (inUrl.endsWith(".") == true) {
            inUrl = inUrl.substring(0, inUrl.length() - 1);
        }

        try {
            webAddress = new WebAddress(inUrl);
        } catch (ParseException ex) {

            if (TRACE) {
                Log.v(LOGTAG, "smartUrlFilter: failed to parse url = " + inUrl);
            }
            return retVal;
        }

        // Check host
        if (webAddress.getHost().indexOf('.') == -1) {
            // no dot: user probably entered a bare domain.  try .com
            webAddress.setHost("www." + webAddress.getHost() + ".com");
        }
        return webAddress.toString();
    }

    /**
     * Inserts the {@code inQuery} in the {@code template} after URL-encoding it. The encoded query
     * will replace the {@code queryPlaceHolder}.
     */
    public static String composeSearchUrl(
            String inQuery, String template, String queryPlaceHolder) {
        int placeHolderIndex = template.indexOf(queryPlaceHolder);
        if (placeHolderIndex < 0) {
            return null;
        }

        String query;
        StringBuilder buffer = new StringBuilder();
        buffer.append(template.substring(0, placeHolderIndex));

        try {
            query = java.net.URLEncoder.encode(inQuery, "utf-8");
            buffer.append(query);
        } catch (UnsupportedEncodingException ex) {
            return null;
        }

        buffer.append(template.substring(placeHolderIndex + queryPlaceHolder.length()));

        return buffer.toString();
    }

    public static byte[] decode(byte[] url) throws IllegalArgumentException {
        if (url.length == 0) {
            return new byte[0];
        }

        // Create a new byte array with the same length to ensure capacity
        byte[] tempData = new byte[url.length];

        int tempCount = 0;
        for (int i = 0; i < url.length; i++) {
            byte b = url[i];
            if (b == '%') {
                if (url.length - i > 2) {
                    b = (byte) (parseHex(url[i + 1]) * 16 + parseHex(url[i + 2]));
                    i += 2;
                } else {
                    throw new IllegalArgumentException("Invalid format");
                }
            }
            tempData[tempCount++] = b;
        }
        byte[] retData = new byte[tempCount];
        System.arraycopy(tempData, 0, retData, 0, tempCount);
        return retData;
    }

    /**
     * @return {@code true} if the url is correctly URL encoded
     */
    @UnsupportedAppUsage
    static boolean verifyURLEncoding(String url) {
        int count = url.length();
        if (count == 0) {
            return false;
        }

        int index = url.indexOf('%');
        while (index >= 0 && index < count) {
            if (index < count - 2) {
                try {
                    parseHex((byte) url.charAt(++index));
                    parseHex((byte) url.charAt(++index));
                } catch (IllegalArgumentException e) {
                    return false;
                }
            } else {
                return false;
            }
            index = url.indexOf('%', index + 1);
        }
        return true;
    }

    private static int parseHex(byte b) {
        if (b >= '0' && b <= '9') return (b - '0');
        if (b >= 'A' && b <= 'F') return (b - 'A' + 10);
        if (b >= 'a' && b <= 'f') return (b - 'a' + 10);

        throw new IllegalArgumentException("Invalid hex char '" + b + "'");
    }

    /**
     * @return {@code true} if the url is an asset file.
     */
    public static boolean isAssetUrl(String url) {
        return (null != url) && url.startsWith(ASSET_BASE);
    }

    /**
     * @return {@code true} if the url is a resource file.
     * @hide
     */
    @UnsupportedAppUsage
    public static boolean isResourceUrl(String url) {
        return (null != url) && url.startsWith(RESOURCE_BASE);
    }

    /**
     * @return {@code true} if the url is a proxy url to allow cookieless network requests from a
     *     file url.
     * @deprecated Cookieless proxy is no longer supported.
     */
    @Deprecated
    public static boolean isCookielessProxyUrl(String url) {
        return (null != url) && url.startsWith(PROXY_BASE);
    }

    /**
     * @return {@code true} if the url is a local file.
     */
    public static boolean isFileUrl(String url) {
        return (null != url)
                && (url.startsWith(FILE_BASE)
                        && !url.startsWith(ASSET_BASE)
                        && !url.startsWith(PROXY_BASE));
    }

    /**
     * @return {@code true} if the url is an about: url.
     */
    public static boolean isAboutUrl(String url) {
        return (null != url) && url.startsWith("about:");
    }

    /**
     * @return {@code true} if the url is a data: url.
     */
    public static boolean isDataUrl(String url) {
        return (null != url) && url.startsWith("data:");
    }

    /**
     * @return {@code true} if the url is a javascript: url.
     */
    public static boolean isJavaScriptUrl(String url) {
        return (null != url) && url.startsWith("javascript:");
    }

    /**
     * @return {@code true} if the url is an http: url.
     */
    public static boolean isHttpUrl(String url) {
        return (null != url)
                && (url.length() > 6)
                && url.substring(0, 7).equalsIgnoreCase("http://");
    }

    /**
     * @return {@code true} if the url is an https: url.
     */
    public static boolean isHttpsUrl(String url) {
        return (null != url)
                && (url.length() > 7)
                && url.substring(0, 8).equalsIgnoreCase("https://");
    }

    /**
     * @return {@code true} if the url is a network url.
     */
    public static boolean isNetworkUrl(String url) {
        if (url == null || url.length() == 0) {
            return false;
        }
        return isHttpUrl(url) || isHttpsUrl(url);
    }

    /**
     * @return {@code true} if the url is a content: url.
     */
    public static boolean isContentUrl(String url) {
        return (null != url) && url.startsWith(CONTENT_BASE);
    }

    /**
     * @return {@code true} if the url is valid.
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.length() == 0) {
            return false;
        }

        return (isAssetUrl(url)
                || isResourceUrl(url)
                || isFileUrl(url)
                || isAboutUrl(url)
                || isHttpUrl(url)
                || isHttpsUrl(url)
                || isJavaScriptUrl(url)
                || isContentUrl(url));
    }

    /** Strips the url of the anchor. */
    public static String stripAnchor(String url) {
        int anchorIndex = url.indexOf('#');
        if (anchorIndex != -1) {
            return url.substring(0, anchorIndex);
        }
        return url;
    }

    /**
     * Guesses canonical filename that a download would have, using the URL and contentDisposition.
     *
     * <p>File extension, if not defined, is added based on the mimetype.
     *
     * <p>The {@code contentDisposition} argument will be treated differently depending on
     * targetSdkVersion.
     *
     * <ul>
     *   <li>For targetSDK versions &lt; {@code VANILLA_ICE_CREAM} it will be parsed based on RFC
     *       2616.
     *   <li>For targetSDK versions &gt;= {@code VANILLA_ICE_CREAM} it will be parsed based on RFC
     *       6266.
     * </ul>
     *
     * In practice, this means that from {@code VANILLA_ICE_CREAM}, this method will be able to
     * parse {@code filename*} directives in the {@code contentDisposition} string.
     *
     * <p>The function also changed in the following ways in {@code VANILLA_ICE_CREAM}:
     *
     * <ul>
     *   <li>If the suggested file type extension doesn't match the passed {@code mimeType}, the
     *       method will append the appropriate extension instead of replacing the current
     *       extension.
     *   <li>If the suggested file name contains a path separator ({@code "/"}), the method will
     *       replace this with the underscore character ({@code "_"}) instead of splitting the
     *       result and only using the last part.
     * </ul>
     *
     * @param url Url to the content
     * @param contentDisposition Content-Disposition HTTP header or {@code null}
     * @param mimeType Mime-type of the content or {@code null}
     * @return suggested filename
     */
    public static String guessFileName(
            String url, @Nullable String contentDisposition, @Nullable String mimeType) {
        if (android.os.Flags.androidOsBuildVanillaIceCream()) {
            if (Compatibility.isChangeEnabled(PARSE_CONTENT_DISPOSITION_USING_RFC_6266)) {
                return guessFileNameRfc6266(url, contentDisposition, mimeType);
            }
        }

        return guessFileNameRfc2616(url, contentDisposition, mimeType);
    }

    /** Legacy implementation of guessFileName, based on RFC 2616. */
    private static String guessFileNameRfc2616(
            String url, @Nullable String contentDisposition, @Nullable String mimeType) {
        String filename = null;
        String extension = null;

        // If we couldn't do anything with the hint, move toward the content disposition
        if (contentDisposition != null) {
            filename = parseContentDispositionRfc2616(contentDisposition);
            if (filename != null) {
                int index = filename.lastIndexOf('/') + 1;
                if (index > 0) {
                    filename = filename.substring(index);
                }
            }
        }

        // If all the other http-related approaches failed, use the plain uri
        if (filename == null) {
            String decodedUrl = Uri.decode(url);
            if (decodedUrl != null) {
                int queryIndex = decodedUrl.indexOf('?');
                // If there is a query string strip it, same as desktop browsers
                if (queryIndex > 0) {
                    decodedUrl = decodedUrl.substring(0, queryIndex);
                }
                if (!decodedUrl.endsWith("/")) {
                    int index = decodedUrl.lastIndexOf('/') + 1;
                    if (index > 0) {
                        filename = decodedUrl.substring(index);
                    }
                }
            }
        }

        // Finally, if couldn't get filename from URI, get a generic filename
        if (filename == null) {
            filename = "downloadfile";
        }

        // Split filename between base and extension
        // Add an extension if filename does not have one
        int dotIndex = filename.indexOf('.');
        if (dotIndex < 0) {
            if (mimeType != null) {
                extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                if (extension != null) {
                    extension = "." + extension;
                }
            }
            if (extension == null) {
                if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("text/")) {
                    if (mimeType.equalsIgnoreCase("text/html")) {
                        extension = ".html";
                    } else {
                        extension = ".txt";
                    }
                } else {
                    extension = ".bin";
                }
            }
        } else {
            if (mimeType != null) {
                // Compare the last segment of the extension against the mime type.
                // If there's a mismatch, discard the entire extension.
                int lastDotIndex = filename.lastIndexOf('.');
                String typeFromExt =
                        MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(filename.substring(lastDotIndex + 1));
                if (typeFromExt != null && !typeFromExt.equalsIgnoreCase(mimeType)) {
                    extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                    if (extension != null) {
                        extension = "." + extension;
                    }
                }
            }
            if (extension == null) {
                extension = filename.substring(dotIndex);
            }
            filename = filename.substring(0, dotIndex);
        }

        return filename + extension;
    }

    /**
     * Guesses canonical filename that a download would have, using the URL and contentDisposition.
     * Uses RFC 6266 for parsing the contentDisposition header value.
     */
    @NonNull
    private static String guessFileNameRfc6266(
            @NonNull String url, @Nullable String contentDisposition, @Nullable String mimeType) {
        String filename = getFilenameSuggestion(url, contentDisposition);
        // Split filename between base and extension
        // Add an extension if filename does not have one
        String extensionFromMimeType = suggestExtensionFromMimeType(mimeType);

        if (filename.indexOf('.') < 0) {
            // Filename does not have an extension, use the suggested one.
            return filename + extensionFromMimeType;
        }

        // Filename already contains at least one dot.
        // Compare the last segment of the extension against the mime type.
        // If there's a mismatch, add the suggested extension instead.
        if (mimeType != null && extensionDifferentFromMimeType(filename, mimeType)) {
            return filename + extensionFromMimeType;
        }
        return filename;
    }

    /**
     * Get the suggested file name from the {@code contentDisposition} or {@code url}. Will ensure
     * that the filename contains no path separators by replacing them with the {@code "_"}
     * character.
     */
    @NonNull
    private static String getFilenameSuggestion(String url, @Nullable String contentDisposition) {
        // First attempt to parse the Content-Disposition header if available
        if (contentDisposition != null) {
            String filename = getFilenameFromContentDispositionRfc6266(contentDisposition);
            if (filename != null) {
                return replacePathSeparators(filename);
            }
        }

        // Try to generate a filename based on the URL.
        if (url != null) {
            Uri parsedUri = Uri.parse(url);
            String lastPathSegment = parsedUri.getLastPathSegment();
            if (lastPathSegment != null) {
                return replacePathSeparators(lastPathSegment);
            }
        }

        // Finally, if couldn't get filename from URI, get a generic filename.
        return "downloadfile";
    }

    /**
     * Replace all instances of {@code "/"} with {@code "_"} to avoid filenames that navigate the
     * path.
     */
    @NonNull
    private static String replacePathSeparators(@NonNull String raw) {
        return raw.replaceAll("/", "_");
    }

    /**
     * Check if the {@code filename} has an extension that is different from the expected one based
     * on the {@code mimeType}.
     */
    private static boolean extensionDifferentFromMimeType(
            @NonNull String filename, @NonNull String mimeType) {
        int lastDotIndex = filename.lastIndexOf('.');
        String typeFromExt =
                MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(filename.substring(lastDotIndex + 1));
        return typeFromExt != null && !typeFromExt.equalsIgnoreCase(mimeType);
    }

    /**
     * Get a candidate file extension (including the {@code .}) for the given mimeType. will return
     * {@code ".bin"} if {@code mimeType} is {@code null}
     *
     * @param mimeType Reported mimetype
     * @return A file extension, including the {@code .}
     */
    @NonNull
    private static String suggestExtensionFromMimeType(@Nullable String mimeType) {
        if (mimeType == null) {
            return ".bin";
        }
        String extensionFromMimeType =
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extensionFromMimeType != null) {
            return "." + extensionFromMimeType;
        }
        if (mimeType.equalsIgnoreCase("text/html")) {
            return ".html";
        } else if (mimeType.toLowerCase(Locale.ROOT).startsWith("text/")) {
            return ".txt";
        } else {
            return ".bin";
        }
    }

    /**
     * Parse the Content-Disposition HTTP Header.
     *
     * <p>Behavior depends on targetSdkVersion.
     *
     * <ul>
     *   <li>For targetSDK versions &lt; {@code VANILLA_ICE_CREAM} it will parse based on RFC 2616.
     *   <li>For targetSDK versions &gt;= {@code VANILLA_ICE_CREAM} it will parse based on RFC 6266.
     * </ul>
     */
    @UnsupportedAppUsage
    static String parseContentDisposition(String contentDisposition) {
        if (android.os.Flags.androidOsBuildVanillaIceCream()) {
            if (Compatibility.isChangeEnabled(PARSE_CONTENT_DISPOSITION_USING_RFC_6266)) {
                return getFilenameFromContentDispositionRfc6266(contentDisposition);
            }
        }
        return parseContentDispositionRfc2616(contentDisposition);
    }

    /** Regex used to parse content-disposition headers */
    private static final Pattern CONTENT_DISPOSITION_PATTERN =
            Pattern.compile(
                    "attachment;\\s*filename\\s*=\\s*(\"?)([^\"]*)\\1\\s*$",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Parse the Content-Disposition HTTP Header. The format of the header is defined here: <a
     * href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec19.html">rfc2616 Section 19</a>. This
     * header provides a filename for content that is going to be downloaded to the file system. We
     * only support the attachment type. Note that RFC 2616 specifies the filename value must be
     * double-quoted. Unfortunately some servers do not quote the value so to maintain consistent
     * behaviour with other browsers, we allow unquoted values too.
     */
    private static String parseContentDispositionRfc2616(String contentDisposition) {
        try {
            Matcher m = CONTENT_DISPOSITION_PATTERN.matcher(contentDisposition);
            if (m.find()) {
                return m.group(2);
            }
        } catch (IllegalStateException ex) {
            // This function is defined as returning null when it can't parse the header
        }
        return null;
    }

    /**
     * Pattern for parsing individual content disposition key-value pairs.
     *
     * <p>The pattern will attempt to parse the value as either single-, double-, or unquoted. For
     * the single- and double-quoted options, the pattern allows escaped quotes as part of the
     * value, as per <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-2.2">rfc2616
     * section-2.2</a>
     */
    @SuppressWarnings("RegExpRepeatedSpace") // Spaces are only for readability.
    private static final Pattern DISPOSITION_PATTERN =
            Pattern.compile(
                    """
                            \\s*(\\S+?) # Group 1: parameter name
                            \\s*=\\s* # Match equals sign
                            (?: # non-capturing group of options
                               '( (?: [^'\\\\] | \\\\. )* )' # Group 2: single-quoted
                             | "( (?: [^"\\\\] | \\\\. )*  )" # Group 3: double-quoted
                             | ( [^'"][^;\\s]* ) # Group 4: un-quoted parameter
                            )\\s*;? # Optional end semicolon""",
                    Pattern.COMMENTS);

    /**
     * Extract filename from a {@code Content-Disposition} header value.
     *
     * <p>This method implements the parsing defined in <a
     * href="https://datatracker.ietf.org/doc/html/rfc6266">RFC 6266</a>, supporting both the {@code
     * filename} and {@code filename*} disposition parameters. If the passed header value has the
     * {@code "inline"} disposition type, this method will return {@code null} to indicate that a
     * download was not intended.
     *
     * <p>If both {@code filename*} and {@code filename} is present, the former will be returned, as
     * per the RFC. Invalid encoded values will be ignored.
     *
     * @param contentDisposition Value of {@code Content-Disposition} header.
     * @return The filename suggested by the header or {@code null} if no filename could be parsed
     *     from the header value.
     */
    @Nullable
    private static String getFilenameFromContentDispositionRfc6266(
            @NonNull String contentDisposition) {
        String[] parts = contentDisposition.trim().split(";", 2);
        if (parts.length < 2) {
            // Need at least 2 parts, the `disposition-type` and at least one `disposition-parm`.
            return null;
        }
        String dispositionType = parts[0].trim();
        if ("inline".equalsIgnoreCase(dispositionType)) {
            // "inline" should not result in a download.
            // Unknown disposition types should be handles as "attachment"
            // https://datatracker.ietf.org/doc/html/rfc6266#section-4.2
            return null;
        }
        String dispositionParameters = parts[1];
        Matcher matcher = DISPOSITION_PATTERN.matcher(dispositionParameters);
        String filename = null;
        String filenameExt = null;
        while (matcher.find()) {
            String parameter = matcher.group(1);
            String value;
            if (matcher.group(2) != null) {
                value = removeSlashEscapes(matcher.group(2)); // Value was single-quoted
            } else if (matcher.group(3) != null) {
                value = removeSlashEscapes(matcher.group(3)); // Value was double-quoted
            } else {
                value = matcher.group(4); // Value was un-quoted
            }

            if (parameter == null || value == null) {
                continue;
            }

            if ("filename*".equalsIgnoreCase(parameter)) {
                filenameExt = parseExtValueString(value);
            } else if ("filename".equalsIgnoreCase(parameter)) {
                filename = value;
            }
        }

        // RFC 6266 dictates the filenameExt should be preferred if present.
        if (filenameExt != null) {
            return filenameExt;
        }
        return filename;
    }

    /** Replace escapes of the \X form with X. */
    private static String removeSlashEscapes(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("\\\\(.)", "$1");
    }

    /**
     * Parse an extended value string which can be percent-encoded. Return {@code} null if unable to
     * parse the string.
     */
    private static String parseExtValueString(String raw) {
        String[] parts = raw.split("'", 3);
        if (parts.length < 3) {
            return null;
        }

        String encoding = parts[0];
        // Intentionally ignore parts[1] (language).
        String valueChars = parts[2];

        try {
            // The URLDecoder force-decodes + as " "
            // so preemptively replace all values with the encoded value to preserve them.
            Charset charset = Charset.forName(encoding);
            String valueWithEncodedPlus = encodePlusCharacters(valueChars, charset);
            return URLDecoder.decode(valueWithEncodedPlus, charset);
        } catch (RuntimeException ignored) {
            return null; // Ignoring an un-parsable value is within spec.
        }
    }

    /**
     * Replace all instances of {@code "+"} with the percent-encoded equivalent for the given {@code
     * charset}.
     */
    @NonNull
    private static String encodePlusCharacters(@NonNull String valueChars, Charset charset) {
        StringBuilder sb = new StringBuilder();
        for (byte b : charset.encode("+").array()) {
            // Formatting a byte is not possible with TextUtils.formatSimple
            sb.append(String.format("%02x", b));
        }
        return valueChars.replaceAll("\\+", sb.toString());
    }
}
