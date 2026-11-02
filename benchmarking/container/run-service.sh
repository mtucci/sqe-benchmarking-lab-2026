#!/usr/bin/env bash
set -euo pipefail
export PRICING_THREAD_POOL_SIZE="${PRICING_WORKERS:-2}"
export PRICING_PROCESSING_DELAY_MS="${PRICING_DELAY_MS:-20}"
export PRICING_QUEUE_CAPACITY="${PRICING_QUEUE:-50}"
exec java -XX:ActiveProcessorCount=2 -Xms32m -Xmx192m -XX:+UseSerialGC -jar "/opt/sqe/$1.jar"
