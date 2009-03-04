// Copyright 2008 The Android Open Source Project
// All rights reserved.

package com.android.unit_tests;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import com.google.android.googleapps.GoogleLoginCredentialsResult;
import com.google.android.googleapps.IGoogleLoginService;
import com.google.android.googlelogin.GoogleLoginServiceConstants;

import junit.framework.Assert;

// Suppress until bug http://b/issue?id=1416570 is fixed
@Suppress
/** Unit test for the Google login service. */
public class GoogleLoginServiceTest extends AndroidTestCase {
    private static final String TAG = "GoogleLoginServiceTest";

    private IGoogleLoginService mGls = null;
    private Lock mGlsLock = new ReentrantLock();
    private Condition mGlsCv = mGlsLock.newCondition();

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            mGlsLock.lock();
            try {
                mGls = IGoogleLoginService.Stub.asInterface(service);
                mGlsCv.signalAll();
            } finally {
                mGlsLock.unlock();
            }
            Log.v(TAG, "service is connected");
        }
        public void onServiceDisconnected(ComponentName className) {
            mGlsLock.lock();
            try {
                mGls = null;
                mGlsCv.signalAll();
            } finally {
                mGlsLock.unlock();
            }
            Log.v(TAG, "service is disconnected");
        }
    };

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        getContext().bindService((new Intent())
                                 .setClassName("com.google.android.googleapps",
                                               "com.google.android.googleapps.GoogleLoginService"),
                                 mConnection, Context.BIND_AUTO_CREATE);

        // wait for the service to cnnnect
        mGlsLock.lock();
        try {
            while (mGls == null) {
                try {
                    mGlsCv.await();
                } catch (InterruptedException ignore) {
                }
            }
        } finally {
            mGlsLock.unlock();
        }
    }

    @Override
    protected void tearDown() throws Exception {
        getContext().unbindService(mConnection);
        super.tearDown();
    }

    public void testSingleAccountScheme() throws Exception {
        Assert.assertNotNull(mGls);
        mGls.deleteAllAccounts();

        Assert.assertNull(mGls.getAccount(false));
        Assert.assertNull(mGls.getAccount(true));

        mGls.saveUsernameAndPassword("vespa@gmail.com", "meow",
                                     GoogleLoginServiceConstants.FLAG_GOOGLE_ACCOUNT);
        Assert.assertEquals("vespa@gmail.com", mGls.getAccount(false));
        Assert.assertEquals("vespa@gmail.com", mGls.getAccount(true));

        mGls.saveUsernameAndPassword("mackerel@hosted.com", "purr",
                                     GoogleLoginServiceConstants.FLAG_HOSTED_ACCOUNT);
        Assert.assertEquals("mackerel@hosted.com", mGls.getAccount(false));
        Assert.assertEquals("vespa@gmail.com", mGls.getAccount(true));
    }

    public void listsEqual(String[] a, String[] b) {
        Assert.assertEquals(a.length, b.length);
        Arrays.sort(a);
        Arrays.sort(b);
        Assert.assertTrue(Arrays.equals(a, b));
    }

    public void testAuthTokens() throws Exception {
        Assert.assertNotNull(mGls);
        mGls.deleteAllAccounts();

        Assert.assertNull(mGls.peekCredentials("vespa@example.com", "mail"));

        mGls.saveUsernameAndPassword("vespa@example.com", "meow",
                                     GoogleLoginServiceConstants.FLAG_HOSTED_ACCOUNT);
        Assert.assertNull(mGls.peekCredentials("vespa@example.com", "mail"));
        Assert.assertNull(mGls.peekCredentials(null, "mail"));

        mGls.saveAuthToken("vespa@example.com", "mail", "1234");
        Assert.assertEquals("1234", mGls.peekCredentials("vespa@example.com", "mail"));
        Assert.assertEquals("1234", mGls.peekCredentials(null, "mail"));

        mGls.saveUsernameAndPassword("mackerel@example.com", "purr",
                                     GoogleLoginServiceConstants.FLAG_GOOGLE_ACCOUNT);
        mGls.saveAuthToken("mackerel@example.com", "mail", "5678");
        Assert.assertEquals("1234", mGls.peekCredentials(null, "mail"));

        mGls.saveAuthToken("mackerel@example.com", "mail", "8765");
        Assert.assertEquals("8765", mGls.peekCredentials("mackerel@example.com", "mail"));

        GoogleLoginCredentialsResult r = mGls.blockingGetCredentials(
                "vespa@example.com", "mail", false);
        Assert.assertEquals("vespa@example.com", r.getAccount());
        Assert.assertEquals("1234", r.getCredentialsString());
        Assert.assertNull(r.getCredentialsIntent());

        mGls.saveAuthToken("vespa@example.com", "cl", "abcd");
        Assert.assertEquals("1234", mGls.peekCredentials("vespa@example.com", "mail"));
        Assert.assertEquals("abcd", mGls.peekCredentials("vespa@example.com", "cl"));
    }
}
