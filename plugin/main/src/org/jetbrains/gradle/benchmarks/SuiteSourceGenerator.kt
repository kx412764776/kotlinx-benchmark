package org.jetbrains.gradle.benchmarks

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.annotations.*
import org.jetbrains.kotlin.resolve.constants.*
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.scopes.*
import java.io.*

enum class Platform {
    JS, NATIVE
}

class SuiteSourceGenerator(val title: String, val module: ModuleDescriptor, val output: File, val platform: Platform) {
    companion object {
        val setupFunctionName = "setUp"
        val teardownFunctionName = "tearDown"

        val benchmarkAnnotationFQN = "org.jetbrains.gradle.benchmarks.Benchmark"
        val setupAnnotationFQN = "org.jetbrains.gradle.benchmarks.Setup"
        val teardownAnnotationFQN = "org.jetbrains.gradle.benchmarks.TearDown"
        val stateAnnotationFQN = "org.jetbrains.gradle.benchmarks.State"
        val modeAnnotationFQN = "org.jetbrains.gradle.benchmarks.BenchmarkMode"
        val timeUnitFQN = "org.jetbrains.gradle.benchmarks.BenchmarkTimeUnit"
        val modeFQN = "org.jetbrains.gradle.benchmarks.Mode"
        val timeAnnotationFQN = "org.jetbrains.gradle.benchmarks.OutputTimeUnit"
        val warmupAnnotationFQN = "org.jetbrains.gradle.benchmarks.Warmup"
        val measureAnnotationFQN = "org.jetbrains.gradle.benchmarks.Measurement"

        val mainBenchmarkPackage = "org.jetbrains.gradle.benchmarks.generated"

        val suppressUnusedParameter = AnnotationSpec.builder(Suppress::class).addMember("\"UNUSED_PARAMETER\"").build()
    }

    private val executorType = when (platform) {
        Platform.JS -> ClassName.bestGuess("org.jetbrains.gradle.benchmarks.js.JsExecutor")
        Platform.NATIVE -> ClassName.bestGuess("org.jetbrains.gradle.benchmarks.native.NativeExecutor")
    }

    private val suiteDescriptorType = when (platform) {
        Platform.JS -> ClassName.bestGuess("org.jetbrains.gradle.benchmarks.SuiteDescriptor")
        Platform.NATIVE -> ClassName.bestGuess("org.jetbrains.gradle.benchmarks.SuiteDescriptor")
    }

    private val benchmarkDescriptorType = when (platform) {
        Platform.JS -> ClassName.bestGuess("org.jetbrains.gradle.benchmarks.js.JsBenchmarkDescriptor")
        Platform.NATIVE -> ClassName.bestGuess("org.jetbrains.gradle.benchmarks.native.NativeBenchmarkDescriptor")
    }

    val benchmarks = mutableListOf<ClassName>()

    fun generate() {
        processPackage(module, module.getPackage(FqName.ROOT))
        generateRunnerMain()
    }

    private fun generateRunnerMain() {
        val file = FileSpec.builder(mainBenchmarkPackage, "BenchmarkSuite").apply {
            function("main") {
                val array = ClassName("kotlin", "Array")
                val arrayOfStrings = array.parameterizedBy(WildcardTypeName.producerOf(String::class))
                addParameter("args", arrayOfStrings)
                addStatement("val executor = %T(%S, args)", executorType, title)
                for (benchmark in benchmarks) {
                    addStatement("executor.suite(%T.describe())", benchmark)
                }
                addStatement("executor.run()")
            }
        }.build()
        file.writeTo(output)
    }

    private fun processPackage(module: ModuleDescriptor, packageView: PackageViewDescriptor) {
        for (packageFragment in packageView.fragments.filter { it.module == module }) {
            DescriptorUtils.getAllDescriptors(packageFragment.getMemberScope())
                .filterIsInstance<ClassDescriptor>()
                .filter { it.annotations.any { it.fqName.toString() == stateAnnotationFQN } }
                .filter { it.modality != Modality.ABSTRACT }
                .forEach {
                    generateBenchmark(it)
                }
        }

        for (subpackageName in module.getSubPackagesOf(packageView.fqName, MemberScope.ALL_NAME_FILTER)) {
            processPackage(module, module.getPackage(subpackageName))
        }
    }

