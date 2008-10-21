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

package android.net;

/**
 * A UNIX-domain (AF_LOCAL) socket address. For use with
 * android.net.LocalSocket and android.net.LocalServerSocket.
 *
 * On the Android system, these names refer to names in the Linux
 * abstract (non-filesystem) UNIX domain namespace.
 */
public class LocalSocketAddress
{
    /**
     * The namespace that this address exists in. See also
     * include/cutils/sockets.h ANDROID_SOCKET_NAMESPACE_*
     */
    public enum Namespace {
        /** A socket in the Linux abstract namespace */
        ABSTRACT(0),
        /**
         * A socket in the Android reserved namespace in /dev/socket.
         * Only the init process may create a socket here.
         */
        RESERVED(1),
        /**
         * A socket named with a normal filesystem path.
         */
        FILESYSTEM(2);

        /** The id matches with a #define in include/cutils/sockets.h */
        private int id;
        Namespace (int id) {
            this.id = id;
        }

        /**
         * @return int constant shared with native code
         */
        /*package*/ int getId() {
            return id;
        }
    }

    private final String name;
    private final Namespace namespace;

    /**
     * Creates an instance with a given name.
     *
     * @param name non-null name
     * @param namespace namespace the name should be created in.
     */
    public LocalSocketAddress(String name, Namespace namespace) {
        this.name = name;
        this.namespace = namespace;
    }

    /**
     * Creates an instance with a given name in the {@link Namespace#ABSTRACT}
     * namespace
     *
     * @param name non-null name
     */
    public LocalSocketAddress(String name) {
        this(name,Namespace.ABSTRACT);
    }

    /**
     * Retrieves the string name of this address
     * @return string name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Returns the namespace used by this address.
     *
     * @return non-null a namespace
     */
    public Namespace getNamespace() {
        return namespace;
    }
}
