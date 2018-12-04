#!/bin/bash
# Script to sign data with the debug keys. Outputs base64 for embedding into
# APK metadata.

openssl dgst -sha256 -sign $(dirname $0)/debug_key.pem  $1 | base64 -w 0
echo
