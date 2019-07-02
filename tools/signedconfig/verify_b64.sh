#!/bin/bash

# Script to verify signatures, with both signature & data given in b64
# Args:
# 1. data (base64 encoded)
# 2. signature (base64 encoded)
# The arg values can be taken from the debug log for SignedConfigService when verbose logging is
# enabled.

function verify() {
  D=${1}
  S=${2}
  K=${3}
  echo Trying ${K}
  openssl dgst -sha256 -verify $(dirname $0)/${K} -signature <(echo ${S} | base64 -d) <(echo ${D} | base64 -d)
}


PROD_KEY_NAME=prod_public.pem
DEBUG_KEY_NAME=debug_public.pem
SIGNATURE="$2"
DATA="$1"

echo DATA: ${DATA}
echo SIGNATURE: ${SIGNATURE}

if verify "${DATA}" "${SIGNATURE}" "${PROD_KEY_NAME}"; then
  echo Verified with ${PROD_KEY_NAME}
  exit 0
fi

if verify "${DATA}" "${SIGNATURE}" "${DEBUG_KEY_NAME}"; then
  echo Verified with ${DEBUG_KEY_NAME}
  exit 0
fi
exit 1
