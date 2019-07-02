#! /bin/bash

if [[ $PWD != *"/frameworks/base/packages/SystemUI" ]]; then
    echo "Please run this in frameworks/base/packages/SystemUI" >&2
    exit 1
fi

# Empty the baseline file so the baseline-generation run below do not ignore any existing errors.
echo '' > tools/lint/baseline.xml

lint . -Werror --exitcode --config tools/lint/lint.xml --html /tmp/lint_output.html \
  --baseline tools/lint/baseline.xml --remove-fixed &

# b/37579990 - The file needs to be removed *while* lint is running
sleep 0.5
rm tools/lint/baseline.xml

wait
