LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

aapt2_results := $(call intermediates-dir-for,PACKAGING,aapt2_run_host_unit_tests)/result.xml

# Target for running host unit tests on post/pre-submit.
.PHONY: aapt2_run_host_unit_tests
aapt2_run_host_unit_tests: $(aapt2_results)

$(call dist-for-goals,aapt2_run_host_unit_tests,$(aapt2_results):gtest/aapt2_host_unit_tests_result.xml)

# Always run the tests again, even if they haven't changed
$(aapt2_results): .KATI_IMPLICIT_OUTPUTS := $(aapt2_results)-nocache
$(aapt2_results): $(HOST_OUT_NATIVE_TESTS)/aapt2_tests/aapt2_tests
	-$(HOST_OUT_NATIVE_TESTS)/aapt2_tests/aapt2_tests --gtest_output=xml:$@ > /dev/null 2>&1

aapt2_results :=

include $(call all-makefiles-under,$(LOCAL_PATH))
