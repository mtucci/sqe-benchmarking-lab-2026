#!/usr/bin/env bash
# Prepare fresh service JVMs and an empty in-memory order database for one run.
set -euo pipefail
supervisorctl -c /opt/lab/supervisord.conf restart pricing order risk workflow gateway
lab-ready
