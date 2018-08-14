#!/bin/bash
if git show --name-only --pretty=format: $1 | grep api/ > /dev/null; then
    python tools/apilint/apilint.py <(git show $1:api/current.txt) <(git show $1^:api/current.txt)
fi
