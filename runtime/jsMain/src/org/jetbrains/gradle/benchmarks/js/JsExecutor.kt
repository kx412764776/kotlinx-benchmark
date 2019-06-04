package org.jetbrains.gradle.benchmarks.js

import org.jetbrains.gradle.benchmarks.*
import kotlin.js.Promise

class JsExecutor(name: String, @Suppress("UNUSED_PARAMETER") dummy_args: Array<out String>) : SuiteExecutor(
    name,
    (process["argv"] as Array<String>).drop(2).toTypedArray()
) {
    private val benchmarkJs: dynamic = require("benchmark")

    override fun run(reporter: BenchmarkReporter, benchmarks: List<BenchmarkDescriptor<Any?>>, complete: () -> Unit) {
        val jsSuite: dynamic = benchmarkJs.Suite()
        jsSuite.on("complete") {
            complete()
        }

        benchmarks.forEach { benchmark ->
            val suite = benchmark.suite
            val jsDescriptor = benchmark as JsBenchmarkDescriptor

            @Suppress("UNCHECKED_CAST")
            val function = benchmark.function
            val instance = suite.factory() // TODO: should we create instance per bench or per suite?

            val asynchronous = if (jsDescriptor.async) {
                @Suppress("UNCHECKED_CAST")
                val promiseFunction = function as Any?.() -> Promise<*>

                jsSuite.add(benchmark.name) { deferred: Promise<Unit> ->
                    // Mind asDynamic: this is **not** a regular promise
                    instance.promiseFunction().then { (deferred.asDynamic()).resolve() }
                }
                true
            } else {
                jsSuite.add(benchmark.name) { instance.function() }
                false
            }

            val jsBenchmark = jsSuite[jsSuite.length - 1] // take back last added benchmark and subscribe to events

            // TODO: Configure properly
            // initCount: The default number of times to execute a test on a benchmark’s first cycle
            // minTime: The time needed to reduce the percent uncertainty of measurement to 1% (secs).
            // maxTime: The maximum time a benchmark is allowed to run before finishing (secs).

            jsBenchmark.options.initCount = suite.warmups
            jsBenchmark.options.minSamples = suite.iterations
            val iterationSeconds = suite.iterationTime.first * suite.iterationTime.second.toSecondsMultiplier()
            jsBenchmark.options.minTime = iterationSeconds
            jsBenchmark.options.maxTime = iterationSeconds
            jsBenchmark.options.async = asynchronous
            jsBenchmark.options.defer = asynchronous

            jsBenchmark.on("start") { event ->
                val benchmarkName = event.target.name as String
                reporter.startBenchmark(executionName, benchmarkName)
                suite.setup(instance)
            }
            var iteration = 0
            jsBenchmark.on("cycle") { event ->
                val target = event.target
                val benchmarkName = target.name as String
                val nanos = (target.times.period as Double) * BenchmarkTimeUnit.SECONDS.toMultiplier()
                val sample = nanos.nanosToText(suite.mode, suite.outputUnit)
                // (${target.cycles} × ${target.count} calls) -- TODO: what's this?
                reporter.output(
                    executionName,
                    benchmarkName,
                    "Iteration #${iteration++}: $sample"
                )
            }
            jsBenchmark.on("complete") { event ->
                suite.teardown(instance)
                val benchmarkFQN = event.target.name
                val stats = event.target.stats
                val samples = stats.sample
                    .unsafeCast<DoubleArray>()
                    .map {
                        val nanos = it * BenchmarkTimeUnit.SECONDS.toMultiplier()
                        nanos.nanosToSample(suite.mode, suite.outputUnit)
                    }
                    .toDoubleArray()
                val result = ReportBenchmarksStatistics.createResult(event.target.name, samples)
                val message = with(result) {
                    "  ~ ${score.sampleToText(suite.mode, suite.outputUnit)} ±${(error / score * 100).formatAtMost(2)}%"
                }
                val error = event.target.error
                if (error == null) {
                    reporter.endBenchmark(
                        executionName,
                        benchmarkFQN,
                        BenchmarkReporter.FinishStatus.Success,
                        message
                    )
                    result(result)
                } else {
                    val stacktrace = error.stack
                    reporter.endBenchmarkException(
                        executionName,
                        benchmarkFQN,
                        error.toString(),
                        stacktrace.toString()
                    )
                }
            }
            Unit
        }
        jsSuite.run()
    }

}

external fun require(module: String): dynamic
private val process = require("process")

