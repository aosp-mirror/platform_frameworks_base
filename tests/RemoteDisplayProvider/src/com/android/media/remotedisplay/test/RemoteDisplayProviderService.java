/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.media.remotedisplay.test;

import com.android.media.remotedisplay.RemoteDisplay;
import com.android.media.remotedisplay.RemoteDisplayProvider;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

/**
 * Remote display provider implementation that publishes working routes.
 */
public class RemoteDisplayProviderService extends Service {
    private static final String TAG = "RemoteDisplayProviderTest";

    private Provider mProvider;

    @Override
    public IBinder onBind(Intent intent) {
        if (intent.getAction().equals(RemoteDisplayProvider.SERVICE_INTERFACE)) {
            if (mProvider == null) {
                mProvider = new Provider();
                return mProvider.getBinder();
            }
        }
        return null;
    }

    final class Provider extends RemoteDisplayProvider {
        private RemoteDisplay mTestDisplay1; // variable volume
        private RemoteDisplay mTestDisplay2; // fixed volume
        private RemoteDisplay mTestDisplay3; // not available
        private RemoteDisplay mTestDisplay4; // in use
        private RemoteDisplay mTestDisplay5; // available but ignores request to connect
        private RemoteDisplay mTestDisplay6; // available but never finishes connecting
        private RemoteDisplay mTestDisplay7; // blinks in and out of existence
        private RemoteDisplay mTestDisplay8; // available but connecting attempt flakes out
        private RemoteDisplay mTestDisplay9; // available but connection flakes out
        private RemoteDisplay mTestDisplay10; // available and reconnects periodically

        private final Handler mHandler;
        private boolean mBlinking;

        public Provider() {
            super(RemoteDisplayProviderService.this);
            mHandler = new Handler(getMainLooper());
        }

        @Override
        public void onDiscoveryModeChanged(int mode) {
            Log.d(TAG, "onDiscoveryModeChanged: mode=" + mode);

            if (mode != DISCOVERY_MODE_NONE) {
                // When discovery begins, go find all of the routes.
                if (mTestDisplay1 == null) {
                    mTestDisplay1 = new RemoteDisplay("testDisplay1",
                            "Test Display 1 (variable)");
                    mTestDisplay1.setDescription("Variable volume");
                    mTestDisplay1.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                    mTestDisplay1.setVolume(10);
                    mTestDisplay1.setVolumeHandling(RemoteDisplay.PLAYBACK_VOLUME_VARIABLE);
                    mTestDisplay1.setVolumeMax(15);
                    addDisplay(mTestDisplay1);
                }
                if (mTestDisplay2 == null) {
                    mTestDisplay2 = new RemoteDisplay("testDisplay2",
                            "Test Display 2 (fixed)");
                    mTestDisplay2.setDescription("Fixed volume");
                    mTestDisplay2.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                    addDisplay(mTestDisplay2);
                }
                if (mTestDisplay3 == null) {
                    mTestDisplay3 = new RemoteDisplay("testDisplay3",
                            "Test Display 3 (unavailable)");
                    mTestDisplay3.setDescription("Always unavailable");
                    mTestDisplay3.setStatus(RemoteDisplay.STATUS_NOT_AVAILABLE);
                    addDisplay(mTestDisplay3);
                }
                if (mTestDisplay4 == null) {
                    mTestDisplay4 = new RemoteDisplay("testDisplay4",
                            "Test Display 4 (in-use)");
                    mTestDisplay4.setDescription("Always in-use");
                    mTestDisplay4.setStatus(RemoteDisplay.STATUS_IN_USE);
                    addDisplay(mTestDisplay4);
                }
                if (mTestDisplay5 == null) {
                    mTestDisplay5 = new RemoteDisplay("testDisplay5",
                            "Test Display 5 (connect ignored)");
                    mTestDisplay5.setDescription("Ignores connect");
                    mTestDisplay5.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                    addDisplay(mTestDisplay5);
                }
                if (mTestDisplay6 == null) {
                    mTestDisplay6 = new RemoteDisplay("testDisplay6",
                            "Test Display 6 (connect hangs)");
                    mTestDisplay6.setDescription("Never finishes connecting");
                    mTestDisplay6.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                    addDisplay(mTestDisplay6);
                }
                if (mTestDisplay8 == null) {
                    mTestDisplay8 = new RemoteDisplay("testDisplay8",
                            "Test Display 8 (flaky when connecting)");
                    mTestDisplay8.setDescription("Aborts spontaneously while connecting");
                    mTestDisplay8.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                    addDisplay(mTestDisplay8);
                }
                if (mTestDisplay9 == null) {
                    mTestDisplay9 = new RemoteDisplay("testDisplay9",
                            "Test Display 9 (flaky when connected)");
                    mTestDisplay9.setDescription("Aborts spontaneously while connected");
                    mTestDisplay9.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                    addDisplay(mTestDisplay9);
                }
                if (mTestDisplay10 == null) {
                    mTestDisplay10 = new RemoteDisplay("testDisplay10",
                            "Test Display 10 (reconnects periodically)");
                    mTestDisplay10.setDescription("Reconnects spontaneously");
                    mTestDisplay10.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                    addDisplay(mTestDisplay10);
                }
            } else {
                // When discovery ends, go hide some of the routes we can't actually use.
                // This isn't something a normal route provider would do though.
                // The routes will usually stay published.
                if (mTestDisplay3 != null) {
                    removeDisplay(mTestDisplay3);
                    mTestDisplay3 = null;
                }
                if (mTestDisplay4 != null) {
                    removeDisplay(mTestDisplay4);
                    mTestDisplay4 = null;
                }
            }

            // When active discovery is on, pretend there's a route that we can't quite
            // reach that blinks in and out of existence.
            if (mode == DISCOVERY_MODE_ACTIVE) {
                if (!mBlinking) {
                    mBlinking = true;
                    mHandler.post(mBlink);
                }
            } else {
                mBlinking = false;
            }
        }

