/*
 * Copyright (C) 2011 The Android Open Source Project
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
package android.system;

import com.android.ravenwood.common.RavenwoodCommonUtils;

/**
 * Copied from libcore's version, with the local changes:
 * - All the imports are removed. (they're only used in javadoc)
 * - All the annotations are removed.
 * - The initConstants() method is moved to a nested class.
 *
 * TODO(b/340887115): Need a better integration with libcore.
 */

public class OsConstants {
//    @UnsupportedAppUsage
    private OsConstants() {
    }

    /**
     * Returns the index of the element in the {@link StructCapUserData} (cap_user_data)
     * array that this capability is stored in.
     *
     * @param x capability
     * @return index of the element in the {@link StructCapUserData} array storing this capability
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static int CAP_TO_INDEX(int x) { return x >>> 5; }

    /**
     * Returns the mask for the given capability. This is relative to the capability's
     * {@link StructCapUserData} (cap_user_data) element, the index of which can be
     * retrieved with {@link CAP_TO_INDEX}.
     *
     * @param x capability
     * @return mask for given capability
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static int CAP_TO_MASK(int x) { return 1 << (x & 31); }

    /**
     * Tests whether the given mode is a block device.
     */
    public static boolean S_ISBLK(int mode) { return (mode & S_IFMT) == S_IFBLK; }

    /**
     * Tests whether the given mode is a character device.
     */
    public static boolean S_ISCHR(int mode) { return (mode & S_IFMT) == S_IFCHR; }

    /**
     * Tests whether the given mode is a directory.
     */
    public static boolean S_ISDIR(int mode) { return (mode & S_IFMT) == S_IFDIR; }

    /**
     * Tests whether the given mode is a FIFO.
     */
    public static boolean S_ISFIFO(int mode) { return (mode & S_IFMT) == S_IFIFO; }

    /**
     * Tests whether the given mode is a regular file.
     */
    public static boolean S_ISREG(int mode) { return (mode & S_IFMT) == S_IFREG; }

    /**
     * Tests whether the given mode is a symbolic link.
     */
    public static boolean S_ISLNK(int mode) { return (mode & S_IFMT) == S_IFLNK; }

    /**
     * Tests whether the given mode is a socket.
     */
    public static boolean S_ISSOCK(int mode) { return (mode & S_IFMT) == S_IFSOCK; }

    /**
     * Extracts the exit status of a child. Only valid if WIFEXITED returns true.
     */
    public static int WEXITSTATUS(int status) { return (status & 0xff00) >> 8; }

    /**
     * Tests whether the child dumped core. Only valid if WIFSIGNALED returns true.
     */
    public static boolean WCOREDUMP(int status) { return (status & 0x80) != 0; }

    /**
     * Returns the signal that caused the child to exit. Only valid if WIFSIGNALED returns true.
     */
    public static int WTERMSIG(int status) { return status & 0x7f; }

    /**
     * Returns the signal that cause the child to stop. Only valid if WIFSTOPPED returns true.
     */
    public static int WSTOPSIG(int status) { return WEXITSTATUS(status); }

    /**
     * Tests whether the child exited normally.
     */
    public static boolean WIFEXITED(int status) { return (WTERMSIG(status) == 0); }

    /**
     * Tests whether the child was stopped (not terminated) by a signal.
     */
    public static boolean WIFSTOPPED(int status) { return (WTERMSIG(status) == 0x7f); }

    /**
     * Tests whether the child was terminated by a signal.
     */
    public static boolean WIFSIGNALED(int status) { return (WTERMSIG(status + 1) >= 2); }

    public static final int AF_INET = placeholder();
    public static final int AF_INET6 = placeholder();
    public static final int AF_NETLINK = placeholder();
    public static final int AF_PACKET = placeholder();
    public static final int AF_UNIX = placeholder();

    /**
     * The virt-vsock address family, linux specific.
     * It is used with {@code struct sockaddr_vm} from uapi/linux/vm_sockets.h.
     *
     * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
     * @see VmSocketAddress
     */
    public static final int AF_VSOCK = placeholder();
    public static final int AF_UNSPEC = placeholder();
    public static final int AI_ADDRCONFIG = placeholder();
    public static final int AI_ALL = placeholder();
    public static final int AI_CANONNAME = placeholder();
    public static final int AI_NUMERICHOST = placeholder();
    public static final int AI_NUMERICSERV = placeholder();
    public static final int AI_PASSIVE = placeholder();
    public static final int AI_V4MAPPED = placeholder();
    public static final int ARPHRD_ETHER = placeholder();

    /**
     * The virtio-vsock {@code svmPort} value to bind for any available port.
     *
     * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
     * @see VmSocketAddress
     */
    public static final int VMADDR_PORT_ANY = placeholder();

    /**
     * The virtio-vsock {@code svmCid} value to listens for all CIDs.
     *
     * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
     * @see VmSocketAddress
     */
    public static final int VMADDR_CID_ANY = placeholder();

    /**
     * The virtio-vsock {@code svmCid} value for host communication.
     *
     * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
     * @see VmSocketAddress
     */
    public static final int VMADDR_CID_LOCAL = placeholder();

    /**
     * The virtio-vsock {@code svmCid} value for loopback communication.
     *
     * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
     * @see VmSocketAddress
     */
    public static final int VMADDR_CID_HOST = placeholder();

