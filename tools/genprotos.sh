#!/bin/bash

# TODO This should not be needed. If you set a custom OUT_DIR or OUT_DIR_COMMON_BASE you can
# end up with a command that is extremely long, potentially going passed MAX_ARG_STRLEN due to
# the way sbox rewrites the command. See b/70221552.

set -e

location_aprotoc=$1
location_protoc=$2
location_soong_zip=$3
genDir=$4
depfile=$5
in=$6
out=$7

mkdir -p ${genDir}/${in} && \
  ${location_aprotoc} --plugin=${location_protoc} \
                      --dependency_out=${depfile} \
                      --javastream_out=${genDir}/${in} \
                      -Iexternal/protobuf/src \
                      -I . \
                      ${in} && \
  ${location_soong_zip} -jar -o ${out} -C ${genDir}/${in} -D ${genDir}/${in}
