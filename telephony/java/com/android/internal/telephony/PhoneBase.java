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

package com.android.internal.telephony;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.telephony.ServiceState;
import android.util.Log;
import com.android.internal.R;
import com.android.internal.telephony.test.SimulatedRadioControl;

import java.util.List;
import java.util.Locale;

/**
 * (<em>Not for SDK use</em>) 
 * A base implementation for the com.android.internal.telephony.Phone interface.
 * 
 * Note that implementations of Phone.java are expected to be used
 * from a single application thread. This should be the same thread that
 * originally called PhoneFactory to obtain the interface.
 *
 *  {@hide}
 *
 */

public abstract class PhoneBase implements Phone {
    private static final String LOG_TAG = "GSM";

    protected final RegistrantList mPhoneStateRegistrants 
            = new RegistrantList();

    protected final RegistrantList mNewRingingConnectionRegistrants 
            = new RegistrantList();

    protected final RegistrantList mIncomingRingRegistrants 
            = new RegistrantList();
    
    protected final RegistrantList mDisconnectRegistrants 
            = new RegistrantList();

    protected final RegistrantList mServiceStateRegistrants 
            = new RegistrantList();
    
    protected final RegistrantList mMmiCompleteRegistrants 
            = new RegistrantList();

    protected final RegistrantList mMmiRegistrants 
            = new RegistrantList();

    protected final RegistrantList mUnknownConnectionRegistrants 
            = new RegistrantList();
    
    protected final RegistrantList mSuppServiceFailedRegistrants 
            = new RegistrantList();
    
    protected Looper mLooper; /* to insure registrants are in correct thread*/

    protected Context mContext;

    /** 
     * PhoneNotifier is an abstraction for all system-wide 
     * state change notification. DefaultPhoneNotifier is 
     * used here unless running we're inside a unit test.
     */
    protected PhoneNotifier mNotifier;

    protected SimulatedRadioControl mSimulatedRadioControl;

    boolean mUnitTestMode;

    /**
     * Constructs a PhoneBase in normal (non-unit test) mode.
     *
     * @param context Context object from hosting application
     * @param notifier An instance of DefaultPhoneNotifier, 
     * unless unit testing.
     */
    protected PhoneBase(PhoneNotifier notifier, Context context) {
        this(notifier, context, false);
    }

    /**
     * Constructs a PhoneBase in normal (non-unit test) mode.
     *
     * @param context Context object from hosting application
     * @param notifier An instance of DefaultPhoneNotifier, 
     * unless unit testing.
     * @param unitTestMode when true, prevents notifications 
     * of state change events
     */
    protected PhoneBase(PhoneNotifier notifier, Context context, 
                         boolean unitTestMode) {
        this.mNotifier = notifier;
        this.mContext = context;
        mLooper = Looper.myLooper();

        setLocaleByCarrier();

        setUnitTestMode(unitTestMode);
    }

    // Inherited documentation suffices.
    public Context getContext() {
        return mContext;
    }