    /**
     * ARP protocol loopback device identifier.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int ARPHRD_LOOPBACK = placeholder();
    public static final int CAP_AUDIT_CONTROL = placeholder();
    public static final int CAP_AUDIT_WRITE = placeholder();
    public static final int CAP_BLOCK_SUSPEND = placeholder();
    public static final int CAP_CHOWN = placeholder();
    public static final int CAP_DAC_OVERRIDE = placeholder();
    public static final int CAP_DAC_READ_SEARCH = placeholder();
    public static final int CAP_FOWNER = placeholder();
    public static final int CAP_FSETID = placeholder();
    public static final int CAP_IPC_LOCK = placeholder();
    public static final int CAP_IPC_OWNER = placeholder();
    public static final int CAP_KILL = placeholder();
    public static final int CAP_LAST_CAP = placeholder();
    public static final int CAP_LEASE = placeholder();
    public static final int CAP_LINUX_IMMUTABLE = placeholder();
    public static final int CAP_MAC_ADMIN = placeholder();
    public static final int CAP_MAC_OVERRIDE = placeholder();
    public static final int CAP_MKNOD = placeholder();
    public static final int CAP_NET_ADMIN = placeholder();
    public static final int CAP_NET_BIND_SERVICE = placeholder();
    public static final int CAP_NET_BROADCAST = placeholder();
    public static final int CAP_NET_RAW = placeholder();
    public static final int CAP_SETFCAP = placeholder();
    public static final int CAP_SETGID = placeholder();
    public static final int CAP_SETPCAP = placeholder();
    public static final int CAP_SETUID = placeholder();
    public static final int CAP_SYS_ADMIN = placeholder();
    public static final int CAP_SYS_BOOT = placeholder();
    public static final int CAP_SYS_CHROOT = placeholder();
    public static final int CAP_SYSLOG = placeholder();
    public static final int CAP_SYS_MODULE = placeholder();
    public static final int CAP_SYS_NICE = placeholder();
    public static final int CAP_SYS_PACCT = placeholder();
    public static final int CAP_SYS_PTRACE = placeholder();
    public static final int CAP_SYS_RAWIO = placeholder();
    public static final int CAP_SYS_RESOURCE = placeholder();
    public static final int CAP_SYS_TIME = placeholder();
    public static final int CAP_SYS_TTY_CONFIG = placeholder();
    public static final int CAP_WAKE_ALARM = placeholder();
    public static final int E2BIG = placeholder();
    public static final int EACCES = placeholder();
    public static final int EADDRINUSE = placeholder();
    public static final int EADDRNOTAVAIL = placeholder();
    public static final int EAFNOSUPPORT = placeholder();
    public static final int EAGAIN = placeholder();
    public static final int EAI_AGAIN = placeholder();
    public static final int EAI_BADFLAGS = placeholder();
    public static final int EAI_FAIL = placeholder();
    public static final int EAI_FAMILY = placeholder();
    public static final int EAI_MEMORY = placeholder();
    public static final int EAI_NODATA = placeholder();
    public static final int EAI_NONAME = placeholder();
    public static final int EAI_OVERFLOW = placeholder();
    public static final int EAI_SERVICE = placeholder();
    public static final int EAI_SOCKTYPE = placeholder();
    public static final int EAI_SYSTEM = placeholder();
    public static final int EALREADY = placeholder();
    public static final int EBADF = placeholder();
    public static final int EBADMSG = placeholder();
    public static final int EBUSY = placeholder();
    public static final int ECANCELED = placeholder();
    public static final int ECHILD = placeholder();
    public static final int ECONNABORTED = placeholder();
    public static final int ECONNREFUSED = placeholder();
    public static final int ECONNRESET = placeholder();
    public static final int EDEADLK = placeholder();
    public static final int EDESTADDRREQ = placeholder();
    public static final int EDOM = placeholder();
    public static final int EDQUOT = placeholder();
    public static final int EEXIST = placeholder();
    public static final int EFAULT = placeholder();
    public static final int EFBIG = placeholder();
    public static final int EHOSTUNREACH = placeholder();
    public static final int EIDRM = placeholder();
    public static final int EILSEQ = placeholder();
    public static final int EINPROGRESS = placeholder();
    public static final int EINTR = placeholder();
    public static final int EINVAL = placeholder();
    public static final int EIO = placeholder();
    public static final int EISCONN = placeholder();
    public static final int EISDIR = placeholder();
    public static final int ELOOP = placeholder();
    public static final int EMFILE = placeholder();
    public static final int EMLINK = placeholder();
    public static final int EMSGSIZE = placeholder();
    public static final int EMULTIHOP = placeholder();
    public static final int ENAMETOOLONG = placeholder();
    public static final int ENETDOWN = placeholder();
    public static final int ENETRESET = placeholder();
    public static final int ENETUNREACH = placeholder();
    public static final int ENFILE = placeholder();
    public static final int ENOBUFS = placeholder();
    public static final int ENODATA = placeholder();
    public static final int ENODEV = placeholder();
    public static final int ENOENT = placeholder();
    public static final int ENOEXEC = placeholder();
    public static final int ENOLCK = placeholder();
    public static final int ENOLINK = placeholder();
    public static final int ENOMEM = placeholder();
    public static final int ENOMSG = placeholder();
    public static final int ENONET = placeholder();
    public static final int ENOPROTOOPT = placeholder();
    public static final int ENOSPC = placeholder();
    public static final int ENOSR = placeholder();
    public static final int ENOSTR = placeholder();
    public static final int ENOSYS = placeholder();
    public static final int ENOTCONN = placeholder();
    public static final int ENOTDIR = placeholder();
    public static final int ENOTEMPTY = placeholder();
    public static final int ENOTSOCK = placeholder();
    public static final int ENOTSUP = placeholder();
    public static final int ENOTTY = placeholder();
    public static final int ENXIO = placeholder();
    public static final int EOPNOTSUPP = placeholder();
    public static final int EOVERFLOW = placeholder();
    public static final int EPERM = placeholder();
    public static final int EPIPE = placeholder();
    public static final int EPROTO = placeholder();
    public static final int EPROTONOSUPPORT = placeholder();
    public static final int EPROTOTYPE = placeholder();
    public static final int ERANGE = placeholder();
    public static final int EROFS = placeholder();
    public static final int ESPIPE = placeholder();
    public static final int ESRCH = placeholder();
    public static final int ESTALE = placeholder();
    public static final int ETH_P_ALL = placeholder();
    public static final int ETH_P_ARP = placeholder();
    public static final int ETH_P_IP = placeholder();
    public static final int ETH_P_IPV6 = placeholder();
    public static final int ETIME = placeholder();
    public static final int ETIMEDOUT = placeholder();
    public static final int ETXTBSY = placeholder();
    /**
     * "Too many users" error.
     * See <a href="https://man7.org/linux/man-pages/man3/errno.3.html">errno(3)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int EUSERS = placeholder();
    // On Linux, EWOULDBLOCK == EAGAIN. Use EAGAIN instead, to reduce confusion.
    public static final int EXDEV = placeholder();
    public static final int EXIT_FAILURE = placeholder();
    public static final int EXIT_SUCCESS = placeholder();
    public static final int FD_CLOEXEC = placeholder();
    public static final int FIONREAD = placeholder();
    public static final int F_DUPFD = placeholder();
    public static final int F_DUPFD_CLOEXEC = placeholder();
    public static final int F_GETFD = placeholder();
    public static final int F_GETFL = placeholder();
    public static final int F_GETLK = placeholder();
    public static final int F_GETLK64 = placeholder();
    public static final int F_GETOWN = placeholder();
    public static final int F_OK = placeholder();
    public static final int F_RDLCK = placeholder();
    public static final int F_SETFD = placeholder();
    public static final int F_SETFL = placeholder();
    public static final int F_SETLK = placeholder();
    public static final int F_SETLK64 = placeholder();
    public static final int F_SETLKW = placeholder();
    public static final int F_SETLKW64 = placeholder();
    public static final int F_SETOWN = placeholder();
    public static final int F_UNLCK = placeholder();
    public static final int F_WRLCK = placeholder();
    public static final int ICMP_ECHO = placeholder();
    public static final int ICMP_ECHOREPLY = placeholder();
    public static final int ICMP6_ECHO_REQUEST = placeholder();
    public static final int ICMP6_ECHO_REPLY = placeholder();
    public static final int IFA_F_DADFAILED = placeholder();
    public static final int IFA_F_DEPRECATED = placeholder();
    public static final int IFA_F_HOMEADDRESS = placeholder();
    public static final int IFA_F_MANAGETEMPADDR = placeholder();
    public static final int IFA_F_NODAD = placeholder();
    public static final int IFA_F_NOPREFIXROUTE = placeholder();
    public static final int IFA_F_OPTIMISTIC = placeholder();
    public static final int IFA_F_PERMANENT = placeholder();
    public static final int IFA_F_SECONDARY = placeholder();
    public static final int IFA_F_TEMPORARY = placeholder();
    public static final int IFA_F_TENTATIVE = placeholder();
    public static final int IFF_ALLMULTI = placeholder();
    public static final int IFF_AUTOMEDIA = placeholder();
    public static final int IFF_BROADCAST = placeholder();
    public static final int IFF_DEBUG = placeholder();
    public static final int IFF_DYNAMIC = placeholder();
    public static final int IFF_LOOPBACK = placeholder();
    public static final int IFF_MASTER = placeholder();
    public static final int IFF_MULTICAST = placeholder();
    public static final int IFF_NOARP = placeholder();
    public static final int IFF_NOTRAILERS = placeholder();
    public static final int IFF_POINTOPOINT = placeholder();
    public static final int IFF_PORTSEL = placeholder();
    public static final int IFF_PROMISC = placeholder();
    public static final int IFF_RUNNING = placeholder();
    public static final int IFF_SLAVE = placeholder();
    public static final int IFF_UP = placeholder();
    public static final int IPPROTO_ICMP = placeholder();
    public static final int IPPROTO_ICMPV6 = placeholder();
    public static final int IPPROTO_IP = placeholder();
    public static final int IPPROTO_IPV6 = placeholder();
    public static final int IPPROTO_RAW = placeholder();
    public static final int IPPROTO_TCP = placeholder();
    public static final int IPPROTO_UDP = placeholder();

    /**
     * Encapsulation Security Payload protocol
     *
     * <p>Defined in /uapi/linux/in.h
     */
    public static final int IPPROTO_ESP = placeholder();

