package com.google.android.gdata.client;

import com.google.wireless.gdata.client.QueryParams;

import android.text.TextUtils;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Simple implementation of the QueryParams interface.
 */
// TODO: deal with categories
public class QueryParamsImpl extends QueryParams {

    private final Map<String,String> mParams = new HashMap<String,String>();

    /**
     * Creates a new empty QueryParamsImpl.
     */
    public QueryParamsImpl() {
    }

    @Override
    public void clear() {
        setEntryId(null);
        mParams.clear();
    }

    @Override
    public String generateQueryUrl(String feedUrl) {

        if (TextUtils.isEmpty(getEntryId()) &&
            mParams.isEmpty()) {
            // nothing to do
            return feedUrl;
        }

        // handle entry IDs
        if (!TextUtils.isEmpty(getEntryId())) {
            if (!mParams.isEmpty()) {
                throw new IllegalStateException("Cannot set both an entry ID "
                        + "and other query paramters.");
            }
            return feedUrl + '/' + getEntryId();
        }

        // otherwise, append the querystring params.
        StringBuilder sb = new StringBuilder();
        sb.append(feedUrl);
        Set<String> params = mParams.keySet();
        boolean first = true;
        if (feedUrl.contains("?")) {
            first = false;
        } else {
            sb.append('?');
        }
        for (String param : params) {
            String value = mParams.get(param);
            if (value == null) continue;
            if (first) {
                first = false;
            } else {
                sb.append('&');
            }
            sb.append(param);
            sb.append('=');

            String encodedValue = null;

            try {
                encodedValue = URLEncoder.encode(value, "UTF-8");
            } catch (UnsupportedEncodingException uee) {
                // should not happen.
                Log.w("QueryParamsImpl",
                      "UTF-8 not supported -- should not happen.  "
                      + "Using default encoding.", uee);
                encodedValue = URLEncoder.encode(value);
            }
            sb.append(encodedValue);
        }
        return sb.toString();
    }

    @Override
    public String getParamValue(String param) {
        if (!(mParams.containsKey(param))) {
            return null;
        }
        return mParams.get(param);
    }

    @Override
    public void setParamValue(String param, String value) {
        mParams.put(param, value);
    }

}
