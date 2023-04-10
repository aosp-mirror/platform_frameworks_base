/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package lockedregioncodeinjection;

/**
 * Represent a specific class that is used for synchronization. A pre and post method can be
 * specified to by the user to be called right after monitor_enter and after monitor_exit
 * respectively.
 */
public class LockTarget {
    public static final LockTarget NO_TARGET = new LockTarget("", null, null);

    // The lock which must be instrumented, in Java internal form (L<path>;).
    private final String targetDesc;
    // The methods to be called when the lock is taken (released).  For non-scoped locks,
    // these are fully qualified static methods.  For scoped locks, these are the
    // unqualified names of a member method of the target lock.
    private final String pre;
    private final String post;
    // If true, the pre and post methods are virtual on the target class.  The pre and post methods
    // are both called while the lock is held.  If this field is false then the pre and post methods
    // take no parameters and the post method is called after the lock is released.  This is legacy
    // behavior.
    private final boolean scoped;

    public LockTarget(String targetDesc, String pre, String post, boolean scoped) {
        this.targetDesc = targetDesc;
        this.pre = pre;
        this.post = post;
        this.scoped = scoped;
    }

    public LockTarget(String targetDesc, String pre, String post) {
        this(targetDesc, pre, post, false);
    }

    public String getTargetDesc() {
        return targetDesc;
    }

    public String getPre() {
        return pre;
    }

    public String getPreOwner() {
        if (scoped) {
            return targetDesc.substring(1, targetDesc.length() - 1);
        } else {
            return pre.substring(0, pre.lastIndexOf('.'));
        }
    }

    public String getPreMethod() {
        return pre.substring(pre.lastIndexOf('.') + 1);
    }

    public String getPost() {
        return post;
    }

    public String getPostOwner() {
        if (scoped) {
            return targetDesc.substring(1, targetDesc.length() - 1);
        } else {
            return post.substring(0, post.lastIndexOf('.'));
        }
    }

    public String getPostMethod() {
        return post.substring(post.lastIndexOf('.') + 1);
    }

    public boolean getScoped() {
        return scoped;
    }

    @Override
    public String toString() {
        return targetDesc + ":" + pre + ":" + post;
    }
}