    public static final int IPV6_CHECKSUM = placeholder();
    public static final int IPV6_MULTICAST_HOPS = placeholder();
    public static final int IPV6_MULTICAST_IF = placeholder();
    public static final int IPV6_MULTICAST_LOOP = placeholder();
    public static final int IPV6_PKTINFO = placeholder();
    public static final int IPV6_RECVDSTOPTS = placeholder();
    public static final int IPV6_RECVHOPLIMIT = placeholder();
    public static final int IPV6_RECVHOPOPTS = placeholder();
    public static final int IPV6_RECVPKTINFO = placeholder();
    public static final int IPV6_RECVRTHDR = placeholder();
    public static final int IPV6_RECVTCLASS = placeholder();
    public static final int IPV6_TCLASS = placeholder();
    public static final int IPV6_UNICAST_HOPS = placeholder();
    public static final int IPV6_V6ONLY = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int IP_MULTICAST_ALL = placeholder();
    public static final int IP_MULTICAST_IF = placeholder();
    public static final int IP_MULTICAST_LOOP = placeholder();
    public static final int IP_MULTICAST_TTL = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int IP_RECVTOS = placeholder();
    public static final int IP_TOS = placeholder();
    public static final int IP_TTL = placeholder();
    /**
     * Version constant to be used in {@link StructCapUserHeader} with
     * {@link Os#capset(StructCapUserHeader, StructCapUserData[])} and
     * {@link Os#capget(StructCapUserHeader)}.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/capget.2.html">capget(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int _LINUX_CAPABILITY_VERSION_3 = placeholder();
    public static final int MAP_FIXED = placeholder();
    public static final int MAP_ANONYMOUS = placeholder();
    /**
     * Flag argument for {@code mmap(long, long, int, int, FileDescriptor, long)}.
     *
     * See <a href="http://man7.org/linux/man-pages/man2/mmap.2.html">mmap(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int MAP_POPULATE = placeholder();
    public static final int MAP_PRIVATE = placeholder();
    public static final int MAP_SHARED = placeholder();
    public static final int MCAST_JOIN_GROUP = placeholder();
    public static final int MCAST_LEAVE_GROUP = placeholder();
    public static final int MCAST_JOIN_SOURCE_GROUP = placeholder();
    public static final int MCAST_LEAVE_SOURCE_GROUP = placeholder();
    public static final int MCAST_BLOCK_SOURCE = placeholder();
    public static final int MCAST_UNBLOCK_SOURCE = placeholder();
    public static final int MCL_CURRENT = placeholder();
    public static final int MCL_FUTURE = placeholder();
    public static final int MFD_CLOEXEC = placeholder();
    public static final int MSG_CTRUNC = placeholder();
    public static final int MSG_DONTROUTE = placeholder();
    public static final int MSG_EOR = placeholder();
    public static final int MSG_OOB = placeholder();
    public static final int MSG_PEEK = placeholder();
    public static final int MSG_TRUNC = placeholder();
    public static final int MSG_WAITALL = placeholder();
    public static final int MS_ASYNC = placeholder();
    public static final int MS_INVALIDATE = placeholder();
    public static final int MS_SYNC = placeholder();
    public static final int NETLINK_NETFILTER = placeholder();
    public static final int NETLINK_ROUTE = placeholder();
    /**
     * SELinux enforces that only system_server and netd may use this netlink socket type.
     */
    public static final int NETLINK_INET_DIAG = placeholder();

