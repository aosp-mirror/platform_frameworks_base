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
package com.android.ravenwoodtest.runtimetest;

// Copied from libcore/luni/src/test/java/libcore/android/system/OsConstantsTest.java

import static android.system.OsConstants.CAP_TO_INDEX;
import static android.system.OsConstants.CAP_TO_MASK;
import static android.system.OsConstants.S_ISBLK;
import static android.system.OsConstants.S_ISCHR;
import static android.system.OsConstants.S_ISDIR;
import static android.system.OsConstants.S_ISFIFO;
import static android.system.OsConstants.S_ISLNK;
import static android.system.OsConstants.S_ISREG;
import static android.system.OsConstants.S_ISSOCK;
import static android.system.OsConstants.WCOREDUMP;
import static android.system.OsConstants.WEXITSTATUS;
import static android.system.OsConstants.WIFEXITED;
import static android.system.OsConstants.WIFSIGNALED;
import static android.system.OsConstants.WIFSTOPPED;
import static android.system.OsConstants.WSTOPSIG;
import static android.system.OsConstants.WTERMSIG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.system.OsConstants;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OsConstantsTest {

    // http://b/15602893
    @Test
    public void testBug15602893() {
        assertTrue(OsConstants.RT_SCOPE_HOST > 0);
        assertTrue(OsConstants.RT_SCOPE_LINK > 0);
        assertTrue(OsConstants.RT_SCOPE_SITE > 0);

        assertTrue(OsConstants.IFA_F_TENTATIVE > 0);
    }

    // introduced for http://b/30402085
    @Test
    public void testTcpUserTimeoutIsDefined() {
        assertTrue(OsConstants.TCP_USER_TIMEOUT > 0);
    }

    /**
     * Verifies equality assertions given in the documentation for
     * {@link OsConstants#SOCK_CLOEXEC} and {@link OsConstants#SOCK_NONBLOCK}.
     */
    @Test
    public void testConstantsEqual() {
        assertEquals(OsConstants.O_CLOEXEC,  OsConstants.SOCK_CLOEXEC);
        assertEquals(OsConstants.O_NONBLOCK, OsConstants.SOCK_NONBLOCK);
    }

    @Test
    public void test_CAP_constants() {
        assertEquals(0,  OsConstants.CAP_CHOWN);
        assertEquals(1,  OsConstants.CAP_DAC_OVERRIDE);
        assertEquals(2,  OsConstants.CAP_DAC_READ_SEARCH);
        assertEquals(3,  OsConstants.CAP_FOWNER);
        assertEquals(4,  OsConstants.CAP_FSETID);
        assertEquals(5,  OsConstants.CAP_KILL);
        assertEquals(6,  OsConstants.CAP_SETGID);
        assertEquals(7,  OsConstants.CAP_SETUID);
        assertEquals(8,  OsConstants.CAP_SETPCAP);
        assertEquals(9,  OsConstants.CAP_LINUX_IMMUTABLE);
        assertEquals(10, OsConstants.CAP_NET_BIND_SERVICE);
        assertEquals(11, OsConstants.CAP_NET_BROADCAST);
        assertEquals(12, OsConstants.CAP_NET_ADMIN);
        assertEquals(13, OsConstants.CAP_NET_RAW);
        assertEquals(14, OsConstants.CAP_IPC_LOCK);
        assertEquals(15, OsConstants.CAP_IPC_OWNER);
        assertEquals(16, OsConstants.CAP_SYS_MODULE);
        assertEquals(17, OsConstants.CAP_SYS_RAWIO);
        assertEquals(18, OsConstants.CAP_SYS_CHROOT);
        assertEquals(19, OsConstants.CAP_SYS_PTRACE);
        assertEquals(20, OsConstants.CAP_SYS_PACCT);
        assertEquals(21, OsConstants.CAP_SYS_ADMIN);
        assertEquals(22, OsConstants.CAP_SYS_BOOT);
        assertEquals(23, OsConstants.CAP_SYS_NICE);
        assertEquals(24, OsConstants.CAP_SYS_RESOURCE);
        assertEquals(25, OsConstants.CAP_SYS_TIME);
        assertEquals(26, OsConstants.CAP_SYS_TTY_CONFIG);
        assertEquals(27, OsConstants.CAP_MKNOD);
        assertEquals(28, OsConstants.CAP_LEASE);
        assertEquals(29, OsConstants.CAP_AUDIT_WRITE);
        assertEquals(30, OsConstants.CAP_AUDIT_CONTROL);
        assertEquals(31, OsConstants.CAP_SETFCAP);
        assertEquals(32, OsConstants.CAP_MAC_OVERRIDE);
        assertEquals(33, OsConstants.CAP_MAC_ADMIN);
        assertEquals(34, OsConstants.CAP_SYSLOG);
        assertEquals(35, OsConstants.CAP_WAKE_ALARM);
        assertEquals(36, OsConstants.CAP_BLOCK_SUSPEND);
        // last constant
        assertEquals(40, OsConstants.CAP_LAST_CAP);
    }

    @Test
    public void test_CAP_TO_INDEX() {
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_CHOWN));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_DAC_OVERRIDE));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_DAC_READ_SEARCH));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_FOWNER));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_FSETID));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_KILL));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SETGID));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SETUID));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SETPCAP));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_LINUX_IMMUTABLE));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_NET_BIND_SERVICE));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_NET_BROADCAST));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_NET_ADMIN));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_NET_RAW));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_IPC_LOCK));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_IPC_OWNER));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_MODULE));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_RAWIO));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_CHROOT));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_PTRACE));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_PACCT));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_ADMIN));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_BOOT));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_NICE));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_RESOURCE));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_TIME));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SYS_TTY_CONFIG));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_MKNOD));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_LEASE));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_AUDIT_WRITE));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_AUDIT_CONTROL));
        assertEquals(0, CAP_TO_INDEX(OsConstants.CAP_SETFCAP));
        assertEquals(1, CAP_TO_INDEX(OsConstants.CAP_MAC_OVERRIDE));
        assertEquals(1, CAP_TO_INDEX(OsConstants.CAP_MAC_ADMIN));
        assertEquals(1, CAP_TO_INDEX(OsConstants.CAP_SYSLOG));
        assertEquals(1, CAP_TO_INDEX(OsConstants.CAP_WAKE_ALARM));
        assertEquals(1, CAP_TO_INDEX(OsConstants.CAP_BLOCK_SUSPEND));
    }

    @Test
    public void test_CAP_TO_MASK() {
        assertEquals(1 << 0,  CAP_TO_MASK(OsConstants.CAP_CHOWN));
        assertEquals(1 << 1,  CAP_TO_MASK(OsConstants.CAP_DAC_OVERRIDE));
        assertEquals(1 << 2,  CAP_TO_MASK(OsConstants.CAP_DAC_READ_SEARCH));
        assertEquals(1 << 3,  CAP_TO_MASK(OsConstants.CAP_FOWNER));
        assertEquals(1 << 4,  CAP_TO_MASK(OsConstants.CAP_FSETID));
        assertEquals(1 << 5,  CAP_TO_MASK(OsConstants.CAP_KILL));
        assertEquals(1 << 6,  CAP_TO_MASK(OsConstants.CAP_SETGID));
        assertEquals(1 << 7,  CAP_TO_MASK(OsConstants.CAP_SETUID));
        assertEquals(1 << 8,  CAP_TO_MASK(OsConstants.CAP_SETPCAP));
        assertEquals(1 << 9,  CAP_TO_MASK(OsConstants.CAP_LINUX_IMMUTABLE));
        assertEquals(1 << 10, CAP_TO_MASK(OsConstants.CAP_NET_BIND_SERVICE));
        assertEquals(1 << 11, CAP_TO_MASK(OsConstants.CAP_NET_BROADCAST));
        assertEquals(1 << 12, CAP_TO_MASK(OsConstants.CAP_NET_ADMIN));
        assertEquals(1 << 13, CAP_TO_MASK(OsConstants.CAP_NET_RAW));
        assertEquals(1 << 14, CAP_TO_MASK(OsConstants.CAP_IPC_LOCK));
        assertEquals(1 << 15, CAP_TO_MASK(OsConstants.CAP_IPC_OWNER));
        assertEquals(1 << 16, CAP_TO_MASK(OsConstants.CAP_SYS_MODULE));
        assertEquals(1 << 17, CAP_TO_MASK(OsConstants.CAP_SYS_RAWIO));
        assertEquals(1 << 18, CAP_TO_MASK(OsConstants.CAP_SYS_CHROOT));
        assertEquals(1 << 19, CAP_TO_MASK(OsConstants.CAP_SYS_PTRACE));
        assertEquals(1 << 20, CAP_TO_MASK(OsConstants.CAP_SYS_PACCT));
        assertEquals(1 << 21, CAP_TO_MASK(OsConstants.CAP_SYS_ADMIN));
        assertEquals(1 << 22, CAP_TO_MASK(OsConstants.CAP_SYS_BOOT));
        assertEquals(1 << 23, CAP_TO_MASK(OsConstants.CAP_SYS_NICE));
        assertEquals(1 << 24, CAP_TO_MASK(OsConstants.CAP_SYS_RESOURCE));
        assertEquals(1 << 25, CAP_TO_MASK(OsConstants.CAP_SYS_TIME));
        assertEquals(1 << 26, CAP_TO_MASK(OsConstants.CAP_SYS_TTY_CONFIG));
        assertEquals(1 << 27, CAP_TO_MASK(OsConstants.CAP_MKNOD));
        assertEquals(1 << 28, CAP_TO_MASK(OsConstants.CAP_LEASE));
        assertEquals(1 << 29, CAP_TO_MASK(OsConstants.CAP_AUDIT_WRITE));
        assertEquals(1 << 30, CAP_TO_MASK(OsConstants.CAP_AUDIT_CONTROL));
        assertEquals(1 << 31, CAP_TO_MASK(OsConstants.CAP_SETFCAP));
        assertEquals(1 << 0,  CAP_TO_MASK(OsConstants.CAP_MAC_OVERRIDE));
        assertEquals(1 << 1,  CAP_TO_MASK(OsConstants.CAP_MAC_ADMIN));
        assertEquals(1 << 2,  CAP_TO_MASK(OsConstants.CAP_SYSLOG));
        assertEquals(1 << 3,  CAP_TO_MASK(OsConstants.CAP_WAKE_ALARM));
        assertEquals(1 << 4,  CAP_TO_MASK(OsConstants.CAP_BLOCK_SUSPEND));
    }

    @Test
    public void test_S_ISLNK() {
        assertTrue(S_ISLNK(OsConstants.S_IFLNK));

        assertFalse(S_ISLNK(OsConstants.S_IFBLK));
        assertFalse(S_ISLNK(OsConstants.S_IFCHR));
        assertFalse(S_ISLNK(OsConstants.S_IFDIR));
        assertFalse(S_ISLNK(OsConstants.S_IFIFO));
        assertFalse(S_ISLNK(OsConstants.S_IFMT));
        assertFalse(S_ISLNK(OsConstants.S_IFREG));
        assertFalse(S_ISLNK(OsConstants.S_IFSOCK));
        assertFalse(S_ISLNK(OsConstants.S_IRGRP));
        assertFalse(S_ISLNK(OsConstants.S_IROTH));
        assertFalse(S_ISLNK(OsConstants.S_IRUSR));
        assertFalse(S_ISLNK(OsConstants.S_IRWXG));
        assertFalse(S_ISLNK(OsConstants.S_IRWXO));
        assertFalse(S_ISLNK(OsConstants.S_IRWXU));
        assertFalse(S_ISLNK(OsConstants.S_ISGID));
        assertFalse(S_ISLNK(OsConstants.S_ISUID));
        assertFalse(S_ISLNK(OsConstants.S_ISVTX));
        assertFalse(S_ISLNK(OsConstants.S_IWGRP));
        assertFalse(S_ISLNK(OsConstants.S_IWOTH));
        assertFalse(S_ISLNK(OsConstants.S_IWUSR));
        assertFalse(S_ISLNK(OsConstants.S_IXGRP));
        assertFalse(S_ISLNK(OsConstants.S_IXOTH));
        assertFalse(S_ISLNK(OsConstants.S_IXUSR));
    }

    @Test
    public void test_S_ISREG() {
        assertTrue(S_ISREG(OsConstants.S_IFREG));

        assertFalse(S_ISREG(OsConstants.S_IFBLK));
        assertFalse(S_ISREG(OsConstants.S_IFCHR));
        assertFalse(S_ISREG(OsConstants.S_IFDIR));
        assertFalse(S_ISREG(OsConstants.S_IFIFO));
        assertFalse(S_ISREG(OsConstants.S_IFLNK));
        assertFalse(S_ISREG(OsConstants.S_IFMT));
        assertFalse(S_ISREG(OsConstants.S_IFSOCK));
        assertFalse(S_ISREG(OsConstants.S_IRGRP));
        assertFalse(S_ISREG(OsConstants.S_IROTH));
        assertFalse(S_ISREG(OsConstants.S_IRUSR));
        assertFalse(S_ISREG(OsConstants.S_IRWXG));
        assertFalse(S_ISREG(OsConstants.S_IRWXO));
        assertFalse(S_ISREG(OsConstants.S_IRWXU));
        assertFalse(S_ISREG(OsConstants.S_ISGID));
        assertFalse(S_ISREG(OsConstants.S_ISUID));
        assertFalse(S_ISREG(OsConstants.S_ISVTX));
        assertFalse(S_ISREG(OsConstants.S_IWGRP));
        assertFalse(S_ISREG(OsConstants.S_IWOTH));
        assertFalse(S_ISREG(OsConstants.S_IWUSR));
        assertFalse(S_ISREG(OsConstants.S_IXGRP));
        assertFalse(S_ISREG(OsConstants.S_IXOTH));
        assertFalse(S_ISREG(OsConstants.S_IXUSR));
    }

    @Test
    public void test_S_ISDIR() {
        assertTrue(S_ISDIR(OsConstants.S_IFDIR));

        assertFalse(S_ISDIR(OsConstants.S_IFBLK));
        assertFalse(S_ISDIR(OsConstants.S_IFCHR));
        assertFalse(S_ISDIR(OsConstants.S_IFIFO));
        assertFalse(S_ISDIR(OsConstants.S_IFLNK));
        assertFalse(S_ISDIR(OsConstants.S_IFMT));
        assertFalse(S_ISDIR(OsConstants.S_IFREG));
        assertFalse(S_ISDIR(OsConstants.S_IFSOCK));
        assertFalse(S_ISDIR(OsConstants.S_IRGRP));
        assertFalse(S_ISDIR(OsConstants.S_IROTH));
        assertFalse(S_ISDIR(OsConstants.S_IRUSR));
        assertFalse(S_ISDIR(OsConstants.S_IRWXG));
        assertFalse(S_ISDIR(OsConstants.S_IRWXO));
        assertFalse(S_ISDIR(OsConstants.S_IRWXU));
        assertFalse(S_ISDIR(OsConstants.S_ISGID));
        assertFalse(S_ISDIR(OsConstants.S_ISUID));
        assertFalse(S_ISDIR(OsConstants.S_ISVTX));
        assertFalse(S_ISDIR(OsConstants.S_IWGRP));
        assertFalse(S_ISDIR(OsConstants.S_IWOTH));
        assertFalse(S_ISDIR(OsConstants.S_IWUSR));
        assertFalse(S_ISDIR(OsConstants.S_IXGRP));
        assertFalse(S_ISDIR(OsConstants.S_IXOTH));
        assertFalse(S_ISDIR(OsConstants.S_IXUSR));
    }

    @Test
    public void test_S_ISCHR() {
        assertTrue(S_ISCHR(OsConstants.S_IFCHR));

        assertFalse(S_ISCHR(OsConstants.S_IFBLK));
        assertFalse(S_ISCHR(OsConstants.S_IFDIR));
        assertFalse(S_ISCHR(OsConstants.S_IFIFO));
        assertFalse(S_ISCHR(OsConstants.S_IFLNK));
        assertFalse(S_ISCHR(OsConstants.S_IFMT));
        assertFalse(S_ISCHR(OsConstants.S_IFREG));
        assertFalse(S_ISCHR(OsConstants.S_IFSOCK));
        assertFalse(S_ISCHR(OsConstants.S_IRGRP));
        assertFalse(S_ISCHR(OsConstants.S_IROTH));
        assertFalse(S_ISCHR(OsConstants.S_IRUSR));
        assertFalse(S_ISCHR(OsConstants.S_IRWXG));
        assertFalse(S_ISCHR(OsConstants.S_IRWXO));
        assertFalse(S_ISCHR(OsConstants.S_IRWXU));
        assertFalse(S_ISCHR(OsConstants.S_ISGID));
        assertFalse(S_ISCHR(OsConstants.S_ISUID));
        assertFalse(S_ISCHR(OsConstants.S_ISVTX));
        assertFalse(S_ISCHR(OsConstants.S_IWGRP));
        assertFalse(S_ISCHR(OsConstants.S_IWOTH));
        assertFalse(S_ISCHR(OsConstants.S_IWUSR));
        assertFalse(S_ISCHR(OsConstants.S_IXGRP));
        assertFalse(S_ISCHR(OsConstants.S_IXOTH));
        assertFalse(S_ISCHR(OsConstants.S_IXUSR));
    }

    @Test
    public void test_S_ISBLK() {
        assertTrue (S_ISBLK(OsConstants.S_IFBLK));

        assertFalse(S_ISBLK(OsConstants.S_IFCHR));
        assertFalse(S_ISBLK(OsConstants.S_IFDIR));
        assertFalse(S_ISBLK(OsConstants.S_IFIFO));
        assertFalse(S_ISBLK(OsConstants.S_IFLNK));
        assertFalse(S_ISBLK(OsConstants.S_IFMT));
        assertFalse(S_ISBLK(OsConstants.S_IFREG));
        assertFalse(S_ISBLK(OsConstants.S_IFSOCK));
        assertFalse(S_ISBLK(OsConstants.S_IRGRP));
        assertFalse(S_ISBLK(OsConstants.S_IROTH));
        assertFalse(S_ISBLK(OsConstants.S_IRUSR));
        assertFalse(S_ISBLK(OsConstants.S_IRWXG));
        assertFalse(S_ISBLK(OsConstants.S_IRWXO));
        assertFalse(S_ISBLK(OsConstants.S_IRWXU));
        assertFalse(S_ISBLK(OsConstants.S_ISGID));
        assertFalse(S_ISBLK(OsConstants.S_ISUID));
        assertFalse(S_ISBLK(OsConstants.S_ISVTX));
        assertFalse(S_ISBLK(OsConstants.S_IWGRP));
        assertFalse(S_ISBLK(OsConstants.S_IWOTH));
        assertFalse(S_ISBLK(OsConstants.S_IWUSR));
        assertFalse(S_ISBLK(OsConstants.S_IXGRP));
        assertFalse(S_ISBLK(OsConstants.S_IXOTH));
        assertFalse(S_ISBLK(OsConstants.S_IXUSR));
    }

    @Test
    public void test_S_ISFIFO() {
        assertTrue(S_ISFIFO(OsConstants.S_IFIFO));

        assertFalse(S_ISFIFO(OsConstants.S_IFBLK));
        assertFalse(S_ISFIFO(OsConstants.S_IFCHR));
        assertFalse(S_ISFIFO(OsConstants.S_IFDIR));
        assertFalse(S_ISFIFO(OsConstants.S_IFLNK));
        assertFalse(S_ISFIFO(OsConstants.S_IFMT));
        assertFalse(S_ISFIFO(OsConstants.S_IFREG));
        assertFalse(S_ISFIFO(OsConstants.S_IFSOCK));
        assertFalse(S_ISFIFO(OsConstants.S_IRGRP));
        assertFalse(S_ISFIFO(OsConstants.S_IROTH));
        assertFalse(S_ISFIFO(OsConstants.S_IRUSR));
        assertFalse(S_ISFIFO(OsConstants.S_IRWXG));
        assertFalse(S_ISFIFO(OsConstants.S_IRWXO));
        assertFalse(S_ISFIFO(OsConstants.S_IRWXU));
        assertFalse(S_ISFIFO(OsConstants.S_ISGID));
        assertFalse(S_ISFIFO(OsConstants.S_ISUID));
        assertFalse(S_ISFIFO(OsConstants.S_ISVTX));
        assertFalse(S_ISFIFO(OsConstants.S_IWGRP));
        assertFalse(S_ISFIFO(OsConstants.S_IWOTH));
        assertFalse(S_ISFIFO(OsConstants.S_IWUSR));
        assertFalse(S_ISFIFO(OsConstants.S_IXGRP));
        assertFalse(S_ISFIFO(OsConstants.S_IXOTH));
        assertFalse(S_ISFIFO(OsConstants.S_IXUSR));
    }

    @Test
    public void test_S_ISSOCK() {
        assertTrue(S_ISSOCK(OsConstants.S_IFSOCK));

        assertFalse(S_ISSOCK(OsConstants.S_IFBLK));
        assertFalse(S_ISSOCK(OsConstants.S_IFCHR));
        assertFalse(S_ISSOCK(OsConstants.S_IFDIR));
        assertFalse(S_ISSOCK(OsConstants.S_IFIFO));
        assertFalse(S_ISSOCK(OsConstants.S_IFLNK));
        assertFalse(S_ISSOCK(OsConstants.S_IFMT));
        assertFalse(S_ISSOCK(OsConstants.S_IFREG));
        assertFalse(S_ISSOCK(OsConstants.S_IRGRP));
        assertFalse(S_ISSOCK(OsConstants.S_IROTH));
        assertFalse(S_ISSOCK(OsConstants.S_IRUSR));
        assertFalse(S_ISSOCK(OsConstants.S_IRWXG));
        assertFalse(S_ISSOCK(OsConstants.S_IRWXO));
        assertFalse(S_ISSOCK(OsConstants.S_IRWXU));
        assertFalse(S_ISSOCK(OsConstants.S_ISGID));
        assertFalse(S_ISSOCK(OsConstants.S_ISUID));
        assertFalse(S_ISSOCK(OsConstants.S_ISVTX));
        assertFalse(S_ISSOCK(OsConstants.S_IWGRP));
        assertFalse(S_ISSOCK(OsConstants.S_IWOTH));
        assertFalse(S_ISSOCK(OsConstants.S_IWUSR));
        assertFalse(S_ISSOCK(OsConstants.S_IXGRP));
        assertFalse(S_ISSOCK(OsConstants.S_IXOTH));
        assertFalse(S_ISSOCK(OsConstants.S_IXUSR));
    }

    @Test
    public void test_WEXITSTATUS() {
        assertEquals(0, WEXITSTATUS(0x0000));
        assertEquals(0, WEXITSTATUS(0x00DE));
        assertEquals(0xF0, WEXITSTATUS(0xF000));
        assertEquals(0xAB, WEXITSTATUS(0xAB12));
    }

    @Test
    public void test_WCOREDUMP() {
        assertFalse(WCOREDUMP(0));
        assertTrue(WCOREDUMP(0x80));
    }

    @Test
    public void test_WTERMSIG() {
        assertEquals(0, WTERMSIG(0));
        assertEquals(0x7f, WTERMSIG(0x7f));
    }

    @Test
    public void test_WSTOPSIG() {
        assertEquals(0, WSTOPSIG(0x0000));
        assertEquals(0, WSTOPSIG(0x00DE));
        assertEquals(0xF0, WSTOPSIG(0xF000));
        assertEquals(0xAB, WSTOPSIG(0xAB12));
    }


    @Test
    public void test_WIFEXITED() {
        assertTrue(WIFEXITED(0));
        assertFalse(WIFEXITED(0x7f));
    }

    @Test
    public void test_WIFSTOPPED() {
        assertFalse(WIFSTOPPED(0));
        assertTrue(WIFSTOPPED(0x7f));
    }

    @Test
    public void test_WIFSIGNALED() {
        assertFalse(WIFSIGNALED(0));
        assertTrue(WIFSIGNALED(1));
    }
}
