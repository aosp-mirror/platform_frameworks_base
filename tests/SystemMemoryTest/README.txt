This directory contains a test for system server memory use.

Directory structure
===================
device
  - those parts of the test that run on device.

host
  - those parts of the test that run on host.

Running the test
================

You can manually run the test as follows:

  atest -v system-memory-test

This installs and runs the test on device. You can see the metrics in the
tradefed output.