    /**
     * SELinux enforces that only system_server and netd may use this netlink socket type.
     *
     * @see <a href="https://man7.org/linux/man-pages/man7/netlink.7.html">netlink(7)</a>
     */
    public static final int NETLINK_XFRM = placeholder();

    public static final int NI_DGRAM = placeholder();
    public static final int NI_NAMEREQD = placeholder();
    public static final int NI_NOFQDN = placeholder();
    public static final int NI_NUMERICHOST = placeholder();
    public static final int NI_NUMERICSERV = placeholder();
    public static final int O_ACCMODE = placeholder();
    public static final int O_APPEND = placeholder();
    public static final int O_CLOEXEC = placeholder();
    public static final int O_CREAT = placeholder();
    /**
     * Flag for {@code Os#open(String, int, int)}.
     *
     * When enabled, tries to minimize cache effects of the I/O to and from this
     * file. In general this will degrade performance, but it is
     * useful in special situations, such as when applications do
     * their own caching. File I/O is done directly to/from
     * user-space buffers. The {@link O_DIRECT} flag on its own makes an
     * effort to transfer data synchronously, but does not give
     * the guarantees of the {@link O_SYNC} flag that data and necessary
     * metadata are transferred. To guarantee synchronous I/O,
     * {@link O_SYNC} must be used in addition to {@link O_DIRECT}.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/open.2.html">open(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int O_DIRECT = placeholder();
    public static final int O_EXCL = placeholder();
    public static final int O_NOCTTY = placeholder();
    public static final int O_NOFOLLOW = placeholder();
    public static final int O_NONBLOCK = placeholder();
    public static final int O_RDONLY = placeholder();
    public static final int O_RDWR = placeholder();
    public static final int O_SYNC = placeholder();
    public static final int O_DSYNC = placeholder();
    public static final int O_TRUNC = placeholder();
    public static final int O_WRONLY = placeholder();
    public static final int POLLERR = placeholder();
    public static final int POLLHUP = placeholder();
    public static final int POLLIN = placeholder();
    public static final int POLLNVAL = placeholder();
    public static final int POLLOUT = placeholder();
    public static final int POLLPRI = placeholder();
    public static final int POLLRDBAND = placeholder();
    public static final int POLLRDNORM = placeholder();
    public static final int POLLWRBAND = placeholder();
    public static final int POLLWRNORM = placeholder();
    /**
     * Reads or changes the ambient capability set of the calling thread.
     * Has to be used as a first argument for {@link Os#prctl(int, long, long, long, long)}.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/prctl.2.html">prctl(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PR_CAP_AMBIENT = placeholder();
    /**
     * The capability specified in {@code arg3} of {@link Os#prctl(int, long, long, long, long)}
     * is added to the ambient set. The specified capability must already
     * be present in both the permitted and the inheritable sets of the process.
     * Has to be used as a second argument for {@link Os#prctl(int, long, long, long, long)}.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/prctl.2.html">prctl(2)</a>.
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PR_CAP_AMBIENT_RAISE = placeholder();
    public static final int PR_GET_DUMPABLE = placeholder();
    public static final int PR_SET_DUMPABLE = placeholder();
    public static final int PR_SET_NO_NEW_PRIVS = placeholder();
    public static final int PROT_EXEC = placeholder();
    public static final int PROT_NONE = placeholder();
    public static final int PROT_READ = placeholder();
    public static final int PROT_WRITE = placeholder();
    public static final int R_OK = placeholder();
    /**
     * Specifies a value one greater than the maximum file
     * descriptor number that can be opened by this process.
     *
     * <p>Attempts ({@link Os#open(String, int, int)}, {@link Os#pipe()},
     * {@link Os#dup(java.io.FileDescriptor)}, etc.) to exceed this
     * limit yield the error {@link EMFILE}.
     *
     * See <a href="https://man7.org/linux/man-pages/man3/vlimit.3.html">getrlimit(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int RLIMIT_NOFILE = placeholder();
    public static final int RT_SCOPE_HOST = placeholder();
    public static final int RT_SCOPE_LINK = placeholder();
    public static final int RT_SCOPE_NOWHERE = placeholder();
    public static final int RT_SCOPE_SITE = placeholder();
    public static final int RT_SCOPE_UNIVERSE = placeholder();
    /**
     * Bitmask for IPv4 addresses add/delete events multicast groups mask.
     * Used in {@link NetlinkSocketAddress}.
     *
     * See <a href="https://man7.org/linux/man-pages/man7/netlink.7.html">netlink(7)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int RTMGRP_IPV4_IFADDR = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_IPV4_MROUTE = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_IPV4_ROUTE = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_IPV4_RULE = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_IFADDR = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_IFINFO = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_MROUTE = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_PREFIX = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_ROUTE = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_LINK = placeholder();
    public static final int RTMGRP_NEIGH = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_NOTIFY = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int RTMGRP_TC = placeholder();
    public static final int SEEK_CUR = placeholder();
    public static final int SEEK_END = placeholder();
    public static final int SEEK_SET = placeholder();
    public static final int SHUT_RD = placeholder();
    public static final int SHUT_RDWR = placeholder();
    public static final int SHUT_WR = placeholder();
    public static final int SIGABRT = placeholder();
    public static final int SIGALRM = placeholder();
    public static final int SIGBUS = placeholder();
    public static final int SIGCHLD = placeholder();
    public static final int SIGCONT = placeholder();
    public static final int SIGFPE = placeholder();
    public static final int SIGHUP = placeholder();
    public static final int SIGILL = placeholder();
    public static final int SIGINT = placeholder();
    public static final int SIGIO = placeholder();
    public static final int SIGKILL = placeholder();
    public static final int SIGPIPE = placeholder();
    public static final int SIGPROF = placeholder();
    public static final int SIGPWR = placeholder();
    public static final int SIGQUIT = placeholder();
    public static final int SIGRTMAX = placeholder();
    public static final int SIGRTMIN = placeholder();
    public static final int SIGSEGV = placeholder();
    public static final int SIGSTKFLT = placeholder();
    public static final int SIGSTOP = placeholder();
    public static final int SIGSYS = placeholder();
    public static final int SIGTERM = placeholder();
    public static final int SIGTRAP = placeholder();
    public static final int SIGTSTP = placeholder();
    public static final int SIGTTIN = placeholder();
    public static final int SIGTTOU = placeholder();
    public static final int SIGURG = placeholder();
    public static final int SIGUSR1 = placeholder();
    public static final int SIGUSR2 = placeholder();
    public static final int SIGVTALRM = placeholder();
    public static final int SIGWINCH = placeholder();
    public static final int SIGXCPU = placeholder();
    public static final int SIGXFSZ = placeholder();
    public static final int SIOCGIFADDR = placeholder();
    public static final int SIOCGIFBRDADDR = placeholder();
    public static final int SIOCGIFDSTADDR = placeholder();
    public static final int SIOCGIFNETMASK = placeholder();

    /**
     * Set the close-on-exec ({@code FD_CLOEXEC}) flag on the new file
     * descriptor created by {@link Os#socket(int,int,int)} or
     * {@link Os#socketpair(int,int,int,java.io.FileDescriptor,java.io.FileDescriptor)}.
     * See the description of the O_CLOEXEC flag in
     * <a href="http://man7.org/linux/man-pages/man2/open.2.html">open(2)</a>
     * for reasons why this may be useful.
     *
     * <p>Applications wishing to make use of this flag on older API versions
     * may use {@link #O_CLOEXEC} instead. On Android, {@code O_CLOEXEC} and
     * {@code SOCK_CLOEXEC} are the same value.
     */
    public static final int SOCK_CLOEXEC = placeholder();
    public static final int SOCK_DGRAM = placeholder();