    // Inherited documentation suffices.
    public void registerForPhoneStateChanged(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mPhoneStateRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForPhoneStateChanged(Handler h) {
        mPhoneStateRegistrants.remove(h);
    }
    
    /**
     * Notify registrants of a PhoneStateChanged.
     * Subclasses of Phone probably want to replace this with a 
     * version scoped to their packages
     */
    protected void notifyCallStateChangedP() {
        AsyncResult ar = new AsyncResult(null, this, null);
        mPhoneStateRegistrants.notifyRegistrants(ar);
    }
     
    // Inherited documentation suffices.
    public void registerForUnknownConnection(Handler h, int what, Object obj) {
        checkCorrectThread(h);
        
        mUnknownConnectionRegistrants.addUnique(h, what, obj);
    }
    
    // Inherited documentation suffices.
    public void unregisterForUnknownConnection(Handler h) {
        mUnknownConnectionRegistrants.remove(h);
    }
    
    // Inherited documentation suffices.
    public void registerForNewRingingConnection(
            Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mNewRingingConnectionRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForNewRingingConnection(Handler h) {
        mNewRingingConnectionRegistrants.remove(h);
    }

    /**
     * Notifiy registrants of a new ringing Connection.
     * Subclasses of Phone probably want to replace this with a 
     * version scoped to their packages
     */
    protected void notifyNewRingingConnectionP(Connection cn) {    
        AsyncResult ar = new AsyncResult(null, cn, null);
        mNewRingingConnectionRegistrants.notifyRegistrants(ar);
    }

    // Inherited documentation suffices.
    public void registerForIncomingRing(
            Handler h, int what, Object obj) {
        checkCorrectThread(h);
        
        mIncomingRingRegistrants.addUnique(h, what, obj);
    }
    
    // Inherited documentation suffices.
    public void unregisterForIncomingRing(Handler h) {
        mIncomingRingRegistrants.remove(h);
    }
    
    // Inherited documentation suffices.
    public void registerForDisconnect(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mDisconnectRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForDisconnect(Handler h) {
        mDisconnectRegistrants.remove(h);
    }

    // Inherited documentation suffices.
    public void registerForSuppServiceFailed(Handler h, int what, Object obj) {
        checkCorrectThread(h);
        
        mSuppServiceFailedRegistrants.addUnique(h, what, obj);
    }
    
    // Inherited documentation suffices.
    public void unregisterForSuppServiceFailed(Handler h) {
        mSuppServiceFailedRegistrants.remove(h);
    }
    
    // Inherited documentation suffices.
    public void registerForMmiInitiate(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mMmiRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForMmiInitiate(Handler h) {
        mMmiRegistrants.remove(h);
    }
    
    // Inherited documentation suffices.
    public void registerForMmiComplete(Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mMmiCompleteRegistrants.addUnique(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForMmiComplete(Handler h) {
        checkCorrectThread(h);

        mMmiCompleteRegistrants.remove(h);
    }

    /**
     * Subclasses should override this. See documentation in superclass.
     */
    public abstract List getPendingMmiCodes();
    
    // Inherited documentation suffices.
    public void setUnitTestMode(boolean f) {
        mUnitTestMode = f;
    }

    // Inherited documentation suffices.
    public boolean getUnitTestMode() {
        return mUnitTestMode;
    }
    
    /**
     * To be invoked when a voice call Connection disconnects.
     *
     * Subclasses of Phone probably want to replace this with a 
     * version scoped to their packages
     */
    protected void notifyDisconnectP(Connection cn) {
        AsyncResult ar = new AsyncResult(null, cn, null);
        mDisconnectRegistrants.notifyRegistrants(ar);
    }

    // Inherited documentation suffices.
    public void registerForServiceStateChanged(
            Handler h, int what, Object obj) {
        checkCorrectThread(h);

        mServiceStateRegistrants.add(h, what, obj);
    }

    // Inherited documentation suffices.
    public void unregisterForServiceStateChanged(Handler h) {
        mServiceStateRegistrants.remove(h);
    }

    /**
     * Subclasses of Phone probably want to replace this with a 
     * version scoped to their packages
     */
    protected void notifyServiceStateChangedP(ServiceState ss) {
        AsyncResult ar = new AsyncResult(null, ss, null);
        mServiceStateRegistrants.notifyRegistrants(ar);

        mNotifier.notifyServiceState(this);
    }

    // Inherited documentation suffices.
    public SimulatedRadioControl getSimulatedRadioControl() {
        return mSimulatedRadioControl;
    }

    /**
     * Verifies the current thread is the same as the thread originally
     * used in the initialization of this instance. Throws RuntimeException
     * if not.
     *
     * @exception RuntimeException if the current thread is not
     * the thread that originally obtained this PhoneBase instance.
     */
    private void checkCorrectThread(Handler h) {
        if (h.getLooper() != mLooper) {
            throw new RuntimeException(
                "com.android.internal.telephony.Phone must be used from within one thread");
        }
    }

    /**
     * Set the locale by matching the carrier string in
     * a string-array resource
     */
    private void setLocaleByCarrier() {
        String carrier = SystemProperties.get("ro.carrier");

        if (null == carrier || 0 == carrier.length()) {
            return;
        }

        CharSequence[] carrierLocales = mContext.
                getResources().getTextArray(R.array.carrier_locales);

        for (int i = 0; i < carrierLocales.length-1; i+=2) {
            String c = carrierLocales[i].toString();
            String l = carrierLocales[i+1].toString();
            if (carrier.equals(c)) {
                String language = l.substring(0, 2);
                String country = "";
                if (l.length() >=5) {
                    country = l.substring(3, 5);
                }
                setSystemLocale(language, country);
                return;
            }
        }
    }

    /**
     * Utility code to set the system locale if it's not set already
     * @param langauge Two character language code desired
     * @param country Two character country code desired
     *
     *  {@hide}
     */
    public void setSystemLocale(String language, String country) {
        String l = SystemProperties.get("persist.sys.language");
        String c = SystemProperties.get("persist.sys.country");

        if (null == language) {
            return; // no match possible
        }
        language.toLowerCase();
        if (null == country) {
            country = "";
        }
        country = country.toUpperCase();

        if((null == l || 0 == l.length()) && (null == c || 0 == c.length())) {
            try {
                // try to find a good match
                String[] locales = mContext.getAssets().getLocales();
                final int N = locales.length;
                String bestMatch = null;
                for(int i = 0; i < N; i++) {
                    if (locales[i]!=null && locales[i].length() >= 2 &&
                            locales[i].substring(0,2).equals(language)) {
                        if (locales[i].length() >= 5) {
                            if (locales[i].substring(3,5).equals(country)) {
                                bestMatch = locales[i];
                                break;
                            }
                        } else if (null == bestMatch) {
                            bestMatch = locales[i];
                        }
                    }
                }
                if (null != bestMatch) {
                    IActivityManager am = ActivityManagerNative.getDefault();
                    Configuration config = am.getConfiguration();
                    if (bestMatch.length() >= 5) {
                        config.locale = new Locale(bestMatch.substring(0,2),
                                                   bestMatch.substring(3,5));
                    } else {
                        config.locale = new Locale(bestMatch.substring(0,2));
                    }
                    config.userSetLocale = true;
                    am.updateConfiguration(config);
                }
            } catch (Exception e) {
                // Intentionally left blank
            }
        }
    }
}
