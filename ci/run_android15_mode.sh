#!/usr/bin/env bash
set -euo pipefail
case "${OBSERVATION_MODE:-regression}" in
  regression) bash ci/run_android15.sh ;;
  real_prospective) bash ci/run_real_prospective.sh ;;
  *) echo 'UNKNOWN_OBSERVATION_MODE'; exit 1 ;;
esac
