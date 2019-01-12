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

package com.android.internal.os;

import static android.system.OsConstants.POLLIN;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructPollfd;
import android.util.Log;
import android.util.Slog;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Server socket class for zygote processes.
 *
 * Provides functions to wait for commands on a UNIX domain socket, and fork
 * off child processes that inherit the initial state of the VM.%
 *
 * Please see {@link ZygoteConnection.Arguments} for documentation on the
 * client protocol.
 */
class ZygoteServer {
    // TODO (chriswailes): Change this so it is set with Zygote or ZygoteSecondary as appropriate
    public static final String TAG = "ZygoteServer";

    /**
     * Listening socket that accepts new server connections.
     */
    private LocalServerSocket mZygoteSocket;

    /**
     * Whether or not mZygoteSocket's underlying FD should be closed directly.
     * If mZygoteSocket is created with an existing FD, closing the socket does
     * not close the FD and it must be closed explicitly. If the socket is created
     * with a name instead, then closing the socket will close the underlying FD
     * and it should not be double-closed.
     */
    private boolean mCloseSocketFd;

    /**
     * Set by the child process, immediately after a call to {@code Zygote.forkAndSpecialize}.
     */
    private boolean mIsForkChild;

    ZygoteServer() { }

    void setForkChild() {
        mIsForkChild = true;
    }

    /**
     * Creates a managed object representing the Zygote socket that has already
     * been initialized and bound by init.
     *
     * TODO (chriswailes): Move the name selection logic into this function.
     *
     * @throws RuntimeException when open fails
     */
    void createZygoteSocket(String socketName) {
        if (mZygoteSocket == null) {
            mZygoteSocket = Zygote.createManagedSocketFromInitSocket(socketName);
            mCloseSocketFd = true;
        }
    }

    /**
     * Registers a server socket for zygote command connections. This opens the server socket
     * at the specified name in the abstract socket namespace.
     */
    void registerServerSocketAtAbstractName(String socketName) {
        if (mZygoteSocket == null) {
            try {
                mZygoteSocket = new LocalServerSocket(socketName);
                mCloseSocketFd = false;
            } catch (IOException ex) {
                throw new RuntimeException(
                        "Error binding to abstract socket '" + socketName + "'", ex);
            }
        }
    }

    /**
     * Waits for and accepts a single command connection. Throws
     * RuntimeException on failure.
     */
    private ZygoteConnection acceptCommandPeer(String abiList) {
        try {
            return createNewConnection(mZygoteSocket.accept(), abiList);
        } catch (IOException ex) {
            throw new RuntimeException(
                    "IOException during accept()", ex);
        }
    }

    protected ZygoteConnection createNewConnection(LocalSocket socket, String abiList)
            throws IOException {
        return new ZygoteConnection(socket, abiList);
    }

    /**
     * Close and clean up zygote sockets. Called on shutdown and on the
     * child's exit path.
     */
    void closeServerSocket() {
        try {
            if (mZygoteSocket != null) {
                FileDescriptor fd = mZygoteSocket.getFileDescriptor();
                mZygoteSocket.close();
                if (fd != null && mCloseSocketFd) {
                    Os.close(fd);
                }
            }
        } catch (IOException ex) {
            Log.e(TAG, "Zygote:  error closing sockets", ex);
        } catch (ErrnoException ex) {
            Log.e(TAG, "Zygote:  error closing descriptor", ex);
        }

        mZygoteSocket = null;
    }

    /**
     * Return the server socket's underlying file descriptor, so that
     * ZygoteConnection can pass it to the native code for proper
     * closure after a child process is forked off.
     */

    FileDescriptor getZygoteSocketFileDescriptor() {
        return mZygoteSocket.getFileDescriptor();
    }

