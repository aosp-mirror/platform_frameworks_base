#!/usr/bin/env python3
#
# Copyright 2019, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Helper util libraries for command line operations."""

import asyncio
import sys
import time
from typing import Tuple, Optional, List

import lib.print_utils as print_utils

TIMEOUT = 50
SIMULATE = False

def run_command_nofail(cmd: List[str], **kwargs) -> None:
  """Runs cmd list with default timeout.

     Throws exception if the execution fails.
  """
  my_kwargs = {"timeout": TIMEOUT, "shell": False, "simulate": False}
  my_kwargs.update(kwargs)
  passed, out = execute_arbitrary_command(cmd, **my_kwargs)
  if not passed:
    raise RuntimeError(
      "Failed to execute %s (kwargs=%s), output=%s" % (cmd, kwargs, out))

def run_adb_shell_command(cmd: str) -> Tuple[bool, str]:
  """Runs command using adb shell.

  Returns:
    A tuple of running status (True=succeeded, False=failed or timed out) and
    std output (string contents of stdout with trailing whitespace removed).
  """
  return run_shell_command('adb shell "{}"'.format(cmd))

def run_shell_func(script_path: str,
                   func: str,
                   args: List[str]) -> Tuple[bool, str]:
  """Runs shell function with default timeout.

  Returns:
    A tuple of running status (True=succeeded, False=failed or timed out) and
    std output (string contents of stdout with trailing whitespace removed) .
  """
  if args:
    cmd = 'bash -c "source {script_path}; {func} {args}"'.format(
      script_path=script_path,
      func=func,
      args=' '.join("'{}'".format(arg) for arg in args))
  else:
    cmd = 'bash -c "source {script_path}; {func}"'.format(
      script_path=script_path,
      func=func)

  print_utils.debug_print(cmd)
  return run_shell_command(cmd)

def run_shell_command(cmd: str) -> Tuple[bool, str]:
  """Runs shell command with default timeout.

  Returns:
    A tuple of running status (True=succeeded, False=failed or timed out) and
    std output (string contents of stdout with trailing whitespace removed) .
  """
  return execute_arbitrary_command([cmd],
                                   TIMEOUT,
                                   shell=True,
                                   simulate=SIMULATE)

def execute_arbitrary_command(cmd: List[str],
                              timeout: int,
                              shell: bool,
                              simulate: bool) -> Tuple[bool, str]:
  """Run arbitrary shell command with default timeout.

    Mostly copy from
    frameworks/base/startop/scripts/app_startup/app_startup_runner.py.

  Args:
    cmd: list of cmd strings.
    timeout: the time limit of running cmd.
    shell: indicate if the cmd is a shell command.
    simulate: if it's true, do not run the command and assume the running is
        successful.

  Returns:
    A tuple of running status (True=succeeded, False=failed or timed out) and
    std output (string contents of stdout with trailing whitespace removed) .
  """
  if simulate:
    print(cmd)
    return True, ''

  print_utils.debug_print('[EXECUTE]', cmd)
  # block until either command finishes or the timeout occurs.
  loop = asyncio.get_event_loop()

  (return_code, script_output) = loop.run_until_complete(
    _run_command(*cmd, shell=shell, timeout=timeout))

  script_output = script_output.decode()  # convert bytes to str

  passed = (return_code == 0)
  print_utils.debug_print('[$?]', return_code)
  if not passed:
    print('[FAILED, code:%s]' % (return_code), script_output, file=sys.stderr)

  return passed, script_output.rstrip()

async def _run_command(*args: List[str],
                       shell: bool = False,
                       timeout: Optional[int] = None) -> Tuple[int, bytes]:
  if shell:
    process = await asyncio.create_subprocess_shell(
      *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
  else:
    process = await asyncio.create_subprocess_exec(
      *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)

  script_output = b''

  print_utils.debug_print('[PID]', process.pid)

  timeout_remaining = timeout
  time_started = time.time()

  # read line (sequence of bytes ending with b'\n') asynchronously
  while True:
    try:
      line = await asyncio.wait_for(process.stdout.readline(),
                                    timeout_remaining)
      print_utils.debug_print('[STDOUT]', line)
      script_output += line

      if timeout_remaining:
        time_elapsed = time.time() - time_started
        timeout_remaining = timeout - time_elapsed
    except asyncio.TimeoutError:
      print_utils.debug_print('[TIMEDOUT] Process ', process.pid)

      print_utils.debug_print('[TIMEDOUT] Sending SIGTERM.')
      process.terminate()

      # 5 second timeout for process to handle SIGTERM nicely.
      try:
        (remaining_stdout,
         remaining_stderr) = await asyncio.wait_for(process.communicate(), 5)
        script_output += remaining_stdout
      except asyncio.TimeoutError:
        print_utils.debug_print('[TIMEDOUT] Sending SIGKILL.')
        process.kill()

      # 5 second timeout to finish with SIGKILL.
      try:
        (remaining_stdout,
         remaining_stderr) = await asyncio.wait_for(process.communicate(), 5)
        script_output += remaining_stdout
      except asyncio.TimeoutError:
        # give up, this will leave a zombie process.
        print_utils.debug_print('[TIMEDOUT] SIGKILL failed for process ',
                                process.pid)
        time.sleep(100)

      return -1, script_output
    else:
      if not line:  # EOF
        break

  code = await process.wait()  # wait for child process to exit
  return code, script_output
