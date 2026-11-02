# Benchmarking lab — Docker only

Students need **Docker and a browser**. Java, JMH, k6, Python, the toy services and their broker are already in one image. Run benchmarks in its Linux shell on Windows, Linux or macOS; no Compose, source checkout or host programming tools are required.

## 1. Pull, start and enter

Use the public image `mtucci/sqe-benchmarking-lab:2026`. Use Docker Desktop with Linux containers on Windows/macOS. The `2026` tag identifies this lab; `latest` is also published.

In your **host terminal** (PowerShell works too):

```sh
docker pull mtucci/sqe-benchmarking-lab:2026
docker run -d --name sqe-lab --cpus=2 --memory=3g -p 127.0.0.1:8080:8090 -v sqe-lab-data:/lab/data mtucci/sqe-benchmarking-lab:2026
docker exec -it sqe-lab bash
```

All experiment and analysis commands below run **inside that shell**, in `/lab`:

```sh
lab-ready
RUN_ID="$(date -u +%Y%m%dT%H%M%S)-${RANDOM}"
```

This waits for startup. Open **http://localhost:8080** in your host browser. It lists saved experiments and analyses with UTC dates and times. Refresh after runs finish. Click **Analyze** for one experiment, or check compatible experiments and click **Compare selected**. Use **Select all experiments** or **Select all analyses** to select a whole list; click the same control again to clear it. Use the remove buttons to delete the selected files or reports. Removing an experiment keeps its existing analyses viewable, but their warm-up controls need the raw file. Removing an analysis keeps its experiments. Raw results and analyses persist in the named Docker volume. No package installation or internet access is needed inside the running image.

The same `RiskScorer.evaluate` function runs at every level:

| Level | Measured path |
| --- | --- |
| JMH | `RiskScorerBenchmark.evaluate` → `RiskScorer.evaluate` |
| Component | HTTP `/risk-evaluations` on the risk service → `RiskScorer.evaluate` |
| End-to-end | Gateway `/api/risk-previews` → workflow → pricing and order-history reads → risk service → `RiskScorer.evaluate` |

See the [PlantUML component diagram](benchmark-architecture.puml) for the architecture and benchmark entry points, and the [sequence diagram](benchmark-scopes.puml) for the three paths and their measurement boundaries.

Inspect the supplied function with `cat source/RiskScorer.java` and its JMH wrapper with `cat source/RiskScorerBenchmark.java`. Each rule names a shipping country, a minimum order amount, an account-age limit and risk points. For example: “DE, amount ≥ 50, account younger than 30 days → add 1 point.” The baseline scans all rules; the optimized implementation looks up the country first and checks only that country’s rules. At `ruleCount=100000`, 20 countries have 5,000 rules each. Both implementations return the same score (earned points as a percentage of possible points for that country), category and order amount. The rule catalog and country index are prepared outside the measured function and reused; order results are computed anew. Preparing the index costs time and memory; this lab measures requests after that preparation. `ruleCount` changes the rule collection, so changing it may change the score. The versioned `risk-rules-v1` workload uses the same order, customer and rules at all three levels. Compare results from the same workload version.

## 2. Microbenchmark: JMH single-shot mode

```sh
java -jar benchmarks.jar 'RiskScorerBenchmark.evaluate$' -p ruleCount=100000 -p implementation=baseline -t 1 -f 5 -wi 0 -i 1000 -bs 1 -bm ss -tu ms -rf json -rff results/${RUN_ID}-jmh-baseline.json
java -jar benchmarks.jar 'RiskScorerBenchmark.evaluate$' -p ruleCount=100000 -p implementation=optimized -t 1 -f 5 -wi 0 -i 1000 -bs 1 -bm ss -tu ms -rf json -rff results/${RUN_ID}-jmh-optimized.json
python analyze.py results/${RUN_ID}-jmh-baseline.json -o analysis/${RUN_ID}-jmh-single
python analyze.py results/${RUN_ID}-jmh-baseline.json results/${RUN_ID}-jmh-optimized.json -o analysis/${RUN_ID}-jmh-comparison
```

Open both analyses. Each file contains five fresh JVM forks with 1,000 single shots each, with batch size one, one thread and zero built-in warm-up. Every point times one invocation. Forks appear consecutively in alternating shades. Statistics use all retained shots; confidence intervals and tests assume independent samples for illustration, while within-fork dependence can overstate precision.

Compare the baseline and optimized implementation at the same rule count. Try other `ruleCount`, shots per fork (`-i`) or forks (`-f`) settings, saving new filenames. Use the warm-up cutoff to examine the early samples. The [JMH single-shot example](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_02_BenchmarkModes.java) explains this measurement mode.

## 3. Component benchmark: risk service directly