    /**
     * Runs the zygote process's select loop. Accepts new connections as
     * they happen, and reads commands from connections one spawn-request's
     * worth at a time.
     */
    Runnable runSelectLoop(String abiList) {
        ArrayList<FileDescriptor> socketFDs = new ArrayList<FileDescriptor>();
        ArrayList<ZygoteConnection> peers = new ArrayList<ZygoteConnection>();

        socketFDs.add(mZygoteSocket.getFileDescriptor());
        peers.add(null);

        while (true) {
            int[] blastulaPipeFDs = Zygote.getBlastulaPipeFDs();

            // Space for all of the socket FDs, the Blastula Pool Event FD, and
            // all of the open blastula read pipe FDs.
            StructPollfd[] pollFDs =
                new StructPollfd[socketFDs.size() + 1 + blastulaPipeFDs.length];

            int pollIndex = 0;
            for (FileDescriptor socketFD : socketFDs) {
                pollFDs[pollIndex] = new StructPollfd();
                pollFDs[pollIndex].fd = socketFD;
                pollFDs[pollIndex].events = (short) POLLIN;
                ++pollIndex;
            }

            final int blastulaPoolEventFDIndex = pollIndex;
            pollFDs[pollIndex] = new StructPollfd();
            pollFDs[pollIndex].fd = Zygote.sBlastulaPoolEventFD;
            pollFDs[pollIndex].events = (short) POLLIN;
            ++pollIndex;

            for (int blastulaPipeFD : blastulaPipeFDs) {
                FileDescriptor managedFd = new FileDescriptor();
                managedFd.setInt$(blastulaPipeFD);

                pollFDs[pollIndex] = new StructPollfd();
                pollFDs[pollIndex].fd = managedFd;
                pollFDs[pollIndex].events = (short) POLLIN;
                ++pollIndex;
            }

            try {
                Os.poll(pollFDs, -1);
            } catch (ErrnoException ex) {
                throw new RuntimeException("poll failed", ex);
            }

            while (--pollIndex >= 0) {
                if ((pollFDs[pollIndex].revents & POLLIN) == 0) {
                    continue;
                }

                if (pollIndex == 0) {
                    // Zygote server socket

                    ZygoteConnection newPeer = acceptCommandPeer(abiList);
                    peers.add(newPeer);
                    socketFDs.add(newPeer.getFileDescriptor());

                } else if (pollIndex < blastulaPoolEventFDIndex) {
                    // Session socket accepted from the Zygote server socket

                    try {
                        ZygoteConnection connection = peers.get(pollIndex);
                        final Runnable command = connection.processOneCommand(this);

                        if (mIsForkChild) {
                            // We're in the child. We should always have a command to run at this
                            // stage if processOneCommand hasn't called "exec".
                            if (command == null) {
                                throw new IllegalStateException("command == null");
                            }

                            return command;
                        } else {
                            // We're in the server - we should never have any commands to run.
                            if (command != null) {
                                throw new IllegalStateException("command != null");
                            }

                            // We don't know whether the remote side of the socket was closed or
                            // not until we attempt to read from it from processOneCommand. This
                            // shows up as a regular POLLIN event in our regular processing loop.
                            if (connection.isClosedByPeer()) {
                                connection.closeSocket();
                                peers.remove(pollIndex);
                                socketFDs.remove(pollIndex);
                            }
                        }
                    } catch (Exception e) {
                        if (!mIsForkChild) {
                            // We're in the server so any exception here is one that has taken place
                            // pre-fork while processing commands or reading / writing from the
                            // control socket. Make a loud noise about any such exceptions so that
                            // we know exactly what failed and why.

                            Slog.e(TAG, "Exception executing zygote command: ", e);

                            // Make sure the socket is closed so that the other end knows
                            // immediately that something has gone wrong and doesn't time out
                            // waiting for a response.
                            ZygoteConnection conn = peers.remove(pollIndex);
                            conn.closeSocket();

                            socketFDs.remove(pollIndex);
                        } else {
                            // We're in the child so any exception caught here has happened post
                            // fork and before we execute ActivityThread.main (or any other main()
                            // method). Log the details of the exception and bring down the process.
                            Log.e(TAG, "Caught post-fork exception in child process.", e);
                            throw e;
                        }
                    } finally {
                        // Reset the child flag, in the event that the child process is a child-
                        // zygote. The flag will not be consulted this loop pass after the Runnable
                        // is returned.
                        mIsForkChild = false;
                    }
                } else {
                    // Either the blastula pool event FD or a blastula reporting pipe.

                    // If this is the event FD the payload will be the number of blastulas removed.
                    // If this is a reporting pipe FD the payload will be the PID of the blastula
                    // that was just specialized.
                    long messagePayload = -1;

                    try {
                        byte[] buffer = new byte[Zygote.BLASTULA_MANAGEMENT_MESSAGE_BYTES];
                        int readBytes = Os.read(pollFDs[pollIndex].fd, buffer, 0, buffer.length);

                        if (readBytes == Zygote.BLASTULA_MANAGEMENT_MESSAGE_BYTES) {
                            DataInputStream inputStream =
                                    new DataInputStream(new ByteArrayInputStream(buffer));

                            messagePayload = inputStream.readLong();
                        } else {
                            Log.e(TAG, "Incomplete read from blastula management FD of size "
                                    + readBytes);
                            continue;
                        }
                    } catch (Exception ex) {
                        if (pollIndex == blastulaPoolEventFDIndex) {
                            Log.e(TAG, "Failed to read from blastula pool event FD: "
                                    + ex.getMessage());
                        } else {
                            Log.e(TAG, "Failed to read from blastula reporting pipe: "
                                    + ex.getMessage());
                        }

                        continue;
                    }

                    if (pollIndex > blastulaPoolEventFDIndex) {
                        Zygote.removeBlastulaTableEntry((int) messagePayload);
                    }

                    int[] sessionSocketRawFDs =
                            socketFDs.subList(1, socketFDs.size())
                                .stream()
                                .mapToInt(fd -> fd.getInt$())
                                .toArray();

                    final Runnable command = Zygote.fillBlastulaPool(sessionSocketRawFDs);

                    if (command != null) {
                        return command;
                    }
                }
            }
        }
    }
}
