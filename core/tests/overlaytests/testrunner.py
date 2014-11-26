#!/usr/bin/python
import hashlib
import optparse
import os
import re
import shlex
import subprocess
import sys
import threading
import time

TASK_COMPILATION = 'compile'
TASK_DISABLE_OVERLAYS = 'disable overlays'
TASK_ENABLE_MULTIPLE_OVERLAYS = 'enable multiple overlays'
TASK_ENABLE_SINGLE_OVERLAY = 'enable single overlay'
TASK_FILE_EXISTS_TEST = 'test (file exists)'
TASK_GREP_IDMAP_TEST = 'test (grep idmap)'
TASK_MD5_TEST = 'test (md5)'
TASK_IDMAP_PATH = 'idmap --path'
TASK_IDMAP_SCAN = 'idmap --scan'
TASK_INSTRUMENTATION = 'instrumentation'
TASK_INSTRUMENTATION_TEST = 'test (instrumentation)'
TASK_MKDIR = 'mkdir'
TASK_PUSH = 'push'
TASK_ROOT = 'root'
TASK_REMOUNT = 'remount'
TASK_RM = 'rm'
TASK_SETUP_IDMAP_PATH = 'setup idmap --path'
TASK_SETUP_IDMAP_SCAN = 'setup idmap --scan'
TASK_START = 'start'
TASK_STOP = 'stop'

adb = 'adb'

