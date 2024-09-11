# Utility Android Lint Checks for AOSP

This directory contains scripts that execute utility Android Lint Checks for AOSP, specifically:
* `enforce_permission_counter.py`: Provides statistics regarding the percentage of annotated/not
  annotated `AIDL` methods with `@EnforcePermission` annotations.
* `generate-exempt-aidl-interfaces.sh`: Provides a list of all `AIDL` interfaces in the entire
  source tree.

When adding a new utility Android Lint check to this directory, consider adding any utility or
data processing tool you might require. Make sure that your contribution is documented in this
README file.
