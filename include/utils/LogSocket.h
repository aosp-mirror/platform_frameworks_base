/* utils/LogSocket.h
** 
** Copyright 2008, The Android Open Source Project
**
** This file is dual licensed.  It may be redistributed and/or modified
** under the terms of the Apache 2.0 License OR version 2 of the GNU
** General Public License.
*/

#ifndef _UTILS_LOGSOCKET_H
#define _UTILS_LOGSOCKET_H

#define SOCKET_CLOSE_LOCAL 0

void add_send_stats(int fd, int send);
void add_recv_stats(int fd, int recv);
void log_socket_close(int fd, short reason);
void log_socket_connect(int fd, unsigned int ip, unsigned short port);

#endif /* _UTILS_LOGSOCKET_H */
