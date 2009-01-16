// Copyright 2007 The Android Open Source Project

package com.google.android.gdata.client;

import com.google.android.net.GoogleHttpClient;
import com.google.wireless.gdata.client.GDataClient;
import com.google.wireless.gdata.client.HttpException;
import com.google.wireless.gdata.client.QueryParams;
import com.google.wireless.gdata.data.StringUtils;
import com.google.wireless.gdata.parser.ParseException;
import com.google.wireless.gdata.serializer.GDataSerializer;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.AbstractHttpEntity;

import android.content.ContentResolver;
import android.net.http.AndroidHttpClient;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.io.BufferedInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;

/**
 * Implementation of a GDataClient using GoogleHttpClient to make HTTP
 * requests.  Always issues GETs and POSTs, using the X-HTTP-Method-Override
 * header when a PUT or DELETE is desired, to avoid issues with firewalls, etc.,
 * that do not allow methods other than GET or POST.
 */
public class AndroidGDataClient implements GDataClient {

    private static final String TAG = "GDataClient";
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = DEBUG ? Config.LOGD : Config.LOGV;

    private static final String X_HTTP_METHOD_OVERRIDE =
        "X-HTTP-Method-Override";

    private static final String USER_AGENT_APP_VERSION = "Android-GData/1.0";

    private static final int MAX_REDIRECTS = 10;

    private final GoogleHttpClient mHttpClient;
    private ContentResolver mResolver;

    /**
     * Interface for creating HTTP requests.  Used by
     * {@link AndroidGDataClient#createAndExecuteMethod}, since HttpUriRequest does not allow for
     * changing the URI after creation, e.g., when you want to follow a redirect.
     */
    private interface HttpRequestCreator {
        HttpUriRequest createRequest(URI uri);
    }

    private static class GetRequestCreator implements HttpRequestCreator {
        public GetRequestCreator() {
        }

        public HttpUriRequest createRequest(URI uri) {
            HttpGet get = new HttpGet(uri);
            return get;
        }
    }

    private static class PostRequestCreator implements HttpRequestCreator {
        private final String mMethodOverride;
        private final HttpEntity mEntity;
        public PostRequestCreator(String methodOverride, HttpEntity entity) {
            mMethodOverride = methodOverride;
            mEntity = entity;
        }

        public HttpUriRequest createRequest(URI uri) {
            HttpPost post = new HttpPost(uri);
            if (mMethodOverride != null) {
                post.addHeader(X_HTTP_METHOD_OVERRIDE, mMethodOverride);
            }
            post.setEntity(mEntity);
            return post;
        }
    }

    // MAJOR TODO: make this work across redirects (if we can reset the InputStream).
    // OR, read the bits into a local buffer (yuck, the media could be large).
    private static class MediaPutRequestCreator implements HttpRequestCreator {
        private final InputStream mMediaInputStream;
        private final String mContentType;
        public MediaPutRequestCreator(InputStream mediaInputStream, String contentType) {
            mMediaInputStream = mediaInputStream;
            mContentType = contentType;
        }

        public HttpUriRequest createRequest(URI uri) {
            HttpPost post = new HttpPost(uri);
            post.addHeader(X_HTTP_METHOD_OVERRIDE, "PUT");
            // mMediaInputStream.reset();
            InputStreamEntity entity = new InputStreamEntity(mMediaInputStream,
                    -1 /* read until EOF */);
            entity.setContentType(mContentType);
            post.setEntity(entity);
            return post;
        }
    }

    /**
     * Creates a new AndroidGDataClient.
     * 
     * @param resolver The ContentResolver to get URL rewriting rules from
     * through the Android proxy server, using null to indicate not using proxy.
     */
    public AndroidGDataClient(ContentResolver resolver) {
        mHttpClient = new GoogleHttpClient(resolver, USER_AGENT_APP_VERSION,
                true /* gzip capable */);
        mHttpClient.enableCurlLogging(TAG, Log.VERBOSE);
        mResolver = resolver;
    }

    public void close() {
        mHttpClient.close();
    }

    /*
     * (non-Javadoc)
     * @see GDataClient#encodeUri(java.lang.String)
     */
    public String encodeUri(String uri) {
        String encodedUri;
        try {
            encodedUri = URLEncoder.encode(uri, "UTF-8");
        } catch (UnsupportedEncodingException uee) {
            // should not happen.
            Log.e("JakartaGDataClient",
                  "UTF-8 not supported -- should not happen.  "
                  + "Using default encoding.", uee);
            encodedUri = URLEncoder.encode(uri);
        }
        return encodedUri;
    }

