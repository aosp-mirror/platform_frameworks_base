LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

# Target for running host unit tests on post/pre-submit.
.PHONY: aapt2_run_host_unit_tests
aapt2_run_host_unit_tests: PRIVATE_GTEST_OPTIONS := --gtest_output=xml:$(DIST_DIR)/gtest/aapt2_host_unit_tests_result.xml
aapt2_run_host_unit_tests: $(HOST_OUT_NATIVE_TESTS)/aapt2_tests/aapt2_tests
	-$(HOST_OUT_NATIVE_TESTS)/aapt2_tests/aapt2_tests $(PRIVATE_GTEST_OPTIONS) > /dev/null 2>&1

include $(call all-makefiles-under,$(LOCAL_PATH))
