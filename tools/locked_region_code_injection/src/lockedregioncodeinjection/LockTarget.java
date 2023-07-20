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

    private final String targetDesc;
    private final String pre;
    private final String post;

    public LockTarget(String targetDesc, String pre, String post) {
        this.targetDesc = targetDesc;
        this.pre = pre;
        this.post = post;
    }

    public String getTargetDesc() {
        return targetDesc;
    }

    public String getPre() {
        return pre;
    }

    public String getPreOwner() {
        return pre.substring(0, pre.lastIndexOf('.'));
    }

    public String getPreMethod() {
        return pre.substring(pre.lastIndexOf('.') + 1);
    }

    public String getPost() {
        return post;
    }

    public String getPostOwner() {
        return post.substring(0, post.lastIndexOf('.'));
    }

    public String getPostMethod() {
        return post.substring(post.lastIndexOf('.') + 1);
    }
}