    /*
     * (non-Javadoc)
     * @see com.google.wireless.gdata.client.GDataClient#createQueryParams()
     */
    public QueryParams createQueryParams() {
        return new QueryParamsImpl();
    }

    // follows redirects
    private InputStream createAndExecuteMethod(HttpRequestCreator creator,
                                               String uriString,
                                               String authToken)
        throws HttpException, IOException {

        HttpResponse response = null;
        int status = 500;
        int redirectsLeft = MAX_REDIRECTS;

        URI uri;
        try {
            uri = new URI(uriString);
        } catch (URISyntaxException use) {
            Log.w(TAG, "Unable to parse " + uriString + " as URI.", use);
            throw new IOException("Unable to parse " + uriString + " as URI: "
                    + use.getMessage());
        }

        // we follow redirects ourselves, since we want to follow redirects even on POSTs, which
        // the HTTP library does not do.  following redirects ourselves also allows us to log
        // the redirects using our own logging.
        while (redirectsLeft > 0) {

            HttpUriRequest request = creator.createRequest(uri);

            AndroidHttpClient.modifyRequestToAcceptGzipResponse(request);
            // only add the auth token if not null (to allow for GData feeds that do not require
            // authentication.)
            if (!TextUtils.isEmpty(authToken)) {
                request.addHeader("Authorization", "GoogleLogin auth=" + authToken);
            }
            if (LOCAL_LOGV) {
                for (Header h : request.getAllHeaders()) {
                    Log.v(TAG, h.getName() + ": " + h.getValue());
                }
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Executing " + request.getRequestLine().toString());
            }

            response = null;

            try {
                response = mHttpClient.execute(request);
            } catch (IOException ioe) {
                Log.w(TAG, "Unable to execute HTTP request." + ioe);
                throw ioe;
            }

            StatusLine statusLine = response.getStatusLine();
            if (statusLine == null) {
                Log.w(TAG, "StatusLine is null.");
                throw new NullPointerException("StatusLine is null -- should not happen.");
            }

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, response.getStatusLine().toString());
                for (Header h : response.getAllHeaders()) {
                    Log.d(TAG, h.getName() + ": " + h.getValue());
                }
            }
            status = statusLine.getStatusCode();

            HttpEntity entity = response.getEntity();