    /**
     * Set the O_NONBLOCK file status flag on the file descriptor
     * created by {@link Os#socket(int,int,int)} or
     * {@link Os#socketpair(int,int,int,java.io.FileDescriptor,java.io.FileDescriptor)}.
     *
     * <p>Applications wishing to make use of this flag on older API versions
     * may use {@link #O_NONBLOCK} instead. On Android, {@code O_NONBLOCK}
     * and {@code SOCK_NONBLOCK} are the same value.
     */
    public static final int SOCK_NONBLOCK = placeholder();
    public static final int SOCK_RAW = placeholder();
    public static final int SOCK_SEQPACKET = placeholder();
    public static final int SOCK_STREAM = placeholder();
    public static final int SOL_SOCKET = placeholder();
    public static final int SOL_UDP = placeholder();
    public static final int SOL_PACKET = placeholder();
    public static final int SO_BINDTODEVICE = placeholder();
    public static final int SO_BROADCAST = placeholder();
    public static final int SO_DEBUG = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int SO_DOMAIN = placeholder();
    public static final int SO_DONTROUTE = placeholder();
    public static final int SO_ERROR = placeholder();
    public static final int SO_KEEPALIVE = placeholder();
    public static final int SO_LINGER = placeholder();
    public static final int SO_OOBINLINE = placeholder();
    public static final int SO_PASSCRED = placeholder();
    public static final int SO_PEERCRED = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int SO_PROTOCOL = placeholder();
    public static final int SO_RCVBUF = placeholder();
    public static final int SO_RCVLOWAT = placeholder();
    public static final int SO_RCVTIMEO = placeholder();
    public static final int SO_REUSEADDR = placeholder();
    public static final int SO_SNDBUF = placeholder();
    public static final int SO_SNDLOWAT = placeholder();
    public static final int SO_SNDTIMEO = placeholder();
    public static final int SO_TYPE = placeholder();
    public static final int PACKET_IGNORE_OUTGOING = placeholder();
    /**
     * Bitmask for flags argument of
     * {@link splice(java.io.FileDescriptor, Int64Ref , FileDescriptor, Int64Ref, long, int)}.
     *
     * Attempt to move pages instead of copying.  This is only a
     * hint to the kernel: pages may still be copied if the
     * kernel cannot move the pages from the pipe, or if the pipe
     * buffers don't refer to full pages.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/splice.2.html">splice(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int SPLICE_F_MOVE = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int SPLICE_F_NONBLOCK = placeholder();
    /**
     * Bitmask for flags argument of
     * {@link splice(java.io.FileDescriptor, Int64Ref, FileDescriptor, Int64Ref, long, int)}.
     *
     * <p>Indicates that more data will be coming in a subsequent splice. This is
     * a helpful hint when the {@code fdOut} refers to a socket.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/splice.2.html">splice(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int SPLICE_F_MORE = placeholder();
    public static final int STDERR_FILENO = placeholder();
    public static final int STDIN_FILENO = placeholder();
    public static final int STDOUT_FILENO = placeholder();
    public static final int ST_MANDLOCK = placeholder();
    public static final int ST_NOATIME = placeholder();
    public static final int ST_NODEV = placeholder();
    public static final int ST_NODIRATIME = placeholder();
    public static final int ST_NOEXEC = placeholder();
    public static final int ST_NOSUID = placeholder();
    public static final int ST_RDONLY = placeholder();
    public static final int ST_RELATIME = placeholder();
    public static final int ST_SYNCHRONOUS = placeholder();
    public static final int S_IFBLK = placeholder();
    public static final int S_IFCHR = placeholder();
    public static final int S_IFDIR = placeholder();
    public static final int S_IFIFO = placeholder();
    public static final int S_IFLNK = placeholder();
    public static final int S_IFMT = placeholder();
    public static final int S_IFREG = placeholder();
    public static final int S_IFSOCK = placeholder();
    public static final int S_IRGRP = placeholder();
    public static final int S_IROTH = placeholder();
    public static final int S_IRUSR = placeholder();
    public static final int S_IRWXG = placeholder();
    public static final int S_IRWXO = placeholder();
    public static final int S_IRWXU = placeholder();
    public static final int S_ISGID = placeholder();
    public static final int S_ISUID = placeholder();
    public static final int S_ISVTX = placeholder();
    public static final int S_IWGRP = placeholder();
    public static final int S_IWOTH = placeholder();
    public static final int S_IWUSR = placeholder();
    public static final int S_IXGRP = placeholder();
    public static final int S_IXOTH = placeholder();
    public static final int S_IXUSR = placeholder();
    public static final int TCP_NODELAY = placeholder();
    public static final int TCP_USER_TIMEOUT = placeholder();
    public static final int UDP_GRO = placeholder();
    public static final int UDP_SEGMENT = placeholder();
    /**
     * Get the number of bytes in the output buffer.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/ioctl.2.html">ioctl(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int TIOCOUTQ = placeholder();
    /**
     * Sockopt option to encapsulate ESP packets in UDP.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int UDP_ENCAP = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int UDP_ENCAP_ESPINUDP_NON_IKE = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int UDP_ENCAP_ESPINUDP = placeholder();
    /** @hide */
//    @UnsupportedAppUsage
    public static final int UNIX_PATH_MAX = placeholder();
    public static final int WCONTINUED = placeholder();
    public static final int WEXITED = placeholder();
    public static final int WNOHANG = placeholder();
    public static final int WNOWAIT = placeholder();
    public static final int WSTOPPED = placeholder();
    public static final int WUNTRACED = placeholder();
    public static final int W_OK = placeholder();
    /**
     * {@code flags} option for {@link Os#setxattr(String, String, byte[], int)}.
     *
     * <p>Performs a pure create, which fails if the named attribute exists already.
     *
     * See <a href="http://man7.org/linux/man-pages/man2/setxattr.2.html">setxattr(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int XATTR_CREATE = placeholder();
    /**
     * {@code flags} option for {@link Os#setxattr(String, String, byte[], int)}.
     *
     * <p>Perform a pure replace operation, which fails if the named attribute
     * does not already exist.
     *
     * See <a href="http://man7.org/linux/man-pages/man2/setxattr.2.html">setxattr(2)</a>.
     *
     * @hide
     */
//    @UnsupportedAppUsage
//    @SystemApi(client = MODULE_LIBRARIES)
    public static final int XATTR_REPLACE = placeholder();
    public static final int X_OK = placeholder();
    public static final int _SC_2_CHAR_TERM = placeholder();
    public static final int _SC_2_C_BIND = placeholder();
    public static final int _SC_2_C_DEV = placeholder();
    public static final int _SC_2_C_VERSION = placeholder();
    public static final int _SC_2_FORT_DEV = placeholder();
    public static final int _SC_2_FORT_RUN = placeholder();
    public static final int _SC_2_LOCALEDEF = placeholder();
    public static final int _SC_2_SW_DEV = placeholder();
    public static final int _SC_2_UPE = placeholder();
    public static final int _SC_2_VERSION = placeholder();
    public static final int _SC_AIO_LISTIO_MAX = placeholder();
    public static final int _SC_AIO_MAX = placeholder();
    public static final int _SC_AIO_PRIO_DELTA_MAX = placeholder();
    public static final int _SC_ARG_MAX = placeholder();
    public static final int _SC_ASYNCHRONOUS_IO = placeholder();
    public static final int _SC_ATEXIT_MAX = placeholder();
    public static final int _SC_AVPHYS_PAGES = placeholder();
    public static final int _SC_BC_BASE_MAX = placeholder();
    public static final int _SC_BC_DIM_MAX = placeholder();
    public static final int _SC_BC_SCALE_MAX = placeholder();
    public static final int _SC_BC_STRING_MAX = placeholder();
    public static final int _SC_CHILD_MAX = placeholder();
    public static final int _SC_CLK_TCK = placeholder();
    public static final int _SC_COLL_WEIGHTS_MAX = placeholder();
    public static final int _SC_DELAYTIMER_MAX = placeholder();
    public static final int _SC_EXPR_NEST_MAX = placeholder();
    public static final int _SC_FSYNC = placeholder();
    public static final int _SC_GETGR_R_SIZE_MAX = placeholder();
    public static final int _SC_GETPW_R_SIZE_MAX = placeholder();
    public static final int _SC_IOV_MAX = placeholder();
    public static final int _SC_JOB_CONTROL = placeholder();
    public static final int _SC_LINE_MAX = placeholder();
    public static final int _SC_LOGIN_NAME_MAX = placeholder();
    public static final int _SC_MAPPED_FILES = placeholder();
    public static final int _SC_MEMLOCK = placeholder();
    public static final int _SC_MEMLOCK_RANGE = placeholder();
    public static final int _SC_MEMORY_PROTECTION = placeholder();
    public static final int _SC_MESSAGE_PASSING = placeholder();
    public static final int _SC_MQ_OPEN_MAX = placeholder();
    public static final int _SC_MQ_PRIO_MAX = placeholder();
    public static final int _SC_NGROUPS_MAX = placeholder();
    public static final int _SC_NPROCESSORS_CONF = placeholder();
    public static final int _SC_NPROCESSORS_ONLN = placeholder();
    public static final int _SC_OPEN_MAX = placeholder();
    public static final int _SC_PAGESIZE = placeholder();
    public static final int _SC_PAGE_SIZE = placeholder();
    public static final int _SC_PASS_MAX = placeholder();
    public static final int _SC_PHYS_PAGES = placeholder();
    public static final int _SC_PRIORITIZED_IO = placeholder();
    public static final int _SC_PRIORITY_SCHEDULING = placeholder();
    public static final int _SC_REALTIME_SIGNALS = placeholder();
    public static final int _SC_RE_DUP_MAX = placeholder();
    public static final int _SC_RTSIG_MAX = placeholder();
    public static final int _SC_SAVED_IDS = placeholder();
    public static final int _SC_SEMAPHORES = placeholder();
    public static final int _SC_SEM_NSEMS_MAX = placeholder();
    public static final int _SC_SEM_VALUE_MAX = placeholder();
    public static final int _SC_SHARED_MEMORY_OBJECTS = placeholder();
    public static final int _SC_SIGQUEUE_MAX = placeholder();
    public static final int _SC_STREAM_MAX = placeholder();
    public static final int _SC_SYNCHRONIZED_IO = placeholder();
    public static final int _SC_THREADS = placeholder();
    public static final int _SC_THREAD_ATTR_STACKADDR = placeholder();
    public static final int _SC_THREAD_ATTR_STACKSIZE = placeholder();
    public static final int _SC_THREAD_DESTRUCTOR_ITERATIONS = placeholder();
    public static final int _SC_THREAD_KEYS_MAX = placeholder();
    public static final int _SC_THREAD_PRIORITY_SCHEDULING = placeholder();
    public static final int _SC_THREAD_PRIO_INHERIT = placeholder();
    public static final int _SC_THREAD_PRIO_PROTECT = placeholder();
    public static final int _SC_THREAD_SAFE_FUNCTIONS = placeholder();
    public static final int _SC_THREAD_STACK_MIN = placeholder();
    public static final int _SC_THREAD_THREADS_MAX = placeholder();
    public static final int _SC_TIMERS = placeholder();
    public static final int _SC_TIMER_MAX = placeholder();
    public static final int _SC_TTY_NAME_MAX = placeholder();
    public static final int _SC_TZNAME_MAX = placeholder();
    public static final int _SC_VERSION = placeholder();
    public static final int _SC_XBS5_ILP32_OFF32 = placeholder();
    public static final int _SC_XBS5_ILP32_OFFBIG = placeholder();
    public static final int _SC_XBS5_LP64_OFF64 = placeholder();
    public static final int _SC_XBS5_LPBIG_OFFBIG = placeholder();
    public static final int _SC_XOPEN_CRYPT = placeholder();
    public static final int _SC_XOPEN_ENH_I18N = placeholder();
    public static final int _SC_XOPEN_LEGACY = placeholder();
    public static final int _SC_XOPEN_REALTIME = placeholder();
    public static final int _SC_XOPEN_REALTIME_THREADS = placeholder();
    public static final int _SC_XOPEN_SHM = placeholder();
    public static final int _SC_XOPEN_UNIX = placeholder();
    public static final int _SC_XOPEN_VERSION = placeholder();
    public static final int _SC_XOPEN_XCU_VERSION = placeholder();

