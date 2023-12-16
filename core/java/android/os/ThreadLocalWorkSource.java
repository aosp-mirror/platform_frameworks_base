/*
 * Copyright (C) 2018 The Android Open Source Project
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

/**
 * Tracks who triggered the work currently executed on this thread.
 *
 * <p>ThreadLocalWorkSource is automatically updated inside system server for incoming/outgoing
 * binder calls and messages posted to handler threads.
 *
 * <p>ThreadLocalWorkSource can also be set manually if needed to refine the WorkSource.
 *
 * <p>Example:
 * <ul>
 * <li>Bluetooth process calls {@link PowerManager#isInteractive()} API on behalf of app foo.
 * <li>ThreadLocalWorkSource will be automatically set to the UID of foo.
 * <li>Any code on the thread handling {@link PowerManagerService#isInteractive()} can call
 * {@link ThreadLocalWorkSource#getUid()} to blame any resource used to handle this call.
 * <li>If a message is posted from the binder thread, the code handling the message can also call
 * {@link ThreadLocalWorkSource#getUid()} and it will return the UID of foo since the work source is
 * automatically propagated.
 * </ul>
 *
 * @hide Only for use within system server.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class ThreadLocalWorkSource {
    public static final int UID_NONE = Message.UID_NONE;
    private static final ThreadLocal<int []> sWorkSourceUid =
            ThreadLocal.withInitial(() -> new int[] {UID_NONE});

    /**
     * Returns the UID to blame for the code currently executed on this thread.
     *
     * <p>This UID is set automatically by common frameworks (e.g. Binder and Handler frameworks)
     * and automatically propagated inside system server.
     * <p>It can also be set manually using {@link #setUid(int)}.
     */
    public static int getUid() {
        return sWorkSourceUid.get()[0];
    }

    /**
     * Sets the UID to blame for the code currently executed on this thread.
     *
     * <p>Inside system server, this UID will be automatically propagated.
     * <p>It will be used to attribute future resources used on this thread (e.g. binder
     * transactions or processing handler messages) and on any other threads the UID is propagated
     * to.
     *
     * @return a token that can be used to restore the state.
     */
    public static long setUid(int uid) {
        final long token = getToken();
        sWorkSourceUid.get()[0] = uid;
        return token;
    }

    /**
     * Restores the state using the provided token.
     */
    public static void restore(long token) {
        sWorkSourceUid.get()[0] = parseUidFromToken(token);
    }

    /**
     * Clears the stored work source uid.
     *
     * <p>This method should be used when we do not know who to blame. If the UID to blame is the
     * UID of the current process, it is better to attribute the work to the current process
     * explicitly instead of clearing the work source:
     *
     * <pre>
     * ThreadLocalWorkSource.setUid(Process.myUid());
     * </pre>
     *
     * @return a token that can be used to restore the state.
     */
    public static long clear() {
        return setUid(UID_NONE);
    }

    private static int parseUidFromToken(long token) {
        return (int) token;
    }

    private static long getToken() {
        return sWorkSourceUid.get()[0];
    }

    private ThreadLocalWorkSource() {
    }
}