def _adb_shell(cmd):
    argv = shlex.split(adb + " shell '" + cmd + "; echo $?'")
    proc = subprocess.Popen(argv, bufsize=1, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    (stdout, stderr) = proc.communicate()
    (stdout, stderr) = (stdout.replace('\r', ''), stderr.replace('\r', ''))
    tmp = stdout.rsplit('\n', 2)
    if len(tmp) == 2:
        stdout == ''
        returncode = int(tmp[0])
    else:
        stdout = tmp[0] + '\n'
        returncode = int(tmp[1])
    return returncode, stdout, stderr

class VerbosePrinter:
    class Ticker(threading.Thread):
        def _print(self):
            s = '\r' + self.text + '[' + '.' * self.i + ' ' * (4 - self.i) + ']'
            sys.stdout.write(s)
            sys.stdout.flush()
            self.i = (self.i + 1) % 5

        def __init__(self, cond_var, text):
            threading.Thread.__init__(self)
            self.text = text
            self.setDaemon(True)
            self.cond_var = cond_var
            self.running = False
            self.i = 0
            self._print()
            self.running = True

        def run(self):
            self.cond_var.acquire()
            while True:
                self.cond_var.wait(0.25)
                running = self.running
                if not running:
                    break
                self._print()
            self.cond_var.release()

        def stop(self):
            self.cond_var.acquire()
            self.running = False
            self.cond_var.notify_all()
            self.cond_var.release()

    def _start_ticker(self):
        self.ticker = VerbosePrinter.Ticker(self.cond_var, self.text)
        self.ticker.start()

    def _stop_ticker(self):
        self.ticker.stop()
        self.ticker.join()
        self.ticker = None

    def _format_begin(self, type, name):
        N = self.width - len(type) - len(' [    ] ')
        fmt = '%%s %%-%ds ' % N
        return fmt % (type, name)

    def __init__(self, use_color):
        self.cond_var = threading.Condition()
        self.ticker = None
        if use_color:
            self.color_RED = '\033[1;31m'
            self.color_red = '\033[0;31m'
            self.color_reset = '\033[0;37m'
        else:
            self.color_RED = ''
            self.color_red = ''
            self.color_reset = ''

        argv = shlex.split('stty size') # get terminal width
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (stdout, stderr) = proc.communicate()
        if proc.returncode == 0:
            (h, w) = stdout.split()
            self.width = int(w)
        else:
            self.width = 72 # conservative guesstimate

    def begin(self, type, name):
        self.text = self._format_begin(type, name)
        sys.stdout.write(self.text + '[    ]')
        sys.stdout.flush()
        self._start_ticker()

    def end_pass(self, type, name):
        self._stop_ticker()
        sys.stdout.write('\r' + self.text + '[ OK ]\n')
        sys.stdout.flush()

    def end_fail(self, type, name, msg):
        self._stop_ticker()
        sys.stdout.write('\r' + self.color_RED + self.text + '[FAIL]\n')
        sys.stdout.write(self.color_red)
        sys.stdout.write(msg)
        sys.stdout.write(self.color_reset)
        sys.stdout.flush()

class QuietPrinter:
    def begin(self, type, name):
        pass

    def end_pass(self, type, name):
        sys.stdout.write('PASS ' + type + ' ' + name + '\n')
        sys.stdout.flush()

    def end_fail(self, type, name, msg):
        sys.stdout.write('FAIL ' + type + ' ' + name + '\n')
        sys.stdout.flush()

class CompilationTask:
    def __init__(self, makefile):
        self.makefile = makefile

    def get_type(self):
        return TASK_COMPILATION

    def get_name(self):
        return self.makefile

    def execute(self):
        os.putenv('ONE_SHOT_MAKEFILE', os.getcwd() + "/" + self.makefile)
        argv = shlex.split('make -C "../../../../../" files')
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (stdout, stderr) = proc.communicate()
        return proc.returncode, stdout, stderr

class InstrumentationTask:
    def __init__(self, instrumentation_class):
        self.instrumentation_class = instrumentation_class

    def get_type(self):
        return TASK_INSTRUMENTATION

    def get_name(self):
        return self.instrumentation_class

    def execute(self):
        return _adb_shell('am instrument -r -w -e class %s com.android.overlaytest/android.test.InstrumentationTestRunner' % self.instrumentation_class)

class PushTask:
    def __init__(self, src, dest):
        self.src = src
        self.dest = dest

    def get_type(self):
        return TASK_PUSH

    def get_name(self):
        return "%s -> %s" % (self.src, self.dest)

    def execute(self):
        src = os.getenv('OUT') + "/" + self.src
        argv = shlex.split(adb + ' push %s %s' % (src, self.dest))
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (stdout, stderr) = proc.communicate()
        return proc.returncode, stdout, stderr

class MkdirTask:
    def __init__(self, path):
        self.path = path

    def get_type(self):
        return TASK_MKDIR

    def get_name(self):
        return self.path

    def execute(self):
        return _adb_shell('mkdir -p %s' % self.path)

class RmTask:
    def __init__(self, path):
        self.path = path

    def get_type(self):
        return TASK_RM

    def get_name(self):
        return self.path

    def execute(self):
        returncode, stdout, stderr = _adb_shell('ls %s' % self.path)
        if returncode != 0 and stdout.endswith(': No such file or directory\n'):
            return 0, "", ""
        return _adb_shell('rm -r %s' % self.path)

class IdmapPathTask:
    def __init__(self, path_target_apk, path_overlay_apk, path_idmap):
        self.path_target_apk = path_target_apk
        self.path_overlay_apk = path_overlay_apk
        self.path_idmap = path_idmap

    def get_type(self):
        return TASK_IDMAP_PATH

    def get_name(self):
        return self.path_idmap

    def execute(self):
        return _adb_shell('su system idmap --path "%s" "%s" "%s"' % (self.path_target_apk, self.path_overlay_apk, self.path_idmap))

class IdmapScanTask:
    def __init__(self, overlay_dir, target_pkg_name, target_pkg, idmap_dir, symlink_dir):
        self.overlay_dir = overlay_dir
        self.target_pkg_name = target_pkg_name
        self.target_pkg = target_pkg
        self.idmap_dir = idmap_dir
        self.symlink_dir = symlink_dir

    def get_type(self):
        return TASK_IDMAP_SCAN

    def get_name(self):
        return self.target_pkg_name

    def execute(self):
        return _adb_shell('su system idmap --scan "%s" "%s" "%s" "%s"' % (self.overlay_dir, self.target_pkg_name, self.target_pkg, self.idmap_dir))

class FileExistsTest:
    def __init__(self, path):
        self.path = path

    def get_type(self):
        return TASK_FILE_EXISTS_TEST

    def get_name(self):
        return self.path

    def execute(self):
        return _adb_shell('ls %s' % self.path)

class GrepIdmapTest:
    def __init__(self, path_idmap, pattern, expected_n):
        self.path_idmap = path_idmap
        self.pattern = pattern
        self.expected_n = expected_n

    def get_type(self):
        return TASK_GREP_IDMAP_TEST

    def get_name(self):
        return self.pattern

    def execute(self):
        returncode, stdout, stderr = _adb_shell('idmap --inspect %s' % self.path_idmap)
        if returncode != 0:
            return returncode, stdout, stderr
        all_matches = re.findall('\s' + self.pattern + '$', stdout, flags=re.MULTILINE)
        if len(all_matches) != self.expected_n:
            return 1, 'pattern=%s idmap=%s expected=%d found=%d\n' % (self.pattern, self.path_idmap, self.expected_n, len(all_matches)), ''
        return 0, "", ""

class Md5Test:
    def __init__(self, path, expected_content):
        self.path = path
        self.expected_md5 = hashlib.md5(expected_content).hexdigest()

    def get_type(self):
        return TASK_MD5_TEST

    def get_name(self):
        return self.path

    def execute(self):
        returncode, stdout, stderr = _adb_shell('md5 %s' % self.path)
        if returncode != 0:
            return returncode, stdout, stderr
        actual_md5 = stdout.split()[0]
        if actual_md5 != self.expected_md5:
            return 1, 'expected %s, got %s\n' % (self.expected_md5, actual_md5), ''
        return 0, "", ""

class StartTask:
    def get_type(self):
        return TASK_START

    def get_name(self):
        return ""

    def execute(self):
        (returncode, stdout, stderr) = _adb_shell('start')
        if returncode != 0:
            return returncode, stdout, stderr

        while True:
            (returncode, stdout, stderr) = _adb_shell('getprop dev.bootcomplete')
            if returncode != 0:
                return returncode, stdout, stderr
            if stdout.strip() == "1":
                break
            time.sleep(0.5)

        return 0, "", ""

class StopTask:
    def get_type(self):
        return TASK_STOP

    def get_name(self):
        return ""

    def execute(self):
        (returncode, stdout, stderr) = _adb_shell('stop')
        if returncode != 0:
            return returncode, stdout, stderr
        return _adb_shell('setprop dev.bootcomplete 0')

class RootTask:
    def get_type(self):
        return TASK_ROOT

    def get_name(self):
        return ""

    def execute(self):
        (returncode, stdout, stderr) = _adb_shell('getprop service.adb.root 0')
        if returncode != 0:
            return returncode, stdout, stderr
        if stdout.strip() == '1': # already root
            return 0, "", ""

        argv = shlex.split(adb + ' root')
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (stdout, stderr) = proc.communicate()
        if proc.returncode != 0:
            return proc.returncode, stdout, stderr

        argv = shlex.split(adb + ' wait-for-device')
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (stdout, stderr) = proc.communicate()
        return proc.returncode, stdout, stderr

class RemountTask:
    def get_type(self):
        return TASK_REMOUNT

    def get_name(self):
        return ""

    def execute(self):
        argv = shlex.split(adb + ' remount')
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (stdout, stderr) = proc.communicate()
        # adb remount returns 0 even if the operation failed, so check stdout
        if stdout.startswith('remount failed:'):
            return 1, stdout, stderr
        return proc.returncode, stdout, stderr

class CompoundTask:
    def __init__(self, type, tasks):
        self.type = type
        self.tasks = tasks

    def get_type(self):
        return self.type

    def get_name(self):
        return ""

    def execute(self):
        for t in self.tasks:
            (returncode, stdout, stderr) = t.execute()
            if returncode != 0:
                return returncode, stdout, stderr
        return 0, "", ""

def _create_disable_overlays_task():
    tasks = [
        RmTask("/vendor/overlay/framework_a.apk"),
        RmTask("/vendor/overlay/framework_b.apk"),
        RmTask("/data/resource-cache/vendor@overlay@framework_a.apk@idmap"),
        RmTask("/data/resource-cache/vendor@overlay@framework_b.apk@idmap"),
        RmTask("/vendor/overlay/app_a.apk"),
        RmTask("/vendor/overlay/app_b.apk"),
        RmTask("/data/resource-cache/vendor@overlay@app_a.apk@idmap"),
        RmTask("/data/resource-cache/vendor@overlay@app_b.apk@idmap"),
    ]
    return CompoundTask(TASK_DISABLE_OVERLAYS, tasks)

def _create_enable_single_overlay_task():
    tasks = [
        _create_disable_overlays_task(),
        MkdirTask('/system/vendor'),
        MkdirTask('/vendor/overlay'),
        PushTask('/data/app/com.android.overlaytest.overlay/com.android.overlaytest.overlay.apk', '/vendor/overlay/framework_a.apk'),
        PushTask('/data/app/com.android.overlaytest.first_app_overlay/com.android.overlaytest.first_app_overlay.apk', '/vendor/overlay/app_a.apk'),
    ]
    return CompoundTask(TASK_ENABLE_SINGLE_OVERLAY, tasks)

def _create_enable_multiple_overlays_task():
    tasks = [
        _create_disable_overlays_task(),
        MkdirTask('/system/vendor'),
        MkdirTask('/vendor/overlay'),

        PushTask('/data/app/com.android.overlaytest.overlay/com.android.overlaytest.overlay.apk', '/vendor/overlay/framework_b.apk'),
        PushTask('/data/app/com.android.overlaytest.first_app_overlay/com.android.overlaytest.first_app_overlay.apk', '/vendor/overlay/app_a.apk'),
        PushTask('/data/app/com.android.overlaytest.second_app_overlay/com.android.overlaytest.second_app_overlay.apk', '/vendor/overlay/app_b.apk'),
    ]
    return CompoundTask(TASK_ENABLE_MULTIPLE_OVERLAYS, tasks)

def _create_setup_idmap_path_task(idmaps, symlinks):
    tasks = [
        _create_enable_single_overlay_task(),
        RmTask(symlinks),
        RmTask(idmaps),
        MkdirTask(idmaps),
        MkdirTask(symlinks),
    ]
    return CompoundTask(TASK_SETUP_IDMAP_PATH, tasks)

def _create_setup_idmap_scan_task(idmaps, symlinks):
    tasks = [
        _create_enable_single_overlay_task(),
        RmTask(symlinks),
        RmTask(idmaps),
        MkdirTask(idmaps),
        MkdirTask(symlinks),
        _create_enable_multiple_overlays_task(),
    ]
    return CompoundTask(TASK_SETUP_IDMAP_SCAN, tasks)

def _handle_instrumentation_task_output(stdout, printer):
    regex_status_code = re.compile(r'^INSTRUMENTATION_STATUS_CODE: -?(\d+)')
    regex_name = re.compile(r'^INSTRUMENTATION_STATUS: test=(.*)')
    regex_begin_stack = re.compile(r'^INSTRUMENTATION_STATUS: stack=(.*)')
    regex_end_stack = re.compile(r'^$')

    failed_tests = 0
    current_test = None
    current_stack = []
    mode_stack = False
    for line in stdout.split("\n"):
        line = line.rstrip() # strip \r from adb output
        m = regex_status_code.match(line)
        if m:
            c = int(m.group(1))
            if c == 1:
                printer.begin(TASK_INSTRUMENTATION_TEST, current_test)
            elif c == 0:
                printer.end_pass(TASK_INSTRUMENTATION_TEST, current_test)
            else:
                failed_tests += 1
                current_stack.append("\n")
                msg = "\n".join(current_stack)
                printer.end_fail(TASK_INSTRUMENTATION_TEST, current_test, msg.rstrip() + '\n')
            continue

        m = regex_name.match(line)
        if m:
            current_test = m.group(1)
            continue

        m = regex_begin_stack.match(line)
        if m:
            mode_stack = True
            current_stack = []
            current_stack.append("  " + m.group(1))
            continue

        m = regex_end_stack.match(line)
        if m:
            mode_stack = False
            continue

        if mode_stack:
            current_stack.append("    " + line.strip())

    return failed_tests

def _set_adb_device(option, opt, value, parser):
    global adb
    if opt == '-d' or opt == '--device':
        adb = 'adb -d'
    if opt == '-e' or opt == '--emulator':
        adb = 'adb -e'
    if opt == '-s' or opt == '--serial':
        adb = 'adb -s ' + value

def _create_opt_parser():
    parser = optparse.OptionParser()
    parser.add_option('-d', '--device', action='callback', callback=_set_adb_device,
            help='pass -d to adb')
    parser.add_option('-e', '--emulator', action='callback', callback=_set_adb_device,
            help='pass -e to adb')
    parser.add_option('-s', '--serial', type="str", action='callback', callback=_set_adb_device,
            help='pass -s <serical> to adb')
    parser.add_option('-C', '--no-color', action='store_false',
            dest='use_color', default=True,
            help='disable color escape sequences in output')
    parser.add_option('-q', '--quiet', action='store_true',
            dest='quiet_mode', default=False,
            help='quiet mode, output only results')
    parser.add_option('-b', '--no-build', action='store_false',
            dest='do_build', default=True,
            help='do not rebuild test projects')
    parser.add_option('-k', '--continue', action='store_true',
            dest='do_continue', default=False,
            help='do not rebuild test projects')
    parser.add_option('-i', '--test-idmap', action='store_true',
            dest='test_idmap', default=False,
            help='run tests for single overlay')
    parser.add_option('-0', '--test-no-overlay', action='store_true',
            dest='test_no_overlay', default=False,
            help='run tests without any overlay')
    parser.add_option('-1', '--test-single-overlay', action='store_true',
            dest='test_single_overlay', default=False,
            help='run tests for single overlay')
    parser.add_option('-2', '--test-multiple-overlays', action='store_true',
            dest='test_multiple_overlays', default=False,
            help='run tests for multiple overlays')
    return parser

if __name__ == '__main__':
    opt_parser = _create_opt_parser()
    opts, args = opt_parser.parse_args(sys.argv[1:])
    if not opts.test_idmap and not opts.test_no_overlay and not opts.test_single_overlay and not opts.test_multiple_overlays:
        opts.test_idmap = True
        opts.test_no_overlay = True
        opts.test_single_overlay = True
        opts.test_multiple_overlays = True
    if len(args) > 0:
        opt_parser.error("unexpected arguments: %s" % " ".join(args))
        # will never reach this: opt_parser.error will call sys.exit

    if opts.quiet_mode:
        printer = QuietPrinter()
    else:
        printer = VerbosePrinter(opts.use_color)
    tasks = []

    # must be in the same directory as this script for compilation tasks to work
    script = sys.argv[0]
    dirname = os.path.dirname(script)
    wd = os.path.realpath(dirname)
    os.chdir(wd)

    # build test cases
    if opts.do_build:
        tasks.append(CompilationTask('OverlayTest/Android.mk'))
        tasks.append(CompilationTask('OverlayTestOverlay/Android.mk'))
        tasks.append(CompilationTask('OverlayAppFirst/Android.mk'))
        tasks.append(CompilationTask('OverlayAppSecond/Android.mk'))

    # remount filesystem, install test project
    tasks.append(RootTask())
    tasks.append(RemountTask())
    tasks.append(PushTask('/system/app/OverlayTest/OverlayTest.apk', '/system/app/OverlayTest.apk'))

    # test idmap
    if opts.test_idmap:
        idmaps='/data/local/tmp/idmaps'
        symlinks='/data/local/tmp/symlinks'

        # idmap --path
        tasks.append(StopTask())
        tasks.append(_create_setup_idmap_path_task(idmaps, symlinks))
        tasks.append(StartTask())
        tasks.append(IdmapPathTask('/vendor/overlay/framework_a.apk', '/system/framework/framework-res.apk', idmaps + '/a.idmap'))
        tasks.append(FileExistsTest(idmaps + '/a.idmap'))
        tasks.append(GrepIdmapTest(idmaps + '/a.idmap', 'bool/config_annoy_dianne', 1))

        # idmap --scan
        idmap = idmaps + '/vendor@overlay@framework_b.apk@idmap'
        tasks.append(StopTask())
        tasks.append(_create_setup_idmap_scan_task(idmaps, symlinks))
        tasks.append(StartTask())
        tasks.append(IdmapScanTask('/vendor/overlay', 'android', '/system/framework/framework-res.apk', idmaps, symlinks))
        tasks.append(FileExistsTest(idmap))
        tasks.append(GrepIdmapTest(idmap, 'bool/config_annoy_dianne', 1))

        # overlays.list
        overlays_list_path = idmaps + '/overlays.list'
        expected_content = '''\
/vendor/overlay/framework_b.apk /data/local/tmp/idmaps/vendor@overlay@framework_b.apk@idmap
'''
        tasks.append(FileExistsTest(overlays_list_path))
        tasks.append(Md5Test(overlays_list_path, expected_content))

        # idmap cleanup
        tasks.append(RmTask(symlinks))
        tasks.append(RmTask(idmaps))

    # test no overlay
    if opts.test_no_overlay:
        tasks.append(StopTask())
        tasks.append(_create_disable_overlays_task())
        tasks.append(StartTask())
        tasks.append(InstrumentationTask('com.android.overlaytest.WithoutOverlayTest'))

    # test single overlay
    if opts.test_single_overlay:
        tasks.append(StopTask())
        tasks.append(_create_enable_single_overlay_task())
        tasks.append(StartTask())
        tasks.append(InstrumentationTask('com.android.overlaytest.WithOverlayTest'))

    # test multiple overlays
    if opts.test_multiple_overlays:
        tasks.append(StopTask())
        tasks.append(_create_enable_multiple_overlays_task())
        tasks.append(StartTask())
        tasks.append(InstrumentationTask('com.android.overlaytest.WithMultipleOverlaysTest'))

    ignored_errors = 0
    for t in tasks:
        type = t.get_type()
        name = t.get_name()
        if type == TASK_INSTRUMENTATION:
            # InstrumentationTask will run several tests, but we want it
            # to appear as if each test was run individually. Calling
            # "am instrument" with a single test method is prohibitively
            # expensive, so let's instead post-process the output to
            # emulate individual calls.
            retcode, stdout, stderr = t.execute()
            if retcode != 0:
                printer.begin(TASK_INSTRUMENTATION, name)
                printer.end_fail(TASK_INSTRUMENTATION, name, stderr)
                sys.exit(retcode)
            retcode = _handle_instrumentation_task_output(stdout, printer)
            if retcode != 0:
                if not opts.do_continue:
                    sys.exit(retcode)
                else:
                    ignored_errors += retcode
        else:
            printer.begin(type, name)
            retcode, stdout, stderr = t.execute()
            if retcode == 0:
                printer.end_pass(type, name)
            if retcode != 0:
                if len(stderr) == 0:
                    # hope for output from stdout instead (true for eg adb shell rm)
                    stderr = stdout
                printer.end_fail(type, name, stderr)
                if not opts.do_continue:
                    sys.exit(retcode)
                else:
                    ignored_errors += retcode
    sys.exit(ignored_errors)