    private fun generateBenchmark(original: ClassDescriptor) {
        val originalPackage = original.fqNameSafe.parent()
        val originalName = original.fqNameSafe.shortName()
        val originalClass = ClassName(originalPackage.toString(), originalName.toString())

        val benchmarkPackageName = originalPackage.child(Name.identifier("generated")).toString()
        val benchmarkName = originalName.toString() + "_Descriptor"
        val benchmarkClass = ClassName(mainBenchmarkPackage, benchmarkName)

        val functions = DescriptorUtils.getAllDescriptors(original.unsubstitutedMemberScope)
            .filterIsInstance<FunctionDescriptor>()

        val measureAnnotation = original.annotations.singleOrNull { it.fqName.toString() == measureAnnotationFQN }
        val warmupAnnotation = original.annotations.singleOrNull { it.fqName.toString() == warmupAnnotationFQN }
        val timeAnnotation = original.annotations.singleOrNull { it.fqName.toString() == timeAnnotationFQN }
        val modeAnnotation = original.annotations.singleOrNull { it.fqName.toString() == modeAnnotationFQN }

        val timeUnitValue = timeAnnotation?.argumentValue("value") as? EnumValue
        val timeUnit = timeUnitValue?.enumEntryName?.toString() ?: "SECONDS"
        
        val modesValue = modeAnnotation?.argumentValue("value")?.value as? List<EnumValue>
        val mode = modesValue?.single()?.enumEntryName?.toString() ?: "AverageTime"
        
        val measureIterations = measureAnnotation?.argumentValue("iterations")?.value as? Int
        val measureIterationTime = measureAnnotation?.argumentValue("time")?.value as? Int
        val measureIterationTimeUnit = measureAnnotation?.argumentValue("timeUnit")?.value as? EnumValue

        val warmupIterations = warmupAnnotation?.argumentValue("iterations")?.value as? Int
        val warmupIterationTime = warmupAnnotation?.argumentValue("time")?.value as? Int
        val warmupIterationTimeUnit = warmupAnnotation?.argumentValue("timeUnit")?.value as? EnumValue

        val iterations = measureIterations ?: 3
        val iterationTime = measureIterationTime ?: 1
        val iterationTimeUnit = measureIterationTimeUnit?.enumEntryName?.toString() ?: "SECONDS"
        val warmups = warmupIterations ?: 3
        val warmupTime = warmupIterationTime ?: 1
        val warmupTimeUnit = warmupIterationTimeUnit?.enumEntryName?.toString() ?: "SECONDS"

        val benchmarkFunctions =
            functions.filter { it.annotations.any { it.fqName.toString() == benchmarkAnnotationFQN } }

        val setupFunctions = functions
            .filter { it.annotations.any { it.fqName.toString() == setupAnnotationFQN } }

        val teardownFunctions = functions
            .filter { it.annotations.any { it.fqName.toString() == teardownAnnotationFQN } }.reversed()

        val file = FileSpec.builder(mainBenchmarkPackage, benchmarkName).apply {
            declareObject(benchmarkClass) {
                addAnnotation(suppressUnusedParameter)

                function(setupFunctionName) {
                    addModifiers(KModifier.PRIVATE)
                    addParameter("instance", originalClass)
                    for (fn in setupFunctions) {
                        val functionName = fn.name.toString()
                        addStatement("instance.%N()", functionName)
                    }
                }

                function(teardownFunctionName) {
                    addModifiers(KModifier.PRIVATE)
                    addParameter("instance", originalClass)
                    for (fn in teardownFunctions) {
                        val functionName = fn.name.toString()
                        addStatement("instance.%N()", functionName)
                    }
                }

                val timeUnitClass = ClassName.bestGuess(timeUnitFQN)
                val modeClass = ClassName.bestGuess(modeFQN)
                
                function("describe") {
                    returns(suiteDescriptorType.parameterizedBy(originalClass))
                    addStatement(
                        """
val descriptor = %T(
name = %S,
factory = ::%T,
setup = ::%N,
teardown = ::%N,
iterations = $iterations,
warmups = $warmups,
iterationTime = $iterationTime to %T.%N,
outputUnit = %T.%N,
mode = %T.%N
)
""",
                        suiteDescriptorType,
                        originalName,
                        originalClass,
                        setupFunctionName,
                        teardownFunctionName,
                        timeUnitClass,
                        MemberName(timeUnitClass, iterationTimeUnit),
                        timeUnitClass,
                        MemberName(timeUnitClass, timeUnit),
                        modeClass,
                        MemberName(modeClass, mode)
                    )
                    addStatement("")
                    for (fn in benchmarkFunctions) {
                        val functionName = fn.name.toString()
                        addStatement(
                            "descriptor.add(%T(%S, descriptor, %T::%N))",
                            benchmarkDescriptorType,
                            "${originalClass.canonicalName}.$functionName",
                            originalClass,
                            functionName
                        )
                    }
                    addStatement("return descriptor")
                }

            }
            benchmarks.add(benchmarkClass)
        }.build()

        file.writeTo(output)
    }
}

inline fun codeBlock(builderAction: CodeBlock.Builder.() -> Unit): CodeBlock {
    return CodeBlock.builder().apply(builderAction).build()
}

inline fun FileSpec.Builder.declareObject(name: ClassName, builderAction: TypeSpec.Builder.() -> Unit): TypeSpec {
    return TypeSpec.objectBuilder(name).apply(builderAction).build().also {
        addType(it)
    }
}

inline fun FileSpec.Builder.declareClass(name: String, builderAction: TypeSpec.Builder.() -> Unit): TypeSpec {
    return TypeSpec.classBuilder(name).apply(builderAction).build().also {
        addType(it)
    }
}

inline fun FileSpec.Builder.declareClass(name: ClassName, builderAction: TypeSpec.Builder.() -> Unit): TypeSpec {
    return TypeSpec.classBuilder(name).apply(builderAction).build().also {
        addType(it)
    }
}

inline fun TypeSpec.Builder.property(
    name: String,
    type: ClassName,
    builderAction: PropertySpec.Builder.() -> Unit
): PropertySpec {
    return PropertySpec.builder(name, type).apply(builderAction).build().also {
        addProperty(it)
    }
}

inline fun TypeSpec.Builder.function(
    name: String,
    builderAction: FunSpec.Builder.() -> Unit
): FunSpec {
    return FunSpec.builder(name).apply(builderAction).build().also {
        addFunction(it)
    }
}

inline fun FileSpec.Builder.function(
    name: String,
    builderAction: FunSpec.Builder.() -> Unit
): FunSpec {
    return FunSpec.builder(name).apply(builderAction).build().also {
        addFunction(it)
    }
}
