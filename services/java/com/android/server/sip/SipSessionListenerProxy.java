/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.sip;

import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SipProfile;
import android.util.Log;

/** Class to help safely run a callback in a different thread. */
class SipSessionListenerProxy extends ISipSessionListener.Stub {
    private static final String TAG = "SipSession";

    private ISipSessionListener mListener;

    public void setListener(ISipSessionListener listener) {
        mListener = listener;
    }

    public ISipSessionListener getListener() {
        return mListener;
    }

    private void proxy(Runnable runnable) {
        // One thread for each calling back.
        // Note: Guarantee ordering if the issue becomes important. Currently,
        // the chance of handling two callback events at a time is none.
        new Thread(runnable).start();
    }

    public void onCalling(final ISipSession session) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onCalling(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onCalling()", t);
                }
            }
        });
    }

    public void onRinging(final ISipSession session, final SipProfile caller,
            final byte[] sessionDescription) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onRinging(session, caller, sessionDescription);
                } catch (Throwable t) {
                    Log.w(TAG, "onRinging()", t);
                }
            }
        });
    }

    public void onRingingBack(final ISipSession session) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onRingingBack(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onRingingBack()", t);
                }
            }
        });
    }

    public void onCallEstablished(final ISipSession session,
            final byte[] sessionDescription) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onCallEstablished(session, sessionDescription);
                } catch (Throwable t) {
                    Log.w(TAG, "onCallEstablished()", t);
                }
            }
        });
    }

    public void onCallEnded(final ISipSession session) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onCallEnded(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onCallEnded()", t);
                }
            }
        });
    }

    public void onCallBusy(final ISipSession session) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onCallBusy(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onCallBusy()", t);
                }
            }
        });
    }

    public void onCallChangeFailed(final ISipSession session,
            final String className, final String message) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onCallChangeFailed(session, className, message);
                } catch (Throwable t) {
                    Log.w(TAG, "onCallChangeFailed()", t);
                }
            }
        });
    }

    public void onError(final ISipSession session, final String className,
            final String message) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onError(session, className, message);
                } catch (Throwable t) {
                    Log.w(TAG, "onError()", t);
                }
            }
        });
    }

    public void onRegistering(final ISipSession session) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onRegistering(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistering()", t);
                }
            }
        });
    }

    public void onRegistrationDone(final ISipSession session,
            final int duration) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onRegistrationDone(session, duration);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationDone()", t);
                }
            }
        });
    }

    public void onRegistrationFailed(final ISipSession session,
            final String className, final String message) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onRegistrationFailed(session, className, message);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationFailed()", t);
                }
            }
        });
    }

    public void onRegistrationTimeout(final ISipSession session) {
        if (mListener == null) return;
        proxy(new Runnable() {
            public void run() {
                try {
                    mListener.onRegistrationTimeout(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationTimeout()", t);
                }
            }
        });
    }
}
