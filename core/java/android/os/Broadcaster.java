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

package android.os;

import android.compat.annotation.UnsupportedAppUsage;

/** @hide */
public class Broadcaster
{
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public Broadcaster()
    {
    }

    /**
     *  Sign up for notifications about something.
     *
     *  When this broadcaster pushes a message with senderWhat in the what field,
     *  target will be sent a copy of that message with targetWhat in the what field.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void request(int senderWhat, Handler target, int targetWhat)
    {
        synchronized (this) {
            Registration r = null;
            if (mReg == null) {
                r = new Registration();
                r.senderWhat = senderWhat;
                r.targets = new Handler[1];
                r.targetWhats = new int[1];
                r.targets[0] = target;
                r.targetWhats[0] = targetWhat;
                mReg = r;
                r.next = r;
                r.prev = r;
            } else {
                // find its place in the map
                Registration start = mReg;
                r = start;
                do {
                    if (r.senderWhat >= senderWhat) {
                        break;
                    }
                    r = r.next;
                } while (r != start);
                int n;
                if (r.senderWhat != senderWhat) {
                    // we didn't find a senderWhat match, but r is right
                    // after where it goes
                    Registration reg = new Registration();
                    reg.senderWhat = senderWhat;
                    reg.targets = new Handler[1];
                    reg.targetWhats = new int[1];
                    reg.next = r;
                    reg.prev = r.prev;
                    r.prev.next = reg;
                    r.prev = reg;

                    if (r == mReg && r.senderWhat > reg.senderWhat) {
                        mReg = reg;
                    }
                    
                    r = reg;
                    n = 0;
                } else {
                    n = r.targets.length;
                    Handler[] oldTargets = r.targets;
                    int[] oldWhats = r.targetWhats;
                    // check for duplicates, and don't do it if we are dup.
                    for (int i=0; i<n; i++) {
                        if (oldTargets[i] == target && oldWhats[i] == targetWhat) {
                            return;
                        }
                    }
                    r.targets = new Handler[n+1];
                    System.arraycopy(oldTargets, 0, r.targets, 0, n);
                    r.targetWhats = new int[n+1];
                    System.arraycopy(oldWhats, 0, r.targetWhats, 0, n);
                }
                r.targets[n] = target;
                r.targetWhats[n] = targetWhat;
            }
        }
    }
    
    /**
     * Unregister for notifications for this senderWhat/target/targetWhat tuple.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void cancelRequest(int senderWhat, Handler target, int targetWhat)
    {
        synchronized (this) {
            Registration start = mReg;
            Registration r = start;
            
            if (r == null) {
                return;
            }
            
            do {
                if (r.senderWhat >= senderWhat) {
                    break;
                }
                r = r.next;
            } while (r != start);
            
            if (r.senderWhat == senderWhat) {
                Handler[] targets = r.targets;
                int[] whats = r.targetWhats;
                int oldLen = targets.length;
                for (int i=0; i<oldLen; i++) {
                    if (targets[i] == target && whats[i] == targetWhat) {
                        r.targets = new Handler[oldLen-1];
                        r.targetWhats = new int[oldLen-1];
                        if (i > 0) {
                            System.arraycopy(targets, 0, r.targets, 0, i);
                            System.arraycopy(whats, 0, r.targetWhats, 0, i);
                        }

                        int remainingLen = oldLen-i-1;
                        if (remainingLen != 0) {
                            System.arraycopy(targets, i+1, r.targets, i,
                                    remainingLen);
                            System.arraycopy(whats, i+1, r.targetWhats, i,
                                    remainingLen);
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * For debugging purposes, print the registrations to System.out
     */
    public void dumpRegistrations()
    {
        synchronized (this) {
            Registration start = mReg;
            System.out.println("Broadcaster " + this + " {");
            if (start != null) {
                Registration r = start;
                do {
                    System.out.println("    senderWhat=" + r.senderWhat);
                    int n = r.targets.length;
                    for (int i=0; i<n; i++) {
                        System.out.println("        [" + r.targetWhats[i]
                                        + "] " + r.targets[i]);
                    }
                    r = r.next;
                } while (r != start);
            }
            System.out.println("}");
        }
    }

    /**
     * Send out msg.  Anyone who has registered via the request() method will be
     * sent the message.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void broadcast(Message msg)
    {
        synchronized (this) {
            if (mReg == null) {
                return;
            }
            
            int senderWhat = msg.what;
            Registration start = mReg;
            Registration r = start;
            do {
                if (r.senderWhat >= senderWhat) {
                    break;
                }
                r = r.next;
            } while (r != start);
            if (r.senderWhat == senderWhat) {
                Handler[] targets = r.targets;
                int[] whats = r.targetWhats;
                int n = targets.length;
                for (int i=0; i<n; i++) {
                    Handler target = targets[i];
                    Message m = Message.obtain();
                    m.copyFrom(msg);
                    m.what = whats[i];
                    target.sendMessage(m);
                }
            }
        }
    }

    private class Registration
    {
        Registration next;
        Registration prev;

        int senderWhat;
        Handler[] targets;
        int[] targetWhats;
    }
    private Registration mReg;
}
