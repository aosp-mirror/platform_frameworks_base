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

import java.util.ArrayList;

import android.util.AndroidRuntimeException;
import android.util.Config;
import android.util.Log;

import com.android.internal.os.RuntimeInit;

/**
 * Low-level class holding the list of messages to be dispatched by a
 * {@link Looper}.  Messages are not added directly to a MessageQueue,
 * but rather through {@link Handler} objects associated with the Looper.
 * 
 * <p>You can retrieve the MessageQueue for the current thread with
 * {@link Looper#myQueue() Looper.myQueue()}.
 */
public class MessageQueue {
    Message mMessages;
    private final ArrayList mIdleHandlers = new ArrayList();
    private boolean mQuiting = false;
    boolean mQuitAllowed = true;
    
    /**
     * Callback interface for discovering when a thread is going to block
     * waiting for more messages.
     */
    public static interface IdleHandler {
        /**
         * Called when the message queue has run out of messages and will now
         * wait for more.  Return true to keep your idle handler active, false
         * to have it removed.  This may be called if there are still messages
         * pending in the queue, but they are all scheduled to be dispatched
         * after the current time.
         */
        boolean queueIdle();
    }

    /**
     * Add a new {@link IdleHandler} to this message queue.  This may be
     * removed automatically for you by returning false from
     * {@link IdleHandler#queueIdle IdleHandler.queueIdle()} when it is
     * invoked, or explicitly removing it with {@link #removeIdleHandler}.
     * 
     * <p>This method is safe to call from any thread.
     * 
     * @param handler The IdleHandler to be added.
     */
    public final void addIdleHandler(IdleHandler handler) {
        if (handler == null) {
            throw new NullPointerException("Can't add a null IdleHandler");
        }
        synchronized (this) {
            mIdleHandlers.add(handler);
        }
    }

    /**
     * Remove an {@link IdleHandler} from the queue that was previously added
     * with {@link #addIdleHandler}.  If the given object is not currently
     * in the idle list, nothing is done.
     * 
     * @param handler The IdleHandler to be removed.
     */
    public final void removeIdleHandler(IdleHandler handler) {
        synchronized (this) {
            mIdleHandlers.remove(handler);
        }
    }

    MessageQueue() {
    }

    final Message next() {
        boolean tryIdle = true;

        while (true) {
            long now;
            Object[] idlers = null;
    
            // Try to retrieve the next message, returning if found.
            synchronized (this) {
                now = SystemClock.uptimeMillis();
                Message msg = pullNextLocked(now);
                if (msg != null) return msg;
                if (tryIdle && mIdleHandlers.size() > 0) {
                    idlers = mIdleHandlers.toArray();
                }
            }
    
            // There was no message so we are going to wait...  but first,
            // if there are any idle handlers let them know.
            boolean didIdle = false;
            if (idlers != null) {
                for (Object idler : idlers) {
                    boolean keep = false;
                    try {
                        didIdle = true;
                        keep = ((IdleHandler)idler).queueIdle();
                    } catch (Throwable t) {
                        Log.wtf("MessageQueue", "IdleHandler threw exception", t);
                    }

                    if (!keep) {
                        synchronized (this) {
                            mIdleHandlers.remove(idler);
                        }
                    }
                }
            }
            
            // While calling an idle handler, a new message could have been
            // delivered...  so go back and look again for a pending message.
            if (didIdle) {
                tryIdle = false;
                continue;
            }

            synchronized (this) {
                // No messages, nobody to tell about it...  time to wait!
                try {
                    if (mMessages != null) {
                        if (mMessages.when-now > 0) {
                            Binder.flushPendingCommands();
                            this.wait(mMessages.when-now);
                        }
                    } else {
                        Binder.flushPendingCommands();
                        this.wait();
                    }
                }
                catch (InterruptedException e) {
                }
            }
        }
    }

    final Message pullNextLocked(long now) {
        Message msg = mMessages;
        if (msg != null) {
            if (now >= msg.when) {
                mMessages = msg.next;
                if (Config.LOGV) Log.v(
                    "MessageQueue", "Returning message: " + msg);
                return msg;
            }
        }

        return null;
    }

    final boolean enqueueMessage(Message msg, long when) {
        if (msg.when != 0) {
            throw new AndroidRuntimeException(msg
                    + " This message is already in use.");
        }
        if (msg.target == null && !mQuitAllowed) {
            throw new RuntimeException("Main thread not allowed to quit");
        }
        synchronized (this) {
            if (mQuiting) {
                RuntimeException e = new RuntimeException(
                    msg.target + " sending message to a Handler on a dead thread");
                Log.w("MessageQueue", e.getMessage(), e);
                return false;
            } else if (msg.target == null) {
                mQuiting = true;
            }

            msg.when = when;
            //Log.d("MessageQueue", "Enqueing: " + msg);
            Message p = mMessages;
            if (p == null || when == 0 || when < p.when) {
                msg.next = p;
                mMessages = msg;
                this.notify();
            } else {
                Message prev = null;
                while (p != null && p.when <= when) {
                    prev = p;
                    p = p.next;
                }
                msg.next = prev.next;
                prev.next = msg;
                this.notify();
            }
        }
        return true;
    }

    final boolean removeMessages(Handler h, int what, Object object,
            boolean doRemove) {
        synchronized (this) {
            Message p = mMessages;
            boolean found = false;

            // Remove all messages at front.
            while (p != null && p.target == h && p.what == what
                   && (object == null || p.obj == object)) {
                if (!doRemove) return true;
                found = true;
                Message n = p.next;
                mMessages = n;
                p.recycle();
                p = n;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.what == what
                        && (object == null || n.obj == object)) {
                        if (!doRemove) return true;
                        found = true;
                        Message nn = n.next;
                        n.recycle();
                        p.next = nn;
                        continue;
                    }
                }
                p = n;
            }
            
            return found;
        }
    }

    final void removeMessages(Handler h, Runnable r, Object object) {
        if (r == null) {
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.callback == r
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                p.recycle();
                p = n;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.callback == r
                        && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycle();
                        p.next = nn;
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    final void removeCallbacksAndMessages(Handler h, Object object) {
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h
                    && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                p.recycle();
                p = n;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycle();
                        p.next = nn;
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    /*
    private void dumpQueue_l()
    {
        Message p = mMessages;
        System.out.println(this + "  queue is:");
        while (p != null) {
            System.out.println("            " + p);
            p = p.next;
        }
    }
    */

    void poke()
    {
        synchronized (this) {
            this.notify();
        }
    }
}
