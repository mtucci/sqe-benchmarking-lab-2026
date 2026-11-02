#!/usr/bin/env python3
"""Readiness only: never starts services or benchmarks."""
import argparse
import json
import sys
import time
from urllib.request import Request, urlopen


def check(business=True):
    for port in (8080, 8081, 8082, 8083, 8084):
        with urlopen(f'http://127.0.0.1:{port}/health', timeout=1) as response:
            if json.load(response).get('status') != 'UP':
                raise ValueError(f'Service on {port} is not ready')
    if business:
        reference = None
        for implementation in ('baseline', 'optimized'):
            payload = json.dumps({'customerId': 'C-2026', 'shippingAddress': 'Main Street 1, Berlin', 'shippingCountry': 'DE',
                                  'ruleCount': 100000, 'implementation': implementation, 'items': [
                {'sku': 'SKU-1001', 'quantity': 2}, {'sku': 'SKU-4002', 'quantity': 1}]}).encode()
            request = Request('http://127.0.0.1:8080/api/risk-previews', data=payload, headers={'Content-Type': 'application/json'})
            with urlopen(request, timeout=2) as response:
                preview = json.load(response)
            if float(preview['quote']['total']) != 724.8 or preview['quote']['customerHistory']['orderCount'] != 0:
                raise ValueError('Preview data is not in the expected empty-history state')
            if reference is not None and preview['evaluation']['risk'] != reference:
                raise ValueError('Risk implementations disagree')
            reference = preview['evaluation']['risk']
    with urlopen('http://127.0.0.1:8090/health', timeout=1) as response:
        if response.status != 200:
            raise ValueError('Results page is not ready')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Wait for the lab services to be ready.')
    parser.add_argument('--services-only', action='store_true', help='Check health endpoints without running risk computations.')
    parser.add_argument('--timeout', type=float, default=120)
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    while True:
        try:
            check(business=not args.services_only)
            print('Ready. Run benchmarks in /lab; view analyses at http://localhost:8080')
            break
        except (OSError, ValueError, KeyError) as error:
            if time.monotonic() >= deadline:
                print(f'Not ready: {error}. Logs: /var/log/lab/', file=sys.stderr)
                sys.exit(1)
            time.sleep(.5)