    /**
     * Returns the string name of a getaddrinfo(3) error value.
     * For example, "EAI_AGAIN".
     */
    public static String gaiName(int error) {
        if (error == EAI_AGAIN) {
            return "EAI_AGAIN";
        }
        if (error == EAI_BADFLAGS) {
            return "EAI_BADFLAGS";
        }
        if (error == EAI_FAIL) {
            return "EAI_FAIL";
        }
        if (error == EAI_FAMILY) {
            return "EAI_FAMILY";
        }
        if (error == EAI_MEMORY) {
            return "EAI_MEMORY";
        }
        if (error == EAI_NODATA) {
            return "EAI_NODATA";
        }
        if (error == EAI_NONAME) {
            return "EAI_NONAME";
        }
        if (error == EAI_OVERFLOW) {
            return "EAI_OVERFLOW";
        }
        if (error == EAI_SERVICE) {
            return "EAI_SERVICE";
        }
        if (error == EAI_SOCKTYPE) {
            return "EAI_SOCKTYPE";
        }
        if (error == EAI_SYSTEM) {
            return "EAI_SYSTEM";
        }
        return null;
    }

    /**
     * Returns the string name of an errno value.
     * For example, "EACCES". See {@link Os#strerror} for human-readable errno descriptions.
     */
    public static String errnoName(int errno) {
        if (errno == E2BIG) {
            return "E2BIG";
        }
        if (errno == EACCES) {
            return "EACCES";
        }
        if (errno == EADDRINUSE) {
            return "EADDRINUSE";
        }
        if (errno == EADDRNOTAVAIL) {
            return "EADDRNOTAVAIL";
        }
        if (errno == EAFNOSUPPORT) {
            return "EAFNOSUPPORT";
        }
        if (errno == EAGAIN) {
            return "EAGAIN";
        }
        if (errno == EALREADY) {
            return "EALREADY";
        }
        if (errno == EBADF) {
            return "EBADF";
        }
        if (errno == EBADMSG) {
            return "EBADMSG";
        }
        if (errno == EBUSY) {
            return "EBUSY";
        }
        if (errno == ECANCELED) {
            return "ECANCELED";
        }
        if (errno == ECHILD) {
            return "ECHILD";
        }
        if (errno == ECONNABORTED) {
            return "ECONNABORTED";
        }
        if (errno == ECONNREFUSED) {
            return "ECONNREFUSED";
        }
        if (errno == ECONNRESET) {
            return "ECONNRESET";
        }
        if (errno == EDEADLK) {
            return "EDEADLK";
        }
        if (errno == EDESTADDRREQ) {
            return "EDESTADDRREQ";
        }
        if (errno == EDOM) {
            return "EDOM";
        }
        if (errno == EDQUOT) {
            return "EDQUOT";
        }
        if (errno == EEXIST) {
            return "EEXIST";
        }
        if (errno == EFAULT) {
            return "EFAULT";
        }
        if (errno == EFBIG) {
            return "EFBIG";
        }
        if (errno == EHOSTUNREACH) {
            return "EHOSTUNREACH";
        }
        if (errno == EIDRM) {
            return "EIDRM";
        }
        if (errno == EILSEQ) {
            return "EILSEQ";
        }
        if (errno == EINPROGRESS) {
            return "EINPROGRESS";
        }
        if (errno == EINTR) {
            return "EINTR";
        }
        if (errno == EINVAL) {
            return "EINVAL";
        }
        if (errno == EIO) {
            return "EIO";
        }
        if (errno == EISCONN) {
            return "EISCONN";
        }
        if (errno == EISDIR) {
            return "EISDIR";
        }
        if (errno == ELOOP) {
            return "ELOOP";
        }
        if (errno == EMFILE) {
            return "EMFILE";
        }
        if (errno == EMLINK) {
            return "EMLINK";
        }
        if (errno == EMSGSIZE) {
            return "EMSGSIZE";
        }
        if (errno == EMULTIHOP) {
            return "EMULTIHOP";
        }
        if (errno == ENAMETOOLONG) {
            return "ENAMETOOLONG";
        }
        if (errno == ENETDOWN) {
            return "ENETDOWN";
        }
        if (errno == ENETRESET) {
            return "ENETRESET";
        }
        if (errno == ENETUNREACH) {
            return "ENETUNREACH";
        }
        if (errno == ENFILE) {
            return "ENFILE";
        }
        if (errno == ENOBUFS) {
            return "ENOBUFS";
        }
        if (errno == ENODATA) {
            return "ENODATA";
        }
        if (errno == ENODEV) {
            return "ENODEV";
        }
        if (errno == ENOENT) {
            return "ENOENT";
        }
        if (errno == ENOEXEC) {
            return "ENOEXEC";
        }
        if (errno == ENOLCK) {
            return "ENOLCK";
        }
        if (errno == ENOLINK) {
            return "ENOLINK";
        }
        if (errno == ENOMEM) {
            return "ENOMEM";
        }
        if (errno == ENOMSG) {
            return "ENOMSG";
        }
        if (errno == ENONET) {
            return "ENONET";
        }
        if (errno == ENOPROTOOPT) {
            return "ENOPROTOOPT";
        }
        if (errno == ENOSPC) {
            return "ENOSPC";
        }
        if (errno == ENOSR) {
            return "ENOSR";
        }
        if (errno == ENOSTR) {
            return "ENOSTR";
        }
        if (errno == ENOSYS) {
            return "ENOSYS";
        }
        if (errno == ENOTCONN) {
            return "ENOTCONN";
        }
        if (errno == ENOTDIR) {
            return "ENOTDIR";
        }
        if (errno == ENOTEMPTY) {
            return "ENOTEMPTY";
        }
        if (errno == ENOTSOCK) {
            return "ENOTSOCK";
        }
        if (errno == ENOTSUP) {
            return "ENOTSUP";
        }
        if (errno == ENOTTY) {
            return "ENOTTY";
        }
        if (errno == ENXIO) {
            return "ENXIO";
        }
        if (errno == EOPNOTSUPP) {
            return "EOPNOTSUPP";
        }
        if (errno == EOVERFLOW) {
            return "EOVERFLOW";
        }
        if (errno == EPERM) {
            return "EPERM";
        }
        if (errno == EPIPE) {
            return "EPIPE";
        }
        if (errno == EPROTO) {
            return "EPROTO";
        }
        if (errno == EPROTONOSUPPORT) {
            return "EPROTONOSUPPORT";
        }
        if (errno == EPROTOTYPE) {
            return "EPROTOTYPE";
        }
        if (errno == ERANGE) {
            return "ERANGE";
        }
        if (errno == EROFS) {
            return "EROFS";
        }
        if (errno == ESPIPE) {
            return "ESPIPE";
        }
        if (errno == ESRCH) {
            return "ESRCH";
        }
        if (errno == ESTALE) {
            return "ESTALE";
        }
        if (errno == ETIME) {
            return "ETIME";
        }
        if (errno == ETIMEDOUT) {
            return "ETIMEDOUT";
        }
        if (errno == ETXTBSY) {
            return "ETXTBSY";
        }
        if (errno == EXDEV) {
            return "EXDEV";
        }
        return null;
    }

    // [ravenwood-change] Moved to a nested class.
    //    @UnsupportedAppUsage
    static class Native {
        private static native void initConstants();
    }

    // A hack to avoid these constants being inlined by javac...
//    @UnsupportedAppUsage
    private static int placeholder() { return 0; }
    // ...because we want to initialize them at runtime.
    static {
        // [ravenwood-change] Load the JNI lib.
        RavenwoodCommonUtils.loadRavenwoodNativeRuntime();
        Native.initConstants();
    }
}
