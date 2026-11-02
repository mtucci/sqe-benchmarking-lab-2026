#!/usr/bin/env bash
set -euo pipefail
mkdir -p /lab/data/results /lab/data/analysis /var/log/lab
exec /usr/bin/supervisord -n -c /opt/lab/supervisord.conf
