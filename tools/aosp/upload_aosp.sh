#!/bin/bash

set -eu

DRYRUN=false
VERBOSE=false
DEST_BRANCH_NAME="main"
AOSP_URL=""

function log_info() {
  echo -e "\033[32m$1\033[m"
}

function log_warn() {
  echo -e "\033[33m$1\033[m"
}

function log_fatal() {
  echo -e "\033[31mERROR: $1\033[m" > /dev/stderr
  exit 1
}

while [[ $# -gt 0 ]]; do
  case $1 in
    -b|--branch)
      DEST_BRANCH_NAME=$2
      shift
      shift
      ;;
    -v|--verbose)
      set -x
      VERBOSE=true
      shift
      ;;
    -n|--dryrun)
      DRYRUN=true
      shift
      ;;
    -u|--url)
      AOSP_URL=$2
      shift
      shift
      ;;
    --help)
      echo "$0 <options>"
      echo
      echo "Options:"
      echo "    -b, --branch <branch> : destination AOSP branch, default is $DEST_BRANCH_NAME"
      echo "    -n, --dryrun          : do not upload CL"
      echo "    -u, --url             : AOSP repo URL. Default is to use existing 'aosp' remote or guess the URL."
      echo "    -v, --verbose         : show verbose output"
      echo
      exit 0
      ;;
    -*|--*)
      echo "Unknown option $i"
      exit 1
      ;;
    *)
      ;;
  esac
done

if $VERBOSE; then
  log_info "DRYRUN=$DRYRUN"
  log_info "DEST_BRANCH_NAME=$DEST_BRANCH_NAME"
fi

current_branch=$(git branch --no-color --show-current)
if [ -z "$current_branch" ]; then
  log_fatal "use 'repo start' first"
fi

tmp_branch="aosp_$current_branch"

if [ -z "$AOSP_URL" ]; then
  AOSP_URL=$(git config --get remote.goog.url | sed 's/googleplex-//')
fi

if $VERBOSE; then
  log_info "AOSP_URL=$AOSP_URL"
  log_info "current_branch=$current_branch"
  log_info "tmp_branch=$tmp_branch"
fi

log_info "Running repo hooks..."
repo upload -c . -n -y

log_info "Setting up AOSP repo..."
existing_aosp_url=$(git config --get remote.aosp.url 2>/dev/null || true)
if [ -z "$existing_aosp_url" ]; then
  git remote add aosp $AOSP_URL
elif [ "$existing_aosp_url" != "$AOSP_URL"]; then
  log_warn "Remote 'aosp' uses $existing_aosp_url. Expected $AOSP_URL"
fi

log_info "Fetching '$DEST_BRANCH_NAME'"
git fetch aosp $DEST_BRANCH_NAME

log_info "Creating $tmp_branch and cherry-picking..."
git branch -D $tmp_branch 2>/dev/null || true
git checkout -b $tmp_branch
git branch --set-upstream-to aosp/$DEST_BRANCH_NAME
git reset --hard aosp/$DEST_BRANCH_NAME
git cherry-pick goog/$DEST_BRANCH_NAME..$current_branch

if $DRYRUN; then
  log_info "Dryrun specified, skipping CL upload"
else
  log_info "Pushing to AOSP..."
  git push aosp HEAD:refs/for/$DEST_BRANCH_NAME
fi

log_info "Cleaning up..."
git checkout $current_branch
git branch -D $tmp_branch