        @Override
        public void onConnect(final RemoteDisplay display) {
            Log.d(TAG, "onConnect: display.getId()=" + display.getId());

            if (display == mTestDisplay1 || display == mTestDisplay2) {
                display.setStatus(RemoteDisplay.STATUS_CONNECTING);
                updateDisplay(display);
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if ((display == mTestDisplay1 || display == mTestDisplay2)
                                && display.getStatus() == RemoteDisplay.STATUS_CONNECTING) {
                            display.setStatus(RemoteDisplay.STATUS_CONNECTED);
                            updateDisplay(display);
                        }
                    }
                }, 2000);
            } else if (display == mTestDisplay6 || display == mTestDisplay7) {
                // never finishes connecting
                display.setStatus(RemoteDisplay.STATUS_CONNECTING);
                updateDisplay(display);
            } else if (display == mTestDisplay8) {
                // flakes out while connecting
                display.setStatus(RemoteDisplay.STATUS_CONNECTING);
                updateDisplay(display);
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if ((display == mTestDisplay8)
                                && display.getStatus() == RemoteDisplay.STATUS_CONNECTING) {
                            display.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                            updateDisplay(display);
                        }
                    }
                }, 2000);
            } else if (display == mTestDisplay9) {
                // flakes out when connected
                display.setStatus(RemoteDisplay.STATUS_CONNECTING);
                updateDisplay(display);
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if ((display == mTestDisplay9)
                                && display.getStatus() == RemoteDisplay.STATUS_CONNECTING) {
                            display.setStatus(RemoteDisplay.STATUS_CONNECTED);
                            updateDisplay(display);
                        }
                    }
                }, 2000);
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if ((display == mTestDisplay9)
                                && display.getStatus() == RemoteDisplay.STATUS_CONNECTED) {
                            display.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                            updateDisplay(display);
                        }
                    }
                }, 5000);
            } else if (display == mTestDisplay10) {
                display.setStatus(RemoteDisplay.STATUS_CONNECTING);
                updateDisplay(display);
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (display == mTestDisplay10) {
                            if (display.getStatus() == RemoteDisplay.STATUS_CONNECTING) {
                                display.setStatus(RemoteDisplay.STATUS_CONNECTED);
                                updateDisplay(display);
                                mHandler.postDelayed(this, 7000);
                            } else if (display.getStatus() == RemoteDisplay.STATUS_CONNECTED) {
                                display.setStatus(RemoteDisplay.STATUS_CONNECTING);
                                updateDisplay(display);
                                mHandler.postDelayed(this, 2000);
                            }
                        }
                    }
                }, 2000);
            }
        }

        @Override
        public void onDisconnect(RemoteDisplay display) {
            Log.d(TAG, "onDisconnect: display.getId()=" + display.getId());

            if (display == mTestDisplay1 || display == mTestDisplay2
                    || display == mTestDisplay6 || display == mTestDisplay8
                    || display == mTestDisplay9 || display == mTestDisplay10) {
                display.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                updateDisplay(display);
            }
        }

        @Override
        public void onSetVolume(RemoteDisplay display, int volume) {
            Log.d(TAG, "onSetVolume: display.getId()=" + display.getId()
                    + ", volume=" + volume);

            if (display == mTestDisplay1) {
                display.setVolume(Math.max(0, Math.min(display.getVolumeMax(), volume)));
                updateDisplay(display);
            }
        }

        @Override
        public void onAdjustVolume(RemoteDisplay display, int delta) {
            Log.d(TAG, "onAdjustVolume: display.getId()=" + display.getId()
                    + ", delta=" + delta);

            if (display == mTestDisplay1) {
                display.setVolume(Math.max(0, Math.min(display.getVolumeMax(),
                        display .getVolume() + delta)));
                updateDisplay(display);
            }
        }

        @Override
        public void addDisplay(RemoteDisplay display) {
            Log.d(TAG, "addDisplay: display=" + display);
            super.addDisplay(display);
        }

        @Override
        public void removeDisplay(RemoteDisplay display) {
            Log.d(TAG, "removeDisplay: display=" + display);
            super.removeDisplay(display);
        }

        @Override
        public void updateDisplay(RemoteDisplay display) {
            Log.d(TAG, "updateDisplay: display=" + display);
            super.updateDisplay(display);
        }

        private final Runnable mBlink = new Runnable() {
            @Override
            public void run() {
                if (mTestDisplay7 == null) {
                    if (mBlinking) {
                        mTestDisplay7 = new RemoteDisplay("testDisplay7",
                                "Test Display 7 (blinky)");
                        mTestDisplay7.setDescription("Comes and goes but can't connect");
                        mTestDisplay7.setStatus(RemoteDisplay.STATUS_AVAILABLE);
                        addDisplay(mTestDisplay7);
                        mHandler.postDelayed(this, 7000);
                    }
                } else {
                    removeDisplay(mTestDisplay7);
                    mTestDisplay7 = null;
                    if (mBlinking) {
                        mHandler.postDelayed(this, 4000);
                    }
                }
            }
        };
    }
}
