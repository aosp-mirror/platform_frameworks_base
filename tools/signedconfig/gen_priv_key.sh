#!/bin/bash

# This script acts as a record of how the debug key was generated. There should
# be no need to run it again.

openssl ecparam -name prime256v1 -genkey -noout -out debug_key.pem
openssl ec -in debug_key.pem -pubout -out debug_public.pem
