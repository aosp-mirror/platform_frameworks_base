/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.statementservice;

import android.app.Service;
import android.content.Intent;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import com.android.statementservice.retriever.AbstractAsset;
import com.android.statementservice.retriever.AbstractAssetMatcher;
import com.android.statementservice.retriever.AbstractStatementRetriever;
import com.android.statementservice.retriever.AbstractStatementRetriever.Result;
import com.android.statementservice.retriever.AssociationServiceException;
import com.android.statementservice.retriever.Relation;
import com.android.statementservice.retriever.Statement;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Handles com.android.statementservice.service.CHECK_ALL_ACTION intents.
 */
public final class DirectStatementService extends Service {
    private static final String TAG = DirectStatementService.class.getSimpleName();

    /**
     * Returns true if every asset in {@code SOURCE_ASSET_DESCRIPTORS} is associated with {@code
     * EXTRA_TARGET_ASSET_DESCRIPTOR} for {@code EXTRA_RELATION} relation.
     *
     * <p>Takes parameter {@code EXTRA_RELATION}, {@code SOURCE_ASSET_DESCRIPTORS}, {@code
     * EXTRA_TARGET_ASSET_DESCRIPTOR}, and {@code EXTRA_RESULT_RECEIVER}.
     */
    public static final String CHECK_ALL_ACTION =
            "com.android.statementservice.service.CHECK_ALL_ACTION";

    /**
     * Parameter for {@link #CHECK_ALL_ACTION}.
     *
     * <p>A relation string.
     */
    public static final String EXTRA_RELATION =
            "com.android.statementservice.service.RELATION";

    /**
     * Parameter for {@link #CHECK_ALL_ACTION}.
     *
     * <p>An array of asset descriptors in JSON.
     */
    public static final String EXTRA_SOURCE_ASSET_DESCRIPTORS =
            "com.android.statementservice.service.SOURCE_ASSET_DESCRIPTORS";

    /**
     * Parameter for {@link #CHECK_ALL_ACTION}.
     *
     * <p>An asset descriptor in JSON.
     */
    public static final String EXTRA_TARGET_ASSET_DESCRIPTOR =
            "com.android.statementservice.service.TARGET_ASSET_DESCRIPTOR";

    /**
     * Parameter for {@link #CHECK_ALL_ACTION}.
     *
     * <p>A {@code ResultReceiver} instance that will be used to return the result. If the request
     * failed, return {@link #RESULT_FAIL} and an empty {@link android.os.Bundle}. Otherwise, return
     * {@link #RESULT_SUCCESS} and a {@link android.os.Bundle} with the result stored in {@link
     * #IS_ASSOCIATED}.
     */
    public static final String EXTRA_RESULT_RECEIVER =
            "com.android.statementservice.service.RESULT_RECEIVER";

    /**
     * A boolean bundle entry that stores the result of {@link #CHECK_ALL_ACTION}.
     * This is set only if the service returns with {@code RESULT_SUCCESS}.
     * {@code IS_ASSOCIATED} is true if and only if {@code FAILED_SOURCES} is empty.
     */
    public static final String IS_ASSOCIATED = "is_associated";

    /**
     * A String ArrayList bundle entry that stores sources that can't be verified.
     */
    public static final String FAILED_SOURCES = "failed_sources";

    /**
     * Returned by the service if the request is successfully processed. The caller should check
     * the {@code IS_ASSOCIATED} field to determine if the association exists or not.
     */
    public static final int RESULT_SUCCESS = 0;

    /**
     * Returned by the service if the request failed. The request will fail if, for example, the
     * input is not well formed, or the network is not available.
     */
    public static final int RESULT_FAIL = 1;

    private static final long HTTP_CACHE_SIZE_IN_BYTES = 1 * 1024 * 1024;  // 1 MBytes
    private static final String CACHE_FILENAME = "request_cache";

    private AbstractStatementRetriever mStatementRetriever;
    private Handler mHandler;
    private HandlerThread mThread;
    private HttpResponseCache mHttpResponseCache;