            if ((status >= 200) && (status < 300) && entity != null) {
                InputStream in = AndroidHttpClient.getUngzippedContent(entity);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    in = logInputStreamContents(in);
                }
                return in;
            }

            // TODO: handle 301, 307?
            // TODO: let the http client handle the redirects, if we can be sure we'll never get a
            // redirect on POST.
            if (status == 302) {
                // consume the content, so the connection can be closed.
                entity.consumeContent();
                Header location = response.getFirstHeader("Location");
                if (location == null) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Redirect requested but no Location "
                                + "specified.");
                    }
                    break;
                }
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Following redirect to " + location.getValue());
                }
                try {
                    uri = new URI(location.getValue());
                } catch (URISyntaxException use) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Unable to parse " + location.getValue() + " as URI.", use);
                        throw new IOException("Unable to parse " + location.getValue()
                                + " as URI.");
                    }
                    break;
                }
                --redirectsLeft;
            } else {
                break;
            }
        }

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Received " + status + " status code.");
        }
        String errorMessage = null;
        HttpEntity entity = response.getEntity();
        try {
            if (response != null && entity != null) {
                InputStream in = AndroidHttpClient.getUngzippedContent(entity);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int bytesRead = -1;
                while ((bytesRead = in.read(buf)) != -1) {
                    baos.write(buf, 0, bytesRead);
                }
                // TODO: use appropriate encoding, picked up from Content-Type.
                errorMessage = new String(baos.toByteArray());
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, errorMessage);
                }
            }
        } finally {
            if (entity != null) {
                entity.consumeContent();
            }
        }
        String exceptionMessage = "Received " + status + " status code";
        if (errorMessage != null) {
            exceptionMessage += (": " + errorMessage);
        }
        throw new HttpException(exceptionMessage, status, null /* InputStream */);
    }

    /*
     * (non-Javadoc)
     * @see GDataClient#getFeedAsStream(java.lang.String, java.lang.String)
     */
    public InputStream getFeedAsStream(String feedUrl,
                                       String authToken)
        throws HttpException, IOException {

        InputStream in = createAndExecuteMethod(new GetRequestCreator(), feedUrl, authToken);
        if (in != null) {
            return in;
        }
        throw new IOException("Unable to access feed.");
    }

    /**
     * Log the contents of the input stream.
     * The original input stream is consumed, so the caller must use the
     * BufferedInputStream that is returned.
     * @param in InputStream
     * @return replacement input stream for caller to use
     * @throws IOException
     */
    private InputStream logInputStreamContents(InputStream in) throws IOException {
        if (in == null) {
            return in;
        }
        // bufferSize is the (arbitrary) maximum amount to log.
        // The original InputStream is wrapped in a
        // BufferedInputStream with a 16K buffer.  This lets
        // us read up to 16K, write it to the log, and then
        // reset the stream so the the original client can
        // then read the data.  The BufferedInputStream
        // provides the mark and reset support, even when
        // the original InputStream does not.
        final int bufferSize = 16384;
        BufferedInputStream bin = new BufferedInputStream(in, bufferSize);
        bin.mark(bufferSize);
        int wanted = bufferSize;
        int totalReceived = 0;
        byte buf[] = new byte[wanted];
        while (wanted > 0) {
            int got = bin.read(buf, totalReceived, wanted);
            if (got <= 0) break; // EOF
            wanted -= got;
            totalReceived += got;
        }
        Log.d(TAG, new String(buf, 0, totalReceived, "UTF-8"));
        bin.reset();
        return bin;
    }

    public InputStream getMediaEntryAsStream(String mediaEntryUrl, String authToken)
            throws HttpException, IOException {

        InputStream in = createAndExecuteMethod(new GetRequestCreator(), mediaEntryUrl, authToken);

        if (in != null) {
            return in;
        }
        throw new IOException("Unable to access media entry.");
    }

    /* (non-Javadoc)
    * @see GDataClient#createEntry
    */
    public InputStream createEntry(String feedUrl,
                                   String authToken,
                                   GDataSerializer entry)
        throws HttpException, IOException {

        HttpEntity entity = createEntityForEntry(entry, GDataSerializer.FORMAT_CREATE);
        InputStream in = createAndExecuteMethod(
                new PostRequestCreator(null /* override */, entity),
                feedUrl,
                authToken);
        if (in != null) {
            return in;
        }
        throw new IOException("Unable to create entry.");
    }

    /* (non-Javadoc)
     * @see GDataClient#updateEntry
     */
    public InputStream updateEntry(String editUri,
                                   String authToken,
                                   GDataSerializer entry)
        throws HttpException, IOException {
        HttpEntity entity = createEntityForEntry(entry, GDataSerializer.FORMAT_UPDATE);
        InputStream in = createAndExecuteMethod(
                new PostRequestCreator("PUT", entity),
                editUri,
                authToken);
        if (in != null) {
            return in;
        }
        throw new IOException("Unable to update entry.");
    }

    /* (non-Javadoc)
     * @see GDataClient#deleteEntry
     */
    public void deleteEntry(String editUri, String authToken)
        throws HttpException, IOException {
        if (StringUtils.isEmpty(editUri)) {
            throw new IllegalArgumentException(
                    "you must specify an non-empty edit url");
        }
        InputStream in =
            createAndExecuteMethod(
                    new PostRequestCreator("DELETE", null /* entity */),
                    editUri,
                    authToken);
        if (in == null) {
            throw new IOException("Unable to delete entry.");
        }
        try {
            in.close();
        } catch (IOException ioe) {
            // ignore
        }
    }

    public InputStream updateMediaEntry(String editUri, String authToken,
            InputStream mediaEntryInputStream, String contentType)
        throws HttpException, IOException {
        InputStream in = createAndExecuteMethod(
                new MediaPutRequestCreator(mediaEntryInputStream, contentType),
                editUri,
                authToken);
        if (in != null) {
            return in;
        }
        throw new IOException("Unable to write media entry.");
    }

    private HttpEntity createEntityForEntry(GDataSerializer entry, int format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            entry.serialize(baos, format);
        } catch (IOException ioe) {
            Log.e(TAG, "Unable to serialize entry.", ioe);
            throw ioe;
        } catch (ParseException pe) {
            Log.e(TAG, "Unable to serialize entry.", pe);
            throw new IOException("Unable to serialize entry: " + pe.getMessage());
        }

        byte[] entryBytes = baos.toByteArray();

        if (entryBytes != null && Log.isLoggable(TAG, Log.DEBUG)) {
            try {
                Log.d(TAG, "Serialized entry: " + new String(entryBytes, "UTF-8"));
            } catch (UnsupportedEncodingException uee) {
                // should not happen
                throw new IllegalStateException("UTF-8 should be supported!",
                        uee);
            }
        }

        AbstractHttpEntity entity = AndroidHttpClient.getCompressedEntity(entryBytes, mResolver);
        entity.setContentType(entry.getContentType());
        return entity;
    }
}
