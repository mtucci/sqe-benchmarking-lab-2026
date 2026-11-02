package com.example.orderrisk.benchmark;

import com.example.orderrisk.processing.RiskScorer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Single-shot JMH example for the CPU-bound kernel of risk-processing-service.
 * Each measurement iteration times one invocation (one thread, batch size one).
 * Zero warm-up retains the startup samples for inspecting warm-up within each fork.
 * <p>
 * Run: {@code java -jar benchmark-tools/target/benchmarks.jar RiskScorerBenchmark}
 * <p>
 * Results are returned from the benchmark methods so that JMH consumes them and the JIT
 * compiler cannot remove the computation as dead code. Inputs are built once in {@link #setUp}
 * so their construction is not measured.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0, batchSize = 1)
@Measurement(iterations = 1000, batchSize = 1)
@Fork(5)
@Threads(1)
public class RiskScorerBenchmark {

    /** Number of risk rules; compare with processing.ruleCount in risk-processing-service. */
    @Param({"100000"})
    public int ruleCount;

    @Param({"baseline", "optimized"})
    public String implementation;

    @Param({"risk-rules-v1"})
    public String inputId;

    private RiskScorer.Implementation selected;
    private RiskScorer.Input input;
    private RiskScorer.RuleBook rules;

    @Setup
    public void setUp() {
        if (!"risk-rules-v1".equals(inputId)) throw new IllegalArgumentException("Unknown inputId");
        selected = RiskScorer.Implementation.parse(implementation);
        // Same order and deterministic C-2026 profile as the component and HTTP risk preview.
        input = new RiskScorer.Input(List.of(
                new RiskScorer.Item("SKU-1001", "BOOKS", 2, new BigDecimal("12.90")),
                new RiskScorer.Item("SKU-4002", "ELECTRONICS", 1, new BigDecimal("699.00"))),
                "Main Street 1, Berlin", "DE",
                new RiskScorer.Customer(316, 22, 0, new BigDecimal("377.00"), "SE"));
        rules = RiskScorer.rules(ruleCount);
    }

    /** The complete evaluation as executed for every order: rule selection + scoring + category. */
    @Benchmark
    public RiskScorer.Result evaluate() {
        return RiskScorer.evaluate(input, rules, selected);
    }

}
