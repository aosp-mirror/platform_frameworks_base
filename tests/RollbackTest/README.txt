This directory contains a test for the rollback manager service.

Directory structure
===================
RollbackTest
  - device driven test for rollbacks not involving staged rollbacks.

StagedRollbackTest
  - device driven test for staged rollbacks.

TestApp
  - source for dummy apks used in testing.

TestApex
  - source for dummy apex modules used in testing.

Running the tests
=================

You can manually run the tests as follows:

  atest RollbackTest
  atest StagedRollbackTest
