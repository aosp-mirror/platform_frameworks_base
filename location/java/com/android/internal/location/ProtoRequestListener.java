// Copyright 2007 The Android Open Source Project

package com.android.internal.location;

import com.google.common.io.GoogleHttpConnection;
import com.google.common.io.protocol.ProtoBuf;
import com.google.masf.ServiceCallback;
import com.google.masf.protocol.Request;
import com.google.masf.protocol.Response;
import com.google.masf.services.AsyncResult;

import java.io.IOException;
import java.io.InputStream;

import android.util.Log;

/**
 * Listener for protocol buffer requests
 *
 * {@hide}
 */

public class ProtoRequestListener implements Request.Listener {
    private final static String TAG = "ProtoRequestListener";
    private AsyncResult result;
    private ProtoBuf protoResponse;

    /**
     * @return the asynchronous result object
     */
    public AsyncResult getAsyncResult() {
        return result;
    }

    /**
     * Constructor for a ProtoRequestListener
     *
     * @param protoResponse ProtoBuf with correct type to fill response with
     * @param callback function to call after completed request (may be null)
     */
    public ProtoRequestListener(ProtoBuf protoResponse, ServiceCallback callback) {
        this.result = new AsyncResult(callback);
        this.protoResponse = protoResponse;
    }

    public boolean requestComplete(Request request, Response response)
        throws IOException {
        InputStream is = response.getInputStream();
        if (response.getStatusCode() == GoogleHttpConnection.HTTP_OK) {
            protoResponse.parse(is);
            result.setResult(protoResponse);
        } else {
            result.setResult(null);
        }
        return true;
    }

    public void requestException(Request request, Exception exception) {
        Log.e(TAG, "requestException()", exception);
    }
}
