#!/bin/bash

# Script to verify signatures, with both signature & data given in b64
# Args:
# 1. data (base64 encoded)
# 2. signature (base64 encoded)
# The arg values can be taken from the debug log for SignedConfigService when verbose logging is
# enabled.

openssl dgst -sha256 -verify $(dirname $0)/debug_public.pem -signature <(echo $2 | base64 -d) <(echo $1 | base64 -d)
