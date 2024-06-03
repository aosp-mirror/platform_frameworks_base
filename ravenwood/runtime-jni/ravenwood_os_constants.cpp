/*
 * Copyright (C) 2024 The Android Open Source Project
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

// Copied from libcore/luni/src/main/native/android_system_OsConstants.cpp,
// changes annotated with [ravenwood-change].

#define LOG_TAG "OsConstants"

#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/icmp6.h>
#include <netinet/in.h>
#include <netinet/ip_icmp.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#include <net/if_arp.h>
#include <linux/if_ether.h>

// After the others because these are not necessarily self-contained in glibc.
#include <linux/if_addr.h>
#include <linux/rtnetlink.h>

// Include linux socket constants for setting sockopts
#include <linux/udp.h>

#include <net/if.h> // After <sys/socket.h> to work around a Mac header file bug.

// [ravenwood-change] always include it
// #if defined(__BIONIC__)
#include <linux/capability.h>
// #endif

#include <nativehelper/JNIHelp.h>
#include <nativehelper/jni_macros.h>

// [ravenwood-change] -- can't access "Portability.h", so just inline it here.
// #include "Portability.h"
#include <byteswap.h>
#include <sys/sendfile.h>
#include <sys/statvfs.h>
#include <netdb.h>
#include <linux/vm_sockets.h>

// For LOG_ALWAYS_FATAL_IF
#include "utils/Log.h"


static void initConstant(JNIEnv* env, jclass c, const char* fieldName, int value) {
    jfieldID field = env->GetStaticFieldID(c, fieldName, "I");
    env->SetStaticIntField(c, field, value);
}

static void OsConstants_initConstants(JNIEnv* env, jclass) {
    // [ravenwood-change] -- the constants are in the outer class, but this JNI method is in the
    // nested class, so we need to get the outer class here.
    jclass c = env->FindClass("android/system/OsConstants");
    LOG_ALWAYS_FATAL_IF(c == NULL, "Unable to find class android/system/OsConstants");

    initConstant(env, c, "AF_INET", AF_INET);
    initConstant(env, c, "AF_INET6", AF_INET6);
    initConstant(env, c, "AF_PACKET", AF_PACKET);
    initConstant(env, c, "AF_NETLINK", AF_NETLINK);
    initConstant(env, c, "AF_UNIX", AF_UNIX);
    initConstant(env, c, "AF_VSOCK", AF_VSOCK);
    initConstant(env, c, "AF_UNSPEC", AF_UNSPEC);
    initConstant(env, c, "AI_ADDRCONFIG", AI_ADDRCONFIG);
    initConstant(env, c, "AI_ALL", AI_ALL);
    initConstant(env, c, "AI_CANONNAME", AI_CANONNAME);
    initConstant(env, c, "AI_NUMERICHOST", AI_NUMERICHOST);
#if defined(AI_NUMERICSERV)
    initConstant(env, c, "AI_NUMERICSERV", AI_NUMERICSERV);
#endif
    initConstant(env, c, "AI_PASSIVE", AI_PASSIVE);
    initConstant(env, c, "AI_V4MAPPED", AI_V4MAPPED);
    initConstant(env, c, "ARPHRD_ETHER", ARPHRD_ETHER);
    initConstant(env, c, "VMADDR_PORT_ANY", VMADDR_PORT_ANY);
    initConstant(env, c, "VMADDR_CID_ANY", VMADDR_CID_ANY);
    initConstant(env, c, "VMADDR_CID_LOCAL", VMADDR_CID_LOCAL);
    initConstant(env, c, "VMADDR_CID_HOST", VMADDR_CID_HOST);
    initConstant(env, c, "ARPHRD_LOOPBACK", ARPHRD_LOOPBACK);
#if defined(CAP_LAST_CAP)
    initConstant(env, c, "CAP_AUDIT_CONTROL", CAP_AUDIT_CONTROL);
    initConstant(env, c, "CAP_AUDIT_WRITE", CAP_AUDIT_WRITE);
    initConstant(env, c, "CAP_BLOCK_SUSPEND", CAP_BLOCK_SUSPEND);
    initConstant(env, c, "CAP_CHOWN", CAP_CHOWN);
    initConstant(env, c, "CAP_DAC_OVERRIDE", CAP_DAC_OVERRIDE);
    initConstant(env, c, "CAP_DAC_READ_SEARCH", CAP_DAC_READ_SEARCH);
    initConstant(env, c, "CAP_FOWNER", CAP_FOWNER);
    initConstant(env, c, "CAP_FSETID", CAP_FSETID);
    initConstant(env, c, "CAP_IPC_LOCK", CAP_IPC_LOCK);
    initConstant(env, c, "CAP_IPC_OWNER", CAP_IPC_OWNER);
    initConstant(env, c, "CAP_KILL", CAP_KILL);
    initConstant(env, c, "CAP_LAST_CAP", CAP_LAST_CAP);
    initConstant(env, c, "CAP_LEASE", CAP_LEASE);
    initConstant(env, c, "CAP_LINUX_IMMUTABLE", CAP_LINUX_IMMUTABLE);
    initConstant(env, c, "CAP_MAC_ADMIN", CAP_MAC_ADMIN);
    initConstant(env, c, "CAP_MAC_OVERRIDE", CAP_MAC_OVERRIDE);
    initConstant(env, c, "CAP_MKNOD", CAP_MKNOD);
    initConstant(env, c, "CAP_NET_ADMIN", CAP_NET_ADMIN);
    initConstant(env, c, "CAP_NET_BIND_SERVICE", CAP_NET_BIND_SERVICE);
    initConstant(env, c, "CAP_NET_BROADCAST", CAP_NET_BROADCAST);
    initConstant(env, c, "CAP_NET_RAW", CAP_NET_RAW);
    initConstant(env, c, "CAP_SETFCAP", CAP_SETFCAP);
    initConstant(env, c, "CAP_SETGID", CAP_SETGID);
    initConstant(env, c, "CAP_SETPCAP", CAP_SETPCAP);
    initConstant(env, c, "CAP_SETUID", CAP_SETUID);
    initConstant(env, c, "CAP_SYS_ADMIN", CAP_SYS_ADMIN);
    initConstant(env, c, "CAP_SYS_BOOT", CAP_SYS_BOOT);
    initConstant(env, c, "CAP_SYS_CHROOT", CAP_SYS_CHROOT);
    initConstant(env, c, "CAP_SYSLOG", CAP_SYSLOG);
    initConstant(env, c, "CAP_SYS_MODULE", CAP_SYS_MODULE);
    initConstant(env, c, "CAP_SYS_NICE", CAP_SYS_NICE);
    initConstant(env, c, "CAP_SYS_PACCT", CAP_SYS_PACCT);
    initConstant(env, c, "CAP_SYS_PTRACE", CAP_SYS_PTRACE);
    initConstant(env, c, "CAP_SYS_RAWIO", CAP_SYS_RAWIO);
    initConstant(env, c, "CAP_SYS_RESOURCE", CAP_SYS_RESOURCE);
    initConstant(env, c, "CAP_SYS_TIME", CAP_SYS_TIME);
    initConstant(env, c, "CAP_SYS_TTY_CONFIG", CAP_SYS_TTY_CONFIG);
    initConstant(env, c, "CAP_WAKE_ALARM", CAP_WAKE_ALARM);
#endif
    initConstant(env, c, "E2BIG", E2BIG);
    initConstant(env, c, "EACCES", EACCES);
    initConstant(env, c, "EADDRINUSE", EADDRINUSE);
    initConstant(env, c, "EADDRNOTAVAIL", EADDRNOTAVAIL);
    initConstant(env, c, "EAFNOSUPPORT", EAFNOSUPPORT);
    initConstant(env, c, "EAGAIN", EAGAIN);
    initConstant(env, c, "EAI_AGAIN", EAI_AGAIN);
    initConstant(env, c, "EAI_BADFLAGS", EAI_BADFLAGS);
    initConstant(env, c, "EAI_FAIL", EAI_FAIL);
    initConstant(env, c, "EAI_FAMILY", EAI_FAMILY);
    initConstant(env, c, "EAI_MEMORY", EAI_MEMORY);
    initConstant(env, c, "EAI_NODATA", EAI_NODATA);
    initConstant(env, c, "EAI_NONAME", EAI_NONAME);
#if defined(EAI_OVERFLOW)
    initConstant(env, c, "EAI_OVERFLOW", EAI_OVERFLOW);
#endif
    initConstant(env, c, "EAI_SERVICE", EAI_SERVICE);
    initConstant(env, c, "EAI_SOCKTYPE", EAI_SOCKTYPE);
    initConstant(env, c, "EAI_SYSTEM", EAI_SYSTEM);
    initConstant(env, c, "EALREADY", EALREADY);
    initConstant(env, c, "EBADF", EBADF);
    initConstant(env, c, "EBADMSG", EBADMSG);
    initConstant(env, c, "EBUSY", EBUSY);
    initConstant(env, c, "ECANCELED", ECANCELED);
    initConstant(env, c, "ECHILD", ECHILD);
    initConstant(env, c, "ECONNABORTED", ECONNABORTED);
    initConstant(env, c, "ECONNREFUSED", ECONNREFUSED);
    initConstant(env, c, "ECONNRESET", ECONNRESET);
    initConstant(env, c, "EDEADLK", EDEADLK);
    initConstant(env, c, "EDESTADDRREQ", EDESTADDRREQ);
    initConstant(env, c, "EDOM", EDOM);
    initConstant(env, c, "EDQUOT", EDQUOT);
    initConstant(env, c, "EEXIST", EEXIST);
    initConstant(env, c, "EFAULT", EFAULT);
    initConstant(env, c, "EFBIG", EFBIG);
    initConstant(env, c, "EHOSTUNREACH", EHOSTUNREACH);
    initConstant(env, c, "EIDRM", EIDRM);
    initConstant(env, c, "EILSEQ", EILSEQ);
    initConstant(env, c, "EINPROGRESS", EINPROGRESS);
    initConstant(env, c, "EINTR", EINTR);
    initConstant(env, c, "EINVAL", EINVAL);
    initConstant(env, c, "EIO", EIO);
    initConstant(env, c, "EISCONN", EISCONN);
    initConstant(env, c, "EISDIR", EISDIR);
    initConstant(env, c, "ELOOP", ELOOP);
    initConstant(env, c, "EMFILE", EMFILE);
    initConstant(env, c, "EMLINK", EMLINK);
    initConstant(env, c, "EMSGSIZE", EMSGSIZE);
    initConstant(env, c, "EMULTIHOP", EMULTIHOP);
    initConstant(env, c, "ENAMETOOLONG", ENAMETOOLONG);
    initConstant(env, c, "ENETDOWN", ENETDOWN);
    initConstant(env, c, "ENETRESET", ENETRESET);
    initConstant(env, c, "ENETUNREACH", ENETUNREACH);
    initConstant(env, c, "ENFILE", ENFILE);
    initConstant(env, c, "ENOBUFS", ENOBUFS);
    initConstant(env, c, "ENODATA", ENODATA);
    initConstant(env, c, "ENODEV", ENODEV);
    initConstant(env, c, "ENOENT", ENOENT);
    initConstant(env, c, "ENOEXEC", ENOEXEC);
    initConstant(env, c, "ENOLCK", ENOLCK);
    initConstant(env, c, "ENOLINK", ENOLINK);
    initConstant(env, c, "ENOMEM", ENOMEM);
    initConstant(env, c, "ENOMSG", ENOMSG);
    initConstant(env, c, "ENONET", ENONET);
    initConstant(env, c, "ENOPROTOOPT", ENOPROTOOPT);
    initConstant(env, c, "ENOSPC", ENOSPC);
    initConstant(env, c, "ENOSR", ENOSR);
    initConstant(env, c, "ENOSTR", ENOSTR);
    initConstant(env, c, "ENOSYS", ENOSYS);
    initConstant(env, c, "ENOTCONN", ENOTCONN);
    initConstant(env, c, "ENOTDIR", ENOTDIR);
    initConstant(env, c, "ENOTEMPTY", ENOTEMPTY);
    initConstant(env, c, "ENOTSOCK", ENOTSOCK);
    initConstant(env, c, "ENOTSUP", ENOTSUP);
    initConstant(env, c, "ENOTTY", ENOTTY);
    initConstant(env, c, "ENXIO", ENXIO);
    initConstant(env, c, "EOPNOTSUPP", EOPNOTSUPP);
    initConstant(env, c, "EOVERFLOW", EOVERFLOW);
    initConstant(env, c, "EPERM", EPERM);
    initConstant(env, c, "EPIPE", EPIPE);
    initConstant(env, c, "EPROTO", EPROTO);
    initConstant(env, c, "EPROTONOSUPPORT", EPROTONOSUPPORT);
    initConstant(env, c, "EPROTOTYPE", EPROTOTYPE);
    initConstant(env, c, "ERANGE", ERANGE);
    initConstant(env, c, "EROFS", EROFS);
    initConstant(env, c, "ESPIPE", ESPIPE);
    initConstant(env, c, "ESRCH", ESRCH);
    initConstant(env, c, "ESTALE", ESTALE);
    initConstant(env, c, "ETH_P_ALL", ETH_P_ALL);
    initConstant(env, c, "ETH_P_ARP", ETH_P_ARP);
    initConstant(env, c, "ETH_P_IP", ETH_P_IP);
    initConstant(env, c, "ETH_P_IPV6", ETH_P_IPV6);
    initConstant(env, c, "ETIME", ETIME);
    initConstant(env, c, "ETIMEDOUT", ETIMEDOUT);
    initConstant(env, c, "ETXTBSY", ETXTBSY);
    initConstant(env, c, "EUSERS", EUSERS);
#if EWOULDBLOCK != EAGAIN
#error EWOULDBLOCK != EAGAIN
#endif
    initConstant(env, c, "EXDEV", EXDEV);
    initConstant(env, c, "EXIT_FAILURE", EXIT_FAILURE);
    initConstant(env, c, "EXIT_SUCCESS", EXIT_SUCCESS);
    initConstant(env, c, "FD_CLOEXEC", FD_CLOEXEC);
    initConstant(env, c, "FIONREAD", FIONREAD);
    initConstant(env, c, "F_DUPFD", F_DUPFD);
    initConstant(env, c, "F_DUPFD_CLOEXEC", F_DUPFD_CLOEXEC);
    initConstant(env, c, "F_GETFD", F_GETFD);
    initConstant(env, c, "F_GETFL", F_GETFL);
    initConstant(env, c, "F_GETLK", F_GETLK);
#if defined(F_GETLK64)
    initConstant(env, c, "F_GETLK64", F_GETLK64);
#endif
    initConstant(env, c, "F_GETOWN", F_GETOWN);
    initConstant(env, c, "F_OK", F_OK);
    initConstant(env, c, "F_RDLCK", F_RDLCK);
    initConstant(env, c, "F_SETFD", F_SETFD);
    initConstant(env, c, "F_SETFL", F_SETFL);
    initConstant(env, c, "F_SETLK", F_SETLK);
#if defined(F_SETLK64)
    initConstant(env, c, "F_SETLK64", F_SETLK64);
#endif
    initConstant(env, c, "F_SETLKW", F_SETLKW);
#if defined(F_SETLKW64)
    initConstant(env, c, "F_SETLKW64", F_SETLKW64);
#endif
    initConstant(env, c, "F_SETOWN", F_SETOWN);
    initConstant(env, c, "F_UNLCK", F_UNLCK);
    initConstant(env, c, "F_WRLCK", F_WRLCK);
    initConstant(env, c, "ICMP_ECHO", ICMP_ECHO);
    initConstant(env, c, "ICMP_ECHOREPLY", ICMP_ECHOREPLY);
    initConstant(env, c, "ICMP6_ECHO_REQUEST", ICMP6_ECHO_REQUEST);
    initConstant(env, c, "ICMP6_ECHO_REPLY", ICMP6_ECHO_REPLY);
#if defined(IFA_F_DADFAILED)
    initConstant(env, c, "IFA_F_DADFAILED", IFA_F_DADFAILED);
#endif
#if defined(IFA_F_DEPRECATED)
    initConstant(env, c, "IFA_F_DEPRECATED", IFA_F_DEPRECATED);
#endif
#if defined(IFA_F_HOMEADDRESS)
    initConstant(env, c, "IFA_F_HOMEADDRESS", IFA_F_HOMEADDRESS);
#endif
#if defined(IFA_F_MANAGETEMPADDR)
    initConstant(env, c, "IFA_F_MANAGETEMPADDR", IFA_F_MANAGETEMPADDR);
#endif
#if defined(IFA_F_NODAD)
    initConstant(env, c, "IFA_F_NODAD", IFA_F_NODAD);
#endif
#if defined(IFA_F_NOPREFIXROUTE)
    initConstant(env, c, "IFA_F_NOPREFIXROUTE", IFA_F_NOPREFIXROUTE);
#endif
#if defined(IFA_F_OPTIMISTIC)
    initConstant(env, c, "IFA_F_OPTIMISTIC", IFA_F_OPTIMISTIC);
#endif
#if defined(IFA_F_PERMANENT)
    initConstant(env, c, "IFA_F_PERMANENT", IFA_F_PERMANENT);
#endif
#if defined(IFA_F_SECONDARY)
    initConstant(env, c, "IFA_F_SECONDARY", IFA_F_SECONDARY);
#endif
#if defined(IFA_F_TEMPORARY)
    initConstant(env, c, "IFA_F_TEMPORARY", IFA_F_TEMPORARY);
#endif
#if defined(IFA_F_TENTATIVE)
    initConstant(env, c, "IFA_F_TENTATIVE", IFA_F_TENTATIVE);
#endif
    initConstant(env, c, "IFF_ALLMULTI", IFF_ALLMULTI);
#if defined(IFF_AUTOMEDIA)
    initConstant(env, c, "IFF_AUTOMEDIA", IFF_AUTOMEDIA);
#endif
    initConstant(env, c, "IFF_BROADCAST", IFF_BROADCAST);
    initConstant(env, c, "IFF_DEBUG", IFF_DEBUG);
#if defined(IFF_DYNAMIC)
    initConstant(env, c, "IFF_DYNAMIC", IFF_DYNAMIC);
#endif
    initConstant(env, c, "IFF_LOOPBACK", IFF_LOOPBACK);
#if defined(IFF_MASTER)
    initConstant(env, c, "IFF_MASTER", IFF_MASTER);
#endif
    initConstant(env, c, "IFF_MULTICAST", IFF_MULTICAST);
    initConstant(env, c, "IFF_NOARP", IFF_NOARP);
    initConstant(env, c, "IFF_NOTRAILERS", IFF_NOTRAILERS);
    initConstant(env, c, "IFF_POINTOPOINT", IFF_POINTOPOINT);
#if defined(IFF_PORTSEL)
    initConstant(env, c, "IFF_PORTSEL", IFF_PORTSEL);
#endif
    initConstant(env, c, "IFF_PROMISC", IFF_PROMISC);
    initConstant(env, c, "IFF_RUNNING", IFF_RUNNING);
#if defined(IFF_SLAVE)
    initConstant(env, c, "IFF_SLAVE", IFF_SLAVE);
#endif
    initConstant(env, c, "IFF_UP", IFF_UP);
    initConstant(env, c, "IPPROTO_ICMP", IPPROTO_ICMP);
    initConstant(env, c, "IPPROTO_ICMPV6", IPPROTO_ICMPV6);
    initConstant(env, c, "IPPROTO_IP", IPPROTO_IP);
    initConstant(env, c, "IPPROTO_IPV6", IPPROTO_IPV6);
    initConstant(env, c, "IPPROTO_RAW", IPPROTO_RAW);
    initConstant(env, c, "IPPROTO_TCP", IPPROTO_TCP);
    initConstant(env, c, "IPPROTO_UDP", IPPROTO_UDP);
    initConstant(env, c, "IPPROTO_ESP", IPPROTO_ESP);
    initConstant(env, c, "IPV6_CHECKSUM", IPV6_CHECKSUM);
    initConstant(env, c, "IPV6_MULTICAST_HOPS", IPV6_MULTICAST_HOPS);
    initConstant(env, c, "IPV6_MULTICAST_IF", IPV6_MULTICAST_IF);
    initConstant(env, c, "IPV6_MULTICAST_LOOP", IPV6_MULTICAST_LOOP);
#if defined(IPV6_PKTINFO)
    initConstant(env, c, "IPV6_PKTINFO", IPV6_PKTINFO);
#endif
#if defined(IPV6_RECVDSTOPTS)
    initConstant(env, c, "IPV6_RECVDSTOPTS", IPV6_RECVDSTOPTS);
#endif
#if defined(IPV6_RECVHOPLIMIT)
    initConstant(env, c, "IPV6_RECVHOPLIMIT", IPV6_RECVHOPLIMIT);
#endif
#if defined(IPV6_RECVHOPOPTS)
    initConstant(env, c, "IPV6_RECVHOPOPTS", IPV6_RECVHOPOPTS);
#endif
#if defined(IPV6_RECVPKTINFO)
    initConstant(env, c, "IPV6_RECVPKTINFO", IPV6_RECVPKTINFO);
#endif
#if defined(IPV6_RECVRTHDR)
    initConstant(env, c, "IPV6_RECVRTHDR", IPV6_RECVRTHDR);
#endif
#if defined(IPV6_RECVTCLASS)
    initConstant(env, c, "IPV6_RECVTCLASS", IPV6_RECVTCLASS);
#endif
#if defined(IPV6_TCLASS)
    initConstant(env, c, "IPV6_TCLASS", IPV6_TCLASS);
#endif
    initConstant(env, c, "IPV6_UNICAST_HOPS", IPV6_UNICAST_HOPS);
    initConstant(env, c, "IPV6_V6ONLY", IPV6_V6ONLY);
    initConstant(env, c, "IP_MULTICAST_ALL", IP_MULTICAST_ALL);
    initConstant(env, c, "IP_MULTICAST_IF", IP_MULTICAST_IF);
    initConstant(env, c, "IP_MULTICAST_LOOP", IP_MULTICAST_LOOP);
    initConstant(env, c, "IP_MULTICAST_TTL", IP_MULTICAST_TTL);
    initConstant(env, c, "IP_RECVTOS", IP_RECVTOS);
    initConstant(env, c, "IP_TOS", IP_TOS);
    initConstant(env, c, "IP_TTL", IP_TTL);
#if defined(_LINUX_CAPABILITY_VERSION_3)
    initConstant(env, c, "_LINUX_CAPABILITY_VERSION_3", _LINUX_CAPABILITY_VERSION_3);
#endif
    initConstant(env, c, "MAP_FIXED", MAP_FIXED);
    initConstant(env, c, "MAP_ANONYMOUS", MAP_ANONYMOUS);
    initConstant(env, c, "MAP_POPULATE", MAP_POPULATE);
    initConstant(env, c, "MAP_PRIVATE", MAP_PRIVATE);
    initConstant(env, c, "MAP_SHARED", MAP_SHARED);
#if defined(MCAST_JOIN_GROUP)
    initConstant(env, c, "MCAST_JOIN_GROUP", MCAST_JOIN_GROUP);
#endif
#if defined(MCAST_LEAVE_GROUP)
    initConstant(env, c, "MCAST_LEAVE_GROUP", MCAST_LEAVE_GROUP);
#endif
#if defined(MCAST_JOIN_SOURCE_GROUP)
    initConstant(env, c, "MCAST_JOIN_SOURCE_GROUP", MCAST_JOIN_SOURCE_GROUP);
#endif
#if defined(MCAST_LEAVE_SOURCE_GROUP)
    initConstant(env, c, "MCAST_LEAVE_SOURCE_GROUP", MCAST_LEAVE_SOURCE_GROUP);
#endif
#if defined(MCAST_BLOCK_SOURCE)
    initConstant(env, c, "MCAST_BLOCK_SOURCE", MCAST_BLOCK_SOURCE);
#endif
#if defined(MCAST_UNBLOCK_SOURCE)
    initConstant(env, c, "MCAST_UNBLOCK_SOURCE", MCAST_UNBLOCK_SOURCE);
#endif
    initConstant(env, c, "MCL_CURRENT", MCL_CURRENT);
    initConstant(env, c, "MCL_FUTURE", MCL_FUTURE);
#if defined(MFD_CLOEXEC)
    initConstant(env, c, "MFD_CLOEXEC", MFD_CLOEXEC);
#endif
    initConstant(env, c, "MSG_CTRUNC", MSG_CTRUNC);
    initConstant(env, c, "MSG_DONTROUTE", MSG_DONTROUTE);
    initConstant(env, c, "MSG_EOR", MSG_EOR);
    initConstant(env, c, "MSG_OOB", MSG_OOB);
    initConstant(env, c, "MSG_PEEK", MSG_PEEK);
    initConstant(env, c, "MSG_TRUNC", MSG_TRUNC);
    initConstant(env, c, "MSG_WAITALL", MSG_WAITALL);
    initConstant(env, c, "MS_ASYNC", MS_ASYNC);
    initConstant(env, c, "MS_INVALIDATE", MS_INVALIDATE);
    initConstant(env, c, "MS_SYNC", MS_SYNC);
    initConstant(env, c, "NETLINK_NETFILTER", NETLINK_NETFILTER);
    initConstant(env, c, "NETLINK_ROUTE", NETLINK_ROUTE);
    initConstant(env, c, "NETLINK_INET_DIAG", NETLINK_INET_DIAG);
    initConstant(env, c, "NETLINK_XFRM", NETLINK_XFRM);
    initConstant(env, c, "NI_DGRAM", NI_DGRAM);
    initConstant(env, c, "NI_NAMEREQD", NI_NAMEREQD);
    initConstant(env, c, "NI_NOFQDN", NI_NOFQDN);
    initConstant(env, c, "NI_NUMERICHOST", NI_NUMERICHOST);
    initConstant(env, c, "NI_NUMERICSERV", NI_NUMERICSERV);
    initConstant(env, c, "O_ACCMODE", O_ACCMODE);
    initConstant(env, c, "O_APPEND", O_APPEND);
    initConstant(env, c, "O_CLOEXEC", O_CLOEXEC);
    initConstant(env, c, "O_CREAT", O_CREAT);
    initConstant(env, c, "O_DIRECT", O_DIRECT);
    initConstant(env, c, "O_EXCL", O_EXCL);
    initConstant(env, c, "O_NOCTTY", O_NOCTTY);
    initConstant(env, c, "O_NOFOLLOW", O_NOFOLLOW);
    initConstant(env, c, "O_NONBLOCK", O_NONBLOCK);
    initConstant(env, c, "O_RDONLY", O_RDONLY);
    initConstant(env, c, "O_RDWR", O_RDWR);
    initConstant(env, c, "O_SYNC", O_SYNC);
    initConstant(env, c, "O_DSYNC", O_DSYNC);
    initConstant(env, c, "O_TRUNC", O_TRUNC);
    initConstant(env, c, "O_WRONLY", O_WRONLY);
    initConstant(env, c, "POLLERR", POLLERR);
    initConstant(env, c, "POLLHUP", POLLHUP);
    initConstant(env, c, "POLLIN", POLLIN);
    initConstant(env, c, "POLLNVAL", POLLNVAL);
    initConstant(env, c, "POLLOUT", POLLOUT);
    initConstant(env, c, "POLLPRI", POLLPRI);
    initConstant(env, c, "POLLRDBAND", POLLRDBAND);
    initConstant(env, c, "POLLRDNORM", POLLRDNORM);
    initConstant(env, c, "POLLWRBAND", POLLWRBAND);
    initConstant(env, c, "POLLWRNORM", POLLWRNORM);
#if defined(PR_CAP_AMBIENT)
    initConstant(env, c, "PR_CAP_AMBIENT", PR_CAP_AMBIENT);
#endif
#if defined(PR_CAP_AMBIENT_RAISE)
    initConstant(env, c, "PR_CAP_AMBIENT_RAISE", PR_CAP_AMBIENT_RAISE);
#endif
#if defined(PR_GET_DUMPABLE)
    initConstant(env, c, "PR_GET_DUMPABLE", PR_GET_DUMPABLE);
#endif
#if defined(PR_SET_DUMPABLE)
    initConstant(env, c, "PR_SET_DUMPABLE", PR_SET_DUMPABLE);
#endif
#if defined(PR_SET_NO_NEW_PRIVS)
    initConstant(env, c, "PR_SET_NO_NEW_PRIVS", PR_SET_NO_NEW_PRIVS);
#endif
    initConstant(env, c, "PROT_EXEC", PROT_EXEC);
    initConstant(env, c, "PROT_NONE", PROT_NONE);
    initConstant(env, c, "PROT_READ", PROT_READ);
    initConstant(env, c, "PROT_WRITE", PROT_WRITE);
    initConstant(env, c, "R_OK", R_OK);
    initConstant(env, c, "RLIMIT_NOFILE", RLIMIT_NOFILE);
// NOTE: The RT_* constants are not preprocessor defines, they're enum
// members. The best we can do (barring UAPI / kernel version checks) is
// to hope they exist on all host linuxes we're building on. These
// constants have been around since 2.6.35 at least, so we should be ok.
    initConstant(env, c, "RT_SCOPE_HOST", RT_SCOPE_HOST);
    initConstant(env, c, "RT_SCOPE_LINK", RT_SCOPE_LINK);
    initConstant(env, c, "RT_SCOPE_NOWHERE", RT_SCOPE_NOWHERE);
    initConstant(env, c, "RT_SCOPE_SITE", RT_SCOPE_SITE);
    initConstant(env, c, "RT_SCOPE_UNIVERSE", RT_SCOPE_UNIVERSE);
    initConstant(env, c, "RTMGRP_IPV4_IFADDR", RTMGRP_IPV4_IFADDR);
    initConstant(env, c, "RTMGRP_IPV4_MROUTE", RTMGRP_IPV4_MROUTE);
    initConstant(env, c, "RTMGRP_IPV4_ROUTE", RTMGRP_IPV4_ROUTE);
    initConstant(env, c, "RTMGRP_IPV4_RULE", RTMGRP_IPV4_RULE);
    initConstant(env, c, "RTMGRP_IPV6_IFADDR", RTMGRP_IPV6_IFADDR);
    initConstant(env, c, "RTMGRP_IPV6_IFINFO", RTMGRP_IPV6_IFINFO);
    initConstant(env, c, "RTMGRP_IPV6_MROUTE", RTMGRP_IPV6_MROUTE);
    initConstant(env, c, "RTMGRP_IPV6_PREFIX", RTMGRP_IPV6_PREFIX);
    initConstant(env, c, "RTMGRP_IPV6_ROUTE", RTMGRP_IPV6_ROUTE);
    initConstant(env, c, "RTMGRP_LINK", RTMGRP_LINK);
    initConstant(env, c, "RTMGRP_NEIGH", RTMGRP_NEIGH);
    initConstant(env, c, "RTMGRP_NOTIFY", RTMGRP_NOTIFY);
    initConstant(env, c, "RTMGRP_TC", RTMGRP_TC);
    initConstant(env, c, "SEEK_CUR", SEEK_CUR);
    initConstant(env, c, "SEEK_END", SEEK_END);
    initConstant(env, c, "SEEK_SET", SEEK_SET);
    initConstant(env, c, "SHUT_RD", SHUT_RD);
    initConstant(env, c, "SHUT_RDWR", SHUT_RDWR);
    initConstant(env, c, "SHUT_WR", SHUT_WR);
    initConstant(env, c, "SIGABRT", SIGABRT);
    initConstant(env, c, "SIGALRM", SIGALRM);
    initConstant(env, c, "SIGBUS", SIGBUS);
    initConstant(env, c, "SIGCHLD", SIGCHLD);
    initConstant(env, c, "SIGCONT", SIGCONT);
    initConstant(env, c, "SIGFPE", SIGFPE);
    initConstant(env, c, "SIGHUP", SIGHUP);
    initConstant(env, c, "SIGILL", SIGILL);
    initConstant(env, c, "SIGINT", SIGINT);
    initConstant(env, c, "SIGIO", SIGIO);
    initConstant(env, c, "SIGKILL", SIGKILL);
    initConstant(env, c, "SIGPIPE", SIGPIPE);
    initConstant(env, c, "SIGPROF", SIGPROF);
#if defined(SIGPWR)
    initConstant(env, c, "SIGPWR", SIGPWR);
#endif
    initConstant(env, c, "SIGQUIT", SIGQUIT);
#if defined(SIGRTMAX)
    initConstant(env, c, "SIGRTMAX", SIGRTMAX);
#endif
#if defined(SIGRTMIN)
    initConstant(env, c, "SIGRTMIN", SIGRTMIN);
#endif
    initConstant(env, c, "SIGSEGV", SIGSEGV);
#if defined(SIGSTKFLT)
    initConstant(env, c, "SIGSTKFLT", SIGSTKFLT);
#endif
    initConstant(env, c, "SIGSTOP", SIGSTOP);
    initConstant(env, c, "SIGSYS", SIGSYS);
    initConstant(env, c, "SIGTERM", SIGTERM);
    initConstant(env, c, "SIGTRAP", SIGTRAP);
    initConstant(env, c, "SIGTSTP", SIGTSTP);
    initConstant(env, c, "SIGTTIN", SIGTTIN);
    initConstant(env, c, "SIGTTOU", SIGTTOU);
    initConstant(env, c, "SIGURG", SIGURG);
    initConstant(env, c, "SIGUSR1", SIGUSR1);
    initConstant(env, c, "SIGUSR2", SIGUSR2);
    initConstant(env, c, "SIGVTALRM", SIGVTALRM);
    initConstant(env, c, "SIGWINCH", SIGWINCH);
    initConstant(env, c, "SIGXCPU", SIGXCPU);
    initConstant(env, c, "SIGXFSZ", SIGXFSZ);
    initConstant(env, c, "SIOCGIFADDR", SIOCGIFADDR);
    initConstant(env, c, "SIOCGIFBRDADDR", SIOCGIFBRDADDR);
    initConstant(env, c, "SIOCGIFDSTADDR", SIOCGIFDSTADDR);
    initConstant(env, c, "SIOCGIFNETMASK", SIOCGIFNETMASK);
    initConstant(env, c, "SOCK_CLOEXEC", SOCK_CLOEXEC);
    initConstant(env, c, "SOCK_DGRAM", SOCK_DGRAM);
    initConstant(env, c, "SOCK_NONBLOCK", SOCK_NONBLOCK);
    initConstant(env, c, "SOCK_RAW", SOCK_RAW);
    initConstant(env, c, "SOCK_SEQPACKET", SOCK_SEQPACKET);
    initConstant(env, c, "SOCK_STREAM", SOCK_STREAM);
    initConstant(env, c, "SOL_SOCKET", SOL_SOCKET);
#if defined(SOL_UDP)
    initConstant(env, c, "SOL_UDP", SOL_UDP);
#endif
    initConstant(env, c, "SOL_PACKET", SOL_PACKET);
#if defined(SO_BINDTODEVICE)
    initConstant(env, c, "SO_BINDTODEVICE", SO_BINDTODEVICE);
#endif
    initConstant(env, c, "SO_BROADCAST", SO_BROADCAST);
    initConstant(env, c, "SO_DEBUG", SO_DEBUG);
#if defined(SO_DOMAIN)
    initConstant(env, c, "SO_DOMAIN", SO_DOMAIN);
#endif
    initConstant(env, c, "SO_DONTROUTE", SO_DONTROUTE);
    initConstant(env, c, "SO_ERROR", SO_ERROR);
    initConstant(env, c, "SO_KEEPALIVE", SO_KEEPALIVE);
    initConstant(env, c, "SO_LINGER", SO_LINGER);
    initConstant(env, c, "SO_OOBINLINE", SO_OOBINLINE);
#if defined(SO_PASSCRED)
    initConstant(env, c, "SO_PASSCRED", SO_PASSCRED);
#endif
#if defined(SO_PEERCRED)
    initConstant(env, c, "SO_PEERCRED", SO_PEERCRED);
#endif
#if defined(SO_PROTOCOL)
    initConstant(env, c, "SO_PROTOCOL", SO_PROTOCOL);
#endif
    initConstant(env, c, "SO_RCVBUF", SO_RCVBUF);
    initConstant(env, c, "SO_RCVLOWAT", SO_RCVLOWAT);
    initConstant(env, c, "SO_RCVTIMEO", SO_RCVTIMEO);
    initConstant(env, c, "SO_REUSEADDR", SO_REUSEADDR);
    initConstant(env, c, "SO_SNDBUF", SO_SNDBUF);
    initConstant(env, c, "SO_SNDLOWAT", SO_SNDLOWAT);
    initConstant(env, c, "SO_SNDTIMEO", SO_SNDTIMEO);
    initConstant(env, c, "SO_TYPE", SO_TYPE);
#if defined(PACKET_IGNORE_OUTGOING)
    initConstant(env, c, "PACKET_IGNORE_OUTGOING", PACKET_IGNORE_OUTGOING);
#endif
    initConstant(env, c, "SPLICE_F_MOVE", SPLICE_F_MOVE);
    initConstant(env, c, "SPLICE_F_NONBLOCK", SPLICE_F_NONBLOCK);
    initConstant(env, c, "SPLICE_F_MORE", SPLICE_F_MORE);
    initConstant(env, c, "STDERR_FILENO", STDERR_FILENO);
    initConstant(env, c, "STDIN_FILENO", STDIN_FILENO);
    initConstant(env, c, "STDOUT_FILENO", STDOUT_FILENO);
    initConstant(env, c, "ST_MANDLOCK", ST_MANDLOCK);
    initConstant(env, c, "ST_NOATIME", ST_NOATIME);
    initConstant(env, c, "ST_NODEV", ST_NODEV);
    initConstant(env, c, "ST_NODIRATIME", ST_NODIRATIME);
    initConstant(env, c, "ST_NOEXEC", ST_NOEXEC);
    initConstant(env, c, "ST_NOSUID", ST_NOSUID);
    initConstant(env, c, "ST_RDONLY", ST_RDONLY);
    initConstant(env, c, "ST_RELATIME", ST_RELATIME);
    initConstant(env, c, "ST_SYNCHRONOUS", ST_SYNCHRONOUS);
    initConstant(env, c, "S_IFBLK", S_IFBLK);
    initConstant(env, c, "S_IFCHR", S_IFCHR);
    initConstant(env, c, "S_IFDIR", S_IFDIR);
    initConstant(env, c, "S_IFIFO", S_IFIFO);
    initConstant(env, c, "S_IFLNK", S_IFLNK);
    initConstant(env, c, "S_IFMT", S_IFMT);
    initConstant(env, c, "S_IFREG", S_IFREG);
    initConstant(env, c, "S_IFSOCK", S_IFSOCK);
    initConstant(env, c, "S_IRGRP", S_IRGRP);
    initConstant(env, c, "S_IROTH", S_IROTH);
    initConstant(env, c, "S_IRUSR", S_IRUSR);
    initConstant(env, c, "S_IRWXG", S_IRWXG);
    initConstant(env, c, "S_IRWXO", S_IRWXO);
    initConstant(env, c, "S_IRWXU", S_IRWXU);
    initConstant(env, c, "S_ISGID", S_ISGID);
    initConstant(env, c, "S_ISUID", S_ISUID);
    initConstant(env, c, "S_ISVTX", S_ISVTX);
    initConstant(env, c, "S_IWGRP", S_IWGRP);
    initConstant(env, c, "S_IWOTH", S_IWOTH);
    initConstant(env, c, "S_IWUSR", S_IWUSR);
    initConstant(env, c, "S_IXGRP", S_IXGRP);
    initConstant(env, c, "S_IXOTH", S_IXOTH);
    initConstant(env, c, "S_IXUSR", S_IXUSR);
    initConstant(env, c, "TCP_NODELAY", TCP_NODELAY);
#if defined(TCP_USER_TIMEOUT)
    initConstant(env, c, "TCP_USER_TIMEOUT", TCP_USER_TIMEOUT);
#endif
    initConstant(env, c, "TIOCOUTQ", TIOCOUTQ);
    initConstant(env, c, "UDP_ENCAP", UDP_ENCAP);
    initConstant(env, c, "UDP_ENCAP_ESPINUDP_NON_IKE", UDP_ENCAP_ESPINUDP_NON_IKE);
    initConstant(env, c, "UDP_ENCAP_ESPINUDP", UDP_ENCAP_ESPINUDP);
#if defined(UDP_GRO)
    initConstant(env, c, "UDP_GRO", UDP_GRO);
#endif
#if defined(UDP_SEGMENT)
    initConstant(env, c, "UDP_SEGMENT", UDP_SEGMENT);
#endif
    // UNIX_PATH_MAX is mentioned in some versions of unix(7), but not actually declared.
    initConstant(env, c, "UNIX_PATH_MAX", sizeof(sockaddr_un::sun_path));
    initConstant(env, c, "WCONTINUED", WCONTINUED);
    initConstant(env, c, "WEXITED", WEXITED);
    initConstant(env, c, "WNOHANG", WNOHANG);
    initConstant(env, c, "WNOWAIT", WNOWAIT);
    initConstant(env, c, "WSTOPPED", WSTOPPED);
    initConstant(env, c, "WUNTRACED", WUNTRACED);
    initConstant(env, c, "W_OK", W_OK);
    initConstant(env, c, "XATTR_CREATE", XATTR_CREATE);
    initConstant(env, c, "XATTR_REPLACE", XATTR_REPLACE);
    initConstant(env, c, "X_OK", X_OK);
    initConstant(env, c, "_SC_2_CHAR_TERM", _SC_2_CHAR_TERM);
    initConstant(env, c, "_SC_2_C_BIND", _SC_2_C_BIND);
    initConstant(env, c, "_SC_2_C_DEV", _SC_2_C_DEV);
#if defined(_SC_2_C_VERSION)
    initConstant(env, c, "_SC_2_C_VERSION", _SC_2_C_VERSION);
#endif
    initConstant(env, c, "_SC_2_FORT_DEV", _SC_2_FORT_DEV);
    initConstant(env, c, "_SC_2_FORT_RUN", _SC_2_FORT_RUN);
    initConstant(env, c, "_SC_2_LOCALEDEF", _SC_2_LOCALEDEF);
    initConstant(env, c, "_SC_2_SW_DEV", _SC_2_SW_DEV);
    initConstant(env, c, "_SC_2_UPE", _SC_2_UPE);
    initConstant(env, c, "_SC_2_VERSION", _SC_2_VERSION);
    initConstant(env, c, "_SC_AIO_LISTIO_MAX", _SC_AIO_LISTIO_MAX);
    initConstant(env, c, "_SC_AIO_MAX", _SC_AIO_MAX);
    initConstant(env, c, "_SC_AIO_PRIO_DELTA_MAX", _SC_AIO_PRIO_DELTA_MAX);
    initConstant(env, c, "_SC_ARG_MAX", _SC_ARG_MAX);
    initConstant(env, c, "_SC_ASYNCHRONOUS_IO", _SC_ASYNCHRONOUS_IO);
    initConstant(env, c, "_SC_ATEXIT_MAX", _SC_ATEXIT_MAX);
#if defined(_SC_AVPHYS_PAGES)
    initConstant(env, c, "_SC_AVPHYS_PAGES", _SC_AVPHYS_PAGES);
#endif
    initConstant(env, c, "_SC_BC_BASE_MAX", _SC_BC_BASE_MAX);
    initConstant(env, c, "_SC_BC_DIM_MAX", _SC_BC_DIM_MAX);
    initConstant(env, c, "_SC_BC_SCALE_MAX", _SC_BC_SCALE_MAX);
    initConstant(env, c, "_SC_BC_STRING_MAX", _SC_BC_STRING_MAX);
    initConstant(env, c, "_SC_CHILD_MAX", _SC_CHILD_MAX);
    initConstant(env, c, "_SC_CLK_TCK", _SC_CLK_TCK);
    initConstant(env, c, "_SC_COLL_WEIGHTS_MAX", _SC_COLL_WEIGHTS_MAX);
    initConstant(env, c, "_SC_DELAYTIMER_MAX", _SC_DELAYTIMER_MAX);
    initConstant(env, c, "_SC_EXPR_NEST_MAX", _SC_EXPR_NEST_MAX);
    initConstant(env, c, "_SC_FSYNC", _SC_FSYNC);
    initConstant(env, c, "_SC_GETGR_R_SIZE_MAX", _SC_GETGR_R_SIZE_MAX);
    initConstant(env, c, "_SC_GETPW_R_SIZE_MAX", _SC_GETPW_R_SIZE_MAX);
    initConstant(env, c, "_SC_IOV_MAX", _SC_IOV_MAX);
    initConstant(env, c, "_SC_JOB_CONTROL", _SC_JOB_CONTROL);
    initConstant(env, c, "_SC_LINE_MAX", _SC_LINE_MAX);
    initConstant(env, c, "_SC_LOGIN_NAME_MAX", _SC_LOGIN_NAME_MAX);
    initConstant(env, c, "_SC_MAPPED_FILES", _SC_MAPPED_FILES);
    initConstant(env, c, "_SC_MEMLOCK", _SC_MEMLOCK);
    initConstant(env, c, "_SC_MEMLOCK_RANGE", _SC_MEMLOCK_RANGE);
    initConstant(env, c, "_SC_MEMORY_PROTECTION", _SC_MEMORY_PROTECTION);
    initConstant(env, c, "_SC_MESSAGE_PASSING", _SC_MESSAGE_PASSING);
    initConstant(env, c, "_SC_MQ_OPEN_MAX", _SC_MQ_OPEN_MAX);
    initConstant(env, c, "_SC_MQ_PRIO_MAX", _SC_MQ_PRIO_MAX);
    initConstant(env, c, "_SC_NGROUPS_MAX", _SC_NGROUPS_MAX);
    initConstant(env, c, "_SC_NPROCESSORS_CONF", _SC_NPROCESSORS_CONF);
    initConstant(env, c, "_SC_NPROCESSORS_ONLN", _SC_NPROCESSORS_ONLN);
    initConstant(env, c, "_SC_OPEN_MAX", _SC_OPEN_MAX);
    initConstant(env, c, "_SC_PAGESIZE", _SC_PAGESIZE);
    initConstant(env, c, "_SC_PAGE_SIZE", _SC_PAGE_SIZE);
    initConstant(env, c, "_SC_PASS_MAX", _SC_PASS_MAX);
#if defined(_SC_PHYS_PAGES)
    initConstant(env, c, "_SC_PHYS_PAGES", _SC_PHYS_PAGES);
#endif
    initConstant(env, c, "_SC_PRIORITIZED_IO", _SC_PRIORITIZED_IO);
    initConstant(env, c, "_SC_PRIORITY_SCHEDULING", _SC_PRIORITY_SCHEDULING);
    initConstant(env, c, "_SC_REALTIME_SIGNALS", _SC_REALTIME_SIGNALS);
    initConstant(env, c, "_SC_RE_DUP_MAX", _SC_RE_DUP_MAX);
    initConstant(env, c, "_SC_RTSIG_MAX", _SC_RTSIG_MAX);
    initConstant(env, c, "_SC_SAVED_IDS", _SC_SAVED_IDS);
    initConstant(env, c, "_SC_SEMAPHORES", _SC_SEMAPHORES);
    initConstant(env, c, "_SC_SEM_NSEMS_MAX", _SC_SEM_NSEMS_MAX);
    initConstant(env, c, "_SC_SEM_VALUE_MAX", _SC_SEM_VALUE_MAX);
    initConstant(env, c, "_SC_SHARED_MEMORY_OBJECTS", _SC_SHARED_MEMORY_OBJECTS);
    initConstant(env, c, "_SC_SIGQUEUE_MAX", _SC_SIGQUEUE_MAX);
    initConstant(env, c, "_SC_STREAM_MAX", _SC_STREAM_MAX);
    initConstant(env, c, "_SC_SYNCHRONIZED_IO", _SC_SYNCHRONIZED_IO);
    initConstant(env, c, "_SC_THREADS", _SC_THREADS);
    initConstant(env, c, "_SC_THREAD_ATTR_STACKADDR", _SC_THREAD_ATTR_STACKADDR);
    initConstant(env, c, "_SC_THREAD_ATTR_STACKSIZE", _SC_THREAD_ATTR_STACKSIZE);
    initConstant(env, c, "_SC_THREAD_DESTRUCTOR_ITERATIONS", _SC_THREAD_DESTRUCTOR_ITERATIONS);
    initConstant(env, c, "_SC_THREAD_KEYS_MAX", _SC_THREAD_KEYS_MAX);
    initConstant(env, c, "_SC_THREAD_PRIORITY_SCHEDULING", _SC_THREAD_PRIORITY_SCHEDULING);
    initConstant(env, c, "_SC_THREAD_PRIO_INHERIT", _SC_THREAD_PRIO_INHERIT);
    initConstant(env, c, "_SC_THREAD_PRIO_PROTECT", _SC_THREAD_PRIO_PROTECT);
    initConstant(env, c, "_SC_THREAD_SAFE_FUNCTIONS", _SC_THREAD_SAFE_FUNCTIONS);
    initConstant(env, c, "_SC_THREAD_STACK_MIN", _SC_THREAD_STACK_MIN);
    initConstant(env, c, "_SC_THREAD_THREADS_MAX", _SC_THREAD_THREADS_MAX);
    initConstant(env, c, "_SC_TIMERS", _SC_TIMERS);
    initConstant(env, c, "_SC_TIMER_MAX", _SC_TIMER_MAX);
    initConstant(env, c, "_SC_TTY_NAME_MAX", _SC_TTY_NAME_MAX);
    initConstant(env, c, "_SC_TZNAME_MAX", _SC_TZNAME_MAX);
    initConstant(env, c, "_SC_VERSION", _SC_VERSION);
    initConstant(env, c, "_SC_XBS5_ILP32_OFF32", _SC_XBS5_ILP32_OFF32);
    initConstant(env, c, "_SC_XBS5_ILP32_OFFBIG", _SC_XBS5_ILP32_OFFBIG);
    initConstant(env, c, "_SC_XBS5_LP64_OFF64", _SC_XBS5_LP64_OFF64);
    initConstant(env, c, "_SC_XBS5_LPBIG_OFFBIG", _SC_XBS5_LPBIG_OFFBIG);
    initConstant(env, c, "_SC_XOPEN_CRYPT", _SC_XOPEN_CRYPT);
    initConstant(env, c, "_SC_XOPEN_ENH_I18N", _SC_XOPEN_ENH_I18N);
    initConstant(env, c, "_SC_XOPEN_LEGACY", _SC_XOPEN_LEGACY);
    initConstant(env, c, "_SC_XOPEN_REALTIME", _SC_XOPEN_REALTIME);
    initConstant(env, c, "_SC_XOPEN_REALTIME_THREADS", _SC_XOPEN_REALTIME_THREADS);
    initConstant(env, c, "_SC_XOPEN_SHM", _SC_XOPEN_SHM);
    initConstant(env, c, "_SC_XOPEN_UNIX", _SC_XOPEN_UNIX);
    initConstant(env, c, "_SC_XOPEN_VERSION", _SC_XOPEN_VERSION);
    initConstant(env, c, "_SC_XOPEN_XCU_VERSION", _SC_XOPEN_XCU_VERSION);
}

static JNINativeMethod gMethods[] = {
    NATIVE_METHOD(OsConstants, initConstants, "()V"),
};

void register_android_system_OsConstants(JNIEnv* env) {
    // [ravenwood-change] -- method moved to the nested class
    jniRegisterNativeMethods(env, "android/system/OsConstants$Native", gMethods, NELEM(gMethods));
}