    @Override
    public void onCreate() {
        mThread = new HandlerThread("DirectStatementService thread",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mThread.start();
        onCreate(AbstractStatementRetriever.createDirectRetriever(this), mThread.getLooper(),
                getCacheDir());
    }

    /**
     * Creates a DirectStatementService with the dependencies passed in for easy testing.
     */
    public void onCreate(AbstractStatementRetriever statementRetriever, Looper looper,
                         File cacheDir) {
        super.onCreate();
        mStatementRetriever = statementRetriever;
        mHandler = new Handler(looper);

        try {
            File httpCacheDir = new File(cacheDir, CACHE_FILENAME);
            mHttpResponseCache = HttpResponseCache.install(httpCacheDir, HTTP_CACHE_SIZE_IN_BYTES);
        } catch (IOException e) {
            Log.i(TAG, "HTTPS response cache installation failed:" + e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        final HttpResponseCache responseCache = mHttpResponseCache;
        mHandler.post(new Runnable() {
            public void run() {
                try {
                    if (responseCache != null) {
                        responseCache.delete();
                    }
                } catch (IOException e) {
                    Log.i(TAG, "HTTP(S) response cache deletion failed:" + e);
                }
                Looper.myLooper().quit();
            }
        });
        mHttpResponseCache = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (intent == null) {
            Log.e(TAG, "onStartCommand called with null intent");
            return START_STICKY;
        }

        if (intent.getAction().equals(CHECK_ALL_ACTION)) {

            Bundle extras = intent.getExtras();
            List<String> sources = extras.getStringArrayList(EXTRA_SOURCE_ASSET_DESCRIPTORS);
            String target = extras.getString(EXTRA_TARGET_ASSET_DESCRIPTOR);
            String relation = extras.getString(EXTRA_RELATION);
            ResultReceiver resultReceiver = extras.getParcelable(EXTRA_RESULT_RECEIVER);

            if (resultReceiver == null) {
                Log.e(TAG, " Intent does not have extra " + EXTRA_RESULT_RECEIVER);
                return START_STICKY;
            }
            if (sources == null) {
                Log.e(TAG, " Intent does not have extra " + EXTRA_SOURCE_ASSET_DESCRIPTORS);
                resultReceiver.send(RESULT_FAIL, Bundle.EMPTY);
                return START_STICKY;
            }
            if (target == null) {
                Log.e(TAG, " Intent does not have extra " + EXTRA_TARGET_ASSET_DESCRIPTOR);
                resultReceiver.send(RESULT_FAIL, Bundle.EMPTY);
                return START_STICKY;
            }
            if (relation == null) {
                Log.e(TAG, " Intent does not have extra " + EXTRA_RELATION);
                resultReceiver.send(RESULT_FAIL, Bundle.EMPTY);
                return START_STICKY;
            }

            mHandler.post(new ExceptionLoggingFutureTask<Void>(
                    new IsAssociatedCallable(sources, target, relation, resultReceiver), TAG));
        } else {
            Log.e(TAG, "onStartCommand called with unsupported action: " + intent.getAction());
        }
        return START_STICKY;
    }

    private class IsAssociatedCallable implements Callable<Void> {

        private List<String> mSources;
        private String mTarget;
        private String mRelation;
        private ResultReceiver mResultReceiver;

        public IsAssociatedCallable(List<String> sources, String target, String relation,
                ResultReceiver resultReceiver) {
            mSources = sources;
            mTarget = target;
            mRelation = relation;
            mResultReceiver = resultReceiver;
        }

        private boolean verifyOneSource(AbstractAsset source, AbstractAssetMatcher target,
                Relation relation) throws AssociationServiceException {
            Result statements = mStatementRetriever.retrieveStatements(source);
            for (Statement statement : statements.getStatements()) {
                if (relation.matches(statement.getRelation())
                        && target.matches(statement.getTarget())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Void call() {
            Bundle result = new Bundle();
            ArrayList<String> failedSources = new ArrayList<String>();
            AbstractAssetMatcher target;
            Relation relation;
            try {
                target = AbstractAssetMatcher.createMatcher(mTarget);
                relation = Relation.create(mRelation);
            } catch (AssociationServiceException | JSONException e) {
                Log.e(TAG, "isAssociatedCallable failed with exception", e);
                mResultReceiver.send(RESULT_FAIL, Bundle.EMPTY);
                return null;
            }

            boolean allSourcesVerified = true;
            for (String sourceString : mSources) {
                AbstractAsset source;
                try {
                    source = AbstractAsset.create(sourceString);
                } catch (AssociationServiceException e) {
                    mResultReceiver.send(RESULT_FAIL, Bundle.EMPTY);
                    return null;
                }

                try {
                    if (!verifyOneSource(source, target, relation)) {
                        failedSources.add(source.toJson());
                        allSourcesVerified = false;
                    }
                } catch (AssociationServiceException e) {
                    failedSources.add(source.toJson());
                    allSourcesVerified = false;
                }
            }

            result.putBoolean(IS_ASSOCIATED, allSourcesVerified);
            result.putStringArrayList(FAILED_SOURCES, failedSources);
            mResultReceiver.send(RESULT_SUCCESS, result);
            return null;
        }
    }
}