```sh
k6 run -e TARGET=component -e IMPL=baseline -e RULE_COUNT=100000 -e VUS=1 --out json=results/${RUN_ID}-component-baseline.json http.js
k6 run -e TARGET=component -e IMPL=optimized -e RULE_COUNT=100000 -e VUS=1 --out json=results/${RUN_ID}-component-optimized.json http.js
python analyze.py results/${RUN_ID}-component-baseline.json -o analysis/${RUN_ID}-component-single
python analyze.py results/${RUN_ID}-component-baseline.json results/${RUN_ID}-component-optimized.json -o analysis/${RUN_ID}-component-comparison
```

The component request includes the same priced items, shipping details and customer profile as JMH. Each run records thirty seconds with zero warm-up. Before recording, k6 fetches a baseline reference; every measured response must match its score, category and amount and report the requested implementation. HTTP latency uses the fractional-millisecond [k6 request duration](https://grafana.com/docs/k6/latest/javascript-api/k6-http/response/): sending + waiting + receiving, excluding connection setup and client-side result validation. Inspect it alongside failures and throughput.

Try `-e VUS=8`, `-e SECONDS=60` or `-e WARMUP=5`. Keep the client count and rule count equal across implementation comparisons. Save new filenames for each experiment.

## 4. End-to-end benchmark: risk preview

```sh
k6 run -e TARGET=end-to-end -e IMPL=baseline -e RULE_COUNT=100000 -e VUS=1 --out json=results/${RUN_ID}-e2e-baseline.json http.js
k6 run -e TARGET=end-to-end -e IMPL=optimized -e RULE_COUNT=100000 -e VUS=1 --out json=results/${RUN_ID}-e2e-optimized.json http.js
python analyze.py results/${RUN_ID}-e2e-baseline.json -o analysis/${RUN_ID}-e2e-single
python analyze.py results/${RUN_ID}-e2e-baseline.json results/${RUN_ID}-e2e-optimized.json -o analysis/${RUN_ID}-e2e-comparison
```

The gateway request supplies SKUs, quantities and shipping details. The workflow reads prices, the customer profile and order history, then waits for the risk service to execute the same scorer. The preview creates no orders, keeping the workload repeatable. Tracing is disabled.

Compare the baseline/optimized time ratio within each level. Does the function’s improvement produce the same improvement at component and end-to-end levels? HTTP serialization, pricing delays and orchestration contribute time outside the optimized function. Raise `VUS` equally in both implementations to explore bottlenecks. Analyze different boundaries separately; the script rejects cross-boundary comparisons and implementation comparisons with mismatched rule counts, inputs or client counts.

## 5. Read results and finish

One input saves statistics and plots; multiple compatible inputs add all pairwise comparisons. Each browser page starts with a slide-style **Method / Result / Interpretation** conclusion table, followed by statistics and CSV/JSON downloads. Comparisons show each experiment’s histogram/ECDF separately, then a joint plot; time series follow the same order. After them, each result has a **Warm-up close-up**. Set **Close-up window (%)** and click **Update analysis** (default 10%; any percentage greater than 0 up to 100). For JMH it shows that percentage of shots from each fork, with alternating shades; for component and end-to-end runs it shows that percentage of the configured run duration, including successes and failures. Early samples remain visible regardless of the cutoff; this window does not change statistics. From the shell, add `--closeup-percent 20` to an analysis command. The timestamped `RUN_ID` keeps repeated sessions separate; set a new `RUN_ID` before repeating the commands. Reusing an output filename replaces its data. Analyses created from the dashboard receive unique names.

**Try the warm-up control:** enter a cutoff and click **Update analysis**. One value applies to every compared run. Time series keep all observations and mark the excluded prefix; distributions, statistics and conclusions use only retained observations. HTTP cutoffs are seconds since scenario start (default: zero, including any recorded warm-up). Try `0`, `5` and `10`. JMH cutoffs exclude N recorded single shots **per fork** (try `0`, `10`, `50` or `100`); actual JMH warm-up timings are absent from its JSON. Updates replace the report; raw exports stay unchanged. The same selection is available from the shell with `--warmup-cutoff VALUE`.

For latency, A/B > 1 means B took less time; a ratio of two means 50% less time. JMH comparisons include Welch and Mann–Whitney tests. HTTP comparisons are descriptive: requests from one run are not independent replications. Read successful latency alongside failures and the all-attempt mean. HTTP operations/s uses successful measured-start operations divided by the selected time window, including drain completions. All services and the generator share this container's resources; compare sequential runs on the same laptop, not absolute timings across students.

Leave the container shell with `exit`. Back in your **host terminal**:

```sh
docker stop sqe-lab
```

Resume later with `docker start sqe-lab` and `docker exec -it sqe-lab bash`. If port 8080 is occupied, use `-p 127.0.0.1:8085:8090` when creating the container and browse port 8085. Logs inside the container are in `/var/log/lab/`.
